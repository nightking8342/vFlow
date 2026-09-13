package com.chaomixian.vflow.ui.chat

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.chaomixian.vflow.R

/**
 * Chat 悬浮窗的启动封装：统一处理 overlay 权限校验与前台服务启动。
 *
 * 放在这里而不是散在 UI 里，是为了让「顶栏按钮」和将来可能的其他入口共用同一套逻辑。
 */
object ChatFloatWindowLauncher {

    /**
     * 显示悬浮窗。缺少 overlay 权限时调用 [onPermissionMissing]（由调用方决定如何引导）。
     *
     * @param context 用于启动服务的 Context（建议传 Activity）
     * @param onPermissionMissing 缺权限时的回调；不传则直接跳到系统授权页
     */
    fun showOrRequestPermission(
        context: Context,
        onPermissionMissing: (() -> Unit)? = null,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(context)) {
            if (onPermissionMissing != null) {
                onPermissionMissing()
            } else {
                openOverlaySettings(context)
            }
            return
        }
        show(context)
    }

    /** 启动悬浮窗前台服务。 */
    fun show(context: Context) {
        val intent = Intent(context, ChatFloatWindowService::class.java).apply {
            action = ChatFloatWindowService.ACTION_SHOW
        }
        runCatching {
            ContextCompat.startForegroundService(context, intent)
        }.onFailure { t ->
            Toast.makeText(
                context,
                context.getString(R.string.chat_float_start_failed, t.message ?: ""),
                Toast.LENGTH_LONG,
            ).show()
        }
    }

    /** 关闭悬浮窗。 */
    fun hide(context: Context) {
        val intent = Intent(context, ChatFloatWindowService::class.java).apply {
            action = ChatFloatWindowService.ACTION_HIDE
        }
        runCatching { context.startService(intent) }
    }

    /** 跳转到系统的「显示在其他应用上层」授权页。 */
    fun openOverlaySettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            runCatching {
                context.startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}"),
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
    }
}
