package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Switch 管理 sheet 的**接线锚定**测试（源码扫描型）。
 *
 * ⚠️ 为什么不用行为测：`SwitchEditorSheet` / `WorkflowEditorActivity` / `ActionStepAdapter`
 * 都是 Android 组件（`BottomSheetDialogFragment` / `Activity` / `RecyclerView.Adapter`），
 * 本仓库**无 Robolectric** ⇒ 纯 JVM 里起不来。而本批改动最危险的几处恰是
 * **「改错了不报错、只静默变差」**型（sheet 没接上 ⇒ 点 Switch 卡片仍弹旧的参数 sheet；
 * 取消路径误调 onSave ⇒ 用户点取消反而改了工作流），只能靠源码扫描钉住
 * （形态照 `test/services/CoreDexFingerprintTest.kt` 与
 * `test/ui/workflow_editor/TriggerLabelWiringTest.kt`）。
 *
 * ⚠️ 每条断言都**先剥注释** —— 本改动在源码里写了大段中文注释解释原因，
 * 只做 `contains` 的话「把真实调用删掉、只留注释」照样绿
 * （本仓库在 `AgentErrorDialogWiringTest` 上踩过完全相同的坑）。
 *
 * ⚠️ 路径以 `app/` 为工作目录（`SourceScan.file` 的注释写明）。
 */
class SwitchEditorSheetWiringTest {

    private val moduleRegistry = "src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"
    private val editorActivity = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/WorkflowEditorActivity.kt"
    private val adapter = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/ActionStepAdapter.kt"
    private val sheet = "src/main/java/com/chaomixian/vflow/core/workflow/module/logic/SwitchEditorSheet.kt"

    // ---------- ① ModuleRegistry：4 个 register ----------

    @Test
    fun `module registry registers all four switch modules`() {
        val source = SourceScan.stripped(moduleRegistry)

        assertTrue(
            "ModuleRegistry 里找不到 register(SwitchModule(), context)",
            source.contains("register(SwitchModule(), context)"),
        )
        assertTrue(source.contains("register(SwitchCaseModule(), context)"))
        assertTrue(source.contains("register(SwitchDefaultModule(), context)"))
        assertTrue(source.contains("register(EndSwitchModule(), context)"))

        // 防空转：确认扫到的是真实的 initialize 主体
        assertTrue("扫描内容异常短，疑似读错文件", source.lines().size > 100)
    }

    // ---------- ② syncDynamicBlockAfterSave：Switch 分支 ----------

    @Test
    fun `syncDynamicBlockAfterSave carries the switch branches`() {
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun syncDynamicBlockAfterSave(")
        assertNotNull("找不到 syncDynamicBlockAfterSave 函数体（方法被改名了？）", body)
        val text = requireNotNull(body)

        assertTrue("必须按 SWITCH_START_ID 分流", text.contains("SWITCH_START_ID"))
        assertTrue(
            "Switch 起始卡保存后必须 reconcile 分支区",
            text.contains("SwitchBlockSupport.reconcileBranches("),
        )
        assertTrue(
            "Case/Default 卡片上改的匹配值必须写回 branches（设计文档 §5 第 10 条）",
            text.contains("SwitchBlockSupport.syncMatchFromStep("),
        )
        assertTrue(
            "必须用 switchJustSavedPosition 跳过一次 reconcile（防把用户刚做的调序冲掉）",
            text.contains("switchJustSavedPosition"),
        )
        // 防空转
        assertTrue("函数体异常短，疑似扫描失败", text.lines().size > 8)
    }

    // ---------- ③ 保存顺序：pushUndoSnapshot 在 reconcile 之前 ----------

    @Test
    fun `showSwitchEditorSheet snapshots undo before reconciling`() {
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun showSwitchEditorSheet(")
        assertNotNull("找不到 showSwitchEditorSheet 函数体", body)
        val text = requireNotNull(body)

        val snapshotIndex = text.indexOf("pushUndoSnapshot()")
        val reconcileIndex = text.indexOf("SwitchBlockSupport.reconcileBranches(")
        assertTrue("保存路径必须调 pushUndoSnapshot()", snapshotIndex >= 0)
        assertTrue("保存路径必须调 reconcileBranches(", reconcileIndex >= 0)
        assertTrue(
            "pushUndoSnapshot() 必须在 reconcileBranches 之前（在改动之前拍快照）",
            snapshotIndex < reconcileIndex,
        )
        // ⚠️ 写回必须是**合并式**：整表替换会吃掉 __error_policy / __retry_count
        assertTrue(
            "写回必须是 start.parameters.toMutableMap() 的合并式，不能 copy(parameters = mapOf(...))",
            text.contains("parameters.toMutableMap()"),
        )
        assertTrue("保存后必须 recalculateAndNotify()", text.contains("recalculateAndNotify()"))
        assertTrue("函数体异常短，疑似扫描失败", text.lines().size > 20)
    }

    @Test
    fun `both new-switch entry points are routed to the manager sheet`() {
        val source = SourceScan.stripped(editorActivity)

        // ① showActionEditor 入口分流（覆盖「点卡片」与「点值 pill」两条路）
        val actionEditorBody = SourceScan.functionBody(source, "private fun showActionEditor(")
        assertNotNull("找不到 showActionEditor 函数体", actionEditorBody)
        assertTrue(
            "showActionEditor 必须在入口分流到 showSwitchEditorSheet —— " +
                "只做在 Adapter 单击链路上的话，点值 pill 仍会打开旧的参数 sheet，" +
                "而它的 readFromEditor 会用旧 branches 覆盖用户刚做的调序",
            requireNotNull(actionEditorBody).contains("showSwitchEditorSheet("),
        )

        // ② showActionEditorAtPosition 入口分流（「在下方插入」）
        val atPositionBody = SourceScan.functionBody(source, "private fun showActionEditorAtPosition(")
        assertNotNull("找不到 showActionEditorAtPosition 函数体", atPositionBody)
        assertTrue(
            "showActionEditorAtPosition 必须分流到 showSwitchEditorSheetForNew",
            requireNotNull(atPositionBody).contains("showSwitchEditorSheetForNew("),
        )

        // ③ Adapter 单击链路接线
        assertTrue("setupRecyclerView 必须把 onSwitchCardClick 传下去", source.contains("onSwitchCardClick = {"))
    }

    // ---------- ③b 新建落点：必须用 insertPosition，不得走 addStepsWithDefineFunctionRule ----------

    @Test
    fun `new switch block is inserted at the requested position`() {
        val source = SourceScan.stripped(editorActivity)
        val body = SourceScan.functionBody(source, "private fun showSwitchEditorSheetForNew(")
        assertNotNull("找不到 showSwitchEditorSheetForNew 函数体", body)
        val text = requireNotNull(body)

        // ① 必须真的按 insertPosition 插入 —— 走 `addStepsWithDefineFunctionRule` 的话
        //    它的语义是「无条件 addAll(actionSteps.size, ...)」⇒ 整块落到工作流**末尾**
        //    ⇒ 「在下方插入」落点错误，且**不报错**。FAB「加到末尾」那条路恰好传的就是
        //    `actionSteps.size`，所以掩盖了这个缺陷（只有「在下方插入」能暴露）。
        assertTrue(
            "新建 Switch 块必须 `actionSteps.addAll(insertPosition, ...)`（否则会静默落到末尾）",
            text.contains("actionSteps.addAll(insertPosition, configured)"),
        )
        assertTrue(
            "actualStart 必须取 insertPosition（reconcile 要认到刚插进去的那张 Start 卡）",
            text.contains("val actualStart = insertPosition"),
        )
        // ② ⚠️ 反向锁：不得走 addStepsWithDefineFunctionRule。
        //    ⚠️⚠️ 必须先剥注释 —— 本文件在源码里写了一段解释「为什么不用它」的注释，
        //    不剥的话这条 contains 会被注释里的字面量命中、断言在**空转中恒红**。
        assertFalse(
            "不得走 addStepsWithDefineFunctionRule —— 它是「定义函数必须首位」的决策 16 通道，" +
                "而 Switch 骨架不可能含 DEFINE_FUNCTION_MODULE_ID，走它等于追加到末尾",
            text.contains("addStepsWithDefineFunctionRule"),
        )
        assertTrue("函数体异常短，疑似扫描失败", text.lines().size > 20)
    }

    // ---------- ④ ActionStepAdapter：可选回调 + 分流 ----------

    @Test
    fun `adapter exposes an optional switch card callback`() {
        val source = SourceScan.stripped(adapter)

        // ⚠️ 反向锁：默认值必须仍是 null（可选回调不得变成必填，
        //    否则所有未传该参数的既有调用点会编译不过 / 行为被改）
        assertTrue(
            "onSwitchCardClick 必须带默认值 null（可选回调）",
            source.contains("private val onSwitchCardClick: ((position: Int) -> Unit)? = null,"),
        )
        // 单击链路上的分流
        assertTrue(
            "卡片单击的防抖回调里必须按 SWITCH_START_ID 分流",
            source.contains("step.moduleId == SWITCH_START_ID && switchClick != null"),
        )
        assertTrue("分流必须回落到既有的 onEditClick", source.contains("onEditClick(actualPosition, null)"))
        assertTrue("扫描内容异常短，疑似读错文件", source.lines().size > 200)
    }

    // ---------- ⑤ SwitchEditorSheet：取消无副作用 ----------

    @Test
    fun `cancel only dismisses and produces no side effects`() {
        val source = SourceScan.stripped(sheet)

        // 取「取消按钮那一段接线」的窗口。⚠️ 不能用全文 indexOf 比较 ——
        // 保存逻辑写在取消之后，全文首个出现位置会随排版漂移，断言会恒绿或恒红。
        // 这里用**大括号配对**截取按钮接线 lambda 的正文（`SourceScan.functionBody` 的第一个
        // `{` 就是该 lambda 的开括号）。
        assertTrue(
            "SwitchEditorSheet 必须绑定 `R.id.button_switch_sheet_cancel`（找不到就说明按钮被改名/删了）",
            source.contains("R.id.button_switch_sheet_cancel"),
        )
        val lambdaBody = SourceScan.functionBody(
            source,
            "cancelButton.setOnClickListener {",
        )
        assertNotNull(
            "找不到取消按钮接线（`cancelButton.setOnClickListener { dismiss() }`）",
            lambdaBody,
        )
        val window = requireNotNull(lambdaBody)

        assertTrue("取消必须 dismiss()", window.contains("dismiss()"))
        assertFalse("取消路径不得调用 onSave", window.contains("onSave?.invoke"))
        assertFalse("取消路径不得 reconcile", window.contains("reconcileBranches"))
        assertFalse("取消路径不得写回 actionSteps", window.contains("actionSteps"))
    }

    @Test
    fun `save collects pending text and validates before invoking the callback`() {
        val source = SourceScan.stripped(sheet)
        val body = SourceScan.functionBody(source, "saveButton.setOnClickListener {")
        assertNotNull("找不到保存按钮接线", body)
        val text = requireNotNull(body)

        val commitIndex = text.indexOf("commitPendingText()")
        val validateIndex = text.indexOf("validateBranches(")
        val invokeIndex = text.indexOf("onSave?.invoke(")
        assertTrue(
            "保存前必须先把「尚未失焦」的输入框文本收进来（用户可能输入后立刻点保存）",
            commitIndex >= 0,
        )
        assertTrue("保存前必须校验", validateIndex >= 0)
        assertTrue("必须调用 onSave 回调", invokeIndex >= 0)
        assertTrue("先收文本、再校验、最后回调", commitIndex < validateIndex && validateIndex < invokeIndex)
    }

    // ---------- ⑥ 三语文案键齐全 ----------

    @Test
    fun `switch resource keys exist in all three locales`() {
        val keys = listOf(
            "module_vflow_logic_switch_start_name",
            "module_vflow_logic_switch_start_desc",
            "module_vflow_logic_switch_case_name",
            "module_vflow_logic_switch_case_desc",
            "module_vflow_logic_switch_default_name",
            "module_vflow_logic_switch_default_desc",
            "module_vflow_logic_switch_end_name",
            "module_vflow_logic_switch_end_desc",
            "param_vflow_logic_switch_start_value_name",
            "param_vflow_logic_switch_start_branches_name",
            "param_vflow_logic_switch_case_match_name",
            "sheet_switch_title",
            "sheet_switch_hint",
            "sheet_switch_value_hint",
            "sheet_switch_branch_header",
            "sheet_switch_branch_tag_case",
            "sheet_switch_branch_tag_default",
            "sheet_switch_add_case",
            "sheet_switch_add_default",
            "sheet_switch_drag_desc",
            "sheet_switch_remove_desc",
            "sheet_switch_no_match_hint",
            "sheet_switch_case_hint",
        )

        val locales = listOf("values", "values-en", "values-ja")
        val sources = locales.associateWith { locale ->
            SourceScan.file("src/main/res/$locale/strings_module.xml").readText()
        }

        keys.forEach { key ->
            locales.forEach { locale ->
                assertTrue(
                    "键 `$key` 在 `$locale/strings_module.xml` 里缺失（三语必须同步）",
                    sources.getValue(locale).contains("name=\"$key\""),
                )
            }
        }
    }
}
