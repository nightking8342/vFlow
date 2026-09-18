package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.logcat.LogcatFilterType
import com.chaomixian.vflow.core.logcat.LogLevel
import com.chaomixian.vflow.core.logcat.isRegexValid
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.InputVisibility
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * logcat 触发器模块。
 *
 * 当设备日志中出现符合条件的内容时触发工作流。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §4.1。
 *
 * ## 匹配语义
 *
 * - TAG 与消息**各自独立配置，AND 组合**
 * - OR 由「配多个触发器」在架构层提供（多个触发器可指向同一工作流）
 * - 比较一律**大小写不敏感**
 *
 * ## 为什么 TAG / message 的匹配方式要分四类
 *
 * `any` / `equals` / `contains` / `regex` 覆盖了用户从"随便什么都行"到
 * "精确表达式"的全部诉求。⚠️ **这四类只有 `equals` 能翻译成 logcat 的
 * 命令行参数**，这正是本设计**不做过滤下推**的原因——强行下推会让
 * `contains` / `regex` 的用户**静默收不到触发**（文档 §5.3）。
 */
class LogcatTriggerModule : BaseModule() {

    companion object {
        // 匹配方式常量放在 LogcatFilterType 里（Core 侧共用同一份字面量），
        // 这里只做转发，避免两处各写一份字符串
        val TAG_MATCH_OPTIONS = LogcatFilterType.ALL
        val MESSAGE_MATCH_OPTIONS = LogcatFilterType.ALL

        /** 级别选项。顺序即 severity，**不可重排**。 */
        val LEVEL_OPTIONS = LogLevel.entries.map { it.char.toString() }

        /** 是否显示本应用日志。 */
        const val EXCLUDE_SELF_ON = "exclude"
        const val EXCLUDE_SELF_OFF = "include"
    }

    override val id = "vflow.trigger.logcat"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_logcat_name,
        descriptionStringRes = R.string.module_vflow_trigger_logcat_desc,
        name = "logcat 触发",       // Fallback
        description = "当设备日志中出现符合条件的内容时触发工作流",   // Fallback
        iconRes = R.drawable.rounded_terminal_24,
        category = "触发器",
        categoryId = "trigger"
    )

    /**
     * 需要 Shell 权限才能读设备日志。
     *
     * ⚠️ 这里声明的是 **Shizuku / Root**（走 Core 进程读日志），
     * 不是 `READ_LOGS` —— 后者是签名级权限，普通应用拿不到，
     * 而 shell 身份本身就在 `log` 组里，能读全量日志
     * （见 `surveys/logcat-readability-survey.md`）。
     */
    override val requiredPermissions = listOf(
        com.chaomixian.vflow.permissions.PermissionManager.SHIZUKU
    )

    override val uiProvider = null

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "tag_filter_type",
            name = "TAG 条件",
            nameStringRes = R.string.param_vflow_trigger_logcat_tag_filter_type_name,
            staticType = ParameterType.ENUM,
            defaultValue = LogcatFilterType.ANY,
            options = TAG_MATCH_OPTIONS,
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_logcat_any,
                R.string.option_vflow_trigger_logcat_equals,
                R.string.option_vflow_trigger_logcat_contains,
                R.string.option_vflow_trigger_logcat_regex,
            ),
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "tag_filter_value",
            name = "TAG 值",
            nameStringRes = R.string.param_vflow_trigger_logcat_tag_filter_value_name,
            staticType = ParameterType.STRING,
            defaultValue = "",
            // 照 SmsTriggerModule 的做法：值字段只在需要时显示
            visibility = InputVisibility.notEquals("tag_filter_type", LogcatFilterType.ANY),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "message_filter_type",
            name = "消息条件",
            nameStringRes = R.string.param_vflow_trigger_logcat_message_filter_type_name,
            staticType = ParameterType.ENUM,
            defaultValue = LogcatFilterType.ANY,
            options = MESSAGE_MATCH_OPTIONS,
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_logcat_any,
                R.string.option_vflow_trigger_logcat_equals,
                R.string.option_vflow_trigger_logcat_contains,
                R.string.option_vflow_trigger_logcat_regex,
            ),
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "message_filter_value",
            name = "消息值",
            nameStringRes = R.string.param_vflow_trigger_logcat_message_filter_value_name,
            staticType = ParameterType.STRING,
            defaultValue = "",
            visibility = InputVisibility.notEquals("message_filter_type", LogcatFilterType.ANY),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "min_level",
            name = "最低级别",
            nameStringRes = R.string.param_vflow_trigger_logcat_min_level_name,
            staticType = ParameterType.ENUM,
            defaultValue = LogLevel.INFO.char.toString(),
            options = LEVEL_OPTIONS,
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_logcat_level_v,
                R.string.option_vflow_trigger_logcat_level_d,
                R.string.option_vflow_trigger_logcat_level_i,
                R.string.option_vflow_trigger_logcat_level_w,
                R.string.option_vflow_trigger_logcat_level_e,
                R.string.option_vflow_trigger_logcat_level_f,
            ),
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "cooldown_ms",
            name = "冷却时间（毫秒）",
            nameStringRes = R.string.param_vflow_trigger_logcat_cooldown_name,
            staticType = ParameterType.NUMBER,
            defaultValue = 1000,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        ),
        InputDefinition(
            id = "exclude_self",
            name = "排除本应用日志",
            nameStringRes = R.string.param_vflow_trigger_logcat_exclude_self_name,
            staticType = ParameterType.ENUM,
            defaultValue = EXCLUDE_SELF_ON,
            options = listOf(EXCLUDE_SELF_ON, EXCLUDE_SELF_OFF),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_logcat_exclude_self_on,
                R.string.option_vflow_trigger_logcat_exclude_self_off,
            ),
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "message",
            name = "消息正文",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_logcat_message_name
        ),
        OutputDefinition(
            id = "tag",
            name = "TAG",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_logcat_tag_name
        ),
        OutputDefinition(
            id = "level",
            name = "级别",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_logcat_level_name
        ),
        OutputDefinition(
            id = "pid",
            name = "进程号",
            typeName = VTypeRegistry.NUMBER.id,
            nameStringRes = R.string.output_vflow_trigger_logcat_pid_name
        ),
        OutputDefinition(
            id = "raw",
            name = "完整原始行",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_logcat_raw_name
        )
    )

    /**
     * 保存时的校验。
     *
     * ⚠️ **正则语法错误必须在这里拒绝**（真机场景 9）。放过去的话，
     * Core 侧解码时会跳过该条件 —— 用户看到的是"触发器配好了但不触发"，
     * 而没有任何提示。
     *
     * 全 `any` + 最低级别 `V` 只**警告**（有人确实想做全量统计），不阻塞。
     */
    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {

        val tagType = step.parameters["tag_filter_type"] as? String
        val tagValue = step.parameters["tag_filter_value"] as? String
        val messageType = step.parameters["message_filter_type"] as? String
        val messageValue = step.parameters["message_filter_value"] as? String

        if (tagType == LogcatFilterType.REGEX && !isRegexValid(tagValue)) {
            return ValidationResult(false, "TAG 正则表达式语法错误")
        }
        if (messageType == LogcatFilterType.REGEX && !isRegexValid(messageValue)) {
            return ValidationResult(false, "消息正则表达式语法错误")
        }

        // 配了匹配方式却没填值 —— 会导致该条件永远不命中。
        // 也拦住"全 any + 最低级别 V"（= 匹配全部日志）之外的空条件组合
        if (tagType != null && tagType != LogcatFilterType.ANY && tagValue.isNullOrBlank()) {
            return ValidationResult(false, "TAG 条件已选择匹配方式，但未填写匹配值")
        }
        if (messageType != null && messageType != LogcatFilterType.ANY && messageValue.isNullOrBlank()) {
            return ValidationResult(false, "消息条件已选择匹配方式，但未填写匹配值")
        }

        // 全 any + 最低级别 V 会匹配所有日志。允许（有人确实想做全量统计），
        // 但不给校验错误 —— 性能风险由文案承担，不由校验阻塞
        return ValidationResult(true)
    }

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val tagType = step.parameters["tag_filter_type"] as? String ?: LogcatFilterType.ANY
        val tagValue = step.parameters["tag_filter_value"] as? String ?: ""
        val minLevel = step.parameters["min_level"] as? String ?: LogLevel.INFO.char.toString()

        val pills = mutableListOf<PillUtil.Pill>()

        if (tagType != LogcatFilterType.ANY && tagValue.isNotBlank()) {
            pills.add(PillUtil.Pill("TAG ${typeLabel(context, tagType)} $tagValue", "tag_filter_value"))
        }
        pills.add(PillUtil.Pill("${minLevel} 以上", "min_level", isModuleOption = true))

        val prefix = context.getString(R.string.summary_vflow_trigger_logcat_prefix)
        return PillUtil.buildSpannable(context, prefix, " ", *pills.toTypedArray())
    }

    private fun typeLabel(context: Context, type: String): String = when (type) {
        LogcatFilterType.EQUALS -> context.getString(R.string.option_vflow_trigger_logcat_equals)
        LogcatFilterType.CONTAINS -> context.getString(R.string.option_vflow_trigger_logcat_contains)
        LogcatFilterType.REGEX -> context.getString(R.string.option_vflow_trigger_logcat_regex)
        else -> context.getString(R.string.option_vflow_trigger_logcat_any)
    }

    /**
     * 触发器模块本身不做动作——真正的事件来自
     * [com.chaomixian.vflow.core.workflow.module.triggers.handlers.LogcatTriggerHandler]。
     *
     * 这里只在 `seedTriggerOutputs` 里被调用一次，产出"无事件数据时"的占位输出。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        onProgress(ProgressUpdate("logcat 触发器已就绪"))
        return ExecutionResult.Success(
            mapOf(
                "message" to com.chaomixian.vflow.core.types.basic.VString(""),
                "tag" to com.chaomixian.vflow.core.types.basic.VString(""),
                "level" to com.chaomixian.vflow.core.types.basic.VString(""),
                "pid" to com.chaomixian.vflow.core.types.basic.VNumber(0.0),
                "raw" to com.chaomixian.vflow.core.types.basic.VString("")
            )
        )
    }
}
