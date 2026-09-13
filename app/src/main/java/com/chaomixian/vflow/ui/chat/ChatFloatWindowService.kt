package com.chaomixian.vflow.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.chaomixian.vflow.ui.main.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Chat 悬浮窗（折叠态）。
 *
 * 所有实现细节遵循 P0 真机验证结论（见 `docs/fork/chat-float-window-design.md` §9.1），
 * 尤其是以下几条**踩过坑**的约束，改动时不要回退：
 *
 *  1. `ViewTreeLifecycleOwner` / `SavedStateRegistryOwner` 必须挂在**父容器**上
 *     （挂 ComposeView 自身会在 attachedToWindow 抛 `ViewTreeLifecycleOwner not found`）。
 *  2. 窗口 flags 用 `LAYOUT_IN_SCREEN | NOT_TOUCH_MODAL`：可接收输入，且**超出边界的
 *     触摸放行给下层 App**。切勿退回 `FLAG_NOT_FOCUSABLE`（死锁）或只留
 *     `LAYOUT_IN_SCREEN`（模态，背景点不动）。
 *  3. **不得再用原生 View 叠在 ComposeView 之上**（如拖动把手）——`FrameLayout.addView`
 *     默认 `MATCH_PARENT` 会铺满窗口、吞掉全部触摸。
 */
class ChatFloatWindowService : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var rootView: FrameLayout? = null
    private var params: WindowManager.LayoutParams? = null
    private var isExpanded = false

    /** Compose 侧订阅的 UI 状态。 */
    private var panelState by mutableStateOf<ChatFloatPanelUiState?>(null)

    private var screenWidth = 0
    private var screenHeight = 0

    /**
     * 是否向上展开（窄条贴在屏幕下半部分时为 true）。
     *
     * 拖动结束时判定一次，展开/折叠都沿用。由它决定锚定哪条边：
     * 向上展开时保持**底边**不动，向下展开时保持**顶边**不动。
     */
    private var anchorAtBottom = true

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var observeJob: Job? = null

    companion object {
        private const val TAG = "ChatFloatWindow"
        const val ACTION_SHOW = "com.chaomixian.vflow.ACTION_SHOW_CHAT_FLOAT"
        const val ACTION_HIDE = "com.chaomixian.vflow.ACTION_HIDE_CHAT_FLOAT"
        private const val CHANNEL_ID = "vflow_chat_float"
        private const val NOTIFICATION_ID = 97010

        /** 展开时距屏幕底部保留的边距，避免贴死在导航栏上。 */
        private const val BOTTOM_MARGIN_DP = 24


        /** 展开时距屏幕顶部的边距。 */
        private const val TOP_MARGIN_DP = 24

        private const val COLLAPSED_WIDTH_DP = 292
        private const val COLLAPSED_HEIGHT_DP = 50
        private const val EXPANDED_WIDTH_DP = 344
        private const val EXPANDED_HEIGHT_DP = 480
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideWindow()
                return START_NOT_STICKY
            }

            ACTION_SHOW -> {
                startForegroundSafely()
                if (rootView == null) {
                    showWindow()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // 前台服务
    // ------------------------------------------------------------------

    /** 提升为前台服务，避免 Agent 运行期间进程被回收。 */
    private fun startForegroundSafely() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            getString(R.string.chat_float_channel_name),
                            NotificationManager.IMPORTANCE_LOW,
                        )
                    )
                }
            }

            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )

            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.chat_float_notification_title))
                .setContentText(getString(R.string.chat_float_notification_text))
                .setSmallIcon(R.drawable.rounded_branding_watermark_24)
                .setContentIntent(contentIntent)
                .build()

            startForeground(NOTIFICATION_ID, notification)
        } catch (t: Throwable) {
            // 通知权限被拒等情况不应阻止悬浮窗显示
            DebugLogger.w(TAG, "startForeground 失败，降级为普通服务: ${t.message}")
        }
    }

    // ------------------------------------------------------------------
    // 窗口
    // ------------------------------------------------------------------

    private fun showWindow() {
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels

        val themedContext = ThemeUtils.createThemedContext(this)

        // ⚠️ owner 必须挂在父容器（P0 教训 1）
        val container = FrameLayout(themedContext).apply {
            setViewTreeLifecycleOwner(this@ChatFloatWindowService)
            setViewTreeSavedStateRegistryOwner(this@ChatFloatWindowService)
        }

        val composeView = ComposeView(themedContext).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                panelState?.let { state ->
                    ChatFloatPanelContent(
                        state = state,
                        onDrag = ::handleDrag,
                        onDragEnd = ::snapToEdge,
                        onExpand = { setExpanded(true) },
                        onCollapse = { setExpanded(false) },
                        onClose = { hideWindow() },
                    )
                }
            }
        }
        container.addView(composeView)

        // ⚠️ flags：可输入 + 触摸穿透（P0 教训 2）
        params = WindowManager.LayoutParams(
            dpToPx(COLLAPSED_WIDTH_DP),
            dpToPx(COLLAPSED_HEIGHT_DP),
            overlayType(),
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dpToPx(16)
            y = (screenHeight * 0.62f).toInt()
        }

        try {
            windowManager.addView(container, params)
            rootView = container
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            observeViewModel()
            params?.let { updateAnchor(it) }
            setExpanded(false)
            DebugLogger.i(TAG, "悬浮窗已显示 ${screenWidth}x$screenHeight，anchorBottom=$anchorAtBottom")
        } catch (t: Throwable) {
            DebugLogger.e(TAG, "addView 失败: ${t.message}", t)
            stopSelf()
        }
    }

    private fun overlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            @Suppress("DEPRECATION")
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    /**
     * 展开 / 折叠。
     *
     * 保持 `gravity = TOP|START`（原点即左上角），由 `ChatFloatGeometry.resize`
     * 手工锚定：向上展开时底边不动、向下展开时顶边不动。
     *
     * **瞬时切换，不做逐帧动画** —— overlay 窗口在逐帧同时改「位置+尺寸」时
     * 渲染管线会掉帧，产生错配帧（实测表现为闪烁）。一次到位就没有中间帧。
     */
    private fun setExpanded(expanded: Boolean) {
        isExpanded = expanded
        val p = params ?: return
        val view = rootView ?: return

        val current = ChatFloatGeometry.Bounds(p.x, p.y, p.width, p.height)
        val targetWidth = dpToPx(if (expanded) EXPANDED_WIDTH_DP else COLLAPSED_WIDTH_DP)
            .coerceAtMost(screenWidth.coerceAtLeast(1))
        val targetHeight = dpToPx(if (expanded) EXPANDED_HEIGHT_DP else COLLAPSED_HEIGHT_DP)
            .coerceAtMost(screenHeight.coerceAtLeast(1))

        val next = ChatFloatGeometry.resize(
            bounds = current,
            newWidth = targetWidth,
            newHeight = targetHeight,
            hAnchor = ChatFloatGeometry.HAnchor.LEFT,
            vAnchor = if (anchorAtBottom) {
                ChatFloatGeometry.VAnchor.BOTTOM
            } else {
                ChatFloatGeometry.VAnchor.TOP
            },
            screenWidth = screenWidth,
            screenHeight = screenHeight,
        )

        p.width = next.width
        p.height = next.height
        p.x = next.left
        p.y = next.top

        DebugLogger.d(
            TAG,
            "${if (expanded) "展开" else "折叠"} up=$anchorAtBottom " +
                "(${current.left},${current.top})${current.width}x${current.height} → " +
                "(${next.left},${next.top})${next.width}x${next.height}"
        )

        updatePanelState()
        runCatching { windowManager.updateViewLayout(view, p) }
            .onFailure { DebugLogger.w(TAG, "updateViewLayout 失败: ${it.message}") }
    }

    /** 按窗口中心位置判定向上/向下展开（首次显示时调用）。 */
    private fun updateAnchor(p: WindowManager.LayoutParams) {
        anchorAtBottom = (p.y + p.height / 2) > screenHeight / 2
    }

    /** Compose 拖动回调：位移写回窗口位置（可自由拖到屏幕任意位置）。 */
    private fun handleDrag(dx: Float, dy: Float) {
        val p = params ?: return
        val view = rootView ?: return
        p.x = (p.x + dx).toInt().coerceIn(0, (screenWidth - p.width).coerceAtLeast(0))
        p.y = (p.y + dy).toInt().coerceIn(0, (screenHeight - p.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    /**
     * 拖动结束：吸附到最近的左右边缘；纵向保留用户拖到的位置（可到底部）。
     * 同时按窗口中心位置判定向上/向下展开。
     */
    private fun snapToEdge() {
        val p = params ?: return
        val view = rootView ?: return
        val half = screenWidth / 2
        p.x = if (p.x + p.width / 2 < half) 0 else (screenWidth - p.width)
        p.y = p.y.coerceIn(0, (screenHeight - p.height).coerceAtLeast(0))

        anchorAtBottom = (p.y + p.height / 2) > screenHeight / 2

        runCatching { windowManager.updateViewLayout(view, p) }
            .onFailure { DebugLogger.w(TAG, "吸附失败: ${it.message}") }
        DebugLogger.d(TAG, "吸附到 x=${p.x} y=${p.y} up=$anchorAtBottom")
    }

    // ------------------------------------------------------------------
    // 状态映射
    // ------------------------------------------------------------------

    /**
     * 从 `ChatViewModel` 推导折叠态显示内容。
     *
     * 这是 P1 的关键点：窄条显示 **AI 说的内容**，不是状态灯（设计文档 §1.1）。
     */
    private fun updatePanelState() {
        val vm = runCatching { ChatViewModelHolder.get(application) }.getOrNull()
        val uiState = vm?.uiState?.value

        val conversation = uiState?.let { state ->
            state.conversations.firstOrNull { it.id == state.activeConversationId }
        }
        val messages = conversation?.messages.orEmpty()
        val hasPendingApproval = messages.any { message ->
            message.role == ChatMessageRole.ASSISTANT &&
                message.toolApprovalState == ChatToolApprovalState.PENDING
        }
        val isAgentRunning = uiState?.isAgentRunning == true || uiState?.isSending == true

        val summary = ChatFloatSummary.derive(
            messages = messages,
            isAgentRunning = isAgentRunning,
            hasPendingApproval = hasPendingApproval,
            runningHintText = getString(R.string.chat_float_status_running),
            errorFallback = getString(R.string.chat_float_status_idle),
        )

        val presetName = uiState?.presets
            ?.firstOrNull { it.id == conversation?.presetId }
            ?.name
            .orEmpty()

        panelState = ChatFloatPanelUiState(
            expanded = isExpanded,
            summary = summary,
            hasPendingApproval = hasPendingApproval,
            headerTitle = presetName.ifBlank { getString(R.string.chat_float_header_title) },
            approvalBadgeText = getString(R.string.chat_float_badge_approval),
            expandedHint = getString(R.string.chat_float_expanded_hint),
            anchoredAtBottom = anchorAtBottom,
        )
    }

    /** 订阅共享 VM 的 UI 状态，变化即刷新折叠态内容。 */
    private fun observeViewModel() {
        observeJob?.cancel()
        observeJob = serviceScope.launch {
            val vm = runCatching { ChatViewModelHolder.get(application) }.getOrNull() ?: return@launch
            vm.uiState.collect {
                updatePanelState()
            }
        }
    }

    // ------------------------------------------------------------------
    // 清理
    // ------------------------------------------------------------------

    private fun hideWindow() {
        observeJob?.cancel()
        rootView?.let {
            runCatching { windowManager.removeView(it) }
        }
        rootView = null
        params = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        DebugLogger.i(TAG, "悬浮窗已关闭")
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 旋转/折叠后屏幕尺寸变化，重新夹取位置（设计文档 §10 #12）
        val metrics = resources.displayMetrics
        screenWidth = metrics.widthPixels
        screenHeight = metrics.heightPixels
        val p = params ?: return
        val view = rootView ?: return
        p.x = p.x.coerceIn(0, (screenWidth - p.width).coerceAtLeast(0))
        p.y = p.y.coerceIn(0, (screenHeight - p.height).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    override fun onDestroy() {
        observeJob?.cancel()
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private fun dpToPx(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()
}
