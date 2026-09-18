package com.chaomixian.vflow.services.island

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IslandParamsBuilder.buildCustomParam] 的回归测试。
 *
 * 自定义模式（配合 `miui.focus.rv` 的 RemoteViews 展开态）与模板模式的参数结构不同，
 * 但**岛摘要态数据必须一致**——这是「改用 RemoteViews 不影响大岛/小岛」的保证。
 *
 * 依据：mindfs `FocusIslandSupport.java:436-455`（真机验证过的实现）与
 * 设计文档 §1.6 的修订结论。
 */
class IslandCustomParamsTest {

    private fun customOf(
        title: String = "每日签到",
        state: IslandNotificationSpec.State = IslandNotificationSpec.State.RUNNING,
        stepName: String? = "延迟",
        statusText: String? = "正在延迟 6000ms",
        progressText: String? = "3/8",
    ) = JsonParser.parseString(
        IslandParamsBuilder.buildCustomParam(title, state, stepName, statusText, progressText)
    ).asJsonObject

    // ------------------------------------------------------------------
    // 结构差异：扁平 vs param_v2 包裹
    // ------------------------------------------------------------------

    /**
     * `param.custom` 必须是**扁平结构**——不裹 `param_v2`。
     *
     * SystemUI 在自定义模式下从根级直接读 `timeout` / `enableFloat` / `ticker`，
     * 裹了 `param_v2` 就读不到。
     */
    @Test
    fun customParamIsFlatWithoutParamV2Wrapper() {
        val custom = customOf()

        assertFalse("自定义模式不应有 param_v2 包裹层", custom.has("param_v2"))
        assertTrue("timeout 应在根级", custom.has("timeout"))
        assertTrue("enableFloat 应在根级", custom.has("enableFloat"))
        assertTrue("ticker 应在根级", custom.has("ticker"))
        assertTrue("updatable 应在根级", custom.has("updatable"))
    }

    /**
     * 与模板版对照：同样输入下，模板版有 `param_v2`，自定义版没有。
     *
     * 这条测试锁住两条路径的**结构差异**，防止有人「统一」它们。
     */
    @Test
    fun templateAndCustomHaveDifferentShapes() {
        val template = JsonParser.parseString(
            IslandParamsBuilder.buildParam(
                "每日签到", IslandNotificationSpec.State.RUNNING, "打开应用", "正在延迟 6000ms", "3/8"
            )
        ).asJsonObject
        val custom = customOf()

        assertTrue("模板版应裹 param_v2", template.has("param_v2"))
        assertFalse("自定义版不应裹 param_v2", custom.has("param_v2"))

        // 但两者的岛数据都应在顶层 JSON 的 param_island
        assertTrue(template.getAsJsonObject("param_v2").has("param_island"))
        assertTrue(custom.has("param_island"))
    }

    // ------------------------------------------------------------------
    // 核心保证：岛数据不受影响
    // ------------------------------------------------------------------

    /**
     * **最关键的一条**：自定义模式必须携带完整的 `param_island`。
     *
     * 早期误解认为「设了 miui.focus.rv 会让整份模板作废」，实际只是展开态改由
     * RemoteViews 渲染，大岛/小岛照常由 param_island 驱动。本条测试锁住这个结论。
     */
    @Test
    fun customParamStillCarriesCompleteIslandData() {
        val island = customOf().getAsJsonObject("param_island")

        assertNotNull("自定义模式必须携带 param_island", island)
        assertTrue("缺少 bigIslandArea", island.has("bigIslandArea"))
        assertTrue("缺少 smallIslandArea", island.has("smallIslandArea"))
        assertNotNull("缺少 islandProperty", island.get("islandProperty"))
        assertNotNull("缺少 islandTimeout", island.get("islandTimeout"))
        assertNotNull("缺少 highlightColor", island.get("highlightColor"))
    }

    /** 大岛 A 区结构完整（图标 + 工作流名）。 */
    @Test
    fun customParamBigIslandLeftAreaIsIntact() {
        val left = customOf()
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft")

        assertEquals(1, left.get("type").asInt)
        assertTrue(left.has("picInfo"))
        // A 区放**「进度 · 步骤名」**（不是工作流名）。
        assertEquals(
            "3/8 · 延迟",
            left.getAsJsonObject("textInfo").get("title").asString
        )
    }

    /**
     * 岛数据与模板版**逐字段一致**——两条路径共用同一个 `buildIslandParam`，
     * 只要传入相同的 stepName / progressText 就应产出完全相同的岛数据。
     *
     * 这是「改用 RemoteViews 不影响大岛/小岛」的硬保证：线上 dispatcher 对两个方法
     * 传的是同一组 `spec.stepName` / `spec.progressText`（`IslandNotificationDispatcher.kt:117-145`）。
     */
    @Test
    fun customAndTemplateProduceIdenticalIslandData() {
        val fromTemplate = JsonParser.parseString(
            IslandParamsBuilder.buildParam(
                "每日签到", IslandNotificationSpec.State.RUNNING, "打开应用", "正在延迟 6000ms", "3/8"
            )
        ).asJsonObject.getAsJsonObject("param_v2").getAsJsonObject("param_island")

        val fromCustom = JsonParser.parseString(
            IslandParamsBuilder.buildCustomParam(
                "每日签到", IslandNotificationSpec.State.RUNNING, "打开应用", "正在延迟 6000ms", "3/8"
            )
        ).asJsonObject.getAsJsonObject("param_island")

        assertEquals(
            "两条路径的岛数据必须完全一致",
            fromTemplate.toString(),
            fromCustom.toString()
        )
    }

    /** 各状态下两路径的岛数据都应一致（不只执行中）。 */
    @Test
    fun customAndTemplateAgreeOnIslandDataAcrossAllStates() {
        IslandNotificationSpec.State.entries.forEach { state ->
            val fromTemplate = JsonParser.parseString(
                IslandParamsBuilder.buildParam("W", state, "延迟", "正在延迟 6000ms", "1/2")
            ).asJsonObject.getAsJsonObject("param_v2").getAsJsonObject("param_island")
            val fromCustom = JsonParser.parseString(
                IslandParamsBuilder.buildCustomParam("W", state, "延迟", "正在延迟 6000ms", "1/2")
            ).asJsonObject.getAsJsonObject("param_island")

            assertEquals("$state 的岛数据应一致", fromTemplate.toString(), fromCustom.toString())
        }
    }

    // ------------------------------------------------------------------
    // 行为与模板版保持一致
    // ------------------------------------------------------------------

    @Test
    fun runningStateDoesNotAutoFloat() {
        val custom = customOf(state = IslandNotificationSpec.State.RUNNING)

        assertFalse(custom.get("enableFloat").asBoolean)
        assertFalse(custom.get("islandFirstFloat").asBoolean)
    }

    @Test
    fun attentionStatesAutoFloat() {
        listOf(
            IslandNotificationSpec.State.COMPLETED,
            IslandNotificationSpec.State.FAILED,
        ).forEach { state ->
            val custom = customOf(state = state)
            assertTrue(
                "$state 应自动浮出",
                custom.get("enableFloat").asBoolean && custom.get("islandFirstFloat").asBoolean
            )
        }
    }

    @Test
    fun cancelledStateDoesNotAutoFloat() {
        assertFalse(customOf(state = IslandNotificationSpec.State.CANCELLED)
            .get("enableFloat").asBoolean)
    }

    @Test
    fun businessAndUpdatableArePresent() {
        val custom = customOf()

        assertEquals("vflow_workflow", custom.get("business").asString)
        assertTrue(custom.get("updatable").asBoolean)
        assertEquals("reopen", custom.get("reopen").asString)
    }

    @Test
    fun tickerAndAodArePresent() {
        val custom = customOf()

        assertTrue(custom.get("ticker").asString.isNotBlank())
        assertTrue(custom.get("tickerPic").asString.isNotBlank())
        assertTrue(custom.get("aodTitle").asString.isNotBlank())
        assertTrue(custom.get("aodPic").asString.isNotBlank())
    }

    /**
     * 工作流名应出现在 ticker 与息屏文案中。
     *
     * 大岛上不显示工作流名（A 区放步骤进度、B 区放步骤名），这是有意的取舍。
     */
    @Test
    fun workflowNameSurvivesIntoCustomParam() {
        val name = "到家开灯"
        val custom = customOf(title = name)

        assertTrue("ticker 应含工作流名", custom.get("ticker").asString.contains(name))
        assertTrue("aodTitle 应含工作流名", custom.get("aodTitle").asString.contains(name))
    }

    /** 与上面的断言配套：确认大岛确实不再承载工作流名。 */
    @Test
    fun bigIslandCarriesProgressNotWorkflowName() {
        val name = "到家开灯"
        val leftText = customOf(title = name)
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft")
            .getAsJsonObject("textInfo")
            .get("title").asString

        assertFalse("A 区不应是工作流名", leftText == name)
    }

    /** 自定义参数 JSON 里不应出现 RemoteViews 键（那是 extras key，不是 JSON 内容）。 */
    @Test
    fun customParamJsonDoesNotCarryRemoteViewsKeys() {
        val raw = IslandParamsBuilder.buildCustomParam(
                "每日签到", IslandNotificationSpec.State.RUNNING, "打开应用", "正在延迟 6000ms", "3/8"
            )

        assertFalse(raw.contains("miui.focus.rv"))
    }
}
