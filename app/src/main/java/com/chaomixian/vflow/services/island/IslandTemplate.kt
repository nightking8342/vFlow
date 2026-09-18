package com.chaomixian.vflow.services.island

/**
 * 通用「模板态」超级岛通知的数据模型。
 *
 * ## 与现有 [IslandNotificationSpec] 的区别
 *
 * [IslandNotificationSpec] 是**工作流执行专用**的，且走 RemoteViews 自定义视图通道
 * （`miui.focus.param.custom` + `miui.focus.rv`）。
 *
 * 本模型是**通用的模板通道**（`miui.focus.param` + `param_v2` 包裹）：
 * 岛的外观完全由 SystemUI 按小米官方模板渲染，应用侧不提供任何视图。
 * 适合需要**系统原生计时器 / 内置 Lottie 动画**的场景——这两样能力
 * 只有模板通道提供，RemoteViews 通道给不了。
 *
 * ## 两条通道的关系（重要）
 *
 * 它们**不是互斥的两套体系**，而是同一份模板内容的两种投递方式：
 *
 * | 方式 | extras key | 结构 | 视图来源 |
 * |---|---|---|---|
 * | 模板 | `miui.focus.param` | `param_v2` 包裹 | SystemUI 按 JSON 渲染 |
 * | 自定义 | `miui.focus.param.custom` | 扁平 | 应用提供的 `miui.focus.rv` |
 *
 * 两者**可以同时存在**——但一旦提供 `rv`，SystemUI 会走自定义视图分支。
 * 因此本模型**只写 `miui.focus.param`，不写 `param.custom`、不写 `rv`**。
 *
 * ## 通用性
 *
 * 本模型刻意**不含任何业务语义**（没有 workflow / logcat 之类的字段），
 * 只有「一个图标 + 一段文本 + 一个计时器 + 若干按钮」。
 * 将来任何需要「岛上一个走动的计时器」的功能都可复用。
 */

/**
 * 一条模板态岛通知。
 *
 * @param cacheKey 稳定标识，用于派生通知 id 与更新同一条通知。
 *                 同 key 重复发送 = 更新，不是新建。
 * @param content  内容文本，如「正在采集日志」。
 *                 进大岛强调区的小字、展开态文本、息屏标题。
 * @param timer    计时器；null 表示无计时（纯静态文本）
 * @param iconKey  SystemUI 内置 Lottie key（如 `stopwatch_big`）或
 *                 应用通过 `miui.focus.pics` 自注册的 key。
 *                 内置 key 清单见 `doc/fork/` 相关调研——**它们无需应用自备资源**。
 * @param actions  岛上的按钮；受模板限制，**最多 2 个**
 * @param islandTimeoutSec 岛自动消失时间（**秒**）。注意与通知的 `timeout`（分钟）单位不同。
 * @param business 运营统计标识。用应用自己的前缀，不要蹭官方场景名。
 */
internal data class IslandTemplate(
    val cacheKey: String,
    val content: String,
    val timer: TimerSpec?,
    val iconKey: String,
    val actions: List<IslandAction> = emptyList(),
    val islandTimeoutSec: Int = DEFAULT_ISLAND_TIMEOUT_SEC,
    val business: String = DEFAULT_BUSINESS,
    /** 点击通知本体时打开哪里；null 表示不响应 */
    val contentIntent: android.content.Intent? = null,
) {
    companion object {
        /** 岛存活默认值（秒）。与官方模板的 43200 保持一致。 */
        const val DEFAULT_ISLAND_TIMEOUT_SEC = 43_200

        /** 统计标识前缀。区别于官方场景，表明这是 vFlow 自有业务。 */
        const val DEFAULT_BUSINESS = "vflow"

        /**
         * 模板允许的最大按钮数。
         *
         * 官方模板的 `actions` 数组本身不限长，但实际位置只预留了两个，
         * 超出的会被丢弃。这里做显式约束，避免调用方以为能传更多。
         */
        const val MAX_ACTIONS = 2
    }
}

/**
 * 计时器状态。
 *
 * 取值与小米 `timerInfo.timerType` 一一对应（官方原文：
 * `-2-倒计时暂停, -1-倒计时开始, 0-默认值, 1-正计时开始, 2-正计时暂停`）。
 * 单独定义枚举是为了在通用层不泄漏厂商数字。
 */
internal enum class TimerMode(val code: Int) {
    /** 不计时，纯静态文本。 */
    NONE(0),

    /** 倒计时进行中。 */
    COUNTDOWN_RUNNING(-1),

    /** 倒计时暂停。 */
    COUNTDOWN_PAUSED(-2),

    /** 正计时进行中（秒表）。 */
    COUNT_UP_RUNNING(1),

    /** 正计时暂停（秒表定格）。 */
    COUNT_UP_PAUSED(2),
}

/**
 * 计时器参数。
 *
 * ⚠️ **时间戳单位是毫秒**（Unix epoch），不是秒——官方原文为「毫秒的时间戳」。
 *
 * ⚠️ 本层**不读时钟**：`whenMs` / `systemCurrentMs` 一律由调用方传入，
 * 这样 [IslandTemplateBuilder] 是纯函数、可单测。调用方通常传：
 * - `whenMs` = 计时起点（采集开始那一刻）
 * - `systemCurrentMs` = 当前时刻（用于校正系统与应用的时钟差）
 *
 * 秒表恢复时不要改 `whenMs`——只把 [mode] 换成 `COUNT_UP_RUNNING`，
 * 并向 `systemCurrentMs` 传当前时间即可，SystemUI 会自行续算。
 *
 * @param mode 计时模式
 * @param whenMs 计时起点（毫秒时间戳）
 * @param systemCurrentMs 系统当前时刻（毫秒时间戳），用于误差补偿
 */
internal data class TimerSpec(
    val mode: TimerMode,
    val whenMs: Long,
    val systemCurrentMs: Long,
) {
    companion object {
        /**
         * 开始一个正计时。
         *
         * @param startMs 计时起点（毫秒时间戳）
         * @param nowMs 当前时刻（毫秒时间戳）
         */
        fun countUpRunning(startMs: Long, nowMs: Long): TimerSpec =
            TimerSpec(TimerMode.COUNT_UP_RUNNING, startMs, nowMs)

        /** 暂停一个正计时（`whenMs` 保持原起点不变）。 */
        fun countUpPaused(startMs: Long, nowMs: Long): TimerSpec =
            TimerSpec(TimerMode.COUNT_UP_PAUSED, startMs, nowMs)
    }
}

/**
 * 岛上按钮。
 *
 * @param slot 按钮位置（模板预留两个槽位）
 * @param label 按钮文字
 * @param iconKey 按钮图标（`miui.focus.pics` 里的 key）；null 用系统默认
 * @param actionIntent 点击后触发的 Intent。
 *                     ⚠️ 若是广播，**必须带 `Intent.FLAG_RECEIVER_FOREGROUND`**
 *                     （官方接入文档明确要求）。
 */
internal data class IslandAction(
    val slot: ActionSlot,
    val label: String,
    val iconKey: String? = null,
    val actionIntent: android.content.Intent,
)

/**
 * 按钮槽位。
 *
 * 模板的 `actions` 数组靠 `action` 字段引用应用注册的 key，
 * 官方示例用 `miui.focus.action_1` / `miui.focus.action_2`。
 */
internal enum class ActionSlot(val key: String) {
    PRIMARY("miui.focus.action_1"),
    SECONDARY("miui.focus.action_2"),
}
