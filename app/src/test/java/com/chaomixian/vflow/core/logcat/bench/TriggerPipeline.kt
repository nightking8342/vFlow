package com.chaomixian.vflow.core.logcat.bench

/**
 * 把 logcat 触发器的匹配逻辑**从设计文档抄成可运行的代码**，供基准测试使用。
 *
 * ## 为什么要在测试里重写一遍
 *
 * 触发器还没实现（`LogcatCondition` / `LogcatStreamWrapper` 都还不存在）。
 * 但「要不要把过滤下推到命令行」这个决策**现在就要定**，
 * 而它取决于匹配开销的量级——所以先把这部分逻辑按设计文档原样写出来测。
 *
 * ⚠️ **这是草稿，不是实现**。真正的实现应放在 Core 侧（`core/src/main`）。
 * 目录名字里带 `bench` 就是为了让它不会被误当成生产代码。
 *
 * 一旦触发器落地，**这份草稿应当删掉，benchmark 改为直接调真实实现**——
 * 否则两份并行演化，测出来的数字会与真实行为脱节。
 *
 * ## 与设计文档的对应
 *
 * | 本文件 | 文档 |
 * |---|---|
 * | [Matcher] / [Condition] | §4.2 `LogcatCondition`（含**预编译 Regex** 的要求） |
 * | [matchLevelMask] | 优化的第 2 条（级别位掩码短路），文档未载 |
 * | [ParserVariant] | 优化的第 3 条（索引扫描替代正则），文档未载 |
 */
internal object TriggerPipeline {

    // ── 条件结构（照 §4.2）────────────────────────────────────────

    sealed interface Matcher {
        /** 不做任何判断。用 object 免去每行比较时的字段读取（§4.2）。 */
        data object Any : Matcher
        data class Equals(val value: String) : Matcher
        data class Contains(val value: String) : Matcher
        /** ⚠️ **预编译**（§4.2）：绝不可在 matches() 里现编。 */
        data class RegexMatcher(val pattern: kotlin.text.Regex) : Matcher
    }

    data class Condition(
        val triggerId: String,
        val tagMatcher: Matcher,
        val messageMatcher: Matcher,
        val minLevel: Char,
    )

    // ── 级别位掩码（优化的第 2 条）───────────────────────────────

    /**
     * 把 N 个条件的 minLevel 合成一个位掩码，每行先查一位。
     *
     * 收益**取决于低位级别占比**：若所有条件都是 `minLevel=I`，
     * 则 V 与 D 两级整行免扫描丢弃。合成数据的 V/D 约占 70%，
     * 因此这条在真实场景里很值得做。基准会把这个收益量出来。
     */
    fun levelMask(conditions: List<Condition>): Int =
        conditions.fold(0) { acc, c -> acc or (1 shl (c.minLevel - 'A')) }

    // ── 匹配 ────────────────────────────────────────────────────

    /**
     * 一行是否命中任一条件。命中即返回（不继续扫剩下的条件）——
     * 这与文档 §3「一行命中多个触发器就逐个推送」略有出入：
     * 基准只测**单位置命中/不命中**的成本，多命中的成本是它的整数倍。
     */
    fun matches(
        tag: CharSequence,
        tagStart: Int,
        tagEnd: Int,
        message: CharSequence,
        level: Char,
        conditions: List<Condition>,
        mask: Int,
    ): Boolean {
        // 短路：级别没过，整行连 TAG 都不用看
        if (mask and (1 shl (level - 'A')) == 0) return false

        for (c in conditions) {
            if (level < c.minLevel) continue
            if (!matchTag(tag, tagStart, tagEnd, c.tagMatcher)) continue
            if (!matchMessage(message, c.messageMatcher)) continue
            return true
        }
        return false
    }

    /**
     * TAG 匹配。
     *
     * ⚠️ 关键性能点：**在 [tagStart, tagEnd) 区间上直接比，不先 substring**。
     * `contains` / `equals` 都能直接在 `CharSequence` 区间上做
     * （Kotlin 的 `CharSequence.contains(other, ignoreCase)` 与
     * `String.regionMatches`），省掉每行一次 String 分配。
     */
    private fun matchTag(
        tag: CharSequence,
        start: Int,
        end: Int,
        matcher: Matcher,
    ): Boolean = when (matcher) {
        is Matcher.Any -> true
        is Matcher.Equals -> regionEquals(tag, start, end, matcher.value)
        is Matcher.Contains -> regionContains(tag, start, end, matcher.value)
        is Matcher.RegexMatcher -> matcher.pattern.matches(tag.subSequence(start, end))
    }

    private fun matchMessage(message: CharSequence, matcher: Matcher): Boolean = when (matcher) {
        is Matcher.Any -> true
        is Matcher.Equals -> message.toString() == matcher.value
        is Matcher.Contains -> message.contains(matcher.value, ignoreCase = false)
        is Matcher.RegexMatcher -> matcher.pattern.containsMatchIn(message)
    }

    /** 在 `[start, end)` 区间上做大小写不敏感的整串比较，不分配中间 String。 */
    private fun regionEquals(s: CharSequence, start: Int, end: Int, target: String): Boolean {
        if (end - start != target.length) return false
        for (i in target.indices) {
            if (!s[start + i].equals(target[i], ignoreCase = true)) return false
        }
        return true
    }

    /** 在 `[start, end)` 区间上做子串查找，不分配中间 String。 */
    private fun regionContains(s: CharSequence, start: Int, end: Int, target: String): Boolean {
        if (target.isEmpty()) return true
        val limit = end - target.length
        outer@ for (i in start..limit) {
            for (j in target.indices) {
                if (!s[i + j].equals(target[j], ignoreCase = true)) continue@outer
            }
            return true
        }
        return false
    }

    // ── 解析变体（优化的第 3 条）─────────────────────────────────

    /** 解析器的两种实现，基准要对比它们。 */
    enum class ParserVariant {
        /** 现行实现：`LogcatParser` 的整行正则 + 组捕获。 */
        REGEX,

        /**
         * 候选优化：**索引扫描**。
         *
         * 依据是 `threadtime` 的**时间戳前缀定宽**（`MM-dd HH:mm:ss.SSS` = 18 字符），
         * 因此整个前缀可以直接按偏移跳过，无需正则、无需捕获组。
         *
         * 对**不命中的行**（绝大多数），只需要 level 与 tag 的位置，
         * 连 String 都不用构造 → **零分配**。
         */
        INDEXED,
    }

    /** 解析结果：tag 在原始行里的区间 + 级别 + message 起点。 */
    class Parsed(
        /** 原始行（索引扫描下就是原对象，无拷贝） */
        @JvmField val raw: CharSequence,
        @JvmField val tagStart: Int,
        @JvmField val tagEnd: Int,
        @JvmField val level: Char,
        @JvmField val messageStart: Int,
        /** 是否走了降级路径（无前缀的续行） */
        @JvmField val isContinuation: Boolean,
    ) {
        /** message 视图。命中后才需要，避免非命中路径的分配。 */
        fun message(): CharSequence = raw.subSequence(messageStart, raw.length)
    }

    /**
     * 索引扫描解析。
     *
     * ⚠️ **刻意不解析时间戳**：冷却用的是 App 侧的 `System.currentTimeMillis()`，
     * 不是日志自带的时间。既然前缀定宽，跳过即可——省掉两个捕获组。
     *
     * 返回 null 表示"不是日志行"（标记行 / 空行 / 格式意外），
     * 由调用方决定如何处理。
     */
    fun parseIndexed(raw: String): Parsed? {
        if (raw.length < 34) return null

        // ---- 1. 校验定宽前缀的形状，而不是盲信偏移 ----
        // 不校验的话，遇到格式不同的行会解析出垃圾 tag 并**静默匹配失败**
        if (raw[2] != '-' || raw[5] != ' ' || raw[8] != ':' || raw[11] != ':' || raw[14] != '.') return null

        var p = 18                       // 时间戳 = MM-dd HH:mm:ss.SSS
        if (raw[p] != ' ') return null
        p = skipSpaces(raw, p)
        p = skipDigits(raw, p)           // pid
        if (p < 0) return null
        p = skipSpaces(raw, p)
        p = skipDigits(raw, p)           // tid
        if (p < 0) return null
        p = skipSpaces(raw, p)

        if (p >= raw.length) return null
        val level = raw[p]
        if (level !in "VDIWEF") return null

        p = skipSpaces(raw, p + 1)
        val tagStart = p

        // TAG 到 " : " 结束。用 indexOf 找冒号，再回退掉 tag 后的空格。
        val colon = raw.indexOf(':', tagStart)
        if (colon < 0) return null
        var tagEnd = colon
        while (tagEnd > tagStart && raw[tagEnd - 1] == ' ') tagEnd--
        if (tagEnd <= tagStart) return null

        var msgStart = colon + 1
        if (msgStart < raw.length && raw[msgStart] == ' ') msgStart++

        return Parsed(raw, tagStart, tagEnd, level, msgStart, isContinuation = false)
    }

    private fun skipSpaces(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] == ' ') i++
        return i
    }

    /** 返回 digits 之后的偏移；一个数字都没有则返回 -1。 */
    private fun skipDigits(s: String, from: Int): Int {
        var i = from
        while (i < s.length && s[i] in '0'..'9') i++
        return if (i == from) -1 else i
    }
}
