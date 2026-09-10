// 文件: main/java/com/chaomixian/vflow/ui/workflow_editor/MagicVariablePickerSheet.kt
package com.chaomixian.vflow.ui.workflow_editor

import android.content.DialogInterface
import android.os.Bundle
import android.os.Parcelable
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.module.OutputKeyDefinition
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.VPropertyDef
import com.chaomixian.vflow.core.types.parser.VariablePathParser
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.textfield.TextInputLayout
import kotlinx.parcelize.Parcelize

/**
 * 代表一个可供选择的变量的数据模型。
 * @param variableReference 变量的引用字符串。
 * 对于魔法变量, 格式为 "{{stepId.outputId}}";
 * 对于命名变量, 格式为 "[[variableName]]"。
 * @param variableName 变量的可读名称。
 * @param originDescription 描述变量来源的文本, 如 "来自: 查找文本" 或 "命名变量 (数字)"。
 * @param dictionaryKeys 该变量为字典时可静态枚举的键（fork 新增，默认空 = 无声明键）。
 *        用于函数工作流返回值：进入属性导航页时可直接点选键，而不是手动输入键名。
 */
@Parcelize
data class MagicVariableItem(
    val variableReference: String,
    val variableName: String,
    val originDescription: String,
    val typeId: String = VTypeRegistry.ANY.id,
    val dictionaryKeys: List<OutputKeyDefinition> = emptyList()
) : Parcelable

@Parcelize
private data class NavigationCrumb(
    val label: String,
    val item: MagicVariableItem
) : Parcelable

/**
 * RecyclerView 列表项的密封类，支持两种类型：
 * 1. ClearAction: 一个特殊操作项，用于清除当前输入框的魔法变量连接。
 * 2. VariableGroup: 代表一个完整的变量分组，包含标题和变量列表，将渲染在一个卡片中。
 */
sealed class PickerListItem {
    object ClearAction : PickerListItem()
    data class VariableGroup(val title: String, val variables: List<MagicVariableItem>) : PickerListItem()
}

private sealed class NavigationListItem {
    data class Header(val title: String, val subtitle: String? = null) : NavigationListItem()
    data class PropertyEntry(
        val property: VPropertyDef,
        val nextItem: MagicVariableItem,
        val canNavigateDeeper: Boolean
    ) : NavigationListItem()
    data class ActionEntry(
        val title: String,
        val subtitle: String? = null,
        val action: ActionNode,
        val expanded: Boolean = false
    ) : NavigationListItem()
}

private sealed class ActionNode {
    data class SelectCurrent(val item: MagicVariableItem) : ActionNode()
    data class PromptDictionaryKey(val item: MagicVariableItem) : ActionNode()
    data class PromptListIndex(val item: MagicVariableItem) : ActionNode()
    data class PromptStringIndex(val item: MagicVariableItem) : ActionNode()
}

private data class NavigationState(
    val currentItem: MagicVariableItem? = null,
    val acceptedTypes: Set<String> = emptySet(),
    val showUseSelfOption: Boolean = false,
)

/**
 * 魔法变量选择器底部表单 (BottomSheetDialogFragment)。
 * 显示可用魔法变量的分组列表以及一个“清除”选项。
 */
class MagicVariablePickerSheet : BottomSheetDialogFragment() {

    /** 选择回调：当用户选择一个变量或清除操作时触发。null 表示清除了选择。 */
    var onSelection: ((MagicVariableItem?) -> Unit)? = null
    var onDismissed: (() -> Unit)? = null
    private var onInlineVariableSelection: ((MagicVariableItem) -> Unit)? = null

    private lateinit var rootRecyclerView: RecyclerView
    private lateinit var navigationContainer: View
    private lateinit var navigationRecyclerView: RecyclerView
    private lateinit var breadcrumbContainer: LinearLayout
    private lateinit var breadcrumbScrollView: HorizontalScrollView
    private lateinit var currentPathTitle: TextView
    private lateinit var currentPathSubtitle: TextView
    private lateinit var sheetTitleView: TextView
    private lateinit var sheetDoneButton: MaterialButton

    private var expandedActionNode: ActionNode? = null
    private var currentNavigationState: NavigationState? = null
    private var currentNavigationStack: MutableList<NavigationCrumb> = mutableListOf()
    private var currentStepVariables: Map<String, List<MagicVariableItem>> = emptyMap()
    private var currentNamedVariables: Map<String, List<MagicVariableItem>> = emptyMap()
    private var currentAllSteps: List<ActionStep> = emptyList()

    companion object {
        /**
         * 创建 MagicVariablePickerSheet 实例，并接收分组后的变量数据和过滤条件。
         */
        fun newInstance(
            stepVariables: Map<String, List<MagicVariableItem>>,
            namedVariables: Map<String, List<MagicVariableItem>>,
            acceptsMagicVariable: Boolean,
            acceptsNamedVariable: Boolean,
            acceptedMagicVariableTypes: Set<String> = emptySet(),
            enableTypeFilter: Boolean = false,
            allowClear: Boolean = true,
            allSteps: List<ActionStep> = emptyList(),
            initialVariableReference: String? = null
        ): MagicVariablePickerSheet {
            return MagicVariablePickerSheet().apply {
                arguments = Bundle().apply {
                    putSerializable("stepVariables", HashMap(stepVariables))
                    putSerializable("namedVariables", HashMap(namedVariables))
                    putBoolean("acceptsMagic", acceptsMagicVariable)
                    putBoolean("acceptsNamed", acceptsNamedVariable)
                    putSerializable("acceptedTypes", HashSet(acceptedMagicVariableTypes))
                    putBoolean("enableTypeFilter", enableTypeFilter)
                    putBoolean("allowClear", allowClear)
                    putParcelableArrayList("allSteps", ArrayList(allSteps))
                    putString("initialVariableReference", initialVariableReference)
                }
            }
        }

        private const val ARG_NAVIGATION_STACK = "navigationStack"
    }

    /** 创建并返回底部表单的视图。 */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.sheet_magic_variable_picker, container, false)
        rootRecyclerView = view.findViewById(R.id.recycler_view_magic_variables)
        navigationContainer = view.findViewById(R.id.layout_navigation_container)
        navigationRecyclerView = view.findViewById(R.id.recycler_view_navigation)
        breadcrumbContainer = view.findViewById(R.id.layout_breadcrumbs)
        breadcrumbScrollView = view.findViewById(R.id.scroll_breadcrumbs)
        currentPathTitle = view.findViewById(R.id.text_current_path_title)
        currentPathSubtitle = view.findViewById(R.id.text_current_path_subtitle)
        sheetTitleView = view.findViewById(R.id.text_sheet_title)
        sheetDoneButton = view.findViewById(R.id.button_sheet_done)

        val acceptsMagic = arguments?.getBoolean("acceptsMagic", true) ?: true
        val acceptsNamed = arguments?.getBoolean("acceptsNamed", true) ?: true
        val allowClear = arguments?.getBoolean("allowClear", true) ?: true

        // Bundle 序列化会丢失 Map 顺序，需要手动排序
        @Suppress("UNCHECKED_CAST")
        val namedVariables = arguments?.getSerializable("namedVariables") as? Map<String, List<MagicVariableItem>> ?: emptyMap()
        @Suppress("UNCHECKED_CAST")
        val stepVariables = arguments?.getSerializable("stepVariables") as? Map<String, List<MagicVariableItem>> ?: emptyMap()
        currentNamedVariables = namedVariables
        currentStepVariables = stepVariables
        currentAllSteps = arguments?.getParcelableArrayList<ActionStep>("allSteps") ?: emptyList()
        currentNavigationStack =
            (arguments?.getParcelableArrayList<NavigationCrumb>(ARG_NAVIGATION_STACK)?.toMutableList())
                ?: mutableListOf()

        // 将分组数据转换为 RecyclerView 的列表项
        val items = mutableListOf<PickerListItem>().apply {
            if (allowClear) {
                add(PickerListItem.ClearAction)
            }

            // 添加命名变量分组（按原顺序）
            if (acceptsNamed) {
                namedVariables.forEach { (groupName, variableList) ->
                    add(PickerListItem.VariableGroup(groupName, variableList))
                }
            }

            // 添加步骤输出变量分组（按序号倒序排列）
            if (acceptsMagic) {
                stepVariables.entries
                    .sortedByDescending { entry -> entry.key.substringAfter("#").substringBefore(" ").toIntOrNull() ?: 0 }
                    .forEach { (groupName, variableList) ->
                        add(PickerListItem.VariableGroup(groupName, variableList))
                    }
            }
        }

        rootRecyclerView.layoutManager = LinearLayoutManager(context)
        rootRecyclerView.adapter = MagicVariableAdapter(items) { selectedItem ->
            if (selectedItem == null) {
                onSelection?.invoke(null)
                dismiss()
            } else {
                handleVariableSelection(selectedItem)
            }
        }

        navigationRecyclerView.layoutManager = LinearLayoutManager(context)
        sheetDoneButton.setOnClickListener {
            currentNavigationState?.currentItem?.let { item ->
                dispatchSelection(item)
            }
        }
        if (currentNavigationStack.isEmpty()) {
            currentNavigationStack = buildNavigationStackForReference(
                variableReference = arguments?.getString("initialVariableReference"),
                stepVariables = stepVariables,
                namedVariables = namedVariables
            )?.toMutableList() ?: mutableListOf()
        }

        if (currentNavigationStack.isEmpty()) {
            showRootList()
        } else {
            val currentItem = currentNavigationStack.last().item
            @Suppress("UNCHECKED_CAST")
            val acceptedTypes = arguments?.getSerializable("acceptedTypes") as? Set<String> ?: emptySet()
            val enableTypeFilter = arguments?.getBoolean("enableTypeFilter", false) ?: false
            showNavigation(
                NavigationState(
                    currentItem = currentItem,
                    acceptedTypes = acceptedTypes,
                    showUseSelfOption = !enableTypeFilter || acceptedTypes.isEmpty() || currentItem.typeId in acceptedTypes
                )
            )
        }
        return view
    }

    private fun buildNavigationStackForReference(
        variableReference: String?,
        stepVariables: Map<String, List<MagicVariableItem>>,
        namedVariables: Map<String, List<MagicVariableItem>>
    ): List<NavigationCrumb>? {
        if (variableReference.isNullOrBlank()) return null

        val parsed = VariablePathParser.parseSingleVariableReference(variableReference) ?: return null
        val allItems = namedVariables.values.flatten() + stepVariables.values.flatten()
        val rootMatch = allItems
            .mapNotNull { item ->
                val itemPath = VariablePathParser.parseSingleVariableReference(item.variableReference)?.path
                    ?: return@mapNotNull null
                if (parsed.path.take(itemPath.size) == itemPath && parsed.path.size > itemPath.size) {
                    item to itemPath
                } else {
                    null
                }
            }
            .maxByOrNull { (_, itemPath) -> itemPath.size }
            ?: return null
        val rootItem = rootMatch.first
        val rootPathSize = rootMatch.second.size

        val stack = mutableListOf(NavigationCrumb(rootItem.variableName, rootItem))
        var currentItem = rootItem
        parsed.path.drop(rootPathSize).forEach { segment ->
            val type = VTypeRegistry.getType(currentItem.typeId)
            val property = type.properties.firstOrNull { it.matches(segment) }
            val nextItem = currentItem.copy(
                variableReference = VariablePathParser.appendPathSegment(currentItem.variableReference, property?.name ?: segment),
                variableName = if (property != null) {
                    buildPropertyVariableName(currentItem, property.getLocalizedName(requireContext()))
                } else {
                    "${currentItem.variableName}.$segment"
                },
                originDescription = "(${property?.type?.getLocalizedName(requireContext()) ?: VTypeRegistry.ANY.getLocalizedName(requireContext())})",
                typeId = property?.type?.id ?: VTypeRegistry.ANY.id
            )
            stack += NavigationCrumb(property?.getLocalizedName(requireContext()) ?: segment, nextItem)
            currentItem = nextItem
        }

        return stack
    }

    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        onDismissed?.invoke()
    }

    private fun handleVariableSelection(item: MagicVariableItem) {
        @Suppress("UNCHECKED_CAST")
        val acceptedTypes = arguments?.getSerializable("acceptedTypes") as? Set<String> ?: emptySet()

        // 检查是否启用了类型限制（默认关闭，快捷指令风格）
        val enableTypeFilter = arguments?.getBoolean("enableTypeFilter", false) ?: false

        val type = VTypeRegistry.getType(item.typeId)
        val allProperties = type.properties

        // 如果未启用类型限制，或者没有指定接受的类型，则显示所有属性
        val properties = if (!enableTypeFilter || acceptedTypes.isEmpty()) {
            allProperties
        } else {
            // 只显示匹配的属性
            VTypeRegistry.getAcceptedProperties(item.typeId, acceptedTypes)
        }

        if (properties.isEmpty() && item.dictionaryKeys.isEmpty()) {
            // 既没有匹配的属性、也没有声明的键，则直接使用变量本身
            dispatchSelection(item)
        } else {
            openNavigationSheet(
                mutableListOf(
                    NavigationCrumb(item.variableName, item)
                )
            )
        }
    }

    private fun buildUseVariableSelfText(item: MagicVariableItem): String {
        return getString(R.string.magic_variable_use_self, item.variableName)
    }

    private fun buildPropertyVariableName(item: MagicVariableItem, propertyName: String): String {
        return getString(R.string.magic_variable_property_name, item.variableName, propertyName)
    }

    private fun renderVariableLabel(label: CharSequence): CharSequence {
        return PillRenderer.renderToSpannable(
            rawText = label.toString(),
            mode = PillRenderer.RenderMode.PREVIEW,
            allSteps = currentAllSteps,
            context = requireContext()
        )
    }

    private fun dispatchSelection(item: MagicVariableItem) {
        onInlineVariableSelection?.invoke(item) ?: onSelection?.invoke(item)
        dismiss()
    }

    private fun showRootList() {
        currentNavigationState = null
        currentNavigationStack.clear()
        expandedActionNode = null
        sheetTitleView.setText(R.string.desc_select_variable)
        sheetDoneButton.isVisible = false
        navigationContainer.isVisible = false
        rootRecyclerView.isVisible = true
    }

    private fun showNavigation(state: NavigationState) {
        currentNavigationState = state
        expandedActionNode = null
        sheetTitleView.setText(R.string.magic_variable_select_property_sheet_title)
        sheetDoneButton.isVisible = true
        rootRecyclerView.isVisible = false
        navigationContainer.isVisible = true
        renderBreadcrumbs()
        renderCurrentPathCard(state.currentItem)
        renderNavigationList(state)
    }

    private fun renderBreadcrumbs() {
        breadcrumbContainer.removeAllViews()

        currentNavigationStack.forEachIndexed { index, crumb ->
            if (index > 0) {
                val arrow = ImageView(requireContext()).apply {
                    setImageResource(R.drawable.ic_chevron_right_20)
                    imageTintList = android.content.res.ColorStateList.valueOf(
                        com.google.android.material.color.MaterialColors.getColor(
                            requireContext(),
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                            0
                        )
                    )
                    val size = TypedValue.applyDimension(
                        TypedValue.COMPLEX_UNIT_DIP,
                        20f,
                        resources.displayMetrics
                    ).toInt()
                    layoutParams = LinearLayout.LayoutParams(size, size).apply {
                        val spacing = TypedValue.applyDimension(
                            TypedValue.COMPLEX_UNIT_DIP,
                            8f,
                            resources.displayMetrics
                        ).toInt()
                        marginStart = spacing
                        marginEnd = spacing
                    }
                }
                breadcrumbContainer.addView(arrow)
            }
            breadcrumbContainer.addView(
                createBreadcrumbChip(
                    renderVariableLabel(crumb.label),
                    selected = index == currentNavigationStack.lastIndex
                ) {
                    currentNavigationStack = currentNavigationStack.take(index + 1).toMutableList()
                    val currentItem = currentNavigationStack.last().item
                    currentNavigationState?.let { state ->
                        showNavigation(
                            state.copy(
                                currentItem = currentItem,
                                showUseSelfOption = true
                            )
                        )
                    }
                }
            )
        }

        if (currentNavigationStack.isEmpty()) {
            val arrow = ImageView(requireContext()).apply {
                visibility = View.GONE
            }
            breadcrumbContainer.addView(arrow)
        }

        breadcrumbScrollView.post { breadcrumbScrollView.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun renderCurrentPathCard(item: MagicVariableItem?) {
        currentPathTitle.text = item?.variableName?.let(::renderVariableLabel) ?: getString(R.string.desc_select_variable)
        currentPathSubtitle.text = item?.originDescription ?: getString(R.string.magic_variable_navigation_hint)
    }

    private fun renderNavigationList(state: NavigationState) {
        val item = state.currentItem ?: return
        val type = VTypeRegistry.getType(item.typeId)
        val properties = if (state.acceptedTypes.isEmpty()) {
            type.properties
        } else {
            VTypeRegistry.getAcceptedProperties(item.typeId, state.acceptedTypes)
        }

        val rows = mutableListOf<NavigationListItem>()
        rows += NavigationListItem.Header(
            title = getString(R.string.magic_variable_section_properties),
            subtitle = if (properties.isEmpty()) getString(R.string.magic_variable_no_more_properties) else null
        )
        rows += properties.map { prop ->
            val nextItem = item.copy(
                variableReference = VariablePathParser.appendPathSegment(item.variableReference, prop.name),
                variableName = buildPropertyVariableName(item, prop.getLocalizedName(requireContext())),
                originDescription = "(${prop.type.getLocalizedName(requireContext())})",
                typeId = prop.type.id
            )
            NavigationListItem.PropertyEntry(
                property = prop,
                nextItem = nextItem,
                canNavigateDeeper = nextItem.typeId != VTypeRegistry.ANY.id && VTypeRegistry.getType(nextItem.typeId).properties.isNotEmpty()
            )
        }

        // 已声明的键（fork 新增）：函数工作流的返回值可静态枚举键（如 code/msg），
        // 直接列出供点选，生成 {{stepId.result.code}}，无需手动输入键名。
        // 过滤掉与内置属性同名的键：字典 getProperty 优先命中内置属性，此类键无法通过属性访问取到。
        val declaredKeyRows = item.dictionaryKeys
            .filter { key -> key.name.isNotBlank() && type.properties.none { it.matches(key.name) } }
            .map { key ->
                val keyType = VTypeRegistry.getType(key.typeName)
                val nextItem = item.copy(
                    variableReference = VariablePathParser.appendPathSegment(item.variableReference, key.name),
                    variableName = buildPropertyVariableName(item, key.name),
                    originDescription = "(${keyType.getLocalizedName(requireContext())})",
                    typeId = keyType.id,
                    dictionaryKeys = emptyList()
                )
                NavigationListItem.PropertyEntry(
                    property = VPropertyDef(name = key.name, displayName = key.name, type = keyType),
                    nextItem = nextItem,
                    canNavigateDeeper = keyType.id != VTypeRegistry.ANY.id && keyType.properties.isNotEmpty()
                )
            }
        if (declaredKeyRows.isNotEmpty()) {
            rows += NavigationListItem.Header(
                title = getString(R.string.magic_variable_section_declared_keys)
            )
            rows += declaredKeyRows
        }

        val dynamicActions = when (item.typeId) {
            VTypeRegistry.DICTIONARY.id -> listOf(
                NavigationListItem.Header(getString(R.string.magic_variable_section_dynamic)),
                NavigationListItem.ActionEntry(
                    title = getString(R.string.magic_variable_select_dictionary_value),
                    subtitle = getString(R.string.magic_variable_dictionary_key_hint),
                    action = ActionNode.PromptDictionaryKey(item),
                    expanded = expandedActionNode == ActionNode.PromptDictionaryKey(item)
                )
            )
            VTypeRegistry.LIST.id -> listOf(
                NavigationListItem.Header(getString(R.string.magic_variable_section_dynamic)),
                NavigationListItem.ActionEntry(
                    title = getString(R.string.magic_variable_select_list_index_value),
                    subtitle = getString(R.string.magic_variable_list_index_hint),
                    action = ActionNode.PromptListIndex(item),
                    expanded = expandedActionNode == ActionNode.PromptListIndex(item)
                )
            )
            VTypeRegistry.STRING.id -> listOf(
                NavigationListItem.Header(getString(R.string.magic_variable_section_dynamic)),
                NavigationListItem.ActionEntry(
                    title = getString(R.string.magic_variable_select_string_index_value),
                    subtitle = getString(R.string.magic_variable_string_index_hint),
                    action = ActionNode.PromptStringIndex(item),
                    expanded = expandedActionNode == ActionNode.PromptStringIndex(item)
                )
            )
            else -> emptyList()
        }
        rows += dynamicActions

        navigationRecyclerView.adapter = MagicVariableNavigationAdapter(rows,
            onPropertyClick = { propertyItem ->
                if (propertyItem.canNavigateDeeper) {
                    val nextStack = currentNavigationStack.toMutableList().apply {
                        add(NavigationCrumb(
                            propertyItem.property.getLocalizedName(requireContext()),
                            propertyItem.nextItem
                        ))
                    }
                    openNavigationSheet(nextStack)
                } else {
                    dispatchSelection(propertyItem.nextItem)
                }
            },
            onActionClick = { action ->
                expandedActionNode = if (expandedActionNode == action) null else action
                currentNavigationState?.let(::renderNavigationList)
            },
            onActionConfirm = { action, rawValue ->
                val sourceItem = when (action) {
                    is ActionNode.PromptDictionaryKey -> action.item
                    is ActionNode.PromptListIndex -> action.item
                    is ActionNode.PromptStringIndex -> action.item
                    else -> null
                } ?: return@MagicVariableNavigationAdapter

                val newRef = VariablePathParser.appendPathSegment(sourceItem.variableReference, rawValue)
                val newItem = sourceItem.copy(
                    variableReference = newRef,
                    variableName = when (action) {
                        is ActionNode.PromptStringIndex -> "${sourceItem.variableName}[$rawValue]"
                        is ActionNode.PromptListIndex -> "${sourceItem.variableName}[$rawValue]"
                        else -> "${sourceItem.variableName}.$rawValue"
                    },
                    originDescription = when (action) {
                        is ActionNode.PromptStringIndex -> "(${VTypeRegistry.STRING.getLocalizedName(requireContext())})"
                        is ActionNode.PromptListIndex -> sourceItem.originDescription
                        else -> "(${VTypeRegistry.ANY.getLocalizedName(requireContext())})"
                    },
                    typeId = when (action) {
                        is ActionNode.PromptStringIndex -> VTypeRegistry.STRING.id
                        is ActionNode.PromptListIndex -> sourceItem.typeId
                        else -> VTypeRegistry.ANY.id
                    }
                )

                val crumbLabel = when (action) {
                    is ActionNode.PromptStringIndex -> "[$rawValue]"
                    is ActionNode.PromptListIndex -> "[$rawValue]"
                    else -> rawValue
                }
                val nextStack = currentNavigationStack.toMutableList().apply {
                    add(NavigationCrumb(crumbLabel, newItem))
                }
                openNavigationSheet(nextStack)
            },
            onInlineVariableRequested = { input ->
                openInlineVariableSheet { selected ->
                    val reference = VariablePathParser.canonicalizeNamedVariableReference(selected.variableReference)
                    input.insertVariablePill(reference)
                }
            },
            allSteps = currentAllSteps
        )
    }

    private fun openInlineVariableSheet(onSelected: (MagicVariableItem) -> Unit) {
        val picker = newInstance(
            stepVariables = currentStepVariables,
            namedVariables = currentNamedVariables,
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            acceptedMagicVariableTypes = emptySet(),
            enableTypeFilter = false,
            allowClear = false,
            allSteps = currentAllSteps
        ).apply {
            onInlineVariableSelection = onSelected
        }
        picker.show(parentFragmentManager, "MagicVariablePickerSheet.inline")
    }

    private fun createBreadcrumbChip(
        label: CharSequence,
        selected: Boolean,
        onClick: () -> Unit
    ): Chip {
        return (layoutInflater.inflate(R.layout.chip_filter, breadcrumbContainer, false) as Chip).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            text = label
            isCheckable = false
            isClickable = true
            isCheckedIconVisible = false
            setOnClickListener { onClick() }
            chipBackgroundColor = android.content.res.ColorStateList.valueOf(
                com.google.android.material.color.MaterialColors.getColor(
                    requireContext(),
                    if (selected) com.google.android.material.R.attr.colorSecondaryContainer else com.google.android.material.R.attr.colorSurfaceContainerHigh,
                    0
                )
            )
            setTextColor(
                com.google.android.material.color.MaterialColors.getColor(
                    requireContext(),
                    if (selected) com.google.android.material.R.attr.colorOnSecondaryContainer else com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0
                )
            )
        }
    }

    private fun openNavigationSheet(stack: MutableList<NavigationCrumb>) {
        @Suppress("UNCHECKED_CAST")
        val acceptedTypes = arguments?.getSerializable("acceptedTypes") as? Set<String> ?: emptySet()
        val nextSheet = newInstance(
            stepVariables = currentStepVariables,
            namedVariables = currentNamedVariables,
            acceptsMagicVariable = arguments?.getBoolean("acceptsMagic", true) ?: true,
            acceptsNamedVariable = arguments?.getBoolean("acceptsNamed", true) ?: true,
            acceptedMagicVariableTypes = acceptedTypes,
            enableTypeFilter = arguments?.getBoolean("enableTypeFilter", false) ?: false,
            allowClear = arguments?.getBoolean("allowClear", true) ?: true,
            allSteps = currentAllSteps
        ).apply {
            arguments = Bundle(arguments ?: Bundle()).apply {
                putParcelableArrayList(ARG_NAVIGATION_STACK, ArrayList(stack))
            }
            onSelection = this@MagicVariablePickerSheet.onSelection
            onInlineVariableSelection = this@MagicVariablePickerSheet.onInlineVariableSelection
        }
        nextSheet.show(parentFragmentManager, "MagicVariablePickerSheet.nav")
        dismissAllowingStateLoss()
    }
}

/**
 * MagicVariablePickerSheet 中 RecyclerView 的适配器。
 * 支持“清除操作”项和“变量分组卡片”项两种视图类型。
 */
class MagicVariableAdapter(private val items: List<PickerListItem>, private val onVariableClick: (MagicVariableItem?) -> Unit) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    companion object { private const val TYPE_ACTION = 0; private const val TYPE_GROUP = 1 }
    override fun getItemViewType(position: Int) = if (items[position] is PickerListItem.ClearAction) TYPE_ACTION else TYPE_GROUP
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = if (viewType == TYPE_ACTION) ActionViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_magic_variable_action, parent, false)) else GroupViewHolder(LayoutInflater.from(parent.context).inflate(R.layout.item_magic_variable_group_card, parent, false), onVariableClick)
    override fun getItemCount() = items.size
    class GroupViewHolder(view: View, private val onVariableClick: (MagicVariableItem?) -> Unit) : RecyclerView.ViewHolder(view) {
        private val titleTextView: TextView = view.findViewById(R.id.group_title)
        private val variablesContainer: LinearLayout = view.findViewById(R.id.variables_container)
        fun bind(group: PickerListItem.VariableGroup) {
            titleTextView.text = group.title
            variablesContainer.removeAllViews()
            val inflater = LayoutInflater.from(itemView.context)
            group.variables.forEach { variableItem ->
                val itemView = inflater.inflate(R.layout.item_magic_variable, variablesContainer, false)
                val nameTextView: TextView = itemView.findViewById(R.id.variable_name)
                val originTextView: TextView = itemView.findViewById(R.id.variable_origin)
                nameTextView.text = variableItem.variableName
                originTextView.text = variableItem.originDescription
                itemView.setOnClickListener { onVariableClick(variableItem) }
                variablesContainer.addView(itemView)
            }
        }
    }
    class ActionViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val actionTextView: TextView = view.findViewById(R.id.action_text)
        fun bind(text: String) { actionTextView.text = text }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ActionViewHolder) {
            holder.bind(holder.itemView.context.getString(R.string.magic_variable_clear_action))
            holder.itemView.setOnClickListener { onVariableClick(null) }
        } else if (holder is GroupViewHolder) {
            holder.bind(items[position] as PickerListItem.VariableGroup)
        }
    }
}

private class MagicVariableNavigationAdapter(
    private val items: List<NavigationListItem>,
    private val onPropertyClick: (NavigationListItem.PropertyEntry) -> Unit,
    private val onActionClick: (ActionNode) -> Unit,
    private val onActionConfirm: (ActionNode, String) -> Unit,
    private val onInlineVariableRequested: (RichTextView) -> Unit,
    private val allSteps: List<ActionStep>
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private companion object {
        const val TYPE_HEADER = 0
        const val TYPE_PROPERTY = 1
        const val TYPE_ACTION = 2
    }

    override fun getItemViewType(position: Int): Int {
        return when (items[position]) {
            is NavigationListItem.Header -> TYPE_HEADER
            is NavigationListItem.PropertyEntry -> TYPE_PROPERTY
            is NavigationListItem.ActionEntry -> TYPE_ACTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return when (viewType) {
            TYPE_HEADER -> HeaderViewHolder(inflater.inflate(R.layout.item_magic_variable_group, parent, false))
            TYPE_PROPERTY -> PropertyViewHolder(inflater.inflate(R.layout.item_magic_variable, parent, false), onPropertyClick)
            else -> ActionViewHolder(inflater.inflate(R.layout.item_magic_variable_expandable_action, parent, false), onActionClick, onActionConfirm, onInlineVariableRequested, allSteps)
        }
    }

    override fun getItemCount(): Int = items.size

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = items[position]) {
            is NavigationListItem.Header -> (holder as HeaderViewHolder).bind(item)
            is NavigationListItem.PropertyEntry -> (holder as PropertyViewHolder).bind(item)
            is NavigationListItem.ActionEntry -> (holder as ActionViewHolder).bind(item)
        }
    }

    private class HeaderViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val titleView: TextView = view.findViewById(R.id.group_title)
        fun bind(item: NavigationListItem.Header) {
            titleView.text = item.title
        }
    }

    private class PropertyViewHolder(
        view: View,
        private val onPropertyClick: (NavigationListItem.PropertyEntry) -> Unit
    ) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = view.findViewById(R.id.variable_name)
        private val subtitleView: TextView = view.findViewById(R.id.variable_origin)

        fun bind(item: NavigationListItem.PropertyEntry) {
            val context = itemView.context
            nameView.text = item.property.getLocalizedName(context)
            subtitleView.text = "${item.property.name}  (${item.property.type.getLocalizedName(context)})"
            val rightIcon = itemView.findViewById<ImageView?>(R.id.image_item_chevron)
            rightIcon?.isVisible = item.canNavigateDeeper
            itemView.setOnClickListener { onPropertyClick(item) }
        }
    }

    private class ActionViewHolder(
        view: View,
        private val onActionClick: (ActionNode) -> Unit,
        private val onActionConfirm: (ActionNode, String) -> Unit,
        private val onInlineVariableRequested: (RichTextView) -> Unit,
        private val allSteps: List<ActionStep>
    ) : RecyclerView.ViewHolder(view) {
        private val nameView: TextView = view.findViewById(R.id.variable_name)
        private val subtitleView: TextView = view.findViewById(R.id.variable_origin)
        private val header: View = view.findViewById(R.id.layout_action_header)
        private val expandableContent: View = view.findViewById(R.id.layout_expandable_content)
        private val inputLayout: TextInputLayout = view.findViewById(R.id.layout_inline_input)
        private val input: RichTextView = view.findViewById(R.id.edit_inline_input)
        private val cancelButton: MaterialButton = view.findViewById(R.id.button_inline_cancel)
        private val confirmButton: MaterialButton = view.findViewById(R.id.button_inline_confirm)

        fun bind(item: NavigationListItem.ActionEntry) {
            nameView.text = item.title
            subtitleView.text = item.subtitle.orEmpty()
            val rightIcon = itemView.findViewById<ImageView?>(R.id.image_item_chevron)
            rightIcon?.setImageResource(
                if (item.expanded) R.drawable.rounded_keyboard_arrow_down_24
                else R.drawable.ic_chevron_right_20
            )
            header.setOnClickListener { onActionClick(item.action) }
            expandableContent.isVisible = item.expanded
            input.hint = item.subtitle
            if (!item.expanded) {
                input.text?.clear()
            } else {
                input.setRichText(input.text?.toString().orEmpty(), allSteps)
                input.requestFocus()
            }
            inputLayout.setEndIconOnClickListener {
                onInlineVariableRequested(input)
            }
            cancelButton.setOnClickListener { onActionClick(item.action) }
            confirmButton.setOnClickListener {
                val rawValue = input.text?.toString()?.trim().orEmpty()
                if (rawValue.isNotEmpty()) {
                    onActionConfirm(item.action, rawValue)
                }
            }
        }
    }
}
