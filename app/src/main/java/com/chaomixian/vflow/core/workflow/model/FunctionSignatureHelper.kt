// 文件：main/java/com/chaomixian/vflow/core/workflow/model/FunctionSignatureHelper.kt
// 描述：函数工作流签名的静态推导辅助。用于「保存时」从工作流的「停止并返回」步骤
//      静态提取返回值键，写入 functionSignature.returnDef（决策 9：保存时扫描）。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.types.VTypeRegistry

/**
 * 函数工作流签名的静态推导工具。
 * 只在编辑器「保存工作流」时调用（A1），不做运行时注入。
 */
object FunctionSignatureHelper {

    /**
     * 从工作流步骤中静态推导返回值定义。
     * 若「停止并返回」的 value 能静态解出一个字典，则提取其键生成 [FunctionReturn]；
     * 否则返回 null（表示返回值不是可枚举的字典，调用方仍可手动输入键）。
     *
     * @param steps 工作流的全部动作步骤。
     * @return 返回值定义；无法静态推导时返回 null。
     */
    fun deriveReturnDef(steps: List<ActionStep>): FunctionReturn? {
        // 找「停止并返回」步骤（vflow.logic.return）
        val returnStep = steps.firstOrNull { it.moduleId == "vflow.logic.return" } ?: return null
        val value = returnStep.parameters["value"] ?: return null

        val keys = extractDictionaryKeys(value, steps) ?: return null
        return FunctionReturn(
            type = VTypeRegistry.DICTIONARY.id,
            keys = keys
        )
    }

    /**
     * 从「停止并返回」的 value 静态提取字典键。
     *
     * 支持三种情况（决策 24）：
     * 1. value 是字面量字典（Map<*, *>）→ 直接读键。
     * 2. value 是魔法变量引用 `{{stepId.variable}}`，且指向一个「创建字典变量」步骤 → 追读该步骤的键。
     * 3. 其他情况 → 无法静态推导，返回 null。
     */
    private fun extractDictionaryKeys(value: Any?, steps: List<ActionStep>): List<ReturnKey>? {
        // 情况 1：字面量字典
        if (value is Map<*, *>) {
            return value.entries.mapNotNull { (k, v) ->
                val keyName = k?.toString() ?: return@mapNotNull null
                ReturnKey(keyName, inferType(v))
            }
        }

        // 情况 2：魔法变量引用，指向「创建字典变量」步骤
        if (value is String && value.isMagicVariableRef()) {
            val ref = value.trim()
            val stepId = ref.removePrefix("{{").removeSuffix("}}").substringBefore(".")
                .trim()
            val targetStep = steps.firstOrNull { it.id == stepId }
            if (targetStep?.moduleId == "vflow.variable.create") {
                val type = targetStep.parameters["type"]?.toString()
                val dictValue = targetStep.parameters["value"]
                if (type == "dictionary" && dictValue is Map<*, *>) {
                    return dictValue.entries.mapNotNull { (k, v) ->
                        val keyName = k?.toString() ?: return@mapNotNull null
                        ReturnKey(keyName, inferType(v))
                    }
                }
            }
        }

        return null
    }

    /** 从参数值推断一个粗略的类型 ID（用于 ReturnKey 的类型提示）。 */
    private fun inferType(value: Any?): String {
        return when (value) {
            is Boolean -> VTypeRegistry.BOOLEAN.id
            is Number -> VTypeRegistry.NUMBER.id
            is List<*> -> VTypeRegistry.LIST.id
            is Map<*, *> -> VTypeRegistry.DICTIONARY.id
            else -> VTypeRegistry.STRING.id
        }
    }

    /** 判断字符串是否为魔法变量引用，如 `{{stepId.outputId}}`。 */
    private fun String.isMagicVariableRef(): Boolean {
        return trim().startsWith("{{") && trim().endsWith("}}")
    }
}
