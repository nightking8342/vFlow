// 文件: main/java/com/chaomixian/vflow/ui/workflow_editor/AiModelConfigSelectorView.kt
package com.chaomixian.vflow.ui.workflow_editor

import android.content.Context
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.ai.AiModelConfigResolver
import com.chaomixian.vflow.ui.chat.ChatPresetRepository
import com.chaomixian.vflow.ui.common.SearchableWorkflowDialog
import com.chaomixian.vflow.ui.common.WorkflowDialogItem
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

/**
 * **「配置来源（手动 / 引用模型配置）」编辑区**（fork：AI 能力统一 §3.5 / §7.4，批 4 的 T5）。
 *
 * 三个 AI 模块共用。形态照 `CallWorkflowModuleUIProvider` 的「选择按钮 + 对话框」先例；
 * **视图全部代码构建**（不新增 XML 布局），对话框复用 `SearchableWorkflowDialog`。
 *
 * ## 为什么不用「新增 PickerType」那条路（§7.4 的取舍）
 *
 * 1. 分发点在 `PickerHandler.handle()` 的 `when (inputDef.pickerType)`，Kotlin 把它当**语句**用
 *    ⇒ 新增枚举值**不报错、静默什么都不做**（本仓库最怕的静默失效）；
 * 2. 要让它工作必须改 `PickerHandler.kt`（可能还要 `ActionEditorSheet.kt` /
 *    `StandardControlFactory.kt`），而这三个文件**不在本任务的允许改动范围内**；
 * 3. 选择器需要「当前选中项 + 已删提示 + 值拷贝回写」，通用 picker 通道只回写一个字符串。
 *
 * ⚠️ **真机交互未验证**（无设备）；逻辑部分（值拷贝写入 / 缺配置 / 配置已删）由
 * `AiModelConfigResolverTest` 的纯函数用例覆盖。
 *
 * @param ownedValueCopyKeys 该模块**拥有**哪些可被值拷贝的参数 id
 *   （见 [AiModelConfigResolver.selectionWrites]）。`AutoGLMModule` 传空集。
 * @param onApply 选中配置 / 切回手动时的写入集合；模块 UIProvider 据此把值写进
 *   自己的控件与参数（引用模式还要置灰手动字段）。
 */
internal class AiModelConfigSelectorView(
    private val context: Context,
    private val ownedValueCopyKeys: Set<String>,
    private val onApply: (Map<String, Any?>) -> Unit,
    private val onParametersChanged: () -> Unit,
) {

    private val repository = ChatPresetRepository(context)

    private val titleText = TextView(context).apply {
        text = context.getString(R.string.param_ai_config_source_name)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
    }

    private val chipManual = Chip(context).apply {
        id = View.generateViewId()
        text = context.getString(R.string.option_ai_config_source_manual)
        isCheckable = true
    }

    private val chipReference = Chip(context).apply {
        id = View.generateViewId()
        text = context.getString(R.string.option_ai_config_source_reference)
        isCheckable = true
    }

    private val sourceGroup = ChipGroup(context).apply {
        isSingleSelection = true
        addView(chipManual)
        addView(chipReference)
    }

    private val statusText = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    private val selectButton = MaterialButton(
        context,
        null,
        com.google.android.material.R.attr.materialButtonOutlinedStyle,
    ).apply {
        text = context.getString(R.string.button_ai_select_model_config)
    }

    private val manualButton = MaterialButton(
        context,
        null,
        android.R.attr.borderlessButtonStyle,
    ).apply {
        text = context.getString(R.string.option_ai_config_source_manual)
    }

    private val hintText = TextView(context).apply {
        text = context.getString(R.string.hint_ai_config_reference_overrides_manual)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    }

    /** 根视图：由模块 UIProvider 插进自己的编辑器根 `LinearLayout`。 */
    val view: View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        val pad = (context.resources.displayMetrics.density * 4).toInt()
        setPadding(0, pad, 0, pad)
        addView(titleText)
        addView(sourceGroup)
        addView(statusText)
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(selectButton)
                addView(manualButton)
            },
        )
        addView(hintText)
    }

    /** 当前来源（`manual` / `reference`）。 */
    private var source: String = AiModelConfigRef.SOURCE_MANUAL

    /** 当前引用的配置 id（手动模式下仍保留，便于来回切换）。 */
    private var configId: String = ""

    /** `render()` 期间程序化改选中态会回调监听器 ⇒ 用这个闸门避免「渲染即写入」的递归。 */
    private var rendering = false

    init {
        sourceGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (rendering) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds.firstOrNull() ?: return@setOnCheckedStateChangeListener
            val newSource =
                if (checkedId == chipReference.id) AiModelConfigRef.SOURCE_REFERENCE
                else AiModelConfigRef.SOURCE_MANUAL
            if (newSource == source) return@setOnCheckedStateChangeListener
            source = newSource
            applySourceChange()
        }

        manualButton.setOnClickListener {
            if (source == AiModelConfigRef.SOURCE_MANUAL) return@setOnClickListener
            source = AiModelConfigRef.SOURCE_MANUAL
            applySourceChange()
        }

        selectButton.setOnClickListener { openPicker() }
    }

    /** 用当前步骤参数渲染（来源、选中的配置名、缺配置 / 配置已删的提示）。 */
    fun render(currentParameters: Map<String, Any?>) {
        source = AiModelConfigRef.sourceOf(currentParameters)
        configId = AiModelConfigRef.configIdOf(currentParameters)
        refresh()
    }

    /** 供 UIProvider 的 `readFromEditor` 读回（只含 `config_source` / `model_config_id`）。 */
    fun readBack(): Map<String, Any?> = mapOf(
        AiModelConfigRef.PARAM_SOURCE to source,
        AiModelConfigRef.PARAM_CONFIG_ID to configId,
    )

    // ------------------------------------------------------------ 内部

    private fun applySourceChange() {
        val writes = if (source == AiModelConfigRef.SOURCE_REFERENCE) {
            // 只切来源：此时还没有选中具体配置 ⇒ 只写 config_source（configId 原样保留）。
            mapOf(AiModelConfigRef.PARAM_SOURCE to AiModelConfigRef.SOURCE_REFERENCE)
        } else {
            AiModelConfigResolver.manualWrites()
        }
        refresh()
        onApply(writes)
        onParametersChanged()
    }

    private fun openPicker() {
        val presets = repository.getPresets()
        // 空配置列表 ⇒ 不弹空对话框（状态行已经写了 text_ai_config_no_configs）。
        if (presets.isEmpty()) {
            refresh()
            return
        }
        SearchableWorkflowDialog.show(
            context = context,
            titleResId = R.string.dialog_ai_select_model_config_title,
            items = presets.map { WorkflowDialogItem(id = it.id, name = it.name.ifBlank { it.id }) },
            onSelected = { item ->
                repository.getPresets().firstOrNull { it.id == item.id }?.let { config ->
                    source = AiModelConfigRef.SOURCE_REFERENCE
                    configId = config.id
                    refresh()
                    onApply(AiModelConfigResolver.selectionWrites(config, ownedValueCopyKeys))
                    onParametersChanged()
                }
            },
        )
    }

    private fun refresh() {
        val isReference = source == AiModelConfigRef.SOURCE_REFERENCE
        val presets = repository.getPresets()

        rendering = true
        chipManual.isChecked = !isReference
        chipReference.isChecked = isReference
        rendering = false

        manualButton.isVisible = isReference
        hintText.isVisible = isReference
        selectButton.isEnabled = presets.isNotEmpty()
        statusText.isVisible = isReference

        if (!isReference) return

        val referenced = presets.firstOrNull { it.id == configId }
        statusText.text = when {
            presets.isEmpty() -> context.getString(R.string.text_ai_config_no_configs)
            configId.isBlank() -> context.getString(R.string.text_ai_config_selected_none)
            referenced != null -> buildString {
                append(referenced.name.ifBlank { referenced.id })
                append(" · ")
                append(referenced.provider)
                append(" · ")
                append(referenced.model)
            }

            else -> context.getString(R.string.text_ai_config_missing)
        }
    }
}