// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/CallFunctionModuleUIProvider.kt
// 描述：「调用函数工作流」模块的自定义编辑器。选择函数工作流（只列 isFunction==true 的），
//      并动态渲染参数赋值区——按被调函数签名的参数类型分派控件（文本富文本/数字键盘/布尔开关/列表编辑器/字典编辑器），
//      支持魔法变量（🔮）插入变量药丸。这是 fork 新增文件（上游无此文件）。
//
// 架构说明：getHandledInputIds 只接管 workflow_id；函数参数是动态的，无法在静态 getHandledInputIds 里声明，
//      所以在 createEditor 内根据选中的函数签名动态渲染赋值区，并在 readFromEditor 读回全部参数值。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.ui.common.SearchableWorkflowDialog
import com.chaomixian.vflow.ui.common.WorkflowDialogItem
import com.chaomixian.vflow.core.module.isMagicVariable
import com.chaomixian.vflow.core.module.isNamedVariable
import com.chaomixian.vflow.ui.workflow_editor.DictionaryKVAdapter
import com.chaomixian.vflow.ui.workflow_editor.ListItemAdapter
import com.chaomixian.vflow.ui.workflow_editor.ParamPath
import com.chaomixian.vflow.ui.workflow_editor.PillRenderer
import com.chaomixian.vflow.ui.workflow_editor.RichTextView
import com.chaomixian.vflow.ui.workflow_editor.StandardControlFactory
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout

class CallFunctionModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val selectedWorkflowText: TextView = view.findViewById(R.id.text_selected_workflow)
        val selectButton: Button = view.findViewById(R.id.button_select_workflow)
        val paramsContainer: LinearLayout = view.findViewById(R.id.container_call_function_params)
                // 参数控件：paramName -> 值读取器
        val paramValueViews = mutableMapOf<String, View>()
        var selectedWorkflowId: String? = null
        var signature: FunctionSignature? = null
        var onMagicVariableRequested: ((String) -> Unit)? = null
        var allSteps: List<ActionStep>? = null
        var currentParameters: Map<String, Any?> = emptyMap()
        var onParametersChanged: (() -> Unit)? = null

        /**
         * 处理变量插入到参数控件的子元素路径（如 "items.0" / "config.key"）。
         * 覆盖 CustomEditorViewHolder.insertVariable：更新对应列表/字典 adapter 元素，
         * 使列表/字典元素能引用变量。顶层参数名由外部 setPath 处理。
         * @return true 表示已处理。
         */
        override fun insertVariable(inputId: String, variableReference: String): Boolean {
            val path = ParamPath.parse(inputId)
            val segment = path.segments.firstOrNull() ?: return false
            val view = paramValueViews[path.rootId]
            val rootId = path.rootId
            com.chaomixian.vflow.core.logging.DebugLogger.e(
                "CallFunctionUI",
                "insertVariable inputId=$inputId ref=$variableReference root=$rootId segment=$segment view=$view viewTag=${view?.tag}"
            )
            val targetView = view ?: run {
                com.chaomixian.vflow.core.logging.DebugLogger.e("CallFunctionUI", "view null for root=$rootId")
                return false
            }

            when (segment) {
                is ParamPath.Segment.Index -> {
                    // tag 前缀判断（避免 param.name 在字符串插值中被意外求值导致精确匹配失败）
                    if ((targetView.tag as? String)?.startsWith("list:") == true) {
                        val recycler = targetView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view_list)
                        val adapter = recycler?.adapter as? ListItemAdapter ?: return false
                        adapter.updateItem(segment.value, variableReference)
                        onParametersChanged?.invoke()
                        return true
                    }
                    com.chaomixian.vflow.core.logging.DebugLogger.e("CallFunctionUI", "list tag mismatch: actual=${targetView.tag} (root=$rootId)")
                }
                is ParamPath.Segment.Key -> {
                    if ((targetView.tag as? String)?.startsWith("dict:") == true) {
                        val recycler = targetView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view_dictionary)
                        val adapter = recycler?.adapter as? DictionaryKVAdapter ?: return false
                        adapter.updateValueForKey(segment.value, variableReference)
                        onParametersChanged?.invoke()
                        return true
                    }
                    com.chaomixian.vflow.core.logging.DebugLogger.e("CallFunctionUI", "dict tag mismatch: actual=${targetView.tag} (root=$rootId)")
                }
            }
            return false
        }
    }

    override fun getHandledInputIds(): Set<String> = setOf("workflow_id")

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.partial_call_workflow_editor, parent, false)
        val holder = ViewHolder(view)
        holder.onMagicVariableRequested = onMagicVariableRequested
        holder.allSteps = allSteps
        holder.currentParameters = currentParameters
        holder.onParametersChanged = onParametersChanged

        val workflowManager = WorkflowManager(context)
        val workflowId = currentParameters["workflow_id"] as? String

        holder.selectedWorkflowId = workflowId

        fun refreshParams() {
            val signature = holder.signature
            holder.paramsContainer.removeAllViews()
            holder.paramValueViews.clear()
            if (signature == null) {
                holder.paramsContainer.isVisible = false
                return
            }
            holder.paramsContainer.isVisible = true
            if (signature.params.isEmpty()) {
                holder.paramsContainer.addView(TextView(context).apply {
                    text = context.getString(R.string.editor_call_function_no_params)
                    setPadding(0, 8, 0, 8)
                })
                return
            }
            signature.params.forEach { param ->
                val valueView = createParamRow(context, holder, param)
                holder.paramValueViews[param.name] = valueView.valueView
                holder.paramsContainer.addView(valueView.row)
            }
        }

        fun updateSelectedWorkflowText(workflowId: String?) {
            holder.selectedWorkflowText.text = if (workflowId != null) {
                workflowManager.getWorkflow(workflowId)?.name ?: context.getString(R.string.summary_unknown_workflow)
            } else {
                context.getString(R.string.summary_no_workflow_selected)
            }
        }

        // 初始加载：若已选函数，读取签名并渲染参数区
        if (workflowId != null) {
            holder.signature = workflowManager.getWorkflow(workflowId)?.functionSignature
        }
        updateSelectedWorkflowText(workflowId)
        refreshParams()

        holder.selectButton.setOnClickListener {
            // 只列出函数工作流（决策 13）。
            val functionWorkflows = workflowManager.getAllWorkflows().filter { it.isFunction }
            SearchableWorkflowDialog.show(
                context = context,
                titleResId = R.string.dialog_call_function_select_title,
                items = functionWorkflows.map { WorkflowDialogItem(id = it.id, name = it.name) },
                onSelected = {
                    val selectedId = it.id
                    holder.selectedWorkflowId = selectedId
                    holder.signature = workflowManager.getWorkflow(selectedId)?.functionSignature
                    updateSelectedWorkflowText(selectedId)
                    refreshParams()
                    onParametersChanged()
                }
            )
        }

        return holder
    }

    /**
     * 为单个函数参数渲染一行赋值控件。
     * 值控件按参数类型分派（决策 5：与定义侧一致）。返回包装对象以记录值控件。
     */
    private fun createParamRow(
        context: Context,
        holder: ViewHolder,
        param: FunctionParam
    ): ParamRow {
        val row = LayoutInflater.from(context).inflate(R.layout.partial_call_function_param, null, false)
        // 参数名 · 必填标志 · 类型（左对齐，点分隔）
        val nameView = row.findViewById<TextView>(R.id.param_name)
        nameView.text = param.name
        val starView = row.findViewById<TextView>(R.id.param_star)
        starView.isVisible = param.isRequired
        val dotView = row.findViewById<TextView>(R.id.param_dot)
        dotView.isVisible = true
        val typeView = row.findViewById<TextView>(R.id.param_type)
        typeView.text = VTypeRegistry.getType(param.type).getLocalizedName(context)

        val valueContainer = row.findViewById<ViewGroup>(R.id.value_container)
        valueContainer.removeAllViews()

        val magicButton = row.findViewById<android.widget.ImageButton>(R.id.button_magic_variable)
        magicButton.isVisible = true
        magicButton.setOnClickListener { holder.onMagicVariableRequested?.invoke(param.name) }

        val currentValue = holder.currentParameters[param.name]
        val valueView = createParamValueEditor(context, holder, param, currentValue)
        valueContainer.addView(valueView)
        // 注意：不能给 row 本身设 tag = param.name。
        // findViewWithTag(inputId) 在 ActionEditorRichTextLocator.findRichTextView 里做深度优先查找，
        // 若 row（根 LinearLayout）带该 tag，会先命中 row 而非内部的 RichTextView，
        // 导致 ClassCastException: LinearLayout cannot be cast to RichTextView（已实测崩溃）。

        return ParamRow(row, valueView)
    }

    private fun createParamValueEditor(
        context: Context,
        holder: ViewHolder,
        param: FunctionParam,
        currentValue: Any?
    ): View {
        // 决策 5/27：与「新建变量」一致——数字/列表/布尔/字典类型，只要值是变量引用（{{..}}/[[..]]），
        // 就渲染成可点击的变量药丸（未选变量时才显示各自类型控件）。
        // 文本走富文本编辑框（可编辑药丸）；图片/文件/坐标走富文本（用户已确认正常），均不走此分支。
        val shouldRenderVarPill = param.type == VTypeRegistry.NUMBER.id ||
            param.type == VTypeRegistry.BOOLEAN.id ||
            param.type == VTypeRegistry.LIST.id ||
            param.type == VTypeRegistry.DICTIONARY.id
        if (shouldRenderVarPill &&
            currentValue is String && (currentValue.isMagicVariable() || currentValue.isNamedVariable())
        ) {
            val pill = LayoutInflater.from(context).inflate(R.layout.magic_variable_pill, null, false)
            val pillText = pill.findViewById<TextView>(R.id.pill_text)
            pillText.text = PillRenderer.resolveDisplayName(context, currentValue, holder.allSteps ?: emptyList())
            pill.tag = currentValue // 存原始引用，供 readFromEditor 读回
            pill.setOnClickListener {
                holder.onMagicVariableRequested?.invoke(param.name)
            }
            return pill
        }

        return when (param.type) {
            VTypeRegistry.NUMBER.id -> StandardControlFactory.createTextInputLayout(
                context, isNumber = true, currentValue = currentValue, hint = param.name
            )
            VTypeRegistry.BOOLEAN.id -> MaterialSwitch(context).apply {
                isChecked = (currentValue as? Boolean) ?: false
                text = param.name
            }
            VTypeRegistry.LIST.id -> {
                val editorView = LayoutInflater.from(context).inflate(R.layout.partial_list_editor, null, false)
                val recyclerView = editorView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view_list)
                val addButton = editorView.findViewById<Button>(R.id.button_add_list_item)
                val currentList = (currentValue as? List<*>)?.map { it?.toString() ?: "" }?.toMutableList() ?: mutableListOf()
                val adapter = ListItemAdapter(currentList, holder.allSteps) { pos ->
                    com.chaomixian.vflow.core.logging.DebugLogger.e("CallFunctionUI", "list item magic clicked param=${param.name} pos=$pos")
                    holder.onMagicVariableRequested?.invoke("${param.name}.$pos")
                }
                recyclerView.adapter = adapter
                recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                addButton.setOnClickListener { adapter.addItem() }
                editorView.tag = "list:$param.name"
                editorView
            }
            VTypeRegistry.DICTIONARY.id -> {
                val editorView = LayoutInflater.from(context).inflate(R.layout.partial_dictionary_editor, null, false)
                val recyclerView = editorView.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view_dictionary)
                val addButton = editorView.findViewById<Button>(R.id.button_add_kv_pair)
                val currentMap = (currentValue as? Map<*, *>)
                    ?.mapNotNull { (k, v) -> (k?.toString() ?: return@mapNotNull null) to (v?.toString() ?: "") }
                    ?.toMutableList() ?: mutableListOf()
                val adapter = DictionaryKVAdapter(currentMap, holder.allSteps) { key ->
                    holder.onMagicVariableRequested?.invoke("${param.name}.$key")
                }
                recyclerView.adapter = adapter
                recyclerView.layoutManager = androidx.recyclerview.widget.LinearLayoutManager(context)
                addButton.setOnClickListener { adapter.addItem() }
                editorView.tag = "dict:$param.name"
                editorView
            }
            else -> {
                // 文本/图片/文件/坐标等：富文本编辑框（支持药丸 + 变量）
                StandardControlFactory.createRichTextEditor(
                    context = context,
                    initialText = currentValue?.toString() ?: "",
                    allSteps = holder.allSteps ?: emptyList(),
                    tag = param.name,
                    hint = param.name
                ).also { richView ->
                    // 保证行内魔法变量选择能命中该参数
                    richView.findViewById<RichTextView>(R.id.rich_text_view)?.tag = param.name
                }
            }
        }
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        val result = mutableMapOf<String, Any?>("workflow_id" to h.selectedWorkflowId)
        h.signature?.params?.forEach { param ->
            val view = h.paramValueViews[param.name] ?: return@forEach
            result[param.name] = readParamValue(view, param.name)
        }
        return result
    }

    private fun readParamValue(view: View, paramName: String): Any? {
        return when {
            // 变量药丸：tag 里存的是原始变量引用（{{..}}/[[..]]）
            view.tag is String && (view.tag as String).let { it.isMagicVariable() || it.isNamedVariable() } -> view.tag
            view.tag == "list:$paramName" -> {
                val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view_list)
                (recycler?.adapter as? ListItemAdapter)?.getItems()
            }
            view.tag == "dict:$paramName" -> {
                val recycler = view.findViewById<androidx.recyclerview.widget.RecyclerView>(R.id.recycler_view_dictionary)
                (recycler?.adapter as? DictionaryKVAdapter)?.getItemsAsMap()
            }
            view is MaterialSwitch -> view.isChecked
            // 富文本编辑框优先（它本身是 TextInputLayout，但内部含 RichTextView，需用 getRawText 读回药丸原文）
            view.findViewById<RichTextView>(R.id.rich_text_view) != null -> {
                val richText = view.findViewById<RichTextView>(R.id.rich_text_view)
                richText?.getRawText()?.takeIf { it.isNotBlank() }
            }
            view is TextInputLayout -> view.editText?.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null

    private data class ParamRow(val row: View, val valueView: View)
}
