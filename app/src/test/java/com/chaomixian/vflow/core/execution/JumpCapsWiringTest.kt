package com.chaomixian.vflow.core.execution

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`vflow.logic.jump` 的跳转计数与循环栈对齐的接线锚定**（源码扫描型，fork B3 / B4 修复）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §6 B3 / B4。
 *
 * ## 为什么必须是源码扫描
 *
 * `WorkflowExecutor` 依赖 Android `Context`，纯 JVM 起不来；而这次改动最危险的失效方式恰好是
 * **「保护加到了错误的范围上」** —— `ExecutionSignal.Jump` 被 `If` / `Switch` / `Loop` /
 * `ForEach` / `While` / `DoWhile` / UI 块模块**共用**，对整条信号无条件计数或对齐循环栈会：
 *   - 把正常循环在第 10 次迭代**掐死**（计数）；
 *   - 在「块内跳到块外」时弹掉外层循环状态 ⇒ 该循环的 End `peek()` 空栈 ⇒ `EmptyStackException`。
 *
 * 这两件事都不会报编译错、也不会被任何单测发现 ⇒ 只能在源码层把**收窄判据**钉住。
 */
class JumpCapsWiringTest {

    private val executor = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"

    @Test
    fun `跳转分支必须先按模块 id 收窄再计数与对齐`() {
        val source = SourceScan.stripped(executor)
        val body = SourceScan.functionBody(source, "private suspend fun executeWorkflowInternal(")
            ?: error("没能截到 executeWorkflowInternal 的函数体，扫描失效")

        val branchStart = body.indexOf("is ExecutionSignal.Jump ->")
        assertTrue("找不到 Jump 信号分支", branchStart >= 0)

        val guard = body.indexOf(
            "if (step.moduleId != JumpTargetSupport.JUMP_MODULE_ID)",
            branchStart,
        )
        assertTrue(
            "Jump 分支必须按模块 id 收窄 —— 否则块结构跳转（If/Switch/Loop/ForEach/UI 块）" +
                "也会被计数与对齐，正常循环会被掐死或抛 EmptyStackException",
            guard > branchStart,
        )

        val budget = body.indexOf("MAX_JUMPS_PER_EXECUTION", branchStart)
        val align = body.indexOf("alignLoopStack(loopStack, workflow.steps, signal.pc)", branchStart)
        assertTrue("Jump 分支必须有执行级总预算（B4）", budget > branchStart)
        assertTrue("Jump 分支必须对齐循环栈（B3）", align > branchStart)
        assertTrue("收窄判据必须在计数之前（否则收窄形同虚设）", guard < budget)
        assertTrue("收窄判据必须在循环栈对齐之前", guard < align)

        // ⚠️ **反向断言**：`vflow.logic.jump` **不能**套单步上限。
        // 它是**控制流原语**（goto），用户可以用它手写循环（「跳回第 2 步直到成功」）；
        // 套单步上限会在第 11 次**静默改变存量工作流的行为**（循环提前结束、结果不完整，
        // 只有一条 W 日志）。终止性由执行级总预算保证即可（任何环最多 200 跳 ⇒ 响亮地终止）。
        assertEquals(
            "单步上限只应出现在 P0-5 的失败跳转路径上；出现在 Jump 分支 = 会给存量工作流" +
                "的 jump 循环引入静默截断",
            -1,
            body.indexOf("MAX_JUMPS_PER_STEP", branchStart),
        )
    }

    @Test
    fun `两条跳转路径共用同一套收尾与循环栈对齐`() {
        val source = SourceScan.stripped(executor)

        // 单步上限**只**在 P0-5 的失败跳转路径（比较 + 日志插值 = 2 处）
        assertEquals(
            "单步上限应只被 P0-5 的失败跳转路径引用（比较 + 日志）",
            2,
            SourceScan.countOccurrences(source, "JumpTargetSupport.MAX_JUMPS_PER_STEP"),
        )
        // 执行级总预算两条路径都用（新路径 3 处 + 老路径 2 处）
        assertTrue(
            "执行级总预算必须被两条路径共用",
            SourceScan.countOccurrences(source, "JumpTargetSupport.MAX_JUMPS_PER_EXECUTION") >= 4,
        )
        assertTrue(
            "循环栈对齐应被两条路径各调用一次",
            SourceScan.countOccurrences(source, "alignLoopStack(") >= 2,
        )
    }

    @Test
    fun `失败终止的收尾只有一份实现`() {
        val source = SourceScan.stripped(executor)

        // 1 处定义 + 2 处调用（失败分支 / 跳转总预算用尽）
        assertEquals(
            "terminateWithFailure 应恰好有 3 次出现（1 定义 + 2 调用）；" +
                "少于 3 说明有调用点又写回了内联副本，多于 3 说明被别处误用",
            3,
            SourceScan.countOccurrences(source, "terminateWithFailure("),
        )
        assertEquals(
            "终态广播必须只在 terminateWithFailure 里发生一次 —— " +
                "两处各写一份时，改「注销 / 广播」语义极易只改一处（而那是首页日志与 Chat 卡片的状态来源）",
            1,
            SourceScan.countOccurrences(source, "ExecutionState.Failure("),
        )
    }

    @Test
    fun `跳转模块的运行期校验真的接上了`() {
        // `JumpModule.execute` 要 Android Context，起不来 ⇒ 在源码层锁接线
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/workflow/module/logic/JumpModule.kt"
        )

        assertTrue(
            "运行期必须兜底校验向内跳（validate 只覆盖「编辑器保存静态数字」那一条路，",
            SourceScan.countOccurrences(source, "JumpTargetSupport.isAllowedTarget(") >= 2,
        )
        assertTrue(
            "校验失败必须给出可读的失败原因，而不是静默跳过去",
            source.contains("\"无效的跳转目标\""),
        )
    }
}
