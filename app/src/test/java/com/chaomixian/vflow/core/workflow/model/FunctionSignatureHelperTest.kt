package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.module.network.HttpRequestModule
import com.chaomixian.vflow.core.workflow.module.system.CaptureScreenModule
import com.chaomixian.vflow.core.workflow.module.system.JsModule
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 返回值类型**现场推导**的回归测试。
 *
 * ⚠️ 本文件替换了旧版基于「创建变量」判据的键提取用例（决策 #9）：
 *  - 类型来自「那一步**声明的输出类型**」，与「是不是『创建变量』」无关；
 *  - 键只来自 `return_keys` 声明，**不再自动提取**字面量字典 / 创建变量步骤的键。
 */
class FunctionSignatureHelperTest {

    @Before
    fun setUp() {
        ModuleRegistry.reset()
        ModuleRegistry.register(JsModule())
        ModuleRegistry.register(CaptureScreenModule())
        ModuleRegistry.register(HttpRequestModule())
    }

    @After
    fun tearDown() {
        ModuleRegistry.reset()
    }

    private fun returnStep(
        value: Any?,
        returnKeys: List<Map<String, String>>? = null
    ): ActionStep = ActionStep(
        moduleId = FunctionSignatureHelper.RETURN_MODULE_ID,
        parameters = buildMap {
            if (value != null) put("value", value)
            if (returnKeys != null) put(FunctionSignatureHelper.RETURN_KEYS_KEY, returnKeys)
        }
    )

    private fun step(moduleId: String, id: String, params: Map<String, Any?> = emptyMap()): ActionStep =
        ActionStep(moduleId = moduleId, parameters = params, id = id)

    // ---------- 类型推导 ----------

    @Test
    fun `derives dictionary type from js step outputs`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(js, returnStep("{{js-1.outputs}}"))
        assertEquals(VTypeRegistry.DICTIONARY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `derives image type from capture screen step image`() {
        val cap = step("vflow.system.capture_screen", "cap-1")
        val steps = listOf(cap, returnStep("{{cap-1.image}}"))
        assertEquals(VTypeRegistry.IMAGE.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `derives dictionary type from http response headers`() {
        val http = step("vflow.network.http_request", "http-1")
        val steps = listOf(http, returnStep("{{http-1.response_headers}}"))
        assertEquals(VTypeRegistry.DICTIONARY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    // ---------- 带路径 ----------

    @Test
    fun `resolves type along a property path`() {
        // {{cap-1.image.width}} ⇒ 图片.width ⇒ 数字
        val cap = step("vflow.system.capture_screen", "cap-1")
        val steps = listOf(cap, returnStep("{{cap-1.image.width}}"))
        assertEquals(VTypeRegistry.NUMBER.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `unknown property path segment falls back to any`() {
        // {{js-1.outputs.result}} —— result 是手输的字典键，类型未知 ⇒ ANY
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(js, returnStep("{{js-1.outputs.result}}"))
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `unknown output id falls back to any`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(js, returnStep("{{js-1.no_such_output}}"))
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `unknown step id falls back to any`() {
        val steps = listOf(returnStep("{{missing.outputs}}"))
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    // ---------- 字面量 ----------

    @Test
    fun `literal map derives dictionary type but does not extract keys`() {
        // 决策 #9：字面量字典只保留「类型 = 字典」，**不再提取键**
        val steps = listOf(returnStep(mapOf("code" to 200, "msg" to "ok")))
        val ret = FunctionSignatureHelper.deriveReturn(steps)
        assertEquals(VTypeRegistry.DICTIONARY.id, ret.type)
        assertTrue("literal dictionary must not auto-extract keys", ret.keys.isEmpty())
    }

    @Test
    fun `literal list derives list type`() {
        val steps = listOf(returnStep(listOf(1, 2, 3)))
        assertEquals(VTypeRegistry.LIST.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `plain string return value falls back to any`() {
        val steps = listOf(returnStep("http://example.com"))
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    // ---------- 多 return 合并 ----------

    @Test
    fun `multiple returns of the same type keep that type`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(
            js,
            returnStep("{{js-1.outputs}}"),
            returnStep("{{js-1.outputs}}")
        )
        assertEquals(VTypeRegistry.DICTIONARY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `conflicting return types merge to any`() {
        val js = step("vflow.system.js", "js-1")
        val cap = step("vflow.system.capture_screen", "cap-1")
        val steps = listOf(
            js, cap,
            returnStep("{{js-1.outputs}}"),
            returnStep("{{cap-1.image}}")
        )
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    @Test
    fun `a return type mixed with unknown merges to any`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(
            js,
            returnStep("{{js-1.outputs}}"),
            returnStep("plain text")
        )
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    // ---------- 零 / 无 return ----------

    @Test
    fun `no return step yields any with no keys`() {
        val steps = listOf(
            ActionStep(moduleId = "vflow.logic.define_function", parameters = emptyMap())
        )
        val ret = FunctionSignatureHelper.deriveReturn(steps)
        assertEquals(VTypeRegistry.ANY.id, ret.type)
        assertTrue(ret.keys.isEmpty())
    }

    @Test
    fun `return step without a value parameter yields any`() {
        val steps = listOf(ActionStep(moduleId = FunctionSignatureHelper.RETURN_MODULE_ID, parameters = emptyMap()))
        assertEquals(VTypeRegistry.ANY.id, FunctionSignatureHelper.deriveReturn(steps).type)
    }

    // ---------- 键（来自声明） ----------

    @Test
    fun `declared keys are read from the return step`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(
            js,
            returnStep(
                "{{js-1.outputs}}",
                returnKeys = listOf(
                    mapOf("name" to "code", "type" to VTypeRegistry.NUMBER.id),
                    mapOf("name" to "msg", "type" to VTypeRegistry.STRING.id)
                )
            )
        )
        val ret = FunctionSignatureHelper.deriveReturn(steps)
        assertEquals(2, ret.keys.size)
        assertEquals("code", ret.keys[0].name)
        assertEquals(VTypeRegistry.NUMBER.id, ret.keys[0].type)
        assertEquals("msg", ret.keys[1].name)
    }

    @Test
    fun `declared keys are merged across multiple returns and deduplicated`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(
            js,
            returnStep("{{js-1.outputs}}", returnKeys = listOf(
                mapOf("name" to "a", "type" to VTypeRegistry.NUMBER.id),
                mapOf("name" to "shared", "type" to VTypeRegistry.STRING.id)
            )),
            returnStep("{{js-1.outputs}}", returnKeys = listOf(
                mapOf("name" to "b", "type" to VTypeRegistry.BOOLEAN.id),
                mapOf("name" to "shared", "type" to VTypeRegistry.STRING.id)
            ))
        )
        val ret = FunctionSignatureHelper.deriveReturn(steps)
        assertEquals(listOf("a", "shared", "b"), ret.keys.map { it.name })
    }

    @Test
    fun `a key without a type defaults to any`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(
            js,
            returnStep("{{js-1.outputs}}", returnKeys = listOf(mapOf("name" to "k")))
        )
        val ret = FunctionSignatureHelper.deriveReturn(steps)
        assertEquals(1, ret.keys.size)
        assertEquals(VTypeRegistry.ANY.id, ret.keys[0].type)
    }

    @Test
    fun `blank-named keys are dropped`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(
            js,
            returnStep("{{js-1.outputs}}", returnKeys = listOf(
                mapOf("name" to "  ", "type" to VTypeRegistry.STRING.id),
                mapOf("name" to "ok", "type" to VTypeRegistry.STRING.id)
            ))
        )
        val ret = FunctionSignatureHelper.deriveReturn(steps)
        assertEquals(listOf("ok"), ret.keys.map { it.name })
    }

    // ---------- resolveValueType（供 UIProvider 复用） ----------

    @Test
    fun `resolveValueType reports dictionary for js outputs`() {
        val js = step("vflow.system.js", "js-1")
        val steps = listOf(js)
        assertEquals(
            VTypeRegistry.DICTIONARY.id,
            FunctionSignatureHelper.resolveValueType("{{js-1.outputs}}", steps)
        )
    }

    @Test
    fun `resolveValueType reports any for a plain string`() {
        assertEquals(
            VTypeRegistry.ANY.id,
            FunctionSignatureHelper.resolveValueType("hello", emptyList())
        )
    }

    @Test
    fun `resolveValueType reports dictionary for a literal map`() {
        assertEquals(
            VTypeRegistry.DICTIONARY.id,
            FunctionSignatureHelper.resolveValueType(mapOf("a" to 1), emptyList())
        )
    }

    // ---------- 既有往返用例（保留） ----------

    @Test
    fun `functionParam_gson_round_trip_preserves_fields`() {
        val original = listOf(
            FunctionParam(name = "url", type = "vflow.type.string", defaultValue = "https://a.com", isRequired = true),
            FunctionParam(name = "count", type = "vflow.type.number", defaultValue = 3, isRequired = false)
        )
        val gson = Gson()
        val json = gson.toJson(original)
        val type = object : TypeToken<List<FunctionParam>>() {}.type
        val restored: List<FunctionParam> = gson.fromJson(json, type)

        assertEquals(2, restored.size)
        assertEquals("url", restored[0].name)
        assertEquals("https://a.com", restored[0].defaultValue)
        assertEquals(3.0, (restored[1].defaultValue as Number).toDouble(), 0.001)
    }
}
