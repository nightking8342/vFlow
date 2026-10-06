package com.chaomixian.vflow.ui.common.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.ui.common.AppearanceManager

/**
 * 开关的**尺寸规格** —— ⚠️ **数值原样取自 Material 3 的 `SwitchTokens`**。
 *
 * 这一条是本组件「保留 M3 形态」承诺的落实方式：玻璃化**只换材质、不换形态**，
 * 所以每一个尺寸都必须与 M3 逐值对齐，而不是「看着差不多」。
 *
 * ⚠️ 数值是从 `material3` 的字节码里读出来的（`javap` 解 `SwitchTokens` 的
 * 静态初始化块），不是照文档抄的 —— 文档写的是 dp 整数、而 token 里存的是
 * `Dp` inline value，两者的换算偶有出入。核对方式见 `GlassSwitchTokensTest`。
 */
internal object GlassSwitchTokens {
    /** `SwitchTokens.TrackWidth`。 */
    val TrackWidth = 52.dp

    /** `SwitchTokens.TrackHeight`。 */
    val TrackHeight = 32.dp

    /** `SwitchTokens.SelectedHandleWidth`。 */
    val ThumbDiameter = 24.dp

    /** `SwitchTokens.UnselectedHandleWidth`。 */
    val UncheckedThumbDiameter = 16.dp

    /** `SwitchTokens.TrackHeight − SelectedHandleWidth) / 2`。 */
    val ThumbPadding = 4.dp

    /**
     * 按下时滑块的**额外宽度**（只在宽的方向扩，高度不变）。
     *
     * ⚠️ 与 M3 的 `PressedHandleWidth = 28dp` 不是同一个概念：M3 按下时
     * 滑块会朝**手势推进的方向**撑开（左右不对称），那需要接手势的拖动量；
     * 这里取「宽度 +4dp、按选中态锚定一侧」的简化版 —— 观感一致（都表现为
     * 「按下去滑块胖了一点」），但不需要跟踪手势。
     */
    val PressGrowth = 4.dp

    /** 轨道的玻璃通透度：底色按这个 alpha 绘制，让卡片/页面底色透出来。 */
    const val TrackGlassAlpha = 0.62f

    /** 禁用态轨道的 alpha（与 M3 的 `DisabledTrackOpacity = 0.12f` 同量级）。 */
    const val DisabledTrackAlpha = 0.16f
}

/** 滑块在**未按下**时的基准宽度（选中 24dp / 未选中 16dp）。 */
internal fun baseThumbWidthDp(checked: Boolean): Dp =
    if (checked) GlassSwitchTokens.ThumbDiameter else GlassSwitchTokens.UncheckedThumbDiameter

/** 滑块当前宽度：按下时在基准宽度上 +4dp。 */
internal fun glassThumbWidthDp(checked: Boolean, pressed: Boolean): Dp =
    baseThumbWidthDp(checked) + if (pressed) GlassSwitchTokens.PressGrowth else 0.dp

/**
 * 滑块左边缘的 x（相对轨道）。
 *
 * ⚠️ **两侧的锚定方式刻意不同**，因为「按下变宽」往哪边扩是会影响观感的：
 * - **选中**：**右锚定**（滑块的右边紧贴轨道右侧内边距不动，向左长）——
 *   否则按下去滑块会顶出右边缘；
 * - **未选中**：**中心锚定**（左右各长一半）—— 否则会顶出左边缘。
 */
internal fun glassThumbXDp(checked: Boolean, pressed: Boolean): Dp {
    val base = baseThumbWidthDp(checked)
    val width = glassThumbWidthDp(checked, pressed)
    return if (checked) {
        GlassSwitchTokens.TrackWidth - GlassSwitchTokens.ThumbPadding - width
    } else {
        GlassSwitchTokens.ThumbPadding - (width - base) / 2
    }
}

/**
 * **玻璃质感**的开关（参考 kyant 的 `LiquidToggle`，形态仍照 M3）。
 *
 * ## 它解决的是什么
 *
 * 液态玻璃开关打开后，全 App 只有底部导航栏与文件夹 Tab 栏是玻璃的，
 * 而开关（29 处）还是 M3 的实色 —— 一屏之内两种材质并存。
 *
 * ## ⚠️ 为什么不用「给 M3 `Switch` 换 `SwitchColors`」了事
 *
 * M3 的 `Switch` 只暴露 `SwitchColors`（16 个颜色槽），**没有任何绘制钩子**。
 * 玻璃的关键特征是「**边缘高光**」与「**透视**」——前者要画描边、后者要降透明度，
 * 单靠颜色槽做不出来（`checkedThumbColor` 只能把圆点整体染一个色）。
 * 故本组件自己画，但**尺寸与交互全部照抄 M3**（见 [GlassSwitchTokens]）。
 *
 * ## ⚠️ 两道质感处理，都只动「材质」不动「颜色语义」
 *
 * 1. **轨道**：用调用方给的颜色，但**降到 [GlassSwitchTokens.TrackGlassAlpha]
 *    的透明度**，让卡片底色透出来（这是「玻璃」二字的定义），再叠一层
 *    **垂直渐变描边**当边缘高光、一条底部暗边当内阴影。
 * 2. **滑块**：白色玻璃（半透明白 + 细描边 + 外阴影）——
 *    ⚠️ **这一处刻意不用 `colors.checkedThumbColor`**：玻璃材质的滑块天然是
 *    「白色磨砂块」，而 M3 的滑块是「深色圆点」，这是**材质差异而非配色差异**。
 *    轨道才是「这个开关代表什么」的语义色载体，滑块不是。
 *
 * ## ⚠️ 涟漪被刻意关掉（`indication = null`）
 *
 * 涟漪是「实色平面」的反馈语言；玻璃控件的反馈是**形变 + 高光增强**
 * （按下时滑块变宽、描边变亮）。两者叠在一起会互相打架。
 * 按钮的**可点性没有因此降低**：`toggleable` + `Role.Switch` 保留了全部
 * 无障碍语义与点击热区。
 */
@Composable
internal fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
    thumbContent: (@Composable () -> Unit)? = null,
) {
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val isPressed = pressed && enabled
    val isDark = isSystemInDarkTheme()

    val trackColor = when {
        !enabled && checked -> colors.disabledCheckedTrackColor
        !enabled -> colors.disabledUncheckedTrackColor
        checked -> colors.checkedTrackColor
        else -> colors.uncheckedTrackColor
    }
    // ⚠️ 只在**轨道**上做透明化，不去动调用方传进来的颜色值本身 ——
    //    这样「同一个强调色在玻璃态与实色态下是同一个色相」。
    val fillColor = trackColor.copy(
        alpha = if (enabled) GlassSwitchTokens.TrackGlassAlpha else GlassSwitchTokens.DisabledTrackAlpha
    )

    val thumbWidth = glassThumbWidthDp(checked, isPressed)
    val thumbHeight = baseThumbWidthDp(checked)
    val thumbX = glassThumbXDp(checked, isPressed)

    Box(
        modifier = modifier
            .size(GlassSwitchTokens.TrackWidth, GlassSwitchTokens.TrackHeight)
            // ⚠️ `toggleable` 的 `onValueChange` 是**非空**的，而 M3 `Switch` 的
            //    `onCheckedChange` 可空（传 null = 「只显示、不可交互」，M3 用它
            //    表示「这一项由别处控制」）。调用点有传 null 的，故这里让手势
            //    层**可选**：null 时连 `toggleable` 都不加，但**外观仍是完整开关**
            //    （不是禁用态 —— 禁用态是 `enabled = false` 的灰化，两件事不同）。
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        onValueChange = onCheckedChange,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = source,
                        // 见 KDoc「涟漪被刻意关掉」。
                        indication = null,
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ---- 轨道 ----
        Box(
            Modifier
                .matchParentSize()
                .drawWithCache {
                    val radius = CornerRadius(size.height / 2f)
                    val strokePx = 1.dp.toPx()
                    val highlight = Brush.verticalGradient(
                        colors = listOf(
                            Color.White.copy(alpha = if (isDark) 0.38f else 0.60f),
                            Color.White.copy(alpha = if (isDark) 0.05f else 0.15f),
                        )
                    )
                    val innerShadow = Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = if (isDark) 0.20f else 0.10f),
                        )
                    )
                    onDrawBehind {
                        drawRoundRect(color = fillColor, cornerRadius = radius)
                        // 内阴影：底部压暗，制造「有厚度」的观感
                        drawRoundRect(brush = innerShadow, cornerRadius = radius)
                        // 边缘高光：玻璃最可辨识的特征
                        drawRoundRect(
                            brush = highlight,
                            cornerRadius = radius,
                            style = Stroke(width = strokePx),
                        )
                    }
                }
        )

        // ---- 滑块 ----
        Box(
            Modifier
                .offset(x = thumbX)
                .size(thumbWidth, thumbHeight)
                .shadow(
                    elevation = if (isPressed) 4.dp else 2.dp,
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(percent = 50),
                    clip = false,
                )
                .drawWithCache {
                    val radius = CornerRadius(size.height / 2f)
                    val strokePx = 1.dp.toPx()
                    onDrawBehind {
                        if (!enabled) {
                            drawRoundRect(
                                color = Color.White.copy(alpha = 0.38f),
                                cornerRadius = radius,
                            )
                            return@onDrawBehind
                        }
                        // 白色磨砂玻璃：主体半透明白 + 顶部高光 + 一圈极淡描边
                        drawRoundRect(
                            color = Color.White.copy(alpha = if (isPressed) 0.98f else 0.92f),
                            cornerRadius = radius,
                        )
                        drawRoundRect(
                            brush = Brush.verticalGradient(
                                colors = listOf(
                                    Color.White,
                                    Color.White.copy(alpha = 0.55f),
                                )
                            ),
                            cornerRadius = radius,
                        )
                        drawRoundRect(
                            color = if (isDark) {
                                Color.White.copy(alpha = 0.55f)
                            } else {
                                Color.Black.copy(alpha = 0.06f)
                            },
                            cornerRadius = radius,
                            style = Stroke(width = strokePx),
                        )
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            if (thumbContent != null && checked) {
                thumbContent()
            }
        }
    }
}

/**
 * **全 App 统一的开关**：按液态玻璃开关决定走 [GlassSwitch] 还是 M3 `Switch`。
 *
 * ⚠️⚠️ **参数与 M3 `Switch` 逐一对应（含顺序）** —— 调用点只需把 `Switch(`
 * 换成 `VFlowSwitch(`，**一个参数都不用加**。玻璃态需要的强调色直接从同一份
 * [colors] 里读（`checkedTrackColor`），所以「同一个开关在两种材质下的色相一致」。
 *
 * ⚠️ **开关状态是「读一次」的**（`remember`），与既有的玻璃 Tab 栏同一模式
 * （`WorkflowIconPickerActivity` 也是 `onCreate` 里读一次）。用户改设置后
 * 需要重进页面才生效 —— 这是既有约定的延续，不是本组件新引入的限制。
 * 之所以不在这里做成响应式：`AppearanceManager` 只是一个
 * `SharedPreferences` 读取器、**没有变更通知机制**，要做响应式得先给它加
 * `StateFlow`，那是另一件事（且会波及所有既有读取点）。
 */
@Composable
fun VFlowSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    thumbContent: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
) {
    val context = LocalContext.current
    val glassEnabled = remember(context) {
        AppearanceManager.isLiquidGlassNavBarEnabled(context)
    }
    if (glassEnabled) {
        GlassSwitch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
            thumbContent = thumbContent,
        )
    } else {
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = modifier,
            thumbContent = thumbContent,
            enabled = enabled,
            colors = colors,
            interactionSource = interactionSource,
        )
    }
}
