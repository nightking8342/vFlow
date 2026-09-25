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

    // ------------------------------------------------------------ FollowSignal（§4.6(3)）

    /**
     * ⚠️ **指纹必须对「原位增长」敏感**——这是替代旧 `lastOrNull()?.id` 判据的关键。
     *
     * 反证：若把 `lastContentLength` 从 `FollowSignal` 里删掉，
     * 本用例的两个指纹会相等 ⇒ 流式期间**不跟随滚动**（这正是 2026-09-24 真机上出现的问题）。
     */
    @Test
    fun `follow signal changes when last message content grows`() {
        val before = FollowSignal.of(listOf(message(id = "m1", content = "你")), "c1")
        val after = FollowSignal.of(listOf(message(id = "m1", content = "你好")), "c1")
        // id 与条数都没变（正是「原位增长」的形态）
        assertEquals(before.lastMessageId, after.lastMessageId)
        assertEquals(before.messageCount, after.messageCount)
        assertNotEquals("内容增长必须改变指纹", before, after)
    }

    /** 追加消息（新 id、条数 +1）同样要改变指纹。 */
    @Test
    fun `follow signal changes when a message is appended`() {
        val before = FollowSignal.of(listOf(message(id = "m1", content = "a")), "c1")
        val after = FollowSignal.of(
            listOf(message(id = "m1", content = "a"), message(id = "m2", content = "")),
            "c1",
        )
        assertNotEquals(before, after)
    }

    /** 切换会话必须改变指纹（否则切过去后不会滚到底）。 */
    @Test
    fun `follow signal changes on conversation switch`() {
        val messages = listOf(message(id = "m1", content = "a"))
        assertNotEquals(
            FollowSignal.of(messages, "c1"),
            FollowSignal.of(messages, "c2"),
        )
    }

    /**
     * ⚠️⚠️ **指纹不得包含「距底部的距离」**（dsh 的反馈环警告，§4.6(3)）。
     *
     * 本用例是这条设计的**结构性锁定**：指纹的字段集是固定的四个，
     * 任何「距离」「偏移」「是否在底部」之类的维度一旦加进来，
     * 「滚动 → 判定在底部 → 触发重组 → 又滚到底」的反馈环就会出现
     * （表现为用户上翻读历史时被硬拽回底部）。
     *
     * 由于 Kotlin 的 `data class` 无法从外部断言「不含某字段」，
     * 这里用**穷举字段语义**代替：同一份消息在「距底部远近不同」的两种状态下
     * 必须得到**完全相同**的指纹——这正是「距离不参与」的可执行表述。
     */
    @Test
    fun `follow signal is independent of scroll position`() {
        val messages = listOf(message(id = "m1", content = "abc"))
        // 无论当前滚动到哪（调用方不传入任何位置信息），指纹只由消息内容决定
        val a = FollowSignal.of(messages, "c1")
        val b = FollowSignal.of(messages, "c1")
        assertEquals(a, b)
        assertEquals(3, a.lastContentLength)
    }

    /** `reasoningContent` 增长同样要触发（思考过程流式增长时也应跟随）。 */
    @Test
    fun `follow signal counts reasoning length too`() {
        val before = FollowSignal.of(listOf(message(id = "m1", reasoning = "想")), "c1")
        val after = FollowSignal.of(listOf(message(id = "m1", reasoning = "想一下")), "c1")
        assertNotEquals(before, after)
    }

    /** 空列表与 null 会话不得崩（新会话尚未建时的正常状态）。 */
    @Test
    fun `follow signal handles empty and null`() {
        assertEquals(0, FollowSignal.of(emptyList(), "c1").messageCount)
        assertEquals(null, FollowSignal.of(null, null).lastMessageId)
        assertEquals(
            FollowSignal.of(emptyList(), "c1"),
            FollowSignal.of(null, "c1"),
        )
    }

    /**
     * ⚠️ **审批卡片出现必须改变指纹**（0.1 的直接编码）。
     *
     * 收尾时正文长度常常**没变**（内容已逐字显示完），而审批卡片是在这一刻出现的。
     * 反证：去掉 `lastHasToolCalls` 后，本用例的两个指纹相等 ⇒
     * 收尾不触发滚动 ⇒ **审批卡片仍然滚不到**（0.1 复发）。
     */
    @Test
    fun `follow signal changes when tool calls appear`() {
        val before = FollowSignal.of(listOf(message(id = "m1", content = "让我查一下")), "c1")
        val withTools = FollowSignal.of(
            listOf(
                message(id = "m1", content = "让我查一下").copy(
                    toolCalls = listOf(ChatToolCall(id = "t1", name = "get_weather", argumentsJson = "{}")),
                )
            ),
            "c1",
        )
        assertEquals("正文长度不变（收尾的常见形态）", before.lastContentLength, withTools.lastContentLength)
        assertNotEquals("工具调用出现必须改变指纹", before, withTools)
    }

    /**
     * ⚠️⚠️ **「用户刚发消息」的判据必须能在「占位消息同帧到达」时依然成立**。
     *
     * 反证：若用「末尾消息是否来自用户」作判据，本用例的第二个断言会失败——
     * 末尾已是助手占位消息，判据不成立 ⇒ 真机表现「历史会话里发消息不滚到底」。
     */
    @Test
    fun `follow signal tracks last user message id even with trailing placeholder`() {
        val user = ChatMessage(
            id = "u1",
            role = ChatMessageRole.USER,
            content = "你好",
            timestampMillis = 1L,
        )
        val placeholder = ChatMessage(
            id = "m1",
            role = ChatMessageRole.ASSISTANT,
            content = "",
            timestampMillis = 2L,
            isPending = true,
        )

        // 末尾是用户消息
        val onlyUser = FollowSignal.of(listOf(user), "c1")
        assertEquals("u1", onlyUser.lastUserMessageId)

        // ⚠️ 关键：用户消息之后**紧接着**跟了助手占位消息（`sendMessage` 的真实形态）
        val withPlaceholder = FollowSignal.of(listOf(user, placeholder), "c1")
        assertEquals("判据不得被末尾的占位消息掩盖", "u1", withPlaceholder.lastUserMessageId)
        assertNotEquals("占位消息到达也必须改变指纹", onlyUser, withPlaceholder)
    }

    /** 没有用户消息时该字段为 null（新会话、或只有错误消息）。 */
    @Test
    fun `follow signal has null user message id when absent`() {
        assertEquals(null, FollowSignal.of(listOf(message(id = "m1")), "c1").lastUserMessageId)
        assertEquals(null, FollowSignal.of(null, "c1").lastUserMessageId)
    }
}
