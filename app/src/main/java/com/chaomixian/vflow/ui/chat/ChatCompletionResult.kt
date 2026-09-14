package com.chaomixian.vflow.ui.chat

data class ChatCompletionResult(
    val content: String,
    val reasoningContent: String?,
    val totalTokens: Int?,
    val toolCalls: List<ChatToolCall> = emptyList(),
    /**
     * Anthropic 的 prompt 缓存明细。
     *
     * **为什么单独记这三个而不是并进 [totalTokens]**：它们与「新内容」不是并列关系，
     * 而是回答不同问题——[cacheCreationTokens] 说「这轮往缓存里写了多少」，
     * [cacheReadTokens] 说「这轮从缓存里读到了多少」。判据在二者之间：
     *
     * ```
     * 第 1 轮：cacheCreation ≈ 8000, cacheRead = 0     ← 冷启动，正常
     * 第 2 轮：cacheRead     ≈ 8000, cacheCreation = 0 ← ✅ 命中
     *        cacheRead     = 0,    cacheCreation ≈ 8000 ← ❌ 断点失效，每轮重写
     * ```
     *
     * **注意**：非首轮出现 `cacheCreation > 0` 就是明确的失败信号——
     * 这是「正常写入」与「反复重写」的分界点，光看比率分不出来。
     *
     * OpenAI 系协议不返回这些字段（它们是自动前缀缓存，无可观测声明），
     * 故这三个字段在那些链路上恒为 null。
     */
    val cacheCreationTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    /** Anthropic 在缓存编辑删除 KV 缓存内容时返回。本项目未启用缓存编辑，预期恒为 null。 */
    val cacheDeletedTokens: Int? = null,
)
