package com.chaomixian.vflow.core.xposed

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住 ③ 的**接线点真的存在**。
 *
 * ## ⚠️⚠️ 为什么必须有这一层
 *
 * 本仓库反复记录的反模式 6 是「**写了调用点注释、但没有调用点**」。
 * 最典型的一次是 `CoreDexFingerprint`：13 个纯函数单测**全绿**，而集成点缺失
 * ⇒ 表现是「永远不提示重启，用户静默跑旧 Core 代码」，且**没有任何测试变红**。
 *
 * 「接线」这件事**在运行时测不出来**：
 * - `CapabilityRuntime.attach` 的**单元测试**能证明它自己是对的（它确实会接线）；
 * - 但**没有任何行为测试**能证明「生产代码真的调了它」——
 *   除非真跑一遍 `VFlowApplication.onCreate`（纯 JVM 里做不到）。
 *
 * ⇒ 与 `CoreDexFingerprintTest` 的源码扫描型断言同款做法。
 *
 * ## 它检查什么
 *
 * | # | 断言 | 缺失的后果 |
 * |---|---|---|
 * | 1 | `VFlowApplication` 里调了 `CapabilityRuntime.attach(` | ③ 永远不接线 ⇒ 能力交换不发生 ⇒ 每次调用白等 5 秒 |
 * | 2 | `CapabilityRuntime.attach` 里调了 `CapabilityPresenceHolder.start(` | 接线了但不接监听器 ⇒ 同上 |
 * | 3 | `CapabilityRuntime.attach` 里调了 `CapabilityFallbacks.registerAll(` | T3 加能力时会漏掉接线（T3 只需在 `CapabilityFallbacks` 里追加） |
 * | 4 | `HookChannelController.onCallbackUnregistered` 里调了 `notifyOnDisconnected()` | presence 永不失效 ⇒ 「状态说 READY、调用全失败」 |
 *
 * ⚠️ 第 4 条尤其值得单独锁：`onCallbackUnregistered` 有**早退分支**，
 * 而「断开回调没被触发」这件事在运行时**只在真实断连时才暴露** ——
 * 单测走的是直接调 `notifyOnDisconnected()`（绕过 Controller），
 * 所以「Controller 到底有没有调它」测不出来。
 */
class CapabilityRuntimeWiringTest {

    private fun file(path: String): File {
        val f = File(path)
        assertTrue("文件应当存在：${f.absolutePath}", f.isFile)
        return f
    }

    /** 剥掉注释（与 `WireLayerPurityTest` 同一实现思路）。 */
    private fun codeLines(f: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlock = false
        f.readLines().forEachIndexed { i, raw ->
            val t = raw.trim()
            if (inBlock) {
                if (t.contains("*/")) inBlock = false
                return@forEachIndexed
            }
            if (t.startsWith("/*")) {
                if (!t.contains("*/")) inBlock = true
                return@forEachIndexed
            }
            if (t.startsWith("//")) return@forEachIndexed
            out += (i + 1) to raw
        }
        return out
    }

    /** 断言某文件（去注释后）里出现某个调用。 */
    private fun assertContainsCall(path: String, needle: String, why: String) {
        val f = file(path)
        val hits = codeLines(f).filter { it.second.contains(needle) }
        assertTrue(
            "❌ $path 里找不到调用 `$needle`。\n$why",
            hits.isNotEmpty(),
        )
    }

    // ── ① 生产启动路径上的接线 ────────────────────────────────

    @Test
    fun `VFlowApplication wires up the capability runtime`() {
        assertContainsCall(
            "src/main/java/com/chaomixian/vflow/VFlowApplication.kt",
            "CapabilityRuntime.attach(",
            "⚠️ 反模式 6（写了调用点注释但没有调用点）。\n" +
                "没有这一行：③ 的能力交换永远不会发生 ⇒ presence 停在 UNKNOWN ⇒\n" +
                "`CapabilityInvoker` 的 ABSENT 短路永不生效 ⇒ **每次调用白等满 5 秒超时**。\n" +
                "（本仓库的 `CoreDexFingerprint` 就是这样丢了一次集成点，13 个纯函数单测全绿。）",
        )
    }

    @Test
    fun `runtime attach actually starts the presence holder`() {
        assertContainsCall(
            "src/main/java/com/chaomixian/vflow/core/xposed/CapabilityRuntime.kt",
            "CapabilityPresenceHolder.start(",
            "接线了但没接监听器 —— 与「没接线」等价。\n" +
                "⚠️ 注意它必须在 `if (enabled)` **之外**：开关只控制「探测」，\n" +
                "接线是纯注册（无跨进程调用）。若被开关挡住，重新打开开关也永远不生效。",
        )
    }

    @Test
    fun `runtime attach registers the capability table`() {
        assertContainsCall(
            "src/main/java/com/chaomixian/vflow/core/xposed/CapabilityRuntime.kt",
            "CapabilityFallbacks.registerAll(",
            "⚠️ T3 加能力时只需在 `CapabilityFallbacks.registerAll()` 里追加一行 ——\n" +
                "但那条路必须**现在**就接通，否则 T3 会漏掉接线（反模式 6）。",
        )
    }

    // ── ② 断连路径上的接线 ────────────────────────────────────

    @Test
    fun `controller notifies disconnect listeners on unregister`() {
        assertContainsCall(
            "src/main/java/com/chaomixian/vflow/core/xposed/HookChannelController.kt",
            "notifyOnDisconnected()",
            "⚠️⚠️ 断开回调没被触发 ⇒ `CapabilityPresence` 永不失效 ⇒\n" +
                "会出现「状态说 READY、调用却全失败」，而且**没有任何测试会红**\n" +
                "（单测直接调 `notifyOnDisconnected()`，绕过了 Controller 的调用点）。",
        )
    }

    @Test
    fun `controller self heals the presence on every disconnect path`() {
        assertContainsCall(
            "src/main/java/com/chaomixian/vflow/core/xposed/HookChannelController.kt",
            "CapabilityPresenceHolder.resetIfDisconnected()",
            "⚠️ 自愈判定必须挂在 `onCallbackUnregistered` 上，且要在**早退分支之前**\n" +
                "（「旧连接迟到通知」那条 return 不会触发断开回调）。\n" +
                "没有它，那条路径上的残留 READY 就没人清。",
        )
    }

    // ── ③ 反证：扫描本身不是空转 ──────────────────────────────

    @Test
    fun `the scan is not vacuous`() {
        // ⚠️ 防「路径写错 ⇒ 上面全部空转通过」。
        // 用两个必然存在的调用反问
        assertContainsCall(
            "src/main/java/com/chaomixian/vflow/core/xposed/CapabilityRuntime.kt",
            "CapabilityInvoker.attach(",
            "CapabilityRuntime 应当把 context 交给 CapabilityInvoker —— 这条同时验证扫描路径没写错",
        )
        // 且注释剥离不能剥过头：文件里必须还看得到真实代码行
        val lines = codeLines(file("src/main/java/com/chaomixian/vflow/core/xposed/CapabilityRuntime.kt"))
        assertTrue("注释剥离后看不到 fun attach —— 剥离逻辑可能剥过头了", lines.any { it.second.contains("fun attach(") })
    }
}
