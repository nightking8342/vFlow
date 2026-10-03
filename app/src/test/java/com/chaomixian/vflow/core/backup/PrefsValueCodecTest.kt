// 文件: test/java/com/chaomixian/vflow/core/backup/PrefsValueCodecTest.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PrefsValueCodec] 的纯 JVM 测试。
 *
 * ## ⚠️ 这批断言守的是什么
 *
 * `SharedPreferences` 有六种值类型，而 JSON 只有**一种**数字类型。
 * 不带类型标签的话，恢复后 `getInt` / `getFloat` 会拿到 `Double`
 * ⇒ **运行时 `ClassCastException`**，而导出侧一切正常
 * （用户看到的是「恢复成功了，但一打开就崩」）。
 *
 * ⚠️ 另一类同样致命的是**精度**：`1.5f` 经 `Double` 往返会变成 `1.5000001`。
 * 故数字一律按**字符串**编码。
 */
class PrefsValueCodecTest {

    // ── 逐类型往返 ──────────────────────────────────────────

    @Test
    fun `string round trips`() {
        val value = "密码 with 中文 and emoji 🔐 and \"quotes\" and \n newline"
        assertEquals(value, roundTrip(value))
    }

    @Test
    fun `boolean round trips`() {
        assertEquals(true, roundTrip(true))
        assertEquals(false, roundTrip(false))
    }

    @Test
    fun `int round trips as Int not Double`() {
        val decoded = roundTrip(42)
        assertEquals("必须是 Int（JSON 走裸数字会变成 Double）", 42, decoded)
        assertTrue("解码结果必须是 Int 实例，实际 ${decoded!!::class.java}", decoded is Int)
    }

    @Test
    fun `long round trips as Long not Double`() {
        // ⚠️ 用超过 Int 范围的值 —— 否则「Long 被压成 Int」这种缺陷测不出来。
        val value = 9_000_000_000L
        val decoded = roundTrip(value)
        assertEquals(value, decoded)
        assertTrue("解码结果必须是 Long 实例，实际 ${decoded!!::class.java}", decoded is Long)
    }

    @Test
    fun `float round trips without precision drift`() {
        // ⚠️⚠️ 本用例是「数字走字符串编码」这一决策的靶子。
        //     裸存 JSON number 的话，1.5f → Double → Float 会变成 1.5000001。
        val value = 1.5f
        val decoded = roundTrip(value)
        assertEquals("1.5f 经往返必须逐位相等", value, decoded)
        assertTrue("解码结果必须是 Float 实例，实际 ${decoded!!::class.java}", decoded is Float)
    }

    @Test
    fun `string set round trips and loses duplicates`() {
        val value = setOf("a", "b", "中文")
        val decoded = roundTrip(value)
        assertEquals(value, decoded)
        assertTrue(decoded is Set<*>)
    }

    @Test
    fun `an empty string set round trips to an empty set not null`() {
        // ⚠️ 「空集合」与「键不存在」是两件事：前者要恢复成 `emptySet()`，
        //     后者由调用方根本不写这个键来表达。混同会让用户看到
        //     「恢复成功但那个键没了」。
        val decoded = roundTrip(emptySet<String>())
        assertEquals(emptySet<String>(), decoded)
    }

    // ── 不支持 / 异常输入 ───────────────────────────────────

    @Test
    fun `unsupported types encode to null instead of throwing`() {
        // 备份是用户数据路径，一个陌生类型不该让整次导出失败。
        assertNull(PrefsValueCodec.encode(null))
        assertNull(PrefsValueCodec.encode(3.14)) // Double：SharedPreferences 不支持
        assertNull(PrefsValueCodec.encode(listOf("a")))
        assertNull(PrefsValueCodec.encode(mapOf("k" to "v")))
    }

    @Test
    fun `a set with non string members encodes to null`() {
        // ⚠️ 静默转成字符串比跳过更危险（用户会拿到 "[1, 2]" 这样的值）。
        assertNull(PrefsValueCodec.encode(setOf(1, 2)))
        assertNull(PrefsValueCodec.encode(setOf("ok", 2)))
    }

    @Test
    fun `unknown type tag decodes to null without throwing`() {
        // 前向兼容：更新的版本写进来的新标签，旧版本必须安静跳过。
        val future = JsonObject().apply {
            addProperty("t", "future_type")
            addProperty("v", "whatever")
        }
        assertNull(PrefsValueCodec.decode(future))
    }

    @Test
    fun `malformed nodes decode to null without throwing`() {
        assertNull(PrefsValueCodec.decode(null))
        assertNull(PrefsValueCodec.decode(JsonPrimitive("not an object")))
        assertNull(PrefsValueCodec.decode(JsonObject()))                                   // 无 t/v
        assertNull(PrefsValueCodec.decode(JsonObject().apply { addProperty("t", "i") }))   // 无 v
    }

    @Test
    fun `a number tag whose payload is not a string decodes to null`() {
        // ⚠️⚠️ `JsonPrimitive.asString` 对 number 也**不抛**（返回 toString），
        //     于是 `{"t":"i","v":123}` 这种非法形状会被安静地解成 123，
        //     掩盖了「写侧没按规约编码」这件事。必须显式判 isString。
        val illegal = JsonObject().apply {
            addProperty("t", "i")
            addProperty("v", 123)   // 规约要求是字符串 "123"
        }
        assertNull(
            "数字标签的载荷必须是字符串；裸数字说明写侧没按规约编码",
            PrefsValueCodec.decode(illegal),
        )
    }

    @Test
    fun `int payload that is not numeric decodes to null`() {
        val bad = JsonObject().apply {
            addProperty("t", "i")
            addProperty("v", "not-a-number")
        }
        assertNull(PrefsValueCodec.decode(bad))
    }

    // ── 整表 ────────────────────────────────────────────────

    @Test
    fun `encodeAll sorts keys so round trips are byte stable`() {
        // ⚠️ `SharedPreferences.all` 的迭代序在不同实现上不保证稳定 ⇒
        //     不排序的话「往返一致」这条断言会偶发红。
        val values = linkedMapOf<String, Any?>(
            "z_key" to 1,
            "a_key" to "x",
            "m_key" to true,
        )
        val encoded = PrefsValueCodec.encodeAll(values)
        assertEquals(listOf("a_key", "m_key", "z_key"), encoded.entrySet().map { it.key })
    }

    @Test
    fun `encodeAll drops unsupported values without dropping supported siblings`() {
        val values = mapOf<String, Any?>(
            "ok" to "v",
            "bad" to 3.14,
            "also_ok" to true,
        )
        val encoded = PrefsValueCodec.encodeAll(values)
        assertEquals(setOf("ok", "also_ok"), encoded.entrySet().map { it.key }.toSet())
    }

    @Test
    fun `whole table round trip preserves every type`() {
        val original: Map<String, Any?> = linkedMapOf(
            "s" to "text",
            "b" to true,
            "i" to 7,
            "l" to 8_000_000_000L,
            "f" to 2.25f,
            "ss" to setOf("x", "y"),
        )
        val encoded = PrefsValueCodec.encodeAll(original)
        // 过一次真实的 JSON 文本，证明编码结果真的是可序列化的 JSON
        val reparsed = JsonParser.parseString(encoded.toString())
        val decoded = PrefsValueCodec.decodeAll(reparsed)

        assertEquals(original.keys, decoded.keys)
        original.forEach { (key, expected) ->
            assertEquals("键 $key 往返不一致", expected, decoded[key])
            assertTrue(
                "键 $key 的类型丢失：期望 ${expected!!::class.java.simpleName}，" +
                    "实际 ${decoded[key]!!::class.java.simpleName}",
                expected::class.java == decoded[key]!!::class.java ||
                    (expected is Set<*> && decoded[key] is Set<*>),
            )
        }
    }

    @Test
    fun `decodeAll on a non object returns an empty map`() {
        assertTrue(PrefsValueCodec.decodeAll(null).isEmpty())
        assertTrue(PrefsValueCodec.decodeAll(JsonArray()).isEmpty())
    }

    @Test
    fun `decodeAll skips undecodable keys and keeps the rest`() {
        val table = JsonObject().apply {
            add("good", PrefsValueCodec.encode("v")!!)
            addProperty("unknown", "not an encoded node")
            add("also_good", PrefsValueCodec.encode(5)!!)
        }
        val decoded = PrefsValueCodec.decodeAll(table)
        assertEquals(setOf("good", "also_good"), decoded.keys)
    }

    // ── 辅助 ────────────────────────────────────────────────

    private fun roundTrip(value: Any?): Any? =
        PrefsValueCodec.decode(PrefsValueCodec.encode(value))
}
