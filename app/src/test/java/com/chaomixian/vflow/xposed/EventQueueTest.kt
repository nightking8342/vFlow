package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.EventQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * hook 层有界事件队列的测试。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.4。
 *
 * ⚠️ 队列策略写错的表现是**「平时正常，高峰时静默丢事件」** ——
 * 用户只知道「有时候没触发」，没有任何线索指向「丢了」。
 * 所以这一层必须有测试。
 *
 * 语义照 `LogcatEventQueue`（`core/.../server/logcat/`），
 * 尤其是 `clear()` 不清丢弃计数那条。
 */
class EventQueueTest {

    @Test
    fun `offer accepts up to capacity then rejects`() {
        val q = EventQueue(capacity = 3)
        assertTrue(q.offer("a"))
        assertTrue(q.offer("b"))
        assertTrue(q.offer("c"))
        // 第 4 条超出容量 —— 必须**拒绝而非阻塞**
        assertFalse(q.offer("d"))
        assertEquals(3, q.size())
    }

    @Test
    fun `rejected offers are counted as dropped`() {
        val q = EventQueue(capacity = 1)
        q.offer("a")
        q.offer("b")   // 满，丢
        q.offer("c")   // 满，丢
        assertEquals(2L, q.peekDropped())
        assertEquals(1L, q.acceptedCount())
    }

    @Test
    fun `drainDropped returns and clears the counter`() {
        // 语义是「读到多少就报多少，不重复报」 —— 上报一次后必须清零
        val q = EventQueue(capacity = 1)
        q.offer("a")
        q.offer("b")
        assertEquals(1L, q.drainDropped())
        assertEquals(0L, q.drainDropped())
        assertEquals(0L, q.peekDropped())
    }

    @Test
    fun `clear drops queued events but does NOT reset the dropped counter`() {
        // ⚠️⚠️ 这是本类最要紧的一条语义，也是**最容易被人「顺手清理」掉**的。
        //
        // 丢弃数是用来告诉用户「丢过 N 条」的，必须留到 drainDropped 上报为止。
        // 在 clear 里清零会让那次上报永远发不出去 —— 用户就再也知道不了丢过事件。
        //
        // 本仓库在 LogcatEventQueue 上有同一语义的测试；
        // 我曾试图让它的 clear 连计数一起清 —— 那是错的，已回退。
        val q = EventQueue(capacity = 2)
        q.offer("a")
        q.offer("b")
        q.offer("c")          // 丢 1

        assertEquals(1L, q.peekDropped())
        val cleared = q.clear()

        assertEquals(2, cleared)           // 积压确实清了
        assertEquals(0, q.size())
        assertEquals(1L, q.peekDropped())  // ★ 但丢弃数**必须还在**
        assertEquals(1L, q.drainDropped()) // ★ 那次上报仍能发出去
    }

    @Test
    fun `resetAll clears counters and queue`() {
        // 与 clear 的区别：这个是「确认上一代的丢弃数已经上报完毕」后才用的
        val q = EventQueue(capacity = 1)
        q.offer("a")
        q.offer("b")
        q.resetAll()
        assertEquals(0L, q.peekDropped())
        assertEquals(0L, q.acceptedCount())
        assertEquals(0, q.size())
    }

    @Test
    fun `poll returns null on timeout instead of blocking forever`() {
        // 发送线程靠这个超时来检查 running 标志并退出 ——
        // 永久阻塞会让线程无法退出
        val q = EventQueue(capacity = 2)
        val start = System.currentTimeMillis()
        assertNull(q.poll(50))
        val elapsed = System.currentTimeMillis() - start
        assertTrue("poll 应约 50ms 返回，实际 ${elapsed}ms", elapsed >= 40)
    }

    @Test
    fun `poll returns items in FIFO order`() {
        // 事件是时间序的，顺序错乱会让下游的时间判断失效
        val q = EventQueue(capacity = 3)
        q.offer("first")
        q.offer("second")
        assertEquals("first", q.poll(100))
        assertEquals("second", q.poll(100))
    }

    @Test
    fun `capacity is coerced to at least one`() {
        // 容量传 0 会让 ArrayBlockingQueue 抛异常 ——
        // 但那是**构造期**崩溃，发生在 system_server 里，必须防住
        val q = EventQueue(capacity = 0)
        assertTrue(q.offer("a"))
        assertFalse(q.offer("b"))
        assertEquals(1, q.size())
    }
}
