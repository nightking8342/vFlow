package com.chaomixian.vflow.ui.workflow_editor

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.WorkflowIconValue
import com.chaomixian.vflow.core.workflow.WorkflowVisuals
import com.google.android.material.color.MaterialColors

/**
 * 卡片图标选择器的适配器。
 *
 * ## ⚠️ 2026-10-05 起数据源变了：从 18 个注册表项 → **Material Symbols 全量 8310 个**
 *
 * 因此几处与「列表很短」绑定的实现都被改掉了，理由记在下面，**不要再退回去**：
 *
 * 1. **名字 → 资源 id 走 [WorkflowVisuals.resolveIconDrawableResOrZero]**。
 *    8310 个图标不可能逐个写进 `R.drawable.`，必须走 `getIdentifier`。
 *    ⚠️ 它的返回值**可能是 0**（老工作流存了已下线的图标名），
 *    交给 `setImageResource(0)` 会抛 `Resources.NotFoundException`。
 * 2. **选中态用 `indexOfFirst` 而不是 `indexOf`** —— 后者对 `String` 也能用，
 *    但**区分不出"没找到"与"首项"**（都返回 -1 / 0 附近的语义），而 8310 项里
 *    "当前选中的不在过滤后的列表里"是**常态**（用户搜了别的词）。
 * 3. **列表必须能滚动**（页面布局里是 `match_parent` + 权重）。
 * 4. **每项显示官方名字**（[WorkflowIconValue.officialLabelOf]）——
 *    8310 个图标里形状相近的很多，不显示名字只能靠"看着像"猜。
 */
class WorkflowIconPickerAdapter(
    private val onIconSelected: (String) -> Unit
) : RecyclerView.Adapter<WorkflowIconPickerAdapter.IconViewHolder>() {

    /** 全量候选（线框 + 填充交替），作为 [submitList] 的初值。 */
    private var icons: List<String> = WorkflowVisuals.iconPickerCandidates()

    private var selectedIconRes: String? = WorkflowVisuals.defaultIconResName()

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_icon_selector, parent, false)
        return IconViewHolder(view)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        holder.bind(icons[position], icons[position] == selectedIconRes)
    }

    override fun getItemCount(): Int = icons.size

    /**
     * 换一份候选列表（搜索过滤 / 切换分类 / 清空搜索）。
     *
     * ⚠️ 用 `notifyDataSetChanged` 而不是 diff：图标项没有稳定 id（过滤后位置全变），
     *    diff 的比对成本反而更高 —— 8310 项的 diff 每次都要建两遍索引。
     * ⚠️ 选中项若不在新列表里（被搜索/分类过滤掉了），**保留 `selectedIconRes`**：
     *    它是"用户已选的图标"，与"当前屏幕上有没有它"是两件事。
     */
    fun submitList(newIcons: List<String>) {
        icons = newIcons
        notifyDataSetChanged()
    }

    fun setSelectedIcon(iconRes: String?) {
        val normalized = WorkflowVisuals.normalizeIconResName(iconRes)
        val oldIndex = icons.indexOfFirst { it == selectedIconRes }
        selectedIconRes = normalized
        val newIndex = icons.indexOfFirst { it == normalized }
        if (oldIndex >= 0) notifyItemChanged(oldIndex)
        if (newIndex >= 0) notifyItemChanged(newIndex)
    }

    inner class IconViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconContainer: View = itemView.findViewById(R.id.icon_container)
        private val imageView: ImageView = itemView.findViewById(R.id.image_view_icon)
        private val textName: TextView = itemView.findViewById(R.id.text_icon_name)

        fun bind(iconRes: String, isSelected: Boolean) {
            // ⚠️ 走全量解析（含 getIdentifier）；拿不到就**保持原图**而不是设 0 ——
            //    8310 个候选里不该出现这种情况，但老工作流可能存着已下线的名字，
            //    那时宁可显示上一次的图，也不能崩。
            val resolvedId = WorkflowVisuals.resolveIconDrawableResOrZero(itemView.context, iconRes)
            if (resolvedId != 0) {
                imageView.setImageResource(resolvedId)
            }

            // 官方名字（`rounded_play_arrow_24` → `play arrow`）。
            // ⚠️ 填充版带 ` · fill` 后缀，理由见 `WorkflowIconValue.displayLabelOf`。
            textName.text = WorkflowIconValue.displayLabelOf(iconRes)

            updateSelectionStyle(isSelected)

            iconContainer.setOnClickListener {
                val previous = selectedIconRes
                selectedIconRes = iconRes
                icons.indexOfFirst { it == previous }.takeIf { it >= 0 }?.let(::notifyItemChanged)
                notifyItemChanged(bindingAdapterPosition)
                onIconSelected(iconRes)
            }
        }

        /**
         * 选中态切换**背景资源**（[R.drawable.bg_icon_grid_circle] /
         * [R.drawable.bg_icon_grid_circle_selected]）并同步图标着色。
         *
         * ⚠️ 与改动前的差别：原来是 `MaterialCardView.strokeColor` +
         * `iconContainer.setBackgroundColor(...)`（两处分开设）。现在圆形的实色底
         * 与描边都在 shape drawable 里（`shape="oval"` 做不出「底色 + 描边」以外的
         * 组合），所以**必须整体换资源**，不能再单独 `setBackgroundColor`
         * —— 那会把圆形底覆盖成方形色块（且不报错，只是变成方的）。
         */
        private fun updateSelectionStyle(isSelected: Boolean) {
            val context = itemView.context
            iconContainer.setBackgroundResource(
                if (isSelected) R.drawable.bg_icon_grid_circle_selected
                else R.drawable.bg_icon_grid_circle
            )
            imageView.imageTintList = ColorStateList.valueOf(
                MaterialColors.getColor(
                    context,
                    if (isSelected) com.google.android.material.R.attr.colorOnPrimaryContainer
                    else com.google.android.material.R.attr.colorOnSurface,
                    0
                )
            )
        }
    }
}
