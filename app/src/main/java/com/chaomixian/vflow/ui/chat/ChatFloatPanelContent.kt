package com.chaomixian.vflow.ui.chat

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaomixian.vflow.ui.common.VFlowTheme

/** 折叠条内容高度（与 Service 的 COLLAPSED_HEIGHT_DP 保持一致）。 */
private val COLLAPSED_BAR_HEIGHT = 50.dp

/**
 * Chat 悬浮窗的内容 UI。
 *
 * **实现约束（P0 实测教训，见设计文档 §9.1）**：
 *  - 拖动/点击/长按**全部在 Compose 内处理**。P0 曾用原生 `TextView` 做拖动把手，
 *    因 `FrameLayout.addView` 默认 `MATCH_PARENT` 而铺满窗口、吞掉全部触摸。
 *  - 本组件内**不得**使用 `rememberLauncherForActivityResult`（Service 无
 *    `ActivityResultRegistryOwner`），也不得调用 `viewModel()`（无 `ViewModelStoreOwner`）。
 */
@Composable
fun ChatFloatPanelContent(
    state: ChatFloatPanelUiState,
    onDrag: (dx: Float, dy: Float) -> Unit,
    onDragEnd: () -> Unit,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 长按关闭：推迟到下一帧执行，避开 combinedClickable 手势协程收尾时读
    // CompositionLocal 引发的 "Modifier node is not currently attached" 崩溃。
    val closeHandler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val onLongClickClose: () -> Unit = remember(onClose) {
        { closeHandler.post { onClose() } }
    }
    VFlowTheme {
        Surface(
            modifier = modifier.fillMaxSize(),
            // 半透明：能透出下层的被操作界面（设计目标是「不遮挡」而非「实心面板」）
            color = Color(0xCCFCFAFF),
            shape = RoundedCornerShape(if (state.expanded) 22.dp else 24.dp),
            // ⚠️ 不要用 shadowElevation：overlay 窗口上会把阴影画成**矩形边缘**，
            //    圆角外侧露出方形阴影。改用描边区分层次（视觉上也更干净）。
            border = androidx.compose.foundation.BorderStroke(
                width = 1.dp,
                color = Color(0x66794FA8),
            ),
        ) {
            if (state.expanded) {
                // 展开态内容在 P2 实现，这里先占位，保证切换不崩
                ExpandedPlaceholder(state = state, onCollapse = onCollapse, onClose = onClose)
            } else {
                CollapsedBar(
                    state = state,
                    onDrag = onDrag,
                    onDragEnd = onDragEnd,
                    onExpand = onExpand,
                    onClose = onClose,
                    onLongClickClose = onLongClickClose,
                )
            }
        }
    }
}

/** 折叠态：状态点 + 一行 AI 文本 + 展开按钮。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CollapsedBar(
    state: ChatFloatPanelUiState,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onLongClickClose: () -> Unit,
) {
    // 折叠条贴住**锚定边**：锚定底边时贴窗口底部。
    // 因为窗口底边在展开/折叠过程中保持不动，贴在底部的折叠条在动画全程位置恒定，
    // 不会出现「先飘到上面再跳回来」。
    val outerAlign = if (state.anchoredAtBottom) Alignment.BottomCenter else Alignment.TopCenter
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = outerAlign,
    ) {
        CollapsedBarRow(
            state = state,
            onDrag = onDrag,
            onDragEnd = onDragEnd,
            onExpand = onExpand,
            onClose = onClose,
            onLongClickClose = onLongClickClose,
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun CollapsedBarRow(
    state: ChatFloatPanelUiState,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    onLongClickClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(COLLAPSED_BAR_HEIGHT)
            // 拖动：整条可拖（P0 教训——不要叠原生 View，在 Compose 内处理）
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragEnd = { onDragEnd() },
                    onDragCancel = { onDragEnd() },
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount.x, dragAmount.y)
                }
            }
            // 点击展开；长按关闭。
            // ⚠️ 这里**不直接**调 onClose()：长按时 Compose 的手势协程仍在运行，
            //    它随后要读触感反馈的 CompositionLocal；而 onClose 会销毁 composition，
            //    导致 nextValue 抛 "Modifier node is not currently attached"（实测崩溃）。
            //    改为投递到主线程 Handler，让手势协程先结束、再执行关闭。
            .combinedClickable(
                onClick = { onExpand() },
                onLongClick = { onLongClickClose() },
            )
            .padding(start = 14.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(state.summary.tone)

        Text(
            text = state.summary.text,
            fontSize = 13.5.sp,
            color = Color(0xFF221F28),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )

        if (state.hasPendingApproval) {
            Text(
                text = state.approvalBadgeText,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF8A4B00),
                modifier = Modifier
                    .clip(RoundedCornerShape(7.dp))
                    .background(Color(0xFFFFE9CC))
                    .padding(horizontal = 7.dp, vertical = 3.dp),
            )
        }

        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (state.expanded) "⌄" else "⌃",
                fontSize = 15.sp,
                color = Color(0xFF6750A4),
            )
        }
    }
}

/** 状态指示点：运行中呼吸；待审批转橙；错误红。 */
@Composable
private fun StatusDot(tone: ChatFloatSummary.Tone) {
    val target = when (tone) {
        ChatFloatSummary.Tone.NORMAL -> Color(0xFF6750A4)
        ChatFloatSummary.Tone.RUNNING -> Color(0xFF6750A4)
        ChatFloatSummary.Tone.ATTENTION -> Color(0xFFC77700)
        ChatFloatSummary.Tone.ERROR -> Color(0xFFB3261E)
    }
    val color by animateColorAsState(targetValue = target, label = "statusDotColor")

    val alpha = if (tone == ChatFloatSummary.Tone.RUNNING) {
        val transition = rememberInfiniteTransition(label = "dotPulse")
        val animated by transition.animateFloat(
            initialValue = 1f,
            targetValue = 0.35f,
            animationSpec = infiniteRepeatable(
                animation = tween(durationMillis = 700),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "dotAlpha",
        )
        animated
    } else {
        1f
    }

    Box(
        modifier = Modifier
            .size(9.dp)
            .alpha(alpha)
            .clip(CircleShape)
            .background(color)
    )
}

/**
 * 展开态占位（P2 实现完整对话面板）。
 *
 * **标题栏贴近窄条**：
 *  - 向上展开（窄条贴屏幕下方）→ 标题栏放面板**底部**（用户视线所在位置不动）
 *  - 向下展开（窄条贴屏幕上方）→ 标题栏放面板**顶部**
 *
 * 内容区始终朝另一侧生长。这样展开瞬间，用户正在看的那一块（标题栏/按钮）
 * 位置不变，只有内容朝屏幕内侧延伸。
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ExpandedPlaceholder(
    state: ChatFloatPanelUiState,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    val columnModifier = Modifier
        .fillMaxSize()
        .padding(14.dp)

    if (state.anchoredAtBottom) {
        // 向上展开：标题栏贴底（贴近原窄条），内容在上方
        androidx.compose.foundation.layout.Column(
            modifier = columnModifier,
            verticalArrangement = Arrangement.spacedBy(10.dp, Alignment.Bottom),
        ) {
            ExpandedBody(state)
            ExpandedHeader(state, onCollapse, onClose)
        }
    } else {
        androidx.compose.foundation.layout.Column(
            modifier = columnModifier,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ExpandedHeader(state, onCollapse, onClose)
            ExpandedBody(state)
        }
    }
}

/** 标题栏：状态点 + 标题 + 收起 / 关闭。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun ExpandedHeader(
    state: ChatFloatPanelUiState,
    onCollapse: () -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StatusDot(state.summary.tone)
        Text(
            text = state.headerTitle,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .weight(1f)
                .padding(start = 10.dp),
        )
        Text(
            text = "收起",
            fontSize = 12.sp,
            color = Color(0xFF6750A4),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onCollapse, onLongClick = onCollapse)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            text = "关闭",
            fontSize = 12.sp,
            color = Color(0xFFB3261E),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(onClick = onClose, onLongClick = onClose)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
    }
}

/** 内容区占位（P2 换成消息列表 + 输入框）。 */
@Composable
private fun ExpandedBody(state: ChatFloatPanelUiState) {
    androidx.compose.foundation.layout.Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = state.summary.text,
            fontSize = 13.sp,
            color = Color(0xFF2A2731),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = state.expandedHint,
            fontSize = 11.sp,
            color = Color(0xFF8A8695),
        )
    }
}

/**
 * 悬浮窗 UI 状态（与 `ChatViewModel.uiState` 解耦，便于预览与测试）。
 */
data class ChatFloatPanelUiState(
    val expanded: Boolean,
    val summary: ChatFloatSummary.Content,
    val hasPendingApproval: Boolean,
    val headerTitle: String,
    val approvalBadgeText: String,
    val expandedHint: String,
    /**
     * 是否**向上展开**（窄条贴在屏幕下半部分时为 true）。
     *
     * 由它决定折叠条贴窗口的哪条边：向上展开时贴底边 —— 因为窗口底边在展开/折叠
     * 过程中保持不动，贴在底边的折叠条位置恒定，收起时不会飘到别处。
     */
    val anchoredAtBottom: Boolean = false,
)
