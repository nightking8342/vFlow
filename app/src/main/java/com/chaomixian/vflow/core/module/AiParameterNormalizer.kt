// 文件：main/java/com/chaomixian/vflow/core/module/AiParameterNormalizer.kt
// 描述：模块可选的「AI 入参规范化」钩子。用于那些**参数形态是结构化数据、
//      但存储形态是 JSON 字符串**的模块——AI 与编辑器走的是两条不同的写入路径，
//      本接口让模块自己把 AI 那条路径的值补齐到与编辑器一致的存储形态。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.module

/**
 * 让模块把「AI 传进来的松散参数值」规范化为自己的**标准存储形态**。
 *
 * ## 为什么需要这个钩子
 *
 * 同一个模块参数有两条写入路径，值的形态天然不同：
 *
 * | 路径 | 值的来源 | 形态 |
 * |---|---|---|
 * | 编辑器 | UIProvider / 表单控件 | 已是模块期望的形态（如 JSON 字符串） |
 * | **AI** | `call_module` / `save_workflow` 的 JSON 入参 | **松散 JSON**——数组经 [JsonElement] 解析后可能是 `List<Map<...>>` |
 *
 * 多数模块不需要它：它们的参数是标量（字符串、数字、枚举），
 * `ChatAgentModuleExecutor.coerceInputValue` 按 [ParameterType] 转换即可。
 *
 * **只有「参数本身是列表或字典」的模块需要**——因为 `coerceInputValue` 对这类值
 * 无法做有意义的转换：
 *
 * - 声明为 [ParameterType.STRING]：走 `rawValue.toString()`，而 `List<Map<...>>`
 *   的 `toString()` 产出的是 Kotlin 字面量（`{name=x, type=y}`）**不是合法 JSON**，落库后解析必炸。
 * - 声明为 [ParameterType.ANY]：原样透传，形态仍是 `List<Map<...>>`，
 *   与模块期望的 JSON 字符串不一致。
 *
 * 故这类模块应当：
 * 1. 在 [ActionModule.getInputs] 里把该字段声明为 [ParameterType.ANY]（让值原样抵达本钩子）；
 * 2. 实现本接口，在钩子里做「松散结构 → 标准存储形态」的转换。
 *
 * ## 与编辑器路径的关系
 *
 * **本钩子只作用于 AI 路径**（`ChatAgentModuleExecutor.buildParameters`）。
 * 编辑器的 UIProvider 直接写标准形态，不经此处。两条路径**必须收敛到同一形态**，
 * 否则同一份数据会有两种表示——这正是本接口存在的意义：
 * 把「编辑器靠 UI 保证的事」在 AI 侧显式补上，而不是让 AI 去猜存储格式。
 *
 * ## 实现约定
 *
 * - **纯函数、无副作用**：只做值转换，不读写外部状态（它会在校验前被调用）。
 * - **失败时原样返回**：无法识别或字段缺失时**返回入参本身**，让下游的
 *   `validate()` / `coerceInputValue` 去报错——不要在钩子里抛异常或静默丢弃，
 *   那会把「参数错」变成「工具崩」，模型拿不到可自愈的信息。
 * - **不得新增/删除键**：只转换已存在的键的值。未知键该由 `buildParameters`
 *   的 `rejectedKeys` 机制显式拒绝。
 */
interface AiParameterNormalizer {

    /**
     * 把 AI 传入的参数表规范化为模块的标准存储形态。
     *
     * @param parameters 本轮已按 [InputDefinition] 收下的参数（模块默认值与 AI 入参的合并结果）。
     * @return 规范化后的参数表；无需改动时返回入参本身。
     */
    fun normalizeAiParameters(parameters: Map<String, Any?>): Map<String, Any?>
}
