package com.chaomixian.vflow.core.xposed

/**
 * Xposed 通道的**状态判定**（纯函数层，无 Android 依赖，可纯 JVM 单测）。
 *
 * 设计文档：`docs/fork/xposed-channel-p4-design.md` §3。
 *
 * ## ⚠️⚠️ 为什么是**两组**状态位，而不是一个三态枚举
 *
 * `xposed-channel-design.md` §3.3 的硬要求。压成一个枚举会把
 * **「框架在不在」**与**「事件能不能流过来」**混成一个事实，而它们是独立的：
 *
 * ```
 * 框架好好的（L1 ✅）        但我们的信道断了（L0 ❌）
 *   ⇒ 单看前三个信号会判成「一切正常」，而实际什么都不会触发
 * ```
 *
 * 这正是本仓库反复记录的静默失效形态，所以状态位必须分开。
 *
 * ## 四层信息（来源各不相同，**必须都看**）
 *
 * | 层 | 来源 | 回答 | 谁提供 |
 * |---|---|---|---|
 * | L1 框架 | `onServiceBind` / `onServiceDied` | 框架在不在 | [XposedFrameworkMonitor] |
 * | L2 作用域 | `getScope()` | 勾对没 | 同上 |
 * | L3 挂载 | `getRunningTargets()` | 真注入到 system 了吗 | 同上 |
 * | **L0 通道** | [HookChannelController.isConnected] | **事件能不能流过来** | App 侧自有的 bind |
 *
 * ⚠️ **L1/L2/L3 全对，L0 也可能是断的**（App 被 force-stop 后重建、hook 层刚换代还没重连）。
 * **事件链路走的是 L0** —— 这是本文件存在的全部理由。
 */
object XposedState {

    /** system_server 的进程名。`runningTargets` 里出现它才算「真的挂上了」。 */
    private const val SYSTEM_PROCESS = "system"

    /**
     * 状态位 A：**框架活性** —— 「Xposed 环境可用吗」。
     *
     * 判据来源：L1（`onServiceBind` / `onServiceDied`）。
     */
    enum class Framework {
        /** 从未连上过 —— 没装 LSPosed / 没勾选 / 没重启 */
        UNAVAILABLE,

        /** 连过，但现在断开 —— 框架被停 / 被卸载 */
        DEGRADED,

        /** 当前连着 */
        ACTIVE,
    }

    /**
     * 状态位 B：**通道与挂载** —— 「事件真的能流过来吗」。
     */
    enum class Channel {
        /** hook 层没连上（L0 断）—— 事件不会产生 */
        DISCONNECTED,

        /** 连上了，但 `runningTargets` 里没有 system（L3 缺）—— 注入了但没挂上 */
        NOT_MOUNTED,

        /** 连上且 system 在 `runningTargets` 里 —— 正常 */
        READY,
    }

    /**
     * 判定所需**全部输入**。
     *
     * 做成一个数据类而不是散参数：单测可以直接构造各种组合，
     * 且将来加信号（如 §3.3 的 `PENDING_APPLY`）不必改签名。
     */
    data class Input(
        /** L1：框架当前是否连着（来自 `onServiceBind` 后有 / `onServiceDied` 后无）。 */
        val frameworkConnected: Boolean,
        /** L1：**曾经**连上过吗（持久化的，用于区分「未启用」与「已断开」）。 */
        val everConnected: Boolean,
        /** L2：框架返回的作用域列表。 */
        val scope: List<String>,
        /** L3：`runningTargets` 里的进程名。 */
        val runningTargetNames: List<String>,
        /** L0：App 与自己的 `HookChannelService` 连上了没。 */
        val channelConnected: Boolean,
    )

    /** 判定结果：两个状态位。 */
    data class Result(val framework: Framework, val channel: Channel)

    /**
     * 判定。
     *
     * ⚠️ **注意 `Channel` 不依赖 `Framework`** —— 这是刻意的：
     * 它们测量的是两件独立的事。把它们耦合起来（如「框架不在 ⇒ 通道必然断」）
     * 会让「框架在但通道断」这一格**永远判不出来**，而那恰好是最需要展示的一格。
     */
    fun evaluate(input: Input): Result = Result(
        framework = evaluateFramework(input),
        channel = evaluateChannel(input),
    )

    private fun evaluateFramework(input: Input): Framework = when {
        input.frameworkConnected -> Framework.ACTIVE
        input.everConnected -> Framework.DEGRADED
        else -> Framework.UNAVAILABLE
    }

    /**
     * 判定状态位 B。
     *
     * ⚠️ **`NOT_MOUNTED` 的判据有个细节**：优先信 `runningTargets`（实际结果），
     * 而不是 `scope`（配置）。
     *
     * 两者理论上该一致，但**不一致时以实际结果为准**：
     * `scope` 说勾了、`runningTargets` 里没有 ⇒ 实际就是没挂上（可能没重启、可能被框架跳过）。
     * 反过来（scope 没有但 targets 有）则说明用户刚改了配置、还没重启 —— 也是「实际为准」。
     */
    private fun evaluateChannel(input: Input): Channel = when {
        // L0 断开：事件根本流不过来。**先判这条** ——
        // 因为下面的 L3 信号在「App 侧还没连上」时是拿不到的（Monitor 可能还没收到 bind）
        !input.channelConnected -> Channel.DISCONNECTED

        // L3：实际注入的进程里有 system
        input.runningTargetNames.any { it.equals(SYSTEM_PROCESS, ignoreCase = true) } ->
            Channel.READY

        else -> Channel.NOT_MOUNTED
    }

    /**
     * 是否「一切正常」。
     *
     * ⚠️ 只有**两个状态位同时正常**才算。UI 用它决定显示成功态还是失败态。
     */
    fun isHealthy(result: Result): Boolean =
        result.framework == Framework.ACTIVE && result.channel == Channel.READY

    /**
     * 是否需要**引导用户去配置**（而不是「等一会儿就好」）。
     *
     * UI 据此决定点击行为：`true` → 弹引导；`false` → 提供「重连」。
     */
    fun needsGuidance(result: Result): Boolean = when {
        // 从没启用过 ⇒ 必须引导
        result.framework == Framework.UNAVAILABLE -> true
        // 框架有问题、或没挂上 ⇒ 引导（多半是作用域/重启问题）
        result.framework == Framework.DEGRADED -> true
        result.channel == Channel.NOT_MOUNTED -> true
        // ⚠️ ACTIVE + DISCONNECTED **不引导** —— 框架是好的，问题在我们自己的连接，
        // 用户去 LSPosed 里折腾没用。这一格是「最容易被误判成框架问题」的那个
        else -> false
    }

    /**
     * 点击状态卡时该做什么。
     *
     * ## ⚠️ 为什么把它做成枚举 + 纯函数
     *
     * 我第一版在 UI 里写成 `if (needsGuidance(r)) 弹引导 else 弹重连提示` ——
     * 而 `needsGuidance` 对**正常状态**也返回 false，于是
     * **一切正常时点卡片会弹「通道正在重连」**（实测核对时发现）。
     *
     * 把分类抽到这里，是为了让「哪种状态点出什么」**可被单测锁住** ——
     * 它属于「改错了不报错、只是提示莫名其妙」的那一类。
     */
    enum class TapAction {
        /** 弹引导对话框（含实时状态，正常时也能用来看当前作用域）。 */
        GUIDE,

        /**
         * 只弹「正在重连」提示，**不引导**。
         *
         * ⚠️ 仅用于 `ACTIVE + DISCONNECTED`：框架是好的，断的是我们自己的连接。
         * 让用户去 LSPosed 里折腾只会白费功夫、还可能把**本来正确的**配置改坏。
         */
        RECONNECT_HINT,
    }

    /** 点击行为分类。纯函数，可单测。 */
    fun tapAction(result: Result?): TapAction = when {
        // 还没拿到状态 → 引导（此时对话框会显示「未知」）
        result == null -> TapAction.GUIDE

        // ⭐ 只有这一格给「重连」提示 —— 其余一律引导
        result.framework == Framework.ACTIVE && result.channel == Channel.DISCONNECTED ->
            TapAction.RECONNECT_HINT

        else -> TapAction.GUIDE
    }

    /**
     * 作用域显示串（给引导对话框的「自我核对」用）。
     *
     * ⚠️ 空列表不是「没勾」而是「拿不到」—— 区分开，否则会给用户错误信息。
     */
    fun describeScope(scope: List<String>): String =
        if (scope.isEmpty()) "未知" else scope.joinToString(", ")
}
