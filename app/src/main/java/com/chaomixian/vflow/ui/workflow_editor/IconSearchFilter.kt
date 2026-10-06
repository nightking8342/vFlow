package com.chaomixian.vflow.ui.workflow_editor

/**
 * 图标选择器的搜索过滤（**纯函数，可纯 JVM 单测**）。
 *
 * ## 为什么单独成文件
 *
 * 图标库扩到 8310 项后，搜索是这个选择器**可用性的前提**（不带搜索的 4150 项
 * 列表等于不可用）。而"匹配规则"是最容易写错、又最不容易被发现的地方：
 * 写错了不会崩，只会「搜 `play arrow` 搜不到 `rounded_play_arrow_24`」——
 * 用户会以为图标库里没有这个图标。放在这里是为了能按下表逐条测。
 *
 * ## 匹配规则（三条，都是必需的）
 *
 * 1. **去前缀去后缀**：搜索对象是 `rounded_<名字>_24` / `rounded_<名字>_fill_24`，
 *    而用户输入的是「名字」。若把 `rounded_` 也算进匹配范围，用户搜 `round`
 *    会得到全部 8310 项（等于没过滤）。
 * 2. **`-` 与 `_` 等价**：Material Symbols 的官方名字用连字符
 *    （`play-arrow` / `arrow-back-ios-new`），本仓库的资源名用下划线
 *    （资源名不允许连字符）。用户在 fonts.google.com 上看到的是连字符版本，
 *    会照着敲 —— 不做等价转换的话，**从官网上抄来的名字搜不到**。
 * 3. **忽略大小写**：用户会敲 `WIFI`。
 *
 * ⚠️ **不做的**：不做模糊匹配/编辑距离。「`wif` 能不能命中 `wifi`」用前缀匹配
 * 就够了；引入编辑距离会让「搜 `home` 出现 `chrome_reader_mode`」这类意外命中，
 * 而用户对"为什么这个出来了"没有解释能力。
 */
object IconSearchFilter {

    /** 资源名前后缀（与 `MaterialSymbolNames` 生成的资源名一致）。 */
    private const val PREFIX = "rounded_"
    private const val LINE_SUFFIX = "_24"
    private const val FILL_SUFFIX = "_fill_24"

    /**
     * 把资源名还原成 Material Symbols 的官方名字（`rounded_play_arrow_24`
     * → `play arrow`，注意连字符换成空格后便于分词匹配）。
     *
     * ⚠️ 后端**先**去 `_fill_24` 再去 `_24`，顺序反了会把 `xxx_fill` 当成图标名。
     */
    fun officialNameOf(iconResName: String): String {
        var name = iconResName.removePrefix(PREFIX)
        name = when {
            name.endsWith(FILL_SUFFIX) -> name.removeSuffix(FILL_SUFFIX)
            name.endsWith(LINE_SUFFIX) -> name.removeSuffix(LINE_SUFFIX)
            else -> name
        }
        return name.replace('_', ' ')
    }

    /**
     * 某个候选是否命中查询词。
     *
     * ⚠️ 空查询**返回 true**（不过滤）—— 这与「空查询应该什么都不显示」是相反
     * 的两种设计。选"显示全部"是因为清空搜索框时用户期望看到完整列表，
     * 而不是空白。
     */
    fun matches(iconResName: String, query: String): Boolean {
        val normalizedQuery = normalizeQuery(query) ?: return true
        val haystack = officialNameOf(iconResName).lowercase()
        // 前缀匹配优先（`wif` → `wifi`），同时允许词首匹配（`arrow back` → `arrow back ios new`）
        if (haystack.startsWith(normalizedQuery)) return true
        return haystack.split(' ').any { it.startsWith(normalizedQuery) }
    }

    /**
     * 过滤候选列表。
     *
     * ⚠️ 返回**同一份列表的引用**（空查询时）而不是 `filter` 出的新列表：
     *    8310 项的 filter 每次都新建一个 8310 元素的 list，而清空搜索框是
     *    高频动作（用户反复试词）。调用方拿到后直接交给 adapter，没有别名风险
     *    —— 候选列表本身是不可变的。
     */
    fun filter(icons: List<String>, query: String): List<String> {
        val normalizedQuery = normalizeQuery(query) ?: return icons
        return icons.filter { matches(it, normalizedQuery) }
    }

    /** 规范化查询词；**全空白返回 null**（= 不过滤）。 */
    private fun normalizeQuery(query: String): String? {
        val trimmed = query.trim().lowercase()
        if (trimmed.isEmpty()) return null
        // 连字符等价于空格（官方名字用连字符，资源名用下划线，我们的候选是空格分隔）
        return trimmed.replace('-', ' ')
    }
}
