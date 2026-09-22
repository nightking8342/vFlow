package com.chaomixian.vflow.ui.chat

import android.content.Context
import com.chaomixian.vflow.core.module.ActionModule
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ModuleCategories
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class ChatAgentToolDefinition(
    val name: String,
    val title: String,
    val description: String,
    val moduleId: String,
    val moduleDisplayName: String,
    val routingHints: Set<String> = emptySet(),
    val inputSchema: JsonObject,
    val permissionNames: List<String>,
    val riskLevel: ChatAgentToolRiskLevel,
    val usageScopes: Set<ChatAgentToolUsageScope>,
    val backend: ChatAgentToolBackend = ChatAgentToolBackend.MODULE,
    val nativeHelperId: ChatAgentNativeHelperId? = null,
    /**
     * 输出是否受 [CHAT_MAX_TOOL_RESULT_INPUT_CHARS] 截断。
     *
     * 该限制是为「机器 dump、结构重复、长尾无信息量」的输出兜底的（如观察无障碍节点树），
     * 不是通用约束。两类工具必须显式声明 `false`：
     * - 按需加载的人写知识（技能正文）——加载它就是为了拿到全部内容，截断等于让这次调用白做
     * - 结构化元数据（模块 schema 字段定义）——截断的可能正好是字段名，会让模型拿到残缺的说明书
     *
     * 默认 `true` 保证新工具默认安全，只有明确声明的才豁免。
     */
    val truncatable: Boolean = true,
)

internal const val CHAT_TEMPORARY_WORKFLOW_TOOL_NAME = "vflow_agent_run_temporary_workflow"
internal const val CHAT_TEMPORARY_WORKFLOW_MODULE_ID = "vflow.agent.temporary_workflow"
internal const val CHAT_SAVE_WORKFLOW_TOOL_NAME = "vflow_agent_save_workflow"
internal const val CHAT_SAVE_WORKFLOW_MODULE_ID = "vflow.agent.save_workflow"

/**
 * 按需加载技能正文的工具名。
 *
 * 技能**清单**（name + description）常驻 system prompt；
 * **正文**（instructions）只在模型调用本工具时返回，作为 tool result 进入对话历史后永久留存。
 * 这样技能不会因话题切换而消失——历史即状态，无需额外的会话状态字段。
 */
internal const val CHAT_LOAD_SKILL_TOOL_NAME = "vflow_agent_load_skill"
internal const val CHAT_LOAD_SKILL_MODULE_ID = "vflow.agent.load_skill"

/**
 * 按需查询模块完整 schema 的工具名。
 *
 * 模块**清单**（moduleId + 中文名）常驻工作流工具的 description；
 * **完整字段定义**只在模型调用本工具时返回。
 *
 * **查询域 = 184**（能进保存工作流的全部模块），**不是** 59（能直调的）。
 * 写工作流时能用的模块远多于能直接调的，故查询域取大者，
 * 由返回结果里的 `callable` 字段告诉模型「这个能不能直接调」。
 */
internal const val CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME = "vflow_agent_query_module_schema"
internal const val CHAT_QUERY_MODULE_SCHEMA_MODULE_ID = "vflow.agent.query_module_schema"

/**
 * 通用模块执行入口的工具名（P1-1b）。
 *
 * 59 个模块工具撤出 `tools` 数组后，模型须先 `query_module_schema` 拿字段定义，
 * 再用本工具执行。它是**固定工具、永远在**，不随技能路由变化。
 */
internal const val CHAT_CALL_MODULE_TOOL_NAME = "vflow_agent_call_module"
internal const val CHAT_CALL_MODULE_MODULE_ID = "vflow.agent.call_module"

/**
 * 列出用户工作流的工具名。
 *
 * 与 [CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME] 的分工：那个回答「模块长什么样」（形状），
 * 这个回答「用户现在有哪些工作流」（数据）。`CallFunctionModule` 的 `workflow_id`
 * 是必填 string，但其可选值只能从这里得到——**schema 说形状，数据工具提供值**。
 *
 * 也是将来「修改工作流」工具的前置：要改某个工作流，先得知道它的 id。
 */
internal const val CHAT_LIST_WORKFLOWS_TOOL_NAME = "vflow_agent_list_workflows"
internal const val CHAT_LIST_WORKFLOWS_MODULE_ID = "vflow.agent.list_workflows"

/**
 * 查询用户环境的工具名：文件夹 + 全局变量。
 *
 * 这两类都是**跨工作流的持久配置**，与工作流本身的性质不同（工作流是被操作的对象，
 * 而这些是 AI 用来理解上下文的背景），故独立成一个工具。
 *
 * 标签暂不纳入——App 里标签既不在列表页展示也不参与搜索，用户实际很少使用，
 * 给它做查询接口是为一个未打通的功能加维护成本。
 */
internal const val CHAT_GET_ENVIRONMENT_TOOL_NAME = "vflow_agent_get_environment"
internal const val CHAT_GET_ENVIRONMENT_MODULE_ID = "vflow.agent.get_environment"

/**
 * 读出一个已存工作流的完整详情（含逐步的 step id 与参数）。
 *
 * `list_workflows` 只回答「有哪些工作流」，本工具回答「这个工作流长什么样」——
 * 是 [CHAT_UPDATE_WORKFLOW_TOOL_NAME] 的前置：要改某个步骤，先要知道它的 step id。
 *
 * 输出是**纯文本**（与其余 5 个内建工具同风格），因为需要塞进 JSON 装不下的标注
 * （jump 的目标步骤名、只读字段清单、变量型 jump 的「不可静态重映射」）。
 */
internal const val CHAT_GET_WORKFLOW_TOOL_NAME = "vflow_agent_get_workflow"
internal const val CHAT_GET_WORKFLOW_MODULE_ID = "vflow.agent.get_workflow"

/**
 * 修改一个**已存在**的工作流的工具名。
 *
 * 与 `save_workflow` 的关键区别：`save_workflow` 的 id 恒为新生成的 `chat_saved_*`，
 * 永远造新条目；本工具要求传 `workflow_id`，落到 [WorkflowManager.saveWorkflow] 时
 * 按 id 命中已有记录并覆盖——这是数据层**本来就支持**的能力（见 `WorkflowManager:106-110`），
 * 此前只是没有工具去用它。
 *
 * 入参形态是**操作原语补丁**而非整表替换。理由见
 * `docs/fork/workflow-read-write-tools.md` §2.2：整工作流重发在长工作流上 token 成本高约 50 倍，
 * 且「模型列清单时漏了一条」会被整表语义解释成「删除该条」。
 */
internal const val CHAT_UPDATE_WORKFLOW_TOOL_NAME = "vflow_agent_update_workflow"
internal const val CHAT_UPDATE_WORKFLOW_MODULE_ID = "vflow.agent.update_workflow"

internal fun chatToolNameFromModuleId(moduleId: String): String {
    val normalized = moduleId
        .lowercase()
        .replace(Regex("[^a-z0-9_]+"), "_")
        .trim('_')
    return if (normalized.startsWith("vflow_")) normalized else "vflow_$normalized"
}

internal class ChatAgentToolRegistry(context: Context) {
    private val appContext = context.applicationContext

    private val toolsByName: Map<String, ChatAgentToolDefinition>
    private val temporaryWorkflowModuleIds: List<String>
    private val savedWorkflowModuleIds: List<String>

    init {
        ModuleRegistry.initialize(appContext)
        temporaryWorkflowModuleIds = buildTemporaryWorkflowModuleIds()
        savedWorkflowModuleIds = buildSavedWorkflowModuleIds()
        // 常驻工具表 = 4 个工作流工具 + 3 个按需入口 + 11 个屏幕 helper。
        //
        // **59 个模块工具已撤出**（P1-1c）：它们不再进 `tools` 数组，
        // 改用 `query_module_schema` 查字段 + `call_module` 执行。
        // 撤出的原因是这批工具性质上是「扩展工具」（由 ModuleRegistry 自动生成、
        // 数量随模块增长），全量下发会随模块数持续膨胀；参照 CCB 对 MCP 的按需处理。
        //
        // 模块工具的定义构造（`buildDirectToolDefinitions` / `buildToolDefinition`）
        // **仍然保留**——它们是 `query_module_schema` 与 `call_module` 的数据源。
        toolsByName = (
            listOf(
                buildTemporaryWorkflowToolDefinition(),
                buildSaveWorkflowToolDefinition(),
                buildGetWorkflowToolDefinition(),
                buildUpdateWorkflowToolDefinition(),
                buildLoadSkillToolDefinition(),
                buildQueryModuleSchemaToolDefinition(),
                buildCallModuleToolDefinition(),
                buildListWorkflowsToolDefinition(),
                buildGetEnvironmentToolDefinition(),
            ) +
                ChatAgentNativeToolExecutor.buildDefinitions(appContext)
            ).associateBy { it.name }
    }

    fun getTools(): List<ChatAgentToolDefinition> = toolsByName.values.toList()

    fun getTool(name: String): ChatAgentToolDefinition? = toolsByName[name]

    /**
     * 按 moduleId 取工具定义。
     *
     * P1-1c 之后模块工具已不在常驻表里，故这里**总是走 `buildToolDefinition`**——
     * 它与 `query_module_schema` / `call_module` 用的是同一份定义。
     * 保留 `toolsByName` 查询是为了将来若某些模块工具重新常驻时无需改动。
     */
    fun getToolForModuleId(moduleId: String): ChatAgentToolDefinition? {
        return toolsByName[chatToolNameFromModuleId(moduleId)] ?: buildToolDefinition(moduleId)
    }

    fun getRiskLevelForModuleId(moduleId: String): ChatAgentToolRiskLevel = riskLevelForModuleId(moduleId)

    /**
     * 该模块能用在哪些场景（直调 / 临时工作流 / 保存工作流）。
     * 供 `query_module_schema` 把边界前置给模型。
     */
    fun getUsageScopesForModuleId(moduleId: String): Set<ChatAgentToolUsageScope> =
        buildModuleUsageScopes(moduleId)

    /**
     * 查询域的全部 moduleId——**能进保存工作流的全部模块（~184）**，不是能直调的 59。
     *
     * 写工作流时能用的模块远多于能直接调的，故查询域取大者。
     * 「能不能直调」由返回结果里的 `callable` 表达，而不是靠把模块排除出查询域。
     */
    fun getQueryableModuleIds(): List<String> = savedWorkflowModuleIds

    /**
     * 该 moduleId 是否注册在 vFlow 中。
     *
     * 与 [isTemporaryWorkflowModuleAllowed] / [isSavedWorkflowModuleAllowed] 的区别：
     * 后两者是「白名单集合查询」，未注册的 id 同样返回 false，无法区分
     * 「模块不存在」与「模块存在但不允许用于该场景」。
     * 校验路径应先用本方法判存在性，再用白名单判可用性，否则模型会看到
     * 误导性的「not exposed」提示，误以为模块存在、只是权限问题。
     */
    fun isRegisteredModule(moduleId: String): Boolean {
        return ModuleRegistry.getModule(moduleId) != null
    }

    fun isTemporaryWorkflowModuleAllowed(moduleId: String): Boolean {
        return moduleId in temporaryWorkflowModuleIds
    }

    fun isSavedWorkflowModuleAllowed(moduleId: String): Boolean {
        return moduleId in savedWorkflowModuleIds
    }

    fun isTriggerModule(moduleId: String): Boolean {
        return ModuleRegistry.getModule(moduleId)?.let(::isTriggerModule) == true ||
            moduleId.startsWith(TRIGGER_MODULE_PREFIX)
    }

    private fun buildToolDefinition(moduleId: String): ChatAgentToolDefinition? {
        val module = ModuleRegistry.getModule(moduleId) ?: return null
        val baseStep = module.createSteps().firstOrNull() ?: ActionStep(module.id, emptyMap())
        // 与执行校验共用同一求值口径（静态全集 ∪ 动态结果），否则会出现
        // 「schema 里没有该字段但执行时能收下」或反过来的不一致。
        val inputs = visibleInputsForAgent(
            resolveModuleInputDefinitions(module, baseStep),
            baseStep.parameters,
        ).filter(::isInputSupported)
        val localizedName = module.metadata.getLocalizedName(appContext)
        val permissions = module.getRequiredPermissions(baseStep)
            .map { it.getLocalizedName(appContext) }
            .distinct()
        val riskLevel = riskLevelForModuleId(module.id)
        val usageScopes = buildModuleUsageScopes(module.id)

        return ChatAgentToolDefinition(
            name = chatToolNameFromModuleId(module.id),
            title = localizedName,
            description = buildToolDescription(module, localizedName, permissions, riskLevel, usageScopes),
            moduleId = module.id,
            moduleDisplayName = localizedName,
            routingHints = buildToolRoutingHints(module, localizedName, inputs),
            inputSchema = buildToolSchema(module.id, inputs),
            permissionNames = permissions,
            riskLevel = riskLevel,
            usageScopes = usageScopes,
        )
    }

    private fun buildTemporaryWorkflowToolDefinition(): ChatAgentToolDefinition {
        val stepCatalog = buildCompactModuleCatalog(
            temporaryWorkflowModuleIds.filterNot(::isTriggerModule),
            preferWorkflowDescriptions = true,
        )
        return ChatAgentToolDefinition(
            name = CHAT_TEMPORARY_WORKFLOW_TOOL_NAME,
            title = "临时工作流",
            description = buildString {
                append("Run a short temporary vFlow workflow in one approval. ")
                append("Use this for deterministic multi-step or repeated actions, for example toggling the flashlight 10 times with a 2000 ms delay. ")
                append("Do not use this for a single clear action; call the matching single-purpose tool directly. ")
                append("Generate real vFlow ActionStep objects with moduleId and parameters. ")
                append("Use stable descriptive step ids and only parameters defined by each module schema. ")
                append("Use vflow.logic.loop.start and vflow.logic.loop.end for compact repeated sequences. ")
                append("Allowed steps are curated action modules only, not triggers and not this temporary workflow tool. ")
                append("Risk level is computed from the workflow steps.")
                append(stepCatalog)
                append(buildVariablePassingGuide())
            },
            moduleId = CHAT_TEMPORARY_WORKFLOW_MODULE_ID,
            moduleDisplayName = "临时工作流",
            routingHints = setOf("临时工作流", "执行工作流", "temporary workflow", "run workflow"),
            inputSchema = buildTemporaryWorkflowSchema(temporaryWorkflowModuleIds),
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.STANDARD,
            usageScopes = setOf(ChatAgentToolUsageScope.TEMPORARY_WORKFLOW),
            backend = ChatAgentToolBackend.TEMPORARY_WORKFLOW,
        )
    }

    /**
     * `load_skill`：按需加载技能正文。
     *
     * 纯本地计算（查 [ChatAgentSkillRouter.skillInstructions] + 拼字符串），
     * **无权限、无副作用、无 IO**，故 riskLevel = READ_ONLY 且 `truncatable = false`
     * （正文必须完整——加载它就是为了拿到全部内容，截断等于让这次调用白做）。
     */
    private fun buildLoadSkillToolDefinition(): ChatAgentToolDefinition {
        val availableIds = ChatAgentSkillRouter.skillListing().joinToString(", ") { it.id }
        return ChatAgentToolDefinition(
            name = CHAT_LOAD_SKILL_TOOL_NAME,
            title = "加载技能说明",
            description = buildString {
                append("Load the full instructions of a skill listed in <available_skills>. ")
                append("The skill listing shows only ids and one-line descriptions; ")
                append("call this tool to get the detailed rules before doing work that the skill covers. ")
                append("This is a local lookup with no side effects. ")
                append("Available skill ids: ")
                append(availableIds)
                append(".")
            },
            moduleId = CHAT_LOAD_SKILL_MODULE_ID,
            moduleDisplayName = "加载技能说明",
            routingHints = setOf("技能", "skill", "load skill", "说明", "instructions"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "skill_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Id of the skill to load, as shown in <available_skills>.")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("skill_id")) })
            },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            truncatable = false,
        )
    }

    /**
     * `query_module_schema`：按需查询模块的完整字段定义。
     *
     * 纯本地查表（`ModuleRegistry` + 输入定义求值），**无权限、无副作用、无 IO**，
     * 故 riskLevel = READ_ONLY 且 `truncatable = false`——
     * 截断的可能正好是字段定义本身，会让模型拿到半份说明书（病症 B 复发）。
     */
    private fun buildQueryModuleSchemaToolDefinition(): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME,
            title = "查询模块字段定义",
            description = buildString {
                append("Look up the full input/output schema of a vFlow module. ")
                append("The module catalog in the workflow tool descriptions lists only module ids and names; ")
                append("call this tool to get the exact parameter ids, types, and allowed values before writing ")
                append("or calling a module. ")
                append("This is a local lookup with no side effects. ")
                append("Works for any module that can appear in a saved workflow. ")
                append("Important: `callable: false` means the module cannot be invoked directly with ")
                append("`$CHAT_CALL_MODULE_TOOL_NAME`, but it is still valid as a workflow step. ")
                append("This tool itself, `$CHAT_CALL_MODULE_TOOL_NAME` and `$CHAT_LOAD_SKILL_TOOL_NAME` ")
                append("are agent built-ins, not vFlow modules — do not query them.")
            },
            moduleId = CHAT_QUERY_MODULE_SCHEMA_MODULE_ID,
            moduleDisplayName = "查询模块字段定义",
            routingHints = setOf("模块", "字段", "参数", "schema", "module", "parameters"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "module_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Canonical module id, e.g. vflow.system.darkmode.")
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("module_id")) })
            },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            truncatable = false,
        )
    }

    /**
     * `call_module`：通用模块执行入口。
     *
     * ⚠️ 这里声明的 `riskLevel` 是**占位值**，不会被用于审批——`prepareCallModule`
     * 会用目标模块的真实风险等级构造执行项（见 `ChatAgentModuleExecutor.prepareCallModule`）。
     * 若此处被当成实际风险，`call_module` 就成了绕过所有模块风险评估的后门。
     */
    /**
     * `list_workflows`：列出用户的工作流（可选筛选）。
     *
     * 纯本地查询，无副作用，故 READ_ONLY + 不截断（列表可能较长，但截断会让模型
     * 看不到它要找的那条）。
     */
    private fun buildListWorkflowsToolDefinition(): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = CHAT_LIST_WORKFLOWS_TOOL_NAME,
            title = "列出用户工作流",
            description = buildString {
                append("List the user's vFlow workflows, optionally filtered. ")
                append("Use this to find a workflow id before calling one or modifying one. ")
                append("Each entry shows the workflow id, name, folder, and — for function workflows — ")
                append("its full parameter list, so you can build a complete ")
                append("`$CHAT_CALL_MODULE_TOOL_NAME` call for `$CALL_FUNCTION_MODULE_ID` without guessing. ")
                append("A function workflow is one that starts with a Define Function block. ")
                append("This is a local lookup with no side effects.")
            },
            moduleId = CHAT_LIST_WORKFLOWS_MODULE_ID,
            moduleDisplayName = "列出用户工作流",
            routingHints = setOf("工作流", "列表", "workflow", "list"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "query",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Optional. Case-insensitive substring match on the workflow name.")
                            }
                        )
                        put(
                            "folder",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional. Only workflows directly inside this folder (by folder name). " +
                                        "Use `$CHAT_GET_ENVIRONMENT_TOOL_NAME` or an unfiltered call to see folder names."
                                )
                            }
                        )
                        put(
                            "kind",
                            buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(listOf("all", "function", "regular").map(::JsonPrimitive)))
                                put(
                                    "description",
                                    "Optional. `function` = only function workflows (callable via " +
                                        "`$CALL_FUNCTION_MODULE_ID`); `regular` = everything else. Defaults to `all`."
                                )
                            }
                        )
                    }
                )
            },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            truncatable = false,
        )
    }

    /**
     * `get_environment`：文件夹 + 全局变量。
     *
     * 全局变量**只返回名字与类型，不返回值**——模型引用 `{{global.x}}` 不需要知道当前值
     * （执行时取值），而值可能是长 JSON 或用户敏感数据，无谓地送进上下文。
     */
    private fun buildGetEnvironmentToolDefinition(): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = CHAT_GET_ENVIRONMENT_TOOL_NAME,
            title = "查询用户环境",
            description = buildString {
                append("Look up the user's persistent vFlow environment: workflow folders and global variables. ")
                append("Global variables are shared across workflows; reference one in a module parameter as ")
                append("`{{global.<name>}}`. Use this before writing a step that reads or writes a global variable, ")
                append("so you use a name that actually exists. ")
                append("This is a local lookup with no side effects.")
            },
            moduleId = CHAT_GET_ENVIRONMENT_MODULE_ID,
            moduleDisplayName = "查询用户环境",
            routingHints = setOf("全局变量", "文件夹", "变量", "environment", "global variable"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put("properties", buildJsonObject { })
            },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            truncatable = false,
        )
    }

    private fun buildCallModuleToolDefinition(): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = CHAT_CALL_MODULE_TOOL_NAME,
            title = "执行模块",
            description = buildString {
                append("Execute a vFlow module directly. ")
                append("Call `$CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME` first to get the module's exact ")
                append("parameter ids and types — guessing parameter names will fail. ")
                append("Pass the module id as `module_id` and its parameters as `params`. ")
                append("Only modules that report `callable: true` can be called this way; ")
                append("others can only appear as workflow steps. ")
                append("The approval prompt uses the target module's own risk level.")
            },
            moduleId = CHAT_CALL_MODULE_MODULE_ID,
            moduleDisplayName = "执行模块",
            routingHints = setOf("执行模块", "调用模块", "call module", "execute"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "module_id",
                            buildJsonObject {
                                put("type", "string")
                                put("description", "Canonical module id to execute.")
                            }
                        )
                        put(
                            "params",
                            buildJsonObject {
                                put("type", "object")
                                put(
                                    "description",
                                    "Module parameters, keyed by the parameter ids returned by " +
                                        "$CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME."
                                )
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("module_id")) })
            },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.STANDARD,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
        )
    }

    private fun buildSaveWorkflowToolDefinition(): ChatAgentToolDefinition {
        val triggerCatalog = buildCompactModuleCatalog(
            savedWorkflowModuleIds.filter(::isTriggerModule),
            preferWorkflowDescriptions = false,
        )
        val stepCatalog = buildCompactModuleCatalog(
            savedWorkflowModuleIds.filterNot(::isTriggerModule),
            preferWorkflowDescriptions = true,
        )
        return ChatAgentToolDefinition(
            name = CHAT_SAVE_WORKFLOW_TOOL_NAME,
            title = "保存工作流",
            description = buildString {
                append("Save a reusable vFlow workflow into the user's workflow list. ")
                append("Use this only when the user asks to create, generate, or save an automation for later reuse. ")
                append("For immediate one-off execution, use a direct tool or vflow_agent_run_temporary_workflow instead. ")
                append("The workflow must contain real vFlow ActionStep objects with canonical moduleId and parameters. ")
                append("Put trigger modules only in workflow.triggers; put action/data/logic modules only in workflow.steps. ")
                append("If no trigger is requested, omit workflow.triggers and the app will add a manual trigger. ")
                append("Use stable descriptive step ids and only parameters defined by each module schema. ")
                append("Do not persist artifact:// handles in saved workflows because chat artifacts are temporary. ")
                append("Risk level is computed from saved modules; workflows with auto triggers or shell-like modules are high risk. ")
                append("Usage scope: saved workflow step. ")
                append(triggerCatalog)
                append(stepCatalog)
                append(buildVariablePassingGuide())
            },
            moduleId = CHAT_SAVE_WORKFLOW_MODULE_ID,
            moduleDisplayName = "保存工作流",
            routingHints = setOf("保存工作流", "创建工作流", "自动化", "workflow", "automation"),
            inputSchema = buildSaveWorkflowSchema(savedWorkflowModuleIds),
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.HIGH,
            usageScopes = setOf(ChatAgentToolUsageScope.SAVED_WORKFLOW),
            backend = ChatAgentToolBackend.SAVED_WORKFLOW,
        )
    }

    /**
     * `get_workflow`：读出一个已存工作流的完整详情。
     *
     * 纯本地读，无副作用，故 READ_ONLY + 不截断（与 `list_workflows` 同口径：
     * 截断可能正好切掉模型要找的那个 step id，等于让这次调用白做）。
     */
    private fun buildGetWorkflowToolDefinition(): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = CHAT_GET_WORKFLOW_TOOL_NAME,
            title = "查看工作流详情",
            description = buildString {
                append("Read the full definition of one saved workflow: its triggers, its steps, ")
                append("each step's id / module id / parameters / disabled flag, and the workflow-level metadata. ")
                append("Use this before `$CHAT_UPDATE_WORKFLOW_TOOL_NAME` — you need the real step ids to address them. ")
                append("`$CHAT_LIST_WORKFLOWS_TOOL_NAME` only lists ids and names; it cannot show steps. ")
                append("The output is text. Fields that no tool can change are listed under `read-only fields`. ")
                append("A step whose `target_step_index` is a runtime variable is annotated as such — ")
                append("its jump target cannot be recalculated statically, so do not try. ")
                append("This is a local lookup with no side effects.")
            },
            moduleId = CHAT_GET_WORKFLOW_MODULE_ID,
            moduleDisplayName = "查看工作流详情",
            routingHints = setOf("工作流详情", "查看工作流", "工作流步骤", "workflow detail", "show workflow", "inspect workflow"),
            inputSchema = buildJsonObject {
                put("type", "object")
                put("additionalProperties", JsonPrimitive(false))
                put(
                    "properties",
                    buildJsonObject {
                        put(
                            "workflow_id",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Exact workflow id, as returned by `$CHAT_LIST_WORKFLOWS_TOOL_NAME`. " +
                                        "Ids are not guessable — list first if you do not have one."
                                )
                            }
                        )
                    }
                )
                put("required", buildJsonArray { add(JsonPrimitive("workflow_id")) })
            },
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            truncatable = false,
        )
    }

    /**
     * `update_workflow`：用**操作原语补丁**修改一个已存在的工作流。
     *
     * ⚠️ 这里声明的 `riskLevel` 是**占位值**——实际审批走 `prepareUpdateWorkflow`
     * 按「改动后的完整工作流」算出的风险等级（与 `save_workflow` 同口径）。
     *
     * description 里逐条写死了补丁语义，因为这些都是「模型不问就一定会猜错、
     * 猜错就静默改坏数据」的地方（详见 `docs/fork/workflow-read-write-tools.md` §4.3.2）。
     */
    private fun buildUpdateWorkflowToolDefinition(): ChatAgentToolDefinition {
        return ChatAgentToolDefinition(
            name = CHAT_UPDATE_WORKFLOW_TOOL_NAME,
            title = "修改工作流",
            description = buildString {
                append("Modify an EXISTING saved workflow in place, addressed by `workflow_id`. ")
                append("Read it with `$CHAT_GET_WORKFLOW_TOOL_NAME` first to get the real step ids; ")
                append("every `step_id` you pass must exist in that workflow, except the new ids inside `insert`. ")
                append("\n\nThis is a PATCH, not a replacement — anything you do not mention stays untouched. ")
                append("Omitting a step does NOT delete it; deletion must be explicit via `delete`. ")
                append("`update` merges `parameters` by key: only the keys you pass change. ")
                append("To remove one parameter, pass it with a JSON `null` value (that removes the key; ")
                append("just leaving it out keeps it). Same rule for `metadata` fields. ")
                append("\n\nExecution order is fixed: update, then insert, then delete, then move. ")
                append("`move.to_index` is a 1-based display number measured against the workflow ")
                append("BEFORE this patch is applied, so you can copy it straight from ")
                append("`$CHAT_GET_WORKFLOW_TOOL_NAME` output. ")
                append("\n\n`insert` needs exactly one of `after_step_id` or `at_index`. ")
                append("Step ids must match [A-Za-z0-9_-]+ and must not collide with existing ids. ")
                append("\n\nThe whole patch is atomic: if any part is invalid, nothing is written. ")
                append("Risk level is computed from the resulting workflow. ")
                append("To write a whole workflow from scratch, use `$CHAT_SAVE_WORKFLOW_TOOL_NAME` instead.")
            },
            moduleId = CHAT_UPDATE_WORKFLOW_MODULE_ID,
            moduleDisplayName = "修改工作流",
            routingHints = setOf("修改工作流", "编辑工作流", "改工作流", "update workflow", "edit workflow", "modify workflow"),
            inputSchema = buildUpdateWorkflowSchema(),
            permissionNames = emptyList(),
            riskLevel = ChatAgentToolRiskLevel.HIGH,
            usageScopes = setOf(ChatAgentToolUsageScope.DIRECT_TOOL),
            backend = ChatAgentToolBackend.UPDATE_WORKFLOW,
            truncatable = false,
        )
    }

    private fun buildUpdateWorkflowSchema(): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                buildJsonObject {
                    put("workflow_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Exact id of the workflow to modify. Must already exist.")
                    })
                    put("metadata", buildMetadataPatchSchema())
                    put("triggers", buildTriggerPatchSchema())
                    put("steps", buildStepPatchSchema())
                }
            )
            put("required", buildJsonArray { add(JsonPrimitive("workflow_id")) })
        }
    }

    /** `metadata`：按 key 合并，传 `null` 删键。只读字段刻意不出现——它们不可改。 */
    private fun buildMetadataPatchSchema(): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put("description", "Workflow-level fields to change. Only the keys you pass are touched; pass `null` to clear one.")
            put(
                "properties",
                buildJsonObject {
                    put("name", buildJsonObject {
                        put("type", "string")
                        put("description", "New display name.")
                    })
                    put("description", buildJsonObject {
                        put("type", "string")
                        put("description", "New description.")
                    })
                    put("isEnabled", buildJsonObject {
                        put("type", "boolean")
                        put("description", "Enable or disable the whole workflow.")
                    })
                    put("folderId", buildJsonObject {
                        put("type", "string")
                        put("description", "Move into an existing folder by its **id** (not its name). An unknown id is rejected because the workflow would vanish from the list.")
                    })
                    put("tags", buildJsonObject {
                        put("type", "array")
                        put("items", buildJsonObject { put("type", "string") })
                        put("description", "Replace the whole tag list.")
                    })
                    put("maxExecutionTime", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 1)
                        put("maximum", 3600)
                        put("description", "Maximum execution time in seconds.")
                    })
                    put("reentryBehavior", buildJsonObject {
                        put("type", "string")
                        put("enum", JsonArray(listOf("block_new", "stop_current_and_run_new", "allow_parallel").map(::JsonPrimitive)))
                        put("description", "How to handle a new trigger while the workflow is already running.")
                    })
                }
            )
        }
    }

    private fun buildTriggerPatchSchema(): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "description",
                "Trigger changes. Trigger order has no meaning, so there is no `move`. " +
                    "⚠️ Keep the existing `step_id` when modifying a trigger: trigger outputs are keyed by " +
                    "trigger id, so changing an id breaks every `{{triggerId.outputId}}` reference in the steps."
            )
            put(
                "properties",
                buildJsonObject {
                    put("update", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 12)
                        put("description", "Change existing triggers' parameters. `step_id` must already exist.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", JsonPrimitive(false))
                            put("properties", buildJsonObject {
                                put("step_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Existing trigger step id.")
                                })
                                put("parameters", buildPatchParametersSchema("Trigger parameters to merge by key."))
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("step_id")) })
                        })
                    })
                    put("insert", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 12)
                        put("description", "Append new trigger steps. Their ids must not collide with existing ones.")
                        put("items", buildPatchInsertStepSchema("Unique new trigger step id."))
                    })
                    put("delete", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 12)
                        put("description", "Remove triggers by step id. Deleting the last trigger leaves a manual trigger.")
                        put("items", buildJsonObject { put("type", "string") })
                    })
                }
            )
        }
    }

    private fun buildStepPatchSchema(): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "description",
                "Step changes. Anything you do not mention stays exactly as it is — omitting a step is NOT a deletion."
            )
            put(
                "properties",
                buildJsonObject {
                    put("update", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 200)
                        put("description", "Change existing steps' parameters or disabled flag. `step_id` must already exist.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", JsonPrimitive(false))
                            put("properties", buildJsonObject {
                                put("step_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Existing step id, as shown by `$CHAT_GET_WORKFLOW_TOOL_NAME`.")
                                })
                                put("parameters", buildPatchParametersSchema("Step parameters to merge by key."))
                                put("is_disabled", buildJsonObject {
                                    put("type", "boolean")
                                    put(
                                        "description",
                                        "Disable or re-enable this single step. Only allowed on plain steps — " +
                                            "block members (If/Loop/While/ForEach/DoWhile/menu/UI block parts) are rejected, " +
                                            "because disabling them desynchronises the block pairing at runtime."
                                    )
                                })
                            })
                            put("required", buildJsonArray { add(JsonPrimitive("step_id")) })
                        })
                    })
                    put("insert", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 200)
                        put("description", "Add new steps. Give exactly one of `after_step_id` or `at_index`.")
                        put("items", buildPatchInsertStepSchema("Unique new step id."))
                    })
                    put("delete", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 200)
                        put(
                            "description",
                            "Remove steps by id. Deleting a block member requires removing all members " +
                                "in the same call — a half-deleted block is an invalid structure. " +
                                "A step that a `vflow.logic.jump` targets cannot be deleted."
                        )
                        put("items", buildJsonObject { put("type", "string") })
                    })
                    put("move", buildJsonObject {
                        put("type", "array")
                        put("maxItems", 200)
                        put("description", "Reposition existing steps.")
                        put("items", buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", JsonPrimitive(false))
                            put("properties", buildJsonObject {
                                put("step_id", buildJsonObject {
                                    put("type", "string")
                                    put("description", "Existing step id to move.")
                                })
                                put("to_index", buildJsonObject {
                                    put("type", "integer")
                                    put("minimum", 1)
                                    put(
                                        "description",
                                        "Target 1-based display number, measured against the workflow BEFORE " +
                                            "this patch is applied (so you can copy it from `$CHAT_GET_WORKFLOW_TOOL_NAME`)."
                                    )
                                })
                            })
                            put("required", buildJsonArray {
                                add(JsonPrimitive("step_id"))
                                add(JsonPrimitive("to_index"))
                            })
                        })
                    })
                }
            )
        }
    }

    /**
     * 补丁里的 `parameters`：**按 key 合并**，不是整表替换。
     *
     * 传 `null` 表示**删掉这个键**（而不是写一个 null 值）——两者在执行期
     * 对 `isRequired` 校验并不等价，所以必须区分。
     */
    private fun buildPatchParametersSchema(description: String): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(true))
            put(
                "description",
                "$description Only the keys you pass change. Pass `null` for a key to remove it. " +
                    "Values may be literals or references: `{{previousStepId.outputId}}` reads an earlier " +
                    "step's output; `{{vars.paramName}}` reads a parameter declared by the " +
                    "`vflow.logic.define_function` step. A bare `{{paramName}}` (without the `vars.` prefix) " +
                    "does NOT resolve and silently yields an empty value."
            )
        }
    }

    private fun buildPatchInsertStepSchema(idDescription: String): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                buildJsonObject {
                    put("after_step_id", buildJsonObject {
                        put("type", "string")
                        put("description", "Insert directly after this existing step. Mutually exclusive with `at_index`.")
                    })
                    put("at_index", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("description", "Insert at this 0-based position. Mutually exclusive with `after_step_id`.")
                    })
                    put("id", buildJsonObject {
                        put("type", "string")
                        put("description", "$idDescription Must match [A-Za-z0-9_-]+ and be unique in the workflow.")
                    })
                    put("moduleId", buildJsonObject {
                        put("type", "string")
                        put(
                            "description",
                            "Canonical module id. Query `$CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME` for valid ids and their fields."
                        )
                    })
                    put("parameters", buildPatchParametersSchema("New step parameters."))
                    put("indentationLevel", buildJsonObject {
                        put("type", "integer")
                        put("minimum", 0)
                        put("maximum", 12)
                        put("description", "Visual indentation level for block contents.")
                    })
                }
            )
            put("required", buildJsonArray {
                add(JsonPrimitive("id"))
                add(JsonPrimitive("moduleId"))
            })
        }
    }

    private fun buildTemporaryWorkflowSchema(moduleIds: List<String>): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "workflow",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", JsonPrimitive(false))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "name",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "Short user-visible workflow name.")
                                        }
                                    )
                                    put(
                                        "description",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "Brief summary of what the workflow will do.")
                                        }
                                    )
                                    put(
                                        "maxExecutionTime",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("minimum", 1)
                                            put("maximum", 300)
                                            put("description", "Maximum execution time in seconds. Omit for 120 seconds.")
                                        }
                                    )
                                    put(
                                        "steps",
                                        buildJsonObject {
                                            put("type", "array")
                                            put("minItems", 1)
                                            put("maxItems", 80)
                                            put("description", "Ordered vFlow ActionStep objects. Do not include trigger modules.")
                                            put(
                                                "items",
                                                buildJsonObject {
                                                    put("type", "object")
                                                    put("additionalProperties", JsonPrimitive(false))
                                                    put(
                                                        "properties",
                                                        buildJsonObject {
                                                            put(
                                                                "id",
                                                                buildJsonObject {
                                                                    put("type", "string")
                                                                    put("description", "Unique step ID. Critical: other steps reference this step's outputs via {{this_id.output_name}}.")
                                                                }
                                                            )
                                                            put(
                                                                "moduleId",
                                                                buildJsonObject {
                                                                    put("type", "string")
                                                                    put("enum", JsonArray(moduleIds.map(::JsonPrimitive)))
                                                                }
                                                            )
                                                            put(
                                                                "parameters",
                                                                buildJsonObject {
                                                                    put("type", "object")
                                                                    put("additionalProperties", JsonPrimitive(true))
                                                                    put("description", "Step parameters. Values can be literal values or references. Two reference forms exist: {{previousStepId.outputId}} reads an earlier step's output; {{vars.paramName}} reads a parameter declared by the vflow.logic.define_function step. A bare {{paramName}} (without the `vars.` prefix) does NOT resolve and silently yields an empty value.")
                                                                }
                                                            )
                                                            put(
                                                                "indentationLevel",
                                                                buildJsonObject {
                                                                    put("type", "integer")
                                                                    put("minimum", 0)
                                                                    put("maximum", 8)
                                                                    put("description", "Visual indentation level for block contents. Use 1 inside a loop or if block.")
                                                                }
                                                            )
                                                        }
                                                    )
                                                    put(
                                                        "required",
                                                        buildJsonArray {
                                                            add(JsonPrimitive("moduleId"))
                                                            add(JsonPrimitive("parameters"))
                                                        }
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                            put(
                                "required",
                                buildJsonArray {
                                    add(JsonPrimitive("name"))
                                    add(JsonPrimitive("steps"))
                                }
                            )
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("workflow"))
                }
            )
        }
    }

    private fun buildSaveWorkflowSchema(moduleIds: List<String>): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "workflow",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", JsonPrimitive(false))
                            put(
                                "properties",
                                buildJsonObject {
                                    put(
                                        "name",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "Short user-visible workflow name.")
                                        }
                                    )
                                    put(
                                        "description",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "Brief summary shown in the workflow list.")
                                        }
                                    )
                                    put(
                                        "isEnabled",
                                        buildJsonObject {
                                            put("type", "boolean")
                                            put("description", "Whether the saved workflow is enabled. Omit for true.")
                                        }
                                    )
                                    put(
                                        "folderId",
                                        buildJsonObject {
                                            put("type", "string")
                                            put("description", "Optional existing workflow folder id.")
                                        }
                                    )
                                    put(
                                        "tags",
                                        buildJsonObject {
                                            put("type", "array")
                                            put("items", buildJsonObject { put("type", "string") })
                                            put("description", "Optional workflow tags.")
                                        }
                                    )
                                    put(
                                        "maxExecutionTime",
                                        buildJsonObject {
                                            put("type", "integer")
                                            put("minimum", 1)
                                            put("maximum", 3600)
                                            put("description", "Maximum execution time in seconds. Omit to use the app default.")
                                        }
                                    )
                                    put(
                                        "reentryBehavior",
                                        buildJsonObject {
                                            put("type", "string")
                                            put(
                                                "enum",
                                                JsonArray(
                                                    listOf(
                                                        "block_new",
                                                        "stop_current_and_run_new",
                                                        "allow_parallel",
                                                    ).map(::JsonPrimitive)
                                                )
                                            )
                                            put("description", "How to handle a new trigger while the workflow is already running.")
                                        }
                                    )
                                    put(
                                        "triggers",
                                        buildJsonObject {
                                            put("type", "array")
                                            put("maxItems", 12)
                                            put("description", "Optional trigger ActionStep objects. Use only vflow.trigger.* modules here. Omit for a manual trigger.")
                                            put("items", buildWorkflowStepItemSchema(moduleIds, "Trigger or manual step ID. Other steps may reference this step's outputs via {{this_id.output_name}}."))
                                        }
                                    )
                                    put(
                                        "steps",
                                        buildJsonObject {
                                            put("type", "array")
                                            put("minItems", 1)
                                            put("maxItems", 200)
                                            put("description", "Ordered non-trigger vFlow ActionStep objects.")
                                            put("items", buildWorkflowStepItemSchema(moduleIds, "Unique step ID. Critical: other steps reference this step's outputs via {{this_id.output_name}}."))
                                        }
                                    )
                                }
                            )
                            put(
                                "required",
                                buildJsonArray {
                                    add(JsonPrimitive("name"))
                                    add(JsonPrimitive("steps"))
                                }
                            )
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("workflow"))
                }
            )
        }
    }

    private fun buildWorkflowStepItemSchema(
        moduleIds: List<String>,
        idDescription: String,
    ): JsonObject {
        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                buildJsonObject {
                    put(
                        "id",
                        buildJsonObject {
                            put("type", "string")
                            put("description", idDescription)
                        }
                    )
                    put(
                        "moduleId",
                        buildJsonObject {
                            put("type", "string")
                            put("enum", JsonArray(moduleIds.map(::JsonPrimitive)))
                        }
                    )
                    put(
                        "parameters",
                        buildJsonObject {
                            put("type", "object")
                            put("additionalProperties", JsonPrimitive(true))
                            put("description", "Step parameters. Values can be literal values or references. Two reference forms exist: {{previousStepId.outputId}} reads an earlier step's output; {{vars.paramName}} reads a parameter declared by the vflow.logic.define_function step. A bare {{paramName}} (without the `vars.` prefix) does NOT resolve and silently yields an empty value.")
                        }
                    )
                    put(
                        "indentationLevel",
                        buildJsonObject {
                            put("type", "integer")
                            put("minimum", 0)
                            put("maximum", 12)
                            put("description", "Visual indentation level for block contents.")
                        }
                    )
                }
            )
            put(
                "required",
                buildJsonArray {
                    add(JsonPrimitive("moduleId"))
                    add(JsonPrimitive("parameters"))
                }
            )
        }
    }

    private fun buildToolDescription(
        module: ActionModule,
        localizedName: String,
        permissions: List<String>,
        riskLevel: ChatAgentToolRiskLevel,
        usageScopes: Set<ChatAgentToolUsageScope>,
    ): String {
        val parts = mutableListOf<String>()
        parts += "vFlow module: $localizedName."
        parts += module.aiMetadata?.directToolDescription
            ?: module.metadata.getLocalizedDescription(appContext)
        parts += "Risk level: ${riskLevel.name.lowercase()}."
        if (usageScopes.isNotEmpty()) {
            parts += "Usage scope: ${usageScopes.joinToString { it.label }}."
        }
        if (permissions.isNotEmpty()) {
            parts += "May require Android permissions: ${permissions.joinToString()}."
        }
        return parts.joinToString(separator = " ")
    }

    private fun buildToolRoutingHints(
        module: ActionModule,
        localizedName: String,
        inputs: List<InputDefinition>,
    ): Set<String> {
        val phrases = linkedSetOf<String>()

        fun addPhrase(value: String?) {
            val phrase = value?.trim()?.takeIf { it.isNotBlank() } ?: return
            phrases += phrase
        }

        addPhrase(localizedName)
        addPhrase(module.metadata.getLocalizedDescription(appContext))
        addPhrase(module.aiMetadata?.directToolDescription)
        addPhrase(module.aiMetadata?.workflowStepDescription)
        addPhrase(module.id.replace('.', ' '))

        inputs.forEach { input ->
            addPhrase(input.id.replace('_', ' '))
            addPhrase(input.getLocalizedName(appContext))
            addPhrase(input.getLocalizedHint(appContext))
            input.options.forEach(::addPhrase)
            input.getLocalizedOptions(appContext).forEach(::addPhrase)
            input.legacyValueMap?.keys?.forEach(::addPhrase)
            addPhrase(module.aiMetadata?.inputHints?.get(input.id))
        }

        return phrases
            .flatMap(::expandRoutingHints)
            .toSet()
    }

    private fun expandRoutingHints(raw: String): Set<String> {
        val normalized = raw
            .lowercase()
            .replace("wi-fi", "wifi")
            .replace('_', ' ')
            .replace(Regex("""\s+"""), " ")
            .trim()
        if (normalized.isBlank()) return emptySet()

        val hints = linkedSetOf<String>()
        hints += normalized

        Regex("""[\p{L}\p{N}]+""").findAll(normalized)
            .map { it.value.trim() }
            .filter { token ->
                token.length >= 2 &&
                    token !in ROUTING_HINT_STOP_WORDS &&
                    !(token.length < 4 && token.all { char -> char.code < 128 })
            }
            .forEach(hints::add)

        return hints
    }

    private fun buildToolSchema(
        moduleId: String,
        inputs: List<InputDefinition>,
    ): JsonObject {
        val required = ModuleRegistry.getModule(moduleId)?.aiMetadata?.requiredInputIds.orEmpty()
            .filter { inputId -> inputs.any { it.id == inputId } }

        return buildJsonObject {
            put("type", "object")
            put("additionalProperties", JsonPrimitive(false))
            put(
                "properties",
                buildJsonObject {
                    inputs.forEach { input ->
                        put(input.id, buildInputSchema(moduleId, input))
                    }
                }
            )
            if (required.isNotEmpty()) {
                put(
                    "required",
                    buildJsonArray {
                        required.forEach { inputId ->
                            add(JsonPrimitive(inputId))
                        }
                    }
                )
            }
        }
    }

    private fun buildInputSchema(moduleId: String, input: InputDefinition): JsonObject {
        return buildJsonObject {
            when (input.staticType) {
                ParameterType.STRING -> put("type", "string")
                ParameterType.NUMBER -> put("type", "number")
                ParameterType.BOOLEAN -> put("type", "boolean")
                ParameterType.ENUM -> {
                    put("type", "string")
                    put(
                        "enum",
                        JsonArray(input.options.map(::JsonPrimitive))
                    )
                }
                ParameterType.ANY -> put("type", "string")
            }

            val description = buildInputDescription(moduleId, input)
            if (description.isNotBlank()) {
                put("description", description)
            }
        }
    }

    private fun buildInputDescription(moduleId: String, input: InputDefinition): String =
        buildModuleInputDescription(appContext, moduleId, input)

    private fun isInputSupported(input: InputDefinition): Boolean {
        return when (input.staticType) {
            ParameterType.STRING,
            ParameterType.NUMBER,
            ParameterType.BOOLEAN,
            ParameterType.ENUM -> true

            ParameterType.ANY -> input.acceptedMagicVariableTypes.isNotEmpty()
        }
    }

    private fun artifactTypeLabelFromTypeId(typeId: String): String? =
        artifactTypeLabel(typeId)

    private fun buildModuleUsageScopes(moduleId: String): Set<ChatAgentToolUsageScope> {
        val metadata = ModuleRegistry.getModule(moduleId)?.aiMetadata
        return buildSet {
            if (metadata?.usageScopes?.contains(AiModuleUsageScope.DIRECT_TOOL) == true) {
                add(ChatAgentToolUsageScope.DIRECT_TOOL)
            }
            if (metadata?.usageScopes?.contains(AiModuleUsageScope.TEMPORARY_WORKFLOW) == true) {
                add(ChatAgentToolUsageScope.TEMPORARY_WORKFLOW)
            }
            if (moduleId in savedWorkflowModuleIds) {
                add(ChatAgentToolUsageScope.SAVED_WORKFLOW)
            }
        }
    }

    private fun buildDirectToolDefinitions(): List<ChatAgentToolDefinition> {
        val moduleIds = ModuleRegistry.getAllModules()
            .filter { module ->
                module.aiMetadata?.usageScopes?.contains(AiModuleUsageScope.DIRECT_TOOL) == true ||
                    module.id in LEGACY_DIRECT_TOOL_MODULE_IDS
            }
            .map { it.id }
            .distinct()
            .sorted()
        return moduleIds.mapNotNull(::buildToolDefinition)
    }

    private fun buildTemporaryWorkflowModuleIds(): List<String> {
        return ModuleRegistry.getAllModules()
            .filter { module ->
                module.aiMetadata?.usageScopes?.contains(AiModuleUsageScope.TEMPORARY_WORKFLOW) == true ||
                    module.id in LEGACY_TEMPORARY_WORKFLOW_MODULE_IDS
            }
            .sortedWith(compareBy<ActionModule> { ModuleCategories.getSortOrder(it.metadata.getResolvedCategoryId()) }.thenBy { it.id })
            .map { it.id }
    }

    private fun buildSavedWorkflowModuleIds(): List<String> {
        return ModuleRegistry.getAllModules()
            .filter(::isSavedWorkflowModuleAllowed)
            .sortedWith(compareBy<ActionModule> { ModuleCategories.getSortOrder(it.metadata.getResolvedCategoryId()) }.thenBy { it.id })
            .map { it.id }
    }

    private fun isSavedWorkflowModuleAllowed(module: ActionModule): Boolean {
        val category = module.metadata.getResolvedCategoryId()
        if (category == ModuleCategories.TEMPLATE) return false
        if (module.id.startsWith("vflow.snippet.")) return false
        if (module.id in LEGACY_SAVED_WORKFLOW_EXCLUDED_MODULE_IDS) return false
        if (module.aiMetadata?.allowSavedWorkflow == false) return false
        return true
    }

    private fun isTriggerModule(module: ActionModule): Boolean {
        return module.metadata.getResolvedCategoryId() == ModuleCategories.TRIGGER ||
            module.id.startsWith(TRIGGER_MODULE_PREFIX)
    }

    /**
     * 模块**清单**——只留 moduleId，不带描述与字段名。
     *
     * 这是 P1-1 的「分层」：清单常驻在工作流工具的 description 里，
     * **完整字段定义改由 `query_module_schema` 按需提供**。
     *
     * 为什么连描述都去掉：184 个模块仅 id 就占约 4,400 字符，加上分隔符约 5,900 字符
     * （≈1,480 token）。再带描述与字段名会涨到约 24,000 字符（≈6,000 token）。
     * 而描述与字段名**只在真要用某个模块时才需要**——这正是按需查询要解决的。
     *
     * @param preferWorkflowDescriptions 保留参数以兼容调用点；清单模式下不再使用描述。
     */
    private fun buildCompactModuleCatalog(
        moduleIds: List<String>,
        preferWorkflowDescriptions: Boolean,
    ): String {
        val entries = moduleIds.mapNotNull { moduleId ->
            ModuleRegistry.getModule(moduleId)?.id
        }
        if (entries.isEmpty()) return ""
        return " Module catalog (call $CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME for a module's parameters): " +
            entries.joinToString(", ") + "."
    }

    /**
     * 魔法变量语法与块结构配对规则。
     *
     * **保留**：这是无法从模块元数据推导的**操作知识**（变量引用语法、块配对约束、
     * 类型属性表），不是可查询的元数据，故不随 catalog 分层一起按需化。
     *
     * 原本末尾还会追加 184 个模块的输出键清单（约 8,000 字符），
     * 现改由 `query_module_schema` 按需提供——它的返回里已含 outputs。
     */
    private fun buildVariablePassingGuide(): String {
        return """
To pass data from one step to another, give each step a meaningful `id` and use magic variable syntax in parameters: {{STEP_ID.OUTPUT_ID}}.
- References must point to earlier steps only. Do not reference future steps or output ids that are not listed for that module.
- Property access: {{STEP_ID.OUTPUT_ID.PROPERTY}}.
- Available properties by output type: Image(.width,.height,.path,.size,.name,.uri,.base64), File(.path,.uri,.name,.extension,.mimeType,.size,.base64), ScreenElement(.text,.content_description,.all_texts,.x,.y,.width,.height,.center,.region,.id,.class), Coordinate(.x,.y), List(.count,.first,.last,.random,.isempty), String(.length,.uppercase,.lowercase,.trim,.removeSpaces), Number(.int,.round,.abs,.length), Dictionary(.count,.keys,.values).
- Coordinate passing: When a step outputs a Coordinate or a property like .center, pass the whole object directly (e.g. "target": "{{find_btn.elements.0.center}}"). Do NOT manually splice x,y components unless the target format requires separate values.
- List indexing: {{step.list.0}} for first item; String slicing: {{step.str.0}} for first character, {{step.str.0:3}} for substring.
- Element finding preference: Prefer vflow.interaction.find_element (accessibility service) over OCR whenever possible; use OCR only as a fallback when accessibility cannot find the target.
- UI interaction preference: For multi-step screen actions, first collect a fresh control snapshot with a read-only observation step, act on returned ScreenElement outputs instead of guessed labels when possible, and re-check the final screen state before claiming success.

Block structure rules:
- Loop.start/Loop.end and If.start/If.middle/If.end must be paired. Set indentationLevel=1 for steps inside a loop or if block.
- Loop.start outputs "loop_index" (1-based) and "loop_total". Use {{loop_start_id.loop_index}} inside the loop body.
- If.start evaluates its "condition" parameter; If.end has no extra parameters.""".trimIndent()
    }

    private companion object {
        private const val TRIGGER_MODULE_PREFIX = "vflow.trigger."
        private val ROUTING_HINT_STOP_WORDS = setOf(
            "mode",
            "type",
            "text",
            "input",
            "result",
            "selection",
            "module",
            "tool",
            "screen",
            "ui",
            "操作",
            "模式",
            "结果",
            "选择",
            "输入",
            "目标",
            "模块",
            "工具",
            "系统",
        )

        private val LEGACY_DIRECT_TOOL_MODULE_IDS = setOf(
            "vflow.interaction.get_current_activity",
            "vflow.system.capture_screen",
            "vflow.core.capture_screen",
            "vflow.interaction.ocr",
            "vflow.interaction.find_element",
            "vflow.device.click",
            "vflow.interaction.screen_operation",
            "vflow.interaction.input_text",
            "vflow.device.send_key_event",
            "vflow.core.screen_operation",
            "vflow.core.input_text",
            "vflow.core.press_key",
            "vflow.system.launch_app",
            "vflow.system.close_app",
            "vflow.core.force_stop_app",
            "vflow.system.wifi",
            "vflow.system.bluetooth",
            "vflow.system.brightness",
            "vflow.system.mobile_data",
            "vflow.system.get_clipboard",
            "vflow.system.set_clipboard",
            "vflow.core.get_clipboard",
            "vflow.core.set_clipboard",
            "vflow.system.wake_screen",
            "vflow.system.wake_and_unlock_screen",
            "vflow.system.sleep_screen",
            "vflow.system.darkmode",
            "vflow.system.do_not_disturb",
            "vflow.device.vibration",
            "vflow.device.flashlight",
            "vflow.device.delay",
            "vflow.shizuku.shell_command",
            "vflow.core.shell_command",
        )

        private val LEGACY_TEMPORARY_WORKFLOW_MODULE_IDS = LEGACY_DIRECT_TOOL_MODULE_IDS + setOf(
            "vflow.logic.loop.start",
            "vflow.logic.loop.end",
            "vflow.logic.if.start",
            "vflow.logic.if.middle",
            "vflow.logic.if.end",
            "vflow.logic.break_loop",
            "vflow.logic.continue_loop",
        )

        private val LEGACY_SAVED_WORKFLOW_EXCLUDED_MODULE_IDS = setOf(
            "vflow.ai.agent",
            "vflow.ai.autoglm",
            "vflow.interaction.operit",
        )

        private fun riskLevelForModuleId(moduleId: String): ChatAgentToolRiskLevel {
            return when (ModuleRegistry.getModule(moduleId)?.aiMetadata?.riskLevel) {
                AiModuleRiskLevel.READ_ONLY -> ChatAgentToolRiskLevel.READ_ONLY
                AiModuleRiskLevel.LOW -> ChatAgentToolRiskLevel.LOW
                AiModuleRiskLevel.HIGH -> ChatAgentToolRiskLevel.HIGH
                AiModuleRiskLevel.STANDARD, null -> ChatAgentToolRiskLevel.STANDARD
            }
        }
    }
}

/**
 * 产物类型 id → 人类可读标签。
 *
 * 提为顶层函数是为了让 `ChatAgentModuleExecutor` 的 `query_module_schema` 复用——
 * 撤走 59 个模块工具后，模块 schema 的唯一出口是那个工具，两处必须同源。
 */
internal fun artifactTypeLabel(typeId: String): String? {
    return when (typeId) {
        VTypeRegistry.IMAGE.id -> "image"
        VTypeRegistry.FILE.id -> "file"
        VTypeRegistry.COORDINATE.id -> "coordinate"
        VTypeRegistry.COORDINATE_REGION.id -> "coordinate region"
        VTypeRegistry.SCREEN_ELEMENT.id -> "screen element"
        VTypeRegistry.STRING.id -> "text"
        VTypeRegistry.NUMBER.id -> "number"
        else -> null
    }
}

/**
 * 拼装单个输入字段的完整说明：中文名 + 本地化提示 + 可接受的产物类型 +
 * **模块声明的 `inputHints`** + 枚举可选值。
 *
 * ⚠️ **`inputHints` 是这里最要紧的一段**：它是模块作者为 AI 写的**字段语义**，
 * 例如 `IfModule` 的
 * `"value2" to "Upper bound used only by the number_between operator."`
 * ——这类信息**无法从字段名或类型推出**，丢了就只能靠模型猜。
 * 全项目 67 个模块声明了 `inputHints`。
 *
 * 提为顶层函数：`ChatAgentToolRegistry`（模块工具的 JSON Schema）与
 * `ChatAgentModuleExecutor`（`query_module_schema` 的文本输出）共用，
 * 保证两条路径口径一致——否则撤走模块工具后信息会静默丢失。
 */
internal fun buildModuleInputDescription(
    context: Context,
    moduleId: String,
    input: InputDefinition,
): String {
    val parts = mutableListOf<String>()
    parts += input.getLocalizedName(context)
    input.getLocalizedHint(context)
        ?.takeIf { it.isNotBlank() }
        ?.let(parts::add)

    val artifactTypes = input.acceptedMagicVariableTypes
        .mapNotNull(::artifactTypeLabel)
        .distinct()
    if (artifactTypes.isNotEmpty()) {
        parts += "Can accept prior artifact handles of type ${artifactTypes.joinToString()}."
    }

    ModuleRegistry.getModule(moduleId)?.aiMetadata?.inputHints?.get(input.id)
        ?.let(parts::add)

    if (input.staticType == ParameterType.ENUM && input.options.isNotEmpty()) {
        parts += "Allowed values: ${input.options.joinToString()}."
    }

    return parts.joinToString(separator = " ")
}
