package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.xposed.capability.Capability
import com.chaomixian.vflow.xposed.capability.CapabilityFallbackPlan
import com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome
import com.chaomixian.vflow.xposed.capability.CapabilityPresence
import com.chaomixian.vflow.xposed.capability.CapabilityRegistry
import com.chaomixian.vflow.xposed.capability.CapabilityRisk
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityResponse
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ③ 调用入口的测试（`core/xposed/CapabilityInvoker.kt`）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.4 / §5.2 / §6.2 / §6.3。
 *
 * 重点锁**「改错了不报错、只静默变差」**的几条：
 * ① **超时后 waiter 必须被注销** —— 不注销就是泄漏，`MAX_WAITERS = 64` 满了之后
 *    **所有**调用都回 `handler_error`（「这个功能突然全坏了」）；
 * ② **配对表满时必须立刻返回** —— 走超时路径会让用户白等 5 秒；
 * ③ **断连必须立刻 `channel_down`**（两个方向：注册前 / 注册后），不白等超时；
 * ④ **`presence == ABSENT` 时不去试** —— 否则每次白等 5 秒（§6.3 的唯一用途）；
 * ⑤ **降级分派**：`fallback == null` ⇒ `Failed`；`!= null` ⇒ `Degraded` 且**留痕**。
 *
 * ⚠️ 计时断言用一个**宽松的上界**（`< 100ms` vs 5 秒超时）：本机是 Windows + Gradle，
 * 精确耗时不可靠。断言的是「**没有走超时路径**」这个量级差别，不是性能。
 */
class CapabilityInvokerTest {

    private companion object {
        const val CAP = "test_capability"
        const val EXCLUSIVE = "test_exclusive"
        const val OTHER = "test_other"
        const val TOKEN = "test-token-xyz"

        /** 一个不会真的等到的超时（用例用它证明「没走超时路径」）。 */
        const val LONG_TIMEOUT = 5_000L
    }

    /** 把全部单例状态清干净（它们是进程级的，会串用例）。 */
    @Before
    fun setUp() = resetAll()

    @After
    fun tearDown() = resetAll()

    private fun resetAll() {
        HookChannelController.resetWaitersForTest()
        HookChannelController.onCallbackUnregistered()
        HookChannelController.resetWaitersForTest()
        CapabilityInvoker.attachHooksForTest(null)
        CapabilityInvoker.resetForTest()
        CapabilityRegistry.resetForTest()
        CapabilityFallbacks.resetForTest()
        CapabilityPresenceHolder.resetForTest()
        CapabilityRuntime.resetForTest()
    }

    // ── 测试脚手架 ────────────────────────────────────────────

    /** 注册一个能力（可选择带降级实现）。 */
    private fun registerCap(
        name: String = CAP,
        fallback: (suspend (params: Map<String, Any?>) -> Map<String, Any?>)? = null,
        timeoutMs: Long? = null,
    ): Capability {
        val c = Capability(
            name = name,
            risk = CapabilityRisk.READ_ONLY,
            fallback = fallback,
            timeoutMs = timeoutMs,
        )
        CapabilityRegistry.register(c)
        return c
    }

    /**
     * 建立一条「已连接」的通道：注册假 callback，并把它的 token 注入 Controller。
     *
     * ⚠️ 顺序：`onCallbackRegistered(cb)` 会**生成新 token**（`SecureRandom`），
     * 测试拿不到 ⇒ 必须用 `currentToken()` 读回来后 `injectTokenForTest` 覆盖成已知值
     * （`injectTokenForTest` 的注释里写着这个理由）。
     */
    private fun connect(cb: FakeHookCallback): FakeHookCallback {
        HookChannelController.onCallbackRegistered(cb)
        HookChannelController.injectTokenForTest(TOKEN)
        return cb
    }

    /**
     * 让假 callback 收到请求后**自动回一个成功响应**，走真实的 Controller 分发。
     *
     * ⚠️ 这条路穿过：`invoke` → `capabilities` 编解码 → `onResolve` 的
     * **三段鉴权** → **配对表** → 唤醒 waiter。不是把结果直接塞给 deferred ——
     * 后者测不出鉴权与配对通不通。
     */
    private fun autoSucceed(cb: FakeHookCallback, resultJson: String = """{"ok":true}""") {
        cb.autoRespond = { reqJson ->
            FakeHookCallback.successResponse(reqJson, resultJson)?.let {
                HookChannelController.onResolve(it)
            }
        }
    }

    private fun autoFail(cb: FakeHookCallback, code: CapabilityErrorCode = CapabilityErrorCode.HANDLER_ERROR) {
        cb.autoRespond = { reqJson ->
            FakeHookCallback.failureResponse(reqJson, code)?.let {
                HookChannelController.onResolve(it)
            }
        }
    }

    // ══════════════════ ① 成功路径 ══════════════════

    @Test
    fun `successful round trip returns the result`() = runBlocking {
        registerCap()
        val cb = connect(FakeHookCallback())
        autoSucceed(cb, """{"count":3}""")

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)

        assertTrue("应为 Success，实际 $outcome", outcome is CapabilityInvokeOutcome.Success)
        val s = outcome as CapabilityInvokeOutcome.Success
        assertEquals("结果应能读到 hook 侧塞的键", 3, s.result["count"])
        assertEquals(7L, s.elapsedMs)
        assertEquals("配对成功后表应清空", 0, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `success carries pagination fields through`() = runBlocking {
        // §3.6 契约 3/4：nextCursor / truncated 必须**透传**到结果类型 ——
        // 丢了它们，「少了几项」会被当成「本来就没有」
        registerCap()
        val cb = connect(FakeHookCallback())
        cb.autoRespond = { reqJson ->
            val req = CapabilityInvocationCodec.decodeRequest(reqJson)
            if (req != null) {
                HookChannelController.onResolve(
                    CapabilityInvocationCodec.encodeResponse(
                        requestId = req.requestId,
                        ok = true,
                        resultJson = "{}",
                        nextCursor = "12",
                        truncated = true,
                        token = req.token,
                    ),
                )
            }
        }

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)
            as CapabilityInvokeOutcome.Success

        assertEquals("下一页游标必须透传", "12", outcome.nextCursor)
        assertTrue("截断标志必须透传", outcome.truncated)
    }

    // ══════════════════ ② 超时 + waiter 注销（核心）══════════════════

    @Test
    fun `timeout unregisters the waiter`() = runBlocking {
        // ⚠️⚠️ 本文件最要紧的一条：不注销 = 泄漏 ⇒ 64 个槽位满了之后
        // **所有**调用都回 handler_error（用户看到「这个功能突然全坏了」）
        registerCap()
        val cb = connect(FakeHookCallback())   // 故意**不**自动应答 ⇒ 必然超时

        val started = System.currentTimeMillis()
        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = 120L)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("应为 Failed，实际 $outcome", outcome is CapabilityInvokeOutcome.Failed)
        assertEquals(CapabilityErrorCode.TIMEOUT, (outcome as CapabilityInvokeOutcome.Failed).failure.code)
        assertEquals("⚠️ 超时后 waiter 必须归零（否则泄漏）", 0, HookChannelController.pendingWaiterCount())
        assertEquals("请求应已提交（证明走完了提交那一步）", 1, cb.invokeCount)
        assertTrue("应在超时附近返回（实测 ${elapsed}ms）", elapsed < 3_000)
    }

    @Test
    fun `late response after timeout is dropped without leaking`() = runBlocking {
        // ⚠️ 超时之后的迟到响应：不该崩、不该重新占用配对表。
        // 旧实现下它被静默吞掉（waiters.remove 后 sink==null 走「无配对」日志）——
        // 现在有一条 warning 能区分「真没回」与「回得太晚」
        registerCap()
        val cb = connect(FakeHookCallback())
        var lateRequestId: String? = null
        cb.autoRespond = { reqJson ->
            lateRequestId = CapabilityInvocationCodec.decodeRequest(reqJson)?.requestId
        }

        CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = 100L)
        assertEquals(0, HookChannelController.pendingWaiterCount())

        // 现在喂一条**迟到的**响应（token 用注入的那个）
        val id = lateRequestId
        assertNotNull("应已记录 requestId", id)
        HookChannelController.onResolve(
            CapabilityInvocationCodec.encodeResponse(id!!, ok = true, token = TOKEN),
        )

        assertEquals("迟到响应不该重新占用配对表", 0, HookChannelController.pendingWaiterCount())
    }

    // ══════════════════ ③ 配对表满 ══════════════════

    @Test
    fun `full waiter table fails fast with handler_error`() = runBlocking {
        // ⚠️⚠️ §5.2：配对表**必须有界**，满了就拒绝新注册。
        // 且**必须立刻返回** —— 走超时路径会让用户白等满 5 秒（那是「立刻就知道做不了」）
        registerCap()
        val cb = connect(FakeHookCallback())

        // 先占满（用一批假的 requestId）
        var occupied = 0
        while (HookChannelController.registerWaiter("filler-$occupied") { }) occupied++
        assertTrue("应能占满一个合理的批量", occupied >= 16)

        val started = System.currentTimeMillis()
        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = LONG_TIMEOUT)
        val elapsed = System.currentTimeMillis() - started

        assertTrue("应为 Failed，实际 $outcome", outcome is CapabilityInvokeOutcome.Failed)
        val f = outcome as CapabilityInvokeOutcome.Failed
        assertEquals(
            "⚠️ 池满归 HANDLER_ERROR 而不是 TIMEOUT（口径写死在 CapabilityErrorCode 里）",
            CapabilityErrorCode.HANDLER_ERROR,
            f.failure.code,
        )
        assertTrue("detail 应说明是配对表满：${f.failure.detail}", f.failure.detail.contains("配对表"))
        assertTrue(
            "⚠️ 必须**立刻**返回（实测 ${elapsed}ms）—— 不能走超时路径",
            elapsed < 500,
        )
        assertEquals("请求不该被提交", 0, cb.invokeCount)
    }

    // ══════════════════ ④ 断连（两个方向）══════════════════

    @Test
    fun `disconnect before invocation fails immediately without waiting`() = runBlocking {
        // 方向 A：调用**之前**就没连着 ⇒ 步骤 2 的连接闸拦下。
        // ⚠️ 断言「没白等」——这是「不白等超时」这条纪律的正面证据
        registerCap()
        // 不 connect：callback 与 token 都空

        val started = System.currentTimeMillis()
        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = LONG_TIMEOUT)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(
            CapabilityErrorCode.CHANNEL_DOWN,
            (outcome as CapabilityInvokeOutcome.Failed).failure.code,
        )
        assertTrue("⚠️ 必须立刻返回（实测 ${elapsed}ms）", elapsed < 500)
        assertEquals(0, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `disconnect after registration returns channel_down immediately`() = runBlocking {
        // 方向 B：waiter 注册**之后**断连 ⇒ `failAllWaiters` 唤醒它。
        // ⚠️ 比等超时好得多 —— 此刻我们已确知结果回不来了（§5.2 定案）
        registerCap()
        val cb = connect(FakeHookCallback())
        // 收到请求时**立刻断开**，再尝试回响应（回不去，因为已断）
        cb.autoRespond = { reqJson ->
            HookChannelController.onCallbackUnregistered(cb)
        }

        val started = System.currentTimeMillis()
        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = LONG_TIMEOUT)
        val elapsed = System.currentTimeMillis() - started

        assertEquals(
            "断开后应拿到 channel_down，实际 $outcome",
            CapabilityErrorCode.CHANNEL_DOWN,
            (outcome as CapabilityInvokeOutcome.Failed).failure.code,
        )
        assertTrue("⚠️ 必须立刻返回（实测 ${elapsed}ms），不能等满超时", elapsed < 1_000)
        assertEquals("⚠️ 唤醒后配对表必须归零", 0, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `disconnect detected by generation check unregisters the waiter`() = runBlocking {
        // ⚠️⚠️ **反证 A 的目标用例**。
        //
        // 覆盖的是「断连发生在 waiter 注册成功之后、但由**代次变化**表达」这一格 ——
        // `failAllWaiters` 只唤醒**注册当时已在表里**的 waiter；若断连恰好发生在
        // 「读了 callback/token」与「registerWaiter」之间，那条 waiter **没人唤醒**，
        // 会永久残留（64 个槽位满掉 ⇒ 之后所有调用都 handler_error）。
        //
        // ⇒ 用假 hooks 精确制造这个时机：registerWaiter 成功，然后把代次 +1
        //（模拟 `failAllWaiters` 里的 `connectionGeneration.incrementAndGet()`）。
        registerCap()
        val cb = connect(FakeHookCallback())
        val fakeHooks = object : CapabilityInvoker.Hooks {
            var generation = 0L
            override fun callback() = cb
            override fun token() = TOKEN
            override fun registerWaiter(requestId: String, sink: (CapabilityResponse) -> Unit): Boolean {
                val ok = HookChannelController.registerWaiter(requestId, sink)
                // ⚠️ 就在注册成功之后换代 —— 精确对准那个窗口
                generation++
                return ok
            }
            override fun unregisterWaiter(requestId: String) {
                HookChannelController.unregisterWaiter(requestId)
            }
            override fun disconnectGeneration(): Long = generation
        }
        CapabilityInvoker.attachHooksForTest(fakeHooks)

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = 120L)

        assertEquals(
            "代次变化应判 channel_down，实际 $outcome",
            CapabilityErrorCode.CHANNEL_DOWN,
            (outcome as CapabilityInvokeOutcome.Failed).failure.code,
        )
        assertEquals(
            "⚠️⚠️ 代次复查命中时必须注销 waiter（否则永久残留）",
            0,
            HookChannelController.pendingWaiterCount(),
        )
        assertEquals("请求不该被提交（代次已经变了）", 0, cb.invokeCount)
    }

    // ══════════════════ ⑤ presence 避免白试 ══════════════════

    @Test
    fun `absent presence fails locally without touching the channel`() = runBlocking {
        // ⚠️⚠️ §6.3 的**唯一用途**：旧 hook 层没有 invoke 方法时，
        // oneway 调用会被 binder **静默丢弃** ⇒ 不判就会每次白等满 5 秒。
        //
        // ⚠️ **反证 C 的目标用例**：删掉 `presence == ABSENT` 那段短路会变红
        registerCap()
        val cb = connect(FakeHookCallback())
        CapabilityPresenceHolder.setForTest(CapabilityPresence.ABSENT)

        val started = System.currentTimeMillis()
        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = LONG_TIMEOUT)
        val elapsed = System.currentTimeMillis() - started

        val f = outcome as CapabilityInvokeOutcome.Failed
        assertEquals(CapabilityErrorCode.CAPABILITY_ABSENT, f.failure.code)
        assertEquals(
            "⚠️ ABSENT 指向「升级/重启 App」，不是「去改 LSPosed 配置」（§6.4）",
            com.chaomixian.vflow.xposed.wire.CapabilityErrorAction.UPGRADE_APP,
            f.failure.userAction,
        )
        assertEquals("⚠️ 一次都不该碰 hook 层（白试就是这里发生的）", 0, cb.invokeCount)
        assertEquals("也不该占用配对表", 0, HookChannelController.pendingWaiterCount())
        assertTrue("应立刻返回（实测 ${elapsed}ms）", elapsed < 500)
    }

    @Test
    fun `unknown presence still tries the call`() = runBlocking {
        // ⚠️⚠️ UNKNOWN 是「拿不到答案」，**不是**「方法不存在」——
        // 当失败会误杀「实现了 capabilities() 但没实现 ping()」的 hook 层。
        // 代价只是「可能白等一次超时」，那是可接受的一侧（§7.2 的取舍）
        registerCap()
        val cb = connect(FakeHookCallback())
        autoSucceed(cb)
        CapabilityPresenceHolder.setForTest(CapabilityPresence.UNKNOWN)

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)

        assertTrue("UNKNOWN 时必须照常调用，实际 $outcome", outcome is CapabilityInvokeOutcome.Success)
        assertEquals("确实发起了调用", 1, cb.invokeCount)
    }

    // ══════════════════ ⑥ 未知 capability ══════════════════

    @Test
    fun `unregistered capability fails with handler_error`() = runBlocking {
        // ⚠️ §3.2/§4.3：未知 capability **必须显式报错**，与「未知 topic 忽略」方向相反 ——
        // 调用方在等结果，静默会让超时把排查引向错误方向
        val cb = connect(FakeHookCallback())
        autoSucceed(cb)

        val outcome = CapabilityInvoker.invoke("never_registered", emptyMap(), LONG_TIMEOUT)

        val f = outcome as CapabilityInvokeOutcome.Failed
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, f.failure.code)
        assertTrue("detail 应含能力名，便于排查：${f.failure.detail}", f.failure.detail.contains("never_registered"))
        assertEquals("不该提交任何请求", 0, cb.invokeCount)
    }

    // ══════════════════ ⑦ 降级分派 ══════════════════

    @Test
    fun `exclusive capability without fallback returns Failed`() = runBlocking {
        // ⚠️ §6.2：`fallback == null` ⇒ **独占型** —— 无从降级，失败就明确告知
        registerCap(name = EXCLUSIVE, fallback = null)
        val cb = connect(FakeHookCallback())
        autoFail(cb, CapabilityErrorCode.CAPABILITY_ABSENT)

        val outcome = CapabilityInvoker.invokeOrFallback(EXCLUSIVE, emptyMap(), LONG_TIMEOUT)

        assertTrue("应为 Failed，实际 $outcome", outcome is CapabilityInvokeOutcome.Failed)
        val f = outcome as CapabilityInvokeOutcome.Failed
        assertEquals(CapabilityErrorCode.CAPABILITY_ABSENT, f.failure.code)
        assertEquals(
            "独占型的引导指向 App 侧（升级/重启）",
            CapabilityErrorAction_UPGRADE_APP,
            f.failure.userAction,
        )
    }

    @Test
    fun `replacing capability degrades and leaves a trace`() = runBlocking {
        // ⚠️⚠️ §6.2：`fallback != null` ⇒ **替换型** —— 静默降级 + **留痕**。
        // 留痕是本类型存在的理由：同一台设备降级前后结果不同，
        // 不留痕的话「昨天能用今天不能用」会被当成回归
        var fallbackCalls = 0
        registerCap(name = CAP, fallback = { params ->
            fallbackCalls++
            mapOf("via" to "fallback", "echo" to params["q"])
        })
        val cb = connect(FakeHookCallback())
        autoFail(cb, CapabilityErrorCode.CHANNEL_DOWN)

        val outcome = CapabilityInvoker.invokeOrFallback(CAP, mapOf("q" to "hello"), LONG_TIMEOUT)

        assertTrue("应为 Degraded，实际 $outcome", outcome is CapabilityInvokeOutcome.Degraded)
        val d = outcome as CapabilityInvokeOutcome.Degraded
        assertEquals(1, fallbackCalls)
        assertEquals("结果是降级实现的产物", "fallback", d.result["via"])
        assertEquals("⚠️ 必须带上「为什么降级」", CapabilityErrorCode.CHANNEL_DOWN, d.reason.code)
        assertEquals("同样入参的契约：params 应原样传下去", "hello", d.result["echo"])
    }

    @Test
    fun `fallback is not called on success`() = runBlocking {
        // ⚠️ 成功时**不得**执行降级 —— 那会做一次无谓的有损替换
        var fallbackCalls = 0
        registerCap(name = CAP, fallback = { fallbackCalls++; mapOf("via" to "fallback") })
        val cb = connect(FakeHookCallback())
        autoSucceed(cb, """{"via":"hook"}""")

        val outcome = CapabilityInvoker.invokeOrFallback(CAP, emptyMap(), LONG_TIMEOUT)

        assertTrue(outcome is CapabilityInvokeOutcome.Success)
        assertEquals("成功路径必须走 hook 的结果", "hook", (outcome as CapabilityInvokeOutcome.Success).result["via"])
        assertEquals("⚠️ fallback 不该被调用", 0, fallbackCalls)
    }

    @Test
    fun `degrade failure does not throw and reports both causes`() = runBlocking {
        // ⚠️ 降级实现自己也坏了：不能抛（契约是「不抛」），也不能静默
        //（静默 = 「功能没有了但没人知道」）。归 Failed，detail 里写清两件事
        registerCap(name = CAP, fallback = { throw IllegalStateException("降级也坏了") })
        val cb = connect(FakeHookCallback())
        autoFail(cb, CapabilityErrorCode.TIMEOUT)

        val outcome = CapabilityInvoker.invokeOrFallback(CAP, emptyMap(), LONG_TIMEOUT)

        val f = outcome as CapabilityInvokeOutcome.Failed
        assertEquals("保留原失败码", CapabilityErrorCode.TIMEOUT, f.failure.code)
        assertTrue("detail 应说明降级也失败了：${f.failure.detail}", f.failure.detail.contains("降级"))
    }

    @Test
    fun `fallback plan distinguishes the two kinds`() {
        registerCap(name = EXCLUSIVE, fallback = null)
        registerCap(name = CAP, fallback = { it })

        assertEquals(CapabilityFallbackPlan.Unavailable, CapabilityFallbacks.planOf(EXCLUSIVE))
        assertEquals(CapabilityFallbackPlan.NativeCode, CapabilityFallbacks.planOf(CAP))
        // 未注册的也判 Unavailable（无从降级）
        assertEquals(CapabilityFallbackPlan.Unavailable, CapabilityFallbacks.planOf("nope"))
    }

    // ══════════════════ ⑧ 参数与结果序列化 ══════════════════

    @Test
    fun `nested params survive serialization`() = runBlocking {
        // ⚠️⚠️ **反证 B 的目标用例**。
        //
        // 实测对照（org.json:json:20251224，与测试 classpath 同版本）：
        //   JSONObject(params).toString()  ⇒ {"obj":{"k":"v"},"list":[1,2]}   ✅ 深层正确
        //   params.toString()（bug 版本）  ⇒ {obj={k=v}, list=[1, 2]}          ❌ 非法 JSON
        //     ⇒ hook 侧 JSONObject(...) 解析抛异常，**什么都拿不到**
        registerCap()
        val cb = connect(FakeHookCallback())
        autoSucceed(cb)

        CapabilityInvoker.invoke(
            CAP,
            mapOf(
                "obj" to mapOf("k" to "v"),
                "list" to listOf(1, 2, 3),
                "s" to "plain",
            ),
            LONG_TIMEOUT,
        )

        assertEquals(1, cb.invokeCount)
        val req = CapabilityInvocationCodec.decodeRequest(cb.receivedRequests[0])
        assertNotNull("请求应能解析", req)

        // ⚠️ 关键：params 串必须能被 org.json **解析**出嵌套结构。
        // 用 Map.toString() 时下面第一行就会抛（而不是断言失败）——那也是变红
        val params = JSONObject(req!!.paramsJson)
        assertTrue("嵌套 Map 必须仍是对象", params.get("obj") is JSONObject)
        assertEquals("v", params.getJSONObject("obj").getString("k"))
        assertTrue("嵌套 List 必须仍是数组", params.get("list") is JSONArray)
        assertEquals(3, params.getJSONArray("list").length())
        assertEquals("plain", params.getString("s"))
    }

    @Test
    fun `build request json carries the timeout and capability`() {
        val json = CapabilityInvoker.buildRequestJson(
            capability = CAP,
            params = mapOf("a" to 1),
            timeoutMs = 1234L,
            requestId = "rid",
            token = "tk",
        )
        val req = CapabilityInvocationCodec.decodeRequest(json)!!
        assertEquals(CAP, req.capability)
        assertEquals(1234L, req.timeoutMs)
        assertEquals("rid", req.requestId)
        assertEquals("tk", req.token)
    }

    @Test
    fun `null timeout falls back to the codec default`() {
        val json = CapabilityInvoker.buildRequestJson(CAP, emptyMap(), null, "rid", token = "tk")
        assertEquals(
            CapabilityInvocationCodec.DEFAULT_TIMEOUT_MS,
            CapabilityInvocationCodec.decodeRequest(json)!!.timeoutMs,
        )
    }

    @Test
    fun `capability declared timeout is used when caller passes none`() = runBlocking {
        // ⚠️ §5.2：逐 capability 可覆盖超时（不同能力耗时差异很大）。
        // 「声明被忽略、永远用默认值」是静默的 —— 断言请求里带的是声明值
        registerCap(name = CAP, timeoutMs = 250L)
        val cb = connect(FakeHookCallback())
        autoSucceed(cb)

        CapabilityInvoker.invoke(CAP, emptyMap(), timeoutMs = null)

        val req = CapabilityInvocationCodec.decodeRequest(cb.receivedRequests[0])!!
        assertEquals("应用 capability 声明的超时", 250L, req.timeoutMs)
    }

    // ══════════════════ ⑨ 结果不是合法 JSON ══════════════════

    @Test
    fun `non json result fails with handler_error not timeout`() = runBlocking {
        // ⚠️ 「成功但结果解不出来」是**协议层问题**（hook 层违反了 result 是 JSON 对象的约定），
        // 归 HANDLER_ERROR（指向「看具体能力」），不是 TIMEOUT
        //（那会把用户引向「性能问题」这个错误方向）
        registerCap()
        val cb = connect(FakeHookCallback())
        cb.autoRespond = { reqJson ->
            val req = CapabilityInvocationCodec.decodeRequest(reqJson)
            if (req != null) {
                HookChannelController.onResolve(
                    CapabilityInvocationCodec.encodeResponse(
                        requestId = req.requestId,
                        ok = true,
                        resultJson = "这不是 JSON",
                        token = req.token,
                    ),
                )
            }
        }

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)

        assertEquals(
            CapabilityErrorCode.HANDLER_ERROR,
            (outcome as CapabilityInvokeOutcome.Failed).failure.code,
        )
    }

    @Test
    fun `hook side failure code is passed through verbatim`() = runBlocking {
        // ⚠️ hook 层「收到了请求但不会做」时必须显式报错（§3.2/§4.3）——
        // 这与「hook 层根本没这个方法」（收不到 ⇒ 超时）是不同层次的失败。
        // 本用例锁「原样透传」，不重映射成 TIMEOUT
        registerCap()
        val cb = connect(FakeHookCallback())
        autoFail(cb, CapabilityErrorCode.CAPABILITY_ABSENT)

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)

        assertEquals(
            CapabilityErrorCode.CAPABILITY_ABSENT,
            (outcome as CapabilityInvokeOutcome.Failed).failure.code,
        )
    }

    /** 本地别名（避免与通配 import 混淆）。 */
    private val CapabilityErrorAction_UPGRADE_APP =
        com.chaomixian.vflow.xposed.wire.CapabilityErrorAction.UPGRADE_APP

    @Test
    fun `submitting after token cleared by a real disconnect is channel down`() = runBlocking {
        // 一个更贴近真实的组合：连上 → 断开 → 立刻调用。
        // （`onCallbackUnregistered()` 会清 token 与 callback）
        registerCap()
        connect(FakeHookCallback())
        HookChannelController.onCallbackUnregistered()

        val outcome = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)
        assertEquals(
            CapabilityErrorCode.CHANNEL_DOWN,
            (outcome as CapabilityInvokeOutcome.Failed).failure.code,
        )
    }

    @Test
    fun `other capability failure does not affect this one`() = runBlocking {
        // 防「配对表按 requestId 之外的东西分桶」这类错（两条并发互不干扰）
        registerCap(name = CAP)
        registerCap(name = OTHER)
        val cb = connect(FakeHookCallback())
        autoSucceed(cb)

        val a = CapabilityInvoker.invoke(CAP, emptyMap(), LONG_TIMEOUT)
        val b = CapabilityInvoker.invoke(OTHER, emptyMap(), LONG_TIMEOUT)

        assertTrue(a is CapabilityInvokeOutcome.Success)
        assertTrue(b is CapabilityInvokeOutcome.Success)
        assertEquals(CAP, a.capability)
        assertEquals(OTHER, b.capability)
        assertFalse("两条调用的 requestId 不该相同", cb.receivedRequestIds[0] == cb.receivedRequestIds[1])
    }
}
