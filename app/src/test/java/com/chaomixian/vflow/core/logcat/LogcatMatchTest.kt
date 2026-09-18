package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatTriggerCondition] / [matchAnyCondition] / [buildMatcher] 的回归测试。
 *
 * 这一层跑在 **Core 进程**里，判的是"要不要触发工作流"。它出错的表现是
 * **静默的假阴性**——用户配了条件、日志明明打了、工作流就是不跑，
 * 而且没有任何提示。所以这里的每条规则都要锁死。
 */
class LogcatMatchTest {

    private fun line(
        tag: String = "MyApp",
        message: String = "hello",
        level: LogLevel = LogLevel.INFO,
        continuation: Boolean = false,
    ) = LogcatLine(
        tag = tag,
        message = message,
        level = level,
        pid = 100,
        tid = 100,
        timestamp = "09-19 10:00:00.000",
        raw = "raw",
        isContinuation = continuation,
    )

    private fun cond(
        tag: LogcatMatcher = LogcatMatcher.Unconstrained,
        message: LogcatMatcher = LogcatMatcher.Unconstrained,
        minLevel: LogLevel = LogLevel.VERBOSE,
        id: String = "t1",
    ) = LogcatTriggerCondition(id, tag, message, minLevel)

    // ── 匹配方式 ─────────────────────────────────────────────────

    @Test
    fun `any matches everything`() {
        assertTrue(cond().matches(line()))
        assertTrue(cond().matches(line(tag = "", message = "")))
    }

    @Test
    fun `equals matches the whole tag`() {
        assertTrue(cond(tag = LogcatMatcher.Equals("MyApp")).matches(line(tag = "MyApp")))
        assertFalse(cond(tag = LogcatMatcher.Equals("MyApp")).matches(line(tag = "MyAppExtra")))
    }

    @Test
    fun `contains matches a substring`() {
        assertTrue(cond(tag = LogcatMatcher.Contains("App")).matches(line(tag = "MyApp")))
        assertFalse(cond(tag = LogcatMatcher.Contains("Zzz")).matches(line(tag = "MyApp")))
    }

    @Test
    fun `matching is case-insensitive`() {
        // 用户配 "myapp" 时不该因为大小写而漏掉 "MyApp"——
        // 这类失配用户完全看不出来
        assertTrue(cond(tag = LogcatMatcher.Equals("myapp")).matches(line(tag = "MyApp")))
        assertTrue(cond(tag = LogcatMatcher.Contains("APP")).matches(line(tag = "MyApp")))
        assertTrue(cond(message = LogcatMatcher.Contains("HELLO")).matches(line(message = "hello")))
    }

    @Test
    fun `regex matches anywhere in the message`() {
        // ⚠️ 用 containsMatchIn 而非 matches：用户写 "error" 时期望的是
        // "消息里出现 error"，不是"整条消息恰好等于 error"
        val c = cond(message = LogcatMatcher.RegexMatcher(Regex("err(or)?")))
        assertTrue(c.matches(line(message = "an error occurred")))
        assertTrue(c.matches(line(message = "error")))
        assertFalse(c.matches(line(message = "all good")))
    }

    @Test
    fun `regex is anchored when the user writes anchors`() {
        val anchored = cond(message = LogcatMatcher.RegexMatcher(Regex("^error$")))
        assertTrue(anchored.matches(line(message = "error")))
        assertFalse(anchored.matches(line(message = "an error")))
    }

    // ── 级别（含 F 的语义）──────────────────────────────────────

    @Test
    fun `filters out lines below the minimum level`() {
        val c = cond(minLevel = LogLevel.WARN)
        assertFalse(c.matches(line(level = LogLevel.INFO)))
        assertFalse(c.matches(line(level = LogLevel.DEBUG)))
        assertTrue(c.matches(line(level = LogLevel.WARN)))
        assertTrue(c.matches(line(level = LogLevel.ERROR)))
    }

    @Test
    fun `minLevel F admits only fatal`() {
        // ⚠️ 这是「min_level 含 F」的意义所在：若枚举里没有 F，
        // 用户就无法表达"只要 Fatal"——配 E 会把 F 也放行（E ≤ F）
        val c = cond(minLevel = LogLevel.FATAL)
        assertTrue(c.matches(line(level = LogLevel.FATAL)))
        assertFalse("E 不该通过 F 的门槛", c.matches(line(level = LogLevel.ERROR)))
    }

    @Test
    fun `minLevel V admits every level`() {
        val c = cond(minLevel = LogLevel.VERBOSE)
        LogLevel.entries.forEach { lvl ->
            assertTrue("$lvl 应通过 V 门槛", c.matches(line(level = lvl)))
        }
    }

    // ── 组合语义：AND ─────────────────────────────────────────────

    @Test
    fun `tag and message are combined with AND`() {
        // 文档 §4.1：两者独立条件，AND 组合（OR 由"配多个触发器"在架构层提供）
        val c = cond(
            tag = LogcatMatcher.Equals("MyApp"),
            message = LogcatMatcher.Contains("error"),
        )
        assertTrue(c.matches(line(tag = "MyApp", message = "an error")))
        assertFalse("只有 tag 满足不该命中", c.matches(line(tag = "MyApp", message = "all good")))
        assertFalse("只有 message 满足不该命中", c.matches(line(tag = "Other", message = "an error")))
    }

    @Test
    fun `all three conditions must hold together`() {
        val c = cond(
            tag = LogcatMatcher.Equals("MyApp"),
            message = LogcatMatcher.Contains("error"),
            minLevel = LogLevel.ERROR,
        )
        assertTrue(c.matches(line(tag = "MyApp", message = "error", level = LogLevel.ERROR)))
        assertFalse(c.matches(line(tag = "MyApp", message = "error", level = LogLevel.INFO)))
        assertFalse(c.matches(line(tag = "MyApp", message = "ok", level = LogLevel.ERROR)))
        assertFalse(c.matches(line(tag = "X", message = "error", level = LogLevel.ERROR)))
    }

    // ── 降级行 ★ 本层最核心的正确性点 ────────────────────────────

    @Test
    fun `a continuation line matches on its inherited tag`() {
        // ⚠️ 降级行继承了母行的 tag/level，所以**逐行判定时天然一致**。
        // 这是 §5.1 那条修订（继承而非置空）的价值所在：
        // 若按初稿把降级行 tag 置空，配了 tag 条件的触发器就看不到续行
        val c = cond(tag = LogcatMatcher.Equals("MyApp"))
        assertTrue(c.matches(line(tag = "MyApp", continuation = true)))
    }

    @Test
    fun `a continuation line matches on its inherited level`() {
        // 若按初稿把降级行 level 置 V，这一行会被 min_level 静默过滤掉 ——
        // 表现是"堆栈的后续行全部消失"，而用户完全不知道
        val c = cond(minLevel = LogLevel.ERROR)
        assertTrue(c.matches(line(level = LogLevel.ERROR, continuation = true)))
    }

    @Test
    fun `a message condition can match only the continuation line`() {
        // 真机场景 11 的核心：关键字只出现在续行里时，
        // 母行不命中、续行命中 —— 结果是照常触发（因为逐行判定）
        val c = cond(message = LogcatMatcher.Contains("NullPointerException"))

        val parent = line(message = "FATAL EXCEPTION: main")
        val continuation = line(message = "java.lang.NullPointerException: ...", continuation = true)

        assertFalse("母行不该命中", c.matches(parent))
        assertTrue("续行应当命中", c.matches(continuation))
    }

    // ── 多条件与位掩码 ──────────────────────────────────────────

    @Test
    fun `every matching condition is reported not just the first`() {
        // 文档 §3：一行命中多个触发器时，各自触发各自的工作流
        val conditions = listOf(
            cond(id = "t1", tag = LogcatMatcher.Contains("App")),
            cond(id = "t2", message = LogcatMatcher.Contains("hello")),
            cond(id = "t3", tag = LogcatMatcher.Equals("Nope")),
        )
        val hits = matchAnyCondition(line(), conditions, buildLevelMask(conditions))
        assertEquals(listOf("t1", "t2"), hits.map { it.triggerId })
    }

    @Test
    fun `the level mask short-circuits the whole scan`() {
        // 所有条件的门槛都高于本行级别 → 整行免扫描
        val conditions = listOf(cond(id = "t1", minLevel = LogLevel.ERROR))
        val mask = buildLevelMask(conditions)

        assertTrue(matchAnyCondition(line(level = LogLevel.INFO), conditions, mask).isEmpty())
        assertEquals(1, matchAnyCondition(line(level = LogLevel.ERROR), conditions, mask).size)
    }

    @Test
    fun `the level mask covers a range not a single bit`() {
        // ⚠️ 这是本层最容易写错、后果最严重的一点。
        // minLevel 的语义是「**至少**这么严重」，所以 minLevel=E 必须放行
        // E 与 F 两级。只置 E 那一位的话，Fatal 行会在短路处被丢掉 ——
        // 而那正是用户最想知道的日志。
        val mask = buildLevelMask(listOf(cond(minLevel = LogLevel.ERROR)))

        assertTrue("E 自身要放行", mask and (1 shl LogLevel.ERROR.priority) != 0)
        assertTrue("F 比 E 更严重，也必须放行", mask and (1 shl LogLevel.FATAL.priority) != 0)
        assertTrue("I 低于门槛，应被挡", mask and (1 shl LogLevel.INFO.priority) == 0)
    }

    @Test
    fun `a verbose threshold admits every level through the mask`() {
        // ⚠️ 曾经的 bug：minLevel=V 只置了 V 那一位，
        // 结果 INFO 行在短路处就被丢掉 —— 触发器**永远不触发**，
        // 而配置看起来完全正常。这条测试锁定修复后的行为。
        val mask = buildLevelMask(listOf(cond(minLevel = LogLevel.VERBOSE)))

        LogLevel.entries.forEach { lvl ->
            assertTrue("$lvl 应通过 V 门槛的掩码", mask and (1 shl lvl.priority) != 0)
        }
        assertEquals(1, matchAnyCondition(line(level = LogLevel.INFO), listOf(cond()), mask).size)
    }

    @Test
    fun `the level mask unions all conditions`() {
        // 掩码取的是**并集**：低门槛的触发器必须让它关心的行通过
        val mask = buildLevelMask(
            listOf(cond(id = "t1", minLevel = LogLevel.ERROR), cond(id = "t2", minLevel = LogLevel.DEBUG))
        )
        assertTrue(mask and (1 shl LogLevel.DEBUG.priority) != 0)
        assertTrue(mask and (1 shl LogLevel.INFO.priority) != 0)
        assertTrue(mask and (1 shl LogLevel.ERROR.priority) != 0)
        assertTrue(mask and (1 shl LogLevel.FATAL.priority) != 0)
        assertTrue("V 比所有门槛都低，应被挡", mask and (1 shl LogLevel.VERBOSE.priority) == 0)
    }

    @Test
    fun `the mask never blocks a level the conditions would accept`() {
        // 不变式：掩码只能是"过度放行"，绝不能"误挡"。
        // 逐级别两两对照 —— 凡是条件本身会命中的，掩码必须放行。
        val levels = listOf(LogLevel.VERBOSE, LogLevel.DEBUG, LogLevel.INFO, LogLevel.WARN, LogLevel.ERROR, LogLevel.FATAL)
        for (threshold in levels) {
            val c = cond(minLevel = threshold)
            val mask = buildLevelMask(listOf(c))
            for (lvl in levels) {
                val direct = c.matches(line(level = lvl))
                val viaMask = mask and (1 shl lvl.priority) != 0
                assertTrue(
                    "门槛 $threshold 下 $lvl：条件判定=$direct 但掩码放行=$viaMask —— 掩码误挡了",
                    !direct || viaMask
                )
            }
        }
    }

    @Test
    fun `an empty mask from no conditions blocks everything`() {
        assertEquals(0, buildLevelMask(emptyList()))
    }

    @Test
    fun `an empty condition list matches nothing`() {
        // 没有触发器时不该有任何命中（Core 那边也是据此停掉 logcat 进程）
        assertTrue(matchAnyCondition(line(), emptyList(), 0).isEmpty())
    }

    @Test
    fun `a mask of zero blocks everything`() {
        // 空列表算出的掩码是 0 → 整行短路。这是正确行为：
        // 没有触发器就不该有任何匹配
        val conditions = listOf(cond(id = "t1"))
        assertTrue(matchAnyCondition(line(), conditions, 0).isEmpty())
    }

    // ── 匹配器构造与校验 ────────────────────────────────────────

    @Test
    fun `builds each matcher type`() {
        assertTrue(buildMatcher(LogcatFilterType.ANY, null) is LogcatMatcher.Unconstrained)
        assertEquals(LogcatMatcher.Equals("x"), buildMatcher(LogcatFilterType.EQUALS, "x"))
        assertEquals(LogcatMatcher.Contains("x"), buildMatcher(LogcatFilterType.CONTAINS, "x"))
        assertTrue(buildMatcher(LogcatFilterType.REGEX, "a.*b") is LogcatMatcher.RegexMatcher)
    }

    @Test
    fun `regex matcher precompiles the pattern`() {
        // ⚠️ 预编译是硬要求（文档 §4.2）：这段代码在每行日志上执行，
        // 现编正则会成为最贵的一环
        val m = buildMatcher(LogcatFilterType.REGEX, "ab+c")
        assertTrue(m is LogcatMatcher.RegexMatcher)
        assertEquals("ab+c", (m as LogcatMatcher.RegexMatcher).regex.pattern)
    }

    @Test
    fun `an invalid regex yields null instead of throwing`() {
        // 构造失败必须能表达出来，让调用方决定提示 —— 不能抛异常打断整条链路
        assertNull(buildMatcher(LogcatFilterType.REGEX, "[unclosed"))
        assertNull(buildMatcher(LogcatFilterType.REGEX, ""))
        assertNull(buildMatcher(LogcatFilterType.REGEX, "   "))
        assertNull(buildMatcher("unknown_type", "x"))
    }

    @Test
    fun `regex validity check rejects broken patterns`() {
        // 供模块 validate() 使用：语法错误要在**保存时**就拒绝（真机场景 9）
        assertTrue(isRegexValid("a.*b"))
        assertTrue(isRegexValid(null))
        assertTrue(isRegexValid(""))
        assertFalse(isRegexValid("[unclosed"))
        assertFalse(isRegexValid("(a"))
    }

    @Test
    fun `filter type constants are the stable option values`() {
        // 这些字面量会存进工作流 JSON，**改了就破坏兼容**
        assertEquals(listOf("any", "equals", "contains", "regex"), LogcatFilterType.ALL)
    }

    @Test
    fun `regex pattern survives the round trip through the matcher`() {
        val original = Regex("^WeChat: (fail|error)", RegexOption.IGNORE_CASE)
        val m = LogcatMatcher.RegexMatcher(original)
        assertNotNull(m.regex.pattern)
        assertEquals(original.pattern, m.regex.pattern)
    }
}
