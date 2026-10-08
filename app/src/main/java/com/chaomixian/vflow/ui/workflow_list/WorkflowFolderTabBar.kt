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
import androidx.compose.material.icons.outlined.FolderDelete
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.material.icons.outlined.FolderZip
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
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
fun WorkflowFolderTabBar(
    tabs: List<WorkflowFolderTab>,
    selectedFolderId: String,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String, Boolean) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否启用**文件夹管理菜单**（长按 Tab 唤出「重命名 / 导出 / 解散 / 删除」）。
     *
     * ⚠️⚠️ 图标选择页的分类栏必须传 `false` —— 它把**分类 id**（`action` 等）
     * 当成 `folderId` 复用这个组件，而下面的菜单判据是
     * 「`folderId != WORKFLOW_TAB_ALL` 就当它是真实文件夹」⇒ 不关掉的话
     * **长按任意分类都会弹出「删除文件夹」**，误点即数据全没（本项目无撤销）。
     *
     * ⚠️ 传空 lambda **不够** —— 菜单照样会弹出来，只是点了没反应，
     * 比不弹更让人困惑。必须能整个关掉。
     */
    showFolderMenu: Boolean = true,
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
            // ⚠️ `showFolderMenu == false` 时（图标分类栏）一律不算真实文件夹，
            //    否则分类 id 会被当成文件夹、弹出「删除文件夹」。
            val isRealFolder = showFolderMenu && tab.folderId != WORKFLOW_TAB_ALL
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
                    WorkflowFolderMenu(
                        expanded = menuExpanded,
                        folderId = tab.folderId,
                        onDismiss = { menuExpanded = false },
                        onRenameFolder = onRenameFolder,
                        onExportFolder = onExportFolder,
                        onDissolveFolder = onDissolveFolder,
                        onDeleteFolder = onDeleteFolder,
                    )
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
 *
 * ⚠️ `internal` 而非 `private`：液态玻璃版 Tab 栏（`WorkflowFolderGlassTabBar.kt`）
 * 要用**同一份**手势实现 —— 两处各写一份的话，「长按吃掉单击」那个坑会只在一边修好。
 */
internal fun Modifier.longPressForFolderMenu(
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

/**
 * 非消费型「点按」检测，把**按下位置**交给回调。
 *
 * ⚠️⚠️ **为什么玻璃版不能用 `Modifier.clickable` 挂在单个 Tab 上**：
 * 滑动指示块画在最上层、且正好盖住当前选中项，命中测试只走最上层 ⇒
 * 那一格 Tab 的 `clickable` **永远收不到点击**（表现是「点当前 Tab 什么都不会发生」，
 * 而点别的 Tab 正常 —— 极难从现象联想到是遮挡）。
 *
 * 挂在**整条栏**上、按 x 坐标反查就没有遮挡问题：整条栏是指示块的父节点，
 * 父子都会收到同一条指针流。
 *
 * ⚠️ 必须**不消费**事件 —— 指示块的拖拽手势（`inspectDragGestures`）与之共用
 * 同一条指针流，一旦这里 `consume()`，拖动就再也起不来。反过来，拖动时位移会
 * 超过 `touchSlop`，这里的判据自然不成立，不会误触发点击。
 * 两条手势通道因此互不相干：**按住拖 = 换 Tab，轻点 = 切换/弹菜单**。
 *
 * ⚠️⚠️ **必须走 `rememberUpdatedState`**：`pointerInput(Unit)` 的 block 只在
 * 首次组合时跑一次，里面捕获的 lambda 是**那一刻**的实例 —— 而调用方的 lambda
 * 每次都捕获了当次的 `selectedFolderId`。不更新的话判据永远拿**最初**的选中项：
 * 表现是「点第一个 Tab 没反应（它恰好是初始选中项）+ 点其他 Tab 只切换、从不弹菜单」，
 * 而这两条**都不报错**，极难从现象联想到是闭包过期。
 */
@Composable
internal fun Modifier.folderTabTapAt(
    onTap: (Offset) -> Unit,
): Modifier {
    val currentOnTap by rememberUpdatedState(onTap)
    return this.pointerInput(Unit) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            // ⚠️ 三条判据缺一不可，每条都对应一种误触/失灵：
            //   ① 等到**抬起**才算点按（不要 `withTimeout` —— 玻璃版没有「长按」语义了，
            //      按久一点再抬手仍应是点按）；
            //   ② 位移必须小于 `touchSlop` —— 否则滑动指示块的拖拽会连带触发一次「点按」
            //      （拖动结束时 `val` 已经变了，而这一格此时**正是**当前选中项）；
            //   ③ **不能消费事件** —— 消费了指示块的拖拽就再也起不来。
            val up = waitForUpOrCancellation() ?: return@awaitEachGesture
            if ((up.position - down.position).getDistance() <= viewConfiguration.touchSlop) {
                currentOnTap(down.position)
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

/**
 * 「重命名 / 导出 / 删除」这个菜单本体。
 *
 * ⚠️ 由两个 Tab 栏共用（普通版 `WorkflowFolderTabBar` 与液态玻璃版
 * `WorkflowFolderGlassTabBar`）—— 它挂在一个具体的 Tab 的 `Box` 里，
 * `DropdownMenuPopup` 是独立 `Popup` 窗口、不受 `horizontalScroll` 裁剪影响。
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
internal fun WorkflowFolderMenu(
    expanded: Boolean,
    folderId: String,
    onDismiss: () -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String, Boolean) -> Unit,
    /** 删掉文件夹本身，**里面的工作流保留**（移到根目录）。 */
    onDissolveFolder: (String) -> Unit,
    /** 删掉文件夹**连同里面所有工作流**。 */
    onDeleteFolder: (String) -> Unit,
) {
    DropdownMenuPopup(
        expanded = expanded,
        onDismissRequest = onDismiss,
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
                    onDismiss()
                    onRenameFolder(folderId)
                }
                FolderMenuItem(
                    text = stringResource(R.string.folder_export),
                    icon = Icons.Outlined.Download,
                ) {
                    onDismiss()
                    onExportFolder(folderId, false)
                }
                // fork（2026-10-08）：压缩包档 —— 唯一能带上自定义卡片图标的导出形式。
                // 与上面那项**并列**而不是做成子菜单：菜单本来就只有 4 项，
                // 多一层展开反而更难找。
                FolderMenuItem(
                    text = stringResource(R.string.folder_export_archive),
                    icon = Icons.Outlined.FolderZip,
                ) {
                    onDismiss()
                    onExportFolder(folderId, true)
                }
                // ⚠️ 「解散」与「删除」只差一件事：**里面的工作流保不保留**。
                //    图标刻意用成套的一对（FolderOff = 只去掉文件夹本身，
                //    FolderDelete = 连内容一起删），文案也点明差别 ——
                //    否则两者在菜单里看起来一样，用户点错就是数据全没。
                FolderMenuItem(
                    text = stringResource(R.string.folder_dissolve),
                    icon = Icons.Outlined.FolderOff,
                ) {
                    onDismiss()
                    onDissolveFolder(folderId)
                }
                FolderMenuItem(
                    text = stringResource(R.string.folder_delete),
                    icon = Icons.Outlined.FolderDelete,
                ) {
                    onDismiss()
                    onDeleteFolder(folderId)
                }
            }
        }
    }
}
