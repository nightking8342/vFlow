// 文件: main/java/com/chaomixian/vflow/core/workflow/module/network/AIModuleUIProvider.kt
package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import android.content.Intent
import android.text.Editable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
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
import java.util.Locale

class AIModuleUIProvider : ModuleUIProvider {
    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val providerGroup: ChipGroup = view.findViewById(R.id.cg_provider)
        val chipOpenAI: Chip = view.findViewById(R.id.chip_openai)
        val chipDeepSeek: Chip = view.findViewById(R.id.chip_deepseek)
        val chipCustom: Chip = view.findViewById(R.id.chip_custom)

        val apiKeyEdit: TextInputEditText = view.findViewById(R.id.et_api_key)
        val modelEdit: TextInputEditText = view.findViewById(R.id.et_model)
        val promptContainer: FrameLayout = view.findViewById(R.id.container_prompt)

        val advancedHeader: LinearLayout = view.findViewById(R.id.layout_advanced_header)
        val advancedContainer: LinearLayout = view.findViewById(R.id.container_advanced)
        val expandArrow: ImageView = view.findViewById(R.id.iv_expand_arrow)

        val baseUrlEdit: TextInputEditText = view.findViewById(R.id.et_base_url)
        val systemPromptEdit: TextInputEditText = view.findViewById(R.id.et_system_prompt)
        val tempSlider: Slider = view.findViewById(R.id.slider_temperature)
        val tempText: TextView = view.findViewById(R.id.tv_temperature_value)

        // 引用动态创建的 RichTextView
        var promptRichText: RichTextView? = null
        var allSteps: List<ActionStep>? = null

        // fork：配置引用（AiModelConfigRef）—— 选择器实例 + 需要在引用模式下置灰的控件。
        // ⚠️ 声明为 `internal`：`AiModelConfigSelectorView` 是 internal 类型，
        //    而本 ViewHolder 类是 public（`internal` 成员在 public 类里合法）。
        internal var configSelector: AiModelConfigSelectorView? = null
        val manualOnlyViews: MutableList<View> = mutableListOf()
    }

    override fun getHandledInputIds(): Set<String> = setOf(
        "provider", "api_key", "base_url", "model", "prompt", "system_prompt", "temperature", "show_advanced",
        // fork：配置引用由自绘选择器接管（见 AiModelConfigSelectorView）。
        AiModelConfigRef.PARAM_SOURCE, AiModelConfigRef.PARAM_CONFIG_ID,
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
        val view = LayoutInflater.from(context).inflate(R.layout.partial_ai_completion_editor, parent, false)
        val holder = ViewHolder(view)
        holder.allSteps = allSteps

        // 恢复参数
        val provider = AIModule.PROVIDER_INPUT_DEFINITION.normalizeEnumValueOrNull(currentParameters["provider"] as? String)
            ?: AIModule.PROVIDER_OPENAI
        when (provider) {
            AIModule.PROVIDER_OPENAI -> holder.chipOpenAI.isChecked = true
            AIModule.PROVIDER_DEEPSEEK -> holder.chipDeepSeek.isChecked = true
            else -> holder.chipCustom.isChecked = true
        }

        holder.apiKeyEdit.setText(currentParameters["api_key"] as? String ?: "")
        holder.modelEdit.setText(currentParameters["model"] as? String ?: "gpt-3.5-turbo")
        holder.baseUrlEdit.setText(currentParameters["base_url"] as? String ?: "https://api.openai.com/v1")
        holder.systemPromptEdit.setText(currentParameters["system_prompt"] as? String ?: "You are a helpful assistant.")

        val temp = (currentParameters["temperature"] as? Number)?.toFloat() ?: 0.7f
        holder.tempSlider.value = temp
        holder.tempText.text = String.format(Locale.US, "%.1f", temp)

        val showAdvanced = currentParameters["show_advanced"] as? Boolean ?: false
        holder.advancedContainer.isVisible = showAdvanced
        holder.expandArrow.rotation = if (showAdvanced) 180f else 0f

        // 创建 Prompt 富文本编辑器
        val promptValue = currentParameters["prompt"] as? String ?: ""
        setupPromptEditor(context, holder, promptValue, onMagicVariableRequested)

        // fork：配置引用选择器（插到编辑器顶部；视图全部代码构建，不新增 layout 资源）。
        holder.manualOnlyViews += listOf(
            holder.providerGroup,
            holder.baseUrlEdit,
            holder.modelEdit,
            holder.apiKeyEdit,
        )
        val selector = AiModelConfigSelectorView(
            context = context,
            // AIModule **拥有** system_prompt 与 temperature ⇒ 两项都做值拷贝（D7）。
            ownedValueCopyKeys = setOf(
                AiModelConfigRef.PARAM_SYSTEM_PROMPT,
                AiModelConfigRef.PARAM_TEMPERATURE,
            ),
            onApply = { writes -> applyConfigWrites(holder, writes, onParametersChanged) },
            onParametersChanged = onParametersChanged,
        )
        holder.configSelector = selector
        (view as? LinearLayout)?.addView(selector.view, 0)
        selector.render(currentParameters)
        applyReferenceLock(holder, currentParameters)

        // 监听器逻辑
        holder.providerGroup.setOnCheckedStateChangeListener { _, checkedIds ->
            if (checkedIds.isEmpty()) return@setOnCheckedStateChangeListener
            val checkedId = checkedIds[0]

            // 预设填充逻辑
            when (checkedId) {
                R.id.chip_openai -> {
                    holder.baseUrlEdit.setText("https://api.openai.com/v1")
                    holder.modelEdit.setText("gpt-3.5-turbo")
                }
                R.id.chip_deepseek -> {
                    holder.baseUrlEdit.setText("https://api.deepseek.com")
                    holder.modelEdit.setText("deepseek-chat")
                }
                // 自定义模式保持当前值不变
            }
            onParametersChanged()
        }

        holder.advancedHeader.setOnClickListener {
            val isVisible = holder.advancedContainer.isVisible
            holder.advancedContainer.isVisible = !isVisible
            holder.expandArrow.animate().rotation(if (!isVisible) 180f else 0f).setDuration(200).start()
            onParametersChanged()
        }

        holder.tempSlider.addOnChangeListener { _, value, _ ->
            holder.tempText.text = String.format(Locale.US, "%.1f", value)
            onParametersChanged()
        }

        val textWatcher = { _: Editable? -> onParametersChanged() }

        holder.apiKeyEdit.doAfterTextChanged(textWatcher)
        holder.modelEdit.doAfterTextChanged(textWatcher)
        holder.baseUrlEdit.doAfterTextChanged(textWatcher)
        holder.systemPromptEdit.doAfterTextChanged(textWatcher)

        return holder
    }

    /**
     * 选择器回调 → 写进本模块的控件。
     *
     * ⚠️ **值拷贝**（`system_prompt` / `temperature`）要真的落到控件上，否则用户看到的是
     * 旧值而参数里是新值 —— 那种不一致只有真机能发现。
     */
    private fun applyConfigWrites(
        holder: ViewHolder,
        writes: Map<String, Any?>,
        onParametersChanged: () -> Unit,
    ) {
        (writes[AiModelConfigRef.PARAM_SYSTEM_PROMPT] as? String)?.let { holder.systemPromptEdit.setText(it) }
        (writes[AiModelConfigRef.PARAM_TEMPERATURE] as? Number)?.let { value ->
            holder.tempSlider.value = value.toFloat()
            holder.tempText.text = String.format(Locale.US, "%.1f", value.toFloat())
        }
        applyReferenceLock(holder, writes)
        onParametersChanged()
    }

    /**
     * 引用模式 ⇒ 置灰**手动字段**（provider / base_url / model / api_key）。
     *
     * ⚠️ `system_prompt` / `temperature` **不置灰** —— 它们是值拷贝快照，选中后仍可编辑（D7）。
     */
    private fun applyReferenceLock(holder: ViewHolder, parameters: Map<String, Any?>) {
        // `sourceOf` 自带 legacyValueMap 容错（「引用」/`Reference` 都认）。
        val isReference = AiModelConfigRef.sourceOf(parameters) == AiModelConfigRef.SOURCE_REFERENCE
        holder.manualOnlyViews.forEach { it.isEnabled = !isReference }
    }

    private fun setupPromptEditor(
        context: Context,
        holder: ViewHolder,
        initialValue: String,
        onMagicReq: ((String) -> Unit)?
    ) {
        holder.promptContainer.removeAllViews()
        val row = LayoutInflater.from(context).inflate(R.layout.row_editor_input, null)
        row.findViewById<TextView>(R.id.input_name).visibility = View.GONE // 隐藏左侧标签，因为上方已有标题
        val valueContainer = row.findViewById<ViewGroup>(R.id.input_value_container)
        val magicButton = row.findViewById<ImageButton>(R.id.button_magic_variable)

        magicButton.setOnClickListener { onMagicReq?.invoke("prompt") }

        // 加载富文本编辑器布局
        val richEditorLayout = LayoutInflater.from(context).inflate(R.layout.rich_text_editor, valueContainer, false)
        val richTextView = richEditorLayout.findViewById<RichTextView>(R.id.rich_text_view)

        richTextView.setRichText(initialValue, holder.allSteps ?: emptyList())
        richTextView.tag = "prompt"

        // 将引用保存到 ViewHolder
        holder.promptRichText = richTextView

        valueContainer.addView(richEditorLayout)
        holder.promptContainer.addView(row)
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder

        val provider = when {
            h.chipDeepSeek.isChecked -> AIModule.PROVIDER_DEEPSEEK
            h.chipCustom.isChecked -> AIModule.PROVIDER_CUSTOM
            else -> AIModule.PROVIDER_OPENAI
        }

        val configReadBack = h.configSelector?.readBack().orEmpty()
        val isReference =
            configReadBack[AiModelConfigRef.PARAM_SOURCE] == AiModelConfigRef.SOURCE_REFERENCE

        return mapOf(
            "provider" to provider,
            // ⚠️ 引用模式下 `api_key` **必须写空串**：密钥由被引用的配置提供，
            //    否则工作流 JSON 里仍会留下明文密钥（§1.7 的收益就没了）。
            "api_key" to if (isReference) "" else h.apiKeyEdit.text.toString(),
            "base_url" to h.baseUrlEdit.text.toString(),
            "model" to h.modelEdit.text.toString(),
            "prompt" to (h.promptRichText?.getRawText() ?: ""),
            "system_prompt" to h.systemPromptEdit.text.toString(),
            "temperature" to h.tempSlider.value.toDouble(),
            "show_advanced" to h.advancedContainer.isVisible
        ) + configReadBack
    }
}
