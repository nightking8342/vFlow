package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.execution.ExecutionLogLevel

/**
 * 工作流的**执行日志详细度**（元数据里的「日志等级」）。
 *
 * 运行时会过滤掉低于本档位的日志行 —— 用户由此可以在不删步骤的前提下，
 * 把某个工作流（含其中的日志模块）的日志一键关掉。
 *
 * ## ⚠️ 它只管「本次执行的 `detailedLog`」
 *
 * | 通道 | 受影响 |
 * |---|---|
 * | 首页「最近日志」看到的 `detailedLog` | ✅ |
 * | 交给 Agent 的临时工作流日志（同源） | ✅ |
 * | 超级岛 / 执行通知的实时状态 | ❌（另一次独立调用，见 `WorkflowExecutor` 的进度处） |
 * | `adb logcat`、logcat 调试工具与触发器 | ❌（`DebugLogger` 照常打） |
 * | 崩溃上报的 `recentLogs` | ❌（读的是 `DebugLogger.crashBuffer`） |
 *
 * ⇒ **文案上必须说清**，否则用户会以为「我把日志关了」然后疑惑 logcat 里为什么还有。
 *
 * ## ⚠️ 它**不是**一道「数据不外泄」的边界
 *
 * 脚本用 `vars_api.setGlobalVar(k, v)` 把值存成命名/全局变量之后，任何步骤都能用
 * `[[k]]` / `{{vars.k}}` 读回来 —— 这条路不经过日志，本开关挡不住。
 * 本枚举解决的是**噪音与体积**，不是「值能不能离开工作流」。
 *
 * ## ⚠️⚠️ 它在磁盘上有**两种**形状，取决于谁写的
 *
 * | 写入方 | 值 |
 * |---|---|
 * | `WorkflowManager` 读盘路径（`parseWorkflowRecord`） | `storageValue`（`error`） |
 * | 单文件导出（`createWorkflowExportData`） | `storageValue`（`error`） |
 * | **备份链路**（`WorkflowScope` 整对象 Gson） | **枚举名**（`ERROR`） |
 *
 * 原因是本枚举**没有** `@SerializedName`（对照 [WorkflowReentryBehavior] 有），
 * 而 Gson 默认用枚举名。两条读路径各自吃得下自己那一份
 * （`fromStoredValue` 会 lowercase 后比对，Gson 按枚举名找），
 * 所以**当前不构成缺陷** —— 但**不要把两个格式互相喂**：
 * 手工拼 JSON 时喂错形式，Gson 会读到未知值并留 `null`，
 * 而 `null` 落在这个非空字段上要等下一次 `copy()` 才炸。
 */
enum class WorkflowLogLevel(
    /** 稳定存储值。⚠️ **不得写本地化文案** —— 那样切语言后已保存的工作流会全部失配。 */
    val storageValue: String,
    /** 保留的最低级别。见 [ExecutionLogLevel.rank]。 */
    val minRank: Int,
) {
    /** 全部保留 —— **默认档，与改动前的行为逐字节一致**。 */
    VERBOSE("verbose", ExecutionLogLevel.DEBUG.rank),

    /** 去掉 D：步骤切换（`[#3] -> 执行: 延迟`）与模块自报进度（`[进度] …`）。 */
    NORMAL("normal", ExecutionLogLevel.INFO.rank),

    /** 只留 W + E：重试、跳过错误、循环异常退出等。 */
    WARNING("warning", ExecutionLogLevel.WARN.rank),

    /** 只留 E：失败与异常。 */
    ERROR("error", ExecutionLogLevel.ERROR.rank);

    /**
     * 该级别是否应当被记录。
     *
     * ⚠️ `>=` 而非 `>`：档位是「**保留的最低级别**」，
     * `WARNING` 档必须留下 W 本身（否则「仅警告与错误」会把警告也滤掉）。
     */
    fun allows(level: ExecutionLogLevel): Boolean = level.rank >= minRank

    companion object {
        /**
         * 从存储值解析。
         *
         * ⚠️ **未知值 / null / 空串一律回落 [VERBOSE]**：
         * - 旧备份、旧 JSON 里没有这个字段 ⇒ 走这里；
         * - 万一将来改了枚举名，老工作流也不会**静默丢日志**（宁可多记）。
         *
         * ⚠️ 与 `AiModuleRiskLevel` 那种「未知 ⇒ 取更保守的一档」方向**相反**，
         * 这里取的是「**不丢信息**」的那一档。理由：日志是排障的唯一依据，
         * 而「多记几条」的代价远小于「故障时没有线索」。
         */
        fun fromStoredValue(value: String?): WorkflowLogLevel {
            val normalized = value?.trim()?.lowercase()
            return entries.firstOrNull { it.storageValue == normalized } ?: VERBOSE
        }
    }
}
