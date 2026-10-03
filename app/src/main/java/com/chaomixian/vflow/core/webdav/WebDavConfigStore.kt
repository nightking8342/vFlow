package com.chaomixian.vflow.core.webdav

import android.content.Context
import androidx.core.content.edit
import com.chaomixian.vflow.core.security.CryptoKeyUnavailableException
import com.chaomixian.vflow.core.security.KeystoreCryptoBox
import com.google.gson.Gson

/**
 * WebDAV 配置的持久化 + CRUD + 测试连接编排。
 *
 * 存储：**新 prefs 文件** `webdav_config_prefs` / key `webdav_configs_json`（JSON 数组）。
 * ⚠️ 不复用 `module_config_prefs`：那是模块配置的域，混进去会让备份 scope（T2/T5）
 * 的范围划定变模糊，且 T4 的模块要按 id 读它、不该看见模块无关的键。
 *
 * 密码字段：**进出本类的私有边界时明文**，落盘前经 [KeystoreCryptoBox] 加密。
 * ⚠️ [upsert] 接受 `password` 明文；[getAll] 返回的仍是**加密后**的值
 * （清单页不该把明文散到 UI 层，那是多余的攻击面）。
 * 需要明文的只有 [testConnection] 与 T4，走 [decryptPassword]。
 */
object WebDavConfigStore {

    internal const val PREFS_NAME = "webdav_config_prefs"
    internal const val KEY_CONFIGS_JSON = "webdav_configs_json"

    private val gson = Gson()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 读取全部配置（密码保持加密态）。
     *
     * ⚠️ 解析失败 / prefs 为空 ⇒ 返回**空表**，与 `GlobalVariableStore` 同款容错
     * （T4 的模块会在 `appContext` 可能未就绪时调它）。
     * ⚠️ 每条都过 [coerceConfig] —— Gson 走反射会把缺失字段填 null / 0，
     * 不过一遍会让 UI 拿到 null 崩（R13）。
     */
    fun getAll(context: Context): List<WebDavConfig> {
        val json = prefs(context).getString(KEY_CONFIGS_JSON, null).orEmpty()
        if (json.isBlank()) return emptyList()

        return runCatching {
            gson.fromJson(json, Array<WebDavConfig>::class.java)
                .orEmpty()
                .filterNotNull()
                .map { it.toCoerced() }
        }.getOrDefault(emptyList())
    }

    /** 按 id 取单条；不存在返回 null。 */
    fun get(context: Context, id: String): WebDavConfig? =
        getAll(context).firstOrNull { it.id == id }

    /**
     * 新增或按 id 覆盖。
     *
     * @param password 明文密码。传 `null` ⇒ **保持原有的密文不动**（编辑时用户没改密码的常见路径）。
     *                 传 `""` ⇒ 写入「空密码的密文」（用户显式清空）。
     *                 不存在的 id ⇒ 追加到列表末尾（顺序即数组顺序，UI 按持久化顺序展示）。
     */
    @Throws(CryptoKeyUnavailableException::class)
    fun upsert(context: Context, config: WebDavConfig, password: String?): WebDavConfig {
        val current = getAll(context).toMutableList()
        val index = current.indexOfFirst { it.id == config.id }

        val encrypted = when {
            password == null && index >= 0 -> current[index].encryptedPassword
            password == null -> "" // 新增但没给密码 —— 存空串，不加密空串（避免无谓的 Keystore 调用）
            else -> KeystoreCryptoBox.encryptToBase64(
                KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS,
                password
            )
        }

        val stored = config.copy(
            baseUrl = normalizeBaseUrl(config.baseUrl),
            timeoutSeconds = clampTimeout(config.timeoutSeconds),
            encryptedPassword = encrypted
        )

        if (index >= 0) current[index] = stored else current.add(stored)
        persist(context, current)
        return stored
    }

    /** 删除。不存在时静默返回（幂等）。 */
    fun delete(context: Context, id: String) {
        val current = getAll(context)
        val remaining = current.filterNot { it.id == id }
        if (remaining.size == current.size) return
        persist(context, remaining)
    }

    /**
     * 解出明文密码。**仅供 [testConnection] 与 T4 使用**，不要在 UI 列表里调。
     *
     * @throws CryptoKeyUnavailableException 密钥失效 —— 调用方必须把它渲染成
     *         「密钥失效，请重新输入密码」，**不要**渲染成「连接失败」。
     */
    @Throws(CryptoKeyUnavailableException::class)
    fun decryptPassword(config: WebDavConfig): String =
        KeystoreCryptoBox.decryptFromBase64(
            KeystoreCryptoBox.WEBDAV_PASSWORD_ALIAS,
            config.encryptedPassword
        )

    /**
     * 测试连接。
     *
     * ⚠️⚠️ **返回类型刻意是密封类而不是 `Boolean`** —— 密钥失效与网络失败必须分开：
     *
     * | 情形 | 用户该做什么 |
     * |---|---|
     * | 密钥失效 | 重新输入密码 |
     * | 网络错误 | 检查地址 / 网络 |
     * | 认证失败 | 改用户名密码 |
     *
     * 三者的**处置完全不同**，压成一个 false 会让用户对着正确的地址反复重试。
     *
     * ⚠️ 本函数**是阻塞的**（OkHttp `execute()`），**不得在主线程调用** —— 调用方负责切到 IO。
     */
    fun testConnection(context: Context, configId: String): WebDavTestResult {
        val config = get(context, configId) ?: return WebDavTestResult.ConfigNotFound

        if (config.baseUrl.isBlank()) {
            return WebDavTestResult.InvalidConfig("地址为空")
        }

        val password = try {
            decryptPassword(config)
        } catch (e: CryptoKeyUnavailableException) {
            // ⚠️⚠️ 这是本任务的核心质量点：密钥失效**必须**是独立分支，
            // 不能落到下面的网络错误里（否则用户会一直去查服务器地址）。
            return WebDavTestResult.KeyUnavailable(e.message ?: "设备密钥不可用")
        }

        val probe = WebDavProbe.probe(
            baseUrl = config.baseUrl,
            username = config.username,
            password = password,
            allowInsecureTls = config.allowInsecureTls,
            timeoutSeconds = config.timeoutSeconds
        )

        return if (probe.outcome == WebDavProbeOutcome.SUCCESS) {
            WebDavTestResult.Success(probe.httpCode ?: 207)
        } else {
            WebDavTestResult.ServerRejected(probe.outcome, probe.httpCode, probe.detail)
        }
    }

    private fun persist(context: Context, configs: List<WebDavConfig>) {
        prefs(context).edit(commit = true) {
            putString(KEY_CONFIGS_JSON, gson.toJson(configs.toTypedArray()))
        }
    }

    /** 见 [coerceConfig] 的说明（R13 防线）。 */
    private fun WebDavConfig.toCoerced(): WebDavConfig = coerceConfig(
        id, name, baseUrl, username, encryptedPassword,
        allowInsecureTls, timeoutSeconds, remoteBasePath
    )
}

/** 单次测试连接的结论。 */
sealed interface WebDavTestResult {
    /** 服务器接受 PROPFIND。 */
    data class Success(val httpCode: Int) : WebDavTestResult

    /** 服务器给了明确答复，但不是成功。 */
    data class ServerRejected(
        val outcome: WebDavProbeOutcome,
        val httpCode: Int?,
        val detail: String?
    ) : WebDavTestResult

    /** ⚠️ 密钥失效 —— UI 必须提示「重新输入密码」，不能笼统说「连接失败」。 */
    data class KeyUnavailable(val reason: String) : WebDavTestResult

    /** 配置 id 不存在（用户删了还在测，或并发）。 */
    data object ConfigNotFound : WebDavTestResult

    /** 地址为空 / 无法解析。 */
    data class InvalidConfig(val reason: String) : WebDavTestResult
}
