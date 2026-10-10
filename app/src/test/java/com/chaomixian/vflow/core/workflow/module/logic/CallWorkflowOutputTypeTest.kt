package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.FunctionReturn
import com.chaomixian.vflow.core.workflow.model.FunctionSignatureHelper
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 两个调用模块（`call_workflow` / `call_function`）返回值类型的回归测试。
 *
 * ⚠️ **为什么一半是源码扫描型**：`getDynamicOutputs` / `getOutputs` 都要读被调工作流
 * （`WorkflowManager(appContext)`），纯 JVM 里起不来。而本改动最危险的失效模式是
 * 「推导逻辑写对了、但调用点没接上」—— 本仓库已三次踩过这类坑
 * （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint` 等）。
 * 故：**纯函数语义**用单测锁、**接线**用源码扫描锁。
 *
 * ⚠️ 源码扫描**必须先剥注释**：本批在源码里加了大量解释性注释，其中就写着这些符号名，
 * 只做 `contains` 会把「注释里提到」当成「代码里调用了」。
 */
class CallWorkflowOutputTypeTest {

    private companion object {
        val CONSUMER_PATHS = listOf(
            "core/workflow/module/logic/CallFunctionModule.kt",
            "core/workflow/module/logic/DefineFunctionModuleUIProvider.kt",
            "ui/workflow_editor/EditorMoreOptionsSheet.kt",
            "ui/chat/ChatAgentModuleExecutor.kt",
            "core/workflow/WorkflowManager.kt"
        )
    }

    /** 读源码并剥掉块注释 / 行注释，只留代码。 */
    private fun codeOnly(relative: String): String {
        val path = "src/main/java/com/chaomixian/vflow/$relative"
        val file = File(path)
        assertTrue("找不到 $path（当前目录 ${File(".").absolutePath}）", file.exists())
        val withoutBlockComments = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlockComments.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
    }

    // ---------- 读取侧类型兜底（纯函数） ----------

    @Test
    fun `dictionary type keeps declared keys`() {
        val ret = FunctionReturn(
            type = VTypeRegistry.DICTIONARY.id,
            keys = listOf(ReturnKey("code", VTypeRegistry.NUMBER.id))
        )
        assertEquals(1, FunctionSignatureHelper.dictionaryKeysFor(ret).size)
    }

    @Test
    fun `non-dictionary type drops declared keys`() {
        // 用户先声明了键、后把 value 改成 {{截图步骤.image}} ⇒ 选择器不得再列出那些键
        val ret = FunctionReturn(
            type = VTypeRegistry.IMAGE.id,
            keys = listOf(ReturnKey("code", VTypeRegistry.NUMBER.id))
        )
        assertTrue(FunctionSignatureHelper.dictionaryKeysFor(ret).isEmpty())
    }

    @Test
    fun `any type drops declared keys`() {
        val ret = FunctionReturn(
            type = VTypeRegistry.ANY.id,
            keys = listOf(ReturnKey("code", VTypeRegistry.NUMBER.id))
        )
        assertTrue(FunctionSignatureHelper.dictionaryKeysFor(ret).isEmpty())
    }

    // ---------- 接线（源码扫描） ----------

    @Test
    fun `call_workflow routes result through live inference and the type guard`() {
        val src = codeOnly("core/workflow/module/logic/CallWorkflowModule.kt")
        assertTrue(
            "call_workflow must derive the result type on the fly",
            src.contains("FunctionSignatureHelper.deriveReturn(subWorkflow.steps)")
        )
        assertTrue(
            "call_workflow must apply the dictionary type guard",
            src.contains("FunctionSignatureHelper.dictionaryKeysFor(ret)")
        )
    }

    @Test
    fun `call_function routes result through live inference and the type guard`() {
        val src = codeOnly("core/workflow/module/logic/CallFunctionModule.kt")
        assertTrue(
            "call_function must derive the result type on the fly",
            src.contains("FunctionSignatureHelper.deriveReturn(subSteps)")
        )
        assertTrue(
            "call_function must apply the dictionary type guard",
            src.contains("FunctionSignatureHelper.dictionaryKeysFor(ret)")
        )
        assertTrue(
            "call_function must no longer read the stopped-writing returnDef",
            !src.contains("lookupSignature(step)?.returnDef")
        )
    }

    @Test
    fun `no production reader still consumes functionSignature returnDef`() {
        // returnDef 已停写；除 wire 反序列化（WorkflowFunctionSignatureCodec）外不得有消费者。
        for (relative in CONSUMER_PATHS) {
            val src = codeOnly(relative)
            assertTrue(
                "$relative must not read functionSignature.returnDef any more",
                !src.contains("?.returnDef") && !src.contains("signature.returnDef")
            )
        }
    }
}
