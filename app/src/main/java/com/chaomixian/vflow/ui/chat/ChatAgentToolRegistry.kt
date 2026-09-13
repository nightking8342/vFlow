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
        toolsByName = (
            listOf(
                buildTemporaryWorkflowToolDefinition(),
                buildSaveWorkflowToolDefinition(),
                buildLoadSkillToolDefinition(),
                buildQueryModuleSchemaToolDefinition(),
            ) +
                ChatAgentNativeToolExecutor.buildDefinitions(appContext) +
                buildDirectToolDefinitions()
            ).associateBy { it.name }
    }

    fun getTools(): List<ChatAgentToolDefinition> = toolsByName.values.toList()

    fun getTool(name: String): ChatAgentToolDefinition? = toolsByName[name]

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
        val inputs = resolveModuleInputDefinitions(module, baseStep)
            .filterNot { it.isHidden }
            .filter(::isInputSupported)
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
                        put(
                            "operator",
                            buildJsonObject {
                                put("type", "string")
                                put(
                                    "description",
                                    "Optional. For conditional modules, the operator to get a precise field set for."
                                )
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
                                                                    put("description", "Step parameters. Values can be literal values or magic variable references like {{previousStepId.outputId}} to pass data from earlier steps.")
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
                            put("description", "Step parameters. Values can be literal values or magic variable references like {{previousStepId.outputId}} to pass data from earlier steps.")
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

    private fun buildInputDescription(moduleId: String, input: InputDefinition): String {
        val parts = mutableListOf<String>()
        parts += input.getLocalizedName(appContext)
        input.getLocalizedHint(appContext)
            ?.takeIf { it.isNotBlank() }
            ?.let(parts::add)

        val artifactTypes = input.acceptedMagicVariableTypes
            .mapNotNull(::artifactTypeLabelFromTypeId)
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

    private fun isInputSupported(input: InputDefinition): Boolean {
        return when (input.staticType) {
            ParameterType.STRING,
            ParameterType.NUMBER,
            ParameterType.BOOLEAN,
            ParameterType.ENUM -> true

            ParameterType.ANY -> input.acceptedMagicVariableTypes.isNotEmpty()
        }
    }

    private fun artifactTypeLabelFromTypeId(typeId: String): String? {
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
