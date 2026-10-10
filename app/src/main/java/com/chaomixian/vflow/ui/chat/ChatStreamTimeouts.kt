package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.logging.DebugLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient

/**
 * SSE 与「非流式」两个 OkHttpClient 的**唯一真值来源**（`chat-stream-recovery-design.md` 决策 D11）。
 *
 * 单一空闲阈值（决策 D2/D3）：[idleMs] 同时覆盖「首字节之前」与「流中」两个阶段；
 * 两个阶段的区分只发生在 [ChatSse] 的失败分类里（不是两个可配数值）。
 *
 * ## 为什么夹取放在 [fromPreset]，而不是构造函数
 *
 * 构造函数是**测试注入缝**（用例要把阈值设成 200 ms，见设计文档 §6.1），
 * 若在构造器里夹到 1 s 下限，200 ms 会被抬成 1000 ms、用例根本测不到超时。
 * 故「用户输入的唯一入口」才夹取，语义不变：夹住 + 记日志 + **不抛异常**。
 */
internal data class ChatStreamTimeouts(
    val idleMs: Long,
    val connectMs: Long,
) {
    /** 按配置缓存：数据类相等（值相同）即复用同一个 client。 */
    fun toClient(): OkHttpClient = clients.getOrPut(this) {
        OkHttpClient.Builder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(idleMs, TimeUnit.MILLISECONDS)
            // 写超时不属本课题（SSE 请求体很小），保持改动前的 120 s，避免顺手改行为。
            .writeTimeout(WRITE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .build()
    }

    companion object {
        private const val LOG_TAG = "ChatStreamTimeouts"
        const val MIN_TIMEOUT_MS = 1_000L        // 1 s
        const val MAX_TIMEOUT_MS = 3_600_000L    // 3600 s
        const val WRITE_TIMEOUT_MS = 120_000L
        private const val CONNECT_TIMEOUT_MS = 30_000L
        private val clients = ConcurrentHashMap<ChatStreamTimeouts, OkHttpClient>()

        val DEFAULT = ChatStreamTimeouts(idleMs = 300_000L, connectMs = CONNECT_TIMEOUT_MS)

        /** 用户输入的唯一入口：秒 → 夹到 1..3600 → ms。越界夹住并记日志，不抛异常（§4.7）。 */
        fun fromPreset(preset: ChatPresetConfig): ChatStreamTimeouts {
            val raw = preset.streamIdleTimeoutSeconds
            val clamped = raw.coerceIn((MIN_TIMEOUT_MS / 1000L).toInt(), (MAX_TIMEOUT_MS / 1000L).toInt())
            if (clamped != raw) {
                DebugLogger.w(LOG_TAG, "streamIdleTimeoutSeconds=$raw 越界，已夹到 $clamped（允许 1..3600 s）")
            }
            return ChatStreamTimeouts(idleMs = clamped * 1000L, connectMs = CONNECT_TIMEOUT_MS)
        }

        /** §3.3 / §4.7：重试次数夹取 **0..5**（上限 = Codex `DEFAULT_STREAM_MAX_RETRIES`；0 = 关闭重试）。 */
        fun clampStreamMaxRetries(raw: Int): Int {
            val clamped = raw.coerceIn(0, MAX_STREAM_RETRIES)
            if (clamped != raw) {
                DebugLogger.w(LOG_TAG, "streamMaxRetries=$raw 越界，已夹到 $clamped（允许 0..$MAX_STREAM_RETRIES）")
            }
            return clamped
        }

        const val MAX_STREAM_RETRIES = 5
    }
}
