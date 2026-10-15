package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **P0-5（跳过跳转 + 错误上下文）的接线锚定**测试（源码扫描型）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.5 / §2.6 / §4.3。
 *
 * 纯函数那一半在 `JumpTargetSupportTest` / `ErrorContextTest`；这里钉的是
 * **「函数测了但没人调」**与**「跨文件契约漂移」**这两类失效 ——
 * 本仓库在 `CoreLauncher.recordLaunchedDexFingerprint` 与
 * `ChatMessagePatch.SKIPPED_FAILURE_LINE_PREFIX` 上都吃过亏。
 */
class SkipJumpWiringTest {

    private val executor = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
    private val editorSheet = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionEditorSheet.kt"
    private val editorSettings =
        "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionEditorExecutionSettings.kt"
    private val catalog =
        "src/main/java/com/chaomixian/vflow/ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt"
    private val chatMessagePatch = "src/main/java/com/chaomixian/vflow/ui/chat/ChatMessagePatch.kt"

    @Test
    fun `执行器接上了跳转的三个纯函数`() {
        val source = SourceScan.stripped(executor)

        assertTrue(
            "必须按 id 反查目标下标，否则 `__skip_jump_target` 写了也不生效。",
            source.contains("JumpTargetSupport.resolveTargetIndex(workflow.steps, jumpTargetId)"),
        )
        assertTrue(
            "必须做运行期合法性校验（向内跳 / 块中间结束模块）—— UI 过滤只是少给错选项，运行期才是防线。",
            source.contains("JumpTargetSupport.isAllowedTarget("),
        )
        assertTrue(
            "向外跳必须对齐 loopStack，否则后续步骤读到过期的循环变量（既有缺陷 B3）。",
            source.contains("JumpTargetSupport.alignLoopStack(loopStack, workflow.steps, jumpTargetIndex)"),
        )
        assertTrue(
            "失败被跳过时必须写错误上下文，否则收尾步骤读不到 `[[__error]]`。",
            source.contains("ErrorContext.of("),
        )
        assertTrue(
            "错误上下文必须**恒注入**（正常路径空字典）—— 缺键会让 `[[__error.message]]` 解析成字面量。",
            source.contains("namedVariables[ErrorContext.VARIABLE_NAME] = ErrorContext.EMPTY"),
        )
        // 防空转
        assertTrue("疑似扫描失败：执行器源码异常短", source.length > 10_000)
    }

    @Test
    fun `两层防护的阈值来自常量而不是字面量`() {
        val source = SourceScan.stripped(executor)

        assertTrue(
            "单步上限必须引用常量（否则调阈值时容易漏改一处）。",
            source.contains("JumpTargetSupport.MAX_JUMPS_PER_STEP"),
        )
        assertTrue(
            "总预算必须引用常量。",
            source.contains("JumpTargetSupport.MAX_JUMPS_PER_EXECUTION"),
        )
        assertTrue(
            "总预算用尽必须**终止**（设计 §2.5：比静默继续安全）。",
            source.contains("疑似死循环"),
        )
    }

    @Test
    fun `跳过日志行与 AI 卡片锚点逐字一致`() {
        // ⚠️⚠️ 跨文件契约：`ChatMessagePatch.SKIPPED_FAILURE_LINE_PREFIX` 是 AI 侧判定
        // 「有步骤被跳过」的**唯一**锚点。跳转分支改了这段文案而没同步 ⇒ 聊天卡片把
        // 「有步骤失败被跳过」**误报成 SUCCESS**（无报错、无人察觉）。
        val anchor = Regex("""SKIPPED_FAILURE_LINE_PREFIX\s*=\s*"([^"]+)"""")
            .find(SourceScan.stripped(chatMessagePatch))
            ?.groupValues?.get(1)
            ?: error("没能从 ChatMessagePatch 解析出 SKIPPED_FAILURE_LINE_PREFIX，扫描失效")

        // 锚点形如 `W/WorkflowExecutor: 根据策略，跳过错误继续执行。` ⇒ 剥掉级别与 TAG 前缀
        val message = anchor.substringAfter(": ")
        assertTrue("锚点解析异常：$anchor", message.isNotBlank())

        val source = SourceScan.stripped(executor)
        assertTrue(
            "执行器里必须原样打出那条 SKIP 日志行（AI 卡片靠它判「有步骤被跳过」）。",
            source.contains("DebugLogger.w(\"WorkflowExecutor\", \"$message\")"),
        )
    }

    @Test
    fun `编辑器把跳转目标写进参数并在非 SKIP 时清掉`() {
        val source = SourceScan.stripped(editorSettings)

        assertTrue(
            "必须写入 `__skip_jump_target`，否则用户在 UI 上选的跳转目标存不下来。",
            source.contains("this[ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET] = jumpTarget"),
        )
        assertTrue(
            "非 SKIP（有效策略）时必须移除该键 —— 留着会成为「看起来有效、实际被忽略」的字段。",
            source.contains("remove(ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET)"),
        )
        assertTrue(
            "判据必须是**有效策略**，否则「RETRY + 用尽后 = SKIP」这个组合配不出跳转目标。",
            source.contains("ActionStepExecutionSettings.effectivePolicy("),
        )
    }

    @Test
    fun `编辑器的跳转选择器用 id 当值 用序号当显示`() {
        val source = SourceScan.stripped(editorSheet)

        assertTrue(
            "跳转选择器必须传 `displayOptions`（给人看的是 `#序号 模块名`，存的是 step id）。",
            source.contains("displayOptions = jumpOptionLabels"),
        )
        assertTrue(
            "候选必须用与运行期**同一把尺**过滤。",
            source.contains("JumpTargetSupport.isAllowedTarget(steps, index, currentIndentation)"),
        )
        assertTrue(
            "存量目标不在候选里时必须原样保留，否则一保存就把它**静默清掉**。",
            source.contains("R.string.editor_skip_jump_missing"),
        )
        assertTrue(
            "可见性必须跟着**有效策略**变（含「RETRY + 用尽后 = SKIP」）。",
            source.contains("refreshSkipJumpVisibility()"),
        )
        assertTrue(
            "保存时必须做静态环检测（第三层防护）。",
            source.contains("warnOnJumpCycle(finalParams)"),
        )
        assertTrue(
            "环检测必须真的调用纯函数。",
            source.contains("JumpTargetSupport.detectCycles("),
        )
    }

    @Test
    fun `变量选择器提供错误上下文分组`() {
        val source = SourceScan.stripped(catalog)

        assertTrue(
            "选择器必须提供 `[[__error]]` 条目，否则用户根本选不到它。",
            source.contains("ErrorContext.VARIABLE_REFERENCE"),
        )
        assertTrue(
            "键必须来自 ErrorContext 常量（本地化文案会被原样拼进引用路径 ⇒ 解析不到）。",
            source.contains("ErrorContext.FIELD_MESSAGE"),
        )
        assertTrue(
            "分组必须只在工作流真的存在失败跳转时出现（否则永远给一个空字典，误导用户）。",
            source.contains("hasFailureJump(actionSteps)"),
        )
    }

    @Test
    fun `三种语言都有跳转与错误上下文的文案`() {
        val expected = listOf(
            "editor_skip_jump_label",
            "editor_skip_jump_none",
            "editor_skip_jump_option",
            "editor_skip_jump_missing",
            "editor_skip_jump_cycle_warning",
            "editor_group_error_context",
            "error_context_variable_name",
        )
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml",
            "src/main/res/values-ja/strings.xml",
        ).forEach { path ->
            val text = SourceScan.file(path).readText()
            expected.forEach { name ->
                assertTrue(
                    "$path 缺少 $name（缺了会回落默认语言或显示为键名）。",
                    text.contains("name=\"$name\""),
                )
            }
        }
        assertEquals("文案数量变了？请同步本测试", 7, expected.size)
    }
}
