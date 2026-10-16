package com.chaomixian.vflow.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chaomixian.vflow.R

/** 用户气泡里缩略图的边长。 */
private val CHAT_IMAGE_THUMBNAIL_SIZE = 120.dp

/** 正文内联图的高度上限（宽度铺满，按原始比例缩放）。 */
private val CHAT_INLINE_IMAGE_MAX_HEIGHT = 360.dp

/**
 * **正文渲染**：按 `![图注](artifact://…)` 把标记切成「文本段 + 图段」，
 * 文本段仍交给 [ChatMarkdownContent]（版式一字不变），图段用 `AsyncImage` 画出来。
 *
 * fork：AI 能力统一 §3.9 —— 工具产出的图「给人看」走**正文内联引用**。
 *
 * ⚠️ **块级而非严格行内**：引用图独占一行。原因是 `ChatMarkdown.kt` 不在本任务的
 * 允许改动清单里（在那里补一个 `markdownComponents(image = …)` 约 5 行即可换成真行内）。
 * 功能不缺，只是视觉上不是 Markdown 原生的流内联。
 *
 * ⚠️ 图片加载用项目**已有**的 coil2（`coil-compose` 的 `AsyncImage`，与
 * `ui/workflow_list/WorkflowCardIcon.kt` 同款）⇒ **不新增依赖**。
 *
 * ⚠️ **点图放大**（fork：任务 45 —— 症状 (b)）：`onImageClick` 为 null 时图片不可点，
 * 保持「纯展示」；调用点（`ChatScreen`）传进来的模型可能是 null（句柄解析不到），
 * 那种情况由查看器显示占位，故这里**照样把点击传出去**。
 */
@Composable
internal fun ChatMarkdownWithImages(
    markdown: String,
    resolveHandle: (String) -> String?,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
    onImageClick: ((String?) -> Unit)? = null,
) {
    val segments = remember(markdown) { ChatArtifactReferences.parse(markdown) }
    if (segments.none { it is ChatBodySegment.Image }) {
        // 没有引用 ⇒ 走原路径（文字段与改造前逐像素一致）。
        ChatMarkdownContent(markdown = markdown, modifier = modifier, contentColor = contentColor)
        return
    }
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        segments.forEach { segment ->
            when (segment) {
                is ChatBodySegment.Text -> {
                    // 切段会在引用前后留下空白文本段 ⇒ 别画成空隙。
                    if (segment.markdown.isNotBlank()) {
                        ChatMarkdownContent(markdown = segment.markdown, contentColor = contentColor)
                    }
                }

                is ChatBodySegment.Image -> ChatInlineImage(
                    reference = segment.reference,
                    resolveHandle = resolveHandle,
                    onImageClick = onImageClick,
                )
            }
        }
    }
}

/**
 * 用户消息里的图（缩略图行）。
 *
 * 直接吃 `attachment.uri` —— 发送前已落盘（`ChatViewModel.persistUserAttachments`），
 * 故重启后仍是可读的 `file://` 路径。
 */
@Composable
internal fun ChatAttachmentThumbnails(
    attachments: List<ChatImageAttachment>,
    modifier: Modifier = Modifier,
    onImageClick: ((String?) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        attachments.forEach { attachment ->
            ChatImageThumbnail(
                model = attachment.uri,
                contentDescription = attachment.label,
                onClick = onImageClick,
            )
        }
    }
}

/**
 * **Agent 截图消息**：左对齐的**中性卡片**。
 *
 * ⚠️ 刻意不复用用户气泡：截图是 Agent 自己产出的，渲染成「你」发的蓝气泡会让用户
 * 以为那是自己上传的图。标题用 [R.string.chat_agent_screenshot_label] 说明来源。
 */
@Composable
internal fun ChatAgentScreenshotCard(
    attachments: List<ChatImageAttachment>,
    modifier: Modifier = Modifier,
    onImageClick: ((String?) -> Unit)? = null,
) {
    if (attachments.isEmpty()) return
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.Start,
    ) {
        Text(
            text = stringResource(R.string.chat_agent_screenshot_label),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier
                    .horizontalScroll(rememberScrollState())
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                attachments.forEach { attachment ->
                    ChatImageThumbnail(
                        model = attachment.uri,
                        contentDescription = attachment.label,
                        onClick = onImageClick,
                    )
                }
            }
        }
    }
}

// ── 内部 ────────────────────────────────────────────────────

@Composable
private fun ChatInlineImage(
    reference: ChatInlineImageReference,
    resolveHandle: (String) -> String?,
    onImageClick: ((String?) -> Unit)? = null,
) {
    // 句柄不会变，故按句柄缓存解析结果（解析 = 内存映射查表 + 一次 File.exists）。
    val resolved = remember(reference.handle) { resolveHandle(reference.handle) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        ChatImageBlock(
            model = resolved,
            onClick = onImageClick?.let { click -> { click(resolved) } },
        )
        if (reference.caption.isNotBlank()) {
            Text(
                text = reference.caption,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ChatImageThumbnail(
    model: String?,
    contentDescription: String?,
    size: Dp = CHAT_IMAGE_THUMBNAIL_SIZE,
    onClick: ((String?) -> Unit)? = null,
) {
    var failed by remember(model) { mutableStateOf(false) }
    if (model.isNullOrBlank() || failed) {
        UnavailableImageBox(modifier = Modifier.size(size))
        return
    }
    AsyncImage(
        model = model,
        contentDescription = contentDescription?.takeIf { it.isNotBlank() },
        contentScale = ContentScale.Crop,
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .imageClickModifier(onClick = onClick?.let { click -> { click(model) } }),
        onError = { failed = true },
    )
}

@Composable
private fun ChatImageBlock(
    model: String?,
    onClick: (() -> Unit)? = null,
) {
    var failed by remember(model) { mutableStateOf(false) }
    if (model.isNullOrBlank() || failed) {
        UnavailableImageBox(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 56.dp),
        )
        return
    }
    AsyncImage(
        model = model,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(max = CHAT_INLINE_IMAGE_MAX_HEIGHT)
            .clip(RoundedCornerShape(16.dp))
            .imageClickModifier(onClick = onClick),
        onError = { failed = true },
    )
}

/**
 * 「点图放大」的公共修饰：**只有调用点要了点击时**才挂 `clickable`。
 *
 * ⚠️ 两个调用点都在「图确实存在」的分支里（model 为空时提前 return 走占位），
 * 故这里不必再判空 —— 「图片不可用」的占位**不可点**，符合直觉。
 */
private fun Modifier.imageClickModifier(onClick: (() -> Unit)?): Modifier =
    if (onClick == null) this else clickable(onClick = onClick)

/**
 * 读不到图时的占位。
 *
 * ⚠️ 必须存在：附件目录**不在备份范围内**（`core/backup` 本任务只读），
 * 从备份恢复后、或文件被外部删掉后，正文里的引用会指向不存在的文件。
 * 没有它就会留一块空白，用户看不出「这里本该有张图」。
 */
@Composable
private fun UnavailableImageBox(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = stringResource(R.string.chat_image_unavailable),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}
