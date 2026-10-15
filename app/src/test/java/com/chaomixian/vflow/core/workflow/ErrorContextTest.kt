package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.module.logic.ConditionEvaluator
import com.chaomixian.vflow.core.workflow.module.logic.OP_IS_EMPTY
import com.chaomixian.vflow.core.workflow.module.logic.OP_IS_NOT_EMPTY
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **错误上下文**的测试（fork，P0-5）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.6（决策 D7）。
 *
 * 这里最值钱的两条：
 *  1. **键名是契约**：`[[__error.message]]` 里的 `message` 与 `VDictionary` 的 key 必须逐字一致
 *     （`getProperty` 大小写敏感、不做模糊匹配）—— 改名 = 所有存量工作流**静默取到 VNull**；
 *  2. **「为空/不为空」真的可用**：这条是选字典载体的**全部理由**，
 *     必须真的走一遍 `ConditionEvaluator` 才算数（写进文档的库行为断言先实跑再落笔）。
 */
class ErrorContextTest {

    @Test
    fun `变量引用是命名变量语法`() {
        // `[[ ]]` = 命名变量（查 namedVariables）；`{{ }}` = 魔法变量（查 stepOutputs）。
        // 用错形式会**静默解析成字面量**、不报错 —— 所以这里钉死。
        assertEquals("[[__error]]", ErrorContext.VARIABLE_REFERENCE)
        assertEquals(ErrorContext.KEY, ErrorContext.VARIABLE_NAME)
    }

    @Test
    fun `五个字段的名字与顺序是契约`() {
        assertEquals(
            listOf("step_index", "step_id", "module_id", "title", "message"),
            ErrorContext.FIELDS,
        )
    }

    @Test
    fun `正常路径是空字典`() {
        assertTrue(ErrorContext.EMPTY.raw.isEmpty())
    }

    @Test
    fun `失败上下文带齐五个字段`() {
        val context = ErrorContext.of(
            stepIndex = 3,
            stepId = "step-3",
            moduleId = "vflow.interaction.click",
            errorTitle = "权限不足",
            errorMessage = "没有无障碍权限",
        )

        assertEquals(setOf("step_index", "step_id", "module_id", "title", "message"), context.raw.keys)
        assertEquals(VNumber(3), context.raw["step_index"])
        assertEquals(VString("step-3"), context.raw["step_id"])
        assertEquals(VString("vflow.interaction.click"), context.raw["module_id"])
        assertEquals(VString("权限不足"), context.raw["title"])
        assertEquals(VString("没有无障碍权限"), context.raw["message"])
    }

    @Test
    fun `空字典与失败字典能被为空 不为空区分`() {
        // ⚠️ 实跑 `ConditionEvaluator`：这条判据是选**字典**载体的全部理由
        //（`VNull` 参与「为空」恒 false、字符串载体则无法表达「没有失败」）。
        val failed = ErrorContext.of(1, "a", "m", "t", "msg")

        assertTrue(ConditionEvaluator.evaluateCondition(ErrorContext.EMPTY, OP_IS_EMPTY, null, null))
        assertFalse(ConditionEvaluator.evaluateCondition(ErrorContext.EMPTY, OP_IS_NOT_EMPTY, null, null))
        assertFalse(ConditionEvaluator.evaluateCondition(failed, OP_IS_EMPTY, null, null))
        assertTrue(ConditionEvaluator.evaluateCondition(failed, OP_IS_NOT_EMPTY, null, null))
    }

    @Test
    fun `字典的键能按名字取到`() {
        // `[[__error.message]]` 的解析路径 = `VDictionary.getProperty("message")`。
        val context = ErrorContext.of(2, "s", "m", "参数错误", "缺少必填项")
        assertEquals(VString("缺少必填项"), context.getProperty("message"))
        assertEquals(VNumber(2), context.getProperty("step_index"))
        // 未声明的键 ⇒ VNull（不是异常），与字典的既有契约一致。
        assertEquals(
            com.chaomixian.vflow.core.types.basic.VNull,
            context.getProperty("不存在的键"),
        )
    }

    @Test
    fun `EMPTY 是同一个可共享的实例`() {
        // 它被每个工作流执行注入一次；持有的是不可变 emptyMap ⇒ 共享安全。
        assertTrue(ErrorContext.EMPTY is VDictionary)
        assertTrue(ErrorContext.EMPTY.raw.isEmpty())
    }
}
