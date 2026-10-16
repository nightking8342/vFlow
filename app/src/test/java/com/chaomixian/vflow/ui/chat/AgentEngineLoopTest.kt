package com.chaomixian.vflow.ui.chat

import java.util.concurrent.TimeUnit
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VString
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * **直线循环 `runLoop`**（工作流宿主的路径；D17 轮次上限 + 审批 + 观察装配）。
 *
 * ⚠️ 本批 `runLoop` **没有生产调用方**（批 4 的 T6 才接 `AgentModule`）⇒
 * 这里用假宿主 + 假工具执行器 + 假观察 provider 覆盖纯逻辑。
 * 「真实截屏/节点树 + 真实模块执行」**未验证**（见交付说明）。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 去掉 wrap-up 轮（到顶直接 `finish(MAX_ROUNDS, round.completion.content)`）⇒
 *    [runLoop_givesTheModelOneWrapUpRoundAtTheLimit] **变红**（请求数 3 → 2）；
 * 2. 把 `Rejected` 分支改成也执行工具 ⇒ [runLoop_doesNotExecuteRejectedToolCalls] **变红**；
 * 3. 把 `Deferred` 分支改成执行工具 ⇒ [runLoop_treatsDeferredAsNotApproved] **变红**；
 * 4. 把 `observe(host.observationMode, …)` 写死成 `NODE_TREE` ⇒
 *    [runLoop_forwardsTheHostObservationMode] **变红**。
 */
class AgentEngineLoopTest {

    private lateinit var server: MockWebServer

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(2, TimeUnit.SECONDS)
        .readTimeout(2, TimeUnit.SECONDS)
        .build()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        takenRequests = 0
    }

    /** 已从 MockWebServer 取走的请求数（[bodyOfRequest] 的游标）。 */
    private var takenRequests = 0

    @After
    fun tearDown() {
        runCatching { server.shutdown() }
    }

    // ------------------------------------------------------------ 装置

    private fun tool(name: String) = ChatAgentToolDefinition(
        name = name,
        title = name,
        description = "test tool $name",
        moduleId = "vflow.test.$name",
        moduleDisplayName = name,
        inputSchema = JsonObject(emptyMap()),
        permissionNames = emptyList(),
        riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
        usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
    )

    private val allTools = listOf(tool("vflow_agent_tap"))

    private val rejectedMarker = "REJECTED_BY_THE_HOST"

    private inner class FakeRunner : AgentToolRunner {
        var executeCount = 0
        var rejectedCount = 0
        var prepareArtifacts: ChatAgentArtifactStore? = null
        var executeArtifacts: ChatAgentArtifactStore? = null

        override fun prepare(
            toolCalls: List<ChatToolCall>,
            artifacts: ChatAgentArtifactStore,
        ): ChatPreparedToolBatch {
            prepareArtifacts = artifacts
            return ChatPreparedToolBatch(
                items = emptyList(),
                missingPermissions = emptyList(),
                riskLevel = ChatAgentToolRiskLevel.LOW,
            )
        }

        override suspend fun execute(
            batch: ChatPreparedToolBatch,
            artifacts: ChatAgentArtifactStore,
        ): List<ChatToolResult> {
            executeArtifacts = artifacts
            executeCount++
            return emptyList()
        }

        override fun rejected(toolCalls: List<ChatToolCall>): List<ChatToolResult> {
            rejectedCount++
            return toolCalls.map { call ->
                ChatToolResult(
                    callId = call.id,
                    name = call.name,
                    status = ChatToolResultStatus.REJECTED,
                    summary = rejectedMarker,
                    outputText = rejectedMarker,
                )
            }
        }

        override fun permissionRequired(batch: ChatPreparedToolBatch): List<ChatToolResult> = emptyList()
    }

    private inner class LoopHost(
        override val maxRounds: Int? = null,
        override val observationMode: ObservationMode = ObservationMode.BOTH,
        private val decision: ApprovalDecision = ApprovalDecision.Approved,
    ) : AgentHost {
        override val toolNames: Set<String> = allTools.map { it.name }.toSet()
        override val artifacts: ChatAgentArtifactStore = ChatAgentArtifactStore()
        override val promptPlan: AgentPromptPlan = WorkflowPromptPlan("")
        val finished = mutableListOf<AgentOutcome>()
        val notices = mutableListOf<String>()

        override suspend fun onProgress(update: AgentProgressUpdate) {
            if (update is AgentProgressUpdate.Notice) notices += update.message
        }

        override suspend fun requestApproval(
            calls: List<ChatToolCall>,
            riskLevel: ChatAgentToolRiskLevel,
        ): ApprovalDecision = decision

        override suspend fun onFinish(outcome: AgentOutcome) {
            finished += outcome
        }
    }

    private class MapByteSource(private val bytesByUri: Map<String, ByteArray>) : ChatImageByteSource {
        override fun readBytes(attachment: ChatImageAttachment): ByteArray? = bytesByUri[attachment.uri]
    }

    private fun engine(runner: AgentToolRunner, imageUri: String? = null) = ChatAgentEngine(
        chatClient = ChatCompletionClient(
            httpClient = httpClient,
            imageResolver = ChatImageResolver(
                byteSource = MapByteSource(
                    imageUri?.let { mapOf(it to ByteArray(3)) }.orEmpty(),
                ),
                shrinker = object : ChatImageShrinker {
                    override fun shrinkToBase64(
                        attachment: ChatImageAttachment,
                        bytes: ByteArray,
                        maxBase64Chars: Int,
                    ): String? = null
                },
            ),
        ),
        allTools = allTools,
        toolRunner = runner,
    )

    private fun preset() = ChatPresetConfig(
        provider = ChatProvider.OPENAI.storageValue,
        model = "test-model",
        apiKey = "test-key",
        baseUrl = server.url("/v1").toString(),
        systemPrompt = "SYS-PROMPT",
        streamMaxRetries = 0,
    )

    private fun sse(vararg lines: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(lines.joinToString(separator = ""))

    private fun textStream(text: String) = sse(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"$text\"},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
        "data: [DONE]\n\n",
    )

    private fun toolCallStream(id: String) = sse(
        "data: {\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"$id\",\"type\":\"function\",\"function\":{\"name\":\"vflow_agent_tap\",\"arguments\":\"{}\"}}]},\"finish_reason\":null}]}\n\n",
        "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"tool_calls\"}]}\n\n",
        "data: [DONE]\n\n",
    )

    private class RecordingObserver(
        private val modeForImage: Boolean,
        private val imageUri: String?,
    ) : AgentObservationProvider {
        val seenModes = mutableListOf<ObservationMode>()
        val seenRounds = mutableListOf<Int>()
        val seenMaxRounds = mutableListOf<Int?>()

        override suspend fun observe(mode: ObservationMode, round: Int, maxRounds: Int?): AgentObservation {
            seenModes += mode
            seenRounds += round
            seenMaxRounds += maxRounds
            val wantsImage = when (mode) {
                ObservationMode.NODE_TREE -> false
                ObservationMode.SCREENSHOT -> true
                ObservationMode.BOTH -> modeForImage
            }
            return AgentObservation(
                text = if (mode == ObservationMode.SCREENSHOT) null else "UI-NODE-TREE-TEXT",
                image = if (wantsImage && imageUri != null) {
                    ChatImageAttachment(uri = imageUri, source = ChatImageSource.AGENT, mimeType = "image/png")
                } else {
                    null
                },
                screenWidth = 1080,
                screenHeight = 2400,
            )
        }
    }

    private fun loopRequest(observer: AgentObservationProvider) = AgentLoopRequest(
        preset = preset(),
        task = AgentTask(instruction = "打开设置", history = emptyList()),
        observation = observer,
    )

    /**
     * 取**第 index 次**请求（0-based）的请求体。
     *
     * ⚠️ 不能写成「裸调一次 `takeRequest`」—— 那样 `bodyOfRequest(1)` 拿到的是第 1 次请求，
     * 断言会静默地看错对象（本文件第一版就这么错过一次）。
     */
    private fun bodyOfRequest(index: Int): String {
        var body: String? = null
        while (takenRequests <= index) {
            body = server.takeRequest(5, TimeUnit.SECONDS)!!.body.readUtf8()
            takenRequests++
        }
        return body ?: error("第 $index 次请求已被取走")
    }

    // ------------------------------------------------------------ 1. 正常收尾

    @Test
    fun runLoop_finishesWhenTheModelStopsCallingTools() = runBlocking {
        server.enqueue(textStream("done"))
        val host = LoopHost()
        val observer = RecordingObserver(modeForImage = true, imageUri = "file:///tmp/shot.png")

        val outcome = engine(FakeRunner()).runLoop(host, loopRequest(observer))

        assertEquals(StopReason.FINISHED, outcome.stoppedBy)
        assertEquals("一轮就收尾", 1, outcome.roundsUsed)
        assertTrue(outcome.success)
        assertEquals("done", outcome.finalText)
        assertEquals("收尾出口必须调 onFinish（且只调一次）", 1, host.finished.size)
        assertEquals("观察必须从第 1 轮开始", listOf(1), observer.seenRounds)
        assertEquals("目标必须进 system prompt", true, bodyOfRequest(0).contains("# User Goal"))
    }

    // ------------------------------------------------------------ 2. D17-② wrap-up 轮

    @Test
    fun runLoop_givesTheModelOneWrapUpRoundAtTheLimit() = runBlocking {
        // 两轮都产出工具调用 ⇒ 第 2 轮后到顶 ⇒ 第 3 次请求是**收尾总结**轮。
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(toolCallStream("call_2"))
        server.enqueue(textStream("收尾总结"))

        val host = LoopHost(maxRounds = 2)
        val observer = RecordingObserver(modeForImage = false, imageUri = null)

        val outcome = engine(FakeRunner()).runLoop(host, loopRequest(observer))

        assertEquals(StopReason.MAX_ROUNDS, outcome.stoppedBy)
        assertEquals("收尾轮也算一轮（roundsUsed = maxRounds + 1）", 3, outcome.roundsUsed)
        assertEquals("收尾总结的正文必须被采纳", "收尾总结", outcome.finalText)

        val first = bodyOfRequest(0)
        val second = bodyOfRequest(1)
        val wrapUp = bodyOfRequest(2)
        assertEquals("必须有且只有三次请求（2 轮 + 1 次收尾）", 3, server.requestCount)
        assertFalse("前两轮不得含收尾指令", first.contains(WRAP_UP_INSTRUCTION))
        assertFalse(second.contains(WRAP_UP_INSTRUCTION))
        assertTrue(
            "到顶必须**再给一次**收尾机会（D17-②：不是硬中断）",
            wrapUp.contains(WRAP_UP_INSTRUCTION),
        )
    }

    // ------------------------------------------------------------ 3. Rejected / Deferred

    @Test
    fun runLoop_doesNotExecuteRejectedToolCalls() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("已放弃该操作"))

        val runner = FakeRunner()
        val host = LoopHost(decision = ApprovalDecision.Rejected)
        val outcome = engine(runner).runLoop(
            host,
            loopRequest(RecordingObserver(modeForImage = false, imageUri = null)),
        )

        assertEquals("Rejected ⇒ 不得执行", 0, runner.executeCount)
        assertEquals(1, runner.rejectedCount)
        assertEquals(StopReason.FINISHED, outcome.stoppedBy)
        val second = bodyOfRequest(1)
        assertTrue(
            "被拒的调用必须以 tool 结果的形式回到历史里（否则模型不知道自己被拒了）",
            second.contains(rejectedMarker),
        )
    }

    @Test
    fun runLoop_treatsDeferredAsNotApproved() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("已放弃该操作"))

        val runner = FakeRunner()
        val host = LoopHost(decision = ApprovalDecision.Deferred)
        engine(runner).runLoop(
            host,
            loopRequest(RecordingObserver(modeForImage = false, imageUri = null)),
        )

        assertEquals(
            "安全默认：没人批准就不执行（工作流宿主不该返回 Deferred —— 会记一条 E 日志）",
            0,
            runner.executeCount,
        )
        assertEquals(1, runner.rejectedCount)
    }

    @Test
    fun runLoop_executesApprovedToolCalls() = runBlocking {
        server.enqueue(toolCallStream("call_1"))
        server.enqueue(textStream("完成"))

        val runner = FakeRunner()
        val outcome = engine(runner).runLoop(
            LoopHost(decision = ApprovalDecision.Approved),
            loopRequest(RecordingObserver(modeForImage = false, imageUri = null)),
        )

        assertEquals("Approved ⇒ 必须执行一次", 1, runner.executeCount)
        assertEquals(0, runner.rejectedCount)
        assertEquals(StopReason.FINISHED, outcome.stoppedBy)
    }

    // ------------------------------------------------------------ 4. 观察按 mode 装配

    @Test
    fun runLoop_forwardsTheHostObservationMode() = runBlocking {
        server.enqueue(textStream("done"))
        val observer = RecordingObserver(modeForImage = true, imageUri = "file:///tmp/shot.png")
        engine(FakeRunner(), imageUri = "file:///tmp/shot.png")
            .runLoop(LoopHost(observationMode = ObservationMode.BOTH), loopRequest(observer))

        assertEquals(
            "引擎必须把 host.observationMode 交给 provider（不得写死）",
            listOf(ObservationMode.BOTH),
            observer.seenModes,
        )
        assertEquals("maxRounds 也要交给 provider（它要写进告警文案）", listOf(null), observer.seenMaxRounds)
        val body = bodyOfRequest(0)
        assertTrue(
            "BOTH ⇒ 请求里必须出现图片 part（否则模型看不到屏幕）",
            body.contains("image_url"),
        )
    }

    @Test
    fun runLoop_nodeTreeModeSendsNoImage() = runBlocking {
        server.enqueue(textStream("done"))
        val observer = RecordingObserver(modeForImage = true, imageUri = "file:///tmp/shot.png")
        engine(FakeRunner()).runLoop(LoopHost(observationMode = ObservationMode.NODE_TREE), loopRequest(observer))

        assertEquals(listOf(ObservationMode.NODE_TREE), observer.seenModes)
        val body = bodyOfRequest(0)
        assertTrue("节点树文本必须在", body.contains("UI-NODE-TREE-TEXT"))
        assertFalse("NODE_TREE ⇒ 不得有图片 part", body.contains("image_url"))
    }

    @Test
    fun runLoop_screenshotModeSendsImageAndNoNodeTree() = runBlocking {
        server.enqueue(textStream("done"))
        val observer = RecordingObserver(modeForImage = true, imageUri = "file:///tmp/shot.png")
        engine(FakeRunner(), imageUri = "file:///tmp/shot.png")
            .runLoop(LoopHost(observationMode = ObservationMode.SCREENSHOT), loopRequest(observer))

        assertEquals(listOf(ObservationMode.SCREENSHOT), observer.seenModes)
        val body = bodyOfRequest(0)
        assertTrue("SCREENSHOT ⇒ 必须有图片 part", body.contains("image_url"))
        assertFalse("SCREENSHOT ⇒ 不得塞节点树文本", body.contains("UI-NODE-TREE-TEXT"))
    }

    // ------------------------------------------------------------ 5. Q5 硬化：**真实 WorkflowAgentHost** + 假 round runner
    //
    // ⚠️ 为什么不能只用假宿主：本仓库的教训是「纯函数测试全绿 ≠ 集成点接上了」（§3.7.8）。
    // 这一组用**真实的 `WorkflowAgentHost`**（白名单 / 审批 / 执行级 store / `toExecutionResult`
    // 全部是生产实现）驱动 `runLoop`，只把「一次 LLM 往返」换成脚本化的假实现
    // （`ChatCompletionClient` 是 final class 且在禁改清单里 ⇒ 只能从引擎侧注入）。

    /** 模拟 `toolRegistry.getTools().map { it.name }`：**含** 6 个黑名单工具的全量表。 */
    private val registryLikeToolNames = setOf(
        CHAT_TEMPORARY_WORKFLOW_TOOL_NAME,
        CHAT_SAVE_WORKFLOW_TOOL_NAME,
        CHAT_GET_WORKFLOW_TOOL_NAME,
        CHAT_UPDATE_WORKFLOW_TOOL_NAME,
        CHAT_LIST_WORKFLOWS_TOOL_NAME,
        CHAT_LOAD_SKILL_TOOL_NAME,
        "vflow_agent_observe_ui",
        "vflow_agent_tap",
    )

    private val registryLikeTools = registryLikeToolNames.map { tool(it) }

    /** 脚本化的一轮往返：按顺序回放 [rounds]，并记录引擎实际下发的请求。 */
    private inner class ScriptedEngine(
        allTools: List<ChatAgentToolDefinition>,
        toolRunner: AgentToolRunner,
        private val rounds: List<AgentRoundResult>,
    ) : ChatAgentEngine(ChatCompletionClient(), allTools, toolRunner) {
        val seenRequests = mutableListOf<AgentRoundRequest>()
        private var index = 0

        override suspend fun runRound(host: AgentHost, request: AgentRoundRequest): AgentRoundResult {
            seenRequests += request
            val next = rounds[minOf(index, rounds.size - 1)]
            index++
            return next
        }
    }

    private fun textRound(text: String) = AgentRoundResult(
        completion = ChatCompletionResult(content = text, reasoningContent = null, totalTokens = null),
        toolCalls = emptyList(),
        streamedContent = text,
        streamedReasoning = null,
        retriesUsed = 0,
    )

    private fun toolRound(id: String, name: String = "vflow_agent_tap") = AgentRoundResult(
        completion = ChatCompletionResult(content = "", reasoningContent = null, totalTokens = null),
        toolCalls = listOf(ChatToolCall(id = id, name = name, argumentsJson = "{}")),
        streamedContent = "",
        streamedReasoning = null,
        retriesUsed = 0,
    )

    private fun realHost(maxRounds: Int? = null) = WorkflowAgentHost(
        allToolNames = registryLikeToolNames,
        maxRounds = maxRounds,
    )

    private fun quietObserver() = RecordingObserver(modeForImage = false, imageUri = null)

    /** ① 白名单过滤真的生效（黑名单工具一个都进不了引擎的下发列表）。 */
    @Test
    fun realWorkflowHost_keepsTheBlacklistedToolsOutOfTheEngineWhitelist() {
        val host = realHost()
        val engine = ScriptedEngine(registryLikeTools, FakeRunner(), listOf(textRound("done")))

        val sent = engine.availableTools(host).map { it.name }
        assertEquals(
            "真实工作流宿主的白名单必须把 6 个工作流读写类工具挡在引擎之外",
            listOf("vflow_agent_observe_ui", "vflow_agent_tap"),
            sent,
        )
        assertTrue("黑名单工具一个都不许进下发列表", sent.none { it in WORKFLOW_EXCLUDED_TOOL_NAMES })
    }

    /** ② 到 `maxRounds` 时**恰好多跑一轮收尾**（不是硬中断）。 */
    @Test
    fun realWorkflowHost_runsExactlyOneWrapUpRoundAtTheLimit() = runBlocking {
        val host = realHost(maxRounds = 2)
        val engine = ScriptedEngine(
            registryLikeTools,
            FakeRunner(),
            listOf(toolRound("call_1"), toolRound("call_2"), textRound("收尾总结")),
        )

        val outcome = engine.runLoop(host, loopRequest(quietObserver()))

        assertEquals(StopReason.MAX_ROUNDS, outcome.stoppedBy)
        assertEquals("到顶后必须恰好多跑一轮收尾（不是硬中断）", 3, engine.seenRequests.size)
        assertEquals(3, outcome.roundsUsed)
        assertEquals("收尾轮的正文必须被采纳", "收尾总结", outcome.finalText)
        assertTrue(
            "最后一次请求的 history 必须含收尾指令",
            engine.seenRequests.last().history.any { it.content == WRAP_UP_INSTRUCTION },
        )
        assertFalse(
            "前两轮不得含收尾指令",
            engine.seenRequests.take(2).any { request ->
                request.history.any { it.content == WRAP_UP_INSTRUCTION }
            },
        )
        assertEquals("onFinish 必须收到同一个结论", outcome, host.lastOutcome)
    }

    /** ③ `requestApproval` 恒 `Approved`（D12：工作流宿主无 UI 可交互）。 */
    @Test
    fun realWorkflowHost_approvesWithoutUserInteraction() = runBlocking {
        val host = realHost()
        val runner = FakeRunner()
        val engine = ScriptedEngine(
            registryLikeTools,
            runner,
            listOf(toolRound("call_1"), textRound("完成")),
        )

        val outcome = engine.runLoop(host, loopRequest(quietObserver()))

        assertEquals("真实工作流宿主不覆写 requestApproval ⇒ 工具必须真的执行", 1, runner.executeCount)
        assertEquals(0, runner.rejectedCount)
        assertEquals(StopReason.FINISHED, outcome.stoppedBy)
    }

    /** ④ `artifacts` 是**执行级**实例（不是会话级 / 不是跨执行共享）。 */
    @Test
    fun realWorkflowHost_usesAnExecutionScopedArtifactStore() = runBlocking {
        val first = realHost()
        val second = realHost()
        assertNotSame(
            "执行级 store：两次执行（两个宿主实例）不得共享同一个 store",
            first.artifacts,
            second.artifacts,
        )

        val runner = FakeRunner()
        ScriptedEngine(registryLikeTools, runner, listOf(toolRound("call_1"), textRound("完成")))
            .runLoop(first, loopRequest(quietObserver()))

        assertSame("引擎必须把 host.artifacts 透传给 prepare", first.artifacts, runner.prepareArtifacts)
        assertSame("引擎必须把 host.artifacts 透传给 execute", first.artifacts, runner.executeArtifacts)
    }

    /** ⑤ `onFinish` → `toExecutionResult` 的映射正确（含到顶的形状）。 */
    @Test
    fun realWorkflowHost_mapsTheOutcomeToExecutionResult() = runBlocking {
        val finishedHost = realHost()
        ScriptedEngine(registryLikeTools, FakeRunner(), listOf(textRound("任务完成")))
            .runLoop(finishedHost, loopRequest(quietObserver()))

        val finished = (finishedHost.toExecutionResult() as? ExecutionResult.Success)?.outputs
        assertNotNull("FINISHED ⇒ Success", finished)
        assertEquals("任务完成", (finished!!["result"] as VString).raw)
        assertEquals(true, (finished["success"] as VBoolean).raw)

        val limitedHost = realHost(maxRounds = 1)
        ScriptedEngine(registryLikeTools, FakeRunner(), listOf(toolRound("call_1"), textRound("收尾")))
            .runLoop(limitedHost, loopRequest(quietObserver()))

        val limited = (limitedHost.toExecutionResult() as? ExecutionResult.Success)?.outputs
        assertNotNull("MAX_ROUNDS ⇒ Success + success=false（形状不变，D18 未动）", limited)
        assertEquals(
            "⚠️ 批 4 返工（父会话 Q4 裁决）：到顶的正文用**模型的收尾总结**，不再是固定文案",
            "收尾",
            (limited!!["result"] as VString).raw,
        )
        assertEquals(false, (limited["success"] as VBoolean).raw)
    }
}
