package com.chaomixian.vflow.core.security

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.security.GeneralSecurityException

/**
 * [KeystoreCryptoBox] 的纯 JVM 测试。
 *
 * ⚠️ **本测试不能验证 AndroidKeyStore 本身**（`KeystoreGcmEngine` 调 android.jar 桩方法会抛
 * `Method not mocked`）—— 它验证的是 base64 编排、空串语义、以及**异常传播的形状**。
 * Keystore 真机行为见方案 §6「真机验证」（本阶段**未执行**，状态=未验证）。
 */
class KeystoreCryptoBoxTest {

    /**
     * 假的 AES-GCM 引擎：可逆即可，不需要真加密。
     *
     * ⚠️ 形状必须与真实现**对齐**：`iv(12B) || 数据 || tag(16B)`。
     * 早期版本漏了 tag，导致「空明文」编出的 blob 恰好 12 字节、被长度守卫误判为非法 ——
     * 那是假引擎的缺陷，会伪装成生产代码的 bug。
     */
    private class FakeAesGcmEngine(
        var failOnEncrypt: Boolean = false,
        var failOnDecrypt: Boolean = false,
    ) : AliasGcmEngine {
        companion object {
            private const val FAKE_IV_BYTES = 12
            private const val FAKE_TAG_BYTES = 16
        }

        override fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
            if (failOnEncrypt) throw CryptoKeyUnavailableException("fake encrypt failure")
            val iv = ByteArray(FAKE_IV_BYTES) { 0x5A }
            val tag = ByteArray(FAKE_TAG_BYTES) { 0x1C }
            return iv + plaintext.map { (it.toInt() xor 0x2A).toByte() }.toByteArray() + tag
        }

        override fun decrypt(alias: String, blob: ByteArray): ByteArray {
            if (failOnDecrypt) throw CryptoKeyUnavailableException("fake decrypt failure")
            // ⚠️ 用 `<` 而不是 `<=`：空明文的合法 blob 恰好是 IV+TAG = 28 字节，
            // 用 `<=` 会把它误判为「太短」，让「空密码仍产出合法密文」这条语义测不出来。
            if (blob.size < FAKE_IV_BYTES + FAKE_TAG_BYTES) {
                throw CryptoKeyUnavailableException("fake blob too short")
            }
            val end = blob.size - FAKE_TAG_BYTES
            return blob.copyOfRange(FAKE_IV_BYTES, end)
                .map { (it.toInt() xor 0x2A).toByte() }
                .toByteArray()
        }
    }

    private val fake = FakeAesGcmEngine()

    private fun installFake() {
        KeystoreCryptoBox.engine = fake
    }

    @After
    fun tearDown() {
        // ⚠️ 必须复原，否则污染后续用例（object 是全局单例）。
        KeystoreCryptoBox.resetEngineForTest()
    }

    @Test
    fun encryptThenDecrypt_roundTrips() {
        installFake()
        val plaintext = "密码 with 中文 and emoji 🔐🎉 and ascii"

        val encrypted = KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, plaintext)
        val decrypted = KeystoreCryptoBox.decryptFromBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, encrypted)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun decrypt_emptyStringReturnsEmpty() {
        installFake()
        // ⚠️ 空串**不走引擎**（存量数据兜底）—— 故即便引擎配置成必失败，也应返回空串。
        val failing = FakeAesGcmEngine(failOnDecrypt = true)
        KeystoreCryptoBox.engine = failing

        assertEquals("", KeystoreCryptoBox.decryptFromBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, ""))
    }

    @Test
    fun encrypt_emptyPasswordStillProducesCiphertext() {
        installFake()
        val encrypted = KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, "")

        // 空密码不特判：仍产出一段非空密文（即伪 IV + 空数据）。
        assertNotEquals("", encrypted)
        assertTrue("空密码的密文应可解回空串",
            KeystoreCryptoBox.decryptFromBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, encrypted).isEmpty())
    }

    @Test
    fun base64OutputHasNoNewlines() {
        installFake()
        // ⚠️ 输入必须 > 76 字节才会让 Base64.DEFAULT 插换行 —— 这是本用例能抓到 bug 的前提。
        val longPassword = "A".repeat(200)

        val encrypted = KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, longPassword)

        assertTrue("密文长度应 > 76（否则本用例抓不到换行 bug），实际 ${encrypted.length}", encrypted.length > 76)
        assertTrue("basic 编码不应插入换行，实际：$encrypted", !encrypted.contains("\n") && !encrypted.contains("\r"))
        assertTrue("basic 编码不应插入回车/空白", encrypted.none { it.isWhitespace() })
    }

    @Test
    fun engineThrowingKeyUnavailable_propagatesAsKeyUnavailable() {
        KeystoreCryptoBox.engine = FakeAesGcmEngine(failOnDecrypt = true)
        val encrypted = KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, "x")

        try {
            KeystoreCryptoBox.decryptFromBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, encrypted)
            fail("应当抛出 CryptoKeyUnavailableException")
        } catch (e: CryptoKeyUnavailableException) {
            // ✅ 正是期望的类型 —— 不能被包成 GeneralSecurityException 或其它类型，
            // 否则上层的「请重新输入密码」提示永远出不来。
        }
    }

    @Test
    fun engineThrowingKeyUnavailable_onEncrypt_propagatesAsWell() {
        KeystoreCryptoBox.engine = FakeAesGcmEngine(failOnEncrypt = true)

        try {
            KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, "x")
            fail("应当抛出 CryptoKeyUnavailableException")
        } catch (e: CryptoKeyUnavailableException) {
            // ✅ 预期
        }
    }

    @Test
    fun decrypt_invalidBase64_throwsKeyUnavailable() {
        installFake()
        // `!` 不是 base64 字符，且 NO_WRAP 下不允许 —— 应被转成可诊断的 CryptoKeyUnavailable。
        try {
            KeystoreCryptoBox.decryptFromBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, "!!!not base64!!!")
            fail("应当抛出 CryptoKeyUnavailableException")
        } catch (e: CryptoKeyUnavailableException) {
            // ✅ 预期：把「prefs 被改坏」变成可诊断的密钥问题
        }
    }

    @Test
    fun resetEngineForTest_restoresKeystoreEngine() {
        installFake()
        assertTrue(KeystoreCryptoBox.engine is FakeAesGcmEngine)

        KeystoreCryptoBox.resetEngineForTest()

        // 默认引擎是 AndroidKeyStore 实现（单例 object）。
        assertTrue(
            "resetEngineForTest 应复原为 KeystoreGcmEngine，实际 ${KeystoreCryptoBox.engine.javaClass.name}",
            KeystoreCryptoBox.engine === KeystoreGcmEngine
        )
    }

    @Test
    fun aliasIsStable_webdavPasswordAlias() {
        // ⚠️ 别名是存量数据的兼容锚点 —— 改了等于所有已存密码解不开。
        // 这条断言的作用是让「无意重命名」变红。
        assertEquals("vflow_webdav_password_v1", KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS)
    }

    @Test
    fun generalSecurityException_isNotConvertedToKeyUnavailable() {
        // 引擎抛底层 GeneralSecurityException 时，KeystoreCryptoBox **不应**把它包装/吞掉 ——
        // 那类异常是真·实现缺陷，不该伪装成「重输密码」。
        KeystoreCryptoBox.engine = object : AliasGcmEngine {
            override fun encrypt(alias: String, plaintext: ByteArray): ByteArray =
                throw GeneralSecurityException("boom")

            override fun decrypt(alias: String, blob: ByteArray): ByteArray =
                throw GeneralSecurityException("boom")
        }

        try {
            KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, "x")
            fail("应当抛出 GeneralSecurityException")
        } catch (e: GeneralSecurityException) {
            // ✅ 原样上抛，未被转成 CryptoKeyUnavailableException
        } catch (e: CryptoKeyUnavailableException) {
            fail("GeneralSecurityException 不应被转成 CryptoKeyUnavailableException")
        }
    }

    @Test
    fun base64Encoding_isStandardBasic() {
        installFake()
        val plaintext = "B".repeat(100)
        val encrypted = KeystoreCryptoBox.encryptToBase64(KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS, plaintext)

        // 标准 basic 编码：解出的字节应能再被编回原串。
        val decoded = java.util.Base64.getDecoder().decode(encrypted)
        assertEquals(encrypted, java.util.Base64.getEncoder().encodeToString(decoded))
    }
}
