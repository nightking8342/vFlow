package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 折叠态摘要推导的单测。
 *
 * 重点保障设计文档 §1.1 的核心诉求：
 * **窄条显示的是 AI 说的话，不是状态灯**。
 */
class ChatFloatSummaryTest {

    private fun assistant(content: String) = ChatMessage(
        role = ChatMessageRole.ASSISTANT,
        content = content,
        timestampMillis = 1L,
    )

    private fun user(content: String) = ChatMessage(
        role = ChatMessageRole.USER,
        content = content,
        timestampMillis = 0L,
    )

    private fun error(content: String) = ChatMessage(
        role = ChatMessageRole.ERROR,
        content = content,
        timestampMillis = 2L,
    )

    private fun derive(
        messages: List<ChatMessage>,
        isAgentRunning: Boolean = false,
        hasPendingApproval: Boolean = false,
    ) = ChatFloatSummary.derive(
        messages = messages,
        isAgentRunning = isAgentRunning,
        hasPendingApproval = hasPendingApproval,
        runningHintText = "正在观察…",
        errorFallback = "准备就绪",
    )

    @Test
    fun `空闲时显示最后一条助手回复`() {
        val result = derive(
            listOf(user("打开深色模式"), assistant("深色模式已经打开了。"))
        )
        assertEquals("深色模式已经打开了。", result.text)
        assertEquals(ChatFloatSummary.Tone.NORMAL, result.tone)
    }

    @Test
    fun `运行中显示最新助手文本而不是状态动词`() {
        // 这是本需求的核心：AI 的过程说明必须可见
        val result = derive(
            listOf(assistant("当前在设置首页，没直接看到深色模式。我去「显示」里找。")),
            isAgentRunning = true,
        )
        assertEquals("当前在设置首页，没直接看到深色模式。我去「显示」里找。", result.text)
        assertEquals(ChatFloatSummary.Tone.RUNNING, result.tone)
    }

    @Test
    fun `运行中但无助手文本时才退化为状态动词`() {
        val result = derive(listOf(user("打开深色模式")), isAgentRunning = true)
        assertEquals("正在观察…", result.text)
        assertEquals(ChatFloatSummary.Tone.RUNNING, result.tone)
    }

    @Test
    fun `多行文本被压缩为单行`() {
        val result = derive(listOf(assistant("第一行\n第二行\n  第三行  ")))
        assertEquals("第一行 第二行 第三行", result.text)
    }

    @Test
    fun `待审批优先展示最近助手文本并标记 ATTENTION`() {
        val result = derive(
            listOf(assistant("找到了开关，我来打开它。")),
            hasPendingApproval = true,
        )
        assertEquals("找到了开关，我来打开它。", result.text)
        assertEquals(ChatFloatSummary.Tone.ATTENTION, result.tone)
    }

    @Test
    fun `待审批但无助手文本时退回提示`() {
        val result = derive(
            listOf(user("打开深色模式")),
            hasPendingApproval = true,
        )
        assertEquals("正在观察…", result.text)
        assertEquals(ChatFloatSummary.Tone.ATTENTION, result.tone)
    }

    @Test
    fun `错误消息优先于更早的助手文本`() {
        val result = derive(
            listOf(assistant("好的，我来处理。"), error("请求失败，请检查当前模型配置。"))
        )
        assertEquals("请求失败，请检查当前模型配置。", result.text)
        assertEquals(ChatFloatSummary.Tone.ERROR, result.tone)
    }

    @Test
    fun `空会话显示兜底文案`() {
        val result = derive(emptyList())
        assertEquals("准备就绪", result.text)
    }

    @Test
    fun `空白助手文本被忽略`() {
        val result = derive(
            listOf(assistant("有内容"), assistant("   \n  ")),
            isAgentRunning = true,
        )
        assertEquals("有内容", result.text)
        assertTrue(result.text.isNotBlank())
    }
}
