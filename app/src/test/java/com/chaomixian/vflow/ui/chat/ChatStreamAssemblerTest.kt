package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatStreamAssembler] 的回归测试。
 *
 * ⚠️ 本文件的重点不是「正常流能跑通」，而是**改错了不报错、只静默变差**的那些点——
 * 与本仓库 logcat / WorkflowPatch 的测试取向一致。关键用例做过**反证**
 * （把代码改回 bug 版本确认变红），注释里注明了反证内容。
 */
class ChatStreamAssemblerTest {

    private fun openAi() = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT)
    private fun anthropic() = ChatStreamAssembler(ChatStreamProtocol.ANTHROPIC_MESSAGES)

    /** 按文档 §5.3 的做法：fixture 是**逐帧 JSON 字符串**，不是拼好的整块。 */
    private fun ChatStreamAssembler.feed(vararg frames: String) {
        frames.forEach { accept(it) }
    }

    // ------------------------------------------------------------ 基本信息

    @Test
    fun `openai text deltas are concatenated into content`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"content":"你"}}]}""",
            """{"choices":[{"delta":{"content":"好"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        assertEquals("你好", assembler.finish().content)
    }

    /**
     * ⚠️ **F16 反证**：改回「无条件取 choices[0]」后本用例会抛异常。
     *
     * 开了 `stream_options.include_usage` 之后，最后一帧是 `choices: []` 的**纯 usage 帧**。
     * 它是**合法**的，且出现在**全文都已显示完之后**——所以失败形态是
     * 「内容显示完整了，然后突然报错」，很容易被误当成网络问题。
     */
    @Test
    fun `openai usage-only frame with empty choices is handled`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"content":"hi"}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}""",
        )
        // 纯 usage 帧：choices 为空数组
        assembler.accept("""{"choices":[],"usage":{"total_tokens":42}}""")
        val result = assembler.finish()
        assertEquals("hi", result.content)
        assertEquals(42, result.totalTokens)
    }

    /**
     * ⚠️ **F3 反证**：改回「不过滤空串」后，本用例会产出一个 ReasoningDelta 事件
     * （真机上表现为 UI 凭空多出一个空的「思考过程」折叠区）。
     *
     * DeepSeek 流的**第一个 chunk 是 `content: null, reasoning_content: ""`**。
     */
    @Test
    fun `empty reasoning content does not open a block`() {
        val assembler = openAi()
        val events = assembler.accept("""{"choices":[{"delta":{"content":null,"reasoning_content":""}}]}""")
        assertTrue("空 reasoning 不得产出任何事件，实际: $events", events.isEmpty())
        assertNull(assembler.finish().reasoningContent)
    }

    // ------------------------------------------------------------ 工具调用

    /**
     * ⚠️ **F5 反证**：把「只在为空时写入」改成「每次覆盖」，本用例的 name 会变成 null/空。
     *
     * id/name 是**身份不是累积**。某些 OpenAI 兼容网关会在续传分片里
     * 把 `function.name` 填成 null 或空串，含义是「无更新」，**绝不是「清空」**。
     */
    @Test
    fun `tool identity is not cleared by continuation frames`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_weather","arguments":""}}]}}]}""",
            // 续传分片：id 缺席、name 显式为 null
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":null,"function":{"name":null,"arguments":"{\"city\""}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":":\"上海\"}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val calls = assembler.finish().toolCalls
        assertEquals(1, calls.size)
        assertEquals("call_1", calls[0].id)
        assertEquals("get_weather", calls[0].name)
        assertEquals("""{"city":"上海"}""", calls[0].argumentsJson)
    }

    /**
     * ⚠️ **F6 反证**：把归约 key 从「线上 index」改成「列表下标」，本用例两条调用的
     * 参数会**互换**（模型先发 index=1 再发 index=0 时，列表下标与 index 相反）。
     */
    @Test
    fun `tool calls are keyed by wire index not list position`() {
        val assembler = openAi()
        assembler.feed(
            // 注意顺序：先 index=1，再 index=0
            """{"choices":[{"delta":{"tool_calls":[{"index":1,"id":"b","function":{"name":"second","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"a","function":{"name":"first","arguments":"{}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        val calls = assembler.finish().toolCalls
        assertEquals(listOf("second", "first"), calls.map { it.name })
    }

    /**
     * 零参数工具在流式下**不会**有 `input_json_delta`（Anthropic）——
     * 必须与非流式一样回退成 `{}`，否则下游 `parse` 会拿到空串。
     */
    @Test
    fun `zero argument tool call falls back to empty object`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"get_environment"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"message_stop"}""",
        )
        val calls = assembler.finish().toolCalls
        assertEquals(1, calls.size)
        assertEquals("{}", calls[0].argumentsJson)
    }

    /**
     * 名字为空的调用**整条丢弃**（与非流式的 `mapNotNull` 同语义）。
     * ⚠️ 保留它会让下游拿到 `name = ""` 的工具调用，而审批卡片会显示一个无名的工具。
     */
    @Test
    fun `tool call without name is dropped`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1"}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        assertTrue(assembler.finish().toolCalls.isEmpty())
    }

    // ------------------------------------------------------------ §4.7 中断保留判据

    /**
     * ⚠️ **F17 反证（本文件最重要的一例）**：把 `isParseableObject` 判据去掉、
     * 只判「已 closure」后，本用例会返回 1 条调用（畸形 args 被保留）。
     *
     * 保留畸形串的后果不是报错，而是**下一轮请求静默把 arguments 换成 `{}`**
     * （Anthropic 路径的 `runCatching{parse}.getOrNull() ?: buildJsonObject{}`），
     * 模型看到空参 `tool_use`，推理被带偏——完全不报错。
     *
     * 畸形内容取自 Pi 的测试用例：非法转义 `\H`。
     */
    @Test
    fun `interrupted tool call with malformed json is discarded`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"do_thing"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"path\":\"a\\Hb\"}"}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        assertTrue(
            "畸形 partial_json 必须被丢弃；若这里拿到 1 条，说明判据少了 parse 校验",
            assembler.interruptedToolCalls().isEmpty(),
        )
    }

    /** 已 closure **且** args 合法 ⇒ 保留（这是判据的另一半，缺了会退化成「永不保留」）。 */
    @Test
    fun `interrupted tool call that is closed and parseable is kept`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"do_thing"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":1}"}}""",
            """{"type":"content_block_stop","index":0}""",
        )
        val kept = assembler.interruptedToolCalls()
        assertEquals(1, kept.size)
        assertEquals("""{"a":1}""", kept[0].argumentsJson)
    }

    /**
     * ⚠️ **未 closure 的调用必须丢弃**（中断发生在派发之前，保留它就要伪造一个结果）。
     *
     * 反证：若把「已 closure」判据去掉，本用例会拿到 1 条**半截 args** 的调用
     * ⇒ 下一轮请求带着 `{"a":` 这种非法 JSON。
     */
    @Test
    fun `interrupted tool call that never closed is discarded`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"tool_use","id":"t1","name":"do_thing"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"input_json_delta","partial_json":"{\"a\":"}}""",
            // 没有 content_block_stop —— 模拟用户在此刻点了停止
        )
        assertTrue(assembler.interruptedToolCalls().isEmpty())
        // 但 finish() 仍应产出可用的正文（§4.7：已收到的文本必须保留）
        assertNotNull(assembler.finish())
    }

    /**
     * OpenAI 路径：`finish_reason` 一到即视为全部工具调用 closure。
     *
     * 反证：去掉该标记逻辑后，本用例会返回 0 条——表现为
     * 「OpenAI/DeepSeek 上中断一次，半截工具调用全丢；Anthropic 上却保留」，两端行为不一致。
     */
    @Test
    fun `openai marks tool calls closed at finish reason`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"{\"a\":1}"}}]}}]}""",
            """{"choices":[{"delta":{},"finish_reason":"tool_calls"}]}""",
        )
        assertEquals(1, assembler.interruptedToolCalls().size)
    }

    /**
     * ⚠️ 空 delta 帧**不得**被当成流结束。
     *
     * 反证：把判据写成「`delta.isEmpty() || finish_reason != null`」后本用例失败——
     * 半截 args 会被判为已 closure，进而被 §4.7 保留 ⇒ 下一轮请求带着半截 JSON。
     * 而空 delta 帧在流中途是**合法**的（心跳、部分网关的 usage-only 帧）。
     */
    @Test
    fun `empty delta frame does not close tool calls`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f","arguments":"{\"a\":"}}]}}]}""",
            // 空 delta 心跳帧（没有 finish_reason）
            """{"choices":[{"delta":{}}]}""",
        )
        assertTrue(
            "心跳帧不得把半截 args 判成已 closure",
            assembler.interruptedToolCalls().isEmpty(),
        )
    }

    // ------------------------------------------------------------ usage

    /**
     * ⚠️ **Anthropic 的 usage 分两半到达**：输入侧在 `message_start`、
     * `output_tokens` 在**迟到的** `message_delta`（且在 `content_block_stop` **之后**）。
     *
     * 反证：把合并改成「最后一帧胜出」后，本用例的 `totalTokens` 会丢掉输入侧的 100。
     */
    @Test
    fun `anthropic usage merges across message_start and message_delta`() {
        val assembler = anthropic()
        val usageEvents = mutableListOf<ChatStreamEvent.Usage>()
        fun feed(frame: String) {
            assembler.accept(frame).filterIsInstance<ChatStreamEvent.Usage>().forEach(usageEvents::add)
        }

        feed("""{"type":"message_start","message":{"usage":{"input_tokens":100,"cache_read_input_tokens":80}}}""")
        feed("""{"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""")
        feed("""{"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"hi"}}""")
        feed("""{"type":"content_block_stop","index":0}""")
        // 迟到的 output_tokens —— 在 content_block_stop 之后
        feed("""{"type":"message_delta","usage":{"output_tokens":7}}""")
        feed("""{"type":"message_stop"}""")

        val result = assembler.finish()
        assertEquals(107, result.totalTokens)
        assertEquals(80, result.cacheReadTokens)
        // ⚠️ 每个 Usage 事件都必须是**合并后的累积值**，而不是「本帧新增的部分」——
        // 否则订阅方（VM 的 token 计数显示）会在中途先看到 100 再跳回 7。
        // 反证：把 fold 里的 `usage = mergeUsage(...)` 改成 `usage = item.usage` 后本断言失败。
        assertEquals(100, usageEvents.last().promptTokens)
        assertEquals(7, usageEvents.last().completionTokens)
    }

    /**
     * ⚠️ **缺字段与零值不能混淆**：服务端没报 cache 字段时应为 null，不是 0。
     * 判据（`cacheRead > 0`）只看后者；把 null 打成 0 会把「没报告」误读成「未命中」。
     */
    @Test
    fun `absent cache fields stay null not zero`() {
        val assembler = anthropic()
        assembler.feed("""{"type":"message_start","message":{"usage":{"input_tokens":10}}}""")
        val result = assembler.finish()
        assertNull(result.cacheReadTokens)
        assertNull(result.cacheCreationTokens)
        assertNull(result.cacheDeletedTokens)
    }

    @Test
    fun `openai total tokens falls back to prompt plus completion`() {
        val assembler = openAi()
        assembler.feed("""{"choices":[{"delta":{"content":"x"}}],"usage":{"prompt_tokens":5,"completion_tokens":3}}""")
        assertEquals(8, assembler.finish().totalTokens)
    }

    /** `usage: {}`（服务端给了空对象）不应产出事件，避免无意义的状态写入。 */
    @Test
    fun `empty usage object produces no event`() {
        val assembler = openAi()
        val events = assembler.accept("""{"choices":[{"delta":{}}],"usage":{}}""")
        assertTrue(events.none { it is ChatStreamEvent.Usage })
    }

    // ------------------------------------------------------------ 正文与 reasoning 的分离

    /**
     * ⚠️ **reasoning 绝不混进正文**（共识 C2）。
     * 反证：把 FieldReasoning 也 append 进 rawContent 后，正文会多出思考内容。
     */
    @Test
    fun `reasoning field does not leak into content`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"先想"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}""",
            """{"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"答案"}}""",
        )
        val result = assembler.finish()
        assertEquals("答案", result.content)
        assertEquals("先想", result.reasoningContent)
    }

    /**
     * 多个 thinking 块用 `\n\n` 连接（对齐非流式的 `joinToString("\n\n")`）。
     *
     * 反证：把 `ReasoningBlockStart` 去掉、改成「空文本 + 标志」后本用例失败——
     * 空文本会被 F3 的过滤提前拦掉，分隔符**永远插不进去**。
     */
    @Test
    fun `multiple thinking blocks are joined with blank line`() {
        val assembler = anthropic()
        assembler.feed(
            """{"type":"content_block_start","index":0,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"A"}}""",
            """{"type":"content_block_stop","index":0}""",
            """{"type":"content_block_start","index":2,"content_block":{"type":"thinking"}}""",
            """{"type":"content_block_delta","index":2,"delta":{"type":"thinking_delta","thinking":"B"}}""",
            """{"type":"content_block_stop","index":2}""",
        )
        assertEquals("A\n\nB", assembler.finish().reasoningContent)
    }

    /**
     * ⚠️ OpenAI 的 `reasoning_content` 与 `reasoning` 是**取一**（firstNonBlank），不是拼接。
     *
     * 反证：把两条通道合进同一个 StringBuilder 后，本用例得到 `"primarysecondary"`——
     * **静默多出内容**，且落盘后与下一轮请求的上下文都不一致。
     */
    @Test
    fun `openai reasoning keys are one or the other not concatenated`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"reasoning_content":"primary"}}]}""",
            """{"choices":[{"delta":{"reasoning":"secondary"}}]}""",
        )
        assertEquals("primary", assembler.finish().reasoningContent)
    }

    /** 主通道缺席时，次通道的内容要能被用上（否则 `reasoning` 键形同虚设）。 */
    @Test
    fun `openai secondary reasoning key used when primary is absent`() {
        val assembler = openAi()
        assembler.feed("""{"choices":[{"delta":{"reasoning":"only"}}]}""")
        assertEquals("only", assembler.finish().reasoningContent)
    }

    // ------------------------------------------------------------ 错误与幂等

    @Test
    fun `anthropic error event throws with server message`() {
        val assembler = anthropic()
        val error = runCatching {
            assembler.accept("""{"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}""")
        }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error is IllegalStateException)
        assertEquals("Overloaded", error!!.message)
    }

    @Test
    fun `openai error payload throws with server message`() {
        val assembler = openAi()
        val error = runCatching {
            assembler.accept("""{"error":{"message":"Insufficient balance","type":"insufficient_quota"}}""")
        }.exceptionOrNull()
        assertEquals("Insufficient balance [insufficient_quota]", error?.message)
    }

    // ------------------------------------------------------------ 与非流式的等价性

    /**
     * ⚠️ **§8 验收项的直接落点**：流式 `finish()` 的正文，
     * 必须等于非流式路径对**同一份原始内容**调 [normalizeAssistantReply] 的结果。
     *
     * 这不是「顺手多测一次」——它是「两条路径产出不同数据」这类**静默**错误的唯一防线，
     * 而那正是本设计把收尾值交给共享纯函数的理由。
     *
     * 反证：让 `finish()` 自己实现一遍规范化（而不是调 [normalizeAssistantReply]），
     * 哪怕只差一个 `trim()`，本用例也会红。
     */
    @Test
    fun `finish matches non streaming normalization for inline thinking`() {
        val assembler = openAi()
        // 模型把思考写进正文（qwen3 的形态），同时另有独立 reasoning_content 字段
        assembler.feed(
            """{"choices":[{"delta":{"content":"<think>"}}]}""",
            """{"choices":[{"delta":{"content":"想一下"}}]}""",
            """{"choices":[{"delta":{"content":"</think>答案"}}]}""",
        )
        val streamed = assembler.finish()

        val expected = normalizeAssistantReply(
            content = "<think>想一下</think>答案",
            reasoningContent = null,
        )
        assertEquals(expected.content, streamed.content)
        assertEquals(expected.reasoningContent, streamed.reasoningContent)
        // 具体值也钉死，避免「两边都错成一样」时测试仍然绿
        assertEquals("答案", streamed.content)
        assertEquals("想一下", streamed.reasoningContent)
    }

    /**
     * ⚠️ 独立 reasoning 字段**优先于**内联 think（非流式的 `firstNonBlank` 语义）。
     *
     * 反证：把两条通道合并（而非取一）后，本用例的 reasoningContent 会变成
     * `"字段思考\n\n内联思考"`——静默多出内容，且与下一轮请求的上下文不一致。
     */
    @Test
    fun `field reasoning wins over inline thinking`() {
        val assembler = openAi()
        assembler.feed(
            """{"choices":[{"delta":{"reasoning_content":"字段思考"}}]}""",
            """{"choices":[{"delta":{"content":"<think>内联思考</think>正文"}}]}""",
        )
        val streamed = assembler.finish()
        // 与非流式一致：内联那段落选（H1 的既有语义），但仍被从正文里移除
        assertEquals("字段思考", streamed.reasoningContent)
        assertEquals("正文", streamed.content)
    }

    /**
     * ⚠️ 逐字符分片（最坏情况）下，归约结果仍须与非流式一致——
     * 这正是「标签跨 delta 断裂」在归约层的表现。
     */
    @Test
    fun `finish matches non streaming normalization when split per character`() {
        val raw = """前<tool_call>{"a":1}</tool_call>中<think>想</think>后"""
        val assembler = openAi()
        raw.forEach { ch ->
            assembler.accept("""{"choices":[{"delta":{"content":${ch.toJsonStringLiteral()}}}]}""")
        }
        val streamed = assembler.finish()
        val expected = normalizeAssistantReply(raw, null)
        assertEquals(expected.content, streamed.content)
        assertEquals(expected.reasoningContent, streamed.reasoningContent)
    }

    /** 把单个字符转成 JSON 字符串字面量（含引号），供上面的逐字符分片用例构造帧。 */
    private fun Char.toJsonStringLiteral(): String {
        val escaped = when (this) {
            '"' -> "\\\""
            '\\' -> "\\\\"
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> toString()
        }
        return "\"$escaped\""
    }

    /** 畸形/非 JSON 的 data 行必须被忽略而不是抛错——SSE 里可能夹带注释或空行。 */
    @Test
    fun `malformed data line is ignored`() {
        val assembler = openAi()
        assertTrue(assembler.accept("not json at all").isEmpty())
        assertTrue(assembler.accept("").isEmpty())
        assertTrue(assembler.accept("[DONE]").isEmpty())
        assertEquals("", assembler.finish().content)
    }

    /** `finish()` 幂等：重复调用不得重复累积（否则内容会翻倍）。 */
    @Test
    fun `finish is idempotent`() {
        val assembler = openAi()
        assembler.feed("""{"choices":[{"delta":{"content":"ab"}}]}""")
        assertEquals("ab", assembler.finish().content)
        assertEquals("ab", assembler.finish().content)
    }

    /** `finish()` 之后的 `accept()` 必须无效（流已结束，迟到帧不得再改结果）。 */
    @Test
    fun `accept after finish is ignored`() {
        val assembler = openAi()
        assembler.feed("""{"choices":[{"delta":{"content":"ab"}}]}""")
        assembler.finish()
        assertTrue(assembler.accept("""{"choices":[{"delta":{"content":"LATE"}}]}""").isEmpty())
        assertEquals("ab", assembler.finish().content)
    }
}
