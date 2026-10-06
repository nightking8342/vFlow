package com.chaomixian.vflow.ui.workflow_editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 图标网格列数的回归测试。
 *
 * ## 为什么这条纯函数值得单测
 *
 * 它的失败形态是**纯视觉、零报错**的：列数算错了页面照样能打开、图标照样能点，
 * 只是「一排图标之间隔着一片空白」或「挤成一团」。没有任何行为测试会因此变红。
 *
 * ⚠️ **这个函数已经错过两次，且两次方向相反**（都在真机上暴露）：
 *
 * | 版本 | 折叠态（内容区 304dp） | 展开态（847dp） | 结果 |
 * |---|---|---|---|
 * | 固定 5 列 | 5 列 / 60.8dp | 5 列 / 169dp | 展开态**太空** |
 * | `floor(宽 / 60)` | 5 列 / 60.8dp | 14→10 列 / 84.7dp | 折叠态**太挤**（60dp 贴着裁切下限） |
 * | **当前（目标 84dp）** | **4 列 / 76dp** | **10 列 / 84.7dp** | — |
 *
 * ⇒ 下面每条断言都对着一个**已发生过的**失效，不是凭空设的。
 *
 * ⚠️ **入参是 RecyclerView 的「内容区宽度」**（已扣掉布局里的左右 padding
 * 各 12dp），**不是屏幕宽度**。
 */
class IconGridSpanTest {

    @Test
    fun `phone width yields four columns`() {
        // 小米 MIX Fold 3 折叠态实测 328dp 屏 ⇒ 内容区 328 - 12 - 12 = 304dp
        // 304 / 84 = 3.62 → round = 4，每格 76dp（圆底 52dp，左右各留 12dp）。
        // ⚠️ 比上一版的 5 列**更少列**是**有意的** —— 上一版每格 60.8dp，
        //    圆底之间只剩 8.8dp，用户反馈「太密」（2026-10-05）。
        val span = WorkflowIconPickerActivity.computeGridSpan(304f)
        assertEquals(4, span)
        assertTrue("手机每格 ${304f / span} dp，圆底之间太挤", 304f / span >= 72f)
    }

    @Test
    fun `unfolded width yields ten columns`() {
        // 展开态实测 871dp 屏 ⇒ 内容区 847dp
        // 847 / 84 = 10.08 → round = 10，每格 84.7dp —— **正好落在目标宽上**。
        // ⚠️ 这条同时证明「上限 10 不是随手取的」：上限设 8 的话每格会拉到
        //    105.9dp，展开态又开始见白。
        assertEquals(10, WorkflowIconPickerActivity.computeGridSpan(847f))
        val cell = 847f / 10
        assertTrue("展开态每格 $cell dp 偏离目标宽太多", cell in 75f..95f)
    }

    @Test
    fun `cells are never packed tighter than the target`() {
        // ⚠️⚠️ **这条锁的是「太密」那个已发生的缺陷**：上一版用
        //    `floor(宽 / 60)`，效果是列数总落在上限、每格**永远恰好等于最小值**。
        //    这里反向断言「每格不得明显窄于目标宽」—— 允许取整带来的
        //    最多 1 列的偏差（84/2 = 42dp 是理论下界），但**不允许**出现
        //    「一整段宽度区间里每格都贴着 60dp 排」那种密排。
        var width = 240f
        while (width <= 1200f) {
            val span = WorkflowIconPickerActivity.computeGridSpan(width)
            val cell = width / span
            assertTrue(
                "内容宽 $width / $span 列 = 每格 $cell dp，明显比目标宽（84dp）密",
                cell >= 42f
            )
            width += 7f
        }
    }

    @Test
    fun `cell width never clips the circular background`() {
        // ⚠️ 下限 **60dp** 是结构性的：`item_icon_selector.xml` 里圆形底写死
        //    52dp（不是 `match_parent`），根布局四边各 4dp 内边距 ⇒ 每格窄于 60dp
        //    时圆底**真的会被裁掉边缘**，而且不报错。
        //
        // ⚠️ 扫描从 **240dp** 起（`3 × 60dp = 180dp` 以下靠的是下限钳位，
        //    那时确实会窄于 60dp，属于「宁可裁一点也不能排 2 列」的有意取舍）。
        //    240dp 是折叠屏外屏那一档，在役机型里最窄的了。
        var width = 240f
        while (width <= 1200f) {
            val span = WorkflowIconPickerActivity.computeGridSpan(width)
            val cell = width / span
            assertTrue("内容宽 $width / $span 列 = 每格 $cell dp，圆底会被裁", cell >= 60f)
            // 上限 = 目标宽的 1.5 倍（四舍五入的理论上界）再加一点余量。
            assertTrue("内容宽 $width / $span 列 = 每格 $cell dp，留白过大", cell <= 135f)
            width += 13f
        }
    }

    @Test
    fun `span is monotonic in width`() {
        // ⚠️ 单调性不是"顺手加的"：列数在宽度上不单调意味着**折叠屏展开后
        //    图标反而变大、一屏看到的更少** —— 那是最容易让用户觉得"坏掉了"的形态。
        var previous = 0
        var width = 200f
        while (width <= 1200f) {
            val span = WorkflowIconPickerActivity.computeGridSpan(width)
            assertTrue("宽度 $width 时列数从 $previous 掉到了 $span", span >= previous)
            previous = span
            width += 7f
        }
    }

    @Test
    fun `narrow screens never go below three columns`() {
        // 下限 3：再窄（折叠屏外屏之类）也不排到 2 列 —— 2 列时一屏只能看十几个
        // 图标，8300 项的列表等于没法用。
        assertEquals(3, WorkflowIconPickerActivity.computeGridSpan(200f))
        assertEquals(3, WorkflowIconPickerActivity.computeGridSpan(0f))
    }
}
