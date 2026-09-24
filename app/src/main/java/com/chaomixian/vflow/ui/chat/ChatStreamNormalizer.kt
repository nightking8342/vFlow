package com.chaomixian.vflow.ui.chat

/**
 * **流式安全的文本规范化**（`chat-streaming-design.md` §4.9，含 v3 的 B5 修正）。
 *
 * ## 它解决什么问题
 *
 * 非流式路径在收尾时调 [normalizeAssistantReply] 做两件**语义转换**：
 * 内联 ` thinking` 搬到 `reasoningContent`、剥掉内联工具标记。
 * 流式下这件事有三条都不可行的捷径：
 *
 * | 捷径 | 问题 |
 * |---|---|
 * | 逐 delta 直接跑 [normalizeAssistantReply] | think 正则**要求闭合标签**，闭合前会先显示原文再消失 |
 * | 只在收尾时跑 | 屏幕上已显示的文字在最后一刻**整体改写** |
 * | 完全不跑 | 用户**逐字看到** `<tool_call>` / `thinking` 原文 |
 *
 * ⇒ 本类持有「未决缓冲区」：**遇到可能构成标签的前缀时先扣住不放**，
 * 直到能确定它是标签（按非流式同一套正则处理）还是普通文本（原样放行）。
 *
 * ## ⚠️ 三条必须遵守的约束
 *
 * 1. **输出类型必须是 [ChatStreamEvent] 序列，不能是 `String`**（B5）：
 *    内联 ` thinking` 不是「删除」而是**搬运**到 reasoning——
 *    只输出文本串的话，那段思考要么**原样上屏**、要么**凭空消失**，两条都错。
 *    而本项目 Ollama 默认模型 `qwen3:8b`（`ChatModels.kt:42`）吐的**正是内联形态**。
 * 2. **正则必须取自 [ChatMarkupPatterns]**（与非流式共用）：
 *    自己另写一套会让「流式认得的标签」与「非流式删掉的标签」不一致。
 * 3. **think 块内只认关闭标签**：非流式的 `.*?` 是非贪婪捕获，
 *    块内的 `<tool_call>` / 嵌套 `<think>` 都只是**思考正文**的一部分，
 *    不得被当成结构标签处理（否则思考内容会被削掉一段）。
 *
 * ## ⚠️ 已知的、有意接受的不一致（过程态 vs finalize 终态）
 *
 * 收尾的**权威值**由归约器调 [normalizeAssistantReply] 得出（§4.4 的 B4），
 * 故本类只负责**过程显示**。以下差异均属设计接受：
 *
 * | 场景 | 过程态 | 终态 |
 * |---|---|---|
 * | 未闭合的 `<think>`（畸形输出） | 先按 reasoning 增量显示，flush 时**连同开启标签**补回正文 | 与非流式一致（保留字面量） |
 * | 空行折叠 | 文字**滞后一个块**（折叠是替换语义，增量通道只能追加） | 一次跳变到位 |
 * | 行尾空白 | 增量折叠 | `trim()` 回收 |
 *
 * ## 无 Android 依赖，可纯 JVM 单测
 */
internal class ChatStreamNormalizer {

    /** 尚未决定归属的原始缓冲（可能含一个未完成的标签）。 */
    private var pending: String = ""

    /** 已放行的**原始**正文（未折叠）。增量结果由它重算，故折叠可随时收敛。 */
    private var rawText: String = ""

    /** 已经下发给 UI 的折叠后正文（前缀比较的基准）。 */
    private var emittedText: String = ""

    /** 是否已在 `<think>` 块内。 */
    private var inThink: Boolean = false

    /** 当前未闭合 think 块的开启标签原文（flush 时按字面量吐回正文）。 */
    private var thinkOpenTag: String = ""

    /** 当前 think 块已作为 reasoning 下发的文本（未闭合时要还给正文）。 */
    private var thinkText: String = ""

    /** 已开启过的 think 块数（多块之间补 `\n\n`，对齐非流式的 join 语义）。 */
    private var thinkBlockCount: Int = 0

    /** 是否处于「配对的 `<tool_call>…</tool_call>` 内部」（该整段被替换成一个空格）。 */
    private var inToolCallPair: Boolean = false

    private var finished: Boolean = false

    /** 收尾阶段：不再扣留任何前缀，并做与 [stripInlineToolMarkup] 一致的最终 trim。 */
    private var finalizing: Boolean = false

    /**
     * 喂入一个原始 delta，返回本次可下发的事件（可能为空）。
     *
     * ⚠️ 返回为空是**正常**的：内容正被扣在缓冲区里等标签判定。
     * 调用方**不得**把「返回空」当作流结束。
     */
    fun accept(delta: String): List<ChatStreamEvent> {
        if (finished || delta.isEmpty()) return emptyList()
        val out = mutableListOf<ChatStreamEvent>()
        pending += delta
        drain(out)
        return out
    }

    /**
     * 流结束，放行缓冲区内剩余内容。
     *
     * ⚠️ **未闭合的 `<think>` 按普通文本处理**——与非流式一致
     * （`thinkRegex` 要求闭合标签，不闭合就不搬运，整段连标签一起留在正文里）。
     * 因此要把**开启标签原文 + 已下发的思考正文**一起补回正文，
     * 否则用户会看到内容凭空消失。
     */
    fun flush(): List<ChatStreamEvent> {
        if (finished) return emptyList()
        finished = true
        val out = mutableListOf<ChatStreamEvent>()

        // 未配对的 `<tool_call>`（非流式下只删标签、保留内容）：
        // 退出成对模式，让下方 drain 把 pending 里的内容按普通文本处理。
        inToolCallPair = false

        if (inThink) {
            inThink = false
            rawText += thinkOpenTag + thinkText
            thinkOpenTag = ""
            thinkText = ""
        }

        finalizing = true
        drain(out)
        emitText(out)
        return out
    }

    // ---------------------------------------------------------------- 内部

    /**
     * 消费 pending：反复「找出第一个完整标签 → 放行它之前的文本 → 处理标签」，
     * 直到剩余部分可能是半个标签为止。
     */
    private fun drain(out: MutableList<ChatStreamEvent>) {
        while (true) {
            if (inToolCallPair) {
                // 整段替换成一个空格：找到配对的结束标签，连同中间内容一起丢弃。
                // ⚠️ 找不到时**不丢弃** pending 里的内容——它可能根本不是成对形态
                // （非流式下未闭合的 `<tool_call>` 只删标签、保留内容），
                // 丢弃它会让那段文字凭空消失。
                val close = ChatMarkupPatterns.TOOL_CALL_PAIR_CLOSE.find(pending)
                if (close == null) return
                pending = pending.substring(close.range.last + 1)
                inToolCallPair = false
                continue
            }

            val hit = findFirstTag(pending)
            if (hit != null) {
                emitRaw(pending.substring(0, hit.start), out)
                pending = pending.substring(hit.endExclusive)
                handleTag(hit.tag, out)
                continue
            }

            // 没有完整标签：扣住「可能是半个标签」的尾部，其余放行。
            // ⚠️ 这一步是「标签原文绝不外泄」的唯一保证——去掉它，用户就会看到 `<thi`。
            val holdFrom = if (finalizing) -1 else partialTagStart(pending)
            val releasable = if (holdFrom >= 0) pending.substring(0, holdFrom) else pending
            if (releasable.isNotEmpty()) {
                emitRaw(releasable, out)
                pending = pending.substring(releasable.length)
            }
            return
        }
    }

    private data class TagHit(val start: Int, val endExclusive: Int, val tag: Tag)

    /**
     * 找出 pending 中**最靠前**的完整标签。
     *
     * ⚠️ 匹配顺序有意义：`<tool_call>`（单数）**同时**满足 [ChatMarkupPatterns.TOOL_CALL_PAIR_OPEN]
     * 与 [ChatMarkupPatterns.STANDALONE_TAG]（`\b` 在 `l` 与 `>` 之间成立），
     * 而两者语义不同——前者**连内容一起删**、后者**只删标签**。必须先判成对形态。
     * 这与 [stripInlineToolMarkup] 的规则顺序一致（规则 1 先于规则 3）。
     */
    private fun findFirstTag(text: String): TagHit? {
        var i = text.indexOf('<')
        while (i >= 0) {
            val gt = text.indexOf('>', i)
            if (gt < 0) return null  // 后面还没有 '>'，不可能有完整标签
            val candidate = text.substring(i, gt + 1)
            val tag = matchTag(candidate)
            if (tag != null) return TagHit(i, gt + 1, tag)
            i = text.indexOf('<', i + 1)
        }
        return null
    }

    private fun matchTag(candidate: String): Tag? {
        // ⚠️ think 块内**只认关闭标签**：块内的 `<tool_call>` / 嵌套 `<think>` 是思考正文
        // （非流式的 `.*?` 是非贪婪捕获，不会把它们当结构处理）
        if (inThink) {
            return if (ChatMarkupPatterns.THINK_CLOSE.matches(candidate)) {
                Tag.ThinkClose(candidate)
            } else {
                null
            }
        }
        if (ChatMarkupPatterns.THINK_OPEN.matches(candidate)) return Tag.ThinkOpen(candidate)
        if (ChatMarkupPatterns.THINK_CLOSE.matches(candidate)) return Tag.ThinkClose(candidate)
        if (ChatMarkupPatterns.TOOL_CALL_PAIR_OPEN.matches(candidate)) return Tag.ToolCallPairOpen(candidate)
        if (ChatMarkupPatterns.STANDALONE_TAG.matches(candidate)) return Tag.Standalone(candidate)
        return null
    }

    /**
     * `text` 中**最靠后的**「可能开始一个我们关心的标签」的 `<` 下标；没有则 -1。
     * 从该下标起的全部内容都要扣住，等更多输入。
     *
     * ⚠️ 判定必须**宁宽勿严**：放行太早会把半个标签当正文吐出去；
     * 扣住太久只是显示延迟（flush 时一律放行）。
     */
    private fun partialTagStart(text: String): Int {
        var i = text.lastIndexOf('<')
        while (i >= 0) {
            if (couldBeTagPrefix(text.substring(i))) return i
            i = text.lastIndexOf('<', i - 1)
        }
        return -1
    }

    /**
     * `suffix`（以 `<` 开头）是否可能是某个目标标签的**前缀**。
     *
     * 三种成立情形：
     * 1. 名字**还没打完**（`<thi`）——仍可能是 `think`；
     * 2. 名字打完但**没有 `>`**（`<tool_call attr="x"`）——属性长度未知，必须等；
     * 3. 刚打完名字（`<think`）——下一个字符可能让名字更长。
     *
     * ⚠️ 名字**已完整且带 `>`** 时返回 `false`：那是完整标签，
     * 已由 [findFirstTag] 处理；走到这里说明它匹配不上任何标签，属普通文本。
     */
    private fun couldBeTagPrefix(suffix: String): Boolean {
        if (!suffix.startsWith("<")) return false
        var body = suffix.substring(1)
        if (body.startsWith("/")) body = body.substring(1)

        // ⚠️ **开闭两态用同一份名字表**：`</tool_calls>` / `</function_calls>` 同样是被删目标
        // （[ChatMarkupPatterns.STANDALONE_TAG] 的 `</?` 覆盖开闭两种形态）。
        // 曾把关闭态收窄成只有 think，结果这两个关闭标签**既不被扣留、也不被识别**，
        // 原样漏给了用户——而它们的开启标签已被正确删掉，看上去像「只删了一半」。
        val names = ALL_TAG_NAMES

        val nameEnd = body.indexOfFirst { !(it.isLetterOrDigit() || it == '_') }
            .let { if (it < 0) body.length else it }
        val name = body.substring(0, nameEnd)
        val rest = body.substring(nameEnd)

        if (rest.isEmpty()) {
            // 名字尚可能继续（含「还没开始打」的空名字）
            return names.any { it.startsWith(name, ignoreCase = true) }
        }
        // 名字已完整（后面跟了非名字字符）——必须精确匹配到某个目标名
        if (names.none { it.equals(name, ignoreCase = true) }) return false
        // 名字匹配且尚无 '>' ⇒ 属性可能还没收尾，必须等
        return !rest.contains('>')
    }

    private fun handleTag(tag: Tag, out: MutableList<ChatStreamEvent>) {
        when (tag) {
            is Tag.ThinkOpen -> {
                // 非流式：多个 think 块用 `\n\n` 连接（`joinToString`）
                if (thinkBlockCount > 0) out += ChatStreamEvent.ReasoningDelta(THINK_SEPARATOR)
                thinkBlockCount++
                inThink = true
                thinkOpenTag = tag.literal
                thinkText = ""
            }

            is Tag.ThinkClose -> {
                if (!inThink) {
                    // 落单的关闭标签：`thinkRegex` 不匹配它，`stripInlineToolMarkup` 也不删它
                    // ⇒ 原样保留为可见文本
                    rawText += tag.literal
                    emitText(out)
                    return
                }
                inThink = false
                thinkOpenTag = ""
                thinkText = ""
            }

            is Tag.ToolCallPairOpen -> {
                inToolCallPair = true
                // 非流式：整段替换成**一个空格**（`" "`）
                rawText += " "
                emitText(out)
            }

            is Tag.Standalone -> {
                rawText += " "
                emitText(out)
            }
        }
    }

    /**
     * 放行一段**原始**文本：think 块内记为 reasoning，否则进正文。
     *
     * ⚠️ think 块内的文本**不进** [rawText]——非流式下它被 `thinkRegex.replace` 从正文里移除了。
     */
    private fun emitRaw(text: String, out: MutableList<ChatStreamEvent>) {
        if (text.isEmpty()) return
        if (inThink) {
            thinkText += text
            out += ChatStreamEvent.ReasoningDelta(text)
        } else {
            rawText += text
            emitText(out)
        }
    }

    /**
     * 按增量下发正文：只发**比已下发内容更长的前缀**部分。
     *
     * ⚠️ 折叠（连续空行压成一个）是**替换语义**，而增量通道只能追加。
     * 若重算结果不再是已下发内容的前缀（折叠改写了中间），**不下发回退**——
     * 差异由 finalize 的权威值收敛（见类注释的差异表）。
     */
    private fun emitText(out: MutableList<ChatStreamEvent>) {
        val collapsed = collapseForStreaming(rawText)
        if (collapsed.length > emittedText.length && collapsed.startsWith(emittedText)) {
            out += ChatStreamEvent.TextDelta(collapsed.substring(emittedText.length))
            emittedText = collapsed
        }
    }

    /**
     * 正文的增量折叠口径，对齐 [stripInlineToolMarkup]：
     * 折叠空白 + 去掉开头空白（结尾空白等到 [flush]，因为随时可能有新内容）。
     */
    private fun collapseForStreaming(text: String): String {
        val collapsed = ChatMarkupPatterns.collapseWhitespace(text).trimStart()
        return if (finalizing) collapsed.trim() else collapsed.trimEnd(' ', '\t')
    }

    private sealed interface Tag {
        val literal: String

        data class ThinkOpen(override val literal: String) : Tag
        data class ThinkClose(override val literal: String) : Tag
        data class ToolCallPairOpen(override val literal: String) : Tag
        data class Standalone(override val literal: String) : Tag
    }

    private companion object {
        const val THINK_SEPARATOR = "\n\n"

        val THINK_NAMES = listOf("think", "thinking")

        /**
         * 需要识别的标签名。⚠️ 必须与 [ChatMarkupPatterns] 的正则覆盖面一致——
         * 少一个名字会让对应标签被当正文吐出去。
         */
        val ALL_TAG_NAMES = listOf(
            "think",
            "thinking",
            "tool_call",
            "tool_calls",
            "function_call",
            "function_calls",
        )
    }
}
