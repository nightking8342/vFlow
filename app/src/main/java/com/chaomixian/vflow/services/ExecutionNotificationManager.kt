// 文件: main/java/com/chaomixian/vflow/services/ExecutionNotificationManager.kt
package com.chaomixian.vflow.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.services.island.IslandCapability
import com.chaomixian.vflow.services.island.IslandNotificationDispatcher
import com.chaomixian.vflow.services.island.IslandNotificationSpec
import com.chaomixian.vflow.ui.main.MainActivity
import java.util.concurrent.ConcurrentHashMap

/**
 * 表示通知的不同状态。
 */
sealed class ExecutionNotificationState {
    /**
     * 执行中。
     *
     * @param progress 进度百分比（0..100），按顶层步骤数计算。
     * @param message 展示文案。步骤推进时是 `"步骤 3/8: 模块名"`，模块自报进度时
     *        是模块给的任意文本，重试时是 `"重试 (1/3): 模块名"`。
     * @param stepName **当前步骤（模块）名**，或 `null` 表示「沿用上一步骤名」。
     *
     * **为什么要独立于 message**：模块执行期间会通过 `onProgress` 频繁自报进度
     * （146 个模块这么做），那些文本会覆盖 [message]。若步骤名从 message 里解析，
     * 一有模块自报进度就丢失，岛上会频繁闪成空白。故步骤名由执行器在**步骤切换时**
     * 显式给出，模块内部的进度回调传 `null` 表示沿用。
     */
    data class Running(
        val progress: Int,
        val message: String,
        val stepName: String? = null,
    ) : ExecutionNotificationState()

    data class Completed(val message: String) : ExecutionNotificationState()
    data class Cancelled(val message: String) : ExecutionNotificationState()

    /**
     * 执行失败（模块重试耗尽或错误策略要求停止）。
     *
     * 与 [Cancelled] 分开是必要的：失败需要「红色高亮 + 长时驻留」，而用户主动停止
     * 不需要。此前失败复用 Cancelled，导致两者在通知上无法区分。
     */
    data class Failed(val message: String) : ExecutionNotificationState()
}

/**
 * 通知 ID 基址。各工作流的通知 ID 落在 [NOTIFICATION_ID_BASE, NOTIFICATION_ID_BASE + NOTIFICATION_ID_RANGE) 区间内。
 */
internal const val NOTIFICATION_ID_BASE = 100_000

/** 单个工作流通知 ID 的取值跨度。 */
internal const val NOTIFICATION_ID_RANGE = 50_000

/**
 * 计算某个工作流的通知 ID。
 *
 * **为什么按工作流派生**：此前全局共享固定 ID (1998)，导致并发执行的多个工作流
 * 互相覆盖；且任一工作流结束时调用的取消会把仍在运行的其它工作流的通知一并取消。
 *
 * **为什么取模**：`hashCode()` 是任意 32 位整数（可能为负），直接使用必然撞上项目
 * 现有的其它通知 ID（2 / 1001 / 2002 / 3001 / 3002 / 97010，以及 ExecutionUIService
 * 从 1000 起的自增区间）。收敛到 [100_000, 150_000) 即与这些全部隔离。
 *
 * 理论上两个不同 workflowId 仍可能哈希碰撞而共用同一个 ID，但那只是退化为
 * 「两个工作流共用一个通知」，与改造前的行为同级，可接受。
 */
internal fun executionNotificationIdFor(workflowId: String): Int =
    NOTIFICATION_ID_BASE + (kotlin.math.abs(workflowId.hashCode()) % NOTIFICATION_ID_RANGE)

/**
 * 静默执行的工作流是否应当**不发**这条状态通知。
 *
 * 抽成顶层纯函数是为了能 JVM 单测——这里的语义（尤其是 [ExecutionNotificationState.Failed]
 * 的豁免）一旦被误改，表现为「静默工作流失败了却没有任何提示」，靠读代码很难发现。
 *
 * **失败豁免是刻意的**：静默的语义是「别播报过程」，不是「炸了也别告诉我」。
 * 无人值守的静默任务若失败也悄无声息，用户会以为它正常跑着。
 *
 * @param silentExecution 工作流的静默开关。
 * @param state 本次要展示的状态。
 */
internal fun shouldSilenceNotification(
    silentExecution: Boolean,
    state: ExecutionNotificationState,
): Boolean = silentExecution && state !is ExecutionNotificationState.Failed

/**
 * 管理工作流执行期间的进度通知。
 * 这是一个单例对象，负责创建、更新和移除状态栏通知。
 */
object ExecutionNotificationManager {

    private const val CHANNEL_ID = "workflow_execution_channel"
    private const val CHANNEL_NAME = "工作流执行状态"

    /**
     * 匹配「步骤切换」时执行器发的导航性文案：`步骤 3/8: 延迟`。
     *
     * 这类文案要排除在「模块实时状态」之外——否则展开态的状态行会显示
     * 「步骤 3/8: 延迟」，与上方进度行（`3/8 延迟`）内容重复。
     */
    private val STEP_TRANSITION_PATTERN = Regex("""^步骤\s*\d+/\d+:""")

    private lateinit var notificationManager: NotificationManager
    private lateinit var appContext: Context

    /**
     * 初始化管理器。应在应用启动时调用。
     * @param context 应用上下文。
     */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        notificationManager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
        // 异步探测超级岛能力（耗时 provider 调用，不能在启动路径上做）。
        // 探测完成前发出的通知走普通路径，见 IslandNotificationDispatcher 的降级说明。
        IslandNotificationDispatcher.initialize(appContext)
    }

    /**
     * 创建通知渠道。仅在 Android O (API 26) 及以上版本需要。
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            )
            channel.description = "显示正在执行的工作流的进度"
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * 根据给定的状态显示或更新通知。
     * @param workflow 相关的工作流。
     * @param state 通知的当前状态 (Running, Completed, Cancelled, Failed)。
     */
    fun updateState(workflow: Workflow, state: ExecutionNotificationState) {
        // 静默工作流：整个过程性通知都不发。
        //
        // 闸门开在这里而不是 WorkflowExecutor 的 9 个 updateState 调用点，
        // 是为了把改动收在 1 个文件里——执行器是高冲突风险区，散落判断既扩大 diff 又易漏。
        //
        // 顺带取消一次：若用户在执行**中途**打开静默开关，之前发出去的通知会残留在通知栏
        // （后续 updateState 全部 return，Completed 永远不到达，3 秒后的 cancelNotification
        // 也会被下面的守卫拦掉）。静默是「不可逆的收敛态」，中途开启应当立刻清掉残留。
        //
        // 失败状态**豁免**：静默的语义是「别播报过程」，不是「炸了也别告诉我」。
        if (shouldSilenceNotification(workflow.silentExecution, state)) {
            notificationManager.cancel(executionNotificationIdFor(workflow.id))
            return
        }

        val prefs = appContext.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("progressNotificationEnabled", true)) {
            return
        }

        // 计时基准：执行开始时记一次，终态时清理。
        // Chronometer 靠这个值自走，不需要为计时重发通知。
        if (state is ExecutionNotificationState.Running) {
            chronometerBase.getOrPut(workflow.id) { SystemClock.elapsedRealtime() }

            // 步骤名：执行器在步骤切换时给出新值，模块内部自报进度时传 null 表示沿用。
            // 这条「记住上一次」的逻辑是必需的——146 个模块会频繁自报进度，
            // 若每次都把步骤名当 null，岛上会闪成空白。
            state.stepName?.takeIf { it.isNotBlank() }?.let {
                currentStepName[workflow.id] = it
            }
        }

        // 使用 SDK 版本判断
        // 官方文档指出 API 级别为 35 (Android 15)，但为了兼容预览版，使用 36 也是安全的。
        if (Build.VERSION.SDK_INT >= 36) {
            buildStatusChipNotification(workflow, state)
        } else {
            buildLegacyNotification(workflow, state)
        }

        // 终态后基准与步骤名不再需要（下次执行会重新记）。
        if (state !is ExecutionNotificationState.Running) {
            chronometerBase.remove(workflow.id)
            currentStepName.remove(workflow.id)
            // 释放该工作流的 RemoteViews 实例缓存，避免长期运行累积。
            // 注意：必须在 notify 之后释放——释放只影响我们的缓存，
            // 已经交给 NotificationManager 的那份副本不受影响。
            IslandNotificationDispatcher.releaseViews(workflow.id)
        }
    }

    /**
     * 点击通知主体的跳转：打开 App 首页。
     *
     * 此前两个 build 方法都没有设置 contentIntent，点击通知本体没有任何反应。
     */
    private fun buildContentIntent(): PendingIntent = PendingIntent.getActivity(
        appContext,
        0,
        MainActivity.createAppLaunchIntent(appContext),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    /**
     * 把执行器的内部状态映射为超级岛的展示语义。
     *
     * 两者不是一一对应：执行器没有独立的「超时」状态（它复用 [ExecutionNotificationState.Failed]），
     * 而岛上「执行中」与「已停止」都不该自动浮出。
     *
     * **为什么不直接接收步骤信息**：`updateState` 是执行器调用的对外入口，
     * 改签名会波及多个调用点。步骤名改由 [ExecutionNotificationState.Running.stepName]
     * 显式携带（执行器在步骤切换时给出），进度文本从 `Running.progress`
     * 与工作流总步数算出——两者都是结构化数据，不再从文案里解析。
     */
    /** 判断一条 Running.message 是否来自「步骤切换」而非模块自报状态。 */
    private fun isStepTransitionMessage(message: String): Boolean =
        STEP_TRANSITION_PATTERN.containsMatchIn(message)

    private fun islandSpecOf(
        workflow: Workflow,
        state: ExecutionNotificationState,
        contentIntent: PendingIntent,
        stopIntent: PendingIntent?,
        chronometerBase: Long,
    ): IslandNotificationSpec {
        val (islandState, message) = when (state) {
            is ExecutionNotificationState.Running ->
                IslandNotificationSpec.State.RUNNING to state.message
            is ExecutionNotificationState.Completed ->
                IslandNotificationSpec.State.COMPLETED to state.message
            is ExecutionNotificationState.Failed ->
                IslandNotificationSpec.State.FAILED to state.message
            is ExecutionNotificationState.Cancelled ->
                IslandNotificationSpec.State.CANCELLED to state.message
        }

        val isRunning = state is ExecutionNotificationState.Running

        // 步骤名取自「当前步骤」表——执行器在步骤切换时写入，模块自报进度不会覆盖。
        val stepName = if (isRunning) currentStepName[workflow.id] else null

        // 模块实时状态取自 message，但要**排除步骤切换时那条结构化文案**。
        //
        // message 有两种来源（见 WorkflowExecutor）：
        //  - 步骤切换：`"步骤 3/8: 延迟"`——这是导航性文案，不是模块状态，应丢弃；
        //  - 模块自报：`"正在延迟 6000ms"`——这才是要显示的实时状态。
        //
        // 若把前者也当状态显示，展开态会出现「步骤 3/8: 延迟」这种与上方进度行重复的内容。
        val statusText = if (isRunning && !isStepTransitionMessage(state.message)) {
            state.message.takeIf { it.isNotBlank() }
        } else {
            null
        }

        // 进度文本「3/8」由百分比反推第几步 + 顶层总步数。
        // 这样与 Running.progress（同样是按顶层步骤算的百分比）口径一致。
        val totalSteps = workflow.steps.size
        val progressText = if (isRunning && totalSteps > 0) {
            val currentStep = (state.progress * totalSteps) / 100
            "${currentStep.coerceIn(1, totalSteps)}/$totalSteps"
        } else {
            null
        }

        return IslandNotificationSpec(
            title = workflow.name,
            state = islandState,
            workflowId = workflow.id,
            stepName = stepName,
            statusText = statusText,
            progressText = progressText,
            progressPercent = if (isRunning) state.progress else 0,
            chronometerBase = chronometerBase,
            stopIntent = stopIntent,
            contentIntent = contentIntent
        )
    }

    /**
     * 各工作流本次执行的计时基准（`SystemClock.elapsedRealtime()`）。
     *
     * 展开态用 `Chronometer` 展示耗时，只需传一个基准值，系统自走——**不需要为计时重发通知**。
     * 键是 workflowId：并发执行的不同工作流各计各的。
     */
    private val chronometerBase = ConcurrentHashMap<String, Long>()

    /**
     * 各工作流**当前正在执行的步骤名**。
     *
     * 执行器在步骤切换时给出新值（[ExecutionNotificationState.Running.stepName] 非空），
     * 模块内部自报进度时传 null 表示沿用。键是 workflowId，并发执行的不同工作流各记各的。
     */
    private val currentStepName = ConcurrentHashMap<String, String>()

    /**
     * 为 Android 16+ 构建 "Status Chip" 样式的通知。
     * 根据官方文档，不再使用 ProgressStyle，而是直接在 Builder 上设置进度。
     */
    @RequiresApi(36)
    private fun buildStatusChipNotification(workflow: Workflow, state: ExecutionNotificationState) {
        val stopIntent = Intent(appContext, WorkflowActionReceiver::class.java).apply {
            action = WorkflowActionReceiver.ACTION_STOP_WORKFLOW
            putExtra(WorkflowActionReceiver.EXTRA_WORKFLOW_ID, workflow.id)
        }
        val stopPendingIntent = PendingIntent.getBroadcast(
            appContext,
            workflow.id.hashCode(),
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(workflow.name)
            .setSmallIcon(R.drawable.ic_workflows) // 这个是 Status Chip 收起时显示的图标
            .setOnlyAlertOnce(true)

        // AOSP「实时更新」（Status Chip）提升请求与超级岛是**互斥的渲染路径**。
        //
        // 真机实测（小米 MIX Fold 3 / HyperOS V816）：执行中同时带
        // `setRequestPromotedOngoing(true)` 与岛参数时，通知被提升为活体通知，
        // SystemUI 走 AOSP 渲染、**忽略焦点通知的自定义视图**——表现为只有终态
        //（不请求提升）能显示 RemoteViews，执行中是系统默认样式。
        //
        // 有岛能力时让位给岛：岛本身提供了更完整的展示（图标 + 进度 + 步骤名 + 按钮）。
        // 无岛能力时保留提升，那正是 API 36+ 非小米设备上唯一的活体通知能力。
        val useIsland = IslandCapability.isAvailable()

        when (state) {
            is ExecutionNotificationState.Running -> {
                builder
                    .setContentText(state.message)
                    .setOngoing(true)
                    // [新增API] 设置在 Status Chip 进度条旁边显示的图标
                    // 直接在 Builder 上设置进度，系统会自动渲染为 Status Chip 进度条
                    .setProgress(100, state.progress, false)
                    // 添加"结束"操作按钮（显示在展开的通知中）
                    .addAction(
                        R.drawable.rounded_close_small_24,
                        "结束",
                        stopPendingIntent
                    )

                // 请求提升为高优持续性通知 (Status Chip)。有岛时不请求——见函数开头的说明。
                if (!useIsland) {
                    builder.setRequestPromotedOngoing(true)
                }
            }
            is ExecutionNotificationState.Completed -> {
                builder
                    .setContentText(state.message)
                    // 任务完成，不再是持续性通知
                    .setOngoing(false)
                    .setRequestPromotedOngoing(false) // 取消提升请求
                    .setAutoCancel(true)
                    // 通过 setProgress(0, 0, false) 来移除进度条
                    .setProgress(0, 0, false)
                    // (可选) 可以临时将小图标变为完成状态，增强视觉反馈
                    .setSmallIcon(R.drawable.rounded_save_24)
            }
            is ExecutionNotificationState.Failed -> {
                builder
                    .setContentText(state.message)
                    // 失败需要用户处理，不做自动消失（由 WorkflowExecutor 决定何时移除）
                    .setOngoing(false)
                    .setRequestPromotedOngoing(false)
                    .setAutoCancel(false)
                    .setProgress(0, 0, false)
                    .setSmallIcon(R.drawable.rounded_close_small_24)
            }
            is ExecutionNotificationState.Cancelled -> {
                builder
                    .setContentText(state.message)
                    // 任务取消，不再是持续性通知
                    .setOngoing(false)
                    .setRequestPromotedOngoing(false) // 取消提升请求
                    .setAutoCancel(true)
                    // 移除进度条
                    .setProgress(0, 0, false)
                    .setSmallIcon(R.drawable.rounded_close_small_24)
            }
        }
        notificationManager.notify(
            executionNotificationIdFor(workflow.id),
            IslandNotificationDispatcher.dispatch(
                appContext,
                builder,
                islandSpecOf(
                    workflow, state, buildContentIntent(),
                    stopPendingIntent,
                    chronometerBase[workflow.id] ?: SystemClock.elapsedRealtime()
                )
            )
        )
    }

    /**
     * 为旧版本 Android 构建标准进度通知。
     */
    private fun buildLegacyNotification(workflow: Workflow, state: ExecutionNotificationState) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(workflow.name)
            .setSmallIcon(R.drawable.ic_workflows)
            .setOnlyAlertOnce(true)

        when (state) {
            is ExecutionNotificationState.Running -> {
                builder
                    .setContentText(state.message)
                    .setProgress(100, state.progress, false)
                    .setOngoing(true)
            }
            is ExecutionNotificationState.Completed -> {
                builder
                    .setContentText(state.message)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
            }
            is ExecutionNotificationState.Failed -> {
                builder
                    .setContentText(state.message)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(false)
            }
            is ExecutionNotificationState.Cancelled -> {
                builder
                    .setContentText(state.message)
                    .setProgress(0, 0, false)
                    .setOngoing(false)
                    .setAutoCancel(true)
            }
        }
        notificationManager.notify(
            executionNotificationIdFor(workflow.id),
            IslandNotificationDispatcher.dispatch(
                appContext,
                builder,
                islandSpecOf(
                    workflow, state, buildContentIntent(),
                    null,
                    chronometerBase[workflow.id] ?: SystemClock.elapsedRealtime()
                )
            )
        )
    }


    /**
     * 移除指定工作流的通知。
     *
     * 必须带 workflowId：不带的旧签名会把并发执行中的其它工作流通知一并取消。
     */
    fun cancelNotification(workflowId: String) {
        notificationManager.cancel(executionNotificationIdFor(workflowId))
    }
}