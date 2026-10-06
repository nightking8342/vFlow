package com.chaomixian.vflow.core.workflow

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorkflowIconValue` 的三形态判定。
 *
 * ⚠️ 三个形态分别是「内置资源名 / 绝对路径 / `file://`」，而**判定写错的方式
 * 都是静默的**：漏判 `file://` 会让它落到 `getIdentifier`（返回 0 ⇒ 显示默认图标），
 * 用 `substring(7)` 剥前缀会**切掉绝对路径的前 7 个字符**（且不报错，只是文件找不到）。
 */
class WorkflowIconValueTest {

    @Test
    fun `built in icon name is not a custom image`() {
        assertFalse(WorkflowIconValue.isCustomImage("rounded_home_24"))
        assertTrue(WorkflowIconValue.isBuiltInIcon("rounded_home_24"))
        assertNull(WorkflowIconValue.filePathOf("rounded_home_24"))
    }

    @Test
    fun `absolute path is a custom image`() {
        val p = "/data/user/0/com.chaomixian.vflow/files/card_icons/x.png"
        assertTrue(WorkflowIconValue.isCustomImage(p))
        assertFalse(WorkflowIconValue.isBuiltInIcon(p))
        assertEquals(p, WorkflowIconValue.filePathOf(p))
    }

    @Test
    fun `file uri is a custom image`() {
        // ⚠️ `file://` 不以 `/` 开头（它以 `f` 开头）—— 只判后者会漏掉这一形态
        val u = "file:///data/user/0/com.chaomixian.vflow/files/card_icons/x.png"
        assertTrue(WorkflowIconValue.isCustomImage(u))
        assertEquals(
            "/data/user/0/com.chaomixian.vflow/files/card_icons/x.png",
            WorkflowIconValue.filePathOf(u)
        )
    }

    @Test
    fun `stripping file scheme never eats an absolute path`() {
        // ⚠️ 反向锁：用 `substring(7)` 而不是 `removePrefix("file://")` 时，
        //    绝对路径会被切掉开头 7 个字符（`/data/u` → `r/0/...`），且不报错。
        val p = "/data/user/0/x.png"
        assertEquals(p, WorkflowIconValue.filePathOf(p))
    }

    @Test
    fun `blank values are neither built in nor custom`() {
        for (v in listOf(null, "", "   ")) {
            assertFalse("value=$v", WorkflowIconValue.isCustomImage(v))
            assertFalse("value=$v", WorkflowIconValue.isBuiltInIcon(v))
            assertNull("value=$v", WorkflowIconValue.filePathOf(v))
        }
    }

    @Test
    fun `surrounding whitespace is ignored`() {
        // 导入的 JSON 里带空格的资源名/路径都要能认出来
        assertTrue(WorkflowIconValue.isCustomImage("  /data/x.png  "))
        assertEquals("/data/x.png", WorkflowIconValue.filePathOf("  /data/x.png  "))
        assertTrue(WorkflowIconValue.isBuiltInIcon("  rounded_home_24  "))
    }

    @Test
    fun `display label marks the filled variant`() {
        assertEquals("home", WorkflowIconValue.displayLabelOf("rounded_home_24"))
        assertEquals("home · fill", WorkflowIconValue.displayLabelOf("rounded_home_fill_24"))
    }

    @Test
    fun `the one icon whose own name ends with fill is still classified correctly`() {
        // ⚠️⚠️ 全库只有一个图标的名字**本身就以 `fill` 结尾**：`format_color_fill`
        //     （它画的是一只填色桶）。于是：
        //       `rounded_format_color_fill_24`      ← 它的**线框**版
        //       `rounded_format_color_fill_fill_24` ← 它的**填充**版
        //
        //     **光看字符串后缀 `_fill_24` 会把前者误判成填充版** —— 不报错，
        //     只是那一格的标签写着「· fill」而它其实是线框版，**标签说谎**。
        //     ⇒ 判据改成查全量名单（`WorkflowIconValue.isFilledVariant`）。
        assertFalse(WorkflowIconValue.isFilledVariant("rounded_format_color_fill_24"))
        assertTrue(WorkflowIconValue.isFilledVariant("rounded_format_color_fill_fill_24"))

        // 两个标签仍然可区分（唯一性由上面那条全量用例保证）。
        //
        // ⚠️ 注意两个标签的**不对称** —— 这是 `officialLabelOf` 的既有行为
        //    （剥 `_fill_24` 在前、剥 `_24` 在后），**刻意不动**：
        //    · 线框版 `..._fill_24` 被剥成 `format color`（名字里的 fill 没了）；
        //    · 填充版 `..._fill_fill_24` 只剥掉末尾那个 → `format color fill`。
        //    它同时也被搜索侧（`IconSearchFilter.officialNameOf`）使用，
        //    只在这里"修正"会让「屏幕上写着 X、按 X 搜不到」。
        assertEquals("format color", WorkflowIconValue.displayLabelOf("rounded_format_color_fill_24"))
        assertEquals(
            "format color fill · fill",
            WorkflowIconValue.displayLabelOf("rounded_format_color_fill_fill_24")
        )
    }

    @Test
    fun `ordinary filled variants are recognised`() {
        assertFalse(WorkflowIconValue.isFilledVariant("rounded_home_24"))
        assertTrue(WorkflowIconValue.isFilledVariant("rounded_home_fill_24"))
        // 自定义图片与空值都不是填充版
        assertFalse(WorkflowIconValue.isFilledVariant("/data/user/0/x.png"))
        assertFalse(WorkflowIconValue.isFilledVariant(null))
        assertFalse(WorkflowIconValue.isFilledVariant("   "))
    }

    @Test
    fun `custom images and blanks have no display label`() {
        assertEquals("", WorkflowIconValue.displayLabelOf("/data/user/0/x.png"))
        assertEquals("", WorkflowIconValue.displayLabelOf(null))
        assertEquals("", WorkflowIconValue.displayLabelOf("   "))
    }

    @Test
    fun `every icon in the library has a unique display label`() {
        // ⚠️⚠️ **这条是本批最有价值的一条**：8310 个图标来自**两个互不相干的名字
        //     命名法**（Google 的 `_fill1` 后缀 → 本仓库的 `_fill` 后缀），
        //     任何一个环节算错，症状都是「网格里出现两格文字一模一样、
        //     只有图形略有差别」—— 用户会以为图标库有重复项，
        //     且**没有任何报错**。
        //
        //     实测反例：`format_color_fill` 这个图标**本身就带 `fill` 一词**
        //     （它是线框版），它的填充版资源名是 `rounded_format_color_fill_fill_24`。
        //     如果剥后缀时用了 `removeSuffix` 之外的口径（或先剥 `_24` 再剥
        //     `_fill`），两者会渲染成同一个标签。
        val labels = WorkflowVisuals.iconPickerCandidates()
            .map { WorkflowIconValue.displayLabelOf(it) }
        val dupes = labels.groupingBy { it }.eachCount().filterValues { it > 1 }
        assertTrue(
            "有图标渲染出重复标签（用户分不清点的是哪个）：${dupes.entries.take(5)}",
            dupes.isEmpty()
        )
    }
}
