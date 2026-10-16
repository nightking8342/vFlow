package com.chaomixian.vflow.ui.chat

/**
 * **引擎 ↔ 宿主 的唯一接缝**（fork：AI 能力统一 §3.11 / D11，批 3 的 ④）。
 *
 * 一个接口 8 个成员，**不拆 ISP**：两个宿主（[ChatHost] / [WorkflowAgentHost]）的差异
 * 全部落在这里，引擎（[ChatAgentEngine]）只认这个接口 ⇒ 循环 / 工具调度 / 提示词装配
 * 一份代码两处生效。
 *
 * ## 三条已定的边界
 *
 * 1. **取消不入接口**（D11）：引擎跑在**宿主给的** [kotlinx.coroutines.CoroutineScope] 里
 *    （chat 侧 = `viewModelScope.launch`，工作流侧 = 模块自己的协程），宿主 `cancel()` 即可。
 *    ⇒ chat 的 `stopAgent()`（`cancel()` + 同步置 null + 自己改状态）语义**一字不改**。
 * 2. **[artifacts] 必需**：工作流宿主也需要句柄 —— 否则模型没法把 `observe_ui` 返回的
 *    element 传给 `tap`（D11 的原始理由）。
 * 3. **「不适用」的成员给默认实现**：`requestApproval` 默认 [ApprovalDecision.Approved]
 *    （工作流宿主，D12）；`onFinish` 默认空（chat 的收尾由宿主自己的驱动路径负责）。
 *
 * ## 与设计稿 §3.11 草案的 4 处微调（逐条理由）
 *
 * | 草案 | 落地 | 理由 |
 * |---|---|---|
 * | `onProgress(update: ProgressUpdate)` | [AgentProgressUpdate]（新 sealed） | `ProgressUpdate` 只有一行文案，承载不了 chat 的**累积正文**（流式打点需要它） |
 * | `requestApproval(call, risk)` | [requestApproval]`(calls, riskLevel)` | 现状审批是**批级**判据（`shouldAutoApproveToolCalls` 用批内 max 风险），单 call 会改变语义 |
 * | `artifacts: ArtifactScope` | `artifacts: ChatAgentArtifactStore` | 执行器（`ChatAgentModuleExecutor.prepareBatch/executeBatch`）吃的就是这个具体类型；引入平行抽象没有收益。「执行级 store」= 每次执行 new 一个实例（不需要新类） |
 * | `AgentMessage` / `ArtifactRef` | `ChatMessage` / `ChatArtifactReference` | 宿主与传输层都吃既有类型，不新造平行类型（少一层适配、少一类不一致） |
 */
internal interface AgentHost {

    // ── ① 策略（构造时定，引擎只读）──

    /** 工具白名单。引擎**据此过滤**下发/声明的工具（保序）。 */
    val toolNames: Set<String>

    /** 轮次上限（单位 = 一次 LLM 往返，D17）。`null` = 不限（chat 现状）。 */
    val maxRounds: Int?

    /** 观察模式（节点树 / 截图 / both，D14）。 */
    val observationMode: ObservationMode

    /** 提示词分段（D13 / D16）。 */
    val promptPlan: AgentPromptPlan

    // ── ② 运行时 ──

    /** 进度回推（chat：流式打点 + 重试提示；工作流：一行文案）。 */
    suspend fun onProgress(update: AgentProgressUpdate)

    /**
     * 批级审批。判据与现状 `shouldAutoApproveToolCalls` **逐字相同**
     * （`ChatToolAutoApprovalScope.allows(batch.riskLevel)`，batch 风险 = 批内 max）。
     *
     * 默认 [ApprovalDecision.Approved] = 工作流宿主（D12：无 UI 可交互 ⇒ 恒放行）。
     */
    suspend fun requestApproval(
        calls: List<ChatToolCall>,
        riskLevel: ChatAgentToolRiskLevel,
    ): ApprovalDecision = ApprovalDecision.Approved

    /** artifact 作用域：**会话级**（chat）/ **执行级**（工作流，每个宿主实例一个）。 */
    val artifacts: ChatAgentArtifactStore

    // ── ③ 收尾 ──

    /**
     * 引擎产出最终结论时回调一次（`runLoop` 的**唯一**收尾出口）。
     *
     * 默认空实现：chat 的收尾由宿主自己的驱动路径负责
     * （`finalizeStreamingMessage` + `persistSessionState` + `persistReferencedArtifacts`），
     * 引擎**不参与** —— 这是「ChatAgent 行为逐条不变」的一部分。
     */
    suspend fun onFinish(outcome: AgentOutcome) = Unit
}

/** 观察模式（D14）：节点树 / 截图 / 两者。 */
internal enum class ObservationMode { NODE_TREE, SCREENSHOT, BOTH }

/**
 * 批级审批结论。
 *
 * [Deferred] 是 chat 的「需要用户交互」——引擎据此只**回报**「这批待批」，
 * 由宿主稍后再驱动（chat 的审批卡片是**异步用户交互**，PENDING 态下没有活协程；
 * 把它做成引擎内的挂起点会推翻 `stopAgent()` 的既有语义，见设计稿 X1）。
 */
internal sealed interface ApprovalDecision {
    data object Approved : ApprovalDecision
    data object Rejected : ApprovalDecision
    data object Deferred : ApprovalDecision
}

/**
 * 引擎 → 宿主的进度（微调 §3.11 的 `ProgressUpdate`）。
 *
 * ⚠️ [Streaming] 携带的是**累积正文**（不是增量），与 `patchStreamingMessage` 的入参口径一致。
 */
internal sealed interface AgentProgressUpdate {
    /** 累积正文（`content` 非增量；`reasoning` 为 null 表示本轮还没有思考内容）。 */
    data class Streaming(val content: String, val reasoning: String?) : AgentProgressUpdate

    /** 一次重试（chat 用它 `_events.tryEmit(...)` + 记 `retriesUsed`）。 */
    data class Retry(val notice: ChatStreamRetryNotice) : AgentProgressUpdate

    /** 一行文案（工作流宿主映射成 `ProgressUpdate(message)`）。 */
    data class Notice(val message: String) : AgentProgressUpdate
}

/**
 * 循环停止原因。
 *
 * ⚠️ 本批**只有 [FINISHED] / [MAX_ROUNDS] 有产生路径** —— `runRound` 的失败与取消都是
 * **抛异常外传**（由宿主自己的 catch 处理），`runLoop` 内不会产出这两个值。
 * [CANCELLED] / [ERROR] 是给宿主预留的取值（批 4 的工作流宿主若要自行判定可填），
 * **本批不可达** ⇒ 不为它们写断言用例。
 */
internal enum class StopReason { FINISHED, MAX_ROUNDS, CANCELLED, ERROR }

/** 一次任务的输入（工作流宿主用；chat 的驱动路径不走 `runLoop`）。 */
internal data class AgentTask(
    val instruction: String,
    val history: List<ChatMessage> = emptyList(),
)

/** 一次任务的结论（`runLoop` 的返回值，同时回推给 [AgentHost.onFinish]）。 */
internal data class AgentOutcome(
    val finalText: String,
    val success: Boolean,
    val roundsUsed: Int,
    val stoppedBy: StopReason,
    val referencedArtifacts: List<ChatArtifactReference>,
)

/**
 * **工作流宿主**排除的工具名（D12：工作流步骤里没有「工作流读写」这个语义）。
 *
 * 六个名字全部取自 [ChatAgentToolRegistry] 的既有常量（**不写字符串字面量**）——
 * 常量改名时这里会编译失败，而不是静默失效。
 *
 * `load_skill` 一并排除：技能正文是 **chat 侧的知识**，且当前 `SKILL_CATALOG` 为空
 * ⇒ 工作流宿主下发它只会给模型一个永远查不到东西的入口。
 */
internal val WORKFLOW_EXCLUDED_TOOL_NAMES: Set<String> = setOf(
    CHAT_TEMPORARY_WORKFLOW_TOOL_NAME,
    CHAT_SAVE_WORKFLOW_TOOL_NAME,
    CHAT_GET_WORKFLOW_TOOL_NAME,
    CHAT_UPDATE_WORKFLOW_TOOL_NAME,
    CHAT_LIST_WORKFLOWS_TOOL_NAME,
    CHAT_LOAD_SKILL_TOOL_NAME,
)
