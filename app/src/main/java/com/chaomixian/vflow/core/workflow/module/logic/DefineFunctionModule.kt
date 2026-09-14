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

class DefineFunctionModule : BaseModule(), AiParameterNormalizer {
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

    /**
     * AI 元数据。
     *
     * **`workflowStepDescription` 说清"它是第一步、它声明后续能用的参数"**——
     * 这是让模型*设计*签名（而非只是写入）的关键：模型需要知道这个模块的产物
     * 是「其他步骤可引用的参数名」，才会去声明有意义的名字。
     *
     * **`inputHints["functionParams"]` 给结构契约**：不给的话模型只能猜
     * `functionParams` 里该放什么。重点三条——数组元素结构、**类型用简写**
     * （与 `create_variable` 的 `Allowed values: string, number, ...` 一致，
     * 也是模型在别的模块上已经习惯的写法）、名字必须 snake_case。
     */
    override val aiMetadata = temporaryWorkflowOnlyMetadata(
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "Declare this workflow as a function and define its parameter " +
            "signature. Must be the first step. Parameters declared here become available to " +
            "later steps and to callers of this workflow.",
        inputHints = mapOf(
            FUNCTION_PARAMS_KEY to "JSON array of objects, each {name, type, isRequired, defaultValue}. " +
                "`type` is a shorthand value: string, number, boolean, dictionary, list, image, " +
                "file, coordinate. `name` must be snake_case and unique within the function. " +
                "Omit this field entirely for a zero-argument function. " +
                "Later steps read a declared parameter as {{vars.<name>}} — the `vars.` prefix is " +
                "required; a bare {{<name>}} does not resolve and silently yields an empty value. " +
                "Example: [{\"name\":\"user_id\",\"type\":\"string\",\"isRequired\":true}]",
        ),
        // 故意不设 requiredInputIds：零参数函数是合法的，标必填会让模型以为必须有参数。
    )

    /**
     * 本模块的参数声明。
     *
     * **为什么这里必须声明 `functionParams`**：编辑器的签名编辑走 UIProvider
     * （见 [DefineFunctionModuleUIProvider.getHandledInputIds]），那条路径**不需要**
     * `getInputs()` 声明也知道该写什么键。但 **AI 只认 `getInputs()` /
     * `getDynamicInputs()`**——它求值得到空表，于是把 `functionParams` 判为非法键拒绝。
     * 结果是 AI 能建函数工作流、却建不出**带参数的**。
     *
     * 编辑器侧不会因此重复渲染：`ActionEditorUiModel` 会用
     * `getHandledInputIds()` 过滤掉 `functionParams`。
     *
     * **类型必须是 [ParameterType.ANY]**：`coerceInputValue` 对 STRING 走
     * `rawValue.toString()`，而 `JsonArray` 经 `normalizeJsonValue` 已变成
     * `List<Map<...>>`，`toString()` 产出的是 Kotlin 的 `{name=x}` **不是合法 JSON**，
     * 落库后 Gson 解析必炸。ANY 分支原样透传，交给 [normalizeFunctionParams] 转换。
     */
    override fun getInputs(): List<InputDefinition> = listOf(FUNCTION_PARAMS_INPUT)

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
            gson.fromJson(json, paramsType) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 把 AI 传入的松散结构规范化成 UIProvider 期望的 JSON 字符串（见 [AiParameterNormalizer]）。
     *
     * **这是 AI 路径独有的补课**：编辑器侧由 [DefineFunctionParamEditorSheet] 在保存时
     * 调 [FunctionParamTypeMapper.toFullTypeId] 并把列表 Gson 序列化，
     * 存进 `parameters["functionParams"]` 的**已经是 JSON 字符串**。
     * AI 走 JSON 入参，拿到的是 `List<Map<String, Any?>>`，必须在这里收敛到同一形态。
     *
     * **三处转换对应编辑器的既有行为**：
     * 1. 简写类型 → 全限定 ID（`"string"` → `"vflow.type.string"`）——
     *    与 `CreateVariableModule` 的 `TYPE_OPTIONS` 一致，也是模型在别的模块上
     *    已经习惯的写法；不转的话下游 `VTypeRegistry.getType` 匹配失败，类型退化成「任意」。
     * 2. 丢弃 `name` 为空的项——与 UIProvider 的 `filter { it.name.isNotBlank() }` 一致。
     * 3. 序列化为 JSON 字符串。
     *
     * **名字非法（非 snake_case）与重名不在这里拦**：那是 [validate] 的职责，
     * 它会把错误回传给模型自纠。此处若静默丢掉，模型会以为参数声明成功了。
     */
    override fun normalizeAiParameters(parameters: Map<String, Any?>): Map<String, Any?> {
        val raw = parameters["functionParams"] ?: return parameters
        // 已是标准形态（编辑器路径，或模型直接照抄了一段 JSON 字符串）：原样放行
        if (raw is String) return parameters

        val list = raw as? List<*> ?: return parameters
        val normalized = list.mapNotNull { item ->
            val map = item as? Map<*, *> ?: return@mapNotNull null
            val name = map["name"]?.toString()?.trim().orEmpty()
            if (name.isBlank()) return@mapNotNull null
            FunctionParam(
                name = name,
                type = FunctionParamTypeMapper.toFullTypeId(map["type"]?.toString()),
                defaultValue = map["defaultValue"],
                isRequired = map["isRequired"]?.let { coerceBoolean(it) } ?: false,
            )
        }

        return parameters + ("functionParams" to gson.toJson(normalized))
    }

    private fun coerceBoolean(value: Any?): Boolean {
        return when (value) {
            is Boolean -> value
            is String -> value.equals("true", ignoreCase = true)
            else -> false
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

    companion object {
        /** 参数声明的存储键。编辑器 UIProvider 与 AI 路径共用，改它等于破坏两条路径的对接。 */
        const val FUNCTION_PARAMS_KEY = "functionParams"

        /**
         * `functionParams` 的参数声明。
         *
         * **不给 `defaultValue`**：`BaseModule.createSteps()` 会把非空默认值预填进新卡片，
         * 而空列表的默认值没有意义——`loadParams` 遇到缺失的键会退化成空列表。
         * 少一个键，也少一份「空数组还是 null」的歧义。
         */
        private val FUNCTION_PARAMS_INPUT = InputDefinition(
            id = FUNCTION_PARAMS_KEY,
            name = "函数参数",
            nameStringRes = R.string.editor_group_function_params,
            staticType = ParameterType.ANY,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        )
    }
}
