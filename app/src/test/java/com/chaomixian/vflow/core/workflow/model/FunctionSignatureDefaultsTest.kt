package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [FunctionSignatureDefaults.seedNamedVariables] 的回归测试。
 *
 * 这段逻辑决定了「函数工作流单独执行时读到什么」，失败模式是**静默的**：
 * 种子给多了会让本该为空的参数变成有值，给少了则 {{vars.x}} 依旧解析成空、
 * 表现为「配了默认值却还是报错」——两者都不会让测试以外的任何地方报错。
 */
class FunctionSignatureDefaultsTest {

    private fun param(
        name: String,
        defaultValue: Any? = null,
        isRequired: Boolean = false
    ) = FunctionParam(
        name = name,
        type = VTypeRegistry.STRING.id,
        defaultValue = defaultValue,
        isRequired = isRequired
    )

    @Test
    fun `非函数工作流返回空表`() {
        assertTrue(FunctionSignatureDefaults.seedNamedVariables(null).isEmpty())
    }

    @Test
    fun `零参数函数返回空表`() {
        val signature = FunctionSignature(params = emptyList())

        assertTrue(FunctionSignatureDefaults.seedNamedVariables(signature).isEmpty())
    }

    @Test
    fun `只收纳声明了默认值的参数`() {
        val signature = FunctionSignature(
            params = listOf(
                param("with_default", "hello"),
                param("without_default"),
                param("also_without", isRequired = true)
            )
        )

        val seeded = FunctionSignatureDefaults.seedNamedVariables(signature)

        assertEquals(setOf("with_default"), seeded.keys)
    }

    @Test
    fun `默认值按原类型包装为 VObject 而非降级为字符串`() {
        val signature = FunctionSignature(
            params = listOf(
                param("text", "hi"),
                param("count", 3),
                param("ratio", 1.5),
                param("flag", true),
                param("items", listOf("a", "b")),
                param("config", mapOf("k" to "v"))
            )
        )

        val seeded = FunctionSignatureDefaults.seedNamedVariables(signature)

        assertEquals(VString("hi"), seeded["text"])
        assertEquals(VNumber(3), seeded["count"])
        assertEquals(VNumber(1.5), seeded["ratio"])
        assertEquals(VBoolean(true), seeded["flag"])
        assertEquals(VList(listOf(VString("a"), VString("b"))), seeded["items"])
        assertEquals(VDictionary(mapOf("k" to VString("v"))), seeded["config"])
    }

    @Test
    fun `空字符串默认值也算已声明`() {
        // 空串是「作者显式写的默认值」，与「没写默认值」在表里必须可区分：
        // 前者进表（值 VString("")），后者不进表。
        val signature = FunctionSignature(
            params = listOf(param("blank", ""), param("absent"))
        )

        val seeded = FunctionSignatureDefaults.seedNamedVariables(signature)

        assertEquals(setOf("blank"), seeded.keys)
        assertEquals(VString(""), seeded["blank"])
    }

    @Test
    fun `假值默认值不被当作缺失`() {
        // 0 与 false 在 Kotlin 里虽是 falsy，但它们是有效默认值。
        val signature = FunctionSignature(
            params = listOf(param("zero", 0), param("off", false), param("empty_list", emptyList<String>()))
        )

        val seeded = FunctionSignatureDefaults.seedNamedVariables(signature)

        assertEquals(3, seeded.size)
        assertEquals(VNumber(0), seeded["zero"])
        assertEquals(VBoolean(false), seeded["off"])
        assertEquals(VList(emptyList()), seeded["empty_list"])
    }
}
