// 文件：main/java/com/chaomixian/vflow/core/workflow/model/FunctionSignature.kt
// 描述：函数工作流的签名模型。声明一个工作流在接受参数、返回结果时所需的结构信息。
//      这是 fork 新增文件（上游无此文件），用于「函数工作流」功能。

package com.chaomixian.vflow.core.workflow.model

import android.os.Parcelable
import com.chaomixian.vflow.core.types.VTypeRegistry
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue

/**
 * 单个函数参数的定义。
 *
 * @param name 参数名（snake_case）。
 * @param type 参数类型 ID，如 [VTypeRegistry.STRING].id。
 * @param defaultValue 默认值（可选，Any? 类型，序列化时由 Gson/Parcel 处理）。
 * @param isRequired 是否必填。true = 调用方必须赋值。
 */
@Parcelize
data class FunctionParam(
    val name: String,
    val type: String,
    val defaultValue: @RawValue Any? = null,
    val isRequired: Boolean = false
) : Parcelable

/**
 * 返回值的一个键（用于「返回字典」场景）。
 *
 * @param name 键名。
 * @param type 键值类型 ID。
 */
@Parcelize
data class ReturnKey(
    val name: String,
    val type: String
) : Parcelable

/**
 * 返回值定义。目前只支持「返回字典」场景需要声明键；
 * 基础类型的返回值不需要此定义（直接用底层类型引擎展开属性）。
 *
 * @param type 返回类型 ID，默认 [VTypeRegistry.DICTIONARY].id。
 * @param keys 字典的键列表（供调用方选择器点选）。
 */
@Parcelize
data class FunctionReturn(
    val type: String = VTypeRegistry.DICTIONARY.id,
    val keys: List<ReturnKey> = emptyList()
) : Parcelable

/**
 * 函数签名 = 参数声明 + 返回值声明。
 * 存于 [Workflow.functionSignature]。null 表示普通工作流（非函数）。
 *
 * @param params 参数声明列表。
 * @param returnDef 返回值声明（仅「返回字典」场景需要）。
 */
@Parcelize
data class FunctionSignature(
    val params: List<FunctionParam> = emptyList(),
    val returnDef: FunctionReturn? = null
) : Parcelable
