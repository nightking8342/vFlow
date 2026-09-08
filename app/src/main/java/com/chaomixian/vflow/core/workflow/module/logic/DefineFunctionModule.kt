// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/DefineFunctionModule.kt
// 描述：「定义函数」模块。作为函数工作流的第一个动作卡片，负责配置参数声明与返回值声明，
//      并把签名写入 Workflow.functionSignature。运行时为空操作（无副作用）。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

// 注：DefineFunctionModule 的签名数据流依赖编辑器在保存时聚合到 Workflow.functionSignature，
//     本模块本身不持有 Workflow 引用。

class DefineFunctionModule : BaseModule() {
    override val id = "vflow.logic.define_function"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_define_function_name,
        descriptionStringRes = R.string.module_vflow_logic_define_function_desc,
        name = "定义函数",
        description = "声明本工作流为函数，配置参数与返回值。须作为工作流第一步。",
        iconRes = R.drawable.rounded_call_to_action_24,
        category = "逻辑控制",
        categoryId = "logic"
    )

    override val uiProvider: ModuleUIProvider? = DefineFunctionModuleUIProvider()

    override fun getInputs(): List<InputDefinition> = emptyList()

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = emptyList()

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        // 摘要显示函数签名（决策 26）
        val workflowId = findOwningWorkflowId(context, step)
        val signature = workflowId?.let { WorkflowManager(context).getWorkflow(it)?.functionSignature }
        if (signature == null) {
            return context.getString(R.string.summary_vflow_logic_define_function_no_signature)
        }
        val summary = buildString {
            append(context.getString(R.string.summary_vflow_logic_define_function_prefix))
            signature.params.forEachIndexed { index, p ->
                if (index > 0) append(", ")
                append("${p.name}(本)")
            }
            signature.returnDef?.let { ret ->
                append(" ")
                append(context.getString(R.string.summary_vflow_logic_define_function_return_prefix))
                append(ret.keys.joinToString(",") { it.name })
            }
        }
        return summary
    }

    private fun findOwningWorkflowId(context: Context, step: ActionStep): String? {
        // 「定义函数」是工作流第一步，但模块本身不持有 workflow id。
        // 这里通过 WorkflowManager 反查：找到包含该步骤的工作流。
        return WorkflowManager(context).getAllWorkflows()
            .firstOrNull { wf -> wf.steps.any { it.id == step.id } }
            ?.id
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 空操作：运行时无副作用，仅作为编辑器中的声明入口。
        return ExecutionResult.Success(emptyMap())
    }
}
