package com.chaomixian.vflow.ui.overlay

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.utils.VirtualDisplayManager

// fork：思考流在**单栏**布局下的可视高度。
// 原来它与预览各占 weight(1f)（半屏），用户反馈"底部文字占的高度太高，两三行就够"。
// 约 3 行 bodySmall（约 16dp/行）加行距，仍可滚动、仍自动跟到最新。
// **双栏**布局下不受此限：右侧整列高度都给它（见 MONITOR_TWO_PANE_MIN_WIDTH）。
private val THOUGHT_STREAM_HEIGHT = 72.dp

// fork：切到**双栏**布局的最小可用宽度。
//
// 背景：折叠屏**展开态内屏实测 871dp x 982dp**（约两块手机屏并排），此时"左侧虚拟屏画面、
// 右侧思考过程"是最自然的用法；普通手机（328 到 448dp）与折叠态外屏（约 415dp）维持单栏。
//
// 刻意**不**复用 MainComposeShell 的 840dp：那是"是否显示侧边导航栏"的判据（还要求宽大于高），
// ChatScreen 曾因为借它当断点、恰好差 1dp 落回窄屏分支而改动完全不生效（FORK.md 记过）。
// 这里的语义是"宽度够不够并排放两块内容"，阈值按内容需要单独定。
private val MONITOR_TWO_PANE_MIN_WIDTH = 600.dp

/**
 * fork：AI 运行监视器的内容。
 *
 * 三块：**虚拟屏实时画面** / **模型输出的思考流** / **运行控制**。
 * 数据来源全是进程内单例，所以这个 Composable 是纯只读视图（控制按钮除外）。
 *
 * 布局**随可用宽度自适应**：宽屏（折叠屏展开态）左右分栏，窄屏维持上下堆叠。
 */
@Composable
internal fun AgentMonitorContent() {
    val context = LocalContext.current
    val snapshot by AgentRunMonitor.state.collectAsStateWithLifecycle()

    // 当前预览帧。只在本 Activity 处于 RESUMED 期间订阅（省电；也避免长期持帧）。
    var frame by remember { mutableStateOf<Bitmap?>(null) }
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val listener: (Bitmap) -> Unit = { frame = it }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> VirtualDisplayManager.addPreviewListener(listener)
                Lifecycle.Event.ON_PAUSE -> {
                    VirtualDisplayManager.removePreviewListener(listener)
                    frame = null
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            VirtualDisplayManager.removePreviewListener(listener)
            frame = null
        }
    }

    if (snapshot.phase == AgentRunMonitor.Phase.IDLE) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = context.getString(R.string.agent_monitor_empty),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        if (maxWidth >= MONITOR_TWO_PANE_MIN_WIDTH) {
            // 双栏：左画面 / 右思考（宽屏，如折叠屏展开态）
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                MonitorPreview(frame = frame, modifier = Modifier.weight(1f).fillMaxHeight())
                Column(
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    MonitorHeader(snapshot = snapshot)
                    MonitorThoughtStream(
                        snapshot = snapshot,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                    )
                    MonitorControls(snapshot = snapshot)
                }
            }
        } else {
            // 单栏：上画面 / 下思考（普通手机、折叠屏外屏）
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                MonitorHeader(snapshot = snapshot)
                MonitorPreview(frame = frame, modifier = Modifier.fillMaxWidth().weight(1f))
                MonitorThoughtStream(
                    snapshot = snapshot,
                    modifier = Modifier.fillMaxWidth().height(THOUGHT_STREAM_HEIGHT),
                )
                MonitorControls(snapshot = snapshot)
            }
        }
    }
}

/** 状态行：模块名 + 步数 + 「阶段状态 · 当前动作」。 */
@Composable
private fun MonitorHeader(snapshot: AgentRunMonitor.Snapshot) {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = snapshot.moduleLabel.ifBlank { context.getString(R.string.agent_monitor_title) },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (snapshot.maxSteps > 0) {
            Spacer(Modifier.width(8.dp))
            Text(
                text = "${snapshot.step}/${snapshot.maxSteps}",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.weight(1f))
        // fork：**阶段状态**（模块写死的"分析屏幕…"）与**当前动作**合成一行，只做"现在在干嘛"的指示。
        // 它们**不进思考流**（见 AgentRunMonitor.Snapshot.statusText）：思考流里只放模型输出的文字。
        val headerStatus = listOfNotNull(snapshot.statusText, snapshot.currentAction)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .joinToString(" · ")
        if (headerStatus.isNotEmpty()) {
            Text(
                text = headerStatus,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 虚拟屏实时画面（modifier 由调用方决定占位方式）。 */
@Composable
private fun MonitorPreview(frame: Bitmap?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center,
    ) {
        val current = frame
        if (current != null && !current.isRecycled) {
            Image(
                bitmap = current.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Fit,
            )
        } else {
            Text(
                text = context.getString(R.string.agent_monitor_no_preview),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 思考流：只装**模型输出的文字**，自动跟到最新，但**礼让用户**。
 *
 * 跟滚策略（真机反馈两轮才定下来）：
 *  - **默认跟到最新**；
 *  - 用户在流上**碰一下**（点或滑）就**停止跟滚**，好让他往上翻看历史；
 *  - 滚动停下来时若已回到最底，**自动恢复跟滚**。
 *
 * 注意**不要**改用"追加前一刻是否贴着底部"来判断：LaunchedEffect 跑的时候 layoutInfo
 * 可能**已经是新布局**（新条目落在视口外），那个判断会恒为 false、永远不跟滚。
 * 这正是上一版"看起来没有自动滚动机制"的原因。
 */
@Composable
private fun MonitorThoughtStream(snapshot: AgentRunMonitor.Snapshot, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    var autoFollow by remember { mutableStateOf(true) }
    var programmaticScroll by remember { mutableStateOf(false) }

    // 滚动停下来时判断：停在底部就恢复跟滚，否则保持"不打扰"
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (!scrolling && !programmaticScroll) {
                val info = listState.layoutInfo
                val last = info.visibleItemsInfo.lastOrNull()?.index ?: -1
                autoFollow = last >= info.totalItemsCount - 1
            }
        }
    }

    LaunchedEffect(snapshot.entries.size) {
        if (autoFollow && snapshot.entries.isNotEmpty()) {
            programmaticScroll = true
            listState.animateScrollToItem(snapshot.entries.lastIndex)
            programmaticScroll = false
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            // 用户一碰就停止跟滚（Initial 阶段先于 LazyColumn 自己的滚动处理）
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        awaitPointerEvent(PointerEventPass.Initial)
                        autoFollow = false
                    }
                }
            },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(snapshot.entries) { entry ->
            Text(
                text = entry.text,
                style = MaterialTheme.typography.bodySmall,
                color = if (entry.kind == AgentRunMonitor.EntryKind.ERROR) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurface
                },
            )
        }
    }
}

/** 运行控制：暂停/继续 + 两段确认的结束。 */
@Composable
private fun MonitorControls(snapshot: AgentRunMonitor.Snapshot) {
    val context = LocalContext.current
    // 只在**真的能控制**时显示：ENDED 之后主循环已经退出，按钮点了不会有任何反应
    // （那是"点了没反应"的另一半来源）。
    val controllable = snapshot.phase == AgentRunMonitor.Phase.RUNNING ||
        snapshot.phase == AgentRunMonitor.Phase.PAUSED
    if (!controllable) return

    var confirmStop by remember { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(
            onClick = { AgentRunMonitor.togglePause() },
            enabled = !snapshot.cancelling,
        ) {
            Text(
                text = context.getString(
                    if (snapshot.phase == AgentRunMonitor.Phase.PAUSED) {
                        R.string.agent_monitor_resume
                    } else {
                        R.string.agent_monitor_pause
                    }
                )
            )
        }
        OutlinedButton(
            onClick = {
                // 两段确认：误触一次不会杀掉一个跑了很久的任务
                if (confirmStop) {
                    AgentRunMonitor.requestCancel()
                } else {
                    confirmStop = true
                }
            },
            // 取消已受理后按钮置灰：给用户"点到了"的确定反馈。
            // 主循环要等下一次 awaitState() 才真的退出（可能还在一次几十秒的 LLM 调用里）。
            enabled = !snapshot.cancelling,
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = if (confirmStop || snapshot.cancelling) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.primary
                },
            ),
        ) {
            Text(
                text = context.getString(
                    when {
                        snapshot.cancelling -> R.string.agent_monitor_stopping
                        confirmStop -> R.string.agent_monitor_confirm
                        else -> R.string.agent_monitor_stop
                    }
                )
            )
        }
    }
}
