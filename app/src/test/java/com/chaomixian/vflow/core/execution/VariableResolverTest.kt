package com.chaomixian.vflow.core.execution

import android.content.ContextWrapper
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Stack

class VariableResolverTest {

    @Test
    fun `function parameter reference requires the vars namespace prefix`() {
        // 真机回归：模型建函数工作流时，把「引用已声明的函数参数」写成了 {{user_id}}，
        // 正确形式是 {{vars.user_id}}。裸写不会解析、也不报错，静默得到空值——
        // 工作流看起来保存成功，运行时参数却是空的。
        //
        // 本测试锁定两条分支的语义，防止将来有人"放宽"裸写法时无意间改掉行为。
        val context = createContext(
            namedVariables = mutableMapOf("user_id" to VString("u_123"))
        )

        assertEquals(
            "带 vars. 前缀应解析到命名变量",
            "u_123",
            VariableResolver.resolve("{{vars.user_id}}", context),
        )

        assertFalse(
            "裸写 {{user_id}} 不应被当作命名变量解析——它既非 vars.* 也非 stepId.outputId",
            VariableResolver.resolve("{{user_id}}", context).contains("u_123"),
        )
    }

    @Test
    fun `resolveValue uses canonical variable as list index`() {
        val context = createContext(
            stepOutputs = mutableMapOf(
                "step1" to mapOf(
                    "items" to VList(listOf(VString("zero"), VString("one"), VString("two")))
                )
            ),
            namedVariables = mutableMapOf("index" to VNumber(1))
        )

        val result = VariableResolver.resolveValue("{{step1.items.{{vars.index}}}}", context)

        assertEquals("one", result)
    }

    @Test
    fun `resolveValue uses canonical variable as negative list index`() {
        val context = createContext(
            stepOutputs = mutableMapOf(
                "step1" to mapOf(
                    "items" to VList(listOf(VString("zero"), VString("one"), VString("two")))
                )
            ),
            namedVariables = mutableMapOf("index" to VNumber(-1))
        )

        val result = VariableResolver.resolveValue("{{step1.items.{{vars.index}}}}", context)

        assertEquals("two", result)
    }

    @Test
    fun `resolveValue uses canonical variable as string index`() {
        val context = createContext(
            stepOutputs = mutableMapOf("step1" to mapOf("text" to VString("abc"))),
            namedVariables = mutableMapOf("index" to VNumber(2))
        )

        val result = VariableResolver.resolveValue("{{step1.text.{{vars.index}}}}", context)

        assertEquals("c", result)
    }

    @Test
    fun `resolveSingleVariableReference uses step output as dynamic string index`() {
        val context = createContext(
            stepOutputs = mutableMapOf(
                "indexStep" to mapOf("variable" to VNumber(0)),
                "textStep" to mapOf("variable" to VString("abcd"))
            )
        )

        val result = VariableResolver.resolveSingleVariableReference(
            "{{textStep.variable.{{indexStep.variable}}}}",
            context
        )

        assertEquals("a", result?.asString())
    }

    @Test
    fun `complexity helpers use nested template parser`() {
        assertTrue(VariableResolver.hasVariableReference("{{aaa.{{bbb}}}}"))
        assertFalse(VariableResolver.isComplex("{{aaa.{{bbb}}}}"))
        assertTrue(VariableResolver.isComplex("prefix {{aaa.{{bbb}}}}"))
        assertTrue(VariableResolver.isComplex("{{aaa}}{{bbb}}"))
    }

    @Test
    fun `complexity helpers recognize inline scripts`() {
        assertTrue(VariableResolver.hasVariableReference("{%return 1%}"))
        assertTrue(VariableResolver.isComplex("{%return 1%}"))
        assertFalse(VariableResolver.hasVariableReference("\\{%return 1%}"))
    }

    @Test
    fun `resolve throws inline script error with JavaScript message`() {
        val error = org.junit.Assert.assertThrows(
            InlineScriptEvaluator.InlineScriptExecutionException::class.java
        ) {
            VariableResolver.resolve("before {%missingCall()%} after", createContext())
        }

        assertTrue(error.message.orEmpty().contains("Inline JavaScript 执行失败"))
        assertTrue(error.message.orEmpty().contains("missingCall"))
        assertTrue(error.message.orEmpty().contains("脚本: missingCall()"))
    }

    private fun createContext(
        stepOutputs: MutableMap<String, Map<String, com.chaomixian.vflow.core.types.VObject>> = mutableMapOf(),
        namedVariables: MutableMap<String, com.chaomixian.vflow.core.types.VObject> = mutableMapOf()
    ): ExecutionContext {
        return ExecutionContext(
            applicationContext = ContextWrapper(null),
            variables = mutableMapOf(),
            magicVariables = mutableMapOf(),
            services = ExecutionServices(),
            allSteps = emptyList<ActionStep>(),
            currentStepIndex = 0,
            stepOutputs = stepOutputs,
            loopStack = Stack(),
            namedVariables = namedVariables,
            workDir = File("build/test-workdir")
        )
    }
}
