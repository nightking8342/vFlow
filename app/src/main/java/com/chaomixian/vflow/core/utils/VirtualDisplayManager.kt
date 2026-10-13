// 文件: main/java/com/chaomixian/vflow/core/utils/VirtualDisplayManager.kt
package com.chaomixian.vflow.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Display
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.services.ShellManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 虚拟屏幕管理器 (Headless 模式)。
 * 使用 ImageReader 作为 Surface 接收端，配合 Shizuku 创建后台虚拟屏幕。
 */
object VirtualDisplayManager {
    private const val TAG = "VirtualDisplayManager"

    /**
     * fork：虚拟屏预览的推帧间隔。
     *
     * 每帧都要把 ImageReader 的 buffer 拷成 Bitmap（1080×2400 RGBA ≈ 10 MB），
     * 这个常量就是「看得见」与「拷贝开销」之间的旋钮。250 ms（4 fps）在真机上
     * 已足够看清 AI 在虚拟屏里做什么，同时把拷贝开销压在可接受范围。
     */
    private const val PREVIEW_FRAME_INTERVAL_MS = 250L

    data class VirtualDisplaySession(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val imageReader: ImageReader
    )

    private var currentSession: VirtualDisplaySession? = null
    private val mutex = Mutex()
    private var backgroundHandler: Handler? = null
    private var handlerThread: HandlerThread? = null

    // fork：虚拟屏预览（方案 A）的帧获取串行化锁。
    //
    // ⚠️ **ImageReader 是单消费者**：Agent 主循环的截图（[captureCurrentFrame]）与预览循环
    // 都会 `acquireLatestImage()`。两条路各自 acquire 会互相抢帧（`maxImages = 2`，
    // 抢不到的那条拿到 null），更糟的是**取到 image 却不 close 会把生产者堵死**。
    // 因此两条路统一走 [captureCurrentFrame]，由本锁保证同一时刻只有一个消费者。
    private val frameLock = Any()

    private var previewScope: CoroutineScope? = null
    private var previewJob: Job? = null

    /**
     * 启动一个无头虚拟屏幕会话。
     * @return 成功返回 displayId，失败返回 -1。
     */
    suspend fun startHeadlessSession(width: Int = 1080, height: Int = 2400, densityDpi: Int = 400): Int {
        mutex.withLock {
            // 如果已有会话，先清理
            releaseLocked()

            DebugLogger.i(TAG, "正在创建 ImageReader Surface ($width x $height)...")

            // 1. 初始化后台线程 (用于 ImageReader 回调)
            if (handlerThread == null) {
                handlerThread = HandlerThread("VirtualDisplayReader").apply { start() }
                backgroundHandler = Handler(handlerThread!!.looper)
            }

            // 2. 创建 ImageReader
            // 使用 RGBA_8888，maxImages=2 即可
            val imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

            // 3. 通过 Shizuku 请求 Shell 创建 VirtualDisplay
            val displayId = ShellManager.createVirtualDisplay(imageReader.surface, width, height, densityDpi)

            if (displayId > 0) {
                DebugLogger.i(TAG, "虚拟屏幕创建成功: ID $displayId")
                currentSession = VirtualDisplaySession(displayId, width, height, imageReader)
                waitForFirstFrameLocked()
                return displayId
            } else {
                DebugLogger.e(TAG, "虚拟屏幕创建失败 (Shell返回 -1)")
                imageReader.close()
                return -1
            }
        }
    }

    private suspend fun waitForFirstFrameLocked(timeoutMs: Long = 1500L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val bitmap = captureCurrentFrame()
            if (bitmap != null) {
                bitmap.recycle()
                return
            }
            delay(100)
        }
        DebugLogger.w(TAG, "等待虚拟屏幕首帧超时，后续截图将继续重试")
    }

    /**
     * 释放当前会话。
     */
    suspend fun release() {
        mutex.withLock {
            releaseLocked()
        }
    }

    private suspend fun releaseLocked() {
        // fork：先停预览循环，避免它在 ImageReader 关闭后继续 acquire
        stopPreview()

        currentSession?.let { session ->
            DebugLogger.i(TAG, "正在销毁虚拟屏幕 ID: ${session.displayId}")
            // 1. 通知 Shell 销毁 VirtualDisplay
            ShellManager.destroyVirtualDisplay(session.displayId)
            // 2. 关闭 ImageReader
            try {
                session.imageReader.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        currentSession = null

        // 停止后台线程
        handlerThread?.quitSafely()
        handlerThread = null
        backgroundHandler = null
    }

    /**
     * 获取当前的 Display ID。
     */
    fun getCurrentDisplayId(): Int {
        return currentSession?.displayId ?: -1
    }

    fun getCurrentSessionSize(): Pair<Int, Int>? {
        val session = currentSession ?: return null
        return session.width to session.height
    }

    /**
     * 直接从 ImageReader 获取最新一帧的 Bitmap。
     * 这比 'screencap' 命令快得多，且不需要 root。
     *
     * fork：所有取帧路径（Agent 截图 / 预览循环）都必须经过这里 —— 由 [frameLock] 串行化，
     * 因为 ImageReader 是单消费者（原因见 [frameLock] 的说明）。
     * 返回的 Bitmap **归调用方所有**，用完需自行 `recycle()`。
     */
    fun captureCurrentFrame(): Bitmap? = synchronized(frameLock) { captureLatestFrameLocked() }

    private fun captureLatestFrameLocked(): Bitmap? {
        val session = currentSession ?: return null

        try {
            // 获取最新图像
            val image = session.imageReader.acquireLatestImage() ?: return null

            val planes = image.planes
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * session.width

            // 创建 Bitmap
            val bitmap = Bitmap.createBitmap(
                session.width + rowPadding / pixelStride,
                session.height,
                Bitmap.Config.ARGB_8888
            )
            bitmap.copyPixelsFromBuffer(buffer)
            image.close()

            // 如果有 padding，裁剪一下
            return if (rowPadding == 0) {
                bitmap
            } else {
                val cropped = Bitmap.createBitmap(bitmap, 0, 0, session.width, session.height)
                bitmap.recycle()
                cropped
            }
        } catch (e: Exception) {
            DebugLogger.e(TAG, "抓取屏幕帧失败", e)
            return null
        }
    }

    /**
     * fork：启动虚拟屏预览（方案 A）。
     *
     * 以 [PREVIEW_FRAME_INTERVAL_MS] 的间隔取最新一帧回调给 [onFrame]，供悬浮窗显示 ——
     * 虚拟屏模式下用户在主屏上**看不到** AI 正在操作的屏幕，这是唯一的可见通道。
     *
     * ⚠️ 三条约定：
     *  - [onFrame] 在**后台线程**上被调用（`Dispatchers.Default`），UI 侧必须自己 post 到主线程；
     *  - Bitmap 的**所有权转移给 [onFrame]**，由它负责回收；
     *  - 只应在**虚拟屏**场景调用（主屏场景没有会话，取帧恒为 null，预览不会出现）。
     *
     * 生命周期：由 [release] / [releaseLocked] 自动停止，不需要调用方额外收尾。
     */
    fun startPreview(onFrame: (Bitmap) -> Unit) {
        stopPreview()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        previewScope = scope
        previewJob = scope.launch {
            while (isActive) {
                delay(PREVIEW_FRAME_INTERVAL_MS)
                val frame = captureCurrentFrame() ?: continue
                if (!isActive) {
                    frame.recycle()
                    break
                }
                try {
                    onFrame(frame)
                } catch (e: Exception) {
                    // 回调方抛异常不能拖死预览循环（否则表现为「预览静默冻结」）
                    DebugLogger.e(TAG, "预览帧回调失败", e)
                    if (!frame.isRecycled) frame.recycle()
                }
            }
        }
    }

    /**
     * fork：停止虚拟屏预览。可重复调用。
     */
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewScope?.cancel()
        previewScope = null
    }
}
