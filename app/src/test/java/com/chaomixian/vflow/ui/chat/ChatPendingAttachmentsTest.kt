package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **待发附件预览条**的纯函数单测（fork：任务 45 —— 症状 (a)）。
 *
 * 覆盖两件事：
 * 1. **组装**（[chatPendingAttachmentPreviews]）：顺序、种类、key 的唯一性；
 * 2. **可见性判据**（[chatShouldShowPendingAttachmentBar]）：空状态**必须不可见** ——
 *    这是「没有附件时输入框上方不留白条」的锁。
 *
 * ⚠️ 断言只锚**本次新增**的表达式（这两个函数本次才存在），不锚改造前就有的标识符。
 */
class ChatPendingAttachmentsTest {

    // ------------------------------------------------------------ 1. 可见性判据

    @Test
    fun bar_isHiddenWhenNothingIsPending() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = emptyList(),
            hasCapturedPreview = false,
        )
        assertTrue("没有待发附件时列表必须是空的", previews.isEmpty())
        assertFalse(
            "空状态 ⇒ 预览条不可见（否则输入框上方会多出一条空留白）",
            chatShouldShowPendingAttachmentBar(previews),
        )
    }

    @Test
    fun bar_isVisibleWhenOnlyAPhotoIsPending() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = listOf("content://media/picked/1"),
            hasCapturedPreview = false,
        )
        assertTrue(
            "只选了一张相册图 ⇒ 预览条必须可见",
            chatShouldShowPendingAttachmentBar(previews),
        )
    }

    @Test
    fun bar_isVisibleWhenOnlyACameraShotIsPending() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = emptyList(),
            hasCapturedPreview = true,
        )
        assertTrue(
            "只拍了一张照 ⇒ 预览条必须可见",
            chatShouldShowPendingAttachmentBar(previews),
        )
    }

    // ------------------------------------------------------------ 2. 组装

    @Test
    fun previews_photosOnly_keepSelectionOrderAndAreAllPhotoKind() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = listOf("content://media/1", "file:///tmp/2.png"),
            hasCapturedPreview = false,
        )
        assertEquals("两项都要在", 2, previews.size)
        assertEquals(
            "相册项必须保持选择顺序（面板里的顺序 = 预览条里的顺序）",
            listOf("content://media/1", "file:///tmp/2.png"),
            previews.map { it.uri },
        )
        assertTrue(
            "相册来源全部是 PHOTO 档",
            previews.all { it.kind == ChatPendingAttachmentKind.PHOTO },
        )
    }

    @Test
    fun previews_cameraOnly_isASingleUriLessCameraItem() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = emptyList(),
            hasCapturedPreview = true,
        )
        assertEquals(1, previews.size)
        val only = previews.single()
        assertEquals(ChatPendingAttachmentKind.CAMERA, only.kind)
        assertNull("相机图**没有 uri**（只有 Bitmap），故 uri 必须为 null", only.uri)
        assertEquals("相机项的 key 是固定常量", CHAT_PENDING_CAMERA_KEY, only.key)
    }

    @Test
    fun previews_cameraComesFirst_soTheBarOrderMatchesTheSheetCarousel() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = listOf("content://media/7"),
            hasCapturedPreview = true,
        )
        assertEquals(
            "相机项在前 —— 与「+」面板 carousel 的 index 0 一致，两处排列不左右颠倒",
            listOf(ChatPendingAttachmentKind.CAMERA, ChatPendingAttachmentKind.PHOTO),
            previews.map { it.kind },
        )
    }

    @Test
    fun previews_keysAreUniqueEvenWhenAUriEqualsTheCameraKey() {
        val previews = chatPendingAttachmentPreviews(
            photoUris = listOf(CHAT_PENDING_CAMERA_KEY, "content://media/9"),
            hasCapturedPreview = true,
        )
        assertEquals(3, previews.size)
        assertEquals(
            "key 必须唯一（Compose 列表 key 与「点 × 该移除哪一项」都靠它）—— " +
                "相册项与相机项的前缀不同，故 uri 恰好等于常量值也不会撞",
            3,
            previews.map { it.key }.toSet().size,
        )
    }
}
