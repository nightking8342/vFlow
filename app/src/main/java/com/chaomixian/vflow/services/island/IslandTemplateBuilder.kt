package com.chaomixian.vflow.services.island

import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 把 [IslandTemplate] 装配成小米超级岛「模板通道」的 `miui.focus.param` JSON。
 *
 * **纯函数，无 Android 依赖（除 [IslandAction] 里的 Intent 数据），可单测。**
 *
 * ## 通道选择
 *
 * 只产出模板通道的结构——**不含 `param.custom`、不含 `rv`**。
 * 两条通道的关系见 [IslandTemplate] 的类注释。
 *
 * ## 结构来源
 *
 * 逐字段对齐小米官方秒表（`stopWatch`）模板的运行态与暂停态实例。
 * 计时信息在 `param_v2` 内**三处**冗余写入（`highlightInfo` / `sameWidthDigitInfo` /
 * `animTextInfo`），这是照抄官方行为——官方模板确实在多个位置重复填了同一份
 * `timerInfo`，**不要"优化"成只写一处**，SystemUI 读哪一处未经证实。
 *
 * 官方模板在**顶层**也有一份（`timerType`/`timerWhen`/`timerSystemCurrent`），
 * 我们**刻意不写**（见下）。
 *
 * ⚠️ **刻意不写顶层字段**（`scene` / `ticker` / `timerType` / `timerWhen` 等）：
 * 实测这些对岛的渲染没有实际效果，写了反而增加与官方枚举校验冲突的面。
 * 只写 `param_v2` 内部。
 */
internal object IslandTemplateBuilder {

    /** JSON 的 key 名（官方协议常量，不要改）。 */
    private const val KEY_PROTOCOL = "protocol"
    private const val KEY_UPDATABLE = "updatable"
    private const val KEY_ENABLE_FLOAT = "enableFloat"
    private const val KEY_AOD_TITLE = "aodTitle"
    private const val KEY_AOD_PIC = "aodPic"
    private const val KEY_HIGHLIGHT_INFO = "highlightInfo"
    private const val KEY_ACTIONS = "actions"
    private const val KEY_ACTION = "action"
    private const val KEY_PARAM_ISLAND = "param_island"
    private const val KEY_ANIM_TEXT_INFO = "animTextInfo"
    private const val KEY_BUSINESS = "business"

    private const val KEY_TIMER_INFO = "timerInfo"
    private const val KEY_TIMER_TYPE = "timerType"
    private const val KEY_TIMER_WHEN = "timerWhen"
    private const val KEY_TIMER_SYSTEM_CURRENT = "timerSystemCurrent"

    private const val KEY_SUB_CONTENT = "subContent"
    private const val KEY_CONTENT = "content"
    private const val KEY_PIC_FUNCTION = "picFunction"
    private const val KEY_ANIM_ICON_INFO = "animIconInfo"
    private const val KEY_SRC = "src"
    private const val KEY_TYPE = "type"
    private const val KEY_AUTOPLAY = "autoplay"

    private const val KEY_ISLAND_PROPERTY = "islandProperty"
    private const val KEY_ISLAND_ORDER = "islandOrder"
    private const val KEY_ISLAND_TIMEOUT = "islandTimeout"
    private const val KEY_BIG_ISLAND_AREA = "bigIslandArea"
    private const val KEY_SMALL_ISLAND_AREA = "smallIslandArea"
    private const val KEY_IMAGE_TEXT_INFO_LEFT = "imageTextInfoLeft"
    private const val KEY_SAME_WIDTH_DIGIT_INFO = "sameWidthDigitInfo"
    private const val KEY_PIC_INFO = "picInfo"
    private const val KEY_PIC = "pic"

    /** `picInfo.type` / `animIconInfo.type` 取值为 2 时表示 Lottie 动画资源。 */
    private const val PIC_TYPE_LOTTIE = 2

    /** `imageTextInfoLeft.type`：1 = 图文组件1。 */
    private const val IMAGE_TEXT_TYPE = 1

    /** `islandProperty`：1 = 常规岛。 */
    private const val ISLAND_PROPERTY_NORMAL = 1

    /**
     * 构建 `miui.focus.param` 的值（一个 JSON 字符串）。
     *
     * 计时运行中时，图标自动播放（`autoplay = true`）；
     * 暂停/结束时自动改为 `false`——与官方秒表的行为一致
     * （官方暂停态模板里两处 `autoplay` 都是 `false`）。
     */
    fun build(template: IslandTemplate): String {
        val isRunning = template.timer?.mode?.isRunning ?: false

        val paramV2 = JsonObject().apply {
            addProperty(KEY_PROTOCOL, 1)
            addProperty(KEY_UPDATABLE, true)
            addProperty(KEY_ENABLE_FLOAT, false)
            addProperty(KEY_AOD_TITLE, template.content)
            addProperty(KEY_AOD_PIC, template.iconKey)

            if (template.timer != null) {
                add(KEY_HIGHLIGHT_INFO, buildHighlightInfo(template))
            }
            if (template.actions.isNotEmpty()) {
                add(KEY_ACTIONS, buildActions(template.actions))
            }

            add(KEY_PARAM_ISLAND, buildParamIsland(template, isRunning))

            if (template.timer != null) {
                add(KEY_ANIM_TEXT_INFO, buildAnimTextInfo(template, isRunning))
            }

            addProperty(KEY_BUSINESS, template.business)
        }

        return JsonObject().apply { add("param_v2", paramV2) }.toString()
    }

    /** `highlightInfo`：大岛强调区，含计时与副标题。 */
    private fun buildHighlightInfo(template: IslandTemplate): JsonObject = JsonObject().apply {
        add(KEY_TIMER_INFO, buildTimerInfo(template.timer!!))
        addProperty(KEY_SUB_CONTENT, template.content)
        // picFunction 是强调区的小图标，与主图标分开
        addProperty(KEY_PIC_FUNCTION, template.iconKey)
    }

    /** `actions`：按钮数组，只写 key 引用，真正的 Action 在 extras 的 Bundle 里。 */
    private fun buildActions(actions: List<IslandAction>): JsonArray = JsonArray().apply {
        actions.take(IslandTemplate.MAX_ACTIONS).forEach { action ->
            add(JsonObject().apply { addProperty(KEY_ACTION, action.slot.key) })
        }
    }

    /** `param_island`：大岛 / 小岛的具体布局。 */
    private fun buildParamIsland(template: IslandTemplate, isRunning: Boolean): JsonObject =
        JsonObject().apply {
            addProperty(KEY_ISLAND_PROPERTY, ISLAND_PROPERTY_NORMAL)
            addProperty(KEY_ISLAND_ORDER, true)
            addProperty(KEY_ISLAND_TIMEOUT, template.islandTimeoutSec)

            add(
                KEY_BIG_ISLAND_AREA, JsonObject().apply {
                    add(KEY_IMAGE_TEXT_INFO_LEFT, buildImageTextInfoLeft(template, isRunning))
                    // 大岛的等宽数字区承载计时——只有它能显示走动的时间
                    if (template.timer != null) {
                        add(
                            KEY_SAME_WIDTH_DIGIT_INFO, JsonObject().apply {
                                add(KEY_TIMER_INFO, buildTimerInfo(template.timer))
                            }
                        )
                    }
                }
            )

            add(
                KEY_SMALL_ISLAND_AREA, JsonObject().apply {
                    add(KEY_PIC_INFO, buildPicInfo(template.iconKey, isRunning))
                }
            )
        }

    /** 大岛左侧图文区（图标）。 */
    private fun buildImageTextInfoLeft(template: IslandTemplate, isRunning: Boolean): JsonObject =
        JsonObject().apply {
            addProperty(KEY_TYPE, IMAGE_TEXT_TYPE)
            add(KEY_PIC_INFO, buildPicInfo(template.iconKey, isRunning))
        }

    /** `animTextInfo`：展开态的动画文本 + 计时。 */
    private fun buildAnimTextInfo(template: IslandTemplate, isRunning: Boolean): JsonObject =
        JsonObject().apply {
            add(KEY_TIMER_INFO, buildTimerInfo(template.timer!!))
            addProperty(KEY_CONTENT, template.content)
            add(
                KEY_ANIM_ICON_INFO, JsonObject().apply {
                    addProperty(KEY_TYPE, PIC_TYPE_LOTTIE)
                    addProperty(KEY_SRC, template.iconKey)
                    addProperty(KEY_AUTOPLAY, isRunning)
                }
            )
        }

    /** 图标引用。`type = 2` 表示 Lottie。 */
    private fun buildPicInfo(iconKey: String, isRunning: Boolean): JsonObject =
        JsonObject().apply {
            addProperty(KEY_TYPE, PIC_TYPE_LOTTIE)
            addProperty(KEY_PIC, iconKey)
            addProperty(KEY_AUTOPLAY, isRunning)
        }

    /** 计时信息。四处共用的同一结构。 */
    private fun buildTimerInfo(timer: TimerSpec): JsonObject = JsonObject().apply {
        addProperty(KEY_TIMER_TYPE, timer.mode.code)
        addProperty(KEY_TIMER_WHEN, timer.whenMs)
        addProperty(KEY_TIMER_SYSTEM_CURRENT, timer.systemCurrentMs)
    }
}

/**
 * 计时是否处于"进行中"——决定图标是否自动播放。
 *
 * 抽成扩展属性是为了让 [IslandTemplateBuilder] 里少写 when，
 * 也方便单测直接覆盖。
 */
internal val TimerMode.isRunning: Boolean
    get() = this == TimerMode.COUNT_UP_RUNNING || this == TimerMode.COUNTDOWN_RUNNING
