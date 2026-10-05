package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 紧凑模式（瀑布流）的列数与卡片密度推导 —— **纯函数，无 Android 依赖**。
 *
 * ## 为什么要拆出来
 *
 * 「按宽度自适应列数」是这个布局的核心行为，也是最容易悄悄改坏的地方：
 * 阈值写错只会让某个屏幕尺寸下多一列或少一列，不报错、不崩溃，
 * **只有拿真机量才看得出来**。拆成纯函数后可以用 JVM 单测逐档锁住边界。
 *
 * ## 列数：按**可用宽度分档**，`3 / 4 / 5`
 *
 * 目标形态（用户 2026-10-05 给的 ShortX 参考）：
 *
 * | 形态 | 可用宽度 | 期望列数 |
 * |---|---|---|
 * | 手机竖屏（412dp 屏） | ≈ 388dp | **3** |
 * | 折叠展开（871 ~ 982dp 屏） | ≈ 847 ~ 958dp | **5** |
 *
 * ⚠️ **为什么不用「目标单列宽反算」**（本文件上一版就是这么写的，已废）：
 * 那两个锚点要求的目标单列宽**互相矛盾** ——
 * 手机要 3 列 ⇒ 目标宽须落在 `(105, 140]`；展开要 5 列 ⇒ 须落在 `(174, 210]`。
 * 没有哪个单值能同时满足，所以上一版只能靠「钳到 6」把展开强行压成 6 列，
 * 而那正是用户看到的「展开 6 列」—— 与 ShortX 的 5 列不符。
 * **分档不是偷懒，是因为「宽度 → 列数」这个映射在这里本来就不是连续函数。**
 *
 * ⚠️ **上限 5**：展开态锁死在 5 列。再宽也不加 —— 5 列时单列约 170dp，
 * 已经是「卡片内容不挤」的合理上限；加到 6 列会让单列掉到 150dp 以下，
 * 名称与右下角操作区都会挤。
 *
 * ⚠️ 阈值的两个数取的是 Material 3 窗口尺寸类别的分界（600 / 840）**思路**，
 * 但中间档阈值下调到 `800dp`（而非 840）—— 展开屏可能报 847dp，
 * 用 840 会让它落进 4 列档，与 ShortX 的 5 列不符。
 *
 * ⚠️ **不要复用 `MainComposeShell.kt` 里的 `840.dp`** —— 那是「**是否显示侧边导航栏**」
 * 的判据，还额外要求「宽 > 高」。两者语义完全不同
 * （`ChatScreen.kt` 的 `chatMessageMaxWidths` 注释里记过同一个坑）。
 *
 * ⚠️ **入参是「可用宽度」**（已扣掉外边距），不是屏幕宽度。
 */
/** 三档列数的可用宽度上界（不含）。 */
internal val COMPACT_GRID_THREE_COLUMN_MAX_WIDTH = 600.dp
internal val COMPACT_GRID_FOUR_COLUMN_MAX_WIDTH = 800.dp

internal const val COMPACT_GRID_MIN_COLUMNS = 3
internal const val COMPACT_GRID_MAX_COLUMNS = 5

/**
 * 卡片之间的水平间距（必须与 `LazyVerticalStaggeredGrid.horizontalArrangement` 一致）。
 *
 * ⚠️ **这个值直接决定观感，是「像不像 ShortX」的主要变量。**
 * 演进过程：8dp（挤在一起像表格）→ 12dp（有分隔感但仍然偏满）→ **20dp**。
 *
 * 目标来自对截图的**实测**（用户 2026-10-05 要求「卡片宽度小一点、间距大一点」）：
 * 同一屏宽下 ShortX 的卡片占 27.4% / 间距占 5.8%，而当时的 vFlow 是 31.5% / 2.3%。
 * 换算到 412dp 屏：目标卡片约 113dp、间距约 24dp。
 *
 * ⚠️ 间距与卡片宽度是**同一个方程的两端**（`n·卡片 + (n−1)·间距 = 可用宽`）——
 * 加大间距必然会让卡片变窄，反之亦然。所以调这个常量就等于同时调两者。
 */
internal val COMPACT_GRID_HORIZONTAL_SPACING = 20.dp

/** 瀑布流内容区左右各留 [COMPACT_GRID_CONTENT_PADDING]。 */
internal val COMPACT_GRID_CONTENT_PADDING = 12.dp

/**
 * 单列宽度决定的卡片密度档位。
 *
 * ⚠️ 按**单列宽度**分档而不是按列数 —— 两者不等价：
 * 手机 3 列的单列宽约 127dp，而折叠屏展开态 5 列的单列宽约 168dp，
 * **比手机的还宽**。若按列数分档，展开态的卡片会被错误地缩成小字号。
 */
internal enum class CompactCardDensity {
    /** 单列 ≥ 145dp（展开态 5 列，约 150dp）：名称 3 行、说明最多 4 行。 */
    ROOMY,

    /** 单列 ≥ 105dp（手机 3 列，约 113dp）：名称 3 行、说明最多 3 行。 */
    NORMAL,

    /** 更窄：名称 2 行、说明最多 2 行。 */
    TIGHT,
}

internal data class WorkflowCompactGridSpec(
    val columns: Int,
    val laneWidth: Dp,
    val density: CompactCardDensity,
)

/**
 * 由**可用宽度**推导列数、单列宽度与卡片密度。
 *
 * @param availableWidth 已扣掉左右内容内边距的可用宽度。
 */
internal fun workflowCompactGridSpec(availableWidth: Dp): WorkflowCompactGridSpec {
    val columns = columnCountFor(availableWidth)
    val lane = laneWidthFor(availableWidth, columns)
    return WorkflowCompactGridSpec(
        columns = columns,
        laneWidth = lane,
        density = densityFor(lane),
    )
}

/**
 * 可用宽度 → 列数：三档 `3 / 4 / 5`（分档理由见文件头）。
 *
 * ⚠️ 下限 3 是硬的，且**不做 1/2 列** —— 窄屏下 2 列卡片过宽、留白多，
 * 正是这次要修的病。320dp 的老机型也必须 3 列。
 */
internal fun columnCountFor(availableWidth: Dp): Int = when {
    availableWidth < COMPACT_GRID_THREE_COLUMN_MAX_WIDTH -> 3
    availableWidth < COMPACT_GRID_FOUR_COLUMN_MAX_WIDTH -> 4
    else -> COMPACT_GRID_MAX_COLUMNS
}

/**
 * 单列宽度 =（可用宽度 − 列间距总和）/ 列数。
 *
 * ⚠️ 结果钳到 ≥ 0：可用宽度极小时（例如预览或分屏的极端窄态），
 * 扣掉间距会得到负数，而负数尺寸传进 Compose 会在**测量阶段**抛异常 ——
 * 崩在布局里、栈上看不到本函数。有单测锁这条。
 */
internal fun laneWidthFor(availableWidth: Dp, columns: Int): Dp {
    if (columns <= 0) return 0.dp
    val spacing = COMPACT_GRID_HORIZONTAL_SPACING * (columns - 1)
    val lane = (availableWidth - spacing) / columns
    return if (lane < 0.dp) 0.dp else lane
}

/** 单列宽度 → 卡片密度档。 */
internal fun densityFor(laneWidth: Dp): CompactCardDensity = when {
    laneWidth >= 145.dp -> CompactCardDensity.ROOMY
    laneWidth >= 105.dp -> CompactCardDensity.NORMAL
    else -> CompactCardDensity.TIGHT
}

/**
 * 顶部文件夹 Tab 栏的一个条目。
 *
 * 没有「未分类」这一档 —— 用户 2026-10-04 定的：**只有「全部」+ 各文件夹**。
 * 还没归到任何文件夹的工作流，只在「全部」里出现。
 */
internal data class WorkflowFolderTab(
    val folderId: String,
    val name: String,
    val workflowCount: Int,
)

/** 「全部」这一个 Tab 的哨兵 id（它不是真实文件夹）。 */
internal const val WORKFLOW_TAB_ALL = "vflow.tab.all"

/**
 * 按当前选中的 Tab 过滤工作流 —— **纯函数，有单测**。
 *
 * 两条分支：
 * - [WORKFLOW_TAB_ALL]：全部放行。
 * - 其它：只留 `folderId` 等于该 id 的。
 *
 * ⚠️ **没有「未分类」分支**。不能写成 `folderId == null || folderId !in knownFolderIds`
 * 那种兜底 —— 用户明确要求「未分类的工作流不单独一档」，
 * 而且「指向已删文件夹的悬空 folderId」也一并只在「全部」里可见。
 * 那种兜底会**凭空多出一个 Tab**，用户会以为是自己建的。
 */
internal fun filterByFolderTab(
    workflows: List<com.chaomixian.vflow.core.workflow.model.Workflow>,
    selectedFolderId: String,
): List<com.chaomixian.vflow.core.workflow.model.Workflow> =
    if (selectedFolderId == WORKFLOW_TAB_ALL) {
        workflows
    } else {
        workflows.filter { it.folderId == selectedFolderId }
    }

/**
 * 组装 Tab 列表：「全部」+ 各文件夹。
 *
 * ⚠️⚠️ **「全部」的计数必须单独传 [totalWorkflowCount]**，不能写成
 * `folderTabs.sumOf { it.workflowCount }` —— 后者的口径是「**在文件夹里**的工作流数」，
 * 未归类（`folderId == null`）的那些**根本没被算进去**。只有一个文件夹时，
 * 那个数字恰好等于该文件夹的数量，看起来「像是对的」，于是很容易被长期忽略；
 * 一旦有根工作流，「全部」就会**少报**。
 *
 * ## 为什么「全部」的数字与各文件夹之和对不上是对的
 *
 * 未归类的工作流只在「全部」里可见（本文件不设「未分类」档），所以
 * `全部 ≥ Σ各文件夹` 是**正常现象**，不是 bug。改这条之前先读
 * [filterByFolderTab] 的注释。
 */
internal fun folderTabItems(
    folderTabs: List<WorkflowFolderTab>,
    allTabLabel: String,
    totalWorkflowCount: Int,
): List<WorkflowFolderTab> = listOf(
    WorkflowFolderTab(
        folderId = WORKFLOW_TAB_ALL,
        name = allTabLabel,
        workflowCount = totalWorkflowCount,
    )
) + folderTabs
