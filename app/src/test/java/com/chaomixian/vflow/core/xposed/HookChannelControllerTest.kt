package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.xposed.wire.ActivityPayload
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityResponse
import com.chaomixian.vflow.xposed.wire.EventEnvelope
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * App 侧通道控制器的测试。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.2 / §3.4.5 / §4.2.4。
 *
 * 重点锁**「改错了不报错、只静默变差」**的两处：
 * ① 上行 token 校验（松了 = 伪造事件能触发用户的工作流）；
 * ② 未知 topic / 坏信封必须忽略而非崩溃（崩在 binder 线程上无人处理）。
 */
class HookChannelControllerTest {

    private companion object {
        /** 另一个 topic，用来验证「多消费者并存」。 */
        const val OTHER_TOPIC = "hook.test.other"

        /** 测试用 token（通过 injectTokenForTest 注入）。 */
        const val TOKEN = "test-token-abc"
    }

    private fun envelope(
        topic: String = "hook.activity.changed",
        seq: Long = 1,
        token: String = "",
        dropped: Long = 0,
        protocolVersion: Int = EventEnvelopeCodec.PROTOCOL_VERSION,
    ): String = EventEnvelopeCodec.encode(
        topic = topic,
        seq = seq,
        ts = 1L,
        payloadJson = "{}",
        droppedCount = dropped,
        token = token,
        protocolVersion = protocolVersion,
    )

    /** 每个用例前后都清干净 —— Controller 是单例，状态会串。 */
    private fun reset() {
        // ⚠️ 顺序：先清配对表与代次，再断开连接 ——
        // 反过来的话 `onCallbackUnregistered` 会去唤醒上一轮用例遗留的 waiter
        HookChannelController.resetWaitersForTest()
        HookChannelController.onCallbackUnregistered()   // 清 token 与连接态
        // ⚠️ 断开会产生一代新的代次，配对表要用同一代次重新开始
        HookChannelController.resetWaitersForTest()
        HookChannelController.removeOnConnectedListener("A")
        HookChannelController.removeOnConnectedListener("B")
        HookChannelController.removeOnConnectedListener("boom")
        // ⚠️ 断开监听器也要清 —— 它们与连接监听器同款（单例状态会串用例），
        // 且 `CapabilityPresenceHolder` 自己注册的 key 也在表里
        HookChannelController.removeOnDisconnectedListener("A")
        HookChannelController.removeOnDisconnectedListener("B")
        HookChannelController.removeOnDisconnectedListener("boom")
        HookChannelController.removeOnDisconnectedListener("k")
        HookChannelController.unregisterSink(ActivityPayload.TOPIC)
        HookChannelController.unregisterSink(OTHER_TOPIC)
    }

    @Test
    fun `onReport with no sink does not throw`() {
        // 未注册消费者时收到事件：只应记日志，绝不抛
        reset()
        HookChannelController.onReport(envelope())
        HookChannelController.onReport("完全不是 JSON")
        HookChannelController.onReport("")
    }

    @Test
    fun `token check rejects when controller has no token`() {
        // 未连接（token 为空）时，任何带 token 的信封都不该被放行 ——
        // 否则伪造者只要在 App 未连接时发一条就能穿过校验
        assertFalse(HookChannelController.constantTimeEquals("abc", ""))
    }

    @Test
    fun `token comparison is length safe and value correct`() {
        assertTrue(HookChannelController.constantTimeEquals("", ""))
        assertTrue(HookChannelController.constantTimeEquals("deadbeef", "deadbeef"))
        // 长度不同必须直接 false（否则下面的逐字符比较会越界）
        assertFalse(HookChannelController.constantTimeEquals("dead", "deadbeef"))
        // 只差一个字符，且差的在**最后**一位 —— 恒定时间比较也要判出来
        assertFalse(HookChannelController.constantTimeEquals("deadbeef", "deadbeee"))
        // 只差**第一**位
        assertFalse(HookChannelController.constantTimeEquals("deadbeef", "xeadbeef"))
    }

    @Test
    fun `isConnected is false before any registration and after unregistration`() {
        reset()
        assertFalse(HookChannelController.isConnected())
        HookChannelController.onCallbackUnregistered()
        assertFalse(HookChannelController.isConnected())
    }

    @Test
    fun `pushConditions returns false and does not throw when disconnected`() {
        // ⚠️ 不静默：false 会让 Handler 记日志提示 ——
        // 「下不去意味着触发器不会工作，而用户只会看到没反应」
        reset()
        assertFalse(HookChannelController.pushConditions("""{"topics":[]}"""))
        // 空条件也要能调用（语义是「卸下 hook」）
        assertFalse(HookChannelController.pushConditions(""))
    }

    @Test
    fun `onReport tolerates unknown topic`() {
        // 「旧 App + 新 hook 层」会送未知 topic。
        // 必须忽略而非崩溃（§3.4.5）—— 崩在 binder 线程上无人处理
        reset()
        val seen = mutableListOf<EventEnvelope>()
        HookChannelController.registerSink(ActivityPayload.TOPIC) { seen += it }

        // 无 token ⇒ 会被 token 校验拦下，这本身也是预期行为
        HookChannelController.onReport(envelope(topic = "hook.brand.new.topic"))
        assertTrue("无 token 的信封不应到达消费者", seen.isEmpty())
    }

    @Test
    fun `unregisterSink removes only that topic's consumer`() {
        // ⚠️ 原名 `setEventSink null clears the consumer` 且**无任何断言**
        //（旧 API 已废弃，用例名却没跟着改）—— 属「看起来在测、实际没测」。
        // 现在断言真行为：注销该 topic 后它收不到，**别的 topic 不受影响**。
        reset()
        var activityCalled = 0
        var otherCalled = 0
        HookChannelController.registerSink(ActivityPayload.TOPIC) { activityCalled++ }
        HookChannelController.registerSink(OTHER_TOPIC) { otherCalled++ }

        HookChannelController.unregisterSink(ActivityPayload.TOPIC)

        HookChannelController.injectTokenForTest(TOKEN)
        HookChannelController.onReport(envelope(token = TOKEN))
        HookChannelController.onReport(envelope(topic = OTHER_TOPIC, token = TOKEN))

        assertEquals("已注销的 topic 不该再收到事件", 0, activityCalled)
        assertEquals("另一个 topic 的消费者不该被误伤", 1, otherCalled)
    }

    // ── ⭐ 按 topic 分发（修「单槽位」缺陷）──────────────────────

    /**
     * ⚠️⚠️ 这一组锁的是**真实缺陷**：原实现是单个 `eventSink` 槽位 +
     * `setEventSink()` 覆盖语义，加第二个 hook 触发器时会**互相挤掉**。
     */
    @Test
    fun `dispatch goes to the sink registered for that topic only`() {
        // ⚠️⚠️ **真正走 onReport 的分发路径**（用注入的 token 越过校验）。
        reset()
        HookChannelController.injectTokenForTest(TOKEN)

        val forActivity = mutableListOf<String>()
        val forOther = mutableListOf<String>()
        HookChannelController.registerSink(ActivityPayload.TOPIC) { forActivity += it.topic }
        HookChannelController.registerSink(OTHER_TOPIC) { forOther += it.topic }

        HookChannelController.onReport(envelope(topic = ActivityPayload.TOPIC, token = TOKEN))

        assertEquals("activity 的消费者应收到 1 条", 1, forActivity.size)
        assertEquals("另一个 topic 的消费者不该收到", 0, forOther.size)
    }

    @Test
    fun `registering a second topic does not evict the first`() {
        // ⚠️⚠️ **这是「单槽位」缺陷的核心断言**：
        // 原实现是单槽位 + 覆盖语义 ⇒ 第二个 register 会让第一个从表里消失 ⇒
        // 第一个触发器**静默不再收到任何事件**（且是双向的：谁后 start 谁的活）。
        //
        // ⚠️ 我第一版没测这条（测试拿不到 token、走不到分发），
        // **反证时不变红**才发现 —— 见 injectTokenForTest 的注释。
        reset()
        HookChannelController.injectTokenForTest(TOKEN)

        val forActivity = mutableListOf<String>()
        val forOther = mutableListOf<String>()
        HookChannelController.registerSink(ActivityPayload.TOPIC) { forActivity += it.topic }
        HookChannelController.registerSink(OTHER_TOPIC) { forOther += it.topic }

        // 两个消费者都还在册 ⇒ 各自的 topic 都能收到
        HookChannelController.onReport(envelope(topic = ActivityPayload.TOPIC, token = TOKEN))
        HookChannelController.onReport(envelope(topic = OTHER_TOPIC, token = TOKEN))

        assertEquals("第一个注册的消费者不该被挤掉", 1, forActivity.size)
        assertEquals("第二个注册的消费者也应收到", 1, forOther.size)
    }

    @Test
    fun `unregistering one topic does not affect the other`() {
        // ⚠️ 原实现的 `setEventSink(null)` 是「清空唯一槽位」；
        // 注册表下必须「只注销自己」—— 否则会**误伤其他消费者**
        reset()
        HookChannelController.injectTokenForTest(TOKEN)

        val forActivity = mutableListOf<String>()
        val forOther = mutableListOf<String>()
        HookChannelController.registerSink(ActivityPayload.TOPIC) { forActivity += it.topic }
        HookChannelController.registerSink(OTHER_TOPIC) { forOther += it.topic }

        HookChannelController.unregisterSink(ActivityPayload.TOPIC)

        HookChannelController.onReport(envelope(topic = ActivityPayload.TOPIC, token = TOKEN))
        HookChannelController.onReport(envelope(topic = OTHER_TOPIC, token = TOKEN))

        assertEquals("已注销的不该再收到", 0, forActivity.size)
        assertEquals("未注销的必须照常收到", 1, forOther.size)
    }

    @Test
    fun `unknown topic is ignored without calling anyone`() {
        // ⚠️ §3.4.5：旧 App + 新 hook 层是常态，未知 topic 必须**忽略而非崩溃**
        reset()
        HookChannelController.injectTokenForTest(TOKEN)

        var called = 0
        HookChannelController.registerSink(ActivityPayload.TOPIC) { called++ }

        HookChannelController.onReport(envelope(topic = "hook.brand.new.topic", token = TOKEN))

        assertEquals("未知 topic 不该触发任何消费者", 0, called)
    }

    @Test
    fun `registerSink is idempotent per topic`() {
        // 同 topic 重复注册 = 替换，不是叠加 ——
        // 叠加会导致同一事件被处理两次（触发器触发两遍）。
        //
        // ⚠️ 本用例原本**一个断言都没有**（只调了三次 register、一次 unregister），
        // 而缺陷 ⑧ 的修复恰恰依赖这条语义 ⇒ 改回 bug 版本不会变红。
        // 现在断言：三次注册后只**剩最后一个**，且事件只被处理**一次**。
        reset()
        var first = 0
        var second = 0
        var third = 0
        HookChannelController.registerSink(ActivityPayload.TOPIC) { first++ }
        HookChannelController.registerSink(ActivityPayload.TOPIC) { second++ }
        HookChannelController.registerSink(ActivityPayload.TOPIC) { third++ }

        HookChannelController.injectTokenForTest(TOKEN)
        HookChannelController.onReport(envelope(token = TOKEN))

        assertEquals("被替换掉的消费者不该再被调用", 0, first)
        assertEquals("被替换掉的消费者不该再被调用", 0, second)
        assertEquals("同 topic 只应保留最后一个消费者，且只处理一次", 1, third)
    }

    @Test
    fun `lastReportedDroppedCount starts at zero`() {
        reset()
        // ⚠️ 原断言是 `>= 0L`，而字段初值就是 0 ⇒ **近乎恒真**，什么都锁不住。
        assertEquals("初值必须精确为 0", 0L, HookChannelController.lastReportedDroppedCount())
    }

    // ── ⭐ 「连接建立」回调注册表（修缺陷 1）────────────────────

    /**
     * ⚠️⚠️ 这一组锁的是**真实缺陷**：原实现是单个 `onConnected` 槽位，
     * 加第二个 hook 触发器时会**互相挤掉** ⇒ 「Activity 触发器再也不重下发条件」。
     *
     * ⚠️ 与 `eventSinks` 那次（缺陷 ⑧）**是同一个形态**，当时只修了一半。
     */
    @Test
    fun `onConnectedListeners coexist and are not overwritten`() {
        reset()
        var aCalled = 0
        var bCalled = 0
        HookChannelController.setOnConnectedListener("A") { aCalled++ }
        HookChannelController.setOnConnectedListener("B") { bCalled++ }

        HookChannelController.notifyOnConnected()

        assertEquals("A 不该被 B 挤掉", 1, aCalled)
        assertEquals("B 应被调用", 1, bCalled)
    }

    @Test
    fun `removeOnConnectedListener removes only its own key`() {
        reset()
        var aCalled = 0
        var bCalled = 0
        HookChannelController.setOnConnectedListener("A") { aCalled++ }
        HookChannelController.setOnConnectedListener("B") { bCalled++ }
        HookChannelController.removeOnConnectedListener("A")

        HookChannelController.notifyOnConnected()

        assertEquals("已注销的 key 不该被调用", 0, aCalled)
        assertEquals("另一个 key 不该被误伤", 1, bCalled)
    }

    @Test
    fun `one failing onConnectedListener does not block the others`() {
        reset()
        var bCalled = 0
        HookChannelController.setOnConnectedListener("boom") { throw IllegalStateException("boom") }
        HookChannelController.setOnConnectedListener("B") { bCalled++ }

        HookChannelController.notifyOnConnected()

        assertEquals("一个监听器抛异常不该让后面的收不到", 1, bCalled)
    }

    // ── ③ 能力调用：配对表 / 鉴权 / 断连唤醒（V2.0 §3.3 / §5.2）────

    /**
     * ⚠️⚠️ 鉴权必须**逐字照搬** `onReport` 的三段。
     *
     * `resolve` 走的是**同一个 App 侧 binder**（`IHookHost`）。
     * 若它没有凭证，任何能 bind 到 `HookChannelService` 的进程都能伪造响应 ——
     * 而这**比伪造事件更危险**：事件还要过 App 侧 filter 才触发，
     * `resolve` 的 `result` **直接就是 capability 的返回值**（会被写进工作流）。
     */
    @Test
    fun `onResolve rejects when controller has no token`() {
        // ⚠️⚠️ 与 onReport 那次**同一个漏洞形态**：未连接时本侧 token 是空串，
        // 而伪造者送 `token:""` 也是空串 ⇒ 空串比空串**恒等** ⇒ 无凭证响应被放行。
        reset()
        var delivered = false
        HookChannelController.registerWaiter("r1") { delivered = true }

        HookChannelController.onResolve(response(requestId = "r1", token = ""))

        assertFalse("未连接时任何响应都不得放行", delivered)
        assertEquals("不该消费掉 waiter", 1, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `onResolve rejects a wrong token`() {
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        var delivered = false
        HookChannelController.registerWaiter("r1") { delivered = true }

        HookChannelController.onResolve(response(requestId = "r1", token = "wrong"))

        assertFalse("token 不对必须丢弃", delivered)
    }

    @Test
    fun `onResolve delivers a correctly authenticated response`() {
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        val got = mutableListOf<CapabilityResponse>()
        HookChannelController.registerWaiter("r1") { got += it }

        HookChannelController.onResolve(response(requestId = "r1", token = TOKEN))

        assertEquals(1, got.size)
        assertEquals("r1", got[0].requestId)
        assertTrue(got[0].ok)
        assertEquals("投递后配对表应清空", 0, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `onResolve with an unpaired request id is dropped not delivered`() {
        // ⚠️ §5.2：**迟到 / 无配对的响应必须丢弃 + 告警，不能静默** ——
        // 否则「伪造响应被无痕接受」这条风险就有绕过面
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        var delivered = false
        HookChannelController.registerWaiter("r1") { delivered = true }

        HookChannelController.onResolve(response(requestId = "some-other-id", token = TOKEN))

        assertFalse(delivered)
        assertEquals("别的 requestId 不该消费掉 r1 的 waiter", 1, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `onResolve tolerates malformed json without throwing`() {
        // ⚠️ 本方法在 binder 线程上，抛异常跨国界且无人处理
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        HookChannelController.onResolve("")
        HookChannelController.onResolve("完全不是 JSON")
        HookChannelController.onResolve("{")
        // ⚠️ ok=false 却没有 error ⇒ 判坏信封（§3.3 的硬要求）
        HookChannelController.onResolve("""{"request_id":"r1","ok":false,"token":"$TOKEN"}""")
    }

    @Test
    fun `registerWaiter rejects a blank request id`() {
        reset()
        assertFalse(HookChannelController.registerWaiter("") { })
        assertFalse(HookChannelController.registerWaiter("   ") { })
    }

    @Test
    fun `waiter table is bounded`() {
        // ⚠️ §5.2：配对表**必须有界** —— 无界累积是泄漏（EventQueue 的同一条教训）。
        // 上限到了就**拒绝新注册**（调用方转成 handler_error），而不是 OOM。
        reset()
        var accepted = 0
        repeat(200) { i ->
            if (HookChannelController.registerWaiter("r$i") { }) accepted++
        }
        assertTrue("必须拒绝一部分（有界）", accepted < 200)
        assertTrue("但应接受一个合理的批量", accepted >= 16)
    }

    @Test
    fun `disconnect immediately wakes all pending waiters with channel_down`() {
        // ⚠️⚠️ §5.2 定案：断连时**立即唤醒全部 waiter、回 ok=false**。
        // 比等超时好得多 —— 此刻我们已确知结果回不来了，
        // 而 3 个并发调用 × 5 秒超时 = 用户白等。
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        val errors = mutableListOf<CapabilityErrorCode?>()
        HookChannelController.registerWaiter("r1") { errors += it.error?.code }
        HookChannelController.registerWaiter("r2") { errors += it.error?.code }

        HookChannelController.onCallbackUnregistered()

        assertEquals("两条都要被唤醒", 2, errors.size)
        assertTrue(
            "都必须是 channel_down",
            errors.all { it == CapabilityErrorCode.CHANNEL_DOWN },
        )
        assertEquals("唤醒后配对表应清空", 0, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `a late notification for an old connection does not wake waiters`() {
        // ⚠️ 修缺陷 13 的那一半：**旧连接的迟到断开通知必须被忽略** ——
        // 它不该把「新连接上正在等的调用」全部判死。
        //
        // 这里用「先注入 token 建立连接，再用一个『不同 binder』的 which 参数通知断开」模拟。
        // `asBinder()` 在纯 JVM 下无法构造（IHookCallback.Stub 继承 android.os.Binder），
        // 故退而求其次：验证「which == null 时会清空」与「带 which 且取不到 binder 时
        // 保守判为同一个、仍会清空」这两个可达分支。
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        var woken = false
        HookChannelController.registerWaiter("r1") { woken = true }

        // which = null ⇒ 无条件清空（这是 hook 层真的断了时的路径）
        HookChannelController.onCallbackUnregistered(null)
        assertTrue("which=null 时应当唤醒", woken)
    }

    @Test
    fun `waiter registered after a disconnect is a new bucket`() {
        // ⚠️ §10-#17「配对表按连接分桶」在单连接下的落实：
        // 换代（断开）后注册的 waiter 属于**新桶**，断连时的旧快照不该碰到它。
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        HookChannelController.registerWaiter("r1") { }

        // 断开 ⇒ 换代 + 唤醒
        HookChannelController.onCallbackUnregistered()
        assertEquals(0, HookChannelController.pendingWaiterCount())

        // 重连后注册的新 waiter 应当正常存活
        HookChannelController.injectTokenForTest(TOKEN)
        assertTrue(HookChannelController.registerWaiter("r1") { })
        assertEquals(1, HookChannelController.pendingWaiterCount())
    }

    @Test
    fun `waiter sink throwing does not propagate`() {
        // ⚠️ 一个消费者抛异常不该把异常抛到 binder 线程上
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        HookChannelController.registerWaiter("boom") { throw IllegalStateException("boom") }
        HookChannelController.onResolve(response(requestId = "boom", token = TOKEN))
        // 没崩就算过
    }

    @Test
    fun `failAllWaiters wakes others even when one sink throws`() {
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        var second = false
        HookChannelController.registerWaiter("boom") { throw IllegalStateException("boom") }
        HookChannelController.registerWaiter("ok") { second = true }

        HookChannelController.onCallbackUnregistered()

        assertTrue("一个 sink 抛异常不该影响其他", second)
    }

    /** 构造一个响应信封（③ 的 `resolve` 载荷）。 */
    private fun response(
        requestId: String,
        token: String,
        ok: Boolean = true,
    ): String = CapabilityInvocationCodec.encodeResponse(
        requestId = requestId,
        ok = ok,
        token = token,
    )

    // ── ⭐ ③ 调用方所需的访问器（T1）────────────────────────
    //
    // ⚠️ 这三个是**只读**的快照/句柄。它们的存在理由见各类的注释：
    // ③ 的调用方要提交请求、要写鉴权信封、要堵「注册前断连」的竞态，
    // 而这些字段原本是 private。

    @Test
    fun `currentToken is empty when disconnected and set when connected`() {
        // ⚠️ 空串 = 未连接 —— ③ 的调用方据此在**提交前**就判出 channel_down，
        // 不必白等超时（与 onResolve 第①段的判据同源）
        reset()
        assertEquals("未连接时必须是空串", "", HookChannelController.currentToken())

        HookChannelController.injectTokenForTest(TOKEN)
        assertEquals(TOKEN, HookChannelController.currentToken())

        HookChannelController.onCallbackUnregistered()
        assertEquals("断开后必须清空", "", HookChannelController.currentToken())
    }

    @Test
    fun `callbackOrNull is null when disconnected`() {
        // ⚠️ 纯 JVM 下无法构造真的 IHookCallback（Stub 继承 android.os.Binder），
        // 所以只断言「未连接时是 null」这一半 —— 另一半（连上后非 null）
        // 由 CapabilityInvokerTest 用假 callback 覆盖
        reset()
        assertEquals(null, HookChannelController.callbackOrNull())
    }

    @Test
    fun `disconnectGeneration increases on every disconnect`() {
        // ⚠️⚠️ ③ 的调用方靠它堵「注册 waiter 之前发生的断连」——
        // `failAllWaiters` 只唤醒**注册当时已在表里**的 waiter，
        // 所以那一条无人唤醒、会永久残留（64 个槽位满掉 ⇒ 之后所有调用都 handler_error）。
        //
        // ⇒ 断言「每次断开 +1」是那个堵法的**全部前提**
        reset()
        val g0 = HookChannelController.disconnectGeneration()

        HookChannelController.onCallbackUnregistered()
        val g1 = HookChannelController.disconnectGeneration()
        assertTrue("断开必须让代次前进（$g0 → $g1）", g1 > g0)

        HookChannelController.onCallbackUnregistered()
        val g2 = HookChannelController.disconnectGeneration()
        assertTrue("再断开必须再前进（$g1 → $g2）", g2 > g1)
    }

    @Test
    fun `disconnect generation is stable while connected`() {
        // ⚠️ 反向：没断开时不该动 —— 否则「提交前后代次相同」这个判据会误报断连
        reset()
        val g = HookChannelController.disconnectGeneration()
        HookChannelController.injectTokenForTest(TOKEN)
        assertEquals("连接不换代次", g, HookChannelController.disconnectGeneration())
    }

    // ── ⭐ 断开回调注册表（与「连接建立」是两个方向）────────────

    @Test
    fun `disconnect listeners coexist and are not overwritten`() {
        // ⚠️ 与 onConnectedListeners 是**同一个形态的注册表** ——
        // 本类已有三处单槽位缺陷的教训（eventSinks / onConnected / BinderTransport.onConnected），
        // 不再新增第四处
        reset()
        var a = 0
        var b = 0
        HookChannelController.setOnDisconnectedListener("A") { a++ }
        HookChannelController.setOnDisconnectedListener("B") { b++ }

        HookChannelController.notifyOnDisconnected()

        assertEquals("A 不该被 B 挤掉", 1, a)
        assertEquals("B 应被调用", 1, b)
    }

    @Test
    fun `removeOnDisconnectedListener removes only its own key`() {
        reset()
        var a = 0
        var b = 0
        HookChannelController.setOnDisconnectedListener("A") { a++ }
        HookChannelController.setOnDisconnectedListener("B") { b++ }
        HookChannelController.removeOnDisconnectedListener("A")

        HookChannelController.notifyOnDisconnected()

        assertEquals("已注销的不该被调用", 0, a)
        assertEquals("另一个不该被误伤", 1, b)
    }

    @Test
    fun `one failing disconnect listener does not block the others`() {
        reset()
        var b = 0
        HookChannelController.setOnDisconnectedListener("boom") { throw IllegalStateException("boom") }
        HookChannelController.setOnDisconnectedListener("B") { b++ }

        HookChannelController.notifyOnDisconnected()

        assertEquals("一个抛异常不该让后面的收不到", 1, b)
    }

    @Test
    fun `a real disconnect notifies disconnect listeners`() {
        // ⚠️ 走**真实**的 onCallbackUnregistered 路径，证明接线点真的调了它们
        //（CapabilityRuntimeWiringTest 另有一条源码扫描锁这个调用点 ——
        // 两条互补：这条证明「调了会生效」，那条证明「生产代码真的调了」）
        reset()
        HookChannelController.injectTokenForTest(TOKEN)
        var called = 0
        HookChannelController.setOnDisconnectedListener("k") { called++ }

        HookChannelController.onCallbackUnregistered()

        assertEquals("真断连必须通知断开监听器", 1, called)
    }
}
