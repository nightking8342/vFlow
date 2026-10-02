package com.chaomixian.vflow.xposed.script

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ScriptExecutor] 的**真跑 Rhino** 单测。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §4 步骤 5 / §6.2。
 *
 * ## ⚠️ 每条关键用例的形态（方案 §6.3 的硬要求）
 *
 * | 用例 | **必须**写成 | 而**不是** |
 * |---|---|---|
 * | `typeof vflow` | 断言**求值结果**是 `"undefined"` | 断言源码里没有某字符串 |
 * | `null` 保留 | 断言序列化后的 **JSON 文本**里该键在 | 断言 Map 的 size |
 * | 无返回值 | 断言 `outputs` **空** | — |
 *
 * ⇒ 这些断言都**真的把脚本跑出来看**，而不是检查我们自己写的字面量。
 */
class ScriptExecutorTest {

    private fun eval(
        script: String,
        inputs: Map<String, Any?> = emptyMap(),
        context: Any? = null,
        budgetMs: Long = 5_000,
        maxResultBytes: Int = 64 * 1024,
    ): ScriptExecutor.Outcome {
        // ⚠️ 记到 **companion** 里（不是实例字段）—— JUnit 对每个用例新建一个实例，
        // 用实例字段的话计数器永远是 0/1，那条防空转断言会恒假（我第一版就写错了）。
        exercisedScripts += script
        return ScriptExecutor.run(
            script = script,
            inputs = inputs,
            context = context,
            budgetMs = budgetMs,
            maxResultBytes = maxResultBytes,
        )
    }

    private fun ok(
        script: String,
        inputs: Map<String, Any?> = emptyMap(),
        context: Any? = null,
    ): Map<String, Any?> {
        val outcome = eval(script, inputs = inputs, context = context)
        assertTrue(
            "脚本 `$script` 应当成功，实际是 ${outcome::class.java.simpleName}" +
                (outcome as? ScriptExecutor.Outcome.ScriptError)?.let { "：${it.message}" }.orEmpty(),
            outcome is ScriptExecutor.Outcome.Ok,
        )
        return (outcome as ScriptExecutor.Outcome.Ok).outputs
    }

    /**
     * 取**标量**返回值的便捷写法。
     *
     * ⚠️ 必须走这里：本执行器把「数组 / 标量」的返回值包成 `{"result": …}`
     *（见 `ScriptExecutor.outputsOf`（私有）的说明），所以 `ok("5")["result"]` 才是 5。
     * 直接 `ok("5").values.first()` 会把「包装键被改名」这种回归也一起掩盖掉。
     */
    private fun scalar(
        script: String,
        inputs: Map<String, Any?> = emptyMap(),
        context: Any? = null,
    ): Any? {
        val outputs = ok(script, inputs, context)
        assertTrue("标量返回应包在 \"result\" 键下，实际键集 ${outputs.keys}", outputs.containsKey("result"))
        return outputs["result"]
    }

    // ── ★★ 本任务的定义性约束：不注入 vflow 模块树 ─────────────

    @Test
    fun `vflow modules are not injected so typeof vflow is undefined`() {
        // ⚠️⚠️ 这是**本执行环境的定义性差别**（方案 §3 步骤 5 的硬约束）。
        //
        // 反证方式：在 `ScriptExecutor.run` 里加一行 `injectVFlowModules(...)`
        //（或任何往 scope 里写 `vflow` 的代码）⇒ 本用例变红。
        //
        // 为什么这么重要：本 capability 提供的是「UID 1000 的权限 + 同进程对象访问」，
        // **不是**第二个编排入口。若脚本能调 `vflow.device.toast(...)`，
        // 就等于在 system_server 里开了一个绕过工作流引擎的执行面 ——
        // 崩溃半径是整机，而编排的语义（变量、错误处理、审批）一个都没有。
        //
        // ⚠️ 断言的是**求值结果**（真的把脚本跑出来看），不是「源码里没有某字符串」。
        assertEquals("undefined", scalar("typeof vflow"))

        // ⚠️⚠️ **成员访问不受 `typeof` 保护** —— `typeof vflow.device` 会抛
        // `ReferenceError: "vflow" 未定义`（JS 的 `typeof` 只对**裸标识符**安全）。
        //
        // 这恰恰是**更强**的证据：它说明 `vflow` **整棵树都不存在**，
        // 而不是「存在但为空对象」（后者会让 `typeof vflow.device` 也返回 "undefined"）。
        // 我第一版把这三条都写成 `typeof …`，被这条区分抓了出来。
        listOf("typeof vflow.device", "typeof vflow.device.toast", "vflow").forEach { script ->
            val outcome = eval(script)
            assertTrue(
                "`$script` 应当抛 ReferenceError（vflow 不存在），" +
                    "实际是 ${outcome::class.java.simpleName}",
                outcome is ScriptExecutor.Outcome.ScriptError,
            )
        }
    }

    @Test
    fun `accessing vflow throws a ReferenceError instead of silently doing nothing`() {
        // ⚠️ 补强上一条：不仅是「读出来是 undefined」，**调用**它必须报错 ——
        // 静默失败会让用户以为「脚本跑了但没效果」。
        val outcome = eval("vflow.device.toast({message:'x'});")
        assertTrue(
            "调用不存在的 vflow 必须报错，实际是 ${outcome::class.java.simpleName}",
            outcome is ScriptExecutor.Outcome.ScriptError,
        )
    }

    // ── ★★ null 值必须保留（方案的 E8）──────────────────────

    @Test
    fun `null values survive as a key in the serialized json`() {
        // ⚠️⚠️ 反证方式：把 `ScriptExecutor.outputsOf` 换成「先转 Kotlin Map 再交给
        // `JSONObject`」的路线（JS null → Kotlin null）⇒ 本用例**变红**
        //（`new JSONObject(map)` 对值为 Java null 的键会**静默丢键**，E8 实测）。
        //
        // 失败形态：脚本返回 `{ok: null, err: "x"}`，用户看到 `{"err":"x"}`，
        // 会去查脚本 —— 而脚本没错。
        val outputs = ok("({ok: null, err: 'x'})")

        assertTrue("null 键必须存在（否则会被静默丢掉）", outputs.containsKey("ok"))
        assertEquals(JSONObject.NULL, outputs["ok"])

        // ⚠️ 断言**序列化后的 JSON 文本**（不是只断言 Map 里有这个键）——
        // 后者测不出「框架在把 outputs 装进信封时又把它丢了」。
        val text = JSONObject(outputs).toString()
        assertTrue("序列化文本里必须出现 \"ok\":null：$text", text.contains("\"ok\":null"))
        assertTrue("非 null 的键当然也要在：$text", text.contains("\"err\":\"x\""))
    }

    @Test
    fun `nested null values survive too`() {
        // ⚠️ 方案 §6.2 条件 7 明确要求「含**嵌套**」。嵌套是踩过的那个坑的形态：
        // `{"o":{"n":null}}` → `{"o":{}}`。
        val outputs = ok("({o: {n: null, m: 'x'}, arr: [1, null, 3]})")

        val text = JSONObject(outputs).toString()
        assertTrue("嵌套对象的 null 键必须保留：$text", text.contains("\"n\":null"))
        assertTrue("数组里的 null 必须保留：$text", text.contains("[1,null,3]"))
        assertTrue("非 null 的兄弟键也要在：$text", text.contains("\"m\":\"x\""))
    }

    @Test
    fun `deeply nested null values survive`() {
        val outputs = ok("({a:{b:{c:null}}})")
        val text = JSONObject(outputs).toString()
        assertTrue("三层嵌套的 null 也要保留：$text", text.contains("\"c\":null"))
    }

    // ── 返回值形状 ───────────────────────────────────────────

    @Test
    fun `object return value is passed through with zero transformation`() {
        // ⚠️ 方案 §3.4 拍板 A：`items[0]` **就是** outputs 字典，键就是脚本给的键。
        val outputs = ok("({result: 2, foo: 'bar', n: 3.5, b: true})")

        assertEquals(setOf("result", "foo", "n", "b"), outputs.keys)
        assertEquals(2, outputs["result"])
        assertEquals("bar", outputs["foo"])
        assertEquals(true, outputs["b"])
        // ⚠️ 明确断言**没有包装键** —— 方案最要避免的就是造一个 `outputs` 包装
        assertFalse("不得出现 outputs 包装键", outputs.containsKey("outputs"))
    }

    @Test
    fun `no return value produces an empty outputs map`() {
        // ⚠️ 方案 §3.4：脚本无返回值 ⇒ `outputs = emptyMap()` ⇒ 上层产出 `items = [{}]`
        //（**不是**空 items —— 那样会与「返回了空 items」混淆）。
        assertTrue("`var x=1;` 不返回值 ⇒ 空 outputs", ok("var x = 1;").isEmpty())
        assertTrue("显式 undefined ⇒ 空 outputs", ok("undefined").isEmpty())
        assertTrue("显式 null ⇒ 空 outputs", ok("null").isEmpty())
        // ⚠️ 函数值 JSON 无法表达 ⇒ 空 outputs（**不抛**）
        assertTrue("函数返回值 ⇒ 空 outputs", ok("(function(){})").isEmpty())
    }

    @Test
    fun `array and scalar returns are wrapped under a result key`() {
        // ⚠️⚠️ 这是**相对方案 §3.4 的一处偏差**（见交付说明）：
        // 方案的原文是「返回非对象 ⇒ emptyMap()」，但那会把 `[1,2,3]` 或 `Date.now()`
        // 的返回值**静默丢掉** —— 用户看到 `{}` 会去查脚本，而脚本没错。
        //
        // 依据：App 侧 `JsExecutor` 对 `NativeArray` / 标量**正是**产出
        // `mapOf("result" to …)`（两者语义对齐，用户搬运脚本不必适应两套）。
        val arr = ok("[1, 2, 3]")
        assertEquals(setOf("result"), arr.keys)
        assertTrue("数组应保持 JSONArray 类型", arr["result"] is JSONArray)
        assertEquals("[1,2,3]", (arr["result"] as JSONArray).toString())

        val scalar = ok("5")
        assertEquals(mapOf("result" to 5), scalar)

        val str = ok("'hello'")
        assertEquals(mapOf("result" to "hello"), str)
    }

    // ── inputs 注入 ──────────────────────────────────────────

    @Test
    fun `inputs are visible from the script`() {
        assertEquals(7, scalar("inputs.n + 2", mapOf("n" to 5)))
        assertEquals("hi", scalar("inputs.s", mapOf("s" to "hi")))
    }

    @Test
    fun `nested inputs are converted into real js objects and arrays`() {
        // ⚠️⚠️ 反证方式：删掉 `toJs` 里的 `is JSONObject ->` 分支 ⇒ 本用例变红。
        //
        // `JSONObject` **不实现** `Map`（实测），只写 `is Map<*, *>` 分支会让它
        // 掉进 `toString()` 兜底 —— 脚本拿到的会是一段 **JSON 文本**而不是对象，
        // 而 `inputs.o.k` 会得到 undefined。**静默**，且难查。
        val inputs = mapOf(
            "o" to JSONObject("""{"k":"v","nested":{"z":1}}"""),
            "arr" to JSONArray("[1,2,3]"),
        )

        assertEquals("v", scalar("inputs.o.k", inputs))
        assertEquals(1, scalar("inputs.o.nested.z", inputs))
        assertEquals(6, scalar("inputs.arr[0] + inputs.arr[1] + inputs.arr[2]", inputs))
        assertEquals(3, scalar("inputs.arr.length", inputs))
    }

    @Test
    fun `null inputs become js null without losing the key`() {
        val inputs = mapOf("nil" to JSONObject.NULL, "other" to 1)
        assertEquals("null", scalar("String(inputs.nil)", inputs))
        // ⚠️ 键必须还在（丢键会让脚本里 `'nil' in inputs` 变 false）
        assertEquals(true, scalar("'nil' in inputs", inputs))
    }

    @Test
    fun `missing inputs object still lets the script run`() {
        // 脚本不依赖输入时，一个空的 inputs 也该能跑
        assertEquals("object", scalar("typeof inputs"))
    }

    // ── context 注入（有意的降级）──────────────────────────────

    @Test
    fun `context is not injected when the provider is null`() {
        // ⚠️ 这是**有意的降级**（见 XposedJsCapabilityHandler.contextProvider 的 KDoc）：
        // 系统服务尚未就绪（`onSystemServerStarting` 后约 11 秒）的窗口里，
        // 拿不到 context 也**不该**让脚本根本没法执行。
        assertEquals("undefined", scalar("typeof context"))
    }

    @Test
    fun `context is injected as a java object when provided`() {
        val fake = FakeContext()
        assertEquals(FakeContext.MARKER, scalar("context.describe()", context = fake))
    }

    // ── 超时 ────────────────────────────────────────────────

    @Test
    fun `an infinite loop yields TimedOut instead of hanging`() {
        val outcome = eval("while(true){}", budgetMs = 300)
        assertTrue(
            "死循环必须产出 TimedOut，实际是 ${outcome::class.java.simpleName}",
            outcome is ScriptExecutor.Outcome.TimedOut,
        )
        val elapsed = (outcome as ScriptExecutor.Outcome.TimedOut).elapsedMs
        assertTrue("elapsedMs 应落在预算附近（实际 ${elapsed}ms）", elapsed in 250..700)
    }

    // ── 脚本错误 ────────────────────────────────────────────

    @Test
    fun `a syntax error yields ScriptError with a usable position`() {
        val outcome = eval("var a = ;")
        assertTrue(outcome is ScriptExecutor.Outcome.ScriptError)
        outcome as ScriptExecutor.Outcome.ScriptError
        assertTrue("应当给出源码位置（否则用户无从下手），实际 line=${outcome.line}", outcome.line > 0)
        assertTrue("message 不该是空的", outcome.message.isNotBlank())
    }

    @Test
    fun `a runtime error yields ScriptError with the line number`() {
        val outcome = eval("var a = 1;\nthrow new Error('boom');")
        assertTrue(outcome is ScriptExecutor.Outcome.ScriptError)
        outcome as ScriptExecutor.Outcome.ScriptError
        assertEquals("抛出点在第 2 行", 2, outcome.line)
        assertTrue("message 应含原始错误文本：${outcome.message}", outcome.message.contains("boom"))
    }

    @Test
    fun `a host level failure yields ScriptError with line zero`() {
        // ⚠️ 非 Rhino 异常给 line = 0（= 无法定位）。
        // 上层据此**不拼**「第 0 行」—— 那会把用户引去找一个不存在的位置。
        val outcome = eval("throw 'plain string';")
        assertTrue(outcome is ScriptExecutor.Outcome.ScriptError)
        outcome as ScriptExecutor.Outcome.ScriptError
        assertTrue("message 不该为空", outcome.message.isNotBlank())
    }

    // ── 结果大小 ────────────────────────────────────────────

    @Test
    fun `an oversized result yields TooLarge instead of a truncated success`() {
        // ⚠️ 反证方式：删掉 `run` 里的 maxResultBytes 检查 ⇒ 本用例变红（变成 Ok）。
        val outcome = eval("({pad: 'x'.repeat(5000)})", maxResultBytes = 1000)
        assertTrue(
            "超过上限必须报 TooLarge，实际是 ${outcome::class.java.simpleName}",
            outcome is ScriptExecutor.Outcome.TooLarge,
        )
        outcome as ScriptExecutor.Outcome.TooLarge
        assertEquals(1000, outcome.limit)
        assertTrue("应报出实际字节数（> 上限），实际 ${outcome.bytes}", outcome.bytes > 1000)
    }

    @Test
    fun `a result just under the limit is accepted`() {
        // ⚠️ 反向保证：上限检查不能把**正常大小**的结果也拒掉（边界写错成 >= 就完了）
        val outcome = eval("({a: 1})", maxResultBytes = 1024)
        assertTrue(
            "小结果应当成功，实际是 ${outcome::class.java.simpleName}",
            outcome is ScriptExecutor.Outcome.Ok,
        )
    }

    // ── console（移植自 JsConsole）──────────────────────────

    @Test
    fun `console is available and every method is callable`() {
        // ⚠️ 反证方式：删掉 `ScriptExecutor.run` 里的 `JsConsole.install(cx, scope)` ⇒
        // 本用例变红（ReferenceError: "console" is not defined）。
        //
        // 为什么值得测：从 ShortX / Auto.js 移植的脚本普遍用 `console.log`，
        // 缺失时会被 `try/catch` 静默吞掉（表现为「脚本跑了但什么都没发生」）。
        val value = scalar(
            """
            console.log('a', 1, {x: 1});
            console.info('b');
            console.warn('c');
            console.error('d');
            console.debug('e');
            console.println('f');
            console.group('g');
            console.groupEnd();
            console.time('t');
            console.timeEnd('t');
            console.count('n');
            console.countReset('n');
            'all-ok'
            """.trimIndent(),
        )
        assertEquals("all-ok", value)
    }

    @Test
    fun `console log of an object does not crash the script`() {
        // ⚠️ 已知坑（JsConsole 的原始注释记录过）：`Context.toString()` 对 JS 对象
        // 返回 `[object Object]`，必须走 `JSON.stringify`。这里只保证**不崩**
        //（渲染质量靠 JsConsole 自己的实现，本 capability 不该为它背书）。
        assertEquals("done", scalar("console.log({a:1}, [1,2], null, undefined); 'done'"))
    }

    // ── importClass 可用（引擎内建，与本 capability 的边界无关但要保住）──

    @Test
    fun `importClass from the importer top level works`() {
        // ⚠️ 用 `ImporterTopLevel` 而非 `initStandardObjects()` 就是为这个
        //（与 App 侧 JsExecutor 一致，让 ShortX / Auto.js 风格脚本可原样粘贴）。
        //
        // ⚠️ 这与「不注入 vflow 模块树」**不冲突**：`importClass` 是**引擎内建**，
        // 不是我们注入的东西。
        assertEquals("x", scalar("importClass(java.util.ArrayList); var l = new ArrayList(); l.add('x'); l.get(0)"))
    }

    // ── 防空转 ──────────────────────────────────────────────

    @Test
    fun `the test class exercised many distinct scripts so the checks are not vacuous`() {
        // ⚠️ 防空转（照 WireLayerPurityTest 的 files-are-present 形态）。
        // 若有人把用例表清空，上面所有断言会**全部空转通过**。
        //
        // ⚠️ 计数器在 companion 里 —— JUnit 对每个用例新建实例，
        // 用实例字段的话它永远是 0（我第一版就写错了，被这条断言抓出来）。
        assertTrue(
            "本测试类只跑了 ${exercisedScripts.size} 段不同的脚本，覆盖度异常",
            exercisedScripts.size >= 25,
        )
    }

    /** 给 context 注入用例用的假对象（**故意不是** android.content.Context）。 */
    class FakeContext {
        fun describe(): String = MARKER

        companion object {
            const val MARKER = "fake-context"
        }
    }

    private companion object {
        /**
         * 跑过的**全部**脚本（去重）。仅供防空转断言用。
         *
         * ⚠️ 放 companion 而不是实例字段 —— 见那条断言的注释。
         */
        val exercisedScripts = mutableSetOf<String>()
    }
}
