package com.chaomixian.vflow.server.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core 侧匹配逻辑的回归测试。
 *
 * 这是触发器的**判定点**：它说不命中，工作流就不会跑，且没有任何提示。
 * 因此这里锁的是「什么情况下必须命中」。
 *
 * 用例与 app 侧 `LogcatMatchTest` **刻意重复**——两份实现各自演化时，
 * 只有各自的测试能挡住各自的退化。
 */
class LogcatMatcherTest {

    private fun line(
        tag: String = "MyApp",
        message: String = "hello",
        level: Char = 'I',
        continuation: Boolean = false,
    ) = ParsedLine(tag, message, level, 100, continuation)

    private fun cond(
        tag: LogcatFieldMatcher = LogcatFieldMatcher.Unconstrained,
        message: LogcatFieldMatcher = LogcatFieldMatcher.Unconstrained,
        minLevel: Char = 'V',
        id: String = "t1",
    ) = LogcatCondition(id, tag, message, minLevel)

    private fun hits(conditions: List<LogcatCondition>, l: ParsedLine) =
        LogcatMatcher.match(conditions, l)

    // ── 基本匹配 ────────────────────────────────────────────────

    @Test
    fun `unconstrained matches everything`() {
        assertEquals(1, hits(listOf(cond()), line()).size)
        assertEquals(1, hits(listOf(cond()), line(tag = "", message = "")).size)
    }

    @Test
    fun `equals requires the whole tag`() {
        assertEquals(1, hits(listOf(cond(tag = LogcatFieldMatcher.Equals("MyApp"))), line(tag = "MyApp")).size)
        assertTrue(hits(listOf(cond(tag = LogcatFieldMatcher.Equals("MyApp"))), line(tag = "MyAppExtra")).isEmpty())
    }

    @Test
    fun `contains matches a substring`() {
        assertEquals(1, hits(listOf(cond(tag = LogcatFieldMatcher.Contains("App"))), line(tag = "MyApp")).size)
    }

    @Test
    fun `matching is case insensitive`() {
        // 用户配 "myapp" 不该漏掉 "MyApp"
        assertEquals(1, hits(listOf(cond(tag = LogcatFieldMatcher.Equals("myapp"))), line(tag = "MyApp")).size)
        assertEquals(1, hits(listOf(cond(message = LogcatFieldMatcher.Contains("HELLO"))), line(message = "hello")).size)
    }

    @Test
    fun `regex matches anywhere in the message`() {
        val c = cond(message = LogcatFieldMatcher.RegexMatcher(Regex("err(or)?")))
        assertEquals(1, hits(listOf(c), line(message = "an error occurred")).size)
        assertTrue(hits(listOf(c), line(message = "all good")).isEmpty())
    }

    @Test
    fun `regex honors explicit anchors`() {
        val anchored = cond(message = LogcatFieldMatcher.RegexMatcher(Regex("^error$")))
        assertEquals(1, hits(listOf(anchored), line(message = "error")).size)
        assertTrue(hits(listOf(anchored), line(message = "an error")).isEmpty())
    }

    // ── 级别 ────────────────────────────────────────────────────

    @Test
    fun `filters out lower levels`() {
        val c = cond(minLevel = 'W')
        assertTrue(hits(listOf(c), line(level = 'I')).isEmpty())
        assertTrue(hits(listOf(c), line(level = 'D')).isEmpty())
        assertEquals(1, hits(listOf(c), line(level = 'W')).size)
        assertEquals(1, hits(listOf(c), line(level = 'E')).size)
        assertEquals(1, hits(listOf(c), line(level = 'F')).size)
    }

    @Test
    fun `minLevel F admits only fatal`() {
        val c = cond(minLevel = 'F')
        assertEquals(1, hits(listOf(c), line(level = 'F')).size)
        assertTrue("E 不该通过 F 的门槛", hits(listOf(c), line(level = 'E')).isEmpty())
    }

    @Test
    fun `minLevel V admits every level`() {
        val c = cond(minLevel = 'V')
        for (level in "VDIWEF") {
            assertEquals("级别 $level 应通过 V 门槛", 1, hits(listOf(c), line(level = level)).size)
        }
    }

    // ── 位掩码 ★ 最容易写错的地方 ────────────────────────────────

    @Test
    fun `the mask covers a range not a single bit`() {
        // ⚠️ minLevel 的语义是「至少这么严重」，minLevel=E 必须放行 E 与 F。
        // 只置 E 那一位的话，Fatal 行会在短路处被丢掉 ——
        // 而那正是用户最想知道的日志。app 侧已因此踩过一次。
        val mask = LogcatMatcher.buildLevelMask(listOf(cond(minLevel = 'E')))

        assertTrue("E 自身", mask and (1 shl 6) != 0)
        assertTrue("F 更严重，也必须放行", mask and (1 shl 7) != 0)
        assertTrue("I 低于门槛，应被挡", mask and (1 shl 4) == 0)
    }

    @Test
    fun `a verbose threshold admits everything through the mask`() {
        // ⚠️ 曾经的 bug：minLevel=V 只置了 V 那一位，INFO 行在短路处被丢掉 ——
        // 触发器**永远不触发**，而配置看起来完全正常
        val conditions = listOf(cond(minLevel = 'V'))
        val mask = LogcatMatcher.buildLevelMask(conditions)

        for (level in "VDIWEF") {
            assertEquals("$level 应通过 V 门槛的掩码", 1, LogcatMatcher.match(conditions, line(level = level), mask).size)
        }
    }

    @Test
    fun `the mask never blocks a level the conditions would accept`() {
        // 不变式：掩码只能"过度放行"，绝不能"误挡"。
        // 逐级别两两对照 —— 凡是条件本身会命中的，掩码必须放行
        for (threshold in "VDIWEF") {
            val c = cond(minLevel = threshold)
            val mask = LogcatMatcher.buildLevelMask(listOf(c))
            for (level in "VDIWEF") {
                val direct = c.matchesForTest(line(level = level))
                val viaMask = mask and (1 shl priority(level)) != 0
                assertTrue(
                    "门槛 $threshold 下 $level：条件判定=$direct 掩码放行=$viaMask —— 掩码误挡了",
                    !direct || viaMask
                )
            }
        }
    }

    @Test
    fun `an empty mask blocks everything`() {
        assertEquals(0, LogcatMatcher.buildLevelMask(emptyList()))
        assertTrue(hits(emptyList(), line()).isEmpty())
    }

    // ── 组合与多命中 ────────────────────────────────────────────

    @Test
    fun `tag and message are combined with AND`() {
        val c = cond(tag = LogcatFieldMatcher.Equals("MyApp"), message = LogcatFieldMatcher.Contains("error"))
        assertEquals(1, hits(listOf(c), line(tag = "MyApp", message = "an error")).size)
        assertTrue("只有 tag 满足不该命中", hits(listOf(c), line(tag = "MyApp", message = "ok")).isEmpty())
        assertTrue("只有 message 满足不该命中", hits(listOf(c), line(tag = "Other", message = "an error")).isEmpty())
    }

    @Test
    fun `all matching conditions are reported`() {
        // 一行命中多个触发器时，各自触发各自的工作流（文档 §3）
        val conditions = listOf(
            cond(id = "t1", tag = LogcatFieldMatcher.Contains("App")),
            cond(id = "t2", message = LogcatFieldMatcher.Contains("hello")),
            cond(id = "t3", tag = LogcatFieldMatcher.Equals("Nope")),
        )
        assertEquals(listOf("t1", "t2"), hits(conditions, line()).map { it.triggerId })
    }

    // ── 降级行 ──────────────────────────────────────────────────

    @Test
    fun `a continuation line is matched on its inherited fields`() {
        // 降级行继承了母行的 tag/level/pid（解析器保证），
        // 因此这里不需要任何特例：直接按同样规则判即可
        assertEquals(1, hits(listOf(cond(tag = LogcatFieldMatcher.Equals("MyApp"))), line(continuation = true)).size)
        assertEquals(1, hits(listOf(cond(minLevel = 'E')), line(level = 'E', continuation = true)).size)
    }

    // ── 匹配器的相等性 ──────────────────────────────────────────

    @Test
    fun `regex matchers with the same pattern are equal`() {
        // ⚠️ java.util.regex.Pattern 没有按模式文本实现 equals。
        // 若不手写，两个相同模式的匹配器会判为不等，
        // 导致"条件是否变化"恒为真 → 每次同步都全量重发
        assertEquals(
            LogcatFieldMatcher.RegexMatcher(Regex("a.*b")),
            LogcatFieldMatcher.RegexMatcher(Regex("a.*b"))
        )
        assertEquals(
            LogcatFieldMatcher.RegexMatcher(Regex("a.*b")).hashCode(),
            LogcatFieldMatcher.RegexMatcher(Regex("a.*b")).hashCode()
        )
        assertFalse(
            LogcatFieldMatcher.RegexMatcher(Regex("a.*b")) ==
                LogcatFieldMatcher.RegexMatcher(Regex("a.*c"))
        )
    }

    // 供不变式测试直接调用私有扩展
    private fun LogcatCondition.matchesForTest(l: ParsedLine): Boolean =
        LogcatMatcher.match(listOf(this), l).isNotEmpty()

    private fun priority(level: Char): Int = when (level) {
        'V' -> 2; 'D' -> 3; 'I' -> 4; 'W' -> 5; 'E' -> 6; 'F' -> 7; else -> 2
    }
}
