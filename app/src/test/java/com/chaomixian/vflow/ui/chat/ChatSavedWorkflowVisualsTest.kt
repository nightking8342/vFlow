// 文件: test/java/com/chaomixian/vflow/ui/chat/ChatSavedWorkflowVisualsTest.kt
package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AI 保存的工作流**也会取随机主题色**（与编辑器「新建工作流」口径一致）。
 *
 * ## 为什么需要这条测试
 *
 * `Workflow` 的 `cardThemeColor` / `cardIconRes` 有默认值
 * （[com.chaomixian.vflow.core.workflow.WorkflowVisuals.DEFAULT_THEME_COLOR_HEX]），
 * 所以 AI 侧**什么都不传就能编译通过、能存盘、能显示** —— 只是所有 AI 建的工作流
 * 卡片长得一模一样（都是那个默认蓝）。这是一种「改错了不报错、只静默变差」的形态：
 * 没有任何行为测试会因为「忘了取随机色」而变红，故只能用源码扫描锚定。
 *
 * 两条入参路径都要覆盖，它们**互不调用**：
 * - `buildWorkflowForSave(...)`：`saveTemporaryWorkflow`（把临时工作流存为正式工作流）；
 * - `prepareSaveWorkflow(...)`：`vflow_agent_save_workflow` 工具本身。
 *
 * ⚠️ 刻意**不**改 `Workflow` 的字段默认值 —— 那样会让导入 / 远程 API / 备份恢复
 * 等所有构造点一起改掉取色语义，而它们各自有既定的期望（导入保留原色、API 随机见
 * `WorkflowHandler.kt`）。正确做法是**在这两处显式赋值**。
 */
class ChatSavedWorkflowVisualsTest {

    private val executorFile = "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt"

    private fun bodyOf(signature: String): String {
        val source = SourceScan.stripped(executorFile)
        val body = SourceScan.functionBody(source, signature)
        requireNotNull(body) { "找不到函数体：$signature" }

        // 防空转：剥注释剥过头 / 大括号配对断了都会拿到极小或空的一段，
        // 那样下面的 contains 断言会恒假，看起来像「功能没实现」。
        assertTrue("函数体异常短，疑似扫描失败：$signature", body.lines().size > 10)
        return body
    }

    @Test
    fun `saving a temporary workflow assigns a random theme color`() {
        val body = bodyOf("fun buildWorkflowForSave(")

        assertTrue(
            "buildWorkflowForSave 必须显式取随机主题色，否则存下的工作流用 Workflow 默认色",
            body.contains("cardThemeColor = WorkflowVisuals.randomThemeColorHex()"),
        )
        assertTrue(
            "同时要显式给默认图标，与编辑器的取值口径保持一致",
            body.contains("cardIconRes = WorkflowVisuals.defaultIconResName()"),
        )
    }

    @Test
    fun `the save workflow tool assigns a random theme color`() {
        val body = bodyOf("private fun prepareSaveWorkflow(")

        assertTrue(
            "prepareSaveWorkflow 必须显式取随机主题色，否则存下的工作流用 Workflow 默认色",
            body.contains("cardThemeColor = WorkflowVisuals.randomThemeColorHex()"),
        )
        assertTrue(
            "同时要显式给默认图标，与编辑器的取值口径保持一致",
            body.contains("cardIconRes = WorkflowVisuals.defaultIconResName()"),
        )
    }

    @Test
    fun `the executor imports WorkflowVisuals`() {
        // 顺带锁住 import —— 少了它编译不过（这条只是让人一眼看出依赖在哪）。
        val source = SourceScan.stripped(executorFile)
        assertTrue(
            "ChatAgentModuleExecutor 需要 import WorkflowVisuals",
            source.contains("import com.chaomixian.vflow.core.workflow.WorkflowVisuals"),
        )
    }
}
