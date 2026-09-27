package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.ActivityPayload
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `activity_changed` 载荷编解码测试。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.1 / §4.3。
 *
 * 重点锁**「改错了不报错、只静默变差」**的地方：
 * ① 类型信息不能丢（`dumpsys` 就是这么把米家的 String 猜成 Long 的）；
 * ② 超长必须**产出合法 JSON**（截一半 JSON 无法解析 ⇒ 整条 extras 都拿不到）；
 * ③ 坏输入返回 null 而非抛（调用方在 binder 线程上）。
 */
class ActivityPayloadTest {

    @Test
    fun `encode then decode round-trips all fields`() {
        val json = ActivityPayload.encode(
            packageName = "io.legado.app.release",
            className = "io.legado.app.ui.book.read.ReadBookActivity",
            intentUri = "intent:#Intent;component=io.legado.app.release/.ReadBookActivity;end",
            extras = mapOf("bookUrl" to "https://aabook.net/book-3237.html"),
        )

        val e = ActivityPayload.decode(json)
        assertNotNull(e)
        assertEquals("io.legado.app.release", e!!.packageName)
        assertEquals("io.legado.app.ui.book.read.ReadBookActivity", e.className)
        assertEquals(
            "io.legado.app.release/io.legado.app.ui.book.read.ReadBookActivity",
            e.component,
        )
        assertTrue(e.intentUri.contains("ReadBookActivity"))
        assertTrue(e.extrasJson.contains("bookUrl"))
        assertFalse(e.truncated)
    }

    @Test
    fun `extras keep their real types so string one is not guessed as number`() {
        // ⚠️⚠️ 这是本层最重要的语义，也是 dumpsys 那条路的失败原因：
        // 米家快捷方式「无账号权限」的真因就是 String 被猜成了 Long。
        // 本层拿到的是真实 Object，所以「不猜」是能做到的 —— 关键是编码时别 toString。
        val json = ActivityPayload.encode(
            packageName = "p",
            className = "c",
            intentUri = null,
            extras = mapOf(
                "str_num" to "1",
                "int_num" to 1,
                "long_num" to 1L,
                "bool" to true,
            ),
        )

        val extras = JSONObject(ActivityPayload.decode(json)!!.extrasJson)
        // 字符串仍是字符串（带引号），数字仍是数字 —— 两者可区分
        assertTrue("字符串 1 必须是 JSON 字符串", extras.get("str_num") is String)
        assertEquals("1", extras.getString("str_num"))
        assertTrue("整数 1 必须是 JSON 数字", extras.get("int_num") is Number)
        assertEquals(1L, (extras.get("int_num") as Number).toLong())
        assertEquals(1L, (extras.get("long_num") as Number).toLong())
        assertEquals(true, extras.getBoolean("bool"))
    }

    @Test
    fun `null extras value is preserved as explicit null not dropped`() {
        // ⚠️ Kotlin null ≠ JSONObject.NULL：前者在 org.json 里是「删掉这个键」。
        // 用错的话，「值为 null 的键」会静默消失，下游分不清
        // 「键不存在」与「键存在但值是 null」
        val json = ActivityPayload.encode("p", "c", null, mapOf("maybe" to null))
        val extras = JSONObject(ActivityPayload.decode(json)!!.extrasJson)
        assertTrue("键必须存在", extras.has("maybe"))
        assertTrue("值必须是 JSON null", extras.isNull("maybe"))
    }

    @Test
    fun `unencodable extras value is replaced by its type name not dropped`() {
        // 自定义 Parcelable 之类无法编码成 JSON。
        // 丢掉键会让用户以为「这个键不存在」；记下类型名至少能排查
        val custom = object {
            override fun toString() = "should-not-be-used"
        }
        val json = ActivityPayload.encode("p", "c", null, mapOf("obj" to custom))
        val extras = JSONObject(ActivityPayload.decode(json)!!.extrasJson)

        assertTrue("键必须保留", extras.has("obj"))
        assertTrue(
            "应记录类型名供排查，实际=${extras.getString("obj")}",
            extras.getString("obj").contains("unencodable"),
        )
    }

    @Test
    fun `oversized extras are truncated but json stays parseable`() {
        // ⚠️⚠️ 关键语义：**截断后必须是合法 JSON**。
        // 「先拼好再按长度砍」会得到半截 JSON ⇒ 下游 JSONObject() 抛 ⇒
        // **整条 extras 都拿不到**（而不是「少几个键」）。
        val big = buildMap {
            repeat(200) { i -> put("key_$i", "v".repeat(2000)) }
        }
        val json = ActivityPayload.encode("p", "c", null, big)

        val decoded = ActivityPayload.decode(json)
        assertNotNull("截断后仍必须能整体解码", decoded)
        assertTrue("必须标记为已截断", decoded!!.truncated)

        // extras 本身也必须是合法 JSON
        val extras = JSONObject(decoded.extrasJson)
        assertTrue("应保留一部分键", extras.length() > 0)
        assertTrue("应丢掉一部分键", extras.length() < 200)
        assertTrue(
            "extras_json 不应超过预算",
            decoded.extrasJson.length <= ActivityPayload.MAX_EXTRAS_JSON_CHARS + 64,
        )
    }

    @Test
    fun `oversized intent uri is truncated and flagged`() {
        val huge = "intent:#Intent;" + "x".repeat(ActivityPayload.MAX_INTENT_URI_CHARS + 1000)
        val decoded = ActivityPayload.decode(
            ActivityPayload.encode("p", "c", huge, emptyMap())
        )
        assertTrue(decoded!!.truncated)
        assertTrue(decoded.intentUri.length <= ActivityPayload.MAX_INTENT_URI_CHARS)
    }

    @Test
    fun `decode returns null for malformed json instead of throwing`() {
        // 调用方在 binder 线程上，抛异常跨国界且无人处理
        assertNull(ActivityPayload.decode("nonsense"))
        assertNull(ActivityPayload.decode(""))
        assertNull(ActivityPayload.decode("{"))
    }

    @Test
    fun `decode returns null when package name is missing`() {
        // 包名是下游判断的基础，没有它这条事件无法使用 —— 拒绝而不是猜
        assertNull(ActivityPayload.decode("""{"class_name":"c"}"""))
        assertNull(ActivityPayload.decode("""{"package_name":""}"""))
    }

    @Test
    fun `component falls back to package name when class name is blank`() {
        // ⚠️ 不要产出 "pkg/" 这种半截 component —— 下游按 component 匹配时会失败
        val decoded = ActivityPayload.decode(
            ActivityPayload.encode("com.foo", "", null, emptyMap())
        )
        assertEquals("com.foo", decoded!!.component)
    }

    @Test
    fun `empty extras encode to an empty json object`() {
        val decoded = ActivityPayload.decode(
            ActivityPayload.encode("p", "c", null, emptyMap())
        )
        assertEquals("{}", decoded!!.extrasJson)
        assertFalse(decoded.truncated)
    }

    @Test
    fun `primitive arrays are preserved as json arrays`() {
        // 数组不该被降级成 "…" 字符串 —— 那会丢掉「几个元素」的信息
        val json = ActivityPayload.encode(
            "p", "c", null,
            mapOf("ints" to intArrayOf(1, 2, 3), "bools" to booleanArrayOf(true, false)),
        )
        val extras = JSONObject(ActivityPayload.decode(json)!!.extrasJson)
        assertEquals(3, extras.getJSONArray("ints").length())
        assertEquals(2, extras.getJSONArray("bools").length())
    }

    @Test
    fun `NaN and Infinity are not encoded as numbers`() {
        // ⚠️ NaN / Infinity 不是合法 JSON。org.json 会把它们写成字符串，
        // 那会让「本来是数字的键」静默变成字符串 —— 类型语义被破坏
        val json = ActivityPayload.encode(
            "p", "c", null,
            mapOf("nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY, "ok" to 1.5),
        )
        val extras = JSONObject(ActivityPayload.decode(json)!!.extrasJson)
        assertTrue("NaN 不该被当成数字写出去", extras.get("nan") !is Number)
        assertTrue("Infinity 不该被当成数字写出去", extras.get("inf") !is Number)
        assertEquals(1.5, (extras.get("ok") as Number).toDouble(), 0.0001)
    }

    @Test
    fun `topic constant is stable`() {
        // ⚠️ topic 是 App 侧路由依据，**一经发布不要改**（§3.4.2 的契约）
        assertEquals("hook.activity.changed", ActivityPayload.TOPIC)
    }
}
