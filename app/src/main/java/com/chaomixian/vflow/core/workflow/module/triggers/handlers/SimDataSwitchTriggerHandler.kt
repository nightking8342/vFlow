// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/handlers/SimDataSwitchTriggerHandler.kt
// 描述: 数据卡切换触发器的处理器 —— 监听默认上网卡（DDS）变更广播。
//
// 设计依据：docs/fork/sim-data-switch-design.md §9（真机实测结论）
package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.workflow.module.triggers.SimCardInfo
import com.chaomixian.vflow.core.workflow.module.triggers.SimDataSwitchTriggerData
import com.chaomixian.vflow.core.workflow.module.triggers.SimDataSwitchTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.isSentinelSubId
import com.chaomixian.vflow.core.workflow.module.triggers.resolveSimSlotBySubId
import com.chaomixian.vflow.core.workflow.module.triggers.shouldTriggerForAny
import com.chaomixian.vflow.core.workflow.module.triggers.shouldTriggerForSlot
import kotlinx.coroutines.launch

/**
 * 数据卡（DDS）切换触发器处理器。
 *
 * ## ⚠️ 必须用 RECEIVER_EXPORTED 注册 —— 本仓库唯一的例外
 *
 * 本仓库其它系统广播型触发器（如 [DoNotDisturbTriggerHandler]）惯用
 * `ContextCompat.RECEIVER_NOT_EXPORTED`，且那是 Android 14+ 的推荐写法。
 * **但这条广播用 NOT_EXPORTED 会静默收不到**：真机实测（Android 17）同一个进程里
 * 两个 receiver 同时注册，`EXPORTED` 收到、`NOT_EXPORTED` 一律收不到（3/3 复现）。
 * 原因是发送方 `SubscriptionManagerService.broadcastSubId()` 走
 * `sendBroadcastAsUser(intent, UserHandle.ALL)`，属于「跨应用投递」语义，
 * 与 NOT_EXPORTED 的「只收同应用/系统定向广播」不匹配。
 *
 * 表现是「能选能配、后台永不触发」，属最难查的静默失效 —— 改动此行前请先读设计文档 §9.2。
 *
 * ## 关于权限
 *
 * 接收广播**不需要任何权限**，但把 subId 映射成「卡1/卡2」需要读订阅列表
 * （`READ_PHONE_STATE`），因此模块声明了该权限、这里也按需读取。
 *
 * ⚠️ 声明它是**必需**的，不要因为「广播零权限」就删掉：`TriggerService.handleWorkflowChanged`
 * 会在注册前检查权限，缺失时**静默把工作流置为未启用** —— 声明得少反而会让整个触发器不工作。
 *
 * 代价：另有一条**不依赖它**的路径 —— 配成「任意」的工作流只校验 subId 是否为哨兵值，
 * 不读订阅列表，即使读不到卡列表也能触发（见 [shouldTriggerForAny]）。
 * 该路径下 `card_label` / `carrier_name` 等字段为空串、`sim_slot` 为 -1，
 * 但 `sub_id` 仍是真实的。
 */
class SimDataSwitchTriggerHandler : ListeningTriggerHandler() {

    private var receiver: BroadcastReceiver? = null

    companion object {
        private const val TAG = "SimDataSwitchTrigger"

        /**
         * 与 AOSP `TelephonyManager.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED` 逐字一致。
         *
         * 该常量在 AOSP 中是 `@SystemApi @hide`，App 侧编不到，故写字面量 ——
         * fork 已有先例（折叠屏触发器的 `device_posture`）。
         * ⚠️ 不要用同族的 `ACTION_DEFAULT_SUBSCRIPTION_CHANGED`：那是
         * voice/data/sms 的**共同默认**、以 voice 优先，DDS 变更时虽也会连带触发，
         * 但语义更宽、噪声更多。
         */
        private const val ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED =
            "android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED"

        /**
         * 广播 extra 的 subId 键。
         *
         * 实测 extras 里有两个键且值相同（AOSP `SubscriptionManager.putSubscriptionIdExtra`
         * 同时写这两个）：`android.telephony.extra.SUBSCRIPTION_INDEX` 与 `subscription`。
         * 这里优先读前者 —— 它是公开常量的值，语义更明确；后者是 `PhoneConstants.SUBSCRIPTION_KEY`。
         */
        private const val EXTRA_SUBSCRIPTION_INDEX = "android.telephony.extra.SUBSCRIPTION_INDEX"
        private const val EXTRA_SUBSCRIPTION_KEY = "subscription"
    }

    override fun startListening(context: Context) {
        if (receiver != null) return
        DebugLogger.d(TAG, "启动数据卡切换监听...")

        receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                if (intent.action != ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED) return
                val subId = intent.getIntExtra(EXTRA_SUBSCRIPTION_INDEX, -1)
                    .takeIf { it > 0 }
                    ?: intent.getIntExtra(EXTRA_SUBSCRIPTION_KEY, -1)
                DebugLogger.d(TAG, "收到默认数据卡变更广播: subId=$subId")
                handleDataSubscriptionChanged(ctx, subId)
            }
        }

        val filter = IntentFilter(ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED)
        try {
            // ⚠️ NOT_EXPORTED 收不到这条广播，必须 EXPORTED（见类注释）
            ContextCompat.registerReceiver(
                context,
                receiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED,
            )
        } catch (e: Exception) {
            DebugLogger.e(TAG, "注册数据卡切换广播失败", e)
            receiver = null
            return
        }

        DebugLogger.d(TAG, "数据卡切换监听已启动，${probeSimState(context)}")
    }

    override fun stopListening(context: Context) {
        receiver?.let {
            try {
                context.unregisterReceiver(it)
                DebugLogger.d(TAG, "数据卡切换监听已停止。")
            } catch (e: Exception) {
                DebugLogger.w(TAG, "注销数据卡切换广播时出错: ${e.message}")
            } finally {
                receiver = null
            }
        }
    }

    private fun handleDataSubscriptionChanged(context: Context, subId: Int) {
        triggerScope.launch {
            // 哨兵值（-1 / MAX_VALUE）表示「无可用订阅」，不是一次真实切换。
            if (isSentinelSubId(subId)) {
                DebugLogger.d(TAG, "subId=$subId 为哨兵值，忽略。")
                return@launch
            }

            val cards = readSimCards(context)
            val slotIndex = resolveSimSlotBySubId(subId, cards)
            val card = slotIndex?.let { slot -> cards.firstOrNull { it.simSlotIndex == slot } }

            if (slotIndex == null && cards.isEmpty()) {
                // 读不到订阅列表（未授权 / 厂商返回 null）。只有配成「任意」的工作流还能工作。
                DebugLogger.w(
                    TAG,
                    "读不到订阅列表（subId=$subId），无法判定卡槽；" +
                        "配成「任意」的工作流仍会触发，配成具体卡槽的将不触发。",
                )
            }

            val inputs = SimDataSwitchTriggerModule().getInputs()

            listeningTriggers.forEach { trigger ->
                val rawTarget = trigger.parameters[SimDataSwitchTriggerModule.PARAM_TARGET_SLOT] as? String
                    ?: return@forEach
                // 走模块定义的归一化，兼容历史本地化文案；不在 Handler 里硬编码文案
                val target = inputs.normalizeEnumValue(
                    SimDataSwitchTriggerModule.PARAM_TARGET_SLOT,
                    rawTarget,
                    SimDataSwitchTriggerModule.SLOT_ANY,
                ) ?: SimDataSwitchTriggerModule.SLOT_ANY

                val matched = if (target == SimDataSwitchTriggerModule.SLOT_ANY) {
                    // 「任意」只校验不是哨兵值（上面已过），不依赖订阅列表
                    shouldTriggerForAny(subId)
                } else {
                    // 具体卡槽：**查不到就不触发** —— 猜一个会触发错的工作流，属静默错误
                    val targetSlot = SimDataSwitchTriggerModule.slotIndexOf(target) ?: return@forEach
                    shouldTriggerForSlot(targetSlot, subId, cards)
                }
                if (!matched) return@forEach

                val slotDescription = slotIndex?.let { "卡${it + 1} ${card?.label().orEmpty()}" }
                    ?: "未知卡槽"

                DebugLogger.i(
                    TAG,
                    "条件满足, 触发工作流: ${trigger.workflowName} " +
                        "(上网卡切到 $slotDescription, subId=$subId)",
                )

                executeTrigger(
                    context,
                    trigger,
                    SimDataSwitchTriggerData(
                        // 取不到卡槽时给 -1，而不是 0 —— 0 会被误读成「卡1」
                        simSlotIndex = slotIndex ?: -1,
                        subId = subId,
                        cardLabel = card?.label().orEmpty(),
                        carrierName = card?.carrierName.orEmpty(),
                        isSlot1 = slotIndex == 0,
                        isSlot2 = slotIndex == 1,
                    ),
                )
            }
        }
    }

    /**
     * 读当前可用的卡列表。
     *
     * 未授权 `READ_PHONE_STATE`、或系统返回 null 时返回空列表 ——
     * 调用方据此走「无法映射、不触发」的降级路径。
     */
    private fun readSimCards(context: Context): List<SimCardInfo> {
        val appContext = context.applicationContext
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return emptyList()
        }

        val subscriptionManager = appContext
            .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
            ?: return emptyList()

        return try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().map { info ->
                SimCardInfo(
                    subId = info.subscriptionId,
                    simSlotIndex = info.simSlotIndex,
                    carrierName = info.carrierName?.toString(),
                    displayName = info.displayName?.toString(),
                )
            }
        } catch (e: SecurityException) {
            DebugLogger.w(TAG, "读取订阅列表被拒: ${e.message}")
            emptyList()
        }
    }
}

/**
 * 诊断探针：把数据卡相关原始读数写进 vFlow 日志（`DebugLogger` → 应用内日志页）。
 *
 * 与 `probeDndState` 同思路 —— 提供**免改上游文件**的真机验证手段：
 * 无需新增 Activity、无需动 `AndroidManifest.xml`，就能把不同 ROM 上的真实取值拿到手。
 * 生产开销可忽略（仅在监听启动时调用一次）。
 */
internal fun probeSimState(context: Context): String {
    val appContext = context.applicationContext
    val granted = ContextCompat.checkSelfPermission(
        appContext,
        Manifest.permission.READ_PHONE_STATE,
    ) == PackageManager.PERMISSION_GRANTED

    val subscriptionManager = appContext
        .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager

    if (subscriptionManager == null) return "SimProbe: SUBSCRIPTION_SERVICE unavailable"

    val cards = if (granted) {
        try {
            subscriptionManager.activeSubscriptionInfoList.orEmpty().joinToString("; ") {
                "slot=${it.simSlotIndex} subId=${it.subscriptionId} carrier=${it.carrierName}"
            }
        } catch (e: SecurityException) {
            "SecurityException: ${e.message}"
        }
    } else {
        "n/a(未授权 READ_PHONE_STATE)"
    }

    // getDefaultDataSubscriptionId 实测**无需权限**即可读，故单独取出来做交叉核对
    val dds = try {
        SubscriptionManager.getDefaultDataSubscriptionId()
    } catch (e: Throwable) {
        -999
    }

    return buildString {
        append("SimProbe: sdk=${android.os.Build.VERSION.SDK_INT}")
        append(" readPhoneState=$granted")
        append(" defaultDataSubId=$dds")
        append(" cards=[$cards]")
    }
}
