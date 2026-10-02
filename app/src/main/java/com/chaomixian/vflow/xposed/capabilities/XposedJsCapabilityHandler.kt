package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.script.ScriptExecutor
import com.chaomixian.vflow.xposed.script.ScriptRequest
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityRequest

/**
 * **在 system_server 里执行 JavaScript** 的 capability（`xposed_js`）。
 *
 * 方案：`.mindfs/tasks/plan-6.md`。上位文档：`docs/fork/xposed-architecture-v2.md` §5.7。
 *
 * ## 它存在的理由：`service call` 做不到的事
 *
 * | | `service call`（UID 2000） | `vflow.core.*`（UID 2000） | **本 capability**（UID 1000） |
 * |---|---|---|---|
 * | 传基本类型参数 | ✅ | ✅ | ✅ |
 * | **构造对象参数**（`new Rect(…)`） | ❌ 只能传 null | ✅ | ✅ |
 * | **读返回值 / 链式调用** | ❌ 只有 Parcel 十六进制 | ✅ | ✅ |
 * | **读 system_server 内部对象** | ❌ | ❌ | ✅ **独有** |
 * | **签名级权限** | ❌ | ❌ | ✅ **独有** |
 * | **崩溃半径** | 一个进程 | 一个进程 | ⚠️ **整机** |
 *
 * ⇒ 是「单向 RPC」与「完整编程语言」的关系。
 *
 * ## ⚠️⚠️ 与 App 侧 `vflow.system.js` 的分工（不是同一个东西）
 *
 * 本能力**不注入 `vflow.*` 模块树** —— 脚本里 `vflow.device.toast(…)` **不存在**。
 * 这是**定义性差别**（不是省事）：本能力提供的是「UID 1000 的权限 + 同进程对象访问」，
 * 而**不是**第二个编排入口。要编排请用工作流本身。
 * 详见 [ScriptExecutor] 类注释的对照表。
 *
 * ## ⚠️ 调用侧契约（T4 真机验证 / App 侧调用方按这个断言）
 *
 * | 项 | 值 |
 * |---|---|
 * | capability 名 | **`xposed_js`**（[CapabilityNames.XPOSED_JS]） |
 * | 请求 `params` | `{"script": "<JS 源码>", "inputs": { … }}` |
 * | **成功时 result 形状** | **A** —— `result` 是 `{"items":[<outputs 字典>]}`，**`items[0]` 就是脚本返回的字典**（零转换）。脚本无返回值 ⇒ `items = [{}]`（**不是**空 items） |
 * | 失败 | `ok=false` + `error.code` ∈ `{timeout, handler_error}`（详见 [handle]） |
 * | 分页两键 | `next_cursor` / `truncated` 走**信封顶层**（框架填）；本 capability 恒返回单项 ⇒ 分页自然退化为 no-op |
 *
 * ⚠️ **App 侧拿到的 `items[0]` 是【真正的 Kotlin `Map`】，用 `as? Map<*, *>` 取是对的。**
 *
 * 生产路径：`CapabilityInvoker.classifyResponse` → `jsonObjectToMap` → **`deepConvert`**
 * （`core/xposed/CapabilityInvoker.kt:585-590`）做**递归深层转换**
 *（`JSONObject`→`Map`、`JSONArray`→`List`、`JSONObject.NULL`→`null`）。
 * 这不是可有可无的细节 —— 该转换是 2026-10-01 真机缺陷（`itemsFromLossless` 恒返回空）
 * 的结构性修复，**所有 capability 的消费者都依赖它**。
 *
 * > ⚠️⚠️ **本段曾有相反的错误说法，勿照旧读**：原写「`items[0]` 里是 org.json 类型、
 * > 用 `as? Map` 取会恒为 null、App 侧调用方按这个断言」。**那是把方向搞反了** ——
 * > [ScriptRequest] 里「保持 org.json 原始类型」讲的是 **hook 侧读 `inputs`**（App→hook 方向），
 * > 而这里是 **App 侧读 `result`**（hook→App 方向），两条路径规则不同。
 * > 若真按旧说法用 `optJSONObject(...)` 去取，会**恒 null ⇒ 输出恒空**，正好重演那个缺陷。
 *
 * ## ⚠️ 线程：跑在**工作线程**上（可以阻塞）
 *
 * 由 [HookCapabilityRuntime] 投递到**按 `thread_mode` 选出的那一档** —— **不是** binder 线程。
 * 本 handler **不需要**自己管线程，也**不要**自己起线程
 *（那会绕过池的容量控制，而池必须小正是因为它兜的是「不可中断的执行」）。
 * 见 [CapabilityHandler.handle] 的约束说明。
 *
 * ## ⚠️ `detail` 里带的是**中文文案**，但它**只给人看**
 *
 * 判断一律用 [CapabilityErrorCode]（§6.4 约束 2）。
 */
class XposedJsCapabilityHandler : CapabilityHandler {

    companion object {
        /**
         * system_server Context 的提供者。由 `VFlowHookEntry.setup()` 在
         * `rt.start()` **之前**赋值。
         *
         * ## ⚠️⚠️ 为什么是可写字段，而不是构造参数
         *
         * [HookCapabilityRegistry] 是 `object`，它的 `init` 在**首次引用时**就跑完
         *（含 `register(XposedJsCapabilityHandler())`），而 `::systemContext` 只在
         * `VFlowHookEntry.setup()` 里可得 ⇒ **构造参数传不进去**。
         *（`BinderTransport` 能接 `contextProvider = ::systemContext` 是因为它在
         * `setup()` 里 `new` 出来的。）
         *
         * ## ⚠️ 缓存的是**函数引用**，不是「取到的 Context」
         *
         * 热更新是**新 classloader 加载新代码**，静态字段在新代际里是**全新的**
         *（`FORK.md` 记录的实测结论：曾把 ClassLoader 缓存到静态字段 ⇒ 新代际读到 null）。
         * 函数引用在**同一代际内**有效，跨代际由 `setup()` 重新赋值。
         * ⇒ **绝不要**在这里缓存「上一次取到的 Context 对象」。
         *
         * ## ⚠️ 为 null 时是**不注入 context**，不是执行失败
         *
         * 这是**有意的降级**：脚本仍能跑（纯计算、`importClass` 都在），只是没有
         * `context` 变量。若当成失败，会在「系统服务尚未就绪」
         *（`onSystemServerStarting` 时 `PackageManager` 为 null，约 11 秒后才可用）
         * 的窗口里让脚本根本没法执行。
         */
        @Volatile
        var contextProvider: (() -> Any?)? = null
    }

    override val name: String = CapabilityNames.XPOSED_JS

    /**
     * `outputs` 字典的**内容**上限：**64 KiB**。
     *
     * ## 依据
     *
     * - `InvokePolicy.envelopeParcelBudget(64 KiB)` = `min(384 KiB, 2 × 64 KiB)` = **128 KiB**
     *   ⇒ **不撞** [com.chaomixian.vflow.xposed.wire.ResultBudget.MAX_ENVELOPE_PARCEL_BYTES]
     *   （384 KiB 的传输上限）。
     * - 本 capability 的结果是 **outputs 字典**（键值对），不是列表型批量数据。
     *   对照 `query_shortcut_intents` 的 128 KiB —— 它一页要装几十条快捷方式。
     * - ⚠️ **不能用默认值**：[com.chaomixian.vflow.xposed.wire.ResultBudget.DEFAULT_MAX_RESULT_BYTES]
     *   是 256 KiB，换算成信封 parcel 就是 **512 KiB > 384 KiB** ⇒ 必撞传输上限
     *   （那半边异步缓冲是**与所有其他 oneway 事务共享**的）。
     */
    override val maxResultBytes: Int = 64 * 1024

    /**
     * ## ⚠️⚠️ **刻意留 `null`** —— 不做两处超时声明
     *
     * 框架取的是 `min(request.timeoutMs, handler.timeoutMs)`
     *（[InvokePolicy.effectiveTimeoutMs]）。在这里声明一个更小的值会造成
     * **「App 侧配 30s、hook 侧按小值算」的错配** ——
     * 用户配了 30s 却在 5s 被 `timeout` 中断，而 App 侧的等待表还在等。
     *
     * 本 capability 的执行时长**完全由用户脚本决定**，没有「这个能力天然慢/快」的依据
     * ⇒ 让调用方定。
     *
     * ⚠️ 代价：脚本可能被给到 30s（上限）。这是**已被设计接受**的代价 ——
     * 配合执行档位与 [com.chaomixian.vflow.xposed.script.ScriptSandbox] KDoc 里
     * 记录的「阻塞的 Java 调用不可中断」限制。
     */
    override val timeoutMs: Long? = null

    /**
     * 执行。**跑在工作线程上**（按 `thread_mode` 选档），**可以阻塞**。
     *
     * 流程：解析 `params` → 跑脚本 → 把 [ScriptExecutor.Outcome] 映射成
     * [CapabilityOutcome]。
     *
     * ## ⚠️ 不自己抛异常
     *
     * 三类失败都走 [CapabilityOutcome.Failure]（**比抛异常好** —— 能给出具体的错误码）。
     * 框架的顶层 `try/catch(Throwable)` 仍在，但那是「兜底」，不该被当成正常路径。
     */
    override fun handle(request: CapabilityRequest): CapabilityOutcome {
        val params = ScriptRequest.decode(request.paramsJson)
            ?: return CapabilityOutcome.Failure(
                code = CapabilityErrorCode.HANDLER_ERROR,
                detail = "参数不合法：需要一个 JSON 对象，且 $${ScriptRequest.KEY_SCRIPT} 必须是字符串。" +
                    "收到的 params：${request.paramsJson.take(PARAMS_PREVIEW_CHARS)}",
            )

        // ⚠️ 把 `request.timeoutMs` **直接**当预算传给沙箱，不另算 ——
        // 框架还会做一次事后判定（第 ② 层），两者同源才能避免
        // 「沙箱按 A 中断、框架按 B 判定」的错配。
        // ⚠️⚠️ 它是**可空的**：`null` = 不超时（2026-10-02 改）。
        // 沙箱对 null 的处理是不设指令观察器阈值 ⇒ 纯计算死循环也**不会**被中断。
        val outcome = ScriptExecutor.run(
            script = params.script,
            inputs = params.inputs,
            // ⚠️ provider 为 null ⇒ 不注入 context（有意的降级，见其 KDoc）
            context = contextProvider?.invoke(),
            budgetMs = request.timeoutMs,
            maxResultBytes = maxResultBytes,
        )

        return when (outcome) {
            // ⚠️⚠️ 拍板 A：`items[0]` **就是** outputs 字典本身，**零转换**。
            // 脚本无返回值 ⇒ outputs 是空 Map ⇒ `items = [{}]`（**不是**空 items）——
            // 与「返回了空 items」可区分，且与 DiagnosticCapabilityHandler 同形。
            //
            // ⚠️ 分页两键（next_cursor / truncated）**由框架填在信封顶层**，
            // 这里**绝不**把它们塞进 result 内部 —— 塞进去那两个字段永远填不上。
            is ScriptExecutor.Outcome.Ok -> CapabilityOutcome.Items(listOf(outcome.outputs))

            is ScriptExecutor.Outcome.TimedOut -> CapabilityOutcome.Failure(
                code = CapabilityErrorCode.TIMEOUT,
                detail = "脚本执行超时：耗时 ${outcome.elapsedMs}ms。" +
                    "请检查脚本里是否有长时间循环或阻塞调用。",
            )

            is ScriptExecutor.Outcome.ScriptError -> CapabilityOutcome.Failure(
                code = CapabilityErrorCode.HANDLER_ERROR,
                detail = errorDetailOf(outcome),
            )

            // ⚠️ 早于框架兜底的一道检查，**提示更准**：
            // 框架的 payload_too_large 口径是「实现缺陷，请报告问题」，
            // 而这里的真实成因是**用户的脚本返回了过大结果** ⇒ 给可操作的提示。
            is ScriptExecutor.Outcome.TooLarge -> CapabilityOutcome.Failure(
                code = CapabilityErrorCode.HANDLER_ERROR,
                detail = "脚本输出 ${outcome.bytes} 字节，超过上限 ${outcome.limit} 字节。" +
                    "请减少返回值里的数据量（或分批返回）。",
            )
        }
    }

    /**
     * 脚本错误 → `detail`。
     *
     * ⚠️ 位置信息**只在有**的时候才拼进去：非 Rhino 异常给的是 `line = 0` / `column = 0`
     *（= 无法定位），拼出「第 0 行第 0 列」会让用户去找一个不存在的位置 ——
     * 又一个「把排查引向错误方向」。
     */
    private fun errorDetailOf(error: ScriptExecutor.Outcome.ScriptError): String =
        if (error.line > 0) {
            "脚本错误（第 ${error.line} 行第 ${error.column} 列）：${error.message}"
        } else {
            "脚本执行失败：${error.message}"
        }
}

/**
 * `params` 预览的截断长度。
 *
 * ⚠️ 存在的理由：`detail` 是自由文本，而 `params` 可能很长（脚本源码就在里面）。
 * 不截断的话，一条**报错**响应自己就能撑爆 binder 的 oneway 缓冲 ——
 * 而那会**静默丢弃**整条响应（§3.6），也就是「为了报错反而把错误丢了」。
 *
 * 512 与 [InvokePolicy.MAX_DETAIL_CHARS] 同量级，且 `InvokePolicy.sanitize`
 * 还会再加一道保险。
 */
private const val PARAMS_PREVIEW_CHARS = 512
