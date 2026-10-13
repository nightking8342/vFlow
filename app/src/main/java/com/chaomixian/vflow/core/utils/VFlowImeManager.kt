// 文件: main/java/com/chaomixian/vflow/core/utils/VFlowImeManager.kt
package com.chaomixian.vflow.core.utils

import android.content.Context
import android.provider.Settings
import android.util.Base64
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.services.VFlowIME
import kotlinx.coroutines.delay

/**
 * 管理 vFlow 输入法的切换和输入操作。
 */
object VFlowImeManager {
    private const val TAG = "VFlowImeManager"

    // vFlow 输入法的完整 ID (包名/类名)
    private const val VFLOW_IME_ID = "com.chaomixian.vflow/.services.VFlowIME"

    /**
     * 使用 vFlow IME 输入文本。
     * 自动处理：备份当前IME -> 切换vFlow IME -> 发送文本 -> 恢复IME。
     *
     * @param context 上下文
     * @param text 要输入的文本
     * @param keyAction 输入后的按键操作 ("none", "enter", "tab", "next")
     */
    suspend fun inputText(context: Context, text: String, keyAction: String = "none"): Boolean {
        if (!ShellManager.isShizukuActive(context) && !ShellManager.isRootAvailable()) {
            DebugLogger.e(TAG, "IME 输入需要 Shizuku 或 Root 权限。")
            return false
        }

        return try {
            inputTextInternal(context, text, keyAction)
        } catch (e: Exception) {
            // ⚠️ fork：**绝不能把异常抛给调用方** —— 那会打断整条工作流。真机实测过：
            //    AutoGLM 走到 `Type` 指令时，因受限设置读取抛 SecurityException，
            //    整步直接失败（工作流日志「出错模块：#3 AutoGLM 智能体」）。
            //    这里兜底返回 false，让调用方回落到别的输入方式。
            DebugLogger.e(TAG, "IME 输入异常，交由调用方回落", e)
            false
        }
    }

    private suspend fun inputTextInternal(context: Context, text: String, keyAction: String): Boolean {
        // 1. 获取当前默认输入法
        val currentIme = readSecureSettingViaShell(context, Settings.Secure.DEFAULT_INPUT_METHOD)
        DebugLogger.d(TAG, "当前输入法: $currentIme")

        // 2. 如果当前不是 vFlow IME，则切换
        if (currentIme != VFLOW_IME_ID) {
            val enabledMethods = readSecureSettingViaShell(context, Settings.Secure.ENABLED_INPUT_METHODS)

            // 2.1 确保 vFlow IME 已启用 (如果没有，尝试通过 Shell 启用它)
            if (enabledMethods == null) {
                DebugLogger.w(TAG, "读不到 enabled_input_methods，跳过启用步骤（直接尝试切换）")
            } else if (!enabledMethods.contains(VFLOW_IME_ID)) {
                DebugLogger.d(TAG, "vFlow IME 未启用，尝试启用...")
                // 注意：直接修改 enabled_input_methods 比较危险，更安全的是让用户去设置里开一次
                // 但在 Shell 模式下，我们可以尝试 append
                val newEnabled = "$enabledMethods:$VFLOW_IME_ID"
                ShellManager.execShellCommand(context, "settings put secure ${Settings.Secure.ENABLED_INPUT_METHODS} \"$newEnabled\"", ShellManager.ShellMode.AUTO)
            }

            // 2.2 切换到 vFlow IME
            DebugLogger.d(TAG, "切换到 vFlow IME...")
            ShellManager.execShellCommand(context, "ime set $VFLOW_IME_ID", ShellManager.ShellMode.AUTO)
            delay(500) // 等待系统切换
        }

        // 将中文转为 Base64 字符串 (NO_WRAP 避免换行)
        val base64Text = Base64.encodeToString(text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

        // fork：复位标记，避免读到上一次调用留下的陈旧值
        VFlowIME.lastCommitHadConnection = false

        // 使用 --es 指定新的 EXTRA_TEXT_BASE64 和 EXTRA_KEY_ACTION 参数
        val cmd = "am broadcast -a ${VFlowIME.ACTION_INPUT_TEXT} --es ${VFlowIME.EXTRA_TEXT_BASE64} \"$base64Text\" --es ${VFlowIME.EXTRA_KEY_ACTION} \"$keyAction\""

        DebugLogger.d(TAG, "发送输入广播(Base64): $base64Text (原文本: $text, keyAction: $keyAction)")

        val result = ShellManager.execShellCommand(context, cmd, ShellManager.ShellMode.AUTO)

        val broadcastSent = !result.startsWith("Error")

        // 4. 给一点时间让文字上屏
        delay(300)

        // fork：⚠️ 返回「IME 真的拿到 InputConnection 并 commit 了」，而不是「广播发出去了」。
        //    后者会把「IME 没有当前客户端 ⇒ 文字根本没上屏」误报成成功（表现为
        //    "AI 说输入好了，输入框还是空的"）。这条对虚拟屏输入尤其关键 —— 它决定了
        //    AgentTools 该不该回落到 shell `input text`。
        val committed = VFlowIME.lastCommitHadConnection
        if (broadcastSent && !committed) {
            DebugLogger.w(TAG, "广播已发出，但 IME 没有当前 InputConnection ⇒ 文本未上屏")
        }

        // 5. 恢复原来的输入法 (如果之前切过)
        if (currentIme != VFLOW_IME_ID && !currentIme.isNullOrBlank()) {
            DebugLogger.d(TAG, "恢复原输入法: $currentIme")
            ShellManager.execShellCommand(context, "ime set $currentIme", ShellManager.ShellMode.AUTO)
        }

        return broadcastSent && committed
    }

    /**
     * fork：读 `Settings.Secure` 的**受限键**（一律走 shell）。
     *
     * ⚠️⚠️ Android 14 起 `enabled_input_methods` 这类键对 **targetSdk >= 34 的 App 直接不可读**，
     * `Settings.Secure.getString` 会抛 SecurityException：
     * `Settings key: <enabled_input_methods> is only readable to apps with targetSdkVersion
     * lower than or equal to: 33`。本 App 的 `targetSdk` 是 36 ⇒ **必然抛**。
     *
     * 而那条读取原先只在「当前输入法不是 vFlow IME」时才走到 ⇒ 表现是**时好时坏**：
     * 当前输入法恰好是 vFlow IME 时不报错，否则整步失败（真机实测：AutoGLM 报「出错模块 #3」，
     * 同一个工作流里另一个智能体却能正常输入）。
     *
     * 写那边本来就走 shell，这里把读也统一到 shell（shell 不受该限制）。
     * 读不到返回 `null`，由调用方降级 —— **不阻断输入**。
     */
    private suspend fun readSecureSettingViaShell(context: Context, key: String): String? {
        val result = ShellManager.execShellCommand(
            context,
            "settings get secure $key",
            ShellManager.ShellMode.AUTO,
        )
        if (result.startsWith("Error")) return null
        val value = result.trim()
        return value.ifBlank { null }?.takeIf { it != "null" }
    }

    /**
     * 检查是否已经启用了 vFlow 输入法 (在系统设置中勾选)
     *
     * ⚠️ fork：同样受上面那条 targetSdk 限制 —— 直接读会抛 SecurityException。
     * 目前**全仓无调用方**，但别让它成为下一颗雷：读失败一律按"未启用"处理。
     */
    fun isImeEnabled(context: Context): Boolean {
        return try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_INPUT_METHODS)
                ?.contains(VFLOW_IME_ID) == true
        } catch (e: Exception) {
            DebugLogger.w(TAG, "读 enabled_input_methods 被拒（targetSdk 限制），按未启用处理")
            false
        }
    }
}
