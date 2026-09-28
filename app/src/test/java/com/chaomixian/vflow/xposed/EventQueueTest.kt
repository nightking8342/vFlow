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

    // ── ⭐ 上报后「精确扣除」（修缺陷 14）──────────────────────────

    /**
     * ⚠️⚠️ 这一组锁的是**最容易写错的那一处**：用 `drainDropped()` 收尾
     * 会把「装信封与清零之间新发生的丢弃」**无声抹掉**，
     * 而那正是用户唯一的知情途径（本类存在的意义）。
     */
    @Test
    fun `drainDroppedAtMost keeps drops that happened after the reported value`() {
        val q = EventQueue(capacity = 1)
        q.offer("a")
        q.offer("b")                       // 满 ⇒ 丢 1
        val reported = q.peekDropped()      // 装进信封的值 = 1
        assertEquals(1L, reported)

        q.offer("c")                        // ⚠️ 上报与清零之间又丢 1 ⇒ 值变成 2

        assertEquals("只该扣掉已上报的那 1 条", 1L, q.drainDroppedAtMost(reported))
        assertEquals("未上报的那条必须留着", 1L, q.peekDropped())
    }

    @Test
    fun `drainDroppedAtMost with zero reported changes nothing`() {
        val q = EventQueue(capacity = 1)
        q.offer("a")
        q.offer("b")                       // 丢 1
        assertEquals("报 0 就不该扣", 1L, q.drainDroppedAtMost(0))
        assertEquals(1L, q.peekDropped())
    }

    @Test
    fun `drainDroppedAtMost never goes negative`() {
        // 防御性：reported 是历史值，正常路径不会超过当前计数，
        // 但计数器一旦变负，UI 会显示「已丢弃 -1 条」这种荒唐值
        val q = EventQueue(capacity = 1)
        q.offer("a")
        q.offer("b")                       // 丢 1
        assertEquals(0L, q.drainDroppedAtMost(5))
        assertEquals(0L, q.peekDropped())
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

    // ── 集成点：清零必须有调用方 ────────────────────────────────

    /**
     * ⚠️⚠️ **这条测试的存在理由是一个真实缺陷（§8 缺陷 14）。**
     *
     * `drainDropped()` 是**唯一**的清零入口，而它从机制引入起就
     * **零生产调用者**（只有本测试文件调）。后果：
     *
     * ```
     * 信封里的 droppedCount 恒为【累计值】
     *     ⇒ App 侧 lastReportedDroppedCount 恒 > 0
     *     ⇒ 首页「已丢弃 N 条」横幅【永久驻留】，且此后每条事件都刷一条 warning
     * ```
     *
     * 而 `HookRuntime.kt:250` 的注释**逐字**写着「清零由发送成功后的
     * `drainDropped` 负责」—— 注释描述的设计没错，**只是调用点没接上**。
     *
     * 上面那些测试**测不出它**（它们直接喂纯函数，把语义锁得很透，
     * 却不覆盖「谁去调它」）。这正是「测试要覆盖调用链」那条教训的又一实例 ——
     * 与 `CoreDexFingerprintTest` 里那两例同源。
     */
    @Test
    fun `the drop counter is actually cleared by the send path`() {
        val runtime = java.io.File("src/main/java/com/chaomixian/vflow/xposed/HookRuntime.kt")
        if (!runtime.isFile) return // 不在 app 模块根目录时跳过

        val source = runtime.readText()
        assertTrue(
            "HookRuntime 必须在发送成功路径上扣减已上报的丢弃计数 —— " +
                "缺了它 dropped 只增不减，首页横幅永久驻留（缺陷 14）",
            source.contains("drainDroppedAtMost(") || source.contains("drainDropped("),
        )
    }

    /**
     * ⚠️ 扣减必须**只在发送成功之后**做。
     *
     * 若在 `envelope` 装好、还没发出去时就扣，那么「入了队但没发出去」
     * 的那次丢弃会**丢账** —— 而那个信息是用户唯一的知情途径。
     * 与 `CoreDexFingerprintTest` 的「必须在 deployDex 之后」是同一形态。
     */
    @Test
    fun `the counter is decremented only after a successful send`() {
        val runtime = java.io.File("src/main/java/com/chaomixian/vflow/xposed/HookRuntime.kt")
        if (!runtime.isFile) return

        val source = runtime.readText()
        val decrement = source.indexOf("drainDroppedAtMost(")
        val sendCall = source.indexOf("transport.send(")

        assertTrue("扣减调用点应存在", decrement >= 0)
        assertTrue("transport.send 调用点应存在", sendCall >= 0)
        assertTrue(
            "扣减必须在 transport.send 之后（同一循环体内）—— 否则未发出去的丢弃会丢账",
            decrement > sendCall,
        )
    }
}
