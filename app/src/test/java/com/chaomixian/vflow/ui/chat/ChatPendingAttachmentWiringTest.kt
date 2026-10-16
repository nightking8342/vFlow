package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线测试**（fork：任务 45）。
 *
 * 治的是本仓库反复踩的那类病：**纯函数全绿 ≠ 集成点接上了**。本任务的两个能力
 * （待发附件预览条 / 点图放大）各自都有「纯函数或独立组件」与「调用点」之分，
 * 故必须显式锁住调用点。
 *
 * ## ⚠️ 断言只锚**本次新增**的表达式
 *
 * `chatPendingAttachmentPreviews(` / `chatShouldShowPendingAttachmentBar(` /
 * `ChatPendingAttachmentBar(` / `ChatImageViewer(` / `ChatImagePreviewRequest(` /
 * `imageClickModifier(` / `onImageClick = ` 都是本次**新加**的；锚在改造前就存在的
 * 标识符（如 `selectedPhotoUris`）上会**恒真**。
 *
 * ## ⚠️ 扫描前必须剥注释
 *
 * 本次在 `ChatScreen.kt` / `ChatInlineImages.kt` 的 KDoc 里大段引用了上面这些函数名，
 * 只做 `contains` 的话「把真实调用删掉、只留注释」也一样绿 ⇒ 一律走 [SourceScan.stripped]。
 *
 * ## ⚠️ 反证记录（必须实跑，见交付说明）
 *
 * 1. 把 `ChatScreen` 里 `ChatPendingAttachmentBar(...)` 调用删掉 ⇒
 *    [screen_rendersThePendingBarInsideTheComposerCard] 变红；
 * 2. 把 `hasPendingAttachments` 改回 `selectedPhotoUris.isNotEmpty() || capturedPreview != null` ⇒
 *    [screen_derivesBarAndSendButtonFromTheSamePureFunction] 变红；
 * 3. 把 `ChatImageViewer` 里 `transformable(...)` 删掉 ⇒
 *    [viewer_isAFullScreenZoomableDialog] 变红；
 * 4. 把 `ChatInlineImages` 里两处 `imageClickModifier(onClick = onClick)` 删掉 ⇒
 *    [inlineImages_attachAClickToEveryRenderedImage] 变红。
 */
class ChatPendingAttachmentWiringTest {

    private val screenSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatScreen.kt")
    private val inlineImagesSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatInlineImages.kt")
    private val pendingSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatPendingAttachments.kt")
    private val barSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatPendingAttachmentBar.kt")
    private val viewerSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatImageViewer.kt")

    // ------------------------------------------------------------ (a) 输入框区域的预览条

    @Test
    fun screen_rendersThePendingBarInsideTheComposerCard() {
        assertEquals(
            "输入框卡片里必须**恰有一处**渲染预览条",
            1,
            SourceScan.countOccurrences(screenSource, "ChatPendingAttachmentBar("),
        )
        assertTrue(
            "预览条的数据源必须是派生的 `pendingAttachmentPreviews`（与「+」面板同一份状态）",
            screenSource.contains("previews = pendingAttachmentPreviews"),
        )
        assertTrue(
            "相机预览图必须传进去（相机图没有 uri，只能直接画 Bitmap）",
            screenSource.contains("cameraPreview = capturedPreview"),
        )
        assertTrue(
            "预览条必须能移除（缩略图 + 可移除是本次要求的形态）",
            screenSource.contains("onRemove = { preview ->"),
        )
    }

    @Test
    fun screen_derivesBarAndSendButtonFromTheSamePureFunction() {
        assertTrue(
            "组装必须走纯函数（可单测）",
            screenSource.contains("chatPendingAttachmentPreviews("),
        )
        assertTrue(
            "可见性判据必须走纯函数",
            screenSource.contains("chatShouldShowPendingAttachmentBar(pendingAttachmentPreviews)"),
        )
        assertTrue(
            "纯函数必须真的定义在 `ChatPendingAttachments.kt` 里",
            pendingSource.contains("internal fun chatPendingAttachmentPreviews("),
        )
        assertTrue(
            "纯函数必须真的定义在 `ChatPendingAttachments.kt` 里",
            pendingSource.contains("internal fun chatShouldShowPendingAttachmentBar("),
        )
        assertFalse(
            "❌ 不得再留一份「另写的判据」（两份判据必然漂移：一处改了另一处忘）",
            screenSource.contains("selectedPhotoUris.isNotEmpty() || capturedPreview != null"),
        )
    }

    @Test
    fun screen_removalCallbackMapsBackToTheSameStateItWasBuiltFrom() {
        assertTrue(
            "移除相册项必须回到 `selectedPhotoUris`（按字符串比对，避开 Uri 相等性的歧义）",
            screenSource.contains("selectedPhotoUris.indexOfFirst { it.toString() == preview.uri }"),
        )
        assertTrue(
            "相册项用 `removeAt`（`selectedPhotoUris` 是 SnapshotStateList）",
            screenSource.contains("selectedPhotoUris.removeAt(index)"),
        )
        assertTrue(
            "移除相机项 = 清掉 `capturedPreview`",
            screenSource.contains("ChatPendingAttachmentKind.CAMERA -> capturedPreview = null"),
        )
    }

    @Test
    fun screen_reservesVerticalRoomForTheBar() {
        assertTrue(
            "预览条一出现 composer 就高出一截 ⇒ 列表底部留白要跟着让",
            screenSource.contains("messageListBottomPadding + pendingAttachmentBarAllowance"),
        )
        assertTrue(
            "「滚到底」按钮也要跟着让（否则会落在预览条下面）",
            screenSource.contains("composerBottomPadding + 86.dp + pendingAttachmentBarAllowance"),
        )
        assertTrue(
            "让出的量必须来自预览条自己的高度常量（改尺寸时只有一处要改）",
            barSource.contains("internal val CHAT_PENDING_ATTACHMENT_BAR_HEIGHT"),
        )
    }

    // ------------------------------------------------------------ (b) 点图放大

    @Test
    fun screen_opensTheViewerFromEveryImagePath() {
        assertEquals(
            "全屏查看器必须**恰有一处**挂载点",
            1,
            SourceScan.countOccurrences(screenSource, "ChatImageViewer("),
        )
        assertTrue(
            "点图的请求必须带上解析后的模型",
            screenSource.contains("enlargedImage = ChatImagePreviewRequest(model)"),
        )
        assertTrue(
            "查看器关闭后必须复位（否则下次进聊天页会直接弹出来）",
            screenSource.contains("onDismiss = { enlargedImage = null }"),
        )
        val wiringCount = SourceScan.countOccurrences(screenSource, "onImageClick = ")
        assertTrue(
            "三条图片路径都要接上点击：用户所发的图（气泡缩略图）/ Agent 截图卡片 / " +
                "assistant 正文内联引用 —— 期望至少 6 处传递（含调用点），实际 $wiringCount",
            wiringCount >= 6,
        )
    }

    @Test
    fun inlineImages_attachAClickToEveryRenderedImage() {
        assertTrue(
            "三个对外组件（正文内联图 / 用户气泡缩略图 / Agent 截图卡片）都要收 `onImageClick`",
            SourceScan.countOccurrences(inlineImagesSource, "onImageClick: ((String?) -> Unit)? = null") >= 4,
        )
        assertTrue(
            "公共修饰必须存在",
            inlineImagesSource.contains("private fun Modifier.imageClickModifier("),
        )
        assertEquals(
            "**两处**真正画图的地方（缩略图 + 正文块）都要挂上它",
            2,
            SourceScan.countOccurrences(inlineImagesSource, "imageClickModifier(onClick ="),
        )
        assertTrue(
            "修饰内部必须真的挂 `clickable`",
            inlineImagesSource.contains("clickable(onClick = onClick)"),
        )
        assertTrue(
            "缩略图那条路径要把**自己那份模型**回传（否则查看器不知道画哪张）",
            inlineImagesSource.contains("onClick = onClick?.let { click -> { click(model) } }"),
        )
        assertTrue(
            "正文内联图要把**解析后的模型**回传（否则查看器不知道画哪张）",
            inlineImagesSource.contains("onClick = onImageClick?.let { click -> { click(resolved) } }"),
        )
    }

    @Test
    fun viewer_isAFullScreenZoomableDialog() {
        assertTrue(
            "查看器组件必须存在",
            viewerSource.contains("internal fun ChatImageViewer("),
        )
        assertTrue(
            "必须铺满整屏（默认的 Dialog 只有一个小窗口）",
            viewerSource.contains("DialogProperties(usePlatformDefaultWidth = false)"),
        )
        assertTrue(
            "必须有关闭路径",
            viewerSource.contains("onDismissRequest = onDismiss"),
        )
        assertTrue(
            "缩放走纯手势（加分项，但做了就要锁住它没被删掉）",
            viewerSource.contains("transformable(transformState)"),
        )
        assertTrue(
            "点空白处也能关",
            viewerSource.contains(".clickable(onClick = onDismiss)"),
        )
    }

    // ------------------------------------------------------------ 三语文案

    @Test
    fun newStringsExistInAllThreeLocales() {
        val keys = listOf(
            "chat_pending_attachment_remove",
            "chat_pending_attachment_photo_label",
            "chat_pending_attachment_camera_label",
            "chat_image_viewer_close",
        )
        val locales = listOf("values", "values-en", "values-ja")
        locales.forEach { locale ->
            val file = File("src/main/res/$locale/strings.xml")
            assertTrue("找不到 $locale/strings.xml", file.isFile)
            val xml = file.readText()
            keys.forEach { key ->
                assertTrue(
                    "❌ $locale/strings.xml 缺少 `$key`（三语必须同步、键名集合一致）",
                    xml.contains("name=\"$key\""),
                )
            }
        }
    }
}
