package com.chaomixian.vflow.ui.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **通用工具 schema 通道**（fork：AI 能力统一 §3.3 / D3-B）。
 *
 * 三件事：
 * 1. `ChatToolSchema.inputSchema` 里的**任意** JSON Schema（`oneOf` / `$defs` / 嵌套数组）
 *    在三条协议里**深等透传**；
 * 2. `tools` 非 null 时**取代**（不是追加）`skillSelection` 的工具集；
 * 3. **回归**：`tools = null` 时工具集与改造前逐字段一致（由 `skillSelection` 适配而来）。
 *
 * ## ⚠️ 必做反证
 *
 * 把三个 tool builder 改回读 `request.skillSelection.availableTools`（忽略 `request.tools`）
 * ⇒ 前四条用例**全红**（自造 schema 不会出现在请求体里）。
 */
class ChatToolSchemaChannelTest {

    private lateinit var server: MockWebServer

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

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

    /** 刻意包含 `oneOf` / `$defs` / 嵌套数组——「只认扁平的 name+type」的实现会在这里露馅。 */
    private val complexSchemaText = """
        {
          "type": "object",
          "properties": {
            "mode": {"type": "string", "oneOf": [{"const": "fast"}, {"const": "safe"}]},
            "items": {
              "type": "array",
              "items": {"type": "object", "properties": {"id": {"type": "integer"}}}
            }
          },
          "required": ["mode"],
          "${'$'}defs": {"Named": {"type": "object", "additionalProperties": false}}
        }
    """.trimIndent()

    private val complexSchema: JsonObject =
        json.parseToJsonElement(complexSchemaText).jsonObject

    private fun neutralTool(
        name: String = "custom_tool",
        schema: JsonObject = complexSchema,
        truncatable: Boolean = true,
    ) = ChatToolSchema(
        name = name,
        description = "a tool that is not an agent tool",
        inputSchema = schema,
        truncatable = truncatable,
    )

    private fun agentTool(
        name: String,
        schema: JsonObject = complexSchema,
        truncatable: Boolean = true,
    ) = ChatAgentToolDefinition(
        name = name,
        title = name,
        description = "agent tool",
        moduleId = "vflow.agent.test",
        moduleDisplayName = name,
        inputSchema = schema,
        permissionNames = emptyList(),
        riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
        usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
        truncatable = truncatable,
    )

    private fun preset(provider: ChatProvider, useResponsesApi: Boolean = false) = ChatPresetConfig(
        provider = provider.storageValue,
        model = "test-model",
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        useResponsesApi = useResponsesApi,
    )

    private fun history() = listOf(
        ChatMessage(id = "m1", role = ChatMessageRole.USER, content = "hi", timestampMillis = 1L),
    )

    private fun enqueueChatCompletionResponse() {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"ok"}}]}"""))
    }

    private fun enqueueResponsesResponse() {
        server.enqueue(
            MockResponse().setBody(
                """{"output":[{"type":"message","content":[{"type":"output_text","text":"ok"}]}]}""",
            ),
        )
    }

    private fun enqueueAnthropicResponse() {
        server.enqueue(MockResponse().setBody("""{"content":[{"type":"text","text":"ok"}]}"""))
    }

    private fun takeRequestBody(): JsonObject {
        val recorded: RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!
        return json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
    }

    private fun JsonObject.toolNames(): List<String> = this["tools"]!!.jsonArray.map { tool ->
        tool.jsonObject["name"]?.jsonPrimitive?.content
            ?: tool.jsonObject["function"]!!.jsonObject["name"]!!.jsonPrimitive.content
    }

    private fun JsonObject.chatCompletionParameters(): JsonObject =
        this["tools"]!!.jsonArray.single().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject

    private fun JsonObject.responsesParameters(): JsonObject =
        this["tools"]!!.jsonArray.single().jsonObject["parameters"]!!.jsonObject

    private fun JsonObject.anthropicInputSchema(): JsonObject =
        this["tools"]!!.jsonArray.single().jsonObject["input_schema"]!!.jsonObject

    // ------------------------------------------------------------ 1. 任意 schema 透传

    @Test
    fun chatCompletions_forwardsArbitrarySchema() = runBlocking {
        enqueueChatCompletionResponse()

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = history(),
            tools = listOf(neutralTool()),
        )

        val body = takeRequestBody()
        assertEquals(listOf("custom_tool"), body.toolNames())
        assertEquals("schema 必须原样透传（含 oneOf / \$defs / 嵌套数组）", complexSchema, body.chatCompletionParameters())
        assertEquals(
            "tools 非空时必须关掉并行工具调用",
            "false",
            body["parallel_tool_calls"]!!.jsonPrimitive.content,
        )
    }

    @Test
    fun responses_forwardsArbitrarySchema() = runBlocking {
        enqueueResponsesResponse()

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.OPENAI, useResponsesApi = true),
            history = history(),
            tools = listOf(neutralTool()),
        )

        val body = takeRequestBody()
        assertEquals(listOf("custom_tool"), body.toolNames())
        assertEquals(complexSchema, body.responsesParameters())
    }

    @Test
    fun anthropic_forwardsArbitrarySchemaAndKeepsTheCacheBreakpoint() = runBlocking {
        enqueueAnthropicResponse()

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.ANTHROPIC),
            history = history(),
            tools = listOf(neutralTool()),
        )

        val body = takeRequestBody()
        assertEquals(listOf("custom_tool"), body.toolNames())
        assertEquals(complexSchema, body.anthropicInputSchema())
        // 缓存断点必须仍在**最后一个工具**上（前缀缓存的覆盖面靠它）
        val tool = body["tools"]!!.jsonArray.single().jsonObject
        assertEquals("ephemeral", tool["cache_control"]!!.jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("true", tool["strict"]!!.jsonPrimitive.content)
    }

    // ------------------------------------------------------------ 2. 取代语义

    @Test
    fun nonNullTools_replaceSkillSelectionToolsInsteadOfMerging() = runBlocking {
        enqueueChatCompletionResponse()
        val selection = ChatAgentSkillRouter.availableTools(listOf(agentTool("vflow_agent_extra")))

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = history(),
            skillSelection = selection,
            tools = listOf(neutralTool("custom_tool")),
        )

        val body = takeRequestBody()
        assertEquals(
            "tools 非 null ⇒ **取代**（不是追加）skillSelection 的工具集",
            listOf("custom_tool"),
            body.toolNames(),
        )
    }

    @Test
    fun emptyNonNullTools_sendNoToolsSectionAtAll() = runBlocking {
        // 「取代」语义的极端：显式传空表 ⇒ 一个工具都不发（而不是退回 skillSelection）。
        enqueueChatCompletionResponse()
        val selection = ChatAgentSkillRouter.availableTools(listOf(agentTool("vflow_agent_extra")))

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = history(),
            skillSelection = selection,
            tools = emptyList(),
        )

        val body = takeRequestBody()
        assertNull("显式空表 ⇒ 不发 tools 段", body["tools"])
    }

    // ------------------------------------------------------------ 3. 回归：tools = null

    @Test
    fun nullTools_fallBackToSkillSelectionWithIdenticalSchema() = runBlocking {
        enqueueChatCompletionResponse()
        val selection = ChatAgentSkillRouter.availableTools(
            listOf(agentTool("vflow_agent_observe_ui"), agentTool("vflow_agent_tap")),
        )

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = history(),
            skillSelection = selection,
            tools = null,
        )

        val body = takeRequestBody()
        assertEquals(listOf("vflow_agent_observe_ui", "vflow_agent_tap"), body.toolNames())
        assertEquals(
            "tools = null 时工具 schema 必须与改造前逐字段一致",
            complexSchema,
            body["tools"]!!.jsonArray.first().jsonObject["function"]!!.jsonObject["parameters"]!!.jsonObject,
        )
    }

    @Test
    fun nullToolsAndEmptySelection_sendNoToolsSection() = runBlocking {
        enqueueChatCompletionResponse()

        ChatCompletionClient(httpClient = httpClient).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = history(),
        )

        assertNull(takeRequestBody()["tools"])
    }

    // ------------------------------------------------------------ 4. formatter 的形参优先级

    @Test
    fun formatter_prefersNeutralToolsOverAgentDefinitions() {
        // 同名、相反的 truncatable ⇒ 必须**以 tools 为准**（两条方向都验，防止「碰巧相等」）
        val longContent = "x".repeat(3_000)
        val message = ChatMessage(
            id = "m1",
            role = ChatMessageRole.TOOL,
            content = longContent,
            timestampMillis = 1L,
        )
        val toolResult = ChatToolResult(
            callId = "call_1",
            name = "vflow_agent_observe_ui",
            status = ChatToolResultStatus.SUCCESS,
            summary = "s",
            outputText = longContent,
        )

        // ① tools 说「可截断」、toolDefinitions 说「不可截断」⇒ 结果必须**被截断**
        val truncated = ChatToolResultInputFormatter.format(
            message = message,
            toolResult = toolResult,
            toolDefinitions = listOf(agentTool("vflow_agent_observe_ui", truncatable = false)),
            tools = listOf(neutralTool("vflow_agent_observe_ui", truncatable = true)),
        )
        assertTrue(
            "tools 非空时必须优先看它的 truncatable，实际：${truncated.take(80)}",
            truncated.contains("[output truncated:"),
        )

        // ② tools 说「不可截断」、toolDefinitions 说「可截断」⇒ 结果必须**原样返回**
        val untruncated = ChatToolResultInputFormatter.format(
            message = message,
            toolResult = toolResult,
            toolDefinitions = listOf(agentTool("vflow_agent_observe_ui", truncatable = true)),
            tools = listOf(neutralTool("vflow_agent_observe_ui", truncatable = false)),
        )
        assertEquals(longContent, untruncated)
    }

    @Test
    fun formatter_stillHonoursAgentDefinitionsWhenNeutralToolsAreEmpty() {
        // 既有形参的语义一字不动（既有 ChatAgentToolingTest 也依赖它）
        val longContent = "y".repeat(3_000)
        val message = ChatMessage(
            id = "m1",
            role = ChatMessageRole.TOOL,
            content = longContent,
            timestampMillis = 1L,
        )
        val toolResult = ChatToolResult(
            callId = "call_1",
            name = "load_skill",
            status = ChatToolResultStatus.SUCCESS,
            summary = "s",
            outputText = longContent,
        )

        assertEquals(
            longContent,
            ChatToolResultInputFormatter.format(
                message = message,
                toolResult = toolResult,
                toolDefinitions = listOf(agentTool("load_skill", truncatable = false)),
            ),
        )
    }
}
