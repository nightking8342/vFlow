// 文件: main/java/com/chaomixian/vflow/core/workflow/model/TriggerLabel.kt
package com.chaomixian.vflow.core.workflow.model

/**
 * 触发器标签（fork 新增）。
 *
 * ## 它解决什么
 *
 * 一个工作流可挂多个触发器（`Workflow.triggers`），而**任何**触发器命中都执行同一套
 * `steps` —— 工作流内部看不出「这次是谁触发的」。本对象给每个触发器一个标签，
 * 执行期把命中那个触发器的标签注入 `namedVariables`，于是工作流可以用
 * `If [[__trigger_label]] equals "xxx"` 分支执行。
 *
 * ## ⚠️ 三处同名，只有这一份字面量
 *
 * | 用途 | 名字 |
 * |---|---|
 * | 存储（`ActionStep.parameters` 的键） | `__trigger_label` |
 * | 工作流内引用（命名变量语法） | `[[__trigger_label]]` |
 * | AI 读写（`save_workflow` / `update_workflow` / `get_workflow`） | `__trigger_label` |
 *
 * ⚠️ **双下划线**对齐既有的保留参数 [ActionStepExecutionSettings.KEY_ERROR_POLICY] /
 * [ActionStepExecutionSettings.KEY_RETRY_COUNT] —— 保留参数与模块声明的输入靠这个前缀区分。
 * **不得**写成单下划线的 `trigger_label`（那会与模块自己可能声明的参数名撞车）。
 *
 * ## ⚠️ 引用形式是 `[[ ]]` 不是 `{{ }}`
 *
 * `[[name]]` 是**命名变量**（`TemplateSegment.Variable.isNamedVariable == true`），
 * 走 `namedVariables` 查表；`{{name}}` 是魔法变量，走 `stepOutputs` 查表。
 * 两者在本仓库是**两条不同的解析分支**（`VariableResolver.resolveExistingVariableObject`），
 * 用错形式的后果是静默解析成字面量、不报错。
 *
 * ## 为什么未设置时必须注入**空串**而不是不注入
 *
 * 不注入 / 注入 `VNull` 时，`VariableResolver` 会落到
 * 「返回 `{...}` 字面量」的兜底分支（`VariableResolver.kt:133`）⇒
 * `If [[__trigger_label]] equals "x"` 恒 false，且**没有任何报错**，用户查不到原因。
 * 注入 `VString("")` 则语义明确：用户可用 `is_empty` 判断「没标签」。
 *
 * 纯 Kotlin，无 Android 依赖 ⇒ 可纯 JVM 单测。
 */
object TriggerLabel {

    /** 存储键 = 工作流内变量名 = AI 读写名。三处同名，只有这一份字面量。 */
    const val KEY = "__trigger_label"

    /**
     * 与 [KEY] 同值，语义化别名（`[[NAME]]` 里的 NAME）。
     *
     * ⚠️ 与 [KEY] 拆成两个常量**不是**为了将来能改其中一个 —— 契约锁定测试断言二者相等。
     * 分开只为让调用点的**意图**可读：写 `parameters[KEY]` 是「存取」，写
     * `namedVariables[VARIABLE_NAME]` 是「供 `[[ ]]` 解析」。
     */
    const val VARIABLE_NAME = KEY

    /**
     * 用户在编辑器里看到 / 插入的引用形式。
     *
     * `[[ ]]` 是命名变量语法，不是魔法变量 —— 见类注释。
     */
    val VARIABLE_REFERENCE: String = "[[$VARIABLE_NAME]]"

    /**
     * 读标签。
     *
     * 无键 / 值为非 String / 全空白 ⇒ **空串**（**不是 null、不是 VNull**）。
     *
     * ⚠️ 非 String 值返回空串是**刻意的**：AI 侧 `coerceInputValue` 对
     * `ParameterType.STRING` 一定产出 String，磁盘上不该出现数字标签；
     * 手工编辑 JSON 塞数字属于用户自己破坏契约，静默丢标签好过
     * 让「标签是字符串」这条契约变得模糊（`"1"` 与 `1` 判等语义会分叉）。
     */
    fun labelOf(step: ActionStep): String =
        (step.parameters[KEY] as? String)?.trim().orEmpty()

    /**
     * 写标签，返回新的 [ActionStep]。
     *
     * ⚠️ **空白一律删键**，不写空串 —— 写空串会让卡片回显成一行空白、
     * `get_workflow` 输出 `__trigger_label: `，与「没标签」不可区分。
     */
    fun withLabel(step: ActionStep, label: String): ActionStep {
        val value = label.trim()
        val newParameters = if (value.isEmpty()) {
            step.parameters - KEY
        } else {
            step.parameters + (KEY to value)
        }
        return step.copy(parameters = newParameters)
    }

    /**
     * 由本次执行的**触发步骤 id** 反查标签；取不到 ⇒ 空串。
     *
     * @param triggerStepId 本次执行是由哪个触发器步骤引发的。
     *   为 null（直接调用 / 未指定）或 id 不在 `workflow.triggers` 里 ⇒ 空串。
     *
     * ⚠️ 只在 `workflow.triggers` 里找，不看 `steps` —— 标签是**触发器**的属性。
     */
    fun labelFor(workflow: Workflow, triggerStepId: String?): String {
        val trigger = triggerStepId?.let(workflow::getTrigger) ?: return ""
        return labelOf(trigger)
    }
}
