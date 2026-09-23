// 文件: main/java/com/chaomixian/vflow/core/workflow/module/system/SimDataSwitchSupport.kt
// 描述: 切换数据卡的 App 侧解析层 —— 卡槽 ↔ subId 映射与当前默认卡读取。
//
// 设计文档：docs/fork/sim-data-switch-design.md
//
// ## 为什么这些放在 App 侧而不是 Core
//
// 这些都是**读**操作，用公开的 `SubscriptionManager` 即可，不需要特权。
// 真机实测（设计文档 §9.1）：
//   · `getActiveSubscriptionInfoList()` 需 `READ_PHONE_STATE`，能拿到 卡槽/运营商/subId；
//   · `getDefaultDataSubscriptionId()` **连权限都不需要**。
//
// 初版把它们放进 Core 的 ISub wrapper 用反射实现，结果被 AIDL 签名变化坑了
// （见 ISubWrapper 的类注释）。**能用公开 API 就别反射猜 AIDL 签名。**
//
// 本文件是纯映射逻辑 + 一处 Android API 调用，刻意做成顶层函数便于单测。
package com.chaomixian.vflow.core.workflow.module.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import androidx.core.content.ContextCompat
import com.chaomixian.vflow.core.logging.DebugLogger

private const val TAG = "SimDataSwitchSupport"

/**
 * 卡槽解析结果。
 *
 * 刻意用密封类而不是 `Int?`：**「读不到卡列表」与「该卡槽确实没卡」是两件事**，
 * 混成同一个 null 会让排障时分不清是权限问题还是硬件问题 ——
 * 这正是初版那个含糊报错「no subscription in slot N」的教训。
 */
sealed interface SimSlotLookup {
    /** 解析成功 */
    data class Found(val subId: Int, val label: String) : SimSlotLookup

    /** 读不到订阅列表（未授权 READ_PHONE_STATE，或厂商返回 null） */
    data object Unavailable : SimSlotLookup

    /** 读到了卡列表，但目标卡槽里没有卡 */
    data object EmptySlot : SimSlotLookup

    /** 设备不支持多卡订阅（无 FEATURE_TELEPHONY_SUBSCRIPTION） */
    data object NotSupported : SimSlotLookup
}

/** 该设备是否具备多卡订阅能力。 */
fun isSubscriptionSupported(context: Context): Boolean =
    context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_SUBSCRIPTION)

/**
 * 读卡槽对应的 subId。
 *
 * ⚠️ 不能用「subId = 卡槽 + 1」之类的算式：subId 由系统分配，
 * 重插卡后同一卡槽可能拿到新 subId（真机上 subId 1/2 对应卡槽 0/1 只是巧合）。
 */
fun lookupSubIdForSlot(context: Context, slotIndex: Int): SimSlotLookup {
    if (!isSubscriptionSupported(context)) return SimSlotLookup.NotSupported

    val appContext = context.applicationContext
    if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.READ_PHONE_STATE)
        != PackageManager.PERMISSION_GRANTED
    ) {
        DebugLogger.w(TAG, "缺 READ_PHONE_STATE，无法解析卡槽 $slotIndex")
        return SimSlotLookup.Unavailable
    }

    val subscriptionManager = appContext
        .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE) as? SubscriptionManager
        ?: return SimSlotLookup.Unavailable

    val infos: List<SubscriptionInfo> = try {
        subscriptionManager.activeSubscriptionInfoList.orEmpty()
    } catch (e: SecurityException) {
        DebugLogger.w(TAG, "读取订阅列表被拒: ${e.message}")
        return SimSlotLookup.Unavailable
    }

    if (infos.isEmpty()) return SimSlotLookup.Unavailable

    val info = infos.firstOrNull { it.simSlotIndex == slotIndex }
        ?: return SimSlotLookup.EmptySlot

    return SimSlotLookup.Found(
        subId = info.subscriptionId,
        label = info.displayName?.toString()?.takeIf { it.isNotBlank() }
            ?: info.carrierName?.toString()?.takeIf { it.isNotBlank() }
            ?: "卡${slotIndex + 1}",
    )
}

/**
 * 读当前默认上网卡 subId。
 *
 * `SubscriptionManager.getDefaultDataSubscriptionId()` 是真机实测**无需权限**即可调的
 * 公开静态方法。取不到时返回 null（不兜底成 -1，以免与哨兵值混淆）。
 */
fun currentDefaultDataSubId(): Int? = try {
    SubscriptionManager.getDefaultDataSubscriptionId().takeIf { it > 0 }
} catch (e: Throwable) {
    DebugLogger.w(TAG, "读默认数据卡失败: ${e.message}")
    null
}
