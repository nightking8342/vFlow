package com.chaomixian.vflow.xposed.wire

import org.json.JSONArray
import org.json.JSONObject

/**
 * `IHookCallback.capabilities()` 的返回格式：**连接期一次能力交换**的清单。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.1 / §6.3。
 *
 * ## 它是 `CapabilityPresence` 的唯一数据源
 *
 * ```
 * ping() 通  &&  capabilities() 调不通（方法不存在） ⇒ ABSENT（hook 层代码太旧）
 * ping() 通  &&  capabilities() 返回（哪怕是空清单） ⇒ READY
 * ```
 *
 * ## ⚠️ 「空清单」与「方法不存在」**是两件事**（本模块最容易搞混的地方）
 *
 * | 情形 | [decode] 返回 | 判为 |
 * |---|---|---|
 * | hook 层没有 `capabilities()` 方法 | **调用方根本拿不到字符串**（见下） | `ABSENT` |
 * | hook 层有方法，但一个 capability 都没注册 | `emptySet()`（**不是 null**） | `READY` |
 * | 字符串存在但不是合法 JSON | `null`（坏输入） | 由调用方决定（见下） |
 *
 * ⚠️ **「方法不存在」在 AIDL 层面表现为「调用抛异常」，而不是「返回空串」** ——
 * 所以调用方（`capabilities()` 的调用点）必须先 `try/catch`，
 * 把异常当成 `null` 交给 `presenceAfterExchange`。
 * 本模块只负责「字符串 ↔ 集合」，**不**负责识别「方法存不存在」。
 *
 * ## 依赖白名单
 *
 * 跑在 system_server 里，只允许 `org.json` / `java.*` / `kotlin.*`。
 */
object CapabilityManifest {

    const val KEY_PROTOCOL = "protocol_version"
    const val KEY_CAPABILITIES = "capabilities"

    /**
     * 编码清单。
     *
     * @param capabilityNames 这个 hook 层**代码里注册了**的 capability 名。
     *   ⚠️ 是「代码里有没有」，不是「此刻能不能用」—— 后者由每次调用的实测结果决定
     *   （§6.3：「状态是采样的，结果才是权威」）。
     */
    fun encode(
        capabilityNames: Collection<String>,
        protocolVersion: Int = EventEnvelopeCodec.PROTOCOL_VERSION,
    ): String {
        val arr = JSONArray()
        // ⚠️ 排序输出：让同一份清单的编码结果**稳定**，
        // 便于测试断言与日志比对（顺序本身无语义，但不确定性会掩盖真实的差异）
        capabilityNames.filter { it.isNotBlank() }.sorted().forEach { arr.put(it) }

        return JSONObject()
            .put(KEY_PROTOCOL, protocolVersion)
            .put(KEY_CAPABILITIES, arr)
            .toString()
    }

    /**
     * 解码清单。
     *
     * @return capability 名集合。**任何异常都返回 null**（含坏 JSON、缺字段）——
     *   调用方在 system_server 的 binder 线程上，抛异常会危及整机。
     *
     * ⚠️ **空清单是合法的**，返回**空集合**而非 null。
     * 把「方法存在但没注册任何 capability」误判成 `ABSENT` 会让用户
     * 被引去「升级 App」，而 App 其实是最新的 —— 正是 §6.4 要避免的误导方向。
     */
    fun decode(json: String): Set<String>? {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        // ⚠️ 用 optJSONArray + isNull 判断而非 optString：
        // 缺 `capabilities` 键与「它的值是 null」都应当判为坏输入，
        // 而空数组 `[]` 是**合法**的
        if (!obj.has(KEY_CAPABILITIES)) return null
        val arr = obj.optJSONArray(KEY_CAPABILITIES) ?: return null

        val out = LinkedHashSet<String>(arr.length())
        for (i in 0 until arr.length()) {
            val name = arr.optString(i)
            // 空名跳过（而不是让它成为一个「名字为空的能力」）
            if (name.isNotBlank()) out.add(name)
        }
        return out
    }

    /**
     * 从清单里取协议版本。
     *
     * @return 版本号；缺失返回 **-1**（不是 0 —— 0 是个合法版本，
     *   用 0 会让「缺失」与「版本 0」混同，照 [EventEnvelopeCodec] 的 seq 先例）
     */
    fun protocolVersionOf(json: String): Int = try {
        val obj = JSONObject(json)
        if (obj.has(KEY_PROTOCOL)) obj.optInt(KEY_PROTOCOL) else -1
    } catch (_: Exception) {
        -1
    }
}
