// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/SimDataSwitchTriggerModule.kt
// 描述: 数据卡切换触发器 —— 当默认上网卡（DDS）在卡1/卡2 之间切换时触发工作流。
//
// 设计文档：docs/fork/sim-data-switch-design.md
// 真机验证结论：小米 Redmi K60 至尊版 / HyperOS 4.0 / Android 17 实测通过（见文档 §9）
//
// 监听链路：系统广播 android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED
//   由 com.android.phone（uid 1001）发出，属 <protected-broadcast>，不可伪造；
//   **接收无需任何权限**，且 extra 自带新 subId。
//   ⚠️ 该广播必须用 RECEIVER_EXPORTED 注册 —— 用本仓库其它触发器惯用的
//   RECEIVER_NOT_EXPORTED 会**静默收不到**，详见 Handler 的类注释。
package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

class SimDataSwitchTriggerModule : BaseModule() {

    override val id = "vflow.trigger.sim_data_switch"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_sim_data_switch_name,
        descriptionStringRes = R.string.module_vflow_trigger_sim_data_switch_desc,
        name = "数据卡切换触发",
        description = "当默认上网卡在卡1/卡2 之间切换时触发工作流",
        iconRes = R.drawable.rounded_swap_sim_24,
        category = "触发器",
        categoryId = "trigger",
    )

    /**
     * 触发需要 `READ_PHONE_STATE`：广播本身零权限可收，但要把 subId 映射成
     * 「卡1/卡2」读订阅列表就需要它（见 Handler 的类注释）。
     * 与同为 telephony 触发器的 [CallTriggerModule] 对齐。
     *
     * ⚠️ **不要因为它「理论上零权限」就删掉**：`TriggerService.handleWorkflowChanged`
     * 会在注册前检查权限，缺失时**静默把工作流置为未启用**。声明得少反而会让
     * 整个触发器不工作 —— 这是把「降级」误当成「无需权限」的经典错误。
     */
    override val requiredPermissions = listOf(PermissionManager.READ_PHONE_STATE)

    override val uiProvider = null

    companion object {
        const val PARAM_TARGET_SLOT = "target_slot"

        /** 序列化值使用与语言无关的标识符 */
        const val SLOT_1 = "slot1"
        const val SLOT_2 = "slot2"

        /** 任意卡切换都触发（无需知道换成了哪张卡） */
        const val SLOT_ANY = "any"

        /** 稳定值 → 卡槽序号；[SLOT_ANY] 不是具体卡槽，返回 null */
        fun slotIndexOf(value: String): Int? = when (value) {
            SLOT_1 -> 0
            SLOT_2 -> 1
            else -> null
        }

        /** 旧值兼容映射（含用户可能存过的本地化文案） */
        private val SLOT_LEGACY_MAP = mapOf(
            "卡1" to SLOT_1,
            "卡一" to SLOT_1,
            "SIM1" to SLOT_1,
            "卡2" to SLOT_2,
            "卡二" to SLOT_2,
            "SIM2" to SLOT_2,
            // 与 DoNotDisturbTriggerModule 的「任意」文案保持同一套写法
            "任意" to SLOT_ANY,
            "任意卡" to SLOT_ANY,
            "任意切换" to SLOT_ANY,
        )
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_TARGET_SLOT,
            name = "切换到",
            nameStringRes = R.string.param_vflow_trigger_sim_data_switch_slot_name,
            staticType = ParameterType.ENUM,
            defaultValue = SLOT_ANY,
            options = listOf(SLOT_ANY, SLOT_1, SLOT_2),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_sim_data_switch_any,
                R.string.option_vflow_trigger_sim_data_switch_slot1,
                R.string.option_vflow_trigger_sim_data_switch_slot2,
            ),
            legacyValueMap = SLOT_LEGACY_MAP,
            inputStyle = InputStyle.CHIP_GROUP,
            // 卡槽是编译期确定的枚举，不接受运行时变量
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "sim_slot",
            name = "当前上网卡槽",
            typeName = VTypeRegistry.NUMBER.id,
            nameStringRes = R.string.output_vflow_trigger_sim_data_switch_slot_name,
        ),
        OutputDefinition(
            id = "is_slot1",
            name = "是卡1",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_sim_data_switch_is_slot1_name,
        ),
        OutputDefinition(
            id = "is_slot2",
            name = "是卡2",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_sim_data_switch_is_slot2_name,
        ),
        OutputDefinition(
            id = "card_label",
            name = "卡名",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_sim_data_switch_card_label_name,
        ),
        OutputDefinition(
            id = "carrier_name",
            name = "运营商",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_sim_data_switch_carrier_name_name,
        ),
        OutputDefinition(
            id = "sub_id",
            name = "订阅 ID",
            typeName = VTypeRegistry.NUMBER.id,
            nameStringRes = R.string.output_vflow_trigger_sim_data_switch_sub_id_name,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val slot = getInputs().normalizeEnumValue(
            PARAM_TARGET_SLOT,
            step.parameters[PARAM_TARGET_SLOT] as? String,
            SLOT_ANY,
        ) ?: SLOT_ANY

        // 「任意」复用 DoNotDisturbTriggerModule 同一套写法：摘要只留前缀、不带选项 pill，
        // 读起来是「默认上网卡切换」而非「默认上网卡切换到 任意」
        if (slot == SLOT_ANY) {
            return PillUtil.buildSpannable(
                context,
                context.getString(R.string.summary_vflow_trigger_sim_data_switch_any),
            )
        }

        val displayText = when (slot) {
            SLOT_2 -> context.getString(R.string.option_vflow_trigger_sim_data_switch_slot2)
            else -> context.getString(R.string.option_vflow_trigger_sim_data_switch_slot1)
        }

        val slotPill = PillUtil.Pill(displayText, PARAM_TARGET_SLOT, isModuleOption = true)
        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_trigger_sim_data_switch_prefix),
            " ",
            slotPill,
        )
    }

    /**
     * 触发器模块本身不做实际动作 —— 真实触发由
     * [com.chaomixian.vflow.core.workflow.module.triggers.handlers.SimDataSwitchTriggerHandler] 完成。
     * 此方法只在 `WorkflowExecutor.seedTriggerOutputs` 里被调用一次，
     * 用于给下游提供「无事件数据时」的占位输出。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_trigger_sim_data_switch_ready)))

        val data = context.triggerData as? SimDataSwitchTriggerData
        return ExecutionResult.Success(
            outputs = mapOf(
                "sim_slot" to VNumber(data?.simSlotIndex?.toDouble() ?: -1.0),
                "is_slot1" to VBoolean(data?.isSlot1 ?: false),
                "is_slot2" to VBoolean(data?.isSlot2 ?: false),
                "card_label" to VString(data?.cardLabel.orEmpty()),
                "carrier_name" to VString(data?.carrierName.orEmpty()),
                "sub_id" to VNumber(data?.subId?.toDouble() ?: SIM_INVALID_SUBSCRIPTION_ID.toDouble()),
            )
        )
    }
}
