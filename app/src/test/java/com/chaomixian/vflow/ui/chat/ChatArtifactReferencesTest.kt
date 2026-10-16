package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatArtifactReferences` 的**纯解析**（fork：AI 能力统一 §3.9「引用即保存」的入口）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 [ChatArtifactReferences.HANDLE_SCHEME] 改成 `"artifact:"`（少一个斜杠）⇒
 *    [referencedHandles_keepsOrderAndDeduplicates] **变红**；
 * 2. 把正则放宽到「任意 URL」（如 `\((https?://[^\s)]+)\)`）⇒
 *    [nonArtifactImageSyntax_staysInTheTextSegment] **变红**。
 */
class ChatArtifactReferencesTest {

    @Test
    fun emptyBody_hasNoSegments() {
        assertEquals(emptyList<ChatBodySegment>(), ChatArtifactReferences.parse(""))
    }

    @Test
    fun bodyWithoutReferences_isASingleTextSegment() {
        val segments = ChatArtifactReferences.parse("hello **world**")
        assertEquals(1, segments.size)
        assertEquals(ChatBodySegment.Text("hello **world**"), segments[0])
    }

    @Test
    fun oneReference_isSurroundedByItsTextSegmentsInOrder() {
        val body = "before\n![shot](artifact://call_1/screenshot)\nafter"
        val segments = ChatArtifactReferences.parse(body)

        assertEquals("段序必须是 文本 → 图 → 文本", 3, segments.size)
        assertEquals(ChatBodySegment.Text("before\n"), segments[0])
        val image = segments[1] as ChatBodySegment.Image
        assertEquals("artifact://call_1/screenshot", image.reference.handle)
        assertEquals("shot", image.reference.caption)
        assertEquals("\nafter", (segments[2] as ChatBodySegment.Text).markdown)

        // 下标必须能切回原文（落盘/渲染两侧都依赖它）
        assertEquals(
            "![shot](artifact://call_1/screenshot)",
            body.substring(image.reference.startIndex, image.reference.endIndex),
        )
    }

    @Test
    fun multipleReferences_keepOrderAndSplitEveryGap() {
        val body = "a![one](artifact://c/1)b![two](artifact://c/2)c"
        val segments = ChatArtifactReferences.parse(body)

        assertEquals(5, segments.size)
        assertEquals(ChatBodySegment.Text("a"), segments[0])
        assertEquals(
            "artifact://c/1",
            (segments[1] as ChatBodySegment.Image).reference.handle,
        )
        assertEquals(ChatBodySegment.Text("b"), segments[2])
        assertEquals(
            "artifact://c/2",
            (segments[3] as ChatBodySegment.Image).reference.handle,
        )
        assertEquals(ChatBodySegment.Text("c"), segments[4])
    }

    @Test
    fun referenceWithTitle_andEmptyCaption_areParsed() {
        val withTitle = ChatArtifactReferences.parse("""![cap](artifact://c/1 "title")""")
        assertEquals(1, withTitle.size)
        assertEquals(
            "artifact://c/1",
            (withTitle[0] as ChatBodySegment.Image).reference.handle,
        )

        val emptyCaption = ChatArtifactReferences.parse("![](artifact://c/1)")
        assertEquals(1, emptyCaption.size)
        assertEquals("", (emptyCaption[0] as ChatBodySegment.Image).reference.caption)
    }

    @Test
    fun nonArtifactImageSyntax_staysInTheTextSegment() {
        // 只有 `artifact://` 句柄是会话内的临时产物；http/file/data 必须原样交给 markdown。
        val body = "![remote](https://example.com/a.png) and ![local](file:///tmp/b.png)"
        val segments = ChatArtifactReferences.parse(body)

        assertEquals("非 artifact 链接不得被当成引用切段", 1, segments.size)
        assertEquals(ChatBodySegment.Text(body), segments[0])
        assertEquals(emptyList<String>(), ChatArtifactReferences.referencedHandles(body))
    }

    @Test
    fun referencedHandles_keepsOrderAndDeduplicates() {
        val body = """
            ![a](artifact://c/2)
            ![b](artifact://c/1)
            ![a again](artifact://c/2)
        """.trimIndent()

        assertEquals(
            "去重且保持出现顺序（落盘顺序要稳定）",
            listOf("artifact://c/2", "artifact://c/1"),
            ChatArtifactReferences.referencedHandles(body),
        )
    }

    @Test
    fun handleScheme_isTheArtifactPrefix() {
        assertTrue(
            "句柄前缀必须与 ChatAgentArtifactStore 生成的形态逐字一致",
            ChatArtifactReferences.HANDLE_SCHEME == "artifact://",
        )
    }
}
