// 文件: main/java/com/chaomixian/vflow/ui/workflow_editor/ActionEditorSheet.kt
package com.chaomixian.vflow.ui.workflow_editor

import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.*
import com.chaomixian.vflow.core.workflow.JumpTargetSupport
import com.chaomixian.vflow.core.workflow.model.ActionStepExecutionSettings
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.common.ModuleDetailDialog
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputLayout

/**
 * 模块参数编辑器底部表单。
 * UI 由模块定义驱动，支持通用输入类型和模块自定义UI。
 * 支持配置异常处理策略。
 */
class ActionEditorSheet : BottomSheetDialogFragment() {
    private lateinit var module: ActionModule
    private var existingStep: ActionStep? = null
    private var focusedInputId: String? = null
    private var allSteps: ArrayList<ActionStep>? = null
    var onSave: ((ActionStep) -> Unit)? = null
    var onMagicVariableRequested: ((inputId: String, currentParameters: Map<String, Any?>) -> Unit)? = null
    var onInlineScriptVariableRequested: ((
        inputId: String,
        currentParameters: Map<String, Any?>,
        targetSheet: InlineScriptEditorSheet
    ) -> Unit)? = null
    var onVariablePillEditRequested: ((
        inputId: String,
        currentParameters: Map<String, Any?>,
        currentReference: String,
        onUpdated: (String?) -> Unit
    ) -> Unit)? = null
    var onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)? = null
    private val inputViews = mutableMapOf<String, View>()
    private var customEditorHolder: CustomEditorViewHolder? = null
    private val currentParameters = ActionEditorSessionState()
    private var onPickerRequested: ((inputDef: InputDefinition) -> Unit)? = null

    // 引用容器视图
    private var customUiCard: MaterialCardView? = null
    private var customUiContainer: LinearLayout? = null
    private var genericInputsCard: MaterialCardView? = null
    private var genericInputsContainer: LinearLayout? = null
    private var editorActionsCard: MaterialCardView? = null
    private var editorActionsContainer: LinearLayout? = null
    private var actionEditorScroll: NestedScrollView? = null
    private var keyboardToolbar: View? = null
    private var focusedToolbarInputId: String? = null
    private var isKeyboardVisible: Boolean = false
    private var baseScrollPaddingBottom: Int = 0

    // 异常处理 UI 组件
    private var errorSettingsContent: LinearLayout? = null
    private var errorPolicyGroup: RadioGroup? = null
    private var retryCountSlider: Slider? = null
    private var retryIntervalSlider: Slider? = null
    private var retryExhaustedSpinner: TextInputLayout? = null
    private var skipJumpContainer: LinearLayout? = null
    private var skipJumpSpinner: TextInputLayout? = null

    companion object {
        // 单次编辑会话的展开状态缓存
        private val expandedSections = mutableMapOf<String, Boolean>()

        /** 创建 ActionEditorSheet 实例。 */
        fun newInstance(
            module: ActionModule,
            existingStep: ActionStep?,
            focusedInputId: String?,
            allSteps: List<ActionStep>? = null
        ): ActionEditorSheet {
            return ActionEditorSheet().apply {
                arguments = Bundle().apply {
                    putString("moduleId", module.id)
                    putParcelable("existingStep", existingStep)
                    putString("focusedInputId", focusedInputId)
                    allSteps?.let { putParcelableArrayList("allSteps", ArrayList(it)) }
                }
            }
        }
    }

    /** 初始化核心数据。 */
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val moduleId = arguments?.getString("moduleId")
        module = moduleId?.let { ModuleRegistry.getModule(it) } ?: return dismiss()
        existingStep = arguments?.getParcelableCompat("existingStep")
        focusedInputId = arguments?.getString("focusedInputId")
        allSteps = arguments?.getParcelableArrayListCompat("allSteps")

        // 初始化参数，首先使用模块定义的默认值
        currentParameters.initializeDefaults(module.getInputs())
        if (existingStep == null) {
            currentParameters.applyDefaultExecutionSettings(requireContext())
        }
        // 然后用步骤已有的参数覆盖默认值
        existingStep?.parameters?.let { currentParameters.putAll(it) }
    }

    override fun onDestroyView() {
        customEditorHolder?.onDestroy()
        customEditorHolder = null
        super.onDestroyView()
    }

    fun setOnPickerRequestedListener(listener: (InputDefinition) -> Unit) {
        onPickerRequested = listener
    }

    private fun dispatchPickerRequest(inputDefinition: InputDefinition) {
        onPickerRequested?.invoke(inputDefinition)
    }

    /** 创建视图并构建UI。 */
    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        val view = inflater.inflate(R.layout.sheet_action_editor, container, false)
        val titleTextView = view.findViewById<TextView>(R.id.text_view_bottom_sheet_title)
        val infoButton = view.findViewById<ImageButton>(R.id.button_module_info)
        val saveButton = view.findViewById<Button>(R.id.button_save)

        // 绑定视图容器
        customUiCard = view.findViewById(R.id.card_custom_ui)
        customUiContainer = view.findViewById(R.id.container_custom_ui)
        genericInputsCard = view.findViewById(R.id.card_generic_inputs)
        genericInputsContainer = view.findViewById(R.id.container_generic_inputs)
        editorActionsCard = view.findViewById(R.id.card_editor_actions)
        editorActionsContainer = view.findViewById(R.id.container_editor_actions)
        actionEditorScroll = view.findViewById(R.id.action_editor_scroll)
        keyboardToolbar = view.findViewById(R.id.keyboard_toolbar)
        baseScrollPaddingBottom = actionEditorScroll?.paddingBottom ?: 0
        setupKeyboardToolbar(view)

        // 绑定错误处理容器
        errorSettingsContent = view.findViewById(R.id.container_execution_settings_content)
        val errorHeader = view.findViewById<View>(R.id.header_execution_settings)
        val errorArrow = view.findViewById<View>(R.id.arrow_execution_settings)

        // 错误处理区域的折叠/展开逻辑
        errorHeader.setOnClickListener {
            val isVisible = errorSettingsContent?.isVisible == true
            errorSettingsContent?.isVisible = !isVisible
            errorArrow.animate().rotation(if (!isVisible) 180f else 0f).setDuration(200).start()
        }

        // 设置标题
        val focusedInputDef = module.getInputs().find { it.id == focusedInputId }
        titleTextView.text = if (focusedInputId != null && focusedInputDef != null) {
            getString(R.string.title_edit_target, focusedInputDef.name)
        } else {
            val localizedName = module.metadata.getLocalizedName(requireContext())
            getString(R.string.title_edit_target, localizedName)
        }
        infoButton.setOnClickListener {
            ModuleDetailDialog.show(requireContext(), module)
        }

        buildUi()
        buildErrorHandlingUi() // 构建错误处理 UI

        saveButton.setOnClickListener {
            readParametersFromUi()
            syncExecutionSettingsFromUi()

            val finalParams = existingStep?.parameters?.toMutableMap() ?: mutableMapOf()
            finalParams.putAll(currentParameters.snapshot())
            val stepForValidation = ActionStep(moduleId = module.id, parameters = finalParams, id = existingStep?.id ?: "")
            // 调用 validate 方法进行验证
            val validationResult = module.validate(stepForValidation, allSteps ?: emptyList())
            if (validationResult.isValid) {
                // fork（P0-5 第三层防护）：跳转环检测 —— 只**警告**，不阻止保存。
                warnOnJumpCycle(finalParams)
                onSave?.invoke(currentParameters.toActionStep(module.id))
                dismiss()
            } else {
                Toast.makeText(context, validationResult.errorMessage, Toast.LENGTH_LONG).show()
            }
        }
        return view
    }

    private fun setupKeyboardToolbar(rootView: View) {
        val toolbar = keyboardToolbar ?: return
        val variableButton = toolbar.findViewById<MaterialButton>(R.id.button_keyboard_toolbar_variable)
        variableButton.setOnClickListener {
            val inputId = focusedToolbarInputId ?: focusedInputId ?: return@setOnClickListener
            requestMagicVariableSelection(inputId)
        }
        val scriptButton = toolbar.findViewById<MaterialButton>(R.id.button_keyboard_toolbar_script)
        scriptButton.setOnClickListener {
            val inputId = focusedToolbarInputId ?: focusedInputId ?: return@setOnClickListener
            showInlineScriptEditor(initialScript = "", targetInputId = inputId) { script ->
                insertInlineScriptIntoInput(inputId, script)
            }
        }

        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            isKeyboardVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            updateKeyboardToolbarVisibility()
            insets
        }
        ViewCompat.requestApplyInsets(rootView)
    }

    private fun updateKeyboardToolbarVisibility() {
        val toolbar = keyboardToolbar ?: return
        val inputDef = focusedToolbarInputDefinition()
        val scroll = actionEditorScroll ?: return
        val canInsertVariable = inputDef?.let { it.acceptsMagicVariable || it.acceptsNamedVariable } == true
        val canInsertScript = inputDef?.allowsInlineScript == true
        val shouldShow = isKeyboardVisible &&
            focusedToolbarInputId != null &&
            (canInsertVariable || canInsertScript)

        toolbar.translationY = 0f
        toolbar.isVisible = shouldShow
        toolbar.findViewById<MaterialButton>(R.id.button_keyboard_toolbar_variable)?.isVisible =
            canInsertVariable
        toolbar.findViewById<MaterialButton>(R.id.button_keyboard_toolbar_script)?.isVisible =
            canInsertScript
        val toolbarSpace = if (shouldShow) toolbar.height.takeIf { it > 0 } ?: (48 * resources.displayMetrics.density).toInt() else 0
        scroll.setPadding(
            scroll.paddingLeft,
            scroll.paddingTop,
            scroll.paddingRight,
            baseScrollPaddingBottom + toolbarSpace
        )
    }

    private fun focusedToolbarInputDefinition(): InputDefinition? {
        val inputId = focusedToolbarInputId ?: focusedInputId ?: return null
        return findDynamicInputDefinition(inputId)
    }

    private fun showInlineScriptEditor(
        initialScript: String,
        targetInputId: String?,
        onDone: (String) -> Unit
    ) {
        val sheet = InlineScriptEditorSheet.newInstance(initialScript)
        sheet.onDone = { script ->
            if (script.isNotBlank()) {
                onDone(script)
            }
        }
        sheet.onVariableRequested = onVariableRequested@{
            val inputId = targetInputId ?: focusedToolbarInputId ?: focusedInputId ?: return@onVariableRequested
            readParametersFromUi()
            onInlineScriptVariableRequested?.invoke(inputId, currentParameters.asMap(), sheet)
        }
        sheet.show(parentFragmentManager, "InlineScriptEditorSheet")
    }

    private fun insertInlineScriptIntoInput(inputId: String, script: String) {
        val richTextView = findRichTextView(inputId) ?: return
        richTextView.insertInlineScriptPill(script)
    }

    /**
     * 构建异常处理策略的 UI。
     * 包含一个 RadioGroup 选择策略（4 种），以及重试相关的 Slider。
     *
     * ⚠️ **每新增一种策略都必须同时加一个 radio**：恢复选中态用的 `when` 有 `else -> rbStop`
     * 兜底，漏加的话存量步骤里已存的那个值会被**静默改成 STOP**（用户看不出任何异常）。
     */
    private fun buildErrorHandlingUi() {
        val context = requireContext()
        errorSettingsContent?.removeAllViews()

        val radioGroup = RadioGroup(context).apply { orientation = RadioGroup.VERTICAL }
        val rbStop = RadioButton(context).apply {
            text = getString(R.string.editor_error_policy_stop)
            tag = ActionStepExecutionSettings.POLICY_STOP
        }
        val rbStopSilent = RadioButton(context).apply {
            text = getString(R.string.editor_error_policy_stop_silent)
            tag = ActionStepExecutionSettings.POLICY_STOP_SILENT
        }
        val rbSkip = RadioButton(context).apply {
            text = getString(R.string.editor_error_policy_skip)
            tag = ActionStepExecutionSettings.POLICY_SKIP
        }
        val rbRetry = RadioButton(context).apply {
            text = getString(R.string.editor_error_policy_retry)
            tag = ActionStepExecutionSettings.POLICY_RETRY
        }

        radioGroup.addView(rbStop)
        radioGroup.addView(rbStopSilent)
        radioGroup.addView(rbSkip)
        radioGroup.addView(rbRetry)

        val retryContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 16, 0, 0)
            visibility = View.GONE
        }

        // 恢复状态
        val executionSettings = currentParameters.getExecutionSettings()
        val currentPolicy = executionSettings.policy
        val currentRetryCount = executionSettings.retryCount.toFloat()
        val currentRetryInterval = executionSettings.retryIntervalMillis.toFloat()

        // --- 重试次数 ---
        val sliderRetryCount = StandardControlFactory.createSliderWithLabel(
            context = context,
            label = getString(R.string.editor_retry_count),
            valueFrom = 1f,
            valueTo = 10f,
            stepSize = 1f,
            currentValue = currentRetryCount,
            valueFormatter = { getString(R.string.editor_retry_times, it.toInt()) }
        )
        retryContainer.addView(sliderRetryCount)

        // --- 重试间隔 ---
        val sliderRetryInterval = StandardControlFactory.createSliderWithLabel(
            context = context,
            label = getString(R.string.editor_retry_interval),
            valueFrom = 100f,
            valueTo = 5000f,
            stepSize = 100f,
            currentValue = currentRetryInterval,
            valueFormatter = { "${it.toLong()} ms" }
        ).apply {
            // 增加间距区分上下两个滑块
            (layoutParams as LinearLayout.LayoutParams).topMargin = (24 * resources.displayMetrics.density).toInt()
        }
        retryContainer.addView(sliderRetryInterval)

        // --- 用尽后（策略链，P0-1）---
        // ⚠️ **只在选 RETRY 时可见**（它属于 retryContainer）—— 其余策略下这个值没有语义。
        // ⚠️⚠️ `optionsStringRes` 的**顺序必须与 `ON_RETRY_EXHAUSTED_CHOICES` 一一对应**：
        //    `bindDropdown` 是按 index 取文案的，错位会让下拉显示与实际存入的值不符（静默写错参数）。
        //    该顺序由 `RetryExhaustedPolicyWiringTest` 的源码扫描守卫锁死。
        val exhaustLabel = TextView(context).apply {
            text = getString(R.string.editor_retry_exhausted_label)
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
        }
        val spinnerExhausted = StandardControlFactory.createSpinner(
            context = context,
            options = ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES,
            selectedValue = executionSettings.onRetryExhausted,
            // 用尽后动作变了 ⇒ 有效策略可能从 STOP 变成 SKIP（或反过来）⇒ 跳转目标的可见性要跟着变
            onItemSelectedCallback = { refreshSkipJumpVisibility() },
            optionsStringRes = listOf(
                R.string.editor_retry_exhausted_stop,
                R.string.editor_retry_exhausted_stop_silent,
                R.string.editor_retry_exhausted_skip
            )
        )
        retryContainer.addView(exhaustLabel)
        retryContainer.addView(spinnerExhausted)

        // --- 失败时跳转到（P0-5）---
        // ⚠️ 存的是 **step id**（选项值），显示的是 `#序号 模块名` —— 序号只给人看。
        //    `StandardControlFactory.createSpinner` 支持 `displayOptions` 与 `options` 分离，
        //    正好表达这层「给人看 vs 给机器用」的区分。
        val skipJumpLabel = TextView(context).apply {
            text = getString(R.string.editor_skip_jump_label)
        }
        val (jumpOptionIds, jumpOptionLabels) = buildSkipJumpChoices()
        val spinnerJump = StandardControlFactory.createSpinner(
            context = context,
            options = jumpOptionIds,
            selectedValue = executionSettings.skipJumpTarget ?: "",
            displayOptions = jumpOptionLabels
        )
        val jumpContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (24 * resources.displayMetrics.density).toInt(), 0, 0)
            visibility = View.GONE
            addView(skipJumpLabel)
            addView(spinnerJump)
        }

        when (currentPolicy) {
            ActionStepExecutionSettings.POLICY_SKIP -> rbSkip.isChecked = true
            ActionStepExecutionSettings.POLICY_RETRY -> rbRetry.isChecked = true
            ActionStepExecutionSettings.POLICY_STOP_SILENT -> rbStopSilent.isChecked = true
            else -> rbStop.isChecked = true
        }
        retryContainer.isVisible = (currentPolicy == ActionStepExecutionSettings.POLICY_RETRY)

        // 获取滑块引用
        val sliderRetryCountView = sliderRetryCount.getChildAt(1) as Slider
        val sliderRetryIntervalView = sliderRetryInterval.getChildAt(1) as Slider

        // 保存引用（必须在 refreshSkipJumpVisibility 之前 —— 它读这两个字段）
        this.errorPolicyGroup = radioGroup
        this.retryExhaustedSpinner = spinnerExhausted
        this.skipJumpContainer = jumpContainer
        this.skipJumpSpinner = spinnerJump
        refreshSkipJumpVisibility()

        // 监听器
        radioGroup.setOnCheckedChangeListener { _, checkedId ->
            retryContainer.isVisible = (checkedId == rbRetry.id)
            // 「重试用尽后」也会影响有效策略 ⇒ 跳转目标的可见性要跟着变
            refreshSkipJumpVisibility()
        }

        this.retryCountSlider = sliderRetryCountView
        this.retryIntervalSlider = sliderRetryIntervalView

        errorSettingsContent?.addView(radioGroup)
        errorSettingsContent?.addView(retryContainer)
        errorSettingsContent?.addView(jumpContainer)
    }

    private fun syncExecutionSettingsFromUi() {
        currentParameters.setExecutionSettings(readExecutionSettingsFromUi())
    }

    /**
     * 「失败时跳转到」容器的可见性。
     *
     * ⚠️ 判据是**有效策略**（`effectivePolicy`），不是裸的 `policy` ——
     * 「RETRY + 用尽后 = SKIP」也会走跳过分支，跳转目标对它同样有意义。
     */
    private fun refreshSkipJumpVisibility() {
        val checkedId = errorPolicyGroup?.checkedRadioButtonId ?: -1
        val selectedPolicy = errorPolicyGroup?.findViewById<View>(checkedId)?.tag as? String
            ?: ActionStepExecutionSettings.POLICY_STOP
        val exhausted = retryExhaustedSpinner
            ?.let { StandardControlFactory.getDropdownValue(it) }
            ?: ActionStepExecutionSettings.POLICY_STOP
        val effective = ActionStepExecutionSettings.effectivePolicy(selectedPolicy, exhausted)
        skipJumpContainer?.isVisible = (effective == ActionStepExecutionSettings.POLICY_SKIP)
    }

    /**
     * 跳转目标的候选列表：`（不跳转）` + 所有**允许**的步骤。
     *
     * - 过滤用 `JumpTargetSupport.isAllowedTarget`（与运行期**同一把尺**：
     *   目标缩进 ≤ 当前缩进，且不是块中间/结束模块）⇒ UI 少给错选项，但运行期才是防线。
     * - 显示 `#序号 模块名`，**存的是 step id** —— 序号只是给人看的。
     * - ⚠️ 存量值不在候选里（目标被删 / 缩进变了 / 模块未注册）时**必须原样保留**，
     *   否则用户打开 sheet 一保存就把已存的跳转目标**静默清掉**
     *   （`bindDropdown` 找不到 selectedValue 会回落第 0 项 =「不跳转」）。
     */
    private fun buildSkipJumpChoices(): Pair<List<String>, List<String>> {
        val steps = allSteps.orEmpty()
        val currentIndentation = existingStep?.indentationLevel ?: 0

        val ids = mutableListOf("")
        val labels = mutableListOf(getString(R.string.editor_skip_jump_none))

        steps.forEachIndexed { index, candidate ->
            if (!JumpTargetSupport.isAllowedTarget(steps, index, currentIndentation)) return@forEachIndexed
            val moduleName = ModuleRegistry.getModule(candidate.moduleId)
                ?.metadata?.getLocalizedName(requireContext())
                ?: candidate.moduleId
            ids += candidate.id
            labels += getString(R.string.editor_skip_jump_option, index + 1, moduleName)
        }

        val stored = currentParameters.getExecutionSettings().skipJumpTarget
        if (stored != null && stored !in ids) {
            ids += stored
            labels += getString(R.string.editor_skip_jump_missing, stored)
        }
        return ids to labels
    }

    /**
     * **静态环检测**（P0-5 第三层防护）：只**警告**，不阻止保存。
     *
     * 为什么不阻止：环也可能是有意的有限循环；运行期还有「单步 10 次 / 单次执行 200 次」
     * 两级上限兜底。但用户大概率是写错了，所以要在保存时把话说到。
     */
    private fun warnOnJumpCycle(finalParams: Map<String, Any?>) {
        val original = existingStep ?: return
        val steps = allSteps.orEmpty()
        val index = steps.indexOfFirst { it.id == original.id }
        if (index < 0) return // 新步骤还没进列表 ⇒ 建不出图

        val candidate = steps.toMutableList().also { list ->
            list[index] = list[index].copy(parameters = finalParams)
        }
        val cycles = JumpTargetSupport.detectCycles(candidate)
        if (cycles.isEmpty()) return

        fun labelOf(stepId: String): String {
            val position = candidate.indexOfFirst { it.id == stepId }
            return if (position >= 0) "#${position + 1}" else stepId
        }

        // 首尾都写出来（`#2 → #5 → #2`），一眼能看出环在哪里闭合。
        val rendered = cycles.joinToString("、") { cycle ->
            (cycle + cycle.first()).joinToString(" → ") { labelOf(it) }
        }
        DebugLogger.w("ActionEditorSheet", "检测到跳转环：$rendered")
        Toast.makeText(context, getString(R.string.editor_skip_jump_cycle_warning, rendered), Toast.LENGTH_LONG).show()
    }

    private fun readExecutionSettingsFromUi(): ActionStepExecutionSettings {
        val selectedId = errorPolicyGroup?.checkedRadioButtonId
        val view = errorPolicyGroup?.findViewById<View>(selectedId ?: -1)
        val policy = view?.tag as? String ?: ActionStepExecutionSettings.POLICY_STOP

        return ActionStepExecutionSettings(
            policy = policy,
            // ⚠️ 读回的是 tag（序列化值），不是显示文案 —— `getDropdownValue` 已按此约定实现。
            //    为 null（UI 未构建 / 未选中）时回落 STOP，与 fromParameters 的缺省一致。
            onRetryExhausted = retryExhaustedSpinner?.let { StandardControlFactory.getDropdownValue(it) }
                ?: ActionStepExecutionSettings.POLICY_STOP,
            // 同理：读回的是 step id（tag）。空白（选了「不跳转」）⇒ null ⇒ 删键。
            skipJumpTarget = skipJumpSpinner
                ?.let { StandardControlFactory.getDropdownValue(it) }
                ?.takeIf { it.isNotBlank() },
            retryCount = retryCountSlider?.value?.toInt() ?: ActionStepExecutionSettings.DEFAULT_RETRY_COUNT,
            retryIntervalMillis = retryIntervalSlider?.value?.toLong()
                ?: ActionStepExecutionSettings.DEFAULT_RETRY_INTERVAL_MS
        )
    }

    private fun requestMagicVariableSelection(inputId: String) {
        readParametersFromUi()
        onMagicVariableRequested?.invoke(inputId, currentParameters.asMap())
    }

    private fun mutateSession(
        readUiFirst: Boolean = false,
        rebuildMode: RebuildMode = RebuildMode.IMMEDIATE,
        mutation: ActionEditorSessionState.() -> Unit
    ) {
        if (readUiFirst) {
            readParametersFromUi()
        }
        mutation(currentParameters)
        when (rebuildMode) {
            RebuildMode.NONE -> Unit
            RebuildMode.IMMEDIATE -> buildUi()
            RebuildMode.POST -> postBuildUi()
        }
    }

    private fun applyParameterUpdate(
        inputId: String,
        updatedValue: Any?,
        readUiFirst: Boolean,
        rebuildMode: RebuildMode
    ) {
        mutateSession(readUiFirst = readUiFirst, rebuildMode = rebuildMode) {
            val updatedStep = toActionStep(module.id, existingStep?.id ?: "").copy(
                parameters = snapshot().apply {
                    this[inputId] = updatedValue
                }
            )
            replaceAll(module.onParameterUpdated(updatedStep, inputId, updatedValue))
        }
    }

    private fun postBuildUi() {
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            try {
                buildUi()
            } catch (_: Exception) {
                // 忽略视图已销毁的情况
            }
        }
    }

    private enum class RebuildMode {
        NONE,
        IMMEDIATE,
        POST
    }

    /**
     * 构建UI的逻辑。
     */
    private fun buildUi() {
        val uiModel = computeUiModel()
        applyUiModelCorrections(uiModel)
        renderUiModel(uiModel)
    }

    private fun computeUiModel(): EditorUiModel {
        return ActionEditorUiModelBuilder.build(
            module = module,
            sessionState = currentParameters,
            allSteps = allSteps
        )
    }

    private fun applyUiModelCorrections(uiModel: EditorUiModel) {
        val correctedParameters = uiModel.effectiveParameters
        if (currentParameters.asMap() != correctedParameters) {
            currentParameters.replaceAll(correctedParameters)
        }
    }

    private fun renderUiModel(uiModel: EditorUiModel) {
        customUiContainer?.removeAllViews()
        genericInputsContainer?.removeAllViews()
        editorActionsContainer?.removeAllViews()
        inputViews.clear()
        customEditorHolder = null

        if (uiModel.showCustomUi) {
            val uiProvider = uiModel.uiProvider ?: return
            customEditorHolder = uiProvider.createEditor(
                context = requireContext(),
                parent = customUiContainer!!,
                currentParameters = uiModel.effectiveParameters,
                onParametersChanged = { readParametersFromUi() },
                onMagicVariableRequested = ::requestMagicVariableSelection,
                allSteps = allSteps,
                onStartActivityForResult = onStartActivityForResult
            )
            customUiContainer?.addView(customEditorHolder!!.view)
            bindKeyboardToolbarFocus(customEditorHolder!!.view)
            bindCustomEditorKeyboardToolbarTargets(customEditorHolder!!)
        }

        customUiCard?.isVisible = uiModel.showCustomUi

        uiModel.genericInputsSection.fields.forEach { fieldModel ->
            val inputView = createViewForInputDefinition(fieldModel)
            genericInputsContainer?.addView(inputView)
            inputViews[fieldModel.inputDefinition.id] = inputView
        }

        if (uiModel.advancedInputsSection.isVisible) {
            if (uiModel.shouldShowAdvancedDivider) {
                val divider = View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (1 * resources.displayMetrics.density).toInt()
                    ).apply {
                        setMargins(0, 32, 0, 16)
                    }
                    setBackgroundColor(requireContext().getColor(android.R.color.darker_gray))
                    alpha = 0.2f
                }
                genericInputsContainer?.addView(divider)
            }

            val advancedSection = createAdvancedSection(uiModel.advancedInputsSection.fields)
            genericInputsContainer?.addView(advancedSection)
        }

        genericInputsCard?.isVisible = uiModel.showsAnyGenericInputs

        if (uiModel.editorActionsSection.isVisible) {
            editorActionsContainer?.addView(createEditorActionsSection(uiModel.editorActionsSection.actions))
        }
        editorActionsCard?.isVisible = uiModel.editorActionsSection.isVisible
    }

    private fun createEditorActionsSection(actions: List<EditorAction>): View {
        val context = requireContext()
        val density = resources.displayMetrics.density
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt(),
                (16 * density).toInt()
            )
            actions.forEachIndexed { index, action ->
                addView(
                    MaterialButton(
                        context,
                        null,
                        com.google.android.material.R.attr.materialButtonOutlinedStyle
                    ).apply {
                        text = action.getLocalizedLabel(context)
                        layoutParams = LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT
                        ).apply {
                            if (index > 0) topMargin = (12 * density).toInt()
                        }
                        setOnClickListener { action.onClick(context) }
                    }
                )
            }
        }
    }

    /**
     * 动态创建"更多设置"折叠区域。
     */
    private fun createAdvancedSection(fields: List<EditorFieldModel>): View {
        val context = requireContext()
        val density = resources.displayMetrics.density

        // 为每个模块生成唯一的 key 来保存展开状态（单次会话）
        val stateKey = "advanced_section_expanded_${module.id}"
        var isExpanded = expandedSections[stateKey] ?: false

        // 根容器
        val rootLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }

        // 标题栏 (点击区域)
        val headerLayout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // 使用系统可点击背景
            val attrs = intArrayOf(android.R.attr.selectableItemBackground)
            val typedArray = context.obtainStyledAttributes(attrs)
            background = typedArray.getDrawable(0)
            typedArray.recycle()

            setPadding(
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt(),
                (12 * density).toInt()
            )
        }

        // 图标
        val icon = ImageView(context).apply {
            setImageResource(R.drawable.rounded_settings_24)
            layoutParams = LinearLayout.LayoutParams((20 * density).toInt(), (20 * density).toInt())
            val color = com.google.android.material.color.MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY
            )
            setColorFilter(color)
            alpha = 0.7f
        }

        // 文字
        val title = TextView(context).apply {
            text = getString(R.string.editor_more_settings)
            textSize = 16f
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = (12 * density).toInt()
            }
            setTypeface(null, android.graphics.Typeface.BOLD)
        }

        // 箭头
        val arrow = ImageView(context).apply {
            setImageResource(R.drawable.rounded_arrow_drop_down_24)
            layoutParams = LinearLayout.LayoutParams((24 * density).toInt(), (24 * density).toInt())
            val color = com.google.android.material.color.MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                android.graphics.Color.GRAY
            )
            setColorFilter(color)
            alpha = 0.7f
        }

        headerLayout.addView(icon)
        headerLayout.addView(title)
        headerLayout.addView(arrow)

        // 内容容器 (默认隐藏)
        val contentLayout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(0, (8 * density).toInt(), 0, 0)
        }

        // 填充参数到内容容器
        fields.forEach { fieldModel ->
            val inputView = createViewForInputDefinition(fieldModel)
            contentLayout.addView(inputView)
            // 注册到 inputViews，这样 readParametersFromUi 就能自动读取它们
            inputViews[fieldModel.inputDefinition.id] = inputView
        }

        // 设置点击事件
        contentLayout.isVisible = isExpanded
        arrow.rotation = if (isExpanded) 180f else 0f

        headerLayout.setOnClickListener {
            isExpanded = !isExpanded
            contentLayout.isVisible = isExpanded
            arrow.animate()
                .rotation(if (isExpanded) 180f else 0f)
                .setDuration(200)
                .start()

            // 保存展开状态到内存（单次会话）
            expandedSections[stateKey] = isExpanded
        }

        rootLayout.addView(headerLayout)
        rootLayout.addView(contentLayout)
        return rootLayout
    }

    /**
     * 为输入参数创建视图。
     * 对于 CHIP_GROUP 风格，使用简化的布局（不带标签和魔法变量按钮）。
     * 对于 PICKER 类型，使用带选择器图标的输入框。
     * 对于其他风格，使用完整的参数输入行布局。
     */
    private fun createViewForInputDefinition(fieldModel: EditorFieldModel): View {
        val inputDef = fieldModel.inputDefinition
        val currentValue = fieldModel.currentValue

        // CHIP_GROUP 风格不需要魔法变量按钮，使用简化布局
        if (inputDef.inputStyle == InputStyle.CHIP_GROUP) {
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.row_editor_input, null, false)
            row.findViewById<TextView>(R.id.input_name).text = inputDef.getDisplayName(requireContext())
            row.findViewById<ImageButton>(R.id.button_magic_variable).visibility = View.GONE

            val valueContainer = row.findViewById<ViewGroup>(R.id.input_value_container)
            valueContainer.removeAllViews()

            val chipGroupView = StandardControlFactory.createChipGroup(
                context = requireContext(),
                options = inputDef.options,
                currentValue = currentValue as? String,
                onSelectionChanged = { selectedItem ->
                    if (currentParameters[inputDef.id] != selectedItem) {
                        applyParameterUpdate(
                            inputId = inputDef.id,
                            updatedValue = selectedItem,
                            readUiFirst = true,
                            rebuildMode = RebuildMode.POST
                        )
                    }
                },
                optionsStringRes = if (inputDef.optionsStringRes.isNotEmpty()) inputDef.optionsStringRes else null
            )
            valueContainer.addView(chipGroupView)
            row.tag = inputDef.id
            return row
        }

        // PICKER 类型使用带选择器图标的输入框
        if (inputDef.pickerType != PickerType.NONE && !inputDef.supportsRichText) {
            val row = LayoutInflater.from(requireContext()).inflate(R.layout.row_editor_input, null, false)
            row.findViewById<TextView>(R.id.input_name).text = inputDef.getDisplayName(requireContext())
            row.findViewById<ImageButton>(R.id.button_magic_variable).visibility = View.GONE

            val valueContainer = row.findViewById<ViewGroup>(R.id.input_value_container)
            valueContainer.removeAllViews()

            val pickerInputView = StandardControlFactory.createPickerInput(
                context = requireContext(),
                currentValue = currentValue,
                pickerType = inputDef.pickerType,
                hint = inputDef.getLocalizedHint(requireContext()),
                onPickerClicked = {
                    dispatchPickerRequest(inputDef)
                }
            )
            valueContainer.addView(pickerInputView)
            row.tag = inputDef.id
            return row
        }

        // 其他风格使用标准布局
        return StandardControlFactory.createParameterInputRow(
            context = requireContext(),
            inputDef = inputDef,
            currentValue = currentValue,
            allSteps = allSteps,
            onMagicVariableRequested = ::requestMagicVariableSelection,
            onPickerRequested = ::dispatchPickerRequest,
            onEnumItemSelected = { selectedItem ->
                // 防止重复触发：只在值真正改变时才处理
                if (currentParameters[inputDef.id] != selectedItem) {
                    applyParameterUpdate(
                        inputId = inputDef.id,
                        updatedValue = selectedItem,
                        readUiFirst = true,
                        rebuildMode = RebuildMode.POST
                    )
                }
            }
        ).also { row ->
            bindImmediateDynamicInputUpdates(row, inputDef)
            bindKeyboardToolbarFocus(row, inputDef)
        }
    }

    private fun bindKeyboardToolbarFocus(row: View, inputDef: InputDefinition) {
        if (!inputDef.acceptsMagicVariable && !inputDef.acceptsNamedVariable && !inputDef.allowsInlineScript) {
            return
        }
        findDescendantTextInputs(row).forEach { editText ->
            bindKeyboardToolbarFocus(editText, inputDef.id, row)
            bindInlineScriptEditing(editText)
        }
    }

    private fun bindKeyboardToolbarFocus(root: View) {
        findDescendantTextInputs(root).forEach { editText ->
            val inputId = editText.tag as? String ?: return@forEach
            val inputDef = findDynamicInputDefinition(inputId) ?: return@forEach
            if (!inputDef.acceptsMagicVariable && !inputDef.acceptsNamedVariable && !inputDef.allowsInlineScript) {
                return@forEach
            }
            bindKeyboardToolbarFocus(editText, inputId, root)
            bindInlineScriptEditing(editText)
        }
    }

    private fun bindCustomEditorKeyboardToolbarTargets(holder: CustomEditorViewHolder) {
        holder.getKeyboardToolbarTargets().forEach { target ->
            val inputDef = findDynamicInputDefinition(target.inputId) ?: return@forEach
            if (!inputDef.acceptsMagicVariable && !inputDef.acceptsNamedVariable && !inputDef.allowsInlineScript) {
                return@forEach
            }
            bindKeyboardToolbarFocus(target.view, target.inputId, holder.view)
        }
    }

    private fun bindInlineScriptEditing(editText: com.google.android.material.textfield.TextInputEditText) {
        val richTextView = editText as? RichTextView ?: return
        richTextView.onInlineScriptEditRequested = { currentCode, onUpdated ->
            val inputId = richTextView.tag as? String ?: focusedToolbarInputId ?: focusedInputId
            showInlineScriptEditor(currentCode, inputId, onUpdated)
        }
        richTextView.onVariablePillEditRequested = onVariablePillEditRequested@{ currentReference, onUpdated ->
            val inputId = richTextView.tag as? String
                ?: focusedToolbarInputId
                ?: focusedInputId
                ?: return@onVariablePillEditRequested
            readParametersFromUi()
            onVariablePillEditRequested?.invoke(
                inputId,
                currentParameters.asMap(),
                currentReference,
                onUpdated
            )
        }
    }

    private fun bindKeyboardToolbarFocus(
        editText: com.google.android.material.textfield.TextInputEditText,
        inputId: String,
        scope: View
    ) {
        editText.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedToolbarInputId = inputId
            } else if (focusedToolbarInputId == inputId && !hasFocusedTextInput(scope)) {
                focusedToolbarInputId = null
            }
            updateKeyboardToolbarVisibility()
        }
        editText.setOnClickListener {
            focusedToolbarInputId = inputId
            updateKeyboardToolbarVisibility()
        }
    }

    private fun bindKeyboardToolbarFocus(
        targetView: View,
        inputId: String,
        scope: View
    ) {
        targetView.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                focusedToolbarInputId = inputId
            } else if (focusedToolbarInputId == inputId && !hasFocusedToolbarTarget(scope)) {
                focusedToolbarInputId = null
            }
            updateKeyboardToolbarVisibility()
        }
    }

    private fun findDescendantTextInputs(root: View): List<com.google.android.material.textfield.TextInputEditText> {
        val result = mutableListOf<com.google.android.material.textfield.TextInputEditText>()
        collectDescendantTextInputs(root, result)
        return result
    }

    private fun collectDescendantTextInputs(
        view: View,
        result: MutableList<com.google.android.material.textfield.TextInputEditText>
    ) {
        if (view is com.google.android.material.textfield.TextInputEditText) {
            result.add(view)
        }
        if (view !is ViewGroup) {
            return
        }
        for (index in 0 until view.childCount) {
            collectDescendantTextInputs(view.getChildAt(index), result)
        }
    }

    private fun hasFocusedTextInput(root: View): Boolean {
        return findDescendantTextInputs(root).any { it.hasFocus() }
    }

    private fun hasFocusedToolbarTarget(root: View): Boolean {
        if (hasFocusedTextInput(root)) return true
        return customEditorHolder
            ?.getKeyboardToolbarTargets()
            ?.any { isDescendantOrSelf(root, it.view) && it.view.hasFocus() }
            ?: false
    }

    private fun isDescendantOrSelf(root: View, candidate: View): Boolean {
        if (root == candidate) return true
        if (root !is ViewGroup) return false
        for (index in 0 until root.childCount) {
            if (isDescendantOrSelf(root.getChildAt(index), candidate)) {
                return true
            }
        }
        return false
    }

    private fun bindImmediateDynamicInputUpdates(row: View, inputDef: InputDefinition) {
        if (inputDef.staticType != ParameterType.BOOLEAN && inputDef.inputStyle != InputStyle.SWITCH) {
            return
        }
        val toggle = findDescendantMaterialSwitch(row) ?: return
        toggle.setOnCheckedChangeListener { _, isChecked ->
            if (currentParameters[inputDef.id] == isChecked) {
                return@setOnCheckedChangeListener
            }
            applyParameterUpdate(
                inputId = inputDef.id,
                updatedValue = isChecked,
                readUiFirst = true,
                rebuildMode = RebuildMode.POST
            )
        }
    }

    private fun findDescendantMaterialSwitch(root: View): MaterialSwitch? {
        if (root is MaterialSwitch) {
            return root
        }
        if (root !is ViewGroup) {
            return null
        }
        for (index in 0 until root.childCount) {
            val match = findDescendantMaterialSwitch(root.getChildAt(index))
            if (match != null) {
                return match
            }
        }
        return null
    }

    fun updateParametersAndRebuildUi(newParameters: Map<String, Any?>) {
        mutateSession {
            putAll(newParameters)
        }
    }

    private fun currentStepForUi(): ActionStep {
        return currentParameters.toActionStep(module.id)
    }

    private fun findDynamicInputDefinition(inputId: String): InputDefinition? {
        return module.getDynamicInputs(currentStepForUi(), allSteps).find { it.id == inputId }
    }

    private fun findRichTextView(inputId: String): RichTextView? {
        return ActionEditorRichTextLocator.findRichTextView(
            inputId = inputId,
            inputDefinition = findDynamicInputDefinition(inputId),
            inputViews = inputViews,
            customEditorHolder = customEditorHolder
        )
    }

    private fun mergeCustomEditorParametersFromUi() {
        val uiProvider = module.uiProvider
        if (uiProvider != null && customEditorHolder != null && uiProvider.hasCustomEditor()) {
            currentParameters.putAll(uiProvider.readFromEditor(customEditorHolder!!))
        }
    }

    private fun readParametersFromUi() {
        mergeCustomEditorParametersFromUi()

        inputViews.forEach { (id, view) ->
            val inputDef = findDynamicInputDefinition(id)
            val resolvedInputDef = inputDef ?: return@forEach
            val readResult = ActionEditorViewStateReader.readParameterValue(
                view = view,
                inputDefinition = resolvedInputDef,
                currentValue = currentParameters[id]
            )
            if (!readResult.shouldUpdate) return@forEach
            currentParameters[id] = readResult.value
        }
    }

    /**
     * 更新输入框的变量。
     */
    fun updateInputWithVariable(inputId: String, variableReference: String) {
        val path = ParamPath.parse(inputId)
        val richTextView = findRichTextView(inputId)

        if (richTextView != null) {
            richTextView.insertVariablePill(variableReference)
            return
        }

        if (customEditorHolder?.insertVariable(inputId, variableReference) == true) {
            return
        }

        mutateSession {
            setPath(path, variableReference)
        }
    }

    /**
     * 清除输入框的变量。
     */
    fun clearInputVariable(inputId: String) {
        val path = ParamPath.parse(inputId)
        val topLevelDefaultValue = module.getInputs().find { it.id == path.rootId }?.defaultValue
        mutateSession {
            clearPath(path, topLevelDefaultValue)
        }
    }
}
