package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.CapabilityResponse
import com.chaomixian.vflow.xposed.wire.ResultBudget
import com.chaomixian.vflow.xposed.wire.ThreadModes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

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

    private companion object {
        /** 被测运行时（源码扫描用；Gradle test 工作目录 = `app/`）。 */
        const val RUNTIME_PATH =
            "src/main/java/com/chaomixian/vflow/xposed/capabilities/HookCapabilityRuntime.kt"
    }

    /** 收到的响应（已解码）。`Collections.synchronizedList` —— 工作线程会往里写。 */
    private val received = Collections.synchronizedList(mutableListOf<CapabilityResponse>())

    /** 每个响应对应的原始信封串（用于字面量断言，如「键必须存在」）。 */
    private val rawResponses = Collections.synchronizedList(mutableListOf<String>())

    private var runtime: HookCapabilityRuntime? = null

    /**
     * 响应出口 —— 与生产 `VFlowHookEntry` 的接线**逐字一致**：
     * 收到信封串后**原样**解码，不再自己编码。
     *
     * ⚠️ 这里收的是 `json: String`（**已编好的信封**），而不是七个字段。
     * 2026-10-01 之前是后者，而那个形态正是那个真机缺陷的温床：
     * 运行时手里只有 `resultJson`，量不到真正发出去的信封。
     */
    private fun responder() = CapabilityResponder { json ->
        rawResponses += json
        // ⚠️ 解码失败返回 null —— 那本身就是一个应当变红的信号
        received += requireNotNull(CapabilityInvocationCodec.decodeResponse(json)) {
            "回了一条连自家 codec 都解不开的信封：$json"
        }
        true
    }

    /**
     * 起一个运行时。
     *
     * ⚠️ 2026-10-03：构造参数由 `poolSize: Int` 改为 `dispatchers: Map<String, CoroutineDispatcher>`。
     * 默认（不传）走生产三档；需要断言「分发到哪一档」时注入**记录身份的假 dispatcher**。
     */
    private fun start(
        dispatchers: Map<String, CoroutineDispatcher> = HookCapabilityRuntime.defaultDispatchers(),
    ): HookCapabilityRuntime {
        val rt = HookCapabilityRuntime(respond = responder(), dispatchers = dispatchers)
        runtime = rt
        return rt
    }

    /**
     * 只**记录**投递、**不执行**的假 dispatcher。
     *
     * ⚠️⚠️ 存在的理由有两个，都很实在：
     * 1. **纯 JVM 里真 `ui` 档起不来** —— `HandlerThread.start()` / `Looper.myLooper()`
     *    在 AGP mockable jar 里抛 `Method … not mocked`（本项目无 Robolectric）。
     *    于是「分发到 ui 档」这条**语义**只能用假 dispatcher 覆盖。
     * 2. **造「队列堆积」状态** —— UI 档安全阀那条用例需要一个「待执行数只增不减」的
     *    dispatcher，真 `HandlerThread` 会立刻消费掉，造不出来。
     *
     * @param label 记录用的档名（断言用）
     */
    private class RecordingDispatcher(private val label: String) : CoroutineDispatcher() {
        val dispatched = Collections.synchronizedList(mutableListOf<String>())

        /** `true` ⇒ **执行**投递的协程；`false` ⇒ 只记录、永不执行（造堆积用）。 */
        var execute: Boolean = true

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched += label
            if (execute) block.run()
        }
    }

    /** 纯 JVM 可用的「真执行」dispatcher —— `Dispatchers.Default` 在测试 JVM 里是好的。 */
    private fun realDefault() = Dispatchers.Default

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

    /**
     * 造一条能力请求（走真 codec 编码 ⇒ 顺便验了请求侧的解码路径）。
     *
     * ⚠️ `timeoutMs` 是 **`Long?`** 而非非空 —— `null` ⇒ **不写 `timeout_ms` 键**
     *（= 不超时，见 `encodeRequest`）。出队判过期那组用例需要构造
     * 「不含该键」的请求来验「budget 为 null 时判据是 no-op」，
     * 非空签名**编译不过**。
     */
    private fun requestJson(
        capability: String,
        params: String = "{}",
        timeoutMs: Long? = 5_000L,
        requestId: String = "req-1",
        token: String = "tok",
        threadMode: String? = null,
    ): String = CapabilityInvocationCodec.encodeRequest(
        requestId = requestId,
        capability = capability,
        paramsJson = params,
        timeoutMs = timeoutMs,
        token = token,
        threadMode = threadMode,
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
            Thread.sleep((request.timeoutMs ?: 1_000L) + 200)
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

    // ══════════ 分页的框架契约（真机实测暴露的缺陷，2026-10-01）══════════

    @Test
    fun `handler must return the full list and let the framework slice by startIndex`() {
        // ⚠️⚠️ 这条锁的是**框架与 handler 的分工**，它来自一次真机缺陷。
        //
        // 框架把 `Items.items` 与 `Items.startIndex` 一起交给
        // `ResultBudget.collectWithin(items, ..., startIndex)`，
        // **后者自己就会从 `startIndex` 开始收**。
        //
        // ⇒ handler 若「先自己 `drop(cursor)`、又传 `startIndex = cursor`」，
        // 第二页就是**双重切片** —— 真机表现是**第 2 页恒返回空**
        //（`QueryShortcutIntentsHandler` 就写成了这样，实测 408 条只拿得到 232 条）。
        //
        // 本用例用一个**故意返回全量**的 handler 走两页，断言第二页**非空**且
        // 两页**不重叠**。⇒ 任何人把框架改成「不按 startIndex 切片」或
        // 误以为「handler 该自己切」都会让它变红。
        HookCapabilityRegistry.register(FakeHandler("paged") { req ->
            val cursor = JSONObject(req.paramsJson).optString("cursor").toIntOrNull() ?: 0
            CapabilityOutcome.Items(
                items = (0 until 500).map { mapOf("i" to it, "pad" to "x".repeat(900)) },
                startIndex = cursor,
            )
        })

        val rt = start()
        rt.onInvoke(requestJson("paged", params = """{}""", requestId = "p1"))
        val first = awaitResponse(0)
        val firstItems = JSONObject(first.resultJson).getJSONArray(InvokePolicy.KEY_ITEMS)
        assertTrue("第一页应当被截断", first.truncated)
        assertNotNull("第一页应当给出游标", first.nextCursor)

        rt.onInvoke(requestJson("paged", params = """{"cursor":"${first.nextCursor}"}""", requestId = "p2"))
        val second = awaitResponse(1)
        val secondItems = JSONObject(second.resultJson).getJSONArray(InvokePolicy.KEY_ITEMS)
        assertTrue(
            "❌ 第二页必须非空 —— 双重切片（handler 自己 drop + 框架按 startIndex 再跳）" +
                "会让它恒为 0，表现为「后面的条目永远拿不到」。实际：${secondItems.length()}",
            secondItems.length() > 0,
        )

        // 两页不重叠：第二页的首项下标 == 第一页项数（游标语义是下标）
        assertEquals(
            "第二页应从第一页的游标处接上（不重不漏）",
            firstItems.length(),
            secondItems.getJSONObject(0).getInt("i"),
        )
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

    // ═══ 4b · ★★ 校验对象必须是「信封」而非「result 字段」 ═══
    //
    // 2026-10-01 真机实测缺陷（小米 MIX Fold 3 / Android 17）的回归锁。
    //
    // 现象：`huge` 路径上截断**成功**了（日志「收下 1228/2000 项，nextCursor=1228」），
    // 紧接着 `resolve` 抛 `TransactionTooLargeException  data parcel size 533700 bytes`。
    //
    // 根因：旧终检量的是 `resultJson` 的 **UTF-8 字节**（259,237 < 262,144 判通过），
    // 而实际发出去的是**信封**，binder 按 **UTF-16 代码单元 × 2 字节** 计费
    // ⇒ 533,514 字节，撞上 oneway 的异步半缓冲（≈508 KiB）。
    //
    // ## ⚠️⚠️ 本用例为什么能测到它（而原来的 71 例测不到）
    //
    // 原来的用例「经过的是**类型**，不是**传输**」：它们断言字段内容，
    // 而 `resultJson` 与信封的**字节口径差 2 倍**这件事只体现在字节数上。
    //
    // 本用例构造一个**恰好卡在两口径之间**的载荷：
    //
    // | 口径 | 值 | 旧判据 | 新判据 |
    // |---|---|---|---|
    // | `result` 的 UTF-8 字节 | ≈180 KiB | ✅ 通过（< 256 KiB） | — |
    // | 信封的 parcel 字节 | ≈360 KiB | ❌ **发不出去** | — |
    //
    // ⇒ 改回「只量 resultJson」时，本条会失败（得到 `ok=true` 而不是 `payload_too_large`）。

    @Test
    fun `the check measures the envelope not the result field`() {
        // ## 夹具构造的关键：让【传输上限】成为唯一的约束
        //
        // 声明为默认（256 KiB 内容）⇒ 内容换算成 parcel 是 512 KiB，
        // 但**传输上限**把它封顶到 384 KiB。于是「能收下多少」由传输上限决定，
        // 而不由 result 的字节数决定 —— 这正是旧实现看不见的那一格。
        //
        // 单个 200 KiB 的元素：
        // | 口径 | 值 | 旧判据 | 新判据 |
        // |---|---|---|---|
        // | `result` 的 UTF-8 字节 | ≈200 KiB | ✅ 通过（< 256 KiB） | — |
        // | 信封 parcel（×2） | ≈400 KiB | ❌ **超出传输上限** | — |
        val payloadBytes = 200 * 1024
        HookCapabilityRegistry.register(
            // ⚠️ 不声明 maxResultBytes ⇒ 用默认 256 KiB（内容口径）
            FakeHandler("boundary") {
                CapabilityOutcome.Items(listOf(mapOf("pad" to "x".repeat(payloadBytes))))
            },
        )
        start().onInvoke(requestJson("boundary"))

        val r = awaitResponse()

        // ★ 核心断言：不能是「成功」。旧实现会在这里给 ok=true
        //（因为 `resultJson` 的 UTF-8 字节 204,800 < 262,144），
        // 而那条响应在真机上会抛 TransactionTooLargeException。
        assertFalse(
            "❌ 信封已超出传输上限，不该回成功 —— 这正是 2026-10-01 真机缺陷的形态" +
                "（result 的 UTF-8 字节 ${ResultBudget.byteSizeOf(r.resultJson)}，" +
                "而信封 parcel ${InvokePolicy.estimatedParcelBytes(rawResponses[0])}）",
            r.ok,
        )
        assertEquals(CapabilityErrorCode.PAYLOAD_TOO_LARGE, r.error?.code)

        // 反面证据：确认夹具**真的**落在两口径之间（否则本用例空转）
        val resultUtf8 = ResultBudget.byteSizeOf(r.resultJson)
        assertTrue(
            "夹具没落在两口径之间（result UTF-8 = $resultUtf8）—— 本用例会空转",
            resultUtf8 < ResultBudget.DEFAULT_MAX_RESULT_BYTES,
        )
    }

    @Test
    fun `a huge truncation that fits the transport still succeeds end to end`() {
        // ⚠️ 与上一条配对：**修完不能把正常截断也弄坏**。
        //
        // `huge`（2000 项 × ~213 字节 ≈ 426 KiB 单层）必须仍然
        // **截断成功**（而不是 `payload_too_large`）——
        // 它在真机上是「收下 1228/2000 项」那条路径。
        //
        // ⚠️ 它同时锁住「预算换算后仍能装下可观数量」：若换算系数写错成 4，
        // 收下的项数会骤降到 600 左右（虽然仍是成功），说明换算口径不对。
        HookCapabilityRegistry.register(DiagnosticCapabilityHandler())
        start().onInvoke(
            requestJson(
                com.chaomixian.vflow.xposed.capability.CapabilityNames.DIAGNOSTIC,
                params = """{"mode":"huge"}""",
            ),
        )

        val r = awaitResponse()
        assertTrue("huge 必须截断成功而不是 payload_too_large（error=${r.error?.code}）", r.ok)
        assertTrue(r.truncated)
        assertNotNull(r.nextCursor)

        // ★★ **真机同款判据**：信封的 parcel 字节必须在传输上限之内。
        // 这一条就是真机 `TransactionTooLargeException` 的**直接**回归断言。
        val parcel = InvokePolicy.estimatedParcelBytes(rawResponses[0])
        assertTrue(
            "信封 parcel $parcel 字节超出传输上限 " +
                "${ResultBudget.MAX_ENVELOPE_PARCEL_BYTES} —— 真机上会抛 TransactionTooLargeException",
            parcel <= ResultBudget.MAX_ENVELOPE_PARCEL_BYTES,
        )

        val items = JSONObject(r.resultJson).getJSONArray(InvokePolicy.KEY_ITEMS)
        // ⚠️ 收下的项数是**预算换算正确性**的直接体现（实测边界 859，取 750 留余量）：
        // | 换算口径 | 收下项数 |
        // |---|---|
        // | 旧实现（按 result 的 UTF-8 记 1:1） | ~1228 —— **但真机发不出去** |
        // | **新实现**（按信封 parcel 的 1:2） | **~859** |
        // | 若误写成 1:4 | ~430 |
        assertTrue("收下 ${items.length()} 项，偏少说明预算换算系数偏大", items.length() > 750)
        assertTrue("收下 ${items.length()} 项，偏多说明换算系数偏小", items.length() < 1000)
        // ⚠️ 旧实现的 1228 项在这里会被上界挡下 —— 那是「结果发不出去」的信号
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

    // ═══ 5 · 「满」的语义（三档**各不相同**）═════════════════
    //
    // ⚠️⚠️ 2026-10-03 改造：自建有界池（容量 2 + SynchronousQueue）换成三档之后，
    // 「第三个调用立刻收到 handler_error」这个语义**消失了** ——
    // `default`/`io` 改为**排队等**（`Dispatchers.IO` 的 64 并发信号量），
    // `ui` 改为**无界排队 + 安全阀**。这不是回归，是**三档各自的天然行为**
    //（设计 §1.4 末表：`Default` 弹性建线程 / `IO` 64 并发排队 / `UI` 无界排队）。
    //
    // ⇒ 原来那两条（`when the pool is exhausted …` / `pool size two allows …`）
    // 改成「三档都能接受多个并发、不因『满』而拒」的**正面断言**。

    @Test
    fun `the tiers accept concurrent calls instead of rejecting them`() {
        // ⚠️ 反证：若把投递改回「有界池 + 满即拒」的形态，本条的第二个调用会
        // 立刻收到 handler_error ⇒ 断言「不该有响应」变红。
        //
        // 用一个**只记录、不执行**的假 dispatcher 精确制造「投递出去但都还没跑完」，
        // 避免真 dispatcher 的调度时序让测试变 flaky。
        val fake = RecordingDispatcher("fake").apply { execute = false }
        HookCapabilityRegistry.register(FakeHandler("acceptor") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.DEFAULT to fake))
        rt.onInvoke(requestJson("acceptor", requestId = "a"))
        rt.onInvoke(requestJson("acceptor", requestId = "b"))
        rt.onInvoke(requestJson("acceptor", requestId = "c"))

        // ★ 核心：三条**都被接受并投递**了（没有一条被「满」拒掉）
        assertEquals("三条都应被投递（不再有『池满』这个语义）", 3, fake.dispatched.size)
        assertEquals("不该有『满了』的响应", 0, received.size)
    }

    @Test
    fun `the tiers actually execute when the dispatcher runs them`() {
        // 上一条只证明「被接受」；本条证明**真执行**（用真实 Dispatchers.Default）。
        HookCapabilityRegistry.register(FakeHandler("alpha") {
            CapabilityOutcome.Items(listOf(mapOf("v" to 1)))
        })

        start().onInvoke(requestJson("alpha", requestId = "a"))
        start().onInvoke(requestJson("alpha", requestId = "b"))

        awaitCount(2)
        assertTrue("两条都应真的执行并回成功", received.all { it.ok })
    }

    // ═══ 5a · ★★ 三档分发（本任务的核心）═══════════════════

    @Test
    fun `each tier dispatches to its own dispatcher`() {
        // ⚠️⚠️ 本任务最核心的一条：`thread_mode` 必须真的选到**对应那一档**。
        //
        // ⚠️ 为什么注入**假 dispatcher**：真 `ui` 档在纯 JVM 里起不来
        //（`HandlerThread.start()` / `Looper.myLooper()` 抛 `not mocked`，本项目无 Robolectric）。
        // ⇒ 「分发到 ui 档」这条**语义**只能这样覆盖；「ui 档真有 Looper」由
        // instrumented 测试 + 真机项覆盖（那是**另一层**事实，两者缺一不可）。
        val def = RecordingDispatcher(ThreadModes.DEFAULT)
        val io = RecordingDispatcher(ThreadModes.IO)
        val ui = RecordingDispatcher(ThreadModes.UI)
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(
            dispatchers = mapOf(
                ThreadModes.DEFAULT to def,
                ThreadModes.IO to io,
                ThreadModes.UI to ui,
            ),
        )

        rt.onInvoke(requestJson("alpha", requestId = "d", threadMode = ThreadModes.DEFAULT))
        rt.onInvoke(requestJson("alpha", requestId = "i", threadMode = ThreadModes.IO))
        rt.onInvoke(requestJson("alpha", requestId = "u", threadMode = ThreadModes.UI))

        assertEquals("default 档应只落 default", 1, def.dispatched.size)
        assertEquals("io 档应只落 io", 1, io.dispatched.size)
        assertEquals("ui 档应只落 ui", 1, ui.dispatched.size)
    }

    @Test
    fun `an unknown thread mode falls back to default without failing`() {
        // ⚠️ 硬约束：新 App 发 `io`、旧 hook 层不认识时必须**静默降级**，
        // 不能报错（报错会把它变成一次**调用失败**，而降级只损失「资源画像准确度」）。
        val def = RecordingDispatcher(ThreadModes.DEFAULT)
        val io = RecordingDispatcher(ThreadModes.IO)
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.DEFAULT to def, ThreadModes.IO to io))
        rt.onInvoke(requestJson("alpha", requestId = "x", threadMode = "not-a-mode"))

        assertEquals("未知档应回落 default", 1, def.dispatched.size)
        assertEquals("未知档**不得**误投给 io", 0, io.dispatched.size)
    }

    @Test
    fun `a missing thread mode falls back to default`() {
        val def = RecordingDispatcher(ThreadModes.DEFAULT)
        val io = RecordingDispatcher(ThreadModes.IO)
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.DEFAULT to def, ThreadModes.IO to io))
        rt.onInvoke(requestJson("alpha", requestId = "n"))   // 不带 thread_mode

        assertEquals("缺省档应回落 default", 1, def.dispatched.size)
        assertEquals("缺省档**不得**误投给 io", 0, io.dispatched.size)
    }

    @Test
    fun `a dispatcher table missing a tier still does not crash`() {
        // ⚠️ 表缺项（注入假表时可能只给一个档）也走 default —— 不该崩。
        // 生产路径不会缺（`defaultDispatchers()` 给全三档），但防御性兜住。
        val def = RecordingDispatcher(ThreadModes.DEFAULT)
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.DEFAULT to def))
        rt.onInvoke(requestJson("alpha", requestId = "u", threadMode = ThreadModes.UI))

        assertEquals("表里没有 ui 时应落到 default（而不是崩）", 1, def.dispatched.size)
    }

    @Test
    fun `the ui tier overflow valve rejects beyond the limit`() {
        // ⚠️⚠️ UI 档安全阀（`InvokePolicy.MAX_UI_QUEUE`）。
        //
        // ⚠️ 为什么必须用**只记录不执行**的假 ui dispatcher：真 `HandlerThread` 会立刻
        // 消费掉投递，待执行数涨不起来 ⇒ 造不出「堆积」这个状态。
        val ui = RecordingDispatcher(ThreadModes.UI).apply { execute = false }
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.UI to ui))

        // 把待执行数顶到上限（假 dispatcher 不执行 ⇒ 计数只增）
        repeat(InvokePolicy.MAX_UI_QUEUE) { i ->
            rt.onInvoke(requestJson("alpha", requestId = "u$i", threadMode = ThreadModes.UI))
        }
        assertEquals(
            "上限内的请求都应被投递",
            InvokePolicy.MAX_UI_QUEUE,
            ui.dispatched.size,
        )

        // ★ 第 N+1 条必须被**安全阀**拦下（而不是无限排队到 OOM）
        rt.onInvoke(requestJson("alpha", requestId = "over", threadMode = ThreadModes.UI))

        val r = awaitResponse()
        assertFalse("超限那条不该成功", r.ok)
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, r.error?.code)
        assertTrue(
            "detail 必须说明是 UI 档待处理过多：${r.error?.detail}",
            r.error!!.detail.contains("待处理请求过多"),
        )
        assertEquals(
            "被拦下的那条**不得**进入 dispatcher",
            InvokePolicy.MAX_UI_QUEUE,
            ui.dispatched.size,
        )
    }

    @Test
    fun `the ui overflow valve does not leak when the tier drains`() {
        // ⚠️ 反证：若 `finally` 里的递减漏了，计数会**只增不减**，
        // 安全阀会被逐渐堵死（跑到上限后**所有** ui 调用都失败）。
        val ui = RecordingDispatcher(ThreadModes.UI)
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.UI to ui))
        repeat(10) { i ->
            rt.onInvoke(requestJson("alpha", requestId = "u$i", threadMode = ThreadModes.UI))
        }
        awaitCount(10)

        assertEquals("执行完后待执行数必须归零（否则安全阀会被堵死）", 0, rt.uiPendingForTest())
    }

    @Test
    fun `other tiers are not charged against the ui overflow valve`() {
        // ⚠️ 安全阀**只管 ui 档** —— `default`/`io` 走各自的库语义（弹性 / 排队），
        // 不该被 UI 的上限约束（那会让两个独立的资源画像互相污染）。
        val def = RecordingDispatcher(ThreadModes.DEFAULT).apply { execute = false }
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.DEFAULT to def))
        repeat(InvokePolicy.MAX_UI_QUEUE + 10) { i ->
            rt.onInvoke(requestJson("alpha", requestId = "d$i", threadMode = ThreadModes.DEFAULT))
        }

        assertEquals("default 档不受 ui 安全阀约束", 0, rt.uiPendingForTest())
        assertEquals("default 档不该有被拦下的响应", 0, received.size)
    }

    // ═══ 5b · ★★ 出队判过期（hook 侧）═══════════════════════
    //
    // 需求：没有这一判据，「App 侧已判超时失败、脚本却仍在 system_server 里真的执行」
    // 就会发生 —— 用户看到「超时失败」，副作用却已经发生（对 risk=HIGH 的
    // `vflow.xposed.js` 不可接受）。
    //
    // ## ⚠️⚠️ 为什么这一格必须用**接缝直调**而不是「钉住 worker 让 B 排队」
    //
    // 当前池是 `ThreadPoolExecutor(core = max = 2, SynchronousQueue)` —— 队列**容量 0**。
    // 钉住 2 个 worker 后，第 3 个提交在 `onInvoke` 就被 `RejectedExecutionException` 拒绝，
    // **根本进不了 `runOnWorker`** ⇒ 断言会拿到 `handler_error`（池满）而**不是** `timeout`。
    // 这是池结构使然，**调夹具救不回来**（真机与端到端单测是同一根因）。
    //
    // ⇒ 用 `runOnWorkerForTest` 直接喂一个**过去的** `arrivedAtMs` 精确制造该状态
    //（确定性、无 sleep、无 latch、无 flaky）。
    //
    // ⚠️ 该接缝证明的只是「判据语义对」；「`onInvoke` 真的把 arrivedAtMs 传下去」由
    // 后面源码扫描那条锁住 —— 两者缺一不可（本仓库反复踩过「纯函数全绿但集成点缺失」）。

    /** 受控地直接跑一次工作线程体，返回记录调用次数的计数器。 */
    private fun runSeam(
        capability: String,
        timeoutMs: Long?,
        arrivedOffsetMs: Long,
        handler: CapabilityHandler? = null,
    ): AtomicInteger {
        val calls = AtomicInteger(0)
        val h = handler ?: FakeHandler(capability) {
            calls.incrementAndGet()
            CapabilityOutcome.Items(listOf(mapOf("ok" to true)))
        }
        HookCapabilityRegistry.register(h)
        val rt = start()
        val request = requireNotNull(
            CapabilityInvocationCodec.decodeRequest(
                requestJson(capability, timeoutMs = timeoutMs),
            ),
        ) { "自造请求解不开" }
        rt.runOnWorkerForTest(
            request = request,
            handler = h,
            arrivedAtMs = System.nanoTime() - arrivedOffsetMs * 1_000_000L,
        )
        return calls
    }

    @Test
    fun `a request that expired while queued is not executed`() {
        // ⚠️⚠️ **反证 #1 的落点**：删掉整个判过期块 ⇒ 本条变红（`calls` 会变成 1）。
        //
        // 排队 600ms，预算 200ms ⇒ 出队时判据必须命中。
        val calls = runSeam(
            capability = "queued",
            timeoutMs = 200L,
            arrivedOffsetMs = 600L,
        )

        // ★★ 核心：handler **从未被调用**（这正是「不执行」的意义）
        assertEquals("❌ 排队已超预算却仍执行了 handler —— 副作用发生了", 0, calls.get())

        val r = awaitResponse()
        assertFalse("过期不是成功", r.ok)
        assertEquals(CapabilityErrorCode.TIMEOUT, r.error?.code)
        // 文案必须让用户知道**没有发生副作用**（区别于「执行超时」的「耗时 Nms 超过预算」）。
        // ⚠️ 断言字面取 `queuedExpiredError` 的定稿文案（「在队列中等待」），
        // 而不是「排队」—— 后者是同义口语，不在生产文案里。
        assertTrue(
            "detail 应说明是排队导致：${r.error?.detail}",
            r.error!!.detail.contains("在队列中等待"),
        )
        assertTrue("detail 必须点明未执行：${r.error?.detail}", r.error!!.detail.contains("未执行"))
        // elapsedMs 应回「等了多久」（不是 0）—— 调用方时间轴才有意义
        assertTrue("elapsedMs 应为排队时长而非 0（实际 ${r.elapsedMs}）", r.elapsedMs >= 500L)
    }

    @Test
    fun `a request still within the queue budget executes normally`() {
        // 排队 50ms、预算 2000ms ⇒ 判据不该命中
        val calls = runSeam(
            capability = "fresh",
            timeoutMs = 2_000L,
            arrivedOffsetMs = 50L,
        )

        assertEquals("排队未超预算，handler 应正常执行", 1, calls.get())
        assertTrue("正常路径应成功（error=${received.firstOrNull()?.error?.code}）", awaitResponse().ok)
    }

    @Test
    fun `no budget means the queue wait never expires the request`() {
        // ⚠️⚠️ **反向断言**：`budget == null`（不超时）⇒ 判据 **no-op**，
        // **不许**因为等待时间长就丢弃。
        //
        // **反证 #2 的落点**：把判据写成 `if (queuedMs > (queuedBudget ?: 0L))`
        // ⇒ 本条变红（等 3 秒 > 0 ⇒ 会被误丢弃）。
        //
        // ⚠️ 请求**不含 `timeout_ms` 键**（`timeoutMs = null`），走真 codec 编码 ⇒
        // 键确实不写 ⇒ 解码后 `request.timeoutMs` 为 null。
        val calls = runSeam(
            capability = "nobudget",
            timeoutMs = null,
            arrivedOffsetMs = 3_000L,
        )

        assertEquals("budget 为 null 时判据必须 no-op（不因等待久而丢弃）", 1, calls.get())
        assertTrue("应正常成功（error=${received.firstOrNull()?.error?.code}）", awaitResponse().ok)
    }

    @Test
    fun `the queue expiry judgement is wired from onInvoke and runs before handler`() {
        // ⚠️⚠️ **接线源码扫描**（形态照 `CoreDexFingerprintTest` / `CapabilityRuntimeWiringTest`）。
        //
        // 接缝级用例证明的是「判据语义对」，**证不了「`onInvoke` 真的把 arrivedAtMs 传下去了」**
        // —— 接线若漏（例如改回两参数调用），接缝用例**照样全绿**。
        // 这正是本仓库反复踩的形态（纯函数全绿但集成点缺失）。
        //
        // ⚠️ **必须剥注释后再断言** —— 本文件里到处是 `arrivedAtMs` / `runOnWorker` 字样
        //（KDoc 与说明注释），只做 `contains` 的话「删掉代码保留注释」照样绿。
        val code = codeOnly(source(RUNTIME_PATH))

        val onInvokeBody = functionBody(code, "fun onInvoke(requestJson: String)")
        assertTrue(
            "onInvoke 必须在方法首行记 arrivedAtMs = System.nanoTime()（全链路最早的点）",
            onInvokeBody.contains("val arrivedAtMs = System.nanoTime()"),
        )
        assertTrue(
            "❌ 投递必须是**三参数**形态 runOnWorker(request, handler, arrivedAtMs) —— " +
                "改回两参数会让接缝用例仍绿、而生产判据永远拿不到真实到达时刻",
            onInvokeBody.contains("runOnWorker(request, handler, arrivedAtMs)"),
        )
        // ── 三档分发（2026-10-03）──
        assertTrue(
            "❌ onInvoke 必须按 thread_mode 选档（InvokePolicy.threadModeOf）—— " +
                "漏了它，所有请求都会落同一档，而纯函数用例照样绿",
            onInvokeBody.contains("InvokePolicy.threadModeOf(request)"),
        )
        assertTrue(
            "❌ 投递必须走 scope.launch（三档各自的 scope）",
            onInvokeBody.contains("scope.launch"),
        )
        assertTrue(
            "❌ UI 档必须有安全阀（否则无界排队 ⇒ OOM ⇒ 整机）",
            onInvokeBody.contains("InvokePolicy.MAX_UI_QUEUE"),
        )

        val workerBody = functionBody(code, "private fun runOnWorker(")
        val judgeIndex = workerBody.indexOf("isTimedOut(queuedMs, queuedBudget)")
        val handleIndex = workerBody.indexOf("handler.handle(request)")
        assertTrue("runOnWorker 里应有出队判过期", judgeIndex >= 0)
        assertTrue("runOnWorker 里应有 handler 调用", handleIndex >= 0)
        assertTrue(
            "❌ 判过期必须排在 handler.handle **之前** —— 放后面等于跑了脚本再判过期",
            judgeIndex < handleIndex,
        )
        assertTrue(
            "判过期命中后必须 return（绝不落到 handler.handle）",
            workerBody.substring(judgeIndex, handleIndex).contains("return"),
        )

        // 防空转：剥注释后仍应有实质代码
        val codeLines = code.lineSequence().count { it.isNotBlank() }
        assertTrue("剥注释后代码行数异常（$codeLines），剥得太狠了", codeLines > 200)
    }

    @Test
    fun `the overflow path still reports handler_error not timeout`() {
        // ⚠️ 「满了」这条路径与**出队判过期**是两条**独立**路径，本条锁住它们不互相污染：
        // 安全阀在 `onInvoke` 里就回错了，**根本没进 `runOnWorker`**
        // ⇒ 无论判过期怎么改，「满了」都必须是 `handler_error`（而判过期是 `timeout`）。
        //
        // ⚠️ 2026-10-03：自建有界池换成三档后，「池满」这一格由 **UI 档安全阀**承载
        //（见 `InvokePolicy.uiQueueOverflowError`）—— 三档里只有 `ui` 档还会「满」。
        val ui = RecordingDispatcher(ThreadModes.UI).apply { execute = false }
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.UI to ui))
        repeat(InvokePolicy.MAX_UI_QUEUE) { i ->
            rt.onInvoke(requestJson("alpha", requestId = "u$i", threadMode = ThreadModes.UI))
        }
        // ⚠️ 带一个 50ms 的预算 —— 若实现错把「满」当成「排队过期」，本条能看出码变了
        rt.onInvoke(
            requestJson("alpha", requestId = "over", threadMode = ThreadModes.UI, timeoutMs = 50L),
        )

        val r = awaitResponse()
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, r.error?.code)
        assertTrue(
            "「满了」的 detail 必须说明是待处理过多：${r.error?.detail}",
            r.error!!.detail.contains("待处理请求过多"),
        )
        assertFalse(
            "❌「满了」**不得**被出队判过期改写成「未执行」—— 两者是不同排查方向",
            r.error!!.detail.contains("未执行"),
        )
    }

    // ══ 源码扫描的辅助（形态照 CapabilityRuntimeWiringTest）══

    private fun source(path: String): String {
        val f = File(path)
        assertTrue("找不到 $path（当前目录 ${File(".").absolutePath}）", f.exists())
        return f.readText()
    }

    /** 剥掉块注释与行注释 —— 否则注释里的同名字样会让「删掉代码」照样绿。 */
    private fun codeOnly(text: String): String {
        val withoutBlock = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlock.lineSequence().joinToString("\n") { it.substringBefore("//") }
    }

    /** 按大括号配对截取函数体（跳过字符串字面量不够、但本文件无相关用法）。 */
    private fun functionBody(code: String, signatureFragment: String): String {
        val start = code.indexOf(signatureFragment)
        assertTrue("找不到函数：$signatureFragment", start >= 0)
        val braceStart = code.indexOf('{', start)
        assertTrue("函数 $signatureFragment 没有函数体", braceStart >= 0)
        var depth = 0
        var index = braceStart
        while (index < code.length) {
            when (code[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(braceStart, index + 1)
                }
            }
            index++
        }
        error("函数 $signatureFragment 的大括号不配对")
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
    fun `unknown capability is answered even when the tier is saturated`() {
        // ⚠️ 未知名在**投递之前**就判掉了 ⇒ 「满了」也不影响它。
        // 若实现把查表放在投递之后，UI 档满时未知名会得到「待处理过多」——
        // 那是两个完全不同的排查方向。
        val ui = RecordingDispatcher(ThreadModes.UI).apply { execute = false }
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start(dispatchers = mapOf(ThreadModes.UI to ui))
        // 先把 UI 档顶满
        repeat(InvokePolicy.MAX_UI_QUEUE) { i ->
            rt.onInvoke(requestJson("alpha", requestId = "u$i", threadMode = ThreadModes.UI))
        }
        // 再问一个**不存在**的 capability
        rt.onInvoke(requestJson("nope", threadMode = ThreadModes.UI))

        assertEquals(
            "未知名必须仍回 capability_absent（查表在投递之前）",
            CapabilityErrorCode.CAPABILITY_ABSENT,
            awaitResponse().error?.code,
        )
    }

    // ═══ 7 · 停止后的迟到调用（验收 #7）═══════════════════

    @Test
    fun `a late call after stop reports stopped not full`() {
        // ⚠️⚠️ 验收 #7。「已停止」与「满了」的成因**必须分开**：
        // 都报「满」会让用户去查并发，而真实原因是「运行时已停，不会再有响应」。
        HookCapabilityRegistry.register(FakeHandler("alpha") { CapabilityOutcome.Items(emptyList()) })

        val rt = start()
        rt.stop()
        received.clear()

        rt.onInvoke(requestJson("alpha"))

        val r = awaitResponse()
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, r.error?.code)
        assertTrue("detail 必须说明已停止：${r.error!!.detail}", r.error!!.detail.contains("已停止"))
        assertFalse(
            "已停止**不得**说成「满了」",
            r.error!!.detail.contains("待处理请求过多") || r.error!!.detail.contains("工作线程池已满"),
        )
    }

    @Test
    fun `after stop a new request produces no response at all`() {
        // ⚠️⚠️ 验收要求：`stop()` 之后新请求**不再落档** ——
        // `CoroutineScope.cancel()` 之后的 `launch` 是**静默 no-op**（不抛、不执行）。
        //
        // ⚠️ 这条与上一条测的是**两个不同的分支**：
        //  · 上一条走「`stopped` 标志已置 ⇒ 立刻回 runtimeStoppedError」；
        //  · 本条走**闸 ②** —— 经 `launchOnTierForTest` 绕过 `stopped` 前置闸，
        //    唯一能拦住它的就是 `scope.cancel()`。
        // ⇒ 只测上一条**证不了** scope 真的被 cancel 了（标志会把所有情况都兜住）。
        val calls = AtomicInteger(0)
        val h = FakeHandler("alpha") {
            calls.incrementAndGet()
            CapabilityOutcome.Items(emptyList())
        }
        HookCapabilityRegistry.register(h)
        val rt = start()
        val request = requireNotNull(
            CapabilityInvocationCodec.decodeRequest(requestJson("alpha")),
        ) { "自造请求解不开" }

        rt.stop()
        received.clear()

        // 经 scope 投递（绕过 stopped 标志）—— scope 已 cancel ⇒ 这条**不该执行**
        rt.launchOnTierForTest(
            request = request,
            handler = h,
            arrivedAtMs = System.nanoTime(),
            mode = ThreadModes.DEFAULT,
        )
        Thread.sleep(200)

        assertEquals("stop 之后 scope 已取消，不该执行 handler", 0, calls.get())
        assertEquals("stop 之后不该有任何响应", 0, received.size)
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
