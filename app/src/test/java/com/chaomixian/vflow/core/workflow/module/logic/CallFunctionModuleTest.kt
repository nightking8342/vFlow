package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CallFunctionModule.getOutputs 的回归测试。
 *
 * 重点：魔法变量选择器会调用 getDynamicOutputs → getOutputs，
 * 而模块的 appContext 由 ModuleRegistry 在注册时注入（lateinit）。
 * 若此处未初始化就访问 appContext，会抛 UninitializedPropertyAccessException 导致 UI 崩溃。
 */
class CallFunctionModuleTest {

    private val module = CallFunctionModule()

    @Test
    fun `getOutputs 在没有 step 时返回 ANY 且无声明键`() {
        val outputs = module.getOutputs(null)

        assertEquals(1, outputs.size)
        assertEquals("result", outputs[0].id)
        assertEquals(VTypeRegistry.ANY.id, outputs[0].typeName)
        assertTrue(outputs[0].dictionaryKeys.isEmpty())
    }

    @Test
    fun `getOutputs 在模块 Context 未初始化时不崩溃`() {
        val step = ActionStep(
            moduleId = module.id,
            parameters = mapOf("workflow_id" to "some-workflow-id")
        )

        val outputs = module.getOutputs(step)

        assertEquals(1, outputs.size)
        assertEquals("result", outputs[0].id)
        // Context 不可用时安全回退到 ANY，且不带声明键
        assertEquals(VTypeRegistry.ANY.id, outputs[0].typeName)
        assertTrue(outputs[0].dictionaryKeys.isEmpty())
    }
}
