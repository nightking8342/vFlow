package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.ActionStepExecutionSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **P0-1（策略链）的接线锚定**测试（源码扫描型）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.1 / §3.2。
 *
 * 纯函数那一半在 `ActionStepExecutionSettingsTest`（`effectivePolicy` 的展开规则）；
 * 这里钉的是**「函数测了但没人调」**这一类失效 —— 本仓库在
 * `CoreLauncher.recordLaunchedDexFingerprint` 上踩过完全相同的坑
 * （13 个纯函数单测全绿，集成点缺失，表现为"改了但没生效"）。
 *
 * 本仓库无 Robolectric，`WorkflowExecutor` / `ActionEditorSheet` 都是 Android 组件 ⇒
 * 只能在源码层锁。
 */
class RetryExhaustedPolicyWiringTest {

    private val executor = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
    private val editorSheet = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionEditorSheet.kt"
    private val editorSettings =
        "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionEditorExecutionSettings.kt"

    @Test
    fun `执行器按有效策略分流（跳过分支与弹窗门禁都用 effectivePolicy）`() {
        val source = SourceScan.stripped(executor)

        assertTrue(
            "执行器必须算出有效策略，否则 `__on_retry_exhausted` 写了也不生效。",
            source.contains("ActionStepExecutionSettings.effectivePolicy("),
        )
        assertTrue(
            "跳过分支必须判有效策略 —— 否则「RETRY + 用尽后跳过」会落进终止分支。",
            // ⚠️ 形态变更（2026-10-15，fork P0-5）：条件后面多了
            //    `&& !jumpBudgetExhausted`（跳转总预算用尽时改走**终止**，不再跳过）。
            //    这里只断言前半句，避免把那一层的括号写死。
            source.contains("effectivePolicy == ActionStepExecutionSettings.POLICY_SKIP"),
        )
        assertTrue(
            "弹窗门禁必须判有效策略 —— 否则「RETRY + 用尽后静默停止」仍会弹窗。",
            source.contains("shouldShowErrorDialog(effectivePolicy, showErrorDialog)"),
        )
        // 防空转：确认截到的确实是执行器源码
        assertTrue("疑似扫描失败：执行器源码异常短", source.length > 10_000)
    }

    @Test
    fun `重试循环仍看原始策略（不许被有效策略污染）`() {
        // ⚠️ 反向断言：「要不要重试」与「重试完之后怎么办」是两件事。
        // 若把重试 `while` 也改成 `effectivePolicy`，`RETRY + 用尽后=SKIP` 会**一次都不重试**
        // （有效策略是 SKIP，条件 `!= RETRY` 恒真 ⇒ 循环立刻退出）——
        // 功能看起来"能用"（确实跳过了），但重试静默失效。
        val source = SourceScan.stripped(executor)

        assertTrue(
            "重试循环的条件必须继续看原始 errorPolicy。",
            source.contains(
                "while (attempt <= retryCount || errorPolicy != ActionStepExecutionSettings.POLICY_RETRY)"
            ),
        )
        assertTrue(
            "重试计数分支必须继续看原始 errorPolicy。",
            source.contains(
                "if (errorPolicy == ActionStepExecutionSettings.POLICY_RETRY && finalResult is ExecutionResult.Failure)"
            ),
        )
    }

    @Test
    fun `编辑器把该键写进参数并在非 RETRY 时清掉`() {
        val source = SourceScan.stripped(editorSettings)

        assertTrue(
            "RETRY 时必须写入 `__on_retry_exhausted`，否则用户在 UI 上的选择存不下来。",
            source.contains("this[ActionStepExecutionSettings.KEY_ON_RETRY_EXHAUSTED] = settings.onRetryExhausted"),
        )
        assertTrue(
            "非 RETRY 时必须移除该键（它在其它策略下没有语义，留着会成为" +
                "「看起来有效、实际被忽略」的字段）。",
            source.contains("remove(ActionStepExecutionSettings.KEY_ON_RETRY_EXHAUSTED)"),
        )
    }

    @Test
    fun `编辑器的下拉选项顺序与常量表一一对应`() {
        // ⚠️⚠️ 这条是**静默写错参数**的守卫：`StandardControlFactory.bindDropdown`
        // 是按 **index** 取文案的（`optionsStringRes[index]`），顺序错位会让下拉里显示
        // 「跳过此步骤继续」而实际存进去的是 `STOP`（用户看到的选择和真正生效的策略不一致）。
        val source = SourceScan.stripped(editorSheet)

        assertTrue(
            "下拉必须直接用常量表当 options，不能另抄一份字面量（那样会与执行器脱钩）。",
            source.contains("options = ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES"),
        )

        // `\b` 让 `..._stop` 不会匹配到 `..._stop_silent`（`_` 是词字符）。
        val actualOrder = Regex("""R\.string\.editor_retry_exhausted_(stop_silent|skip|stop)\b""")
            .findAll(source)
            .map { it.groupValues[1] }
            .toList()

        val expectedOrder = ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES.map { policy ->
            when (policy) {
                ActionStepExecutionSettings.POLICY_STOP -> "stop"
                ActionStepExecutionSettings.POLICY_STOP_SILENT -> "stop_silent"
                ActionStepExecutionSettings.POLICY_SKIP -> "skip"
                else -> error(
                    "用尽后动作新增了取值 `$policy`，但 `ActionEditorSheet` 没有对应文案 —— " +
                        "下拉会显示错位或显示为键名。"
                )
            }
        }

        assertEquals(
            "下拉文案顺序必须与 ON_RETRY_EXHAUSTED_CHOICES 一一对应（按 index 取文案）。",
            expectedOrder,
            actualOrder,
        )
    }

    @Test
    fun `三种语言都有用尽后动作的文案`() {
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml",
            "src/main/res/values-ja/strings.xml",
        ).forEach { path ->
            val text = SourceScan.file(path).readText()
            listOf(
                "editor_retry_exhausted_label",
                "editor_retry_exhausted_stop",
                "editor_retry_exhausted_stop_silent",
                "editor_retry_exhausted_skip",
            ).forEach { name ->
                assertTrue(
                    "$path 缺少 $name（缺了会在该语言下回落默认语言，或显示为键名）。",
                    text.contains("name=\"$name\""),
                )
            }
        }
    }
}
