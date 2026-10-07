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
     * ⚠️ **取值依据是 Binder oneway 半缓冲（≈508 KiB）** —— 本载荷正是跨进程传的。
     * **不要**把这个数值套到「同进程传递」的场景上：广播触发器的载荷同进程传递、
     * 不过 Binder，它用的是自己的 8 KiB 常量（理由见 `BroadcastTriggerHandler`）。
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
            ExtrasJsonCodec.truncateToBytes(safeUri, MAX_INTENT_URI_BYTES)
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
     * ⚠️ 实现已**提取**到 [ExtrasJsonCodec]（广播触发器与 hook 链路共用一份，
     * 避免仓库反复踩过的「双份实现静默漂移」）。这里保留同名入口只是为了
     * 不改变本文件的对外契约 —— 预算仍是 [MAX_EXTRAS_JSON_BYTES]。
     *
     * 那一段的实现语义（为什么不能「拼好再截」、为什么按字节计）写在
     * [ExtrasJsonCodec.encode] 的 KDoc 里，与本处同步维护。
     */
    internal fun encodeExtras(extras: Map<String, Any?>): ExtrasJsonCodec.Result =
        ExtrasJsonCodec.encode(extras, MAX_EXTRAS_JSON_BYTES)

    private fun escape(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"")
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
