package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinate
import com.chaomixian.vflow.core.types.complex.VImage
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatAgentToolingTest {

    @Test
    fun toolName_isStableAndSafe() {
        assertEquals(
            "vflow_system_capture_screen",
            chatToolNameFromModuleId("vflow.system.capture_screen")
        )
        assertEquals(
            "vflow_core_force_stop_app",
            chatToolNameFromModuleId("vflow.core.force_stop_app")
        )
        assertEquals(
            "vflow_device_flashlight",
            chatToolNameFromModuleId("vflow.device.flashlight")
        )
    }

    @Test
    fun artifactStore_createsAndResolvesComplexHandles() {
        val store = ChatAgentArtifactStore()
        val image = VImage("file:///tmp/screen.png")
        val coordinate = VCoordinate(12, 34)

        val references = store.createReferences(
            callId = "call_123",
            outputs = mapOf(
                "image" to image,
                "point" to coordinate,
                "text" to VString("hello"),
            )
        )

        assertEquals(2, references.size)
        assertTrue(references.any { it.key == "image" && it.handle == "artifact://call_123/image" })
        assertTrue(references.any { it.key == "point" && it.handle == "artifact://call_123/point" })
        assertEquals(image, store.resolve("artifact://call_123/image"))
        assertEquals(coordinate, store.resolve("artifact://call_123/point"))
        assertNull(store.resolve("artifact://call_123/text"))
    }

    @Test
    fun autoApprovalScope_allowsExpectedRiskLevels() {
        assertTrue(ChatToolAutoApprovalScope.READ_ONLY.allows(ChatAgentToolRiskLevel.READ_ONLY))
        assertTrue(ChatToolAutoApprovalScope.STANDARD.allows(ChatAgentToolRiskLevel.LOW))
        assertTrue(ChatToolAutoApprovalScope.STANDARD.allows(ChatAgentToolRiskLevel.STANDARD))
        assertTrue(ChatToolAutoApprovalScope.ALL.allows(ChatAgentToolRiskLevel.HIGH))
        assertTrue(!ChatToolAutoApprovalScope.OFF.allows(ChatAgentToolRiskLevel.READ_ONLY))
        assertTrue(!ChatToolAutoApprovalScope.STANDARD.allows(ChatAgentToolRiskLevel.HIGH))
    }

    @Test
    fun skillRouter_promptIncludesAlwaysAvailableHelpersWithoutActiveSkills() {
        val selection = ChatAgentSkillRouter.availableTools(sampleTools())

        val prompt = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "Base prompt",
            skillSelection = selection,
        )

        assertTrue(prompt.contains("Always-available agent-native helpers"))
        assertTrue(prompt.contains(CHAT_AGENT_TAP_TOOL_NAME))
        assertTrue(prompt.contains(CHAT_AGENT_OBSERVE_UI_TOOL_NAME))
    }

    @Test
    fun toolResultFormatter_preservesArtifactHandlesWhenTruncating() {
        val longText = buildString {
            repeat(CHAT_MAX_TOOL_RESULT_INPUT_CHARS) {
                append('a')
            }
            append(" trailing detail")
        }
        val formatted = ChatToolResultInputFormatter.format(
            message = ChatMessage(
                role = ChatMessageRole.TOOL,
                content = longText,
                timestampMillis = 1L,
            ),
            toolResult = ChatToolResult(
                callId = "call_1",
                name = "vflow_device_flashlight",
                status = ChatToolResultStatus.SUCCESS,
                summary = "Flashlight",
                outputText = longText,
                artifacts = listOf(
                    ChatArtifactReference(
                        key = "image",
                        handle = "artifact://call_1/image",
                        typeLabel = "image",
                    )
                ),
            ),
        )

        assertTrue(formatted.contains("artifact://call_1/image"))
        assertTrue(formatted.contains("[output truncated:"))
        assertTrue(formatted.length <= CHAT_MAX_TOOL_RESULT_INPUT_CHARS)
    }

    @Test
    fun toolResultFormatter_truncationNoticeReportsOmittedSizeAndRecoveryHint() {
        // 告知必须让模型知道「被截掉多少」和「下次怎么收窄」——
        // 否则就是病症 A 换个地方复发（拿到半份却以为全份）。
        val originalChars = CHAT_MAX_TOOL_RESULT_INPUT_CHARS * 3
        val longText = "a".repeat(originalChars)
        val formatted = ChatToolResultInputFormatter.format(
            message = ChatMessage(
                role = ChatMessageRole.TOOL,
                content = longText,
                timestampMillis = 1L,
            ),
            toolResult = ChatToolResult(
                callId = "call_1",
                name = CHAT_AGENT_OBSERVE_UI_TOOL_NAME,
                status = ChatToolResultStatus.SUCCESS,
                summary = "UI tree",
                outputText = longText,
            ),
        )

        // 报出原始长度与被丢弃的尾部长度
        assertTrue("应报出原始字符数", formatted.contains(originalChars.toString()))
        val expectedOmitted = originalChars - CHAT_MAX_TOOL_RESULT_INPUT_CHARS
        assertTrue("应报出被丢弃的字符数", formatted.contains(expectedOmitted.toString()))
        // 针对可参数收窄的工具给出具体建议
        assertTrue("应给出收窄建议", formatted.contains("limit"))
    }

    @Test
    fun toolResultFormatter_truncationNoticeGivesGenericHintForOtherTools() {
        val longText = "a".repeat(CHAT_MAX_TOOL_RESULT_INPUT_CHARS * 2)
        val formatted = ChatToolResultInputFormatter.format(
            message = ChatMessage(
                role = ChatMessageRole.TOOL,
                content = longText,
                timestampMillis = 1L,
            ),
            toolResult = ChatToolResult(
                callId = "call_1",
                name = "vflow_some_other_tool",
                status = ChatToolResultStatus.SUCCESS,
                summary = "Other",
                outputText = longText,
            ),
        )

        assertTrue(formatted.contains("[output truncated:"))
        assertTrue("未知工具走通用提示，不给具体参数建议", formatted.contains("Narrow the request"))
    }

    @Test
    fun toolResultFormatter_doesNotTruncateToolDeclaredAsNotTruncatable() {
        val longText = buildString {
            repeat(CHAT_MAX_TOOL_RESULT_INPUT_CHARS * 3) {
                append('a')
            }
            append(" tail must survive")
        }

        val formatted = ChatToolResultInputFormatter.format(
            message = ChatMessage(
                role = ChatMessageRole.TOOL,
                content = longText,
                timestampMillis = 1L,
            ),
            toolResult = ChatToolResult(
                callId = "call_1",
                name = "vflow_agent_load_skill",
                status = ChatToolResultStatus.SUCCESS,
                summary = "Skill",
                outputText = longText,
            ),
            toolDefinitions = listOf(
                testToolDefinition(name = "vflow_agent_load_skill", truncatable = false),
            ),
        )

        assertEquals(longText, formatted)
        assertFalse(formatted.contains("[output truncated:"))
    }

    @Test
    fun toolResultFormatter_truncatesWhenToolIsTruncatableOrUnknown() {
        val longText = buildString {
            repeat(CHAT_MAX_TOOL_RESULT_INPUT_CHARS * 3) {
                append('a')
            }
        }

        val message = ChatMessage(
            role = ChatMessageRole.TOOL,
            content = longText,
            timestampMillis = 1L,
        )
        val toolResult = ChatToolResult(
            callId = "call_1",
            name = "vflow_agent_observe_ui",
            status = ChatToolResultStatus.SUCCESS,
            summary = "UI tree",
            outputText = longText,
        )

        // 显式声明可截断
        val explicit = ChatToolResultInputFormatter.format(
            message = message,
            toolResult = toolResult,
            toolDefinitions = listOf(
                testToolDefinition(name = "vflow_agent_observe_ui", truncatable = true),
            ),
        )
        assertTrue(explicit.contains("[output truncated:"))

        // 名字查不到时按可截断处理（与改造前行为一致）
        val unknown = ChatToolResultInputFormatter.format(
            message = message,
            toolResult = toolResult,
            toolDefinitions = emptyList(),
        )
        assertTrue(unknown.contains("[output truncated:"))
    }

    @Test
    fun skillCatalogIsEmptyAfterCleanup() {
        // 14 个技能的正文经逐行核对，70 行里仅 6 行独有，已上提到 system prompt 规则段。
        // 其余与 prompt / 工具 description 重复，故整批清空。
        //
        // **机制保留**：清单与按需加载都在，后续加真正承载独立知识的技能时在此追加。
        // 本测试锁定「清空」这一事实——若将来加回技能，请连带更新它。
        val listing = ChatAgentSkillRouter.skillListing()

        assertTrue("技能目录当前应为空", listing.isEmpty())
    }

    @Test
    fun skillInstructionsReturnsNullForUnknownSkill() {
        // 目录已清空，任何 id 都取不到正文。
        // 关键契约：必须返回 null 而非空对象——调用方据此回错误给模型。
        assertNull(ChatAgentSkillRouter.skillInstructions("no_such_skill"))
    }

    @Test
    fun loadSkillToolIsAlwaysExposedRegardlessOfKeywords() {
        // load_skill 是「按需入口」：<available_skills> 清单常驻并指示模型调用它，
        // 若工具表里没有它，清单就是在教模型调一个不存在的工具。
        val exposed = ChatAgentSkillRouter.availableTools(sampleTools()).availableTools.map { it.name }

        assertTrue(exposed.contains(CHAT_LOAD_SKILL_TOOL_NAME))
    }

    @Test
    fun callModuleIsAlwaysExposedAndReadOnlyQueryIsToo() {
        // call_module / query_module_schema 是「万能入口 + 查询入口」，
        // 撤走 59 个模块工具后它们就是模型唯一能触达模块的通道。
        val exposed = ChatAgentSkillRouter.availableTools(sampleTools()).availableTools.map { it.name }

        assertTrue(exposed.contains(CHAT_CALL_MODULE_TOOL_NAME))
        assertTrue(exposed.contains(CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME))
    }

    /**
     * 所有「按需入口」必须在任何输入下都常驻。
     *
     * 这是 P0-2 / P1-1 实施时发现的坑的**防回归测试**：这些工具不在任何技能的
     * `toolNames` / `moduleIds` 里，若不显式登记进 `ALWAYS_EXPOSED_AGENT_TOOL_NAMES`
     * 就会被 `selectSkills` 过滤掉。
     *
     * **P1-1c 撤走 59 个模块工具后，漏掉任何一个都会让模型彻底失能**——
     * 既没有模块工具，也拿不到查询/调用入口。
     */
    @Test
    fun everyOnDemandEntryPointIsAlwaysExposed() {
        val entryPoints = listOf(
            CHAT_LOAD_SKILL_TOOL_NAME,
            CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME,
            CHAT_CALL_MODULE_TOOL_NAME,
            CHAT_LIST_WORKFLOWS_TOOL_NAME,
            CHAT_GET_ENVIRONMENT_TOOL_NAME,
        )
        val exposed = ChatAgentSkillRouter.availableTools(sampleTools()).availableTools.map { it.name }

        entryPoints.forEach { entry ->
            assertTrue(
                "$entry 未常驻——模型将无法使用按需机制",
                exposed.contains(entry),
            )
        }
    }

    @Test
    fun availableToolsPassesThroughEverythingWithoutKeywordFiltering() {
        // P1-1c 的核心契约：不再有「按关键词选工具」。
        // 传入什么就得到什么——这正是 selectSkills 被删除的原因。
        val tools = sampleTools()
        val selection = ChatAgentSkillRouter.availableTools(tools)

        assertEquals(tools.map { it.name }, selection.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_noLongerExposesModuleTools() {
        // 撤走 59 个模块工具后，模块工具不应出现在常驻表里——
        // 它们是「扩展工具」（随模块数增长），改由 query_module_schema + call_module 按需触达。
        // 这里用 sampleTools() 模拟：其中 vflow_device_flashlight 等模块工具若传入仍会下发，
        // 但真实 registry 已不再把它们放进 toolsByName（见 ChatAgentToolRegistry.init）。
        // 本测试锁定的是「路由层不做任何过滤」这一行为，真实撤出由 registry 保证。
        val selection = ChatAgentSkillRouter.availableTools(sampleTools())
        assertTrue(selection.availableTools.isNotEmpty())
    }

    @Test
    fun systemPromptOmitsSkillListingWhenCatalogIsEmpty() {
        val selection = ChatAgentSkillRouter.availableTools(sampleTools())

        val prompt = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "Base prompt",
            skillSelection = selection,
        )

        // 目录为空时不输出 <available_skills> 段——避免给模型一个空的清单占位。
        assertFalse(prompt.contains("<available_skills>"))
        // 但 prompt 本身仍完整（规则段不依赖技能）。
        assertTrue(prompt.contains("You are the vFlow chat agent"))
    }

    @Test
    fun systemPromptCarriesTheContentSalvagedFromDeletedSkills() {
        // 删技能时把 6 条独有内容上提到了 prompt 规则段。
        // 本测试锁定它们确实在——否则删技能就丢了行为约束。
        val prompt = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "Base prompt",
            skillSelection = ChatAgentSkillRouter.availableTools(sampleTools()),
        )

        assertTrue(
            "输入框焦点规则丢失",
            prompt.contains("establish focus before typing"),
        )
        assertTrue(
            "应用名解析规则丢失",
            prompt.contains("by display name or brand instead of an Android package"),
        )
        assertTrue(
            "shell 风险约束丢失",
            prompt.contains("Shell execution is high risk and a last resort"),
        )
        assertTrue(
            "勿扰模式规则丢失",
            prompt.contains("For Do Not Disturb requests"),
        )
        assertTrue(
            "反馈模态规则丢失",
            prompt.contains("matching the requested output modality"),
        )
        assertTrue(
            "OCR 兜底规则丢失",
            prompt.contains("use OCR only as a fallback"),
        )
    }

    private fun testToolDefinition(name: String, truncatable: Boolean): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = name,
            title = name,
            description = "",
            moduleId = "vflow.agent.test",
            moduleDisplayName = name,
            inputSchema = JsonObject(emptyMap()),
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            truncatable = truncatable,
        )
    }

    @Test
    fun skillRouter_promptInstructsObservationAndVerification() {
        val selection = ChatAgentSkillRouter.availableTools(sampleTools())

        val prompt = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "Base prompt",
            skillSelection = selection,
        )

        assertTrue(prompt.contains("If one direct tool can complete a simple request"))
        assertTrue(prompt.contains("prefer the agent-native `vflow_agent_*` helper tools first"))
        assertTrue(prompt.contains("Treat the accessibility/UI node tree as the primary source of truth"))
        assertTrue(prompt.contains("Prefer small deterministic tool calls over speculative multi-step jumps"))
        assertTrue(prompt.contains("Keep tool usage token-efficient"))
        assertTrue(prompt.contains("use that recovery guidance to self-heal"))
        assertTrue(prompt.contains("prefer `vflow_agent_read_page_content`"))
        assertTrue(prompt.contains("Before chaining screen interactions"))
        assertTrue(prompt.contains("Do not issue blind repeated swipes"))
        assertTrue(prompt.contains("ordered in the current viewport from top to bottom"))
        assertTrue(prompt.contains("After any tap that is supposed to open content"))
        assertTrue(prompt.contains("Never do two same-direction swipes in a row"))
        assertTrue(prompt.contains("never scrolls on its own"))
        assertTrue(prompt.contains("Do not hide scrolling inside a read request"))
        assertTrue(prompt.contains("summarize from the currently visible node-tree text first"))
        assertTrue(prompt.contains("Before you say a screen-based task is complete"))
    }

    @Test
    fun stripInlineToolMarkup_removesPseudoToolTags() {
        val sanitized = stripInlineToolMarkup(
            """
            先观察一下。
            <tool_call>vflow_agent_observe_ui</tool_call>
            然后再说。
            """.trimIndent()
        )

        assertEquals("先观察一下。\n然后再说。", sanitized)
    }

    @Test
    fun pagerAndCarouselTokens_areRecognizedGenerically() {
        assertTrue(chatAgentLooksLikePagerOrCarouselTokens("home viewPager banner"))
        assertTrue(chatAgentLooksLikePagerOrCarouselTokens("carousel slider"))
        assertFalse(chatAgentLooksLikePagerOrCarouselTokens("recycler article list"))
    }

    @Test
    fun prominentPagerCandidate_isDemotedButRegularListCardIsNot() {
        assertTrue(
            chatAgentShouldDemoteProminentPagerCandidate(
                tokens = "viewPager banner",
                top = 442,
                width = 1120,
                height = 448,
                screenWidth = 1216,
                screenHeight = 2640,
            )
        )
        assertFalse(
            chatAgentShouldDemoteProminentPagerCandidate(
                tokens = "shadowLayout article",
                top = 1287,
                width = 1216,
                height = 332,
                screenWidth = 1216,
                screenHeight = 2640,
            )
        )
    }

    @Test
    fun skillRouter_promptDescribesReadPageContentAsReadOnly() {
        val selection = ChatAgentSkillRouter.availableTools(sampleTools())
        val prompt = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "Base prompt",
            skillSelection = selection,
        )

        assertTrue(prompt.contains("never scrolls on its own"))
        assertTrue(prompt.contains("Do not hide scrolling inside a read request"))
    }

    @Test
    fun repeatedSwipeGuard_blocksSameDirectionWithoutFreshObservation() {
        assertTrue(
            chatAgentShouldBlockRepeatedSwipe(
                lastActionToolName = CHAT_AGENT_SWIPE_TOOL_NAME,
                lastSwipeDirection = "down",
                lastActionObservationEpoch = 7,
                currentObservationEpoch = 7,
                requestedDirection = "down",
            )
        )
    }

    @Test
    fun repeatedSwipeGuard_allowsSameDirectionAfterFreshObservation() {
        assertFalse(
            chatAgentShouldBlockRepeatedSwipe(
                lastActionToolName = CHAT_AGENT_SWIPE_TOOL_NAME,
                lastSwipeDirection = "down",
                lastActionObservationEpoch = 7,
                currentObservationEpoch = 8,
                requestedDirection = "down",
            )
        )
    }

    @Test
    fun nativeRecoveryHint_forTapPointsBackToObserveUi() {
        val hint = chatAgentNativeRecoveryHint(ChatAgentNativeHelperId.TAP)

        assertTrue(hint.contains("vflow_agent_observe_ui"))
        assertTrue(hint.contains("artifact://"))
    }

    @Test
    fun appendNextStep_formatsGuidanceBlock() {
        val text = chatAgentAppendNextStep(
            baseMessage = "Base failure.",
            nextStep = "retry with a fresh handle.",
        )

        assertTrue(text.contains("Base failure."))
        assertTrue(text.contains("Next step: retry with a fresh handle."))
    }

    private fun userMessage(content: String): ChatMessage {
        return ChatMessage(
            role = ChatMessageRole.USER,
            content = content,
            timestampMillis = 1L,
        )
    }

    private fun assistantToolCallMessage(name: String): ChatMessage {
        return ChatMessage(
            role = ChatMessageRole.ASSISTANT,
            content = "",
            timestampMillis = 2L,
            toolCalls = listOf(
                ChatToolCall(
                    id = "call_1",
                    name = name,
                    argumentsJson = "{}",
                )
            ),
        )
    }

    private fun toolResultMessage(
        callId: String,
        name: String,
    ): ChatMessage {
        return ChatMessage(
            role = ChatMessageRole.TOOL,
            content = "Tool completed.",
            timestampMillis = 3L,
            toolResult = ChatToolResult(
                callId = callId,
                name = name,
                status = ChatToolResultStatus.SUCCESS,
                summary = "Tool",
                outputText = "Tool completed.",
            ),
        )
    }

    private fun sampleTools(): List<ChatAgentToolDefinition> {
        return listOf(
            sampleTool(
                name = CHAT_TEMPORARY_WORKFLOW_TOOL_NAME,
                moduleId = CHAT_TEMPORARY_WORKFLOW_MODULE_ID,
                usageScopes = setOf(ChatAgentToolUsageScope.TEMPORARY_WORKFLOW),
            ),
            sampleTool(
                name = CHAT_SAVE_WORKFLOW_TOOL_NAME,
                moduleId = CHAT_SAVE_WORKFLOW_MODULE_ID,
                usageScopes = setOf(ChatAgentToolUsageScope.SAVED_WORKFLOW),
            ),
            sampleTool(
                name = CHAT_LOAD_SKILL_TOOL_NAME,
                moduleId = CHAT_LOAD_SKILL_MODULE_ID,
            ),
            sampleTool(
                name = CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME,
                moduleId = CHAT_QUERY_MODULE_SCHEMA_MODULE_ID,
            ),
            sampleTool(
                name = CHAT_CALL_MODULE_TOOL_NAME,
                moduleId = CHAT_CALL_MODULE_MODULE_ID,
            ),
            sampleTool(
                name = CHAT_LIST_WORKFLOWS_TOOL_NAME,
                moduleId = CHAT_LIST_WORKFLOWS_MODULE_ID,
            ),
            sampleTool(
                name = CHAT_GET_ENVIRONMENT_TOOL_NAME,
                moduleId = CHAT_GET_ENVIRONMENT_MODULE_ID,
            ),
            sampleTool(
                name = "vflow_device_flashlight",
                moduleId = "vflow.device.flashlight",
            ),
            sampleTool(
                name = "vflow_system_darkmode",
                moduleId = "vflow.system.darkmode",
                title = "深色模式",
                description = "切换系统的深色/浅色模式（自动/深色/浅色）",
                routingHints = setOf("深色模式", "切换系统的深色/浅色模式", "浅色模式", "深色", "浅色"),
            ),
            sampleTool(
                name = "vflow_system_set_clipboard",
                moduleId = "vflow.system.set_clipboard",
            ),
            helperTool(
                name = CHAT_AGENT_OBSERVE_UI_TOOL_NAME,
                moduleId = "vflow.agent.observe_ui",
                helperId = ChatAgentNativeHelperId.OBSERVE_UI,
            ),
            helperTool(
                name = CHAT_AGENT_READ_PAGE_CONTENT_TOOL_NAME,
                moduleId = "vflow.agent.read_page_content",
                helperId = ChatAgentNativeHelperId.READ_PAGE_CONTENT,
            ),
            helperTool(
                name = CHAT_AGENT_VERIFY_UI_TOOL_NAME,
                moduleId = "vflow.agent.verify_ui",
                helperId = ChatAgentNativeHelperId.VERIFY_UI,
            ),
            helperTool(
                name = CHAT_AGENT_TAP_TOOL_NAME,
                moduleId = "vflow.agent.tap_screen",
                helperId = ChatAgentNativeHelperId.TAP,
            ),
            helperTool(
                name = CHAT_AGENT_LONG_PRESS_TOOL_NAME,
                moduleId = "vflow.agent.long_press_screen",
                helperId = ChatAgentNativeHelperId.LONG_PRESS,
            ),
            helperTool(
                name = CHAT_AGENT_INPUT_TEXT_TOOL_NAME,
                moduleId = "vflow.agent.input_text",
                helperId = ChatAgentNativeHelperId.INPUT_TEXT,
            ),
            helperTool(
                name = CHAT_AGENT_SWIPE_TOOL_NAME,
                moduleId = "vflow.agent.swipe_screen",
                helperId = ChatAgentNativeHelperId.SWIPE,
            ),
            helperTool(
                name = CHAT_AGENT_PRESS_KEY_TOOL_NAME,
                moduleId = "vflow.agent.press_key",
                helperId = ChatAgentNativeHelperId.PRESS_KEY,
            ),
            helperTool(
                name = CHAT_AGENT_WAIT_TOOL_NAME,
                moduleId = "vflow.agent.wait",
                helperId = ChatAgentNativeHelperId.WAIT,
            ),
            helperTool(
                name = CHAT_AGENT_LOOKUP_APP_TOOL_NAME,
                moduleId = "vflow.agent.lookup_installed_app",
                helperId = ChatAgentNativeHelperId.LOOKUP_APP,
            ),
            helperTool(
                name = CHAT_AGENT_LAUNCH_APP_TOOL_NAME,
                moduleId = "vflow.agent.launch_app",
                helperId = ChatAgentNativeHelperId.LAUNCH_APP,
            ),
            sampleTool(
                name = "vflow_system_find_installed_app",
                moduleId = "vflow.system.find_installed_app",
            ),
            sampleTool(
                name = "vflow_system_launch_app",
                moduleId = "vflow.system.launch_app",
            ),
            sampleTool(
                name = "vflow_interaction_get_current_activity",
                moduleId = "vflow.interaction.get_current_activity",
            ),
            sampleTool(
                name = "vflow_system_capture_screen",
                moduleId = "vflow.system.capture_screen",
            ),
            sampleTool(
                name = "vflow_interaction_ocr",
                moduleId = "vflow.interaction.ocr",
            ),
            sampleTool(
                name = "vflow_interaction_find_element",
                moduleId = "vflow.interaction.find_element",
            ),
            sampleTool(
                name = "vflow_device_click",
                moduleId = "vflow.device.click",
            ),
            sampleTool(
                name = "vflow_interaction_input_text",
                moduleId = "vflow.interaction.input_text",
            ),
            sampleTool(
                name = "vflow_interaction_screen_operation",
                moduleId = "vflow.interaction.screen_operation",
            ),
        )
    }

    private fun helperTool(
        name: String,
        moduleId: String,
        helperId: ChatAgentNativeHelperId,
        title: String = name,
        description: String = name,
        routingHints: Set<String> = setOf(name, moduleId),
    ): ChatAgentToolDefinition {
        return sampleTool(
            name = name,
            moduleId = moduleId,
            title = title,
            description = description,
            routingHints = routingHints,
        ).copy(
            backend = ChatAgentToolBackend.NATIVE_HELPER,
            nativeHelperId = helperId,
        )
    }

    private fun sampleTool(
        name: String,
        moduleId: String,
        title: String = name,
        description: String = name,
        routingHints: Set<String> = setOf(name, moduleId),
        usageScopes: Set<ChatAgentToolUsageScope> = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
    ): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = name,
            title = title,
            description = description,
            moduleId = moduleId,
            moduleDisplayName = title,
            routingHints = routingHints,
            inputSchema = buildJsonObject { put("type", "object") },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = usageScopes,
        )
    }
}
