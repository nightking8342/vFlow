package com.chaomixian.vflow.ui.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **基于官方报文 fixture 的端到端测试**（`chat-streaming-design.md` §5.3 的落地）。
 *
 * ## 与其它测试的分工
 *
 * | 测试 | 覆盖 |
 * |---|---|
 * | [ChatStreamAssemblerTest] / [ChatStreamNormalizerTest] | 纯函数层，**手写单帧**，逐个边界 |
 * | [ChatSseStreamTest] | **失败路径**（400 / 非 SSE / 断连 / 截断） |
 * | 本文件 | **完整链路的正常路径**：真实报文 → okhttp-sse 分帧 → 归约 → 收尾 |
 *
 * 区别在**输入的真实性**：前面几个用手写的极简单帧，本文件用 `src/test/resources/chat-sse/`
 * 下的完整报文（含 `event:` 行、空行分隔、多块、usage 帧、`signature_delta` 等
 * 「手写用例不会想到要覆盖」的东西）。
 *
 * ## ⚠️ fixture 的来源与可信度**分级**
 *
 * 这一点必须说清楚，否则后人会把所有 fixture 当成同等权威：
 *
 * | fixture | 来源 | 可信度 |
 * |---|---|---|
 * | `anthropic-*.sse` | **官方文档的原始 SSE 报文**（`platform.claude.com/docs/en/build-with-claude/streaming`，本机技能包 `curl/examples.md` 与 `typescript/claude-api/streaming.md` 有完整转载），事件序列与字段名按官方示例；thinking / tool_use 块按同页与 `shared/tool-use-concepts.md` 的字段说明补全 | **协议规范级**——事件名、字段名、顺序可据此定案 |
 * | `openai-chat-*.sse` | **无权威源**（本机技能包只覆盖 Anthropic）。形状按 OpenAI 的公开协议常识 + 本项目 `parseChatCompletion` 已支持的字段构造 | **形状级**——只保证「归约器对已知形状的处理正确」，**不保证这就是 OpenAI 的真实报文** |
 * | `deepseek-reasoning-first-chunk.sse` | 取自 dsh 对 **DeepSeek 官方**的实测（`references/dsh/packages/llm/llm-deepseek/tests/translate.spec.ts`）；⚠️ **经 OpenRouter 等网关是否一致未验证** | **单 provider 实测级** |
 *
 * ⇒ 结论：Anthropic 那三条 fixture 可以当规范用；OpenAI / DeepSeek 的**必须**在真机上再确认一遍
 * （即设计文档 §5.3 的 U4/U5，仍未关闭）。
 */
class ChatSseFixtureTest {

    private lateinit var server: MockWebServer

    private val client = OkHttpClient.Builder()
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

    // ------------------------------------------------------------ 运行装置

    /** 从 `src/test/resources/chat-sse/` 读 fixture。⚠️ 找不到就抛——**不能静默跳过**。 */
    private fun fixture(name: String): String {
        val stream = javaClass.getResourceAsStream("/chat-sse/$name")
            ?: error("fixture 缺失：/chat-sse/$name（应位于 app/src/test/resources/chat-sse/）")
        return stream.bufferedReader(Charsets.UTF_8).use { it.readText() }
    }

    /**
     * 把 fixture 当作真实响应体喂进**完整链路**，返回产出的事件序列。
     *
     * ⚠️ 刻意走真实 HTTP（MockWebServer）而不是直接调归约器：
     * 这样 `event:` 行、空行分隔、多行 `data:`、CRLF 等**分帧层的行为**也一并被测到。
     * 直接调归约器会绕过 okhttp-sse，那些东西就没人测了。
     */
    private fun runFixture(name: String, protocol: ChatStreamProtocol): List<ChatStreamEvent> {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(fixture(name)),
        )
        val request = Request.Builder()
            .url(server.url("/v1/messages"))
            .header("Accept", "text/event-stream")
            .post("{}".toRequestBody())
            .build()
        return runBlocking {
            withTimeout(5_000) {
                ChatStreamRunner.run(
                    frames = ChatSse.frames(request),
                    assembler = ChatStreamAssembler(protocol),
                ).toList()
            }
        }
    }

    private fun List<ChatStreamEvent>.completed(): ChatCompletionResult =
        filterIsInstance<ChatStreamEvent.Completed>().single().result

    private fun List<ChatStreamEvent>.textDeltas(): String =
        filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.text }

    private fun List<ChatStreamEvent>.reasoningDeltas(): String =
        filterIsInstance<ChatStreamEvent.ReasoningDelta>().joinToString("") { it.text }

    // ------------------------------------------------------------ Anthropic（规范级）

    /**
     * 官方报文：`message_start` → `content_block_start` → `ping` → 2× `content_block_delta`
     * → `content_block_stop` → `message_delta` → `message_stop`。
     *
     * ⚠️ 两个要点：
     * 1. **`content_block_start` 的 `text` 是空串**（官方原文如此）⇒ 答掉了 U3：
     *    起始块**没有**初始内容需要保留。归约器不把它计入正文是正确的。
     * 2. **`ping` 必须被忽略**，且**不能**被当成流结束或错误。
     */
    @Test
    fun `official anthropic text stream`() {
        val events = runFixture("anthropic-text.sse", ChatStreamProtocol.ANTHROPIC_MESSAGES)
        val result = events.completed()

        assertEquals("Hello world", result.content)
        assertNull(result.reasoningContent)
        assertTrue("不应产生工具调用", result.toolCalls.isEmpty())
        assertEquals("增量下发必须与终态一致", "Hello world", events.textDeltas())
    }

    /**
     * ⚠️ **U2 的验证**：`message_start` 的 usage 既有输入侧、也有两个缓存字段；
     * `output_tokens` 在**迟到的** `message_delta`。
     *
     * 官方文档写明缓存字段是**响应 usage 的字段**，事件表说明 `message_start` 携带 message
     * metadata、`message_delta` 携带 usage。本用例把「两半合并」钉死：
     * `input_tokens=12` + `output_tokens=12` ⇒ `totalTokens=24`。
     *
     * 反证：把 usage 合并改成「最后一帧胜出」，`totalTokens` 会变成 12（丢掉输入侧）。
     */
    @Test
    fun `anthropic usage merges input side and late output tokens`() {
        val result = runFixture("anthropic-text.sse", ChatStreamProtocol.ANTHROPIC_MESSAGES).completed()
        assertEquals(24, result.totalTokens)
    }

    /**
     * 工具调用：**文本块 + 工具块混合**（index 0 是 text、index 1 是 tool_use）。
     *
     * ⚠️ 这个 fixture 刻意让 `content_block_start` 带上 `"input":{}`——
     * 正是 CCB 注释描述的那种形态。官方报文显示**起始块为空**，
     * 且 `shared/tool-use-concepts.md` 明确参数是按
     * `content_block_start → input_json_delta × N → content_block_stop` 发出的，
     * ⇒ 归约器**只认 `input_json_delta`** 是正确做法（U3 据此关闭）。
     *
     * ⚠️ 同时验证 **F6**：`index` 是内容块序号（1），**不是** tool_calls 列表下标（0）。
     * 若按列表下标归约，这个调用会被错记成 index 0。
     */
    @Test
    fun `official anthropic tool use stream`() {
        val events = runFixture("anthropic-tool-use.sse", ChatStreamProtocol.ANTHROPIC_MESSAGES)
        val result = events.completed()

        assertEquals("Let me check the weather.", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("toolu_abc123", result.toolCalls[0].id)
        assertEquals("get_weather", result.toolCalls[0].name)
        // 两个分片拼接而成：{"loc  +  ation": "Paris"}
        assertEquals("""{"location": "Paris"}""", result.toolCalls[0].argumentsJson)
        assertEquals(477, result.totalTokens)  // 420 + 57
    }

    /**
     * thinking 块：**两个** thinking 块 + 一个 text 块，中间夹一个 `signature_delta`。
     *
     * ⚠️ 三件事同时被验：
     * 1. **reasoning 与正文分离**（C2）——思考绝不混进 `content`；
     * 2. **多块之间用 `\n\n` 连接**，对齐非流式的 `joinToString("\n\n")`；
     * 3. **`signature_delta` 被忽略**且不报错——它是扩展思考的签名串，
     *    本项目不重放 thinking 块，故无需保存。
     */
    @Test
    fun `anthropic thinking blocks stay out of content and join with blank line`() {
        val events = runFixture("anthropic-thinking.sse", ChatStreamProtocol.ANTHROPIC_MESSAGES)
        val result = events.completed()

        assertEquals("Answer.", result.content)
        assertEquals("Let me work this out.\n\nSecond block.", result.reasoningContent)
        assertEquals("思考不得混进正文", "Answer.", events.textDeltas())
        // 缓存明细（message_start 的输入侧）
        assertEquals(4096, result.cacheReadTokens)
        assertEquals(1024, result.cacheCreationTokens)
        assertEquals(119, result.totalTokens)  // 88 + 31
    }

    // ------------------------------------------------------------ OpenAI 兼容（形状级）

    /** 官方文档未覆盖 OpenAI，形状见类注释的可信度分级。 */
    @Test
    fun `openai chat text stream with usage frame`() {
        val events = runFixture("openai-chat-text.sse", ChatStreamProtocol.OPENAI_CHAT)
        val result = events.completed()

        assertEquals("Hello world", result.content)
        // 首帧 content 是空串 ⇒ 不得产出可见增量（否则用户会先看到一个空 delta）
        assertEquals("Hello world", events.textDeltas())
        // usage-only 帧（choices: []）在**全文显示完之后**到达，不得抛错
        assertEquals(11, result.totalTokens)
        assertTrue("OpenAI 无缓存明细", result.cacheReadTokens == null)
    }

    /**
     * 两个工具调用，**分片交错到达**（index 0 的参数分两片，之后才出现 index 1）。
     *
     * ⚠️ 验证 F5 + F6 的真实形态：
     * - index 1 的首片**同时**带 id/name/arguments（`{}`），后续无续传分片；
     * - index 0 的续传分片**不带** id/name ⇒ 不得把已记下的身份清空。
     */
    @Test
    fun `openai chat tool calls stream`() {
        val events = runFixture("openai-chat-tool-calls.sse", ChatStreamProtocol.OPENAI_CHAT)
        val result = events.completed()

        assertEquals("Let me check.", result.content)
        assertEquals(2, result.toolCalls.size)
        assertEquals("get_weather", result.toolCalls[0].name)
        assertEquals("""{"location": "Paris"}""", result.toolCalls[0].argumentsJson)
        assertEquals("get_time", result.toolCalls[1].name)
        assertEquals("{}", result.toolCalls[1].argumentsJson)
        assertEquals(120, result.totalTokens)
    }

    /**
     * ⚠️ **OpenAI 路径的 closure 语义**（§4.7 的保留判据依赖它）。
     *
     * OpenAI 的流**不逐个**标记工具调用结束（不像 Anthropic 的 `content_block_stop`），
     * 只能在 `finish_reason` 出现时把全部已见调用标记为 closure。
     *
     * 反证：去掉那段标记逻辑后，本用例会拿到 **0** 条——
     * 表现为「OpenAI/DeepSeek 上中断一次，半截工具调用全丢；Anthropic 上却保留」，
     * 两端行为不一致，且只有真机中止操作才看得出来。
     */
    @Test
    fun `openai tool calls are marked closed at finish reason`() {
        val assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT)
        fixture("openai-chat-tool-calls.sse")
            .lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }
            .forEach { assembler.accept(it) }

        // 两个调用的 args 都合法且已 closure ⇒ 中断时应**全部保留**
        val kept = assembler.interruptedToolCalls()
        assertEquals(2, kept.size)
        assertEquals("get_weather", kept[0].name)
        assertEquals("get_time", kept[1].name)
    }

    /**
     * ⚠️ **F3 的真实形态**（来自 dsh 对 DeepSeek 官方的实测）：
     * 首个 chunk 是 `content: null, reasoning_content: ""`。
     *
     * 若为它产出事件，UI 会凭空多出一个空的「思考过程」折叠区。
     * ⚠️ 这里同时验证「首个空帧不产出任何事件」——用 fixture 驱动的版本比手写单帧更可信。
     */
    @Test
    fun `deepseek empty first reasoning chunk produces no events`() {
        // ⚠️ 逐帧送入以便断言「第一帧无产出」——整条链路跑完后无从区分是哪一帧产生的
        val assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT)
        val firstFrame = fixture("deepseek-reasoning-first-chunk.sse")
            .lineSequence()
            .first { it.startsWith("data: ") }
            .removePrefix("data: ")
        val firstEvents = assembler.accept(firstFrame)
        assertTrue("首帧 content=null / reasoning_content=\"\" 不得产出任何事件，实际: $firstEvents", firstEvents.isEmpty())

        val events = runFixture("deepseek-reasoning-first-chunk.sse", ChatStreamProtocol.OPENAI_CHAT)
        val result = events.completed()
        assertEquals("The answer is 42.", result.content)
        assertEquals("Let me think.", result.reasoningContent)
        assertEquals(45, result.totalTokens)
    }

    // ------------------------------------------------------------ Responses（形状级）

    /**
     * Responses 的正文流。
     *
     * ⚠️ **事件名与字段名取自有权威源**（官方 SDK 从 OpenAPI spec 自动生成的类型定义，
     * `openai/types/responses/response_stream_event.py` 列出了完整的事件联合类型），
     * 但**具体的事件序列**没有官方示例报文可抄 ⇒ 这条 fixture 是**形状级**：
     * 只保证「归约器对这套字段的处理正确」，不声称它就是真实报文。
     *
     * ⚠️ 两个与 chat/completions 的**结构差异**在本用例被钉死：
     * 1. `delta` 是**裸字符串**（`"delta":"Hello"`），不是 `{"content":"..."}` 对象；
     * 2. 终止事件是 **`response.completed`**，没有 `data: [DONE]`。
     */
    @Test
    fun `responses text stream with bare string delta`() {
        val events = runFixture("responses-text.sse", ChatStreamProtocol.OPENAI_RESPONSES)
        val result = events.completed()

        assertEquals("Hello world", result.content)
        assertEquals("Hello world", events.textDeltas())
        assertNull(result.reasoningContent)
    }

    /**
     * ⚠️ **Responses 的 usage 在 `response.completed` 事件的 `response.usage` 里**
     * （不是独立的 usage 帧），且缓存明细是**嵌套**的
     * （`input_tokens_details.cached_tokens`，不是顶层字段）。
     *
     * 反证：把 usage 的读取路径改成顶层 `root["usage"]`，本用例的 `totalTokens` 会变 null。
     */
    @Test
    fun `responses usage lives inside completed event and caches are nested`() {
        val result = runFixture("responses-text.sse", ChatStreamProtocol.OPENAI_RESPONSES).completed()
        assertEquals(14, result.totalTokens)
        // 嵌套的 input_tokens_details.cached_tokens 映射到 cacheReadTokens
        assertEquals(0, result.cacheReadTokens)
    }

    /**
     * Responses 的工具调用：身份在 `response.output_item.added`、
     * 参数在 `response.function_call_arguments.delta`，两者靠 **`output_index`** 关联。
     *
     * ⚠️ 这里 `output_index=1`（0 是文本消息），故同时验证「工具索引不是列表下标」。
     * ⚠️ `id` 取的是 **`call_id`**（回传时必须用的那个），不是 `item.id`。
     */
    @Test
    fun `responses tool call uses output index and call id`() {
        val events = runFixture("responses-tool-call.sse", ChatStreamProtocol.OPENAI_RESPONSES)
        val result = events.completed()

        assertEquals("Let me check.", result.content)
        assertEquals(1, result.toolCalls.size)
        assertEquals("call_xyz", result.toolCalls[0].id)
        assertEquals("get_weather", result.toolCalls[0].name)
        assertEquals("""{"location": "Paris"}""", result.toolCalls[0].argumentsJson)
        assertEquals(150, result.totalTokens)
        assertEquals(64, result.cacheReadTokens)
    }

    /**
     * ⚠️ **`response.failed` 必须抛错，不能静默当成正常收尾**。
     *
     * 反证：把 `response.failed` 分支删掉（落入 `else -> Unit`），
     * 本用例会变成「流正常结束但没有结束标记」⇒ 被 F7 报成「截断」——
     * 错误信息会指向截断，而真实原因是服务端失败。文案误导，且用户拿不到服务端的原因。
     */
    @Test
    fun `responses failed event raises with server message`() {
        val assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_RESPONSES)
        val error = runCatching {
            assembler.accept(
                """{"type":"response.failed","sequence_number":0,"response":{"id":"r","error":{"code":"server_error","message":"upstream exploded"}}}"""
            )
        }.exceptionOrNull()
        assertEquals("upstream exploded", error?.message)
    }

    /** `error` 事件同样必须抛错（它是另一种失败形态）。 */
    @Test
    fun `responses error event raises with message`() {
        val assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_RESPONSES)
        val error = runCatching {
            assembler.accept("""{"type":"error","sequence_number":0,"code":"bad","message":"boom"}""")
        }.exceptionOrNull()
        assertEquals("boom", error?.message)
    }

    /**
     * ⚠️ `response.incomplete` **不得**被当成正常收尾。
     *
     * 它表示「流结束了但回复未完成」（例如撞上 `max_output_tokens`）。
     * 若把 `sawTerminator` 也置位，用户会拿到半截回复且**毫无提示**——
     * 这类「看起来完整、其实被截断」正是 F7 要防的形态。
     */
    @Test
    fun `responses incomplete event is not a normal terminator`() {
        val assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_RESPONSES)
        runCatching { assembler.accept("""{"type":"response.incomplete","sequence_number":0,"response":{}}""") }
        assertTrue("incomplete 不得置位 sawTerminator", !assembler.sawTerminator)
    }

    // ------------------------------------------------------------ 跨协议一致性

    /**
     * ⚠️ **两条协议的「终态 == 增量拼接」都成立**。
     *
     * 这是流式 UX 的根本一致性要求：屏幕上逐字显示的内容，最终必须与落盘内容相同
     * （否则用户会看到文字在收尾时跳变）。注意口径是「增量拼接」而非「增量拼接的规范化」——
     * 收尾会做 `trim`/去标签，那些差异由 §4.9 的末尾跳变显式接受。
     */
    @Test
    fun `streamed deltas agree with final content for every fixture`() {
        val cases = listOf(
            "anthropic-text.sse" to ChatStreamProtocol.ANTHROPIC_MESSAGES,
            "anthropic-tool-use.sse" to ChatStreamProtocol.ANTHROPIC_MESSAGES,
            "anthropic-thinking.sse" to ChatStreamProtocol.ANTHROPIC_MESSAGES,
            "openai-chat-text.sse" to ChatStreamProtocol.OPENAI_CHAT,
            "openai-chat-tool-calls.sse" to ChatStreamProtocol.OPENAI_CHAT,
            "deepseek-reasoning-first-chunk.sse" to ChatStreamProtocol.OPENAI_CHAT,
            "responses-text.sse" to ChatStreamProtocol.OPENAI_RESPONSES,
            "responses-tool-call.sse" to ChatStreamProtocol.OPENAI_RESPONSES,
        )
        cases.forEach { (name, protocol) ->
            val events = runFixture(name, protocol)
            val result = events.completed()
            assertEquals(
                "fixture=$name：增量拼接必须等于终态正文（否则收尾时会跳变）",
                result.content,
                events.textDeltas(),
            )
            assertEquals(
                "fixture=$name：reasoning 增量拼接必须等于终态 reasoning",
                result.reasoningContent.orEmpty(),
                events.reasoningDeltas(),
            )
        }
    }

    /** 每条 fixture 都必须以 `Completed` 收尾（否则说明截断判据误杀了正常流）。 */
    @Test
    fun `every fixture reaches completion`() {
        val cases = listOf(
            "anthropic-text.sse" to ChatStreamProtocol.ANTHROPIC_MESSAGES,
            "openai-chat-text.sse" to ChatStreamProtocol.OPENAI_CHAT,
        )
        cases.forEach { (name, protocol) ->
            val events = runFixture(name, protocol)
            assertTrue("fixture=$name 未以 Completed 收尾", events.last() is ChatStreamEvent.Completed)
        }
    }

    /**
     * ⚠️ **Anthropic 路径的 closure 语义**，与上面的 OpenAI 用例配对。
     *
     * Anthropic 有逐个块的 `content_block_stop`，故 closure 是**逐块**确定的：
     * 这里把 fixture 截到**工具块 stop 之前**（即模拟用户在中途点停止），
     * 断言该调用**被丢弃**——中断发生在派发之前，保留它就要伪造一个结果。
     *
     * 两条路径的 closure 判据不同（逐块 vs `finish_reason` 统一标记），
     * 故**必须各有一条用例**；否则其中一条静默失效时，另一条仍会绿。
     */
    @Test
    fun `anthropic tool call before its block stop is discarded on interruption`() {
        val assembler = ChatStreamAssembler(ChatStreamProtocol.ANTHROPIC_MESSAGES)
        // 截到 content_block_stop 之前：参数已完整收到，但**块未 closure**
        fixture("anthropic-tool-use.sse")
            .lineSequence()
            .filter { it.startsWith("data: ") }
            .map { it.removePrefix("data: ") }
            .takeWhile { !it.contains("content_block_stop\",\"index\":1") }
            .forEach { assembler.accept(it) }

        assertTrue(
            "未 closure 的工具调用必须被丢弃（中断发生在派发之前）",
            assembler.interruptedToolCalls().isEmpty(),
        )
        // 但已收到的正文必须保留（§4.7：C4 共识）
        assertEquals("Let me check the weather.", assembler.finish().content)
    }
}
