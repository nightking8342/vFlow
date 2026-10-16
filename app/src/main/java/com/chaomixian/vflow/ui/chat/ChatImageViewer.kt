package com.chaomixian.vflow.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import com.chaomixian.vflow.R

/** 放大查看的最小/最大缩放倍数。 */
private const val CHAT_IMAGE_VIEWER_MIN_SCALE = 1f
private const val CHAT_IMAGE_VIEWER_MAX_SCALE = 6f

/**
 * 「点开看大图」的请求。
 *
 * ⚠️ [model] 允许为 **null** 是**防御性**的：正文内联引用的句柄可能解析不到（附件目录不在
 * 备份范围内，恢复后文件可能不存在），而 `ChatInlineImages` 那条路径在解析不到时画的是
 * 「图片不可用」占位、不挂点击。查看器仍兜住 null ⇒ 显示占位而不是空白黑屏。
 */
internal data class ChatImagePreviewRequest(
    val model: String?,
    val caption: String? = null,
)

/**
 * **全屏图片查看器**（fork：任务 45 —— 症状 (b)）。
 *
 * 症状：对话里的图（用户发的图 + Agent 正文内联引用的图）点了没反应、不能放大。
 * 本组件补上：全屏黑底 + 双指缩放/拖动 + 右上角关闭 + 点空白处关闭。
 *
 * ⚠️ 用 `usePlatformDefaultWidth = false` 才能铺满整屏（默认的 `Dialog` 只有一个小窗口）。
 * ⚠️ 缩放是**加分项**，不做也能满足「看大图 + 关闭」；做了则必须是**纯手势**，
 * 不改任何状态（关闭即复位，因为整棵子树随对话框一起销毁）。
 */
@Composable
internal fun ChatImageViewer(
    request: ChatImagePreviewRequest,
    onDismiss: () -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        var scale by remember { mutableFloatStateOf(CHAT_IMAGE_VIEWER_MIN_SCALE) }
        var offset by remember { mutableStateOf(Offset.Zero) }
        val transformState = rememberTransformableState { zoomChange, panChange, _ ->
            scale = (scale * zoomChange).coerceIn(CHAT_IMAGE_VIEWER_MIN_SCALE, CHAT_IMAGE_VIEWER_MAX_SCALE)
            // 缩回 1x 时把位移归零，否则图会停在屏幕外（看起来像「图没了」）。
            offset = if (scale <= CHAT_IMAGE_VIEWER_MIN_SCALE) Offset.Zero else offset + panChange
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.96f))
                // 点任意空白处关闭（捏合/拖动不会触发 click，故不与缩放冲突）。
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center,
        ) {
            if (request.model.isNullOrBlank()) {
                Text(
                    text = stringResource(R.string.chat_image_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            } else {
                AsyncImage(
                    model = request.model,
                    contentDescription = request.caption?.takeIf { it.isNotBlank() },
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer(
                            scaleX = scale,
                            scaleY = scale,
                            translationX = offset.x,
                            translationY = offset.y,
                        )
                        .transformable(transformState),
                )
            }

            if (!request.caption.isNullOrBlank()) {
                Text(
                    text = request.caption,
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 28.dp),
                )
            }

            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(8.dp)
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.16f)),
                contentAlignment = Alignment.Center,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = stringResource(R.string.chat_image_viewer_close),
                        tint = Color.White,
                    )
                }
            }
        }
    }
}
