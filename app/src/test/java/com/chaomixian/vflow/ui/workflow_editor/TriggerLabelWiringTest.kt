package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.TriggerLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发器标签的**接线锚定**测试（源码扫描型）。
 *
 * ⚠️ 为什么不用行为测：本仓库**无 Robolectric**，`WorkflowEditorActivity` /
 * `ActionStepAdapter` 都是 Android 组件，纯 JVM 里起不来。而本批改动里最危险的
 * 两处（缺陷① 的参数合并、操作区的 gating）恰恰都是**「改错了不报错、只静默变差」**型，
 * 只能靠源码扫描钉住（形态照 `test/services/CoreDexFingerprintTest.kt`）。
 *
 * 每条断言都**剥注释**后再做 —— 本改动在源码里写了大段中文注释解释原因，
 * 只做 `contains` 的话「把真实调用删掉、只留注释」照样绿
 * （本仓库在 `AgentErrorDialogWiringTest` 上踩过完全相同的坑）。
 */
class TriggerLabelWiringTest {

    private val editorActivity = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/WorkflowEditorActivity.kt"
    private val adapter = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionStepAdapter.kt"
    private val executor = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
    private val catalogBuilder = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/WorkflowEditorMagicVariableCatalogBuilder.kt"

    // ---------- 缺陷①：参数整表替换 ----------

    @Test
    fun `showTriggerEditor merges parameters instead of replacing them`() {
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun showTriggerEditor(")
        assertNotNull("找不到 showTriggerEditor 函数体", body)
        val text = requireNotNull(body)

        assertFalse(
            "缺陷① 回归：onSave 的 else 分支又变回整表替换了。" +
                "newStepData.parameters 只含模块声明的输入 ⇒ 用户设好的标签会在" +
                "「再点开改一次触发条件」时静默丢失（同分支还吃掉 __error_policy / __retry_count）。",
            text.contains("triggerSteps[position] = triggerSteps[position].copy(parameters = newStepData.parameters)"),
        )
        assertTrue(
            "必须以旧参数为基、合并新参数",
            text.contains("updatedParams.putAll(newStepData.parameters)"),
        )
        // 防空转：别让它在一个空函数体上通过
        assertTrue("函数体异常短，疑似扫描失败", text.lines().size > 20)
        assertTrue("函数体应含 recalculateAndNotify()（确认截取的是 onSave 回调那段）", text.contains("recalculateAndNotify()"))
    }

    @Test
    fun `showActionEditor keeps its original replace behaviour`() {
        // ⚠️ 保护性断言：动作步骤上不存在 `__trigger_label`，本任务**刻意不改**它
        //    （改它需要单独评估 __error_policy 的存量影响，且会扩大 diff 面积）。
        //    这条断言防「顺手一起改」。将来若确实要改，请连同本测试一并评估。
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun showActionEditor(")
        assertNotNull("找不到 showActionEditor 函数体", body)

        assertTrue(
            "showActionEditor 应保持原样（整表替换）。若你确实改了它，请连同本断言一起更新并说明理由。",
            requireNotNull(body).contains("actionSteps[position] = actionSteps[position].copy(parameters = newStepData.parameters)"),
        )
    }

    // ---------- WorkflowExecutor 注入 ----------

    @Test
    fun `executor injects the trigger label into named variables`() {
        val source = SourceScan.stripped(executor)

        assertTrue("必须引用 TriggerLabel.VARIABLE_NAME", source.contains("TriggerLabel.VARIABLE_NAME"))
        assertTrue("必须调用 TriggerLabel.labelFor(", source.contains("TriggerLabel.labelFor("))
    }

    @Test
    fun `the injection lives on the initialContext of execute not on the inner executor`() {
        // 结构锚定（不用「N 字符窗口」——`SourceScan` 剥注释时**保留长度**，
        // 窗口阈值会随注释增删而无声翻转，要么误红、要么形同虚设）。
        val source = SourceScan.stripped(executor)

        val executeBody = SourceScan.functionBody(source, "fun execute(")
        assertNotNull("找不到 execute 函数体", executeBody)
        assertTrue(
            "注入必须在 execute() 内",
            requireNotNull(executeBody).contains("TriggerLabel.VARIABLE_NAME"),
        )

        val internalBody = SourceScan.functionBody(source, "private suspend fun executeWorkflowInternal(")
        assertNotNull("找不到 executeWorkflowInternal 函数体", internalBody)
        assertFalse(
            "注入不该出现在 executeWorkflowInternal 里（它拿不到 triggerStepId，且会被子工作流路径绕过）",
            requireNotNull(internalBody).contains("TriggerLabel.VARIABLE_NAME"),
        )

        // 再补一条位置锚：注入点必须在 initialContext 的构造段内 ——
        // 判据是两者之间不出现 `logSink`（那是 initialContext 构造的最后一个参数）。
        val namedIdx = source.indexOf("TriggerLabel.VARIABLE_NAME")
        val logSinkIdx = source.indexOf("logSink = { level, tag, message ->")
        assertTrue("找不到 logSink 构造点", logSinkIdx > 0)
        assertTrue(
            "TriggerLabel 注入应落在 namedVariables 初始化处（在 logSink 之前、initialContext 之内）",
            namedIdx in 1 until logSinkIdx,
        )
    }

    // ---------- 卡片：按钮 / 回显 / gating ----------

    @Test
    fun `the card reads the label through the pure function`() {
        val source = SourceScan.stripped(adapter)
        assertTrue(
            "卡片回显必须走 TriggerLabel.labelOf(（不能走 module.getSummary()）",
            source.contains("TriggerLabel.labelOf("),
        )
    }

    @Test
    fun `the trigger action area is no longer gated by isDeletable`() {
        // ⚠️ 存在理由：`layout_step_actions` 在触发器卡上原由 `isDeletable` 门控
        //    （`isDeletable = triggerSteps.size > 1`）⇒ **只有一个触发器时整个操作区
        //    （含新加的标签按钮）隐藏**。而单触发器是最常见的形态（含 Agent 保存的工作流）。
        //    这是**纯视觉、无任何报错**的失效，行为测测不到。
        val source = SourceScan.stripped(adapter)
        val body = SourceScan.functionBody(source, "private fun bindEmbeddedStepCard(")
        assertNotNull("找不到 bindEmbeddedStepCard 函数体", body)
        val text = requireNotNull(body)

        // 防空转
        assertTrue("函数体异常短，疑似扫描失败", text.lines().size > 60)
        assertTrue("函数体应含 contentContainer.removeAllViews()", text.contains("contentContainer.removeAllViews()"))

        // 只应有两处给 actionContainer.visibility 赋值（isActionStep 分支 + else 分支）
        assertEquals(
            "actionContainer.visibility 的赋值应恰好两处。实际：$text",
            2,
            SourceScan.countOccurrences(text, "actionContainer.visibility ="),
        )

        // ⚠️ 判据必须针对**整行文本**，不能用「含 else View.VISIBLE」——
        //    isActionStep 分支那行以同样的前缀开头（`… else View.VISIBLE`），
        //    `indexOf` 会命中它、反证时**不变红**（第一版就是这么写的，反证实测没红，已修正）。
        assertEquals(
            "触发器卡（else 分支）的操作区必须对触发器恒 VISIBLE（selectionModeEnabled 时 GONE）" +
                "—— 不得再按 isDeletable 门控，否则单触发器工作流上标签按钮整个消失。",
            0,
            SourceScan.countOccurrences(
                text,
                "actionContainer.visibility = if (selectionModeEnabled) View.GONE else if (isDeletable)",
            ),
        )
        // 两个分支（isActionStep / else）现在都是「恒 VISIBLE」形态 ⇒ 该文本应恰好出现两次。
        // bug 版本里 else 分支不同 ⇒ 只剩一次。
        assertEquals(
            "两个分支都应采用「恒 VISIBLE」形态（isActionStep 一处、else 一处）",
            2,
            SourceScan.countOccurrences(
                text,
                "actionContainer.visibility = if (selectionModeEnabled) View.GONE else View.VISIBLE",
            ),
        )
    }

    @Test
    fun `the label button visibility is driven by isActionStep`() {
        val source = SourceScan.stripped(adapter)
        val body = SourceScan.functionBody(source, "private fun bindEmbeddedStepCard(")
        val text = requireNotNull(body)

        assertTrue(
            "标签按钮的显隐必须由既有判据 isActionStep 驱动（不新造标志位）",
            text.contains("labelButton.visibility = if (!isActionStep"),
        )
        assertTrue(
            "普通步骤不传回调时应为 GONE（双重保险）",
            text.contains("onTriggerLabelClick != null"),
        )
    }

    // ---------- 编辑器接线 ----------

    @Test
    fun `the editor wires the label sheet`() {
        val source = SourceScan.stripped(editorActivity)

        assertTrue("必须实例化 TriggerLabelSheet", source.contains("TriggerLabelSheet.newInstance("))
        assertTrue("必须调用 TriggerLabel.withLabel(", source.contains("TriggerLabel.withLabel("))
        assertTrue("必须有 onTriggerLabelClick 接线", source.contains("onTriggerLabelClick = {"))
        assertTrue(
            "改标签前必须先 pushUndoSnapshot()",
            source.contains("showTriggerLabelSheet"),
        )
    }

    @Test
    fun `the label sheet takes an undo snapshot before mutating`() {
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun showTriggerLabelSheet(")
        assertNotNull("找不到 showTriggerLabelSheet 函数体", body)
        val text = requireNotNull(body)

        val snapshotIdx = text.indexOf("pushUndoSnapshot()")
        val mutateIdx = text.indexOf("TriggerLabel.withLabel(")
        assertTrue("必须先 pushUndoSnapshot()", snapshotIdx >= 0)
        assertTrue("必须调用 withLabel", mutateIdx >= 0)
        assertTrue(
            "pushUndoSnapshot() 必须在改动**之前**（否则撤销栈记的是改完的状态）",
            snapshotIdx < mutateIdx,
        )
    }

    // ---------- 魔法变量选择器 ----------

    @Test
    fun `the picker exposes a fixed trigger label group`() {
        val source = SourceScan.stripped(catalogBuilder)

        assertTrue("buildNamedVariables 必须接受 hasAutoTriggers", source.contains("hasAutoTriggers: Boolean"))
        assertTrue("必须引用 TriggerLabel.VARIABLE_REFERENCE", source.contains("TriggerLabel.VARIABLE_REFERENCE"))
        assertTrue("必须用 editor_group_trigger_label 作分组名", source.contains("R.string.editor_group_trigger_label"))
    }

    @Test
    fun `the picker call site passes hasAutoTriggers`() {
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun showMagicVariablePicker(")
        assertNotNull("找不到 showMagicVariablePicker 函数体", body)
        assertTrue(
            "调用点必须传 hasAutoTriggers（否则参数只是摆设、分组永不出现）",
            requireNotNull(body).contains("hasAutoTriggers ="),
        )
    }

    // ---------- 命名定案 ----------

    @Test
    fun `the label sheet clears by writing an empty string that withLabel turns into key removal`() {
        // 契约：清空 = 传空串 ⇒ `withLabel` 删键。两处必须同向，
        // 若某处改成传 null 或直接写空串值，卡片回显会变成一行空白。
        val sheet = SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/workflow_editor/TriggerLabelSheet.kt")
        assertTrue("清空按钮应回调空串", sheet.contains("onSave?.invoke(\"\")"))
        assertTrue(TriggerLabel.KEY.startsWith("__"))
    }
}
