package com.chaomixian.vflow.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** 流式协议族。 */
internal enum class ChatStreamProtocol {
    /** OpenAI `chat/completions`：`deepseek` / `openrouter` / `ollama` / `openai`(默认)。 */
    OPENAI_CHAT,

    /**
     * OpenAI **Responses** API（`openai` + `useResponsesApi = true`）。
     *
     * ⚠️ 与 [OPENAI_CHAT] 是**两套完全不同的事件模型**（不是同一协议的变体）：
     * 这里的事件是**带点号命名的类型**（`response.output_text.delta` 等），
     * 包在 SSE 的 `data:` 里、**没有** `choices`/`delta` 那套结构。
     * 依据是 OpenAI 官方 SDK 由 OpenAPI spec 自动生成的类型定义
     * （`openai/types/responses/response_stream_event.py` 列出了完整的事件联合类型）。
     */
    OPENAI_RESPONSES,

    /** Anthropic `messages`。 */
    ANTHROPIC_MESSAGES,
}

/**
 * 流式事件**归约器**（`chat-streaming-design.md` §4.1/§4.3）。
 *
 * 输入是 SSE 的 `data:` 载荷（已由 P2 的分帧层拆好），输出是 [ChatStreamEvent]。
 * 无 Android / 网络依赖，可纯 JVM 单测。
 *
 * ## 设计原则
 *
 * 1. **只做拼接，不做工具参数 JSON 解析**（共识 C1）。参数原样累积到收尾；
 *    唯一例外是 §4.7 的中断判定（[interruptedToolCalls] 在收尾时解析一次）。
 * 2. **收尾值一律走共享纯函数**：[finish] 把累积结果交给 [normalizeAssistantReply]，
 *    **不自己实现一遍**规范化。这样「流式落盘的」与「非流式落盘的」在构造上就
 *    逐字段一致（§8 验收项），不会因两份实现漂移。
 * 3. **宽容缺失**：usage 可能缺席/迟到/分次到达；工具分片的 `id`/`name` 可能不发；
 *    空 `choices` 帧是合法的。
 *
 * ## ⚠️ 与「非流式等价」的**已知刻意取舍**
 *
 * | 场景 | 非流式 | 本类 | 说明 |
 * |---|---|---|---|
 * | `content` 是数组且含 `reasoning_text` part | 该段文字**同时**进正文与 reasoning（旧的重复计入） | 只进正文 | chat/completions 极少出现此形状（那是 Responses 的形态）；不复制既有缺陷 |
 */
internal class ChatStreamAssembler(
    private val protocol: ChatStreamProtocol,
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 正文**原始**串（含内联标签，未经规范化）。收尾时交给 [normalizeAssistantReply]。 */
    private val rawContent = StringBuilder()

    /**
     * reasoning 的两条**独立通道**。
     *
     * ⚠️ **必须分开累积**：非流式 OpenAI 路径是
     * `firstNonBlank(reasoning_content, reasoning)` ——**取一**，后者在前者非空时被丢弃。
     * 若把两者拼进同一个 StringBuilder，就变成了「合并」，**静默多出内容**。
     */
    private val reasoningPrimary = ReasoningTrack()
    private val reasoningSecondary = ReasoningTrack()

    /** 正文的流式安全规范化器（只影响过程显示，权威值在 [finish]）。 */
    private val normalizer = ChatStreamNormalizer()

    /** 按「流内 index」归约的工具调用（F6：绝不能按列表下标）。 */
    private val toolCalls = LinkedHashMap<Int, ToolBuilder>()

    /** 已 closure 的工具调用 index（§4.7 的保留判据之一）。 */
    private val closedToolCalls = mutableSetOf<Int>()

    private var usage: ChatStreamEvent.Usage = ChatStreamEvent.Usage()

    private var finished = false

    private var result: ChatCompletionResult? = null

    /**
     * 是否见过**协议规定的结束标记**（OpenAI 的 `[DONE]`、Anthropic 的 `message_stop`）。
     *
     * ⚠️ 这是 F7「截断」判据的依据：连接关闭时若**没见过**它，
     * 说明响应被中途切断，而**已收到的内容看起来是完整的**——
     * 若不判这一条，截断会被静默当成正常收尾，用户拿到半句话且没有任何提示。
     *
     * ⚠️ 两种协议各自的合法结束形态不同，故标记在解码层置位而非由调用方判断。
     */
    var sawTerminator: Boolean = false
        private set

    /**
     * 单条 reasoning 通道的累积器。
     *
     * 分隔符语义对齐非流式的 `joinToString("\n\n")`：**只在块间、且前面已有内容时**插入。
     * 用「块开始」标志 + 「已有内容」判据实现，故**空块不会把分隔符泄漏给下一块**
     * （标志在新块开始时重设，与中间隔了几个空块无关）。
     */
    private class ReasoningTrack {
        private val text = StringBuilder()
        private var atBlockStart = false

        fun markBlockStart() {
            atBlockStart = true
        }

        /** 追加一块内容，返回**应当下发**的文本（含分隔符，可能为空串）。 */
        fun append(chunk: String): String {
            if (chunk.isEmpty()) return ""
            val separator = if (atBlockStart && text.isNotEmpty()) "\n\n" else ""
            atBlockStart = false
            text.append(separator).append(chunk)
            return separator + chunk
        }

        fun content(): String = text.toString()
    }

    private class ToolBuilder {
        var id: String? = null
        var name: String? = null
        val arguments = StringBuilder()
    }

    // ------------------------------------------------------------ 对外

    /**
     * 喂入一个 SSE `data:` 载荷。
     *
     * @param type SSE 的 `event:` 字段（Anthropic 用它在 JSON 之外再标一次类型；
     *   OpenAI 不用，其类型在 JSON 的 `type` 字段里）。
     * @return 可下发的事件（**可能为空**——内容可能正被规范化器的暂存区扣住，
     *   这是正常状态，调用方不得视作流结束）。
     * @throws IllegalStateException 服务端在流中报了错（Anthropic 的 `error` 事件）。
     */
    fun accept(data: String, type: String? = null): List<ChatStreamEvent> {
        if (finished) return emptyList()
        val trimmed = data.trim()
        if (trimmed.isEmpty()) return emptyList()
        // OpenAI 系的结束哨兵。**不是错误**，由调用方在收到后调 finish()。
        if (trimmed == "[DONE]") {
            // ⚠️ 必须先置位再返回：这是 F7 判「正常收尾 vs 被截断」的唯一依据。
            // 漏了它，每一次正常结束都会被误判成截断。
            sawTerminator = true
            return emptyList()
        }

        val root = runCatching { json.parseToJsonElement(trimmed) }.getOrNull() as? JsonObject
            ?: return emptyList()

        val decoded = when (protocol) {
            ChatStreamProtocol.OPENAI_CHAT -> decodeOpenAi(root)
            ChatStreamProtocol.OPENAI_RESPONSES -> decodeResponses(root)
            ChatStreamProtocol.ANTHROPIC_MESSAGES -> decodeAnthropic(root, type)
        }
        return decoded.flatMap(::fold)
    }

    /**
     * 流结束，产出最终的**权威结果**。
     *
     * ⚠️ **必须调用**——即使中途出错/被取消，也要先拿到已累积内容，那正是 §4.7 取消语义的基础。
     * 幂等：重复调用返回同一结果，不重复累积。
     */
    fun finish(): ChatCompletionResult {
        result?.let { return it }
        finished = true
        val normalized = normalizeAssistantReply(
            content = rawContent.toString(),
            reasoningContent = firstNonBlank(
                reasoningPrimary.content(),
                reasoningSecondary.content(),
            ),
        )
        val built = ChatCompletionResult(
            content = normalized.content,
            reasoningContent = normalized.reasoningContent,
            totalTokens = computeTotalTokens(),
            toolCalls = collectToolCalls(),
            cacheCreationTokens = usage.cacheCreationTokens,
            cacheReadTokens = usage.cacheReadTokens,
            cacheDeletedTokens = usage.cacheDeletedTokens,
        )
        result = built
        return built
    }

    /**
     * **中断**（用户点停止 / 连接断开）时应保留的工具调用——`chat-streaming-design.md` §4.7。
     *
     * 判据是**两个条件的合取**：
     * 1. 该调用**已 closure**（收到过 `content_block_stop`，或 OpenAI 侧已见 `finish_reason`）；
     * 2. 累积的 arguments **能解析成 JSON 对象**。
     *
     * ⚠️ **只判条件 1 不够**：closure 只说明**块结束**，不说明串是合法 JSON——
     * provider 真的会发畸形 `partial_json`（非法转义 `\H`、裸制表符）。
     * 保留畸形串的后果是**下一轮请求静默改数据**：Anthropic 路径的
     * `runCatching{parse}.getOrNull() ?: buildJsonObject{}` 会把 arguments **悄悄换成 `{}`**，
     * 模型看到空参 `tool_use`，推理被带偏。
     *
     * 其余（未 closure / 不可解析）**整块丢弃**——中断发生在派发之前，
     * 保留它就需要伪造一个结果。
     *
     * ⚠️ 本函数**不改动内部状态**，可在 [finish] 前后任意调用。
     */
    fun interruptedToolCalls(): List<ChatToolCall> {
        return toolCalls.entries
            .filter { (index, builder) ->
                index in closedToolCalls &&
                    !builder.name.isNullOrBlank() &&
                    isParseableObject(builder.arguments.toString())
            }
            .map { (_, builder) ->
                ChatToolCall(
                    id = builder.id,
                    name = builder.name.orEmpty(),
                    argumentsJson = builder.arguments.toString(),
                )
            }
    }

    // ------------------------------------------------------------ 归约

    /** 解码后的中间形态。⚠️ 与 [ChatStreamEvent] **不是**一一对应：正文要先过规范化器。 */
    private sealed interface Decoded {
        data class Content(val text: String) : Decoded

        /** reasoning 增量。`secondary = true` 表示它来自 OpenAI 的 `reasoning` 键（非主通道）。 */
        data class FieldReasoning(val text: String, val secondary: Boolean = false) : Decoded

        /**
         * 一个**新 reasoning 块**开始（Anthropic 的 `content_block_start`）。
         *
         * ⚠️ 刻意做成**独立事件**，而不是「空文本 + blockStart 标志」：
         * 后者会被 fold() 的「空文本不得开块」（F3）提前拦掉，
         * 于是**多个 thinking 块之间的分隔符永远不会插入**，
         * 与非流式的 `joinToString("\n\n")` 静默不一致。
         */
        data object ReasoningBlockStart : Decoded

        data class Tool(
            val index: Int,
            val id: String?,
            val name: String?,
            val argumentsDelta: String,
        ) : Decoded

        data class ToolDone(val index: Int) : Decoded

        data class Use(val usage: ChatStreamEvent.Usage) : Decoded
    }

    private fun fold(item: Decoded): List<ChatStreamEvent> {
        val out = mutableListOf<ChatStreamEvent>()
        when (item) {
            is Decoded.Content -> {
                rawContent.append(item.text)
                // 过程显示：走流式安全规范化（内联 think → ReasoningDelta，即 B5）
                out += normalizer.accept(item.text)
            }

            is Decoded.FieldReasoning -> {
                // ⚠️ 空串**不得开块**（F3）：DeepSeek 的首个 chunk 就是
                // `content: null, reasoning_content: ""`；若为它产出事件，
                // UI 会凭空多出一个空的「思考过程」折叠区。
                val track = if (item.secondary) reasoningSecondary else reasoningPrimary
                val emitted = track.append(item.text)
                if (emitted.isNotEmpty()) out += ChatStreamEvent.ReasoningDelta(emitted)
            }

            is Decoded.ReasoningBlockStart -> reasoningPrimary.markBlockStart()

            is Decoded.Tool -> {
                val builder = toolCalls.getOrPut(item.index) { ToolBuilder() }
                // ⚠️ id/name 是「身份」不是「累积」（F5）：只在**当前为空**且**本次非空**时写入。
                // 续传分片把字段发成空串或 null（某些网关会填 null）意为「无更新」，**绝不是清空**。
                if (builder.id.isNullOrBlank() && !item.id.isNullOrBlank()) builder.id = item.id
                if (builder.name.isNullOrBlank() && !item.name.isNullOrBlank()) builder.name = item.name
                builder.arguments.append(item.argumentsDelta)
                out += ChatStreamEvent.ToolCallDelta(
                    index = item.index,
                    id = item.id?.takeIf { it.isNotBlank() },
                    name = item.name?.takeIf { it.isNotBlank() },
                    argumentsDelta = item.argumentsDelta,
                )
            }

            is Decoded.ToolDone -> {
                closedToolCalls += item.index
                out += ChatStreamEvent.ToolCallCompleted(item.index)
            }

            is Decoded.Use -> {
                usage = mergeUsage(usage, item.usage)
                out += usage
            }
        }
        return out
    }

    /**
     * usage **逐字段合并（非空覆盖）**——不能「最后一帧胜出」。
     *
     * ⚠️ Anthropic 的两半是**分开到达**的：输入侧（含 cache 字段）在 `message_start`、
     * `output_tokens` 在**迟到的** `message_delta`（且在 `content_block_stop` **之后**）。
     * 用「最后一帧胜出」会**丢掉输入侧的 token 与全部缓存明细**。
     */
    private fun mergeUsage(
        current: ChatStreamEvent.Usage,
        incoming: ChatStreamEvent.Usage,
    ): ChatStreamEvent.Usage = ChatStreamEvent.Usage(
        totalTokens = incoming.totalTokens ?: current.totalTokens,
        promptTokens = incoming.promptTokens ?: current.promptTokens,
        completionTokens = incoming.completionTokens ?: current.completionTokens,
        cacheCreationTokens = incoming.cacheCreationTokens ?: current.cacheCreationTokens,
        cacheReadTokens = incoming.cacheReadTokens ?: current.cacheReadTokens,
        cacheDeletedTokens = incoming.cacheDeletedTokens ?: current.cacheDeletedTokens,
    )

    /**
     * 与非流式的 `extractTotalTokens` / `extractAnthropicTotalTokens` **同一口径**：
     * 优先用协议给的 `total_tokens`，否则把两半相加（缺一半就只加现有的）。
     */
    private fun computeTotalTokens(): Int? {
        usage.totalTokens?.let { return it }
        return listOf(usage.promptTokens, usage.completionTokens)
            .filterNotNull()
            .takeIf { it.isNotEmpty() }
            ?.sum()
    }

    private fun collectToolCalls(): List<ChatToolCall> {
        return toolCalls.values.mapNotNull { builder ->
            val name = builder.name
            // 与非流式一致：名字为空的调用**直接丢弃**（两条路径同为 mapNotNull 语义）
            if (name.isNullOrBlank()) return@mapNotNull null
            ChatToolCall(
                id = builder.id,
                name = name,
                // 与非流式一致：空参数回退 `{}`——零参数工具在流式下**不会**有 `input_json_delta`
                argumentsJson = builder.arguments.toString().ifBlank { "{}" },
            )
        }
    }

    private fun isParseableObject(raw: String): Boolean {
        if (raw.isBlank()) return false
        return runCatching { json.parseToJsonElement(raw) }.getOrNull() is JsonObject
    }

    // ------------------------------------------------------------ 解码：OpenAI

    private fun decodeOpenAi(root: JsonObject): List<Decoded> {
        root["error"]?.let { error ->
            if (error !is JsonNull) throw IllegalStateException(openAiErrorMessage(error))
        }
        val out = mutableListOf<Decoded>()
        val choice = (root["choices"] as? JsonArray)?.firstOrNull()?.jsonObject

        // ⚠️ **空 choices 是合法的**（F16）：开了 `stream_options.include_usage` 后，
        // 最后一帧是 `choices: []` 的**纯 usage 帧**。
        // 无条件取 `choices[0]` 会在**全文显示完之后**抛异常，或在 usage 帧上崩溃。
        choice?.get("delta")?.jsonObject?.let { delta ->
            deltaTextOf(delta["content"])?.let { out += Decoded.Content(it) }
            // 两个键都认，但**分通道**累积——非流式是 firstNonBlank 取一，不是拼接
            deltaTextOf(delta["reasoning_content"])?.let { out += Decoded.FieldReasoning(it) }
            deltaTextOf(delta["reasoning"])?.let { out += Decoded.FieldReasoning(it, secondary = true) }

            delta["tool_calls"]?.let { it as? JsonArray }?.forEachIndexed { fallbackIndex, element ->
                val obj = element as? JsonObject ?: return@forEachIndexed
                // ⚠️ F6：**必须**优先用线上的 `index`（它计的是工具调用序号）。
                // 仅在它缺席时才退回列表下标——某些网关只对发生变化的项发分片，
                // 用 forEachIndexed 的下标会错位。
                val index = obj["index"]?.jsonPrimitive?.intOrNull ?: fallbackIndex
                val function = obj["function"] as? JsonObject
                out += Decoded.Tool(
                    index = index,
                    // ⚠️ F5：空串/null 一律转成 null，表示「本次无更新」
                    id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    name = function?.get("name")?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                    argumentsDelta = function?.get("arguments")?.jsonPrimitive?.contentOrNull.orEmpty(),
                )
            }
        }

        // `finish_reason` 非 null 即表示本轮不再有分片。
        // ⚠️ 判据**只能是 `finish_reason`**，不能顺手用「delta 为空」：
        // 空 delta 帧在流中途是合法的（心跳、usage-only 帧），
        // 用它会**提前把半截 args 判成已 closure** ⇒ 被 §4.7 保留 ⇒
        // 下一轮请求带着半截 JSON（正是 F17 要防的）。
        // ⚠️ 必须在 `delta == null` 时也检查——末帧常常只有 `finish_reason` 而没有 delta。
        val finishReason = choice?.get("finish_reason")
        if (finishReason != null && finishReason !is JsonNull) {
            // OpenAI 的流**不逐个**标记工具调用结束（不像 Anthropic 的 `content_block_stop`），
            // 故在此把全部已见调用标为 closure——否则 §4.7 的保留判据在 OpenAI 路径上
            // 永远不会保留任何调用（表现为「中断后半截工具调用全丢」）。
            toolCalls.keys.forEach { if (it !in closedToolCalls) out += Decoded.ToolDone(it) }
        }

        usageOf(root["usage"])?.let { out += Decoded.Use(it) }
        return out
    }

    private fun openAiErrorMessage(error: JsonElement): String {
        return when (error) {
            is JsonObject -> {
                val message = error["message"]?.jsonPrimitive?.contentOrNull
                    ?: error["msg"]?.jsonPrimitive?.contentOrNull
                    ?: error["detail"]?.jsonPrimitive?.contentOrNull
                val type = error["type"]?.jsonPrimitive?.contentOrNull
                when {
                    message.isNullOrBlank() -> type?.takeIf { it.isNotBlank() } ?: "服务端返回了错误。"
                    type.isNullOrBlank() -> message
                    else -> "$message [$type]"
                }
            }

            is JsonPrimitive -> error.contentOrNull?.takeIf { it.isNotBlank() } ?: "服务端返回了错误。"
            else -> "服务端返回了错误。"
        }
    }

    // ------------------------------------------------------------ 解码：Responses

    /**
     * OpenAI **Responses** 流的解码。
     *
     * ## 与 chat/completions 的结构差异（不是「字段名不同」，是**模型不同**）
     *
     * | 维度 | chat/completions | Responses |
     * |---|---|---|
     * | 事件类型 | JSON 里的 `choices[].delta` | **带点号的 `type`**（`response.output_text.delta`） |
     * | 文本增量字段 | `delta.content` | `delta`（**就是字符串本身**） |
     * | 推理 | `delta.reasoning_content` | `response.reasoning_text.delta` 的 `delta` |
     * | 工具参数 | `tool_calls[].function.arguments` 分片 | `response.function_call_arguments.delta` 的 `delta` |
     * | 工具身份 | `tool_calls[].id` / `.function.name` | `response.output_item.added` 的 `item.call_id` / `.name` |
     * | 结束标记 | `data: [DONE]` | `response.completed` |
     * | 工具「项」索引 | `tool_calls[].index` | `output_index` |
     *
     * ⚠️ 三条易错点：
     * 1. **`delta` 是裸字符串**，不是对象。`response.output_text.delta` 的载荷形如
     *    `{"type":"response.output_text.delta","delta":"Hello","output_index":0,...}`
     *    ⇒ 取 `root["delta"]` 的**原始内容**，不要去 `jsonObject`。
     * 2. **工具的 id/name 只在 `output_item.added` 出现**，参数走 `function_call_arguments.delta`。
     *    两者靠 **`output_index`** 关联（不是 `item_id`——虽然它有，但 delta 事件里也带，
     *    用 `output_index` 与 chat/completions 的 `index` 语义一致，便于复用归约逻辑）。
     * 3. **终止是 `response.completed`**，没有 `[DONE]`；且 `response.failed` /
     *    `response.incomplete` 也是**终止**，但语义是失败/未完成，必须区分。
     *
     * 依据：OpenAI 官方 SDK 自动生成的类型定义（`openai/types/responses/`）。
     */
    private fun decodeResponses(root: JsonObject): List<Decoded> {
        val eventType = root["type"]?.jsonPrimitive?.contentOrNull
        val out = mutableListOf<Decoded>()
        when (eventType) {
            // 正文增量：`delta` 是**裸字符串**
            "response.output_text.delta" -> contentTextOf(root["delta"])
                ?.let { out += Decoded.Content(it) }

            // 推理增量：字段名同为 `delta`，但与正文是**不同事件类型**（C2：绝不混）
            "response.reasoning_text.delta" -> contentTextOf(root["delta"])
                ?.let { out += Decoded.FieldReasoning(it) }

            // 推理摘要（部分模型只给摘要不给全文）——同样进 reasoning 通道
            "response.reasoning_summary_text.delta" -> contentTextOf(root["delta"])
                ?.let { out += Decoded.FieldReasoning(it) }

            // 工具调用的**身份**在这里（id/name），参数不在这里
            "response.output_item.added" -> {
                val item = root["item"]?.jsonObject ?: return out
                if (item["type"]?.jsonPrimitive?.contentOrNull == "function_call") {
                    val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: 0
                    out += Decoded.Tool(
                        index = index,
                        // ⚠️ 优先 `call_id`（那是**回传时必须用的** id），回退 `id`
                        id = item["call_id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                            ?: item["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        name = item["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        argumentsDelta = "",
                    )
                }
            }

            // 工具参数分片
            "response.function_call_arguments.delta" -> {
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: 0
                root["delta"]?.jsonPrimitive?.contentOrNull?.let {
                    out += Decoded.Tool(index, null, null, it)
                }
            }

            // 参数结束 ⇒ 该工具调用 closure（对齐 Anthropic 的 `content_block_stop` 语义）
            "response.function_call_arguments.done" -> {
                val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: 0
                if (toolCalls.containsKey(index)) out += Decoded.ToolDone(index)
            }

            // 工具项完成：此时 `arguments` 是完整串。**补一次 closure**，
            // 因为某些情况下 `function_call_arguments.done` 可能缺席
            //（⚠️ 未实测，属防御性：重复标记 closure 是幂等的）。
            "response.output_item.done" -> {
                val item = root["item"]?.jsonObject
                if (item?.get("type")?.jsonPrimitive?.contentOrNull == "function_call") {
                    val index = root["output_index"]?.jsonPrimitive?.intOrNull ?: 0
                    if (toolCalls.containsKey(index) && index !in closedToolCalls) {
                        out += Decoded.ToolDone(index)
                    }
                }
            }

            // ⚠️ **终止事件三种，语义不同**：
            // - completed：正常收尾
            // - failed / incomplete：**必须区分**——它们也是「流结束了」，但结果是坏的。
            //   若只把 completed 当终止，这两种会被 F7 误判成「截断」（错误文案不准确）；
            //   若把三者都当正常终止，失败会被静默当成成功（更糟）。
            "response.completed" -> {
                sawTerminator = true
                // usage 在 completed 事件的 `response.usage` 里（整个请求的总量）
                root["response"]?.jsonObject?.get("usage")?.let { usageOf(it) }?.let { out += Decoded.Use(it) }
            }

            "response.failed" -> {
                val message = root["response"]?.jsonObject
                    ?.get("error")?.jsonObject
                    ?.get("message")?.jsonPrimitive?.contentOrNull
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "服务端返回了失败状态。")
            }

            "response.incomplete" -> {
                // 未完成（例如达到 max_output_tokens）——已收到的内容仍应保留，
                // 但它**不是**正常收尾，故不置 `sawTerminator`
                throw IllegalStateException("回复未完成（可能达到 max_output_tokens）。")
            }

            "error" -> {
                val message = root["message"]?.jsonPrimitive?.contentOrNull
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "服务端返回了错误。")
            }

            // 其余事件（response.created / in_progress / content_part.added /
            // output_text.done 等）与归约无关，忽略。
            else -> Unit
        }
        return out
    }

    /**
     * 取「裸字符串」形态的增量值。
     *
     * ⚠️ Responses 的 `delta` 是**字符串本身**，而 chat/completions 的 `delta` 是**对象**。
     * 这里刻意只接受字符串形态，不接受数组/对象——若某天真出现对象形态，
     * 静默返回 null 会让内容凭空消失；而**返回 null 是安全的**
     * （后续 `response.completed` 的收尾会以官方 `output` 为准吗？——不会，见下方说明）
     *
     * ⚠️ **重要限制**：本项目流式的权威值来自**累积的 delta**（`finish()` 走
     * `normalizeAssistantReply(rawContent, …)`），**不**从 `response.completed` 的
     * `output` 数组重建。这与非流式 `parseResponsesCompletion` 的取值路径**不同**。
     * 若某个模型发了文本却只走 `output_text.done` 而不发 delta，本实现会丢内容。
     * 属**已知限制**，与 §5.3 的 U8 对应（待真机确认）。
     */
    private fun contentTextOf(element: JsonElement?): String? {
        return when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }
            else -> null
        }
    }

    // ------------------------------------------------------------ 解码：Anthropic

    private fun decodeAnthropic(root: JsonObject, type: String?): List<Decoded> {
        val eventType = root["type"]?.jsonPrimitive?.contentOrNull ?: type
        val out = mutableListOf<Decoded>()
        when (eventType) {
            // 输入侧 usage（含两个 cache 字段）在这里。
            // ⚠️ `output_tokens` 此时是 0，真值在**迟到的** `message_delta`（§3.3-1）。
            "message_start" -> {
                root["message"]?.jsonObject?.get("usage")?.let { usageOf(it) }?.let { out += Decoded.Use(it) }
            }

            // ⚠️ `content_block_start` 可能**已经带内容**（§3.3-3）。
            // CCB 与 Pi 面对同一现象结论相反（CCB 清空、Pi 保留），权威结论待实测（U3）。
            // 此处采取 CCB 的做法（**不**把 `input` 当参数增量），理由：
            // Anthropic 的流式契约是「input 走 `input_json_delta`」，
            // 而 CCB 注释指出 SDK 有时会把同一份内容**再发一遍**。
            // 重复与丢失都会让累积串成为非法 JSON ⇒ 该调用被 §4.7 判据丢弃且日志可见，
            // 属**可观测**的失败。若实测（U3）证明必须保留，改此处一行即可。
            "content_block_start" -> {
                val block = root["content_block"]?.jsonObject ?: return out
                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                when (block["type"]?.jsonPrimitive?.contentOrNull) {
                    "tool_use" -> out += Decoded.Tool(
                        index = index,
                        id = block["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        name = block["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
                        argumentsDelta = "",
                    )

                    "thinking" -> {
                        out += Decoded.ReasoningBlockStart
                        block["thinking"]?.jsonPrimitive?.contentOrNull
                            ?.takeIf { it.isNotEmpty() }
                            ?.let { out += Decoded.FieldReasoning(it) }
                    }

                    "text" -> block["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out += Decoded.Content(it) }
                }
            }

            "content_block_delta" -> {
                val delta = root["delta"]?.jsonObject ?: return out
                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                when (delta["type"]?.jsonPrimitive?.contentOrNull) {
                    "text_delta" -> delta["text"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out += Decoded.Content(it) }

                    "thinking_delta" -> delta["thinking"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf { it.isNotEmpty() }
                        ?.let { out += Decoded.FieldReasoning(it) }

                    // ⚠️ 字段名是 `partial_json`（不是 `input_json`），且**可能是畸形 JSON**。
                    // 本层**不解析**（共识 C1），原样累积；解析只在 §4.7 判定时做一次。
                    "input_json_delta" -> delta["partial_json"]?.jsonPrimitive?.contentOrNull
                        ?.let { out += Decoded.Tool(index, null, null, it) }
                }
            }

            // 该块 closure。**冻结原始串**，是否可解析留到 §4.7 判定。
            // ⚠️ 只有工具块才标记——文本/思考块的 stop 与本判据无关。
            "content_block_stop" -> {
                val index = root["index"]?.jsonPrimitive?.intOrNull ?: 0
                if (toolCalls.containsKey(index)) out += Decoded.ToolDone(index)
            }

            // ⚠️ **usage 迟到**：`output_tokens` 在这里，且在 `content_block_stop` 之后
            "message_delta" -> root["usage"]?.let { usageOf(it) }?.let { out += Decoded.Use(it) }

            // 正常收尾由调用方调 finish()。
            // ⚠️ `message_stop` 是 Anthropic 的**结束标记**，置位供 F7 判「正常 vs 截断」。
            "message_stop" -> sawTerminator = true

            "ping" -> Unit

            "error" -> {
                val message = root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "服务端返回了错误。")
            }
        }
        return out
    }

    // ------------------------------------------------------------ 共用小工具

    /**
     * 从 delta 的文本字段取值，容忍三种形状（与非流式的 `extractTextContent` 对齐）：
     * 字符串 / `null` / `[{type:"text", text:"…"}]` 数组。
     *
     * ⚠️ 返回 `null`（而非空串）表示「本帧没有该字段」；**空串转成 null**——
     * 这是 F3 的第一道防线（DeepSeek 首帧的 `reasoning_content: ""`）。
     */
    private fun deltaTextOf(element: JsonElement?): String? {
        return when (element) {
            null, JsonNull -> null
            is JsonPrimitive -> element.contentOrNull?.takeIf { it.isNotEmpty() }
            is JsonArray -> element.joinToString(separator = "") { part ->
                val obj = part as? JsonObject ?: return@joinToString ""
                obj["text"]?.jsonPrimitive?.contentOrNull
                    ?: obj["content"]?.jsonPrimitive?.contentOrNull
                    ?: ""
            }.takeIf { it.isNotEmpty() }

            else -> (
                element.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?: element.jsonObject["content"]?.jsonPrimitive?.contentOrNull
                )?.takeIf { it.isNotEmpty() }
        }
    }

    /**
     * usage 归一。字段映射见 [ChatStreamEvent.Usage] 的表格。
     *
     * ⚠️ **缺字段必须保持 null，不能写成 0**——两者含义不同：
     * null 是「服务端没报」，0 是「报了且为零」。
     * 缓存命中判据（`cacheRead > 0`）只看后者；把 null 打成 0 会把「没报告」误读成「未命中」。
     */
    private fun usageOf(element: JsonElement?): ChatStreamEvent.Usage? {
        val obj = element as? JsonObject ?: return null
        val mapped = when (protocol) {
            ChatStreamProtocol.OPENAI_CHAT -> ChatStreamEvent.Usage(
                totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull,
                promptTokens = obj["prompt_tokens"]?.jsonPrimitive?.intOrNull,
                completionTokens = obj["completion_tokens"]?.jsonPrimitive?.intOrNull,
            )

            ChatStreamProtocol.OPENAI_RESPONSES -> ChatStreamEvent.Usage(
                // ⚠️ Responses 的 usage 有 `total_tokens`（与 chat/completions 一致），
                // 但也有 `input_tokens` / `output_tokens`。本项目非流式路径
                // （`extractResponsesTotalTokens`）是「total 优先，否则四项相加」，
                // 这里是「total 优先，否则两半相加」——**口径略有收窄**（不含
                // `prompt_tokens`/`completion_tokens` 那两个 OpenAI 兼容别名）。
                // Responses 是 OpenAI 自家协议、不会用别名，故不构成实际差异。
                totalTokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull,
                promptTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull,
                completionTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull,
                // ⚠️ 缓存明细在**嵌套**的 `input_tokens_details` 里（不是顶层字段），
                // 这是与 Anthropic 的又一处结构差异。
                // 映射到 [ChatStreamEvent.Usage.cacheReadTokens] 以复用既有的命中判据
                // （`cacheRead > 0`）；`cache_write_tokens` 对应 cacheCreation。
                cacheReadTokens = obj["input_tokens_details"]?.jsonObject
                    ?.get("cached_tokens")?.jsonPrimitive?.intOrNull,
                cacheCreationTokens = obj["input_tokens_details"]?.jsonObject
                    ?.get("cache_write_tokens")?.jsonPrimitive?.intOrNull,
            )

            ChatStreamProtocol.ANTHROPIC_MESSAGES -> ChatStreamEvent.Usage(
                promptTokens = obj["input_tokens"]?.jsonPrimitive?.intOrNull,
                completionTokens = obj["output_tokens"]?.jsonPrimitive?.intOrNull,
                cacheCreationTokens = obj["cache_creation_input_tokens"]?.jsonPrimitive?.intOrNull,
                cacheReadTokens = obj["cache_read_input_tokens"]?.jsonPrimitive?.intOrNull,
                cacheDeletedTokens = obj["cache_deleted_input_tokens"]?.jsonPrimitive?.intOrNull,
            )
        }
        // 全空（服务端给了 `usage: {}`）→ 不产出事件，避免无意义的状态写入
        return mapped.takeIf {
            with(it) {
                listOf(
                    totalTokens, promptTokens, completionTokens,
                    cacheCreationTokens, cacheReadTokens, cacheDeletedTokens,
                ).any { value -> value != null }
            }
        }
    }
}
