package com.chaomixian.vflow.ui.chat

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.content.FileProvider
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.utils.StorageManager
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 单条会话的完整导出载荷。
 *
 * 会话数据本身已由 [ChatSessionState] 全量保存，这里原样搬运、不做裁剪，
 * 以保证 role / reasoningContent / toolCalls.argumentsJson / toolResult.outputText
 * 等分析所需的字段全部保真。
 */
@Serializable
internal data class ChatConversationExportPayload(
    val exportVersion: Int = 1,
    val exportedAtMillis: Long,
    val appPackageName: String,
    val appVersionName: String? = null,
    val appVersionCode: Long? = null,
    val conversation: ChatConversation,
)

/**
 * 会话导出：把一条 [ChatConversation] 写成 JSON 落到 `/sdcard/vFlow/exports/`，再交给系统分享。
 *
 * 与 [ChatBenchmarkExportManager] 保持同一套路（FileProvider + ACTION_SEND），
 * 区别只在于落盘目录用 [StorageManager]，导出的文件在手机上可被文件管理器直接找到。
 */
internal object ChatConversationExportManager {
    private const val EXPORT_DIR_NAME = "exports"

    private val exportJson = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
    }
    private val exportDateFormat = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)

    fun buildShareIntent(
        context: Context,
        conversation: ChatConversation,
    ): Intent {
        val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
        val payload = buildExportPayload(
            conversation = conversation,
            packageName = context.packageName,
            versionName = packageInfo.versionName,
            versionCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                packageInfo.longVersionCode
            } else {
                @Suppress("DEPRECATION")
                packageInfo.versionCode.toLong()
            },
        )
        val exportsDir = File(StorageManager.rootDir, EXPORT_DIR_NAME).apply { mkdirs() }
        val file = File(exportsDir, buildExportFileName(conversation))
        file.writeText(encodeExportPayload(payload))

        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.provider",
            file,
        )
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/json"
            putExtra(Intent.EXTRA_SUBJECT, buildShareTitle(conversation))
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            clipData = ClipData.newUri(context.contentResolver, file.name, uri)
        }
        return Intent.createChooser(sendIntent, context.getString(R.string.chat_export_share_title))
    }

    internal fun buildExportPayload(
        conversation: ChatConversation,
        packageName: String,
        versionName: String?,
        versionCode: Long?,
    ): ChatConversationExportPayload {
        return ChatConversationExportPayload(
            exportedAtMillis = System.currentTimeMillis(),
            appPackageName = packageName,
            appVersionName = versionName,
            appVersionCode = versionCode,
            conversation = conversation,
        )
    }

    internal fun encodeExportPayload(
        payload: ChatConversationExportPayload,
    ): String {
        return exportJson.encodeToString(payload)
    }

    /**
     * 文件名形如 `vflow-chat-<slug>-20260913-104900.json`。
     * 标题是中文时 slug 会退化为空串，此时用会话 id 前缀兜底，保证文件名始终可辨识且唯一。
     */
    internal fun buildExportFileName(conversation: ChatConversation): String {
        val timestamp = exportDateFormat.format(Date(conversation.updatedAtMillis))
        val slug = conversation.title
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        return "vflow-chat-${slug.ifBlank { conversation.id.take(8) }}-$timestamp.json"
    }

    /** 会话在系统分享面板里的展示名。 */
    internal fun buildShareTitle(conversation: ChatConversation): String {
        return "vFlow chat export ${conversation.title}"
    }
}
