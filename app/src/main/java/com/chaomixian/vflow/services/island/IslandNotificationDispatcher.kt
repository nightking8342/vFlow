// 文件: main/java/com/chaomixian/vflow/services/island/IslandNotificationDispatcher.kt
package com.chaomixian.vflow.services.island

import android.app.Notification
import android.content.Context
import android.os.Parcelable
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * 岛参数在通知 extras 里的 key（扁平结构，配合 RemoteViews 使用）。
     *
     * **只写这一个**——不写模板的 `miui.focus.param`。依据 mindfs 的可用实现，
     * 它全程只用 custom。两者并存时 SystemUI 可能走模板分支、忽略 RemoteViews。
     */
    private const val KEY_FOCUS_PARAM_CUSTOM = "miui.focus.param.custom"

    /** 岛图片包在通知 extras 里的 key（官方约定）。 */
    private const val KEY_FOCUS_PICS = "miui.focus.pics"

    /** RemoteViews 系列 key。`miui.focus.rv` 存在与否决定走自定义还是模板分支。 */
    private const val KEY_FOCUS_RV = "miui.focus.rv"
    private const val KEY_FOCUS_RV_NIGHT = "miui.focus.rvNight"
    private const val KEY_FOCUS_RV_ISLAND_EXPAND = "miui.focus.rv.island.expand"

    /** 光效相关 key（mindfs 实测生效的组合）。 */
    private const val KEY_EFFECT_SRC = "miui.effect.src"
    private const val KEY_EFFECT_COLOR = "miui.effect.color"
    private const val KEY_BIG_ISLAND_EFFECT_SRC = "miui.bigIsland.effect.src"

    /** 光色，取 vFlow 深色主题主色。 */
    private const val EFFECT_COLOR = "#A1D39A"

    /** 「结束」按钮文案。 */
    private const val STOP_LABEL = "结束"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /**
     * 各工作流正在使用的 RemoteViews 实例组。
     *
     * **为什么要缓存**：一个工作流执行期间只有一条通知（ID 恒定），它被反复更新。
     * 若每次都新建 RemoteViews，等于每次重新 inflate 布局 + 跨进程传整棵树，
     * 比模板路径还贵。缓存后每次更新只传变化字段的差异——这才是用 RemoteViews 的意义。
     *
     * 键是 workflowId：并发执行的不同工作流各持一组，互不干扰。
     * 由 [releaseViews] 在工作流结束时清理。
     */
    private val viewsByWorkflow = ConcurrentHashMap<String, IslandViews>()

    /**
     * 释放某工作流的 RemoteViews 缓存。应在工作流执行结束时调用。
     *
     * 不释放的后果：每个执行过的工作流都会常驻一组 RemoteViews（3 个实例，
     * 各持有布局引用），长期运行会累积。
     */
    fun releaseViews(workflowId: String) {
        viewsByWorkflow.remove(workflowId)
    }

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
            // ⚠️ 必须打完整堆栈：此处吞掉的异常会让「部分参数写入成功、部分失败」，
            // 表现为岛用上了一半功能（如模板生效但 RemoteViews 不生效），极难排查。
            DebugLogger.w(TAG, "附加岛参数失败，降级为普通通知", t)
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
        // ---- 1. 图片包 ----
        notification.extras.putBundle(
            KEY_FOCUS_PICS,
            IslandIcons.buildPics(context)
        )

        // ---- 2. 岛参数（自定义结构）----
        //
        // 只写 `param.custom`，**不写** `miui.focus.param`。
        // 依据 mindfs 的可用实现（`FocusIslandSupport.java:185-214`）：它全程只写 custom，
        // 从不写模板 key。两者并存时 SystemUI 的行为不可靠，实测表现为走模板分支、
        // RemoteViews 被忽略。
        //
        // custom 是**扁平结构**（不解包 param_v2），但 `param_island` 在根级照常携带，
        // 故大岛/小岛的摘要态由它驱动。
        notification.extras.putString(
            KEY_FOCUS_PARAM_CUSTOM,
            IslandParamsBuilder.buildCustomParam(
                title = spec.title,
                state = spec.state,
                stepName = spec.stepName,
                statusText = spec.statusText,
                progressText = spec.progressText,
            )
        )

        // ---- 3. 光效 ----
        // mindfs 写的三个 key（`:196-203`）。岛与展开态的外圈光效开关，
        // 只判非空、值本身不被解析为资源，社区约定填 outer_glow。
        notification.extras.putString(KEY_EFFECT_SRC, "outer_glow")
        notification.extras.putString(KEY_EFFECT_COLOR, EFFECT_COLOR)
        notification.extras.putString(KEY_BIG_ISLAND_EFFECT_SRC, "outer_glow")

        // ---- 4. 展开态 RemoteViews ----
        // `miui.focus.rv` 存在与否是 SystemUI 的硬分叉点：有则走自定义分支
        //（读 param.custom、渲染展开态），无则走模板。
        //
        // **只写这三个**——与 mindfs 一致，不写 `rv.tiny`（那是小折叠机型的折叠态用）。
        //
        // **实例复用**：一个工作流执行期间只有一条通知被反复更新。每次更新都重建
        // RemoteViews 会重新 inflate + 重新传整棵树，比模板路径还贵，会把本改造的收益
        // 抹掉。故按 workflowId 缓存实例，只在首次创建、之后复用同一组。
        val views = viewsByWorkflow.getOrPut(spec.workflowId) {
            IslandRemoteViews.newInstance(context)
        }
        views.update(
            context = context,
            title = spec.title,
            state = spec.state,
            stepName = spec.stepName,
            statusText = spec.statusText,
            progressText = spec.progressText,
            progressPercent = spec.progressPercent,
            chronometerBase = spec.chronometerBase,
            stopIntent = spec.stopIntent,
            stopLabel = STOP_LABEL,
        )

        notification.extras.putParcelable(KEY_FOCUS_RV, views.light)
        notification.extras.putParcelable(KEY_FOCUS_RV_NIGHT, views.dark)
        notification.extras.putParcelable(KEY_FOCUS_RV_ISLAND_EXPAND, views.islandExpand)

        // 诊断日志：只记录结构与长度，不记录值——岛参数里含工作流名（可能是用户隐私）。
        //
        // SystemUI 以 `extras.getParcelable("miui.focus.rv") instanceof RemoteViews`
        // 决定是否走自定义视图分支（`DynamicIslandUtils.hasCustomFocusView`）。
        // 这里回读一次，确认写进去的确实是 RemoteViews 而非被序列化成别的东西。
        val rvReadBack = notification.extras.getParcelable<Parcelable>(KEY_FOCUS_RV)
        val expandReadBack = notification.extras.getParcelable<Parcelable>(KEY_FOCUS_RV_ISLAND_EXPAND)
        DebugLogger.d(
            TAG,
            "已附加岛参数 state=${spec.state} " +
                "titleLength=${spec.title.length} stepNameLength=${spec.stepName?.length ?: 0} " +
                "statusLength=${spec.statusText?.length ?: 0} " +
                "progress=${spec.progressPercent} " +
                "rvIsRemoteViews=${rvReadBack is RemoteViews} " +
                "expandIsRemoteViews=${expandReadBack is RemoteViews} " +
                "templateParamPresent=${notification.extras.containsKey("miui.focus.param")}"
        )
    }
}
