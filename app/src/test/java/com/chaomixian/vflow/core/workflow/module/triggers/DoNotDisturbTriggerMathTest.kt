package com.chaomixian.vflow.core.workflow.module.triggers

import android.app.NotificationManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁定 interruption filter -> 「勿扰是否开启」的映射。
 *
 * 这层映射直接决定触发器在每个方向上的行为，且极易被「顺手改写」破坏，
 * 因此逐值锁定。映射依据 AOSP NotificationManager 的 ZEN_MODE_* 对照表。
 */
class DoNotDisturbTriggerMathTest {

    @Test
    fun `filter ALL means Do Not Disturb is off`() {
        assertEquals(false, isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_ALL))
    }

    @Test
    fun `PRIORITY NONE and ALARMS all mean Do Not Disturb is on`() {
        assertEquals(true, isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_PRIORITY))
        assertEquals(true, isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_NONE))
        assertEquals(true, isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_ALARMS))
    }

    /**
     * UNKNOWN 表示取值不可用（例如监听器尚未连接），必须返回 null
     * 让调用方回退，而不是武断地当作「关闭」。
     */
    @Test
    fun `UNKNOWN is unavailable rather than off`() {
        assertNull(isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_UNKNOWN))
    }

    /**
     * PRIORITY 与 NONE 之间切换时，filter 变了但「是否开启」没变。
     * 这是 Handler 去抖逻辑依赖的性质：两者都必须映射为 true，
     * 否则会产生一次伪触发。
     */
    @Test
    fun `PRIORITY and NONE agree so switching between them is not a real change`() {
        val priority = isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_PRIORITY)
        val none = isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_NONE)
        assertEquals(priority, none)
        assertTrue(priority == true)
    }

    @Test
    fun `only the off filter maps to false`() {
        val allFilters = listOf(
            NotificationManager.INTERRUPTION_FILTER_ALL,
            NotificationManager.INTERRUPTION_FILTER_PRIORITY,
            NotificationManager.INTERRUPTION_FILTER_NONE,
            NotificationManager.INTERRUPTION_FILTER_ALARMS,
            NotificationManager.INTERRUPTION_FILTER_UNKNOWN
        )
        val falseCount = allFilters.count { isDndFilterEnabled(it) == false }
        assertEquals("只有 INTERRUPTION_FILTER_ALL 应映射为「关闭」", 1, falseCount)
        assertFalse(isDndFilterEnabled(NotificationManager.INTERRUPTION_FILTER_UNKNOWN) == false)
    }
}
