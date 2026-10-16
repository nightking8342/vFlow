package com.chaomixian.vflow.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64

/**
 * 三协议都认的图片 mime 白名单。
 *
 * 不在白名单里的（相册常见的 `image/heic` 等）**不直接发**：先重编码为 JPEG 再按
 * `image/jpeg` 发送——否则服务端会以「不支持的 media type」整条请求报错。
 *
 * ⚠️ 该重编码**是有损的**（丢画质 / 丢 alpha / HEIC→JPEG 还会丢 HDR 与位深），
 * 父会话 2026-10-16 已确认接受（见方案 §4.4 已知限制 2）。
 */
internal val CHAT_SUPPORTED_IMAGE_MIME_TYPES = setOf("image/jpeg", "image/png", "image/webp", "image/gif")

/**
 * 附件 → 原始字节。**读不到返回 `null`**（⇒ `UNREADABLE` 占位），**不抛异常**。
 *
 * 抽成接口是为了让「预算 + 5MB 兜底」的编排逻辑可以在纯 JVM 单测里跑
 * （默认实现依赖 `ContentResolver` / `File`，单测环境里 `android.*` 是 not-mocked 桩）。
 */
internal fun interface ChatImageByteSource {
    fun readBytes(attachment: ChatImageAttachment): ByteArray?
}

/**
 * 把图片重新编码成 `≤ maxBase64Chars` 的 base64。解码失败或仍超限返回 `null`。
 *
 * 只在两种情形下被调用：① base64 超单图上限；② mime 不在白名单。
 */
internal fun interface ChatImageShrinker {
    fun shrinkToBase64(attachment: ChatImageAttachment, bytes: ByteArray, maxBase64Chars: Int): String?
}

/**
 * 默认字节来源：`file://` / 绝对路径走 `java.io.File`（**不依赖 Context**）；
 * `content://` 走 [LogManager.applicationContext] 的 `ContentResolver`。
 *
 * 整体 `runCatching`：未初始化（`LogManager` 的 `lateinit`）/ 无权限 / 内容提供者报错
 * 一律 ⇒ `null` ⇒ 占位文案。**不崩、不静默**（占位文案会告诉模型这张图读不到）。
 */
internal object UriChatImageByteSource : ChatImageByteSource {
    override fun readBytes(attachment: ChatImageAttachment): ByteArray? = runCatching {
        val rawUri = attachment.uri.trim()
        when {
            rawUri.startsWith("content://") -> {
                val resolver = LogManager.applicationContext.contentResolver
                resolver.openInputStream(Uri.parse(rawUri))?.use { it.readBytes() }
            }

            else -> {
                val path = rawUri.removePrefix("file://")
                val file = File(path)
                if (file.isFile) file.readBytes() else null
            }
        }
    }.getOrNull()
}

/**
 * 默认缩压实现：`BitmapFactory`（`inSampleSize` 2/4/8）× JPEG 质量 85/70/50 逐步试，
 * **首个 ≤ 上限即返回**。
 *
 * ⚠️ **有损**（见 [CHAT_SUPPORTED_IMAGE_MIME_TYPES] 的说明）。
 * ⚠️ 本实现依赖 `android.graphics`，**单测环境不可用**（not-mocked 桩）⇒
 * 单测只覆盖「判定 + 编排」（注入 fake shrinker），重编码本身由真机验证。
 */
internal object AndroidChatImageShrinker : ChatImageShrinker {
    private val sampleSizes = listOf(2, 4, 8)
    private val jpegQualities = listOf(85, 70, 50)

    override fun shrinkToBase64(
        attachment: ChatImageAttachment,
        bytes: ByteArray,
        maxBase64Chars: Int,
    ): String? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        sampleSizes.forEach { sampleSize ->
            val options = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = runCatching {
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
            }.getOrNull() ?: return@forEach
            try {
                jpegQualities.forEach { quality ->
                    val output = ByteArrayOutputStream()
                    bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)
                    val encoded = Base64.getEncoder().encodeToString(output.toByteArray())
                    if (encoded.length <= maxBase64Chars) return encoded
                }
            } finally {
                bitmap.recycle()
            }
        }
        return null
    }
}

/**
 * 把「历史里的附件引用」变成「本次请求真正要发的图片」。
 *
 * **本类是整条链路上唯一做文件 IO 的地方**（[plan]），故调用方必须保证它不在 Main 线程上跑
 * （`ChatCompletionClient.buildRequest` 用 `withContext(Dispatchers.IO)` 包住）。
 *
 * 判定口径（**决策已定**）：
 * - 上限按 **base64 字符串长度 ≤ [maxBase64Chars]**；**恰好等于上限不缩**。
 * - **正常路径不解码、不重编码**（保持 D19「默认不缩」与 `AgentUtils.kt:198` 的原意：
 *   token 中性 + 文字更清晰）。
 * - 触发缩压只有两种情形：① base64 超上限；② mime 不在白名单。
 *   两种都失败 ⇒ `TOO_LARGE`（①）/ `UNREADABLE`（②）占位。
 */
internal class ChatImageResolver(
    private val byteSource: ChatImageByteSource = UriChatImageByteSource,
    private val shrinker: ChatImageShrinker = AndroidChatImageShrinker,
    private val maxBase64Chars: Int = CHAT_MAX_IMAGE_BASE64_CHARS,
) {
    /**
     * 读历史 → 预算裁剪 → **只为「保留」的图读字节** → 生成计划。
     *
     * 顺序很重要：先裁剪再读字节，被裁掉的图**完全不碰文件系统**
     * （省掉 N 次无用 IO，也不会因为一张读不到的旧图而改变裁剪结果）。
     */
    fun plan(history: List<ChatMessage>, budget: ChatImageBudget): ChatImagePlan {
        val decisions = ChatImageBudgetPlanner.plan(history, budget)
        if (decisions.isEmpty()) return ChatImagePlan.EMPTY

        val byMessage = linkedMapOf<String, MutableList<ChatPlannedImage>>()
        decisions.forEach { decision ->
            val planned = when (decision) {
                is ChatImageDecision.Keep -> resolveAttachment(decision.attachment)
                is ChatImageDecision.Omit -> ChatPlannedImage.Omitted(
                    attachment = decision.attachment,
                    reason = decision.reason,
                    placeholderText = decision.placeholderText,
                )
            }
            byMessage.getOrPut(decision.messageId) { mutableListOf() } += planned
        }

        val plan = ChatImagePlan(byMessage)
        // ⚠️ 任何日志都**不得**出现 base64（只记 id / 计数 / 分布）。
        DebugLogger.i(
            LOG_TAG,
            "Image plan messagesWithImages=${byMessage.size} kept=${plan.keptCount()} " +
                "omittedBudget=${plan.omittedCount(ChatImageOmissionReason.BUDGET)} " +
                "omittedUnreadable=${plan.omittedCount(ChatImageOmissionReason.UNREADABLE)} " +
                "omittedTooLarge=${plan.omittedCount(ChatImageOmissionReason.TOO_LARGE)}",
        )
        return plan
    }

    /**
     * 单张图的归宿：
     * 1. 读不到 ⇒ `UNREADABLE`；
     * 2. mime 在白名单且 base64 ≤ 上限 ⇒ **原样保留（不缩）**；
     * 3. 否则调 shrinker；仍失败 ⇒ `TOO_LARGE`（①）/ `UNREADABLE`（②）。
     */
    fun resolveAttachment(attachment: ChatImageAttachment): ChatPlannedImage {
        val bytes = byteSource.readBytes(attachment)
            ?: return omitted(attachment, ChatImageOmissionReason.UNREADABLE)

        val mimeType = normalizedMimeType(attachment)
        if (mimeType in CHAT_SUPPORTED_IMAGE_MIME_TYPES) {
            val encoded = Base64.getEncoder().encodeToString(bytes)
            if (encoded.length <= maxBase64Chars) {
                return ChatPlannedImage.Kept(attachment, ChatImagePayload(mimeType, encoded))
            }
            val shrunk = shrinker.shrinkToBase64(attachment, bytes, maxBase64Chars)
                ?: return omitted(attachment, ChatImageOmissionReason.TOO_LARGE)
            return ChatPlannedImage.Kept(attachment, ChatImagePayload("image/jpeg", shrunk))
        }

        // mime 不在白名单：重编码为 JPEG（有损，见 CHAT_SUPPORTED_IMAGE_MIME_TYPES）
        val shrunk = shrinker.shrinkToBase64(attachment, bytes, maxBase64Chars)
            ?: return omitted(attachment, ChatImageOmissionReason.UNREADABLE)
        return ChatPlannedImage.Kept(attachment, ChatImagePayload("image/jpeg", shrunk))
    }

    private fun omitted(
        attachment: ChatImageAttachment,
        reason: ChatImageOmissionReason,
    ): ChatPlannedImage.Omitted {
        DebugLogger.w(
            LOG_TAG,
            "Image omitted reason=$reason id=${attachment.id} uri=${attachment.uri}",
        )
        return ChatPlannedImage.Omitted(
            attachment = attachment,
            reason = reason,
            placeholderText = ChatImagePlaceholder.text(reason, attachment),
        )
    }

    /** `image/jpeg; charset=…` 之类的参数不影响白名单判定。 */
    private fun normalizedMimeType(attachment: ChatImageAttachment): String =
        attachment.mimeType.substringBefore(';').trim().lowercase()

    private companion object {
        private const val LOG_TAG = "ChatImage"
    }
}
