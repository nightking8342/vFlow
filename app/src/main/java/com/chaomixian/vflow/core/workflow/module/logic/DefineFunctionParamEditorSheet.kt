// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/DefineFunctionParamEditorSheet.kt
// 描述：「定义函数」参数编辑底部弹窗。负责单个参数的增删改：
//      参数名（snake_case 校验 + 去重）、类型下拉（8 种）、默认值（随类型切换）、必填开关。
//      这是 fork 新增文件（上游无此文件）。
//
// 决策 4.1.1：参数名 snake_case 校验 + 去重；类型下拉 8 种；默认值随类型切换；是否必填开关。
// 决策 21：编辑框复用 StandardControlFactory 标准控件。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionParamError
import com.chaomixian.vflow.core.workflow.model.FunctionParamValidator
import com.chaomixian.vflow.core.workflow.module.data.CreateVariableModule
import com.chaomixian.vflow.ui.workflow_editor.DictionaryKVAdapter
import com.chaomixian.vflow.ui.workflow_editor.ListItemAdapter
import com.chaomixian.vflow.ui.workflow_editor.StandardControlFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class DefineFunctionParamEditorSheet : BottomSheetDialogFragment() {

    private var editingIndex: Int = -1
    private var params: MutableList<FunctionParam> = mutableListOf()
    private var onParametersChanged: (() -> Unit)? = null

    private lateinit var editParamName: TextInputEditText
    private lateinit var layoutParamName: TextInputLayout
    private lateinit var dropdownParamType: TextInputLayout
    private lateinit var containerParamDefault: FrameLayout
    private lateinit var switchParamRequired: MaterialSwitch
    private lateinit var textTitle: android.widget.TextView

    private var currentType: String = CreateVariableModule.TYPE_STRING
    private var currentDefaultView: View? = null
    private var currentDictionaryAdapter: DictionaryKVAdapter? = null
    private var currentListAdapter: ListItemAdapter? = null

    companion object {
        /**
         * 显示参数编辑弹窗。
         * 由于 UIProvider 拿到的 context 可能是 Application Context，这里先尝试从 Activity
         * 获取 supportFragmentManager；若拿不到则无法弹窗（这种情况下由调用方兜底）。
         */
        fun show(
            context: Context,
            existing: FunctionParam?,
            params: MutableList<FunctionParam>,
            onParametersChanged: () -> Unit
        ) {
            val activity = context.findFragmentActivity()
            val fm: FragmentManager? = activity?.supportFragmentManager
            if (fm == null) {
                Toast.makeText(context, "无法打开参数编辑（无活动宿主）", Toast.LENGTH_SHORT).show()
                return
            }
            val editor = DefineFunctionParamEditorSheet()
            editor.editingIndex = params.indexOf(existing)
            editor.params = params
            editor.onParametersChanged = onParametersChanged
            editor.isCancelable = true
            try {
                editor.show(fm, "DefineFunctionParamEditorSheet")
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: "无法打开参数编辑", Toast.LENGTH_SHORT).show()
            }
        }

        private fun Context.findFragmentActivity(): FragmentActivity? {
            var ctx = this
            while (ctx is android.content.ContextWrapper) {
                if (ctx is FragmentActivity) return ctx
                ctx = ctx.baseContext
            }
            return null
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: android.os.Bundle?
    ): View? {
        return inflater.inflate(R.layout.sheet_define_function_param_editor, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: android.os.Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val context = requireContext()

        textTitle = view.findViewById(R.id.text_param_editor_title)
        editParamName = view.findViewById(R.id.edit_param_name)
        layoutParamName = view.findViewById(R.id.layout_param_name)
        dropdownParamType = view.findViewById(R.id.dropdown_param_type)
        containerParamDefault = view.findViewById(R.id.container_param_default)
        switchParamRequired = view.findViewById(R.id.switch_param_required)

        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_param_save)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_param_cancel)

        // 类型选项：复用 CreateVariableModule 的 8 种类型
        val typeOptions = CreateVariableModule.TYPE_OPTIONS
        val typeLabels = typeOptions.map { CreateVariableModule.getTypeLabel(context, it) }

        val existingParam = params.getOrNull(editingIndex)
        val existingName = existingParam?.name
        // 编辑时 existingType 可能是完整类型 ID（vflow.type.string），控件分派用简写值（string），先转回简写
        val existingType = existingParam?.type?.let { FunctionParamTypeMapper.toShortValue(it) }

        if (existingName != null) {
            textTitle.text = context.getString(R.string.editor_define_function_param_title_edit)
            editParamName.setText(existingName)
        } else {
            textTitle.text = context.getString(R.string.editor_define_function_param_title_add)
        }

        currentType = existingType ?: CreateVariableModule.TYPE_STRING
        val selectedTypeIndex = typeOptions.indexOf(currentType).takeIf { it >= 0 } ?: 0

        StandardControlFactory.bindDropdown(
            textInputLayout = dropdownParamType,
            options = typeOptions,
            selectedValue = typeOptions[selectedTypeIndex],
            displayOptions = typeLabels,
            onItemSelectedCallback = { selectedType ->
                currentType = selectedType
                rebuildDefaultEditor(context, editingIndex)
            }
        )

        // 必填开关
        val existingRequired = params.getOrNull(editingIndex)?.isRequired ?: false
        switchParamRequired.isChecked = existingRequired

        // 默认值编辑框
        rebuildDefaultEditor(context, editingIndex)

        btnCancel.setOnClickListener { dismiss() }
        btnSave.setOnClickListener {
            val name = editParamName.text?.toString()?.trim().orEmpty()
            val validation = validateName(name, editingIndex)
            if (validation != null) {
                Toast.makeText(context, validation, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val defaultValue = readDefaultValue(context)
            val param = FunctionParam(
                name = name,
                // 存进 FunctionParam 的类型必须是完整类型 ID（vflow.type.*），
                // 否则下游 VTypeRegistry.getType / toParameterType 会匹配失败（关键修复）。
                type = FunctionParamTypeMapper.toFullTypeId(currentType),
                defaultValue = defaultValue,
                isRequired = switchParamRequired.isChecked
            )
            if (editingIndex in params.indices) {
                params[editingIndex] = param
            } else {
                params.add(param)
            }
            onParametersChanged?.invoke()
            dismiss()
        }
    }

    private fun validateName(name: String, editingIndex: Int): String? {
        // 用一条临时列表模拟「当前编辑的参数」以复用统一校验
        val candidate = FunctionParam(name = name, type = currentType)
        val listToValidate = params.toMutableList().apply {
            if (editingIndex in indices) {
                this[editingIndex] = candidate
            } else {
                add(candidate)
            }
        }
        val validation = FunctionParamValidator.validate(listToValidate, editingIndex)
        if (validation.isValid) return null
        return when (validation.errorKind) {
            FunctionParamError.DUPLICATE_NAME ->
                getString(R.string.editor_define_function_duplicate_name, validation.duplicateName.orEmpty())
            else -> getString(R.string.editor_define_function_invalid_name)
        }
    }

    private fun rebuildDefaultEditor(
        context: Context,
        editingIndex: Int = -1
    ) {
        containerParamDefault.removeAllViews()
        currentDictionaryAdapter = null
        currentListAdapter = null
        val existingDefault = if (editingIndex in params.indices) params[editingIndex].defaultValue else null
        currentDefaultView = createDefaultEditor(context, currentType, existingDefault)
        currentDefaultView?.let { containerParamDefault.addView(it) }
    }

    private fun createDefaultEditor(context: Context, type: String, currentValue: Any?): View? {
        return when (type) {
            CreateVariableModule.TYPE_STRING -> {
                val input = StandardControlFactory.createTextInputLayout(
                    context, isNumber = false, currentValue = currentValue,
                    hint = context.getString(R.string.editor_define_function_param_default_hint)
                )
                input.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                input
            }
            CreateVariableModule.TYPE_NUMBER -> {
                val input = StandardControlFactory.createTextInputLayout(
                    context, isNumber = true, currentValue = currentValue,
                    hint = context.getString(R.string.editor_define_function_param_default_hint)
                )
                input.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                input
            }
            CreateVariableModule.TYPE_BOOLEAN -> {
                // 布局里已有「默认值」标题，开关不再重复写文案，只作为开/关控件
                MaterialSwitch(context).apply {
                    text = ""
                    isChecked = (currentValue as? Boolean) ?: false
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            CreateVariableModule.TYPE_DICTIONARY -> {
                // 复用「创建变量」的字典编辑器（key-value 列表），而非普通文本框
                val editorView = LayoutInflater.from(context).inflate(R.layout.partial_dictionary_editor, null, false)
                val recyclerView = editorView.findViewById<RecyclerView>(R.id.recycler_view_dictionary)
                val addButton = editorView.findViewById<android.widget.Button>(R.id.button_add_kv_pair)
                val currentMap = (currentValue as? Map<*, *>)
                    ?.mapNotNull { (k, v) -> (k?.toString() ?: return@mapNotNull null) to (v?.toString() ?: "") }
                    ?.toMutableList() ?: mutableListOf()
                val adapter = DictionaryKVAdapter(currentMap, null) { }
                recyclerView.adapter = adapter
                recyclerView.layoutManager = LinearLayoutManager(context)
                addButton.setOnClickListener { adapter.addItem() }
                currentDictionaryAdapter = adapter
                editorView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                editorView
            }
            CreateVariableModule.TYPE_LIST -> {
                // 复用「创建变量」的列表编辑器（逐项增删）
                val editorView = LayoutInflater.from(context).inflate(R.layout.partial_list_editor, null, false)
                val recyclerView = editorView.findViewById<RecyclerView>(R.id.recycler_view_list)
                val addButton = editorView.findViewById<android.widget.Button>(R.id.button_add_list_item)
                val currentList = (currentValue as? List<*>)?.map { it?.toString() ?: "" }?.toMutableList() ?: mutableListOf()
                val adapter = ListItemAdapter(currentList, null) { }
                recyclerView.adapter = adapter
                recyclerView.layoutManager = LinearLayoutManager(context)
                addButton.setOnClickListener { adapter.addItem() }
                currentListAdapter = adapter
                editorView.layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                )
                editorView
            }
            CreateVariableModule.TYPE_IMAGE, CreateVariableModule.TYPE_FILE -> {
                StandardControlFactory.createTextInputLayout(
                    context, isNumber = false, currentValue = currentValue,
                    hint = context.getString(R.string.editor_define_function_param_default_hint)
                ).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            CreateVariableModule.TYPE_COORDINATE -> {
                StandardControlFactory.createTextInputLayout(
                    context, isNumber = false, currentValue = currentValue,
                    hint = context.getString(R.string.editor_define_function_param_default_hint)
                ).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
                    )
                }
            }
            else -> null
        }
    }

    private fun readDefaultValue(context: Context): Any? {
        val view = currentDefaultView ?: return null
        val type = currentType
        when (type) {
            CreateVariableModule.TYPE_BOOLEAN -> {
                return (view as? MaterialSwitch)?.isChecked ?: false
            }
            CreateVariableModule.TYPE_NUMBER -> {
                val text = findTextInView(view)?.trim().orEmpty()
                return text.toDoubleOrNull()
            }
            CreateVariableModule.TYPE_LIST -> {
                val adapter = currentListAdapter ?: return null
                return adapter.getItems()
            }
            CreateVariableModule.TYPE_DICTIONARY -> {
                val adapter = currentDictionaryAdapter ?: return null
                return adapter.getItemsAsMap().takeIf { it.isNotEmpty() }
            }
            else -> {
                val text = findTextInView(view)?.trim().orEmpty()
                return text.ifEmpty { null }
            }
        }
    }

    private fun findTextInView(view: View): String? {
        // 递归查找 TextInputEditText / EditText
        val textInputLayout = view as? TextInputLayout
        if (textInputLayout != null) {
            return textInputLayout.editText?.text?.toString()
        }
        if (view is EditText) return view.text?.toString()
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val result = findTextInView(view.getChildAt(i))
                if (result != null) return result
            }
        }
        return null
    }
}
