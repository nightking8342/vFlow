package com.chaomixian.vflow.core.workflow.module.triggers

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * `vflow.trigger.broadcast` 触发器的事件载荷。
 *
 * 设计文档：`docs/fork/broadcast-trigger-design.md` §5。
 *
 * 字段与模块的 8 个输出**一一对应**。
 *
 * ## ⚠️ `extrasJson` 是字符串，**不逐键建输出**
 *
 * extras 的键**不可枚举** —— 任意应用都能往 Intent 里塞任意键。
 * 为每个键建输出既做不到，也会把编辑器撑爆。
 * 所以统一给一个 JSON 字符串，下游用现有的 JSON 模块解析
 * （与 `OutputDefinition.dictionaryKeys` 的机制一致，AI 也能处理）。
 *
 * ## ⚠️ `truncated` 必须传下去，而且它比 `activity_changed` 多**一种成因**
 *
 * `ActivityChangedTriggerData.truncated` 只有一个成因（超预算被截）；
 * 本模块有两个：
 *
 * 1. **超预算被截** —— extras 超过 `BroadcastTriggerHandler.MAX_EXTRAS_JSON_BYTES`；
 * 2. **extras 整体读不出来** —— 发送方塞了自定义 `Parcelable`，
 *    而那个类在本进程不可见（`Bundle.get` 抛 `BadParcelableException`）。
 *
 * 两者都意味着「extras 不完整」，用同一个标志表达是诚实且够用的 ——
 * **不给标志的后果很具体**：「extras 少了几个键」会被当成「那个应用本来就没传」，
 * 用户会去查自己的应用为什么不发那个键，而真因在我们这边。
 *
 * ## 为什么**没有** `sender_package`
 *
 * `Intent.getSentFromPackage()` 是 **API 34+**，且只有发送方
 * `BroadcastOptions.setShareIdentityEnabled(true)`（**默认 false**）才给值。
 * ⇒ 绝大多数广播下它恒为 null，**暴露即误导**（用户会以为「这个广播没有发送方」）。
 * 用户 2026-10-07 已明确否掉鉴权层，故本模块**不做发送方鉴权、也不暴露该字段**。
 */
@Parcelize
data class BroadcastTriggerData(
    /** `Intent.getAction()`；无则空串。 */
    val action: String,
    /** `Intent.getDataString()`；无 data 时是空串。 */
    val dataUri: String,
    /** `Intent.getData()?.scheme`；无 data 时是空串。 */
    val scheme: String,
    /** `Intent.getType()`；无则空串。 */
    val mimeType: String,
    /** `Intent.getCategories()`；无则空列表。 */
    val categories: List<String>,
    /** [com.chaomixian.vflow.xposed.wire.ExtrasJsonCodec] 产出；无 extras 时是 `"{}"`。 */
    val extrasJson: String,
    /** `Intent.getFlags()`。 */
    val flags: Int,
    /** [extrasJson] 是否不完整（被截断 **或** 整体读不出）。见类注释。 */
    val truncated: Boolean,
) : Parcelable
