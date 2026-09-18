package com.chaomixian.vflow.services

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.chaomixian.vflow.core.logging.DebugLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 超级岛「结束」按钮的广播接收器。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §5b.3 坑 2。
 *
 * ## ⚠️ 必须 **Manifest 静态注册**
 *
 * 岛按钮可能在通知发出后**很久**才被点击（用户先去别的 App 复现问题，
 * 几十分钟后从岛上点结束）。那时应用的动态注册接收器早已注销，
 * 表现是**按钮渲染完全正常、点着却没有任何反应**。
 *
 * 这个坑已实际踩过一次，因此本类**不能**改成在 Activity / Service 里
 * 动态注册（先例见 [WorkflowActionReceiver]）。
 *
 * ## ⚠️ 必须幂等
 *
 * 实测连点 5 次「结束」会收到 5 次广播（§5b.3 坑 6）。
 * [LogcatCaptureController.stop] 本身是幂等的，这里只负责转交。
 */
class LogcatActionReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "LogcatActionReceiver"

        /** 结束采集。 */
        const val ACTION_STOP_CAPTURE = "com.chaomixian.vflow.action.STOP_LOGCAT_CAPTURE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_STOP_CAPTURE) return

        DebugLogger.i(TAG, "收到岛按钮广播：结束采集")

        // goAsync：onReceive 返回后进程可能被回收，而停止采集是一次跨进程 shell 调用。
        // 用 goAsync 把生命周期延长到协程结束，避免调用被半路掐断。
        val pendingResult = goAsync()
        val appContext = context.applicationContext
        CoroutineScope(Dispatchers.IO).launch {
            try {
                LogcatCaptureController.stop(appContext)
            } catch (t: Throwable) {
                DebugLogger.w(TAG, "结束采集失败", t)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
