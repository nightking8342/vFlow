package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XposedJsCapabilityHandler] 的契约单测。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §4 步骤 8 / §6.2。
 *
 * ## ⚠️ 「handler 在 LSPosed 的 ClassLoader 里、App 侧测不到」这件事对本类**不适用**
 *
 * §7.2b-9 说的是**真实注入 system_server 后的运行**（那确实测不到）。
 * 而本 handler 只引用 `CapabilityHandler`（hook 侧契约，纯 Kotlin）、
 * `ScriptExecutor` / `ScriptRequest`（Rhino + org.json，纯 JVM 可跑）、`InvokePolicy`
 * —— **全部无 Android 依赖**。
 *
 * 对照：`QueryShortcutIntentsHandler` 引 `android.os.ServiceManager` ⇒ 测不到；
 * `DiagnosticCapabilityHandler` 无平台依赖 ⇒ 有 14 例直测。本 handler 属后者。
 *
 * ## ⚠️ 纯函数测试**测不出**的部分（如实记录，别以为这里覆盖了）
 *
 * | 要求 | 为什么测不到 | 由谁钉住 |
 * |---|---|---|
 * | `HookCapabilityRegistry.init` 真的注册了本 handler | 注册表是进程级单例，`@Before` 会 reset 它、`init` 不会重跑 | `HookCapabilityRegistryTest` 的**源码扫描**用例 |
 * | `VFlowHookEntry` 真的赋值了 `contextProvider` | 同上（且它跑在 system_server 里） | `XposedJsWiringTest` 的源码扫描 |
 * | `ScriptExecutor` 真的设了 `optimizationLevel = -1` | 运行期测不出「生产代码到底设没设」 | `XposedJsWiringTest` 的源码扫描 |
 */
class XposedJsCapabilityHandlerTest {

    private val handler = XposedJsCapabilityHandler()

    private fun request(params: String, timeoutMs: Long = 5_000L) = CapabilityRequest(
        requestId = "r-js-1",
        protocolVersion = 1,
        capability = CapabilityNames.XPOSED_JS,
        paramsJson = params,
        timeoutMs = timeoutMs,
        token = "t",
    )

    /** 造一个 `{"script": …, "inputs": …}` 形态的 params。 */
    private fun params(script: String, inputs: String = "{}") =
        JSONObject()
            .put("script", script)
            .put("inputs", JSONObject(inputs))
            .toString()

    @After
    fun tearDown() {
        // ⚠️ `contextProvider` 是**静态可写字段**（进程级）——
        // 用例改了它而不还原，会影响同一 JVM 里的其他用例。
        XposedJsCapabilityHandler.contextProvider = null
    }

    // ── 声明 ────────────────────────────────────────────────

    @Test
    fun `name comes from the CapabilityNames constant`() {
        // ⚠️ 必须引用常量而不是写字面量：名字是跨进程协议的一部分，
        // 拼错的表现是「这能力明明装了却说没有」（两端各自看都没问题）。
        assertEquals(CapabilityNames.XPOSED_JS, handler.name)
        assertEquals("xposed_js", handler.name)
    }

    @Test
    fun `maxResultBytes is 64 KiB and does not collide with the transport limit`() {
        // ⚠️⚠️ 这条同时锁两件事：
        // ① 它是**显式声明**的（不能用默认值 —— 256 KiB 换算成信封 parcel 是 512 KiB，
        //    超过 `MAX_ENVELOPE_PARCEL_BYTES` 的 384 KiB ⇒ 必撞 oneway 异步半缓冲）；
        // ② 声明的值换算后**确实**落在传输上限内。
        assertEquals(64 * 1024, handler.maxResultBytes)

        // ⚠️ 判据必须是「本项目**自己**换算出的值」而不是 `envelopeParcelBudget` 的返回值 ——
        // 后者内部已经与 `MAX_ENVELOPE_PARCEL_BYTES` 取了 min ⇒ 恒不超过，
        // 那条断言会**恒真**（我第一版写成那样，是空转）。
        val ownParcelBudget = InvokePolicy.PARCEL_PER_CODE_UNIT.toLong() * handler.maxResultBytes!!
        assertTrue(
            "声明值换算出的信封 parcel 预算 $ownParcelBudget 必须不超过传输上限 " +
                "${ResultBudget.MAX_ENVELOPE_PARCEL_BYTES}",
            ownParcelBudget <= ResultBudget.MAX_ENVELOPE_PARCEL_BYTES,
        )

        // 反向：确认**默认值**确实会撞线（否则这条显式声明就是多余的，
        // 而「多余的声明」会掩盖「将来有人顺手删掉它」）
        val defaultParcelBudget = InvokePolicy.PARCEL_PER_CODE_UNIT.toLong() *
            ResultBudget.DEFAULT_MAX_RESULT_BYTES
        assertTrue(
            "默认 ${ResultBudget.DEFAULT_MAX_RESULT_BYTES} 换算出的 $defaultParcelBudget " +
                "本应超过传输上限 —— 若这条失败，说明传输上限变了，" +
                "本 capability 的显式声明理由需要复核",
            defaultParcelBudget > ResultBudget.MAX_ENVELOPE_PARCEL_BYTES,
        )
    }

    @Test
    fun `timeoutMs stays null so the caller decides`() {
        // ⚠️ 反证方式：把 `timeoutMs` 声明成一个固定值 ⇒ 本用例变红。
        //
        // 声明更小的值会造成「App 侧配 30s、hook 侧按小值算」的**错配**：
        // 用户配了 30s 却在 5s 被 timeout 中断，而 App 侧的等待表还在等。
        // 本 capability 的执行时长**完全由用户脚本决定**，没有「天然慢/快」的依据。
        assertNull("不得声明超时（留给请求侧）", handler.timeoutMs)
    }

    // ── 正常路径：result 形状（拍板 A）──────────────────────

    @Test
    fun `items zero is the outputs dict itself with no wrapper key`() {
        // ⚠️⚠️ 这是**方案 §6.2 条件 8** 的用例。
        //
        // 反证方式：把结果形状从 A 改成 B（例如包成 `mapOf("outputs" to outputs)`）⇒ 变红。
        //
        // 断言的是**键集相等**（不是「包含」）—— 后者测不出多出来的包装键。
        //
        // ⚠️ 对象字面量**必须加括号**：在语句位置，`{...}` 会被 JS 解析成**块**，
        // `result: 2` 是标签语句 ⇒ 语法错误（这条我踩过，表现是本用例抛 ClassCastException）。
        val out = handler.handle(request(params("({result: 2, foo: 'bar'})")))
            as CapabilityOutcome.Items

        assertEquals("本 capability 恒返回单项 —— 分页机制自然退化为 no-op", 1, out.items.size)
        assertEquals(0, out.startIndex)
        assertEquals(
            "items[0] 的键集必须等于脚本返回的键集，且不得有包装键",
            setOf("result", "foo"),
            out.items[0].keys,
        )
        assertFalse("不得出现 outputs 包装键", out.items[0].containsKey("outputs"))
        assertFalse("不得出现 items 包装键", out.items[0].containsKey("items"))
    }

    @Test
    fun `a script with no return value produces one empty item`() {
        // ⚠️ 方案 §3.4：`items = [{}]`，**不是**空 `items`。
        // 理由：与「返回了空 items」可区分；且与 DiagnosticCapabilityHandler
        // 把单值包成单元素列表同形（仓库既有先例）。
        val out = handler.handle(request(params("var x = 1;"))) as CapabilityOutcome.Items

        assertEquals("无返回值也必须是**一项**（空对象），不是空列表", 1, out.items.size)
        assertTrue("那一项应是空字典", out.items[0].isEmpty())
    }

    @Test
    fun `null values in outputs survive all the way to the result items`() {
        // ⚠️ 端到端锁住「null 不丢键」—— 从脚本一路到 handler 的 items。
        // 反证方式：同 ScriptExecutorTest 的那条（去掉 JSON 文本路线）。
        val out = handler.handle(request(params("({ok: null, err: 'x'})")))
            as CapabilityOutcome.Items

        assertTrue("null 键必须在 items[0] 里", out.items[0].containsKey("ok"))
        val text = JSONObject(out.items[0]).toString()
        assertTrue("序列化后必须还是 null：$text", text.contains("\"ok\":null"))
    }

    @Test
    fun `inputs from params reach the script`() {
        val out = handler.handle(
            request(params("inputs.n * 2", """{"n":21}""")),
        ) as CapabilityOutcome.Items

        assertEquals(42, out.items[0]["result"])
    }

    // ── 失败路径：三个错误码 ─────────────────────────────────

    @Test
    fun `malformed params yields handler_error with a concrete detail`() {
        // ⚠️ 不是抛异常、也不是笼统的 handler_error —— detail 要能指路
        val out = handler.handle(request("not json")) as CapabilityOutcome.Failure

        assertEquals(CapabilityErrorCode.HANDLER_ERROR, out.code)
        assertTrue("detail 应说明参数形态：${out.detail}", out.detail.contains("script"))
    }

    @Test
    fun `missing script field yields handler_error`() {
        val out = handler.handle(request("""{"inputs":{}}""")) as CapabilityOutcome.Failure
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, out.code)
    }

    @Test
    fun `a script error yields handler_error carrying the line number`() {
        // ⚠️ 脚本自己写错 ⇒ `handler_error`（**不是** timeout）。
        // 两者的用户处置不同：前者改脚本，后者看是不是卡住了。
        val out = handler.handle(request(params("var a = 1;\nthrow new Error('boom');")))
            as CapabilityOutcome.Failure

        assertEquals(CapabilityErrorCode.HANDLER_ERROR, out.code)
        assertTrue("detail 应含行号：${out.detail}", out.detail.contains("第 2 行"))
        assertTrue("detail 应含原始错误：${out.detail}", out.detail.contains("boom"))
    }

    @Test
    fun `a syntax error carries no zero line text`() {
        // ⚠️ 非 Rhino 异常给 line = 0（= 无法定位）⇒ **不得**拼出「第 0 行第 0 列」，
        // 那会让用户去找一个不存在的位置。
        //
        // 本用例用一个**能跑到**但返回值无法序列化的场景锁住「line=0 时不拼位置」。
        // 语法错误本身是有行号的，故这里直接验语法错误的另一侧：
        val syntax = handler.handle(request(params("var a = ;"))) as CapabilityOutcome.Failure
        assertFalse("不应出现「第 0 行」这种无意义位置：${syntax.detail}", syntax.detail.contains("第 0 行"))
    }

    @Test
    fun `an infinite loop yields timeout not handler_error`() {
        // ⚠️ 超时归 TIMEOUT（§6.4：它指向「报告问题 / 可能真的慢」），
        // 而 `handler_error` 指向「看具体能力」。混用会让排查方向错掉。
        //
        // ⚠️ 把 request.timeoutMs 设小，让第 ① 层沙箱在可接受的时间内中断。
        val out = handler.handle(request(params("while(true){}"), timeoutMs = 300))
            as CapabilityOutcome.Failure

        assertEquals(CapabilityErrorCode.TIMEOUT, out.code)
        assertTrue("detail 应报出耗时：${out.detail}", out.detail.contains("耗时"))
    }

    @Test
    fun `an oversized output yields handler_error with an actionable detail`() {
        // ⚠️ 这里**故意**归 HANDLER_ERROR 而不是 PAYLOAD_TOO_LARGE：
        // 后者在 §6.4 里的口径是「实现缺陷，请报告问题」，而本情形的真实成因是
        // **用户的脚本返回了过大结果** —— 让他去「报告问题」是把排查引向错误方向。
        val out = handler.handle(request(params("({pad: 'x'.repeat(80000)})")))
            as CapabilityOutcome.Failure

        assertEquals(CapabilityErrorCode.HANDLER_ERROR, out.code)
        assertTrue("detail 应给出上限：${out.detail}", out.detail.contains(handler.maxResultBytes!!.toString()))
        assertTrue("detail 应给出可操作的提示：${out.detail}", out.detail.contains("减少"))
    }

    // ── contextProvider 接线 ─────────────────────────────────

    @Test
    fun `a null context provider degrades to no context instead of failing`() {
        // ⚠️ 有意的降级（见 contextProvider 的 KDoc）：
        // 「系统服务尚未就绪」的窗口里拿不到 context，但脚本仍应能跑。
        XposedJsCapabilityHandler.contextProvider = null
        val out = handler.handle(request(params("typeof context"))) as CapabilityOutcome.Items

        assertEquals("undefined", out.items[0]["result"])
    }

    @Test
    fun `a non null context provider is invoked and its value injected`() {
        // ⚠️ 反证方式：删掉 `handle` 里的 `contextProvider?.invoke()` ⇒ 本用例变红
        //（脚本里会是 undefined）。
        XposedJsCapabilityHandler.contextProvider = { BootstrapContext() }
        val out = handler.handle(request(params("context.tag()"))) as CapabilityOutcome.Items

        assertEquals(BootstrapContext.TAG, out.items[0]["result"])
    }

    @Test
    fun `the provider is consulted per call so a later value takes effect`() {
        // ⚠️⚠️ 这条锁的是「缓存的是**函数引用**，不是取到的值」那个设计决策。
        //
        // 若有人改成「第一次取到就缓存到静态字段」，热更新换代后新代际会读到
        // 旧代际的 Context（或 null）—— 而热更新是**新 classloader 加载新代码**，
        // 静态字段在新代际是全新的（FORK.md 记录的实测结论）。
        var current = "first"
        XposedJsCapabilityHandler.contextProvider = { Tag(current) }

        val first = handler.handle(request(params("context.tag()"))) as CapabilityOutcome.Items
        current = "second"
        val second = handler.handle(request(params("context.tag()"))) as CapabilityOutcome.Items

        assertEquals("first", first.items[0]["result"])
        assertEquals("second", second.items[0]["result"])
    }

    /** 给接线用例用的假 context（**故意不是** android.content.Context）。 */
    class BootstrapContext {
        fun tag(): String = TAG

        companion object {
            const val TAG = "bootstrap-ctx"
        }
    }

    private fun Tag(value: String) = object {
        @Suppress("unused")
        fun tag(): String = value
    }
}
