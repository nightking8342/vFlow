// 文件: main/java/com/chaomixian/vflow/core/utils/VirtualDisplayManager.kt
package com.chaomixian.vflow.core.utils

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
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
import kotlinx.coroutines.withContext
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

    /**
     * fork：让输入法渲染在虚拟屏上，而不是弹到用户的主屏。
     *
     * 取值来自 `WindowManager.DISPLAY_IME_POLICY_*`（⚠️ **LOCAL 是 0**，FALLBACK_DISPLAY 是 1，HIDE 是 2）。
     * 默认（FALLBACK_DISPLAY）下 AOSP 会把输入法一律显示在主屏 —— 见
     * `InputMethodManagerService.computeImeDisplayIdForTargetInner()`：
     * 只有目标屏的 policy 是 LOCAL 才留在本屏，否则 `return fallbackDisplayId`。
     */
    private const val IME_POLICY_LOCAL = 0

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
     * fork：最近一次**成功取到**的帧 + 它的时间戳（供"这一轮没有新帧"时回退，见 [captureCurrentFrame]）。
     *
     * ⚠️ 只是回退用的缓存，**不参与回收** —— 与 [previewListeners] 的契约一致（帧谁也不 recycle）。
     */
    private var lastFrame: Bitmap? = null
    private var lastFrameAtMs: Long = 0L

    /**
     * fork：缓存帧的**新鲜度上限**。
     *
     * 超过这个年龄就不再当"当前画面"返回 —— 否则 Agent 会看到**自己动作之前**的屏幕，
     * 误判"动作没生效"（那比拿不到图更糟）。预览循环在跑时缓存 ≤250 ms，1 s 是安全余量。
     */
    private const val LAST_FRAME_MAX_AGE_MS = 1_000L

    /**
     * fork：预览帧的**监听者集合**（多消费者）。
     *
     * 为什么是多消费者：悬浮窗那一小块预览与监视 Activity 的大画面**可能同时存在**。
     *
     * ⚠️ **所有权契约（改这块前必读）**：
     *  - 回调在**主线程**上发生；
     *  - **监听者不得 `recycle()`**，生产者**也不回收** —— 见下面那条注释，回收权谁都不持有，
     *    交给 GC。订阅方（`ImageView` / Compose `Image`）可以一直引用到下一帧到来。
     *
     * 只在主线程读写（添加/移除都来自主线程：悬浮窗 `show()`、Compose 的 DisposableEffect），
     * 所以用普通集合即可。
     */
    private val previewListeners = linkedSetOf<(Bitmap) -> Unit>()

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
                // fork：把输入法留在虚拟屏上。⚠️ 失败不影响任务本身，只影响键盘弹在哪块屏 ——
                //    所以这里只记一条 warning，不阻断。
                if (!ShellManager.setVirtualDisplayImePolicy(displayId, IME_POLICY_LOCAL)) {
                    DebugLogger.w(TAG, "输入法策略未设置成功，键盘可能仍会弹在主屏")
                }
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
     * 取一帧给**调用方**（Agent 截图路径）。
     *
     * fork：优先取新帧；**取不到就退回最近一帧的副本**（有新鲜度上限）。
     *
     * ⚠️ 为什么需要这条回退：`ImageReader` **只在内容变化时产出帧**，而预览循环每 250 ms 就会把
     * 唯一待取的那帧取走（`acquireLatestImage` 是单消费者）⇒ Agent 紧接着来取时会拿到 null。
     * 真机日志里每一步都是 `虚拟屏幕 (ID: 42) 内存帧为空`，而 shell 回退又因为传了**逻辑** id
     * 而必然失败（`screencap -d` 要**物理** id，见 `multi-display-survey.md` §3.3）
     * ⇒ **AI 智能体在虚拟屏上一直是盲跑**（只靠 XML 树）。
     *
     * 返回的 Bitmap **归调用方所有**，用完需自行 `recycle()`。
     */
    fun captureCurrentFrame(): Bitmap? = synchronized(frameLock) {
        takeFrameLocked() ?: cachedFrameCopyLocked()
    }

    /**
     * fork：只取**新**帧，取不到就 null（**不吃缓存**）。
     *
     * 预览循环专用：如果它也跟着吃缓存，就会把"陈旧"一遍遍刷成"新鲜"（缓存时间戳被自己刷新），
     * Agent 那边拿到的就可能是几秒前的画面 —— **比拿不到图更糟**。
     */
    private fun captureFreshFrameOnly(): Bitmap? = synchronized(frameLock) { takeFrameLocked() }

    /** 取新帧并刷新最近帧缓存；返回 null 表示"这一轮没有新帧"。 */
    private fun takeFrameLocked(): Bitmap? {
        val fresh = captureLatestFrameLocked() ?: return null
        lastFrame = fresh
        lastFrameAtMs = SystemClock.elapsedRealtime()
        return fresh
    }

    /** 最近一帧的副本（为空或超龄则 null）。 */
    private fun cachedFrameCopyLocked(): Bitmap? {
        val cached = lastFrame ?: return null
        if (cached.isRecycled) return null
        val ageMs = SystemClock.elapsedRealtime() - lastFrameAtMs
        if (ageMs > LAST_FRAME_MAX_AGE_MS) return null
        DebugLogger.d(TAG, "没有新帧，回退到最近一帧（${ageMs}ms 前）")
        return cached.copy(Bitmap.Config.ARGB_8888, false)
    }

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
     * fork：注册一个预览帧监听者。
     *
     * 第一个监听者到来时启动取帧循环；只应在**虚拟屏**场景注册（主屏场景没有会话，取帧恒为 null）。
     * 契约见 [previewListeners] 的注释（主线程回调 / 不得 recycle）。
     */
    fun addPreviewListener(listener: (Bitmap) -> Unit) {
        previewListeners.add(listener)
        if (previewJob == null) startPreviewLoop()
    }

    /** fork：注销监听者。最后一个走掉时停掉取帧循环。 */
    fun removePreviewListener(listener: (Bitmap) -> Unit) {
        previewListeners.remove(listener)
        if (previewListeners.isEmpty()) stopPreview()
    }

    private fun startPreviewLoop() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        previewScope = scope
        previewJob = scope.launch {
            while (isActive) {
                delay(PREVIEW_FRAME_INTERVAL_MS)
                // ⚠️ 预览只吃**新**帧（不吃缓存）—— 否则会把陈旧帧一遍遍刷成"新鲜"
                val frame = captureFreshFrameOnly() ?: continue
                if (!isActive) {
                    frame.recycle()
                    break
                }
                // 发布在主线程：与 ImageView 的绘制同一条线程 ⇒ 回收上一帧不会与绘制交错
                withContext(Dispatchers.Main) {
                    val listeners = previewListeners.toList()
                    if (listeners.isEmpty()) {
                        frame.recycle()
                        return@withContext
                    }
                    listeners.forEach { listener ->
                        try {
                            listener(frame)
                        } catch (e: Exception) {
                            // 单个监听者抛异常不能拖死整个预览（否则表现为「预览静默冻结」）
                            DebugLogger.e(TAG, "预览帧回调失败", e)
                        }
                    }
                    // ⚠️ **刻意不回收上一帧**：订阅方（ImageView / Compose Image）可能还引用着它，
                    //    而"什么时候不再引用"在主线程上**无法可靠观测** —— Compose 的重组时机不在我们手里，
                    //    提前回收就是 use-after-recycle 崩溃。代价是每帧一个 ≈10 MB 的大对象垃圾
                    //    （4 fps），交给 GC；嫌重就调大 PREVIEW_FRAME_INTERVAL_MS。
                }
            }
        }
    }

    /**
     * fork：停止预览。可重复调用；[releaseLocked] 也会调它。
     *
     * 帧的回收策略与发布路径一致：**谁都不 recycle，交给 GC**（理由见发布处的注释）。
     */
    fun stopPreview() {
        previewJob?.cancel()
        previewJob = null
        previewScope?.cancel()
        previewScope = null
        previewListeners.clear()
    }
}
