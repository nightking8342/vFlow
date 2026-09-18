package com.chaomixian.vflow.server.logcat

/**
 * logcat 触发器的条件模型与匹配。**跑在 Core 进程的热路径上**。
 *
 * ## ⚠️ 这是 app 侧 `LogcatMatch.kt` 的移植版，两份必须保持一致
 *
 * | | app 侧 | Core 侧（本文件） |
 * |---|---|---|
 * | 测试 | 29 例（`LogcatMatchTest`） | **无**（`core/src` 没有测试目录） |
 * | 用途 | 条件构造、校验、编解码 | 流式匹配 |
 *
 * **任何语义改动必须同时改两处，且以 app 侧为准**（它有测试保护）。
 *
 * ## 匹配语义
 *
 * - TAG 与 message **AND 组合**；OR 由"配多个触发器"在架构层提供
 * - 比较**大小写不敏感**（用户配 "myapp" 不该漏掉 "MyApp"）
 * - regex 用 `containsMatchIn` 而非 `matches`（用户写 "error" 期望的是
 *   "消息里出现 error"，不是"整条消息恰好等于 error"）
 */

/** 单个字段的匹配方式。 */
sealed interface LogcatFieldMatcher {

    /**
     * 不做判断（模块参数里的 `any`）。
     *
     * ⚠️ **刻意不叫 `Any`**：在 `sealed interface` 内部，名为 `Any` 的嵌套对象会
     * **遮蔽 `kotlin.Any`**，让同层里 `equals(other: Any?)` 的签名解析错
     * （app 侧已因此踩过一次编译错误）。
     */
    data object Unconstrained : LogcatFieldMatcher

    data class Equals(val value: String) : LogcatFieldMatcher

    data class Contains(val value: String) : LogcatFieldMatcher

    /**
     * 正则。
     *
     * ⚠️ **必须预编译**：`Regex(...)` 的构造开销远大于匹配，
     * 而这段代码在每行日志上执行。编译发生在
     * [LogcatConditionCodec.decode] 里（条件变更时），不在匹配路径上。
     *
     * ⚠️ **手写 equals/hashCode**：`java.util.regex.Pattern` 没有按模式文本实现
     * `equals`（实测两个相同模式返回 false）。用 data class 的默认实现会让
     * 两个模式相同的匹配器判为不等，导致"条件是否变化"恒为真 → 全量重发。
     */
    class RegexMatcher(val regex: Regex) : LogcatFieldMatcher {
        val pattern: String = regex.pattern

        override fun equals(other: kotlin.Any?): Boolean {
            if (this === other) return true
            if (other !is RegexMatcher) return false
            return pattern == other.pattern
        }

        override fun hashCode(): Int = pattern.hashCode()

        override fun toString(): String = "RegexMatcher($pattern)"
    }
}

/**
 * 一个触发器的匹配条件。
 *
 * @param triggerId App 侧靠它定位 `TriggerSpec`——Core 推给 App 的消息里必须带，
 *   否则 App 无法路由（文档 §4.2）
 */
data class LogcatCondition(
    val triggerId: String,
    val tagMatcher: LogcatFieldMatcher,
    val messageMatcher: LogcatFieldMatcher,
    val minLevel: Char,
)

/**
 * 匹配入口。
 */
object LogcatMatcher {

    /**
     * 找出命中该行的**所有**条件。
     *
     * 可能多于一个——文档 §3 明确：一行命中多个触发器时逐个推送，
     * 各自触发各自的工作流。
     *
     * @param mask 级别位掩码，由条件列表算出。命中率低时它能挡掉大部分行。
     *   ⚠️ 掩码必须覆盖"minLevel **及以上**"的所有级别（`minLevel=V` 要放行全部），
     *   只置 minLevel 那一位会造成**静默的假阴性**——app 侧已踩过一次。
     */
    fun match(
        conditions: List<LogcatCondition>,
        line: ParsedLine,
        mask: Int = buildLevelMask(conditions),
    ): List<LogcatCondition> {
        if (mask and (1 shl priorityOf(line.level)) == 0) return emptyList()

        val hits = ArrayList<LogcatCondition>(1)
        for (c in conditions) {
            if (c.matches(line)) hits.add(c)
        }
        return hits
    }

    /**
     * 算出"哪些级别可能被任一条件接受"。
     *
     * ⚠️ **置的是区间而非单个位**：`minLevel` 的语义是"至少这么严重"，
     * 所以 `minLevel=I` 要放行 I/W/E/F 四级。
     */
    fun buildLevelMask(conditions: List<LogcatCondition>): Int {
        var mask = 0
        for (c in conditions) {
            for (p in priorityOf(c.minLevel)..FATAL_PRIORITY) {
                mask = mask or (1 shl p)
            }
        }
        return mask
    }

    private fun LogcatCondition.matches(line: ParsedLine): Boolean {
        if (priorityOf(line.level) < priorityOf(minLevel)) return false
        if (!matchField(line.tag, tagMatcher)) return false
        if (!matchField(line.message, messageMatcher)) return false
        return true
    }

    private fun matchField(value: String, matcher: LogcatFieldMatcher): Boolean = when (matcher) {
        is LogcatFieldMatcher.Unconstrained -> true
        is LogcatFieldMatcher.Equals -> value.equals(matcher.value, ignoreCase = true)
        is LogcatFieldMatcher.Contains -> value.contains(matcher.value, ignoreCase = true)
        is LogcatFieldMatcher.RegexMatcher -> matcher.regex.containsMatchIn(value)
    }

    /**
     * 级别字符 → 位序号。
     *
     * ⚠️ **必须与 app 侧 `LogLevel.priority` 一致**（V=2 D=3 I=4 W=5 E=6 F=7）。
     * 两边不一致时掩码会错位，表现为某些级别的行被静默丢掉。
     * 这里刻意写死数字而不是依赖枚举 ordinal——顺序是协议的一部分。
     */
    private fun priorityOf(level: Char): Int = when (level) {
        'V' -> 2
        'D' -> 3
        'I' -> 4
        'W' -> 5
        'E' -> 6
        'F' -> 7
        else -> 2
    }

}

/** 最高级别（Fatal）的位序号。 */
private const val FATAL_PRIORITY = 7
