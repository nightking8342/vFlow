package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventTimeoutCancellationException
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.withTimeout
import com.chaomixian.vflow.R

/**
 * 内容区顶部的文件夹 Tab 栏（fork 新增，替代原来的「文件夹卡片 + 底部弹窗」）。
 *
 * ## 为什么改
 *
 * 原实现把文件夹做成**一张卡片混在内容流里**，点开是一个 `ModalBottomSheet`
 * （`FolderContentSheet`）。三个问题：
 *
 * 1. 它和工作流卡片混排，**占掉一整行**；文件夹多了以后要滚很久才看得到工作流。
 * 2. 「当前在看哪个文件夹」在弹窗关掉之后**完全不可见** —— 用户不知道自己还在筛选状态里。
 * 3. 每次切换都要开弹窗、等它加载。
 *
 * 改成 Tab 栏后这三点一并消失，且**数据层零改动**：仍然是 `WorkflowFolder` +
 * `Workflow.folderId`，只是入口形态换了。
 *
 * ## ⚠️ Tab 列表由调用方组装，「全部」是**隐含第一项**
 *
 * [WorkflowFolderTab] 列表是从真实文件夹派生的（`uiState.items` 里的 `FolderItem`），
 * 不该把「全部」这个 UI 概念混进数据派生逻辑。两个调用点（列表模式 / 紧凑模式）
 * 各自在 Tab 列表前面拼一个「全部」，`folderId` 用 [WORKFLOW_TAB_ALL] 这个哨兵串。
 *
 * ## ⚠️ 右端**没有**任何按钮（2026-10-05 改）
 *
 * 上一版在 Tab 栏右端放了一个「新建文件夹」圆钮，选中文件夹时还会再多一个 ⋮。
 * 用户指出两点：① 新建文件夹在**顶栏的更多菜单**里已经有了（`folder_create`），
 * 重复；② 切换文件夹后右端突然多出一个 ⋮，「不美观」。
 *
 * 现在：**右端什么都不放**。文件夹的「重命名 / 导出 / 删除」改成
 * **长按当前选中的那个 Tab** 唤出（见下方 `combinedClickable`）。
 *
 * 为什么选长按而不是「把菜单挂到右下角的 FAB」：
 * - FAB 属于**列表页整体**（新建工作流），而这三个动作只对**某个文件夹**有意义；
 *   挂上去之后用户点 FAB 的预期是「新建」，「删除文件夹」出现在同一个菜单里很反直觉。
 * - 长按的目标就是那个 Tab 本身，「操作这个文件夹」的指向**零歧义**。
 * - 代价是多了一步（要先切到该文件夹）—— 但反正只有选中它才谈得上操作它。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorkflowFolderTabBar(
    tabs: List<WorkflowFolderTab>,
    selectedFolderId: String,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuExpanded by remember { mutableStateOf(false) }
    var menuTarget by remember { mutableStateOf<String?>(null) }

    Row(
        modifier = modifier
            .padding(top = 4.dp, bottom = 4.dp)
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        tabs.forEach { tab ->
            val selected = selectedFolderId == tab.folderId
            // 长按菜单只对**真实文件夹**有意义 ——「全部」是伪 Tab，没有可操作的对象。
            val isRealFolder = tab.folderId != WORKFLOW_TAB_ALL
            // ⚠️ 每个 Tab 各自包一个 `Box`，菜单就挂在这个 `Box` 里 ——
            //    这样弹层从**被长按的那个 Tab 下方**弹出，而不是一律从 Tab 栏
            //    最左边弹出（用户 2026-10-05 指出）。
            //    `DropdownMenuPopup` 内部是独立 `Popup` 窗口，**不受 `horizontalScroll`
            //    的裁剪区影响**，所以挂在滚动容器内部是安全的。
            Box {
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(tab.folderId) },
                    modifier = Modifier.longPressForFolderMenu(enabled = isRealFolder) {
                        menuTarget = tab.folderId
                        menuExpanded = true
                    },
                    shape = RoundedCornerShape(999.dp),
                    // 这里**只用颜色**表达选中，不迭比缩放或位移：
                    // 筛选 Tab 的价值在于「一眼看出当前在哪一格」，
                    // 尺寸变化会让整条 Tab 栏在切换时抖动，反而看不清。
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                    ),
                    label = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            Text(
                                text = tab.name,
                                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            )
                            Text(
                                text = tab.workflowCount.toString(),
                                style = MaterialTheme.typography.labelSmall,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.onSecondaryContainer
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    },
                )

                if (isRealFolder && menuTarget == tab.folderId) {
                    DropdownMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false },
                    ) {
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(index = 0, count = 1),
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                                .widthIn(min = 156.dp, max = 236.dp),
                            containerColor = MenuDefaults.groupStandardContainerColor,
                        ) {
                            Column(modifier = Modifier.padding(vertical = 6.dp)) {
                                FolderMenuItem(
                                    text = stringResource(R.string.folder_rename),
                                    icon = Icons.Outlined.DriveFileRenameOutline,
                                ) {
                                    menuExpanded = false
                                    onRenameFolder(tab.folderId)
                                }
                                FolderMenuItem(
                                    text = stringResource(R.string.folder_export),
                                    icon = Icons.Outlined.Download,
                                ) {
                                    menuExpanded = false
                                    onExportFolder(tab.folderId)
                                }
                                FolderMenuItem(
                                    text = stringResource(R.string.folder_delete),
                                    icon = Icons.Outlined.DeleteOutline,
                                ) {
                                    menuExpanded = false
                                    onDeleteFolder(tab.folderId)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 只接管**长按**、把单击原样让给内部那个 `FilterChip`。
 *
 * ⚠️ 不能用 `Modifier.combinedClickable` / `detectTapGestures` ——
 * 它们建的是**消费型**手势检测器，会把同一个指针事件也吃掉，
 * 里层 `FilterChip` 的 `onClick` 就再也收不到（点 Tab 会完全没反应）。
 *
 * 这里用 `awaitEachGesture` 自己判：超时后仍在按压 ⇒ 算长按，否则**原样返回**
 * 让事件继续往下传。
 */
private fun Modifier.longPressForFolderMenu(
    enabled: Boolean,
    onLongPress: () -> Unit,
): Modifier = if (!enabled) {
    this
} else {
    this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val longPress = try {
                withTimeout(viewConfiguration.longPressTimeoutMillis) {
                    waitForUpOrCancellation()
                    null // 超时前抬手 ⇒ 不是长按
                }
            } catch (_: PointerEventTimeoutCancellationException) {
                down
            }
            if (longPress != null) {
                onLongPress()
            }
        }
    }
}

@Composable
private fun FolderMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(imageVector = icon, contentDescription = null)
            Text(text = text, style = MaterialTheme.typography.bodyLarge)
        }
    }
}
