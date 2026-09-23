package com.chaomixian.vflow.core.workflow.module.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimDataSwitchMath] 的回归测试。
 *
 * 重点锁「改错了不报错、只静默变差」的两个方向：
 *  1. **该触发却没触发**（映射查不到就静默跳过）—— 表现为触发器失灵；
 *  2. **不该触发却触发**（哨兵值或未知 subId 被猜成一个卡槽）—— 表现为
 *     「切卡1 却跑了配在卡2 上的工作流」，比失灵更难发现。
 *
 * 卡数据取自真机实测（Redmi K60 至尊版，2026-09-23）：
 *   slot0 subId=1 中国广电 / slot1 subId=2 中国联通。
 */
class SimDataSwitchMathTest {

    private val realCards = listOf(
        SimCardInfo(subId = 1, simSlotIndex = 0, carrierName = "中国广电", displayName = "中国广电"),
        SimCardInfo(subId = 2, simSlotIndex = 1, carrierName = "中国联通", displayName = "中国联通"),
    )

    // ------------------------------------------------------- subId -> 卡槽

    @Test
    fun `subId 1 resolves to slot 0`() {
        assertEquals(0, resolveSimSlotBySubId(1, realCards))
    }

    @Test
    fun `subId 2 resolves to slot 1`() {
        assertEquals(1, resolveSimSlotBySubId(2, realCards))
    }

    @Test
    fun `unknown subId resolves to null instead of guessing a slot`() {
        // 关键反向断言：未知 subId 必须返回 null。若实现「回退到卡1」，
        // 切到一张不存在的卡会误触发卡1 的工作流。
        assertNull(resolveSimSlotBySubId(99, realCards))
    }

    @Test
    fun `invalid subId resolves to null`() {
        assertNull(resolveSimSlotBySubId(-1, realCards))
        assertNull(resolveSimSlotBySubId(0, realCards))
    }

    @Test
    fun `empty card list resolves to null`() {
        // 未授权 READ_PHONE_STATE 时订阅列表为空，必须退化为「不触发」
        assertNull(resolveSimSlotBySubId(1, emptyList()))
    }

    /**
     * subId 与卡槽**不是**恒等关系：重插卡后系统可能给同一个卡槽分配新 subId。
     * 若实现里偷懒写成 `slot = subId - 1`，这条会红。
     */
    @Test
    fun `subId is not simply slot plus one`() {
        val shifted = listOf(
            SimCardInfo(subId = 5, simSlotIndex = 0, carrierName = "A", displayName = null),
            SimCardInfo(subId = 7, simSlotIndex = 1, carrierName = "B", displayName = null),
        )
        assertEquals(0, resolveSimSlotBySubId(5, shifted))
        assertEquals(1, resolveSimSlotBySubId(7, shifted))
    }

    // ------------------------------------------------------- 卡槽 -> 卡信息

    @Test
    fun `findCardBySlot returns the matching card`() {
        assertEquals(2, findCardBySlot(1, realCards)?.subId)
        assertEquals(1, findCardBySlot(0, realCards)?.subId)
    }

    @Test
    fun `findCardBySlot returns null for an empty slot`() {
        assertNull(findCardBySlot(1, listOf(realCards[0])))
    }

    // ------------------------------------------------------- 触发判定

    @Test
    fun `switching to slot 0 triggers a slot-1-configured workflow`() {
        assertTrue(shouldTriggerForSlot(targetSlot = 0, newSubId = 1, cards = realCards))
    }

    @Test
    fun `switching to slot 1 triggers a slot-2-configured workflow`() {
        assertTrue(shouldTriggerForSlot(targetSlot = 1, newSubId = 2, cards = realCards))
    }

    @Test
    fun `switching to slot 0 does not trigger a slot-2-configured workflow`() {
        // 最要紧的反向断言：配在卡2 上的工作流在切到卡1 时绝不能触发
        assertFalse(shouldTriggerForSlot(targetSlot = 1, newSubId = 1, cards = realCards))
    }

    @Test
    fun `unknown subId never triggers any slot`() {
        assertFalse(shouldTriggerForSlot(targetSlot = 0, newSubId = 99, cards = realCards))
        assertFalse(shouldTriggerForSlot(targetSlot = 1, newSubId = 99, cards = realCards))
    }

    @Test
    fun `empty card list never triggers any slot`() {
        assertFalse(shouldTriggerForSlot(targetSlot = 0, newSubId = 1, cards = emptyList()))
    }

    // ------------------------------------------------------- 「任意」语义

    @Test
    fun `any triggers on any real switch`() {
        assertTrue(shouldTriggerForAny(1))
        assertTrue(shouldTriggerForAny(2))
        assertTrue(shouldTriggerForAny(99))
    }

    /**
     * 「任意」**不读订阅列表** —— 这是它唯一不依赖 `READ_PHONE_STATE` 的原因。
     * 所以只要 subId 是真实的就必须触发，哪怕一张卡都读不到。
     */
    @Test
    fun `any does not depend on the subscription list`() {
        // 签名本身就不收 cards 参数，这条断言锁住这个设计选择
        assertTrue(shouldTriggerForAny(1))
    }

    @Test
    fun `any ignores sentinel subIds`() {
        assertFalse(shouldTriggerForAny(SIM_INVALID_SUBSCRIPTION_ID))
        assertFalse(shouldTriggerForAny(-1))
        assertFalse(shouldTriggerForAny(0))
        assertFalse(shouldTriggerForAny(Int.MAX_VALUE))
    }

    // ------------------------------------------------------- 哨兵值

    @Test
    fun `invalid subscription id is a sentinel`() {
        assertTrue(isSentinelSubId(SIM_INVALID_SUBSCRIPTION_ID))
        assertTrue(isSentinelSubId(-1))
        assertTrue(isSentinelSubId(0))
    }

    /**
     * `SubscriptionManager.DEFAULT_SUBSCRIPTION_ID` = Integer.MAX_VALUE。
     * 系统在「无可用订阅」时会给出它，必须与 INVALID 一样忽略 ——
     * 否则会去订阅列表里找一个不存在的卡，最终走 null 分支（碰巧也对），
     * 但日志里会留下一次「未知 subId」的噪声，掩盖真实的映射失败。
     */
    @Test
    fun `default subscription sentinel is recognized`() {
        assertTrue(isSentinelSubId(Int.MAX_VALUE))
    }

    @Test
    fun `a normal subId is not a sentinel`() {
        assertFalse(isSentinelSubId(1))
        assertFalse(isSentinelSubId(2))
    }

    // ------------------------------------------------------- 卡名展示

    @Test
    fun `label prefers display name`() {
        val card = SimCardInfo(1, 0, carrierName = "Carrier", displayName = "My SIM")
        assertEquals("My SIM", card.label())
    }

    @Test
    fun `label falls back to carrier name when display name is blank`() {
        val card = SimCardInfo(1, 0, carrierName = "中国联通", displayName = "   ")
        assertEquals("中国联通", card.label())
    }

    @Test
    fun `label falls back to SimN when everything is missing`() {
        // 卡槽 0 -> SIM1，卡槽 1 -> SIM2，与用户心智一致
        assertEquals("SIM1", SimCardInfo(1, 0, carrierName = null, displayName = null).label())
        assertEquals("SIM2", SimCardInfo(2, 1, carrierName = null, displayName = null).label())
    }
}
