package com.chaomixian.vflow.core.workflow.module.logic

import android.content.ContextWrapper
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionServices
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BlockType
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ExecutionSignal
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.workflow.model.ActionStep
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.util.Stack

/**
 * Switch 四个模块的**声明体检** + `validate` 接线 + 执行语义。
 *
 * ⚠️ `validate` 的四条用例**必须经由 `SwitchModule().validate(...)`** 调，不能直接调
 * `validateBranches` —— 后者绿不代表前者接上了（本仓库三次踩过「纯函数单测全绿但调用点缺失」的坑）。
 */
class SwitchModuleTest {

    private val switchModule = SwitchModule()
    private val caseModule = SwitchCaseModule()
    private val defaultModule = SwitchDefaultModule()
    private val endModule = EndSwitchModule()

    private val plain = "test.switch.plain"

    @Before
    fun setUp() {
        ModuleRegistry.reset()
        ModuleRegistry.register(switchModule)
        ModuleRegistry.register(caseModule)
        ModuleRegistry.register(defaultModule)
        ModuleRegistry.register(endModule)
    }

    @After
    fun tearDown() {
        ModuleRegistry.reset()
    }

    // ───────────────────────── 声明体检 ─────────────────────────

    @Test
    fun `ids are the four stable switch ids`() {
        assertEquals("vflow.logic.switch.start", switchModule.id)
        assertEquals("vflow.logic.switch.case", caseModule.id)
        assertEquals("vflow.logic.switch.default", defaultModule.id)
        assertEquals("vflow.logic.switch.end", endModule.id)
    }

    @Test
    fun `block types are start middle middle end`() {
        assertEquals(BlockType.BLOCK_START, switchModule.blockBehavior.type)
        assertEquals(BlockType.BLOCK_MIDDLE, caseModule.blockBehavior.type)
        assertEquals(BlockType.BLOCK_MIDDLE, defaultModule.blockBehavior.type)
        assertEquals(BlockType.BLOCK_END, endModule.blockBehavior.type)
    }

    @Test
    fun `all four share the switch pairing id`() {
        listOf(switchModule, caseModule, defaultModule, endModule).forEach {
            assertEquals(it.id, SWITCH_PAIRING_ID, it.blockBehavior.pairingId)
        }
    }

    @Test
    fun `case and default are individually deletable so the card menu exposes delete`() {
        // ⚠️ 这是对设计文档 §3.1 的**有意偏离**（文档写 false），用户 2026-10-05 已拍板取 true：
        //    该字段的唯一作用是让 ⋮ 菜单里的删除按钮出现，取 false 会让 Case 卡片
        //    **完全没有删除入口**（卡片上的直接 🗑 已被 fork 改成恒 GONE）。
        assertTrue(caseModule.blockBehavior.isIndividuallyDeletable)
        assertTrue(defaultModule.blockBehavior.isIndividuallyDeletable)
    }

    @Test
    fun `no module requires any permission`() {
        listOf(switchModule, caseModule, defaultModule, endModule).forEach {
            assertTrue("${it.id} 不应需要任何权限", it.getRequiredPermissions().isEmpty())
        }
    }

    @Test
    fun `module names are the plain english words`() {
        assertEquals("Switch", switchModule.metadata.name)
        assertEquals("Case", caseModule.metadata.name)
        assertEquals("Default", defaultModule.metadata.name)
        assertEquals("End Switch", endModule.metadata.name)
        // ⚠️ 刻意不设 nameStringRes：模块名三语都写英文
        listOf(switchModule, caseModule, defaultModule, endModule).forEach {
            assertEquals(it.id, null, it.metadata.nameStringRes)
        }
    }
    @Test
    fun `switch icon is not reused from if module`() {
        // ⚠️ 两个逻辑模块图标相同会让用户分不清
        assertNotEquals(IfModule().metadata.iconRes, switchModule.metadata.iconRes)
    }

    @Test
    fun `createSteps produces the four card kinds in order`() {
        val steps = switchModule.createSteps()
        assertEquals(
            listOf(SWITCH_START_ID, SWITCH_CASE_ID, SWITCH_DEFAULT_ID, SWITCH_END_ID),
            steps.map { it.moduleId }
        )
    }

    @Test
    fun `createSteps links each card caseId to a branches entry`() {
        val steps = switchModule.createSteps()
        val branches = SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY])
        assertEquals(2, branches.size)
        assertEquals(branches[0].id, steps[1].parameters[SWITCH_CASE_ID_KEY])
        assertEquals(branches[1].id, steps[2].parameters[SWITCH_CASE_ID_KEY])
        // 第一条是 Case（`""`），第二条是 Default（null）
        assertEquals("", branches[0].match)
        assertEquals(null, branches[1].match)
    }

    @Test
    fun `switch and case expose the value and match inputs with default values`() {
        val value = switchModule.getInputs().first { it.id == SWITCH_VALUE_KEY }
        assertEquals(ParameterType.ANY, value.staticType)
        assertEquals("", value.defaultValue)
        assertTrue(value.acceptsMagicVariable)

        val branches = switchModule.getInputs().first { it.id == SWITCH_BRANCHES_KEY }
        assertEquals(ParameterType.ANY, branches.staticType)

        val match = caseModule.getInputs().first { it.id == SWITCH_MATCH_KEY }
        assertEquals(ParameterType.ANY, match.staticType)
        // §2.3：Case 的值能连魔法变量是刻意的
        assertTrue(match.acceptsMagicVariable)
        assertTrue(match.acceptsNamedVariable)
    }

    @Test
    fun `caseId is routed into the custom ui so it never shows in the generic form`() {
        val provider = caseModule.uiProvider
        assertNotNull(provider)
        assertTrue(provider.getHandledInputIds().contains(SWITCH_CASE_ID_KEY))
        // 不画任何自定义 UI ⇒ customUiCard 隐藏，但 getHandledInputIds 仍然生效
        assertFalse(provider.hasCustomEditor())
    }

    @Test
    fun `switch ui provider handles value and branches and keeps a custom editor`() {
        val provider = switchModule.uiProvider
        assertNotNull(provider)
        // ⚠️⚠️ 必须留在默认的 true —— 否则 readFromEditor 不再被调用，branches 会被清空
        assertTrue(provider.hasCustomEditor())
        assertTrue(provider.getHandledInputIds().contains(SWITCH_VALUE_KEY))
        assertTrue(provider.getHandledInputIds().contains(SWITCH_BRANCHES_KEY))
    }

    @Test
    fun `default and end modules expose no inputs and no outputs`() {
        assertEquals(emptyList<Any>(), defaultModule.getInputs())
        assertEquals(emptyList<Any>(), endModule.getInputs())
        assertEquals(emptyList<Any>(), switchModule.getOutputs(null))
        assertEquals(emptyList<Any>(), caseModule.getOutputs(null))
        assertEquals(emptyList<Any>(), defaultModule.getOutputs(null))
        assertEquals(emptyList<Any>(), endModule.getOutputs(null))
    }

    // ───────────────────────── aiMetadata ─────────────────────────

    @Test
    fun `all four are temporary workflow only low risk`() {
        listOf(switchModule, caseModule, defaultModule, endModule).forEach { module ->
            val meta = module.aiMetadata
            assertNotNull("${module.id} 应有 aiMetadata", meta)
            assertTrue(
                "${module.id} 必须含 TEMPORARY_WORKFLOW",
                meta!!.usageScopes.contains(AiModuleUsageScope.TEMPORARY_WORKFLOW)
            )
            assertFalse(
                "${module.id} 不得含 DIRECT_TOOL（与 If / 菜单一致）",
                meta.usageScopes.contains(AiModuleUsageScope.DIRECT_TOOL)
            )
            assertEquals(module.id, AiModuleRiskLevel.LOW, meta.riskLevel)
        }
    }

    @Test
    fun `switch workflow step description explains the four parts and the branches pairing`() {
        val description = switchModule.aiMetadata.workflowStepDescription.orEmpty()
        // ⚠️ 缺了这些，AI 生成的结构会缺件（少 end、或 caseId 对不上 branches）
        assertTrue(description.contains("vflow.logic.switch.start"))
        assertTrue(description.contains("vflow.logic.switch.case"))
        assertTrue(description.contains("vflow.logic.switch.default"))
        assertTrue(description.contains("vflow.logic.switch.end"))
        assertTrue(description.contains("branches"))
        assertTrue(description.contains("caseId"))
    }

    @Test
    fun `case workflow step description ties caseId back to branches`() {
        val description = caseModule.aiMetadata.workflowStepDescription.orEmpty()
        assertTrue(description.contains("caseId"))
        assertTrue(description.contains("branches"))
    }

    // ───────────────────────── validate 真的接上了 ─────────────────────────

    private fun startStep(matches: List<String?>): ActionStep =
        ActionStep(
            SWITCH_START_ID,
            mapOf(
                SWITCH_VALUE_KEY to "",
                SWITCH_BRANCHES_KEY to SwitchBlockSupport.toParameters(
                    SwitchBlockSupport.createBranches(matches)
                ),
            )
        )

    @Test
    fun `validate rejects a blank case match value through the module entry point`() {
        val result = switchModule.validate(startStep(listOf("", null)), emptyList())
        assertFalse("空匹配值会匹配一切，必须被拦", result.isValid)
    }

    @Test
    fun `validate rejects duplicated case values through the module entry point`() {
        val result = switchModule.validate(startStep(listOf("ok", "OK", null)), emptyList())
        assertFalse("重复匹配值会让第二条成为死代码，必须被拦", result.isValid)
    }

    @Test
    fun `validate rejects two defaults through the module entry point`() {
        val result = switchModule.validate(startStep(listOf("ok", null, null)), emptyList())
        assertFalse("两张 Default 只有第一张生效，必须被拦", result.isValid)
    }

    @Test
    fun `validate accepts a well formed block through the module entry point`() {
        val result = switchModule.validate(startStep(listOf("ok", null)), emptyList())
        assertTrue(result.errorMessage.orEmpty(), result.isValid)
    }

    @Test
    fun `validate rejects an empty branches table`() {
        val result = switchModule.validate(startStep(emptyList()), emptyList())
        assertFalse(result.isValid)
    }

    // ───────────────────────── 执行语义 ─────────────────────────

    private fun plainStep(tag: String) = ActionStep(plain, mapOf("tag" to tag))

    private fun caseStep(caseId: String, match: String) =
        ActionStep(SWITCH_CASE_ID, mapOf(SWITCH_CASE_ID_KEY to caseId, SWITCH_MATCH_KEY to match))

    private fun defaultStep(caseId: String) =
        ActionStep(SWITCH_DEFAULT_ID, mapOf(SWITCH_CASE_ID_KEY to caseId))

    private fun contextFor(
        steps: List<ActionStep>,
        index: Int,
        value: String = ""
    ): ExecutionContext = ExecutionContext(
        applicationContext = ContextWrapper(null),
        variables = mutableMapOf(),
        magicVariables = mutableMapOf(SWITCH_VALUE_KEY to com.chaomixian.vflow.core.types.basic.VString(value)),
        services = ExecutionServices(),
        allSteps = steps,
        currentStepIndex = index,
        stepOutputs = mutableMapOf(),
        loopStack = Stack(),
        namedVariables = mutableMapOf(),
        workDir = File("build/test-workdir")
    )

    private suspend fun jumpTarget(module: com.chaomixian.vflow.core.module.ActionModule, context: ExecutionContext): Int {
        val result = module.execute(context) { }
        assertTrue("expected Jump but was $result", result is ExecutionResult.Signal)
        val signal = (result as ExecutionResult.Signal).signal
        assertTrue("expected Jump but was $signal", signal is ExecutionSignal.Jump)
        return (signal as ExecutionSignal.Jump).pc
    }

    @Test
    fun `matching case jumps to the first step of its body`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
            caseStep("b", "error"),
            plainStep("B1"),
            defaultStep("d"),
            plainStep("D1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(2, jumpTarget(switchModule, contextFor(steps, 0, "ok")))
        assertEquals(4, jumpTarget(switchModule, contextFor(steps, 0, "error")))
    }

    @Test
    fun `no match falls through to the default body`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
            defaultStep("d"),
            plainStep("D1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(4, jumpTarget(switchModule, contextFor(steps, 0, "nope")))
    }

    @Test
    fun `no match and no default skips the whole block`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(4, jumpTarget(switchModule, contextFor(steps, 0, "nope")))
    }

    @Test
    fun `the first matching case wins when several would match`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "OK"),
            plainStep("A1"),
            caseStep("b", "ok"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // looseEquals 忽略大小写 ⇒ 两个都匹配，取第一个
        assertEquals(2, jumpTarget(switchModule, contextFor(steps, 0, "ok")))
    }

    @Test
    fun `an unselected case card jumps past the end switch`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
            caseStep("b", "error"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // 走到 "error" 卡片（索引 3）⇒ 说明它没被选中 ⇒ 跳到 End 之后
        assertEquals(6, jumpTarget(caseModule, contextFor(steps, 3)))
    }

    @Test
    fun `default card jumps past the end switch`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
            defaultStep("d"),
            plainStep("D1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(6, jumpTarget(defaultModule, contextFor(steps, 3)))
    }

    @Test
    fun `end switch module succeeds`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertTrue(endModule.execute(contextFor(steps, 1)) { } is ExecutionResult.Success)
    }

    @Test
    fun `a broken structure fails instead of crashing`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
        )

        assertTrue(switchModule.execute(contextFor(steps, 0, "ok")) { } is ExecutionResult.Failure)
        assertTrue(caseModule.execute(contextFor(steps, 1)) { } is ExecutionResult.Failure)
        assertTrue(defaultModule.execute(contextFor(steps, 1)) { } is ExecutionResult.Failure)
    }

    @Test
    fun `a nested switch resolves its own end and branches`() = runBlocking {
        val steps = mutableListOf(
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("outer", "ok"),
            // ── 内层 ──
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("inner", "x"),
            plainStep("INNER"),
            ActionStep(SWITCH_END_ID, emptyMap()),
            // ── 内层结束 ──
            plainStep("OUTER"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // 外层命中 "ok" ⇒ 跳到内层 Switch 起始卡（索引 2）
        assertEquals(2, jumpTarget(switchModule, contextFor(steps, 0, "ok")))
        // 内层不匹配（无 Default）⇒ 跳到内层 End 之后（索引 6），而**不是**外层 End 之后
        assertEquals(6, jumpTarget(switchModule, contextFor(steps, 2, "nope")))
    }

    @Test
    fun `onStepDeleted of the start module removes the whole block`() {
        val steps = mutableListOf(
            plainStep("BEFORE"),
            ActionStep(SWITCH_START_ID, mapOf(SWITCH_VALUE_KEY to "")),
            caseStep("a", "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
            plainStep("AFTER"),
        )

        assertTrue(switchModule.onStepDeleted(steps, 1))

        assertEquals(listOf(plain, plain), steps.map { it.moduleId })
        assertEquals("BEFORE", steps[0].parameters["tag"])
        assertEquals("AFTER", steps[1].parameters["tag"])
    }

    @Test
    fun `onStepDeleted of the case module only removes that branch`() {
        val steps = mutableListOf(
            ActionStep(
                SWITCH_START_ID,
                mapOf(
                    SWITCH_VALUE_KEY to "",
                    SWITCH_BRANCHES_KEY to SwitchBlockSupport.toParameters(
                        listOf(SwitchBranch("a", "ok"), SwitchBranch("b", "error"))
                    ),
                )
            ),
            caseStep("a", "ok"),
            plainStep("A1"),
            caseStep("b", "error"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertTrue(caseModule.onStepDeleted(steps, 3))

        assertEquals(4, steps.size)
        assertEquals(plain, steps[2].moduleId)
        assertEquals("A1", steps[2].parameters["tag"])
    }
}
