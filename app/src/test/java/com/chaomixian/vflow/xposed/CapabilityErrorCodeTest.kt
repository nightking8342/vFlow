package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.CapabilityErrorAction
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.userAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * §6.4 错误码枚举测试。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.4。
 *
 * 锁的是那两条**硬约束**：
 * ① §6.4 那张失败分类表的**每一行必须能映射到恰好一个**枚举值；
 * ② `detail` 绝不参与任何判断（本文件用「枚举里根本没有 detail 这个概念」来锁它）。
 */
class CapabilityErrorCodeTest {

    @Test
    fun `exactly five codes exist`() {
        // ⚠️ §6.4 的五值是**已定案**的，不得增删。
        // 加第六个码会让「逐行可映射」这条约束需要重新论证 ——
        // 这个断言就是让那次论证**必须发生**的闸门。
        assertEquals(5, CapabilityErrorCode.entries.size)
    }

    @Test
    fun `wire strings are the agreed literals`() {
        // ⚠️ 这些字符串是跨进程协议的一部分，**一经发布不要改**
        assertEquals("capability_absent", CapabilityErrorCode.CAPABILITY_ABSENT.wire)
        assertEquals("timeout", CapabilityErrorCode.TIMEOUT.wire)
        assertEquals("handler_error", CapabilityErrorCode.HANDLER_ERROR.wire)
        assertEquals("channel_down", CapabilityErrorCode.CHANNEL_DOWN.wire)
        assertEquals("payload_too_large", CapabilityErrorCode.PAYLOAD_TOO_LARGE.wire)
    }

    @Test
    fun `every code maps to exactly one action`() {
        // ⚠️ 约束 1 的正面：每个枚举值都**有**映射（`when` 写全才不会漏，
        // 但这条断言保证将来加码时**测试会红**，而不只是编译期靠穷尽 when）
        val actions = CapabilityErrorCode.entries.map { it.userAction() }
        assertEquals("每个码都要有映射", CapabilityErrorCode.entries.size, actions.size)
        actions.forEach { assertNotNull(it) }
    }

    @Test
    fun `the mapping table matches section 6_4`() {
        // §6.4 那张表逐行落地
        assertEquals(CapabilityErrorAction.UPGRADE_APP, CapabilityErrorCode.CAPABILITY_ABSENT.userAction())
        assertEquals(CapabilityErrorAction.REPORT_PROBLEM, CapabilityErrorCode.TIMEOUT.userAction())
        assertEquals(CapabilityErrorAction.CHECK_CAPABILITY, CapabilityErrorCode.HANDLER_ERROR.userAction())
        assertEquals(CapabilityErrorAction.CHECK_LSPOSED, CapabilityErrorCode.CHANNEL_DOWN.userAction())
    }

    @Test
    fun `payload_too_large goes to report problem not to check config`() {
        // ⚠️⚠️ §6.4 特别说明：`payload_too_large` **不是用户能处理的失败**，
        // 是「实现缺陷或数据异常」（§3.6 要求 hook 侧主动截断 ⇒ 正常路径不该出这个码）。
        //
        // 把它引向「改配置」会让用户白折腾 —— 这正是 P4 踩过的坑的形态
        //（`TriggerService` 加载比 hook 连接早 1.6 秒，旧文案却让用户「检查 LSPosed 配置」）。
        assertEquals(
            CapabilityErrorAction.REPORT_PROBLEM,
            CapabilityErrorCode.PAYLOAD_TOO_LARGE.userAction(),
        )
    }

    @Test
    fun `three codes that point at the app side are distinguishable from the framework one`() {
        // §6.4 的立意：「前三/四类指向 App 侧，其余指向框架配置」——
        // 混在一起会让用户去白折腾错误的方向。
        //
        // 断言：只有 channel_down 指向框架配置
        val frameworkSide = CapabilityErrorCode.entries.filter {
            it.userAction() == CapabilityErrorAction.CHECK_LSPOSED
        }
        assertEquals("只有 channel_down 该指向 LSPosed", listOf(CapabilityErrorCode.CHANNEL_DOWN), frameworkSide)
    }

    @Test
    fun `fromWire resolves every code and rejects unknown`() {
        CapabilityErrorCode.entries.forEach { code ->
            assertEquals(code, CapabilityErrorCode.fromWire(code.wire))
        }
        // ⚠️ 未知值返回 **null** 而不是回落 —— 静默回落会让
        // 「新 hook 层引入了新错误码」这件事在旧 App 上看不出来。
        // 调用方（decodeResponse）负责把它转成 HANDLER_ERROR + 原始串进 detail。
        assertNull(CapabilityErrorCode.fromWire("not_a_code"))
        assertNull(CapabilityErrorCode.fromWire(""))
        // ⚠️ 用常量**名**而不是 wire 值时必须查不到 ——
        // 这锁的是「别把 fromWire 写成 valueOf」
        assertNull(CapabilityErrorCode.fromWire("TIMEOUT"))
    }

    @Test
    fun `wire strings are unique`() {
        val wires = CapabilityErrorCode.entries.map { it.wire }
        assertEquals("wire 值不得重复（重复会让 fromWire 有歧义）", wires.size, wires.toSet().size)
    }

    @Test
    fun `only channel down and handler error are produced without waiting`() {
        // 分组断言，锁「哪些码是**调用前/立刻**可判的」——
        // 这些是 §6.4「用户该做什么」里最不该被超时掩盖的几类。
        //
        // capability_absent：调用前判定（CapabilityPresence.ABSENT）或 hook 侧立刻回
        // channel_down：断连时立刻唤醒
        // handler_error：handler 抛异常时立刻回
        // 而 timeout / payload_too_large 都是「等待或事后」才知道的
        val immediate = setOf(
            CapabilityErrorCode.CAPABILITY_ABSENT,
            CapabilityErrorCode.CHANNEL_DOWN,
            CapabilityErrorCode.HANDLER_ERROR,
        )
        assertEquals(3, immediate.size)
        // 反向：timeout 与 payload_too_large 不在其中
        assertEquals(false, CapabilityErrorCode.TIMEOUT in immediate)
        assertEquals(false, CapabilityErrorCode.PAYLOAD_TOO_LARGE in immediate)
    }
}
