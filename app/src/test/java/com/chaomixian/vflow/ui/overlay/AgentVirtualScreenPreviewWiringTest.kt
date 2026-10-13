package com.chaomixian.vflow.ui.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 虚拟屏预览（方案 A）的**接线锚定**（源码扫描型，形态照 `ReturnKeysEditorWiringTest`）。
 *
 * ⚠️ **为什么必须有**：这条链路的失效模式几乎全是**静默**的 ——
 *  - 预览循环没在 `release()` 里停掉 ⇒ 屏幕都销毁了，循环还在每 250 ms 拷一张 10 MB 的
 *    位图，**用户只看到"电池掉得快"**，没有任何行为测试会红；
 *  - 取帧多出一个调用点 ⇒ `ImageReader` 变成双消费者（`maxImages = 2`）：两条路互相抢帧、
 *    **取到 image 不 close 会把生产者堵死**，表现为「截图偶发为空 / 虚拟屏卡住」；
 *  - 预览回调在后台线程直接碰视图 ⇒ 崩溃（这条不静默，但只在真机偶发）；
 *  - 帧没被回收 ⇒ 内存泄漏，同样只在真机显现。
 *
 * ⚠️ 全部**先剥注释**再断言（源码里到处是这些符号的说明文字）。
 */
class AgentVirtualScreenPreviewWiringTest {

    private fun codeOnly(relative: String): String {
        val path = "src/main/java/com/chaomixian/vflow/$relative"
        val file = File(path)
        assertTrue("找不到 $path（当前目录 ${File(".").absolutePath}）", file.exists())
        val withoutBlockComments = file.readText().replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlockComments.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
    }

    private val virtualDisplay = "core/utils/VirtualDisplayManager.kt"
    private val overlay = "ui/overlay/AgentOverlayManager.kt"
    private val agentModule = "core/workflow/module/interaction/AgentModule.kt"
    private val autoGlmModule = "core/workflow/module/interaction/AutoGLMModule.kt"

    @Test
    fun `frame acquisition stays single consumer`() {
        val src = codeOnly(virtualDisplay)
        assertEquals(
            "ImageReader 只能有一个 acquireLatestImage 调用点（多一处就会互相抢帧、且可能把生产者堵死）",
            1,
            Regex("acquireLatestImage").findAll(src).count()
        )
        assertTrue(
            "取帧必须经 frameLock 串行化",
            src.contains("fun captureCurrentFrame(): Bitmap? = synchronized(frameLock)")
        )
    }

    @Test
    fun `preview loop is throttled and torn down with the session`() {
        val src = codeOnly(virtualDisplay)
        assertTrue(
            "预览循环必须节流（否则是拿 10 MB/帧的忙循环）",
            src.contains("delay(PREVIEW_FRAME_INTERVAL_MS)")
        )
        assertTrue(
            "releaseLocked 必须停掉预览循环（漏了就是销毁后继续拷帧）",
            src.contains("private suspend fun releaseLocked()") &&
                src.substringAfter("private suspend fun releaseLocked()").substringBefore("currentSession?.let")
                    .contains("stopPreview()")
        )
    }

    @Test
    fun `overlay renders preview on the main thread and recycles frames`() {
        val src = codeOnly(overlay)
        assertTrue("必须提供 updatePreview", src.contains("fun updatePreview(bitmap: Bitmap)"))
        assertTrue(
            "回调来自后台线程，必须 post 到主线程",
            src.contains("mainHandler.post")
        )
        assertTrue(
            "替换时必须回收上一帧",
            src.contains("previous?.takeIf { !it.isRecycled }?.recycle()")
        )
        assertTrue(
            "dismiss 时必须回收当前帧",
            src.contains("previewBitmap?.takeIf { !it.isRecycled }?.recycle()")
        )
        assertTrue(
            "预览默认不可见（主屏模式全程不显示）",
            src.contains("visibility = View.GONE")
        )
    }

    @Test
    fun `both virtual-screen modules start the preview inside the cleanup scope`() {
        listOf(agentModule, autoGlmModule).forEach { relative ->
            val src = codeOnly(relative)
            val startIndex = src.indexOf("VirtualDisplayManager.startPreview")
            assertTrue("$relative 必须启动虚拟屏预览", startIndex >= 0)
            assertTrue(
                "$relative 的预览只能挂在 try 内（否则取消时会没人收尾）",
                src.indexOf("try {") in 0 until startIndex
            )
            assertTrue(
                "$relative 的预览必须只在虚拟屏模式启动",
                src.substring((startIndex - 200).coerceAtLeast(0), startIndex).contains("targetDisplayId > 0")
            )
            assertTrue(
                "$relative 必须调用 updatePreview 把帧交给悬浮窗",
                src.contains("overlayManager.updatePreview(frame)")
            )
        }
    }

    /**
     * ⚠️ 这条锁的是一个**既有缺陷**：两个模块原先都在「虚拟屏」分支里调了
     * `overlayManager.hideForScreenshot()`，而 `restoreAfterScreenshot()` 只写在
     * 「主屏」分支上 ⇒ **整场虚拟屏任务里悬浮窗全程不可见**（连暂停/结束按钮都看不见），
     * 预览帧自然也没人能看到。悬浮窗挂在主屏、被截的图来自虚拟屏，本来就不需要隐藏。
     */
    @Test
    fun `screenshot hiding never applies to the virtual display branch`() {
        listOf(agentModule, autoGlmModule).forEach { relative ->
            val src = codeOnly(relative)
            Regex("hideForScreenshot").findAll(src).forEach { match ->
                val preceding = src.substring((match.range.first - 200).coerceAtLeast(0), match.range.first)
                assertTrue(
                    "$relative 里第 ${match.range.first} 处的 hideForScreenshot 没有和 targetDisplayId == 0 绑定",
                    preceding.contains("targetDisplayId == 0")
                )
            }
        }
    }
}
