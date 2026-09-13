package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.ui.chat.ChatFloatGeometry.Bounds
import com.chaomixian.vflow.ui.chat.ChatFloatGeometry.HAnchor
import com.chaomixian.vflow.ui.chat.ChatFloatGeometry.VAnchor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 悬浮窗展开/折叠的几何单测。
 *
 * 重点保障用户提出的需求：**拖到底部时向上展开，折叠后仍停在底部**。
 */
class ChatFloatGeometryTest {

    private val screenW = 1080
    private val screenH = 2400

    private val collapsedW = 800
    private val collapsedH = 140
    private val expandedW = 960
    private val expandedH = 1320

    @Test
    fun `拖到底部时判定为底边锚定`() {
        val bounds = Bounds(left = 40, top = 2200, width = collapsedW, height = collapsedH)
        val (_, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(VAnchor.BOTTOM, v)
    }

    @Test
    fun `贴右边缘时判定为右边锚定`() {
        val bounds = Bounds(left = 270, top = 500, width = collapsedW, height = collapsedH)
        val (h, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(HAnchor.RIGHT, h)
        assertEquals(VAnchor.TOP, v)
    }

    @Test
    fun `底边锚定展开时向上生长 底边不动`() {
        val bounds = Bounds(left = 40, top = 2200, width = collapsedW, height = collapsedH)
        val (h, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        val expanded = ChatFloatGeometry.resize(
            bounds = bounds,
            newWidth = expandedW,
            newHeight = expandedH,
            hAnchor = h,
            vAnchor = v,
            screenWidth = screenW,
            screenHeight = screenH,
        )

        // 底边应保持不动（= 原 bounds.bottom），而不是向下顶出屏幕
        assertEquals(bounds.bottom, expanded.bottom)
        // 向上生长意味着 top 变小
        assertTrue(expanded.top < bounds.top || expanded.top == 0)
        // 不应越界
        assertTrue(expanded.bottom <= screenH)
    }

    @Test
    fun `底边锚定的展开再折叠 窄条仍停在底部`() {
        val start = Bounds(left = 40, top = 2200, width = collapsedW, height = collapsedH)
        val (h, v) = ChatFloatGeometry.anchorsFor(start, expandedW, expandedH, screenW, screenH)

        val expanded = ChatFloatGeometry.resize(
            start, expandedW, expandedH, h, v, screenW, screenH,
        )
        // 折叠回原尺寸，锚定边仍是底边 → 应回到与初始相同的底边位置
        val collapsedAgain = ChatFloatGeometry.resize(
            expanded, collapsedW, collapsedH, h, v, screenW, screenH,
        )

        assertEquals(start.bottom, collapsedAgain.bottom)
        assertEquals(start.left, collapsedAgain.left)
    }

    @Test
    fun `右边锚定展开时左边生长 右边不动`() {
        val bounds = Bounds(left = 250, top = 500, width = collapsedW, height = collapsedH)
        val (h, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        val expanded = ChatFloatGeometry.resize(
            bounds, expandedW, expandedH, h, v, screenW, screenH,
        )
        assertEquals(bounds.right, expanded.right)
        assertTrue(expanded.left < bounds.left)
    }

    @Test
    fun `展开后不超出屏幕`() {
        val bounds = Bounds(left = 0, top = 0, width = collapsedW, height = collapsedH)
        val expanded = ChatFloatGeometry.resize(
            bounds, expandedW, expandedH, HAnchor.LEFT, VAnchor.TOP, screenW, screenH,
        )
        assertTrue(expanded.left >= 0)
        assertTrue(expanded.top >= 0)
        assertTrue(expanded.right <= screenW)
        assertTrue(expanded.bottom <= screenH)
    }

    @Test
    fun `屏幕比面板还小时夹到 0 不产生负坐标`() {
        val smallW = 500
        val smallH = 600
        val bounds = Bounds(left = 10, top = 10, width = 800, height = 140)
        val result = ChatFloatGeometry.resize(
            bounds, expandedW, expandedH, HAnchor.LEFT, VAnchor.BOTTOM, smallW, smallH,
        )
        assertEquals(0, result.left)
        assertEquals(0, result.top)
    }

    @Test
    fun `顶部锚定展开向下生长 顶边不动`() {
        val bounds = Bounds(left = 40, top = 200, width = collapsedW, height = collapsedH)
        val (h, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(VAnchor.TOP, v)
        val expanded = ChatFloatGeometry.resize(
            bounds, expandedW, expandedH, h, v, screenW, screenH,
        )
        assertEquals(bounds.top, expanded.top)
        assertTrue(expanded.bottom > bounds.bottom)
    }

    // ------------------------------------------------------------------
    // 「按可用空间」判定规则
    // ------------------------------------------------------------------

    @Test
    fun `靠近底部时只有向上放得下 强制向上展开`() {
        // 窄条贴近底部：向下展开会越界（top+expandedH > screenH），只能向上
        val bounds = Bounds(left = 40, top = 2200, width = collapsedW, height = collapsedH)
        val (_, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(VAnchor.BOTTOM, v)
    }

    @Test
    fun `靠近顶部时只有向下放得下 强制向下展开`() {
        val bounds = Bounds(left = 40, top = 400, width = collapsedW, height = collapsedH)
        val (_, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(VAnchor.TOP, v)
    }

    @Test
    fun `中偏下位置 向下放不下则向上展开`() {
        // top=1300, bottom=1440, 展开高 1320：
        //   向下 1300+1320=2620 > 2400 放不下；向上 1440-1320=120 >= 0 放得下 → 向上
        val bounds = Bounds(left = 40, top = 1300, width = collapsedW, height = collapsedH)
        val (_, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(VAnchor.BOTTOM, v)
    }

    @Test
    fun `中偏上位置 向上放不下则向下展开`() {
        // top=800, bottom=940：向下 800+1320=2120 <= 2400 可以；向上 940-1320=-380 不行 → 向下
        val bounds = Bounds(left = 40, top = 800, width = collapsedW, height = collapsedH)
        val (_, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(VAnchor.TOP, v)
    }

    @Test
    fun `真正的两侧都放得下时 按中心二分`() {
        // 屏幕很高（5000）且面板较小（1320）：上下两侧都放得下 → 走中心二分
        val tallH = 5000
        val upper = Bounds(left = 40, top = 1000, width = collapsedW, height = collapsedH)
        val lower = Bounds(left = 40, top = 3000, width = collapsedW, height = collapsedH)
        val (_, vUpper) = ChatFloatGeometry.anchorsFor(upper, expandedW, expandedH, screenW, tallH)
        val (_, vLower) = ChatFloatGeometry.anchorsFor(lower, expandedW, expandedH, screenW, tallH)
        assertEquals(VAnchor.TOP, vUpper)
        assertEquals(VAnchor.BOTTOM, vLower)
    }

    @Test
    fun `面板高度接近屏幕时 总有一侧被强制选定且不越界`() {
        // 展开高 = 屏幕高的 55%，扫描整列位置，验证始终不越界
        val h = (screenH * 0.55f).toInt()
        (0..(screenH - collapsedH) step 100).forEach { top ->
            val bounds = Bounds(left = 40, top = top, width = collapsedW, height = collapsedH)
            val (ha, va) = ChatFloatGeometry.anchorsFor(bounds, expandedW, h, screenW, screenH)
            val r = ChatFloatGeometry.resize(bounds, expandedW, h, ha, va, screenW, screenH)
            assertTrue("top=$top", r.top >= 0)
            assertTrue("top=$top", r.bottom <= screenH)
        }
    }

    @Test
    fun `贴右边缘且向左放不下时改为向右展开`() {
        // 窄条在左侧，左边放得下 → 锚定左边（向右生长）
        val bounds = Bounds(left = 20, top = 800, width = collapsedW, height = collapsedH)
        val (h, _) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
        assertEquals(HAnchor.LEFT, h)
    }

    @Test
    fun `无论选哪个方向 展开结果都不越界`() {
        val tops = listOf(0, 300, 800, 1300, 1800, 2200, 2260)
        tops.forEach { top ->
            val bounds = Bounds(left = 40, top = top, width = collapsedW, height = collapsedH)
            val (h, v) = ChatFloatGeometry.anchorsFor(bounds, expandedW, expandedH, screenW, screenH)
            val r = ChatFloatGeometry.resize(bounds, expandedW, expandedH, h, v, screenW, screenH)
            assertTrue("top=$top left=${r.left}", r.left >= 0)
            assertTrue("top=$top 结果 top=${r.top}", r.top >= 0)
            assertTrue("top=$top right=${r.right}", r.right <= screenW)
            assertTrue("top=$top bottom=${r.bottom}", r.bottom <= screenH)
        }
    }
}
