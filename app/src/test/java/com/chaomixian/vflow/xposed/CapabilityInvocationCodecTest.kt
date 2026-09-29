package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ③ 调用信封编解码测试。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.3 / §3.4。
 *
 * 重点锁**「改错了不报错、只静默变差」**的地方：
 * ① 坏 JSON 必须返回 null 而非抛（调用方在 binder 线程上，抛异常无人处理）；
 * ② `ok=false` 却没有 `error` 必须判为坏信封（否则「失败但没原因」会被放行）；
 * ③ 未知 capability **不能被信封层吞掉**（它要留给上层显式报错，§4.3）；
 * ④ 两个信封都带 token（响应不带 = 伪造面）；
 * ⑤ 协议版本与 `EventEnvelopeCodec` **同源**（§5.5 版本号收敛）。
 */
class CapabilityInvocationCodecTest {

    @Test
    fun `request round-trips all fields`() {
        val json = CapabilityInvocationCodec.encodeRequest(
            requestId = "req-1",
            capability = "query_shortcut_intents",
            paramsJson = """{"package_name":"com.xiaomi.mihome"}""",
            timeoutMs = 3000L,
            token = "tok-abc",
        )

        val req = CapabilityInvocationCodec.decodeRequest(json)
        assertNotNull(req)
        assertEquals("req-1", req!!.requestId)
        assertEquals("query_shortcut_intents", req.capability)
        assertEquals(3000L, req.timeoutMs)
        assertEquals("tok-abc", req.token)
        // params 保持原始串 —— 信封层不认识业务字段
        assertTrue(req.paramsJson.contains("com.xiaomi.mihome"))
        assertEquals(EventEnvelopeCodec.PROTOCOL_VERSION, req.protocolVersion)
    }

    @Test
    fun `response round-trips success`() {
        val json = CapabilityInvocationCodec.encodeResponse(
            requestId = "req-2",
            ok = true,
            resultJson = """{"count":3}""",
            elapsedMs = 42L,
            token = "tok-abc",
        )

        val resp = CapabilityInvocationCodec.decodeResponse(json)
        assertNotNull(resp)
        assertEquals("req-2", resp!!.requestId)
        assertTrue(resp.ok)
        assertNull("成功时不该有 error", resp.error)
        assertEquals(42L, resp.elapsedMs)
        assertEquals("tok-abc", resp.token)
        assertTrue(resp.resultJson.contains("\"count\""))
    }

    @Test
    fun `response round-trips failure with error code`() {
        val json = CapabilityInvocationCodec.encodeResponse(
            requestId = "req-3",
            ok = false,
            error = CapabilityError(CapabilityErrorCode.TIMEOUT, "等了 5 秒没回"),
            token = "t",
        )

        val resp = CapabilityInvocationCodec.decodeResponse(json)
        assertNotNull(resp)
        assertFalse(resp!!.ok)
        val error = resp.error
        assertNotNull(error)
        assertEquals(CapabilityErrorCode.TIMEOUT, error!!.code)
        assertEquals("等了 5 秒没回", error.detail)
    }

    @Test
    fun `success response does not carry an error key`() {
        // ⚠️ 成功时**不写** error 键（而不是写 null）——
        // 「不存在的键」比「值为 null 的键」更明确
        val json = CapabilityInvocationCodec.encodeResponse("r", ok = true, token = "t")
        assertFalse(JSONObject(json).has(CapabilityInvocationCodec.KEY_ERROR))
    }

    @Test
    fun `failure response always carries an error even if caller passed null`() {
        // ⚠️⚠️ §3.3 的硬要求「ok=false 必须带 error，绝不静默」。
        // 调用方忘了传时给一个兜底 code，而不是写 "error":null ——
        // 后者会让 decodeResponse 返回 null（坏信封），
        // 把一次**真实失败**降级成「协议错误」，掩盖真正原因。
        val json = CapabilityInvocationCodec.encodeResponse("r", ok = false, error = null, token = "t")
        val obj = JSONObject(json)
        assertTrue("必须有 error 对象", obj.has(CapabilityInvocationCodec.KEY_ERROR))
        assertEquals(
            CapabilityErrorCode.HANDLER_ERROR.wire,
            obj.getJSONObject(CapabilityInvocationCodec.KEY_ERROR)
                .getString(CapabilityInvocationCodec.KEY_ERROR_CODE),
        )
    }

    @Test
    fun `failure without error is rejected as a bad envelope`() {
        // ⚠️⚠️ 这是本文件最要紧的反向断言：
        // 「失败但不知道原因」与「没收到响应」在排查上必须能区分。
        // 放行一个没有原因的失败 = 让「ok=false 必须带 error」形同虚设。
        val bad = """{"request_id":"r","ok":false,"token":"t"}"""
        assertNull(CapabilityInvocationCodec.decodeResponse(bad))

        // error 对象存在但没有 code，同样判坏
        val noCode = """{"request_id":"r","ok":false,"error":{"detail":"x"},"token":"t"}"""
        assertNull(CapabilityInvocationCodec.decodeResponse(noCode))

        // error 是 null 而不是对象，同样判坏
        val nullError = """{"request_id":"r","ok":false,"error":null,"token":"t"}"""
        assertNull(CapabilityInvocationCodec.decodeResponse(nullError))
    }

    @Test
    fun `unknown error code falls back to handler_error not to a bad envelope`() {
        // ⚠️ 新 hook 层引入未知码时，旧 App 必须**能拿到它是个失败**。
        // 返回 null 会把「未知码」变成「坏信封」⇒ 走「协议错误」的提示路径
        // ⇒ 把排查引向错误方向。所以回落 + 把原始串塞进 detail（detail 不参与判断）。
        val json = """{"request_id":"r","ok":false,""" +
            """"error":{"code":"brand_new_code","detail":"x"},"token":"t"}"""
        val resp = CapabilityInvocationCodec.decodeResponse(json)
        assertNotNull(resp)
        val error = resp!!.error
        assertNotNull(error)
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, error!!.code)
        assertTrue("原始码应留在 detail 供人排查", error.detail.contains("brand_new_code"))
    }

    @Test
    fun `bad json returns null instead of throwing`() {
        // 调用方在 binder 线程上，抛异常跨国界且无人处理
        assertNull(CapabilityInvocationCodec.decodeRequest("nonsense"))
        assertNull(CapabilityInvocationCodec.decodeRequest(""))
        assertNull(CapabilityInvocationCodec.decodeRequest("{"))
        assertNull(CapabilityInvocationCodec.decodeResponse("nonsense"))
        assertNull(CapabilityInvocationCodec.decodeResponse(""))
        assertNull(CapabilityInvocationCodec.decodeResponse("{"))
    }

    @Test
    fun `request missing request_id or capability is rejected`() {
        // 没有 request_id 就无法配对；没有 capability 就不知道要做什么
        assertNull(CapabilityInvocationCodec.decodeRequest("""{"capability":"x"}"""))
        assertNull(CapabilityInvocationCodec.decodeRequest("""{"request_id":"r"}"""))
        assertNull(CapabilityInvocationCodec.decodeRequest("""{"request_id":"","capability":"x"}"""))
        assertNull(CapabilityInvocationCodec.decodeRequest("""{"request_id":"r","capability":""}"""))
    }

    @Test
    fun `unknown capability is preserved for the upper layer to reject`() {
        // ⚠️⚠️ 信封层**不得**吞掉未知 capability（§4.3）：
        // 「未知 topic 忽略」与「未知 capability 必须报错」方向相反 ——
        // 调用方在等结果，静默会让它白等超时、把排查引向错误方向。
        val json = CapabilityInvocationCodec.encodeRequest(
            requestId = "r",
            capability = "capability_that_does_not_exist",
        )
        val req = CapabilityInvocationCodec.decodeRequest(json)
        assertNotNull("信封层必须把它解出来交给上层", req)
        assertEquals("capability_that_does_not_exist", req!!.capability)
    }

    @Test
    fun `protocol version defaults to the shared constant`() {
        // §5.5 版本号收敛：③ 的信封与 EventEnvelope **必须是同一处常量**
        val req = CapabilityInvocationCodec.decodeRequest(
            CapabilityInvocationCodec.encodeRequest("r", "c"),
        )
        assertEquals(EventEnvelopeCodec.PROTOCOL_VERSION, req!!.protocolVersion)

        val resp = CapabilityInvocationCodec.decodeResponse(
            CapabilityInvocationCodec.encodeResponse("r", ok = true),
        )
        assertEquals(EventEnvelopeCodec.PROTOCOL_VERSION, resp!!.protocolVersion)
    }

    @Test
    fun `missing protocol version decodes to minus one not zero`() {
        // ⚠️ 0 是一个**合法的协议版本**，用 0 会让「缺失」与「版本 0」混同
        //（照 EventEnvelopeCodec 的 seq 先例）
        val req = CapabilityInvocationCodec.decodeRequest("""{"request_id":"r","capability":"c"}""")
        assertEquals(-1, req!!.protocolVersion)

        val resp = CapabilityInvocationCodec.decodeResponse("""{"request_id":"r","ok":true}""")
        assertEquals(-1, resp!!.protocolVersion)
    }

    @Test
    fun `missing timeout decodes to the default not to zero`() {
        // ⚠️ 0 意味着「立刻超时」⇒ 一个正常请求会必然失败
        val req = CapabilityInvocationCodec.decodeRequest("""{"request_id":"r","capability":"c"}""")
        assertEquals(CapabilityInvocationCodec.DEFAULT_TIMEOUT_MS, req!!.timeoutMs)

        // 显式给了 0 才用 0（钳到非负，负值不产生荒谬的计时结果）
        val zero = CapabilityInvocationCodec.decodeRequest(
            """{"request_id":"r","capability":"c","timeout_ms":0}""",
        )
        assertEquals(0L, zero!!.timeoutMs)

        val negative = CapabilityInvocationCodec.decodeRequest(
            """{"request_id":"r","capability":"c","timeout_ms":-5}""",
        )
        assertEquals(0L, negative!!.timeoutMs)
    }

    @Test
    fun `ok missing decodes as failure not as success`() {
        // ⚠️ 默认按失败处理：一次「假成功」比一次显式失败难查得多。
        // 缺 ok 时走到 error 校验 ⇒ 没有 error ⇒ 坏信封
        assertNull(CapabilityInvocationCodec.decodeResponse("""{"request_id":"r","token":"t"}"""))
    }

    @Test
    fun `both envelopes carry the token`() {
        // ⚠️⚠️ §3.3 定案：**两个信封都带 token**。
        // 响应不带凭证 = 任何能 bind 到 HookChannelService 的进程都能伪造返回值，
        // 而那个值会被写进工作流 —— 比伪造事件更危险。
        val req = CapabilityInvocationCodec.decodeRequest(
            CapabilityInvocationCodec.encodeRequest("r", "c", token = "secret"),
        )
        assertEquals("secret", req!!.token)

        val resp = CapabilityInvocationCodec.decodeResponse(
            CapabilityInvocationCodec.encodeResponse("r", ok = true, token = "secret"),
        )
        assertEquals("secret", resp!!.token)
    }

    @Test
    fun `params falls back to empty object when blank`() {
        // params 缺失时给 "{}" —— 一个合法的空 JSON 对象，
        // 而不是空串（后者会让下游 JSONObject() 抛）
        val req = CapabilityInvocationCodec.decodeRequest(
            """{"request_id":"r","capability":"c","params":""}""",
        )
        assertEquals("{}", req!!.paramsJson)
        JSONObject(req.paramsJson)   // 必须能解析，不抛
    }

    @Test
    fun `result falls back to empty object when blank`() {
        val resp = CapabilityInvocationCodec.decodeResponse(
            """{"request_id":"r","ok":true,"result":"","token":"t"}""",
        )
        assertEquals("{}", resp!!.resultJson)
        JSONObject(resp.resultJson)
    }

    @Test
    fun `key names are stable`() {
        // ⚠️ 键名是**跨进程协议的一部分**，一经发布不要改 ——
        // 改了会让新旧两端静默对不上（旧端解不出 request_id ⇒ 判为坏信封 ⇒ 丢弃）
        assertEquals("request_id", CapabilityInvocationCodec.KEY_REQUEST_ID)
        assertEquals("protocol_version", CapabilityInvocationCodec.KEY_PROTOCOL)
        assertEquals("capability", CapabilityInvocationCodec.KEY_CAPABILITY)
        assertEquals("params", CapabilityInvocationCodec.KEY_PARAMS)
        assertEquals("timeout_ms", CapabilityInvocationCodec.KEY_TIMEOUT_MS)
        assertEquals("token", CapabilityInvocationCodec.KEY_TOKEN)
        assertEquals("ok", CapabilityInvocationCodec.KEY_OK)
        assertEquals("result", CapabilityInvocationCodec.KEY_RESULT)
        assertEquals("error", CapabilityInvocationCodec.KEY_ERROR)
        assertEquals("code", CapabilityInvocationCodec.KEY_ERROR_CODE)
        assertEquals("detail", CapabilityInvocationCodec.KEY_ERROR_DETAIL)
        assertEquals("elapsed_ms", CapabilityInvocationCodec.KEY_ELAPSED_MS)
    }

    @Test
    fun `envelope is not event envelope`() {
        // ⚠️ §3.3：「绝不复用 EventEnvelope（它含 seq/dropped，与请求-响应语义相反）」
        // 这条断言防的是「有人图省事把 seq 塞进来」
        val req = JSONObject(CapabilityInvocationCodec.encodeRequest("r", "c"))
        assertFalse("调用信封不该有 seq", req.has(EventEnvelopeCodec.KEY_SEQ))
        assertFalse("调用信封不该有 dropped", req.has(EventEnvelopeCodec.KEY_DROPPED))
        assertFalse("调用信封不该有 topic", req.has(EventEnvelopeCodec.KEY_TOPIC))
    }
}
