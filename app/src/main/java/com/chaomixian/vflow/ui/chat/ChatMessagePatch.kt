package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.execution.ExecutionState

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

// ---------------------------------------------------------------------------
// 临时工作流的失败可见性（`docs/fork/agent-debug-failure-visibility.md`）
// ---------------------------------------------------------------------------

/**
 * 失败摘要的日志行前缀。
 *
 * ⚠️ 必须含 `E/` + tag，不能只找「模块执行失败」四个字 ——
 * 错误消息**本身**可能包含这四个字，会产生误匹配。
 */
private const val FAILURE_LINE_PREFIX = "E/WorkflowExecutor: 模块执行失败: "

/**
 * SKIP 策略的**专属**日志行前缀（`WorkflowExecutor` 的 SKIP 分支）。
 *
 * ⚠️⚠️ **这是判定「有步骤被跳过」的唯一可靠锚点**，不能用失败行代替：
 *
 * `WorkflowExecutor` 的重试循环里 `finalResult` 每轮被重新赋值，任一次成功就 `break`，
 * 最后的 `when` **只在最终结果上执行**。所以：
 *
 * | 场景 | `E/模块执行失败` | 结果 |
 * |---|---|---|
 * | 直接失败（STOP / RETRY 耗尽） | 会打 | 判失败 ✓ |
 * | `RETRY` 重试 N 次后**成功** | **不会打** | — |
 * | `RETRY` 途中抛异常（最终成功） | 每次尝试都打 `E/模块执行异常` | ⚠️ 日志有 E 行但执行正确 |
 *
 * ⇒ 若判据是「含 E 行」，最后一行会把**正确的执行误判成失败**。
 * 而本行由 SKIP 分支自己打，**只在最终 Failure 且策略为 SKIP 时出现**。
 */
private const val SKIPPED_FAILURE_LINE_PREFIX = "W/WorkflowExecutor: 根据策略，跳过错误继续执行。"

/** 日志行的时间戳前缀形如 `[14:12:22.657] `（见 `WorkflowExecutor.appendToLog`）。 */
private val LOG_LINE_PATTERN = Regex("""^\[\d{2}:\d{2}:\d{2}\.\d{3}\] """)

/**
 * 把多行文本截到 [maxLength] 字符，超出时追加 `...` 提示。
 *
 * ⚠️ 取的是**开头**——所以调用方必须**先抽取、后截断**：
 * 失败行写在执行末尾，先截断会让它落进被丢弃的部分（见 [buildTemporaryWorkflowOutputText]）。
 *
 * （原为 `ChatAgentModuleExecutor` 的私有方法，随失败可见性改造下移到本纯函数文件。）
 */
internal fun truncateMultiline(text: String, maxLength: Int = 4_000): String {
    val normalized = text.trim()
    return if (normalized.length > maxLength) {
        normalized.take(maxLength) + "\n..."
    } else {
        normalized
    }
}

/**
 * 逐行找以 [prefix] 开头的日志行，返回**最后一条**的正文；没有则 null。
 *
 * ⚠️ **逐行扫描**，不要对整个文本做 `substringAfterLast` ——
 * 堆栈回溯里出现同样前缀会误匹配。必须先剥掉行首的 `[时间] ` 再看前缀。
 */
private fun lastLogLineAfter(detailedLog: String, prefix: String): String? {
    var found: String? = null
    detailedLog.lineSequence().forEach { rawLine ->
        val line = rawLine.removePrefix(LOG_LINE_PATTERN.find(rawLine)?.value.orEmpty()).trim()
        if (line.startsWith(prefix)) {
            found = line.removePrefix(prefix).trim()
        }
    }
    // ⚠️ 这里**不能**加 `takeIf { it.isNotBlank() }` —— 前缀本身可能就是整行的全部内容
    // （SKIP 标记行就是：`W/…根据策略，跳过错误继续执行。` 之后没有别的字），
    // 加了会把「命中」误判成「没找到」。空与非空由调用方各自决定。
    return found
}

/**
 * 从执行日志里取失败摘要（最后一条 `E/模块执行失败`）；取不到返回 null。
 *
 * ⚠️ **必须传入【未截断】的 `detailedLog`**：日志是先截断到 4000 字符再拼进卡片的，
 * 而失败行写在执行**末尾** —— 工作流越长越容易被截掉，届时本函数静默返回 null。
 */
internal fun extractFailureSummary(detailedLog: String): String? =
    lastLogLineAfter(detailedLog, FAILURE_LINE_PREFIX)?.takeIf { it.isNotBlank() }

/**
 * 日志里是否存在 SKIP 专属行 —— 即「有步骤失败但按策略跳过了」。
 *
 * 判据理由见 [SKIPPED_FAILURE_LINE_PREFIX] 的注释。
 */
internal fun hasSkippedFailure(detailedLog: String): Boolean =
    lastLogLineAfter(detailedLog, SKIPPED_FAILURE_LINE_PREFIX) != null

/**
 * 临时工作流的**终态 → 工具结果状态**。
 *
 * ⚠️⚠️ 不能只判 `Finished`：**`Finished` 只说明「跑完了」，不等于「没出错」**。
 * 某步失败但策略为 `SKIP` 时工作流会继续跑完 ⇒ 终态 `Finished` ⇒
 * 若直接映射成 `SUCCESS`，卡片和 Agent 会**同时**看到 `completed successfully.`，
 * 而失败只在日志里。故此处额外查 SKIP 专属行。
 */
internal fun temporaryWorkflowStatus(terminalState: ExecutionState): ChatToolResultStatus =
    when (terminalState) {
        is ExecutionState.Finished ->
            if (hasSkippedFailure(terminalState.detailedLog)) ChatToolResultStatus.ERROR
            else ChatToolResultStatus.SUCCESS

        else -> ChatToolResultStatus.ERROR
    }

/**
 * 拼装临时工作流卡片 / 工具结果的正文。
 *
 * ⚠️ **返回值形状**：本函数与 [temporaryWorkflowStatus] 必须**成对**使用 ——
 * 状态与正文要一起产出。若只抽状态而把正文构造留在别处，本函数里
 * 「摘要提到最前」「`Execution log` 段保留」这些改动就会在重构中丢失，且**静默**
 * （卡片仍显示 ERROR，只是没有详情）—— 与本次改造目的相反。
 *
 * ⚠️ [detailedLog] 必须是**未截断**的原始日志：摘要先从这里抽，再交给
 * [truncateMultiline] 截断（先抽取、后截断）。
 */
internal fun buildTemporaryWorkflowOutputText(
    workflowName: String,
    stepDescriptions: List<String>,
    terminalState: ExecutionState,
    detailedLog: String,
    maxSteps: Int = 30,
): String {
    // ⚠️ 用未截断的 detailedLog 提取，理由见函数注释
    val failureSummary = extractFailureSummary(detailedLog)
    val skippedFailure = terminalState is ExecutionState.Finished && hasSkippedFailure(detailedLog)

    return buildString {
        append(
            when (terminalState) {
                is ExecutionState.Finished -> if (skippedFailure) {
                    "Temporary workflow `$workflowName` completed, but one or more steps failed and were skipped."
                } else {
                    "Temporary workflow `$workflowName` completed successfully."
                }

                is ExecutionState.Failure ->
                    "Temporary workflow `$workflowName` failed at step ${terminalState.stepIndex + 1}."

                is ExecutionState.Cancelled -> "Temporary workflow `$workflowName` was cancelled."
                is ExecutionState.Running -> "Temporary workflow `$workflowName` is still running."
            }
        )

        // 失败摘要提到步骤清单之前 —— 否则它会被埋在清单与日志之间，
        // 而卡片默认只显示 6 行（`ToolMessageCard` 的 contentCollapsed 阈值）。
        if (failureSummary != null) {
            append("\n\n")
            append(failureSummary)
        }

        append("\n\nSteps:\n")
        stepDescriptions.take(maxSteps).forEachIndexed { index, description ->
            append("- ")
            append(index + 1)
            append(". ")
            append(description)
            append("\n")
        }
        if (stepDescriptions.size > maxSteps) {
            append("- ... ")
            append(stepDescriptions.size - maxSteps)
            append(" more steps\n")
        }

        val trimmedLog = detailedLog.trim()
        if (trimmedLog.isNotBlank()) {
            append("\nExecution log:\n")
            append(truncateMultiline(trimmedLog))
            append("\n")
        }
    }.trim()
}
