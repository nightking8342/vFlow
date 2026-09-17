// 文件: main/java/com/chaomixian/vflow/services/island/IslandRemoteViews.kt
package com.chaomixian.vflow.services.island

import android.app.PendingIntent
import android.content.Context
import android.os.SystemClock
import android.view.View
import android.widget.RemoteViews
import com.chaomixian.vflow.R

/**
 * 构建超级岛展开态的 RemoteViews。
 *
 * ## 为什么用 RemoteViews
 *
 * 工作流执行时通知更新频繁（每推进一步一次）。RemoteViews 的收益来自
 * **复用同一个实例、只调 `setTextViewText` 等改变化字段**——跨进程只传差异，
 * 而不是重新传整棵布局树。
 *
 * **因此本类必须复用实例**：调用方持有 [IslandViews] 并在每次更新时调
 * [IslandViews.update]，不要每次 `new RemoteViews`。后者要重新 inflate +
 * 传整棵树，比模板路径还贵，会把本改造的收益抹掉。
 *
 * ## 浅色 / 深色
 *
 * 这套布局不只用于岛展开态——它被通知栏与锁屏预览复用，而那两处有浅色模式。
 * 岛展开态本身恒为深色，但同一份布局必须出两个配色版本：
 * `miui.focus.rv`（浅）与 `miui.focus.rvNight`（深）。
 * 两份布局的 view id 完全一致，故本类操作代码可以通用。
 */
internal object IslandRemoteViews {

    /** 状态栏紧凑胶囊的布局（恒深色）。 */
    private val LAYOUT_TINY = R.layout.island_execution_tiny

    private val LAYOUT_DARK = R.layout.island_execution_expand_dark
    private val LAYOUT_LIGHT = R.layout.island_execution_expand_light

    /**
     * 构建一组 RemoteViews：浅色、深色、岛展开态、状态栏胶囊。
     *
     * @return 四个 key 对应的实例。调用方把它们放进通知 extras。
     */
    fun build(
        context: Context,
        title: String,
        state: IslandNotificationSpec.State,
        moduleName: String?,
        progressText: String?,
        progressPercent: Int,
        chronometerBase: Long,
        stopIntent: PendingIntent?,
        stopLabel: String,
    ): IslandViews {
        // RemoteViews 需要包名来远端 inflate。项目未启用 BuildConfig，从 Context 取。
        val pkg = context.packageName

        // 浅色与深色各一份；岛展开态与通知栏深色共用深色布局（岛恒深色）。
        val light = RemoteViews(pkg, LAYOUT_LIGHT)
        val dark = RemoteViews(pkg, LAYOUT_DARK)
        val islandExpand = RemoteViews(pkg, LAYOUT_DARK)
        val tiny = RemoteViews(pkg, LAYOUT_TINY)

        val views = IslandViews(light, dark, islandExpand, tiny)
        views.update(title, state, moduleName, progressText, progressPercent, chronometerBase, stopIntent, stopLabel)
        return views
    }
}

/**
 * 一次执行期间持有的一组 RemoteViews。
 *
 * **生命周期与执行实例相同**：执行开始时 [IslandRemoteViews.build] 一次，
 * 之后每步只调 [update]，不要重建。
 */
internal class IslandViews(
    /** 通知栏浅色模式。 */
    val light: RemoteViews,
    /** 通知栏深色模式。 */
    val dark: RemoteViews,
    /** 岛展开卡片（恒深色）。 */
    val islandExpand: RemoteViews,
    /** 状态栏紧凑胶囊。 */
    val tiny: RemoteViews,
) {

    /**
     * 按状态刷新全部字段。三个布局逐一套用同一份逻辑
     *（它们的 view id 一致，故可以同一套代码操作）。
     */
    fun update(
        title: String,
        state: IslandNotificationSpec.State,
        moduleName: String?,
        progressText: String?,
        progressPercent: Int,
        chronometerBase: Long,
        stopIntent: PendingIntent?,
        stopLabel: String,
    ) {
        listOf(light, dark, islandExpand).forEach { rv ->
            applyToCard(rv, title, state, moduleName, progressText, progressPercent, chronometerBase, stopIntent, stopLabel)
        }
        applyToTiny(tiny, title, progressText)
    }

    private fun applyToCard(
        rv: RemoteViews,
        title: String,
        state: IslandNotificationSpec.State,
        moduleName: String?,
        progressText: String?,
        progressPercent: Int,
        chronometerBase: Long,
        stopIntent: PendingIntent?,
        stopLabel: String,
    ) {
        val isRunning = state == IslandNotificationSpec.State.RUNNING
        val isDark = rv !== light

        // ---- 头部 ----
        rv.setTextViewText(R.id.island_title, title)
        rv.setTextViewText(R.id.island_chip, chipTextOf(state))
        rv.setInt(R.id.island_chip, "setBackgroundResource", chipBackgroundOf(state, isDark))
        rv.setTextColor(R.id.island_chip, chipTextColorOf(state, isDark))
        rv.setInt(R.id.island_icon, "setBackgroundResource", iconBackgroundOf(state, isDark))
        rv.setImageViewResource(R.id.island_icon, iconDrawableOf(state))
        rv.setTextColor(R.id.island_icon, iconTintOf(state, isDark))

        // ---- 进度区 ----
        // 执行中显示进度（3/8），终态显示状态词（让大号数字位承载结果）
        rv.setTextViewText(
            R.id.island_progress_text,
            if (isRunning) progressText.orEmpty() else chipTextOf(state)
        )
        rv.setProgressBar(
            R.id.island_progress_bar,
            100,
            if (isRunning) progressPercent else if (state == IslandNotificationSpec.State.COMPLETED) 100 else progressPercent,
            false
        )
        rv.setInt(
            R.id.island_progress_bar,
            "setProgressDrawable",
            progressDrawableOf(state, isDark)
        )
        rv.setTextViewText(R.id.island_module, moduleName.orEmpty())
        rv.setTextColor(R.id.island_module, moduleTextColor(isDark))

        // ---- 底部 ----
        if (isRunning) {
            rv.setViewVisibility(R.id.island_timer, View.VISIBLE)
            rv.setChronometer(R.id.island_timer, chronometerBase, null, true)
            rv.setViewVisibility(R.id.island_elapsed, View.GONE)
            rv.setTextViewText(R.id.island_timer_label, TIMER_LABEL_RUNNING)
        } else {
            rv.setViewVisibility(R.id.island_timer, View.GONE)
            rv.setViewVisibility(R.id.island_elapsed, View.GONE)
            rv.setTextViewText(R.id.island_timer_label, "")
        }

        // 「结束」按钮只在执行中有意义
        if (isRunning && stopIntent != null) {
            rv.setViewVisibility(R.id.island_stop, View.VISIBLE)
            rv.setTextViewText(R.id.island_stop, stopLabel)
            rv.setOnClickPendingIntent(R.id.island_stop, stopIntent)
        } else {
            rv.setViewVisibility(R.id.island_stop, View.GONE)
        }
    }

    private fun applyToTiny(rv: RemoteViews, title: String, progressText: String?) {
        rv.setTextViewText(R.id.tiny_title, title)
        rv.setTextViewText(R.id.tiny_progress, progressText.orEmpty())
    }

    // ------------------------------------------------------------------
    // 状态 → 外观。浅/深两套色值取自 res/values/colors.xml 的 md_theme_*
    // ------------------------------------------------------------------

    private fun chipTextOf(state: IslandNotificationSpec.State): String = when (state) {
        IslandNotificationSpec.State.RUNNING -> "执行中"
        IslandNotificationSpec.State.COMPLETED -> "已完成"
        IslandNotificationSpec.State.FAILED -> "失败"
        IslandNotificationSpec.State.CANCELLED -> "已停止"
    }

    /**
     * 状态胶囊的底色资源。
     *
     * 浅/深是两套独立的 drawable 文件（RemoteViews 不支持主题变量），
     * 故用 `if (dark)` 显式分派，不能靠 id 拼接。
     */
    private fun chipBackgroundOf(state: IslandNotificationSpec.State, dark: Boolean): Int = when (state) {
        IslandNotificationSpec.State.COMPLETED ->
            if (dark) R.drawable.island_rv_chip_done else R.drawable.island_rv_chip_done_light
        IslandNotificationSpec.State.FAILED ->
            if (dark) R.drawable.island_rv_chip_fail else R.drawable.island_rv_chip_fail_light
        IslandNotificationSpec.State.CANCELLED ->
            if (dark) R.drawable.island_rv_chip_stop else R.drawable.island_rv_chip_stop_light
        IslandNotificationSpec.State.RUNNING ->
            if (dark) R.drawable.island_rv_chip_bg else R.drawable.island_rv_chip_bg_light
    }

    private fun chipTextColorOf(state: IslandNotificationSpec.State, dark: Boolean): Int = when (state) {
        IslandNotificationSpec.State.COMPLETED ->
            if (dark) 0xFFA1D39A.toInt() else 0xFF0F7A52.toInt()
        IslandNotificationSpec.State.FAILED ->
            if (dark) 0xFFFFB4AB.toInt() else 0xFFBA1A1A.toInt()
        IslandNotificationSpec.State.CANCELLED ->
            if (dark) 0xFF8A9096.toInt() else 0xFF6B7075.toInt()
        IslandNotificationSpec.State.RUNNING ->
            if (dark) 0xFFD7DADE.toInt() else 0xFF4A4F55.toInt()
    }

    private fun iconBackgroundOf(state: IslandNotificationSpec.State, dark: Boolean): Int = when (state) {
        IslandNotificationSpec.State.COMPLETED ->
            if (dark) R.drawable.island_rv_icon_bg else R.drawable.island_rv_icon_bg_light
        IslandNotificationSpec.State.FAILED ->
            if (dark) R.drawable.island_rv_icon_bg_fail else R.drawable.island_rv_icon_bg_fail_light
        IslandNotificationSpec.State.CANCELLED ->
            if (dark) R.drawable.island_rv_icon_bg_stop else R.drawable.island_rv_icon_bg_stop_light
        IslandNotificationSpec.State.RUNNING ->
            if (dark) R.drawable.island_rv_icon_bg else R.drawable.island_rv_icon_bg_light
    }

    private fun iconDrawableOf(state: IslandNotificationSpec.State): Int = when (state) {
        IslandNotificationSpec.State.COMPLETED -> R.drawable.rounded_save_24
        IslandNotificationSpec.State.FAILED -> R.drawable.rounded_close_small_24
        IslandNotificationSpec.State.CANCELLED -> R.drawable.rounded_close_small_24
        IslandNotificationSpec.State.RUNNING -> R.drawable.ic_workflows
    }

    /** 图标着色：浅色底上用白图形，深色底上用深色图形。 */
    private fun iconTintOf(state: IslandNotificationSpec.State, dark: Boolean): Int {
        val light = !dark
        return when (state) {
            IslandNotificationSpec.State.FAILED -> if (dark) 0xFF690005.toInt() else 0xFFFFFFFF.toInt()
            IslandNotificationSpec.State.CANCELLED -> if (dark) 0xFF2B322A.toInt() else 0xFFFFFFFF.toInt()
            else -> if (light) 0xFFFFFFFF.toInt() else 0xFF0A390F.toInt()
        }
    }

    private fun progressDrawableOf(state: IslandNotificationSpec.State, dark: Boolean): Int = when (state) {
        IslandNotificationSpec.State.FAILED ->
            if (dark) R.drawable.island_rv_progress_fail_dark else R.drawable.island_rv_progress_fail_light
        else ->
            if (dark) R.drawable.island_rv_progress_dark else R.drawable.island_rv_progress_light
    }

    private fun moduleTextColor(dark: Boolean): Int =
        if (dark) 0xFF8A9096.toInt() else 0xFF6B7075.toInt()

    companion object {
        private const val TIMER_LABEL_RUNNING = "已运行"

        /** 便于调用方在不关心计时基准时传值。 */
        fun nowBase(): Long = SystemClock.elapsedRealtime()
    }
}
