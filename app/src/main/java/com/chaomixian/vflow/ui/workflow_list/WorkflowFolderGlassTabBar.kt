package com.chaomixian.vflow.ui.workflow_list

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import com.chaomixian.vflow.ui.main.glass.DampedDragAnimation
import com.chaomixian.vflow.ui.main.glass.InteractiveHighlight
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import kotlin.math.roundToInt

/** 玻璃胶囊的高度（含内边距）。比底部导航栏（64dp）矮一档 —— 它嵌在内容区里，不该抢焦点。 */
private val GLASS_TAB_BAR_HEIGHT = 46.dp

/** 胶囊内四边的留白 —— 滑动指示块就是这个范围内的一枚内嵌圆角块。 */
private val GLASS_TAB_BAR_PADDING = 4.dp

/** 单个 Tab 的期望宽度（上限）。实际会按可用宽度均分，窄了就让位、宽了就到此为止。 */
private val GLASS_TAB_BAR_DESIRED_TAB_WIDTH = 92.dp

/** 指示块按下时的放大倍率 —— 与底栏同一比例（78/56）。 */
private const val GLASS_TAB_INDICATOR_PRESSED_SCALE = 78f / 56f

/**
 * 文件夹 Tab 栏的**液态玻璃版**（fork 新增，2026-10-05）。
 *
 * 目标就是「跟底部导航栏差不多」：
 * 1. **滑动指示块可以拖着走**（[DampedDragAnimation]），带按下放大 + 透镜放大
 *    （`lens`）与跟手的速度形变；松手按最接近的位置落位；
 * 2. **宽度按 Tab 数量决定、左对齐**，不铺满整行；
 * 3. 高度 46dp，比底栏矮一档。
 *
 * ## ⚠️ 底座是「静态玻璃」而不是 `drawBackdrop`
 *
 * 底栏能用真 `drawBackdrop`，是因为它在 `Scaffold.bottomBar` 槽里、而内容区被
 * `Modifier.layerBackdrop(...)` 单独录了一层（`MainComposeShell.kt` 的 `backdrop`），
 * 底栏**不在那层里面**所以采样不到自己。
 *
 * 本控件是**内联在列表里的一个 item**（用户 2026-10-05 明确要求保持原位），
 * 任何承载它的图层都会**把它自己也录进去** ⇒ 采样到自身、每帧叠一层形成反馈。
 * 所以底座改用等效的静态画法（半透明底 + 纵向渐变描边 + 外阴影），
 * **只做外观、不做折射**；背后本来也只有页面背景色，折射不出东西。
 *
 * ⚠️ **滑动指示块仍是真 `drawBackdrop`**：它采样的是 [itemsBackdrop]
 * —— 下面那层**只含 Tab 文字**的隐形强调色副本。指示块与那个副本行是**平级**
 * 关系而非包含 ⇒ 不自采样，透镜放大是完整的。
 *
 * ## ⚠️ 菜单的唤出方式与普通版**不同**（点两次 vs 长按）
 *
 * 普通版（`WorkflowFolderTabBar`）用**长按当前 Tab** 唤出「重命名 / 导出 / 删除」。
 * 玻璃版**不能用长按** —— 指示块自己就靠长按起拖（同一个 `longPressTimeoutMillis`），
 * 两者会抢同一个超时点：拖得动就弹不出菜单，弹得出菜单就拖不动。
 *
 * 故玻璃版改成 **「再点一次当前已选中的 Tab ⇒ 呼出菜单」**（用户 2026-10-05 定）：
 * - 点别的 Tab ⇒ 正常切换（`onSelect`）；
 * - 点当前 Tab ⇒ 弹菜单（**不**重复触发 `onSelect`）。
 *
 * 这样两者用互不相干的手势通道（长按 = 拖动、单击 = 切换/菜单），不再打架。
 */
@Composable
internal fun WorkflowFolderGlassTabBar(
    tabs: List<WorkflowFolderTab>,
    selectedFolderId: String,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (tabs.isEmpty()) return

    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f)
    val accentColor = MaterialTheme.colorScheme.primary
    // 玻璃边缘高光：浅色主题下用白光提边，深色主题下压一档。
    val rimColor = if (isLightTheme) Color.White else Color.White.copy(alpha = 0.6f)

    val density = LocalDensity.current
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    // 只含 Tab 文字的那一层（供指示块采样放大）。与指示块平级 ⇒ 不自采样。
    val itemsBackdrop = rememberLayerBackdrop()

    val selectedIndex = tabs.indexOfFirst { it.folderId == selectedFolderId }.coerceAtLeast(0)
    var menuTarget by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val innerWidth = (maxWidth - GLASS_TAB_BAR_PADDING * 2).coerceAtLeast(0.dp)
        val evenWidth = innerWidth / tabs.size
        // 宽度**由 Tab 数量决定**：少的时候用期望宽度（于是整条栏是窄的、靠左），
        // 多的时候均分可用宽度（于是不会溢出屏幕）。
        val tabWidth = evenWidth.coerceAtMost(GLASS_TAB_BAR_DESIRED_TAB_WIDTH).coerceAtLeast(0.dp)
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val paddingPx = with(density) { GLASS_TAB_BAR_PADDING.toPx() }
        val barWidth = tabWidth * tabs.size + GLASS_TAB_BAR_PADDING * 2
        val contentHeight = GLASS_TAB_BAR_HEIGHT - GLASS_TAB_BAR_PADDING * 2

        val dampedDragAnimation = remember(
            animationScope,
            tabs.size,
            density,
            isLtr,
            tabWidthPx,
        ) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = selectedIndex.toFloat(),
                valueRange = 0f..(tabs.size - 1).toFloat(),
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = GLASS_TAB_INDICATOR_PRESSED_SCALE,
                onDragStarted = {},
                onDragStopped = {
                    val targetIndex = targetValue.fastRoundToInt().fastCoerceIn(0, tabs.size - 1)
                    animateToValue(targetIndex.toFloat())
                    onSelect(tabs[targetIndex].folderId)
                },
                onDrag = { _, dragAmount ->
                    if (tabWidthPx > 0f) {
                        updateValue(
                            (targetValue + dragAmount.x / tabWidthPx * if (isLtr) 1f else -1f)
                                .fastCoerceIn(0f, (tabs.size - 1).toFloat())
                        )
                    }
                }
            )
        }

        LaunchedEffect(selectedIndex, dampedDragAnimation) {
            dampedDragAnimation.animateToValue(selectedIndex.toFloat())
        }

        val interactiveHighlight = remember(animationScope, tabWidthPx) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                InteractiveHighlight(
                    animationScope = animationScope,
                    position = { size, _ ->
                        Offset(
                            if (isLtr) {
                                (dampedDragAnimation.value + 0.5f) * tabWidthPx
                            } else {
                                size.width - (dampedDragAnimation.value + 0.5f) * tabWidthPx
                            },
                            size.height / 2f
                        )
                    }
                )
            } else {
                null
            }
        }

        // ⚠️ 外层 `Box` 只包到「栏的宽度」，不 `fillMaxWidth` —— 这就是「左对齐、
        //    宽度按 Tab 数量」的落点。调用方只需把它放进自己的 item 槽（默认即靠左）。
        Box(
            modifier = Modifier
                .width(barWidth)
                .height(GLASS_TAB_BAR_HEIGHT)
                // ⚠️⚠️ 点按挂在**整条栏**上、按 x 反查 Tab，而**不是**给每个 Tab 挂
                //     `clickable` —— 滑动指示块画在最上层且正好盖住选中项，命中测试只走
                //     最上层 ⇒ 给 Tab 挂点击时**当前选中项永远收不到点击**（表现是
                //     「点当前 Tab 毫无反应」而点别的正常）。父节点与指示块都会收到同一条
                //     指针流，挂在栏上就没有遮挡问题。
                .folderTabTapAt { position ->
                    // ⚠️ 必须 `coerceIn`：最右一像素算出来正好等于 `tabs.size`
                    //    （栏宽 = 数量 × 格宽 + padding），不钳的话最右边那一格
                    //    永远点不中 —— 而且**不报错**，只是「点最后一个 Tab 没反应」。
                    val slot = (((position.x - paddingPx) / tabWidthPx).toInt())
                        .coerceIn(0, tabs.size - 1)
                    val tab = tabs[slot]
                    if (tab.folderId == selectedFolderId) {
                        // 点当前 Tab ⇒ 呼出菜单。「全部」是伪 Tab，没有可操作的对象。
                        // 菜单开着时点别处会先 `onDismissRequest` 关掉它，
                        // 所以下一次点同一个 Tab 必然是从 false → true，会重新弹。
                        if (tab.folderId != WORKFLOW_TAB_ALL) {
                            menuTarget = tab.folderId
                            menuExpanded = true
                        }
                    } else {
                        onSelect(tab.folderId)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            // ① 玻璃底座：真 `drawBackdrop`，采样下面那层 Tab 文字副本
            //    ⇒ 文字在胶囊里有一层模糊的、被提了饱和度的倒影，这就是「液态玻璃」观感。
            //    ⚠️ 不采样页面内容（会自采样，见类注释）；也**不做 `lens`** ——
            //       底座在指示块下面，再折射一次会把文字撕成两层。
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = itemsBackdrop,
                        shape = { CircleShape },
                        effects = {
                            vibrancy()
                            blur(8.dp.toPx())
                        },
                        highlight = { Highlight.Default },
                        shadow = {
                            Shadow.Default.copy(
                                color = Color.Black.copy(alpha = if (isLightTheme) 0.10f else 0.20f)
                            )
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
            )

            // ② 可见的 Tab 内容。
            Row(
                modifier = Modifier.padding(GLASS_TAB_BAR_PADDING),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    val isSelected = tab.folderId == selectedFolderId
                    GlassFolderTab(
                        tab = tab,
                        selected = isSelected,
                        tabWidth = tabWidth,
                        menuExpanded = menuExpanded && menuTarget == tab.folderId,
                        onDismissMenu = { menuExpanded = false },
                        onRenameFolder = onRenameFolder,
                        onExportFolder = onExportFolder,
                        onDissolveFolder = onDissolveFolder,
                        onDeleteFolder = onDeleteFolder,
                    )
                }
            }

            // ③ Tab 文字的一层**隐形强调色副本**，专门给指示块采样。
            Row(
                modifier = Modifier
                    .padding(GLASS_TAB_BAR_PADDING)
                    .clearAndSetSemantics {}
                    .alpha(0f)
                    .layerBackdrop(itemsBackdrop)
                    .graphicsLayer(colorFilter = ColorFilter.tint(accentColor)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                tabs.forEach { tab ->
                    GlassFolderTabContent(
                        tab = tab,
                        selected = tab.folderId == selectedFolderId,
                        tabWidth = tabWidth,
                    )
                }
            }

            // ④ 滑动指示块（放大镜 + 拖拽手柄）。画在最上层。
            if (tabWidthPx > 0f) {
                Box(
                    modifier = Modifier
                        .padding(GLASS_TAB_BAR_PADDING)
                        .graphicsLayer {
                            translationX = if (isLtr) {
                                dampedDragAnimation.value * tabWidthPx
                            } else {
                                -dampedDragAnimation.value * tabWidthPx
                            }
                        }
                        .then(interactiveHighlight?.gestureModifier ?: Modifier)
                        .then(dampedDragAnimation.modifier)
                        .drawBackdrop(
                            backdrop = itemsBackdrop,
                            shape = { CircleShape },
                            effects = {
                                val progress = dampedDragAnimation.pressProgress
                                lens(10.dp.toPx() * progress, 14.dp.toPx() * progress, true)
                            },
                            highlight = {
                                Highlight.Default.copy(alpha = dampedDragAnimation.pressProgress)
                            },
                            shadow = { Shadow(alpha = dampedDragAnimation.pressProgress) },
                            innerShadow = {
                                InnerShadow(
                                    radius = 8.dp * dampedDragAnimation.pressProgress,
                                    alpha = dampedDragAnimation.pressProgress,
                                )
                            },
                            layerBlock = {
                                scaleX = dampedDragAnimation.scaleX
                                scaleY = dampedDragAnimation.scaleY
                                val velocity = dampedDragAnimation.velocity / 10f
                                scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                                scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                            },
                            onDrawSurface = {
                                val progress = dampedDragAnimation.pressProgress
                                drawRect(
                                    color = if (isLightTheme) {
                                        Color.Black.copy(alpha = 0.10f)
                                    } else {
                                        Color.White.copy(alpha = 0.10f)
                                    },
                                    alpha = 1f - progress
                                )
                                drawRect(Color.Black.copy(alpha = 0.03f * progress))
                            }
                        )
                        .then(interactiveHighlight?.modifier ?: Modifier)
                        .height(contentHeight)
                        .width(tabWidth),
                )
            }
        }
    }
}

/**
 * 按液态玻璃开关在 [WorkflowFolderTabBar] 与 [WorkflowFolderGlassTabBar] 之间分派。
 *
 * ⚠️ 抽出来是为了让两个调用点（列表模式 / 紧凑模式）**不会各自走偏** ——
 * 直接在两处写 `if (liquidGlassEnabled) A else B` 的话，将来给其中一个版本加参数
 * 很容易只改一处，而表现是「换个布局就少了个功能」，很难联想到。
 */
@Composable
internal fun WorkflowFolderTabBarSwitch(
    liquidGlassEnabled: Boolean,
    tabs: List<WorkflowFolderTab>,
    selectedFolderId: String,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (liquidGlassEnabled) {
        WorkflowFolderGlassTabBar(
            tabs = tabs,
            selectedFolderId = selectedFolderId,
            onSelect = onSelect,
            onRenameFolder = onRenameFolder,
            onExportFolder = onExportFolder,
            onDissolveFolder = onDissolveFolder,
            onDeleteFolder = onDeleteFolder,
            modifier = modifier,
        )
    } else {
        WorkflowFolderTabBar(
            tabs = tabs,
            selectedFolderId = selectedFolderId,
            onSelect = onSelect,
            onRenameFolder = onRenameFolder,
            onExportFolder = onExportFolder,
            onDissolveFolder = onDissolveFolder,
            onDeleteFolder = onDeleteFolder,
            modifier = modifier,
        )
    }
}

/**
 * 一个 Tab（纯显示 + 菜单宿主）。
 *
 * ⚠️ **点击不在这里** —— 它挂在整条栏上按 x 反查（见 `WorkflowFolderGlassTabBar`
 * 的 `folderTabTapAt`）。指示块画在最上层、盖住选中项，挂在这里的 `clickable`
 * 对选中项**永远收不到事件**。
 */
@Composable
private fun RowScope.GlassFolderTab(
    tab: WorkflowFolderTab,
    selected: Boolean,
    tabWidth: Dp,
    menuExpanded: Boolean,
    onDismissMenu: () -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
) {
    Box(
        modifier = Modifier
            .width(tabWidth)
            .fillMaxHeight(),
        contentAlignment = Alignment.Center,
    ) {
        GlassFolderTabContent(tab = tab, selected = selected, tabWidth = tabWidth)

        // 菜单只对**真实文件夹**弹出（「全部」是伪 Tab，由调用方的判据保证）。
        if (menuExpanded) {
            WorkflowFolderMenu(
                expanded = true,
                folderId = tab.folderId,
                onDismiss = onDismissMenu,
                onRenameFolder = onRenameFolder,
                onExportFolder = onExportFolder,
                onDissolveFolder = onDissolveFolder,
                onDeleteFolder = onDeleteFolder,
            )
        }
    }
}

/**
 * Tab 的文字内容。
 *
 * ⚠️ 可见行与采样层**必须画得一模一样**（同样的 `tabWidth`、同样的排版参数），
 * 否则放大镜下的字形会与实体文字错开 —— 而且**不报错**，只是看着重影。
 */
@Composable
private fun GlassFolderTabContent(
    tab: WorkflowFolderTab,
    selected: Boolean,
    tabWidth: Dp,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .width(tabWidth)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        Text(
            text = tab.name,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = " ${tab.workflowCount}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
        )
    }
}
