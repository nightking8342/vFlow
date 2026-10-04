package com.chaomixian.vflow.core.workflow

import android.content.Context
import android.graphics.Color
import androidx.core.graphics.ColorUtils
import com.chaomixian.vflow.R
import com.google.android.material.color.MaterialColors
import kotlin.random.Random

object WorkflowVisuals {
    const val DEFAULT_ICON_RES_NAME = "rounded_layers_fill_24"
    const val DEFAULT_THEME_COLOR_HEX = "#5B8CFF"

    private val iconRegistry = linkedMapOf(
        DEFAULT_ICON_RES_NAME to R.drawable.rounded_layers_fill_24,
        "rounded_auto_awesome_motion_24" to R.drawable.rounded_auto_awesome_motion_24,
        "rounded_smart_toy_24" to R.drawable.rounded_smart_toy_24,
        "rounded_terminal_24" to R.drawable.rounded_terminal_24,
        "rounded_cloud_24" to R.drawable.rounded_cloud_24,
        "rounded_dataset_24" to R.drawable.rounded_dataset_24,
        "rounded_dashboard_fill_24" to R.drawable.rounded_dashboard_fill_24,
        "rounded_feature_search_24" to R.drawable.rounded_feature_search_24,
        "rounded_notifications_unread_24" to R.drawable.rounded_notifications_unread_24,
        "rounded_public_24" to R.drawable.rounded_public_24,
        "rounded_settings_fill_24" to R.drawable.rounded_settings_fill_24,
        "rounded_wifi_tethering_24" to R.drawable.rounded_wifi_tethering_24,
        "rounded_bluetooth_24" to R.drawable.rounded_bluetooth_24,
        "rounded_call_to_action_24" to R.drawable.rounded_call_to_action_24,
        "rounded_photo_24" to R.drawable.rounded_photo_24,
        "rounded_search_24" to R.drawable.rounded_search_24,
        "rounded_save_24" to R.drawable.rounded_save_24,
        "rounded_sdk_fill_24" to R.drawable.rounded_sdk_fill_24
    )

    val availableIconResNames = iconRegistry.keys.toList()

    val themePaletteHex = listOf(
        "#5B8CFF",
        "#6D5EF4",
        "#8B5CF6",
        "#D946EF",
        "#EC4899",
        "#F43F5E",
        "#F97316",
        "#F59E0B",
        "#EAB308",
        "#84CC16",
        "#22C55E",
        "#10B981",
        "#14B8A6",
        "#06B6D4",
        "#0EA5E9"
    )

    /**
     * 卡片配色。
     *
     * ⚠️ [cardBackground] 与 [cardBackgroundEnd] 是**同一张卡上下两端**的颜色，
     * 供瀑布流卡片画竖直渐变（参考 ShortX：卡片不是纯色，顶部带一点主题色、
     * 越往下越贴近 surface）。列表模式的卡片仍只用 [cardBackground]（单色）——
     * 那是既有行为，本次未动。
     */
    data class CardColors(
        val cardBackground: Int,
        val iconBackground: Int,
        val iconTint: Int,
        val accentBackground: Int,
        val chipBackground: Int,
        /** 渐变的底端颜色。默认等于 [cardBackground]（即不渐变），保证旧调用点行为不变。 */
        val cardBackgroundEnd: Int = cardBackground,
    )

    fun defaultIconResName(): String = DEFAULT_ICON_RES_NAME

    fun defaultThemeColorHex(): String = DEFAULT_THEME_COLOR_HEX

    fun randomThemeColorHex(): String {
        return themePaletteHex[Random.nextInt(themePaletteHex.size)]
    }

    fun normalizeIconResName(iconResName: String?): String {
        val normalized = iconResName?.trim().orEmpty()
        return if (normalized.isNotEmpty()) normalized else DEFAULT_ICON_RES_NAME
    }

    fun normalizeThemeColorHex(colorHex: String?): String {
        val normalized = colorHex?.trim()?.uppercase().orEmpty()
        if (normalized.matches(Regex("^#[0-9A-F]{6}$"))) {
            return normalized
        }
        return DEFAULT_THEME_COLOR_HEX
    }

    fun resolveIconDrawableRes(iconResName: String?): Int {
        val normalized = normalizeIconResName(iconResName)
        return iconRegistry[normalized] ?: iconRegistry.getValue(DEFAULT_ICON_RES_NAME)
    }

    fun resolveCardColors(context: Context, colorHex: String?): CardColors {
        val baseColor = Color.parseColor(normalizeThemeColorHex(colorHex))
        val surface = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurface,
            Color.WHITE
        )
        val surfaceContainerHighest = MaterialColors.getColor(
            context,
            com.google.android.material.R.attr.colorSurfaceContainerHighest,
            surface
        )
        val cardBackground = ColorUtils.blendARGB(surface, baseColor, 0.18f)
        // 渐变底端：比顶端更贴近 surface（即更「淡出」），使卡片有自上而下的层次。
        // 0.18 → 0.06 的跨度是照 ShortX 截图目测调的：再大就会在深色主题下
        // 变成明显色块，再小则完全看不出渐变。
        val cardBackgroundEnd = ColorUtils.blendARGB(surface, baseColor, 0.06f)
        val iconBackground = ColorUtils.blendARGB(surface, baseColor, 0.82f)
        val accentBackground = ColorUtils.blendARGB(surface, baseColor, 0.72f)
        val chipBackground = ColorUtils.blendARGB(surfaceContainerHighest, baseColor, 0.30f)
        val iconTint = if (ColorUtils.calculateLuminance(iconBackground) > 0.46) {
            Color.parseColor("#111827")
        } else {
            Color.WHITE
        }
        return CardColors(
            cardBackground = cardBackground,
            iconBackground = iconBackground,
            iconTint = iconTint,
            accentBackground = accentBackground,
            chipBackground = chipBackground,
            cardBackgroundEnd = cardBackgroundEnd,
        )
    }
}
