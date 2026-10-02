package com.chaomixian.vflow.core.workflow.module.xposed

/**
 * `vflow.xposed.js` 模块的**纯函数层**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §5.7（两个 JS 模块）。
 *
 * ## ⚠️ 为什么单独抽一个文件
 *
 * 本文件的两件事（超时钳位 / 结果形状消费）都是「**改错了不报错、只静默变差**」的地方：
 *
 * - 钳位算错 ⇒ 用户配了 30 秒却按 5 秒超时，脚本被活活掐断，报错说的是「超时」；
 * - 结果形状取错 ⇒ 脚本明明返回了字典，下游永远拿到空字典，**且不报任何错**
 *   （本仓库在 `query_shortcut_intents` 上真机踩过这个形态：
 *   `itemsFromLossless` 恒返回空 ⇒ 选择器里「没有快捷方式」）。
 *
 * ⇒ 抽成**无 Android 依赖**的纯 Kotlin，走纯 JVM 单测逐值锁住（形态照 `WorkflowPatch.kt`）。
 */

/**
 * 默认超时（ms）。
 *
 * ⚠️ 与 `CapabilityInvocationCodec.DEFAULT_TIMEOUT_MS` **同值但刻意不引用它** ——
 * 那个常量在 `xposed/wire/` 下（会被注入 system_server 的代码加载），
 * 引用它会把 app 侧依赖带进本模块的依赖面。**同值是巧合，也是口径**，
 * 由 `XposedJsSupportTest` 的注释钉住「改一处要想到另一处」。
 */
internal const val DEFAULT_TIMEOUT_MS = 5_000L

/**
 * 超时上限（ms）。
 *
 * ⚠️ 存在的理由不是「数字好看」：hook 侧的工作线程池**容量只有 2、且不排队**
 * ⇒ 一个跑很久的脚本会**占住半边池**，两个就池满（此后所有调用立刻回
 * `handler_error`）。故上限必须**显式**存在，让「池被长时间占住」有个天花板。
 */
internal const val MAX_TIMEOUT_MS = 30_000L

/**
 * 把用户配的超时钳到合法区间。
 *
 * ## 语义（每条分支都能被独立断言）
 *
 * | 输入 | 输出 | 理由 |
 * |---|---|---|
 * | `null`（没配过 / 取值失败） | 5000 | 默认值 |
 * | `<= 0` | 5000 | ⚠️ **退回默认，而不是钳到 1ms** —— 见下 |
 * | `1 .. 30000` | 原样 | |
 * | `> 30000` | 30000 | 上限 |
 *
 * ⚠️⚠️ **`<= 0` 为什么退回默认而不是钳到最小值**：
 * 钳到 1ms 会让用户拿到一个**必然超时**的结果 —— 连一次 binder 往返到 system_server
 * 都不止 1ms。那时用户看到的是「脚本超时」，会去查脚本本身，
 * 而真相是他填了个 `0`。退回默认至少让脚本能跑完。
 * （0 / 负数本身也没有任何有意义的语义。）
 */
internal fun clampTimeoutMs(raw: Long?): Long = when {
    raw == null -> DEFAULT_TIMEOUT_MS
    raw <= 0L -> DEFAULT_TIMEOUT_MS
    raw > MAX_TIMEOUT_MS -> MAX_TIMEOUT_MS
    else -> raw
}

/**
 * 从 `xposed_js` 的 **result** 里取出脚本返回的 outputs 字典。
 *
 * ## ⚠️⚠️ T2 定案的 result 形状 —— 不是 `{"outputs": {...}}`
 *
 * ```json
 * { "items": [ <脚本返回的字典本身> ] }
 * ```
 *
 * ⇒ 取 `items[0]`，**零转换、无包装键**。
 *
 * **为什么是这个形状**：框架的 `InvokePolicy.buildResultJson` **固定**产出
 * `{"items":[…]}`，`CapabilityOutcome` 只有 `Items` / `Failure` 两个变体，
 * result 顶层**没有元数据位**（旁证：`query_shortcut_intents` 想带 `total`
 * 也因此没做成）。用户 2026-10-02 拍板接受形状 A。
 *
 * ## ⚠️ 边界：空字典 **不是** 失败
 *
 * 脚本无返回值 / 返回非对象 ⇒ T2 给 `items = [{}]`（**空字典，不是空 items**）
 * ⇒ 这里返回**空 map**。调用方**不得**把它当失败 —— 那会把
 * 「脚本故意不返回东西」误报成「脚本坏了」。
 *
 * ## ⚠️ 防御：一律返回空 map，从不抛
 *
 * `items` 缺失 / 不是 List / 是空 List / `items[0]` 不是 Map ⇒ 空 map。
 *
 * ## ⚠️ 为什么 `as? Map<*, *>` 是对的（不要改成 `optJSONObject`）
 *
 * App 侧 `CapabilityInvoker.jsonObjectToMap` → `deepConvert` 做了**递归深转**
 *（`JSONObject`→`Map`、`JSONArray`→`List`、`JSONObject.NULL`→`null`）
 * ⇒ 拿到的 `result["items"]` 是**真正的 `List`**、`items[0]` 是**真正的 `Map`**。
 *
 * 改走 `optJSONObject(...)` 会**恒返回 null**（对 `Map` 调 `optJSONObject` 取不到），
 * 也就是重演 `itemsFromLossless` 恒空那个真机缺陷。
 *
 * ⚠️ `XposedJsCapabilityHandler` 的 KDoc 里**曾有一句方向相反的说法**
 *（「`items[0]` 里的值是 org.json 类型」）—— 那说的是**另一个方向**：
 * hook 侧读**请求**里的 `inputs`（保持 org.json 原始类型）。
 * 本函数读的是**响应**里的 `result`，由 App 侧 codec 转过。**以本注释为准。**
 *（该句**已由父会话就地修正**，现在两边口径一致；此处保留记录是为了
 * 「读旧代码/旧截图的人知道该信哪边」。）
 */
internal fun rawOutputsOf(result: Map<String, Any?>): Map<String, Any?> {
    val items = result["items"] as? List<*> ?: return emptyMap()
    val first = items.firstOrNull() as? Map<*, *> ?: return emptyMap()
    return first.entries.associate { (k, v) -> k.toString() to v }
}

/**
 * 从 inputs 字典里取出「需要展开魔法变量」的项。
 *
 * 与 `JsModule.execute()` 的做法一致：值先 `asString()` 得到可能的 `{{...}}` 文本，
 * 是引用就 `resolveValue` 展开，否则原样用。
 *
 * ⚠️ **顺序不能反**（先字符串化再判引用）—— `hasVariableReference` 收的是 `String?`，
 * 而 `resolveValue` 返回的可能是 `Double` / `Boolean` / `Map` 等原始值
 *（单个 `{{x}}` 片段时它返回 `vObj.raw`，保留类型）。
 *
 * @param entries 键 → 文本（由调用方从 `VDictionary` 取好，本函数不认识 VObject）
 * @param hasReference 判「这段文本里有没有变量引用」
 * @param resolve 展开一个变量引用
 */
internal fun scriptInputsOf(
    entries: Map<String, String>,
    hasReference: (String) -> Boolean,
    resolve: (String) -> Any?,
): Map<String, Any?> = entries.mapValues { (_, text) ->
    if (hasReference(text)) resolve(text) else text
}
