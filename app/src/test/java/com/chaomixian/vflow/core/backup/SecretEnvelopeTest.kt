// 文件: test/java/com/chaomixian/vflow/core/backup/SecretEnvelopeTest.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.security.AeadFailure
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [SecretEnvelope] 的纯 JVM 单测。
 *
 * 重点在**形状判据**：`isWrapped` 一旦放宽成「任意对象」，
 * 用户的字典类型参数（`{"type":"vflow.type.dictionary","value":{…}}`）
 * 会被误当密文去解，报出假的「损坏」。
 */
class SecretEnvelopeTest {

    private val ctx = SecretContext("pass".toCharArray(), ByteArray(16) { it.toByte() }, 1000)

    @Test
    fun `wrap and unwrap round trip`() {
        val wrapped = ctx.seal("sk-super-secret")
        assertEquals("sk-super-secret", ctx.openSlot(wrapped))
    }

    @Test
    fun `wrapped node carries the marker and a base64 data string`() {
        val wrapped = ctx.seal("v")
        assertEquals(BackupCrypto.MARKER_VALUE, wrapped.get(SecretEnvelope.MARKER_KEY).asString)
        assertTrue(wrapped.get("data").isJsonPrimitive)
        assertTrue(wrapped.get("data").asJsonPrimitive.isString)
        // 形状必须恰好两个键 —— 多出来的键会让人以为还有别的语义
        assertEquals(2, wrapped.entrySet().size)
    }

    @Test
    fun `the plaintext never appears verbatim in the wrapped node`() {
        val secret = "sk-live-abcdefghijklmnop"
        val wrapped = ctx.seal(secret)
        assertFalse(
            "密文节点的 JSON 里不得出现明文 —— 否则等于没加密",
            wrapped.toString().contains(secret),
        )
    }

    @Test
    fun `isWrapped accepts only the exact shape`() {
        assertTrue(SecretEnvelope.isWrapped(ctx.seal("x")))

        // 缺 data
        assertFalse(SecretEnvelope.isWrapped(JsonObject().apply {
            addProperty(BackupCrypto.MARKER_KEY, BackupCrypto.MARKER_VALUE)
        }))
        // 标记值不对
        assertFalse(SecretEnvelope.isWrapped(JsonObject().apply {
            addProperty(BackupCrypto.MARKER_KEY, "aes-gcm-v99")
            addProperty("data", "AAAA")
        }))
        // data 不是字符串
        assertFalse(SecretEnvelope.isWrapped(JsonObject().apply {
            addProperty(BackupCrypto.MARKER_KEY, BackupCrypto.MARKER_VALUE)
            add("data", JsonObject())
        }))
        // 非对象
        assertFalse(SecretEnvelope.isWrapped(JsonPrimitive("x")))
        assertFalse(SecretEnvelope.isWrapped(JsonNull.INSTANCE))
        assertFalse(SecretEnvelope.isWrapped(null))
    }

    @Test
    fun `a dictionary typed parameter is not mistaken for an envelope`() {
        // ⚠️⚠️ 这是本文件最重要的一条。`VObjectGsonAdapter` 会把字典类型参数
        //     写成下面的形状；若 isWrapped 放宽成「任意对象」，
        //     它会被当密文去解 ⇒ 假的「文件已损坏」。
        val dictionaryParam = JsonObject().apply {
            addProperty("type", "vflow.type.dictionary")
            add("value", JsonObject().apply { addProperty("k", "v") })
        }
        assertFalse(
            "字典类型参数不是密文信封 —— 误判会让完好的备份被判成损坏",
            SecretEnvelope.isWrapped(dictionaryParam),
        )
    }

    @Test
    fun `plain values are untouched by isWrapped`() {
        for (plain in listOf(
            JsonPrimitive("api_key_value"),
            JsonPrimitive(42),
            JsonPrimitive(true),
            JsonNull.INSTANCE,
        )) {
            assertFalse("普通值 $plain 不该被判成信封", SecretEnvelope.isWrapped(plain))
        }
    }

    @Test
    fun `multi byte and emoji payloads survive round trip`() {
        for (text in listOf("中文", "🎉😀", "𝄞", "ß€", "a🎉b中c")) {
            assertEquals(text, ctx.openSlot(ctx.seal(text)))
        }
    }

    @Test
    fun `empty string survives round trip`() {
        assertEquals("", ctx.openSlot(ctx.seal("")))
    }

    @Test
    fun `unwrapData returns null for malformed nodes without throwing`() {
        assertNull(SecretEnvelope.unwrapData(null))
        assertNull(SecretEnvelope.unwrapData(JsonPrimitive("x")))
        assertNull(SecretEnvelope.unwrapData(JsonObject()))
        assertNull(SecretEnvelope.unwrapData(JsonObject().apply {
            addProperty(BackupCrypto.MARKER_KEY, BackupCrypto.MARKER_VALUE)
        }))
    }

    @Test
    fun `unwrap throws AeadFailure on a tampered ciphertext`() {
        val wrapped = ctx.seal("secret")
        val data = BackupCrypto.base64OrNull(wrapped.get("data").asString)!!
        data[data.size - 1] = (data[data.size - 1].toInt() xor 0x01).toByte()
        val tampered = JsonObject().apply {
            addProperty(BackupCrypto.MARKER_KEY, BackupCrypto.MARKER_VALUE)
            addProperty("data", BackupCrypto.base64(data))
        }

        try {
            ctx.openSlot(tampered)
            org.junit.Assert.fail("篡改密文必须抛 AeadFailure")
        } catch (expected: AeadFailure) {
            // 期望路径
        }
    }

    @Test
    fun `unwrap throws AeadFailure on a malformed node rather than returning garbage`() {
        try {
            ctx.openSlot(JsonPrimitive("not an envelope"))
            org.junit.Assert.fail("形状不对必须抛，而不是返回原文")
        } catch (expected: AeadFailure) {
            // 期望路径
        }
    }

    @Test
    fun `plain helper returns the value unchanged`() {
        val node = SecretEnvelope.plain("api_key")
        assertNotNull(node)
        assertFalse(SecretEnvelope.isWrapped(node))
        assertEquals("api_key", node.asString)
    }
}
