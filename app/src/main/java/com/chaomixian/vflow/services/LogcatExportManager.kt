package com.chaomixian.vflow.services

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.chaomixian.vflow.core.logcat.CaptureState
import com.chaomixian.vflow.core.logcat.LogcatExportRenderer
import com.chaomixian.vflow.core.logcat.LogcatFilter
import com.chaomixian.vflow.core.logcat.LogcatLine
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.utils.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * logcat 日志的导出：渲染（纯函数）+ 落盘 + 系统分享。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §5。
 *
 * 复用现成设施，**不新增机制**：
 * - 落盘目录 `/sdcard/vFlow/exports/`（照 `ChatConversationExport`）
 * - FileProvider authority `${applicationId}.provider`，路径已在 `provider_paths.xml` 里
 *   覆盖了 `vFlow/` **整棵树**，因此无需改那个文件
 * - 分享走 `ACTION_SEND` + `EXTRA_STREAM` + `FLAG_GRANT_READ_URI_PERMISSION`
 *
 * 内容渲染全部委托给 [LogcatExportRenderer]（纯函数、有单测），
 * 本类只做 IO——这样"导出内容长什么样"能被测试锁住。
 *
 * ## ⚠️ 与文档 §5 的一处偏离：导出的是**已加载的行**，而非拷贝采集文件
 *
 * 文档原写「采集态导出即文件拷贝（`cp`）」，本实现没这么做，因为那条路依赖一个
 * **未验证的前提**：
 *
 * | 方案 | 前提 | 风险 |
 * |---|---|---|
 * | 文件拷贝 | **App 进程能直接读 `/sdcard/vFlow/logs/`** | 应用与 shell 的存储权限不同——`StorageManager` 存在的理由就是"解决 Shell/App 权限差异"。这个前提从没验证过 |
 * | **渲染已加载的行**（本实现） | 无额外前提：行已经过 shell 通道送到内存了 | 无 |
 *
 * 渲染还有两个附带好处：
 * 1. **导出内容与界面所见一致**。拷贝整个文件会把用户没在看（甚至已过滤掉）的行
 *    也导出去，而用户点导出的场景恰恰是"我看到这几行有问题"。
 * 2. JSON 格式天然可用（文件拷贝给不了抓取条件段）。
 *
 * 代价：导出的是**已解析 + 已过滤**的内容。对"拿去给人看 / 贴 issue"
 * 这个用途而言不是损失。
 */
internal object LogcatExportManager {

    private const val TAG = "LogcatExport"


    /** 导出格式。文本为默认（§10 决策 6），JSON 放菜单。 */
    enum class Format(val extension: String, val mimeType: String) {
        TEXT("log", "text/plain"),
        JSON("json", "application/json"),
    }

    private val fileNameFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    /**
     * 导出并返回分享 Intent。
     *
     * **同步落盘**，调用方应在 IO 线程执行（日志量最大 2000 行，写入很快，
     * 但仍在 IO 线程做更稳妥）。
     *
     * @return 分享 Intent；写入失败返回 null（调用方负责提示）
     */
    fun buildShareIntent(
        context: Context,
        lines: List<LogcatLine>,
        filter: LogcatFilter,
        state: CaptureState,
        format: Format,
        capturedAtMs: Long = System.currentTimeMillis(),
    ): Intent? {
        val content = when (format) {
            Format.TEXT -> LogcatExportRenderer.renderText(lines)
            Format.JSON -> LogcatExportRenderer.renderJson(lines, filter, state, capturedAtMs)
        }

        val file = try {
            val dir = StorageManager.exportsDir
            File(dir, LogcatExportRenderer.buildFileName(fileNameFormat.format(Date(capturedAtMs)), format.extension))
                .apply { writeText(content) }
        } catch (t: Throwable) {
            // 最常见的原因是用户没给存储权限，或 /sdcard 不可写。
            // 不静默：调用方会把这个失败提示出来。
            DebugLogger.w(TAG, "写入导出文件失败", t)
            return null
        }

        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = format.mimeType
            putExtra(
                Intent.EXTRA_SUBJECT,
                LogcatExportRenderer.buildShareTitle(lines.size, format.extension),
            )
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            // clipData 是给某些不吃 EXTRA_STREAM 的接收方兜底
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }

        // 顺带把落盘路径记进日志，方便用户去文件管理器里找
        DebugLogger.i(TAG, "已导出 ${lines.size} 行到 ${file.absolutePath}")

        return Intent.createChooser(sendIntent, "分享日志").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
    }

}

