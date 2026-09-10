// 文件：main/java/com/chaomixian/vflow/core/workflow/model/FunctionParamValidator.kt
// 描述：函数参数名校验工具（决策 7：snake_case + 去重）。纯 Kotlin，无 Android 依赖，便于 JVM 单测。
//      这是 fork 新增文件（上游无此文件），供 DefineFunctionModule.validate 与
//      DefineFunctionParamEditorSheet 复用，避免校验逻辑重复。

package com.chaomixian.vflow.core.workflow.model

/**
 * 函数参数名校验结果。
 * @param isValid 是否通过。
 * @param errorMessage 校验失败原因（用于本地化，由调用方提供文案）。
 */
data class FunctionParamValidation(
    val isValid: Boolean,
    val errorKind: FunctionParamError? = null,
    val duplicateName: String? = null
)

enum class FunctionParamError {
    /** 名字为空或非 snake_case。 */
    INVALID_NAME,
    /** 与已有参数同名。 */
    DUPLICATE_NAME
}

object FunctionParamValidator {

    /**
     * 校验单个参数名是否合法。
     * 与项目风格一致（不强制 snake_case）：仅要求非空，且不含会破坏 [[参数名]] 引用的字符
     * （[、]、.、$、{、}、空白）。
     */
    fun isValidName(name: String): Boolean {
        if (name.isBlank()) return false
        // 禁止会破坏变量引用的特殊字符（. [ ] $ { } 空格 / 制表 / 换行）
        val forbidden = charArrayOf('[', ']', '.', '$', '{', '}', ' ', '\t', '\n')
        if (name.any { it in forbidden }) return false
        return true
    }

    /**
     * 校验一组函数参数：非空 + 去重（排除自身）。
     * @param params 待校验的参数列表。
     * @param editingIndex 当前编辑中的参数下标（用于排除自身）；新增不传（-1）。
     */
    fun validate(params: List<FunctionParam>, editingIndex: Int = -1): FunctionParamValidation {
        for (param in params) {
            val name = param.name.trim()
            if (!isValidName(name)) return FunctionParamValidation(false, FunctionParamError.INVALID_NAME)
        }
        // 去重检查
        val grouped = params.mapIndexed { index, p -> p.name.trim() to index }
            .groupBy { it.first }
            .filterValues { it.size > 1 }
        if (grouped.isNotEmpty()) {
            return FunctionParamValidation(false, FunctionParamError.DUPLICATE_NAME, grouped.keys.first())
        }
        return FunctionParamValidation(true)
    }
}
