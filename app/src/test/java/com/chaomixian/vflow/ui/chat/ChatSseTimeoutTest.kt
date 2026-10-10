package com.chaomixian.vflow.ui.chat

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * [ChatSse] 的超时分类测试（`chat-stream-recovery-design.md` §6.1 用例 ①–⑤）。
 *
 * 用 [ChatStreamTimeouts] 注入 **200 ms** 阈值（`connectMs` 同样 200 ms）——这就是
 * 把夹取放在 `fromPreset()` 而非构造函数的原因：构造函数保持可注入任意 ms。
 *
 * ⚠️ 所有用例都套了 [withTimeout]：**挂了就是失败**。
 * 每个用例都应当**数秒内结束**；若某个卡到 5 s 以上，说明构造方式不对。
 */
class ChatSseTimeoutTest {

    private lateinit var server: MockWebServer

    private val fastTimeouts = ChatStreamTimeouts(idleMs = 200L, connectMs = 200L)

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

    private fun framesOf(
        request: Request,
        timeouts: ChatStreamTimeouts = fastTimeouts,
    ): List<ChatSse.SseFrame> = runBlocking {
        withTimeout(10_000) { ChatSse.frames(request, timeouts).toList() }
    }

    // ------------------------------------------------------------ ① 首字节超时

    @Test
    fun `first byte timeout when header arrives but no event`() {
        val response = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            // 响应头已到 + 首块不含事件（一串空格）⇒ 触发的是「首字节超时」而非「无响应」。
            // ⚠️ body 必须**长于一块**：throttleBody 只在「还有数据要写」时 sleep，
            // 恰好一块的话写完即关闭 ⇒ 走 onClosed 而非 onFailure（首版就踩了这个坑）。
            .setBody(" ".repeat(200))
            .throttleBody(8, 1, TimeUnit.SECONDS)
        server.enqueue(response)

        val frames = framesOf(request())
        val failure = frames.filterIsInstance<ChatSse.SseFrame.Failure>().singleOrNull()
        assertNotNull("必须产出 Failure 帧，实际: $frames", failure)
        assertEquals(SseFailureCause.FIRST_BYTE_TIMEOUT, failure!!.cause)
        assertEquals(200, failure.httpCode)
    }

    // ------------------------------------------------------------ ② 流中空闲

    @Test
    fun `idle timeout after first event`() {
        val response = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            // 首块含完整事件（15 字节）+ 后续填充 ⇒ 首块 flush 后 1 s 不再发
            .setBody("data: {\"x\":1}\n\n" + " ".repeat(200))
            .throttleBody(16, 1, TimeUnit.SECONDS)
        server.enqueue(response)

        val frames = framesOf(request())
        assertTrue("必须至少收到一帧 Data，实际: $frames", frames.any { it is ChatSse.SseFrame.Data })
        val failure = frames.filterIsInstance<ChatSse.SseFrame.Failure>().singleOrNull()
        assertNotNull("必须产出 Failure 帧，实际: $frames", failure)
        assertEquals(SseFailureCause.IDLE_TIMEOUT, failure!!.cause)
    }

    // ------------------------------------------------------------ ③ keep-alive 不算超时

    @Test
    fun `keep alive comments do not count as idle`() {
        val response = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(": keep-alive\n\n".repeat(6))
            .throttleBody(14, 50, TimeUnit.MILLISECONDS)
        server.enqueue(response)

        // 本用例阈值放宽到 500 ms（留 450 ms 余量）：keep-alive 行按字节流持续到达，
        // readTimeout 不会触发 ⇒ 判据必须是**字节级**（收到任意事件才算活跃是错的）
        val frames = framesOf(request(), fastTimeouts.copy(idleMs = 500L))
        assertTrue("keep-alive 期间不得判超时，实际: $frames", frames.none { it is ChatSse.SseFrame.Failure })
        assertTrue("流应正常结束，实际: $frames", frames.any { it is ChatSse.SseFrame.EndOfStream })
    }

    // ------------------------------------------------------------ ④ 无响应超时（且可重试）

    @Test
    fun `no response timeout is classified and retryable`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val frames = framesOf(request())
        val failure = frames.filterIsInstance<ChatSse.SseFrame.Failure>().singleOrNull()
        assertNotNull("必须产出 Failure 帧，实际: $frames", failure)
        assertEquals(SseFailureCause.NO_RESPONSE_TIMEOUT, failure!!.cause)

        // 第二条断言：同一帧流经 ChatStreamRunner ⇒ 必须是 ChatStreamTimeoutException（可重试），
        // 而不是 IllegalStateException。这是「v1.2 / D13 并入可重试分支」的机器化守卫。
        val thrown = runBlocking {
            withTimeout(10_000) {
                runCatching {
                    ChatStreamRunner.run(
                        frames = ChatSse.frames(request(), fastTimeouts),
                        assembler = ChatStreamAssembler(ChatStreamProtocol.OPENAI_CHAT),
                    ).toList()
                }.exceptionOrNull()
            }
        }
        assertTrue("必须是可重试的超时异常，实际: $thrown", thrown is ChatStreamTimeoutException)
        assertEquals(SseFailureCause.NO_RESPONSE_TIMEOUT, (thrown as ChatStreamTimeoutException).kind)
    }

    // ------------------------------------------------------------ ⑤ 非 2xx ⇒ TRANSPORT

    @Test
    fun `non 2xx is transport failure and keeps server message`() {
        val response = MockResponse()
            .setResponseCode(400)
            .setHeader("Content-Type", "application/json")
            .setBody("""{"error":{"message":"Insufficient balance","type":"insufficient_quota"}}""")
        server.enqueue(response)

        val frames = framesOf(request())
        val failure = frames.filterIsInstance<ChatSse.SseFrame.Failure>().singleOrNull()
        assertNotNull("必须产出 Failure 帧，实际: $frames", failure)
        assertEquals(SseFailureCause.TRANSPORT, failure!!.cause)
        assertEquals(400, failure.httpCode)
        assertEquals("Insufficient balance [insufficient_quota]", failure.message)
    }
}
