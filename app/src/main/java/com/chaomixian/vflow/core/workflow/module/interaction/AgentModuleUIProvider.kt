// 文件: main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentModuleUIProvider.kt
package com.chaomixian.vflow.core.workflow.module.interaction

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.text.InputType
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.AiModelConfigSelectorView
import com.chaomixian.vflow.ui.workflow_editor.RichTextView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/**
 * fork（AI 能力统一批 4 的 T6）：
 * - 新增「配置来源（手动 / 引用模型配置）」选择器；
 * - 新增**可配前段** `system_prompt`（D13 / D7：引用模型配置时**值拷贝**，也可手填）。
 */
class AgentModuleUIProvider : ModuleUIProvider {
    companion object {
        private const val PROVIDER_DASHSCOPE = "dashscope"
        private const val PROVIDER_BIGMODEL = "bigmodel"
        private const val PROVIDER_CUSTOM = "custom"
    }

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val providerGroup: ChipGroup = view.findViewById(R.id.cg_provider)
        val chipBigModel: Chip = view.findViewById(R.id.chip_bigmodel)
        val chipDashScope: Chip = view.findViewById(R.id.chip_dashscope)
        val chipCustom: Chip = view.findViewById(R.id.chip_custom)

        val baseUrlEdit: TextInputEditText = view.findViewById(R.id.et_base_url)
        val modelEdit: TextInputEditText = view.findViewById(R.id.et_model)
        val apiKeyEdit: TextInputEditText = view.findViewById(R.id.et_api_key)

        val instructionContainer: FrameLayout = view.findViewById(R.id.container_instruction)

        val stepsSlider: Slider = view.findViewById(R.id.slider_max_steps)
        val stepsText: TextView = view.findViewById(R.id.tv_max_steps_value)

        var instructionRichText: RichTextView? = null
        var allSteps: List<ActionStep>? = null

        // fork：配置引用 —— 选择器实例 + 需要在引用模式下置灰的控件。
        // ⚠️ `internal`：`AiModelConfigSelectorView` 是 internal 类型，而本类是 public。
        internal var configSelector: AiModelConfigSelectorView? = null
        val manualOnlyViews: MutableList<View> = mutableListOf()

        /** 可配前段（plain 多行文本框，不走富文本 ⇒ 不会踩胶囊坑）。 */
        var systemPromptEdit: TextInputEditText? = null
    }

    override fun getHandledInputIds(): Set<String> = setOf(
        "provider", "base_url", "model", "api_key", "instruction", "max_steps",
        // fork：配置引用 + 可配前段由自绘控件接管。
        AiModelConfigRef.PARAM_SOURCE, AiModelConfigRef.PARAM_CONFIG_ID, "system_prompt",
    )

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.partial_agent_editor, parent, false)
        val holder = ViewHolder(view)
        holder.allSteps = allSteps

        // 隐藏旧的工具选择 UI，未来可能再次加入
        view.findViewById<View>(R.id.btn_select_tools)?.isVisible = false
        view.findViewById<View>(R.id.tv_selected_tools)?.isVisible = false

        // 恢复服务商配置
        val provider = currentParameters["provider"] as? String ?: PROVIDER_DASHSCOPE
        when (provider) {
            PROVIDER_DASHSCOPE -> holder.chipDashScope.isChecked = true
            PROVIDER_BIGMODEL -> holder.chipBigModel.isChecked = true
            else -> holder.chipCustom.isChecked = true
        }

        holder.baseUrlEdit.setText(currentParameters["base_url"] as? String ?: "https://dashscope.aliyuncs.com/compatible-mode/v1")
        holder.modelEdit.setText(currentParameters["model"] as? String ?: "glm-4.6v-flash")
        holder.apiKeyEdit.setText(currentParameters["api_key"] as? String ?: "")

        // 恢复 Instruction
        setupInstructionEditor(context, holder, currentParameters["instruction"] as? String ?: "", onMagicVariableRequested)

        // 恢复 Max Steps
        val maxSteps = (currentParameters["max_steps"] as? Number)?.toFloat() ?: 10f
        holder.stepsSlider.value = maxSteps
        holder.stepsText.text = "${maxSteps.toInt()}"

        // fork：配置引用选择器 + 可配前段（插到编辑器顶部）
        holder.manualOnlyViews += listOf(
            holder.providerGroup,
            holder.baseUrlEdit,
            holder.modelEdit,
            holder.apiKeyEdit,
        )
        val selector = AiModelConfigSelectorView(
            context = context,
            // 本模块拥有 system_prompt（前段）⇒ 值拷贝成立；**没有** temperature 参数。
            ownedValueCopyKeys = setOf(AiModelConfigRef.PARAM_SYSTEM_PROMPT),
            onApply = { writes -> applyConfigWrites(holder, writes, onParametersChanged) },
            onParametersChanged = onParametersChanged,
        )
        holder.configSelector = selector

        val prefixLayout = buildSystemPromptField(
            context = context,
            holder = holder,
            initialValue = currentParameters["system_prompt"] as? String ?: "",
            onParametersChanged = onParametersChanged,
        )
        val root = view as? LinearLayout
        // 倒序插入：先插前段（会落到 index 0），再插选择器（最终 index 0）⇒ 选择器在上、前段在下。
        root?.addView(prefixLayout, 0)
        root?.addView(selector.view, 0)

        selector.render(currentParameters)
        applyReferenceLock(holder, currentParameters)

        // 监听器
        holder.providerGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            when (checkedIds[0]) {
                R.id.chip_bigmodel -> {
                    holder.baseUrlEdit.setText("https://open.bigmodel.cn/api/paas/v4")
                    holder.modelEdit.setText("glm-4.6v-flash")
                }
                R.id.chip_dashscope -> {
                    holder.baseUrlEdit.setText("https://dashscope.aliyuncs.com/compatible-mode/v1")
                    holder.modelEdit.setText("glm-4.6")
                }
            }
            onParametersChanged()
        }

        val textWatcher = { _: Editable? -> onParametersChanged() }
        holder.baseUrlEdit.doAfterTextChanged(textWatcher)
        holder.modelEdit.doAfterTextChanged(textWatcher)
        holder.apiKeyEdit.doAfterTextChanged(textWatcher)

        holder.stepsSlider.addOnChangeListener { _, value, _ ->
            holder.stepsText.text = "${value.toInt()}"
            onParametersChanged()
        }

        return holder
    }

    /**
     * 可配前段的多行 plain 文本框。
     *
     * ⚠️ **故意不用 `createRichTextEditor`**：前段是「一段系统提示词」，不接魔法变量
     * （`InputDefinition.acceptsMagicVariable = false`）⇒ 用普通 `TextInputEditText`，
     * 从根上避开「漏写 supportsRichText ⇒ 胶囊不渲染」那个反复踩的坑。
     */
    private fun buildSystemPromptField(
        context: Context,
        holder: ViewHolder,
        initialValue: String,
        onParametersChanged: () -> Unit,
    ): TextInputLayout {
        val layout = TextInputLayout(context).apply {
            hint = context.getString(R.string.hint_ai_agent_system_prompt_prefix)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
        val edit = TextInputEditText(context).apply {
            setText(initialValue)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setSingleLine(false)
            minLines = 2
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        }
        layout.addView(edit)
        holder.systemPromptEdit = edit
        return layout
    }

    /** 选择器回调 → 写进本模块的控件（值拷贝 `system_prompt` 要真的落到控件上）。 */
    private fun applyConfigWrites(
        holder: ViewHolder,
        writes: Map<String, Any?>,
        onParametersChanged: () -> Unit,
    ) {
        (writes[AiModelConfigRef.PARAM_SYSTEM_PROMPT] as? String)?.let { value ->
            holder.systemPromptEdit?.setText(value)
        }
        applyReferenceLock(holder, writes)
        onParametersChanged()
    }

    /** 引用模式 ⇒ 置灰手动字段（provider / base_url / model / api_key）。 */
    private fun applyReferenceLock(holder: ViewHolder, parameters: Map<String, Any?>) {
        // `sourceOf` 自带 legacyValueMap 容错（「引用」/`Reference` 都认）。
        val isReference = AiModelConfigRef.sourceOf(parameters) == AiModelConfigRef.SOURCE_REFERENCE
        holder.manualOnlyViews.forEach { it.isEnabled = !isReference }
    }

    private fun setupInstructionEditor(
        context: Context,
        holder: ViewHolder,
        initialValue: String,
        onMagicReq: ((String) -> Unit)?
    ) {
        holder.instructionContainer.removeAllViews()
        val row = LayoutInflater.from(context).inflate(R.layout.row_editor_input, null)
        row.findViewById<TextView>(R.id.input_name).visibility = View.GONE
        val valueContainer = row.findViewById<ViewGroup>(R.id.input_value_container)
        val magicButton = row.findViewById<ImageButton>(R.id.button_magic_variable)

        magicButton.setOnClickListener { onMagicReq?.invoke("instruction") }

        val richEditorLayout = LayoutInflater.from(context).inflate(R.layout.rich_text_editor, valueContainer, false)
        val richTextView = richEditorLayout.findViewById<RichTextView>(R.id.rich_text_view)

        richTextView.setRichText(initialValue, holder.allSteps ?: emptyList())
        richTextView.tag = "instruction"

        holder.instructionRichText = richTextView
        valueContainer.addView(richEditorLayout)
        holder.instructionContainer.addView(row)
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder

        val provider = when {
            h.chipDashScope.isChecked -> PROVIDER_DASHSCOPE
            h.chipCustom.isChecked -> PROVIDER_CUSTOM
            else -> PROVIDER_BIGMODEL
        }

        val configReadBack = h.configSelector?.readBack().orEmpty()
        val isReference =
            configReadBack[AiModelConfigRef.PARAM_SOURCE] == AiModelConfigRef.SOURCE_REFERENCE

        return mapOf(
            "provider" to provider,
            "base_url" to h.baseUrlEdit.text.toString(),
            "model" to h.modelEdit.text.toString(),
            // ⚠️ 引用模式下 `api_key` **必须写空串**（§1.7：密钥由被引用的配置提供）。
            "api_key" to if (isReference) "" else h.apiKeyEdit.text.toString(),
            "instruction" to (h.instructionRichText?.getRawText() ?: ""),
            "max_steps" to h.stepsSlider.value.toDouble(),
            "system_prompt" to (h.systemPromptEdit?.text?.toString() ?: "")
        ) + configReadBack
    }
}
