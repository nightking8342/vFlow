package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
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
 * 工作流开关触发器 —— 当**指定工作流**的启用开关变化时触发工作流。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md`（§6 模块定义 / §3.3 判定机制）。
 *
 * ## ⚠️⚠️ 它没有可注册的系统事件源（本设计最核心的一条）
 *
 * 现有已注册 Handler 的事件源**全部在 App 之外**（系统广播 / 无障碍事件流 /
 * 传感器 / AlarmManager / 外部进程 Intent）。本触发器要监听的是
 * **App 自己写的 `Workflow.isEnabled`** —— 没有 `IntentFilter` 可注册、
 * 没有传感器可订阅。
 *
 * ⇒ 它是**继 `key_event` 之后第二个「事件源不在 Handler 内部」的触发器**，
 * 形态照抄 `key_event`：由 `TriggerService.onStartCommand` 的
 * `ACTION_WORKFLOW_CHANGED` 分支**直接调进 Handler**
 * （[com.chaomixian.vflow.core.workflow.module.triggers.handlers.WorkflowToggleTriggerHandler.onWorkflowSaved]）。
 *
 * ## ⚠️ 「什么算一次开关变化」由显式来源标记决定，不靠 `isEnabled` 拼判据
 *
 * 见 [com.chaomixian.vflow.core.workflow.WorkflowWriteOrigin] 的类注释
 * —— 那里记着一组「字段逐字节相同」的反例，**不要**改回借用
 * `wasEnabledBeforePermissionsLost` 的方案。
 *
 * ## 权限：**刻意为空**
 *
 * 不涉及任何系统能力（事件源就是 App 自己的写盘）。覆写 `requiredPermissions`
 * 一行都不要加 —— `BaseModule` 的默认值 `emptyList()` 正是本模块要的。
 */
class WorkflowToggleTriggerModule : BaseModule() {

    override val id = "vflow.trigger.workflow_toggle"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_workflow_toggle_name,
        descriptionStringRes = R.string.module_vflow_trigger_workflow_toggle_desc,
        name = "工作流开关触发",
        description = "当指定的工作流被启用或关闭时触发工作流",
        iconRes = R.drawable.rounded_workflow_toggle_24,
        category = "触发器",
        // ⚠️ id 前缀与 categoryId 缺一不可（survey §6）
        categoryId = "trigger",
    )

    override val uiProvider: ModuleUIProvider = WorkflowToggleTriggerUIProvider()

    // aiMetadata 不覆写（null）—— 与现有全部触发器一致（survey §8.2 短板 7）
    // requiredPermissions 不覆写 —— BaseModule 默认 emptyList()

    companion object {
        const val PARAM_WORKFLOW_ID = "workflow_id"
        const val PARAM_STATE = "state"

        /** 序列化值使用与语言无关的稳定标识符（本仓库既有纪律）。 */
        const val VALUE_ANY = "any"
        const val VALUE_ENABLED = "enabled"
        const val VALUE_DISABLED = "disabled"
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_WORKFLOW_ID,
            name = "工作流",
            nameStringRes = R.string.param_vflow_trigger_workflow_toggle_workflow_id_name,
            staticType = ParameterType.STRING,
            // 静态选择：存工作流 id，改名不影响绑定。与 CallWorkflowModule 对齐
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = PARAM_STATE,
            name = "状态",
            nameStringRes = R.string.param_vflow_trigger_workflow_toggle_state_name,
            staticType = ParameterType.ENUM,
            defaultValue = VALUE_ANY,
            options = listOf(VALUE_ANY, VALUE_ENABLED, VALUE_DISABLED),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_workflow_toggle_any,
                R.string.option_vflow_trigger_workflow_toggle_enabled,
                R.string.option_vflow_trigger_workflow_toggle_disabled,
            ),
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "workflow_id",
            name = "工作流 ID",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_workflow_toggle_workflow_id,
        ),
        OutputDefinition(
            id = "workflow_name",
            name = "工作流名称",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_workflow_toggle_workflow_name,
        ),
        OutputDefinition(
            id = "is_enabled",
            name = "当前已启用",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_workflow_toggle_is_enabled,
        ),
        OutputDefinition(
            id = "was_enabled",
            name = "之前已启用",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_workflow_toggle_was_enabled,
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

        val state = getInputs().normalizeEnumValue(
            PARAM_STATE,
            step.parameters[PARAM_STATE] as? String,
            VALUE_ANY,
        ) ?: VALUE_ANY

        val stateText = when (state) {
            VALUE_ENABLED -> context.getString(R.string.option_vflow_trigger_workflow_toggle_enabled)
            VALUE_DISABLED -> context.getString(R.string.option_vflow_trigger_workflow_toggle_disabled)
            else -> context.getString(R.string.option_vflow_trigger_workflow_toggle_any)
        }

        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_trigger_workflow_toggle_prefix),
            " ",
            PillUtil.Pill(workflowName, PARAM_WORKFLOW_ID),
            " ",
            PillUtil.Pill(stateText, PARAM_STATE, isModuleOption = true),
        )
    }

    /**
     * 触发器模块本身不做动作 —— 真实触发由
     * [com.chaomixian.vflow.core.workflow.module.triggers.handlers.WorkflowToggleTriggerHandler]
     * 完成。
     *
     * 此方法只在 `WorkflowExecutor.seedTriggerOutputs` 里被调用一次，
     * 给下游提供「无事件数据时」的占位输出（手动执行整个工作流时走的就是这条）。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_trigger_workflow_toggle_triggered)))

        val data = context.triggerData as? WorkflowToggleTriggerData
        return ExecutionResult.Success(
            outputs = mapOf(
                "workflow_id" to VString(data?.workflowId.orEmpty()),
                "workflow_name" to VString(data?.workflowName.orEmpty()),
                "is_enabled" to VBoolean(data?.isEnabled ?: false),
                "was_enabled" to VBoolean(data?.wasEnabled ?: false),
            )
        )
    }
}
