// 文件: main/java/com/chaomixian/vflow/core/workflow/module/data/InspectTypeModule.kt
package com.chaomixian.vflow.core.workflow.module.data

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * 一次类型查看的结果。
 *
 * @param typeId  运行期类型 id（取自 `value.type.id`，稳定、可比），如 `vflow.type.image`
 * @param matched 是否命中期望类型；**`null` = 未选期望类型（仅查看）**
 */
internal data class TypeInspectionResult(
    val typeId: String,
    val matched: Boolean?,
)

/**
 * 纯函数：给定值与期望类型 id，产出 `(typeId, matched)`。
 *
 * ⚠️ `typeId` **必须**取自 `value.type.id`，不得硬编码 —— 硬编码会让所有非该类型的值
 * 报出同一个 id，且**不报错**（本仓库反复踩过的「改错了不报错」形态）。
 *
 * @param expectedTypeId `null` 表示「仅查看」（不比较）。
 */
internal fun inspectValueType(value: VObject, expectedTypeId: String?): TypeInspectionResult {
    val typeId = value.type.id
    return TypeInspectionResult(
        typeId = typeId,
        matched = expectedTypeId?.let { it == typeId },
    )
}

/**
 * 纯函数：把一次类型查看的结果装配成步骤输出。
 *
 * ## ⚠️ 为什么单独抽出来（而不是内联在 `execute` 里）
 *
 * `execute` 里唯一的 Android 依赖是 `value.type.getLocalizedName(context)`（本地化类型名）。
 * 而纯 JVM 单测里**取不到可用的 `Context`** —— `Context.getString` 是 `final`
 * （无法覆写）、`Resources` 的构造器要一个 package-private 的 `AssetManager`
 * （造不出来）、本项目没有 Robolectric / mockito，且**明确否决**开
 * `unitTests.isReturnDefaultValues`（那会让「没接上」表现为默认值 ⇒ 断言恒真）。
 * 本仓库在 `BroadcastTriggerWiringTest` 上记过同源结论。
 *
 * ⇒ 把「结果 → outputs」这一层抽成不碰 `Context` 的纯函数，
 * 「`matched` 键的**条件写入**」这个本模块最易静默的点才**真的能被行为测试覆盖**，
 * 而不是退化成只能证明「源码里写了」的扫描断言。
 *
 * @param typeName 本地化的类型名 —— 由调用方从 `Context` 取，**不进本函数**。
 */
internal fun buildInspectionOutputs(
    result: TypeInspectionResult,
    typeName: String,
): Map<String, Any?> {
    val outputs = mutableMapOf<String, Any?>(
        InspectTypeModule.OUTPUT_TYPE_ID to VString(result.typeId),
        InspectTypeModule.OUTPUT_TYPE_NAME to VString(typeName),
    )
    // ⚠️「仅查看」（`matched == null`）时**不写 `matched` 键** —— 与 [InspectTypeModule.getOutputs]
    //    的条件输出保持一致。写成 `?: false` 会让用户判「为真」时永远走假分支，
    //    还以为是「类型不匹配」（文档 §3.2.1 的对照表）。
    result.matched?.let { outputs[InspectTypeModule.OUTPUT_MATCHED] = VBoolean(it) }
    return outputs
}

/**
 * 查看数据类型 —— 把**运行期**的实际类型暴露给下游，供 `If` 分流。
 *
 * 设计文档：`docs/fork/type-inspection-module-design.md`。
 *
 * ## 它解决什么
 *
 * `If` 只有「存在 / 不存在 / 空 / 非空 / 等于 / …」，**没有任何类型判断手段**；
 * 而 `ConditionEvaluator` 明写「`VNull` 是对象实例、视为**存在**」⇒
 * `If` 的「存在 / 不存在」**判不出空值**。脚本侧也拿不到细粒度类型
 * （`JsValueConverter` 对图片 / 文件走 `toString()`）。本模块补上这个缺口。
 *
 * ## 主用法（文档 §3.4）
 *
 * ```
 * ① 查看数据类型：value = {{调用步骤.result}}，expected_type = 图片
 * ② If：条件 = {{①.matched}}，运算符 = 为真      ← 不用填右操作数
 *    ├─ 真分支 → 创建变量(断言成图片) → 点 width/height
 *    └─ 假分支 → …
 * ```
 *
 * ⚠️ **断言必须放在「判断为真」的分支内** —— 放在分支外的话假分支也会执行它，
 * 那时值不是图片，`创建变量` 会构造出一个假图片（属性访问静默 `VNull`）。
 *
 * ## ⚠️ 三个静默失效点（文档 §7）
 *
 * 1. `expected_type` **只影响 `matched`，不影响 `value` 的类型** ⇒ 本模块
 *    **刻意不输出「带类型的 value」**（`value` 由「创建变量」在真分支内给）。
 *    若本模块的 `value` 被标成目标类型，那是在**说谎**（文档 §4.1）。
 * 2. 用户拿 `type_name`（本地化文案）做比较 ⇒ 切换语言后判断失效。
 *    故同时输出稳定的 `type_id`；`type_name` **仅供显示**。
 * 3. 先选了具体类型、引用 `matched` 后又改成「仅查看」⇒ 引用**悬空**
 *    （运行期 `VNull` ⇒ `If` 条件恒假、静默走假分支）。
 *    ⚠️ **无机器化守卫** —— 换来的是「编辑期就能看出没选类型」（见下）。
 *
 * ## ⚠️ 为什么「仅查看」时**不输出 `matched`**（而不是输出一个恒假布尔）
 *
 * | 做法 | 编辑期 | 运行期 |
 * |---|---|---|
 * | 输出恒 `false` | `matched` 存在、能用 | 用户判「为真」⇒ **永远走假分支**，还以为是「类型不匹配」 |
 * | 输出恒 `true` | 同上 | 更糟：把「没选类型」当成「类型匹配」 |
 * | **不输出**（本模块） | **选择器里根本没有这一项** ⇒ 用户被迫去选一个期望类型 | 无陷阱 |
 *
 * ⇒ **把错误暴露在编辑期**，而不是运行期静默走错分支。
 */
class InspectTypeModule : BaseModule() {

    override val id = "vflow.data.inspect_type"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_data_inspect_type_name,
        descriptionStringRes = R.string.module_vflow_data_inspect_type_desc,
        name = "查看数据类型",
        description = "查看一个值的运行期类型，并可与期望类型比较。",
        iconRes = R.drawable.rounded_type_specimen_24,
        category = "数据",
        categoryId = "data"
    )

    // ⚠️ **刻意不声明 `aiMetadata`**（默认 `null`）⇒ 不进 DIRECT_TOOL / TEMPORARY_WORKFLOW
    //    工具清单，避免污染 catalog。
    //    ⚠️ 一处如实记录的出入：`buildSavedWorkflowModuleIds()` 用的是**排除法**
    //    （`isSavedWorkflowModuleAllowed`，**完全不看 `usageScopes`**）⇒ 本模块**会**出现在
    //    `save_workflow` 的模块清单里。该清单经 `buildCompactModuleCatalog` **只留 moduleId**、
    //    不带描述与字段名 ⇒ 与本仓库既有的 `LogModule` / `BackupExportModule` 同量级。
    //    彻底排除需额外设 `allowSavedWorkflow = false`，那超出设计文档的决策范围（方案 §6.4）。

    // 无 uiProvider —— 走自动表单（两个参数都是标准控件）。

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_VALUE,
            nameStringRes = R.string.param_vflow_data_inspect_type_value_name,
            name = "值",
            staticType = ParameterType.ANY,
            defaultValue = "",
            acceptsMagicVariable = true,
            // ⚠️ 必须为 true，否则用户从 🪄 选的变量在输入框里显示成裸 `{{...}}` 而非胶囊。
            //    不报错、不崩溃，只有真机肉眼能发现（AGENTS.md 那条反复踩的坑）。
            supportsRichText = true
        ),
        EXPECTED_TYPE_INPUT_DEFINITION
    )

    /**
     * 输出**按 `expected_type` 参数条件化**（有先例：`CreateVariableModule.getOutputs` 按 `type` 决定类型）。
     *
     * ⚠️ 编辑器选择器与执行器都读这个方法（`getDynamicOutputs` 默认委托它、
     * `generateDefaultOutputs` 也读它）⇒ 条件输出会自动反映到两处。
     */
    override fun getOutputs(step: ActionStep?): List<OutputDefinition> {
        if (step == null) return emptyList() // 与 CreateVariableModule 同款

        val outputs = mutableListOf(
            OutputDefinition(
                id = OUTPUT_TYPE_ID,
                name = "类型 ID",
                typeName = VTypeRegistry.STRING.id,
                nameStringRes = R.string.output_vflow_data_inspect_type_type_id_name
            ),
            OutputDefinition(
                id = OUTPUT_TYPE_NAME,
                name = "类型名称",
                typeName = VTypeRegistry.STRING.id,
                nameStringRes = R.string.output_vflow_data_inspect_type_type_name_name
            )
        )

        // ⚠️ 「仅查看」时**不输出 `matched`**（不是输出一个恒假布尔）——
        //    把「没选期望类型」暴露在**编辑期**（选择器里根本没这一项），
        //    而不是让用户在运行期静默走假分支还以为「类型不匹配」。
        if (expectedTypeOf(step) != null) {
            outputs += OutputDefinition(
                id = OUTPUT_MATCHED,
                name = "类型匹配",
                typeName = VTypeRegistry.BOOLEAN.id,
                nameStringRes = R.string.output_vflow_data_inspect_type_matched_name
            )
        }
        return outputs
    }

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val expected = expectedTypeOf(step)
        val prefix = if (expected == null) {
            context.getString(R.string.summary_vflow_data_inspect_type_prefix)
        } else {
            context.getString(
                R.string.summary_vflow_data_inspect_type_with_expected,
                expectedTypeLabel(context, expected)
            )
        }
        return PillUtil.buildSpannable(
            context,
            prefix,
            PillUtil.richTextPreview(step.parameters[PARAM_VALUE]?.toString(), onlyWhenComplex = false)
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 执行器已把 `{{...}}` / `[[...]]` 解析进 `magicVariables`，故这里拿到的是
        // **真实类型**的对象（这正是本模块成立的前提）；不存在 ⇒ VNull。
        val value = context.getVariable(PARAM_VALUE)
        val expectedRaw = context.getVariableAsString(PARAM_EXPECTED_TYPE, EXPECTED_VIEW_ONLY)
        val expectedId = EXPECTED_TYPE_INPUT_DEFINITION.normalizeEnumValueOrNull(expectedRaw)
            ?: EXPECTED_VIEW_ONLY
        val expected = expectedId.takeIf { it != EXPECTED_VIEW_ONLY }

        val inspection = inspectValueType(value, expected)

        // ⚠️ 本地化名只在模块层做（需要 Context），**不进纯函数层**（见 [buildInspectionOutputs]）。
        return ExecutionResult.Success(
            buildInspectionOutputs(
                inspection,
                value.type.getLocalizedName(context.applicationContext),
            )
        )
    }

    /** 期望类型 id → 本地化标签（取 ENUM 的本地化选项，保证与 spinner 显示逐字一致）。 */
    private fun expectedTypeLabel(context: Context, expectedId: String): String {
        val index = EXPECTED_TYPE_INPUT_DEFINITION.options.indexOf(expectedId)
        val labels = EXPECTED_TYPE_INPUT_DEFINITION.getLocalizedOptions(context)
        return labels.getOrElse(index) { expectedId }
    }

    companion object {
        /** 参数 id */
        const val PARAM_VALUE = "value"
        const val PARAM_EXPECTED_TYPE = "expected_type"

        /** 输出 id */
        const val OUTPUT_TYPE_ID = "type_id"
        const val OUTPUT_TYPE_NAME = "type_name"
        const val OUTPUT_MATCHED = "matched"

        /**
         * 「仅查看」哨兵值。
         *
         * ⚠️ 必须带前缀，且**不得与 `VTypeRegistry` 的任何类型 id 撞车** ——
         * 撞车会让「仅查看」被当成一个真实类型去比较（`matched` 恒假），且不报错。
         * 对齐仓库既有惯例（`WORKFLOW_TAB_ALL = "vflow.tab.all"`、`vflow.icon.category.`）。
         */
        const val EXPECTED_VIEW_ONLY = "vflow.inspect_type.view_only"

        /**
         * 期望类型选项 = 「仅查看」（**首项、默认值**）+ 8 种可主动构造的类型 + 「空」。
         *
         * ⚠️ 选项**值**用 `VTypeRegistry.*.id`（`vflow.type.xxx`），**不是** `CreateVariableModule`
         * 那种短常量（`string`）。理由：`matched` 的语义正是 `value.type.id == expectedType`，
         * 直接比较**无需映射表**（映射表是第二份真相、改错不报错）；且「哨兵不得与类型 id 撞车」
         * 这条约束**只有在其余选项就是类型 id 的前提下才成立**。
         *
         * ⚠️ **该值会落盘**到用户工作流，将来改动属破坏性变更。
         *
         * ⚠️ 「空」**必须加**：`If` 的「存在 / 不存在」判不出 `VNull`
         * （`ConditionEvaluator` 明写「VNull 是对象实例，视为存在」），
         * 本枚举是用户**唯一**能判断「值是不是空」的手段。
         *
         * ⚠️ 其余长尾类型（屏幕控件 / 通知 / 日期 / 时间 / UI事件…）第一版不给 ——
         * 枚举太长不好用；要判它们走 `type_id` 文本比较（次用法）。
         */
        val EXPECTED_TYPE_OPTIONS: List<String> = listOf(
            EXPECTED_VIEW_ONLY,
            VTypeRegistry.STRING.id,
            VTypeRegistry.NUMBER.id,
            VTypeRegistry.BOOLEAN.id,
            VTypeRegistry.DICTIONARY.id,
            VTypeRegistry.LIST.id,
            VTypeRegistry.IMAGE.id,
            VTypeRegistry.FILE.id,
            VTypeRegistry.COORDINATE.id,
            VTypeRegistry.NULL.id
        )

        /**
         * 与 [EXPECTED_TYPE_OPTIONS] **逐项对应**（长度必须相等）。
         *
         * ⚠️ 显示名与 `VType.getLocalizedName` 的用词**刻意一致**（「图片」而非
         * `CreateVariableModule` 的「图像」）—— `type_name` 输出走 `getLocalizedName`，
         * 两处不一致会让用户看到同一个类型有两个名字。
         */
        val EXPECTED_TYPE_OPTIONS_RES: List<Int> = listOf(
            R.string.option_vflow_data_inspect_type_view_only,
            R.string.option_vflow_data_inspect_type_string,
            R.string.option_vflow_data_inspect_type_number,
            R.string.option_vflow_data_inspect_type_boolean,
            R.string.option_vflow_data_inspect_type_dictionary,
            R.string.option_vflow_data_inspect_type_list,
            R.string.option_vflow_data_inspect_type_image,
            R.string.option_vflow_data_inspect_type_file,
            R.string.option_vflow_data_inspect_type_coordinate,
            R.string.option_vflow_data_inspect_type_null
        )

        val EXPECTED_TYPE_INPUT_DEFINITION = InputDefinition(
            id = PARAM_EXPECTED_TYPE,
            nameStringRes = R.string.param_vflow_data_inspect_type_expected_type_name,
            name = "期望类型",
            staticType = ParameterType.ENUM,
            defaultValue = EXPECTED_VIEW_ONLY,
            options = EXPECTED_TYPE_OPTIONS,
            optionsStringRes = EXPECTED_TYPE_OPTIONS_RES,
            acceptsMagicVariable = false
        )

        /**
         * 从 step 参数解析出期望类型 id；`null` = 仅查看。
         *
         * 缺失 / 非法一律回落「仅查看」—— 「仅查看」是最无害的一档
         * （刚加模块、还没选类型时，**不该**给出一个看起来合理的判断结果）。
         */
        internal fun expectedTypeOf(step: ActionStep?): String? {
            val raw = step?.parameters?.get(PARAM_EXPECTED_TYPE) as? String
            val normalized = EXPECTED_TYPE_INPUT_DEFINITION.normalizeEnumValueOrNull(raw)
                ?: EXPECTED_VIEW_ONLY
            return normalized.takeIf { it != EXPECTED_VIEW_ONLY }
        }
    }
}
