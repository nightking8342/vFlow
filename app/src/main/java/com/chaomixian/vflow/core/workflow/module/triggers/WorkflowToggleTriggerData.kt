package com.chaomixian.vflow.core.workflow.module.triggers

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 工作流开关触发器的触发载荷。
 *
 * 字段与 [WorkflowToggleTriggerModule.getOutputs] 声明的输出一一对应，
 * 在 `execute` 中回填为魔法变量供下游引用。
 *
 * ⚠️ [workflowName] 是目标工作流**当时**的名字 —— 改名后历史事件仍拿旧名
 * （事件发生时的快照，不在下游现查）。
 */
@Parcelize
data class WorkflowToggleTriggerData(
    /** 目标工作流 id */
    val workflowId: String,
    /** 目标工作流**当时**的名字 */
    val workflowName: String,
    /** 变化**后**的状态 */
    val isEnabled: Boolean,
    /** 变化**前**的状态 */
    val wasEnabled: Boolean,
) : Parcelable
