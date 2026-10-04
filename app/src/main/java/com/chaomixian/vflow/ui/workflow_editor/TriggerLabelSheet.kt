// 文件: main/java/com/chaomixian/vflow/ui/workflow_editor/TriggerLabelSheet.kt
// 描述：触发器标签输入弹窗（fork 新增）。纯自由输入、无「已用标签」快捷选。
//      ⚠️ 刻意**不放进触发器自己的参数编辑 sheet** —— 标签是触发器卡片上的属性，
//      不是模块声明的参数；放进参数 sheet 会让它随模块定义走，且与「标签不参与表单」冲突。
package com.chaomixian.vflow.ui.workflow_editor

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import com.chaomixian.vflow.R
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText

class TriggerLabelSheet : BottomSheetDialogFragment() {

    /** 确定时回调（已 trim）。空串 = 清空标签。 */
    var onSave: ((String) -> Unit)? = null

    private lateinit var labelEdit: TextInputEditText

    companion object {
        private const val ARG_CURRENT_LABEL = "current_label"

        fun newInstance(currentLabel: String): TriggerLabelSheet {
            return TriggerLabelSheet().apply {
                arguments = Bundle().apply {
                    putString(ARG_CURRENT_LABEL, currentLabel)
                }
            }
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        val view = inflater.inflate(R.layout.sheet_trigger_label, container, false)
        labelEdit = view.findViewById(R.id.edit_trigger_label)
        val clearButton: MaterialButton = view.findViewById(R.id.button_trigger_label_clear)
        val confirmButton: MaterialButton = view.findViewById(R.id.button_trigger_label_confirm)

        labelEdit.setText(arguments?.getString(ARG_CURRENT_LABEL).orEmpty())

        clearButton.setOnClickListener {
            onSave?.invoke("")
            dismiss()
        }

        confirmButton.setOnClickListener {
            onSave?.invoke(labelEdit.text?.toString().orEmpty())
            dismiss()
        }

        return view
    }
}
