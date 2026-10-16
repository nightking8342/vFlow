package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线测试**：治「纯函数全绿但集成点没接上」。
 *
 * 本仓库在这类问题上踩过两次（`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、
 * `XposedDiagnostics.messageFor` 零生产调用点）—— 单测覆盖了纯函数、生产路径却没人调它。
 * 本任务的每个新能力都有「纯函数文件」与「调用点文件」之分，故必须显式锁住调用点。
 *
 * ## ⚠️ 断言必须锚在**新增的表达式**上
 *
 * `chatAgentToolImageAttachments(` / `persistReferencedArtifacts(` / `ChatAttachmentStore.open(` /
 * `ChatImageCarriers.normalizeForRequest(` / `normalizedPixel(` 都是本次**新加**的；
 * 锚在改造前就存在的标识符上（如 `sendMessage`）会**恒真**。
 * 并且**必须区分「定义」与「调用」**：`persistReferencedArtifacts(` 若只数出现次数，
 * 把三个调用点全删掉、只留定义也照样绿 —— 故这里按**次数下界**断言
 * （定义 1 处 + 调用 3 处 ⇒ 下界 4），并额外钉住调用点的实参形态。
 *
 * ## ⚠️ 扫描前必须剥注释
 *
 * 本仓库的改动会在 KDoc 里大段引用函数名与新写法（例如本文件提到的那些），
 * 只做 `contains` 的话「把真实调用删掉、只留注释」也一样绿。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 `ChatViewModel` 里三处 `persistReferencedArtifacts(…)` 调用注释掉 ⇒
 *    [viewModel_persistsReferencedArtifactsAtEveryFinalizeExit] **变红**；
 * 2. 把 `ChatScreen` 的 `R.string.chat_attachment_not_supported` snackbar 分支加回去 ⇒
 *    [screen_noLongerDropsAttachments] **变红**；
 * 3. 把 `prepareTap` 的 `x = normalizedPixel(...)` 改回 `x = rawX` ⇒
 *    [nativeTooling_routesEveryCoordinateThroughTheNormalizer] **变红**。
 */
class ChatMultimodalWiringTest {

    private val screenSource = SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatScreen.kt")
    private val viewModelSource = SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatViewModel.kt")
    private val nativeToolingSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentNativeTooling.kt")
    private val completionClientSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatCompletionClient.kt")

    // ------------------------------------------------------------ 1. 用户附图真的发出去

    @Test
    fun screen_noLongerDropsAttachments() {
        assertFalse(
            "❌ 输入框里选的图/拍的照不得再被「附件暂不支持」丢掉",
            screenSource.contains("chat_attachment_not_supported"),
        )
        assertTrue(
            "必须真的构造附件（相册 content:// 与相机 Bitmap 两条来源）",
            screenSource.contains("ChatImageAttachment("),
        )
        assertTrue(
            "相册来源必须显式给 mime（默认值是 image/jpeg，会把 PNG 标错）",
            screenSource.contains("contentResolver.getType("),
        )
        assertTrue(
            "相机来源是 Bitmap、没有 uri ⇒ 必须先落一个中间文件",
            screenSource.contains("writeCameraPreviewToCache("),
        )
    }

    @Test
    fun screen_sendsThePendingAttachmentsWithTheMessage() {
        assertTrue(
            "两个发送点（按钮 + 键盘）都必须把附件一起交给 VM",
            screenSource.contains("sendMessage(prompt, attachments)"),
        )
        assertEquals(
            "按钮与键盘各一处，共两处",
            2,
            SourceScan.countOccurrences(screenSource, "sendMessage(prompt, attachments)"),
        )
        assertFalse(
            "❌ 不得再有「只传正文」的旧调用形态",
            screenSource.contains("sendMessage(prompt)"),
        )
        assertTrue(
            "有图无字也要能发",
            screenSource.contains("hasPendingAttachments"),
        )
    }

    @Test
    fun screen_rendersBothUserImagesAndInlineReferences() {
        assertTrue(
            "用户所发的图要在气泡里看到",
            screenSource.contains("ChatAttachmentThumbnails("),
        )
        assertTrue(
            "Agent 截图不得伪装成用户气泡",
            screenSource.contains("ChatAgentScreenshotCard("),
        )
        assertTrue(
            "assistant 正文要能渲染正文内联引用",
            screenSource.contains("ChatMarkdownWithImages("),
        )
        assertTrue(
            "句柄 -> 真实图 的解析必须接到 VM",
            screenSource.contains("resolveChatAttachment("),
        )
    }

    // ------------------------------------------------------------ 2. 截图进历史 + 落盘

    @Test
    fun viewModel_turnsToolImagesIntoScreenshotMessages() {
        assertTrue(
            "工具产出的图必须经 chatAgentToolImageAttachments 变成 AGENT 档附件",
            viewModelSource.contains("chatAgentToolImageAttachments("),
        )
        assertTrue(
            "截图消息的句柄解析必须落到会话的 artifact store",
            viewModelSource.contains("as? VImage"),
        )
        assertTrue(
            "附件落盘必须接到 filesDir 上的 store",
            viewModelSource.contains("ChatAttachmentStore.open("),
        )
        assertTrue(
            "落盘必须在 IO 线程（不得阻塞 Main）",
            viewModelSource.contains("withContext(Dispatchers.IO)"),
        )
    }

    @Test
    fun viewModel_persistsReferencedArtifactsAtEveryFinalizeExit() {
        assertTrue(
            "主路径必须做「引用即保存」",
            viewModelSource.contains("persistReferencedArtifacts(updatedConversation.id, pendingMessage.id)"),
        )
        assertEquals(
            "被中断 / 失败两条收尾路径各一次（best-effort），加上定义共 4 处；" +
                "少于 4 处说明有收尾出口漏了落盘",
            4,
            SourceScan.countOccurrences(viewModelSource, "persistReferencedArtifacts("),
        )
        assertTrue(
            "引用解析必须复用 ChatArtifactReferences（判据与渲染同源）",
            viewModelSource.contains("ChatArtifactReferences.referencedHandles("),
        )
    }

    // ------------------------------------------------------------ 3. D20 坐标归一化

    @Test
    fun nativeTooling_routesEveryCoordinateThroughTheNormalizer() {
        // 8 个坐标参数（tap x/y、input_text x/y、swipe 四个）都必须过 normalizedPixel；
        // 加上定义本身共 9 处。
        assertEquals(
            "每个坐标参数都必须经归一化换算（漏一个就会静默点偏）",
            9,
            SourceScan.countOccurrences(nativeToolingSource, "normalizedPixel("),
        )
        assertTrue(
            "换算本体必须是 chatAgentNormalizedToPixels（越界报错，不钳制）",
            nativeToolingSource.contains("chatAgentNormalizedToPixels("),
        )
        assertTrue(
            "归一化必须用真实屏幕尺寸",
            nativeToolingSource.contains("screenExtent("),
        )
        assertFalse(
            "❌ 已发布 schema 的坐标语义不再是绝对像素，描述里不得再写 absolute screen pixels",
            nativeToolingSource.contains("absolute screen pixels"),
        )
        assertTrue(
            "schema 描述必须说清取值域与方向",
            nativeToolingSource.contains("normalized 0-1000"),
        )
        assertTrue(
            "recovery hint 必须提示坐标是归一化的（模型自愈的主防线）",
            nativeToolingSource.contains("never absolute pixels"),
        )
    }

    @Test
    fun nativeTooling_tellsTheModelHowToShowImages() {
        assertTrue(
            "observe_ui 的结果要给出可照抄的引用示例",
            nativeToolingSource.contains("inline image reference"),
        )
        assertTrue(
            "include_screenshot 不再是「留给未来」",
            !nativeToolingSource.contains("future multimodal/visual fallback"),
        )
    }

    @Test
    fun moduleExecutor_tellsTheModelHowToShowToolImages() {
        val executorSource =
            SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt")
        assertTrue(
            "工具结果里出现 image artifact 时要给出引用引导",
            executorSource.contains("CHAT_IMAGE_ARTIFACT_TYPE_LABEL"),
        )
        assertTrue(
            "引导必须给出可照抄的示例",
            executorSource.contains("inline image reference"),
        )
    }

    // ------------------------------------------------------------ 4. 预算口径统一

    @Test
    fun completionClient_normalizesTheHistoryBeforePlanningImages() {
        assertTrue(
            "预算规划与装配必须看同一份历史（否则非承载角色的附件会静默吃掉预算）",
            completionClientSource.contains("ChatImageCarriers.normalizeForRequest("),
        )
        assertTrue(
            "规划器必须吃归一化后的历史",
            completionClientSource.contains("imageResolver.plan(normalizedHistory"),
        )
    }

    // ------------------------------------------------------------ 5. 扫描不空转

    @Test
    fun theScanIsNotVacuous() {
        assertTrue(screenSource.contains("fun ChatScreen("))
        assertTrue(viewModelSource.contains("class ChatViewModel"))
        assertTrue(nativeToolingSource.contains("class ChatAgentNativeToolExecutor"))
        assertTrue(completionClientSource.contains("class ChatCompletionClient"))
        assertTrue(
            "剥注释剥过头了：ChatScreen 的注释长度远大于这个数",
            screenSource.length > 10_000,
        )
    }
}