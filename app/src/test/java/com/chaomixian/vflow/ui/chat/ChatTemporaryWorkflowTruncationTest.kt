// 文件: test/java/com/chaomixian/vflow/ui/chat/ChatTemporaryWorkflowTruncationTest.kt
package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 临时工作流的输出**两道截断都关掉了**（外层 1600 + 日志段自身）。
 *
 * ## 为什么需要这条测试
 *
 * `ChatToolResultInputFormatter` 默认把工具输出砍到 [CHAT_MAX_TOOL_RESULT_INPUT_CHARS]
 * 字符，**且砍的是尾部**。而临时工作流的正文顺序是
 * 「结论 → 失败摘要 → Steps 条数 → `Execution log`」——
 * 被砍掉的那一段恰好包含 `E/` 失败行与模块自报的进度。
 *
 * 截断的后果不是「少看几行」，而是**失败原因整段消失**：模型只看到
 * `failed at step N`，拿不到任何可据以自愈的信息。
 *
 * 外层关掉之后，**日志段自身的 4000/8000 也一并去掉了** —— 否则那一道会接着砍。
 * 代价是输出无上界（常见单次执行约 3.2k 字符，100 轮循环可达几万字），已记录在
 * `buildTemporaryWorkflowOutputText` 的注释里。
 *
 * ## 为什么是源码扫描
 *
 * `ChatAgentToolRegistry` 的构造要 `ModuleRegistry.initialize(appContext)`，
 * 纯 JVM 单测起不来（与 `ChatAgentToolingTest` 里那些用 `sampleTool(...)`
 * 手搓定义的原因相同 —— 但手搓等于**把要测的那句抄一遍**，改坏了也不会红）。
 * 故只能扫源码，形态照 `AgentErrorDialogWiringTest` / `CoreDexFingerprintTest`。
 */
class ChatTemporaryWorkflowTruncationTest {

    private val registryFile = "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentToolRegistry.kt"

    @Test
    fun `the temporary workflow tool opts out of truncation`() {
        val source = SourceScan.stripped(registryFile)
        val body = SourceScan.functionBody(source, "private fun buildTemporaryWorkflowToolDefinition()")

        assertTrue("未找到 buildTemporaryWorkflowToolDefinition", body != null)
        val block = body!!

        // 防空转：块内必须有真实代码行（剥注释剥过头会让下面的断言恒真/恒假）
        assertTrue("函数体被截空（断言在空转）", block.count { it == '\n' } > 10)
        assertTrue(
            "截到的不是临时工作流的定义块",
            block.contains("CHAT_TEMPORARY_WORKFLOW_TOOL_NAME"),
        )

        assertTrue(
            "临时工作流仍是可截断的 —— 输出尾部的 Execution log 会被 1600 字符上限砍掉",
            block.contains("truncatable = false"),
        )
    }

    @Test
    fun `the truncation default stays on for every other tool`() {
        // ⚠️ 反向锁：不要把 `ChatAgentToolDefinition.truncatable` 的**默认值**改成 false。
        // 那个默认值是「机器 dump、结构重复、长尾无信息量」输出的唯一防线
        // （典型是 observe_ui 的无障碍节点树），改了会让它们全量灌进上下文。
        // 正确做法是本模块**逐个显式豁免**，不是动默认值。
        val declarations = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentToolRegistry.kt"
        )
        // ⚠️ 逐行取声明，**不用 `functionBody`** —— `data class` 是主构造参数、没有 `{}`，
        // `functionBody` 会一路找到后文某个函数的 `{`、截出一大段无关代码。
        val defaultLine = declarations.lineSequence()
            .firstOrNull { it.trimStart().startsWith("val truncatable") }

        assertTrue("未找到 truncatable 的默认值声明", defaultLine != null)
        assertTrue(
            "truncatable 的默认值不再是 true —— 全体工具会失去截断兜底",
            defaultLine!!.contains("= true"),
        )
    }

    @Test
    fun `neither truncation gate is left in the temporary workflow path`() {
        // ⚠️⚠️ 两道截断**都关掉了**（外层 1600 + 日志段自身）。这条是**反向锁**：
        //     谁要把任一道加回来，这里必须变红。
        //
        // 为什么只能靠源码扫描：行为测试分不出「日志段无界」与「日志段被截在一个很大的数」
        // （测试日志再长也超不过那个数），只有扫源码才能确认那道闸真的不在。
        val patchFile = SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatMessagePatch.kt")

        // ① 日志段的预算常量已彻底删除（不是改成更大的数）
        val leftoverConst = patchFile.lineSequence()
            .firstOrNull { it.trimStart().startsWith("internal const val LOG_CHAR_LIMIT") }
        assertTrue(
            "LOG_CHAR_LIMIT 又被加回来了 —— 日志段应当无界",
            leftoverConst == null,
        )

        // ② 日志段是裸 append，不再过任何截断函数
        val body = SourceScan.functionBody(
            patchFile, "internal fun buildTemporaryWorkflowOutputText(",
        )
        assertTrue("未找到 buildTemporaryWorkflowOutputText", body != null)
        assertFalse(
            "日志段又走了截断函数",
            body!!.contains("truncateMultiline"),
        )
        assertTrue(
            "日志段应当直接 append 原日志",
            body.contains("append(trimmedLog)"),
        )
        // ③ 截断函数本身也不该再存在于这个文件（它已无调用者）
        assertFalse(
            "truncateMultiline 已无调用者，不应留在文件里",
            patchFile.contains("fun truncateMultiline("),
        )

        // ④ 日志段之外不再有别的按【字符】截断的段（Steps 已改成只给条数）
        assertTrue(
            "Steps 段应只输出条数，不应再逐条列清单",
            body.contains("append(stepCount)"),
        )
        // ⚠️ 判据要针对**代码**（清单参数与分页逻辑），不要裸测 `"more steps"` ——
        //    函数体里有英文散文 `"one or more steps failed and were skipped."`，
        //    裸子串会命中它、让断言恒红（实现期实际踩到）。
        assertFalse("清单参数应已删除", body.contains("stepDescriptions"))
        assertFalse("分页逻辑应已删除", body.contains("maxSteps") || body.contains(".take("))
    }
}
