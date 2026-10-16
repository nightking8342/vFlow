package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString

/**
 * **工作流宿主**（fork：AI 能力统一 §3.6 两宿主对照表 + D12 / D13 / D14 / D16 / D17，批 3 的 ④）。
 *
 * 与 [ChatHost] 的差异**只有** §4.4 表里的那几行：
 *
 * | 成员 | 本宿主 | 与 chat 的差别 |
 * |---|---|---|
 * | [toolNames] | 全量 − [WORKFLOW_EXCLUDED_TOOL_NAMES] | 去掉工作流读写类工具（工作流步骤里没有这个语义） |
 * | [maxRounds] | 默认 **15**（= `AgentModule.max_steps` 默认） | chat 为 `null`（不限） |
 * | [observationMode] | **BOTH**（保持 `AgentModule` 现状，D14 方向） | chat 为 `NODE_TREE` |
 * | [promptPlan] | [WorkflowPromptPlan]（空协议段）/ [WorkflowModulePromptPlan]（有协议段） | chat 委托上游规则段 |
 * | [requestApproval] | **不覆写** ⇒ 默认 [ApprovalDecision.Approved] | chat 按自动审批档位 |
 * | [artifacts] | **执行级**（每个宿主实例一个） | chat 为会话级 |
 * | [onFinish] | 记录 [lastOutcome] + [toExecutionResult] | chat 由 VM 自己的收尾路径负责 |
 *
 * ⚠️ 批 4 起**有生产调用方**（`AgentModule`）——「端到端未验证」的注释已由
 * `AgentEngineToolCallProtocolTest` 的「真实传输 + 多轮工具循环」用例取代。
 */
internal class WorkflowAgentHost(
    /** 全量工具名（生产：`AgentModule` 自己的工具表 ⇒ 白名单过滤对它是**恒等**的）。 */
    allToolNames: Collection<String>,
    override val maxRounds: Int? = DEFAULT_MAX_ROUNDS,
    override val observationMode: ObservationMode = ObservationMode.BOTH,
    /** 可配前段（引用模型配置时**值拷贝**，也可手填；D7/D13）。 */
    prefix: String = "",
    /**
     * **新增（批 4）**：宿主专属**协议段** —— `AgentModule` 现有提示词里**去掉 19 条规则**
     * 的那部分（含日期 / RECENT APPS 等**每次执行现算**的内容）。
     *
     * 空 ⇒ 与批 3 的 [WorkflowPromptPlan] **逐字节相同**（既有测试因此不受影响）；
     * 非空 ⇒ 用 [WorkflowModulePromptPlan]（段序：前段 → 协议段 → 公共段 → 规则段 → UserGoal）。
     */
    protocolSection: String = "",
    /** 进度出口（生产：`AgentModule` 的 `onProgress`）；默认丢弃。 */
    private val progressSink: suspend (ProgressUpdate) -> Unit = {},
    /**
     * **新增（批 4）**：**流式打点出口**。
     *
     * 存在的理由：工作流的监视器页面有「模型整段原文进思考流」这个既有能力
     * （fork 的 `publishModelOutput`），而 [progressSink] 只能承载**一行文案**。
     * 默认空实现 ⇒ 不传它时本宿主的行为与批 3 **逐字相同**（既有测试不传它）。
     *
     * ⚠️ 这是相对方案 §5.3 的一处**增量**偏离（方案只列了 `protocolSection`）：
     * 不加它就只能**丢掉**监视器的思考流（那是 fork 明确修过的回归），
     * 或把 `ChatAgentEngine` 子类化（会破坏它「生产代码不继承」的既有不变量）。
     */
    private val streamingSink: suspend (content: String, reasoning: String?) -> Unit = { _, _ -> },
) : AgentHost {

    override val toolNames: Set<String> = allToolNames.toSet() - WORKFLOW_EXCLUDED_TOOL_NAMES

    override val promptPlan: AgentPromptPlan =
        if (protocolSection.isBlank()) {
            WorkflowPromptPlan(prefix)
        } else {
            WorkflowModulePromptPlan(prefix, protocolSection)
        }

    /**
     * **执行级** store：随本次执行创建、随执行结束丢弃。
     * 语义与 chat 的会话级 store 一致（同一个实例透传给 `prepare` / `execute`），
     * 模型仍能把 `observe_ui` 返回的 element 句柄传给 `tap`。
     */
    override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore()

    /** 引擎的最终结论（[toExecutionResult] 的数据源）。 */
    var lastOutcome: AgentOutcome? = null
        private set

    override suspend fun onProgress(update: AgentProgressUpdate) {
        when (update) {
            // 工作流侧没有「流式正文」这个界面概念（进度只显示一行文案）。
            is AgentProgressUpdate.Notice -> progressSink(ProgressUpdate(update.message))
            // 但**监视器页面**要那一整段原文（fork 既有能力）⇒ 走独立出口。
            is AgentProgressUpdate.Streaming -> streamingSink(update.content, update.reasoning)
            is AgentProgressUpdate.Retry -> Unit
        }
    }

    override suspend fun onFinish(outcome: AgentOutcome) {
        lastOutcome = outcome
    }

    /**
     * 引擎结论 → 工作流模块的 [ExecutionResult]。
     *
     * ⚠️ [StopReason.MAX_ROUNDS] 的文案与形状**照抄 `AgentModule` 现状**
     * （`Success` + `success = false`，`AgentModule.kt` 的 `Task stopped: Max steps (n) reached.`）——
     * 只是把 `steps` 改成 `rounds`（本引擎的单位是 LLM 往返，D17-①）。
     * D18（该不该改成 `Failure`）未定案 ⇒ 本任务不擅自改。
     */
    fun toExecutionResult(): ExecutionResult {
        val outcome = lastOutcome
            ?: return ExecutionResult.Failure("AI 任务未完成", "Agent 循环没有产出结果。")
        return when (outcome.stoppedBy) {
            StopReason.FINISHED -> ExecutionResult.Success(
                mapOf(
                    "result" to VString(outcome.finalText),
                    "success" to VBoolean(true),
                ),
            )

            StopReason.MAX_ROUNDS -> ExecutionResult.Success(
                mapOf(
                    // fork 批 4 返工（父会话 Q4 裁决）：**到顶的正文用模型的收尾总结**
                    // （`finalText` = 收尾轮 `runRound` 产出的正文）。
                    //
                    // D17-② 明确要求「到顶给一次收尾总结机会」—— 若这里仍返回固定文案，
                    // 那次机会就白费了（模型总结了却没人看）。固定文案**降级为兜底**：
                    // 只有收尾轮**没产出任何文本**（空白）时才用它。
                    //
                    // ⚠️ `success = false` 不变、D18 不动（本项只改正文来源）。
                    "result" to VString(
                        outcome.finalText.ifBlank {
                            "Task stopped: Max rounds (${maxRounds ?: outcome.roundsUsed}) reached."
                        },
                    ),
                    "success" to VBoolean(false),
                ),
            )

            // runLoop 不会产出这两个值（见 StopReason 的注释）；这里是**宿主侧**的
            // 兜底映射：无法完成的循环一律按失败上报，不伪装成成功。
            StopReason.ERROR,
            StopReason.CANCELLED,
            -> ExecutionResult.Failure(
                "AI 任务失败",
                outcome.finalText.ifBlank { "Agent 循环未正常结束（${outcome.stoppedBy}）。" },
            )
        }
    }

    internal companion object {
        /** = `AgentModule.max_steps` 的默认值（D17-④：工作流侧保留硬上限）。 */
        const val DEFAULT_MAX_ROUNDS = 15
    }
}

/**
 * **工作流模块的提示词**（fork：AI 能力统一 D13 / D16，批 4 的 T6）。
 *
 * 段序：`[前段] + [协议段] + [公共段] + [规则段] + [# User Goal]`（段间空行，空段不产生空行）。
 *
 * - **协议段** = `AgentModule` 现有提示词里**去掉 19 条规则**的那部分
 *   （`# SETUP` / `# CRITICAL INSTRUCTION` / `# MANDATORY PROTOCOL` / … / `# THINKING PROTOCOL`，
 *   含日期 / RECENT APPS 等每次执行现算的内容）。
 * - **规则段** = [WORKFLOW_RULES]（那 19 条的**唯一来源**；`AgentModule` 内的副本已删除）。
 *
 * ⚠️ `AgentPromptPlan` / [SHARED_RULES] / [WORKFLOW_RULES] 都在**只读**的
 * `ui/chat/AgentPrompts.kt` 里 ⇒ 本 plan 类只能定义在这里（`WorkflowAgentHost.kt` 可改）。
 */
internal class WorkflowModulePromptPlan(
    private val prefix: String,
    private val protocolSection: String,
) : AgentPromptPlan {
    override fun build(context: AgentPromptContext): String =
        listOfNotNull(
            prefix.trim().ifBlank { null },
            protocolSection.trim().ifBlank { null },
            SHARED_RULES,
            WORKFLOW_RULES,
            context.instruction
                ?.takeIf { it.isNotBlank() }
                ?.let { "# User Goal\n\"$it\"" },
        ).joinToString(separator = "\n\n")
}
