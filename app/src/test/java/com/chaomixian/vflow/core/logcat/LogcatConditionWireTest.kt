package com.chaomixian.vflow.core.logcat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatConditionWire] 的回归测试。
 *
 * **编解码不对称是本层最危险的失败模式**：编出 Core 解不开的格式时，
 * Core 拿到空条件列表 → **所有 logcat 触发器静默不触发**，
 * 而 App 侧看起来一切正常（条件已下发、无报错）。
 *
 * 所以核心测试是**往返一致性**（encode → decode 必须回到原值）。
 */
class LogcatConditionWireTest {

    private fun cond(
        tag: LogcatMatcher = LogcatMatcher.Unconstrained,
        message: LogcatMatcher = LogcatMatcher.Unconstrained,
        minLevel: LogLevel = LogLevel.INFO,
        id: String = "t1",
    ) = LogcatTriggerCondition(id, tag, message, minLevel)

    // ── 往返一致性 ★ ────────────────────────────────────────────

    @Test
    fun `round trips a full condition`() {
        val original = cond(
            tag = LogcatMatcher.Equals("MyApp"),
            message = LogcatMatcher.Contains("error"),
            minLevel = LogLevel.WARN,
            id = "workflow-42",
        )

        val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))

        assertEquals(original, decoded)
    }

    @Test
    fun `round trips every matcher type`() {
        val cases = listOf(
            LogcatMatcher.Unconstrained,
            LogcatMatcher.Equals("exact"),
            LogcatMatcher.Contains("sub"),
            LogcatMatcher.RegexMatcher(Regex("a.*b(c|d)")),
        )

        for (tagMatcher in cases) {
            for (messageMatcher in cases) {
                val original = cond(tag = tagMatcher, message = messageMatcher)
                val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))
                assertEquals("往返后不一致: $tagMatcher / $messageMatcher", original, decoded)
            }
        }
    }

    @Test
    fun `round trips every level including fatal`() {
        for (level in LogLevel.entries) {
            val original = cond(minLevel = level)
            val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))
            assertEquals("级别 $level 往返失败", level, decoded?.minLevel)
        }
    }

    @Test
    fun `round trips a whole list`() {
        val conditions = listOf(
            cond(id = "t1", tag = LogcatMatcher.Equals("A")),
            cond(id = "t2", message = LogcatMatcher.Contains("B")),
            cond(id = "t3", tag = LogcatMatcher.RegexMatcher(Regex("C\\d+")), minLevel = LogLevel.ERROR),
        )

        val decoded = LogcatConditionWire.decodeConditions(
            LogcatConditionWire.encodeConditions(conditions)
        )

        assertEquals(conditions, decoded)
    }

    @Test
    fun `round trips an empty list`() {
        // 空列表是有意义的载荷：Core 据此停掉 logcat 进程（文档 §7.1）
        val decoded = LogcatConditionWire.decodeConditions(
            LogcatConditionWire.encodeConditions(emptyList())
        )
        assertTrue(decoded.isEmpty())
    }

    @Test
    fun `regex pattern text survives verbatim`() {
        // 模式不能被"转义"或改写——Core 侧拿到的必须与用户配的逐字一致
        val pattern = """^WeChat:\s+(fail|error)["']?$"""
        val original = cond(tag = LogcatMatcher.RegexMatcher(Regex(pattern)))
        val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))
        assertEquals(pattern, (decoded!!.tagMatcher as LogcatMatcher.RegexMatcher).regex.pattern)
    }

    @Test
    fun `special characters in values survive the round trip`() {
        val nasty = """a"b\c/d:e{f}g|h*i?j+k&l=m<n>o"""
        val original = cond(message = LogcatMatcher.Contains(nasty))
        val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))
        assertEquals(nasty, (decoded!!.messageMatcher as LogcatMatcher.Contains).value)
    }

    @Test
    fun `cjk values survive the round trip`() {
        val original = cond(message = LogcatMatcher.Contains("微信 登录失败"))
        val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))
        assertEquals("微信 登录失败", (decoded!!.messageMatcher as LogcatMatcher.Contains).value)
    }

    // ── 脏数据容错 ─────────────────────────────────────────────

    @Test
    fun `a condition without a trigger id is rejected`() {
        // triggerId 是 App 侧路由的唯一依据（文档 §4.2）——没有它就不知道
        // 该触发哪个工作流，只能拒收
        val json = LogcatConditionWire.encode(cond()).apply { remove("triggerId") }
        assertNull(LogcatConditionWire.decode(json))
    }

    @Test
    fun `a condition with a blank trigger id is rejected`() {
        val json = LogcatConditionWire.encode(cond()).put("triggerId", "   ")
        assertNull(LogcatConditionWire.decode(json))
    }

    @Test
    fun `a condition with a broken regex is rejected`() {
        // Core 侧收到非法正则会编译失败。必须拒收而不是静默当成 Any ——
        // 当成 Any 会让"配错了正则"变成"匹配所有日志"，行为完全相反
        val json = LogcatConditionWire.encode(cond()).put("tagType", "regex").put("tagValue", "[unclosed")
        assertNull(LogcatConditionWire.decode(json))
    }

    @Test
    fun `a condition with an unknown matcher type is rejected`() {
        val json = LogcatConditionWire.encode(cond()).put("tagType", "fuzzy")
        assertNull(LogcatConditionWire.decode(json))
    }

    @Test
    fun `a condition with an unknown level is rejected`() {
        // 不猜测、不回退到默认：回退会让"配了 E 却触发了 V"成为可能
        val json = LogcatConditionWire.encode(cond()).put("minLevel", "X")
        assertNull(LogcatConditionWire.decode(json))
    }

    @Test
    fun `a condition with a missing level is rejected`() {
        val json = LogcatConditionWire.encode(cond()).apply { remove("minLevel") }
        assertNull(LogcatConditionWire.decode(json))
    }

    @Test
    fun `one bad entry does not discard the whole list`() {
        // ⚠️ 关键行为：某一条坏掉时**只跳过它**。
        // 若整体失败，用户"新加了一个配错的触发器"会导致
        // **已有的正常触发器全部失效** —— 影响面与直觉完全不符
        val good = LogcatConditionWire.encode(cond(id = "good"))
        val bad = JSONObject().put("triggerId", "").put("tagType", "any")
        val alsoGood = LogcatConditionWire.encode(cond(id = "also-good"))

        val array = JSONArray().apply { put(good); put(bad); put(alsoGood) }

        val decoded = LogcatConditionWire.decodeConditions(array)
        assertEquals(listOf("good", "also-good"), decoded.map { it.triggerId })
    }

    @Test
    fun `non-object entries in the array are skipped`() {
        val array = JSONArray().apply {
            put(LogcatConditionWire.encode(cond(id = "ok")))
            put("a bare string")
            put(42)
        }
        assertEquals(listOf("ok"), LogcatConditionWire.decodeConditions(array).map { it.triggerId })
    }

    // ── 字段名稳定性 ────────────────────────────────────────────

    @Test
    fun `encoded field names are stable`() {
        // ⚠️ Core 侧照抄同一份实现，**字段名改了必须两边同时改**。
        // 这条测试是给"只改了一边"这种情况的警报
        val json = LogcatConditionWire.encode(cond(id = "x"))

        assertTrue(json.has("triggerId"))
        assertTrue(json.has("tagType"))
        assertTrue(json.has("tagValue"))
        assertTrue(json.has("messageType"))
        assertTrue(json.has("messageValue"))
        assertTrue(json.has("minLevel"))
        assertEquals("x", json.getString("triggerId"))
    }

    @Test
    fun `level is encoded as a single character`() {
        // 与 LogLevel.char 一致（也是日志行里那个字符）
        assertEquals("E", LogcatConditionWire.encode(cond(minLevel = LogLevel.ERROR)).getString("minLevel"))
    }

    @Test
    fun `an Any matcher encodes as the any type constant`() {
        val json = LogcatConditionWire.encode(cond(tag = LogcatMatcher.Unconstrained))
        assertEquals(LogcatFilterType.ANY, json.getString("tagType"))
    }
}
