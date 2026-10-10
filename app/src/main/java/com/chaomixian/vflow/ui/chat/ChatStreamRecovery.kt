package com.chaomixian.vflow.ui.chat

import kotlin.random.Random
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow

/**
 * Chat 流超时的**恢复层**（`chat-stream-recovery-design.md` §4.4）。
 *
 * ⚠️ **纯 Kotlin，零 Android 依赖**（也不 import `DebugLogger`）⇒ 可纯 JVM 单测。
 *
 * ## 只 catch 超时异常
 *
 * [ChatStreamTimeoutException] 是**唯一**被捕获的类型。`CancellationException`
 * 与其它异常一律原样冒泡 —— 用户点「停止」会 cancel 本协程，取消必须立即生效，
 * 否则会出现「点了停止它自己又偷偷重试」（§4.4 / D10）。
 */
internal class ChatStreamTimeoutException(
    val kind: SseFailureCause,
    val elapsedHintMs: Long?,
    message: String,
) : IllegalStateException(message)

/**
 * 重试策略。
 *
 * 退避：第 attempt 次重试（1-based）等待 `min(base × 2^(attempt-1), cap) × (1 ± jitter)`。
 * 默认 1 s → 2 s → 4 s → 8 s → 10 s（封顶），±10% 抖动避免惊群。
 */
internal data class ChatStreamRecoveryPolicy(
    val maxRetries: Int = DEFAULT_MAX_RETRIES,   // 2
    val backoffBaseMs: Long = 1_000L,
    val backoffCapMs: Long = 10_000L,
    val jitterRatio: Double = 0.10,
) {
    /** 第 attempt 次重试（1-based）的等待时长：min(base × 2^(attempt-1), cap) × (1 ± jitter)。 */
    fun backoffFor(attempt: Int): Long {
        val raw = (backoffBaseMs shl (attempt - 1).coerceAtLeast(0)).coerceAtMost(backoffCapMs)
        val jitter = 1.0 + jitterRatio * (Random.nextDouble() * 2.0 - 1.0)
        return (raw * jitter).toLong().coerceAtLeast(0L)
    }

    companion object {
        const val DEFAULT_MAX_RETRIES = 2
    }
}

/** 每次准备重试时回调一次（用户提示 / 计数都挂在这里）。 */
internal data class ChatStreamRetryNotice(
    val attempt: Int,       // 1-based：第几次重试
    val maxAttempts: Int,   // = maxRetries + 1（总尝试次数）
    val kind: SseFailureCause,
    val delayMs: Long,
)

/**
 * 带超时恢复的流包装。
 *
 * @param openStream ⚠️ **必须是「每次调用开一条新流」的工厂**，不能是 Flow 实例（§4.5 / D8）。
 *   [ChatStreamAssembler] 有状态且在 `stream()` 调用时创建 ⇒ 复用同一个 Flow 实例
 *   会拿到跑脏的归约器，表现为**静默状态污染**（回复出现两遍开头 / 截断被判成正常收尾）。
 * @param onRetry    每次准备重试时回调一次。
 *
 * 重试预算**不跨轮共享**：函数每次被调用都是新预算（工具调用轮各自独立，§4.8）。
 */
internal fun streamWithRecovery(
    policy: ChatStreamRecoveryPolicy,
    onRetry: (ChatStreamRetryNotice) -> Unit,
    openStream: () -> Flow<ChatStreamEvent>,
): Flow<ChatStreamEvent> = flow {
    var attempt = 0
    while (true) {
        var committed = false          // 本轮是否已向 UI 提交过内容
        try {
            openStream().collect { event ->
                if (event.commitsToUi()) committed = true
                emit(event)
            }
            return@flow
        } catch (timeout: ChatStreamTimeoutException) {
            // ⚠️ 已提交过内容 ⇒ 半截正文比重新来过更有价值，不重试（§4.3）。
            if (committed || attempt >= policy.maxRetries) throw timeout
            attempt++
            val delayMs = policy.backoffFor(attempt)
            onRetry(ChatStreamRetryNotice(attempt, policy.maxRetries + 1, timeout.kind, delayMs))
            delay(delayMs)          // 可取消 ⇒ 退避窗口内点「停止」同样立即生效
        }
    }
}

/**
 * 本轮是否已向 UI 提交过内容（§4.3）。
 *
 * ⚠️ **只有这三类算「已提交」**：工具 / usage 事件在 VM 里一律 `-> Unit`（不进 UI），
 * 故它们到达过也不阻止重试。
 */
private fun ChatStreamEvent.commitsToUi(): Boolean = when (this) {
    is ChatStreamEvent.TextDelta, is ChatStreamEvent.ReasoningDelta, is ChatStreamEvent.Completed -> true
    else -> false
}
