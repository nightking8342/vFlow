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
            appendLine("A tool result may be truncated: if it contains an `[output truncated: ...]` marker, you are seeing only the head. Never treat a truncated result as the complete output—either narrow the request as the marker suggests, or tell the user the output was too large to read in full.")
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
            // 以下六条原先散落在各个技能的 instructions 里。那些技能的正文与上面的
            // 规则高度重合（70 行里仅这 6 行是独有的），故技能清空、独有内容上提到这里。
            appendLine("Input-text tools type into the focused field, so establish focus before typing when necessary.")
            appendLine("If the user names an app by display name or brand instead of an Android package, resolve it with the installed-app lookup helper before launching or closing it.")
            appendLine("Shell execution is high risk and a last resort: use it only when no safer module can complete the task, keep commands narrowly scoped, and never assume success before reading the result.")
            appendLine("For Do Not Disturb requests, call the direct tool with on, off, or toggle instead of opening Settings.")
            appendLine("For feedback requests (toast, vibration, TTS, audio playback, phone call), prefer the direct tool matching the requested output modality.")
            appendLine("Prefer accessibility/find_element tools over OCR when the target is in the UI tree; use OCR only as a fallback.")
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
     * 常驻的屏幕操作 helper——prompt 里那段 "Always-available agent-native helpers"
     * 就是用它筛出来的。
     *
     * 注：P1-1c 之前这里还有一个 `isAlwaysExposedTool`（helper ∪ 按需入口），
     * 用于在 `selectSkills` 的关键词过滤中保住常驻项。过滤删除后它失去调用者，已一并移除。
     * **若将来重新引入工具过滤，必须把 `load_skill` / `query_module_schema` / `call_module`
     * 这三个按需入口纳入白名单**，否则模型会拿不到入口而失能。
     */
    private fun isAlwaysExposedNativeHelper(tool: ChatAgentToolDefinition): Boolean {
        return tool.backend == ChatAgentToolBackend.NATIVE_HELPER &&
            tool.nativeHelperId in ALWAYS_EXPOSED_NATIVE_HELPERS
    }



    /**
     * 技能目录。
     *
     * **当前为空**：原有 14 个技能的正文经逐行核对，70 行里仅 6 行是独有的，
     * 已上提到 `buildSystemPrompt` 的规则段（见那里的注释）。其余与 prompt 重复，
     * 故整批清空。
     *
     * **机制保留**：清单（`<available_skills>`）与按需加载（`load_skill`）都在，
     * 后续要加真正承载独立知识的技能时，在此追加 [ChatAgentSkillDefinition] 即可。
     *
     * ⚠️ 加技能前先自问：这条内容**是否已在 system prompt 或工具 description 里**？
     * 若在，写进技能只会造成重复——上一批技能正是这么变成死重的。
     */
    private val SKILL_CATALOG: List<ChatAgentSkillDefinition> = emptyList()

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
