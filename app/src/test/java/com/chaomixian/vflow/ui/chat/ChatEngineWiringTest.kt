package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线测试：`ChatViewModel` 现在真的走引擎**（fork：AI 能力统一 §7.2）。
 *
 * 为什么必须有它：`ChatViewModel` 需要 `Context`（无 Robolectric）⇒ 它的驱动路径
 * **无法直接单测**。而「纯逻辑全绿 ≠ 集成点接上」是本仓库踩过两次的坑
 * （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor` 零调用点）。
 *
 * ## ⚠️ 断言锚在**本次新增**的表达式上
 *
 * `ChatAgentEngine(` / `engine.runRound(` / `ChatHost(` / `engine.prepareToolBatch(` 都是本任务
 * **新加**的；锚在改造前就存在的标识符上（如 `requestAssistantReply`）会**恒真**。
 *
 * ## ⚠️ 扫描前剥注释
 *
 * 本任务的改动在 KDoc 里大段引用 `streamWithRecovery` / `chatClient.streamReply` 这些
 * 「已经搬走的」名字。只做 `contains` 的话，把真实调用留成注释也一样绿 ——
 * 故一律走 [SourceScan.stripped]。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 把 `requestAssistantReply` 改回直接 `chatClient.streamReply(...)`（绕过引擎）⇒
 *    [viewModel_drivesTheEngine] 与 [viewModel_noLongerStreamsOnItsOwn] **变红**；
 * 2. 把 `stopAgent()` 里的 `toolExecutor.buildRejectedResults(` 也改成 `engine.…` ⇒
 *    [viewModel_keepsStopAgentsOwnRejectedResultsCall] **变红**；
 * 3. 删掉三处收尾落盘中的任意一处 ⇒
 *    [viewModel_persistsReferencedArtifactsAtEveryFinalizeExit] **变红**
 *    （与既有 `ChatMultimodalWiringTest` 同口径，两道守卫）；
 * 4. 把引擎里的 `host.toolNames` 过滤删掉 ⇒ [engine_ownsTheLoop] **变红**。
 */
class ChatEngineWiringTest {

    private val viewModelSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatViewModel.kt")
    private val engineSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentEngine.kt")
    private val hostSource =
        SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/chat/AgentHost.kt")

    // ------------------------------------------------------------ 1. 真的走引擎

    @Test
    fun viewModel_drivesTheEngine() {
        assertTrue(
            "VM 必须构造引擎（共享引擎的接线点）",
            viewModelSource.contains("ChatAgentEngine("),
        )
        assertTrue(
            "一轮 LLM 往返必须调 `engine.runRound(`",
            viewModelSource.contains("engine.runRound("),
        )
        assertTrue(
            "工具准备必须走引擎接缝",
            viewModelSource.contains("engine.prepareToolBatch("),
        )
        assertTrue(
            "工具执行必须走引擎接缝",
            viewModelSource.contains("engine.executeToolBatch("),
        )
        assertTrue(
            "拒绝结果必须走引擎接缝",
            viewModelSource.contains("engine.buildRejectedResults("),
        )
        assertTrue(
            "缺权限结果必须走引擎接缝",
            viewModelSource.contains("engine.buildPermissionRequiredResults("),
        )
        assertTrue(
            "审批判据必须问引擎（引擎再问宿主）",
            viewModelSource.contains("engine.decideApproval("),
        )
        assertTrue(
            "chat 宿主必须被显式构造",
            viewModelSource.contains("ChatHost("),
        )
        assertTrue(
            "chat 的工具白名单必须**由注册表派生**（手抄清单会静默少下发工具）",
            viewModelSource.contains(".getTools().map { it.name }"),
        )
    }

    /**
     * **循环真的搬走了**：VM 里不得再有流式收集与重试包装。
     *
     * ⚠️ 这条不是恒真断言 —— 改造前这两个表达式都在 VM 里，现在只剩引擎里有。
     */
    @Test
    fun viewModel_noLongerStreamsOnItsOwn() {
        assertFalse(
            "VM 不得再直接调传输层（那正是「循环搬进引擎」的反面）",
            viewModelSource.contains("chatClient.streamReply("),
        )
        assertFalse(
            "VM 不得再自己包重试（`streamWithRecovery` 已随循环搬进引擎）",
            viewModelSource.contains("streamWithRecovery("),
        )
        assertFalse(
            "VM 不得再自己拼提示词（组装点已搬进引擎）",
            viewModelSource.contains("ChatAgentSkillRouter."),
        )
        assertFalse(
            "错误文案已随循环搬进引擎（VM 里连注释都不该留成活的字符串）",
            viewModelSource.contains("流已结束但没有收到 Completed 事件。"),
        )
    }

    // ------------------------------------------------------------ 2. 引擎真的接管了

    @Test
    fun engine_ownsTheLoop() {
        assertTrue("传输层调用必须在引擎里", engineSource.contains("chatClient.streamReply("))
        assertTrue("重试包装必须在引擎里", engineSource.contains("streamWithRecovery("))
        assertTrue("白名单过滤必须在引擎里", engineSource.contains("host.toolNames"))
        assertTrue("审批必须问宿主", engineSource.contains("host.requestApproval("))
        assertTrue("进度必须推给宿主", engineSource.contains("host.onProgress("))
        assertTrue("收尾必须通知宿主", engineSource.contains("host.onFinish("))
        assertTrue(
            "错误文案必须逐字保留（搬动过程中最容易悄悄改掉的一处）",
            engineSource.contains("流已结束但没有收到 Completed 事件。"),
        )
        assertTrue(
            "id 兜底规则必须仍在（前缀由宿主给）",
            engineSource.contains("request.toolCallIdPrefix"),
        )
    }

    /**
     * 引擎**零 Android 依赖** —— 这是「循环/审批/白名单能被纯 JVM 单测」的前提，
     * 也是本任务最容易被无意破坏的性质（顺手 import 一个 `Context` 就退化）。
     */
    @Test
    fun engine_hasNoAndroidDependencies() {
        assertFalse(
            "引擎不得 import 任何 android.*（否则 AgentEngineRoundTest 之类会当场失效）",
            engineSource.contains("import android."),
        )
        assertFalse(
            "引擎不得用 NonCancellable（宿主 cancel() 必须立刻生效）",
            engineSource.contains("NonCancellable"),
        )
        assertFalse(
            "引擎不得吞 CancellationException",
            engineSource.contains("catch (cancellation: CancellationException)"),
        )
    }

    // ------------------------------------------------------------ 3. 既有行为不变的三处证据

    /**
     * 三处收尾落盘（流式主路径 / 中断 / 失败）+ 定义 = **4 处**。
     *
     * 与既有 `ChatMultimodalWiringTest` 同口径 —— 两道守卫同时盯这一条，
     * 因为它是最容易在重构里被「顺手删掉」的调用。
     */
    @Test
    fun viewModel_persistsReferencedArtifactsAtEveryFinalizeExit() {
        assertEquals(
            "三处收尾出口 + 定义共 4 处；少一处说明有出口漏了落盘",
            4,
            SourceScan.countOccurrences(viewModelSource, "persistReferencedArtifacts("),
        )
    }

    /**
     * **`stopAgent()` 里那次 `toolExecutor.buildRejectedResults(` 必须保持直连**。
     *
     * 理由：它是「停止时把 PENDING/RUNNING 的审批改 REJECTED + 补 tool 结果」这段
     * 状态迁移的一部分，跑在**取消路径**上（此时没有引擎上下文）。
     * 方案明确点名 :364 属「不改」清单 —— 这条断言把它钉住。
     */
    @Test
    fun viewModel_keepsStopAgentsOwnRejectedResultsCall() {
        assertEquals(
            "VM 里应当只剩 `stopAgent` 那一处直连执行器",
            1,
            SourceScan.countOccurrences(viewModelSource, "toolExecutor.buildRejectedResults("),
        )
        val stopAgentBody = SourceScan.functionBody(viewModelSource, "fun stopAgent()")
        assertNotNull("未找到 stopAgent()，断言会空转", stopAgentBody)
        assertTrue(
            "stopAgent() 里必须仍直接调执行器（取消路径不经过引擎）",
            stopAgentBody!!.contains("toolExecutor.buildRejectedResults("),
        )
    }

    /** 审批三件套的公开签名与 `ChatScreen` 的接线点不得变（`ChatScreen` 零 diff）。 */
    @Test
    fun viewModel_keepsTheApprovalEntryPoints() {
        listOf(
            "fun approveToolCalls(messageId: String)",
            "fun rejectToolCalls(messageId: String)",
            "fun rerunToolCalls(messageId: String)",
            "fun onToolPermissionResult(granted: Boolean)",
        ).forEach { signature ->
            assertTrue("审批/权限入口的签名不得变：$signature", viewModelSource.contains(signature))
        }
    }

    // ------------------------------------------------------------ 4. 扫描不空转

    @Test
    fun theScanIsNotVacuous() {
        assertTrue(viewModelSource.contains("class ChatViewModel"))
        assertTrue(engineSource.contains("class ChatAgentEngine"))
        assertTrue(hostSource.contains("interface AgentHost"))
        assertTrue(
            "剥注释剥过头了：VM 的注释长度远大于这个数",
            viewModelSource.length > 30_000,
        )
        assertTrue(
            "剥注释剥过头了：引擎的注释长度远大于这个数",
            engineSource.length > 3_000,
        )
    }
}
