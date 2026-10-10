package com.chaomixian.vflow.services

import android.os.Parcelable
import com.chaomixian.vflow.core.workflow.WorkflowWriteOrigin
import kotlinx.parcelize.Parcelize

@Parcelize
data class WorkflowTriggerRef(
    val triggerId: String,
    val type: String,
) : Parcelable

@Parcelize
data class WorkflowTriggerDelta(
    val workflowId: String,
    val oldTriggerRefs: List<WorkflowTriggerRef> = emptyList(),
    /**
     * 保存**前**该工作流的启用态（fork 新增，见 `docs/fork/workflow-toggle-design.md` §2.3）。
     * `null` = 保存前该工作流不存在（新建 / 导入 / API 创建）。
     *
     * ⚠️ **不要**再加第二个 `isNew` 布尔 —— `null` 已同时承担「不存在」与「首次」两个语义，
     * 两者必然同真同假。
     */
    val oldIsEnabled: Boolean? = null,
    /** 这次写入是「有人显式要求的」还是「系统自动的」。见 [WorkflowWriteOrigin]。 */
    val writeOrigin: WorkflowWriteOrigin = WorkflowWriteOrigin.EXPLICIT,
) : Parcelable
