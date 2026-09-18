package com.chaomixian.vflow.core.logcat

/**
 * 日志导出内容的渲染。**纯函数，无 Android 依赖，可单测。**
 *
 * 只负责把日志**渲染成字符串**；落盘与分享属 IO，由 `LogcatExportManager` 负责。
 * 这样拆是为了让"导出内容长什么样"可以被单测锁住——
 * 导出是用户拿去给开发者看的东西，格式错了没人会立刻发现。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §5。
 */
object LogcatExportRenderer {

    /** 退格 (BS) 与换页 (FF) 的码位。 */
    private const val CODE_BACKSPACE = 0x08
    private const val CODE_FORM_FEED = 0x0C

    /**
     * 渲染纯文本（`.log`，默认格式）。
     *
     * **原样回放 [LogcatLine.raw]，不重新拼装**——
     * `raw` 是 logcat 的原始输出，重拼会丢掉降级行的本来面目，
     * 而"这行没有 TAG 前缀"恰恰是用户排查触发器失配时最需要看到的信息。
     *
     * 降级行加 `↳ ` 前缀与界面保持一致，让用户一眼认出多行日志的续行。
     */
    fun renderText(lines: List<LogcatLine>): String =
        lines.joinToString("\n") { line ->
            if (line.isContinuation) "↳ ${line.raw}" else line.raw
        }

    /**
     * 渲染 JSON（可选格式）。
     *
     * 含**抓取条件**（§5）：用户报问题时，开发者需要知道"这是在什么条件下抓的"
     * 才能判断日志是否足以定位问题。
     *
     * 手写 JSON 而非引入序列化框架：结构简单且字段固定，
     * 手写能让"必须转义"这件事显式可见（见 [escapeJson]）。
     */
    fun renderJson(
        lines: List<LogcatLine>,
        filter: LogcatFilter,
        state: CaptureState,
        capturedAtMs: Long,
    ): String = buildString {
        append("{\n")
        append("  \"exportVersion\": 1,\n")
        append("  \"capturedAtMillis\": ").append(capturedAtMs).append(",\n")

        // ---- 抓取条件：复现问题所需的最小信息 ----
        append("  \"filter\": {\n")
        append("    \"minLevel\": \"").append(filter.minLevel.name).append("\",\n")
        append("    \"tagQuery\": ").append(quote(filter.tagQuery)).append(",\n")
        append("    \"showOwnApp\": ").append(filter.showOwnApp).append(",\n")
        append("    \"lineLimit\": ").append(filter.lineLimit).append("\n")
        append("  },\n")

        // ---- 数据源：决定了这批日志"是什么" ----
        append("  \"source\": {")
        append("\"state\": ").append(quote(stateName(state))).append(", ")
        append("\"description\": ").append(quote(sourceDescription(state))).append(", ")
        append("\"lineCount\": ").append(lines.size).append(", ")
        append("\"continuationCount\": ").append(LogcatParser.countContinuations(lines))
        append("},\n")

        append("  \"lines\": [\n")
        lines.forEachIndexed { index, line ->
            append("    {")
            append("\"timestamp\": ").append(quote(line.timestamp)).append(", ")
            append("\"pid\": ").append(line.pid).append(", ")
            append("\"tid\": ").append(line.tid).append(", ")
            append("\"level\": \"").append(line.level.char).append("\", ")
            append("\"tag\": ").append(quote(line.tag)).append(", ")
            append("\"message\": ").append(quote(line.message)).append(", ")
            append("\"continuation\": ").append(line.isContinuation)
            append("}")
            if (index != lines.lastIndex) append(",")
            append("\n")
        }
        append("  ]\n")
        append("}\n")
    }

    /**
     * 导出文件名，形如 `vflow-logcat-20260918-104900.log`。
     *
     * @param extension 不含点，如 `log` / `json`
     */
    fun buildFileName(timestamp: String, extension: String): String =
        "vflow-logcat-$timestamp.$extension"

    /** 分享面板里的标题。 */
    fun buildShareTitle(lineCount: Int, extension: String): String =
        "vFlow logcat ($lineCount 行, .$extension)"

    /** 数据源的一句话说明，与界面底部文案口径一致（§4.1.2）。 */
    fun sourceDescription(state: CaptureState): String = when (state) {
        is CaptureState.Capturing -> "采集文件（本次采集区间内的日志）"
        is CaptureState.Stale -> "上次异常结束的采集文件"
        is CaptureState.Idle -> "logcat 缓冲区（滚动窗口，两次抓取结果可能不同）"
    }

    private fun stateName(state: CaptureState): String = when (state) {
        is CaptureState.Capturing -> "CAPTURING"
        is CaptureState.Stale -> "STALE"
        is CaptureState.Idle -> "IDLE"
    }

    /** 加引号并转义。日志正文里什么字符都可能有，**转义不是可选的**。 */
    private fun quote(value: String): String = "\"" + escapeJson(value) + "\""

    /**
     * JSON 字符串转义。
     *
     * ⚠️ 必须处理**控制字符**而不只是引号与反斜杠：logcat 的正文里
     * 混进 0x00–0x1F（尤其是 ANSI 转义序列里的 ESC）很常见，
     * 不转义会产出**语法非法**的 JSON——而用户拿到的是一份"看起来能打开"的文件。
     * 这类问题不会在生成时报错，只会在别人解析时炸。
     */
    internal fun escapeJson(value: String): String = buildString(value.length + 8) {
        for (ch in value) {
            // 用码位比较而非 '\b' / '\f' 字面量：后者在源码里是**不可见的控制字符**，
            // 编辑器与 diff 里都看不出来，极易被误删或误改（本文件初版就写坏过一次）。
            when (ch.code) {
                '"'.code -> append("\\\"")
                '\\'.code -> append("\\\\")
                '\n'.code -> append("\\n")
                '\r'.code -> append("\\r")
                '\t'.code -> append("\\t")
                CODE_BACKSPACE -> append("\\b")
                CODE_FORM_FEED -> append("\\f")
                else -> if (ch.code < 0x20) {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
    }
}
