package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `every state has a distinct data source label`() {
        // ⚠️ 数据源的语义不同，文案必须写出来（§4.1.2）：
        // 缓冲区是**会变**的滚动窗口，采集文件在区间内**不变**。
        // 不说清楚，用户会以为工具不稳定——"刚才还有的行怎么没了"。
        //
        // 这里只断言**两两不同**而不写死文案：文案会随本地化变，
        // 但"四个状态必须能区分开"这个不变量不会变。
        val labels = listOf(
            CaptureState.Idle,
            CaptureState.Capturing(1),
            CaptureState.Completed,
            CaptureState.Stale(1),
        ).map { dataSourceLabel(it) }

        assertEquals("四个状态的文案应当互不相同", labels.size, labels.toSet().size)
        labels.forEach { assertTrue("文案不该为空", it.isNotBlank()) }
    }

    @Test
    fun `idle and capturing labels are distinct`() {
        assertTrue(dataSourceLabel(CaptureState.Idle) != dataSourceLabel(CaptureState.Capturing(1)))
    }

    @Test
    fun `completed and capturing labels are distinct`() {
        // ⚠️ 两者数据源是同一个文件，但"还在增长"与"已固定"对用户
        // 是完全不同的心智模型——前者可以等更多日志，后者不能
        assertTrue(
            dataSourceLabel(CaptureState.Completed) != dataSourceLabel(CaptureState.Capturing(1))
        )
    }

    // ── 数据源是否已固定 ★ ──────────────────────────────────────

    @Test
    fun `refresh is unnecessary when the source is fixed`() {
        // ⚠️ 已完成态与异常结束态下，采集文件不再变化 —— 刷新按钮没有意义，
        // 界面据此隐藏它（用户报的问题 1/2 的界面侧落点）
        assertTrue(buildViewerActions(CaptureState.Completed, true, false).sourceIsFixed)
        assertTrue(buildViewerActions(CaptureState.Stale(1), true, false).sourceIsFixed)
    }

    @Test
    fun `the source is not fixed while idle or capturing`() {
        // 空闲态读实时缓冲区（会变）；采集态文件还在增长（会变）
        assertFalse(buildViewerActions(CaptureState.Idle, true, false).sourceIsFixed)
        assertFalse(buildViewerActions(CaptureState.Capturing(1), true, false).sourceIsFixed)
    }

    @Test
    fun `a completed state still allows starting a new capture`() {
        // 已完成态下用户要能"重新采集" —— canStart 必须为真，
        // 否则用户卡在这一批里出不去
        val actions = buildViewerActions(CaptureState.Completed, shellReady = true, refreshing = false)
        assertTrue(actions.canStart)
        assertFalse(actions.capturing)
    }

    // ── 时间范围：已完成态也要显示 ──────────────────────────────

    @Test
    fun `time range is shown when the capture is completed`() {
        // 采集完成后，时间范围是**唯一**能说明"这批覆盖哪段时间"的信息，
        // 比采集中时更需要它
        val raw = listOf(
            line("A").copy(timestamp = "09-18 10:00:00.000"),
            line("B").copy(timestamp = "09-18 10:01:00.000"),
        )
        val completed = buildViewerResult(raw, filter, CaptureState.Completed, emptySet(), 1)
        assertEquals("10:00:00–10:01:00", completed.timeRange)
    }

    // ── 消息过滤 ★ ──────────────────────────────────────────────

    @Test
    fun `message filter narrows the result`() {
        val raw = listOf(
            line("A").copy(message = "connection established"),
            line("B").copy(message = "connection failed"),
        )
        val result = buildViewerResult(
            raw = raw,
            filter = filter.copy(messageQuery = "failed"),
            state = CaptureState.Idle,
            ownPids = emptySet(),
            elapsedMs = 1,
        )
        assertEquals(1, result.lines.size)
        assertEquals("B", result.lines.first().tag)
        assertEquals("过滤前的行数要保留", 2, result.rawLineCount)
    }

    @Test
    fun `a message filter alone counts as an active filter`() {
        // ⚠️ 若忘了把 message 算进 filterActive，用户配了消息条件却筛出空时
        // 会看到"没有日志"而不是"被筛没了" —— 误导性完全不同
        val result = buildViewerResult(
            raw = listOf(line("A").copy(message = "something else")),
            filter = filter.copy(messageQuery = "nomatch"),
            state = CaptureState.Idle,
            ownPids = emptySet(),
            elapsedMs = 1,
        )
        assertEquals(LogcatEmptyReason.FilteredOut, result.emptyReason)
    }

    // ── 查找（与筛选的区别）★ ──────────────────────────────────

    @Test
    fun `search does not remove non-matching rows`() {
        // ⚠️ 这是查找与筛选的**本质区别**，也是它存在的理由：
        // 不匹配的行必须保留，否则看不到上下文。
        // 而上下文恰恰是 logcat 调试的核心 —— 一个崩溃堆栈，
        // 只看 NullPointerException 那一行没有意义，要看它前后发生了什么
        val lines = listOf(
            line("A").copy(raw = "start"),
            line("B").copy(raw = "NullPointerException here"),
            line("C").copy(raw = "end"),
        )
        val result = runLogcatSearch(lines, LogcatSearch(query = "NullPointer"))

        assertEquals("命中数", 1, result.matchCount)
        assertEquals("应定位到第 1 行（0 起）", 1, result.currentLineIndex)
        assertEquals("查找**不改动**结果集本身", 3, lines.size)
    }

    @Test
    fun `search is case insensitive by default`() {
        val lines = listOf(line("A").copy(raw = "NullPointerException"))
        assertEquals(1, runLogcatSearch(lines, LogcatSearch(query = "nullpointer")).matchCount)
    }

    @Test
    fun `search can be made case sensitive`() {
        val lines = listOf(line("A").copy(raw = "NullPointerException"))
        assertEquals(
            0,
            runLogcatSearch(lines, LogcatSearch(query = "nullpointer", caseSensitive = true)).matchCount
        )
        assertEquals(
            1,
            runLogcatSearch(lines, LogcatSearch(query = "NullPointer", caseSensitive = true)).matchCount
        )
    }

    @Test
    fun `an empty query disables search`() {
        val lines = listOf(line("A"))
        val r = runLogcatSearch(lines, LogcatSearch())
        assertEquals(0, r.matchCount)
        assertFalse(r.hasMatches)
        assertNull(r.currentLineIndex)
        assertTrue("未启用时不该有位置标签", r.positionLabel().isEmpty())
    }

    @Test
    fun `all matches are collected not just the first`() {
        val lines = listOf(
            line("A").copy(raw = "err"),
            line("B").copy(raw = "ok"),
            line("C").copy(raw = "err again"),
        )
        val r = runLogcatSearch(lines, LogcatSearch(query = "err"))
        assertEquals(listOf(0, 2), r.matchIndices)
        assertEquals("1/2", r.positionLabel())
    }

    @Test
    fun `no match yields an empty result`() {
        val r = runLogcatSearch(listOf(line("A").copy(raw = "x")), LogcatSearch(query = "zzz"))
        assertFalse(r.hasMatches)
        assertEquals(-1, r.currentIndex)
        assertNull(r.currentLineIndex)
    }

    // ── 跳转 ────────────────────────────────────────────────────

    @Test
    fun `advance moves to the next match`() {
        val lines = List(5) { line("T$it").copy(raw = "err$it") }
        val r = runLogcatSearch(lines, LogcatSearch(query = "err"))
        assertEquals(0, r.currentIndex)
        assertEquals(1, r.advance(1).currentIndex)
        assertEquals(2, r.advance(1).advance(1).currentIndex)
    }

    @Test
    fun `advance wraps around at both ends`() {
        // ⚠️ 循环跳转：到底了再点回到第一个。
        // 比"到底就停住"更好用 —— 用户不需要知道自己在第几个，一直点总能找到
        val lines = List(3) { line("T$it").copy(raw = "err") }
        val r = runLogcatSearch(lines, LogcatSearch(query = "err"))

        assertEquals("末尾再下一个应回到第一个", 0, r.copy(currentIndex = 2).advance(1).currentIndex)
        assertEquals("第一个往上应到末尾", 2, r.copy(currentIndex = 0).advance(-1).currentIndex)
    }

    @Test
    fun `advance on an empty result is a no-op`() {
        val r = runLogcatSearch(listOf(line("A").copy(raw = "x")), LogcatSearch(query = "zzz"))
        assertEquals(r, r.advance(1))
        assertEquals(r, r.advance(-1))
    }

    @Test
    fun `a stale index is clamped when the result set shrinks`() {
        // 换关键字后命中数变少，旧的 currentIndex 可能越界。
        // 不夹取的话 currentLineIndex 会返回 null，界面表现为"跳转失效"
        val lines = List(10) { line("T$it").copy(raw = "err") }
        val r = runLogcatSearch(lines, LogcatSearch(query = "err"), currentIndex = 9)

        assertEquals("越界应被夹到最后一个", 9, r.currentIndex)

        val fewer = List(2) { line("T$it").copy(raw = "err") }
        val r2 = runLogcatSearch(fewer, LogcatSearch(query = "err"), currentIndex = 9)
        assertEquals("命中只剩 2 个时应夹到 1", 1, r2.currentIndex)
    }

    @Test
    fun `position label reports one-based position`() {
        // 用户看到的是 `1/17` 而不是 `0/17`
        val lines = List(17) { line("T$it").copy(raw = "err") }
        assertEquals("1/17", runLogcatSearch(lines, LogcatSearch(query = "err")).positionLabel())
    }

    @Test
    fun `search matches on the raw line so continuations are searchable`() {
        // 用 raw 而非 message：降级行的 raw 是整行原文，
        // 用户想找的往往是"这一行在不在"而不是"这条消息的正文是什么"
        val lines = listOf(line("A", continuation = true).copy(raw = "   at Foo.bar(Foo.kt:42)"))
        assertEquals(1, runLogcatSearch(lines, LogcatSearch(query = "Foo.kt")).matchCount)
    }
}
