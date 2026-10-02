package com.chaomixian.vflow.xposed.script

import org.json.JSONObject

/**
 * `xposed_js` capability 的**参数层**（`params` 内部）编解码。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §4 步骤 2。
 * 上位文档：`docs/fork/xposed-executor-design.md` §3 协议字段表。
 *
 * ## 协议形状
 *
 * ```
 * 请求 params（本文件管的这一层）
 *   { "script": "...", "inputs": { … } }
 *
 * 响应 result（由 `InvokePolicy.buildResultJson` 产出，本文件不管）
 *   { "items": [ <脚本返回的字典> ] }
 * ```
 *
 * ## ⚠️⚠️ 职责边界：本文件**只做参数层**，不做信封层
 *
 * `{"request_id": …, "capability": …, "params": …}` 那一层由
 * [com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec] 负责。
 * 在这里再实现一遍是「两份编解码」的反模式 —— `FORK.md` 记过 logcat 双份实现的代价
 *（「语义改动必须同时改两处，不一致的表现是调试工具里能匹配而触发器匹配不到」）。
 *
 * ## ⚠️ 依赖白名单
 *
 * 本文件在 `xposed/` 下（会被注入 system_server 的 hook 层加载），
 * 只允许 `org.json` / `java.*` / `kotlin.*` / `com.chaomixian.vflow.xposed.*`。
 * 由 `WireLayerPurityTest` 源码扫描锁住。
 *
 * ## ⚠️ `inputs` 的值**保持 org.json 的原始类型**（不做递归深转）
 *
 * 顶层转成 `Map` 是为了对上 [ScriptExecutor.run] 的签名，而**值原样保留**：
 * `JSONObject` / `JSONArray` / `String` / 数字 / 布尔 / [JSONObject.NULL]。
 *
 * 三条理由（都是「少一处会出错的地方」）：
 *
 * 1. **往返无损** —— `JSONObject.NULL` 塞回 [JSONObject] 会正确输出 `null`；
 *    若转成 Kotlin `null`，[JSONObject] 的构造函数会**静默丢掉那个键**
 *    （实测：`{"a":null,"b":1}` → `{"b":1}`），round-trip 就不再成立。
 * 2. **不需要两份递归转换**（encode 一份、decode 一份），
 *    而递归转换里的 `null` 处理正是最容易写错的那种代码。
 * 3. [ScriptExecutor] 本来就要处理多种值类型，多认两个 org.json 类型不增加复杂度。
 *
 * ## ⚠️ 边界：以上只适用于**本文件的入参方向**（hook 侧读 `inputs`）
 *
 * `inputs` 的值保持 org.json 原始类型，是因为它们要**塞回 Rhino 作用域**（消费方是
 * `ScriptExecutor`，它本来就要认多种值类型）。这与「**App 侧读 `result`**」是**两条不同的路径**：
 *
 * | 方向 | 消费方 | 值形态 |
 * |---|---|---|
 * | App → hook（`inputs`） | `ScriptExecutor`（Rhino 作用域） | **org.json 原始类型**（本文件的选择） |
 * | hook → App（`result`） | `CapabilityInvoker` → 业务模块 | **Kotlin `Map` / `List`**（codec 递归深转） |
 *
 * ⚠️⚠️ **不要**把本节的结论搬到 App 侧去 —— `CapabilityInvoker.classifyResponse` →
 * `jsonObjectToMap` → `deepConvert`（`core/xposed/CapabilityInvoker.kt:585-590`）已做
 * **递归深层转换**（`JSONObject`→`Map`、`JSONArray`→`List`、`JSONObject.NULL`→`null`），
 * 故 App 侧 `result["items"]` 是**真正的 `List`**、`items[0]` 是**真正的 `Map`**，
 * 用 `as? List<*>` / `as? Map<*, *>` 取**是对的**。
 * 这正是 2026-10-01 真机缺陷（`itemsFromLossless` 恒返回空）的结构性修复。
 */
object ScriptRequest {

    /** `params.script` —— 要执行的 JavaScript 源码。 */
    const val KEY_SCRIPT = "script"

    /** `params.inputs` —— 注入脚本的 `inputs` 对象。 */
    const val KEY_INPUTS = "inputs"

    /**
     * 解析 `params`。
     *
     * ## ⚠️⚠️ 任何形态的坏输入都返回 `null`，**绝不抛异常**
     *
     * 它跑在 hook 层（system_server）的执行路径上。抛出去会被
     * [com.chaomixian.vflow.xposed.capabilities.HookCapabilityRuntime] 的顶层兜成
     * `handler_error`，而「参数不合法」应当是 **handler 自己可判定**的失败 ——
     * 那样才能给出更具体的 `detail`（「缺 `script`」比「handler 抛了异常」有用得多）。
     *
     * ## 判定
     *
     * | 输入 | 结果 |
     * |---|---|
     * | 坏 JSON（不是对象） | `null` |
     * | 缺 `script` 键 | `null` |
     * | `script` 不是字符串（如 `{"script":5}`） | `null` |
     * | `script` 是**空串** | **放行**（照方案：只判类型不判空）⇒ 空脚本会跑出空结果 |
     * | `inputs` 缺失 / 不是对象 | 空 Map（**宽容**：脚本仍能跑，只是没有输入） |
     * | 未知字段 | 忽略（协议是加法演进的，旧端不认识新键是常态） |
     */
    fun decode(paramsJson: String): ScriptParams? {
        val obj = try {
            JSONObject(paramsJson)
        } catch (_: Throwable) {
            return null
        }

        // ⚠️ 用 `opt` 而不是 `optString`：后者对**非字符串**值会返回其 `toString()`
        //（`{"script":5}` ⇒ `"5"`），于是一个类型写错的请求会被**静默当成**合法脚本执行，
        // 报错变成「脚本第 1 行有语法错误」—— 排查方向整个错掉。
        val script = obj.opt(KEY_SCRIPT)
        if (script !is String) return null

        return ScriptParams(script = script, inputs = inputsOf(obj))
    }

    /**
     * 编码 `params`。**仅测试用** —— 生产路径的请求由 App 侧构造
     *（那里是 `CapabilityInvocationCodec.encodeRequest`）。
     *
     * ⚠️ 保留它的理由是 **round-trip 可断言**：没有 encode 就没法验「decode 无损」，
     * 而「`null` 值会不会在往返里丢键」正是本文件最容易静默出错的地方。
     */
    fun encode(params: ScriptParams): String {
        val inputs = JSONObject()
        params.inputs.forEach { (key, value) ->
            // ⚠️ Kotlin `null` 要显式转成 JSONObject.NULL —— 直接 put(null) 会**丢键**
            //（实测：`{"a":null,"b":1}` → `{"b":1}`）
            inputs.put(key, value ?: JSONObject.NULL)
        }
        return JSONObject()
            .put(KEY_SCRIPT, params.script)
            .put(KEY_INPUTS, inputs)
            .toString()
    }

    /**
     * 取 `inputs` 的顶层浅表（值原样）。
     *
     * ⚠️ `optJSONObject` 对「缺失」「不是对象」都返回 `null` ⇒ 两者都落到空 Map。
     * 这是**有意的宽容**：一个没有 `inputs` 的请求（脚本不依赖输入）是完全合法的用法。
     */
    private fun inputsOf(obj: JSONObject): Map<String, Any?> {
        val inputsObj = obj.optJSONObject(KEY_INPUTS) ?: return emptyMap()
        val out = LinkedHashMap<String, Any?>(inputsObj.length())
        for (key in inputsObj.keys()) {
            // ⚠️ `opt`（不是 `get`）：JSON null 会返回 JSONObject.NULL 这个**哨兵对象**
            //（实测确认），而不是 Java null —— 这正是往返无损的前提（见类注释）。
            out[key] = inputsObj.opt(key)
        }
        return out
    }
}

/**
 * `xposed_js` 的参数。
 *
 * @property script 要执行的 JavaScript 源码。
 * @property inputs 注入脚本作用域的 `inputs` 对象（脚本里按 `inputs.<key>` 访问）。
 *   ⚠️ 值的类型是 **org.json 的原始类型**（`JSONObject` / `JSONArray` /
 *   `String` / 数字 / 布尔 / [JSONObject.NULL]）—— 见 [ScriptRequest] 类注释。
 */
data class ScriptParams(
    val script: String,
    val inputs: Map<String, Any?>,
)
