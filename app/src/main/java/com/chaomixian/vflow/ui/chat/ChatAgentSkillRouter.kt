package com.chaomixian.vflow.ui.chat

/**
 * 一项技能的**定义**。
 *
 * P1-1c 之后它只剩「按需加载的参考资料」一个职责：
 * - 清单条目（[id] / [title] / [description]）常驻 system prompt，供模型判断要不要加载
 * - [instructions] 正文经 `load_skill` 按需返回，作为 tool result 进入历史后永久留存
 *
 * 原先的 `toolNames` / `moduleIds` 字段已退役——它们服务的是「按关键词决定下发哪些工具」
 * 那套机制，模块工具撤出后该机制整体删除（见 [ChatAgentSkillRouter.availableTools]）。
 */
internal data class ChatAgentSkillDefinition(
    val id: String,
    val title: String,
    val description: String,
    val instructions: String,
)

/**
 * 本轮对话可用的工具集。
 *
 * **P1-1c 之后语义已变**：不再是「按关键词选出的技能所声明的工具」，
 * 而是**全部常驻工具**（2 个工作流工具 + 3 个按需入口 + 11 个屏幕 helper）。
 * 模块工具已撤出，改由 `query_module_schema` + `call_module` 按需触达。
 *
 * 保留这个类型是为了让 provider 适配层（`ChatCompletionClient`）无需改动——
 * 它只关心「有哪些工具要下发」。
 */
internal data class ChatAgentSkillSelection(
    val availableTools: List<ChatAgentToolDefinition>,
) {
    companion object {
        val EMPTY = ChatAgentSkillSelection(availableTools = emptyList())
    }
}

internal object ChatAgentSkillRouter {
    /**
     * 全部技能的**清单条目**（id / title / description），供 `<available_skills>` 常驻段使用。
     * 不含正文——正文经 [skillInstructions] 按需加载。
     */
    fun skillListing(): List<ChatAgentSkillDefinition> = SKILL_CATALOG

    /**
     * 按 id 取技能**正文**，供 `load_skill` 工具返回。
     * 找不到时返回 null（调用方据此回错误给模型，而不是静默返回空）。
     */
    fun skillInstructions(skillId: String): ChatAgentSkillDefinition? =
        SKILL_CATALOG.firstOrNull { it.id == skillId }

    /**
     * 构造本轮的常驻工具集。
     *
     * **P1-1c 之后不再有「选择」**：模块工具已撤出 `tools` 数组，剩下的
     * （2 个工作流工具 + 3 个按需入口 + 11 个 screen helper）**全部常驻**——
     * 它们不随关键词变化，按关键词过滤恒等于不过滤。
     *
     * 这正是改造前 `selectSkills` 的归宿：它唯一的作用是「从 59 个模块工具里
     * 挑几个发出去」（见文档 §1.1 实测），那个集合消失后它就失去全部语义，
     * 故整体删除——连同它依赖的 `CONTINUATION_SIGNALS` /
     * `KNOWLEDGE_QUESTION_SIGNALS` / `OPERATIONAL_SIGNALS` / `moduleIds` 白名单。
     *
     * 模块能力改由 `query_module_schema`（查字段）+ `call_module`（执行）按需触达。
     */
    fun availableTools(allTools: List<ChatAgentToolDefinition>): ChatAgentSkillSelection {
        if (allTools.isEmpty()) return ChatAgentSkillSelection.EMPTY
        return ChatAgentSkillSelection(availableTools = allTools)
    }

    fun buildSystemPrompt(
        basePrompt: String,
        skillSelection: ChatAgentSkillSelection,
    ): String {
        val trimmedBasePrompt = basePrompt.trim()
        if (skillSelection.availableTools.isEmpty()) return trimmedBasePrompt

        val skillPrompt = buildString {
            appendLine("You are the vFlow chat agent inside an Android automation app.")
            appendLine("Only use the tools exposed below.")
            appendLine("Prefer the narrowest direct tool that can complete the request safely.")
            appendLine("For screen observation and on-screen interaction, prefer the agent-native `vflow_agent_*` helper tools first. Treat human-oriented vFlow action modules as supplemental fallbacks.")
            appendLine("Treat the accessibility/UI node tree as the primary source of truth. Keep screenshots, OCR, and other visual tools as explicit fallback paths so future multimodal models can use them without making them the default.")
            appendLine("If one direct tool can complete a simple request such as dark mode, flashlight, wifi, brightness, clipboard, volume, or app launch, call that direct tool instead of navigating system UI or building a workflow.")
            appendLine("Use canonical module parameters and step IDs; never invent localized parameter keys.")
            appendLine("Ask one concise clarification only when a missing target, time, account, or condition would make the action ambiguous or risky.")
            appendLine("Never claim a tool succeeded until you receive the tool result.")
            appendLine("If a tool result includes artifact:// handles, preserve and reuse them in later tool arguments when needed.")
            appendLine("Prefer small deterministic tool calls over speculative multi-step jumps. Observe, act once, then verify.")
            appendLine("Keep tool usage token-efficient: rely on concise summaries and artifact handles instead of asking tools to dump raw data unless you truly need it.")
            appendLine("When a tool returns an error or guardrail message, use that recovery guidance to self-heal. Do not repeat the same failing call unchanged.")
            appendLine("If the latest user turn is conceptual or explanatory, answer normally instead of forcing a tool call.")
            appendLine("Before chaining screen interactions, first make a fresh read-only observation of the current UI with `vflow_agent_observe_ui` or another accessibility-first helper, and prefer returned ScreenElement handles or verified ids instead of guessed text.")
            appendLine("Do not issue blind repeated swipes. If a top-ranked content target that matches the task is already visible, tap or verify it before scrolling.")
            appendLine("On feed/list screens, treat the returned primary content targets as ordered in the current viewport from top to bottom. For requests like first/latest visible item, use that visible order before considering any scroll.")
            appendLine("After any tap that is supposed to open content, re-observe once before deciding to swipe. Treat an activity change or a detail-like screen role as a strong navigation-success signal.")
            appendLine("Never do two same-direction swipes in a row without a fresh observation in between.")
            appendLine("When you need article text or page content for summarization, prefer `vflow_agent_read_page_content`, which reads from the current visible UI node tree and never scrolls on its own.")
            appendLine("If `vflow_agent_read_page_content` says the screen still looks like a feed/list, go back to target selection instead of continuing to scroll or summarize the feed.")
            appendLine("If more content is needed after a read, decide explicitly whether to swipe, then re-observe or read again. Do not hide scrolling inside a read request.")
            appendLine("On detail/article screens, summarize from the currently visible node-tree text first. Only continue scrolling when the visible text is clearly insufficient, and avoid long downward swipe chains that skip past正文.")
            appendLine("Before you say a screen-based task is complete, perform a final read-only verification step. If you could not verify the final state, say that it is not yet verified.")
            val alwaysOnNativeTools = skillSelection.availableTools.filter(::isAlwaysExposedNativeHelper)
            if (alwaysOnNativeTools.isNotEmpty()) {
                appendLine()
                appendLine("Always-available agent-native helpers:")
                append("- ")
                appendLine(alwaysOnNativeTools.joinToString(separator = ", ") { it.name })
                appendLine("These helpers stay available even when no specialized skill is selected.")
            }
            // 技能**清单**常驻：只放 id + 标题 + 一句话描述，供模型判断「要不要加载」。
            // **正文不再进 system prompt**——它在模型调用 load_skill 时作为 tool result
            // 进入对话历史，之后永久留存。这治的是「技能随话题切换而消失」：
            // 正文若每轮重算，话题一换就掉出上下文。
            val listing = skillListing()
            if (listing.isNotEmpty()) {
                appendLine()
                appendLine("<available_skills>")
                listing.forEach { skill ->
                    append("- ")
                    append(skill.id)
                    append(": ")
                    append(skill.title)
                    append(" — ")
                    appendLine(skill.description)
                }
                appendLine("</available_skills>")
                appendLine(
                    "Skills above are listed by name only. " +
                        "Call `$CHAT_LOAD_SKILL_TOOL_NAME` with a skill id to load its full instructions " +
                        "before doing work that the skill covers."
                )
            }
        }.trim()

        return listOf(trimmedBasePrompt, skillPrompt)
            .filter { it.isNotBlank() }
            .joinToString(separator = "\n\n")
    }

    /**
     * 该工具是否**无条件常驻**，不受技能关键词路由影响。
     *
     * 两类：
     * 1. 屏幕操作 helper——它们是模型的「感官手脚」，任何一轮都可能需要，
     *    不能因关键词没命中就消失（`ALWAYS_EXPOSED_NATIVE_HELPERS` 即全部 11 个 helper）。
     * 2. **按需入口**（`load_skill`）——`<available_skills>` 清单常驻 system prompt 并
     *    指示模型调用它，若工具表里没有它，清单就是在教模型调一个不存在的工具。
     */
    private fun isAlwaysExposedTool(tool: ChatAgentToolDefinition): Boolean {
        return isAlwaysExposedNativeHelper(tool) || tool.name in ALWAYS_EXPOSED_AGENT_TOOL_NAMES
    }

    /**
     * 常驻的「按需入口」工具名。
     *
     * 它们不在任何技能的 `toolNames` / `moduleIds` 里，若不加进常驻就会被
     * 工具路由过滤掉——模型看不到入口，也就用不上按需机制。
     * （P1-1c 之前是 `selectSkills` 在做过滤，它已随模块工具撤出一并删除。）
     *
     * ⚠️ 新增按需入口（如 P1-1b 的 `call_module`）时**必须**登记到这里。
     * 尤其 P1-1c 撤走 59 个模块工具后，漏登记会让模型既没有模块工具、
     * 也拿不到查询入口——彻底失能。
     */
    private val ALWAYS_EXPOSED_AGENT_TOOL_NAMES = setOf(
        CHAT_LOAD_SKILL_TOOL_NAME,
        CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME,
        CHAT_CALL_MODULE_TOOL_NAME,
    )

    /** 常驻的屏幕操作 helper（不含按需入口——prompt 里那段只描述 helper）。 */
    private fun isAlwaysExposedNativeHelper(tool: ChatAgentToolDefinition): Boolean {
        return tool.backend == ChatAgentToolBackend.NATIVE_HELPER &&
            tool.nativeHelperId in ALWAYS_EXPOSED_NATIVE_HELPERS
    }



    private val temporaryWorkflowSkill = ChatAgentSkillDefinition(
        id = "temporary_workflow_execution",
        title = "Temporary Workflow Execution",
        description = "Execute deterministic multi-step or repeated device actions in one approval.",
        instructions = """
            Use `vflow_agent_run_temporary_workflow` only for one-off multi-step or repeated device actions.
            Generate a real workflow object with canonical `moduleId`, `parameters`, and stable snake_case step IDs.
            Temporary workflows must never include trigger modules or nested workflow tools.
            Prefer loop modules for repeated sequences instead of duplicating many steps.
            If a single direct tool can finish the request safely, prefer that direct tool instead.
        """.trimIndent(),
    )

    private val savedWorkflowSkill = ChatAgentSkillDefinition(
        id = "saved_workflow_creation",
        title = "Saved Workflow Creation",
        description = "Create reusable automations that appear in the user's workflow list.",
        instructions = """
            Use `vflow_agent_save_workflow` when the user asks to create, save, or generate an automation for later reuse.
            Put trigger modules only in `workflow.triggers` and action/data/logic modules only in `workflow.steps`.
            If the user did not request a trigger, omit `workflow.triggers` and let the app add a manual trigger.
            Never persist artifact:// handles inside saved workflows because chat artifacts are temporary.
        """.trimIndent(),
    )

    private val flashlightSkill = ChatAgentSkillDefinition(
            id = "flashlight_control",
            title = "Flashlight Control",
            description = "Operate the flashlight directly without UI automation.",
            instructions = """
                Use the direct flashlight tool for on/off/toggle requests.
                Do not open system UI, take screenshots, or search the screen for flashlight requests.
            """.trimIndent(),
    )

    private val clipboardSkill = ChatAgentSkillDefinition(
            id = "clipboard_and_share",
            title = "Clipboard And Share",
            description = "Read, write, and share clipboard-oriented content directly.",
            instructions = """
                Use clipboard or share tools for copy, paste, share, and quick-view tasks.
                Prefer direct clipboard tools instead of UI automation unless the user explicitly asks to interact inside an app screen.
            """.trimIndent(),
    )

    private val connectivitySkill = ChatAgentSkillDefinition(
            id = "device_settings_control",
            title = "Device Settings Control",
            description = "Toggle or adjust direct device settings without navigating system UI.",
            instructions = """
                Use direct system tools for wifi, bluetooth, brightness, mobile data, dark mode, Do Not Disturb, and volume changes.
                For dark/light theme requests, call the direct dark mode tool with the requested mode instead of opening Settings.
                For Do Not Disturb requests, call the direct Do Not Disturb tool with on, off, or toggle instead of opening Settings.
                Avoid opening Settings or Quick Settings when a direct tool can perform the change safely.
            """.trimIndent(),
    )

    private val screenStateSkill = ChatAgentSkillDefinition(
            id = "screen_state_control",
            title = "Screen State Control",
            description = "Wake, sleep, lock, or unlock the screen directly.",
            instructions = """
                Use the direct screen state tools for wake, sleep, lock, and unlock requests.
                Do not build a workflow unless the user asks for repetition or a sequence involving multiple actions.
            """.trimIndent(),
    )

    private val observationSkill = ChatAgentSkillDefinition(
            id = "screen_observation",
            title = "Screen Observation",
            description = "Observe the current screen, activity, or visible text when state is unknown.",
            instructions = """
                Use read-only tools to build a fresh picture of the current UI before complex screen interactions and again before final completion.
                Prefer `vflow_agent_observe_ui` for a full control snapshot and `vflow_agent_verify_ui` for final confirmation.
                Prefer `vflow_agent_read_page_content` when the task depends on reading article text, visible copy, or current page content from the node tree.
                Treat `vflow_agent_read_page_content` as a pure read-only snapshot; if more content is needed, choose an explicit swipe yourself and then read again.
                Treat activity changes and detail-like screen roles as strong evidence that navigation already succeeded.
                On feed/list screens, treat the primary content targets as already ordered by the current viewport from top to bottom.
                On detail/article screens, use the visible node-tree text before asking for more downward scrolling.
                Do not recommend scrolling while the requested top content target is already visible on screen.
                Prefer `find_element` over OCR when the UI is in the accessibility tree; use it only as a module-level fallback.
                Use current-activity tools only when the foreground app or activity must be confirmed before acting.
                Prefer direct action tools when they can complete the request without observation.
            """.trimIndent(),
    )

    private val visualFallbackSkill = ChatAgentSkillDefinition(
            id = "visual_screen_fallback",
            title = "Visual Screen Fallback",
            description = "Capture screenshots or use OCR only when the user explicitly asks for visual inspection or when non-visual node-tree tools are insufficient.",
            instructions = """
                This is an explicit visual fallback layer.
                Prefer the accessibility/node-tree helper tools first.
                Use screenshot capture or OCR only when the user explicitly requests screenshot/OCR behavior, or when a future multimodal model needs visual evidence for a UI surface the node tree cannot expose.
            """.trimIndent(),
    )

    private val uiInteractionSkill = ChatAgentSkillDefinition(
            id = "ui_interaction",
            title = "UI Interaction",
            description = "Tap, swipe, type, or press keys inside app UI when direct tools are not enough.",
            instructions = """
                Use the agent-native tap, long-press, swipe, input, key, and wait helpers as the primary screen-operation layer.
                Before the first interaction in a multi-step UI flow, observe the screen and work from returned ScreenElement handles or verified id data instead of guessing labels or coordinates.
                Never issue repeated swipes in the same direction without an intervening observation.
                If a visible top-ranked content target already satisfies a request like "open the first article", tap it before any scroll.
                Treat feed/list target ordering as the current viewport order unless a tool result proves otherwise.
                On detail/article screens, scrolling is an explicit agent decision; after each swipe, re-observe or re-read before deciding whether another swipe is justified.
                After a tap that should navigate, re-observe first; only scroll if the fresh observation shows that navigation did not happen and the desired target is no longer visible.
                Re-observe after meaningful screen changes and perform a final verification check before declaring the task complete.
                Input-text tools type into the focused field, so establish focus before typing when necessary.
            """.trimIndent(),
    )

    private val appLifecycleSkill = ChatAgentSkillDefinition(
            id = "app_lifecycle",
            title = "App Lifecycle",
            description = "Launch, stop, or inspect app state directly.",
            instructions = """
                Use the agent-native app lookup and launch helpers before falling back to app modules.
                If the user names an app by display name or brand instead of an Android package, resolve it with the installed-app lookup helper before launching or closing it.
                After launching an app for inspection, use read-only observation tools to confirm the foreground app or visible content when needed.
                Use current activity only when the active app or screen must be confirmed before acting.
            """.trimIndent(),
    )

    private val notificationSkill = ChatAgentSkillDefinition(
            id = "notifications",
            title = "Notifications",
            description = "Send or manage local notifications.",
            instructions = """
                Use notification tools for creating, finding, or removing Android notifications.
                Do not route notification requests through UI automation unless the user explicitly asks to interact with another app.
            """.trimIndent(),
    )

    private val feedbackSkill = ChatAgentSkillDefinition(
            id = "device_feedback",
            title = "Device Feedback",
            description = "Produce device feedback such as toast, vibration, speech, audio, or calls.",
            instructions = """
                Use direct feedback tools for toast, vibration, TTS, speech-to-text, audio playback, and phone calls.
                Prefer the direct tool that matches the user's requested output modality.
            """.trimIndent(),
    )

    private val shellSkill = ChatAgentSkillDefinition(
            id = "shell_execution",
            title = "Shell Execution",
            description = "Run shell-like commands only when no safer vFlow tool can complete the task.",
            instructions = """
                Shell tools are high risk and should be the last resort.
                Use them only when no safer direct vFlow module can observe or complete the task.
                Keep shell commands narrowly scoped and never assume they succeeded before reading the result.
            """.trimIndent(),
    )

    private val fallbackInteractionSkill = ChatAgentSkillDefinition(
        id = "generic_device_interaction",
        title = "Generic Device Interaction",
        description = "Handle broad device-action requests with a minimal safe fallback toolset.",
        instructions = """
            Use this fallback only when no more specific skill matches the request.
            Prefer direct tools first; for screen work, use the agent-native helper tools before human-oriented vFlow action modules.
            Observe the screen before tapping or typing when the target is uncertain.
            Treat the node tree as primary and keep OCR/screenshot paths for explicit visual fallback only.
            Do not perform blind repeated swipes; after each navigation tap, re-observe once before deciding to scroll.
            On feed/list screens, treat the visible primary content ranking as viewport order and exhaust those visible targets before scrolling.
            On detail/article screens, keep downward scrolling conservative and prefer summarizing from the currently visible node-tree text unless more content is clearly needed.
            If the requested main content is already visible, act on that visible target instead of scrolling past it.
            If the task requires multiple screen actions, start with a fresh control snapshot and end with a verification step instead of guessing that the task is done.
            Keep the plan short and avoid escalating to shell or workflows unless the user explicitly needs them.
        """.trimIndent(),
    )

    private val SKILL_CATALOG = listOf(
        temporaryWorkflowSkill,
        savedWorkflowSkill,
        flashlightSkill,
        clipboardSkill,
        connectivitySkill,
        screenStateSkill,
        observationSkill,
        visualFallbackSkill,
        uiInteractionSkill,
        appLifecycleSkill,
        notificationSkill,
        feedbackSkill,
        shellSkill,
        fallbackInteractionSkill,
    )

    private val ALWAYS_EXPOSED_NATIVE_HELPERS = setOf(
        ChatAgentNativeHelperId.OBSERVE_UI,
        ChatAgentNativeHelperId.READ_PAGE_CONTENT,
        ChatAgentNativeHelperId.TAP,
        ChatAgentNativeHelperId.LONG_PRESS,
        ChatAgentNativeHelperId.INPUT_TEXT,
        ChatAgentNativeHelperId.SWIPE,
        ChatAgentNativeHelperId.PRESS_KEY,
        ChatAgentNativeHelperId.WAIT,
        ChatAgentNativeHelperId.VERIFY_UI,
        ChatAgentNativeHelperId.LOOKUP_APP,
        ChatAgentNativeHelperId.LAUNCH_APP,
    )
}
