package com.chaomixian.vflow.core.workflow.module.logic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `return_keys` 编辑链路的**接线锚定**（源码扫描型，形态照 `CoreDexFingerprintTest`）。
 *
 * ⚠️ **为什么必须有**：这条链路的失效模式全是**静默**的 ——
 *  - 显隐判据写错 ⇒ 该显示时不显示（或反之），没有任何行为测试会红；
 *  - 新 sheet 忘了隐藏「默认值 / 必填」两块 ⇒ 用户看到一个与返回键无关的表单；
 *  - 顺手改了 `DictionaryKVAdapter` 的行结构 ⇒ **9 处生产调用点**（HTTP 头/体、
 *    JS/Lua inputs、字典默认值…）一起变形，而那些模块的测试不会因此变红。
 *
 * ⚠️ 全部**先剥注释**再断言（源码里到处是这些符号的说明文字）。
 */
class ReturnKeysEditorWiringTest {

    private fun codeOnly(relative: String): String {
        val path = "src/main/java/com/chaomixian/vflow/$relative"
        val file = File(path)
        assertTrue("找不到 $path（当前目录 ${File(".").absolutePath}）", file.exists())
        val withoutBlockComments = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlockComments.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private val provider = "core/workflow/module/logic/StopAndReturnModuleUIProvider.kt"
    private val editorSheet = "core/workflow/module/logic/ReturnKeyEditorSheet.kt"
    private val stopAndReturn = "core/workflow/module/logic/StopAndReturnModule.kt"

    @Test
    fun `ui provider gates the editor on the live derived type being a dictionary`() {
        val src = codeOnly(provider)
        assertTrue(
            "the visibility criterion must use resolveValueType against DICTIONARY",
            src.contains("FunctionSignatureHelper.resolveValueType(value, allSteps.orEmpty())") &&
                src.contains("derivedType != VTypeRegistry.DICTIONARY.id")
        )
        assertTrue(
            "the provider must declare that it handles return_keys",
            src.contains("getHandledInputIds(): Set<String> = setOf(FunctionSignatureHelper.RETURN_KEYS_KEY)")
        )
        assertTrue(
            "readFromEditor must write back the declared keys",
            src.contains("\"name\" to it.name") && src.contains("\"type\" to it.type")
        )
    }

    @Test
    fun `return key sheet reuses the define-function layout and hides the unused blocks`() {
        val src = codeOnly(editorSheet)
        assertTrue(
            "must reuse the existing layout instead of a new XML",
            src.contains("R.layout.sheet_define_function_param_editor")
        )
        assertTrue(
            "must hide the default-value block",
            src.contains("R.id.layout_param_default).visibility = View.GONE")
        )
        assertTrue(
            "must hide the required switch",
            src.contains("R.id.switch_param_required).visibility = View.GONE")
        )
        assertTrue(
            "must override both hints (layout defaults say 参数名 / 参数类型)",
            src.contains("layoutParamName.hint = getString(R.string.editor_return_key_name_hint)") &&
                src.contains("dropdownKeyType.hint = getString(R.string.editor_return_key_type_hint)")
        )
    }

    @Test
    fun `stop and return module declares the return_keys input and wires the provider`() {
        val src = codeOnly(stopAndReturn)
        assertTrue(
            "must declare uiProvider",
            src.contains("override val uiProvider: ModuleUIProvider = StopAndReturnModuleUIProvider()")
        )
        assertTrue(
            "must declare the return_keys input",
            src.contains("id = FunctionSignatureHelper.RETURN_KEYS_KEY")
        )
        assertTrue(
            "the value input must support rich text (AGENTS.md hard rule)",
            src.contains("supportsRichText = true")
        )
    }

    @Test
    fun `dictionary kv adapter row structure is untouched`() {
        // 它在 9 处在用，本批明令不改其行结构。
        val src = codeFileText("ui/workflow_editor/DictionaryKVAdapter.kt")
        assertTrue(
            "DictionaryKVAdapter must not gain a per-row type dropdown",
            !src.contains("ReturnKey")
        )
    }

    private fun codeFileText(relative: String): String =
        File("src/main/java/com/chaomixian/vflow/$relative").readText()
}
