package com.chaomixian.vflow.ui.workflow_list

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyStaggeredGridState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material.icons.automirrored.outlined.AddToHomeScreen
import androidx.compose.material.icons.automirrored.outlined.DriveFileMove
import androidx.compose.material.icons.outlined.Badge
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.DashboardCustomize
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.DriveFileRenameOutline
import androidx.compose.material.icons.outlined.ToggleOn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.WorkflowVisuals
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.common.SearchBarCard
import com.chaomixian.vflow.ui.common.SearchEmptyStateCard
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.chaomixian.vflow.ui.main.WorkflowLayoutMode
import com.chaomixian.vflow.ui.common.matchesSearch
import com.chaomixian.vflow.ui.common.normalizeSearchQuery
import com.chaomixian.vflow.ui.viewmodel.WorkflowListUiState
import com.chaomixian.vflow.ui.workflow_list.WorkflowListItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.ReorderableLazyStaggeredGridState
import sh.calvin.reorderable.rememberReorderableLazyListState
import sh.calvin.reorderable.rememberReorderableLazyStaggeredGridState

data class WorkflowListScreenActions(
    val onCreateWorkflow: () -> Unit,
    val onOpenWorkflow: (Workflow) -> Unit,
    val onToggleFavorite: (Workflow) -> Unit,
    val onToggleEnabled: (Workflow, Boolean) -> Unit,
    val onDeleteWorkflow: (Workflow) -> Unit,
    val onDuplicateWorkflow: (Workflow) -> Unit,
    val onExportWorkflow: (Workflow) -> Unit,
    val onExecuteWorkflow: (Workflow) -> Unit,
    val onExecuteWorkflowDelayed: (Workflow, Long) -> Unit,
    val onAddShortcut: (Workflow) -> Unit,
    /**
     * fork（2026-10-06）：磁贴拆成**两个池**，故本回调带上 `kind`。
     *
     * ⚠️ 两个池的菜单项**显隐判据由调用点各自传 `TileGate.accepts(...)` 决定**，
     * 这里只负责把「哪一池」带到下一步（`WorkflowListRoute` 据此分段列槽位）。
     * ⚠️ 判据必须只有一处（`TileGate`）—— 各写各的会出现
     * 「菜单项显示着、点了却被拒绝」（§4.6 闸 1 vs 闸 3 不一致）。
     */
    val onAddToTile: (Workflow, TileKind) -> Unit,
    val onCopyWorkflowId: (Workflow) -> Unit,
    val onMoveWorkflowToFolder: (Workflow) -> Unit,
    /**
     * fork：文件夹 Tab 栏右侧的「新建文件夹」按钮（与顶栏菜单里的同名动作并存）。
     *
     * ⚠️ 下面四个 `*Folder` 动作（重命名 / 导出 / 解散 / 删除）**原本挂在文件夹卡片的
     * 菜单上**。文件夹改成 Tab 栏后卡片没了，若不搬过来，这几个功能会**静默消失** ——
     * 用户不会收到任何提示，只是找不到入口。
     */
    val onRenameFolder: (String) -> Unit,
    val onExportFolder: (String) -> Unit,
    /**
     * 删掉文件夹本身，**里面的工作流保留**（移到根目录）。
     * 与 [onDeleteFolder] 的区别只有一条：工作流保不保留。
     */
    val onDissolveFolder: (String) -> Unit,
    /** 删掉文件夹**连同里面所有工作流**。 */
    val onDeleteFolder: (String) -> Unit,
    val onPersistWorkflowOrder: (List<Workflow>) -> Unit,
)

data class WorkflowMenuItemAction(
    val textRes: Int,
    val icon: ImageVector,
    val onClick: () -> Unit,
)

@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
fun WorkflowListScreen(
    uiState: WorkflowListUiState,
    layoutMode: WorkflowLayoutMode,
    isWideLayout: Boolean = false,
    // 液态玻璃开关（`AppearanceManager.isLiquidGlassNavBarEnabled`）。为 true 时
    // 文件夹 Tab 栏换成 `WorkflowFolderGlassTabBar`；两种布局共用这一个开关。
    liquidGlassEnabled: Boolean = false,
    actions: WorkflowListScreenActions,
    extraBottomPadding: Dp = 0.dp,
    modifier: Modifier = Modifier
) {
    var searchQuery by rememberSaveable { mutableStateOf("") }
    val normalizedQuery = remember(searchQuery) { normalizeSearchQuery(searchQuery) }
    val focusManager = LocalFocusManager.current
    val displayItems = remember { mutableStateListOf<WorkflowListItem>() }
    val lazyListState = rememberLazyListState()
    val lazyStaggeredGridState = rememberLazyStaggeredGridState()
    val scope = rememberCoroutineScope()
    val isSearching = normalizedQuery.isNotBlank()
    var showLoadingCard by remember { mutableStateOf(false) }

    // 文件夹 Tab 栏的选中项：`WORKFLOW_TAB_ALL` 哨兵串 = 全部，否则是文件夹 id。
    var selectedFolderTab by rememberSaveable { mutableStateOf(WORKFLOW_TAB_ALL) }
    val folderTabs = remember(uiState.items) {
        uiState.items
            .filterIsInstance<WorkflowListItem.FolderItem>()
            .map { folder -> WorkflowFolderTab(folder.folder.id, folder.folder.name, folder.workflowCount) }
    }
    val knownFolderIds = remember(folderTabs) { folderTabs.map { it.folderId }.toSet() }
    val allTabLabel = stringResource(R.string.workflow_tab_all)
    // ⚠️「全部」的计数 = **所有**工作流（含根目录下未归类的），
    //    不能取各文件夹之和（那只数了 `FolderItem` 里的），也不能拿 `filteredItems`
    //    （那是**当前选中 Tab 过滤后**的结果）。
    //
    // ⚠️⚠️ **源必须是 `uiState.items`，不能是 `displayItems`**：后者是
    //    `SnapshotStateList`，拿它**本身**当 `remember` 的 key 只认引用变化，
    //    而它的引用恒定不变 ⇒ 计数在首帧（列表还是空的）就算死了，之后**永远是 0**
    //    （且不报错，只是显示不对）。`uiState.items` 是 StateFlow 里的不可变 `List`，
    //    每次发射都是新引用，key 才会真的失效重算。
    val allWorkflowCount = remember(uiState.items) {
        buildList {
            uiState.items.forEach { item ->
                when (item) {
                    is WorkflowListItem.WorkflowItem -> add(item.workflow)
                    is WorkflowListItem.FolderItem -> addAll(item.childWorkflows)
                }
            }
        }.size
    }
    val tabItems = remember(folderTabs, allTabLabel, allWorkflowCount) {
        folderTabItems(folderTabs, allTabLabel, allWorkflowCount)
    }
    // 搜索态不显示 Tab 栏（搜索结果是跨文件夹的平铺结果，显示筛选会误导）；
    // 一个文件夹都没有时也不显示（此时 Tab 栏只剩「全部 N」，纯占高度）。
    val showFolderTabBar = folderTabs.isNotEmpty() && !isSearching

    /**
     * 当前选中 Tab 下应显示的工作流（**跨文件夹平铺**）。
     *
     * ⚠️ 定义成普通函数而不是 `remember` 出来的值 —— 拖拽结束时 `onDragStopped` 里要
     * **立刻**按最新顺序落盘，而 `remember` 的值依赖重组时机，**不保证**那时已经更新。
     * 这里每次现场算，顺序一定是当前 `displayItems` 的真实顺序。
     */
    fun visibleWorkflowsNow(): List<Workflow> {
        val flat = buildList {
            displayItems.forEach { item ->
                when (item) {
                    is WorkflowListItem.WorkflowItem -> add(item.workflow)
                    is WorkflowListItem.FolderItem -> addAll(item.childWorkflows)
                }
            }
        }
        return filterByFolderTab(flat, selectedFolderTab)
    }

    val filteredItems = if (isSearching) {
        // ⚠️ 搜索态：**跨文件夹**搜索。`displayItems` 里的 FolderItem 带着 childWorkflows，
        // 所以能搜到文件夹里的工作流；但结果列表里不再保留 FolderItem 本身
        // （Tab 栏在搜索态隐藏，摘出来的卡片直接平铺）。
        buildList {
            displayItems.forEach { item ->
                when (item) {
                    is WorkflowListItem.WorkflowItem -> {
                        if (matchesSearch(normalizedQuery, item.workflow.name, item.workflow.description)) {
                            add(item)
                        }
                    }

                    is WorkflowListItem.FolderItem -> {
                        item.childWorkflows.forEach { workflow ->
                            if (matchesSearch(normalizedQuery, workflow.name, workflow.description)) {
                                add(WorkflowListItem.WorkflowItem(workflow))
                            }
                        }
                    }
                }
            }
        }
    } else {
        // 非搜索态：由 Tab 栏决定看哪些工作流（跨文件夹平铺）。
        //
        // ⚠️ 只取 `displayItems` 里的 WorkflowItem 是**不够的** —— Route 层把文件夹内的
        //    工作流收进了 `FolderItem.childWorkflows`、不再平铺出来，所以必须两边都摊平，
        //    否则切到某个文件夹 Tab 会是空的。
        //
        // ⚠️ 这一步对**列表模式也生效**：文件夹机制整体改成了 Tab 栏，
        //    两种布局共用同一套筛选。列表模式的卡片本身（`WorkflowCard`）一行未改。
        visibleWorkflowsNow().map { WorkflowListItem.WorkflowItem(it) }
    }

    LaunchedEffect(uiState.items) {
        displayItems.clear()
        displayItems.addAll(uiState.items)
    }

    LaunchedEffect(uiState.isLoading, displayItems.size, isSearching) {
        if (uiState.isLoading && displayItems.isEmpty() && !isSearching) {
            delay(180)
            showLoadingCard = uiState.isLoading && displayItems.isEmpty() && !isSearching
        } else {
            showLoadingCard = false
        }
    }

    // ⚠️ 选中的文件夹被删掉后，Tab 会消失而选中项还留在那个 id 上
    // ⇒ 列表变空且没有任何 Tab 高亮，用户看到「工作流全没了」。
    // 这里在文件夹集合变化时把失效的选中项退回「全部」。
    LaunchedEffect(knownFolderIds, selectedFolderTab) {
        if (selectedFolderTab != WORKFLOW_TAB_ALL && selectedFolderTab !in knownFolderIds) {
            selectedFolderTab = WORKFLOW_TAB_ALL
        }
    }

    // ⚠️ 列表模式下搜索栏恒为第 0 项，Tab 栏为第 1 项 —— 它**可能不显示**
    //    （无文件夹 / 搜索态），所以偏移量是**动态的**。
    //    写死常量会让「有文件夹时」拖拽搬错项，而且**不报错**，只是顺序不对。
    // ⚠️ 列表模式的 onMove 也改成**按 key 反查**（原来用 `from.index - N` 的固定偏移）。
    //    改动前 N 恒为 1（搜索栏），现在 Tab 栏可能插在第 1 位、偏移变成 2，
    //    而且**还同时夹着「过滤后只显示一部分」**的情况 —— 固定偏移会搬错项且不报错。
    //    `LazyListItemInfo.key` 已核实存在（`javap` 确认），路径与瀑布流那条完全一致。
    //
    //    索引在过滤后列表里的位置 → 映射回 `displayItems` 的下标。
    val reorderableState = rememberReorderableLazyListState(
        lazyListState = lazyListState,
        scrollThresholdPadding = PaddingValues(
            bottom = extraBottomPadding + 88.dp
        )
    ) { from, to ->
        val fromIndex = filteredItems.indexOfFirst { it.id == from.key as? String }
        val toIndex = filteredItems.indexOfFirst { it.id == to.key as? String }
        if (fromIndex < 0 || toIndex < 0) return@rememberReorderableLazyListState

        val fromItem = filteredItems[fromIndex]
        val toItem = filteredItems[toIndex]
        if (fromItem is WorkflowListItem.WorkflowItem && toItem is WorkflowListItem.WorkflowItem) {
            val fromDataIndex = displayItems.indexOfFirst { it.id == fromItem.id }
            val toDataIndex = displayItems.indexOfFirst { it.id == toItem.id }
            if (fromDataIndex < 0 || toDataIndex < 0) return@rememberReorderableLazyListState
            displayItems.removeAt(fromDataIndex)
            displayItems.add(toDataIndex, fromItem)
        }
    }

    // ⚠️ 瀑布流的 onMove 必须用 **item 的 key** 反查索引，不能用 `from.index - N`。
    //    `LazyStaggeredGridItemInfo.index` 是「本项在列表里的序号」，而瀑布流多了
    //    搜索栏、Tab 栏这些 FullLine 项，序号与 `displayItems` 的下标**不再有固定偏移**
    //    （Tab 栏有没有取决于文件夹数量）。用固定偏移会在有文件夹时搬错项 ——
    //    而且**不报错**，只是拖拽后顺序不对。
    val reorderableStaggeredGridState = rememberReorderableLazyStaggeredGridState(
        lazyStaggeredGridState = lazyStaggeredGridState,
        scrollThresholdPadding = PaddingValues(
            bottom = extraBottomPadding + 88.dp
        )
    ) { from, to ->
        val fromKey = from.key as? String ?: return@rememberReorderableLazyStaggeredGridState
        val toKey = to.key as? String ?: return@rememberReorderableLazyStaggeredGridState
        val fromDataIndex = displayItems.indexOfFirst { it.id == fromKey }
        val toDataIndex = displayItems.indexOfFirst { it.id == toKey }
        if (fromDataIndex < 0 || toDataIndex < 0) return@rememberReorderableLazyStaggeredGridState

        val fromItem = displayItems[fromDataIndex]
        val toItem = displayItems[toDataIndex]
        if (fromItem is WorkflowListItem.WorkflowItem && toItem is WorkflowListItem.WorkflowItem) {
            displayItems.removeAt(fromDataIndex)
            displayItems.add(toDataIndex, fromItem)
        }
    }

    /**
     * 拖拽结束后落盘顺序。
     *
     * ⚠️ **只落当前 Tab 可见的那些**，不是 `displayItems` 全量。
     * `WorkflowManager.saveAllWorkflows` 是**合并**语义（保留未出现在入参里的旧项，
     * 且会强制沿用旧 `folderId`），所以按过滤后的列表落盘是安全的 ——
     * 别的文件夹里的工作流不会被删、归属也不会被改。
     *
     * 传全量会把「用户在当前 Tab 里看到的顺序」当成全局顺序覆盖回去，
     * 而那些没显示的项在 `displayItems` 里仍在原位 ⇒ 顺序会与用户拖拽的结果打架。
     */
    fun persistOrder() {
        actions.onPersistWorkflowOrder(visibleWorkflowsNow())
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // 紧凑模式的列数/密度由**可用宽度**推导（见 WorkflowCompactGridSpec.kt 的纯函数与单测）。
        // ⚠️ 这里刻意**不再借** `isWideLayout` 与 840dp 那个判断 —— 那个判据属于
        //    「是否显示侧边导航栏」，本处要的是「内容区有多宽」，两者不是一回事。
        val compactSpec = workflowCompactGridSpec(
            availableWidth = maxWidth - COMPACT_GRID_CONTENT_PADDING * 2
        )

        if (layoutMode == WorkflowLayoutMode.Grid) {
            WorkflowCompactGridContent(
                filteredItems = filteredItems,
                uiState = uiState,
                actions = actions,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                isSearching = isSearching,
                showLoadingCard = showLoadingCard,
                extraBottomPadding = extraBottomPadding,
                spec = compactSpec,
                folderTabs = folderTabs,
                allWorkflowCount = allWorkflowCount,
                showFolderTabBar = showFolderTabBar,
                liquidGlassEnabled = liquidGlassEnabled,
                selectedFolderTab = selectedFolderTab,
                onSelectFolderTab = { selectedFolderTab = it },
                onRenameFolder = actions.onRenameFolder,
                onExportFolder = actions.onExportFolder,
                onDissolveFolder = actions.onDissolveFolder,
                onDeleteFolder = actions.onDeleteFolder,
                lazyStaggeredGridState = lazyStaggeredGridState,
                reorderableStaggeredGridState = reorderableStaggeredGridState,
                onPersistOrder = ::persistOrder,
            )
        } else {
            LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { focusManager.clearFocus() }
                },
            contentPadding = PaddingValues(
                start = 0.dp,
                top = 12.dp,
                end = 0.dp,
                bottom = extraBottomPadding + 88.dp
            ),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            item {
                SearchBarCard(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholderRes = R.string.workflow_search_placeholder,
                    clearContentDescriptionRes = R.string.workflow_search_clear,
                    onClearFocus = { focusManager.clearFocus() },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // 文件夹 Tab 栏（与紧凑模式同一套；列表模式也必须能进文件夹，
            // 否则文件夹里的工作流在这个布局下就完全看不到了）。
            // ⚠️ 它插在搜索栏之后、工作流列表之前。列表模式的拖拽排序按 **item key**
            //    反查索引（见 `reorderableState`），不依赖 item 的固定偏移，
            //    所以这里插一项不会打乱拖拽映射。
            if (showFolderTabBar) {
                item {
                    WorkflowFolderTabBarSwitch(
                        liquidGlassEnabled = liquidGlassEnabled,
                        tabs = tabItems,
                        selectedFolderId = selectedFolderTab,
                        onSelect = { selectedFolderTab = it },
                        onRenameFolder = actions.onRenameFolder,
                        onExportFolder = actions.onExportFolder,
                        onDissolveFolder = actions.onDissolveFolder,
                        onDeleteFolder = actions.onDeleteFolder,
                        // ⚠️⚠️ **两侧留白走 `contentInset`，不能加在 `modifier` 上**。
                        //    两者视觉一样，但栏现在会因「文件夹多 / 名字长」而溢出滚动，
                        //    而 `horizontalScroll` 会给自己的节点套一层
                        //    `clipScrollableContainer`（没有开关）⇒ 留白加在 modifier 上时
                        //    它落在**滚动视口之外**，指示块按下放大到 1.39 倍、
                        //    超出内边距的那部分会被切平（图标分类栏踩过这个坑）。
                        //    `fillMaxWidth` 是必需的：栏要撑满 item，留白才由组件内部出。
                        modifier = Modifier.fillMaxWidth(),
                        contentInset = 16.dp,
                    )
                }
            }

            if (showLoadingCard) {
                item {
                    WorkflowLoadingState(
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else if (filteredItems.isEmpty()) {
                item {
                    if (isSearching) {
                        SearchEmptyStateCard(
                            titleRes = R.string.workflow_search_no_results,
                            hintRes = R.string.workflow_search_no_results_hint,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    } else {
                        EmptyWorkflowState(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                        )
                    }
                }
            } else {
                itemsIndexed(
                    items = filteredItems,
                    key = { _, item -> item.id }
                ) { _, item ->
                    when (item) {
                        is WorkflowListItem.WorkflowItem -> {
                            val workflow = item.workflow
                            var suppressOpenUntil by remember(item.id) { mutableLongStateOf(0L) }
                            val topMenuActions = listOf(
                                WorkflowMenuItemAction(
                                    textRes = R.string.dialog_move_to_folder_title,
                                    icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                    onClick = { actions.onMoveWorkflowToFolder(workflow) }
                                ),
                                WorkflowMenuItemAction(
                                    textRes = R.string.workflow_item_menu_duplicate,
                                    icon = Icons.Outlined.ContentCopy,
                                    onClick = { actions.onDuplicateWorkflow(workflow) }
                                ),
                                WorkflowMenuItemAction(
                                    textRes = R.string.workflow_item_menu_delete,
                                    icon = Icons.Outlined.DeleteOutline,
                                    onClick = { actions.onDeleteWorkflow(workflow) }
                                ),
                            )
                            val regularMenuActions = buildList {
                                if (workflow.hasManualTrigger()) {
                                    add(
                                        WorkflowMenuItemAction(
                                            textRes = R.string.workflow_item_menu_add_shortcut,
                                            icon = Icons.AutoMirrored.Outlined.AddToHomeScreen,
                                            onClick = { actions.onAddShortcut(workflow) }
                                        )
                                    )
                                }
                                add(
                                    WorkflowMenuItemAction(
                                        textRes = R.string.workflow_item_menu_export_single,
                                        icon = Icons.Outlined.Download,
                                        onClick = { actions.onExportWorkflow(workflow) }
                                    )
                                )
                                add(
                                    WorkflowMenuItemAction(
                                        textRes = R.string.workflow_item_menu_copy_id,
                                        icon = Icons.Outlined.Badge,
                                        onClick = { actions.onCopyWorkflowId(workflow) }
                                    )
                                )
                                // ⚠️ 两个池**各自按自己那一池判**，且判据只有 TileGate 一处。
                                //    此前这里只有一个菜单项、判据是 hasManualTrigger() ——
                                //    那会让「Agent 建的纯自动工作流」没有入口（它没有 manual
                                //    trigger），而多数工作流同时有 manual+auto、缺陷被掩盖着。
                                if (TileGate.accepts(TileKind.EXECUTE, workflow)) {
                                    add(
                                        WorkflowMenuItemAction(
                                            textRes = R.string.workflow_item_menu_add_to_execute_tile,
                                            icon = Icons.Outlined.DashboardCustomize,
                                            onClick = { actions.onAddToTile(workflow, TileKind.EXECUTE) }
                                        )
                                    )
                                }
                                if (TileGate.accepts(TileKind.TOGGLE, workflow)) {
                                    add(
                                        WorkflowMenuItemAction(
                                            textRes = R.string.workflow_item_menu_add_to_toggle_tile,
                                            icon = Icons.Outlined.ToggleOn,
                                            onClick = { actions.onAddToTile(workflow, TileKind.TOGGLE) }
                                        )
                                    )
                                }
                            }
                            if (isSearching) {
                                WorkflowCard(
                                    workflow = workflow,
                                    executionStateVersion = uiState.executionStateVersion,
                                    isDragging = false,
                                    topMenuActions = topMenuActions,
                                    regularMenuActions = regularMenuActions,
                                    modifier = Modifier.fillMaxWidth(),
                                    onOpenWorkflow = { actions.onOpenWorkflow(workflow) },
                                    onToggleFavorite = { actions.onToggleFavorite(workflow) },
                                    onToggleEnabled = { enabled -> actions.onToggleEnabled(workflow, enabled) },
                                    onExecuteWorkflow = { actions.onExecuteWorkflow(workflow) },
                                    onExecuteWorkflowDelayed = { delayMs ->
                                        actions.onExecuteWorkflowDelayed(workflow, delayMs)
                                    }
                                )
                            } else {
                                ReorderableItem(
                                    state = reorderableState,
                                    key = item.id
                                ) { isDragging ->
                                    WorkflowCard(
                                        workflow = workflow,
                                        executionStateVersion = uiState.executionStateVersion,
                                        isDragging = isDragging,
                                        topMenuActions = topMenuActions,
                                        regularMenuActions = regularMenuActions,
                                        modifier = Modifier.fillMaxWidth(),
                                        dragHandleModifier = with(this) {
                                            Modifier.longPressDraggableHandle(
                                                onDragStarted = {
                                                    suppressOpenUntil = SystemClock.uptimeMillis() + 250L
                                                },
                                                onDragStopped = {
                                                    suppressOpenUntil = SystemClock.uptimeMillis() + 250L
                                                    persistOrder()
                                                }
                                            )
                                        },
                                        onOpenWorkflow = {
                                            if (SystemClock.uptimeMillis() < suppressOpenUntil) return@WorkflowCard
                                            actions.onOpenWorkflow(workflow)
                                        },
                                        onToggleFavorite = { actions.onToggleFavorite(workflow) },
                                        onToggleEnabled = { enabled -> actions.onToggleEnabled(workflow, enabled) },
                                        onExecuteWorkflow = { actions.onExecuteWorkflow(workflow) },
                                        onExecuteWorkflowDelayed = { delayMs ->
                                            actions.onExecuteWorkflowDelayed(workflow, delayMs)
                                        }
                                    )
                                }
                            }
                        }

                        // ⚠️ 文件夹已改为顶部 Tab 栏，不再进内容流（Route 层也不再产出
                        //    FolderItem）。保留分支只因 `WorkflowListItem` 是 sealed。
                        is WorkflowListItem.FolderItem -> Unit
                    }
                }
            }
            }
        }

        FloatingActionButton(
            onClick = actions.onCreateWorkflow,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = extraBottomPadding + 16.dp)
        ) {
            Icon(
                painter = painterResource(R.drawable.rounded_add_24),
                contentDescription = stringResource(R.string.add_workflow)
            )
        }

        // ⚠️ 原「文件夹底部弹窗」（`FolderContentSheet`）已移除 ——
        //    文件夹改为内容区顶部的 Tab 栏（见 `WorkflowFolderTabBar.kt`）。
        //    那套弹窗 + `uiState.openFolder` 的整条链路随之作废：
        //    卡片从来不从内容流里被「打开」，只是被 Tab 筛选。
    }
}

@Composable
private fun EmptyWorkflowState(modifier: Modifier = Modifier) {
    SearchEmptyStateCard(
        titleRes = R.string.text_no_workflows,
        hintRes = R.string.workflow_empty_hint,
        modifier = modifier
    )
}

@Composable
private fun WorkflowLoadingState(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            ContainedLoadingIndicator(
                modifier = Modifier.size(28.dp),
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                indicatorColor = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@OptIn(
    ExperimentalFoundationApi::class,
    ExperimentalLayoutApi::class,
    ExperimentalMaterial3ExpressiveApi::class
)
@Composable
fun WorkflowCard(
    workflow: Workflow,
    executionStateVersion: Int,
    isDragging: Boolean,
    topMenuActions: List<WorkflowMenuItemAction>,
    regularMenuActions: List<WorkflowMenuItemAction>,
    modifier: Modifier = Modifier,
    dragHandleModifier: Modifier = Modifier,
    onOpenWorkflow: () -> Unit,
    onToggleFavorite: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onExecuteWorkflow: () -> Unit,
    onExecuteWorkflowDelayed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val colorfulCardsEnabled = remember { ThemeUtils.isColorfulWorkflowCardsEnabled(context) }
    val visualColors = remember(workflow.cardThemeColor) {
        WorkflowVisuals.resolveCardColors(context, workflow.cardThemeColor)
    }
    val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
    val requiredPermissions = workflow.allSteps
        .mapNotNull { step -> ModuleRegistry.getModule(step.moduleId)?.getRequiredPermissions(step) }
        .flatten()
        .distinct()
        .map { it.getLocalizedName(context) }
    val isManualTrigger = workflow.hasManualTrigger()
    val hasAutoTriggers = workflow.hasAutoTriggers()
    val isRunning = remember(workflow.id, executionStateVersion) {
        WorkflowExecutor.isRunning(workflow.id)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var delayedMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = modifier
            .padding(start = 12.dp, top = 6.dp, end = 12.dp, bottom = 6.dp)
            .graphicsLayer {
                if (isDragging) {
                    scaleX = 1.02f
                    scaleY = 1.02f
                }
            },
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 0.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (colorfulCardsEnabled) {
                Color(visualColors.cardBackground)
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onOpenWorkflow)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.then(dragHandleModifier)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (colorfulCardsEnabled) {
                        // ⚠️ **曲奇饼干花边**（参考 ShortX）：原来是 `RoundedCornerShape(14.dp)`。
                        //    形状与实测依据见 `ScallopedBadgeShape`；花瓣数 12、起伏 4.1%
                        //    都是对 ShortX 截图做极坐标半径扫描量出来的。
                        Surface(
                            modifier = Modifier.size(40.dp),
                            color = Color(visualColors.iconBackground),
                            shape = remember { ScallopedBadgeShape() }
                        ) {
                            // ⚠️ 走 `WorkflowCardIcon`：卡片图标现在可能是**用户选的图片**
                            //    （绝对路径 / file://），不能再无条件 painterResource。
                            WorkflowCardIcon(
                                cardIconRes = workflow.cardIconRes,
                                tint = Color(visualColors.iconTint),
                                size = 22.dp,
                            )
                        }
                        SpacerWidth(12.dp)
                    }

                    Text(
                        text = workflow.name,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2
                    )

                    Box {
                        IconButton(onClick = { menuExpanded = true }) {
                            Icon(
                                painter = painterResource(R.drawable.ic_more_vert),
                                contentDescription = stringResource(R.string.workflow_item_more_options)
                            )
                        }
                        DropdownMenuPopup(
                            expanded = menuExpanded,
                            onDismissRequest = { menuExpanded = false }
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(index = 0, count = 1),
                                modifier = Modifier
                                    .width(IntrinsicSize.Max)
                                    .widthIn(min = 156.dp, max = 236.dp),
                                containerColor = MenuDefaults.groupStandardContainerColor,
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp),
                                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                                    ) {
                                        topMenuActions.forEach { item ->
                                            WorkflowQuickActionButton(
                                                icon = item.icon,
                                                contentDescription = stringResource(item.textRes),
                                                onClick = {
                                                    menuExpanded = false
                                                    item.onClick()
                                                }
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(10.dp))

                                    regularMenuActions.forEach { item ->
                                        WorkflowFlatMenuItem(
                                            text = stringResource(item.textRes),
                                            icon = item.icon,
                                            onClick = {
                                                menuExpanded = false
                                                item.onClick()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 函数徽标放最前：它回答的是「这是什么」，
                    // 比后面的「有多少步骤 / 缺哪些权限」更先被需要。
                    // 样式与其他 chip 完全一致——「函数」二字加 Σ 图标已足够区分，
                    // 再给描边会让它在同排 chip 里显得像异常态。
                    if (workflow.isFunction) {
                        WorkflowChip(
                            label = stringResource(R.string.workflow_chip_function),
                            iconRes = R.drawable.rounded_functions_24,
                            containerColor = if (colorfulCardsEnabled) {
                                Color(visualColors.chipBackground)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (colorfulCardsEnabled) {
                                Color(visualColors.iconTint)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                    if (missingPermissions.isNotEmpty()) {
                        WorkflowChip(
                            label = stringResource(R.string.workflow_chip_missing_permissions),
                            iconRes = R.drawable.rounded_security_24,
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                    WorkflowChip(
                        label = stringResource(R.string.workflow_chip_steps, workflow.steps.size),
                        iconRes = R.drawable.rounded_dashboard_fill_24,
                        containerColor = if (colorfulCardsEnabled) {
                            Color(visualColors.chipBackground)
                        } else {
                            MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        contentColor = if (colorfulCardsEnabled) {
                            Color(visualColors.iconTint)
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    requiredPermissions.forEach { permission ->
                        WorkflowChip(
                            label = permission,
                            iconRes = R.drawable.rounded_security_24,
                            containerColor = if (colorfulCardsEnabled) {
                                Color(visualColors.chipBackground)
                            } else {
                                MaterialTheme.colorScheme.surfaceContainerHighest
                            },
                            contentColor = if (colorfulCardsEnabled) {
                                Color(visualColors.iconTint)
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        painter = painterResource(
                            if (workflow.isFavorite) R.drawable.ic_star else R.drawable.ic_star_border
                        ),
                        contentDescription = stringResource(R.string.workflow_item_favorite),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                if (hasAutoTriggers) {
                    Switch(
                        checked = workflow.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        colors = workflowSwitchColors(
                            colorfulCardsEnabled = colorfulCardsEnabled,
                            visualColors = visualColors
                        )
                    )
                }

                if (isManualTrigger && !hasAutoTriggers) {
                    Box {
                        // ⚠️ **执行按钮没有底色方块**（参考 ShortX）：整块消失，只留一个
                        //    纯色三角。原先是「圆角矩形底 + 白图标」，等于在一张彩色卡上
                        //    再叠一个高亮色块，而卡片本身已经带主题色了 —— 视觉上抢焦点。
                        //
                        //    现在的观感 = 主题色 × 40% 叠在卡片底色上，与 ShortX 一致
                        //    （0.40 的来历见 `WorkflowVisuals.EXECUTE_ICON_ALPHA`）。
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .combinedClickable(
                                    onClick = onExecuteWorkflow,
                                    onLongClick = { delayedMenuExpanded = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(
                                    // ⚠️ **线框**版（不带 `_fill_`）—— 与 ShortX 一致。
                                    //    填充版是实心三角，ShortX 的是空心（见
                                    //    `WorkflowVisuals.EXECUTE_ICON_SIZE_DP` 的实测依据）。
                                    if (isRunning) R.drawable.rounded_pause_24
                                    else R.drawable.rounded_play_arrow_24
                                ),
                                contentDescription = stringResource(R.string.workflow_item_execute),
                                tint = executeIconColor(
                                    colorfulCardsEnabled = colorfulCardsEnabled,
                                    visualColors = visualColors,
                                ),
                                // ⚠️ 去掉底块后图标要**自己撑起视觉重量**。尺寸照 ShortX
                                //    对齐（来历见 `EXECUTE_ICON_SIZE_DP`）：原先 24dp 盒里
                                //    的字形只有 10dp 高，明显偏小。
                                modifier = Modifier.size(WorkflowVisuals.EXECUTE_ICON_SIZE_DP.dp)
                            )
                        }
                        DropdownMenuPopup(
                            expanded = delayedMenuExpanded,
                            onDismissRequest = { delayedMenuExpanded = false }
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(index = 0, count = 1),
                                modifier = Modifier
                                    .width(IntrinsicSize.Max)
                                    .widthIn(min = 132.dp, max = 180.dp),
                                containerColor = MenuDefaults.groupStandardContainerColor,
                            ) {
                                Column(
                                    modifier = Modifier.padding(vertical = 10.dp)
                                ) {
                                    WorkflowPlainMenuItem(
                                        text = stringResource(R.string.workflow_execute_delay_5s),
                                        onClick = {
                                            delayedMenuExpanded = false
                                            onExecuteWorkflowDelayed(5_000L)
                                        }
                                    )
                                    WorkflowPlainMenuItem(
                                        text = stringResource(R.string.workflow_execute_delay_15s),
                                        onClick = {
                                            delayedMenuExpanded = false
                                            onExecuteWorkflowDelayed(15_000L)
                                        }
                                    )
                                    WorkflowPlainMenuItem(
                                        text = stringResource(R.string.workflow_execute_delay_1min),
                                        onClick = {
                                            delayedMenuExpanded = false
                                            onExecuteWorkflowDelayed(60_000L)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkflowActionMenuItem(
    text: String,
    icon: ImageVector,
    index: Int,
    count: Int,
    onClick: () -> Unit,
) {
    DropdownMenuItem(
        onClick = onClick,
        text = { Text(text) },
        leadingIcon = {
            Icon(
                imageVector = icon,
                contentDescription = null
            )
        },
        shape = MenuDefaults.itemShape(index = index, count = count).shape,
    )
}

@Composable
private fun RowScope.WorkflowQuickActionButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.size(40.dp),
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.surfaceContainer,
        tonalElevation = 0.dp
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun WorkflowFlatMenuItem(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun WorkflowPlainMenuItem(
    text: String,
    onClick: () -> Unit,
) {
    Surface(
        onClick = onClick,
        color = Color.Transparent,
        shape = RoundedCornerShape(18.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun WorkflowChip(
    label: String,
    iconRes: Int,
    containerColor: Color,
    contentColor: Color
) {
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = RoundedCornerShape(999.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(14.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall
            )
        }
    }
}

@Composable
private fun WorkflowCompactGridContent(
    filteredItems: List<WorkflowListItem>,
    uiState: WorkflowListUiState,
    actions: WorkflowListScreenActions,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    isSearching: Boolean,
    showLoadingCard: Boolean,
    extraBottomPadding: Dp,
    spec: WorkflowCompactGridSpec,
    folderTabs: List<WorkflowFolderTab>,
    /** 「全部」Tab 上显示的数量（全部工作流，含未归类的）。 */
    allWorkflowCount: Int,
    showFolderTabBar: Boolean,
    liquidGlassEnabled: Boolean,
    selectedFolderTab: String,
    onSelectFolderTab: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    lazyStaggeredGridState: LazyStaggeredGridState,
    reorderableStaggeredGridState: ReorderableLazyStaggeredGridState,
    onPersistOrder: () -> Unit,
) {
    val allTabLabel = stringResource(R.string.workflow_tab_all)
    val tabItems = remember(folderTabs, allTabLabel, allWorkflowCount) {
        folderTabItems(folderTabs, allTabLabel, allWorkflowCount)
    }

    LazyVerticalStaggeredGrid(
        state = lazyStaggeredGridState,
        columns = StaggeredGridCells.Fixed(spec.columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = COMPACT_GRID_CONTENT_PADDING,
            top = 12.dp,
            end = COMPACT_GRID_CONTENT_PADDING,
            bottom = extraBottomPadding + 88.dp,
        ),
        horizontalArrangement = Arrangement.spacedBy(COMPACT_GRID_HORIZONTAL_SPACING),
        verticalItemSpacing = COMPACT_GRID_HORIZONTAL_SPACING,
    ) {
        item(span = StaggeredGridItemSpan.FullLine) {
            SearchBarCard(
                value = searchQuery,
                onValueChange = onSearchQueryChange,
                placeholderRes = R.string.workflow_search_placeholder,
                clearContentDescriptionRes = R.string.workflow_search_clear,
                onClearFocus = {},
            )
        }

        // 文件夹 Tab 栏（fork：替代原来的「文件夹卡片 + 底部弹窗」）。
        // ⚠️ 与列表模式用**同一个开关**（都是 `showFolderTabBar`）—— 两种布局的行为
        //    必须一致，否则切一下布局就多/少一行，用户会以为界面错乱。
        if (showFolderTabBar) {
            item(span = StaggeredGridItemSpan.FullLine) {
                WorkflowFolderTabBarSwitch(
                    liquidGlassEnabled = liquidGlassEnabled,
                    tabs = tabItems,
                    selectedFolderId = selectedFolderTab,
                    onSelect = onSelectFolderTab,
                    onRenameFolder = onRenameFolder,
                    onExportFolder = onExportFolder,
                    onDissolveFolder = onDissolveFolder,
                    onDeleteFolder = onDeleteFolder,
                    // ⚠️ **不传 `contentInset`** —— 与列表模式不同，这里的左右留白
                    //    已由网格自己的 `contentPadding`（`COMPACT_GRID_CONTENT_PADDING`）
                    //    提供，再传一次就是双重留白（栏会比搜索卡片窄一截，
                    //    而两者本该左对齐）。视口边缘 = 内容边缘，指示块放大也不会被切。
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        if (showLoadingCard) {
            item(span = StaggeredGridItemSpan.FullLine) {
                WorkflowLoadingState(modifier = Modifier.padding(vertical = 12.dp))
            }
        } else if (filteredItems.isEmpty()) {
            item(span = StaggeredGridItemSpan.FullLine) {
                if (isSearching) {
                    SearchEmptyStateCard(
                        titleRes = R.string.workflow_search_no_results,
                        hintRes = R.string.workflow_search_no_results_hint,
                        modifier = Modifier.padding(vertical = 12.dp),
                    )
                } else {
                    EmptyWorkflowState(modifier = Modifier.padding(vertical = 12.dp))
                }
            }
        } else {
            items(
                items = filteredItems,
                key = { item -> item.id },
            ) { item ->
                when (item) {
                    is WorkflowListItem.WorkflowItem -> {
                        val workflow = item.workflow
                        var suppressOpenUntil by remember(item.id) { mutableLongStateOf(0L) }
                        val topMenuActions = listOf(
                            WorkflowMenuItemAction(
                                textRes = R.string.workflow_item_menu_favorite,
                                icon = if (workflow.isFavorite) {
                                    Icons.Filled.Star
                                } else {
                                    Icons.Outlined.StarBorder
                                },
                                onClick = { actions.onToggleFavorite(workflow) }
                            ),
                            WorkflowMenuItemAction(
                                textRes = R.string.dialog_move_to_folder_title,
                                icon = Icons.AutoMirrored.Outlined.DriveFileMove,
                                onClick = { actions.onMoveWorkflowToFolder(workflow) }
                            ),
                            WorkflowMenuItemAction(
                                textRes = R.string.workflow_item_menu_duplicate,
                                icon = Icons.Outlined.ContentCopy,
                                onClick = { actions.onDuplicateWorkflow(workflow) }
                            ),
                            WorkflowMenuItemAction(
                                textRes = R.string.workflow_item_menu_delete,
                                icon = Icons.Outlined.DeleteOutline,
                                onClick = { actions.onDeleteWorkflow(workflow) }
                            ),
                        )
                        val regularMenuActions = buildList {
                            if (workflow.hasManualTrigger()) {
                                add(
                                    WorkflowMenuItemAction(
                                        textRes = R.string.workflow_item_menu_add_shortcut,
                                        icon = Icons.AutoMirrored.Outlined.AddToHomeScreen,
                                        onClick = { actions.onAddShortcut(workflow) }
                                    )
                                )
                            }
                            add(
                                WorkflowMenuItemAction(
                                    textRes = R.string.workflow_item_menu_export_single,
                                    icon = Icons.Outlined.Download,
                                    onClick = { actions.onExportWorkflow(workflow) }
                                )
                            )
                            add(
                                WorkflowMenuItemAction(
                                    textRes = R.string.workflow_item_menu_copy_id,
                                    icon = Icons.Outlined.Badge,
                                    onClick = { actions.onCopyWorkflowId(workflow) }
                                )
                            )
                            // ⚠️ 同列表模式的注释：两个池各按自己那一池判，判据只有 TileGate 一处。
                            if (TileGate.accepts(TileKind.EXECUTE, workflow)) {
                                add(
                                    WorkflowMenuItemAction(
                                        textRes = R.string.workflow_item_menu_add_to_execute_tile,
                                        icon = Icons.Outlined.DashboardCustomize,
                                        onClick = { actions.onAddToTile(workflow, TileKind.EXECUTE) }
                                    )
                                )
                            }
                            if (TileGate.accepts(TileKind.TOGGLE, workflow)) {
                                add(
                                    WorkflowMenuItemAction(
                                        textRes = R.string.workflow_item_menu_add_to_toggle_tile,
                                        icon = Icons.Outlined.ToggleOn,
                                        onClick = { actions.onAddToTile(workflow, TileKind.TOGGLE) }
                                    )
                                )
                            }
                        }
                        ReorderableItem(
                            state = reorderableStaggeredGridState,
                            key = item.id,
                        ) { isDragging ->
                            WorkflowCompactCard(
                                // ⚠️ 用 `animateItem`（逐项淡入淡出），**不要用
                                //    `animateContentSize`** —— 那会自己触发重组（动画每帧回调），
                                //    在长列表里代价高，且本卡片高度本来就随内容自适应、
                                //    没有「尺寸要平滑变化」的需求。见 ChatMarkdown.kt 里记的同类坑。
                                entryAnimationModifier = Modifier.animateItem(),
                                workflow = workflow,
                                executionStateVersion = uiState.executionStateVersion,
                                isDragging = isDragging,
                                density = spec.density,
                                topMenuActions = topMenuActions,
                                regularMenuActions = regularMenuActions,
                                dragHandleModifier = with(this) {
                                    Modifier.longPressDraggableHandle(
                                        onDragStarted = {
                                            suppressOpenUntil = SystemClock.uptimeMillis() + 250L
                                        },
                                        onDragStopped = {
                                            suppressOpenUntil = SystemClock.uptimeMillis() + 250L
                                            onPersistOrder()
                                        }
                                    )
                                },
                                onOpenWorkflow = {
                                    if (SystemClock.uptimeMillis() < suppressOpenUntil) {
                                        return@WorkflowCompactCard
                                    }
                                    actions.onOpenWorkflow(workflow)
                                },
                                onToggleEnabled = { enabled -> actions.onToggleEnabled(workflow, enabled) },
                                onExecuteWorkflow = { actions.onExecuteWorkflow(workflow) },
                                onExecuteWorkflowDelayed = { delayMs ->
                                    actions.onExecuteWorkflowDelayed(workflow, delayMs)
                                },
                            )
                        }
                    }

                    // ⚠️ 文件夹已改为顶部 Tab 栏，不再进内容流（Route 层也不再产出
                    //    FolderItem）。这里保留分支只因 `WorkflowListItem` 是 sealed。
                    is WorkflowListItem.FolderItem -> Unit
                }
            }
        }
    }
}

/**
 * 紧凑模式卡片（瀑布流卡）—— fork 重做，替代原正方形 `WorkflowCardCompact`。
 *
 * ## 结构（自上而下）
 *
 * ```
 * ┌──────────────────────────┐
 * │ ⬛            ⋮   ⇄/▷    │  ← 图标、更多、状态控件**同一行**
 * │ 工作流名称                │
 * │ 工作流说明（为空则无此行）  │
 * │ [函数] [缺 2 项权限] [5 步] │
 * └──────────────────────────┘
 * ```
 *
 * ## ⚠️ 与原正方形卡的三处关键差异
 *
 * 1. **取消底部独立操作行**。原卡是「图标行 + 标题 + 竖排胶囊 + 底部 ★/开关」四段，
 *    底部那行是独立的 `48dp` IconButton 行 —— 卡片被它撑高，内容却撑不满正方形，
 *    于是 `SpaceBetween` 拉出一大片空白。现在状态控件上提到图标行，
 *    卡片高度随内容自适应（瀑布流），不再有撑不满的问题。
 *
 * 2. **不显示步骤摘要，显示 `workflow.description`**。摘要要靠
 *    `ModuleRegistry.getModule(...)?.getSummary(context, step)` 逐模块现算，
 *    每张卡都要跑 N 次、滚动时反复重算，而用户明确表示「我们有工作流的说明，显示这个」。
 *
 * 3. **说明为空时整行不出现**（不显示「未填写说明」之类的占位）——
 *    用户 2026-10-04 明确要求「没有说明就显示空就行了」。
 *
 * ## ⚠️ 收藏按钮放进了 ⋮ 菜单
 *
 * 三列时（单列约 112dp）一行塞不下「图标 + ★ + ⋮ + 开关」四项。
 * 卡片头部优先留给 ⋮ 与状态控件 —— 前者是唯一的「更多操作」入口，
 * 后者是自动/手动触发工作流唯一的状态控件。收藏放进菜单第一格（快捷按钮区首位）。
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun WorkflowCompactCard(
    workflow: Workflow,
    executionStateVersion: Int,
    isDragging: Boolean,
    density: CompactCardDensity,
    entryAnimationModifier: Modifier = Modifier,
    topMenuActions: List<WorkflowMenuItemAction>,
    regularMenuActions: List<WorkflowMenuItemAction>,
    dragHandleModifier: Modifier = Modifier,
    onOpenWorkflow: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onExecuteWorkflow: () -> Unit,
    onExecuteWorkflowDelayed: (Long) -> Unit,
) {
    val context = LocalContext.current
    val colorfulCardsEnabled = remember { ThemeUtils.isColorfulWorkflowCardsEnabled(context) }
    val visualColors = remember(workflow.cardThemeColor) {
        WorkflowVisuals.resolveCardColors(context, workflow.cardThemeColor)
    }
    val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
    val permissionCount = workflow.allSteps
        .mapNotNull { step -> ModuleRegistry.getModule(step.moduleId)?.getRequiredPermissions(step) }
        .flatten()
        .distinct()
        .size
    val hasAutoTriggers = workflow.hasAutoTriggers()
    val isManualTrigger = workflow.hasManualTrigger()
    val isRunning = remember(workflow.id, executionStateVersion) {
        WorkflowExecutor.isRunning(workflow.id)
    }
    var menuExpanded by remember { mutableStateOf(false) }
    var delayedMenuExpanded by remember { mutableStateOf(false) }

    val iconBox = when (density) {
        CompactCardDensity.ROOMY -> 36.dp
        CompactCardDensity.NORMAL -> 34.dp
        CompactCardDensity.TIGHT -> 32.dp
    }
    val iconInner = when (density) {
        CompactCardDensity.ROOMY -> 20.dp
        CompactCardDensity.NORMAL -> 19.dp
        CompactCardDensity.TIGHT -> 18.dp
    }
    // ⚠️ 原为 12 / 11 / 10，对着 ShortX 的截图偏挤。整体 +2dp 后
    //    「图标行 → 名称 → 说明 → 徽标」四段之间才有清晰的分隔感。
    val cardPadding = when (density) {
        CompactCardDensity.ROOMY -> 14.dp
        CompactCardDensity.NORMAL -> 13.dp
        CompactCardDensity.TIGHT -> 12.dp
    }
    val titleStyle = when (density) {
        CompactCardDensity.ROOMY -> MaterialTheme.typography.titleMedium
        else -> MaterialTheme.typography.titleSmall
    }
    val titleMaxLines = when (density) {
        CompactCardDensity.TIGHT -> 2
        else -> 3
    }
    val descMaxLines = when (density) {
        CompactCardDensity.ROOMY -> 4
        CompactCardDensity.NORMAL -> 3
        CompactCardDensity.TIGHT -> 2
    }
    val controlSize = when (density) {
        CompactCardDensity.ROOMY -> 44.dp
        CompactCardDensity.NORMAL -> 40.dp
        CompactCardDensity.TIGHT -> 38.dp
    }
    val moreSize = when (density) {
        CompactCardDensity.ROOMY -> 30.dp
        CompactCardDensity.NORMAL -> 28.dp
        CompactCardDensity.TIGHT -> 26.dp
    }
    val chipColor = if (colorfulCardsEnabled) {
        Color(visualColors.chipBackground)
    } else {
        MaterialTheme.colorScheme.surfaceContainerHighest
    }
    val chipContent = if (colorfulCardsEnabled) {
        Color(visualColors.iconTint)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }
    // ⚠️ 必须在这里算（`animateFloatAsState` 是 @Composable，不能写在
    //    `graphicsLayer { }` 的 lambda 里 —— 那个 lambda 不是 composable 作用域）。
    val dragScale by animateFloatAsState(
        targetValue = if (isDragging) 1.02f else 1f,
        // 用 spring 不用 tween：手指压下与松开之间可能被打断，
        // spring 会从当前值接着走，tween 会从 1.0 重新数。
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 700f),
        label = "compactCardDragScale",
    )

    val cardShape = RoundedCornerShape(18.dp)
    // ⚠️ **竖直渐变**而不是纯色 —— 参考 ShortX：卡片顶端带一点主题色、
    //    越往下越贴近 surface，于是同一张卡自己有了层次，相邻卡片也有了区隔。
    //    纯色时整屏卡片是「一格格色块」，渐变之后才有「发光卡片」的感觉。
    //    `cardBackgroundEnd` 由 `WorkflowVisuals.resolveCardColors` 给出；
    //    非多彩模式（`colorfulCardsEnabled == false`）下两个端点相同 ⇒ 退化成纯色，
    //    与改动前的观感一致。
    val cardBrush = if (colorfulCardsEnabled) {
        Brush.verticalGradient(
            colors = listOf(
                Color(visualColors.cardBackground),
                Color(visualColors.cardBackgroundEnd),
            )
        )
    } else {
        Brush.verticalGradient(
            colors = listOf(
                MaterialTheme.colorScheme.surfaceContainerLow,
                MaterialTheme.colorScheme.surfaceContainerLow,
            )
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(entryAnimationModifier)
            // ⚠️ 拖拽缩放走动画（原来是硬切）—— 「拿起来了」这件事需要一点
            //    时间上的确认感，硬切看起来像渲染错了一帧。
            .graphicsLayer {
                scaleX = dragScale
                scaleY = dragScale
            },
        shape = cardShape,
        elevation = CardDefaults.cardElevation(defaultElevation = if (isDragging) 8.dp else 0.dp),
        // ⚠️ 容器色设成透明、渐变画在**内层 Box** 上：
        //    `Card` 的 `containerColor` 只接受纯色，传不了 `Brush`。
        colors = CardDefaults.cardColors(containerColor = Color.Transparent),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(brush = cardBrush, shape = cardShape)
                .combinedClickable(onClick = onOpenWorkflow)
                .padding(cardPadding),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(dragHandleModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    modifier = Modifier.size(iconBox),
                    // ⚠️ 曲奇饼干花边，与列表模式一致（`RoundedCornerShape(13.dp)` 是原来
                    //    的形态）。花边是**纯几何**、不依赖主题色，故非多彩模式下照用。
                    shape = remember { ScallopedBadgeShape() },
                    color = if (colorfulCardsEnabled) {
                        Color(visualColors.iconBackground)
                    } else {
                        MaterialTheme.colorScheme.secondaryContainer
                    }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        // ⚠️ 同紧凑卡片：图标可能是自定义图片，见 `WorkflowCardIcon`。
                        WorkflowCardIcon(
                            cardIconRes = workflow.cardIconRes,
                            tint = if (colorfulCardsEnabled) {
                                Color(visualColors.iconTint)
                            } else {
                                MaterialTheme.colorScheme.onSecondaryContainer
                            },
                            size = iconInner,
                            iconPadding = 0.dp,
                        )
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                // ⚠️ 卡片头行只留「图标 + 状态控件」——⋮ 已挪到卡片**右下角**（见卡片底行）。
                //    头行两者之间的空白是刻意的：那里是视觉重心的呼吸区，
                //    塞进第三个图标（⋮）会把「图标 → 空白 → ⋮ → 开关」挤成一条线。
                if (hasAutoTriggers) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Switch(
                        checked = workflow.isEnabled,
                        onCheckedChange = onToggleEnabled,
                        // ⚠️ 间距加大后单列只有约 116dp（手机）/ 153dp（展开），
                        //    而 `Switch` 的固有宽度是 **52dp** —— 不缩的话它一枚就吃掉
                        //    头行的三分之一，图标与它的间距会被压没。
                        //    `Modifier.scale` 等比缩整块（内部 thumb/track 不会错位），
                        //    再用 `requiredSize` 把布局占位也收掉 —— 只 scale 不改尺寸
                        //    的话布局仍按 52dp 算，视觉上缩了但位置照旧偏右。
                        modifier = Modifier
                            .requiredSize(44.dp, 28.dp)
                            .scale(0.85f),
                        colors = workflowSwitchColors(
                            colorfulCardsEnabled = colorfulCardsEnabled,
                            visualColors = visualColors
                        )
                    )
                } else if (isManualTrigger) {
                    Spacer(modifier = Modifier.width(4.dp))
                    Box {
                        // ⚠️ **无底色方块**（同列表模式，参考 ShortX）：见上方长注释。
                        //    尺寸由 `controlSize` 承担触控目标，图标自己放大。
                        //    ⚠️⚠️ 卡片底色走 `cardBrush` 的**渐变**，而 `executeIconColor`
                        //    是按 `cardBackground`（渐变顶端）算的 —— 它在卡片顶部区域，
                        //    两者逐像素贴合；再往下颜色会与卡片底色有细微偏移，
                        //    肉眼不可见（0.40 的合成量本身就把差异压得很低）。
                        Box(
                            modifier = Modifier
                                .size(controlSize)
                                .combinedClickable(
                                    onClick = onExecuteWorkflow,
                                    onLongClick = { delayedMenuExpanded = true }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                painter = painterResource(
                                    // ⚠️ 线框版（不带 `_fill_`），与 ShortX 一致。
                                    //    见 `WorkflowVisuals.EXECUTE_ICON_SIZE_DP`。
                                    if (isRunning) R.drawable.rounded_pause_24
                                    else R.drawable.rounded_play_arrow_24
                                ),
                                contentDescription = stringResource(R.string.workflow_item_execute),
                                tint = executeIconColor(
                                    colorfulCardsEnabled = colorfulCardsEnabled,
                                    visualColors = visualColors,
                                ),
                                // ⚠️ 尺寸照 ShortX 对齐。**不再挂在 `iconInner` 上** ——
                                //    那是左上角**图标徽章**的尺寸（18~20dp），拿它当
                                //    执行按钮的尺寸会让「徽章」与「按钮」两个本该独立的
                                //    设计变量被绑死。触控目标仍是 controlSize（38~44dp）。
                                modifier = Modifier.size(WorkflowVisuals.EXECUTE_ICON_SIZE_DP.dp)
                            )
                        }
                        DropdownMenuPopup(
                            expanded = delayedMenuExpanded,
                            onDismissRequest = { delayedMenuExpanded = false }
                        ) {
                            DropdownMenuGroup(
                                shapes = MenuDefaults.groupShape(index = 0, count = 1),
                                modifier = Modifier
                                    .width(IntrinsicSize.Max)
                                    .widthIn(min = 132.dp, max = 180.dp),
                                containerColor = MenuDefaults.groupStandardContainerColor,
                            ) {
                                Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                    WorkflowPlainMenuItem(
                                        text = stringResource(R.string.workflow_execute_delay_5s),
                                        onClick = {
                                            delayedMenuExpanded = false
                                            onExecuteWorkflowDelayed(5_000L)
                                        }
                                    )
                                    WorkflowPlainMenuItem(
                                        text = stringResource(R.string.workflow_execute_delay_15s),
                                        onClick = {
                                            delayedMenuExpanded = false
                                            onExecuteWorkflowDelayed(15_000L)
                                        }
                                    )
                                    WorkflowPlainMenuItem(
                                        text = stringResource(R.string.workflow_execute_delay_1min),
                                        onClick = {
                                            delayedMenuExpanded = false
                                            onExecuteWorkflowDelayed(60_000L)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = workflow.name,
                modifier = Modifier.padding(top = 10.dp),
                style = titleStyle,
                fontWeight = FontWeight.SemiBold,
                maxLines = titleMaxLines,
                overflow = TextOverflow.Ellipsis
            )

            // 说明为空 ⇒ **整行不出现**（用户 2026-10-04 明确要求不留占位）
            if (workflow.description.isNotBlank()) {
                Text(
                    text = workflow.description,
                    modifier = Modifier.padding(top = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = descMaxLines,
                    overflow = TextOverflow.Ellipsis
                )
            }

            // ── 底行：徽标在左、⋮ 在**右下角** ──
            // ⚠️ ⋮ 放这里而不是头行：头行已经有「图标 + 状态控件」两端的元素，
            //    再塞一个会在中间留出突兀的空档；而右下角在 ShortX 的卡片里
            //    本来就是空的，是这块面积上唯一的合理落点（用户 2026-10-05 指出
            //    「卡片的右下角基本都是空的，把更多按钮放在这里最好」）。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (workflow.isFunction) {
                            WorkflowChip(
                                label = stringResource(R.string.workflow_chip_function),
                                iconRes = R.drawable.rounded_functions_24,
                                containerColor = chipColor,
                                contentColor = chipContent
                            )
                        }
                        if (missingPermissions.isNotEmpty()) {
                            WorkflowChip(
                                label = stringResource(
                                    R.string.workflow_chip_missing_permissions_count,
                                    missingPermissions.size
                                ),
                                iconRes = R.drawable.rounded_security_24,
                                containerColor = MaterialTheme.colorScheme.errorContainer,
                                contentColor = MaterialTheme.colorScheme.onErrorContainer
                            )
                        } else if (permissionCount > 0) {
                            WorkflowChip(
                                label = stringResource(R.string.workflow_chip_permissions_count, permissionCount),
                                iconRes = R.drawable.rounded_security_24,
                                containerColor = chipColor,
                                contentColor = chipContent
                            )
                        }
                        WorkflowChip(
                            label = stringResource(R.string.workflow_chip_steps, workflow.steps.size),
                            iconRes = R.drawable.rounded_dashboard_fill_24,
                            containerColor = chipColor,
                            contentColor = chipContent
                        )
                    }
                }

                Box {
                    IconButton(
                        onClick = { menuExpanded = true },
                        modifier = Modifier.size(moreSize)
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_more_vert),
                            contentDescription = stringResource(R.string.workflow_item_more_options),
                            // ⚠️ 用 `onSurfaceVariant` 再压一点 alpha：⋮ 在右下角，
                            //    与徽标同处一行；不压的话它的对比度会盖过徽标，
                            //    变成整张卡最显眼的东西（ShortX 的卡片操作图标都很收）。
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            modifier = Modifier.size(iconInner)
                        )
                    }
                    DropdownMenuPopup(
                        expanded = menuExpanded,
                        onDismissRequest = { menuExpanded = false }
                    ) {
                        DropdownMenuGroup(
                            shapes = MenuDefaults.groupShape(index = 0, count = 1),
                            modifier = Modifier
                                .width(IntrinsicSize.Max)
                                .widthIn(min = 156.dp, max = 236.dp),
                            containerColor = MenuDefaults.groupStandardContainerColor,
                        ) {
                            Column(modifier = Modifier.padding(vertical = 10.dp)) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 14.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    topMenuActions.take(3).forEach { item ->
                                        WorkflowQuickActionButton(
                                            icon = item.icon,
                                            contentDescription = stringResource(item.textRes),
                                            onClick = {
                                                menuExpanded = false
                                                item.onClick()
                                            }
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.height(10.dp))
                                (topMenuActions.drop(3) + regularMenuActions).forEach { item ->
                                    WorkflowFlatMenuItem(
                                        text = stringResource(item.textRes),
                                        icon = item.icon,
                                        onClick = {
                                            menuExpanded = false
                                            item.onClick()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun workflowSwitchColors(
    colorfulCardsEnabled: Boolean,
    visualColors: WorkflowVisuals.CardColors,
) = if (colorfulCardsEnabled) {
    SwitchDefaults.colors(
        checkedThumbColor = Color(visualColors.iconTint),
        checkedTrackColor = Color(visualColors.accentBackground),
        checkedBorderColor = Color(visualColors.accentBackground),
        checkedIconColor = Color(visualColors.accentBackground),
        uncheckedThumbColor = MaterialTheme.colorScheme.surface,
        uncheckedTrackColor = Color(visualColors.chipBackground),
        uncheckedBorderColor = Color(visualColors.iconBackground),
        uncheckedIconColor = Color(visualColors.iconBackground),
    )
} else {
    SwitchDefaults.colors()
}

/**
 * 卡片上「执行」▶ / 「运行中」⏸ 图标的颜色。
 *
 * ⚠️ 多彩模式下是**预先合成好的实色**（主题色 × 40% 叠在卡片底色上，见
 * `WorkflowVisuals.EXECUTE_ICON_ALPHA` 的实测来历），调用方不能再叠 alpha。
 * 非多彩模式没有主题色可用，退回 `primary` —— 它要在一张中性色卡上
 * 单独承担「这里能点」的语义（原先靠 `primaryContainer` 色块表达，色块已去掉了）。
 */
@Composable
private fun executeIconColor(
    colorfulCardsEnabled: Boolean,
    visualColors: WorkflowVisuals.CardColors,
): Color = if (colorfulCardsEnabled) {
    Color(visualColors.executeIconColor)
} else {
    MaterialTheme.colorScheme.primary
}

@Composable
private fun SpacerWidth(width: Dp) {
    Spacer(modifier = Modifier.width(width))
}
