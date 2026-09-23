// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/SimDataSwitchTriggerData.kt
// 描述: 数据卡切换触发器的触发载荷。
package com.chaomixian.vflow.core.workflow.module.triggers

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 数据卡切换触发器的触发载荷。
 *
 * 字段与 [SimDataSwitchTriggerModule.getOutputs] 声明的输出一一对应，
 * 在 `execute` 中回填为魔法变量供下游引用。
 *
 * `cardLabel` 与 `carrierName` 在广播到达时取自订阅列表 ——
 * 下游若稍后再查，期间可能已再次切卡，得到的就不是「那次事件发生时」的卡了。
 */
@Parcelize
data class SimDataSwitchTriggerData(
    /** 切换后成为默认上网卡的卡槽：0 = 卡1，1 = 卡2；无法判定时为 -1 */
    val simSlotIndex: Int,
    /** 切换后的 subId（诊断用；subId 会随重插卡变化，不要用来做长期判据） */
    val subId: Int,
    /** 卡名（显示名或运营商名），取不到时为空串 */
    val cardLabel: String,
    /** 运营商名，取不到时为空串 */
    val carrierName: String,
    /** 是否为卡1（slot 0） */
    val isSlot1: Boolean,
    /** 是否为卡2（slot 1） */
    val isSlot2: Boolean,
) : Parcelable
