package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.CapabilityManifest
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `capabilities()` 清单编解码测试。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.1 / §6.3。
 *
 * 重点锁**「空清单」与「方法不存在」不能混同**这一条 ——
 * 混同会让用户被引去「升级 App」，而 App 其实是最新的。
 */
class CapabilityManifestTest {

    @Test
    fun `manifest round-trips`() {
        val json = CapabilityManifest.encode(listOf("query_shortcut_intents", "execute_script"))
        val decoded = CapabilityManifest.decode(json)
        assertEquals(setOf("query_shortcut_intents", "execute_script"), decoded)
    }

    @Test
    fun `empty manifest decodes to empty set not null`() {
        // ⚠️⚠️ 本文件最要紧的一条。
        // 「hook 层有 capabilities() 方法，但一个能力都没注册」⇒ 判 READY（方法存在）。
        // 若这里返回 null，调用方会判 ABSENT ⇒ 让用户去「升级 App」，
        // 而 App 其实是最新的 —— 正是 §6.4 要避免的误导方向。
        val json = CapabilityManifest.encode(emptyList())
        val decoded = CapabilityManifest.decode(json)
        assertNotNull("空清单必须是合法的（空集合），不是 null", decoded)
        assertTrue(decoded!!.isEmpty())
    }

    @Test
    fun `bad json returns null`() {
        // 调用方在 system_server 的 binder 线程上，抛异常 = 整机
        assertNull(CapabilityManifest.decode("nonsense"))
        assertNull(CapabilityManifest.decode(""))
        assertNull(CapabilityManifest.decode("{"))
    }

    @Test
    fun `manifest without capabilities key is rejected`() {
        // ⚠️ 缺 `capabilities` 键 ⇒ 坏输入（而不是「空清单」）——
        // 空清单是「方法存在但没注册」，缺键是「格式不对」，两者处置不同
        assertNull(CapabilityManifest.decode("""{"protocol_version":1}"""))
    }

    @Test
    fun `manifest with null capabilities is rejected`() {
        assertNull(CapabilityManifest.decode("""{"protocol_version":1,"capabilities":null}"""))
    }

    @Test
    fun `manifest with an object instead of array is rejected`() {
        assertNull(CapabilityManifest.decode("""{"protocol_version":1,"capabilities":{}}"""))
    }

    @Test
    fun `blank names are skipped`() {
        val json = """{"protocol_version":1,"capabilities":["a",""," ","b"]}"""
        assertEquals(setOf("a", "b"), CapabilityManifest.decode(json))
    }

    @Test
    fun `encode is stable and sorted`() {
        // ⚠️ 排序让同一份清单的编码结果稳定 —— 便于断言与日志比对。
        // 顺序本身无语义，但不确定性会掩盖真实的差异
        val a = CapabilityManifest.encode(listOf("b", "a", "c"))
        val b = CapabilityManifest.encode(listOf("c", "b", "a"))
        assertEquals(a, b)
        assertTrue(a.indexOf("\"a\"") < a.indexOf("\"b\""))
    }

    @Test
    fun `encode drops blank names`() {
        val json = CapabilityManifest.encode(listOf("a", "", "  "))
        assertEquals(setOf("a"), CapabilityManifest.decode(json))
    }

    @Test
    fun `protocol version defaults to the shared constant`() {
        // §5.5 版本号收敛：与 ③ 的调用信封、事件信封**同一处常量**
        val json = CapabilityManifest.encode(emptyList())
        assertEquals(EventEnvelopeCodec.PROTOCOL_VERSION, CapabilityManifest.protocolVersionOf(json))
    }

    @Test
    fun `missing protocol version reads as minus one not zero`() {
        // ⚠️ 0 是个合法版本号，用 0 会让「缺失」与「版本 0」混同
        assertEquals(-1, CapabilityManifest.protocolVersionOf("""{"capabilities":[]}"""))
        assertEquals(-1, CapabilityManifest.protocolVersionOf("nonsense"))
        assertEquals(-1, CapabilityManifest.protocolVersionOf(""))
    }

    @Test
    fun `key names are stable`() {
        assertEquals("protocol_version", CapabilityManifest.KEY_PROTOCOL)
        assertEquals("capabilities", CapabilityManifest.KEY_CAPABILITIES)
    }
}
