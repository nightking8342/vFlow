package com.chaomixian.vflow.ui.workflow_list

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.text.rememberTextMeasurer
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

/**
 * 单个 Tab 的**最小宽度**。
 *
 * ⚠️ 它不是「裁剪阈值」而是**最小触控目标** —— 一两个字的名字（「全部 13」）
 * 自然宽只有 60dp 上下，再窄下去相邻两格的手指按压会互相蹭到。
 * Material 的无障碍建议是 48dp，这里取 64dp 是因为胶囊里还要留内边距。
 */
private val GLASS_TAB_BAR_MIN_TAB_WIDTH = 64.dp

/**
 * 单格宽度相对可用宽度的**上限比例**。
 *
 * ⚠️ 防的是「一个超长文件夹名占满大半屏」—— 那时用户连「这条栏还有别的东西」
 * 都看不出来，拖动一格也要滑很远。超出部分按 `Ellipsis` 截断（与改动前一致）。
 */
private const val GLASS_TAB_BAR_MAX_TAB_WIDTH_FRACTION = 0.6f

/** Tab 格子内部左右各留 [GLASS_TAB_CONTENT_HORIZONTAL_PADDING]。 */
private val GLASS_TAB_CONTENT_HORIZONTAL_PADDING = 8.dp

/**
 * 由「每格内容的自然宽度」推出整条栏**统一的格宽**。
 *
 * ⚠️⚠️ **必须统一，不能每格按自己的内容定宽** —— 指示块的定位、拖动换算、
 * 点击反查、栏宽计算**四处**都建立在「格宽相等」这个前提上（见 `folderTabTapAt`
 * 与 `barWidth` 的注释）。所以取**最宽那一格**为准，短的格子多留白。
 *
 * ⚠️⚠️ **不写死阈值**：三语名字长度差很远（中文「操作」2 字 / 英文
 * `Communication` 13 字符），任何写死的值都只能对一种语言正确。第一版写死 76dp，
 * 中文四字分类「通讯社交 633」就被挤成了「通讯社…」—— 而**计数直接消失**，
 * 因为数字排在名字后面、名字先占满了整格。
 *
 * 三条边界：
 * - 内容为空 ⇒ `0.dp`（调用方据此不渲染指示块）；
 * - 上限 = `availableWidth × [maxFraction]`；
 * - 下限 = [minWidth]，且**不与上限打架**：首帧 `maxWidth` 可能是 0，
 *   直接 `coerceIn(min, upper)` 会因区间倒置抛 `IllegalArgumentException`。
 */
internal fun glassTabWidthFor(
    contentWidths: List<Dp>,
    availableWidth: Dp,
    horizontalPadding: Dp,
    minWidth: Dp,
    maxFraction: Float,
): Dp {
    val widest = contentWidths.maxOrNull() ?: return 0.dp
    val upperBound = maxOf(availableWidth * maxFraction, minWidth)
    return (widest + horizontalPadding).coerceIn(minWidth, upperBound)
}

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
fun WorkflowFolderGlassTabBar(
    tabs: List<WorkflowFolderTab>,
    selectedFolderId: String,
    onSelect: (String) -> Unit,
    onRenameFolder: (String) -> Unit,
    onExportFolder: (String, Boolean) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 指示块**是否可拖动**（默认 `true`，保持工作流 Tab 栏的既有行为）。
     *
     * ⚠️ 设为 `false` 时**指示块本身仍在**：选中项的滑动动画、按下放大、
     * 透镜效果都由动画值驱动，与手势无关 —— 只是拖不动。
     */
    draggable: Boolean = true,
    /**
     * 是否启用**文件夹管理菜单**（再点一次当前 Tab 唤出「重命名 / 导出 / 解散 / 删除」）。
     *
     * ⚠️⚠️ 图标选择页的分类栏必须传 `false` —— 它复用本组件展示**图标分类**
     * （把分类 id 当 `folderId` 传），而下面的判据是「点当前 Tab 就弹菜单」、
     * 菜单项是「删除文件夹」。不关掉的话**点两下当前分类就会看到删除文件夹的菜单**。
     *
     * ⚠️ 传空 lambda 不够：菜单照样弹，只是点了没反应。
     */
    showFolderMenu: Boolean = true,
    /**
     * 栏两侧的**内边距**（默认 `0.dp` = 栏紧贴给定宽度）。
     *
     * ⚠️⚠️ **不能改用「调用方在 modifier 上加 padding」** —— 两者视觉效果一样，
     * 但裁切行为完全不同：
     *
     * - 加在 `modifier` 上 ⇒ padding **在滚动视口之外**，而
     *   `horizontalScroll` 会给自己的节点套一层 `clipScrollableContainer`
     *   （`ScrollableAreaKt.scrollableArea` 内部就会加，**没有开关**）。
     *   于是视口边缘 = padding 内侧，**指示块按下时放大到 1.39 倍、
     *   超出 4dp 内边距的那部分会被切掉**（左端第一格最明显）。
     * - 加在这里 ⇒ padding **在滚动视口之内**，指示块的溢出落在 padding 带里，
     *   照样看得见，只在屏幕真正边缘才裁。
     *
     * ⚠️ 工作流页传默认值 `0.dp`（它不在滚动容器里、也从不溢出）⇒ 行为不变。
     */
    contentInset: Dp = 0.dp,
    /**
     * 栏**上下**预留的空间（默认 `0.dp`）—— 给指示块按下时的放大留余量。
     *
     * ⚠️⚠️ **只在「宿主是 Android `ViewGroup`」时才需要**：那类容器的
     * `clipChildren` 默认是 **true**，而指示块按下会放大到 1.39 倍，
     * 比 46dp 的栏高出约 **3.5dp**（每侧）⇒ 不给余量就被上下**切平**。
     * 工作流页的宿主在 Compose 里（LazyColumn 的 item），Compose 不主动裁切
     * 子节点 ⇒ 它传默认值即可，行为与改动前一致。
     */
    verticalSlack: Dp = 0.dp,
) {
    if (tabs.isEmpty()) return

    // ⚠️ 横向滚动状态。声明在 `BoxWithConstraints` **之外** —— 里面是 `maxWidth`
    //    作用域，放进去会随约束变化重建。
    val scrollState = rememberScrollState()

    val isLightTheme = !isSystemInDarkTheme()
    val containerColor = MaterialTheme.colorScheme.surfaceContainer.copy(alpha = 0.40f)
    val accentColor = MaterialTheme.colorScheme.primary
    // 玻璃边缘高光：浅色主题下用白光提边，深色主题下压一档。
    val rimColor = if (isLightTheme) Color.White else Color.White.copy(alpha = 0.6f)

    val density = LocalDensity.current
    // ⚠️ 量文字自然宽度用（见 `tabWidth` 的推导）——必须每次组合共享同一个实例，
    //    否则每格各建一个 `TextMeasurer`（内部带布局缓存），整条栏白付 N 份。
    val textMeasurer = rememberTextMeasurer()
    val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
    val animationScope = rememberCoroutineScope()
    // 只含 Tab 文字的那一层（供指示块采样放大）。与指示块平级 ⇒ 不自采样。
    val itemsBackdrop = rememberLayerBackdrop()

    val selectedIndex = tabs.indexOfFirst { it.folderId == selectedFolderId }.coerceAtLeast(0)
    var menuTarget by remember { mutableStateOf<String?>(null) }
    var menuExpanded by remember { mutableStateOf(false) }

    BoxWithConstraints(modifier = modifier) {
        val innerWidth =
            (maxWidth - contentInset * 2 - GLASS_TAB_BAR_PADDING * 2).coerceAtLeast(0.dp)

        // ⚠️⚠️ **宽度由「每格内容的自然宽度」推出，不再按可用宽度均分。**
        //    均分的问题是名字一长就把后面的计数挤没了（`Text` 的 `Ellipsis` 只
        //    截名字那一格，计数是它的兄弟节点、拿不到宽度就整个消失），
        //    而三语的名字长度差很远，写死任何阈值都只能对一种语言正确。
        //    推出来的宽度超出可用宽度时，下面的 `overflows` 会自动开横向滚动
        //    （滚动能力本来就有，此前只是被 `minTabWidth` 这个开关挡着）。
        // ⚠️ 两个 Text 的样式必须与 `GlassFolderTabContent` 里**逐字一致** ——
        //    这里量多少、那里就画多少，样式一分叉量出来的宽度就不等于实际占用
        //    （`FontWeight` 尤其隐蔽：`SemiBold` 比 `Normal` 宽一点，
        //    选中项的名字会被自己的格宽卡出一个省略号），而且**不报错**。
        val tabWidth = glassTabWidthFor(
            contentWidths = with(density) {
                tabs.map { tab ->
                    val selected = tab.folderId == selectedFolderId
                    textMeasurer.measure(
                        text = tab.name,
                        style = MaterialTheme.typography.labelLarge.copy(
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                        ),
                        maxLines = 1,
                    ).size.width.toDp() +
                        textMeasurer.measure(
                            text = " ${tab.workflowCount}",
                            style = MaterialTheme.typography.labelSmall,
                            maxLines = 1,
                        ).size.width.toDp()
                }
            },
            availableWidth = innerWidth,
            horizontalPadding = GLASS_TAB_CONTENT_HORIZONTAL_PADDING * 2,
            minWidth = GLASS_TAB_BAR_MIN_TAB_WIDTH,
            maxFraction = GLASS_TAB_BAR_MAX_TAB_WIDTH_FRACTION,
        )
        val tabWidthPx = with(density) { tabWidth.toPx() }
        val paddingPx = with(density) { GLASS_TAB_BAR_PADDING.toPx() }
        val barWidth = tabWidth * tabs.size + GLASS_TAB_BAR_PADDING * 2
        val contentHeight = GLASS_TAB_BAR_HEIGHT - GLASS_TAB_BAR_PADDING * 2

        val dampedDragAnimation = remember(
            animationScope,
            tabs.size,
            tabs,
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
                // ⚠️ 长按起拖 —— **不能改成即时起拖**。本组件可能被放进横向滚动容器
                //    （图标分类栏就是），即时起拖会与滚动抢同一条指针流
                //    （见 `inspectLongPressDragGestures` 的 KDoc）。
                //    代价是多一个长按阈值（底栏是即时起拖，两处手感因此不同）——
                //    这是「要拖动就得先表达拖动意图」的必然代价。
                longPressDrag = true,
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
                    // ⚠️ 高光中心算在**指示块自己的坐标系**里（`size` 就是指示块的尺寸，
                    //    而 `InteractiveHighlight.modifier` 正挂在指示块上、不会自动
                    //    叠加它的 `translationX`）⇒ 直接取自己的中点即可。
                    //    ⚠️ 原实现在这里乘了 `tabWidthPx`，那是**外层栏的**坐标系，
                    //    在指示块上算出来永远是偏右的一大段距离，光斑被推到块外。
                    position = { size, _ -> Offset(size.width / 2f, size.height / 2f) },
                    // ⚠️ 必须与 `DampedDragAnimation` 的 `longPressDrag` 传同一个值，
                    //    否则「光晕亮起」与「指示块开始跟手」会差一个长按阈值。
                    longPressDrag = true,
                )
            } else {
                null
            }
        }

        // ⚠️ 超宽时横向滚动。格宽由内容自然宽度推出 ⇒ **文件夹多、名字长时就会
        //    溢出**，此时不再把每格压窄（那正是计数被挤没的原因），而是滚动。
        //    ⚠️ `barWidth` 已含 `GLASS_TAB_BAR_PADDING * 2`，**不要再加一次** ——
        //    多加的后果是「刚好放得下时也判溢出」，栏尾会多出一小段空滚。
        val overflows = barWidth + contentInset * 2 > maxWidth

        // ⚠️ 外层 `Box` 只包到「栏的宽度」，不 `fillMaxWidth` —— 这就是「左对齐、
        //    宽度按 Tab 数量」的落点。调用方只需把它放进自己的 item 槽（默认即靠左）。
        //
        // ⚠️⚠️ 滚动**必须加在这一层**（而不是更外面）：`folderTabTapAt` 按
        //    `position.x` 反查第几格，而指针坐标是**相对被挂节点的局部坐标**
        //    （这个 `Box`）⇒ 滚了多少都不影响映射。把滚动加到外面再在外层做命中判定，
        //    就得手动加上 `scrollState.value`，漏了会「点中间那个却选中左边的」——
        //    而且**只有滚过之后**才出问题。
        Box(
            modifier = Modifier
                .padding(vertical = verticalSlack)
                .then(if (overflows) Modifier.horizontalScroll(scrollState) else Modifier)
                // ⚠️ 水平 padding 加在**滚动容器内部**（见 `contentInset` 的 KDoc）。
                .padding(horizontal = contentInset)
        ) {
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
                        // ⚠️ `showFolderMenu == false` 时（图标分类栏）整段跳过 ——
                        //    重复点当前分类应当**什么都不发生**，而不是弹出「删除文件夹」。
                        if (showFolderMenu && tab.folderId != WORKFLOW_TAB_ALL) {
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
                        // ⚠️ 只加一次 `interactiveHighlight`（它在 `gestureModifier` 与
                        //    `modifier` 两处都要用；`pointerInput` 是**叠加**的，
                        //    同一个实例挂两次会收到两遍事件、透镜位置跳成两处）。
                        .then(if (draggable) interactiveHighlight?.gestureModifier ?: Modifier else Modifier)
                        .then(if (draggable) dampedDragAnimation.modifier else Modifier)
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
    onExportFolder: (String, Boolean) -> Unit,
    onDissolveFolder: (String) -> Unit,
    onDeleteFolder: (String) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 指示块是否可拖动（默认 `true`）。⚠️ 只有**玻璃版**有这个开关，
     * 普通版没有拖拽手势，传什么都不影响 —— 图标分类栏把它设为 `false`。
     */
    draggable: Boolean = true,
    /**
     * 是否启用文件夹管理菜单（默认 `true`）。
     * ⚠️ 图标分类栏必须传 `false`（详见两版组件各自的 KDoc）。
     */
    showFolderMenu: Boolean = true,
    /**
     * 栏两侧内边距（**滚动容器内部**）。⚠️ 只对**玻璃版**有意义，见组件 KDoc；
     * 普通版不需要 —— 它是 `FilterChip` 自己撑宽 + 整条栏横滚，没有"放大溢出被裁"的问题。
     */
    contentInset: Dp = 0.dp,
    /** 栏上下预留的空间（宿主是 Android `ViewGroup` 时必须给，见组件 KDoc）。 */
    verticalSlack: Dp = 0.dp,
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
            // ⚠️⚠️ **`modifier` 原样转发，包括它的 `padding`** ——
            //    曾想过「把 padding 换成 `BoxWithConstraints` 上的 `padding`、
            //    让 `maxWidth` 变成内容宽度」，那样不对：`BoxWithConstraints`
            //    自己会把「外层约束」按自己的 padding **收缩后再交给内容**
            //    （`padding` 通过 `measure` 改约束），于是 `maxWidth` 已经是内宽，
            //    再加一次就等于扣两遍 —— 表现为「明明放得下却开了滚动」。
            //    ⇒ 调用方要留白，正确地传 `contentInset`（见列表模式调用点）。
            modifier = modifier,
            draggable = draggable,
            showFolderMenu = showFolderMenu,
            contentInset = contentInset,
            verticalSlack = verticalSlack,
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
            // ⚠️⚠️ **普通版必须自己把 `contentInset` 加回去**。它没有「视口内/视口外」
            //    的区分（`FilterChip` 自己撑宽、整条栏横滚，不存在放大被切的问题），
            //    所以调用方按玻璃版的口径把留白改成了 `contentInset`
            //    （列表模式的 `modifier` 里已不含 padding）—— 这里不补的话，
            //    关掉液态玻璃后第一枚 Chip 会**贴着屏幕左边缘**，
            //    而玻璃版正常，排障时会一直往玻璃那条路找。
            modifier = modifier.padding(horizontal = contentInset),
            // ⚠️ 必须转发 —— 漏了的话「关掉菜单」只在玻璃版生效，
            //    而这是**按开关切换样式**的页面：用户关掉液态玻璃后
            //    长按分类就会看到「删除文件夹」（玻璃版正常、普通版出事，
            //    排障时会一直往液态玻璃那条路找）。
            showFolderMenu = showFolderMenu,
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
    onExportFolder: (String, Boolean) -> Unit,
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
            // ⚠️ 必须与 `glassTabWidthFor` 的 `horizontalPadding` 用**同一个常量** ——
            //    两处脱节的话，算出来的格宽与实际排版差一点，而**不报错**，
            //    只是名字在临界情况下又开始出现省略号。
            .padding(horizontal = GLASS_TAB_CONTENT_HORIZONTAL_PADDING),
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
