package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.execution.LoopState
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.ActionStepExecutionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Stack

/**
 * **跳转目标纯函数层**的测试（fork，P0-5 跳过跳转）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.4 / §2.5。
 *
 * 为什么必须有：这块的失效方式**全是静默的** ——
 *  - 目标解析错 ⇒ 跳到无关步骤（工作流"跑完了"，但做错了事）；
 *  - 向内跳没挡住 ⇒ `Loop` 的 End 抛 `EmptyStackException`（被执行器 catch 成
 *    「模块执行异常」，**文案零信息量**）、`ForEach` 的 End 直接失败；
 *  - `loopStack` 没对齐 ⇒ 后续步骤读到**过期的循环变量**（既有 `vflow.logic.jump` 的缺陷 B3）；
 *  - 环检测漏报 ⇒ 用户配出死循环，只能等运行期上限才发现。
 *
 * 本仓库无 Robolectric，`WorkflowExecutor` 起不来 ⇒ 逻辑抽成纯函数 + 可注入的
 * `ShapeResolver`（生产读 `ModuleRegistry`，这里读假表），在 JVM 里逐个钉住。
 */
class JumpTargetSupportTest {

    private val PLAIN = "vflow.device.delay"
    private val LOOP_START = "vflow.logic.loop.start"
    private val LOOP_END = "vflow.logic.loop.end"
    private val IF_MIDDLE = "vflow.logic.else"

    /** 假块结构：两个循环端点 + 一个 If 的中间分支（Else），其余都是普通步骤。 */
    private val shapes = JumpTargetSupport.ShapeResolver { step ->
        when (step.moduleId) {
            LOOP_START -> JumpTargetSupport.BlockShape(
                JumpTargetSupport.BlockRole.START, "loop", isLoop = true
            )
            LOOP_END -> JumpTargetSupport.BlockShape(
                JumpTargetSupport.BlockRole.END, "loop", isLoop = true
            )
            IF_MIDDLE -> JumpTargetSupport.BlockShape(
                JumpTargetSupport.BlockRole.MIDDLE, "if", isLoop = false
            )
            else -> JumpTargetSupport.BlockShape(
                JumpTargetSupport.BlockRole.NONE, null, isLoop = false
            )
        }
    }

    private fun step(
        id: String,
        indent: Int = 0,
        moduleId: String = PLAIN,
        jumpTarget: String? = null,
    ) = ActionStep(
        moduleId = moduleId,
        parameters = if (jumpTarget == null) {
            emptyMap()
        } else {
            mapOf(ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET to jumpTarget)
        },
        indentationLevel = indent,
        id = id,
    )

    // ── 目标解析（决策 D4：存 id 不存序号）────────────────────────

    @Test
    fun `按 id 反查下标`() {
        val steps = listOf(step("a"), step("b"), step("c"))
        assertEquals(2, JumpTargetSupport.resolveTargetIndex(steps, "c"))
        assertEquals(0, JumpTargetSupport.resolveTargetIndex(steps, "a"))
    }

    @Test
    fun `目标不存在或为空时返回 -1`() {
        // 「目标被删」是 id 方案**唯一**的代价 —— 必须是**可检测**的，不能崩、也不能瞎跳。
        val steps = listOf(step("a"))
        assertEquals(-1, JumpTargetSupport.resolveTargetIndex(steps, "被删掉的步骤"))
        assertEquals(-1, JumpTargetSupport.resolveTargetIndex(steps, null))
        assertEquals(-1, JumpTargetSupport.resolveTargetIndex(steps, "   "))
    }

    @Test
    fun `空白的跳转目标键当成没设置`() {
        assertEquals("t", JumpTargetSupport.targetIdOf(step("a", jumpTarget = " t ")))
        assertNull(JumpTargetSupport.targetIdOf(step("a", jumpTarget = "   ")))
        assertNull(JumpTargetSupport.targetIdOf(step("a")))
    }

    // ── 目标合法性（§2.5：允许任意方向，只禁止向内跳）──────────────

    @Test
    fun `向前跳和向后跳都允许`() {
        val steps = listOf(step("a", 0), step("b", 0), step("c", 0))
        assertTrue(
            "向后跳（失败后回到前面的步骤重做）必须允许 —— 用户明确要求过这类场景。",
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 0, currentIndentationLevel = 0, shapes = shapes),
        )
        assertTrue(
            "向前跳（失败后落到收尾步骤）必须允许。",
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 2, currentIndentationLevel = 0, shapes = shapes),
        )
    }

    @Test
    fun `禁止向内跳（目标缩进更深）`() {
        // 跳到 Loop 体内 ⇒ End Loop 的 `loopStack.peek()` 在空栈上抛 `EmptyStackException`
        //   （`java.util.Stack.peek()` 空栈**抛异常、不返回 null** ⇒ 被执行器 catch 成
        //   `Failure("模块执行异常", …)`，文案零信息量）；
        // 跳到 ForEach 体内 ⇒ End ForEach 直接 Failure。必须在入口挡住。
        val steps = listOf(step("outer", 0), step("inner", 1))
        assertFalse(
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 1, currentIndentationLevel = 0, shapes = shapes),
        )
        assertTrue(
            "同级（缩进相等）不算向内跳。",
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 1, currentIndentationLevel = 1, shapes = shapes),
        )
        assertTrue(
            "从深处往外跳永远合法。",
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 0, currentIndentationLevel = 2, shapes = shapes),
        )
    }

    @Test
    fun `块中间与结束模块不能当目标`() {
        val steps = listOf(step("a", 0), step("else", 0, moduleId = IF_MIDDLE), step("end", 0, moduleId = LOOP_END))
        assertFalse(
            "跳到 If 的中间分支 ⇒ 分支配对失配，行为未定义。",
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 1, currentIndentationLevel = 0, shapes = shapes),
        )
        assertFalse(
            "跳到 End Loop ⇒ 循环栈与迭代计数错乱。",
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 2, currentIndentationLevel = 0, shapes = shapes),
        )
    }

    @Test
    fun `块开始模块可以当目标`() {
        // 回到循环开头重跑是合法诉求（缩进满足时）；只有 MIDDLE / END 才破坏块配对。
        val steps = listOf(step("loop", 0, moduleId = LOOP_START), step("body", 1))
        assertTrue(
            JumpTargetSupport.isAllowedTarget(steps, targetIndex = 0, currentIndentationLevel = 0, shapes = shapes),
        )
    }

    @Test
    fun `越界目标不合法`() {
        val steps = listOf(step("a"))
        assertFalse(JumpTargetSupport.isAllowedTarget(steps, targetIndex = -1, currentIndentationLevel = 0, shapes = shapes))
        assertFalse(JumpTargetSupport.isAllowedTarget(steps, targetIndex = 1, currentIndentationLevel = 0, shapes = shapes))
    }

    // ── 循环嵌套深度（loopStack 对齐的判据）───────────────────────

    /** 两层嵌套：`L1( A, L2( B ), C ) D`。 */
    private fun nestedLoops(): List<ActionStep> = listOf(
        step("L1", 0, moduleId = LOOP_START),
        step("A", 1),
        step("L2", 1, moduleId = LOOP_START),
        step("B", 2),
        step("L2end", 1, moduleId = LOOP_END),
        step("C", 1),
        step("L1end", 0, moduleId = LOOP_END),
        step("D", 0),
    )

    @Test
    fun `数出目标位置被几个循环包着`() {
        val steps = nestedLoops()
        assertEquals("循环开始步骤本身**还没进循环**（状态是它执行时才压栈的）", 0, count(steps, 0))
        assertEquals("L1 体内", 1, count(steps, 1))
        assertEquals("L2 开头：只被 L1 包着", 1, count(steps, 2))
        assertEquals("L2 体内：被 L1、L2 包着", 2, count(steps, 3))
        assertEquals("L2 结束处：L2 还没弹栈 ⇒ 仍是 2", 2, count(steps, 4))
        assertEquals("L2 之后、L1 之内", 1, count(steps, 5))
        assertEquals("L1 结束处：L1 还没弹栈 ⇒ 1", 1, count(steps, 6))
        assertEquals("全在循环外", 0, count(steps, 7))
    }

    private fun count(steps: List<ActionStep>, position: Int): Int =
        JumpTargetSupport.enclosingLoopCount(steps, position, shapes)

    // ── loopStack 对齐（修既有缺陷 B3）───────────────────────────

    @Test
    fun `向外跳会把多余的循环状态弹掉`() {
        val steps = nestedLoops()
        val stack = Stack<LoopState>().apply {
            push(LoopState.CountLoopState(3))
            push(LoopState.ForEachLoopState(emptyList()))
        }
        JumpTargetSupport.alignLoopStack(stack, steps, targetIndex = 7, shapes = shapes)
        assertEquals("跳到循环外 ⇒ 两层状态都要弹掉，否则后续步骤读到过期的循环变量", 0, stack.size)
    }

    @Test
    fun `跳到外层循环体内只弹内层`() {
        val steps = nestedLoops()
        val stack = Stack<LoopState>().apply {
            push(LoopState.CountLoopState(3))
            push(LoopState.ForEachLoopState(emptyList()))
        }
        JumpTargetSupport.alignLoopStack(stack, steps, targetIndex = 1, shapes = shapes)
        assertEquals(1, stack.size)
    }

    @Test
    fun `跳到自己的循环开头会先弹掉旧状态（避免残留）`() {
        // 从 L1 体内跳回 L1 开头：L1 的旧状态必须弹掉，否则 L1 执行时会**再压一份**，
        // 循环结束后栈里留一个 stale 条目（后续步骤会读到过期的 loop_index）。
        val steps = nestedLoops()
        val stack = Stack<LoopState>().apply { push(LoopState.CountLoopState(3)) }
        JumpTargetSupport.alignLoopStack(stack, steps, targetIndex = 0, shapes = shapes)
        assertEquals(0, stack.size)
    }

    @Test
    fun `目标比当前更深时不会凭空造状态`() {
        // 向内跳已被 isAllowedTarget 挡住；万一缩进信息不可靠，这里**只能什么都不做**
        //（凭空 push 一个 LoopState 会让 End Loop 的迭代计数错乱）。
        val steps = nestedLoops()
        val stack = Stack<LoopState>()
        JumpTargetSupport.alignLoopStack(stack, steps, targetIndex = 3, shapes = shapes)
        assertEquals(0, stack.size)
    }

    // ── 静态环检测（第三层防护）──────────────────────────────────

    @Test
    fun `A 跳 B、B 跳 A 能报出环`() {
        val steps = listOf(
            step("a", jumpTarget = "b"),
            step("b", jumpTarget = "a"),
        )
        val cycles = JumpTargetSupport.detectCycles(steps)
        assertEquals(1, cycles.size)
        assertEquals(setOf("a", "b"), cycles.single().toSet())
    }

    @Test
    fun `自己跳自己能报出环`() {
        val cycles = JumpTargetSupport.detectCycles(listOf(step("a", jumpTarget = "a")))
        assertEquals(listOf(listOf("a")), cycles)
    }

    @Test
    fun `链式跳转没有环时不报`() {
        val steps = listOf(
            step("a", jumpTarget = "b"),
            step("b", jumpTarget = "c"),
            step("c"),
        )
        assertTrue(JumpTargetSupport.detectCycles(steps).isEmpty())
    }

    @Test
    fun `目标不存在时不参与建图`() {
        // 悬空目标在运行期会降级为「继续下一步」⇒ 不可能成环，不该报警（否则是噪音）。
        val steps = listOf(step("a", jumpTarget = "不存在"), step("b"))
        assertTrue(JumpTargetSupport.detectCycles(steps).isEmpty())
    }

    @Test
    fun `长环也能报出来`() {
        val steps = listOf(
            step("a", jumpTarget = "b"),
            step("b", jumpTarget = "c"),
            step("c", jumpTarget = "a"),
        )
        val cycles = JumpTargetSupport.detectCycles(steps)
        assertEquals(1, cycles.size)
        assertEquals(setOf("a", "b", "c"), cycles.single().toSet())
    }
}
