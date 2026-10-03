// 文件: main/java/com/chaomixian/vflow/core/backup/WebDavBackupStore.kt
package com.chaomixian.vflow.core.backup

/**
 * WebDAV 配置的备份转档接缝。
 *
 * ## 为什么必须是转档，不能直接搬
 *
 * 落盘的密码是**设备 Keystore 密文**（alias `vflow_webdav_password_v1`，
 * 见 `KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS`）。Keystore 密钥**与设备绑定**，
 * 换机后解不开。直接把它塞进备份 = 导出一坨换机后无法解密的字节，
 * 而用户看到「配置在备份里」，以为备了其实没备 —— 典型静默失效。
 *
 * ## 契约
 *
 * 密码在本接口的**进出两侧一律是明文**：
 *  - 导出：[readAll] 给出明文 → `SecretsScope` 用**备份口令**把它们连同整份
 *    secrets payload 一起加密（`SecretContext.seal`）⇒ 落盘是密文。
 *  - 导入：`SecretsScope` 解出明文 → [writeAll] 用**本设备的 Keystore**重新加密后落盘。
 *
 * ⚠️ 明文只在内存里活一次，**不落任何盘**。
 */
interface WebDavBackupStore {
    /**
     * 读全部配置，密码已解成明文。
     *
     * ⚠️ 单条解密失败（Keystore 密钥失效）**不得抛** —— 该条 [WebDavBackupEntry.password]
     * 置 null，其余照常返回。整体失败会让「一台 Keystore 坏了的设备无法导出任何其它数据」。
     */
    fun readAll(): List<WebDavBackupEntry>

    /**
     * 写回。密码经本设备 Keystore 重新加密。
     *
     * @param mode MERGE ⇒ 逐条 upsert，本地多出的配置保留；
     *             REPLACE ⇒ upsert 之外还删除本地「不在 [entries] 里」的配置。
     * @return 成功写入的条数。
     */
    fun writeAll(entries: List<WebDavBackupEntry>, mode: ImportMode): Int
}

/**
 * 明文密码载体。
 *
 * ⚠️ **不要 log 它、不要序列化到任何未加密的地方**。
 *
 * ⚠️⚠️ **它没有 `encryptedPassword` 字段**（设计使然）——
 * 于是「把别的设备的 Keystore 密文搬过来」这条路径**在类型层面就不存在**。
 * 备份里存的是**备份口令**的密文（整份 payload 被 `SecretContext.seal`），
 * 导入侧拿到的是明文，再由本机 `WebDavConfigStore.upsert` 重新加密。
 */
data class WebDavBackupEntry(
    val id: String,
    val name: String,
    val baseUrl: String,
    val username: String,
    /** null ⇒ 本机 Keystore 解不开（该条不带密码进备份）。 */
    val password: String?,
    val allowInsecureTls: Boolean,
    val timeoutSeconds: Int,
    val remoteBasePath: String
)
