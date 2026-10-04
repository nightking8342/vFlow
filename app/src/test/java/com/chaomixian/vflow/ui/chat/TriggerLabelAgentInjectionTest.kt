package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TriggerLabel
import com.chaomixian.vflow.core.workflow.module.logic.CallFunctionModule
import com.chaomixian.vflow.core.workflow.module.logic.IfModule
import com.chaomixian.vflow.core.workflow.module.triggers.LogcatTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.ManualTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.SmsTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.TimeTriggerModule
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * 触发器标签的 AI 侧注入测试。
 *
 * 存在理由：AI 侧 `buildParameters` / `applyParameterPatch` 把**未知 key 判 reject**。
 * 标签不由模块声明（否则会被推进编辑器的通用参数表单，与「标签不放参数 sheet」冲突），
 * 因此必须在 `resolveModuleInputDefinitions` 里对 `vflow.trigger.*` 统一补齐它的
 * [com.chaomixian.vflow.core.module.InputDefinition]。不补的话 AI 写不进标签，
 * 而且 `update_workflow` 会**整份补丁不落地**（`executeUpdateWorkflow` 见
 * `validationErrors` 非空就不写库）。
 *
 * ⚠️ 本仓库无 Robolectric，`ModuleRegistry.register(...)` 的 context 参数默认 null，
 * 故这里不需要 Context。
 */
class TriggerLabelAgentInjectionTest {

    @Before
    fun setUp() {
        ModuleRegistry.reset()
        ModuleRegistry.register(ManualTriggerModule())
        ModuleRegistry.register(TimeTriggerModule())
        ModuleRegistry.register(SmsTriggerModule())
        ModuleRegistry.register(LogcatTriggerModule())
        ModuleRegistry.register(IfModule())
        ModuleRegistry.register(CallFunctionModule())
    }

    @After
    fun tearDown() {
        ModuleRegistry.reset()
    }

    private fun definitionsFor(moduleId: String) = run {
        val module = requireNotNull(ModuleRegistry.getModule(moduleId)) { "模块未注册：$moduleId" }
        val step = module.createSteps().firstOrNull() ?: ActionStep(module.id, emptyMap())
        resolveModuleInputDefinitions(module, step)
    }

    @Test
    fun `every trigger module exposes the label input`() {
        listOf(
            "vflow.trigger.manual",
            "vflow.trigger.time",
            "vflow.trigger.sms",
            "vflow.trigger.logcat",
        ).forEach { moduleId ->
            val ids = definitionsFor(moduleId).map { it.id }
            assertTrue(
                "$moduleId 必须暴露 ${TriggerLabel.KEY}，否则 AI 写标签会被判成 unknown。实际：$ids",
                ids.contains(TriggerLabel.KEY),
            )
        }
    }

    @Test
    fun `non-trigger modules do not get the label input`() {
        // 反向锁：不能「顺手」给所有模块都加。
        listOf("vflow.logic.if.start", "vflow.logic.call_function").forEach { moduleId ->
            val ids = definitionsFor(moduleId).map { it.id }
            assertFalse("$moduleId 不该有 ${TriggerLabel.KEY}。实际：$ids", ids.contains(TriggerLabel.KEY))
        }
    }

    @Test
    fun `the injected label input is a plain string that does not accept magic variables`() {
        val definition = definitionsFor("vflow.trigger.manual").single { it.id == TriggerLabel.KEY }

        assertEquals(
            "必须是 STRING —— `isInputSupported` 会过滤掉 ANY 且无 acceptedMagicVariableTypes 的项",
            ParameterType.STRING,
            definition.staticType,
        )
        assertFalse(
            "标签是字面量，不是变量引用。允许魔法变量会让用户写出「标签里再嵌变量」这种无人解析的形态",
            definition.acceptsMagicVariable,
        )
    }

    @Test
    fun `the injected label input is visible to the model`() {
        val definition = definitionsFor("vflow.trigger.manual").single { it.id == TriggerLabel.KEY }
        assertFalse(
            "isHidden 必须为 false，否则 query_module_schema 不会展示它、模型发现不了这个字段",
            definition.isHidden,
        )
    }

    @Test
    fun `injection does not drop any static key`() {
        // 注入是 `base + label` 的追加，distinctBy 之后不该丢任何静态键。
        // 复用 ModuleInputDefinitionsTest 的性质，但把范围限定在本次会被注入的模块上
        // （那一条覆盖全部模块，这一条覆盖「注入路径」）。
        listOf(
            "vflow.trigger.manual",
            "vflow.trigger.time",
            "vflow.trigger.sms",
            "vflow.trigger.logcat",
            "vflow.logic.if.start",
        ).forEach { moduleId ->
            val module = requireNotNull(ModuleRegistry.getModule(moduleId))
            val staticIds = module.getInputs().map { it.id }.toSet()
            val resolved = definitionsFor(moduleId).map { it.id }.toSet()
            val missing = staticIds - resolved
            assertTrue("$moduleId 注入后丢了静态键：$missing", missing.isEmpty())
        }
    }

    @Test
    fun `the label input appears exactly once`() {
        val ids = definitionsFor("vflow.trigger.time").map { it.id }
        assertEquals(
            "distinctBy 之后 ${TriggerLabel.KEY} 只能出现一次。实际：$ids",
            1,
            ids.count { it == TriggerLabel.KEY },
        )
    }

    // ---------- 源码扫描型接线锚定 ----------

    @Test
    fun `the executor does not implement the injection by touching module getInputs`() {
        // ⚠️ 存在理由：另一种「看起来更简单」的实现方式是给 28 个触发器模块各加一行
        //    `getInputs()`（或改触发器的基类）。那样做的**后果是把标签推进编辑器的
        //    通用参数表单区**（编辑器由 `getDynamicInputs` 驱动），与用户拍板的
        //    「标签不放进参数 sheet」直接冲突 —— 而且**不会有任何报错**。
        //    这条断言把「注入只发生在 resolveModuleInputDefinitions 一处」钉住。
        //
        // 判据：ChatAgentModuleExecutor 里 `getInputs()` 的出现次数不得增加。
        // 基线 = 2（一处是 `resolveModuleInputDefinitions` 里的 `module.getInputs()`，
        //        另一处是 KDoc 里的同名文本——剥注释后应降为 1，故断言用剥注释后的值）。
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt"
        )
        val calls = SourceScan.countOccurrences(source, "module.getInputs()")
        assertEquals(
            "注入实现不得依赖 module.getInputs()（那会把标签推进模块定义）。实际调用数：$calls",
            1,
            calls,
        )
    }

    @Test
    fun `the executor injects the label for the trigger prefix only`() {
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt"
        )
        val body = SourceScan.functionBody(source, "internal fun resolveModuleInputDefinitions(")
        requireNotNull(body) { "找不到 resolveModuleInputDefinitions 函数体" }

        assertTrue(
            "必须按 vflow.trigger. 前缀判定",
            body.contains("TRIGGER_MODULE_PREFIX"),
        )
        assertTrue(
            "必须真的把标签定义追加进结果",
            body.contains("triggerLabelInputDefinition()"),
        )
        // 防空转：函数体不能是空的
        assertTrue("函数体异常短，疑似扫描失败", body.lines().size > 5)
    }

    @Test
    fun `ai facing text references the constants rather than a second literal`() {
        // ⚠️ 存在理由：文案里再写一份 `"__trigger_label"` 字面量会让「三处同名」这条契约
        //    在某处悄悄漂移，而编译不会报错。
        listOf(
            "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentToolRegistry.kt",
            "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentSkillRouter.kt",
        ).forEach { path ->
            val source = SourceScan.stripped(path)
            assertTrue(
                "$path 应引用 TriggerLabel.KEY",
                source.contains("TriggerLabel.KEY"),
            )
            assertTrue(
                "$path 应引用 TriggerLabel.VARIABLE_NAME",
                source.contains("TriggerLabel.VARIABLE_NAME"),
            )
        }
    }

    // ---------- 三语字符串 ----------

    @Test
    fun `all three locales define every new trigger label string`() {
        // 三语必须同步：漏一个会让该语言下回落成另一个语言的文案（或直接崩）。
        val keys = listOf(
            "trigger_label_button_desc",
            "trigger_label_sheet_title",
            "trigger_label_sheet_hint",
            "trigger_label_sheet_clear",
            "trigger_label_display_prefix",
            "editor_group_trigger_label",
            "trigger_label_variable_name",
            "trigger_label_input_name",
        )
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml",
            "src/main/res/values-ja/strings.xml",
        ).forEach { path ->
            val text = SourceScan.file(path).readText()
            val missing = keys.filterNot { text.contains("name=\"$it\"") }
            assertTrue("$path 缺少字符串：$missing", missing.isEmpty())
        }
    }

    // ---------- 全仓反向断言 ----------

    @Test
    fun `no source file uses a single underscore literal`() {
        // ⚠️ 存在理由：命名定案是**双下划线** `__trigger_label`（对齐 `__error_policy`）。
        //    写成单下划线 `trigger_label` 会与模块可能声明的参数名撞车，
        //    而且**不会有任何报错** —— 只是标签读不出来。
        //
        // 判据：源码里不得出现完整的 `"trigger_label"` 字符串字面量。
        // ⚠️ `"__trigger_label"` 的 `t` 前面是 `_` 而不是 `"`，字符边界不匹配，故不会被误判。
        //
        // ⚠️ 这里不用 `SourceScan.file(...)` —— 它的 `check(f.isFile)` 是给**单个文件**用的，
        //    传目录会直接断言失败。
        val root = File("src/main/java")
        check(root.isDirectory) { "源码目录不存在（测试工作目录应为 app/）：${root.absolutePath}" }
        val offenders = mutableListOf<String>()
        var scanned = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
            scanned++
            val source = SourceScan.stripCommentsPreservingStructure(file.readText())
            if (source.contains("\"trigger_label\"")) {
                offenders += file.path
            }
        }

        assertTrue("扫到的 .kt 文件数异常：$scanned", scanned > 100)
        assertTrue(
            "以下文件把 trigger_label 写成了单下划线形态：$offenders",
            offenders.isEmpty(),
        )
    }
}
