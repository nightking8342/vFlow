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

/**
 * 设置工作流开关模块的目标选择器。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md` §8 —— 复用既有实现
 * （`SearchableWorkflowDialog`），与
 * [com.chaomixian.vflow.core.workflow.module.triggers.WorkflowToggleTriggerUIProvider] 同构，
 * 共用同一个布局 [R.layout.partial_workflow_picker_editor]（只是文案不同）。
 *
 * ⚠️ 只接管 `workflow_id`；`action` 走自动表单的 `CHIP_GROUP`。
 */
class SetWorkflowEnabledUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_picker_title)
        val selectedWorkflowText: TextView = view.findViewById(R.id.text_selected_workflow)
        val selectButton: Button = view.findViewById(R.id.button_select_workflow)
        var selectedWorkflowId: String? = null
    }

    override fun getHandledInputIds(): Set<String> =
        setOf(SetWorkflowEnabledModule.PARAM_WORKFLOW_ID)

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
            .inflate(R.layout.partial_workflow_picker_editor, parent, false)
        val holder = ViewHolder(view)
        val workflowManager = WorkflowManager(context)
        val workflowId = currentParameters[SetWorkflowEnabledModule.PARAM_WORKFLOW_ID] as? String

        holder.selectedWorkflowId = workflowId
        holder.title.setText(R.string.editor_vflow_logic_set_workflow_enabled_workflow_title)

        fun updateSelectedWorkflowText(id: String?) {
            holder.selectedWorkflowText.text = if (id != null) {
                workflowManager.getWorkflow(id)?.name
                    ?: context.getString(R.string.summary_unknown_workflow)
            } else {
                context.getString(R.string.summary_no_workflow_selected)
            }
        }

        updateSelectedWorkflowText(workflowId)

        holder.selectButton.setOnClickListener {
            SearchableWorkflowDialog.show(
                context = context,
                titleResId = R.string.dialog_vflow_logic_set_workflow_enabled_select_title,
                items = workflowManager.getAllWorkflows().map { WorkflowDialogItem(id = it.id, name = it.name) },
                onSelected = {
                    holder.selectedWorkflowId = it.id
                    updateSelectedWorkflowText(it.id)
                    onParametersChanged()
                }
            )
        }

        return holder
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        return mapOf(SetWorkflowEnabledModule.PARAM_WORKFLOW_ID to h.selectedWorkflowId)
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null
}
