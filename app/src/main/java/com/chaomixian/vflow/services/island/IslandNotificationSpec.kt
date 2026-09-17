// 文件: main/java/com/chaomixian/vflow/services/island/IslandNotificationSpec.kt
package com.chaomixian.vflow.services.island

import android.app.PendingIntent

/**
 * 提交给超级岛装配器的通知语义。
 *
 * **与厂商无关**：本类不含任何 `miui.*` 字段、不含 JSON——业务侧（执行器）只描述
 * 「发生了什么」，由 [IslandNotificationSpec] → [IslandParamsBuilder] 的转换决定
 * 在岛上如何呈现。厂商私有协议只存在于装配层内部。
 */
internal data class IslandNotificationSpec(
    /** 通知标题。用工作流名。 */
    val title: String,
    /** 当前状态。决定岛上的文案、强调色与自动浮出行为。 */
    val state: State,
    /**
     * 点击通知/岛主体时的跳转。
     *
     * 为 `null` 时不设置 contentIntent（点击无反应）。
     */
    val contentIntent: PendingIntent? = null,

    /**
     * 当前正在执行的步骤名（模块名）。
     *
     * 显示位置有两个：大岛 B 区的大字位、展开态摘要区。步骤名可能很长，
     * 所以放在 B 区（纯文本位，比被图标占去一半的 A 区更宽）。
     */
    val stepName: String? = null,

    /**
     * 进度文本（如 `3/8`）。
     *
     * 显示位置：大岛 B 区的前置小字（渲染为「步骤 3/8:」）、展开态的大号进度位。
     */
    val progressText: String? = null,

    /** 进度百分比（0..100），供展开态进度条使用。 */
    val progressPercent: Int = 0,

    /**
     * 秒表基准（`SystemClock.elapsedRealtime()`）。
     *
     * 由 `Chronometer` 系统自走，不需要为计时重发通知。
     * 非执行中状态该值不被使用。
     */
    val chronometerBase: Long = 0L,

    /**
     * 「结束」按钮的点击意图。
     *
     * 仅执行中展示该按钮；为 `null` 时按钮隐藏。
     */
    val stopIntent: PendingIntent? = null,
) {

    /**
     * 通知状态。
     *
     * 与 `ExecutionNotificationState` 的区别：后者是执行器的内部状态（含进度回调等），
     * 本枚举是**面向展示**的语义——同一个执行状态在不同模式下可能映射到不同的展示状态。
     */
    internal enum class State {
        /** 正在执行。安静更新，不自动浮出（避免每步都弹）。 */
        RUNNING,

        /** 成功完成。自动浮出一次提示用户。 */
        COMPLETED,

        /** 执行失败。红色强调，自动浮出，长时驻留。 */
        FAILED,

        /** 用户主动停止。不强调、不浮出。 */
        CANCELLED,
    }
}
