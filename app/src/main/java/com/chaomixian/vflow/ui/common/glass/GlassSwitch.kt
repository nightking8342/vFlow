package com.chaomixian.vflow.ui.common.glass

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchColors
import androidx.compose.material3.SwitchDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 开关的胶囊形状。
 *
 * ⚠️ **不用 kyant 的 `com.kyant.shapes.Capsule`** —— 那个库只是 `backdrop` 的
 * **运行期**传递依赖，**不在编译类路径上**（`./gradlew :app:dependencies` 实测），
 * 直接 import 编译不过。要用它得往 `app/build.gradle.kts` 显式加一条
 * `implementation("io.github.kyant0:shapes:1.2.0")`，为了一颗滑块改构建依赖
 * 不划算（本仓库「控制 diff 面积」的原则）。
 *
 * `RoundedCornerShape(percent = 50)` 在非正方形盒子上渲染出的就是**标准的胶囊**
 * （两端半圆），与 kyant 那颗在 24dp 尺度上肉眼无差 ——
 * 两者的差别只在「连续曲率 vs 圆角」，那个差别要到几十 dp 的圆角半径才看得出来。
 */
private val SWITCH_CAPSULE = RoundedCornerShape(percent = 50)

/**
 * 开关的**尺寸规格** —— ⚠️ **数值原样取自 Material 3 的 `SwitchTokens`**。
 *
 * 这一条是「保留 M3 形态」承诺的落实方式：玻璃化**只换材质、不换形态**。
 * 数值从 `material3` 字节码里 `javap` 解 `SwitchTokens` 的静态初始化块读出，
 * 不是照文档抄的（文档写 dp 整数、token 存 `Dp` inline value）。
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

    /** `(TrackHeight − SelectedHandleWidth) / 2`。 */
    val ThumbPadding = 4.dp

    /** `SwitchTokens.TrackOutlineWidth`。 */
    val TrackOutlineWidth = 2.dp

    /**
     * 按下时滑块横向撑开的量。
     *
     * ⚠️ **必须 ≤ 两侧内边距之和**（本文件有断言）：未选中态是中心锚定，
     * 撑开量的一半会跑到左边，超过内边距就会顶出轨道左端。
     */
    val ThumbPressGrowth = 4.dp

    /** 轨道的玻璃通透度：让背后的卡片底色透出来。 */
    const val TrackGlassAlpha = 0.62f

    /** 禁用态轨道的 alpha（对齐 M3 的 `DisabledTrackOpacity`）。 */
    const val DisabledTrackAlpha = 0.16f
}

/**
 * 滑块**直径**（两维同步）：在 16dp 与 24dp 之间按 `fraction` 插值。
 *
 * ⚠️⚠️ **宽高必须由同一个值驱动**。第一版把高度写死成轨道内高（24dp）、
 * 只让宽度在两档之间动 —— 关态就渲染出 16×24 的**竖条**，而它的圆角是
 * 胶囊形（半径 = 高/2 = 12dp > 宽的一半 = 8dp）⇒ 圆角被夹取，
 * 形状既不圆也不方。用户验收原话是「关闭时中间那个圆的白的东西都不贴合」，
 * 实测截图量出来正是 **17.6dp × 26.8dp**。
 *
 * ⚠️ 也不能用 `animateFloatAsState(checked)` —— 它只在整条 `Switch` 重组时
 * 被激活（`checked` 一变就跳到目标值），而位置早就由 `fraction` 平滑驱动了
 * ⇒ 表现是「位置滑过去、尺寸啪一下换掉」。
 */
internal fun thumbDiameterDp(fraction: Float): Dp =
    lerp(
        GlassSwitchTokens.UncheckedThumbDiameter.value,
        GlassSwitchTokens.ThumbDiameter.value,
        fraction.coerceIn(0f, 1f),
    ).dp

/**
 * 拖动时「走完一整条轨道」对应的横向像素数。
 *
 * ⚠️ 取的是**可移动距离**（轨道宽 − 两侧内边距 − 滑块直径）而非轨道宽：
 * 用手指走完 52dp 轨道只对应 fraction 0→1 的话，跟手感会明显「发飘」
 * （走了很远滑块才动一点）。
 */
internal fun dragWidthDp(checked: Boolean): Dp =
    GlassSwitchTokens.TrackWidth - GlassSwitchTokens.ThumbPadding * 2 -
        baseThumbWidthDp(checked)

/** 滑块在**未按下**时的基准宽度（选中 24dp / 未选中 16dp）。 */
internal fun baseThumbWidthDp(checked: Boolean): Dp =
    if (checked) GlassSwitchTokens.ThumbDiameter else GlassSwitchTokens.UncheckedThumbDiameter

/**
 * 滑块左边的 x（相对轨道）。
 *
 * ## ⚠️ 用「**中心点**插值」而不是「两端各写一支分支」
 *
 * 推导（`TrackWidth 52 / ThumbPadding 4 / 直径 16→24`）：
 * ```
 * centerX = lerp(4 + 16/2, 52 − 4 − 24/2, fraction) = lerp(12, 36, fraction)
 * x       = centerX − width / 2
 * ```
 * 这个写法在 **fraction = 0 / 0.5 / 1 三处都逐值等于 M3**（12 / 24 / 36 三个中心点）。
 *
 * ⚠️ 最早的写法是 `if (checked) 右对齐 else 左对齐` —— 两端静止位置也对，
 *    但**中间态会塌**：fraction = 0.5 时它算出中心 12dp（等于关态的位置），
 *    滑块会在动画中途往左弹一下再过去。根因是它只在两端正确，
 *    而「按下撑开」恰恰发生在中间态。
 *
 * ⚠️ 按下撑开量从中心**均分到两侧**（`− width/2` 里已经含了），
 * 左端最紧的情形（fraction = 0 + 全撑开）算出来是 2dp ≥ 0，不会越界。
 *
 * @param fraction 0..1 的连续进度（不是 `checked`）
 * @param widthDp 滑块**当前**宽度（含按下时的撑开量）
 */
internal fun glassThumbXDp(fraction: Float, widthDp: Dp): Dp {
    val f = fraction.coerceIn(0f, 1f)
    val minCenter = GlassSwitchTokens.ThumbPadding +
        GlassSwitchTokens.UncheckedThumbDiameter / 2
    val maxCenter = GlassSwitchTokens.TrackWidth - GlassSwitchTokens.ThumbPadding -
        GlassSwitchTokens.ThumbDiameter / 2
    val center = lerp(minCenter.value, maxCenter.value, f)
    return (center - widthDp.value / 2).dp
}

/**
 * **液态玻璃**开关（参考 kyant 的 `LiquidToggle`，形态仍照 M3）。
 *
 * ## 它到底「玻璃」在哪 —— 三件事，缺一件都看不出效果
 *
 * 1. **滑块是采样器**（`drawBackdrop`），它采样两样东西的合成：
 *    - **开关背后是什么颜色**（用调用方 / 主题给的 `containerColor` 画一层
 *      `CanvasBackdrop`）—— 玻璃得能透出背景；
 *    - **轨道**（`trackBackdrop`，见第 3 点）。
 * 2. **按下滑块会「化掉」**：静止时 `onDrawSurface` 用不透明度 1.0 的白把它糊住
 *    （所以静止看是一颗普通白色圆点），按下时白的不透明度随 `pressProgress`
 *    退到 0 ⇒ **底下那层被透镜扭曲的轨道色透出来**。这是整个观感里最「液态」的一帧。
 * 3. **透镜只在按下时吃到轨道**：轨道被 `rememberBackdrop` 包一层、
 *    用 `scaleY = lerp(0f, 0.75f, progress)` 做**纵向压扁**——
 *    静止时 `scaleY = 0`（采样里等于没有它，**从而避免「滑块采样自己盖住的轨道」
 *    这个自采样回环**），按下时才张开。这就是 kyant 原实现的做法。
 *
 * ⚠️⚠️ **这些效果一个都不能用 `drawBehind` 手画**：第一版就是手画「高光描边 +
 * 渐变」，用户验收时原话是「完全没有液态玻璃的效果」—— 因为真正让玻璃成立的
 * 是**折射**（`lens` 的 RuntimeShader）与**库自带的高光/内阴影着色器**，
 * 描边和渐变只是它们的粗糙模仿。
 *
 * ## ⚠️ 尺寸与交互全部照抄 M3（见 [GlassSwitchTokens]）
 *
 * ## ⚠️ 涟漪被刻意关掉（`indication = null`）
 *
 * 涟漪是「实色平面」的反馈语言；玻璃控件的反馈是**形变 + 折射**。
 * 可点性没有降低：`toggleable` + `Role.Switch` 保留全部无障碍语义与点击热区，
 * 手势层也不消费纵向位移（设置页是 `LazyColumn`，消费了会**滚不动**）。
 */
@Suppress("unused") // fork: 保留 M3 形态那份实现；对外入口见 LiquidToggleSwitch.kt 的 VFlowSwitch
@Composable
internal fun GlassSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: SwitchColors = SwitchDefaults.colors(),
    interactionSource: MutableInteractionSource? = null,
    thumbContent: (@Composable () -> Unit)? = null,
    /** 开关**背后**是什么颜色 —— 玻璃要透出的那层。 */
    containerColor: Color = Color.Unspecified,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val context = LocalContext.current
    val source = interactionSource ?: remember { MutableInteractionSource() }

    // 玻璃要透出的那层：调用方没给就用主题的表面色。
    val resolvedContainer = if (containerColor == Color.Unspecified) {
        androidx.compose.material3.MaterialTheme.colorScheme.surfaceContainerLow
    } else {
        containerColor
    }
    val containerBackdrop = rememberCanvasBackdrop { drawRect(resolvedContainer) }

    val trackBackdrop = rememberLayerBackdrop()

    val animation = remember(scope) {
        SwitchDragAnimation(
            animationScope = scope,
            initialFraction = if (checked) 1f else 0f,
            dragWidthPx = { with(density) { dragWidthDp(checked).toPx() } },
            onSettled = { nowChecked ->
                // 拖动落位：`checked` 由宿主更新；宿主若没接（onCheckedChange 为 null）
                // 也不能让滑块停在半路 —— 所以先本地对齐一次。
                if (onCheckedChange != null) onCheckedChange(nowChecked)
            },
        )
    }

    // ⚠️ 只跟**外部** checked 的变化：`animation.fraction` 是内部状态，
    //    拖动过程中它自己就在变，跟着它走会自己打断自己。
    LaunchedEffect(checked) {
        val target = if (checked) 1f else 0f
        if (kotlin.math.abs(animation.fraction - target) > 0.001f) {
            animation.animateTo(target)
        }
    }

    val fraction = animation.fraction
    val progress = animation.pressProgress

    // ⚠️⚠️ **轨道色与边框色也必须跟着 `fraction` 插值，不能用 `checked` 硬切**。
    //    跟 `fraction` 走之后，「点一下」的全过程才是连贯的：
    //    滑块滑过去的同时轨道由灰渐变蓝、边框由 2dp 渐隐到 0
    //    （M3 的选中态本来就没有边框）。用 `checked` 的话颜色会在动画第一帧
    //    就跳到终态 —— 用户 2026-10-06 反馈「点击切换时根本没什么动画，很生硬」，
    //    一半的原因在这里（另一半是尺寸那处 `animateFloatAsState` 瞬移）。
    val trackColor = lerpTrackColor(colors, enabled, checked ?: false, fraction)
    val borderColor = lerpBorderColor(colors, enabled, checked ?: false, fraction)
    val trackAlpha = if (enabled) GlassSwitchTokens.TrackGlassAlpha else GlassSwitchTokens.DisabledTrackAlpha

    // ⚠️⚠️ **宽度与高度必须由同一个值驱动，且必须跟着 `fraction` 走**。
    //
    // 两条都是实测抓出来的（用户 2026-10-06 反馈「关闭时中间那个圆的白的东西
    // 不贴合」）：
    //
    // ① **只动宽不动高会得到一个纵向拉长的胶囊** —— M3 的 16/24 是**两维同步**的
    //    （`UnselectedHandle{Width,Height} = 16`、`Selected = 24`，`javap` 解出）。
    //    原实现把高度写死成轨道的内高（24dp）⇒ 关闭态渲染出 16×24 的竖条，
    //    而它的圆角是 `Capsule`（半径 = 高/2 = 12dp > 宽的一半 = 8dp）⇒
    //    圆角被夹取，形状**既不圆也不方**、四边不贴合。实测截图里那个滑块
    //    量出来就是 **17.6dp 宽 × 26.8dp 高**。
    //
    // ② **用 `animateFloatAsState(checked)` 会「瞬移」** —— 它只在**整条
    //    `Switch` 重组**时才被激活（`checked` 一变就立刻跳到目标值），
    //    而玻璃滑块的位移早就由 `fraction` 平滑动画驱动了 ⇒ 表现是
    //    「位置滑过去、尺寸啪一下换掉」。改为从**同一个** `animation.fraction`
    //    插值，尺寸与位置才同步。
    val thumbSizePx = with(density) { thumbDiameterDp(fraction).toPx() }
    // 按下时横向撑开（「捏扁」的观感来自这里 + `layerBlock` 里的速度挤压）。
    val pressedExtra = with(density) { GlassSwitchTokens.ThumbPressGrowth.toPx() } * progress
    val thumbWidthPx = thumbSizePx + pressedExtra
    val thumbHeightPx = thumbSizePx
    val thumbWidthDp = with(density) { thumbWidthPx.toDp() }
    val thumbHeightDp = with(density) { thumbHeightPx.toDp() }

    // ⚠️ 位置与尺寸用**同一个** `thumbWidthDp` 算 —— 两处各算一遍的话，
    //    「按下撑开」那一刻位置与宽度会各自插值、对不上（滑块会先动再长）。
    val thumbOffsetDp = glassThumbXDp(fraction, thumbWidthDp)

    Box(
        modifier
            .size(GlassSwitchTokens.TrackWidth, GlassSwitchTokens.TrackHeight)
            // ⚠️ `toggleable` 的 `onValueChange` 非空，而 M3 `Switch` 的可空
            //    （null = 「只显示、由别处控制」）。调用点有传 null 的。
            .then(
                if (onCheckedChange != null) {
                    Modifier.toggleable(
                        value = checked,
                        onValueChange = onCheckedChange,
                        enabled = enabled,
                        role = Role.Switch,
                        interactionSource = source,
                        indication = null,
                    )
                } else {
                    Modifier
                }
            )
            // 手势层：按下起拖、水平跟手、抬手落位。
            .then(if (enabled && onCheckedChange != null) animation.modifier else Modifier),
        contentAlignment = Alignment.CenterStart,
    ) {
        // ---- 轨道 ----
        // ⚠️ 轨道**不是**玻璃：kyant 的实现里它就是个纯色胶囊，
        //    `layerBackdrop` 只为了把自己录进 `trackBackdrop` 供滑块采样。
        Box(
            Modifier
                .matchParentSize()
                .layerBackdrop(trackBackdrop)
                .clip(SWITCH_CAPSULE)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            trackColor.copy(alpha = trackAlpha),
                            trackColor.copy(alpha = trackAlpha * 0.82f),
                        )
                    )
                )
        ) {
            // 描边：M3 的**未选中态**本来就有 2dp 边框（选中态没有）。
            // 用 `drawBehind` 画在外层，避免再套一层布局节点。
            if (borderColor != Color.Unspecified) {
                Box(
                    Modifier
                        .matchParentSize()
                        .drawBehind {
                            drawRoundRect(
                                color = borderColor,
                                cornerRadius = CornerRadius(size.height / 2f),
                                style = Stroke(width = GlassSwitchTokens.TrackOutlineWidth.toPx()),
                            )
                        }
                )
            }
        }

        // ---- 滑块（玻璃本体）----
        Box(
            Modifier
                .offset(x = thumbOffsetDp)
                .size(thumbWidthDp, thumbHeightDp)
                .graphicsLayer {
                    scaleX = animation.scaleX *
                        (1f - (animation.velocity * SwitchDragAnimation.VELOCITY_SQUEEZE)
                            .coerceIn(-0.25f, 0.25f))
                    scaleY = animation.scaleY
                }
                .drawBackdrop(
                    // ① 开关背后的颜色 + ② 被纵向压扁的轨道（静止时压扁量为 0）
                    backdrop = rememberCombinedBackdrop(
                        containerBackdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val p = animation.pressProgress
                            val scaleX = kotlin.math.abs(kotlin.math.cos((1f - p) * Math.PI.toFloat() / 2f))
                                .coerceAtLeast(0.001f)
                            val scaleY = 0.75f * p
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { SWITCH_CAPSULE },
                    effects = {
                        val p = animation.pressProgress
                        // ⚠️ 模糊只在静止时全量、按下时让位给透镜 ——
                        //    两者叠加会把折射糊掉。
                        blur(8.dp.toPx() * (1f - p))
                        lens(
                            5.dp.toPx() * p,
                            10.dp.toPx() * p,
                            chromaticAberration = true,
                        )
                    },
                    highlight = {
                        val p = animation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = p,
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4.dp,
                            color = Color.Black.copy(alpha = 0.18f),
                        )
                    },
                    innerShadow = {
                        val p = animation.pressProgress
                        InnerShadow(radius = 4.dp * p, alpha = p)
                    },
                    layerBlock = {
                        scaleX = animation.scaleX
                        scaleY = animation.scaleY
                    },
                    onDrawSurface = {
                        // ⚠️ **按下的核心动画**：白的不透明度随进度退到 0，
                        //    底下被折射的轨道色才透出来（见类 KDoc 第 2 点）。
                        val p = animation.pressProgress
                        drawRect(
                            Color.White.copy(alpha = if (enabled) 0.95f * (1f - p) else 0.38f)
                        )
                    }
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (thumbContent != null && checked) thumbContent()
        }
    }
}

/**
 * 按 `fraction` 在「关 / 开」两种轨道色之间插值。
 *
 * ⚠️ 三个判断的**优先级**是定死的：禁用态 > 目标态 > 当前进度 ——
 * 把禁用态排在后面会让禁用开关在切换时闪一下彩色。
 */
private fun lerpTrackColor(
    colors: SwitchColors,
    enabled: Boolean,
    checked: Boolean,
    fraction: Float,
): Color {
    if (!enabled) {
        return if (checked) colors.disabledCheckedTrackColor else colors.disabledUncheckedTrackColor
    }
    return androidx.compose.ui.graphics.lerp(
        colors.uncheckedTrackColor,
        colors.checkedTrackColor,
        fraction.coerceIn(0f, 1f),
    )
}

/**
 * 边框色插值 —— ⚠️ **含 alpha 一起插**。
 *
 * M3 里边框是**未选中态独有**的视觉（`checkedBorderColor` 默认全透明），
 * 所以「选中」这个动作本身包含「边框淡出」。若只插 RGB 不插 alpha，
 * 打开后会留下一圈实色描边 —— 而它看起来「也挺像玻璃的边缘高光」，
 * 很容易被当成有意为之、没人会去查。
 */
private fun lerpBorderColor(
    colors: SwitchColors,
    enabled: Boolean,
    checked: Boolean,
    fraction: Float,
): Color {
    if (!enabled) return colors.disabledUncheckedBorderColor
    return androidx.compose.ui.graphics.lerp(
        colors.uncheckedBorderColor,
        colors.checkedBorderColor.copy(alpha = 0f),
        fraction.coerceIn(0f, 1f),
    )
}
