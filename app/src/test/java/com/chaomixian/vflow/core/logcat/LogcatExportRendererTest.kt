package com.chaomixian.vflow.core.logcat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LogcatExportRenderer] 的回归测试。
 *
 * 导出是用户拿去给开发者看的东西——**格式错了没人会立刻发现**，
 * 只会在别人解析时炸。所以 JSON 转义、raw 原样回放这些点必须锁住。
 */
class LogcatExportRendererTest {

    private fun line(
        tag: String = "MyApp",
        message: String = "hello",
        level: LogLevel = LogLevel.INFO,
        timestamp: String = "09-18 10:00:00.000",
        raw: String = "09-18 10:00:00.000  100  100 I MyApp: hello",
        continuation: Boolean = false,
    ) = LogcatLine(tag, message, level, 100, 100, timestamp, raw, continuation)

    // ── 纯文本 ──────────────────────────────────────────────────

    @Test
    fun `text export replays raw lines verbatim`() {
        // ⚠️ 不重新拼装：raw 是 logcat 的原始输出。
        // 重拼会丢掉降级行的本来面目，而"这行没有 TAG 前缀"恰恰是
        // 用户排查触发器失配时最需要看到的信息。
        val raw = "09-18 10:00:01.100  9278 15081 I WeChat: 具体消息"
        val text = LogcatExportRenderer.renderText(listOf(line(raw = raw)))
        assertEquals(raw, text)
    }

    @Test
    fun `text export marks continuation lines`() {
        // 与界面口径一致：↳ 前缀让用户一眼认出多行日志的续行
        val text = LogcatExportRenderer.renderText(
            listOf(line(raw = "  at com.example.Foo.bar(Foo.kt:42)", continuation = true))
        )
        assertEquals("↳   at com.example.Foo.bar(Foo.kt:42)", text)
    }

    @Test
    fun `text export joins lines with a newline`() {
        val text = LogcatExportRenderer.renderText(listOf(line(raw = "a"), line(raw = "b")))
        assertEquals("a\nb", text)
    }

    @Test
    fun `text export of nothing is an empty string`() {
        assertEquals("", LogcatExportRenderer.renderText(emptyList()))
    }

    // ── JSON 转义 ★ ─────────────────────────────────────────────

    @Test
    fun `escapes quotes and backslashes`() {
        assertEquals("say \\\"hi\\\"", LogcatExportRenderer.escapeJson("say \"hi\""))
        assertEquals("a\\\\b", LogcatExportRenderer.escapeJson("a\\b"))
    }

    @Test
    fun `escapes newlines so a multi-line message stays one JSON value`() {
        // 多行日志（堆栈）必须留在一个 JSON 字符串里，否则产出的文件语法非法
        assertEquals("a\\nb", LogcatExportRenderer.escapeJson("a\nb"))
        assertEquals("a\\r\\nb", LogcatExportRenderer.escapeJson("a\r\nb"))
        assertEquals("a\\tb", LogcatExportRenderer.escapeJson("a\tb"))
    }

    @Test
    fun `escapes backspace and form feed`() {
        assertEquals("""a\bb""", LogcatExportRenderer.escapeJson("a\u0008b"))
        assertEquals("""a\fb""", LogcatExportRenderer.escapeJson("a\u000Cb"))
    }

    @Test
    fun `escapes other control characters as unicode`() {
        // ⚠️ logcat 正文里混进 ANSI 转义序列很常见（ESC = 0x1B）。
        // 不转义会产出"看起来能打开"但语法非法的 JSON —— 生成时不报错，
        // 只在别人解析时炸。
        assertEquals("""\u001b[31m""", LogcatExportRenderer.escapeJson("\u001B[31m"))
        assertEquals("""\u0000""", LogcatExportRenderer.escapeJson("\u0000"))
        assertEquals("""\u001f""", LogcatExportRenderer.escapeJson("\u001F"))
    }

    @Test
    fun `leaves ordinary text and non-ascii alone`() {
        // 中文、emoji 不该被转义（JSON 允许直接是 UTF-8）
        assertEquals("中文日志 🎉", LogcatExportRenderer.escapeJson("中文日志 🎉"))
    }

    @Test
    fun `json line fields carry escaped messages`() {
        val json = LogcatExportRenderer.renderJson(
            lines = listOf(line(message = "he said \"hi\"\nnext")),
            filter = LogcatFilter(),
            state = CaptureState.Idle,
            capturedAtMs = 1_700_000_000_000,
        )
        // 期望的 JSON 文本（逐字）：  "he said \"hi\"\nnext"
        // 即：外层是 JSON 的引号，内部的引号与换行都已转义，
        // 整段留在一个 JSON 字符串里。
        val expectedQuote = "\""
        val expected = expectedQuote + "he said " + "\\" + expectedQuote + "hi" +
            "\\" + expectedQuote + "\\nnext" + expectedQuote
        assertTrue("实际内容: " + json, json.contains(expected))
        // 裸引号（未转义的 "hi"）不该出现
        assertTrue("裸引号未转义", !json.contains(expectedQuote + "hi" + expectedQuote))
    }

    // ── JSON 结构 ───────────────────────────────────────────────

    @Test
    fun `json carries the capture conditions for reproduction`() {
        // 用户报问题时，开发者需要知道"这是在什么条件下抓的"
        val json = LogcatExportRenderer.renderJson(
            lines = listOf(line()),
            filter = LogcatFilter(minLevel = LogLevel.WARN, tagQuery = "MyApp", lineLimit = 500),
            state = CaptureState.Idle,
            capturedAtMs = 1_700_000_000_000,
        )

        assertTrue(json.contains(""""exportVersion": 1"""))
        assertTrue(json.contains(""""capturedAtMillis": 1700000000000"""))
        assertTrue(json.contains(""""minLevel": "WARN""""))
        assertTrue(json.contains(""""tagQuery": "MyApp""""))
        assertTrue(json.contains(""""lineLimit": 500"""))
    }

    @Test
    fun `json records which data source the lines came from`() {
        // 数据源决定了这批日志"是什么"：缓冲区是会变的滚动窗口，
        // 采集文件是固定的区间
        val capturing = LogcatExportRenderer.renderJson(
            listOf(line()), LogcatFilter(), CaptureState.Capturing(1), 0
        )
        val idle = LogcatExportRenderer.renderJson(
            listOf(line()), LogcatFilter(), CaptureState.Idle, 0
        )

        assertTrue(capturing.contains(""""state": "CAPTURING""""))
        assertTrue(idle.contains(""""state": "IDLE""""))
        assertTrue(capturing.contains("采集文件"))
        assertTrue(idle.contains("缓冲区"))
    }

    @Test
    fun `json reports continuation count`() {
        val json = LogcatExportRenderer.renderJson(
            lines = listOf(line(), line(continuation = true), line(continuation = true)),
            filter = LogcatFilter(),
            state = CaptureState.Idle,
            capturedAtMs = 0,
        )
        assertTrue(json.contains(""""lineCount": 3"""))
        assertTrue(json.contains(""""continuationCount": 2"""))
    }

    @Test
    fun `json does not emit a trailing comma after the last line`() {
        // 尾随逗号是新手手写 JSON 最常见的语法错误，且不报错只在解析时炸
        val json = LogcatExportRenderer.renderJson(
            lines = listOf(line(), line()),
            filter = LogcatFilter(),
            state = CaptureState.Idle,
            capturedAtMs = 0,
        )
        assertTrue("末尾多出逗号", !json.contains("},\n  ]"))
        assertTrue(json.contains("}\n  ]"))
    }

    @Test
    fun `json handles an empty line list`() {
        val json = LogcatExportRenderer.renderJson(
            emptyList(), LogcatFilter(), CaptureState.Idle, 0
        )
        assertTrue(json.contains(""""lineCount": 0"""))
        assertTrue(json.contains("\"lines\": [\n  ]"))
    }

    @Test
    fun `json level is a single character matching the enum`() {
        val json = LogcatExportRenderer.renderJson(
            listOf(line(level = LogLevel.ERROR)), LogcatFilter(), CaptureState.Idle, 0
        )
        assertTrue(json.contains(""""level": "E""""))
    }

    // ── 文件名与标题 ────────────────────────────────────────────

    @Test
    fun `builds a file name with the given extension`() {
        assertEquals(
            "vflow-logcat-20260918-104900.log",
            LogcatExportRenderer.buildFileName("20260918-104900", "log"),
        )
        assertEquals(
            "vflow-logcat-20260918-104900.json",
            LogcatExportRenderer.buildFileName("20260918-104900", "json"),
        )
    }

    @Test
    fun `share title mentions the line count and format`() {
        val title = LogcatExportRenderer.buildShareTitle(430, "log")
        assertTrue(title.contains("430"))
        assertTrue(title.contains(".log"))
    }
}
