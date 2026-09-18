package com.chaomixian.vflow.core.logcat

import org.json.JSONArray
import org.json.JSONObject

/**
 * 触发器条件的**跨进程传输格式**。纯函数（只依赖 org.json），可单测。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §6.5。
 *
 * ## 为什么用 JSON 而不是 Parcelable
 *
 * 这条链路是 **App → Core**，走的是 socket 上的 JSON 帧（现有 `VFlowCoreBridge`
 * 的协议就是 `{"target":..,"method":..,"params":{..}}`），
 * 不是 Binder，因此没有 Parcelable 的用武之地。
 *
 * ## 为什么不传正则的字符串而传模式
 *
 * 传的就是**模式原文**，由 Core 侧自己编译。理由：
 * - `Regex` 对象不可序列化
 * - Core 侧预编译一次即可（文档 §4.2 的硬要求：**不可在 matches() 里现编**）
 *
 * ## 编解码必须对称
 *
 * 这是本层最容易出错的地方，且**错了不报错**——
 * 编出 Core 解不开的格式时，Core 会给出一个空条件列表，
 * 表现是**所有 logcat 触发器静默不触发**。
 * 因此 [encodeConditions] 与 [decodeConditions] 有往返测试（round-trip），
 * 且 Core 要照抄同一份实现。
 */
object LogcatConditionWire {

    private const val KEY_TRIGGER_ID = "triggerId"
    private const val KEY_TAG_TYPE = "tagType"
    private const val KEY_TAG_VALUE = "tagValue"
    private const val KEY_MESSAGE_TYPE = "messageType"
    private const val KEY_MESSAGE_VALUE = "messageValue"
    private const val KEY_MIN_LEVEL = "minLevel"

    /** 序列化一个条件。 */
    fun encode(c: LogcatTriggerCondition): JSONObject = JSONObject()
        .put(KEY_TRIGGER_ID, c.triggerId)
        .put(KEY_TAG_TYPE, typeOf(c.tagMatcher))
        .put(KEY_TAG_VALUE, valueOf(c.tagMatcher))
        .put(KEY_MESSAGE_TYPE, typeOf(c.messageMatcher))
        .put(KEY_MESSAGE_VALUE, valueOf(c.messageMatcher))
        .put(KEY_MIN_LEVEL, c.minLevel.char.toString())

    /** 序列化整份条件列表（`updateTriggers` 的载荷）。 */
    fun encodeConditions(conditions: List<LogcatTriggerCondition>): JSONArray =
        JSONArray().apply { conditions.forEach { put(encode(it)) } }

    /**
     * 反序列化单个条件。
     *
     * @return 条件；**某个字段不合法时返回 null**（如正则编译失败）。
     *   调用方应跳过该条并记录日志——**不能因为一条坏条件让整个列表失败**，
     *   否则会出现"加了一个配错的触发器，其他触发器全部失效"。
     */
    fun decode(json: JSONObject): LogcatTriggerCondition? {
        val triggerId = json.optString(KEY_TRIGGER_ID).takeIf { it.isNotBlank() } ?: return null

        val tagMatcher = decodeMatcher(
            json.optString(KEY_TAG_TYPE),
            json.optString(KEY_TAG_VALUE),
        ) ?: return null

        val messageMatcher = decodeMatcher(
            json.optString(KEY_MESSAGE_TYPE),
            json.optString(KEY_MESSAGE_VALUE),
        ) ?: return null

        // 级别字符反查枚举。认不出时**不猜测**——回退到默认会让
        // "配了 E 却触发了 V"这种静默错误成为可能
        val level = json.optString(KEY_MIN_LEVEL)
            .firstOrNull()
            ?.let { LogLevel.fromChar(it) }
            ?: return null

        return LogcatTriggerCondition(triggerId, tagMatcher, messageMatcher, level)
    }

    /**
     * 反序列化整份列表。
     *
     * **跳过坏条目而不是整体失败**（见 [decode] 的说明）。
     */
    fun decodeConditions(array: JSONArray): List<LogcatTriggerCondition> {
        val out = ArrayList<LogcatTriggerCondition>(array.length())
        for (i in 0 until array.length()) {
            val obj = array.optJSONObject(i) ?: continue
            decode(obj)?.let { out.add(it) }
        }
        return out
    }

    private fun typeOf(m: LogcatMatcher): String = when (m) {
        is LogcatMatcher.Unconstrained -> LogcatFilterType.ANY
        is LogcatMatcher.Equals -> LogcatFilterType.EQUALS
        is LogcatMatcher.Contains -> LogcatFilterType.CONTAINS
        is LogcatMatcher.RegexMatcher -> LogcatFilterType.REGEX
    }

    private fun valueOf(m: LogcatMatcher): String = when (m) {
        is LogcatMatcher.Unconstrained -> ""
        is LogcatMatcher.Equals -> m.value
        is LogcatMatcher.Contains -> m.value
        is LogcatMatcher.RegexMatcher -> m.regex.pattern
    }

    private fun decodeMatcher(type: String, value: String): LogcatMatcher? =
        buildMatcher(type, value)
}
