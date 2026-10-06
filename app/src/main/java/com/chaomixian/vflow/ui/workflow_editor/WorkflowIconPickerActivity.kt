package com.chaomixian.vflow.ui.workflow_editor

import android.content.Intent
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlin.math.roundToInt
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.MaterialSymbolCategories
import com.chaomixian.vflow.core.workflow.MaterialSymbolNames
import com.chaomixian.vflow.ui.common.AppearanceManager
import com.chaomixian.vflow.ui.common.BaseActivity
import com.chaomixian.vflow.ui.common.VFlowTheme
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.textfield.TextInputEditText

/**
 * 图标选择器（**独立页面**）。
 *
 * ## 为什么从「嵌在更多选项 sheet 里」改成独立页面
 *
 * 原来图标网格直接嵌在 `EditorMoreOptionsSheet` 的滚动容器里。图标库只有 18 个时
 * 那是合理的（顺手就能选），扩到 4150 个之后有三个问题：
 *
 * 1. **sheet 太短**。图标网格占 240dp 后，sheet 里其余设置（主题色、重入策略、
 *    日志等级…）全被挤到需要滚很久的位置，而它们与图标是**不相干的设置**。
 * 2. **分类无处可放**。4150 个图标必须有分类导航，而分类侧栏/顶部 Chip 需要
 *    一整屏的宽度与高度 —— 嵌在 sheet 里只能塞成一行横向滚动的小 Chip，
 *    既看不清也点不准。
 * 3. **搜索框与 sheet 的软键盘冲突**。sheet 是 `BottomSheetDialog`，
 *    软键盘弹出时的 `SOFT_INPUT_ADJUST_RESIZE` 与 `NestedScrollView` 叠加，
 *    焦点在搜索框上时列表会被顶出可视区。
 *
 * ⇒ sheet 里只留**一行入口**（当前图标预览 + 「选择图标」按钮），
 * 点开本页面。
 *
 * ## 与调用方的契约
 *
 * 入参：`EXTRA_CURRENT_ICON`（当前选中的图标名或自定义图片路径，可空）。
 * 出参：`RESULT_OK` + `EXTRA_PICKED_ICON`（选中的**图标名**）。
 *
 * ⚠️ **本页面只返回内置图标名**，不处理自定义图片 —— 那是 sheet 里另一个按钮
 * （「从相册选择图片」）的职责。分开的理由：自定义图片要写文件、要有存储权限、
 * 失败原因有五种，与"从网格里点一个"是完全不同的交互与错误面。
 */
class WorkflowIconPickerActivity : BaseActivity() {

    companion object {
        const val EXTRA_CURRENT_ICON = "extra_current_icon"
        const val EXTRA_PICKED_ICON = "extra_picked_icon"

        /**
         * 每格的**目标**宽度。列数由它反算（见 [computeGridSpan]）。
         *
         * ⚠️ 84dp 是「疏密」的调参点，不是随手取的：格子里的圆形底是**写死的 52dp**，
         * 所以每格留白 = 目标宽 − 52dp，在这里是 32dp（左右各 16dp）。
         * 改动前后的实测对照（小米 MIX Fold 3 折叠态 328dp 屏）：
         *
         * | 目标宽 | 列数 | 每格 | 圆底间的空隙 |
         * |---|---|---|---|
         * | 60dp（上一版） | 5 | 65dp | 13dp —— **太挤**（用户 2026-10-05 反馈） |
         * | **84dp（当前）** | **4** | **82dp** | **30dp** |
         *
         * ⚠️ 空底下限 4dp（`item_icon_selector.xml` 根布局的 padding）⇒ 每格只要
         * ≥ 60dp 圆底就不会被裁；84dp 留了充足余量，不会因取整掉到危险区。
         */
        private const val CELL_TARGET_DP = 84f

        /**
         * 按**可用宽度**（RecyclerView 内容区宽度，已扣掉左右 padding）算列数。
         *
         * ⚠️⚠️ **列数曾两次写死**，每次都在真机上暴露一种问题：
         * - 固定 5：手机上正常，但**折叠屏展开态**（871dp）每格宽到 174dp，
         *   而圆底只有 52dp ⇒ 「右边间距为什么这么大」（用户 2026-10-05）。
         * - 改成 `floor(宽 / 60)` 之后展开态变成 14 列、每格 60dp —— 又**太密**
         *   （同一天的第二次反馈）。60dp 只是「不裁切」的下限，
         *   拿它当目标宽等于**永远贴着最密的一档排**。
         *
         * ⚠️ 故这里用**四舍五入到最接近的目标宽**，而不是「能塞几列就塞几列」：
         * 后者会让列数总落在上限、每格永远等于最小宽。四舍五入允许每格在
         * `[0.5×84, 1.5×84)` = `[42, 126)` 之间浮动，取到的是**观感最接近 84dp
         * 的那一档**。代价是每格可能略小于目标（328dp 屏 → 76dp），无害。
         *
         * ⚠️ 上限取 **10** 是照着真机量的：展开态内容区 847dp 恰好
         * `847 / 84 ≈ 10.08` ⇒ 10 列时每格 **84.7dp**，正好落在目标宽上。
         * 上限设小了（比如 8）反而会让展开态每格拉到 105dp、又开始见白。
         */
        internal fun computeGridSpan(availableWidthDp: Float): Int =
            (availableWidthDp / CELL_TARGET_DP).roundToInt().coerceIn(3, 10)
    }

    private lateinit var adapter: WorkflowIconPickerAdapter
    private lateinit var iconRecyclerView: RecyclerView
    private lateinit var editSearch: TextInputEditText
    private lateinit var textEmpty: View

    /**
     * 当前选中的分类。
     *
     * `null` = 「全部」（不是"未选"）；[ICON_CATEGORY_POPULAR] = 常用；
     * 其余是分类 id（`action` / `media` …）。
     *
     * ⚠️ 必须是 **Compose 的 `mutableStateOf`**：分类栏已经是 Compose 组件
     * （见 `IconCategoryBar`），它靠这个值高亮当前项。
     */
    private var selectedCategoryId by mutableStateOf<String?>(null)

    private var currentIcon: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_workflow_icon_picker)

        currentIcon = intent.getStringExtra(EXTRA_CURRENT_ICON)

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { finish() }

        textEmpty = findViewById(R.id.text_icon_empty)
        iconRecyclerView = findViewById(R.id.recycler_icons)
        editSearch = findViewById(R.id.edit_icon_search)

        adapter = WorkflowIconPickerAdapter { iconName ->
            setResult(
                RESULT_OK,
                Intent().putExtra(EXTRA_PICKED_ICON, iconName)
            )
            finish()
        }
        // ⚠️ 不覆写 `canScrollVertically()`：图标库 8300 项，必须让 RecyclerView
        //    自己虚拟化（布局里是 match_parent + 权重，撑满剩余高度）。
        iconRecyclerView.layoutManager = GridLayoutManager(this, computeGridSpan(contentWidthDp()))
        iconRecyclerView.adapter = adapter
        // ⚠️ 列数**必须跟着宽度走**，不能只在 `onCreate` 算一次：本项目主力机型是
        //    折叠屏，用户很可能在**停在本页时**展开/折叠 —— 那一瞬 Activity 未必重建
        //    （取决于 manifest 的 `configChanges`），列数就会停在旧值，表现是
        //    「展开后图标还是挤在小格子里」。挂在布局回调上就没有这个前提依赖。
        iconRecyclerView.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            val lm = iconRecyclerView.layoutManager as? GridLayoutManager
                ?: return@addOnLayoutChangeListener
            val wanted = computeGridSpan(contentWidthDp())
            // ⚠️ 必须判等再设：`spanCount` 的 setter 会**重新触发一次布局**，
            //    不判等就是「布局 → 回调 → 布局」的死循环（表现为页面卡死、无报错）。
            if (wanted != lm.spanCount) lm.spanCount = wanted
        }

        setupCategoryBar()
        applyFilter()

        // ⚠️ 用 `doAfterTextChanged` 而非 `TextWatcher` 三件套 —— 后者要写两个空方法。
        editSearch.doAfterTextChanged { applyFilter() }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    /**
     * RecyclerView 的**内容区**宽度（dp）—— 已扣掉布局里给的 `paddingHorizontal`。
     *
     * ⚠️ 不能拿 `displayMetrics.widthPixels`：那算出来的列数会让最右一列
     * 被 padding 挤掉一部分（12dp × 2 在 360dp 屏上是 6.7% 的宽度）。
     * 而 `GridLayoutManager` 是**按内容区**等分格子的，两边口径必须一致。
     *
     * ⚠️ `width` 在**首次布局前恒为 0**。那时退回按屏幕宽度估一个值：
     * 直接返回 0 会让列数被钳到下限 4（一进页面挤成一团），
     * 而布局回调随后就会把它纠正过来。
     */
    private fun contentWidthDp(): Float {
        val metrics = resources.displayMetrics
        val px = if (iconRecyclerView.width > 0) {
            iconRecyclerView.width - iconRecyclerView.paddingLeft - iconRecyclerView.paddingRight
        } else {
            metrics.widthPixels -
                with(metrics) { (12f * density).toInt() * 2 } // 布局里的 paddingHorizontal
        }
        return px / metrics.density
    }

    /**
     * 接上分类栏（Compose）。
     *
     * ⚠️ **开关的读法必须与工作流页一致**（`AppearanceManager.isLiquidGlassNavBarEnabled`）
     * —— 用户的原话是"它会根据『液态玻璃』那个开关使用不同样式"。读别的 key
     * 或不读，表现就是"开关拨了但图标页没变"。
     *
     * ⚠️ 在 `onCreate` 里读一次即可，**不必**订阅变更：`SettingsViewModel`
     * 改的是同一个 prefs，而本页是**独立 Activity**，用户改完开关再回来时
     * 页面会重建（或至少重新进），不存在"停留在本页时开关被改"的场景。
     */
    private fun setupCategoryBar() {
        val composeView = findViewById<ComposeView>(R.id.compose_icon_categories)
        val liquidGlassEnabled = AppearanceManager.isLiquidGlassNavBarEnabled(this)

        composeView.setContent {
            VFlowTheme {
                IconCategoryBar(
                    liquidGlassEnabled = liquidGlassEnabled,
                    selectedCategoryId = selectedCategoryId,
                    onSelectCategory = { id ->
                        selectedCategoryId = id
                        applyFilter()
                    },
                )
            }
        }
    }

    /** 按「当前分类 + 搜索词」重算候选列表。 */
    private fun applyFilter() {
        val iconNames = when (val cat = selectedCategoryId) {
            // ⚠️ 「全部」取的是 **`MaterialSymbolNames.ALL`**（生成期写下的权威名单），
            //    而**不是** `MaterialSymbolCategories.ALL.flatMap { it.iconNames }`。
            //    两者今天相等（4150），但后者**多了一层可出错的手**：分类表是
            //    生成脚本归并出来的，万一某个图标没被分进任何一组，它就会从
            //    「全部」里**静默消失** —— 用户永远找不到那个图标，
            //    却没有任何地方报错。有测试锁住两者必须一致（`MaterialSymbolCategoriesTest`）。
            null -> MaterialSymbolNames.ALL
            ICON_CATEGORY_POPULAR -> MaterialSymbolCategories.POPULAR
            else -> MaterialSymbolCategories.ALL.firstOrNull { it.id == cat }?.iconNames.orEmpty()
        }

        // ⚠️ 候选是**图标基础名**（`home`），而过滤器与资源名的关系藏在
        //    `availableIconResNames` 的拼装里。这里先拼成资源名再过滤，
        //    过滤器返回的也是资源名 —— 保证"搜索匹配"与"最终显示"用的是同一套名字。
        val resNames = iconNames.flatMap { listOf("rounded_${it}_24", "rounded_${it}_fill_24") }
        val filtered = IconSearchFilter.filter(resNames, editSearch.text?.toString().orEmpty())

        adapter.submitList(filtered)
        adapter.setSelectedIcon(currentIcon)

        // 空状态：分类 + 搜索词的组合可能一个都不剩（如"商务"里搜 "wifi"）
        val isEmpty = filtered.isEmpty()
        textEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        iconRecyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }
}
