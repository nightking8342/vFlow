package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 玻璃 Tab 栏的**格宽推导**。
 *
 * ## 为什么这些必须被单测锁住
 *
 * 格宽这个数同时喂给**四处**：指示块定位（`translationX = value * tabWidthPx`）、
 * 拖动换算（`dragAmount / tabWidthPx`）、点击反查（`position.x / tabWidthPx`）、
 * 栏宽（`tabWidth * tabs.size`）。推错了**一处都不报错**，表现分别是
 * 「指示块停在两格之间」「拖一格跳两格」「点中间那个选中左边的」——
 * 都是**只有上手滑才看得出来**的形态。
 *
 * ## 为什么是纯函数
 *
 * `glassTabWidthFor` 刻意**不接收 `TextMeasurer`** —— 量的活在外面做，
 * 这里只做纯算术。于是这条推导可以在纯 JVM 里逐档锁边界，
 * 不必起 Compose 测试环境（本项目也没有 Compose 测试环境）。
 */
class GlassTabWidthTest {

    private val padding = 16.dp

    /**
     * ⚠️ 用容差比较而不是 `assertEquals(Dp, Dp)`。
     *
     * 上限那一支是 `availableWidth * maxFraction`，而 `0.6f` 在二进制里不精确
     * （`400f * 0.6f = 240.00002f`）⇒ 精确相等断言会在「上限生效」的用例上
     * **必然变红**，而那是**唯一**能覆盖上限分支的情形。第一版就是这么写的，
     * 四个用例里有两个因此失败 —— 断言的是浮点误差，不是行为。
     */
    private fun assertDpEquals(expected: Dp, actual: Dp, label: String = "") {
        assertEquals(label, expected.value.toDouble(), actual.value.toDouble(), 0.01)
    }

    private fun width(
        contents: List<Int>,
        available: Int,
        min: Int = 64,
        maxFraction: Float = 0.6f,
    ) = glassTabWidthFor(
        contentWidths = contents.map { it.dp },
        availableWidth = available.dp,
        horizontalPadding = padding,
        minWidth = min.dp,
        maxFraction = maxFraction,
    )

    // ────────────────────────────────────────────────
    // 核心：宽的那格说了算，且不会把计数挤没
    // ────────────────────────────────────────────────

    @Test
    fun `the widest content decides the shared width`() {
        // 中文分类栏的实际数字（内容宽 ≈ 名字 + 空格 + 计数）：
        // 「全部 4150」≈ 66dp、「通讯社交 633」≈ 84dp、「操作 792」≈ 62dp。
        // 取最宽的 84 ⇒ +16 内边距 ⇒ 100dp。
        // ⚠️ 这正是**旧实现（均分 + 76dp 下限）算错的地方**：旧值 = max(328/13, 76) = 76dp，
        //    84dp 的内容放进 76dp 的格子 ⇒ 「通讯社交」被截成「通讯社…」，
        //    而计数排在它后面 ⇒ **整个消失**（本次要修的缺陷）。
        val w = width(contents = listOf(66, 84, 62), available = 328)
        assertDpEquals(100.dp, w)
        assertTrue("格宽必须 ≥ 最宽内容 + 内边距", w >= 100.dp)
    }

    @Test
    fun `english names make the bar wider without clamping the counter`() {
        // 英文 `Communication 633` 的实测值（labelLarge 14sp ≈ 13 字符 × 7.2dp）。
        // 关键断言：**宽度跟着内容长**，而不是被某个固定上限卡住。
        // 旧实现受 GLASS_TAB_BAR_DESIRED_TAB_WIDTH = 92dp 封顶 ⇒ 英文全被截。
        val w = width(contents = listOf(120, 138, 95), available = 871)
        assertDpEquals(154.dp, w)
    }

    // ────────────────────────────────────────────────
    // 两条边界：下限（触控目标）与上限（防一格占满屏）
    // ────────────────────────────────────────────────

    @Test
    fun `narrow content is raised to the minimum touch target`() {
        // 内容只有 30dp（一个字的极短名 + 一格计数） ⇒ 30 + 16 内边距 = 46dp，
        // 低于下限 64 ⇒ 抬到 64。
        // ⚠️ 断言的是「抬高后的值恰好是下限」，不是「≥ 下限」—— 后者对
        //    「忘了抬」的实现照样绿（它只是恰好也不算小）。
        assertDpEquals(64.dp, width(contents = listOf(20, 30), available = 871))
    }

    @Test
    fun `an absurdly long folder name is capped at the fraction`() {
        // 「我的超长文件夹名字abcdefgh 12」内容宽 600dp，在 400dp 屏上。
        // 上限 = 400 × 0.6 = 240dp ⇒ 封顶（名字自己回落到省略号，与改动前一致）。
        assertDpEquals(240.dp, width(contents = listOf(600, 70), available = 400))
    }

    @Test
    fun `the cap is a fraction of the available width, not a constant`() {
        // 同一条内容，屏越宽上限越松 —— 锁住「比例」而不是「某个数」。
        assertDpEquals(240.dp, width(contents = listOf(999), available = 400))
        assertDpEquals(480.dp, width(contents = listOf(999), available = 800))
    }

    // ────────────────────────────────────────────────
    // 退化输入：首帧 / 空列表 / 下限与上限打架
    // ────────────────────────────────────────────────

    @Test
    fun `empty tabs produce zero width`() {
        // 调用方据此跳过指示块的渲染（`if (tabWidthPx > 0f)`）。
        // ⚠️ 若这里返回 minWidth 而不是 0，空列表会渲染出一枚孤零零的指示块。
        assertDpEquals(0.dp, width(contents = emptyList(), available = 328))
    }

    @Test
    fun `zero available width does not invert the range`() {
        // ⚠️⚠️ **实测踩过的崩溃点**：`BoxWithConstraints` 首帧的 `maxWidth` 可能是 0，
        //    此时上限 = 0 × 0.6 = 0 < 下限 64 ⇒ 直接 `coerceIn(64.dp, 0.dp)`
        //    抛 `IllegalArgumentException: Cannot coerce value to an empty range`。
        //    修法是上限取 `max(比例值, 下限)`，本用例是那条修法的**反向锁**。
        assertDpEquals(64.dp, width(contents = listOf(80), available = 0))
    }

    @Test
    fun `a minimum larger than the fraction cap still wins`() {
        // 下限比上限还大时以下限为准（触控目标优先于比例上限）。
        assertDpEquals(200.dp, width(contents = listOf(300), available = 100, min = 200))
    }

    // ────────────────────────────────────────────────
    // 工作流页与图标页现在走**同一条**推导（这是本改动的前提）
    // ────────────────────────────────────────────────

    @Test
    fun `both pages go through the same width derivation`() {
        // ⚠️ 存在理由：本改动的**全部价值**是「两个页面行为一致」。
        //    若哪天有人给某一页重新加一个专属宽度参数（旧实现就是那样：
        //    图标页传 `minTabWidth`、工作流页不传 ⇒ 两页表现不同），
        //    这条断言会变红。
        val bar = SourceScan.stripped(SRC_BAR)
        val iconBar = SourceScan.stripped(SRC_ICON_CATEGORY_BAR)

        assertTrue("玻璃栏必须调 glassTabWidthFor", bar.contains("glassTabWidthFor("))
        assertFalse(
            "`minTabWidth` 已删除，不得复活（它正是两页表现不一致的来源）",
            bar.contains("minTabWidth"),
        )
        assertFalse(
            "图标分类栏不得再传页面专属的宽度参数",
            iconBar.contains("minTabWidth"),
        )
        assertTrue(
            "图标分类栏仍须走同一个分派入口",
            iconBar.contains("WorkflowFolderTabBarSwitch("),
        )
    }

    @Test
    fun `both variants honour the same inset`() {
        // ⚠️ 存在理由：调用方按**玻璃版的口径**把留白从 `modifier` 挪到了 `contentInset`
        //    （列表模式就是这样，见 `WorkflowListScreen`）。普通版没有「视口内/视口外」
        //    的区分，`Switch` 里**不把它补成 padding** 的话，关掉液态玻璃后第一枚
        //    Chip 会**贴着屏幕左边缘** —— 而玻璃版正常，排障只会往玻璃那条路找。
        //    ⚠️ 反证：把这一行改回裸 `modifier = modifier`，下面的断言变红。
        val bar = SourceScan.stripped(SRC_BAR)
        val switchBody = SourceScan.functionBody(bar, "internal fun WorkflowFolderTabBarSwitch(")
        assertTrue("必须能截到 Switch 函数体（防改名后断言空转）", switchBody != null)

        assertTrue(
            "普通版必须把 contentInset 补成 padding",
            switchBody!!.contains("modifier.padding(horizontal = contentInset)"),
        )
    }

    @Test
    fun `the measured styles match the ones actually drawn`() {
        // ⚠️⚠️ 这是本文件最重要的一条：量宽度用的样式若与 `GlassFolderTabContent`
        //    实际画的不一致，量出来的宽就**不等于**实际占用 —— 选中项的名字
        //    （SemiBold 比 Normal 略宽）会被自己的格宽卡出一个省略号，且不报错。
        val bar = SourceScan.stripped(SRC_BAR)
        val measureBody = SourceScan.functionBody(bar, "val tabWidth = glassTabWidthFor(")
        val drawBody = SourceScan.functionBody(bar, "private fun GlassFolderTabContent(")

        assertTrue("必须能截到两段函数体（防签名改名后断言空转）", measureBody != null && drawBody != null)

        listOf("labelLarge", "labelSmall", "FontWeight.SemiBold", "FontWeight.Normal").forEach { token ->
            assertTrue("量宽处缺少 $token", measureBody!!.contains(token))
            assertTrue("绘制处缺少 $token", drawBody!!.contains(token))
        }
    }

    @Test
    fun `the content padding constant is shared between measuring and drawing`() {
        // ⚠️ 两处脱节的话算出来的格宽与实际排版差一点 —— 不报错，
        //    只是名字在临界情况下又开始出现省略号（正是本次修的形态）。
        val bar = SourceScan.stripped(SRC_BAR)
        // 3 处 = 常量声明本身 + 量宽处 + 排版处。
        // ⚠️ 刻意**不用** `SourceScan.countOccurrences` —— 它只剥注释、不剥文档，
        //    而本文件的 KDoc 里就写着这个常量名（`[GLASS_TAB_CONTENT_HORIZONTAL_PADDING]`）
        //    ⇒ 数出来是 4，断言恒红（第一版就是这么写的）。改成剥掉字符串外的注释后
        //    再数 —— `SourceScan.stripped` 的整行注释保留的是空行、不留内容。
        assertEquals(
            "量宽与排版的左右内边距必须用同一个常量（见本用例注释里的口径说明）",
            3,
            Regex("GLASS_TAB_CONTENT_HORIZONTAL_PADDING")
                .findAll(bar)
                .count(),
        )
    }

    private companion object {
        const val SRC_BAR =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowFolderGlassTabBar.kt"
        const val SRC_ICON_CATEGORY_BAR =
            "src/main/java/com/chaomixian/vflow/ui/workflow_editor/IconCategoryBar.kt"
    }
}
