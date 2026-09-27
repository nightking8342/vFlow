package com.chaomixian.vflow.core.workflow.module.triggers

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * `activity_changed` 触发器的事件载荷。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.1 / §4.3。
 *
 * ## ⚠️ `extrasJson` 是字符串，**不逐键建输出**
 *
 * extras 的键**不可枚举** —— 任何 App 都能往 Intent 里塞任意键。
 * 为每个键建输出既做不到，也会把编辑器撑爆。
 * 所以统一给一个 JSON 字符串，下游用现有的 JSON 模块解析
 * （与 `OutputDefinition.dictionaryKeys` 的机制一致，AI 也能处理）。
 *
 * ## ⚠️ `truncated` 必须传下去
 *
 * 载荷可能因 Binder 事务上限（1 MB）被截断。
 * **不告诉用户的话，「extras 少了几个键」会被当成数据本身如此** ——
 * 他会去查自己的 App 为什么不传那个键，而真因在我们这边。
 */
@Parcelize
data class ActivityChangedTriggerData(
    val packageName: String,
    val className: String,
    val component: String,
    val intentUri: String,
    val extrasJson: String,
    val truncated: Boolean,
) : Parcelable
