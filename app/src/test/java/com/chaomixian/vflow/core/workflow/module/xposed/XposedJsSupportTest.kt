package com.chaomixian.vflow.core.workflow.module.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `vflow.xposed.js` 纯函数层单测。
 *
 * ## ⚠️ 这里锁的都是「改错了不报错、只静默变差」的地方
 *
 * - 钳位算错 ⇒ 脚本被悄悄掐断，报的是「超时」，用户去查脚本；
 * - 结果形状取错 ⇒ 下游**永远**拿到空字典，且没有任何报错。
 */
class XposedJsSupportTest {

    // ════════════════════ clampTimeoutMs ════════════════════
    //
    // ⚠️⚠️ 本段于 2026-10-02 **整段翻面**：`null` / `<= 0` 从「回落 5000」
    // 改为「不超时（返回 null）」，以对齐 `JsExecutor` 的既有约定
    //（「`null` 或 `<= 0` 表示不超时」）。上限 `MAX_TIMEOUT_MS` 同时删除。

    @Test
    fun `null means no timeout`() {
        // 没填过 / getVariableAsNumber 对 VNull 返回 null
        assertNull(clampTimeoutMs(null))
    }

    @Test
    fun `zero and negatives mean no timeout`() {
        // ⚠️⚠️ 这条锁的是**与 JsExecutor 的语义对齐**，不是数值。
        // 此前这里断言的是「回落 5000」—— 那会让两个 JS 模块在同一个数值上
        // 行为相反（填 0 时 system.js 永不超时、xposed.js 5 秒就断）。
        assertNull(clampTimeoutMs(0L))
        assertNull(clampTimeoutMs(-1L))
        assertNull(clampTimeoutMs(Long.MIN_VALUE))
    }

    @Test
    fun `positive values are passed through unchanged and uncapped`() {
        assertEquals(1L, clampTimeoutMs(1L))
        assertEquals(5_000L, clampTimeoutMs(5_000L))
        assertEquals(30_000L, clampTimeoutMs(30_000L))
        // ⚠️ 上限已删：既然「不填」就等于无限，用户想要 60 秒只需不填 ——
        // 旧上限拦不住任何真实意图，只能拦住「填了 60 秒」这种更明确的写法。
        assertEquals(60_000L, clampTimeoutMs(60_000L))
        assertEquals(Long.MAX_VALUE, clampTimeoutMs(Long.MAX_VALUE))
    }

    @Test
    fun `the timeout input has no default value`() {
        // ⚠️ 反向断言：`timeout_ms` **不得**有 `defaultValue`。
        // 有默认值 = 表单预填 = hint（「不填则不超时」）永远不显示、
        // 且用户拿到的其实是「默认超时」而不是「不超时」。
        val timeout = XposedJsModule().getInputs().first { it.id == "timeout_ms" }
        assertNull("timeout_ms 不应有默认值", timeout.defaultValue)
    }

    // ════════════════════ rawOutputsOf ════════════════════

    @Test
    fun `outputs are read from items zero`() {
        // T2 定案的形状：result = {"items": [ <脚本返回的字典> ]}
        val result = mapOf<String, Any?>("items" to listOf(mapOf("k" to "v")))
        assertEquals(mapOf("k" to "v"), rawOutputsOf(result))
    }

    @Test
    fun `the outputs dictionary is not wrapped in an extra key`() {
        // ⚠️ 反向断言：**不许**多出 `outputs` 之类的包装键。
        // 若将来有人「顺手」把形状改回 {"outputs": {...}}，会先在这里变红。
        val result = mapOf<String, Any?>("items" to listOf(mapOf("a" to 1.0, "b" to true)))
        val outputs = rawOutputsOf(result)
        assertEquals(setOf("a", "b"), outputs.keys)
        assertTrue("不应有包装键 outputs", !outputs.containsKey("outputs"))
    }

    @Test
    fun `an empty dictionary is an empty result not a failure`() {
        // ⚠️⚠️ 脚本无返回值 / 返回非对象 ⇒ T2 给 items = [{}]（空字典，不是空 items）
        // ⇒ 这里是**空 map**，而**不是**失败。当成失败会把「脚本故意不返回东西」
        // 误报成「脚本坏了」。
        val result = mapOf<String, Any?>("items" to listOf(emptyMap<String, Any?>()))
        val outputs = rawOutputsOf(result)
        assertTrue(outputs.isEmpty())
    }

    @Test
    fun `missing items yields an empty map`() {
        assertTrue(rawOutputsOf(emptyMap()).isEmpty())
        assertTrue(rawOutputsOf(mapOf("something_else" to listOf(mapOf("k" to "v")))).isEmpty())
    }

    @Test
    fun `a non-list items yields an empty map`() {
        // 协议被破坏时的防御：不抛，只给空
        assertTrue(rawOutputsOf(mapOf("items" to "not-a-list")).isEmpty())
        assertTrue(rawOutputsOf(mapOf("items" to 42.0)).isEmpty())
        assertTrue(rawOutputsOf(mapOf("items" to null)).isEmpty())
    }

    @Test
    fun `an empty items list yields an empty map`() {
        assertTrue(rawOutputsOf(mapOf("items" to emptyList<Any?>())).isEmpty())
    }

    @Test
    fun `a non-map first element yields an empty map`() {
        assertTrue(rawOutputsOf(mapOf("items" to listOf("scalar"))).isEmpty())
        assertTrue(rawOutputsOf(mapOf("items" to listOf(listOf(1, 2, 3)))).isEmpty())
    }

    @Test
    fun `only the first element is used`() {
        // 本 capability 恒返回单项（分页对它是 no-op），多出来的项不该混进来
        val result = mapOf<String, Any?>(
            "items" to listOf(mapOf("first" to 1.0), mapOf("second" to 2.0)),
        )
        assertEquals(mapOf("first" to 1.0), rawOutputsOf(result))
    }

    @Test
    fun `non-string keys are stringified`() {
        // JSON 对象键本来就是字符串，但 codec 的 deepConvert 之后
        // 若有人塞了非字符串键，这里必须能收敛而不是崩
        val result = mapOf<String, Any?>("items" to listOf(mapOf<Any?, Any?>(1 to "one")))
        assertEquals(mapOf("1" to "one"), rawOutputsOf(result))
    }

    @Test
    fun `nested structures survive unchanged`() {
        // codec 已递归深转，本函数只做「取 items[0]」，不该动里面的值
        val nested = mapOf("inner" to listOf(mapOf("deep" to "value")))
        val result = mapOf<String, Any?>("items" to listOf(nested))
        assertEquals(nested, rawOutputsOf(result))
    }

    @Test
    fun `null values from json null are preserved as keys with null value`() {
        // codec 把 JSONObject.NULL 折成 kotlin null（键存在、值为 null），
        // 这里必须**保留键**，否则下游会以为「脚本没返回这个键」
        val result = mapOf<String, Any?>("items" to listOf(mapOf("k" to null)))
        val outputs = rawOutputsOf(result)
        assertEquals(1, outputs.size)
        assertTrue(outputs.containsKey("k"))
        assertEquals(null, outputs["k"])
    }

    // ════════════════════ scriptInputsOf ════════════════════

    @Test
    fun `plain text passes through untouched`() {
        val out = scriptInputsOf(
            entries = mapOf("a" to "hello"),
            hasReference = { false },
            resolve = { error("不该被调用") },
        )
        assertEquals(mapOf("a" to "hello"), out)
    }

    @Test
    fun `a reference is resolved and keeps its original type`() {
        // ⚠️ resolveValue 对单个 {{x}} 片段返回 vObj.raw（保留类型），
        // 不是字符串 —— 这就是为什么判引用必须在字符串化之后
        val out = scriptInputsOf(
            entries = mapOf("n" to "{{vars.count}}"),
            hasReference = { true },
            resolve = { 42.0 },
        )
        assertEquals(mapOf("n" to 42.0), out)
        assertNotEquals(mapOf("n" to "42.0"), out)
    }

    @Test
    fun `empty entries yield an empty map`() {
        assertTrue(
            scriptInputsOf(emptyMap(), { false }, { null }).isEmpty(),
        )
    }

    // ════════════════════ normalizeThreadMode ════════════════════
    //
    // ⚠️ 本段锁的是**硬约束**：未知值必须静默降级为 `default`，**绝不报错**
    //（新 App 发 `io`、旧 hook 层不认识时，报错会让它变成一次调用失败，
    //  而降级只损失「资源画像准确度」）。

    @Test
    fun `null means the default mode`() {
        assertEquals("default", normalizeThreadMode(null))
    }

    @Test
    fun `unknown values fall back to default without throwing`() {
        // ⚠️ 含大小写不信：「协议值一律小写」是 KNOWN 里字面量定的，
        // `"IO"` 属于未知值 ⇒ 同样回落 default（不静默 lowercase 抹平真实缺陷）
        for (raw in listOf("xxx", "", "   ", "DEFAULT", "IO", "UI", "io ", "i o")) {
            assertEquals("输入 <$raw> 应回落 default", "default", normalizeThreadMode(raw))
        }
    }

    @Test
    fun `the three known modes pass through unchanged`() {
        assertEquals("default", normalizeThreadMode("default"))
        assertEquals("io", normalizeThreadMode("io"))
        assertEquals("ui", normalizeThreadMode("ui"))
    }
}
