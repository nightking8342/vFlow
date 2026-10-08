// 文件: main/java/com/chaomixian/vflow/core/backup/BackupEnvelope.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.stream.JsonWriter
import java.io.StringWriter

/**
 * 备份信封的读写与版本判定。
 *
 * ## 信封形状
 *
 * ```json
 * {
 *   "schema": "vflow.backup",
 *   "schemaVersion": 1,
 *   "createdAt": 1712345678000,
 *   "app": { "versionName": "1.5.4", "versionCode": 50 },
 *   "scopes": {
 *     "folders":          { "count": 3,   "data": [ ... ] },
 *     "global_variables": { "count": 5,   "data": [ {"name":"k","type":"string","value":"v"} ] },
 *     "workflows":        { "count": 12,  "data": [ ... ] }
 *   },
 *   "encryption": null,
 *   "summary": { "includedScopes": ["folders","global_variables","workflows"],
 *                "excludedScopes": [], "scrubbedFields": [] }
 * }
 * ```
 *
 * ## 兼容三种输入
 *
 * 1. **现代信封**（有 `schema`）—— 正常解析。
 * 2. **legacy 备份**（无 `schema`，顶层有 `workflows` / `folders`，或根本身是数组）
 *    —— 交给 [LegacyBackupAdapter]。
 * 3. **来自更新版本的备份**（`schemaVersion` 高于本机）—— **拒绝并提示，不半解析**。
 *    半解析的后果是用户以为恢复了，实际只恢复了一部分且没有任何提示。
 */
object BackupEnvelope {

    const val SCHEMA = "vflow.backup"

    /** 当前信封版本。**加字段不必改它**；只有「旧版 App 会理解错新备份」时才 +1。 */
    const val SCHEMA_VERSION = 1

    private const val KEY_SCHEMA = "schema"
    private const val KEY_SCHEMA_VERSION = "schemaVersion"
    private const val KEY_CREATED_AT = "createdAt"
    private const val KEY_APP = "app"
    private const val KEY_APP_VERSION_NAME = "versionName"
    private const val KEY_APP_VERSION_CODE = "versionCode"
    private const val KEY_SCOPES = "scopes"
    private const val KEY_COUNT = "count"
    private const val KEY_DATA = "data"
    private const val KEY_SUMMARY = "summary"
    private const val KEY_INCLUDED_SCOPES = "includedScopes"
    private const val KEY_EXCLUDED_SCOPES = "excludedScopes"
    private const val KEY_SCRUBBED_FIELDS = "scrubbedFields"
    private const val KEY_ENCRYPTION = "encryption"

    data class Header(
        val schemaVersion: Int,
        val createdAt: Long,
        val appVersionName: String?,
        val appVersionCode: Int?
    )

    sealed interface ReadResult {
        data class Modern(
            val header: Header,
            val scopes: Map<String, ScopePayload>,
            /**
             * 信封的加密段。**null = 这份备份没有加密任何东西**
             * （T1 时代的所有备份都是这种）。
             */
            val encryption: EncryptionSection? = null
        ) : ReadResult

        /** 旧格式备份。解析留给 [LegacyBackupAdapter]（本对象不依赖任何 App 侧类）。 */
        data class Legacy(val root: JsonObject) : ReadResult

        /** `schemaVersion` 高于本机 ⇒ **拒绝，不半解析**。 */
        data class TooNew(val schemaVersion: Int) : ReadResult

        data class Invalid(val reason: String) : ReadResult
    }

    /**
     * 写出信封。
     *
     * ⚠️ `json` **只用于把 payload 转成 JSON 元素**（`export` 里已经做过了），
     * 本函数对最终文本的序列化**不走 Gson** —— 见 [writeElementTree] 的说明。
     * 参数保留是为了签名稳定（它确实是这次备份所用的 Gson 实例）。
     *
     * @param sections 各 scope 的 payload。**顺序即 JSON 键顺序** —— 调用方
     *   （`BackupScopeRegistry.exportAll`）已按导入拓扑排好，这里不再排序，
     *   以免两处各排一次而漂移。
     * @param scrubbedFields 被清洗掉的敏感字段位置。为空 ⇒ 写空数组（不改形状）。
     * @param encryption 加密段。**null ⇒ 仍写 `JsonNull`**（T1 行为逐字不变）——
     *   见下方 `root.add(KEY_ENCRYPTION, …)` 处的说明。
     */
    fun write(
        json: Gson,
        sections: Map<String, ScopePayload>,
        includedScopes: List<String>,
        excludedScopes: List<String>,
        scrubbedFields: List<String>,
        appVersionName: String,
        appVersionCode: Int,
        createdAt: Long = System.currentTimeMillis(),
        encryption: EncryptionSection? = null
    ): String = writeElementTree(
        buildRoot(
            sections, includedScopes, excludedScopes, scrubbedFields,
            appVersionName, appVersionCode, createdAt, encryption
        )
    )

    /**
     * 构造信封的 **JSON 树**（不序列化）。
     *
     * ⚠️ 存在的理由是 [BackupArchive]：ZIP 容器要把信封当作 `manifest.json` 的根对象，
     * 而它需要**替换**各 scope 的 `data`（改成对 `scopes/<id>.json` 条目的引用）。
     * 走 `write()` 拿到文本再 parse 回来能做同一件事，但那是「先序列化再反序列化」
     * 的一趟无用功，且两份树之间多一次形状漂移的机会。
     *
     * [write] 就是「本函数 + [writeElementTree]」，两条路共用同一棵树 ——
     * 所以「ZIP 里的 manifest」与「纯 JSON 备份」在结构上**不可能不一致**。
     */
    fun buildRoot(
        sections: Map<String, ScopePayload>,
        includedScopes: List<String>,
        excludedScopes: List<String>,
        scrubbedFields: List<String>,
        appVersionName: String,
        appVersionCode: Int,
        createdAt: Long = System.currentTimeMillis(),
        encryption: EncryptionSection? = null
    ): JsonObject {
        val root = JsonObject()
        root.addProperty(KEY_SCHEMA, SCHEMA)
        root.addProperty(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
        root.addProperty(KEY_CREATED_AT, createdAt)

        root.add(KEY_APP, JsonObject().apply {
            addProperty(KEY_APP_VERSION_NAME, appVersionName)
            addProperty(KEY_APP_VERSION_CODE, appVersionCode)
        })

        root.add(KEY_SCOPES, JsonObject().apply {
            sections.forEach { (id, payload) ->
                add(id, JsonObject().apply {
                    addProperty(KEY_COUNT, payload.count)
                    add(KEY_DATA, payload.data)
                })
            }
        })

        // 加密段：**无条件 add**（无加密时写 JsonNull），保留键位使形状稳定，
        // 让「有无加密」在信封层面一目了然。
        //
        // ⚠️⚠️ **不能写成 `if (encryption != null) root.add(...)`** ——
        // 那会让「不加密」的备份里**没有 encryption 这个键**，
        // 于是「没有该键」与「该键为 null」两种形状并存，读端只能靠 has() 兜底。
        // 既有测试 `written envelope carries summary and null encryption slot`
        // 有两条断言直接锁 `root.has("encryption")`，改成条件式会让它们变红。
        root.add(KEY_ENCRYPTION, encryption?.toJson() ?: JsonNull.INSTANCE)

        root.add(KEY_SUMMARY, JsonObject().apply {
            add(KEY_INCLUDED_SCOPES, toArray(includedScopes))
            add(KEY_EXCLUDED_SCOPES, toArray(excludedScopes))
            add(KEY_SCRUBBED_FIELDS, toArray(scrubbedFields))
        })

        return root
    }

    /**
     * 把 [JsonElement] 树写成 JSON 文本 —— **不用 `gson.toJson(JsonElement)`**。
     *
     * ⚠️⚠️ **原因是实测出来的，不是洁癖**：Gson 的 `TypeAdapters.JSON_ELEMENT.write`
     * 对**值为 `JsonNull` 的键**调 `out.nullValue()` 时**不写延迟键名**，
     * 结果那个键**整条消失**；且 `serializeNulls = true` **也救不回来**
     * （实测：`gson.toJson(JsonObject{"enc" to JsonNull})` ⇒ `{}`；
     * 同一 `JsonWriter` 手工 `name("enc"); nullValue()` ⇒ `{"enc":null}`，
     * `gson.toJson(obj, writer)` 配 `serializeNulls=true` ⇒ 仍为 `{}`）。
     *
     * 这里受影响的**不止** `encryption` —— 任何 payload 里的显式 null
     * （如 `WorkflowFolder.parentId` 为 null）都会被吞掉。吞掉**多数情况**无害
     * （反序列化后同样是 null），但「写了却没写进去」是本仓库最忌讳的静默失效形态，
     * 且 `summary` / `encryption` 这种**结构键**一旦消失，读端只能靠 `has()` 兜底，
     * 容易在下一次改动里变成真缺陷。
     *
     * 只递归 `JsonElement`（信封根本就是手工搭出来的，且 payload 在进入本函数**之前**
     * 已由 `env.json.toJsonTree(...)` 转成元素）⇒ 这里不需要 Gson 的任何适配器。
     */
    fun writeElementTree(root: JsonElement): String {
        val out = StringWriter()
        JsonWriter(out).use { writer ->
            // ⚠️ 必须开：否则 `nullValue()` 同样会被吞（那是 Gson 与本函数都要的那一档）。
            writer.serializeNulls = true
            writeElement(writer, root)
        }
        return out.toString()
    }

    private fun writeElement(writer: JsonWriter, element: JsonElement) {
        when {
            element.isJsonNull -> writer.nullValue()
            element.isJsonPrimitive -> {
                val primitive = element.asJsonPrimitive
                when {
                    primitive.isBoolean -> writer.value(primitive.asBoolean)
                    primitive.isNumber -> writer.value(primitive.asNumber)
                    else -> writer.value(primitive.asString)
                }
            }
            element.isJsonArray -> {
                writer.beginArray()
                element.asJsonArray.forEach { writeElement(writer, it) }
                writer.endArray()
            }
            else -> {
                writer.beginObject()
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    writer.name(key)
                    writeElement(writer, value)
                }
                writer.endObject()
            }
        }
    }

    /**
     * 解析信封。
     *
     * ⚠️⚠️ **下面各分支的判定顺序是契约，不要调整** —— 尤其是
     * 「`TooNew` 必须在 `Modern` 之前」与「数组先于对象判 Legacy」两条。
     */
    fun read(json: Gson, text: String): ReadResult {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return ReadResult.Invalid("不是合法 JSON：${e.message}")
        }

        // 0. 根本身是数组 ⇒ 那是 legacy 的「裸工作流列表」形状（WorkflowJsonImportParser 认它）。
        //    必须在「不是对象 ⇒ Invalid」之前判，否则这种形状会被整份拒掉。
        if (root.isJsonArray) {
            return ReadResult.Legacy(JsonObject().apply { add("workflows", root) })
        }

        // 1. 不是 JSON 对象 —— 标量/字符串等，没有可解释的结构。
        if (!root.isJsonObject) {
            return ReadResult.Invalid("备份根节点既不是对象也不是数组")
        }

        val obj = root.asJsonObject

        // 2/3. 有 schema 键。
        val schemaElement = obj.get(KEY_SCHEMA)
        if (schemaElement != null && schemaElement.isJsonPrimitive) {
            val schema = schemaElement.asString
            if (schema != SCHEMA) {
                return ReadResult.Invalid("未知信封类型：$schema")
            }

            val versionElement = obj.get(KEY_SCHEMA_VERSION)
            val version = versionElement
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
            if (version == null || version <= 0) {
                return ReadResult.Invalid("schemaVersion 缺失或不是正整数")
            }
            if (version > SCHEMA_VERSION) {
                // ⚠️ 拒绝，**不半解析** —— 旧版 App 读新备份会把不认识的字段默默丢掉。
                return ReadResult.TooNew(version)
            }

            return ReadResult.Modern(
                headerOf(obj, version),
                readScopes(obj, json),
                // ⚠️ 形状不符 ⇒ null（当作「没有加密段」），**不抛** ——
                //    与 readScopes 对坏 scope 的处理同一纪律。真正的后果由
                //    BackupPipeline 承担：section == null 时含密文的 scope 会被判损坏。
                EncryptionSection.fromJson(obj.get(KEY_ENCRYPTION))
            )
        }

        // 4. 无 schema，但有 legacy 标志性顶层键 ⇒ 旧备份。
        if (obj.has("workflows") || obj.has("folders")) {
            return ReadResult.Legacy(obj)
        }

        // 5. 其余无法解释。
        return ReadResult.Invalid("不是 vFlow 备份：既无 schema 也无 workflows/folders")
    }

    private fun headerOf(obj: JsonObject, version: Int): Header {
        val app = obj.get(KEY_APP)?.takeIf { it.isJsonObject }?.asJsonObject
        return Header(
            schemaVersion = version,
            createdAt = obj.get(KEY_CREATED_AT)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asLong ?: 0L,
            appVersionName = app?.get(KEY_APP_VERSION_NAME)
                ?.takeIf { it.isJsonPrimitive }
                ?.asString,
            appVersionCode = app?.get(KEY_APP_VERSION_CODE)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
        )
    }

    /**
     * 读 `scopes`。
     *
     * ⚠️ **单个 scope 成员形状不对（缺 `count` / 缺 `data` / 类型不符）⇒ 只跳过它，
     * 不让整份失败** —— 一份备份里坏了一个范围，用户仍应能恢复其余范围。
     * 这与「schemaVersion 过高 ⇒ 整份拒绝」是两种不同的错误：后者是**语义不明的整份文件**，
     * 前者是**局部数据损坏**。
     *
     * ⚠️ 这里会把形状不对的项**记进 W 日志**。`read` 是纯函数、拿不到 `BackupEnvironment`，
     * 故日志由 `warningSink` 出口（生产侧可选接线）。
     */
    private fun readScopes(obj: JsonObject, json: Gson): Map<String, ScopePayload> {
        val scopesElement = obj.get(KEY_SCOPES)
        if (scopesElement == null || !scopesElement.isJsonObject) {
            return emptyMap()
        }

        val out = LinkedHashMap<String, ScopePayload>()
        scopesElement.asJsonObject.entrySet().forEach { (id, element) ->
            if (!element.isJsonObject) {
                warn("scope '$id' 不是对象，已跳过")
                return@forEach
            }
            val scopeObj = element.asJsonObject
            val countElement = scopeObj.get(KEY_COUNT)
            val dataElement = scopeObj.get(KEY_DATA)
            val count = countElement
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt
            if (count == null || dataElement == null || dataElement.isJsonNull) {
                warn("scope '$id' 缺 count 或 data，已跳过")
                return@forEach
            }
            out[id] = ScopePayload(count, dataElement)
        }
        return out
    }

    private fun toArray(values: List<String>): JsonArray =
        JsonArray().apply { values.forEach { add(it) } }

    /** 诊断出口。默认丢弃（本对象保持纯 JVM、无 App 侧依赖）。 */
    var warningSink: (String) -> Unit = {}

    private fun warn(message: String) {
        warningSink("BackupEnvelope: $message")
    }

    /** 供调用方把 `JsonElement` 安全转成数组（读取 scope data 时的常见需求）。 */
    fun asArrayOrNull(element: JsonElement?): JsonArray? =
        if (element != null && element.isJsonArray) element.asJsonArray else null

    /**
     * **只读**地取出信封 `summary.scrubbedFields`（被清空的敏感字段位置）。
     *
     * ⚠️⚠️ **为什么需要它**：`ReadResult.Modern` 不带 `summary`，而
     * 「这份备份在导出时把哪些凭据清空了」是**导入侧用户唯一能发现数据缺失的途径**。
     * 少了它，用户 REPLACE 导入一份不含密钥的备份后，只会看到
     * 「导入 12 · 跳过 0」，然后发现工作流里的 `api_key` 是空的 ——
     * 他会去查执行日志、查模块、查权限，唯独查不到「导出时就没带密钥」。
     *
     * ⚠️ **它是纯函数、不吃 `BackupEnvironment`、不产生任何副作用** ——
     * 故意**不**给 `ReadResult.Modern` 加字段：那会波及别处对 `Modern` 的构造点。
     * 本函数签名与 `read` 完全解耦，`scrubbedFields` 是「导出时的既成事实」，
     * 与本次导入走哪个分支无关。
     *
     * @return 字段位置列表（如 `s1.api_key`）。整份不是合法 JSON / 不是对象 /
     *   没有 `summary` / 形状不符 ⇒ **空列表**（不抛 —— 调用方在 UI 路径上，
     *   抛异常会变成「读文件失败」这种误导性的提示）。
     */
    fun scrubbedFieldsOf(text: String): List<String> {
        val root = try {
            JsonParser.parseString(text)
        } catch (e: Exception) {
            return emptyList()
        }
        if (!root.isJsonObject) return emptyList()
        return scrubbedFieldsOf(root.asJsonObject)
    }

    /**
     * [scrubbedFieldsOf] 的已解析重载 —— 供已经拿到 `JsonObject` 的调用方复用，
     * 避免为同一个 root 解析两次。
     */
    fun scrubbedFieldsOf(root: JsonObject): List<String> {
        val summary = root.get(KEY_SUMMARY)?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return emptyList()
        val array = summary.get(KEY_SCRUBBED_FIELDS)?.takeIf { it.isJsonArray }?.asJsonArray
            ?: return emptyList()
        return array.mapNotNull { it.takeIf { e -> e.isJsonPrimitive && e.asJsonPrimitive.isString }?.asString }
    }
}

/**
 * 信封的 `encryption` 段。**null 表示这份备份没有加密任何东西。**
 *
 * 只有存在 [ScopeGroup.SECRET] 的数据被写进去时才非 null ——
 * 即「用户勾了包含密钥**且**给了口令」。因此它能被当作
 * 「这份备份要不要口令」的唯一判据。
 *
 * ## 参数为什么必须随文件走
 *
 * `kdf` / `iterations` / `salt` 全是解密的**必需输入**（`algorithm` 目前只作审计用）。
 * 让它们随文件走，才能做到「同一口令解开任意多份备份」而 App 侧不持久化任何东西
 * （方案 §6.6）。把 iterations 或 salt 固定在代码里（或存进 SharedPreferences）
 * 会让「换了台设备就解不开」或「改一次默认值就让旧备份全部失效」。
 */
data class EncryptionSection(
    /** 例 `"AES-256-GCM"`。目前只作审计/展示用，解密路径写死用 GCM。 */
    val algorithm: String,
    /** 例 `"PBKDF2WithHmacSHA256"`。同上。 */
    val kdf: String,
    /** PBKDF2 迭代次数 —— **解密时必须用它，不能用默认值**。 */
    val iterations: Int,
    /** base64(salt)。每次导出随机、不持久化。 */
    val salt: String,
    /** base64(iv‖ct‖tag)：用派生密钥加密的 [BackupCrypto.VERIFIER_PLAINTEXT]。 */
    val verifier: String,
    /**
     * ⚠️ **verifier 密文的 SHA-256（base64）**，**不参与密钥派生**。
     *
     * 存在的理由（本方案对设计正文的一处补充，见方案 §6.3）：
     * 只靠 GCM tag 无法区分「口令错」与「verifier 本身被篡改」——
     * 两者都表现为 verifier 解不开。加了它之后，判定链条是确定的：
     *
     * | `verifierHash` | verifier 能否解开 | 判定 |
     * |---|---|---|
     * | 符 | 否 | 口令错 |
     * | **不符** | ——（**不派生密钥**） | 损坏 |
     *
     * 不带它的话，用户会对着一个**已经损坏**的备份反复重输一个**本来就对**的口令，
     * 而这直接违反「错口令 vs 数据损坏必须可区分」这条硬要求。
     *
     * 代价只是一个字段，不引入任何新算法。
     */
    val verifierHash: String
) {

    fun toJson(): JsonObject = JsonObject().apply {
        addProperty("algorithm", algorithm)
        addProperty("kdf", kdf)
        addProperty("iterations", iterations)
        addProperty("salt", salt)
        addProperty("verifier", verifier)
        addProperty("verifierHash", verifierHash)
    }

    companion object {
        /**
         * 从 `encryption` 段解析。
         *
         * ⚠️ **形状不对 ⇒ null（当作「没有加密段」），不抛** ——
         * 与 `BackupEnvelope.readScopes` 对坏 scope 的处理同一纪律：
         * 局部数据损坏不该让整份文件直接崩掉。真正的后果由调用方承担：
         * `section == null` 时含密文的 scope 会被当作**没有加密**处理，
         * `BackupPipeline` 会判它损坏。
         *
         * @param element 为 null / JsonNull / 形状不符 ⇒ null。
         */
        fun fromJson(element: JsonElement?): EncryptionSection? {
            val obj = element?.takeIf { it.isJsonObject }?.asJsonObject ?: return null

            fun str(key: String): String? = obj.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
                ?.asString
                ?.takeIf { it.isNotEmpty() }

            fun int(key: String): Int? = obj.get(key)
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asInt

            return EncryptionSection(
                algorithm = str("algorithm") ?: return null,
                kdf = str("kdf") ?: return null,
                iterations = int("iterations") ?: return null,
                salt = str("salt") ?: return null,
                verifier = str("verifier") ?: return null,
                verifierHash = str("verifierHash") ?: return null
            )
        }
    }
}
