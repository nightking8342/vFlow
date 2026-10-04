package com.chaomixian.vflow.ui.workflow_editor

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.module.data.LogModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「全部输出」这条**跨文件契约**的接线锚定。
 *
 * ## ⚠️ 为什么必须扫源码
 *
 * 这条功能由**两个文件各写一半**、且两边都不知道对方存在：
 *
 * | 角色 | 文件 | 内容 |
 * |---|---|---|
 * | 提供方（选择器） | `WorkflowEditorMagicVariableCatalogBuilder` | 为 `vflow.data.log` 的 `content` 多列一项 `{{<步骤id>}}` |
 * | 消费者（解析） | `LogModule`（`vflow.data.log`） | 把裸步骤 id 展开成整表 |
 *
 * 两边脱节的后果是**静默**的：
 *
 * - **只改选择器**（多列一项、解析没做）⇒ 用户选了一个**点了没反应**的选项
 *   （裸 id 在常规解析里是空值），比不给这个选项更糟。
 * - **只改解析**（做了整表、选择器不列）⇒ 功能存在但**用户发现不了**
 *   （步骤 id 是 UUID，编辑器里根本不显示，没人会手输）。
 *
 * ⚠️ **为什么不能写成行为测试**：真正的链路是
 * `EditorMoreOptionsSheet`（Android Fragment）→ `ComposeView` → 选择器 →
 * `LogModule.execute` —— 前三段在纯 JVM 里起不来。
 * 而「构造一个 item 再断言它的字段」等于**把要测的那句抄一遍**
 * （改坏了也不会红，本仓库已两次踩过同形坑）。
 */
class WholeStepOutputContractTest {

    // ── 提供方：选择器侧 ────────────────────────────────────────

    @Test
    fun `the picker offers the item only for the log module's content input`() {
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/workflow_editor/" +
                "WorkflowEditorMagicVariableCatalogBuilder.kt"
        )
        // 判据必须同时含模块 id 与输入 id —— 少任一个都会把这一项泄漏到别的模块上，
        // 而泄漏的表现是「用户选了却没反应」。
        assertTrue(
            "选择器没有把「全部输出」限定到日志模块的 content 上",
            source.contains("moduleId == LOG_MODULE_ID && targetInputId == LOG_CONTENT_INPUT_ID"),
        )
        assertTrue("未找到常量 LOG_MODULE_ID", source.contains("const val LOG_MODULE_ID = "))
        assertTrue("未找到常量 LOG_CONTENT_INPUT_ID", source.contains("const val LOG_CONTENT_INPUT_ID = "))
        // 防空转：剥注释后代码行数不能只剩个位数
        assertTrue("截取到的源码过短（断言在空转）", source.count { it == '\n' } > 100)
    }

    @Test
    fun `the item reference is a bare step id`() {
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/workflow_editor/" +
                "WorkflowEditorMagicVariableCatalogBuilder.kt"
        )
        // ⚠️ 必须是**裸写**（不含 `.outputs` 之类）—— 加任何后缀都会让 `LogModule` 的
        //    `path.size == 1` 判据失效，退回成普通魔法变量（= 空值）。
        assertTrue(
            "「全部输出」的引用必须是裸步骤 id（`{{<id>}}`）",
            source.contains("variableReference = \"{{\${step.id}}}\""),
        )
        assertFalse(
            "「全部输出」的引用被加了后缀 —— 那会让整表展开失效",
            source.contains("{{\${step.id}.outputs}}") ||
                source.contains("{{\${step.id}.all}}"),
        )
    }

    // ── 消费者：解析侧 ─────────────────────────────────────────

    @Test
    fun `the log module still implements the whole-step expansion`() {
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/workflow/module/data/LogModule.kt"
        )
        // 消费者侧的最小判据：裸 id 的判据（path.size == 1 + 在 stepOutputs 里）
        // 与整表渲染都还在。
        assertTrue("LogModule 不再判「单段路径」", source.contains("path.size != 1"))
        assertTrue("LogModule 不再查 stepOutputs", source.contains("stepOutputs.containsKey"))
        assertTrue("LogModule 不再渲染整表", source.contains("VDictionary(outputs)"))
    }

    @Test
    fun `the picker constants match the registry id of the log module`() {
        // ⚠️ 字符串字面量与模块的 `id` 必须一致 —— 这是唯一能机器化核对的连接点
        //    （选择器用字面量、模块用 `override val id`）。
        val source = SourceScan.file(
            "src/main/java/com/chaomixian/vflow/ui/workflow_editor/" +
                "WorkflowEditorMagicVariableCatalogBuilder.kt"
        ).readText()
        val declared = Regex("""const val LOG_MODULE_ID = "([^"]+)"""")
            .find(source)?.groupValues?.get(1)
        assertEquals(
            "选择器里的模块 id 与 LogModule.id 不一致 —— 选择器会一个选项都不给",
            LogModule().id,
            declared,
        )
    }

    // ── 解析侧专有语义没被搬进全局解析器 ────────────────────────

    @Test
    fun `the variable resolver still does not expand a bare step id`() {
        // ⚠️ 反向锁：有人可能图省事把「裸 id ⇒ 整表」挪进 `VariableResolver`
        //    （那样所有模块都自动支持）。**不能这么做** —— 它会全局改变语义：
        //    既有工作流里裸写步骤 id 的地方会从「空值」变成「一整表」，
        //    且无法回滚。故这条语义必须留在 `LogModule` 内。
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/execution/VariableResolver.kt"
        )
        assertFalse(
            "VariableResolver 里出现了整表返回 —— 那会把裸写 id 变成全局语义",
            source.contains("return context.stepOutputs"),
        )
    }
}
