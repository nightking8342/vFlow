package com.chaomixian.vflow.core.workflow.model

data class ActionStepExecutionSettings(
    val policy: String = POLICY_STOP,
    val onRetryExhausted: String = POLICY_STOP,
    val skipJumpTarget: String? = null,
    val retryCount: Int = DEFAULT_RETRY_COUNT,
    val retryIntervalMillis: Long = DEFAULT_RETRY_INTERVAL_MS
) {
    companion object {
        const val KEY_ERROR_POLICY = "__error_policy"
        const val KEY_RETRY_COUNT = "__retry_count"
        const val KEY_RETRY_INTERVAL = "__retry_interval"

        /**
         * fork（2026-10-15）：`RETRY` **重试耗尽后**的动作 —— 策略链（P0-1）。
         *
         * 动机：成熟实现里「重试」与「兜底」是**两件事**
         * （Step Functions 的 `Retry` + `Catch`、Airflow 的 `retries` + `on_failure_callback`）。
         * 而 vFlow 的 `RETRY` 原本**必然**落到「终止 + 弹窗」那一条路上
         * （`WorkflowExecutor` 失败分支的注释原文：「`POLICY_STOP (默认) 或 重试耗尽`」），
         * 用户想「重试几次，还不行就跳过继续」时无路可走。
         *
         * ⚠️ **缺省值必须等于今天的行为**：缺省 = [POLICY_STOP]（终止 + 弹窗）
         * ⇒ 存量工作流的行为**逐字不变**。这条由 `ActionStepExecutionSettingsTest` 锁死。
         *
         * 取值只允许 [ON_RETRY_EXHAUSTED_CHOICES]（`STOP` / `STOP_SILENT` / `SKIP`）——
         * **`RETRY` 不在其中**：重试用尽后再重试没有意义，且会把策略链绕成无限循环。
         * 非法值一律**收敛到 [POLICY_STOP]**（保守 = 不静默改变失败可见性）。
         */
        const val KEY_ON_RETRY_EXHAUSTED = "__on_retry_exhausted"

        /**
         * fork（2026-10-15）：跳过时**跳到哪一步**（P0-5）。
         *
         * ⚠️⚠️ **存 step id，不存显示序号**（决策 D4）。序号是**位置引用**：
         * 在它前面插入/删除一个步骤，目标就漂移，而症状是**静默跳错步骤**
         * （既有的 `vflow.logic.jump` 正是这样，见设计稿 §5）。id 天然不漂移，
         * 代价只是「目标被删 ⇒ 反查不到」—— 那是**可检测**的，降级为「继续下一步」+ W 日志。
         *
         * 仅当策略（或重试用尽后动作）是 `SKIP` 时有语义；缺省 `null` = 不跳转，
         * **等于今天的行为**（跳过 ⇒ 继续下一步）。
         */
        const val KEY_SKIP_JUMP_TARGET = "__skip_jump_target"

        const val POLICY_STOP = "STOP"
        const val POLICY_SKIP = "SKIP"
        const val POLICY_RETRY = "RETRY"

        /**
         * [KEY_ON_RETRY_EXHAUSTED] 的合法取值（**顺序即 UI 顺序**）。
         *
         * ⚠️ 与 `POLICY_*` 的关系：这是**子集**（少了 `RETRY`），
         * 但两者刻意**共用同一批字面量**，不另起一套 —— 存进工作流的是同一串字符串，
         * 将来 `STOP_SILENT` 若改名，两处一起改。
         */
        val ON_RETRY_EXHAUSTED_CHOICES = listOf(POLICY_STOP, POLICY_STOP_SILENT, POLICY_SKIP)

        /**
         * fork（2026-10-15）：**终止，但不弹错误弹窗**。
         *
         * 只抑制那个**阻塞式**弹窗，其余通道一律照旧：
         * 通知栏 `Failed`、`ExecutionState.Failure` 广播、日志 `E/模块执行失败` 行**全部保留**。
         * 与工作流级 `Workflow.silentExecution` 是同一条原则 ——
         * 「静默的语义是『别打扰我』，不是『炸了也别告诉我』」。
         *
         * ⚠️ 附带收益：弹窗是 `suspend` 的，用户不点它 ⇒ 后面的 `postState(Failure)` 不执行、
         * 本协程的 `finally`（WakeLock / 工作目录 / 摘除登记）也不执行。静默终止让失败路径
         * 变成**不依赖 UI 交互**的纯状态机路径（详见 `WorkflowExecutor` 失败分支的注释）。
         *
         * 设计依据：`docs/fork/error-handling-extension-design.md` §2.3（D3）。
         */
        const val POLICY_STOP_SILENT = "STOP_SILENT"

        const val DEFAULT_RETRY_COUNT = 3
        const val DEFAULT_RETRY_INTERVAL_MS = 1000L

        /**
         * 失败时是否弹出阻塞式错误弹窗。
         *
         * ⚠️ 抽成纯函数是为了**可单测**：本仓库无 Robolectric，`WorkflowExecutor` 起不来，
         * 而这条判断的两端都会「静默变差」（漏抑制 = 打扰用户；抑制过头 = 失败无提示）。
         *
         * @param showErrorDialog 调用方的总开关。Agent 调试路径传 `false`（整段跳过），
         *   此时与策略无关，一律不弹。
         */
        fun shouldShowErrorDialog(policy: String, showErrorDialog: Boolean): Boolean {
            return showErrorDialog && policy != POLICY_STOP_SILENT
        }

        /**
         * 把任意输入收敛成合法的「用尽后动作」。
         *
         * ⚠️ 非法值收敛到 [POLICY_STOP] 而**不是**抛异常：工作流参数是从 JSON 读出来的，
         * 旧版本 / 手改过的文件里出现未知值必须能跑（且失败可见性只能往**更可见**的方向退化，
         * 不能反过来静默变成 SKIP —— 那会让一个本该停下的工作流悄悄继续跑）。
         */
        fun normalizeOnRetryExhausted(value: String?): String {
            return if (value != null && value in ON_RETRY_EXHAUSTED_CHOICES) value else POLICY_STOP
        }

        /**
         * **策略链**：算出一个步骤失败时**真正生效**的策略（P0-1）。
         *
         * 只有 `RETRY` 会被展开 —— 其余策略原样返回（`SKIP` 分支一行不改，见设计稿 §3.2 ①）。
         *
         * ⚠️ 抽成**纯函数**是为了可单测：本仓库无 Robolectric，`WorkflowExecutor` 起不来，
         * 而这条判断的失效方式恰好都是**静默**的（缺省值写错 = 存量工作流行为漂移）。
         */
        fun effectivePolicy(policy: String, onRetryExhausted: String): String {
            return if (policy == POLICY_RETRY) normalizeOnRetryExhausted(onRetryExhausted) else policy
        }

        fun fromParameters(parameters: Map<String, Any?>): ActionStepExecutionSettings {
            return ActionStepExecutionSettings(
                policy = parameters[KEY_ERROR_POLICY] as? String ?: POLICY_STOP,
                onRetryExhausted = normalizeOnRetryExhausted(
                    parameters[KEY_ON_RETRY_EXHAUSTED] as? String
                ),
                // 空白一律当「没设置」—— 否则 `""` 会被当成一个（永远解析不到的）目标 id，
                // 每次失败都打一条 W 日志。
                skipJumpTarget = (parameters[KEY_SKIP_JUMP_TARGET] as? String)
                    ?.trim()
                    ?.takeIf { it.isNotEmpty() },
                retryCount = (parameters[KEY_RETRY_COUNT] as? Number)?.toInt() ?: DEFAULT_RETRY_COUNT,
                retryIntervalMillis = (parameters[KEY_RETRY_INTERVAL] as? Number)?.toLong()
                    ?: DEFAULT_RETRY_INTERVAL_MS
            )
        }
    }
}
