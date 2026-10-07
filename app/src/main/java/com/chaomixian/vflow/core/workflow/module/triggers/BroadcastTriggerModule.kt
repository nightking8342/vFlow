package com.chaomixian.vflow.core.workflow.module.triggers

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * 广播触发器 —— 用户**自由配置任意 action** 的逃生舱。
 *
 * 设计文档：`docs/fork/broadcast-trigger-design.md`（§3 平台约束 / §4 schema / §5 输出）。
 *
 * ## 它补的是什么空白
 *
 * 现有广播型触发器（`app_package` / `power` / `screen` / `battery` / `bluetooth` /
 * `wifi` / `call` / `sms` / `do_not_disturb` / `sim_data_switch`）**每个都硬编码一组 action**，
 * 且参数是结构化的（如电源的「已连接/已断开」是枚举）。
 * 那些「既有触发器没覆盖、又不值得单独做模块」的广播（第三方 App 的自定义 action、
 * `ACTION_MEDIA_MOUNTED`、`ACTION_HEADSET_PLUG` …）此前**没有表达方式**。
 *
 * ## ⚠️ 它**不取代**任何一个既有触发器
 *
 * 本模块在能力上确实是那 10 个的超集，但**每一个替代都需要用户在下游重写
 * 该触发器已内置的解析逻辑**（方向判定 / 枚举归一 / 权限 / sticky 基线）。
 * 既有触发器的结构化参数与专用输出**就是它们的价值**，且已发布、编辑器里找得到。
 * ⇒ 本模块是**逃生舱**，不是替代品；既有 10 个 Handler 一行都不动。
 *
 * ## ⚠️⚠️ 三条与仓库惯例**相反**的平台约束（最易做错的地方）
 *
 * 1. **`actions` 必填，且没有通配写法** —— `IntentFilter.addAction("*")` 无效
 *    （`matchAction` 是 `mActions.contains(action)`，列表里存的是字面量 `"*"`）；
 *    而**不声明任何 action** 的 filter 只匹配「没有 action 的 intent」。
 *    ⇒ **不得沿用本仓库其它触发器「留空 = 任意」的惯例** ——
 *    那样做出来的默认值是「永不触发」且无任何报错。
 * 2. **`data_schemes` 留空 ≠ 任意** —— filter 未声明 scheme 时，
 *    带 data 而 scheme 非 `content`/`file` 的 intent 直接 `NO_MATCH_DATA`；
 *    而 `addDataScheme("*")` 同样无效。
 * 3. **`categories` 是 AND 语义**（intent 携带的每个 category 都要在列表里）——
 *    与 action 的 OR **相反**（AOSP `addCategory` 的 javadoc 原话：
 *    "the semantics of categories is the opposite of actions"）。
 *
 * ## 权限：**刻意为空**
 *
 * 覆写 `requiredPermissions` **一行都不要加**。理由：
 * action 是用户自由填写的，**无法静态推导需要什么权限**；按前缀猜（如
 * `android.provider.Telephony.SMS_RECEIVED` ⇒ `RECEIVE_SMS`）会误判，
 * 而本仓库**已记过**「声明少了反而让 `TriggerService` 静默禁用整个工作流」的坑。
 * 缺权限的处置走**运行时诊断**：[handlers.BroadcastTriggerHandler] 把
 * `registerReceiver` 的异常与 actions 列表一起打进 ERROR 日志，
 * 用户可在「最近日志」里自查。
 */
class BroadcastTriggerModule : BaseModule() {

    override val id = "vflow.trigger.broadcast"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_trigger_broadcast_name,
        descriptionStringRes = R.string.module_vflow_trigger_broadcast_desc,
        name = "广播触发",
        description = "监听指定的广播（action），收到时触发工作流并可引用广播携带的数据",
        // ⚠️ 该图标来自 Material Symbols 全量库（经字符串名 + getIdentifier 解析，
        // 不在 R.drawable 里静态引用，由 res/raw/keep.xml 的白名单托住 shrinker）。
        // ⚠️ 刻意与 ActivityChangedTriggerModule（rounded_activity_zone_24）、
        // SimDataSwitchTriggerModule（rounded_swap_sim_24）不同 —— 图标撞车会让用户在
        // 模块选择器里分不清谁是谁（有声明体检测试锁住）。
        iconRes = R.drawable.rounded_settings_input_antenna_24,
        category = "触发器",
        categoryId = "trigger",
    )

    override val uiProvider: ModuleUIProvider = BroadcastTriggerUIProvider()

    companion object {
        const val PARAM_ACTIONS = "actions"
        const val PARAM_DATA_SCHEMES = "data_schemes"
        const val PARAM_CATEGORIES = "categories"
        const val PARAM_MATCH_MODE = "match_mode"
        const val PARAM_COOLDOWN_MS = "cooldown_ms"

        /**
         * 匹配方式。**只有一种取值**，且必须存**稳定常量**（不存本地化文案 ——
         * 否则切语言后已保存的工作流会失配，本仓库既有纪律）。
         *
         * ⚠️ 为什么不给 `contains`：`IntentFilter` 做不了子串匹配，
         * 而「注册得足够宽」**没有合法写法**（没有通配、也没有「任意 scheme」）。
         * ⇒ 给一个「看起来能用、实际永远收不到」的选项，比不给更糟。
         * 保留该字段是为了 schema 形态与其它触发器一致（将来若平台提供能力，加值即可）。
         */
        const val MATCH_EXACT = "exact"

        /** 冷却默认值。与 Handler 的 `FALLBACK_COOLDOWN_MS` 必须一致。 */
        const val DEFAULT_COOLDOWN_MS = 1000
    }

    /**
     * ⚠️⚠️ **刻意不覆写 `requiredPermissions`** —— `BaseModule` 的默认值就是
     * `emptyList()`，那正是本模块要的。
     *
     * 不要「顺手」加 `RECEIVE_SMS` / `READ_PHONE_STATE` / `RECEIVE_BOOT_COMPLETED`：
     * 那会让**每一个**广播触发器（哪怕只是监听一个第三方 App 的自定义 action）
     * 无条件向用户索要敏感权限。缺权限的处置在 Handler 的运行时诊断里。
     *
     * `BroadcastTriggerWiringTest` 有一条反向锁：本文件不得出现
     * `override val requiredPermissions`。
     */
    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_ACTIONS,
            name = "广播 Action 列表",
            nameStringRes = R.string.param_vflow_trigger_broadcast_actions_name,
            staticType = ParameterType.ANY,
            defaultValue = emptyList<String>(),
            // ⚠️ `isRequired = true` 在这里只作**契约声明**（编辑器可能据此加 `*`），
            // 真正的拦截在 [validate]。因为本参数被 UIProvider 接管、自动表单不渲染它，
            // 标记未必显示出来。
            isRequired = true,
            // action 是**字面量标识**，填 `{{...}}` 没有语义（而且 `IntentFilter`
            // 也拿不到运行期解析后的值 —— 注册发生在工作流执行之前）
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = PARAM_DATA_SCHEMES,
            name = "数据 Scheme",
            nameStringRes = R.string.param_vflow_trigger_broadcast_data_schemes_name,
            staticType = ParameterType.ANY,
            defaultValue = emptyList<String>(),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = PARAM_CATEGORIES,
            name = "分类（Category）",
            nameStringRes = R.string.param_vflow_trigger_broadcast_categories_name,
            staticType = ParameterType.ANY,
            defaultValue = emptyList<String>(),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = PARAM_MATCH_MODE,
            name = "匹配方式",
            nameStringRes = R.string.param_vflow_trigger_broadcast_match_mode_name,
            staticType = ParameterType.ENUM,
            defaultValue = MATCH_EXACT,
            options = listOf(MATCH_EXACT),
            optionsStringRes = listOf(R.string.option_vflow_trigger_broadcast_exact),
            // ⚠️ CHIP_GROUP 而不是 DROPDOWN：它**只有一个**选项，
            // DROPDOWN 会渲染成一个只含一项的下拉（点开一次、选回同一个值）
            inputStyle = InputStyle.CHIP_GROUP,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
        InputDefinition(
            id = PARAM_COOLDOWN_MS,
            name = "冷却时间（毫秒）",
            nameStringRes = R.string.param_vflow_trigger_broadcast_cooldown_name,
            staticType = ParameterType.NUMBER,
            // 默认 1000ms：本模块注册的是 **EXPORTED** receiver（任意应用可发），
            // 不设冷却会让一个高频广播（如某个 App 的进度通知）瞬间刷爆工作流
            defaultValue = DEFAULT_COOLDOWN_MS,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
        ),
    )

    /**
     * ## ⚠️ 关于 `supportsRichText`
     *
     * AGENTS.md 的规则是「**接受变量**的文本参数必须 `supportsRichText = true`」。
     * 本模块**三个列表参数都不接受变量**（`acceptsMagicVariable = false` ——
     * action / scheme / category 是字面量标识，填 `{{...}}` 没有语义），
     * 且它们**全部被 [BroadcastTriggerUIProvider] 接管**（`getHandledInputIds()`），
     * 自动表单根本不会为它们渲染控件。
     * ⇒ 该规则**不适用**，**不要**照清单机械补上 `supportsRichText`。
     */
    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "action",
            name = "广播 Action",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_action,
        ),
        OutputDefinition(
            id = "data_uri",
            name = "数据 URI",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_data_uri,
        ),
        OutputDefinition(
            id = "scheme",
            name = "数据 Scheme",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_scheme,
        ),
        OutputDefinition(
            id = "mime_type",
            name = "MIME 类型",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_mime_type,
        ),
        OutputDefinition(
            id = "categories",
            name = "分类",
            typeName = VTypeRegistry.LIST.id,
            listElementType = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_categories,
        ),
        OutputDefinition(
            id = "extras_json",
            name = "附加数据（JSON）",
            typeName = VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_extras_json,
            // ⚠️ 不逐键建输出（键不可枚举）。声明成字典键让选择器能提示这是 JSON
            dictionaryKeys = emptyList(),
        ),
        OutputDefinition(
            id = "flags",
            name = "Flags",
            typeName = VTypeRegistry.NUMBER.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_flags,
        ),
        OutputDefinition(
            id = "truncated",
            name = "附加数据是否被截断",
            typeName = VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_trigger_broadcast_truncated,
        ),
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val actions = BroadcastTriggerSupport.normalizeActions(
            BroadcastTriggerSupport.stringListOf(step.parameters[PARAM_ACTIONS])
        )
        val schemes = BroadcastTriggerSupport.normalizeSchemes(
            BroadcastTriggerSupport.stringListOf(step.parameters[PARAM_DATA_SCHEMES])
        )

        // ⚠️⚠️ 空 = **「没配」**，不是「任意」 —— 空 actions 是**永不触发**的无效配置
        // （平台没有通配写法，见类注释）。文案必须如实说「未配置」，
        // **不能**写「任意广播」（那是不可能的，会误导用户以为已经生效）。
        if (actions.isEmpty()) {
            return PillUtil.buildSpannable(
                context,
                context.getString(R.string.summary_vflow_trigger_broadcast_none),
            )
        }

        val text = when (actions.size) {
            1 -> actions[0]
            // ⚠️ 占位符**必须显式索引** —— 混用裸 `%s` 与 `%2$d` 会抛
            // `IllegalFormatException`（本仓库已记过这个坑）
            else -> context.getString(
                R.string.summary_vflow_trigger_broadcast_more,
                actions[0],
                actions.size,
            )
        }

        val parts = mutableListOf<Any?>(
            context.getString(R.string.summary_vflow_trigger_broadcast_prefix),
            " ",
            PillUtil.Pill(text, PARAM_ACTIONS),
        )
        if (schemes.isNotEmpty()) {
            parts += " "
            parts += context.getString(
                R.string.summary_vflow_trigger_broadcast_scheme,
                schemes.joinToString("/"),
            )
        }
        return PillUtil.buildSpannable(context, *parts.toTypedArray())
    }

    /**
     * 编辑期校验。
     *
     * ## ⚠️ 它的**真实作用面**（实测 5 个调用点）
     *
     * | 调用点 | 覆盖触发器？ |
     * |---|---|
     * | `ActionEditorSheet`（`showTriggerEditor` 用的就是它） | ✅ **在触发器卡片上保存时会拦** |
     * | `WorkflowEditorActivity.saveWorkflow` | ❌ 循环的是 `actionSteps`，`triggerSteps` 一个都不校验 |
     * | `ChatAgentModuleExecutor`（`temporary_workflow` / `save_workflow` / `update_workflow`） | ✅ |
     *
     * ⇒ 它**拦不住** JSON 导入与直接改 prefs。兑现「actions 必填」的**第二道防线**
     * 是 Handler 的 `filterSpecOf()` 返回 null（**不注册 receiver** + WARN 日志），
     * 那一道覆盖所有路径。
     *
     * ⚠️ 本方法读的是 `actions`，而 `actions` 由 UIProvider 提供（不是自动表单）。
     * 已核 `ActionEditorSheet` 的顺序：`readParametersFromUi()`（内部先
     * `mergeCustomEditorParametersFromUi()`）**早于** 拼 `finalParams` 与
     * `validate()` ⇒ UIProvider 里刚编辑的 `actions` 一定看得到。
     */
    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        val raw = BroadcastTriggerSupport.stringListOf(step.parameters[PARAM_ACTIONS])
        return when (BroadcastTriggerSupport.validateActions(raw)) {
            BroadcastTriggerSupport.ActionsValidation.OK -> ValidationResult(true)
            BroadcastTriggerSupport.ActionsValidation.EMPTY -> ValidationResult(
                false,
                appContext.getString(R.string.error_vflow_trigger_broadcast_no_action),
            )
            BroadcastTriggerSupport.ActionsValidation.WILDCARD -> ValidationResult(
                false,
                appContext.getString(R.string.error_vflow_trigger_broadcast_wildcard),
            )
        }
    }

    /**
     * 触发器模块本身不做动作 —— 真实触发由
     * [com.chaomixian.vflow.core.workflow.module.triggers.handlers.BroadcastTriggerHandler] 完成。
     *
     * 此方法只在 `WorkflowExecutor.seedTriggerOutputs` 里被调用一次，
     * 给下游提供「无事件数据时」的占位输出
     * （手动执行整个工作流时走的就是这条 ⇒ 输出全空/默认，与 ActivityChanged 同形）。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_trigger_broadcast_ready)))

        val data = context.triggerData as? BroadcastTriggerData
        return ExecutionResult.Success(
            outputs = mapOf(
                "action" to VString(data?.action.orEmpty()),
                "data_uri" to VString(data?.dataUri.orEmpty()),
                "scheme" to VString(data?.scheme.orEmpty()),
                "mime_type" to VString(data?.mimeType.orEmpty()),
                "categories" to VList(data?.categories.orEmpty().map { VString(it) }),
                "extras_json" to VString(data?.extrasJson ?: "{}"),
                // ⚠️ 缺失时给 0 而不是 null —— flags 是位掩码，0 是合法且最常见的取值
                "flags" to VNumber((data?.flags ?: 0).toDouble()),
                "truncated" to VBoolean(data?.truncated ?: false),
            )
        )
    }
}
