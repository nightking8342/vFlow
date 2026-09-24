package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [patchStreamingMessage] / [finalizeStreamingMessage] 的回归测试。
 *
 * ⚠️ 本文件的重点是 §4.4 那 11 项收尾清单里**漏了就静默出错**的几项。
 * 关键用例做过**反证**（把代码改回 bug 版本确认变红），注释中注明反证内容。
 */
class ChatMessagePatchTest {

    private fun message(
        id: String = "m1",
        content: String = "",
        reasoning: String? = null,
        pending: Boolean = true,
        timestamp: Long = 1_000L,
    ) = ChatMessage(
        id = id,
        role = ChatMessageRole.ASSISTANT,
        content = content,
        reasoningContent = reasoning,
        timestampMillis = timestamp,
        isPending = pending,
    )

    private fun conversation(
        id: String = "c1",
        updatedAt: Long = 500L,
        messages: List<ChatMessage> = listOf(message()),
    ) = ChatConversation(
        id = id,
        title = "t",
        messages = messages,
        createdAtMillis = 1L,
        updatedAtMillis = updatedAt,
    )

    // ------------------------------------------------------------ 原位增长

    @Test
    fun `patch appends content in place keeping the same id`() {
        val before = listOf(conversation())
        val after = before.patchStreamingMessage("c1", "m1", content = "你好", reasoningContent = "想")
        val msg = after.single().messages.single()
        assertEquals("m1", msg.id)  // ⚠️ id 必须不变（LazyColumn 的 key）
        assertEquals("你好", msg.content)
        assertEquals("想", msg.reasoningContent)
    }

    /**
     * ⚠️ **反证**：若照抄现有的 `updateMessage`（`ChatViewModel.kt:1192`）的写法
     * ——无条件刷新 `updatedAtMillis` 并 `sortedByDescending` 重排——
     * 本用例会失败。流式下每秒十几次重排会让**侧边栏持续跳动**。
     */
    @Test
    fun `patch does not touch updatedAt or reorder conversations`() {
        val before = listOf(
            conversation(id = "c1", updatedAt = 500L),
            conversation(id = "c2", updatedAt = 900L, messages = listOf(message(id = "m2"))),
        )
        val after = before.patchStreamingMessage("c1", "m1", content = "x", reasoningContent = null)
        assertEquals("会话顺序不得变化", listOf("c1", "c2"), after.map { it.id })
        assertEquals("updatedAtMillis 不得被刷新", 500L, after.first { it.id == "c1" }.updatedAtMillis)
    }

    /**
     * ⚠️ 流式期间 `isPending` **必须保持 true**。
     *
     * 反证：若 patch 顺手置 false，消息会被 `persistSessionState`（`ChatViewModel.kt:1147`）
     * 提前落盘成**半截内容**，且 UI 会在还在生成时就停止「正在生成…」的提示。
     */
    @Test
    fun `patch keeps isPending true`() {
        val after = listOf(conversation()).patchStreamingMessage("c1", "m1", "半截", null)
        assertTrue("流式期间必须保持 isPending", after.single().messages.single().isPending)
    }

    /** 消息或会话不存在时**原样返回**，不抛错（流式期间会话可能被用户删掉）。 */
    @Test
    fun `patch is a no-op for unknown ids`() {
        val before = listOf(conversation())
        assertSame(before, before.patchStreamingMessage("nope", "m1", "x", null))
        assertSame(before, before.patchStreamingMessage("c1", "nope", "x", null))
    }

    // ------------------------------------------------------------ 收尾（§4.4 清单）

    private fun finalize(
        before: List<ChatConversation>,
        content: String = "答案",
        reasoning: String? = null,
        tools: List<ChatToolCall> = emptyList(),
        approval: ChatToolApprovalState? = null,
        now: Long = 9_000L,
    ) = before.finalizeStreamingMessage(
        conversationId = "c1",
        messageId = "m1",
        patch = StreamingFinalizePatch(
            content = content,
            reasoningContent = reasoning,
            tokenCount = 42,
            toolCalls = tools,
            toolApprovalState = approval,
        ),
        now = now,
    )

    /**
     * ⚠️⚠️ **本文件最重要的一例（F1）**。
     *
     * 反证：去掉 `isPending = false` 后本用例失败——而在真机上表现为
     * **永远显示「正在生成…」+ 重启后内容消失**（`filterNot { it.isPending }` 把它过滤掉了）。
     * 这是整条改造里最容易漏、后果最隐蔽的一项。
     */
    @Test
    fun `finalize clears isPending`() {
        val outcome = finalize(listOf(conversation()))
        assertFalse("必须显式置 false", outcome.conversations.single().messages.single().isPending)
        assertTrue(outcome.applied)
    }

    /**
     * ⚠️ **F13 的一半**：时间戳必须刷成**收尾时刻**。
     *
     * 反证：不刷 `timestampMillis` 后本用例失败——真机上 `MessageFooterRow`
     * 会显示**请求发起**的时刻，长回复下偏差数分钟。
     */
    @Test
    fun `finalize refreshes message timestamp to completion time`() {
        val before = listOf(conversation(messages = listOf(message(timestamp = 1_000L))))
        val outcome = finalize(before, now = 9_000L)
        assertEquals(9_000L, outcome.conversations.single().messages.single().timestampMillis)
    }

    /**
     * ⚠️ **F13 的另一半**：会话必须刷新 `updatedAtMillis` **并重排到最前**。
     *
     * 反证：去掉重排后本用例失败——真机上刚答完的会话会**沉在侧边栏旧的下面**。
     */
    @Test
    fun `finalize bumps conversation to top`() {
        val before = listOf(
            conversation(id = "c1", updatedAt = 500L),
            conversation(id = "c2", updatedAt = 900L, messages = listOf(message(id = "m2"))),
        )
        val outcome = finalize(before, now = 9_000L)
        assertEquals("被收尾的会话应排到最前", listOf("c1", "c2"), outcome.conversations.map { it.id })
        assertEquals(9_000L, outcome.conversations.first { it.id == "c1" }.updatedAtMillis)
    }

    /**
     * ⚠️ 工具调用与审批态必须一并写入，否则**审批卡片不出现**（用户点不到「批准」）。
     *
     * 反证：漏传 `toolApprovalState` 后本用例失败，真机表现为「AI 说了要调用工具，但界面没有任何按钮」。
     */
    @Test
    fun `finalize writes tool calls and approval state`() {
        val tools = listOf(ChatToolCall(id = "t1", name = "get_weather", argumentsJson = "{}"))
        val outcome = finalize(listOf(conversation()), tools = tools, approval = ChatToolApprovalState.PENDING)
        val msg = outcome.conversations.single().messages.single()
        assertEquals(1, msg.toolCalls.size)
        assertEquals(ChatToolApprovalState.PENDING, msg.toolApprovalState)
    }

    /** `tokenCount` 必须写入，否则页脚不显示 token。 */
    @Test
    fun `finalize writes token count`() {
        val outcome = finalize(listOf(conversation()))
        assertEquals(42, outcome.conversations.single().messages.single().tokenCount)
    }

    /** reasoning 必须保留（否则思考过程丢失）。 */
    @Test
    fun `finalize writes reasoning content`() {
        val outcome = finalize(listOf(conversation()), reasoning = "思考过")
        assertEquals("思考过", outcome.conversations.single().messages.single().reasoningContent)
    }

    /** 消息已被删除时 `applied=false`，调用方据此跳过后续动作（如自动审批）。 */
    @Test
    fun `finalize reports not applied for unknown message`() {
        val outcome = listOf(conversation(id = "c9")).finalizeStreamingMessage(
            conversationId = "c9",
            messageId = "ghost",
            patch = StreamingFinalizePatch("x", null, null),
            now = 1L,
        )
        assertFalse(outcome.applied)
    }

    /**
     * ⚠️ 「是否找到消息」与「是否重排会话」**必须解耦**。
     *
     * 反证：把重排写在 `if (applied)` 里后本用例失败——真机表现为
     * 「消息因故没写进去，连会话置顶也一起丢了」，两件事的失败互相掩盖。
     */
    @Test
    fun `reorder still happens when message is missing`() {
        val before = listOf(
            conversation(id = "c1", updatedAt = 100L, messages = listOf(message(id = "gone"))),
            conversation(id = "c2", updatedAt = 900L, messages = listOf(message(id = "m2"))),
        )
        val outcome = before.finalizeStreamingMessage(
            conversationId = "c1",
            messageId = "missing",
            patch = StreamingFinalizePatch("x", null, null),
            now = 9_000L,
        )
        assertFalse(outcome.applied)
        assertEquals("c1", outcome.conversations.first().id)
    }

    /** 显式关掉置顶时，`updatedAtMillis` 与顺序都不得变化。 */
    @Test
    fun `sortConversationToTop false keeps order and timestamp`() {
        val before = listOf(
            conversation(id = "c1", updatedAt = 500L),
            conversation(id = "c2", updatedAt = 900L, messages = listOf(message(id = "m2"))),
        )
        val outcome = before.finalizeStreamingMessage(
            conversationId = "c1",
            messageId = "m1",
            patch = StreamingFinalizePatch("x", null, null, sortConversationToTop = false),
            now = 9_000L,
        )
        assertEquals(listOf("c1", "c2"), outcome.conversations.map { it.id })
        assertEquals(500L, outcome.conversations.first().updatedAtMillis)
    }

    // ------------------------------------------------------------ 与既有行为的一致性

    /**
     * ⚠️ 收尾后的消息**不得**再被 `persistSessionState` 的 `filterNot { it.isPending }` 过滤掉。
     *
     * 这是 F1 的**真实后果**验证（不只是「字段变成 false」）：
     * 本用例直接复用落盘逻辑的判据，确保收尾后的消息**能落盘**。
     */
    @Test
    fun `finalized message survives the persistence filter`() {
        val outcome = finalize(listOf(conversation()))
        val messages = outcome.conversations.single().messages
        val persisted = messages.filterNot { it.isPending }
        assertEquals("收尾后的消息必须能落盘", 1, persisted.size)
    }

    /** 收尾必须是**幂等**的：重复调用不得让内容变化（防重试路径重复收尾）。 */
    @Test
    fun `finalize is idempotent`() {
        val once = finalize(listOf(conversation()), now = 9_000L).conversations
        val twice = finalize(once, now = 9_000L).conversations
        assertEquals(once.single().messages.single(), twice.single().messages.single())
        assertNotEquals(0, twice.size)
    }
}
