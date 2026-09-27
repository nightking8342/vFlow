package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.HookConditionWire
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 下行过滤条件的编解码测试。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.2 / §3.4.5。
 *
 * ⚠️ 与 logcat 那条链路同一个教训：**编码不对称的表现是「所有触发器静默不触发」**
 * （App 编出 hook 层解不开的格式 ⇒ hook 层得到空条件 ⇒ 什么都不采）。
 * 所以有往返测试。
 */
class HookConditionWireTest {

    @Test
    fun `encode then decode round-trips topics and packages`() {
        val json = HookConditionWire.encode(
            topics = listOf("hook.activity.changed"),
            packages = listOf("com.foo", "bin.mt.plus"),
        )
        val c = HookConditionWire.decode(json)
        assertEquals(setOf("hook.activity.changed"), c.topics)
        assertEquals(setOf("com.foo", "bin.mt.plus"), c.packages)
        assertFalse(c.isEmpty)
    }

    @Test
    fun `empty condition encodes and decodes to empty`() {
        val c = HookConditionWire.decode(HookConditionWire.empty())
        assertTrue(c.topics.isEmpty())
        assertTrue(c.isEmpty)
    }

    @Test
    fun `blank packages means all packages not none`() {
        // ⚠️⚠️ 这是刻意的语义，写反了会静默失效：
        // 空集若解成「一个包都不要」，用户刚配触发器那一瞬间会什么都采不到。
        // 反过来（全部）最多是多采一点、由 App 侧过滤掉 ——
        // **宁可多采，不可漏采**：漏采是静默失效，多采只是浪费
        val c = HookConditionWire.decode(HookConditionWire.encode(listOf("t")))
        assertTrue("packages 为空时必须表示「全部」", c.caresAboutPackage("任何包"))
        assertTrue(c.caresAboutPackage("com.whatever"))
    }

    @Test
    fun `non-empty packages restricts to the listed ones`() {
        val c = HookConditionWire.decode(
            HookConditionWire.encode(listOf("t"), listOf("com.foo"))
        )
        assertTrue(c.caresAboutPackage("com.foo"))
        assertFalse(c.caresAboutPackage("com.bar"))
        // ⚠️ 注意 `com.foobar` 会被**放行** —— 那是刻意的（见 caresAboutPackage 的说明）：
        // hook 侧必须比 App 侧宽松，多放行只是多采，漏放行是静默失效
        assertTrue(c.caresAboutPackage("com.foobar"))
    }

    @Test
    fun `decode tolerates malformed json by falling back to empty`() {
        // ⚠️ 调用方在 hook 层（system_server）。抛异常危险性 = 整机。
        // 而「按空条件处理」的后果是停掉采集 —— 是**安全的那一侧**
        for (bad in listOf("", "   ", "not json", "{", "[]", "null")) {
            val c = HookConditionWire.decode(bad)
            assertTrue("坏输入 `$bad` 应得到空条件", c.isEmpty)
        }
    }

    @Test
    fun `decode ignores blank entries and unknown fields`() {
        // 向前兼容：「只加不改不删」，将来加字段旧解析器不能崩
        val c = HookConditionWire.decode(
            """{"topics":["a","","  ","b"],"packages":[],"future_field":{"x":1}}"""
        )
        assertEquals(setOf("a", "b"), c.topics)
    }

    @Test
    fun `decode handles missing keys`() {
        val c = HookConditionWire.decode("""{"topics":["a"]}""")
        assertEquals(setOf("a"), c.topics)
        assertTrue(c.packages.isEmpty())
    }

    /**
     * ⚠️⚠️ 这一组锁的是**真实缺陷**：下推白名单若比 App 侧更严，
     * 事件会在到达 App 之前就被丢掉 —— 静默漏采。
     */
    @Test
    fun `hook-side filter must be coarser than app-side contains mode`() {
        // App 侧默认是 contains 模式，所以 filter="com.foo" 会匹配
        // "com.foobar"（App 侧会触发）。
        // ⇒ hook 层必须也放行它，否则这个事件永远到不了 App。
        val c = HookConditionWire.decode(HookConditionWire.encode(listOf("t"), listOf("com.foo")))
        assertTrue(
            "hook 侧必须比 App 侧宽松（App 用 contains，hook 就不能用精确）",
            c.caresAboutPackage("com.foobar"),
        )
    }

    @Test
    fun `hook-side filter ignores case like app-side does`() {
        // App 侧 matchOne 用 equals/contains(ignoreCase = true)。
        // hook 侧若区分大小写，用户填 COM.FOO 就会全被挡掉
        val c = HookConditionWire.decode(HookConditionWire.encode(listOf("t"), listOf("COM.FOO")))
        assertTrue("hook 侧必须忽略大小写", c.caresAboutPackage("com.foo"))
        assertTrue("反向也要成立", c.caresAboutPackage("COM.FOO"))
    }

    @Test
    fun `hook-side filter still rejects genuinely unrelated packages`() {
        // ⚠️ 放宽不等于放弃过滤 —— 它仍要砍掉绝大多数无关 App，
        // 否则「下推」就白做了（退化成全量上报）
        val c = HookConditionWire.decode(
            HookConditionWire.encode(listOf("t"), listOf("com.android.settings"))
        )
        assertFalse("无关包必须仍被挡掉", c.caresAboutPackage("bin.mt.plus"))
        assertFalse(c.caresAboutPackage("com.miui.home"))
    }

    @Test
    fun `topics order does not matter for equality of sets`() {
        // 全量替换语义下，「同样的集合、不同顺序」必须等价 ——
        // 否则每次下发都会被判为「变了」，触发无意义的重启/重挂
        val a = HookConditionWire.decode(HookConditionWire.encode(listOf("x", "y")))
        val b = HookConditionWire.decode(HookConditionWire.encode(listOf("y", "x")))
        assertEquals(a.topics, b.topics)
    }
}
