package com.chaomixian.vflow.ui.chat

/**
 * 助手回复的**规范化纯函数层**。
 *
 * **为什么从 `ChatCompletionClient` 里搬出来**（fail-fast 的共享源）：
 * 流式路径也必须产出与非流式**逐字段一致**的结果（`chat-streaming-design.md` §8 验收项）。
 * 若流式层复制一份正则，两条路径必然漂移——而漂移的表现是
 * 「同样一句话，流式落盘的和非流式落盘的不一样」，属**静默**错误。
 * 故本文件是**唯一**的规范化定义处，两条路径共用。
 *
 * 无 Android 依赖，可纯 JVM 单测。
 */

/**
 * 剥离正文里的**内联工具调用标记**。
 *
 * 有些模型（或网关）会把工具调用同时以文本形式写进正文。这些标记对用户无意义，
 * 且若原样落盘会污染下一轮请求的上下文。
 *
 * ⚠️ **三条正则覆盖的形态不同，改动时必须逐条确认**（顺序也有意义）：
 * 1. `<tool_call ...>…</tool_call>` —— **连同中间内容一起删除**；
 *    这是唯一会「删掉大段正文」的规则。
 *    ⚠️ `tool_call\b` 的 `\b` 使 `<tool_calls>`（复数）**不匹配**本规则
 *    （`l` 与 `s` 之间没有词边界）——复数形态走规则 3，**标签删、内容留**。
 * 2. `</?function_calls?>` 系列 —— **只删标签**，保留内容。
 * 3. `</?tool_calls?>` 系列 —— **只删标签**，保留内容。
 *    它同时覆盖**落单的**开/闭标签（无配对的一方也会被删）。
 *
 * 后三条空白规则是「折叠空行」，也是流式下**唯一无法完全增量复现**的部分，
 * 详见 [ChatStreamNormalizer] 的说明。
 */
internal fun stripInlineToolMarkup(content: String): String {
    return content
        .replace(Regex("(?is)<tool_call\\b[^>]*>.*?</tool_call>"), " ")
        .replace(Regex("(?is)</?function_calls?\\b[^>]*>"), " ")
        .replace(Regex("(?is)</?tool_calls?\\b[^>]*>"), " ")
        .replace(Regex("(?m)^[ \t]+$"), "")
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("""\n\s*\n+"""), "\n")
        .trim()
}

/** 取第一个非空白值并 trim。**注意它是「取一」而非「合并」**，见下方 [normalizeAssistantReply] 的 H1 注记。 */
internal fun firstNonBlank(vararg values: String?): String? {
    return values.firstOrNull { !it.isNullOrBlank() }?.trim()
}

/**
 * 把「原始正文 + 独立的 reasoning 字段」规范化成最终结果。三条非流式路径
 * （OpenAI chat/completions、Anthropic messages、Responses）**都**在收尾时调用它。
 *
 * 做两件**语义转换**（不是可有可无的清理）：
 *
 * 1. **内联 ` thinking` → `reasoningContent`**：不是删除，是**搬运**。
 *    这对本项目是必需的——Ollama 默认模型 `qwen3:8b`（`ChatModels.kt:42`）吐的就是
 *    内联在正文里的 ` thinking`，而不是独立的 `reasoning_content` 字段。
 * 2. **剥掉内联工具标记**（见 [stripInlineToolMarkup]）。
 *
 * ⚠️ **H1（既有缺陷，尚未修）**：`mergedReasoning` 用的是 [firstNonBlank]——**取第一个非空**。
 * 若模型**同时**给出独立 `reasoning_content` 字段**和**正文内联 ` thinking`，
 * 则内联那段**被静默丢弃**（而它上面已经被 `thinkRegex.replace` 从正文里删掉了）
 * ⇒ 那段思考内容**彻底消失**。
 * 语义上更合理的是「合并」，但那会**改变非流式路径的既有行为**，
 * 按 `FORK.md` 须单独评估 + 登记，故此处保持原样。改之前先读 §12。
 */
internal fun normalizeAssistantReply(
    content: String,
    reasoningContent: String?,
): ChatCompletionResult {
    val thinkRegex = Regex("(?is)<(?:think|thinking)>(.*?)</(?:think|thinking)>")
    val matches = thinkRegex.findAll(content).toList()
    val inlineReasoning = matches.joinToString(separator = "\n\n") { it.groupValues[1].trim() }
        .trim()
        .ifBlank { null }
    val visibleContent = if (matches.isEmpty()) {
        stripInlineToolMarkup(content)
    } else {
        stripInlineToolMarkup(thinkRegex.replace(content, ""))
    }
    val mergedReasoning = firstNonBlank(reasoningContent, inlineReasoning)
    val normalizedContent = when {
        visibleContent.isNotBlank() -> visibleContent
        mergedReasoning != null -> ""
        else -> content.trim()
    }
    return ChatCompletionResult(
        content = normalizedContent,
        reasoningContent = mergedReasoning,
        totalTokens = null,
    )
}

/**
 * 规范化所用的**正则定义**，供 [ChatStreamNormalizer] 增量复现时共用。
 *
 * ⚠️ 集中在此，避免「流式认得的标签集」与「非流式删掉的标签集」不一致——
 * 那会让用户**逐字看到**某个标签原文，而它最终又没被落盘。
 */
internal object ChatMarkupPatterns {
    /** 内联 think 块（不区分大小写、`.` 匹配换行）。 */
    val THINK_BLOCK = Regex("(?is)<(?:think|thinking)>(.*?)</(?:think|thinking)>")

    /** think 开启标签的确切文本（保留原始大小写，flush 未闭合块时要原样吐回）。 */
    val THINK_OPEN = Regex("<(?:think|thinking)>", RegexOption.IGNORE_CASE)

    /** think 关闭标签。⚠️ 允许与开启标签**不成对**（`<think>…</thinking>` 会被 [THINK_BLOCK] 匹配）。 */
    val THINK_CLOSE = Regex("</(?:think|thinking)>", RegexOption.IGNORE_CASE)

    /**
     * `<tool_call …>`（**单数**、可带属性）——配对删除的起始标签。
     * ⚠️ `\b` 使复数 `<tool_calls>` 落空，与 [stripInlineToolMarkup] 规则 1 一致。
     */
    val TOOL_CALL_PAIR_OPEN = Regex("<tool_call\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 配对删除的**结束**标签。
     * ⚠️ 刻意写成**字面量**（不允许属性、不匹配复数）：与 [stripInlineToolMarkup] 规则 1
     * 的 `</tool_call>` 逐字对应。写成 `</tool_calls?>` 会让 `<tool_call>…</tool_calls>`
     * 也被配对删除，而非流式路径**不删**——两边就此漂移。
     */
    val TOOL_CALL_PAIR_CLOSE = Regex("</tool_call>", RegexOption.IGNORE_CASE)

    /** 落单即删的标签：`tool_call(s)` / `function_call(s)` 的开闭形态。 */
    val STANDALONE_TAG = Regex("</?(?:tool_calls?|function_calls?)\\b[^>]*>", RegexOption.IGNORE_CASE)

    /**
     * 空白折叠（[stripInlineToolMarkup] 的后两条规则）。
     *
     * ⚠️ **规则 1（`^[ \t]+$`，删空白行）不在此处**：它只对「整行只有空白」生效，
     * 而 [ChatStreamNormalizer] 只在**消息最开头**可能遇到这种情况，已由 `trimStart` 覆盖；
     * 中间出现的空白行会被规则 2 + 规则 3 的组合吃掉。
     */
    fun collapseWhitespace(text: String): String = text
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("""\n\s*\n+"""), "\n")
}
