package com.chaomixian.vflow.ui.common.glass

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
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
    /**
     * 轨道宽。⚠️ 示例是 **64dp**，这里同等比例收到 **55**。
     * ⚠️ 其余所有尺寸都按同一个系数缩放（见 [Scale]），滑块也一起缩。
     */
    val TrackWidth = 55.dp
    val TrackHeight = 24.dp

    /** 示例 `size(40f.dp, 24f.dp)` —— 按 [Scale] 缩放。 */
    val ThumbWidth = 34.dp
    val ThumbHeight = 20.dp

    /** 示例里的 `padding = 2f.dp`。 */
    val Padding = 2.dp

    /**
     * **按下时滑块浮起并放大**的倍率 —— 示例 `1.5f`。
     *
     * ⚠️⚠️ **这条曾经被误判掉过一次，记在这里以免再犯**：我一度把它收回 `1f`，
     * 理由写的是「用户反馈『关闭的时候那个圆会变小』的真因就是它」。
     * **那个判断是错的**，用户随后明确指出原版就是「拖动时滑块浮起并变大覆盖掉轨道」，
     * 而这正是本参数 + `layerBlock` 里的 `scaleX/scaleY` 共同产生的效果，
     * 是本控件「液态」观感的核心，**不能删**。
     *
     * ⚠️ 它在两处生效，缺一不可：
     * 1. `layerBlock` 里的 `scaleX/scaleY = dampedDragAnimation.scaleX/scaleY`
     *    —— 把滑块（含玻璃层）整体放大；
     * 2. `pressedScale` 就是 `DampedDragAnimation` 用来推 `scaleX/scaleY` 的目标值。
     *
     * ⚠️ 放大后滑块**会盖到轨道外面**（`Box` 默认不裁剪）：这是**刻意的**，
     * 「浮起并覆盖掉轨道」正是要的效果。
     */
    const val PressedScale = 1.5f

    /**
     * 示例尺寸与本实现尺寸之间的比例系数（55 / 64）。
     *
     * ⚠️ **所有尺寸同乘这个系数**：只收轨道宽而不收滑块的话，滑块占轨道的
     * 比例会从 62.5% 升到 66.7%，关闭态两侧露出的柱位更窄（用户上一版反馈的
     * 「关闭时看不到柱位」就会被放大）。
     *
     * ⚠️ 系数是**算出来的、不是调出来的**：先定轨道宽 55（卡片单列约 116dp
     * 里它要与图标/⋮ 挤一行），其余按比例推。
     */
    const val Scale = 55f / 64f
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
    // ⚠️ `didDrag` 用 `remember { BooleanArray }` 包一层：回调闭包在
    //    `remember(animationScope)` 里被捕获**一次**，直接写 `var didDrag by
    //    remember` 会捕获到**首帧的委托**，后续赋值不生效（Kotlin 委托的
    //    局部变量被 lambda 捕获时的固有陷阱）。数组是引用类型，跨帧共享。
    val dragState = remember { booleanArrayOf(false) }
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
                if (dragState[0]) {
                    fraction = if (targetValue >= 0.5f) 1f else 0f
                    onCheckedChange?.invoke(fraction == 1f)
                    dragState[0] = false
                } else {
                    // 轻点：取反当前值。⚠️ 不能用闭包里的 `checked` —— 它在
                    // `remember(animationScope)` 里被捕获一次，之后永远是最初那个值
                    // ⇒ 第二次点击算出来的目标与第一次相同 ⇒ **点一次开、再点开不了**。
                    // 用 `fraction`（同一帧的当前进度）判，它由 `LaunchedEffect` 持续同步。
                    val next = if (fraction >= 0.5f) 0f else 1f
                    fraction = next
                    onCheckedChange?.invoke(next == 1f)
                }
            },
            onDrag = { _, dragAmount ->
                if (!dragState[0]) dragState[0] = dragAmount.x != 0f
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
                // ⚠️ 语义显式补上（示例原版也没有；它靠滑块上的 `role = Role.Switch`）。
                //    这里补全 `toggleableState` 与 `onClick`，TalkBack 才能读出状态、
                //    并用「双击」切换。
                .semantics {
                    role = Role.Switch
                    toggleableState = if (checked) ToggleableState.On else ToggleableState.Off
                    if (onCheckedChange != null) {
                        onClick(label = "切换", action = { onCheckedChange(!checked); true })
                    }
                }
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
 * **全 App 统一的开关**：按液态玻璃开关决定走 [LiquidToggleSwitch] 还是 M3 `Switch`。
 *
 * ⚠️ **2026-10-06 第三次定版**：玻璃态终于用回**照抄库示例**的那版。
 * 中间曾被改回 [GlassSwitch]（M3 尺寸），原因是当时把三件事混在一起判断了：
 * ① 示例轨道偏宽（64dp）、② 关闭时柱位看不清、③ 点不亮/拖不动。
 * 复核后确认：
 * - **② 的真因不是宽度** —— 是 `pressedScale = 1.5f`（按下整体放大）让滑块
 *   在松手后「缩回去」，读起来像「关闭时变小」；宽度只是把这个问题放大了。
 * - **③ 的真因是 `toggleable`** —— 它内部的 `detectTapAndPress` 与拖动抢同一个
 *   down；示例原版**根本没有 `toggleable`**（点击全在 `DampedDragAnimation`
 *   的 `onDragStopped` 里判 `didDrag`）。
 * ⇒ 只保留一处真正的偏离：**轨道宽 64 → 60**（用户「示例有些偏宽」的原话），
 * 其余全部照抄示例（含宽滑块、「摇杆」式左对齐位移、折射链路）。
 *
 * ⚠️ 另修掉示例原版在**我们这里**才会暴露的一只 bug：`onDragStopped` 的
 * 轻点分支用闭包捕获的 `checked`（在 `remember` 里捕获**一次**）⇒ 第二次点击
 * 算出的目标与第一次相同 ⇒「点一次能关、再点开不了」。改用同一帧的 `fraction` 判。
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
        LiquidToggleSwitch(
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
