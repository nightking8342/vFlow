package com.chaomixian.vflow.core.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.UnrecoverableKeyException
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * AndroidKeyStore (AES-256-GCM) 实现。**只能在真机 / 模拟器上跑**（纯 JVM 单测不可用）。
 *
 * 密钥参数（全部是硬要求，改动前先读注释）：
 * - `KeyProperties.PURPOSE_ENCRYPT or PURPOSE_DECRYPT`
 * - `setBlockModes(KeyProperties.BLOCK_MODE_GCM)`
 * - `setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)`
 * - `setKeySize(256)`
 * - ⚠️⚠️ **绝不调** `setUserAuthenticationRequired(true)` —— 那会让后台工作流（无人解锁）
 *   解不开密码，功能静默失效。本引擎的**全部消费者都是后台场景**。
 * - ⚠️ **不用** `setRandomizedEncryptionRequired` 关闭随机 IV（默认就是 true，IV 每轮随机）——
 *   GCM 复用 IV 是灾难性的，保持默认。
 *
 * 存储格式：`cipher.iv(12B) || ciphertext || tag(16B)` —— IV 前置，与 [KeystoreCryptoBox] 约定一致。
 */
object KeystoreGcmEngine : AliasGcmEngine {
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val TRANSFORMATION = "AES/GCM/NoPadding"
    private const val GCM_TAG_BITS = 128

    /** IV 长度：GCM 推荐的 96 bit。前置到密文前，解密时按此长度切分。 */
    private const val GCM_IV_BYTES = 12

    override fun encrypt(alias: String, plaintext: ByteArray): ByteArray {
        val key = getOrCreateKey(alias)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        // ⚠️ IV 必须取 `cipher.iv`（由 Keystore 随机生成），不能自己造一个 ——
        // 手动 setIV 在随机 IV 默认开启时会被拒绝（InvalidAlgorithmParameterException）。
        val iv = cipher.iv
        val ciphertext = cipher.doFinal(plaintext)
        return iv + ciphertext
    }

    override fun decrypt(alias: String, blob: ByteArray): ByteArray {
        // ⚠️ 先做长度校验：blob 短于 IV 长度时 `copyOfRange` 会越界崩溃，
        // 而那段 blob 只可能来自被改坏的 prefs —— 应当是可诊断的「密钥不可用」，不是崩溃。
        if (blob.size <= GCM_IV_BYTES) {
            throw CryptoKeyUnavailableException("密文格式不合法：长度 ${blob.size} 不足 IV($GCM_IV_BYTES) + 数据")
        }

        val key = getOrCreateKey(alias)
        val iv = blob.copyOfRange(0, GCM_IV_BYTES)
        val ciphertext = blob.copyOfRange(GCM_IV_BYTES, blob.size)

        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext)
        } catch (e: KeyPermanentlyInvalidatedException) {
            // ⚠️⚠️ 下面两个 catch 必须排在「其余 GeneralSecurityException」之前 ——
            // Java/Kotlin 的 catch 顺序即优先级，两者都是 GeneralSecurityException 的子类，
            // 排在后面会被通用分支吞掉，上层的「重输密码」提示永远出不来（R2）。
            //
            // ⚠️⚠️ **本分支在本项目里实际到不了**（保留它只为把「语义上确实失效」与
            // 「密文对不上」分开，别让读者以为它等价于 AEADBadTagException）：
            // 官方对该异常的界定是「**只**发生在被授权为『需用户认证』的密钥上」
            // （`KeyPermanentlyInvalidatedException` 类文档逐字：*This only occurs for keys
            // which are authorized to be used only if the user has been authenticated*）——
            // 而本引擎**刻意没有** `setUserAuthenticationRequired(true)`（见 `getOrCreateKey`，
            // 加它会因后台场景无人解锁而静默失效），也**不是** `setUnlockedDeviceRequired`。
            // ⇒ 改了锁屏密码 / 设了生物识别 / 清了凭据，**都不会**让本密钥失效；
            // 本 catch 只是为了以后有人给别名加上认证要求时不会静默走错分支。
            throw CryptoKeyUnavailableException("密钥已永久失效（该密钥声明了用户认证要求）", e)
        } catch (e: AEADBadTagException) {
            // ⚠️ 本引擎的密钥**不随锁屏凭据变化**（见上一条），所以走到这里最可能的原因是
            // **密文本身对不上**：Keystore 条目被删后由 `getOrCreateKey` 重新生成了一把
            // 同名新密钥（App 数据被清但 prefs 有备份/迁移残留、Keystore 数据库异常等），
            // 或 prefs 里的密文被改坏。**不是**「用户改了锁屏」。
            throw CryptoKeyUnavailableException("密文认证失败（密钥不匹配或数据损坏）", e)
        } catch (e: UnrecoverableKeyException) {
            throw CryptoKeyUnavailableException("Keystore 中没有可用的密钥", e)
        } catch (e: CryptoKeyUnavailableException) {
            throw e
        }
        // 其余 GeneralSecurityException 原样上抛 —— 那是真·实现缺陷，不该伪装成「重输密码」。
    }

    /**
     * 取别名对应的密钥，不存在则生成。
     *
     * ⚠️ `KeyStoreException`（Keystore 条目被清、Keystore 本身不可用）映射为
     * [CryptoKeyUnavailableException] —— 它与「密钥失效」的用户处置相同（重输密码）。
     */
    private fun getOrCreateKey(alias: String): SecretKey {
        val keyStore = try {
            KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        } catch (e: Exception) {
            throw CryptoKeyUnavailableException("无法访问 AndroidKeyStore", e)
        }

        val existing = try {
            keyStore.getKey(alias, null) as? SecretKey
        } catch (e: UnrecoverableKeyException) {
            throw CryptoKeyUnavailableException("Keystore 条目不可恢复", e)
        } catch (e: Exception) {
            throw CryptoKeyUnavailableException("读取 Keystore 条目失败", e)
        }

        if (existing != null) return existing

        return try {
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
            val spec = KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // ⚠️⚠️ 此处**绝不**加 setUserAuthenticationRequired(true)。
                // 本引擎的全部消费者都是后台场景（工作流无人解锁时也要能读密码）；
                // 加了这个设置会让解密抛 UserNotAuthenticatedException，功能静默失效。
                // ⚠️ 也不要调 setRandomizedEncryptionRequired(false) —— GCM 复用 IV 是灾难性的。
                .build()
            generator.init(spec)
            generator.generateKey()
        } catch (e: Exception) {
            throw CryptoKeyUnavailableException("生成 Keystore 密钥失败", e)
        }
    }
}
