// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/ReturnKeyEditorSheet.kt
// 描述：「停止并返回」的**返回键**编辑底部弹窗。负责单个 return key 的增删改：
//      键名（校验 + 去重）+ 类型下拉（「任意」+ 8 种类型）。
//
// 这是 fork 新增文件（上游无此文件）。
//
// ⚠️ 刻意**复用** sheet_define_function_param_editor.xml（不改 XML），只在代码里：
//  ① 隐藏「默认值」与「必填」两块（return key 没有这两个概念）；
//  ② 覆盖两个 hint（布局原文案是「参数名 / 参数类型」，与返回键语义不符）。
//
// ⚠️ 刻意**不复用** DictionaryKVAdapter 做行编辑器 —— 它在 9 处生产代码里在用，
//    改它的行结构会波及 8 个无关模块。
//
// 键的类型存 VTypeRegistry 的类型 id（vflow.type.*），**不引入第二套「类型简写」词表**。
// 「留空」的语义 = VTypeRegistry.ANY.id。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentManager
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionParamError
import com.chaomixian.vflow.core.workflow.model.FunctionParamValidator
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import com.chaomixian.vflow.ui.workflow_editor.StandardControlFactory
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

class ReturnKeyEditorSheet : BottomSheetDialogFragment() {

    private var editingIndex: Int = -1
    private var keys: MutableList<ReturnKey> = mutableListOf()
    private var onChanged: (() -> Unit)? = null

    private lateinit var editKeyName: TextInputEditText
    private lateinit var dropdownKeyType: TextInputLayout
    private var currentType: String = VTypeRegistry.ANY.id

    companion object {
        /** 可选类型：ANY（「任意」，默认）+ 8 种基础/常用类型。 */
        private val TYPE_IDS: List<String> = listOf(
            VTypeRegistry.ANY.id,
            VTypeRegistry.STRING.id,
            VTypeRegistry.NUMBER.id,
            VTypeRegistry.BOOLEAN.id,
            VTypeRegistry.DICTIONARY.id,
            VTypeRegistry.LIST.id,
            VTypeRegistry.IMAGE.id,
            VTypeRegistry.FILE.id,
            VTypeRegistry.COORDINATE.id
        )

        /**
         * 显示返回键编辑弹窗。
         * 形态照 [DefineFunctionParamEditorSheet.show]：字段直赋，**不走 arguments Bundle**。
         */
        fun show(
            context: Context,
            existing: ReturnKey?,
            keys: MutableList<ReturnKey>,
            onChanged: () -> Unit
        ) {
            val activity = context.findFragmentActivity()
            val fm: FragmentManager? = activity?.supportFragmentManager
            if (fm == null) {
                Toast.makeText(context, context.getString(R.string.editor_return_key_no_host), Toast.LENGTH_SHORT).show()
                return
            }
            val editor = ReturnKeyEditorSheet()
            editor.editingIndex = keys.indexOf(existing)
            editor.keys = keys
            editor.onChanged = onChanged
            editor.isCancelable = true
            try {
                editor.show(fm, "ReturnKeyEditorSheet")
            } catch (e: Exception) {
                Toast.makeText(context, e.localizedMessage ?: context.getString(R.string.editor_return_key_no_host), Toast.LENGTH_SHORT).show()
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

        val textTitle = view.findViewById<android.widget.TextView>(R.id.text_param_editor_title)
        editKeyName = view.findViewById(R.id.edit_param_name)
        val layoutParamName = view.findViewById<TextInputLayout>(R.id.layout_param_name)
        dropdownKeyType = view.findViewById(R.id.dropdown_param_type)

        // ① 隐藏 return key 用不到的两块
        view.findViewById<View>(R.id.layout_param_default).visibility = View.GONE
        view.findViewById<View>(R.id.switch_param_required).visibility = View.GONE

        // ② 覆盖 hint（布局原文案是「参数名 / 参数类型」）
        layoutParamName.hint = getString(R.string.editor_return_key_name_hint)
        dropdownKeyType.hint = getString(R.string.editor_return_key_type_hint)

        val existing = keys.getOrNull(editingIndex)
        if (existing != null) {
            textTitle.text = context.getString(R.string.editor_return_key_title_edit)
            editKeyName.setText(existing.name)
        } else {
            textTitle.text = context.getString(R.string.editor_return_key_title_add)
        }

        currentType = existing?.type?.takeIf { it in TYPE_IDS } ?: VTypeRegistry.ANY.id
        StandardControlFactory.bindDropdown(
            textInputLayout = dropdownKeyType,
            options = TYPE_IDS,
            selectedValue = currentType,
            displayOptions = TYPE_IDS.map { VTypeRegistry.getType(it).getLocalizedName(context) },
            onItemSelectedCallback = { selected -> currentType = selected }
        )

        val btnSave = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_param_save)
        val btnCancel = view.findViewById<com.google.android.material.button.MaterialButton>(R.id.button_param_cancel)

        btnCancel.setOnClickListener { dismiss() }
        btnSave.setOnClickListener {
            val name = editKeyName.text?.toString()?.trim().orEmpty()
            val error = validateName(name, editingIndex)
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val key = ReturnKey(name = name, type = currentType)
            if (editingIndex in keys.indices) {
                keys[editingIndex] = key
            } else {
                keys.add(key)
            }
            onChanged?.invoke()
            dismiss()
        }
    }

    /**
     * 校验键名：非空 + 不含会破坏 `{{...}}` 路径的字符（复用 [FunctionParamValidator.isValidName]，
     * 它校验的正是 `[ ] . $ { }` 与空白）+ 与其它键去重。
     * 复用 [FunctionParam] 只为搭上统一校验器（两者对名字的要求逐字相同）。
     */
    private fun validateName(name: String, editingIndex: Int): String? {
        val candidate = FunctionParam(name = name, type = currentType)
        val listToValidate = keys.map { FunctionParam(name = it.name, type = it.type) }.toMutableList().apply {
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
}
