package com.chaomixian.vflow.server.logcat

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Core 侧条件编解码的回归测试。
 *
 * ## 这里锁的是「App 编的格式 Core 必须能解」
 *
 * 两边不对称时，Core 会拿到空条件列表 → **所有触发器静默不触发**，
 * 而 App 侧看起来一切正常（条件已下发、无报错）。
 *
 * 因此下面的 JSON **手写字面量**，而不是调用 Core 自己的 encode
 * （Core 侧没有 encode —— 它只解不编）。手写能真正验证「App 那边的字段名」
 * 是否被正确接受，而不是自己跟自己转圈。
 *
 * 字段名若在 app 侧 `LogcatConditionWire` 改了而这里没跟上，
 * 这些测试就会红。
 */
class LogcatConditionCodecTest {

    private fun json(
        triggerId: String? = "t1",
        tagType: String = "any",
        tagValue: String = "",
        messageType: String = "any",
        messageValue: String = "",
        minLevel: String? = "I",
    ): JSONObject = JSONObject().apply {
        triggerId?.let { put("triggerId", it) }
        put("tagType", tagType)
        put("tagValue", tagValue)
        put("messageType", messageType)
        put("messageValue", messageValue)
        minLevel?.let { put("minLevel", it) }
    }

    // ── 字段名契约 ★ ────────────────────────────────────────────

    @Test
    fun `decodes the wire format produced by the app side`() {
        // 这份 JSON 逐字对应 app 侧 LogcatConditionWire.encode 的输出结构
        val decoded = LogcatConditionCodec.decodeOne(
            json(
                triggerId = "workflow-42",
                tagType = "equals",
                tagValue = "MyApp",
                messageType = "contains",
                messageValue = "error",
                minLevel = "E",
            )
        )

        assertEquals("workflow-42", decoded!!.triggerId)
        assertEquals(LogcatFieldMatcher.Equals("MyApp"), decoded.tagMatcher)
        assertEquals(LogcatFieldMatcher.Contains("error"), decoded.messageMatcher)
        assertEquals('E', decoded.minLevel)
    }

    @Test
    fun `all four matcher types are accepted`() {
        assertEquals(
            LogcatFieldMatcher.Unconstrained,
            LogcatConditionCodec.decodeOne(json(tagType = "any"))!!.tagMatcher
        )
        assertEquals(
            LogcatFieldMatcher.Equals("v"),
            LogcatConditionCodec.decodeOne(json(tagType = "equals", tagValue = "v"))!!.tagMatcher
        )
        assertEquals(
            LogcatFieldMatcher.Contains("v"),
            LogcatConditionCodec.decodeOne(json(tagType = "contains", tagValue = "v"))!!.tagMatcher
        )
        assertTrue(
            LogcatConditionCodec.decodeOne(json(tagType = "regex", tagValue = "a.*b"))!!.tagMatcher
                is LogcatFieldMatcher.RegexMatcher
        )
    }

    @Test
    fun `regex is precompiled at decode time`() {
        // ⚠️ 文档 §4.2 的硬要求：正则必须在条件变更时编译，
        // **不可在 matches() 里现编**（那段代码在每行日志上执行）。
        // decode 是唯一的编译点，编译产物必须带上模式原文
        val decoded = LogcatConditionCodec.decodeOne(json(tagType = "regex", tagValue = "ab+c"))!!
        assertEquals("ab+c", (decoded.tagMatcher as LogcatFieldMatcher.RegexMatcher).pattern)
    }

    @Test
    fun `every level character is accepted`() {
        for (level in "VDIWEF") {
            assertEquals(
                "级别 $level 应被接受",
                level,
                LogcatConditionCodec.decodeOne(json(minLevel = level.toString()))!!.minLevel
            )
        }
    }

    // ── 容错：跳过坏条目而非整体失败 ────────────────────────────

    @Test
    fun `rejects a condition without a trigger id`() {
        // triggerId 是 App 侧路由的唯一依据，没有它就不知道触发哪个工作流
        assertNull(LogcatConditionCodec.decodeOne(json(triggerId = null)))
        assertNull(LogcatConditionCodec.decodeOne(json(triggerId = "")))
        assertNull(LogcatConditionCodec.decodeOne(json(triggerId = "   ")))
    }

    @Test
    fun `rejects a broken regex`() {
        // ⚠️ 不能把编译失败当成 Unconstrained —— 那会让"配错了正则"
        // 变成"匹配所有日志"，与用户意图正好相反
        assertNull(LogcatConditionCodec.decodeOne(json(tagType = "regex", tagValue = "[unclosed")))
    }

    @Test
    fun `rejects an unknown matcher type`() {
        assertNull(LogcatConditionCodec.decodeOne(json(tagType = "fuzzy")))
    }

    @Test
    fun `rejects an unknown level instead of guessing`() {
        // 回退到默认会让"配了 E 却触发了 V"这种静默错误成为可能
        assertNull(LogcatConditionCodec.decodeOne(json(minLevel = "X")))
        assertNull(LogcatConditionCodec.decodeOne(json(minLevel = null)))
    }

    @Test
    fun `one bad entry does not discard the whole list`() {
        // ⚠️ 关键行为：某一条坏掉时**只跳过它**。
        // 若整体失败，用户"新加了一个配错正则的触发器"会导致
        // **已有的正常触发器全部失效** —— 影响面与直觉完全不符
        val array = JSONArray().apply {
            put(json(triggerId = "good"))
            put(json(triggerId = ""))                    // 坏：缺 id
            put(json(triggerId = "broken", tagType = "regex", tagValue = "[bad"))
            put(json(triggerId = "also-good"))
        }

        assertEquals(
            listOf("good", "also-good"),
            LogcatConditionCodec.decode(array).map { it.triggerId }
        )
    }

    @Test
    fun `non-object entries are skipped`() {
        val array = JSONArray().apply {
            put(json(triggerId = "ok"))
            put("a bare string")
            put(42)
        }
        assertEquals(listOf("ok"), LogcatConditionCodec.decode(array).map { it.triggerId })
    }

    @Test
    fun `a missing array decodes to an empty list`() {
        // 空列表是有意义的载荷：Core 据此停掉 logcat 进程（文档 §7.1）
        assertTrue(LogcatConditionCodec.decode(null).isEmpty())
        assertTrue(LogcatConditionCodec.decode(JSONArray()).isEmpty())
    }

    // ── 值保真 ──────────────────────────────────────────────────

    @Test
    fun `special characters in values are preserved`() {
        val nasty = """a"b\c/d:e{f}g|h*i?j+k&l=m<n>o"""
        val decoded = LogcatConditionCodec.decodeOne(
            json(messageType = "contains", messageValue = nasty)
        )!!
        assertEquals(nasty, (decoded.messageMatcher as LogcatFieldMatcher.Contains).value)
    }

    @Test
    fun `cjk values are preserved`() {
        val decoded = LogcatConditionCodec.decodeOne(
            json(messageType = "contains", messageValue = "微信 登录失败")
        )!!
        assertEquals("微信 登录失败", (decoded.messageMatcher as LogcatFieldMatcher.Contains).value)
    }

    @Test
    fun `a regex pattern with special syntax survives`() {
        val pattern = """^WeChat:\s+(fail|error)["']?$"""
        val decoded = LogcatConditionCodec.decodeOne(json(tagType = "regex", tagValue = pattern))!!
        assertEquals(pattern, (decoded.tagMatcher as LogcatFieldMatcher.RegexMatcher).pattern)
    }
}
