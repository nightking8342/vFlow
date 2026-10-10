package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.normalizeEnumValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置工作流开关模块的判定单测 + 「`changed == false` 时不写盘」的源码锚定。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md`（§7 模块定义 / §11.2 验证）。
 */
class SetWorkflowEnabledModuleTest {

    // ═══════════════════════════════════════════════════════════════════
    // 一、判定纯函数 desiredEnabled
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `enable and disable are absolute`() {
        assertTrue(
            SetWorkflowEnabledModule.desiredEnabled(
                SetWorkflowEnabledModule.VALUE_ENABLE, currentEnabled = false,
            )
        )
        // 已经是启用态时再「启用」⇒ 仍是 true（changed 会是 false）
        assertTrue(
            SetWorkflowEnabledModule.desiredEnabled(
                SetWorkflowEnabledModule.VALUE_ENABLE, currentEnabled = true,
            )
        )
        assertFalse(
            SetWorkflowEnabledModule.desiredEnabled(
                SetWorkflowEnabledModule.VALUE_DISABLE, currentEnabled = true,
            )
        )
        assertFalse(
            SetWorkflowEnabledModule.desiredEnabled(
                SetWorkflowEnabledModule.VALUE_DISABLE, currentEnabled = false,
            )
        )
    }

    @Test
    fun `toggle inverts the current state`() {
        assertFalse(
            SetWorkflowEnabledModule.desiredEnabled(
                SetWorkflowEnabledModule.VALUE_TOGGLE, currentEnabled = true,
            )
        )
        assertTrue(
            SetWorkflowEnabledModule.desiredEnabled(
                SetWorkflowEnabledModule.VALUE_TOGGLE, currentEnabled = false,
            )
        )
    }

    @Test
    fun `unknown and null actions fall into the toggle branch`() {
        // ⚠️ 形态与设计文档 §7.3 的示例**逐字一致**：只显式列 enable / disable，
        //    其余（toggle + 经 normalizeEnumValue 后仍非法的未知值）落在 else 的取反分支。
        assertFalse(
            SetWorkflowEnabledModule.desiredEnabled("bogus", currentEnabled = true),
        )
        assertTrue(
            SetWorkflowEnabledModule.desiredEnabled("bogus", currentEnabled = false),
        )
        assertFalse(
            SetWorkflowEnabledModule.desiredEnabled(null, currentEnabled = true),
        )
        assertTrue(
            SetWorkflowEnabledModule.desiredEnabled(null, currentEnabled = false),
        )
    }

    @Test
    fun `changed is derived from desired versus current`() {
        fun changed(action: String?, current: Boolean) =
            SetWorkflowEnabledModule.desiredEnabled(action, current) != current

        assertTrue(changed(SetWorkflowEnabledModule.VALUE_ENABLE, current = false))
        assertFalse(changed(SetWorkflowEnabledModule.VALUE_ENABLE, current = true))
        assertTrue(changed(SetWorkflowEnabledModule.VALUE_DISABLE, current = true))
        assertFalse(changed(SetWorkflowEnabledModule.VALUE_DISABLE, current = false))
        // toggle 永远变化
        assertTrue(changed(SetWorkflowEnabledModule.VALUE_TOGGLE, current = true))
        assertTrue(changed(SetWorkflowEnabledModule.VALUE_TOGGLE, current = false))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 二、枚举归一化
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `action enum normalizes stable english values and falls back on null`() {
        val inputs = SetWorkflowEnabledModule().getInputs()

        assertEquals(
            SetWorkflowEnabledModule.VALUE_ENABLE,
            inputs.normalizeEnumValue(
                SetWorkflowEnabledModule.PARAM_ACTION,
                SetWorkflowEnabledModule.VALUE_ENABLE,
                SetWorkflowEnabledModule.VALUE_ENABLE,
            ),
        )
        assertEquals(
            SetWorkflowEnabledModule.VALUE_DISABLE,
            inputs.normalizeEnumValue(
                SetWorkflowEnabledModule.PARAM_ACTION,
                SetWorkflowEnabledModule.VALUE_DISABLE,
                SetWorkflowEnabledModule.VALUE_ENABLE,
            ),
        )
        assertEquals(
            SetWorkflowEnabledModule.VALUE_TOGGLE,
            inputs.normalizeEnumValue(
                SetWorkflowEnabledModule.PARAM_ACTION,
                SetWorkflowEnabledModule.VALUE_TOGGLE,
                SetWorkflowEnabledModule.VALUE_ENABLE,
            ),
        )
        // null ⇒ 回落默认档
        assertEquals(
            SetWorkflowEnabledModule.VALUE_ENABLE,
            inputs.normalizeEnumValue(
                SetWorkflowEnabledModule.PARAM_ACTION,
                null,
                SetWorkflowEnabledModule.VALUE_ENABLE,
            ),
        )
        // 非空未知值原样透出（由 desiredEnabled 的 else 分支接管）
        assertEquals(
            "bogus",
            inputs.normalizeEnumValue(
                SetWorkflowEnabledModule.PARAM_ACTION,
                "bogus",
                SetWorkflowEnabledModule.VALUE_ENABLE,
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 三、声明体检
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `module declaration is well formed`() {
        val module = SetWorkflowEnabledModule()

        assertEquals("vflow.logic.set_workflow_enabled", module.id)
        assertEquals("logic", module.metadata.categoryId)
        // 零权限 —— 不涉及任何系统能力
        assertTrue(module.requiredPermissions.isEmpty())
        // AI 元数据：与触发器**不同**，本动作模块给 aiMetadata（设计文档 §7.5）
        assertEquals(
            setOf(
                com.chaomixian.vflow.core.module.AiModuleUsageScope.DIRECT_TOOL,
                com.chaomixian.vflow.core.module.AiModuleUsageScope.TEMPORARY_WORKFLOW,
            ),
            module.aiMetadata?.usageScopes,
        )
        assertEquals(
            com.chaomixian.vflow.core.module.AiModuleRiskLevel.STANDARD,
            module.aiMetadata?.riskLevel,
        )
    }

    @Test
    fun `outputs and inputs are declared`() {
        val module = SetWorkflowEnabledModule()

        assertEquals(
            listOf("workflow_id", "action"),
            module.getInputs().map { it.id },
        )
        assertEquals(
            listOf("workflow_name", "previous_enabled", "is_enabled", "changed"),
            module.getOutputs(null).map { it.id },
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 四、源码锚定：「changed == false 时不写盘」
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `saveWorkflow sits inside the changed branch`() {
        val path = "src/main/java/com/chaomixian/vflow/core/workflow/module/logic/SetWorkflowEnabledModule.kt"
        val src = SourceScan.stripped(path)

        val body = SourceScan.functionBody(src, "override suspend fun execute(")
        assertTrue("找不到 execute 的函数体（文件被搬走或签名变了）", body != null)

        // ⚠️ 锚点必须是 `if (changed) {`（**带左大括号**）而不是 `if (changed)` ——
        //    同一函数体里 `getString(if (changed) … else …)` 还有一处裸 `if (changed)`，
        //    用它当锚点会让「把守卫改成 `if (true)`」照样绿（实测确认过这条弱断言）。
        val guard = body!!.indexOf("if (changed) {")
        val save = body.indexOf("saveWorkflow(")
        assertTrue("execute 里没有 `if (changed) {` 守卫", guard >= 0)
        assertTrue("execute 里没有 saveWorkflow( 调用", save >= 0)
        assertTrue(
            "saveWorkflow( 必须落在 `if (changed) {` 之后 —— " +
                "否则「目标本来就是这个状态」也会白写一次盘（多走一遍 " +
                "notifyWorkflowChanged + TileRefreshNotifier）",
            guard < save,
        )
        // 反向锁：不得再出现第二处 `if (changed) {`（防有人复制出一个恒真的分支）
        assertEquals(1, SourceScan.countOccurrences(body, "if (changed) {"))
    }

    @Test
    fun `the module does not override validate or requiredPermissions`() {
        val path = "src/main/java/com/chaomixian/vflow/core/workflow/module/logic/SetWorkflowEnabledModule.kt"
        val src = SourceScan.stripped(path)

        // 与 CallWorkflowModule 一致：目标不存在时在 execute 里返回 Failure，
        // 不做编辑期校验（避免第二套判据）
        assertFalse(
            "本模块刻意不覆写 validate（与 CallWorkflowModule 一致）",
            src.contains("override fun validate("),
        )
        // 零权限：覆写 requiredPermissions 会让每个用到本模块的工作流无条件索要权限
        assertFalse(
            "本模块刻意不覆写 requiredPermissions（零权限）",
            src.contains("override val requiredPermissions"),
        )
    }
}
