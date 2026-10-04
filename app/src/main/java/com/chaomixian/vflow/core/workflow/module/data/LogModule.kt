// 文件: main/java/com/chaomixian/vflow/core/workflow/module/data/LogModule.kt
package com.chaomixian.vflow.core.workflow.module.data

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionLogLevel
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.AiModuleMetadata
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.parser.TemplateParser
import com.chaomixian.vflow.core.types.parser.TemplateSegment
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil

/**
 * 输出日志 —— 在工作流中间把任意值打进**执行日志**，用于调试与运行追踪。
 *
 * 设计文档：`docs/fork/log-module-design.md`。
 *
 * ## 它解决什么
 *
 * 在此之前，工作流的执行日志只有「步骤名 + 模块进度文案」，**步骤输出的值不可见**。
 * AI 与人工调试时想取一个值，只能靠 `throw new Error(...)` 借失败日志回传 ——
 * 不可持续且会中断工作流。本模块给出一条正常的取值通道。
 *
 * ## ⚠️ 三条需要知道的行为
 *
 * 1. **受工作流「日志等级」过滤**（`WorkflowLogLevel`）：档位低于本模块 [level] 时不打印。
 *    把工作流设成「仅错误」即可关掉本模块的全部 `info` 输出。
 * 2. **`{{<步骤id>}}`（裸写、不带输出名）会展开该步的全部输出** ——
 *    这是**本模块专有**写法，在别的模块里裸写步骤 id 仍然是空值。
 * 3. **不截断** —— 打什么就完整打什么。图片/文件只打元数据（绝不打 base64 或文件内容）。
 */
class LogModule : BaseModule() {

    override val id = "vflow.data.log"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_data_log_name,
        descriptionStringRes = R.string.module_vflow_data_log_desc,
        name = "输出日志",
        description = "把一个值打印到执行日志，用于调试与运行追踪。",
        iconRes = R.drawable.rounded_log_24,
        category = "数据",
        categoryId = "data"
    )

    /**
     * ⚠️ `riskLevel = LOW` 而不是 `HIGH`。
     *
     * `ChatAgentModuleExecutor.riskLevelForSavedWorkflow` 取工作流内所有步骤的
     * **max** ⇒ 声明 `HIGH` 会让任何含本模块的工作流被抬到 high risk 并触发人工审批，
     * 而用户审批的是一个「打印一行字」的操作 —— 与真实风险完全不成比例。
     *
     * ⚠️ 只给 `TEMPORARY_WORKFLOW`，**不给 `DIRECT_TOOL`**：
     * 直调时没有工作流上下文、日志也没有人在看，价值极低；
     * 而本项目 v2.0 的方向是**精简直调工具表**（59 个模块工具已撤出）。
     * `allowSavedWorkflow` 不设 ⇒ 保存的工作流里照常可用。
     */
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "Print a value into the workflow execution log for debugging.",
        inputHints = mapOf(
            "content" to
                "Value to print. Use a magic variable like {{stepId.output}} or {{vars.name}}. " +
                    "Writing just the step id ({{stepId}}, with no output name) dumps EVERY output " +
                    "of that step at once — this shorthand works only in this module.",
            "label" to "Optional tag to tell apart several log calls in the same workflow.",
            "level" to "info (default) / warn / error. May be filtered out by the workflow's log level.",
        ),
        // ⚠️ **不设 `requiredInputIds`**：`content` 是 `ParameterType.ANY`，
        //    在 schema 里是 `additionalProperties: true` —— 声明它必填会让模型
        //    写 `content` 时被要求一个具体类型。留空即可（提示文案里已说清该怎么填）。
    )

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = "content",
            name = "内容",
            nameStringRes = R.string.param_vflow_data_log_content_name,
            staticType = ParameterType.ANY,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            supportsRichText = true,
            hintStringRes = R.string.param_vflow_data_log_content_hint
        ),
        InputDefinition(
            id = "label",
            name = "标签",
            nameStringRes = R.string.param_vflow_data_log_label_name,
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = false,
            hintStringRes = R.string.param_vflow_data_log_label_hint
        ),
        InputDefinition(
            id = "level",
            name = "级别",
            nameStringRes = R.string.param_vflow_data_log_level_name,
            staticType = ParameterType.ENUM,
            defaultValue = LEVEL_INFO,
            options = listOf(LEVEL_INFO, LEVEL_WARN, LEVEL_ERROR),
            optionsStringRes = listOf(
                R.string.option_vflow_data_log_level_info,
                R.string.option_vflow_data_log_level_warn,
                R.string.option_vflow_data_log_level_error,
            ),
            acceptsMagicVariable = false
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            "success",
            "是否成功",
            VTypeRegistry.BOOLEAN.id,
            nameStringRes = R.string.output_vflow_data_log_success_name
        ),
        // ⚠️ `text` **不含** `label` 前缀、不含 `[日志] ` 标记 —— 它只等于值的序列化结果。
        //    理由：下游 `{{log1.text}}` 的语义应当是「把这个值再拿回来用」，
        //    带上 `label="x" ` 会让它不可用。
        OutputDefinition(
            "text",
            "实际输出",
            VTypeRegistry.STRING.id,
            nameStringRes = R.string.output_vflow_data_log_text_name
        )
    )

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val label = (step.parameters["label"] as? String).orEmpty()
        val prefix = if (label.isBlank()) "输出日志" else "输出日志 $label"
        return PillUtil.buildSpannable(
            context,
            prefix,
            PillUtil.richTextPreview(
                rawText = step.parameters["content"]?.toString(),
                onlyWhenComplex = false
            )
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val text = renderContent(context)
        val label = (context.getParameterRaw("label") ?: "").trim()
        val level = resolveLevel(context)

        val line = buildString {
            append("[日志] ")
            if (label.isNotEmpty()) {
                append("label=\"").append(label).append("\" ")
            }
            append(text.ifBlank { "(空)" })
        }

        // ⚠️ `logSink` 为 null 是**正常情况**（默认值 = 不写日志）：
        //    如测试直接构造 ExecutionContext、或将来某条路径没接上。
        //    此时**不算失败** —— 见下方 `success` 的取值。
        val wrote = context.logSink != null
        context.logSink?.invoke(level, TAG, line)

        return ExecutionResult.Success(
            mapOf(
                "success" to VBoolean(wrote),
                "text" to VString(text)
            )
        )
    }

    /**
     * 求 `content` 的值并序列化成一行文本。
     *
     * ## 为什么不能直接用 `getVariable("content")`
     *
     * 执行器在步骤开始时**已经替我们解析过一遍**（`WorkflowExecutor.kt` 的参数解析块）：
     *
     * | `content` 填的是 | 执行器做了什么 | `getVariable("content")` 拿到 |
     * |---|---|---|
     * | `{{cls.state}}`（普通魔法变量） | 解析成功，写进 `magicVariables` | 已解析的 VObject（**原始串丢了**） |
     * | `{{cls}}`（本模块专有） | 解析**失败**返回 null，`magicVariables` 不设 | 回落 `variables` ⇒ `VString("{{cls}}")` **字面量** |
     * | `label: {{cls}}`（混写） | `isComplex` ⇒ 走 `VariableResolver.resolve` | 已解析的 `VString` |
     *
     * ⇒ 两条路拿到的东西**形态不同**，照原样打印会把 `{{cls}}` 输出成字符串 `"{{cls}}"`。
     *
     * ## 正解
     *
     * 优先用 [ExecutionContext.getParameterRaw]（读 `variables[key]`，即 `step.parameters`
     * 的**原始值**，永不含解析结果）；只有它不是字符串时（`content` 是 `ANY`，
     * 可能是数字/布尔）才回落到已解析的值。
     */
    private fun renderContent(context: ExecutionContext): String {
        val raw = context.getParameterRaw("content")
        if (raw == null) {
            // 非字符串参数（数字 / 布尔 / 已包装的 VObject）—— 直接用已解析的值。
            // ⚠️ 漏了这个分支 ⇒ 传 42 时会打印 `(空)`，而使用者明明填了值。
            return VObjectLogSerializer.render(context.getVariable("content"))
        }
        if (raw.isBlank()) return ""

        resolveBareStepIds(raw, context)?.let { return it }

        return renderResolvedValue(context)
    }

    /**
     * 常规路径：取执行器**已经解析好**的那个值。
     *
     * ## ⚠️ 为什么不再自己对 `raw` 跑一遍 `VariableResolver.resolve`
     *
     * 执行器在模块执行前**已经**把 `step.parameters` 解析过一轮
     * （`WorkflowExecutor.kt` 的参数解析块），结果放在 `magicVariables`：
     *
     * | `content` 填的是 | 执行器做了什么 | `magicVariables["content"]` |
     * |---|---|---|
     * | `{{ocr.count}}` | `resolveSingleVariableReference` 成功 | 解析后的 **VObject**（保留类型） |
     * | `label: {{ocr.count}}`（混写） | `isComplex` ⇒ `VariableResolver.resolve` | `VString(已解析文本)` |
     * | `{{nosuchstep}}` / `{{index}}`（解析不掉） | 返回 null，**不设** | 无 ⇒ 回落 `variables`，即**原始字符串** |
     * | 纯文本 `hello` | 无变量引用，不动 | 无 ⇒ 回落 `variables` |
     *
     * 再自己 resolve 一遍的危害有两个，且都是静默的：
     *
     * 1. **解析不掉的引用会被「撑大括号」** —— `VariableResolver.resolveVariableObject`
     *    在寻址失败时返回 `VObjectFactory.from("{${rawExpression}}")`
     *    （`VariableResolver.kt:133`），即把 `{{nosuchstep}}` 包成 `{{{nosuchstep}}}`
     *    再当作变量**递归重试**，每层多一对括号、直到递归上限。
     *    ⇒ 用户打错一个步骤 id，日志里出现的是一串几十个括号的噪声。
     *    而执行器那条路**刻意不设** `magicVariables`，回落原始串 —— 那才是既有语义。
     * 2. **丢类型** —— 自己 resolve 只能拿到字符串；`{{img}}` 这类会退化成文本。
     *
     * ⇒ 尊重执行器的结论：`VString` 直接取正文（用户写的是给人看的文本，
     * 不该被加上引号），其余类型交给 [VObjectLogSerializer] 带类型渲染。
     */
    private fun renderResolvedValue(context: ExecutionContext): String {
        val resolved = context.getVariable("content")
        return when (resolved) {
            is VString -> resolved.raw
            else -> VObjectLogSerializer.render(resolved)
        }
    }

    /**
     * 处理 `{{<步骤id>}}`（**裸写，不带输出名**）⇒ 展开该步的全部输出。
     *
     * 返回 `null` 表示「这个串里没有裸步骤 id」，调用方走常规解析。
     *
     * ## ⚠️ 为什么先用 `VariableResolver.resolve` 求值
     *
     * 整表分支要替换的是**那一段**、而混写串的其余部分仍应由全局解析器处理。
     * `resolve` 会把裸步骤 id 解析成**空值**（既有行为）—— 于是「哪几段是空的」
     * 天然给出了候选位置：在这些空位上按 `TemplateParser` 的变量段顺序去匹配即可。
     *
     * ⚠️ **代价（如实记录）**：`resolve` 会**丢掉非字符串的类型信息**
     * （`VList` 之类会被 `asString()` 拉平成文本）。这与其它模块的静态参数行为**一致**
     * （它们同样走 `VariableResolver.resolve`），故不额外处理。
     *
     * ## ⚠️ 为什么不把这条规则写进 `VariableResolver`
     *
     * 按常规做法它该写在 `VariableResolver.resolveExistingVariableObject` 里
     * （`path.size == 1` 且 `stepOutputs` 有这个 key 时返回整表）—— 但那样会
     * **全局**改变语义：所有模块的静态输入跑的是同一个解析器，裸写 `{{cls}}`
     * 会到处变成「整步输出」，且**不可撤销**（已有工作流会依赖它）。
     *
     * ⇒ 分派只在本模块内部，`VariableResolver` 一行不动。
     */
    private fun resolveBareStepIds(raw: String, context: ExecutionContext): String? {
        // ① 把「裸步骤 id 段」换成哨兵，其余段落原样保留 —— 得到一个新的模板串。
        //    哨兵是不含 `{` 的普通文本，`VariableResolver` 会原样留着它。
        val placeholders = mutableListOf<String>()
        val templated = buildString {
            for (segment in TemplateParser(raw).parse()) {
                when (segment) {
                    is TemplateSegment.Text -> append(segment.content)
                    is TemplateSegment.Script -> append(segment.rawExpression)
                    is TemplateSegment.Variable -> {
                        val stepId = segment.bareStepIdOrNull(context)
                        if (stepId != null) {
                            placeholders += stepId
                            append(WHOLE_STEP_MARKER)
                        } else {
                            append(segment.rawExpression)
                        }
                    }
                }
            }
        }
        if (placeholders.isEmpty()) return null

        // ② 用全局解析器处理**其余**段落（保证与其它模块的参数解析完全一致）。
        val resolved = VariableResolver.resolve(templated, context)

        // ③ 按哨兵把整表填回去。
        //
        // ⚠️ 索引方向：`"A MARKER B".split(MARKER)` == `["A ", " B"]`
        //    —— 哨兵**夹在** `parts[i]` 与 `parts[i + 1]` 之间，
        //    故第 i 个整表要插在 `parts[i]` 之后。写成「按 part 配 stepId」
        //    会把 `"label: {{ocr}}"` 的前缀吃掉（实现期实际踩到）。
        val parts = resolved.split(WHOLE_STEP_MARKER)
        return buildString {
            append(parts.firstOrNull().orEmpty())
            placeholders.forEachIndexed { index, stepId ->
                append(renderStepOutputs(stepId, context))
                append(parts.getOrNull(index + 1).orEmpty())
            }
        }
    }

    /**
     * 该段是不是「裸步骤 id」；是则返回步骤 id，否则 null。
     *
     * ## ⚠️ 判据必须包含「这个 id 真的是一个步骤」
     *
     * 只判 `path.size == 1` 会把 `{{index}}`（循环变量，落在 `magicVariables`）之类
     * 误判成步骤 id。做法：额外要求它在 `stepOutputs` 里。
     */
    private fun TemplateSegment.Variable.bareStepIdOrNull(context: ExecutionContext): String? {
        if (isNamedVariable) return null
        if (path.size != 1) return null
        return path[0].takeIf { context.stepOutputs.containsKey(it) }
    }

    /**
     * 把某一步的全部输出渲染成一整表。
     *
     * ## ⚠️ `isNullOrEmpty` 分支目前**打不到**（如实记录，勿据此写测试）
     *
     * [bareStepIdOrNull] 要求该 id **已经在 `stepOutputs` 里**才会走到这里，而
     * `WorkflowExecutor.kt:751` 只在 `result.outputs.isNotEmpty()` 时才登记
     * ⇒ 能走到这里的 map 必然非空。
     *
     * 保留它纯粹是**防御性**的：将来若执行器改成「无论有没有输出都登记该步」，
     * 这一条就会真的生效，且届时它的用户价值是明确的（别让「这一步没输出」
     * 表现成一条空日志）。**现在不要为它写测试** —— 那是条恒绿的断言。
     */
    private fun renderStepOutputs(stepId: String, context: ExecutionContext): String {
        val outputs = context.stepOutputs[stepId]
        if (outputs.isNullOrEmpty()) return "{}(step \"$stepId\" 无输出)"
        return VObjectLogSerializer.render(VDictionary(outputs))
    }

    /** `level` 参数 → 日志级别。未知值 ⇒ INFO（与参数默认值一致，不静默降级成别的档）。 */
    private fun resolveLevel(context: ExecutionContext): ExecutionLogLevel {
        return when (context.getVariableAsString("level", LEVEL_INFO).trim().lowercase()) {
            LEVEL_WARN -> ExecutionLogLevel.WARN
            LEVEL_ERROR -> ExecutionLogLevel.ERROR
            LEVEL_INFO -> ExecutionLogLevel.INFO
            else -> ExecutionLogLevel.INFO
        }
    }

    companion object {
        private const val TAG = "LogModule"

        /**
         * 替换裸步骤 id 段的哨兵。
         *
         * ⚠️ 必须是一个**不会被 `VariableResolver` 动、也不会出现在正常文本里**的串：
         * 不含 `{` / `}` / `[` / `]`，故不会与魔法变量、命名变量、内联脚本的语法冲突。
         * 用 `\u0000` 开头保证它不可能由用户输入产生
         *（XML/编辑器都不会让它进参数值）。
         */
        private const val WHOLE_STEP_MARKER = "\u0000vflow-whole-step\u0000"

        const val LEVEL_INFO = "info"
        const val LEVEL_WARN = "warn"
        const val LEVEL_ERROR = "error"
    }
}
