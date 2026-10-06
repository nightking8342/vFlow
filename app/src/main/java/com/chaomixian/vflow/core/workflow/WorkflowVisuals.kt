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

    /**
     * 图标选择器的候选项：**Material Symbols 全量**（4150 个基础名 × 线框/填充）。
     *
     * ⚠️⚠️ **这里刻意不再返回 `iconRegistry.keys`**（原先只有 18 个）。
     *
     * 原实现的解析路径是 `iconRegistry[名字] ?: 默认图标`，而**非注册名字会被
     * 静默回落到默认图标** —— 这在家目录只有 18 个可选图标时不是问题，但图标库
     * 扩到 4150 个之后，它会把「用户选了 A、显示成 B」变成一个**不报错的日常事件**。
     * 更早还有一起同类事故：`IconSelectorAdapter` 用字符串名经 `getIdentifier`
     * 查找，R8 的 `shrinkResources` 看不见这种引用，把图标剥掉后
     * 「点了没反应」（见 `res/raw/keep.xml`）。
     *
     * ⇒ 现在两件事分开：
     * - **选择器**用 [MaterialSymbolNames.ALL]（全量名字，`getIdentifier` 能查到）；
     * - **[resolveIconDrawableRes] 的回退**保留（老工作流里存着已下线的图标名时，
     *   宁可显示默认图标，也不能崩 —— 有测试锁住这条回退）。
     *
     * ⚠️ `iconRegistry` 本身仍被 [resolveIconDrawableRes] 用作**快速路径**，
     *    不要因为它不在选择器里了就当死代码删掉。
     */
    val availableIconResNames: List<String> = MaterialSymbolNames.ALL.map { "rounded_${it}_24" }

    /** 填充风格的候选项（与 [availableIconResNames] 一一对应）。 */
    val availableFilledIconResNames: List<String> = MaterialSymbolNames.ALL.map { "rounded_${it}_fill_24" }

    /**
     * 图标选择器的候选列表：**线框 + 填充**，跨风格交替排列。
     *
     * ⚠️ 交替（而不是「先 4150 个线框、再 4150 个填充」）是**用户可发现性的要求**：
     *    顺序排列的话，想找「填充版的 home」得先滚过 4150 项；交替后同一个图标的
     *    两种风格相邻，用户能直接看到「哦，这个图标还有填充版」。
     *    代价是搜索时需要自己判断要不要两种都留（见 `IconSearchFilter`）。
     */
    fun iconPickerCandidates(): List<String> {
        val line = availableIconResNames
        val fill = availableFilledIconResNames
        return List(line.size * 2) { i ->
            if (i % 2 == 0) line[i / 2] else fill[i / 2]
        }
    }

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

    /**
     * 规范化卡片图标字段：非空原样保留，空则回默认。
     *
     * ⚠️ **自定义图片路径（`/data/...` / `file://...`）走的就是「非空原样保留」
     * 这条分支** —— 本函数**不需要**也知道路径的存在。形态判定在消费端做
     * （[WorkflowIconValue] + `WorkflowCardIcon`），因为"是不是图片"只影响**怎么渲染**，
     * 不影响**怎么存**。
     *
     * ⚠️ 曾一度想在这里加 `isCustomImage` 分支，实测**两个版本行为完全一致**
     * （都是返回同一个非空字符串），属于"看着更严谨、实则无据"的改动，已撤掉。
     * 若将来真要在这里做点什么（比如路径失效时回退默认图标），先想清楚：
     * 本函数在**写入路径**上（save / load / 导入），而"文件还在不在"是**渲染时**
     * 才知道的事 —— 在写入路径上判定文件存在性会把"导入到另一台设备"
     * 变成"图标丢失"。
     */
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

    /**
     * 按**资源名**解析图标，失败返回 0（调用方决定怎么回退）。
     *
     * ⚠️ 两条路径缺一不可：
     * - `iconRegistry` 是**快速路径**（`R.drawable.` 静态引用，无反射开销）；
     * - `getIdentifier` 是**全量路径**（覆盖 8310 个 Material Symbols，
     *   它们不可能逐个写进 registry）。
     *
     * ⚠️⚠️ **返回值 0 必须被调用方处理**。`getIdentifier` 找不到时返回 0，
     * 直接把 0 交给 `setImageResource` / `painterResource` 会抛
     * `Resources.NotFoundException` —— 而这条路径上的输入是**用户数据**
     * （老工作流里存着的图标名、从别处导入的工作流），不是常量。
     */
    fun resolveIconDrawableResOrZero(context: android.content.Context, iconResName: String?): Int {
        val normalized = normalizeIconResName(iconResName)
        iconRegistry[normalized]?.let { return it }
        return context.resources.getIdentifier(normalized, "drawable", context.packageName)
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
