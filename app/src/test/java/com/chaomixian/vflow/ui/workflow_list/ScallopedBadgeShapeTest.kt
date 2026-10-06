package com.chaomixian.vflow.ui.workflow_list

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.min

/**
 * 卡片图标徽章的「曲奇饼干」花边形状。
 *
 * ## ⚠️ 测的是**几何**，不是「画出来好不好看」
 *
 * 这条改动是照 ShortX 抄观感，且**没有任何行为测试会因它变红** ——
 * 形状画错只是「看起来不像」，卡片照常渲染。故本文件锁两样东西：
 *
 * 1. **参数有没有被后人改偏**（12 瓣 / 4.1% 起伏是量出来的，不是随手取的）；
 * 2. **曲线真的落在盒子内**（峰超出盒子会被父布局裁掉，而那是**静默**的：
 *    只有真机上四角被切平才看得出来）。
 *
 * ## 参数来历（对 ShortX 截图做极坐标半径扫描，0.1px 步进）
 *
 * | 量 | 实测 |
 * |---|---|
 * | 花瓣数 | 12（相邻峰间隔恰 30.0°）|
 * | 峰半径 | 38.3 px |
 * | 谷半径 | 34.8 px |
 * | (峰 − 均) / 均 | 0.041 |
 */
class ScallopedBadgeShapeTest {

    /**
     * ⚠️⚠️ **直接调生产代码的纯函数，绝不在这里复刻公式**。
     *
     * 第一版就是在测试里手抄了一份 `mean·(1+a·cos)` —— 于是把生产代码里的
     * 归一化 `/(1+a)` 删掉后**测试照绿**（实测确认），因为被删的是代码里那份、
     * 测试算的是自己那份。这正是本仓库记过的「断言自己写的字面量」形态。
     */
    private fun radiusAt(theta: Float, boxRadius: Float): Float =
        ScallopedBadgeShape.scallopedRadius(theta, boxRadius)

    @Test
    fun `花瓣数锚定 ShortX 实测的 12`() {
        assertEquals(
            "12 是对 ShortX 截图做极坐标扫描数出来的（相邻峰间隔恰 30.0°）；" +
                "改动它等于换一种花边，请先重新测量",
            12,
            ScallopedBadgeShape.SCALLOPS,
        )
    }

    @Test
    fun `起伏幅度锚定实测的 0-041`() {
        assertEquals(0.041f, ScallopedBadgeShape.AMPLITUDE, 0.0005f)
        // 反向锁：幅度太大就不是「曲奇饼干」而是「齿轮/星形」。
        // 实测 ShortX 是 0.041（很含蓄），改到 0.15 以上观感完全不同。
        assertTrue(
            "幅度 > 0.08 会从「饼干花边」变成「齿轮」，超出 ShortX 的观感",
            ScallopedBadgeShape.AMPLITUDE < 0.08f,
        )
    }

    @Test
    fun `曲线始终落在盒子内 —— 峰不超出、谷不明显缩水`() {
        val boxRadius = 50f
        var maxR = 0f
        var minR = Float.MAX_VALUE
        val steps = ScallopedBadgeShape.SCALLOPS * 120
        for (i in 0 until steps) {
            val r = radiusAt((2.0 * Math.PI * i / steps).toFloat(), boxRadius)
            if (r > maxR) maxR = r
            if (r < minR) minR = r
        }
        // ⚠️ 这是**必须做归一化**的原因：直接用 r = R·(1 + a·cos) 会让峰达到
        //    R·1.041 —— 超出 4.1%，徽章四周被父 Row 裁平，而这是静默的。
        assertTrue(
            "峰半径 $maxR 超出盒子半径 $boxRadius（形状会被父布局裁掉）。" +
                "实现里必须按 1/(1+amplitude) 归一化",
            maxR <= boxRadius + 0.01f,
        )
        assertTrue("峰应贴着盒子边缘（否则徽章会显得比设计小）", abs(maxR - boxRadius) < 0.1f)
        assertTrue(
            "谷不应缩太多 —— (1−a)/(1+a) = 0.921 倍是实测比例",
            abs(minR / boxRadius - (1f - ScallopedBadgeShape.AMPLITUDE) / (1f + ScallopedBadgeShape.AMPLITUDE)) < 0.01f,
        )
    }

    @Test
    fun `退化尺寸不抛异常`() {
        // 徽章尺寸由三档密度决定，将来也可能加更窄的档；半径为 0 时
        // createOutline 不能崩（真机上会是「卡片整块空白」而不是报错）。
        val shape = ScallopedBadgeShape()
        val outline = shape.createOutline(
            androidx.compose.ui.geometry.Size(0f, 0f),
            androidx.compose.ui.unit.LayoutDirection.Ltr,
            androidx.compose.ui.unit.Density(1f),
        )
        assertNotNull(outline)
    }

    @Test
    fun `非正方形盒子按短边取半径不会溢出`() {
        // 三个密度的 iconBox 都是正方形，但复用形状的地方可能不是
        // （编辑器预览就是 40dp 卡片里塞 40dp）。短边取值保证不会溢出窄的那一边。
        val w = 40f
        val h = 24f
        val boxRadius = min(w, h) / 2f
        val maxX = (0 until 360).maxOf { i ->
            radiusAt(Math.toRadians(i.toDouble()).toFloat(), boxRadius)
        }
        assertTrue("窄边方向的峰不应超出盒子", maxX <= boxRadius + 0.01f)
    }

    // ------------------------------------------------------------------
    // 接线：形状必须真的挂在两个调用点上
    // ------------------------------------------------------------------

    @Test
    fun `两张卡片的图标徽章都用曲奇形状`() {
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertEquals(
            "两个图标徽章（列表模式 WorkflowCard + 瀑布流 WorkflowCompactCard）" +
                "都必须用 ScallopedBadgeShape；少一个 = 另一种视图还是圆角矩形",
            2,
            SourceScan.countOccurrences(source, "remember { ScallopedBadgeShape() }"),
        )
        assertFalse(
            "徽章不应再是 RoundedCornerShape —— 那正是要替换掉的形态",
            source.contains("shape = RoundedCornerShape(14.dp)") ||
                source.contains("shape = RoundedCornerShape(13.dp)"),
        )
    }

    @Test
    fun `源码扫描不是空转`() {
        val source = SourceScan.stripped(WORKFLOW_LIST_SCREEN)
        assertTrue(source.length > 20_000)
        assertTrue(source.contains("ScallopedBadgeShape"))
    }

    private companion object {
        /** ⚠️ 路径相对 `app/`（Gradle 默认测试工作目录），见 `SourceScan`。 */
        const val WORKFLOW_LIST_SCREEN =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
    }
}
