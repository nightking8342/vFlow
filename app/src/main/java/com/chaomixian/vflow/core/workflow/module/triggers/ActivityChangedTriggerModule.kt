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
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * Activity 切换触发器 —— 当某个 Activity 被切换到前台时触发工作流。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.1。
 * 数据来源：Xposed 通道 hook `ActivityRecord.activityResumedLocked`（§4.2.9 实测）。
 *
 * ## 它补的是什么空白（价值论据）
 *
 * vFlow 现有触发器的**真实空白**：
 *
 * | 已有 | 粒度 |
 * |---|---|
 * | `vflow.trigger.app_switch` | **包级** —— `AppSwitchTriggerHandler.kt:47` 按包名去重（`if (packageName == previousPackage) return`），**同包内 Activity 切换被直接丢弃** |
 * | `vflow.trigger.element` | 元素级 |
 * | **本模块** | ✅ **Activity 级** |
 *
 * ⚠️ **别把这条推广到 `AppStartTriggerModule`** —— 它收到 `newClassName` 却在
 * 同包时不处理，那是**正确行为**（它的语义是「从桌面打开某 App」），不是缺陷。
 *
 * 另外它**能拿到完整 Intent（含 extras）** —— 这是前三条通道（无障碍 / 广播 /
 * Shizuku-Root-Core）原理上做不到的：`dumpsys` 打印的 intent **不含 extras**。
 */
class ActivityChangedTriggerModule : BaseModule() {

    override val id = "vflow.trigger.activity_changed"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_activity_changed_name,
        descriptionStringRes = R.string.module_vflow_trigger_activity_changed_desc,
        name = "Activity 切换触发",
        description = "当指定的 Activity 被切换到前台时触发工作流（可获取启动 Intent 与参数）",
        iconRes = R.drawable.rounded_activity_zone_24,
        category = "触发器",
        categoryId = "trigger",
    )

    /**
     * 需要 Xposed 通道能力（在 LSPosed 里勾选本模块 + 作用域「系统框架」）。
     *
     * ⚠️⚠️ **不能因为「探测不到就跳过声明」** —— `TriggerService.handleWorkflowChanged`
     * 会在注册前检查权限，缺失时**静默把整个工作流置为 `isEnabled = false`**。
     * 这是本仓库反复踩过的坑（见 `SimDataSwitchTriggerModule` 的同名注释）。
     */
    override val requiredPermissions = listOf(PermissionManager.XPOSED_HOOK)

    override val uiProvider = null

    companion object {
        const val PARAM_PACKAGE_FILTER = "package_filter"
        const val PARAM_CLASS_FILTER = "class_filter"

        /**
         * 匹配方式。
         *
         * ⚠️ 用**稳定常量**（`exact`/`contains`），不要存本地化文案 ——
         * 否则切语言后已保存的工作流会失配（本仓库的既有纪律）。
         */
        const val MATCH_EXACT = "exact"
        const val MATCH_CONTAINS = "contains"

        private val MATCH_LEGACY_MAP = mapOf(
            "精确" to MATCH_EXACT,
            "精确匹配" to MATCH_EXACT,
            "包含" to MATCH_CONTAINS,
            "包含匹配" to MATCH_CONTAINS,
        )
    }

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_PACKAGE_FILTER,
            name = "包名",
            nameStringRes = R.string.param_vflow_trigger_activity_changed_package_name,
            staticType = ParameterType.STRING,
            defaultValue = "",
            // 空 = 任意包。⚠️ 空串做「全部」而不是「都不匹配」——
            // 用户新建触发器时不该因为还没填就什么都不触发
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
        ),
        InputDefinition(
            id = PARAM_CLASS_FILTER,
            name = "Activity 类名",
            nameStringRes = R.string.param_vflow_trigger_activity_changed_class_name,
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
        ),
        InputDefinition(
            id = "match_mode",
            name = "匹配方式",
            nameStringRes = R.string.param_vflow_trigger_activity_changed_match_mode_name,
            staticType = ParameterType.ENUM,
            defaultValue = MATCH_CONTAINS,
            options = listOf(MATCH_CONTAINS, MATCH_EXACT),
            optionsStringRes = listOf(
                R.string.option_vflow_trigger_activity_changed_contains,
                R.string.option_vflow_trigger_activity_changed_exact,
            ),
            legacyValueMap = MATCH_LEGACY_MAP,
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = "cooldown_ms",
            name = "冷却时间（毫秒）",
            nameStringRes = R.string.param_vflow_trigger_activity_changed_cooldown_name,
            staticType = ParameterType.NUMBER,
            // 默认 1000ms：activityResumedLocked **每次 resume 都触发**
            // （含返回、锁屏解锁、同 Activity 重入），不设冷却会瞬间刷爆工作流
            defaultValue = 1000,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "package_name",
            name = "包名",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_activity_changed_package_name,
        ),
        OutputDefinition(
            id = "class_name",
            name = "Activity 类名",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_activity_changed_class_name,
        ),
        OutputDefinition(
            id = "component",
            name = "组件（包/类）",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_activity_changed_component_name,
        ),
        OutputDefinition(
            id = "intent_uri",
            name = "启动 Intent",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_activity_changed_intent_uri_name,
        ),
        OutputDefinition(
            id = "extras_json",
            name = "Intent 参数（JSON）",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_activity_changed_extras_json_name,
            // ⚠️ 不逐键建输出（键不可枚举）。声明成字典键让选择器能提示这是 JSON
            dictionaryKeys = emptyList(),
        ),
        OutputDefinition(
            id = "truncated",
            name = "参数是否被截断",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_activity_changed_truncated_name,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val pkg = (step.parameters[PARAM_PACKAGE_FILTER] as? String).orEmpty()
        val cls = (step.parameters[PARAM_CLASS_FILTER] as? String).orEmpty()

        // 两个都空 = 任意 Activity 切换
        if (pkg.isBlank() && cls.isBlank()) {
            return PillUtil.buildSpannable(
                context,
                context.getString(R.string.summary_vflow_trigger_activity_changed_any),
            )
        }

        val target = listOf(pkg, cls).filter { it.isNotBlank() }.joinToString("/")
        return PillUtil.buildSpannable(
            context,
            context.getString(R.string.summary_vflow_trigger_activity_changed_prefix),
            " ",
            PillUtil.Pill(target, if (pkg.isNotBlank()) PARAM_PACKAGE_FILTER else PARAM_CLASS_FILTER),
        )
    }

    /**
     * 触发器模块本身不做动作 —— 真实触发由
     * [com.chaomixian.vflow.core.workflow.module.triggers.handlers.ActivityChangedTriggerHandler] 完成。
     *
     * 此方法只在 `WorkflowExecutor.seedTriggerOutputs` 里被调用一次，
     * 给下游提供「无事件数据时」的占位输出。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_trigger_activity_changed_ready)))

        val data = context.triggerData as? ActivityChangedTriggerData
        return ExecutionResult.Success(
            outputs = mapOf(
                "package_name" to VString(data?.packageName.orEmpty()),
                "class_name" to VString(data?.className.orEmpty()),
                "component" to VString(data?.component.orEmpty()),
                "intent_uri" to VString(data?.intentUri.orEmpty()),
                "extras_json" to VString(data?.extrasJson ?: "{}"),
                "truncated" to VBoolean(data?.truncated ?: false),
            )
        )
    }

    /** 供 Handler 复用：取归一化后的匹配方式。 */
    internal fun matchModeOf(step: ActionStep): String =
        getInputs().normalizeEnumValue(
            "match_mode",
            step.parameters["match_mode"] as? String,
            MATCH_CONTAINS,
        ) ?: MATCH_CONTAINS
}
