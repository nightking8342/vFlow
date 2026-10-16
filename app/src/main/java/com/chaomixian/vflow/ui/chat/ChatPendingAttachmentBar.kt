package com.chaomixian.vflow.ui.chat

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chaomixian.vflow.R

/** 预览条里单张缩略图的边长。 */
private val CHAT_PENDING_THUMBNAIL_SIZE = 64.dp

/** 缩略图右上角「移除」徽标的边长。 */
private val CHAT_PENDING_REMOVE_BADGE_SIZE = 26.dp

/**
 * 预览条占用的**垂直高度**（含上下 padding）。
 *
 * ⚠️ 导出给 `ChatScreen` 用：预览条一出现，composer 就高出这一截，
 * 消息列表的底部留白与「滚到底」按钮的落点都必须跟着让出同样的量，
 * 否则最后一条消息会被预览条盖住。**改这里的尺寸要同步改使用点。**
 */
internal val CHAT_PENDING_ATTACHMENT_BAR_HEIGHT = CHAT_PENDING_THUMBNAIL_SIZE + 14.dp

/**
 * **输入框区域的「待发附件预览条」**（fork：任务 45 —— 症状 (a)）。
 *
 * 症状：在「+」面板里选了图，面板一收起输入框那里就**看不到已引用的图**。
 * 本组件补上这一层：缩略图 + 可移除，**面板开着或收起时都渲染**（它挂在 composer 卡片里，
 * 与 `ModalBottomSheet` 的可见性无关）。
 *
 * ⚠️ 数据源与「+」面板**同一份**（`selectedPhotoUris` / `capturedPreview`）——
 * 面板里勾选、面板外移除，改的都是同一处状态。
 *
 * ⚠️ 列表为空时**不渲染任何东西**（判据是纯函数 [chatShouldShowPendingAttachmentBar]，
 * 与「有图无字也能发」的发送按钮判据同源）。
 */
@Composable
internal fun ChatPendingAttachmentBar(
    previews: List<ChatPendingAttachmentPreview>,
    cameraPreview: Bitmap?,
    onRemove: (ChatPendingAttachmentPreview) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!chatShouldShowPendingAttachmentBar(previews)) return
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        previews.forEach { preview ->
            PendingAttachmentThumbnail(
                preview = preview,
                cameraPreview = cameraPreview,
                onRemove = { onRemove(preview) },
            )
        }
    }
}

@Composable
private fun PendingAttachmentThumbnail(
    preview: ChatPendingAttachmentPreview,
    cameraPreview: Bitmap?,
    onRemove: () -> Unit,
) {
    val shape = RoundedCornerShape(12.dp)
    val contentDescription = when (preview.kind) {
        ChatPendingAttachmentKind.PHOTO -> stringResource(R.string.chat_pending_attachment_photo_label)
        ChatPendingAttachmentKind.CAMERA -> stringResource(R.string.chat_pending_attachment_camera_label)
    }
    Box(modifier = Modifier.size(CHAT_PENDING_THUMBNAIL_SIZE)) {
        // 底：中性色 —— 图还没解码出来时不留「洞」。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .clip(shape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest)
        )
        when (preview.kind) {
            ChatPendingAttachmentKind.CAMERA -> {
                // ⚠️ 相机图**没有 uri**，只能拿 `Bitmap` 直接画。
                // 理论上有 CAMERA 项就说明 `capturedPreview != null`；但状态切换的那一帧
                // 可能取不到，故这里判空并保持中性底（不留洞、不崩）。
                val bitmap = cameraPreview
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = contentDescription,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(shape),
                    )
                }
            }

            ChatPendingAttachmentKind.PHOTO -> AsyncImage(
                model = preview.uri,
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .clip(shape),
            )
        }
        // 移除徽标：压在上层，点它只移除这一项。
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(2.dp)
                .size(CHAT_PENDING_REMOVE_BADGE_SIZE)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest.copy(alpha = 0.92f))
                .clickable(onClick = onRemove),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Rounded.Close,
                contentDescription = stringResource(R.string.chat_pending_attachment_remove),
                modifier = Modifier.size(16.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
