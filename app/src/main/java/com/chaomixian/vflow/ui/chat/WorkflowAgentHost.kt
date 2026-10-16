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
 * | [promptPlan] | [WorkflowPromptPlan]（前段 + 公共段 + 专属段 + `# User Goal`） | chat 委托上游规则段 |
 * | [requestApproval] | **不覆写** ⇒ 默认 [ApprovalDecision.Approved] | chat 按自动审批档位 |
 * | [artifacts] | **执行级**（每个宿主实例一个） | chat 为会话级 |
 * | [onFinish] | 记录 [lastOutcome] + [toExecutionResult] | chat 由 VM 自己的收尾路径负责 |
 *
 * ⚠️ **它只做纯逻辑**，本任务**不接 `AgentModule`**（那是批 4 的 T6）⇒
 * **端到端未验证**（没有任何生产调用方构造它，只被单测用假 provider 覆盖）。
 */
internal class WorkflowAgentHost(
    /** 全量工具名（生产：`toolRegistry.getTools().map { it.name }`）。 */
    allToolNames: Collection<String>,
    override val maxRounds: Int? = DEFAULT_MAX_ROUNDS,
    override val observationMode: ObservationMode = ObservationMode.BOTH,
    /** 可配前段（引用模型配置时**值拷贝**，也可手填；D7/D13）。 */
    prefix: String = "",
    /** 进度出口（生产：`AgentModule` 的 `onProgress`）；默认丢弃。 */
    private val progressSink: suspend (ProgressUpdate) -> Unit = {},
) : AgentHost {

    override val toolNames: Set<String> = allToolNames.toSet() - WORKFLOW_EXCLUDED_TOOL_NAMES

    override val promptPlan: AgentPromptPlan = WorkflowPromptPlan(prefix)

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
            is AgentProgressUpdate.Streaming, is AgentProgressUpdate.Retry -> Unit
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
                    "result" to VString(
                        "Task stopped: Max rounds (${maxRounds ?: outcome.roundsUsed}) reached.",
                    ),
                    "success" to VBoolean(false),
                ),
            )

            // 本批 runLoop 不会产出这两个值（见 StopReason 的注释）；这里是**宿主侧**的
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
