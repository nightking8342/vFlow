package com.chaomixian.vflow.core.execution

/**
 * 一条执行日志的级别。
 *
 * ⚠️ **不要与 `WorkflowLogLevel` 混淆**：
 * - 本枚举描述**一条日志本身**有多严重（由写日志的人决定）；
 * - `WorkflowLogLevel` 是**用户在元数据里选的过滤档位**（决定哪些级别能落进日志）。
 *
 * 两者的 [rank] 必须同序（见 `WorkflowLogLevel.allows`），否则过滤会失灵。
 */
enum class ExecutionLogLevel(val rank: Int) {
    DEBUG(0),
    INFO(1),
    WARN(2),
    ERROR(3);

    companion object {
        /**
         * 从 `WorkflowExecutor` 内部日志器的级别字符（`"D"` / `"I"` / `"W"` / `"E"`）解析。
         *
         * ⚠️ **未知字符一律当 [DEBUG]**（最低）——这样「过滤不了它」而不是「静默丢掉它」。
         * 宁可多记一条，也不要在解析出错时把日志吃掉。
         */
        fun fromChar(level: String): ExecutionLogLevel = when (level) {
            "E" -> ERROR
            "W" -> WARN
            "I" -> INFO
            else -> DEBUG
        }
    }
}
