package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.module.logic.CallFunctionModule
import com.chaomixian.vflow.core.workflow.module.logic.IfModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `resolveModuleInputDefinitions` 的回归测试——病症 B（模块字段残缺）的守门人。
 *
 * 改造前 catalog 生成与执行校验都用 `getDynamicInputs(默认值 step)` 当参数白名单，
 * 而它是为「编辑器里用户下一步该填哪格」服务的，会按默认算子裁剪，天然是子集。
 * `If` 的默认算子 `exists` 不需要比较值，于是 `value1`/`value2` 两个字段被裁掉，
 * 模型看不见（catalog 残缺）、填了也被静默丢弃（`?: return@forEach`）。
 */
class ModuleInputDefinitionsTest {

    @Before
    fun setUp() {
        ModuleRegistry.reset()
        ModuleRegistry.register(IfModule())
        ModuleRegistry.register(CallFunctionModule())
    }

    @After
    fun tearDown() {
        ModuleRegistry.reset()
    }

    private fun definitionsFor(moduleId: String): List<String> {
        val module = requireNotNull(ModuleRegistry.getModule(moduleId)) {
            "Module $moduleId not registered"
        }
        val step = module.createSteps().firstOrNull()
            ?: ActionStep(module.id, emptyMap())
        return resolveModuleInputDefinitions(module, step).map { it.id }
    }

    @Test
    fun ifModule_defaultOperatorStillExposesBothComparisonValues() {
        val ids = definitionsFor("vflow.logic.if.start")

        // 默认算子 exists 既不落在 OPERATORS_REQUIRING_ONE_INPUT
        // 也不落在 OPERATORS_REQUIRING_TWO_INPUTS，动态求值会把两个比较值都裁掉。
        assertTrue(
            "If 模块必须暴露 value1，否则模型填了会被丢弃。实际：$ids",
            ids.contains("value1"),
        )
        assertTrue(
            "If 模块必须暴露 value2，否则 number_between 算子无法使用。实际：$ids",
            ids.contains("value2"),
        )
        assertTrue(ids.contains("input1"))
        assertTrue(ids.contains("operator"))
    }

    @Test
    fun definitionsAreUnionOfStaticAndDynamic() {
        val module = requireNotNull(ModuleRegistry.getModule("vflow.logic.if.start"))
        val step = module.createSteps().firstOrNull() ?: ActionStep(module.id, emptyMap())

        val staticIds = module.getInputs().map { it.id }.toSet()
        val resolved = resolveModuleInputDefinitions(module, step).map { it.id }

        // 并集的核心性质：只多不少。静态全集里的键一个都不能丢。
        assertTrue(
            "解析结果必须包含静态全集的全部键。缺失：${staticIds - resolved.toSet()}",
            resolved.containsAll(staticIds),
        )
        assertEquals(resolved.size, resolved.distinct().size)
    }

    @Test
    fun callFunctionModuleStillExposesWorkflowId() {
        // CallFunctionModule 的 getInputs() 只声明 workflow_id；函数参数靠动态求值生成。
        // 本测试只锁定「静态部分不丢」，函数参数的动态生成需在有签名数据时才能验证。
        val ids = definitionsFor("vflow.logic.call_function")
        assertTrue("必须暴露 workflow_id。实际：$ids", ids.contains("workflow_id"))
    }

    @Test
    fun everyRegisteredModuleKeepsItsStaticKeys() {
        // 对所有已注册模块做一次统一体检：解析结果必须覆盖静态全集，
        // 否则换成并集口径就是引入了回归。
        val offenders = mutableListOf<String>()
        ModuleRegistry.getAllModules().forEach { module ->
            val step = module.createSteps().firstOrNull() ?: ActionStep(module.id, emptyMap())
            val staticIds = module.getInputs().map { it.id }.toSet()
            val resolved = resolveModuleInputDefinitions(module, step).map { it.id }.toSet()
            val missing = staticIds - resolved
            if (missing.isNotEmpty()) {
                offenders += "${module.id} 丢失 $missing"
            }
        }

        assertTrue("以下模块的静态输入在解析后丢失：$offenders", offenders.isEmpty())
    }
}
