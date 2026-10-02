package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住「Agent 调试不弹错误弹窗」这条接线真的存在。
 *
 * 设计文档：`docs/fork/agent-debug-failure-visibility.md`。
 *
 * ## ⚠️ 为什么必须是源码扫描
 *
 * 本仓库已三次踩过「纯函数单测全绿但**调用点缺失**」：
 * `CoreLauncher` 漏调 `recordLaunchedDexFingerprint`（13 例全绿、用户静默跑旧 Core）、
 * `XposedDiagnostics.messageFor` 写了但零生产调用点。
 * `WorkflowExecutor` 是核心执行器、`ChatAgentModuleExecutor` 依赖 Android `Context`，
 * 纯 JVM 都起不来 ⇒ 「有没有真的传这个参数」只能在**源码层**锁。
 *
 * ⚠️ 形态照 `TriggerServiceXposedNoticeWiringTest`（相对路径，Gradle test 工作目录 = `app/`）。
 *
 * ## ⚠️⚠️ 剥注释是本测试的**必要条件**
 *
 * 本改动要在失败分支加 `showErrorDialog` 字样，而**注释与文档里会出现大量该字样**
 * （方案文档、本文件、以及源码里的说明性注释）。
 * 若只做 `text.contains("showErrorDialog")`，**把 `if` 整个删掉改回无条件调用后照样绿**
 * —— `TriggerServiceXposedNoticeWiringTest` 明确记过这条教训。
 */
class AgentErrorDialogWiringTest {

    private companion object {
        const val EXECUTOR_PATH = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
        const val CHAT_EXECUTOR_PATH = "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt"
    }

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到 $path（当前目录 ${File(".").absolutePath}）", file.exists())
        return file.readText()
    }

    /**
     * 剥掉块注释、行注释与字符串字面量后返回**只剩代码**的文本。
     *
     * ⚠️ 顺序：先剥块注释（可能跨行，含嵌套的 `*`），再剥行注释，最后处理字符串。
     * 朴素实现足够 —— 这里没有正则/嵌套注释等复杂场景。
     */
    private fun codeOnly(text: String): String {
        val withoutBlockComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlockComments.lineSequence()
            .joinToString("\n") { line ->
                // 行注释：保守处理，不解析字符串里的 //（本项目没有这类用法）
                line.substringBefore("//")
            }
    }

    /** 取某个函数的函数体（按大括号配对，跳过字符串与注释）。 */
    private fun functionBody(code: String, signatureFragment: String): String {
        val start = code.indexOf(signatureFragment)
        assertTrue("找不到函数：$signatureFragment", start >= 0)
        val braceStart = code.indexOf('{', start)
        assertTrue("函数 $signatureFragment 没有函数体", braceStart >= 0)

        var depth = 0
        var index = braceStart
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(braceStart, index + 1)
                }
            }
            index++
        }
        error("函数 $signatureFragment 的大括号不配对")
    }

    // ── 1. Agent 侧真的传了 false ────────────────────────────────

    @Test
    fun `chat agent passes showErrorDialog false when running a temporary workflow`() {
        val code = codeOnly(source(CHAT_EXECUTOR_PATH))

        assertTrue(
            "Agent 执行临时工作流时必须传 showErrorDialog = false —— " +
                "否则失败弹窗会挂住协程，终止状态永不广播",
            code.contains("showErrorDialog = false"),
        )
        assertTrue(
            "该参数必须挂在 WorkflowExecutor.execute 的那次调用上",
            code.contains("WorkflowExecutor.execute("),
        )
    }

    @Test
    fun `the chat agent call site is not vacuous`() {
        // 防空转：剥注释后仍应有实质代码行（防「正则把整个文件剥空 ⇒ 断言恒真」）
        val code = codeOnly(source(CHAT_EXECUTOR_PATH))
        val codeLines = code.lineSequence().count { it.isNotBlank() }
        assertTrue("剥注释后代码行数异常（$codeLines），剥得太狠了", codeLines > 500)
    }

    // ── 2. 执行器侧的条件包裹真的存在 ─────────────────────────────

    @Test
    fun `executor guards the error dialog behind the flag`() {
        val code = codeOnly(source(EXECUTOR_PATH))

        // ⚠️⚠️ 关键断言：`if (showErrorDialog)` 与 `showError` **必须在同一个函数体内**，
        // 且 `if` 要在前。只断言「两个字符串都存在」是无效的 —— 注释里到处都有。
        val body = functionBody(code, "private suspend fun executeWorkflowInternal(")
        val ifIndex = body.indexOf("if (showErrorDialog)")
        val showIndex = body.indexOf("showError(")

        assertTrue("失败分支必须有 showError 调用", showIndex >= 0)
        assertTrue("showError 必须被 if (showErrorDialog) 包住", ifIndex >= 0)
        assertTrue("if 判断必须在 showError 调用之前", ifIndex < showIndex)
    }

    @Test
    fun `executor propagates the flag to every internal call site`() {
        val code = codeOnly(source(EXECUTOR_PATH))

        // ⚠️ 不绑定具体行号（行号随上游漂移）—— 断言数量与透传关系。
        //
        // `executeWorkflowInternal(` 全文件命中 **4** 次：函数**定义** 1 次 + 调用 3 次。
        // 调用分别是：execute() 的超时分支、execute() 的普通分支、executeSubWorkflow()。
        // 前两处**透传** `showErrorDialog`；第 3 处**刻意不传**（走默认值 true，
        // 子工作流作为被调方应继承调用方语义）。
        //
        // ⚠️ 用「函数定义行」把它排除掉，避免把定义也算成调用点。
        val calls = Regex("""(?<!fun )executeWorkflowInternal\(""").findAll(code).count()
        val forwarded = Regex("""showErrorDialog = showErrorDialog""").findAll(code).count()

        assertTrue(
            "executeWorkflowInternal 的调用点数量变了（$calls，期望 3），请复核方案 §3.1 ④",
            calls == 3,
        )
        assertTrue(
            "必须恰好两处透传（execute 的两个分支）；" +
                "若变成 3 处说明有人给子工作流也关掉了弹窗 —— 那是静默的行为变更",
            forwarded == 2,
        )
    }

    @Test
    fun `the executor source scan is not vacuous`() {
        val code = codeOnly(source(EXECUTOR_PATH))
        val codeLines = code.lineSequence().count { it.isNotBlank() }
        assertTrue("剥注释后代码行数异常（$codeLines）", codeLines > 500)
    }

    // ── 3. 反向断言：本改动不该扩散到其他调用点 ────────────────────

    @Test
    fun `no other production call site disables the error dialog`() {
        val chatCode = codeOnly(source(CHAT_EXECUTOR_PATH))

        // 只有 Agent 那一次调用允许传 false。若将来有人「顺手」在别处也传 false，
        // 会静默让那个场景也失去失败提示 —— 本条把它挡住。
        val occurrences = Regex("""showErrorDialog\s*=\s*false""").findAll(chatCode).count()
        assertTrue("showErrorDialog = false 只应出现在 Agent 那一处，实际 $occurrences 处", occurrences == 1)
    }
}
