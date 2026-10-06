package com.chaomixian.vflow.ui.common.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.lerp as lerpFloat
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.chaomixian.vflow.ui.main.glass.DampedDragAnimation
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import androidx.compose.foundation.shape.RoundedCornerShape

/**
 * **照抄 AndroidLiquidGlass 官方示例（`LiquidToggle`）的开关**。
 *
 * ## 与 [GlassSwitch]（上一版）的区别 —— 这一版是「尺寸/手感照抄示例」
 *
 * | | [GlassSwitch]（上一版） | 本版 |
 * |---|---|---|
 * | 尺寸 | M3 的 52×32 / 滑块 16-24 | **示例的 64×28 / 滑块 40×24（恒定）** |
 * | 滑块宽度 | 随选中态变（M3 语义） | **恒定**，只有位置在动 |
 * | 按下 | 滑块横向撑开 4dp | **整体放大到 1.5×**（`pressedScale = 1.5f`）|
 * | 拖动 | 自研手势 | **示例用的 `DampedDragAnimation`** |
 * | 轨道色 | M3 主题色（未选中 = 描边） | 半透明灰 → 主题色，`lerp` 过渡 |
 *
 * 用户 2026-10-06 的原话是「完全按照库里的示例开关实现一版」，故这张表就是
 * 「哪些地方刻意与示例一致、哪些地方刻意不一致」的清单 —— 不一致的三处见下。
 *
 * ## ⚠️ 三处刻意与示例不同的地方（都是「示例能那么写、我们不行」）
 *
 * 1. **颜色取自 [colors]，不是示例写死的 iOS 绿。** 示例的
 *    `Color(0xFF34C759)` / `Color(0xFF30D158)` 是 iOS 系统开关的绿；
 *    本 App 的开关承载主题色语义（卡片强调色、设置项的选中态），
 *    全染成绿会与其它强调色打架。⇒ 用 `colors.checkedTrackColor` 当 accent。
 * 2. **`Capsule` 换成 `RoundedCornerShape(percent = 50)`。** 示例 import 的
 *    `com.kyant.shapes.Capsule` 所在的 `shapes` 库**不在编译类路径上**
 *    （它只是 `backdrop` 的运行期依赖，`./gradlew :app:dependencies` 实测）。
 *    在非正方形盒子上 `percent = 50` 渲染出来的就是标准胶囊，肉眼无差。
 * 3. **不用示例的 `rememberCanvasBackdrop`**：示例的 `backdrop` 参数由
 *    demo 的 `BackdropDemoScaffold` 提供（一张壁纸）。本 App 的开关背后是
 *    卡片/列表底色 ⇒ 由调用方通过 `containerColor` 给一层静态色，
 *    否则在设置页里会折射出一片透明的黑。
 *
 * ## 示例的运镜（照抄，一字未改的三处参数）
 *
 * - `dragWidth = 20dp`（= 轨道宽 64 − 滑块宽 40 − 两侧内边距 2×2，**恰好等于
 *   可移动距离**；示例直接写 20，本实现按公式算，两者逐值相同）；
 * - 位置 `translationX = lerp(2dp, 2dp + 20dp, fraction)` —— **左对齐插值**，
 *   滑块宽度不参与（这是「摇杆」而非「M3 滑块」的关键）；
 * - 轨道被采样时 `scaleX = lerp(2/3, 0.75, progress)`、`scaleY = lerp(0, 0.75, progress)`：
 *   **静止 `scaleY = 0`** ⇒ 采样里没有轨道 ⇒ 避开「滑块采样自己盖住的轨道」的自采样回环。
 */
internal object LiquidToggleTokens {
    /** 示例 `size(64f.dp, 28f.dp)`。 */
    val TrackWidth = 64.dp
    val TrackHeight = 28.dp

    /** 示例 `size(40f.dp, 24f.dp)`。 */
    val ThumbWidth = 40.dp
    val ThumbHeight = 24.dp

    /** 示例里的 `padding = 2f.dp`。 */
    val Padding = 2.dp

    /** 示例 `pressedScale = 1.5f`。 */
    const val PressedScale = 1.5f
}

/**
 * 滑块可移动的距离（示例里写死的 `dragWidth = 20f.dp`）。
 *
 * ⚠️ 按公式算而不是抄 20：**64 − 40 − 2×2 = 20** 两者相同，但改任一尺寸后
 * 公式仍自洽 —— 抄常量的版本会静默失去同步（滑块能拖出轨道，而 `Box` 不裁剪）。
 */
internal fun liquidToggleTravelDp(): androidx.compose.ui.unit.Dp =
    LiquidToggleTokens.TrackWidth - LiquidToggleTokens.ThumbWidth -
        LiquidToggleTokens.Padding * 2

/** 滑块左边的 x（示例：`lerp(padding, padding + dragWidth, fraction)`）。 */
internal fun liquidToggleThumbXDp(fraction: Float, isLtr: Boolean): androidx.compose.ui.unit.Dp {
    val travel = liquidToggleTravelDp().value
    val x = lerpFloat(LiquidToggleTokens.Padding.value, LiquidToggleTokens.Padding.value + travel, fraction.fastCoerceIn(0f, 1f))
    // ⚠️ RTL 下向左偏移（示例原作如此）。
    return if (isLtr) x.dp else (-x).dp
}

/**
 * 照抄示例的液态玻璃开关。
 *
 * ⚠️ 与 M3 `Switch` 的签名**不对齐**（示例用的是 `selected: () -> Boolean`，
 * 且没有 `colors`）—— 对外统一由 [VFlowSwitch] 承担，本函数只做玻璃态。
 */
@Composable
internal fun LiquidToggleSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
    /** 开关**背后**是什么颜色（示例里由壁纸提供，这里由调用方给）。 */
    containerColor: Color = Color.Unspecified,
) {
    val isLightTheme = !isSystemInDarkTheme()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val density = LocalDensity.current

    // 示例：accentColor 写死 iOS 绿；本实现取主题的选中轨道色（见类 KDoc 第 1 点）。
    val accentColor = colors.checkedTrackColor
    val trackColor = if (isLightTheme) {
        Color(0xFF787878).copy(alpha = 0.2f)
    } else {
        Color(0xFF787880).copy(alpha = 0.36f)
    }

    val animationScope = rememberCoroutineScope()
    var didDrag by remember { mutableStateOf(false) }
    var fraction by remember { mutableFloatStateOf(if (checked) 1f else 0f) }

    val dampedDragAnimation = remember(animationScope) {
        // ⚠️ 照抄示例的参数：valueRange 0..1、pressedScale 1.5、
        //    即时起拖（`longPressDrag` 默认 false，与示例的即时响应一致）。
        DampedDragAnimation(
            animationScope = animationScope,
            initialValue = fraction,
            valueRange = 0f..1f,
            visibilityThreshold = 0.001f,
            initialScale = 1f,
            pressedScale = LiquidToggleTokens.PressedScale,
            onDragStarted = {},
            onDragStopped = {
                if (didDrag) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    onCheckedChange?.invoke(fraction == 1f)
                    didDrag = false
                } else {
                    // 轻点：取反当前值（与示例一致）
                    fraction = if (checked) 0f else 1f
                    onCheckedChange?.invoke(fraction == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!didDrag) didDrag = dragAmount.x != 0f
                val travelPx = with(density) { liquidToggleTravelDp().toPx() }
                if (travelPx > 0f) {
                    val delta = dragAmount.x / travelPx
                    fraction = if (isLtr) {
                        (fraction + delta).fastCoerceIn(0f, 1f)
                    } else {
                        (fraction - delta).fastCoerceIn(0f, 1f)
                    }
                }
            },
        )
    }

    LaunchedEffect(dampedDragAnimation) {
        snapshotFlow { fraction }.collectLatest { dampedDragAnimation.updateValue(it) }
    }
    LaunchedEffect(checked) {
        snapshotFlow { checked }.collectLatest { isSelected ->
            val target = if (isSelected) 1f else 0f
            if (target != fraction) {
                fraction = target
                dampedDragAnimation.animateToValue(target)
            }
        }
    }

    val trackBackdrop = rememberLayerBackdrop()

    Box(modifier, contentAlignment = Alignment.CenterStart) {
        // ---- 轨道 ----
        Box(
            Modifier
                .layerBackdrop(trackBackdrop)
                .clip(TOGGLE_CAPSULE)
                .drawBehind {
                    // 示例：`drawRect(lerp(trackColor, accentColor, fraction))` —— 整条纯色。
                    drawRect(lerp(trackColor, accentColor, dampedDragAnimation.value.fastCoerceIn(0f, 1f)))
                }
                .size(LiquidToggleTokens.TrackWidth, LiquidToggleTokens.TrackHeight)
                .then(
                    if (onCheckedChange != null) {
                        Modifier.toggleable(
                            value = checked,
                            onValueChange = onCheckedChange,
                            enabled = enabled,
                            role = Role.Switch,
                            interactionSource = interactionSource ?: remember { MutableInteractionSource() },
                            indication = null,
                        )
                    } else {
                        Modifier
                    }
                )
        )

        // ---- 滑块（示例：位置左对齐插值，宽度恒定）----
        Box(
            Modifier
                .graphicsLayer {
                    translationX = with(density) { liquidToggleThumbXDp(dampedDragAnimation.value, isLtr).toPx() }
                }
                .then(dampedDragAnimation.modifier)
                .drawBackdrop(
                    // ⚠️ 采样合成两层：① 开关背后的颜色；② 被压扁的轨道。
                    //    静止时压扁量为 0 ⇒ 采样里没有轨道 ⇒ 避开自采样回环。
                    backdrop = rememberCombinedBackdrop(
                        rememberLayerBackdrop {
                            drawRect(if (containerColor == Color.Unspecified) Color.Transparent else containerColor)
                            drawContent()
                        },
                        // ⚠️ `rememberBackdrop` 的 lambda 有**两个**接收者层次：
                        //    外层是 `DrawScope`、参数是**另一个** `drawBackdrop: DrawScope.() -> Unit`。
                        //    漏写那个参数名的话，`drawBackdrop()` 会解析到本文件 import 的
                        //    **修饰符** `drawBackdrop`（名字撞车），报「receiver type mismatch」
                        //    —— 而错误信息完全指不出真正的原因。
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerpFloat(THUMB_SAMPLE_SCALE_X_MIN, THUMB_SAMPLE_SCALE_X_MAX, progress)
                            val scaleY = lerpFloat(0f, THUMB_SAMPLE_SCALE_Y_MAX, progress)
                            scale(scaleX, scaleY) { drawBackdrop() }
                        },
                    ),
                    shape = { TOGGLE_CAPSULE },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            5f.dp.toPx() * progress,
                            10f.dp.toPx() * progress,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress,
                        )
                    },
                    shadow = {
                        Shadow(radius = 4f.dp, color = Color.Black.copy(alpha = 0.05f))
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(radius = 4f.dp * progress, alpha = progress)
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 50f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        // 示例原句：`drawRect(Color.White.copy(alpha = 1f - progress))`
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(LiquidToggleTokens.ThumbWidth, LiquidToggleTokens.ThumbHeight)
        )
    }
}

/** 胶囊形状（替代示例的 `com.kyant.shapes.Capsule`，见类 KDoc 第 2 点）。 */
private val TOGGLE_CAPSULE = RoundedCornerShape(percent = 50)

/** 滑块采样轨道时的横向压扁范围（示例 `lerp(2f / 3f, 0.75f, progress)`）。 */
private const val THUMB_SAMPLE_SCALE_X_MIN = 2f / 3f
private const val THUMB_SAMPLE_SCALE_X_MAX = 0.75f

/** 滑块采样轨道时的纵向压扁上限（示例 `lerp(0f, 0.75f, progress)`）。 */
private const val THUMB_SAMPLE_SCALE_Y_MAX = 0.75f

/**
 * **全 App 统一的开关**：按液态玻璃开关决定走 [GlassSwitch] 还是 M3 `Switch`。
 *
 * ⚠️ **2026-10-06 换回过 M3 尺寸那版**：本文件里的 [LiquidToggleSwitch]（照抄库示例）
 * 在设置页里没问题，但**工作流卡片上不成立** —— 卡片那格只有约 116dp 宽、
 * 开关要与图标/⋮ 挤在一行，64dp 的轨道在那里显得又宽又扁；更要命的是
 * 它的滑块占轨道 62.5%，关闭时柱位几乎看不见（用户原话「关闭的时候都看不到
 * 底下的槽位了」）。⇒ 玻璃态改回 [GlassSwitch]，本文件保留示例版实现备查。
 *
 * ⚠️⚠️ **参数与 M3 `Switch` 逐一对应（含顺序）** —— 调用点只需把 `Switch(`
 * 换成 `VFlowSwitch(`，**一个参数都不用加**。
 *
 * ⚠️ 玻璃态**尺寸与 M3 不同**（64×28 vs 52×32）：这是「照抄库示例」的直接后果，
 * 已与用户确认接受。卡片头行（单列约 116dp）里两种尺寸都能放下。
 *
 * @param containerColor 开关**背后**是什么颜色。默认 `Unspecified` ⇒ 透明
 *   （玻璃只采样轨道）。⚠️ 卡片上应当传**卡片底色**，否则折射出来的底色与
 *   实际背景不符 —— 透错色比不透色更假。
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
    containerColor: Color = Color.Unspecified,
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
            containerColor = containerColor,
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
