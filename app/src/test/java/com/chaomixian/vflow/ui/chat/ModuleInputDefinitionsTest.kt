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
    fun ifModuleDeclaresFieldSemanticsForComparisonValues() {
        // `query_module_schema` 与模块工具 JSON Schema 都靠 `inputHints` 向模型传达
        // 字段语义。`If` 的这条尤其关键——它回答了「value1/value2 分别在什么算子下使用」
        // 与「谁是主体」，这两点**无法从字段名或类型推出**，丢了只能靠模型猜。
        //
        // 实测证据：某次真实会话中模型查过 schema 后仍回答
        // 「value2 是否被 number_between 用到……是我从命名猜的」。
        val metadata = requireNotNull(ModuleRegistry.getModule("vflow.logic.if.start")?.aiMetadata)
        val hints = metadata.inputHints

        assertTrue("If 必须声明 value2 的语义", hints.containsKey("value2"))
        assertTrue(
            "value2 的说明应点明它只被 number_between 使用。实际：${hints["value2"]}",
            hints["value2"]?.contains("number_between") == true,
        )
        assertTrue(
            "input1 的说明应点明它是被判断的主体。实际：${hints["input1"]}",
            hints["input1"]?.contains("Primary value") == true,
        )
    }

    @Test
    fun modulesDeclareRequiredInputsIndependentlyOfDefaults() {
        // 必填集由模块自己声明（`requiredInputIds`），不能靠 `defaultValue` 有无推断——
        // 有默认值 ≠ 非必填。`If` 声明 input1/operator 必填，而 operator 是有默认值的。
        val metadata = requireNotNull(ModuleRegistry.getModule("vflow.logic.if.start")?.aiMetadata)

        assertTrue(
            "If 应声明必填字段。实际：${metadata.requiredInputIds}",
            metadata.requiredInputIds.isNotEmpty(),
        )
        assertTrue(metadata.requiredInputIds.contains("operator"))
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
