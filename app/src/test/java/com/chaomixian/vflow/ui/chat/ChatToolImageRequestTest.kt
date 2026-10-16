package com.chaomixian.vflow.ui.chat

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **Agent 截图真的进了请求**（三协议，断言的是 MockWebServer 收到的真实请求体）。
 *
 * 承载方式（fork：AI 能力统一 §3.1 结论）= **紧随其后的 user 轮**：截图挂在一条
 * `role = USER`、`source = AGENT`、`content = ""` 的消息上。理由：
 * `chat/completions` 的 `role:"tool"` content **只能是字符串**、Responses 的
 * `function_call_output.output` 同理、assistant 的 content 也不支持图片 part
 * ⇒ 只有 user 角色在三协议里都成立。Anthropic 侧「连续两条 user 消息」不是错误
 * （官方文档：连续 user/assistant turn 会被合并成一个 turn）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把三个 USER 分支改回「只写字符串 content」⇒ 三条协议用例**全红**；
 * 2. 把 `imageBudget` 换成 `ChatImageBudget.UNLIMITED` ⇒
 *    [overBudgetAgentScreenshots_becomePlaceholderTextParts] **红**（找不到占位 part）；
 * 3. 把 [chatAgentToolImageAttachments] 改成返回 `emptyList()`（截图没接线）⇒
 *    本文件所有用例**红**（历史里那条截图消息不会出现）。
 */
class ChatToolImageRequestTest {

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

    private val imageBytes = ByteArray(3) { 0x00 }
    private val imageBase64 = Base64.getEncoder().encodeToString(imageBytes)

    private class MapByteSource(private val bytesByUri: Map<String, ByteArray>) : ChatImageByteSource {
        override fun readBytes(attachment: ChatImageAttachment): ByteArray? = bytesByUri[attachment.uri]
    }

    private object NeverCalledShrinker : ChatImageShrinker {
        override fun shrinkToBase64(
            attachment: ChatImageAttachment,
            bytes: ByteArray,
            maxBase64Chars: Int,
        ): String? = error("缩压不该被调用：本文件的图都在单图上限内")
    }

    private fun attachment(
        id: String,
        uri: String,
        source: ChatImageSource = ChatImageSource.AGENT,
        label: String = "",
    ) = ChatImageAttachment(
        id = id,
        uri = uri,
        source = source,
        mimeType = "image/png",
        label = label,
    )

    private fun clientWith(vararg attachments: ChatImageAttachment): ChatCompletionClient {
        val bytes = attachments.associate { it.uri to imageBytes }
        return ChatCompletionClient(
            httpClient = httpClient,
            imageResolver = ChatImageResolver(
                byteSource = MapByteSource(bytes),
                shrinker = NeverCalledShrinker,
            ),
        )
    }

    private fun preset(provider: ChatProvider, useResponsesApi: Boolean = false) = ChatPresetConfig(
        provider = provider.storageValue,
        model = "test-model",
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        useResponsesApi = useResponsesApi,
    )

    private fun toolCallMessage() = ChatMessage(
        id = "m2",
        role = ChatMessageRole.ASSISTANT,
        content = "",
        timestampMillis = 2L,
        toolCalls = listOf(
            ChatToolCall(id = "call_1", name = "vflow_agent_observe_ui", argumentsJson = "{}"),
        ),
    )

    private fun toolResult() = ChatToolResult(
        callId = "call_1",
        name = "vflow_agent_observe_ui",
        status = ChatToolResultStatus.SUCCESS,
        summary = "observe",
        outputText = "Observed the current UI successfully.",
        artifacts = listOf(
            ChatArtifactReference(
                key = "screenshot",
                handle = "artifact://call_1/screenshot",
                typeLabel = "image",
            )
        ),
    )

    private fun toolResultMessage() = ChatMessage(
        id = "m3",
        role = ChatMessageRole.TOOL,
        content = "Observed the current UI successfully.",
        timestampMillis = 3L,
        toolResult = toolResult(),
    )

    /** 「工具结果 + 紧随其后的截图 user 轮」—— 本方案确定的截图承载形态。 */
    private fun screenshotMessage(vararg attachments: ChatImageAttachment) = ChatMessage(
        id = "m4",
        role = ChatMessageRole.USER,
        content = "",
        timestampMillis = 4L,
        attachments = attachments.toList(),
    )

    /**
     * **从真实的工具结果走到截图消息**（= 生产路径，不只是「给定历史时的装配」）：
     * `ChatToolResult.artifacts(typeLabel = "image")` → [chatAgentToolImageAttachments]
     * → 一条 `role = USER`、`source = AGENT`、`content = ""` 的消息。
     *
     * ⚠️ 这三条协议用例刻意走这条路径：否则「接线断了」（`chatAgentToolImageAttachments`
     * 返回空表）时用例照样绿 —— 那正是本仓库反复踩过的「纯函数全绿但集成点没接上」。
     */
    private fun screenshotMessageFromToolResults(vararg toolResults: ChatToolResult): ChatMessage {
        val attachments = chatAgentToolImageAttachments(toolResults.toList()) { handle ->
            if (handle == "artifact://call_1/screenshot") "file:///tmp/s1.png" else null
        }
        return screenshotMessage(*attachments.toTypedArray())
    }

    private fun userMessage(id: String, content: String) = ChatMessage(
        id = id,
        role = ChatMessageRole.USER,
        content = content,
        timestampMillis = 1L,
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

    private fun JsonElement.parts(): List<JsonObject> = jsonArray.map { it.jsonObject }

    /** 文本 content（字符串）与块数组 content 都存在 ⇒ 取块时先判类型。 */
    private fun JsonElement.partsOrNull(): List<JsonObject>? =
        (this as? JsonArray)?.map { it.jsonObject }

    private fun JsonElement.asString(): String = jsonPrimitive.content

    // ------------------------------------------------------------ 1. 三协议：截图进 user 轮

    @Test
    fun chatCompletions_keepsToolContentAsStringAndSendsScreenshotInAUserTurn() = runBlocking {
        val toolResult = toolResult()
        enqueueChatCompletionResponse()

        clientWith(attachment("s1", "file:///tmp/s1.png", label = "artifact://call_1/screenshot")).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(
                userMessage("m1", "look at my screen"),
                toolCallMessage(),
                toolResultMessage(),
                screenshotMessageFromToolResults(toolResult),
            ),
            imageBudget = ChatImageBudget(maxUserImages = 5, maxAgentImages = 1),
        )

        val messages = takeRequestBody()["messages"]!!.jsonArray.map { it.jsonObject }

        val toolMessage = messages.single { it["role"]?.asString() == "tool" }
        assertTrue(
            "OpenAI 的 role:\"tool\" content 只能是字符串（这是截图不能挂在工具消息上的硬约束）",
            toolMessage["content"] is JsonPrimitive,
        )

        val userTurns = messages.filter { it["role"]?.asString() == "user" }
        assertEquals("工具结果之后紧跟一条 user 轮（Anthropic 侧会与之合并）", 2, userTurns.size)
        assertEquals("look at my screen", userTurns[0]["content"]?.asString())

        val screenshotParts = userTurns[1]["content"]!!.parts()
        assertEquals("空文本 + 有图 ⇒ 数组里只有图片 part", 1, screenshotParts.size)
        assertEquals("image_url", screenshotParts[0]["type"]?.asString())
        assertEquals(
            "data:image/png;base64,$imageBase64",
            screenshotParts[0]["image_url"]!!.jsonObject["url"]?.asString(),
        )
    }

    @Test
    fun responses_keepsFunctionCallOutputAsStringAndSendsInputImageInAUserTurn() = runBlocking {
        val toolResult = toolResult()
        enqueueResponsesResponse()

        clientWith(attachment("s1", "file:///tmp/s1.png", label = "artifact://call_1/screenshot")).generateReply(
            preset = preset(ChatProvider.OPENAI, useResponsesApi = true),
            history = listOf(
                userMessage("m1", "look at my screen"),
                toolCallMessage(),
                toolResultMessage(),
                screenshotMessageFromToolResults(toolResult),
            ),
            imageBudget = ChatImageBudget(maxUserImages = 5, maxAgentImages = 1),
        )

        val input = takeRequestBody()["input"]!!.jsonArray.map { it.jsonObject }

        val toolOutput = input.single { it["type"]?.asString() == "function_call_output" }
        assertTrue(
            "Responses 的 function_call_output.output 只能是字符串",
            toolOutput["output"] is JsonPrimitive,
        )

        val userMessages = input.filter {
            it["type"]?.asString() == "message" && it["role"]?.asString() == "user"
        }
        assertEquals(2, userMessages.size)

        val screenshotParts = userMessages[1]["content"]!!.parts()
        assertEquals(1, screenshotParts.size)
        assertEquals("input_image", screenshotParts[0]["type"]?.asString())
        assertEquals(
            "Responses 的 image_url 是**字符串**（chat/completions 的是对象）",
            "data:image/png;base64,$imageBase64",
            screenshotParts[0]["image_url"]?.asString(),
        )
    }

    @Test
    fun anthropic_keepsToolResultAsStringAndSendsImageBlockInAUserTurn() = runBlocking {
        val toolResult = toolResult()
        enqueueAnthropicResponse()

        clientWith(attachment("s1", "file:///tmp/s1.png", label = "artifact://call_1/screenshot")).generateReply(
            preset = preset(ChatProvider.ANTHROPIC),
            history = listOf(
                userMessage("m1", "look at my screen"),
                toolCallMessage(),
                toolResultMessage(),
                screenshotMessageFromToolResults(toolResult),
            ),
            imageBudget = ChatImageBudget(maxUserImages = 5, maxAgentImages = 1),
        )

        val messages = takeRequestBody()["messages"]!!.jsonArray.map { it.jsonObject }

        val toolResultUserTurn = messages.single { message ->
            message["content"]!!.partsOrNull()?.any { it["type"]?.asString() == "tool_result" } == true
        }
        val toolResultBlock = toolResultUserTurn["content"]!!.parts()
            .single { it["type"]?.asString() == "tool_result" }
        assertTrue(
            "tool_result 的 content 必须仍是字符串（不把图塞进 tool_result）",
            toolResultBlock["content"] is JsonPrimitive,
        )

        val screenshotTurn = messages.last()
        assertEquals("user", screenshotTurn["role"]?.asString())
        val screenshotParts = screenshotTurn["content"]!!.parts()
        assertEquals(1, screenshotParts.size)
        assertEquals("image", screenshotParts[0]["type"]?.asString())
        val source = screenshotParts[0]["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.asString())
        assertEquals("image/png", source["media_type"]?.asString())
        assertEquals(imageBase64, source["data"]?.asString())
    }

    // ------------------------------------------------------------ 2. Agent 预算真的生效

    @Test
    fun overBudgetAgentScreenshots_becomePlaceholderTextParts() = runBlocking {
        val screenshots = (1..3).map { index ->
            attachment("s$index", "file:///tmp/s$index.png", label = "shot$index.png")
        }
        enqueueChatCompletionResponse()

        clientWith(*screenshots.toTypedArray()).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(
                userMessage("m1", "look at my screen"),
                screenshotMessage(*screenshots.toTypedArray()),
            ),
            imageBudget = ChatImageBudget(maxUserImages = 5, maxAgentImages = 1),
        )

        val userTurns = takeRequestBody()["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]?.asString() == "user" }
        val parts = userTurns.last()["content"]!!.parts()

        assertEquals("3 张截图、预算 1 ⇒ 2 张占位 + 1 张图", 3, parts.size)
        assertEquals("text", parts[0]["type"]?.asString())
        assertTrue(
            "占位必须让模型知道「这里曾有张图」：${parts[0]["text"]?.asString()}",
            parts[0]["text"]?.asString().orEmpty().startsWith("[image omitted"),
        )
        assertTrue(parts[0]["text"]?.asString().orEmpty().contains("\"shot1.png\" (s1)"))
        assertEquals("text", parts[1]["type"]?.asString())
        assertEquals("image_url", parts[2]["type"]?.asString())
    }

    @Test
    fun agentAndUserImages_areBudgetedIndependently() = runBlocking {
        val userImages = (1..2).map { index -> attachment("u$index", "file:///tmp/u$index.png", source = ChatImageSource.USER) }
        val screenshots = (1..2).map { index -> attachment("s$index", "file:///tmp/s$index.png") }
        enqueueChatCompletionResponse()

        clientWith(*(userImages + screenshots).toTypedArray()).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(
                userMessage("m1", "here"),
                ChatMessage(
                    id = "m2",
                    role = ChatMessageRole.USER,
                    content = "here",
                    timestampMillis = 2L,
                    attachments = userImages,
                ),
                screenshotMessage(*screenshots.toTypedArray()),
            ),
            imageBudget = ChatImageBudget(maxUserImages = 5, maxAgentImages = 1),
        )

        val userTurns = takeRequestBody()["messages"]!!.jsonArray.map { it.jsonObject }
            .filter { it["role"]?.asString() == "user" }

        assertEquals("用户图 2 张都在预算内 ⇒ 文本 + 2 张图", 3, userTurns[1]["content"]!!.parts().size)
        assertEquals(
            "Agent 档预算 1 ⇒ 2 张截图里只有最后一张是图（两档互不影响）",
            2,
            userTurns[2]["content"]!!.parts().size,
        )
        assertEquals("text", userTurns[2]["content"]!!.parts()[0]["type"]?.asString())
        assertEquals("image_url", userTurns[2]["content"]!!.parts()[1]["type"]?.asString())
    }
}
