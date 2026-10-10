package com.chaomixian.vflow.ui.chat

import java.util.concurrent.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [streamWithRecovery] 的恢复层单测（`chat-stream-recovery-design.md` §6.1 用例 ①–⑧）。
 *
 * ⚠️ 全部用**假流**（无网络、无 Android）。退避一律用 `backoffBaseMs = 0, backoffCapMs = 0`
 * 让用例零等待（除用例⑤ 专门验退避区间）。
 *
 * ⚠️ 所有用例都套了 [withTimeout]：**挂了就是失败**。
 */
class ChatStreamRecoveryTest {

    /** 零等待策略：重试次数可配，退避恒 0。 */
    private fun fastPolicy(maxRetries: Int) = ChatStreamRecoveryPolicy(
        maxRetries = maxRetries,
        backoffBaseMs = 0L,
        backoffCapMs = 0L,
    )

    private fun timeout(kind: SseFailureCause = SseFailureCause.FIRST_BYTE_TIMEOUT) =
        ChatStreamTimeoutException(kind = kind, elapsedHintMs = null, message = "timeout")

    /** 工厂：第 [failTimes] 次调用抛超时，之后发 `TextDelta("hi")` + `Completed`。 */
    private class CountingFactory(
        private val failTimes: Int,
        private val kind: SseFailureCause = SseFailureCause.FIRST_BYTE_TIMEOUT,
        private val beforeThrow: suspend (kotlinx.coroutines.flow.FlowCollector<ChatStreamEvent>) -> Unit = {},
    ) {
        var calls = 0
            private set

        fun open(): Flow<ChatStreamEvent> {
            // ⚠️ `calls` 计的是**工厂被调用的次数**（不是 flow block 被 collect 的次数）——
            // 恢复层的契约是「每次重试都重新调 openStream」，这一条必须能被机器化锚定。
            calls++
            return flow {
                if (calls <= failTimes) {
                    beforeThrow(this)
                    throw ChatStreamTimeoutException(kind, null, "timeout")
                }
                emit(ChatStreamEvent.TextDelta("hi"))
                emit(
                    ChatStreamEvent.Completed(
                        ChatCompletionResult(content = "hi", reasoningContent = null, totalTokens = null),
                    ),
                )
            }
        }
    }

    // ------------------------------------------------------------ ① 重试且事件不重复

    @Test
    fun `zero content timeout retries and does not duplicate events`() {
        val factory = CountingFactory(failTimes = 1)
        val collected = runBlocking {
            withTimeout(5_000) {
                streamWithRecovery(fastPolicy(2), onRetry = {}) { factory.open() }.toList()
            }
        }

        assertEquals("工厂必须被调用两次（第一次失败 + 一次重试）", 2, factory.calls)
        assertEquals(2, collected.size)
        assertTrue(collected[0] is ChatStreamEvent.TextDelta)
        assertTrue(collected[1] is ChatStreamEvent.Completed)
    }

    // ------------------------------------------------------------ ② 已提交 ⇒ 不重试

    @Test
    fun `timeout after content is committed is not retried`() {
        val factory = CountingFactory(failTimes = 1) { collector ->
            collector.emit(ChatStreamEvent.TextDelta("半"))
        }

        val thrown = runBlocking {
            withTimeout(5_000) {
                runCatching {
                    streamWithRecovery(fastPolicy(2), onRetry = {}) { factory.open() }.toList()
                }.exceptionOrNull()
            }
        }

        assertTrue("必须抛出 ChatStreamTimeoutException，实际: $thrown", thrown is ChatStreamTimeoutException)
        assertEquals("已提交过内容 ⇒ 只调用一次", 1, factory.calls)
    }

    // ------------------------------------------------------------ ③ 预算耗尽 ⇒ 上抛

    @Test
    fun `budget exhausted rethrows after maxRetries plus one attempts`() {
        val factory = CountingFactory(failTimes = Int.MAX_VALUE)

        val thrown = runBlocking {
            withTimeout(5_000) {
                runCatching {
                    streamWithRecovery(fastPolicy(2), onRetry = {}) { factory.open() }.toList()
                }.exceptionOrNull()
            }
        }

        assertTrue(thrown is ChatStreamTimeoutException)
        assertEquals("maxRetries=2 ⇒ 共尝试 3 次", 3, factory.calls)
    }

    // ------------------------------------------------------------ ④ notice 递增

    @Test
    fun `onRetry receives increasing attempts and correct kind`() {
        val factory = CountingFactory(failTimes = Int.MAX_VALUE)
        val notices = mutableListOf<ChatStreamRetryNotice>()

        runBlocking {
            withTimeout(5_000) {
                runCatching {
                    streamWithRecovery(fastPolicy(2), onRetry = { notices += it }) { factory.open() }.toList()
                }
            }
        }

        assertEquals(2, notices.size)
        assertEquals(1, notices[0].attempt)
        assertEquals(2, notices[1].attempt)
        assertEquals(3, notices[0].maxAttempts)
        assertEquals(SseFailureCause.FIRST_BYTE_TIMEOUT, notices[0].kind)
        assertTrue(notices[0].delayMs >= 0)
        assertTrue(notices[1].delayMs >= 0)
    }

    // ------------------------------------------------------------ ⑤ 退避区间

    @Test
    fun `backoff stays within jitter bounds`() {
        val policy = ChatStreamRecoveryPolicy()   // 默认 1 s base / 10 s cap / ±10%
        val expectations = listOf(
            1 to 1_000L,
            2 to 2_000L,
            3 to 4_000L,
            4 to 8_000L,
            5 to 10_000L,   // 16000 封顶到 10000
        )
        for ((attempt, base) in expectations) {
            repeat(200) {
                val value = policy.backoffFor(attempt)
                assertTrue(
                    "attempt=$attempt 退避 $value 越界，期望 [${(base * 0.9).toLong()}, ${(base * 1.1).toLong()}]",
                    value >= (base * 0.9).toLong() && value <= (base * 1.1).toLong(),
                )
            }
        }
    }

    // ------------------------------------------------------------ ⑥ CancellationException 穿透

    @Test
    fun `cancellation propagates without retrying`() {
        var calls = 0
        val openStream: () -> Flow<ChatStreamEvent> = {
            calls++
            flow { throw CancellationException("stopped") }
        }

        val thrown = runBlocking {
            withTimeout(5_000) {
                runCatching {
                    streamWithRecovery(fastPolicy(2), onRetry = {}) { openStream() }.toList()
                }.exceptionOrNull()
            }
        }

        assertTrue("取消异常必须原样抛出，实际: $thrown", thrown is CancellationException)
        assertEquals("取消不得触发重试", 1, calls)
    }

    // ------------------------------------------------------------ ⑦ 非超时异常不重试

    @Test
    fun `non timeout failures are not retried`() {
        var calls = 0
        val openStream: () -> Flow<ChatStreamEvent> = {
            calls++
            flow { throw IllegalStateException("HTTP 400") }
        }

        val thrown = runBlocking {
            withTimeout(5_000) {
                runCatching {
                    streamWithRecovery(fastPolicy(2), onRetry = {}) { openStream() }.toList()
                }.exceptionOrNull()
            }
        }

        assertTrue(thrown is IllegalStateException)
        assertTrue(thrown !is ChatStreamTimeoutException)
        assertEquals(1, calls)
    }

    // ------------------------------------------------------------ ⑧ Completed 后无事件

    @Test
    fun `completed is the last event`() {
        val factory = CountingFactory(failTimes = 0)
        val collected = runBlocking {
            withTimeout(5_000) {
                streamWithRecovery(fastPolicy(2), onRetry = {}) { factory.open() }.toList()
            }
        }
        assertTrue(collected.last() is ChatStreamEvent.Completed)
        assertEquals(1, collected.count { it is ChatStreamEvent.Completed })
    }

    // ------------------------------------------------------------ 附加：夹取（§8 第 4 项）

    @Test
    fun `fromPreset clamps out of range values`() {
        assertEquals(300_000L, ChatStreamTimeouts.fromPreset(ChatPresetConfig()).idleMs)
        assertEquals(3_600_000L, ChatStreamTimeouts.fromPreset(ChatPresetConfig(streamIdleTimeoutSeconds = 99_999)).idleMs)
        assertEquals(1_000L, ChatStreamTimeouts.fromPreset(ChatPresetConfig(streamIdleTimeoutSeconds = 0)).idleMs)
        assertEquals(2, ChatStreamTimeouts.clampStreamMaxRetries(2))
        assertEquals(5, ChatStreamTimeouts.clampStreamMaxRetries(99))
        assertEquals(0, ChatStreamTimeouts.clampStreamMaxRetries(-1))
    }
}
