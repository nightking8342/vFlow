package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.ActivityPayload
import com.chaomixian.vflow.xposed.wire.ExtrasJsonCodec
import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * extras 编码层（[ExtrasJsonCodec]）的单测。
 *
 * ## 为什么这个文件要有（它是从 `ActivityPayloadTest` 平移过来的）
 *
 * 编码实现**从 `ActivityPayload` 提取**到了 `ExtrasJsonCodec`，两处（hook 链路 +
 * 广播触发器）共用同一份。提取的动机是**避免双份实现静默漂移**（`FORK.md` 记过
 * logcat 双份实现的代价），但提取本身有风险：**原测试全是经 `ActivityPayload.encode`
 * 间接覆盖的**，一旦实现搬走而测试不动，覆盖就落在了一个「只是委托」的壳上 ——
 * 提取时的任何笔误都不会变红。
 *
 * ⇒ 本文件**直调 `ExtrasJsonCodec.encode` / `truncateToBytes`**，把字节口径的
 * 反向断言原样带过来。`ActivityPayloadTest` 保留（它测的是整条载荷的组装），
 * 两者不重复：那边锁「extras 装进载荷后仍对」，这边锁「编码层自身对」。
 *
 * ## 重点锁「改错了不报错、只静默变差」的地方
 *
 * ① 类型信息不能丢（`dumpsys` 就是这么把米家的 String 猜成 Long 的）；
 * ② 超长必须**产出合法 JSON**（截一半 JSON 无法解析 ⇒ 整条 extras 都拿不到）；
 * ③ 字节口径而非字符口径（全 CJK 时字符数只是真实体积的 1/3）；
 * ④ 截断按**码点**推进（按 `Char` 推进会低估 emoji 一半，上限形同虚设）。
 */
class ExtrasJsonCodecTest {

    private val budget = ActivityPayload.MAX_EXTRAS_JSON_BYTES

    private fun encode(extras: Map<String, Any?>, maxBytes: Int = budget) =
        ExtrasJsonCodec.encode(extras, maxBytes)

    // ═══ 类型保真 ═══

    @Test
    fun `extras keep their real types so string one is not guessed as number`() {
        // ⚠️⚠️ 这是本层最重要的语义，也是 dumpsys 那条路的失败原因：
        // 米家快捷方式「无账号权限」的真因就是 String 被猜成了 Long。
        // 本层拿到的是真实 Object，所以「不猜」是能做到的 —— 关键是编码时别 toString。
        val r = encode(
            mapOf(
                "str_num" to "1",
                "int_num" to 1,
                "long_num" to 1L,
                "bool" to true,
            )
        )

        val extras = JSONObject(r.json)
        assertTrue("字符串 1 必须是 JSON 字符串", extras.get("str_num") is String)
        assertEquals("1", extras.getString("str_num"))
        assertTrue("整数 1 必须是 JSON 数字", extras.get("int_num") is Number)
        assertEquals(1L, (extras.get("int_num") as Number).toLong())
        assertEquals(1L, (extras.get("long_num") as Number).toLong())
        assertEquals(true, extras.getBoolean("bool"))
        assertFalse(r.truncated)
    }

    @Test
    fun `null extras value is preserved as explicit null not dropped`() {
        // ⚠️ Kotlin null ≠ JSONObject.NULL：前者在 org.json 里是「删掉这个键」。
        // 用错的话，「值为 null 的键」会静默消失，下游分不清
        // 「键不存在」与「键存在但值是 null」
        val extras = JSONObject(encode(mapOf("maybe" to null)).json)
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
        val extras = JSONObject(encode(mapOf("obj" to custom)).json)

        assertTrue("键必须保留", extras.has("obj"))
        assertTrue(
            "应记录类型名供排查，实际=${extras.getString("obj")}",
            extras.getString("obj").contains("unencodable"),
        )
    }

    @Test
    fun `primitive arrays are preserved as json arrays`() {
        // 数组不该被降级成 "…" 字符串 —— 那会丢掉「几个元素」的信息
        val extras = JSONObject(
            encode(
                mapOf("ints" to intArrayOf(1, 2, 3), "bools" to booleanArrayOf(true, false))
            ).json
        )
        assertEquals(3, extras.getJSONArray("ints").length())
        assertEquals(2, extras.getJSONArray("bools").length())
    }

    @Test
    fun `NaN and Infinity are not encoded as numbers`() {
        // ⚠️ NaN / Infinity 不是合法 JSON。org.json 会把它们写成字符串，
        // 那会让「本来是数字的键」静默变成字符串 —— 类型语义被破坏
        val extras = JSONObject(
            encode(mapOf("nan" to Double.NaN, "inf" to Double.POSITIVE_INFINITY, "ok" to 1.5)).json
        )
        assertTrue("NaN 不该被当成数字写出去", extras.get("nan") !is Number)
        assertTrue("Infinity 不该被当成数字写出去", extras.get("inf") !is Number)
        assertEquals(1.5, (extras.get("ok") as Number).toDouble(), 0.0001)
    }

    // ═══ 截断 ═══

    @Test
    fun `empty extras encode to an empty json object`() {
        val r = encode(emptyMap())
        assertEquals("{}", r.json)
        assertFalse(r.truncated)
    }

    @Test
    fun `oversized extras are truncated but json stays parseable`() {
        // ⚠️⚠️ 关键语义：**截断后必须是合法 JSON**。
        // 「先拼好再按长度砍」会得到半截 JSON ⇒ 下游 JSONObject() 抛 ⇒
        // **整条 extras 都拿不到**（而不是「少几个键」）。
        val big = buildMap {
            repeat(200) { i -> put("key_$i", "v".repeat(2000)) }
        }
        val r = encode(big, maxBytes = 4 * 1024)

        assertTrue("必须标记为已截断", r.truncated)
        val extras = JSONObject(r.json) // 不抛即证明是合法 JSON
        assertTrue("应保留一部分键", extras.length() > 0)
        assertTrue("应丢掉一部分键", extras.length() < 200)
        assertTrue(
            "extras 不应超过预算，实际=${ResultBudget.byteSizeOf(r.json)}",
            ResultBudget.byteSizeOf(r.json) <= 4 * 1024 + 64,
        )
    }

    @Test
    fun `the budget parameter actually bounds the output`() {
        // ⚠️ 反向锁：预算必须是**参数**而不是写死常量 ——
        // 提取时若把 `maxBytes` 悄悄换回 `MAX_EXTRAS_JSON_BYTES`，
        // 同一份输入在小预算下就会超限，而那是**静默的**
        val big = buildMap {
            repeat(50) { i -> put("k_$i", "x".repeat(500)) }
        }
        val small = encode(big, maxBytes = 1024)
        assertTrue(small.truncated)
        assertTrue(
            "小预算下输出必须落在小预算内，实际=${ResultBudget.byteSizeOf(small.json)}",
            ResultBudget.byteSizeOf(small.json) <= 1024 + 64,
        )
    }

    // ═══ 字节口径的反向断言（§3.6 末的既有缺陷修正）═══════
    //
    // ⚠️⚠️ 这几条锁的是一个**真实缺陷**：原实现按【字符】计预算
    // （`MAX_*_CHARS` + `String.length`），而 §3.6 的契约按【字节】立。
    // 全 CJK 时字符数只是真实体积的 1/3 ⇒ 192K 字符 ≈ 576 KiB
    // ⇒ 超 oneway 半缓冲（≈508 KiB）⇒ **整条事件被静默丢弃**。
    // 表现是「打开某些 App 不触发、换一个就正常」。
    //
    // 反证：把 `encode` 里的 cost 改回 `probeStr.length - 2` ⇒ 下面至少一条变红。

    @Test
    fun `cjk extras stay within the byte budget`() {
        // 纯中文载荷：每个字 3 字节。用「字符数远小于预算、字节数远超预算」
        // 的构造，确保字符口径**必然**判错。
        val big = buildMap {
            repeat(200) { i -> put("k_$i", "中".repeat(400)) }
        }
        val r = encode(big)
        val bytes = ResultBudget.byteSizeOf(r.json)

        assertTrue("CJK 载荷必须被截断", r.truncated)
        assertTrue("CJK extras 字节数 $bytes 超预算 $budget", bytes <= budget + 64)
        // ⚠️ 反向：字符数**确实**在预算内 —— 证明这条用例能区分两种口径
        assertTrue(
            "字符数确实小于预算（所以字符口径会误判为没超）",
            r.json.length < budget,
        )
    }

    @Test
    fun `emoji extras stay within the byte budget`() {
        // emoji 通常 4 字节/字符 —— 比 CJK 更极端
        val big = buildMap {
            repeat(200) { i -> put("k_$i", "😀".repeat(300)) }
        }
        val r = encode(big)
        val bytes = ResultBudget.byteSizeOf(r.json)

        assertTrue(r.truncated)
        assertTrue("emoji extras 字节数 $bytes 超预算", bytes <= budget + 64)
    }

    // ═══ truncateToBytes：按码点推进 ═══

    @Test
    fun `truncateToBytes counts bytes not chars`() {
        // 「中」是 3 字节。若按字符取（`take(n)`）会得到 3 倍预算 —— 等于没截
        val text = "中".repeat(100)
        val out = ExtrasJsonCodec.truncateToBytes(text, 30)
        assertEquals(30, ResultBudget.byteSizeOf(out))
        assertEquals(10, out.length)
    }

    @Test
    fun `truncateToBytes never splits a multibyte character`() {
        // ⚠️ 不能简单按字节数组切：从中间切开多字节字符会产出非法 UTF-8，
        // 下游得到替换字符（�）甚至解析失败
        val text = "中".repeat(100)
        // 31 字节不是 3 的倍数 ⇒ 必然会「差 1 字节」而不能切半个字符
        val out = ExtrasJsonCodec.truncateToBytes(text, 31)
        assertFalse("不得出现替换字符", out.contains('�'))
        assertEquals(30, ResultBudget.byteSizeOf(out))
        // 往返仍相等 ⇒ 是合法 UTF-8
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
    }

    @Test
    fun `truncateToBytes advances by code point so emoji are not undercounted`() {
        // ⚠️⚠️ **这条是「按码点推进」的守门人，必须用 emoji 做载荷**。
        //
        // 按 `Char` 推进时，一个 emoji（代理对，两个 Char）会被算成 1 + 1 = 2 字节
        // （孤立代理编码成 UTF-8 只占 1 字节），而它实际是 4 字节
        // ⇒ 预算低估一半 ⇒ **截断完全没生效**，且是静默的。
        //
        // ⚠️ 「中」在 BMP 内只占**一个** Char，恰好绕过这条路径 ——
        // 用它做载荷时，把实现改回按 Char 推进**测试依然全绿**，等于没有守卫。
        val emoji = String(Character.toChars(0x1F600)) // 😀，UTF-8 占 4 字节
        val limit = 4 * 1024
        val out = ExtrasJsonCodec.truncateToBytes(emoji.repeat(limit), limit)

        assertFalse("不得切碎多字节字符", out.contains('�'))
        assertEquals(out, String(out.toByteArray(Charsets.UTF_8), Charsets.UTF_8))
        // ⚠️ **真正会红的断言**：字节数必须落在上限内。
        // 只断言「没替换字符 / 往返相等」是**不够的** —— 按 Char 推进时两个代理
        // 会被一起 break 掉，那两条断言照样通过，而实际字节数是上限的 **2 倍**。
        val bytes = ResultBudget.byteSizeOf(out)
        assertTrue("emoji 载荷的字节数 $bytes 必须不超上限 $limit", bytes <= limit)
        // 再反向核一次「数量对得上」：正好装下 limit / 4 个 emoji
        assertEquals(limit, bytes)
    }

    @Test
    fun `truncateToBytes is identity when already within budget`() {
        val text = "abc"
        assertEquals(text, ExtrasJsonCodec.truncateToBytes(text, 100))
    }

    @Test
    fun `truncateToBytes handles degenerate limits`() {
        // 预算为 0 / 负数时不该抛 —— 边界值在生产里可能来自配置
        assertEquals("", ExtrasJsonCodec.truncateToBytes("abc", 0))
        assertEquals("", ExtrasJsonCodec.truncateToBytes("abc", -5))
        // 预算比首个码点还小时同理（宁可返回空，也不要切碎）
        assertEquals("", ExtrasJsonCodec.truncateToBytes("中", 2))
    }
}
