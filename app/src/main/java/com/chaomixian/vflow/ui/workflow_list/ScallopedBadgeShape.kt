package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.cos
import kotlin.math.min

/**
 * 卡片左上角**图标徽章**的「曲奇饼干」花边形状（参考 ShortX）。
 *
 * ## 几何是量出来的，不是估的
 *
 * 方法：在 ShortX 截图里对徽章做**极坐标半径扫描**（`r(θ)`，0.1px 步进），
 * 再对半径序列做极值计数。实测（截图 392×452、徽章 77×77px）：
 *
 * | 量 | 值 |
 * |---|---|
 * | 花瓣数 | **12**（相邻峰间隔恰好 30.0°）|
 * | 峰半径 | 38.3 px |
 * | 谷半径 | 34.8 px |
 * | 平均半径 | 36.8 px |
 * | (峰 − 均) / 均 | **0.041** |
 * | 峰/谷 | 0.909 |
 *
 * 半径剖面是**平滑正弦**而非尖角星形（自谷到峰 15° 内单调单调上升、峰附近
 * 有约 19% 的平台区），故用余弦波而不是「圆 + 尖角」拼接。
 *
 * ## 两条实现上的注意
 *
 * ⚠️ **必须把波形缩放在盒子内**：直接用 `r·(1 + a·cos)` 会让**峰半径超出**
 * `size/2`，徽章的四角会被父布局裁掉（父 `Row` 只有 `iconBox` 那么大）。
 * 故按 `1 / (1 + a)` 归一化，使峰恰好落在盒子边缘。
 *
 * ⚠️ **采样密度不需要跟波形频率绑定**：每瓣 12 个采样点（共 144 点）已经足够
 * 平滑 —— 这是把「实时计算」简化成「每帧重算一次性 Path」的前提。徽章很小
 * （32~40dp），再多采样肉眼无法分辨。
 */
class ScallopedBadgeShape(
    private val scallops: Int = SCALLOPS,
    /** (峰半径 − 平均半径) / 平均半径。 */
    private val amplitude: Float = AMPLITUDE,
) : Shape {

    private val path = Path()

    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline {
        val radius = min(size.width, size.height) / 2f
        if (radius <= 0f) return Outline.Generic(Path())
        val cx = size.width / 2f
        val cy = size.height / 2f
        val steps = scallops * SAMPLES_PER_SCALLOP

        path.reset()
        for (i in 0 until steps) {
            val theta = 2f * Math.PI.toFloat() * i / steps
            val r = scallopedRadius(theta, radius, scallops, amplitude)
            val x = cx + r * cos(theta)
            val y = cy + r * kotlin.math.sin(theta)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return Outline.Generic(path)
    }

    companion object {
        /** 花瓣数：ShortX 实测相邻峰间隔 30.0°。 */
        const val SCALLOPS = 12

        /** 起伏幅度：(峰 − 均) / 均，ShortX 实测 0.041。 */
        const val AMPLITUDE = 0.041f

        private const val SAMPLES_PER_SCALLOP = 12

        /**
         * 极坐标半径：`r(θ) = mean · (1 + a·cos(n·θ))`，**已归一化**。
         *
         * ⚠️⚠️ **必须抽成纯函数**，不能让测试自己复刻公式 ——
         * 本仓库记过这条教训：测试里手抄一份公式，生产代码改坏了测试照样绿
         * （本函数就是从 `createOutline` 里提出来才修好那条反证的）。
         * 它同时是「不依赖 `android.graphics.Path`」的：纯 JVM 单测里
         * `android.graphics` 是 stub，碰不到真的 `Path`。
         *
         * ⚠️ **归一化是必需的**：不除 `(1 + a)` 的话峰半径会达到 `R·1.041`，
         * 超出盒子 4.1%，徽章四周被父 `Row` 裁平 —— 而这是**静默**的
         * （只有真机上四角被切才看得出来）。
         *
         * @param boxRadius 盒子短边的一半（峰恰好落在这个半径上）
         */
        fun scallopedRadius(
            theta: Float,
            boxRadius: Float,
            scallops: Int = SCALLOPS,
            amplitude: Float = AMPLITUDE,
        ): Float {
            val mean = boxRadius / (1f + amplitude)
            return mean * (1f + amplitude * cos(scallops * theta))
        }
    }
}
