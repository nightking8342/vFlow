package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「跳转到步骤」模块的测试（fork 扩充）。
 *
 * ⚠️ `execute` 起不来（要 Android `Context`）⇒ 它的**运行期校验**只能靠源码扫描型测试锁
 * （见 `test/core/execution/JumpCapsWiringTest.kt`）；这里覆盖纯函数 `validate`。
 */
class JumpModuleTest {

    @Test
    fun validate_acceptsNamedVariableReferenceForTargetStepIndex() {
        val module = JumpModule()

        val result = module.validate(
            ActionStep(
                moduleId = module.id,
                parameters = mapOf("target_step_index" to "[[targetStep]]")
            ),
            emptyList()
        )

        assertTrue(result.isValid)
    }

    @Test
    fun inputs_allowNamedVariablesForTargetStepIndex() {
        val module = JumpModule()

        val input = module.getInputs().first { it.id == "target_step_index" }

        assertTrue(input.acceptsNamedVariable)
        assertEquals(setOf(VTypeRegistry.NUMBER.id), input.acceptedMagicVariableTypes)
    }

    // ─────────────── B2 修复：向内跳必须在保存时挡住（fork，2026-10-15）───────────────

    private fun jumpStep(target: Number, indentation: Int = 0) = ActionStep(
        moduleId = "vflow.logic.jump",
        parameters = mapOf("target_step_index" to target),
        indentationLevel = indentation,
        id = "jump",
    )

    private fun plainStep(id: String, indentation: Int) = ActionStep(
        moduleId = "vflow.device.delay",
        parameters = emptyMap(),
        indentationLevel = indentation,
        id = id,
    )

    @Test
    fun validate_rejectsJumpIntoBlockBody() {
        // #1 循环开始（缩进 0）、#2 循环体内（缩进 1）、#3 这个 jump（缩进 0）跳到 #2。
        // 症状随块类型不同（Loop 的 End 抛 EmptyStackException、ForEach 的 End 直接失败），
        // 所以要在**保存时**就挡住，别等运行期。
        val jump = jumpStep(target = 2)
        val steps = listOf(plainStep("loop", 0), plainStep("body", 1), jump)

        val result = JumpModule().validate(jump, steps)

        assertFalse("跳到块体内部会打乱块结构，必须拒绝", result.isValid)
    }

    @Test
    fun validate_acceptsJumpWithinSameIndentation() {
        // 同级跳转（含向后跳回开头重跑）是合法诉求，不能误伤
        val jump = jumpStep(target = 1)
        val steps = listOf(plainStep("a", 0), plainStep("b", 0), jump)

        assertTrue(JumpModule().validate(jump, steps).isValid)
    }

    @Test
    fun validate_acceptsJumpOutOfBlock() {
        // 从循环体内（缩进 1）跳到块外（缩进 0）是「跳出循环」的正常写法
        val jump = jumpStep(target = 1, indentation = 1)
        val steps = listOf(plainStep("outside", 0), plainStep("loop", 0), jump)

        assertTrue(JumpModule().validate(jump, steps).isValid)
    }

    @Test
    fun validate_skipsBlockCheckWhenStepListIsMissing() {
        // 调用方没给步骤列表（allSteps 为空）⇒ 无法判断块结构，跳过该检查，
        // 行为与改动前一致（只有运行期兜底）。
        assertTrue(JumpModule().validate(jumpStep(target = 2), emptyList()).isValid)
    }

    @Test
    fun validate_stillRejectsNonIntegerAndOutOfRangeLowerBound() {
        // 回归：新增的块结构校验不能把原有校验挤掉
        assertFalse(JumpModule().validate(jumpStep(target = 0), listOf(plainStep("a", 0))).isValid)
        assertFalse(
            JumpModule().validate(
                ActionStep(
                    moduleId = "vflow.logic.jump",
                    parameters = mapOf("target_step_index" to "abc"),
                    id = "jump",
                ),
                listOf(plainStep("a", 0)),
            ).isValid,
        )
    }
}
