package com.chaomixian.vflow.ui.chat

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **引擎的工具白名单 + 审批判据 + 工具调度接缝**（fork：AI 能力统一 §4.3 / D12）。
 *
 * 引擎**零 `Context` 依赖**（工具执行器经 [AgentToolRunner] 注入、全量工具表经构造参数注入）
 * ⇒ 这三组「本来只能靠源码扫描」的逻辑在这里是**真单测**。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 `availableTools` 改成直接 `return allTools`（去掉白名单过滤）⇒
 *    [availableTools_filtersByTheHostWhitelist] 与 [availableTools_keepsRegistryOrder] **变红**；
 * 2. 把 `decideApproval` 改成恒返回 `Approved`（绕过 `host.requestApproval`）⇒
 *    [decideApproval_asksTheHostWithTheBatchRiskLevel] 与
 *    [decideApproval_followsTheChatHostScope] **变红**；
 * 3. 把 `prepareToolBatch` / `executeToolBatch` 里的 `host.artifacts` 换成新建的 store ⇒
 *    [toolScheduling_passesTheHostArtifactStoreThrough] **变红**（句柄会传不下去）。
 */
class AgentEngineToolSchedulingTest {

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

    private val allTools = listOf(tool("tool_a"), tool("tool_b"), tool("tool_c"))

    private class RecordingRunner(
        private val riskLevel: ChatAgentToolRiskLevel = ChatAgentToolRiskLevel.STANDARD,
    ) : AgentToolRunner {
        var prepareArtifacts: ChatAgentArtifactStore? = null
        var executeArtifacts: ChatAgentArtifactStore? = null
        var executeCount = 0
        var rejectedCount = 0
        var permissionRequiredCount = 0
        var preparedToolCallNames: List<String> = emptyList()

        override fun prepare(
            toolCalls: List<ChatToolCall>,
            artifacts: ChatAgentArtifactStore,
        ): ChatPreparedToolBatch {
            prepareArtifacts = artifacts
            preparedToolCallNames = toolCalls.map { it.name }
            return ChatPreparedToolBatch(
                items = emptyList(),
                missingPermissions = emptyList(),
                riskLevel = riskLevel,
            )
        }

        override suspend fun execute(
            batch: ChatPreparedToolBatch,
            artifacts: ChatAgentArtifactStore,
        ): List<ChatToolResult> {
            executeArtifacts = artifacts
            executeCount++
            return emptyList()
        }

        override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> {
            rejectedCount++
            return emptyList()
        }

        override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> {
            permissionRequiredCount++
            return emptyList()
        }
    }

    /** 记录「引擎问了宿主什么」的假宿主。 */
    private class RecordingHost(
        override val toolNames: Set<String>,
        override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore(),
        private val decision: ApprovalDecision = ApprovalDecision.Approved,
        override val maxRounds: Int? = null,
        override val observationMode: ObservationMode = ObservationMode.NODE_TREE,
    ) : AgentHost {
        var askedRiskLevels = mutableListOf<ChatAgentToolRiskLevel>()
        var askedCallNames = mutableListOf<List<String>>()
        override val promptPlan: AgentPromptPlan = WorkflowPromptPlan("")

        override suspend fun onProgress(update: AgentProgressUpdate) = Unit

        override suspend fun requestApproval(
            calls: List<ChatToolCall>,
            riskLevel: ChatAgentToolRiskLevel,
        ): ApprovalDecision {
            askedRiskLevels += riskLevel
            askedCallNames += calls.map { it.name }
            return decision
        }
    }

    private fun engine(runner: AgentToolRunner) = ChatAgentEngine(
        chatClient = ChatCompletionClient(),
        allTools = allTools,
        toolRunner = runner,
    )

    private fun call(name: String) = ChatToolCall(id = "call_1", name = name, argumentsJson = "{}")

    // ------------------------------------------------------------ 1. 白名单

    @Test
    fun availableTools_filtersByTheHostWhitelist() {
        val host = RecordingHost(toolNames = setOf("tool_b"))
        assertEquals(
            "只应下发白名单里的工具",
            listOf("tool_b"),
            engine(RecordingRunner()).availableTools(host).map { it.name },
        )
    }

    /** **保序**：过滤后顺序必须与注册表顺序一致（否则请求体的 tools 数组会变）。 */
    @Test
    fun availableTools_keepsRegistryOrder() {
        val host = RecordingHost(toolNames = setOf("tool_c", "tool_a"))
        assertEquals(
            "白名单是 Set（无序）⇒ 顺序只能来自全量表，必须保序",
            listOf("tool_a", "tool_c"),
            engine(RecordingRunner()).availableTools(host).map { it.name },
        )
    }

    /**
     * **chat 不变量的核心**：白名单 = 全量时，过滤必须**恒等**
     * （否则「chat 行为逐条不变」当场失效）。
     */
    @Test
    fun availableTools_isIdentityForTheFullWhitelist() {
        val host = RecordingHost(toolNames = allTools.map { it.name }.toSet())
        assertEquals(
            "全量白名单 ⇒ 与全量表逐元素相同",
            allTools,
            engine(RecordingRunner()).availableTools(host),
        )
    }

    // ------------------------------------------------------------ 2. 审批判据

    @Test
    fun decideApproval_asksTheHostWithTheBatchRiskLevel() {
        val runner = RecordingRunner(riskLevel = ChatAgentToolRiskLevel.HIGH)
        val host = RecordingHost(toolNames = setOf("tool_a"), decision = ApprovalDecision.Deferred)

        val decision = runBlocking {
            engine(runner).decideApproval(host, listOf(call("tool_a"), call("tool_b")))
        }

        assertEquals(
            "riskLevel 必须取自 prepareBatch 的结果（批内 max），不是别的来源",
            listOf(ChatAgentToolRiskLevel.HIGH),
            host.askedRiskLevels,
        )
        assertEquals("引擎必须把整批调用交给宿主", listOf(listOf("tool_a", "tool_b")), host.askedCallNames)
        assertEquals("引擎不得自己下结论，必须用宿主的返回值", ApprovalDecision.Deferred, decision)
    }

    /**
     * 判据 × 风险组合（D12 / 现状口径）：
     * `ALL` ⇒ Approved；`OFF` ⇒ Deferred；`READ_ONLY` + `LOW` ⇒ Deferred。
     */
    @Test
    fun decideApproval_followsTheChatHostScope() {
        fun chatHostFor(scope: ChatToolAutoApprovalScope) = ChatHost(
            debugLabel = "conv",
            preset = null,
            artifacts = ChatAgentArtifactStore(),
            toolNames = allTools.map { it.name }.toSet(),
            autoApprovalScope = { scope },
        )

        fun decide(scope: ChatToolAutoApprovalScope, risk: ChatAgentToolRiskLevel): ApprovalDecision {
            val runner = RecordingRunner(riskLevel = risk)
            return runBlocking {
                engine(runner).decideApproval(chatHostFor(scope), listOf(call("tool_a")))
            }
        }

        assertEquals(ApprovalDecision.Approved, decide(ChatToolAutoApprovalScope.ALL, ChatAgentToolRiskLevel.HIGH))
        assertEquals(ApprovalDecision.Deferred, decide(ChatToolAutoApprovalScope.OFF, ChatAgentToolRiskLevel.READ_ONLY))
        assertEquals(ApprovalDecision.Deferred, decide(ChatToolAutoApprovalScope.READ_ONLY, ChatAgentToolRiskLevel.LOW))
        assertEquals(ApprovalDecision.Approved, decide(ChatToolAutoApprovalScope.READ_ONLY, ChatAgentToolRiskLevel.READ_ONLY))
        assertEquals(ApprovalDecision.Deferred, decide(ChatToolAutoApprovalScope.STANDARD, ChatAgentToolRiskLevel.HIGH))
    }

    // ------------------------------------------------------------ 3. 工具调度接缝

    @Test
    fun toolScheduling_passesTheHostArtifactStoreThrough() {
        val runner = RecordingRunner()
        val host = RecordingHost(toolNames = setOf("tool_a"))
        val chatEngine = engine(runner)

        val batch = chatEngine.prepareToolBatch(host, listOf(call("tool_a")))
        assertSame(
            "prepare 必须吃 host.artifacts（会话级/执行级 store 的唯一来源）",
            host.artifacts,
            runner.prepareArtifacts,
        )
        assertEquals(listOf("tool_a"), runner.preparedToolCallNames)

        runBlocking { chatEngine.executeToolBatch(host, batch) }
        assertSame("execute 必须吃同一个 host.artifacts", host.artifacts, runner.executeArtifacts)
        assertEquals(1, runner.executeCount)
    }

    @Test
    fun toolScheduling_routesRejectedAndPermissionResultsThroughTheRunner() {
        val runner = RecordingRunner()
        val host = RecordingHost(toolNames = setOf("tool_a"))
        val chatEngine = engine(runner)

        val batch = chatEngine.prepareToolBatch(host, listOf(call("tool_a")))
        chatEngine.buildRejectedResults(host, listOf(call("tool_a")))
        chatEngine.buildPermissionRequiredResults(host, batch)

        assertEquals(1, runner.rejectedCount)
        assertEquals(1, runner.permissionRequiredCount)
    }

    @Test
    fun buildSystemPrompt_usesTheHostPromptPlan() {
        val host = RecordingHost(toolNames = setOf("tool_a"))
        val chatEngine = engine(RecordingRunner())

        val withInstruction = chatEngine.buildSystemPrompt(host, allTools, instruction = "目标")
        assertTrue("必须走 host.promptPlan（工作流前段 + 公共段 + 专属段）", withInstruction.contains(SHARED_RULES))
        assertTrue("instruction 必须进 `# User Goal`", withInstruction.contains("# User Goal\n\"目标\""))

        val withoutInstruction = chatEngine.buildSystemPrompt(host, allTools)
        assertFalse(
            "instruction 为 null ⇒ 不得产出 `# User Goal` 段",
            withoutInstruction.contains("# User Goal"),
        )
    }
}
