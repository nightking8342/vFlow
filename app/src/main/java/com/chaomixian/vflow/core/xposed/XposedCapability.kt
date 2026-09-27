package com.chaomixian.vflow.core.xposed

import android.content.Context

/**
 * Xposed 通道的**能力判据**（供 `PermissionManager.XPOSED_HOOK` 使用）。
 *
 * ## ⚠️⚠️ 判据是**实时**的：模块当前是否真的在生效
 *
 * 与 `shizukuStrategy`（判 `ShellManager.isShizukuActive`）同一语义 ——
 * **「现在能不能用」**，而不是「历史上有没有过」。
 *
 * ### 一次被推翻的设计（记在这里避免重蹈）
 *
 * 本类**最初**用 SharedPreferences 记「曾经连上过」，理由是：
 *
 * > `TriggerService` 加载触发器比 hook 层连接**早约 1.6 秒**（实测），
 * > 实时判据会让启动瞬间判「缺权限」⇒ 工作流被自动禁用。
 *
 * **那个担心是真的，但解法用错了地方** —— 它把权限判据变成了「永久授权」，
 * 于是：
 *
 * 1. **用户在 LSPosed 里关掉模块后，权限页仍显示「已授权」**（实测确认）；
 * 2. 而 vFlow 的**其他所有权限判据都是实时的**（Shizuku / Core / 无障碍…）
 *    ⇒ XPOSED 成了**体系里的异类**；
 * 3. 更糟的是「已授权」的显示会**掩盖真实的失效**，用户对着「触发器不工作」发呆。
 *
 * ### 改成实时之后的实际行为（已核实，闭环完整）
 *
 * 关掉 LSPosed 模块 ⇒ 判缺权限 ⇒ 工作流被**自动禁用**（与其它权限一致）。
 * 重新打开模块 + 重启设备后，`WorkflowPermissionRecovery.recoverEligibleWorkflows`
 * 会把它**自动恢复** —— 该恢复逻辑是**通用**的（6 处触发点，含
 * `PermissionGuardianService` 每 10 秒一次的轮询），不必用户手动重开。
 *
 * ⚠️ 因此**不需要**给 XPOSED 加「不禁用」的豁免 ——
 * 那会引入又一个写死 id 的后门（`TriggerService` 里已有一个给无障碍的），
 * 等于再造一个异类。
 *
 * ## ⚠️ 启动时序的处理（不是靠判据，而是靠恢复逻辑）
 *
 * `TriggerService.onCreate` 那一刻 hook 可能还没连上（实测早约 1.6 秒），
 * 于是**那一次**恢复尝试会跳过。但这不要紧 ——
 * `PermissionGuardianService` 每 10 秒重试，hook 连上后即恢复。
 *
 * **这是「把时序问题交给幂等的重试」而不是「把判据放宽」** ——
 * 后者会污染权限模型（本次修正的原因）。
 */
object XposedCapability {

    /**
     * 能力是否具备（判据：**框架此刻连着**）。
     *
     * @return false 表示当前不可用 —— 未装 / 未勾选 / 未重启 / 模块被停用
     */
    fun isGranted(context: Context): Boolean =
        XposedFrameworkMonitor.state.value.frameworkConnected

    /**
     * 当前是否**真的连着**（与 [isGranted] 同源，保留本方法是为了让调用点语义更清楚）。
     *
     * ⚠️ 别把它接到 `isGranted` 之外的用途上 —— 见 [XposedState] 的组合判定：
     * 「框架连着」不等于「功能可用」（还要看通道 L0 与挂载 L3）。
     */
    fun isLiveConnected(): Boolean = HookChannelController.isConnected()
}
