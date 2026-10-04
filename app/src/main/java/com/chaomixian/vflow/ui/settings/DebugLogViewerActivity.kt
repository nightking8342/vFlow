// 文件: main/java/com/chaomixian/vflow/ui/settings/DebugLogViewerActivity.kt
package com.chaomixian.vflow.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.WrapText
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.locale.LocaleManager
import com.chaomixian.vflow.core.locale.toast
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.chaomixian.vflow.ui.common.VFlowTheme

/**
 * 「查看日志」—— 就地展示**调试日志**的全文。
 *
 * ## 数据源与「导出日志」**同一个**
 *
 * 两者都调 [DebugLogger.getLogs]（设备信息头 + 内存 `logBuffer` + shell 日志文件），
 * 差别只在出口：那边写文件走 SAF，这边直接渲染。⚠️ **不要在这里另取数据源**
 * （例如只读 `logBuffer`）—— 那会让「我在文件里看到的」与「我在页面上看到的」
 * 不一致，而用户恰恰是拿这两处互相核对的。由 `DebugLogViewerWiringTest` 的
 * 源码扫描锁住这一点。
 *
 * ## ⚠️ 这里看到的是**未经过滤**的全量日志
 *
 * 工作流的「日志等级」（`WorkflowLogLevel`）**只管**本次执行的 `detailedLog`
 * （首页「最近日志」）。本页与「导出日志」一样读全局 logger，**刻意不受它影响** ——
 * 排障要的正是全量证据。⚠️ 于是「仅错误」档下会出现：首页日志里没有 `D` 上下文、
 * 而这里与导出文件里有。**这不是缺陷**，别当成过滤失效。
 * （另：`logBuffer` 只在设置里打开「调试日志」开关时才累加，关着时本页只剩
 * 设备信息头与 shell 日志。）
 *
 * ## 为什么是「新页面」而不是弹窗
 *
 * 调试日志动辄几千行（`logBuffer` 上限 5000 条）。对话框里滚动长文本既难操作、
 * 又拿不到全屏宽度（日志行普遍很长）。新页面还顺带解决了「想全选复制整段」的需求。
 */
class DebugLogViewerActivity : ComponentActivity() {

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getLanguage(newBase)
        val localizedContext = LocaleManager.applyLanguage(newBase, languageCode)
        super.attachBaseContext(AppearanceManager.applyDisplayScale(localizedContext))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VFlowTheme {
                DebugLogViewerScreen(
                    onBack = { finish() },
                    onCopy = ::copyLogs,
                )
            }
        }
    }

    private fun copyLogs(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("vFlow Debug Log", text))
        toast(R.string.toast_logs_copied)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogViewerScreen(
    onBack: () -> Unit,
    onCopy: (String) -> Unit,
) {
    // ⚠️ 用 `remember` 缓存一次快照而不是每次重组都调 `getLogs()`：
    //    后者要读 SharedPreferences 与 shell 日志文件（IO），
    //    放进组合里会在每次重组（滚动、旋转）都做一遍。
    //    刷新走右上角那个按钮，**显式**重取 —— 用户看得见「我刚刷过」。
    var logs by remember { mutableStateOf(DebugLogger.getLogs()) }

    // ⚠️ **默认开**（与 logcat 查看器相反，那个默认关）：
    //    本页展示的是**应用日志** —— 它里面有模块进度、JSON、脚本输出这类长行，
    //    不换行时会普遍横拖；而 logcat 那边逐行比对时间戳/级别更多，默认不换行更合适。
    //    两边默认值不同是有意的，别「统一」。
    var wrapLines by remember { mutableStateOf(true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_button_view_logs)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                },
                actions = {
                    // ⚠️ 用 IconToggleButton 而不是普通 IconButton：换行是个**开关**，
                    //    需要「当前是开还是关」的视觉反馈。用普通按钮的话，
                    //    用户点完只能靠内容排版变化去猜，而长行短行混排时看不出来。
                    IconToggleButton(
                        checked = wrapLines,
                        onCheckedChange = { wrapLines = it }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.WrapText,
                            contentDescription = stringResource(
                                if (wrapLines) R.string.logcat_wrap_on
                                else R.string.logcat_wrap_off
                            )
                        )
                    }
                    IconButton(onClick = { logs = DebugLogger.getLogs() }) {
                        Icon(
                            imageVector = Icons.Rounded.Refresh,
                            contentDescription = stringResource(R.string.settings_view_logs_refresh)
                        )
                    }
                    IconButton(onClick = { onCopy(logs) }) {
                        Icon(
                            imageVector = Icons.Rounded.ContentCopy,
                            contentDescription = stringResource(R.string.common_copy)
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (logs.isBlank()) {
                // ⚠️ 空状态必须给**可操作的解释**，而不是一片白：
                //    「调试日志开关关着」是最常见的原因（`logBuffer` 只在开关打开时累加）。
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.settings_view_logs_empty),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                // ⚠️ 两个模式的取舍（与 logcat 查看器同一套，见那里的注释）：
                // - **换行**：长行完整可见，代价是行与行的视觉对应变弱。
                // - **不换行 + 横向滚动**：保证「一行就是一行」，比对时间戳时更清楚，
                //   但长消息要横拖。
                // 没有哪个绝对更好 ⇒ 做成开关。⚠️ **横向滚动只能在不换行时加** ——
                // 两者同时开着时，`Text` 会按无穷宽测量（横滚给的约束），
                // 于是 `softWrap` 永远不触发，开关看起来点了没反应。
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .then(
                            if (wrapLines) Modifier
                            else Modifier.horizontalScroll(rememberScrollState())
                        )
                        .padding(12.dp)
                ) {
                    SelectionContainer {
                        Text(
                            text = logs,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                            softWrap = wrapLines,
                            // ⚠️ **不设 maxLines / 不截断** —— 与「导出日志」一致地给全量。
                            //    这里加任何截断都会让本页与导出文件对不上，
                            //    而两者互相核对正是本页存在的理由。
                        )
                    }
                }
            }
        }
    }
}
