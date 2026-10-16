package com.chaomixian.vflow.ui.chat

/**
 * **chat 宿主**（fork：AI 能力统一 §3.6 / §4.4，批 3 的 ④）。
 *
 * 只做「环境相关的事」：把引擎的进度推给 UI 状态、用会话级 artifact store、
 * 按用户配置的自动审批档位决定要不要弹审批卡片。
 *
 * ⚠️ **chat 的收尾不在这里**：`finalizeStreamingMessage` + `persistSessionState` +
 * `persistReferencedArtifacts` 三个出口仍由 `ChatViewModel` 自己的驱动路径负责
 * （流式主路径 / 中断 / 失败），故 [onFinish] 默认空实现（引擎不参与）。
 *
 * ⚠️ **chat 的审批不是引擎内的挂起点**：本宿主的 `requestApproval` 只**回报**
 * 「自动放行」或「待批」（[ApprovalDecision.Deferred]），真正的审批卡片与后续驱动
 * 仍由 `ChatViewModel.approveToolCalls` / `rejectToolCalls` 完成 ——
 * 这样 PENDING 态下**没有**长命挂起协程，`stopAgent()` 的既有语义一字不改（设计稿 X1）。
 */
internal class ChatHost(
    /** 会话 id，**仅用于日志与诊断**（引擎行为不依赖它）。 */
    val debugLabel: String,
    /**
     * 模型配置（提示词前段 = 它的 `systemPrompt`，**活引用**）。
     *
     * ⚠️ 可空：**只跑工具、不发起 LLM 往返**的路径（如「重新执行工具」）手上没有可用配置，
     * 而 [promptPlan] 在这些路径上**从不会被调用**（只有 `runRound` 会调它）。
     * 真到了要拼提示词却没有配置 ⇒ 前段退化为空串（`ChatAgentSkillRouter` 的规则段照旧）。
     */
    private val preset: ChatPresetConfig?,
    override val artifacts: ChatAgentArtifactStore,
    override val toolNames: Set<String>,
    private val autoApprovalScope: () -> ChatToolAutoApprovalScope,
    private val onProgressSink: suspend (AgentProgressUpdate) -> Unit = {},
    private val onFinishSink: (suspend (AgentOutcome) -> Unit)? = null,
) : AgentHost {

    /** D17-④：chat 侧**维持现状零上限**（本任务只落接缝，不改行为）。 */
    override val maxRounds: Int? = null

    /**
     * chat 现状：观察由 `vflow_agent_observe_ui` **工具**驱动（模型自己决定要不要截图），
     * 不是宿主每轮自动装配 ⇒ 本批不变。
     */
    override val observationMode: ObservationMode = ObservationMode.NODE_TREE

    /**
     * **活引用**：prefix 取自模型配置的 `systemPrompt`（每次 `build` 现读），
     * 且文案**全部委托** `ChatAgentSkillRouter` ⇒ 与改造前逐字节相同。
     */
    override val promptPlan: AgentPromptPlan = ChatPromptPlan { preset?.systemPrompt.orEmpty() }

    override suspend fun onProgress(update: AgentProgressUpdate) = onProgressSink(update)

    /**
     * 判据与改造前 `shouldAutoApproveToolCalls` **逐字相同**：
     * `ChatToolAutoApprovalScope.allows(batch.riskLevel)`。
     * `scope == OFF` 由 `allows()` 天然为 false（`maxRiskLevel == null`）。
     */
    override suspend fun requestApproval(
        calls: List<ChatToolCall>,
        riskLevel: ChatAgentToolRiskLevel,
    ): ApprovalDecision =
        if (autoApprovalScope().allows(riskLevel)) {
            ApprovalDecision.Approved
        } else {
            ApprovalDecision.Deferred
        }

    override suspend fun onFinish(outcome: AgentOutcome) {
        onFinishSink?.invoke(outcome)
    }
}
