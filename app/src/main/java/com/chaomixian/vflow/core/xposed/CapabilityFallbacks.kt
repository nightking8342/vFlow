package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.capability.Capability
import com.chaomixian.vflow.xposed.capability.CapabilityFallbackPlan
import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.capability.CapabilityRegistry
import com.chaomixian.vflow.xposed.capability.CapabilityRisk
import java.util.concurrent.atomic.AtomicReference

/**
 * ③ **降级实现 / 能力注册**的落点（App 侧，位于 `xposed/` **之外**）。
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
 * **把崩溃半径从「那个 App」扩大到整个系统**。
 *
 * ## ⚠️ 本文件是**唯一的 App 侧注册落点**
 *
 * 加新 capability（含 T3 的首个真实能力 `query_shortcut_intents`）= 在
 * [registerAll] 里追加一次 `register`，**不必新建文件、不必动 `xposed/` 下的任何东西**
 *（那才是 `FORK.md` 里「控制 diff 面积」原则的直接兑现）。
 *
 * ⚠️ 但**别往这里塞「测试用的假能力」** —— 那会让它出现在 **release 的生产启动路径**上。
 * 单测需要假能力时直接调 `CapabilityRegistry.register(...)`，并在 `@After` 里
 * `resetForTest()`。本仓库的纪律是「不留未实现的调用点」。
 *
 * ## 当前注册的内容：`diagnostic`（**开发期自证用**）
 *
 * 见 [registerAll] 里的注释。⚠️ 它**必须在这里注册**，否则 `CapabilityInvoker` 的
 * 调用入口**第一步** `CapabilityRegistry.find(name)` 就把它拦下
 * （`Failed(HANDLER_ERROR, "未注册的 capability：…")`）—— 而**那一步在 App 侧、
 * 在提交给 hook 层之前** ⇒ hook 侧永远收不到请求，四条失败路径一条都验不了。
 */
object CapabilityFallbacks {

    private const val TAG = "CapabilityFallbacks"

    private val registered = AtomicReference(false)

    /**
     * 在 `xposed/` **之外**装配全部 capability（含 `fallback` lambda）。**幂等**。
     *
     * 由 [CapabilityRuntime.attach] 调用一次。
     *
     * ## T3 加首个真实能力的格式（给 T3 的接口说明）
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

        // ── 诊断能力（hook 侧执行体：`capabilities/DiagnosticCapabilityHandler.kt`）──
        //
        // ⚠️ `fallback = null` ⇒ 【独占型】（§6.2）：诊断能力只可能由 Xposed 通道提供，
        //    没有替代实现 ⇒ 不可用时「无从降级」，而不是「静默换个数据源」。
        //
        // ⚠️ `risk = READ_ONLY`：无副作用（`slow` 只 sleep、`huge` 只造数据、
        //    `throw` 只抛异常）⇒ 留在生产包里是安全的。
        //
        // ⚠️ `maxResultBytes` / `timeoutMs` 留 `null` ⇒ 两端都用默认值
        //    （256 KiB / 请求里的 `timeout_ms`）⇒ 不会出现
        //    「App 以为 5s、hook 按 3s 算」的错配。
        CapabilityRegistry.register(
            Capability(
                name = CapabilityNames.DIAGNOSTIC,
                risk = CapabilityRisk.READ_ONLY,
                fallback = null,
            ),
        )

        // ⚠️ 打一行日志而不是默默注册：排查「调用回 capability_absent」时，
        // 「注册表里到底有没有东西」是第一个要问的问题，而这行日志是它的答案。
        // （空表与「注册代码没跑到」在没有这行日志时长得一模一样。）
        DebugLogger.d(TAG, "能力表装配完成（已注册：${CapabilityRegistry.names().sorted()}）")
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
