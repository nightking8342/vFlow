package com.chaomixian.vflow.core.execution

import com.chaomixian.vflow.core.logging.DebugLogger
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined

/**
 * 注入到 JS 作用域的 `console` 对象。
 *
 * ## 为什么需要它
 *
 * 从 ShortX / Auto.js 移植过来的脚本普遍以 `console.log(...)` 打日志。
 * 在此之前 vFlow 的作用域里**没有这个对象** —— 脚本会直接抛
 * `ReferenceError: "console" is not defined`（若在 `try` 之外），
 * 或者被 `try/catch` 静默吞掉（若在 `try` 之内，表现为「脚本跑了但什么都没发生」）。
 *
 * 方法集对齐 ShortX 的 `KotlinConsole`（11 个），照浏览器控制台习语实现：
 * `log` / `info` / `warn` / `error` / `debug` / `println` /
 * `group` / `groupEnd` / `time` / `timeEnd` / `count` / `countReset`。
 *
 * ## 与 ShortX 的差异（有意为之）
 *
 * ShortX 的 `group` 用缩进层级渲染。本实现只保留**语义**（层级计数），不渲染缩进 ——
 * vFlow 的日志出口是 `DebugLogger`（logcat + 文件），缩进在单行日志里没有意义，
 * 加了反而让 `grep` 变难。标签统一附在消息前，便于按标签过滤。
 *
 * ## 生命周期
 *
 * 每次脚本执行**新建一个实例**（作用域是 per-execution 的），因此
 * `timers` / `counters` 不需要并发保护。
 * ⚠️ **不要**把它做成跨执行复用的单例 —— 计时器与计数器会串。
 *
 * @param tag 日志 TAG，用于在 logcat 里区分来源。
 */
class JsConsole(
    private val tag: String = DEFAULT_TAG,
    /**
     * `JSON.stringify` 的引用，由 [install] 在注入时取一次。
     *
     * ⚠️ **每次调用都 `initStandardObjects()` 建临时作用域是错的** —— 那会在
     * 每条日志上重建整套标准内建（实测约 700 个类的对象树）。这里持有引用即可。
     */
    private val stringify: org.mozilla.javascript.Function? = null,
) {

    companion object {
        const val DEFAULT_TAG = "JsScript"

        /** 把 `console` 注入到指定作用域。注入为普通属性（非 const），脚本可覆盖它。 */
        fun install(context: Context, scope: Scriptable, tag: String = DEFAULT_TAG) {
            // 在这里取一次 `JSON.stringify` 并把引用存起来（见 jsonify 的注释：不每次新建 scope）。
            val stringify = resolveStringify(context, scope)
            val console = JsConsole(tag, stringify)
            val obj = context.newObject(scope)

            // 日志输出。
            // ⚠️ 用 lambda 显式转发，不写 `console::log` —— 方法引用会与
            // `Scriptable.log` 之类的同名成员混淆，导致 receiver 类型不匹配（已踩过）。
            obj.defineFunction("log") { console.log(it) }
            obj.defineFunction("info") { console.info(it) }
            obj.defineFunction("warn") { console.warn(it) }
            obj.defineFunction("error") { console.error(it) }
            obj.defineFunction("debug") { console.debug(it) }
            obj.defineFunction("println") { console.log(it) }   // 浏览器习语：等价 log

            // 分组（语义保留，不渲染缩进，见类注释）
            obj.defineFunction("group") { console.group(it) }
            obj.defineFunction("groupEnd") { console.groupEnd(it) }

            // 计时
            obj.defineFunction("time") { console.time(it) }
            obj.defineFunction("timeEnd") { console.timeEnd(it) }

            // 计数
            obj.defineFunction("count") { console.count(it) }
            obj.defineFunction("countReset") { console.countReset(it) }

            ScriptableObject.putProperty(scope, "console", obj)
        }

        /**
         * 从作用域里取 `JSON.stringify` 的引用。
         *
         * 作用域由 `ImporterTopLevel(context)` 或 `initStandardObjects()` 建立，
         * 两条路径都会装入标准内建，故 `JSON` 一定存在。
         * 取不到时返回 null，渲染逻辑会降级为 `Context.toString()`。
         */
        private fun resolveStringify(context: Context, scope: Scriptable): org.mozilla.javascript.Function? {
            return runCatching {
                val json = scope.get("JSON", scope) as? Scriptable ?: return null
                json.get("stringify", json) as? org.mozilla.javascript.Function
            }.getOrNull()
        }

        /**
         * 把 Kotlin 函数定义成 JS 函数。
         *
         * ⚠️ 参数用 `Array<Any?>` 承载 —— Rhino 的 `BaseFunction.call` 给的就是这个类型。
         * **不要在函数签名上写 `vararg`**（与 `call` 的签名对不上，运行期会失败）。
         *
         * 接收者用 `Scriptable`（而非 `ScriptableObject`）—— `Context.newObject()` 的
         * 返回类型是 `Scriptable`，用 `ScriptableObject` 会因 receiver 不匹配而编译失败。
         */
        private fun Scriptable.defineFunction(
            name: String,
            body: (Array<Any?>) -> Unit,
        ) {
            val fn = object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<Any?>,
                ): Any? {
                    body(args)
                    return Undefined.instance
                }

                override fun getFunctionName(): String = name

                override fun getArity(): Int = 1
            }
            put(name, this, fn)
        }

        /**
         * 把 JS 传进来的任意值转成可读文本。
         *
         * ⚠️ **实测结论（本机 Rhino 1.9.0，2026-09-27）**：
         * 只有 `JSON.stringify` 能正确渲染 JS 对象/数组：
         *
         * | 输入 | `Context.toString()` | `JSON.stringify()` |
         * |---|---|---|
         * | `{x:1,y:'z'}` | ❌ `[object Object]` | ✅ `{"x":1,"y":"z"}` |
         * | `[1,2,3]` | ⚠️ `1,2,3`（无括号） | ✅ `[1,2,3]` |
         * | `[{a:1}]` | ❌ `[object Object]` | ✅ `[{"a":1}]` |
         * | `'plain'` | ✅ `plain` | ⚠️ `"plain"`（带引号） |
         *
         * 所以策略是**分级**：字符串/数字/布尔/null 直接转（避免多余引号），
         * 其余一律走 `JSON.stringify`（拿不到时降级为 `Context.toString`）。
         *
         * 实现落在实例上（需要持有 `stringify` 引用，见 [JsConsole.stringify]）。
         */
    }

    // 分组层级：保留语义（与 ShortX 行为对齐），不参与输出渲染
    private var groupLevel = 0

    // 计时器 / 计数器：per-execution，无需并发保护（见类注释）
    private val timers = mutableMapOf<String, Long>()
    private val counters = mutableMapOf<String, Int>()

    /**
     * 用持有的 `JSON.stringify` 引用渲染。取不到引用、或该值 JSON 无法表达
     * （函数 / `undefined`）时返回 null，由 [renderOne] 降级为 `Context.toString()`。
     */
    private fun jsonify(value: Any?): String? {
        val fn = stringify ?: return null
        return runCatching {
            val cx = Context.getCurrentContext() ?: return null
            // 这里的作用域只用于满足 call 的签名；stringify 不依赖它
            val result = fn.call(cx, fn.parentScope, fn, arrayOf<Any?>(value))
            if (result is Undefined || result == null) null else result.toString()
        }.getOrNull()
    }

    private fun renderOne(arg: Any?): String = when (arg) {
        null -> "null"
        is Undefined -> "undefined"
        is CharSequence, is Number, is Boolean -> arg.toString()
        else -> jsonify(arg) ?: Context.toString(arg)
    }

    private fun render(args: Array<Any?>): String {
        if (args.isEmpty()) return ""
        return args.joinToString(" ") { renderOne(it) }
    }

    fun log(args: Array<Any?>) = safe { DebugLogger.d(tag, render(args)) }
    fun info(args: Array<Any?>) = safe { DebugLogger.i(tag, render(args)) }
    fun warn(args: Array<Any?>) = safe { DebugLogger.w(tag, render(args)) }
    fun error(args: Array<Any?>) = safe { DebugLogger.e(tag, render(args)) }
    fun debug(args: Array<Any?>) = safe { DebugLogger.d(tag, render(args)) }

    fun group(args: Array<Any?>) {
        safe { DebugLogger.d(tag, "[group] ${render(args)}") }
        groupLevel++
    }

    fun groupEnd(args: Array<Any?>) {
        if (groupLevel > 0) groupLevel--
    }

    fun time(args: Array<Any?>) {
        // 浏览器语义：无标签时用 "default"
        timers[render(args).ifBlank { "default" }] = System.currentTimeMillis()
    }

    fun timeEnd(args: Array<Any?>) {
        val label = render(args).ifBlank { "default" }
        val start = timers.remove(label)
        if (start == null) {
            // 与浏览器一致：未启动的计时器给出提示，而不是静默
            safe { DebugLogger.w(tag, "[time] $label: 计时器未启动") }
        } else {
            safe { DebugLogger.d(tag, "[time] $label: ${System.currentTimeMillis() - start} ms") }
        }
    }

    fun count(args: Array<Any?>) {
        val label = render(args).ifBlank { "default" }
        val next = (counters[label] ?: 0) + 1
        counters[label] = next
        safe { DebugLogger.d(tag, "[count] $label: $next") }
    }

    fun countReset(args: Array<Any?>) {
        counters.remove(render(args).ifBlank { "default" })
    }

    /**
     * 统一兜底。
     *
     * ⚠️ 日志失败**绝不能**让用户的脚本崩掉 —— `DebugLogger` 内部有文件 IO，
     * 理论上可抛（磁盘满 / 权限）。所以每处出口都包住。
     */
    private inline fun safe(block: () -> Unit) {
        runCatching(block)
    }
}
