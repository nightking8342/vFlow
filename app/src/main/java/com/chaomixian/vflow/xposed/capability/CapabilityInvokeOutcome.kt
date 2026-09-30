package com.chaomixian.vflow.xposed.capability

import com.chaomixian.vflow.xposed.wire.CapabilityErrorAction
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.userAction

/**
 * ③ 一次能力调用的**结果类型**（App 侧调用方的返回面）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.2（降级判据）/ §6.3（presence 只避免白试）/ §6.4（失败分类）。
 *
 * ## ⚠️⚠️ 为什么是密封接口而不是抛异常
 *
 * 「能力不可用」在 ③ 里是**一等结果**，不是异常情形：
 *
 * | | 抛异常 | **密封返回（本设计）** |
 * |---|---|---|
 * | 「独占型能力不可用」 | 要调用方 `try/catch` 才知道 | 类型上就写明「这是预期内的结果」 |
 * | 「走了降级路径」 | ❌ **表达不出来** —— 它不是失败，也不是成功 | [Degraded] 显式留痕 |
 * | 漏 catch | 静默崩在某个上游 | 编译期穷尽 |
 *
 * 第二行是本类型存在的**直接理由**（§6.2）：
 * 替换型能力在 Xposed 不可用时**静默降级**，用户视角无变化，但
 * 「同一台设备降级前后结果不同」必须**留痕** —— 否则「昨天能用的快捷方式今天不能用了」
 * 会被当成回归。抛异常的模型里没有地方放这个中间态。
 *
 * ## ⚠️ 本文件为什么在 `xposed/capability/` 而实现为什么在 `core/xposed/`
 *
 * 本包在 `xposed/` 下 ⇒ 会被注入 system_server 的代码加载 ⇒
 * 受 `CapabilityContractPurityTest` 的 import 白名单管辖（**不得引 coroutines**，见
 * `CapabilityPresence` 的类注释）。
 * 而结果类型本身**只是数据**（三个 data class + 两个枚举），没有任何协程依赖 ——
 * 放在这里与 [Capability] / [CapabilityPresence] 同处，让「③ 的类型面」收敛在一个包里。
 *
 * 真正需要协程的调用实现（`suspend fun invoke`）在 `core/xposed/CapabilityInvoker.kt`。
 */
sealed interface CapabilityInvokeOutcome {

    /** 本次调用针对的 capability 名。三个分支都有 —— 调用方不必 `when` 完才知道自己在调什么。 */
    val capability: String

    /**
     * 拿到了真结果。
     *
     * ⚠️ 它**不代表「Xposed 路径可用」** —— `fallback` 的产物与 hook 的产物**都**走这里。
     * 「走没走降级」这件事由**有没有 [Degraded]** 表达，不在本类里再标一个布尔
     * （两个地方表达同一件事必然漂移）。
     */
    data class Success(
        override val capability: String,
        /** capability 的返回值。形状由各 capability 自己定义（信封层不认识业务字段）。 */
        val result: Map<String, Any?>,
        /** hook 侧自报的执行耗时。⚠️ 本端生成的失败（如超时）为 0。 */
        val elapsedMs: Long,
        /** §3.6 契约 4：下一页游标；`null` ⇒ 全量已取完。 */
        val nextCursor: String? = null,
        /** §3.6 契约 3：是否发生过截断。⚠️ 必须传给下游，否则「少了几项」会被当成「本来就没有」。 */
        val truncated: Boolean = false,
    ) : CapabilityInvokeOutcome

    /**
     * 拿到了失败结果，且**没有可用的降级路径**。
     *
     * ⚠️ 这一支同时覆盖两种成因，由 [failure]`.code` 区分：
     * ① **独占型**（`Capability.fallback == null`）—— 无从降级（§6.2）；
     * ② **替换型的降级实现自己抛了异常** —— 有替代实现但它也坏了。
     *
     * 合并成一支是有意的：对调用方而言**两者要做的完全一样**（明确告知 + 引导）。
     * 若拆成两支，下游必然写出 `is Exclusive -> 提示A; is FallbackFailed -> 提示A`
     * 这种重复分支 —— 而本仓库的教训是「两个地方表达同一件事必然漂移」。
     */
    data class Failed(
        override val capability: String,
        val failure: CapabilityFailure,
    ) : CapabilityInvokeOutcome

    /**
     * 走了**降级路径**。
     *
     * ⚠️⚠️ **它的存在就是「留痕」这件事本身**（§6.2）。
     * 替换型能力在 Xposed 不可用时**静默降级**（用户视角无变化），
     * 但降级实现是**有损**的（如 dumpsys 路径的 dat 残缺 + 类型靠猜）⇒
     * **同一台设备降级前后结果不同**。不做留痕的话，
     * 「昨天能用今天不能用」会被当成回归去查，而真实原因是「Xposed 掉线了」。
     *
     * [reason] 是**触发降级的那个失败**：调用方据此告诉用户「为什么走了降级」，
     * 以及「怎么恢复走正路」。
     */
    data class Degraded(
        override val capability: String,
        /** 降级实现的产物。契约是「**同样的入参、同形状的结果、更差的实现**」（§6.2 的 S7 修正）。 */
        val result: Map<String, Any?>,
        val reason: CapabilityFailure,
    ) : CapabilityInvokeOutcome
}

/**
 * 一次失败的**结构化**描述。
 *
 * ## ⚠️⚠️ [detail] 只给人看，绝不参与任何判断（§6.4 约束 2）
 *
 * 它是自由文本、将来会被三语本地化。`if (detail.contains("…"))` 这类写法
 * 等于埋一个「切语言就坏」的雷。**要分支请用 [code]。**
 *
 * 本文件把这条约束落成**可检查**的形式：[userAction] 是**派生**的
 * （`code.userAction()`），而不是另一个可以与之矛盾的字段。
 * `CapabilityInvokeOutcomeTest` 有一条源码扫描断言锁住生产代码里不出现
 * `detail.contains(` / `detail ==`。
 */
data class CapabilityFailure(
    val code: CapabilityErrorCode,
    val detail: String,
) {
    /**
     * 用户该做什么（§6.4 那张表直接落在枚举上，UI 侧不再做字符串判断）。
     *
     * ⚠️ **派生而非独立字段** —— 独立字段会与 `code` 漂移。
     */
    val userAction: CapabilityErrorAction get() = code.userAction()
}

/**
 * 该能力**声明**的降级方案。
 *
 * ## ⚠️ 它**不参与**降级判定
 *
 * ```
 * 状态（本类型 + CapabilityPresence）：提前决定【有没有替代实现 / 要不要试】  ← 优化，可能错
 * 结果（运行时调用的实际结果）：      决定【要不要降级】                      ← 权威，每次实测
 * ```
 *
 * ⇒ 「降级依据永远是运行时结果」（§6.3 定案）。本类型只回答一件事：
 * **这个能力有没有原生替代实现**。判定由
 * `core/xposed/CapabilityInvoker.invokeOrFallback` 先看调用结果、再看本类型。
 */
sealed interface CapabilityFallbackPlan {

    /** 有原生替代实现（`Capability.fallback != null`）⇒ 可静默降级 + 留痕。 */
    data object NativeCode : CapabilityFallbackPlan

    /**
     * 独占型（`Capability.fallback == null`）⇒ **无从降级**（§6.2）。
     *
     * ⚠️ **刻意不复用 [CapabilityInvokeOutcome.Failed]**、也不做成它的子类：
     * 若做成子类，下游会写出 `is Unavailable -> …` 这样的分支，
     * 把「**声明**层面没有替代实现」与「**运行**层面调失败了」混成一个判断。
     * 本仓库的教训是「把某层次的事实平移到另一层次用」（§7.4 反模式 9/10）。
     */
    data object Unavailable : CapabilityFallbackPlan
}
