// 文件: main/java/com/chaomixian/vflow/core/security/AesGcmEngine.kt
package com.chaomixian.vflow.core.security

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * AEAD（带关联数据的认证加密）接缝。
 *
 * ## 存在的唯一理由
 *
 * **让「口令错」这条路径可以纯 JVM 注入复现。**
 *
 * 备份加密有一条硬要求：**「口令错」与「数据损坏」必须可区分**。而这两者
 * 在密码学层面都会表现为 GCM tag 校验失败（`AEADBadTagException`）——
 * 要验证我们的判定链真的把它们分开了，就得能**确定性地造出**这两种情形。
 * 有了这个接口，测试可以注入一个「总是解不开」的假引擎来单独驱动判定分支，
 * 而不必依赖「跑一遍看看」。
 *
 * ## 落点
 *
 * ⚠️ `core/security/` **不是** `androidx.security` —— 后者已 deprecated
 * （设计正文决策 6：WebDAV 密码用自建 Keystore 封装，不引入 androidx.security）。
 * 本文件零新依赖，只用 JDK 自带的 JCE。
 *
 * ⚠️ 本包当前**不得**出现 `android.*`（由 `SecretLayerPurityTest` 机器化保证）。
 * T3 若要新增 `KeystoreGcmEngine.kt`（Android Keystore 实现），届时由 T3
 * 自行放开该文件 —— 本检查准确描述了 T2 交付时的现状。
 */
interface AesGcmEngine {

    /**
     * 加密并认证。
     *
     * @param key 密钥字节（备份场景下是 PBKDF2 派生的 256bit）。
     * @param aad 关联数据：**参与认证但不参与加密**。用于把密文绑定到某个位置，
     *   防止攻击者把 A 字段的密文搬到 B 字段（本项目的 `SecretEnvelope`
     *   目前传 null，保留该参数是为了接缝完整）。
     * @return `iv ‖ ct ‖ tag` —— **IV 前置、tag 尾置**，整个返回值即
     *   「解开它所需的全部字节」（salt 除外，那在信封里）。
     */
    fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray? = null): ByteArray

    /**
     * 解密并验证。
     *
     * @throws AeadFailure 认证失败（tag 不符）或密文长度不合法等**一切**解不开的情形。
     *   刻意不把 `AEADBadTagException` 直接漏出去 —— 调用方只该看到一个自己的类型，
     *   否则「口令错」与「损坏」的判定会散落在各处 `catch` 里。
     */
    fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray
}

/** 解不开的唯一出口类型。见 [AesGcmEngine.open]。 */
class AeadFailure(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * 纯 JVM 实现：`AES/GCM/NoPadding` + `GCMParameterSpec(128, iv)`。
 *
 * | 参数 | 值 | 依据 |
 * |---|---|---|
 * | 算法 | `AES/GCM/NoPadding` | 设计正文 §1.3 |
 * | IV | **12 字节**，每次随机 | GCM 的标准长度；非 12 字节会在 JCE 里被 GHASH 再派生一次 |
 * | tag | **128 bit** | GCM 最大强度 |
 *
 * ⚠️ **IV 必须每次随机且不可重用** —— GCM 在「同一密钥 + 同一 IV」下重复使用
 * 会泄漏明文异或值。本实现的 [JvmAesGcmEngine.newIv] 每次调 `SecureRandom`，
 * 没有任何缓存/复用路径。
 */
class JvmAesGcmEngine : AesGcmEngine {

    override fun seal(key: ByteArray, plaintext: ByteArray, aad: ByteArray?): ByteArray {
        val iv = newIv()
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }

        val body = try {
            cipher.doFinal(plaintext)
        } catch (e: Exception) {
            throw AeadFailure("加密失败：${e.message}", e)
        }

        // IV 前置 —— 让「解开它所需的一切」都在这一个字节串里。
        return iv + body
    }

    override fun open(key: ByteArray, sealed: ByteArray, aad: ByteArray?): ByteArray {
        // ⚠️ 长度校验必须在 `copyOfRange` 之前：IV 长度不足时 `copyOfRange` 会
        // 抛 `ArrayIndexOutOfBoundsException`（不是 AeadFailure），
        // 调用方按类型分流时会漏掉这条路径。
        // 最短合法长度 = IV(12) + tag(16)，密文本身可以为 0 字节（空明文的合法产物）。
        if (sealed.size < IV_BYTES + TAG_BYTES) {
            throw AeadFailure("密文长度不足（${sealed.size} < ${IV_BYTES + TAG_BYTES}）")
        }

        val iv = sealed.copyOfRange(0, IV_BYTES)
        val body = sealed.copyOfRange(IV_BYTES, sealed.size)

        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, KEY_ALGORITHM), GCMParameterSpec(TAG_BITS, iv))
        aad?.let { cipher.updateAAD(it) }

        return try {
            cipher.doFinal(body)
        } catch (e: Exception) {
            // ⚠️ 这里**不区分** tag 不符 / 密文被截断 / 密钥错 —— 它们对上层是同一件事
            //   （「这个密文用这个密钥解不开」），区分它们是 `BackupCrypto` 的职责
            //   （靠 verifier + verifierHash 两条独立证据，而不是靠 catch 到的异常类型）。
            throw AeadFailure("解密失败（密钥不符或密文已损坏）", e)
        }
    }

    companion object {
        const val KEY_ALGORITHM = "AES"
        const val TRANSFORMATION = "AES/GCM/NoPadding"

        /** GCM 标准 IV 长度。改它会让既有密文全部解不开 —— 不是可调参数。 */
        const val IV_BYTES = 12

        /** tag 长度（字节）= 128 bit。 */
        const val TAG_BYTES = 16

        const val TAG_BITS = 128

        private val secureRandom = SecureRandom()

        /** 每次调用都取新的随机 IV，**没有任何复用路径**。 */
        fun newIv(): ByteArray = ByteArray(IV_BYTES).also { secureRandom.nextBytes(it) }
    }
}
