// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/FoldTriggerData.kt
// 描述: 折叠屏触发器传递给 execute 的数据载荷。
package com.chaomixian.vflow.core.workflow.module.triggers

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 折叠屏触发器的触发载荷。
 *
 * 字段与 [FoldTriggerModule.getOutputs] 声明的输出一一对应，
 * 在 `execute` 中回填为魔法变量供下游引用。
 */
@Parcelize
data class FoldTriggerData(
    /** 当前状态常量：folded / half_opened / unfolded */
    val foldState: String,
    /** 铰链角度（度）；不可用时为 -1 */
    val angle: Float,
    /** 是否折叠态 */
    val isFolded: Boolean,
    /** 是否展开态 */
    val isUnfolded: Boolean,
    /** 是否半折态 */
    val isHalfOpened: Boolean,
    /** 信号来源：sensor / miui，诊断用 */
    val postureSource: String,
) : Parcelable
