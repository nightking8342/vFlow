package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TileKind
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
 *
 * ⚠️⚠️ **候选只列「有开关状态」的工作流**（fork，2026-10-10 真机反馈后补）。
 * 判据走 [TileGate.accepts]`(TOGGLE, …)`，**不在这里自己写 `hasAutoTriggers()`** ——
 * 见 [com.chaomixian.vflow.core.workflow.module.triggers.WorkflowToggleTriggerUIProvider] 的同名说明。
 *
 * ⚠️ 本模块**也能**写手动型工作流的 `isEnabled`，但那个字段对手动型**毫无作用**
 * （不阻止手动执行、卡片上也没有开关可看），而且会在用户**之后**给它加自动触发器时
 * 留下一个 `isEnabled = false` 的存量值（编辑器保存会保留它）⇒ 新触发器静默不注册。
 * 与其让用户踩这个坑，不如**根本不提供**这个选项。
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
            val candidates = workflowManager.getAllWorkflows()
                .filter { TileGate.accepts(TileKind.TOGGLE, it) }
            // 空态必须**说出来**（理由同上，与触发器侧一致）
            if (candidates.isEmpty()) {
                Toast.makeText(
                    context,
                    R.string.toast_no_toggleable_workflow,
                    Toast.LENGTH_LONG,
                ).show()
                return@setOnClickListener
            }
            SearchableWorkflowDialog.show(
                context = context,
                titleResId = R.string.dialog_vflow_logic_set_workflow_enabled_select_title,
                items = candidates.map { WorkflowDialogItem(id = it.id, name = it.name) },
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
