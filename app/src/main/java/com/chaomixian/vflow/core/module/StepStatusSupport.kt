package com.chaomixian.vflow.core.module

import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean

/**
 * 步骤级「状态」输出的统一供给层（fork，P0-3 状态可见）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.2（决策 D2）。
 *
 * ## 背景
 *
 * 「异常处理策略」的 `SKIP` 分支会往该步骤的输出里注入 `error` / `success`
 * （见 `WorkflowExecutor` 失败分支），但这两个键**不是任何模块声明的输出** ⇒
 * 变量选择器不列、胶囊渲染不出、AI 也看不见 —— 下游根本没法据此分流
 * （详见 `docs/fork/surveys/module-error-status-survey.md` §2）。
 *
 * 本对象把 `success` 变成**声明输出**，并规定成功路径也写它：
 *
 * | 情形 | `{{该步骤.success}}` |
 * |---|---|
 * | 成功，模块未声明 | `true`（[withSuccessFlag] 注入） |
 * | 成功，模块声明了 | 模块自己的值（**不覆盖**，如 `ClickModule` 的落点结果） |
 * | 被 SKIP | `false`（`WorkflowExecutor` 失败分支已有） |
 * | 被 STOP / STOP_SILENT | 无（工作流已终止） |
 *
 * ## 为什么是「纯函数 + 单独文件」
 *
 * 本仓库无 Robolectric，`BaseModule` / `WorkflowExecutor` / 编辑器都起不来 ⇒
 * 判定逻辑必须可纯 JVM 单测；三处调用点由源码扫描型测试钉住（`StepStatusWiringTest`）。
 */
object StepStatusSupport {

    /**
     * 状态输出的 id。
     *
     * ⚠️ 必须与 `WorkflowExecutor` 失败分支注入的键**同字面量** —— 两边不一致时，
     * 「成功 true / 跳过 false」会落到两个不同的键上，判据恒真或恒假且不报错。
     */
    const val SUCCESS_OUTPUT_ID = "success"

    /**
     * 状态输出的声明。
     *
     * 名字取「是否成功」—— 与那 73 个自行声明 `success` 的模块同文案，避免同一个
     * 概念在选择器里出现两种叫法。
     */
    fun successOutputDefinition(): OutputDefinition = OutputDefinition(
        id = SUCCESS_OUTPUT_ID,
        nameStringRes = R.string.output_common_step_success_name,
        name = "是否成功",
        typeName = VTypeRegistry.BOOLEAN.id,
    )

    /**
     * 给模块声明的输出补上状态输出。
     *
     * **已声明则不重复加**：73 个模块自己声明了 `success`，它们的取值是业务语义
     * （如「点击落点是否命中」），不能被合成项顶掉或重复列出。
     */
    fun withSuccessOutput(declared: List<OutputDefinition>): List<OutputDefinition> =
        if (declared.any { it.id == SUCCESS_OUTPUT_ID }) declared
        else declared + successOutputDefinition()

    /**
     * 成功路径的输出：补 `success = true`，**不覆盖**模块自己给的值
     * （`ClickModule` 会在点击未命中时返回 `success = false`）。
     *
     * ⚠️ 空输出表也会被补成 `{success: true}` ⇒ 从此**每个成功步骤都会写 `stepOutputs`**。
     * 已知副作用：`vflow.data.log` 的「裸写步骤 id ⇒ 打印整表」特性
     * （`LogModule.bareStepIdOrNull` 要求该 step 在 `stepOutputs` 里）现在对**所有**步骤生效 ——
     * 打印出来的是 `{success=true}`，比原来"什么都不打"更有信息量，故接受。
     */
    fun withSuccessFlag(outputs: Map<String, Any?>): Map<String, Any?> =
        if (outputs.containsKey(SUCCESS_OUTPUT_ID)) outputs
        else outputs + (SUCCESS_OUTPUT_ID to VBoolean(true))
}
