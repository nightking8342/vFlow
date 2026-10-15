package com.chaomixian.vflow.ui.workflow_editor

import android.content.Context
import com.chaomixian.vflow.ui.viewmodel.SettingsViewModel
import com.chaomixian.vflow.core.workflow.model.ActionStepExecutionSettings

internal fun ActionEditorSessionState.getExecutionSettings(): ActionStepExecutionSettings {
    return ActionStepExecutionSettings.fromParameters(asMap())
}

internal fun ActionEditorSessionState.setExecutionSettings(settings: ActionStepExecutionSettings) {
    this[ActionStepExecutionSettings.KEY_ERROR_POLICY] = settings.policy

    if (settings.policy == ActionStepExecutionSettings.POLICY_RETRY) {
        this[ActionStepExecutionSettings.KEY_RETRY_COUNT] = settings.retryCount
        this[ActionStepExecutionSettings.KEY_RETRY_INTERVAL] = settings.retryIntervalMillis
        // fork（P0-1 策略链）：重试用尽后的动作。只在 RETRY 时写 —— 其它策略下这个键
        // 没有语义（执行器只在 `effectivePolicy` 里读它），留着会让工作流文件里多一个
        // 「看起来有效、实际被忽略」的字段。
        this[ActionStepExecutionSettings.KEY_ON_RETRY_EXHAUSTED] = settings.onRetryExhausted
    } else {
        remove(ActionStepExecutionSettings.KEY_RETRY_COUNT)
        remove(ActionStepExecutionSettings.KEY_RETRY_INTERVAL)
        remove(ActionStepExecutionSettings.KEY_ON_RETRY_EXHAUSTED)
    }

    // fork（P0-5）：失败后跳到哪一步（存 step id）。
    //
    // ⚠️ 判据用**有效策略**（不是 `settings.policy`）—— 因为「RETRY + 用尽后 = SKIP」也会
    //    走跳过分支（`WorkflowExecutor` 里判的就是有效策略）。只认 `policy == SKIP` 的话，
    //    「重试 3 次，还不行就跳到收尾步骤」这个最自然的组合在编辑器里**配不出来**。
    //
    // ⚠️ 空白一律**删键**，不写空串：空串会被执行期当成一个（永远解析不到的）目标 id，
    //    每次失败都多打一条「目标不可用」的 W 日志。
    val jumpTarget = settings.skipJumpTarget?.trim().orEmpty()
    val effectivePolicy = ActionStepExecutionSettings.effectivePolicy(
        settings.policy,
        settings.onRetryExhausted
    )
    if (effectivePolicy == ActionStepExecutionSettings.POLICY_SKIP && jumpTarget.isNotEmpty()) {
        this[ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET] = jumpTarget
    } else {
        remove(ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET)
    }
}

internal fun ActionEditorSessionState.applyDefaultExecutionSettings(context: Context) {
    val prefs = context.getSharedPreferences(SettingsViewModel.PREFS_NAME, Context.MODE_PRIVATE)
    val policy = prefs.getString(
        "defaultErrorPolicy",
        ActionStepExecutionSettings.POLICY_STOP
    ) ?: ActionStepExecutionSettings.POLICY_STOP
    val retryCount = prefs.getInt(
        "defaultRetryCount",
        ActionStepExecutionSettings.DEFAULT_RETRY_COUNT
    )
    val retryInterval = prefs.getLong(
        "defaultRetryInterval",
        ActionStepExecutionSettings.DEFAULT_RETRY_INTERVAL_MS
    )
    setExecutionSettings(
        ActionStepExecutionSettings(
            policy = policy,
            retryCount = retryCount,
            retryIntervalMillis = retryInterval
        )
    )
}
