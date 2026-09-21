package com.chaomixian.vflow.core.workflow.module.triggers

import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.service.notification.Condition
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
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * 免打扰模式触发器。
 *
 * 当系统勿扰（Do Not Disturb）状态发生变化时触发工作流。
 *
 * 权限说明：本模块**只需** [PermissionManager.NOTIFICATION_POLICY]（勿扰访问），
 * **不需要**通知使用权（NOTIFICATION_LISTENER_SERVICE）。原因是监听走
 * [NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED] 广播，而从 Android 10(Q) 起
 * 该广播只投递给「已获得勿扰访问权限」的包。授权同时解锁读与写。
 */
class DoNotDisturbTriggerModule : BaseModule() {

    companion object {
        const val STATE_ON = "on"
        const val STATE_OFF = "off"
        const val STATE_ANY = "any"

        /** 历史本地化文案 -> 稳定常量。 */
        private val STATE_LEGACY_MAP = mapOf(
            "开启时" to STATE_ON,
            "打开时" to STATE_ON,
            "开启" to STATE_ON,
            "关闭时" to STATE_OFF,
            "关闭" to STATE_OFF,
            "任意" to STATE_ANY,
            "任意变化" to STATE_ANY
        )

        /**
         * vFlow 自建勿扰规则的 SharedPreferences 键。
         *
         * 与 `DoNotDisturbModule` 的私有常量保持一致，刻意重复而非改动上游文件
         * （见 FORK.md「控制 diff 面积」）。仅用于 API 35+ 的兜底读取路径；
         * 若上游改了这两个字面量，此处会静默回退到 false，影响面有限。
         */
        internal const val DND_PREFS_NAME = "vflow_do_not_disturb"
        internal const val DND_PREF_RULE_ID = "automatic_zen_rule_id"
    }

    override val id = "vflow.trigger.do_not_disturb"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_do_not_disturb_name,
        descriptionStringRes = R.string.module_vflow_trigger_do_not_disturb_desc,
        name = "免打扰触发",  // Fallback
        description = "当系统免打扰模式开启或关闭时触发工作流",  // Fallback
        iconRes = R.drawable.rounded_do_not_disturb_on_24,
        category = "触发器",
        categoryId = "trigger"
    )

    override val requiredPermissions = listOf(PermissionManager.NOTIFICATION_POLICY)

    override val uiProvider = null

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "target_state",
            name = "触发条件",
            nameStringRes = R.string.param_vflow_trigger_do_not_disturb_state_name,
            staticType = ParameterType.ENUM,
            defaultValue = STATE_ON,
            options = listOf(STATE_ON, STATE_OFF, STATE_ANY),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_do_not_disturb_on,
                R.string.option_vflow_trigger_do_not_disturb_off,
                R.string.option_vflow_trigger_do_not_disturb_any
            ),
            legacyValueMap = STATE_LEGACY_MAP,
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "enabled",
            name = "免打扰已开启",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_do_not_disturb_enabled_name
        ),
        OutputDefinition(
            id = "previous_enabled",
            name = "变化前是否开启",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_do_not_disturb_previous_enabled_name
        )
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val targetState = getInputs().normalizeEnumValue(
            "target_state",
            step.parameters["target_state"] as? String,
            STATE_ON
        ) ?: STATE_ON

        val displayText = when (targetState) {
            STATE_OFF -> context.getString(R.string.option_vflow_trigger_do_not_disturb_off)
            STATE_ANY -> context.getString(R.string.option_vflow_trigger_do_not_disturb_any)
            else -> context.getString(R.string.option_vflow_trigger_do_not_disturb_on)
        }

        val conditionPill = PillUtil.Pill(displayText, "target_state", isModuleOption = true)
        val prefix = context.getString(R.string.summary_vflow_trigger_do_not_disturb_prefix)

        return PillUtil.buildSpannable(context, prefix, " ", conditionPill)
    }

    /**
     * 触发器模块本身不做实际动作——真实触发由 [com.chaomixian.vflow.core.workflow.module.triggers.handlers.DoNotDisturbTriggerHandler]
     * 完成。此方法只在 [com.chaomixian.vflow.core.execution.WorkflowExecutor.seedTriggerOutputs]
     * 里被调用一次，用于给下游提供「无事件数据时」的占位输出。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        onProgress(ProgressUpdate("免打扰触发器已就绪"))
        return ExecutionResult.Success(
            mapOf(
                "enabled" to com.chaomixian.vflow.core.types.basic.VBoolean(readDndEnabled(context.applicationContext)),
                "previous_enabled" to com.chaomixian.vflow.core.types.basic.VBoolean(false)
            )
        )
    }
}

/**
 * 纯函数：把 interruption filter 映射为「勿扰是否开启」。
 *
 * 返回 `null` 表示取值不可用（[NotificationManager.INTERRUPTION_FILTER_UNKNOWN]），
 * 调用方应回退到其它判据。
 *
 * 映射依据 AOSP `NotificationManager.java` 的 ZEN_MODE_* ↔ INTERRUPTION_FILTER_* 对照：
 * ```
 * ZEN_MODE_OFF                     -> INTERRUPTION_FILTER_ALL      (1) 关
 * ZEN_MODE_IMPORTANT_INTERRUPTIONS -> INTERRUPTION_FILTER_PRIORITY (2) 开
 * ZEN_MODE_NO_INTERRUPTIONS        -> INTERRUPTION_FILTER_NONE     (3) 开
 * ZEN_MODE_ALARMS                  -> INTERRUPTION_FILTER_ALARMS   (4) 开
 * ```
 * 即「非 ALL 且非 UNKNOWN」即为开启。
 *
 * 独立成顶层纯函数以便单测——这是本模块最易出错、也最值得锁定的逻辑。
 */
internal fun isDndFilterEnabled(filter: Int): Boolean? {
    return when (filter) {
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> null
        NotificationManager.INTERRUPTION_FILTER_ALL -> false
        else -> true
    }
}

/**
 * 读取「系统免打扰当前是否开启」。
 *
 * 分层取值：
 * 1. 全局 interruption filter —— 覆盖用户手动开关，语义最贴近「系统勿扰」；
 * 2. 当 filter 不可用且系统为 API 35+ 时，回退到 vFlow 自建自动规则的状态。
 *
 * ⚠️ 第 2 层未经真机验证：Android 16 引入 Modes 后勿扰被泛化为多个具名 mode，
 * `getAutomaticZenRuleState(vFlow 自己的 ruleId)` 只反映 vFlow 规则本身，
 * 用户在系统设置里开启的勿扰可能读不到。
 * 详见 `docs/fork/surveys/trigger-dnd-design.md`；真机验证方式见
 * [DoNotDisturbTriggerHandler] 里 `DndProbe` 输出的诊断日志。
 *
 * 未授权时返回 `false`（调用方 `TriggerService` 已在注册前拦截缺权限的工作流）。
 */
internal fun isDndEnabled(context: Context): Boolean {
    return readDndEnabled(context.applicationContext)
}

private fun readDndEnabled(appContext: Context): Boolean {
    val notificationManager = appContext
        .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        ?: return false

    if (!notificationManager.isNotificationPolicyAccessGranted) return false

    isDndFilterEnabled(notificationManager.currentInterruptionFilter)?.let { return it }

    // filter 不可用时的兜底：仅在 API 35+ 有 getAutomaticZenRuleState。
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
        val ruleId = readVFlowZenRuleId(appContext) ?: return false
        return notificationManager.getAutomaticZenRuleState(ruleId) == Condition.STATE_TRUE
    }

    return false
}

/**
 * 诊断探针：把勿扰相关的原始读数写进 vFlow 日志（`DebugLogger` → 应用内日志页）。
 *
 * 存在的意义是**免改上游文件的真机验证手段**——无需新增 Activity、无需动
 * `AndroidManifest.xml`，就能把不同 ROM 上的真实取值拿到手，用来定稿
 * [isDndEnabled] 的分层策略。生产环境开销可忽略（仅在勿扰变化时调用一次）。
 */
internal fun probeDndState(context: Context): String {
    val appContext = context.applicationContext
    val notificationManager = appContext
        .getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    if (notificationManager == null) return "DndProbe: NOTIFICATION_SERVICE unavailable"

    val granted = notificationManager.isNotificationPolicyAccessGranted
    val filter = notificationManager.currentInterruptionFilter
    val filterName = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL(1)"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY(2)"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE(3)"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS(4)"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "UNKNOWN(0)"
        else -> "UNRECOGNIZED($filter)"
    }

    val ruleId = readVFlowZenRuleId(appContext)
    val ruleState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM && ruleId != null) {
        when (notificationManager.getAutomaticZenRuleState(ruleId)) {
            Condition.STATE_TRUE -> "TRUE"
            Condition.STATE_FALSE -> "FALSE"
            else -> "UNKNOWN(${Condition.STATE_UNKNOWN})"
        }
    } else {
        "n/a"
    }

    return buildString {
        append("DndProbe: sdk=${Build.VERSION.SDK_INT}")
        append(" accessGranted=$granted")
        append(" filter=$filterName")
        append(" vflowRule=$ruleId")
        append(" vflowRuleState=$ruleState")
        append(" resolved=${readDndEnabled(appContext)}")
    }
}

/** 读取 vFlow 自建勿扰规则的 id（由 `DoNotDisturbModule` 写入）。 */
private fun readVFlowZenRuleId(context: Context): String? {
    return context
        .getSharedPreferences(DoNotDisturbTriggerModule.DND_PREFS_NAME, Context.MODE_PRIVATE)
        .getString(DoNotDisturbTriggerModule.DND_PREF_RULE_ID, null)
}
