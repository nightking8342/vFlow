package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatFilter] / [applyLogcatFilter] / [diagnoseEmpty] 的回归测试。
 *
 * 重点锁的是**降级行的连带行为**（继承语义带来的结构性好处，一旦被破坏会静默产出
 * 孤儿续行）以及**「没日志」与「日志被筛没了」必须可区分**。
 */
class LogcatFilterTest {

    private fun line(
        tag: String,
        level: LogLevel = LogLevel.INFO,
        pid: Int = 100,
        message: String = "msg",
        timestamp: String = "09-18 10:00:00.000",
        continuation: Boolean = false,
    ) = LogcatLine(
        tag = tag,
        message = message,
        level = level,
        pid = pid,
        tid = 100,
        timestamp = timestamp,
        raw = "raw-$message",
        isContinuation = continuation,
    )

    private val defaultFilter = LogcatFilter()

    // ── 级别 ─────────────────────────────────────────────────────

    @Test
    fun `keeps lines at or above the minimum level`() {
        val lines = listOf(
            line("A", LogLevel.VERBOSE),
            line("B", LogLevel.DEBUG),
            line("C", LogLevel.INFO),
            line("D", LogLevel.ERROR),
        )
        val result = applyLogcatFilter(lines, defaultFilter.copy(minLevel = LogLevel.INFO))
        assertEquals(listOf("C", "D"), result.map { it.tag })
    }

    @Test
    fun `verbose keeps everything`() {
        val lines = listOf(line("A", LogLevel.VERBOSE), line("B", LogLevel.FATAL))
        assertEquals(2, applyLogcatFilter(lines, defaultFilter.copy(minLevel = LogLevel.VERBOSE)).size)
    }

    // ── TAG ──────────────────────────────────────────────────────

    @Test
    fun `matches tag as a case-insensitive substring`() {
        // 子串而非全等：用户常只记得 TAG 的一部分
        val lines = listOf(line("MyAppActivity"), line("OtherThing"))
        val result = applyLogcatFilter(lines, defaultFilter.copy(tagQuery = "myapp"))
        assertEquals(listOf("MyAppActivity"), result.map { it.tag })
    }

    @Test
    fun `blank tag query disables tag filtering`() {
        val lines = listOf(line("A"), line("B"))
        assertEquals(2, applyLogcatFilter(lines, defaultFilter.copy(tagQuery = "   ")).size)
        assertTrue(!defaultFilter.copy(tagQuery = "  ").hasTagQuery)
    }

    // ── 本应用日志 ────────────────────────────────────────────────

    @Test
    fun `own app logs are shown by default`() {
        val lines = listOf(line("vFlow", pid = 777))
        val result = applyLogcatFilter(lines, defaultFilter, ownPids = setOf(777))
        assertEquals(1, result.size)
    }

    @Test
    fun `hiding own app logs drops only our pids`() {
        // ⚠️ 用 pid 而非 TAG 判定：TAG 是应用自己起的，不可靠
        val lines = listOf(line("vFlow", pid = 777), line("System", pid = 1000))
        val result = applyLogcatFilter(
            lines,
            defaultFilter.copy(showOwnApp = false),
            ownPids = setOf(777),
        )
        assertEquals(listOf("System"), result.map { it.tag })
    }

    @Test
    fun `hiding own app logs without known pids drops nothing`() {
        // 拿不到自己的 pid 时不能把所有日志都当成自己的
        val lines = listOf(line("A"), line("B"))
        assertEquals(2, applyLogcatFilter(lines, defaultFilter.copy(showOwnApp = false)).size)
    }

    // ── 降级行的连带行为 ★ ──────────────────────────────────────

    @Test
    fun `continuations follow their parent through the tag filter`() {
        // ⚠️ 这是继承语义带来的结构性好处：降级行继承了母行的 tag，
        // 因此母行被筛掉时续行也一起走，不会留下孤儿续行。
        // 若哪天把降级行的 tag 置空，这个不变量立刻碎掉。
        val lines = listOf(
            line("MyApp"),
            line("MyApp", continuation = true),
            line("Other"),
        )
        val result = applyLogcatFilter(lines, defaultFilter.copy(tagQuery = "MyApp"))
        assertEquals(2, result.size)
        assertTrue(result.all { it.tag == "MyApp" })
    }

    @Test
    fun `continuations follow their parent through the level filter`() {
        // 反过来说：降级行若被置为 V，会被 minLevel 静默丢掉，
        // 表现是"母行还在、续行没了"——用户看不见的那一半日志
        val lines = listOf(
            line("MyApp", LogLevel.ERROR),
            line("MyApp", LogLevel.ERROR, continuation = true),
        )
        val result = applyLogcatFilter(lines, defaultFilter.copy(minLevel = LogLevel.INFO))
        assertEquals(2, result.size)
    }

    @Test
    fun `continuations follow their parent through the own-app filter`() {
        val lines = listOf(
            line("vFlow", pid = 777),
            line("vFlow", pid = 777, continuation = true),
        )
        val result = applyLogcatFilter(
            lines,
            defaultFilter.copy(showOwnApp = false),
            ownPids = setOf(777),
        )
        assertTrue(result.isEmpty())
    }

    // ── 空结果归因 ★ ────────────────────────────────────────────

    @Test
    fun `non-empty result has no empty reason`() {
        assertNull(
            diagnoseEmpty(
                state = CaptureState.Idle,
                filtered = listOf(line("A")),
                rawCount = 1,
                filterActive = false,
            )
        )
    }

    @Test
    fun `idle empty is attributed to buffer rotation`() {
        // ★ 这是 §2.2 那个静默失败的对策：不能只说"没有日志"
        assertEquals(
            LogcatEmptyReason.BufferRotated,
            diagnoseEmpty(CaptureState.Idle, emptyList(), 0, filterActive = false),
        )
    }

    @Test
    fun `fresh capture empty is not treated as an error`() {
        // 刚开始采，还没有日志产生——完全正常，别报错
        assertEquals(
            LogcatEmptyReason.CaptureJustStarted,
            diagnoseEmpty(CaptureState.Capturing(1), emptyList(), 0, filterActive = false),
        )
    }

    @Test
    fun `stale empty is attributed to the failed capture`() {
        assertEquals(
            LogcatEmptyReason.CaptureStale,
            diagnoseEmpty(CaptureState.Stale(1), emptyList(), 0, filterActive = false),
        )
    }

    @Test
    fun `filtered-out is distinguished from no-logs-at-all`() {
        // ⚠️ 核心区分：「抓到了但被筛没了」与「压根没有日志」，
        // 用户的下一步动作完全不同（放宽条件 vs 换数据源/开采集）。
        // 混为一谈会让人白白去查权限问题。
        assertEquals(
            LogcatEmptyReason.FilteredOut,
            diagnoseEmpty(CaptureState.Idle, emptyList(), rawCount = 800, filterActive = true),
        )
    }

    @Test
    fun `shell unavailability outranks the state-based reason`() {
        // 命令压根没跑，就不该说"缓冲区已轮转"
        assertEquals(
            LogcatEmptyReason.ShellUnavailable,
            diagnoseEmpty(
                CaptureState.Idle, emptyList(), rawCount = 0,
                filterActive = false, shellAvailable = false,
            ),
        )
    }

    @Test
    fun `timeout outranks shell unavailability`() {
        assertEquals(
            LogcatEmptyReason.CommandTimeout,
            diagnoseEmpty(
                CaptureState.Idle, emptyList(), rawCount = 0,
                filterActive = false, shellAvailable = false, timedOut = true,
            ),
        )
    }

    @Test
    fun `shell problem is reported even when lines were filtered away`() {
        // 判断顺序：超时 / 无权限 是"命令没成功"，优先于"被筛没了"
        assertEquals(
            LogcatEmptyReason.ShellUnavailable,
            diagnoseEmpty(
                CaptureState.Idle, emptyList(), rawCount = 800,
                filterActive = true, shellAvailable = false,
            ),
        )
    }

    // ── 时间范围 ─────────────────────────────────────────────────

    @Test
    fun `capture time range shows first and last timestamps`() {
        val lines = listOf(
            line("A", timestamp = "09-18 10:00:12.100"),
            line("B", timestamp = "09-18 10:02:43.900"),
        )
        assertEquals("10:00:12–10:02:43", captureTimeRange(lines))
    }

    @Test
    fun `capture time range collapses to a single instant for one line`() {
        // 只抓到一行时首末相同，显示成「10:00:12–10:00:12」没有意义，collapse 成一个时刻
        val lines = listOf(line("A", timestamp = "09-18 10:00:12.100"))
        assertEquals("10:00:12", captureTimeRange(lines))
    }

    @Test
    fun `capture time range ignores continuation lines`() {
        // 降级行继承母行的时间戳，算进去不会改变结果，但把它们排除更贴合"覆盖区间"的语义
        val lines = listOf(
            line("A", timestamp = "09-18 10:00:12.100"),
            line("A", timestamp = "09-18 10:00:12.100", continuation = true),
            line("B", timestamp = "09-18 10:01:00.000"),
        )
        assertEquals("10:00:12–10:01:00", captureTimeRange(lines))
    }

    @Test
    fun `capture time range is null when no timestamp is parseable`() {
        val lines = listOf(line("A", timestamp = ""), line("B", timestamp = ""))
        assertNull(captureTimeRange(lines))
    }

    @Test
    fun `capture time range tolerates a missing millisecond part`() {
        val lines = listOf(
            line("A", timestamp = "09-18 10:00:12"),
            line("B", timestamp = "09-18 10:01:00"),
        )
        assertEquals("10:00:12–10:01:00", captureTimeRange(lines))
    }
}
