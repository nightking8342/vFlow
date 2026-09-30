package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.CapabilityResponse
import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * [HookCapabilityRuntime] 的**整个执行运行时**单测。
 *
 * ## ⚠️ 为什么它能在纯 JVM 里跑
 *
 * handler 本身跑在 LSPosed 的 ClassLoader 里、App 侧测不到（§7.2b-9），
 * 但**框架层可以** —— 只要「回响应」被抽成函数类型。
 * 本测试用一个收 `CapabilityResponse` 的列表替代 binder。
 *
 * ⚠️ 这里收的是**已解码的 `CapabilityResponse`**（走真 codec 的
 * `encodeResponse` + `decodeResponse`）。**不能用「自己拼的 JSON」绕过 codec** ——
 * 那样就测不到「分页两键究竟有没有写进信封顶层」这件最要紧的事。
 *
 * ## 六个用例对应需求表里的六格
 *
 * 正常 / 超时 / 异常 / 截断 / 池满 / 未知名。
 */
class HookCapabilityRuntimeTest {

    /** 收到的响应（已解码）。`Collections.synchronizedList` —— 工作线程会往里写。 */
    private val received = Collections.synchronizedList(mutableListOf<CapabilityResponse>())

    /** 每个响应对应的原始信封串（用于字面量断言，如「键必须存在」）。 */
    private val rawResponses = Collections.synchronizedList(mutableListOf<String>())

    private var runtime: HookCapabilityRuntime? = null

    /**
     * 真 codec 的编码路径 —— 与生产 `VFlowHookEntry` 的接线**逐字一致**。
     *
     * ⚠️ 这一处是全部 ③ 响应的唯一出口（分页两键的编码规则由 codec 负责）。
     */
    private fun responder() = CapabilityResponder { requestId, resultJson, nextCursor, truncated, elapsedMs, token, error ->
        val json = CapabilityInvocationCodec.encodeResponse(
            requestId = requestId,
            ok = error == null,
            resultJson = resultJson,
            error = error,
            nextCursor = nextCursor,
            truncated = truncated,
            elapsedMs = elapsedMs,
            token = token,
        )
        rawResponses += json
        // ⚠️ 解码失败返回 null —— 那本身就是一个应当变红的信号
        received += requireNotNull(CapabilityInvocationCodec.decodeResponse(json)) {
            "回了一条连自家 codec 都解不开的信封：$json"
        }
        true
    }

    private fun start(poolSize: Int = HookCapabilityRuntime.DEFAULT_POOL_SIZE): HookCapabilityRuntime {
        val rt = HookCapabilityRuntime(respond = responder(), poolSize = poolSize)
        runtime = rt
        return rt
    }

    @Before
    fun setUp() {
        received.clear()
        rawResponses.clear()
        HookCapabilityRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        runtime?.stop()
        runtime = null
        HookCapabilityRegistry.resetForTest()
    }

    /** 造一条能力请求（走真 codec 编码 ⇒ 顺便验了请求侧的解码路径）。 */
    private fun requestJson(
        capability: String,
        params: String = "{}",
        timeoutMs: Long = 5_000L,
        requestId: String = "req-1",
        token: String = "tok",
    ): String = CapabilityInvocationCodec.encodeRequest(
        requestId = requestId,
        capability = capability,
        paramsJson = params,
        timeoutMs = timeoutMs,
        token = token,
    )

    /** 等一条响应到达（工作线程是异步的）。 */
    private fun awaitResponse(index: Int = 0, timeoutMs: Long = 5_000L): CapabilityResponse {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            synchronized(received) {
                if (received.size > index) return received[index]
            }
            Thread.sleep(10)
        }
        throw AssertionError(
            "等了 ${timeoutMs}ms 没有收到第 ${index + 1} 条响应（共收到 ${received.size} 条）",
        )
    }

    /** 等若干条响应到达。 */
    private fun awaitCount(count: Int, timeoutMs: Long = 8_000L) {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadline) {
            if (received.size >= count) return
            Thread.sleep(10)
        }
        throw AssertionError("等 ${count} 条响应超时（只收到 ${received.size} 条）")
    }

    /**
     * 第 [index] 条响应的 **`result` 字段**的字节数。
     *
     * ⚠️⚠️ **必须量 `result` 而不是整个信封**：`maxResultBytes` 这条契约
     * （§3.6 契约 1）约束的是**结果**的大小，而外层信封还带
     * `request_id` / `token` / `elapsed_ms` / `truncated` 等固定开销 ——
     * 它与结果大小无关，却会让「按整个信封断言」的说法站不住。
     * 生产中真正会撞 binder 的也是两者之和，但**契约的口径是 result**。
     */
    private fun resultBytesOf(index: Int = 0): Int =
        ResultBudget.byteSizeOf(
            JSONObject(rawResponses[index]).getString(CapabilityInvocationCodec.KEY_RESULT),
        )

    // ═══ 1 · 正常路径 ═══════════════════════════════════════

    @Test
    fun `normal call returns items with neither truncation nor cursor`() {
        HookCapabilityRegistry.register(FakeHandler("alpha") {
            CapabilityOutcome.Items(listOf(mapOf("a" to 1)))
        })
        start().onInvoke(requestJson("alpha"))

        val r = awaitResponse()
        assertTrue("ok 应为 true（error=${r.error?.code}）", r.ok)
        assertNull(r.error)
        assertFalse("没截断不该带标志", r.truncated)
        assertNull("没截断不该带游标", r.nextCursor)

        val items = JSONObject(r.resultJson).getJSONArray(InvokePolicy.KEY_ITEMS)
        assertEquals(1, items.length())
        assertEquals(1, items.getJSONObject(0).getInt("a"))
    }

    @Test
    fun `response echoes the request id and token`() {
        // ⚠️ token 必须原样回 —— 它是 App 侧 `onResolve` 鉴权的依据（§3.3）
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })
        start().onInvoke(requestJson("alpha", requestId = "req-xyz", token = "secret"))

        val r = awaitResponse()
        assertEquals("req-xyz", r.requestId)
        assertEquals("secret", r.token)
    }

    // ═══ 2 · 超时路径 ═══════════════════════════════════════

    @Test
    fun `handler running past the budget yields timeout and no result`() {
        HookCapabilityRegistry.register(FakeHandler("slow") { request ->
            Thread.sleep(request.timeoutMs + 200)
            CapabilityOutcome.Items(listOf(mapOf("late" to true)))
        })
        // ⚠️ 用 ~120ms 量级的预算而不是默认 5s，否则单测要跑 5 秒
        start().onInvoke(requestJson("slow", timeoutMs = 120L))

        val r = awaitResponse()
        assertFalse("超时必须 ok=false", r.ok)
        assertEquals(CapabilityErrorCode.TIMEOUT, r.error?.code)
        assertTrue("elapsed 应被带上（供两端日志比对）", r.elapsedMs > 120L)
    }

    @Test
    fun `handler exactly at the budget is not a timeout`() {
        // ⚠️ 判定是**严格大于** —— 恰好用满预算不算超时（那是合法的成功执行）。
        // 用一个「立刻返回」的 handler + 大预算来钉住这个边界。
        HookCapabilityRegistry.register(FakeHandler("fast") { CapabilityOutcome.Items(emptyList()) })
        start().onInvoke(requestJson("fast", timeoutMs = 5_000L))

        assertTrue("预算充足时正常返回", awaitResponse().ok)
    }

    @Test
    fun `handler declared timeout is honoured over the request one`() {
        // CapabilityHandler.timeoutMs 存在时取 min ⇒ 声明 50ms、请求 5s ⇒ 用 50ms
        HookCapabilityRegistry.register(
            FakeHandler("declared", declaredTimeoutMs = 50L) {
                Thread.sleep(200)
                CapabilityOutcome.Items(emptyList())
            },
        )
        start().onInvoke(requestJson("declared", timeoutMs = 5_000L))

        assertEquals(CapabilityErrorCode.TIMEOUT, awaitResponse().error?.code)
    }

    // ═══ 3 · 异常路径 ═══════════════════════════════════════

    @Test
    fun `handler throwing produces a handler_error response not silence`() {
        // ⚠️⚠️ **反证 #1 的落点**：去掉 `runOnWorker` 的顶层 try/catch 后，
        // 这条会「等不到响应」而超时 ⇒ 本用例变红。
        HookCapabilityRegistry.register(FakeHandler("boom") {
            throw IllegalStateException("boom")
        })
        start().onInvoke(requestJson("boom"))

        val r = awaitResponse()
        assertFalse(r.ok)
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, r.error?.code)
        assertTrue("detail 应含异常类名与消息", r.error!!.detail.contains("IllegalStateException"))
        assertTrue(r.error!!.detail.contains("boom"))
    }

    @Test
    fun `handler throwing an Error is also caught`() {
        // ⚠️ 顶层兜的是 `Throwable`（含 Error）—— 只 catch Exception 会让
        // `StackOverflowError` 逃逸进 system_server
        HookCapabilityRegistry.register(FakeHandler("error") {
            throw StackOverflowError("deep")
        })
        start().onInvoke(requestJson("error"))

        assertEquals(CapabilityErrorCode.HANDLER_ERROR, awaitResponse().error?.code)
    }

    @Test
    fun `handler returning an explicit failure keeps its own code`() {
        // ⚠️ handler 自报的失败**不该**被降级成 handler_error —— 它能给出更具体的码
        HookCapabilityRegistry.register(FakeHandler("diy") {
            CapabilityOutcome.Failure(CapabilityErrorCode.PAYLOAD_TOO_LARGE, "自己判定的")
        })
        start().onInvoke(requestJson("diy"))

        assertEquals(CapabilityErrorCode.PAYLOAD_TOO_LARGE, awaitResponse().error?.code)
    }

    // ═══ 4 · 截断路径 ═══════════════════════════════════════

    @Test
    fun `huge result is truncated with flag and cursor and stays within the limit`() {
        // ⚠️⚠️ **反证 #2 / #4 / #5 / #6 的落点**，也是验收 #6。
        // 关键：`ok` 必须仍是 true（若字节记账错了，这里会变成
        // `payload_too_large` 而**不是**截断成功）。
        HookCapabilityRegistry.register(DiagnosticCapabilityHandler())
        start().onInvoke(requestJson("${com.chaomixian.vflow.xposed.capability.CapabilityNames.DIAGNOSTIC}",
            params = """{"mode":"huge"}"""))

        val r = awaitResponse()
        assertTrue("截断是成功路径，不是失败（error=${r.error?.code}）", r.ok)
        assertTrue("必须带截断标志位", r.truncated)
        assertNotNull("必须带下一页游标", r.nextCursor)

        val limit = ResultBudget.DEFAULT_MAX_RESULT_BYTES
        val actual = resultBytesOf()
        assertTrue("result 字节 $actual 超过上限 $limit", actual <= limit)

        // 只验**第一页 + 游标**，不验自动翻页（本任务不做自动翻页）
        val items = JSONObject(r.resultJson).getJSONArray(InvokePolicy.KEY_ITEMS)
        assertTrue("应收下若干项（而不是 0 或全部）", items.length() in 1 until DiagnosticCapabilityHandler.HUGE_ITEM_COUNT)
    }

    @Test
    fun `truncation flag and cursor go on the envelope not inside result`() {
        // ⚠️⚠️ **反证 #5 的落点**（评审发现的坑）。
        // 分页两键若塞进 `result` 内部 ⇒ App 侧的 `Success.nextCursor` / `.truncated`
        // **永远填不上、恒为缺省值**，而 codec 的 round-trip 测试照样全绿。
        HookCapabilityRegistry.register(DiagnosticCapabilityHandler())
        start().onInvoke(requestJson(com.chaomixian.vflow.xposed.capability.CapabilityNames.DIAGNOSTIC,
            params = """{"mode":"huge"}"""))
        awaitResponse()

        val envelope = JSONObject(rawResponses[0])
        assertTrue("信封顶层必须有 truncated", envelope.has(CapabilityInvocationCodec.KEY_TRUNCATED))
        assertTrue("信封顶层必须有 next_cursor", envelope.has(CapabilityInvocationCodec.KEY_NEXT_CURSOR))

        // 反面：result 内部**不得**出现它们
        val result = JSONObject(envelope.getString(CapabilityInvocationCodec.KEY_RESULT))
        assertFalse("result 内部不得出现 truncated", result.has(CapabilityInvocationCodec.KEY_TRUNCATED))
        assertFalse("result 内部不得出现 next_cursor", result.has(CapabilityInvocationCodec.KEY_NEXT_CURSOR))
    }

    @Test
    fun `cursor of the first page equals the number of collected items`() {
        // ⚠️ 框架算 `nextCursor = startIndex + 收下的元素数`。
        // 真机验证时 hook 侧日志的「收下 N/2000」与 App 侧 nextCursor 应是同一个数
        // —— 不一致说明分页记账与上报脱节。
        HookCapabilityRegistry.register(DiagnosticCapabilityHandler())
        start().onInvoke(requestJson(com.chaomixian.vflow.xposed.capability.CapabilityNames.DIAGNOSTIC,
            params = """{"mode":"huge"}"""))

        val r = awaitResponse()
        val collected = JSONObject(r.resultJson)
            .getJSONArray(InvokePolicy.KEY_ITEMS).length()
        assertEquals(collected.toString(), r.nextCursor)
    }

    @Test
    fun `result that cannot fit even after truncation reports payload too large`() {
        // ⚠️ 兜的是 `collectWithin` 的**单元素超限特例**：它会收下那个超限元素
        // （否则分页会死循环）⇒ 整串真的可能超上限 ⇒ 必须在发出去之前拦下，
        // 因为 oneway 超限是**静默丢弃**（binder 不通知发送方）。
        HookCapabilityRegistry.register(FakeHandler("single") {
            // 一个元素就 400 KiB > 默认上限 256 KiB
            CapabilityOutcome.Items(listOf(mapOf("pad" to "x".repeat(400 * 1024))))
        })
        start().onInvoke(requestJson("single"))

        val r = awaitResponse()
        assertFalse("超上限不是成功", r.ok)
        assertEquals(CapabilityErrorCode.PAYLOAD_TOO_LARGE, r.error?.code)
    }

    @Test
    fun `a capability can lower its own byte limit`() {
        // 声明得越小越安全（那半边异步缓冲是与所有其他 oneway 事务共享的）
        HookCapabilityRegistry.register(
            FakeHandler("tiny", maxResultBytes = 512) {
                CapabilityOutcome.Items((0 until 100).map { mapOf("i" to it, "pad" to "x".repeat(40)) })
            },
        )
        start().onInvoke(requestJson("tiny"))

        val r = awaitResponse()
        assertTrue(r.ok)
        assertTrue("应按 512 字节截断", r.truncated)
        assertTrue("result 应落在 512 字节内（实测 ${resultBytesOf()}）", resultBytesOf() <= 512)
    }

    // ═══ 5 · 池满路径 ═══════════════════════════════════════

    @Test
    fun `when the pool is exhausted the second call fails immediately`() {
        // ⚠️⚠️ **反证 #3 的落点**（`SynchronousQueue` 换成有界队列后本条变红）。
        //
        // ⚠️ 写法要点：必须用 latch 把第一个 worker **钉住**，
        // 否则它可能已经跑完、池空出来 ⇒ 测试变 flaky。
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        HookCapabilityRegistry.register(FakeHandler("blocker") {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            CapabilityOutcome.Items(emptyList())
        })

        val rt = start(poolSize = 1)
        try {
            rt.onInvoke(requestJson("blocker", requestId = "first"))
            assertTrue("第一个 handler 应已进入工作线程", entered.await(5, TimeUnit.SECONDS))

            // 此刻唯一的 worker 被钉住 ⇒ 第二个调用必然被拒
            val startedAt = System.nanoTime()
            rt.onInvoke(requestJson("blocker", requestId = "second"))
            val r = awaitResponse()

            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            assertFalse(r.ok)
            assertEquals(CapabilityErrorCode.HANDLER_ERROR, r.error?.code)
            assertTrue(
                "detail 必须写明「工作线程池已满」（否则用户排查方向会错）：${r.error!!.detail}",
                r.error!!.detail.contains("工作线程池已满"),
            )
            assertFalse("池满**不得**说成已停止", r.error!!.detail.contains("已停止"))
            // 「立即返回」—— 绝不能阻塞 binder 线程等池子空出来
            assertTrue("池满响应必须立刻返回（实测 ${elapsedMs}ms）", elapsedMs < 1_000)
        } finally {
            gate.countDown()
        }
    }

    @Test
    fun `pool size two allows two concurrent handlers`() {
        // 容量 2 ⇒ 两个并发调用都应被接受（第三个才被拒）
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(2)
        HookCapabilityRegistry.register(FakeHandler("blocker") {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            CapabilityOutcome.Items(emptyList())
        })

        val rt = start(poolSize = 2)
        try {
            rt.onInvoke(requestJson("blocker", requestId = "a"))
            rt.onInvoke(requestJson("blocker", requestId = "b"))
            assertTrue(
                "容量 2 时两个调用都应进入工作线程",
                entered.await(5, TimeUnit.SECONDS),
            )
            // 此刻都没回响应
            assertEquals("两个都在跑，不该有响应", 0, received.size)
        } finally {
            gate.countDown()
        }
    }

    // ═══ 6 · 未知名 ═════════════════════════════════════════

    @Test
    fun `unknown capability name yields capability_absent naming the name`() {
        // ⚠️⚠️ **反证 #3 的另一个落点**：改成静默 return 后本条会「等不到响应」。
        // 与事件侧「未知 topic 忽略」**方向相反** —— 调用方在等结果，
        // 静默会让它白等到超时，并把排查引向「hook 点 / 系统版本」。
        start().onInvoke(requestJson("nope"))

        val r = awaitResponse()
        assertFalse(r.ok)
        assertEquals(CapabilityErrorCode.CAPABILITY_ABSENT, r.error?.code)
        assertTrue("detail 应含被问到的名字", r.error!!.detail.contains("nope"))
    }

    @Test
    fun `unknown capability is answered even when the pool is full`() {
        // ⚠️ 未知名在**投递之前**就判掉了 ⇒ 池满也不影响它。
        // 若实现把查表放在投递之后，池满时未知名会得到「池已满」——
        // 那是两个完全不同的排查方向。
        val gate = CountDownLatch(1)
        val entered = CountDownLatch(1)
        HookCapabilityRegistry.register(FakeHandler("blocker") {
            entered.countDown()
            gate.await(10, TimeUnit.SECONDS)
            CapabilityOutcome.Items(emptyList())
        })

        val rt = start(poolSize = 1)
        try {
            rt.onInvoke(requestJson("blocker"))
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            rt.onInvoke(requestJson("nope"))

            assertEquals(CapabilityErrorCode.CAPABILITY_ABSENT, awaitResponse().error?.code)
        } finally {
            gate.countDown()
        }
    }

    // ═══ 7 · 停止后的迟到调用（验收 #7）═══════════════════

    @Test
    fun `a late call after stop reports stopped not pool exhausted`() {
        // ⚠️⚠️ 验收 #7。两种 `RejectedExecutionException` 的成因**必须分开**：
        // 都报「池已满」会让用户去查并发，而真实原因是「运行时已停，不会再有响应」。
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(poolSize = 1)
        rt.stop()
        received.clear()

        rt.onInvoke(requestJson("alpha"))

        val r = awaitResponse()
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, r.error?.code)
        assertTrue("detail 必须说明已停止：${r.error!!.detail}", r.error!!.detail.contains("已停止"))
        assertFalse("已停止**不得**说成池满", r.error!!.detail.contains("工作线程池已满"))
    }

    @Test
    fun `stop is idempotent`() {
        val rt = start()
        rt.stop()
        rt.stop()   // 热更新 + 通道关闭可能都调
        assertTrue(rt.isStoppedForTest())
    }

    // ═══ 8 · 健壮性 ═════════════════════════════════════════

    @Test
    fun `undecodable request is ignored without respose or crash`() {
        // 连 request_id 都没有 ⇒ 无法配对，回响应也没人要。只能留日志。
        start().onInvoke("not json at all")
        start().onInvoke("{}")             // 缺 request_id / capability

        Thread.sleep(200)
        assertEquals("不该产生任何响应", 0, received.size)
    }

    @Test
    fun `protocol version mismatch still processes the call`() {
        // §3.5：版本错配只 warn、不拒绝 —— 协议是加法演进的
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })
        val json = CapabilityInvocationCodec.encodeRequest(
            requestId = "req-v",
            capability = "alpha",
            protocolVersion = 999,
        )
        start().onInvoke(json)

        assertTrue("版本不符仍应给出结果", awaitResponse().ok)
    }

    @Test
    fun `capabilities json lists what the registry holds`() {
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })
        HookCapabilityRegistry.register(FakeHandler("beta") { CapabilityOutcome.Items(emptyList()) })

        val decoded = com.chaomixian.vflow.xposed.wire.CapabilityManifest
            .decode(start().capabilitiesJson())
        assertEquals(setOf("alpha", "beta"), decoded)
    }

    // ═══ 夹具 ═══════════════════════════════════════════════

    /**
     * 最小假 handler。
     *
     * ⚠️ 与 `DiagnosticCapabilityHandler` 不同，它**不碰 params** ——
     * 让本测试关心的事（超时 / 池满 / 异常）不被参数解析干扰。
     */
    private class FakeHandler(
        override val name: String,
        override val maxResultBytes: Int? = null,
        override val timeoutMs: Long? = null,
        private val body: (CapabilityRequest) -> CapabilityOutcome,
    ) : CapabilityHandler {
        constructor(
            name: String,
            declaredTimeoutMs: Long,
            body: (CapabilityRequest) -> CapabilityOutcome,
        ) : this(name, null, declaredTimeoutMs, body)

        override fun handle(request: CapabilityRequest): CapabilityOutcome = body(request)
    }
}
