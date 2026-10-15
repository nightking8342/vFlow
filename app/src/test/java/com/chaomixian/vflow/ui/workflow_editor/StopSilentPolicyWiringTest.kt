package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-4（静默终止）的**接线锚定**测试（源码扫描型）。
 *
 * ⚠️ 为什么不用行为测：本仓库**无 Robolectric**，`WorkflowExecutor` / `ActionEditorSheet`
 * 都是 Android 组件，纯 JVM 里起不来。而这条改动最危险的失效方式恰好是
 * 「**纯函数测了，但调用点没接**」—— 本仓库在 `CoreLauncher.recordLaunchedDexFingerprint`
 * 上踩过完全相同的坑（13 个纯函数单测全绿，集成点缺失，表现为"改了但没生效"）。
 *
 * 每条断言都**剥注释**后再做（`SourceScan.stripped`）：本改动在源码里写了中文注释解释原因，
 * 只做 `contains` 的话「把真实调用删掉、只留注释」照样绿。
 */
class StopSilentPolicyWiringTest {

    private val executor = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
    private val editorSheet = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionEditorSheet.kt"
    private val settingsScreen = "src/main/java/com/chaomixian/vflow/ui/settings/SettingsScreen.kt"
    private val settings = "src/main/java/com/chaomixian/vflow/core/workflow/model/ActionStepExecutionSettings.kt"

    @Test
    fun `执行器经 shouldShowErrorDialog 决定是否弹窗`() {
        val source = SourceScan.stripped(executor)

        // ⚠️ 形态变更（2026-10-15，fork P0-1「策略链」）：实参由 `executionSettings.policy`
        //    改为 `effectivePolicy` —— 因为 RETRY 的「用尽后 = STOP_SILENT」也必须在这一层生效，
        //    否则用户选了「重试用尽后静默停止」仍然会被弹窗打扰。
        //    断言随之升级为**必须传展开后的策略**，比原来更强（顺带锁住了策略链的接线）。
        assertTrue(
            "失败分支必须经 shouldShowErrorDialog 判断，否则 STOP_SILENT 不生效（弹窗照弹）。",
            source.contains(
                "ActionStepExecutionSettings.shouldShowErrorDialog(effectivePolicy, showErrorDialog)"
            ),
        )
        // 防空转：确认截到的确实是执行器源码
        assertTrue("疑似扫描失败：执行器源码异常短", source.length > 10_000)
    }

    @Test
    fun `编辑器为 STOP_SILENT 提供选项与文案`() {
        val source = SourceScan.stripped(editorSheet)

        assertTrue(
            "缺少 STOP_SILENT 的 radio ⇒ 用户设不了；更糟的是恢复选中态的 when 有 else 兜底，" +
                "存量步骤里已存该值时会**静默改回 STOP**。",
            source.contains("ActionStepExecutionSettings.POLICY_STOP_SILENT"),
        )
        assertTrue(
            "必须给该 radio 接上新字符串资源",
            source.contains("R.string.editor_error_policy_stop_silent"),
        )
    }

    @Test
    fun `每一种策略常量都在编辑器里有对应选项`() {
        // ⚠️⚠️ 这条是**面向未来**的守卫（自动枚举，不写死名单）：
        // 两个 UI 都有「选项列表 + 兜底」的形态，漏接的后果都是**静默改值**：
        //  - `ActionEditorSheet`：恢复选中态用 `when (currentPolicy) { ... else -> rbStop.isChecked = true }`
        //    ⇒ 存量步骤里已存的值被静默改回 STOP；
        //  - `SettingsScreen`：`checked = localPolicy == value` ⇒ 该值**三个按钮全不选中**，
        //    用户随手点一个就把全局默认覆盖掉了。
        // 将来加第 5 种策略时，这条会自动变红，逼实现者同时补两处。
        val policyConstants = Regex("""const val (POLICY_[A-Z_]+)\s*=""")
            .findAll(SourceScan.stripped(settings))
            .map { it.groupValues[1] }
            .toList()

        assertTrue("没能从 ActionStepExecutionSettings 里解析出任何 POLICY_ 常量，扫描失效", policyConstants.size >= 4)

        val editorSource = SourceScan.stripped(editorSheet)
        val settingsSource = SourceScan.stripped(settingsScreen)
        policyConstants.forEach { name ->
            assertTrue(
                "策略常量 $name 在 ActionEditorSheet 里没有对应选项 —— " +
                    "漏加 radio 会让存量步骤里已存的该值被 when 的 else 分支静默改回 STOP。",
                editorSource.contains("ActionStepExecutionSettings.$name"),
            )
            assertTrue(
                "策略常量 $name 在设置页（全局默认策略）里没有对应选项 —— " +
                    "漏加会让该值在设置页显示为「三个都没选中」，用户一点就把它覆盖掉。",
                settingsSource.contains("ActionStepExecutionSettings.$name"),
            )
        }
    }

    @Test
    fun `三种语言都有该文案`() {
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml",
            "src/main/res/values-ja/strings.xml",
        ).forEach { path ->
            val text = SourceScan.file(path).readText()
            assertTrue(
                "$path 缺少 editor_error_policy_stop_silent（缺了会在该语言下回落到默认语言或显示为键名）",
                text.contains("name=\"editor_error_policy_stop_silent\""),
            )
        }
    }

    @Test
    fun `常量与纯函数住在 ActionStepExecutionSettings`() {
        // 防「把常量搬到别处、只改一半」：执行器与编辑器必须引用同一个来源。
        val source = SourceScan.stripped(settings)
        assertTrue(source.contains("const val POLICY_STOP_SILENT"))
        assertTrue(source.contains("fun shouldShowErrorDialog("))
    }
}
