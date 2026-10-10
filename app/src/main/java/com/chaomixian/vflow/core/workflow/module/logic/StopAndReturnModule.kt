// main/java/com/chaomixian/vflow/core/workflow/module/logic/StopAndReturnModule.kt
package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionSignatureHelper
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * "停止并返回" 模块。
 * 立即终止当前工作流（或子工作流）的执行，并返回一个值。
 */
class StopAndReturnModule : BaseModule() {
    override val id = "vflow.logic.return"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_return_name,
        descriptionStringRes = R.string.module_vflow_logic_return_desc,
        name = "停止并返回",
        description = "停止当前工作流的执行并返回一个值。",
        iconRes = R.drawable.rounded_output_24,
        category = "逻辑控制",
        categoryId = "logic"
    )

    override val uiProvider: ModuleUIProvider = StopAndReturnModuleUIProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "value",
            nameStringRes = R.string.param_vflow_logic_return_value_name,
            name = "返回值",
            staticType = ParameterType.ANY,
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            // ⚠️ 文本参数接受变量 ⇒ 必须 supportsRichText = true（AGENTS.md 硬规则），
            //    否则 🪄 选出的变量在输入框里只显示底层语法、渲染不成胶囊。
            supportsRichText = true
        ),
        InputDefinition(
            id = FunctionSignatureHelper.RETURN_KEYS_KEY,
            nameStringRes = R.string.param_vflow_logic_return_return_keys_name,
            name = "返回键",
            staticType = ParameterType.ANY,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
            // 该参数由 uiProvider 的 getHandledInputIds 整体接管（不进通用表单），
            // 显隐与位置完全由 createEditor 决定。
            supportsRichText = false
        )
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val valuePill = PillUtil.createPillFromParam(
            step.parameters["value"],
            getInputs().find { it.id == "value" }
        )
        return PillUtil.buildSpannable(context, context.getString(R.string.summary_vflow_logic_stop_and_return_prefix), valuePill)
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        onProgress(ProgressUpdate("准备返回值..."))
        val returnValue = context.getVariable("value")
        return ExecutionResult.Signal(ExecutionSignal.Return(returnValue))
    }
}