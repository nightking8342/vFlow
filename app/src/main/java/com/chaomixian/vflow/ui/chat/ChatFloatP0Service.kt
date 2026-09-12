package com.chaomixian.vflow.ui.chat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import com.chaomixian.vflow.ui.common.VFlowTheme
import java.lang.ref.WeakReference
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * P0 技术验证 Service —— 临时文件，验证完成后删除。
 *
 * 在一个 overlay 窗口里同时验证：
 *  ① ChatViewModelHolder 共享（与 Activity 取到同一实例）
 *  ② ComposeView 在 Service 中渲染
 *  ③ 去 FLAG_NOT_FOCUSABLE 后 Compose 输入框能聚焦 + 弹键盘（+ FocusRequester 重申请）
 *  ⑤ 折叠/展开 add-remove 时 composition 状态是否保留
 *  ⑥ startForeground(specialUse) 在 API 37 上是否抛异常
 */
class ChatFloatP0Service : Service(), LifecycleOwner, SavedStateRegistryOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    private lateinit var windowManager: WindowManager
    private var rootView: View? = null
    private var composeView: ComposeView? = null
    private var params: WindowManager.LayoutParams? = null
    private var isFocusableMode = false
    private var isExpanded = false
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 焦点态 flag 组合对照记录（P0 已在真机同环境实测三组）：
     *
     *  · `LAYOUT_IN_SCREEN`                        → IME ✅ / 触摸穿透 ❌（吞掉背景触摸）
     *  · `LAYOUT_IN_SCREEN | NOT_TOUCH_MODAL`      → IME ✅ / 触摸穿透 ✅ **【采用】**
     *  · `NOT_FOCUSABLE | ALT_FOCUSABLE_IM`        → IME ❌（HyperOS 上无效，与官方文档不符）
     *
     * 详见设计文档 §9.1 修正②。
     */

    /** 供 Compose 侧订阅的「请聚焦」信号：adb 触发时由 Service 置位。 */
    private val focusRequestFlow = MutableStateFlow(0L)
    val focusRequest: StateFlow<Long> = focusRequestFlow.asStateFlow()

    /** 供 Activity 侧对比是否为同一 VM 实例。 */
    companion object {
        const val ACTION_SHOW = "com.chaomixian.vflow.P0_SHOW"
        const val ACTION_HIDE = "com.chaomixian.vflow.P0_HIDE"
        const val ACTION_TOGGLE_FOCUS = "com.chaomixian.vflow.P0_TOGGLE_FOCUS"
        const val ACTION_EXPAND = "com.chaomixian.vflow.P0_EXPAND"
        const val ACTION_REQUEST_IME = "com.chaomixian.vflow.P0_REQUEST_IME"
        const val ACTION_REMOVE_READD = "com.chaomixian.vflow.P0_REMOVE_READD"
        const val ACTION_SET_FLAG_MODE = "com.chaomixian.vflow.P0_SET_FLAG_MODE"
        const val EXTRA_FLAG_MODE = "flag_mode"
        const val EXTRA_ACTIVITY_VM_IDENTITY = "activity_vm_identity"
        private const val CHANNEL_ID = "vflow_p0_probe"
        private const val NOTIFICATION_ID = 97001

        @Volatile
        var lastVmIdentity: String? = null
            private set

        @Volatile
        var serviceRef: WeakReference<ChatFloatP0Service>? = null
            private set
    }

    override fun onCreate() {
        super.onCreate()
        savedStateController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
        serviceRef = WeakReference(this)
        ChatFloatP0Probe.info("s0", "Service 生命周期", "onCreate 完成，lifecycle=CREATED")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_HIDE -> {
                hideWindow()
                return START_NOT_STICKY
            }

            ACTION_TOGGLE_FOCUS -> {
                setWindowFocusable(!isFocusableMode)
                ChatFloatP0Probe.info(
                    "s0",
                    "焦点模式手动切换",
                    "isFocusable=$isFocusableMode（供 Activity 侧对比键盘行为）"
                )
                return START_STICKY
            }

            ACTION_EXPAND -> {
                if (!isExpanded) toggleExpand()
                return START_STICKY
            }

            ACTION_REQUEST_IME -> {
                // 模拟「用户点击输入框」的效果：让 Compose 输入框 requestFocus。
                // 窗口已是可聚焦态（创建时即设定），拿到焦点后 onFocusChanged 会触发，
                // 走与真实点击**完全相同**的路径（见 P0ProbeContent.onRequestFocus）。
                requestComposeFocus()
                mainHandler.postDelayed({ reportImeState() }, 500)
                return START_STICKY
            }

            ACTION_REMOVE_READD -> {
                // ⑤ 验证 composition 是否在 remove→add 后存活
                if (isExpanded) toggleExpand()
                return START_STICKY
            }
        }

        // ⑥ 前台服务：在 API 37 上验证 startForeground(specialUse) 是否抛异常
        probeForegroundStart()

        val activityVmIdentity = intent?.getStringExtra(EXTRA_ACTIVITY_VM_IDENTITY)
        probeViewModelSharing(activityVmIdentity)

        if (rootView == null) {
            showWindow()
        }

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------------
    // ⑥ 前台服务
    // ------------------------------------------------------------------
    private fun probeForegroundStart() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                if (manager.getNotificationChannel(CHANNEL_ID) == null) {
                    manager.createNotificationChannel(
                        NotificationChannel(
                            CHANNEL_ID,
                            "P0 Probe",
                            NotificationManager.IMPORTANCE_LOW
                        )
                    )
                }
            }
            val notification: Notification = Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("P0 Probe 运行中")
                .setContentText("验证 specialUse 前台服务")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
            startForeground(NOTIFICATION_ID, notification)
            ChatFloatP0Probe.pass(
                "P6",
                "前台服务 startForeground",
                "API ${Build.VERSION.SDK_INT}：未抛异常，已进入前台"
            )
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail(
                "P6",
                "前台服务 startForeground",
                "抛异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    // ------------------------------------------------------------------
    // ① VM 共享
    // ------------------------------------------------------------------
    private fun probeViewModelSharing(activityVmIdentity: String?) {
        try {
            val vm = ChatViewModelHolder.get(application)
            val serviceIdentity = System.identityHashCode(vm).toString()
            lastVmIdentity = serviceIdentity
            if (activityVmIdentity == null) {
                ChatFloatP0Probe.info(
                    "P1",
                    "VM 共享",
                    "Service 侧 vm@$serviceIdentity（未收到 Activity 侧身份，无法比对）"
                )
            } else if (activityVmIdentity == serviceIdentity) {
                ChatFloatP0Probe.pass(
                    "P1",
                    "VM 共享",
                    "Activity 与 Service 同一实例 vm@$serviceIdentity"
                )
            } else {
                ChatFloatP0Probe.fail(
                    "P1",
                    "VM 共享",
                    "实例不同！activity@$activityVmIdentity service@$serviceIdentity"
                )
            }
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail(
                "P1",
                "VM 共享",
                "取 VM 抛异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    // ------------------------------------------------------------------
    // ② ComposeView 承载 + ③ IME + ⑤ 折叠展开
    // ------------------------------------------------------------------
    private fun showWindow() {
        val (width, height) = windowSize()
        params = WindowManager.LayoutParams(
            width,
            height,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            },
            // ⚠️ 必须从一开始就可聚焦（P0 实测的关键修正）：
            //    若创建时带 FLAG_NOT_FOCUSABLE，Compose 输入框**永远拿不到焦点**，
            //    onFocusChanged 不触发 → 无法「点击时才切 focusable」——这是个死锁。
            //    用「真聚焦 + NOT_TOUCH_MODAL」：可输入，且窗口外的触摸放行给下层。
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 40
            y = 320
            // 官方建议：overlay 要键盘时配 ADJUST_RESIZE，让内容随键盘调整
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }

        // ⚠️ 关键（P0 实测踩到的坑）：
        // ViewTreeLifecycleOwner 的查找是从 View 的**父链向上**遍历的，
        // 挂在 ComposeView 自己身上无效 —— attachedToWindow 时会抛
        // "ViewTreeLifecycleOwner not found"。必须设在**父容器**上。
        // 诊断探针已移除（P0 完成）。保留教训见 ChatFloatP0Service 注释与设计文档 §9.1。
        val container = FrameLayout(this).apply {
            setViewTreeLifecycleOwner(this@ChatFloatP0Service)
            setViewTreeSavedStateRegistryOwner(this@ChatFloatP0Service)
        }
        val compose = ComposeView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            // ⑤ 关键：折叠/展开会 remove→add 同一个 ComposeView，
            //    用默认策略会因 detach 而 dispose composition，导致输入文本丢失。
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent {
                VFlowTheme {
                    P0ProbeContent(
                        focusRequest = focusRequest,
                        onRequestFocus = { wantFocus ->
                            if (wantFocus) {
                                // 窗口已是可聚焦态，直接请求 IME（真实点击路径）
                                mainHandler.postDelayed({ requestImeForFocusedView() }, 120)
                            } else {
                                hideIme()
                            }
                        },
                        onToggleExpand = { toggleExpand() },
                        onReportComposeReady = {
                            ChatFloatP0Probe.pass(
                                "P2",
                                "ComposeView 在 Service 中渲染",
                                "setContent 已执行，composition 完成"
                            )
                        },
                    )
                }
            }
        }
        composeView = compose
        container.addView(compose)

        // 拖动把手（验证 overlay 触摸）
        // ⚠️ 必须是 WRAP_CONTENT 的小把手，不能让它铺满容器 ——
        //    FrameLayout.addView 默认给 MATCH_PARENT，会盖在 ComposeView 之上
        //    吞掉全部触摸（P0 实测踩到的布局 bug：点击全部变成拖动）。
        val dragHandle = TextView(this).apply {
            text = "⠿"
            textSize = 16f
            setTextColor(0xFF6750A4.toInt())
            setPadding(24, 8, 24, 8)
            setOnTouchListener(DragTouchListener())
        }
        container.addView(
            dragHandle,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.TOP or android.view.Gravity.START
            )
        )

        try {
            windowManager.addView(container, params)
            rootView = container
            lifecycleRegistry.currentState = Lifecycle.State.RESUMED
            ChatFloatP0Probe.pass(
                "P2",
                "窗口创建",
                "addView 成功 (${width}x${height}px)，lifecycle=RESUMED"
            )
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail(
                "P2",
                "窗口创建",
                "addView 抛异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    private fun windowSize(): Pair<Int, Int> {
        val density = resources.displayMetrics.density
        // 折叠 300x64dp；展开 340x420dp
        return if (isExpanded) {
            (340 * density).toInt() to (420 * density).toInt()
        } else {
            (300 * density).toInt() to (64 * density).toInt()
        }
    }

    /**
     * ⑤ 折叠/展开：执行 **remove → add 同一个 View**（参照实现 `WorkflowsFloatPanelService`
     * 的真实切换方式，也是最激进的情况）。
     *
     * 判据：切换后第二个输入框（rememberSaveable 保存）的内容是否还在。
     *  - 文本保留 → composition 未被 dispose（`DisposeOnViewTreeLifecycleDestroyed` 生效）
     *  - 文本清空 → composition 被 dispose，双栈方案需要调整
     */
    private fun toggleExpand() {
        val container = rootView ?: return
        val p = params ?: return
        isExpanded = !isExpanded
        val (w, h) = windowSize()
        p.width = w
        p.height = h
        val mode = if (isExpanded) "展开" else "折叠"

        try {
            windowManager.removeView(container)
            windowManager.addView(container, p)
            val attached = composeView?.isAttachedToWindow == true
            ChatFloatP0Probe.pass(
                "P5",
                "折叠/展开 remove→add",
                "切为 $mode ${w}x${h}px，重挂载成功，ComposeView attached=$attached" +
                    "（请核对⑤输入框文本是否保留）"
            )
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail(
                "P5",
                "折叠/展开 remove→add",
                "抛异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    /** 更温和的对照：只改 LayoutParams，不 remove/add。 */
    fun probeUpdateLayoutParams() {
        val container = rootView ?: return
        val p = params ?: return
        try {
            windowManager.updateViewLayout(container, p)
            ChatFloatP0Probe.pass(
                "P5b",
                "updateViewLayout（对照）",
                "仅改宽高，未 remove/add"
            )
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail(
                "P5b",
                "updateViewLayout（对照）",
                "抛异常：${t.javaClass.simpleName}: ${t.message}"
            )
        }
    }

    // ------------------------------------------------------------------
    // ③ 焦点与 IME
    // ------------------------------------------------------------------
    /**
     * 焦点态管理。
     *
     * ⚠️ **P0 关键修正**：窗口**始终**保持「可聚焦 + 触摸穿透」，失焦时也**不退回
     * `FLAG_NOT_FOCUSABLE`** —— 否则 Compose 输入框永远拿不到焦点，形成死锁：
     *
     * ```
     * NOT_FOCUSABLE → 点输入框拿不到焦点 → onFocusChanged 不触发
     *              → 无法切 focusable → 永远 NOT_FOCUSABLE
     * ```
     *
     * 因此这里只负责**收起键盘**，不再改窗口 flags。
     * 真正的 flags 组合在 `showWindow()` 创建时一次性设定。
     */
    private fun setWindowFocusable(@Suppress("UNUSED_PARAMETER") focusable: Boolean) {
        // 保持可聚焦：不改 flags，仅在被要求失焦时收起键盘
        if (!focusable) {
            hideIme()
        }
        ChatFloatP0Probe.info(
            "P3",
            "焦点态（不改 flags）",
            "请求 focusable=$focusable，窗口始终保持 LAYOUT_IN_SCREEN|NOT_TOUCH_MODAL"
        )
    }

    /** 让 Compose 侧的输入框主动 requestFocus（adb 触发用）。 */
    private fun requestComposeFocus() {
        focusRequestFlow.value = System.currentTimeMillis()
    }

    /**
     * 主动请求 IME —— 由 Compose 输入框**真正获得焦点后**回调触发
     * （即真实点击路径，不再是 adb 硬调）。
     *
     * 窗口创建时即为「可聚焦 + NOT_TOUCH_MODAL」，因此输入框能正常拿到焦点，
     * `showSoftInput` 可成功（P0 实测）。
     */
    fun requestImeForFocusedView() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val target = rootView?.findFocus() ?: composeView
            val ok = target?.let { imm.showSoftInput(it, InputMethodManager.SHOW_IMPLICIT) }
            ChatFloatP0Probe.record(
                "P3",
                "点击输入框弹键盘",
                if (ok == true) ChatFloatP0Probe.Status.PASS else ChatFloatP0Probe.Status.FAIL,
                "showSoftInput=$ok isAcceptingText=${imm.isAcceptingText} " +
                    "target=${target?.javaClass?.simpleName}"
            )
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail("P3", "点击输入框弹键盘", "抛异常：${t.message}")
        }
    }

    /** 仅上报当前 IME 状态（供 adb 触发的自检）。 */
    private fun reportImeState() {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        ChatFloatP0Probe.info(
            "P3",
            "IME 状态自检",
            "isAcceptingText=${imm.isAcceptingText}"
        )
    }

    @Deprecated("改用 showImeRobust")
    private fun showIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            val target = composeView ?: return
            val ok = imm.showSoftInput(target, InputMethodManager.SHOW_IMPLICIT)
            ChatFloatP0Probe.info("P3", "showSoftInput(old)", "返回=$ok")
        } catch (t: Throwable) {
            ChatFloatP0Probe.fail("P3", "showSoftInput", "抛异常：${t.message}")
        }
    }

    private fun hideIme() {
        try {
            val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            composeView?.windowToken?.let { imm.hideSoftInputFromWindow(it, 0) }
        } catch (_: Throwable) {
        }
    }

    /** 由 Compose 侧在输入框获得/失去焦点时回调，用于验证键盘与焦点联动。 */
    fun onComposeFocusChanged(hasFocus: Boolean) {
        ChatFloatP0Probe.pass(
            "P3",
            "Compose 输入框焦点",
            "hasFocus=$hasFocus（窗口 focusable=$isFocusableMode）"
        )
    }

    // ------------------------------------------------------------------
    // ⑦ screencap 是否拍进 overlay
    // ------------------------------------------------------------------
    fun probeScreencap() {
        Thread {
            try {
                val workDir = java.io.File(cacheDir, "p0").apply { mkdirs() }
                val outFile = java.io.File(workDir, "shot.png")
                val process = ProcessBuilder("sh", "-c", "screencap -p ${outFile.absolutePath}")
                    .redirectErrorStream(true)
                    .start()
                val exit = process.waitFor()
                val size = if (outFile.exists()) outFile.length() else 0L
                if (exit == 0 && size > 0) {
                    ChatFloatP0Probe.info(
                        "P7",
                        "screencap 截图",
                        "成功 exit=$exit size=${size}B path=${outFile.absolutePath} —— 需人工核对悬浮窗是否入镜"
                    )
                } else {
                    ChatFloatP0Probe.fail(
                        "P7",
                        "screencap 截图",
                        "exit=$exit size=$size（应用内直接 screencap 可能被 SELinux 限制，属预期）"
                    )
                }
            } catch (t: Throwable) {
                ChatFloatP0Probe.fail(
                    "P7",
                    "screencap 截图",
                    "抛异常：${t.javaClass.simpleName}: ${t.message}"
                )
            }
        }.start()
    }

    // ------------------------------------------------------------------
    private fun hideWindow() {
        rootView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Throwable) {
            }
        }
        rootView = null
        composeView = null
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        val dm = resources.displayMetrics
        ChatFloatP0Probe.info(
            "s0",
            "配置变化（旋转）",
            "screen=${dm.widthPixels}x${dm.heightPixels}，窗口 x=${params?.x} y=${params?.y}"
        )
    }

    override fun onDestroy() {
        ChatFloatP0Probe.info("s0", "Service 生命周期", "onDestroy")
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    private inner class DragTouchListener : View.OnTouchListener {
        private var initialX = 0
        private var initialY = 0
        private var initialTouchX = 0f
        private var initialTouchY = 0f
        private var dragging = false
        private val touchSlop = ViewConfiguration.get(this@ChatFloatP0Service).scaledTouchSlop

        override fun onTouch(v: View, event: MotionEvent): Boolean {
            val p = params ?: return false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = p.x; initialY = p.y
                    initialTouchX = event.rawX; initialTouchY = event.rawY
                    dragging = false
                    return true
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - initialTouchX
                    val dy = event.rawY - initialTouchY
                    if (!dragging && (kotlin.math.abs(dx) > touchSlop || kotlin.math.abs(dy) > touchSlop)) {
                        dragging = true
                    }
                    if (dragging) {
                        p.x = initialX + dx.toInt()
                        p.y = initialY + dy.toInt()
                        rootView?.let { windowManager.updateViewLayout(it, p) }
                    }
                    return true
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        ChatFloatP0Probe.pass(
                            "P2",
                            "overlay 拖动",
                            "拖到 x=${p.x} y=${p.y}"
                        )
                    }
                    return true
                }
            }
            return false
        }
    }
}

/** P0 验证内容 UI：折叠态显示 AI 文本，展开态含输入框。 */
@Composable
private fun P0ProbeContent(
    focusRequest: StateFlow<Long>,
    onRequestFocus: (Boolean) -> Unit,
    onToggleExpand: () -> Unit,
    onReportComposeReady: () -> Unit,
) {
    // ⑤ 验证：rememberSaveable 的文本在 remove→add 后是否保留
    var savedText by rememberSaveable { mutableStateOf("") }
    var plainText by remember { mutableStateOf("") }
    var hasFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        ready = true
        onReportComposeReady()
    }

    // ③ adb 触发：收到「请聚焦」信号后主动 requestFocus（Compose 方案必须补的一步）
    val focusSignal by focusRequest.collectAsState()
    LaunchedEffect(focusSignal) {
        if (focusSignal > 0L) {
            runCatching { focusRequester.requestFocus() }
            ChatFloatP0Probe.info("P3", "Compose focusRequester", "已请求焦点 signal=$focusSignal")
        }
    }

    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xF2FCFAFF),
        shape = RoundedCornerShape(18.dp),
        shadowElevation = 8.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "P0 验证窗",
                    style = MaterialTheme.typography.labelLarge,
                    color = Color(0xFF4F3D8A),
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = "Compose 渲染 OK=$ready",
                    fontSize = 11.sp,
                    color = Color(0xFF2E6B4F),
                )
                Text(
                    text = "切换形态",
                    fontSize = 11.sp,
                    color = Color(0xFF6750A4),
                    modifier = Modifier.padding(start = 8.dp)
                        .background(Color(0x1F6750A4), RoundedCornerShape(6.dp))
                        .clickable { onToggleExpand() }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }

            Text(
                text = "场景：没找到深色模式，我去「显示」里找",
                fontSize = 13.sp,
                color = Color(0xFF221F28),
            )

            // ③ 输入框：聚焦时请求窗口切 focusable + 弹键盘
            BasicTextField(
                value = plainText,
                onValueChange = { plainText = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 13.sp, color = Color(0xFF1C1B1F)),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFF0EEF6), RoundedCornerShape(18.dp))
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        if (state.isFocused != hasFocus) {
                            hasFocus = state.isFocused
                            onRequestFocus(state.isFocused)
                        }
                    },
                decorationBox = { inner ->
                    if (plainText.isEmpty()) {
                        Text("点这里测试输入与键盘…", fontSize = 13.sp, color = Color(0xFF8A8695))
                    }
                    inner()
                },
            )

            // ⑤ 验证项：此框内容用 rememberSaveable 保存。
            //    点「切换形态」触发 remove→add 后，若文本仍在 → composition 未被 dispose。
            BasicTextField(
                value = savedText,
                onValueChange = { savedText = it },
                singleLine = true,
                textStyle = TextStyle(fontSize = 12.sp, color = Color(0xFF7D5260)),
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x14FFD8E4), RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                decorationBox = { inner ->
                    if (savedText.isEmpty()) {
                        Text("⑤ 在此输入，然后点「切换形态」看是否保留", fontSize = 11.sp, color = Color(0xFF9A96A6))
                    }
                    inner()
                },
            )

            Text(
                text = "rememberSaveable=\"$savedText\" · plain=\"$plainText\" · focus=$hasFocus",
                fontSize = 10.sp,
                color = Color(0xFF8A8695),
            )
        }
    }
}

