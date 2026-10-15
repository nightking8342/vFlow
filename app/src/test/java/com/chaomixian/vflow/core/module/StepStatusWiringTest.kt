package com.chaomixian.vflow.core.module

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-3（状态可见）的**接线锚定**测试（源码扫描型）。
 *
 * ⚠️ 为什么不用行为测：本仓库**无 Robolectric**，`BaseModule` / `WorkflowExecutor` /
 * `ChatAgentModuleExecutor` 都起不来。而这条改动的失效方式恰好是
 * 「纯函数测了，但某个调用点没接」—— 三处调用点各有各的静默后果：
 *
 * | 漏接的地方 | 表现 |
 * |---|---|
 * | `BaseModule.getDynamicOutputs` | 编辑器选不到 `success`（胶囊退化成原文） |
 * | `WorkflowExecutor` 成功分支 | 成功时不写 `success` ⇒ `为假` 恒 true ⇒ **分流永远走错** |
 * | `ChatAgentModuleExecutor` 的 schema | 人工能选、AI 看不见（口径分裂） |
 *
 * 每条断言都**剥注释**后再做（`SourceScan.stripped`）。
 */
class StepStatusWiringTest {

    private val baseModule = "src/main/java/com/chaomixian/vflow/core/module/BaseModule.kt"
    private val executor = "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
    private val chatExecutor = "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt"

    @Test
    fun `模块的声明输出统一补上状态输出`() {
        val source = SourceScan.stripped(baseModule)
        assertTrue(
            "BaseModule.getDynamicOutputs 必须经 StepStatusSupport.withSuccessOutput —— " +
                "少了它，变量选择器不会列出 success（胶囊会退化成 {{id.success}} 原文）。",
            source.contains("StepStatusSupport.withSuccessOutput(getOutputs(step))"),
        )
    }

    @Test
    fun `成功分支写 success`() {
        val source = SourceScan.stripped(executor)
        assertTrue(
            "成功分支必须经 StepStatusSupport.withSuccessFlag —— " +
                "少了它，成功时不写 success ⇒ `IF {{id.success}} 为假` 恒真 ⇒ 分流永远走错分支。",
            source.contains("StepStatusSupport.withSuccessFlag(result.outputs)"),
        )
        assertTrue("疑似扫描失败：执行器源码异常短", source.length > 10_000)
    }

    @Test
    fun `AI 侧的模块 schema 与编辑器同口径`() {
        val source = SourceScan.stripped(chatExecutor)
        assertTrue(
            "AI 侧读的是 getOutputs（不是 getDynamicOutputs）⇒ 必须显式接同一个供给层，" +
                "否则模型不知道可以用 {{stepId.success}} 做失败分流。",
            source.contains("StepStatusSupport.withSuccessOutput(module.getOutputs(step))"),
        )
    }

    @Test
    fun `状态输出与静默停止的文案三语齐全`() {
        listOf(
            "src/main/res/values/strings.xml",
            "src/main/res/values-en/strings.xml",
            "src/main/res/values-ja/strings.xml",
        ).forEach { path ->
            val text = SourceScan.file(path).readText()
            assertTrue("$path 缺少 output_common_step_success_name", text.contains("name=\"output_common_step_success_name\""))
            assertTrue(
                "$path 缺少 settings_error_policy_stop_silent（设置页的全局默认策略第 4 项）",
                text.contains("name=\"settings_error_policy_stop_silent\""),
            )
        }
    }
}
