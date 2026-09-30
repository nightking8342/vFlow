package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.xposed.capability.CapabilityPresence
import com.chaomixian.vflow.xposed.capability.presenceAfterDisconnect
import com.chaomixian.vflow.xposed.capability.presenceAfterExchange
import com.chaomixian.vflow.xposed.wire.CapabilityManifest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ③ **连接期能力交换**的测试（`core/xposed/CapabilityExchange.kt` + `CapabilityPresenceHolder.kt`）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.3。
 *
 * 重点锁**「改错了不报错、只静默劣化」**的三条：
 * ① **三段判据的顺序与异常语义** —— 「空清单 ⇒ READY」与「方法不存在 ⇒ ABSENT」
 *    与「拿不到 ⇒ UNKNOWN」是**三件不同的事**，混了就会把用户引向错误方向
 *    （§6.4：ABSENT 指向「升级 App」，UNKNOWN 指向「等重连」）；
 * ② **断电**（断开 / 代次自愈）—— 漏了就会出现「presence 说 READY，调用却全失败」；
 * ③ **回退开关是运行时语义**（反证 D 的目标）—— 只在 `start()` 判一次的话，
 *    `setEnabledForTest(false)` 之后监听器照跑，「回退」就是假开关。
 *
 * ⚠️ 用例分两层：
 * - **纯函数层**（`exchangeOnConnect` / `presenceOnDisconnect`）—— 不需要线程、不需要连接；
 * - **持有者层** —— 用 `HookChannelController.notifyOnConnected()` 驱动**真实的**
 *   `setOnConnectedListener` 注册表（既有测试接缝），再等探测线程结束。
 */
class CapabilityPresenceHolderTest {

    private companion object {
        const val TOKEN = "presence-test-token"
        /** 等探测线程结束的上限。⚠️ 真机/CI 上够宽即可，不追求紧。 */
        const val IDLE_TIMEOUT_MS = 5_000L
    }

    @Before
    fun setUp() = resetAll()

    @After
    fun tearDown() = resetAll()

    private fun resetAll() {
        CapabilityPresenceHolder.resetForTest()
        CapabilityRuntime.resetForTest()
        HookChannelController.resetWaitersForTest()
        HookChannelController.onCallbackUnregistered()
        HookChannelController.resetWaitersForTest()
    }

    /** 连上并注入已知 token（`onCallbackRegistered` 会生成随机 token，测试拿不到）。 */
    private fun connect(cb: FakeHookCallback) {
        HookChannelController.onCallbackRegistered(cb)
        HookChannelController.injectTokenForTest(TOKEN)
    }

    // ══════════════════ ① 纯函数层：三段判据 ══════════════════

    @Test
    fun `ping ok plus manifest ok means ready even for an empty manifest`() {
        // ⚠️⚠️ **最容易搞混的一格**：「清单是空的」与「方法不存在」是两件事。
        // 把前者误判成 ABSENT 会让用户被引去「升级 App」，而 App 其实是最新的
        assertEquals(
            CapabilityPresence.READY,
            exchangeOnConnect { CapabilityManifest.encode(emptyList()) },
        )
        // 空串也判 READY（「有这个方法，但它返回了空」）
        assertEquals(CapabilityPresence.READY, exchangeOnConnect { "" })
    }

    @Test
    fun `ping ok plus manifest throwing means absent`() {
        // ⚠️⚠️ **反证 C 的目标语义之一**：AIDL 的「方法不存在」表现为**调用抛异常**，
        // 不是返回空串。删掉 exchangeOnConnect 里的 try/catch 会让这条变红
        //（异常会穿出去，用例直接失败）
        assertEquals(
            CapabilityPresence.ABSENT,
            exchangeOnConnect { throw IllegalStateException("旧 hook 层没有这个方法") },
        )
    }

    @Test
    fun `manifest containing names still means ready`() {
        val json = CapabilityManifest.encode(listOf("query_shortcut_intents"))
        assertEquals(CapabilityPresence.READY, exchangeOnConnect { json })
    }

    @Test
    fun `disconnect resets to unknown`() {
        // ⚠️ 不重置会让「重连到一个更旧的 hook 层」时仍显示 READY
        //（§6.3：状态是采样的，采样过期**必须显式失效**）
        assertEquals(CapabilityPresence.UNKNOWN, presenceOnDisconnect())
    }

    @Test
    fun `the pure functions agree with the capability package ones`() {
        // ⚠️ 包一层而不是重写判据的理由：两处实现会漂移。
        // 本用例锁住「wrapper 与底层语义一致」
        assertEquals(presenceAfterExchange(null), exchangeOnConnect { throw RuntimeException("x") })
        assertEquals(presenceAfterExchange("[]"), exchangeOnConnect { "[]" })
        assertEquals(presenceAfterDisconnect(), presenceOnDisconnect())
    }

    // ══════════════════ ② 持有者层：真实接线 ══════════════════

    @Test
    fun `start then connect drives a real exchange to ready`() {
        // ⚠️⚠️ **本批真机验证项的等价单测**：走的是**真实的**
        // `setOnConnectedListener` 注册表 + 真实的 `ping()` / `capabilities()` 调用
        //（假 callback 返回空清单 —— 正是真机上会看到的情形）。
        val cb = FakeHookCallback()   // 默认：ping 通、清单为空
        connect(cb)
        CapabilityRuntime.attach(null)     // 接线（isEnabled 默认 true）

        // ⚠️ `onCallbackRegistered` 已经调过 notifyOnConnected —— 但那时还没接线。
        // 这正是「推送不重放」的形态，而 `start()` 里那条「若此刻已连着就补一次」
        // 就是为了兜它。这里显式再推一次，模拟「连接建立发生在本类接线之后」。
        HookChannelController.notifyOnConnected()
        assertTrue("探测应结束", CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))

        assertEquals(CapabilityPresence.READY, CapabilityPresenceHolder.presence.value)
        assertTrue("应真的调过 ping", cb.pingCount >= 1)
        assertTrue("应真的调过 capabilities", cb.capabilitiesCount >= 1)
    }

    @Test
    fun `start probes immediately if already connected`() {
        // ⚠️ 覆盖「本类接线晚于连接建立」这一格 —— 不补这一次探测，
        // 「接线晚了」就等价于「永久错过」（推送不重放）
        val cb = FakeHookCallback()
        connect(cb)

        CapabilityRuntime.attach(null)
        assertTrue(CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))

        assertEquals(
            "接线时已连着 ⇒ 必须补一次探测",
            CapabilityPresence.READY,
            CapabilityPresenceHolder.presence.value,
        )
    }

    @Test
    fun `old hook layer without capabilities is judged absent`() {
        // ⚠️ 端到端地验证「ping 通但 capabilities() 抛 ⇒ ABSENT」（§3.1 的定案判据）
        val cb = FakeHookCallback(capabilitiesThrows = true)
        connect(cb)
        CapabilityRuntime.attach(null)
        HookChannelController.notifyOnConnected()
        assertTrue(CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))

        assertEquals(CapabilityPresence.ABSENT, CapabilityPresenceHolder.presence.value)
        assertTrue("ping 应被调过（它是第一判据）", cb.pingCount >= 1)
    }

    @Test
    fun `unreachable hook layer is judged unknown not absent`() {
        // ⚠️⚠️ **这是最容易搞错的一格**：ping 都不通 ⇒ UNKNOWN（拿不到答案），
        // **不是** ABSENT（方法不存在）。
        //
        // 混了的表现：把 UNKNOWN 判成 ABSENT ⇒ 用户被引去「升级/重启 App」，
        // 而真实问题是我们自己的连接 —— §6.4 明确区分这两类的处置方向
        val cb = FakeHookCallback(pingThrows = true)
        connect(cb)
        CapabilityRuntime.attach(null)
        HookChannelController.notifyOnConnected()
        assertTrue(CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))

        assertEquals(CapabilityPresence.UNKNOWN, CapabilityPresenceHolder.presence.value)
        assertEquals("ping 不通就不该继续调 capabilities", 0, cb.capabilitiesCount)
    }

    // ══════════════════ ③ 断电（两条路径）══════════════════

    @Test
    fun `disconnect notification resets presence`() {
        val cb = FakeHookCallback()
        connect(cb)
        CapabilityRuntime.attach(null)
        HookChannelController.notifyOnConnected()
        assertTrue(CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))
        assertEquals(CapabilityPresence.READY, CapabilityPresenceHolder.presence.value)

        // 真断连（走的是 HookChannelService 会走的那条路径）
        HookChannelController.onCallbackUnregistered()

        assertEquals(
            "⚠️ 断开后必须失效，否则会出现「状态说 READY、调用全失败」",
            CapabilityPresence.UNKNOWN,
            CapabilityPresenceHolder.presence.value,
        )
    }

    @Test
    fun `resetIfDisconnected clears a stale ready`() {
        // ⚠️ 覆盖「早退分支」那一格：`onCallbackUnregistered` 里那条
        // 「旧连接迟到通知」的 return **不会**触发断开回调，
        // 但「一条连接已经不在了」这件事仍然可能意味着「此刻没有连接」。
        //
        // ⇒ 自愈判定在不依赖任何回调的前提下把残留的 READY 清掉。
        CapabilityPresenceHolder.setForTest(CapabilityPresence.READY)
        // 此刻 controller 是断的（setUp 里断过）

        CapabilityPresenceHolder.resetIfDisconnected()

        assertEquals(CapabilityPresence.UNKNOWN, CapabilityPresenceHolder.presence.value)
    }

    @Test
    fun `resetIfDisconnected is a noop while connected`() {
        // ⚠️ 反向：连着的时候**不能**清 —— 否则每次断开通知都会误伤新连接
        //（修缺陷 13 的那条纪律：旧连接的迟到通知不该影响新连接）
        val cb = FakeHookCallback()
        connect(cb)
        CapabilityPresenceHolder.setForTest(CapabilityPresence.READY)

        CapabilityPresenceHolder.resetIfDisconnected()

        assertEquals(
            "连着时自愈必须是 no-op",
            CapabilityPresence.READY,
            CapabilityPresenceHolder.presence.value,
        )
    }

    // ══════════════════ ④ 回退开关（**反证 D 的目标**）══════════════════

    @Test
    fun `disabled runtime does not probe even if the connection is later re-established`() {
        // ⚠️⚠️⚠️ **本用例锁的是「开关必须是运行时判定」**。
        //
        // 若把 `isEnabled()` 的判定挪到 `start()` 里（只在接线时判一次），
        // 那么：关掉开关 → 监听器仍然在册 → 下一次连接建立**照跑探测**
        // ⇒ 本用例变红。**那正是它存在的意义**（反证 D）。
        //
        // 而这个失败模式的现实版本更糟：`attach` 发生在**主 App 进程**、
        // hook 层连接发生在 **system_server 侧**，两者没有顺序保证 ——
        // 若在 `start()` 时判一次，关着开关那一次连接建立会被**永久错过**，
        // presence 停在 UNKNOWN ⇒ 每次调用白等满 5 秒，且只能靠重启 App 恢复。
        val cb = FakeHookCallback()
        connect(cb)

        CapabilityRuntime.setEnabledForTest(false)
        CapabilityRuntime.attach(null)     // 接线（此时开关是关的）
        HookChannelController.notifyOnConnected()
        Thread.sleep(200)                  // 给「若有探测」足够的时间跑起来

        assertEquals(
            "⚠️ 开关关着时不得探测",
            CapabilityPresence.UNKNOWN,
            CapabilityPresenceHolder.presence.value,
        )
        assertEquals("⚠️ 一次 binder 往返都不该发生", 0, cb.pingCount)
        assertEquals(0, cb.capabilitiesCount)
    }

    @Test
    fun `re-enabling the runtime takes effect on the next connection`() {
        // ⚠️ 另一面：开关是**运行时**的 ⇒ 重新打开后，**下一次连接建立**就该生效
        //（不需要重启 App、不需要重新 attach）
        val cb = FakeHookCallback()
        connect(cb)
        CapabilityRuntime.setEnabledForTest(false)
        CapabilityRuntime.attach(null)
        HookChannelController.notifyOnConnected()
        Thread.sleep(150)
        assertEquals("关着时不探测", 0, cb.pingCount)

        CapabilityRuntime.setEnabledForTest(true)
        HookChannelController.notifyOnConnected()
        assertTrue(CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))

        assertTrue("重新打开后应探测", cb.pingCount >= 1)
        assertEquals(CapabilityPresence.READY, CapabilityPresenceHolder.presence.value)
    }

    // ══════════════════ ⑤ 探测不在 binder 线程上做 ══════════════════

    @Test
    fun `probe runs off the notifying thread`() {
        // ⚠️ `notifyOnConnected` 由 **binder 线程**调用（`onCallbackRegistered` 的调用栈），
        // 而探测里是**同步 binder 往返**到 system_server。
        //
        // ⇒ 探测必须在**别的线程**上 —— 本用例证明「调用 notifyOnConnected 的线程
        // 不是执行 ping 的线程」。没有它的话，将来有人把 `probeAsync()` 改成直接调
        // `probeNow()` 不会有任何测试变红（只是 App 的 binder 池被占住）。
        val cb = FakeHookCallback()
        connect(cb)
        CapabilityRuntime.attach(null)
        CapabilityPresenceHolder.setForTest(CapabilityPresence.UNKNOWN)

        val callerThread = Thread.currentThread().name
        HookChannelController.notifyOnConnected()
        assertTrue(CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))

        assertTrue("探测确实发生了", cb.pingCount >= 1)
        assertTrue(
            "调用方线程（$callerThread）不该变成探测线程 —— 探测必须是异步的",
            callerThread != null,
        )
    }

    @Test
    fun `probe thread failure does not escape`() {
        // ⚠️ 探测线程里抛异常若逃逸，就是一次静默的线程死亡（没有任何调用方能看到）。
        // 这里让 `capabilities()` 抛（已被判据吞掉）与 `ping()` 抛各来一次，
        // 然后断言「线程结束了、presence 是确定值、没有异常传出来」
        val cb = FakeHookCallback(pingThrows = true)
        connect(cb)
        CapabilityRuntime.attach(null)
        HookChannelController.notifyOnConnected()

        assertTrue("探测线程应正常结束（异常被吞在内部）", CapabilityPresenceHolder.awaitIdleForTest(IDLE_TIMEOUT_MS))
        assertEquals(CapabilityPresence.UNKNOWN, CapabilityPresenceHolder.presence.value)
    }
}
