package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片执行按钮（▶ / ⏸）的**颜色公式与「去掉底色方块」接线**锚定。
 *
 * ## ⚠️ 为什么需要这个文件
 *
 * 本条改动是「**照 ShortX 抄观感**」，而它的两半**都不会有任何行为测试变红**：
 *
 * 1. **颜色算错**是静默的 —— 只是色号差一点，没有断言能判定「这个红不对」；
 * 2. **底色方块加回来**也是静默的 —— 编译通过、卡片照常渲染，只是多一个色块。
 *
 * 而本仓库已有三次「纯函数全绿、集成点缺失」的记录（`CoreDexFingerprint`
 * 的 13 个单测全绿但 `CoreLauncher` 漏调 `recordLaunchedDexFingerprint`）。
 * 故：**公式用纯函数断言、接线用源码扫描断言**。
 *
 * ## 公式的来历（实测，不是猜的）
 *
 * 量的方法：ShortX 截图里逐张卡片取「左上角徽章」（= 该卡主题色）与
 * 「右上角三角笔画最纯处」，**逐通道相除**。7 张卡 × 3 通道 = 18 个比值，
 * **中位数恰为 0.400**（范围 0.386 ~ 0.435），且三角的色相/饱和度与主题色一致
 * ⇒ 主题色整体乘一个标量，而不是另配了一组暗色。
 *
 * ⚠️ 本测试**不可能**复现那次测量（要图片、要 Android 色彩工具），它能锁的是
 * 「公式没有被后人改宽/改窄」，以及「它确实被接到了两个调用点」。
 */
class WorkflowExecuteIconTest {

    // ------------------------------------------------------------------
    // 一、公式的纯函数部分
    // ------------------------------------------------------------------

    @Test
    fun `执行图标透明度锚定在实测值 0_40`() {
        assertEquals(
            "0.40 是照 ShortX 截图逐通道反解出的中位数（18 个比值的范围 0.386~0.435）。" +
                "改动它等于把观感从 ShortX 挪开，请先重新测量并同步 KDoc。",
            0.40f,
            WorkflowVisuals.EXECUTE_ICON_ALPHA,
            0.0001f,
        )
    }

    @Test
    fun `执行图标透明度必须明显低于强调色避免与卡片抢焦点`() {
        // 反向锁：抄成「和强调色一样亮」在主观上「也能看」，但它正是
        // 去掉底色方块要解决的问题 —— 去掉色块却在原地留一个同亮度的三角，
        // 观感上等于「色块变成三角」，白改一场。
        assertTrue(
            "EXECUTE_ICON_ALPHA 应远低于 accentBackground 的 0.72",
            WorkflowVisuals.EXECUTE_ICON_ALPHA < 0.5f,
        )
    }

    @Test
    fun `executeIconColor 的默认值等于 iconTint 不会变成随机色`() {
        // ⚠️ 这条锁的是「构造点漏传」这个静默失效：本类有多个构造点
        //    （`resolveCardColors`、测试、将来的预览），任何一个漏传新字段
        //    都会拿到默认值。默认值若是一个新算的颜色，漏传 = 颜色悄悄变掉。
        val colors = WorkflowVisuals.CardColors(
            cardBackground = 0xFF101010.toInt(),
            iconBackground = 0xFF202020.toInt(),
            iconTint = 0xFFFFFFFF.toInt(),
            accentBackground = 0xFF303030.toInt(),
            chipBackground = 0xFF404040.toInt(),
        )
        assertEquals(
            "executeIconColor 的默认值必须是 iconTint —— 语义是「没传就保持改动前的样子」",
            colors.iconTint,
            colors.executeIconColor,
        )
    }

    @Test
    fun `EXECUTE_ICON_ALPHA 的 KDoc 记录了两个防误用点`() {
        // ⚠️ 这条看似「在测注释」，但这两句话正是最容易踩错的地方：
        //    ① 采样自深色主题（浅色主题下同公式会偏淡，那是「半透明」的固有语义）；
        //    ② 必须叠在本 App 自己的卡片底色上（本 App 是 0.18，ShortX 是 0.075，
        //       直接拿 ShortX 的实色号来用会在本 App 里偏亮）。
        //    删掉它们之后，下一个看这份代码的人会以为 0.40 是通用常数。
        val source = SourceScan.stripped(WORKFLOW_VISUALS)
        val kdoc = source.indexOf("EXECUTE_ICON_ALPHA")
        assertTrue("找不到 EXECUTE_ICON_ALPHA 的定义", kdoc >= 0)
        // 注释已被剥掉，改在**原始源码**里找这两句。
        val raw = SourceScan.file(WORKFLOW_VISUALS).readText()
        assertTrue(
            "EXECUTE_ICON_ALPHA 的 KDoc 必须写明「采样自深色主题」，" +
                "否则会被当成主题无关的常数",
            raw.contains("实测取自**深色主题**"),
        )
        assertTrue(
            "EXECUTE_ICON_ALPHA 的 KDoc 必须写明「必须叠在本 App 自己的卡片底色上」，" +
                "否则会被误当成可直接使用的实色",
            raw.contains("叠在本 App 自己的卡片底色上"),
        )
    }

    // ------------------------------------------------------------------
    // 二、接线：两个调用点都必须真的用上它
    // ------------------------------------------------------------------

    @Test
    fun `两张卡片的执行按钮都用 executeIconColor 取色`() {
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "两个执行按钮（列表模式 WorkflowCard + 瀑布流 WorkflowCompactCard）" +
                "都必须走 executeIconColor()；少一个 = 另一种视图的图标颜色悄悄不同",
            2,
            SourceScan.countOccurrences(source, "tint = executeIconColor("),
        )
    }

    @Test
    fun `执行按钮的 tint 不再取 iconTint 而是走 executeIconColor`() {
        // ⚠️ 反向锁，但**不能用「全文件不含 iconTint」**做判据 ——
        //    左上角的图标徽章用的就是它（那是白/近黑前景色，叠在 iconBackground
        //    的高饱和方块上，**是对的、不该动**）。第一版这么写过，当场变红。
        //    ⇒ 判据改成「**跟着 ▶/⏸ 图标名**的那段窗口」：
        //    改动前那里是 `tint = Color(visualColors.iconTint)`（纯白），
        //    只删底色方块而不改色，三角会比原来的色块更抢眼，与目的相反。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        for (icon in EXECUTE_ICONS) {
            var idx = source.indexOf("R.drawable.$icon")
            var found = 0
            while (idx >= 0) {
                val window = source.substring(idx, minOf(source.length, idx + 600))
                assertTrue(
                    "$icon 之后 600 字符内应出现 executeIconColor( —— 说明这里真的是执行按钮",
                    window.contains("executeIconColor("),
                )
                assertFalse(
                    "$icon 之后 600 字符内不得再出现 Color(visualColors.iconTint) —— " +
                        "那是「叠在强调色块上」的白色前景色，色块已去掉，用它会让三角过亮",
                    window.contains("Color(visualColors.iconTint)"),
                )
                found++
                idx = source.indexOf("R.drawable.$icon", idx + 1)
            }
            assertEquals("$icon 应恰有 2 个执行按钮调用点（两种卡片各一）", 2, found)
        }
    }

    // ------------------------------------------------------------------
    // 三、形状与尺寸（用户 2026-10-06 反馈：偏小、且 ShortX 不是实心三角）
    // ------------------------------------------------------------------

    @Test
    fun `执行按钮用线框三角而非实心三角`() {
        // ⚠️ **反向锁**：填充版 `*_fill_24` 是实心三角，ShortX 的是空心
        //    （逐像素 ASCII 图上左侧竖边 5~6px、中间整块是卡片底色）。
        //    这条锁的存在理由是：改回填充版**不会有任何行为测试变红** ——
        //    卡片照常渲染，只是三角变成实心的。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        for (filled in listOf("rounded_play_arrow_fill_24", "rounded_pause_fill_24")) {
            assertFalse(
                "执行按钮不该用实心的 $filled —— 那是填充版三角，ShortX 用的是线框版。" +
                    "（左侧「图标徽章」不受影响，它走的是 workflow.cardIconRes）",
                source.contains("R.drawable.$filled"),
            )
        }
        for (line in EXECUTE_ICONS) {
            assertEquals(
                "线框版 $line 应恰有 2 个调用点（两种卡片各一）",
                2,
                SourceScan.countOccurrences(source, "R.drawable.$line"),
            )
        }
    }

    @Test
    fun `执行按钮的图标尺寸锚定 32dp 且不挂在 iconInner 上`() {
        // 32 的来历见 `WorkflowVisuals.EXECUTE_ICON_SIZE_DP` 的 KDoc
        //（ShortX 字形 27×34px、同屏 px/dp≈1.97、play_arrow 字形只占盒高 51.5%）。
        assertEquals(32f, WorkflowVisuals.EXECUTE_ICON_SIZE_DP, 0.001f)
        assertTrue(
            "原先是 20~24dp（字形只有 10~12dp 高），验收入口提过「偏小」；" +
                "指数小于 28 说明又被改回去了",
            WorkflowVisuals.EXECUTE_ICON_SIZE_DP >= 28f,
        )

        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "两个执行按钮的尺寸都必须取 EXECUTE_ICON_SIZE_DP",
            2,
            SourceScan.countOccurrences(source, "Modifier.size(WorkflowVisuals.EXECUTE_ICON_SIZE_DP.dp)"),
        )
        assertFalse(
            "执行按钮的尺寸不得再挂在 `iconInner` 上 —— 那是左上角**图标徽章**的尺寸" +
                "（18~20dp），把两者绑死之后改任一处都会连带改另一处",
            source.contains("Modifier.size(iconInner + 2.dp)"),
        )
    }

    @Test
    fun `尺寸常量的 KDoc 记录了「空心」与「32dp」两半依据`() {
        // ⚠️ 这两句是防止后人「只改一半」的关键：只把图标换成线框而不放大 ⇒ 仍然偏小；
        //    只放大而不换线框 ⇒ 是一个大号实心三角，比 ShortX 更抢眼。
        val raw = SourceScan.file(WORKFLOW_VISUALS).readText()
        assertTrue(
            "EXECUTE_ICON_SIZE_DP 的 KDoc 必须写明图标是「空心」的",
            raw.contains("图标是「空心」的"),
        )
        assertTrue(
            "EXECUTE_ICON_SIZE_DP 的 KDoc 必须写明 32dp 是由字形高度反推的",
            raw.contains("等效图标盒 ≈ 33.6dp"),
        )
    }

    @Test
    fun `反向锁不是空转 —— 图标徽章仍在使用 iconTint`() {
        // 上一条若因「源码里根本没有 iconTint」而通过，那它什么都没锁住。
        // 徽章那两处必须还在（它们在 WorkspaceCard / WorkflowCompactCard 的左上角）。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertTrue(
            "源码里应仍存在 Color(visualColors.iconTint)（左上角图标徽章的前景色），" +
                "若一个都没有，说明上一条的反向锁在空转",
            source.contains("Color(visualColors.iconTint)"),
        )
    }

    @Test
    fun `两张卡片的执行按钮都不再有底色方块`() {
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        val body = SourceScan.functionBody(source, "private fun executeIconColor(")
        assertNotNull("executeIconColor 必须存在（本测试与上面的接线断言都依赖它）", body)

        // 判据：执行按钮的函数体里不得再出现 `color = ...accentBackground`。
        // ⚠️ 不能全局断言「源码里没有 accentBackground」—— 开关（Switch）的
        //    选中态仍然用它（`workflowSwitchColors`），那是另一处、且是对的。
        // ⚠️ `WorkflowCard` 声明时没有 `private` 前缀（它是 `fun WorkflowCard(`），
        //    写 `private fun WorkflowCard(` 会匹配不到 ⇒ 注释说的「防空转」
        //    在这里是必需的，否则下面那条 assertNotNull 才是唯一能发现问题的地方。
        val listCard = SourceScan.functionBody(source, "fun WorkflowCard(")
        val compactCard = SourceScan.functionBody(source, "private fun WorkflowCompactCard(")
        assertNotNull("WorkflowCard 必须存在（列表模式卡片）", listCard)
        assertNotNull("WorkflowCompactCard 必须存在（瀑布流卡片）", compactCard)

        for ((name, cardBody) in listOf("WorkflowCard" to listCard!!, "WorkflowCompactCard" to compactCard!!)) {
            assertFalse(
                "$name 的执行按钮不应再有底色方块（含 accentBackground 的 Surface/Box 底）",
                cardBody.contains("accentBackground"),
            )
        }
    }

    @Test
    fun `接线扫描不是空转`() {
        // 防空转：如果 SourceScan 的路径写错/文件被改名，上面几条都会「永远绿」。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertTrue("源码扫描拿到了空内容，接线断言全部在空转", source.length > 20_000)
        assertTrue(
            "WorkflowCompactCard 应仍在同一文件里",
            source.contains("private fun WorkflowCompactCard("),
        )
    }

    private companion object {
        const val WORKFLOW_VISUALS =
            "src/main/java/com/chaomixian/vflow/core/workflow/WorkflowVisuals.kt"
        const val WORKFLOW_LIST_SCREEN =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"

        /** 执行按钮的 ▶ / ⏸ —— **线框**版（不带 `_fill_`），与 ShortX 一致。 */
        val EXECUTE_ICONS = listOf("rounded_play_arrow_24", "rounded_pause_24")
    }
}
