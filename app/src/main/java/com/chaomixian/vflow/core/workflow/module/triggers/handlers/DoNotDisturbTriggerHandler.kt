package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.DoNotDisturbTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.isDndEnabled
import com.chaomixian.vflow.core.workflow.module.triggers.probeDndState
import kotlinx.coroutines.launch

/**
 * 免打扰模式触发器处理器。
 *
 * 监听 [NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED] 广播。
 *
 * 与 `PowerTriggerHandler`（同为系统广播型）的区别在于多了一个**状态基线**：
 * 广播只告知「过滤器变了」，不告知方向，因此必须自己缓存上一次的状态来判断
 * 是「开启」还是「关闭」。
 */
class DoNotDisturbTriggerHandler : ListeningTriggerHandler() {

    private var dndReceiver: BroadcastReceiver? = null

    /**
     * 上一次已知的勿扰状态。
     *
     * 必须是实例变量（Handler 是 `TriggerService` 的持久成员，跨 add/remove 存活），
     * 否则每次 `addTrigger` 都会丢失基线，导致第一次变化时方向判断错误。
     * 在 `stopListening` 里清空，避免下次 start 拿到陈旧状态。
     */
    private var lastKnownEnabled: Boolean? = null

    companion object {
        private const val TAG = "DoNotDisturbTriggerHandler"
    }

    override fun startListening(context: Context) {
        if (dndReceiver != null) return
        DebugLogger.d(TAG, "启动免打扰监听...")

        dndReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action == NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED) {
                    handleDndChange(ctx)
                }
            }
        }

        val filter = IntentFilter(NotificationManager.ACTION_INTERRUPTION_FILTER_CHANGED)
        try {
            // 系统广播，用 RECEIVER_NOT_EXPORTED 注册（Android 14+ 要求显式声明）。
            ContextCompat.registerReceiver(
                context,
                dndReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "注册免打扰广播失败", e)
            dndReceiver = null
            return
        }

        // 关键：注册时用当前状态建立基线。若留 null，第一次切换会被当成
        // 「无前值」而无法判断方向（previous == null 时我们按「反向」处理，
        // 会误判一次）。
        lastKnownEnabled = isDndEnabled(context)
        DebugLogger.d(TAG, "免打扰监听已启动，当前状态=$lastKnownEnabled，${probeDndState(context)}")
    }

    override fun stopListening(context: Context) {
        dndReceiver?.let {
            try {
                context.unregisterReceiver(it)
                DebugLogger.d(TAG, "免打扰监听已停止。")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "注销免打扰广播时出错: ${e.message}")
            } finally {
                dndReceiver = null
                lastKnownEnabled = null
            }
        }
    }

    private fun handleDndChange(context: Context) {
        triggerScope.launch {
            val current = isDndEnabled(context)
            val previous = lastKnownEnabled

            // 去抖：filter 变化但「是否开启」的语义没变（例如 PRIORITY <-> NONE
            // 之间的切换、或同值的重复广播），不应触发。
            if (previous == current) {
                DebugLogger.d(TAG, "免打扰状态未发生实质变化（$current），忽略。")
                return@launch
            }
            lastKnownEnabled = current

            DebugLogger.i(TAG, "免打扰状态变化: $previous -> $current；${probeDndState(context)}")

            val inputs = DoNotDisturbTriggerModule().getInputs()
            listeningTriggers.forEach { trigger ->
                val rawTarget = trigger.parameters["target_state"] as? String ?: return@forEach
                // 必须走 normalizeEnumValue：历史数据里可能存的是本地化文案。
                val target = inputs.normalizeEnumValue(
                    "target_state",
                    rawTarget,
                    DoNotDisturbTriggerModule.STATE_ON
                ) ?: DoNotDisturbTriggerModule.STATE_ON

                val shouldTrigger = when (target) {
                    DoNotDisturbTriggerModule.STATE_ON -> current
                    DoNotDisturbTriggerModule.STATE_OFF -> !current
                    DoNotDisturbTriggerModule.STATE_ANY -> true
                    else -> false
                }

                if (shouldTrigger) {
                    val stateDescription = if (current) "已开启" else "已关闭"
                    DebugLogger.i(TAG, "条件满足, 触发工作流: ${trigger.workflowName} (免打扰 $stateDescription)")
                    executeTrigger(
                        context,
                        trigger,
                        VDictionary(
                            mapOf(
                                "enabled" to VBoolean(current),
                                // previous 为 null 时用 !current 兜底：此时唯一可知的是
                                // 「与当前相反」。
                                "previous_enabled" to VBoolean(previous ?: !current)
                            )
                        )
                    )
                }
            }
        }
    }
}
