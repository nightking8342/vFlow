package com.chaomixian.vflow.core.workflow.module.interaction

import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.ai.AiModelConfigResolution
import com.chaomixian.vflow.core.ai.AiModelConfigResolver
import com.chaomixian.vflow.core.ai.ManualAiConfig
import com.chaomixian.vflow.ui.chat.ChatCompletionClient
import com.chaomixian.vflow.ui.chat.ChatCompletionResult
import com.chaomixian.vflow.ui.chat.ChatImageAttachment
import com.chaomixian.vflow.ui.chat.ChatImageByteSource
import com.chaomixian.vflow.ui.chat.ChatImageResolver
import com.chaomixian.vflow.ui.chat.ChatImageShrinker
import com.chaomixian.vflow.ui.chat.ChatImageSource
import com.chaomixian.vflow.ui.chat.ChatMessage
import com.chaomixian.vflow.ui.chat.ChatMessageRole
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **`AutoGLMModule` 接入共享传输层 + 配置引用**（fork：AI 能力统一批 4 的 T5）。
 *
 * ⚠️ 本批对 AutoGLM **只改传输与配置**：`do(action=…)` 文本动作协议、整块提示词、
 * 图片清理意图、`errorCount` 兜底**全部逐字保留**（上游模型契约）。
 * 本文件断言请求体（集成点）+ 三个纯函数 helper。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 [autoGlmPreset] 的 temperature 改成 `0.7`
 *    ⇒ [preset_pinsTemperatureAtZeroPointOne] 与
 *    [turn_sendsThePromptAsSystemMessageWithTemperatureZeroPointOneAndNoTools] 变红；
 * 2. 去掉 [stripImagesFromLastUserMessage] 里的 `[Image removed]` 追加
 *    ⇒ [stripImagesFromLastUserMessage_clearsAttachmentsAndAppendsTheMarker] 变红。
 */
class AutoGLMTransportTest {

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

    private fun completionResponse(content: String) = MockResponse()
        .setHeader("Content-Type", "application/json")
        .setBody(
            """{"choices":[{"index":0,"message":{"role":"assistant","content":"$content"}}]}""",
        )

    /** 注入假图片字节来源（纯 JVM 下没有真实文件系统可读）。 */
    private fun client() = ChatCompletionClient(
        httpClient = httpClient,
        imageResolver = ChatImageResolver(
            byteSource = ChatImageByteSource { ByteArray(4) },
            shrinker = ChatImageShrinker { _, _, _ -> null },
        ),
    )

    private fun preset(
        model: String = "autoglm-phone",
        apiKey: String = "sk-glm",
        baseUrl: String = server.url("/api/paas/v4").toString(),
        systemPrompt: String = AUTO_GLM_PROMPT,
    ) = autoGlmPreset(model = model, apiKey = apiKey, baseUrl = baseUrl, systemPrompt = systemPrompt)

    private fun userMessage(
        content: String,
        imagePath: String? = null,
    ) = ChatMessage(
        role = ChatMessageRole.USER,
        content = content,
        timestampMillis = 1L,
        attachments = imagePath
            ?.let {
                listOf(
                    ChatImageAttachment(
                        uri = it,
                        source = ChatImageSource.AGENT,
                        mimeType = "image/jpeg",
                    ),
                )
            }
            .orEmpty(),
    )

    private fun bodyOfFirstRequest(): JSONObject {
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(
            "端点必须由共享的 ChatEndpointResolver 算出（bigmodel 的 paas/v4 与改造前逐字相同）",
            "/api/paas/v4/chat/completions",
            request.path,
        )
        return JSONObject(request.body.readUtf8())
    }

    // ------------------------------------------------------------ 1. 请求体（协议零改动）

    @Test
    fun turn_sendsThePromptAsSystemMessageWithTemperatureZeroPointOneAndNoTools() = runBlocking {
        server.enqueue(completionResponse("ok"))

        runAutoGlmTurn(
            preset = preset(),
            history = listOf(userMessage("Current Step: 1\nCurrent Interface: [UI]")),
            client = client(),
        )

        val body = bodyOfFirstRequest()
        assertEquals("autoglm-phone", body.getString("model"))
        assertEquals(
            "temperature 必须恒为 0.1（模型契约的一部分，不参与配置引用的值拷贝）",
            0.1,
            body.getDouble("temperature"),
            1e-9,
        )
        assertFalse("必须显式声明非流式", body.getBoolean("stream"))
        assertFalse(
            "AutoGLM 的动作走 do(action=…) 文本协议 ⇒ 请求体里**不得**有 tools",
            body.has("tools"),
        )

        val messages = body.getJSONArray("messages")
        assertEquals("history 1 条 + system 1 条", 2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(
            "整块 AutoGLM 提示词必须原样作为第一条 system 消息发出",
            AUTO_GLM_PROMPT,
            messages.getJSONObject(0).getString("content"),
        )
        assertEquals("user", messages.getJSONObject(1).getString("role"))
    }

    @Test
    fun turn_sendsTheCurrentScreenshotAsAnImagePart() = runBlocking {
        server.enqueue(completionResponse("ok"))

        runAutoGlmTurn(
            preset = preset(),
            history = listOf(userMessage("Current Step: 1", imagePath = "file:///tmp/glm_step1.jpg")),
            client = client(),
        )

        val messages = bodyOfFirstRequest().getJSONArray("messages")
        val userContent = messages.getJSONObject(1).get("content")
        assertTrue(
            "带图的 user 轮 content 必须是数组（文本 part + 图片 part）",
            userContent is JSONArray,
        )
        val parts = userContent as JSONArray
        var imageUrl: String? = null
        for (i in 0 until parts.length()) {
            val part = parts.getJSONObject(i)
            if (part.optString("type") == "image_url") {
                imageUrl = part.getJSONObject("image_url").getString("url")
            }
        }
        assertTrue(
            "当前轮的截图必须真的进请求（data URL 形态）",
            imageUrl?.startsWith("data:image/jpeg;base64,") == true,
        )
    }

    // ------------------------------------------------------------ 2. 纯函数：图片清理

    @Test
    fun stripImagesFromLastUserMessage_clearsAttachmentsAndAppendsTheMarker() {
        val history = listOf(
            userMessage("第一轮", imagePath = "file:///tmp/a.jpg"),
            ChatMessage(role = ChatMessageRole.ASSISTANT, content = "do(action=\"Back\")", timestampMillis = 2L),
            userMessage("第二轮", imagePath = "file:///tmp/b.jpg"),
        )

        val stripped = stripImagesFromLastUserMessage(history)

        assertEquals("条数不变", 3, stripped.size)
        assertEquals(
            "第一条（更早的那轮）不受影响 —— 只清**最后一条** user 消息",
            listOf("file:///tmp/a.jpg"),
            stripped[0].attachments.map { it.uri },
        )
        assertTrue("最后一条的图必须被清掉", stripped[2].attachments.isEmpty())
        assertTrue(
            "必须追加 [Image removed] 标记（等价搬迁：原实现无条件追加）",
            stripped[2].content.contains("[Image removed]"),
        )
        assertTrue("原文必须保留", stripped[2].content.startsWith("第二轮"))
    }

    @Test
    fun stripImagesFromLastUserMessage_leavesANonUserTailAlone() {
        val history = listOf(
            ChatMessage(role = ChatMessageRole.ASSISTANT, content = "thinking", timestampMillis = 1L),
        )
        assertEquals("最后一条不是 user ⇒ 原样返回", history, stripImagesFromLastUserMessage(history))
    }

    @Test
    fun stripImagesFromLastUserMessage_toleratesAnEmptyHistory() {
        assertEquals(emptyList<ChatMessage>(), stripImagesFromLastUserMessage(emptyList()))
    }

    // ------------------------------------------------------------ 3. 纯函数：assistant 历史

    @Test
    fun assistantHistoryContent_rebuildsTheThinkPrefixWhenReasoningExists() {
        val content = autoGlmAssistantHistoryContent(
            ChatCompletionResult(
                content = "<answer>do(action=\"Back\")</answer>",
                reasoningContent = "我需要返回上一页",
                totalTokens = null,
            ),
        )

        val expected = "<" + "think" + ">" + "我需要返回上一页" + "<" + "/think" + ">" +
            "\n" + "<answer>do(action=\"Back\")</answer>"
        assertEquals("必须重建完整的 think 段（现状把原始消息含 think 段原样入历史）", expected, content)
    }

    @Test
    fun assistantHistoryContent_isJustTheContentWhenThereIsNoReasoning() {
        val content = autoGlmAssistantHistoryContent(
            ChatCompletionResult(
                content = "<answer>do(action=\"Home\")</answer>",
                reasoningContent = null,
                totalTokens = null,
            ),
        )
        assertEquals("<answer>do(action=\"Home\")</answer>", content)
    }

    // ------------------------------------------------------------ 4. 配置引用（只做活引用 4 项）

    @Test
    fun preset_pinsTemperatureAtZeroPointOne() {
        val preset = preset()
        assertEquals(AUTO_GLM_TEMPERATURE, preset.temperature, 1e-9)
        assertEquals(0.1, preset.temperature, 1e-9)
        assertEquals(false, preset.useResponsesApi)
        assertEquals(0, preset.streamMaxRetries)
    }

    @Test
    fun referenceMode_keepsTemperatureAtZeroPointOneAndTakesTheLiveFourFields() = runBlocking {
        server.enqueue(completionResponse("ok"))

        val resolved = AiModelConfigResolver.resolve(
            parameters = mapOf(
                AiModelConfigRef.PARAM_SOURCE to AiModelConfigRef.SOURCE_REFERENCE,
                AiModelConfigRef.PARAM_CONFIG_ID to "cfg-ref",
            ),
            presets = listOf(
                ChatPresetConfig(
                    id = "cfg-ref",
                    name = "引用档",
                    provider = "openai",
                    model = "referenced-glm",
                    apiKey = "sk-referenced",
                    baseUrl = server.url("/api/paas/v4").toString(),
                    systemPrompt = "CONFIG-PROMPT",
                    temperature = 0.9,
                ),
            ),
            manual = ManualAiConfig(
                provider = "bigmodel",
                model = "autoglm-phone",
                apiKey = "",
                baseUrl = "https://open.bigmodel.cn/api/paas/v4",
                // AutoGLM 不适用值拷贝 ⇒ 模块传空串，稍后由整块提示词覆盖。
                systemPrompt = "",
                temperature = AUTO_GLM_TEMPERATURE,
            ),
        )
        assertTrue(resolved is AiModelConfigResolution.Resolved)
        val preset = (resolved as AiModelConfigResolution.Resolved)
            .preset
            .copy(systemPrompt = AUTO_GLM_PROMPT)

        runAutoGlmTurn(preset, listOf(userMessage("Current Step: 1")), client())

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("活引用的 key 必须真的进请求", "Bearer sk-referenced", request.getHeader("Authorization"))
        val body = JSONObject(request.body.readUtf8())
        assertEquals("model 是活引用", "referenced-glm", body.getString("model"))
        assertEquals(
            "⚠️ temperature **不**跟配置走（模型契约固定 0.1）",
            0.1,
            body.getDouble("temperature"),
            1e-9,
        )
        assertEquals(
            "⚠️ systemPrompt **不**跟配置走（提示词是模型契约）",
            AUTO_GLM_PROMPT,
            body.getJSONArray("messages").getJSONObject(0).getString("content"),
        )
    }

    @Test
    fun moduleExposesTheConfigReferenceParametersWithManualAsTheDefault() {
        val inputs = AutoGLMModule().getInputs().associateBy { it.id }

        assertEquals(
            "默认 manual ⇒ 老步骤零迁移",
            AiModelConfigRef.SOURCE_MANUAL,
            inputs[AiModelConfigRef.PARAM_SOURCE]?.defaultValue,
        )
        listOf("provider", "base_url", "api_key", "model", "instruction", "max_steps")
            .forEach { id ->
                assertTrue("既有参数一个都不能少（只追加、不替换）：$id", inputs.containsKey(id))
            }
    }

    private companion object {
        /** 代表「整块 AutoGLM 提示词」的一段文本（真实提示词见 AutoGLMModule.kt）。 */
        const val AUTO_GLM_PROMPT =
            "今天的日期是: 2026年10月16日\n你必须严格按照要求输出以下格式：\n" +
                " thinking{think} response\n<answer>{action}</answer>"
    }
}