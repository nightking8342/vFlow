package com.chaomixian.vflow.services.island

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import androidx.core.app.NotificationCompat
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger

/**
 * 通用的「模板态」超级岛通知发送器。
 *
 * 与 [IslandNotificationDispatcher] 的分工：
 *
 * | | 本类 | [IslandNotificationDispatcher] |
 * |---|---|---|
 * | 通道 | **模板**（`miui.focus.param` + `param_v2`） | 自定义（`param.custom` + `rv`） |
 * | 视图 | SystemUI 按官方模板渲染 | 应用提供的 RemoteViews |
 * | 能力 | **系统原生计时器、内置 Lottie 动画** | 任意自定义布局 |
 * | 场景 | 需要"走动的计时器"或动图 | 需要展示应用特有的复杂信息 |
 *
 * 两者**互不干扰**：各自写自己的 extras key，各用各的通知 id。
 * 同时存在时 SystemUI 的行为未有定论，因此**建议同一时刻只让一条岛活跃**。
 *
 * ## 用法
 *
 * ```kotlin
 * IslandNotifier.show(context, template)        // 发起或更新
 * IslandNotifier.cancel(context, template.cacheKey)  // 主动结束
 * ```
 *
 * 更新同一条通知只需**再次 `show` 同一个 `cacheKey`**——
 * 计时器靠 `timerWhen` 自我推进，**不需要为每秒计时重发通知**。
 */
internal object IslandNotifier {

    private const val TAG = "IslandNotifier"

    /** 模板通道的 extras key。 */
    private const val KEY_FOCUS_PARAM = "miui.focus.param"

    /** 图片包。应用自注册的图标放这里。 */
    private const val KEY_FOCUS_PICS = "miui.focus.pics"

    /** 按钮包。`Notification.Action` 放这里，JSON 里按 key 引用。 */
    private const val KEY_FOCUS_ACTIONS = "miui.focus.actions"

    /** 光效三件套（与现有实现保持一致，避免两条通道观感不一致）。 */
    private const val KEY_EFFECT_SRC = "miui.effect.src"
    private const val KEY_EFFECT_COLOR = "miui.effect.color"
    private const val KEY_BIG_ISLAND_EFFECT_SRC = "miui.bigIsland.effect.src"
    private const val EFFECT_COLOR = "#A1D39A"

    /**
     * vFlow 自注册的应用图标 key。
     *
     * ⚠️ 必须带 `miui.focus.pic_` 前缀，否则 SystemUI 解析不到
     * （见 [IslandParamsBuilder.PIC_APP] 的说明）。
     */
    private const val PIC_APP = IslandParamsBuilder.PIC_APP

    /**
     * 通知 id 区间。
     *
     * ⚠️ **必须避开 `[100000, 150000)`**——那是工作流执行通知的区间
     * （见 `ExecutionNotificationManager.executionNotificationIdFor`）。
     * 也避开既有通知 id（2 / 1001 / 1998 / 2002 / 3001 / 3002 / 97010）。
     */
    private const val NOTIFICATION_ID_BASE = 97_100

    /** 通知渠道。与工作流执行分开，便于用户单独控制。 */
    private const val CHANNEL_ID = "logcat_capture_channel"

    /**
     * 发送或更新一条岛通知。
     *
     * @param notificationId 通知 id。同一功能的更新应传**同一个 id**，
     *                       否则会堆出多条通知。调用方可用
     *                       [notificationIdFor] 从 cacheKey 派生。
     */
    fun show(context: Context, notificationId: Int, template: IslandTemplate) {
        val appContext = context.applicationContext
        ensureChannel(appContext)

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.rounded_terminal_24)
            .setContentTitle(template.content)
            .setContentText(template.content)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        template.contentIntent?.let {
            builder.setContentIntent(
                PendingIntent.getActivity(
                    appContext,
                    notificationId,
                    it,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
        }

        val notification = builder.build()
        attachIslandParams(appContext, notification, template)

        notificationManager(appContext)?.notify(notificationId, notification)
    }

    /** 取消一条岛通知。 */
    fun cancel(context: Context, notificationId: Int) {
        notificationManager(context.applicationContext)?.cancel(notificationId)
    }

    /**
     * 从 [IslandTemplate.cacheKey] 派生稳定的通知 id。
     *
     * 同一 cacheKey 永远映射到同一个 id——这正是「重复 show = 更新而非新建」的前提。
     */
    fun notificationIdFor(cacheKey: String): Int =
        NOTIFICATION_ID_BASE + (cacheKey.hashCode() and 0x7FFFFFFF) % 100

    /**
     * 写入岛参数。
     *
     * **注意**：`build()` 之后 `notification.extras` 仍是同一个可变 Bundle，
     * 可以直接写入（官方示例即如此）。
     */
    private fun attachIslandParams(
        context: Context,
        notification: Notification,
        template: IslandTemplate,
    ) {
        // ---- 1. 模板参数（唯一驱动岛渲染的 key）----
        //
        // 只写 `miui.focus.param`，**不写** `param.custom`、**不写** `rv`。
        // 一旦写了 rv，SystemUI 会走自定义视图分支，模板里的
        // 计时器与 Lottie 动画都不会被渲染。
        notification.extras.putString(KEY_FOCUS_PARAM, IslandTemplateBuilder.build(template))

        // ---- 2. 图片包（自注册的应用图标）----
        // 内置 Lottie key 不需要在这里注册；自注册的才需要。
        notification.extras.putBundle(KEY_FOCUS_PICS, buildPicsBundle(context))

        // ---- 3. 按钮包 ----
        if (template.actions.isNotEmpty()) {
            notification.extras.putBundle(KEY_FOCUS_ACTIONS, buildActionsBundle(context, template))
        }

        // ---- 4. 光效 ----
        notification.extras.putString(KEY_EFFECT_SRC, "outer_glow")
        notification.extras.putString(KEY_EFFECT_COLOR, EFFECT_COLOR)
        notification.extras.putString(KEY_BIG_ISLAND_EFFECT_SRC, "outer_glow")
    }

    /**
     * 构建图片 Bundle。
     *
     * 复用 [IslandIcons] 的圆形应用图标——与工作流通知保持一致的视觉。
     */
    private fun buildPicsBundle(context: Context): android.os.Bundle =
        android.os.Bundle().apply {
            runCatching {
                putParcelable(PIC_APP, Icon.createWithBitmap(IslandIcons.appIconBitmap(context)))
            }.onFailure {
                DebugLogger.w(TAG, "构建岛图标失败，岛上将无图标", it)
            }
        }

    /**
     * 构建按钮 Bundle。
     *
     * ⚠️ 广播型 PendingIntent **必须带 `FLAG_RECEIVER_FOREGROUND`**
     * ——官方接入文档明确要求；否则在后台可能被延迟投递。
     *
     * ⚠️ **隐式广播不适用于岛按钮**：`PendingIntent.getBroadcast` 发出的广播
     * 在岛被点击时，应用的动态注册接收器通常已经注销（用户可能几分钟后才点），
     * 因此接收方**必须是 Manifest 静态注册的显式 Component**
     * （先例见 `WorkflowActionReceiver`）。
     * 本方法只负责包装，`action.actionIntent` 必须已带 Component。
     */
    private fun buildActionsBundle(
        context: Context,
        template: IslandTemplate,
    ): android.os.Bundle = android.os.Bundle().apply {
        template.actions.take(IslandTemplate.MAX_ACTIONS).forEach { action ->
            // 触发广播的 PendingIntent 需要 FLAG_RECEIVER_FOREGROUND（官方要求）
            action.actionIntent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND)

            val pendingIntent = PendingIntent.getBroadcast(
                context,
                action.slot.ordinal,
                action.actionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            // 图标：暂用应用图标（圆形化）——与岛上的主图标保持一致。
            // 按钮专属图标待有设计稿后再加，不预留在那个假接口。
            val icon = Icon.createWithBitmap(IslandIcons.appIconBitmap(context))

            val notificationAction = Notification.Action.Builder(icon, action.label, pendingIntent)
                .build()

            putParcelable(action.slot.key, notificationAction)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = notificationManager(context) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return

        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.logcat_notification_channel_name),
                // LOW：岛通知是被动的状态展示，不应发声或弹横幅
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.logcat_notification_channel_desc)
                setShowBadge(false)
            }
        )
    }

    private fun notificationManager(context: Context): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
}
