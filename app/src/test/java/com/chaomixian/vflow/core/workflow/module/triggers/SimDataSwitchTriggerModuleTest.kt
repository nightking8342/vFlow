package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.module.normalizeEnumValueOrNull
import com.chaomixian.vflow.permissions.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SimDataSwitchTriggerModule] 的定义层体检。
 *
 * 这些断言与 [SimDataSwitchMathTest] 互补：后者锁纯函数语义，本文件锁
 * 「模块对外声明」—— 枚举稳定值、旧值兼容、输出齐全、**零权限**。
 */
class SimDataSwitchTriggerModuleTest {

    private val module = SimDataSwitchTriggerModule()
    private val slotInput = module.getInputs().first { it.id == SimDataSwitchTriggerModule.PARAM_TARGET_SLOT }

    // ------------------------------------------------------- 枚举归一化

    @Test
    fun `normalizes canonical slot values`() {
        assertEquals(
            SimDataSwitchTriggerModule.SLOT_ANY,
            slotInput.normalizeEnumValueOrNull(SimDataSwitchTriggerModule.SLOT_ANY)
        )
        assertEquals(
            SimDataSwitchTriggerModule.SLOT_1,
            slotInput.normalizeEnumValueOrNull(SimDataSwitchTriggerModule.SLOT_1)
        )
        assertEquals(
            SimDataSwitchTriggerModule.SLOT_2,
            slotInput.normalizeEnumValueOrNull(SimDataSwitchTriggerModule.SLOT_2)
        )
    }

    @Test
    fun `normalizes legacy localized slot values`() {
        val legacy1 = listOf("卡1", "卡一", "SIM1")
        val legacy2 = listOf("卡2", "卡二", "SIM2")
        val legacyAny = listOf("任意", "任意卡", "任意切换")
        legacy1.forEach {
            assertEquals("旧值 '$it' 应归一化到 SLOT_1", SimDataSwitchTriggerModule.SLOT_1, slotInput.normalizeEnumValueOrNull(it))
        }
        legacy2.forEach {
            assertEquals("旧值 '$it' 应归一化到 SLOT_2", SimDataSwitchTriggerModule.SLOT_2, slotInput.normalizeEnumValueOrNull(it))
        }
        legacyAny.forEach {
            assertEquals("旧值 '$it' 应归一化到 SLOT_ANY", SimDataSwitchTriggerModule.SLOT_ANY, slotInput.normalizeEnumValueOrNull(it))
        }
    }

    /**
     * `slotIndexOf` 对「任意」必须返回 null —— 它表示「不是某个具体卡槽」。
     * Handler 靠这个把「任意」分流到不依赖订阅列表的判定路径。
     * 若哪天让它返回 0，「任意」就会退化成「只认卡1」。
     */
    @Test
    fun `slotIndexOf returns null for the any option`() {
        assertNull(SimDataSwitchTriggerModule.slotIndexOf(SimDataSwitchTriggerModule.SLOT_ANY))
    }

    @Test
    fun `default target is any`() {
        // 默认「任意」而非某个具体卡槽：多数用户想要的是「切卡就触发」，
        // 而不是「只在切到卡1 时触发」。改默认值会让存量行为的预期变化。
        assertEquals(SimDataSwitchTriggerModule.SLOT_ANY, slotInput.defaultValue)
    }

    @Test
    fun `unknown slot value does not normalize`() {
        assertNull(slotInput.normalizeEnumValueOrNull("卡3"))
        assertNull(slotInput.normalizeEnumValueOrNull(""))
    }

    @Test
    fun `slotIndexOf maps stable values to zero based indices`() {
        assertEquals(0, SimDataSwitchTriggerModule.slotIndexOf(SimDataSwitchTriggerModule.SLOT_1))
        assertEquals(1, SimDataSwitchTriggerModule.slotIndexOf(SimDataSwitchTriggerModule.SLOT_2))
        assertNull(SimDataSwitchTriggerModule.slotIndexOf("卡1"))
        assertNull(SimDataSwitchTriggerModule.slotIndexOf("bogus"))
    }

    // ------------------------------------------------------- 稳定 id

    @Test
    fun `module id is stable`() {
        // 一经发布不可更改：工作流里存的是这个字符串
        assertEquals("vflow.trigger.sim_data_switch", module.id)
    }

    // ------------------------------------------------------- 权限

    /**
     * ⚠️ **这条测试锁的是一个真实踩过的坑，不要把它反过来。**
     *
     * 广播本身零权限可收（实测普通第三方 App 未授权即收到），所以最初本模块**没有**
     * 声明任何权限 —— 看起来更「干净」。但 Handler 需要读订阅列表来把 subId 映射成卡槽，
     * 那需要 `READ_PHONE_STATE`；而 `TriggerService.handleWorkflowChanged` 会在注册前检查权限，
     * 缺失时**静默把工作流置为未启用**。
     *
     * 结果：新装设备上（vFlow 尚未因其它功能取得该权限）整个触发器**静默永不触发**，
     * 而真机测试因为设备上已持有该权限而恰好测不出来。
     *
     * 教训是把「需要权限」误当成「无需权限」——**声明得少反而更危险**。
     */
    @Test
    fun `trigger declares READ_PHONE_STATE because the handler must map subId to slot`() {
        assertEquals(
            "Handler 靠读订阅列表把 subId 映射成卡槽，缺 READ_PHONE_STATE 会让整个工作流被静默禁用",
            listOf(PermissionManager.READ_PHONE_STATE),
            module.requiredPermissions,
        )
    }

    // ------------------------------------------------------- 输出

    @Test
    fun `declares all outputs referenced by the trigger data`() {
        val outputIds = module.getOutputs(null).map { it.id }.toSet()

        // 这 6 个 id 与 SimDataSwitchTriggerData 的字段、以及 execute() 里回填的 key 一一对应。
        // 漏一个的后果是下游魔法变量取到空值。
        listOf("sim_slot", "is_slot1", "is_slot2", "card_label", "carrier_name", "sub_id")
            .forEach { assertTrue("缺少输出声明: $it", outputIds.contains(it)) }
    }

    // ------------------------------------------------------- 输入

    @Test
    fun `slot input does not accept runtime variables`() {
        // 卡槽是编译期确定的枚举；接受变量会让 Handler 的归一化无法静态判定
        assertFalse(slotInput.acceptsMagicVariable)
        assertFalse(slotInput.acceptsNamedVariable)
    }

    @Test
    fun `slot input declares three option strings`() {
        // 任意 / 卡1 / 卡2 —— 选项与文案必须一一对应，漏一个会显示成另一种语言
        assertEquals(3, slotInput.options.size)
        assertEquals(3, slotInput.optionsStringRes.size)
    }
}
