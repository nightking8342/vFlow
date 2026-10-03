// 文件: main/java/com/chaomixian/vflow/core/backup/BackupPrefs.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * 通用 `SharedPreferences` 接缝。
 *
 * ## 与 [SecretStore] 的分工
 *
 * | | [SecretStore] | 本接口 |
 * |---|---|---|
 * | 面 | 只有 `getString` / `putString` | 带类型的读写删 |
 * | 服务的 scope | `secrets` | `settings` / `chat` / `modules` / `tiles` |
 *
 * ⚠️ **刻意不复用 [SecretStore]**：`SharedPreferences` 有
 * `Boolean` / `Int` / `Long` / `Float` / `Set<String>` 五种非字符串类型，
 * 只走字符串通道会**丢类型** —— 恢复后 `getBoolean(键, false)` 读到一个
 * `String` 会抛 `ClassCastException`，而那是在**恢复成功之后**才发生的
 * （导出侧一切正常）。
 *
 * ## 为什么没有 `clear(name)`
 *
 * REPLACE 绝不能清空整个 prefs 文件：`module_config_prefs` 与 `vFlowPrefs`
 * 都是**开放键集**（模块、主界面、Core 管理、无障碍守护都在往里写），
 * 清空会误删用户其它设置，更会误删 `secrets` scope 管的凭证键。
 * 只提供 [removeAll] 是**结构上**堵死这条错误路径（风险 R3）。
 */
interface BackupPrefs {
    /**
     * 读出该 prefs 文件的全部键值。
     *
     * 值的实际类型是 `String` / `Int` / `Long` / `Float` / `Boolean` / `Set<String>`。
     */
    fun readAll(prefsName: String): Map<String, Any?>

    /** upsert：只覆盖入参里出现的键，其余本地键保留。 */
    fun putAll(prefsName: String, values: Map<String, Any?>)

    /** 精确删除指定的键（REPLACE 用：只删本 scope 白名单内、且备份里没有的键）。 */
    fun removeAll(prefsName: String, keys: Set<String>)
}

/**
 * 一个 prefs 文件的备份范围声明。
 *
 * @param prefsName   prefs 文件名（`getSharedPreferences(name)` 的那个 name）
 * @param exactKeys   精确收的键
 * @param keyPrefixes 按前缀收的键（用于键名动态的场景，如 `provider_enabled_<包名>`）
 *
 * ⚠️ 前缀只允许出现在**人工判定过、键空间有界**的地方；不确定的一律不收
 * （前缀是最容易「不知不觉多收一堆键」的形态）。
 */
data class BackupPrefsSpec(
    val prefsName: String,
    val exactKeys: Set<String>,
    val keyPrefixes: Set<String> = emptySet()
) {
    /** 本地某个键是否落在本 spec 的范围内。 */
    fun matches(key: String): Boolean =
        key in exactKeys || keyPrefixes.any { key.startsWith(it) }
}

/**
 * 由 prefs 支撑的 scope 都实现它。
 *
 * 存在的唯一理由是让**泄漏断言**能读到白名单：`BackupPrefsLeakTest` 遍历全部 scope，
 * 对每个 `sensitive == false` 的 [PrefsBackedScope] 断言其 spec 里不含
 * `vflow_api_tokens`、也不含任何 `SecretsScope.SECRET_SLOTS` 里的 `(prefs, key)`、
 * 也不含 `webdav_config_prefs`。没有这个接口，那条断言写不出来。
 */
interface PrefsBackedScope {
    val specs: List<BackupPrefsSpec>
}

/**
 * 由 prefs 支撑的 scope 的**共享实现** —— 导出形状、导入语义、REPLACE 的白名单过滤。
 *
 * ## 为什么抽成基类而不是四个 scope 各写一遍
 *
 * 四个 scope 的 export/import **逐字相同**，差别只在 [specs] 与（tiles 独有的）
 * [mergeValue]。各写一遍的话，「REPLACE 不能清空整个 prefs 文件」这条硬约束
 * 就要在四处分别落实 —— 漏一处就是静默误删用户设置，而且**只在那一个 scope 上发作**。
 * 收敛到一处后，`PrefsScopeContractTest` 的一条用例就能锁住全部四个。
 *
 * ## 统一的 data 形状
 *
 * ```json
 * [ { "prefs": "vFlowPrefs", "items": { "key": {"t":"b","v":true}, ... } } ]
 * ```
 *
 * ⚠️ 一个 prefs 文件若**一个匹配键都没有**，则该文件**不进数组**
 * ⇒ 空数据集 = `ScopePayload(0, JsonArray())`（绝不是 null）。
 */
abstract class AbstractPrefsScope : BackupScope, PrefsBackedScope {

    /** 日志 TAG 与 scopeId 分开：前者给排障，后者是落进备份 JSON 的稳定 id。 */
    protected abstract val scopeTag: String

    /**
     * MERGE 模式下、写入**之前**对单个键的合并钩子。
     *
     * 默认原样返回 [incoming]（即「备份优先、整键覆盖」）。
     * [TileScope] 覆写它 —— `tile_list` 是**一个键装一个数组**，整键覆盖会让
     * 本地独有的磁贴消失，那不是 MERGE 的含义。
     */
    protected open fun mergeValue(
        env: BackupEnvironment,
        prefsName: String,
        key: String,
        incoming: Any?,
        local: Any?
    ): Any? = incoming

    final override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload? {
        // null ⇒ 本环境不支持（与 SecretsScope 同款）。**不是**「本次没勾选」——
        // 那种情形由调用方根本不调用 export 来表达。
        val prefs = env.prefs ?: return null

        val elements = mutableListOf<JsonElement>()
        for (spec in specs) {
            val all = prefs.readAll(spec.prefsName)
            val matching = all.filterKeys { spec.matches(it) }
            // 该文件一个匹配键都没有 ⇒ **不进数组**（空数据集 = count 0 的 payload）。
            if (matching.isEmpty()) continue

            val items = PrefsValueCodec.encodeAll(matching)
            val unsupported = matching.keys.filter { items.get(it) == null }
            if (unsupported.isNotEmpty()) {
                // 不支持的运行时类型 ⇒ 丢弃该键**并记 W**，不静默。
                env.log(
                    LogLevel.W, scopeTag,
                    "prefs '${spec.prefsName}' 有 ${unsupported.size} 个键的类型不支持，已丢弃：$unsupported"
                )
            }
            if (items.size() == 0) continue

            elements += JsonObject().apply {
                addProperty(KEY_PREFS, spec.prefsName)
                add(KEY_ITEMS, items)
            }
        }
        return ScopePayload.of(elements)
    }

    final override fun import(
        env: BackupEnvironment,
        payload: ScopePayload?,
        mode: ImportMode
    ): ScopeImportResult {
        if (payload == null) {
            return ScopeImportResult(id, ImportStatus.SKIPPED_NOT_SELECTED)
        }

        val prefs = env.prefs
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "本环境不支持读写 prefs"
            )

        val array = payload.data.takeIf { it.isJsonArray }?.asJsonArray
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "$id 的 data 不是 JSON 数组"
            )

        val specByName = specs.associateBy { it.prefsName }
        var imported = 0
        var skipped = 0

        for (element in array) {
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject
            if (obj == null) {
                skipped++
                continue
            }
            val prefsName = obj.get(KEY_PREFS)?.takeIf { it.isJsonPrimitive }?.asString
            if (prefsName == null) {
                skipped++
                continue
            }
            val spec = specByName[prefsName]
            if (spec == null) {
                // 备份里有本机不认识的 prefs（更新的版本加的 / 手工改的）⇒ 跳过该文件。
                env.log(LogLevel.W, scopeTag, "备份里有本 scope 不认识的 prefs '$prefsName'，已跳过")
                skipped++
                continue
            }

            val decoded = PrefsValueCodec.decodeAll(obj.get(KEY_ITEMS))
            val localAll = prefs.readAll(prefsName)

            // ⚠️ 只接受白名单内的键。备份是**外部输入**（可以是从别处拷来的文件、
            //    也可以被手工编辑过），不过滤的话它能把任意键写进任意 prefs
            //    —— 包括 secrets 管的凭证键。
            val accepted = LinkedHashMap<String, Any?>()
            for ((key, value) in decoded) {
                if (!spec.matches(key)) {
                    skipped++
                    continue
                }
                accepted[key] = if (mode == ImportMode.MERGE) {
                    mergeValue(env, prefsName, key, value, localAll[key])
                } else {
                    value
                }
            }

            if (mode == ImportMode.REPLACE) {
                // ⚠️⚠️ **只删「本 spec 白名单内、且备份里没有」的键**。
                //     两个条件缺一不可：
                //       缺 ①（白名单）⇒ 会删掉别的 scope 的键（最坏是 secrets 的凭证键）
                //                       以及用户从没备份过的设置；
                //       缺 ②（备份里没有）⇒ MERGE 与 REPLACE 在「本地多出的键」上
                //                            就没差别了，REPLACE 失去意义。
                //     ⚠️ 这里**绝不** `clear()` 整个 prefs 文件 —— `module_config_prefs`
                //        与 `vFlowPrefs` 都是开放键集（模块、主界面、Core 管理、
                //        无障碍守护都在往里写）。
                val toRemove = localAll.keys
                    .filter { spec.matches(it) }
                    .filter { it !in accepted.keys }
                    .toSet()
                if (toRemove.isNotEmpty()) prefs.removeAll(prefsName, toRemove)
            }

            if (accepted.isNotEmpty()) prefs.putAll(prefsName, accepted)
            imported += accepted.size
        }

        return ScopeImportResult(
            scopeId = id,
            status = ImportStatus.IMPORTED,
            imported = imported,
            skipped = skipped
        )
    }

    internal companion object {
        /** data 数组里每个元素的键名（`{ "prefs": …, "items": … }`）。 */
        const val KEY_PREFS = "prefs"
        const val KEY_ITEMS = "items"
    }
}

/**
 * prefs 值的 JSON 编解码。
 *
 * ## ⚠️⚠️ 必须带类型标签
 *
 * JSON 只有一种数字类型，Gson 反序列化一律给 `Double`；直接存裸值会让
 * `getInt` / `getFloat` 在恢复后拿到错误类型 ⇒ 运行时 `ClassCastException`。
 * 数字统一按**字符串**编码，顺带避免 double 往返的精度漂移
 * （`1.5f` 经 `Double` 往返会变成 `1.5000001`）。
 *
 * ## 表
 *
 * | 标签 | Kotlin 类型 | `v` 载荷 |
 * |---|---|---|
 * | `s`  | `String`       | JSON string |
 * | `b`  | `Boolean`      | JSON bool |
 * | `i`  | `Int`          | JSON **string**（`"123"`） |
 * | `l`  | `Long`         | JSON **string** |
 * | `f`  | `Float`        | JSON **string** |
 * | `ss` | `Set<String>`  | JSON 字符串数组 |
 */
object PrefsValueCodec {

    const val TAG_STRING = "s"
    const val TAG_BOOLEAN = "b"
    const val TAG_INT = "i"
    const val TAG_LONG = "l"
    const val TAG_FLOAT = "f"
    const val TAG_STRING_SET = "ss"

    private const val KEY_TYPE = "t"
    private const val KEY_VALUE = "v"

    /**
     * 把值编成 `{"t": <标签>, "v": <载荷>}`。
     *
     * @return 不支持的运行时类型 ⇒ **null**（该键被调用方丢弃并记 W），不抛 ——
     *   备份是用户数据路径，一个陌生类型不该让整次导出失败。
     */
    fun encode(value: Any?): JsonObject? {
        val node = JsonObject()
        when (value) {
            is String -> {
                node.addProperty(KEY_TYPE, TAG_STRING)
                node.addProperty(KEY_VALUE, value)
            }
            is Boolean -> {
                node.addProperty(KEY_TYPE, TAG_BOOLEAN)
                node.addProperty(KEY_VALUE, value)
            }
            is Int -> {
                node.addProperty(KEY_TYPE, TAG_INT)
                node.addProperty(KEY_VALUE, value.toString())
            }
            is Long -> {
                node.addProperty(KEY_TYPE, TAG_LONG)
                node.addProperty(KEY_VALUE, value.toString())
            }
            is Float -> {
                node.addProperty(KEY_TYPE, TAG_FLOAT)
                node.addProperty(KEY_VALUE, value.toString())
            }
            is Set<*> -> {
                // ⚠️ 只认 `Set<String>`。SharedPreferences 的 StringSet 恒是该类型；
                //    别的 Set 只可能来自测试的构造错误，静默转成字符串更危险。
                if (value.any { it !is String }) return null
                node.addProperty(KEY_TYPE, TAG_STRING_SET)
                val array = JsonArray()
                @Suppress("UNCHECKED_CAST")
                (value as Set<String>).forEach { array.add(it) }
                node.add(KEY_VALUE, array)
            }
            else -> return null
        }
        return node
    }

    /**
     * 解码。
     *
     * @return 标签未知 / 载荷类型不符 ⇒ **null**（调用方跳过该键，**不抛**）。
     *   这是前向兼容契约：更新的版本写进来的新类型标签，旧版本应当安静跳过。
     */
    fun decode(node: JsonElement?): Any? {
        val obj = node?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val type = obj.get(KEY_TYPE)?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        val value = obj.get(KEY_VALUE) ?: return null

        return when (type) {
            TAG_STRING -> value.takeIf { it.isJsonPrimitive }?.asString
            TAG_BOOLEAN -> value.takeIf { it.isJsonPrimitive }?.asBoolean
            TAG_INT -> value.asStringOrNull()?.toIntOrNull()
            TAG_LONG -> value.asStringOrNull()?.toLongOrNull()
            TAG_FLOAT -> value.asStringOrNull()?.toFloatOrNull()
            TAG_STRING_SET -> value.takeIf { it.isJsonArray }?.asJsonArray
                ?.mapNotNull { element ->
                    element.takeIf { it.isJsonPrimitive }?.asString
                }
                ?.toSet()
            else -> null
        }
    }

    /**
     * 整表编码。键按**字典序**排列 —— 让往返结果可以逐字断言
     * （`SharedPreferences.all` 的迭代序在不同实现上不保证稳定）。
     */
    fun encodeAll(values: Map<String, Any?>): JsonObject {
        val out = JsonObject()
        values.keys.sorted().forEach { key ->
            val encoded = encode(values[key]) ?: return@forEach
            out.add(key, encoded)
        }
        return out
    }

    /** 整表解码；单个键解不出 ⇒ 跳过它，其余照常。 */
    fun decodeAll(node: JsonElement?): Map<String, Any?> {
        val obj = node?.takeIf { it.isJsonObject }?.asJsonObject ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>()
        obj.entrySet().forEach { (key, element) ->
            val decoded = decode(element) ?: return@forEach
            out[key] = decoded
        }
        return out
    }

    /**
     * 取一个「应当装字符串」的载荷。
     *
     * ⚠️ **必须显式判 `isString`** —— `JsonPrimitive.asString` 对 bool/number
     * 也**不抛**（返回 `toString()`），于是 `{"t":"i","v":123}`（非法形状）
     * 会被安静地解成 int 123，掩盖了「写侧没按规约编码」这件事。
     */
    private fun JsonElement.asStringOrNull(): String? =
        (this as? JsonPrimitive)?.takeIf { it.isString }?.asString
}
