package com.chaomixian.vflow.core.security

import java.util.Base64

/**
 * 对外唯一入口：把 [AliasGcmEngine] 的字节面包装成字符串面。
 *
 * 本类存在的第二个理由是**接缝**：[engine] 可注入（默认 [KeystoreGcmEngine]），
 * 于是 base64 往返与「失败怎么传出去」这两件事能在纯 JVM 单测里验证。
 *
 * ⚠️ **用 `java.util.Base64` 而不是 `android.util.Base64`**（方案 §4.3 原文写的是后者）：
 * 后者是 android.jar 的桩方法，在本模块的纯 JVM 单测里会抛
 * `RuntimeException: Method not mocked`（`testOptions.unitTests.isReturnDefaultValues` 未开，
 * 方案 §2.1 已核实）—— 那会让本类的「可纯 JVM 测」这一存在理由**直接不成立**。
 * `java.util.Base64` 是 JDK8 API，Android 自 API 26 起提供（本项目 `minSdk 29` 满足），
 * 且语义等价：`getEncoder()` 与 `android.util.Base64.NO_WRAP` **都不插入换行**。
 */
object KeystoreCryptoBox {

    /** WebDAV 密码专用别名。**别名一经发布不要改** —— 改了等于所有存量密码解不开。 */
    const val WEBDAV_PASSWORD_ALIAS = "vflow_webdav_password_v1"

    /**
     * 可注入引擎；默认 AndroidKeyStore 实现。单测里替换成假实现。
     *
     * ⚠️ 用 `var` 而非构造函数注入，是因为 `object` 没有构造点；
     * 用 `@Volatile` 保证测试线程切换后可见。**生产代码不写 `engine =`**。
     */
    @Volatile
    var engine: AliasGcmEngine = KeystoreGcmEngine

    /**
     * 加密为 base64。
     *
     * ⚠️ 空串**仍走加密**（产出合法密文），不特判 —— 让「有配置但没填密码」与「没配置」
     * 在存储层形状一致，T4 侧不必分支。
     *
     * ⚠️ 用 `java.util.Base64.getEncoder()`（无换行的 basic 编码），
     * **不是**会插换行的 MIME 编码 —— 带换行的 base64 塞进 JSON / prefs 会出问题。
     */
    @Throws(CryptoKeyUnavailableException::class)
    fun encryptToBase64(alias: String, plaintext: String): String {
        val ciphertext = engine.encrypt(alias, plaintext.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(ciphertext)
    }

    /**
     * 从 base64 解密。
     *
     * ⚠️ 空串输入返回空串（「未设置密码」是合法状态，不是错误）——
     * 这是给「已有存量数据 / 手工改过 prefs」的兜底。
     */
    @Throws(CryptoKeyUnavailableException::class)
    fun decryptFromBase64(alias: String, encoded: String): String {
        if (encoded.isEmpty()) return ""

        val blob = try {
            Base64.getDecoder().decode(encoded)
        } catch (e: IllegalArgumentException) {
            throw CryptoKeyUnavailableException("密文不是合法的 base64", e)
        }

        return String(engine.decrypt(alias, blob), Charsets.UTF_8)
    }

    /** 重置为默认引擎。供单测 `@After` 复原，避免污染后续用例。 */
    fun resetEngineForTest() {
        engine = KeystoreGcmEngine
    }
}
