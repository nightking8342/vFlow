package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatCaptureUi] 与 [CaptureSession] 的回归测试。
 *
 * 这里锁的都是**改错了不报错、只让功能静默变差**的地方：
 * 岛上的计时显示不对、自动停止不触发、计时起点被重置。
 */
class LogcatCaptureUiTest {

    // ── 岛参数 ───────────────────────────────────────────────────

    @Test
    fun `island timeout has a margin over the capture limit`() {
        // ⚠️ 这是 App 被杀时唯一的自愈手段：采集由 shell 侧 timeout 结束，
        // 但没人再去取消岛通知。岛必须自己到点消失。
        val limit = 300
        val island = LogcatCaptureUi.islandTimeoutSec(limit)

        assertTrue("岛必须比采集上限活得久", island > limit)
        assertEquals(limit + LogcatCaptureUi.ISLAND_TIMEOUT_MARGIN_SEC, island)
    }

    @Test
    fun `island timeout stays positive for degenerate limits`() {
        // timeout 0 在 toybox 里表示"不超时"，但岛不能因此永不消失
        assertTrue(LogcatCaptureUi.islandTimeoutSec(0) > 0)
        assertTrue(LogcatCaptureUi.islandTimeoutSec(-5) > 0)
    }

    @Test
    fun `island icon is the system built-in stopwatch`() {
        // 内置 Lottie key，无需应用自备资源；改成别的 key 岛上会没有图标
        assertEquals("stopwatch_big", LogcatCaptureUi.ISLAND_ICON_KEY)
    }

    @Test
    fun `island cache key is stable`() {
        // 不稳定就会每次 show 都新建一条通知，堆满通知栏
        assertEquals("logcat_capture", LogcatCaptureUi.ISLAND_CACHE_KEY)
    }

    // ── 计时文案 ─────────────────────────────────────────────────

    @Test
    fun `formats elapsed under an hour as mm ss`() {
        assertEquals("00:00", LogcatCaptureUi.formatElapsed(0))
        assertEquals("00:01", LogcatCaptureUi.formatElapsed(1_000))
        assertEquals("02:31", LogcatCaptureUi.formatElapsed(151_000))
        assertEquals("59:59", LogcatCaptureUi.formatElapsed(3_599_000))
    }

    @Test
    fun `formats elapsed past an hour with an hour field`() {
        assertEquals("1:00:00", LogcatCaptureUi.formatElapsed(3_600_000))
        assertEquals("2:05:03", LogcatCaptureUi.formatElapsed(7_503_000))
    }

    @Test
    fun `never shows a negative timer`() {
        // 时钟回拨会让 elapsed 变负。显示负号比显示 00:00 更糟。
        assertEquals("00:00", LogcatCaptureUi.formatElapsed(-1))
        assertEquals("00:00", LogcatCaptureUi.formatElapsed(-100_000))
    }

    @Test
    fun `truncates sub-second remainder instead of rounding up`() {
        // 999ms 应显示 00:00（还没走满一秒），不能四舍五入成 00:01
        assertEquals("00:00", LogcatCaptureUi.formatElapsed(999))
        assertEquals("00:01", LogcatCaptureUi.formatElapsed(1_999))
    }

    @Test
    fun `formats duration in seconds below one minute`() {
        // 不能显示 "0分钟"——读起来像没设上限
        assertEquals("1秒", LogcatCaptureUi.formatDuration(1))
        assertEquals("59秒", LogcatCaptureUi.formatDuration(59))
    }

    @Test
    fun `formats whole minutes without a seconds part`() {
        assertEquals("1分钟", LogcatCaptureUi.formatDuration(60))
        assertEquals("5分钟", LogcatCaptureUi.formatDuration(300))
        assertEquals("30分钟", LogcatCaptureUi.formatDuration(1800))
    }

    @Test
    fun `formats mixed durations with both parts`() {
        assertEquals("1分30秒", LogcatCaptureUi.formatDuration(90))
        assertEquals("2分30秒", LogcatCaptureUi.formatDuration(150))
    }

    // ── 会话状态 ─────────────────────────────────────────────────

    @Test
    fun `capturing session reports capturing`() {
        val session = CaptureSession(CaptureState.Capturing(42), startedAtMs = 1_000)
        assertTrue(session.isCapturing)
    }

    @Test
    fun `idle and stale sessions do not report capturing`() {
        assertFalse(CaptureSession.Idle.isCapturing)
        assertFalse(CaptureSession(CaptureState.Stale(42), 1_000).isCapturing)
    }

    @Test
    fun `elapsed is computed from the start instant`() {
        val session = CaptureSession(CaptureState.Capturing(1), startedAtMs = 10_000)
        assertEquals(5_000L, session.elapsedMs(15_000))
    }

    @Test
    fun `elapsed is null when the start instant is unknown`() {
        // App 重启后 pidfile 里没有开始时间 → 不显示计时，而不是显示一个错的时间
        val session = CaptureSession(CaptureState.Capturing(1), startedAtMs = null)
        assertNull(session.elapsedMs(15_000))
    }

    @Test
    fun `elapsed is null rather than negative after a clock rollback`() {
        val session = CaptureSession(CaptureState.Capturing(1), startedAtMs = 10_000)
        assertNull(session.elapsedMs(9_000))
    }

    @Test
    fun `reaching the limit is detected at or past the threshold`() {
        val session = CaptureSession(CaptureState.Capturing(1), startedAtMs = 0)

        assertFalse("差 1ms 不该停", session.hasReachedLimit(299_999, timeoutSec = 300))
        assertTrue("正好到点该停", session.hasReachedLimit(300_000, timeoutSec = 300))
        assertTrue("超过更该停", session.hasReachedLimit(400_000, timeoutSec = 300))
    }

    @Test
    fun `an untimed session never reaches the limit`() {
        // 无法计时时不能误判为超时——那会在用户刚开采集时立刻停掉它
        val session = CaptureSession(CaptureState.Capturing(1), startedAtMs = null)
        assertFalse(session.hasReachedLimit(Long.MAX_VALUE, timeoutSec = 1))
    }

    @Test
    fun `a zero limit still stops eventually instead of running forever`() {
        // timeout 0 对用户意味着"尽快停"，不能变成"永不超时"
        val session = CaptureSession(CaptureState.Capturing(1), startedAtMs = 0)
        assertTrue(session.hasReachedLimit(1_000, timeoutSec = 0))
    }
}
