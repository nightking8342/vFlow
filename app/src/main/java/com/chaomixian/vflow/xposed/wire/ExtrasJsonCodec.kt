package com.chaomixian.vflow.xposed.wire

import org.json.JSONObject

/**
 * extras（`Map<String, Any?>` 形态）→ JSON 的**纯函数编码层**。
 *
 * ## ⚠️ 这个文件**不是 hook 专用**（这是它存在的第一个理由）
 *
 * 它同时被 App 侧的广播触发器
 * （`com.chaomixian.vflow.core.workflow.module.triggers.handlers.BroadcastTriggerHandler`）
 * 复用 —— 广播触发器拿不到 hook，但需要同一套「按真实类型 + 按字节预算 + 逐键累加」的编码。
 *
 * 若各写一份，代价不是「多几行」，而是**改一处忘另一处**（`FORK.md` 已记过 logcat
 * 双份实现的代价：「语义改动必须同时改两处，不一致的表现是调试工具里看着能匹配的日志，
 * 触发器匹配不到」）。共用一份 ⇒ 两处行为**结构上不可能漂移**。
 *
 * ## ⚠️ 为什么落在 `xposed/wire/` 而不是 `core/`
 *
 * 同目录的 [ActivityPayload] 受 `WireLayerPurityTest` 的 import 白名单约束
 * （只有 `org.json.` / `java.` / `kotlin.` / `com.chaomixian.vflow.xposed.`），
 * **不能反向依赖 App 侧代码**。把本文件放到 `core/util/` 会让 [ActivityPayload]
 * 无法引用它；放到 `xposed/wire/` 下、由 App 侧 import，是本仓库既有的**单向**引用方向
 * （先例：`core/xposed/HookChannelController.kt`、
 * `core/workflow/module/triggers/handlers/ActivityChangedTriggerHandler.kt`
 * 都已在 import `xposed.wire.*`）。
 *
 * ⇒ 「App 层触发器 import `xposed.wire.*`」的语义别扭感由本段解释掉，
 * 不留给人猜。**但是**：这条也意味着本文件**必须保持纯 JVM**
 * （只允许 org.json / java / kotlin / com.chaomixian.vflow.xposed.*），
 * **不得 import 任何 `android.*`**（连 `Bundle` 都不行 —— 入参刻意用 `Map<String, Any?>`）。
 * 由 `WireLayerPurityTest` 的四条扫描锁住。
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
 * 不能「先拼好再按长度砍」——砍一半的 JSON 无法解析，下游只会得到一个
 * 静默的解析失败，**整条 extras 都拿不到**（而不是「少几个键」）。
 * 本层的做法是**逐键累加、超限就停**，并置 `truncated = true`。
 */
object ExtrasJsonCodec {

    /** 编码结果。[truncated] = true 表示**内容不完整**（超预算被截，或有键无法编码）。 */
    data class Result(val json: String, val truncated: Boolean)

    /**
     * 把 extras 编码成 JSON —— **逐键累加，超预算即停**。
     *
     * 为什么不是「拼好再砍」：截断后的 JSON 不合法，下游 `JSONObject(...)` 会抛，
     * 于是**整条 extras 都拿不到**，而不是「少几个键」。逐键累加能保证
     * 拿到的部分始终是可解析的。
     *
     * @param extras 已从 Bundle 取出的键值对。**值保留原始类型**（不做 toString）——
     *   类型信息在这里还不能丢，由 [putTyped] 按真实类型写进 JSON。
     * @param maxBytes 预算上限（**字节**，UTF-8），由调用方给：
     *   [ActivityPayload] 给 [ActivityPayload.MAX_EXTRAS_JSON_BYTES]（48 KiB，
     *   依据是 Binder oneway 半缓冲）；广播触发器给自己的常量（8 KiB，
     *   它的载荷同进程传递、不过 Binder —— 理由见 `BroadcastTriggerHandler`）。
     * @return 编码结果 + 是否发生截断
     */
    fun encode(extras: Map<String, Any?>, maxBytes: Int): Result {
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

            if (used + cost > maxBytes) {
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

        return Result(obj.toString(), truncated)
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
     *
     * ⚠️⚠️ **必须按「码点」推进，不能按 `Char` 推进**（2026-09-29 独立验收发现）。
     *
     * 初版写的是 `for (ch in text)` + `byteSizeOf(ch.toString())`，看似正确，
     * 但对**补充平面**字符（emoji、部分 CJK 扩展）是错的：它们在 Kotlin 里是
     * 一对代理 `Char`，而**单个代理 Char 编码成 UTF-8 只有 1 字节**
     * （孤立代理退化成替换符，`String.toByteArray` 照样编得出来、不抛异常）。
     *
     * ⇒ 一个 4 字节的 emoji 被算成 `1 + 1 = 2` 字节，**预算低估一半**。
     * 实测（`limit = MAX_INTENT_URI_BYTES = 16384`，载荷为重复 emoji）：
     * **截断后实际 32768 字节，正好 2 倍上限** —— 即**截断完全没生效**，
     * 而它是静默的（不抛异常、`contains('�')` 也是 false，因为两个代理
     * 连着一起被 `break` 掉了，反而是**侥幸**没切碎的）。
     *
     * ⚠️ 这也说明**「按 Char 切会切碎」这个担心本身是次生问题**：
     * 真正的后果是**上限形同虚设**，正是 §3.6 要防的「静默超限」。
     *
     * ⚠️ 与之配套：编码层的「不得切碎多字节字符」用例
     * **必须用 emoji 做载荷** —— 「中」在 BMP 内只占一个 Char、恰好绕过本 bug，
     * 否则修完也无法反证。
     */
    fun truncateToBytes(text: String, maxBytes: Int): String {
        var used = 0
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val cp = text.codePointAt(i)
            val charCount = Character.charCount(cp)
            // 用整码点一次性算字节数 —— 与 ResultBudget 的口径同源，
            // 且对代理对得到的是真实 UTF-8 长度（emoji = 4），不是 1 + 1。
            val size = ResultBudget.byteSizeOf(String(text.toCharArray(i, i + charCount)))
            if (used + size > maxBytes) break
            sb.appendCodePoint(cp)
            used += size
            i += charCount
        }
        return sb.toString()
    }

    /**
     * 按**真实类型**写入。返回 false 表示该类型无法安全编码（调用方改存类型名）。
     *
     * ⚠️ 这里就是「不猜类型」的落实点：
     * `String "1"` 写成 JSON 字符串 `"1"`，`Int 1` 写成 JSON 数字 `1` ——
     * 两者在 JSON 层可区分，下游不会像 `dumpsys` 那样把字符串猜成数字。
     *
     * ⚠️ 保持 `private`：它不是对外契约，且**对外暴露会诱使别人绕过
     * [encode] 的预算管理**（那正是本层要防的静默超限）。
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
}
