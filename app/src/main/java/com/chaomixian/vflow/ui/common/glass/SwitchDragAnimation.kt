package com.chaomixian.vflow.ui.common.glass

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * 玻璃开关的**拖动 + 形变**动画状态机（0..1 连续值）。
 *
 * ## 为什么不能直接复用底栏那个 `DampedDragAnimation`
 *
 * 底栏那个的 `onDragStopped` 签名是 `() -> Unit`、**拿不到抬手位置**，
 * 而开关必须知道「拖动结束时手指停在左半还是右半」才决定最终是开还是关
 * （它内部只做 `roundToInt` 到最近格，对两格控件完全够用、对开关不够）。
 *
 * ⚠️ 也没有把它改成带位置回调 —— `DampedDragAnimation` 是底栏与玻璃 Tab 栏
 * 共用的**既有实现**，动它是「为一个新消费者改三个既有消费者的行为」。
 * 本类是新文件、只服务开关。
 *
 * ## 手感与 kyant 的 `LiquidToggle` 对齐的两处
 *
 * 1. **按下即成「拖动态」**（无需长按）—— 于是「轻点切换」天然可用：
 *    没产生过横向位移就是一次点击，由调用方取反当前值。
 * 2. **拖动中 1:1 跟手、不做位移动画**；只有 `checked` 变化才 `animateTo`。
 *
 * ## 速度形变
 *
 * `velocity` 是「每毫秒走过的 fraction」，喂给滑块的横向挤压。
 * ⚠️ 抬手后必须显式归零（见 [release]）—— 漏了的表现是滑块**永远保持
 * 上一次拖动末尾的挤压形变**（拖得越快、停下后越扁），且不报错。
 *
 * @param dragWidthPx 走完 fraction 0→1 对应的横向像素数。取**可移动距离**
 *   （轨道宽 − 滑块宽 − 两侧内边距）而非轨道宽 —— 用轨道宽会让跟手感发飘。
 */
internal class SwitchDragAnimation(
    private val animationScope: CoroutineScope,
    initialFraction: Float,
    private val dragWidthPx: () -> Float,
    /** 拖动落位后把最终值回调出去 —— 「滑块停右边但 `checked` 还是 false」会状态脱节。 */
    private val onSettled: (Boolean) -> Unit = {},
    /**
     * **轻点**（按下后没产生过水平位移）。
     *
     * ⚠️⚠️ 必须由手势层自己判，**不能靠外层的 `toggleable`** —— 后者的
     * `detectTapAndPress` 会消费掉 down 事件，把拖动侧饿死；而没被消费的点击
     * 会**冒泡到宿主**（工作流卡片的 `combinedClickable`）⇒ 表现是
     * 「关闭状态下点开关，很大几率点进工作流内部」。这两件事是同一个根因。
     */
    private val onTap: () -> Unit = {},
) {
    private val fractionAnimation = Animatable(initialFraction)
    private val pressAnimation = Animatable(0f)
    private val scaleXAnimation = Animatable(1f)
    private val scaleYAnimation = Animatable(1f)
    private val velocityAnimation = Animatable(0f)

    private val mutex = MutatorMutex()
    private var velocityJob: Job? = null
    private var lastSampleAt = 0L
    private var lastSampleValue = initialFraction

    val fraction: Float get() = fractionAnimation.value
    val pressProgress: Float get() = pressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    fun press() {
        lastSampleAt = 0L
        lastSampleValue = fraction
        animationScope.launch { pressAnimation.animateTo(1f, spring(1f, 1000f, 0.001f)) }
        animationScope.launch { scaleYAnimation.animateTo(PRESSED_SCALE, spring(0.7f, 250f, 0.001f)) }
    }

    fun release() {
        animationScope.launch { pressAnimation.animateTo(0f, spring(1f, 1000f, 0.001f)) }
        animationScope.launch { scaleXAnimation.animateTo(1f, spring(0.6f, 250f, 0.001f)) }
        animationScope.launch { scaleYAnimation.animateTo(1f, spring(0.7f, 250f, 0.001f)) }
        // ⚠️ 速度必须显式归零（见类 KDoc）。
        velocityJob?.cancel()
        animationScope.launch { velocityAnimation.snapTo(0f) }
        lastSampleAt = 0L
    }

    /** 拖动中：**1:1 跟手**，不做位移动画。 */
    fun dragTo(target: Float) {
        val clamped = target.coerceIn(0f, 1f)
        animationScope.launch { fractionAnimation.snapTo(clamped) }
        trackVelocity(clamped)
    }

    /** `checked` 变化 / 抬手落位：走动画。 */
    fun animateTo(target: Float, stiffness: Float = 1000f) {
        animationScope.launch {
            mutex.mutate {
                fractionAnimation.animateTo(target.coerceIn(0f, 1f), spring(1f, stiffness, 0.001f))
            }
        }
    }

    /** 抬手：先落位、再清速度。 */
    fun settleTo(target: Float) {
        animateTo(target, stiffness = 800f)
        velocityJob?.cancel()
        animationScope.launch { velocityAnimation.snapTo(0f) }
    }

    /**
     * 手势层：**按下即起拖**、水平位移跟手、抬手结束。
     *
     * ⚠️ **不消费纵向位移**：开关会被放进可滚动列表（设置页就是 `LazyColumn`），
     * 无差别消费会让**列表滚不动**（表现是「手指按在开关上时整页卡住」）。
     * 这里只在**水平位移占优**时才消费并驱动滑块，纵向一律放行给滚动容器。
     *
     * ⚠️⚠️ **`onEnd(dragged)` 必须在拖动过的情况下把「落位」也做掉** ——
     *    这正是「按得亮、拖不动」那条反馈里被忽略的一半：光把 `fraction`
     *    跟着手指移，抬手后**没人把它落到 0 或 1**，滑块会停在半路
     *    （看起来就像「没拖成功」）。落位方向按 `fraction >= 0.5` 判。
     */
    val modifier: Modifier = Modifier.pointerInput(Unit) {
        inspectPressDragGestures(
            onStart = { press() },
            onDrag = { dx ->
                val width = dragWidthPx()
                if (width > 0f) dragTo(fraction + dx / width)
            },
            onEnd = { dragged ->
                release()
                if (dragged) {
                    val target = if (fraction >= 0.5f) 1f else 0f
                    settleTo(target)
                    onSettled(target == 1f)
                } else {
                    onTap()
                }
            },
        )
    }

    private fun trackVelocity(value: Float) {
        val now = System.nanoTime() / 1_000_000
        if (lastSampleAt != 0L && now > lastSampleAt) {
            val v = (value - lastSampleValue) / (now - lastSampleAt)
            velocityJob?.cancel()
            velocityJob = animationScope.launch {
                velocityAnimation.animateTo(v, spring(0.5f, 300f, 0.005f))
            }
        }
        lastSampleAt = now
        lastSampleValue = value
    }

    companion object {
        /** 按下时滑块在**高度**方向略微撑开（宽度由 `pressProgress` 另算）。 */
        const val PRESSED_SCALE = 1.04f

        /** 速度对滑块的横向挤压系数：越大越「弹」，0 = 关掉形变。 */
        const val VELOCITY_SQUEEZE = 0.35f
    }
}

/**
 * **按下即起拖**的手势循环：按下开始、移动跟手、抬手结束。
 *
 * ⚠️ 与底栏/玻璃 Tab 栏用的 `inspectLongPressDragGestures` 是两套：
 * 那个有长按门槛（为了与横向滚动共存），开关在设置行里**没有可滚动的手势
 * 与之竞争**，加门槛只会让「轻点」变得迟钝。
 *
 * ## ⚠️ 三处照抄本仓库已验证的实现（`DragGestureInspector`）
 *
 * 1. **`awaitFirstDown(false, Pass.Initial)`** —— 在 Initial 通道上拿 down，
 *    保证早于任何祖先（`toggleable`、`LazyColumn` 的 `scrollable`）。
 * 2. **位移一律用 `positionChangeIgnoreConsumed()`** —— `positionChange()`
 *    在事件**已被消费**时返回 `Offset.Zero`（`DragGestureInspector` 有实测记录）。
 *    第一版是手算 `position - previousPosition`，看似等价、实则绕开了这条纪律，
 *    一旦别处消费过就再也拿不到位移（表现正是「按得亮、拖不动」）。
 * 3. **只有水平占优才 `consume()`** —— 无差别消费会让**列表滚不动**。
 */
private suspend fun PointerInputScope.inspectPressDragGestures(
    onStart: () -> Unit,
    onDrag: (dx: Float) -> Unit,
    onEnd: (dragged: Boolean) -> Unit,
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        val down = awaitFirstDown(requireUnconsumed = false)
        onStart()

        var dragged = false
        var totalX = 0f
        var totalY = 0f
        var pointer = initialDown.id

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.fastFirstOrNull { it.id == pointer } ?: break
            if (!change.pressed) {
                // ⚠️ **抬手时一律消费**（不管有没有拖过）—— 轻点若不消费，
                //    事件会冒泡到宿主（工作流卡片的 `combinedClickable`），
                //    于是「点开关」变成「点进工作流」。这是实测反馈的那只 bug。
                change.consume()
                break
            }
            val delta = change.positionChangeIgnoreConsumed()
            totalX += delta.x
            totalY += delta.y
            if (totalX != 0f || totalY != 0f) {
                if (abs(totalX) > abs(totalY) && abs(totalX) > 1f) {
                    dragged = true
                    change.consume()
                    onDrag(delta.x)
                }
            }
            pointer = change.id
        }
        onEnd(dragged)
    }
}
