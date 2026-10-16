package com.chaomixian.vflow.ui.chat

import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `ChatImageResolver` 的**判定与编排**（D19 的「默认不缩」+ 单图 5MB 兜底）。
 *
 * ⚠️ 这里**不测** `AndroidChatImageShrinker` 的重编码本身——它依赖 `android.graphics`，
 * 单测环境是 not-mocked 桩。本文件用注入的 fake shrinker 只证明**编排**：
 * 什么时候调它、它失败时怎么退化。
 *
 * 上限一律注入一个小值（`maxBase64Chars = 16`）而不是真的 5MB：
 * 5MB 的 base64 需要 ~3.75MB 的测试数据，跑起来慢且没有额外信息量
 * （判据是「字符串长度 ≤ 上限」，与上限具体多大无关）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 删掉 [ChatImageResolver.resolveAttachment] 里的 `encoded.length <= maxBase64Chars` 判定
 *    （直接返回 Kept）⇒ [underLimit_isKeptWithoutCallingTheShrinker] **变红**
 *    （该用例断言 `shrinker.calls == 0`）。
 * 2. 无条件调用 shrinker ⇒ 同上断言**变红**。
 */
class ChatImageResolverTest {

    // ------------------------------------------------------------ fakes

    private class RecordingByteSource(
        private val bytesByUri: Map<String, ByteArray>,
    ) : ChatImageByteSource {
        val requestedUris = mutableListOf<String>()

        override fun readBytes(attachment: ChatImageAttachment): ByteArray? {
            requestedUris += attachment.uri
            return bytesByUri[attachment.uri]
        }
    }

    private class RecordingShrinker(private val result: String?) : ChatImageShrinker {
        var calls = 0
        var lastMaxBase64Chars: Int? = null

        override fun shrinkToBase64(
            attachment: ChatImageAttachment,
            bytes: ByteArray,
            maxBase64Chars: Int,
        ): String? {
            calls++
            lastMaxBase64Chars = maxBase64Chars
            return result
        }
    }

    private val maxBase64Chars = 16

    private fun attachment(
        id: String = "att-1",
        uri: String = "file:///tmp/$id.png",
        mimeType: String = "image/png",
        source: ChatImageSource = ChatImageSource.USER,
        label: String = "shot.png",
    ) = ChatImageAttachment(
        id = id,
        uri = uri,
        source = source,
        mimeType = mimeType,
        label = label,
    )

    private fun resolver(
        byteSource: ChatImageByteSource,
        shrinker: ChatImageShrinker,
    ) = ChatImageResolver(
        byteSource = byteSource,
        shrinker = shrinker,
        maxBase64Chars = maxBase64Chars,
    )

    /** 12 字节 ⇒ base64 恰好 16 字符（`4 * ceil(12/3)`）⇒ **恰好等于上限**。 */
    private val exactlyAtLimitBytes = ByteArray(12) { 0x41 }

    /** 30 字节 ⇒ base64 40 字符 > 上限 16。 */
    private val overLimitBytes = ByteArray(30) { 0x42 }

    // ------------------------------------------------------------ 默认不缩

    @Test
    fun underLimit_isKeptWithoutCallingTheShrinker() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to ByteArray(3) { 0x41 }))
        val shrinker = RecordingShrinker(result = "SHOULD_NOT_BE_USED")

        val planned = resolver(byteSource, shrinker).resolveAttachment(attachment())

        val kept = planned as ChatPlannedImage.Kept
        assertEquals("image/png", kept.payload.mimeType)
        assertEquals(
            Base64.getEncoder().encodeToString(ByteArray(3) { 0x41 }),
            kept.payload.base64,
        )
        assertEquals("正常路径**不得**触碰缩压（D19 默认不缩）", 0, shrinker.calls)
    }

    @Test
    fun exactlyAtLimit_isKeptWithoutShrinking() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to exactlyAtLimitBytes))
        val shrinker = RecordingShrinker(result = "SHOULD_NOT_BE_USED")

        val planned = resolver(byteSource, shrinker).resolveAttachment(attachment())

        val kept = planned as ChatPlannedImage.Kept
        assertEquals(maxBase64Chars, kept.payload.base64.length)
        assertEquals("恰好等于上限**不缩**", 0, shrinker.calls)
    }

    // ------------------------------------------------------------ 5MB 兜底

    @Test
    fun overLimit_callsShrinkerAndUsesItsResult() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to overLimitBytes))
        val shrinker = RecordingShrinker(result = "SHRUNK")

        val planned = resolver(byteSource, shrinker).resolveAttachment(attachment())

        val kept = planned as ChatPlannedImage.Kept
        assertEquals(1, shrinker.calls)
        assertEquals("重编码后统一按 JPEG 发送", "image/jpeg", kept.payload.mimeType)
        assertEquals("SHRUNK", kept.payload.base64)
        assertEquals("缩压上限必须原样传给 shrinker", maxBase64Chars, shrinker.lastMaxBase64Chars)
    }

    @Test
    fun overLimitAndShrinkerFails_isOmittedAsTooLarge() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to overLimitBytes))
        val shrinker = RecordingShrinker(result = null)

        val planned = resolver(byteSource, shrinker).resolveAttachment(attachment())

        val omitted = planned as ChatPlannedImage.Omitted
        assertEquals(ChatImageOmissionReason.TOO_LARGE, omitted.reason)
        assertTrue(omitted.placeholderText.contains("5 MB per-image limit"))
        assertTrue(
            "占位必须带上附件身份：${omitted.placeholderText}",
            omitted.placeholderText.contains("\"shot.png\" (att-1)"),
        )
    }

    // ------------------------------------------------------------ 读不到

    @Test
    fun missingBytes_isOmittedAsUnreadable() {
        val byteSource = RecordingByteSource(emptyMap())
        val shrinker = RecordingShrinker(result = "SHRUNK")

        val planned = resolver(byteSource, shrinker).resolveAttachment(attachment())

        val omitted = planned as ChatPlannedImage.Omitted
        assertEquals(ChatImageOmissionReason.UNREADABLE, omitted.reason)
        assertTrue(omitted.placeholderText.startsWith("[image omitted: the image could not be read;"))
        assertEquals("读不到字节时**不该**再尝试缩压", 0, shrinker.calls)
    }

    // ------------------------------------------------------------ 白名单外的 mime

    @Test
    fun nonWhitelistedMime_isReEncodedAsJpeg() {
        // image/heic 是相册常见格式，三协议都不认 ⇒ 必须先重编码（有损，已知限制 2）
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to ByteArray(3) { 0x41 }))
        val shrinker = RecordingShrinker(result = "JPEG")

        val planned = resolver(byteSource, shrinker)
            .resolveAttachment(attachment(mimeType = "image/heic"))

        val kept = planned as ChatPlannedImage.Kept
        assertEquals("mime 不在白名单 ⇒ 必须走 shrinker（即便字节没超限）", 1, shrinker.calls)
        assertEquals("image/jpeg", kept.payload.mimeType)
        assertEquals("JPEG", kept.payload.base64)
    }

    @Test
    fun nonWhitelistedMimeAndShrinkerFails_isOmittedAsUnreadable() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to ByteArray(3) { 0x41 }))
        val shrinker = RecordingShrinker(result = null)

        val planned = resolver(byteSource, shrinker)
            .resolveAttachment(attachment(mimeType = "image/heic"))

        val omitted = planned as ChatPlannedImage.Omitted
        assertEquals(ChatImageOmissionReason.UNREADABLE, omitted.reason)
    }

    @Test
    fun mimeParameters_doNotBreakTheWhitelist() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/att-1.png" to ByteArray(3) { 0x41 }))
        val shrinker = RecordingShrinker(result = "SHOULD_NOT_BE_USED")

        val planned = resolver(byteSource, shrinker)
            .resolveAttachment(attachment(mimeType = "image/png; charset=binary"))

        val kept = planned as ChatPlannedImage.Kept
        assertEquals("image/png", kept.payload.mimeType)
        assertEquals(0, shrinker.calls)
    }

    // ------------------------------------------------------------ 计划编排

    @Test
    fun plan_onlyReadsBytesForKeptImages() {
        // 先裁剪再读字节：被裁掉的那张**完全不碰文件系统**。
        val byteSource = RecordingByteSource(
            mapOf(
                "file:///tmp/u1.png" to ByteArray(3) { 0x41 },
                "file:///tmp/u2.png" to ByteArray(3) { 0x42 },
            ),
        )
        val shrinker = RecordingShrinker(result = null)
        val history = listOf(
            ChatMessage(
                id = "m1",
                role = ChatMessageRole.USER,
                content = "two images",
                timestampMillis = 1L,
                attachments = listOf(
                    attachment(id = "u1", uri = "file:///tmp/u1.png"),
                    attachment(id = "u2", uri = "file:///tmp/u2.png"),
                ),
            ),
        )

        val plan = resolver(byteSource, shrinker).plan(history, ChatImageBudget(maxUserImages = 1))

        assertEquals(
            "只有被保留的那张才该读字节，实际读了：${byteSource.requestedUris}",
            listOf("file:///tmp/u2.png"),
            byteSource.requestedUris,
        )
        assertEquals(1, plan.keptCount())
        assertEquals(1, plan.omittedCount(ChatImageOmissionReason.BUDGET))
        val images = plan.forMessage("m1")
        assertEquals(2, images.size)
        assertTrue("占位必须落在被裁的那张的原位", images[0] is ChatPlannedImage.Omitted)
        assertTrue(images[1] is ChatPlannedImage.Kept)
    }

    @Test
    fun plan_withoutAttachments_isEmpty() {
        val plan = resolver(RecordingByteSource(emptyMap()), RecordingShrinker(result = null))
            .plan(
                listOf(ChatMessage(id = "m1", role = ChatMessageRole.USER, content = "hi", timestampMillis = 1L)),
                ChatImageBudget.DEFAULT,
            )

        assertTrue(plan.isEmpty())
        assertTrue(plan.forMessage("m1").isEmpty())
        assertEquals(0, plan.keptCount())
    }

    @Test
    fun plan_omittedBudgetImageDoesNotTouchTheByteSource() {
        val byteSource = RecordingByteSource(mapOf("file:///tmp/u1.png" to ByteArray(3) { 0x41 }))
        val history = listOf(
            ChatMessage(
                id = "m1",
                role = ChatMessageRole.USER,
                content = "one image",
                timestampMillis = 1L,
                attachments = listOf(attachment(id = "u1", uri = "file:///tmp/u1.png")),
            ),
        )

        val plan = resolver(byteSource, RecordingShrinker(result = null))
            .plan(history, ChatImageBudget(maxUserImages = 0))

        assertTrue(byteSource.requestedUris.isEmpty())
        assertEquals(0, plan.keptCount())
        assertEquals(1, plan.omittedCount(ChatImageOmissionReason.BUDGET))
        assertFalse(plan.isEmpty())
    }
}
