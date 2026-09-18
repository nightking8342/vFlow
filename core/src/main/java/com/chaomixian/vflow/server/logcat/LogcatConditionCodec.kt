package com.chaomixian.vflow.server.logcat

import org.json.JSONArray
import org.json.JSONObject

/**
 * 触发器条件的跨进程编解码。**Core 侧**。
 *
 * ## ⚠️ 必须与 app 侧 `LogcatConditionWire` 严格对称
 *
 * 那条链是 App → Core。**编解码不对称是本链路最危险的失败模式**：
 * Core 解不开 App 编的格式时会拿到空条件列表 → **所有 logcat 触发器静默不触发**，
 * 而 App 侧看起来一切正常（条件已下发、无报错）。
 *
 * 因此**字段名一个字都不能改**，除非两边同时改。
 * app 侧有 `LogcatConditionWireTest`（19 例）锁住字段名与往返一致性，
 * 改动应以那一份为准。
 *
 * ## 容错策略：跳过坏条目而非整体失败
 *
 * 某一条条件不合法时**只跳过它**。若整体失败，用户"新加了一个配错正则的触发器"
 * 会导致**已有的正常触发器全部失效**——影响面与直觉完全不符。
 */
object LogcatConditionCodec {

    // ⚠️ 与 app 侧 LogcatConditionWire 的私有常量逐字一致
    private const val KEY_TRIGGER_ID = "triggerId"
    private const val KEY_TAG_TYPE = "tagType"
    private const val KEY_TAG_VALUE = "tagValue"
    private const val KEY_MESSAGE_TYPE = "messageType"
    private const val KEY_MESSAGE_VALUE = "messageValue"
    private const val KEY_MIN_LEVEL = "minLevel"

    private const val TYPE_ANY = "any"
    private const val TYPE_EQUALS = "equals"
    private const val TYPE_CONTAINS = "contains"
    private const val TYPE_REGEX = "regex"

    /**
     * 反序列化整份条件列表。
     *
     * @param array App 发来的条件数组；null 或缺失时返回空列表
     *   （空列表是有意义的载荷：Core 据此停掉 logcat 进程，文档 §7.1）
     */
    fun decode(array: JSONArray?): List<LogcatCondition> {
        if (array == null) return emptyList()

        val out = ArrayList<LogcatCondition>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            decodeOne(obj)?.let { out.add(it) }
        }
        return out
    }

    /**
     * 反序列化单条。
     *
     * @return null 表示该条不合法（缺 triggerId / 正则编译失败 / 级别字符不认识），
     *   调用方应跳过并记录
     */
    fun decodeOne(json: JSONObject): LogcatCondition? {
        val triggerId = json.optString(KEY_TRIGGER_ID).takeIf { it.isNotBlank() } ?: return null

        val tagMatcher = decodeMatcher(
            json.optString(KEY_TAG_TYPE),
            json.optString(KEY_TAG_VALUE),
        ) ?: return null

        val messageMatcher = decodeMatcher(
            json.optString(KEY_MESSAGE_TYPE),
            json.optString(KEY_MESSAGE_VALUE),
        ) ?: return null

        // 级别字符。认不出时**不猜测**——回退到默认会让
        // "配了 E 却触发了 V"这种静默错误成为可能
        val level = json.optString(KEY_MIN_LEVEL).firstOrNull()
            ?.takeIf { it in "VDIWEF" }
            ?: return null

        return LogcatCondition(triggerId, tagMatcher, messageMatcher, level)
    }

    /**
     * 构造匹配器。
     *
     * ⚠️ **这里是正则唯一的编译点**，也是把它放在遍历热路径之外的保证。
     * 编译失败（用户写了非法正则）返回 null → 该条被跳过。
     *
     * 注意**不能把编译失败当成 `Unconstrained`**：那会让"配错了正则"
     * 变成"匹配所有日志"，与用户意图正好相反。
     */
    private fun decodeMatcher(type: String, value: String): LogcatFieldMatcher? = when (type) {
        TYPE_ANY -> LogcatFieldMatcher.Unconstrained
        TYPE_EQUALS -> LogcatFieldMatcher.Equals(value)
        TYPE_CONTAINS -> LogcatFieldMatcher.Contains(value)
        TYPE_REGEX -> value
            .takeIf { it.isNotBlank() }
            ?.let { runCatching { LogcatFieldMatcher.RegexMatcher(Regex(it)) }.getOrNull() }
        else -> null
    }
}
