package com.chaomixian.vflow.services

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住 `TriggerService` 真的接上了「通道未就绪」提示链路。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.1。
 *
 * ## ⚠️ 为什么必须是源码扫描，而不是换个方式测
 *
 * 本仓库在 `CoreLauncher` 上踩过同类的坑：13 个纯函数单测**全绿**，
 * 但**调用点缺失**（`recordLaunchedDexFingerprint` 从未被调用）——
 * 纯函数测试再全也测不出「调用方缺失」。`TriggerService` 是 Android `Service`，
 * 纯 JVM 单测起不来它，所以「接线是否存在」只能在**源码层**锁。
 *
 * ⚠️ 扫描用**相对路径**（Gradle 的 test 工作目录是 `app/`），
 * 与 `CoreDexFingerprintTest` 的既有形态一致。
 */
class TriggerServiceXposedNoticeWiringTest {

    private companion object {
        const val SERVICE_PATH = "src/main/java/com/chaomixian/vflow/services/TriggerService.kt"

        /** `refreshXposedChannelNotice` 的函数签名片段（定位用）。 */
        const val REFRESH_SIGNATURE = "private fun refreshXposedChannelNotice()"
    }

    private fun source(): String {
        val file = File(SERVICE_PATH)
        assertTrue("找不到 $SERVICE_PATH（当前目录 ${File(".").absolutePath}）", file.exists())
        return file.readText()
    }

    // ── 1. 订阅两条流（缺一条会漏掉一半场景）────────────────────

    @Test
    fun `trigger service subscribes to both state flows`() {
        val text = source()
        // ⚠️⚠️ 必须断言到 **collect 的函数体里真的调了 refresh** ——
        // 只断言「collect 字样存在」是不够的：把函数体掏空（`collect { }`）时
        // 那种写法照样绿（本测试的第一版就是这样，反证时**没变红**）。
        // 这正是本仓库的老教训：**测试要经过调用点，否则反证不会变红**。
        assertTrue(
            "必须订阅 XposedFrameworkMonitor.state 并在其中刷新 —— 框架 bind/died 会改变判定",
            text.contains("XposedFrameworkMonitor.state.collect { refreshXposedChannelNotice() }"),
        )
        assertTrue(
            "必须订阅 HookChannelController.connected 并在其中刷新 —— 通道连上/断开同样改变判定",
            text.contains("HookChannelController.connected.collect { refreshXposedChannelNotice() }"),
        )
    }

    // ── 2. 判据真的被用上 ─────────────────────────────────────

    @Test
    fun `trigger service consults XposedReadiness`() {
        val text = source()
        assertTrue(
            "必须走 XposedReadiness.needsChannelNotice —— 它是「要不要提示」的唯一判据",
            text.contains("XposedReadiness.needsChannelNotice("),
        )
        assertTrue(
            "必须走 XposedReadiness.selectAffectedWorkflows —— 否则会把没配 Xposed 的用户也拖进来",
            text.contains("XposedReadiness.selectAffectedWorkflows("),
        )
    }

    @Test
    fun `trigger service uses the diagnostics layer for the notice`() {
        assertTrue(
            "通知文案必须来自 XposedDiagnostics（复用 tapAction 的分流），不许在这里另写一套分支",
            source().contains("XposedDiagnostics.channelNoticeRes("),
        )
    }

    // ── 3. ⭐ 需求硬约束 2 的机器化锁 ───────────────────────────

    @Test
    fun `the notice path never writes workflow state`() {
        val body = functionBody(source(), REFRESH_SIGNATURE)
        assertTrue("截取到的函数体为空 —— 扫描器可能失效了", body.isNotBlank())

        val forbidden = listOf("saveWorkflow", "workflowManager.save", "isEnabled = false")
        val hits = forbidden.filter { body.contains(it) }
        assertTrue(
            "❌ `$REFRESH_SIGNATURE` 里出现了落盘改动：$hits\n" +
                "需求硬约束：**不要用「禁用工作流」把用户引过来** ——\n" +
                "通道断多半是几秒内会自己好的**时序**问题（重装 APK / 强杀 App），\n" +
                "让一个短暂窗口去改用户的落盘数据，代价与收益不成比例。\n" +
                "提示只允许「打日志 + 改通知正文」。",
            hits.isEmpty(),
        )
    }

    // ── 4. 通知正文是条件式 ───────────────────────────────────

    @Test
    fun `the notification text is conditional`() {
        assertTrue(
            "createNotification 必须按 xposedNoticeRes 分流（null 时回落默认文案）",
            source().contains("xposedNoticeRes ?: R.string.trigger_service_notification_text"),
        )
    }

    @Test
    fun `the scan is not vacuous`() {
        val text = source()
        assertTrue("TriggerService.kt 为空？", text.isNotBlank())
        assertTrue(
            "函数体截取失效（括号配对扫不到东西）",
            functionBody(text, REFRESH_SIGNATURE).length > 50,
        )
    }

    // ── 内部工具 ──────────────────────────────────────────────

    /**
     * 从 `signatureFragment` 起按**大括号配对**截出函数体。
     *
     * ⚠️ 扫描时必须跳过字符串字面量 —— 函数体里有
     * `"…${affected.joinToString { it.name }}…"`，不跳过的话插值里的 `{}` 会
     * 让配对计数错位（虽然本例里它们恰好平衡，但那属于**碰巧**，不该依赖）。
     */
    private fun functionBody(source: String, signatureFragment: String): String {
        val start = source.indexOf(signatureFragment)
        require(start >= 0) { "找不到函数签名：$signatureFragment" }

        val open = source.indexOf('{', start)
        require(open >= 0) { "函数签名后没有 `{`：$signatureFragment" }

        var depth = 0
        var i = open
        var inString = false
        while (i < source.length) {
            val c = source[i]
            when {
                inString -> {
                    if (c == '\\') {
                        i += 2
                        continue
                    }
                    if (c == '"') inString = false
                }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open + 1, i)
                }
            }
            i++
        }
        error("大括号不配对：$signatureFragment")
    }
}
