// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/GlobalVariableScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.BackupEnvelope
import com.chaomixian.vflow.core.backup.BackupEnvironment
import com.chaomixian.vflow.core.backup.BackupScope
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopeImportResult
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.google.gson.JsonObject
import com.google.gson.annotations.SerializedName

/**
 * 备份/恢复**全局变量**。
 *
 * ## ⚠️ 备份格式 ≠ 存储格式（本文件最容易想当然的地方）
 *
 * | | 形状 |
 * |---|---|
 * | **存储格式**（`global_variable_store` / `variables_json`） | `[{"name":"k","type":"string","value":"v"}]` —— `type` 是 `"string"`/`"number"`/`"boolean"` 三个**裸标签** |
 * | **备份格式**（`scopes.global_variables.data`） | **同形状**，但读写走本文件声明的 [BackupGlobalVariable] |
 *
 * - **为什么不复用 `GlobalVariableStore.StoredVariable`**：它是 `private`，而且它是
 *   **存储格式**不是**备份格式** —— 将来存储层换形状不该连带改备份格式。
 * - **为什么不改成「用 `env.json` 序列化 `Map<String, VObject>`」**：那条路产出的形状是
 *   `{"k":{"type":"vflow.type.string","value":"v"}}`，与存储格式不同，且反序列化要碰
 *   `TypeToken<Map<String, VObject>>` 的类型擦除 + typeHierarchyAdapter 组合（能否正确解回需实测）。
 *   本 scope 的唯一要求是**往返无损**，没有理由引入这个风险。
 * - **三种标签为何足够（无损性论证）**：`GlobalVariableStore.serialize` 的
 *   `else -> StoredVariable(name, "string", value.asString())` 表明存储层**本来就把**
 *   非 string/number/boolean 的 `VObject` **压成字符串**，所以 `getAll()` 只会产出
 *   `VString` / `VNumber` / `VBoolean` 三种 ⇒ 逐个往返无损。
 *
 * ## 合并语义为什么只在这里
 *
 * 本 scope **没有**对应的 `mergeGlobalVariables` 环境方法（见 `BackupEnvironment` 的 KDoc）：
 * 合并不是写路径，只是「本地 ∪ 入参、入参优先」三行，直接在 [import] 里做。
 */

/**
 * 备份格式里的单条全局变量。**形状与存储格式一致**，但不是存储格式本身。
 *
 * ## ⚠️ 为什么每个字段都要 `@SerializedName`
 *
 * 本类是 `core/backup/` 里**唯一**由 Gson **反射**序列化、又**不在任何
 * `-keep` 范围**的持久化模型（`core.backup.scopes` 未列入 `proguard-rules.pro`）。
 * 其余同类都安全：`Workflow` / `WorkflowFolder` 在
 * `-keep class com.chaomixian.vflow.core.workflow.model.**` 里；
 * `EncryptionSection` 有手写 `toJson()` / `fromJson()`，不走反射。
 *
 * **R8 会把字段名改短**（`name`/`type`/`value` → `a`/`b`/`c`），而全仓
 * **没有 `-applymapping`、也没有混淆字典** ⇒ 字段短名只对**当前这一份二进制**
 * 稳定。改代码 / 合上游 / 换 R8 版本后可能变成 `d`/`e`/`f`。
 *
 * ⚠️ **这不是「同一次构建内读写失败」** —— Gson 写与读都用运行时字段名，对称，
 * 同一份二进制里往返完全正常。真问题是**跨版本**：备份文件的寿命天生比一次构建长
 * （用户升级 App 后导入旧备份），那时旧文件里的 `a`/`b`/`c` 就对不上新二进制的
 * `d`/`e`/`f` 了，而 `parse()` 是**手工按键读**的（`obj.get("name")`）⇒
 * 返回 null ⇒ 那条变量被**静默跳过**，用户看到的是「恢复成功但少了几条变量」。
 *
 * `@SerializedName` 把格式的 key **固化在源码里**，从此与 R8 怎么改名无关。
 *
 * ⚠️ **不要删这些注解，也不要改成依赖字段名。**
 * ⚠️ 注解的值刻意与字段名**逐字相同**（`name`/`type`/`value`）⇒
 * 本加固**不改变现有备份格式**，旧备份照常可读。
 */
data class BackupGlobalVariable(
    @SerializedName("name") val name: String,
    @SerializedName("type") val type: String,
    @SerializedName("value") val value: Any?,
)

class GlobalVariableScope : BackupScope {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.USER_CONTENT
    override val sensitive: Boolean = false
    override val defaultIncluded: Boolean = true

    /** 与 folders 同组、无依赖：5 只是让它排在 folders 之后、workflows 之前（可读性）。 */
    override val importOrder: Int = 5

    override val dependsOn: List<String> = emptyList()

    override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload {
        val variables = env.getGlobalVariables()
        val stored = variables.entries
            .sortedBy { it.key } // 稳定的键顺序，使往返可逐字断言
            .map { (name, value) -> serialize(name, value) }
        // ⚠️ 空集合也返回 count=0 的 payload，绝不返回 null。
        return ScopePayload.of(stored.map { env.json.toJsonTree(it) })
    }

    override fun import(
        env: BackupEnvironment,
        payload: ScopePayload?,
        mode: ImportMode
    ): ScopeImportResult {
        if (payload == null) {
            return ScopeImportResult(id, ImportStatus.SKIPPED_NOT_SELECTED)
        }

        val elements = BackupEnvelope.asArrayOrNull(payload.data)
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "global_variables 的 data 不是 JSON 数组"
            )

        var skipped = 0
        val incoming = LinkedHashMap<String, VObject>()
        for (element in elements) {
            val entry = parse(element)
            if (entry == null) {
                skipped++
                continue
            }
            incoming[entry.first] = entry.second
        }

        val toWrite = when (mode) {
            // ⚠️ 入参优先：备份里有的键覆盖本地；本地独有的键保留。
            ImportMode.MERGE -> LinkedHashMap(env.getGlobalVariables()).apply { putAll(incoming) }
            // ⚠️ 覆盖式：本地独有的键**必须消失**（这是 REPLACE 与 MERGE 的定义性差别）。
            ImportMode.REPLACE -> incoming
        }

        env.replaceGlobalVariables(toWrite)

        return ScopeImportResult(
            scopeId = id,
            status = ImportStatus.IMPORTED,
            imported = toWrite.size,
            skipped = skipped
        )
    }

    companion object {
        const val ID = "global_variables"

        /** 存储层认识的三个裸标签（见 `GlobalVariableStore.serialize`）。 */
        const val TYPE_STRING = "string"
        const val TYPE_NUMBER = "number"
        const val TYPE_BOOLEAN = "boolean"

        fun serialize(name: String, value: VObject): BackupGlobalVariable = when (value) {
            is VString -> BackupGlobalVariable(name, TYPE_STRING, value.raw)
            is VNumber -> BackupGlobalVariable(name, TYPE_NUMBER, value.raw.toString())
            is VBoolean -> BackupGlobalVariable(name, TYPE_BOOLEAN, value.raw)
            // 与存储层一致：其余类型压成字符串（这样导出的东西一定能原样写回）。
            else -> BackupGlobalVariable(name, TYPE_STRING, value.asString())
        }

        /**
         * 反序列化，与 `GlobalVariableStore.deserialize` **逐分支对齐**。
         *
         * 返回 null 表示这条记录无法解释（缺 name 或缺 type），调用方跳过并计数。
         */
        fun parse(element: com.google.gson.JsonElement?): Pair<String, VObject>? {
            if (element == null || !element.isJsonObject) return null
            val obj: JsonObject = element.asJsonObject
            val name = obj.stringOrNull("name")?.takeIf { it.isNotBlank() } ?: return null
            val type = obj.stringOrNull("type") ?: return null
            val raw = obj.get("value")

            val value: VObject = when (type) {
                TYPE_STRING -> VString(raw?.let { if (it.isJsonNull) null else it.asString } ?: "")
                TYPE_NUMBER -> {
                    val asNumber = raw?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asDouble
                    val asText = raw?.takeIf { !it.isJsonNull }?.asString?.toDoubleOrNull()
                    VNumber(asNumber ?: asText ?: 0.0)
                }
                TYPE_BOOLEAN -> {
                    val asBool = raw?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
                    VBoolean(asBool ?: (raw?.takeIf { !it.isJsonNull }?.asString?.toBoolean() ?: false))
                }
                else -> VString(raw?.let { if (it.isJsonNull) null else it.asString } ?: "")
            }
            return name to value
        }
    }
}

private fun JsonObject.stringOrNull(name: String): String? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
    return element.asString
}
