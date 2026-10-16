package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.module.interaction.agentModuleToolDefinitions
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **`tool_calls` 轮的历史协议**（fork：AI 能力统一批 4 的 ⓪ 前置修复）。
 *
 * 背景：批 3 的 `runLoop` 只把 `role:"tool"` 结果追加进 history，**没有**追加那条带
 * `tool_calls` 的 assistant 消息。真实 OpenAI 协议要求 `role:"tool"` 之前必须有对应的
 * assistant `tool_calls` 轮 ⇒ 接 `AgentModule`（批 4）时必然 400。
 *
 * 本文件用**真实传输层**（`ChatCompletionClient` + `MockWebServer`）+ **真实
 * `WorkflowAgentHost`** + **真实 `ChatAgentEngine.runLoop`** 跑通多轮工具循环，
 * 并对**实际下发的请求体**断言协议不变量 —— 这是本仓库「纯函数测试全绿 ≠ 集成点接上了」
 * 那条教训的直接应对（§3.7.8）。
 *
 * ## ⚠️ 必做反证
 *
 * 删掉 `runLoop` 里的 `+ assistantToolCallMessage(round)` ⇒
 * [secondRequest_putsAnAssistantToolCallsMessageBeforeEveryToolMessage] 变红。
 */
class AgentEngineToolCallProtocolTest {

    private lateinit var server: MockWebServer
    private var takenRequests = 0

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        takenRequests = 0
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ------------------------------------------------------------ 装置

    private val toolName = "vflow_agent_tap"

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

    private val allTools = listOf(tool(toolName))

    /**
     * 假工具执行器：结果**逐条对应**收到的 tool call（`callId` 必须回填）。
     *
     * ⚠️ `ChatPreparedToolBatch` 不携带 calls，故这里在 [prepare] 里把本批 calls 记下来，
     * 供 [execute] 逐条产出结果 —— 与生产侧 `AgentModuleToolRunner` 的做法同构。
     */
    private inner class EchoRunner : AgentToolRunner {
        private var preparedCalls: List<ChatToolCall> = emptyList()

        /** 引擎是否把 `host.artifacts`（执行级 store）透传给了 prepare / execute。 */
        var seenArtifacts: ChatAgentArtifactStore? = null
        val executedNames = mutableListOf<String>()

        override fun prepare(
            toolCalls: List<ChatToolCall>,
            artifacts: ChatAgentArtifactStore,
        ): ChatPreparedToolBatch {
            preparedCalls = toolCalls
            seenArtifacts = artifacts
            return ChatPreparedToolBatch(
                items = emptyList(),
                missingPermissions = emptyList(),
                riskLevel = ChatAgentToolRiskLevel.LOW,
            )
        }

        override suspend fun execute(
            batch: ChatPreparedToolBatch,
            artifacts: ChatAgentArtifactStore,
        ): List<ChatToolResult> = preparedCalls.map { call ->
            executedNames += call.name
            ChatToolResult(
                callId = call.id,
                name = call.name,
                status = ChatToolResultStatus.SUCCESS,
                summary = "ok",
                outputText = "TOOL-OUTPUT-${call.id}",
            )
        }

        override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> = emptyList()

        override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> = emptyList()
    }

    /**
     * 真实 `WorkflowAgentHost`（白名单 / 恒 `Approved` / 执行级 artifact store /
     * `toExecutionResult` 全部是生产实现）。
     */
    private fun host(maxRounds: Int? = null) = WorkflowAgentHost(
        allToolNames = allTools.map { it.name },
        maxRounds = maxRounds,
    )

    private fun preset() = ChatPresetConfig(
        provider = ChatProvider.OPENAI.storageValue,
        model = "test-model",
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        systemPrompt = "SYS-PROMPT",
        streamMaxRetries = 0,
    )

    private fun sse(vararg lines: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString(separator = ""))

    private fun textStream(text: String) = sse(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
        "data: [DONE]\n\n",
    )

    private fun toolCallStream(id: String, name: String = toolName) = sse(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"$id\",\"type\":\"function\",\"function\":{\"name\":\"$name\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        "data: [DONE]\n\n",
    )

    /** 观察：只要文本，不截图（本用例只关心历史协议）。 */
    private fun quietObserver() = AgentObservationProvider { _, _, _ ->
        AgentObservation(text = "UI-NODE-TREE-TEXT", image = null)
    }

    private fun loopRequest() = AgentLoopRequest(
        preset = preset(),
        task = AgentTask(instruction = "打开设置", history = emptyList()),
        observation = quietObserver(),
    )

    private fun engine() = ChatAgentEngine(
        chatClient = ChatCompletionClient(httpClient = httpClient),
        allTools = allTools,
        toolRunner = EchoRunner(),
    )

    /** 取**第 index 次**请求（0-based）的请求体（游标式，避免静默看错对象）。 */
    private fun bodyOfRequest(index: Int): String {
        var body: String? = null
        while (takenRequests <= index) {
            body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            takenRequests++
        }
        return body ?: error("第 $index 次请求已被取走")
    }

    private fun messagesOf(body: String): JSONArray =
        JSONObject(body).getJSONArray("messages")

    // ------------------------------------------------------------ 1. 协议不变量

    @Test
    fun secondRequest_putsAnAssistantToolCallsMessageBeforeEveryToolMessage() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("完成"))

        val outcome = engine().runLoop(host(), loopRequest())

        assertEquals(StopReason.FINISHED, outcome.stoppedBy)
        assertEquals("一轮工具调用 + 一轮收尾 = 两轮", 2, outcome.roundsUsed)

        val messages = messagesOf(bodyOfRequest(1))
        val assistantToolCallIndexes = mutableListOf<Int>()
        val toolIndexes = mutableListOf<Int>()
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            when (message.optString("role")) {
                "assistant" -> {
                    val calls = message.optJSONArray("tool_calls") ?: continue
                    if (calls.length() > 0) assistantToolCallIndexes += i
                }

                "tool" -> toolIndexes += i
            }
        }
        assertTrue(
            "第二轮请求里必须有 tool 消息（否则工具结果没回到模型）",
            toolIndexes.isNotEmpty(),
        )
        assertTrue(
            "第二轮请求里必须有带 tool_calls 的 assistant 消息",
            assistantToolCallIndexes.isNotEmpty(),
        )

        // ① 协议不变量：每条 role:"tool" 之前都存在一条 assistant 消息，
        //    其 tool_calls[*].id 等于该 tool 消息的 tool_call_id。
        for (toolIndex in toolIndexes) {
            val toolMessage = messages.getJSONObject(toolIndex)
            val toolCallId = toolMessage.optString("tool_call_id")
            assertTrue("tool 消息必须带 tool_call_id", toolCallId.isNotEmpty())

            val matchingIndexes = assistantToolCallIndexes.filter { assistantIndex ->
                val calls = messages.getJSONObject(assistantIndex).getJSONArray("tool_calls")
                (0 until calls.length()).any { callIndex ->
                    calls.getJSONObject(callIndex).optString("id") == toolCallId
                }
            }
            assertTrue(
                "tool 消息 (tool_call_id=$toolCallId) 之前必须有一条带同一 id 的 assistant tool_calls 消息",
                matchingIndexes.isNotEmpty(),
            )

            // ② 顺序：assistant 的 tool_calls 轮必须在 tool 消息**之前**。
            assertTrue(
                "assistant 的 tool_calls 轮必须排在 tool 消息之前（下标比较）",
                matchingIndexes.min() < toolIndex,
            )
        }
    }

    // ------------------------------------------------------------ 2. 第一轮没有 tool 消息

    @Test
    fun firstRequest_hasNoToolMessage() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("完成"))

        engine().runLoop(host(), loopRequest())

        val messages = messagesOf(bodyOfRequest(0))
        for (i in 0 until messages.length()) {
            assertFalse(
                "第一轮请求里不得有 role:\"tool\"（此时还没有任何工具结果）",
                messages.getJSONObject(i).optString("role") == "tool",
            )
        }
    }

    // ------------------------------------------------------------ 3. 收尾轮不得有悬挂 tool_calls

    @Test
    fun wrapUpRequest_hasNoDanglingToolCalls() = runBlocking {
        // maxRounds = 1 ⇒ 第 1 轮的 tool_calls **不执行**（D17-② 只取文本收尾）
        // ⇒ 收尾请求里不得出现「有 tool_calls 却没有对应 tool 响应」的悬挂调用。
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("收尾总结"))

        val outcome = engine().runLoop(host(maxRounds = 1), loopRequest())

        assertEquals(StopReason.MAX_ROUNDS, outcome.stoppedBy)
        assertEquals(2, server.requestCount)

        val messages = messagesOf(bodyOfRequest(1))
        for (i in 0 until messages.length()) {
            val message = messages.getJSONObject(i)
            assertFalse(
                "收尾轮不得出现 role:\"tool\"（那一轮的 tool_calls 根本没执行）",
                message.optString("role") == "tool",
            )
            val calls = message.optJSONArray("tool_calls")
            assertTrue(
                "收尾轮不得出现悬挂的 tool_calls（有 tool_calls 无 tool 响应 ⇒ 真实 API 400）",
                calls == null || calls.length() == 0,
            )
        }
    }

    // ------------------------------------------------------------ 4. 真实传输 + AgentModule 的工具表
    //
    // 这是「`WorkflowAgentHost` 端到端第一次被验证」的另一半：用**真实**的
    // `agentModuleToolDefinitions()`（11 个工具）+ 真实传输层 + 真实 `runLoop` 跑多轮工具循环。

    private fun agentModuleTools() = agentModuleToolDefinitions()

    private fun agentModuleHost(maxRounds: Int?) = WorkflowAgentHost(
        allToolNames = agentModuleTools().map { it.name },
        maxRounds = maxRounds,
    )

    private fun engineWith(tools: List<ChatAgentToolDefinition>, runner: AgentToolRunner) =
        ChatAgentEngine(
            chatClient = ChatCompletionClient(httpClient = httpClient),
            allTools = tools,
            toolRunner = runner,
        )

    @Test
    fun realTransport_whitelistsTheWholeAgentModuleToolTableAndRunsAGestureTool() = runBlocking {
        server.enqueue(toolCallStream("call_1", name = "swipe"))
        server.enqueue(textStream("完成"))

        val host = agentModuleHost(maxRounds = 3)
        val runner = EchoRunner()
        val engine = engineWith(agentModuleTools(), runner)

        assertEquals(
            "本模块的 11 个工具必须全部通过白名单（天然不含工作流读写类）",
            11,
            engine.availableTools(host).size,
        )

        val outcome = engine.runLoop(host, loopRequest())

        assertEquals(StopReason.FINISHED, outcome.stoppedBy)
        assertEquals("手势工具必须真的被执行", listOf("swipe"), runner.executedNames)
        assertEquals("收尾正文必须被采纳", "完成", outcome.finalText)
    }

    @Test
    fun realTransport_passesTheExecutionScopedArtifactStoreToTheRunner() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("完成"))

        val host = agentModuleHost(maxRounds = 3)
        val runner = EchoRunner()
        engineWith(agentModuleTools(), runner).runLoop(host, loopRequest())

        assertSame(
            "执行级 store 必须透传给 runner（工作流宿主也要句柄，D11）",
            host.artifacts,
            runner.seenArtifacts,
        )
    }

    @Test
    fun realTransport_mapsTheFinishedOutcomeToExecutionResult() = runBlocking {
        server.enqueue(textStream("任务完成"))

        val host = agentModuleHost(maxRounds = 3)
        engineWith(agentModuleTools(), EchoRunner()).runLoop(host, loopRequest())

        val result = host.toExecutionResult()
        assertTrue("FINISHED ⇒ Success", result is ExecutionResult.Success)
        val outputs = (result as ExecutionResult.Success).outputs
        assertEquals("任务完成", (outputs["result"] as VString).raw)
        assertEquals(true, (outputs["success"] as VBoolean).raw)
    }

    @Test
    fun realTransport_runsExactlyOneWrapUpRoundAtTheHardLimit() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("收尾总结"))

        val host = agentModuleHost(maxRounds = 1)
        engineWith(agentModuleTools(), EchoRunner()).runLoop(host, loopRequest())

        assertEquals("1 轮 + 1 次收尾 = 2 次请求", 2, server.requestCount)
        val wrapUp = bodyOfRequest(1)
        assertTrue("到顶必须给一次收尾机会（D17-②）", wrapUp.contains(WRAP_UP_INSTRUCTION))

        val outputs = (host.toExecutionResult() as ExecutionResult.Success).outputs
        assertEquals("Task stopped: Max rounds (1) reached.", (outputs["result"] as VString).raw)
        assertEquals(
            "D18 未定案 ⇒ 形状照抄 AgentModule 现状（Success + success=false）",
            false,
            (outputs["success"] as VBoolean).raw,
        )
    }

    @Test
    fun realTransport_approvesWithoutAnyUserInteraction() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("完成"))

        val runner = EchoRunner()
        val host = agentModuleHost(maxRounds = 3)
        engineWith(agentModuleTools(), runner).runLoop(host, loopRequest())

        assertEquals("真实工作流宿主不覆写 requestApproval ⇒ 工具必须真的执行（D12）", 1, runner.executedNames.size)
    }
}