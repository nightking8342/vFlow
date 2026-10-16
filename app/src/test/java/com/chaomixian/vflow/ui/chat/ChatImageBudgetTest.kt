package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 每请求图片预算的**纯逻辑**（`ChatImageBudgetPlanner` + `ChatImagePlaceholder`）。
 *
 * 这一层不碰文件系统也不碰 Android —— 它只需要证明 D19 的两条硬约束：
 * 1. **按来源分档**且**两档之间没有合计上限**；
 * 2. 超预算的图**换占位文本**（不是静默丢弃），且落点就是原来那张图的位置。
 *
 * ## ⚠️ 必做反证
 *
 * 把 `ChatImageBudgetPlanner.plan` 里的裁剪分支去掉（全部 `Kept`）⇒
 * [sixUserImages_keepsLastFiveAndReplacesTheOldestWithPlaceholder] 与
 * [defaultBudget_hasNoCombinedCap_acrossSources] 的占位断言**变红**。
 */
class ChatImageBudgetTest {

    // ------------------------------------------------------------ 装置

    private fun attachment(
        id: String,
        source: ChatImageSource,
        label: String = "",
    ) = ChatImageAttachment(
        id = id,
        uri = "file:///tmp/$id.jpg",
        source = source,
        label = label,
    )

    private fun userMessage(id: String, vararg attachments: ChatImageAttachment) = ChatMessage(
        id = id,
        role = ChatMessageRole.USER,
        content = "look at these",
        timestampMillis = 1L,
        attachments = attachments.toList(),
    )

    // ------------------------------------------------------------ 默认预算

    @Test
    fun defaultBudget_hasNoCombinedCap_acrossSources() {
        // 默认 maxAgentImages = 1（父会话 2026-10-16 裁决），maxUserImages = 5。
        // 1 张 Agent 截图（用户消息承载）+ 5 张用户图 ⇒ **6 张全部保留**，
        // 证明分类上限之间**没有合计上限**（不是「一共 5 张」）。
        val history = listOf(
            userMessage("m1", attachment("a1", ChatImageSource.AGENT, "screenshot.png")),
            userMessage(
                "m2",
                attachment("u1", ChatImageSource.USER, "photo1.jpg"),
                attachment("u2", ChatImageSource.USER, "photo2.jpg"),
                attachment("u3", ChatImageSource.USER, "photo3.jpg"),
                attachment("u4", ChatImageSource.USER, "photo4.jpg"),
                attachment("u5", ChatImageSource.USER, "photo5.jpg"),
            ),
        )

        val decisions = ChatImageBudgetPlanner.plan(history, ChatImageBudget.DEFAULT)

        assertEquals(6, decisions.size)
        assertTrue(
            "默认预算下 1 张 Agent + 5 张用户应全部保留，实际：$decisions",
            decisions.all { it is ChatImageDecision.Keep },
        )
    }

    @Test
    fun configurableAgentBudget_keepsEightOfEight() {
        // 把 maxAgentImages 显式配成 3 ⇒ 3 + 5 = 8 张全部保留。
        // ⚠️ 这条同时锁住「取值可配」：若哪天有人把分档写死成常量，它会红。
        val history = listOf(
            userMessage("m1", attachment("a1", ChatImageSource.AGENT)),
            userMessage("m2", attachment("a2", ChatImageSource.AGENT)),
            userMessage("m3", attachment("a3", ChatImageSource.AGENT)),
            userMessage(
                "m4",
                attachment("u1", ChatImageSource.USER),
                attachment("u2", ChatImageSource.USER),
                attachment("u3", ChatImageSource.USER),
                attachment("u4", ChatImageSource.USER),
                attachment("u5", ChatImageSource.USER),
            ),
        )

        val decisions = ChatImageBudgetPlanner.plan(
            history,
            ChatImageBudget(maxUserImages = 5, maxAgentImages = 3),
        )

        assertEquals(8, decisions.size)
        assertTrue(
            "3 张 Agent + 5 张用户应全部保留，实际：$decisions",
            decisions.all { it is ChatImageDecision.Keep },
        )
    }

    // ------------------------------------------------------------ 用户图裁剪

    @Test
    fun sixUserImages_keepsLastFiveAndReplacesTheOldestWithPlaceholder() {
        val history = listOf(
            userMessage(
                "m1",
                attachment("u1", ChatImageSource.USER, "old.jpg"),
                attachment("u2", ChatImageSource.USER, "photo2.jpg"),
                attachment("u3", ChatImageSource.USER, "photo3.jpg"),
                attachment("u4", ChatImageSource.USER, "photo4.jpg"),
                attachment("u5", ChatImageSource.USER, "photo5.jpg"),
                attachment("u6", ChatImageSource.USER, "newest.jpg"),
            ),
        )

        val decisions = ChatImageBudgetPlanner.plan(history, ChatImageBudget.DEFAULT)

        assertEquals(6, decisions.size)
        // 落点必须是**原来那张图的位置**（oldest first 的第一个），不能挪到数组末尾——
        // 挪了会让模型把「这里曾有张图」的告知错配到别的图上。
        val first = decisions.first()
        assertTrue("最旧的那张应被换成占位，实际：$first", first is ChatImageDecision.Omit)
        first as ChatImageDecision.Omit
        assertEquals("u1", first.attachment.id)
        assertEquals(ChatImageOmissionReason.BUDGET, first.reason)
        assertTrue(
            "占位必须让模型知道「这里曾有张图」，实际：${first.placeholderText}",
            first.placeholderText.startsWith("[image omitted to fit request image limits;"),
        )
        assertTrue(
            "占位应带上附件可读名与 id，实际：${first.placeholderText}",
            first.placeholderText.contains("\"old.jpg\" (u1)"),
        )

        val kept = decisions.drop(1)
        assertTrue("其余 5 张应全部保留，实际：$kept", kept.all { it is ChatImageDecision.Keep })
        assertEquals(
            listOf("u2", "u3", "u4", "u5", "u6"),
            kept.map { it.attachment.id },
        )
    }

    // ------------------------------------------------------------ Agent 截图裁剪

    @Test
    fun fiveAgentScreenshots_keepsOnlyTheMostRecentOneByDefault() {
        // D19 默认 1（不是 3）：Agent 每轮都会重新截图，「当前屏幕」就是它需要的。
        val history = (1..5).map { index ->
            userMessage("m$index", attachment("a$index", ChatImageSource.AGENT, "screen$index.png"))
        }

        val decisions = ChatImageBudgetPlanner.plan(history, ChatImageBudget.DEFAULT)

        assertEquals(5, decisions.size)
        assertEquals(1, decisions.count { it is ChatImageDecision.Keep })
        assertEquals(4, decisions.count { it is ChatImageDecision.Omit })
        // 保留的必须是**最近**那张
        assertEquals("a5", decisions.last().attachment.id)
        assertTrue(decisions.last() is ChatImageDecision.Keep)
        assertTrue(decisions.take(4).all { it is ChatImageDecision.Omit })
    }

    @Test
    fun userBudgetDoesNotConsumeAgentBudget() {
        // 6 张用户图 + 1 张 Agent 截图：用户图被裁掉 1 张，Agent 截图**照常保留**。
        // 若两档共用一个计数器，Agent 那张会被用户图挤掉。
        val history = listOf(
            userMessage("m1", attachment("a1", ChatImageSource.AGENT, "screen.png")),
            userMessage(
                "m2",
                attachment("u1", ChatImageSource.USER),
                attachment("u2", ChatImageSource.USER),
                attachment("u3", ChatImageSource.USER),
                attachment("u4", ChatImageSource.USER),
                attachment("u5", ChatImageSource.USER),
                attachment("u6", ChatImageSource.USER),
            ),
        )

        val decisions = ChatImageBudgetPlanner.plan(history, ChatImageBudget.DEFAULT)
        val agentDecision = decisions.single { it.attachment.source == ChatImageSource.AGENT }

        assertTrue("Agent 截图不应被用户图挤掉，实际：$agentDecision", agentDecision is ChatImageDecision.Keep)
        assertEquals(1, decisions.count { it is ChatImageDecision.Omit })
    }

    // ------------------------------------------------------------ 边界与占位文案

    @Test
    fun noAttachments_producesEmptyPlan() {
        val decisions = ChatImageBudgetPlanner.plan(
            listOf(
                ChatMessage(id = "m1", role = ChatMessageRole.USER, content = "hi", timestampMillis = 1L),
            ),
            ChatImageBudget.DEFAULT,
        )
        assertTrue(decisions.isEmpty())
    }

    @Test
    fun unlimitedBudget_keepsEverything() {
        val history = (1..9).map { index ->
            userMessage("m$index", attachment("x$index", ChatImageSource.USER))
        }
        val decisions = ChatImageBudgetPlanner.plan(history, ChatImageBudget.UNLIMITED)
        assertTrue(decisions.all { it is ChatImageDecision.Keep })
    }

    @Test
    fun zeroBudget_omitsEverythingButStillExplains() {
        // 极端配置（0 张）不能退化成「静默丢弃」：每张都必须有占位文本。
        val history = listOf(userMessage("m1", attachment("u1", ChatImageSource.USER, "a.jpg")))
        val decisions = ChatImageBudgetPlanner.plan(
            history,
            ChatImageBudget(maxUserImages = 0, maxAgentImages = 0),
        )
        val only = decisions.single()
        assertTrue(only is ChatImageDecision.Omit)
        only as ChatImageDecision.Omit
        assertEquals(ChatImageOmissionReason.BUDGET, only.reason)
        assertTrue(only.placeholderText.isNotBlank())
    }

    @Test
    fun placeholderTextForEveryReason_isNonEmptyAndIdentifiesTheImage() {
        val attachment = ChatImageAttachment(id = "att-7", uri = "file:///tmp/a.jpg", label = "screenshot.png")

        val budget = ChatImagePlaceholder.text(ChatImageOmissionReason.BUDGET, attachment)
        val tooLarge = ChatImagePlaceholder.text(ChatImageOmissionReason.TOO_LARGE, attachment)
        val unreadable = ChatImagePlaceholder.text(ChatImageOmissionReason.UNREADABLE, attachment)

        listOf(budget, tooLarge, unreadable).forEach { text ->
            assertTrue("占位文案必须能被模型识别：$text", text.startsWith("[image omitted"))
            assertTrue("占位文案必须带上附件身份：$text", text.contains("\"screenshot.png\" (att-7)"))
            assertTrue("占位文案会进历史被每轮重发，必须短：$text", text.length < 200)
        }
        assertTrue("TOO_LARGE 必须点明 5 MB 上限：$tooLarge", tooLarge.contains("5 MB per-image limit"))
        assertTrue("BUDGET 必须说明图仍在历史里：$budget", budget.contains("still part of the conversation history"))
        assertFalse("三档文案不应互相重复", budget == tooLarge || tooLarge == unreadable)
    }

    @Test
    fun placeholderText_fallsBackWhenLabelIsBlank() {
        val attachment = ChatImageAttachment(id = "att-9", uri = "file:///tmp/a.jpg", label = "")
        val text = ChatImagePlaceholder.text(ChatImageOmissionReason.UNREADABLE, attachment)
        assertTrue("label 为空时应退化为 attachment <id>，实际：$text", text.contains("attachment att-9"))
    }
}
