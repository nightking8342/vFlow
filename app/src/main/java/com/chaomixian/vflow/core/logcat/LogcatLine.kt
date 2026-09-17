package com.chaomixian.vflow.core.logcat

/**
 * logcat 日志行的数据模型。
 *
 * 供 logcat 调试工具与 logcat 触发器共用。
 * 设计文档见 `docs/fork/logcat-debug-tool.md` §4.4 与
 * `docs/fork/logcat-trigger-design.md` §5.1。
 */

/**
 * 日志级别。
 *
 * ⚠️ 顺序即优先级，**不可重排**——`min_level` 过滤依赖 [priority] 比较大小。
 *
 * `F`（FATAL）在 Android 的 `Log` 里排在 `E` 之后，本枚举沿用该序。
 * `S`（SILENT）不表示真实日志级别，仅用于"全部静音"的 filter spec，
 * 因此**不参与**解析结果的级别判定。
 */
enum class LogLevel(val char: Char, val priority: Int) {
    VERBOSE('V', 2),
    DEBUG('D', 3),
    INFO('I', 4),
    WARN('W', 5),
    ERROR('E', 6),
    FATAL('F', 7);

    companion object {
        /** 从级别字符解析；无法识别时返回 null。 */
        fun fromChar(c: Char): LogLevel? = entries.firstOrNull { it.char == c }

        /** 解析失败的降级默认值。见 `LogcatParser` 的降级语义。 */
        val DEFAULT = VERBOSE
    }
}

/**
 * 一行解析后的 logcat 日志。
 *
 * @param tag       TAG；降级行继承上一条的值
 * @param message   消息正文；降级行为整行原文
 * @param level     级别；降级行继承上一条的值
 * @param pid       进程号；降级行为 -1
 * @param tid       线程号；降级行为 -1
 * @param timestamp 时间戳原文（`threadtime` 的 `MM-dd HH:mm:ss.SSS`）；降级行继承上一条的值
 * @param raw       完整原始行
 * @param isContinuation 是否为降级行（无 `threadtime` 前缀，通常是上一条日志的续行）
 */
data class LogcatLine(
    val tag: String,
    val message: String,
    val level: LogLevel,
    val pid: Int,
    val tid: Int,
    val timestamp: String,
    val raw: String,
    val isContinuation: Boolean,
) {
    /**
     * 是否满足级别过滤。
     *
     * ⚠️ 之所以能直接比较 [LogLevel.priority]，是因为降级行**继承了上一条的级别**
     * （见 `LogcatParser`）。若沿用旧设计把降级行置为 `V`，这里会把它们全部过滤掉——
     * 那正是 `logcat-trigger-design.md` §5.1 修订掉的错误。
     */
    fun passesLevel(minLevel: LogLevel): Boolean = level.priority >= minLevel.priority
}

/**
 * 解析结果：一行日志，或一个被识别并跳过的结构行。
 */
sealed interface LogcatParseResult {
    /** 正常的日志行（含降级行）。 */
    data class Line(val line: LogcatLine) : LogcatParseResult

    /**
     * logcat 的类别分隔标记，不是日志，例如：
     * ```
     * --------- beginning of main
     * --------- beginning of system
     * --------- beginning of crash
     * ```
     *
     * ⚠️ 必须**单独识别**而不是丢给降级路径——否则它们会被当成"续行"
     * 并**污染上一条的 tag/level 继承链**（真机实测发现，见调试工具文档 §4.4）。
     */
    data class BufferMarker(val raw: String) : LogcatParseResult

    /** 空行，直接跳过。 */
    data object Blank : LogcatParseResult
}
