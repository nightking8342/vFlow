package com.chaomixian.vflow.ui.chat

/**
 * **待发附件**（输入框上方那条预览条）的数据模型与判据 —— **纯函数，可单测**。
 *
 * 症状来源（真机）：在「+」面板里选了一张图之后，面板一收起输入框那里就**看不到
 * 已引用的图**，用户不知道当前带了什么图。面板内部本来就有选中态预览，缺的正是
 * 「面板收起后仍然可见」的这一层。
 *
 * ⚠️ 本文件**不读任何 Compose 状态、不碰 Context** —— 组装与判据都能在纯 JVM 单测里锁死。
 * 真正的 UI（缩略图 + 移除按钮）在 `ChatPendingAttachmentBar.kt`。
 */

/** 一项待发附件的来源形态。两种形态的**移除方式不同**，故必须区分。 */
internal enum class ChatPendingAttachmentKind {
    /** 相册 / 系统选择器给出的图：有 `content://` 或 `file://` uri。 */
    PHOTO,

    /** 相机 `TakePicturePreview` 拍下的图：只有 `Bitmap`，**没有 uri**。 */
    CAMERA,
}

/**
 * 预览条里的一项。
 *
 * ⚠️ [key] 必须**稳定且唯一** —— Compose 列表 key 与「点 × 该移除哪一项」都靠它。
 * 相册项用 `photo:<uri>`、相机项用固定常量 [CHAT_PENDING_CAMERA_KEY]，
 * 两者**前缀不同**，故即便某个 uri 恰好等于常量值也不会撞。
 */
internal data class ChatPendingAttachmentPreview(
    val key: String,
    val kind: ChatPendingAttachmentKind,
    /** 相册项的可加载 uri；相机项为 null（它由 `Bitmap` 直接画）。 */
    val uri: String?,
)

/** 相机项的固定 key（相机图**至多一张**，见 `ChatScreen` 的 `capturedPreview`）。 */
internal const val CHAT_PENDING_CAMERA_KEY = "camera:"

private const val CHAT_PENDING_PHOTO_KEY_PREFIX = "photo:"

/**
 * 把「当前待发的附件状态」组装成预览条要画的一串项。
 *
 * 顺序：**相机项在前、相册项在后** —— 与「+」面板的 carousel 一致（那里相机是 index 0），
 * 于是用户在面板里看到的排列与输入框上方的排列不会左右颠倒。
 *
 * @param photoUris 相册/选择器选中的 uri（字符串形态，与 `ChatScreen.selectedPhotoUris` 一一对应）
 * @param hasCapturedPreview 相机是否已有待发预览图
 */
internal fun chatPendingAttachmentPreviews(
    photoUris: List<String>,
    hasCapturedPreview: Boolean,
): List<ChatPendingAttachmentPreview> = buildList {
    if (hasCapturedPreview) {
        add(
            ChatPendingAttachmentPreview(
                key = CHAT_PENDING_CAMERA_KEY,
                kind = ChatPendingAttachmentKind.CAMERA,
                uri = null,
            )
        )
    }
    photoUris.forEach { uri ->
        add(
            ChatPendingAttachmentPreview(
                key = CHAT_PENDING_PHOTO_KEY_PREFIX + uri,
                kind = ChatPendingAttachmentKind.PHOTO,
                uri = uri,
            )
        )
    }
}

/**
 * 预览条的**可见性判据**：有内容才画。
 *
 * ⚠️ 刻意收成一个纯函数而不是在 UI 里写 `list.isNotEmpty() || preview != null`：
 * 发送按钮的可用性（「有图无字也能发」）与预览条的可见性**必须同源**，
 * 各写一份必然漂移（一处改了另一处忘）。
 */
internal fun chatShouldShowPendingAttachmentBar(
    previews: List<ChatPendingAttachmentPreview>,
): Boolean = previews.isNotEmpty()
