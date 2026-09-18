// 文件: main/java/com/chaomixian/vflow/services/island/IslandCapability.kt
package com.chaomixian.vflow.services.island

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import com.chaomixian.vflow.core.logging.DebugLogger

/**
 * 小米澎湃 OS 超级岛的能力探测。
 *
 * 岛不是「发一条特殊通知」就能显示的——需要同时满足三个条件，缺一不可：
 *
 * 1. 系统支持焦点通知协议，且版本 >= 3（OS3 才有岛，OS2 只有焦点通知，OS1 及更早没有）；
 * 2. 用户在系统设置里为**本应用**开启了「焦点通知」权限（逐应用、默认关闭，
 *    与 `POST_NOTIFICATIONS` 完全无关）；
 * 3. 应用侧把岛参数写进通知 extras。
 *
 * 本类负责 1 和 2 的探测，并把结果缓存到进程结束，供 [IslandNotificationDispatcher] 查询。
 *
 * **为什么必须缓存**：`canShowFocus` 是一次跨进程的 ContentProvider 调用，开销不小，
 * 不适合每次发通知都查——而工作流执行时每推进一步就会更新一次通知。
 *
 * **为什么必须异步**：同上，那次 provider 调用是耗时操作，不能在主线程或执行器协程里做。
 */
internal object IslandCapability {

    private const val TAG = "IslandCapability"

    /** 焦点通知协议版本：OS3 才支持超级岛。 */
    private const val PROTOCOL_OS3 = 3

    /** 用于查询焦点通知权限的 provider。 */
    private const val FOCUS_PERMISSION_AUTHORITY = "miui.statusbar.notification.public"

    /** provider 返回的权限查询结果键。 */
    private const val KEY_CAN_SHOW_FOCUS = "canShowFocus"

    /** 协议版本查询键（Settings.System）。 */
    private const val KEY_FOCUS_PROTOCOL = "notification_focus_protocol"

    /**
     * 已探测到的协议版本。0 表示「未探测」或「系统不支持」——两者对调用方等价，
     * 都走普通通知路径。
     */
    @Volatile
    private var protocolVersion: Int = 0

    /** 本应用是否已被授予焦点通知权限。 */
    @Volatile
    private var focusPermissionGranted: Boolean = false

    /**
     * 当前是否应当使用超级岛。
     *
     * 能力未就绪（含尚未探测完成）时返回 false，调用方据此走普通通知路径——
     * 这是 [IslandNotificationDispatcher] 的降级依据。
     */
    fun isAvailable(): Boolean = protocolVersion >= PROTOCOL_OS3 && focusPermissionGranted

    /**
     * 刷新能力探测结果。
     *
     * **必须在后台线程调用**：内部会发起 ContentProvider 调用。
     * 重复调用是安全的（幂等），结果会覆盖旧值。
     */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        var protocol = 0
        var granted = false

        try {
            protocol = Settings.System.getInt(
                appContext.contentResolver,
                KEY_FOCUS_PROTOCOL,
                0
            )
            if (protocol >= PROTOCOL_OS3) {
                granted = queryFocusPermission(appContext)
            }
        } catch (t: Throwable) {
            // 非小米设备上 Settings/Provider 都可能抛异常，属预期情况，不视为错误。
            DebugLogger.d(TAG, "焦点协议探测失败（非小米设备属正常）：${t.message}")
        }

        protocolVersion = protocol
        focusPermissionGranted = granted

        // 只记录数值，不记录任何可能含隐私的内容。
        DebugLogger.d(
            TAG,
            "能力探测完成 protocol=$protocol permission=$granted available=${isAvailable()}"
        )
    }

    /**
     * 查询本应用的焦点通知权限。
     *
     * 官方接口（见《小米超级岛开发接入文档》§五.3）：通过 `miui.statusbar.notification.public`
     * provider 调用 `canShowFocus`，传入本应用包名。
     */
    private fun queryFocusPermission(context: Context): Boolean {
        return try {
            val extras = Bundle().apply { putString("package", context.packageName) }
            val result = context.contentResolver.call(
                Uri.parse("content://$FOCUS_PERMISSION_AUTHORITY"),
                "canShowFocus",
                null,
                extras
            )
            result?.getBoolean(KEY_CAN_SHOW_FOCUS, false) == true
        } catch (t: Throwable) {
            DebugLogger.d(TAG, "焦点通知权限查询失败：${t.message}")
            false
        }
    }
}
