// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/FoldTriggerModule.kt
// 描述: 折叠屏触发器 —— 当设备折叠、展开或半折时触发工作流。
//
// 设计文档：docs/fork/fold-trigger-design.md
// 状态推断逻辑在 FoldStateResolver（纯函数，可单测），本类只做定义与输出。
package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

class FoldTriggerModule : BaseModule() {

    override val id = "vflow.trigger.fold"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_fold_name,
        descriptionStringRes = R.string.module_vflow_trigger_fold_desc,
        name = "折叠屏触发",
        description = "当设备折叠、展开或半折时触发工作流",
        iconRes = R.drawable.rounded_fold_24,
        category = "触发器",
        categoryId = "trigger",
    )

    override val uiProvider = null

    companion object {
        const val PARAM_FOLD_EVENT = "fold_event"

        /** 序列化值使用与语言无关的标识符（与 FoldState.serialized 一致） */
        const val VALUE_FOLDED = "folded"
        const val VALUE_UNFOLDED = "unfolded"
        const val VALUE_HALF_OPENED = "half_opened"

        /** 旧值兼容映射（历史上若曾以本地化文案存储，可在此收敛） */
        private val EVENT_LEGACY_MAP = mapOf(
            "折叠" to VALUE_FOLDED,
            "展开" to VALUE_UNFOLDED,
            "半折" to VALUE_HALF_OPENED,
        )
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_FOLD_EVENT,
            name = "触发条件",
            nameStringRes = R.string.param_vflow_trigger_fold_event_name,
            staticType = ParameterType.ENUM,
            defaultValue = VALUE_UNFOLDED,
            options = listOf(VALUE_FOLDED, VALUE_UNFOLDED, VALUE_HALF_OPENED),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_fold_folded,
                R.string.option_vflow_trigger_fold_unfolded,
                R.string.option_vflow_trigger_fold_half_opened,
            ),
            legacyValueMap = EVENT_LEGACY_MAP,
            inputStyle = InputStyle.CHIP_GROUP,
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "fold_state",
            name = "当前状态",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_fold_state_name,
        ),
        OutputDefinition(
            id = "angle",
            name = "铰链角度",
            typeName = VTypeRegistry.NUMBER.id,
            nameStringRes = R.string.output_vflow_trigger_fold_angle_name,
        ),
        OutputDefinition(
            id = "is_folded",
            name = "已折叠",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_fold_is_folded_name,
        ),
        OutputDefinition(
            id = "is_unfolded",
            name = "已展开",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_fold_is_unfolded_name,
        ),
        OutputDefinition(
            id = "is_half_opened",
            name = "半折",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_fold_is_half_opened_name,
        ),
        OutputDefinition(
            id = "posture_source",
            name = "信号来源",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_fold_posture_source_name,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val event = getInputs().normalizeEnumValue(
            PARAM_FOLD_EVENT,
            step.parameters[PARAM_FOLD_EVENT] as? String,
        ) ?: VALUE_UNFOLDED

        val displayText = when (event) {
            VALUE_FOLDED -> context.getString(R.string.option_vflow_trigger_fold_folded)
            VALUE_HALF_OPENED -> context.getString(R.string.option_vflow_trigger_fold_half_opened)
            else -> context.getString(R.string.option_vflow_trigger_fold_unfolded)
        }

        val eventPill = PillUtil.Pill(displayText, PARAM_FOLD_EVENT, isModuleOption = true)
        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_trigger_fold_prefix),
            " ",
            eventPill,
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_trigger_fold_triggered)))

        // triggerData 缺失时按「展开」兜底，与 defaultValue 保持一致
        val data = context.triggerData as? FoldTriggerData
            ?: FoldTriggerData(
                foldState = VALUE_UNFOLDED,
                angle = FoldStateResolver.ANGLE_UNAVAILABLE,
                isFolded = false,
                isUnfolded = true,
                isHalfOpened = false,
                postureSource = PostureSource.SENSOR.serialized,
            )

        return ExecutionResult.Success(
            outputs = mapOf(
                "fold_state" to VString(data.foldState),
                "angle" to VNumber(data.angle.toDouble()),
                "is_folded" to VBoolean(data.isFolded),
                "is_unfolded" to VBoolean(data.isUnfolded),
                "is_half_opened" to VBoolean(data.isHalfOpened),
                "posture_source" to VString(data.postureSource),
            )
        )
    }
}
