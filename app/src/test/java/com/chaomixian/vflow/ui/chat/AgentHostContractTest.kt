package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **`AgentHost` 契约 + 两个宿主的最终形态**（fork：AI 能力统一 §3.11 / D11 / D12 / D13 / D16 / D17）。
 *
 * 本文件全部是纯 JVM 单测：`AgentHost` / `AgentPrompts` / 两个宿主都**零 `Context` 依赖**
 * （这正是把工具执行器与全量工具表做成注入点的原因）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 `AgentHost.requestApproval` 的默认实现改成 `Deferred` ⇒
 *    [interface_requestApprovalDefaultsToApproved] 与
 *    [workflowHost_isAlwaysApproved] **变红**；
 * 2. 把 `ChatHost.requestApproval` 的判据改成恒 `Approved`（绕过 `allows`）⇒
 *    [chatHost_requestApprovalFollowsTheConfiguredScope] **变红**；
 * 3. 把 `WorkflowPromptPlan` 的段顺序改成「专属段在前」⇒
 *    [workflowPromptPlan_ordersPrefixSharedRulesThenUserGoal] **变红**；
 * 4. 把 `ChatPromptPlan` 的委托换成自己拼的一段文案 ⇒
 *    [chatPromptPlan_delegatesToTheUpstreamRouterVerbatim] **变红**。
 */
class AgentHostContractTest {

    // ------------------------------------------------------------ 装置

    private fun tool(name: String) = ChatAgentToolDefinition(
        name = name,
        title = name,
        description = "test tool $name",
        moduleId = "vflow.test.$name",
        moduleDisplayName = name,
        inputSchema = JsonObject(emptyMap()),
        permissionNames = emptyList(),
        riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
        usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
    )

    /** 只实现**必需**成员的宿主：用来验证两个默认实现真的存在且行为正确。 */
    private class MinimalHost : AgentHost {
        override val toolNames: Set<String> = emptySet()
        override val maxRounds: Int? = null
        override val observationMode: ObservationMode = ObservationMode.NODE_TREE
        override val promptPlan: AgentPromptPlan = WorkflowPromptPlan("")
        override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore()
        override suspend fun onProgress(update: AgentProgressUpdate) = Unit
    }

    private fun outcome(
        text: String,
        stoppedBy: StopReason,
        rounds: Int = 1,
        success: Boolean = stoppedBy != StopReason.ERROR,
    ) = AgentOutcome(
        finalText = text,
        success = success,
        roundsUsed = rounds,
        stoppedBy = stoppedBy,
        referencedArtifacts = emptyList(),
    )

    // ------------------------------------------------------------ 1. 接口形状

    /**
     * **8 个成员，一个不多一个不少**（D11：一个接口、不拆 ISP）。
     *
     * 用反射而不是「能不能编译」：成员被删掉/改名时，「调用点仍能编译」是拦不住的
     * （默认实现会让少一个成员也照常编译）。
     */
    @Test
    fun interface_declaresExactlyTheEightContractMembers() {
        // ⚠️ 必须滤掉编译器为「suspend 默认实现」生成的合成成员
        // （`onFinish$suspendImpl` / `access$requestApproval$jd` 之类）——
        // 它们不是契约成员，但会出现在 `declaredMethods` 里。
        val names = AgentHost::class.java.declaredMethods
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        val expected = setOf(
            "getToolNames",
            "getMaxRounds",
            "getObservationMode",
            "getPromptPlan",
            "onProgress",
            "requestApproval",
            "getArtifacts",
            "onFinish",
        )
        assertEquals(
            "AgentHost 的成员集合与 §3.11 的 8 成员契约不一致（多了/少了都会让「取消不入接口」等边界失效）",
            expected,
            names,
        )
    }

    /** 默认 `requestApproval` = `Approved`（工作流宿主不需要覆写，D12）。 */
    @Test
    fun interface_requestApprovalDefaultsToApproved() {
        val decision = runBlocking {
            MinimalHost().requestApproval(
                calls = listOf(ChatToolCall(id = "c1", name = "vflow_agent_tap", argumentsJson = "{}")),
                riskLevel = ChatAgentToolRiskLevel.HIGH,
            )
        }
        assertEquals(ApprovalDecision.Approved, decision)
    }

    /** 默认 `onFinish` 是空实现（chat 的收尾由宿主自己的驱动路径负责）。 */
    @Test
    fun interface_onFinishDefaultsToNoOp() {
        runBlocking {
            MinimalHost().onFinish(outcome("done", StopReason.FINISHED))
        }
    }

    // ------------------------------------------------------------ 2. 提示词分段（D13 / D16）

    @Test
    fun promptSegments_areNonEmpty() {
        assertTrue("公共段不得为空", SHARED_RULES.isNotBlank())
        assertTrue("工作流专属段不得为空", WORKFLOW_RULES.isNotBlank())
        assertTrue("工作流专属段必须含 19 条领域规则的开头", WORKFLOW_RULES.contains("# 必须遵循的规则："))
        assertTrue("工作流专属段必须含最后一条（19 条逐字复制）", WORKFLOW_RULES.contains("19. 播放视频、音乐时"))
    }

    /**
     * 顺序 = **前段 → 公共段 → 宿主专属段 → `# User Goal`**。
     * 规则段在**后**（后 = 优先级更高，D13）。
     */
    @Test
    fun workflowPromptPlan_ordersPrefixSharedRulesThenUserGoal() {
        val built = WorkflowPromptPlan("可配前段").build(
            AgentPromptContext(tools = emptyList(), instruction = "点一杯咖啡"),
        )

        val prefixIndex = built.indexOf("可配前段")
        val sharedIndex = built.indexOf(SHARED_RULES)
        val rulesIndex = built.indexOf(WORKFLOW_RULES)
        val goalIndex = built.indexOf("# User Goal")

        assertTrue("前段必须在最前，实际: $prefixIndex", prefixIndex == 0)
        assertTrue("公共段必须在前段之后", sharedIndex > prefixIndex)
        assertTrue("宿主专属段必须在公共段之后（规则段优先级更高）", rulesIndex > sharedIndex)
        assertTrue("`# User Goal` 必须在最后", goalIndex > rulesIndex)
        assertTrue(
            "任务目标必须原样出现在 `# User Goal` 里",
            built.contains("# User Goal\n\"点一杯咖啡\""),
        )
    }

    @Test
    fun workflowPromptPlan_omitsBlankPrefixAndBlankInstruction() {
        val built = WorkflowPromptPlan("   ").build(
            AgentPromptContext(tools = emptyList(), instruction = "  "),
        )
        assertFalse("空前段不得留下空段/空行", built.startsWith("\n"))
        assertTrue("空前段 ⇒ 公共段打头", built.startsWith(SHARED_RULES))
        assertFalse("空目标不得产出 `# User Goal` 段", built.contains("# User Goal"))
    }

    /**
     * **chat 的提示词逐字委托上游**（不复制文案）——
     * 这是「ChatAgent 行为逐条不变」在提示词维度的直接证据。
     */
    @Test
    fun chatPromptPlan_delegatesToTheUpstreamRouterVerbatim() {
        val tools = listOf(tool("vflow_agent_observe_ui"), tool("vflow_agent_tap"))
        val plan = ChatPromptPlan { "SYS-PROMPT" }

        val fromPlan = plan.build(AgentPromptContext(tools = tools))
        val fromRouter = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "SYS-PROMPT",
            skillSelection = ChatAgentSkillSelection(tools),
        )

        assertEquals("ChatPromptPlan 必须与上游 router 的输出逐字相等", fromRouter, fromPlan)
    }

    /** chat 的 base prompt **仍然可配**（§3.6.3）——前段由宿主现读，不是常量。 */
    @Test
    fun chatPromptPlan_readsThePrefixLive() {
        var prefix = "FIRST"
        val plan = ChatPromptPlan { prefix }
        val tools = listOf(tool("vflow_agent_tap"))

        val first = plan.build(AgentPromptContext(tools = tools))
        prefix = "SECOND"
        val second = plan.build(AgentPromptContext(tools = tools))

        assertTrue("第一次必须用当时的值", first.contains("FIRST"))
        assertTrue("改了配置后必须用新值（活引用）", second.contains("SECOND"))
        assertFalse("旧值不得残留", second.contains("FIRST"))
    }

    // ------------------------------------------------------------ 3. chat 宿主

    private fun chatHost(scope: () -> ChatToolAutoApprovalScope) = ChatHost(
        debugLabel = "conv-1",
        preset = ChatPresetConfig(systemPrompt = "SYS-PROMPT"),
        artifacts = ChatAgentArtifactStore(),
        toolNames = setOf("vflow_agent_tap"),
        autoApprovalScope = scope,
    )

    @Test
    fun chatHost_keepsChatsCurrentDefaults() {
        val host = chatHost { ChatToolAutoApprovalScope.OFF }
        assertEquals("chat 的白名单 = 注册表全量（构造时传入的集合）", setOf("vflow_agent_tap"), host.toolNames)
        assertNull("D17-④：chat 维持现状零上限", host.maxRounds)
        assertEquals("chat 的观察由工具驱动，宿主不自动装配截图", ObservationMode.NODE_TREE, host.observationMode)
        assertEquals("conv-1", host.debugLabel)
    }

    /**
     * 审批三态：`ALL` ⇒ Approved；`OFF` ⇒ Deferred；`READ_ONLY` + `LOW` ⇒ Deferred；
     * `READ_ONLY` + `READ_ONLY` ⇒ Approved。判据与改造前 `shouldAutoApproveToolCalls` 相同。
     */
    @Test
    fun chatHost_requestApprovalFollowsTheConfiguredScope() {
        val call = listOf(ChatToolCall(id = "c1", name = "vflow_agent_tap", argumentsJson = "{}"))

        assertEquals(
            ApprovalDecision.Approved,
            runBlocking { chatHost { ChatToolAutoApprovalScope.ALL }.requestApproval(call, ChatAgentToolRiskLevel.HIGH) },
        )
        assertEquals(
            ApprovalDecision.Deferred,
            runBlocking { chatHost { ChatToolAutoApprovalScope.OFF }.requestApproval(call, ChatAgentToolRiskLevel.READ_ONLY) },
        )
        assertEquals(
            ApprovalDecision.Deferred,
            runBlocking { chatHost { ChatToolAutoApprovalScope.READ_ONLY }.requestApproval(call, ChatAgentToolRiskLevel.LOW) },
        )
        assertEquals(
            ApprovalDecision.Approved,
            runBlocking { chatHost { ChatToolAutoApprovalScope.READ_ONLY }.requestApproval(call, ChatAgentToolRiskLevel.READ_ONLY) },
        )
    }

    /** 档位是**活引用**：用户在界面上改了档位，下一次审批立刻生效。 */
    @Test
    fun chatHost_readsTheScopeLive() {
        var scope = ChatToolAutoApprovalScope.OFF
        val host = chatHost { scope }
        val call = listOf(ChatToolCall(id = "c1", name = "vflow_agent_tap", argumentsJson = "{}"))

        assertEquals(
            ApprovalDecision.Deferred,
            runBlocking { host.requestApproval(call, ChatAgentToolRiskLevel.READ_ONLY) },
        )
        scope = ChatToolAutoApprovalScope.ALL
        assertEquals(
            ApprovalDecision.Approved,
            runBlocking { host.requestApproval(call, ChatAgentToolRiskLevel.READ_ONLY) },
        )
    }

    // ------------------------------------------------------------ 4. 工作流宿主

    private val allToolNames = setOf(
        CHAT_TEMPORARY_WORKFLOW_TOOL_NAME,
        CHAT_SAVE_WORKFLOW_TOOL_NAME,
        CHAT_GET_WORKFLOW_TOOL_NAME,
        CHAT_UPDATE_WORKFLOW_TOOL_NAME,
        CHAT_LIST_WORKFLOWS_TOOL_NAME,
        CHAT_LOAD_SKILL_TOOL_NAME,
        "vflow_agent_observe_ui",
        "vflow_agent_tap",
    )

    @Test
    fun workflowExcludedToolNames_areExactlyTheSixWorkflowReadWriteTools() {
        assertEquals(
            "排除集必须是那 6 个工作流读写/技能类工具（且取自既有常量，不是字面量）",
            setOf(
                CHAT_TEMPORARY_WORKFLOW_TOOL_NAME,
                CHAT_SAVE_WORKFLOW_TOOL_NAME,
                CHAT_GET_WORKFLOW_TOOL_NAME,
                CHAT_UPDATE_WORKFLOW_TOOL_NAME,
                CHAT_LIST_WORKFLOWS_TOOL_NAME,
                CHAT_LOAD_SKILL_TOOL_NAME,
            ),
            WORKFLOW_EXCLUDED_TOOL_NAMES,
        )
    }

    @Test
    fun workflowHost_whitelistDropsWorkflowToolsButKeepsTheRest() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        assertEquals(
            "工作流宿主不得下发工作流读写类工具，但屏幕 helper 必须留下",
            setOf("vflow_agent_observe_ui", "vflow_agent_tap"),
            host.toolNames,
        )
    }

    @Test
    fun workflowHost_keepsTheWorkflowSideDefaults() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        assertEquals("D17-④：工作流侧保留硬上限，默认 = AgentModule.max_steps", 15, host.maxRounds)
        assertEquals("D14：默认 both（保持 AgentModule 现状）", ObservationMode.BOTH, host.observationMode)
    }

    @Test
    fun workflowHost_isAlwaysApproved() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        assertEquals(
            "D12：工作流宿主无 UI 可交互 ⇒ 恒 Approved（不覆写接口默认实现）",
            ApprovalDecision.Approved,
            runBlocking {
                host.requestApproval(
                    calls = listOf(ChatToolCall(id = "c1", name = "vflow_agent_tap", argumentsJson = "{}")),
                    riskLevel = ChatAgentToolRiskLevel.HIGH,
                )
            },
        )
    }

    @Test
    fun workflowHost_artifactsIsOneExecutionScopedInstance() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        assertSame(
            "执行级 store：同一个宿主实例必须始终返回同一个 store（否则句柄传不下去）",
            host.artifacts,
            host.artifacts,
        )
        assertNotNull(host.artifacts)
    }

    @Test
    fun workflowHost_forwardsNoticesToTheProgressSink() {
        val messages = mutableListOf<String>()
        val host = WorkflowAgentHost(
            allToolNames = allToolNames,
            progressSink = { update -> messages += update.message },
        )
        runBlocking {
            host.onProgress(AgentProgressUpdate.Notice("观察中…"))
            host.onProgress(AgentProgressUpdate.Streaming("正文", null))
        }
        assertEquals("Notice 必须转成 ProgressUpdate；Streaming 在工作流侧无意义", listOf("观察中…"), messages)
    }

    // ------------------------------------------------------------ 5. toExecutionResult 三条映射

    @Test
    fun workflowHost_mapsFinishedToSuccess() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        runBlocking { host.onFinish(outcome("任务完成", StopReason.FINISHED, rounds = 3)) }

        val result = host.toExecutionResult()
        assertTrue("FINISHED ⇒ Success", result is ExecutionResult.Success)
        val outputs = (result as ExecutionResult.Success).outputs
        assertEquals("任务完成", (outputs["result"] as VString).raw)
        assertEquals(true, (outputs["success"] as VBoolean).raw)
    }

    @Test
    fun workflowHost_mapsMaxRoundsToTheAgentModuleShapedResult() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        runBlocking { host.onFinish(outcome("半途收尾", StopReason.MAX_ROUNDS, rounds = 15)) }

        val result = host.toExecutionResult()
        assertTrue(
            "D18 未定案 ⇒ 照抄 AgentModule 现状：到顶是 Success + success=false，不是 Failure",
            result is ExecutionResult.Success,
        )
        val outputs = (result as ExecutionResult.Success).outputs
        assertEquals(
            "⚠️ 返工（父会话 Q4 裁决）：到顶的正文用**模型的收尾总结**（finalText），不再是固定文案",
            "半途收尾",
            (outputs["result"] as VString).raw,
        )
        assertEquals(false, (outputs["success"] as VBoolean).raw)
    }

    @Test
    fun workflowHost_fallsBackToTheFixedTextWhenTheWrapUpSummaryIsBlank() {
        val host = WorkflowAgentHost(allToolNames = allToolNames, maxRounds = 15)
        runBlocking { host.onFinish(outcome("   ", StopReason.MAX_ROUNDS, rounds = 15)) }

        val outputs = (host.toExecutionResult() as ExecutionResult.Success).outputs
        assertEquals(
            "收尾轮没产出文本（空白）时才回退固定文案 —— 固定文案是**兜底**，不是默认",
            "Task stopped: Max rounds (15) reached.",
            (outputs["result"] as VString).raw,
        )
        assertEquals(false, (outputs["success"] as VBoolean).raw)
    }

    @Test
    fun workflowHost_mapsErrorToFailure() {
        val host = WorkflowAgentHost(allToolNames = allToolNames)
        runBlocking { host.onFinish(outcome("崩了", StopReason.ERROR)) }

        val result = host.toExecutionResult()
        assertTrue("ERROR ⇒ Failure", result is ExecutionResult.Failure)
        assertEquals("崩了", (result as ExecutionResult.Failure).errorMessage)
    }

    @Test
    fun workflowHost_withoutOutcomeReportsFailure() {
        val result = WorkflowAgentHost(allToolNames = allToolNames).toExecutionResult()
        assertTrue("没有结论时必须报失败，不得伪装成成功", result is ExecutionResult.Failure)
    }
}
