package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.execution.ExecutionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 临时工作流失败可见性的纯函数层单测。
 *
 * 设计文档：`docs/fork/agent-debug-failure-visibility.md`。
 *
 * 覆盖三类「改错了不报错、只静默变差」的地方：
 * 1. **SKIP 判据锚点**（用失败行会误报 —— 重试成功后日志里也有 E 行）
 * 2. **摘要必须在截断前抽取**（失败行在末尾，先截断会静默取不到）
 * 3. **状态与正文一起产出**（只抽状态会丢 `Execution log` 段）
 */
class ChatTemporaryWorkflowResultTest {

    // ── 工具：造日志行。格式照 `WorkflowExecutor.appendToLog` ──────────

    /** `[HH:mm:ss.SSS] LEVEL/tag: message` */
    private fun logLine(level: String, tag: String, message: String): String =
        "[14:12:22.657] $level/$tag: $message"

    private fun failureLine(title: String, message: String): String =
        logLine("E", "WorkflowExecutor", "模块执行失败: $title - $message")

    private val skipLine: String =
        logLine("W", "WorkflowExecutor", "根据策略，跳过错误继续执行。")

    private fun finished(log: String) = ExecutionState.Finished("wf", "inst", log)

    private fun failed(log: String, stepIndex: Int = 0) =
        ExecutionState.Failure("wf", "inst", stepIndex, log)

    // ── 1. extractFailureSummary ──────────────────────────────────

    @Test
    fun `extracts the failure detail from a real log line`() {
        // 真机截图里的真实内容（`.mindfs/upload/2026-10-02/`）
        val log = listOf(
            logLine("D", "WorkflowExecutor", "开始执行主工作流: 正则写法穷举"),
            logLine("D", "WorkflowExecutor", "[进度] Xposed JavaScript: 正在系统进程中执行 JavaScript..."),
            failureLine("该功能执行失败", "系统进程执行该功能时报错。详情见应用日志；若反复出现请报告该问题。"),
            "脚本错误（第 3 行第 0 列）：正则表达式不可用。",
        ).joinToString("\n")

        assertEquals(
            "该功能执行失败 - 系统进程执行该功能时报错。详情见应用日志；若反复出现请报告该问题。",
            extractFailureSummary(log),
        )
    }

    @Test
    fun `takes the last failure when several steps failed`() {
        val log = listOf(
            failureLine("第一个", "原因一"),
            failureLine("第二个", "原因二"),
        ).joinToString("\n")

        assertEquals("第二个 - 原因二", extractFailureSummary(log))
    }

    @Test
    fun `returns null when there is no failure line`() {
        val log = logLine("D", "WorkflowExecutor", "执行完毕")
        assertNull(extractFailureSummary(log))
    }

    @Test
    fun `does not match a failure line without the E-level tag`() {
        // 反向断言：只有「模块执行失败」四个字、但没有 E/WorkflowExecutor: 前缀时不能匹配。
        // 防的是「错误消息正文里恰好含这四个字」导致的误匹配。
        val log = logLine("D", "SomeModule", "模块执行失败: 这不是真的失败行")
        assertNull(extractFailureSummary(log))
    }

    @Test
    fun `returns null for blank input`() {
        assertNull(extractFailureSummary(""))
        assertNull(extractFailureSummary("\n\n   \n"))
    }

    @Test
    fun `does not match the prefix when it appears mid-line`() {
        // 堆栈回溯里可能印出含该前缀的字符串 ⇒ 必须按行首匹配，不能 substringAfterLast
        val log = logLine("E", "WorkflowExecutor", "某异常") +
            "\n\tat Foo.bar(E/WorkflowExecutor: 模块执行失败: 骗你的)"
        assertNull(extractFailureSummary(log))
    }

    // ── 2. hasSkippedFailure（本判据存在的理由）─────────────────────

    @Test
    fun `detects the skip line`() {
        assertTrue(hasSkippedFailure(listOf(skipLine).joinToString("\n")))
    }

    @Test
    fun `a plain STOP failure does not count as skipped`() {
        // ⚠️ 反向断言：STOP 场景有 E/模块执行失败 但**没有** SKIP 行。
        // 若判据误用失败行，这条会红。
        val log = failureLine("出错", "原因")
        assertFalse(hasSkippedFailure(log))
    }

    @Test
    fun `retry-exhausted exceptions without a skip line do not count as skipped`() {
        // ⚠️⚠️ 反向断言（本判据的核心动机）：
        // RETRY 策略下每次尝试抛异常都会打 `E/模块执行异常`；若最终仍失败但**没走**
        // SKIP 分支，就不该被当成「跳过」。用「含 E 行」做判据时这里必然误报。
        val log = buildString {
            repeat(3) { attempt ->
                append(logLine("E", "WorkflowExecutor", "模块执行异常: 某模块"))
                append("\n")
                append("\tjava.lang.RuntimeException: 第 ${attempt + 1} 次尝试失败")
                append("\n")
            }
            append(failureLine("某模块", "重试次数已用尽"))
        }
        assertFalse("重试耗尽 ≠ 有步骤被跳过", hasSkippedFailure(log))
    }

    @Test
    fun `returns false for blank input`() {
        assertFalse(hasSkippedFailure(""))
    }

    // ── 3. temporaryWorkflowStatus ────────────────────────────────

    @Test
    fun `a finished run without skipped failures is a success`() {
        assertEquals(
            ChatToolResultStatus.SUCCESS,
            temporaryWorkflowStatus(finished(logLine("D", "WorkflowExecutor", "执行完毕"))),
        )
    }

    @Test
    fun `a finished run with a skipped failure is an error`() {
        // ⚠️ 这是本次改造修掉的核心缺陷：`Finished` 只说明「跑完了」。
        val log = listOf(failureLine("某模块", "出错"), skipLine).joinToString("\n")
        assertEquals(ChatToolResultStatus.ERROR, temporaryWorkflowStatus(finished(log)))
    }

    @Test
    fun `failure and cancellation are errors`() {
        assertEquals(ChatToolResultStatus.ERROR, temporaryWorkflowStatus(failed("")))
        assertEquals(
            ChatToolResultStatus.ERROR,
            temporaryWorkflowStatus(ExecutionState.Cancelled("wf", "inst", "")),
        )
        assertEquals(
            ChatToolResultStatus.ERROR,
            temporaryWorkflowStatus(ExecutionState.Running("wf", "inst", 0)),
        )
    }

    // ── 4. buildTemporaryWorkflowOutputText ───────────────────────

    @Test
    fun `failure summary appears before the step list`() {
        val text = buildTemporaryWorkflowOutputText(
            workflowName = "正则写法穷举",
            stepDescriptions = listOf("Xposed JavaScript (vflow.xposed.js)"),
            terminalState = failed(failureLine("出错", "原因")),
            detailedLog = failureLine("出错", "原因"),
        )
        val summaryIndex = text.indexOf("出错 - 原因")
        val stepsIndex = text.indexOf("Steps:")
        assertTrue("摘要必须出现", summaryIndex >= 0)
        assertTrue("摘要必须在步骤清单之前（卡片默认只显示前 6 行）", summaryIndex < stepsIndex)
    }

    @Test
    fun `failure summary survives a log longer than the truncation limit`() {
        // ⚠️ 核心回归：失败行写在执行【末尾】，而 truncateMultiline 取的是【开头】4000 字符。
        // 若摘要是从截断后的文本里提取，这里会静默取不到。
        val padding = (1..400).joinToString("\n") {
            logLine("D", "WorkflowExecutor", "填充行 $it —— 用于把失败行推到 4000 字符之后")
        }
        val log = padding + "\n" + failureLine("末尾的失败", "真原因")

        assertTrue("前置条件：日志确实超过 4000 字符", log.length > 4_000)
        assertNotNull("摘要必须从【未截断】的日志里抽取", extractFailureSummary(log))

        val text = buildTemporaryWorkflowOutputText(
            workflowName = "长工作流",
            stepDescriptions = listOf("A (m)"),
            terminalState = failed(log),
            detailedLog = log,
        )
        assertTrue("长日志下摘要仍应出现在卡片正文里", text.contains("末尾的失败 - 真原因"))
    }

    @Test
    fun `skipped failure rewrites the headline and keeps the log section`() {
        val log = listOf(failureLine("某模块", "出错"), skipLine).joinToString("\n")
        val text = buildTemporaryWorkflowOutputText(
            workflowName = "有跳过的流程",
            stepDescriptions = listOf("某模块 (vflow.x)"),
            terminalState = finished(log),
            detailedLog = log,
        )
        assertTrue("首行必须说明「完成但有步骤失败被跳过」", text.contains("failed and were skipped"))
        assertFalse("不能再宣称成功", text.contains("completed successfully"))
        assertTrue("必须保留 Execution log 段（排错全靠它）", text.contains("Execution log:"))
        assertTrue("日志段里要有原始失败行", text.contains("模块执行失败: 某模块 - 出错"))
    }

    @Test
    fun `a clean finished run is unchanged`() {
        val text = buildTemporaryWorkflowOutputText(
            workflowName = "一切正常",
            stepDescriptions = listOf("A (m)"),
            terminalState = finished(logLine("D", "WorkflowExecutor", "执行完毕")),
            detailedLog = logLine("D", "WorkflowExecutor", "执行完毕"),
        )
        assertTrue(text.startsWith("Temporary workflow `一切正常` completed successfully."))
        assertTrue(text.contains("Execution log:"))
    }

    @Test
    fun `a failure without a parseable summary degrades without crashing`() {
        val text = buildTemporaryWorkflowOutputText(
            workflowName = "无摘要",
            stepDescriptions = listOf("A (m)"),
            terminalState = failed("没有可解析的失败行"),
            detailedLog = "没有可解析的失败行",
        )
        assertTrue(text.contains("failed at step 1"))
        assertTrue("仍要有步骤清单", text.contains("Steps:"))
    }

    @Test
    fun `step list is capped`() {
        val steps = (1..35).map { "步骤 $it (vflow.x)" }
        val text = buildTemporaryWorkflowOutputText(
            workflowName = "长清单",
            stepDescriptions = steps,
            terminalState = finished(""),
            detailedLog = "",
            maxSteps = 30,
        )
        assertTrue("应截断并给出剩余数量", text.contains("... 5 more steps"))
    }

    @Test
    fun `blank log omits the log section`() {
        val text = buildTemporaryWorkflowOutputText(
            workflowName = "空日志",
            stepDescriptions = listOf("A (m)"),
            terminalState = finished(""),
            detailedLog = "",
        )
        assertFalse(text.contains("Execution log:"))
    }
}
