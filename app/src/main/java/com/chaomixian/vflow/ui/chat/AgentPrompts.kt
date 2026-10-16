package com.chaomixian.vflow.ui.chat

/**
 * 提示词**分段**（fork：AI 能力统一 D13 / D16）。
 *
 * 三段式：**① 可配前段**（用户/配置给的内容，优先级最低）→ **② 公共段**（宿主无关的
 * 工具契约与观察纪律）→ **③ 宿主专属段**（chat 的规则段 / 工作流的手机领域规则）。
 * 规则段在**后**，即**优先级更高**（D13）。
 *
 * ## 为什么前段不从 [AgentPromptContext] 取
 *
 * 前段是**宿主的**配置（chat = 模型配置里的「系统提示词」；工作流 = 模块参数），
 * 故由 plan 自己持有（[ChatPromptPlan] 持 lambda ⇒ **活引用**；
 * [WorkflowPromptPlan] 持值 ⇒ **值拷贝**，与 D6/D7 一致）。
 * 放进 context 会让「plan 自己的 prefix」与「context 的 prefix」两个来源打架。
 */
internal data class AgentPromptContext(
    /** 已按白名单过滤的工具集（chat 的规则段要用它列常驻 helper）。 */
    val tools: List<ChatAgentToolDefinition>,
    /** 工作流：任务目标（进 `# User Goal`）；chat 为 null。 */
    val instruction: String? = null,
)

/** 一个宿主怎么把自己的 system prompt 拼出来。 */
internal interface AgentPromptPlan {
    fun build(context: AgentPromptContext): String
}

/**
 * **chat 宿主**：**委托**上游 [ChatAgentSkillRouter]，**一条文案都不复制**
 * ⇒ 与改造前的 system prompt **逐字节相同**（这是「行为不变」最硬的证据之一，
 * 由 `EngineRequestParityTest` 的请求体逐字节比对兜底）。
 *
 * ⚠️ `prefixProvider` 是**活引用**：模型配置里的「系统提示词」改了，
 * 下一次 `runRound` 就会用新值（chat 的 base prompt **仍然可配**，§3.6.3）。
 */
internal class ChatPromptPlan(private val prefixProvider: () -> String) : AgentPromptPlan {
    override fun build(context: AgentPromptContext): String =
        ChatAgentSkillRouter.buildSystemPrompt(
            basePrompt = prefixProvider(),
            skillSelection = ChatAgentSkillSelection(context.tools),
        )
}

/**
 * **工作流宿主**：`[前段] + [公共段] + [宿主专属段] + [# User Goal]`（D13 / D16）。
 *
 * 前段在**前**、规则段在**后**（后 = 优先级更高）。
 * 空段一律**不产生空行**（`listOfNotNull` + `ifBlank { null }`）。
 */
internal class WorkflowPromptPlan(private val prefix: String) : AgentPromptPlan {
    override fun build(context: AgentPromptContext): String =
        listOfNotNull(
            prefix.trim().ifBlank { null },
            SHARED_RULES,
            WORKFLOW_RULES,
            context.instruction
                ?.takeIf { it.isNotBlank() }
                ?.let { "# User Goal\n\"$it\"" },
        ).joinToString(separator = "\n\n")
}

/**
 * **公共段**（宿主无关的工具契约与观察纪律）。
 *
 * 逐字取自上游 `ChatAgentSkillRouter.buildSystemPrompt` 规则段里的 8 条。
 *
 * ⚠️ **已知重复**：这 8 条**也仍在** `ChatAgentSkillRouter` 的 33 条里 ——
 * chat 侧**不复制、也不从那里删**（那个文件不在本任务的允许改动范围内，
 * 且删它会改变 chat 的 system prompt ⇒ 违反「行为逐条不变」）。
 * 待后续把规则段收敛到本对象时消除（设计稿 §12 分歧 3 / Q2）。
 */
internal val SHARED_RULES: String = """
    Never claim a tool succeeded until you receive the tool result.
    If a tool result includes artifact:// handles, preserve and reuse them in later tool arguments when needed.
    Use canonical module parameters and step IDs; never invent localized parameter keys.
    To pass data between steps, use references: `{{previousStepId.outputId}}` for an earlier step's output, and `{{vars.paramName}}` for a parameter declared by the `vflow.logic.define_function` step. The `vars.` prefix is required — a bare `{{paramName}}` does not resolve and silently produces an empty value.
    Prefer small deterministic tool calls over speculative multi-step jumps. Observe, act once, then verify.
    When a tool returns an error or guardrail message, use that recovery guidance to self-heal. Do not repeat the same failing call unchanged.
    A tool result may be truncated: if it contains an `[output truncated: ...]` marker, you are seeing only the head. Never treat a truncated result as the complete output—either narrow the request as the marker suggests, or tell the user the output was too large to read in full.
    Before you say a screen-based task is complete, perform a final read-only verification step. If you could not verify the final state, say that it is not yet verified.
""".trimIndent()

/**
 * **工作流宿主专属段**：手机领域规则（中文，19 条）。
 *
 * ⚠️ **逐字复制** `AgentModule.kt` 里 `# 必须遵循的规则：` 那一段（该文件不在本任务的
 * 允许改动范围内）。**批 4 的 T6** 让 `AgentModule` 消费本 plan 之后，
 * 才删除 `AgentModule` 内的副本（届时文案只有一处）。
 */
internal val WORKFLOW_RULES: String = """
    # 必须遵循的规则：
    1. 在执行任何操作前，先检查当前app是否是目标app，如果不是，先执行 Launch。
    2. 如果进入到了无关页面，先执行 Back。如果执行Back后页面没有变化，请点击页面左上角的返回键进行返回，或者右上角的X号关闭。
    3. 如果页面未加载出内容，最多连续 Wait 三次，否则执行 Back 重新进入。
    4. 如果页面显示网络问题，需要重新加载，请点击重新加载。
    5. 如果当前页面找不到目标联系人、商品、店铺等信息，可以尝试 Swipe 滑动查找。
    6. 遇到价格区间、时间区间等筛选条件，如果没有完全符合的，可以放宽要求。
    7. 在做小红书总结类任务时一定要筛选图文笔记。
    8. 购物车全选后再点击全选可以把状态设为全不选，在做购物车任务时，如果购物车里已经有商品被选中时，你需要点击全选后再点击取消全选，再去找需要购买或者删除的商品。
    9. 在做外卖任务时，如果相应店铺购物车里已经有其他商品你需要先把购物车清空再去购买用户指定的外卖。
    10. 在做点外卖任务时，如果用户需要点多个外卖，请尽量在同一店铺进行购买，如果无法找到可以下单，并说明某个商品未找到。
    11. 请严格遵循用户意图执行任务，用户的特殊要求可以执行多次搜索，滑动查找。比如（i）用户要求点一杯咖啡，要咸的，你可以直接搜索咸咖啡，或者搜索咖啡后滑动查找咸的咖啡，比如海盐咖啡。（ii）用户要找到XX群，发一条消息，你可以先搜索XX群，找不到结果后，将"群"字去掉，搜索XX重试。（iii）用户要找到宠物友好的餐厅，你可以搜索餐厅，找到筛选，找到设施，选择可带宠物，或者直接搜索可带宠物，必要时可以使用AI搜索。
    12. 在选择日期时，如果原滑动方向与预期日期越来越远，请向反方向滑动查找。
    13. 执行任务过程中如果有多个可选择的项目栏，请逐个查找每个项目栏，直到完成任务，一定不要在同一项目栏多次查找，从而陷入死循环。
    14. 在执行下一步操作前请一定要检查上一步的操作是否生效，如果点击没生效，可能因为app反应较慢，请先稍微等待一下，如果还是不生效请调整一下点击位置重试，如果仍然不生效请跳过这一步继续任务，并在finish_task result说明点击不生效。
    15. 在执行任务中如果遇到滑动不生效的情况，请调整一下起始点位置，增大滑动距离重试，如果还是不生效，有可能是已经滑到底了，请继续向反方向滑动，直到顶部或底部，如果仍然没有符合要求的结果，请跳过这一步继续任务，并在finish_task result说明但没找到要求的项目。
    16. 在做游戏任务时如果在战斗页面如果有自动战斗一定要开启自动战斗，如果多轮历史状态相似要检查自动战斗是否开启。
    17. 如果没有合适的搜索结果，可能是因为搜索页面不对，请返回到搜索页面的上一级尝试重新搜索，如果尝试三次返回上一级搜索后仍然没有符合要求的结果，执行 finish_task(result="原因")。
    18. 在结束任务前请一定要仔细检查任务是否完整准确的完成，如果出现错选、漏选、多选的情况，请返回之前的步骤进行纠正。
    19. 播放视频、音乐时，三角形播放按钮出现意味着当前处于暂停状态，请点击以播放。
""".trimIndent()
