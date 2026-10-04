package com.chaomixian.vflow.core.workflow.module.data

import android.content.ContextWrapper
import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionLogLevel
import com.chaomixian.vflow.core.execution.ExecutionServices
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Stack

/**
 * `vflow.data.log` 的**声明体检** + 裸步骤 id 分派的行为测试。
 *
 * 设计文档：`docs/fork/log-module-design.md`。
 * 形态照 `ActivityChangedTriggerModuleTest`（声明体检）+ `CoreDexFingerprintTest`（源码扫描）。
 */
class LogModuleTest {

    private val module = LogModule()

    // ── 声明体检 ────────────────────────────────────────────────

    @Test
    fun `declares the stable id and the data category`() {
        assertEquals("vflow.data.log", module.id)
        assertEquals("data", module.metadata.getResolvedCategoryId())
    }

    @Test
    fun `has no permissions`() {
        assertTrue(
            "本模块只写日志，不该要任何权限",
            module.getRequiredPermissions(null).isEmpty(),
        )
    }

    @Test
    fun `is low risk so it does not raise the whole workflow`() {
        // ⚠️⚠️ 反向锁：`riskLevelForSavedWorkflow` 取工作流内所有步骤的 **max**
        // ⇒ 声明 HIGH 会让任何含本模块的工作流被抬到 high risk 并触发人工审批，
        //    而用户审批的是一个「打印一行字」的操作。
        assertEquals(AiModuleRiskLevel.LOW, module.aiMetadata.riskLevel)
    }

    @Test
    fun `is offered to temporary workflows but not as a direct tool`() {
        val scopes = module.aiMetadata.usageScopes
        assertTrue(
            "临时工作流必须能用 —— 那是本模块存在的核心理由（Agent 调试取数）",
            AiModuleUsageScope.TEMPORARY_WORKFLOW in scopes,
        )
        assertFalse(
            "不该进直调工具表：直调没有工作流上下文，且 v2.0 的方向是精简直调工具数",
            AiModuleUsageScope.DIRECT_TOOL in scopes,
        )
    }

    @Test
    fun `stays available in saved workflows`() {
        // `allowSavedWorkflow` 不设（null）⇒ 保存的工作流里也能用。
        assertEquals(null, module.aiMetadata.allowSavedWorkflow)
    }

    @Test
    fun `content accepts magic and named variables`() {
        val content = module.getInputs().first { it.id == "content" }
        assertEquals(ParameterType.ANY, content.staticType)
        assertTrue(content.acceptsMagicVariable)
        assertTrue(content.acceptsNamedVariable)
    }

    @Test
    fun `level defaults to info and offers three options`() {
        val level = module.getInputs().first { it.id == "level" }
        assertEquals(LevelDefaults.INFO, level.defaultValue)
        assertEquals(listOf("info", "warn", "error"), level.options)
    }

    @Test
    fun `does not declare the two rejected parameters`() {
        // ⚠️ 反向锁：`step_id`（v1 设计）与 `max_length`（截断）都已推翻，
        //    谁把它们加回来这里必须变红。
        val ids = module.getInputs().map { it.id }
        assertFalse("`step_id` 已被「裸写步骤 id」取代", "step_id" in ids)
        assertFalse("截断已整体取消，不再需要 max_length", "max_length" in ids)
    }

    // ── 裸步骤 id 分派（本模块专有语义）────────────────────────

    @Test
    fun `a bare step id expands to all of that step's outputs`() = runBlocking {
        val ctx = contextWith(
            content = "{{ocr}}",
            outputs = mapOf(
                "ocr" to mapOf(
                    "full_text" to VString("你好"),
                    "count" to VNumber(2),
                )
            ),
        )

        val text = executeAndCaptureText(ctx)
        assertTrue("整表应被展开", text.contains("full_text"))
        assertTrue(text.contains("你好"))
        assertTrue(text.contains("count"))
    }

    @Test
    fun `a normal magic variable still resolves to a single value`() = runBlocking {
        // ⚠️ 反向锁：`{{ocr.count}}` 带输出名 ⇒ **不是**裸步骤 id，不能被整表分支吃掉。
        //    （`path.size != 1` 这条判据的机器化锁。）
        //
        // ⚠️ 混写串的解析结果由**执行器**放进 `magicVariables["content"]`
        //    （`WorkflowExecutor` 的参数解析块走 `isComplex` 分支）——
        //    这里如实模拟那一刻，而不是让本模块自己去 resolve
        //    （自己 resolve 会让解析不掉的引用被撑成 `{{{…}}}`，见 `renderResolvedValue` 的 KDoc）。
        val ctx = contextWith(
            content = "hello {{ocr.count}}",
            outputs = mapOf("ocr" to mapOf("count" to VNumber(2))),
            magicVariables = mapOf("content" to VString("hello 2")),
        )
        val text = executeAndCaptureText(ctx)
        assertEquals("应原样输出执行器解析好的文本", "hello 2", text)
        assertFalse("不该展开成整表", text.contains("count:"))
    }

    @Test
    fun `a whole-step reference must not swallow the surrounding text`() = runBlocking {
        // ⚠️⚠️ 反向锁（实现期真的踩到）：哨兵 `\u0000…\u0000` 是**夹在**两段文本之间的，
        //    `split` 出来的 `parts[i]` 才是哨兵**前面**的文本。
        //    写成「按 part 配 stepId」（`parts[i]` ↔ `placeholders[i]`）会把前缀吃掉 ——
        //    `label: {{ocr}}` 会输出成 `{count:2}`，而断言只看 `contains("count")` 时不报错。
        val ctx = contextWith(
            content = "label: {{ocr}}",
            outputs = mapOf("ocr" to mapOf("count" to VNumber(2))),
        )
        val text = executeAndCaptureText(ctx)
        assertTrue("前缀文本必须保留", text.startsWith("label: "))
        assertTrue("整表应展开在后半段", text.contains("count"))
    }

    @Test
    fun `two whole-step references in one string both expand in order`() = runBlocking {
        // ⚠️ 反向锁：占位符与 `split` 结果的下标关系是「N 个哨兵 ⇒ N+1 段」。
        //    只用一个哨兵时错的实现也能碰巧对，两个才暴露。
        val ctx = contextWith(
            content = "A{{a}}B{{b}}C",
            outputs = mapOf(
                "a" to mapOf("x" to VNumber(1)),
                "b" to mapOf("y" to VNumber(2)),
            ),
        )
        val text = executeAndCaptureText(ctx)
        assertTrue("第一段前缀必须在最前", text.startsWith("A"))
        assertTrue("第一个整表在 a 与 b 之间", text.indexOf("x:1") < text.indexOf("y:2"))
        assertTrue("结尾文本必须在最后", text.endsWith("C"))
    }

    @Test
    fun `a bare id that is not a step falls back to existing behaviour`() = runBlocking {
        // ⚠️ 反向锁：判据必须含 `stepOutputs.containsKey` ——
        //    否则 `{{index}}`（循环变量，落在 magicVariables）会被误判成步骤 id，
        //    打印成一个空整表 `{}` 而不是它的值。
        //
        // 模拟循环里那一刻：执行器的 `resolveSingleVariableReference("{{index}}")` 成功，
        // 结果放进 `magicVariables["content"]`（是个 **VNumber**，不是 VString）。
        val ctx = contextWith(
            content = "{{index}}",
            outputs = mapOf("ocr" to mapOf("count" to VNumber(2))),
            magicVariables = mapOf("content" to VNumber(3)),
        )
        val text = executeAndCaptureText(ctx)
        assertEquals("应打印循环变量本身的值", "3", text)
        assertFalse("不该把非步骤的单段引用当成整表", text.contains("count"))
    }

    @Test
    fun `an unresolved reference is printed verbatim, not wrapped in more braces`() = runBlocking {
        // ⚠️⚠️ 反向锁（本模块**刻意不**自己再跑一遍 `VariableResolver.resolve` 的理由）：
        //    `VariableResolver.resolveVariableObject` 在寻址失败时返回
        //    `VObjectFactory.from("{${rawExpression}}")`（VariableResolver.kt:133）——
        //    即 `{{nosuchstep}}` 会被包成 `{{{nosuchstep}}}` 再当变量递归重试，
        //    每层多一对括号，直到 10 层递归上限。用户打错一个 id
        //    就会在日志里看到几十个括号的噪声。
        //    执行器那条路**刻意不设** `magicVariables`（解析失败返回 null），
        //    回落原始串 —— 这里锁住的就是这个语义。
        val ctx = contextWith(
            content = "{{nosuchstep}}",
            outputs = mapOf("ocr" to mapOf("count" to VNumber(2))),
        )
        val result = execute(ctx)
        assertTrue("缺步骤不该算失败", result is ExecutionResult.Success)
        val text = executeAndCaptureText(ctx)
        assertEquals("应原样输出、不得撑大括号", "{{nosuchstep}}", text)
        assertFalse("不该把别的步骤的输出误当成本次结果", text.contains("count"))
    }

    @Test
    fun `a non-string content value is printed, not treated as empty`() = runBlocking {
        // ⚠️ `content` 是 ANY —— AI 可以直接给数字。
        //    `getParameterRaw` 对非字符串返回 null，漏了 else 分支会打印成 `(空)`。
        val ctx = ExecutionContext(
            applicationContext = ContextWrapper(null),
            variables = mutableMapOf("content" to VNumber(42)),
            magicVariables = mutableMapOf(),
            services = ExecutionServices(),
            allSteps = emptyList(),
            currentStepIndex = 0,
            stepOutputs = mutableMapOf(),
            loopStack = Stack(),
            namedVariables = mutableMapOf(),
            workDir = File("build/test-workdir"),
        )
        assertTrue(executeAndCaptureText(ctx).contains("42"))
    }

    @Test
    fun `an empty content prints a placeholder instead of failing`() = runBlocking {
        val ctx = contextWith(content = "", outputs = emptyMap())
        val captured = mutableListOf<String>()
        val ctx2 = contextWith(
            content = "",
            outputs = emptyMap(),
            logSink = { _, _, message -> captured += message },
        )
        val result = execute(ctx2)
        assertTrue("空内容不该算失败", result is ExecutionResult.Success)
        assertTrue("日志行里应给出 (空) 占位", captured.single().contains("(空)"))
        // ⚠️ 而 `text` 输出是**空串** —— 它的语义是「值本身」，不是给日志看的占位。
        assertEquals("", executeAndCaptureText(ctx2))
    }

    // ── 通过 logSink 落盘（真实链路）────────────────────────────

    @Test
    fun `writes through logSink and reports success`() = runBlocking {
        val captured = mutableListOf<Triple<ExecutionLogLevel, String, String>>()
        val ctx = contextWith(
            content = "\"hello\"",
            outputs = emptyMap(),
            logSink = { level, tag, message -> captured += Triple(level, tag, message) },
        )

        val result = execute(ctx)

        assertTrue(result is ExecutionResult.Success)
        assertEquals(1, captured.size)
        assertEquals(ExecutionLogLevel.INFO, captured[0].first)
        assertEquals("LogModule", captured[0].second)
        assertTrue("应带 [日志] 标记", captured[0].third.startsWith("[日志]"))
    }

    @Test
    fun `level parameter selects the log level`() = runBlocking {
        listOf("warn" to ExecutionLogLevel.WARN, "error" to ExecutionLogLevel.ERROR, "info" to ExecutionLogLevel.INFO)
            .forEach { (raw, expected) ->
                val captured = mutableListOf<ExecutionLogLevel>()
                val ctx = contextWith(
                    content = "\"x\"",
                    outputs = emptyMap(),
                    extraVariables = mapOf("level" to raw),
                    logSink = { level, _, _ -> captured += level },
                )
                execute(ctx)
                assertEquals("level=$raw 应映射到 $expected", expected, captured.single())
            }
    }

    @Test
    fun `success is false when no log sink is attached`() = runBlocking {
        // ⚠️ `logSink == null` = 这条路径没接日志（如默认值）。
        //    此时**不算失败**（不该中断工作流），但 `success` 要如实为 false。
        val ctx = contextWith(content = "\"x\"", outputs = emptyMap(), logSink = null)
        val result = execute(ctx) as ExecutionResult.Success
        assertEquals(false, (result.outputs["success"] as? com.chaomixian.vflow.core.types.basic.VBoolean)?.raw)
    }

    @Test
    fun `text output excludes the label prefix`() = runBlocking {
        // ⚠️ `text` 的语义是「把这个值再拿回来用」——带上 `label="x" ` 会让它不可用。
        val ctx = contextWith(
            content = "value",
            outputs = emptyMap(),
            extraVariables = mapOf("label" to "tag"),
        )
        val result = execute(ctx) as ExecutionResult.Success
        assertEquals("value", (result.outputs["text"] as? VString)?.raw)
        // ⚠️ 不带引号 —— `VariableResolver.resolve` 出的是纯文本，不是 VString 字面量。
    }

    // ── 源码扫描：接线锚定 ──────────────────────────────────────

    @Test
    fun `appendToLog filters the level but only after calling the global logger`() {
        // ⚠️⚠️ 存在理由：等级过滤**必须**放在 appendToLog 里、且**必须**排在
        //    `GlobalDebugLogger` 调用之后 —— 放在 d/i/w/e 里会把 logcat 与
        //    崩溃缓冲一起吃掉（那是另一条链路，本开关不该管）。
        //    纯行为测试测不出「过滤点放错了地方」，只能扫源码。
        val source = SourceScan.file("src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt")
            .readText()
        val body = SourceScan.functionBody(source, "private fun appendToLog(")

        assertTrue("未找到 appendToLog", body != null)
        val block = body!!
        assertTrue("必须调 allows()", block.contains("allows("))
        assertTrue("必须按 workflowId 查等级表", block.contains("logLevelsByWorkflow["))
        // 防空转
        assertTrue("截取的函数体过短（断言在空转）", block.count { it == '\n' } > 3)
    }

    @Test
    fun `the workflow executor injects a log sink into the execution context`() {
        val source = SourceScan.stripped("src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt")
        assertTrue(
            "WorkflowExecutor 没有注入 logSink —— 日志模块在工作流里会静默不输出",
            source.contains("logSink = "),
        )
    }

    @Test
    fun `the chat module executor injects a log sink too`() {
        val source = SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt")
        assertTrue(
            "直调路径没有注入 logSink",
            source.contains("logSink = "),
        )
    }

    @Test
    fun `the variable resolver is not touched by the bare step id feature`() {
        // ⚠️ 本模块专有语义**不得**写进全局解析器（会污染所有模块的参数解析）。
        val source = SourceScan.stripped("src/main/java/com/chaomixian/vflow/core/execution/VariableResolver.kt")
        assertFalse(
            "VariableResolver 里出现了整表返回 —— 那会把 {{cls}} 变成全局语义",
            source.contains("stepOutputs[") && source.contains("return context.stepOutputs"),
        )
    }

    // ── helpers ─────────────────────────────────────────────────

    private class LevelDefaults {
        companion object {
            const val INFO = "info"
        }
    }

    private fun contextWith(
        content: String,
        outputs: Map<String, Map<String, VObject>>,
        magicVariables: Map<String, VObject> = emptyMap(),
        extraVariables: Map<String, Any?> = emptyMap(),
        logSink: ((ExecutionLogLevel, String, String) -> Unit)? = { _, _, _ -> },
    ): ExecutionContext {
        val variables = mutableMapOf<String, VObject>("content" to VString(content))
        extraVariables.forEach { (k, v) -> variables[k] = VObjectFactory.from(v) }
        return ExecutionContext(
            applicationContext = ContextWrapper(null),
            variables = variables,
            magicVariables = magicVariables.toMutableMap(),
            services = ExecutionServices(),
            allSteps = emptyList(),
            currentStepIndex = 0,
            stepOutputs = outputs.mapValues { (_, v) -> v }.toMutableMap(),
            loopStack = Stack(),
            namedVariables = mutableMapOf(),
            workDir = File("build/test-workdir"),
            logSink = logSink,
        )
    }

    private suspend fun execute(ctx: ExecutionContext): ExecutionResult =
        module.execute(ctx) { }

    private suspend fun executeAndCaptureText(ctx: ExecutionContext): String {
        val result = execute(ctx) as ExecutionResult.Success
        return (result.outputs["text"] as? VString)?.raw.orEmpty()
    }
}
