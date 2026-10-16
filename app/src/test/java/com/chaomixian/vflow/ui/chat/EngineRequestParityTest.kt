package com.chaomixian.vflow.ui.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **请求体逐字节不变**（fork：AI 能力统一 §4.5 的「三条保证」）。
 *
 * 这是「ChatAgent 行为逐条不变」最硬的一条证据：引擎路径与改造前路径各发一次请求，
 * 抓 MockWebServer 收到的 **HTTP body** 做 `assertEquals`（不是「大致相同」）。
 *
 * ## 被锁住的三条
 *
 * 1. **system prompt 相同** —— 引擎先算出 `S = promptPlan.build(...)`，再传
 *    `preset.copy(systemPrompt = S)` + `skillSelection = EMPTY`；客户端对 EMPTY selection
 *    直接返回 `trimmedBasePrompt` ⇒ 最终 system 消息仍是 `S`。
 * 2. **tools 数组相同** —— 引擎传显式 `tools`（非 null ⇒ 取代 selection 适配），
 *    且用的是**同一个** `toChatToolSchema()` 映射，过滤保序。
 * 3. **messages 相同** —— history 原样透传（图片装配仍在客户端内部）。
 *
 * ## ⚠️ 必做反证
 *
 * 把引擎里的 `skillSelection` 传成**非** `EMPTY`（例如 `ChatAgentSkillSelection(tools)`）⇒
 * 客户端会把规则段拼**两遍** ⇒ body 不再逐字节相等 ⇒
 * [enginePath_sendsTheSameBytesAsTheLegacyPath] **变红**。
 */
class EngineRequestParityTest {

    private lateinit var server: MockWebServer

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

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
        inputSchema = JsonObject(emptyMap()),
        permissionNames = emptyList(),
        riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
        usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
    )

    private val allTools = listOf(tool("vflow_agent_observe_ui"), tool("vflow_agent_tap"))

    private fun preset() = ChatPresetConfig(
        provider = ChatProvider.OPENAI.storageValue,
        model = "test-model",
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        systemPrompt = "You are a helpful assistant.",
        streamMaxRetries = 0,
    )

    private fun history() = listOf(
        ChatMessage(id = "m1", role = ChatMessageRole.USER, content = "看看屏幕", timestampMillis = 1L),
        ChatMessage(
            id = "m2",
            role = ChatMessageRole.ASSISTANT,
            content = "",
            timestampMillis = 2L,
            toolCalls = listOf(
                ChatToolCall(id = "call_1", name = "vflow_agent_observe_ui", argumentsJson = "{}"),
            ),
        ),
        ChatMessage(
            id = "m3",
            role = ChatMessageRole.TOOL,
            content = "observed",
            timestampMillis = 3L,
            toolResult = ChatToolResult(
                callId = "call_1",
                name = "vflow_agent_observe_ui",
                status = ChatToolResultStatus.SUCCESS,
                summary = "observed",
                outputText = "observed",
            ),
        ),
    )

    private fun sseResponse() = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(
            "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"},\"finish_reason\":null}]}\n\n" +
                "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n" +
                "data: [DONE]\n\n",
        )

    private fun takeBody(): String =
        server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()

    private class NoopRunner : AgentToolRunner {
        override fun prepare(toolCalls: List<ChatToolCall>, artifacts: ChatAgentArtifactStore) =
            ChatPreparedToolBatch(emptyList(), emptyList(), ChatAgentToolRiskLevel.READ_ONLY)

        override suspend fun execute(
            batch: ChatPreparedToolBatch,
            artifacts: ChatAgentArtifactStore,
        ): List<ChatToolResult> = emptyList()

        override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> = emptyList()

        override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> = emptyList()
    }

    private class ParityHost(
        override val toolNames: Set<String>,
        private val systemPrompt: String,
    ) : AgentHost {
        override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore()
        override val maxRounds: Int? = null
        override val observationMode: ObservationMode = ObservationMode.NODE_TREE
        override val promptPlan: AgentPromptPlan = ChatPromptPlan { systemPrompt }
        override suspend fun onProgress(update: AgentProgressUpdate) = Unit
    }

    // ------------------------------------------------------------ 主用例

    @Test
    fun enginePath_sendsTheSameBytesAsTheLegacyPath() = runBlocking {
        val preset = preset()
        val history = history()

        // ── ① 改造前形态：skillSelection = 全量工具，tools = null ──
        server.enqueue(sseResponse())
        ChatCompletionClient(httpClient = httpClient)
            .streamReply(
                preset = preset,
                history = history,
                skillSelection = ChatAgentSkillSelection(allTools),
            )
            .collect { }
        val legacyBody = takeBody()

        // ── ② 引擎形态：显式 tools + EMPTY selection ──
        server.enqueue(sseResponse())
        val engine = ChatAgentEngine(
            chatClient = ChatCompletionClient(httpClient = httpClient),
            allTools = allTools,
            toolRunner = NoopRunner(),
        )
        engine.runRound(
            host = ParityHost(toolNames = allTools.map { it.name }.toSet(), systemPrompt = preset.systemPrompt),
            request = AgentRoundRequest(
                preset = preset,
                history = history,
                toolCallIdPrefix = "call_parity_",
            ),
        )
        val engineBody = takeBody()

        // 防空转：body 必须真的有内容（否则「相等」毫无意义）
        assertTrue("请求体太短，断言在空转", legacyBody.length > 500)
        assertTrue("请求体里必须有 system prompt", legacyBody.contains("You are a helpful assistant."))
        assertTrue("请求体里必须有规则段", legacyBody.contains("Only use the tools exposed below."))
        assertTrue("请求体里必须有 tools 段", legacyBody.contains("\"tools\""))

        assertEquals(
            "引擎路径与改造前路径的 HTTP body 必须**逐字节相等**（差异：${firstDifference(legacyBody, engineBody)}）",
            legacyBody,
            engineBody,
        )
    }

    /** 失败时给出「第一个不同的位置」，比整段 diff 更好定位。 */
    private fun firstDifference(a: String, b: String): String {
        val limit = minOf(a.length, b.length)
        var i = 0
        while (i < limit && a[i] == b[i]) i++
        if (i == limit && a.length == b.length) return "无差异"
        val from = (i - 60).coerceAtLeast(0)
        return "下标 $i：改造前=…${a.substring(from, minOf(a.length, i + 60))}… " +
            "引擎=…${b.substring(from, minOf(b.length, i + 60))}…"
    }
}
