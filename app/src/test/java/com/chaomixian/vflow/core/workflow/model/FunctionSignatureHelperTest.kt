package com.chaomixian.vflow.core.workflow.model

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FunctionSignatureHelperTest {

    private fun returnStep(parameters: Map<String, Any?>): ActionStep =
        ActionStep(moduleId = "vflow.logic.return", parameters = parameters)

    private fun createDictStep(parameters: Map<String, Any?>): ActionStep =
        ActionStep(moduleId = "vflow.variable.create", parameters = parameters)

    @Test
    fun `deriveReturnDef_returns_null_when_no_return_step_exists`() {
        val steps = listOf(
            ActionStep(moduleId = "vflow.logic.define_function", parameters = emptyMap())
        )
        assertNull(FunctionSignatureHelper.deriveReturnDef(steps))
    }

    @Test
    fun `deriveReturnDef_extracts_keys_from_literal_dictionary`() {
        val steps = listOf(
            returnStep(parameters = mapOf("value" to mapOf("code" to 200, "msg" to "ok")))
        )
        val result = FunctionSignatureHelper.deriveReturnDef(steps)
        assertEquals(2, result?.keys?.size)
        assertEquals("code", result?.keys?.get(0)?.name)
        assertEquals("msg", result?.keys?.get(1)?.name)
    }

    @Test
    fun `deriveReturnDef_traces_dictionary_variable_step`() {
        val dictStep = createDictStep(
            parameters = mapOf(
                "type" to "dictionary",
                "value" to mapOf("data" to listOf(1, 2, 3))
            )
        )
        val steps = listOf(
            dictStep,
            returnStep(parameters = mapOf("value" to "{{${dictStep.id}.variable}}"))
        )
        val result = FunctionSignatureHelper.deriveReturnDef(steps)
        assertEquals(1, result?.keys?.size)
        assertEquals("data", result?.keys?.get(0)?.name)
    }

    @Test
    fun `deriveReturnDef_returns_null_for_non_dictionary_return_value`() {
        val steps = listOf(
            returnStep(parameters = mapOf("value" to "http://example.com"))
        )
        assertNull(FunctionSignatureHelper.deriveReturnDef(steps))
    }

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
