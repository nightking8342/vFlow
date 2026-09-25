package com.chaomixian.vflow.ui.chat

/**
 * 流式消息的**原位补丁**纯函数层（`chat-streaming-design.md` §4.4 / B4）。
 *
 * ## 为什么必须下沉成纯函数
 *
 * 本工程的 `app/src/test` 下**零个**测试引用 `ChatViewModel` / `ChatPresetRepository`，
 * 且没有 Robolectric / mockk / androidx.test.core——`ChatViewModel` 是 `AndroidViewModel`、
 * `repository` 无注入缝 ⇒ **VM 内的逻辑无法单测**。
 *
 * 而流式收尾有 11 项必须做的事（§4.4），漏任何一项都是**静默**错误
 * （最典型的是漏 `isPending = false` ⇒ 永远显示「正在生成…」且**永不落盘**）。
 * 故把「列表怎么变」全部放到本文件：**无 Android 依赖，可纯 JVM 单测**。
 * VM 侧只剩「把结果写回 `_uiState`」这一行无逻辑的接线。
 *
 * 参照 `core/workflow/WorkflowPatch.kt` 的做法（本项目已有的同类先例）。
 */

/**
 * 流式期间**原位增长**一条消息的正文与思考内容。
 *
 * ⚠️ 三个「**刻意不做**」，每个都对应一个静默失效：
 *
 * | 不做 | 原因 |
 * |---|---|
 * | 不刷新 `updatedAtMillis`、**不重排会话** | 现有的 `updateMessage`（`ChatViewModel.kt:1192`）会无条件刷新时间戳并 `sortedByDescending` 重排——流式下每秒十几次，**侧边栏会持续跳动** |
 * | 不改 `isPending` | 收尾由 [finalizeStreamingMessage] 负责；流式期间它必须保持 `true`（否则消息会被 `persistSessionState` 提前落盘成半截内容） |
 * | 不查 `activeConversation`，**按 id 全表查找** | 浮窗与 App 共用同一 VM ⇒ 「用户切到别的会话」是**被鼓励的常态**；只查活动会话会让流式内容无处可写 |
 *
 * @return 新列表；**消息 id 或会话 id 不存在时原样返回**（不抛错——流式期间会话可能已被用户删除）。
 */
internal fun List<ChatConversation>.patchStreamingMessage(
    conversationId: String,
    messageId: String,
    content: String,
    reasoningContent: String?,
): List<ChatConversation> {
    var changed = false
    val updated = map { conversation ->
        if (conversation.id != conversationId) return@map conversation
        val messages = conversation.messages.map { message ->
            if (message.id != messageId) return@map message
            changed = true
            // ⚠️ 只改这两个字段。`timestampMillis` / `tokenCount` / `isPending` 全部留到 finalize。
            message.copy(content = content, reasoningContent = reasoningContent)
        }
        // ⚠️ 不重排、不刷 updatedAtMillis —— 见上表
        if (messages === conversation.messages) conversation else conversation.copy(messages = messages)
    }
    return if (changed) updated else this
}

/**
 * 流式收尾要一次写回的**全部**内容。
 *
 * ⚠️ 做成一个参数对象而不是长参数列表：§4.4 的清单有 11 项，
 * 逐个传参会**漏传而编译器不报错**（有默认值）——那正是「静默失效」的温床。
 */
internal data class StreamingFinalizePatch(
    /** 正文。⚠️ 必须是 `Completed.result.content`（**权威值**，见 §4.4 的 B4），不是累积 delta。 */
    val content: String,
    val reasoningContent: String?,
    val tokenCount: Int?,
    val toolCalls: List<ChatToolCall> = emptyList(),
    /** 工具调用的审批态。有工具时必须给 `PENDING`，否则审批卡片不出现。 */
    val toolApprovalState: ChatToolApprovalState? = null,
    /** 是否把会话**排到最前**（刷新 `updatedAtMillis` 并重排）。 */
    val sortConversationToTop: Boolean = true,
)

/**
 * **跟随滚动的内容指纹**（`chat-streaming-design.md` §4.6(3)）。
 *
 * ## 为什么需要它
 *
 * 滚动跟随原先用 `LaunchedEffect(..., messages.lastOrNull()?.id)` 触发，
 * 依赖「AI 回复到达时最后一条消息的 **id 会变**」。
 * 流式改为**原位增长**后 id 恒定 ⇒ 该 effect 在流式期间与收尾时**都不触发**
 * ⇒ 不跟随滚动、审批卡片出现时也不滚过去（0.1 复发）。
 *
 * ⇒ 改为监听**内容指纹**的变化。
 *
 * ## ⚠️ 关键设计：**不含「距底部的距离」**
 *
 * dsh 的注释（`ChatView.tsx:532-533`）是本条最重要的外部依据：
 * > Follow new flow content while pinned; do NOT re-pin on every render merely
 * > because atBottomRef is true (scroll threshold → setState → snap).
 *
 * 即：**「是否在底部」这个判定本身不能触发滚动**，否则形成
 * 「滚动 → 判定在底部 → 触发重组 → 又滚到底」的**反馈环**，把用户的惯性滚动直接吸到底部。
 *
 * ⇒ 指纹只装「结构性 + 末尾内容」两个维度；
 * 「要不要跟随」是**在指纹变化时**才去查的一次性判定。
 */
internal data class FollowSignal(
    val conversationId: String?,
    val lastMessageId: String?,
    val messageCount: Int,
    /** 末尾消息的 `content` + `reasoningContent` 总长（够用且便宜，不必存全文）。 */
    val lastContentLength: Int,
    /**
     * 末尾消息**是否已带工具调用**。
     *
     * ⚠️ 这一维是**审批卡片场景（0.1）的直接编码**，不能省：
     * 收尾时正文长度可能**没变**（内容已流式显示完，`normalizeAssistantReply` 的
     * 收尾通常不改长度），而**审批卡片是在这一刻出现的**。
     * 若指纹不含本项，收尾那一次不触发滚动 ⇒ **审批卡片仍然滚不到**，
     * 而它正是最初那个 bug 被吞掉的东西。
     */
    val lastHasToolCalls: Boolean,
    /**
     * **最后一条用户消息的 id**。它变化 ⇒ 用户刚发了一条消息。
     *
     * ⚠️ 为什么不是「末尾消息是否来自用户」（我最初的写法，**是错的**）：
     * `ChatViewModel.sendMessage` 先追加用户消息，**紧接着**在协程里追加助手的
     * 占位消息——两者可能在**同一帧**内完成。那样 effect 只会看到**最终状态**，
     * 此时末尾是助手占位消息（`lastIsFromUser == false`）⇒ **判定不成立**
     * ⇒ 真机表现：「在历史会话里发消息，不滚动到底」。
     *
     * 改成「最后一条用户消息的 id」后，无论占位消息是否同帧到达，
     * 这个值**都**会随用户发言而改变，判据稳定。
     */
    val lastUserMessageId: String?,
) {
    companion object {
        internal fun of(messages: List<ChatMessage>?, conversationId: String?): FollowSignal {
            val last = messages?.lastOrNull()
            return FollowSignal(
                conversationId = conversationId,
                lastMessageId = last?.id,
                messageCount = messages?.size ?: 0,
                lastContentLength = (last?.content?.length ?: 0) +
                    (last?.reasoningContent?.length ?: 0),
                lastHasToolCalls = last?.toolCalls?.isNotEmpty() == true,
                lastUserMessageId = messages?.lastOrNull { it.role == ChatMessageRole.USER }?.id,
            )
        }
    }
}

/** [finalizeStreamingMessage] 的结果：新列表 + 实际生效的消息 id（供调用方做后续动作）。 */
internal data class StreamingFinalizeOutcome(
    val conversations: List<ChatConversation>,
    /** 是否真的找到了并收尾了那条消息（false ⇒ 会话或消息已被删除，调用方应跳过后续步骤）。 */
    val applied: Boolean,
)

/**
 * 流式收尾：把累积内容与元数据一次写回，并把 `isPending` **显式置 false**。
 *
 * ## ⚠️ 为什么「显式置 false」是最关键的一步
 *
 * 改动前，`isPending` 是**靠整条消息被 `replacePendingMessage` 替换而消失**的
 * （`ChatViewModel.kt:852` 置 true，**全仓无任何地方改回 false**）。
 * 改成原位增长后那条路径不再存在，若忘记置 false：
 *
 * 1. UI **永远显示「正在生成…」**；
 * 2. 且 `persistSessionState`（`ChatViewModel.kt:1147`）与 `sanitizeForRestore`（`:1159`）
 *    都 `filterNot { it.isPending }` ⇒ 这条消息**永远不落盘**，重启后消失。
 *
 * ## §4.4 的 11 项收尾清单（本函数负责其中的 7 项）
 *
 * | # | 项 | 本函数 |
 * |---|---|---|
 * | 1 | `isPending = false` | ✅ |
 * | 2 | `content`（含空内容兜底） | ✅（兜底由调用方传入前处理，见下） |
 * | 3 | `reasoningContent` | ✅ |
 * | 4 | `tokenCount` | ✅ |
 * | 5 | `toolCalls` + `toolApprovalState` | ✅ |
 * | 6 | `timestampMillis` | ✅（收尾时刻，非请求发起时刻） |
 * | 7 | `conversation.updatedAtMillis` + 重排 | ✅（`sortConversationToTop`） |
 * | 8 | `deriveConversationTitle` | ❌ 由调用方做（它是 `ChatViewModel` 的私有方法） |
 * | 9 | `isSending` / `isAgentRunning` | ❌ 调用方 |
 * | 10 | `pendingPermissionRequest` | ❌ 调用方 |
 * | 11 | `processNextQueuedPromptIfIdle()` | ❌ 调用方 |
 *
 * ⚠️ **空内容兜底**（清单第 2 项）刻意**不在这里做**：
 * 非流式路径的兜底是 `result.content.ifBlank { "模型返回了空内容。" }`（`ChatViewModel.kt:909`），
 * 若这里再写一份，两份文案会漂移。⇒ 由调用方在构造 [StreamingFinalizePatch] 时套用同一句。
 *
 * @param sortConversationToTop 终态时 `true`（会话应排到最前）；**中断时也应为 `true`**
 *   ——用户刚在这里说过话，会话本就该浮上来。
 */
internal fun List<ChatConversation>.finalizeStreamingMessage(
    conversationId: String,
    messageId: String,
    patch: StreamingFinalizePatch,
    now: Long = System.currentTimeMillis(),
): StreamingFinalizeOutcome {
    var applied = false
    val updated = map { conversation ->
        if (conversation.id != conversationId) return@map conversation
        val messages = conversation.messages.map { message ->
            if (message.id != messageId) return@map message
            applied = true
            message.copy(
                content = patch.content,
                reasoningContent = patch.reasoningContent,
                tokenCount = patch.tokenCount,
                toolCalls = patch.toolCalls,
                toolApprovalState = patch.toolApprovalState,
                // ⚠️ 收尾时刻。`ChatViewModel.kt:851` 写下的是**请求发起**时刻，
                // 长回复下偏差可达数分钟，且 `MessageFooterRow` 显示的就是它。
                timestampMillis = now,
                // ⚠️⚠️ 最关键的一行：不置 false 会永远显示「正在生成…」且永不落盘
                isPending = false,
            )
        }
        conversation.copy(
            messages = messages,
            updatedAtMillis = if (patch.sortConversationToTop) now else conversation.updatedAtMillis,
        )
    }
    // ⚠️ 重排是**会话级**操作，必须与「是否找到消息」解耦：
    // 即便消息已被删除（applied=false），只要会话还在且要求置顶，也应重排。
    val ordered = if (patch.sortConversationToTop) updated.sortedByDescending { it.updatedAtMillis } else updated
    return StreamingFinalizeOutcome(conversations = ordered, applied = applied)
}
