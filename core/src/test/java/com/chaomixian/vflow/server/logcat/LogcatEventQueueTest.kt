package com.chaomixian.vflow.server.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatEventQueue] 的回归测试。
 *
 * 这一层保护的是**触发器"偶尔不触发"这个最难查的故障**：
 * 队列满时丢事件是必要的（不丢就会反压到读 logcat，由内核丢得更多且不可控），
 * 但**丢弃必须被计数**，否则用户只会看到"触发器没反应"而无从判断。
 */
class LogcatEventQueueTest {

    // ── 基本进出 ────────────────────────────────────────────────

    @Test
    fun `offers and polls in order`() {
        val q = LogcatEventQueue(10)
        assertTrue(q.offer("a"))
        assertTrue(q.offer("b"))

        assertEquals("a", q.poll(0))
        assertEquals("b", q.poll(0))
    }

    @Test
    fun `poll returns null when empty and not blocked`() {
        val q = LogcatEventQueue(10)
        assertNull(q.poll(0))
    }

    @Test
    fun `accepted count tracks successful offers`() {
        val q = LogcatEventQueue(10)
        repeat(3) { q.offer("x$it") }
        assertEquals(3L, q.acceptedCount())
    }

    @Test
    fun `a polled item leaves the queue`() {
        val q = LogcatEventQueue(10)
        q.offer("a")
        assertEquals(1, q.size())
        q.poll(0)
        assertEquals(0, q.size())
    }

    // ── 溢出与计数 ★ ────────────────────────────────────────────

    @Test
    fun `offering beyond capacity returns false instead of blocking`() {
        // ⚠️ 这是本类存在的根本理由：offer 跑在**泵线程**上，
        // 一旦阻塞就会停止读 logcat stdout → 管道满 → 内核丢日志。
        // 那一刻丢弃就重新变成不可控的了
        val q = LogcatEventQueue(2)
        assertTrue(q.offer("a"))
        assertTrue(q.offer("b"))
        assertFalse("满了应立刻返回 false，不能阻塞", q.offer("c"))
    }

    @Test
    fun `dropped events are counted`() {
        // ⚠️ 计数是"用户能知道丢过东西"的唯一途径
        val q = LogcatEventQueue(1)
        q.offer("keep")
        repeat(5) { q.offer("drop$it") }

        assertEquals(5L, q.peekDropped())
        assertEquals(1L, q.acceptedCount())
    }

    @Test
    fun `drainDropped returns and resets the counter`() {
        // 上报时读到多少就报多少，**不重复报**——
        // 否则用户会看到同一个数字被反复提示
        val q = LogcatEventQueue(1)
        q.offer("keep")
        q.offer("drop1")
        q.offer("drop2")

        assertEquals(2L, q.drainDropped())
        assertEquals("清零点应归零", 0L, q.peekDropped())

        q.offer("drop3")
        assertEquals("新一轮只报新增的", 1L, q.drainDropped())
    }

    @Test
    fun `peekDropped does not reset`() {
        val q = LogcatEventQueue(1)
        q.offer("keep")
        q.offer("drop")

        assertEquals(1L, q.peekDropped())
        assertEquals("查看不该清零", 1L, q.peekDropped())
    }

    // ── 丢弃策略：丢最新而非丢队首 ★ ────────────────────────────

    @Test
    fun `overflow keeps the earliest events and drops the newest`() {
        // ⚠️ 策略选择：丢**最新**的，而不是淘汰队首。
        //
        // 理由：丢掉队首会让保留的事件**顺序错乱**（时间戳跳跃）；
        // 丢最新则保留一段**连续**的早期事件，之后整段缺失。
        // 后者对用户更好理解 —— 日志本来就是时间序的，
        // "某段时间之后就没有触发了"比"记录中间莫名缺几条"好判断得多。
        val q = LogcatEventQueue(3)
        repeat(5) { q.offer("event-$it") }

        assertEquals("最早的三条应被保留", "event-0", q.poll(0))
        assertEquals("event-1", q.poll(0))
        assertEquals("event-2", q.poll(0))
        assertNull("后面两条已丢弃", q.poll(0))
        assertEquals(2L, q.peekDropped())
    }

    // ── 清理 ────────────────────────────────────────────────────

    @Test
    fun `clear empties the queue`() {
        // 流结束时调用，避免旧事件串到下一次
        val q = LogcatEventQueue(10)
        repeat(5) { q.offer("x") }
        q.clear()
        assertEquals(0, q.size())
    }

    @Test
    fun `clear does not reset the dropped counter`() {
        // 丢弃数要留到上报为止 —— 清队列是"不要再发了"，
        // 不是"假装没丢过"
        val q = LogcatEventQueue(1)
        q.offer("keep")
        q.offer("drop")
        q.clear()
        assertEquals(1L, q.peekDropped())
    }

    // ── 边界 ────────────────────────────────────────────────────

    @Test
    fun `a degenerate capacity is clamped to at least one`() {
        // 防御性：容量 0 / 负数不该让队列抛异常。
        // 实现把它夹到 1 —— 比"全部丢弃"更合理：队列至少能工作，
        // 而"容量 0"本身是调用方的配置错误，不是想表达"丢弃一切"
        val zero = LogcatEventQueue(0)
        assertTrue("容量 0 被夹到 1，第一条应能入队", zero.offer("x"))
        assertFalse("第二条才丢弃", zero.offer("y"))
        assertEquals(1L, zero.peekDropped())

        val negative = LogcatEventQueue(-5)
        assertTrue(negative.offer("x"))
        assertFalse(negative.offer("y"))
    }

    @Test
    fun `poll with a timeout returns what is available immediately`() {
        val q = LogcatEventQueue(10)
        q.offer("ready")

        val started = System.currentTimeMillis()
        assertEquals("ready", q.poll(5_000))
        val elapsed = System.currentTimeMillis() - started

        // 有数据时应当立刻返回，而不是等满超时
        assertTrue("不该等满超时，实际等了 ${elapsed}ms", elapsed < 1_000)
    }

    @Test
    fun `default capacity is the documented constant`() {
        assertEquals(1000, LogcatEventQueue.DEFAULT_CAPACITY)
    }
}
