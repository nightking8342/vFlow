package com.chaomixian.vflow.core.workflow.model

import android.content.ContextWrapper
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionServices
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.parser.TemplateParser
import com.chaomixian.vflow.core.types.parser.TemplateSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Stack

/**
 * [TriggerLabel] 的纯函数测试。
 *
 * 重点锁「改错了不报错、只静默变差」的地方：
 * - `withLabel` 对空白必须**删键**而不是写空串（写空串会让卡片回显成一行空白、
 *   `get_workflow` 输出不可区分）；
 * - `labelOf` 对非 String 必须返回空串（否则手工改 JSON 塞数字会得到一个「看起来有标签」的假象）；
 * - `VARIABLE_NAME` 必须**等于** `KEY`（防有人「顺手」把它们拆成两个不同的名字）；
 * - `VARIABLE_REFERENCE` 必须真的走**命名变量**解析分支（喂真实字符串给真实解析器，
 *   不构造 `mapOf` —— 后者会绕过「`[[ ]]` 是不是命名变量」这个真正要验的点）。
 */
class TriggerLabelTest {

    private fun step(
        parameters: Map<String, Any?> = emptyMap(),
        id: String = "trigger-1"
    ): ActionStep = ActionStep(moduleId = "vflow.trigger.manual", parameters = parameters, id = id)

    private fun workflow(
        triggers: List<ActionStep> = emptyList(),
        steps: List<ActionStep> = emptyList()
    ): Workflow = Workflow(id = "wf-1", name = "测试工作流", triggers = triggers, steps = steps)

    // ---------- labelOf ----------

    @Test
    fun `labelOf returns empty string when the key is absent`() {
        assertEquals("", TriggerLabel.labelOf(step()))
    }

    @Test
    fun `labelOf returns empty string for a blank value`() {
        assertEquals("", TriggerLabel.labelOf(step(mapOf(TriggerLabel.KEY to "   "))))
        assertEquals("", TriggerLabel.labelOf(step(mapOf(TriggerLabel.KEY to ""))))
    }

    @Test
    fun `labelOf trims surrounding whitespace`() {
        assertEquals("每日巡检", TriggerLabel.labelOf(step(mapOf(TriggerLabel.KEY to "  每日巡检 "))))
    }

    @Test
    fun `labelOf returns empty string for a non-string value`() {
        // 手工编辑 JSON 塞数字属于用户自己破坏契约。契约是「标签是字符串」，
        // 静默丢标签好过让 "1" 与 1 的判等语义分叉。
        assertEquals("", TriggerLabel.labelOf(step(mapOf(TriggerLabel.KEY to 1))))
        assertEquals("", TriggerLabel.labelOf(step(mapOf(TriggerLabel.KEY to true))))
        assertEquals("", TriggerLabel.labelOf(step(mapOf(TriggerLabel.KEY to null))))
    }

    // ---------- withLabel ----------

    @Test
    fun `withLabel writes the trimmed value`() {
        val result = TriggerLabel.withLabel(step(), "  晨间  ")
        assertEquals("晨间", result.parameters[TriggerLabel.KEY])
    }

    @Test
    fun `withLabel removes the key for blank input instead of writing an empty string`() {
        val existing = step(mapOf(TriggerLabel.KEY to "旧标签"))

        val cleared = TriggerLabel.withLabel(existing, "   ")

        assertFalse(
            "空白必须删键，不能留一个空串值——否则与「没有标签」不可区分",
            cleared.parameters.containsKey(TriggerLabel.KEY),
        )
    }

    @Test
    fun `withLabel overwrites an existing label`() {
        val result = TriggerLabel.withLabel(step(mapOf(TriggerLabel.KEY to "旧")), "新")
        assertEquals("新", result.parameters[TriggerLabel.KEY])
    }

    @Test
    fun `withLabel does not disturb other parameters`() {
        val original = step(
            mapOf(
                "some_input" to "value",
                "__error_policy" to "skip",
                TriggerLabel.KEY to "旧",
            )
        )

        val result = TriggerLabel.withLabel(original, "新")

        assertEquals("value", result.parameters["some_input"])
        assertEquals("skip", result.parameters["__error_policy"])
        assertEquals("新", result.parameters[TriggerLabel.KEY])
        // 原始步骤不被就地修改（返回的是新实例）
        assertEquals("旧", original.parameters[TriggerLabel.KEY])
    }

    @Test
    fun `withLabel on a step without the key adds exactly one key`() {
        val original = step(mapOf("a" to 1))
        val result = TriggerLabel.withLabel(original, "标签")
        assertEquals(original.parameters.size + 1, result.parameters.size)
    }

    // ---------- labelFor ----------

    @Test
    fun `labelFor returns empty string when triggerStepId is null`() {
        val wf = workflow(triggers = listOf(step(mapOf(TriggerLabel.KEY to "标签"))))
        assertEquals("", TriggerLabel.labelFor(wf, null))
    }

    @Test
    fun `labelFor returns empty string when the id matches no trigger`() {
        val wf = workflow(triggers = listOf(step(mapOf(TriggerLabel.KEY to "标签"), id = "t1")))
        assertEquals("", TriggerLabel.labelFor(wf, "does-not-exist"))
    }

    @Test
    fun `labelFor returns the label of the matched trigger`() {
        val wf = workflow(
            triggers = listOf(
                step(mapOf(TriggerLabel.KEY to "甲"), id = "t1"),
                step(mapOf(TriggerLabel.KEY to "乙"), id = "t2"),
            )
        )
        assertEquals("甲", TriggerLabel.labelFor(wf, "t1"))
        assertEquals("乙", TriggerLabel.labelFor(wf, "t2"))
    }

    @Test
    fun `labelFor returns empty string when the matched trigger has no label`() {
        val wf = workflow(triggers = listOf(step(id = "t1")))
        assertEquals("", TriggerLabel.labelFor(wf, "t1"))
    }

    @Test
    fun `labelFor ignores steps that are not triggers`() {
        // 标签是**触发器**的属性。steps 里即便碰巧有同 id 的步骤也不该被当成标签来源。
        val wf = workflow(
            triggers = emptyList(),
            steps = listOf(step(mapOf(TriggerLabel.KEY to "不该被读到"), id = "s1")),
        )
        assertEquals("", TriggerLabel.labelFor(wf, "s1"))
    }

    // ---------- 契约锁定 ----------

    @Test
    fun `variable name and storage key are the same name`() {
        // 契约：三处同名（存储 / 工作流内引用 / AI 读写）。
        // 分开只是为了让调用点的意图可读，不是为了将来能改其中一个。
        assertEquals(TriggerLabel.KEY, TriggerLabel.VARIABLE_NAME)
    }

    @Test
    fun `the key uses the double underscore retained-parameter prefix`() {
        // 与 __error_policy / __retry_count 对齐。单下划线会与模块可能声明的参数撞车。
        assertTrue(TriggerLabel.KEY.startsWith("__"))
        assertFalse(
            "不得退化成单下划线形态",
            TriggerLabel.KEY.removePrefix("__").startsWith("_"),
        )
    }

    @Test
    fun `the reference form parses as a named variable`() {
        // ⚠️ 这条是本文件最重要的一条：它喂**真实字符串**给**真实解析器**，
        //    验证用户拍板的 `[[ ]]` 形式真的走命名变量分支。
        //    用 `mapOf` 构造会绕过这个点、恰好不测任何东西。
        val segments = TemplateParser(TriggerLabel.VARIABLE_REFERENCE).parse()

        assertEquals(
            "引用形式必须解析成**单个** Variable 段。实际：$segments",
            1,
            segments.size,
        )
        val segment = segments.single()
        assertTrue("必须是 Variable 段，实际：$segment", segment is TemplateSegment.Variable)
        val variable = segment as TemplateSegment.Variable
        assertTrue(
            "`${TriggerLabel.VARIABLE_REFERENCE}` 必须解析为**命名变量**（[[ ]] 语法）。" +
                "若为 false，用户写的引用会走 stepOutputs 查表、永远解析不到。",
            variable.isNamedVariable,
        )
        assertEquals(listOf(TriggerLabel.VARIABLE_NAME), variable.path)
        assertEquals(TriggerLabel.VARIABLE_REFERENCE, variable.rawExpression)
    }

    @Test
    fun `the reference resolves through the named variable channel`() {
        // 端到端：命名变量里放了标签 ⇒ `[[__trigger_label]]` 解析成它。
        val context = createContext(
            namedVariables = mutableMapOf<String, VObject>(TriggerLabel.VARIABLE_NAME to VString("每日巡检"))
        )

        assertEquals("每日巡检", VariableResolver.resolve(TriggerLabel.VARIABLE_REFERENCE, context))
    }

    @Test
    fun `an empty label resolves to the empty string not to a literal`() {
        // 这是「必须恒注入空串而不是不注入」的机器化锁：
        // 注入 VString("") ⇒ 解析出空串；不注入 ⇒ 会落到 `{...}` 字面量兜底分支，
        // If 比较恒 false 且无报错。
        val context = createContext(
            namedVariables = mutableMapOf<String, VObject>(TriggerLabel.VARIABLE_NAME to VString(""))
        )

        val resolved = VariableResolver.resolve(TriggerLabel.VARIABLE_REFERENCE, context)

        assertEquals("", resolved)
        assertFalse(
            "解析结果不得是字面量回退形态（那说明它根本没走命名变量分支）",
            resolved.contains("__trigger_label"),
        )
    }

    private fun createContext(
        namedVariables: MutableMap<String, VObject> = mutableMapOf()
    ): ExecutionContext {
        return ExecutionContext(
            applicationContext = ContextWrapper(null),
            variables = mutableMapOf(),
            magicVariables = mutableMapOf(),
            services = ExecutionServices(),
            allSteps = emptyList<ActionStep>(),
            currentStepIndex = 0,
            stepOutputs = mutableMapOf(),
            loopStack = Stack(),
            namedVariables = namedVariables,
            workDir = File("build/test-workdir")
        )
    }
}
