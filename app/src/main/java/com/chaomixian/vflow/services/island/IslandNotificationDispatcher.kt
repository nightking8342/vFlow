// 文件: main/java/com/chaomixian/vflow/services/island/IslandNotificationDispatcher.kt
package com.chaomixian.vflow.services.island

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationCompat
import com.chaomixian.vflow.core.logging.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 把超级岛参数附加到已构建的通知上。**这是装配层对外的唯一入口。**
 *
 * ## 用法
 *
 * ```kotlin
 * val notification = IslandNotificationDispatcher.dispatch(
 *     context = appContext,
 *     builder = builder,          // 调用方已配好的 NotificationCompat.Builder
 *     spec = IslandNotificationSpec(title = ..., state = ...),
 * )
 * notificationManager.notify(id, notification)
 * ```
 *
 * ## 降级语义（关键设计）
 *
 * [dispatch] 接收调用方**已经配好的 builder**，只负责「附加参数后 build」。
 * 因此：
 *
 * - 岛能力可用 → 通知带岛参数，系统渲染为超级岛；
 * - 岛能力不可用 → 通知与不调用本类时**完全一致**，退化为普通通知；
 * - 非小米设备 → 系统忽略这些 extras（官方文档明确的零影响降级）。
 *
 * **调用方不需要写任何 if**。这是把厂商细节收敛在本层、不让它渗透到业务代码的关键。
 */
internal object IslandNotificationDispatcher {

    private const val TAG = "IslandDispatcher"

    /** 岛参数在通知 extras 里的 key（官方约定）。 */
    private const val KEY_FOCUS_PARAM = "miui.focus.param"

    /** 自定义模式的岛参数 key（扁平结构，配合 RemoteViews 使用）。 */
    private const val KEY_FOCUS_PARAM_CUSTOM = "miui.focus.param.custom"

    /** 岛图片包在通知 extras 里的 key（官方约定）。 */
    private const val KEY_FOCUS_PICS = "miui.focus.pics"

    /** RemoteViews 系列 key。存在与否决定 SystemUI 走模板还是自定义分支。 */
    private const val KEY_FOCUS_RV = "miui.focus.rv"
    private const val KEY_FOCUS_RV_NIGHT = "miui.focus.rvNight"
    private const val KEY_FOCUS_RV_ISLAND_EXPAND = "miui.focus.rv.island.expand"
    private const val KEY_FOCUS_RV_TINY = "miui.focus.rv.tiny"

    /** 「结束」按钮文案。 */
    private const val STOP_LABEL = "结束"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 初始化能力探测。应在 App 启动时调用。
     *
     * 探测是异步的：`canShowFocus` 是耗时 provider 调用，不能占用启动路径。
     * 探测完成前若有工作流执行，那一次会走普通通知——概率低、影响小。
     */
    fun initialize(context: Context) {
        val appContext = context.applicationContext
        scope.launch {
            IslandCapability.refresh(appContext)
        }
    }

    /**
     * 构建附带岛参数（若可用）的通知。
     *
     * @param context 应用上下文。
     * @param builder 调用方已配好的 builder（渠道、标题、图标、contentIntent 等）。
     * @param spec 本次通知的业务语义。
     */
    fun dispatch(
        context: Context,
        builder: NotificationCompat.Builder,
        spec: IslandNotificationSpec,
    ): Notification {
        spec.contentIntent?.let { builder.setContentIntent(it) }

        val notification = builder.build()

        if (!IslandCapability.isAvailable()) {
            // 能力不可用：原样返回。不打印额外日志，避免在工作流每一步都刷屏。
            return notification
        }

        return try {
            attachIslandParams(context, notification, spec)
            notification
        } catch (t: Throwable) {
            // 附加失败不应影响通知本身——它已经是一条可用的普通通知了。
            DebugLogger.w(TAG, "附加岛参数失败，降级为普通通知：${t.message}")
            notification
        }
    }

    /**
     * 写入岛参数。
     *
     * **注意**：`build()` 之后 `notification.extras` 仍是同一个可变 Bundle，
     * 可以直接写入（官方示例即如此）。
     */
    private fun attachIslandParams(
        context: Context,
        notification: Notification,
        spec: IslandNotificationSpec,
    ) {
        // ---- 1. 岛摘要态参数（模板路径）----
        // 这条驱动大岛 / 小岛 / 状态栏 ticker，与是否使用 RemoteViews 无关。
        val param = IslandParamsBuilder.buildParam(
            title = spec.title,
            state = spec.state,
            stepName = spec.stepName,
            progressText = spec.progressText,
        )
        notification.extras.putString(KEY_FOCUS_PARAM, param)

        // ---- 2. 图片包 ----
        notification.extras.putBundle(
            KEY_FOCUS_PICS,
            IslandIcons.buildPics(context)
        )

        // ---- 3. 自定义展开态（RemoteViews 路径）----
        // param.custom 与 miui.focus.rv 必须成对出现：SystemUI 以 extras 里
        // 有没有 miui.focus.rv 硬分叉，有则改读 param.custom（扁平结构）。
        // 注意 param_island 在 custom 里照常携带，故岛的摘要态不受影响。
        notification.extras.putString(
            KEY_FOCUS_PARAM_CUSTOM,
            IslandParamsBuilder.buildCustomParam(
                title = spec.title,
                state = spec.state,
                stepName = spec.stepName,
                progressText = spec.progressText,
            )
        )

        val views = IslandRemoteViews.build(
            context = context,
            title = spec.title,
            state = spec.state,
            moduleName = spec.stepName,
            progressText = spec.progressText,
            progressPercent = spec.progressPercent,
            chronometerBase = spec.chronometerBase,
            stopIntent = spec.stopIntent,
            stopLabel = STOP_LABEL,
        )

        // 浅色 / 深色 / 岛展开（恒深色）/ 状态栏胶囊（恒深色）。
        // rv.tiny 不能省——缺省会回落 rv，整张卡片塞进胶囊会被压变形。
        notification.extras.putParcelable(KEY_FOCUS_RV, views.light)
        notification.extras.putParcelable(KEY_FOCUS_RV_NIGHT, views.dark)
        notification.extras.putParcelable(KEY_FOCUS_RV_ISLAND_EXPAND, views.islandExpand)
        notification.extras.putParcelable(KEY_FOCUS_RV_TINY, views.tiny)

        // 诊断日志：只记录结构与长度，不记录值——岛参数里含工作流名（可能是用户隐私）。
        DebugLogger.d(
            TAG,
            "已附加岛参数 state=${spec.state} paramLength=${param.length} " +
                "titleLength=${spec.title.length} stepNameLength=${spec.stepName?.length ?: 0} " +
                "progress=${spec.progressPercent}"
        )
    }
}
