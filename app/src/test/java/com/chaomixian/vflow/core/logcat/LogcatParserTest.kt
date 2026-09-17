package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatParser] 的回归测试。
 *
 * 重点是**锁死降级语义**——它是真机实测踩出来的坑，
 * 且是"改错了也不报错、只是行为悄悄变差"的那类问题。
 *
 * 相关文档：`docs/fork/logcat-debug-tool.md` §4.4、
 * `docs/fork/logcat-trigger-design.md` §5.1。
 */
class LogcatParserTest {

    // ── 正常解析 ─────────────────────────────────────────────────

    @Test
    fun `parses a standard threadtime line`() {
        val result = LogcatParser.parseLine(
            "09-16 21:04:13.040  9278 15081 I WeChat: 具体消息",
            prev = null
        )

        val line = (result as LogcatParseResult.Line).line
        assertEquals("WeChat", line.tag)
        assertEquals("具体消息", line.message)
        assertEquals(LogLevel.INFO, line.level)
        assertEquals(9278, line.pid)
        assertEquals(15081, line.tid)
        assertEquals("09-16 21:04:13.040", line.timestamp)
        assertFalse(line.isContinuation)
    }

    @Test
    fun `parses every level character including F and E`() {
        // F 必须可解析 —— 首版设计文档的枚举漏了 F 而正则含 F，两端不一致。
        // 本测试锁死「正则与 LogLevel 枚举都含 F」。
        val cases = mapOf(
            'V' to LogLevel.VERBOSE,
            'D' to LogLevel.DEBUG,
            'I' to LogLevel.INFO,
            'W' to LogLevel.WARN,
            'E' to LogLevel.ERROR,
            'F' to LogLevel.FATAL,
        )
        for ((c, expected) in cases) {
            val result = LogcatParser.parseLine("09-16 21:04:13.040  1 1 $c Tag: msg", prev = null)
            val line = (result as LogcatParseResult.Line).line
            assertEquals("level char $c should map to $expected", expected, line.level)
        }
    }

    @Test
    fun `parses a line with empty message`() {
        val result = LogcatParser.parseLine("09-16 21:04:13.040  1 1 I Tag:", prev = null)
        val line = (result as LogcatParseResult.Line).line
        assertEquals("", line.message)
        assertEquals("Tag", line.tag)
    }

    @Test
    fun `accepts colon without trailing space`() {
        val result = LogcatParser.parseLine("09-16 21:04:13.040  1 1 I Tag:msg", prev = null)
        val line = (result as LogcatParseResult.Line).line
        assertEquals("msg", line.message)
    }

    @Test
    fun `keeps colons inside the message intact`() {
        // message 里含冒号很常见（URL、时间戳），不能把第一个冒号之后的都丢掉
        val result = LogcatParser.parseLine(
            "09-16 21:04:13.040  1 1 I Tag: url=https://a.com:8080/x",
            prev = null
        )
        val line = (result as LogcatParseResult.Line).line
        assertEquals("Tag", line.tag)
        assertEquals("url=https://a.com:8080/x", line.message)
    }

    @Test
    fun `parses multiple spaces between pid and tid`() {
        // threadtime 用右对齐，pid/tid 位数不同时空格数会变
        val result = LogcatParser.parseLine("09-16 21:04:13.040  9278 15081 D T: m", prev = null)
        val line = (result as LogcatParseResult.Line).line
        assertEquals(9278, line.pid)
        assertEquals(15081, line.tid)
    }

    // ── 降级语义（本文件最重要的部分）───────────────────────────

    @Test
    fun `continuation line inherits tag and level from previous line`() {
        // 这是修订后的正确语义：无前缀行是上一条的续行，继承 tag/level。
        // 旧设计「level 置 V、tag 置空」会让 min_level（默认 I）把它过滤掉，
        // 等于"不丢行"实际丢行。
        val prev = (LogcatParser.parseLine(
            "09-17 10:00:01.100  9278 15081 E MyApp: 上报失败:",
            prev = null
        ) as LogcatParseResult.Line).line

        val cont = (LogcatParser.parseLine("url=https://x.com/api", prev) as LogcatParseResult.Line).line

        assertEquals("MyApp", cont.tag)
        assertEquals(LogLevel.ERROR, cont.level)   // ← 继承 E，不是 V
        assertEquals("url=https://x.com/api", cont.message)
        assertEquals(9278, cont.pid)
        assertTrue(cont.isContinuation)
    }

    @Test
    fun `continuation line passes level filter when previous line does`() {
        // 直接锁定"修订语义"的实际收益：降级行不再被 min_level 无谓过滤
        val prev = (LogcatParser.parseLine(
            "09-17 10:00:01.100  9278 15081 E MyApp: 上报失败:",
            prev = null
        ) as LogcatParseResult.Line).line
        val cont = (LogcatParser.parseLine("code=500", prev) as LogcatParseResult.Line).line

        assertTrue("降级行应能通过 min_level=I", cont.passesLevel(LogLevel.INFO))
        assertTrue("降级行应能通过 min_level=E（继承自前一行）", cont.passesLevel(LogLevel.ERROR))
    }

    @Test
    fun `continuation line without previous line falls back to defaults`() {
        // 边界：流重启后第一条就是降级行，没有可继承的对象。
        // 此时降级为默认级别（V），不得崩溃。
        val cont = (LogcatParser.parseLine("orphan continuation", prev = null) as LogcatParseResult.Line).line

        assertEquals("", cont.tag)
        assertEquals(LogLevel.DEFAULT, cont.level)
        assertEquals(-1, cont.pid)
        assertTrue(cont.isContinuation)
    }

    @Test
    fun `continuation does not become the new baseline`() {
        // 连续多行堆栈：第二行起都应继承同一条正常日志，
        // 而不是互相继承（否则会链式污染）
        val prev = (LogcatParser.parseLine(
            "09-17 10:00:01.100  9278 15081 E MyApp: 异常:",
            prev = null
        ) as LogcatParseResult.Line).line

        val lines = LogcatParser.parseLines(
            """
            09-17 10:00:01.100  9278 15081 E MyApp: 异常:
            java.lang.RuntimeException: boom
            at com.example.Foo.bar(Foo.kt:1)
            """.trimIndent()
        )

        assertEquals(3, lines.size)
        assertEquals("MyApp", lines[0].tag)
        assertEquals("MyApp", lines[1].tag)
        assertEquals("MyApp", lines[2].tag)
        assertEquals(LogLevel.ERROR, lines[1].level)
        assertEquals(LogLevel.ERROR, lines[2].level)
        assertFalse(lines[0].isContinuation)
        assertTrue(lines[1].isContinuation)
        assertTrue(lines[2].isContinuation)
    }

    // ── 头标记行（实测新增）─────────────────────────────────────

    @Test
    fun `detects logcat buffer markers`() {
        // logcat -d 输出里混有非日志行，实测样本见调试工具文档 §4.4。
        // 它们不匹配 threadtime 正则，若不单独识别会走降级路径污染继承链。
        assertTrue(LogcatParser.isBufferMarker("--------- beginning of main"))
        assertTrue(LogcatParser.isBufferMarker("--------- beginning of system"))
        assertTrue(LogcatParser.isBufferMarker("--------- beginning of crash"))
        assertTrue(LogcatParser.isBufferMarker("--------- beginning of events"))
        assertFalse(LogcatParser.isBufferMarker("09-16 21:04:13.040  1 1 I T: m"))
    }

    @Test
    fun `buffer marker is reported separately and does not pollute inheritance`() {
        // 关键回归：标记行不能把"上一条日志"冲掉。
        // 实测中 -T n 会多返回这类行，若处理不当，
        // 后续降级行就会继承到空的 tag/level。
        val lines = LogcatParser.parseLines(
            """
            --------- beginning of main
            09-17 10:00:01.100  9278 15081 E MyApp: 异常:
            at com.example.Foo.bar(Foo.kt:1)
            """.trimIndent()
        )

        // 标记行被 parseLines 滤掉，只剩 2 行
        assertEquals(2, lines.size)
        assertEquals("MyApp", lines[0].tag)
        // 若标记行污染了基线，这里会是空 tag / V 级别
        assertEquals("MyApp", lines[1].tag)
        assertEquals(LogLevel.ERROR, lines[1].level)
    }

    @Test
    fun `parse result reports buffer marker as its own type`() {
        val result = LogcatParser.parse("--------- beginning of main")
        assertTrue(result[0] is LogcatParseResult.BufferMarker)
    }

    @Test
    fun `blank lines are dropped`() {
        val result = LogcatParser.parse("09-17 10:00:01.100  1 1 I T: a\n\n\n09-17 10:00:02.100  1 1 I T: b")
        assertEquals(2, result.size)
    }

    // ── TAG 聚合 ─────────────────────────────────────────────────

    @Test
    fun `counts by tag sorted by count descending`() {
        val lines = LogcatParser.parseLines(
            """
            09-17 10:00:01.100  1 1 I A: 1
            09-17 10:00:01.101  1 1 I B: 2
            09-17 10:00:01.102  1 1 I A: 3
            09-17 10:00:01.103  1 1 I A: 4
            09-17 10:00:01.104  1 1 I B: 5
            """.trimIndent()
        )

        assertEquals(listOf("A" to 3, "B" to 2), LogcatParser.countByTag(lines))
    }

    @Test
    fun `counts exclude continuation lines to avoid inflating a tag`() {
        // 降级行的 tag 是继承来的。若计入，"续行很多"的 TAG 会虚高，
        // 误导用户以为那个 TAG 日志最多。
        val lines = LogcatParser.parseLines(
            """
            09-17 10:00:01.100  1 1 I A: 1
            continuation 1
            continuation 2
            continuation 3
            09-17 10:00:01.200  1 1 I B: 2
            """.trimIndent()
        )

        assertEquals(listOf("A" to 1, "B" to 1), LogcatParser.countByTag(lines))
    }

    @Test
    fun `counts skip blank tags from orphan continuations`() {
        val lines = LogcatParser.parseLines("orphan line without prefix")
        assertEquals(emptyList<Pair<String, Int>>(), LogcatParser.countByTag(lines))
    }

    @Test
    fun `counts continuations separately`() {
        val lines = LogcatParser.parseLines(
            """
            09-17 10:00:01.100  1 1 I A: 1
            cont 1
            cont 2
            """.trimIndent()
        )
        assertEquals(2, LogcatParser.countContinuations(lines))
    }
}
