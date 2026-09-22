package com.chaomixian.vflow.ui.chat

import android.content.Context
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionState
import com.chaomixian.vflow.core.execution.ExecutionStateBus
import com.chaomixian.vflow.core.execution.ExecutionServices
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.ActionModule
import com.chaomixian.vflow.core.module.AiParameterNormalizer
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinate
import com.chaomixian.vflow.core.types.complex.VCoordinateRegion
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.types.complex.VScreenElement
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
import com.chaomixian.vflow.core.workflow.FolderManager
import com.chaomixian.vflow.core.workflow.GlobalVariableStore
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.WorkflowPatch
import com.chaomixian.vflow.core.workflow.StepInsertion
import com.chaomixian.vflow.core.workflow.StepListPatch
import com.chaomixian.vflow.core.workflow.StepMove
import com.chaomixian.vflow.core.workflow.StepPatchOutcome
import com.chaomixian.vflow.core.workflow.applyStepListPatch
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.ExecutionUIService
import com.chaomixian.vflow.services.ServiceStateBus
import java.io.File
import java.util.Stack
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal class ChatAgentArtifactStore {
    private val artifacts = linkedMapOf<String, Any?>()
    private val sessionState = linkedMapOf<String, Any?>()

    fun createReferences(
        callId: String,
        outputs: Map<String, Any?>,
    ): List<ChatArtifactReference> {
        return outputs.mapNotNull { (key, value) ->
            store(callId = callId, key = key, value = value)
        }
    }

    fun store(
        callId: String,
        key: String,
        value: Any?,
        explicitTypeLabel: String? = null,
    ): ChatArtifactReference? {
        val typeLabel = explicitTypeLabel ?: chatArtifactTypeLabel(value) ?: return null
        val handle = "artifact://$callId/$key"
        artifacts[handle] = value
        return ChatArtifactReference(
            key = key,
            handle = handle,
            typeLabel = typeLabel,
        )
    }

    fun resolve(handle: String): Any? = artifacts[handle]

    fun snapshotArtifacts(): Map<String, Any?> = LinkedHashMap(artifacts)

    fun rememberSessionValue(
        key: String,
        value: Any?,
    ) {
        sessionState[key] = value
    }

    @Suppress("UNCHECKED_CAST")
    fun <T> recallSessionValue(key: String): T? = sessionState[key] as? T

    fun snapshotSessionState(): Map<String, Any?> = LinkedHashMap(sessionState)
}

internal fun chatArtifactTypeLabel(value: Any?): String? {
    return when (value) {
        is VImage -> "image"
        is VCoordinate -> "coordinate"
        is VCoordinateRegion -> "coordinate region"
        is VScreenElement -> "screen element"
        is ChatAgentUiSnapshot -> "ui snapshot"
        else -> null
    }
}

internal sealed interface ChatPreparedToolItem {
    val toolCall: ChatToolCall

    data class Ready(
        override val toolCall: ChatToolCall,
        val definition: ChatAgentToolDefinition,
        val module: ActionModule,
        val step: ActionStep,
        val missingPermissions: List<Permission>,
    ) : ChatPreparedToolItem

    data class NativeReady(
        override val toolCall: ChatToolCall,
        val definition: ChatAgentToolDefinition,
        val request: ChatAgentNativeRequest,
        val missingPermissions: List<Permission>,
    ) : ChatPreparedToolItem

    /**
     * 无需执行、直接返回结果的工具调用。
     *
     * @property riskLevel 该次调用对整批风险等级的贡献（batch 取 max）。
     *   默认 [ChatAgentToolRiskLevel.HIGH]：现有使用点都是错误/前置校验路径，标 HIGH 合理。
     *   纯本地计算、无副作用的工具（如 `load_skill`）应显式传 `READ_ONLY`——
     *   否则每次调用都会触发审批弹窗。
     */
    data class ImmediateResult(
        override val toolCall: ChatToolCall,
        val result: ChatToolResult,
        val riskLevel: ChatAgentToolRiskLevel = ChatAgentToolRiskLevel.HIGH,
    ) : ChatPreparedToolItem

    data class TemporaryWorkflow(
        override val toolCall: ChatToolCall,
        val definition: ChatAgentToolDefinition,
        val workflow: Workflow,
        val preparedSteps: List<Ready>,
        val validationErrors: List<ChatToolResult>,
        val missingPermissions: List<Permission>,
        val riskLevel: ChatAgentToolRiskLevel,
    ) : ChatPreparedToolItem

    data class SaveWorkflow(
        override val toolCall: ChatToolCall,
        val definition: ChatAgentToolDefinition,
        val workflow: Workflow,
        val validationErrors: List<ChatToolResult>,
        val missingPermissions: List<Permission>,
        val riskLevel: ChatAgentToolRiskLevel,
    ) : ChatPreparedToolItem

    /**
     * `update_workflow`：把操作原语补丁应用到已有工作流上。
     *
     * 与 [SaveWorkflow] 分开而不是复用，因为两者的语义不同：
     * [SaveWorkflow] 的 id 是新生成的、整表替换；本项保持**原 id 不变**（靠它命中已有记录）、
     * 只改补丁提到的部分。顺带要带上 [warnings]——那是「改动成功但用户需要知道」的提示。
     */
    data class UpdateWorkflow(
        override val toolCall: ChatToolCall,
        val definition: ChatAgentToolDefinition,
        val workflowId: String,
        val workflow: Workflow,
        val warnings: List<String>,
        val validationErrors: List<ChatToolResult>,
        val missingPermissions: List<Permission>,
        val riskLevel: ChatAgentToolRiskLevel,
    ) : ChatPreparedToolItem
}

internal data class ChatPreparedToolBatch(
    val items: List<ChatPreparedToolItem>,
    val missingPermissions: List<Permission>,
    val riskLevel: ChatAgentToolRiskLevel,
)

internal class ChatAgentModuleExecutor(
    context: Context,
    private val toolRegistry: ChatAgentToolRegistry,
) {
    private val appContext = context.applicationContext
    private val json = Json { ignoreUnknownKeys = true }
    private val nativeToolExecutor = ChatAgentNativeToolExecutor(appContext)

    fun prepareBatch(
        toolCalls: List<ChatToolCall>,
        artifactStore: ChatAgentArtifactStore,
    ): ChatPreparedToolBatch {
        val items = toolCalls.map { prepareToolCall(it, artifactStore) }
        val missingPermissions = items
            .flatMap { item ->
                when (item) {
                    is ChatPreparedToolItem.Ready -> item.missingPermissions
                    is ChatPreparedToolItem.NativeReady -> item.missingPermissions
                    is ChatPreparedToolItem.TemporaryWorkflow -> item.missingPermissions
                    is ChatPreparedToolItem.SaveWorkflow -> emptyList()
                    is ChatPreparedToolItem.UpdateWorkflow -> emptyList()
                    is ChatPreparedToolItem.ImmediateResult -> emptyList()
                }
            }
            .distinctBy { it.id }
        return ChatPreparedToolBatch(
            items = items,
            missingPermissions = missingPermissions,
            riskLevel = ChatAgentToolRiskLevel.maxOf(items.map(::riskLevelOf)),
        ).also { batch ->
            DebugLogger.i(
                LOG_TAG,
                "Prepared tool batch tools=${toolCalls.joinToString { it.name }} items=${batch.items.size} risk=${batch.riskLevel} missingPermissions=${batch.missingPermissions.joinToString { it.id }}"
            )
        }
    }

    suspend fun executeBatch(
        batch: ChatPreparedToolBatch,
        artifactStore: ChatAgentArtifactStore,
    ): List<ChatToolResult> {
        DebugLogger.i(
            LOG_TAG,
            "Executing prepared batch items=${batch.items.size} risk=${batch.riskLevel}"
        )
        return batch.items.map { item ->
            DebugLogger.i(LOG_TAG, "Tool start ${item.describeForLog()}")
            when (item) {
                is ChatPreparedToolItem.ImmediateResult -> item.result
                is ChatPreparedToolItem.Ready -> executeReadyTool(item, artifactStore)
                is ChatPreparedToolItem.NativeReady -> nativeToolExecutor.execute(item, artifactStore)
                is ChatPreparedToolItem.TemporaryWorkflow -> executeTemporaryWorkflow(item, artifactStore)
                is ChatPreparedToolItem.SaveWorkflow -> executeSaveWorkflow(item)
                is ChatPreparedToolItem.UpdateWorkflow -> executeUpdateWorkflow(item)
            }.also { result ->
                DebugLogger.i(
                    LOG_TAG,
                    "Tool end ${item.describeForLog()} status=${result.status} output=${result.outputText.compactForLog()}"
                )
            }
        }
    }

    fun buildWorkflowForSave(toolCall: ChatToolCall): Workflow? {
        if (toolCall.name != CHAT_TEMPORARY_WORKFLOW_TOOL_NAME) return null
        return try {
            val spec = parseTemporaryWorkflowSpec(toolCall)
            val readySteps = mutableListOf<ActionStep>()
            spec.steps.forEach { stepSpec ->
                if (!toolRegistry.isTemporaryWorkflowModuleAllowed(stepSpec.moduleId)) return null
                val module = ModuleRegistry.getModule(stepSpec.moduleId) ?: return null
                val built = buildParameters(module, stepSpec.parameters.toString(), ChatAgentArtifactStore())
                if (built.rejectedKeys.isNotEmpty()) {
                    DebugLogger.w(
                        LOG_TAG,
                        "Temporary workflow step `${stepSpec.moduleId}` has unknown parameters: " +
                            built.rejectedKeys.joinToString(", ")
                    )
                    return null
                }
                readySteps += ActionStep(
                    moduleId = module.id,
                    parameters = built.parameters,
                    indentationLevel = stepSpec.indentationLevel,
                    id = stepSpec.id,
                )
            }
            Workflow(
                id = "chat_saved_${UUID.randomUUID()}",
                name = spec.name,
                triggers = listOf(
                    ActionStep(moduleId = "vflow.trigger.manual", parameters = emptyMap(), id = "manual_trigger")
                ),
                steps = readySteps,
                isEnabled = true,
                description = spec.description,
                maxExecutionTime = spec.maxExecutionTime,
                reentryBehavior = WorkflowReentryBehavior.STOP_CURRENT_AND_RUN_NEW,
            )
        } catch (_: Throwable) {
            null
        }
    }

    fun buildRejectedResults(toolCalls: List<ChatToolCall>): List<ChatToolResult> {
        return toolCalls.map { toolCall ->
            val definition = toolRegistry.getTool(toolCall.name)
            val title = definition?.title ?: toolCall.name
            ChatToolResult(
                callId = toolCall.id,
                name = toolCall.name,
                status = ChatToolResultStatus.REJECTED,
                summary = title,
                outputText = chatAgentAppendNextStep(
                    baseMessage = "Tool execution was rejected by the user for `$title`.",
                    nextStep = "choose a lower-risk tool, ask the user for approval, or explain what still needs confirmation.",
                ),
            )
        }
    }

    fun buildPermissionRequiredResults(batch: ChatPreparedToolBatch): List<ChatToolResult> {
        return batch.items.map { item ->
            when (item) {
                is ChatPreparedToolItem.ImmediateResult -> item.result
                is ChatPreparedToolItem.NativeReady -> {
                    val permissionNames = item.missingPermissions
                        .map { it.getLocalizedName(appContext) }
                        .distinct()
                    ChatToolResult(
                        callId = item.toolCall.id,
                        name = item.toolCall.name,
                        status = ChatToolResultStatus.PERMISSION_REQUIRED,
                        summary = item.definition.title,
                        outputText = chatAgentAppendNextStep(
                            baseMessage = "Tool `${item.definition.title}` could not run because the following permissions are not granted: ${permissionNames.joinToString()}.",
                            nextStep = "ask the user to grant those permissions, then retry the same tool. ${item.definition.nativeHelperId?.let(::chatAgentNativeRecoveryHint).orEmpty()}".trim(),
                        ),
                    )
                }
                is ChatPreparedToolItem.SaveWorkflow -> {
                    ChatToolResult(
                        callId = item.toolCall.id,
                        name = item.toolCall.name,
                        status = ChatToolResultStatus.PERMISSION_REQUIRED,
                        summary = item.definition.title,
                        outputText = chatAgentAppendNextStep(
                            baseMessage = "Saved workflow `${item.workflow.name}` could not be saved because required permissions are not granted.",
                            nextStep = "ask the user to grant the missing permissions, then save the workflow again.",
                        ),
                    )
                }
                is ChatPreparedToolItem.UpdateWorkflow -> {
                    ChatToolResult(
                        callId = item.toolCall.id,
                        name = item.toolCall.name,
                        status = ChatToolResultStatus.PERMISSION_REQUIRED,
                        summary = item.definition.title,
                        outputText = chatAgentAppendNextStep(
                            baseMessage = "Workflow `${item.workflow.name}` was not modified because the resulting workflow needs permissions that are not granted.",
                            nextStep = "ask the user to grant the missing permissions, then apply the same patch again.",
                        ),
                    )
                }
                is ChatPreparedToolItem.TemporaryWorkflow -> {
                    val permissionNames = item.missingPermissions
                        .map { it.getLocalizedName(appContext) }
                        .distinct()
                    ChatToolResult(
                        callId = item.toolCall.id,
                        name = item.toolCall.name,
                        status = ChatToolResultStatus.PERMISSION_REQUIRED,
                        summary = item.definition.title,
                        outputText = chatAgentAppendNextStep(
                            baseMessage = "Temporary workflow `${item.definition.title}` could not run because the following permissions are not granted: ${permissionNames.joinToString()}.",
                            nextStep = "ask the user to grant those permissions, then retry the workflow.",
                        ),
                    )
                }
                is ChatPreparedToolItem.Ready -> {
                    val permissionNames = item.missingPermissions
                        .map { it.getLocalizedName(appContext) }
                        .distinct()
                    ChatToolResult(
                        callId = item.toolCall.id,
                        name = item.toolCall.name,
                        status = ChatToolResultStatus.PERMISSION_REQUIRED,
                        summary = item.definition.title,
                        outputText = chatAgentAppendNextStep(
                            baseMessage = "Tool `${item.definition.title}` could not run because the following permissions are not granted: ${permissionNames.joinToString()}.",
                            nextStep = "ask the user to grant those permissions, then retry the same tool.",
                        ),
                    )
                }
            }
        }
    }

    private fun riskLevelOf(item: ChatPreparedToolItem): ChatAgentToolRiskLevel {
        return when (item) {
            is ChatPreparedToolItem.Ready -> item.definition.riskLevel
            is ChatPreparedToolItem.NativeReady -> item.definition.riskLevel
            is ChatPreparedToolItem.TemporaryWorkflow -> item.riskLevel
            is ChatPreparedToolItem.SaveWorkflow -> item.riskLevel
            is ChatPreparedToolItem.UpdateWorkflow -> item.riskLevel
            is ChatPreparedToolItem.ImmediateResult -> item.riskLevel
        }
    }

    private fun prepareToolCall(
        toolCall: ChatToolCall,
        artifactStore: ChatAgentArtifactStore,
    ): ChatPreparedToolItem {
        if (toolCall.name == CHAT_LOAD_SKILL_TOOL_NAME) {
            return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = prepareLoadSkill(toolCall),
                // 纯本地查表 + 拼字符串：无权限、无副作用、无 IO，
                // 若沿用 ImmediateResult 的默认 HIGH，每次加载技能都会弹审批。
                riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            )
        }
        if (toolCall.name == CHAT_QUERY_MODULE_SCHEMA_TOOL_NAME) {
            return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = prepareQueryModuleSchema(toolCall),
                riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            )
        }
        if (toolCall.name == CHAT_CALL_MODULE_TOOL_NAME) {
            return prepareCallModule(toolCall, artifactStore)
        }
        if (toolCall.name == CHAT_LIST_WORKFLOWS_TOOL_NAME) {
            return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = prepareListWorkflows(toolCall),
                riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            )
        }
        if (toolCall.name == CHAT_GET_ENVIRONMENT_TOOL_NAME) {
            return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = prepareGetEnvironment(toolCall),
                riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            )
        }
        if (toolCall.name == CHAT_TEMPORARY_WORKFLOW_TOOL_NAME) {
            return prepareTemporaryWorkflow(toolCall, artifactStore)
        }
        if (toolCall.name == CHAT_SAVE_WORKFLOW_TOOL_NAME) {
            return prepareSaveWorkflow(toolCall, artifactStore)
        }
        if (toolCall.name == CHAT_GET_WORKFLOW_TOOL_NAME) {
            return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = prepareGetWorkflow(toolCall),
                riskLevel = ChatAgentToolRiskLevel.READ_ONLY,
            )
        }
        if (toolCall.name == CHAT_UPDATE_WORKFLOW_TOOL_NAME) {
            return prepareUpdateWorkflow(toolCall, artifactStore)
        }

        val definition = toolRegistry.getTool(toolCall.name)
            ?: return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = toolCall.name,
                    outputText = "Unknown tool `${toolCall.name}`.",
                )
            )

        if (definition.backend == ChatAgentToolBackend.NATIVE_HELPER) {
            return nativeToolExecutor.prepare(definition, toolCall, artifactStore)
        }

        val module = ModuleRegistry.getModule(definition.moduleId)
            ?: return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = definition.title,
                    outputText = "The vFlow module for `${definition.title}` is not registered.",
                )
            )

        return prepareModuleStep(
            toolCall = toolCall,
            definition = definition,
            module = module,
            rawArgumentsJson = toolCall.argumentsJson,
            artifactStore = artifactStore,
        )
    }

    private fun prepareModuleStep(
        toolCall: ChatToolCall,
        definition: ChatAgentToolDefinition,
        module: ActionModule,
        rawArgumentsJson: String,
        artifactStore: ChatAgentArtifactStore,
        stepId: String = UUID.randomUUID().toString(),
        indentationLevel: Int = 0,
    ): ChatPreparedToolItem {
        return try {
            val built = buildParameters(module, rawArgumentsJson, artifactStore)
            if (built.rejectedKeys.isNotEmpty()) {
                return ChatPreparedToolItem.ImmediateResult(
                    toolCall = toolCall,
                    result = ChatToolResult(
                        callId = toolCall.id,
                        name = toolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = definition.title,
                        outputText = buildRejectedKeysMessage(module, definition, built),
                    )
                )
            }
            val step = ActionStep(
                moduleId = module.id,
                parameters = built.parameters,
                indentationLevel = indentationLevel,
                id = stepId,
            )
            val validation = module.validate(step, listOf(step))
            if (!validation.isValid) {
                ChatPreparedToolItem.ImmediateResult(
                    toolCall = toolCall,
                    result = ChatToolResult(
                        callId = toolCall.id,
                        name = toolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = definition.title,
                        outputText = validation.errorMessage
                            ?: "Validation failed for `${definition.title}`.",
                    )
                )
            } else {
                ChatPreparedToolItem.Ready(
                    toolCall = toolCall,
                    definition = definition,
                    module = module,
                    step = step,
                    missingPermissions = module.getRequiredPermissions(step)
                        .filterNot { PermissionManager.isGranted(appContext, it) },
                )
            }
        } catch (throwable: Throwable) {
            ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = definition.title,
                    outputText = throwable.message?.ifBlank { null }
                        ?: "Failed to parse arguments for `${definition.title}`.",
                )
            )
        }
    }

    private fun prepareTemporaryWorkflow(
        toolCall: ChatToolCall,
        artifactStore: ChatAgentArtifactStore,
    ): ChatPreparedToolItem {
        val definition = toolRegistry.getTool(toolCall.name)
            ?: return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = toolCall.name,
                    outputText = "Unknown temporary workflow tool `${toolCall.name}`.",
                )
            )

        return try {
            val spec = parseTemporaryWorkflowSpec(toolCall)
            val readySteps = mutableListOf<ChatPreparedToolItem.Ready>()
            val validationErrors = mutableListOf<ChatToolResult>()
            spec.steps.forEachIndexed { index, stepSpec ->
                val preparedToolCall = ChatToolCall(
                    id = "${normalizedToolCallId(toolCall)}_${stepSpec.id}",
                    name = chatToolNameFromModuleId(stepSpec.moduleId),
                    argumentsJson = stepSpec.parameters.toString(),
                )
                // 同保存工作流路径：先判存在性，避免"不存在"被报成"未暴露"。
                if (!toolRegistry.isRegisteredModule(stepSpec.moduleId)) {
                    validationErrors += buildTemporaryWorkflowValidationError(
                        toolCall = preparedToolCall,
                        summary = definition.title,
                        outputText = "Temporary workflow step ${index + 1} uses module `${stepSpec.moduleId}`, which does not exist. " +
                            "No such module is registered in vFlow. Do not retry this id; pick a real module id from the enum in the tool schema.",
                    )
                    return@forEachIndexed
                }
                if (!toolRegistry.isTemporaryWorkflowModuleAllowed(stepSpec.moduleId)) {
                    validationErrors += buildTemporaryWorkflowValidationError(
                        toolCall = preparedToolCall,
                        summary = definition.title,
                        outputText = "Temporary workflow step ${index + 1} uses module `${stepSpec.moduleId}`, which exists but cannot be used in a temporary workflow. " +
                            "Pick a different module id from the enum in the tool schema.",
                    )
                    return@forEachIndexed
                }
                val module = ModuleRegistry.getModule(stepSpec.moduleId)
                if (module == null) {
                    validationErrors += buildTemporaryWorkflowValidationError(
                        toolCall = preparedToolCall,
                        summary = definition.title,
                        outputText = "Temporary workflow step ${index + 1} uses module `${stepSpec.moduleId}`, which could not be loaded. " +
                            "Pick a different module id from the enum in the tool schema.",
                    )
                    return@forEachIndexed
                }
                val stepDefinition = toolRegistry.getToolForModuleId(stepSpec.moduleId)
                    ?: ChatAgentToolDefinition(
                        name = preparedToolCall.name,
                        title = stepSpec.moduleId,
                        description = stepSpec.moduleId,
                        moduleId = stepSpec.moduleId,
                        moduleDisplayName = stepSpec.moduleId,
                        routingHints = setOf(stepSpec.moduleId),
                        inputSchema = buildJsonObject { },
                        permissionNames = emptyList(),
                        riskLevel = toolRegistry.getRiskLevelForModuleId(stepSpec.moduleId),
                        usageScopes = setOf(ChatAgentToolUsageScope.TEMPORARY_WORKFLOW),
                    )
                when (val prepared = prepareModuleStep(
                    toolCall = preparedToolCall,
                    definition = stepDefinition,
                    module = module,
                    rawArgumentsJson = stepSpec.parameters.toString(),
                    artifactStore = artifactStore,
                    stepId = stepSpec.id,
                    indentationLevel = stepSpec.indentationLevel,
                )) {
                    is ChatPreparedToolItem.Ready -> readySteps += prepared
                    is ChatPreparedToolItem.NativeReady -> validationErrors += ChatToolResult(
                        callId = preparedToolCall.id,
                        name = preparedToolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = definition.title,
                        outputText = "Native helper tools cannot be embedded inside temporary workflows.",
                    )
                    is ChatPreparedToolItem.ImmediateResult -> validationErrors += prepared.result
                    is ChatPreparedToolItem.TemporaryWorkflow -> validationErrors += ChatToolResult(
                        callId = preparedToolCall.id,
                        name = preparedToolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = definition.title,
                        outputText = "Nested temporary workflows are not supported.",
                    )
                    is ChatPreparedToolItem.SaveWorkflow -> validationErrors += ChatToolResult(
                        callId = preparedToolCall.id,
                        name = preparedToolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = definition.title,
                        outputText = "Saving a workflow from inside a temporary workflow is not supported.",
                    )
                    is ChatPreparedToolItem.UpdateWorkflow -> validationErrors += ChatToolResult(
                        callId = preparedToolCall.id,
                        name = preparedToolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = definition.title,
                        outputText = "Updating a workflow from inside a temporary workflow is not supported.",
                    )
                }
            }
            val workflow = buildTemporaryWorkflow(spec, readySteps.map { it.step })
            val workflowMissingPermissions = PermissionManager.getMissingPermissions(appContext, workflow)
            ChatPreparedToolItem.TemporaryWorkflow(
                toolCall = toolCall,
                definition = definition,
                workflow = workflow,
                preparedSteps = readySteps,
                validationErrors = validationErrors,
                missingPermissions = (readySteps.flatMap { it.missingPermissions } + workflowMissingPermissions)
                    .distinctBy { it.id },
                riskLevel = ChatAgentToolRiskLevel.maxOf(readySteps.map { it.definition.riskLevel }),
            )
        } catch (throwable: Throwable) {
            ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = definition.title,
                    outputText = throwable.message?.ifBlank { null }
                        ?: "Failed to parse the temporary workflow.",
                )
            )
        }
    }

    private fun prepareSaveWorkflow(
        toolCall: ChatToolCall,
        artifactStore: ChatAgentArtifactStore,
    ): ChatPreparedToolItem {
        val definition = toolRegistry.getTool(toolCall.name)
            ?: return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = toolCall.name,
                    outputText = "Unknown saved workflow tool `${toolCall.name}`.",
                )
            )

        return try {
            val spec = parseSaveWorkflowSpec(toolCall)
            val validationErrors = mutableListOf<ChatToolResult>()
            val triggerCandidates = prepareSavedWorkflowSteps(
                toolCall = toolCall,
                definition = definition,
                stepSpecs = spec.triggers,
                expectTrigger = true,
                artifactStore = artifactStore,
                validationErrors = validationErrors,
            ).ifEmpty {
                listOf(createManualTriggerCandidate())
            }
            val stepCandidates = prepareSavedWorkflowSteps(
                toolCall = toolCall,
                definition = definition,
                stepSpecs = spec.steps,
                expectTrigger = false,
                artifactStore = artifactStore,
                validationErrors = validationErrors,
            )
            val allCandidates = triggerCandidates + stepCandidates
            val allSteps = allCandidates.map { it.step }
            allCandidates.forEach { candidate ->
                val validation = candidate.module.validate(candidate.step, allSteps)
                if (!validation.isValid) {
                    validationErrors += buildSaveWorkflowValidationError(
                        toolCall = toolCall,
                        summary = definition.title,
                        outputText = "${candidate.sourceLabel} `${candidate.step.moduleId}` is invalid: ${
                            validation.errorMessage ?: "Validation failed."
                        }",
                    )
                }
            }

            val workflow = Workflow(
                id = "chat_saved_${UUID.randomUUID()}",
                name = spec.name,
                triggers = triggerCandidates.map { it.step },
                steps = stepCandidates.map { it.step },
                isEnabled = spec.isEnabled,
                folderId = spec.folderId,
                description = spec.description,
                tags = spec.tags,
                maxExecutionTime = spec.maxExecutionTime,
                reentryBehavior = spec.reentryBehavior,
            )
            val missingPermissions = PermissionManager.getMissingPermissions(appContext, workflow)
            ChatPreparedToolItem.SaveWorkflow(
                toolCall = toolCall,
                definition = definition,
                workflow = workflow,
                validationErrors = validationErrors,
                missingPermissions = missingPermissions,
                riskLevel = riskLevelForSavedWorkflow(workflow),
            )
        } catch (throwable: Throwable) {
            ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = definition.title,
                    outputText = throwable.message?.ifBlank { null }
                        ?: "Failed to parse the saved workflow.",
                )
            )
        }
    }

    /**
     * 处理 `get_workflow`：读出一个已存工作流的完整详情。
     *
     * 输出的结构刻意与 `save_workflow` 的入参对齐，于是模型可以把这里的输出
     * 改一改直接喂给 `save_workflow`。但要注意「对齐」不等于「等价」——
     * `isDisabled` 只有 `update_workflow` 能写，`save_workflow` 的 schema 里没有它。
     */
    private fun prepareGetWorkflow(toolCall: ChatToolCall): ChatToolResult {
        val arguments = parseArguments(toolCall.argumentsJson)
        val workflowId = arguments["workflow_id"]?.toString()?.trim().orEmpty()

        val manager = WorkflowManager(appContext)
        val workflow = if (workflowId.isBlank()) null else {
            runCatching { manager.getWorkflow(workflowId) }.getOrNull()
        }
        if (workflow == null) {
            return workflowNotFoundResult(
                toolCall = toolCall,
                summary = "查看工作流详情",
                requestedId = workflowId,
                knownWorkflows = runCatching { manager.getAllWorkflows() }.getOrDefault(emptyList()),
            )
        }

        val foldersById = runCatching {
            FolderManager(appContext).getAllFolders().associateBy { it.id }
        }.getOrDefault(emptyMap())

        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.SUCCESS,
            summary = "查看工作流详情",
            outputText = buildGetWorkflowOutputText(workflow, foldersById),
        )
    }

    private fun buildGetWorkflowOutputText(
        workflow: Workflow,
        foldersById: Map<String, WorkflowFolder>,
    ): String {
        return buildString {
            appendLine("workflow: ${workflow.name}")
            appendLine("id: ${workflow.id}")
            appendLine("isFunction: ${workflow.isFunction}")
            workflow.folderId?.let { id ->
                appendLine("folder: ${foldersById[id]?.name ?: id}")
            }
            if (workflow.description.isNotBlank()) appendLine("description: ${workflow.description}")
            appendLine("isEnabled: ${workflow.isEnabled}")
            appendLine("reentryBehavior: ${workflow.reentryBehavior.storedValue}")
            workflow.maxExecutionTime?.let { appendLine("maxExecutionTime: $it") }
            if (workflow.tags.isNotEmpty()) appendLine("tags: ${workflow.tags.joinToString(" ") { "#$it" }}")
            appendLine("modifiedAt: ${workflow.modifiedAt}")

            appendLine()
            appendLine("triggers (${workflow.triggers.size}):")
            if (workflow.triggers.isEmpty()) {
                appendLine("- (none)")
            } else {
                workflow.triggers.forEach { appendLine(describeStepLine(it, workflow)) }
            }

            appendLine()
            appendLine("steps (${workflow.steps.size}):")
            if (workflow.steps.isEmpty()) {
                appendLine("- (none)")
            } else {
                workflow.steps.forEach { appendLine(describeStepLine(it, workflow)) }
            }

            workflow.functionSignature?.let { signature ->
                appendLine()
                appendLine("function signature:")
                if (signature.params.isEmpty()) {
                    appendLine("  (no parameters)")
                } else {
                    signature.params.forEach { param ->
                        val required = if (param.isRequired) " (required)" else ""
                        val default = param.defaultValue?.let { " default=$it" }.orEmpty()
                        appendLine("  - ${param.name}: ${param.type}$required$default")
                    }
                }
                signature.returnDef?.let { ret -> appendLine("  returns: ${ret.type}") }
            }

            appendLine()
            appendLine("read-only fields (no tool can change these):")
            appendLine("  ${READ_ONLY_WORKFLOW_FIELDS.joinToString(", ")}")
            appendLine()
            append("To change this workflow, call `$CHAT_UPDATE_WORKFLOW_TOOL_NAME` with this id. ")
            append("Step ids above are the addresses you pass as `step_id`.")
        }.trim()
    }

    /**
     * 一行的步骤描述。
     *
     * 三个标注是刻意的，缺一个模型就会误判：
     * - **`[disabled]`**：这是「停用一步而不删」的唯一手段，AI 此前完全看不到
     * - **jump 的目标步骤名**：`target_step_index: 1` 看不出这是个引用，标注 `(→ delay_1)` 才能
     * - **变量型 jump 的警告**：`{{vars.x}}` 算不出目标，标注出来避免模型以为它能改
     */
    private fun describeStepLine(step: ActionStep, workflow: Workflow): String {
        val parameters = step.parameters.entries
            .filter { it.value != null }
            .joinToString(", ") { (key, value) -> "$key: ${renderParameterValue(value)}" }
        val disabled = if (step.isDisabled) "  [disabled]" else ""

        val jumpNote = if (step.moduleId == JUMP_MODULE_ID) {
            val raw = step.parameters[TARGET_STEP_INDEX_PARAM]
            when {
                raw is Number && raw.toDouble() % 1.0 == 0.0 ->
                    workflow.steps.getOrNull(raw.toInt() - 1)?.let { " (→ ${it.id})" }.orEmpty()
                else ->
                    " (runtime variable target — cannot be recalculated statically)"
            }
        } else {
            ""
        }

        return "- ${step.id}  ${step.moduleId}  {$parameters}$jumpNote$disabled"
    }

    private fun renderParameterValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is String -> "\"$value\""
            is Map<*, *> -> value.entries.joinToString(", ", "{", "}") { (k, v) -> "$k: ${renderParameterValue(v)}" }
            is List<*> -> value.joinToString(", ", "[", "]") { renderParameterValue(it) }
            else -> value.toString()
        }
    }

    /**
     * 工作流 id 找不到时的**共用**错误构造。
     *
     * `get_workflow` 与 `update_workflow` 走同一个出口，避免两处各写一遍而口径漂移。
     *
     * ⚠️ **刻意不做「最近似 id」**：id 之间不存在近似关系（`chat_saved_550e8400-...`
     * 与 `wf_abc` 算不出相似度），按相似度猜等于用名字选目标。工作流允许重名，
     * 那会静默改错对象——而这是不可逆破坏。名字只用于**精确匹配**后的提示。
     */
    private fun workflowNotFoundResult(
        toolCall: ChatToolCall,
        summary: String,
        requestedId: String,
        knownWorkflows: List<Workflow>,
    ): ChatToolResult {
        val message = buildString {
            if (requestedId.isBlank()) {
                append("Missing `workflow_id`. ")
            } else {
                append("No workflow with id `$requestedId` exists. ")
                append("Do not retry the same id — it will keep failing. ")
                // 精确同名时告知其 id：模型常把名字当 id 传，这是可确定的纠正而非猜测。
                val sameName = knownWorkflows.filter { it.name == requestedId }
                if (sameName.isNotEmpty()) {
                    append("\n`$requestedId` is the NAME of ")
                    append(if (sameName.size == 1) "this workflow" else "${sameName.size} workflows")
                    append(": ")
                    append(sameName.joinToString("; ") { "`${it.id}` (${it.name})" })
                    append(". Pick one id — do not guess.")
                }
            }
            append("\n\nCall `$CHAT_LIST_WORKFLOWS_TOOL_NAME` to get real ids")
            append(" (it accepts a `query` to filter by name).")
        }
        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.ERROR,
            summary = summary,
            outputText = message,
        )
    }

    private fun prepareSavedWorkflowSteps(
        toolCall: ChatToolCall,
        definition: ChatAgentToolDefinition,
        stepSpecs: List<WorkflowStepSpec>,
        expectTrigger: Boolean,
        artifactStore: ChatAgentArtifactStore,
        validationErrors: MutableList<ChatToolResult>,
    ): List<SavedWorkflowStepCandidate> {
        return stepSpecs.mapIndexedNotNull { index, stepSpec ->
            val sourceLabel = if (expectTrigger) "trigger ${index + 1}" else "step ${index + 1}"
            // 先判「模块是否存在」，再判「是否允许用于保存工作流」。
            // 顺序很重要：不存在 moduleId 时若先走白名单检查，会被报成「未暴露给 Agent」，
            // 让模型误以为模块存在、只是权限问题，从而反复重试同一个无效 id。
            if (!toolRegistry.isRegisteredModule(stepSpec.moduleId)) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel uses module `${stepSpec.moduleId}`, which does not exist. " +
                        "No such module is registered in vFlow. Do not retry this id; pick a real module id from the enum in the tool schema.",
                )
                return@mapIndexedNotNull null
            }
            if (!toolRegistry.isSavedWorkflowModuleAllowed(stepSpec.moduleId)) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel uses module `${stepSpec.moduleId}`, which exists but is not available for saved workflows. " +
                        "Pick a different module id from the enum in the tool schema.",
                )
                return@mapIndexedNotNull null
            }
            val module = ModuleRegistry.getModule(stepSpec.moduleId)
            if (module == null) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel uses module `${stepSpec.moduleId}`, which could not be loaded. " +
                        "Pick a different module id from the enum in the tool schema.",
                )
                return@mapIndexedNotNull null
            }
            val isTrigger = toolRegistry.isTriggerModule(stepSpec.moduleId)
            if (expectTrigger && !isTrigger) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel must be a trigger module, but `${stepSpec.moduleId}` is not a trigger.",
                )
                return@mapIndexedNotNull null
            }
            if (!expectTrigger && isTrigger) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel uses trigger module `${stepSpec.moduleId}` in workflow.steps. Put triggers in workflow.triggers instead.",
                )
                return@mapIndexedNotNull null
            }
            if (containsArtifactHandle(stepSpec.parameters)) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel contains an artifact:// handle. Chat artifacts are temporary and cannot be saved into reusable workflows.",
                )
                return@mapIndexedNotNull null
            }

            try {
                val built = buildParameters(module, stepSpec.parameters.toString(), artifactStore)
                if (built.rejectedKeys.isNotEmpty()) {
                    validationErrors += buildSaveWorkflowValidationError(
                        toolCall = toolCall,
                        summary = definition.title,
                        outputText = "Saved workflow $sourceLabel step `${stepSpec.moduleId}` has unknown parameter(s): " +
                            "${built.rejectedKeys.joinToString(", ")}. " +
                            "Available parameters: ${built.availableKeys.joinToString(", ").ifBlank { "none" }}.",
                    )
                    return@mapIndexedNotNull null
                }
                SavedWorkflowStepCandidate(
                    module = module,
                    step = ActionStep(
                        moduleId = module.id,
                        parameters = built.parameters,
                        indentationLevel = stepSpec.indentationLevel,
                        id = stepSpec.id,
                    ),
                    sourceLabel = sourceLabel,
                )
            } catch (throwable: Throwable) {
                validationErrors += buildSaveWorkflowValidationError(
                    toolCall = toolCall,
                    summary = definition.title,
                    outputText = "Saved workflow $sourceLabel could not parse `${stepSpec.moduleId}` parameters: ${
                        throwable.message?.ifBlank { null } ?: "Invalid arguments."
                    }",
                )
                null
            }
        }
    }

    private fun createManualTriggerCandidate(): SavedWorkflowStepCandidate {
        val moduleId = "vflow.trigger.manual"
        val module = ModuleRegistry.getModule(moduleId)
            ?: throw IllegalStateException("Manual trigger module is not registered.")
        return SavedWorkflowStepCandidate(
            module = module,
            step = ActionStep(
                moduleId = moduleId,
                parameters = emptyMap(),
                id = "manual_trigger",
            ),
            sourceLabel = "trigger 1",
        )
    }

    private fun parseSaveWorkflowSpec(toolCall: ChatToolCall): SaveWorkflowSpec {
        val root = json.parseToJsonElement(toolCall.argumentsJson) as? JsonObject
            ?: throw IllegalArgumentException("Saved workflow arguments must be a JSON object.")
        val workflow = root["workflow"] as? JsonObject ?: root
        val steps = workflow["steps"] as? JsonArray
            ?: throw IllegalArgumentException("Saved workflow requires a `steps` array.")
        val triggers = workflow["triggers"] as? JsonArray

        val parsedSteps = parseWorkflowStepSpecs(
            source = "Saved workflow step",
            array = steps,
            maxItems = MAX_SAVED_WORKFLOW_STEPS,
        )
        if (parsedSteps.isEmpty()) {
            throw IllegalArgumentException("Saved workflow must include at least one action step.")
        }

        return SaveWorkflowSpec(
            name = workflow["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: throw IllegalArgumentException("Saved workflow requires a non-empty `name`."),
            description = workflow["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            isEnabled = workflow["isEnabled"]?.jsonPrimitive?.booleanOrNull ?: true,
            folderId = workflow["folderId"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() },
            tags = parseStringArray(workflow["tags"] as? JsonArray),
            maxExecutionTime = workflow["maxExecutionTime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceIn(1, MAX_SAVED_WORKFLOW_MAX_SECONDS),
            reentryBehavior = WorkflowReentryBehavior.fromStoredValue(
                workflow["reentryBehavior"]?.jsonPrimitive?.contentOrNull
            ),
            triggers = triggers?.let {
                parseWorkflowStepSpecs(
                    source = "Saved workflow trigger",
                    array = it,
                    maxItems = MAX_SAVED_WORKFLOW_TRIGGERS,
                )
            }.orEmpty(),
            steps = parsedSteps,
        )
    }

    private fun parseWorkflowStepSpecs(
        source: String,
        array: JsonArray,
        maxItems: Int,
    ): List<WorkflowStepSpec> {
        if (array.size > maxItems) {
            throw IllegalArgumentException("$source list is too long: ${array.size}, max is $maxItems.")
        }
        return array.mapIndexed { index, stepElement ->
            val step = stepElement as? JsonObject
                ?: throw IllegalArgumentException("$source ${index + 1} must be an object.")
            val moduleId = step["moduleId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            if (moduleId.isBlank()) {
                throw IllegalArgumentException("$source ${index + 1} is missing `moduleId`.")
            }
            val parameters = step["parameters"] as? JsonObject
                ?: buildJsonObject { }
            val stepId = step["id"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "step_${index + 1}"
            val indentationLevel = step["indentationLevel"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 0
            WorkflowStepSpec(
                id = stepId,
                moduleId = moduleId,
                parameters = parameters,
                indentationLevel = indentationLevel.coerceIn(0, MAX_WORKFLOW_INDENTATION_LEVEL),
            )
        }
    }

    private fun parseStringArray(array: JsonArray?): List<String> {
        return array?.toList().orEmpty()
            .mapNotNull { element -> element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() } }
            .distinct()
            .take(MAX_SAVED_WORKFLOW_TAGS)
    }

    private fun containsArtifactHandle(element: JsonElement): Boolean {
        return when (element) {
            is JsonPrimitive -> element.contentOrNull?.startsWith("artifact://") == true
            is JsonArray -> element.any(::containsArtifactHandle)
            is JsonObject -> element.values.any(::containsArtifactHandle)
            JsonNull -> false
        }
    }

    private fun riskLevelForSavedWorkflow(workflow: Workflow): ChatAgentToolRiskLevel {
        val moduleLevels = workflow.steps.map { toolRegistry.getRiskLevelForModuleId(it.moduleId) }
        val triggerLevels = workflow.triggers.map { trigger ->
            if (trigger.moduleId == "vflow.trigger.manual") {
                ChatAgentToolRiskLevel.LOW
            } else {
                ChatAgentToolRiskLevel.HIGH
            }
        }
        return ChatAgentToolRiskLevel.maxOf(
            moduleLevels + triggerLevels + ChatAgentToolRiskLevel.STANDARD
        )
    }

    private fun parseTemporaryWorkflowSpec(toolCall: ChatToolCall): TemporaryWorkflowSpec {
        val root = json.parseToJsonElement(toolCall.argumentsJson) as? JsonObject
            ?: throw IllegalArgumentException("Temporary workflow arguments must be a JSON object.")
        val workflow = root["workflow"] as? JsonObject ?: root
        val steps = workflow["steps"] as? JsonArray
            ?: throw IllegalArgumentException("Temporary workflow requires a `steps` array.")
        val parsedSteps = mutableListOf<WorkflowStepSpec>()

        steps.forEachIndexed { stepIndex, stepElement ->
            val step = stepElement as? JsonObject
                ?: throw IllegalArgumentException("Temporary workflow step ${stepIndex + 1} must be an object.")
            val moduleId = step["moduleId"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: step["module_id"]?.jsonPrimitive?.contentOrNull?.trim()
                ?: step["tool"]?.jsonPrimitive?.contentOrNull?.trim()
                    ?.let { toolName -> toolRegistry.getTool(toolName)?.moduleId }
                ?: ""
            if (moduleId.isBlank()) {
                throw IllegalArgumentException("Temporary workflow step ${stepIndex + 1} is missing `moduleId`.")
            }
            if (moduleId == CHAT_TEMPORARY_WORKFLOW_MODULE_ID || step["tool"]?.jsonPrimitive?.contentOrNull == CHAT_TEMPORARY_WORKFLOW_TOOL_NAME) {
                throw IllegalArgumentException("Nested temporary workflows are not supported.")
            }
            val parameters = step["parameters"] as? JsonObject
                ?: step["arguments"] as? JsonObject
                ?: buildJsonObject { }
            val baseStepId = step["id"]?.jsonPrimitive?.contentOrNull
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "step_${stepIndex + 1}"
            val indentationLevel = step["indentationLevel"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: step["indentation_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?: 0
            val repeat = step["repeat"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceIn(1, MAX_TEMPORARY_WORKFLOW_REPEAT) ?: 1
            val delayMsAfter = step["delay_ms_after"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()
                ?.coerceIn(0L, MAX_TEMPORARY_WORKFLOW_DELAY_MS) ?: 0L

            repeat(repeat) { repeatIndex ->
                parsedSteps += WorkflowStepSpec(
                    id = if (repeat == 1) baseStepId else "${baseStepId}_${repeatIndex + 1}",
                    moduleId = moduleId,
                    parameters = parameters,
                    indentationLevel = indentationLevel.coerceIn(0, 8),
                )
                if (delayMsAfter > 0 && repeatIndex < repeat - 1) {
                    parsedSteps += WorkflowStepSpec(
                        id = "${baseStepId}_${repeatIndex + 1}_delay",
                        moduleId = "vflow.device.delay",
                        parameters = buildJsonObject {
                            put("duration", delayMsAfter)
                        },
                        indentationLevel = indentationLevel.coerceIn(0, 8),
                    )
                }
            }
        }

        if (parsedSteps.isEmpty()) {
            throw IllegalArgumentException("Temporary workflow must include at least one executable step.")
        }
        if (parsedSteps.size > MAX_TEMPORARY_WORKFLOW_EXPANDED_STEPS) {
            throw IllegalArgumentException("Temporary workflow is too long: ${parsedSteps.size} expanded steps, max is $MAX_TEMPORARY_WORKFLOW_EXPANDED_STEPS.")
        }
        return TemporaryWorkflowSpec(
            name = workflow["name"]?.jsonPrimitive?.contentOrNull?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: "聊天临时工作流",
            description = workflow["description"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty(),
            maxExecutionTime = workflow["maxExecutionTime"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
                ?.coerceIn(1, MAX_TEMPORARY_WORKFLOW_MAX_SECONDS)
                ?: DEFAULT_TEMPORARY_WORKFLOW_MAX_SECONDS,
            steps = parsedSteps,
        )
    }

    private fun buildTemporaryWorkflow(
        spec: TemporaryWorkflowSpec,
        steps: List<ActionStep>,
    ): Workflow {
        return Workflow(
            id = "chat_temp_${UUID.randomUUID()}",
            name = spec.name,
            triggers = listOf(
                ActionStep(
                    moduleId = "vflow.trigger.manual",
                    parameters = emptyMap(),
                    id = "chat_manual_trigger",
                )
            ),
            steps = steps,
            isEnabled = true,
            description = spec.description,
            maxExecutionTime = spec.maxExecutionTime,
            reentryBehavior = WorkflowReentryBehavior.STOP_CURRENT_AND_RUN_NEW,
        )
    }

    private fun buildTemporaryWorkflowValidationError(
        toolCall: ChatToolCall,
        summary: String,
        outputText: String,
    ): ChatToolResult {
        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.ERROR,
            summary = summary,
            outputText = outputText,
        )
    }

    private fun buildSaveWorkflowValidationError(
        toolCall: ChatToolCall,
        summary: String,
        outputText: String,
    ): ChatToolResult {
        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.ERROR,
            summary = summary,
            outputText = outputText,
        )
    }

    private fun executeSaveWorkflow(workflow: ChatPreparedToolItem.SaveWorkflow): ChatToolResult {
        if (workflow.validationErrors.isNotEmpty()) {
            return ChatToolResult(
                callId = workflow.toolCall.id,
                name = workflow.toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = workflow.definition.title,
                outputText = buildString {
                    append("Saved workflow `${workflow.workflow.name}` was not saved because some parts are invalid.\n")
                    workflow.validationErrors.take(10).forEachIndexed { index, result ->
                        append("${index + 1}. ")
                        append(result.outputText)
                        append("\n")
                    }
                }.trim(),
            )
        }

        return try {
            WorkflowManager(appContext).saveWorkflow(workflow.workflow)
            ChatToolResult(
                callId = workflow.toolCall.id,
                name = workflow.toolCall.name,
                status = ChatToolResultStatus.SUCCESS,
                summary = workflow.definition.title,
                outputText = buildSavedWorkflowResultText(workflow),
            )
        } catch (throwable: Throwable) {
            ChatToolResult(
                callId = workflow.toolCall.id,
                name = workflow.toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = workflow.definition.title,
                outputText = buildString {
                    append("Saved workflow `${workflow.workflow.name}` failed to save.")
                    throwable.message?.takeIf { it.isNotBlank() }?.let {
                        append("\n")
                        append(it)
                    }
                }.trim(),
            )
        }
    }

    /**
     * 处理 `update_workflow`：把操作原语补丁应用到已有工作流上。
     *
     * 流水线（顺序固定，见 `docs/fork/workflow-read-write-tools.md` §4.3.3）：
     * 解析 → 定位/键校验 → update → insert → delete → move → jump 重映射
     * → folderId 校验 → validate → 权限 → 风险等级。
     *
     * 结构校验（块配对）按决策**降级到 P2 且只探测不拦截**，这里不拦。
     */
    private fun prepareUpdateWorkflow(
        toolCall: ChatToolCall,
        artifactStore: ChatAgentArtifactStore,
    ): ChatPreparedToolItem {
        val definition = toolRegistry.getTool(toolCall.name)
            ?: return ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = toolCall.name,
                    outputText = "Unknown update workflow tool `${toolCall.name}`.",
                )
            )

        val manager = WorkflowManager(appContext)
        return try {
            val root = json.parseToJsonElement(toolCall.argumentsJson) as? JsonObject
                ?: throw IllegalArgumentException("Update workflow arguments must be a JSON object.")
            val workflowId = root["workflow_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val existing = if (workflowId.isBlank()) null else {
                runCatching { manager.getWorkflow(workflowId) }.getOrNull()
            }
            if (existing == null) {
                return ChatPreparedToolItem.ImmediateResult(
                    toolCall = toolCall,
                    result = workflowNotFoundResult(
                        toolCall = toolCall,
                        summary = definition.title,
                        requestedId = workflowId,
                        knownWorkflows = runCatching { manager.getAllWorkflows() }.getOrDefault(emptyList()),
                    ),
                )
            }

            val errors = mutableListOf<ChatToolResult>()
            val warnings = mutableListOf<String>()

            // ── metadata：按 key 合并，null = 删键（只读字段不在 schema 里，改不到）──
            val metadataPatch = root["metadata"] as? JsonObject
            val newMetadata = applyMetadataPatch(existing, metadataPatch, errors, definition, toolCall)

            // ── folderId 存在性：未知 id 会让工作流从列表上消失 ──
            newMetadata["folderId"]?.let { rawFolder ->
                val folderId = rawFolder.toString()
                val folderExists = runCatching {
                    FolderManager(appContext).getAllFolders().any { it.id == folderId }
                }.getOrDefault(false)
                if (!folderExists) {
                    val known = runCatching {
                        FolderManager(appContext).getAllFolders().joinToString(", ") { "${it.name}(${it.id})" }
                    }.getOrDefault("")
                    errors += updateValidationError(
                        toolCall, definition.title,
                        "`folderId: $folderId` is not an existing folder, so the workflow would " +
                            "disappear from the list. Pass a folder **id**, not its name. " +
                            "Existing folders: ${known.ifBlank { "none" }}."
                    )
                }
            }

            // ── triggers：update/insert/delete（无 move，顺序无语义）──
            val triggerPatch = root["triggers"] as? JsonObject
            val patchResult = applyStepPatch(
                toolCall = toolCall,
                definition = definition,
                artifactStore = artifactStore,
                expectTrigger = true,
                original = existing.triggers,
                patchJson = triggerPatch,
                errors = errors,
                warnings = warnings,
            )

            // ── steps：update/insert/delete/move ──
            val stepsPatch = root["steps"] as? JsonObject
            val stepsResult = applyStepPatch(
                toolCall = toolCall,
                definition = definition,
                artifactStore = artifactStore,
                expectTrigger = false,
                original = existing.steps,
                patchJson = stepsPatch,
                errors = errors,
                warnings = warnings,
            )

            val newTriggers = patchResult ?: existing.triggers
            val newSteps = stepsResult ?: existing.steps

            val updated = existing.copy(
                name = newMetadata["name"] as? String ?: existing.name,
                description = newMetadata["description"] as? String ?: existing.description,
                isEnabled = newMetadata["isEnabled"] as? Boolean ?: existing.isEnabled,
                folderId = when {
                    newMetadata.containsKey("folderId") -> newMetadata["folderId"] as? String
                    else -> existing.folderId
                },
                tags = (newMetadata["tags"] as? List<*>)?.mapNotNull { it?.toString() } ?: existing.tags,
                maxExecutionTime = when {
                    newMetadata.containsKey("maxExecutionTime") ->
                        (newMetadata["maxExecutionTime"] as? Number)?.toInt()
                    else -> existing.maxExecutionTime
                },
                reentryBehavior = (newMetadata["reentryBehavior"] as? String)
                    ?.let(WorkflowReentryBehavior::fromStoredValue)
                    ?: existing.reentryBehavior,
                triggers = newTriggers.ifEmpty { listOf(createManualTriggerCandidate().step) },
                steps = newSteps,
            )

            if (updated.steps.isEmpty()) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "The workflow must keep at least one action step."
                )
            }

            // ── 逐模块 validate：**只覆盖本次新增/修改的步骤** ──
            // 存量工作流可能含历史上就不合规模块的步骤，全量校验会让它彻底不可编辑。
            val touchedIds = collectTouchedStepIds(root)
            val allSteps = updated.triggers + updated.steps
            allSteps.filter { it.id in touchedIds }.forEach { step ->
                val module = ModuleRegistry.getModule(step.moduleId) ?: return@forEach
                val validation = module.validate(step, allSteps)
                if (!validation.isValid) {
                    errors += updateValidationError(
                        toolCall, definition.title,
                        "Step `${step.id}` (${step.moduleId}) is invalid: " +
                            (validation.errorMessage ?: "Validation failed.")
                    )
                }
            }

            val missingPermissions = PermissionManager.getMissingPermissions(appContext, updated)
            ChatPreparedToolItem.UpdateWorkflow(
                toolCall = toolCall,
                definition = definition,
                workflowId = existing.id,
                workflow = updated,
                warnings = warnings,
                validationErrors = errors,
                missingPermissions = missingPermissions,
                riskLevel = riskLevelForSavedWorkflow(updated),
            )
        } catch (throwable: Throwable) {
            ChatPreparedToolItem.ImmediateResult(
                toolCall = toolCall,
                result = ChatToolResult(
                    callId = toolCall.id,
                    name = toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = definition.title,
                    outputText = throwable.message?.ifBlank { null }
                        ?: "Failed to parse the workflow patch.",
                )
            )
        }
    }

    /**
     * `metadata` 补丁 → 落地值表。
     *
     * 规则：**只改传了的键；传 `null` = 删键**（与 `steps.update` 的 parameters 同语义）。
     * 返回值里出现的键就是「本次要改的」，没出现的一律保持原值——这是「不丢字段」的保证。
     */
    private fun applyMetadataPatch(
        existing: Workflow,
        patch: JsonObject?,
        errors: MutableList<ChatToolResult>,
        definition: ChatAgentToolDefinition,
        toolCall: ChatToolCall,
    ): Map<String, Any?> {
        if (patch == null) return emptyMap()
        val result = LinkedHashMap<String, Any?>()
        patch.forEach { (key, element) ->
            when (key) {
                "name", "description", "folderId", "reentryBehavior" ->
                    result[key] = element.jsonPrimitive.contentOrNull
                "isEnabled" ->
                    result[key] = element.jsonPrimitive.booleanOrNull
                "maxExecutionTime" ->
                    result[key] = element.jsonPrimitive.contentOrNull?.toIntOrNull()
                "tags" ->
                    result[key] = (element as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }
                else ->
                    errors += updateValidationError(
                        toolCall, definition.title,
                        "`metadata.$key` is not a changeable field. Changeable: " +
                            "name, description, isEnabled, folderId, tags, maxExecutionTime, reentryBehavior."
                    )
            }
        }
        return result
    }

    /**
     * 一个 `steps` / `triggers` 补丁块 → 新的步骤列表。
     *
     * 先按模块把新参数求值出来（复用 [buildParameters]，口径与 `save_workflow` 一致），
     * 再交给纯函数层的 [applyStepListPatch] 做列表手术。
     *
     * @return 新列表；失败返回 `null`（错误已写进 [errors]）
     */
    private fun applyStepPatch(
        toolCall: ChatToolCall,
        definition: ChatAgentToolDefinition,
        artifactStore: ChatAgentArtifactStore,
        expectTrigger: Boolean,
        original: List<ActionStep>,
        patchJson: JsonObject?,
        errors: MutableList<ChatToolResult>,
        warnings: MutableList<String>,
    ): List<ActionStep>? {
        if (patchJson == null) return null
        val label = if (expectTrigger) "triggers" else "steps"
        val originalIds = original.map { it.id }.toSet()
        val rebuilt = mutableMapOf<String, ActionStep>()
        val insertions = mutableListOf<StepInsertion>()
        val deletions = mutableSetOf<String>()
        val moves = mutableListOf<StepMove>()

        // ── update：按 key 合并参数 ──
        (patchJson["update"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val stepId = obj["step_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val target = original.firstOrNull { it.id == stepId }
            if (target == null) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "`$label.update` targets step id `$stepId`, which does not exist. " +
                        "Existing ids: ${originalIds.joinToString(", ").ifBlank { "none" }}."
                )
                return@forEach
            }
            val module = ModuleRegistry.getModule(target.moduleId)
            if (module == null) {
                errors += updateValidationError(
                    toolCall, definition.title, "Step `$stepId` uses unregistered module `${target.moduleId}`."
                )
                return@forEach
            }

            // is_disabled：块成员必须拒绝（执行器对 BLOCK_END 的禁用只跳一步，
            // 会让循环静默只跑一遍——静态结构校验抓不到，见 §3.3.4）
            val disabled = obj["is_disabled"]?.jsonPrimitive?.booleanOrNull
            if (disabled != null && WorkflowPatch.isBlockMember(target.moduleId)) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "`is_disabled` cannot be applied to `${target.moduleId}` (`$stepId`) because it is a " +
                        "block member (If/Loop/While/ForEach/DoWhile/menu/UI block part). " +
                        "Disabling part of a block desynchronises its pairing at runtime. " +
                        "To remove the whole block, delete all of its members in the same patch."
                )
                return@forEach
            }

            // 参数：**以现有参数为 base**，而非模块默认值——否则未提及的参数会被重置成默认。
            val mergedParameters = applyParameterPatch(
                toolCall = toolCall,
                definition = definition,
                module = module,
                base = target.parameters,
                patch = obj["parameters"] as? JsonObject,
                artifactStore = artifactStore,
                stepLabel = stepId,
                errors = errors,
            ) ?: return@forEach

            rebuilt[stepId] = target.copy(
                parameters = mergedParameters,
                isDisabled = disabled ?: target.isDisabled,
            )
        }

        // ── insert ──
        (patchJson["insert"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val stepId = obj["id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val moduleId = obj["moduleId"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val module = ModuleRegistry.getModule(moduleId)
            if (module == null) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "Inserted step `$stepId` uses module `$moduleId`, which is not registered."
                )
                return@forEach
            }
            val isTrigger = toolRegistry.isTriggerModule(moduleId)
            if (expectTrigger && !isTrigger) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "`triggers.insert` step `$stepId` must be a trigger module, but `$moduleId` is not."
                )
                return@forEach
            }
            if (!expectTrigger && isTrigger) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "`steps.insert` step `$stepId` uses trigger module `$moduleId`. Put triggers in `triggers`."
                )
                return@forEach
            }
            if (!toolRegistry.isSavedWorkflowModuleAllowed(moduleId)) {
                errors += updateValidationError(
                    toolCall, definition.title,
                    "Module `$moduleId` cannot appear in a saved workflow."
                )
                return@forEach
            }
            val parameters = applyParameterPatch(
                toolCall = toolCall,
                definition = definition,
                module = module,
                base = emptyMap(),
                patch = obj["parameters"] as? JsonObject,
                artifactStore = artifactStore,
                stepLabel = stepId,
                errors = errors,
            ) ?: return@forEach

            insertions += StepInsertion(
                step = ActionStep(
                    moduleId = moduleId,
                    parameters = parameters,
                    indentationLevel = obj["indentationLevel"]?.jsonPrimitive?.contentOrNull
                        ?.toIntOrNull()?.coerceIn(0, MAX_WORKFLOW_INDENTATION_LEVEL) ?: 0,
                    id = stepId,
                ),
                afterStepId = obj["after_step_id"]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotBlank() },
                atIndex = obj["at_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull(),
            )
        }

        // ── delete ──
        (patchJson["delete"] as? JsonArray)?.forEach { element ->
            element.jsonPrimitive.contentOrNull?.trim()?.takeIf { it.isNotBlank() }?.let(deletions::add)
        }

        // ── move（仅 steps）──
        (patchJson["move"] as? JsonArray)?.forEach { element ->
            val obj = element as? JsonObject ?: return@forEach
            val stepId = obj["step_id"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
            val toIndex = obj["to_index"]?.jsonPrimitive?.contentOrNull?.toIntOrNull()
            if (toIndex == null) {
                errors += updateValidationError(toolCall, definition.title, "`move` for `$stepId` needs `to_index`.")
                return@forEach
            }
            moves += StepMove(stepId = stepId, toIndex = toIndex)
        }

        if (rebuilt.isEmpty() && insertions.isEmpty() && deletions.isEmpty() && moves.isEmpty()) {
            return null
        }

        return when (
            val outcome = applyStepListPatch(
                original = original,
                patch = StepListPatch(
                    rebuilt = rebuilt,
                    insertions = insertions,
                    deletions = deletions,
                    moves = moves,
                ),
            )
        ) {
            is StepPatchOutcome.Applied -> {
                warnings += outcome.warnings
                outcome.steps
            }
            is StepPatchOutcome.Rejected -> {
                outcome.errors.forEach { message ->
                    errors += updateValidationError(toolCall, definition.title, message)
                }
                null
            }
        }
    }

    /**
     * 参数补丁：**以 [base] 为基准按 key 合并**。
     *
     * 关键差异（与 `save_workflow` 的 [buildParameters]）：那里以**模块默认值**为 base，
     * 因为它在造一个新步骤；这里必须以上**步骤的现有参数**为 base，
     * 否则模型只想改 `duration`，其余参数会被静默重置成默认值。
     *
     * 未知键**必须报错**——静默丢弃会让模型以为改好了（病症 B）。
     */
    private fun applyParameterPatch(
        toolCall: ChatToolCall,
        definition: ChatAgentToolDefinition,
        module: ActionModule,
        base: Map<String, Any?>,
        patch: JsonObject?,
        artifactStore: ChatAgentArtifactStore,
        stepLabel: String,
        errors: MutableList<ChatToolResult>,
    ): Map<String, Any?>? {
        if (patch == null) return base

        // 求值口径与执行校验同源（静态全集 ∪ 动态结果），并把 base 当作「已填好的 step」，
        // 好让 CallFunctionModule 这类依据 step 现有值生成字段的模块也能吐出参数。
        val baseStep = ActionStep(moduleId = module.id, parameters = base)
        val definitionsById = resolveModuleInputDefinitions(module, baseStep).associateBy { it.id }

        val unknown = patch.keys.filterNot(definitionsById::containsKey)
        if (unknown.isNotEmpty()) {
            errors += updateValidationError(
                toolCall, definition.title,
                "Step `$stepLabel` (`${module.id}`) got unknown parameter(s): ${unknown.joinToString(", ")}. " +
                    "Available: ${definitionsById.keys.joinToString(", ").ifBlank { "none" }}."
            )
            return null
        }

        val accepted = linkedMapOf<String, Any?>()
        patch.forEach { (key, element) ->
            // null = 删键（不是写 null 值——两者在执行期对 isRequired 校验不等价）
            if (element is JsonNull) {
                accepted[key] = null
                return@forEach
            }
            val input = definitionsById[key] ?: return@forEach
            accepted[key] = coerceInputValue(input, element, artifactStore)
        }

        val merged = WorkflowPatch.mergeParameters(base, accepted)
        return (module as? AiParameterNormalizer)?.normalizeAiParameters(merged) ?: merged
    }

    /** 收集本次补丁触及的 step id——`validate` 只跑这些，避免卡住存量工作流。 */
    private fun collectTouchedStepIds(root: JsonObject): Set<String> {
        val touched = mutableSetOf<String>()
        listOf("triggers", "steps").forEach { section ->
            val block = root[section] as? JsonObject ?: return@forEach
            (block["update"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.get("step_id")?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotBlank() }?.let(touched::add)
            }
            (block["insert"] as? JsonArray)?.forEach { element ->
                (element as? JsonObject)?.get("id")?.jsonPrimitive?.contentOrNull
                    ?.trim()?.takeIf { it.isNotBlank() }?.let(touched::add)
            }
        }
        return touched
    }

    private fun updateValidationError(
        toolCall: ChatToolCall,
        summary: String,
        message: String,
    ): ChatToolResult {
        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.ERROR,
            summary = summary,
            outputText = message,
        )
    }

    private fun executeUpdateWorkflow(item: ChatPreparedToolItem.UpdateWorkflow): ChatToolResult {
        if (item.validationErrors.isNotEmpty()) {
            return ChatToolResult(
                callId = item.toolCall.id,
                name = item.toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = item.definition.title,
                outputText = buildString {
                    append("Workflow `${item.workflow.name}` was NOT modified — the patch has problems. ")
                    append("Nothing was written.\n")
                    item.validationErrors.take(10).forEachIndexed { index, error ->
                        append("${index + 1}. ").append(error.outputText).append("\n")
                    }
                }.trim(),
            )
        }

        val manager = WorkflowManager(appContext)
        // 落盘前留一份原件：saveWorkflow 是覆盖式整表写，异常时会留下半写状态，
        // 而本项目没有任何版本历史或回收站——不写回就是永久损坏。
        val original = runCatching { manager.getWorkflow(item.workflowId) }.getOrNull()

        return try {
            manager.saveWorkflow(item.workflow)
            ChatToolResult(
                callId = item.toolCall.id,
                name = item.toolCall.name,
                status = ChatToolResultStatus.SUCCESS,
                summary = item.definition.title,
                outputText = buildUpdatedWorkflowResultText(item),
            )
        } catch (throwable: Throwable) {
            val restored = original != null && runCatching { manager.saveWorkflow(original) }.isSuccess
            ChatToolResult(
                callId = item.toolCall.id,
                name = item.toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = item.definition.title,
                outputText = buildString {
                    append("Workflow `${item.workflow.name}` failed to save.")
                    throwable.message?.takeIf { it.isNotBlank() }?.let { append("\n").append(it) }
                    append(
                        if (restored) "\n\nThe previous version was restored."
                        else "\n\n⚠️ The previous version could NOT be restored — check the workflow manually."
                    )
                }.trim(),
            )
        }
    }

    private fun buildUpdatedWorkflowResultText(item: ChatPreparedToolItem.UpdateWorkflow): String {
        return buildString {
            append("Workflow `${item.workflow.name}` was updated.")
            append("\n\nID: ").append(item.workflow.id)
            append("\nTriggers: ").append(item.workflow.triggers.size)
            append("\nSteps: ").append(item.workflow.steps.size)
            append("\nRisk level: ").append(item.riskLevel.name.lowercase())

            if (item.warnings.isNotEmpty()) {
                append("\n\nWarnings:\n")
                item.warnings.forEach { append("- ").append(it).append("\n") }
            }
            if (item.missingPermissions.isNotEmpty()) {
                append("\nMissing permissions before execution:\n")
                item.missingPermissions.forEach { append("- ").append(it.getLocalizedName(appContext)).append("\n") }
            }
            append("\nCall `").append(CHAT_GET_WORKFLOW_TOOL_NAME)
                .append("` again to confirm the result, or `").append(CHAT_UPDATE_WORKFLOW_TOOL_NAME)
                .append("` to apply another patch.")
        }.trim()
    }

    private fun buildSavedWorkflowResultText(workflow: ChatPreparedToolItem.SaveWorkflow): String {
        return buildString {
            append("Workflow `${workflow.workflow.name}` was saved successfully.")
            append("\n\nID: ")
            append(workflow.workflow.id)
            append("\nTriggers: ")
            append(workflow.workflow.triggers.size)
            append(if (workflow.workflow.hasAutoTriggers()) " (includes auto triggers)" else " (manual)")
            append("\nSteps: ")
            append(workflow.workflow.steps.size)
            append("\nRisk level: ")
            append(workflow.riskLevel.name.lowercase())

            if (workflow.missingPermissions.isNotEmpty()) {
                append("\n\nMissing permissions before execution:\n")
                workflow.missingPermissions
                    .map { it.getLocalizedName(appContext) }
                    .distinct()
                    .forEach { permissionName ->
                        append("- ")
                        append(permissionName)
                        append("\n")
                    }
            }

            append("\n\nWorkflow outline:\n")
            workflow.workflow.triggers.take(8).forEach { trigger ->
                append("- Trigger: ")
                append(trigger.moduleId)
                append("\n")
            }
            workflow.workflow.steps.take(30).forEachIndexed { index, step ->
                append("- ")
                append(index + 1)
                append(". ")
                append(step.moduleId)
                append("\n")
            }
            if (workflow.workflow.steps.size > 30) {
                append("- ... ")
                append(workflow.workflow.steps.size - 30)
                append(" more steps\n")
            }
        }.trim()
    }

    private suspend fun executeTemporaryWorkflow(
        workflow: ChatPreparedToolItem.TemporaryWorkflow,
        artifactStore: ChatAgentArtifactStore,
    ): ChatToolResult {
        if (workflow.validationErrors.isNotEmpty()) {
            return ChatToolResult(
                callId = workflow.toolCall.id,
                name = workflow.toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = workflow.definition.title,
                outputText = buildString {
                    append("Temporary workflow could not start because some steps are invalid.\n")
                    workflow.validationErrors.take(8).forEachIndexed { index, result ->
                        append("${index + 1}. ")
                        append(result.summary)
                        append(": ")
                        append(result.outputText)
                        append("\n")
                    }
                }.trim(),
            )
        }

        return coroutineScope {
            val terminalState = async {
                withTimeout((workflow.workflow.maxExecutionTime ?: DEFAULT_TEMPORARY_WORKFLOW_MAX_SECONDS) * 1000L + 5_000L) {
                    ExecutionStateBus.stateFlow.first { state ->
                        state.workflowId == workflow.workflow.id && state.isTerminalExecutionState()
                    }
                }
            }
            val executionInstanceId = WorkflowExecutor.execute(
                workflow = workflow.workflow,
                context = appContext,
                triggerStepId = workflow.workflow.manualTrigger()?.id,
            )
            if (executionInstanceId.isBlank()) {
                terminalState.cancel()
                return@coroutineScope ChatToolResult(
                    callId = workflow.toolCall.id,
                    name = workflow.toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = workflow.definition.title,
                    outputText = "Temporary workflow `${workflow.workflow.name}` did not start because another execution is already running.",
                )
            }

            try {
                buildTemporaryWorkflowResult(
                    workflow = workflow,
                    terminalState = terminalState.await(),
                )
            } catch (timeout: TimeoutCancellationException) {
                WorkflowExecutor.stopExecution(workflow.workflow.id)
                ChatToolResult(
                    callId = workflow.toolCall.id,
                    name = workflow.toolCall.name,
                    status = ChatToolResultStatus.ERROR,
                    summary = workflow.definition.title,
                    outputText = "Temporary workflow `${workflow.workflow.name}` timed out.",
                )
            } catch (cancellation: CancellationException) {
                WorkflowExecutor.stopExecution(workflow.workflow.id)
                throw cancellation
            }
        }
    }

    private suspend fun executeReadyTool(
        ready: ChatPreparedToolItem.Ready,
        artifactStore: ChatAgentArtifactStore,
    ): ChatToolResult {
        val progressMessages = mutableListOf<String>()
        val workDir = File(appContext.cacheDir, "chat-agent").apply { mkdirs() }
        val services = ExecutionServices().apply {
            ServiceStateBus.getAccessibilityService()?.let(::add)
            add(ExecutionUIService(appContext))
        }
        val executionContext = ExecutionContext(
            applicationContext = appContext,
            variables = ExecutionContext.mutableMapToVObjectMap(ready.step.parameters.toMutableMap()),
            magicVariables = mutableMapOf(),
            services = services,
            allSteps = listOf(ready.step),
            currentStepIndex = 0,
            stepOutputs = mutableMapOf(),
            loopStack = Stack(),
            namedVariables = mutableMapOf(),
            workDir = workDir,
        )

        return try {
            when (val result = ready.module.execute(executionContext) { update ->
                update.message.takeIf { it.isNotBlank() }?.let(progressMessages::add)
            }) {
                is ExecutionResult.Success -> {
                    val artifacts = artifactStore.createReferences(
                        callId = normalizedToolCallId(ready.toolCall),
                        outputs = result.outputs,
                    )
                    ChatToolResult(
                        callId = ready.toolCall.id,
                        name = ready.toolCall.name,
                        status = ChatToolResultStatus.SUCCESS,
                        summary = ready.definition.title,
                        outputText = buildSuccessOutputText(
                            definition = ready.definition,
                            outputs = result.outputs,
                            artifacts = artifacts,
                            progressMessages = progressMessages,
                        ),
                        artifacts = artifacts,
                    )
                }

                is ExecutionResult.Failure -> {
                    ChatToolResult(
                        callId = ready.toolCall.id,
                        name = ready.toolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = ready.definition.title,
                        outputText = buildFailureOutputText(
                            definition = ready.definition,
                            failure = result,
                            progressMessages = progressMessages,
                        ),
                    )
                }

                is ExecutionResult.Signal -> {
                    ChatToolResult(
                        callId = ready.toolCall.id,
                        name = ready.toolCall.name,
                        status = ChatToolResultStatus.ERROR,
                        summary = ready.definition.title,
                        outputText = "Tool `${ready.definition.title}` returned an unsupported workflow control signal instead of a normal result.",
                    )
                }
            }
        } catch (throwable: Throwable) {
            if (throwable is CancellationException) throw throwable
            ChatToolResult(
                callId = ready.toolCall.id,
                name = ready.toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = ready.definition.title,
                outputText = buildString {
                    append("Tool `${ready.definition.title}` failed with an exception.")
                    throwable.message?.takeIf { it.isNotBlank() }?.let {
                        append("\n")
                        append(it)
                    }
                    if (progressMessages.isNotEmpty()) {
                        append("\n\nRecent progress:\n")
                        progressMessages.takeLast(6).forEach { line ->
                            append("- ")
                            append(line)
                            append("\n")
                        }
                    }
                }.trim(),
            )
        }
    }

    private fun buildParameters(
        module: ActionModule,
        rawArgumentsJson: String,
        artifactStore: ChatAgentArtifactStore,
    ): ChatParameterBuildResult {
        val defaults = module.createSteps().firstOrNull()?.parameters?.toMutableMap() ?: mutableMapOf()
        val arguments = parseArguments(rawArgumentsJson)

        // 两轮求值：第一轮用默认值 step 拿到基础定义表，第二轮带上已被接受的参数——
        // 让 CallFunctionModule 这类「依据 step 里已有值生成新字段」的模块也能吐出参数。
        val step0 = ActionStep(moduleId = module.id, parameters = defaults)
        val firstPass = resolveInputDefinitions(module, step0)

        val premerged = defaults.toMutableMap()
        arguments.forEach { (key, value) ->
            firstPass.firstOrNull { it.id == key }?.let { premerged[key] = value }
        }
        val step1 = ActionStep(moduleId = module.id, parameters = premerged)
        val secondPass = resolveInputDefinitions(module, step1)

        // 最终定义表 = 两轮并集（同 id 时后一轮的类型覆盖前一轮）。
        // 用并集而非纯静态全集：并集只会「多收」不会「少收」，不可能丢掉原本能通过的参数。
        val definitionsById = (firstPass + secondPass).associateBy { it.id }

        val accepted = linkedMapOf<String, Any?>()
        val rejected = mutableListOf<String>()
        arguments.forEach { (key, value) ->
            val input = definitionsById[key]
            if (input == null) {
                rejected += key
            } else {
                accepted[key] = coerceInputValue(input, value, artifactStore)
            }
        }

        // 模块可选：把 AI 的松散入参收敛成自己的标准存储形态。
        // 只有「参数本身是列表/字典」的模块需要（见 AiParameterNormalizer）——
        // 这类值经 ParameterType.ANY 原样透传到这里，形态与编辑器写的不一致。
        val merged = defaults + accepted
        val normalized = (module as? AiParameterNormalizer)
            ?.normalizeAiParameters(merged)
            ?: merged

        return ChatParameterBuildResult(
            parameters = normalized,
            rejectedKeys = rejected,
            availableKeys = definitionsById.keys.toList(),
        )
    }

    /**
     * 把「模型给了模块不认识的参数」变成**显式错误**。
     *
     * 改造前这里是 `?: return@forEach` 静默丢弃——模型以为参数生效了，
     * 编辑器里却显示为空（病症 B）。改成报错并附上可用键，让模型能自愈。
     */
    private fun buildRejectedKeysMessage(
        module: ActionModule,
        definition: ChatAgentToolDefinition,
        built: ChatParameterBuildResult,
    ): String {
        return buildString {
            append("Unknown parameter(s) for `${definition.title}` ")
            append("(module `${module.id}`): ")
            append(built.rejectedKeys.joinToString(", "))
            append(". ")
            if (built.availableKeys.isEmpty()) {
                append("This module accepts no parameters.")
            } else {
                append("Available parameters: ")
                append(built.availableKeys.joinToString(", "))
                append(".")
            }
        }
    }

    /**
     * 求一次模块的可用输入定义：**静态全集 ∪ 动态结果**。见 [resolveModuleInputDefinitions]。
     */
    private fun resolveInputDefinitions(
        module: ActionModule,
        step: ActionStep,
    ): List<InputDefinition> = resolveModuleInputDefinitions(module, step)

    private fun ChatPreparedToolItem.describeForLog(): String {
        return when (this) {
            is ChatPreparedToolItem.ImmediateResult -> "immediate name=${toolCall.name}"
            is ChatPreparedToolItem.NativeReady -> "native name=${toolCall.name} helper=${definition.nativeHelperId}"
            is ChatPreparedToolItem.Ready -> "module name=${toolCall.name} module=${module.id}"
            is ChatPreparedToolItem.SaveWorkflow -> "save_workflow name=${toolCall.name} workflow=${workflow.name}"
            is ChatPreparedToolItem.UpdateWorkflow ->
                "update_workflow name=${toolCall.name} id=${workflowId} warnings=${warnings.size} errors=${validationErrors.size}"
            is ChatPreparedToolItem.TemporaryWorkflow -> "temporary_workflow name=${toolCall.name} workflow=${workflow.name}"
        }
    }

    private fun String.compactForLog(maxLength: Int = 160): String {
        val compact = replace(Regex("""\s+"""), " ").trim()
        return if (compact.length > maxLength) compact.take(maxLength) + "…" else compact
    }

    /**
     * 处理 `load_skill`：按 id 返回技能正文。
     *
     * 正文作为 **tool result** 返回，进入对话历史后永久留存——这治的是
     * 「技能随话题切换而消失」：正文若每轮重算地拼进 system prompt，
     * 话题一换就掉出上下文。改造前 vFlow 正是那样做的。
     *
     * 失败时**明确报错并给出可选 id**，而不是返回空内容——模型拿到空字符串
     * 会以为技能没有内容，比报错更难自愈。
     */
    private fun prepareLoadSkill(toolCall: ChatToolCall): ChatToolResult {
        val skillId = parseArguments(toolCall.argumentsJson)["skill_id"]?.toString()?.trim().orEmpty()
        val listing = ChatAgentSkillRouter.skillListing()

        if (skillId.isBlank()) {
            return ChatToolResult(
                callId = toolCall.id,
                name = toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = "加载技能说明",
                outputText = "Missing `skill_id`. Available skills: " +
                    listing.joinToString(", ") { it.id } + ".",
            )
        }

        val skill = ChatAgentSkillRouter.skillInstructions(skillId)
            ?: return ChatToolResult(
                callId = toolCall.id,
                name = toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = "加载技能说明",
                outputText = "Unknown skill `$skillId`. Available skills: " +
                    listing.joinToString(", ") { it.id } + ".",
            )

        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.SUCCESS,
            summary = skill.title,
            outputText = buildString {
                append("<skill id=\"")
                append(skill.id)
                append("\" title=\"")
                append(skill.title)
                append("\">\n")
                // 正文原样输出，不做任何裁剪——加载技能就是为了拿到全部内容。
                append(skill.instructions.trim())
                append("\n</skill>")
            },
        )
    }

    /**
     * 处理 `call_module(module_id, params)`：通用模块执行入口。
     *
     * 59 个模块工具撤出 `tools` 数组后，模型须先 `query_module_schema` 拿字段定义，
     * 再用本工具执行——**它是固定工具，永远在**。
     *
     * ### 四项校验（文档 §P1-1b 的契约）
     *
     * 1. **`callable` 校验**：不在 `DIRECT_TOOL` 集合则明确报错。
     *    不信任模型从查询结果里读到的 `callable`——它可能凭记忆直接调。
     * 2. **params 自行校验**：`params` 是通用 object，provider 的 `strict` 校验失效，
     *    必须用与 `query_module_schema` 同一份定义在服务端校验。
     * 3. **风险等级取目标模块的实际等级**，不能一律放行——它是「万能入口」，
     *    审批若按「调用 call_module」这个动作统一判定，会绕过所有模块的风险评估。
     * 4. 参数错误**显式报错**，不静默丢弃（依赖 P0-1）。
     */
    private fun prepareCallModule(
        toolCall: ChatToolCall,
        artifactStore: ChatAgentArtifactStore,
    ): ChatPreparedToolItem {
        val arguments = parseArguments(toolCall.argumentsJson)
        val moduleId = arguments["module_id"]?.toString()?.trim().orEmpty()
        if (moduleId.isBlank()) {
            return callModuleError(toolCall, "Missing `module_id`.")
        }

        val module = ModuleRegistry.getModule(moduleId)
            ?: return callModuleError(
                toolCall,
                "Unknown module `$moduleId`. Use the module ids listed in the workflow tool descriptions.",
            )

        // 校验 1：能否直接调用。不信任模型查过的结果。
        if (ChatAgentToolUsageScope.DIRECT_TOOL !in toolRegistry.getUsageScopesForModuleId(moduleId)) {
            return callModuleError(
                toolCall,
                "`$moduleId` cannot be called directly — it is only valid as a workflow step. " +
                    "Use `$CHAT_SAVE_WORKFLOW_TOOL_NAME` or `$CHAT_TEMPORARY_WORKFLOW_TOOL_NAME` " +
                    "if you need this module as part of a workflow.",
            )
        }

        // 用真实模块的信息构造 definition：prepareModuleStep 复用它生成错误消息的 summary，
        // 并据此推导权限与风险等级。
        val definition = ChatAgentToolDefinition(
            name = chatToolNameFromModuleId(moduleId),
            title = module.metadata.getLocalizedName(appContext),
            description = "",
            moduleId = moduleId,
            moduleDisplayName = module.metadata.getLocalizedName(appContext),
            inputSchema = JsonObject(emptyMap()),
            permissionNames = emptyList(),
            riskLevel = toolRegistry.getRiskLevelForModuleId(moduleId),
            usageScopes = toolRegistry.getUsageScopesForModuleId(moduleId),
        )

        // params 是嵌套的 JSON 子树，原样取出即可——buildParameters 自己会解析。
        // 直接从原始 JSON 取，避免经 Map 往返丢失类型信息。
        val rawParamsJson = runCatching {
            json.parseToJsonElement(toolCall.argumentsJson)
                .jsonObject["params"]
                ?.jsonObject
                ?.toString()
        }.getOrNull().orEmpty()

        // 复用主执行链：参数构建（含 P0-1 的非退化求值与显式报错）、validate、权限计算。
        // stepId 用模块 id，便于模型在后续轮次用 {{STEP_ID.OUTPUT}} 引用本次输出。
        return prepareModuleStep(
            toolCall = toolCall,
            definition = definition,
            module = module,
            rawArgumentsJson = rawParamsJson,
            artifactStore = artifactStore,
            stepId = moduleId,
        )
    }

    private fun callModuleError(toolCall: ChatToolCall, message: String): ChatPreparedToolItem =
        ChatPreparedToolItem.ImmediateResult(
            toolCall = toolCall,
            result = ChatToolResult(
                callId = toolCall.id,
                name = toolCall.name,
                status = ChatToolResultStatus.ERROR,
                summary = "执行模块",
                outputText = message,
            ),
        )

    /**
     * 处理 `list_workflows`：列出用户的工作流，支持按名字 / 文件夹 / 类型筛选。
     *
     * **为什么需要它**：`CallFunctionModule` 的 `workflow_id` 是必填 string，但其可选值
     * 只能来自用户数据。schema 工具回答「字段长什么样」，这个工具回答「有哪些可选值」——
     * 职责分开，避免 schema 工具为各模块的数据依赖堆满特例。
     *
     * 输出带**文件夹名**（而非 id）：模型要按文件夹筛选时只需填名字，不必先查 id。
     */
    private fun prepareListWorkflows(toolCall: ChatToolCall): ChatToolResult {
        val arguments = parseArguments(toolCall.argumentsJson)
        val query = arguments["query"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val folder = arguments["folder"]?.toString()?.trim()?.takeIf { it.isNotBlank() }
        val kind = arguments["kind"]?.toString()?.trim()?.lowercase() ?: "all"

        val workflows = runCatching { WorkflowManager(appContext).getAllWorkflows() }
            .getOrDefault(emptyList())
        val foldersById = runCatching {
            FolderManager(appContext).getAllFolders().associateBy { it.id }
        }.getOrDefault(emptyMap())

        val filtered = workflows.filter { workflow ->
            val matchesQuery = query == null || workflow.name.contains(query, ignoreCase = true)
            val matchesKind = when (kind) {
                "function" -> workflow.isFunction
                "regular" -> !workflow.isFunction
                else -> true
            }
            val matchesFolder = folder == null || run {
                val folderName = workflow.folderId?.let { foldersById[it]?.name }
                folderName != null && folderName.equals(folder, ignoreCase = true)
            }
            matchesQuery && matchesKind && matchesFolder
        }

        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.SUCCESS,
            summary = "列出用户工作流",
            outputText = buildString {
                if (filtered.isEmpty()) {
                    append("No workflow matches ")
                    append(
                        listOfNotNull(
                            query?.let { "query=\"$it\"" },
                            folder?.let { "folder=\"$it\"" },
                            kind.takeIf { it != "all" }?.let { "kind=$it" },
                        ).joinToString(", ").ifBlank { "the request" }
                    )
                    appendLine(".")
                    if (workflows.isNotEmpty()) {
                        append("There are ${workflows.size} workflow(s) in total.")
                    }
                    return@buildString
                }

                filtered.forEach { workflow ->
                    append("- ").append(workflow.id).append(" — ").append(workflow.name)
                    if (workflow.isFunction) append(" [function]")
                    workflow.folderId?.let { id -> foldersById[id]?.name }?.let { append(" 📁").append(it) }
                    workflow.tags.takeIf { it.isNotEmpty() }?.let { append(" #").append(it.joinToString(",")) }

                    // 函数工作流附上完整签名——模型据此才能拼出 call_function 的 params
                    workflow.functionSignature?.let { signature ->
                        val params = signature.params
                        if (params.isEmpty()) {
                            append(" (no parameters)")
                        } else {
                            append(" (")
                            append(
                                params.joinToString(", ") { param ->
                                    val required = if (param.isRequired) " *" else ""
                                    "${param.name}:${param.type}$required"
                                }
                            )
                            append(")")
                        }
                        signature.returnDef?.let { ret -> append(" -> ").append(ret.type) }
                    }
                    appendLine()
                }
            },
        )
    }

    /**
     * 处理 `get_environment`：文件夹 + 全局变量。
     *
     * 全局变量**只给名字与类型**：模型引用 `{{global.x}}` 不需要知道当前值（执行时取值），
     * 而值可能是长 JSON 或用户敏感数据，无谓地进上下文。
     */
    private fun prepareGetEnvironment(toolCall: ChatToolCall): ChatToolResult {
        val folders = runCatching { FolderManager(appContext).getAllFolders() }
            .getOrDefault(emptyList())
        val workflows = runCatching { WorkflowManager(appContext).getAllWorkflows() }
            .getOrDefault(emptyList())
        val globals = runCatching { GlobalVariableStore.getAll(appContext) }
            .getOrDefault(emptyMap())

        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.SUCCESS,
            summary = "查询用户环境",
            outputText = buildString {
                appendLine("folders:")
                if (folders.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    folders.sortedBy { it.order }.forEach { folder ->
                        val count = workflows.count { it.folderId == folder.id }
                        append("  - ").append(folder.name).append(" (").append(count).append(" workflows)")
                        folder.parentId?.let { parent ->
                            folders.firstOrNull { it.id == parent }?.let { append(" under ").append(it.name) }
                        }
                        appendLine()
                    }
                }
                val unfiled = workflows.count { it.folderId == null }
                if (unfiled > 0) appendLine("  - (unfiled): $unfiled workflows")

                appendLine()
                appendLine("global variables (reference as {{global.<name>}}):")
                if (globals.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    globals.entries.sortedBy { it.key }.forEach { (name, value) ->
                        append("  - ").append(name)
                            .append(" (").append(value.type.name.lowercase()).appendLine(")")
                    }
                }
            },
        )
    }

    /**
     * 处理 `query_module_schema`：返回模块的完整字段定义。
     *
     * **查询域 = 能进保存工作流的全部模块（~184）**，不是能直调的 59——
     * 写工作流时能用的模块远多于能直接调的。边界靠返回结果里的
     * `callable` 与 `scopes` 前置给模型，而不是靠把模块排除出查询域。
     *
     * 字段定义**与执行校验共用同一求值口径**（[resolveModuleInputDefinitions]），
     * 保证「模型看得见的字段」与「执行时收下的字段」一致——这条以前是断的，
     * catalog 用空白 step 求值会裁掉 `If` 的 `value1`/`value2`（病症 B）。
     */
    private fun prepareQueryModuleSchema(toolCall: ChatToolCall): ChatToolResult {
        val arguments = parseArguments(toolCall.argumentsJson)
        val moduleId = arguments["module_id"]?.toString()?.trim().orEmpty()

        if (moduleId.isBlank()) {
            return querySchemaError(toolCall, "Missing `module_id`. Pass a canonical module id.")
        }

        val module = ModuleRegistry.getModule(moduleId)
            ?: return querySchemaError(
                toolCall,
                "Unknown module `$moduleId`. Use the module ids listed in the workflow tool descriptions.",
            )

        if (!toolRegistry.isSavedWorkflowModuleAllowed(moduleId)) {
            return querySchemaError(
                toolCall,
                "`$moduleId` cannot appear in a saved workflow, so it is out of the queryable set.",
            )
        }

        val step = ActionStep(
            moduleId = module.id,
            parameters = module.createSteps().firstOrNull()?.parameters.orEmpty(),
        )
        val inputs = visibleInputsForAgent(
            resolveModuleInputDefinitions(module, step),
            step.parameters,
        )
        val scopes = toolRegistry.getUsageScopesForModuleId(moduleId)
        val callable = ChatAgentToolUsageScope.DIRECT_TOOL in scopes
        val metadata = module.aiMetadata

        // 必填集由模块自己声明（76 个模块用了它）。仅凭 defaultValue 有无判断
        // 是不可靠的——有默认值 ≠ 非必填。
        val requiredIds = metadata?.requiredInputIds.orEmpty()

        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.SUCCESS,
            summary = module.metadata.getLocalizedName(appContext),
            outputText = buildString {
                append("moduleId: ").appendLine(module.id)
                append("name: ").appendLine(module.metadata.getLocalizedName(appContext))
                append("description: ").appendLine(
                    // 直调场景优先用模块专为 AI 写的描述，而非面向人的本地化文案
                    metadata?.directToolDescription
                        ?: module.metadata.getLocalizedDescription(appContext)
                )
                metadata?.workflowStepDescription?.let { append("as workflow step: ").appendLine(it) }
                append("callable: ").appendLine(callable)
                append("scopes: ").appendLine(scopes.joinToString(", ") { it.label })
                append("risk: ").appendLine(toolRegistry.getRiskLevelForModuleId(moduleId).name.lowercase())
                appendLine()
                appendLine("inputs (fields marked * are required):")
                if (inputs.isEmpty()) {
                    appendLine("  (none)")
                } else {
                    inputs.forEach { input ->
                        val required = input.id in requiredIds
                        append("  - ").append(input.id)
                        if (required) append(" *")
                        append(" (").append(input.staticType.name.lowercase()).append(")")
                        // 复用与模块工具 JSON Schema 同一份说明拼装：
                        // 中文名 + 本地化提示 + 产物类型 + **inputHints** + 枚举值。
                        // inputHints 是模块作者为 AI 写的字段语义（67 个模块声明了它），
                        // 例如 If 的 "value2: Upper bound used only by the number_between
                        // operator." —— 这类信息无法从字段名或类型推出。
                        val description = buildModuleInputDescription(appContext, moduleId, input)
                        if (description.isNotBlank()) append(" — ").append(description)
                        if (input.defaultValue != null) {
                            append(" [default: ").append(input.defaultValue.toString()).append("]")
                        }
                        appendLine()
                    }
                }
                val outputs = module.getOutputs(step)
                if (outputs.isNotEmpty()) {
                    appendLine()
                    appendLine("outputs:")
                    outputs.forEach { output ->
                        append("  - ").append(output.id)
                        append(" (").append(output.typeName).append(")")
                        if (output.name.isNotBlank()) append(" — ").append(output.name)
                        appendLine()
                    }
                }
                if (!callable) {
                    appendLine()
                    append(
                        "Note: this module cannot be invoked directly with `$CHAT_CALL_MODULE_TOOL_NAME`, " +
                            "but it is still valid as a workflow step."
                    )
                }
                // 查询"有哪些函数工作流"是**数据**问题，不是 schema 问题——
                // 由 `list_workflows` 承担（见该工具定义）。这里只给出指引，
                // 避免 schema 工具因各模块的数据依赖而堆满特例。
                if (moduleId == CALL_FUNCTION_MODULE_ID) {
                    appendLine()
                    appendLine(
                        "Note: `workflow_id` must be the id of a **function workflow**. " +
                            "Call `$CHAT_LIST_WORKFLOWS_TOOL_NAME` with kind=\"function\" to see " +
                            "the available ones and their parameters."
                    )
                }
            },
        )
    }

    private fun querySchemaError(toolCall: ChatToolCall, message: String): ChatToolResult {
        return ChatToolResult(
            callId = toolCall.id,
            name = toolCall.name,
            status = ChatToolResultStatus.ERROR,
            summary = "查询模块字段定义",
            outputText = message,
        )
    }

    private fun parseArguments(rawArgumentsJson: String): Map<String, Any?> {
        if (rawArgumentsJson.isBlank()) return emptyMap()
        val element = json.parseToJsonElement(rawArgumentsJson)
        val root = element as? JsonObject ?: return emptyMap()
        return root.mapValues { (_, value) -> normalizeJsonValue(value) }
    }

    private fun normalizeJsonValue(element: JsonElement): Any? {
        return when (element) {
            JsonNull -> null
            is JsonObject -> element.mapValues { (_, value) -> normalizeJsonValue(value) }
            is JsonArray -> element.map(::normalizeJsonValue)
            is JsonPrimitive -> {
                element.booleanOrNull
                    ?: element.doubleOrNull
                    ?: element.contentOrNull
            }
        }
    }

    private fun coerceInputValue(
        input: InputDefinition,
        rawValue: Any?,
        artifactStore: ChatAgentArtifactStore,
    ): Any? {
        val artifactValue = resolveArtifactValue(rawValue, artifactStore)
        return when (input.staticType) {
            ParameterType.STRING -> artifactValue ?: rawValue?.toString().orEmpty()
            ParameterType.NUMBER -> coerceNumber(rawValue, input.defaultValue)
            ParameterType.BOOLEAN -> coerceBoolean(rawValue)
            ParameterType.ENUM -> input.normalizeEnumValue(rawValue?.toString(), rawValue?.toString())
            ParameterType.ANY -> artifactValue ?: rawValue
        }
    }

    private fun resolveArtifactValue(
        rawValue: Any?,
        artifactStore: ChatAgentArtifactStore,
    ): Any? {
        val handle = rawValue as? String ?: return null
        if (!handle.startsWith("artifact://")) return null
        return artifactStore.resolve(handle)
            ?: throw IllegalArgumentException("Artifact handle is no longer available: $handle")
    }

    private fun coerceNumber(rawValue: Any?, defaultValue: Any?): Any? {
        val number = when (rawValue) {
            is Number -> rawValue
            is String -> rawValue.toDoubleOrNull()
            else -> null
        } ?: return rawValue

        return when (defaultValue) {
            is Int -> number.toInt()
            is Long -> number.toLong()
            is Float -> number.toFloat()
            else -> number.toDouble()
        }
    }

    private fun coerceBoolean(rawValue: Any?): Any? {
        return when (rawValue) {
            is Boolean -> rawValue
            is String -> rawValue.equals("true", ignoreCase = true)
            else -> rawValue
        }
    }

    private fun buildSuccessOutputText(
        definition: ChatAgentToolDefinition,
        outputs: Map<String, Any?>,
        artifacts: List<ChatArtifactReference>,
        progressMessages: List<String>,
    ): String {
        return buildString {
            append("Tool `${definition.title}` completed successfully.")

            if (outputs.isNotEmpty()) {
                append("\n\nOutputs:\n")
                outputs.forEach { (key, value) ->
                    append("- ")
                    append(key)
                    append(": ")
                    append(summarizeOutputValue(value))
                    append("\n")
                }
            }

            if (artifacts.isNotEmpty()) {
                append("\nArtifacts:\n")
                artifacts.forEach { artifact ->
                    append("- ")
                    append(artifact.key)
                    append(" (")
                    append(artifact.typeLabel)
                    append("): ")
                    append(artifact.handle)
                    append("\n")
                }
            }

            if (progressMessages.isNotEmpty()) {
                append("\nProgress:\n")
                progressMessages.takeLast(8).forEach { message ->
                    append("- ")
                    append(message)
                    append("\n")
                }
            }
        }.trim()
    }

    private fun buildFailureOutputText(
        definition: ChatAgentToolDefinition,
        failure: ExecutionResult.Failure,
        progressMessages: List<String>,
    ): String {
        return buildString {
            append("Tool `${definition.title}` failed.")
            if (failure.errorTitle.isNotBlank()) {
                append("\n")
                append(failure.errorTitle)
            }
            if (failure.errorMessage.isNotBlank()) {
                append("\n")
                append(failure.errorMessage)
            }
            if (progressMessages.isNotEmpty()) {
                append("\n\nRecent progress:\n")
                progressMessages.takeLast(8).forEach { message ->
                    append("- ")
                    append(message)
                    append("\n")
                }
            }
        }.trim()
    }

    private fun buildTemporaryWorkflowResult(
        workflow: ChatPreparedToolItem.TemporaryWorkflow,
        terminalState: ExecutionState,
    ): ChatToolResult {
        val status = if (terminalState is ExecutionState.Finished) {
            ChatToolResultStatus.SUCCESS
        } else {
            ChatToolResultStatus.ERROR
        }
        return ChatToolResult(
            callId = workflow.toolCall.id,
            name = workflow.toolCall.name,
            status = status,
            summary = workflow.definition.title,
            outputText = buildString {
                append(
                    when (terminalState) {
                        is ExecutionState.Finished -> "Temporary workflow `${workflow.workflow.name}` completed successfully."
                        is ExecutionState.Failure -> "Temporary workflow `${workflow.workflow.name}` failed at step ${terminalState.stepIndex + 1}."
                        is ExecutionState.Cancelled -> "Temporary workflow `${workflow.workflow.name}` was cancelled."
                        is ExecutionState.Running -> "Temporary workflow `${workflow.workflow.name}` is still running."
                    }
                )
                append("\n\nSteps:\n")
                workflow.preparedSteps.take(30).forEachIndexed { index, readyStep ->
                    append("- ")
                    append(index + 1)
                    append(". ")
                    append(readyStep.definition.title)
                    append(" (")
                    append(readyStep.step.moduleId)
                    append(")")
                    append("\n")
                }
                if (workflow.preparedSteps.size > 30) {
                    append("- ... ")
                    append(workflow.preparedSteps.size - 30)
                    append(" more steps\n")
                }

                val detailedLog = terminalState.detailedLogOrEmpty().trim()
                if (detailedLog.isNotBlank()) {
                    append("\nExecution log:\n")
                    append(truncateMultiline(detailedLog))
                    append("\n")
                }
            }.trim(),
        )
    }

    private fun ExecutionState.isTerminalExecutionState(): Boolean {
        return this is ExecutionState.Finished ||
            this is ExecutionState.Failure ||
            this is ExecutionState.Cancelled
    }

    private fun ExecutionState.detailedLogOrEmpty(): String {
        return when (this) {
            is ExecutionState.Finished -> detailedLog
            is ExecutionState.Failure -> detailedLog
            is ExecutionState.Cancelled -> detailedLog
            is ExecutionState.Running -> ""
        }
    }

    private fun summarizeOutputValue(value: Any?): String {
        return when (value) {
            null -> "null"
            is VString -> truncate(value.raw)
            is VBoolean -> value.raw.toString()
            is VNumber -> value.raw.toString()
            is VCoordinate -> value.asString()
            is VCoordinateRegion -> value.asString()
            is VImage -> "image artifact available"
            is VScreenElement -> buildString {
                append(value.text?.takeIf { it.isNotBlank() } ?: value.viewId ?: value.className ?: "screen element")
                append(" @ ")
                append(value.centerX)
                append(",")
                append(value.centerY)
            }
            is ChatAgentUiSnapshot -> buildString {
                append(value.currentUi)
                append(" (")
                append(value.elements.size)
                append(" elements)")
            }
            is VObject -> truncate(value.asString())
            is String -> truncate(value)
            else -> truncate(value.toString())
        }
    }

    private fun truncate(text: String, maxLength: Int = 180): String {
        val normalized = text.replace('\n', ' ').trim()
        return if (normalized.length > maxLength) {
            normalized.take(maxLength) + "..."
        } else {
            normalized
        }
    }

    private fun truncateMultiline(text: String, maxLength: Int = 4_000): String {
        val normalized = text.trim()
        return if (normalized.length > maxLength) {
            normalized.take(maxLength) + "\n..."
        } else {
            normalized
        }
    }

    private fun normalizedToolCallId(toolCall: ChatToolCall): String {
        return toolCall.id ?: "call_${UUID.randomUUID()}"
    }

    private data class TemporaryWorkflowSpec(
        val name: String,
        val description: String,
        val maxExecutionTime: Int,
        val steps: List<WorkflowStepSpec>,
    )

    private data class SaveWorkflowSpec(
        val name: String,
        val description: String,
        val isEnabled: Boolean,
        val folderId: String?,
        val tags: List<String>,
        val maxExecutionTime: Int?,
        val reentryBehavior: WorkflowReentryBehavior,
        val triggers: List<WorkflowStepSpec>,
        val steps: List<WorkflowStepSpec>,
    )

    private data class WorkflowStepSpec(
        val id: String,
        val moduleId: String,
        val parameters: JsonObject,
        val indentationLevel: Int,
    )

    private data class SavedWorkflowStepCandidate(
        val module: ActionModule,
        val step: ActionStep,
        val sourceLabel: String,
    )

    private companion object {
        private const val LOG_TAG = "ChatToolExec"
        const val DEFAULT_TEMPORARY_WORKFLOW_MAX_SECONDS = 120
        const val MAX_TEMPORARY_WORKFLOW_MAX_SECONDS = 300
        const val MAX_TEMPORARY_WORKFLOW_REPEAT = 50
        const val MAX_TEMPORARY_WORKFLOW_DELAY_MS = 60_000L
        const val MAX_TEMPORARY_WORKFLOW_EXPANDED_STEPS = 80
        const val MAX_SAVED_WORKFLOW_TRIGGERS = 12
        const val MAX_SAVED_WORKFLOW_STEPS = 200
        const val MAX_SAVED_WORKFLOW_MAX_SECONDS = 3600
        const val MAX_SAVED_WORKFLOW_TAGS = 12
        const val MAX_WORKFLOW_INDENTATION_LEVEL = 12

        /** `vflow.logic.jump` 的 moduleId 与目标参数名（`get_workflow` 的标注要用）。 */
        private const val JUMP_MODULE_ID = "vflow.logic.jump"
        private const val TARGET_STEP_INDEX_PARAM = "target_step_index"

        /**
         * AI 工具链**改不到**的工作流字段。
         *
         * 它们不在任何补丁原语里，所以结构上就改不到——列出来是为了防止模型
         * 试图通过 `metadata` 改它们然后陷入无效重试。
         */
        private val READ_ONLY_WORKFLOW_FIELDS = listOf(
            "cardIconRes",
            "cardThemeColor",
            "shortcutName",
            "shortcutIconRes",
            "order",
            "isFavorite",
            "author",
            "homepage",
            "version",
            "vFlowLevel",
            "wasEnabledBeforePermissionsLost",
            "modifiedAt",
            "functionSignature",
        )
    }
}

/**
 * 求一次模块的可用输入定义：**静态全集（[ActionModule.getInputs]）∪ 动态结果
 * （[ActionModule.getDynamicInputs]）**。
 *
 * 之所以不只是 `getDynamicInputs`：它是为「编辑器里用户下一步该填哪格」服务的，
 * 会按当前算子/类型裁剪，天然是子集。拿它当参数合法性白名单，模型填的合法参数
 * （如 `If` 的 `value1`/`value2`）会被静默裁掉——病症 B 的根因。
 *
 * 之所以不只是 `getInputs`：它有例外——`CallFunctionModule` 的 `getInputs()` 只声明
 * `workflow_id`，各函数参数由 `getDynamicInputs` 依据选中工作流的签名凭空生成。
 *
 * 并集的性质是**只会「多收」不会「少收」**：它不可能丢掉纯静态方案能通过的参数。
 * 同 id 时保留后者（动态结果），以便拿到算子相关的类型改写（如 `If` 把 `value1`
 * 从 `ANY` 改成 `NUMBER`）。
 *
 * 动态求值异常时回退静态全集，绝不因求值出错而丢参数。
 *
 * 模块 catalog（[ChatAgentToolRegistry]）与执行校验（[ChatAgentModuleExecutor]）共用此函数，
 * 保证「模型看得见的字段」与「执行时收下的字段」口径一致。
 */
internal fun resolveModuleInputDefinitions(
    module: ActionModule,
    step: ActionStep,
): List<InputDefinition> {
    val staticInputs = module.getInputs()
    val dynamicInputs = runCatching {
        module.getDynamicInputs(step, listOf(step))
    }.getOrDefault(emptyList())
    // 动态结果放后面：同 id 时它覆盖静态定义，保留算子相关的类型/选项改写。
    return (staticInputs + dynamicInputs).distinctBy { it.id }
}

/**
 * [ChatAgentModuleExecutor.buildParameters] 的产物。
 *
 * @property parameters 模块的完整参数表（模块默认值 + 被接受的模型入参）
 * @property rejectedKeys 模型给了、但模块定义里找不到的键。**不静默丢弃**——
 *   调用方须把它变成显式错误回传给模型，否则它会以为参数已生效。
 * @property availableKeys 本次求值得到的全部合法键（用于错误提示里给出可用项）
 */
internal data class ChatParameterBuildResult(
    val parameters: Map<String, Any?>,
    val rejectedKeys: List<String> = emptyList(),
    val availableKeys: List<String> = emptyList(),
)

/** 「调用函数工作流」的 moduleId。查询它的字段定义时会附上指向 `list_workflows` 的指引。 */
internal const val CALL_FUNCTION_MODULE_ID = "vflow.logic.call_function"

/**
 * 从模块的输入定义中筛出**该让 AI 看到的**字段。
 *
 * ### 为什么不能照搬编辑器的 `isHidden` 判定
 *
 * 编辑器的过滤是两道（`ActionEditorUiModel.build`）：
 * 1. `getHandledInputIds()`——UIProvider 声明的「这些字段我来画」
 * 2. `visibility?.isVisible(params) ?: !isHidden`——可见性
 *
 * `isHidden` 在那里的语义是「**通用渲染器别碰**」，它回答的是「谁负责画这个字段」。
 * **AI 不渲染 UI，这个问题对 AI 无意义。** 拿渲染管线的标记去判断「字段该不该给模型看」
 * 是两件不相干的事。
 *
 * 它的三个实际用法：
 * - 「UIProvider 接管渲染」→ 有替代品 `getHandledInputIds()`，但**多数模块尚未迁移**
 * - 「条件可见性」→ 已被 `InputVisibility` 取代，**这就是上游标注它废弃的原因**
 * - 「动态生成的字段不参与通用渲染」→ **至今无替代品**（`getHandledInputIds` 是静态
 *   Set，声明不了运行时才知道的参数名；`CallFunctionModule` 正是这种情况）
 *
 * ### 是否照搬是经过权衡的
 *
 * 不读 `isHidden` 会让模型多看到几个 UI 开关（如 `show_advanced`——编辑器里
 * 「▼ 显示高级选项」那个折叠箭头，它根本不是模块参数）。代价是轻微的噪声。
 *
 * 而照搬的代价更重：**真参数会被一起丢掉**。最典型的是
 * `CallFunctionModule` 的函数参数与 `FindInstalledAppModule` 的 `userId`/`maxResults`
 * ——它们被标了 `isHidden`，但模块作者**专门为 AI 写了 `inputHints`**，
 * 显然期望模型使用。丢掉它们，模型就再也调不对这些模块。
 *
 * 两害相权，选择让模型多看到几个无害的开关。
 *
 * @param stepParameters 当前参数，供 `visibility` 条件求值
 */
internal fun visibleInputsForAgent(
    inputs: List<InputDefinition>,
    stepParameters: Map<String, Any?>,
): List<InputDefinition> {
    return inputs.filter { input ->
        // 只认声明式的条件可见性。`visibility` 表达的是「此刻该字段有没有意义」——
        // 这才是与 AI 相关的语义。
        input.visibility?.isVisible(stepParameters) ?: true
    }
}
