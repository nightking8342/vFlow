package com.chaomixian.vflow.ui.common.glass

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 玻璃开关的**尺寸锚定 + 滑块定位几何 + 全项目接线**。
 *
 * ## ⚠️ 为什么这三样都必须机器化
 *
 * 三个失败模式**全是静默的**：
 * 1. **尺寸抄错**（轨道 52×32、滑块 24/16）—— 看一眼不觉得，但「保留 M3 形态」
 *    的承诺已经破了，且没有任何行为断言会红；
 * 2. **滑块算出界** —— 按下时滑块变宽，锚定方向搞反会顶出轨道边缘
 *    （`Box` 默认**不裁剪**，所以不报错，只是看起来「滑块探出来了」）；
 * 3. **有调用点漏换** —— 表现为「有的开关是玻璃、有的还是实色」，
 *    而这**比全不玻璃更扎眼**，却完全编译得过。
 *
 * ## 尺寸的核对方式（不是照文档抄的）
 *
 * 从 `material3` 字节码里读出来的：`javap -c -p` 解 `SwitchTokens`
 * 静态初始化块 ⇒ `TrackWidth 52 / TrackHeight 32 / SelectedHandleWidth 24 /
 * UnselectedHandleWidth 16 / TrackOutlineWidth 2`，
 * `ThumbPadding = (TrackHeight − SelectedHandleWidth) / 2 = 4`。
 * 而 `SwitchKt` 的静态块显示它读的就是这几个 token（不是另有一套常量）。
 */
class GlassSwitchTest {

    private val trackWidth: Dp get() = GlassSwitchTokens.TrackWidth

    /** 滑块宽度在给定状态下的取值（与组件里的算法同源：两档 + 按下撑开）。 */
    private fun thumbWidth(checked: Boolean, pressed: Boolean): Dp =
        baseThumbWidthDp(checked) + if (pressed) PRESS_EXTRA else 0.dp

    // ------------------------------------------------------------------
    // 一、尺寸锚定 M3 的 SwitchTokens
    // ------------------------------------------------------------------

    @Test
    fun `尺寸与 Material 3 的 SwitchTokens 逐值一致`() {
        assertEquals("SwitchTokens.TrackWidth", 52f, trackWidth.value, 0.001f)
        assertEquals("SwitchTokens.TrackHeight", 32f, GlassSwitchTokens.TrackHeight.value, 0.001f)
        assertEquals("SwitchTokens.SelectedHandleWidth", 24f, GlassSwitchTokens.ThumbDiameter.value, 0.001f)
        assertEquals(
            "SwitchTokens.UnselectedHandleWidth",
            16f,
            GlassSwitchTokens.UncheckedThumbDiameter.value,
            0.001f,
        )
        assertEquals(
            "(TrackHeight − SelectedHandleWidth) / 2",
            4f,
            GlassSwitchTokens.ThumbPadding.value,
            0.001f,
        )
        assertEquals(
            "SwitchTokens.TrackOutlineWidth",
            2f,
            GlassSwitchTokens.TrackOutlineWidth.value,
            0.001f,
        )
    }

    @Test
    fun `轨道比滑块高一档才留得出内边距`() {
        assertTrue(
            "轨道高度应大于选中态滑块直径（32 > 24），否则滑块贴死上下边缘",
            GlassSwitchTokens.TrackHeight > GlassSwitchTokens.ThumbDiameter,
        )
    }

    @Test
    fun `按下撑开量必须小于两侧内边距之和`() {
        // 未选中态是**中心锚定**：滑块左右各扩 extra/2。若 extra/2 > ThumbPadding，
        // 左边缘会跑到轨道外面 —— 本文件里最容易踩的一个约束。
        assertTrue(
            "PRESS_EXTRA/2 = ${PRESS_EXTRA.value / 2} 必须 ≤ ThumbPadding = " +
                "${GlassSwitchTokens.ThumbPadding.value}",
            PRESS_EXTRA / 2 <= GlassSwitchTokens.ThumbPadding,
        )
    }

    // ------------------------------------------------------------------
    // 二、滑块定位几何
    // ------------------------------------------------------------------

    @Test
    fun `未选中未按下时滑块左边距等于 M3 的内边距`() {
        assertEquals(
            4f,
            glassThumbXDp(checked = false, widthDp = baseThumbWidthDp(false)).value,
            0.001f,
        )
    }

    @Test
    fun `选中未按下时滑块右边缘贴住 M3 的内边距`() {
        val w = baseThumbWidthDp(true)
        val x = glassThumbXDp(checked = true, widthDp = w)
        assertEquals(
            "右边缘应落在 TrackWidth − ThumbPadding = 48dp（52 − 4）",
            (trackWidth - GlassSwitchTokens.ThumbPadding).value,
            (x + w).value,
            0.001f,
        )
    }

    @Test
    fun `任意状态下滑块都完整落在轨道内`() {
        // ⚠️ 「按下变宽」最容易出事的地方：`Box` 默认不裁剪，
        //    滑块探出边缘不会报错、也不会被切，只是看起来不对。
        for (checked in listOf(false, true)) {
            for (pressed in listOf(false, true)) {
                val w = thumbWidth(checked, pressed)
                val x = glassThumbXDp(checked, w)
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
        // 这是「往哪边扩」的判据，也是 glassThumbXDp 里唯一需要解释的决策。
        val restChecked = baseThumbWidthDp(true)
        val pressedChecked = thumbWidth(true, true)
        assertEquals(
            "选中态按下时**右边缘不动**（向左长），否则会顶出右边缘",
            (glassThumbXDp(true, restChecked) + restChecked).value,
            (glassThumbXDp(true, pressedChecked) + pressedChecked).value,
            0.001f,
        )

        val restUnchecked = baseThumbWidthDp(false)
        val pressedUnchecked = thumbWidth(false, true)
        assertEquals(
            "未选中态按下时**中心不动**（左右各长一半）",
            (glassThumbXDp(false, restUnchecked) + restUnchecked / 2).value,
            (glassThumbXDp(false, pressedUnchecked) + pressedUnchecked / 2).value,
            0.001f,
        )
    }

    @Test
    fun `未选中滑块比选中态窄 —— 与 M3 的动画一致`() {
        assertTrue(
            "M3 未选中 16dp、选中 24dp；两者相同时就丢了 M3 那个「长大」的过渡",
            GlassSwitchTokens.UncheckedThumbDiameter < GlassSwitchTokens.ThumbDiameter,
        )
    }

    // ------------------------------------------------------------------
    // 三、实现里必须真的用了 kyant 的折射链路
    // ------------------------------------------------------------------

    @Test
    fun `玻璃本体走 drawBackdrop 而不是手画高光`() {
        // ⚠️⚠️ **这条是本改动最重要的一条**。第一版用 `drawWithCache` 手画
        //    「渐变描边 + 半透明白」冒充玻璃，用户验收原话是
        //    「完全没有液态玻璃的效果」—— 因为真正让玻璃成立的是
        //    **折射**（`lens` 的 RuntimeShader）与库自带的高光/内阴影着色器，
        //    描边和渐变只是它们的粗糙模仿。
        val source = SourceScan.stripped(GLASS_SWITCH)
        for (required in listOf(
            "drawBackdrop(",
            "lens(",
            "Highlight.Ambient",
            "InnerShadow(",
            "rememberLayerBackdrop()",
            "chromaticAberration = true",
        )) {
            assertTrue(
                "GlassSwitch 的实现里必须出现 `$required` —— 缺了它就不是真玻璃，" +
                    "而是手画的仿制品（第一版就是这么被否掉的）",
                source.contains(required),
            )
        }
        // ⚠️⚠️ 这条必须锚**具体那个 lambda 的体内**，不能只断言「源码里有 `1f - p`」——
        //    实测：把 `onDrawSurface` 里的褪白改掉之后，**别的三处 `1f - p`**
        //    （模糊衰减、压扁曲线的 `cos`）会让弱断言照样绿（反证不变红）。
        val surface = SourceScan.functionBody(source, "onDrawSurface = {")
            ?: error("找不到滑块的 onDrawSurface —— 那条「白随按下退掉」的断言会失去依据")
        assertTrue(
            "滑块的白必须**随按下进度退掉**：onDrawSurface 体内应出现 `1f - p`。" +
                "否则按下时看不到底下被折射的轨道色 —— 那是整个观感最「液态」的一帧",
            surface.contains("1f - p"),
        )
        assertFalse(
            "onDrawSurface 里不应出现「不随进度衰减」的纯白常量（那就是第一版的写法）",
            surface.contains("Color.White.copy(alpha = 0.95f)"),
        )
    }

    @Test
    fun `轨道被压扁成滑块的采样源且静止时压扁量为零`() {
        // ⚠️ `scaleY = lerp(0f, 0.75f, progress)` 是 kyant 原实现的做法：
        //    静止时 0 ⇒ 采样里等于没有轨道 ⇒ **避免「滑块采样自己盖住的轨道」
        //    这个自采样回环**（会每帧叠一层）。改成恒定的 1f 就会形成回环，
        //    而表现只是「按下时颜色越来越怪」，很难联想到原因。
        val source = SourceScan.stripped(GLASS_SWITCH)
        val body = SourceScan.functionBody(source, "rememberBackdrop(trackBackdrop)")
            ?: error("找不到轨道被包进 rememberBackdrop 的那一段")
        assertTrue(
            "压扁量必须随按下进度走（`0.75f * p` 这类写法），不能是常量",
            body.contains("0.75f") && body.contains("pressProgress"),
        )
    }

    // ------------------------------------------------------------------
    // 四、全项目接线（源码扫描）
    // ------------------------------------------------------------------

    @Test
    fun `全项目不再有直接使用 M3 Switch 的调用点`() {
        val offenders = mutableListOf<String>()
        val root = File("src/main/java/com/chaomixian/vflow")
        check(root.isDirectory) { "源码目录不存在（测试工作目录应为 app/）：${root.absolutePath}" }
        root.walkTopDown().filter { it.extension == "kt" }.forEach { f ->
            // ⚠️ 必须先剥注释：本组件的 KDoc 里到处是 `Switch(...)` 字样
            //    （解释「为什么不给 M3 Switch 换颜色槽」），不剥的话恒红。
            val s = SourceScan.stripCommentsPreservingStructure(f.readText())
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
        const val GLASS_SWITCH = "src/main/java/com/chaomixian/vflow/ui/common/glass/GlassSwitch.kt"
        const val WORKFLOW_LIST_SCREEN =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"

        /** 按下时滑块横向撑开的量。⚠️ 与组件里的 `pressedExtra` 是同一个数。 */
        val PRESS_EXTRA = 4.dp
    }
}
