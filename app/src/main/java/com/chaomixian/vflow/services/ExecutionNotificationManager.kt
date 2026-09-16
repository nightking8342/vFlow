// 文件: main/java/com/chaomixian/vflow/services/ExecutionNotificationManager.kt
package com.chaomixian.vflow.services

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.IconCompat
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.ui.main.MainActivity

/**
 * 表示通知的不同状态。
 */
sealed class ExecutionNotificationState {
    data class Running(val progress: Int, val message: String) : ExecutionNotificationState()
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
 * 管理工作流执行期间的进度通知。
 * 这是一个单例对象，负责创建、更新和移除状态栏通知。
 */
object ExecutionNotificationManager {

    private const val CHANNEL_ID = "workflow_execution_channel"
    private const val CHANNEL_NAME = "工作流执行状态"

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
        val prefs = appContext.getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("progressNotificationEnabled", true)) {
            return
        }

        // 使用 SDK 版本判断
        // 官方文档指出 API 级别为 35 (Android 15)，但为了兼容预览版，使用 36 也是安全的。
        if (Build.VERSION.SDK_INT >= 36) {
            buildStatusChipNotification(workflow, state)
        } else {
            buildLegacyNotification(workflow, state)
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
            .setContentIntent(buildContentIntent())

        when (state) {
            is ExecutionNotificationState.Running -> {
                builder
                    .setContentText(state.message)
                    .setOngoing(true)
                    // 请求提升为高优持续性通知 (Status Chip)
                    .setRequestPromotedOngoing(true)
                    // [新增API] 设置在 Status Chip 进度条旁边显示的图标
                    // 直接在 Builder 上设置进度，系统会自动渲染为 Status Chip 进度条
                    .setProgress(100, state.progress, false)
                    // 添加"结束"操作按钮（显示在展开的通知中）
                    .addAction(
                        R.drawable.rounded_close_small_24,
                        "结束",
                        stopPendingIntent
                    )
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
        notificationManager.notify(executionNotificationIdFor(workflow.id), builder.build())
    }

    /**
     * 为旧版本 Android 构建标准进度通知。
     */
    private fun buildLegacyNotification(workflow: Workflow, state: ExecutionNotificationState) {
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setContentTitle(workflow.name)
            .setSmallIcon(R.drawable.ic_workflows)
            .setOnlyAlertOnce(true)
            .setContentIntent(buildContentIntent())

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
        notificationManager.notify(executionNotificationIdFor(workflow.id), builder.build())
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