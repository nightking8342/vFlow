package com.chaomixian.vflow.ui.common.glass

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/**
 * 玻璃开关的**尺寸锚定 + 滑块定位几何 + 全项目接线**。
 *
 * ## ⚠️ 为什么这三样都必须机器化
 *
 * 这层改动三个失败模式**全是静默的**：
 * 1. **尺寸抄错**（轨道 52×32、滑块 24/16）—— 看一眼不觉得，但「保留 M3 形态」
 *    的承诺已经破了，且没有任何行为断言会红；
 * 2. **滑块算出界** —— 按下时滑块变宽，锚定方向搞反会顶出轨道边缘
 *    （`Box` 默认**不裁剪**，所以不会报错，只是看起来「滑块探出来了」）；
 * 3. **有调用点漏换** —— 表现为「有的开关是玻璃、有的还是实色」，
 *    而这**比全不玻璃更扎眼**，却完全编译得过。
 *
 * ## 尺寸的核对方式（不是照文档抄的）
 *
 * 从 `material3` 的字节码里读出来的：`javap -c -p` 解 `SwitchTokens`
 * 的静态初始化块，得到 `TrackWidth = 52dp / TrackHeight = 32dp /
 * SelectedHandleWidth = 24dp / UnselectedHandleWidth = 16dp`，
 * `ThumbPadding = (TrackHeight − SelectedHandleWidth) / 2 = 4dp`。
 * 而 `SwitchKt` 的静态块显示它读的就是这几个 token（不是另有一套常量）。
 */
class GlassSwitchTest {

    // ------------------------------------------------------------------
    // 一、尺寸锚定 M3 的 SwitchTokens
    // ------------------------------------------------------------------

    @Test
    fun `尺寸与 Material 3 的 SwitchTokens 逐值一致`() {
        assertEquals("SwitchTokens.TrackWidth", 52f, GlassSwitchTokens.TrackWidth.value, 0.001f)
        assertEquals("SwitchTokens.TrackHeight", 32f, GlassSwitchTokens.TrackHeight.value, 0.001f)
        assertEquals("SwitchTokens.SelectedHandleWidth", 24f, GlassSwitchTokens.ThumbDiameter.value, 0.001f)
        assertEquals(
            "SwitchTokens.UnselectedHandleWidth",
            16f,
            GlassSwitchTokens.UncheckedThumbDiameter.value,
            0.001f,
        )
        assertEquals("(TrackHeight − SelectedHandleWidth) / 2", 4f, GlassSwitchTokens.ThumbPadding.value, 0.001f)
    }

    @Test
    fun `轨道比滑块高一档才留得出按下富余`() {
        // 32 − 24 = 8，两侧各 4dp 内边距；按下时滑块高度**不变**
        // （只有宽度加 4dp），所以高度方向的富余始终够用。
        assertTrue(
            "轨道高度应大于选中态滑块直径（否则滑块贴死上下边缘、看不出是「轨道里的滑块」）",
            GlassSwitchTokens.TrackHeight > GlassSwitchTokens.ThumbDiameter,
        )
    }

    @Test
    fun `按下增宽必须小于两侧内边距之和`() {
        // 未选中态是**中心锚定**：滑块左右各扩 growth/2。若 growth/2 > ThumbPadding，
        // 滑块左边缘会跑到轨道外面。这是本文件里最容易踩的一个约束。
        assertTrue(
            "PressGrowth/2 = ${GlassSwitchTokens.PressGrowth.value / 2} 必须 ≤ ThumbPadding " +
                "= ${GlassSwitchTokens.ThumbPadding.value}，否则按下时滑块会捅出左边缘",
            GlassSwitchTokens.PressGrowth / 2 <= GlassSwitchTokens.ThumbPadding,
        )
    }

    // ------------------------------------------------------------------
    // 二、滑块定位几何
    // ------------------------------------------------------------------

    private val trackWidth: Dp get() = GlassSwitchTokens.TrackWidth

    @Test
    fun `未选中未按下时滑块左边距等于 M3 的内边距`() {
        assertEquals(4f, glassThumbXDp(checked = false, pressed = false).value, 0.001f)
    }

    @Test
    fun `选中未按下时滑块右边缘贴住 M3 的内边距`() {
        val x = glassThumbXDp(checked = true, pressed = false)
        val right = x + baseThumbWidthDp(checked = true)
        assertEquals(
            "右边缘应落在 TrackWidth − ThumbPadding = 48dp（52 − 4）",
            (trackWidth - GlassSwitchTokens.ThumbPadding).value,
            right.value,
            0.001f,
        )
    }

    @Test
    fun `任意状态下滑块都完整落在轨道内`() {
        // ⚠️ 这条是「按下变宽」最容易出事的地方：`Box` 默认不裁剪，
        //    滑块探出边缘不会报错、也不会被切，只是看起来不对。
        for (checked in listOf(false, true)) {
            for (pressed in listOf(false, true)) {
                val x = glassThumbXDp(checked, pressed)
                val w = glassThumbWidthDp(checked, pressed)
                assertTrue(
                    "checked=$checked pressed=$pressed: 左边缘 ${x.value} 越过了轨道左端",
                    x.value >= -0.001f,
                )
                assertTrue(
                    "checked=$checked pressed=$pressed: 右边缘 ${(x + w).value} 越过了轨道右端 " +
                        "${trackWidth.value}",
                    (x + w).value <= trackWidth.value + 0.001f,
                )
            }
        }
    }

    @Test
    fun `按下时选中态右边缘不动、未选中态中心不动`() {
        // 这是「往哪边扩」的判据，也是 [glassThumbXDp] 里唯一需要解释的决策。
        val checkedRest = glassThumbXDp(true, false)
        val checkedPressed = glassThumbXDp(true, true)
        assertEquals(
            "选中态按下时**右边缘不动**（向左长），否则会顶出右边缘",
            (checkedRest + baseThumbWidthDp(true)).value,
            (checkedPressed + glassThumbWidthDp(true, true)).value,
            0.001f,
        )

        val uncheckedRest = glassThumbXDp(false, false)
        val uncheckedPressed = glassThumbXDp(false, true)
        assertEquals(
            "未选中态按下时**中心不动**（左右各长一半）",
            (uncheckedRest + baseThumbWidthDp(false) / 2).value,
            (uncheckedPressed + glassThumbWidthDp(false, true) / 2).value,
            0.001f,
        )
    }

    @Test
    fun `按下只加宽不加高`() {
        // 高度跟着变宽会让滑块变成一颗「椭圆」，那是另一种观感（不是 M3 的按下反馈）。
        assertEquals(
            baseThumbWidthDp(true).value,
            baseThumbWidthDp(checked = true).value,
            0.001f,
        )
        assertTrue(glassThumbWidthDp(true, true) > glassThumbWidthDp(true, false))
        assertTrue(
            "按下增宽应为 PressGrowth（4dp）",
            abs(
                (glassThumbWidthDp(true, true) - glassThumbWidthDp(true, false)).value -
                    GlassSwitchTokens.PressGrowth.value
            ) < 0.001f,
        )
    }

    @Test
    fun `未选中滑块比选中态窄 —— 与 M3 的动画一致`() {
        assertTrue(
            "M3 未选中滑块 16dp、选中 24dp；两者相同时就丢了 M3 那个「长大」的过渡",
            GlassSwitchTokens.UncheckedThumbDiameter < GlassSwitchTokens.ThumbDiameter,
        )
    }

    // ------------------------------------------------------------------
    // 三、全项目接线（源码扫描）
    // ------------------------------------------------------------------

    @Test
    fun `全项目不再有直接使用 M3 Switch 的调用点`() {
        val offenders = mutableListOf<String>()
        val root = File("src/main/java/com/chaomixian/vflow")
        check(root.isDirectory) { "源码目录不存在（测试工作目录应为 app/）：${root.absolutePath}" }
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            val raw = f.readText()
            // ⚠️ 必须先剥注释：本组件的 KDoc 里到处是 `Switch(...)` 字样
            //    （解释「为什么不给 M3 Switch 换颜色槽」），不剥的话恒红。
            val s = SourceScan.stripCommentsPreservingStructure(raw)
            Regex("(?<![\\w.])(?<!VFlow)Switch\\(").findAll(s).forEach { m ->
                // 唯一合法的例外：组件自己文件里的 M3 回退分支。
                if (f.name != "GlassSwitch.kt") {
                    offenders += "${f.path}:${s.take(m.range.first).count { it == '\n' } + 1}"
                }
            }
        }
        assertEquals(
            "这些调用点还在直接用 M3 Switch —— 液态玻璃打开后它们会是实色的，" +
                "而「有的开关是玻璃、有的不是」比全不玻璃更扎眼。改用 VFlowSwitch：\n" +
                offenders.joinToString("\n"),
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun `VFlowSwitch 覆盖到了两个工作流卡片调用点`() {
        // 这两处是用户最初截图里对比的对象，单独锁一遍 ——
        // 上面那条「全项目无裸 Switch」在**有人新加了裸 Switch** 时会红，
        // 但**有人把这两处删掉**时不会红。
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "两张卡片（列表模式 + 瀑布流）的启用开关都必须走 VFlowSwitch",
            2,
            SourceScan.countOccurrences(source, "VFlowSwitch("),
        )
    }

    @Test
    fun `接线扫描不是空转`() {
        val root = File("src/main/java/com/chaomixian/vflow")
        var total = 0
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            total += Regex("VFlowSwitch\\(").findAll(
                SourceScan.stripCommentsPreservingStructure(f.readText())
            ).count()
        }
        assertTrue("全项目应至少有一批 VFlowSwitch 调用点，实际 $total", total >= 10)
    }

    private companion object {
        const val WORKFLOW_LIST_SCREEN =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
    }
}
