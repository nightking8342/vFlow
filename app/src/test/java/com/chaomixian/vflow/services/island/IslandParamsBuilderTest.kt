package com.chaomixian.vflow.services.island

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [IslandParamsBuilder] 的回归测试。
 *
 * 岛的真机渲染无法在单测里验证（需要澎湃 OS3 设备），但**字段有没有拼对**可以。
 * 本测试把「JSON 结构正确性」与「真机渲染效果」这两个不确定性拆开——前者在这里锁住，
 * 后者交给真机验证，避免调试时两者混在一起难以归因。
 *
 * 字段名与结构依据《小米超级岛开发接入文档》与《小米超级岛模板库》。
 */
class IslandParamsBuilderTest {

    private fun paramOf(
        title: String = "每日签到",
        state: IslandNotificationSpec.State = IslandNotificationSpec.State.RUNNING,
        stepName: String? = "延迟",
        statusText: String? = "正在延迟 6000ms",
        progressText: String? = "3/8",
    ) = JsonParser.parseString(
        IslandParamsBuilder.buildParam(title, state, stepName, statusText, progressText)
    ).asJsonObject

    private fun paramV2Of(
        title: String = "每日签到",
        state: IslandNotificationSpec.State = IslandNotificationSpec.State.RUNNING,
        stepName: String? = "延迟",
        statusText: String? = "正在延迟 6000ms",
        progressText: String? = "3/8",
    ) = paramOf(title, state, stepName, statusText, progressText).getAsJsonObject("param_v2")

    // ------------------------------------------------------------------
    // 顶层结构
    // ------------------------------------------------------------------

    /** 顶层必须是 `param_v2` 包裹——模板路径的固定形状。 */
    @Test
    fun paramIsWrappedInParamV2() {
        val param = paramOf()

        assertTrue("缺少 param_v2 包裹层", param.has("param_v2"))
        assertTrue(param.get("param_v2").isJsonObject)
    }

    /** 必填的运营场景标识不能为空。 */
    @Test
    fun businessIsAlwaysPresent() {
        val paramV2 = paramV2Of()

        assertEquals("vflow_workflow", paramV2.get("business").asString)
    }

    /**
     * 岛数据块必须完整。
     *
     * 官方文档标注 `param_island` 必填，其中 `bigIslandArea` / `smallIslandArea` 也必填；
     * 缺任何一个岛都不会渲染。
     */
    @Test
    fun islandBlockContainsAllRequiredSections() {
        val island = paramV2Of().getAsJsonObject("param_island")

        assertNotNull("缺少 param_island", island)
        assertTrue("缺少 bigIslandArea", island.has("bigIslandArea"))
        assertTrue("缺少 smallIslandArea", island.has("smallIslandArea"))
        assertNotNull("缺少 islandProperty", island.get("islandProperty"))
        assertNotNull("缺少 islandTimeout", island.get("islandTimeout"))
    }

    /** 持续性通知必须置真，否则岛不会随更新刷新。 */
    @Test
    fun notificationIsMarkedUpdatable() {
        val paramV2 = paramV2Of()

        assertTrue(paramV2.get("updatable").asBoolean)
        assertEquals("reopen", paramV2.get("reopen").asString)
    }

    // ------------------------------------------------------------------
    // 大岛 / 小岛结构
    // ------------------------------------------------------------------

    /** 大岛 A 区必须是「图文组件1」（type=1），且含图标与主文本。 */
    @Test
    fun bigIslandLeftAreaIsImageTextComponent() {
        val left = paramV2Of()
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft")

        assertEquals("A 区组件类型应为 1（图文组件1）", 1, left.get("type").asInt)
        assertTrue("A 区缺少 picInfo", left.has("picInfo"))
        assertTrue("A 区缺少 textInfo", left.has("textInfo"))
        // A 区放**「进度 · 步骤名」**（不是工作流名）。
        assertTrue(
            "A 区应含进度与步骤名",
            left.getAsJsonObject("textInfo").get("title").asString.contains("3/8")
        )
    }

    // ------------------------------------------------------------------
    // B 区：前置小字 + 大字（方案 ii）
    // ------------------------------------------------------------------

    /** A 区把进度与步骤名拼成「3/8 · 延迟」，不加「步骤」前缀（挤占窄空间）。 */
    @Test
    fun leftAreaJoinsProgressAndStepName() {
        val leftText = paramV2Of(
            state = IslandNotificationSpec.State.RUNNING,
            progressText = "3/8",
            stepName = "延迟",
        )
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft")
            .getAsJsonObject("textInfo")

        assertEquals("3/8 · 延迟", leftText.get("title").asString)
    }

    /** 只有进度时 A 区只显示进度，不出现多余的分隔符。 */
    @Test
    fun leftAreaOmitsSeparatorWhenStepNameMissing() {
        val leftText = paramV2Of(
            state = IslandNotificationSpec.State.RUNNING,
            progressText = "3/8",
            stepName = null,
        )
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft")
            .getAsJsonObject("textInfo")

        assertEquals("3/8", leftText.get("title").asString)
    }

    /** 两者都缺时 A 区不应写出空文本块。 */
    @Test
    fun missingProgressAndStepNameOmitsLeftText() {
        val leftText = paramV2Of(
            state = IslandNotificationSpec.State.RUNNING,
            progressText = null,
            stepName = null,
        )
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("imageTextInfoLeft")
            .getAsJsonObject("textInfo")

        assertFalse("无进度也无步骤名时不应有 title", leftText.has("title"))
    }

    /**
     * **B 区放模块实时状态**（这是本次改动的核心）——不再是步骤名。
     *
     * 状态文案来自模块 `onProgress`（如「正在延迟 6000ms」），比模块名信息量大。
     */
    @Test
    fun rightAreaCarriesModuleStatusText() {
        val textInfo = paramV2Of(
            state = IslandNotificationSpec.State.RUNNING,
            stepName = "延迟",
            statusText = "正在延迟 6000ms",
        )
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("textInfo")

        assertEquals("正在延迟 6000ms", textInfo.get("title").asString)
    }

    /** 没有状态文案时 B 区不应写出空块。 */
    @Test
    fun missingStatusTextOmitsRightArea() {
        val textInfo = paramV2Of(state = IslandNotificationSpec.State.RUNNING, statusText = null)
            .getAsJsonObject("param_island")
            .getAsJsonObject("bigIslandArea")
            .getAsJsonObject("textInfo")

        assertFalse("无状态文案时不应有 title", textInfo.has("title"))
    }

    /**
     * 终态在 B 区大字位显示状态词（执行中该位放步骤名）。
     *
     * 避免终态时 B 区出现「只有前置小字、没有大字」的残缺结构。
     */
    @Test
    fun terminalStatesShowStatusWordInLeftArea() {
        val expected = mapOf(
            IslandNotificationSpec.State.COMPLETED to "已完成",
            IslandNotificationSpec.State.FAILED to "失败",
            IslandNotificationSpec.State.CANCELLED to "已停止",
        )

        expected.forEach { (state, word) ->
            val leftText = paramV2Of(state = state, stepName = null)
                .getAsJsonObject("param_island")
                .getAsJsonObject("bigIslandArea")
                .getAsJsonObject("imageTextInfoLeft")
                .getAsJsonObject("textInfo")

            assertEquals("$state 应在 A 区显示状态词", word, leftText.get("title").asString)
        }
    }

    /** 终态 A 区显示状态词，不残留进度（进度是执行中的语义）。 */
    @Test
    fun terminalStatesReplaceProgressWithStatusWord() {
        listOf(
            IslandNotificationSpec.State.COMPLETED,
            IslandNotificationSpec.State.FAILED,
            IslandNotificationSpec.State.CANCELLED,
        ).forEach { state ->
            val leftText = paramV2Of(state = state, progressText = "3/8")
                .getAsJsonObject("param_island")
                .getAsJsonObject("bigIslandArea")
                .getAsJsonObject("imageTextInfoLeft")
                .getAsJsonObject("textInfo")

            assertFalse(
                "$state 的 A 区不应残留进度文本",
                leftText.get("title").asString.contains("/")
            )
        }
    }

    /** 小岛只放图标。 */
    @Test
    fun smallIslandContainsOnlyIcon() {
        val small = paramV2Of()
            .getAsJsonObject("param_island")
            .getAsJsonObject("smallIslandArea")

        assertTrue("小岛缺少 picInfo", small.has("picInfo"))
        assertEquals(
            IslandParamsBuilder.PIC_APP,
            small.getAsJsonObject("picInfo").get("pic").asString
        )
    }

    /** 图片引用格式必须是 `{"type":1,"pic":"<key>"}`。 */
    @Test
    fun pictureReferencesUseExpectedShape() {
        val picRef = paramV2Of()
            .getAsJsonObject("param_island")
            .getAsJsonObject("smallIslandArea")
            .getAsJsonObject("picInfo")

        assertEquals(1, picRef.get("type").asInt)
        assertTrue("pic key 不应为空", picRef.get("pic").asString.isNotBlank())
    }

    /**
     * 岛参数 JSON 里不出现 RemoteViews 相关键。
     *
     * 这两个键（`miui.focus.rv` / `miui.focus.param.custom`）是**通知 extras 的 key**，
     * 不是 JSON 内容——由 `IslandNotificationDispatcher` 负责写入 extras。
     * 本测试锁住「JSON 只承载岛数据、不承载 RemoteViews 引用」这条边界，
     * 避免有人把 rv 序列化进 JSON。
     *
     * 注：早期版本本测试断言「绝不能设 miui.focus.rv」，那是基于
     * 「设了会让整份模板作废」的**错误理解**（实际只接管展开态，param_island 照常生效）。
     * 该误解已修正，见 design 文档 §1.6。
     */
    @Test
    fun paramJsonDoesNotCarryRemoteViewsKeys() {
        val raw = IslandParamsBuilder.buildParam(
            "每日签到", IslandNotificationSpec.State.RUNNING, "延迟", "正在延迟 6000ms", "3/8"
        )

        assertFalse("rv 不应序列化进 JSON", raw.contains("miui.focus.rv"))
        assertFalse("param.custom 是 extras key，不应出现在 JSON 里", raw.contains("param.custom"))
    }

    // ------------------------------------------------------------------
    // 状态相关的行为
    // ------------------------------------------------------------------

    /**
     * 执行中绝不自动浮出。
     *
     * 工作流每推进一步就更新一次通知，若每次更新都展开岛会变成持续骚扰
     * （mindfs 在同类实现里踩过这个坑）。
     */
    @Test
    fun runningStateNeverAutoFloats() {
        val paramV2 = paramV2Of(state = IslandNotificationSpec.State.RUNNING)

        assertFalse("执行中不应自动浮出", paramV2.get("enableFloat").asBoolean)
        assertFalse("执行中不应首次浮出", paramV2.get("islandFirstFloat").asBoolean)
    }

    /** 需要用户注意的终态才自动浮出。 */
    @Test
    fun terminalStatesThatNeedAttentionAutoFloat() {
        listOf(
            IslandNotificationSpec.State.COMPLETED,
            IslandNotificationSpec.State.FAILED,
        ).forEach { state ->
            val paramV2 = paramV2Of(state = state)
            assertTrue(
                "$state 应自动浮出",
                paramV2.get("enableFloat").asBoolean && paramV2.get("islandFirstFloat").asBoolean
            )
        }
    }

    /** 用户主动停止不需要浮出打扰。 */
    @Test
    fun cancelledStateDoesNotAutoFloat() {
        val paramV2 = paramV2Of(state = IslandNotificationSpec.State.CANCELLED)

        assertFalse(paramV2.get("enableFloat").asBoolean)
        assertFalse(paramV2.get("islandFirstFloat").asBoolean)
    }

    /** 完成与失败使用强调色，执行中与取消不用。 */
    @Test
    fun highlightColorOnlyForStatesNeedingAttention() {
        assertFalse(
            "执行中不应使用强调色",
            paramV2Of(state = IslandNotificationSpec.State.RUNNING)
                .getAsJsonObject("param_island")
                .getAsJsonObject("bigIslandArea")
                .getAsJsonObject("textInfo")
                .get("showHighlightColor").asBoolean
        )
        assertFalse(
            "取消不应使用强调色",
            paramV2Of(state = IslandNotificationSpec.State.CANCELLED)
                .getAsJsonObject("param_island")
                .getAsJsonObject("bigIslandArea")
                .getAsJsonObject("textInfo")
                .get("showHighlightColor").asBoolean
        )
        listOf(
            IslandNotificationSpec.State.COMPLETED,
            IslandNotificationSpec.State.FAILED,
        ).forEach { state ->
            assertTrue(
                "$state 应使用强调色",
                paramV2Of(state = state)
                    .getAsJsonObject("param_island")
                    .getAsJsonObject("bigIslandArea")
                    .getAsJsonObject("textInfo")
                    .get("showHighlightColor").asBoolean
            )
        }
    }

    /** 失败态的强调色必须与正常态不同（否则用户分不出成功与失败）。 */
    @Test
    fun failedStateUsesDistinctHighlightColor() {
        val runningColor = paramV2Of(state = IslandNotificationSpec.State.RUNNING)
            .getAsJsonObject("param_island").get("highlightColor").asString
        val failedColor = paramV2Of(state = IslandNotificationSpec.State.FAILED)
            .getAsJsonObject("param_island").get("highlightColor").asString

        assertTrue("失败色不应与正常色相同", runningColor != failedColor)
        assertTrue("颜色应为 #RRGGBB 形式", failedColor.matches(Regex("^#[0-9A-Fa-f]{6}$")))
    }

    /** 失败态的岛存活时长应长于完成态（用户需要时间去处理）。 */
    @Test
    fun failedStateLivesLongerThanCompleted() {
        val completedTimeout = paramV2Of(state = IslandNotificationSpec.State.COMPLETED)
            .getAsJsonObject("param_island").get("islandTimeout").asInt
        val failedTimeout = paramV2Of(state = IslandNotificationSpec.State.FAILED)
            .getAsJsonObject("param_island").get("islandTimeout").asInt

        assertTrue(
            "失败态(${failedTimeout}s)应长于完成态(${completedTimeout}s)",
            failedTimeout > completedTimeout
        )
    }

    /** 执行中的岛必须能覆盖长工作流（不能中途消失）。 */
    @Test
    fun runningStateTimeoutCoversLongWorkflows() {
        val timeout = paramV2Of(state = IslandNotificationSpec.State.RUNNING)
            .getAsJsonObject("param_island").get("islandTimeout").asInt

        assertTrue("执行中岛的存活时长过短：${timeout}s", timeout >= 1800)
    }

    /** ticker / 息屏文案必须非空，否则 OS2 与状态栏没有内容可显示。 */
    @Test
    fun tickerAndAodTextArePresent() {
        val paramV2 = paramV2Of()

        assertTrue(paramV2.get("ticker").asString.isNotBlank())
        assertTrue(paramV2.get("tickerPic").asString.isNotBlank())
        assertTrue(paramV2.get("aodTitle").asString.isNotBlank())
        assertTrue(paramV2.get("aodPic").asString.isNotBlank())
    }

    /**
     * 工作流名出现在 ticker 与息屏文案中。
     *
     * **大岛上不再显示工作流名**——A 区让给了步骤进度、B 区让给了步骤名。
     * 这是有意的取舍：执行期间用户更关心「跑到哪一步」，
     * 而自己发起的工作流通常知道是哪个。
     */
    @Test
    fun workflowNameAppearsInTickerAndAod() {
        val name = "到家开灯"
        val paramV2 = paramV2Of(title = name)

        assertTrue("ticker 应含工作流名", paramV2.get("ticker").asString.contains(name))
        assertTrue("aodTitle 应含工作流名", paramV2.get("aodTitle").asString.contains(name))
    }

    /** 各状态产出不同文案，用户能区分。 */
    @Test
    fun tickerTextDiffersPerState() {
        val texts = IslandNotificationSpec.State.entries.map { state ->
            paramV2Of(state = state).get("ticker").asString
        }

        assertEquals("各状态的 ticker 文案应互不相同", texts.size, texts.toSet().size)
    }
}
