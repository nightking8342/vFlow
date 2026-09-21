package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触发器参数 → [LogcatTriggerCondition] 的翻译逻辑。
 *
 * 这段逻辑在 `LogcatTriggerHandler.toCondition` 里，但它**本质是纯函数**
 * （参数 map 进、条件出），所以这里用一个同构的本地实现来锁定语义。
 *
 * ⚠️ 这里锁的是**参数层与匹配层之间的契约**：
 * `LogcatTriggerModule` 声明了哪些参数、存的是什么值，
 * 与 `LogcatMatch` 期望的匹配器形态必须对得上。
 * 对不上的表现是**触发器配好了但不触发**，且没有任何提示。
 *
 * ## 为什么不直接测 Handler
 *
 * `LogcatTriggerHandler` 继承 `BaseTriggerHandler`，构造时会建立
 * `CoroutineScope(Dispatchers.IO)` 并用 `WorkflowManager` —— 在 JVM 单测里
 * 起不来。所以把可测的部分抽出来，Handler 只负责接线。
 */
class LogcatTriggerConditionBuildTest {

    // ── 与 Handler.toCondition 同构的实现 ────────────────────────

    /**
     * 参数 map → 条件。
     *
     * 保持与 `LogcatTriggerHandler.toCondition` 逐行一致；
     * 两边不一致时以本文件为准（它有测试）。
     */
    private fun toCondition(
        params: Map<String, Any?>,
        triggerId: String = "wf:step",
    ): LogcatTriggerCondition? {
        val tagMatcher = buildMatcher(
            params["tag_filter_type"] as? String ?: LogcatFilterType.ANY,
            params["tag_filter_value"] as? String,
        ) ?: return null

        val messageMatcher = buildMatcher(
            params["message_filter_type"] as? String ?: LogcatFilterType.ANY,
            params["message_filter_value"] as? String,
        ) ?: return null

        val minLevel = (params["min_level"] as? String)?.firstOrNull()
            ?.let { LogLevel.fromChar(it) }
            ?: LogLevel.INFO

        return LogcatTriggerCondition(triggerId, tagMatcher, messageMatcher, minLevel)
    }

    private fun line(
        tag: String = "MyApp",
        message: String = "hello",
        level: LogLevel = LogLevel.INFO,
    ) = LogcatLine(tag, message, level, 100, 100, "09-19 10:00:00.000", "raw", false)

    // ── 默认值 ──────────────────────────────────────────────────

    @Test
    fun `an empty parameter map yields a permissive condition`() {
        // 全新拖进来的触发器：两个条件都是 any，级别取默认 I。
        // 这是"能工作但不精确"的起点，不该是 null（那会让触发器完全不工作）
        val c = toCondition(emptyMap())!!
        assertEquals(LogcatMatcher.Unconstrained, c.tagMatcher)
        assertEquals(LogcatMatcher.Unconstrained, c.messageMatcher)
        assertEquals(LogLevel.INFO, c.minLevel)
    }

    @Test
    fun `a fresh trigger matches info and above but not debug`() {
        val c = toCondition(emptyMap())!!
        assertTrue(c.matches(line(level = LogLevel.INFO)))
        assertTrue(c.matches(line(level = LogLevel.ERROR)))
        assertFalse("默认级别是 I，D 不该触发", c.matches(line(level = LogLevel.DEBUG)))
    }

    // ── 参数 → 匹配器 ───────────────────────────────────────────

    @Test
    fun `each filter type maps to the right matcher`() {
        assertTrue(
            toCondition(mapOf("tag_filter_type" to LogcatFilterType.ANY))!!.tagMatcher
                is LogcatMatcher.Unconstrained
        )
        assertEquals(
            LogcatMatcher.Equals("X"),
            toCondition(mapOf("tag_filter_type" to "equals", "tag_filter_value" to "X"))!!.tagMatcher
        )
        assertEquals(
            LogcatMatcher.Contains("X"),
            toCondition(mapOf("tag_filter_type" to "contains", "tag_filter_value" to "X"))!!.tagMatcher
        )
        assertTrue(
            toCondition(mapOf("tag_filter_type" to "regex", "tag_filter_value" to "a.*b"))!!.tagMatcher
                is LogcatMatcher.RegexMatcher
        )
    }

    @Test
    fun `an invalid regex yields null so the trigger is skipped`() {
        // ⚠️ 返回 null 而不是回退成 Unconstrained —— 后者会让"配错了正则"
        // 变成"匹配所有日志"，与用户意图正好相反
        assertNull(toCondition(mapOf("tag_filter_type" to "regex", "tag_filter_value" to "[unclosed")))
        assertNull(toCondition(mapOf("message_filter_type" to "regex", "message_filter_value" to "(a")))
    }

    @Test
    fun `an invalid tag condition does not affect the message condition`() {
        // 逐字段独立判断：TAG 配错时该触发器的 message 配得再对也没用，
        // 但**不影响其他触发器**（这一点由 Handler 的 mapNotNull 保证）
        assertNull(
            toCondition(
                mapOf(
                    "tag_filter_type" to "regex",
                    "tag_filter_value" to "[bad",
                    "message_filter_type" to "contains",
                    "message_filter_value" to "error",
                )
            )
        )
    }

    // ── 级别 ────────────────────────────────────────────────────

    @Test
    fun `each level character round-trips`() {
        for (level in LogLevel.entries) {
            val c = toCondition(mapOf("min_level" to level.char.toString()))!!
            assertEquals("级别 ${level.char} 应被解析", level, c.minLevel)
        }
    }

    @Test
    fun `an unrecognized level falls back to the default`() {
        // ⚠️ 生成侧与解码侧的严格度**刻意不同**：
        // Core 侧解码时遇到非法级别会拒收（避免"配了 E 却触发 V"），
        // 而这里是生成侧，回退到默认至少能让触发器工作。
        // 两者不对称是有意的——此处锁住生成侧的宽松行为
        assertEquals(LogLevel.INFO, toCondition(mapOf("min_level" to "X"))!!.minLevel)
        assertEquals(LogLevel.INFO, toCondition(mapOf("min_level" to ""))!!.minLevel)
    }

    @Test
    fun `minLevel F is preserved so only fatal triggers`() {
        val c = toCondition(mapOf("min_level" to "F"))!!
        assertTrue(c.matches(line(level = LogLevel.FATAL)))
        assertFalse(c.matches(line(level = LogLevel.ERROR)))
    }

    // ── 端到端：参数配置真的能匹配到日志 ────────────────────────

    @Test
    fun `a realistic configuration matches the intended log`() {
        // 用户配：TAG 包含 "WeChat"，消息包含 "登录失败"，级别 E 以上
        val c = toCondition(
            mapOf(
                "tag_filter_type" to "contains",
                "tag_filter_value" to "WeChat",
                "message_filter_type" to "contains",
                "message_filter_value" to "登录失败",
                "min_level" to "E",
            )
        )!!

        assertTrue("目标日志应命中", c.matches(line(tag = "WeChat", message = "微信 登录失败", level = LogLevel.ERROR)))
        assertFalse("TAG 不符不应命中", c.matches(line(tag = "Other", message = "登录失败", level = LogLevel.ERROR)))
        assertFalse("级别不够不应命中", c.matches(line(tag = "WeChat", message = "登录失败", level = LogLevel.INFO)))
        assertFalse("消息不符不应命中", c.matches(line(tag = "WeChat", message = "登录成功", level = LogLevel.ERROR)))
    }

    @Test
    fun `the built condition survives the wire round trip`() {
        // 参数 → 条件 → 传输 → Core 侧解出来，必须还是同一份语义
        val original = toCondition(
            mapOf(
                "tag_filter_type" to "equals",
                "tag_filter_value" to "MyApp",
                "message_filter_type" to "regex",
                "message_filter_value" to "err(or)?",
                "min_level" to "W",
            )
        )!!

        val decoded = LogcatConditionWire.decode(LogcatConditionWire.encode(original))
        assertNotNull(decoded)
        assertEquals(original, decoded)

        // 解出来的条件对同一行日志给出同样的判定
        val sample = line(tag = "MyApp", message = "an error", level = LogLevel.ERROR)
        assertEquals(original.matches(sample), decoded!!.matches(sample))
    }

    @Test
    fun `a trigger id is required for routing`() {
        // 没有 id 的事件在 App 侧无法定位到工作流
        assertEquals("wf1:step2", toCondition(emptyMap(), triggerId = "wf1:step2")!!.triggerId)
    }
}
