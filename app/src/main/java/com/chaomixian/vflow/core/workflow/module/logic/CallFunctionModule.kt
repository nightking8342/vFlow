// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/CallFunctionModule.kt
// 描述：「调用函数工作流」模块。按被调工作流声明的函数签名传参、隔离作用域、取回返回值。
//      与现有 CallWorkflowModule（共享命名变量）并存（决策 14）。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.types.VObjectFactory
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

class CallFunctionModule : BaseModule() {
    override val id = "vflow.logic.call_function"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_call_function_name,
        descriptionStringRes = R.string.module_vflow_logic_call_function_desc,
        name = "调用函数工作流",
        description = "按函数签名传参调用另一个函数工作流，并取回其返回值。",
        iconRes = R.drawable.rounded_swap_calls_24,
        category = "逻辑控制",
        categoryId = "logic"
    )

    override val uiProvider: ModuleUIProvider? = CallFunctionModuleUIProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "workflow_id",
            nameStringRes = R.string.param_vflow_logic_call_function_workflow_id_name,
            name = "函数工作流",
            staticType = ParameterType.STRING,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        )
        // 各参数输入框由 getDynamicInputs 依据选中函数工作流的签名动态生成
    )

    override fun getDynamicInputs(step: ActionStep?, allSteps: List<ActionStep>?): List<InputDefinition> {
        val base = getInputs().toMutableList()
        val workflowId = step?.parameters?.get("workflow_id") as? String ?: return base
        val signature = lookupSignature(workflowId) ?: return base

        // 为每个函数参数动态生成一个输入框；控件类型按参数类型映射（决策 5：与定义侧一致，支持绑定变量）。
        signature.params.forEach { param ->
            base += InputDefinition(
                id = param.name,
                name = param.name,
                staticType = toParameterType(param.type),
                acceptsMagicVariable = true,
                acceptsNamedVariable = true,
                supportsRichText = true
            )
        }
        return base
    }

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("result", nameStringRes = R.string.output_vflow_logic_call_function_result_name, name = "函数返回值", typeName = VTypeRegistry.ANY.id)
    )

    private fun lookupSignature(workflowId: String): FunctionSignature? {
        val workflow = WorkflowManager(appContext).getWorkflow(workflowId) ?: return null
        return workflow.functionSignature
    }

    private fun toParameterType(typeId: String): ParameterType {
        return when (typeId) {
            VTypeRegistry.NUMBER.id -> ParameterType.NUMBER
            VTypeRegistry.BOOLEAN.id -> ParameterType.BOOLEAN
            VTypeRegistry.LIST.id, VTypeRegistry.DICTIONARY.id, VTypeRegistry.IMAGE.id, VTypeRegistry.FILE.id -> ParameterType.ANY
            else -> ParameterType.STRING
        }
    }

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val workflowId = step.parameters["workflow_id"] as? String
        val workflowName = if (workflowId != null) {
            WorkflowManager(context).getWorkflow(workflowId)?.name ?: context.getString(R.string.summary_unknown_workflow)
        } else {
            context.getString(R.string.summary_no_workflow_selected)
        }
        val workflowPill = PillUtil.Pill(workflowName, "workflow_id")
        return PillUtil.buildSpannable(context, context.getString(R.string.summary_vflow_logic_call_function_prefix), workflowPill)
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val workflowId = context.getVariableAsString("workflow_id", "")
        val workflowToCall = WorkflowManager(context.applicationContext).getWorkflow(workflowId)
            ?: return ExecutionResult.Failure("执行错误", "找不到ID为 '$workflowId' 的函数工作流。")
        val signature = workflowToCall.functionSignature
            ?: return ExecutionResult.Failure("执行错误", "工作流 '${workflowToCall.name}' 不是函数工作流。")

        // 防止无限递归
        if (context.workflowStack.contains(workflowId)) {
            return ExecutionResult.Failure("递归错误", "检测到循环调用: ${context.workflowStack.joinToString(" -> ")} -> $workflowId")
        }

        // 收集调用方传入的实参（决策 18：调用方侧校验必填参数）
        val injectedVariables = mutableMapOf<String, com.chaomixian.vflow.core.types.VObject>()
        for (param in signature.params) {
            val raw = context.getVariableAsRaw(param.name)
            if (raw != null) {
                injectedVariables[param.name] = VObjectFactory.from(raw)
            } else if (param.defaultValue != null) {
                injectedVariables[param.name] = VObjectFactory.from(param.defaultValue)
            } else if (param.isRequired) {
                return ExecutionResult.Failure("缺少参数", "缺少必填参数 '${param.name}'")
            }
        }

        onProgress(ProgressUpdate("正在调用函数工作流: ${workflowToCall.name}"))

        val subResult = WorkflowExecutor.executeSubWorkflow(workflowToCall, context, injectedVariables)

        onProgress(ProgressUpdate("函数工作流 '${workflowToCall.name}' 执行完毕。"))

        return ExecutionResult.Success(mapOf("result" to subResult.returnValue))
    }
}
