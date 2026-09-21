package com.chaomixian.vflow.server.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core 侧索引扫描解析器的回归测试。
 *
 * ## 为什么这个文件必须存在
 *
 * 索引扫描**靠偏移硬算**，写错了不会崩、只会**静默解析出错误的 tag**——
 * 那样触发器就永远不命中，而日志、配置、UI 全都看起来正常。
 * 这是最难查的一类 bug，所以它必须有测试。
 *
 * ## 与 app 侧的关系
 *
 * 语义必须与 `app/.../core/logcat/LogcatParser.kt`（及其 18 例测试）一致。
 * 两处不一致的表现同样是「调试工具里看着能匹配的日志，触发器匹配不到」。
 * 本文件里的关键用例与 app 侧**刻意重复**——重复是刻意的，
 * 因为两份实现各自独立演化时，只有各自的测试能挡住各自的退化。
 */
class LogcatLineParserTest {

    private fun parser() = LogcatLineParser()

    // ── 正常行 ──────────────────────────────────────────────────

    @Test
    fun `parses a standard threadtime line`() {
        // 真实 logcat 的 pid/tid 是 %5d 右对齐
        val line = "09-18 10:00:01.100  9278 15081 I MyApp: hello world"
        val parsed = parser().parse(line)

        assertEquals("MyApp", parsed!!.tag)
        assertEquals("hello world", parsed.message)
        assertEquals('I', parsed.level)
        assertEquals(9278, parsed.pid)
        assertFalse(parsed.isContinuation)
    }

    @Test
    fun `parses every level character`() {
        for (level in "VDIWEF") {
            val parsed = parser().parse("09-18 10:00:01.100  9278 15081 $level Tag: msg")
            assertEquals("级别 $level 应被识别", level, parsed!!.level)
        }
    }

    @Test
    fun `parses a tag containing dots and underscores`() {
        val parsed = parser().parse("09-18 10:00:01.100  1 2 D vFlow_Shell.Manager: ok")
        assertEquals("vFlow_Shell.Manager", parsed!!.tag)
    }

    @Test
    fun `parses a message containing colons`() {
        // ⚠️ 用**第一个**冒号分隔 tag 与 message。消息里的冒号不能被当成分隔符，
        // 否则 tag 会包含消息的前半段（如 "MyApp: url: http" → tag "MyApp: url"）
        val parsed = parser().parse("09-18 10:00:01.100  1 2 E MyApp: url: https://x.com:443")
        assertEquals("MyApp", parsed!!.tag)
        assertEquals("url: https://x.com:443", parsed.message)
    }

    @Test
    fun `parses an empty message`() {
        val parsed = parser().parse("09-18 10:00:01.100  1 2 I MyApp: ")
        assertEquals("MyApp", parsed!!.tag)
        assertEquals("", parsed.message)
    }

    @Test
    fun `tolerates a space before the colon`() {
        // 少数 ROM 会输出 `Tag : msg`
        val parsed = parser().parse("09-18 10:00:01.100  1 2 I MyApp : hello")
        assertEquals("MyApp", parsed!!.tag)
        assertEquals("hello", parsed.message)
    }

    @Test
    fun `tolerates varying pid widths`() {
        // 不假设 pid/tid 的列宽——按"跳过连续数字"取，
        // 这样不同 ROM 的列宽差异不会导致解析错误
        val wide = parser().parse("09-18 10:00:01.100  123456 654321 I Tag: m")
        assertEquals(123456, wide!!.pid)
        assertEquals("Tag", wide.tag)

        val narrow = parser().parse("09-18 10:00:01.100  1 2 I Tag: m")
        assertEquals(1, narrow!!.pid)
    }

    @Test
    fun `parses cjk messages`() {
        val parsed = parser().parse("09-18 10:00:01.100  1 2 I MyApp: 微信登录失败")
        assertEquals("微信登录失败", parsed!!.message)
    }

    // ── 非法 / 非日志行 ─────────────────────────────────────────

    @Test
    fun `returns null for a buffer marker`() {
        // ⚠️ 日志头标记行**必须返回 null**，不能走降级路径 ——
        // 否则会污染 tag/level 的继承链（真机实测发现）
        assertNull(parser().parse("--------- beginning of main"))
        assertNull(parser().parse("--------- beginning of system"))
        assertNull(parser().parse("--------- beginning of crash"))
    }

    @Test
    fun `returns null for an empty line`() {
        assertNull(parser().parse(""))
    }

    @Test
    fun `an epoch format line falls back instead of being misparsed`() {
        // ⚠️ `-v epoch` 的输出形状不同（纯数字时间戳）。**判为非日志行、走降级路径**
        // 是正确的——关键是**不能按 threadtime 的偏移硬算出一堆垃圾 tag**
        // （那会让触发器匹配到错误的 TAG，且完全静默）。
        val parsed = parser().parse("1695000000.123  9278 15081 I MyApp: hello")
        assertTrue("应走降级路径而非被误解析", parsed!!.isContinuation)
    }

    @Test
    fun `a too-short line falls back`() {
        val parsed = parser().parse("09-18 10:00 I Tag: m")
        assertTrue(parsed!!.isContinuation)
    }

    @Test
    fun `an invalid level character falls back`() {
        val parsed = parser().parse("09-18 10:00:01.100  9278 15081 Z MyApp: hello")
        assertTrue("级别字符不合法时不该硬算", parsed!!.isContinuation)
    }

    @Test
    fun `a missing tag falls back`() {
        // 有冒号但 tag 为空（`I : msg`）
        val parsed = parser().parse("09-18 10:00:01.100  9278 15081 I : hello")
        assertTrue(parsed!!.isContinuation)
    }

    @Test
    fun `a short pid and empty message still parses`() {
        // ⚠️ 这条曾经失败过：旧实现用 MIN_LENGTH=34 做前置门槛，
        // 而这行只有 33 字符 → 被误判为非日志行。
        // 短 pid/tid + 空消息是完全合法的形态，不能靠行长判断。
        val parsed = parser().parse("09-18 10:00:01.100  1 2 I MyApp: ")
        assertFalse("短行也应当被解析", parsed!!.isContinuation)
        assertEquals("MyApp", parsed.tag)
        assertEquals("", parsed.message)
    }

    // ── 降级语义 ★ 与 app 侧必须一致 ────────────────────────────

    @Test
    fun `a stack trace line inherits the previous tag and level`() {
        // ⚠️ 这是 §5.1 那条修订的核心。若按初稿把降级行置空/置 V：
        // - 置空 tag → 配了 tag 条件的触发器看不到续行
        // - 置 V level → 被 min_level 静默过滤掉
        // 两种都会让"多行日志"的下半截消失，而用户毫无察觉
        val p = parser()
        p.parse("09-18 10:00:01.100  1 2 E MyApp: FATAL EXCEPTION: main")
        val continuation = p.parse("        at com.example.Foo.bar(Foo.kt:42)")

        assertTrue("应判为降级行", continuation!!.isContinuation)
        assertEquals("应继承母行的 tag", "MyApp", continuation.tag)
        assertEquals("应继承母行的级别", 'E', continuation.level)
        assertEquals("整行原文作为消息", "        at com.example.Foo.bar(Foo.kt:42)", continuation.message)
    }

    @Test
    fun `a buffer marker does not pollute the inheritance chain`() {
        // ⚠️ 关键：标记行介于两条日志之间时，后续的降级行必须继承
        // **上一条真正的日志**，而不是被标记行"清空"
        val p = parser()
        p.parse("09-18 10:00:01.100  1 2 W MyApp: first")
        p.parse("--------- beginning of main")          // 应被跳过，不影响继承
        val continuation = p.parse("  continuation text")

        assertEquals("MyApp", continuation!!.tag)
        assertEquals('W', continuation.level)
    }

    @Test
    fun `consecutive continuations do not chain-inherit from each other`() {
        // 降级行本身不更新继承基线。若它错误地更新了，第二条续行的 tag
        // 会变成第一条续行的 tag（即整行原文），彻底跑偏
        val p = parser()
        p.parse("09-18 10:00:01.100  1 2 D RealTag: parent")
        val c1 = p.parse("  first continuation")
        val c2 = p.parse("  second continuation")

        assertEquals("RealTag", c1!!.tag)
        assertEquals("第二条续行的 tag 不该变成第一条的原文", "RealTag", c2!!.tag)
    }

    @Test
    fun `a continuation before any log line has an empty tag`() {
        // 流刚开始就遇到无前缀行（不该发生，但不能崩）
        val parsed = parser().parse("  orphan continuation")
        assertTrue(parsed!!.isContinuation)
        assertEquals("", parsed.tag)
    }

    @Test
    fun `a continuation inherits the pid of its parent`() {
        // pid 用于「排除本应用日志」，继承错了会导致自触发环判错
        val p = parser()
        p.parse("09-18 10:00:01.100  4242 1 E Tag: parent")
        assertEquals(4242, p.parse("  cont")!!.pid)
    }

    @Test
    fun `the parser keeps state across calls`() {
        // 确认它是有状态的（每个流一个实例），而非无状态工具
        val p = parser()
        p.parse("09-18 10:00:01.100  1 2 I First: a")
        assertEquals("First", p.parse("  cont")!!.tag)

        p.parse("09-18 10:00:02.100  1 2 I Second: b")
        assertEquals("Second", p.parse("  cont2")!!.tag)
    }
}
