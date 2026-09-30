package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.capability.Capability
import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.capability.CapabilityRegistry
import com.chaomixian.vflow.xposed.capability.CapabilityRisk
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
 * **把崩溃半径从「那个 App」扩大到整个系统**。
 *
 * ## ⚠️⚠️ 本文件当前注册的是 `diagnostic`（**开发期自证用**）
 *
 * 它是「池满 / 超时 / 截断 / 异常」四条失败路径的**唯一可控探针** ——
 * 那三条路径用真实 capability 很难稳定触发，而失败路径出错时**往往静默**。
 *
 * ⚠️ **它必须在这里注册**，否则 `CapabilityInvoker` 的调用入口**第一步**
 * `CapabilityRegistry.find(name)` 就把它拦下（`Failed(HANDLER_ERROR, "未注册的 capability：…")`）
 * —— 而**那一步在 App 侧、在提交给 hook 层之前** ⇒ hook 侧永远收不到请求。
 *
 * ## 加新 capability（含 T3 的首个真实能力）= 这里追加一次 `register`
 *
 * 不必新建文件、**不必动 `xposed/` 下的任何东西**（那才是 `FORK.md` 里
 * 「控制 diff 面积」原则的直接兑现）。
 */
object CapabilityFallbacks {

    private const val TAG = "CapabilityFallbacks"

    private val registered = AtomicReference(false)

    /**
     * 装配全部 capability（含 `fallback` lambda）。**幂等**。
     *
     * 由 `CapabilityRuntime.attach` 调用一次。
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
        DebugLogger.d(TAG, "能力表装配完成（已注册：${CapabilityRegistry.names().sorted()}）")
    }

    /** **仅供测试**：回到初始状态（本对象是单例，`registered` 会串用例）。 */
    internal fun resetForTest() {
        registered.set(false)
    }
}
