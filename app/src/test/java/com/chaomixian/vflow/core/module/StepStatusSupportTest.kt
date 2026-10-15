package com.chaomixian.vflow.core.module

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「步骤状态输出」供给层的纯函数测试（fork，P0-3 状态可见）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.2（决策 D2）。
 *
 * 为什么必须有：这条改动决定 `IF {{任意步骤.success}} 为假` 是否可靠，而两端都会
 * **静默变差** ——
 *  - 少注入 ⇒ 该步骤的 `success` 解析不到，判据回落成字符串 ⇒ 恒 true（分流永远走"成功"分支）；
 *  - **覆盖过头** ⇒ 把模块自己的业务语义（如 `ClickModule` 的落点结果）顶成 `true`，假成功；
 *  - id 与执行器注入的键不一致 ⇒ 成功/跳过落在两个键上，同样恒真。
 * 三者都不报错、不崩溃。
 */
class StepStatusSupportTest {

    @Test
    fun `状态输出 id 必须与执行器注入的键同字面量`() {
        // ⚠️ 执行器失败分支（SKIP）注入的是字面量 "success"；改这里而不改那里，
        //    「成功 true / 跳过 false」会落到两个不同的键上。
        assertEquals("success", StepStatusSupport.SUCCESS_OUTPUT_ID)
    }

    @Test
    fun `状态输出的类型是 BOOLEAN`() {
        val def = StepStatusSupport.successOutputDefinition()
        assertEquals(StepStatusSupport.SUCCESS_OUTPUT_ID, def.id)
        assertEquals(VTypeRegistry.BOOLEAN.id, def.typeName)
        assertTrue("必须给本地化名，否则选择器里显示的是裸 id", def.nameStringRes != null)
    }

    // ---------- withSuccessOutput ----------

    @Test
    fun `未声明 success 的模块会被补上状态输出`() {
        val declared = listOf(OutputDefinition("count", "结果数量", VTypeRegistry.NUMBER.id))
        val result = StepStatusSupport.withSuccessOutput(declared)

        assertEquals(2, result.size)
        assertEquals(StepStatusSupport.SUCCESS_OUTPUT_ID, result.last().id)
        assertEquals("原有输出必须原样保留且在前", "count", result.first().id)
    }

    @Test
    fun `声明了 success 的模块不会被重复添加`() {
        // 73 个模块自己声明了 success（业务语义，如「点击落点是否命中」）——
        // 合成项不能顶掉它、也不能让它出现两次（选择器里会重复列出）。
        val declared = listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id),
            OutputDefinition("count", "结果数量", VTypeRegistry.NUMBER.id),
        )
        val result = StepStatusSupport.withSuccessOutput(declared)

        assertEquals("不应新增任何输出", declared, result)
        assertEquals(1, result.count { it.id == StepStatusSupport.SUCCESS_OUTPUT_ID })
    }

    @Test
    fun `无输出的模块也会拿到状态输出`() {
        val result = StepStatusSupport.withSuccessOutput(emptyList())
        assertEquals(1, result.size)
        assertEquals(StepStatusSupport.SUCCESS_OUTPUT_ID, result.first().id)
    }

    // ---------- withSuccessFlag ----------

    @Test
    fun `成功路径补 success 为 true`() {
        val result = StepStatusSupport.withSuccessFlag(mapOf("count" to 3.0))
        assertEquals(2, result.size)
        assertEquals(VBoolean(true), result[StepStatusSupport.SUCCESS_OUTPUT_ID])
        assertEquals(3.0, result["count"])
    }

    @Test
    fun `不覆盖模块自己给的 success`() {
        // ClickModule 会在点击未命中时返回 success = false —— 那是业务结果，不能被改成 true。
        val outputs = mapOf(StepStatusSupport.SUCCESS_OUTPUT_ID to VBoolean(false))
        val result = StepStatusSupport.withSuccessFlag(outputs)

        assertEquals(outputs, result)
        assertEquals(VBoolean(false), result[StepStatusSupport.SUCCESS_OUTPUT_ID])
    }

    @Test
    fun `无输出的模块成功时也会写 success`() {
        // 约 60 个模块没有任何声明输出；对它们来说「这步成没成」恰恰是最需要的信息。
        val result = StepStatusSupport.withSuccessFlag(emptyMap())
        assertEquals(1, result.size)
        assertEquals(VBoolean(true), result[StepStatusSupport.SUCCESS_OUTPUT_ID])
        assertFalse(result.isEmpty())
    }
}
