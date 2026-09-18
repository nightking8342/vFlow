package com.chaomixian.vflow.services.island

import android.content.Intent
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IslandTemplateBuilder] 的回归测试。
 *
 * 结构对齐小米官方秒表（`stopWatch`）模板的**运行态**与**暂停态**两份真实实例。
 * 若将来小米改协议，这些断言会立刻指出是哪一块变了。
 */
class IslandTemplateBuilderTest {

    // ── 测试夹具 ─────────────────────────────────────────────────

    private val startMs = 1_789_728_010_405L
    private val nowMs = 1_789_728_025_803L

    private fun runningTemplate() = IslandTemplate(
        cacheKey = "logcat",
        content = "正在采集日志",
        timer = TimerSpec.countUpRunning(startMs, nowMs),
        iconKey = "stopwatch_big",
    )

    private fun pausedTemplate() = IslandTemplate(
        cacheKey = "logcat",
        content = "已采集 2:31",
        timer = TimerSpec.countUpPaused(startMs, nowMs),
        iconKey = "stopwatch_big",
    )

    private fun parse(template: IslandTemplate): JsonObject =
        JsonParser.parseString(IslandTemplateBuilder.build(template)).asJsonObject

    private fun JsonObject.paramV2(): JsonObject = getAsJsonObject("param_v2")

    private fun JsonObject.timer(): JsonObject =
        paramV2().getAsJsonObject("highlightInfo").getAsJsonObject("timerInfo")

    // ── param_v2 包装（通道选择）─────────────────────────────────

    @Test
    fun `wraps everything under param_v2`() {
        val json = parse(runningTemplate())
        assertTrue("必须用 param_v2 包裹", json.has("param_v2"))
    }

    @Test
    fun `does not write top-level fields`() {
        // 实测顶层字段（scene / ticker / timerType 等）对岛的渲染没有实际效果，
        // 刻意不写以缩小与官方枚举校验冲突的面。
        val json = parse(runningTemplate())

        for (key in listOf("scene", "ticker", "content", "timerType", "timerWhen", "timerSystemCurrent")) {
            assertFalse("不应写顶层字段 $key", json.has(key))
        }
    }

    @Test
    fun `other top-level keys besides param_v2 are absent`() {
        // 只应有 param_v2 一个顶层 key
        assertEquals(setOf("param_v2"), parse(runningTemplate()).keySet())
    }

    // ── 计时器：四处冗余写入 ─────────────────────────────────────

    @Test
    fun `writes timer info in all three island areas`() {
        // 官方模板把同一份 timerInfo 冗余写在 `param_v2` 内的三处
        // （highlightInfo / sameWidthDigitInfo / animTextInfo）。
        // 不要"优化"成一处——SystemUI 究竟读哪一处未经证实。
        val json = parse(runningTemplate())
        val v2 = json.paramV2()

        assertTrue("highlightInfo.timerInfo", v2.getAsJsonObject("highlightInfo").has("timerInfo"))
        assertTrue("animTextInfo.timerInfo", v2.getAsJsonObject("animTextInfo").has("timerInfo"))
        assertTrue(
            "bigIslandArea.sameWidthDigitInfo.timerInfo",
            v2.getAsJsonObject("param_island")
                .getAsJsonObject("bigIslandArea")
                .getAsJsonObject("sameWidthDigitInfo")
                .has("timerInfo")
        )
        assertEquals(3, countTimerInfoOccurrences(json))
    }

    /** 递归统计 timerInfo 出现的次数。 */
    private fun countTimerInfoOccurrences(obj: JsonObject): Int {
        var count = 0
        for ((key, value) in obj.entrySet()) {
            if (key == "timerInfo") count++
            if (value.isJsonObject) count += countTimerInfoOccurrences(value.asJsonObject)
        }
        return count
    }

    @Test
    fun `running timer uses correct mode code`() {
        // 官方原文：1-正计时开始
        assertEquals(1, parse(runningTemplate()).timer().get("timerType").asInt)
    }

    @Test
    fun `paused timer uses correct mode code`() {
        // 官方原文：2-正计时暂停
        assertEquals(2, parse(pausedTemplate()).timer().get("timerType").asInt)
    }

    @Test
    fun `timer timestamps are written as milliseconds`() {
        val timer = parse(runningTemplate()).timer()
        assertEquals(startMs, timer.get("timerWhen").asLong)
        assertEquals(nowMs, timer.get("timerSystemCurrent").asLong)
    }

    @Test
    fun `paused timer keeps the original start time`() {
        // 暂停不应改写起点——恢复时才不会把已走过的时间算丢
        val timer = parse(pausedTemplate()).timer()
        assertEquals(startMs, timer.get("timerWhen").asLong)
    }

    @Test
    fun `all timer modes map to their documented codes`() {
        // 官方原文完整取值：-2-倒计时暂停, -1-倒计时开始, 0-默认值, 1-正计时开始, 2-正计时暂停
        val expected = mapOf(
            TimerMode.COUNTDOWN_PAUSED to -2,
            TimerMode.COUNTDOWN_RUNNING to -1,
            TimerMode.NONE to 0,
            TimerMode.COUNT_UP_RUNNING to 1,
            TimerMode.COUNT_UP_PAUSED to 2,
        )
        for ((mode, code) in expected) {
            assertEquals("$mode 应映射为 $code", code, mode.code)
        }
    }

    // ── autoplay：最容易踩的坑 ───────────────────────────────────

    @Test
    fun `running state sets autoplay true in every picture slot`() {
        // ⚠️ 官方 SystemUI 的 playAnimation() 里 autoplay 默认 false 会直接
        // pauseAnimation()——不显式写 true，图标是**静止的**。
        val json = parse(runningTemplate())
        val v2 = json.paramV2()

        val autoplays = collectAutoplayValues(json)
        assertTrue("至少要有 3 处图标 autoplay", autoplays.size >= 3)
        assertTrue("运行中必须全部 autoplay=true", autoplays.all { it })
    }

    @Test
    fun `paused state sets autoplay false in every picture slot`() {
        // 官方暂停态模板里两处 autoplay 都是 false
        val autoplays = collectAutoplayValues(parse(pausedTemplate()))
        assertTrue("暂停时图标应静止", autoplays.all { !it })
    }

    /** 收集所有 `autoplay` 布尔值。 */
    private fun collectAutoplayValues(obj: JsonObject): List<Boolean> {
        val result = mutableListOf<Boolean>()
        for ((key, value) in obj.entrySet()) {
            if (key == "autoplay" && value.isJsonPrimitive) result.add(value.asBoolean)
            if (value.isJsonObject) result.addAll(collectAutoplayValues(value.asJsonObject))
        }
        return result
    }

    // ── 图标 ─────────────────────────────────────────────────────

    @Test
    fun `icon key is written to big and small island and anim text`() {
        val json = parse(runningTemplate())
        val v2 = json.paramV2()
        val island = v2.getAsJsonObject("param_island")

        assertEquals(
            "stopwatch_big",
            island.getAsJsonObject("bigIslandArea")
                .getAsJsonObject("imageTextInfoLeft")
                .getAsJsonObject("picInfo").get("pic").asString
        )
        assertEquals(
            "stopwatch_big",
            island.getAsJsonObject("smallIslandArea")
                .getAsJsonObject("picInfo").get("pic").asString
        )
        assertEquals(
            "stopwatch_big",
            v2.getAsJsonObject("animTextInfo").getAsJsonObject("animIconInfo").get("src").asString
        )
    }

    @Test
    fun `lottie pictures use type 2`() {
        // type=2 表示 Lottie 动画资源（系统内置，无需应用自备图片）
        val island = parse(runningTemplate()).paramV2().getAsJsonObject("param_island")
        assertEquals(
            2,
            island.getAsJsonObject("smallIslandArea")
                .getAsJsonObject("picInfo").get("type").asInt
        )
        assertEquals(
            2,
            parse(runningTemplate()).paramV2()
                .getAsJsonObject("animTextInfo")
                .getAsJsonObject("animIconInfo").get("type").asInt
        )
    }

    // ── 按钮 ─────────────────────────────────────────────────────

    @Test
    fun `writes action key references`() {
        val template = runningTemplate().copy(
            actions = listOf(
                IslandAction(ActionSlot.PRIMARY, "结束", Intent("test.STOP"))
            )
        )
        val actions = parse(template).paramV2().getAsJsonArray("actions")

        assertEquals(1, actions.size())
        assertEquals(
            "miui.focus.action_1",
            actions[0].asJsonObject.get("action").asString
        )
    }

    @Test
    fun `omits actions array when there are no actions`() {
        assertFalse(parse(runningTemplate()).paramV2().has("actions"))
    }

    @Test
    fun `caps actions at the template limit of two`() {
        // 模板只预留两个按钮位置，多余的会被丢弃——显式截断而不是静默丢弃
        val template = runningTemplate().copy(
            actions = listOf(
                IslandAction(ActionSlot.PRIMARY, "A", Intent("a")),
                IslandAction(ActionSlot.SECONDARY, "B", Intent("b")),
                IslandAction(ActionSlot.PRIMARY, "C", Intent("c")),
            )
        )
        val actions = parse(template).paramV2().getAsJsonArray("actions")
        assertEquals(2, actions.size())
    }

    // ── 无计时器的静态文本 ───────────────────────────────────────

    @Test
    fun `static template omits all timer fields`() {
        val template = IslandTemplate(
            cacheKey = "x",
            content = "静态",
            timer = null,
            iconKey = "stopwatch",
        )
        val json = parse(template)

        assertEquals(0, countTimerInfoOccurrences(json))
        // 无计时器时不应写 animTextInfo 与 sameWidthDigitInfo（它们靠计时才有意义）
        val v2 = json.paramV2()
        assertFalse(v2.has("animTextInfo"))
        assertFalse(
            v2.getAsJsonObject("param_island")
                .getAsJsonObject("bigIslandArea").has("sameWidthDigitInfo")
        )
    }

    // ── 时延与统计标识 ───────────────────────────────────────────

    @Test
    fun `writes island timeout in seconds`() {
        // ⚠️ islandTimeout 单位是**秒**，而通知的 timeout 是**分钟**——别弄混
        val template = runningTemplate().copy(islandTimeoutSec = 300)
        assertEquals(
            300,
            parse(template).paramV2().getAsJsonObject("param_island")
                .get("islandTimeout").asInt
        )
    }

    @Test
    fun `default island timeout follows the official template`() {
        assertEquals(
            43_200,
            parse(runningTemplate()).paramV2().getAsJsonObject("param_island")
                .get("islandTimeout").asInt
        )
    }

    @Test
    fun `writes business identifier`() {
        assertEquals("vflow", parse(runningTemplate()).paramV2().get("business").asString)
    }

    @Test
    fun `business can be overridden per feature`() {
        val template = runningTemplate().copy(business = "vflow_logcat")
        assertEquals("vflow_logcat", parse(template).paramV2().get("business").asString)
    }

    // ── 输出是合法 JSON 字符串 ───────────────────────────────────

    @Test
    fun `output is a valid json string`() {
        val raw = IslandTemplateBuilder.build(runningTemplate())
        assertTrue(raw.startsWith("{"))
        // 能再解析一次不抛异常即说明结构合法
        JsonParser.parseString(raw).asJsonObject
    }

    @Test
    fun `updatable is enabled so the island can be refreshed`() {
        assertEquals(true, parse(runningTemplate()).paramV2().get("updatable").asBoolean)
    }

    @Test
    fun `enableFloat is false to avoid auto expanding on every update`() {
        // 每次更新都自动展开会很吵——官方运行态模板也是 false
        assertEquals(false, parse(runningTemplate()).paramV2().get("enableFloat").asBoolean)
    }
}
