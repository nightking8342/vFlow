package com.chaomixian.vflow.ui.common.glass

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **卡片图标徽章的液态玻璃材质**（2026-10-07）。
 *
 * ## ⚠️ 为什么这些断言必须存在
 *
 * 本条改动是「照 kyant 的 `LiquidButton` 抄材质」，四个失败模式**全都是静默的**：
 *
 * 1. **退回实色 `Surface`** —— 徽章照常渲染，只是不透明了，**没有任何行为测试会红**；
 * 2. **手画高光冒充玻璃** —— 本仓库已经踩过一次（液态玻璃开关第一版，
 *    用户验收原话「完全没有液态玻璃的效果」）；描边与渐变只是库自带着色器的
 *    **粗糙模仿**，去掉 `drawBackdrop` 链路后肉眼要真机对比才看得出来；
 * 3. **直接采样页面层导致自采样** —— 表现是「越看越脏 / 颜色逐帧叠加」，
 *    且只在真机上随帧累积，单测完全看不见（故这条锁在**结构**上：
 *    `GlassBadge` **不接受外部 `Backdrop`**）；
 * 4. **`remember` 缓存玻璃开关** —— 用户关掉液态玻璃后**当场不生效**，
 *    而开关那边已经为这条 bug 修过一轮（见 `GlassSwitchTest`）。
 *
 * ⇒ 前三条只能源码扫描（它们是「接线是否真的存在」，不是「值对不对」），
 * 第四条同理（`remember` 是写法，不是可观测行为）。
 */
class GlassBadgeTest {

    // ------------------------------------------------------------------
    // 一、材质：必须走库的折射链路，不能手画
    // ------------------------------------------------------------------

    @Test
    fun `玻璃材质走 drawBackdrop 链路而不是手画高光`() {
        val source = SourceScan.stripped(GLASS_BADGE)
        // 与 `GlassSwitchTest` 里那条同源：这几个符号就是「真玻璃 vs 仿制品」的分界。
        // ⚠️ 清单里**刻意没有 `lens(`** —— 见下面那条独立断言。
        for (required in listOf(
            "drawBackdrop(",
            "Highlight.Ambient",
            "InnerShadow(",
            "rememberCanvasBackdrop",
        )) {
            assertTrue(
                "GlassBadge 里必须出现 `$required` —— 缺了它就不是真玻璃，" +
                    "而是手画的仿制品（液态玻璃开关第一版就是这么被否掉的）",
                source.contains(required),
            )
        }
    }

    @Test
    fun `花边形状下不得使用 lens —— 库只支持圆角矩形，越界即崩`() {
        // ⚠️⚠️ **这条锁的是一次真机崩溃**：
        //     java.lang.UnsupportedOperationException:
        //       Only RoundedRectangularShape or CornerBasedShape is supported
        //       in lens effects.
        //     徽章的 `ScallopedBadgeShape` 产出 `Outline.Generic`（Path），
        //     而 `lens` 要按圆角矩形算位移场 ⇒ 绘制阶段直接抛。
        //     ⚠️ 崩溃点在 `drawBackdrop` 节点 onAttach / 绘制期，**栈里看不到本文件**，
        //     只看栈很容易怀疑到渲染管线的别处。
        //
        //     ⚠️ 两者的关系是**互斥**的，不是参数问题：
        //     用户 2026-10-07 选了「保留曲奇花边」，那就不能有 lens。
        //     将来若换成正圆（`RoundedCornerShape(percent = 50)`，属于
        //     `CornerBasedShape`）才可以加回来 —— 而那时这条断言也该随之改写，
        //     而不是被删掉。
        val source = SourceScan.stripped(GLASS_BADGE)
        assertFalse(
            "ScallopedBadgeShape 是 Outline.Generic，`lens` 对它抛 " +
                "UnsupportedOperationException（真机崩过一次）；" +
                "要用 lens 必须先换成正圆",
            source.contains("lens("),
        )
        // 另一半：形状确实还是曲奇花边（否则上面那条会变成「因为换了形状所以禁 lens」，
        // 而它想防的是「花边 + lens 同时存在」这个组合）。
        assertTrue(
            "两个调用点都必须仍用曲奇花边 —— 这条与上面那条是同一个互斥关系的两半",
            2 == SourceScan.countOccurrences(
                SourceScan.stripped(WORKFLOW_LIST_SCREEN),
                "remember { ScallopedBadgeShape() }",
            ),
        )
    }

    @Test
    fun `染色画在 onDrawSurface —— 玻璃之上、图标之下`() {
        // ⚠️ 层序是契约：`drawBackdrop` 节点的内部顺序是
        //    backdrop → onDrawSurface → drawContent。
        //    染色若写进 `backdrop` 那层，**图标会被一起染上色**（而且不报错，
        //    只是所有卡片图标都蒙一层主题色）。
        val source = SourceScan.stripped(GLASS_BADGE)
        val body = SourceScan.functionBody(source, "internal fun GlassBadge(")
            ?: error("找不到 GlassBadge —— 这条断言会失去依据")
        val surface = body.substringAfter("onDrawSurface = {", "")
            .substringBefore("\n            },")
        assertTrue(
            "染色必须出现在 `onDrawSurface` 块内（`drawRect(tint.copy(alpha = …))`）",
            surface.contains("tint.copy(alpha = TINT_ALPHA)"),
        )
    }

    @Test
    fun `徽章背后的采样源由调用方给，不接受外部 Backdrop`() {
        // ⚠️⚠️ **这条是防自采样**。徽章是卡片内部的叶子节点，而
        //    `MainComposeShell` 的 `layerBackdrop` 挂在整个 pager 上 ——
        //    徽章在那层**里面**，直接采样它就是采样到自己，每帧叠一层。
        //    （`WorkflowFolderGlassTabBar` 的类注释记过同一个坑。）
        //    ⇒ 参数表里**只能有 `backdropBrush: Brush`**，不能有 `backdrop: Backdrop`。
        val source = SourceScan.stripped(GLASS_BADGE)
        val body = SourceScan.functionBody(source, "internal fun GlassBadge(")
            ?: error("找不到 GlassBadge")
        assertFalse(
            "GlassBadge 不得接受 `backdrop: Backdrop` 参数 —— 徽章在页面层**内部**，" +
                "采样它就是采样自己（每帧叠一层，且不报错）。应当由调用方给 `backdropBrush`",
            body.contains("backdrop: Backdrop"),
        )
        assertTrue(
            "采样源必须是自造的 `rememberCanvasBackdrop { drawRect(backdropBrush) }`",
            body.contains("rememberCanvasBackdrop { drawRect(backdropBrush) }"),
        )
    }

    // ------------------------------------------------------------------
    // 二、开关：液态玻璃关掉必须退回实色（且每次组合都读）
    // ------------------------------------------------------------------

    @Test
    fun `液态玻璃开关必须每次组合都读，不能 remember 缓存`() {
        val source = SourceScan.stripped(GLASS_BADGE)
        val body = SourceScan.functionBody(source, "internal fun GlassBadge(")
            ?: error("找不到 GlassBadge")
        assertTrue(
            "必须直接调 `AppearanceManager.isLiquidGlassNavBarEnabled(context)`",
            body.contains("AppearanceManager.isLiquidGlassNavBarEnabled(context)"),
        )
        assertFalse(
            "不得对它做 `remember` 缓存 —— 那会让「关掉液态玻璃」当场不生效" +
                "（智能开关那边的原话反馈就是这条）",
            body.contains("remember(context)"),
        )
    }

    @Test
    fun `玻璃关掉时有实色回退分支`() {
        val source = SourceScan.stripped(GLASS_BADGE)
        val body = SourceScan.functionBody(source, "internal fun GlassBadge(")
            ?: error("找不到 GlassBadge")
        assertTrue(
            "必须有 `if (!glassEnabled)` 的回退分支，且回退用 `background(fallbackColor, shape)`",
            body.contains("if (!glassEnabled)") &&
                body.contains("background(fallbackColor, shape)"),
        )
    }

    // ------------------------------------------------------------------
    // 三、接线：两张卡片的徽章都要换成它
    // ------------------------------------------------------------------

    @Test
    fun `两张卡片的图标徽章都换成了玻璃徽章`() {
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "列表模式与瀑布流两个徽章都必须走 GlassBadge；少一个 = 另一种视图还是实色底",
            2,
            SourceScan.countOccurrences(source, "GlassBadge("),
        )
        assertFalse(
            "徽章不应再是 `Surface(... color = … iconBackground …)` 的实色写法",
            source.contains("color = Color(visualColors.iconBackground)"),
        )
        // 形状没换：用户 2026-10-07 明确「保留曲奇花边，只换材质」。
        assertEquals(
            "形状必须仍是曲奇花边（只换材质，不换外形）",
            2,
            SourceScan.countOccurrences(source, "remember { ScallopedBadgeShape() }"),
        )
    }

    @Test
    fun `卡片渐变只有一处出处，徽章与卡片共用`() {
        // ⚠️ 徽章要透出卡片底色，两边**必须**用同一个 brush。
        //    各拼一份 `Brush.verticalGradient` 的话，改了其中一处就会出现
        //    「徽章透出的颜色与卡片对不上」—— 静默，只是看着有点脏。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "`Brush.verticalGradient` 只应出现在 `WorkflowVisuals.cardBackgroundBrush` 一处，" +
                "卡片与徽章共用它",
            0,
            SourceScan.countOccurrences(source, "Brush.verticalGradient"),
        )
        assertTrue(
            "卡片必须走 `WorkflowVisuals.cardBackgroundBrush(...)`",
            source.contains("WorkflowVisuals.cardBackgroundBrush("),
        )
    }

    @Test
    fun `玻璃徽章的图标对比色与实色底是两个字段`() {
        // ⚠️⚠️ 实色底亮度是 `blend(surface, base, 0.82)`，而玻璃底是
        //    「玻璃 + 68% 染色」，整体向 surface 靠一档 ⇒ **对比色要重算**。
        //    直接复用 `iconTint` 会让浅色主题色（黄 / 青）上的图标对比不足，
        //    而它**不报错、不崩溃**，只是看不清。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertTrue(
            "两个徽章都必须用 `badgeIconTint` 而不是 `iconTint`",
            2 == SourceScan.countOccurrences(source, "Color(visualColors.badgeIconTint)"),
        )
        assertTrue(
            "徽章底色必须用 `badgeGlassTint`",
            2 == SourceScan.countOccurrences(source, "Color(visualColors.badgeGlassTint)"),
        )
    }

    @Test
    fun `源码扫描不是空转`() {
        val screen = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        val badge = SourceScan.stripped(GLASS_BADGE)
        assertTrue("屏幕源码应扫到内容", screen.length > 20_000)
        assertTrue("徽章源码应扫到内容", badge.length > 1_000)
        // ⚠️ 这条**必须选代码里存在的符号**，不能选只出现在 KDoc 里的名字 ——
        //    `SourceScan.stripped` 会把注释剥掉，拿 `LiquidButton`（只在文档里提过）
        //    当判据会**恒红**，而恒红的断言会被下一个实现者直接删掉。
        assertTrue("剥注释后应仍能看到实现体", badge.contains("fun GlassBadge("))
    }

    private companion object {
        /** ⚠️ 路径相对 `app/`（Gradle 默认测试工作目录），见 `SourceScan`。 */
        const val WORKFLOW_LIST_SCREEN =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
        const val GLASS_BADGE =
            "src/main/java/com/chaomixian/vflow/ui/common/glass/GlassBadge.kt"
    }
}
