package com.chaomixian.vflow.ui.chat

/**
 * **哪些消息角色可以带图片** —— 全链路唯一口径（fork：AI 能力统一 §3.1 / §3.7）。
 *
 * 存在的理由是一条**低危但真实**的不一致：
 * [ChatImageBudgetPlanner] 按**所有角色**计数（它只看 `ChatImageAttachment.source`），
 * 而三条协议的历史装配**只渲染 `role == USER`** 的附件。若某条非 USER 消息被挂上附件，
 * 它会**静默吃掉预算却不被发送**（模型看不到、用户也不知道）。
 *
 * 收敛方式：预算规划与装配**看同一份历史** —— `ChatCompletionClient.buildRequest`
 * 先用 [normalizeForRequest] 剥掉不可承载角色上的附件（并记 W），再把结果同时交给
 * 规划器与装配器。于是「计数的那批消息」与「渲染的那批消息」必然是同一批。
 *
 * ⚠️ 本方案下 Agent 截图也走 `role = USER`（`source = AGENT`）⇒
 * [CARRIER_ROLES] 与装配器渲染的角色集合**逐字一致**。
 */
internal object ChatImageCarriers {

    /**
     * 唯一允许带图片的角色集合。
     *
     * `USER` 是三协议的共同交集：`chat/completions` 的 `role:"tool"` content
     * 官方只接受字符串，Responses 的 `function_call_output.output` 同理，
     * 而 assistant 的 `content` 在 `chat/completions` 里也不支持图片 part。
     */
    val CARRIER_ROLES: Set<ChatMessageRole> = setOf(ChatMessageRole.USER)

    fun canCarryImages(role: ChatMessageRole): Boolean = role in CARRIER_ROLES

    /**
     * 剥掉不可承载角色上的附件（**预算规划与装配共用同一份历史**）。
     *
     * 未被剥离的项**保持同一实例**（`assertSame` 级别），不做无谓拷贝 ——
     * 这个方法在每次请求装配时都会跑，而绝大多数消息没有附件。
     */
    fun normalizeForRequest(history: List<ChatMessage>): List<ChatMessage> {
        if (history.none { it.attachments.isNotEmpty() && !canCarryImages(it.role) }) return history
        return history.map { message ->
            if (message.attachments.isEmpty() || canCarryImages(message.role)) {
                message
            } else {
                message.copy(attachments = emptyList())
            }
        }
    }

    /** 会被 [normalizeForRequest] 剥掉的附件条数（只用于日志计数，不参与判定）。 */
    fun droppedAttachmentCount(history: List<ChatMessage>): Int =
        history.sumOf { message ->
            if (canCarryImages(message.role)) 0 else message.attachments.size
        }
}
