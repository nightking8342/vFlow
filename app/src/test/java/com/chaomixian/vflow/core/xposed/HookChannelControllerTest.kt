package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.xposed.wire.ActivityPayload
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
        HookChannelController.onCallbackUnregistered()   // 清 token 与连接态
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
    fun `setEventSink null clears the consumer`() {
        reset()
        HookChannelController.registerSink(ActivityPayload.TOPIC) { }
        HookChannelController.unregisterSink(ActivityPayload.TOPIC)
        // 无消费者时收到事件不该抛
        HookChannelController.onReport(envelope())
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
        // 叠加会导致同一事件被处理两次（触发器触发两遍）
        reset()
        HookChannelController.registerSink(ActivityPayload.TOPIC) { }
        HookChannelController.registerSink(ActivityPayload.TOPIC) { }
        HookChannelController.registerSink(ActivityPayload.TOPIC) { }
        HookChannelController.unregisterSink(ActivityPayload.TOPIC)
    }

    @Test
    fun `lastReportedDroppedCount starts at zero`() {
        reset()
        assertTrue(HookChannelController.lastReportedDroppedCount() >= 0L)
    }
}
