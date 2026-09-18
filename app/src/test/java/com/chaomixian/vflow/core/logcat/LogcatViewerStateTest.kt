package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [buildViewerActions] / [buildViewerResult] / [dataSourceLabel] 的回归测试。
 *
 * 锁的是两处**用户会直接感到卡的判断**：
 * 1. 命令执行期间必须禁用「刷新」（§4.1.1，Shizuku 通道同步阻塞，并发会互相干扰）
 * 2. 「日志被筛没了」与「没日志」不能混为一谈（§4.6）
 */
class LogcatViewerStateTest {

    private fun line(
        tag: String,
        level: LogLevel = LogLevel.INFO,
        pid: Int = 100,
        continuation: Boolean = false,
    ) = LogcatLine(
        tag = tag,
        message = "msg",
        level = level,
        pid = pid,
        tid = 100,
        timestamp = "09-18 10:00:00.000",
        raw = "raw",
        isContinuation = continuation,
    )

    private val filter = LogcatFilter()

    // ── 刷新禁用 ★ ──────────────────────────────────────────────

    @Test
    fun `refresh is allowed when idle with shell ready`() {
        val actions = buildViewerActions(CaptureState.Idle, shellReady = true, refreshing = false)
        assertTrue(actions.canRefresh)
    }

    @Test
    fun `refresh is disabled while a command is in flight`() {
        // ⚠️ Shizuku 的 exec 是同步阻塞的，并发发起会互相干扰。
        // 这里是"禁用"而非"排队"（§4.1.1）。
        val actions = buildViewerActions(CaptureState.Idle, shellReady = true, refreshing = true)
        assertFalse(actions.canRefresh)
    }

    @Test
    fun `refresh is disabled without shell`() {
        val actions = buildViewerActions(CaptureState.Idle, shellReady = false, refreshing = false)
        assertFalse(actions.canRefresh)
    }

    @Test
    fun `refresh is disabled in the stale state`() {
        // STALE 走提示 + 清理，不该去跑命令（§4.2.1）
        val actions = buildViewerActions(CaptureState.Stale(1), shellReady = true, refreshing = false)
        assertFalse(actions.canRefresh)
        assertTrue(actions.canClear)
    }

    // ── 采集按钮 ─────────────────────────────────────────────────

    @Test
    fun `start is disabled while capturing to prevent a second capture process`() {
        // ⚠️ 两个 logcat 写同一个文件会互相覆写。
        // 控制器内部也会探测，但用户在探测返回前就能再点一次——界面这层不能省（§4.2.2 边界 5）。
        val actions = buildViewerActions(CaptureState.Capturing(1), shellReady = true, refreshing = false)
        assertFalse(actions.canStart)
        assertTrue(actions.canStop)
    }

    @Test
    fun `start is enabled when idle`() {
        val actions = buildViewerActions(CaptureState.Idle, shellReady = true, refreshing = false)
        assertTrue(actions.canStart)
        assertFalse(actions.canStop)
    }

    @Test
    fun `stop is blocked by an in-flight refresh`() {
        // ⚠️ 所有 shell 操作都必须串行（§4.1.1：Shizuku 的 exec 同步阻塞，
        // 并发发起会互相干扰）。停止虽然只是一条 kill，也占同一条通道。
        // 若哪天想让停止插队，必须先想清楚它与在飞的刷新如何互斥。
        val actions = buildViewerActions(CaptureState.Capturing(1), shellReady = true, refreshing = true)
        assertFalse(actions.canStop)
    }

    @Test
    fun `stop needs the shell too`() {
        val actions = buildViewerActions(CaptureState.Capturing(1), shellReady = false, refreshing = false)
        assertFalse(actions.canStop)
    }

    @Test
    fun `clear is available only in the stale state`() {
        val idle = buildViewerActions(CaptureState.Idle, shellReady = true, refreshing = false)
        val capturing = buildViewerActions(CaptureState.Capturing(1), shellReady = true, refreshing = false)
        val stale = buildViewerActions(CaptureState.Stale(1), shellReady = true, refreshing = false)

        assertFalse(idle.canClear)
        assertFalse(capturing.canClear)
        assertTrue(stale.canClear)
    }

    @Test
    fun `export requires content`() {
        val actions = buildViewerActions(CaptureState.Idle, shellReady = true, refreshing = false)
        assertFalse(actions.canExport(hasLines = false))
        assertTrue(actions.canExport(hasLines = true))
    }

    // ── 结果组装 ─────────────────────────────────────────────────

    @Test
    fun `result keeps both pre-filter and post-filter counts`() {
        // ⚠️ 只看过滤后为空会得出"没有日志"，而真相可能是"抓到了 3 行但全被筛掉"。
        // 两个计数都要留（§4.6）。
        val raw = listOf(line("A"), line("B"), line("C"))
        val result = buildViewerResult(
            raw = raw,
            filter = filter.copy(tagQuery = "NotFound"),
            state = CaptureState.Idle,
            ownPids = emptySet(),
            elapsedMs = 10,
        )

        assertEquals(0, result.lines.size)
        assertEquals(3, result.rawLineCount)
        assertEquals(LogcatEmptyReason.FilteredOut, result.emptyReason)
    }

    @Test
    fun `result reports the continuation count of the filtered lines`() {
        // 这是本工具最有价值的提示：告诉用户"有 N 行没有 TAG 前缀，
        // 你配的 message 条件可能因此失配"
        val raw = listOf(
            line("A"),
            line("A", continuation = true),
            line("A", continuation = true),
        )
        val result = buildViewerResult(raw, filter, CaptureState.Idle, emptySet(), elapsedMs = 10)
        assertEquals(2, result.continuationCount)
    }

    @Test
    fun `time range is shown only while capturing`() {
        val raw = listOf(
            line("A").copy(timestamp = "09-18 10:00:00.000"),
            line("B").copy(timestamp = "09-18 10:01:00.000"),
        )

        val idle = buildViewerResult(raw, filter, CaptureState.Idle, emptySet(), 1)
        val capturing = buildViewerResult(raw, filter, CaptureState.Capturing(1), emptySet(), 1)

        // 空闲态读的是缓冲区滚动窗口，"覆盖哪段时间"没有意义
        assertEquals(null, idle.timeRange)
        assertEquals("10:00:00–10:01:00", capturing.timeRange)
    }

    @Test
    fun `result carries the elapsed time for the status bar`() {
        val result = buildViewerResult(listOf(line("A")), filter, CaptureState.Idle, emptySet(), elapsedMs = 210)
        assertEquals(210L, result.elapsedMs)
    }

    @Test
    fun `result reports shell unavailability instead of a state reason`() {
        val result = buildViewerResult(
            raw = emptyList(),
            filter = filter,
            state = CaptureState.Idle,
            ownPids = emptySet(),
            elapsedMs = 0,
            shellAvailable = false,
        )
        assertEquals(LogcatEmptyReason.ShellUnavailable, result.emptyReason)
    }

    // ── 数据源文案 ★ ────────────────────────────────────────────

    @Test
    fun `data source label differs between the two sources`() {
        // ⚠️ 两种数据源的语义不同，文案必须写出来（§4.1.2）：
        // 缓冲区是**会变**的滚动窗口，采集文件在区间内**不变**。
        // 不说清楚，用户会以为工具不稳定——"刚才还有的行怎么没了"。
        assertEquals("缓冲区", dataSourceLabel(CaptureState.Idle))
        assertEquals("采集文件", dataSourceLabel(CaptureState.Capturing(1)))
        assertEquals("上次采集文件", dataSourceLabel(CaptureState.Stale(1)))
    }

    @Test
    fun `idle and capturing labels are distinct`() {
        assertTrue(dataSourceLabel(CaptureState.Idle) != dataSourceLabel(CaptureState.Capturing(1)))
    }
}
