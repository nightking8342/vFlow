package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **B1 修复的接线锚定**：编辑器里每一处「结构变更」之后都要重映射跳转引用（源码扫描型）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §6 B1。
 *
 * ## 为什么必须是源码扫描
 *
 * `WorkflowEditorActivity` 是 Android 组件，纯 JVM 起不来；而这条缺陷的形态是
 * **「漏接一处调用点」**：`vflow.logic.jump` 的目标存的是**显示序号**，只要有一处
 * 增删步骤没重映射，那个跳转就**静默指到别的步骤**（不报错、不提示）。
 * 修复前只有拖拽路径接了（而且用的是只支持等长重排的上游实现）。
 *
 * ## 覆盖范围（11 处调用）
 *
 * 粘贴 / 追加（含「定义函数」强制首位）/ 复制 / 恢复缺失块成员 / 清理未注册模块（删除）/
 * Switch 骨架 / 模板（追加 + 指定位置）/ 主插入路径（新建步骤）/ 删除（拒绝语义）。
 *
 * ## 刻意**不**覆盖的路径（各自有别的机制）
 *
 * | 路径 | 为什么不在这里重映射 |
 * |---|---|
 * | 加载工作流 / 撤销 / 重做 / AI 应用补丁 / 枚举迁移 | **整份替换**：内容自带一致的编号（AI 路径自己做过 `remapJumpReferences`），不该拿旧列表当基准去"修"它 |
 * | 拖拽 | 等长重排，走**上游** `WorkflowJumpReferenceUpdater.remapAfterReorder`（正是它的设计场景） |
 */
class JumpRemapWiringTest {

    private val editor = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/WorkflowEditorActivity.kt"

    /**
     * 断言 `anchor`（一处步骤列表变更）之后**不远**就有重映射调用。
     *
     * ⚠️ 用「窗口」而不是精确行号：行号会随上游漂移，而这条断言真正要防的是
     * 「有人新增/改写了一处结构变更，忘了重映射」—— 那种失效**不报错、不崩溃**，
     * 只让某个跳转静默指到别的步骤。
     */
    private fun assertRemappedNear(source: String, anchor: String) {
        val at = source.indexOf(anchor)
        assertTrue("找不到锚点：`$anchor`（锚点被改写或扫描失效）", at >= 0)
        val window = source.substring(at, minOf(source.length, at + 1_200))
        assertTrue(
            "`$anchor` 之后没有 `remapJumpReferencesAfterEdit(...)` —— " +
                "这条路径上的 `vflow.logic.jump` 目标会**静默漂移**（不报错、不提示）",
            window.contains("remapJumpReferencesAfterEdit("),
        )
    }

    @Test
    fun `每一处结构变更都接了重映射`() {
        val source = SourceScan.stripped(editor)

        // 本批修的就是「只接了一处」——所以这里**逐条列锚点**，而不是只数个数。
        // ⚠️ 整份替换（加载工作流 / 撤销 / 重做 / AI 应用补丁 / 枚举迁移）与拖拽
        //    （走上游版 remapAfterReorder）**刻意不在此列**，理由见本文件 KDoc 与
        //    `remapJumpReferencesAfterEdit` 的 KDoc。
        listOf(
            "actionSteps.addAll(safeInsertPosition, stepsToPaste)",        // 粘贴
            "actionSteps.addAll(0, steps)",                                 // 追加（含「定义函数」强制首位）
            "actionSteps.addAll(start, steps)",                             // 追加（普通）
            "actionSteps.addAll(insertPosition, stepsToDuplicate)",         // 复制
            "actionSteps.addAll(insertPosition, restoredSteps)",            // 恢复缺失块成员
            "actionSteps.addAll(cleanedActionSteps)",                       // 清理未注册模块（删除）
            "actionSteps.addAll(insertPosition, configured)",               // Switch 骨架
            "actionSteps.addAll(newSteps)",                                 // 模板（追加到末尾）
            "actionSteps.addAll(insertPosition, newSteps)",                 // 模板（指定位置）
            "actionSteps.add(insertPosition, configuredFirstStep)",         // 主插入路径
            "actionSteps.addAll(insertPosition + 1, stepsToAdd.subList(1, stepsToAdd.size))",
        ).forEach { assertRemappedNear(source, it) }
    }

    @Test
    fun `结构变更点的数量没有变（新增时必须回来核对）`() {
        // ⚠️ 这是**面向未来**的金丝雀：有人新增/删除了改动 `actionSteps` 的路径时，
        // 这条会红 —— 那是好事，请逐条核对新路径要不要重映射，然后同步本数字。
        // （与 `AgentErrorDialogWiringTest` 里「调用点数量变了请复核」同一种做法。）
        val source = SourceScan.stripped(editor)
        val mutationSites = Regex("""actionSteps\.(addAll|add|clear)\(""").findAll(source).count()

        assertEquals(
            "步骤列表的变更点数量变了 ⇒ 有人新增/删除了结构变更路径。" +
                "请核对新路径是否需要 remapJumpReferencesAfterEdit（并同步本数字与锚点清单）",
            31,
            mutationSites,
        )
        // 1 处定义 + 11 处调用（上一条测试逐条列了锚点）
        assertEquals(
            "重映射的调用点数量应与锚点清单一致（1 定义 + 11 调用）",
            12,
            SourceScan.countOccurrences(source, "remapJumpReferencesAfterEdit("),
        )
    }

    @Test
    fun `重映射用 fork 版（按 step id 建映射、支持增删）`() {
        val source = SourceScan.stripped(editor)

        assertTrue(
            "重映射必须用 fork 版（按 step id 建映射、支持增删）",
            source.contains("WorkflowPatch.remapJumpReferences(before, actionSteps.toList())"),
        )
    }

    @Test
    fun `拖拽路径仍用上游实现（刻意的分工）`() {
        // ⚠️ 这条断言锁的是一个**决策**：拖拽是等长重排，正是上游
        // `WorkflowJumpReferenceUpdater.remapAfterReorder` 的设计场景 ⇒ 继续用它，
        // 上游文件一行不动（控制 diff 面积）。两份实现的一致性由
        // `WorkflowJumpRemapContractTest` 保证。
        val source = SourceScan.stripped(editor)

        assertTrue(
            "拖拽路径应继续调用上游实现；若改成 fork 版，请同时更新本断言与 FORK.md 的登记",
            source.contains("WorkflowJumpReferenceUpdater.remapAfterReorder("),
        )
    }

    @Test
    fun `删除撞上跳转目标时拒绝并回滚`() {
        val source = SourceScan.stripped(editor)

        assertTrue(
            "有跳转指向被删步骤时必须**拒绝删除**（与 AI 侧补丁同语义：静默保留悬空引用会让跳转跳飞）",
            source.contains("R.string.editor_toast_delete_blocked_by_jump"),
        )
        assertTrue(
            "拒绝时必须把列表回滚到删除前，否则 UI 与数据不一致",
            source.contains("actionSteps.addAll(stepsBeforeDelete)"),
        )
        assertTrue(
            "回滚后必须刷新列表",
            source.contains("recalculateAndNotify()"),
        )
    }

    @Test
    fun `三种语言都有该提示文案`() {
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml",
            "src/main/res/values-ja/strings.xml",
        ).forEach { path ->
            assertTrue(
                "$path 缺少 editor_toast_delete_blocked_by_jump（缺了会回落默认语言或显示为键名）",
                SourceScan.file(path).readText().contains("name=\"editor_toast_delete_blocked_by_jump\""),
            )
        }
    }
}
