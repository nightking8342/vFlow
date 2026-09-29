package com.chaomixian.vflow.xposed.wire

import org.json.JSONObject

/**
 * `activity_changed` 事件**载荷的编解码**（hook 层编码 / App 侧解码）。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.1 / §4.3。
 *
 * ## ⚠️ 与 [EventEnvelopeCodec] 同一条纯度约束
 *
 * 跑在 system_server 里，只允许 `org.json` / `java.*` / `kotlin.*`。
 * 由 `WireLayerPurityTest` 源码扫描锁住。
 *
 * ## ⚠️ 为什么 extras 要带类型信息
 *
 * `dumpsys` 那条路踩过的坑：**它把 extras 的值统一按文本打印，类型就丢了** ——
 * 米家快捷方式「无账号权限」的真因就是 String 被猜成了 Long
 * （见 `docs/fork/surveys/shortcut-system-overview.md`）。
 *
 * 本层**不需要猜**：Bundle 取出来的是 `Object`，`javaClass` 就是真类型。
 * 所以这里按真实类型编码 —— `"1"`（String）与 `1`（Int）在 JSON 里是不同的值。
 *
 * ## ⚠️ 截断必须产出**合法 JSON**
 *
 * Binder 事务上限约 1 MB，超了抛 `TransactionTooLargeException`，
 * 而 **`oneway` 调用下这个异常是被吞掉的**（App 侧永远收不到、也不知道为什么）。
 * 所以必须在**编码时**就把体积控住。
 *
 * 关键是：不能「先拼好再按长度砍」——砍一半的 JSON 无法解析，下游只会得到一个
 * 静默的解析失败。本层的做法是**逐键累加、超限就停**，并置 `truncated = true`。
 */
object ActivityPayload {

    /** topic 常量。加新触发器时这里是唯一要改的地方（§3.4.3）。 */
    const val TOPIC = "hook.activity.changed"

    private const val KEY_PACKAGE = "package_name"
    private const val KEY_CLASS = "class_name"
    private const val KEY_COMPONENT = "component"
    private const val KEY_INTENT_URI = "intent_uri"
    private const val KEY_EXTRAS = "extras_json"
    private const val KEY_TRUNCATED = "truncated"

    /**
     * `intent_uri` 上限（**字节**）。
     *
     * 16 KiB 是个保守值：一条带长 `dat` URI 的 VIEW intent 通常几 KB，
     * 留够余量又不至于让它单独顶破事务上限。
     *
     * ⚠️⚠️ **单位是字节（UTF-8），不是字符** —— 这条差别曾是一个真实的静默缺陷，
     * 详见 [MAX_EXTRAS_JSON_BYTES] 的注释。
     */
    const val MAX_INTENT_URI_BYTES = 16 * 1024

    /**
     * `extras_json` 上限（**字节**）。
     *
     * ⚠️ 它**不是**「拼好之后砍到这里」——是累加时的预算上限（见 [encodeExtras]）。
     *
     * ## ⚠️⚠️ 为什么单位必须是字节（这是一处已修的既有缺陷）
     *
     * 原实现按**字符**计（`MAX_INTENT_URI_CHARS = 64K` / `MAX_EXTRAS_JSON_CHARS = 128K`，
     * 判据是 `String.length`），而 §3.6 的契约按**字节**立。最坏情况：
     *
     * | 载荷构成 | 字节数 | 与 oneway 半缓冲（≈508 KiB）的关系 |
     * |---|---|---|
     * | 全 ASCII（1 字节/字符） | ≈ 188 KiB | 勉强够，但**与所有其他 oneway 事务共享** |
     * | **全 CJK（3 字节/字符）** | ⚠️ **≈ 576 KiB** | ❌ **超限 ⇒ 整条事件静默丢弃** |
     * | 叠加 emoji（4 字节/字符） | 更糟 | ❌ |
     *
     * 表现是「**打开某些 App 不触发、换一个就正常**」——
     * 正是最难被当成 bug 上报的那类（§6.5）。
     *
     * ⇒ 两项合计降到 **64 KiB**（16 + 48），留足余量：
     * 即便外层信封的 JSON 转义把它放大到 1.5 倍（≈96 KiB），
     * 距半缓冲仍有 5 倍以上空间。
     *
     * ⚠️ 估算仍然用**精确**的 `toByteArray(Charsets.UTF_8).size` 而非 ×3 ——
     * 见 [com.chaomixian.vflow.xposed.wire.ResultBudget.byteSizeOf] 的说明。
     */
    const val MAX_EXTRAS_JSON_BYTES = 48 * 1024

    /**
     * 编码。
     *
     * @param extras 已从 Bundle 取出的键值对。**值保留原始类型**（不做 toString）——
     *   类型信息在这里还不能丢，由 [encodeExtras] 按真实类型写进 JSON。
     * @return JSON 字符串；**任何异常都吞掉并降级为「只有包名/类名」的载荷**，
     *   绝不让编码失败变成「一条事件都没上报」。
     */
    fun encode(
        packageName: String,
        className: String,
        intentUri: String?,
        extras: Map<String, Any?>,
    ): String {
        var truncated = false

        // ① intent_uri：只有一个值，超限直接截断（它是纯字符串，截断了也只是不能
        //    原样喂给 `am start`，其余字段不受影响）
        //
        // ⚠️ 按**字节**判、按**字节**截（不是 `.length`）——
        // 若只改判据不改截断，CJK 的 URI 仍会超出预算。
        val safeUri = intentUri ?: ""
        val finalUri = if (ResultBudget.byteSizeOf(safeUri) > MAX_INTENT_URI_BYTES) {
            truncated = true
            truncateToBytes(safeUri, MAX_INTENT_URI_BYTES)
        } else {
            safeUri
        }

        // ② extras：逐键累加，超预算就停
        val extrasResult = encodeExtras(extras)
        if (extrasResult.truncated) truncated = true

        val component = if (className.isNotBlank()) "$packageName/$className" else packageName

        return try {
            JSONObject()
                .put(KEY_PACKAGE, packageName)
                .put(KEY_CLASS, className)
                .put(KEY_COMPONENT, component)
                .put(KEY_INTENT_URI, finalUri)
                .put(KEY_EXTRAS, extrasResult.json)
                .put(KEY_TRUNCATED, truncated)
                .toString()
        } catch (_: Throwable) {
            // 兜底：连 JSONObject 都构造失败时，至少给出可识别的骨架。
            // 手工拼接（不依赖 JSONObject），键名与上面保持一致
            """{"$KEY_PACKAGE":"${escape(packageName)}","$KEY_CLASS":"${escape(className)}",""" +
                """"$KEY_COMPONENT":"${escape(component)}","$KEY_INTENT_URI":"",""" +
                """"$KEY_EXTRAS":"{}","$KEY_TRUNCATED":true}"""
        }
    }

    /**
     * 解码。**App 侧用**。
     *
     * @return 事件；JSON 坏掉或没有包名时返回 null（**不抛** —— 调用方在 binder 线程上）
     */
    fun decode(payloadJson: String): ActivityEvent? {
        val obj = try {
            JSONObject(payloadJson)
        } catch (_: Exception) {
            return null
        }
        val pkg = obj.optString(KEY_PACKAGE)
        if (pkg.isBlank()) return null

        return ActivityEvent(
            packageName = pkg,
            className = obj.optString(KEY_CLASS),
            component = obj.optString(KEY_COMPONENT),
            intentUri = obj.optString(KEY_INTENT_URI),
            extrasJson = obj.optString(KEY_EXTRAS),
            truncated = obj.optBoolean(KEY_TRUNCATED, false),
        )
    }

    /**
     * 把 extras 编码成 JSON —— **逐键累加，超预算即停**。
     *
     * 为什么不是「拼好再截」：截断后的 JSON 不合法，下游 `JSONObject(...)` 会抛，
     * 于是**整条 extras 都拿不到**，而不是「少几个键」。逐键累加能保证
     * 拿到的部分始终是可解析的。
     *
     * @return 编码结果 + 是否发生截断
     */
    internal fun encodeExtras(extras: Map<String, Any?>): ExtrasResult {
        val obj = JSONObject()
        var used = 2 // "{}"
        var truncated = false
        var added = 0

        for ((key, value) in extras) {
            // 先编码单个键，再判断加上它会不会超预算 ——
            // 这样「超了就停」不会留下半截 JSON
            val probe = JSONObject()
            if (!putTyped(probe, key, value)) {
                // 该键无法编码（如自定义 Parcelable）⇒ 记类型名，不丢键
                if (!putTyped(probe, key, "<unencodable:${value?.javaClass?.name ?: "null"}>")) {
                    continue
                }
                truncated = true
            }

            val probeStr = probe.toString()
            // 每个键在整对象里占 "key":value 加上分隔逗号。
            //
            // ⚠️ 必须是**字节**数（原实现是 `probeStr.length`，即字符数）——
            // 全 CJK 时字符数只是真实体积的三分之一，会静默超限。
            // 用 ResultBudget.byteSizeOf 保证与契约、与 intent_uri 的判据同源。
            val cost = ResultBudget.byteSizeOf(probeStr) - 2 + if (added > 0) 1 else 0

            if (used + cost > MAX_EXTRAS_JSON_BYTES) {
                truncated = true
                break
            }

            try {
                obj.put(key, probe.get(key))
            } catch (_: Exception) {
                truncated = true
                continue
            }
            used += cost
            added++
        }

        return ExtrasResult(obj.toString(), truncated)
    }

    /**
     * 按**真实类型**写入。返回 false 表示该类型无法安全编码（调用方改存类型名）。
     *
     * ⚠️ 这里就是「不猜类型」的落实点：
     * `String "1"` 写成 JSON 字符串 `"1"`，`Int 1` 写成 JSON 数字 `1` ——
     * 两者在 JSON 层可区分，下游不会像 `dumpsys` 那样把字符串猜成数字。
     */
    private fun putTyped(target: JSONObject, key: String, value: Any?): Boolean = try {
        when (value) {
            null -> {
                // ⚠️ 用 JSONObject.NULL 而不是 Kotlin null ——
                // 后者在 org.json 里是「删掉这个键」的意思
                target.put(key, JSONObject.NULL)
                true
            }
            is String, is CharSequence -> {
                target.put(key, value.toString())
                true
            }
            is Boolean -> {
                target.put(key, value)
                true
            }
            is Int, is Long, is Short, is Byte -> {
                target.put(key, (value as Number).toLong())
                true
            }
            is Float, is Double -> {
                val d = (value as Number).toDouble()
                // NaN / Infinity 不是合法 JSON，org.json 会把它们写成字符串
                // 破坏类型语义，故显式拒绝
                if (d.isNaN() || d.isInfinite()) false else {
                    target.put(key, d)
                    true
                }
            }
            is Array<*> -> {
                // 数组逐元素降级为字符串（保留「有几个元素」，不保证元素类型）
                val arr = org.json.JSONArray()
                for (e in value) arr.put(e?.toString() ?: JSONObject.NULL)
                target.put(key, arr)
                true
            }
            is BooleanArray -> {
                val arr = org.json.JSONArray()
                for (e in value) arr.put(e)
                target.put(key, arr)
                true
            }
            is IntArray -> {
                val arr = org.json.JSONArray()
                for (e in value) arr.put(e)
                target.put(key, arr)
                true
            }
            is LongArray -> {
                val arr = org.json.JSONArray()
                for (e in value) arr.put(e)
                target.put(key, arr)
                true
            }
            is DoubleArray -> {
                val arr = org.json.JSONArray()
                for (e in value) arr.put(e)
                target.put(key, arr)
                true
            }
            else -> false
        }
    } catch (_: Throwable) {
        false
    }

    /**
     * 按**字节**截断字符串，且**绝不切碎一个 UTF-8 字符**。
     *
     * ⚠️ 为什么要单独写：`String.take(n)` 是按**字符**取的，
     * 而我们要的是「不超过 n 个字节」。若直接 `take(maxBytes)`，
     * CJK 场景下会取到约 3 倍预算的字节数 —— 等于没截。
     *
     * ⚠️ 也**不能**简单按字节数组切：从中间切开一个多字节字符会产出
     * 非法的 UTF-8 序列，下游解析时得到替换字符（`�`）甚至解析失败。
     * 所以这里按字符逐个累加字节数。
     */
    private fun truncateToBytes(text: String, maxBytes: Int): String {
        var used = 0
        val sb = StringBuilder()
        for (ch in text) {
            val size = ResultBudget.byteSizeOf(ch.toString())
            if (used + size > maxBytes) break
            sb.append(ch)
            used += size
        }
        return sb.toString()
    }

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")

    /** [encodeExtras] 的结果。 */
    internal data class ExtrasResult(val json: String, val truncated: Boolean)
}

/**
 * 解码后的事件。
 *
 * `extrasJson` 保持**原始字符串** —— 每个 extras 键的语义由下游（工作流）决定，
 * 本层不认识任何键（§4.3：不为每个 extras 键建独立输出，键不可枚举）。
 */
data class ActivityEvent(
    val packageName: String,
    val className: String,
    val component: String,
    val intentUri: String,
    val extrasJson: String,
    /** 载荷是否被截断过。⚠️ 必须让下游能知道 —— 否则「extras 少了几个键」会被当成数据本身如此。 */
    val truncated: Boolean,
)
