package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.module.normalizeEnumValueOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DoNotDisturbTriggerModuleTest {

    private val module = DoNotDisturbTriggerModule()
    private val stateInput = module.getInputs().first { it.id == "target_state" }

    @Test
    fun `normalizes canonical state values`() {
        assertEquals(
            DoNotDisturbTriggerModule.STATE_ON,
            stateInput.normalizeEnumValueOrNull(DoNotDisturbTriggerModule.STATE_ON)
        )
        assertEquals(
            DoNotDisturbTriggerModule.STATE_OFF,
            stateInput.normalizeEnumValueOrNull(DoNotDisturbTriggerModule.STATE_OFF)
        )
        assertEquals(
            DoNotDisturbTriggerModule.STATE_ANY,
            stateInput.normalizeEnumValueOrNull(DoNotDisturbTriggerModule.STATE_ANY)
        )
    }

    @Test
    fun `normalizes legacy localized state values`() {
        assertEquals(DoNotDisturbTriggerModule.STATE_ON, stateInput.normalizeEnumValueOrNull("开启时"))
        assertEquals(DoNotDisturbTriggerModule.STATE_ON, stateInput.normalizeEnumValueOrNull("打开时"))
        assertEquals(DoNotDisturbTriggerModule.STATE_ON, stateInput.normalizeEnumValueOrNull("开启"))
        assertEquals(DoNotDisturbTriggerModule.STATE_OFF, stateInput.normalizeEnumValueOrNull("关闭时"))
        assertEquals(DoNotDisturbTriggerModule.STATE_OFF, stateInput.normalizeEnumValueOrNull("关闭"))
        assertEquals(DoNotDisturbTriggerModule.STATE_ANY, stateInput.normalizeEnumValueOrNull("任意"))
    }

    @Test
    fun `returns null for unknown state values`() {
        assertNull(stateInput.normalizeEnumValueOrNull(null))
        assertNull(stateInput.normalizeEnumValueOrNull("unexpected"))
    }

    /** 触发器模块 id 必须带 `vflow.trigger.` 前缀，否则 WorkflowNormalizer 不会认它为触发器。 */
    @Test
    fun `module id carries the trigger prefix`() {
        assertTrue(module.id.startsWith("vflow.trigger."))
    }

    /**
     * 该触发器只应声明勿扰访问权限。
     *
     * 若有人「顺手」加上通知使用权（NOTIFICATION_LISTENER_SERVICE），会强迫用户
     * 再开一个无关权限，且与 NotificationTriggerModule 的权限语义混淆。
     */
    @Test
    fun `declares exactly the notification policy permission`() {
        assertEquals(1, module.requiredPermissions.size)
        assertEquals(
            "android.permission.ACCESS_NOTIFICATION_POLICY",
            module.requiredPermissions.first().id
        )
    }

    /** 下游需要 enabled/previous_enabled 两个布尔输出，防止被误删。 */
    @Test
    fun `exposes enabled and previous_enabled outputs`() {
        val outputIds = module.getOutputs(null).map { it.id }.toSet()
        assertTrue(outputIds.contains("enabled"))
        assertTrue(outputIds.contains("previous_enabled"))
    }
}
