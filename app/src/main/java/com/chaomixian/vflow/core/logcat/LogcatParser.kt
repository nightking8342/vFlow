package com.chaomixian.vflow.core.logcat

/**
 * logcat 输出解析。**纯函数，无 Android 依赖，可单测。**
 *
 * 供 logcat 调试工具与 logcat 触发器共用（设计文档：
 * `docs/fork/logcat-debug-tool.md` §4.4、`docs/fork/logcat-trigger-design.md` §5.1）。
 *
 * 关键设计（都是真机实测踩出来的，改动前请先读对应文档）：
 *
 * 1. **降级语义**：无前缀行是**上一条日志的续行**，因此**继承上一条的 tag/level**，
 *    而不是置空 / 置 `V`。置 `V` 会让 `min_level`（默认 `I`）把它过滤掉，
 *    等于"不丢行"实际丢行——见触发器文档 §5.1 的修订说明。
 * 2. **头标记行必须单独识别**：`--------- beginning of main` 这类结构行不匹配正则，
 *    若走降级路径会污染继承链——见调试工具文档 §4.4。
 * 3. **`-T n` 不保证 n 行**：实测 `-T 5` 得 7 行、`-t 5` 得 6 行，多出的是标记行。
 *    所以不要依赖"输入多少行就解析出多少行"。
 */
object LogcatParser {

    /**
     * `threadtime` 格式（logcat 默认 `-v` 值）：
     * ```
     * 09-16 21:04:13.040  9278 15081 I WeChat: 具体消息
     * └─日期─┘└──时间──┘  └pid┘└tid┘ └级┘└─TAG─┘└message┘
     * ```
     *
     * ⚠️ 级别字符类 `[VDIWEF]` 必须与 [LogLevel] 的枚举一致。
     * 首版设计文档里枚举是 `V/D/I/W/E` 而正则含 `F`，两端不一致——已修正为都含 `F`。
     *
     * TAG 用 `(.+?)` 非贪婪 + `\s*:\s?` 分隔，以兼容 TAG 内含空格的少数情况
     * （TAG 本身不允许空格，但 ROM 实现差异下偶有例外，宽松些更稳）。
     * message 允许为空（`.*`）。
     */
    private val THREADTIME = Regex(
        """^(\d{2}-\d{2})\s+(\d{2}:\d{2}:\d{2}\.\d+)\s+(\d+)\s+(\d+)\s+([VDIWEF])\s+(.+?)\s*:\s?(.*)$"""
    )

    /**
     * logcat 的类别分隔标记。
     *
     * 实测样本：`--------- beginning of main` / `system` / `crash` / `events`。
     * 用宽松匹配（5 个以上短横 + `beginning of`）以兼容不同 ROM 的措辞。
     */
    private val BUFFER_MARKER = Regex("""^-{5,}\s*beginning of\s+\S+.*$""")

    /**
     * 解析整个缓冲区输出。
     *
     * 有状态地逐行解析——降级行需要"上一条成功解析的日志"才能继承 tag/level。
     *
     * @param text 原始输出（多行）
     * @return 解析结果列表（含标记行与降级行，调用方按需过滤）
     */
    fun parse(text: String): List<LogcatParseResult> {
        val results = ArrayList<LogcatParseResult>()
        // 上一条「成功解析」的日志。降级行继承它的 tag/level/timestamp。
        var lastParsed: LogcatLine? = null

        for (rawLine in text.lineSequence()) {
            when (val result = parseLine(rawLine, lastParsed)) {
                is LogcatParseResult.Line -> {
                    // 只有真正的日志行才更新基线；降级行不更新（它继承自基线）
                    if (!result.line.isContinuation) {
                        lastParsed = result.line
                    }
                    results.add(result)
                }

                LogcatParseResult.Blank -> Unit          // 空行直接丢弃，不入结果
                is LogcatParseResult.BufferMarker -> results.add(result)
            }
        }
        return results
    }

    /**
     * 解析单行。
     *
     * @param raw  原始行
     * @param prev 上一条**成功解析**的日志；用于降级时继承 tag/level
     */
    fun parseLine(raw: String, prev: LogcatLine?): LogcatParseResult {
        if (raw.isBlank()) return LogcatParseResult.Blank
        if (BUFFER_MARKER.matches(raw.trim())) return LogcatParseResult.BufferMarker(raw)

        val m = THREADTIME.matchEntire(raw)
        if (m != null) {
            // 用下标而非 destructured：Kotlin 的 destructured 最多只支持 5 个组件
            val date = m.groupValues[1]
            val time = m.groupValues[2]
            val pid = m.groupValues[3]
            val tid = m.groupValues[4]
            val levelChar = m.groupValues[5]
            val tag = m.groupValues[6]
            val message = m.groupValues[7]
            return LogcatParseResult.Line(
                LogcatLine(
                    tag = tag.trim(),
                    message = message,
                    // 正则已限定为 [VDIWEF]，fromChar 理论上不会为 null；
                    // 真为 null 说明正则与枚举脱节，降级而非崩溃。
                    level = levelChar.firstOrNull()?.let { LogLevel.fromChar(it) } ?: LogLevel.DEFAULT,
                    pid = pid.toIntOrNull() ?: -1,
                    tid = tid.toIntOrNull() ?: -1,
                    timestamp = "$date $time",
                    raw = raw,
                    isContinuation = false,
                )
            )
        }

        // ── 降级路径 ──────────────────────────────────────────────
        // 不要丢行：把它当作上一条日志的续行。
        // 关键是**继承** tag/level 而非置空/置 V（见类注释第 1 条）。
        return LogcatParseResult.Line(
            LogcatLine(
                tag = prev?.tag.orEmpty(),
                message = raw,
                level = prev?.level ?: LogLevel.DEFAULT,
                pid = prev?.pid ?: -1,
                tid = prev?.tid ?: -1,
                timestamp = prev?.timestamp.orEmpty(),
                raw = raw,
                isContinuation = true,
            )
        )
    }

    /**
     * 只取日志行（丢弃标记行）。
     *
     * 调试工具的列表渲染用这个——标记行对调试无价值。
     */
    fun parseLines(text: String): List<LogcatLine> =
        parse(text).filterIsInstance<LogcatParseResult.Line>().map { it.line }

    /**
     * 按 TAG 聚合计数（调试工具的「TAG 统计」用）。
     *
     * **只统计非降级行**——降级行的 tag 是继承来的，
     * 计入会让"续行很多"的 TAG 虚高，误导用户。
     *
     * @return 按行数降序的 (tag, 行数) 列表；空 tag 直接丢弃
     */
    fun countByTag(lines: List<LogcatLine>): List<Pair<String, Int>> =
        lines.asSequence()
            .filter { !it.isContinuation && it.tag.isNotBlank() }
            .groupingBy { it.tag }
            .eachCount()
            .entries
            .sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { it.key to it.value }

    /**
     * 统计降级行数（调试工具底部展示"降级 N 行"用）。
     *
     * 这个数字是关键提示：降级行没有 TAG 前缀，
     * 用户配的 `message contains` 很可能因此失配。
     */
    fun countContinuations(lines: List<LogcatLine>): Int = lines.count { it.isContinuation }

    /**
     * 判断是否为 logcat 的类别分隔标记行。
     *
     * 单独暴露以便调用方自行过滤。
     */
    fun isBufferMarker(line: String): Boolean = BUFFER_MARKER.matches(line.trim())
}
