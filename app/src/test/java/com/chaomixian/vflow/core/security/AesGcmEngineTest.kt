// 文件: test/java/com/chaomixian/vflow/core/security/AesGcmEngineTest.kt
package com.chaomixian.vflow.core.security

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * [JvmAesGcmEngine] 的纯 JVM 单测。
 *
 * ⚠️ 本文件**不使用**任何 Android 类或 mock 框架 —— 引擎只用 JDK 的 JCE。
 */
class AesGcmEngineTest {

    private val engine = JvmAesGcmEngine()
    private val key = ByteArray(32) { (it + 1).toByte() }

    @Test
    fun `round trip returns the original plaintext`() {
        val plaintext = "vFlow 备份口令测试/emoji 🎉".toByteArray(Charsets.UTF_8)
        val sealed = engine.seal(key, plaintext)
        assertArrayEquals(plaintext, engine.open(key, sealed))
    }

    @Test
    fun `empty plaintext round trips`() {
        // 空明文是合法输入（例如某个密钥槽存在但值为空串）。
        // ⚠️ 它同时是「密文长度可以为 0」的边界 —— open() 的长度校验
        //    只要求 IV + tag，不能把密文本身算进去。
        val sealed = engine.seal(key, ByteArray(0))
        assertEquals(JvmAesGcmEngine.IV_BYTES + JvmAesGcmEngine.TAG_BYTES, sealed.size)
        assertArrayEquals(ByteArray(0), engine.open(key, sealed))
    }

    @Test
    fun `sealed output is iv then ciphertext then tag`() {
        // 「IV 前置」是本项目对外的字节布局约定（见接口 KDoc）。
        // 验证方式：解两次都能成功、且两次密文前缀（IV）不同 ⇒ 说明前 12 字节确实是 IV。
        val plaintext = "same input".toByteArray(Charsets.UTF_8)
        val a = engine.seal(key, plaintext)
        val b = engine.seal(key, plaintext)

        assertNotEquals(
            "两次 seal 的前 12 字节（IV）必须不同 —— GCM 下 IV 重用会泄漏明文异或值",
            hex(a.copyOfRange(0, JvmAesGcmEngine.IV_BYTES)),
            hex(b.copyOfRange(0, JvmAesGcmEngine.IV_BYTES)),
        )
        // 而密文本体也必须不同（同样的明文 + 不同 IV ⇒ 不同密文）
        assertNotEquals(hex(a), hex(b))
        assertArrayEquals(plaintext, engine.open(key, a))
        assertArrayEquals(plaintext, engine.open(key, b))
    }

    @Test
    fun `newIv always returns 12 distinct random bytes`() {
        val seen = mutableSetOf<String>()
        repeat(64) {
            val iv = JvmAesGcmEngine.newIv()
            assertEquals(JvmAesGcmEngine.IV_BYTES, iv.size)
            assertTrue("IV 出现重复 —— SecureRandom 被换成了固定值？", seen.add(hex(iv)))
        }
    }

    @Test
    fun `a single flipped ciphertext byte is rejected`() {
        val sealed = engine.seal(key, "sensitive".toByteArray(Charsets.UTF_8))
        // 改最后一个字节（落在 tag 里，但同样会先经过 GCM 校验）
        val tampered = sealed.copyOf()
        tampered[tampered.size - 1] = (tampered[tampered.size - 1].toInt() xor 0x01).toByte()

        assertAeadFailure { engine.open(key, tampered) }
    }

    @Test
    fun `a single flipped iv byte is rejected`() {
        // ⚠️ IV 也在 tag 的认证范围内（GCM 把它当作初始计数器块）。
        //    若实现改成「IV 不进认证」，这条会变红 —— 那意味着攻击者可以
        //    篡改 IV 而不被发现，进而破坏 GCM 的重放保护。
        val sealed = engine.seal(key, "sensitive".toByteArray(Charsets.UTF_8))
        val tampered = sealed.copyOf()
        tampered[0] = (tampered[0].toInt() xor 0x01).toByte()

        assertAeadFailure { engine.open(key, tampered) }
    }

    @Test
    fun `a different key cannot open the ciphertext`() {
        val sealed = engine.seal(key, "sensitive".toByteArray(Charsets.UTF_8))
        val otherKey = ByteArray(32) { (it + 99).toByte() }

        assertAeadFailure { engine.open(otherKey, sealed) }
    }

    @Test
    fun `aad mismatch is rejected and aad match is accepted`() {
        val aad = "slot:module_config_prefs.feishu_app_secret".toByteArray(Charsets.UTF_8)
        val sealed = engine.seal(key, "token".toByteArray(Charsets.UTF_8), aad)

        assertArrayEquals("token".toByteArray(Charsets.UTF_8), engine.open(key, sealed, aad))
        assertAeadFailure { engine.open(key, sealed, "slot:other".toByteArray(Charsets.UTF_8)) }
        assertAeadFailure { engine.open(key, sealed, null) }
    }

    @Test
    fun `too-short input raises AeadFailure not an index error`() {
        // ⚠️ 这条锁的是**异常类型**：实现若先 copyOfRange 再判长度，
        //    会抛 ArrayIndexOutOfBoundsException —— 调用方按 AeadFailure 分流时会漏掉，
        //    而漏掉的表现是「损坏的备份把整个导入流程炸掉」。
        for (size in 0 until JvmAesGcmEngine.IV_BYTES + JvmAesGcmEngine.TAG_BYTES) {
            assertAeadFailure("长度 $size 应当被拒") { engine.open(key, ByteArray(size)) }
        }
    }

    @Test
    fun `multi-byte utf8 payload survives round trip`() {
        // 中文 / emoji / 代理对 —— 逐字节往返，不做任何字符串中转。
        val plaintext = "中文🀄️🎉ß€𝄞".toByteArray(Charsets.UTF_8)
        val sealed = engine.seal(key, plaintext)
        assertArrayEquals(plaintext, engine.open(key, sealed))
        assertFalse("密文不该等于明文", sealed.contentEquals(plaintext))
    }

    private fun assertAeadFailure(message: String = "应当抛出 AeadFailure", block: () -> Unit) {
        try {
            block()
            fail(message)
        } catch (e: AeadFailure) {
            // 期望路径
        }
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }
}
