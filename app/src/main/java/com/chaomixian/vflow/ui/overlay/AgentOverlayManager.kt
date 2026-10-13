// 文件: main/java/com/chaomixian/vflow/ui/overlay/AgentOverlayManager.kt
package com.chaomixian.vflow.ui.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.util.DisplayMetrics
import android.view.ContextThemeWrapper
import android.view.Gravity
import com.chaomixian.vflow.ui.common.ThemeUtils
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 管理 Agent 执行时的视觉反馈（彩虹边框 + 底部控制面板）。
 */
class AgentOverlayManager(
    private val context: Context,
    /** 模块显示名，进 [AgentRunMonitor] 供监视器展示。 */
    private val moduleLabel: String = "",
    private val maxSteps: Int = 0,
    /** 运行界面：`overlay`（悬浮窗，默认）/ `activity`（监视器页面）。见 [MONITOR_MODE_*]。 */
    private val monitorMode: String = MONITOR_MODE_OVERLAY,
) {

    companion object {
        /** 只显示悬浮窗（卡片里带虚拟屏预览）。 */
        const val MONITOR_MODE_OVERLAY = "overlay"

        /** 自动打开监视器页面（画面 + 完整思考流都在那边；悬浮窗退化成状态指示 + 兜底控制）。 */
        const val MONITOR_MODE_ACTIVITY = "activity"
    }

    /**
     * fork：是否需要在**悬浮窗里**画虚拟屏预览。
     *
     * `activity` 模式下画面由监视器页面负责 ⇒ 模块**不再注册预览监听者**
     * （省掉每帧 ≈10 MB 的位图拷贝；预览帧流本身支持多消费者，见 `VirtualDisplayManager`）。
     */
    val usesOverlayPreview: Boolean get() = monitorMode != MONITOR_MODE_ACTIVITY

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    // 全屏流光边框 (不可点击)
    private var borderRoot: FrameLayout? = null
    private var borderView: RainbowBorderView? = null
    private var borderAnimator: ValueAnimator? = null

    // 底部控制面板 (可点击)
    private var controlRoot: FrameLayout? = null
    private var statusText: TextView? = null
    private var actionText: TextView? = null
    private var btnPause: MaterialButton? = null
    private var btnCancel: MaterialButton? = null

    // fork：虚拟屏预览（方案 A）。
    // 虚拟屏模式下用户在主屏上看不到 AI 正在操作的屏幕，这里把每一帧贴到控制卡片里。
    private var previewView: ImageView? = null

    // 状态控制
    private var isCancelConfirming = false

    // fork：暂停/取消状态与 awaitState 闸门已搬到 [AgentRunMonitor]（监视 Activity 也要能控制），
    // 这里只做转发。`isPaused` 仍被 hideForScreenshot / restoreAfterScreenshot 用来判断边框显隐。
    private val isPaused: Boolean get() = AgentRunMonitor.isPaused

    val isCancelled: Boolean get() = AgentRunMonitor.isCancelled

    fun show() {
        if (borderRoot != null) return
        // fork：把本轮运行登记到总线 —— 监视 Activity 靠它判断"有没有在跑"、并拿到模块名与步数上限。
        AgentRunMonitor.begin(moduleLabel, maxSteps)

        // fork：`activity` 模式下**完全不显示悬浮窗**（连那张只有文字的卡片也不要）——
        // 画面与完整思考流都在监视器页面里，悬浮窗只剩干扰。
        //
        // ⚠️ 代价：这条路径下**没有可见窗口**了，`openMonitor()` 的后台启动更容易被系统拦下
        //    （BAL）。拦下时只记一条日志，用户看不到任何 UI —— 这是"不要悬浮窗"换来的。
        if (!usesOverlayPreview) {
            openMonitor()
            return
        }

        // 创建带有应用主题的 ContextWrapper（根据用户设置选择动态取色或默认主题）
        val themedContext = ThemeUtils.createThemedContext(context)

        // 初始化边框窗口 (Border)
        borderRoot = FrameLayout(themedContext).apply {
            fitsSystemWindows = false
            systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }

        // 创建彩虹边框 View
        borderView = RainbowBorderView(themedContext).apply {
            val cornerRadius = getScreenCornerRadius(themedContext)
            this.setCornerRadius(cornerRadius)
        }
        borderRoot?.addView(borderView, FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)

        val metrics = DisplayMetrics()
        windowManager.defaultDisplay.getRealMetrics(metrics)

        val borderParams = WindowManager.LayoutParams(
            metrics.widthPixels,
            metrics.heightPixels,
            getOverlayType(),
            // 边框层必须完全透传点击，不影响操作
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS,
            PixelFormat.TRANSLUCENT
        ).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        // 初始化控制窗口 (Control)
        controlRoot = FrameLayout(themedContext)

        val card = MaterialCardView(themedContext).apply {
            radius = 50f
            cardElevation = 10f
            // 稍带透明的 Surface 色，适配深色模式
            setCardBackgroundColor(Color.parseColor("#F2FFFFFF")) // 95% 白
            setContentPadding(48, 32, 48, 32)
        }

        // fork：卡片宽度改成**撑满（留边距）**，按钮各占 1/3。
        // ⚠️ 原先卡片是 WRAP_CONTENT + 三个带图标的按钮，合计 ≈406dp，**超过外屏的 ≈392dp** ⇒
        //    卡片居中后两侧被裁掉，**最右边的「监视器」按钮直接看不见**（真机实测：按钮没出现）。
        //    改成撑满 + weight 分配 + 纯文字之后，任何屏宽 / 任何文案长度都不会再溢出。
        val cardParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.CENTER
            marginStart = dp(12f)
            marginEnd = dp(12f)
        }

        // 内部垂直布局
        val contentLayout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        // fork：虚拟屏预览（方案 A）。
        // 默认 GONE —— 只有真的收到帧（`updatePreview`）才占位显示，主屏模式全程不可见。
        // 尺寸固定成 150×320dp（虚拟屏是 1080×2400，比例 9:20）而不是按帧比例自适应，
        // 这样卡片高度不会随帧到达与否跳动；FIT_CENTER 让可能的留白对称分布。
        previewView = ImageView(themedContext).apply {
            visibility = View.GONE
            scaleType = ImageView.ScaleType.FIT_CENTER
            background = GradientDrawable().apply {
                cornerRadius = dp(12f).toFloat()
                setColor(Color.parseColor("#14000000"))
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams(dp(150f), dp(320f)).apply {
                bottomMargin = dp(16f)
            }
        }

        // 状态文本
        statusText = TextView(themedContext).apply {
            textSize = 13f
            setTextColor(Color.DKGRAY)
            text = "AI 正在观察..."
            gravity = Gravity.CENTER
        }

        // 动作文本
        actionText = TextView(themedContext).apply {
            textSize = 16f
            setTextColor(Color.BLACK)
            typeface = Typeface.DEFAULT_BOLD
            text = "准备就绪"
            gravity = Gravity.CENTER
            setPadding(0, 8, 0, 24)
        }

        // 按钮容器
        // fork：三个按钮**各占 1/3 宽**（`width = 0` + `weight = 1f`），且**不再带图标** ——
        // 见上面 cardParams 的注释：WRAP_CONTENT + 图标会让整行溢出外屏宽度，把最右边的按钮挤出屏幕。
        val buttonLayout = LinearLayout(themedContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        fun weightedButtonParams() =
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)

        // 暂停/继续按钮
        btnPause = MaterialButton(themedContext, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
            text = "暂停"
            layoutParams = weightedButtonParams()
            setOnClickListener { togglePause() }
        }

        // 取消按钮 (红色警告色)
        btnCancel = MaterialButton(themedContext, null, android.R.attr.borderlessButtonStyle).apply {
            text = "结束"
            setTextColor(Color.RED)
            layoutParams = weightedButtonParams()
            setOnClickListener { handleCancel() }
        }

        buttonLayout.addView(btnPause)
        buttonLayout.addView(btnCancel)

        previewView?.let { contentLayout.addView(it) }
        contentLayout.addView(statusText)
        contentLayout.addView(actionText)
        contentLayout.addView(buttonLayout)
        card.addView(contentLayout)
        controlRoot?.addView(card, cardParams)

        val controlWindowParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, // 宽度占满方便居中
            WindowManager.LayoutParams.WRAP_CONTENT,
            getOverlayType(),
            // 加上 FLAG_NOT_FOCUSABLE 防止抢键盘焦点
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            y = 120 // 底部留出 Margin，避开手势条
        }

        // 添加视图
        try {
            windowManager.addView(borderRoot, borderParams)
            windowManager.addView(controlRoot, controlWindowParams)
            startBorderAnimation()
        } catch (e: Exception) {
            e.printStackTrace()
        }

    }

    /**
     * Agent 每次循环开始时调用此方法。
     * 如果处于暂停状态，这里会挂起，直到用户点击“继续”。
     * 如果用户点击了“取消”，这里会抛出异常终止任务。
     *
     * fork：**转发到 [AgentRunMonitor]** —— 状态搬走后两个模块的调用点一行都不用改。
     */
    suspend fun awaitState() = AgentRunMonitor.awaitState()

    private fun togglePause() {
        // fork：状态由 [AgentRunMonitor] 持有（监视器也要能控制），这里只同步本窗的视觉。
        if (AgentRunMonitor.togglePause()) {
            btnPause?.text = "继续"
            actionText?.text = "已暂停 (您可以操作屏幕)"
            statusText?.text = "等待用户指令..."
            borderView?.visibility = View.INVISIBLE // 隐藏边框，方便用户看清屏幕
        } else {
            btnPause?.text = "暂停"
            actionText?.text = "任务继续..."
            borderView?.visibility = View.VISIBLE // 恢复边框
        }
    }

    private fun handleCancel() {
        if (isCancelConfirming) {
            // 确认取消：先让总线解锁并置位，再关窗
            AgentRunMonitor.requestCancel()
            actionText?.text = "正在停止..."
            dismiss()
        } else {
            // 第一次点击，提示确认
            isCancelConfirming = true
            btnCancel?.text = "确定?"
            // ⚠️ fork：这里**故意不再"3 秒后自动恢复"**。原先那个 postDelayed 是个真 bug ——
            //    用户第一次点完、犹豫超过 3 秒再点，倒计时已经把状态复位 ⇒ 第二次点击被当成
            //    "第一次点击"重新起确认，表现就是**「再点一次仍然没反应，按钮又变回结束」**。
            //    确认态现在一直保留，直到用户真的确认（关窗）或悬浮窗被销毁。
        }
    }

    /**
     * **阶段状态**（模块写死的文案，如"分析屏幕…"、"生成计划"）。
     *
     * ⚠️ fork：它**不进思考流** —— 只有模型自己输出的文字才进（见 [publishModelOutput]）。
     * 真机反馈过：两者混在一起时，监视器里的"思考流"大半是这些噪音，用户以为那就是模型的思考。
     */
    fun updateStatus(status: String?, action: String?) {
        AgentRunMonitor.publishStatus(status)
        AgentRunMonitor.publishAction(action)
        renderStatusLine(status, action)
    }

    /**
     * fork：**模型的原始文字输出**（新增）—— 它会进思考流。
     *
     * ⚠️ 与 [updateStatus] 的**唯一区别**就是这一条：**只有拿到模型输出时才用本方法**。
     *
     * 文本只做**去标签**（` thinking` / ` response` 换成空格），**不抽取、不丢弃任何文字** ——
     * 原先 `AgentModule` 只取 ` thinking` 标签**内部**（` response` 之后的文字被直接丢掉），
     * `AutoGLMModule` 更是"模型没用标签就什么都不显示"。模型说了什么就展示什么。
     */
    fun publishModelOutput(text: String?, action: String?) {
        val stripped = stripThinkingTags(text)
        AgentRunMonitor.publishThought(stripped)
        AgentRunMonitor.publishAction(action)
        renderStatusLine(stripped, action)
    }

    /** 只把标签本身换成空格，**不动**其余任何字符。 */
    private fun stripThinkingTags(text: String?): String? {
        val raw = text?.trim().orEmpty()
        if (raw.isEmpty()) return null
        // ⚠️ 标签是 XML 形态的 ` thinking` / ` response`（不是 ` think`）——
        //    两个模块的系统提示词都按这个约定要求模型输出，`extractTag` 拼的也是 `"<" + tag + ">"`。
        return raw.replace("</think>", " ").replace("<think>", " ").trim()
    }

    private fun renderStatusLine(thought: String?, action: String?) {
        if (isPaused) return // 暂停时不更新文字

        controlRoot?.post {
            if (!thought.isNullOrBlank()) {
                statusText?.text = "Thinking: ${thought.take(30)}..."
            }
            if (!action.isNullOrBlank()) {
                actionText?.text = action
            }
        }
    }

    /**
     * fork：显示一帧虚拟屏预览。
     *
     * ⚠️ 两条约定（与 `VirtualDisplayManager.previewListeners` 的契约对应）：
     *  - 回调在**主线程**上发生，所以这里直接碰视图，不需要 post；
     *  - **不得 `recycle()`** —— 帧的所有权在生产者手里，回收也由它做。
     */
    fun updatePreview(bitmap: Bitmap) {
        val view = previewView ?: return
        view.setImageBitmap(bitmap)
        view.visibility = View.VISIBLE
    }

    private fun dp(value: Float): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    suspend fun hideForScreenshot() = withContext(Dispatchers.Main) {
        if (borderRoot == null) return@withContext
        // 截图时隐藏所有悬浮窗
        borderRoot?.visibility = View.INVISIBLE
        controlRoot?.visibility = View.INVISIBLE
        delay(150)
    }

    suspend fun restoreAfterScreenshot() = withContext(Dispatchers.Main) {
        // 恢复显示（如果处于暂停状态，保持边框隐藏）
        if (!isPaused) borderRoot?.visibility = View.VISIBLE
        controlRoot?.visibility = View.VISIBLE
    }

    fun dismiss() {
        // fork：本轮结束 —— 只把相位置 ENDED，**保留**最后一份思考快照供监视器回看。
        AgentRunMonitor.finish()
        try {
            borderAnimator?.cancel()
            if (borderRoot != null) windowManager.removeView(borderRoot)
            if (controlRoot != null) windowManager.removeView(controlRoot)
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            // fork：只摘掉视图对预览帧的引用，**不回收** —— 帧的所有权在 VirtualDisplayManager 手里
            previewView?.setImageDrawable(null)
            previewView = null
            borderRoot = null
            controlRoot = null
        }
    }

    /**
     * fork：打开监视器 Activity（虚拟屏画面 + 完整思考流 + 暂停/结束）。
     *
     * ⚠️ 从悬浮窗（overlay 窗口）里启动 Activity 可能撞上后台启动限制（BAL）——
     * 这里**只记日志不抛**：监视器打不开不影响任务本身，用户还可以从最近任务进。
     */
    private fun openMonitor() {
        try {
            context.startActivity(
                Intent(context, AgentMonitorActivity::class.java).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (e: Exception) {
            DebugLogger.e("AgentOverlayManager", "打开监视器失败", e)
        }
    }

    private fun startBorderAnimation() {
        borderAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                borderView?.setPhase(it.animatedValue as Float)
            }
            start()
        }
    }

    private fun getOverlayType(): Int {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        } else {
            WindowManager.LayoutParams.TYPE_PHONE
        }
    }

    private fun getScreenCornerRadius(context: Context): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            try {
                val display = context.display
                val corner = display?.getRoundedCorner(android.view.RoundedCorner.POSITION_TOP_LEFT)
                if (corner != null) {
                    return corner.radius.toFloat()
                }
            } catch (e: Exception) { /* Ignore */ }
        }
        return 125f
    }

    private class RainbowBorderView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 18f
            strokeCap = Paint.Cap.ROUND
        }
        private var cornerRadius = 0f
        private var phase = 0f
        private val colors = intArrayOf(
            Color.RED, Color.MAGENTA, Color.BLUE, Color.CYAN,
            Color.GREEN, Color.YELLOW, Color.RED
        )

        fun setCornerRadius(radius: Float) {
            this.cornerRadius = radius
            invalidate()
        }

        fun setPhase(p: Float) {
            this.phase = p
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            val strokeHalf = paint.strokeWidth / 2
            val shader = SweepGradient(w / 2, h / 2, colors, null)
            val matrix = Matrix()
            matrix.postRotate(phase * 360f, w / 2, h / 2)
            shader.setLocalMatrix(matrix)
            paint.shader = shader
            val rect = RectF(strokeHalf, strokeHalf, w - strokeHalf, h - strokeHalf)
            canvas.drawRoundRect(rect, cornerRadius, cornerRadius, paint)
        }
    }
}
