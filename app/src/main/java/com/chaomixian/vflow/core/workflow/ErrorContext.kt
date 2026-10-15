package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString

/**
 * **错误上下文**（fork 新增，P0-5 跳过跳转）。
 *
 * ## 它解决什么
 *
 * 一个「收尾 / 补偿」步骤通常被**多个**失败步骤指向 —— 光看步骤本身，它不知道
 * 「是谁跳过来的、错在哪」。本对象把最近一次被兜住的失败写成一个命名变量
 * `[[__error]]`，于是收尾步骤里可以直接读：
 *
 * ```
 * [[__error.message]]      → 失败原因
 * [[__error.step_index]]   → 失败步骤的显示序号（1-based，给人看）
 * [[__error.step_id]]      → 失败步骤的 step id（稳定锚，给程序用）
 * ```
 *
 * 对应成熟实现里的同款设施：n8n 的 `$json.error`、Step Functions `Catch` 的 `ResultPath`。
 *
 * ## 为什么载体必须是**字典**（决策 D7）
 *
 * 「为空 / 不为空」作用在**真 `VDictionary`** 上是可靠的
 * （`ConditionEvaluator`：`is VDictionary -> input1.raw.isEmpty()`）—— 正常路径注入**空字典**
 * ⇒ `[[__error]] 不为空` 恰好在「有失败被兜住」时为真，**绕开** survey §3 记录的那整套
 * `VNull` 陷阱（`存在/不存在` 恒常量、`VNull` 参与 `为空` 恒 false）。
 * 换成字符串载体，这套判据立刻失效。
 *
 * ## ⚠️ 恒注入（与 `[[__trigger_label]]` 同因同果）
 *
 * 缺键会让 `[[__error.message]]` 被 `VariableResolver` 解析成**字面量**
 * `"{[[__error.message]]}"`（`VariableResolver.kt:133` 的兜底分支）——
 * `If` 比较恒 false 且**没有任何报错**，用户查不到原因。
 * 故正常路径也注入**空字典**（不是不注入、也不是 `VNull`）。
 *
 * ## 与 P1 的关系
 *
 * 这个对象**步骤级跳转与工作流级错误处理器共用**。字段集合现在定死，P1 只**追加**
 * （如 `log_tail` / `executed_step_count`），不改已有字段。
 *
 * 纯 Kotlin（只用 `VDictionary` / `VNumber` / `VString`）⇒ 可纯 JVM 单测。
 */
object ErrorContext {

    /** 存储键 = 工作流内变量名（`[[NAME]]`）= AI 读写名。三处同名，只有这一份字面量。 */
    const val KEY = "__error"

    /**
     * 与 [KEY] 同值，语义化别名（`[[NAME]]` 里的 NAME）。
     * 拆开只为让调用点的**意图**可读（照 `TriggerLabel` 的既有约定）。
     */
    const val VARIABLE_NAME = KEY

    /** 用户在编辑器里看到 / 插入的引用形式（命名变量语法 `[[ ]]`，不是 `{{ }}`）。 */
    val VARIABLE_REFERENCE: String = "[[$VARIABLE_NAME]]"

    const val FIELD_STEP_INDEX = "step_index"
    const val FIELD_STEP_ID = "step_id"
    const val FIELD_MODULE_ID = "module_id"
    const val FIELD_TITLE = "title"
    const val FIELD_MESSAGE = "message"

    /**
     * 字段顺序即 UI（变量选择器「已声明的键」）顺序。
     *
     * ⚠️ 这些是**机器可读的键名**（`VDictionary.getProperty` 直接按 key 匹配、大小写敏感），
     * **不是**本地化文案 —— 编辑器里显示的也是它们本身（与函数工作流返回值同一约定）。
     */
    val FIELDS: List<String> = listOf(
        FIELD_STEP_INDEX,
        FIELD_STEP_ID,
        FIELD_MODULE_ID,
        FIELD_TITLE,
        FIELD_MESSAGE,
    )

    /**
     * 正常路径的取值：**空字典**。
     *
     * ⚠️ 共用一个实例是安全的 —— `VDictionary` 持有的是 `emptyMap()`（不可变）。
     */
    val EMPTY: VDictionary = VDictionary(emptyMap())

    /**
     * 构造一次「被兜住的失败」的上下文。
     *
     * @param stepIndex 失败步骤的**显示序号**（1-based）。给人看，不参与逻辑判断。
     * @param stepId 失败步骤的 step id。插入/删除步骤后仍然稳定 ⇒ 适合做程序判据。
     */
    fun of(
        stepIndex: Int,
        stepId: String,
        moduleId: String,
        errorTitle: String,
        errorMessage: String,
    ): VDictionary = VDictionary(
        linkedMapOf(
            FIELD_STEP_INDEX to VNumber(stepIndex),
            FIELD_STEP_ID to VString(stepId),
            FIELD_MODULE_ID to VString(moduleId),
            FIELD_TITLE to VString(errorTitle),
            FIELD_MESSAGE to VString(errorMessage),
        )
    )
}
