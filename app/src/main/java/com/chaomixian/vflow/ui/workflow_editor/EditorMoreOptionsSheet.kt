// 文件: main/java/com/chaomixian/vflow/ui/workflow_editor/EditorMoreOptionsSheet.kt
package com.chaomixian.vflow.ui.workflow_editor

import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.ButtonGroupDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.ToggleButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.WorkflowVisuals
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
import com.chaomixian.vflow.ui.common.VFlowTheme
import com.chaomixian.vflow.ui.common.ThemeUtils
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.card.MaterialCardView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

class EditorMoreOptionsSheet : BottomSheetDialogFragment() {

    var workflow: Workflow? = null
    var onAiGenerateClicked: (() -> Unit)? = null
    var onUiInspectorClicked: (() -> Unit)? = null
    var onLogcatDebuggerClicked: (() -> Unit)? = null
    var onMetadataSaved: ((Workflow) -> Unit)? = null

    private lateinit var textWorkflowName: TextView
    private lateinit var textWorkflowId: TextView
    private lateinit var textWorkflowModified: TextView
    private lateinit var cardWorkflowInfo: MaterialCardView
    private lateinit var cardFunctionSignature: MaterialCardView
    private lateinit var textFunctionParamsSummary: TextView
    private lateinit var textFunctionReturnSummary: TextView

    private lateinit var editVersion: com.google.android.material.textfield.TextInputEditText
    private lateinit var editVFlowLevel: com.google.android.material.textfield.TextInputEditText
    private lateinit var editDescription: com.google.android.material.textfield.TextInputEditText
    private lateinit var editAuthor: com.google.android.material.textfield.TextInputEditText
    private lateinit var editHomepage: com.google.android.material.textfield.TextInputEditText
    private lateinit var editTags: com.google.android.material.textfield.TextInputEditText

    private lateinit var layoutAiGenerate: MaterialCardView
    private lateinit var layoutUiInspector: MaterialCardView
    private lateinit var layoutLogcatDebugger: MaterialCardView
    private lateinit var cardMoreMetadata: MaterialCardView
    private lateinit var layoutMoreMetadataHeader: LinearLayout
    private lateinit var layoutMoreMetadataContent: LinearLayout
    private lateinit var imageExpandIcon: ImageView

    private lateinit var switchMaxExecutionTime: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var switchSilentExecution: com.google.android.material.materialswitch.MaterialSwitch
    private lateinit var layoutMaxExecutionTimeSlider: LinearLayout
    private lateinit var textMaxExecutionTimeValue: TextView
    private lateinit var sliderMaxExecutionTime: com.google.android.material.slider.Slider
    private lateinit var reentryBehaviorComposeView: ComposeView
    private lateinit var logLevelComposeView: ComposeView
    private lateinit var layoutWorkflowVisuals: LinearLayout
    private lateinit var textColorfulWorkflowCardsDisabled: TextView
    private lateinit var cardVisualPreview: MaterialCardView
    private lateinit var cardVisualPreviewIcon: MaterialCardView
    private lateinit var imageVisualPreviewIcon: ImageView
    private lateinit var textVisualPreviewName: TextView
    private lateinit var textVisualPreviewColor: TextView
    private lateinit var textSelectedThemeColor: TextView
    private lateinit var iconPickerAdapter: WorkflowIconPickerAdapter
    private lateinit var themeColorAdapter: WorkflowThemeColorAdapter

    private var selectedIconRes: String = WorkflowVisuals.defaultIconResName()
    private var selectedThemeColor: String = WorkflowVisuals.defaultThemeColorHex()
    private var selectedReentryBehavior by mutableStateOf(WorkflowReentryBehavior.BLOCK_NEW)
    private var selectedLogLevel by mutableStateOf(WorkflowLogLevel.VERBOSE)

    private var isMoreMetadataExpanded = false
    private var metadataCommittedByAction = false

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val dialog = super.onCreateDialog(savedInstanceState) as BottomSheetDialog
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        dialog.setOnShowListener {
            val bottomSheet = dialog.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)
            if (bottomSheet != null) {
                val behavior = BottomSheetBehavior.from(bottomSheet)
                behavior.state = BottomSheetBehavior.STATE_EXPANDED
                behavior.skipCollapsed = true
            }
        }
        return dialog
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.sheet_editor_more_options, container, false)
    }

    override fun onDismiss(dialog: android.content.DialogInterface) {
        commitMetadataToEditorState(showToast = false)
        super.onDismiss(dialog)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // 初始化工作流信息视图
        textWorkflowName = view.findViewById(R.id.text_workflow_name)
        textWorkflowId = view.findViewById(R.id.text_workflow_id)
        textWorkflowModified = view.findViewById(R.id.text_workflow_modified)
        cardWorkflowInfo = view.findViewById(R.id.card_workflow_info)
        cardFunctionSignature = view.findViewById(R.id.card_function_signature)
        textFunctionParamsSummary = view.findViewById(R.id.text_function_params_summary)
        textFunctionReturnSummary = view.findViewById(R.id.text_function_return_summary)

        // 初始化元数据编辑视图
        editVersion = view.findViewById(R.id.edit_workflow_version)
        editVFlowLevel = view.findViewById(R.id.edit_workflow_vflow_level)
        editDescription = view.findViewById(R.id.edit_workflow_description)
        editAuthor = view.findViewById(R.id.edit_workflow_author)
        editHomepage = view.findViewById(R.id.edit_workflow_homepage)
        editTags = view.findViewById(R.id.edit_workflow_tags)

        layoutAiGenerate = view.findViewById(R.id.card_ai_generate)
        layoutUiInspector = view.findViewById(R.id.card_ui_inspector)
        layoutLogcatDebugger = view.findViewById(R.id.card_logcat_debugger)
        cardMoreMetadata = view.findViewById(R.id.card_more_metadata)
        layoutMoreMetadataHeader = view.findViewById(R.id.layout_more_metadata_header)
        layoutMoreMetadataContent = view.findViewById(R.id.layout_more_metadata_content)
        imageExpandIcon = view.findViewById(R.id.image_expand_icon)

        switchMaxExecutionTime = view.findViewById(R.id.switch_max_execution_time)
        switchSilentExecution = view.findViewById(R.id.switch_silent_execution)
        layoutMaxExecutionTimeSlider = view.findViewById(R.id.layout_max_execution_time_slider)
        textMaxExecutionTimeValue = view.findViewById(R.id.text_max_execution_time_value)
        sliderMaxExecutionTime = view.findViewById(R.id.slider_max_execution_time)
        reentryBehaviorComposeView = view.findViewById(R.id.compose_reentry_behavior)
        logLevelComposeView = view.findViewById(R.id.compose_log_level)
        layoutWorkflowVisuals = view.findViewById(R.id.layout_workflow_visuals)
        textColorfulWorkflowCardsDisabled = view.findViewById(R.id.text_colorful_workflow_cards_disabled)
        cardVisualPreview = view.findViewById(R.id.card_visual_preview)
        cardVisualPreviewIcon = view.findViewById(R.id.card_visual_preview_icon)
        imageVisualPreviewIcon = view.findViewById(R.id.image_visual_preview_icon)
        textVisualPreviewName = view.findViewById(R.id.text_visual_preview_name)
        textVisualPreviewColor = view.findViewById(R.id.text_visual_preview_color)
        textSelectedThemeColor = view.findViewById(R.id.text_selected_theme_color)

        setupReentryBehaviorSelector()
        setupLogLevelSelector()
        setupVisualPickers(view)
        val colorfulCardsEnabled = ThemeUtils.isColorfulWorkflowCardsEnabled(requireContext())
        layoutWorkflowVisuals.visibility = if (colorfulCardsEnabled) View.VISIBLE else View.GONE
        textColorfulWorkflowCardsDisabled.visibility = View.GONE

        val btnSaveMetadata = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.btn_save_metadata)

        // 填充工作流信息
        workflow?.let { wf ->
            textWorkflowName.text = wf.name
            textWorkflowId.text = wf.id

            val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            textWorkflowModified.text = dateFormat.format(Date(wf.modifiedAt))

            // 点击卡片复制 ID
            cardWorkflowInfo.setOnClickListener {
                copyToClipboard(wf.id)
            }

            bindFunctionSignature(wf)

            // 填充元数据
            editVersion.setText(wf.version)
            editVFlowLevel.setText(wf.vFlowLevel.toString())
            editDescription.setText(wf.description)
            editAuthor.setText(wf.author)
            editHomepage.setText(wf.homepage)
            editTags.setText(wf.tags.joinToString(", "))
            selectedIconRes = WorkflowVisuals.normalizeIconResName(wf.cardIconRes)
            selectedThemeColor = WorkflowVisuals.normalizeThemeColorHex(wf.cardThemeColor)
            selectedReentryBehavior = wf.reentryBehavior
            selectedLogLevel = wf.logLevel
            switchSilentExecution.isChecked = wf.silentExecution
            iconPickerAdapter.setSelectedIcon(selectedIconRes)
            themeColorAdapter.setSelectedColor(selectedThemeColor)
            updateVisualPreview()

            // 填充最大执行时长配置
            wf.maxExecutionTime?.let { maxTime ->
                switchMaxExecutionTime.isChecked = true
                layoutMaxExecutionTimeSlider.visibility = View.VISIBLE
                // ⚠️ 布局写死 `valueTo="120"`，而 AI（save_workflow）/ 远程 API / JSON 导入
                // 都不受这个上限约束（AI 侧允许到 3600）。直接把 300 赋给 value 会命中
                // BaseSlider 的取值范围校验并抛 IllegalStateException —— 且那个校验在
                // onSizeChanged/onDraw 里才触发，即**绑定之后才崩**。
                // 故超范围时按实际值抬高上界，未超范围时原样返回基准值（行为与改动前一致）。
                sliderMaxExecutionTime.valueTo = maxExecutionTimeSliderUpperBound(
                    valueSeconds = maxTime,
                    baseUpperBound = sliderMaxExecutionTime.valueTo,
                    stepSize = sliderMaxExecutionTime.stepSize,
                )
                sliderMaxExecutionTime.value = maxTime.toFloat()
                updateMaxExecutionTimeValue(maxTime)
            } ?: run {
                switchMaxExecutionTime.isChecked = false
                layoutMaxExecutionTimeSlider.visibility = View.GONE
                sliderMaxExecutionTime.value = 60f // 默认值
                updateMaxExecutionTimeValue(60)
            }
        } ?: run {
            textWorkflowName.text = getString(R.string.workflow_not_exists)
            textWorkflowId.text = getString(R.string.text_placeholder_dash)
            textWorkflowModified.text = getString(R.string.text_placeholder_dash)

            // 初始化默认配置
            switchMaxExecutionTime.isChecked = false
            layoutMaxExecutionTimeSlider.visibility = View.GONE
            sliderMaxExecutionTime.value = 60f
            updateMaxExecutionTimeValue(60)
            selectedIconRes = WorkflowVisuals.defaultIconResName()
            selectedThemeColor = WorkflowVisuals.defaultThemeColorHex()
            selectedReentryBehavior = WorkflowReentryBehavior.BLOCK_NEW
            selectedLogLevel = WorkflowLogLevel.VERBOSE
            switchSilentExecution.isChecked = false
            iconPickerAdapter.setSelectedIcon(selectedIconRes)
            themeColorAdapter.setSelectedColor(selectedThemeColor)
            updateVisualPreview()
        }

        // 保存元数据
        btnSaveMetadata.setOnClickListener {
            commitMetadataToEditorState(showToast = true)
            metadataCommittedByAction = true
            dismiss()
        }

        layoutAiGenerate.setOnClickListener {
            onAiGenerateClicked?.invoke()
        }

        layoutUiInspector.setOnClickListener {
            onUiInspectorClicked?.invoke()
        }

        layoutLogcatDebugger.setOnClickListener {
            onLogcatDebuggerClicked?.invoke()
        }

        // 折叠/展开更多元数据
        layoutMoreMetadataHeader.setOnClickListener {
            toggleMoreMetadataExpansion()
        }

        // 最大执行时长开关监听
        switchMaxExecutionTime.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                layoutMaxExecutionTimeSlider.visibility = View.VISIBLE
            } else {
                layoutMaxExecutionTimeSlider.visibility = View.GONE
            }
        }

        // 最大执行时长 Slider 监听
        sliderMaxExecutionTime.addOnChangeListener { _, value, _ ->
            updateMaxExecutionTimeValue(value.toInt())
        }
    }

    private fun updateMaxExecutionTimeValue(seconds: Int) {
        textMaxExecutionTimeValue.text = getString(R.string.workflow_max_execution_time_value, seconds)
    }

    /**
     * 填充函数签名状态行（只读，决策 11.5）。
     * 仅当工作流声明了函数签名时显示；否则隐藏整个卡片。
     */
    private fun bindFunctionSignature(wf: Workflow) {
        val signature = wf.functionSignature
        if (signature == null) {
            cardFunctionSignature.visibility = View.GONE
            return
        }
        cardFunctionSignature.visibility = View.VISIBLE

        // 参数摘要：url(文本必填), count(数字可选)
        val paramsSummary = signature.params.joinToString(", ") { param ->
            val typeLabel = VTypeRegistry.getType(param.type).getLocalizedName(requireContext())
            val requiredFlag = getString(
                if (param.isRequired) R.string.editor_more_options_function_param_required
                else R.string.editor_more_options_function_param_optional
            )
            getString(R.string.editor_more_options_function_param_entry, param.name, typeLabel, requiredFlag)
        }
        textFunctionParamsSummary.text = getString(
            R.string.editor_more_options_function_params_prefix
        ) + ": " + paramsSummary

        // 返回值摘要：只对「返回字典」场景展示键
        val returnDef = signature.returnDef
        textFunctionReturnSummary.text = if (returnDef != null && returnDef.keys.isNotEmpty()) {
            getString(R.string.editor_more_options_function_return_prefix) + ": {" +
                returnDef.keys.joinToString(", ") { it.name } + "}"
        } else {
            getString(R.string.editor_more_options_function_return_prefix) + ": -"
        }
    }

    private fun setupReentryBehaviorSelector() {
        reentryBehaviorComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        reentryBehaviorComposeView.setContent {
            VFlowTheme {
                ReentryBehaviorButtonGroup(
                    selectedBehavior = selectedReentryBehavior,
                    labelFor = ::getReentryBehaviorLabel,
                    onBehaviorSelected = { selectedReentryBehavior = it }
                )
            }
        }
    }

    private fun getReentryBehaviorLabel(behavior: WorkflowReentryBehavior): String {
        val stringRes = when (behavior) {
            WorkflowReentryBehavior.BLOCK_NEW -> R.string.workflow_reentry_behavior_block_new
            WorkflowReentryBehavior.STOP_CURRENT_AND_RUN_NEW -> R.string.workflow_reentry_behavior_stop_current_and_run_new
            WorkflowReentryBehavior.ALLOW_PARALLEL -> R.string.workflow_reentry_behavior_allow_parallel
        }
        return getString(stringRes)
    }

    /**
     * 日志等级选择器。
     *
     * ⚠️ 用 ToggleButton 组而不是下拉：四个档位是**有序**的（详细 → 精简 → 仅警告 → 仅错误），
     * 排成一排能直接看出「越往右越安静」；下拉只看得到当前值。
     */
    private fun setupLogLevelSelector() {
        logLevelComposeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed
        )
        logLevelComposeView.setContent {
            VFlowTheme {
                LogLevelButtonGroup(
                    selectedLevel = selectedLogLevel,
                    labelFor = ::getLogLevelLabel,
                    onLevelSelected = { selectedLogLevel = it }
                )
            }
        }
    }

    private fun getLogLevelLabel(level: WorkflowLogLevel): String {
        val stringRes = when (level) {
            WorkflowLogLevel.VERBOSE -> R.string.workflow_log_level_verbose
            WorkflowLogLevel.NORMAL -> R.string.workflow_log_level_normal
            WorkflowLogLevel.WARNING -> R.string.workflow_log_level_warning
            WorkflowLogLevel.ERROR -> R.string.workflow_log_level_error
        }
        return getString(stringRes)
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun LogLevelButtonGroup(
        selectedLevel: WorkflowLogLevel,
        labelFor: (WorkflowLogLevel) -> String,
        onLevelSelected: (WorkflowLogLevel) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            WorkflowLogLevel.entries.forEachIndexed { index, level ->
                ToggleButton(
                    checked = selectedLevel == level,
                    onCheckedChange = { checked ->
                        if (checked && selectedLevel != level) {
                            onLevelSelected(level)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        WorkflowLogLevel.entries.lastIndex ->
                            ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                ) {
                    // ⚠️ 文案刻意**只写档位名、不带括号说明**（用户 2026-10-04 定）：
                    //    四个按钮挤一排，括号说明会把每个都撑成两行且互相截断，
                    //    反而看不出区别。档位名的含义由标题下方的 `_desc` 统一交代。
                    //    `maxLines = 2` 只是兜底（「仅警告与错误」在窄屏/大字号下仍需换行）。
                    Text(
                        text = labelFor(level),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 2
                    )
                }
            }
        }
    }

    private fun setupVisualPickers(view: View) {
        val iconRecyclerView = view.findViewById<RecyclerView>(R.id.recycler_workflow_icons)
        iconPickerAdapter = WorkflowIconPickerAdapter { iconRes ->
            selectedIconRes = iconRes
            updateVisualPreview()
        }
        iconRecyclerView.layoutManager = object : GridLayoutManager(requireContext(), 5) {
            override fun canScrollVertically(): Boolean = false
        }
        iconRecyclerView.adapter = iconPickerAdapter
        iconRecyclerView.overScrollMode = View.OVER_SCROLL_NEVER

        val colorRecyclerView = view.findViewById<RecyclerView>(R.id.recycler_workflow_colors)
        themeColorAdapter = WorkflowThemeColorAdapter { colorHex ->
            selectedThemeColor = colorHex
            updateVisualPreview()
        }
        colorRecyclerView.layoutManager = object : GridLayoutManager(requireContext(), 5) {
            override fun canScrollVertically(): Boolean = false
        }
        colorRecyclerView.adapter = themeColorAdapter
        colorRecyclerView.overScrollMode = View.OVER_SCROLL_NEVER
    }

    private fun updateVisualPreview() {
        val previewName = workflow?.name?.takeIf { it.isNotBlank() }
            ?: getString(R.string.workflow_name_untitled)
        val cardColors = WorkflowVisuals.resolveCardColors(requireContext(), selectedThemeColor)
        cardVisualPreview.setCardBackgroundColor(cardColors.cardBackground)
        cardVisualPreviewIcon.setCardBackgroundColor(cardColors.iconBackground)
        imageVisualPreviewIcon.setImageResource(
            WorkflowVisuals.resolveIconDrawableRes(selectedIconRes)
        )
        imageVisualPreviewIcon.imageTintList = ColorStateList.valueOf(cardColors.iconTint)
        textVisualPreviewName.text = previewName
        textVisualPreviewColor.text = selectedThemeColor
        textSelectedThemeColor.text = getString(R.string.workflow_theme_color_value, selectedThemeColor)
    }

    private fun toggleMoreMetadataExpansion() {
        isMoreMetadataExpanded = !isMoreMetadataExpanded

        if (isMoreMetadataExpanded) {
            layoutMoreMetadataContent.visibility = View.VISIBLE
            imageExpandIcon.rotation = 180f
        } else {
            layoutMoreMetadataContent.visibility = View.GONE
            imageExpandIcon.rotation = 0f
        }
    }

    private fun commitMetadataToEditorState(showToast: Boolean) {
        val wf = workflow ?: return
        if (metadataCommittedByAction && !showToast) return

        val version = editVersion.text?.toString()?.trim() ?: getString(R.string.default_workflow_version)
        val vFlowLevel = editVFlowLevel.text?.toString()?.toIntOrNull() ?: 1
        val description = editDescription.text?.toString()?.trim() ?: ""
        val author = editAuthor.text?.toString()?.trim() ?: ""
        val homepage = editHomepage.text?.toString()?.trim() ?: ""
        val tagsText = editTags.text?.toString()?.trim() ?: ""
        val tags = if (tagsText.isNotEmpty()) {
            tagsText.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        } else {
            emptyList()
        }

        val maxExecutionTime = if (switchMaxExecutionTime.isChecked) {
            sliderMaxExecutionTime.value.toInt()
        } else {
            null
        }

        val updatedWorkflow = wf.copy(
            version = version,
            vFlowLevel = vFlowLevel,
            description = description,
            author = author,
            homepage = homepage,
            tags = tags,
            maxExecutionTime = maxExecutionTime,
            reentryBehavior = selectedReentryBehavior,
            logLevel = selectedLogLevel,
            silentExecution = switchSilentExecution.isChecked,
            cardIconRes = selectedIconRes,
            cardThemeColor = selectedThemeColor
        )

        workflow = updatedWorkflow
        onMetadataSaved?.invoke(updatedWorkflow)
        if (showToast) {
            Toast.makeText(requireContext(), R.string.metadata_updated_pending_save, Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyToClipboard(text: String) {
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText(getString(R.string.clipboard_label_workflow_id), text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_SHORT).show()
    }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    @Composable
    private fun ReentryBehaviorButtonGroup(
        selectedBehavior: WorkflowReentryBehavior,
        labelFor: (WorkflowReentryBehavior) -> String,
        onBehaviorSelected: (WorkflowReentryBehavior) -> Unit
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(ButtonGroupDefaults.ConnectedSpaceBetween)
        ) {
            WorkflowReentryBehavior.values().forEachIndexed { index, behavior ->
                ToggleButton(
                    checked = selectedBehavior == behavior,
                    onCheckedChange = { checked ->
                        if (checked && selectedBehavior != behavior) {
                            onBehaviorSelected(behavior)
                        }
                    },
                    modifier = Modifier
                        .weight(1f)
                        .semantics { role = Role.RadioButton },
                    shapes = when (index) {
                        0 -> ButtonGroupDefaults.connectedLeadingButtonShapes()
                        WorkflowReentryBehavior.values().lastIndex -> ButtonGroupDefaults.connectedTrailingButtonShapes()
                        else -> ButtonGroupDefaults.connectedMiddleButtonShapes()
                    }
                ) {
                    Text(labelFor(behavior))
                }
            }
        }
    }
}

/**
 * 由「实际要显示的秒数」推出滑动条的 `valueTo`。
 *
 * ## 为什么需要它
 *
 * `sheet_editor_more_options.xml` 把滑块写死成 `valueFrom=0 / valueTo=120 / stepSize=5`，
 * 但 `Workflow.maxExecutionTime` 的值域**不受它约束**：
 *
 * | 写入方 | 允许范围 | 依据 |
 * |---|---|---|
 * | AI（`save_workflow` / `update_workflow`） | 1–3600 | `ChatAgentModuleExecutor.MAX_SAVED_WORKFLOW_MAX_SECONDS` |
 * | 远程 API | 无上限校验 | `api/handler/WorkflowHandler.kt` |
 * | JSON 导入 | 无上限校验 | `WorkflowJsonImportParser` |
 *
 * 于是打开「更多选项」时把 300 赋给 `value` 会命中 Material `BaseSlider.validateValues`
 * 的检查并抛 `IllegalStateException`。⚠️ **崩溃点是延迟的**：`setValue` 只置
 * `dirtyConfig = true`，真正的校验在 `onSizeChanged` / `onDraw` 里——
 * 即「面板绑定完成、首次绘制时才崩」，看栈上看不到本文件。
 *
 * ## 规则
 *
 * - **[valueSeconds] 未超过 [baseUpperBound]**：原样返回 [baseUpperBound]。
 *   这是**必须**的——无谓地抬高上界会让普通工作流的滑块刻度变粗（120 秒范围被压成
 *   轨道上的一小段），等于改掉了既有产品的行为。
 * - **超过**：抬到不小于 [valueSeconds] 的最近一个 [stepSize] 整数倍。
 *   `valueTo` 必须让 `120` 与 `valueSeconds` 都落在刻度上，否则
 *   `validateStepSize`（`valueLandsOnTick`）会以同样的方式抛异常。
 *
 * ⚠️ 抬高上界**只影响这一次面板会话**：它是视图属性，不改 `Workflow`，
 * 也不落盘。用户不点「保存元数据」则磁盘上的值原样不变。
 *
 * @param baseUpperBound 当前滑动条的上界（取自视图而非硬编码，避免与布局脱节）
 * @param stepSize 当前滑动条的步长（同上）
 */
internal fun maxExecutionTimeSliderUpperBound(
    valueSeconds: Int,
    baseUpperBound: Float,
    stepSize: Float,
): Float {
    if (baseUpperBound <= 0f) return baseUpperBound
    if (valueSeconds <= baseUpperBound) return baseUpperBound
    if (stepSize <= 0f) return valueSeconds.toFloat()

    // ⚠️ 必须**向上**取整：截断会让上界落在实际值之下（302 → 300），
    // 于是刚修好的越界异常原样复发，只是换了个值域。
    val steps = ceil(valueSeconds.toDouble() / stepSize.toDouble()).toInt()
    return stepSize * steps
}
