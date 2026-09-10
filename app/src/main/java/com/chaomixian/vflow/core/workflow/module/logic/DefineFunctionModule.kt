// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/DefineFunctionModule.kt
// 描述：「定义函数」模块。作为函数工作流的第一个动作卡片，负责配置参数声明与返回值声明，
//      并把签名写入 Workflow.functionSignature。运行时为空操作（无副作用）。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionParamError
import com.chaomixian.vflow.core.workflow.model.FunctionParamValidator
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

// 注：DefineFunctionModule 的签名数据流依赖编辑器在保存时聚合到 Workflow.functionSignature，
//     本模块本身不持有 Workflow 引用。

class DefineFunctionModule : BaseModule() {
    private val gson = Gson()
    private val paramsType = object : TypeToken<List<FunctionParam>>() {}.type

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
        // 摘要显示函数签名（决策 26）。直接从步骤参数实时读（不依赖保存后的 Workflow.functionSignature），
        // 这样编辑定义函数时摘要随参数变化而实时更新（决策 12「实时写入」）。
        val params = parseFunctionParams(step.parameters["functionParams"])
        if (params.isEmpty()) {
            return context.getString(R.string.summary_vflow_logic_define_function_no_signature)
        }
        val summary = buildString {
            append(context.getString(R.string.summary_vflow_logic_define_function_prefix))
            params.forEachIndexed { index, p ->
                if (index > 0) append(", ")
                val typeLabel = com.chaomixian.vflow.core.types.VTypeRegistry.getType(p.type).getLocalizedName(context)
                val required = if (p.isRequired) context.getString(R.string.editor_define_function_param_required) else context.getString(R.string.editor_define_function_param_optional)
                append("${p.name}($typeLabel$required)")
            }
        }
        return summary
    }

    private fun parseFunctionParams(raw: Any?): List<FunctionParam> {
        val json = raw as? String ?: return emptyList()
        return try {
            gson.fromJson(json, object : com.google.gson.reflect.TypeToken<List<FunctionParam>>() {}.type) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        // 决策 7：参数名非法（非 snake_case）与重复校验。
        val rawParams = step.parameters["functionParams"] as? String ?: return ValidationResult(isValid = true)
        val params: List<FunctionParam> = try {
            gson.fromJson(rawParams, paramsType) ?: emptyList()
        } catch (e: Exception) {
            return ValidationResult(isValid = true)
        }

        val validation = FunctionParamValidator.validate(params)
        if (!validation.isValid) {
            val message = when (validation.errorKind) {
                FunctionParamError.DUPLICATE_NAME ->
                    appContext.getString(R.string.editor_define_function_duplicate_name, validation.duplicateName.orEmpty())
                else -> appContext.getString(R.string.editor_define_function_invalid_name)
            }
            return ValidationResult(false, message)
        }
        return ValidationResult(isValid = true)
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 空操作：运行时无副作用，仅作为编辑器中的声明入口。
        return ExecutionResult.Success(emptyMap())
    }
}
