package com.chaomixian.vflow.ui.settings

import android.content.Context
import androidx.appcompat.app.AlertDialog
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.xposed.HookChannelController
import com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor
import com.chaomixian.vflow.core.xposed.XposedState

/**
 * Xposed **通用通道**的授权引导。
 *
 * 设计文档：`docs/fork/xposed-channel-p4-design.md` §2。
 *
 * ## ⚠️ 措辞必须**能力导向**，不能写成某个触发器的用途
 *
 * 这条是本通道的定位决定的：**它是一个通用通道**，后续会有很多触发器/模块挂在上面
 * （② 类手势/组合键、③ 类系统栏动作……）。
 * 所以文案里**不出现具体触发器名**（早期版本写过「用于获取当前 Activity 与启动 Intent」，
 * 那是把通道当成了单一功能，已改）。
 *
 * ## ⚠️ 为什么需要它（P4 里的**功能缺口**，不只是体验）
 *
 * `XPOSED_HOOK.createRequestIntent()` 返回 `null`，而两个 UI 入口
 * **都把 null 当成「那就走别的路」**：
 *
 * | 入口 | 表现 |
 * |---|---|
 * | `PermissionActivity` | 转去 `autoGrantPermission`（要 Shizuku/Root，失败） |
 * | `OnboardingActivity` | 当成运行时权限去 `requestPermissions`（**弹不出对话框**） |
 *
 * ⇒ **用户点「授予」什么都不发生、也没有提示。**
 *
 * ## 两条路并存
 *
 * 1. **主动请求**（首选）：`XposedService.requestScope(["system"])` —— 官方 API，
 *    由框架弹授权界面，结果有回调。**不需要跳转、不需要用户自己找。**
 * 2. **手动步骤**（保底）：框架未连接时（还没启用模块）调不了 `requestScope`，
 *    此时给文字步骤，并附带**实时作用域显示**让用户能自我核对。
 */
object XposedGuideDialog {

    private const val TAG = "XposedGuideDialog"

    /**
     * 弹出引导对话框。
     *
     * @param onDismiss 用户关闭后的回调（通常用于刷新权限状态）
     */
    fun show(context: Context, onDismiss: (() -> Unit)? = null) {
        val monitor = XposedFrameworkMonitor
        try {
            monitor.refresh()
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "刷新状态失败：${t.javaClass.simpleName}")
        }

        val result = monitor.evaluate(HookChannelController.isConnected())
        val snapshot = monitor.state.value
        val frameworkAvailable = snapshot.frameworkConnected

        val body = buildString {
            append(context.getString(R.string.xposed_guide_intro))
            append("\n\n")

            // ⚠️ 分两种处境给不同的指引 ——
            // 「框架已在跑」与「框架还没启用」要做的**完全不同**：
            // 前者只需把 system 加进作用域，后者连模块都还没启用
            if (frameworkAvailable) {
                append(context.getString(R.string.xposed_guide_scope_hint))
            } else {
                append(context.getString(R.string.xposed_guide_steps_when_unavailable))
            }

            append("\n\n————\n\n")
            append(context.getString(R.string.xposed_guide_current_state))
            append("：")
            append(describeForUser(context, result))
            append("\n")
            append(context.getString(R.string.xposed_guide_current_scope))
            append("：")
            append(XposedState.describeScope(snapshot.scope))
        }

        val builder = AlertDialog.Builder(context)
            .setTitle(R.string.permission_name_xposed_hook)
            .setMessage(body)
            .setNegativeButton(R.string.xposed_guide_ok, null)

        if (frameworkAvailable) {
            // ⭐ 首选路径：直接向框架请求作用域授权，不必让用户去 LSPosed 里找
            builder.setPositiveButton(R.string.xposed_guide_request_scope) { _, _ ->
                monitor.requestSystemScope { ok ->
                    DebugLogger.i(TAG, "作用域请求结果：$ok")
                }
            }
        }

        builder.setOnDismissListener { onDismiss?.invoke() }.show()
    }

    /**
     * 把两个状态位翻译成**用户能看懂的一句话**。
     *
     * ⚠️ 不能只显示枚举名（`ACTIVE` / `DISCONNECTED`）—— 那是给开发者看的。
     * 也不能笼统说「未启用」—— 几种失败状态**用户处境完全不同**：
     */
    private fun describeForUser(context: Context, result: XposedState.Result): String =
        when {
            XposedState.isHealthy(result) ->
                context.getString(R.string.xposed_guide_state_ready)

            result.framework == XposedState.Framework.UNAVAILABLE ->
                context.getString(R.string.xposed_guide_state_unavailable)

            result.framework == XposedState.Framework.DEGRADED ->
                context.getString(R.string.xposed_guide_state_degraded)

            result.channel == XposedState.Channel.NOT_MOUNTED ->
                context.getString(R.string.xposed_guide_state_not_mounted)

            // ACTIVE + DISCONNECTED —— ⚠️ 这一格最容易被误判成「框架问题」，
            // 但框架其实是好的，用户去改 LSPosed 配置没用（反而可能改坏）
            else ->
                context.getString(R.string.xposed_guide_state_disconnected)
        }
}
