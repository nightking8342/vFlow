package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatConversationExportTest {

    private fun conversation(
        title: String,
        id: String = "11111111-2222-3333-4444-555555555555",
        updatedAtMillis: Long = 1_770_000_000_000L,
    ) = ChatConversation(
        id = id,
        title = title,
        createdAtMillis = updatedAtMillis,
        updatedAtMillis = updatedAtMillis,
        messages = listOf(
            ChatMessage(
                id = "msg-user",
                role = ChatMessageRole.USER,
                content = "帮我生成一个签到工作流",
                timestampMillis = updatedAtMillis,
            ),
        ),
    )

    @Test
    fun exportPayload_preservesToolCallAndResultDetail() {
        val conversation = conversation("带工具的会话").let { base ->
            base.copy(
                messages = base.messages + ChatMessage(
                    id = "msg-assistant",
                    role = ChatMessageRole.ASSISTANT,
                    content = "",
                    timestampMillis = 1_770_000_001_000L,
                    toolCalls = listOf(
                        ChatToolCall(
                            id = "call-1",
                            name = "workflow_query_modules",
                            argumentsJson = """{"query":"签到"}""",
                        )
                    ),
                    toolApprovalState = ChatToolApprovalState.APPROVED,
                    toolResult = ChatToolResult(
                        callId = "call-1",
                        name = "workflow_query_modules",
                        status = ChatToolResultStatus.SUCCESS,
                        summary = "命中 3 个模块",
                        outputText = """{"modules":["a","b","c"]}""",
                    ),
                ),
            )
        }

        val json = ChatConversationExportManager.encodeExportPayload(
            ChatConversationExportManager.buildExportPayload(
                conversation = conversation,
                packageName = "com.chaomixian.vflow",
                versionName = "1.5.3-pr1",
                versionCode = 1L,
            )
        )

        // 分析所需的关键字段必须全部保真，不能被裁剪
        assertTrue(json.contains("\"argumentsJson\""))
        assertTrue(json.contains("\"outputText\""))
        assertTrue(json.contains("\"workflow_query_modules\""))
        assertTrue(json.contains("\"APPROVED\""))
        assertTrue(json.contains("命中 3 个模块"))
        assertTrue(json.contains("\"exportVersion\": 1"))
        assertTrue(json.contains("\"appVersionName\": \"1.5.3-pr1\""))
    }

    @Test
    fun exportFileName_slugifiesAsciiTitle() {
        val name = ChatConversationExportManager.buildExportFileName(
            conversation("Debug Workflow Run #2", updatedAtMillis = 1_770_000_000_000L)
        )

        assertTrue(name.startsWith("vflow-chat-debug-workflow-run-2-"))
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun exportFileName_fallsBackToIdForNonAsciiTitle() {
        val name = ChatConversationExportManager.buildExportFileName(
            conversation("纯中文标题没有任何字母", id = "abcdef12-3456-7890-abcd-ef1234567890")
        )

        assertTrue(name.startsWith("vflow-chat-abcdef12-"))
        assertTrue(name.endsWith(".json"))
    }

    @Test
    fun exportFileName_isStableForSameConversation() {
        val conversation = conversation("Stable Title")

        assertEquals(
            ChatConversationExportManager.buildExportFileName(conversation),
            ChatConversationExportManager.buildExportFileName(conversation),
        )
    }

    @Test
    fun shareTitle_includesConversationTitle() {
        val title = ChatConversationExportManager.buildShareTitle(conversation("签到排查"))

        assertTrue(title.contains("签到排查"))
        assertFalse(title.isBlank())
    }
}
