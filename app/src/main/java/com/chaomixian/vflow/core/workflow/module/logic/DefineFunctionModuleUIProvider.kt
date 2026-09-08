// 文件：main/java/com/chaomixian/vflow/core/workflow/module/logic/DefineFunctionModuleUIProvider.kt
// 描述：「定义函数」模块的自定义编辑器。负责参数列表的增删改（决策 21：列表新做，编辑框复用标准控件）。
//      配置写回 step.parameters["functionParams"]（JSON 字符串），由 WorkflowManager 在保存时聚合成 FunctionSignature。
//      这是 fork 新增文件（上游无此文件）。

package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class DefineFunctionModuleUIProvider : ModuleUIProvider {

    class ViewHolder(view: View) : CustomEditorViewHolder(view) {
        val container: LinearLayout = view as LinearLayout
        var params: MutableList<FunctionParam> = mutableListOf()
        var textWatchers: MutableList<android.text.TextWatcher> = mutableListOf()
    }

    private val gson = Gson()
    private val paramsType = object : TypeToken<List<FunctionParam>>() {}.type

    override fun getHandledInputIds(): Set<String> = setOf("functionParams")

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(16, 8, 16, 8)
        }
        val holder = ViewHolder(container)
        loadParams(holder, currentParameters["functionParams"])

        fun render() {
            container.removeAllViews()
            // 标题
            container.addView(sectionLabel(context, "函数参数"))
            // 参数列表
            holder.params.forEachIndexed { index, p ->
                container.addView(createParamRow(context, p, index, holder, onParametersChanged))
            }
            // 添加按钮
            val addBtn = TextView(context).apply {
                text = "+ 添加参数"
                setTextColor(context.getColor(android.R.color.holo_blue_dark))
                setPadding(0, 16, 0, 0)
                setOnClickListener {
                    holder.params += FunctionParam(name = "", type = VTypeRegistry.STRING.id, isRequired = false)
                    render()
                    onParametersChanged()
                }
            }
            container.addView(addBtn)
        }

        render()
        return holder
    }

    private fun createParamRow(
        context: Context,
        param: FunctionParam,
        index: Int,
        holder: ViewHolder,
        onParametersChanged: () -> Unit
    ): View {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 8, 0, 8)
        }

        val nameInput = EditText(context).apply {
            hint = "参数名"
            setText(param.name)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            // 文本变化时更新内存中的参数，并用 textWatcher 防重复
            val watcher = object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: android.text.Editable?) {
                    if (index < holder.params.size) {
                        holder.params[index] = holder.params[index].copy(name = s?.toString() ?: "")
                        onParametersChanged()
                    }
                }
            }
            addTextChangedListener(watcher)
            holder.textWatchers += watcher
        }
        row.addView(nameInput)

        // 删除按钮
        val delBtn = TextView(context).apply {
            text = "✕"
            setPadding(12, 0, 0, 0)
            setOnClickListener {
                if (index < holder.params.size) {
                    holder.params.removeAt(index)
                    (parent as? ViewGroup)?.removeView(row)
                    onParametersChanged()
                }
            }
        }
        row.addView(delBtn)

        return row
    }

    private fun sectionLabel(context: Context, text: String): TextView {
        return TextView(context).apply {
            this.text = text
            textSize = 14f
        }
    }

    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as ViewHolder
        // 清理掉空参数名
        val cleaned = h.params.filter { it.name.isNotBlank() }
        return mapOf("functionParams" to gson.toJson(cleaned))
    }

    override fun createPreview(
        context: Context, parent: ViewGroup, step: ActionStep, allSteps: List<ActionStep>,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): View? = null

    private fun loadParams(holder: ViewHolder, raw: Any?) {
        val json = raw as? String ?: return
        holder.params = try {
            gson.fromJson<List<FunctionParam>>(json, paramsType).toMutableList()
        } catch (e: Exception) {
            mutableListOf()
        }
    }
}
