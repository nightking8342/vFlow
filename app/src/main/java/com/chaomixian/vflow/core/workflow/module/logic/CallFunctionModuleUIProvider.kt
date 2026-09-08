// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/CallFunctionModuleUIProvider.kt
// 描述：「调用函数工作流」模块的自定义编辑器。选择函数工作流（只列 isFunction==true 的），
//      并动态生成参数赋值区。这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.common.SearchableWorkflowDialog
import com.chaomixian.vflow.ui.common.WorkflowDialogItem

class CallFunctionModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val selectedWorkflowText: TextView = view.findViewById(R.id.text_selected_workflow)
        val selectButton: Button = view.findViewById(R.id.button_select_workflow)
        var selectedWorkflowId: String? = null
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
        val workflowManager = WorkflowManager(context)
        val workflowId = currentParameters["workflow_id"] as? String

        holder.selectedWorkflowId = workflowId

        fun updateSelectedWorkflowText(workflowId: String?) {
            holder.selectedWorkflowText.text = if (workflowId != null) {
                workflowManager.getWorkflow(workflowId)?.name ?: context.getString(R.string.summary_unknown_workflow)
            } else {
                context.getString(R.string.summary_no_workflow_selected)
            }
        }

        updateSelectedWorkflowText(workflowId)

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
                    updateSelectedWorkflowText(selectedId)
                    onParametersChanged()
                }
            )
        }

        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        return mapOf("workflow_id" to h.selectedWorkflowId)
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null
}
