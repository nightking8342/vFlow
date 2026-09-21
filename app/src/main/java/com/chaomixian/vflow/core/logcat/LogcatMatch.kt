package com.chaomixian.vflow.core.logcat

/**
 * logcat 触发器的**匹配逻辑**。纯函数，无 Android 依赖，可单测。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §4.2（条件结构）、§8（冷却）。
 *
 * ## 为什么这一层必须在 app 模块
 *
 * 它真正的运行位置是 **Core 进程**（日志在那里过滤，未命中的行不跨进程）。
 * 但 `core/src/` 下**没有测试目录**（0 个测试文件），所以按文档 §10 的要求，
 * 匹配逻辑写成纯函数放在 app 模块（`core/logcat/`）——Core 那边**照抄同一份实现**。
 *
 * ⚠️ **两边必须保持一致**。若将来匹配规则要改，**必须同时改两处**，
 * 且这里是唯一有测试保护的一份，改动以它为准。
 *
 * ## 与调试工具的关系
 *
 * 调试工具（`LogcatFilter`）是「人肉筛选给人看」，本层是「给触发器判命中」。
 * 两者都做 TAG/级别的比较，但**语义不同，刻意不共用**：
 *
 * | | 调试工具 | 本层 |
 * |---|---|---|
 * | TAG 匹配 | 固定为**大小写不敏感子串** | 用户可选 eq/contains/regex |
 * | 级别 | 单选「至少这么严重」 | 每个触发器独立 |
 * | 隔离要求 | 宽（给人看，宁可多） | 严（判命中，**假阴性最坏**） |
 *
 * 共用的只有 [LogcatLine] / [LogLevel] 这对数据模型。
 */

/**
 * 单个字段的匹配方式。
 *
 * 与模块参数的 `*_filter_type` 一一对应（`any` / `equals` / `contains` / `regex`）。
 */
sealed interface LogcatMatcher {

    /**
     * 不做判断（模块参数里的 `any`）。
     *
     * ⚠️ **刻意不叫 `Any`**：在 `sealed interface` 内部，名为 `Any` 的嵌套对象会
     * **遮蔽 `kotlin.Any`**，导致同层里 `equals(other: Any?)` 的签名解析到这个
     * data object 上，编译报「equals overrides nothing」。
     * 换个名字就彻底避开这类咬人问题。
     *
     * `Unconstrained` 也比 `Any` 更贴切：它的语义是"对 TAG/消息不作约束"。
     */
    data object Unconstrained : LogcatMatcher

    /** 精确相等。 */
    data class Equals(val value: String) : LogcatMatcher

    /** 子串包含。 */
    data class Contains(val value: String) : LogcatMatcher

    /**
     * 正则。
     *
     * ⚠️ **[regex] 必须预编译**——`Regex(...)` 的构造开销远大于匹配，
     * 而这段代码在**每行日志**上执行（文档 §4.2）。
     * 编译发生在触发器条件变更时（用户改配置），不在匹配路径上。
     * 构造入口只有 [buildMatcher]，它保证了这一点。
     *
     * ## ⚠️ 为什么手写 equals / hashCode
     *
     * **`java.util.regex.Pattern` 没有按模式文本实现 `equals`**——
     * 实测 `Pattern.compile("a.*b").equals(Pattern.compile("a.*b"))` 返回 `false`。
     * 而 `kotlin.text.Regex` 是它的包装，只按引用比较。
     *
     * 若用 `data class` 的默认实现，两个**模式完全相同**的匹配器会被判为不等，
     * 后果是：
     * - 往返测试（encode→decode）永远失败
     * - 「条件是否变化」的判断恒为"变了"→ 每次同步都全量重发
     * - 去重逻辑失效（同一份条件在列表里重复堆积）
     *
     * 这些都是**静默的行为退化**，不会有异常提示，所以必须显式修掉。
     */
    class RegexMatcher(val regex: Regex) : LogcatMatcher {
        /** 模式原文。持有它是为了让相等性/哈希不依赖 [Regex] 自身的实现。 */
        val pattern: String = regex.pattern

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RegexMatcher) return false
            return pattern == other.pattern
        }

        override fun hashCode(): Int = pattern.hashCode()

        override fun toString(): String = "RegexMatcher($pattern)"
    }
}

/**
 * 构造匹配器。**唯一的入口**，保证 [LogcatMatcher.RegexMatcher] 里的正则是预编译的。
 *
 * @param type  `any` / `equals` / `contains` / `regex`
 * @param value 匹配值；`any` 时忽略
 * @return 匹配器；**`regex` 语法错误时返回 null**（由调用方决定如何提示）
 */
fun buildMatcher(type: String, value: String?): LogcatMatcher? = when (type) {
    LogcatFilterType.ANY -> LogcatMatcher.Unconstrained
    LogcatFilterType.EQUALS -> LogcatMatcher.Equals(value.orEmpty())
    LogcatFilterType.CONTAINS -> LogcatMatcher.Contains(value.orEmpty())
    LogcatFilterType.REGEX -> value
        ?.takeIf { it.isNotBlank() }
        ?.let { runCatching { LogcatMatcher.RegexMatcher(Regex(it)) }.getOrNull() }
    else -> null
}

/**
 * 检查正则是否可编译。
 *
 * 供模块的 `validate()` 使用——**语法错误必须在保存时就拒绝**，
 * 否则会静默存下一个永远不匹配的条件（真机场景 9）。
 */
fun isRegexValid(value: String?): Boolean =
    value.isNullOrBlank() || runCatching { Regex(value) }.isSuccess

/**
 * 匹配方式常量。
 *
 * 与模块参数的 `options` **必须逐字一致**——参数里存的是这些稳定常量，
 * 不是本地化文案（AGENTS.md 第 3 条）。
 */
object LogcatFilterType {
    const val ANY = "any"
    const val EQUALS = "equals"
    const val CONTAINS = "contains"
    const val REGEX = "regex"

    /** 供 `InputDefinition.options` 使用。 */
    val ALL = listOf(ANY, EQUALS, CONTAINS, REGEX)
}

/**
 * 一个 logcat 触发器的匹配条件。
 *
 * 对应文档 §4.2 的 `LogcatCondition`，但**去掉了预编译产物之外的东西**——
 * 这里可以直接用于匹配，不需要再转换。
 *
 * @param triggerId App 侧靠它定位 `TriggerSpec`（文档 §4.2 强调：必须带，
 *   否则 Core 推给 App 的消息无法路由）
 * @param minLevel  最低级别。**含 `F`**——`LogLevel` 的序里 `F` 在 `E` 之后，
 *   若不含 F 就无法表达「只要 Fatal」（文档 §4.1）
 */
data class LogcatTriggerCondition(
    val triggerId: String,
    val tagMatcher: LogcatMatcher,
    val messageMatcher: LogcatMatcher,
    val minLevel: LogLevel,
)

/**
 * 判断一个触发器条件是否命中某行。
 *
 * ## 降级行的处理 —— 本层的核心正确性点
 *
 * 降级行（无 TAG 前缀的续行）**继承了上一条的 tag/level/pid**
 * （见 [LogcatParser] 的降级语义），因此直接对它施加同样的判定即可，
 * **不需要任何特例**：
 *
 * - 母行的 tag 满足条件 → 续行的 tag 相同 → 也满足
 * - 母行的 tag 不满足 → 续行也不满足（不会误触发）
 *
 * 这正是文档 §5.1 那条修订（「降级行继承 tag/level，而不是置空/置 V」）的价值所在：
 * **若当年按初稿把降级行置 `V`，多行日志的 message 条件就永远匹配不到续行**
 * （真机场景 11 验的就是这个）。
 *
 * 注意业务上的一个推论：用户配 `message contains "xxx"` 而 xxx **只出现在续行**里时，
 * 母行不命中、续行命中——**结果是照常触发**（因为逐行判定）。
 */
fun LogcatTriggerCondition.matches(line: LogcatLine): Boolean {
    // 级别先比。它最便宜，且能挡掉大部分行
    if (line.level.priority < minLevel.priority) return false

    if (!matchField(line.tag, tagMatcher)) return false
    if (!matchField(line.message, messageMatcher)) return false

    return true
}

/**
 * 判断一行是否命中**任一**条件。
 *
 * @return 命中的条件列表。**可能多于一个**——文档 §3 明确：
 *   一行命中多个触发器时要逐个推送，各自触发各自的工作流。
 * @param levelMask 由 [buildLevelMask] 预先算好的位掩码；级别没过就直接返回
 */
fun matchAnyCondition(
    line: LogcatLine,
    conditions: List<LogcatTriggerCondition>,
    levelMask: Int,
): List<LogcatTriggerCondition> {
    // 短路：所有条件的最低价都高于本行级别时，整行连 TAG 都不用比
    if (levelMask and (1 shl line.level.priority) == 0) return emptyList()

    val hits = ArrayList<LogcatTriggerCondition>(1)
    for (c in conditions) {
        if (c.matches(line)) hits.add(c)
    }
    return hits
}

/**
 * 把 N 个条件的 `minLevel` 合成位掩码：**哪些级别可能被任一条件接受**。
 *
 * ⚠️ **必须把 `minLevel` 及其以上的每一位都置上，而不是只置 minLevel 那一位**。
 * `minLevel` 的语义是「**至少**这么严重」，所以 `minLevel=I` 放行的是
 * I / W / E / F 四个级别，不只是 I。
 *
 * 只置一位会造成**静默的假阴性**：配了 `minLevel=V`（想匹配一切）的触发器，
 * 掩码里只有 V 那一位，于是 INFO 行在短路处就被丢掉，
 * **触发器永远不触发，且没有任何提示**。这个错已实际写出来过一次，
 * 由 [LogcatMatchTest] 的多条件用例发现。
 *
 * 收益取决于**低位级别的占比**：若所有触发器都是 `minLevel=I`（默认值），
 * V/D 两级整行免扫描丢弃。基准实测（`LogcatTriggerBenchmarkTest` →
 * `app/build/reports/logcat-trigger-benchmark.txt`）：合成数据里 V/D 占 64%。
 *
 * 按 `LogLevel.priority` 置位（2..7）——⚠️ **不能按 `ordinal`**，
 * 枚举顺序与 priority 恰好一致只是巧合，往枚举里插入新级别就会让掩码错位。
 */
fun buildLevelMask(conditions: List<LogcatTriggerCondition>): Int {
    var mask = 0
    for (c in conditions) {
        // 从该级别一直置到最高级（F）
        for (p in c.minLevel.priority..LogLevel.FATAL.priority) {
            mask = mask or (1 shl p)
        }
    }
    return mask
}

/** 单个字段的匹配。 */
private fun matchField(value: String, matcher: LogcatMatcher): Boolean = when (matcher) {
    is LogcatMatcher.Unconstrained -> true
    is LogcatMatcher.Equals -> value.equals(matcher.value, ignoreCase = true)
    is LogcatMatcher.Contains -> value.contains(matcher.value, ignoreCase = true)
    is LogcatMatcher.RegexMatcher -> matcher.regex.containsMatchIn(value)
}
