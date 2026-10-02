package com.chaomixian.vflow.xposed.script

import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import org.mozilla.javascript.Context
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.RhinoException
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * 在 **system_server（hook 层）** 里执行一段 JavaScript。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §3.3 / §4 步骤 5。
 * 上位文档：`docs/fork/xposed-executor-design.md` §4.3（执行流程七步）。
 *
 * ## ⚠️⚠️ 执行环境的**定义性差别**：不注入 `vflow.*` 模块树
 *
 * 这是本 capability 与 App 侧 `vflow.system.js`
 *（[com.chaomixian.vflow.core.execution.JsExecutor]）**最本质的区别**，不是省事：
 *
 * | | App 侧 `JsExecutor` | **本执行器** |
 * |---|---|---|
 * | `vflow.<模块>.<动作>(…)` | ✅ 自动注入整棵模块树 | ❌ **不存在**（脚本里会抛 `ReferenceError`） |
 * | 能做什么 | 编排（调模块、串流程） | **只有「这一段计算」** |
 * | 崩溃半径 | 那个 App | ⚠️ **整机** |
 *
 * 设计意图（`docs/fork/xposed-architecture-v2.md` §5.7）：本能力提供的是
 * **UID 1000 的权限与同进程对象访问**，而**不是**第二个编排入口。
 * 需要编排请用工作流本身 —— 它有模块、有变量、有错误处理。
 *
 * 由 `ScriptExecutorTest` 的 `typeof vflow === "undefined"` 锁住。
 *
 * ⚠️ **注意区分**：Rhino 自身的 `Packages` / `JavaAdapter` / `importClass` **仍然存在**
 * ——它们是**引擎内建**，不是我们注入的。本期不做 `ClassShutter` 白名单
 * （依据：用户即设备主人 + 入口只有一个模块），这是**已知并接受**的边界；
 * 但记录下来：若脚本来源扩展到「下载 / AI 生成」，**必须补**。
 *
 * ## 返回值序列化：走 JSON 文本，不走 Kotlin 对象
 *
 * [Context.toString] 对 JS 对象返回 `[object Object]`（**必须**走 `JSON.stringify`），
 * 而 `JSON.stringify` 产出的是**一段 JSON 文本**。
 *
 * ⇒ 本执行器直接用 [JSONTokener] 把那**段文本**解析成 org.json 的对象，
 * **不再转成 Kotlin 的 `Map` / `List`**。这样做顺带**结构性地**消灭了一个缺陷：
 *
 * | 路线 | `null` 值 |
 * |---|---|
 * | 转 Kotlin `Map`（JS null → Kotlin `null`） | ❌ 交给 [JSONObject] 时**静默丢键**（实测 `{"a":null,"b":1}` → `{"b":1}`） |
 * | **走 JSON 文本（本实现）** | ✅ 全程是 org.json 的 `JSONObject.NULL` 哨兵，**不会丢** |
 *
 * ⚠️ 代价（下游必须知道）：`outputs` 的**值是 org.json 类型**
 *（[JSONObject] / [JSONArray] / String / 数字 / 布尔 / [JSONObject.NULL]），
 * 而 **`JSONObject` 不实现 `Map`、`JSONArray` 不实现 `List`**（实测）——
 * 用 `as? Map<*, *>` 取会**恒为 null**。见 [ScriptRequest] 类注释里的详细说明。
 *
 * ## 依赖白名单
 *
 * 本文件在 `xposed/` 下（会被注入 system_server 的 hook 层加载），
 * 只允许 `org.json` / `org.mozilla.javascript` / `java.*` / `kotlin.*` /
 * `com.chaomixian.vflow.xposed.*`。由 `WireLayerPurityTest` 源码扫描锁住。
 *
 * ⚠️ `org.mozilla.javascript.*` **不需要**登记进那两张白名单表 ——
 * 它们的判定维度是「是不是 Android 类 / 是不是 App 侧包」，Rhino 两边都不沾。
 *
 * ## ⚠️⚠️ 唯一的 **P0 级未知**：Rhino 能否在 system_server 的 classloader 下初始化
 *
 * **本 class 从未在真机上跑过。** 全部验证只到「编译 + 纯 JVM 单测 + release 打包」。
 *
 * 具体未知项：`ImporterTopLevel(cx)` 以及它内部要初始化的整套标准内建，
 * **能否在注入 system_server 的 LSPosed ClassLoader 下成功构造**。
 * 纯 JVM 上实测通过（本机 Rhino 1.9.0），但 **system_server 的 classloader 环境不同**
 *（它由 LSPosed 加载、且 system_server 已有一堆自己的类加载状态）。
 *
 * ⚠️ **若它起不来，本 capability 整个不成立** —— 不是「功能受限」，是**完全不响应**
 *（App 侧会看到 `capability_absent` 或超时）。这是本 capability 的**头号真机验证项**。
 *
 * ⚠️ 这条**不要**和下面的「已知限制」混为一谈：那些是「功能成立、有边界」，
 * 这一条是「成不成立」。层级不同。
 *
 * ## 其余已知限制（功能成立，只是有边界）
 *
 * - **一次阻塞的 Java 调用不可中断** —— 见 [ScriptSandbox] 的类注释（含实测数据）。
 *   ⚠️ 这是**设计主动接受**的代价，**不是缺陷**。
 * - **本期不做 `ClassShutter` 白名单**（依据：用户即设备主人 + 入口只有一个模块）。
 *   ⚠️ 若脚本来源扩展到「下载 / AI 生成」，**必须补**。
 *
 * ## ⚠️ 本 object 是**纯 JVM 可测**的
 *
 * [run] 的 `context` 参数类型是 `Any?`（不是 `android.content.Context`），
 * 且不依赖任何线程/IO 设施 ⇒ 可以在普通 JVM 单测里真跑 Rhino（实测可行）。
 * 这是「handler 跑在 LSPosed ClassLoader 里、App 侧测不到」的破解方式：
 * **把可判定的部分抽出来，别把逻辑埋在 handler 里**。
 */
object ScriptExecutor {

    /** 脚本在 `evaluateString` 里的**源名** —— 会出现在 Rhino 的报错与栈里。 */
    const val SOURCE_NAME = "xposed_js"

    /**
     * 脚本里注入 `inputs` 的变量名。
     *
     * ⚠️ 与 App 侧 `JsExecutor` 的 `"inputs"` **刻意保持一致** ——
     * 从 ShortX / Auto.js 移植的脚本、以及用户在两个模块之间搬运的脚本，
     * 不需要改这个引用。
     */
    const val INPUTS_VAR = "inputs"

    /** 脚本里注入 system_server Context 的变量名（**provider 为 null 时不注入**）。 */
    const val CONTEXT_VAR = "context"

    /**
     * 脚本执行的产出。
     *
     * ## ⚠️ 为什么是密封类而不是「抛异常」
     *
     * 与 `CapabilityOutcome` 同源的理由：**「超时」与「脚本自己报错」必须能被区分**。
     * 用异常表达时两者都会变成 `handler_error`，
     * 而它们的**用户处置完全不同**（超时 ⇒ `timeout`，脚本错误 ⇒ 用户改脚本）。
     */
    sealed interface Outcome {

        /**
         * 脚本正常结束。
         *
         * @property outputs 脚本返回的字典。⚠️ **值是 org.json 类型**（见类注释）。
         *   脚本无返回值（`undefined` / `null` / 函数）时为**空 Map**
         *   ⇒ 上层应当产出 `items = [{}]`（**不是**空 `items`）。
         */
        data class Ok(val outputs: Map<String, Any?>) : Outcome

        /** 第 ① 层（[ScriptSandbox] 的指令级观察器）中断。 */
        data class TimedOut(val elapsedMs: Long) : Outcome

        /**
         * 脚本自身错误（语法 / 运行时）。
         *
         * @param line Rhino 给的行号；**`0` 表示「无法定位」**（非 Rhino 异常）。
         * @param column 同上。
         */
        data class ScriptError(val message: String, val line: Int, val column: Int) : Outcome

        /**
         * `outputs` 序列化后超过上限。
         *
         * ⚠️ 这是 **handler 侧比框架更早、提示更准**的一道检查：
         * 框架的 `payload_too_large` 口径是「实现缺陷，请报告问题」，
         * 而这里的真实成因是**用户的脚本返回了过大结果** ⇒ 应给出可操作的提示。
         */
        data class TooLarge(val bytes: Int, val limit: Int) : Outcome
    }

    /**
     * 执行脚本。
     *
     * ⚠️ **跑在调用方线程上、可以阻塞**（生产路径上由
     * [com.chaomixian.vflow.xposed.capabilities.HookCapabilityRuntime] 的自建有界池投递到
     * **工作线程**，**不是** binder 线程）。
     *
     * @param script 要执行的 JavaScript 源码。
     * @param inputs 注入脚本作用域 `inputs` 的值。值的类型见 [toJs] 的说明。
     * @param context 注入成脚本里的 `context` 变量
     *   （经 [Context.javaToJS] 桥接为 Java 对象）。
     *   `null` ⇒ **不注入**（脚本里 `typeof context === "undefined"`）。
     *
     *   ⚠️ 类型是 `Any?` 而**不是** `android.content.Context` ——
     *   这让本 object 保持**纯 JVM 可测**（生产传 system_server 的 Context，
   *   单测传 null 或任意假对象）。顺带也让它**不引任何 `android.*`**，
   *   不必去动 `WireLayerPurityTest` 的 `ANDROID_ALLOWLIST`。
     * @param budgetMs 第 ① 层超时预算（毫秒）。由 handler 从 `request.timeoutMs` 取。
     * @param maxResultBytes `outputs` 的字节上限（UTF-8，与框架同口径）。
     */
    fun run(
        script: String,
        inputs: Map<String, Any?>,
        context: Any?,
        budgetMs: Long,
        maxResultBytes: Int,
    ): Outcome {
        // ⚠️ 沙箱在**执行前**建立，但 `arm()` 会重置 deadline ——
        // 见 ScriptSandbox.arm 的说明（环境装配的时间不应算进脚本预算）。
        val sandbox = ScriptSandbox(budgetMs)

        val cx = try {
            sandbox.enterContext()
        } catch (t: Throwable) {
            return Outcome.ScriptError(message = describe(t), line = 0, column = 0)
        }

        try {
            // ⚠️⚠️ 必须 `setOptimizationLevel(-1)`（解释模式）。
            // 方案 §1 的 E5 记录了原因，且有回归锁（见 ScriptSandboxTest）。
            @Suppress("DEPRECATION")
            cx.setOptimizationLevel(-1)
            cx.setInstructionObserverThreshold(ScriptSandbox.DEFAULT_STEP)

            // 用 ImporterTopLevel 而非 initStandardObjects()：前者额外提供
            // importClass / importPackage 两个 Java 互操作入口，与 App 侧 JsExecutor 一致。
            // 其构造函数内部已初始化标准对象，无需再调 initStandardObjects()。
            val scope = ImporterTopLevel(cx)

            injectInputs(cx, scope, inputs)

            if (context != null) {
                ScriptableObject.putProperty(scope, CONTEXT_VAR, Context.javaToJS(context, scope))
            }

            // console（浏览器习语）。缺失时从 ShortX / Auto.js 移植的脚本会抛
            // ReferenceError 或被 try/catch 静默吞掉（表现为「脚本跑了但什么都没发生」）。
            JsConsole.install(cx, scope)

            // ⚠️⚠️ 这里**刻意不调用** injectVFlowModules —— 见类注释的「定义性差别」。
            // 加回来会让 `ScriptExecutorTest` 的 `typeof vflow` 用例变红。

            // ★ deadline 重新以「即将执行」为起点 —— 环境装配的时间不该吃掉脚本预算
            sandbox.arm()
            val started = System.currentTimeMillis()

            val result = cx.evaluateString(scope, script, SOURCE_NAME, 1, null)
            val elapsedMs = System.currentTimeMillis() - started

            val outputs = outputsOf(cx, scope, result)
            val bytes = ResultBudget.byteSizeOf(JSONObject(outputs).toString())
            if (bytes > maxResultBytes) {
                return Outcome.TooLarge(bytes = bytes, limit = maxResultBytes)
            }
            return Outcome.Ok(outputs)
        } catch (t: Throwable) {
            // ⚠️ 超时判定**不看异常类型**，看沙箱的标志位（方案 §1 的 E10：
            // 观察器抛出的异常穿过 Rhino 后未必还是原类型）。
            if (sandbox.timedOut) {
                return Outcome.TimedOut(elapsedMs = sandbox.elapsedSinceArm())
            }
            return scriptErrorOf(t)
        } finally {
            // ⚠️ 与 sandbox.enterContext() 配对。**不配对**会让 Rhino 的线程局部
            // Context 泄漏 —— 在 system_server 的工作线程上反复泄漏是实打实的资源问题。
            try {
                Context.exit()
            } catch (_: Throwable) {
            }
        }
    }

    // ── 返回值 → outputs ──────────────────────────────────────

    /**
     * 把 `evaluateString` 的返回值转成 `outputs` 字典。
     *
     * ## 形状（**与方案 §3.4 的一处偏差，见交付说明**）
     *
     * | JS 返回值 | `outputs` | 依据 |
     * |---|---|---|
     * | 对象字面量 `{a:1}` | `{a:1}`（**零转换**，键就是脚本给的键） | 方案 §3.4 拍板 A |
     * | 数组 / 标量 | `{"result": <值>}` | ⚠️ **偏差** —— 见下 |
     * | `undefined` / `null` / 函数 | `{}`（空 Map） | 方案 §3.4（「无返回值 ⇒ `items = [{}]`」） |
     *
     * ## ⚠️ 为什么数组/标量包成 `{"result": …}` 而不是丢成空 Map
     *
     * 方案 §3.4 的原文是「脚本无返回值 / **返回非对象** ⇒ `outputs = emptyMap()`」。
     * 但「非对象」按字面执行会把 `Date.now()`（标量）或 `[1,2,3]`（数组）的返回值
     * **静默丢掉** —— 用户看到 `{}` 会去查脚本，而脚本没错。这正是本仓库反复记录、
     * 明令要避免的「静默变差」形态。
     *
     * 故对**有值但非对象**的返回走 `{"result": …}`。三条依据：
     *
     * 1. **App 侧先例**：[com.chaomixian.vflow.core.execution.JsExecutor] 对
     *    `NativeArray` / 标量**正是**产出 `mapOf("result" to …)`。两个模块
     *    （`vflow.system.js` 与 `vflow.xposed.js`）语义对齐，用户搬运脚本时不必适应两套。
     * 2. **不丢信息**：`{}` 与 `{"result": …}` 相比，后者严格更接近脚本的原意。
     * 3. **不破坏方案契约**：`items[0]` 仍然是「脚本返回的字典」，没有出现
     *    `"outputs"` 之类的包装键（那才是方案真正要避免的）。
     *
     * `undefined` / `null` / 函数**仍然**是空 Map —— 那是真的「什么都没返回」。
     */
    private fun outputsOf(cx: Context, scope: Scriptable, result: Any?): Map<String, Any?> {
        if (result == null || result is Undefined) return emptyMap()

        val text = stringify(cx, scope, result)
        // 取不到 JSON 文本（函数 / 循环引用 / 取不到 JSON.stringify）
        // ⇒ 当成「没有可用的返回值」。**不抛** —— 见类注释的序列化说明。
            ?: return emptyMap()

        val value = try {
            JSONTokener(text).nextValue()
        } catch (_: Throwable) {
            return emptyMap()
        }

        return when (value) {
            // 对象字面量：**零转换**，键就是它自己的键
            is JSONObject -> topLevelOf(value)
            // ⚠️ JS 的 `null` 是「没有值」，不是「值就是 null」——
            // 与 `undefined` 同等对待（方案 §3.4：无返回值 ⇒ `{}`）。
            // 不给它造一个 `{"result": null}` 的键。
            JSONObject.NULL -> emptyMap()
            // 数组 / 标量：包成 `{"result": …}`（见本函数的 KDoc）
            else -> mapOf("result" to value)
        }
    }

    /**
     * 取 `JSONObject` 的**顶层浅表**，值原样保留 org.json 类型。
     *
     * ⚠️ 不做递归深转 —— 理由见 [ScriptRequest] 类注释：
     * 递归转换的 `null` 处理是本仓库踩过的坑（`JSONObject` **不实现** `Map`，
     * `as? Map<*, *>` 恒为 null），而**原样保留反而完全无损**。
     */
    private fun topLevelOf(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            // ⚠️ `opt`（不是 `get`）：JSON null 返回 `JSONObject.NULL` 哨兵对象，
            // **不是** Java null —— 这正是「null 不丢键」的前提。
            out[key] = obj.opt(key)
        }
        return out
    }

    /**
     * 用脚本作用域里的 `JSON.stringify` 渲染（取法照
     * [JsConsole] 的 `resolveStringify`：**从 scope 的 `JSON` 对象上取函数引用**）。
     *
     * 取不到引用、或该值 JSON 无法表达（函数 / `undefined` / 循环引用）时返回 null。
     */
    private fun stringify(cx: Context, scope: Scriptable, value: Any?): String? = runCatching {
        val json = scope.get("JSON", scope) as? Scriptable ?: return null
        val fn = json.get("stringify", json) as? org.mozilla.javascript.Function ?: return null
        val text = fn.call(cx, scope, json, arrayOf<Any?>(value))
        if (text == null || text is Undefined) null else text.toString()
    }.getOrNull()

    // ── inputs 注入 ───────────────────────────────────────────

    /**
     * 把 `inputs` 注入作用域。
     *
     * ⚠️ 每个值经 [toJs] 转换 —— 脚本侧看到的是 **JS 值**（object / array / 基础类型），
     * 而不是 Java 对象。
     */
    private fun injectInputs(cx: Context, scope: Scriptable, inputs: Map<String, Any?>) {
        val obj = cx.newObject(scope)
        inputs.forEach { (key, value) ->
            obj.put(key, obj, toJs(cx, scope, value))
        }
        ScriptableObject.putProperty(scope, INPUTS_VAR, obj)
    }

    /**
     * Kotlin 值 → JS 值。
     *
     * ## 支持的类型
     *
     * | 输入 | JS 侧 |
     * |---|---|
     * | `null` / [JSONObject.NULL] | JS `null` |
     * | [JSONObject] | 普通对象（递归） |
     * | [JSONArray] | 数组（递归） |
     * | `String` / 数字 / `Boolean` | 原样 |
     * | [Map] / [Iterable] / `Array` | 对象 / 数组（递归） |
     * | 其余 | `toString()`（**兜底，不抛**） |
     *
     * ⚠️ 必须显式判 [JSONObject] / [JSONArray]：它们**不实现** `Map` / `List`（实测），
     * 只写 `is Map<*, *>` 分支会让它们掉进 `toString()` 兜底分支 ——
     * 而那个失败是**静默的**（脚本拿到的会是一段 JSON 文本而不是对象）。
     *
     * ⚠️ 与 App 侧的 `JsValueConverter` **刻意不复用**：那个在 `core.` 包下
     *（命中 `FORBIDDEN_APP_PACKAGES`），且它认识 `VString` / `VDictionary` 等
     * 一堆 vFlow 业务类型 —— 本执行器的 inputs 只来自 JSON，不需要那些。
     */
    @Suppress("UNCHECKED_CAST")
    private fun toJs(cx: Context, scope: Scriptable, value: Any?): Any? = when (value) {
        null, JSONObject.NULL -> null

        is JSONObject -> {
            val obj = cx.newObject(scope)
            val keys = value.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                obj.put(key, obj, toJs(cx, scope, value.opt(key)))
            }
            obj
        }

        is JSONArray -> {
            val arr = cx.newArray(scope, value.length())
            for (i in 0 until value.length()) {
                arr.put(i, arr, toJs(cx, scope, value.opt(i)))
            }
            arr
        }

        is Map<*, *> -> {
            val obj = cx.newObject(scope)
            value.forEach { (k, v) -> obj.put(k.toString(), obj, toJs(cx, scope, v)) }
            obj
        }

        is Iterable<*> -> {
            val list = value.toList()
            val arr = cx.newArray(scope, list.size)
            list.forEachIndexed { i, v -> arr.put(i, arr, toJs(cx, scope, v)) }
            arr
        }

        is Array<*> -> {
            val arr = cx.newArray(scope, value.size)
            value.forEachIndexed { i, v -> arr.put(i, arr, toJs(cx, scope, v)) }
            arr
        }

        else -> value
    }

    // ── 错误 ──────────────────────────────────────────────────

    /**
     * 任意 [Throwable] → [Outcome.ScriptError]。
     *
     * ⚠️ 用 [RhinoException.details] 而不是 `message`：前者会带上源码片段与位置标记，
     * 在「脚本第 3 行到底哪里错了」这个问题上信息量高得多
     *（`JavaScriptException` 的 `message` 通常只是 `Error: boom`）。
     */
    private fun scriptErrorOf(t: Throwable): Outcome.ScriptError = when (t) {
        is RhinoException -> Outcome.ScriptError(
            message = t.details().takeIf { it.isNotBlank() } ?: describe(t),
            // ⚠️ Rhino 的行号从 1 起；取不到时给 0（= 无法定位，由上层决定怎么显示）
            line = t.lineNumber().coerceAtLeast(0),
            column = t.columnNumber().coerceAtLeast(0),
        )

        else -> Outcome.ScriptError(message = describe(t), line = 0, column = 0)
    }

    /** `类型名: 消息`，`message` 为 null 时只给类型名（避免拼出 `XxxException: null`）。 */
    private fun describe(t: Throwable): String =
        if (t.message == null) t.javaClass.simpleName else "${t.javaClass.simpleName}: ${t.message}"
}
