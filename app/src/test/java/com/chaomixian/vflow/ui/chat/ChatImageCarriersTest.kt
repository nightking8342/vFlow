package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「可承载图片的角色」的**唯一口径**（fork：AI 能力统一 §3.1 末段）。
 *
 * ## ⚠️ 必做反证
 *
 * 把 [ChatImageCarriers.normalizeForRequest] 改成 `return history`（模拟「没接线」）⇒
 * [nonCarrierAttachments_areStripped] 与 [droppedAttachmentCount_countsOnlyNonCarriers] **变红**。
 */
class ChatImageCarriersTest {

    private fun attachment(id: String = "a1", source: ChatImageSource = ChatImageSource.USER) =
        ChatImageAttachment(
            id = id,
            uri = "file:///tmp/$id.png",
            source = source,
            mimeType = "image/png",
        )

    private fun message(
        id: String,
        role: ChatMessageRole,
        content: String = "text",
        attachments: List<ChatImageAttachment> = emptyList(),
        reasoningContent: String? = null,
    ) = ChatMessage(
        id = id,
        role = role,
        content = content,
        timestampMillis = 1L,
        reasoningContent = reasoningContent,
        attachments = attachments,
    )

    @Test
    fun carrierRoles_isExactlyUser() {
        assertEquals(
            "三协议的交集只有 user；改这里等于改装配语义",
            setOf(ChatMessageRole.USER),
            ChatImageCarriers.CARRIER_ROLES,
        )
        assertTrue(ChatImageCarriers.canCarryImages(ChatMessageRole.USER))
        assertTrue(!ChatImageCarriers.canCarryImages(ChatMessageRole.ASSISTANT))
        assertTrue(!ChatImageCarriers.canCarryImages(ChatMessageRole.TOOL))
        assertTrue(!ChatImageCarriers.canCarryImages(ChatMessageRole.ERROR))
    }

    @Test
    fun nonCarrierAttachments_areStripped() {
        val offender = message(
            id = "m1",
            role = ChatMessageRole.ASSISTANT,
            content = "assistant text",
            attachments = listOf(attachment()),
            reasoningContent = "why",
        )

        val normalized = ChatImageCarriers.normalizeForRequest(listOf(offender))

        assertEquals(1, normalized.size)
        assertTrue(
            "挂在不可承载角色上的附件必须被剥掉（否则会静默吃掉预算却不被发送）",
            normalized[0].attachments.isEmpty(),
        )
        assertEquals(
            "剥离只动 attachments，其余字段逐字段不变",
            offender.copy(attachments = emptyList()),
            normalized[0],
        )
        assertEquals(1, ChatImageCarriers.droppedAttachmentCount(listOf(offender)))
    }

    @Test
    fun userAttachments_areKeptAsTheSameInstance() {
        val userMessage = message(
            id = "m1",
            role = ChatMessageRole.USER,
            attachments = listOf(attachment()),
        )

        val normalized = ChatImageCarriers.normalizeForRequest(listOf(userMessage))

        assertSame("USER 消息不得被复制", userMessage, normalized.single())
        assertEquals(0, ChatImageCarriers.droppedAttachmentCount(listOf(userMessage)))
    }

    @Test
    fun cleanHistory_isReturnedWithoutCopying() {
        val history = listOf(
            message("m1", ChatMessageRole.USER),
            message("m2", ChatMessageRole.ASSISTANT),
            message("m3", ChatMessageRole.TOOL),
        )

        val normalized = ChatImageCarriers.normalizeForRequest(history)

        assertEquals(3, normalized.size)
        history.forEachIndexed { index, original ->
            assertSame("无违规附件时不得产生任何拷贝", original, normalized[index])
        }
        assertEquals(0, ChatImageCarriers.droppedAttachmentCount(history))
    }

    @Test
    fun droppedAttachmentCount_countsOnlyNonCarriers() {
        val history = listOf(
            message("m1", ChatMessageRole.USER, attachments = listOf(attachment("u1"))),
            message("m2", ChatMessageRole.TOOL, attachments = listOf(attachment("t1"), attachment("t2"))),
            message("m3", ChatMessageRole.USER, attachments = listOf(attachment("u2", ChatImageSource.AGENT))),
        )

        assertEquals(
            "计数只算不可承载角色；USER 上的 AGENT 截图（本方案的截图承载方式）不算",
            2,
            ChatImageCarriers.droppedAttachmentCount(history),
        )
    }
}
