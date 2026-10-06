package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.ListItemAdapter

/**
 * 广播触发器的自绘编辑器：三个字符串列表的增删。
 *
 * 设计文档：`docs/fork/broadcast-trigger-design.md` §4 / §7。
 *
 * ## 为什么用「列表 + 增删」而不是一个文本框
 *
 * 用户常需多条 action，且需要逐条增删。单行文本（逗号分隔）既不可读、
 * 也无法表达「这一条要删掉」。本仓库既有先例：`AppPackageTriggerModule.packageNames`
 * 用的是同一个 [ListItemAdapter]。
 *
 * ## ⚠️ 三个列表**各自一个 RecyclerView**，不复用通用布局
 *
 * `partial_list_editor.xml` 的 id 是 `recycler_view_list` ——
 * 同一个宿主布局里三处同名会让 `findViewById` 全部拿到第一个
 * （表现是「三个区块显示同一份数据」且不报错）。
 *
 * ## ⚠️ 不用 `createTextInputLayout` / `createRichTextEditor`
 *
 * - 前者是普通文本框，会丢掉「这是一个列表」的交互与可读性；
 * - 后者面向**接受变量**的文本参数，而本模块三个列表
 *   `acceptsMagicVariable = false`（action/scheme/category 是字面量标识，
 *   且 `IntentFilter` 在工作流执行之前就注册好了，运行期解析没有意义）。
 *   ⇒ 列表项的魔棒按钮**整体隐藏**（`showMagicControls = false`）。
 */
class BroadcastTriggerUIProvider : ModuleUIProvider {

    override fun getHandledInputIds(): Set<String> = setOf(
        BroadcastTriggerModule.PARAM_ACTIONS,
        BroadcastTriggerModule.PARAM_DATA_SCHEMES,
        BroadcastTriggerModule.PARAM_CATEGORIES,
    )

    private class ViewHolder(
        view: View,
        val actionsAdapter: ListItemAdapter,
        val schemesAdapter: ListItemAdapter,
        val categoriesAdapter: ListItemAdapter,
    ) : CustomEditorViewHolder(view)

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.partial_broadcast_trigger_editor, parent, false)

        // ⚠️ 三个回调（onItemAdded / onItemRemoved / onItemChanged）**必须**接上
        // onParametersChanged —— 它们是空 lambda 的默认值。
        // 不接的后果**不致命**（保存时 ActionEditorSheet 走 readFromEditor → getItems()，
        // 数据源头是 adapter 的 data，仍是最新的），但**编辑器的摘要不会实时刷新**，
        // 用户会以为没生效。
        val actionsAdapter = listAdapter(
            currentParameters[BroadcastTriggerModule.PARAM_ACTIONS], allSteps, onParametersChanged
        )
        val schemesAdapter = listAdapter(
            currentParameters[BroadcastTriggerModule.PARAM_DATA_SCHEMES], allSteps, onParametersChanged
        )
        val categoriesAdapter = listAdapter(
            currentParameters[BroadcastTriggerModule.PARAM_CATEGORIES], allSteps, onParametersChanged
        )

        val holder = ViewHolder(view, actionsAdapter, schemesAdapter, categoriesAdapter)

        bind(view, R.id.rv_broadcast_actions, R.id.button_broadcast_add_action, context, actionsAdapter)
        bind(view, R.id.rv_broadcast_schemes, R.id.button_broadcast_add_scheme, context, schemesAdapter)
        bind(
            view, R.id.rv_broadcast_categories, R.id.button_broadcast_add_category,
            context, categoriesAdapter,
        )

        return holder
    }

    /**
     * ## ⚠️ 这里**不做运行时归一化**（不丢 `"*"`）
     *
     * 读回的值要**原样**交给 [BroadcastTriggerModule.validate] ——
     * 由它拦下 `"*"` 并**给出文案**。若在这里静默丢掉，
     * 用户输入 `"*"` 后点保存会看到那一项凭空消失、既没报错也不知道为什么。
     *
     * 真正的运行时防护在 [BroadcastTriggerSupport.normalizeActions]（Handler 侧），
     * 它覆盖的路径比本方法宽得多（JSON 导入 / 直接改 prefs）。
     */
    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        return mapOf(
            BroadcastTriggerModule.PARAM_ACTIONS to
                BroadcastTriggerSupport.stripBlank(h.actionsAdapter.getItems()),
            BroadcastTriggerModule.PARAM_DATA_SCHEMES to
                BroadcastTriggerSupport.stripBlank(h.schemesAdapter.getItems()),
            BroadcastTriggerModule.PARAM_CATEGORIES to
                BroadcastTriggerSupport.stripBlank(h.categoriesAdapter.getItems()),
        )
    }

    private fun listAdapter(
        raw: Any?,
        allSteps: List<ActionStep>?,
        onParametersChanged: () -> Unit,
    ): ListItemAdapter {
        val items = BroadcastTriggerSupport.stringListOf(raw).toMutableList()
        return ListItemAdapter(
            data = items,
            allSteps = allSteps,
            // 不接受变量的列表，整体隐藏魔棒（含该列占位，布局不受影响）
            showMagicControls = false,
            onItemAdded = { onParametersChanged() },
            onItemRemoved = { onParametersChanged() },
            onItemChanged = { _, _ -> onParametersChanged() },
            // ⚠️ onMagicClick 是**必填参数**（没有默认值）—— 不传编译不过
            onMagicClick = {},
        )
    }

    private fun bind(
        root: View,
        recyclerId: Int,
        addButtonId: Int,
        context: Context,
        adapter: ListItemAdapter,
    ) {
        root.findViewById<RecyclerView>(recyclerId).apply {
            layoutManager = LinearLayoutManager(context)
            // 列表通常只有几项，禁掉自身滚动，让外层 sheet 的滚动接管
            isNestedScrollingEnabled = false
            this.adapter = adapter
        }
        root.findViewById<Button>(addButtonId).setOnClickListener { adapter.addItem() }
    }
}
