package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [HookCapabilityRegistry] 的单测。
 *
 * ⚠️ 本对象是**进程级单例**，用例之间会串状态 ⇒ 每个用例前后都 `resetForTest()`。
 */
class HookCapabilityRegistryTest {

    /** 最小假 handler（本测试只关心「按名查得到 / 查不到」）。 */
    private class FakeHandler(override val name: String) : CapabilityHandler {
        override fun handle(request: CapabilityRequest): CapabilityOutcome =
            CapabilityOutcome.Items(emptyList())
    }

    @Before
    fun setUp() {
        HookCapabilityRegistry.resetForTest()
    }

    @After
    fun tearDown() {
        HookCapabilityRegistry.resetForTest()
    }

    @Test
    fun `register then find returns the same handler`() {
        val h = FakeHandler("alpha")
        HookCapabilityRegistry.register(h)
        assertSame(h, HookCapabilityRegistry.find("alpha"))
    }

    @Test
    fun `find returns null for unknown name`() {
        // ⚠️ 返回 null 而不是抛 / 回默认 handler —— 由调用方**显式**报
        // `capability_absent`（§4.3）。静默回落会让「hook 层太旧」看不出来。
        assertNull(HookCapabilityRegistry.find("nope"))
    }

    @Test
    fun `register is idempotent by name and replaces`() {
        // ⚠️ 按 name 去重（与 App 侧 CapabilityRegistry 同措辞）：
        // 无脑 append 会让「查表返回哪个」变成注册顺序的隐式结果 ——
        // 本仓库已因隐式覆盖踩过三次坑（三处单槽位）。
        val first = FakeHandler("dup")
        val second = FakeHandler("dup")
        HookCapabilityRegistry.register(first)
        HookCapabilityRegistry.register(second)

        assertSame(second, HookCapabilityRegistry.find("dup"))
        assertEquals(1, HookCapabilityRegistry.names().size)
    }

    @Test
    fun `register rejects blank name`() {
        // 一个「名字为空的能力」永远查不到，却会污染 capabilities() 清单
        HookCapabilityRegistry.register(FakeHandler(""))
        HookCapabilityRegistry.register(FakeHandler("   "))

        assertTrue(HookCapabilityRegistry.names().isEmpty())
        assertFalse(HookCapabilityRegistry.contains(""))
    }

    @Test
    fun `names returns every registered name`() {
        HookCapabilityRegistry.register(FakeHandler("a"))
        HookCapabilityRegistry.register(FakeHandler("b"))

        assertEquals(setOf("a", "b"), HookCapabilityRegistry.names())
        assertTrue(HookCapabilityRegistry.contains("a"))
        assertTrue(HookCapabilityRegistry.contains("b"))
    }

    @Test
    fun `all is sorted by name so output is stable`() {
        // ⚠️ 排序是为了让 capabilities() 的输出**稳定** ——
        // 顺序本身无语义，但不确定性会掩盖真实的差异（测试断言与日志比对都会变脆）
        HookCapabilityRegistry.register(FakeHandler("zulu"))
        HookCapabilityRegistry.register(FakeHandler("alpha"))
        HookCapabilityRegistry.register(FakeHandler("mike"))

        assertEquals(
            listOf("alpha", "mike", "zulu"),
            HookCapabilityRegistry.all().map { it.name },
        )
    }

    @Test
    fun `resetForTest clears everything including the default registrations`() {
        // ⚠️ 清空**故意不重跑 `init` 的默认注册** —— 让「测试是不是依赖了
        // 生产注册表」这件事显式可见（依赖了就会红）。
        HookCapabilityRegistry.resetForTest()
        assertTrue(HookCapabilityRegistry.names().isEmpty())
        assertNull(HookCapabilityRegistry.find("diagnostic"))
    }

    @Test
    fun `the production init block registers the diagnostic handler`() {
        // ⚠️⚠️ 为什么用**源码扫描**而不是断言注册表内容：
        // 本对象是进程级单例，而本类的 `@Before` 会 `resetForTest()` —— 清空后
        // `init` 块**不会重跑**。所以「默认注册了没有」这件事在运行期**测不出来**
        // （跑得到的状态已经被别的用例改过了，用例顺序一变结论就变）。
        //
        // 而它恰恰是「写了调用点、但没有调用点」的高危处 —— 本仓库
        // （`CoreDexFingerprint` 教训）为此专门立过一条纪律：结构性/接线性的要求
        // 只能靠源码扫描钉住。
        val file = java.io.File(
            "src/main/java/com/chaomixian/vflow/xposed/capabilities/HookCapabilityRegistry.kt",
        )
        assertTrue("HookCapabilityRegistry.kt 应当存在", file.isFile)
        val text = file.readText()

        assertTrue(
            "❌ `init` 块里必须注册诊断能力（否则 hook 侧一个 capability 都没有，\n" +
                "`capabilities()` 会回空清单、所有调用都回 capability_absent）。",
            text.contains("register(DiagnosticCapabilityHandler())"),
        )
    }
}
