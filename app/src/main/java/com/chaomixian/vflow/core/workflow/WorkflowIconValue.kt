package com.chaomixian.vflow.core.workflow

/**
 * 卡片图标字段（`Workflow.cardIconRes`）的**取值判定**（纯函数，可纯 JVM 单测）。
 *
 * ## 字段里现在能存三种东西
 *
 * | 形态 | 例子 | 来源 |
 * |---|---|---|
 * | 内置图标资源名 | `rounded_home_24` | Material Symbols 图标库（4150 个）|
 * | 自定义图片**绝对路径** | `/data/user/0/.../files/card_icons/xxx.png` | 用户从相册选 |
 * | 自定义图片 `file://` URI | `file:///data/.../xxx.png` | 老数据 / 导入的工作流 |
 *
 * ⚠️ **刻意复用 `shortcutIconRes` 的形态约定**（绝对路径或 `file://`），
 *    而不是新造一个前缀（如 `custom:`）：
 * - `ShortcutHelper` 已经有一份「绝对路径 / `file://` → 解码 Bitmap」的实现，
 *   新造格式等于让它再写第二套判定；
 * - 存量工作流导出的 JSON 里 `cardIconRes` 一直是资源名，新格式仍需兼容它，
 *   两种格式并存时"哪个前缀是谁"只会更难判。
 *
 * ⚠️ **判定必须放在这里而不是散在各个消费点**：卡片图标有**五个**消费点
 * （列表卡片 ×2、编辑器预览、选择器、以及模型层的 normalize）。
 * 各写各的判定，漏掉一处的表现是「列表上显示图片、编辑器预览里显示默认图标」——
 * 而更糟的是 `normalizeIconResName` 会把**路径**当成一个"非空名字"原样返回，
 * 那条路径会一路走到 `getIdentifier`（必然返回 0）。
 */
object WorkflowIconValue {

    /** 自定义图片在应用私有目录下的子目录名（与快捷方式的分开，便于单独清理）。 */
    const val CUSTOM_ICON_DIR = "card_icons"

    /** `file://` 前缀。 */
    private const val FILE_SCHEME = "file://"

    /**
     * 是否为**自定义图片**（绝对路径或 `file://`）。
     *
     * ⚠️ 判定顺序：先看 `file://`，再看「以 `/` 开头」。**不能只看后者** ——
     * `file://` 的字符串不以 `/` 开头（它以 `f` 开头），漏判会让这类值
     * 落到资源名路径上，`getIdentifier("file:///...")` 必然返回 0。
     */
    fun isCustomImage(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.startsWith(FILE_SCHEME) || v.startsWith("/")
    }

    /**
     * 把字段值转成**可直接交给文件 API 的路径**；不是自定义图片时返回 `null`。
     *
     * ⚠️ 剥 `file://` 用 `removePrefix` 而不是 `substring(7)` —— 后者在
     * 「不是 file:// 但以 / 开头」的分支上会**切掉路径开头 7 个字符**
     * （`/data/user/...` 变成 `r/0/...`），且不报错，只是文件找不到。
     */
    fun filePathOf(value: String?): String? {
        val v = value?.trim().orEmpty()
        if (v.isEmpty()) return null
        return when {
            v.startsWith(FILE_SCHEME) -> v.removePrefix(FILE_SCHEME)
            v.startsWith("/") -> v
            else -> null
        }
    }

    /**
     * 字段值是否**需要**被解析成资源名（即内置图标）。
     *
     * ⚠️ 用它做分支，而不是 `!isCustomImage(...)` 取反 —— 读代码时
     * 「是不是内置图标」比「不是自定义图片」少一层否定，而这里有**三个**形态。
     */
    fun isBuiltInIcon(value: String?): Boolean {
        val v = value?.trim().orEmpty()
        return v.isNotEmpty() && !isCustomImage(v)
    }

    private const val RES_PREFIX = "rounded_"
    private const val LINE_SUFFIX = "_24"
    private const val FILL_SUFFIX = "_fill_24"

    /**
     * 资源名 → **用户看得懂的标签**（`rounded_play_arrow_24` → `play arrow`）。
     *
     * ⚠️ 与 `IconSearchFilter.officialNameOf` 是**同一套规则**（剥前缀、先剥
     * `_fill_24` 再剥 `_24`、下划线转空格）。两处规则若漂移，会出现
     * 「搜索按 A 规则匹配、显示按 B 规则渲染」，而用户在搜索框里敲的正是
     * 屏幕上看到的那个名字 —— 症状是「搜得到但名字对不上」，很难归因。
     *
     * ⚠️ 刻意**不在这里引用 `IconSearchFilter`**：那个类在 `ui.workflow_editor`
     * 包下，而本文件在 `core.workflow`（业务层）。core 反向依赖 ui 会把
     * 分层弄乱，且 `IconSearchFilter` 的匹配逻辑（前缀/词首匹配）与
     * "生成展示名"是两件事。两条规则靠**测试**对齐（`WorkflowIconValueTest`
     * 里有一例直接比对两者的输出）。
     *
     * ⚠️ 自定义图片路径进来时返回**空串**（它没有"官方名字"）。调用方据此
     * 决定要不要显示标签 —— 给自定义图片显示一长串 `/data/user/0/...` 毫无意义。
     */
    fun officialLabelOf(iconResName: String?): String {
        val v = iconResName?.trim().orEmpty()
        if (v.isEmpty() || isCustomImage(v)) return ""
        var name = v.removePrefix(RES_PREFIX)
        name = when {
            name.endsWith(FILL_SUFFIX) -> name.removeSuffix(FILL_SUFFIX)
            name.endsWith(LINE_SUFFIX) -> name.removeSuffix(LINE_SUFFIX)
            else -> name
        }
        return name.replace('_', ' ')
    }

    /**
     * 图标全名表（**用于精确判定「这一个是线框版还是填充版」**）。
     *
     * ⚠️ 见 [isFilledVariant] —— 光看资源名字符串是判不出来的。
     */
    private val knownIconNames: Set<String> by lazy { MaterialSymbolNames.ALL.toSet() }

    /** 填充版标签的后缀（见 [displayLabelOf]）。 */
    const val FILL_LABEL_SUFFIX = " · fill"

    /**
     * 这个资源名是不是**填充版**。
     *
     * ⚠️⚠️ **不能只看字符串后缀 `_fill_24`** —— 全库有一个图标的名字**本身就以
     * `_fill` 结尾**：`format_color_fill`（画的是一只填色桶）。于是：
     *
     * | 资源名 | 实际是 | 光看后缀会判成 |
     * |---|---|---|
     * | `rounded_format_color_fill_24` | 它的**线框**版 | 填充版 ❌ |
     * | `rounded_format_color_fill_fill_24` | 它的**填充**版 | 填充版 ✓ |
     *
     * 后果是那一格的标签会写成「… · fill」而它其实是线框版 —— 不报错，
     * 只是**标签说谎**。
     *
     * ⇒ 判据改为**查名单**：把 `rounded_<名>_24` 里的 `<名>` 拿去全量名单里比对。
     * 命中 ⇒ 线框版；不命中但去掉末尾 `_fill` 后命中 ⇒ 填充版。
     *
     * ⚠️ 名单里查不到时（测试里的合成名、将来改过名的老数据）**回退到字符串规则**
     * —— 回退会让上面那一格判错，但总比把一个陌生名字判成"线框"更加无从解释。
     */
    fun isFilledVariant(iconResName: String?): Boolean {
        val v = iconResName?.trim().orEmpty()
        if (v.isEmpty() || isCustomImage(v)) return false
        val name = v.removePrefix(RES_PREFIX).removeSuffix(LINE_SUFFIX)
        if (name in knownIconNames) return false
        if (name.endsWith(FILL_SUFFIX_NAME) && name.removeSuffix(FILL_SUFFIX_NAME) in knownIconNames) {
            return true
        }
        return v.endsWith(FILL_SUFFIX)
    }

    /**
     * 网格里显示的那行文字 —— [officialLabelOf] + 填充版后缀。
     *
     * ⚠️⚠️ **后缀是必需的，不是美化**：`rounded_home_24` 与 `rounded_home_fill_24`
     * 的官方名字**是同一个**（都是 `home`，只差字形）。不加后缀的话两格的文字
     * 逐字相同，用户会以为图标库里有重复项、点了哪个也说不清。
     *
     * ⚠️ 本函数从 `WorkflowIconPickerAdapter`（一个 `RecyclerView.Adapter`，
     * 纯 JVM 起不来）**提到这里**，就是为了能按全量名单做**去重断言** ——
     * 见 `WorkflowIconValueTest` 里那条"任两个图标的标签不得相同"的用例。
     */
    fun displayLabelOf(iconResName: String?): String {
        val label = officialLabelOf(iconResName)
        if (label.isEmpty()) return ""
        return if (isFilledVariant(iconResName)) label + FILL_LABEL_SUFFIX else label
    }

    /** 名字里自带的 `fill`（区别于资源名的 `_fill_24` 后缀）。 */
    private const val FILL_SUFFIX_NAME = "_fill"
}
