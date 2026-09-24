package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ChatStreamNormalizer] 的回归测试。
 *
 * ⚠️ 本层的失败模式是**「用户逐字看到标签原文」**或**「思考内容凭空消失」**——
 * 都不报错。故每个用例都用「把整串一次性喂进去」vs「逐字符喂进去」两种方式断言，
 * 后者才是真实的流式到达形态。
 */
class ChatStreamNormalizerTest {

    /** 逐字符喂入，模拟最坏的分片（现实中分片边界是随机的）。 */
    private fun charByChar(input: String): String {
        val normalizer = ChatStreamNormalizer()
        val out = StringBuilder()
        input.forEach { ch ->
            normalizer.accept(ch.toString()).forEach { out.append(it.visibleText()) }
        }
        normalizer.flush().forEach { out.append(it.visibleText()) }
        return out.toString()
    }

    /** 一次性喂入。 */
    private fun atOnce(input: String): String {
        val normalizer = ChatStreamNormalizer()
        val out = StringBuilder()
        normalizer.accept(input).forEach { out.append(it.visibleText()) }
        normalizer.flush().forEach { out.append(it.visibleText()) }
        return out.toString()
    }

    private fun ChatStreamEvent.visibleText(): String = when (this) {
        is ChatStreamEvent.TextDelta -> text
        is ChatStreamEvent.ReasoningDelta -> ""  // 思考不参与正文比较
        else -> ""
    }

    private fun reasoningOf(input: String): String {
        val normalizer = ChatStreamNormalizer()
        val events = mutableListOf<ChatStreamEvent>()
        input.forEach { events += normalizer.accept(it.toString()) }
        events += normalizer.flush()
        return events.filterIsInstance<ChatStreamEvent.ReasoningDelta>().joinToString("") { it.text }
    }

    // ------------------------------------------------------------ 核心：标签不得漏出

    /**
     * ⚠️ **F12 反证（最重要的一例）**：把「扣住未决前缀」的逻辑去掉
     * （即 `isPotentialTag` 恒返回 false），本用例会输出 `<thi` 或整个标签原文。
     *
     * 这是本层存在的**唯一理由**——用户不应看到 `<tool_call>` / `thinking` 的字面量。
     */
    @Test
    fun `tags never leak to visible text even when split char by char`() {
        val inputs = listOf(
            "<tool_call>{\"a\":1}</tool_call>",
            "<tool_calls>[{\"a\":1}]</tool_calls>",
            "<function_calls>x</function_calls>",
            "<thinking>思考</thinking>",
            "<think>思考</think>",
        )
        inputs.forEach { input ->
            val visible = charByChar(input)
            assertTrue(
                "逐字符喂入时漏出了标签原文：input=$input visible=$visible",
                !visible.contains("<") && !visible.contains(">"),
            )
        }
    }

    /** 正常文本不受影响（防止「为了扣标签把正文也扣没了」）。 */
    @Test
    fun `plain text passes through unchanged`() {
        assertEquals("你好，世界", charByChar("你好，世界"))
        assertEquals("你好，世界", atOnce("你好，世界"))
    }

    /** 数学比较符不会被误判成标签（`<` 后面不是已知标签名 ⇒ 普通文本）。 */
    @Test
    fun `comparison operators are not treated as tags`() {
        assertEquals("1 < 2 且 3 > 2", charByChar("1 < 2 且 3 > 2"))
        assertEquals("a<b", atOnce("a<b"))
        // 形近但非标签：`<think >`（多余空格）、`<tool_callx>`、`<b>`
        assertEquals("x<b>y", atOnce("x<b>y"))
        assertEquals("x<think >y", atOnce("x<think >y"))
    }

    // ------------------------------------------------------------ think 的搬运（B5）

    /**
     * ⚠️ **B5 反证**：若本层只输出文本串（不产出 [ChatStreamEvent.ReasoningDelta]），
     * 本用例会得到「思考正文混在可见文本里」或「思考内容消失」。
     *
     * 内联 ` thinking` 必须**搬运**到 reasoning，而不是删除——
     * 因为 Ollama 默认模型 `qwen3:8b` 吐的就是内联形态（`ChatModels.kt:42`），
     * 而非流式路径下它会被渲染成**独立的折叠块**。
     */
    @Test
    fun `inline thinking is moved to reasoning not shown in content`() {
        val input = "<think>我在思考</think>答案"
        assertEquals("答案", charByChar(input))
        assertEquals("我在思考", reasoningOf(input))
    }

    /**
     * 跨 delta 断裂的 think 标签：正文**不能**先冒出半截，思考内容**也不能**丢。
     *
     * 反证：去掉「未决前缀扣留」后，前半段 `<thi` 会作为正文输出（用户看到半个标签）。
     */
    @Test
    fun `thinking tag split across deltas is handled`() {
        val normalizer = ChatStreamNormalizer()
        val text = StringBuilder()
        val reasoning = StringBuilder()

        fun feed(part: String) {
            normalizer.accept(part).forEach { event ->
                when (event) {
                    is ChatStreamEvent.TextDelta -> text.append(event.text)
                    is ChatStreamEvent.ReasoningDelta -> reasoning.append(event.text)
                    else -> Unit
                }
            }
        }

        feed("<thi")
        feed("nk>思考")
        feed("过程</thi")
        feed("nking>")
        feed("正文")
        normalizer.flush().forEach { event ->
            when (event) {
                is ChatStreamEvent.TextDelta -> text.append(event.text)
                is ChatStreamEvent.ReasoningDelta -> reasoning.append(event.text)
                else -> Unit
            }
        }

        assertEquals("正文", text.toString())
        assertEquals("思考过程", reasoning.toString())
    }

    /**
     * ⚠️ **未闭合的 think**：非流式路径的 `thinkRegex` 要求闭合标签，
     * 不闭合就**不搬运**——整段（含标签原文）留在正文里。
     *
     * 本层必须与之**方向一致**（内容归位可能不同，见类注释的差异表），
     * 且**不得**把思考内容凭空丢弃。
     */
    @Test
    fun `unclosed thinking falls back to literal text`() {
        val input = "<think>没有闭合的思考"
        val visible = charByChar(input)
        // 至少要把原始内容还给用户，不能静默吞掉
        assertTrue("内容不得丢失，实际: $visible", visible.contains("没有闭合的思考"))
        // 且终态应与非流式的口径一致（把标签当普通文本）
        val expected = normalizeAssistantReply(input, null).content
        assertEquals(expected, visible)
    }

    /**
     * ⚠️ **等价性断言**：对一批输入，逐字符流式产出的正文
     * 必须等于非流式的 [normalizeAssistantReply] 结果。
     *
     * 这是 §8 验收项「流式最终呈现 == 非流式结果」在本层的落地。
     * 反证：任何一处正则/折叠口径写错，本用例都会指出**具体是哪个输入**。
     */
    @Test
    fun `streamed text matches non streaming normalization`() {
        val samples = listOf(
            "普通正文",
            "第一段\n\n第二段",
            "<think>想</think>答案",
            "前<tool_call>{\"a\":1}</tool_call>后",
            "<tool_calls>[1]</tool_calls>答案",
            "<function_calls>内容</function_calls>",
        )
        samples.forEach { input ->
            val streamed = charByChar(input)
            val expected = normalizeAssistantReply(input, null).content
            assertEquals("输入: $input", expected, streamed)
        }
    }

    /** 多个 think 块之间用 `\n\n` 连接（对齐非流式的 `joinToString("\n\n")`）。 */
    @Test
    fun `multiple thinking blocks are separated by blank line`() {
        val input = "<think>A</think>正文<think>B</think>"
        val reasoning = reasoningOf(input)
        assertEquals("A\n\nB", reasoning.trim())

        val expected = normalizeAssistantReply(input, null).reasoningContent
        assertEquals("应与非流式一致", expected, reasoning.trim())
    }

    // ------------------------------------------------------------ 空与边界

    @Test
    fun `empty deltas produce nothing`() {
        val normalizer = ChatStreamNormalizer()
        assertTrue(normalizer.accept("").isEmpty())
        assertTrue(normalizer.flush().isEmpty())
    }

    /** 落单的关闭标签：非流式的 `thinkRegex` 不匹配它，且 `stripInlineToolMarkup` 也不删它。 */
    @Test
    fun `stray closing think tag is kept as text`() {
        val input = "正文</think>后续"
        assertEquals(normalizeAssistantReply(input, null).content, charByChar(input))
    }

    /** `<tool_calls>`（复数）**只删标签、保留内容**——与 `<tool_call>`（单数）配对删除不同。 */
    @Test
    fun `plural tool calls tags keep their content`() {
        val input = "<tool_calls>保留我</tool_calls>"
        val visible = charByChar(input)
        assertTrue("复数形态的内容必须保留，实际: $visible", visible.contains("保留我"))
        assertEquals(normalizeAssistantReply(input, null).content, visible)
    }

    /** 单数 `<tool_call>` 配对删除**连同内容**——这是唯一会删掉大段正文的规则。 */
    @Test
    fun `singular tool call tag removes its content`() {
        val input = "前<tool_call>{\"secret\":1}</tool_call>后"
        val visible = charByChar(input)
        assertTrue("单数形态的内容应被删除，实际: $visible", !visible.contains("secret"))
        assertEquals(normalizeAssistantReply(input, null).content, visible)
    }
}
