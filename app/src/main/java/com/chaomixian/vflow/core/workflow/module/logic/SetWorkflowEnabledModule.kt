package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.AiModuleMetadata
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * 设置工作流开关 —— 在工作流内部**启用 / 关闭 / 切换**另一个工作流。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md`（§7 模块定义 / §3.3 判定机制）。
 *
 * ## ⚠️ 本模块的写入会触发 `vflow.trigger.workflow_toggle`（用户第 3 条要求）
 *
 * 靠的是**默认来源 [com.chaomixian.vflow.core.workflow.WorkflowWriteOrigin.EXPLICIT]** ——
 * 即 `saveWorkflow(...)` **不传** `origin`。**不要**为了「避免打扰」在这里传 `AUTOMATIC`，
 * 那会让「B 的动作触发 A」这条需求直接失效。
 *
 * ## ⚠️ `changed == false` 时**不写盘**
 *
 * 目标本来就处于目标状态时直接返回 —— 写盘会白走一遍
 * `notifyWorkflowChanged` + `TileRefreshNotifier`。
 *
 * ## ⚠️ 目标缺权限时会被回弹（已在模块描述里写明）
 *
 * 启用一个缺权限的工作流时，`TriggerService` 会异步检查权限并把它自动关回去。
 * **本模块不做权限预检** —— 那会造出第二套权限判据，与服务侧的权威判据必然漂移。
 * 用户的观感是「模块说启用成功了，但工作流还是关的」，与列表页 / 磁贴的既有行为一致。
 */
class SetWorkflowEnabledModule : BaseModule() {

    override val id = "vflow.logic.set_workflow_enabled"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_set_workflow_enabled_name,
        descriptionStringRes = R.string.module_vflow_logic_set_workflow_enabled_desc,
        name = "设置工作流开关",
        description = "启用、关闭或切换另一个工作流。目标工作流缺少所需权限时，可能被系统自动关闭。",
        iconRes = R.drawable.rounded_set_workflow_enabled_24,
        category = "逻辑控制",
        categoryId = "logic",
    )

    override val uiProvider: ModuleUIProvider = SetWorkflowEnabledUIProvider()

    /**
     * AI 元数据。
     *
     * ⚠️ 与触发器**不同** —— survey §8.2 短板 7 记着「触发器一律不设 `aiMetadata`」，
     * 那是**触发器**的现状，**不适用于本动作模块**。
     *
     * 理由：AI 本来就能通过 `update_workflow` 改 `isEnabled`，所以这不是新增权限面，
     * 只是让 AI 用**更窄**的工具（明确的目标 + 动作）替代
     * 「读整个工作流 → 改一个字段 → 整体写回」的高风险路径。
     */
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.DIRECT_TOOL, AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.STANDARD,
        directToolDescription = "启用 / 关闭 / 切换一个已存在的工作流",
    )

    // requiredPermissions 不覆写 —— BaseModule 默认 emptyList()（不涉及系统能力）

    companion object {
        const val PARAM_WORKFLOW_ID = "workflow_id"
        const val PARAM_ACTION = "action"

        const val VALUE_ENABLE = "enable"
        const val VALUE_DISABLE = "disable"
        const val VALUE_TOGGLE = "toggle"

        /**
         * 纯函数：由 `action` + 当前状态推导目标状态。可单测，不碰 Android。
         *
         * ⚠️ 形态与设计文档 §7.3 的示例**逐字一致**：只显式列 `enable` / `disable`，
         * `toggle`（以及经 `normalizeEnumValue` 后仍非法的未知值）落在 `else` 的取反分支。
         */
        internal fun desiredEnabled(action: String?, currentEnabled: Boolean): Boolean = when (action) {
            VALUE_ENABLE -> true
            VALUE_DISABLE -> false
            // toggle（含未知值）⇒ 取反
            else -> !currentEnabled
        }
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_WORKFLOW_ID,
            name = "工作流",
            nameStringRes = R.string.param_vflow_logic_set_workflow_enabled_workflow_id_name,
            staticType = ParameterType.STRING,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = PARAM_ACTION,
            name = "操作",
            nameStringRes = R.string.param_vflow_logic_set_workflow_enabled_action_name,
            staticType = ParameterType.ENUM,
            defaultValue = VALUE_ENABLE,
            options = listOf(VALUE_ENABLE, VALUE_DISABLE, VALUE_TOGGLE),
            optionsStringRes = listOf(
                R.string.option_vflow_logic_set_workflow_enabled_enable,
                R.string.option_vflow_logic_set_workflow_enabled_disable,
                R.string.option_vflow_logic_set_workflow_enabled_toggle,
            ),
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "workflow_name",
            name = "工作流名称",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_logic_set_workflow_enabled_workflow_name,
        ),
        OutputDefinition(
            id = "previous_enabled",
            name = "之前已启用",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_logic_set_workflow_enabled_previous_enabled,
        ),
        OutputDefinition(
            id = "is_enabled",
            name = "当前已启用",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_logic_set_workflow_enabled_is_enabled,
        ),
        OutputDefinition(
            id = "changed",
            name = "是否发生变化",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_logic_set_workflow_enabled_changed,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val workflowId = step.parameters[PARAM_WORKFLOW_ID] as? String
        val workflowName = if (workflowId != null) {
            WorkflowManager(context).getWorkflow(workflowId)?.name
                ?: context.getString(R.string.summary_unknown_workflow)
        } else {
            context.getString(R.string.summary_no_workflow_selected)
        }

        val action = getInputs().normalizeEnumValue(
            PARAM_ACTION,
            step.parameters[PARAM_ACTION] as? String,
            VALUE_ENABLE,
        ) ?: VALUE_ENABLE

        val actionText = when (action) {
            VALUE_DISABLE -> context.getString(R.string.option_vflow_logic_set_workflow_enabled_disable)
            VALUE_TOGGLE -> context.getString(R.string.option_vflow_logic_set_workflow_enabled_toggle)
            else -> context.getString(R.string.option_vflow_logic_set_workflow_enabled_enable)
        }

        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_logic_set_workflow_enabled_prefix),
            " ",
            PillUtil.Pill(actionText, PARAM_ACTION, isModuleOption = true),
            " ",
            PillUtil.Pill(workflowName, PARAM_WORKFLOW_ID),
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val workflowId = context.getVariableAsString(PARAM_WORKFLOW_ID, "")

        val target = WorkflowManager(context.applicationContext).getWorkflow(workflowId)
            ?: return ExecutionResult.Failure("执行错误", "找不到 ID 为 '$workflowId' 的工作流。")

        val action = getInputs().normalizeEnumValue(
            PARAM_ACTION,
            context.getVariableAsString(PARAM_ACTION, ""),
            VALUE_ENABLE,
        )
        val desired = desiredEnabled(action, target.isEnabled)
        val changed = desired != target.isEnabled

        onProgress(
            ProgressUpdate(
                appContext.getString(
                    if (changed) R.string.msg_vflow_logic_set_workflow_enabled_done
                    else R.string.msg_vflow_logic_set_workflow_enabled_unchanged
                )
            )
        )

        if (changed) {
            // ⚠️ origin 走**默认值 EXPLICIT** —— 这正是「B 的动作要触发 A」的实现方式。
            //    不要为了「避免打扰」在这里传 AUTOMATIC。
            // ⚠️ wasEnabledBeforePermissionsLost = false 与列表页 / 磁贴逐字一致：
            //    它表达「这是显式意图」，从而**不会**被 recoverEligibleWorkflows 自动重开。
            WorkflowManager(context.applicationContext).saveWorkflow(
                target.copy(isEnabled = desired, wasEnabledBeforePermissionsLost = false)
            )
        }

        return ExecutionResult.Success(
            outputs = mapOf(
                "workflow_name" to VString(target.name),
                "previous_enabled" to VBoolean(target.isEnabled),
                "is_enabled" to VBoolean(desired),
                "changed" to VBoolean(changed),
            )
        )
    }
}
