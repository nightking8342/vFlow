package com.chaomixian.vflow.server.logcat

/**
 * logcat 输出解析。**跑在 Core 进程**。
 *
 * ## ⚠️ 这是 app 侧 `LogcatParser` 的移植版，两份必须保持一致
 *
 * | | app 侧 | Core 侧（本文件） |
 * |---|---|---|
 * | 路径 | `app/.../core/logcat/LogcatParser.kt` | 本文件 |
 * | 用途 | 调试工具展示；触发器条件的构造与校验 | 流式匹配（热路径） |
 * | 测试 | 18 例（`LogcatParserTest`） | **无**（`core/src` 没有测试目录，见文档 §10） |
 *
 * 因此**语义改动必须同时改两处，且以 app 侧那份为准**（它有测试保护）。
 * 两处不一致的表现是：调试工具里看着能匹配的日志，触发器却匹配不到——
 * 而且没有任何报错。
 *
 * ## 降级语义（必须与 app 侧一致）
 *
 * 无前缀行是**上一条日志的续行**，因此**继承上一条的 tag/level**，
 * 而不是置空 / 置 `V`（后者会被 `min_level` 过滤掉，等于丢行）。
 * 详见 `docs/fork/logcat-trigger-design.md` §5.1 的修订说明。
 *
 * ## 为什么不用正则
 *
 * 本类在**每行日志**上执行（调试工具只在点击时跑一次）。
 * 基准实测（`LogcatTriggerBenchmarkTest`）：正则 919 ns/行、索引扫描 47 ns/行。
 * 绝对差值只有 0.1% 单核（可忽略），但正则每行会产生一个 `MatchResult` +
 * 8 元素 `groupValues` 的分配——高频下 GC 压力不值得。
 *
 * ⚠️ 索引扫描依赖 `threadtime` 的前缀形状，因此**必须先校验形状再按偏移取值**：
 * 盲信偏移时，格式不同的行（`-v epoch` 的输出、ROM 变体）会被解析出垃圾 tag
 * 并**静默匹配失败**。校验不过就返回 null，交给降级路径。
 *
 * ## 实例状态
 *
 * 持有「上一条成功解析的日志」用于降级继承，因此**每个流一个实例**，
 * 不可跨线程共享（Core 侧每条流只在自己的泵线程里用一个实例）。
 */
class LogcatLineParser {

    private var prevTag: String = ""
    private var prevLevel: Char = 'V'
    private var prevPid: Int = -1

    /**
     * 解析一行。
     *
     * @return 解析结果；**返回 null 表示这不是日志行**（空行 / 日志头标记行）。
     *   ⚠️ 调用方必须跳过 null，**不能**把它当成降级行——
     *   标记行会污染 tag/level 继承链（真机实测发现，见调试工具文档 §4.4）。
     */
    fun parse(raw: String): ParsedLine? {
        if (raw.isEmpty()) return null
        if (isBufferMarker(raw)) return null

        val parsed = parseIndexed(raw)
        if (parsed != null) {
            prevTag = parsed.tag
            prevLevel = parsed.level
            prevPid = parsed.pid
            return parsed
        }

        // 降级路径：无 threadtime 前缀的行，当作上一条日志的续行。
        // **不丢行**，且**继承** tag/level（见类注释的降级语义）
        return ParsedLine(
            tag = prevTag,
            message = raw,
            level = prevLevel,
            pid = prevPid,
            isContinuation = true,
        )
    }

    /**
     * 索引扫描解析。
     *
     * 形状（真实的 `threadtime`，日期时间定宽、pid/tid 右对齐）：
     * ```
     * 09-18 10:00:01.100  9278 15081 I WeChat: message
     * └──────18 字符────┘ └─%5d─┘└─%5d─┘ └级┘ └─TAG─┘
     * ```
     *
     * 只对**日期时间那 18 个字符**用绝对偏移（`MM-dd` 恒为 2+2 位、
     * `HH:mm:ss.SSS` 恒为 12 位）；pid 与 tid **不假设宽度**，
     * 用「跳过连续数字」的方式取——这样即便某 ROM 用了不同的列宽也不会解析错。
     *
     * ⚠️ 这也意味着 **pid 为负（不可能）或缺失时会被判为非日志行**，
     * 走降级路径，是安全的方向。
     */
    private fun parseIndexed(raw: String): ParsedLine? {
        // ⚠️ **不要用行长做前置门槛**。曾经设过 MIN_LENGTH=34（按"最短的真实
        // 日志行"拍的），但 `09-18 10:00:01.100  1 2 I MyApp: ` 这种
        // 短 pid/tid + 空消息只有 33 字符，是**完全合法的日志行** ——
        // 被长度门槛拒掉后会走降级路径，表现为该行 tag 变成空/继承错。
        //
        // 真正的形状校验就是下面这 5 个位置断言，它们足够且不自作聪明。
        if (raw.length < TIMESTAMP_WIDTH + 1) return null

        // 日期时间的形状断言。校验失败 = 不是 threadtime 格式，交降级路径
        if (raw[2] != '-' || raw[5] != ' ' ||
            raw[8] != ':' || raw[11] != ':' || raw[14] != '.'
        ) return null

        // ── pid ──
        var p = skipSpaces(raw, TIMESTAMP_WIDTH) ?: return null
        val pidEnd = skipDigits(raw, p) ?: return null
        val pid = raw.substring(p, pidEnd).toIntOrNull() ?: return null
        p = pidEnd

        // ── tid（不关心值，只需跳过）──
        p = skipSpaces(raw, p) ?: return null
        p = skipDigits(raw, p) ?: return null

        // ── 级别 ──
        p = skipSpaces(raw, p) ?: return null
        if (p >= raw.length) return null
        val level = raw[p]
        if (level !in LEVEL_CHARS) return null

        // ── TAG ──
        p = skipSpaces(raw, p + 1) ?: return null
        val colon = raw.indexOf(':', p)
        if (colon < 0) return null

        // 少数 ROM 会输出 `Tag : msg`，把 TAG 尾部的空格回退掉
        var tagEnd = colon
        while (tagEnd > p && raw[tagEnd - 1] == ' ') tagEnd--
        if (tagEnd <= p) return null

        val tag = raw.substring(p, tagEnd)

        // ── message（允许为空）──
        var msgStart = colon + 1
        if (msgStart < raw.length && raw[msgStart] == ' ') msgStart++
        val message = if (msgStart < raw.length) raw.substring(msgStart) else ""

        return ParsedLine(tag, message, level, pid, isContinuation = false)
    }

    /** 日志头标记行，如 `--------- beginning of main`。 */
    private fun isBufferMarker(raw: String): Boolean =
        raw.startsWith("---") && raw.contains("beginning of")

    // ── 扫描辅助。返回新位置；返回 null 表示"没找到该跳过的内容" ──

    private fun skipSpaces(s: String, from: Int): Int? {
        if (from >= s.length) return null
        var i = from
        while (i < s.length && s[i] == ' ') i++
        // 至少要跳过一格。分隔符缺失说明列结构不对，交降级路径
        return if (i > from) i else null
    }

    private fun skipDigits(s: String, from: Int): Int? {
        if (from >= s.length) return null
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        return if (i > from) i else null
    }

    private companion object {
        /** `MM-dd HH:mm:ss.SSS` 的固定宽度。 */
        const val TIMESTAMP_WIDTH = 18

        const val LEVEL_CHARS = "VDIWEF"
    }
}

/**
 * 一行解析后的日志（Core 侧）。
 *
 * 与 app 侧 `LogcatLine` 的字段**刻意保持一致**，但少了 `tid` / `timestamp` /
 * `raw`——热路径用不到它们，省掉每行的 substring 分配。
 * 需要完整原始行的场景（推送 `raw` 输出）由调用方直接用原始字符串。
 */
data class ParsedLine(
    val tag: String,
    val message: String,
    val level: Char,
    val pid: Int,
    /** 是否为降级行（无 `threadtime` 前缀，通常是上一条日志的续行）。 */
    val isContinuation: Boolean,
)
