package com.chaomixian.vflow.ui.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具入参归一化的回归测试。
 *
 * ## 背景（2026-09-22 真机缺陷）
 *
 * `update_workflow` 首版把**未归一化的 `JsonElement`** 直接交给了 `coerceInputValue`，
 * 导致三个同时出现的写入污染：
 *
 * | 症状 | 实际存储 | 根因 |
 * |---|---|---|
 * | 字符串多包一层引号 | `""ABC""` | `JsonPrimitive("ABC").toString()` = `"\"ABC\""` |
 * | 数字落成对象 | `{a:false,b:"6000"}` | `coerceNumber` 的 `is Number` / `is String` 都不匹配，元素原样落库（release 里 `JsonLiteral` 的字段被 R8 混淆成 `a`/`b`） |
 * | 多行文本被二次转义 | 真换行变字面 `\n`、`/\s+/` 变 `/\\s+/` | 整个 `JsonElement` 落库后在序列化时再转义一遍 |
 *
 * 三者同源：**值必须先归一化成 Kotlin 值再进参数表**。
 * 这个测试类锁的就是这一步——它此前是私有方法，测不到，所以 bug 漏了出去。
 *
 * ## 为什么这些断言是「改错了不报错」型
 *
 * 归一化写错不会抛异常：`"ABC"` 变成 `""ABC""` 后，工作流照样能存、能在编辑器打开，
 * 只是**运行时匹配不到文本、变量解析不到**。用户看到的是「工作流行为不对」而不是报错。
 */
class ChatAgentParameterNormalizationTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun normalizeJsonElement_plainStringIsNotQuotedAgain() {
        // 缺陷 A：纯 ASCII 短串必须原样，不带外层引号
        val normalized = normalizeJsonElement(json.parseToJsonElement("\"ABC\""))

        assertEquals("ABC", normalized)
        assertFalse("不能多包一层引号", (normalized as String).contains("\""))
    }

    @Test
    fun normalizeJsonElement_variableReferenceStaysVerbatim() {
        // 缺陷 A 的变量引用变体：{{vars.STATE}} 与 [[STATE]] 都要单层原样
        assertEquals(
            "{{vars.STATE}}",
            normalizeJsonElement(json.parseToJsonElement("\"{{vars.STATE}}\"")),
        )
        assertEquals(
            "[[STATE]]",
            normalizeJsonElement(json.parseToJsonElement("\"[[STATE]]\"")),
        )
        assertEquals(
            "本次允许",
            normalizeJsonElement(json.parseToJsonElement("\"本次允许\"")),
        )
    }

    @Test
    fun normalizeJsonElement_numberBecomesNumberNotContainer() {
        // 缺陷 B：数字必须归一成 Kotlin Number，不能是任何容器/元素对象
        val normalized = normalizeJsonElement(json.parseToJsonElement("6000"))

        assertTrue("必须是 Number", normalized is Number)
        assertEquals(6000, (normalized as Number).toInt())
    }

    @Test
    fun normalizeJsonElement_zeroStaysZero() {
        // 缺陷 B 的边界：0 曾被写成 {a:false,b:"0"}。
        // 注意 0 是 falsy，若用 `?:` 串接 boolean 判定会走错分支——这里锁住。
        val normalized = normalizeJsonElement(json.parseToJsonElement("0"))

        assertTrue(normalized is Number)
        assertEquals(0, (normalized as Number).toInt())
    }

    @Test
    fun normalizeJsonElement_falseIsBooleanNotString() {
        val normalized = normalizeJsonElement(json.parseToJsonElement("false"))
        assertEquals(false, normalized)
    }

    @Test
    fun normalizeJsonElement_quotedNumberAlsoBecomesNumber() {
        // 记录**既有行为**（从原 normalizeJsonValue 原样提取，save_workflow 一直如此）：
        // kotlinx 的 doubleOrNull 对带引号的 `"6000"` 也会解析成功，所以它同样归一成 Number。
        //
        // 这不是本次要改的东西——判序是「boolean → double → content」。
        // 之所以记下来：它是**宽容**的（`"6000"` 与 `6000` 殊途同归），
        // 对 update_workflow 无害；但如果哪天要区分二者，改的是判序，不是这里。
        val normalized = normalizeJsonElement(json.parseToJsonElement("\"6000\""))
        assertTrue("带引号的数字也归一成 Number（既有行为）", normalized is Number)
        assertEquals(6000, (normalized as Number).toInt())

        // 真正不能被数字吞掉的是**非纯数字字符串**——这是关键边界
        assertEquals("ABC", normalizeJsonElement(json.parseToJsonElement("\"ABC\"")))
        assertEquals(
            "{{vars.STATE}}",
            normalizeJsonElement(json.parseToJsonElement("\"{{vars.STATE}}\"")),
        )
    }

    @Test
    fun normalizeJsonElement_preservesRealNewlinesAndBackslashes() {
        // 缺陷 C：真换行保留为真换行；正则 \s 保持原样（不能变成 \\s）
        // 这里刻意用 JSON 文本字面量构造：`\n` 在 JSON 里就是换行，`\\s` 才是字面反斜杠+s
        val rawJson = "\"var r={state:0};\\nvar flat=ft.replace(/\\\\s+/g,'');\""

        val normalized = normalizeJsonElement(json.parseToJsonElement(rawJson)) as String

        assertTrue("真换行必须保留", normalized.contains('\n'))
        assertFalse("换行不能是字面 \\n", normalized.contains("\\n"))
        assertTrue("正则 \\s 必须原样", normalized.contains("/\\s+/"))
        assertFalse("正则不能被二次转义", normalized.contains("/\\\\s+/"))
    }

    @Test
    fun normalizeJsonElement_objectBecomesMapNotJsonElement() {
        // 嵌套对象必须变成 Map——留着 JsonElement 会在落库时被再序列化一次
        val normalized = normalizeJsonElement(
            json.parseToJsonElement("""{"duration":6000,"message":"hi"}""")
        )

        assertTrue("必须是 Map", normalized is Map<*, *>)
        val map = normalized as Map<*, *>
        assertEquals(6000, (map["duration"] as Number).toInt())
        assertEquals("hi", map["message"])
    }

    @Test
    fun normalizeJsonElement_arrayBecomesListNotJsonElement() {
        val normalized = normalizeJsonElement(json.parseToJsonElement("""["a","b"]"""))

        assertTrue(normalized is List<*>)
        assertEquals(listOf("a", "b"), normalized)
    }

    @Test
    fun normalizeJsonElement_nullIsKotlinNull() {
        // null 必须保持 Kotlin null——「传 null = 删键」的语义依赖这一点
        assertEquals(null, normalizeJsonElement(json.parseToJsonElement("null")))
    }

    // ══════════════════════════════════════════════════════════════════
    // 以下是**入口级**测试：走 normalizeParameterPatchJson（update_workflow
    // 真正调用的那个函数），而不是单个内部函数。
    //
    // 为什么必须这样测：本次缺陷的形态是「函数写对了，但调用点漏了归一化」。
    // 只测内部函数的测试**不会变红**——反证时已实测确认过这一点。
    // ══════════════════════════════════════════════════════════════════

    @Test
    fun normalizeParameterPatchJson_producesKotlinValuesForEveryShape() {
        val normalized = normalizeParameterPatchJson(
            """{"content":"ABC","duration":6000,"flag":false,"nested":{"k":"v"},"list":["a","b"]}"""
        )

        assertNotNull(normalized)
        assertTrue("字符串必须是 String", normalized!!["content"] is String)
        assertTrue("数字必须是 Number", normalized["duration"] is Number)
        assertTrue("布尔必须是 Boolean", normalized["flag"] is Boolean)
        assertTrue("对象必须是 Map", normalized["nested"] is Map<*, *>)
        assertTrue("数组必须是 List", normalized["list"] is List<*>)
        // 核心断言：一个 JsonElement 都不许残留
        normalized.values.forEach { value ->
            assertFalse("归一化后不应残留 JsonElement", value is JsonElement)
        }
    }

    @Test
    fun normalizeParameterPatchJson_reproducesTheThreeRealWorldDefects() {
        // 用真机报告里的实际 payload，逐条对照修复后的期望值。
        // 这是本测试类存在的最终理由——这三条任意一条失败即代表回归。
        val normalized = normalizeParameterPatchJson(
            """
            {
              "content": "ABC",
              "duration": 6000,
              "source": "{{vars.STATE}}",
              "targetText": "本次允许",
              "script": "var a=1;\nvar f=ft.replace(/\\s+/g,'');"
            }
            """.trimIndent()
        )

        assertNotNull(normalized)
        val values = normalized!!

        // 缺陷 A：不能是 ""ABC""
        assertEquals("ABC", values["content"])
        assertEquals("{{vars.STATE}}", values["source"])
        assertEquals("本次允许", values["targetText"])

        // 缺陷 B：不能是 {a:false,b:"6000"}
        val duration = values["duration"]
        assertTrue("duration 必须是 Number，实际是 ${duration?.let { it::class.simpleName }}", duration is Number)
        assertEquals(6000, (duration as Number).toInt())

        // 缺陷 C：真换行保留、正则 \s 不被二次转义
        val script = values["script"] as String
        assertTrue("真换行必须保留", script.contains('\n'))
        assertTrue("正则 \\s 必须原样", script.contains("/\\s+/"))
        assertFalse("正则不能被二次转义成 \\\\s", script.contains("/\\\\s+/"))
    }

    @Test
    fun normalizeParameterPatchJson_nullValueSurvivesForDeleteSemantics() {
        // 「传 null = 删键」依赖 null 能活着穿过归一化
        val normalized = normalizeParameterPatchJson("""{"content":null}""")

        assertNotNull(normalized)
        assertTrue(normalized!!.containsKey("content"))
        assertNull(normalized["content"])
    }

    @Test
    fun normalizeParameterPatchJson_blankOrNonObjectReturnsNull() {
        // 返回 null 表示「本次不改参数」，调用方据此保持原值
        assertNull(normalizeParameterPatchJson(null))
        assertNull(normalizeParameterPatchJson(""))
        assertNull(normalizeParameterPatchJson("  "))
        assertNull("数组不是合法补丁", normalizeParameterPatchJson("""["a"]"""))
    }
}
