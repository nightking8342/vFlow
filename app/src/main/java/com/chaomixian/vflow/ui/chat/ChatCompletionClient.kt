package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.logging.DebugLogger
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

internal const val CHAT_MAX_TOOL_RESULT_INPUT_CHARS = 1_600

// ⚠️ `stripInlineToolMarkup` / `normalizeAssistantReply` / `firstNonBlank` 已搬到
// `ChatReplyNormalizer.kt` —— 因为流式路径必须与它们**共用同一份定义**
// （复制一份必然漂移，且漂移是静默的，见该文件头部说明）。

internal object ChatToolResultInputFormatter {
    fun format(
        message: ChatMessage,
        toolResult: ChatToolResult,
        toolDefinitions: List<ChatAgentToolDefinition> = emptyList(),
    ): String {
        val raw = message.content.ifBlank { toolResult.outputText }.trim()

        // 声明为不可截断的工具（技能正文、模块 schema）原样返回：
        // 加载它们就是为了拿到全部内容，截断等于让这次调用白做。
        // 工具定义查不到时按可截断处理——与改造前的行为保持一致。
        val truncatable = toolDefinitions
            .firstOrNull { it.name == toolResult.name }
            ?.truncatable
            ?: true
        if (!truncatable) return raw

        if (raw.length <= CHAT_MAX_TOOL_RESULT_INPUT_CHARS) return raw

        val marker = buildTruncationNotice(raw.length, toolResult.name)
        val maxArtifactBudget = (CHAT_MAX_TOOL_RESULT_INPUT_CHARS - marker.length - 2).coerceAtLeast(0)
        val artifactSection = buildArtifactSection(toolResult.artifacts, maxArtifactBudget)
        val suffix = listOf(marker, artifactSection.takeIf { it.isNotBlank() })
            .filterNotNull()
            .joinToString(separator = "\n\n")
        val headBudget = (CHAT_MAX_TOOL_RESULT_INPUT_CHARS - suffix.length - 2).coerceAtLeast(0)
        val head = raw.take(headBudget).trimEnd()

        return listOf(head.takeIf { it.isNotBlank() }, suffix)
            .filterNotNull()
            .joinToString(separator = "\n\n")
    }

    /**
     * 构造截断告知。
     *
     * **为什么不能只写 `... truncated`**：模型看到半份输出却以为拿到全份，
     * 就会基于残缺信息继续推理——那正是「病症 A」（模型不知道手里有什么）
     * 换个地方复发。告知必须让模型知道：**被截掉的是尾部、还有多少没看到、
     * 以及下次怎么避免**。
     *
     * 针对已知的「可参数收窄」工具给出具体建议；其余工具只能就事论事说明
     * 还有多少没读到。**不在此处为单个工具堆特例分支**——新工具应当靠
     * 自己的 description 说清如何收窄（与 `query_module_schema` 撤掉函数
     * 工作流特化的同一条原则）。
     */
    private fun buildTruncationNotice(originalChars: Int, toolName: String): String {
        // 保留长度取「约等于预算」而非精确值：notice 自身也计入预算，
        // 报精确的 CHAT_MAX_TOOL_RESULT_INPUT_CHARS 会与实际的 head 长度对不上。
        val omitted = (originalChars - CHAT_MAX_TOOL_RESULT_INPUT_CHARS).coerceAtLeast(0)
        return buildString {
            append("[output truncated: showing only the first ~")
            append(CHAT_MAX_TOOL_RESULT_INPUT_CHARS)
            append(" characters of ")
            append(originalChars)
            append("; the tail (~")
            append(omitted)
            append(" characters) was dropped. ")
            when (toolName) {
                "vflow_agent_observe_ui" ->
                    append("Re-call with a smaller `limit` for fewer element handles.")
                "vflow_agent_read_page_content" ->
                    append("Re-call with `mode=\"primary_content\"` for main content only.")
                else ->
                    append("Narrow the request if you need the omitted part.")
            }
            append("]")
        }
    }

    private fun buildArtifactSection(
        artifacts: List<ChatArtifactReference>,
        budget: Int,
    ): String {
        if (artifacts.isEmpty() || budget <= 0) return ""

        val lines = mutableListOf<String>()
        var remaining = budget

        fun appendLine(line: String): Boolean {
            val lineCost = if (lines.isEmpty()) line.length else line.length + 1
            if (lineCost > remaining) return false
            lines += line
            remaining -= lineCost
            return true
        }

        if (!appendLine("Artifacts:")) return ""

        artifacts.forEach { artifact ->
            val line = "- ${artifact.key} (${artifact.typeLabel}): ${artifact.handle}"
            if (!appendLine(line)) return@forEach
        }

        return lines.joinToString(separator = "\n")
    }
}

internal class ChatCompletionClient(
    private val httpClient: OkHttpClient = sharedHttpClient,
) {
    suspend fun generateReply(
        preset: ChatPresetConfig,
        history: List<ChatMessage>,
        skillSelection: ChatAgentSkillSelection = ChatAgentSkillSelection.EMPTY,
    ): ChatCompletionResult = withContext(Dispatchers.IO) {
        DebugLogger.i(
            LOG_TAG,
            "Generating reply provider=${preset.providerEnum.storageValue} model=${preset.model} history=${history.size} tools=${skillSelection.availableTools.joinToString { it.name }} lastUser=${history.lastOrNull { it.role == ChatMessageRole.USER }?.content.orEmpty().compactForLog()}"
        )
        val request = ChatProviderRequest(
            preset = preset,
            history = history.filter { it.role != ChatMessageRole.ERROR },
            skillSelection = skillSelection,
        )
        val adapter = when (preset.providerEnum) {
            ChatProvider.OPENAI -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.DEEPSEEK -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.OPENROUTER -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.OLLAMA -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.ANTHROPIC -> AnthropicChatAdapter(httpClient)
        }
        adapter.complete(request).also { result ->
            DebugLogger.i(
                LOG_TAG,
                "Reply ready provider=${preset.providerEnum.storageValue} model=${preset.model} tokens=${result.totalTokens ?: -1} reasoningChars=${result.reasoningContent?.length ?: 0} toolCalls=${result.toolCalls.joinToString { it.name }} content=${result.content.compactForLog()}"
            )
        }
    }

    /**
     * **流式入口**（`chat-streaming-design.md` §4.2 / §5.1）。
     *
     * 与非流式 [generateReply] 并列，但**不替代**它：
     * `ChatBenchmarkRunner`（`:352`）继续用 `generateReply`——基准测试不需要中间态。
     *
     * ⚠️ 三个易错点：
     * 1. **Responses 路径自动退化为非流式**（`stream()` 的默认实现），
     *    调用方**无需**分支——`useResponsesApi=true` 时本方法仍可用，只是不发中间事件。
     * 2. **本方法不 `withContext`**：线程切换由 SSE 层内部的 [ChatSse.frames] `flowOn` 负责。
     *    若在这里包 `withContext(IO)`，Flow 的收集仍会回到调用方线程（Flow 不是 suspend 值），
     *    看起来「切了线程」其实没切。
     * 3. **HTTP 非 2xx 时抛异常**（不是发个事件就算了）——上层需要「这轮失败」的信号，
     *    否则会走正常收尾、把半截内容当完整回复落盘。
     */
    fun streamReply(
        preset: ChatPresetConfig,
        history: List<ChatMessage>,
        skillSelection: ChatAgentSkillSelection = ChatAgentSkillSelection.EMPTY,
    ): Flow<ChatStreamEvent> {
        DebugLogger.i(
            LOG_TAG,
            "Streaming reply provider=${preset.providerEnum.storageValue} model=${preset.model} history=${history.size}"
        )
        val request = ChatProviderRequest(
            preset = preset,
            history = history.filter { it.role != ChatMessageRole.ERROR },
            skillSelection = skillSelection,
        )
        val adapter = when (preset.providerEnum) {
            ChatProvider.OPENAI -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.DEEPSEEK -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.OPENROUTER -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.OLLAMA -> OpenAICompatibleChatAdapter(httpClient, preset.providerEnum)
            ChatProvider.ANTHROPIC -> AnthropicChatAdapter(httpClient)
        }
        return adapter.stream(request)
    }

    private companion object {
        private const val LOG_TAG = "ChatCompletion"
        private val json = Json {
            ignoreUnknownKeys = true
            encodeDefaults = false
        }
        private val jsonMediaType = "application/json; charset=utf-8".toMediaType()
        private val sharedHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(120, TimeUnit.SECONDS)
                .build()
        }
    }

    private data class ChatProviderRequest(
        val preset: ChatPresetConfig,
        val history: List<ChatMessage>,
        val skillSelection: ChatAgentSkillSelection,
    )

    private interface ChatProviderAdapter {
        suspend fun complete(request: ChatProviderRequest): ChatCompletionResult

        /**
         * 流式。**默认实现退化为 [complete]**（`chat-streaming-design.md` §5.1）。
         *
         * ⚠️ 这个默认实现是**收敛不确定性的关键**：`ChatProviderAdapter` 是内部接口、
         * 未来加新 provider（或 Responses 路径要做流式）时无需立刻实现流式，
         * 也不会破坏任何调用点——表现为「不发中间事件的流」，行为与改动前完全一致。
         *
         * ⚠️ 默认实现是**非 suspend** 的，且用 `flow {}` 包住 suspend 的 [complete]：
         * `flow` 的 block 本身是 suspend 的，故合法。**不要**改成
         * `fun stream(...) = flowOf(Completed(runBlocking { complete() }))`——
         * 那会阻塞调用线程，而这里需要的是「收集时才执行」的懒语义。
         */
        fun stream(request: ChatProviderRequest): Flow<ChatStreamEvent> = flow {
            emit(ChatStreamEvent.Completed(complete(request)))
        }
    }

    private inner class OpenAICompatibleChatAdapter(
        private val httpClient: OkHttpClient,
        private val provider: ChatProvider,
    ) : ChatProviderAdapter {

        override suspend fun complete(request: ChatProviderRequest): ChatCompletionResult {
            val mode = if (request.preset.providerEnum == ChatProvider.OPENAI && request.preset.useResponsesApi) {
                ChatEndpointMode.RESPONSES
            } else {
                ChatEndpointMode.CHAT_COMPLETIONS
            }
            val url = ChatEndpointResolver.resolve(
                provider = provider,
                rawBaseUrl = request.preset.baseUrl,
                mode = mode,
            )
            val payload = when (mode) {
                ChatEndpointMode.CHAT_COMPLETIONS -> buildChatCompletionPayload(request)
                ChatEndpointMode.RESPONSES -> buildResponsesPayload(request)
                ChatEndpointMode.ANTHROPIC_MESSAGES -> error("Unsupported OpenAI adapter mode")
            }
            val root = executeJsonRequest(
                url = url,
                payload = payload,
                headers = buildHeaders(request.preset),
            )
            return when (mode) {
                ChatEndpointMode.CHAT_COMPLETIONS -> parseChatCompletion(root)
                ChatEndpointMode.RESPONSES -> parseResponsesCompletion(root)
                ChatEndpointMode.ANTHROPIC_MESSAGES -> error("Unsupported OpenAI adapter mode")
            }
        }

        /**
         * 流式实现（`chat-streaming-design.md` §4.3.1）。
         *
         * ⚠️ **Responses 路径不在此实现流式**——`useResponsesApi=true` 时
         * **含 Responses 路径**（`useResponsesApi = true` 时走 [ChatStreamProtocol.OPENAI_RESPONSES]）。
         *
         * ⚠️ v5 起 Responses **不再退化为非流式**：它的流式事件模型此前被判为
         * 「无权威源」（U1），但官方 SDK 从 OpenAPI spec 自动生成的类型定义
         * （`openai/types/responses/response_stream_event.py`）是完整可得的权威源，
         * 故两套协议都走真正的流式。
         */
        override fun stream(request: ChatProviderRequest): Flow<ChatStreamEvent> {
            val useResponses = request.preset.providerEnum == ChatProvider.OPENAI &&
                request.preset.useResponsesApi
            val mode = if (useResponses) {
                ChatEndpointMode.RESPONSES
            } else {
                ChatEndpointMode.CHAT_COMPLETIONS
            }
            val protocol = if (useResponses) {
                ChatStreamProtocol.OPENAI_RESPONSES
            } else {
                ChatStreamProtocol.OPENAI_CHAT
            }
            val url = ChatEndpointResolver.resolve(
                provider = provider,
                rawBaseUrl = request.preset.baseUrl,
                mode = mode,
            )
            val payload = when (mode) {
                ChatEndpointMode.RESPONSES -> buildResponsesPayload(request, streaming = true)
                else -> buildChatCompletionPayload(request, streaming = true)
            }
            val httpRequest = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .apply { buildStreamHeaders(request.preset).forEach { (k, v) -> header(k, v) } }
                .build()
            return ChatStreamRunner.run(
                frames = ChatSse.frames(httpRequest),
                assembler = ChatStreamAssembler(protocol),
            )
        }

        private fun buildHeaders(preset: ChatPresetConfig): Map<String, String> {
            val headers = linkedMapOf(
                "Content-Type" to "application/json",
                "Accept" to "application/json",
            )
            if (preset.apiKey.isNotBlank() && provider != ChatProvider.OLLAMA) {
                headers["Authorization"] = "Bearer ${preset.apiKey}"
            }
            if (provider == ChatProvider.OPENROUTER) {
                headers["X-Title"] = "vFlow"
            }
            return headers
        }

        /**
         * 流式请求头。
         *
         * ⚠️ **B3 / F19：`Accept` 必须是 `text/event-stream`**，不能沿用 [buildHeaders] 的
         * `application/json`。多数服务端不校验 `Accept`（所以沿用也能跑），但严格的会
         * 拒绝或**返回非流式响应**——后者被当 SSE 解析时没有任何 `data:` 行，
         * 表现为**静默无输出**，继而退化成「一直转圈、没有报错」的挂死形态。
         *
         * ⚠️ 认证头等其余字段与 [buildHeaders]**保持一致**，只换 `Accept`。
         */
        private fun buildStreamHeaders(preset: ChatPresetConfig): Map<String, String> {
            return buildHeaders(preset) + ("Accept" to "text/event-stream")
        }

        /**
         * @param streaming 是否走流式。⚠️ 非流式路径恒传 `false`（默认值），
         *   故既有调用点**无需改动**。
         */
        private fun buildChatCompletionPayload(
            request: ChatProviderRequest,
            streaming: Boolean = false,
        ): JsonObject {
            return buildJsonObject {
                put("model", request.preset.model)
                put("temperature", request.preset.temperature)
                put("stream", streaming)
                // ⚠️ **按 provider 开关，不是无条件加**（§3.3-6）：
                // 有些 OpenAI 兼容网关不认 `stream_options`，**直接报错**（不是忽略）。
                // Ollama 与 OpenRouter 的支持情况未实测（设计文档 §5.3 的 U4），
                // 故**只对确定支持的 provider 开启**——保守取「宁可不报 usage，也不要整条流失败」。
                if (streaming && supportsUsageInStreaming(request.preset.providerEnum)) {
                    put(
                        "stream_options",
                        buildJsonObject { put("include_usage", true) },
                    )
                }
                if (request.skillSelection.availableTools.isNotEmpty()) {
                    put("parallel_tool_calls", false)
                    put(
                        "tools",
                        buildJsonArray {
                            buildOpenAiChatToolDefinitions(request.skillSelection.availableTools).forEach(::add)
                        }
                    )
                }
                put(
                    "messages",
                    buildJsonArray {
                        buildChatCompletionHistoryMessages(request).forEach(::add)
                    }
                )
            }
        }

        private fun buildResponsesPayload(
            request: ChatProviderRequest,
            streaming: Boolean = false,
        ): JsonObject {
            return buildJsonObject {
                put("model", request.preset.model)
                put("temperature", request.preset.temperature)
                put("store", false)
                // ⚠️ Responses 用 `stream: true` 开启流式，**不需要** `stream_options`——
                // 它的 usage 直接挂在 `response.completed` 事件的 `response.usage` 里
                // （官方类型定义 `response_completed_event.py` 明确如此），
                // 故不存在 chat/completions 那个「必须显式请求才给 usage」的问题（§3.3-6 只适用于后者）。
                if (streaming) put("stream", true)
                if (request.skillSelection.availableTools.isNotEmpty()) {
                    put("parallel_tool_calls", false)
                    put(
                        "tools",
                        buildJsonArray {
                            buildOpenAiResponsesToolDefinitions(request.skillSelection.availableTools).forEach(::add)
                        }
                    )
                }
                put(
                    "input",
                    buildJsonArray {
                        buildResponsesInputItems(request).forEach(::add)
                    }
                )
            }
        }

        private fun buildChatCompletionHistoryMessages(request: ChatProviderRequest): List<JsonObject> {
            val items = mutableListOf<JsonObject>()
            val systemPrompt = buildSystemPrompt(request)
            if (systemPrompt.isNotBlank()) {
                items += buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            }
            request.history.forEach { message ->
                when (message.role) {
                    ChatMessageRole.USER -> {
                        val content = message.content.trim()
                        if (content.isBlank()) return@forEach
                        items += buildJsonObject {
                            put("role", "user")
                            put("content", content)
                        }
                    }

                    ChatMessageRole.ASSISTANT -> {
                        val assistantObject = buildJsonObject {
                            put("role", "assistant")
                            if (message.content.isNotBlank()) {
                                put("content", message.content)
                            } else {
                                put("content", JsonNull)
                            }
                            if (message.toolCalls.isNotEmpty()) {
                                put(
                                    "tool_calls",
                                    buildJsonArray {
                                        message.toolCalls.forEach { toolCall ->
                                            add(
                                                buildJsonObject {
                                                    put("id", toolCall.id ?: "call_${message.id}")
                                                    put("type", "function")
                                                    put(
                                                        "function",
                                                        buildJsonObject {
                                                            put("name", toolCall.name)
                                                            put("arguments", toolCall.argumentsJson)
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        }
                        if (message.content.isNotBlank() || message.toolCalls.isNotEmpty()) {
                            items += assistantObject
                        }
                    }

                    ChatMessageRole.TOOL -> {
                        val toolResult = message.toolResult ?: return@forEach
                        val callId = toolResult.callId ?: return@forEach
                        items += buildJsonObject {
                            put("role", "tool")
                            put("tool_call_id", callId)
                            put(
                                "content",
                                ChatToolResultInputFormatter.format(
                                    message,
                                    toolResult,
                                    request.skillSelection.availableTools,
                                )
                            )
                        }
                    }

                    ChatMessageRole.ERROR -> Unit
                }
            }
            return items
        }

        private fun buildResponsesInputItems(request: ChatProviderRequest): List<JsonObject> {
            val items = mutableListOf<JsonObject>()
            val systemPrompt = buildSystemPrompt(request)
            if (systemPrompt.isNotBlank()) {
                items += buildMessageInput(role = "system", text = systemPrompt)
            }
            request.history.forEach { message ->
                when (message.role) {
                    ChatMessageRole.USER -> {
                        val content = message.content.trim()
                        if (content.isBlank()) return@forEach
                        items += buildMessageInput(role = "user", text = content)
                    }

                    ChatMessageRole.ASSISTANT -> {
                        val content = message.content.trim()
                        if (content.isNotBlank()) {
                            items += buildMessageInput(role = "assistant", text = content)
                        }
                        message.toolCalls.forEach { toolCall ->
                            items += buildJsonObject {
                                put("type", "function_call")
                                put("call_id", toolCall.id ?: "call_${message.id}")
                                put("name", toolCall.name)
                                put("arguments", toolCall.argumentsJson)
                            }
                        }
                    }

                    ChatMessageRole.TOOL -> {
                        val toolResult = message.toolResult ?: return@forEach
                        val callId = toolResult.callId ?: return@forEach
                        items += buildJsonObject {
                            put("type", "function_call_output")
                            put("call_id", callId)
                            put(
                                "output",
                                ChatToolResultInputFormatter.format(
                                    message,
                                    toolResult,
                                    request.skillSelection.availableTools,
                                )
                            )
                        }
                    }

                    ChatMessageRole.ERROR -> Unit
                }
            }
            return items
        }

        private fun parseChatCompletion(root: JsonObject): ChatCompletionResult {
            val payload = unwrapPayload(root, expectedKey = "choices")
            extractServiceError(payload)?.let { throw IllegalStateException(it) }
            val choice = payload["choices"]?.jsonArray?.firstOrNull()?.jsonObject
                ?: throw IllegalStateException("模型没有返回有效的 choices。")
            val message = choice["message"]?.jsonObject
                ?: throw IllegalStateException("模型没有返回有效的 message。")
            val normalized = normalizeAssistantReply(
                content = extractTextContent(message["content"]),
                reasoningContent = firstNonBlank(
                    extractTextContent(message["reasoning_content"]),
                    extractReasoningValue(message["reasoning"]),
                    extractReasoningFromContentArray(message["content"]),
                ),
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = extractTotalTokens(payload["usage"]),
                toolCalls = parseOpenAiToolCalls(message["tool_calls"]),
            )
        }

        private fun parseResponsesCompletion(root: JsonObject): ChatCompletionResult {
            val payload = unwrapPayload(root, expectedKey = "output")
            extractServiceError(payload)?.let { throw IllegalStateException(it) }
            val textChunks = mutableListOf<String>()
            val reasoningChunks = mutableListOf<String>()
            val toolCalls = mutableListOf<ChatToolCall>()

            payload["output_text"]?.jsonPrimitive?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?.let(textChunks::add)

            payload["output"]?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "message" -> {
                        obj["content"]?.jsonArray?.forEach { part ->
                            val partObject = part.jsonObject
                            when (partObject["type"]?.jsonPrimitive?.contentOrNull) {
                                "output_text", "text" -> partObject["text"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(textChunks::add)
                                "reasoning_text" -> partObject["text"]?.jsonPrimitive?.contentOrNull
                                    ?.takeIf { it.isNotBlank() }
                                    ?.let(reasoningChunks::add)
                            }
                        }
                    }

                    "reasoning" -> {
                        obj["summary"]?.jsonArray?.forEach { summary ->
                            summary.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let(reasoningChunks::add)
                        }
                        obj["content"]?.jsonArray?.forEach { part ->
                            part.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                                ?.takeIf { it.isNotBlank() }
                                ?.let(reasoningChunks::add)
                        }
                    }

                    "function_call" -> {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (name.isNotBlank()) {
                            toolCalls += ChatToolCall(
                                id = obj["call_id"]?.jsonPrimitive?.contentOrNull
                                    ?: obj["id"]?.jsonPrimitive?.contentOrNull,
                                name = name,
                                argumentsJson = obj["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                            )
                        }
                    }
                }
            }

            val normalized = normalizeAssistantReply(
                content = textChunks.joinToString(separator = "").trim(),
                reasoningContent = reasoningChunks.joinToString(separator = "\n\n").trim().ifBlank { null },
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = extractResponsesTotalTokens(payload["usage"]),
                toolCalls = toolCalls,
            )
        }

        private fun parseOpenAiToolCalls(element: JsonElement?): List<ChatToolCall> {
            val toolCalls = element as? JsonArray ?: return emptyList()
            return toolCalls.mapNotNull { item ->
                val obj = item.jsonObject
                val function = obj["function"]?.jsonObject ?: return@mapNotNull null
                val name = function["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (name.isBlank()) return@mapNotNull null
                ChatToolCall(
                    id = obj["id"]?.jsonPrimitive?.contentOrNull,
                    name = name,
                    argumentsJson = function["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                )
            }
        }
    }

    private inner class AnthropicChatAdapter(
        private val httpClient: OkHttpClient,
    ) : ChatProviderAdapter {

        /**
         * 流式实现（`chat-streaming-design.md` §4.3.2）。
         *
         * ⚠️ payload 与 `complete()` **共用** [buildMessagesPayload]，只多一个 `stream: true`。
         * 两个 `cache_control` 断点的位置对缓存命中是**关键**的
         * （断点前移/减少都会让缓存每轮重写），故绝不能各写一份。
         */
        override fun stream(request: ChatProviderRequest): Flow<ChatStreamEvent> {
            val url = ChatEndpointResolver.resolve(
                provider = ChatProvider.ANTHROPIC,
                rawBaseUrl = request.preset.baseUrl,
                mode = ChatEndpointMode.ANTHROPIC_MESSAGES,
            )
            val payload = buildMessagesPayload(request, streaming = true)
            val httpRequest = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(jsonMediaType))
                .apply {
                    anthropicHeaders(request.preset, streaming = true).forEach { (k, v) -> header(k, v) }
                }
                .build()
            return ChatStreamRunner.run(
                frames = ChatSse.frames(httpRequest),
                assembler = ChatStreamAssembler(ChatStreamProtocol.ANTHROPIC_MESSAGES),
            )
        }

        /**
         * Anthropic 的请求头。
         *
         * ⚠️ **B3 / F19：流式必须把 `Accept` 换成 `text/event-stream`**，
         * 理由同 OpenAI 路径的 [buildStreamHeaders]。
         */
        private fun anthropicHeaders(
            preset: ChatPresetConfig,
            streaming: Boolean,
        ): Map<String, String> = linkedMapOf(
            "Content-Type" to "application/json",
            "Accept" to if (streaming) "text/event-stream" else "application/json",
            "x-api-key" to preset.apiKey,
            "anthropic-version" to "2023-06-01",
        )

        /**
         * Anthropic messages 的请求体。`complete()` 与 `stream()` **共用**本函数。
         *
         * Anthropic 的 prompt 缓存是「前缀缓存」：给某个块打上 cache_control，
         * 该块及其之前的全部内容（system + tools 前缀）都会被缓存复用。
         *
         * 打两个断点（参照 Pi 的做法）：
         *   1. system 段末尾——覆盖系统提示词
         *   2. tools 数组最后一个工具——覆盖「system + 全部工具定义」
         * 这两段在会话内是稳定的（工具固定 16 个、技能目录为空），故每轮都能命中；
         * 而 messages 每轮都变，缓存价值低且会挤占断点额度（Anthropic 上限 4 个），故不予标记。
         *
         * ⚠️ 流式下这两个断点**同样有效**，但要注意 prompt 缓存的命中还取决于
         * 请求的**前缀是否逐字节一致**——故 `stream` 字段的位置不影响缓存
         * （它在 JSON 里的位置与缓存前缀无关，缓存看的是渲染后的内容块）。
         */
        private fun buildMessagesPayload(
            request: ChatProviderRequest,
            streaming: Boolean = false,
        ): JsonObject = buildJsonObject {
            put("model", request.preset.model)
            put("max_tokens", 4096)
            put("temperature", request.preset.temperature)
            if (streaming) put("stream", true)
            put(
                "system",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", buildSystemPrompt(request))
                            put("cache_control", ephemeralCacheControl())
                        }
                    )
                }
            )
            if (request.skillSelection.availableTools.isNotEmpty()) {
                put(
                    "tools",
                    buildJsonArray {
                        buildAnthropicToolDefinitions(request.skillSelection.availableTools).forEach(::add)
                    }
                )
            }
            put(
                "messages",
                buildJsonArray {
                    buildAnthropicHistoryMessages(request).forEach(::add)
                }
            )
        }

        override suspend fun complete(request: ChatProviderRequest): ChatCompletionResult {
            val url = ChatEndpointResolver.resolve(
                provider = ChatProvider.ANTHROPIC,
                rawBaseUrl = request.preset.baseUrl,
                mode = ChatEndpointMode.ANTHROPIC_MESSAGES,
            )
            val payload = buildMessagesPayload(request)
            val root = executeJsonRequest(
                url = url,
                payload = payload,
                headers = anthropicHeaders(request.preset, streaming = false),
            )
            extractServiceError(root)?.let { throw IllegalStateException(it) }

            val textChunks = mutableListOf<String>()
            val reasoningChunks = mutableListOf<String>()
            val toolCalls = mutableListOf<ChatToolCall>()
            root["content"]?.jsonArray?.forEach { item ->
                val obj = item.jsonObject
                when (obj["type"]?.jsonPrimitive?.contentOrNull) {
                    "text" -> obj["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let(textChunks::add)
                    "thinking" -> obj["thinking"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotBlank() }
                        ?.let(reasoningChunks::add)
                    "tool_use" -> {
                        val name = obj["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        if (name.isNotBlank()) {
                            toolCalls += ChatToolCall(
                                id = obj["id"]?.jsonPrimitive?.contentOrNull,
                                name = name,
                                argumentsJson = obj["input"]?.toString() ?: "{}",
                            )
                        }
                    }
                }
            }

            val normalized = normalizeAssistantReply(
                content = textChunks.joinToString(separator = "").trim(),
                reasoningContent = reasoningChunks.joinToString(separator = "\n\n").trim().ifBlank { null },
            )
            return ChatCompletionResult(
                content = normalized.content,
                reasoningContent = normalized.reasoningContent,
                totalTokens = extractAnthropicTotalTokens(root["usage"]),
                toolCalls = toolCalls,
                cacheCreationTokens = extractAnthropicCacheTokens(root["usage"], "cache_creation_input_tokens"),
                cacheReadTokens = extractAnthropicCacheTokens(root["usage"], "cache_read_input_tokens"),
                cacheDeletedTokens = extractAnthropicCacheTokens(root["usage"], "cache_deleted_input_tokens"),
            )
        }
    }

    private fun executeJsonRequest(
        url: String,
        payload: JsonObject,
        headers: Map<String, String>,
    ): JsonObject {
        DebugLogger.d(
            LOG_TAG,
            "HTTP request url=$url payloadChars=${payload.toString().length} headers=${headers.keys.joinToString()}"
        )
        val requestBuilder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(jsonMediaType))
        headers.forEach { (name, value) ->
            if (value.isNotBlank()) {
                requestBuilder.header(name, value)
            }
        }
        httpClient.newCall(requestBuilder.build()).execute().use { response ->
            val rawBody = response.body?.string().orEmpty().trim()
            val root = parseJsonObject(rawBody)
            val errorMessage = root?.let(::extractServiceError)
            DebugLogger.d(
                LOG_TAG,
                "HTTP response url=$url code=${response.code} bodyChars=${rawBody.length} error=${errorMessage ?: "none"}"
            )
            if (!response.isSuccessful) {
                val detail = errorMessage ?: rawBody.ifBlank { "HTTP ${response.code}" }
                throw IllegalStateException("Status code: ${response.code}\nError body:\n$detail")
            }
            if (root == null) {
                throw IllegalStateException("服务返回了无法解析的 JSON 响应。")
            }
            errorMessage?.let { throw IllegalStateException(it) }
            return root
        }
    }

    private fun parseJsonObject(rawBody: String): JsonObject? {
        if (rawBody.isBlank()) return null
        val element = runCatching { json.parseToJsonElement(rawBody) }.getOrNull() ?: return null
        return element as? JsonObject
    }

    private fun unwrapPayload(root: JsonObject, expectedKey: String): JsonObject {
        if (root.containsKey(expectedKey)) return root
        val nested = root["data"] as? JsonObject
        if (nested != null && nested.containsKey(expectedKey)) {
            return nested
        }
        return root
    }

    private fun extractServiceError(root: JsonObject): String? {
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            return when (error) {
                is JsonObject -> {
                    val message = error["message"]?.jsonPrimitive?.contentOrNull
                        ?: error["msg"]?.jsonPrimitive?.contentOrNull
                        ?: error["detail"]?.jsonPrimitive?.contentOrNull
                    val type = error["type"]?.jsonPrimitive?.contentOrNull
                    if (message.isNullOrBlank()) {
                        type
                    } else if (type.isNullOrBlank()) {
                        message
                    } else {
                        "$message [$type]"
                    }
                }

                is JsonPrimitive -> error.contentOrNull
                else -> null
            }
        }
        val type = root["type"]?.jsonPrimitive?.contentOrNull
        val topLevelMessage = root["message"]?.jsonPrimitive?.contentOrNull
            ?: root["msg"]?.jsonPrimitive?.contentOrNull
            ?: root["detail"]?.jsonPrimitive?.contentOrNull
        return if (type == "error" && !topLevelMessage.isNullOrBlank()) {
            topLevelMessage
        } else {
            topLevelMessage
        }
    }

    private fun extractTextContent(element: JsonElement?): String {
        return when (element) {
            null, JsonNull -> ""
            is JsonPrimitive -> element.contentOrNull.orEmpty()
            is JsonArray -> element.joinToString(separator = "") { part ->
                val obj = part as? JsonObject ?: return@joinToString ""
                obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
            is JsonObject -> {
                element["text"]?.jsonPrimitive?.contentOrNull
                    ?: element["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }
            else -> ""
        }
    }

    private fun extractReasoningFromContentArray(element: JsonElement?): String? {
        val array = element as? JsonArray ?: return null
        return array.mapNotNull { item ->
            val obj = item as? JsonObject ?: return@mapNotNull null
            if (obj["type"]?.jsonPrimitive?.contentOrNull == "reasoning_text") {
                obj["text"]?.jsonPrimitive?.contentOrNull
            } else {
                null
            }
        }.joinToString(separator = "\n\n").trim().ifBlank { null }
    }

    private fun extractReasoningValue(element: JsonElement?): String? {
        return when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.trim()?.ifBlank { null }
            is JsonArray -> element.joinToString(separator = "\n\n") { extractTextContent(it) }
                .trim()
                .ifBlank { null }
            is JsonObject -> {
                val summary = (element["summary"] as? JsonArray)
                    ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                    ?.joinToString(separator = "\n\n")
                    ?.trim()
                    .orEmpty()
                val content = extractTextContent(element["content"])
                firstNonBlank(summary, content)
            }
            else -> null
        }
    }

    private fun extractTotalTokens(element: JsonElement?): Int? {
        val usage = element as? JsonObject ?: return null
        return usage["total_tokens"]?.jsonPrimitive?.intOrNull
            ?: listOf(
                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
            ).filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    }

    private fun extractResponsesTotalTokens(element: JsonElement?): Int? {
        val usage = element as? JsonObject ?: return null
        return usage["total_tokens"]?.jsonPrimitive?.intOrNull
            ?: listOf(
                usage["input_tokens"]?.jsonPrimitive?.intOrNull,
                usage["output_tokens"]?.jsonPrimitive?.intOrNull,
                usage["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                usage["completion_tokens"]?.jsonPrimitive?.intOrNull,
            ).filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    }

    private fun extractAnthropicTotalTokens(element: JsonElement?): Int? {
        val usage = element as? JsonObject ?: return null
        return listOf(
            usage["input_tokens"]?.jsonPrimitive?.intOrNull,
            usage["output_tokens"]?.jsonPrimitive?.intOrNull,
        ).filterNotNull().takeIf { it.isNotEmpty() }?.sum()
    }

    /**
     * 取 Anthropic 缓存明细里的单个字段。
     *
     * **缺字段时返回 null 而不是 0**：两者含义不同——null 是「服务端没报这个字段」，
     * 0 是「报了且为零」。缓存命中的判据（`cache_read > 0`）只关心后者，
     * 而日志里若把 null 打成 0，会把「没报告」误读成「未命中」。
     */
    private fun extractAnthropicCacheTokens(element: JsonElement?, field: String): Int? {
        val usage = element as? JsonObject ?: return null
        return usage[field]?.jsonPrimitive?.intOrNull
    }

    // 注：`firstNonBlank` 已迁为 `ChatReplyNormalizer.kt` 的顶层函数（同一语义），
    // 本文件内的调用点（`extractReasoningValue` 等）解析到那一份，故不再保留类内副本。

    /**
     * 该 provider 是否支持 `stream_options.include_usage`。
     *
     * ⚠️ **不开只有代价、不报错**：拿不到 usage ⇒ 页脚不显示 token（`tokenCount = null`）。
     * 而**开错会整条流失败**（部分网关对未知字段直接 400）。
     * 故策略是「只对确定支持的开启」，其余留待实测（设计文档 §5.3 的 U4）。
     *
     * | provider | 依据 |
     * |---|---|
     * | `openai` / `deepseek` | 官方支持该字段 |
     * | `openrouter` | 透传上游，但**未实测** ⇒ 暂不开 |
     * | `ollama` | 走 `/v1/chat/completions` 兼容层，**未实测** ⇒ 暂不开 |
     */
    private fun supportsUsageInStreaming(provider: ChatProvider): Boolean {
        return when (provider) {
            ChatProvider.OPENAI, ChatProvider.DEEPSEEK -> true
            ChatProvider.OPENROUTER, ChatProvider.OLLAMA, ChatProvider.ANTHROPIC -> false
        }
    }

    private fun buildSystemPrompt(request: ChatProviderRequest): String {
        return ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = request.preset.systemPrompt,
            skillSelection = request.skillSelection,
        )
    }

    private fun buildMessageInput(role: String, text: String): JsonObject {
        return buildJsonObject {
            put("type", "message")
            put("role", role)
            put(
                "content",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        }
                    )
                }
            )
        }
    }

    private fun buildOpenAiChatToolDefinitions(
        tools: List<ChatAgentToolDefinition>,
    ): List<JsonObject> {
        return tools.map { tool ->
            buildJsonObject {
                put("type", "function")
                put(
                    "function",
                    buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("parameters", tool.inputSchema)
                    }
                )
            }
        }
    }

    private fun buildOpenAiResponsesToolDefinitions(
        tools: List<ChatAgentToolDefinition>,
    ): List<JsonObject> {
        return tools.map { tool ->
            buildJsonObject {
                put("type", "function")
                put("name", tool.name)
                put("description", tool.description)
                put("parameters", tool.inputSchema)
            }
        }
    }

    /**
     * Anthropic 的短时缓存标记。打在段末尾，表示「到此为止的内容可缓存」。
     * 默认 TTL 5 分钟，会话内连续对话通常都能命中。
     */
    private fun ephemeralCacheControl(): JsonObject = buildJsonObject {
        put("type", "ephemeral")
    }

    private fun buildAnthropicToolDefinitions(
        tools: List<ChatAgentToolDefinition>,
    ): List<JsonObject> {
        return tools.mapIndexed { index, tool ->
            buildJsonObject {
                put("name", tool.name)
                put("description", tool.description)
                put("input_schema", tool.inputSchema)
                put("strict", true)
                // 只在最后一个工具上打断点：缓存是前缀式的，标记末尾即覆盖整个 tools 数组
                // （连同其前面的 system）。逐个标记会浪费断点额度（上限 4 个）且无额外收益。
                if (index == tools.lastIndex) {
                    put("cache_control", ephemeralCacheControl())
                }
            }
        }
    }

    private fun buildAnthropicHistoryMessages(request: ChatProviderRequest): List<JsonObject> {
        val items = mutableListOf<JsonObject>()
        request.history.forEach { message ->
            when (message.role) {
                ChatMessageRole.USER -> {
                    val content = message.content.trim()
                    if (content.isBlank()) return@forEach
                    items += buildJsonObject {
                        put("role", "user")
                        put("content", content)
                    }
                }

                ChatMessageRole.ASSISTANT -> {
                    val contentBlocks = buildJsonArray {
                        message.content.trim().takeIf { it.isNotBlank() }?.let { content ->
                            add(
                                buildJsonObject {
                                    put("type", "text")
                                    put("text", content)
                                }
                            )
                        }
                        message.toolCalls.forEach { toolCall ->
                            add(
                                buildJsonObject {
                                    put("type", "tool_use")
                                    put("id", toolCall.id ?: "call_${message.id}")
                                    put("name", toolCall.name)
                                    put(
                                        "input",
                                        runCatching { json.parseToJsonElement(toolCall.argumentsJson) }
                                            .getOrNull() ?: buildJsonObject { }
                                    )
                                }
                            )
                        }
                    }
                    if (contentBlocks.isNotEmpty()) {
                        items += buildJsonObject {
                            put("role", "assistant")
                            put("content", contentBlocks)
                        }
                    }
                }

                ChatMessageRole.TOOL -> {
                    val toolResult = message.toolResult ?: return@forEach
                    val callId = toolResult.callId ?: return@forEach
                    items += buildJsonObject {
                        put("role", "user")
                        put(
                            "content",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("type", "tool_result")
                                        put("tool_use_id", callId)
                                        put(
                                            "content",
                                            ChatToolResultInputFormatter.format(
                                                message,
                                                toolResult,
                                                request.skillSelection.availableTools,
                                            )
                                        )
                                        put("is_error", toolResult.status != ChatToolResultStatus.SUCCESS)
                                    }
                                )
                            }
                        )
                    }
                }

                ChatMessageRole.ERROR -> Unit
            }
        }
        return items
    }

    // ⚠️ 原 `normalizeAssistantReply` / `firstNonBlank` / `stripInlineToolMarkup` 三个私有函数
    // 已迁到 `ChatReplyNormalizer.kt`。下方三处调用点现在解析到**同包的顶层函数**——
    // 流式路径共用同一份定义，避免两条路径产出不一致（§8 验收要求逐字段一致）。

    private fun String.compactForLog(maxLength: Int = 180): String {
        val compact = replace(Regex("""\s+"""), " ").trim()
        return if (compact.length > maxLength) compact.take(maxLength) + "…" else compact
    }
}
