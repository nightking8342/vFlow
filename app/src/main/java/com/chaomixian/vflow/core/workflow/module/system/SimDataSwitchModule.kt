// 文件: main/java/com/chaomixian/vflow/core/workflow/module/system/SimDataSwitchModule.kt
// 描述: 数据卡切换模块 —— 把默认上网卡（DDS）切到卡1 或卡2。
//
// 设计文档：docs/fork/sim-data-switch-design.md
// 真机验证：小米 Redmi K60 至尊版 / HyperOS 4.0 / Android 17，切换耗时 5–22ms（见文档 §9）
//
// ## 为什么不能像 `MobileDataModule` 那样简单地 exec shell 命令
//
// 「切换默认上网卡」没有 shell 命令可用，实测排除三条路径：
//   · `svc data`         —— 只有 enable|disable，无 prefer 之类子命令
//   · `cmd phone data`   —— 同上
//   · `settings put global multi_sim_data_call` —— **无效**：
//     `SubscriptionManagerService` 没有 ContentObserver 监听该 key，
//     外部改 Settings 不会更新服务的内存态。
//
// 唯一可行路径是经 `isub` binder 调 `setDefaultDataSubId(int)`，而它是
// `@RequiresPermission(MODIFY_PHONE_STATE)` 的 —— shell (UID 2000) 恰好持有该权限
// （`packages/Shell/AndroidManifest.xml` 有 <uses-permission>），故走 Shizuku/Root 可行。
//
// 因此本模块不 exec 命令，而是经 Core 的 `isub` wrapper 调用。
package com.chaomixian.vflow.core.workflow.module.system

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.services.VFlowCoreBridge
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import kotlinx.coroutines.delay

class SimDataSwitchModule : BaseModule() {

    companion object {
        private const val TAG = "SimDataSwitchModule"

        const val PARAM_TARGET_SLOT = "target_slot"
        const val SLOT_1 = "slot1"
        const val SLOT_2 = "slot2"

        /** 卡槽序号：0 = 卡1，1 = 卡2 */
        fun slotIndexOf(value: String): Int? = when (value) {
            SLOT_1 -> 0
            SLOT_2 -> 1
            else -> null
        }

        private val SLOT_LEGACY_MAP = mapOf(
            "卡1" to SLOT_1,
            "卡一" to SLOT_1,
            "SIM1" to SLOT_1,
            "卡2" to SLOT_2,
            "卡二" to SLOT_2,
            "SIM2" to SLOT_2,
        )

        /**
         * 切换后等待回读的时长。
         *
         * 切换是异步的：`setDefaultDataSubId` 返回只代表服务端接受了请求，
         * modem 重新附着（`remapRafIfApplicable` 会改 radio capability）需要时间。
         * 期间蜂窝数据会短暂中断，这是正常现象，**不是失败**。
         */
        private const val VERIFY_DELAY_MS = 1500L
    }

    override val id = "vflow.system.sim_data_switch"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_system_sim_data_switch_name,
        descriptionStringRes = R.string.module_vflow_system_sim_data_switch_desc,
        name = "切换数据卡",
        description = "把默认上网卡切换到卡1 或卡2",
        iconRes = R.drawable.rounded_swap_sim_24,
        category = "应用与系统",
        categoryId = "device",
    )

    override val aiMetadata = directToolMetadata(
        riskLevel = AiModuleRiskLevel.HIGH,
        directToolDescription = "Switch the default mobile data SIM between slot 1 and slot 2.",
        workflowStepDescription = "Change which SIM card provides mobile data.",
        inputHints = mapOf(
            "target_slot" to "Canonical values are slot1 (SIM 1) or slot2 (SIM 2).",
        ),
        requiredInputIds = setOf("target_slot"),
    )

    /** 需要 Shell（Root 或 Shizuku）身份 —— 底层要 MODIFY_PHONE_STATE。 */
    override fun getRequiredPermissions(step: ActionStep?): List<Permission> {
        return ShellManager.getRequiredPermissions(LogManager.applicationContext)
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_TARGET_SLOT,
            name = "切换到",
            nameStringRes = R.string.param_vflow_system_sim_data_switch_slot_name,
            staticType = ParameterType.ENUM,
            defaultValue = SLOT_1,
            options = listOf(SLOT_1, SLOT_2),
            optionsStringRes = listOf(
                R.string.option_vflow_system_sim_data_switch_slot1,
                R.string.option_vflow_system_sim_data_switch_slot2,
            ),
            legacyValueMap = SLOT_LEGACY_MAP,
            acceptsMagicVariable = false,
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            "success",
            "是否成功",
            VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_system_sim_data_switch_success_name,
        ),
        OutputDefinition(
            "sub_id",
            "切换后的订阅 ID",
            VTypeRegistry.NUMBER.id,
            nameStringRes = R.string.output_vflow_system_sim_data_switch_sub_id_name,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val slot = getInputs().normalizeEnumValue(
            PARAM_TARGET_SLOT,
            step.parameters[PARAM_TARGET_SLOT] as? String,
            SLOT_1,
        ) ?: SLOT_1

        val displayText = when (slot) {
            SLOT_2 -> context.getString(R.string.option_vflow_system_sim_data_switch_slot2)
            else -> context.getString(R.string.option_vflow_system_sim_data_switch_slot1)
        }

        val slotPill = PillUtil.Pill(displayText, PARAM_TARGET_SLOT, isModuleOption = true)
        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_system_sim_data_switch_prefix),
            " ",
            slotPill,
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val rawSlot = context.getVariableAsString(PARAM_TARGET_SLOT, SLOT_1)
        val slot = getInputs().normalizeEnumValue(PARAM_TARGET_SLOT, rawSlot, SLOT_1) ?: rawSlot
        val slotIndex = slotIndexOf(slot)
            ?: return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_system_sim_data_switch_param_error),
                appContext.getString(R.string.error_vflow_system_sim_data_switch_unknown_slot, rawSlot),
            )

        // 1. 解析目标 subId：卡槽是用户语义，binder 只认 subId。
        //    走 App 侧公开 API（不需特权），**不**用 Core —— 见 SimDataSwitchSupport 注释。
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_system_sim_data_switch_resolving)))
        val targetSubId = when (val lookup = lookupSubIdForSlot(appContext, slotIndex)) {
            is SimSlotLookup.Found -> {
                DebugLogger.i(TAG, "目标卡槽 ${slotIndex + 1} -> subId=${lookup.subId} (${lookup.label})")
                lookup.subId
            }
            // 三种失败分开报 —— 混成一句话会让排障分不清是权限、硬件还是设备不支持
            SimSlotLookup.Unavailable ->
                return ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_system_sim_data_switch_resolve_failed),
                    appContext.getString(R.string.error_vflow_system_sim_data_switch_unavailable),
                )
            SimSlotLookup.EmptySlot ->
                return ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_system_sim_data_switch_resolve_failed),
                    appContext.getString(R.string.error_vflow_system_sim_data_switch_slot_empty, slotIndex + 1),
                )
            SimSlotLookup.NotSupported ->
                return ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_system_sim_data_switch_execution_failed),
                    appContext.getString(R.string.error_vflow_system_sim_data_switch_not_supported),
                )
        }

        // 2. 幂等短路：已是目标卡就不必切换（避免无谓的 modem 重附着）
        val currentSubId = currentDefaultDataSubId()
        if (currentSubId == targetSubId) {
            DebugLogger.i(TAG, "默认上网卡已是 subId=$targetSubId，无需切换。")
            onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_system_sim_data_switch_already, slotIndex + 1)))
            return ExecutionResult.Success(
                mapOf("success" to VBoolean(true), "sub_id" to VNumber(targetSubId.toDouble()))
            )
        }

        // 3. 切换
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_system_sim_data_switch_switching, slotIndex + 1)))
        val switched = VFlowCoreBridge.setDefaultDataSubId(targetSubId)
        if (!switched) {
            return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_system_sim_data_switch_execution_failed),
                appContext.getString(R.string.error_vflow_system_sim_data_switch_failed_hint),
            )
        }

        // 4. 回读确认。切换是异步的，拿到回读才算真成功；
        //    但**不因回读失败就报错** —— 真机上切换期间数据会断，回读可能滞后。
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_system_sim_data_switch_verifying)))
        delay(VERIFY_DELAY_MS)
        val afterSubId = currentDefaultDataSubId()
        if (afterSubId != targetSubId) {
            DebugLogger.w(TAG, "切换后回读不一致：期望 $targetSubId，实际 $afterSubId（可能尚未生效）")
        }

        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_system_sim_data_switch_done, slotIndex + 1)))
        return ExecutionResult.Success(
            mapOf(
                "success" to VBoolean(true),
                "sub_id" to VNumber((afterSubId ?: targetSubId).toDouble()),
            )
        )
    }
}
