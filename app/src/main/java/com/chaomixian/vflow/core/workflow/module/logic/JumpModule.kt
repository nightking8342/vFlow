// 文件: JumpModule.kt
// 描述: 定义了跳转到工作流中指定步骤的模块。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VNull
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.JumpTargetSupport
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * "跳转到步骤" 模块。
 * 允许用户通过指定一个步骤的索引号，无条件跳转到该步骤继续执行。
 */
class JumpModule : BaseModule() {
    override val id = "vflow.logic.jump"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_logic_jump_name,
        descriptionStringRes = R.string.module_vflow_logic_jump_desc,
        name = "跳转步骤",
        description = "跳转到工作流中指定的步骤继续执行。",
        iconRes = R.drawable.rounded_turn_slight_right_24,
        category = "逻辑控制",
        categoryId = "logic"
    )

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "target_step_index",
            nameStringRes = R.string.param_vflow_logic_jump_target_step_index_name,
            name = "目标步骤编号",
            staticType = ParameterType.NUMBER,
            defaultValue = 1L,
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            acceptedMagicVariableTypes = setOf(VTypeRegistry.NUMBER.id)
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition("success", nameStringRes = R.string.output_vflow_logic_jump_success_name, name = "是否成功", typeName = VTypeRegistry.BOOLEAN.id)
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val targetStepPill = PillUtil.createPillFromParam(
            step.parameters["target_step_index"],
            getInputs().find { it.id == "target_step_index" }
        )
        return PillUtil.buildSpannable(context, context.getString(R.string.summary_vflow_logic_jump_to_step_prefix), targetStepPill)
    }

    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        val targetIndex = step.parameters["target_step_index"]

        // 变量引用在执行时再解析和校验，这里只拦截明显无效的静态值
        if (targetIndex is String && (targetIndex.isMagicVariable() || targetIndex.isNamedVariable())) {
            return ValidationResult(true)
        }

        // 编辑器显示的步骤编号从 1 开始，这里保持一致。
        val rawIndex = targetIndex as? Number
        val index = rawIndex?.toDouble()
        if (index == null || index.isNaN() || index % 1.0 != 0.0) {
            return ValidationResult(false, "步骤编号必须是一个整数。")
        }

        if (index.toInt() < 1) {
            return ValidationResult(false, "步骤编号必须大于等于 1。")
        }

        // fork（B2 修复，2026-10-15）：**向内跳**（跳到块体内部）在保存时就挡住。
        //
        // 为什么要挡：它会把块的开始/结束配对打乱，而**症状随块类型不同**（见
        // `JumpTargetSupport` 的 KDoc）—— 跳到 `Loop` 体内 ⇒ End Loop 的 `loopStack.peek()`
        // 在空栈上抛 `EmptyStackException`（被执行器 catch 成「模块执行异常」，零信息量）；
        // 跳到 `ForEach` 体内 ⇒ End ForEach 直接 `Failure`；跳到 `If` 中间分支 ⇒ 行为未定义。
        //
        // ⚠️ 只在能拿到步骤列表时校验（`allSteps` 为空 = 调用方没给上下文）；
        // ⚠️ 越界目标不在这里报（列表是动态的），运行期 `execute` 里有更准确的判据。
        val displayIndex = index.toInt()
        if (allSteps.isNotEmpty() && displayIndex <= allSteps.size) {
            val currentIndentation = allSteps.firstOrNull { it.id == step.id }?.indentationLevel
                ?: step.indentationLevel
            if (!JumpTargetSupport.isAllowedTarget(allSteps, displayIndex - 1, currentIndentation)) {
                return ValidationResult(
                    false,
                    "不能跳到块体内部的步骤（会打乱循环 / 条件块的结构）。请改跳到块外的步骤。"
                )
            }
        }

        return ValidationResult(true)
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 获取原始值用于错误消息
        val rawValue = context.getVariable("target_step_index")
        val rawValueStr = when (rawValue) {
            is VString -> rawValue.raw
            is VNull -> "空值"
            is VNumber -> rawValue.raw.toString()
            else -> rawValue.toString()
        }

        // 使用 getVariableAsNumber 获取可空值
        val targetIndexDouble = context.getVariableAsNumber("target_step_index")

        if (targetIndexDouble == null || targetIndexDouble.isNaN()) {
            return ExecutionResult.Failure(
                "类型错误",
                "无法将 '$rawValueStr' 解析为有效的步骤编号。"
            )
        }

        if (targetIndexDouble % 1.0 != 0.0) {
            return ExecutionResult.Failure(
                "无效的步骤编号",
                "目标步骤编号 '$rawValueStr' 不是有效的整数。"
            )
        }

        val displayStepNumber = targetIndexDouble.toInt()
        val targetIndex = displayStepNumber - 1

        if (targetIndex !in context.allSteps.indices) {
            return ExecutionResult.Failure(
                "无效的步骤编号",
                "目标步骤编号 $displayStepNumber 无效或超出范围。"
            )
        }

        // fork（B2 修复，2026-10-15）：**运行期兜底** —— 向内跳（目标在块体内部）会让块的
        // 开始/结束配对失配，症状随块类型不同（见 `JumpTargetSupport` 的 KDoc）。
        //
        // ⚠️ 这里才是**防线**：`validate` 只覆盖「编辑器保存静态数字」这一条路，
        //    存量工作流（改动前存下的）与**变量型目标**（`{{vars.x}}`，静态算不出）都会绕过它。
        val currentIndentation = context.allSteps
            .getOrNull(context.currentStepIndex)
            ?.indentationLevel
            ?: 0
        if (!JumpTargetSupport.isAllowedTarget(context.allSteps, targetIndex, currentIndentation)) {
            return ExecutionResult.Failure(
                "无效的跳转目标",
                "不能跳到块体内部的步骤（会打乱循环 / 条件块的结构）。"
            )
        }

        onProgress(ProgressUpdate("跳转到步骤: #$displayStepNumber"))
        return ExecutionResult.Signal(ExecutionSignal.Jump(targetIndex))
    }
}
