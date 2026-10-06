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
import com.chaomixian.vflow.ui.common.AppearanceManager
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

    /** 轨道的玻璃通透度：让背后的卡片底色透出来。 */
    const val TrackGlassAlpha = 0.62f

    /** 禁用态轨道的 alpha（对齐 M3 的 `DisabledTrackOpacity`）。 */
    const val DisabledTrackAlpha = 0.16f
}

/** 滑块在**未按下**时的基准宽度（选中 24dp / 未选中 16dp）。 */
internal fun baseThumbWidthDp(checked: Boolean): Dp =
    if (checked) GlassSwitchTokens.ThumbDiameter else GlassSwitchTokens.UncheckedThumbDiameter

/**
 * 滑块左下角的 x（相对轨道）。
 *
 * ⚠️ **两侧的锚定方式刻意不同**：选中态**右锚定**（右边贴住内边距不动、
 * 有宽度富余时向左长），未选中态**中心锚定** —— 统一成一种会顶出轨道边缘，
 * 而 `Box` 默认**不裁剪**，所以不报错、只是看起来滑块探出来了。
 *
 * @param widthPx 滑块**当前**宽度（含按下时的撑开量，由手势层的动画给出）
 */
internal fun glassThumbXDp(checked: Boolean, widthDp: Dp): Dp {
    val base = baseThumbWidthDp(checked)
    return if (checked) {
        GlassSwitchTokens.TrackWidth - GlassSwitchTokens.ThumbPadding - widthDp
    } else {
        GlassSwitchTokens.ThumbPadding - (widthDp - base) / 2
    }
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
            dragWidthPx = {
                with(density) {
                    (GlassSwitchTokens.TrackWidth - GlassSwitchTokens.ThumbPadding * 2 -
                        baseThumbWidthDp(checked)).toPx()
                }
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

    val trackColor = when {
        !enabled && checked -> colors.disabledCheckedTrackColor
        !enabled -> colors.disabledUncheckedTrackColor
        checked -> colors.checkedTrackColor
        else -> colors.uncheckedTrackColor
    }
    val borderColor = when {
        !enabled -> colors.disabledUncheckedBorderColor
        checked -> colors.checkedBorderColor
        else -> colors.uncheckedBorderColor
    }

    // 滑块宽度：M3 的两档（16 / 24）之间按 fraction 插值 + 按下时的撑开。
    val widthPx by animateFloatAsState(
        targetValue = with(density) { baseThumbWidthDp(checked).toPx() },
        animationSpec = spring(1f, 1000f, 0.001f),
        label = "glassSwitchThumbWidth",
    )
    val heightPx = with(density) { GlassSwitchTokens.TrackHeight.toPx() } -
        with(density) { GlassSwitchTokens.ThumbPadding.toPx() } * 2
    // 按下时横向撑开（「捏扁」的观感来自这里 + `layerBlock` 里的速度挤压）。
    val pressedExtra = with(density) { 4.dp.toPx() } * progress
    val thumbWidthPx = widthPx + pressedExtra
    val thumbWidthDp = with(density) { thumbWidthPx.toDp() }

    val thumbOffsetDp = glassThumbXDp(checked, thumbWidthDp)

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
                            trackColor.copy(alpha = if (enabled) GlassSwitchTokens.TrackGlassAlpha else GlassSwitchTokens.DisabledTrackAlpha),
                            trackColor.copy(alpha = if (enabled) GlassSwitchTokens.TrackGlassAlpha * 0.82f else GlassSwitchTokens.DisabledTrackAlpha * 0.82f),
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
                .size(thumbWidthDp, with(density) { heightPx.toDp() })
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
 * **全 App 统一的开关**：按液态玻璃开关决定走 [GlassSwitch] 还是 M3 `Switch`。
 *
 * ⚠️⚠️ **参数与 M3 `Switch` 逐一对应（含顺序）** —— 调用点只需把 `Switch(`
 * 换成 `VFlowSwitch(`，**一个参数都不用加**。玻璃态需要的颜色从同一份
 * [colors] 里读，所以同一个开关在两种材质下色相一致。
 *
 * @param containerColor 开关**背后**是什么颜色。默认 `Unspecified` ⇒ 用主题的
 *   `surfaceContainerLow`。⚠️ 卡片上应当传**卡片底色**，否则玻璃会透出一个
 *   与实际背景不符的颜色（透错色比不透色更假）。
 *
 * ⚠️ **开关状态是「读一次」的**（`remember`），与既有玻璃组件同一模式；
 *    `AppearanceManager` 没有变更通知机制，做响应式得先给它加 `StateFlow`。
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
            thumbContent = thumbContent,
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
