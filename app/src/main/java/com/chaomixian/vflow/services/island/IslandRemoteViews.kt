// 文件: main/java/com/chaomixian/vflow/services/island/IslandRemoteViews.kt
package com.chaomixian.vflow.services.island

import android.app.PendingIntent
import android.content.Context
import android.view.View
import android.widget.RemoteViews
import com.chaomixian.vflow.R

/**
 * 构建超级岛展开态的 RemoteViews。
 *
 * ## ⚠️⚠️ 为什么**每次更新都重建**实例（曾经的「复用」是错的）
 *
 * 本类最初的注释写着「复用同一个实例、只调 `setTextViewText` 改变化字段，
 * 跨进程只传差异」—— 那个推理**是错的**，且导致了真机必现的崩溃：
 * 工作流跑到一半 `android.os.TransactionTooLargeException: data parcel size 1035216 bytes`。
 *
 * 实测（`scripts/probe/island-probe/`，2026-09-29 崩溃机型复现）：
 *
 * | 用法 | 单次通知体积随 notify 次数的变化 |
 * |---|---|
 * | **复用**同一组 RemoteViews，每轮写 8 个字段 | 9,492 B →（200 轮后）217,252 B，**每轮 +1,044 B，严格线性** |
 * | **每轮新建** RemoteViews，写同样的 8 个字段 | 9,492 B →（200 轮后）9,496 B，**完全不涨** |
 * | 普通通知（无 RemoteViews） | 2,396 → 2,400 B，不涨 |
 *
 * 根因：**`RemoteViews` 的每个 setter 都是「追加一条动作」，从不替换**。
 * 复用同一个实例 ⇒ 动作列表随更新次数单调增长 ⇒ 每次 `notify` 都要把
 * **整条历史**序列化过 binder ⇒ 体积线性上升。
 *
 * 「跨进程只传差异」的那个直觉来自**另一个** API —— `RemoteViews.reapply()`
 * 才是把增量应用到已有视图的入口；`notify()` 每次都是**完整**事务。
 *
 * 按 vFlow 的真实节奏（`applyToCard` 约 20 个 setter × 3 个实例 × 每个执行
 * 约 84 次更新）反推约 0.6–0.7 MiB，与崩溃实测的 1.01 MiB 同量级 —— 复现吻合。
 *
 * ⇒ **正确做法是每次 `update()` 都新建**。新建的开销只是本地构造对象
 * （不像「重建布局树」那样昂贵 —— 布局是在 SystemUI 侧 inflate 的，
 * 而每次新建的 RemoteViews 携带的正是**必要的**全量动作）。
 */
internal object IslandRemoteViews {

    private val LAYOUT_DARK = R.layout.island_execution_expand_dark
    private val LAYOUT_LIGHT = R.layout.island_execution_expand_light

    /**
     * 创建一组**空的** RemoteViews 实例（不填数据）。
     *
     * ⚠️ **每次更新都要重新调用本方法** —— 不要缓存返回值复用。
     * 复用的后果见类注释（动作列表只增不减 ⇒ 通知体积线性膨胀 ⇒ binder 超限崩溃）。
     *
     * RemoteViews 需要包名来远端 inflate，故必须传 context。
     */
    fun newInstance(context: Context): IslandViews {
        val pkg = context.packageName
        return IslandViews(
            // 浅色与深色各一份；岛展开态与通知栏深色共用深色布局（岛恒深色）。
            light = RemoteViews(pkg, LAYOUT_LIGHT),
            dark = RemoteViews(pkg, LAYOUT_DARK),
            islandExpand = RemoteViews(pkg, LAYOUT_DARK),
        )
    }
}

/**
 * 一组 RemoteViews（浅色 / 深色 / 岛展开），**寿命只有一次 `update()`**。
 *
 * ⚠️ 不要长期持有：每次更新都要 `IslandRemoteViews.newInstance()` 造一组新的
 * （理由见 `IslandRemoteViews` 的类注释）。三个实例共用一套操作代码 ——
 * 它们的布局 view id 完全一致。
 */
internal class IslandViews(
    /** 通知栏浅色模式。 */
    val light: RemoteViews,
    /** 通知栏深色模式。 */
    val dark: RemoteViews,
    /** 岛展开卡片（恒深色）。 */
    val islandExpand: RemoteViews,
) {

    /**
     * 按状态写全部字段。
     *
     * ⚠️ 本方法**必须**作用在刚 `newInstance()` 出来的实例上。它对同一个实例
     * 调了约 20 个 setter，而 setter 是**追加**语义 —— 在已更新过的实例上再调
     * 一次，动作列表就多 20 条，通知体积随之膨胀（见 `IslandRemoteViews` 类注释）。
     */
    fun update(
        context: Context,
        title: String,
        state: IslandNotificationSpec.State,
        stepName: String?,
        statusText: String?,
        progressText: String?,
        progressPercent: Int,
        chronometerBase: Long,
        stopIntent: PendingIntent?,
        stopLabel: String,
    ) {
        listOf(light, dark, islandExpand).forEach { rv ->
            applyToCard(
                context, rv, title, state, stepName, statusText,
                progressText, progressPercent, chronometerBase, stopIntent, stopLabel
            )
        }
    }

    private fun applyToCard(
        context: Context,
        rv: RemoteViews,
        title: String,
        state: IslandNotificationSpec.State,
        stepName: String?,
        statusText: String?,
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

        // 图标用应用图标（裁圆位图）。它自身是完整图形，不能再套状态色圆底或着色——
        // 故显式清掉布局里的 background 与 tint。
        rv.setImageViewBitmap(R.id.island_icon, IslandIcons.appIconBitmap(context))
        rv.setInt(R.id.island_icon, "setBackgroundResource", 0)
        rv.setInt(R.id.island_icon, "setColorFilter", 0)

        // ---- 进度行：大号数值 + 步骤名 ----
        // 执行中显示「3/8」，终态显示状态词（大号数字位承载结果）。
        rv.setTextViewText(
            R.id.island_progress_text,
            if (isRunning) progressText.orEmpty() else chipTextOf(state)
        )
        // 终态没有「当前步骤」，步骤名留空避免与状态词重复。
        rv.setTextViewText(R.id.island_step_name, if (isRunning) stepName.orEmpty() else "")
        rv.setTextColor(R.id.island_step_name, tertiaryTextColor(isDark))

        // ---- 进度条：独占整行 ----
        rv.setProgressBar(
            R.id.island_progress_bar,
            100,
            when {
                !isRunning && state == IslandNotificationSpec.State.COMPLETED -> 100
                isRunning -> progressPercent
                else -> progressPercent
            },
            false
        )
        // ⚠️ 进度条填充色**不能**用 setInt(id, "setProgressDrawable", resId) 切换。
        // RemoteViews 的 setInt 走反射，只能调带 @RemotableViewMethod 注解且参数为 int
        // 的方法；而 ProgressBar.setProgressDrawable(Drawable) 收的是对象，反射必然失败
        // → 整个 apply() 抛异常 → 远端 inflate 失败 → 静默回落到系统模板样式。
        // 配色由布局静态指定（深/浅两份布局各用自己的 drawable）。

        // ---- 状态行：模块实时状态，独占整行 ----
        // 终态时这里显示结果摘要（已完成 / 错误信息）。
        val statusLine = if (isRunning) statusText.orEmpty() else terminalStatusOf(state)
        rv.setTextViewText(R.id.island_status, statusLine)
        rv.setTextColor(R.id.island_status, secondaryTextColor(isDark))
        // 没有内容时整行隐藏，避免留一条空行撑高卡片。
        rv.setViewVisibility(R.id.island_status, if (statusLine.isBlank()) View.GONE else View.VISIBLE)

        // ---- 底部 ----
        if (isRunning) {
            rv.setViewVisibility(R.id.island_timer, View.VISIBLE)
            rv.setChronometer(R.id.island_timer, chronometerBase, null, true)
            rv.setViewVisibility(R.id.island_elapsed, View.GONE)
            rv.setTextViewText(R.id.island_timer_label, TIMER_LABEL_RUNNING)
        } else {
            // 终态：秒表隐藏，定格耗时暂无数据（不记录，故只留标签为空）。
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

    // ------------------------------------------------------------------
    // 状态 → 外观。浅/深两套色值取自 res/values/colors.xml 的 md_theme_*
    // ------------------------------------------------------------------

    private fun chipTextOf(state: IslandNotificationSpec.State): String = when (state) {
        IslandNotificationSpec.State.RUNNING -> "执行中"
        IslandNotificationSpec.State.COMPLETED -> "已完成"
        IslandNotificationSpec.State.FAILED -> "失败"
        IslandNotificationSpec.State.CANCELLED -> "已停止"
    }

    /** 终态时状态行的文案。执行中返回空（那时该行放模块实时状态）。 */
    private fun terminalStatusOf(state: IslandNotificationSpec.State): String = when (state) {
        IslandNotificationSpec.State.RUNNING -> ""
        IslandNotificationSpec.State.COMPLETED -> "执行完毕"
        IslandNotificationSpec.State.FAILED -> "执行出错"
        IslandNotificationSpec.State.CANCELLED -> "已停止"
    }

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

    private fun secondaryTextColor(dark: Boolean): Int =
        if (dark) 0xFFB6BCC2.toInt() else 0xFF4A4F55.toInt()

    private fun tertiaryTextColor(dark: Boolean): Int =
        if (dark) 0xFF8A9096.toInt() else 0xFF6B7075.toInt()

    private companion object {
        const val TIMER_LABEL_RUNNING = "已运行"
    }
}
