package com.chaomixian.vflow.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 重连退避策略的测试。
 *
 * ## 这个测试的存在理由（一个实测暴露的真实缺陷）
 *
 * 2026-09-27 的开机日志暴露：`onServiceDisconnected` **原先只清 `host`、不重连**
 * （`BinderTransport.kt`），于是断开之后**永久失联** ——
 * Activity 触发器再也不工作，只能靠重启 App 恢复。
 *
 * 而触发断开最常见的场景恰恰是**我们自己的部署流程**（重装 APK ⇒ App 被杀）
 * 与**热更新换代**。用户看到的是「装了新版本之后触发器就不灵了」。
 *
 * ⚠️ 退避参数算错的表现是**静默的**：算小了刷屏、算大了用户以为功能坏了。
 */
class ReconnectPolicyTest {

    // ── 退避计算 ──────────────────────────────────────────────

    @Test
    fun `backoff doubles each time`() {
        assertEquals(2_000L, BinderTransport.nextBackoffMs(1_000L))
        assertEquals(4_000L, BinderTransport.nextBackoffMs(2_000L))
        assertEquals(8_000L, BinderTransport.nextBackoffMs(4_000L))
    }

    @Test
    fun `backoff is capped so users do not wait too long`() {
        // ⚠️ 封顶 30 秒的理由：重装 APK 之后用户通常几秒内就开始用，
        // 无限翻倍（1→2→4→…→512 秒）会让体感变成「功能坏了」
        assertEquals(30_000L, BinderTransport.nextBackoffMs(16_000L))
        assertEquals(30_000L, BinderTransport.nextBackoffMs(30_000L))
        // 已经到顶再翻也不会超
        assertEquals(30_000L, BinderTransport.nextBackoffMs(60_000L))
    }

    @Test
    fun `backoff sequence from base reaches the cap in reasonable steps`() {
        // 从 1 秒起，多少步到顶？—— 这条锁住「不会太久才到顶」
        var d = 1_000L
        var steps = 0
        while (d < 30_000L && steps < 100) {
            d = BinderTransport.nextBackoffMs(d)
            steps++
        }
        assertEquals("应 5 步到顶（1s→2→4→8→16→32 封顶 30）", 5, steps)
        assertEquals(30_000L, d)
    }

    // ── 尝试上限 ──────────────────────────────────────────────

    @Test
    fun `keeps retrying below the cap`() {
        assertTrue(BinderTransport.shouldKeepRetrying(0))
        assertTrue(BinderTransport.shouldKeepRetrying(1))
        assertTrue(BinderTransport.shouldKeepRetrying(19))
    }

    @Test
    fun `stops retrying at the cap`() {
        // ⚠️ 封顶的必要性：框架**真的没了**时（用户卸载了 LSPosed）
        // `onServiceDisconnected` 会反复触发。无限重连会一直占线程 + 刷日志
        assertFalse(BinderTransport.shouldKeepRetrying(20))
        assertFalse(BinderTransport.shouldKeepRetrying(21))
    }

    @Test
    fun `retry cap is not so low that a transient outage exhausts it`() {
        // ⚠️ 反向约束：上限太小的话，一次较长的 App 重启（如系统升级）
        // 就会耗尽重试次数，之后永久不恢复 —— 那等于没修
        //
        // 20 次 × 平均约 20 秒 ≈ 6 分钟以上，足够覆盖常见的重启耗时
        val totalWaitApproxSec = generateSequence(1_000L) { BinderTransport.nextBackoffMs(it) }
            .take(20)
            .sum() / 1000
        assertTrue(
            "20 次重试的总等待应超过 3 分钟，实际约 ${totalWaitApproxSec}s",
            totalWaitApproxSec > 180,
        )
    }
}
