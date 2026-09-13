package com.chaomixian.vflow.ui.chat

/**
 * 折叠态窄条要显示的内容。
 *
 * 设计要点（见 `docs/fork/chat-float-window-design.md` §1.1 / FR2）：
 * 窄条上必须显示 **AI 说的内容**，而不是一个泛泛的「运行中」状态灯 ——
 * 因为用户跟着 AI 离开 Chat 页后，丢失的正是「AI 在说什么」这条信息通道。
 *
 * 优先级：
 *  1. 有 PENDING 审批 → 提示待审批（最高优先，避免用户错过）
 *  2. 有最新助手文本 → 显示该文本（本需求的核心）
 *  3. 无助手文本但 Agent 在跑 → 退化为状态动词（「正在观察…」）
 *  4. 空闲 → 显示最后一条回复
 *
 * 纯函数、无 Android 依赖，便于单测。
 */
object ChatFloatSummary {

    /** 窄条显示内容 + 是否需要醒目标记。 */
    data class Content(
        val text: String,
        val tone: Tone,
    )

    enum class Tone {
        /** 正常：显示 AI 文本 */
        NORMAL,

        /** Agent 运行中（含无文本时的状态动词） */
        RUNNING,

        /** 有待审批操作，需醒目提示 */
        ATTENTION,

        /** 出错 */
        ERROR,
    }

    /**
     * 从会话消息推导折叠态内容。
     *
     * @param messages 当前会话消息（按时间正序）
     * @param isAgentRunning Agent 是否在运行
     * @param hasPendingApproval 是否有待审批的工具调用
     * @param runningHintText 无助手文本时显示的动词占位（由调用方提供已本地化的文案）
     * @param errorFallback 出错且无文本时的占位
     */
    fun derive(
        messages: List<ChatMessage>,
        isAgentRunning: Boolean,
        hasPendingApproval: Boolean,
        runningHintText: String,
        errorFallback: String,
    ): Content {
        if (hasPendingApproval) {
            // 待审批时优先展示「最近一条助手文本」，让用户知道 AI 想干什么；
            // 没有文本才退回通用提示。
            val lastAssistant = lastAssistantText(messages)
            return Content(
                text = lastAssistant ?: runningHintText,
                tone = Tone.ATTENTION,
            )
        }

        // 最新一条助手/错误消息
        val latest = messages.lastOrNull { message ->
            (message.role == ChatMessageRole.ASSISTANT && message.content.isNotBlank()) ||
                message.role == ChatMessageRole.ERROR
        }

        if (latest != null) {
            val text = latest.content.singleLine()
            if (text.isNotBlank()) {
                return Content(
                    text = text,
                    tone = if (latest.role == ChatMessageRole.ERROR) Tone.ERROR else {
                        if (isAgentRunning) Tone.RUNNING else Tone.NORMAL
                    },
                )
            }
        }

        // 没有可显示的助手文本
        return if (isAgentRunning) {
            Content(text = runningHintText, tone = Tone.RUNNING)
        } else {
            Content(text = errorFallback, tone = Tone.NORMAL)
        }
    }

    /** 最近一条有内容的助手文本。 */
    private fun lastAssistantText(messages: List<ChatMessage>): String? {
        return messages.lastOrNull { message ->
            message.role == ChatMessageRole.ASSISTANT && message.content.isNotBlank()
        }?.content?.singleLine()
    }

    /** 压缩为单行，供窄条显示（多余的换行/空白会破坏单行省略效果）。 */
    private fun String.singleLine(): String = replace(Regex("""\s+"""), " ").trim()
}
