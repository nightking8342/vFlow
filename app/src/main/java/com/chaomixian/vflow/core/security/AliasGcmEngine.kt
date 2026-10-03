package com.chaomixian.vflow.core.security

import java.security.GeneralSecurityException

/**
 * 按 **alias** 取密钥的 AES-256-GCM 加密引擎接缝。
 *
 * 存在的理由是**可测性**：AndroidKeyStore 在纯 JVM 单测里不可用
 * （`testOptions.unitTests.isReturnDefaultValues` 未开，调 android.jar 桩方法即抛
 * `RuntimeException: Method not mocked`），故把「引擎」抽成接口，
 * 让 [KeystoreCryptoBox] 的加解密与 base64 编排能注入假实现来验证。
 *
 * ⚠️ 实现方必须保证 [decrypt] 对「密钥不可用」的情形抛 [CryptoKeyUnavailableException]，
 * **不能**抛 `GeneralSecurityException` 之类的底层类型 —— 上层要据此给出「请重新输入密码」，
 * 而不是含糊的「操作失败」。
 *
 * ## ⚠️⚠️ 为什么不与同包的 [AesGcmEngine] 合并成一个接口
 *
 * 两者的**方法面根本不同**，合并要引入一层无实益的间接：
 *
 * | | [AesGcmEngine] | 本接口 |
 * |---|---|---|
 * | 方法 | `seal(key, plaintext, aad)` / `open(key, sealed, aad)` | `encrypt(alias, plaintext)` / `decrypt(alias, blob)` |
 * | 密钥从哪来 | **调用方自己派生并持有**（备份口令经 PBKDF2） | 交给 AndroidKeyStore 按 alias 托管，**调用方拿不到字节** |
 * | 服务于 | 「用**口令**加密」（备份文件可跨机解密） | 「用**设备密钥**加密」（WebDAV 密码落盘） |
 * | 可否纯 JVM 实现 | 可以（JCE 即可） | 不可以（依赖 AndroidKeyStore） |
 *
 * 合二为一的话，共用的那个接口要么带 `key: ByteArray` 参数（设备密钥场景拿不出这个参数），
 * 要么带 `alias: String`（口令场景没有 alias），只能退化成「两种签名都放进去、各自一半方法是死的」；
 * 或者引入一层 `alias → key` 的间接 —— 那还需要在纯 JVM 单测里造一个可注入的 `KeyStore` 替身。
 * 收益不抵风险，故**保持两个独立接口**。
 */
interface AliasGcmEngine {
    /** 用 [alias] 对应的密钥加密，返回 `iv || ciphertext || tag`。密钥不存在时新建。 */
    @Throws(CryptoKeyUnavailableException::class, GeneralSecurityException::class)
    fun encrypt(alias: String, plaintext: ByteArray): ByteArray

    /** 用 [alias] 对应的密钥解密。密钥永久失效 / 认证失败时抛 [CryptoKeyUnavailableException]。 */
    @Throws(CryptoKeyUnavailableException::class, GeneralSecurityException::class)
    fun decrypt(alias: String, blob: ByteArray): ByteArray
}

/**
 * 「密钥不可用」——**唯一**需要向用户区分的一类失败。
 *
 * ⚠️ 与「服务器挂了」是**两件完全不同的事**：本异常的唯一处置是「让用户重新输入密码」。
 * 若不区分，用户在 WebDAV 测试连接失败时会一直去查服务器地址，永远查不到。
 */
class CryptoKeyUnavailableException(message: String, cause: Throwable? = null) :
    Exception(message, cause)
