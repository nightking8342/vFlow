package com.chaomixian.vflow.ui.settings

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.locale.LocaleManager
import com.chaomixian.vflow.core.logcat.*
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.services.LogcatCaptureController
import com.chaomixian.vflow.services.LogcatExportManager
import com.chaomixian.vflow.services.ShellManager
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.chaomixian.vflow.ui.common.VFlowTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * logcat 查看器。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §4.1。
 *
 * 形态照 [KeyTesterActivity]（`ComponentActivity` + Compose，无 ViewModel）。
 *
 * ## 界面与逻辑的分工
 *
 * 本文件只负责**渲染与事件接线**。所有可判断的逻辑都下沉到了纯函数层，
 * 因为那些逻辑几乎都是"写错了不报错、只是行为悄悄变差"的类型：
 *
 * | 逻辑 | 位置 | 测试 |
 * |---|---|---|
 * | 刷新读哪个数据源、按钮何时禁用 | `buildViewerActions` / `buildRefresh` | ✅ |
 * | 过滤、空结果归因、时间范围 | `applyLogcatFilter` / `diagnoseEmpty` | ✅ |
 * | 导出内容长什么样 | `LogcatExportRenderer` | ✅ |
 * | 状态机、计时、岛 | `LogcatCaptureController` | ✅ |
 *
 * 因此这个文件里**不应该出现需要单测才能确认的判断**。
 */
class LogcatViewerActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getLanguage(newBase)
        val localizedContext = LocaleManager.applyLanguage(newBase, languageCode)
        super.attachBaseContext(AppearanceManager.applyDisplayScale(localizedContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            VFlowTheme {
                LogcatViewerScreen(onBack = { finish() })
            }
        }
    }
}

private const val TAG = "LogcatViewer"

/** 可选的条数（上界，非精确值——§4.4）。 */
private val LINE_LIMIT_CHOICES = listOf(200, 500, 1000, 2000)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogcatViewerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ---- 采集状态（来自进程级控制器）----
    val session by LogcatCaptureController.session.collectAsState()
    val nowMs by LogcatCaptureController.nowMs.collectAsState()
    val controllerMessage by LogcatCaptureController.message.collectAsState()

    // ---- 界面本地状态 ----
    var filter by remember { mutableStateOf(LogcatFilter()) }
    var refreshing by remember { mutableStateOf(false) }
    var shellReady by remember { mutableStateOf(false) }
    var tagStats by remember { mutableStateOf<List<Pair<String, Int>>?>(null) }
    var exportMenuOpen by remember { mutableStateOf(false) }
    var wrapLines by remember { mutableStateOf(false) }

    /**
     * **未经过滤**的原始行，只由「读数据源」更新。
     *
     * ⚠️ 这是「改过滤条件不重跑命令」的关键：数据一旦读进内存，
     * 改 TAG / 消息 / 级别只是**重新筛一遍内存里的列表**，不再碰 shell。
     *
     * 原先的实现在每次过滤条件变化时都调 `refresh()` 重读数据源，
     * 后果有两个（都是用户报的）：
     * 1. 采集态下重读没问题（文件不变），但**已完成态会被判成空闲**，
     *    于是重读的是实时滚动的缓冲区 —— 用户看到的不再是自己采的那批
     * 2. 每次切 TAG 都要等一次跨进程命令，白等
     */
    var rawLines by remember { mutableStateOf<List<LogcatLine>>(emptyList()) }
    var capturedState by remember { mutableStateOf<CaptureState>(CaptureState.Idle) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var shellOk by remember { mutableStateOf(true) }

    // 过滤结果由「原始行 + 当前条件」派生，**不是独立状态** ——
    // 这样改条件必然触发重算，不可能出现两者不一致
    val result = remember(rawLines, filter, capturedState, elapsedMs, shellOk) {
        buildViewerResult(
            raw = rawLines,
            filter = filter,
            state = capturedState,
            ownPids = ownPidsOf(context),
            elapsedMs = elapsedMs,
            shellAvailable = shellOk,
        )
    }

    // ⚠️ 进入界面必须探测真实状态（§4.2.1 调用时机表）：
    // 采集脱离 UI 存活，用户上次离开时可能正在采，也可能 App 被杀后转入了 STALE。
    // 内存状态在这里不可信。
    LaunchedEffect(Unit) {
        shellReady = checkShellReady(context)
        if (shellReady) {
            LogcatCaptureController.probe(context)
        }
    }

    // 控制器的一次性提示（错误、到点停止等）转成 Toast 后清掉
    LaunchedEffect(controllerMessage) {
        controllerMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            LogcatCaptureController.clearMessage()
        }
    }

    val actions = buildViewerActions(session.state, shellReady, refreshing)

    /**
     * 读一次数据源（**唯一会跑 shell 命令的入口**）。
     *
     * 过滤条件传空 —— 读回来的是原始数据，筛选交给内存里的 [result] 派生。
     * 这样"读"与"筛"彻底分开：读一次可以用很多次。
     */
    fun loadFromSource() {
        refreshing = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                loadLogs(context, session.state, LogcatFilter())
            }
            rawLines = outcome.rawLines
            capturedState = session.state
            elapsedMs = outcome.elapsedMs
            shellOk = outcome.shellOk
            refreshing = false
            if (outcome.timedOut) {
                Toast.makeText(
                    context,
                    context.getString(R.string.logcat_empty_timeout),
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }

    /**
     * 采集状态变化时自动读一次数据源（用户报的问题 2）。
     *
     * 覆盖两个时刻：
     * - **刚进入「采集中」**：让用户立刻看到已经有日志进来，而不是空屏等着
     * - **刚进入「已完成」**：采完自动展示，不必再手动点刷新
     *
     * ⚠️ 只在**状态本身变化**时触发，不随过滤条件变化触发 ——
     * 后者只重筛内存，不重读数据源（用户报的问题 1）。
     * 这正是把 `key` 设成 state 而不是 filter 的原因。
     */
    LaunchedEffect(session.state) {
        val st = session.state
        if (st is CaptureState.Completed || st is CaptureState.Capturing) {
            loadFromSource()
        }
    }



    /**
     * 采集状态变化时自动读一次数据源（用户报的问题 2）。
     *
     * 覆盖两个时刻：
     * - **刚进入「采集中」**：让用户立刻看到已经有日志进来，而不是空屏等着
     * - **刚进入「已完成」**：采完自动展示，不必再手动点刷新
     *
     * ⚠️ 只在**状态本身变化**时触发，不随过滤条件变化触发 ——
     * 后者只重筛内存，不重读数据源（用户报的问题 1）。
     * 这正是把 key 设成 state 而不是 filter 的原因。
     */
    LaunchedEffect(session.state) {
        val st = session.state
        if (st is CaptureState.Completed || st is CaptureState.Capturing) {
            loadFromSource()
        }
    }

    /** TAG 统计：**绕过 TAG 过滤**（§4.5），但保留级别。 */
    fun runTagStats() {
        if (!actions.canRefresh) return
        refreshing = true
        scope.launch {
            val stats = withContext(Dispatchers.IO) {
                LogcatCommands.buildTagStats(
                    state = session.state,
                    lines = filter.lineLimit,
                    minLevel = filter.minLevel,
                )?.let { cmd ->
                    val text = ShellManager.execShellCommand(context, cmd)
                    LogcatParser.countByTag(LogcatParser.parseLines(text))
                }
            }
            tagStats = stats ?: emptyList()
            refreshing = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.logcat_viewer_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { exportMenuOpen = true },
                        enabled = actions.canExport(result.lines.isNotEmpty()),
                    ) {
                        Icon(Icons.Default.Share, contentDescription = stringResource(R.string.logcat_viewer_export))
                    }
                    DropdownMenu(
                        expanded = exportMenuOpen,
                        onDismissRequest = { exportMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logcat_viewer_export_text)) },
                            onClick = {
                                exportMenuOpen = false
                                doExport(context, result, filter, session.state, LogcatExportManager.Format.TEXT)
                            },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.logcat_viewer_export_json)) },
                            onClick = {
                                exportMenuOpen = false
                                doExport(context, result, filter, session.state, LogcatExportManager.Format.JSON)
                            },
                        )
                    }
                },
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {

            ShellStatusBar(shellReady)

            CaptureSection(
                session = session,
                nowMs = nowMs,
                actions = actions,
                timeoutSec = LogcatCaptureController.timeoutSec,
                onTimeoutChange = { LogcatCaptureController.timeoutSec = it },
                onStart = { scope.launch { LogcatCaptureController.start(context) } },
                onStop = { scope.launch { LogcatCaptureController.stop(context) } },
                onClearStale = { scope.launch { LogcatCaptureController.clearStale(context) } },
                onRelease = {
                    scope.launch {
                        LogcatCaptureController.releaseCapture(context)
                        // 数据源切回缓冲区，内存里的这批也就不再对应任何东西
                        rawLines = emptyList()
                    }
                },
            )

            FilterSection(
                filter = filter,
                enabled = shellReady && !refreshing,
                actions = actions,
                onFilterChange = { filter = it },
                onRefresh = { loadFromSource() },
                onTagStats = { runTagStats() },
                onClearList = {
                    // 「清空」是清掉当前展示的内容。rawLines 是唯一的数据源，
                    // 清它即可（result 由它派生）
                    rawLines = emptyList()
                },
                wrapLines = wrapLines,
                onToggleWrap = { wrapLines = !wrapLines },
            )

            HorizontalDivider()

            LogcatList(
                lines = result.lines,
                emptyReason = result.emptyReason,
                rawLineCount = result.rawLineCount,
                refreshing = refreshing,
                wrapLines = wrapLines,
                modifier = Modifier.weight(1f),
            )

            if (rawLines.isNotEmpty() || result.emptyReason != null) {
                StatusBar(
                    result = result,
                    state = session.state,
                )
            }
        }
    }

    tagStats?.let { stats ->
        TagStatsSheet(
            stats = stats,
            onDismiss = { tagStats = null },
            onPick = { tag ->
                // ⚠️ 只改条件，**不重新读数据源** —— 数据已在内存里，
                // 改 TAG 只是重新筛一遍。原先这里调 refresh() 会重跑一次
                // shell 命令，而且在「已完成」态下会读到实时缓冲区（用户报的问题 1）
                filter = filter.copy(tagQuery = tag)
                tagStats = null
            },
        )
    }
}

// ── 数据加载 ────────────────────────────────────────────────────

/**
 * 读数据源的结果。
 *
 * ⚠️ 返回的是**未过滤**的原始行 —— 过滤交给调用方在内存里做，
 * 这样"读一次、筛多次"，改条件不必重跑命令。
 */
private class LoadOutcome(
    val rawLines: List<LogcatLine>,
    val elapsedMs: Long,
    val shellOk: Boolean,
    val timedOut: Boolean,
)

/**
 * 读一次日志。
 *
 * 「刷新」读哪里**由状态决定，不暴露给用户**（§4.1.1）——
 * 这正是把「抓取一次」与「刷新」合并成一个按钮的原因。
 */
private suspend fun loadLogs(
    context: Context,
    state: CaptureState,
    filter: LogcatFilter,
): LoadOutcome {
    // STALE 不执行命令，走提示（buildRefresh 返回 null 就是表达这个）
    val cmd = LogcatCommands.buildRefresh(state, filter.lineLimit, filter.tagQuery, filter.minLevel)
        ?: return LoadOutcome(emptyList(), 0, shellOk = true, timedOut = false)

    val startedAt = System.currentTimeMillis()
    val output = runCatching { ShellManager.execShellCommand(context, cmd) }
        .onFailure { DebugLogger.w(TAG, "读取日志失败", it) }
        .getOrDefault("")
    val elapsed = System.currentTimeMillis() - startedAt

    // Shell 层失败会以 "Error:" 前缀返回（见 ShellManager.executeShizukuCommand）
    val failed = output.startsWith("Error:")

    return LoadOutcome(
        rawLines = LogcatParser.parseLines(output),
        elapsedMs = elapsed,
        shellOk = !failed,
        timedOut = false,
    )
}

private fun doExport(
    context: Context,
    result: LogcatViewerResult?,
    filter: LogcatFilter,
    state: CaptureState,
    format: LogcatExportManager.Format,
) {
    val lines = result?.lines.orEmpty()
    if (lines.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.logcat_toast_nothing_to_export), Toast.LENGTH_SHORT).show()
        return
    }
    val intent = LogcatExportManager.buildShareIntent(context, lines, filter, state, format)
    if (intent == null) {
        Toast.makeText(context, context.getString(R.string.logcat_toast_export_failed), Toast.LENGTH_LONG).show()
        return
    }
    Toast.makeText(
        context,
        context.getString(R.string.logcat_toast_exported, lines.size),
        Toast.LENGTH_SHORT,
    ).show()
    context.startActivity(intent)
}

/** Shell 是否可用。不可用时整页操作置灰（§4.1）。 */
private fun checkShellReady(context: Context): Boolean =
    runCatching {
        ShellManager.isShizukuActive(context) || ShellManager.isRootAvailable()
    }.getOrDefault(false)

/**
 * 本应用的进程号，用于「显示本应用日志」开关。
 *
 * ⚠️ 只取**主进程**的 pid。`:core` 等子进程的 pid 要跨进程问，成本高于收益——
 * 用户关心的「我自己的日志刷屏」几乎都来自主进程。
 * 拿不到时返回空集，此时开关退化为"不隐藏任何日志"，不会误伤。
 */
private fun ownPidsOf(context: Context): Set<Int> =
    runCatching { setOf(android.os.Process.myPid()) }.getOrDefault(emptySet())

// ── 界面组件 ────────────────────────────────────────────────────

@Composable
private fun ShellStatusBar(shellReady: Boolean) {
    Surface(
        color = if (shellReady) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            MaterialTheme.colorScheme.errorContainer
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            text = stringResource(
                if (shellReady) R.string.logcat_shell_ready else R.string.logcat_shell_not_ready
            ),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun CaptureSection(
    session: CaptureSession,
    nowMs: Long,
    actions: LogcatViewerActions,
    timeoutSec: Int,
    onTimeoutChange: (Int) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onClearStale: () -> Unit,
    onRelease: () -> Unit,
) {
    var timeoutMenuOpen by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logcat_section_capture),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.weight(1f),
            )

            // 正计时（§4.7）：显示"我采了多久"，不是"还剩多久"。
            // 起点未知时（App 重启后 pidfile 里没有开始时间）不显示假的 00:00。
            val elapsed = session.elapsedMs(nowMs)
            if (elapsed != null) {
                Text(
                    text = LogcatCaptureUi.formatElapsed(elapsed),
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(12.dp))
            }

            if (actions.capturing) {
                Button(onClick = onStop, enabled = actions.canStop) {
                    Text(stringResource(R.string.logcat_action_stop))
                }
            } else {
                Button(onClick = onStart, enabled = actions.canStart) {
                    Text(
                        stringResource(
                            // 已完成态下「开始」的语义是"再采一批"，
                            // 文案要跟上，否则用户以为点了会丢掉现在这批
                            if (actions.completed) R.string.logcat_action_recapture
                            else R.string.logcat_action_start
                        )
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))
        Text(
            text = stringResource(stateLabel(session.state)),
            style = MaterialTheme.typography.bodySmall,
            color = if (actions.stale) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logcat_timeout_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            val label = LogcatCaptureUi.formatDuration(timeoutSec)
            TextButton(onClick = { timeoutMenuOpen = true }, enabled = !actions.capturing) {
                Text(label)
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = timeoutMenuOpen, onDismissRequest = { timeoutMenuOpen = false }) {
                LogcatCommands.TIMEOUT_CHOICES_SEC.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(LogcatCaptureUi.formatDuration(choice)) },
                        onClick = {
                            onTimeoutChange(choice)
                            timeoutMenuOpen = false
                        },
                    )
                }
            }
        }

        // 已完成态：给一个"放弃这批"的出口。
        // 没有它的话用户只能靠"重新采集"来覆盖，而如果只是想回到
        // 实时缓冲区看看，重新采集是多此一举
        if (actions.completed) {
            Text(
                text = stringResource(R.string.logcat_completed_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onRelease, enabled = !actions.refreshing) {
                Text(stringResource(R.string.logcat_action_release))
            }
        }

        // STALE 态：提示 + 手动清理（§10 决策 2，不自动删——
        // 用户可能想先看看那次异常结束前采集到的日志）
        if (actions.stale) {
            Text(
                text = stringResource(R.string.logcat_empty_stale),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = onClearStale, enabled = actions.canClear) {
                Text(stringResource(R.string.logcat_action_clear))
            }
        }
    }
}

@Composable
private fun FilterSection(
    filter: LogcatFilter,
    enabled: Boolean,
    actions: LogcatViewerActions,
    onFilterChange: (LogcatFilter) -> Unit,
    onRefresh: () -> Unit,
    onTagStats: () -> Unit,
    onClearList: () -> Unit,
    wrapLines: Boolean,
    onToggleWrap: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text(
            text = stringResource(R.string.logcat_section_filter),
            style = MaterialTheme.typography.titleSmall,
        )

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logcat_level_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(52.dp),
            )
            // 级别是单选而非多选：用户的心智是"至少这么严重"，与触发器一致
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = filter.minLevel == level,
                    onClick = { onFilterChange(filter.copy(minLevel = level)) },
                    enabled = enabled,
                    label = { Text(level.char.toString(), fontFamily = FontFamily.Monospace) },
                    modifier = Modifier.padding(end = 4.dp),
                )
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logcat_tag_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(52.dp),
            )
            OutlinedTextField(
                value = filter.tagQuery,
                onValueChange = { onFilterChange(filter.copy(tagQuery = it)) },
                enabled = enabled,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.logcat_tag_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onTagStats, enabled = actions.canRefresh) {
                Text(stringResource(R.string.logcat_tag_stats))
            }
        }

        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logcat_filter_message_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(52.dp),
            )
            OutlinedTextField(
                value = filter.messageQuery,
                onValueChange = { onFilterChange(filter.copy(messageQuery = it)) },
                enabled = enabled,
                singleLine = true,
                placeholder = { Text(stringResource(R.string.logcat_filter_message_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                textStyle = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = filter.showOwnApp,
                onCheckedChange = { onFilterChange(filter.copy(showOwnApp = it)) },
                enabled = enabled,
            )
            Text(
                text = stringResource(R.string.logcat_show_own_app),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.clickable(enabled = enabled) {
                    onFilterChange(filter.copy(showOwnApp = !filter.showOwnApp))
                },
            )
        }

        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.logcat_line_limit_label),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.width(52.dp),
            )
            var limitMenuOpen by remember { mutableStateOf(false) }
            TextButton(
                onClick = { limitMenuOpen = true },
                enabled = enabled && !actions.capturing,
            ) {
                Text(filter.lineLimit.toString())
                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
            }
            DropdownMenu(expanded = limitMenuOpen, onDismissRequest = { limitMenuOpen = false }) {
                LINE_LIMIT_CHOICES.forEach { choice ->
                    DropdownMenuItem(
                        text = { Text(choice.toString()) },
                        onClick = {
                            onFilterChange(filter.copy(lineLimit = choice))
                            limitMenuOpen = false
                        },
                    )
                }
            }

            Spacer(Modifier.weight(1f))
            // ⚠️ 采集已完成 / 异常结束时**不显示刷新按钮**：
            // 数据源是已固定的采集文件，刷新只会读到同一批内容。
            // 那个按钮在那里只会让人以为"点了会变"，而且它原先真的会去
            // 重读数据源（在已完成态被判成空闲 → 读到实时缓冲区，用户报的问题 1）。
            // 要换一批日志应当重新采集，而不是刷新。
            if (!actions.sourceIsFixed) {
                Button(onClick = onRefresh, enabled = actions.canRefresh) {
                    Text(stringResource(R.string.logcat_action_refresh))
                }
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = onClearList, enabled = enabled) {
                Text(stringResource(R.string.logcat_action_clear_list))
            }
            Spacer(Modifier.width(4.dp))
            // 换行开关：只影响渲染，不碰数据，因此不受 enabled 约束
            TextButton(onClick = onToggleWrap) {
                Text(
                    text = stringResource(
                        if (wrapLines) R.string.logcat_wrap_on else R.string.logcat_wrap_off
                    ),
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun LogcatList(
    lines: List<LogcatLine>,
    emptyReason: LogcatEmptyReason?,
    rawLineCount: Int,
    refreshing: Boolean,
    wrapLines: Boolean,
    modifier: Modifier = Modifier,
) {
    if (refreshing) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator()
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.logcat_refreshing), style = MaterialTheme.typography.bodySmall)
            }
        }
        return
    }

    if (lines.isEmpty()) {
        EmptyHint(emptyReason, rawLineCount, modifier)
        return
    }

    val listState = rememberLazyListState()

    // 新日志在尾部，刷新后默认滚到底更符合"看最近发生了什么"。
    // ⚠️ LaunchedEffect 必须放在 LazyColumn **之外**——
    // LazyColumn 的内容 lambda 是 LazyItemScope，不是 Composable 上下文，
    // 在里面调用 @Composable 函数会编译报错。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    LazyColumn(state = listState, modifier = modifier.fillMaxSize()) {
        // ⚠️ **不要自定义 key**。logcat 里两行完全相同的日志极常见
        // （循环打印、重复的堆栈帧），而 `LogcatLine` 是 data class，
        // 内容相同的两行必然算出同一个 key —— LazyColumn 要求 key 唯一，
        // 撞上会抛 IllegalArgumentException 直接崩掉界面。
        //
        // 这里也确实不需要自定义 key：列表是**整体替换**语义（§4.1.2），
        // 没有移动/重排，默认的位置索引天然唯一且稳定。
        items(lines) { line ->
            LogcatRow(line, wrapLines)
        }
    }
}

@Composable
private fun LogcatRow(line: LogcatLine, wrapLines: Boolean) {
    // 降级行灰显 + ↳ 前缀：这是本工具最有价值的可视化（§4.4）——
    // 它把"这行没有 TAG 前缀，你的 message 条件可能因此失配"直接摆到用户面前
    val color = when {
        line.isContinuation -> MaterialTheme.colorScheme.onSurfaceVariant
        line.level.priority >= LogLevel.ERROR.priority -> MaterialTheme.colorScheme.error
        line.level.priority >= LogLevel.WARN.priority -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    // ⚠️ 两种模式的取舍：
    // - 不换行 + 横向滚动：保证"一行就是一行"，比对时间戳/对齐时更清楚，
    //   但长消息要横向拖（用户报的问题 4）
    // - 换行：长消息完整可见，代价是行与行的视觉对应变弱
    // 没有哪个绝对更好，所以做成开关让用户按场景选
    val scrollModifier = if (wrapLines) {
        Modifier.fillMaxWidth()
    } else {
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())
    }

    Row(
        modifier = scrollModifier.padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Text(
            // 换行模式下不设 maxLines，Text 会自行折行；
            // 不换行时靠 horizontalScroll 承载超出部分
            softWrap = wrapLines,
            text = buildString {
                if (line.isContinuation) append("↳ ")
                if (line.timestamp.isNotBlank()) append(line.timestamp).append("  ")
                if (line.pid >= 0) append(line.pid).append(" ").append(line.tid).append(" ")
                append(line.level.char).append(" ")
                if (line.tag.isNotBlank()) append(line.tag).append(": ")
                append(line.message)
            },
            fontFamily = FontFamily.Monospace,
            fontSize = 11.sp,
            color = color,
        )
    }
}

/**
 * 空结果提示。
 *
 * §4.6：各类空/异常态**必须分别给文案**，不要一律「没有日志」——
 * 「缓冲区已轮转」「采集刚开始」「被筛没了」对用户是三个完全不同的下一步。
 */
@Composable
private fun EmptyHint(reason: LogcatEmptyReason?, rawLineCount: Int, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = when (reason) {
                LogcatEmptyReason.BufferRotated -> stringResource(R.string.logcat_empty_rotated)
                LogcatEmptyReason.CaptureJustStarted -> stringResource(R.string.logcat_empty_just_started)
                LogcatEmptyReason.CaptureStale -> stringResource(R.string.logcat_empty_stale)
                LogcatEmptyReason.ShellUnavailable -> stringResource(R.string.logcat_empty_shell)
                LogcatEmptyReason.CommandTimeout -> stringResource(R.string.logcat_empty_timeout)
                LogcatEmptyReason.FilteredOut ->
                    stringResource(R.string.logcat_empty_filtered, rawLineCount)
                LogcatEmptyReason.CaptureCompletedEmpty ->
                    stringResource(R.string.logcat_empty_completed)
                null -> stringResource(R.string.logcat_empty_no_filter_hint)
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
        )
    }
}

@Composable
private fun StatusBar(result: LogcatViewerResult, state: CaptureState) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            Text(
                text = when (state) {
                    is CaptureState.Capturing -> stringResource(
                        R.string.logcat_status_capturing,
                        result.lines.size,
                        result.continuationCount,
                        result.timeRange ?: stringResource(R.string.logcat_status_no_time_range),
                        result.elapsedMs,
                    )

                    is CaptureState.Stale -> stringResource(
                        R.string.logcat_status_stale,
                        result.lines.size,
                        result.continuationCount,
                    )

                    is CaptureState.Completed -> stringResource(
                        R.string.logcat_status_completed,
                        result.lines.size,
                        result.continuationCount,
                        result.timeRange ?: stringResource(R.string.logcat_status_no_time_range),
                    )

                    is CaptureState.Idle -> stringResource(
                        R.string.logcat_status_idle,
                        result.lines.size,
                        result.continuationCount,
                        result.elapsedMs,
                    )
                },
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )

            // 降级行数是最容易让用户踩坑的地方，单独再提一次
            if (result.continuationCount > 0) {
                Spacer(Modifier.height(2.dp))
                Text(
                    text = stringResource(R.string.logcat_degraded_hint, result.continuationCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TagStatsSheet(
    stats: List<Pair<String, Int>>,
    onDismiss: () -> Unit,
    onPick: (String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = stringResource(R.string.logcat_tag_stats_title, stats.sumOf { it.second }),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.logcat_tag_stats_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            if (stats.isEmpty()) {
                Text(stringResource(R.string.logcat_tag_stats_empty))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    items(stats) { (tag, count) ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPick(tag) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = tag,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = count.toString(),
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/**
 * 状态文案。
 *
 * **不是 `@Composable`**：它在纯 `stringResource(...)` 参数位置被调用
 * （如 `Text(text = stateLabel(...))`），那里不是 composable 上下文。
 * 保持成普通函数，`stringResource` 会在调用点求值。
 */
private fun stateLabel(state: CaptureState): Int = when (state) {
    is CaptureState.Capturing -> R.string.logcat_state_capturing
    is CaptureState.Completed -> R.string.logcat_state_completed
    is CaptureState.Stale -> R.string.logcat_state_stale
    is CaptureState.Idle -> R.string.logcat_state_idle
}
