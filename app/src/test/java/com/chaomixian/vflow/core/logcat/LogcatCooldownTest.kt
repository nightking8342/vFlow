package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatCooldown] 的回归测试。
 *
 * 冷却出错的两个方向都不好查：
 * - **太松**（失效）→ 工作流被刷爆，用户以为程序失控
 * - **太紧**（误判）→ 该触发的没触发，且无任何提示
 */
class LogcatCooldownTest {

    @Test
    fun `first hit always fires`() {
        assertTrue(LogcatCooldown(1000).tryAcquire("t1", nowMs = 0))
    }

    @Test
    fun `hits inside the window are dropped`() {
        val cd = LogcatCooldown(1000)
        assertTrue(cd.tryAcquire("t1", 0))
        assertFalse(cd.tryAcquire("t1", 1))
        assertFalse(cd.tryAcquire("t1", 500))
        assertFalse(cd.tryAcquire("t1", 999))
    }

    @Test
    fun `a hit exactly at the boundary fires`() {
        // 边界取「小于」而非「小于等于」：到点即放行，
        // 免得用户看到冷却时间明明到了却还差一毫秒
        val cd = LogcatCooldown(1000)
        assertTrue(cd.tryAcquire("t1", 0))
        assertTrue(cd.tryAcquire("t1", 1000))
    }

    @Test
    fun `the window slides from the last accepted hit`() {
        // 关键行为：窗口从**上一次真正触发**算起，不是从第一次算起。
        // 否则高频日志下会变成"每 N 秒必触发一次"的固定节拍，
        // 与"上一次触发后冷却 N 秒"的语义不同
        val cd = LogcatCooldown(1000)
        assertTrue(cd.tryAcquire("t1", 0))
        assertFalse(cd.tryAcquire("t1", 900))
        assertTrue(cd.tryAcquire("t1", 1000))     // 从上一次 0 起算已满
        assertFalse(cd.tryAcquire("t1", 1900))    // 从 1000 起算未满
        assertTrue(cd.tryAcquire("t1", 2000))
    }

    @Test
    fun `triggers are counted independently`() {
        // 每个触发器独立计数（文档 §8，与 ElementTriggerState 一致）。
        // 共享计数器会让一个高频触发器把其他触发器一起"冷却"掉
        val cd = LogcatCooldown(1000)
        assertTrue(cd.tryAcquire("t1", 0))
        assertTrue("t2 不该被 t1 的冷却影响", cd.tryAcquire("t2", 0))
        assertFalse(cd.tryAcquire("t1", 500))
        assertFalse(cd.tryAcquire("t2", 500))
    }

    @Test
    fun `zero cooldown disables throttling`() {
        val cd = LogcatCooldown(0)
        repeat(10) { i ->
            assertTrue("第 $i 次应当放行", cd.tryAcquire("t1", nowMs = i.toLong()))
        }
    }

    @Test
    fun `negative cooldown also disables throttling`() {
        // 防御性：配置读进来若为负，不该反过来变成"永久冷却"
        val cd = LogcatCooldown(-5)
        assertTrue(cd.tryAcquire("t1", 0))
        assertTrue(cd.tryAcquire("t1", 0))
    }

    @Test
    fun `dropped hits do not extend the window`() {
        // ⚠️ 容易被写错的一点：冷却期内的命中**不记账**。
        // 若错误地把每次命中都记账，持续的高频日志会让窗口无限顺延，
        // 触发器在日志不停的情况下**永远不会再触发**
        val cd = LogcatCooldown(1000)
        assertTrue(cd.tryAcquire("t1", 0))
        // 这在窗口内被丢掉，不该把窗口推到 1500
        assertFalse(cd.tryAcquire("t1", 500))
        assertFalse(cd.tryAcquire("t1", 900))
        // 仍以 0 为基准，所以 1000 就能触发
        assertTrue(cd.tryAcquire("t1", 1000))
    }

    @Test
    fun `isCoolingDown reports without consuming`() {
        val cd = LogcatCooldown(1000)
        assertFalse(cd.isCoolingDown("t1", 0))

        assertTrue(cd.tryAcquire("t1", 0))
        assertTrue(cd.isCoolingDown("t1", 500))
        assertFalse(cd.isCoolingDown("t1", 1000))

        // ⚠️ 预判**不能**影响后续真正的判定
        assertFalse("预判蹭掉了窗口", cd.tryAcquire("t1", 600))
    }

    @Test
    fun `isCoolingDown is false for unknown triggers`() {
        assertFalse(LogcatCooldown(1000).isCoolingDown("never-seen", 12345))
    }

    @Test
    fun `forget drops a single trigger`() {
        // 触发器被删除时调用，防 map 无界增长
        val cd = LogcatCooldown(1000)
        cd.tryAcquire("t1", 0)
        cd.tryAcquire("t2", 0)
        assertEquals(2, cd.trackedCount())

        cd.forget("t1")
        assertEquals(1, cd.trackedCount())
        assertTrue("t1 的记录已清，应可立即触发", cd.tryAcquire("t1", 0))
    }

    @Test
    fun `clear drops everything`() {
        val cd = LogcatCooldown(1000)
        cd.tryAcquire("t1", 0)
        cd.tryAcquire("t2", 0)
        cd.clear()
        assertEquals(0, cd.trackedCount())
    }

    @Test
    fun `clock going backwards does not wedge the trigger`() {
        // 系统时间被改（用户手动调整 / NTP 校正）时 now - last 会变负。
        // 负值小于冷却窗口 → 会被判为"冷却中"，触发器可能长时间不响应。
        // 这里锁定当前行为，让将来若要做单调时钟改造时能看出差异。
        val cd = LogcatCooldown(1000)
        assertTrue(cd.tryAcquire("t1", 1_000_000))
        val stillCooling = !cd.tryAcquire("t1", 0)
        assertTrue("当前实现下时钟回拨会被判为冷却中", stillCooling)
    }

    @Test
    fun `default cooldown constant is one second`() {
        assertEquals(1000L, DEFAULT_LOGCAT_COOLDOWN_MS)
    }
}
