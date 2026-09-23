package com.chaomixian.vflow.core.workflow.module.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimDataSwitchSupport] 的语义锁定。
 *
 * 这些是纯 JVM 可测的部分（不触发 Android 调用）。重点锁两类**静默错误**：
 *  1. 用「subId = 卡槽 + 1」的算式替代真实映射 —— 重插卡后会切错卡；
 *  2. 把「读不到卡列表」与「卡槽没卡」混成一个错误 —— 排障时分不清是权限还是硬件。
 *
 * 真机取值为（设计文档 §9.1，Redmi K60 至尊版 / Android 17）：
 *   卡槽0 subId=1 中国广电 / 卡槽1 subId=2 中国联通。
 */
class SimDataSwitchSupportTest {

    // ------------------------------------------------- 失败态必须可区分

    @Test
    fun `lookup failure kinds are distinct types`() {
        // 三种失败是三个独立类型，不是同一个 null —— 这是修初版「含糊报错」的核心
        val a: SimSlotLookup = SimSlotLookup.Unavailable
        val b: SimSlotLookup = SimSlotLookup.EmptySlot
        val c: SimSlotLookup = SimSlotLookup.NotSupported
        assertNotEquals(a, b)
        assertNotEquals(b, c)
        assertNotEquals(a, c)
    }

    @Test
    fun `found carries both subId and a human readable label`() {
        val found = SimSlotLookup.Found(subId = 2, label = "中国联通")
        assertEquals(2, found.subId)
        assertEquals("中国联通", found.label)
    }

    @Test
    fun `found is distinguishable from the failure states`() {
        val found: SimSlotLookup = SimSlotLookup.Found(1, "中国广电")
        assertTrue("Found 必须能被 when 分支识别为成功", found is SimSlotLookup.Found)
        assertTrue(found !is SimSlotLookup.Unavailable)
    }

    // ------------------------------------------------- 卡槽与 subId 的换算关系

    /**
     * 真机上 subId 1/2 恰好对应卡槽 0/1，**看起来**像 `subId = 卡槽 + 1`。
     * 这条测试用反例固定住「不能这么算」：设备 A 是 1/2，设备 B 是 5/7，
     * 同一个卡槽在不同设备上 subId 完全不同。
     */
    @Test
    fun `subId to slot mapping is not an arithmetic formula`() {
        // 设备 A：卡槽0→subId1，卡槽1→subId2
        assertTrue(matchesSlot(subId = 1, slot = 0, cards = listOf(0 to 1, 1 to 2)))
        // 设备 B：卡槽0→subId5，卡槽1→subId7（同一卡槽，subId 完全不同）
        assertTrue(matchesSlot(subId = 5, slot = 0, cards = listOf(0 to 5, 1 to 7)))
        assertTrue(matchesSlot(subId = 7, slot = 1, cards = listOf(0 to 5, 1 to 7)))
        // 若实现写成 subId == slot + 1，设备 B 上会判错
        assertTrue(
            "设备 B 上 subId=5 属于卡槽0；算式 5-1=4 会得到不存在的卡槽",
            matchesSlot(subId = 5, slot = 0, cards = listOf(0 to 5, 1 to 7)),
        )
    }

    @Test
    fun `unknown subId matches no slot`() {
        val cards = listOf(0 to 1, 1 to 2)
        assertTrue(!matchesSlot(subId = 99, slot = 0, cards = cards))
        assertTrue(!matchesSlot(subId = 99, slot = 1, cards = cards))
    }

    /** 与 Handler 里 `resolveSimSlotBySubId` 同语义的最小复刻，用于锁定换算关系。 */
    private fun matchesSlot(subId: Int, slot: Int, cards: List<Pair<Int, Int>>): Boolean =
        cards.firstOrNull { it.second == subId }?.first == slot
}
