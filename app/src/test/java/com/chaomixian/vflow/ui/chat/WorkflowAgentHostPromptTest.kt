package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **工作流模块的提示词分段**（fork：AI 能力统一 D13 / D16，批 4 的 T6）。
 *
 * `AgentModule` 的提示词由三部分拼成：**前段**（模块参数 / 引用配置的值拷贝）
 * → **协议段**（`agentModuleProtocolSection`，含日期 / RECENT APPS）
 * → **公共段**（[SHARED_RULES]）→ **规则段**（[WORKFLOW_RULES]，那 19 条的唯一来源）
 * → `# User Goal`。
 *
 * ## ⚠️ 必做反证
 *
 * 把 `WORKFLOW_RULES` 从 [WorkflowModulePromptPlan.build] 的段序里去掉
 * ⇒ [protocolSection_sitsBetweenThePrefixAndTheSharedRules] 与
 * [rulesSection_isProvidedByWorkflowRules] **变红**。
 */
class WorkflowAgentHostPromptTest {

    private fun context(instruction: String? = null) = AgentPromptContext(
        tools = emptyList(),
        instruction = instruction,
    )

    // ------------------------------------------------------------ 1. 空协议段 ⇒ 与批 3 逐字节相同

    @Test
    fun emptyProtocolSection_isByteIdenticalToTheBatchThreePlan() {
        val legacy = WorkflowPromptPlan("PREFIX").build(context(instruction = "打开设置"))
        val viaHost = WorkflowAgentHost(
            allToolNames = emptyList(),
            prefix = "PREFIX",
        ).promptPlan.build(context(instruction = "打开设置"))

        assertEquals(
            "协议段为空时必须与批 3 的 WorkflowPromptPlan 输出逐字节相同（既有行为不回退）",
            legacy,
            viaHost,
        )
    }

    @Test
    fun blankProtocolSection_alsoFallsBackToTheBatchThreePlan() {
        val viaHost = WorkflowAgentHost(
            allToolNames = emptyList(),
            prefix = "PREFIX",
            protocolSection = "   ",
        ).promptPlan.build(context())

        assertEquals(
            "纯空白的协议段与「不传」等价",
            WorkflowPromptPlan("PREFIX").build(context()),
            viaHost,
        )
    }

    // ------------------------------------------------------------ 2. 段序

    @Test
    fun protocolSection_sitsBetweenThePrefixAndTheSharedRules() {
        val prompt = WorkflowAgentHost(
            allToolNames = emptyList(),
            prefix = "PREFIX-SECTION",
            protocolSection = "PROTOCOL-SECTION",
        ).promptPlan.build(context(instruction = "打开设置"))

        val iPrefix = prompt.indexOf("PREFIX-SECTION")
        val iProtocol = prompt.indexOf("PROTOCOL-SECTION")
        val iShared = prompt.indexOf(SHARED_RULES)
        val iRules = prompt.indexOf(WORKFLOW_RULES)
        val iGoal = prompt.indexOf("# User Goal")

        assertTrue("前段必须在", iPrefix >= 0)
        assertTrue("协议段必须在", iProtocol >= 0)
        assertTrue("公共段必须在", iShared >= 0)
        assertTrue("规则段必须在", iRules >= 0)
        assertTrue("User Goal 必须在", iGoal >= 0)

        assertTrue("段序：前段 → 协议段", iPrefix < iProtocol)
        assertTrue("段序：协议段 → 公共段", iProtocol < iShared)
        assertTrue("段序：公共段 → 规则段", iShared < iRules)
        assertTrue("段序：规则段 → User Goal（规则段在最后 = 优先级更高，D13）", iRules < iGoal)
    }

    @Test
    fun rulesSection_isProvidedByWorkflowRules() {
        val prompt = WorkflowAgentHost(
            allToolNames = emptyList(),
            protocolSection = "PROTOCOL-SECTION",
        ).promptPlan.build(context())

        assertTrue(
            "19 条规则必须由 WORKFLOW_RULES 提供（单一来源）",
            prompt.contains(WORKFLOW_RULES),
        )
        assertTrue(
            "规则段的标题就在 WORKFLOW_RULES 里",
            prompt.contains("# 必须遵循的规则："),
        )
        assertEquals(
            "规则段在提示词里只出现一次",
            1,
            Regex(Regex.escape("# 必须遵循的规则：")).findAll(prompt).count(),
        )
    }

    // ------------------------------------------------------------ 3. 空段不产生空行

    @Test
    fun blankSectionsProduceNoBlankLines() {
        val prompt = WorkflowModulePromptPlan("", "   ").build(context())

        assertEquals(
            "空前段 + 空协议段 ⇒ 只剩公共段与规则段，且段间恰一个空行",
            SHARED_RULES + "\n\n" + WORKFLOW_RULES,
            prompt,
        )
        assertFalse("不得以空行开头", prompt.startsWith("\n"))
    }

    @Test
    fun prefixAndProtocolAreTrimmed() {
        val prompt = WorkflowModulePromptPlan("\n  PREFIX  \n", "\n  PROTOCOL  \n").build(context())

        assertTrue("前段必须被 trim", prompt.startsWith("PREFIX\n\nPROTOCOL"))
        assertFalse("不得留下前后空行", prompt.startsWith("\n"))
    }

    // ------------------------------------------------------------ 4. User Goal

    @Test
    fun instructionBecomesTheUserGoalSection() {
        val prompt = WorkflowModulePromptPlan("", "PROTOCOL").build(context(instruction = "打开设置"))

        assertTrue(
            "instruction 必须进 `# User Goal` 段（引擎把它交给 plan）",
            prompt.endsWith("# User Goal\n\"打开设置\""),
        )
    }

    @Test
    fun blankInstructionOmitsTheUserGoalSection() {
        val prompt = WorkflowModulePromptPlan("", "PROTOCOL").build(context(instruction = "   "))

        assertFalse("空白 instruction 不得产生空的 User Goal 段", prompt.contains("# User Goal"))
    }
}