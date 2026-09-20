package com.chaomixian.vflow.core.workflow.module.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [FoldStateResolver] 的回归测试。
 *
 * 阈值取自 MIX Fold 3 实测校准值（设计文档 §9.3）：
 * 折叠 2–10° / 半折 80–110° / 展开 172–179°，稳定期抖动 7°。
 */
class FoldStateResolverTest {

    /** 去抖与半折确认都设为 0，便于专注测试状态判定本身 */
    private fun resolver(
        debounceMs: Long = 0L,
        halfOpenConfirmMs: Long = 0L,
    ) = FoldStateResolver(debounceMs = debounceMs, halfOpenConfirmMs = halfOpenConfirmMs)

    private fun angle(deg: Float) = FoldSignals(angleDegrees = deg)

    // ---------------------------------------------------------- 基础三态判定

    @Test
    fun `unfolded angle classifies as unfolded`() {
        val r = resolver()
        r.prime(angle(176f), 0L)
        assertEquals(FoldState.UNFOLDED, r.lastSnapshot?.state)
    }

    @Test
    fun `folded angle classifies as folded`() {
        val r = resolver()
        r.prime(angle(5f), 0L)
        assertEquals(FoldState.FOLDED, r.lastSnapshot?.state)
    }

    @Test
    fun `mid angle classifies as half opened`() {
        val r = resolver()
        r.prime(angle(90f), 0L)
        assertEquals(FoldState.HALF_OPENED, r.lastSnapshot?.state)
    }

    @Test
    fun `angle at calibrated boundaries maps as documented`() {
        // 实测校准值：<40 折叠 / >150 展开 / 其余半折
        assertEquals(FoldState.FOLDED, resolvePrimed(39f))
        assertEquals(FoldState.HALF_OPENED, resolvePrimed(40f))
        assertEquals(FoldState.HALF_OPENED, resolvePrimed(150f))
        assertEquals(FoldState.UNFOLDED, resolvePrimed(151f))
    }

    private fun resolvePrimed(deg: Float): FoldState? {
        val r = resolver()
        r.prime(angle(deg), 0L)
        return r.lastSnapshot?.state
    }

    // ---------------------------------------------------------- 边沿检测

    @Test
    fun `prime establishes baseline without producing a transition`() {
        val r = resolver()
        r.prime(angle(176f), 0L)
        // 基线建立后，送入相同状态不应产生变迁
        assertNull(r.update(angle(177f), 100L))
    }

    @Test
    fun `update without prime establishes baseline without transition`() {
        val r = resolver()
        // 未调用 prime，首次 update 只建基线
        assertNull(r.update(angle(176f), 0L))
        assertEquals(FoldState.UNFOLDED, r.lastSnapshot?.state)
    }

    @Test
    fun `fold transition is reported once on the edge`() {
        val r = resolver()
        r.prime(angle(176f), 0L)

        val t = r.update(angle(5f), 100L)
        assertNotNull(t)
        assertEquals(FoldState.UNFOLDED, t!!.from)
        assertEquals(FoldState.FOLDED, t.to)

        // 继续处于折叠态，不应重复上报
        assertNull(r.update(angle(4f), 200L))
    }

    @Test
    fun `full fold unfold round trip produces two transitions`() {
        val r = resolver()
        r.prime(angle(179f), 0L)

        val fold = r.update(angle(8f), 100L)
        assertEquals(FoldState.FOLDED, fold?.to)

        val unfold = r.update(angle(176f), 200L)
        assertEquals(FoldState.FOLDED, unfold?.from)
        assertEquals(FoldState.UNFOLDED, unfold?.to)
    }

    // ---------------------------------------------------------- 迟滞

    @Test
    fun `hysteresis prevents flapping just below folded threshold`() {
        val r = resolver()
        r.prime(angle(176f), 0L)

        // 进入折叠态
        assertEquals(FoldState.FOLDED, r.update(angle(5f), 100L)?.to)

        // 角度回到 45°：高于 foldedEnter(40) 但低于 foldedExit(55)
        // 迟滞应使其保持在 FOLDED，不立刻跳到 HALF_OPENED
        assertNull(r.update(angle(45f), 200L))
        assertEquals(FoldState.FOLDED, r.lastSnapshot?.state)

        // 超过 foldedExit 后才切换
        assertEquals(FoldState.HALF_OPENED, r.update(angle(60f), 300L)?.to)
    }

    @Test
    fun `hysteresis prevents flapping just above unfolded threshold`() {
        val r = resolver()
        r.prime(angle(176f), 0L)

        // 从展开态掉到 140°：低于 unfoldedExit(135)? 否 —— 140 > 135，应保持 UNFOLDED
        assertNull(r.update(angle(140f), 100L))
        assertEquals(FoldState.UNFOLDED, r.lastSnapshot?.state)

        // 跌破 135 才进入半折
        assertEquals(FoldState.HALF_OPENED, r.update(angle(130f), 200L)?.to)
    }

    @Test
    fun `measured jitter of seven degrees does not cause transitions`() {
        // 实测稳定期抖动 172–179，不应触发任何变迁
        val r = resolver(debounceMs = 300L, halfOpenConfirmMs = 500L)
        r.prime(angle(179f), 0L)

        var t = 100L
        listOf(172f, 179f, 175f, 178f, 173f, 177f).forEach { deg ->
            assertNull("jitter $deg should not transition", r.update(angle(deg), t))
            t += 600L
        }
        assertEquals(FoldState.UNFOLDED, r.lastSnapshot?.state)
    }

    // ---------------------------------------------------------- 去抖

    @Test
    fun `debounce delays confirmation until candidate is stable`() {
        val r = resolver(debounceMs = 300L, halfOpenConfirmMs = 300L)
        r.prime(angle(176f), 0L)

        // 首次进入折叠候选，时间未到 —— 不确认
        assertNull(r.update(angle(5f), 100L))
        // 未满 300ms
        assertNull(r.update(angle(5f), 250L))
        // 满 300ms —— 确认
        assertEquals(FoldState.FOLDED, r.update(angle(5f), 450L)?.to)
    }

    @Test
    fun `candidate reset when state changes mid debounce`() {
        val r = resolver(debounceMs = 300L, halfOpenConfirmMs = 300L)
        r.prime(angle(176f), 0L)

        r.update(angle(5f), 100L)   // 折叠候选开始计时
        r.update(angle(90f), 200L)  // 变成半折候选 —— 重新计时
        assertNull(r.update(angle(90f), 400L))  // 距 200 仅 200ms，未满
        assertEquals(FoldState.HALF_OPENED, r.update(angle(90f), 550L)?.to)
    }

    @Test
    fun `half opened requires longer confirmation than other states`() {
        val r = FoldStateResolver(debounceMs = 100L, halfOpenConfirmMs = 800L)
        r.prime(angle(176f), 0L)

        r.update(angle(90f), 0L)
        // 超过 debounceMs(100) 但未达 halfOpenConfirmMs(800)
        assertNull(r.update(angle(90f), 300L))
        // 达到 800ms 才确认
        assertEquals(FoldState.HALF_OPENED, r.update(angle(90f), 900L)?.to)
    }

    @Test
    fun `fast sweep through half opened does not trigger half opened`() {
        // 快速开合（如 200ms 内 176→5）：半折只是路过，不应判定为 HALF_OPENED
        val r = FoldStateResolver(debounceMs = 100L, halfOpenConfirmMs = 500L)
        r.prime(angle(176f), 0L)

        r.update(angle(90f), 0L)    // 路过半折
        r.update(angle(5f), 150L)   // 已经到折叠 —— 半折候选被替换
        val t = r.update(angle(5f), 400L)
        assertEquals(FoldState.FOLDED, t?.to)
    }

    // ---------------------------------------------------------- 信号降级

    @Test
    fun `falls back to miui posture when angle unavailable`() {
        val r = resolver()
        val snapshot = r.prime(FoldSignals(angleDegrees = null, miuiPosture = 1), 0L)
        assertNotNull(snapshot)
        assertEquals(FoldState.FOLDED, snapshot!!.state)
        assertEquals(PostureSource.MIUI, snapshot.source)
    }

    @Test
    fun `angle takes priority over miui posture`() {
        // 实测依据（§9.2 问题 1）：device_posture 滞后，角度优先
        val r = resolver()
        // 角度说折叠(5°)，posture 说展开(3) —— 应采信角度
        val snapshot = r.prime(FoldSignals(angleDegrees = 5f, miuiPosture = 3), 0L)
        assertEquals(FoldState.FOLDED, snapshot!!.state)
        assertEquals(PostureSource.SENSOR, snapshot.source)
    }

    @Test
    fun `miui posture two maps to half opened`() {
        val r = resolver()
        val snapshot = r.prime(FoldSignals(angleDegrees = null, miuiPosture = 2), 0L)
        assertEquals(FoldState.HALF_OPENED, snapshot!!.state)
    }

    @Test
    fun `miui posture unknown zero is not usable`() {
        val r = resolver()
        // 0 = UNKNOWN，应视为无信号
        assertNull(r.prime(FoldSignals(angleDegrees = null, miuiPosture = 0), 0L))
    }

    @Test
    fun `no usable signal returns null and does not change state`() {
        val r = resolver()
        r.prime(angle(176f), 0L)
        assertNull(r.update(FoldSignals(null, null), 100L))
        assertEquals(FoldState.UNFOLDED, r.lastSnapshot?.state)
    }

    // ---------------------------------------------------------- 快照与重置

    @Test
    fun `snapshot carries angle and marks unavailable angle`() {
        val r = resolver()
        val withAngle = r.prime(angle(90f), 0L)
        assertEquals(90f, withAngle!!.angleDegrees, 0.01f)

        val r2 = resolver()
        val withoutAngle = r2.prime(FoldSignals(angleDegrees = null, miuiPosture = 3), 0L)
        assertEquals(FoldStateResolver.ANGLE_UNAVAILABLE, withoutAngle!!.angleDegrees, 0.01f)
    }

    @Test
    fun `reset clears state so next update re primes without transition`() {
        val r = resolver()
        r.prime(angle(176f), 0L)
        r.reset()
        // reset 后再 update 应只建基线，不产生变迁
        assertNull(r.update(angle(5f), 100L))
        assertEquals(FoldState.FOLDED, r.lastSnapshot?.state)
    }

    // ---------------------------------------------------------- 枚举与序列化

    @Test
    fun `fold state serialized values are stable`() {
        assertEquals("folded", FoldState.FOLDED.serialized)
        assertEquals("half_opened", FoldState.HALF_OPENED.serialized)
        assertEquals("unfolded", FoldState.UNFOLDED.serialized)
    }

    @Test
    fun `fold state round trips through serialized value`() {
        FoldState.entries.forEach { state ->
            assertEquals(state, FoldState.fromSerialized(state.serialized))
        }
        assertNull(FoldState.fromSerialized("nonsense"))
        assertNull(FoldState.fromSerialized(null))
    }

    @Test
    fun `posture source serialized values are stable`() {
        assertEquals("sensor", PostureSource.SENSOR.serialized)
        assertEquals("miui", PostureSource.MIUI.serialized)
    }
}
