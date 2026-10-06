package com.chaomixian.vflow.ui.common.glass

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow

/**
 * **液态玻璃徽章** —— 工作流卡片左上角那个图标底座（fork 新增，2026-10-07）。
 *
 * 外观照 kyant `AndroidLiquidGlass` 的 `LiquidButton`（48dp 玻璃胶囊按钮），
 * **形状仍用本仓库实测出来的曲奇花边** [com.chaomixian.vflow.ui.workflow_list.ScallopedBadgeShape]
 * ——用户 2026-10-07 明确「保留曲奇花边，只换材质」。
 *
 * ## 与 `LiquidButton` 的三处不同（都是「它那么写、我们不行」）
 *
 * 1. **不用 `lens` 的按下联动**：组件是**静态**的（用户拍板）。卡片本身已有
 *    按下反馈（缩放 + `combinedClickable`），再给徽章加一层形变是重复反馈。
 * 2. **`backdrop` 是自造的 `CanvasBackdrop`，不接 `MainComposeShell` 的那张页面层**。
 *    ⚠️⚠️ **这不是偷懒，是必须的**：徽章是卡片**内部**的一个叶子节点，而
 *    `MainComposeShell` 的 `layerBackdrop` 挂在**整个 pager** 上 —— 徽章
 *    在那层里面。直接采样它 = **采样到自己**，每帧叠一层，形成反馈（
 *    `WorkflowFolderGlassTabBar` 的类注释记过同一个坑，它因此退回静态画法）。
 *    故这里按 `backdropBrush` 自画一层：徽章**背后就是卡片自己的底色/渐变**，
 *    画一份等效的进去，语义与「真的采样到卡片」一致，且结构上不可能自采样。
 * 3. **颜色不是写死的 iOS 蓝**：`tint` 由调用方给（工作流主题色），
 *    以 [TINT_ALPHA] 半透明叠在玻璃上 —— 卡片之间仍要靠颜色区分。
 *
 * ## ⚠️⚠️ 不能用 `lens` —— 花边形状会让它当场抛异常（真机崩溃过一次）
 *
 * 真机反馈（小米 2308CPXD0C / Android 17）：
 * ```
 * java.lang.UnsupportedOperationException:
 *   Only RoundedRectangularShape or CornerBasedShape is supported in lens effects.
 * ```
 * `lens` 的折射要按形状算**圆角矩形位移场**，故只接受 `RoundedCornerShape`
 * 这一类；而徽章用的是 `ScallopedBadgeShape`（`Outline.Generic` + `Path`）。
 * ⇒ 它与「保留曲奇花边」**在实现层互斥**，不是参数问题。
 *
 * ⚠️ 这也一并说明 `blur` 的处境：`blur` 没有形状限制，但同样作用在
 * 一层**几乎平色**的 backdrop 上（调用方给的 `backdropBrush`）⇒ 视觉上近空转。
 * ⇒ 真正让这枚徽章「看起来是玻璃」的是下面三样，与库示例的运镜无关：
 *
 * | 起作用的 | 参数 |
 * |---|---|
 * | 顶部**高光**（`Highlight.Ambient`，最像玻璃的一笔）| [HIGHLIGHT_ALPHA] |
 * | 内圈**倒角阴影**（`InnerShadow`）| [INNER_SHADOW_ALPHA] |
 * | 半透明染色**透出卡片底色** | [TINT_ALPHA] |
 * | 外投影（把它从卡面上「抬起来」）| [SHADOW_ALPHA] |
 *
 * ⚠️ **若将来把花边换成正圆**，可以再把 `lens` 加回来（正圆在
 * `RoundedCornerShape(percent = 50)` 下属于 `CornerBasedShape`，是合法的）——
 * 但**必须先确认形状确实换了**，否则就是这次的崩溃重演。
 * 有测试把这条关系钉住（花边 ⇒ 不得出现 `lens(`）。
 *
 * ## ⚠️ 液态玻璃开关不生效时退回实色
 *
 * 判据与 [VFlowSwitch] **完全一致**（同一个 `AppearanceManager` 开关），
 * 且**同样不做 `remember`** —— `remember(context)` 只在首次组合读一次，
 * 用户关掉开关后当场不生效（那只 bug 在开关上踩过）。
 */
@Composable
internal fun GlassBadge(
    /** 工作流主题色的来源 —— 会以 [TINT_ALPHA] 半透明叠在玻璃上。 */
    tint: Color,
    /** 徽章**背后**是什么（卡片底色 / 卡片渐变）。玻璃采样它，见类 KDoc 第 2 点。 */
    backdropBrush: Brush,
    /** 液态玻璃关掉时的底色（实色回退）。 */
    fallbackColor: Color,
    shape: Shape,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val context = LocalContext.current
    // ⚠️ 不 remember —— 见类 KDoc 末节。
    val glassEnabled = AppearanceManager.isLiquidGlassNavBarEnabled(context)

    if (!glassEnabled) {
        Box(
            modifier = modifier.background(fallbackColor, shape),
            contentAlignment = Alignment.Center,
            content = content,
        )
        return
    }

    // ⚠️ `rememberCanvasBackdrop` 把 `onDraw` **当作 remember key**
    //    （`javap` 核过：`Composer.changed(onDraw)`）⇒ 主题/卡片底色变了会重建，
    //    不会拿旧颜色一直画。
    val backdrop = rememberCanvasBackdrop { drawRect(backdropBrush) }

    Box(
        modifier = modifier.drawBackdrop(
            backdrop = backdrop,
            shape = { shape },
            effects = {
                // ⚠️⚠️ **这里绝不能加 `lens(...)`** —— 它只支持 `CornerBasedShape`
                //    （`RoundedRectangularShape`），而徽章是 `ScallopedBadgeShape`
                //    （`Outline.Generic`）⇒ 当场抛
                //    `UnsupportedOperationException: Only RoundedRectangularShape or
                //    CornerBasedShape is supported in lens effects.`（真机崩过一次）。
                //    ⚠️ **崩溃点是绘制阶段**（`drawBackdrop` 节点 onAttach），
                //    栈里看不到本文件 ⇒ 只看栈很容易以为是渲染管线的别处。
                //    `blur` 没有形状限制，可以留。
                blur(BLUR_DP.dp.toPx())
            },
            highlight = {
                // ⚠️ `Highlight.Ambient` 是「上方来的环境光」；这里把它的宽度与
                //    模糊半径**各收一档** —— 库的默认值是按 48dp 按钮调的，
                //    套在 32~40dp 的徽章上会让高光糊满整个面、看不出是「一条边」。
                Highlight.Ambient.copy(
                    width = Highlight.Ambient.width / 1.6f,
                    blurRadius = Highlight.Ambient.blurRadius / 1.6f,
                    alpha = HIGHLIGHT_ALPHA,
                )
            },
            shadow = {
                Shadow(
                    radius = SHADOW_DP.dp,
                    color = Color.Black.copy(alpha = SHADOW_ALPHA),
                )
            },
            innerShadow = {
                InnerShadow(radius = INNER_SHADOW_DP.dp, alpha = INNER_SHADOW_ALPHA)
            },
            // ⚠️ 染色走 `onDrawSurface`（节点内部顺序是
            //    backdrop → onDrawSurface → drawContent）⇒ 染色**盖在玻璃上、
            //    压在图标的下面**。这正是想要的层序：
            //    「玻璃 | 半透明主题色 | 图标」。
            //    若改画进 `backdrop` 那层，图标会被一起染上色。
            onDrawSurface = { drawRect(tint.copy(alpha = TINT_ALPHA)) },
        ),
        contentAlignment = Alignment.Center,
        content = content,
    )
}

/** 半透明染色强度：再高就退回实色块（看不出玻璃），再低则卡片之间分不出颜色。 */
private const val TINT_ALPHA = 0.68f

/** 顶部高光强度。 */
private const val HIGHLIGHT_ALPHA = 0.70f

/** 内圈倒角阴影强度 —— 玻璃的「厚度」感来自它。 */
private const val INNER_SHADOW_ALPHA = 0.30f

/** 外投影强度（把徽章从卡面上抬起来）。深色卡片上要收着点。 */
private const val SHADOW_ALPHA = 0.16f

private const val SHADOW_DP = 5f
private const val INNER_SHADOW_DP = 4f
private const val BLUR_DP = 6f

/** 徽章**非彩色模式**下的形状（圆形，与曲奇花边在 32~40dp 上视觉同族）。 */
internal val GLASS_BADGE_FALLBACK_SHAPE: Shape = RoundedCornerShape(percent = 50)
