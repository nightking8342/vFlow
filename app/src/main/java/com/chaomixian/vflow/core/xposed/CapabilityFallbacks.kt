package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.capability.CapabilityFallbackPlan
import com.chaomixian.vflow.xposed.capability.CapabilityRegistry
import java.util.concurrent.atomic.AtomicReference

/**
 * ③ **降级实现**的注册处（App 侧，位于 `xposed/` **之外**）。
 *
 * ## ⚠️⚠️ 为什么它必须在这个文件里，而不是在 `CapabilityRegistry.kt` 里
 *
 * `CapabilityRegistry` 的类注释写死了这条分层：
 *
 * > `Capability.fallback` 声明的是**一个函数类型**（纯 Kotlin，不引任何 App 类），
 * > 但**它的实现必然引用 App 侧代码** —— 例如快捷方式的降级路径是 dumpsys，
 * > 实现体在 `ui/shortcut_picker/ShortcutPickerSupport`。
 * >
 * > 而 `capability/` 包在 `xposed/` 下，受「不得引用 `com.chaomixian.vflow.ui.`」
 * > 的扫描管辖 ⇒ **具体的注册动作（把 lambda 装进去）必须发生在 `xposed/` 之外**。
 *
 * ```
 * xposed/capability/CapabilityRegistry.kt   ← 只放注册表 + 类型（不得引 App 侧）
 * core/xposed/CapabilityFallbacks.kt        ← 本文件：在这里 register(Capability(…, fallback = { … }))
 * ```
 *
 * ⚠️ **不要图省事在 `CapabilityRegistry.kt` 里 `import` 那些实现** ——
 * `CapabilityContractPurityTest` 会红，且真在 system_server 里加载了它们会
 * **把崩溃半径从「那个 App」扩大到整机**。
 *
 * ## ⚠️ T1 阶段本对象是**空实现**（这是刻意的，不是没写完）
 *
 * 首个真实的 capability（`CapabilityNames.QUERY_SHORTCUT_INTENTS`）是 **T3** 的交付物 ——
 * 它需要先有 hook 侧的 handler（T2）才有意义。
 *
 * ⇒ 本文件当前 `registerAll()` 里**一条注册都没有**，`CapabilityRegistry` 在生产路径上
 * **保持为空**。单测需要用假能力时**直接调 `CapabilityRegistry.register(...)`**
 * （并在 `@After` 里 `resetForTest()`），**不**通过本对象。
 *
 * ⚠️ 这一点很重要：往这里塞「测试用的假能力」会让它出现在 **release 的生产启动路径**上，
 * 而这正是本仓库「不留未实现的调用点」纪律要避免的。
 *
 * ## 那本文件现在存在的意义是什么
 *
 * **把注册落点固定下来**：T3 加能力时只需在此追加一行 `CapabilityRegistry.register(...)`，
 * 不必新建文件、**不必动 `xposed/` 下的任何东西**（那才是 `FORK.md` 里
 * 「控制 diff 面积」原则的直接兑现）。同时它也把上面那条分层要求
 * 变成一个**可被引用的具体位置**，而不是只写在别人的注释里
 *（§7.4 反模式 5：约束只写在注释里 = 等于没写）。
 */
object CapabilityFallbacks {

    private const val TAG = "CapabilityFallbacks"

    private val registered = AtomicReference(false)

    /**
     * 在 `xposed/` **之外**装配全部 capability（含 `fallback` lambda）。**幂等**。
     *
     * 由 [CapabilityRuntime.attach] 调用一次。
     *
     * ## ⚠️ T3 在此追加，格式如下（不是现在写的，是给 T3 的接口说明）
     *
     * ```kotlin
     * CapabilityRegistry.register(
     *     Capability(
     *         name = CapabilityNames.QUERY_SHORTCUT_INTENTS,
     *         risk = CapabilityRisk.READ_ONLY,
     *         // ⚠️ fallback 必须接收 params（§6.2 的 S7 修正）：
     *         // 契约是「同样的入参、同形状的结果、更差的实现」——
     *         // 无参版本会让每个降级实现自己去读外部状态，
     *         // 重演「各消费者只算自己那份」（§7.4 反模式 2）
     *         fallback = { params -> ShortcutPickerSupport.queryViaDumpsys(params) },
     *         maxResultBytes = 64 * 1024,   // §3.6：声明得越小越安全
     *         timeoutMs = 3_000,
     *     ),
     * )
     * ```
     */
    fun registerAll() {
        if (!registered.compareAndSet(false, true)) return

        // ⚠️ T1：**刻意为空**（见类注释）。这里不放任何注册。
        //
        // 若将来有人想在此加「测试用假能力」——请不要：那会让它进生产启动路径。
        // 单测直接用 CapabilityRegistry.register(...) + resetForTest()。

        // ⚠️ 打一行日志而不是默默 return：T3 加能力时，「注册表里到底有没有东西」
        // 是排查「调用回 capability_absent」的第一个问题，而空表与「注册代码没跑到」
        // 在**没有这行日志**时长得一模一样。
        DebugLogger.d(TAG, "能力表装配完成（T1 的预期状态：注册表为空，首个 capability 由 T3 注册）")
    }

    /**
     * 某能力的降级方案（§6.2 的两分：替换型 / 独占型）。
     *
     * ⚠️ **它不参与降级判定** —— 判定永远看运行时调用结果（§6.3 定案）。
     * 本方法只回答「**声明**里有没有原生替代实现」。
     * `CapabilityInvoker.invokeOrFallback` 里读的是同一个判据
     * （`cap.fallback != null`）；本方法存在的意义是让「降级方案」这个概念
     * 有一个**可被断言的名字**，避免两处口径漂移。
     */
    fun planOf(capability: String): CapabilityFallbackPlan =
        CapabilityInvoker.fallbackPlanOf(capability)

    /** **仅供测试**：回到初始状态（本对象是单例，`registered` 会串用例）。 */
    internal fun resetForTest() {
        registered.set(false)
    }
}
