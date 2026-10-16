package com.chaomixian.vflow.ui.chat

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **一次 LLM 往返（T4）**：流式累积、重试上报、工具 id 兜底、线程契约。
 *
 * ⚠️ 全部走**真实 HTTP**（MockWebServer + okhttp-sse 分帧），不是直接调归约器 ——
 * 否则分帧层的行为就没人测了。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 `runRound` 里的 `tools.map { it.toChatToolSchema() }` 换成 `null`（改回由
 *    `skillSelection` 适配）⇒ [runRound_stillSendsTheTools] **变红**；
 * 2. 去掉 id 兜底 ⇒ [runRound_fallsBackToTheProvidedToolCallIdPrefix] **变红**；
 * 3. 把 `onRetry` 里的 `retriesUsed` 赋值删掉 ⇒ [runRound_reportsRetriesAndCountsThem] **变红**；
 * 4. 给 `runRound` 包一层 `withContext(Dispatchers.IO)` ⇒
 *    [runRound_deliversProgressOnTheCallersThread] **变红**（这是 §3.7.8 那类真回归的守卫）。
 */
class AgentEngineRoundTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ------------------------------------------------------------ 装置

    private fun tool(name: String) = ChatAgentToolDefinition(
        name = name,
        title = name,
        description = "test tool $name",
        moduleId = "vflow.test.$name",
        moduleDisplayName = name,
        inputSchema = kotlinx.serialization.json.JsonObject(emptyMap()),
        permissionNames = emptyList(),
        riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
        usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
    )

    private val allTools = listOf(tool("vflow_agent_observe_ui"), tool("vflow_agent_tap"))

    private fun preset(
        streamIdleTimeoutSeconds: Int = ChatPresetConfig.DEFAULT_STREAM_IDLE_TIMEOUT_SECONDS,
        streamMaxRetries: Int = 0,
    ) = ChatPresetConfig(
        provider = ChatProvider.OPENAI.storageValue,
        model = "test-model",
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        systemPrompt = "SYS-PROMPT",
        streamIdleTimeoutSeconds = streamIdleTimeoutSeconds,
        streamMaxRetries = streamMaxRetries,
    )

    private class RecordingHost(
        override val toolNames: Set<String>,
        override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore(),
    ) : AgentHost {
        val progress = mutableListOf<AgentProgressUpdate>()
        override val maxRounds: Int? = null
        override val observationMode: ObservationMode = ObservationMode.NODE_TREE
        // 用 **chat 侧**的 promptPlan：本文件验的是 chat 的驱动路径（`runRound`）。
        override val promptPlan: AgentPromptPlan = ChatPromptPlan { "SYS-PROMPT" }
        override suspend fun onProgress(update: AgentProgressUpdate) {
            progress += update
        }
    }

    private fun sse(vararg lines: String): MockResponse = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString(separator = ""))

    private fun openAiTextStream() = sse(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"\"},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hello\"},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\" world\"},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
        "data: [DONE]\n\n",
    )

    /** 工具调用**不带 id**（某些 OpenAI 兼容网关会这样）⇒ 必须落到引擎的兜底规则。 */
    private fun openAiToolCallStreamWithoutId() = sse(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"type\":\"function\",\"function\":{\"name\":\"vflow_agent_tap\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        "data: [DONE]\n\n",
    )

    private fun request(host: AgentHost, preset: ChatPresetConfig = preset()) = AgentRoundRequest(
        preset = preset,
        history = listOf(
            ChatMessage(id = "m1", role = ChatMessageRole.USER, content = "hi", timestampMillis = 1L),
        ),
        toolCallIdPrefix = "call_conv_msg_",
    )

    private fun engine() = ChatAgentEngine(
        chatClient = ChatCompletionClient(),
        allTools = allTools,
        toolRunner = object : AgentToolRunner {
            override fun prepare(toolCalls: List<ChatToolCall>, artifacts: ChatAgentArtifactStore) =
                ChatPreparedToolBatch(emptyList(), emptyList(), ChatAgentToolRiskLevel.READ_ONLY)

            override suspend fun execute(
                batch: ChatPreparedToolBatch,
                artifacts: ChatAgentArtifactStore,
            ): List<ChatToolResult> = emptyList()

            override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> = emptyList()

            override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> = emptyList()
        },
    )

    /**
     * 取请求体里 **`tools` 数组**的工具名。
     *
     * ⚠️ 不能对整段 body 做 `contains("vflow_agent_observe_ui")` ——
     * chat 的规则段**本来就提到**这个工具名（「用 `vflow_agent_observe_ui` 做只读观察」），
     * 那种断言恒真。
     */
    private fun toolNamesIn(rawBody: String): List<String> =
        Json.parseToJsonElement(rawBody).jsonObject["tools"]!!.jsonArray
            .map { it.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content }

    // ------------------------------------------------------------ 1. 流式累积

    @Test
    fun runRound_reportsCumulativeContentAndFinishesWithTheAuthoritativeResult() = runBlocking {
        server.enqueue(openAiTextStream())
        val host = RecordingHost(toolNames = allTools.map { it.name }.toSet())

        val result = engine().runRound(host, request(host))

        assertEquals("Hello world", result.completion.content)
        assertEquals("过程值必须等于完整正文", "Hello world", result.streamedContent)
        assertTrue("工具调用不得凭空出现", result.toolCalls.isEmpty())
        assertEquals(0, result.retriesUsed)

        val streaming = host.progress.filterIsInstance<AgentProgressUpdate.Streaming>()
        assertTrue("必须逐 delta 上报进度（否则界面不会流式增长）", streaming.size >= 2)
        assertEquals(
            "最后一次上报必须是**完整**正文（累积口径）",
            "Hello world",
            streaming.last().content,
        )
        assertTrue(
            "累积口径：后一次必须以前一次为前缀",
            streaming.last().content.startsWith(streaming.first().content),
        )
    }

    /** 工具 schema 必须真的进请求（否则模型看不到工具）。 */
    @Test
    fun runRound_stillSendsTheTools() = runBlocking {
        server.enqueue(openAiTextStream())
        val host = RecordingHost(toolNames = allTools.map { it.name }.toSet())

        engine().runRound(host, request(host))

        val body = server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
        assertEquals(
            "tools 数组必须逐字等于白名单（保序）",
            listOf("vflow_agent_observe_ui", "vflow_agent_tap"),
            toolNamesIn(body),
        )
        assertTrue(
            "system prompt 必须是引擎拼出来的那份（含规则段）",
            body.contains("Only use the tools exposed below."),
        )
    }

    // ------------------------------------------------------------ 2. 工具 id 兜底

    @Test
    fun runRound_fallsBackToTheProvidedToolCallIdPrefix() = runBlocking {
        server.enqueue(openAiToolCallStreamWithoutId())
        val host = RecordingHost(toolNames = allTools.map { it.name }.toSet())

        val result = engine().runRound(host, request(host))

        assertEquals(1, result.toolCalls.size)
        assertEquals(
            "缺 id 时必须按 `前缀 + 序号` 生成（与改造前 `call_<会话>_<消息>_<序号>` 同规则）",
            "call_conv_msg_0",
            result.toolCalls[0].id,
        )
        assertEquals("vflow_agent_tap", result.toolCalls[0].name)
    }

    // ------------------------------------------------------------ 3. 重试上报（含 retriesUsed）

    @Test
    fun runRound_reportsRetriesAndCountsThem() = runBlocking {
        // 首条：连上了但一个字节都不发 ⇒ 空闲超时（可重试）。
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        server.enqueue(openAiTextStream())

        val host = RecordingHost(toolNames = allTools.map { it.name }.toSet())
        val result = engine().runRound(
            host,
            request(host, preset(streamIdleTimeoutSeconds = 1, streamMaxRetries = 1)),
        )

        val retries = host.progress.filterIsInstance<AgentProgressUpdate.Retry>()
        assertEquals("必须上报一次重试（chat 用它弹提示 + 记 retriesUsed）", 1, retries.size)
        assertEquals(1, retries.first().notice.attempt)
        assertEquals("重试次数必须回到调用方（失败文案要带上它）", 1, result.retriesUsed)
        assertEquals("重试成功后本轮应当正常拿到结果", "Hello world", result.completion.content)
    }

    // ------------------------------------------------------------ 4. 线程契约（§3.7.8 的守卫）

    /**
     * **收集必须留在调用方线程上**。
     *
     * chat 的调用方是 `viewModelScope.launch`（Main），而 `host.onProgress` 会写
     * `_uiState`（UI 状态）。引擎若自作主张包一层 `withContext(Dispatchers.IO)`，
     * 进度回调就会跑到 IO 线程上 ⇒ 真正的跨线程写 UI 状态。
     *
     * ⚠️ 防假绿：同时**正向**断言调用方协程确实被钉在单线程 dispatcher 上，
     * 且确实收到过进度回调。
     */
    @Test
    fun runRound_deliversProgressOnTheCallersThread() = runBlocking {
        server.enqueue(openAiTextStream())
        val progressThreads = mutableListOf<String>()
        val host = object : AgentHost {
            override val toolNames: Set<String> = allTools.map { it.name }.toSet()
            override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore()
            override val maxRounds: Int? = null
            override val observationMode: ObservationMode = ObservationMode.NODE_TREE
            override val promptPlan: AgentPromptPlan = WorkflowPromptPlan("")
            override suspend fun onProgress(update: AgentProgressUpdate) {
                progressThreads += Thread.currentThread().name
            }
        }

        val callerDispatcher = Executors
            .newSingleThreadExecutor { runnable -> Thread(runnable, CALLER_THREAD_NAME) }
            .asCoroutineDispatcher()
        try {
            var callerThreadName: String? = null
            val result = withContext(callerDispatcher) {
                callerThreadName = Thread.currentThread().name
                engine().runRound(host, request(host))
            }

            assertTrue(
                "测试前提被破坏：调用方协程没有跑在单线程 dispatcher 上（实际：$callerThreadName）",
                callerThreadName.orEmpty().startsWith(CALLER_THREAD_NAME),
            )
            assertEquals("请求必须真的走通，否则本用例没有意义", "Hello world", result.completion.content)
            assertTrue("必须真的收到过进度回调（否则下面的断言在空转）", progressThreads.isNotEmpty())
            progressThreads.forEach { name ->
                assertTrue(
                    "收集必须留在调用方线程上 —— 引擎不得包 withContext(Dispatchers.IO)（实际：$name）",
                    name.startsWith(CALLER_THREAD_NAME),
                )
            }
        } finally {
            callerDispatcher.close()
        }
    }

    // ------------------------------------------------------------ 5. 工具白名单真的生效于请求

    @Test
    fun runRound_onlySendsWhitelistedTools() = runBlocking {
        server.enqueue(openAiTextStream())
        val host = RecordingHost(toolNames = setOf("vflow_agent_tap"))

        engine().runRound(host, request(host))

        val body = server.takeRequest(3, TimeUnit.SECONDS)!!.body.readUtf8()
        assertEquals(
            "只有白名单里的工具能进 tools 数组（白名单是本任务唯一的安全边界）",
            listOf("vflow_agent_tap"),
            toolNamesIn(body),
        )
        assertNotNull(body)
    }

    private companion object {
        /** 与 `ChatCompletionThreadAffinityTest` 同款：不可能与 OkHttp/coroutines 内部线程重名。 */
        private const val CALLER_THREAD_NAME = "vflow-chat-caller-thread"
    }
}
