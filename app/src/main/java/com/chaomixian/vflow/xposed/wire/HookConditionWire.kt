package com.chaomixian.vflow.xposed.wire

import org.json.JSONArray
import org.json.JSONObject

/**
 * **下行过滤条件**的跨进程格式（App → hook 层）。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.2 / §4.1。
 *
 * ## ⚠️⚠️ 这里传的是「过滤项」，**不是业务规则**
 *
 * 条件是「**在哪个包的哪个 hook 点上报告事件**」——
 * 它让 hook 层不必 hook 所有进程、也不必全量上报（洪泛）。
 *
 * 但 hook 层**永远不知道**：有几个工作流、触发后做什么、用户配了什么条件。
 * **判定全在 App 侧**（§3.2 硬约束「Hook 层不知道工作流的存在」）。
 *
 * 这个区分不是形式主义：它让「用户改规则」**根本不需要碰 hook 层**，
 * 从而也不依赖热更新（而官方明文禁止拿热更新传配置，§4.6.1）。
 *
 * ## ⚠️ 与 [EventEnvelopeCodec] 同一条纯度约束
 *
 * 跑在 system_server 里，只允许 `org.json` / `java.*` / `kotlin.*`。
 *
 * ## 编码必须对称
 *
 * 与 logcat 那条链路同一个教训：编出 hook 层解不开的格式时，
 * hook 层会得到**空条件**，表现是**所有 Xposed 触发器静默不触发**。
 * 所以有往返测试。
 */
object HookConditionWire {

    private const val KEY_TOPICS = "topics"
    private const val KEY_PACKAGES = "packages"

    /**
     * 编码整份条件（`pushConditions` 的载荷）。
     *
     * @param topics 订阅的事件主题，如 `hook.activity.changed`
     * @param packages 关心的包名。**空集 = 全部包**（不限制）。
     *   ⚠️ 这是刻意的语义：空集若是「一个都不要」，用户新配触发器时
     *   会有一瞬间「什么都没有」，而反过来「全部」最多是多采一点、由 App 侧过滤掉。
     *   **宁可多采，不可漏采** —— 漏采是静默失效，多采只是浪费。
     */
    fun encode(topics: Collection<String>, packages: Collection<String> = emptyList()): String =
        try {
            JSONObject()
                .put(KEY_TOPICS, JSONArray().apply { topics.forEach { put(it) } })
                .put(KEY_PACKAGES, JSONArray().apply { packages.forEach { put(it) } })
                .toString()
        } catch (_: Throwable) {
            // 兜底：手工拼一个合法但为空的对象。hook 层会据此停掉采集，
            // 好过给出一个解不开的串（那会让 hook 层把条件也丢掉）
            """{"$KEY_TOPICS":[],"$KEY_PACKAGES":[]}"""
        }

    /** 空条件 —— 语义是「没有订阅者了，卸下 hook」。 */
    fun empty(): String = encode(emptyList())

    /**
     * 解码。
     *
     * **任何坏输入都返回空条件，绝不抛** —— 调用方在 hook 层，
     * 抛异常可能危及 system_server。而「按空条件处理」的后果是停掉采集，
     * 是安全的一侧。
     */
    fun decode(json: String): HookConditions {
        if (json.isBlank()) return HookConditions(emptySet(), emptySet())
        return try {
            val obj = JSONObject(json)
            HookConditions(
                topics = readStringSet(obj, KEY_TOPICS),
                packages = readStringSet(obj, KEY_PACKAGES),
            )
        } catch (_: Throwable) {
            HookConditions(emptySet(), emptySet())
        }
    }

    private fun readStringSet(obj: JSONObject, key: String): Set<String> {
        val arr = obj.optJSONArray(key) ?: return emptySet()
        val out = LinkedHashSet<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i)
            if (s.isNotBlank()) out.add(s)
        }
        return out
    }
}

/**
 * 解码后的条件。
 *
 * 注意它**只有过滤项**，没有「该触发哪个工作流」这类信息 —— 见 [HookConditionWire] 的说明。
 */
data class HookConditions(
    val topics: Set<String>,
    val packages: Set<String>,
) {
    /** 是否有任何订阅。空 ⇒ 可以停掉采集。 */
    val isEmpty: Boolean get() = topics.isEmpty()

    /**
     * 某包是否需要上报。[packages] 为空集时表示「全部包」。
     *
     * ## ⚠️⚠️ 判据必须比 App 侧**宽松**，否则会静默漏采
     *
     * App 侧的匹配（`ActivityChangedTriggerHandler.matchOne`）用的是
     * **包含 + 忽略大小写**。如果这里用精确匹配，就会出现：
     *
     * ```
     * 用户填包名 "com.android.set"（默认「包含」模式）
     *   → App 侧会匹配上 com.android.settings ✅
     *   → 但这里精确匹配失败 ⇒ hook 层根本不发 ⇒ 事件永远到不了 App ❌
     * ```
     *
     * 用户看到的是「配了但不触发」，而 App 侧日志里什么都没有 ——
     * 排查会一直往 hook 点或系统版本上找，找不到真因。
     *
     * **所以这里刻意用「包含」** —— 与 App 侧同宽（甚至略宽）。
     * 多放行几个包只是多采一点（App 侧会再筛一遍），
     * 少放行就是漏采，而漏采是静默失效。
     *
     * ⚠️ **类名不做同样处理是有意的**（见 `ActivityChangedTriggerModule`）：
     * 下推类名要么紧（把 App 侧「包含」当白名单精确值 ⇒ 漏采），
     * 要么松（子串匹配几乎放行一切），两头不讨好。收益只是同包内再砍一层，
     * 不值得。—— **但包名不同**：它砍掉的是绝大部分无关 App，值得下推，
     * 只是必须用与 App 侧一致的判据。
     */
    fun caresAboutPackage(packageName: String): Boolean {
        if (packages.isEmpty()) return true
        // 包含 + 忽略大小写 —— 与 ActivityChangedTriggerHandler.matchOne 对齐
        return packages.any { filter ->
            packageName.contains(filter, ignoreCase = true)
        }
    }
}
