package com.chaomixian.vflow.ui.chat

import java.util.Base64
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **三协议的图片装配，断言的是 MockWebServer 收到的真实请求体**
 * （不是内部函数返回值——`ChatCompletionClient` 此前零覆盖，见方案 §2 row 10）。
 *
 * 覆盖四个必测面里与本层有关的三条：
 * 1. 三协议图片 part 形状 + `data:` URL 的 base64 逐字符正确；
 * 2. 超预算的图换成**占位文本 part**，且落在被裁的那张**原位**；
 * 3. **回归**：无附件时 user content 仍是**字符串**（既有行为不回退）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把三处 USER 分支改回「只写字符串 content」⇒ 三条协议用例**全红**。
 * 2. 把 `imageBudget` 换成 `ChatImageBudget.UNLIMITED`（或删掉图片分支）
 *    ⇒ [overBudgetImage_becomesPlaceholderTextPartInPlace] **红**（找不到占位 part）。
 * 3. 把「文本为空**且**无图才跳过」改回 `if (content.isBlank()) return@forEach`
 *    ⇒ [emptyTextWithImage_isNotDropped] **红**。
 */
class ChatImageProtocolAssemblyTest {

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

    /** 任何调用都是缺陷：本文件所有图都在单图上限内，**不该**触发缩压（D19 默认不缩）。 */
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
        mimeType: String = "image/png",
        source: ChatImageSource = ChatImageSource.USER,
        label: String = "",
    ) = ChatImageAttachment(id = id, uri = uri, source = source, mimeType = mimeType, label = label)

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

    private fun userMessage(
        id: String = "m1",
        content: String = "hello",
        vararg attachments: ChatImageAttachment,
    ) = ChatMessage(
        id = id,
        role = ChatMessageRole.USER,
        content = content,
        timestampMillis = 1L,
        attachments = attachments.toList(),
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

    private fun JsonObject.chatCompletionUserContent(): JsonElement =
        this["messages"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "user" }["content"]!!

    private fun JsonObject.responsesUserContent(): JsonElement =
        this["input"]!!.jsonArray
            .map { it.jsonObject }
            .single {
                it["type"]?.jsonPrimitive?.content == "message" &&
                    it["role"]?.jsonPrimitive?.content == "user"
            }["content"]!!

    private fun JsonObject.anthropicUserContent(): JsonElement =
        this["messages"]!!.jsonArray
            .map { it.jsonObject }
            .single { it["role"]?.jsonPrimitive?.content == "user" }["content"]!!

    private fun JsonElement.parts(): List<JsonObject> = jsonArray.map { it.jsonObject }

    private fun JsonElement.asString(): String = jsonPrimitive.content

    // ------------------------------------------------------------ 1. 三协议图片装配

    @Test
    fun chatCompletions_sendsImageUrlPartNextToTheTextPart() = runBlocking {
        val image = attachment("a1", "file:///tmp/a1.png")
        enqueueChatCompletionResponse()

        clientWith(image).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(userMessage(content = "hello", attachments = arrayOf(image))),
        )

        val content = takeRequestBody().chatCompletionUserContent().parts()
        assertEquals(2, content.size)
        assertEquals("text", content[0]["type"]?.asString())
        assertEquals("hello", content[0]["text"]?.asString())
        assertEquals("image_url", content[1]["type"]?.asString())
        assertEquals(
            "data:image/png;base64,$imageBase64",
            content[1]["image_url"]!!.jsonObject["url"]?.asString(),
        )
    }

    @Test
    fun responses_sendsInputImagePartAsDataUrlString() = runBlocking {
        val image = attachment("a1", "file:///tmp/a1.png")
        enqueueResponsesResponse()

        clientWith(image).generateReply(
            preset = preset(ChatProvider.OPENAI, useResponsesApi = true),
            history = listOf(userMessage(content = "hello", attachments = arrayOf(image))),
        )

        val content = takeRequestBody().responsesUserContent().parts()
        assertEquals(2, content.size)
        assertEquals("input_text", content[0]["type"]?.asString())
        assertEquals("hello", content[0]["text"]?.asString())
        assertEquals("input_image", content[1]["type"]?.asString())
        // ⚠️ Responses 的 image_url 是**字符串**（chat/completions 的是对象）——串了只会被服务端 400
        assertEquals(
            "data:image/png;base64,$imageBase64",
            content[1]["image_url"]?.asString(),
        )
    }

    @Test
    fun anthropic_sendsBase64SourceBlock() = runBlocking {
        val image = attachment("a1", "file:///tmp/a1.png")
        enqueueAnthropicResponse()

        clientWith(image).generateReply(
            preset = preset(ChatProvider.ANTHROPIC),
            history = listOf(userMessage(content = "hello", attachments = arrayOf(image))),
        )

        val content = takeRequestBody().anthropicUserContent().parts()
        assertEquals(2, content.size)
        assertEquals("text", content[0]["type"]?.asString())
        assertEquals("hello", content[0]["text"]?.asString())
        assertEquals("image", content[1]["type"]?.asString())
        val source = content[1]["source"]!!.jsonObject
        assertEquals("base64", source["type"]?.asString())
        assertEquals("image/png", source["media_type"]?.asString())
        assertEquals(imageBase64, source["data"]?.asString())
    }

    // ------------------------------------------------------------ 2. 预算占位

    @Test
    fun overBudgetImage_becomesPlaceholderTextPartInPlace() = runBlocking {
        // 6 张用户图（默认预算 5）⇒ 最旧的那张换成占位文本 part，且**落在原位**（文本 part 之后第一位）
        val images = (1..6).map { index ->
            attachment("u$index", "file:///tmp/u$index.png", label = "photo$index.jpg")
        }
        enqueueChatCompletionResponse()

        clientWith(*images.toTypedArray()).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(userMessage(content = "hello", attachments = images.toTypedArray())),
        )

        val content = takeRequestBody().chatCompletionUserContent().parts()
        assertEquals("文本 part + 6 张图（1 张占位 + 5 张图）", 7, content.size)
        assertEquals("hello", content[0]["text"]?.asString())

        assertEquals("占位必须是文本 part", "text", content[1]["type"]?.asString())
        val placeholderText = content[1]["text"]?.asString().orEmpty()
        assertTrue(
            "占位文案必须让模型知道「这里曾有张图被省掉了」，实际：$placeholderText",
            placeholderText.startsWith("[image omitted to fit request image limits;"),
        )
        assertTrue(
            "占位必须指认是**哪一张**：$placeholderText",
            placeholderText.contains("\"photo1.jpg\" (u1)"),
        )

        // 其余 5 张原样发出，且顺序与消息里一致
        val remaining = content.drop(2)
        assertEquals(5, remaining.size)
        assertTrue(
            "被裁的只能是第一张；其余应为图片 part，实际：${remaining.map { it["type"]?.asString() }}",
            remaining.all { it["type"]?.asString() == "image_url" },
        )
        remaining.forEach { part ->
            assertEquals(
                "data:image/png;base64,$imageBase64",
                part["image_url"]!!.jsonObject["url"]?.asString(),
            )
        }
    }

    @Test
    fun agentScreenshots_areBudgetedSeparatelyFromUserImages() = runBlocking {
        // 6 张用户图 + 3 张 Agent 截图（默认 Agent 预算 1）⇒ 用户裁 1、Agent 裁 2。
        // 若两档共用计数器，第二段消息的保留数会与 1 不符。
        val userImages = (1..6).map { index -> attachment("u$index", "file:///tmp/u$index.png") }
        val agentImages = (1..3).map { index ->
            attachment("s$index", "file:///tmp/s$index.png", source = ChatImageSource.AGENT, label = "screen$index.png")
        }
        enqueueChatCompletionResponse()

        clientWith(*(userImages + agentImages).toTypedArray()).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(
                userMessage(id = "m1", content = "here", attachments = userImages.toTypedArray()),
                userMessage(id = "m2", content = "", attachments = agentImages.toTypedArray()),
            ),
        )

        val userParts = takeRequestBody()["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]?.asString() == "user" }
            .map { it["content"]!!.parts() }

        assertEquals(2, userParts.size)
        assertEquals("用户消息：文本 + 1 占位 + 5 保留", 7, userParts[0].size)
        // 第二条消息 content 为空 ⇒ 文本 part 不加，只剩 2 占位 + 1 保留
        assertEquals("Agent 消息：空文本不进 part + 2 占位 + 1 保留", 3, userParts[1].size)
        assertTrue(
            userParts[1][0]["text"]?.asString().orEmpty().contains("\"screen1.png\" (s1)"),
        )
        assertTrue(
            userParts[1][1]["text"]?.asString().orEmpty().contains("\"screen2.png\" (s2)"),
        )
        assertEquals("image_url", userParts[1][2]["type"]?.asString())
    }

    // ------------------------------------------------------------ 3. 回归：无图时请求体不变

    @Test
    fun noAttachments_keepsUserContentAsPlainString() = runBlocking {
        enqueueChatCompletionResponse()

        ChatCompletionClient(httpClient = httpClient, imageResolver = ChatImageResolver()).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(userMessage(content = "plain text")),
        )

        val content = takeRequestBody().chatCompletionUserContent()
        assertTrue(
            "无附件时 user content 必须仍是字符串（否则是行为回退），实际：$content",
            content is JsonPrimitive,
        )
        assertEquals("plain text", content.jsonPrimitive.content)
    }

    @Test
    fun noAttachments_keepsAnthropicUserContentAsPlainString() = runBlocking {
        enqueueAnthropicResponse()

        ChatCompletionClient(httpClient = httpClient, imageResolver = ChatImageResolver()).generateReply(
            preset = preset(ChatProvider.ANTHROPIC),
            history = listOf(userMessage(content = "plain text")),
        )

        val content = takeRequestBody().anthropicUserContent()
        assertTrue("Anthropic 无附件时 content 必须仍是字符串，实际：$content", content is JsonPrimitive)
        assertEquals("plain text", content.jsonPrimitive.content)
    }

    @Test
    fun emptyTextWithImage_isNotDropped() = runBlocking {
        // 现状 `if (content.isBlank()) return@forEach` 会整条丢掉「纯图消息」——
        // 对图片路径是致命的（用户直接发一张图时那条消息会消失）。
        val image = attachment("a1", "file:///tmp/a1.png")
        enqueueChatCompletionResponse()

        clientWith(image).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(userMessage(content = "   ", attachments = arrayOf(image))),
        )

        val content = takeRequestBody().chatCompletionUserContent().parts()
        assertEquals("空文本 + 有图 ⇒ 数组里只有图片 part（而且消息没被丢）", 1, content.size)
        assertEquals("image_url", content[0]["type"]?.asString())
    }

    @Test
    fun nonUserAttachments_areIgnoredNotSentAsImageParts() = runBlocking {
        // 只有 user 角色在三协议里都支持图片；其它角色带附件必须被忽略（并打 W 日志），
        // 绝不能悄悄塞进 payload（Anthropic 会因为 assistant 块里出现 image 而整条 400）。
        val image = attachment("a1", "file:///tmp/a1.png")
        enqueueChatCompletionResponse()

        clientWith(image).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(
                ChatMessage(
                    id = "m1",
                    role = ChatMessageRole.ASSISTANT,
                    content = "assistant text",
                    timestampMillis = 1L,
                    attachments = listOf(image),
                ),
            ),
        )

        val body = takeRequestBody()
        val assistant = body["messages"]!!.jsonArray.map { it.jsonObject }
            .single { it["role"]?.asString() == "assistant" }
        assertEquals("assistant text", assistant["content"]?.asString())
        assertFalse(
            "非 user 角色的附件不得出现在请求体里：$body",
            body.toString().contains("image_url"),
        )
    }

    @Test
    fun multipleMessagesWithImages_areAssembledPerMessage() = runBlocking {
        // 两张图分属两条消息 ⇒ 各自装配成自己的数组（不能把不同消息的图并到一起）
        val first = attachment("u1", "file:///tmp/u1.png")
        val second = attachment("u2", "file:///tmp/u2.png")
        enqueueChatCompletionResponse()

        clientWith(first, second).generateReply(
            preset = preset(ChatProvider.OPENAI),
            history = listOf(
                userMessage(id = "m1", content = "first", attachments = arrayOf(first)),
                userMessage(id = "m2", content = "second", attachments = arrayOf(second)),
            ),
        )

        val contents = takeRequestBody()["messages"]!!.jsonArray
            .map { it.jsonObject }
            .filter { it["role"]?.asString() == "user" }
            .map { it["content"]!!.parts() }

        assertEquals(2, contents.size)
        contents.forEach { parts ->
            assertEquals(2, parts.size)
            assertEquals("image_url", parts[1]["type"]?.asString())
        }
        assertEquals("first", contents[0][0]["text"]?.asString())
        assertEquals("second", contents[1][0]["text"]?.asString())
    }
}
