package com.chaomixian.vflow.ui.main.glass

import android.annotation.SuppressLint
import android.graphics.RuntimeShader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@SuppressLint("NewApi")
class InteractiveHighlight(
    val animationScope: CoroutineScope,
    val position: (size: Size, offset: Offset) -> Offset = { _, offset -> offset },
    /**
     * 是否**长按起拖**（要与同一个指示块上的 `DampedDragAnimation` 传一致）。
     *
     * ⚠️ 两者必须一致，否则在那段长按等待里一个已经亮了、另一个还没反应，
     * 看起来像「光晕比拖动早半秒」。
     * ⚠️ 本类**不消费**事件（消费由 `DampedDragAnimation` 负责）—— 两者都消费
     * 会让同一节点上的另一个检测器被「已消费」挡掉。但它必须**容忍**被消费
     * （见 `drag(ignoreConsumed = true)`），否则先跑的那个一消费、光晕就断。
     */
    val longPressDrag: Boolean = false,
) {
    private val pressProgressAnimationSpec = spring(0.5f, 300f, 0.001f)
    private val positionAnimationSpec = spring(0.5f, 300f, Offset.VisibilityThreshold)

    private val pressProgressAnimation = Animatable(0f, 0.001f)
    private val positionAnimation = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)

    private var startPosition = Offset.Zero

    private val shader = RuntimeShader(
        """
            uniform float2 size;
            layout(color) uniform half4 color;
            uniform float radius;
            uniform float2 position;

            half4 main(float2 coord) {
                float dist = distance(coord, position);
                float intensity = smoothstep(radius, radius * 0.5, dist);
                return color * intensity;
            }
        """.trimIndent()
    )

    val modifier: Modifier = Modifier.drawWithContent {
        val progress = pressProgressAnimation.value
        if (progress > 0f) {
            drawRect(Color.White.copy(alpha = 0.06f * progress), blendMode = BlendMode.Plus)
            shader.apply {
                val target = position(size, positionAnimation.value)
                setFloatUniform("size", size.width, size.height)
                setColorUniform("color", Color.White.copy(alpha = 0.12f * progress).toArgb())
                setFloatUniform("radius", size.minDimension * 1.2f)
                setFloatUniform(
                    "position",
                    target.x.fastCoerceIn(0f, size.width),
                    target.y.fastCoerceIn(0f, size.height)
                )
            }
            drawRect(ShaderBrush(shader), blendMode = BlendMode.Plus)
        }
        drawContent()
    }

    val gestureModifier: Modifier = Modifier.pointerInput(animationScope, longPressDrag) {
        val onDragStart: (down: PointerInputChange) -> Unit = { down ->
            startPosition = down.position
            animationScope.launch {
                launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
                launch { positionAnimation.snapTo(startPosition) }
            }
        }
        val onDragEnd: (PointerInputChange) -> Unit = {
            animationScope.launch {
                launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
            }
        }
        val onDragCancel: () -> Unit = {
            animationScope.launch {
                launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
                launch { positionAnimation.animateTo(startPosition, positionAnimationSpec) }
            }
        }
        val onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit = { change, _ ->
            animationScope.launch { positionAnimation.snapTo(change.position) }
        }
        // ⚠️ 与 `DampedDragAnimation` 用**同一个**手势函数：两者挂在同一个
        //    指示块节点上，长按阈值必须一致，否则光晕与位移会差半秒。
        //    ⚠️ 这里传的 lambda **不消费**事件（消费由 `DampedDragAnimation` 做）——
        //    两个都消费会让同节点上的另一个检测器被「已消费」挡掉。
        if (longPressDrag) {
            inspectLongPressDragGestures(onDragStart, onDragEnd, onDragCancel, onDrag)
        } else {
            inspectDragGestures(onDragStart, onDragEnd, onDragCancel, onDrag)
        }
    }
}
