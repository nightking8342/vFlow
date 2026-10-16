package com.chaomixian.vflow.core.workflow.module.interaction

import com.chaomixian.vflow.core.ai.AiModelConfigRef
import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.ui.chat.WORKFLOW_EXCLUDED_TOOL_NAMES
import com.chaomixian.vflow.ui.chat.WorkflowAgentHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线测试：`AgentModule` 现在真的走共享引擎**（fork：AI 能力统一批 4 的 T6）。
 *
 * 为什么必须有它：`AgentModule.execute` 需要 `Context` / 无障碍服务（无 Robolectric）⇒
 * 它的驱动路径**无法直接单测**。而「纯逻辑全绿 ≠ 集成点接上」是本仓库踩过两次的坑。
 *
 * ⚠️ 断言锚在**本次新增**的表达式上（`ChatAgentEngine(` / `engine.runLoop(` /
 * `WorkflowAgentHost(` / `AgentModuleToolRunner(`）—— 锚在改造前就存在的标识符上会**恒真**。
 * ⚠️ 扫描前剥注释（[SourceScan.stripped]）：本任务的 KDoc 大段引用「已经搬走的」名字
 * （`getNativeToolsSchema` / `callLLM`），只做 `contains` 的话留成注释也一样绿。
 *
 * ## ⚠️ 必做反证
 *
 * 1. 删掉 [AgentModuleToolRunner.executeOne] 里的 `overlay.awaitState()`
 *    ⇒ [runner_keepsThePreActionPauseGate] **变红**；
 * 2. 把 `agentModuleToolDefinitions()` 里的 `swipe` 删掉
 *    ⇒ [toolTable_hasTheEightOriginalToolsPlusTheThreeGestures] **变红**。
 */
class AgentModuleWiringTest {

    private val moduleSource = SourceScan.stripped(
        "src/main/java/com/chaomixian/vflow/core/workflow/module/interaction/AgentModule.kt",
    )
    private val hostSource = SourceScan.stripped(
        "src/main/java/com/chaomixian/vflow/ui/chat/WorkflowAgentHost.kt",
    )

    // ------------------------------------------------------------ 1. 真的走引擎

    @Test
    fun module_drivesTheSharedEngineAndHost() {
        assertTrue("必须构造共享引擎", moduleSource.contains("ChatAgentEngine("))
        assertTrue("循环必须走 `engine.runLoop(`", moduleSource.contains("engine.runLoop("))
        assertTrue("必须构造工作流宿主", moduleSource.contains("WorkflowAgentHost("))
        assertTrue("工具执行必须走自己的 runner 接缝", moduleSource.contains("AgentModuleToolRunner("))
        assertTrue("观察装配必须经 AgentObservationProvider", moduleSource.contains("AgentObservationProvider {"))
    }

    @Test
    fun module_noLongerHandRollsItsOwnHttpLoop() {
        assertFalse("手写的 callLLM 必须消失（传输层改走共享 client）", moduleSource.contains("callLLM("))
        assertFalse("手写的工具 schema 必须消失", moduleSource.contains("getNativeToolsSchema"))
        assertFalse("不得再直接依赖 okhttp", moduleSource.contains("okhttp3"))
        assertFalse("不得再直接拼 JSON 请求体", moduleSource.contains("JSONArray"))
    }

    @Test
    fun runner_keepsThePreActionPauseGate() {
        assertTrue(
            "动作前必须仍查一次 awaitState()（保住「动作前可暂停」的既有语义）",
            moduleSource.contains("overlay.awaitState()"),
        )
        assertTrue(
            "每轮观察开头也必须查一次",
            moduleSource.contains("overlayManager.awaitState()"),
        )
    }

    @Test
    fun module_keepsItsOwnToolsAndExecutionContext() {
        assertTrue("必须仍用自己的 AgentTools", moduleSource.contains("AgentTools(context)"))
        assertTrue(
            "虚拟屏仍靠 _target_display_id 注入 ExecutionContext（不得改用 chat 的 native helper）",
            moduleSource.contains("_target_display_id"),
        )
    }

    // ------------------------------------------------------------ 2. 提示词收敛

    @Test
    fun prompt_keepsThe19RulesInExactlyOnePlace() {
        assertFalse(
            "19 条规则不得再在 AgentModule 里留副本",
            moduleSource.contains("# 必须遵循的规则"),
        )
        assertTrue(
            "它们必须由 WORKFLOW_RULES 提供（唯一来源）",
            hostSource.contains("WORKFLOW_RULES"),
        )
        assertTrue(
            "宿主必须把协议段拼在公共段之前",
            hostSource.contains("WorkflowModulePromptPlan("),
        )
    }

    // ------------------------------------------------------------ 3. 工具表（D-a / D-b）

    @Test
    fun toolTable_hasTheEightOriginalToolsPlusTheThreeGestures() {
        val names = agentModuleToolDefinitions().map { it.name }

        assertEquals(
            "8 个既有工具的顺序与名字逐字保留，3 个新手势工具追加在 finish_task 之前",
            listOf(
                "click_point",
                "click_element",
                "input_text",
                "scroll",
                "press_key",
                "launch_app",
                "wait",
                "long_press",
                "double_tap",
                "swipe",
                "finish_task",
            ),
            names,
        )
        assertTrue("long_press 必须补上（D-a）", names.contains("long_press"))
        assertTrue("double_tap 必须补上（D-a）", names.contains("double_tap"))
        assertTrue("swipe 必须补上（D-a）", names.contains("swipe"))
    }

    @Test
    fun toolTable_isEntirelyWhitelistedByTheWorkflowHost() {
        val names = agentModuleToolDefinitions().map { it.name }
        val host = WorkflowAgentHost(allToolNames = names)

        assertEquals(
            "本模块的工具表天然不含工作流读写类工具 ⇒ 白名单过滤对它恒等",
            names.toSet(),
            host.toolNames,
        )
        assertTrue(
            "一个都不该被排除",
            host.toolNames.none { it in WORKFLOW_EXCLUDED_TOOL_NAMES },
        )
    }

    @Test
    fun protocolSection_namesTheThreeNewToolsSoTheRulesCanBeFollowed() {
        val protocol = agentModuleProtocolSection("2026年10月16日 星期五", "09:30", "WeChat")

        assertTrue("协议段必须点名 long_press（D-b：提示词与工具表对齐）", protocol.contains("long_press"))
        assertTrue("协议段必须点名 double_tap", protocol.contains("double_tap"))
        assertTrue("协议段必须点名 swipe（规则 5/12/15 要求滑动查找）", protocol.contains("swipe"))
        assertTrue("协议段必须仍带日期（每次执行现算）", protocol.contains("2026年10月16日 星期五"))
        assertTrue("协议段必须仍带 RECENT APPS", protocol.contains("WeChat"))
        assertFalse(
            "协议段里**不得**再含 19 条规则（那是 WORKFLOW_RULES 的职责）",
            protocol.contains("# 必须遵循的规则"),
        )
    }

    // ------------------------------------------------------------ 4. 参数形态

    @Test
    fun newParametersAreAppendedWithManualAsTheDefault() {
        val inputs = AgentModule().getInputs().associateBy { it.id }

        assertEquals(
            "config_source 默认 manual ⇒ 老步骤零迁移",
            AiModelConfigRef.SOURCE_MANUAL,
            inputs[AiModelConfigRef.PARAM_SOURCE]?.defaultValue,
        )
        assertTrue("model_config_id 必须在", inputs.containsKey(AiModelConfigRef.PARAM_CONFIG_ID))
        assertEquals(
            "system_prompt（可配前段）默认空串",
            "",
            inputs["system_prompt"]?.defaultValue,
        )
        assertFalse(
            "前段是 plain 文本 ⇒ 不走富文本胶囊（避开 supportsRichText 那个坑）",
            inputs["system_prompt"]!!.supportsRichText,
        )
        assertFalse(
            "前段不接受魔法变量（它是系统提示词，不是内容参数）",
            inputs["system_prompt"]!!.acceptsMagicVariable,
        )
        listOf(
            "provider", "base_url", "api_key", "model", "instruction", "max_steps",
            "display_mode", "monitor_mode", "tools",
        ).forEach { id ->
            assertTrue("既有参数一个都不能少（只追加、不替换）：$id", inputs.containsKey(id))
        }
    }

    @Test
    fun normalizedCoordinatesKeepTheModulesOwnZeroToThousandScale() {
        assertEquals(0, agentNormalizedToPixel(0, 1080))
        assertEquals(540, agentNormalizedToPixel(500, 1080))
        assertEquals(1080, agentNormalizedToPixel(1000, 1080))
        assertEquals(1200, agentNormalizedToPixel(500, 2400))
    }

    // ------------------------------------------------------------ 5. 温度（返工登记 #2）

    @Test
    fun moduleTemperatureIsTheUnspecifiedEquivalentAndIsActuallyUsed() {
        assertEquals(
            "改造前本模块的请求体**没有** temperature 字段（靠 API 默认）；共享传输层" +
                "**无条件**下发它 ⇒ 1.0 是「不指定」在 OpenAI 系语义下的等价值" +
                "（⚠️ 非 OpenAI 系网关的默认值不保证是 1.0）—— 已登记为有意变更",
            1.0,
            AGENT_MODULE_TEMPERATURE,
            1e-9,
        )
        assertTrue(
            "模块必须真的把它交给解析器（否则这个常量只是摆设）",
            moduleSource.contains("temperature = AGENT_MODULE_TEMPERATURE"),
        )
    }

    // ------------------------------------------------------------ 6. 反死代码

    @Test
    fun zeroCallSiteHelpersStayDeleted() {
        assertFalse(
            "`agentModulePreset` 曾是无生产调用点、也无测试引用的死代码（返工第 4 条）⇒ 已删除，不得回潮",
            moduleSource.contains("agentModulePreset"),
        )
    }
}
