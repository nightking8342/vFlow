package com.chaomixian.vflow.ui.chat

/**
 * 流式事件模型（纯 Kotlin，无 Android / 网络依赖，可纯 JVM 单测）。
 *
 * 设计依据见 `docs/fork/chat-streaming-design.md` §4.1。
 * 归约器 [ChatStreamAssembler] 只做拼接与归约，**不做 JSON 解析**（三家头部 Agent 的共识 C1）。
 */

sealed interface ChatStreamEvent {

    /** 正文增量。 */
    data class TextDelta(val text: String) : ChatStreamEvent

    /** 思考过程增量。⚠️ 与正文是**独立块类型**，绝不混进 [TextDelta]（共识 C2）。 */
    data class ReasoningDelta(val text: String) : ChatStreamEvent

    /**
     * 工具调用的增量分片。
     *
     * ⚠️ `index` 是**本轮流内的块序号**，**不是** `toolCalls` 列表下标——
     * 两者在「模型先发 text 再发 tool_call」时不一致（Anthropic 的 content block 序号
     * 会把 text 块也算进去），故**必须按 `index` 归约后再落到列表**。
     * 归约器按「首次出现顺序」排定最终列表顺序，故 index 不连续也安全。
     *
     * ⚠️ **`id` / `name` 是「身份」不是「累积」**（dsh `acceptIdentity` 语义）：
     * 它们在调用的首个分片里**只发一次**，后续分片若把该字段发成空串或 null
     * （某些 OpenAI 兼容网关会填 null），含义是「本次无更新」，**绝不是「清空」**。
     * 故 `null` 表示「无更新」，归约器只在收到非空值时写入，且**只写一次**。
     */
    data class ToolCallDelta(
        val index: Int,
        val id: String?,
        val name: String?,
        val argumentsDelta: String,
    ) : ChatStreamEvent

    /**
     * 某个工具调用分片**结束**（Anthropic 的 `content_block_stop`；OpenAI 在 `finish_reason` 处统一标记）。
     *
     * **为什么必须单独一个事件**（`chat-streaming-design.md` §4.7）：
     * 取消/中断时要判断「已 closure **且** 累积串可 parse」才保留该工具调用。
     * 「累积串是否已完整」这个信息**无法从 [ToolCallDelta] 推断**——
     * 分片之间与分片之后，字符串看起来是一样的。
     * 只靠「收到过 delta」判据会把**半截 args 当成完整的**，而其后果是
     * 下一轮请求**静默把 arguments 换成 `{}`**（Anthropic 路径的 `getOrNull() ?: buildJsonObject{}`）。
     */
    data class ToolCallCompleted(val index: Int) : ChatStreamEvent

    /**
     * 用量。⚠️ **流式中可能缺失、迟到、或分多次到达**（共识 C5），必须容忍 null。
     *
     * ⚠️ **字段语义（协议无关的归一）**，由解码器负责映射：
     * | 本模型 | OpenAI chat/completions | Anthropic messages |
     * |---|---|---|
     * | [totalTokens] | `usage.total_tokens` | **无**（由下两项相加） |
     * | [promptTokens] | `usage.prompt_tokens` | `usage.input_tokens` |
     * | [completionTokens] | `usage.completion_tokens` | `usage.output_tokens` |
     *
     * ⚠️ **Anthropic 的两半是分开到达的**：输入侧（含两个 cache 字段）在 `message_start`，
     * `output_tokens` 在**迟到的** `message_delta`——而后者在 `content_block_stop` **之后**才来。
     * 故归约器对 usage 必须**逐字段合并（非空覆盖）**，不能「最后一帧胜出」。
     */
    data class Usage(
        val totalTokens: Int? = null,
        val promptTokens: Int? = null,
        val completionTokens: Int? = null,
        val cacheCreationTokens: Int? = null,
        val cacheReadTokens: Int? = null,
        val cacheDeletedTokens: Int? = null,
    ) : ChatStreamEvent

    /**
     * 流**正常**结束，携带归约后的最终结果。
     *
     * ⚠️ 两个易错点：
     * 1. 它只让**类型**可复用，**不让「收尾逻辑」可复用**——
     *    现有的 `replacePendingMessage` 是整条替换（换 id），与「原位增长」结构性冲突（§4.4）。
     * 2. 只有当归约器能证明**两个协议各自的结果字段取值规则**被完整复现时，
     *    [ChatCompletionResult] 才能作为**权威值**。`content` 的权威性见 §4.4 的 B4。
     */
    data class Completed(val result: ChatCompletionResult) : ChatStreamEvent
}
