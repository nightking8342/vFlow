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
    fun skillRouter_plainChatDoesNotAttachSkillsOrTools() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("解释一下 Koog 的设计思路")),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.isEmpty())
        assertEquals(alwaysExposedToolNames(), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_directActionActivatesFlashlightSkill() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("打开手电筒")),
            availableTools = sampleTools(),
        )

        assertEquals(listOf("flashlight_control"), selected.skills.map { it.id })
        assertEquals(expectedToolNames("vflow_device_flashlight"), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_screenObservationRequestUsesObserveUiHelper() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("当前页面有什么控件")),
            availableTools = sampleTools(),
        )

        assertEquals(listOf("screen_observation"), selected.skills.map { it.id })
        assertEquals(
            expectedToolNames(
                "vflow_interaction_get_current_activity",
                "vflow_interaction_find_element",
            ),
            selected.availableTools.map { it.name }
        )
    }

    @Test
    fun skillRouter_lightModeRequestUsesDirectDarkModeTool() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("切换到浅色模式")),
            availableTools = sampleTools(),
        )

        assertEquals(listOf("device_settings_control"), selected.skills.map { it.id })
        assertEquals(expectedToolNames("vflow_system_darkmode"), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_openAppRequestIncludesInstalledAppLookupTool() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("打开IT之家看看最新的新闻")),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.map { it.id }.contains("app_lifecycle"))
        assertTrue(selected.skills.map { it.id }.contains("screen_observation"))
        assertTrue(selected.availableTools.map { it.name }.contains(CHAT_AGENT_LOOKUP_APP_TOOL_NAME))
        assertTrue(selected.availableTools.map { it.name }.contains(CHAT_AGENT_LAUNCH_APP_TOOL_NAME))
        assertTrue(selected.availableTools.map { it.name }.contains(CHAT_AGENT_OBSERVE_UI_TOOL_NAME))
        assertTrue(selected.availableTools.map { it.name }.contains(CHAT_AGENT_READ_PAGE_CONTENT_TOOL_NAME))
        assertTrue(selected.availableTools.map { it.name }.contains("vflow_system_find_installed_app"))
        assertTrue(selected.availableTools.map { it.name }.contains("vflow_system_launch_app"))
        assertTrue(selected.availableTools.map { it.name }.contains("vflow_interaction_find_element"))
        assertTrue(!selected.availableTools.map { it.name }.contains("vflow_interaction_ocr"))
        assertTrue(!selected.availableTools.map { it.name }.contains("vflow_system_capture_screen"))
    }

    @Test
    fun skillRouter_metadataMatchedDirectToolActivatesOwningSkill() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("打开照明")),
            availableTools = sampleTools().map { tool ->
                if (tool.name == "vflow_device_flashlight") {
                    tool.copy(routingHints = setOf("照明"))
                } else {
                    tool
                }
            },
        )

        assertEquals(listOf("flashlight_control"), selected.skills.map { it.id })
        assertEquals(expectedToolNames("vflow_device_flashlight"), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_temporaryWorkflowActivatesWorkflowSkillOnly() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("把手电筒重复打开关闭三次")),
            availableTools = sampleTools(),
        )

        assertEquals(listOf("temporary_workflow_execution"), selected.skills.map { it.id })
        assertEquals(expectedToolNames(CHAT_TEMPORARY_WORKFLOW_TOOL_NAME), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_savedWorkflowActivatesSaveSkillOnly() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("创建一个每天 8 点打开手电筒的工作流")),
            availableTools = sampleTools(),
        )

        assertEquals(listOf("saved_workflow_creation"), selected.skills.map { it.id })
        assertEquals(expectedToolNames(CHAT_SAVE_WORKFLOW_TOOL_NAME), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_continuationKeepsPriorSkillForExplicitFollowUp() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(
                userMessage("打开手电筒"),
                ChatMessage(
                    role = ChatMessageRole.ASSISTANT,
                    content = "",
                    timestampMillis = 2L,
                    toolCalls = listOf(
                        ChatToolCall(
                            id = "call_1",
                            name = "vflow_device_flashlight",
                            argumentsJson = "{}",
                        )
                    ),
                ),
                ChatMessage(
                    role = ChatMessageRole.TOOL,
                    content = "Tool completed.",
                    timestampMillis = 3L,
                    toolResult = ChatToolResult(
                        callId = "call_1",
                        name = "vflow_device_flashlight",
                        status = ChatToolResultStatus.SUCCESS,
                        summary = "Flashlight",
                        outputText = "Tool completed.",
                    ),
                ),
                userMessage("再来一次"),
            ),
            availableTools = sampleTools(),
        )

        assertEquals(listOf("flashlight_control"), selected.skills.map { it.id })
        assertEquals(expectedToolNames("vflow_device_flashlight"), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_uiInteractionAlsoEnablesObservationSkill() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("点击登录按钮")),
            availableTools = sampleTools(),
        )

        assertEquals(
            listOf("ui_interaction", "screen_observation"),
            selected.skills.map { it.id }
        )
        assertEquals(
            expectedToolNames(
                "vflow_interaction_get_current_activity",
                "vflow_interaction_find_element",
                "vflow_device_click",
                "vflow_interaction_input_text",
                "vflow_interaction_screen_operation",
            ),
            selected.availableTools.map { it.name }
        )
    }

    @Test
    fun skillRouter_explicitScreenshotRequestActivatesVisualFallbackSkill() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("截个图然后 OCR 一下当前页面")),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.map { it.id }.contains("visual_screen_fallback"))
        assertTrue(selected.availableTools.map { it.name }.contains("vflow_system_capture_screen"))
        assertTrue(selected.availableTools.map { it.name }.contains("vflow_interaction_ocr"))
        assertTrue(selected.availableTools.map { it.name }.contains(CHAT_AGENT_OBSERVE_UI_TOOL_NAME))
    }

    @Test
    fun skillRouter_unrelatedFollowUpDoesNotLeakPriorSkill() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(
                userMessage("打开手电筒"),
                assistantToolCallMessage("vflow_device_flashlight"),
                toolResultMessage("call_1", "vflow_device_flashlight"),
                userMessage("workflow 是什么意思"),
            ),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.isEmpty())
        assertEquals(alwaysExposedToolNames(), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_conceptualWorkflowQuestionDoesNotActivateWorkflowSkills() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("workflow 是什么意思")),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.isEmpty())
        assertEquals(alwaysExposedToolNames(), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_genericTransitionQuestionDoesNotActivateTemporaryWorkflow() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("然后为什么会这样")),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.isEmpty())
        assertEquals(alwaysExposedToolNames(), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_alwaysExposesCoreNativeHelpers() {
        val selected = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("你好")),
            availableTools = sampleTools(),
        )

        assertTrue(selected.skills.isEmpty())
        assertEquals(alwaysExposedToolNames(), selected.availableTools.map { it.name })
    }

    @Test
    fun skillRouter_promptIncludesAlwaysAvailableHelpersWithoutActiveSkills() {
        val selection = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("你好")),
            availableTools = sampleTools(),
        )

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
        assertTrue(formatted.contains("... truncated"))
        assertTrue(formatted.length <= CHAT_MAX_TOOL_RESULT_INPUT_CHARS)
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
        assertFalse(formatted.contains("... truncated"))
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
        assertTrue(explicit.contains("... truncated"))

        // 名字查不到时按可截断处理（与改造前行为一致）
        val unknown = ChatToolResultInputFormatter.format(
            message = message,
            toolResult = toolResult,
            toolDefinitions = emptyList(),
        )
        assertTrue(unknown.contains("... truncated"))
    }

    @Test
    fun skillListingContainsIdTitleAndDescriptionOnly() {
        val listing = ChatAgentSkillRouter.skillListing()

        assertTrue("技能清单不应为空", listing.isNotEmpty())
        // 清单用于 system prompt 常驻段，必须只含轻量字段。
        assertTrue(listing.all { it.id.isNotBlank() })
        assertTrue(listing.all { it.title.isNotBlank() })
        assertTrue(listing.all { it.description.isNotBlank() })
        // 14 个技能（P0-2 不改技能集合，瘦身留给 P1-3B）
        assertEquals(14, listing.size)
    }

    @Test
    fun skillInstructionsReturnsFullBodyForKnownSkill() {
        val skill = requireNotNull(ChatAgentSkillRouter.skillInstructions("flashlight_control"))

        assertEquals("flashlight_control", skill.id)
        assertTrue(skill.instructions.isNotBlank())
        // 正文是「加载它就是为了拿到全部内容」，不做裁剪。
        assertTrue(skill.instructions.contains("flashlight"))
    }

    @Test
    fun skillInstructionsReturnsNullForUnknownSkill() {
        assertNull(ChatAgentSkillRouter.skillInstructions("no_such_skill"))
    }

    @Test
    fun loadSkillToolIsAlwaysExposedRegardlessOfKeywords() {
        // load_skill 是「按需入口」：<available_skills> 清单常驻并指示模型调用它，
        // 若工具表里没有它，清单就是在教模型调一个不存在的工具。
        val listingTools = { text: String ->
            ChatAgentSkillRouter.selectSkills(
                history = listOf(userMessage(text)),
                availableTools = sampleTools(),
            ).availableTools.map { it.name }
        }

        assertTrue(
            "纯闲聊轮也必须暴露 load_skill",
            listingTools("解释一下 Koog 的设计思路").contains(CHAT_LOAD_SKILL_TOOL_NAME),
        )
        assertTrue(
            "操作轮也必须暴露 load_skill",
            listingTools("打开手电筒").contains(CHAT_LOAD_SKILL_TOOL_NAME),
        )
        assertTrue(
            "空输入轮也必须暴露 load_skill",
            listingTools("").contains(CHAT_LOAD_SKILL_TOOL_NAME),
        )
    }

    @Test
    fun callModuleIsAlwaysExposedAndReadOnlyQueryIsToo() {
        // call_module / query_module_schema 是「万能入口 + 查询入口」，
        // 撤走 59 个模块工具后它们就是模型唯一能触达模块的通道。
        val exposed = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("解释一下 Koog 的设计思路")),
            availableTools = sampleTools(),
        ).availableTools.map { it.name }

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
        )
        // 覆盖各种路由结果：闲聊（无技能）、单技能命中、工作流技能命中
        val inputs = listOf("", "解释一下 Koog 的设计思路", "打开手电筒", "创建一个每天 8 点开灯的工作流")

        inputs.forEach { text ->
            val exposed = ChatAgentSkillRouter.selectSkills(
                history = listOf(userMessage(text)),
                availableTools = sampleTools(),
            ).availableTools.map { it.name }

            entryPoints.forEach { entry ->
                assertTrue(
                    "输入「$text」下 $entry 未常驻——模型将无法使用按需机制",
                    exposed.contains(entry),
                )
            }
        }
    }

    @Test
    fun systemPromptListsSkillsWithoutEmbeddingInstructions() {
        val selection = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("打开手电筒")),
            availableTools = sampleTools(),
        )

        val prompt = ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = "Base prompt",
            skillSelection = selection,
        )

        // 清单在
        assertTrue(prompt.contains("<available_skills>"))
        assertTrue(prompt.contains("flashlight_control"))
        assertTrue(prompt.contains(CHAT_LOAD_SKILL_TOOL_NAME))
        // 正文不在——这是治疗病症 A 的关键：正文改走 tool result 进历史。
        val flashlight = requireNotNull(ChatAgentSkillRouter.skillInstructions("flashlight_control"))
        assertTrue(
            "技能正文不得出现在 system prompt 中",
            !prompt.contains(flashlight.instructions.trim()),
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
        val selection = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("点击登录按钮")),
            availableTools = sampleTools(),
        )

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
        // 技能**正文**不再进 system prompt——改为按需经 `load_skill` 加载。
        // 这里断言的是「清单在、正文不在」这个新契约。
        assertTrue(prompt.contains("<available_skills>"))
        assertTrue(prompt.contains("screen_observation"))
        assertTrue(!prompt.contains("Prefer `find_element` over OCR"))
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
        val selection = ChatAgentSkillRouter.selectSkills(
            history = listOf(userMessage("点击登录按钮")),
            availableTools = sampleTools(),
        )
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

    private fun alwaysExposedNativeToolNames(): List<String> {
        return listOf(
            CHAT_AGENT_OBSERVE_UI_TOOL_NAME,
            CHAT_AGENT_READ_PAGE_CONTENT_TOOL_NAME,
            CHAT_AGENT_VERIFY_UI_TOOL_NAME,
            CHAT_AGENT_TAP_TOOL_NAME,
            CHAT_AGENT_LONG_PRESS_TOOL_NAME,
            CHAT_AGENT_INPUT_TEXT_TOOL_NAME,
            CHAT_AGENT_SWIPE_TOOL_NAME,
            CHAT_AGENT_PRESS_KEY_TOOL_NAME,
            CHAT_AGENT_WAIT_TOOL_NAME,
            CHAT_AGENT_LOOKUP_APP_TOOL_NAME,
            CHAT_AGENT_LAUNCH_APP_TOOL_NAME,
        )
    }

    /**
     * 预期工具表 = 常驻 helper + 常驻的按需入口（`load_skill`）+ 本轮命中的模块工具。
     *
     * `load_skill` 恒在：`<available_skills>` 清单常驻并指示模型调用它，
     * 工具表里没有它就是在教模型调一个不存在的工具。
     */
    /**
     * 完整常驻工具表 = 按需入口 + 屏幕 helper。
     *
     * 顺序必须与 [sampleTools] 里的排列一致——`selectSkills` 保持输入顺序。
     */
    private fun alwaysExposedToolNames(): List<String> {
        return listOf(
            CHAT_LOAD_SKILL_TOOL_NAME,
            CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME,
            CHAT_CALL_MODULE_TOOL_NAME,
        ) + alwaysExposedNativeToolNames()
    }

    private fun expectedToolNames(vararg extra: String): List<String> {
        return alwaysExposedToolNames() + extra.toList()
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
