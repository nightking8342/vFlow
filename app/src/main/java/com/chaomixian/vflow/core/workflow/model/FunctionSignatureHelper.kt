// 文件：main/java/com/chaomixian/vflow/core/workflow/model/FunctionSignatureHelper.kt
// 描述：返回值类型的**现场推导**层。给定一个工作流的步骤列表，静态推导它的返回值
//      类型（多 return 合并）与声明的键（多 return 并集）。
//
// 这是 fork 新增文件（上游无此文件）。
//
// ⚠️ 核心事实：函数工作流（vflow.logic.call_function）与普通子工作流
// （vflow.logic.call_workflow）的返回值来自**同一个锚点** —— 「停止并返回」
// （vflow.logic.return）发出的 ExecutionSignal.Return，由 WorkflowExecutor 的
// 唯一一个分支赋值给 returnValue。故两处调用模块**共用本文件这一份推导**。
//
// 与旧实现的差别（见 docs/fork/return-type-inference-design.md §3）：
//  - 判据由「那一步是不是『创建变量』」改为「查那一步**声明的输出类型**」；
//  - 推类型与推键**解耦**：类型来自值本身的静态类型，键只来自 `return_keys` 声明；
//  - 删掉 `extractDictionaryKeys` 的两条路径（含「字面量字典」的**键提取**）——
//    决策 #9 定案：字面量字典只保留「类型 = 字典」的判断，不再自动提取键。

package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.parser.VariablePathParser

/**
 * 返回值类型的静态推导工具（纯函数，无 Android 依赖，可纯 JVM 单测）。
 */
object FunctionSignatureHelper {

    /** 「停止并返回」模块的 id。 */
    const val RETURN_MODULE_ID = "vflow.logic.return"

    /** `return_keys` 参数在 step.parameters 里的键名。 */
    const val RETURN_KEYS_KEY = "return_keys"

    /**
     * 现场推导工作流的返回值：类型（多 return 合并）+ 声明的键（多 return 并集去重）。
     *
     * **永不返回 null**：没有「停止并返回」步骤时返回
     * `FunctionReturn(type = ANY, keys = emptyList())`。
     *
     * 合并规则（设计文档决策 1/2/3）：
     *  - 类型：逐 return 点求类型，`distinct()` 后 `singleOrNull() ?: ANY`
     *    （全一致 ⇒ 该类型；有分歧 或 含未知 ⇒ ANY）
     *  - 键：`flatMap { resolveKeys(it) }.distinctBy { it.name }`（保持声明顺序）
     *
     * @param steps 工作流的全部动作步骤。
     */
    fun deriveReturn(steps: List<ActionStep>): FunctionReturn {
        val returnSteps = steps.filter { it.moduleId == RETURN_MODULE_ID }

        val types = returnSteps.map { resolveValueType(it.parameters["value"], steps) }
        val mergedType = types.distinct().singleOrNull() ?: VTypeRegistry.ANY.id

        val keys = returnSteps
            .flatMap { resolveKeys(it) }
            .distinctBy { it.name }

        return FunctionReturn(type = mergedType, keys = keys)
    }

    /**
     * 单个值的静态类型 ID。推不出 ⇒ `VTypeRegistry.ANY.id`。
     *
     * **public**：供 `StopAndReturnModuleUIProvider` 判断「该 return 点的 value
     * 是否推导出字典」以决定 `return_keys` 是否显示（避免复制一份判据）。
     *
     * 规则（设计文档 §3.3.4）：
     *  - 字面量 `Map<*,*>` ⇒ 字典；字面量 `List<*>` ⇒ 列表
     *  - `{{stepId.outputId}}`（路径长度恰 2）⇒ 该步声明的输出类型
     *  - `{{stepId.outputId.prop…}}`（路径 ≥ 3）⇒ 沿路径用 `VTypeRegistry.getPropertyType` 求；
     *    **任一段取不到 ⇒ ANY**（不是「保持当前类型」——手输的字典键值类型未知）
     *  - 其它（普通字符串 / null / 命名变量引用）⇒ ANY
     */
    fun resolveValueType(value: Any?, steps: List<ActionStep>): String {
        // 字面量容器
        if (value is Map<*, *>) return VTypeRegistry.DICTIONARY.id
        if (value is List<*>) return VTypeRegistry.LIST.id
        if (value !is String) return VTypeRegistry.ANY.id

        val path = VariablePathParser.parseVariableReference(value)
        // 路径长度 < 2 说明不是 `stepId.outputId` 形式（含普通字符串、`{{vars.x}}` 等）
        if (path.size < 2) return VTypeRegistry.ANY.id

        val stepId = path[0]
        val outputId = path[1]
        val sourceStep = steps.firstOrNull { it.id == stepId } ?: return VTypeRegistry.ANY.id
        val sourceModule = ModuleRegistry.getModule(sourceStep.moduleId) ?: return VTypeRegistry.ANY.id
        val outputDef = sourceModule.getDynamicOutputs(sourceStep, steps)
            .firstOrNull { it.id == outputId }
            ?: return VTypeRegistry.ANY.id

        // 单段引用：直接就是该输出的类型
        if (path.size == 2) return outputDef.typeName

        // 带路径：起点与 VariablePathParser.canonicalizeVariableReference 同口径
        var currentTypeId = outputDef.listElementType ?: outputDef.typeName
        path.drop(2).forEach { segment ->
            val next = VTypeRegistry.getPropertyType(currentTypeId, segment)?.id
                ?: return VTypeRegistry.ANY.id
            currentTypeId = next
        }
        return currentTypeId
    }

    /**
     * 读取侧**类型兜底**：只有推导出的类型是「字典」时才交出声明的键。
     *
     * ⚠️ 判据只看类型、不看键 —— 否则用户先声明了键、后把 `value` 改成
     * `{{截图步骤.image}}`，调用方选择器会**真的列出**那些失效的键。
     *
     * 两个调用模块（`call_workflow` / `call_function`）共用本函数，避免各自写一份判据。
     */
    fun dictionaryKeysFor(ret: FunctionReturn): List<ReturnKey> =
        if (ret.type == VTypeRegistry.DICTIONARY.id) ret.keys else emptyList()

    /**
     * 单个 return 点声明的键（读 `return_keys` 参数；无 ⇒ 空列表）。
     *
     * 存储形态 `List<Map<String, String>>`（`[{name, type}]`），
     * `type` = [VTypeRegistry] 的类型 id；**保持声明顺序**。
     */
    private fun resolveKeys(returnStep: ActionStep): List<ReturnKey> {
        val raw = returnStep.parameters[RETURN_KEYS_KEY] as? List<*> ?: return emptyList()
        return raw.mapNotNull { entry ->
            val map = entry as? Map<*, *> ?: return@mapNotNull null
            val name = (map["name"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val type = (map["type"] as? String)?.takeIf { it.isNotBlank() } ?: VTypeRegistry.ANY.id
            ReturnKey(name = name, type = type)
        }
    }
}
