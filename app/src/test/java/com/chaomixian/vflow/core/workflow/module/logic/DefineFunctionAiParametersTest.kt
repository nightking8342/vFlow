package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DefineFunctionModule.normalizeAiParameters] 的回归测试。
 *
 * 背景（设计文档 §6.2 开放问题 6）：AI 走 JSON 入参写 `functionParams` 时，
 * 拿到的是 `List<Map<...>>`，而编辑器写的是 JSON 字符串。两条路径必须收敛到
 * 同一形态，否则同一份数据会有两种表示。
 */
class DefineFunctionAiParametersTest {

    private val module = DefineFunctionModule()
    private val gson = Gson()

    private fun decode(params: Map<String, Any?>): List<FunctionParam> {
        val raw = params[DefineFunctionModule.FUNCTION_PARAMS_KEY] as? String
            ?: return emptyList()
        return gson.fromJson(raw, object : TypeToken<List<FunctionParam>>() {}.type) ?: emptyList()
    }

    @Test
    fun normalizeAiParameters_convertsLooseListToJsonString() {
        val result = module.normalizeAiParameters(
            mapOf(
                DefineFunctionModule.FUNCTION_PARAMS_KEY to listOf(
                    mapOf("name" to "user_id", "type" to "string", "isRequired" to true),
                )
            )
        )

        val stored = result[DefineFunctionModule.FUNCTION_PARAMS_KEY]
        assertTrue("应序列化为 JSON 字符串而非保留 List", stored is String)

        val decoded = decode(result)
        assertEquals(1, decoded.size)
        assertEquals("user_id", decoded[0].name)
        assertTrue("isRequired 应保留", decoded[0].isRequired)
    }

    @Test
    fun normalizeAiParameters_mapsShorthandTypeToFullId() {
        // 模型写简写 "string"（与 create_variable 的 Allowed values 一致），
        // 存储必须是全限定 ID，否则 VTypeRegistry.getType 匹配失败、类型退化成「任意」。
        val result = module.normalizeAiParameters(
            mapOf(
                DefineFunctionModule.FUNCTION_PARAMS_KEY to listOf(
                    mapOf("name" to "a", "type" to "string"),
                    mapOf("name" to "b", "type" to "number"),
                    mapOf("name" to "c", "type" to "dictionary"),
                )
            )
        )

        val decoded = decode(result)
        assertEquals(VTypeRegistry.STRING.id, decoded[0].type)
        assertEquals(VTypeRegistry.NUMBER.id, decoded[1].type)
        assertEquals(VTypeRegistry.DICTIONARY.id, decoded[2].type)
    }

    @Test
    fun normalizeAiParameters_acceptsFullTypeIdUnchanged() {
        // 已经写了全限定 ID 的模型（或手工数据）不该被二次转换破坏。
        val result = module.normalizeAiParameters(
            mapOf(
                DefineFunctionModule.FUNCTION_PARAMS_KEY to listOf(
                    mapOf("name" to "a", "type" to VTypeRegistry.BOOLEAN.id),
                )
            )
        )

        assertEquals(VTypeRegistry.BOOLEAN.id, decode(result)[0].type)
    }

    @Test
    fun normalizeAiParameters_preservesDefaultValue() {
        val result = module.normalizeAiParameters(
            mapOf(
                DefineFunctionModule.FUNCTION_PARAMS_KEY to listOf(
                    mapOf("name" to "count", "type" to "number", "defaultValue" to 5),
                )
            )
        )

        // Gson 往返后 JSON 数字回来是 Double——断言值相等即可，不锁死装箱类型。
        assertEquals(5.0, (decode(result)[0].defaultValue as Number).toDouble(), 0.0)
    }

    @Test
    fun normalizeAiParameters_dropsEntriesWithBlankName() {
        // 与 UIProvider 的 filter { it.name.isNotBlank() } 保持一致。
        val result = module.normalizeAiParameters(
            mapOf(
                DefineFunctionModule.FUNCTION_PARAMS_KEY to listOf(
                    mapOf("name" to "keep", "type" to "string"),
                    mapOf("name" to "  ", "type" to "string"),
                    mapOf("type" to "string"),
                )
            )
        )

        val decoded = decode(result)
        assertEquals(1, decoded.size)
        assertEquals("keep", decoded[0].name)
    }

    @Test
    fun normalizeAiParameters_leavesEditorWrittenJsonStringAlone() {
        // 编辑器路径写进去的已经是 JSON 字符串——必须原样放行，
        // 否则会对字符串做二次 Gson 序列化，产生双重编码。
        val editorValue = """[{"name":"x","type":"vflow.type.string","isRequired":false}]"""
        val input = mapOf(DefineFunctionModule.FUNCTION_PARAMS_KEY to editorValue)

        val result = module.normalizeAiParameters(input)

        assertSame("字符串形态应原样返回", input, result)
    }

    @Test
    fun normalizeAiParameters_isNoOpWhenFieldMissing() {
        val input = mapOf<String, Any?>("somethingElse" to 1)
        assertSame("缺字段时不该改动参数表", input, module.normalizeAiParameters(input))
    }

    @Test
    fun normalizeAiParameters_handlesZeroArgumentFunction() {
        val result = module.normalizeAiParameters(
            mapOf(DefineFunctionModule.FUNCTION_PARAMS_KEY to emptyList<Any>())
        )

        assertTrue("零参数函数应产出空数组而非报错", decode(result).isEmpty())
    }

    @Test
    fun getInputs_declaresFunctionParamsAsAny() {
        // 类型必须是 ANY：STRING 会走 rawValue.toString()，
        // 而 List<Map<...>> 的 toString() 不是合法 JSON，落库后解析必炸。
        val input = module.getInputs().firstOrNull { it.id == DefineFunctionModule.FUNCTION_PARAMS_KEY }

        assertTrue("AI 只认 getInputs()，缺声明就会把 functionParams 判为非法键", input != null)
        assertEquals(
            com.chaomixian.vflow.core.module.ParameterType.ANY,
            input!!.staticType,
        )
    }

    @Test
    fun getInputs_doesNotInjectDefaultValue() {
        // 有 defaultValue 会被 BaseModule.createSteps() 预填进新卡片——
        // 空列表的默认值没有意义，且会引入「空数组还是 null」的歧义。
        val input = module.getInputs().first { it.id == DefineFunctionModule.FUNCTION_PARAMS_KEY }
        assertFalse("不应带默认值", input.defaultValue != null)
    }
}
