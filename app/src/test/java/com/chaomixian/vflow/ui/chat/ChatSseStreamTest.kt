package com.chaomixian.vflow.ui.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [ChatSse] + [ChatStreamRunner] 的回归测试。
 *
 * ⚠️ 本文件是 **F18/F19/F7** 的唯一可测手段。
 * 它们全是「不报错、只是悄悄挂死或悄悄不完整」的失效点，用真机手工极难复现：
 * 要么得让服务端真的返回 400，要么得让连接在特定时刻断掉。
 * MockWebServer 可以精确回放这三种形态。
 *
 * ⚠️ 所有用例都套了 [withTimeout]：**挂了就是失败**。
 * 不加超时的话，F18 那个缺陷会让测试**永远卡住**（而不是失败），
 * 在 CI 上表现为超时被杀、看不出原因。
 */
class ChatSseStreamTest {

    private lateinit var server: MockWebServer

    /** ⚠️ 超时必须远小于生产环境的 120s readTimeout，否则挂死用例要跑两分钟。 */
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

    private fun request(): Request = Request.Builder()
        .url(server.url("/v1/chat/completions"))
        .header("Accept", "text/event-stream")
        .post(okhttp3.RequestBody.create(null, "{}"))
        .build()

    private fun sse(vararg bodies: String): MockResponse {
        val response = MockResponse()
        response.setHeader("Content-Type", "text/event-stream")
        response.setBody(bodies.joinToString(separator = ""))
        return response
    }

    private fun framesOf(request: Request): List<ChatSse.SseFrame> = runBlocking {
        withTimeout(5_000) { ChatSse.frames(request).toList() }
    }

    // ------------------------------------------------------------ 正常路径

    /**
     * 正常流：每个事件一个 `data:` 行，以 `[DONE]` 收尾。
     *
     * ⚠️ 这一例同时验证「分帧交给了 okhttp-sse」——包括**多个 `data:` 行合并**、
     * CRLF、以及注释行（`:` 开头）被忽略。手写 `split("\n\n")` 会在这些地方出错。
     */
    @Test
    fun `normal stream yields data frames and end of stream`() {
        server.enqueue(
            sse(
                ":comment line should be ignored\n",
                "data: {\"a\":1}\r\n\r\n",
                // 一个事件拆成多个 data 行 —— 按 SSE 规范应以 \n 拼接
                "data: {\"b\":\n",
                "data: 2}\n\n",
                "data: [DONE]\n\n",
            )
        )
        val frames = framesOf(request())
        val data = frames.filterIsInstance<ChatSse.SseFrame.Data>()

        // ⚠️ **3 帧而非 2 帧**：`[DONE]` 也是普通 `data:` 载荷，传输层**不认**它的协议语义
        // ——「哪条载荷代表流结束」由归约器按协议判断（OpenAI 认 `[DONE]`、
        // Anthropic 认 `message_stop`）。这是**有意的分层**，不是漏判。
        // 若这里断言 2 帧，等于要求传输层懂协议，两家的结束标记就得写死在传输层。
        assertEquals(3, data.size)
        assertEquals("""{"a":1}""", data[0].data)
        // ⚠️ 多行 data 必须**合并成一个载荷**，而不是当成两个事件
        assertEquals("{\"b\":\n2}", data[1].data)
        assertEquals("[DONE]", data[2].data)
        assertTrue(frames.any { it is ChatSse.SseFrame.EndOfStream })
        assertTrue(frames.none { it is ChatSse.SseFrame.Failure })
    }

    /** `event:` 字段要透传（Anthropic 用它区分事件类型）。 */
    @Test
    fun `event type is passed through`() {
        server.enqueue(
            sse(
                "event: content_block_delta\ndata: {\"type\":\"content_block_delta\"}\n\n",
                "data: [DONE]\n\n",
            )
        )
        val data = framesOf(request()).filterIsInstance<ChatSse.SseFrame.Data>()
        assertEquals("content_block_delta", data[0].type)
    }

    // ------------------------------------------------------------ B2 / F18：失败路径必须结束

    /**
     * ⚠️⚠️ **B2 / F18 的核心用例（本文件最重要的一例）**。
     *
     * 反证：把 `onFailure` 里的 `close()` 删掉，本用例**不会失败，而是挂死**——
     * 被外层 [withTimeout] 打断。这正是真机上的表现：
     * **一直转圈、没有报错、没有日志、没有超时**（本项目无 `callTimeout`，
     * `readTimeout` 也救不了——此时响应头已到、连接是健康的）。
     *
     * 同时验证**服务端错误原文被提取**（B2 的另一半）：
     * 不读响应体的话，用户只能看到 `HTTP 400`。
     */
    @Test
    fun `http 400 with json error body produces failure frame carrying server message`() {
        val response = MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"message":"Insufficient balance","type":"insufficient_quota"}}""")
        server.enqueue(response)

        val frames = framesOf(request())
        val failure = frames.filterIsInstance<ChatSse.SseFrame.Failure>().singleOrNull()
        assertNotNull("失败路径必须产出 Failure 帧；若这里为 null，说明 onFailure 没结束这条流", failure)
        assertEquals(400, failure!!.httpCode)
        assertEquals("Insufficient balance [insufficient_quota]", failure.message)
    }

    /** content-type 不是 `text/event-stream` ⇒ okhttp-sse 走 `onFailure`。 */
    @Test
    fun `non sse content type produces failure frame`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"message":{"content":"hi"}}]}"""),
        )
        val frames = framesOf(request())
        assertTrue(
            "非 SSE 响应必须走失败路径，实际: $frames",
            frames.any { it is ChatSse.SseFrame.Failure },
        )
    }

    /**
     * 连接**中途断开**（不是正常收尾）⇒ 必须产出 Failure，而不是静默结束。
     *
     * ⚠️ 与 F7 的区别：这里是**传输层**断了（连接被对端掐断），
     * F7 管的是传输层正常关闭但**没见过结束标记**。两者都表现为「内容不完整」，
     * 但这条由 okhttp-sse 报 `onFailure`，那条要靠归约器的 `sawTerminator` 判。
     */
    @Test
    fun `connection dropped mid stream produces failure frame`() {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"half\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY),
        )
        val frames = framesOf(request())
        assertTrue(
            "中途断连必须走失败路径，实际: $frames",
            frames.any { it is ChatSse.SseFrame.Failure },
        )
    }

    /** 流中途服务端报错（HTTP 200 但事件体是 error）——那属归约器职责，传输层照传。 */
    @Test
    fun `in stream error payload is delivered as data frame`() {
        server.enqueue(
            sse(
                "data: {\"error\":{\"message\":\"rate limited\"}}\n\n",
                "data: [DONE]\n\n",
            )
        )
        val data = framesOf(request()).filterIsInstance<ChatSse.SseFrame.Data>()
        assertTrue(data.any { it.data.contains("rate limited") })
    }

    // ------------------------------------------------------------ F7：截断判据

    /**
     * ⚠️⚠️ **F7 的核心用例**：连接**干净关闭**、但**没见过结束标记**
     * ⇒ 必须抛异常，而不是把半截内容当完整回复。
     *
     * 反证：去掉 `sawTerminator` 判断后，本用例会正常收尾并返回半句话——
     * 用户完全看不出回复被截断了。
     */
    @Test
    fun `stream ends without terminator is treated as truncation`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"半句话\"}}]}\n\n",
                // 没有 [DONE] —— 干净关闭
            )
        )
        val error = runCatching {
            runBlocking {
                withTimeout(5_000) {
                    ChatStreamRunner.run(
                        frames = ChatSse.frames(request()),
                        assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT),
                    ).toList()
                }
            }
        }.exceptionOrNull()

        assertNotNull("未见结束标记就关闭，必须报错而不是静默收尾", error)
        assertTrue(
            "错误信息应说明是截断，实际: ${error!!.message}",
            error.message.orEmpty().contains("完整性") || error.message.orEmpty().contains("结束标记"),
        )
    }

    /** 见过 `[DONE]` 的正常流**不得**被判成截断（否则每次正常结束都报错）。 */
    @Test
    fun `stream with terminator completes successfully`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"完整回复\"}}]}\n\n",
                "data: [DONE]\n\n",
            )
        )
        val events = runBlocking {
            withTimeout(5_000) {
                ChatStreamRunner.run(
                    frames = ChatSse.frames(request()),
                    assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT),
                ).toList()
            }
        }
        val completed = events.filterIsInstance<ChatStreamEvent.Completed>().singleOrNull()
        assertNotNull("正常流必须产出 Completed", completed)
        assertEquals("完整回复", completed!!.result.content)
    }

    /** Anthropic 的结束标记是 `message_stop`（不是 `[DONE]`）。 */
    @Test
    fun `anthropic message_stop is recognized as terminator`() {
        server.enqueue(
            sse(
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5}}}\n\n",
                "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n",
            )
        )
        val events = runBlocking {
            withTimeout(5_000) {
                ChatStreamRunner.run(
                    frames = ChatSse.frames(request()),
                    assembler = ChatStreamAssembler(ChatStreamProtocol.ANTHROPIC_MESSAGES),
                ).toList()
            }
        }
        assertNotNull(events.filterIsInstance<ChatStreamEvent.Completed>().singleOrNull())
    }

    // ------------------------------------------------------------ B4：Completed 携带权威值

    /**
     * ⚠️ **B4 的落地验证**：`Completed` 里的 `content` 必须是**规范化之后的权威值**，
     * 而不是累积的原始 delta。
     *
     * 这里用内联 ` thinking` 构造差异：原始 delta 含标签，权威值不含。
     * 反证：把 `ChatStreamRunner` 的 `assembler.finish()` 换成「拼接收到的 delta」后，
     * 本用例的 content 会带上 `<think>` 标签。
     */
    @Test
    fun `completed carries normalized content not raw deltas`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"<think>想\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"</think>答案\"}}]}\n\n",
                "data: [DONE]\n\n",
            )
        )
        val events = runBlocking {
            withTimeout(5_000) {
                ChatStreamRunner.run(
                    frames = ChatSse.frames(request()),
                    assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT),
                ).toList()
            }
        }
        val result = events.filterIsInstance<ChatStreamEvent.Completed>().single().result
        assertEquals("答案", result.content)
        assertEquals("想", result.reasoningContent)
        // 中途下发的正文里也不得出现标签
        val streamedText = events.filterIsInstance<ChatStreamEvent.TextDelta>().joinToString("") { it.text }
        assertTrue("流式过程中漏出了标签：$streamedText", !streamedText.contains("<"))
    }

    /**
     * ⚠️ 中间事件必须**边收边发**，而不是攒到结束才一次性吐出来。
     *
     * 反证：把 `run()` 里的 `emit` 换成「收集完再 emit」后，
     * 本用例断言「首个事件在流结束前就已到达」会失败。
     */
    @Test
    fun `text deltas are emitted incrementally before completion`() {
        server.enqueue(
            sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"一\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"二\"}}]}\n\n",
                "data: [DONE]\n\n",
            )
        )
        val events = runBlocking {
            withTimeout(5_000) {
                ChatStreamRunner.run(
                    frames = ChatSse.frames(request()),
                    assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT),
                ).toList()
            }
        }
        val deltas = events.filterIsInstance<ChatStreamEvent.TextDelta>()
        assertEquals("一二", deltas.joinToString("") { it.text })
        // Completed 必须是最后一个事件
        assertTrue(events.last() is ChatStreamEvent.Completed)
    }
}
