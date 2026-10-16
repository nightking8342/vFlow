// 文件: test/java/com/chaomixian/vflow/ui/workflow_editor/AiModelConfigSelectorVisibilityTest.kt
package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **「配置来源」选择器的可见性契约**（源码扫描型；真机反馈修正 (a)(b)）。
 *
 * ## 修的是什么
 *
 * 三个 AI 模块共用的这块编辑区原先有**两颗按钮**：「选择」（`selectButton`，outlined）
 * 与「手动配置」（`manualButton`，borderless，文案与「配置来源」里的 Chip 一模一样）。
 * 结果是两种模式各错一处：
 *
 * - **(a)** 手动模式下「选择」按钮**照样显示** —— 该按钮只在引用模式有意义
 *   （根因：`refresh()` 里 `selectButton` 只设了 `isEnabled`，**从来没设过 `isVisible`**）；
 * - **(b)** 引用模式下那颗「手动配置」按钮**是多余的** —— 切回手动本来就只需点
 *   「配置来源」里的「手动配置」Chip，两条入口做同一件事。
 *
 * ## 为什么必须是源码扫描
 *
 * `AiModelConfigSelectorView` 是 `View`（不是 Compose），构造就要 `Context` +
 * `ChatPresetRepository`（`SharedPreferences`）⇒ 纯 JVM 单测起不来、也不打算引 Robolectric。
 * 而这两条缺陷的形态都是**「漏了一行赋值 / 多了一颗控件」**：不报错、不崩溃、
 * 编译照过，**只有真机肉眼能看出来**（本仓库的 `SwitchEditorSheetWiringTest` 是同类先例）。
 *
 * ## ⚠️ 断言只锚「这次新增/删除的表达式」
 *
 * `selectButton.isVisible = isReference` 是**本次新增**的表达式；
 * `manualButton` 是**本次删除**的标识符。
 * 不去锚「改造前就存在」的行（如 `selectButton.isEnabled = ...`），否则断言会恒真。
 *
 * ## ⚠️ 必做反证（三条都实测过变红再改回，记录见交付说明）
 *
 * 1. 从 `refresh()` 里删掉 `selectButton.isVisible = isReference`
 *    ⇒ [selectButton 只在引用模式显示] **变红**；
 * 2. 把那行挪到 `if (!isReference) return` **之后**
 *    ⇒ 同一个用例**变红**（手动模式下执行不到 ⇒ 按钮残留上一次的可见性）；
 * 3. 把 `manualButton` 加回来（连 `borderlessButtonStyle` 一起）
 *    ⇒ [manualButton 已删除] **变红**。
 */
class AiModelConfigSelectorVisibilityTest {

    /** ⚠️ 测试工作目录是 `app/`（Gradle 默认）。 */
    private val view =
        "src/main/java/com/chaomixian/vflow/ui/workflow_editor/AiModelConfigSelectorView.kt"

    /** 剥注释后的源码 —— 否则 KDoc 里提到的标识符会把「已删除」断言照绿。 */
    private val source: String = SourceScan.stripped(view)

    /**
     * `refresh()` 的函数体（大括号配对截取）。
     *
     * ⚠️ 找不到**直接失败**：函数改名/被删时不能退化成「空串上的恒真断言」。
     */
    private fun refreshBody(): String =
        SourceScan.functionBody(source, "private fun refresh()")
            ?: error(
                "在 `AiModelConfigSelectorView.kt` 里找不到 `private fun refresh()` —— " +
                    "函数被改名或扫描失效，本文件的断言已失去意义",
            )

    /** 某一行在 `refresh()` 里的字符下标（不存在返回 -1）。 */
    private fun indexInRefresh(line: String): Int = refreshBody().indexOf(line)

    /** `refresh()` 里「手动模式早退」那一行的下标（不存在直接失败）。 */
    private fun earlyReturnIndex(): Int {
        val at = indexInRefresh("if (!isReference) return")
        assertTrue(
            "`refresh()` 里的 `if (!isReference) return` 没了 —— 本文件的「早退之前」判据失效",
            at >= 0,
        )
        return at
    }

    @Test
    fun `selectButton 只在引用模式显示`() {
        val at = indexInRefresh("selectButton.isVisible = isReference")
        assertTrue(
            "`refresh()` 里缺少 `selectButton.isVisible = isReference` —— " +
                "「选择」按钮会在**手动模式**下也显示出来（真机反馈 (a)）",
            at >= 0,
        )
        assertTrue(
            "`selectButton.isVisible = isReference` 落在了 `if (!isReference) return` **之后** —— " +
                "手动模式下这一行执行不到，按钮会残留上一次（引用模式）的可见性",
            at < earlyReturnIndex(),
        )
    }

    @Test
    fun `manualButton 已删除（切回手动只走配置来源 Chip）`() {
        assertTrue(
            "`AiModelConfigSelectorView.kt` 里又出现了 `manualButton` —— " +
                "它与「配置来源」里的「手动配置」Chip 功能重复，正是这次删掉的那颗多余按钮（真机反馈 (b)）",
            !source.contains("manualButton"),
        )
        assertTrue(
            "`borderlessButtonStyle` 又出现了 —— 那是已删除的 `manualButton` 的样式；" +
                "本文件现在只剩一颗 outlined 的「选择」按钮",
            !source.contains("borderlessButtonStyle"),
        )
    }

    @Test
    fun `其余可见性与使能判据保持，且在手动模式下也会重设`() {
        val guard = earlyReturnIndex()
        listOf(
            "hintText.isVisible = isReference",
            "statusText.isVisible = isReference",
            "selectButton.isEnabled = presets.isNotEmpty()",
        ).forEach { line ->
            val at = indexInRefresh(line)
            assertTrue("`refresh()` 里缺少 `$line`", at >= 0)
            assertTrue(
                "`$line` 落在了 `if (!isReference) return` 之后 —— 手动模式下它执行不到，" +
                    "控件的可见性/使能会残留上一次的值",
                at < guard,
            )
        }
    }
}
