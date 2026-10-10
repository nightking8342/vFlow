package com.chaomixian.vflow.core.workflow.module

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工作流开关」两个模块的**目标选择器候选过滤**的源码扫描锚定（fork 独有）。
 *
 * ## 存在理由（真机反馈，2026-10-10）
 *
 * 首版两个 UIProvider 都直接喂 `workflowManager.getAllWorkflows()`，于是**手动触发的工作流
 * 也能被选中** —— 而那种工作流在卡片上**没有开关**（`WorkflowListScreen` 只在
 * `hasAutoTriggers` 时画 `VFlowSwitch`，手动型画的是 ▶ 执行按钮），
 * 它的 `isEnabled` 也**毫无作用**（`WorkflowExecutor` / `ManualTriggerModule` 都不读它）。
 * ⇒ 用户选中它等于**给了一个永远不可能发生的触发条件**。
 *
 * ## 为什么必须是源码扫描
 *
 * 失效模式是**静默**的：过滤被删掉之后**没有任何行为测试会红** ——
 * 选择器照常弹、照常能选，只是列表里多了几个不该有的条目。
 * 而 `createEditor` 要 `Context` 与 `ViewGroup`，纯 JVM 起不来。
 *
 * ⚠️ **先剥注释再断言** —— 两个文件的 KDoc 里到处写着 `TileGate.accepts` 与
 * `hasAutoTriggers`，不剥注释的话「把真实过滤删掉、只留注释」照样绿
 * （本仓库在 `AgentErrorDialogWiringTest` 上踩过完全相同的坑）。
 *
 * ⚠️ **反证已实际做过**：把任一 UIProvider 的 `.filter { … }` 去掉 ⇒ 对应用例变红；
 * 改回后复绿。
 */
class WorkflowTogglePickerWiringTest {

    private val triggerPickerPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/WorkflowToggleTriggerUIProvider.kt"
    private val modulePickerPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/logic/SetWorkflowEnabledUIProvider.kt"

    private val pickers = mapOf(
        "触发器侧" to triggerPickerPath,
        "动作模块侧" to modulePickerPath,
    )

    // ── 一、两个选择器都必须按 TileGate(TOGGLE) 过滤 ──────────────────

    @Test
    fun `both pickers filter candidates by TileGate TOGGLE`() {
        pickers.forEach { (side, path) ->
            val src = SourceScan.stripped(path)

            assertTrue(
                "$side 的候选没有按 TileGate.accepts(TileKind.TOGGLE, …) 过滤 —— " +
                    "手动触发的工作流会重新出现在选择器里（它们没有开关状态）",
                src.contains("TileGate.accepts(TileKind.TOGGLE"),
            )
            // 防空转：确认这个文件确实还是那个选择器（没被搬走 / 剥注释没过火）
            assertTrue(
                "$side 里找不到 SearchableWorkflowDialog.show( —— 文件可能被搬走或剥注释过了火",
                src.contains("SearchableWorkflowDialog.show("),
            )
            assertTrue(
                "$side 里找不到 getAllWorkflows( —— 候选来源变了，本断言需要重新评估",
                src.contains("getAllWorkflows("),
            )
        }
    }

    // ── 二、反向锁：不能再出现「未过滤直接喂给对话框」的旧形状 ────────

    @Test
    fun `neither picker feeds an unfiltered workflow list to the dialog`() {
        pickers.forEach { (side, path) ->
            val src = SourceScan.stripped(path)

            assertFalse(
                "$side 又把未过滤的 getAllWorkflows() 直接喂给对话框了 —— " +
                    "这正是真机反馈里「所有工作流都能选」的成因",
                src.contains("getAllWorkflows().map"),
            )
            assertFalse(
                "$side 的 items 参数直接写了 getAllWorkflows(（未过滤）",
                src.contains("items = workflowManager.getAllWorkflows("),
            )
        }
    }

    // ── 三、空态必须有提示，不能弹一个空列表 ──────────────────────────

    @Test
    fun `both pickers guard the empty candidate list with an explicit hint`() {
        pickers.forEach { (side, path) ->
            val src = SourceScan.stripped(path)

            assertTrue(
                "$side 没有空态守卫 —— 用户会看到一个写着「没有找到相关工作流」的空列表，" +
                    "而那不是「搜不到」、是「一个都没有」",
                src.contains("toast_no_toggleable_workflow"),
            )
            assertTrue(
                "$side 的空态没有提前 return —— 空列表仍会弹出来",
                src.contains("return@setOnClickListener"),
            )
        }
    }

    // ── 四、空态文案必须三语齐全（漏一个语言会显示成另一种语言） ──────

    @Test
    fun `the empty state string exists in all three locales`() {
        val locales = listOf("values", "values-en", "values-ja")
        locales.forEach { locale ->
            val xml = SourceScan.file("src/main/res/$locale/strings_module.xml").readText()
            assertTrue(
                "$locale/strings_module.xml 里缺 toast_no_toggleable_workflow —— " +
                    "该语言下会显示成另一种语言的文案",
                xml.contains("name=\"toast_no_toggleable_workflow\""),
            )
        }
    }
}
