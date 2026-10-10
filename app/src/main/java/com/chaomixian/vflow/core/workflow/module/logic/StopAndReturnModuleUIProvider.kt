// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/StopAndReturnModuleUIProvider.kt
// 描述：「停止并返回」模块的自定义编辑器。负责 `return_keys`（返回字典的键声明）的增删改。
//
// 这是 fork 新增文件（上游无此文件）。
//
// 显示判据（设计文档决策 #7）：**只在该 return 点的 `value` 现场推导出「字典」时才显示编辑器**；
// 否则渲染一行提示（不撒谎、不显示一个「声明了也没用」的列表）。
//
// ⚠️ 取证的已知边界：手打文本改 `value` 不会即时刷新（ActionEditorSheet 的
//    bindImmediateDynamicInputUpdates 只绑 BOOLEAN/SWITCH）；打开 sheet 与 🪄 选变量
//    都会重渲染。主场景 `value = {{js步骤.outputs}}` 只能靠 🪄 得到，不受影响。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionSignatureHelper
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import com.google.android.material.button.MaterialButton

class StopAndReturnModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val container: LinearLayout = view as LinearLayout
        var keys: MutableList<ReturnKey> = mutableListOf()
        var onParametersChanged: (() -> Unit)? = null
        var render: (() -> Unit)? = null
    }

    override fun getHandledInputIds(): Set<String> = setOf(FunctionSignatureHelper.RETURN_KEYS_KEY)

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        val holder = ViewHolder(container)
        holder.onParametersChanged = onParametersChanged
        loadKeys(holder, currentParameters[FunctionSignatureHelper.RETURN_KEYS_KEY])

        val value = currentParameters["value"]
        val derivedType = FunctionSignatureHelper.resolveValueType(value, allSteps.orEmpty())

        fun onEditorSaved() {
            onParametersChanged()
            holder.render?.invoke()
        }

        fun render() {
            container.removeAllViews()
            holder.render = ::render

            if (derivedType != VTypeRegistry.DICTIONARY.id) {
                // 类型非字典 ⇒ 只给一行解释，不渲染一个「声明了调用方也看不到」的列表
                container.addView(hintLabel(context, context.getString(R.string.editor_return_keys_not_dictionary)))
                return
            }

            container.addView(sectionLabel(context, context.getString(R.string.editor_return_keys_section)))

            if (holder.keys.isEmpty()) {
                container.addView(hintLabel(context, context.getString(R.string.editor_return_keys_empty)))
            } else {
                val recycler = RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = ReturnKeyListAdapter(
                        context = context,
                        keys = holder.keys,
                        onEdit = { index -> ReturnKeyEditorSheet.show(context, holder.keys[index], holder.keys, ::onEditorSaved) },
                        onDelete = { index ->
                            if (index in holder.keys.indices) {
                                holder.keys.removeAt(index)
                                onParametersChanged()
                                holder.render?.invoke()
                            }
                        }
                    )
                }
                container.addView(recycler)
            }

            val addBtn = LayoutInflater.from(context)
                .inflate(R.layout.view_define_function_add_param_button, container, false) as MaterialButton
            addBtn.setText(R.string.editor_return_keys_add)
            addBtn.setOnClickListener {
                ReturnKeyEditorSheet.show(context, null, holder.keys, ::onEditorSaved)
            }
            container.addView(addBtn)
        }

        render()
        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        val cleaned = h.keys.filter { it.name.isNotBlank() }
        return mapOf(
            FunctionSignatureHelper.RETURN_KEYS_KEY to cleaned.map {
                mapOf("name" to it.name, "type" to it.type)
            }
        )
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null

    @Suppress("UNCHECKED_CAST")
    private fun loadKeys(holder: ViewHolder, raw: Any?) {
        val list = raw as? List<*> ?: return
        holder.keys = list.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = (map["type"] as? String)?.takeIf { it.isNotBlank() } ?: VTypeRegistry.ANY.id
            ReturnKey(name = name, type = type)
        }.toMutableList()
    }

    private fun sectionLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
    }

    private fun hintLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(context.getColor(android.R.color.darker_gray))
            setPadding(0, 4, 0, 4)
        }
    }
}

/**
 * 「返回键」列表的卡片式适配器（复用 item_function_param 行布局）。
 * 每行：键名 + 类型 + 编辑 / 删除（隐藏布局里的「必填」标签 —— 返回键没有必填概念）。
 */
class ReturnKeyListAdapter(
    private val context: Context,
    private val keys: List<ReturnKey>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<ReturnKeyListAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val name: TextView = view.findViewById(R.id.text_param_name)
        val type: TextView = view.findViewById(R.id.text_param_type)
        val required: TextView = view.findViewById(R.id.text_param_required)
        val editBtn: android.widget.ImageButton = view.findViewById(R.id.button_edit_param)
        val deleteBtn: android.widget.ImageButton = view.findViewById(R.id.button_delete_param)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_function_param, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val key = keys[position]
        holder.name.text = key.name
        holder.type.text = VTypeRegistry.getType(key.type).getLocalizedName(context)
        holder.required.visibility = View.GONE
        holder.editBtn.setOnClickListener { onEdit(position) }
        holder.deleteBtn.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount() = keys.size
}
