// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/FunctionParamTypeMapper.kt
// 描述：函数参数类型的「简写值」+「完整类型ID」互转工具。
//      定义函数侧用 CreateVariableModule.TYPE_OPTIONS（简写 "string"）做下拉选择；
//      但存进 FunctionParam.type 的必须是 VTypeRegistry.*.id（如 "vflow.type.string"），
//      否则下游 VTypeRegistry.getType / CallFunctionModule.toParameterType 都匹配失败（会退化成「任意」/文本框）。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.module.data.CreateVariableModule

object FunctionParamTypeMapper {

    /**
     * 把 CreateVariableModule 的简写类型值（"string"/"number"/...）转换为 VTypeRegistry 完整类型 ID
     * （"vflow.type.string"/...）。若已经是完整 ID 或无法识别，原样返回。
     */
    fun toFullTypeId(shortValue: String?): String {
        if (shortValue.isNullOrBlank()) return VTypeRegistry.ANY.id
        return when (shortValue) {
            CreateVariableModule.TYPE_STRING -> VTypeRegistry.STRING.id
            CreateVariableModule.TYPE_NUMBER -> VTypeRegistry.NUMBER.id
            CreateVariableModule.TYPE_BOOLEAN -> VTypeRegistry.BOOLEAN.id
            CreateVariableModule.TYPE_DICTIONARY -> VTypeRegistry.DICTIONARY.id
            CreateVariableModule.TYPE_LIST -> VTypeRegistry.LIST.id
            CreateVariableModule.TYPE_IMAGE -> VTypeRegistry.IMAGE.id
            CreateVariableModule.TYPE_FILE -> VTypeRegistry.FILE.id
            CreateVariableModule.TYPE_COORDINATE -> VTypeRegistry.COORDINATE.id
            // 已经是完整 ID 或未知：原样返回
            else -> shortValue
        }
    }

    /**
     * 把完整类型 ID 转回 CreateVariableModule 的简写值（供编辑器默认值控件分派）。
     * 若无法识别，返回该 ID 原样。
     */
    fun toShortValue(fullTypeId: String?): String {
        if (fullTypeId.isNullOrBlank()) return CreateVariableModule.TYPE_STRING
        return when (fullTypeId) {
            VTypeRegistry.STRING.id -> CreateVariableModule.TYPE_STRING
            VTypeRegistry.NUMBER.id -> CreateVariableModule.TYPE_NUMBER
            VTypeRegistry.BOOLEAN.id -> CreateVariableModule.TYPE_BOOLEAN
            VTypeRegistry.DICTIONARY.id -> CreateVariableModule.TYPE_DICTIONARY
            VTypeRegistry.LIST.id -> CreateVariableModule.TYPE_LIST
            VTypeRegistry.IMAGE.id -> CreateVariableModule.TYPE_IMAGE
            VTypeRegistry.FILE.id -> CreateVariableModule.TYPE_FILE
            VTypeRegistry.COORDINATE.id -> CreateVariableModule.TYPE_COORDINATE
            else -> fullTypeId
        }
    }
}
