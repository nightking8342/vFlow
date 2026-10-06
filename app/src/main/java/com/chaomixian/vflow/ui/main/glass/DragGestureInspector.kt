package com.chaomixian.vflow.ui.main.glass

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChangeIgnoreConsumed
import androidx.compose.ui.util.fastFirstOrNull

suspend fun PointerInputScope.inspectDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)

        onDragStart(down)
        onDrag(initialDown, Offset.Zero)

        val upEvent = drag(pointerId = initialDown.id, ignoreConsumed = false) {
            onDrag(it, it.positionChange())
        }
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

/**
 * 与 [inspectDragGestures] 相同，但**必须长按超过 `longPressTimeoutMillis` 才开始拖**。
 *
 * ## 为什么要长按门槛
 *
 * ⚠️⚠️ 即时起拖会与**同一个方向上的横向滚动**直接冲突：滚动容器一收到水平位移
 * 就当作滚动，而指示块一收到位移就当作拖动 —— 同一条指针流被两方争抢，
 * 表现为「想滚动却把选中项带跑了」。
 *
 * 长按把两者**在时间上分开**：按下后立刻横向移动 ⇒ 不满足长按 ⇒ 事件原样下传
 * ⇒ **滚动**；按住不动超过阈值 ⇒ 长按成立 ⇒ 此后移动由**拖动**接管。
 *
 * ## 四条实现约束（每条都对应一种实测过的表现）
 *
 * 1. **长按判定交给 [awaitLongPressOrCancellation]**，不手写超时。
 * 2. ⚠️ **长按达成那一刻不消费**。`awaitLongPressOrCancellation` 的语义是
 *    「事件一旦被消费就返回 null」，而同一个指示块上挂着**两个**检测器
 *    （本函数与 `DampedDragAnimation.modifier`）—— 谁先消费谁把另一个挡掉，
 *    表现是**只有一个生效**（要么光晕不亮、要么指示块不动），且不报错。
 * 3. ⚠️ **第一次移动起必须 `consume()`**。这是「拖动与滚动共存」的**全部依据**：
 *    Compose 的 Main pass 子节点先于父节点，消费掉之后父级 `scrollable` 会放弃
 *    本次滚动。漏了的表现是指示块跟手、**页面同时也在滚**（位移翻倍）。
 * 4. ⚠️ **跟着移动的循环要 `ignoreConsumed = true`** —— 指示块上另一个检测器
 *    也会消费（`InteractiveHighlight` 要跟手指更新光晕位置），见 [drag] 的说明。
 */
suspend fun PointerInputScope.inspectLongPressDragGestures(
    onDragStart: (down: PointerInputChange) -> Unit = {},
    onDragEnd: (change: PointerInputChange) -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: PointerInputChange, dragAmount: Offset) -> Unit
) {
    awaitEachGesture {
        val initialDown = awaitFirstDown(false, PointerEventPass.Initial)
        val down = awaitFirstDown(false)

        // ⚠️ 阈值内抬手 / 被别的检测器取消 ⇒ 返回 null ⇒ **不回调、不消费**，
        //    这次手势整个让给外层（滚动容器）。
        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
        if (!longPress.pressed) return@awaitEachGesture

        onDragStart(longPress)
        onDrag(longPress, Offset.Zero)

        val upEvent = drag(pointerId = longPress.id, ignoreConsumed = true) { change ->
            // ⚠️⚠️ **先取位移、再 `consume()`，顺序不能反**。
            //    `PointerInputChange.positionChange()` 在「已被消费」时**返回 `Offset.Zero`**
            //    （实测确认，见 `PointerEventKt.positionChange` 的字节码）。
            //    先消费再取位移 ⇒ 每一次位移都是 0 ⇒ 表现是
            //    **长按有浮动效果、但指示块纹丝不动**（已实际踩过，就是这一行）。
            val delta = change.positionChangeIgnoreConsumed()
            change.consume()
            onDrag(change, delta)
        }
        if (upEvent == null) {
            onDragCancel()
        } else {
            onDragEnd(upEvent)
        }
    }
}

/**
 * 跟踪一次拖动直到抬手。
 *
 * @param ignoreConsumed 事件被别人消费后是否继续跟。
 *
 * ⚠️ 长按拖动必须传 `true`：指示块上**同时挂着两个**手势检测器
 * （`DampedDragAnimation` 管位移、`InteractiveHighlight` 管光晕），
 * 后者在长按达成时会消费掉那一刻 —— 若这里「见消费就退出」，
 * 表现是**一拖起来就不动了**。前者消费移动是为了挡住滚动，
 * 后者不消费移动，两者不冲突。
 */
private suspend inline fun AwaitPointerEventScope.drag(
    pointerId: PointerId,
    ignoreConsumed: Boolean,
    onDrag: (PointerInputChange) -> Unit
): PointerInputChange? {
    val isPointerUp = currentEvent.changes.fastFirstOrNull { it.id == pointerId }?.pressed != true
    if (isPointerUp) return null

    var pointer = pointerId
    while (true) {
        val change = awaitDragOrUp(pointer) ?: return null
        if (!ignoreConsumed && change.isConsumed) return null
        if (change.changedToUpIgnoreConsumed()) return change
        onDrag(change)
        pointer = change.id
    }
}

private suspend inline fun AwaitPointerEventScope.awaitDragOrUp(
    pointerId: PointerId
): PointerInputChange? {
    var pointer = pointerId
    while (true) {
        val event = awaitPointerEvent()
        val dragEvent = event.changes.fastFirstOrNull { it.id == pointer } ?: return null
        if (dragEvent.changedToUpIgnoreConsumed()) {
            val otherDown = event.changes.fastFirstOrNull { it.pressed }
            if (otherDown == null) {
                return dragEvent
            }
            pointer = otherDown.id
        } else if (dragEvent.previousPosition != dragEvent.position) {
            return dragEvent
        }
    }
}
