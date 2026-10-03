package com.chaomixian.vflow.core.webdav

import com.google.gson.annotations.SerializedName

/**
 * 一套 WebDAV 配置。
 *
 * ⚠️ [encryptedPassword] 存的是 **Keystore 加密后的 base64**，不是明文。
 * 明文只在 [WebDavConfigStore.testConnection] 内部短暂出现。
 *
 * ## ⚠️⚠️ 每个字段上的 [SerializedName] 是**刻意的加固，不是冗余**
 *
 * 本类经 Gson 持久化进 `SharedPreferences`（`webdav_configs_json`）。**不加注解时
 * Gson 用「运行时字段名」作 JSON key**，而本仓库的 R8 配置里：
 *
 * - **没有 `-applymapping`、也没有混淆字典**（`proguard-rules.pro` 全仓 grep 确认）
 * - ⇒ 字段短名（`id -> a`、`encryptedPassword -> e`）**只对当前这一份二进制稳定**，
 *   任何一次重新构建都可能重新分配（`e -> f`、次序变化等）。
 * - 序列化与反序列化**都在同一次构建内**，用的是同一个混淆后的名字，故**同一次构建内
 *   是往返对称的**（这正是 `coerceConfig` 那条防线之外、问题不在此处暴露的原因）。
 * - **但用户升级 App 时换了一份二进制** ⇒ 旧 prefs 里的 JSON 用旧短名、新代码用新短名
 *   ⇒ 全部字段读不出来 ⇒ 配置列表恒空、密码需重填。**静默**：不报错、只表现为「配置没了」。
 *
 * **注意措辞**：这是**加固**（把 wire 契约固化在源码），**不是「修复一个已知缺陷」** ——
 * 同一份二进制内往返始终是对称的，从未因此坏过。
 *
 * ⚠️ **为什么用 `@SerializedName` 而不是 `-keep class ... { *; }`**：后者只保字段名不被 R8
 * 改写，**不阻止开发者把 Kotlin 属性改名**（那样 JSON key 会跟着变、老数据同样读不出）。
 * 注解把 key 钉死在源码里，是更稳的一层。
 *
 * 改动本类的字段时：**key 字符串一经发布不要改**（等同于存储格式的一部分）。
 */
data class WebDavConfig(
    @SerializedName("id") val id: String,
    @SerializedName("name") val name: String,
    /** 规范化后的地址：已去尾斜杠、已去首尾空白。由 [normalizeBaseUrl] 产出。 */
    @SerializedName("baseUrl") val baseUrl: String,
    @SerializedName("username") val username: String,
    @SerializedName("encryptedPassword") val encryptedPassword: String,
    /** 允许自签名 / 过期证书。默认关 —— 安静地接受坏证书是安全缺陷。 */
    @SerializedName("allowInsecureTls") val allowInsecureTls: Boolean = false,
    @SerializedName("timeoutSeconds") val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    /** 远端根路径，可空/可无前导斜杠；拼 URL 时经 [joinUrl] 处理。 */
    @SerializedName("remoteBasePath") val remoteBasePath: String = ""
) {
    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15

        /** 防呆上限：别让用户填 99999 把工作流线程挂死。 */
        const val MAX_TIMEOUT_SECONDS = 120

        /** 超时下限：0 或负数会被 OkHttp 当成「无限等待」。 */
        const val MIN_TIMEOUT_SECONDS = 1
    }
}

/**
 * 把 [WebDavConfig] 里可能为 null 的字符串字段收敛成非空。
 *
 * ⚠️⚠️ **存在的理由是 Gson 的行为**：Gson 反序列化走反射、**不调构造函数**，
 * 缺字段会被填 `null`（而不是走 Kotlin 的默认值）。而 [WebDavConfig] 的字段声明是非空 `String`
 * ⇒ 用户手工改坏 prefs / 旧版本数据缺字段时，UI 侧会拿到 null 并崩溃。
 *
 * 这是 R13 的防线：`getAll()` 必须对每条记录过一遍本函数。
 */
internal fun coerceConfig(
    id: String?,
    name: String?,
    baseUrl: String?,
    username: String?,
    encryptedPassword: String?,
    allowInsecureTls: Boolean?,
    timeoutSeconds: Int?,
    remoteBasePath: String?
): WebDavConfig = WebDavConfig(
    id = id ?: "",
    name = name ?: "",
    baseUrl = baseUrl ?: "",
    username = username ?: "",
    encryptedPassword = encryptedPassword ?: "",
    allowInsecureTls = allowInsecureTls ?: false,
    // ⚠️ `null`（字段缺失且 Gson 填 null）与 `<= 0`（Gson 对缺失的 Int 填 0）
    // **都**表示「没有有效值」⇒ 回落到默认值，而不是钳到下限。
    // 若钳到下限会让「旧数据缺字段」变成「1 秒超时」——那是静默的行为劣化。
    timeoutSeconds = if (timeoutSeconds == null || timeoutSeconds <= 0) {
        WebDavConfig.DEFAULT_TIMEOUT_SECONDS
    } else {
        clampTimeout(timeoutSeconds)
    },
    remoteBasePath = remoteBasePath ?: ""
)

/** 把超时钳到合法区间（防 `0` / 负数 / 超大值）。 */
internal fun clampTimeout(seconds: Int): Int =
    seconds.coerceIn(WebDavConfig.MIN_TIMEOUT_SECONDS, WebDavConfig.MAX_TIMEOUT_SECONDS)

/**
 * 地址规范化：去首尾空白 → 去尾斜杠（`https://dav.example.com/` → `https://dav.example.com`）。
 *
 * ⚠️ 只去**尾部**斜杠，不动中间的；`https://` 里的 `//` 必须原样保留。
 * ⚠️ 全斜杠的怪值（`"///"`）会得到空串，调用侧按「地址为空」拒绝。
 */
fun normalizeBaseUrl(raw: String): String = raw.trim().trimEnd('/')

/**
 * 拼接远端路径。规则：
 * - 各段单独 `trim('/')` 后再拼，中间**恰好**一个 `/`
 * - [path] 为空 ⇒ 结果是 [base]（**不加尾斜杠**，因为本任务的 PROPFIND 目标是配置本身的 baseUrl）
 * - ⚠️ **不做百分号编码** —— 那是 T4 的 `WebDavUrlBuilder` 的职责（要用 `HttpUrl.addPathSegment`）。
 *   本函数只服务于「人工填写的简单路径」与 UI 展示。
 */
fun joinUrl(base: String, path: String): String {
    val cleanBase = base.trimEnd('/')
    val cleanPath = path.trim().trim('/')
    if (cleanBase.isEmpty()) return if (cleanPath.isEmpty()) "" else "/$cleanPath"
    if (cleanPath.isEmpty()) return cleanBase
    return "$cleanBase/$cleanPath"
}
