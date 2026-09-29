package com.chaomixian.vflow.xposed.capability

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * App 侧 capability 注册表与 `CapabilityPresence` 的测试。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.2 / §6.3。
 */
class CapabilityRegistryTest {

    @Before
    fun setUp() {
        CapabilityRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        CapabilityRegistry.resetForTest()
    }

    private fun cap(
        name: String,
        fallback: (suspend (Map<String, Any?>) -> Map<String, Any?>)? = null,
    ) = Capability(name = name, risk = CapabilityRisk.READ_ONLY, fallback = fallback)

    @Test
    fun `register then find`() {
        CapabilityRegistry.register(cap("a"))
        assertEquals("a", CapabilityRegistry.find("a")?.name)
        assertNull(CapabilityRegistry.find("nope"))
    }

    @Test
    fun `register is idempotent per name`() {
        // ⚠️ 同名重复注册**替换**而不是追加 ——
        // 列式追加会让「查表返回哪个」变成注册顺序的隐式结果，
        // 本仓库已因这类隐式覆盖踩过三次坑（三处单槽位）
        CapabilityRegistry.register(cap("a").copy(risk = CapabilityRisk.READ_ONLY))
        CapabilityRegistry.register(cap("a").copy(risk = CapabilityRisk.HIGH))

        assertEquals(1, CapabilityRegistry.all().size)
        assertEquals(CapabilityRisk.HIGH, CapabilityRegistry.find("a")?.risk)
    }

    @Test
    fun `names and contains reflect the registry`() {
        CapabilityRegistry.register(cap("a"))
        CapabilityRegistry.register(cap("b"))

        assertEquals(setOf("a", "b"), CapabilityRegistry.names())
        assertTrue(CapabilityRegistry.contains("a"))
        assertFalse(CapabilityRegistry.contains("c"))
    }

    @Test
    fun `all is sorted by name so listing is deterministic`() {
        CapabilityRegistry.register(cap("zebra"))
        CapabilityRegistry.register(cap("alpha"))
        CapabilityRegistry.register(cap("mid"))

        assertEquals(listOf("alpha", "mid", "zebra"), CapabilityRegistry.all().map { it.name })
    }

    @Test
    fun `names is what capabilities reports`() {
        // ⚠️ §3.1：`capabilities()` 该上报的就是「App 侧注册了哪些」。
        // 但注意——**hook 层上报的是 hook 层自己的注册表**，
        // App 侧这个 registry 是权威的**声明**（风险分级/降级所在）。
        CapabilityRegistry.register(cap("query_shortcut_intents"))
        assertEquals(setOf("query_shortcut_intents"), CapabilityRegistry.names())
    }

    @Test
    fun `blank capability name is rejected at construction`() {
        // 空名的 capability 无法被调用（没有可匹配的字符串）
        val threw = try {
            Capability(name = "", risk = CapabilityRisk.READ_ONLY)
            false
        } catch (_: IllegalArgumentException) {
            true
        }
        assertTrue("空名应在构造期就被拒绝", threw)
    }

    @Test
    fun `null fallback means exclusive capability with no degradation`() {
        // ⚠️ §6.2：`fallback == null` ⇒ 独占型 ⇒ 不可用时**无从降级**，
        // 形态是「明确告知 + 引导」而不是空列表或错误弹窗
        val exclusive = cap("exclusive", fallback = null)
        assertNull(exclusive.fallback)

        val replaceable = cap("replaceable", fallback = { it })
        assertNotNull(replaceable.fallback)
    }

    @Test
    fun `fallback receives params`() {
        // ⚠️ §6.2 的 S7 修正：fallback **必须接收 params**。
        // 初版写成无参 `() -> Any`，那样每个降级实现只能自己去读外部状态，
        // 重演「各消费者只算自己那份」（§7.4 反模式 2）。
        // 契约是「同样的入参、同形状的结果、更差的实现」。
        val seen = mutableMapOf<String, Any?>()
        val c = cap("c") { params ->
            seen.putAll(params)
            mapOf("ok" to true)
        }
        assertNotNull(c.fallback)
        // 这里只验签名形状（lambda 接收一个 Map）；真正调用需要协程，留给任务 2/4
        assertEquals(0, seen.size)
    }

    @Test
    fun `defaults for budget timeout and idempotency`() {
        val c = cap("c")
        assertNull("null ⇒ 用 ResultBudget 的默认上限", c.maxResultBytes)
        assertNull("null ⇒ 用 5000ms 默认超时", c.timeoutMs)
        assertTrue("首版均为只读 ⇒ 默认幂等（§10-#15）", c.idempotent)
    }

    @Test
    fun `capability names constant is the agreed literal`() {
        // ⚠️ 跨进程协议的一部分，一经发布不要改
        assertEquals("query_shortcut_intents", CapabilityNames.QUERY_SHORTCUT_INTENTS)
    }

    // ── CapabilityPresence（§6.3）──────────────────────────────

    @Test
    fun `presence after a successful exchange is READY`() {
        assertEquals(CapabilityPresence.READY, presenceAfterExchange("""{"capabilities":[]}"""))
        assertEquals(CapabilityPresence.READY, presenceAfterExchange(""))
    }

    @Test
    fun `presence after a failed exchange is ABSENT`() {
        // ⚠️⚠️ `null` 表示「拿不到」—— 旧 hook 层根本没有这个方法
        // ⇒ 判 ABSENT（hook 层太旧）。
        // 调用点必须把「binder 调用抛异常」也转成 null（AIDL 上没有方法时是抛，
        // 不是返回空串 —— 这是最容易搞错的一处）
        assertEquals(CapabilityPresence.ABSENT, presenceAfterExchange(null))
    }

    @Test
    fun `empty manifest still counts as READY`() {
        // ⚠️ 与上一条配对：**空清单 ≠ 方法不存在**。
        // 混同会让用户被引去「升级 App」，而 App 其实是最新的。
        assertEquals(
            CapabilityPresence.READY,
            presenceAfterExchange(CapabilityManifestMini.EMPTY),
        )
    }

    @Test
    fun `presence resets to UNKNOWN on disconnect`() {
        // ⚠️ 不保留旧值：它回答的是「**此刻**连着的那一端有没有这个能力」。
        // 保留旧值会让「重连到一个更旧的 hook 层」时仍显示 READY ——
        // 而 §6.3 明说「状态是采样的」，采样的东西过期后必须显式失效
        assertEquals(CapabilityPresence.UNKNOWN, presenceAfterDisconnect())
    }

    @Test
    fun `presence has exactly three values and no DEGRADED`() {
        // ⚠️ 初版的 DEGRADED 已删除（§6.3）：它被描述为「引用捕获型专用」，
        // 但 ① 引用捕获型现在一个都没有；② 「引用有没有抓到」不是**连接级**属性
        //（它随对象生死变化、秒级抖动），放在「连接期交换一次」的缓存里根本不准。
        assertEquals(3, CapabilityPresence.entries.size)
        assertEquals(
            listOf(
                CapabilityPresence.UNKNOWN,
                CapabilityPresence.ABSENT,
                CapabilityPresence.READY,
            ),
            CapabilityPresence.entries.toList(),
        )
    }

    private object CapabilityManifestMini {
        /** 与 `CapabilityManifest.encode(emptyList())` 等价的最小空清单。 */
        const val EMPTY = """{"protocol_version":1,"capabilities":[]}"""
    }
}
