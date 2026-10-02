package com.chaomixian.vflow.core.workflow.module.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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

    @Test
    fun `null falls back to the default`() {
        // 未配过 / getVariableAsNumber 对 VNull 返回 null
        assertEquals(DEFAULT_TIMEOUT_MS, clampTimeoutMs(null))
    }

    @Test
    fun `zero and negatives fall back to the default instead of clamping to one ms`() {
        // ⚠️⚠️ 这条锁的是**语义选择**，不是数值。
        // 钳到 1ms 会让用户拿到一个必然超时的结果（一次 binder 往返都不止 1ms），
        // 而他看到的是「脚本超时」，会去查脚本本身。
        assertEquals(DEFAULT_TIMEOUT_MS, clampTimeoutMs(0L))
        assertEquals(DEFAULT_TIMEOUT_MS, clampTimeoutMs(-1L))
        assertEquals(DEFAULT_TIMEOUT_MS, clampTimeoutMs(Long.MIN_VALUE))
    }

    @Test
    fun `in-range values are passed through unchanged`() {
        assertEquals(1L, clampTimeoutMs(1L))
        assertEquals(5_000L, clampTimeoutMs(5_000L))
        assertEquals(30_000L, clampTimeoutMs(30_000L))
    }

    @Test
    fun `values above the max are clamped to the max`() {
        assertEquals(MAX_TIMEOUT_MS, clampTimeoutMs(30_001L))
        assertEquals(MAX_TIMEOUT_MS, clampTimeoutMs(60_000L))
        assertEquals(MAX_TIMEOUT_MS, clampTimeoutMs(Long.MAX_VALUE))
    }

    @Test
    fun `the max is meaningful because the hook side pool has only two workers`() {
        // 反向断言：上限必须**真的**小于一个「会占住工作线程很久」的量级。
        // 这防止将来有人「顺手」把 MAX 调到几分钟 —— 那会让两个慢脚本就把
        // hook 侧容量 2 的池占满，此后所有调用立刻回 handler_error。
        assertTrue("超时上限不应超过 60 秒（池容量只有 2）", MAX_TIMEOUT_MS <= 60_000L)
        assertTrue("默认值应小于上限", DEFAULT_TIMEOUT_MS < MAX_TIMEOUT_MS)
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
}
