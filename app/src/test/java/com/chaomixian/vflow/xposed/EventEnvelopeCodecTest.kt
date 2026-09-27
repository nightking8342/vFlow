package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 统一信封的编解码测试。
 *
 * 这是 fork 新增的 Xposed 通道的**共享 wire 层**（hook 层与 App 同一份实现）。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.2。
 *
 * ⚠️ 这些测试锁的是**「改错了不报错、只静默变差」**的地方：
 * 编码不对称的表现是「hook 层看着发出去了，App 侧收不到」。
 */
class EventEnvelopeCodecTest {

    @Test
    fun `encode then decode round-trips every field`() {
        val json = EventEnvelopeCodec.encode(
            topic = "hook.activity.changed",
            seq = 42L,
            ts = 1_700_000_000_000L,
            payloadJson = """{"package_name":"bin.mt.plus"}""",
            droppedCount = 7L,
            token = "abc123",
            protocolVersion = 1,
        )

        val decoded = EventEnvelopeCodec.decode(json)
        assertNotNull(decoded)
        assertEquals("hook.activity.changed", decoded!!.topic)
        assertEquals(42L, decoded.seq)
        assertEquals(1_700_000_000_000L, decoded.ts)
        assertEquals("""{"package_name":"bin.mt.plus"}""", decoded.payloadJson)
        assertEquals(7L, decoded.droppedCount)
        assertEquals("abc123", decoded.token)
        assertEquals(1, decoded.protocolVersion)
    }

    @Test
    fun `payload is carried verbatim without re-parsing`() {
        // 信封层**不认识任何业务字段** —— payload 原样搬运。
        // 若这里被「帮忙」解析成对象再序列化，键顺序/转义都可能变，
        // 而下游 topic schema 是按原字符串解析的
        val weird = """{"a":"中文 \\" 引号","b":[1,2,3],"c":null}"""
        val decoded = EventEnvelopeCodec.decode(
            EventEnvelopeCodec.encode("t", 1, 1, weird)
        )
        assertEquals(weird, decoded!!.payloadJson)
    }

    @Test
    fun `decode returns null for malformed json instead of throwing`() {
        // ⚠️ 解码发生在 App 侧的 binder 线程上。抛异常会跨国界且无人处理。
        // 「未知/坏格式必须忽略而非崩溃」是 §3.4.5 的硬要求
        assertNull(EventEnvelopeCodec.decode("not json at all"))
        assertNull(EventEnvelopeCodec.decode(""))
        assertNull(EventEnvelopeCodec.decode("{"))
        assertNull(EventEnvelopeCodec.decode("null"))
    }

    @Test
    fun `decode returns null when topic is missing or blank`() {
        // topic 是路由依据，没有它这条信封无法处理 —— 必须拒绝而不是猜
        assertNull(EventEnvelopeCodec.decode("""{"seq":1}"""))
        assertNull(EventEnvelopeCodec.decode("""{"topic":""}"""))
        assertNull(EventEnvelopeCodec.decode("""{"topic":"   "}"""))
    }

    @Test
    fun `missing seq decodes to minus one not zero`() {
        // ⚠️ 这条是**丢包检测**的前提：
        // 0 是一个合法的首个序号，用 0 表示「缺失」会让两者混同，
        // 于是丢包检测在第一条事件上就误报（或永远不报）
        val decoded = EventEnvelopeCodec.decode("""{"topic":"t"}""")
        assertEquals(-1L, decoded!!.seq)
    }

    @Test
    fun `unknown topic is preserved for the caller to ignore`() {
        // 「旧 App + 新 hook 层」时会出现未知 topic。
        // 解码层**不该替调用方决定丢弃** —— 它只管搬运，忽略由上层做
        val decoded = EventEnvelopeCodec.decode("""{"topic":"hook.future.thing","seq":1}""")
        assertEquals("hook.future.thing", decoded!!.topic)
    }

    @Test
    fun `decoding ignores unknown extra fields for forward compatibility`() {
        // 「只加不改不删」：新 hook 层加字段，旧 App 必须照常能解析
        val decoded = EventEnvelopeCodec.decode(
            """{"topic":"t","seq":5,"brand_new_future_field":{"nested":true}}"""
        )
        assertNotNull(decoded)
        assertEquals(5L, decoded!!.seq)
    }

    @Test
    fun `encode always emits a protocol version`() {
        // 版本错配是常态（hook 层与 App 生命周期独立，§3.4.5），
        // 所以信封里**必须**始终带版本号，不能省
        val obj = JSONObject(EventEnvelopeCodec.encode("t", 1, 1, "{}"))
        assertTrue(obj.has(EventEnvelopeCodec.KEY_PROTOCOL))
        assertEquals(EventEnvelopeCodec.PROTOCOL_VERSION, obj.getInt(EventEnvelopeCodec.KEY_PROTOCOL))
    }

    @Test
    fun `encode emits token field even when empty`() {
        // 未拿到 token 时 hook 层**不该发**（emit 里已拦），
        // 但编码层仍要保证字段存在 —— App 侧靠「token 字段=空」判伪
        val obj = JSONObject(EventEnvelopeCodec.encode("t", 1, 1, "{}", token = ""))
        assertTrue(obj.has(EventEnvelopeCodec.KEY_TOKEN))
        assertFalse(EventEnvelopeCodec.decode(obj.toString())!!.token.isNotEmpty())
    }
}
