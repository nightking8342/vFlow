package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.core.workflow.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 紧凑模式（瀑布流）的列数 / 密度推导 + 文件夹 Tab 筛选。
 *
 * ## 为什么这些必须被单测锁住
 *
 * 这两个函数都是**「改错了不报错」**的形态：
 * - 列数阈值写错 ⇒ 只有某个屏幕尺寸下多一列或少一列，不崩、不报错，**只有拿真机量才看得出来**；
 * - Tab 筛选写错 ⇒ 某个文件夹里少显示几条工作流，用户会以为工作流丢了。
 *
 * 所以这里**逐档锁边界值**（`599 / 600`、`839 / 840`），而不只是锁「大概能算出 3 列」。
 */
class WorkflowCompactGridSpecTest {

    // ────────────────────────────────────────────────
    // 列数：三档 3 / 4 / 5（照 ShortX：手机 3 / 展开 5）
    // ────────────────────────────────────────────────

    @Test
    fun `measured anchors hit the expected column count`() {
        // ⚠️ 这两个是**用户给的真机锚点**，是这套分档唯一要命中的目标：
        //    手机竖屏 3 列 / 折叠展开 5 列。
        //    ⚠️ 展开横屏也是 5 列（不是 6）—— 上限就是 5。
        assertEquals("手机竖屏 412dp 屏", 3, workflowCompactGridSpec(412.dp - 24.dp).columns)
        assertEquals("折叠展开 871dp 屏", 5, workflowCompactGridSpec(871.dp - 24.dp).columns)
        assertEquals("折叠展开横屏 982dp 屏", 5, workflowCompactGridSpec(982.dp - 24.dp).columns)
    }

    @Test
    fun `four columns appears only in the middle band`() {
        // 中间档是「平板竖屏 / 小尺寸展开」这类宽度，不是主要目标形态，
        // 但必须存在 —— 否则 600dp 一过就直接跳到 5 列，单列会偏窄。
        assertEquals(4, workflowCompactGridSpec(700.dp).columns)
    }

    @Test
    fun `column count never goes below three`() {
        // ⚠️ 反向锁：窄屏**不允许退化成 1/2 列** —— 两列卡片过宽、留白多，
        //    正是这次要修的病。320dp 的老机型也必须 3 列。
        assertEquals(3, workflowCompactGridSpec(320.dp).columns)
        assertEquals(3, workflowCompactGridSpec(0.dp).columns)
        assertEquals(3, workflowCompactGridSpec((-5).dp).columns)
    }

    @Test
    fun `column count never exceeds five`() {
        // ⚠️ 反向锁：上限 **5**（不是 6）。5 列时单列约 180dp，
        //    已是「内容不挤」的合理上限；加到 6 列会让单列掉到 150dp 以下。
        assertEquals(5, workflowCompactGridSpec(1000.dp).columns)
        assertEquals(5, workflowCompactGridSpec(2000.dp).columns)
    }

    @Test
    fun `column count is monotonically non-decreasing`() {
        // 宽度变大时列数绝不能变小（改了公式导致非单调的话，折叠屏展开会比收起少一列，
        // 而那是**静默**的 —— 只有展开那一刻看得出来）。
        var previous = 0
        var w = 0
        while (w <= 1400) {
            val columns = workflowCompactGridSpec(w.dp).columns
            assertTrue("宽度 $w 处列数回退了：$previous → $columns", columns >= previous)
            previous = columns
            w += 10
        }
    }

    // ────────────────────────────────────────────────
    // 单列宽度
    // ────────────────────────────────────────────────

    @Test
    fun `lane width subtracts the spacing between lanes`() {
        // 412 - 8*2(内边距已在外面扣) ; 这里直接喂「可用宽度」
        // 3 列：可用 396 ⇒ 间距 8*2=16 ⇒ (396-16)/3
        val lane = laneWidthFor(420.dp, 3)
        // (420 - 20*2) / 3 = 380 / 3 ≈ 126.67
        assertEquals(380f / 3f, lane.value, 0.01f)
    }

    @Test
    fun `lane width never goes negative`() {
        // ⚠️ 负尺寸传进 Compose 会在**测量阶段**抛异常（崩在布局里，栈上看不到本函数）。
        assertTrue(laneWidthFor(0.dp, 3).value >= 0f)
        assertTrue(laneWidthFor(4.dp, 5).value >= 0f)
    }

    @Test
    fun `zero columns does not divide by zero`() {
        assertEquals(0f, laneWidthFor(400.dp, 0).value, 0.001f)
        assertEquals(0f, laneWidthFor(400.dp, -1).value, 0.001f)
    }

    // ────────────────────────────────────────────────
    // 密度：按**单列宽度**分档，不按列数
    // ────────────────────────────────────────────────

    @Test
    fun `density is derived from lane width`() {
        assertEquals(CompactCardDensity.ROOMY, densityFor(145.dp))
        assertEquals(CompactCardDensity.ROOMY, densityFor(200.dp))
        assertEquals(CompactCardDensity.NORMAL, densityFor(144.9.dp))
        assertEquals(CompactCardDensity.NORMAL, densityFor(105.dp))
        assertEquals(CompactCardDensity.TIGHT, densityFor(104.9.dp))
        assertEquals(CompactCardDensity.TIGHT, densityFor(0.dp))
    }

    @Test
    fun `phone three-column and unfold five-column use different densities`() {
        // ⚠️ 这是「按单列宽度而非列数分档」的**理由本身**：
        //    手机 3 列单列约 113dp（NORMAL），展开 5 列单列约 150dp（ROOMY）。
        //    若按列数分档，展开态会被错误地缩成小字号。
        val phone = workflowCompactGridSpec(412.dp - 24.dp)
        assertEquals(3, phone.columns)
        assertEquals(CompactCardDensity.NORMAL, phone.density)

        val unfold = workflowCompactGridSpec(871.dp - 24.dp)
        assertEquals(5, unfold.columns)
        assertEquals(
            "展开态单列更宽，密度档必须不低于手机",
            CompactCardDensity.ROOMY,
            unfold.density,
        )
    }

    // ────────────────────────────────────────────────
    // 文件夹 Tab 筛选
    // ────────────────────────────────────────────────

    private fun workflow(id: String, folderId: String? = null) = Workflow(
        id = id,
        name = "工作流 $id",
        folderId = folderId,
    )

    @Test
    fun `all tab passes everything through`() {
        val list = listOf(workflow("a"), workflow("b", "f1"), workflow("c", "f2"))
        assertEquals(list, filterByFolderTab(list, WORKFLOW_TAB_ALL))
    }

    @Test
    fun `folder tab keeps only that folder`() {
        val list = listOf(workflow("a"), workflow("b", "f1"), workflow("c", "f2"), workflow("d", "f1"))
        val ids = filterByFolderTab(list, "f1").map { it.id }
        assertEquals(listOf("b", "d"), ids)
    }

    @Test
    fun `uncategorized workflows stay out of every folder tab`() {
        // ⚠️ 用户 2026-10-04 定的：**没有「未分类」档**。
        // 反向锁：不要让 `folderId == null` 的东西混进任何一个文件夹 Tab。
        val list = listOf(workflow("loose"), workflow("b", "f1"))
        assertEquals(listOf("b"), filterByFolderTab(list, "f1").map { it.id })
    }

    @Test
    fun `unknown folder id yields empty rather than everything`() {
        // ⚠️ 反向锁：查不到的文件夹**必须返回空**，不能兜底成全量。
        // 兜底会让「文件夹被删掉」的瞬间用户看到全部工作流，误以为删除没生效。
        val list = listOf(workflow("a"), workflow("b", "f1"))
        assertTrue(filterByFolderTab(list, "不存在的文件夹").isEmpty())
    }

    @Test
    fun `workflows with dangling folder id only appear under all`() {
        // folderId 指向已删文件夹 ⇒ 只在「全部」里可见，不进任何文件夹 Tab
        val orphan = workflow("orphan", "已删除的文件夹id")
        val list = listOf(orphan, workflow("b", "f1"))
        assertEquals(2, filterByFolderTab(list, WORKFLOW_TAB_ALL).size)
        assertTrue(filterByFolderTab(list, "f1").none { it.id == "orphan" })
    }
}
