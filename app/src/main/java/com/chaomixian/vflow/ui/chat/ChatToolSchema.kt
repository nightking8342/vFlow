package com.chaomixian.vflow.ui.chat

import kotlinx.serialization.json.JsonObject

/**
 * **传输层的中性工具定义**（fork：AI 能力统一 §3.3 / D3-B）。
 *
 * 请求装配（`ChatCompletionClient`）只需要「名字 / 描述 / 任意 JSON Schema / 是否可截断」
 * 四样东西，与 Agent 专有字段（`moduleId` / `riskLevel` / `usageScopes` / `permissionNames` /
 * `backend` …）无关。此前工具段的形参类型是 [ChatAgentToolDefinition]，
 * 于是任何**非 Agent 来源**的工具（工作流内的 `AIModule` / `AgentModule` 等）
 * 都必须先伪造一个 Agent 专有类型才能发出去——本类型把这个门槛去掉。
 *
 * ⚠️ **不替代** [ChatAgentToolDefinition]：Agent 侧继续持有自己的专有字段，
 * 只在进入传输层时经 [toChatToolSchema] 适配一次。这样传输层只剩**一种**工具表示，
 * 不存在两条并行的 schema 装配代码。
 */
internal data class ChatToolSchema(
    val name: String,
    val description: String,
    /** 任意 JSON Schema，**原样透传**（含 `oneOf` / `$defs` / 嵌套数组等）。 */
    val inputSchema: JsonObject,
    /**
     * 输出是否受 `CHAT_MAX_TOOL_RESULT_INPUT_CHARS` 截断（语义同
     * [ChatAgentToolDefinition.truncatable]）。默认 `true` 保证新工具默认安全。
     */
    val truncatable: Boolean = true,
)

/**
 * Agent 侧**唯一**适配点：`ChatAgentToolDefinition` → 中性 [ChatToolSchema]。
 *
 * 该函数存在于此处的意义：`ChatAgentToolDefinition` 一个字段都不动，
 * Agent 专有字段继续由 Agent 侧持有，只有「下发工具」这一个动作跨过边界。
 */
internal fun ChatAgentToolDefinition.toChatToolSchema(): ChatToolSchema =
    ChatToolSchema(
        name = name,
        description = description,
        inputSchema = inputSchema,
        truncatable = truncatable,
    )
