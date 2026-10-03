// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/SecretsScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.BackupEnvironment
import com.chaomixian.vflow.core.backup.BackupScope
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.LogLevel
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopeImportResult
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.backup.WebDavBackupEntry
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 备份/恢复 **`SharedPreferences` 里的密钥**。
 *
 * ## 它备的是什么
 *
 * 全部凭证都在 prefs 里（方案 §2.8 的实测表）：飞书七件套、聊天 provider 配置
 * （JSON 内含 `apiKey`）、AI 配置的 `api_key`。白名单见 [SECRET_SLOTS]。
 *
 * ## 三条硬约束
 *
 * 1. **`sensitive = true`** ⇒ 只在「勾选包含密钥」**且**提供了口令时才写。
 *    [export] 无口令时返回 **null**（「本次不适用」），而不是空 payload ——
 *    后者会让用户以为「备了但恰好没数据」。
 * 2. **`defaultIncluded = false`** —— [ScopeGroup.SECRET] 的语义就是
 *    「显式勾选才写」。默认勾选会导致两个坏结果之一：无口令时静默不写
 *    （用户以为备了），或未经明确同意就写密钥。
 * 3. **整个 payload 就是一个 `$enc` 节点** —— 明文是一个 JSON 对象，
 *    键用 `prefs名.键名` 以消除歧义（避免两个 prefs 里的同名键撞车）。
 *    逐槽分开加密会让「哪个槽有哪些键」在信封里明文可见，
 *    而那本身就是敏感信息（例如「这个用户配了飞书」）。
 */
class SecretsScope : BackupScope {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.SECRET
    override val sensitive: Boolean = true
    override val defaultIncluded: Boolean = false

    /**
     * **排在 `workflows`（10）之后。**
     *
     * 理由是失败顺序：若工作流是 REPLACE 且中途失败，不应让密钥已经先落盘
     * —— 那会留下「密钥是新的、工作流是旧的」这种最难解释的中间态。
     */
    override val importOrder: Int = 100

    /**
     * **空列表**（与任何 scope 都无数据依赖）。
     *
     * ⚠️ 刻意不写假依赖：`importOrder()` 对「依赖未注册的 scope」按已就绪处理，
     * 所以假依赖也不会暴露成 bug —— 那更该留空，免得读代码的人以为真有什么依赖。
     */
    override val dependsOn: List<String> = emptyList()

    /**
     * 无口令 ⇒ **null**（本次不适用）；环境不支持 prefs ⇒ 同样 null。
     */
    override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload? {
        val store = env.secretStore ?: return null
        // ⚠️ 这一行是本 scope 的**安全闸**：没有口令就绝不明文写出密钥。
        //    删掉它会让「没勾密钥」的用户照样把全部凭证明文写进备份文件
        //    （反证 R7 锁住这条）。
        if (secrets == null) return null

        val plaintext = JsonObject()
        for (slot in SECRET_SLOTS) {
            val value = store.getString(slot.first, slot.second) ?: continue
            plaintext.addProperty(flatKeyOf(slot), value)
        }

        // ── WebDAV 配置转档（T6）────────────────────────────────
        // ⚠️⚠️ 这里**绝不能**把 `webdav_config_prefs` 的原始 JSON 直接搬进来：
        //    那个 JSON 里的密码是**设备 Keystore 密文**，换机后解不开
        //    ⇒ 用户看到「配置在备份里」，换机恢复后连不上还去查服务器。
        //    正确做法：经 WebDavBackupStore 解成**明文**，随整份 plaintext 一起被
        //    `secrets.seal()` 编成一个 `$enc` 节点 ⇒ 落盘是**备份口令**的密文。
        val webdavWarnings = mutableListOf<String>()
        env.webDavBackup?.let { webdavStore ->
            val array = JsonArray()
            for (entry in webdavStore.readAll()) {
                if (entry.password == null) {
                    // ⚠️ 这条密码解不开（密钥不匹配 / Keystore 条目没了，**不是**改锁屏所致
                    //    —— 见 `KeystoreGcmEngine` 的 `AEADBadTagException` 分支）。
                    //    不整体失败：保留该条配置（地址/用户名仍有用），但**不带密码**，
                    //    并把位置记进 scrubbedFields —— 用户能看到「这条没带密码」，
                    //    否则他会以为备了，换机后发现连不上还去查服务器。
                    //
                    // ⚠️ 位置串**必须**形如 `webdav.<配置名>.password`，
                    //    让用户与「未包含密钥」那些 `<stepId>.<paramId>` 区分开。
                    webdavWarnings += webdavWarningOf(entry.name)
                }
                array.add(entryToJson(entry))
            }
            if (array.size() > 0) plaintext.add(KEY_WEBDav, array)
        }

        // ⚠️ 一个槽都没读到 ⇒ 仍然返回**加密后的空对象**而不是 null。
        //    返回 null 会被上层理解成「本次不适用」，而实际上
        //    「勾了、有口令、但本机确实没配任何密钥」是一个**有效结果**，
        //    用户应当能在恢复时看到「这个备份里 secrets 是空的」。
        return ScopePayload(
            count = plaintext.size(),
            data = secrets.seal(plaintext.toString()),
            scrubbedFields = webdavWarnings
        )
    }

    /**
     * 导入。
     *
     * ⚠️ 走到这里时 `payload.data` **已经被 `BackupPipeline` 就地解成明文对象**
     * （解密在编排层做、不在 scope 里 —— 那样 scope 不需要知道
     * `EncryptionSection` 的存在，也让「密文从哪来」这件事只有一个入口）。
     *
     * ## MERGE 与 REPLACE 都只做 upsert，**绝不清理**
     *
     * （方案 §6.5，**已核实**）`module_config_prefs` 里混着大量**非密钥**配置 ——
     * `network_proxy` / `backtap_sensitivity` / `app_start_*` 等
     * （`ModuleConfigActivity.kt:115-121`），而且**该 prefs 文件的键集合是开放集**：
     * 任何模块都可能往里写。故在这里「按备份内容清理」会**误删用户的其他设置**。
     *
     * 代价是 REPLACE 导入**不删除本地多余的密钥键** —— 这是刻意的取舍，
     * 并且会记一条 W 日志，免得它变成一个没人知道的偏差。
     */
    override fun import(
        env: BackupEnvironment,
        payload: ScopePayload?,
        mode: ImportMode
    ): ScopeImportResult {
        if (payload == null) {
            return ScopeImportResult(id, ImportStatus.SKIPPED_NOT_SELECTED)
        }

        val store = env.secretStore
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "本环境不支持读写密钥"
            )

        val obj = payload.data.takeIf { it.isJsonObject }?.asJsonObject
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "secrets 的 data 不是 JSON 对象"
            )

        var imported = 0
        var skipped = 0

        // ── WebDAV 配置转回（T6）────────────────────────────────
        // ⚠️ `webdav_configs` **不是**扁平键 `prefs.key` —— 若不先摘出来，
        //    它会被下面的白名单判定当「不认识」而 `skipped++` **静默丢掉**。
        //    而 `writeAll` 内部用**本机 Keystore** 重新加密后落盘
        //    （备份里带的明文密码在这一刻才重新变成密文）。
        val webdavNode = obj.get(KEY_WEBDav)

        for ((flatKey, valueElement) in obj.entrySet()) {
            if (flatKey == KEY_WEBDav) continue
            val slot = slotOf(flatKey)
            if (slot == null || !valueElement.isJsonPrimitive || !valueElement.asJsonPrimitive.isString) {
                // 不在白名单里 / 值不是字符串 ⇒ 跳过该键。**不整体失败** ——
                // 备份里可能带着本机不认识的槽（更新的版本加的集成）。
                skipped++
                continue
            }
            store.putString(slot.first, slot.second, valueElement.asString)
            imported++
        }

        if (webdavNode is JsonArray) {
            val entries = parseWebDavEntries(webdavNode)
            val webdavStore = env.webDavBackup
            if (webdavStore == null) {
                // ⚠️ **不要静默丢弃** —— 本环境的密钥部分照样能导进去，
                //    只有 WebDAV 这块没写。记 W 让排障时看得见。
                env.log(
                    LogLevel.W, TAG,
                    "备份含 WebDAV 配置，但本环境不支持写入（已跳过该部分）"
                )
            } else {
                imported += webdavStore.writeAll(entries, mode)
            }
        }

        if (mode == ImportMode.REPLACE) {
            // ⚠️ 刻意只记日志、不做清理。理由见上方 KDoc。
            env.log(
                LogLevel.W,
                TAG,
                "secrets 在 REPLACE 模式下也只做 upsert（不清理本地多余的密钥键）—— " +
                    "因为 module_config_prefs 里混着非密钥配置，清理会误删用户设置"
            )
        }

        return ScopeImportResult(
            scopeId = id,
            status = ImportStatus.IMPORTED,
            imported = imported,
            skipped = skipped
        )
    }

    companion object {
        const val ID = "secrets"

        private const val TAG = "SecretsScope"

        /**
         * 白名单：`(prefs 名, 键名)`。
         *
         * ⚠️ **这是精确清单，不是规则**（与 `SecretFieldScrubber` 同一取舍）：
         * 新加的集成若把凭证写到别处，**不会自动被备份**。新增集成时要回到这里补一行。
         *
         * 键名逐字对应方案 §2.8 的实测结果：
         * - 飞书七件套：`ModuleConfigActivity.PREFS_NAME` + 其键名常量
         * - 聊天配置（JSON 内含 `apiKey`）：`ChatPresetRepository`
         * - AI 配置：`AiGenerationSheet`（裸字面量，故此处也写裸字面量）
         *
         * 由 `SecretsScopeTest` 的源码扫描用例锁住「这些键在当前代码库里确实存在」，
         * 防止它们随代码演进变成僵尸条目。
         */
        val SECRET_SLOTS: List<Pair<String, String>> = listOf(
            "module_config_prefs" to "feishu_app_secret",
            "module_config_prefs" to "feishu_app_access_token",
            "module_config_prefs" to "feishu_access_token",
            "module_config_prefs" to "feishu_user_access_token",
            "module_config_prefs" to "feishu_user_refresh_token",
            "module_config_prefs" to "feishu_user_auth_code",
            "module_config_prefs" to "feishu_user_code_verifier",
            "module_config_prefs" to "chat_provider_configs_json",
            "module_config_prefs" to "chat_presets_json",
            "ai_config" to "api_key"
        )

        /**
         * **默认排除且不提供勾选** —— `vflow_api_tokens` 里的 token 可再生、
         * 且安全敏感（设计正文 §1.4）。写在这里只为了让它**在代码里可见**：
         * 读者能看到「这个 prefs 被有意排除」，而不是怀疑是遗漏。
         */
        const val EXCLUDED_PREFS = "vflow_api_tokens"

        /** 把 `prefs名.键名` 拆回二元组；**不在白名单里 ⇒ null**。 */
        fun slotOf(flatKey: String): Pair<String, String>? {
            val index = flatKey.indexOf('.')
            if (index <= 0 || index == flatKey.length - 1) return null
            val slot = flatKey.substring(0, index) to flatKey.substring(index + 1)
            return if (slot in SECRET_SLOTS) slot else null
        }

        /** `(prefs名, 键名)` → `prefs名.键名`。与 [slotOf] 互为逆运算，两处共用一套口径。 */
        fun flatKeyOf(slot: Pair<String, String>): String = "${slot.first}.${slot.second}"

        /**
         * 按**明文**构造一份 payload（不经加密）。
         *
         * ⚠️ 生产路径上 payload 的 data 恒为 `$enc` 节点（由 [export] 产出）；
         * 到 [import] 手上时**已被 `BackupPipeline` 就地解成明文**。本方法
         * 存在的意义就是给单测与调用方直接构造那个「已解密」的形态 ——
         * 它同时把「import 拿到的到底是什么形状」这件事在代码里写清楚了。
         */
        fun slotPayload(entries: Map<String, String>): ScopePayload {
            val obj = JsonObject().apply { entries.forEach { (k, v) -> addProperty(k, v) } }
            return ScopePayload(obj.size(), obj)
        }

        /**
         * WebDAV 配置在 secrets payload 里的顶层键（T6）。
         *
         * ⚠️ 放在这里（与 [SECRET_SLOTS] / [EXCLUDED_PREFS] 同处），
         * 让「秘密范围的全部键」在一个文件里可见。
         *
         * ⚠️ 它**不是**一个 `prefs.key` 扁平键 —— 它是一个**结构**
         * （`WebDavBackupEntry` 的数组），因为设备 Keystore 的密文必须转档。
         */
        const val KEY_WEBDav = "webdav_configs"

        /** `webdav.<配置名>.password` —— 见 [export] 里那段注释。 */
        fun webdavWarningOf(configName: String): String = "webdav.$configName.password"

        /**
         * 明文密码载体 → JSON。`password == null` 时**不写 `password` 键**
         * （而不是写一个 null 值）—— 「键不存在」比「键值为 null」更明确，
         * 且让老版本读到时不会把 null 误当成「密码就是 null」。
         */
        fun entryToJson(entry: WebDavBackupEntry): JsonObject = JsonObject().apply {
            addProperty("id", entry.id)
            addProperty("name", entry.name)
            addProperty("baseUrl", entry.baseUrl)
            addProperty("username", entry.username)
            entry.password?.let { addProperty("password", it) }
            addProperty("allowInsecureTls", entry.allowInsecureTls)
            addProperty("timeoutSeconds", entry.timeoutSeconds)
            addProperty("remoteBasePath", entry.remoteBasePath)
        }

        /**
         * JSON → [WebDavBackupEntry]。**纯函数，坏形状 ⇒ 该条丢弃**（不抛）。
         *
         * ⚠️ `password` 缺失 ⇒ **null**（「备份里这条没带密码」），
         * 而 null 在下游 `writeAll` 里的语义是「**不动本机的密码**」——
         * 绝不能归一成 `""`（那会抹掉本机已存的密码）。
         */
        fun parseWebDavEntries(array: JsonArray): List<WebDavBackupEntry> =
            array.mapNotNull { element ->
                val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
                val id = obj.stringOrNull("id") ?: return@mapNotNull null
                WebDavBackupEntry(
                    id = id,
                    name = obj.stringOrNull("name") ?: id,
                    baseUrl = obj.stringOrNull("baseUrl").orEmpty(),
                    username = obj.stringOrNull("username").orEmpty(),
                    // ⚠️ 缺失 ⇒ null（不是 ""）。见上方 KDoc。
                    password = obj.stringOrNull("password"),
                    allowInsecureTls = obj.booleanOrNull("allowInsecureTls") ?: false,
                    timeoutSeconds = obj.intOrNull("timeoutSeconds") ?: 15,
                    remoteBasePath = obj.stringOrNull("remoteBasePath").orEmpty()
                )
            }

        private fun JsonObject.stringOrNull(key: String): String? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isString }?.asString

        private fun JsonObject.booleanOrNull(key: String): Boolean? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isBoolean }?.asBoolean

        private fun JsonObject.intOrNull(key: String): Int? =
            get(key)?.takeIf { it.isJsonPrimitive }?.asJsonPrimitive
                ?.takeIf { it.isNumber }?.asInt
    }
}
