package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具产出的图 → AGENT 档附件（纯）。
 *
 * ## ⚠️ 必做反证
 *
 * 把 [chatAgentToolImageAttachments] 改成 `return emptyList()`（模拟「截图没接线」）⇒
 * [imageArtifacts_becomeAgentSourceAttachments] **变红**。
 */
class ChatAgentToolImagesTest {

    private fun toolResult(vararg artifacts: ChatArtifactReference) = ChatToolResult(
        callId = "call_1",
        name = "vflow_agent_observe_ui",
        status = ChatToolResultStatus.SUCCESS,
        summary = "observe",
        outputText = "ok",
        artifacts = artifacts.toList(),
    )

    private fun reference(key: String, typeLabel: String, callId: String = "call_1") =
        ChatArtifactReference(
            key = key,
            handle = "artifact://$callId/$key",
            typeLabel = typeLabel,
        )

    @Test
    fun imageArtifacts_becomeAgentSourceAttachments() {
        val attachments = chatAgentToolImageAttachments(
            toolResults = listOf(
                toolResult(
                    reference("element_01", "screen element"),
                    reference("snapshot", "ui snapshot"),
                    reference("screenshot", "image"),
                )
            ),
            imageUriForHandle = { handle ->
                if (handle == "artifact://call_1/screenshot") "file:///tmp/screenshot.png" else null
            },
        )

        assertEquals("只有 image 类 artifact 才变成附件", 1, attachments.size)
        val attachment = attachments.single()
        assertEquals(ChatImageSource.AGENT, attachment.source)
        assertEquals("file:///tmp/screenshot.png", attachment.uri)
        assertEquals("label 必须是句柄（占位文案要靠它指认）", "artifact://call_1/screenshot", attachment.label)
        assertEquals("mime 必须由扩展名推断，不能用默认的 image/jpeg", "image/png", attachment.mimeType)
    }

    @Test
    fun unresolvableHandle_isSkippedWithoutThrowing() {
        val attachments = chatAgentToolImageAttachments(
            toolResults = listOf(
                toolResult(reference("screenshot", "image")),
                toolResult(reference("screenshot", "image", callId = "call_2")),
            ),
            imageUriForHandle = { handle ->
                if (handle == "artifact://call_2/screenshot") "file:///tmp/b.jpg" else null
            },
        )

        assertEquals("解析不到的句柄跳过、不抛", 1, attachments.size)
        assertEquals("artifact://call_2/screenshot", attachments.single().label)
    }

    @Test
    fun blankUri_isSkipped() {
        val attachments = chatAgentToolImageAttachments(
            toolResults = listOf(toolResult(reference("screenshot", "image"))),
            imageUriForHandle = { "" },
        )

        assertTrue("空 uri 不得变成一条读不出字节的附件", attachments.isEmpty())
    }

    @Test
    fun nonImageArtifacts_produceNothing() {
        val attachments = chatAgentToolImageAttachments(
            toolResults = listOf(
                toolResult(
                    reference("element_01", "screen element"),
                    reference("point", "coordinate"),
                )
            ),
            imageUriForHandle = { "file:///tmp/never.png" },
        )

        assertTrue(attachments.isEmpty())
    }

    @Test
    fun mimeType_isInferredFromTheExtension() {
        val logged = mutableListOf<String>()

        assertEquals("image/png", chatImageMimeTypeForUri("file:///tmp/a.png"))
        assertEquals("image/png", chatImageMimeTypeForUri("/data/user/0/x/screenshot.PNG"))
        assertEquals("image/jpeg", chatImageMimeTypeForUri("file:///tmp/a.jpg"))
        assertEquals("image/jpeg", chatImageMimeTypeForUri("file:///tmp/a.jpeg"))
        assertEquals("image/webp", chatImageMimeTypeForUri("content://x/a.webp"))
        assertEquals("image/gif", chatImageMimeTypeForUri("file:///tmp/a.gif"))
        assertEquals(
            "query 不得参与扩展名判定",
            "image/png",
            chatImageMimeTypeForUri("file:///tmp/a.png?v=2"),
        )
        assertEquals(
            "未知扩展名 ⇒ jpeg 兜底（并记 W）",
            "image/jpeg",
            chatImageMimeTypeForUri("/tmp/no-extension", log = { logged += it }),
        )
        assertTrue("未知扩展名必须留下 W 日志，不能静默", logged.size == 1)
    }
}
