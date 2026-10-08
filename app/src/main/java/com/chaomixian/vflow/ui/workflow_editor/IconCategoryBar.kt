package com.chaomixian.vflow.ui.workflow_editor

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.MaterialSymbolCategories
import com.chaomixian.vflow.core.workflow.MaterialSymbolNames
import com.chaomixian.vflow.ui.workflow_list.WORKFLOW_TAB_ALL
import com.chaomixian.vflow.ui.workflow_list.WorkflowFolderTab
import com.chaomixian.vflow.ui.workflow_list.WorkflowFolderTabBarSwitch

/**
 * 「常用」这一格的哨兵 id。
 *
 * ⚠️ 带 `vflow.icon.category.` 前缀是为了与真分类 id（`action` / `media` …）
 * **不可能撞车** —— 那 11 个 id 由生成脚本从上游元数据派生，将来可能新增，
 * 用一个裸词 `popular` 只是"眼下不冲突"。
 */
internal const val ICON_CATEGORY_POPULAR = "vflow.icon.category.popular"

/**
 * 图标选择页的**分类栏**。
 *
 * ## 为什么复用工作流页那两个 Tab 栏组件
 *
 * 用户的原始要求是「模仿工作流页面的 table 栏，它会根据『液态玻璃』那个开关
 * 使用不同样式」。分类栏与文件夹栏在交互上是同一件事：一排互斥的筛选标签 +
 * 计数 + 横向滚动。既然要"像"，最可靠的做法就是**用同一个组件**——
 * 各写一套的话，将来改配色或手势只会改一处，表现为「图标页的栏跟工作流页不像」。
 *
 * ## 三处必须特殊处理的地方
 *
 * 1. **`showFolderMenu = false`**（两版都要传）。
 *    组件的菜单判据是「`folderId != WORKFLOW_TAB_ALL` ⇒ 这是个真实文件夹」，
 *    而这里传进去的全是分类 id ⇒ **会被当成文件夹**，长按（或玻璃版点两下）
 *    就弹出「重命名 / 导出 / **解散** / **删除**」。点了「删除」就是数据全没
 *    （本项目没有撤销）。传空 lambda **不够**：菜单照样弹，只是点了没反应，
 *    比不弹更困惑。必须能整个关掉。
 *
 * 2. **`draggable = false`**。
 *    ⚠️ 拖动**关掉**（用户 2026-10-05 定的分工）：横滑 = 滚动分类栏，
 *    长按拖动留给工作流页的文件夹栏。组件本身已支持长按起拖
 *    （`longPressDrag = true`），与滚动不冲突，关掉只是省掉那条手势通道。
 *    ⚠️ 此前这里还要传一个 `minTabWidth = 76.dp` 才滚得动，那是**组件的缺陷**
 *    （宽度按可用宽度均分⇒名字长的分类把计数挤没）——现在宽度由内容自然宽度
 *    推出（`glassTabWidthFor`），溢出即自动滚动，**这个页面与工作流页行为一致**，
 *    不再需要任何本页专属的宽度参数。
 *
 * 3. **两个哨兵值的映射**。组件的 `onSelect(String)` 只给 tab id，而外层要的是
 *    「分类 id 或 `null`（= 全部）」。[WORKFLOW_TAB_ALL] → `null`，
 *    [ICON_CATEGORY_POPULAR] → 原样（它本身就是一个"选区"），其余透传。
 */
@Composable
internal fun IconCategoryBar(
    liquidGlassEnabled: Boolean,
    selectedCategoryId: String?,
    onSelectCategory: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    // ⚠️ 「全部」的计数用**去重后的总数**：`MaterialSymbolNames.ALL` 是唯一的
    //    权威名单，而 `categories.sumOf { it.iconNames.size }` 是各分类之和 ——
    //    两者本应相等，但分类表是生成出来的，万一有图标漏进任何一组，
    //    相加的数会比真实总数**偏小**，而栏上写着"全部 4100"却点出 4150 个，
    //    用户只会觉得计数不可信。故这里取真正会被展示的那个数。
    val allCount = MaterialSymbolNames.ALL.size

    val tabs = buildList {
        add(
            WorkflowFolderTab(
                folderId = WORKFLOW_TAB_ALL,
                name = stringResource(R.string.icon_category_all),
                workflowCount = allCount,
            )
        )
        add(
            WorkflowFolderTab(
                folderId = ICON_CATEGORY_POPULAR,
                name = stringResource(R.string.icon_category_popular),
                workflowCount = MaterialSymbolCategories.POPULAR.size,
            )
        )
        for (category in MaterialSymbolCategories.ALL) {
            add(
                WorkflowFolderTab(
                    folderId = category.id,
                    name = stringResource(category.nameRes),
                    workflowCount = category.iconNames.size,
                )
            )
        }
    }

    val selectedTabId = selectedCategoryId ?: WORKFLOW_TAB_ALL

    val onSelect: (String) -> Unit = { id ->
        onSelectCategory(if (id == WORKFLOW_TAB_ALL) null else id)
    }

    // ⚠️⚠️ 四个空 lambda 是**必需的形参**，不是"还没接"。它们只有在
    //    `showFolderMenu = true` 时才会被调用，而本页恒传 `false`。
    //    删掉它们做不到 —— 组件的签名要求这四个回调存在。
    val noopFolderAction: (String) -> Unit = {}
    // ⚠️ 导出那一路的签名多了 `withIcons`（2026-10-08 起有两种格式），
    //    故**不能**复用上面那个 `(String) -> Unit`。这两个 noop 都不该被调用
    //    （本栏传了 `showFolderMenu = false`，菜单根本不会出现），
    //    它们只是让签名对得上。
    val noopFolderExport: (String, Boolean) -> Unit = { _, _ -> }

    // ⚠️ 走**同一个分派入口**（`WorkflowFolderTabBarSwitch`）而不是自己写
    //    `if (liquidGlassEnabled) A else B` —— 工作流页的两个调用点也是走它的。
    //    自己再判一次的话，将来这个开关分了第三态（或参数又加一个），
    //    三处各自走偏，而表现是「图标页的栏跟工作流页不一样」——正是用户本次
    //    要求消除的那种差异，且不报错。
    WorkflowFolderTabBarSwitch(
        liquidGlassEnabled = liquidGlassEnabled,
        tabs = tabs,
        selectedFolderId = selectedTabId,
        onSelect = onSelect,
        onRenameFolder = noopFolderAction,
        onExportFolder = noopFolderExport,
        onDissolveFolder = noopFolderAction,
        onDeleteFolder = noopFolderAction,
        // ⚠️⚠️ **两侧留白走 `contentInset`，不能加在 `modifier` 上**。
        //    两者看起来一样，但 `horizontalScroll` 会给自己的节点套一层
        //    `clipScrollableContainer`（`ScrollableAreaKt.scrollableArea` 内部就会加，
        //    **没有开关**）⇒ 加在 modifier 上时 padding 落在**视口之外**，
        //    指示块按下时放大到 1.39 倍、超出 4dp 内边距的部分被切掉
        //    （截图里第一格左侧缺了一块就是它）。
        //    加在 `contentInset`（视口之内）就没这个问题。
        //    ⚠️ `fillMaxWidth` 仍需保留：玻璃版的 `BoxWithConstraints` 靠它拿
        //    整幅可用宽度（内部按 Tab 数量自算胶囊宽度、靠左放）。
        modifier = modifier.fillMaxWidth(),
        contentInset = 16.dp,
        // ⚠️ 本栏的宿主是 **Android `LinearLayout`**（`clipChildren` 默认 true），
        //    而指示块按下会放大到 1.39 倍、比 46dp 的栏高出约 3.5dp/侧 ——
        //    不给余量就被上下**切平**。10dp 是「够 + 有余量」的取值。
        //    （工作流页的宿主在 Compose 里，不需要这个。）
        verticalSlack = 10.dp,
        // ⚠️⚠️ **必须保持 `true`（默认值，这里显式写出来是为了防止被人"顺手关掉"）**。
        //    关掉它不只是"少一条手势" —— `WorkflowFolderGlassTabBar` 里两个手势
        //    modifier 都被 `draggable` 门控着（`interactiveHighlight.gestureModifier`
        //    与 `dampedDragAnimation.modifier`），关掉 = **长按连光晕都没有**，
        //    表现是「按下去什么都没发生」（已实际踩过）。
        //    两条手势通道由**时间**分开，不冲突：横滑 = 滚动，长按拖动 = 移动指示块。
        draggable = true,
        showFolderMenu = false,
        // ⚠️⚠️ **分类栏保持玻璃版**（用户 2026-10-05 明确：「不要改成普通 tab 栏」）。
        //    13 个分类放不下 ⇒ 宽度由内容自然宽度推出、溢出即自动横向滚动；
        //    长按拖动已在组件内建（`longPressDrag = true`）⇒ 横滑滚动与拖动互不打架。
    )
}
