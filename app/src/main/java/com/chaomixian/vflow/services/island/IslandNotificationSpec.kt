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
     * 副文本（岛的摘要区）。
     *
     * 执行中传进度（如 `3/8`），终态传状态词（如「已完成」）。
     *
     * 注：超级岛支持进度条（模板的 `progressInfo`），但需要一整套图形资源
     * （前进/中间节点/目标点图标），且 vFlow 的百分比按顶层步骤计算、
     * 遇到 If/Loop 会长时间不变。当前设计选择用文本而非进度条，故本类不含进度字段。
     */
    val subtitle: String? = null,
    /**
     * 点击通知/岛主体时的跳转。
     *
     * 为 `null` 时不设置 contentIntent（点击无反应）。
     */
    val contentIntent: PendingIntent? = null,
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
