// 文件: main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportUIProvider.kt
// 描述: 为 BackupExportModule 的「导出范围」参数提供**多选**编辑器（fork 新增）。
package com.chaomixian.vflow.core.workflow.module.data

import android.content.Context
import android.content.Intent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.isVisible
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.settings.backupScopeLabelRes
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup

class BackupExportViewHolder(
    view: View,
    val chipGroup: ChipGroup,
    val emptyHint: TextView,
) : CustomEditorViewHolder(view)

/**
 * 「导出范围」的多选编辑器。
 *
 * ## ⚠️ 为什么必须自定义 UIProvider，而不是用声明式控件
 *
 * `InputDefinition` 的 `ParameterType` 只有 `STRING`/`NUMBER`/`BOOLEAN`/`ENUM`/`ANY`
 * 五种，**没有多选类型**；而 `InputStyle.CHIP_GROUP` 在 `StandardControlFactory` 里
 * 被硬编码为 `isSingleSelection = true`（单选）。所以多选只能走自定义 editor。
 * 范式照 `AppStartTriggerUIProvider`（`ParameterType.ANY` + `readFromEditor`
 * 返回 `Map<String, Any?>`）。
 *
 * ## ⚠️ 勾选项只能来自 `BackupScopeRegistry.all()`
 *
 * 这里是静默失效点 13 的第二个落点：硬编码第二份清单 ⇒ 将来新增的 scope
 * **永远不会出现在编辑器里**，而且没有任何报错（用户只会觉得「怎么没有 xx 的选项」）。
 * 由 `BackupRestoreWiringTest` 的源码扫描机器化锁住。
 *
 * ## ⚠️ 不碰 `appContext`
 *
 * `BaseModule.appContext` 是 `lateinit`，只在 `ModuleRegistry.register(module, context)`
 * 之后才可用。UIProvider 的 `context` 参数是编辑器给的 Activity Context —— 拿它即可，
 * 绝不在 `getInputs()` / 本类里触碰 `appContext`（未注册时调用会抛
 * `UninitializedPropertyAccessException`，整屏模块列表崩掉）。
 */
class BackupExportUIProvider : ModuleUIProvider {

    override fun getHandledInputIds(): Set<String> = setOf(PARAM_SCOPES)

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (Int, Intent?) -> Unit) -> Unit)?,
    ): CustomEditorViewHolder {
        val view = LayoutInflater.from(context)
            .inflate(R.layout.partial_backup_export_editor, parent, false)
        val chipGroup = view.findViewById<ChipGroup>(R.id.cg_backup_scopes)
        val emptyHint = view.findViewById<TextView>(R.id.text_backup_scopes_empty)

        // ⚠️ 勾选项的**唯一来源**。任何 listOf("workflows", ...) 字面量都不得出现在本文件里。
        val allScopes = BackupScopeRegistry.all()
        val selected = readSelectedIds(currentParameters[PARAM_SCOPES])
            // 没存过参数（新建步骤）⇒ 用 registry 的默认勾选。
            .ifEmpty { allScopes.filter { it.defaultIncluded }.map { it.id } }
            .toSet()

        emptyHint.isVisible = allScopes.isEmpty()
        chipGroup.removeAllViews()
        allScopes.forEach { scope ->
            val label = context.getString(backupScopeLabelRes(scope.id))
            val chip = Chip(chipGroup.context).apply {
                text = label
                isCheckable = true
                isChecked = scope.id in selected
                // ⚠️ 用 chip 自身的选中态表达勾选，**不**用 isCloseIconVisible
                //    （那是「已选中的条目」的视觉，与本场景的语义不同，
                //    且会让用户以为删除键能移除范围定义）。
                setOnCheckedChangeListener { _, _ -> onParametersChanged() }
            }
            chipGroup.addView(chip)
        }

        return BackupExportViewHolder(view, chipGroup, emptyHint)
    }

    /**
     * ⚠️ **从 chip 的文本反查 id 是不行的**（文本被本地化过）。这里按
     * 勾选顺序与 `BackupScopeRegistry.all()` 的**下标**对齐 —— 建 chip 时
     * 的顺序就是 `all()` 的顺序，且中途不会重排。
     */
    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> {
        val h = holder as BackupExportViewHolder
        val allScopes = BackupScopeRegistry.all()
        val ids = mutableListOf<String>()
        // 注意：chipGroup 里可能有 ChipGroup 自身的其它子 View 类型，
        // 但本布局里只放 Chip ⇒ 按 childCount 与 allScopes 对齐是安全的。
        for (index in 0 until minOf(h.chipGroup.childCount, allScopes.size)) {
            val chip = h.chipGroup.getChildAt(index) as? Chip ?: continue
            if (chip.isChecked) ids += allScopes[index].id
        }
        return mapOf(PARAM_SCOPES to ids)
    }

    /** 供 `readFromEditor` 之外的地方解析「已存参数」用（与模块侧口径一致）。 */
    private fun readSelectedIds(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.filterIsInstance<String>()
        is String -> raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }
}
