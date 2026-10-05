package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    // ────────────────────────────────────────────────
    // Tab 列表组装：「全部」的计数
    // ────────────────────────────────────────────────

    private fun tab(id: String, count: Int) = WorkflowFolderTab(id, "文件夹 $id", count)

    @Test
    fun `all tab shows the total count not the sum of folders`() {
        // ⚠️ 本用例锁的是一个**真实缺陷**（2026-10-05 用户报）：
        //    原先「全部」的计数取各文件夹之和，而那只数了 `FolderItem` 里的工作流
        //    ⇒ 根目录下未归类的工作流**根本没被算进去**。
        //    只有一个文件夹时两数恰好相等（看起来对），一旦有根工作流就少报。
        val folders = listOf(tab("f1", 3))
        val items = folderTabItems(folders, allTabLabel = "全部", totalWorkflowCount = 5)
        assertEquals("全部", items.first().name)
        assertEquals(
            "「全部」必须用总数（含未归类），不能是各文件夹之和",
            5,
            items.first().workflowCount,
        )
        assertEquals("文件夹本身的数量不受影响", 3, items[1].workflowCount)
    }

    @Test
    fun `all tab still works when there are no folders`() {
        // 无文件夹时 Tab 栏本就不显示，但纯函数不该因此崩或返回空。
        val items = folderTabItems(emptyList(), allTabLabel = "全部", totalWorkflowCount = 2)
        assertEquals(1, items.size)
        assertEquals(WORKFLOW_TAB_ALL, items.first().folderId)
        assertEquals(2, items.first().workflowCount)
    }

    @Test
    fun `all tab is always the first entry`() {
        // ⚠️ 顺序有意义：Tab 栏按下标算格宽与指示块位置，且界面上「全部」恒在最左。
        val items = folderTabItems(
            listOf(tab("f1", 1), tab("f2", 2)),
            allTabLabel = "全部",
            totalWorkflowCount = 4,
        )
        assertEquals(listOf(WORKFLOW_TAB_ALL, "f1", "f2"), items.map { it.folderId })
    }

    @Test
    fun `all tab count may exceed the sum of folders`() {
        // ⚠️ 反向锁：`全部 > Σ文件夹` 是**正常**的（未归类的工作流只在「全部」里可见），
        //    不要为了「看起来整齐」把它改成相等。
        val items = folderTabItems(
            listOf(tab("f1", 2), tab("f2", 3)),
            allTabLabel = "全部",
            totalWorkflowCount = 9,
        )
        assertEquals(9, items.first().workflowCount)
        assertEquals(2, items[1].workflowCount)
        assertEquals(3, items[2].workflowCount)
    }

    @Test
    fun `folder tab count is not derived from the total`() {
        // ⚠️ 反向锁：改「全部」的算法时**不得**顺手改各文件夹的计数（它们来自
        //    `FolderItem.workflowCount`，是 Route 层按 `folderId` 过滤出来的）。
        val items = folderTabItems(
            listOf(tab("f1", 7)),
            allTabLabel = "全部",
            totalWorkflowCount = 7,
        )
        assertEquals(7, items[1].workflowCount)
    }

    // ────────────────────────────────────────────────
    // 源码扫描：「全部」的计数**从哪里算**
    // ────────────────────────────────────────────────

    @Test
    fun `all workflow count is derived from uiState items not displayItems`() {
        // ⚠️⚠️ 本用例锁一个**真实缺陷**（2026-10-05 用户报：「全部」后面的数字变成 0）。
        //    根因不是算法，是 **`remember` 的 key**：`displayItems` 是
        //    `SnapshotStateList`，拿它本身当 key 只认引用变化，而引用恒定不变
        //    ⇒ 计数在首帧就算死，之后永远是 0（**不报错**，只是显示不对）。
        //
        //    ⚠️ 这个缺陷**纯函数测试完全测不出来**（`folderTabItems` 本身是对的），
        //    只能扫源码锁住「取哪个源」。
        val source = SourceScan.file(
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
        ).readText()
        val body = SourceScan.functionBody(source, "fun WorkflowListScreen(")
        checkNotNull(body) { "没能截取 WorkflowListScreen 函数体（签名可能变了）" }

        // ⚠️⚠️ 判据必须锚到**完整表达式**（`val allWorkflowCount = remember(...)`），
        //    不能只测「函数体里出现过 `remember(uiState.items)`」—— 同函数体里
        //    `folderTabs` 那行**也是**这个写法 ⇒ 那样写会**空转**、反证不变红
        //    （已实际踩过：退回 `displayItems` 后测试照样绿）。
        assertTrue(
            "「全部」的计数必须以 uiState.items 为源（displayItems 的引用恒定，当 remember key 会恒为 0）",
            body.contains("val allWorkflowCount = remember(uiState.items)"),
        )
        assertTrue(
            "计数必须同时摊平 FolderItem.childWorkflows（文件夹里的工作流不在顶层）",
            body.contains("FolderItem -> addAll(item.childWorkflows)"),
        )
        assertTrue(
            "防空转：函数体必须真的被截到（不能是空串/极小片段）",
            body.length > 500,
        )
    }

    @Test
    fun `all workflow count is not taken from filtered items`() {
        // ⚠️ 反向锁：`filteredItems` 是**当前选中 Tab 过滤后**的结果，
        //    用它当「全部」的计数会随切换 Tab 而变（在某个文件夹里时显示那个文件夹的数量）。
        //    这正是最初那版缺陷的另一种写法。
        val source = SourceScan.file(
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
        ).readText()
        val body = SourceScan.functionBody(source, "fun WorkflowListScreen(")
        checkNotNull(body)

        assertFalse(
            "「全部」的计数不得取自 filteredItems",
            body.contains("val allWorkflowCount = remember(filteredItems)"),
        )
    }
}
