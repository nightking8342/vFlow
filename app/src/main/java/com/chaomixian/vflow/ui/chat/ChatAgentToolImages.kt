package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.logging.DebugLogger
import java.util.Locale

private const val LOG_TAG = "ChatImage"

/**
 * `ChatToolResult.artifacts` 里代表「图片」的 `typeLabel`
 * （由 `ChatAgentModuleExecutor.kt` 的 `chatArtifactTypeLabel` 产生）。
 */
internal const val CHAT_IMAGE_ARTIFACT_TYPE_LABEL = "image"

/**
 * 由 uri / 路径推断 mime。
 *
 * 存在的理由：`ChatImageAttachment.mimeType` 的默认值是 `image/jpeg`
 * （`ChatModels.kt`），照默认构造会把 **PNG 字节标成 JPEG** —— 那是静默的
 * `media_type` 不符（Anthropic 会按 `media_type` 解码，OpenAI 会把它塞进 `data:` URL）。
 *
 * 已知形态都可判：截图 `.png`（主屏）/ `.jpg`（虚拟屏）、相机 `.jpg`。
 * 无扩展名（含 `content://media/.../1234` 这种）⇒ 按 `image/jpeg` 处理并记 W
 * —— 调用方在有 `ContentResolver` 时应当先问 `getType`（见 `ChatScreen`）。
 */
internal fun chatImageMimeTypeForUri(
    uri: String,
    log: (String) -> Unit = { DebugLogger.w(LOG_TAG, it) },
): String {
    // 去掉 query / fragment，避免 `x.png?v=2` 被当成扩展名 `png?v=2`。
    val path = uri.trim().substringBefore('?').substringBefore('#')
    return when (path.substringAfterLast('.', "").lowercase(Locale.ROOT)) {
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> {
            log("Cannot infer image mime type from `$uri`; falling back to image/jpeg.")
            "image/jpeg"
        }
    }
}

/**
 * **工具产出的图** → AGENT 档附件（纯函数）。
 *
 * 这是「Agent 截图怎么进请求」的**入口**（fork：AI 能力统一 §3.1 结论）：
 * 工具结果里的 `image` 类 artifact 被收成附件，由调用方挂到一条
 * `role = USER`、`source = AGENT` 的消息上 —— 只有 user 角色在三协议里
 * 都支持图片 part（OpenAI 的 `role:"tool"` content 只能是字符串）。
 *
 * @param imageUriForHandle 生产实现 = `artifactStore.resolve(handle) as? VImage` 的 `uriString`。
 *   解析不到（句柄过期 / 不是 VImage）⇒ **跳过并记 W，不抛**：一张拿不到的图
 *   不该让整批工具结果失败。
 * @return 按工具结果顺序排列的附件；`label` = 句柄（占位文案要靠它指认是哪一张）。
 */
internal fun chatAgentToolImageAttachments(
    toolResults: List<ChatToolResult>,
    imageUriForHandle: (String) -> String?,
): List<ChatImageAttachment> {
    val attachments = mutableListOf<ChatImageAttachment>()
    toolResults.forEach { toolResult ->
        toolResult.artifacts
            .filter { it.typeLabel == CHAT_IMAGE_ARTIFACT_TYPE_LABEL }
            .forEach { artifact ->
                val uri = imageUriForHandle(artifact.handle)
                if (uri.isNullOrBlank()) {
                    DebugLogger.w(
                        LOG_TAG,
                        "Tool image artifact ${artifact.handle} could not be resolved; skipping it.",
                    )
                    return@forEach
                }
                attachments += ChatImageAttachment(
                    uri = uri,
                    source = ChatImageSource.AGENT,
                    mimeType = chatImageMimeTypeForUri(uri),
                    label = artifact.handle,
                )
            }
    }
    return attachments
}
