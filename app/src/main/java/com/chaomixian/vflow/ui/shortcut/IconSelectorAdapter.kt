package com.chaomixian.vflow.ui.shortcut

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.chaomixian.vflow.R
import com.google.android.material.color.MaterialColors

/**
 * 图标选择器适配器
 */
class IconSelectorAdapter(
    private val onIconSelected: (String?) -> Unit,
    private val onCustomImageSelected: () -> Unit
) : RecyclerView.Adapter<IconSelectorAdapter.IconViewHolder>() {

    companion object {
        private const val TYPE_ICON = 0
        private const val TYPE_CUSTOM_IMAGE = 1
    }

    // 可用的图标资源列表（资源名称）
    private val availableIcons = listOf(
        "ic_shortcut_play",
        "rounded_play_arrow_24",
        "rounded_activity_zone_24",
        "rounded_ads_click_24",
        "rounded_battery_android_frame_full_24",
        "rounded_bluetooth_24",
        "rounded_brightness_5_24",
        "rounded_calculate_24",
        "rounded_call_to_action_24",
        "rounded_check_circle_24",
        "rounded_content_copy_24",
        "rounded_content_paste_24",
        "rounded_convert_to_text_24",
        "rounded_dashboard_2_edit_24",
        "rounded_dataset_24",
        "rounded_download_24",
        "rounded_earbuds_24",
        "rounded_fullscreen_portrait_24",
        "rounded_hexagon_nodes_24",
        "rounded_image_search_24",
        "rounded_keyboard_24",
        "rounded_logout_24",
        "rounded_output_24",
        "rounded_pause_24",
        "rounded_photo_24",
        "rounded_preview_24",
        "rounded_public_24",
        "rounded_save_24",
        "rounded_search_24",
        "rounded_settings_24",
        "rounded_skip_next_24",
        "rounded_sms_24",
        "rounded_stop_circle_24",
        "rounded_swap_calls_24",
        "rounded_terminal_24",
        "rounded_turn_slight_right_24",
        "rounded_wifi_tethering_24",
    )

    private var selectedIconRes: String? = null
    private var isCustomImage = false
    private var customImageBitmap: android.graphics.Bitmap? = null

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): IconViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_icon_selector, parent, false)
        return IconViewHolder(view, viewType)
    }

    override fun onBindViewHolder(holder: IconViewHolder, position: Int) {
        if (position == availableIcons.size) {
            // 自定义图片选项
            holder.bindCustomImage(isCustomImage, customImageBitmap)
        } else {
            // 普通图标选项
            val iconRes = availableIcons[position]
            holder.bind(iconRes, iconRes == selectedIconRes && !isCustomImage)
        }
    }

    override fun getItemCount(): Int = availableIcons.size + 1 // +1 for custom image option

    override fun getItemViewType(position: Int): Int {
        return if (position == availableIcons.size) TYPE_CUSTOM_IMAGE else TYPE_ICON
    }

    fun setSelectedIcon(iconRes: String?) {
        val previousSelected = selectedIconRes
        selectedIconRes = iconRes
        isCustomImage = false
        customImageBitmap = null

        // 更新之前选中项的状态
        previousSelected?.let {
            val previousPosition = availableIcons.indexOf(it)
            if (previousPosition >= 0) {
                notifyItemChanged(previousPosition)
            }
        }

        // 更新当前选中项的状态
        selectedIconRes?.let {
            val currentPosition = availableIcons.indexOf(it)
            if (currentPosition >= 0) {
                notifyItemChanged(currentPosition)
            }
        }

        // 更新自定义图片选项
        notifyItemChanged(availableIcons.size)

        onIconSelected(iconRes)
    }

    fun setCustomImage(imagePath: String) {
        selectedIconRes = imagePath
        isCustomImage = true

        // 加载自定义图片的缩略图用于显示
        try {
            val path = if (imagePath.startsWith("file://")) {
                imagePath.substring(7)
            } else {
                imagePath
            }
            val file = java.io.File(path)
            if (file.exists()) {
                // 加载缩小的图片作为预览
                val options = android.graphics.BitmapFactory.Options().apply {
                    inSampleSize = 4 // 缩小图片以减少内存使用
                }
                customImageBitmap = android.graphics.BitmapFactory.decodeFile(file.path, options)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            customImageBitmap = null
        }

        // 清除之前选中的图标
        val previousPosition = availableIcons.indexOf(selectedIconRes?.takeIf { !isCustomImage })
        if (previousPosition >= 0) {
            notifyItemChanged(previousPosition)
        }

        // 更新自定义图片选项
        notifyItemChanged(availableIcons.size)

        onIconSelected(imagePath)
    }

    inner class IconViewHolder(itemView: View, private val viewType: Int) : RecyclerView.ViewHolder(itemView) {
        // ⚠️ fork 2026-10-05：`item_icon_selector.xml` 的根布局已从 MaterialCardView
        //    改为 LinearLayout（圆角方块 → 圆形底，CardView 的 cardCornerRadius 做不出正圆）。
        //    原来的 `itemView as MaterialCardView` 会 **ClassCastException** —— 而且只在
        //    打开快捷方式配置页时才炸，图标选择页（另一个 Adapter）看着一切正常。
        private val iconContainer: View = itemView.findViewById(R.id.icon_container)
        private val imageView: ImageView = itemView.findViewById(R.id.image_view_icon)
        // ⚠️ 本选择器**不显示图标名**（它的候选是快捷方式用的通用图标，没有
        //    "官方名字"这一说，显示 `play arrow` 之类的英文反而更费解）。
        // ⚠️ 必须写显式类型参数 `findViewById<TextView>(...)`：Kotlin 从 `.apply {}`
        //    里推不出 T（`apply` 的返回类型是 T 自身，而 T 没有其他约束来源）。
        private val textName: TextView =
            itemView.findViewById<TextView>(R.id.text_icon_name).apply {
                visibility = View.GONE
            }

        fun bind(iconRes: String, isSelected: Boolean) {
            // 获取资源 ID
            val resId = itemView.context.resources.getIdentifier(
                iconRes,
                "drawable",
                itemView.context.packageName
            )

            if (resId != 0) {
                imageView.setImageResource(resId)
            }

            // 更新选中状态
            updateSelectionStyle(isSelected)

            iconContainer.setOnClickListener {
                setSelectedIcon(iconRes)
            }
        }

        fun bindCustomImage(isSelected: Boolean, customBitmap: android.graphics.Bitmap? = null) {
            // 如果有自定义图片且被选中，显示自定义图片；否则显示相册图标
            if (isSelected && customBitmap != null) {
                imageView.setImageBitmap(customBitmap)
                // 清除 tint 以显示原始图片
                imageView.imageTintList = null
            } else {
                imageView.setImageResource(R.drawable.rounded_add_photo_alternate_24)
                // 恢复 tint
                imageView.imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(
                        itemView.context,
                        com.google.android.material.R.attr.colorOnSurface,
                        0
                    )
                )
            }

            // 更新选中状态
            updateSelectionStyle(isSelected)

            // 为相册图标使用不同的背景色，使其与普通图标区分
            // ⚠️ 仍然走**背景资源**切换（不能用 setBackgroundColor：会把圆形覆盖成方）。
            //    相册格用选中态的圆形底（主色描边）作区分。
            if (!isSelected) {
                iconContainer.setBackgroundResource(R.drawable.bg_icon_grid_circle_selected)

                // 同时设置图标的颜色为 OnSecondaryContainer
                val iconColor = MaterialColors.getColor(
                    itemView.context,
                    com.google.android.material.R.attr.colorOnSecondaryContainer,
                    0
                )
                imageView.imageTintList = android.content.res.ColorStateList.valueOf(iconColor)
            }

            iconContainer.setOnClickListener {
                onCustomImageSelected()
            }
        }

        /**
         * 选中态切换**背景资源**。
         *
         * ⚠️ 原来分两步（`cardView.strokeColor` + `iconContainer.setBackgroundColor`），
         *    现在圆形底与描边都画在 shape drawable 里 ⇒ **必须整体换资源**。
         *    单独 `setBackgroundColor` 会把圆形底覆盖成方形色块（不报错，只是变方）。
         */
        private fun updateSelectionStyle(isSelected: Boolean) {
            iconContainer.setBackgroundResource(
                if (isSelected) R.drawable.bg_icon_grid_circle_selected
                else R.drawable.bg_icon_grid_circle
            )
        }
    }
}
