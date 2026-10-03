// 文件: main/java/com/chaomixian/vflow/core/backup/SecretStore.kt
package com.chaomixian.vflow.core.backup

/**
 * 密钥读写的 **Android-free 接缝**。
 *
 * ## 为什么需要它
 *
 * T1 的 [BackupEnvironment] 只有「读写工作流 / 文件夹 / 全局变量」的能力 ——
 * 因为那三类数据本来就存在 `WorkflowManager` / `FolderManager` /
 * `GlobalVariableStore` 里。但 T2 要备份的密钥**全部存在 `SharedPreferences`**
 * （`module_config_prefs` / `ai_config`，见方案 §2.8 的实测表），
 * 而 T1 的接缝里没有任何 prefs 读写能力。
 *
 * 补一个通用的 `SecretStore` 而不是给 [BackupEnvironment] 加一串
 * `getFeishuAppSecret()` 之类的方法，是因为：**密钥的存储位置会变**。
 * 现在有三个 prefs 名，将来加了新集成就可能有第四个 ——
 * 每加一个就扩一次 [BackupEnvironment] 接口，会让四个 scope 与两个实现
 * （Android / Fake）全部跟着改。通用键值接口把这类变化收在一处
 * （`SecretsScope.SECRET_SLOTS` 那张白名单表）。
 *
 * ## 为什么只有 String
 *
 * T2 需要读写的对象**只有** `SharedPreferences`，而本项目在 prefs 里存的凭证
 * 全部是 String（含以 JSON 字符串形式存的 `chat_provider_configs_json`）。
 * 加 `getInt` / `getBoolean` 只是没有消费者的空接口。
 *
 * ## 语义
 *
 * `value == null` ⇒ **删除该键**（与 `SharedPreferences.Editor.remove` 一致）。
 * 这与「写入空串」是两件不同的事。
 */
interface SecretStore {

    /** 读。键不存在 ⇒ null（不返回空串 —— 两者对导入语义不同）。 */
    fun getString(prefsName: String, key: String): String?

    /** 写。[value] 为 null ⇒ 删除该键。 */
    fun putString(prefsName: String, key: String, value: String?)
}
