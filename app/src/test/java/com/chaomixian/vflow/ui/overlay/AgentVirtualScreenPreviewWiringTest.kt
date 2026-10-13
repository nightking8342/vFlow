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

    /**
     * ⚠️ 这条锁的是「虚拟屏截图每一步都失败」的**收尾决定**（2026-10-14）：**删掉 shell 回退，而不是修它**。
     *
     * 依据：`screencap -d` 要 SurfaceFlinger 的 **DisplayId**（物理屏那串大数），而
     * `createVirtualDisplay` 返回的是**逻辑** displayId ⇒ 传逻辑 id 必然报
     * `Display id 'N' is not valid`，每步白花约 350 ms 并打一行假错误。
     * 想拿虚拟屏的 SF DisplayId 只能抠 `dumpsys SurfaceFlinger` 的字符串，而 dumpsys 是
     * **调试接口不是数据接口**（`shortcut-system-overview.md` §4.1）⇒ 不值得。
     */
    @Test
    fun `virtual display screenshot never falls back to screencap -d`() {
        val src = codeOnly("core/workflow/module/interaction/AgentUtils.kt")
        assertTrue(
            "⚠️ 不得再出现 `screencap -d` —— 那条回退必然失败（传的是逻辑 id）",
            !src.contains("screencap -d")
        )
        assertTrue("内存帧路径必须保留", src.contains("VirtualDisplayManager.captureCurrentFrame()"))
        assertTrue(
            "没有可用帧时必须降级为 warning（不是 error）—— 那是预期内的降级，不是故障",
            src.contains("本轮没有可用帧")
        )
    }

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
    fun `preview frames are owned by the producer and never recycled by consumers`() {
        val overlaySrc = codeOnly(overlay)
        assertTrue("必须提供 updatePreview", overlaySrc.contains("fun updatePreview(bitmap: Bitmap)"))
        assertTrue(
            "消费方**不得**回收帧 —— 所有权在生产者手里，回收时机由它统一决定",
            !overlaySrc.contains(".recycle()")
        )
        assertTrue(
            "预览默认不可见（主屏模式全程不显示）",
            overlaySrc.contains("visibility = View.GONE")
        )

        val vdmSrc = codeOnly(virtualDisplay)
        assertTrue(
            "生产者**也不**回收上一帧 —— Compose 的重组时机不在我们手里，提前回收就是 use-after-recycle",
            !vdmSrc.contains("previous?.takeIf")
        )
        assertTrue(
            "发布必须在主线程（与 ImageView 的绘制同一条线程）",
            vdmSrc.contains("withContext(Dispatchers.Main)")
        )
    }

    /**
     * ⚠️ 这条锁的是一个**真机现象**（2026-10-14 日志）：虚拟屏截图**每一步都失败** ——
     * `虚拟屏幕 (ID: 42) 内存帧为空` → shell 回退也失败 ⇒ **AI 智能体在虚拟屏上是盲跑**（只靠 XML 树）。
     *
     * 根因：`ImageReader` **只在内容变化时产出帧**，而预览循环每 250 ms 就把唯一待取的那帧取走
     * （`acquireLatestImage` 是单消费者）⇒ Agent 紧接着来取就是 null。
     */
    @Test
    fun `virtual display falls back to a recent frame instead of none`() {
        val src = codeOnly(virtualDisplay)
        assertTrue("必须有最近帧缓存", src.contains("private var lastFrame: Bitmap?"))
        assertTrue(
            "缓存必须有**新鲜度上限** —— 否则 Agent 会看到自己动作之前的屏幕，误判动作失败",
            src.contains("LAST_FRAME_MAX_AGE_MS")
        )
        assertTrue(
            "没有新帧时必须回退到缓存副本",
            src.contains("takeFrameLocked() ?: cachedFrameCopyLocked()")
        )
        assertTrue(
            "预览循环必须只吃新帧（吃缓存会把陈旧一遍遍刷成'新鲜'）",
            src.contains("captureFreshFrameOnly()")
        )
    }

    @Test
    fun `preview stream is multi consumer`() {
        val src = codeOnly(virtualDisplay)
        assertTrue("必须提供 addPreviewListener", src.contains("fun addPreviewListener(listener: (Bitmap) -> Unit)"))
        assertTrue("必须提供 removePreviewListener", src.contains("fun removePreviewListener(listener: (Bitmap) -> Unit)"))
        assertTrue(
            "第一个监听者到来才启动取帧循环",
            src.contains("if (previewJob == null) startPreviewLoop()")
        )
        assertTrue(
            "最后一个监听者走掉就停预览（否则空转拷帧）",
            src.contains("if (previewListeners.isEmpty()) stopPreview()")
        )
        assertTrue("stopPreview 必须清空监听者集合", src.contains("previewListeners.clear()"))
    }

    @Test
    fun `both virtual-screen modules start the preview inside the cleanup scope`() {
        listOf(agentModule, autoGlmModule).forEach { relative ->
            val src = codeOnly(relative)
            val startIndex = src.indexOf("VirtualDisplayManager.addPreviewListener")
            assertTrue("$relative 必须注册虚拟屏预览监听者", startIndex >= 0)
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
