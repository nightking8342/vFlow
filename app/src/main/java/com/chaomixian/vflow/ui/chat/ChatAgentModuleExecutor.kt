package com.chaomixian.vflow.ui.chat

import android.content.Context
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.ExecutionState
import com.chaomixian.vflow.core.execution.ExecutionStateBus
import com.chaomixian.vflow.core.execution.ExecutionServices
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.ActionModule
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

        return ChatParameterBuildResult(
            parameters = defaults + accepted,
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
