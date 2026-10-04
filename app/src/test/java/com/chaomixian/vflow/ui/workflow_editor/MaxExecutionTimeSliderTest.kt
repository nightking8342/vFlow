// 文件: test/java/com/chaomixian/vflow/ui/workflow_editor/MaxExecutionTimeSliderTest.kt
package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「更多选项」面板的最大执行时长滑块：上界适配 + 接线锚定。
 *
 * ## 为什么要两层测试
 *
 * 纯函数只证明**算法**对，证明不了**调用点真的调了它**。本仓库在这上面
 * 踩过三次（`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、
 * `XposedDiagnostics.messageFor` 零调用点、`CapabilityRuntime` 那条翻面断言），
 * 而这次的失败模式与之同形：`maxExecutionTimeSliderUpperBound` 写过但没接上，
 * 滑块照旧越界崩溃，且**纯函数测试全绿**。
 */
class MaxExecutionTimeSliderTest {

    private val uiFile = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/EditorMoreOptionsSheet.kt"
    private val layoutFile = "src/main/res/layout/sheet_editor_more_options.xml"

    // ════════════════ 1. 上界推导（纯函数） ════════════════

    @Test
    fun `values within the layout range keep the base upper bound`() {
        // ⚠️ 这条是「不改变既有行为」的锁：无谓抬高上界会让普通工作流的
        // 刻度变粗（120 秒范围被压成轨道上的一小段），那是回归。
        assertEquals(120f, maxExecutionTimeSliderUpperBound(60, 120f, 5f), 0f)
        assertEquals(120f, maxExecutionTimeSliderUpperBound(120, 120f, 5f), 0f)
        assertEquals(120f, maxExecutionTimeSliderUpperBound(0, 120f, 5f), 0f)
        assertEquals(120f, maxExecutionTimeSliderUpperBound(-5, 120f, 5f), 0f)
    }

    @Test
    fun `a value beyond the layout range raises the upper bound to cover it`() {
        // 本改动存在的理由：AI 允许到 3600，而布局写死 120。
        assertEquals(300f, maxExecutionTimeSliderUpperBound(300, 120f, 5f), 0f)
        assertEquals(3600f, maxExecutionTimeSliderUpperBound(3600, 120f, 5f), 0f)
    }

    @Test
    fun `the upper bound is rounded up so it is never below the value`() {
        // ⚠️⚠️ 反向锁：截断（302 / 5 = 60.4 → 60 → 300）会让上界**落在实际值之下**，
        // 于是刚修好的越界异常原样复发，只是换了个值域 —— 且看起来"改过了"。
        assertEquals(305f, maxExecutionTimeSliderUpperBound(302, 120f, 5f), 0f)
        assertEquals(365f, maxExecutionTimeSliderUpperBound(361, 120f, 5f), 0f)
        assertEquals(125f, maxExecutionTimeSliderUpperBound(121, 120f, 5f), 0f)

        // 不变量：任何输入下，上界都必须 >= 实际值（否则 BaseSlider 的
        // validateValues 会抛 IllegalStateException —— 就是本次要修的崩溃）。
        (121..3600 step 7).forEach { seconds ->
            val upper = maxExecutionTimeSliderUpperBound(seconds, 120f, 5f)
            assertTrue(
                "上界 $upper 小于实际值 $seconds",
                upper >= seconds.toFloat(),
            )
        }
    }

    @Test
    fun `the raised upper bound lands on the step size grid`() {
        // ⚠️ 抬高上界会引入一条**新**风险，与本次要修的崩溃同一族：
        // `BaseSlider.validateStepSize` 要求 `valueTo` 落在 `valueFrom→valueTo` 的
        // stepSize 格点上（`valueLandsOnTick(valueTo)`，已 javap 核实）。
        // 只满足「上界 >= 实际值」不够 —— 123 抬成 125 才对，124.9 之类会抛
        // "The stepSize(5.0) must be 0, or a factor of the valueFrom(0.0)-valueTo(...) range"。
        //
        // ⚠️ 反过来：**实际值本身不必落在格点上**（121 配 stepSize=5 是合法的），
        // 只有 `valueTo` 有这条约束 —— 别把测试写得比库更严。
        val step = 5f
        (121..3600 step 13).forEach { seconds ->
            val upper = maxExecutionTimeSliderUpperBound(seconds, 120f, step)
            assertEquals(
                "上界 $upper 不在 $step 的整数倍上（会命中 validateStepSize）",
                0.0,
                upper.toDouble() % step,
                1e-6,
            )
        }
    }

    @Test
    fun `degenerate bounds do not divide by zero`() {
        // 布局被改成 valueTo="0" / stepSize="0" 时不抛异常（此时滑块本就不可用，
        // 由 BaseSlider 自己拒绝；这里只保证不因除零而崩）。
        assertEquals(0f, maxExecutionTimeSliderUpperBound(300, 0f, 5f), 0f)
        assertEquals(-1f, maxExecutionTimeSliderUpperBound(300, -1f, 5f), 0f)
        assertEquals(300f, maxExecutionTimeSliderUpperBound(300, 120f, 0f), 0f)
    }

    // ════════════════ 2. 接线锚定（源码扫描） ════════════════

    @Test
    fun `the slider binds through the helper instead of assigning the raw value`() {
        // ⚠️ 剥注释后再断言：本改动在源码里留了大段解释性 KDoc，
        // 只做 `contains` 的话「把调用删掉、只留注释」照样绿。
        val source = SourceScan.stripped(uiFile)

        assertTrue(
            "EditorMoreOptionsSheet 未调用 maxExecutionTimeSliderUpperBound",
            source.contains("maxExecutionTimeSliderUpperBound("),
        )
        // ⚠️ 反向断言：光看「有没有调」不够 —— 若把调用写进 `?: run {}` 分支
        // （只处理「没有超时限制」那种情形），有值的那条路径照样越界崩。
        // 故断言赋值语句与上界抬高在**同一个绑定块**里（见下一条测试）。
    }

    @Test
    fun `the call site passes the view's own bounds rather than hardcoded numbers`() {
        // ⚠️ 存在的理由：把 `baseUpperBound = 120` / `stepSize = 5` 硬编码进去，
        // 布局一改（如上限提到 300）两处就静默脱节 —— 而源码扫描看不出「值对不对」。
        val body = SourceScan.functionBody(
            SourceScan.stripped(uiFile),
            "wf.maxExecutionTime?.let { maxTime ->",
        )
        assertTrue("未找到最大执行时长的绑定块", body != null)
        val block = body!!

        // 防空转：块内必须有真实代码行
        assertTrue("绑定块内没有代码（断言在空转）", block.count { it == '\n' } > 3)

        assertTrue(
            "上界参数未取自视图（layout 改了会脱节）",
            block.contains("baseUpperBound = sliderMaxExecutionTime.valueTo"),
        )
        assertTrue(
            "步长参数未取自视图（layout 改了会脱节）",
            block.contains("stepSize = sliderMaxExecutionTime.stepSize"),
        )
        assertTrue(
            "绑定块内没有把上界算出来（断言在空转）",
            block.contains("valueTo = maxExecutionTimeSliderUpperBound("),
        )
        // ⚠️ 刻意**不**断言「抬高上界必须排在赋值之前」——`BaseSlider` 的校验是
        // 延迟到 `onDraw` 才做的（`setValue` 只置 `dirtyConfig`，已 javap 核实），
        // 两种顺序都合法。写了会变成一条「看着更严、实则无据」的断言。
    }

    @Test
    fun `the layout still caps the slider at 120 seconds`() {
        // 本改动的策略是「超范围时才抬高上界」，而不是「把上限提到 3600」——
        // 后者会让 stepSize=5 时出现 720 档、几乎没法拖。
        // 这条断言锁住布局本身没被顺手改掉。
        val layout = SourceScan.file(layoutFile).readText()
        val sliderBlock = layout.substringAfter("id=\"@+id/slider_max_execution_time\"", "")

        assertTrue("滑块布局段缺失", sliderBlock.isNotBlank())
        assertTrue(
            "布局的 valueTo 已不是 120 —— 若是有意改动，请同步本测试与上界推导的说明",
            sliderBlock.contains("android:valueTo=\"120\""),
        )
        assertTrue(
            "布局的 stepSize 已不是 5 —— 上界推导依赖它",
            sliderBlock.contains("android:stepSize=\"5\""),
        )
    }
}
