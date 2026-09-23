// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/SimDataSwitchMath.kt
// 描述: 数据卡切换触发器的纯函数层 —— subId ↔ 卡槽 映射与事件归一化。
//
// 设计依据：docs/fork/sim-data-switch-design.md
// 本文件刻意不含 Android 依赖（除 SubscriptionManager 的 INVALID_SUBSCRIPTION_ID 常量语义），
// 以便纯 JVM 单测覆盖「改错了不报错、只静默变差」的映射逻辑。
package com.chaomixian.vflow.core.workflow.module.triggers

/**
 * 一张卡的静态描述。由订阅信息（SubscriptionInfo）提取，是纯数据，便于单测。
 *
 * @param subId         订阅 id（系统分配，可能随重插卡/切换而变，**不是**稳定标识）
 * @param simSlotIndex  卡槽序号（0 = 卡1，1 = 卡2）。这是用户心智里的「卡1/卡2」
 * @param carrierName   运营商名（如「中国联通」），用于给用户看的摘要
 * @param displayName   显示名（取不到时为 null，回退到运营商名）
 */
data class SimCardInfo(
    val subId: Int,
    val simSlotIndex: Int,
    val carrierName: String?,
    val displayName: String?,
) {
    /** 给用户看的名字：优先显示名，其次运营商名，最后回退到「卡N」。 */
    fun label(): String = displayName?.takeIf { it.isNotBlank() }
        ?: carrierName?.takeIf { it.isNotBlank() }
        ?: "SIM${simSlotIndex + 1}"
}

/**
 * 把广播带来的 newSubId 归一化为「哪张卡」。
 *
 * 返回 `null` 表示无法判定（subId 无效、或订阅列表里查不到）——
 * 调用方应当记日志并**不触发**，而不是猜测一个卡槽。
 * 猜错的后果是「切卡1 却触发了配在卡2 上的工作流」，属于最难查的静默错误。
 */
fun resolveSimSlotBySubId(subId: Int, cards: List<SimCardInfo>): Int? {
    if (subId <= 0) return null
    return cards.firstOrNull { it.subId == subId }?.simSlotIndex
}

/** 取指定卡槽的卡信息；卡槽无卡时返回 null。 */
fun findCardBySlot(slotIndex: Int, cards: List<SimCardInfo>): SimCardInfo? =
    cards.firstOrNull { it.simSlotIndex == slotIndex }

/**
 * 判断一次「默认数据卡变化」是否应当触发配在 [targetSlot] 上的工作流。
 *
 * 用 subId 精确比对而非卡槽比对的原因：subId 是广播与订阅列表共用的键，
 * 卡槽需要经过一次映射，多一步就多一个失配点。
 */
fun shouldTriggerForSlot(targetSlot: Int, newSubId: Int, cards: List<SimCardInfo>): Boolean {
    val resolved = resolveSimSlotBySubId(newSubId, cards) ?: return false
    return resolved == targetSlot
}

/**
 * 判断一次「默认数据卡变化」是否应当触发配在「任意卡」上的工作流。
 *
 * 「任意」语义下**不需要知道换成了哪张卡** —— 只要是一次真实的默认数据卡变更即可。
 * 因此这里**只校验 subId 不是哨兵值**，不碰订阅列表 ——
 * 这意味着该分支在「读不到订阅列表」（未授权 / 厂商返回 null）时**照样能触发**，
 * 是本触发器唯一不依赖 `READ_PHONE_STATE` 的路径。
 *
 * ⚠️ 注意与「切到同一张卡」的区别：后者服务端有守卫、根本不发广播，
 * 所以能走到这里的 subId 已经代表一次真实变更，无需再去重。
 */
fun shouldTriggerForAny(newSubId: Int): Boolean = !isSentinelSubId(newSubId)

/**
 * subId 哨兵值：系统在「无可用订阅」时会给出该值。
 *
 * 与 AOSP `SubscriptionManager.INVALID_SUBSCRIPTION_ID` 同值（-1），
 * 同族的 `DEFAULT_SUBSCRIPTION_ID` 为 `Integer.MAX_VALUE`。
 * 两者都必须当作「无事件」处理，否则会解析出一个不存在的卡槽。
 */
const val SIM_INVALID_SUBSCRIPTION_ID = -1

/** 是否为需要忽略的哨兵 subId。 */
fun isSentinelSubId(subId: Int): Boolean =
    subId <= 0 || subId == SIM_INVALID_SUBSCRIPTION_ID || subId == Int.MAX_VALUE
