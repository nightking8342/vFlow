// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/DefineFunctionModuleUIProvider.kt
// 描述：「定义函数」模块的自定义编辑器。负责参数列表的增删改（决策 21：列表新做，编辑框复用标准控件）。
//      配置写回 step.parameters["functionParams"]（JSON 字符串），由 WorkflowManager 在保存时聚合成 FunctionSignature。
//      这是 fork 新增文件（上游无此文件）。
//
// 设计（决策 4.1/4.2/11.1/11.2）：
//  - 参数列表行：参数名 + 类型徽标 + 必填/可选 + 默认值预览 + 编辑/删除。
//  - 点击「+ 添加参数」或某一行，弹出 DefineFunctionParamEditorSheet 进行参数的增删改。
//  - 返回值区：只读展示（来自 Workflow.functionSignature.returnDef，保存时由 WorkflowManager 派生）。

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
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionReturn
import com.google.android.material.button.MaterialButton
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DefineFunctionModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val container: LinearLayout = view as LinearLayout
        var params: MutableList<FunctionParam> = mutableListOf()
        var returnDef: FunctionReturn? = null
        var onParametersChanged: (() -> Unit)? = null
        var allSteps: List<ActionStep>? = null
        var render: (() -> Unit)? = null
    }

    private val gson = Gson()
    private val paramsType = object : TypeToken<List<FunctionParam>>() {}.type

    override fun getHandledInputIds(): Set<String> = setOf("functionParams")

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
        holder.allSteps = allSteps
        loadParams(holder, currentParameters["functionParams"])
        holder.returnDef = lookupReturnDef(context)

        // 编辑弹窗保存后：通知外部参数变化 + 立即刷新本列表（问题 B：参数列表动态更新，不需要手动保存工作流）
        fun onEditorSaved() {
            onParametersChanged()
            holder.render?.invoke()
        }

        fun render() {
            container.removeAllViews()
            holder.render = ::render

            // 标题：函数参数
            container.addView(sectionLabel(context, context.getString(R.string.editor_define_function_section_params)))

            // 参数列表（卡片式，参照 item_dictionary_kv 风格）
            if (holder.params.isEmpty()) {
                container.addView(subLabel(context, context.getString(R.string.summary_vflow_logic_define_function_no_signature)))
            } else {
                val recycler = RecyclerView(context).apply {
                    layoutManager = LinearLayoutManager(context)
                    adapter = FunctionParamListAdapter(
                        context = context,
                        params = holder.params,
                        onEdit = { index -> DefineFunctionParamEditorSheet.show(context, holder.params[index], holder.params, ::onEditorSaved) },
                        onDelete = { index ->
                            if (index in holder.params.indices) {
                                holder.params.removeAt(index)
                                onParametersChanged()
                                holder.render?.invoke()
                            }
                        }
                    )
                }
                container.addView(recycler)
            }

            // 添加按钮（统一项目风格：TextButton 胶囊 + ic_add 图标）
            val addBtn = LayoutInflater.from(context)
                .inflate(R.layout.view_define_function_add_param_button, container, false) as MaterialButton
            addBtn.setOnClickListener {
                DefineFunctionParamEditorSheet.show(context, null, holder.params, ::onEditorSaved)
            }
            container.addView(addBtn)

            // 返回值配置区（只读）
            container.addView(sectionLabel(context, context.getString(R.string.editor_define_function_section_return)))
            val returnDef = holder.returnDef
            val returnText = if (returnDef != null && returnDef.keys.isNotEmpty()) {
                context.getString(R.string.summary_vflow_logic_define_function_return_prefix) + ": {" +
                    returnDef.keys.joinToString(", ") { it.name } + "}"
            } else {
                context.getString(R.string.editor_define_function_return_none)
            }
            container.addView(TextView(context).apply {
                text = returnText
                textSize = 13f
                setPadding(0, 4, 0, 0)
                setTextColor(context.getColor(android.R.color.darker_gray))
            })
        }

        render()
        return holder
    }

    private fun sectionLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
            setPadding(0, 12, 0, 4)
        }
    }

    private fun subLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 13f
            setTextColor(context.getColor(android.R.color.darker_gray))
            setPadding(0, 4, 0, 4)
        }
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        val cleaned = h.params.filter { it.name.isNotBlank() }
        return mapOf("functionParams" to gson.toJson(cleaned))
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null

    private fun loadParams(holder: ViewHolder, raw: Any?) {
        val json = raw as? String ?: return
        holder.params = try {
            gson.fromJson<List<FunctionParam>>(json, paramsType).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }

    /**
     * 反查包含本「定义函数」步骤的工作流，读取保存时已聚合的 functionSignature.returnDef（只读展示）。
     * 与 DefineFunctionModule.findOwningWorkflowId 一致。
     */
    private fun lookupReturnDef(context: Context): FunctionReturn? {
        return try {
            val workflow = WorkflowManager(context).getAllWorkflows()
                .firstOrNull { wf -> wf.steps.any { it.moduleId == DEFINE_FUNCTION_MODULE_ID } }
            workflow?.functionSignature?.returnDef
        } catch (e: Exception) {
            null
        }
    }

    private companion object {
        const val DEFINE_FUNCTION_MODULE_ID = "vflow.logic.define_function"
    }
}

/**
 * 「定义函数」参数列表的卡片式适配器（参照 DictionaryKVAdapter 风格）。
 * 每行一个卡片：参数名 + 类型 + 必填/可选 + 编辑/删除按钮。
 */
class FunctionParamListAdapter(
    private val context: Context,
    private val params: List<FunctionParam>,
    private val onEdit: (Int) -> Unit,
    private val onDelete: (Int) -> Unit
) : RecyclerView.Adapter<FunctionParamListAdapter.ViewHolder>() {

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
        val param = params[position]
        holder.name.text = param.name.ifBlank { context.getString(R.string.editor_define_function_param_name_hint) }
        val typeLabel = VTypeRegistry.getType(param.type).getLocalizedName(context)
        holder.type.text = typeLabel
        holder.required.text = context.getString(
            if (param.isRequired) R.string.editor_define_function_param_required
            else R.string.editor_define_function_param_optional
        )
        // 必填用红色醒目（任务 13 同理；这里定义侧必填也标红）
        holder.required.setTextColor(
            if (param.isRequired) context.getColor(R.color.md_theme_light_error)
            else context.getColor(android.R.color.darker_gray)
        )
        holder.editBtn.setOnClickListener { onEdit(position) }
        holder.deleteBtn.setOnClickListener { onDelete(position) }
    }

    override fun getItemCount() = params.size
}
