package com.chaomixian.vflow.core.workflow.module.triggers

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
 * 工作流开关触发器的目标选择器。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md` §8 —— **复用既有实现**，
 * 不新造 `PickerType` 枚举值（那要改上游 `definitions.kt` + `PickerHandler.kt` +
 * `StandardControlFactory.kt`，diff 面大得多）。
 *
 * 形态与 [com.chaomixian.vflow.core.workflow.module.logic.CallWorkflowModuleUIProvider] 同构，
 * 只是换了布局（[R.layout.partial_workflow_picker_editor]）与文案。
 *
 * ⚠️ 只接管 `workflow_id`；`state` 走自动表单的 `CHIP_GROUP`。
 *
 * ⚠️⚠️ **候选只列「有开关状态」的工作流**（fork，2026-10-10 真机反馈后补）。
 * 判据走 [TileGate.accepts]`(TOGGLE, …)`，**不在这里自己写 `hasAutoTriggers()`** ——
 * `TileGate` 的 KDoc 明写「任何一处自己写判据，都会让『菜单项显示着、点了却被拒绝』
 * 这类不一致出现」，而**卡片上的开关本身就是同一个判据**
 * （`WorkflowListScreen` 只在 `hasAutoTriggers` 时画 `VFlowSwitch`，手动型画的是 ▶ 执行按钮）。
 *
 * 手动型工作流的 `isEnabled` **毫无作用**（`WorkflowExecutor` / `ManualTriggerModule` 都不读它），
 * 既没有开关可点、也不会因它而不执行 ⇒ 让用户选中它等于**给了一个永远不可能发生的触发条件**。
 */
class WorkflowToggleTriggerUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val title: TextView = view.findViewById(R.id.text_picker_title)
        val selectedWorkflowText: TextView = view.findViewById(R.id.text_selected_workflow)
        val selectButton: Button = view.findViewById(R.id.button_select_workflow)
        var selectedWorkflowId: String? = null
    }

    override fun getHandledInputIds(): Set<String> =
        setOf(WorkflowToggleTriggerModule.PARAM_WORKFLOW_ID)

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
        val workflowId = currentParameters[WorkflowToggleTriggerModule.PARAM_WORKFLOW_ID] as? String

        holder.selectedWorkflowId = workflowId
        holder.title.setText(R.string.editor_vflow_trigger_workflow_toggle_workflow_title)

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
            // 空态必须**说出来**：直接弹一个空列表只会显示「没有找到相关工作流」，
            // 而那不是「搜不到」，是「一个都没有」—— 用户会以为功能坏了。
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
                titleResId = R.string.dialog_vflow_trigger_workflow_toggle_select_title,
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
        return mapOf(WorkflowToggleTriggerModule.PARAM_WORKFLOW_ID to h.selectedWorkflowId)
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null
}
