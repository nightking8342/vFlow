// 文件: main/java/com/chaomixian/vflow/core/workflow/module/logic/SwitchEditorSheet.kt
// 描述: Switch 块的**分支管理 sheet**（方案 C —— 所有分支在起始卡片的 sheet 里集中管理）。
//      上段：Switch 自己的 `value` 输入 + 魔法变量按钮；
//      下段：分支列表（行序 = 执行顺序；拖拽手柄 + 类型标签 + 匹配值输入 + 🪄 + 🗑）；
//      底部：＋添加分支 / ＋添加默认分支 / 取消 / 保存。
//
// 设计文档: docs/fork/switch-module-design.md §2.4（全套）/ §2.5（Default 锁定在末尾）
// UI 原型:   docs/fork/switch-module-ui.html
//
// ⚠️⚠️ 两条硬约束（写死在这里，改的时候别动）：
//  ① **「取消」不产生任何副作用** —— 所有增删改只动 sheet 内的 `workingBranches` 副本，
//     不碰 `actionSteps`。落盘只发生在「保存」那一次回调里。
//  ② **「保存」= 一次原子操作**（`pushUndoSnapshot()` → 写 `branches`/`value` →
//     `reconcileBranches` → `recalculateAndNotify()`），由宿主 Activity 执行；
//     本 sheet 只负责把「校验过的结果」交出去。
package com.chaomixian.vflow.core.workflow.module.logic

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.ui.workflow_editor.StandardControlFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputLayout
import java.util.UUID

/**
 * 分支管理 sheet。
 *
 * ⚠️ `newInstance` **刻意不走 `arguments` Bundle**（`SwitchBranch` 既非 `Parcelable`
 * 也非 `Serializable`，塞 Bundle 会在运行期崩溃或静默丢值）。形态照
 * `DefineFunctionParamEditorSheet`：先建实例、字段直赋、再 `show()`（同进程内）。
 */
class SwitchEditorSheet : BottomSheetDialogFragment() {

    /** 保存回调。参数 1 = Switch 的 value（原样字符串，可含 `{{...}}`）；参数 2 = 新顺序与新值的分支表。 */
    var onSave: ((value: String, branches: List<SwitchBranch>) -> Unit)? = null

    /**
     * 请求宿主打开魔法变量选择器。
     *
     * @param targetInputId `SWITCH_VALUE_KEY`（上段）或 `SWITCH_MATCH_KEY`（某条 Case）
     * @param currentText   该输入框当前文本（作为选择器的初始值）
     * @param onPicked      用户选中/清除后的回调；`null` 表示清除（写空串）
     */
    var onMagicVariableRequested: ((targetInputId: String, currentText: String, onPicked: (String?) -> Unit) -> Unit)? = null

    private var initialValue: String = ""
    private var initialBranches: List<SwitchBranch> = emptyList()

    /** ⚠️ 只改它 —— 它就是「取消不产生副作用」的全部实现。 */
    private val workingBranches = mutableListOf<SwitchBranch>()

    private var valueField: TextInputLayout? = null
    private lateinit var branchesAdapter: BranchAdapter
    private lateinit var branchHeader: TextView
    private lateinit var addDefaultButton: MaterialButton
    private var itemTouchHelper: ItemTouchHelper? = null

    companion object {
        /**
         * ⚠️ **只有两个参数** —— `allSteps` 不进这里：魔法变量选择器需要它时，
         * 由宿主在 `onMagicVariableRequested` 的闭包里捕获（闭包在 Activity 内，
         * `getAllEditableSteps()` 直接可用）。
         */
        fun newInstance(value: String, branches: List<SwitchBranch>): SwitchEditorSheet =
            SwitchEditorSheet().apply {
                this.initialValue = value
                this.initialBranches = branches
            }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? = inflater.inflate(R.layout.sheet_switch_editor, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        workingBranches.clear()
        workingBranches.addAll(initialBranches)

        val valueContainer: LinearLayout = view.findViewById(R.id.container_switch_value)
        val valueMagic: ImageButton = view.findViewById(R.id.button_switch_value_magic)
        val branchesRecycler: RecyclerView = view.findViewById(R.id.recycler_switch_branches)
        val addCaseButton: MaterialButton = view.findViewById(R.id.button_add_branch)
        val cancelButton: MaterialButton = view.findViewById(R.id.button_switch_sheet_cancel)
        val saveButton: MaterialButton = view.findViewById(R.id.button_switch_sheet_save)
        branchHeader = view.findViewById(R.id.text_switch_branch_header)
        addDefaultButton = view.findViewById(R.id.button_add_default_branch)

        // ── 上段：value 输入（纯文本框，`{{...}}` 原样显示 —— 可点 🪄 换变量）──
        val valueInput = StandardControlFactory.createTextInputLayout(
            context = context,
            isNumber = false,
            currentValue = initialValue,
            hint = context.getString(R.string.sheet_switch_value_hint)
        )
        valueContainer.addView(valueInput)
        valueField = valueInput
        valueMagic.setOnClickListener {
            onMagicVariableRequested?.invoke(
                SWITCH_VALUE_KEY,
                valueInput.editText?.text?.toString().orEmpty()
            ) { picked ->
                valueInput.editText?.setText(picked.orEmpty())
            }
        }

        // ── 下段：分支列表 ──
        branchesAdapter = BranchAdapter(workingBranches)
        branchesRecycler.layoutManager = LinearLayoutManager(context)
        branchesRecycler.adapter = branchesAdapter
        itemTouchHelper = ItemTouchHelper(branchDragCallback()).also {
            it.attachToRecyclerView(branchesRecycler)
        }

        // ⚠️ 「添加分支」必须插到 **Default 之前** —— Default 永远排在所有 Case 之后（设计文档 §2.5）。
        //    直接 add 到末尾的话 Default 在末尾时新 Case 会落到它后面。
        addCaseButton.setOnClickListener {
            val defaultIndex = workingBranches.indexOfFirst { it.match == null }
            val insertAt = if (defaultIndex >= 0) defaultIndex else workingBranches.size
            workingBranches.add(insertAt, SwitchBranch(UUID.randomUUID().toString(), ""))
            branchesAdapter.notifyItemInserted(insertAt)
            refreshHeaderAndButtons()
        }

        addDefaultButton.setOnClickListener {
            if (workingBranches.any { it.match == null }) return@setOnClickListener
            val insertAt = workingBranches.size
            workingBranches.add(insertAt, SwitchBranch(UUID.randomUUID().toString(), null))
            branchesAdapter.notifyItemInserted(insertAt)
            refreshHeaderAndButtons()
        }

        // ⚠️ 「取消」= 只 dismiss，不做任何回调、不产生任何副作用。
        cancelButton.setOnClickListener { dismiss() }

        saveButton.setOnClickListener {
            // ⚠️ 先收「尚未失焦」的输入框文本（用户可能输入后立刻点保存）。
            branchesAdapter.commitPendingText()
            val validation = SwitchBlockSupport.validateBranches(workingBranches)
            if (!validation.isValid) {
                Toast.makeText(context, validation.errorMessage.orEmpty(), Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            onSave?.invoke(
                valueField?.editText?.text?.toString().orEmpty(),
                workingBranches.toList()
            )
            dismiss()
        }

        refreshHeaderAndButtons()
    }

    override fun onDestroyView() {
        itemTouchHelper?.attachToRecyclerView(null)
        itemTouchHelper = null
        valueField = null
        super.onDestroyView()
    }

    private fun refreshHeaderAndButtons() {
        branchHeader.text = getString(R.string.sheet_switch_branch_header, workingBranches.size)
        // Default 最多一张（设计文档 §2.5）：已有时置灰，删掉后复亮。
        addDefaultButton.isEnabled = workingBranches.none { it.match == null }
    }

    /**
     * 拖拽排序（设计文档 §2.5 / §7.2）：
     * - 只有 `⠿` 手柄能起拖（`isLongPressDragEnabled() = false`）；
     * - **Default 行自己不能被拖**（`getMovementFlags` 返回 0）；
     * - **别人也不能拖到 Default 之后**（`onMove` 拦截）。
     *
     * ⚠️ 三个基准（被拖项、目标项、Default 位置）**必须每帧重算** ——
     * 拖动中 `removeAt/add` 之后 `defaultIndex` 会漂，缓存一次的实现会「拖到 Default 之后仍然插进去」。
     */
    private fun branchDragCallback() = object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN,
        0
    ) {
        override fun isLongPressDragEnabled(): Boolean = false

        override fun getMovementFlags(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder
        ): Int {
            val pos = viewHolder.bindingAdapterPosition
            if (pos == RecyclerView.NO_POSITION) return 0
            // ⚠️ 判据用 `match == null`（sheet 操作的是 SwitchBranch，没有 moduleId）
            if (workingBranches.getOrNull(pos)?.match == null) return 0
            return super.getMovementFlags(recyclerView, viewHolder)
        }

        override fun onMove(
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            target: RecyclerView.ViewHolder
        ): Boolean {
            val from = viewHolder.bindingAdapterPosition
            val to = target.bindingAdapterPosition
            if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
            if (from !in workingBranches.indices || to !in workingBranches.indices) return false
            // ⚠️ 每帧重算：被拖项是不是 Default
            if (workingBranches[from].match == null) return false
            // ⚠️ 每帧重算：Default 的当前位置；不允许跨过它
            val defaultIndex = workingBranches.indexOfFirst { it.match == null }
            if (defaultIndex != -1 && to >= defaultIndex) return false
            workingBranches.add(to, workingBranches.removeAt(from))
            branchesAdapter.notifyItemMoved(from, to)
            return true
        }

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit
    }

    private inner class BranchHolder(view: View) : RecyclerView.ViewHolder(view) {
        val grip: ImageView = view.findViewById(R.id.branch_grip)
        val tag: TextView = view.findViewById(R.id.branch_tag)
        val matchContainer: LinearLayout = view.findViewById(R.id.container_branch_match)
        val magic: ImageButton = view.findViewById(R.id.branch_magic)
        val remove: ImageButton = view.findViewById(R.id.branch_remove)

        /**
         * 本 holder 当前绑定的分支 id。
         *
         * ⚠️ 存在的理由：`boundInputs` 是「caseId → 输入框」的映射，而 holder 被回收后
         * 那个输入框会被**复用给别的分支** —— 不按 holder 归属清理的话，
         * `commitPendingText()` 会把复用后那个分支的文本写进**已不在屏幕上的**分支里。
         */
        var boundCaseId: String? = null
    }

    private inner class BranchAdapter(
        private val items: MutableList<SwitchBranch>
    ) : RecyclerView.Adapter<BranchHolder>() {

        /** 当前**已绑定**的行输入框（`caseId` → 控件）。用户可能输入后未失焦就点保存。 */
        private val boundInputs = mutableMapOf<String, TextInputLayout>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BranchHolder =
            BranchHolder(
                LayoutInflater.from(parent.context)
                    .inflate(R.layout.item_switch_branch, parent, false)
            )

        @SuppressLint("ClickableViewAccessibility")
        override fun onBindViewHolder(holder: BranchHolder, position: Int) {
            val branch = items.getOrNull(position) ?: return
            val context = holder.itemView.context
            val isDefault = branch.match == null
            holder.boundCaseId = branch.id

            holder.tag.text = context.getString(
                if (isDefault) R.string.sheet_switch_branch_tag_default
                else R.string.sheet_switch_branch_tag_case
            )

            // ── 匹配值输入框 ──
            holder.matchContainer.removeAllViews()
            boundInputs.remove(branch.id)
            val input = StandardControlFactory.createTextInputLayout(
                context = context,
                isNumber = false,
                currentValue = branch.match.orEmpty(),
                hint = context.getString(
                    if (isDefault) R.string.sheet_switch_no_match_hint
                    else R.string.sheet_switch_case_hint
                )
            )
            // Default 没有匹配值 ⇒ 输入框禁用（原型同款：disabled + 「（无匹配值）」placeholder）
            input.editText?.isEnabled = !isDefault
            input.editText?.doAfterTextChanged { text ->
                val index = items.indexOfFirst { it.id == branch.id }
                if (index >= 0) {
                    items[index] = items[index].copy(match = text?.toString().orEmpty())
                }
            }
            holder.matchContainer.addView(input)
            if (!isDefault) boundInputs[branch.id] = input

            holder.magic.visibility = if (isDefault) View.GONE else View.VISIBLE
            holder.magic.setOnClickListener {
                val current = input.editText?.text?.toString().orEmpty()
                onMagicVariableRequested?.invoke(SWITCH_MATCH_KEY, current) { picked ->
                    // ⚠️ 回调时**按 caseId 重新解析索引** —— 选择器打开期间用户可能拖动了行，
                    //    捕获 position 会把值写到别的分支上。
                    val index = items.indexOfFirst { it.id == branch.id }
                    if (index < 0) return@invoke
                    items[index] = items[index].copy(match = picked.orEmpty())
                    boundInputs[branch.id]?.editText?.setText(picked.orEmpty())
                }
            }

            // ── 🗑：只删 sheet 里的临时副本（真正落盘在保存那一步）──
            holder.remove.setOnClickListener {
                val index = items.indexOfFirst { it.id == branch.id }
                if (index < 0) return@setOnClickListener
                items.removeAt(index)
                notifyItemRemoved(index)
                refreshHeaderAndButtons()
            }

            // ── 手柄起拖：ItemTouchHelper 的 startDrag 只需 ACTION_DOWN 触发一次 ──
            holder.grip.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper?.startDrag(holder)
                }
                false // ⚠️ 不消费事件
            }
        }

        override fun onViewRecycled(holder: BranchHolder) {
            super.onViewRecycled(holder)
            // ⚠️ 按 holder 的归属清理 —— 不能按「items 里还有没有这个 id」清：
            //    holder 被回收后那个输入框会被复用给别的分支，若映射里仍留着旧 id，
            //    `commitPendingText()` 会把**复用后那个分支的文本**写进已不在屏幕上的分支里
            //    （静默改值，且改的是用户看不见的那一条）。
            holder.boundCaseId?.let { boundInputs.remove(it) }
            holder.boundCaseId = null
        }

        override fun getItemCount(): Int = items.size

        /**
         * 把「已绑定但可能还没失焦」的输入框文本收进 `items`。
         *
         * ⚠️ 只依赖 `doAfterTextChanged` 是不够的：用户输入后**立刻点保存**时，
         * `TextInputEditText` 仍是焦点、`doAfterTextChanged` 可能尚未跑完最后一拍。
         */
        fun commitPendingText() {
            boundInputs.forEach { (caseId, layout) ->
                val index = items.indexOfFirst { it.id == caseId }
                if (index < 0) return@forEach
                if (items[index].match == null) return@forEach // Default 没有匹配值
                val text = layout.editText?.text?.toString().orEmpty()
                items[index] = items[index].copy(match = text)
            }
        }
    }
}
