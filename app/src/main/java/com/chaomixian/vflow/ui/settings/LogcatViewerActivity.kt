package com.chaomixian.vflow.ui.settings

import android.content.Context
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
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

/**
 * 过滤条件变化后，等多久才真正去扫全文件统计命中数。
 *
 * ⚠️ **不能去掉这个防抖**：TAG / 消息输入框是逐字符触发的，
 * 每敲一个字就扫一次全文件（几秒～几十秒）会让界面完全不可用。
 *
 * 300ms 的依据：正常打字间隔约 100–200ms，停手 300ms 才算"输入完了"。
 * 太短（<150ms）会让连续输入仍然触发多次；太长（>800ms）用户会觉得卡。
 */
private const val FILTER_COUNT_DEBOUNCE_MS = 300L

/**
 * TAG 行右侧「TAG 统计」按钮的近似宽度。
 *
 * 消息行用它做等宽占位，让两个输入框的右边缘对齐（视觉上成一组）。
 */
private val MESSAGE_ROW_ALIGN_WIDTH = 96.dp

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
     * 查找栏是否展开。
     *
     * 默认**隐藏**，点标题栏的搜索图标才出现 —— 查找是偶发操作，
     * 却要常年占掉一行高度（约 56dp）。
     *
     * ⚠️ 折叠过滤区那条路走过一次，**效果不好**（用户反馈"日志区更小了"）：
     * 折叠后条件摘要那行还在，省下的高度有限，却让常用条件多了一次点击。
     * 所以过滤区改为**常驻**，靠整页滚动来让日志区拿到足够高度。
     */
    var searchBarVisible by remember { mutableStateOf(false) }

    /** 查找条件（只标出命中，**不改动结果集**，见 LogcatSearch 的说明）。 */
    var search by remember { mutableStateOf(LogcatSearch()) }
    var searchIndex by remember { mutableStateOf(0) }

    /** 日志列表的滚动状态。查找跳转要用它滚到目标行。 */
    val listState = rememberLazyListState()
    val scopeForScroll = rememberCoroutineScope()

    /**
     * 上方信息区的**完整内容高度**（不受折叠影响），由该区自己测量后回报。
     *
     * ⚠️ 必须是独立测量的常量。若从"当前可见高度"反推，会把被裁掉的部分
     * 当成不存在 —— 于是每帧都丢一点，手指还按着就把上方区悄悄收光了。
     */
    var upperContentHeightPx by remember { mutableStateOf(0) }

    /**
     * A 行的高度，由 [ActionRow] 自己测量后回报。
     *
     * ⚠️ 它是**折叠上界的算料**：上界 = 内容总高 − A行高，
     * 于是"收尽"等价于"A 行正好贴住标题栏"。不测它就只能按内容总高收，
     * 那样会把 A 行也一并收走（屏幕上只剩日志区，没有任何操作入口）。
     */
    var actionRowHeightPx by remember { mutableStateOf(0) }

    /** 上方带已收起的像素数。`0` = 完全展开。 */
    var upperCollapsedPx by remember { mutableStateOf(0f) }


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

    /** 正在往前翻页（区别于普通刷新，用于按钮态与文案）。 */
    var loadingEarlier by remember { mutableStateOf(false) }

    /**
     * **整个采集文件**的全量统计（总行数 / 覆盖时间 / TAG 分布）。
     *
     * ⚠️ 这是"我看全了吗"的参照物 —— 与 [rawLines]（本次读取）不是一回事。
     *
     * 只在**采集结束后**算一次：采集中文件在变，算出来的数字立刻过期，
     * 而且每次都要扫 256MB。用户也明确说了「采集中不用看」。
     */
    var captureStats by remember { mutableStateOf<CaptureStats?>(null) }

    /**
     * **当前过滤条件在整个采集文件里命中多少行**。
     *
     * ## 为什么需要它（用户报的问题）
     *
     * 原先改级别 / TAG / 消息**只筛内存里那批行**（最多 `条数` 行），
     * 而页码按**未过滤**的总量算 —— 两者口径不同，页码与内容对不上。
     * 用户的原话：「应该是全部数据的过滤，触发筛选后页数也应该重新计算」。
     *
     * 现在改条件后会**扫全文件**得到真实命中数，页数由它派生。
     *
     * ⚠️ `null` 表示**还不知道**（未扫完 / 扫描失败）——
     * 与 `0`（确认为零命中）语义完全不同：前者页码退回 1 页，
     * 后者要提示"放宽条件"。不要把它们混为一谈。
     */
    var filteredCount by remember { mutableStateOf<Int?>(null) }

    /** 是否正在跑全量命中统计（界面据此显示"统计中"）。 */
    var countingMatches by remember { mutableStateOf(false) }

    /** 是否正在统计全量（用于状态栏显示"统计中"）。 */
    var statsLoading by remember { mutableStateOf(false) }

    /**
     * 页码选择。
     *
     * 页码是**显式跳转**：直接读第 N 窗，不需要先读遍前 N-1 页。
     * 底层参数就是 `skipBytes = (page - 1) × 每页条数 × 平均行长`。
     */
    var pageIndex by remember { mutableStateOf(1) }

    /**
     * 翻页游标：当前这一页的起始字节偏移（从文件末尾往回数）。
     *
     * ## 为什么需要它
     *
     * 原先翻页靠 `skipBytesForPage(page, 条数)` 现算 —— 而那是个**估算**
     * （每页条数 × 200 字节）。真实行长不等于 200 时，步进与实际内容错位：
     * 行长 141 时每翻一页**漏掉约 42% 的行**，而且不报错。
     *
     * 改成一页一页地推进「本页实际内容占用的字节数」后，永远不漏也不重。
     * 具体见 `LogcatCommands.nextPageSkipBytes`。
     *
     * ⚠️ 只对**相邻翻页**（上一页 / 下一页）成立。用户从下拉框直接跳到第 N 页时
     * 没有"上一页的实际内容"可用，只能退回估算定位，并重置本游标。
     */
    var pageCursor by remember { mutableStateOf(0L) }

    /**
     * 可选的页码上限。
     *
     * 按**行数**分页，所以页数 = ⌈采集总量 ÷ 每页条数⌉。
     *
     * ⚠️ 采集总量还没算出来时返回 1 —— 那时页码下拉只有一项，
     * 看起来像"坏了"。所以下面在切换「条数」时会重置页码，
     * 并且统计完成后页数会立刻跟上。
     */
    val pageCount = remember(captureStats, filter.lineLimit, filteredCount) {
        // ⚠️ 分母用**过滤后的命中数**，不是全量行数。
        //
        // 原先用 `captureStats.totalLines`（**未过滤**的全文件行数）——
        // 那是错的：页码的物理含义是"第几批 `条数` 条**命中**"，
        // 而分母却是"文件里一共有多少行"。两者口径不同，于是
        // 「共 4 页」与实际能翻出的命中批数对不上。
        //
        // 过滤后命中数 M 由 [filteredCount] 提供（全文件扫描得到，见
        // `LogcatCommands.buildFilteredCount`）。拿不到时**退回 1 页** ——
        // 宁可少给页码，也不给一个错的。
        val total = filteredCount ?: return@remember 1
        val perPage = filter.lineLimit.coerceAtLeast(1)
        ((total + perPage - 1) / perPage).coerceAtLeast(1)
    }

    /**
     * 切换「条数」时**回到第 1 页并重新读取**。
     *
     * ⚠️ 重置页码不是可选的：页码的物理含义就是"每页多少条"，
     * 条数一变，同一个页码指向的内容就完全不同了。
     * 不重置的话，用户从 2000 条切到 500 条时会停在一个**越界**的页码上
     * （总页数变了而页码没变），界面显示空列表。
     *
     * ⚠️⚠️ **光重置页码是不够的，必须重新读一次数据** —— 见下方
     * `LaunchedEffect(filter.lineLimit)` 的说明。
     * 这个 effect 必须放在 `loadFromSource()` 之后：它是局部函数，
     * 声明在前才能在 effect 里调用。
     */
    var capturedState by remember { mutableStateOf<CaptureState>(CaptureState.Idle) }
    var elapsedMs by remember { mutableStateOf(0L) }
    var shellOk by remember { mutableStateOf(true) }

    /**
     * 本次读取是否因**命令超时**而中止。
     *
     * ⚠️ 必须是状态，不能是局部值：空结果的**原因**由它参与判定
     * （见 `diagnoseEmpty`），而原因决定界面给用户的下一步提示。
     * 少了它，"命令超时"会被说成"缓冲区已轮转"，用户会去做一件无关的事。
     */
    var timedOut by remember { mutableStateOf(false) }

    // 过滤结果由「原始行 + 当前条件」派生，**不是独立状态** ——
    // 这样改条件必然触发重算，不可能出现两者不一致
    val result = remember(rawLines, filter, capturedState, elapsedMs, shellOk, timedOut) {
        buildViewerResult(
            raw = rawLines,
            filter = filter,
            state = capturedState,
            ownPids = ownPidsOf(context),
            elapsedMs = elapsedMs,
            shellAvailable = shellOk,
            timedOut = timedOut,
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
     * ⚠️ **过滤条件要一起传下去**：采集态/已完成态走的是 shell 侧 grep 检索，
     * 不是把整个文件读回来内存筛。
     *
     * 这与上一版的做法相反（那时过滤在内存做）。改的原因是**正确性**：
     * 采集文件可达数百 MB，不可能整个读回来；而"读末尾 N 行再筛"
     * 在高频日志下只能看到 0.35 秒的内容，等于大部分日志搜不到。
     *
     * 代价是改过滤条件要重跑一次命令 —— 所以界面上「刷新」改叫「检索」，
     * 语义更准确，也让用户知道点一下会执行一次查询。
     */
    fun loadFromSource() {
        // ⚠️ 已在读取中就不再起第二条命令。
        //
        // 进页面那一帧会有多个 effect 同时想读（见下方 `LaunchedEffect` 的说明），
        // 而 `refreshing` 是**共用的一个布尔**：任何一条先结束就把标志复位，
        // 此时另一条还在跑 → 界面显示"未刷新"、按钮可点、状态栏提示消失，
        // 而数据还没回来。去重是消除这个窗口最直接的办法。
        if (refreshing) return
        refreshing = true
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    // ⚠️ 窗口必须与翻页用同一个口径（[LogcatCommands.windowKbForPageLines]）。
                    // 原先这里不传窗口、落到默认的 16MB，而翻页用"条数 × 200 × 8"，
                    // 两者最多差 250 倍 —— 刷新与翻页看到的内容来自不同大小的窗口，
                    // 页码与内容对不上。
                    loadLogs(
                        context,
                        session.state,
                        filter,
                        initialWindowKb = LogcatCommands.windowKbForPageLines(filter.lineLimit),
                    )
                }
                rawLines = outcome.rawLines
                capturedState = session.state
                elapsedMs = outcome.elapsedMs
                shellOk = outcome.shellOk
                timedOut = outcome.timedOut
                // 重新读数据源 = 回到最新一窗
                pageIndex = 1
                pageCursor = 0L
                if (outcome.timedOut) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.logcat_empty_timeout),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: CancellationException) {
                // ⚠️ 必须把取消原样抛出去，否则会吞掉协程的正常取消语义
                throw e
            } catch (e: Throwable) {
                // ⚠️ 不能只记日志就完事。
                //
                // 走到这里意味着**读取整体失败**（`loadLogsOnce` 内部已用 runCatching
                // 把命令级失败转成了 `shellOk=false`，所以这里是更外层的意外）。
                // 原先只 `DebugLogger.w` 就返回 —— 界面保持上一次的数据与状态，
                // `shellOk` 还是 true，用户看到的是**旧数据 + 一切正常**。
                //
                // 这是本工具最忌讳的失效形态：出错却显示成功。
                DebugLogger.w(TAG, "读取日志异常", e)
                shellOk = false
                Toast.makeText(
                    context,
                    context.getString(R.string.logcat_read_failed),
                    Toast.LENGTH_LONG,
                ).show()
            } finally {
                // ⚠️⚠️ `refreshing` 的复位**必须在 finally 里**（实际踩过）。
                //
                // 原先写在正常路径的末尾 —— 一旦协程在中间被取消就永远不会执行，
                // 标志位**永久卡在 true**：界面一直转圈，且所有
                // `enabled = !actions.refreshing` 的按钮（含「删除采集文件」）
                // 全部变灰，用户除了退出重进没有任何办法。
                //
                // 触发条件很常见：命令执行期间 Core 被重启 / 连接断开，
                // 那条 25 秒的跨进程调用会被取消。日志实证：
                //     W/LogcatViewer: 读取日志失败
                //     androidx.compose.runtime.f0: rememberCoroutineScope left the composition
                //     读取日志: state=Completed 用时=24943ms ...
                //
                // 这和 `cat` 缺 `</dev/null` 那次是同一类错误：
                // **用一个没有兜底的标志位表达"我在忙"**。
                refreshing = false
            }
        }
    }

    /**
     * 切换「条数」时**回到第 1 页并重新读取**（用户报的问题 1）。
     *
     * ⚠️⚠️ **光重置页码是不够的，必须重新读一次数据。**
     * 原先这里只改了 `pageIndex` / `pageCursor`，没有触发读取 ——
     * 列表里仍是**按旧条数读回来的行**（比如 2000 行），
     * 而下方状态栏读的是同一份 `rawLines`，也跟着停在旧值。
     * 用户看到的就是「切了条数、页码回到 1，但状态栏纹丝不动」。
     *
     * `loadFromSource()` 会重跑命令、写回 `rawLines`，状态栏由它派生，
     * 于是页码与状态栏一起更新。
     *
     * ⚠️ 本 effect 必须放在 `loadFromSource()` **之后** ——
     * 它是局部函数，声明在前才能在 effect 里调用（放前面会编译不过）。
     *
     * ## ⚠️ 首次组合也会跑一次，这是**有意保留**的
     *
     * `LaunchedEffect(key)` 在首次组合时必然执行。此刻会话状态可能还是内存里的
     * 旧值（`probe()` 还没回来），所以这一读拿到的可能是"上一次的状态"对应的数据源。
     * 之后 `LaunchedEffect(session.state)` 探测到真实状态时会**再读一次**纠正。
     *
     * 也就是说首次进入存在一次**可能多余的读取**。代价是几百毫秒的一次命令，
     * 换来的是"进页面立刻有内容"，而不是等 probe 往返完成才显示。
     * `loadFromSource()` 里的 `if (refreshing) return` 保证两者不会真正并跑，
     * 所以这个取舍是安全的。
     */
    LaunchedEffect(filter.lineLimit) {
        pageIndex = 1
        pageCursor = 0L
        loadFromSource()
    }

    /**
     * 过滤条件变化 → **扫全文件统计命中数**（用户报的问题 3）。
     *
     * ## 为什么要全量扫
     *
     * 原先改条件只筛内存里那批行（最多 `条数` 行），而页码按未过滤总量算。
     * 用户要的是「全部数据的过滤 + 页数重算」——那就必须知道
     * **整个文件里当前条件命中多少行**，这个数只有扫描才能得到。
     *
     * ## ⚠️⚠️ 必须防抖，否则逐字符输入会打爆 shell
     *
     * `onValueChange` 是**每敲一个字符触发一次**。不加防抖的话，
     * 输入「ActivityManager」会启动 17 次全文件扫描 ——
     * 每次几秒到几十秒，而且它们会排队/互相取消，
     * 界面表现是"一打字就卡死"。
     *
     * 300ms 是常见取值：正常打字间隔 100–200ms，停手后才真正触发一次。
     *
     * ⚠️ key 只放**过滤条件**，不放 `session.state` ——
     * 状态变化由另一个 effect 负责（它会重新读数据源，与本 effect 职责不同）。
     *
     * ⚠️ 拿不到采集文件时（Idle 态读的是内存缓冲区）**直接跳过** ——
     * 那时没有文件可扫，算了也是白算，而且 `buildFilteredCount` 会返回 0，
     * 让页码显示成 1 页（与"没有日志"混淆）。
     */
    LaunchedEffect(filter.minLevel, filter.tagQuery, filter.messageQuery, session.state) {
        val st = session.state
        // 只有"能读采集文件"的两个态才统计；Idle / Stale 没有文件
        if (st !is CaptureState.Capturing && st !is CaptureState.Completed) {
            filteredCount = null
            return@LaunchedEffect
        }

        delay(FILTER_COUNT_DEBOUNCE_MS)

        countingMatches = true
        try {
            val n = withContext(Dispatchers.IO) {
                val cmd = LogcatCommands.buildFilteredCount(
                    tagQuery = filter.tagQuery,
                    messageQuery = filter.messageQuery,
                    minLevel = filter.minLevel,
                )
                runCatching { ShellManager.execShellCommand(context, cmd) }.getOrNull()
                    ?.let { LogcatCommands.parseFilteredCount(it) }
            }
            filteredCount = n
            if (n == null) DebugLogger.w(TAG, "全量命中统计解析失败（页数退回 1 页，不显示错数）")
        } catch (e: CancellationException) {
            // ⚠️ 条件又变了会取消上一次的 effect —— 这是防抖的正常工作方式，
            // 不能当成错误，必须原样抛出
            throw e
        } catch (e: Throwable) {
            DebugLogger.w(TAG, "全量命中统计失败", e)
            filteredCount = null
        } finally {
            countingMatches = false
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
    /**
     * 跳到第 [page] 页。
     *
     * ## 分页语义：**按行数**
     *
     * 第 N 页 = 跳过 `(N-1) × 每页条数` 行。
     *
     * ⚠️ 早先页码是「第几个字节窗口」，与「每页多少条」**定义不一致** ——
     * 切一次「条数」总页数就变、而当前页码不变，直接越界。
     * 用户定下了按行数分页（选项 1），并且**切条数时重置到第 1 页**。
     *
     * ## 两种跳法，精度不同（关键）
     *
     * | 跳法 | 定位依据 | 精度 |
     * |---|---|---|
     * | **相邻页**（页码 ±1） | [pageCursor] + 上一页**实际内容字节数** | 逐行对齐，不漏不重 |
     * | **远跳**（下拉框直接选第 N 页） | `skipBytesForPage` 的**估算** | 可能偏几十行 |
     *
     * 远跳没有办法精确：手上没有任何"第 N 页在哪"的信息，只能估一个位置先读回一页。
     * 但**读回来之后就准了** —— 游标被重置到实际读到的位置，
     * 之后往前继续翻就都走相邻路径。
     */
    fun goToPage(page: Int) {
        if (loadingEarlier) return
        if (session.state !is CaptureState.Capturing && session.state !is CaptureState.Completed) return
        val target = page.coerceAtLeast(1)
        val isAdjacent = target == pageIndex + 1 || target == pageIndex - 1

        loadingEarlier = true
        scope.launch {
            try {
                val outcome = withContext(Dispatchers.IO) {
                    // 相邻翻页：按上一页的**实际内容量**推进，逐行对齐。
                    // 其余情况（远跳 / 回到第 1 页）：退回估算定位，只能给个起点。
                    val contentBytes = LogcatCommands.contentBytesOf(rawLines)
                    val skip = if (isAdjacent) {
                        LogcatCommands.nextPageSkipBytes(pageCursor, contentBytes, filter.lineLimit)
                            ?: return@withContext null          // 已到文件开头
                    } else {
                        LogcatCommands.skipBytesForPage(target, filter.lineLimit)
                    }
                    val window = LogcatCommands.windowKbForPageLines(filter.lineLimit)
                    loadLogs(context, session.state, filter, skipBytes = skip, initialWindowKb = window) to skip
                }

                // 已到文件开头：明确提示，不要读一堆重复内容再装作翻过去了
                if (outcome == null) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.logcat_page_empty),
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@launch
                }

                val (result, usedSkip) = outcome
                if (result.shellOk && !result.timedOut) {
                    rawLines = result.rawLines
                    timedOut = false
                    pageIndex = target
                    // 游标推进到本页实际读到的位置，供下一次相邻翻页使用。
                    // 相邻与远跳都走这里是对的 —— 已用数值模拟验证过：
                    // 远跳之后继续相邻翻页是**自洽**的（页页衔接，不漏不重）
                    pageCursor = usedSkip

                    // ⚠️ 但远跳的**落点本身会偏**，且偏得不少。
                    // 估算行长 200 字节，实测常是 141 —— 远跳 skip 按 200 算，
                    // 换算成真实行数就少了约 30%。
                    //
                    // 实测偏差（20000 行文件、每页 1000 行）：
                    // | 目标页 | 应落行 | 实落行 | 偏差 |
                    // |---|---|---|---|
                    // | 2 | 19000 | 18582 | **-418** |
                    // | 5 | 16000 | 14327 | **-1673** |
                    // | 10 | 11000 | 7235 | **-3765** |
                    // | 20 | 1000 | 越界→夹到 0 | **-7950** |
                    //
                    // 用户看到的是「选第 20 页，结果给了文件最开头」，
                    // 而界面**什么都不说** —— 与这个工具一贯坚持的
                    // "不静默失效"原则相悖。
                    //
                    // 这里不试图修正落点（没有更多信息可用，文档 §说明的取舍），
                    // 但**必须让用户知道落点偏了**，并给出可操作的下一步（用相邻翻页）。
                    if (!isAdjacent && result.rawLines.isNotEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.logcat_page_approx, target),
                            Toast.LENGTH_LONG,
                        ).show()
                    }

                    if (result.rawLines.isEmpty()) {
                        Toast.makeText(
                            context,
                            context.getString(R.string.logcat_page_empty),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }
                } else if (result.timedOut) {
                    // ⚠️ 不静默：翻页失败时**保留原页内容**，但必须告诉用户"没翻过去"。
                    // 原先这里直接什么都不做 —— 用户点了页码、列表纹丝不动，
                    // 无从判断是"这页是空的"还是"命令超时了"。
                    Toast.makeText(
                        context,
                        context.getString(R.string.logcat_empty_timeout),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLogger.w(TAG, "翻到第 $target 页失败", e)
            } finally {
                loadingEarlier = false
            }
        }
    }

    /**
     * 统计**整个采集文件**（总行数 / 覆盖时间 / TAG 分布）。
     *
     * ⚠️ 只在采集结束后调用。采集中文件在变，结果立刻过期，
     * 且每次扫 256MB —— 用户也明确说过「采集中不用看」。
     *
     * 聚合在 shell 侧完成，回传只有几十行摘要，不存在 Binder 压力。
     *
     * @param openSheetWhenDone 统计完成后**直接弹出 TAG 统计面板**。
     *   用户点「TAG 统计」时若统计还没算好，只能等 —— 等到了却不弹面板，
     *   他会以为按钮没用（见 [runTagStats] 的说明）。
     */
    fun refreshCaptureStats(openSheetWhenDone: Boolean = false) {
        statsLoading = true
        scope.launch {
            try {
                val stats = withContext(Dispatchers.IO) {
                    val cmd = LogcatCommands.buildCaptureStats(filter.minLevel)
                    runCatching { ShellManager.execShellCommand(context, cmd) }.getOrNull()
                        ?.let { LogcatCommands.parseCaptureStats(it) }
                }
                captureStats = stats
                if (stats == null) {
                    DebugLogger.w(TAG, "全量统计解析失败（不显示统计，避免显示错数）")
                } else if (openSheetWhenDone) {
                    // 统计成功且用户正等着看 → 弹出面板
                    tagStats = stats.tagCounts
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                DebugLogger.w(TAG, "全量统计失败", e)
                if (openSheetWhenDone) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.logcat_tag_stats_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            } finally {
                statsLoading = false
            }
        }
    }

    LaunchedEffect(session.state) {
        val st = session.state
        if (st is CaptureState.Completed || st is CaptureState.Capturing) {
            loadFromSource()
        }
        // 采集结束后文件不再变化 → 全量统计只算这一次。
        // 用户明确说过「采集中不用看，结束时刷新一次就可以」
        when (st) {
            is CaptureState.Completed, is CaptureState.Stale -> {
                pageIndex = 1        // 新一批日志，翻页回到最新
                pageCursor = 0L
                refreshCaptureStats()
            }
            else -> {
                // 空闲态没有采集文件；采集中文件在变，统计出来的数字立刻过期
                captureStats = null
                pageIndex = 1
                pageCursor = 0L
            }
        }
    }



    
    /**
     * TAG 统计（§4.5）：**绕过 TAG 过滤**，但保留级别。
     *
     * ⚠️ 数据来自 [captureStats]（**全量**），不再跑采样检索。
     *
     * 旧实现调 `buildTagStats`，而那条命令带 `tail -n <条数>` ——
     * 统计的是**文件末尾 N 行**，用户却把它读成"总量"。
     * 实测症状：底部状态栏显示「本次读取 4176 行」，弹窗标题却是
     * 「共 2000 行」——那个 2000 是 `tail` 的截断线。**标题在说谎。**
     *
     * 现在已完成态下统计结果已经算好并缓存（采集结束时算一次），
     * 点开直接显示，不跑任何命令。
     */
    /**
     * 打开 TAG 统计面板。
     *
     * ## ⚠️ 原先的实现是坏的（用户报「TAG 统计始终为 0」）
     *
     * ```kotlin
     * tagStats = when {
     *     stats != null -> stats.tagCounts
     *     else -> { refreshCaptureStats(); return }   // ← 直接 return，tagStats 没赋值
     * }
     * ```
     *
     * 统计还没算好时，点了只是**启动一次异步统计然后返回** —— 面板不弹，
     * 什么也没发生。而 `refreshCaptureStats()` 完成后只写 `captureStats`，
     * **没有任何地方把它回流到 `tagStats`**，所以用户必须**再点一次**才看得到。
     *
     * 更糟的是：如果统计本身失败（`captureStats` 恒为 null，例如采集文件不存在
     * 或 Shell 权限不足），**点多少次都不会有结果**，表现就是"始终为 0"。
     *
     * 现在：没算好时启动统计并**要求它算完直接弹面板**（`openSheetWhenDone`），
     * 失败时明确报错而不是静默无反应。
     */
    fun runTagStats() {
        val stats = captureStats
        val counts = stats?.tagCounts
        if (counts == null) {
            // 还没统计过 / 统计失败 → 现算一次，算完直接弹面板
            refreshCaptureStats(openSheetWhenDone = true)
            return
        }
        if (counts.isEmpty()) {
            // 算出来了但一条 TAG 都没有 —— 多半是文件空或全被级别过滤掉了，
            // 明确告诉用户，而不是弹一个空面板让他以为坏了
            Toast.makeText(
                context,
                context.getString(R.string.logcat_tag_stats_empty),
                Toast.LENGTH_SHORT,
            ).show()
            return
        }
        tagStats = counts
    }

    // 查找在**筛选之后**的结果上做：用户看到的行就是可搜的行，
    // 否则会出现"搜到了但看不见"（那行被筛选掉了）。
    // 而查找**不下推 shell** —— 下推了不匹配的行就没了，看不到上下文
    val searchResult = remember(result.lines, search, searchIndex) {
        runLogcatSearch(result.lines, search, searchIndex)
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
                    // 查找入口已移到 A 行（见 FilterSection 的 searchToggle 参数）——
                    // 那里离日志区更近，展开后正好在日志上方，不必先把上方区展开
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

            /**
             * 上方信息区：**可折叠，但不是滚动区**。
             *
             * ## 交互模型（三版才定下来的）
             *
             * | 手指位置 | 行为 |
             * |---|---|
             * | 日志区 | 只滚日志，**上方区高度不变** |
             * | 上方区 | 驱动它收缩 / 展开，日志区随之变高 |
             * | 日志已到顶仍继续下拉 | 转交给上方区，把它拉回来 |
             *
             * ⚠️ 中间走过两条弯路，都记在这里免得再犯：
             * - 第一版做成"固定高度的独立滚动区"——但高度固定就意味着
             *   日志区永远拿不到更多空间，与"日志区要变大"的诉求相反
             * - 第二版做成"整页一个滚动流 + A 行 sticky"——手势不再有归属，
             *   在日志区里滑动也会把上方区带走
             *
             * ## 为什么用"收缩可见高度 + 内容位移"而不是滚动
             *
             * 滚动是"内容不动、视口移动"，而这里要的是"上方区让出高度给日志区"。
             * 用 [animateContentSize] / `heightIn` 配合内容 `offset` 更直接，
             * 且折叠量是**一个可被纯函数夹住的标量**（见 `advanceUpperCollapse`），
             * 好测也好调。
             */
            /**
             * 上方带：**Shell 状态 / 采集 / 过滤 / 查找栏 / A 行**，整体可折叠。
             *
             * ⚠️ A 行（查找栏也是）**放在这条带子里面**，不是钉在外层。
             * 这是用户明确纠正过的：A 行必须与上方内容**一起上移**，
             * 而不是固定不动让上方内容去"挤压"它。
             *
             * 早先的实现把它钉在外层，代价有三：
             * 1. 上方内容被压缩时，紧挨 A 行的那一条**先被压扁**（不是被裁走），
             *    看起来像一条脏像素
             * 2. 手势只挂在上方内容上，收尽后在 A 行上摸不到滚动手势
             * 3. 折叠不像是"滚动"，像是"A 行在吃掉上方"
             */
            UpperInfoSection(
                onNaturalHeightChange = { upperContentHeightPx = it },
                collapsedPx = upperCollapsedPx,
                onCollapsedChange = { upperCollapsedPx = it },
                maxCollapsePx = maxUpperCollapse(upperContentHeightPx, actionRowHeightPx),
            ) {
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
                    onTagStats = { runTagStats() },
                )

                // 查找栏与 A 行同属这条带子 —— 一起上移、一起被裁。
                // 原先查找栏钉在外面时，收起状态下点搜索会"没反应"：
                // 它确实渲染了，但位置在可视区之外
                if (searchBarVisible || search.isActive) {
                    FindBar(
                        search = search,
                        searchResult = searchResult,
                        enabled = result.lines.isNotEmpty(),
                        onSearchChange = {
                            search = it
                            searchIndex = 0
                        },
                        onNavigate = { step ->
                            val next = searchResult.advance(step)
                            searchIndex = next.currentIndex
                            // ⚠️ 滚到目标行。原先只改 index 不滚动，
                            // 用户点"下一个"看不到任何变化（除非目标恰好在屏幕内）
                            next.currentLineIndex?.let { lineIndex ->
                                scopeForScroll.launch { listState.animateScrollToItem(lineIndex) }
                            }
                        },
                        onClose = {
                            searchBarVisible = false
                            search = LogcatSearch()      // 关掉时一并清掉高亮
                        },
                    )
                }

                ActionRow(
                    filter = filter,
                    enabled = shellReady && !refreshing,
                    actions = actions,
                    searchActive = searchBarVisible || search.isActive,
                    onToggleSearch = {
                        searchBarVisible = !searchBarVisible
                        if (!searchBarVisible) search = LogcatSearch()   // 收起时清掉高亮
                    },
                    onFilterChange = { filter = it },
                    onRefresh = { loadFromSource() },
                    onGoToPage = { goToPage(it) },
                    pageIndex = pageIndex,
                    pageCount = pageCount,
                    loadingEarlier = loadingEarlier,
                    onClearList = { rawLines = emptyList() },
                    wrapLines = wrapLines,
                    onToggleWrap = { wrapLines = !wrapLines },
                    onDeleteFiles = {
                        scope.launch {
                            LogcatCaptureController.deleteCaptureFiles(context)
                            rawLines = emptyList()
                        }
                    },
                    // 报告自身高度：折叠上界 = 内容总高 − A行高，
                    // 这样"收尽"的定义就是"A 行正好贴住标题栏"
                    onHeightChange = { actionRowHeightPx = it },
                )
            }

            LogcatList(
                lines = result.lines,
                emptyReason = result.emptyReason,
                rawLineCount = result.rawLineCount,
                refreshing = refreshing,
                wrapLines = wrapLines,
                search = search,
                currentMatchLineIndex = searchResult.currentLineIndex,
                listState = listState,
                // ⚠️ 日志已到顶仍继续下拉 → 转交给自己上方的折叠带，把它拉回来。
                // 不接这个回调，日志滚到顶后手指继续下拉会整页弹动（嵌套滚动无人认领）
                onOverscrollDown = { deltaY ->
                    val (next, consumed) = consumePullToExpand(
                        collapsedPx = upperCollapsedPx,
                        availableY = deltaY,
                        maxCollapsePx = maxUpperCollapse(upperContentHeightPx, actionRowHeightPx),
                    )
                    upperCollapsedPx = next
                    consumed
                },
                modifier = Modifier.weight(1f),
            )

            // ⚠️ 状态栏固定在底部、不在滚动区内 —— 覆盖范围必须**始终可见**，
            // 它是"这批日志是什么"的唯一说明
            StatusBar(
                result = result,
                refreshing = refreshing,
                filterLineLimit = filter.lineLimit,
                state = session.state,
                rawLines = rawLines,
                captureStats = captureStats,
                statsLoading = statsLoading,
                filteredCount = filteredCount,
                countingMatches = countingMatches,
            )
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
    /**
     * 本次实际使用的读取窗口（KB）。
     *
     * ⚠️ 翻页时**必须**用它推进 `skip`，不能用默认窗口 ——
     * 扩窗重试后实际读的比默认窗口大，用错就会重复读同一段。
     */
    val windowKb: Int = LogcatCommands.READ_WINDOW_KB,
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
    skipBytes: Long = 0L,
    /**
     * 起始窗口（KB）。默认按 [LogcatCommands.READ_WINDOW_KB]。
     *
     * ⚠️ 翻页时会按「每页条数」算窗口（[LogcatCommands.windowKbForPageLines]）：
     * 选了 2000 条却只给 16MB 窗口的话，窄过滤下取不满一页。
     */
    initialWindowKb: Int = LogcatCommands.READ_WINDOW_KB,
): LoadOutcome {
    // ⚠️ 「取不满就扩窗重读」的阶梯循环。
    //
    // 定窗读取用"可能少"换了"快"：窗口 16MB ≈ 8 万行，
    // 命中率低于 2.5% 时就取不满 2000 条，而且**少得静默** ——
    // 界面显示 80 行，看起来像"就这么多"。老实现扫全部 256MB 没有这个问题。
    //
    // 这里在"没取满"时翻倍窗口重读（16MB → 32MB）。
    // 多花一次命令的时间，换取"不会静默漏内容"。
    var windowKb = LogcatCommands.clampWindowKb(initialWindowKb)
    while (true) {
        val outcome = loadLogsOnce(context, state, filter, skipBytes, windowKb)
        if (outcome.timedOut || !outcome.shellOk) return outcome

        val next = LogcatCommands.nextWindowForRetry(
            returned = outcome.rawLines.size,
            limit = filter.lineLimit,
            currentWindowKb = windowKb,
        ) ?: return outcome

        DebugLogger.i(TAG, "结果只取到 ${outcome.rawLines.size}/${filter.lineLimit} 行，扩窗重读：${windowKb}KB → ${next}KB")
        windowKb = next
    }
}

/** 单次读取（不重试）。 */
private suspend fun loadLogsOnce(
    context: Context,
    state: CaptureState,
    filter: LogcatFilter,
    skipBytes: Long,
    windowKb: Int,
): LoadOutcome {
    // STALE 不执行命令，走提示（buildRefresh 返回 null 就是表达这个）
    val cmd = LogcatCommands.buildRefresh(
        state,
        filter.lineLimit,
        filter.tagQuery,
        filter.minLevel,
        filter.messageQuery,
        skipBytes,
        windowKb,
    )
        ?: return LoadOutcome(emptyList(), 0, shellOk = true, timedOut = false, windowKb = windowKb)

    val startedAt = System.currentTimeMillis()
    // ⚠️ 必须用 WithResult：`buildSearch` 把 `timeout` 裹在整条管道外面，
    // 超时后退出码是 124 —— 只看输出是分不出"超时"与"没有匹配"的（两者都是空）。
    val shell = runCatching { ShellManager.execShellCommandWithResult(context, cmd) }
        .onFailure { DebugLogger.w(TAG, "读取日志失败", it) }
        .getOrNull()
    val output = shell?.output.orEmpty()
    val elapsed = System.currentTimeMillis() - startedAt

    // 超时判定：退出码 124（见 LogcatCommands.isTimeoutResult）。
    // ⚠️ 必须在判 failed 之前算——超时时输出也是 `Error: ...` 形式（退出码非零），
    // 若先判 failed，超时会被归成"Shell 不可用"，与真实原因不符。
    val timedOut = shell != null && LogcatCommands.isTimeoutResult(shell.exitCode)


    // Shell 层失败会以 "Error:" 前缀返回（见 ShellManager.executeShizukuCommand）。
    // 超时不算 shell 失败：通道是通的，只是命令跑太久被 timeout 砍了。
    val failed = output.startsWith("Error:") && !timedOut

    val lines = LogcatParser.parseLines(output)

    // ⚠️ 无论成功失败都记一行。此前只在失败时记，导致
    // "命令没跑对"与"跑了但没匹配到"在日志里**完全无法区分** ——
    // 用户报"采集不到日志"时只能猜。
    // 记命令原文（而非仅结果）是因为提交给 shell 的形式本身就可能是问题所在。
    DebugLogger.i(
        TAG,
        "读取日志: state=$state 用时=${elapsed}ms 失败=$failed 超时=$timedOut " +
            "退出码=${shell?.exitCode ?: "null"} 行数=${lines.size} " +
            "窗口=${windowKb}KB skip=$skipBytes cmd=$cmd"
    )

    return LoadOutcome(
        rawLines = lines,
        elapsedMs = elapsed,
        shellOk = !failed,
        timedOut = timedOut,
        windowKb = windowKb,
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


/**
 * 查找栏。
 *
 * ## 与「消息筛选」的分工（重要）
 *
 * | | 消息筛选 | 本栏 |
 * |---|---|---|
 * | 不匹配的行 | **消失** | 保留 |
 * | 上下文 | ❌ 看不到 | ✅ 看得到 |
 * | 下推 shell | ✅ | ❌（下推了上下文就没了） |
 *
 * **上下文是 logcat 调试的核心**：一个崩溃堆栈，只看到
 * `NullPointerException` 那一行没有意义，要看它前后发生了什么。
 * 所以两者都要，但不能合并成一个控件。
 */
@Composable
private fun UpperInfoSection(
    onNaturalHeightChange: (Int) -> Unit,
    collapsedPx: Float,
    onCollapsedChange: (Float) -> Unit,
    maxCollapsePx: Float,
    content: @Composable ColumnScope.() -> Unit,
) {
    /**
     * 折叠：**同一次测量里量出自然高度、算出可见高度、按此摆放**。
     *
     * ```
     * 展开          收起到一半        收尽
     * ┌──────┐      ┌──────┐        ┌──────┐ ← 容器上沿
     * │Shell │      │级别 D│        │A 行  │
     * │采集  │      │TAG   │        └──────┘
     * │过滤  │      │消息  │
     * │A 行  │      │A 行  │
     * └──────┘      └──────┘
     * ```
     *
     * A 行在这条带子的**末尾**，所以收尽时正好剩它 —— 它跟着一起上移。
     *
     * ## ⚠️ 为什么必须是"单次测量"，而不是"测出高度存进 state 再用"
     *
     * 这个组件连着栽了三次，根因都是同一个：**把测量结果存进 state，
     * 再用它去算本节点的高度**。只要内容高度会变，那个环就必然出问题 ——
     *
     * | 写法 | 后果 |
     * |---|---|
     * | 外层 `height(可见高)` 约束下发，读 `onSizeChanged` | 报出的不是自然高度 → 基准随折叠变小 → **底部空出 `collapsed`** |
     * | `wrapContentHeight(unbounded = true)` | 只放宽测量约束，**上报尺寸仍被外层夹住** → 同上 |
     * | `height(测到过的 contentHeightPx)` 钉死 | 内容变高后**钉在旧值**上 → Column 压缩末项 → **A 行被压成 0，整条消失** |
     *
     * 现在改成：`Layout` 里**当场量、当场算、当场摆**，高度**不落 state**。
     * `natural` 只作为**回调**传出去（用于算折叠上界），
     * **不参与本节点的高度计算** —— 反馈环从结构上就不存在了。
     */
    Layout(
        // ⚠️ 这里必须把 content 包进 Column 再交给 Layout。
        // 直接把 `ColumnScope.() -> Unit` 传给 Layout 的 content 参数会让
        // 编译器选中错的 Layout 重载 —— 症状是 measurePolicy 里
        // `layout` / `constraints.minHeight` 全部"未解析"（MeasureScope 没生效）
        content = { Column(content = content) },
        modifier = Modifier
            .fillMaxWidth()
            .clipToBounds()
            // ★ 手势挂在整个可折叠带上（含 A 行）。
            // 用户说的"A 行与上方区是一体的"——手势也必须一体，
            // 否则收尽后在 A 行上摸不到滚动手势，拉不回来
            .verticalDragGestures(
                onDragEnd = { onCollapsedChange(snapUpperCollapse(collapsedPx, maxCollapsePx)) },
            ) { deltaY ->
                onCollapsedChange(advanceUpperCollapse(collapsedPx, deltaY, maxCollapsePx))
            },
        measurePolicy = { measurables, constraints ->
            // ① 以**不受限**的高度量内容 → 得到与折叠无关的自然高度。
            //    每次都重量，所以内容变了（按钮出现、行增减）当场就跟上，
            //    不存在"钉在旧值"的问题
            val placeable = measurables.first().measure(
                constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity),
            )
            val natural = placeable.height

            // 只在变化时回调，避免同值反复写 state 触发重组
            onNaturalHeightChange(natural)

            // ② 当场算可见高度并上报 —— 这个尺寸只由 natural 与 collapsed 决定
            val visible = (natural - collapsedPx).coerceAtLeast(0f)
            val visiblePx = when {
                // 还没测得内容（首帧）→ 按内容撑开，别把高度报成 0
                natural <= 0 -> constraints.minHeight
                else -> visible.toInt().coerceIn(0, natural)
            }

            layout(constraints.maxWidth, visiblePx) {
                // ③ 内容整体上移折叠量。placeable 的高度是 natural（常量），
                //    所以这里就是纯粹的平移，不会有任何缺口
                placeable.place(0, -collapsedPx.toInt())
            }
        },
    )
}

/**
 * 竖直拖动手势。
 *
 * ⚠️ 不能直接用 `detectVerticalDragGestures` 的 `onVerticalDrag` 返回值 ——
 * 它不向父级回报"消费了多少"，于是手势会**同时**被外层滚动容器吃掉，
 * 表现为拖上方区时日志跟着乱动。这里明确把每个位移都消费掉。
 *
 * ## ⚠️ 为什么要用 `rememberUpdatedState` 而不是 `pointerInput(Unit)`
 *
 * **这是踩过的坑**。最初写的是：
 *
 * ```kotlin
 * Modifier.pointerInput(Unit) {
 *     detectVerticalDragGestures { _, d -> onDrag(d) }
 * }
 * ```
 *
 * 看起来没问题，但 `pointerInput(Unit)` 的判断键是那个 `Unit` ——
 * Compose 只在**键变化时**重建手势协程，而 lambda 换了新实例**不算键变化**。
 * 于是协程里持有的是**首次组合时**的那份 lambda，
 * 它闭包捕获的 `collapsedPx`、`maxCollapsePx` 永远停在初始值 `0`。
 *
 * 症状（用户报的"收不尽 / A 行上滚不下来"）：
 * 每次拖动都从 `collapsed = 0` 起算，位移**无法累积** ——
 * 拖了半天也只能跟着单个事件走几像素，松手吸附又把它拉回 0。
 *
 * 修法是把手势里要读的值走 `rememberUpdatedState`，
 * 这样协程始终读到**最新**的闭包，而不必靠重启手势来刷新。
 */
@Composable
private fun Modifier.verticalDragGestures(
    onDragEnd: () -> Unit,
    onDrag: (Float) -> Unit,
): Modifier {
    val currentOnDrag by rememberUpdatedState(onDrag)
    val currentOnDragEnd by rememberUpdatedState(onDragEnd)

    return this.pointerInput(Unit) {
        detectVerticalDragGestures(
            onDragEnd = { currentOnDragEnd() },
        ) { change, dragAmount ->
            change.consume()    // ★ 明确消费：不要让外层滚动容器再处理一遍
            currentOnDrag(dragAmount)
        }
    }
}

@Composable
private fun FindBar(
    search: LogcatSearch,
    searchResult: LogcatSearchResult,
    enabled: Boolean,
    onSearchChange: (LogcatSearch) -> Unit,
    onNavigate: (Int) -> Unit,
    onClose: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = search.query,
            onValueChange = { onSearchChange(search.copy(query = it)) },
            enabled = enabled,
            singleLine = true,
            placeholder = { Text(stringResource(R.string.logcat_find_hint)) },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            textStyle = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
        )

        if (search.isActive) {
            // 命中计数：`3/17`。没有它用户不知道还有没有更多
            Text(
                text = searchResult.positionLabel(),
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace,
                color = if (searchResult.hasMatches) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else {
                    MaterialTheme.colorScheme.error
                },
                modifier = Modifier.padding(horizontal = 6.dp),
            )
            IconButton(
                onClick = { onNavigate(-1) },
                enabled = searchResult.hasMatches,
            ) {
                Icon(Icons.Default.KeyboardArrowUp, contentDescription = stringResource(R.string.logcat_find_prev))
            }
            IconButton(
                onClick = { onNavigate(1) },
                enabled = searchResult.hasMatches,
            ) {
                Icon(Icons.Default.KeyboardArrowDown, contentDescription = stringResource(R.string.logcat_find_next))
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = stringResource(R.string.logcat_close))
            }
        }
    }
}

/**
 * 过滤条件区。
 *
 * ⚠️ 这里**只放过滤条件本身**。A 行（条数 / 检索 / 清空 / 删除 / 换行 / 搜索）
 * 已抽到 [ActionRow] —— 原因见那边的注释：它必须站在折叠区之外。
 */
@Composable
private fun FilterSection(
    filter: LogcatFilter,
    enabled: Boolean,
    actions: LogcatViewerActions,
    onFilterChange: (LogcatFilter) -> Unit,
    onTagStats: () -> Unit,
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
            Spacer(Modifier.width(8.dp))
            // 与 TAG 行的「TAG 统计」按钮对齐。
            // 这里**故意放一个等宽的空占位**而不是加个真按钮 ——
            // 消息过滤没有对应的"统计"功能，硬凑一个按钮反而是误导。
            // 用 Spacer 是为了让两个输入框右边缘齐平，视觉上成一组
            Spacer(modifier = Modifier.width(MESSAGE_ROW_ALIGN_WIDTH))
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

        Spacer(Modifier.height(8.dp))
    }
}

/**
 * A 行：**常驻**的操作行。
 *
 * 它与 [FilterSection] 分离是本次布局改造的核心：
 * A 行原先长在过滤区的末尾，于是折叠上方区时它跟着一起滚走 ——
 * 而"上方区收尽后只剩下 A 行"正是我们要达成的终态，它必须自己**站在折叠区之外**。
 *
 * ## 搜索按钮为什么在这里而不是标题栏
 *
 * 用户明确要求移到这里。除了"离日志区更近"，还有个实际好处：
 * 查找栏展开后会占据 A 行下方的位置，**不需要先把上方区展开**就能用 ——
 * 原先放在标题栏时，上方区一收起来，点搜索等于把控件渲染到了屏幕外（用户报的问题 2）。
 */
@Composable
private fun ActionRow(
    filter: LogcatFilter,
    enabled: Boolean,
    actions: LogcatViewerActions,
    searchActive: Boolean,
    onToggleSearch: () -> Unit,
    onFilterChange: (LogcatFilter) -> Unit,
    onRefresh: () -> Unit,
    /** 跳到第 N 页（1 = 最新一窗）。 */
    onGoToPage: (Int) -> Unit,
    /** 当前页码。 */
    pageIndex: Int,
    /** 可选的页码上限（由采集总量与窗口大小算出）。 */
    pageCount: Int,
    /** 是否正在翻页。 */
    loadingEarlier: Boolean,
    onClearList: () -> Unit,
    wrapLines: Boolean,
    onToggleWrap: () -> Unit,
    onDeleteFiles: () -> Unit,
    /**
     * 回报本行高度。
     *
     * ⚠️ 上层用它算**折叠上界**（内容总高 − A行高），
     * 于是"收尽"恰好等于"A 行贴住标题栏"。不回报的话上界只能按内容总高算，
     * 那会把 A 行一起收走 —— 屏幕上只剩日志，没有任何操作入口。
     */
    onHeightChange: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { if (it.height > 0) onHeightChange(it.height) },
    ) {
        HorizontalDivider()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ⚠️ 字体大小必须与相邻按钮一致。此前「删除采集文件」「自动换行」
            // 显式写了 labelMedium（比 TextButton 默认小一号），一排里看着没对齐
            TextButton(onClick = onToggleSearch) {
                Icon(
                    Icons.Default.Search,
                    contentDescription = stringResource(R.string.logcat_find_hint),
                    tint = if (searchActive) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        // 未激活时跟随主题前景色（TextButton 的默认前景）
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }

            Text(
                text = stringResource(R.string.logcat_line_limit_label),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            var limitMenuOpen by remember { mutableStateOf(false) }
            // ⚠️ 按钮与下拉菜单**必须包在同一个 Box 里**。
            // 直接放进 Row 时，DropdownMenu 的锚点是**父布局**（整行）而不是按钮 ——
            // 而这一行中间有 `Spacer(weight(1f))`，于是菜单会锚到行首，
            // 弹出位置远在按钮左边（实测：按钮在屏幕右侧，菜单却在最左侧）。
            // 包一层 Box 后锚点就是按钮本身的边界，菜单位置才对得上。
            Box {
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
            }

            Spacer(Modifier.weight(1f))
            // 「页码」：定窗读取一次看不全，用页码显式跳转（用户要的翻页方式）。
            // 只在能读文件的两个态显示 —— 空闲态读的是实时缓冲区，没有"第几页"
            if (actions.capturing || actions.completed) {
                Text(
                    text = stringResource(R.string.logcat_page_label),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                var pageMenuOpen by remember { mutableStateOf(false) }
                // ⚠️ 同「条数」：按钮与菜单必须包在同一个 Box 里才能正确锚定。
                // 这一行的 `Spacer(weight(1f))` 会让未包 Box 的菜单锚到行首，
                // 表现为菜单弹在屏幕最左边、与按钮相距整个屏幕宽。
                Box {
                    TextButton(
                        onClick = { pageMenuOpen = true },
                        enabled = !loadingEarlier && !actions.refreshing,
                    ) {
                        Text(
                            if (loadingEarlier) "…" else pageIndex.toString(),
                        )
                        Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                    }
                    DropdownMenu(expanded = pageMenuOpen, onDismissRequest = { pageMenuOpen = false }) {
                        // 页数 = ceil(采集总量 ÷ 每页条数)，由 captureStats 算出。
                        // 拿不到总量时只有 1 页（用户可点「TAG 统计」触发全量统计）
                        val maxPage = pageCount
                        (1..maxPage.coerceAtLeast(1)).forEach { p ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        if (p == 1) stringResource(R.string.logcat_page_newest, p)
                                        else p.toString()
                                    )
                                },
                                onClick = {
                                    onGoToPage(p)
                                    pageMenuOpen = false
                                },
                            )
                        }
                    }
                }
            }
            // ⚠️ 采集已完成 / 异常结束时**不显示刷新按钮**：
            // 数据源是已固定的采集文件，刷新只会读到同一批内容。
            // 那个按钮在那里只会让人以为"点了会变"，而且它原先真的会去
            // 重读数据源（在已完成态被判成空闲 → 读到实时缓冲区，用户报的问题 1）。
            // 要换一批日志应当重新采集，而不是刷新。
            if (!actions.sourceIsFixed) {
                TextButton(onClick = onRefresh, enabled = actions.canRefresh) {
                    Text(stringResource(R.string.logcat_action_refresh))
                }
            }
            TextButton(onClick = onClearList, enabled = enabled) {
                Text(stringResource(R.string.logcat_action_clear_list))
            }
            // 删除采集文件：清掉磁盘上的日志（含轮转历史份）。
            // 只在有采集文件时有意义（空闲态读的是缓冲区，没有文件）
            if (actions.completed || actions.capturing || actions.stale) {
                TextButton(onClick = onDeleteFiles, enabled = !actions.refreshing) {
                    Text(
                        text = stringResource(R.string.logcat_action_delete_files),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            // 换行开关：只影响渲染，不碰数据，因此不受 enabled 约束
            TextButton(onClick = onToggleWrap) {
                Text(
                    text = stringResource(
                        if (wrapLines) R.string.logcat_wrap_on else R.string.logcat_wrap_off
                    )
                )
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun LogcatList(
    lines: List<LogcatLine>,
    emptyReason: LogcatEmptyReason?,
    rawLineCount: Int,
    refreshing: Boolean,
    wrapLines: Boolean,
    search: LogcatSearch,
    currentMatchLineIndex: Int?,
    /**
     * 列表滚动状态。由调用方持有，因为**查找跳转要滚到目标行** ——
     * 状态放在这里的话，外层拿不到它就滚不动。
     */
    listState: LazyListState,
    /**
     * 日志已到顶、手指**仍继续下拉**时的回调。
     *
     * 参数是这份剩余位移（向下为正），返回值是**实际消费掉多少**。
     *
     * ⚠️ 不接这个回调的后果：日志滚到顶后继续下拉，没人认领这段位移，
     * 嵌套滚动会把它抛给更外层 —— 表现为整页跟着弹动。
     * 接入后它被用来把上方信息区**拉回来展开**（[consumePullToExpand]）。
     */
    onOverscrollDown: (Float) -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    // ⚠️⚠️ 「刷新中」**不能**把整个日志区替换成加载圈。
    //
    // 原实现是 `if (refreshing) { Box(fillMaxSize){ 转圈 }; return }`，
    // 两个后果：
    // 1. **已有内容被藏起来** —— 刷新是新数据到来前，旧日志本来还能看
    // 2. **提示只在日志区可见** —— 用户的视线若在上方过滤区或下方状态栏，
    //    完全感知不到正在刷新（用户报的问题 2 正是这个）
    //
    // 现在改成：日志区照常渲染旧内容，只在右上角叠一个**小进度指示**。
    // 状态栏那边另有一处行内提示（见 StatusBar 的 refreshing 参数）。

    if (lines.isEmpty()) {
        EmptyHint(emptyReason, rawLineCount, modifier)
        return
    }

    // 新日志在尾部，刷新后默认滚到底更符合"看最近发生了什么"。
    // ⚠️ LaunchedEffect 必须放在 LazyColumn **之外**——
    // LazyColumn 的内容 lambda 是 LazyItemScope，不是 Composable 上下文，
    // 在里面调用 @Composable 函数会编译报错。
    LaunchedEffect(lines.size) {
        if (lines.isNotEmpty()) listState.scrollToItem(lines.lastIndex)
    }

    // ⚠️ 嵌套滚动连接：**只处理"日志已经滚不动了、还有剩余位移"这一种情况**。
    //
    // 其余情况一律不消费 —— 这正是"手指在日志区滑动时上方区不动"的实现方式：
    // 日志区自己能滚时，位移在 onPreScroll 就被它拿走，轮不到这里；
    // 只有它滚到边界、吃不下这段位移了，剩余部分才会落到 onPostScroll。
    //
    // ⚠️ `onOverscrollDown` 必须走 `rememberUpdatedState`：
    // `remember(key)` 会把对象钉在首次创建的那一份上，
    // 而它闭包捕获的 `collapsedPx` 一直在变 —— 不刷新的话
    // "日志到顶继续下拉展开上方区"会每帧都从旧值起算，效果等于没有。
    val scope = rememberCoroutineScope()
    val currentOverscroll by rememberUpdatedState(onOverscrollDown)
    val overscrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                available: Offset,
                source: NestedScrollSource,
            ): Offset {
                // available.y > 0 = 剩余的是"向下拉"的分量（把上方区拉回来展开）
                if (available.y <= 0f) return Offset.Zero
                val used = currentOverscroll(available.y)
                return if (used > 0f) Offset(0f, used) else Offset.Zero
            }
        }
    }

    // ⚠️ SelectionContainer 包在 LazyColumn **外面**，不是每行一个：
    // 放里面的话只能单行选择，而用户复制日志几乎总是要连着复制好几行
    //
    // ⚠️⚠️ `modifier` 必须给 **SelectionContainer**，不能给里面的 LazyColumn。
    //
    // `Modifier.weight(1f)` 是 **parent data**，只有 Column 的**直接子项**带上它
    // 才有效。原先写成了 `LazyColumn(modifier = modifier.fillMaxSize())` ——
    // LazyColumn 的父级是 SelectionContainer 而不是 Column，weight 被**静默忽略**，
    // 于是 LazyColumn 按内容高度撑满，把下面的 StatusBar 推出屏幕。
    //
    // 症状只在"有日志"时出现（空态/加载中走的是别的分支，modifier 直接给了
    // Column 的子项）。用户报的「覆盖范围等状态信息没有固定展示」根因就在这 ——
    // 此前一轮改的是 `buildCoverage` 的取数来源，那不是主因。
    // ★ 日志区 + 可拖动滚动轴。
    //
    // ⚠️ 滚动轴**只做定位**，不显示时间预览 —— 早先那版会在拖动时弹出
    // 时间气泡，实测因裁剪问题看不到，用户明确要求换成常规滚动轴。
    // 不要照旧注释的"带时间预览"去找那段代码，它已经删了。
    //
    // 用 Box 叠起来而不是 Row：滚动轴要**浮在内容之上**（占的是右侧一条窄边），
    // 用 Row 的话会把日志行挤窄，横向滚动的排版跟着变。
    Box(modifier = modifier) {
        SelectionContainer(modifier = Modifier.fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(overscrollConnection),
            ) {
            // ⚠️ **不要自定义 key**。logcat 里两行完全相同的日志极常见
        // （循环打印、重复的堆栈帧），而 `LogcatLine` 是 data class，
        // 内容相同的两行必然算出同一个 key —— LazyColumn 要求 key 唯一，
        // 撞上会抛 IllegalArgumentException 直接崩掉界面。
        //
        // 这里也确实不需要自定义 key：列表是**整体替换**语义（§4.1.2），
        // 没有移动/重排，默认的位置索引天然唯一且稳定。
            // ⚠️ 用 itemsIndexed 而非 items + `lines.indexOf(line)`：
            // 后者是 O(n²)，几千行时每帧都要全表扫描，会明显卡顿
                itemsIndexed(lines) { index, line ->
                    LogcatRow(
                        line = line,
                        wrapLines = wrapLines,
                        search = search,
                        // 当前定位的那一条给更强的高亮，便于在多个命中里认出来
                        isCurrentMatch = currentMatchLineIndex == index,
                    )
                }
            }
        }

        // 滚动轴浮在右侧。拖它 = 快速定位。
        //
        // ⚠️ `Modifier.align(Alignment.CenterEnd)` **不能省**：
        // 外层 Box 的默认对齐是 TopStart，不加这一句时整条滚动轴会贴在
        // **屏幕左边**。用户去右侧拖滚动轴自然毫无反应，
        // 会直接得出"这个功能没生效"的结论。
        LogcatScrollbar(
            lines = lines,
            listState = listState,
            onSeek = { index -> scope.launch { listState.scrollToItem(index) } },
            modifier = Modifier.align(Alignment.CenterEnd),
        )

        // 刷新中的行内指示：叠在日志区右上角，**不遮挡内容**。
        // 上方过滤区、下方状态栏各有一处提示，三处合力让用户在任何位置都感知得到
        if (refreshing) {
            Surface(
                color = MaterialTheme.colorScheme.inverseSurface,
                shape = RoundedCornerShape(16.dp),
                shadowElevation = 3.dp,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(14.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.logcat_refreshing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.inverseOnSurface,
                    )
                }
            }
        }
    }
}

/**
 * 常规滚动轴：可拖动定位，浮在日志区右侧。
 *
 * ## 为什么是自己画
 *
 * Compose 没有内置的可拖动滚动条（`VerticalScrollbar` 属 Material 的
 * 桌面适配库，不在 appcompat 依赖里）。而 `LazyColumn` 的
 * `VerticalScrollbarAdapter` 需要额外依赖，为一个窄边条引入整包不划算。
 *
 * ## 与上一版的区别（用户反馈驱动）
 *
 * 上一版带"拖动预览时间"的气泡，实测**看不到**：气泡要靠负偏移浮出轨道矩形，
 * 而轨道只有 24dp 宽、左边就是日志区，气泡要么被裁、要么与横向滚动的日志打架。
 * 用户明确说「不要这个时间预览了，换成常规滚动轴」。
 *
 * 现在只做两件事：**显示当前位置**、**可拖动跳转**。
 *
 * ## ⚠️ 命中区是 40dp，比可见滑块宽得多
 *
 * 这是用户反馈的直接结果：「现在这一个不太好滚动点击不到」。
 * 上一版轨道 24dp、可见滑块只有 8dp，手指很难按住（Android 的最小推荐
 * 触摸目标是 48dp）。现在把**透明命中区**加宽到 40dp，
 * 可见滑块保持 6dp 的细条 —— 视觉上仍然克制，但按得住。
 *
 * ## 交互
 *
 * - 按下即跳到对应位置（`detectDragGestures` 的 `onDragStart` 也会在按下时触发）
 * - 拖动为连续手势，用**当前位置**而非累加 delta（累加会漂移）
 */
@Composable
private fun LogcatScrollbar(
    lines: List<LogcatLine>,
    listState: LazyListState,
    onSeek: suspend (Int) -> Unit,
    /**
     * ⚠️ 必须由调用方传入 `Modifier.align(Alignment.CenterEnd)`。
     *
     * 本组件被放在一个 `Box` 里（与 LazyColumn 叠放），而 `Box` 的默认对齐是
     * `TopStart` —— 不给对齐修饰符时，整条滚动轴会落在**容器左上角**。
     */
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()

    /** 拖动中：滑块高亮。 */
    var dragging by remember { mutableStateOf(false) }

    // 轨道高度由布局给出；滑块高度按可见比例算
    var trackHeightPx by remember { mutableStateOf(0f) }

    val minThumbPx = with(density) { 40.dp.toPx() }

    // 可见项数：从布局信息拿，拿不到时按 20 估（只影响滑块长度）
    val visibleCount = listState.layoutInfo.visibleItemsInfo.size.takeIf { it > 0 } ?: 20
    val thumbHeightPx = thumbHeightForTrack(
        trackHeightPx = trackHeightPx,
        visibleCount = visibleCount,
        itemCount = lines.size,
        minThumbPx = minThumbPx,
    )

    // 滑块位置跟随实际滚动位置。
    // ⚠️ 不再有 previewIndex：拖动时 onSeek 会立刻滚动列表，
    // 而 firstVisibleItemIndex 随之更新，滑块自然跟手 ——
    // 上一版用 previewIndex 覆盖是为了让滑块在列表异步滚动前就动，
    // 但那也是气泡被裁的根源。现在的取舍是"跟随稍慢一点、但没有裁剪问题"。
    val thumbY = thumbYForScrollIndex(
        index = listState.firstVisibleItemIndex,
        trackHeightPx = trackHeightPx,
        itemCount = lines.size,
        thumbHeightPx = thumbHeightPx,
    )

    Box(
        modifier = modifier
            // ⚠️ 命中区 40dp：用户反馈 24dp 太细点不到。
            // 可见滑块只有 6dp，多出来的宽度是透明的，不影响观感
            .width(40.dp)
            .fillMaxHeight()
            .onSizeChanged { trackHeightPx = it.height.toFloat() }
            .pointerInput(lines.size) {
                detectDragGestures(
                    onDragStart = { offset ->
                        dragging = true
                        scope.launch {
                            onSeek(scrollIndexForDrag(offset.y, size.height.toFloat(), lines.size))
                        }
                    },
                    onDragEnd = { dragging = false },
                    onDragCancel = { dragging = false },
                ) { change, _ ->
                    change.consume()
                    // ⚠️ 用**当前位置**而不是累加 delta：
                    // 累加会因事件丢失/坐标偏差而漂移，
                    // 表现是"手指没动，滑块自己慢慢跑"
                    scope.launch {
                        onSeek(
                            scrollIndexForDrag(
                                change.position.y,
                                size.height.toFloat(),
                                lines.size,
                            )
                        )
                    }
                }
            },
    ) {
        // 可见滑块：靠轨道右缘，宽 6dp。
        // ⚠️ x 偏移按「命中区宽度 - 滑块宽度 - 内边距」算：
        // 40 - 6 - 4 = 30dp。写死数字的话，命中区宽度一改这里就偏
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .offset(x = with(density) { -(4).dp }, y = with(density) { thumbY.toDp() })
                .width(6.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(RoundedCornerShape(3.dp))
                .background(
                    if (dragging) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant
                ),
        )
    }
}

/**
 * 构造带高亮的文本。
 *
 * ⚠️ **大小写不敏感时必须逐个找原文区间**，不能把文本小写后再 `indexOf` 取下标 ——
 * 某些字符转小写后长度会变（如 `İ` → `i̇`），下标就会错位，
 * 表现是高亮**标在错误的位置**上。
 *
 * 这里改用 `indexOf(ignoreCase = true)`，它返回的是**原文**里的下标，天然正确。
 */
private fun buildAnnotatedStringWithHighlight(
    text: String,
    query: String,
    caseSensitive: Boolean,
    highlightColor: androidx.compose.ui.graphics.Color,
): androidx.compose.ui.text.AnnotatedString {
    if (query.isBlank()) return androidx.compose.ui.text.AnnotatedString(text)

    return androidx.compose.ui.text.buildAnnotatedString {
        var cursor = 0
        while (cursor <= text.length - query.length) {
            val found = text.indexOf(query, cursor, ignoreCase = !caseSensitive)
            if (found < 0) break

            // 命中前的正常段
            append(text.substring(cursor, found))
            // 命中段
            withStyle(
                androidx.compose.ui.text.SpanStyle(background = highlightColor)
            ) {
                append(text.substring(found, found + query.length))
            }
            cursor = found + query.length
        }
        if (cursor < text.length) append(text.substring(cursor))
    }
}

@Composable
private fun LogcatRow(
    line: LogcatLine,
    wrapLines: Boolean,
    search: LogcatSearch,
    isCurrentMatch: Boolean,
) {
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

    val fullText = buildString {
        if (line.isContinuation) append("↳ ")
        if (line.timestamp.isNotBlank()) append(line.timestamp).append("  ")
        if (line.pid >= 0) append(line.pid).append(" ").append(line.tid).append(" ")
        append(line.level.char).append(" ")
        if (line.tag.isNotBlank()) append(line.tag).append(": ")
        append(line.message)
    }

    // ⚠️ 高亮用 AnnotatedString 标在**同一条 Text 上**，而不是把行拆成多个 Text 拼接：
    // 拆分会让换行/横向滚动的排版散掉，且中文与等宽字体混排时对不齐。
    //
    // 高亮当前命中的那条用更强的背景色 —— 否则在一片黄底里认不出
    // "我现在跳到哪一条了"（尤其命中很多时）
    val textContent = if (search.isActive) {
        buildAnnotatedStringWithHighlight(
            text = fullText,
            query = search.query,
            caseSensitive = search.caseSensitive,
            highlightColor = MaterialTheme.colorScheme.tertiaryContainer,
        )
    } else {
        null
    }

    Row(
        modifier = scrollModifier
            .then(
                if (isCurrentMatch) {
                    Modifier.background(MaterialTheme.colorScheme.primaryContainer)
                } else {
                    Modifier
                }
            )
            .padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        if (textContent != null) {
            Text(
                text = textContent,
                softWrap = wrapLines,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
            )
        } else {
            Text(
                // 换行模式下不设 maxLines，Text 会自行折行；
                // 不换行时靠 horizontalScroll 承载超出部分
                softWrap = wrapLines,
                text = fullText,
                fontFamily = FontFamily.Monospace,
                fontSize = 11.sp,
                color = color,
            )
        }
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
private fun StatusBar(
    result: LogcatViewerResult,
    state: CaptureState,
    filterLineLimit: Int,
    /**
     * 未经过滤的原始行（**本次读取**的）。
     *
     * ⚠️ 覆盖范围要从**它**算，不是从 `result.lines` ——
     * 覆盖范围描述的是"这次读回来的是哪一段"，不该因为过滤条件而变化。
     * 从过滤后的行算会踩坑：筛选后只剩降级行时时间戳为空，
     * `rangeText` 返回 null，界面上那行**整行消失**，看起来像没这个功能。
     */
    rawLines: List<LogcatLine>,
    /**
     * **整个采集文件**的全量统计；未算出时为 null。
     *
     * 这是"我看全了吗"的参照物 —— 与本次读取无关，是常量。
     */
    captureStats: CaptureStats?,
    statsLoading: Boolean,
    /**
     * 是否正在重新读取数据源。
     *
     * ⚠️ 状态栏**必须**表达这件事：这里的数字（本次读取 N 行 / 覆盖 X）
     * 在刷新期间是**旧的**，而且刷新完会整体变化。
     * 不提示的话，用户看不到任何变化过程 —— 只有数字突然跳一下，
     * 而如果他的视线不在这块区域，就完全感知不到"刚刷新过"（用户报的问题 2）。
     */
    refreshing: Boolean,
    /**
     * 当前过滤条件在整个采集文件里命中多少行；未知时为 null。
     *
     * ⚠️ 这是**页码的分母** —— 不显示它，用户就没法理解"共 N 页"是怎么来的。
     */
    filteredCount: Int?,
    /** 是否正在跑全量命中统计。 */
    countingMatches: Boolean,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {

            // 刷新中：在状态栏顶部加一行醒目的提示。
            // 放在**最前面**而不是最后，因为下面的数字此刻是旧值，
            // 先看到"正在刷新"就不会把旧值当真
            if (refreshing) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(bottom = 4.dp),
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.logcat_refreshing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }

            // ── 第一行：**采集总量** ────────────────────────────────
            //
            // ⚠️ 这两行的对比是整块状态栏的意义所在。
            // 旧版第一行写的是"本次读取 N 行"、第二行是"实际覆盖 X"，
            // 而两者**算的是同一批行**（都来自本次读取），无过滤时必然相等 ——
            // 用户的原话是「我完全看不懂，到底哪个范围更大」。
            //
            // 现在改成"采集 vs 读取"：落差本身就说明问题
            // 「采集 12 万行覆盖 5 分钟，本次读取 2000 行覆盖 0.7 秒」。
            when (state) {
                is CaptureState.Idle -> Text(
                    // 空闲态读的是实时缓冲区（滚动窗口），"采集了多少"没有意义
                    text = stringResource(
                        R.string.logcat_status_idle,
                        result.lines.size,
                        result.continuationCount,
                        result.elapsedMs,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )

                else -> {
                    Text(
                        text = when {
                            statsLoading -> stringResource(R.string.logcat_status_stats_loading)
                            captureStats != null -> stringResource(
                                R.string.logcat_status_capture,
                                captureStats.totalLines,
                                captureStats.rangeText
                                    ?: stringResource(R.string.logcat_status_no_time_range),
                            )
                            // 统计失败时**不显示数字** —— 宁可不显示也不要显示错的
                            else -> stringResource(R.string.logcat_status_capture_unknown)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )

                    // ── 当前条件的全量命中数 ──────────────────────────
                    //
                    // ⚠️ **必须显示**，因为它就是页码的分母。
                    //
                    // 上一行是「采集 N 行」（全量、未过滤），而页码按
                    // 「当前条件命中 M 行」算 —— 不给用户看 M，
                    // 他看到的「共 4 页」就没有任何依据，
                    // 只会觉得"页码怎么和上面那个数字对不上"。
                    //
                    // 未过滤时 M 与 N 相等，这一行就是冗余的 ——
                    // 但那时它无害（用户一眼能看出两者一致），
                    // 而过滤时它是唯一能解释页码的数字。
                    filteredCount?.let { matches ->
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = when {
                                countingMatches -> stringResource(R.string.logcat_status_counting)
                                matches == 0 -> stringResource(R.string.logcat_status_no_matches)
                                else -> stringResource(R.string.logcat_status_matches, matches)
                            },
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            color = if (matches == 0) {
                                MaterialTheme.colorScheme.error
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                }
            }

            // ── 第二行：**本次读取** ────────────────────────────────
            if (state !is CaptureState.Idle) {
                Spacer(Modifier.height(2.dp))
                val degraded = if (result.continuationCount > 0) {
                    stringResource(R.string.logcat_status_degraded_part, result.continuationCount)
                } else ""
                Text(
                    text = stringResource(
                        R.string.logcat_status_read,
                        result.lines.size,
                        degraded,
                        result.timeRange ?: stringResource(R.string.logcat_status_no_time_range),
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // ── 第三行：提示 ────────────────────────────────────────
            //
            // ⚠️ 原有一条「本页已取满条数；还有更早的日志，用 A 行的页码往后翻」。
            // 已按用户要求**移除**：翻页入口就在上方工具行（页码 + 条数），
            // 状态栏再复述一遍只是噪音；而且「还有更多」这件事，
            // 上方「采集 N 行 / 本次读取 M 行」两个数字已经说清楚了。
            //
            // 这个位置保留为空 —— 将来若出现真正需要提示的状态
            // （例如采集覆盖不全导致窗口缺失），仍然从这里输出。
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
