package com.chaomixian.vflow.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * D20 的归一化换算（**已发布 schema 的行为变更**：`0–1000`，不再是绝对像素）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 [chatAgentNormalizedToPixels] 改成 `return raw`（identity）⇒
 *    [fiveHundredMapsToTheMiddleOfEachAxis] **变红**（1080 屏上 500 → 540，不是 500）；
 * 2. 去掉 `require(raw in 0..1000)` 改成 `coerceIn` ⇒
 *    [outOfRangeCoordinates_throwInsteadOfBeingClamped] **变红**。
 */
class ChatAgentCoordinatesTest {

    @Test
    fun zeroMapsToTheLeadingEdgeForBothAxes() {
        assertEquals(0, chatAgentNormalizedToPixels(0, 1080, "x"))
        assertEquals(0, chatAgentNormalizedToPixels(0, 2400, "y"))
    }

    @Test
    fun maxMapsToTheLastPixel_notOutOfBounds() {
        assertEquals("1000 必须落在末像素，而不是 extent（越界）", 1079, chatAgentNormalizedToPixels(1000, 1080, "x"))
        assertEquals(2399, chatAgentNormalizedToPixels(1000, 2400, "y"))
        assertEquals("extent = 1 的退化情形也必须落在 0", 0, chatAgentNormalizedToPixels(1000, 1, "y"))
    }

    @Test
    fun fiveHundredMapsToTheMiddleOfEachAxis() {
        assertEquals(540, chatAgentNormalizedToPixels(500, 1080, "x"))
        assertEquals(1200, chatAgentNormalizedToPixels(500, 2400, "y"))
    }

    @Test
    fun mapsProportionallyAcrossTheWholeRange() {
        assertEquals(108, chatAgentNormalizedToPixels(100, 1080, "x"))
        assertEquals(270, chatAgentNormalizedToPixels(250, 1080, "x"))
        assertEquals(810, chatAgentNormalizedToPixels(750, 1080, "x"))
    }

    @Test
    fun outOfRangeCoordinates_throwInsteadOfBeingClamped() {
        listOf(1001, -1, 20_000).forEach { raw ->
            try {
                chatAgentNormalizedToPixels(raw, 1080, "x")
                fail("越界的归一化坐标 $raw 必须报错，而不是静默钳制（静默点偏是最坏失败形态）")
            } catch (expected: IllegalArgumentException) {
                assertTrue(
                    "异常文案必须给出取值域与语义，模型才能自愈：${expected.message}",
                    expected.message.orEmpty().contains("0-1000"),
                )
            }
        }
    }

    @Test
    fun nonPositiveExtent_throws() {
        try {
            chatAgentNormalizedToPixels(500, 0, "y")
            fail("调用方必须给出真实屏幕尺寸；extent <= 0 时不得猜")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "文案要说明是屏幕尺寸的问题：${expected.message}",
                expected.message.orEmpty().contains("screen extent"),
            )
        }
    }

    @Test
    fun axisIsNamedInTheErrorMessage() {
        try {
            chatAgentNormalizedToPixels(5000, 1080, "start_y")
            fail("越界必须报错")
        } catch (expected: IllegalArgumentException) {
            assertTrue(
                "异常要指认是哪个轴：${expected.message}",
                expected.message.orEmpty().contains("start_y"),
            )
        }
    }
}
