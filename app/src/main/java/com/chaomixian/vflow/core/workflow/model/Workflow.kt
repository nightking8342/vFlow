package com.chaomixian.vflow.core.workflow.model

import android.os.Parcelable
import com.chaomixian.vflow.core.workflow.WorkflowVisuals
import kotlinx.parcelize.Parcelize

private const val MANUAL_TRIGGER_ID = "vflow.trigger.manual"

@Parcelize
data class Workflow(
    val id: String,
    var name: String,
    var triggers: List<ActionStep> = emptyList(),
    var steps: List<ActionStep> = emptyList(),
    var isEnabled: Boolean = true,
    var isFavorite: Boolean = false,
    var wasEnabledBeforePermissionsLost: Boolean = false,
    var folderId: String? = null,
    var order: Int = 0,
    var shortcutName: String? = null,
    var shortcutIconRes: String? = null,
    var cardIconRes: String = WorkflowVisuals.DEFAULT_ICON_RES_NAME,
    var cardThemeColor: String = WorkflowVisuals.DEFAULT_THEME_COLOR_HEX,
    var modifiedAt: Long = System.currentTimeMillis(),
    var version: String = "1.0.0",
    var vFlowLevel: Int = 1,
    var description: String = "",
    var author: String = "",
    var homepage: String = "",
    var tags: List<String> = emptyList(),
    var maxExecutionTime: Int? = null,
    var reentryBehavior: WorkflowReentryBehavior = WorkflowReentryBehavior.BLOCK_NEW,
    /**
     * 静默执行：执行时不发「过程性噪音」——执行进度通知与「开始执行」Toast。
     *
     * **不静音**「需要用户交互」的通知（ExecutionUIService 的输入/选择/错误弹窗）：
     * 那些是工作流在**等用户操作**的信号，静音掉的工作流会静默挂起，用户不知该去点一下。
     *
     * 失败通知**豁免**本开关——静默的语义是「别播报过程」，不是「炸了也别告诉我」。
     */
    var silentExecution: Boolean = false,
    // 新增：函数工作流的签名声明。null = 普通工作流（非函数）。
    var functionSignature: FunctionSignature? = null
) : Parcelable {
    val allSteps: List<ActionStep>
        get() = triggers + steps

    /** 是否为函数工作流（声明了函数签名）。 */
    val isFunction: Boolean
        get() = functionSignature != null

    fun hasTriggerType(triggerModuleId: String): Boolean {
        return triggers.any { it.moduleId == triggerModuleId }
    }

    fun triggerStepsByType(triggerModuleId: String): List<ActionStep> {
        return triggers.filter { it.moduleId == triggerModuleId }
    }

    fun autoTriggerSteps(): List<ActionStep> {
        return triggers.filter { it.moduleId != MANUAL_TRIGGER_ID }
    }

    fun hasAutoTriggers(): Boolean = autoTriggerSteps().isNotEmpty()

    fun hasManualTrigger(): Boolean = manualTrigger() != null

    fun manualTrigger(): ActionStep? = triggers.firstOrNull { it.moduleId == MANUAL_TRIGGER_ID }

    fun isManualOnly(): Boolean = hasManualTrigger() && !hasAutoTriggers()

    fun getTrigger(triggerId: String): ActionStep? = triggers.firstOrNull { it.id == triggerId }

    fun toAutoTriggerSpecs(): List<TriggerSpec> {
        return autoTriggerSteps().map { TriggerSpec(this, it) }
    }
}
