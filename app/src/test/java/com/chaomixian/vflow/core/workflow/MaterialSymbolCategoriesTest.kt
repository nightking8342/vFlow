package com.chaomixian.vflow.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类表的**覆盖率体检**。
 *
 * ⚠️ 脱节的表现是**静默的**：某个图标没被任何分类收录 ⇒ 用户在分类里
 * 永远找不到它（而"全部"里还有，所以看起来像"分类不全"而不是 bug）。
 * 生成脚本按 `misc` 兜底，但兜底逻辑一旦写错（比如 `imported` 与 `byName`
 * 用了不同的名字集合）就会有漏网的。
 */
class MaterialSymbolCategoriesTest {

    private val allNames: Set<String> = MaterialSymbolNames.ALL.toSet()

    private val categorized: Set<String> =
        MaterialSymbolCategories.ALL.flatMap { it.iconNames }.toSet()

    @Test
    fun `every icon belongs to exactly one category`() {
        val total = MaterialSymbolCategories.ALL.sumOf { it.iconNames.size }
        assertEquals(
            "同一图标被收进多个分类（组间重复）",
            categorized.size,
            total
        )
    }

    @Test
    fun `every icon in the master list is categorized`() {
        val missing = allNames - categorized
        assertTrue("有图标没被任何分类收录：${missing.take(10)}（共 ${missing.size} 个）",
            missing.isEmpty())
    }

    @Test
    fun `no category references an icon that does not exist`() {
        // 反向锁：分类表里出现了清单外的名字 ⇒ 选择器会显示一个点不动的条目
        //（`getIdentifier` 返回 0，`WorkflowIconPickerAdapter` 保留原图 ⇒ 看起来"没反应"）
        val extra = categorized - allNames
        assertTrue("分类表引用了不存在的图标：${extra.take(10)}", extra.isEmpty())
    }

    @Test
    fun `popular icons are all real`() {
        val bad = MaterialSymbolCategories.POPULAR.filterNot { it in allNames }
        assertTrue("常用组里有不存在的图标：${bad.take(10)}", bad.isEmpty())
    }

    @Test
    fun `popular is not part of the content categories`() {
        // ⚠️ 「常用」是一个**视图**，不是一个内容分类 —— 它收录的图标必须
        //    同时出现在各自的内容分类里。若被当成分类塞进 ALL，那些图标会在
        //    各自的内容分类里消失。
        assertTrue("「常用」不该作为内容分类出现在 ALL 里",
            MaterialSymbolCategories.ALL.none { it.id == "popular" })
    }

    @Test
    fun `every category is non empty`() {
        val empty = MaterialSymbolCategories.ALL.filter { it.iconNames.isEmpty() }
        assertTrue("空分类会在 UI 上显示一个点了没反应的 Chip：${empty.map { it.id }}",
            empty.isEmpty())
    }

    @Test
    fun `categorization is not vacuous`() {
        assertTrue("分类太少（${MaterialSymbolCategories.ALL.size}）",
            MaterialSymbolCategories.ALL.size >= 8)
        assertTrue("总收录数太少（${categorized.size}）", categorized.size > 4000)
    }

    @Test
    fun `groupOf falls back to misc for unknown icons`() {
        // ⚠️ 上游有 113 个图标没有任何分类。返回 null 会让调用方还得判一次，
        //    而"归入其他"本来就是正确行为。
        assertEquals("misc", MaterialSymbolCategories.groupOf("this_icon_does_not_exist"))
    }

    @Test
    fun `groupOf resolves a known icon`() {
        val anyIcon = MaterialSymbolCategories.ALL.first { it.id == "action" }.iconNames.first()
        assertEquals("action", MaterialSymbolCategories.groupOf(anyIcon))
    }
}
