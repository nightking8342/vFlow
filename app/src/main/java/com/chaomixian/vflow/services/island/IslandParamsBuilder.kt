// 文件: main/java/com/chaomixian/vflow/services/island/IslandParamsBuilder.kt
package com.chaomixian.vflow.services.island

import com.google.gson.JsonObject

/**
 * 把 [IslandNotificationSpec] 装配成小米超级岛的 `miui.focus.param` JSON。
 *
 * **纯函数**：不触碰 Android 框架、不做 IO，因此可以完整单测。岛的真机渲染无法
 * 在单测里验证，但「字段有没有拼对」可以——把这个不确定性拆出来是本类存在的理由。
 *
 * ## 走的哪条路
 *
 * 走**模板路径**（`miui.focus.param` + `param_v2`），不使用自定义 RemoteViews
 * （那需要 `miui.focus.rv`，会让 `param_v2` 里除 `param.custom` 外的内容全部失效）。
 *
 * ## 关键约束（来自官方文档与模板库）
 *
 * - `param_island` 必填，其中 `bigIslandArea` / `smallIslandArea` 必填；
 * - 大岛 A 区恒为「图文组件1」（`imageTextInfoLeft`，`type=1`）：图标 + 主文本；
 * - 大岛 B 区用「文本组件」（`textInfo`）：显示进度或状态词；
 * - 小岛只放图标（`picInfo`）；
 * - 图片不在 JSON 里内联，而是通过 `miui.focus.pics` Bundle 以 key 引用，
 *   格式为 `{"type":1,"pic":"<key>"}`。
 */
internal object IslandParamsBuilder {

    /** 运营场景标识。官方建议按业务场景填（如打车的 taxi），用于数据统计。 */
    private const val BUSINESS = "vflow_workflow"

    /** 岛图标在 `miui.focus.pics` 中的 key。 */
    internal const val PIC_WORKFLOW = "vflow.focus.pic_workflow"

    /**
     * 圆角应用图标在 `miui.focus.pics` 中的 key。
     *
     * 用于小岛——小岛空间只够放一个图标，放应用图标比放功能图标更易识别来源。
     */
    internal const val PIC_APP = "vflow.focus.pic_app"

    /** vFlow 主色（浅色主题）。见 `res/values/colors.xml` 的 `md_theme_light_primary`。 */
    private const val HIGHLIGHT_COLOR = "#A1D39A"

    /** 失败色。见 `res/values/colors.xml` 的 `md_theme_light_error` 系。 */
    private const val HIGHLIGHT_COLOR_ERROR = "#FFB4AB"

    /** 执行中岛的存活时长（秒）。足够覆盖长工作流。 */
    private const val ISLAND_TIMEOUT_RUNNING = 3600

    /** 完成态岛的存活时长（秒）。 */
    private const val ISLAND_TIMEOUT_DONE = 900

    /** 失败/超时态的岛存活时长（秒）。 */
    private const val ISLAND_TIMEOUT_FAILED = 1800

    /** 通知条默认消失时间（分钟）。 */
    private const val NOTIFICATION_TIMEOUT_MINUTES = 720

    /**
     * 构建 `miui.focus.param` 的值（一个 JSON 字符串）。
     *
     * @param title 标题，用工作流名。
     * @param subtitle 副文本，执行中是进度（如 `3/8`），终态是状态词。
     * @param state 展示状态。
     */
    fun buildParam(
        title: String,
        subtitle: String?,
        state: IslandNotificationSpec.State,
    ): String {
        val paramV2 = JsonObject().apply {
            addProperty("business", BUSINESS)
            addProperty("isShowNotification", true)
            // 持续性通知：岛随每次更新而刷新，而不是每次更新都当作新通知弹出。
            addProperty("updatable", true)
            addProperty("reopen", "reopen")
            addProperty("timeout", NOTIFICATION_TIMEOUT_MINUTES)

            // 自动展开行为。执行中绝不自动展开——工作流每推进一步就更新一次通知，
            // 若每次更新都展开会变成持续骚扰。只有「需要用户注意」的终态才浮出。
            val shouldFloat = state != IslandNotificationSpec.State.RUNNING &&
                state != IslandNotificationSpec.State.CANCELLED
            addProperty("enableFloat", shouldFloat)
            addProperty("islandFirstFloat", shouldFloat)

            // 状态栏 ticker 与息屏文案（OS2 走这部分，OS3 也会用于状态栏）。
            addProperty("ticker", tickerText(title, state))
            addProperty("tickerPic", PIC_WORKFLOW)
            addProperty("aodTitle", tickerText(title, state))
            addProperty("aodPic", PIC_WORKFLOW)

            add("param_island", buildIslandParam(state, title, subtitle))
        }

        return JsonObject().apply { add("param_v2", paramV2) }.toString()
    }

    /**
     * 构建 `miui.focus.param.custom` 的值——**扁平结构**，配合 RemoteViews 使用。
     *
     * 与 [buildParam] 的差异（mindfs 实测，`FocusIslandSupport.java:435-437`）：
     * 自定义模式下 SystemUI 直接从**根级**读 `timeout` / `enableFloat` / `ticker`，
     * **不解包 `param_v2`**。但 `param_island`（大岛/小岛数据）照常传递——
     * 自定义模式只接管**展开态**，岛摘要态不受影响。
     *
     * 两个 key 可以同时存在于 extras：`miui.focus.param`（模板）与
     * `miui.focus.param.custom`（自定义）。后者配合 `miui.focus.rv` 生效。
     */
    fun buildCustomParam(
        title: String,
        subtitle: String?,
        state: IslandNotificationSpec.State,
    ): String {
        val shouldFloat = state != IslandNotificationSpec.State.RUNNING &&
            state != IslandNotificationSpec.State.CANCELLED

        return JsonObject().apply {
            addProperty("business", BUSINESS)
            addProperty("isShowNotification", true)
            addProperty("updatable", true)
            addProperty("reopen", "reopen")
            addProperty("timeout", NOTIFICATION_TIMEOUT_MINUTES)
            addProperty("enableFloat", shouldFloat)
            addProperty("islandFirstFloat", shouldFloat)

            addProperty("ticker", tickerText(title, state))
            addProperty("tickerPic", PIC_WORKFLOW)
            addProperty("aodTitle", tickerText(title, state))
            addProperty("aodPic", PIC_WORKFLOW)

            // 岛数据与模板路径完全一致——这是「自定义模式不影响岛」的关键。
            add("param_island", buildIslandParam(state, title, subtitle))
        }.toString()
    }

    /**
     * 构建 `param_island`——岛摘要态的数据。
     *
     * 结构对应「大岛 = A 区图文组件1 + B 区文本组件」，这是模板库里最贴合
     * 「图标 + 工作流名 + 进度」这一形态的组合。
     */
    private fun buildIslandParam(
        state: IslandNotificationSpec.State,
        title: String,
        subtitle: String?,
    ): JsonObject {
        // A 区：图标 + 工作流名。图标随状态变化（见 IslandIcons.picKeyFor）。
        val primaryText = JsonObject().apply { addProperty("title", title) }

        val imageTextInfoLeft = JsonObject().apply {
            addProperty("type", 1) // 图文组件1
            add("picInfo", picRef(PIC_WORKFLOW))
            add("textInfo", primaryText)
        }

        // B 区：进度 / 状态词。文本组件（type=1）。
        val textInfo = JsonObject().apply {
            subtitle?.takeIf { it.isNotBlank() }?.let { addProperty("title", it) }
            // 只让「需要用户注意」的状态使用强调色。
            addProperty(
                "showHighlightColor",
                state == IslandNotificationSpec.State.COMPLETED ||
                    state == IslandNotificationSpec.State.FAILED
            )
        }

        val bigIslandArea = JsonObject().apply {
            add("imageTextInfoLeft", imageTextInfoLeft)
            add("textInfo", textInfo)
        }

        // 小岛：只放一个图标（空间只够这个）。用应用图标，便于识别来源。
        val smallIslandArea = JsonObject().apply {
            add("picInfo", picRef(PIC_APP))
        }

        return JsonObject().apply {
            addProperty("islandProperty", 1) // 1 = 信息展示为主
            addProperty("islandTimeout", islandTimeoutFor(state))
            addProperty("highlightColor", highlightColorFor(state))
            add("bigIslandArea", bigIslandArea)
            add("smallIslandArea", smallIslandArea)
        }
    }

    /** 图片引用。超级岛不内联图片，而是引用 `miui.focus.pics` 里的 key。 */
    private fun picRef(picKey: String): JsonObject = JsonObject().apply {
        addProperty("type", 1)
        addProperty("pic", picKey)
    }

    /** 状态栏 / 息屏的文案。 */
    private fun tickerText(title: String, state: IslandNotificationSpec.State): String =
        when (state) {
            IslandNotificationSpec.State.RUNNING -> title
            IslandNotificationSpec.State.COMPLETED -> "$title · 已完成"
            IslandNotificationSpec.State.FAILED -> "$title · 失败"
            IslandNotificationSpec.State.CANCELLED -> "$title · 已停止"
        }

    private fun islandTimeoutFor(state: IslandNotificationSpec.State): Int =
        when (state) {
            IslandNotificationSpec.State.RUNNING -> ISLAND_TIMEOUT_RUNNING
            IslandNotificationSpec.State.COMPLETED -> ISLAND_TIMEOUT_DONE
            IslandNotificationSpec.State.FAILED -> ISLAND_TIMEOUT_FAILED
            IslandNotificationSpec.State.CANCELLED -> ISLAND_TIMEOUT_DONE
        }

    /**
     * 强调色。岛背景恒为深色，故用深色主题下的亮色版本
     * （浅色主题的 `#3B6939` 在深色背景上偏暗）。
     */
    private fun highlightColorFor(state: IslandNotificationSpec.State): String =
        when (state) {
            IslandNotificationSpec.State.FAILED -> HIGHLIGHT_COLOR_ERROR
            else -> HIGHLIGHT_COLOR
        }
}
