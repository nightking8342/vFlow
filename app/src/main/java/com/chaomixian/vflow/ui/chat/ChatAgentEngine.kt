package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.logging.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch

/**
 * **宿主参数化的 agent 引擎**（fork：AI 能力统一 §5.1 的 T4 + T9，批 3 的 ④）。
 *
 * 一份代码两处生效：chat 宿主（[ChatHost]）与工作流宿主（[WorkflowAgentHost]）都只提供
 * 「随宿主变化的决策点」（工具白名单 / 提示词 / 审批 / 进度 / artifact store / 收尾），
 * 循环与工具调度**只有这一份**。
 *
 * ## ⛔ 本文件的三条硬约束（改这个文件前先读）
 *
 * 1. **不得 import 任何 `android.*`** —— 这是它能纯 JVM 单测的前提
 *    （本仓库无 Robolectric；`ChatAgentModuleExecutor` / `ChatAgentToolRegistry` /
 *    `ChatViewModel` 都因为要 `Context` 而无法直接单测）。故工具执行器经
 *    [AgentToolRunner] 注入、全量工具表经构造参数注入。
 * 2. **不得使用 `NonCancellable`**，**不得吞 `CancellationException`** ——
 *    宿主 `cancel()` 必须立刻生效（`stopAgent()` 的语义依赖它）。
 * 3. **不得在 [runRound] 里包 `withContext(Dispatchers.IO)`** ——
 *    阻塞 HTTP 的线程归属由传输层自己保证（`ChatCompletionClient` 的
 *    `streamReply` 用 `flow {}` + SSE 层的 `flowOn`）；多包一层会让
 *    「收集仍在调用方线程」这一事实被掩盖（§3.7.8 的真回归）。
 *    由 `AgentEngineRoundTest` 的线程探针锁住。
 */

/**
 * 引擎 ↔ 工具执行器之间的接缝。**只为可测性存在**（见文件头约束 1）。
 *
 * 生产实现 = [ChatAgentModuleToolRunner]（薄适配上游 `ChatAgentModuleExecutor`，零 diff）。
 */
internal interface AgentToolRunner {
    fun prepare(toolCalls: List<ChatToolCall>, artifacts: ChatAgentArtifactStore): ChatPreparedToolBatch

    suspend fun execute(
        batch: ChatPreparedToolBatch,
        artifacts: ChatAgentArtifactStore,
    ): List<ChatToolResult>

    fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult>

    fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult>
}

/** 生产实现：把引擎的接缝原样转给 `ChatAgentModuleExecutor`（该文件**零 diff**）。 */
internal class ChatAgentModuleToolRunner(
    private val executor: ChatAgentModuleExecutor,
) : AgentToolRunner {
    override fun prepare(
        toolCalls: List<ChatToolCall>,
        artifacts: ChatAgentArtifactStore,
    ): ChatPreparedToolBatch = executor.prepareBatch(toolCalls, artifacts)

    override suspend fun execute(
        batch: ChatPreparedToolBatch,
        artifacts: ChatAgentArtifactStore,
    ): List<ChatToolResult> = executor.executeBatch(batch, artifacts)

    override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> =
        executor.buildRejectedResults(toolCalls)

    override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> =
        executor.buildPermissionRequiredResults(batch)
}

/** 一次 LLM 往返的输入。 */
internal data class AgentRoundRequest(
    val preset: ChatPresetConfig,
    /** 本轮 history（chat：会话消息；工作流：循环自己累积）。 */
    val history: List<ChatMessage>,
    /**
     * 工具调用 id 的兜底前缀。
     * chat 传 `"call_${conversationId}_${messageId}_"` —— 与改造前**逐字一致**
     * （拼接后 = `call_<会话>_<消息>_<序号>`）。
     */
    val toolCallIdPrefix: String,
    /** 工作流宿主：任务目标（进 system prompt 的 `# User Goal`）；chat 为 null。 */
    val instruction: String? = null,
    val imageBudget: ChatImageBudget = ChatImageBudget.DEFAULT,
)

/** 一次 LLM 往返的结果。 */
internal data class AgentRoundResult(
    /** **权威值**（`ChatStreamEvent.Completed` 携带），含 tokens / cache / reasoning。 */
    val completion: ChatCompletionResult,
    /** 已做 id 兜底的工具调用。 */
    val toolCalls: List<ChatToolCall>,
    /** 流式过程中累积的正文（过程值；**不是**权威值）。 */
    val streamedContent: String,
    /** 流式过程中累积的思考内容。 */
    val streamedReasoning: String?,
    /** 本轮用掉的重试次数（失败文案要带上它）。 */
    val retriesUsed: Int,
)

/** 直线循环的输入（工作流宿主 + 单测用）。 */
internal data class AgentLoopRequest(
    val preset: ChatPresetConfig,
    val task: AgentTask,
    val observation: AgentObservationProvider,
)

/** 一次观察的结果。 */
internal data class AgentObservation(
    /** 节点树文本（按 mode 决定是否有）。 */
    val text: String?,
    /** 截图（**已落盘、已带 `source = AGENT`**）。 */
    val image: ChatImageAttachment?,
    val screenWidth: Int? = null,
    val screenHeight: Int? = null,
)

/**
 * 观察装配的**机制**由调用方注入（批 4 传 `AgentUtils` 实现）⇒ 引擎自身不做任何
 * 文件 / 截屏 IO，也不需要 `Context`。
 *
 * ⚠️ 本批**没有真实实现**：`runLoop` 只被单测里的假 provider 覆盖（见交付说明「未验证」）。
 */
internal fun interface AgentObservationProvider {
    suspend fun observe(mode: ObservationMode, round: Int, maxRounds: Int?): AgentObservation
}

/**
 * ⚠️ `open` + [runRound] 可覆写**只为一处测试缝**：`runLoop` 的单测需要
 * 「真实宿主 + **假的一轮往返**」来驱动循环（`ChatCompletionClient` 是 final class
 * 且在禁改清单里 ⇒ 无法从传输层注入假响应）。
 * 生产代码**不继承**本类（无任何子类）。
 */
internal open class ChatAgentEngine(
    private val chatClient: ChatCompletionClient = ChatCompletionClient(),
    /** **全量**工具表（生产：`toolRegistry.getTools()`）。白名单过滤在下发侧做。 */
    private val allTools: List<ChatAgentToolDefinition>,
    private val toolRunner: AgentToolRunner,
) {

    /**
     * 白名单过滤（**唯一**的下发 / 声明入口，保序）。
     *
     * chat 的白名单 = 全量 ⇒ 过滤**恒等**（不变量：`availableTools(chatHost) == allTools`）；
     * 工作流宿主据此去掉工作流读写类工具（D12）。
     */
    fun availableTools(host: AgentHost): List<ChatAgentToolDefinition> =
        allTools.filter { it.name in host.toolNames }

    /** 唯一 system prompt 组装入口（分段由 [AgentHost.promptPlan] 决定）。 */
    fun buildSystemPrompt(
        host: AgentHost,
        tools: List<ChatAgentToolDefinition>,
        instruction: String? = null,
    ): String = host.promptPlan.build(
        AgentPromptContext(tools = tools, instruction = instruction),
    )

    /**
     * **一次 LLM 往返**（T4）：工具过滤 → 提示词组装 → 流式（含超时恢复）→ 归约 → 结果。
     *
     * 步骤与改造前**逐条一致**（含错误文案与 id 兜底规则）：
     * 1. `availableTools(host)`（chat 恒等）
     * 2. `host.promptPlan.build(...)`
     * 3. `chatClient.streamReply(preset.copy(systemPrompt = S), history, EMPTY, tools.map { it.toChatToolSchema() })`
     *    —— `skillSelection` 传 EMPTY + 显式 `tools` ⇒ 请求体**逐字节不变**（见 `AgentPrompts` 与
     *    `EngineRequestParityTest`）
     * 4. `streamWithRecovery`（重试次数取 `preset.streamMaxRetries` 的夹取值）
     * 5. 缺 `Completed` ⇒ `error("流已结束但没有收到 Completed 事件。")`（**逐字不变**）
     * 6. id 兜底 `"${prefix}$index"`
     */
    open suspend fun runRound(host: AgentHost, request: AgentRoundRequest): AgentRoundResult {
        val tools = availableTools(host)
        val systemPrompt = buildSystemPrompt(host, tools, request.instruction)

        val streamedContent = StringBuilder()
        val streamedReasoning = StringBuilder()
        var completion: ChatCompletionResult? = null
        var retriesUsed = 0
        // ⚠️ `streamWithRecovery` 的 `onRetry` 是**非挂起** lambda（那个函数在上游、
        // 不在本任务的可改范围内），而 [AgentHost.onProgress] 是挂起的（工作流宿主要把
        // 文案推给 `AgentModule.onProgress`）。这里用 `CoroutineStart.UNDISPATCHED`
        // 在当前线程**就地**启动：chat 宿主的 `onProgress` 全程不挂起 ⇒ 与「直接调用」
        // 逐条等价（`retriesUsed` 的赋值、W 日志、`_events.tryEmit` 仍发生在
        // `delay(退避)` **之前**，用户能在退避窗口里看到「正在重试」提示）。
        val progressScope = CoroutineScope(currentCoroutineContext())

        streamWithRecovery(
            policy = ChatStreamRecoveryPolicy(
                maxRetries = ChatStreamTimeouts.clampStreamMaxRetries(request.preset.streamMaxRetries),
            ),
            onRetry = { notice ->
                retriesUsed = notice.attempt
                progressScope.launch(start = CoroutineStart.UNDISPATCHED) {
                    host.onProgress(AgentProgressUpdate.Retry(notice))
                }
            },
        ) {
            chatClient.streamReply(
                preset = request.preset.copy(systemPrompt = systemPrompt),
                history = request.history,
                skillSelection = ChatAgentSkillSelection.EMPTY,
                tools = tools.map { it.toChatToolSchema() },
                imageBudget = request.imageBudget,
            )
        }.collect { event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> {
                    streamedContent.append(event.text)
                    host.onProgress(
                        AgentProgressUpdate.Streaming(
                            content = streamedContent.toString(),
                            reasoning = streamedReasoning.toString().ifBlank { null },
                        ),
                    )
                }

                is ChatStreamEvent.ReasoningDelta -> {
                    streamedReasoning.append(event.text)
                    host.onProgress(
                        AgentProgressUpdate.Streaming(
                            content = streamedContent.toString(),
                            reasoning = streamedReasoning.toString().ifBlank { null },
                        ),
                    )
                }

                is ChatStreamEvent.Completed -> completion = event.result

                // 工具调用的中间态**不进 UI**：三家头部 Agent 一致反对逐 token
                // 渲染工具参数（共识 C1），且 ApprovalCard 只认收尾后的完整调用。
                is ChatStreamEvent.ToolCallDelta,
                is ChatStreamEvent.ToolCallCompleted,
                is ChatStreamEvent.Usage,
                -> Unit
            }
        }

        val streamed = completion
            ?: error("流已结束但没有收到 Completed 事件。")
        val normalizedToolCalls = streamed.toolCalls.mapIndexed { index, toolCall ->
            toolCall.copy(id = toolCall.id ?: "${request.toolCallIdPrefix}$index")
        }
        return AgentRoundResult(
            completion = streamed,
            toolCalls = normalizedToolCalls,
            streamedContent = streamedContent.toString(),
            streamedReasoning = streamedReasoning.toString().ifBlank { null },
            retriesUsed = retriesUsed,
        )
    }

    // ── 工具调度接缝（artifact store 一律取自 host.artifacts）──

    fun prepareToolBatch(host: AgentHost, toolCalls: List<ChatToolCall>): ChatPreparedToolBatch =
        toolRunner.prepare(toolCalls, host.artifacts)

    suspend fun executeToolBatch(
        host: AgentHost,
        batch: ChatPreparedToolBatch,
    ): List<ChatToolResult> = toolRunner.execute(batch, host.artifacts)

    fun buildRejectedResults(host: AgentHost, toolCalls: List<ChatToolCall>): List<ChatToolResult> =
        toolRunner.rejected(toolCalls)

    fun buildPermissionRequiredResults(
        host: AgentHost,
        batch: ChatPreparedToolBatch,
    ): List<ChatToolResult> = toolRunner.permissionRequired(batch)

    /**
     * **批级审批**：准备批次取 `riskLevel`（= 批内 max）→ 问 [AgentHost.requestApproval]。
     *
     * ⚠️ 这里会调一次 `prepare`，宿主批准时**再调一次** —— 与改造前
     * （`shouldAutoApproveToolCalls` + `approveToolCalls`）**次数相同**，不是新增开销。
     */
    suspend fun decideApproval(host: AgentHost, toolCalls: List<ChatToolCall>): ApprovalDecision {
        val batch = prepareToolBatch(host, toolCalls)
        return host.requestApproval(toolCalls, batch.riskLevel)
    }

    /**
     * **直线循环**（无 UI 审批的宿主：工作流宿主 + 单测）。⚠️ chat 宿主**不调它**
     * —— chat 的循环是「一轮一个 `viewModelScope.launch` + 异步审批卡片」的既有状态机，
     * 见设计稿 §4.1 / X1。
     *
     * D17：单位 = **轮次**；到顶**不是硬中断** —— 给一次收尾总结机会（wrap-up 轮），
     * 该轮若仍产出工具调用则**不执行**、只取文本。
     */
    suspend fun runLoop(host: AgentHost, request: AgentLoopRequest): AgentOutcome {
        // 唯一的收尾出口：组装 outcome → 通知宿主 → 返回。
        // ⚠️ 所有 return 都走它，否则会漏调 onFinish（本仓库踩过同类坑）。
        suspend fun finish(stoppedBy: StopReason, text: String, used: Int): AgentOutcome {
            val outcome = AgentOutcome(
                finalText = text,
                success = stoppedBy != StopReason.ERROR,
                roundsUsed = used,
                stoppedBy = stoppedBy,
                referencedArtifacts = referencedArtifactsOf(text, host.artifacts),
            )
            host.onFinish(outcome)
            return outcome
        }

        var rounds = 0
        var history = request.task.history
        while (true) {
            val roundNumber = rounds + 1
            host.onProgress(
                AgentProgressUpdate.Notice(
                    "正在观察屏幕 (步骤 $roundNumber/${host.maxRounds ?: "∞"})...",
                ),
            )
            val observation = request.observation.observe(
                host.observationMode,
                roundNumber,
                host.maxRounds,
            )
            history = history + observationUserMessage(observation, roundNumber, host.maxRounds)

            val round = runRound(
                host,
                AgentRoundRequest(
                    preset = request.preset,
                    history = history,
                    toolCallIdPrefix = "call_agent_${roundNumber}_",
                    instruction = request.task.instruction,
                ),
            )
            rounds = roundNumber

            if (round.toolCalls.isEmpty()) {
                return finish(StopReason.FINISHED, round.completion.content, rounds)
            }

            val maxRounds = host.maxRounds
            if (maxRounds != null && rounds >= maxRounds) {
                host.onProgress(
                    AgentProgressUpdate.Notice("已达轮次上限（$maxRounds），请求模型收尾总结…"),
                )
                val wrap = runRound(
                    host,
                    AgentRoundRequest(
                        preset = request.preset,
                        history = history + wrapUpInstructionMessage(),
                        toolCallIdPrefix = "call_agent_wrap_${roundNumber}_",
                        instruction = request.task.instruction,
                    ),
                )
                return finish(
                    StopReason.MAX_ROUNDS,
                    wrap.completion.content.ifBlank { round.completion.content },
                    rounds + 1,
                )
            }

            val results = when (val decision = decideApproval(host, round.toolCalls)) {
                ApprovalDecision.Approved ->
                    executeToolBatch(host, prepareToolBatch(host, round.toolCalls))

                ApprovalDecision.Rejected -> buildRejectedResults(host, round.toolCalls)

                ApprovalDecision.Deferred -> {
                    // 安全默认：没人批准就不执行。**不静默** —— 这是宿主实现错了。
                    DebugLogger.e(
                        LOG_TAG,
                        "runLoop 的宿主不该返回 Deferred（无 UI 审批）；按未批准处理",
                    )
                    buildRejectedResults(host, round.toolCalls)
                }
            }
            history = history + toolResultMessages(results)
        }
    }

    /**
     * 正文里引用到的 artifact 句柄 → [ChatArtifactReference]（best-effort）。
     *
     * 判据与「引用即保存」同源（`ChatArtifactReferences.referencedHandles`）；
     * 解析不到值的句柄**跳过**（不编造 typeLabel）。
     */
    private fun referencedArtifactsOf(
        text: String,
        store: ChatAgentArtifactStore,
    ): List<ChatArtifactReference> =
        ChatArtifactReferences.referencedHandles(text).mapNotNull { handle ->
            val value = store.resolve(handle) ?: return@mapNotNull null
            val label = chatArtifactTypeLabel(value) ?: return@mapNotNull null
            ChatArtifactReference(
                key = handle.substringAfterLast('/'),
                handle = handle,
                typeLabel = label,
            )
        }

    private fun toolResultMessages(results: List<ChatToolResult>): List<ChatMessage> {
        val now = System.currentTimeMillis()
        return results.mapIndexed { index, result ->
            ChatMessage(
                role = ChatMessageRole.TOOL,
                content = result.outputText,
                timestampMillis = now + index,
                toolResult = result,
            )
        }
    }

    private fun wrapUpInstructionMessage(): ChatMessage = ChatMessage(
        role = ChatMessageRole.USER,
        content = WRAP_UP_INSTRUCTION,
        timestampMillis = System.currentTimeMillis(),
    )

    /**
     * 一轮观察 → 一条 `role = USER` 消息（文本 + 可选截图附件）。
     *
     * ⚠️ 文本格式**照抄** `AgentModule.kt` 的观察段（`Step: n / max` + 紧急告警 +
     * `Screen Size` + `UI Hierarchy (Reference Only)` + 16000 字截断）。
     * 截图挂在 USER 轮上，与批 1/批 2 的三协议承载结论一致（只有 user 角色能带图）。
     */
    private fun observationUserMessage(
        observation: AgentObservation,
        round: Int,
        maxRounds: Int?,
    ): ChatMessage {
        val stepsLeft = maxRounds?.let { it - round + 1 }
        val urgencyWarning = if (stepsLeft != null && stepsLeft <= 2) {
            "\n[WARNING] Only $stepsLeft steps left! Wrap up."
        } else {
            ""
        }
        val hierarchy = observation.text
        val truncatedHierarchy = hierarchy?.take(MAX_OBSERVATION_TEXT_CHARS)
        val text = buildString {
            append("Step: $round / ${maxRounds ?: "∞"} $urgencyWarning")
            val width = observation.screenWidth
            val height = observation.screenHeight
            if (width != null && height != null) {
                append("\nScreen Size: ${width}x${height} (Coordinates must be normalized 0-1000)")
            }
            if (truncatedHierarchy != null) {
                val suffix = if (hierarchy.length > MAX_OBSERVATION_TEXT_CHARS) "...(truncated)" else ""
                append("\n\nUI Hierarchy (Reference Only):\n$truncatedHierarchy $suffix")
            }
        }
        // 截图一律按 AGENT 档计预算（更早的截图换占位文本）——provider 侧应当已经标好，
        // 这里再钉一次，避免「预算分错档」这种静默失效。
        val attachments = observation.image
            ?.let { listOf(it.copy(source = ChatImageSource.AGENT)) }
            .orEmpty()
        return ChatMessage(
            role = ChatMessageRole.USER,
            content = text,
            timestampMillis = System.currentTimeMillis(),
            attachments = attachments,
        )
    }

    private companion object {
        private const val LOG_TAG = "ChatAgentEngine"
        private const val MAX_OBSERVATION_TEXT_CHARS = 16_000
    }
}

/**
 * 到顶时的收尾指令（D17-②：到顶给一次收尾总结机会，**不是**硬中断）。
 * 与 `AgentModule` 的 `[WARNING] Only N steps left! Wrap up.` 是同一意图的两个阶段。
 */
internal const val WRAP_UP_INSTRUCTION: String =
    "You have reached the round limit. Do NOT call any more tools. " +
        "Summarize what you completed, what is still missing, and any blockers."
