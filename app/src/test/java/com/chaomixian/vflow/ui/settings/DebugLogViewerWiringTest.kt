package com.chaomixian.vflow.ui.settings

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「查看日志」的**接线与数据源一致性**锚定（源码扫描型）。
 *
 * ## ⚠️ 为什么只能扫源码
 *
 * `DebugLogViewerActivity` 是 Android `ComponentActivity`、界面是 Compose ——
 * 纯 JVM 单测起不来。而本改动真正容易失效的地方是两个**跨文件约定**：
 *
 * | 约定 | 破了会怎样 |
 * |---|---|
 * | 与「导出日志」**同数据源**（`DebugLogger.getLogs()`） | 页面上看到的与导出文件里的对不上，而用户恰恰拿这两处互相核对 |
 * | 设置页那一个**连续**按钮网格里的项数与 `SettingsButtonGrid` 的落单规则一致 | 落单按钮的整行宽度算错（三行变两行半），是纯视觉缺陷、无任何报错 |
 */
class DebugLogViewerWiringTest {

    private val viewerPath =
        "src/main/java/com/chaomixian/vflow/ui/settings/DebugLogViewerActivity.kt"
    private val screenPath =
        "src/main/java/com/chaomixian/vflow/ui/settings/SettingsScreen.kt"
    private val routePath =
        "src/main/java/com/chaomixian/vflow/ui/settings/SettingsRoute.kt"

    @Test
    fun `the viewer reads the exact same source as export`() {
        val viewer = SourceScan.stripped(viewerPath)
        val route = SourceScan.stripped(routePath)

        // ⚠️⚠️ **判据必须锚在「初始化那个表达式」上，不能只断言「文件里出现过
        //    `DebugLogger.getLogs()`」** —— 刷新按钮里也有一处，只断言「出现过」的话，
        //    把初始数据源换成别的（例如只读 `logBuffer`）**测试照样绿**。
        //    ⚠️ 这个反证**实际做过**：第一版就是这么写的，改成 `mutableStateOf("")`
        //    后测试**没有变红** ⇒ 才收紧成现在这条。
        assertTrue(
            "查看日志页的初始数据源必须与「导出日志」一致",
            viewer.contains("mutableStateOf(DebugLogger.getLogs())"),
        )
        assertTrue(
            "刷新也必须走同一个数据源（换掉会让页面与刷新后的内容不一致）",
            viewer.contains("{ logs = DebugLogger.getLogs() }"),
        )
        assertTrue(
            "导出路径必须仍然是 DebugLogger.getLogs()",
            route.contains("DebugLogger.getLogs()"),
        )
        // 防空转
        assertTrue("查看日志页源码过短（断言在空转）", viewer.count { it == '\n' } > 50)
    }

    @Test
    fun `the viewer does not silently truncate`() {
        val viewer = SourceScan.stripped(viewerPath)
        // ⚠️ 反向锁：这里加 maxLines / take(n) 会让本页与导出文件不一致，
        //    而「两处互相核对」正是本页存在的理由。
        assertFalse("查看日志页不得对内容做截断（maxLines）", viewer.contains("maxLines"))
        assertFalse("查看日志页不得对内容做截断（take(n)）", viewer.contains(".take("))
        assertTrue(
            "应可全选复制",
            viewer.contains("SelectionContainer"),
        )
    }

    @Test
    fun `wrap toggle drives both softWrap and horizontal scrolling`() {
        val viewer = SourceScan.stripped(viewerPath)
        // ⚠️⚠️ **两处必须同时被 `wrapLines` 驱动**，缺一个都是「点了没反应」：
        //    - 只切 `softWrap` 而横向滚动常开 ⇒ `Text` 按无穷宽测量，
        //      `softWrap` 永远不触发，换行开关看起来完全无效（静默）。
        //    - 只切横向滚动而 `softWrap` 常真 ⇒ 不换行模式下文字被压成一列，
        //      区别只剩「能不能横拖」，用户看不出这是个排版开关。
        assertTrue("换行开关没接到 softWrap 上", viewer.contains("softWrap = wrapLines"))
        assertTrue(
            "横向滚动必须由 wrapLines 反相关地控制（只在「不换行」时开）",
            viewer.contains("if (wrapLines) Modifier") && viewer.contains("horizontalScroll("),
        )
        assertTrue(
            "换行开关默认必须开着（应用日志里长行是常态，不换行会普遍横拖）",
            viewer.contains("mutableStateOf(true)"),
        )
        assertTrue(
            "开关必须能看出当前状态（IconToggleButton，不是普通 IconButton）",
            viewer.contains("IconToggleButton"),
        )
    }

    @Test
    fun `the log buttons live in one contiguous grid`() {
        val screen = SourceScan.file(screenPath).readText()
        // ⚠️ 存在理由：`SettingsButtonGrid` 的「落单按钮独占整行」依赖**这一次调用里**
        //    的项数。把 8 个按钮拆成两个网格，第二个网格里的落单项会按自己的
        //    项数重新算 —— 宽度看起来没问题，但两片卡片之间会多一道缝，
        //    而且「哪些算一组」从此需要人工记住。
        //
        //    判据：调试区里 `SettingsButtonGrid(` 的出现次数必须**恰好 1 次**。
        val debugSection = SourceScan.functionBody(screen, "if (showDebuggingSection) item {")
        assertTrue("未截取到调试区函数体（断言在空转）", debugSection != null)
        val body = debugSection!!
        assertTrue(
            "调试区的按钮网格必须只有一片（拆开会破坏落单按钮的整行规则）",
            body.split("SettingsButtonGrid(").size - 1 == 1,
        )
    }

    @Test
    fun `view logs is wired from the screen through the route to a declared activity`() {
        // 三个环节缺一不可，且**每一环的失败都是静默的**：
        // 屏幕有按钮但 Route 没接线 ⇒ 点了没反应；Route 接线了但 Manifest 没声明
        // ⇒ `ActivityNotFoundException` 崩溃。
        assertTrue(
            "设置页没有渲染「查看日志」按钮",
            SourceScan.stripped(screenPath).contains("viewLogsLabel"),
        )
        assertTrue(
            "SettingsRoute 没有接线 onViewLogs",
            SourceScan.stripped(routePath).contains("DebugLogViewerActivity::class.java"),
        )
        val manifest = SourceScan.file("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "AndroidManifest 没有声明 DebugLogViewerActivity —— 点击会崩",
            manifest.contains(".ui.settings.DebugLogViewerActivity"),
        )
        assertTrue(
            "页面必须不可导出（它展示全量调试日志）",
            Regex(
                """DebugLogViewerActivity"[\s\S]{0,200}?android:exported="false""""
            ).containsMatchIn(manifest),
        )
    }

    @Test
    fun `all three log buttons gate on the logging switch`() {
        // ⚠️ 反向锁：三个日志按钮都只在「启用日志记录」打开时可用。
        //    新增的「查看日志」若漏了 `loggingEnabled`，会在开关关着时
        //    也能点进去、看到一个几乎空的页面（那正是最容易被当成「功能坏了」的场景）。
        val screen = SourceScan.stripped(screenPath)
        assertTrue(screen.contains("viewLogsLabel"))
        assertTrue(
            "每个日志按钮都要带 uiState.loggingEnabled",
            screen.split("uiState.loggingEnabled").size - 1 >= 3,
        )
    }
}
