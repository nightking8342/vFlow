package com.chaomixian.vflow.core.workflow.module.network

import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.ai.AiModelConfigResolution
import com.chaomixian.vflow.core.ai.AiModelConfigResolver
import com.chaomixian.vflow.core.ai.ManualAiConfig
import com.chaomixian.vflow.core.ai.chatProviderForModuleProvider
import com.chaomixian.vflow.ui.chat.ChatCompletionClient
import com.chaomixian.vflow.ui.chat.ChatEndpointMode
import com.chaomixian.vflow.ui.chat.ChatEndpointResolver
import com.chaomixian.vflow.ui.chat.ChatPresetConfig
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **`AIModule` 接入共享传输层**（fork：AI 能力统一批 4 的 T5）。
 *
 * `AIModule` 按 §3.6 **不进引擎**（纯文本一次问答）—— 它只复用「请求怎么发」这一层。
 * 本文件用 MockWebServer 断言**真实下发的请求体**（这是「纯函数全绿 ≠ 集成点接上了」
 * 那条教训的直接应对：请求装配是集成点）。
 *
 * ## ⚠️ 必做反证
 *
 * 把 [runAiCompletionTurn] 改成 `preset = preset.copy(systemPrompt = "")`
 * （模拟「手写实现漏发 system 消息」）⇒
 * [turn_sendsTheSystemPromptAsTheFirstMessageAndTheUserPromptAsTheSecond] 变红。
 */
class AIModuleTransportTest {

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

    private fun preset(
        provider: String = "openai",
        model: String = "gpt-3.5-turbo",
        apiKey: String = "sk-test",
        systemPrompt: String = "SYS-PROMPT",
        temperature: Double = 0.3,
    ) = aiCompletionPreset(
        provider = provider,
        model = model,
        apiKey = apiKey,
        baseUrl = server.url("/v1").toString(),
        systemPrompt = systemPrompt,
        temperature = temperature,
    )

    private fun client() = ChatCompletionClient(httpClient = httpClient)

    private fun bodyOfFirstRequest(): JSONObject {
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(
            "端点必须由共享的 ChatEndpointResolver 算出（v1 后缀只补一次）",
            "/v1/chat/completions",
            request.path,
        )
        return JSONObject(request.body.readUtf8())
    }

    // ------------------------------------------------------------ 1. 请求体形状

    @Test
    fun turn_sendsModelTemperatureAndNonStreamingFlag() = runBlocking {
        server.enqueue(completionResponse("你好"))

        val result = runAiCompletionTurn(
            preset = preset(model = "gpt-3.5-turbo", temperature = 0.3),
            userPrompt = "你好吗",
            client = client(),
        )

        assertEquals("正文必须原样映射", "你好", result.content)
        val body = bodyOfFirstRequest()
        assertEquals("gpt-3.5-turbo", body.getString("model"))
        assertEquals(0.3, body.getDouble("temperature"), 1e-9)
        assertFalse("必须显式声明非流式（与 ChatAgent 共用同一套装配）", body.getBoolean("stream"))
    }

    @Test
    fun turn_sendsTheSystemPromptAsTheFirstMessageAndTheUserPromptAsTheSecond() = runBlocking {
        server.enqueue(completionResponse("ok"))

        runAiCompletionTurn(
            preset = preset(systemPrompt = "SYS-PROMPT"),
            userPrompt = "USER-PROMPT",
            client = client(),
        )

        val messages = bodyOfFirstRequest().getJSONArray("messages")
        assertEquals("一次问答只有两条消息（system + user）", 2, messages.length())
        assertEquals("system", messages.getJSONObject(0).getString("role"))
        assertEquals(
            "system 消息必须来自 preset.systemPrompt（改造前就是这么发的）",
            "SYS-PROMPT",
            messages.getJSONObject(0).getString("content"),
        )
        assertEquals("user", messages.getJSONObject(1).getString("role"))
        assertEquals("USER-PROMPT", messages.getJSONObject(1).getString("content"))
    }

    @Test
    fun turn_sendsNoToolsSection() = runBlocking {
        server.enqueue(completionResponse("ok"))

        runAiCompletionTurn(preset = preset(), userPrompt = "hi", client = client())

        val body = bodyOfFirstRequest()
        assertFalse("AIModule 是纯文本问答 ⇒ 请求体里不得有 tools", body.has("tools"))
        assertFalse(
            "没有 tools 就不该有 parallel_tool_calls",
            body.has("parallel_tool_calls"),
        )
    }

    @Test
    fun turn_usesTheApiKeyFromThePreset() = runBlocking {
        server.enqueue(completionResponse("ok"))

        runAiCompletionTurn(preset = preset(apiKey = "sk-from-preset"), userPrompt = "hi", client = client())

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(
            "认证头必须用解析后的 key（引用模式下它来自被引用的配置）",
            "Bearer sk-from-preset",
            request.getHeader("Authorization"),
        )
    }

    // ------------------------------------------------------------ 2. 手动档：与解析器同形

    @Test
    fun manualResolutionProducesExactlyTheAiCompletionPresetShape() {
        val manual = ManualAiConfig(
            provider = "deepseek",
            model = "deepseek-chat",
            apiKey = "sk-manual",
            baseUrl = "https://api.deepseek.com",
            systemPrompt = "SYS",
            temperature = 0.4,
        )

        // 老步骤：**没有** config_source 键 ⇒ 走 manual 分支。
        val resolved = AiModelConfigResolver.resolve(
            parameters = emptyMap(),
            presets = listOf(ChatPresetConfig(id = "cfg-1", model = "SHOULD-NOT-BE-USED")),
            manual = manual,
        )
        assertTrue(resolved is AiModelConfigResolution.Resolved)

        val expected = aiCompletionPreset(
            provider = manual.provider,
            model = manual.model,
            apiKey = manual.apiKey,
            baseUrl = manual.baseUrl,
            systemPrompt = manual.systemPrompt,
            temperature = manual.temperature,
        )
        // ⚠️ `id` 是随机 UUID（两个独立构造的实例永不相等）⇒ 归一化后再比。
        assertEquals(
            "resolve 的 manual 分支与 aiCompletionPreset 必须逐字段同形（漂移会让手动档行为静默变化）",
            expected.copy(id = "normalized"),
            (resolved as AiModelConfigResolution.Resolved).preset.copy(id = "normalized"),
        )
    }

    @Test
    fun referenceResolutionFeedsTheReferencedKeyIntoTheRequest() = runBlocking {
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
                    model = "referenced-model",
                    apiKey = "sk-referenced",
                    baseUrl = server.url("/v1").toString(),
                    systemPrompt = "CONFIG-PROMPT",
                    temperature = 0.9,
                ),
            ),
            manual = ManualAiConfig(
                provider = "custom",
                model = "manual-model",
                apiKey = "sk-manual",
                baseUrl = "https://manual.example.com/v1",
                systemPrompt = "SNAPSHOT-PROMPT",
                temperature = 0.1,
            ),
        )
        assertTrue(resolved is AiModelConfigResolution.Resolved)

        runAiCompletionTurn(
            preset = (resolved as AiModelConfigResolution.Resolved).preset,
            userPrompt = "hi",
            client = client(),
        )

        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals(
            "活引用的 key 必须真的进请求（不是步骤里那个已被清空的）",
            "Bearer sk-referenced",
            request.getHeader("Authorization"),
        )
        val body = JSONObject(request.body.readUtf8())
        assertEquals("model 也是活引用", "referenced-model", body.getString("model"))
        val messages = body.getJSONArray("messages")
        assertEquals(
            "值拷贝快照优先于配置现值（D7）",
            "SNAPSHOT-PROMPT",
            messages.getJSONObject(0).getString("content"),
        )
        assertEquals(0.1, body.getDouble("temperature"), 1e-9)
    }

    // ------------------------------------------------------------ 3. 端点表（有意变更的锁）

    @Test
    fun endpointTable_pinsTheCommonBaseUrlShapes() {
        fun endpoint(provider: String, baseUrl: String): String =
            ChatEndpointResolver.resolve(
                provider = chatProviderForModuleProvider(provider),
                rawBaseUrl = baseUrl,
                mode = ChatEndpointMode.CHAT_COMPLETIONS,
            )

        assertEquals(
            "openai 标准 base_url（与改造前逐字相同）",
            "https://api.openai.com/v1/chat/completions",
            endpoint("openai", "https://api.openai.com/v1"),
        )
        assertEquals(
            "deepseek（与改造前逐字相同）",
            "https://api.deepseek.com/chat/completions",
            endpoint("deepseek", "https://api.deepseek.com"),
        )
        assertEquals(
            "百炼 compatible-mode（与改造前逐字相同）",
            "https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions",
            endpoint("dashscope", "https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
        assertEquals(
            "智谱 paas/v4（与改造前逐字相同）",
            "https://open.bigmodel.cn/api/paas/v4/chat/completions",
            endpoint("bigmodel", "https://open.bigmodel.cn/api/paas/v4"),
        )
        assertEquals(
            "自定义带 path（与改造前逐字相同）",
            "https://my-proxy.example.com/custom/v1/chat/completions",
            endpoint("custom", "https://my-proxy.example.com/custom/v1"),
        )
        assertEquals(
            "结尾斜杠不会双斜杠（与改造前逐字相同）",
            "https://api.openai.com/v1/chat/completions",
            endpoint("openai", "https://api.openai.com/v1/"),
        )
        assertEquals(
            "⚠️ 有意变更：base_url **无 path** 时会补 provider 的 defaultRoot（OPENAI ⇒ /v1）",
            "https://my-proxy.example.com/v1/chat/completions",
            endpoint("custom", "https://my-proxy.example.com"),
        )
        assertEquals(
            "⚠️ 有意变更的逃生门：`#` 后缀绕过规整（既有能力）",
            "https://my-proxy.example.com",
            endpoint("custom", "https://my-proxy.example.com#"),
        )
    }

    // ------------------------------------------------------------ 4. 参数形态

    @Test
    fun moduleExposesTheConfigReferenceParametersWithManualAsTheDefault() {
        val inputs = AIModule().getInputs().associateBy { it.id }

        assertNotNull("必须含 config_source", inputs[AiModelConfigRef.PARAM_SOURCE])
        assertNotNull("必须含 model_config_id", inputs[AiModelConfigRef.PARAM_CONFIG_ID])
        assertEquals(
            "默认 manual ⇒ 老步骤零迁移",
            AiModelConfigRef.SOURCE_MANUAL,
            inputs[AiModelConfigRef.PARAM_SOURCE]!!.defaultValue,
        )
        listOf("provider", "base_url", "api_key", "model", "prompt", "system_prompt", "temperature")
            .forEach { id ->
                assertTrue("既有参数一个都不能少（只追加、不替换）：$id", inputs.containsKey(id))
            }
    }
}
