package com.chaomixian.vflow.ui.chat

import android.app.Application
import android.content.Intent
import android.content.SharedPreferences
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.permissions.Permission
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatPermissionRequest(
    val requestId: String,
    val permissions: List<Permission>,
    val conversationId: String,
    val messageId: String,
)

data class ChatUiState(
    val conversations: List<ChatConversation> = emptyList(),
    val activeConversationId: String? = null,
    val presets: List<ChatPresetConfig> = emptyList(),
    val defaultPresetId: String? = null,
    val isSending: Boolean = false,
    val isAgentRunning: Boolean = false,
    val availableTools: List<ChatAgentToolDefinition> = emptyList(),
    val pendingPermissionRequest: ChatPermissionRequest? = null,
    val autoApprovalScope: ChatToolAutoApprovalScope = ChatToolAutoApprovalScope.OFF,
    val queuedPromptCount: Int = 0,
    val isBenchmarkRunning: Boolean = false,
    val benchmarkUi: ChatBenchmarkUiState = ChatBenchmarkUiState(),
)

private data class PendingToolExecution(
    val conversationId: String,
    val messageId: String,
    val preset: ChatPresetConfig,
    val batch: ChatPreparedToolBatch,
)

private data class QueuedUserPrompt(
    val conversationId: String,
    val content: String,
    val timestampMillis: Long,
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = ChatPresetRepository(application)
    private val toolRegistry = ChatAgentToolRegistry(application)
    private val toolExecutor = ChatAgentModuleExecutor(application, toolRegistry)
    private val chatClient = ChatCompletionClient()
    private val benchmarkRunner = ChatBenchmarkRunner(application, chatClient, toolRegistry, toolExecutor)
    private val artifactStores = mutableMapOf<String, ChatAgentArtifactStore>()

    private val _uiState = MutableStateFlow(
        ChatUiState(
            availableTools = toolRegistry.getTools(),
            autoApprovalScope = repository.getAutoApprovalScope(),
        )
    )
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private var conversationCounter = 1
    private var pendingToolExecution: PendingToolExecution? = null
    private var currentAgentJob: Job? = null
    private var currentAgentConversationId: String? = null
    private var currentBenchmarkJob: Job? = null
    private val queuedUserPrompts = mutableListOf<QueuedUserPrompt>()
    private val newConversationTitlePattern = Regex("""新对话\s+(\d+)""")

    private companion object {
        private const val LOG_TAG = "ChatAgentFlow"
        private const val MAX_LOG_SNIPPET = 180
    }

    private val prefsListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "chat_presets_json" ||
            key == "chat_provider_configs_json" ||
            key == "chat_default_preset_id"
        ) {
            reloadPresets()
        }
    }

    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()
    val events: SharedFlow<String> = _events.asSharedFlow()

    init {
        repository.registerChangeListener(prefsListener)
        restoreSessionState()
        reloadPresets()
        ensureConversation()
    }

    override fun onCleared() {
        repository.unregisterChangeListener(prefsListener)
        currentBenchmarkJob?.cancel()
        super.onCleared()
    }

    fun newConversation() {
        val currentActive = _uiState.value.activeConversation
        if (currentActive != null && currentActive.messages.isEmpty()) {
            updateUiStateAndPersist { state ->
                state.copy(activeConversationId = currentActive.id)
            }
            return
        }
        val now = System.currentTimeMillis()
        val next = ChatConversation(
            title = "新对话 ${conversationCounter++}",
            presetId = resolveFallbackPresetId(),
            createdAtMillis = now,
            updatedAtMillis = now,
        )
        updateUiStateAndPersist { state ->
            state.copy(
                conversations = listOf(next) + state.conversations,
                activeConversationId = next.id,
            )
        }
    }

    fun selectConversation(conversationId: String) {
        updateUiStateAndPersist { state -> state.copy(activeConversationId = conversationId) }
    }

    fun deleteConversation(conversationId: String) {
        val stateBeforeDelete = _uiState.value
        val deletedConversation = stateBeforeDelete.conversations.firstOrNull { it.id == conversationId }
        val deletesBusyConversation = currentAgentConversationId == conversationId ||
            pendingToolExecution?.conversationId == conversationId ||
            stateBeforeDelete.pendingPermissionRequest?.conversationId == conversationId ||
            (deletedConversation?.messages?.any { message ->
                message.isPending ||
                    message.toolApprovalState == ChatToolApprovalState.PENDING ||
                    message.toolApprovalState == ChatToolApprovalState.RUNNING
            } == true)

        queuedUserPrompts.removeAll { it.conversationId == conversationId }
        if (deletesBusyConversation) {
            currentAgentJob?.cancel()
            currentAgentJob = null
            currentAgentConversationId = null
            pendingToolExecution = null
        }

        updateUiStateAndPersist { state ->
            val remaining = state.conversations.filter { it.id != conversationId }
            state.copy(
                conversations = remaining,
                activeConversationId = if (state.activeConversationId == conversationId) {
                    remaining.firstOrNull()?.id
                } else {
                    state.activeConversationId
                },
                isSending = if (deletesBusyConversation) false else state.isSending,
                isAgentRunning = if (deletesBusyConversation) false else state.isAgentRunning,
                pendingPermissionRequest = state.pendingPermissionRequest
                    ?.takeUnless { it.conversationId == conversationId || deletesBusyConversation },
                queuedPromptCount = queuedUserPrompts.size,
            )
        }
        artifactStores.remove(conversationId)
        processNextQueuedPromptIfIdle()
    }

    fun selectPreset(presetId: String) {
        updateActiveConversation { conversation ->
            conversation.copy(presetId = presetId)
        }
    }

    fun setAutoApprovalScope(scope: ChatToolAutoApprovalScope) {
        repository.setAutoApprovalScope(scope)
        _uiState.update { state -> state.copy(autoApprovalScope = scope) }
    }

    fun refreshBenchmarkPreflight() {
        val preset = resolveBenchmarkPreset()
        viewModelScope.launch {
            val preflight = benchmarkRunner.inspectPreflight(preset)
            _uiState.update { state ->
                state.copy(
                    benchmarkUi = state.benchmarkUi.copy(preflight = preflight)
                )
            }
        }
    }

    fun startBenchmarkRun() {
        val state = _uiState.value
        if (state.isBenchmarkRunning || currentBenchmarkJob != null) {
            _events.tryEmit("Benchmark 已经在运行中。")
            return
        }
        if (state.isSending || pendingToolExecution != null) {
            _events.tryEmit("请先等待当前聊天任务完成。")
            return
        }
        val preset = resolveBenchmarkPreset()
        if (preset == null) {
            _events.tryEmit("请先在设置 -> 模型配置里配置聊天模型。")
            return
        }

        currentBenchmarkJob = viewModelScope.launch {
            try {
                benchmarkRunner.runSuite(
                    suite = _uiState.value.benchmarkUi.suite,
                    preset = preset,
                ) { updatedRun ->
                    _uiState.update { current ->
                        val mergedRuns = listOf(updatedRun) + current.benchmarkUi.recentRuns
                            .filterNot { it.id == updatedRun.id }
                        current.copy(
                            isBenchmarkRunning = updatedRun.status == ChatBenchmarkRunStatus.RUNNING,
                            benchmarkUi = current.benchmarkUi.copy(
                                preflight = updatedRun.preflight ?: current.benchmarkUi.preflight,
                                activeRun = updatedRun.takeIf { it.status == ChatBenchmarkRunStatus.RUNNING },
                                recentRuns = mergedRuns.take(10),
                            ),
                        )
                    }
                    persistSessionState()
                }
                _events.tryEmit("Benchmark 已完成。")
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _events.tryEmit(
                    throwable.message?.trim().orEmpty().ifBlank { "Benchmark 执行失败。" }
                )
                _uiState.update { current ->
                    current.copy(
                        isBenchmarkRunning = false,
                        benchmarkUi = current.benchmarkUi.copy(activeRun = null),
                    )
                }
                persistSessionState()
            } finally {
                currentBenchmarkJob = null
            }
        }
    }

    fun exportBenchmarkRun(run: ChatBenchmarkRun): Intent? {
        return runCatching {
            ChatBenchmarkExportManager.buildShareIntent(getApplication(), run)
        }.getOrElse { throwable ->
            _events.tryEmit(
                throwable.message?.trim().orEmpty().ifBlank { "Benchmark 日志导出失败。" }
            )
            null
        }
    }

    /** 导出指定会话的完整 JSON，返回可交给系统分享的 Intent；失败时经 events 提示并返回 null。 */
    fun exportConversation(conversationId: String): Intent? {
        val conversation = _uiState.value.conversations.firstOrNull { it.id == conversationId }
            ?: return null
        return runCatching {
            ChatConversationExportManager.buildShareIntent(getApplication(), conversation)
        }.getOrElse { throwable ->
            _events.tryEmit(
                getApplication<Application>().getString(
                    R.string.chat_export_failed,
                    throwable.message?.trim().orEmpty().ifBlank { "unknown error" },
                )
            )
            null
        }
    }

    fun deleteBenchmarkRun(runId: String) {
        val state = _uiState.value
        val activeRun = state.benchmarkUi.activeRun
        val target = state.benchmarkUi.recentRuns.firstOrNull { it.id == runId } ?: return
        if (target.status == ChatBenchmarkRunStatus.RUNNING || activeRun?.id == runId) {
            _events.tryEmit("Benchmark 正在运行，无法删除当前记录。")
            return
        }
        updateUiStateAndPersist { current ->
            current.copy(
                benchmarkUi = current.benchmarkUi.copy(
                    recentRuns = current.benchmarkUi.recentRuns.filterNot { it.id == runId }
                )
            )
        }
    }

    fun stopAgent() {
        if (_uiState.value.isBenchmarkRunning) {
            _events.tryEmit("Benchmark 正在运行，当前版本不支持中途停止。")
            return
        }
        // ⚠️ **必须在清空之前**取出目标会话（§4.7 / F14）。
        // 原实现在下一步就把 `currentAgentConversationId` 置 null，且后续变换只处理
        // `state.activeConversationId`——两者组合的后果是：
        // 「流式期间用户切到别的会话（**浮窗场景下这是常态**）后点停止」时，
        // 那条正在流式的消息**永远停在 isPending = true** ⇒
        // `persistSessionState` 的 `filterNot { it.isPending }` **永久过滤掉它**
        // ⇒ 用户切回来看到卡住的「正在生成…」，重启后内容消失。
        val targetConversationId = currentAgentConversationId

        currentAgentJob?.cancel()
        currentAgentJob = null
        currentAgentConversationId = null
        pendingToolExecution = null
        queuedUserPrompts.clear()
        val now = System.currentTimeMillis()
        updateUiStateAndPersist { state ->
            state.copy(
                isSending = false,
                isAgentRunning = false,
                pendingPermissionRequest = null,
                queuedPromptCount = 0,
                conversations = state.conversations.map { conversation ->
                    // ⚠️ 用 target 而**不是** active —— 见上方 F14 说明。
                    // 若 target 为 null（没有在跑的请求），退化为原来的活动会话行为，
                    // 以便仍能处理「有 PENDING 审批但 Agent 未运行」的情形。
                    val effectiveId = targetConversationId ?: state.activeConversationId
                    if (conversation.id != effectiveId) return@map conversation
                    val stoppedMessages = buildList {
                        conversation.messages.forEach { message ->
                            when {
                                // ⚠️ §4.7 / 共识 C4：**保留**已生成的文本，不再换成「已停止。」。
                                // 原写法把它替换成一条 ERROR 消息，等于抹掉用户已经读到的内容。
                                // 现在只把 `isPending` 置 false（内容原样留下），
                                // 是否给提示由下方 `_events` 承担。
                                message.isPending -> {
                                    add(message.copy(isPending = false))
                                }

                                message.role == ChatMessageRole.ASSISTANT &&
                                    message.toolApprovalState in setOf(
                                        ChatToolApprovalState.PENDING,
                                        ChatToolApprovalState.RUNNING,
                                    ) -> {
                                    add(message.copy(toolApprovalState = ChatToolApprovalState.REJECTED))
                                    toolExecutor.buildRejectedResults(message.toolCalls)
                                        .forEachIndexed { index, result ->
                                            add(
                                                ChatMessage(
                                                    role = ChatMessageRole.TOOL,
                                                    content = result.outputText,
                                                    timestampMillis = now + index + 1,
                                                    toolResult = result,
                                                )
                                            )
                                        }
                                }

                                else -> add(message)
                            }
                        }
                    }
                    conversation.copy(
                        messages = stoppedMessages,
                        updatedAtMillis = now,
                    )
                }.sortedByDescending { it.updatedAtMillis },
            )
        }
        _events.tryEmit("已停止。")
    }

    fun sendMessage(prompt: String): Boolean {
        val content = prompt.trim()
        if (content.isBlank()) return false
        val state = _uiState.value
        if (state.isBenchmarkRunning) {
            _events.tryEmit("Benchmark 正在运行，暂时无法发送新的对话消息。")
            return false
        }
        val conversation = state.activeConversation ?: return false
        val preset = resolvePreset(conversation, state)
        if (preset == null) {
            _events.tryEmit("请先在设置 -> 模型配置里配置聊天模型。")
            return false
        }

        val pendingApproval = conversation.pendingToolApprovalMessage()
        if (pendingApproval != null) {
            pendingToolExecution = null
            rejectPendingToolAndStartUserMessage(
                conversation = conversation,
                pendingToolMessage = pendingApproval,
                content = content,
                preset = preset,
            )
            return true
        }

        if (state.isAgentRunning || state.isSending || pendingToolExecution != null) {
            enqueueUserPrompt(conversation.id, content)
            return true
        }

        startUserMessage(conversation, content, preset)
        return true
    }

    private fun startUserMessage(
        conversation: ChatConversation,
        content: String,
        preset: ChatPresetConfig,
    ) {
        DebugLogger.i(
            LOG_TAG,
            "User message received conversation=${conversation.id} preset=${preset.name.ifBlank { preset.model }} text=${content.compactForLog()}"
        )
        val now = System.currentTimeMillis()
        val userMessage = ChatMessage(
            role = ChatMessageRole.USER,
            content = content,
            timestampMillis = now,
        )
        val historyForRequest = conversation.messages + userMessage
        val updatedConversation = conversation.copy(
            title = deriveConversationTitle(historyForRequest),
            updatedAtMillis = now,
            messages = historyForRequest,
            presetId = conversation.presetId ?: preset.id,
        )
        requestAssistantReply(
            conversation = updatedConversation,
            historyForRequest = historyForRequest,
            preset = preset,
        )
    }

    private fun rejectPendingToolAndStartUserMessage(
        conversation: ChatConversation,
        pendingToolMessage: ChatMessage,
        content: String,
        preset: ChatPresetConfig,
    ) {
        val now = System.currentTimeMillis()
        val rejectedResults = toolExecutor.buildRejectedResults(pendingToolMessage.toolCalls)
        val rejectedResultMessages = rejectedResults.mapIndexed { index, result ->
            ChatMessage(
                role = ChatMessageRole.TOOL,
                content = result.outputText,
                timestampMillis = now + index,
                toolResult = result,
            )
        }
        val messagesWithRejectedTool = buildList {
            conversation.messages.forEach { message ->
                if (message.id == pendingToolMessage.id) {
                    add(message.copy(toolApprovalState = ChatToolApprovalState.REJECTED))
                    addAll(rejectedResultMessages)
                } else {
                    add(message)
                }
            }
        }
        val userMessage = ChatMessage(
            role = ChatMessageRole.USER,
            content = content,
            timestampMillis = now + rejectedResultMessages.size + 1,
        )
        val historyForRequest = messagesWithRejectedTool + userMessage
        val updatedConversation = conversation.copy(
            title = deriveConversationTitle(historyForRequest),
            updatedAtMillis = userMessage.timestampMillis,
            messages = historyForRequest,
            presetId = conversation.presetId ?: preset.id,
        )
        requestAssistantReply(
            conversation = updatedConversation,
            historyForRequest = historyForRequest,
            preset = preset,
        )
    }

    private fun enqueueUserPrompt(conversationId: String, content: String) {
        queuedUserPrompts += QueuedUserPrompt(
            conversationId = conversationId,
            content = content,
            timestampMillis = System.currentTimeMillis(),
        )
        DebugLogger.i(
            LOG_TAG,
            "Queued user message conversation=$conversationId queued=${queuedUserPrompts.size} text=${content.compactForLog()}"
        )
        _uiState.update { state ->
            state.copy(queuedPromptCount = queuedUserPrompts.size)
        }
        _events.tryEmit("已加入队列。")
    }

    private fun processNextQueuedPromptIfIdle() {
        val state = _uiState.value
        if (state.isSending || pendingToolExecution != null || queuedUserPrompts.isEmpty()) return
        val nextIndex = queuedUserPrompts.indexOfFirst { queued ->
            state.conversations.any { it.id == queued.conversationId }
        }
        if (nextIndex < 0) {
            queuedUserPrompts.clear()
            _uiState.update { it.copy(queuedPromptCount = 0) }
            return
        }
        val queued = queuedUserPrompts[nextIndex]
        val conversation = state.conversations.firstOrNull { it.id == queued.conversationId } ?: return
        val pendingApproval = conversation.pendingToolApprovalMessage()
        if (state.isAgentRunning && pendingApproval == null) return
        val preset = resolvePreset(conversation, state)
        if (preset == null) {
            queuedUserPrompts.removeAt(nextIndex)
            _uiState.update { it.copy(queuedPromptCount = queuedUserPrompts.size) }
            _events.tryEmit("队列中的消息无法发送：当前会话没有可用模型预设。")
            processNextQueuedPromptIfIdle()
            return
        }

        queuedUserPrompts.removeAt(nextIndex)
        _uiState.update { it.copy(queuedPromptCount = queuedUserPrompts.size) }
        if (pendingApproval != null) {
            rejectPendingToolAndStartUserMessage(
                conversation = conversation,
                pendingToolMessage = pendingApproval,
                content = queued.content,
                preset = preset,
            )
        } else {
            startUserMessage(
                conversation = conversation,
                content = queued.content,
                preset = preset,
            )
        }
    }

    fun approveToolCalls(messageId: String) {
        val state = _uiState.value
        val conversation = state.activeConversation ?: return
        val message = conversation.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatMessageRole.ASSISTANT || message.toolApprovalState != ChatToolApprovalState.PENDING) {
            return
        }
        if (state.isSending || pendingToolExecution != null) {
            return
        }
        val preset = resolvePreset(conversation, state)
        if (preset == null) {
            _events.tryEmit("当前会话没有可用的模型预设。")
            return
        }

        val artifactStore = artifactStores.getOrPut(conversation.id, ::ChatAgentArtifactStore)
        val batch = toolExecutor.prepareBatch(message.toolCalls, artifactStore)
        DebugLogger.i(
            LOG_TAG,
            "Approving tool batch conversation=${conversation.id} message=${message.id} risk=${batch.riskLevel} tools=${message.toolCalls.summarizeToolCalls()} missingPermissions=${batch.missingPermissions.joinToString { it.id }}"
        )
        pendingToolExecution = PendingToolExecution(
            conversationId = conversation.id,
            messageId = message.id,
            preset = preset,
            batch = batch,
        )

        updateUiStateAndPersist { current ->
            current.copy(
                conversations = current.conversations.updateMessage(message.id) { existing ->
                    existing.copy(toolApprovalState = ChatToolApprovalState.RUNNING)
                },
                isSending = true,
                isAgentRunning = true,
                pendingPermissionRequest = batch.missingPermissions
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        ChatPermissionRequest(
                            requestId = "perm_${System.currentTimeMillis()}_${message.id}",
                            permissions = it,
                            conversationId = conversation.id,
                            messageId = message.id,
                        )
                    },
            )
        }

        if (batch.missingPermissions.isEmpty()) {
            launchPendingToolBatch()
        }
    }

    fun rejectToolCalls(messageId: String) {
        val state = _uiState.value
        val conversation = state.activeConversation ?: return
        val message = conversation.messages.firstOrNull { it.id == messageId } ?: return
        if (message.role != ChatMessageRole.ASSISTANT || message.toolApprovalState != ChatToolApprovalState.PENDING) {
            return
        }
        val preset = resolvePreset(conversation, state)
        if (preset == null) {
            _events.tryEmit("当前会话没有可用的模型预设。")
            return
        }
        val toolResults = toolExecutor.buildRejectedResults(message.toolCalls)
        pendingToolExecution = null
        appendToolResultsAndContinue(
            conversationId = conversation.id,
            messageId = message.id,
            preset = preset,
            approvalState = ChatToolApprovalState.REJECTED,
            toolResults = toolResults,
        )
    }

    fun rerunToolCalls(messageId: String) {
        val state = _uiState.value
        val conversation = state.activeConversation ?: return
        val sourceMessage = conversation.messages.firstOrNull { it.id == messageId } ?: return
        if (sourceMessage.role != ChatMessageRole.ASSISTANT || sourceMessage.toolCalls.isEmpty()) {
            return
        }
        if (sourceMessage.toolApprovalState in setOf(ChatToolApprovalState.PENDING, ChatToolApprovalState.RUNNING)) {
            return
        }
        if (state.isSending || pendingToolExecution != null) {
            _events.tryEmit("Agent 正在执行中。")
            return
        }
        val artifactStore = artifactStores.getOrPut(conversation.id, ::ChatAgentArtifactStore)
        val rerunToolCalls = sourceMessage.toolCalls.mapIndexed { index, toolCall ->
            toolCall.copy(id = "rerun_${System.currentTimeMillis()}_$index")
        }
        val batch = toolExecutor.prepareBatch(rerunToolCalls, artifactStore)
        if (batch.missingPermissions.isNotEmpty()) {
            _events.tryEmit("缺少必要权限，无法再次执行。")
            return
        }

        val now = System.currentTimeMillis()
        val rerunMessage = ChatMessage(
            role = ChatMessageRole.ASSISTANT,
            content = "",
            timestampMillis = now,
            toolCalls = rerunToolCalls,
            toolApprovalState = ChatToolApprovalState.RUNNING,
        )
        replaceConversation(
            conversation = conversation.copy(
                messages = conversation.messages + rerunMessage,
                updatedAtMillis = now,
            ),
            isSending = true,
            isAgentRunning = true,
        )

        currentAgentConversationId = conversation.id
        currentAgentJob = viewModelScope.launch {
            try {
                val toolResults = toolExecutor.executeBatch(batch, artifactStore)
                val resultMessages = toolResults.mapIndexed { index, result ->
                    ChatMessage(
                        role = ChatMessageRole.TOOL,
                        content = result.outputText,
                        timestampMillis = System.currentTimeMillis() + index,
                        toolResult = result,
                    )
                }
                updateUiStateAndPersist { currentState ->
                    currentState.copy(
                        conversations = currentState.conversations.map { conv ->
                            if (conv.id != conversation.id) return@map conv
                            conv.copy(
                                messages = conv.messages.map { msg ->
                                    if (msg.id == rerunMessage.id) msg.copy(toolApprovalState = ChatToolApprovalState.APPROVED)
                                    else msg
                                } + resultMessages,
                                updatedAtMillis = resultMessages.lastOrNull()?.timestampMillis ?: now,
                            )
                        }.sortedByDescending { it.updatedAtMillis },
                        isSending = false,
                        isAgentRunning = false,
                    )
                }
                processNextQueuedPromptIfIdle()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                _events.tryEmit(
                    throwable.message?.trim().orEmpty().ifBlank { "工具执行失败。" }
                )
                updateUiStateAndPersist { currentState ->
                    currentState.copy(
                        conversations = currentState.conversations.updateMessage(rerunMessage.id) {
                            it.copy(toolApprovalState = ChatToolApprovalState.REJECTED)
                        },
                        isSending = false,
                        isAgentRunning = false,
                    )
                }
                processNextQueuedPromptIfIdle()
            } finally {
                if (currentAgentJob === coroutineContext[Job]) {
                    currentAgentJob = null
                    currentAgentConversationId = null
                }
            }
        }
    }

    fun markPermissionRequestLaunched() {
        _uiState.update { state ->
            state.copy(pendingPermissionRequest = null)
        }
    }

    fun onToolPermissionResult(granted: Boolean) {
        val pending = pendingToolExecution ?: run {
            updateUiStateAndPersist { state ->
                state.copy(
                    isSending = false,
                    isAgentRunning = false,
                    pendingPermissionRequest = null,
                )
            }
            processNextQueuedPromptIfIdle()
            return
        }
        DebugLogger.i(
            LOG_TAG,
            "Tool permission result conversation=${pending.conversationId} granted=$granted pendingTools=${pending.batch.items.size}"
        )
        if (granted) {
            launchPendingToolBatch()
        } else {
            pendingToolExecution = null
            val toolResults = toolExecutor.buildPermissionRequiredResults(pending.batch)
            appendToolResultsAndContinue(
                conversationId = pending.conversationId,
                messageId = pending.messageId,
                preset = pending.preset,
                approvalState = ChatToolApprovalState.APPROVED,
                toolResults = toolResults,
            )
        }
    }

    private suspend fun executePendingToolBatch() {
        val pending = pendingToolExecution ?: return
        DebugLogger.i(
            LOG_TAG,
            "Executing tool batch conversation=${pending.conversationId} tools=${pending.batch.items.size} risk=${pending.batch.riskLevel}"
        )
        val artifactStore = artifactStores.getOrPut(pending.conversationId, ::ChatAgentArtifactStore)
        val toolResults = toolExecutor.executeBatch(pending.batch, artifactStore)
        pendingToolExecution = null
        appendToolResultsAndContinue(
            conversationId = pending.conversationId,
            messageId = pending.messageId,
            preset = pending.preset,
            approvalState = ChatToolApprovalState.APPROVED,
            toolResults = toolResults,
        )
    }

    private fun launchPendingToolBatch() {
        currentAgentConversationId = pendingToolExecution?.conversationId
        currentAgentJob = viewModelScope.launch {
            try {
                executePendingToolBatch()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                val pending = pendingToolExecution
                pendingToolExecution = null
                _events.tryEmit(
                    throwable.message?.trim().orEmpty().ifBlank { "工具执行失败。" }
                )
                updateUiStateAndPersist { state ->
                    state.copy(
                        isSending = false,
                        isAgentRunning = false,
                        pendingPermissionRequest = null,
                        conversations = pending?.let { failed ->
                            state.conversations.updateMessage(failed.messageId) { message ->
                                message.copy(toolApprovalState = ChatToolApprovalState.REJECTED)
                            }
                        } ?: state.conversations,
                    )
                }
                processNextQueuedPromptIfIdle()
            } finally {
                if (currentAgentJob === coroutineContext[Job]) {
                    currentAgentJob = null
                    currentAgentConversationId = null
                }
            }
        }
    }

    private fun appendToolResultsAndContinue(
        conversationId: String,
        messageId: String,
        preset: ChatPresetConfig,
        approvalState: ChatToolApprovalState,
        toolResults: List<ChatToolResult>,
    ) {
        DebugLogger.i(
            LOG_TAG,
            "Appending tool results conversation=$conversationId message=$messageId approval=$approvalState results=${toolResults.summarizeToolResults()}"
        )
        val conversation = _uiState.value.conversations.firstOrNull { it.id == conversationId } ?: return
        val updatedAssistantMessages = conversation.messages.map { message ->
            if (message.id == messageId) {
                message.copy(toolApprovalState = approvalState)
            } else {
                message
            }
        }
        val resultMessages = toolResults.mapIndexed { index, result ->
            ChatMessage(
                role = ChatMessageRole.TOOL,
                content = result.outputText,
                timestampMillis = System.currentTimeMillis() + index,
                toolResult = result,
            )
        }
        val historyForRequest = updatedAssistantMessages + resultMessages
        val updatedConversation = conversation.copy(
            messages = historyForRequest,
            updatedAtMillis = historyForRequest.lastOrNull()?.timestampMillis ?: System.currentTimeMillis(),
        )
        requestAssistantReply(
            conversation = updatedConversation,
            historyForRequest = historyForRequest,
            preset = preset,
        )
    }

    private fun requestAssistantReply(
        conversation: ChatConversation,
        historyForRequest: List<ChatMessage>,
        preset: ChatPresetConfig,
    ) {
        DebugLogger.i(
            LOG_TAG,
            "Requesting assistant reply conversation=${conversation.id} messages=${historyForRequest.size} preset=${preset.name.ifBlank { preset.model }}"
        )
        val now = System.currentTimeMillis()
        val pendingMessage = ChatMessage(
            role = ChatMessageRole.ASSISTANT,
            content = "",
            timestampMillis = now,
            isPending = true,
        )
        val updatedConversation = conversation.copy(
            title = deriveConversationTitle(historyForRequest),
            updatedAtMillis = now,
            messages = historyForRequest + pendingMessage,
            presetId = conversation.presetId ?: preset.id,
        )
        replaceConversation(
            conversation = updatedConversation,
            isSending = true,
            pendingPermissionRequest = null,
        )

        currentAgentConversationId = updatedConversation.id
        currentAgentJob = viewModelScope.launch {
            // ⚠️ 这三个变量必须在 `try` **之外**声明：`catch` 块也要用它们
            // （中断/失败时要保留已收到的内容，见 §4.7 与 C4）。
            // 若声明在 try 内，catch 访问不到 ⇒ 已生成的文本拿不回来。
            var streamedContent = StringBuilder()
            var streamedReasoning = StringBuilder()
            var result: ChatCompletionResult? = null
            // fork: Chat 流超时与恢复 —— 本轮用掉的重试次数（失败文案要带上它）。
            // 与上面三个变量同理，必须在 `try` 之外声明（catch 要用）。
            var retriesUsed = 0
            try {
                val skillSelection = ChatAgentSkillRouter.availableTools(
                    _uiState.value.availableTools,
                )
                DebugLogger.i(
                    LOG_TAG,
                    "Tools conversation=${updatedConversation.id} tools=${skillSelection.availableTools.joinToString { it.name }}"
                )
                // ⚠️ 流式接线（`chat-streaming-design.md` §4.4）。改动前是一次性
                // `generateReply(...)`，拿到结果后**整条替换**占位消息（换 id）。
                // 现在改为：占位消息的 id **活到成品**，正文**原位增长**。
                //
                // 三条约束：
                // 1. 流式期间只写 `_uiState`，**绝不**走 `updateUiStateAndPersist`
                //    （它每次把整个会话表序列化成大 JSON 写盘，流式下每秒十几次）；
                // 2. `Completed` 携带的是**权威值**（§4.4 的 B4），累积 delta 只用于过程显示；
                // 3. 收尾必须走 `finalizeStreamingMessage`，它负责 §4.4 那 11 项里的 7 项。
                //
                // ⚠️ fork: Chat 流超时与恢复 —— 外面包一层 `streamWithRecovery`。
                // `openStream` 必须是**工厂**（每次重试都重新调 `streamReply` ⇒ 新的 adapter /
                // 新的 assembler / 新的 HTTP 请求），复用同一个 Flow 实例会拿到跑脏的归约器。
                streamWithRecovery(
                    policy = ChatStreamRecoveryPolicy(
                        maxRetries = ChatStreamTimeouts.clampStreamMaxRetries(preset.streamMaxRetries),
                    ),
                    onRetry = { notice ->
                        retriesUsed = notice.attempt
                        DebugLogger.w(
                            LOG_TAG,
                            "Stream idle timeout retry attempt=${notice.attempt}/${notice.maxAttempts - 1} kind=${notice.kind} delayMs=${notice.delayMs} conversation=${updatedConversation.id}",
                        )
                        _events.tryEmit("网络空闲超时，正在重试（${notice.attempt}/${notice.maxAttempts - 1}）…")
                    },
                ) {
                    chatClient.streamReply(
                        preset = preset,
                        history = historyForRequest,
                        skillSelection = skillSelection,
                    )
                }.collect { event ->
                    when (event) {
                        is ChatStreamEvent.TextDelta -> {
                            streamedContent.append(event.text)
                            patchStreamingMessage(
                                conversationId = updatedConversation.id,
                                messageId = pendingMessage.id,
                                content = streamedContent.toString(),
                                reasoning = streamedReasoning.toString().ifBlank { null },
                            )
                        }

                        is ChatStreamEvent.ReasoningDelta -> {
                            streamedReasoning.append(event.text)
                            patchStreamingMessage(
                                conversationId = updatedConversation.id,
                                messageId = pendingMessage.id,
                                content = streamedContent.toString(),
                                reasoning = streamedReasoning.toString().ifBlank { null },
                            )
                        }

                        is ChatStreamEvent.Completed -> result = event.result

                        // 工具调用的中间态**不进 UI**：三家头部 Agent 一致反对逐 token
                        // 渲染工具参数（共识 C1），且 ApprovalCard 只认收尾后的完整调用。
                        is ChatStreamEvent.ToolCallDelta,
                        is ChatStreamEvent.ToolCallCompleted,
                        is ChatStreamEvent.Usage,
                        -> Unit
                    }
                }
                val streamedResult = result
                    ?: error("流已结束但没有收到 Completed 事件。")
                DebugLogger.i(
                    LOG_TAG,
                    "Model reply conversation=${updatedConversation.id} tokens=${result.totalTokens ?: -1} reasoningChars=${result.reasoningContent?.length ?: 0} cacheCreate=${result.cacheCreationTokens ?: "-"} cacheRead=${result.cacheReadTokens ?: "-"} cacheDeleted=${result.cacheDeletedTokens ?: "-"} toolCalls=${result.toolCalls.summarizeToolCalls()} content=${result.content.compactForLog()}"
                )
                // ⚠️ 工具调用的 id 兜底：与原实现同一规则（缺 id 时按 `call_<会话>_<消息>_<序号>` 生成），
                // 但**消息 id 沿用占位消息的 id**（不再新建）——下沉到纯函数层之外的唯一原因
                // 是它依赖 `updatedConversation.id` / `pendingMessage.id` 两个局部变量。
                val normalizedToolCalls = result.toolCalls.mapIndexed { index, toolCall ->
                    toolCall.copy(
                        id = toolCall.id ?: "call_${updatedConversation.id}_${pendingMessage.id}_$index"
                    )
                }
                val hasToolCalls = normalizedToolCalls.isNotEmpty()
                val shouldAutoApprove = hasToolCalls &&
                    shouldAutoApproveToolCalls(updatedConversation.id, normalizedToolCalls)

                // ⚠️ 收尾走**纯函数**（`ChatMessagePatch.kt`）：VM 内的逻辑无法单测
                // （无 Robolectric/mockk，`repository` 无注入缝），而 §4.4 的清单漏一项就是静默错误。
                // 本处只做「取状态 → 调纯函数 → 写回」三件事，不含任何判断逻辑。
                //
                // ⚠️ `content` 用 `result.content`（**权威值**，见 §4.4 的 B4），
                // **不是** `streamedContent`（那是未经规范化的过程值）。
                //
                // ⚠️⚠️ **空内容兜底只在「没有工具调用」时才加**——必须与上游
                // `:906-909` 的 `if (toolCalls.isNotEmpty()) … else …` 结构一致。
                //
                // 工具调用那一轮 `content` **本就该是空的**（模型只输出 `tool_call`、
                // 不产生正文），属**正常情况**。若无条件兜底，界面会显示
                // 「模型返回了空内容。」⇒ **错报**（真机日志实证：`toolCalls=…` 且 `content=` 为空）。
                val finalResult = _uiState.value.conversations.finalizeStreamingMessage(
                    conversationId = updatedConversation.id,
                    messageId = pendingMessage.id,
                    patch = StreamingFinalizePatch(
                        content = if (hasToolCalls) {
                            result.content
                        } else {
                            result.content.ifBlank { "模型返回了空内容。" }
                        },
                        reasoningContent = result.reasoningContent,
                        tokenCount = result.totalTokens,
                        toolCalls = normalizedToolCalls,
                        toolApprovalState = if (hasToolCalls) ChatToolApprovalState.PENDING else null,
                    ),
                )
                _uiState.update { state ->
                    state.copy(
                        conversations = finalResult.conversations,
                        // §4.4 清单第 9-10 项（收尾字段，不属消息本身）
                        isSending = false,
                        // ⚠️ 有工具待审批时 Agent **仍在运行**（等用户点批准），
                        // 与原 `replacePendingMessage` 的判断一致。
                        isAgentRunning = hasToolCalls,
                        pendingPermissionRequest = null,
                    )
                }
                persistSessionState()

                val autoApproveMessageId = if (shouldAutoApprove) pendingMessage.id else null
                if (autoApproveMessageId != null) {
                    DebugLogger.i(
                        LOG_TAG,
                        "Auto-approving tool calls conversation=${updatedConversation.id} message=$autoApproveMessageId"
                    )
                    approveToolCalls(autoApproveMessageId)
                } else {
                    processNextQueuedPromptIfIdle()
                }
            } catch (cancellation: CancellationException) {
                // ⚠️ §4.7 / 共识 C4：**取消时保留已生成的文本**，而非整条换成「已停止。」。
                // 四家头部 Agent（CCB / dsh / OpenCode / Pi）一致如此——
                // 用户已经读到一半了，把它抹掉换成一句「已停止。」是净损失。
                //
                // ⚠️ 这里**不**用 `currentAgentJob` 做守卫（原 `:950-954` 那个模式在本处必然失效）：
                // `stopAgent` 是 `cancel()` **紧接着同步置 null**，等本 `finally` 在调度上跑起来时
                // `currentAgentJob` 已是 null ⇒ 守卫恒为 false ⇒ 收尾被跳过。
                // 故取消的收尾由 `stopAgent` 主动负责（见 F14 的处理），此处只**兜底**：
                // 若消息仍是 pending（说明不是 `stopAgent` 触发的取消，例如父作用域被取消），
                // 把已收到的内容固化下来。
                finalizeOnInterruption(
                    conversationId = updatedConversation.id,
                    messageId = pendingMessage.id,
                    content = streamedContent.toString(),
                    reasoning = streamedReasoning.toString().ifBlank { null },
                )
                throw cancellation
            } catch (throwable: Throwable) {
                DebugLogger.e(
                    LOG_TAG,
                    "Assistant request failed conversation=${updatedConversation.id}: ${throwable.message}",
                    throwable
                )
                pendingToolExecution = null
                // ⚠️ §4.7：失败时**已收到的正文必须保留**，错误以**独立消息**追加，
                // 而不是把半截回复整条换成错误文案（那会让用户刚读到的一段凭空消失）。
                val baseError = throwable.message?.trim().orEmpty().ifBlank { "请求失败，请检查当前模型配置。" }
                finishWithError(
                    conversationId = updatedConversation.id,
                    messageId = pendingMessage.id,
                    partialContent = streamedContent.toString(),
                    partialReasoning = streamedReasoning.toString().ifBlank { null },
                    // fork: 带重试次数（finishWithError 签名不动）
                    errorText = if (retriesUsed > 0) "$baseError（已重试 $retriesUsed 次）" else baseError,
                )
                processNextQueuedPromptIfIdle()
            } finally {
                if (currentAgentJob === coroutineContext[Job]) {
                    currentAgentJob = null
                    currentAgentConversationId = null
                }
            }
        }
    }

    private fun shouldAutoApproveToolCalls(
        conversationId: String,
        toolCalls: List<ChatToolCall>,
    ): Boolean {
        val scope = _uiState.value.autoApprovalScope
        if (scope == ChatToolAutoApprovalScope.OFF || toolCalls.isEmpty()) return false
        val artifactStore = artifactStores.getOrPut(conversationId, ::ChatAgentArtifactStore)
        val batch = toolExecutor.prepareBatch(toolCalls, artifactStore)
        return scope.allows(batch.riskLevel)
    }

    fun canSaveTemporaryWorkflow(messageId: String): Boolean {
        val state = _uiState.value
        val conversation = state.activeConversation ?: return false
        val message = conversation.messages.firstOrNull { it.id == messageId } ?: return false
        return message.toolCalls.any { it.name == CHAT_TEMPORARY_WORKFLOW_TOOL_NAME }
    }

    fun saveTemporaryWorkflow(messageId: String) {
        val state = _uiState.value
        val conversation = state.activeConversation ?: return
        val message = conversation.messages.firstOrNull { it.id == messageId } ?: return
        val tempToolCall = message.toolCalls.firstOrNull { it.name == CHAT_TEMPORARY_WORKFLOW_TOOL_NAME } ?: return
        val workflow = toolExecutor.buildWorkflowForSave(tempToolCall) ?: run {
            _events.tryEmit("无法解析临时工作流。")
            return
        }
        val result = runCatching {
            WorkflowManager(getApplication()).saveWorkflow(workflow)
        }
        _events.tryEmit(
            result.fold(
                onSuccess = { "工作流「${workflow.name}」已保存。" },
                onFailure = { "保存失败：${it.message?.ifBlank { null } ?: "未知错误"}" },
            )
        )
    }

    private fun reloadPresets() {
        val presets = repository.getPresets()
        val defaultPresetId = repository.getDefaultPresetId()
        _uiState.update { state ->
            state.copy(
                presets = presets,
                defaultPresetId = defaultPresetId,
                conversations = state.conversations.map { conversation ->
                    val presetExists = presets.any { it.id == conversation.presetId }
                    if (conversation.presetId == null || presetExists) {
                        conversation
                    } else {
                        conversation.copy(presetId = defaultPresetId ?: presets.firstOrNull()?.id)
                    }
                }
            )
        }
        persistSessionState()
    }

    private fun ensureConversation() {
        if (_uiState.value.conversations.isEmpty()) {
            newConversation()
        }
    }

    private fun resolveFallbackPresetId(): String? {
        val state = _uiState.value
        return state.defaultPresetId ?: state.presets.firstOrNull()?.id
    }

    private fun resolvePreset(
        conversation: ChatConversation,
        state: ChatUiState = _uiState.value,
    ): ChatPresetConfig? {
        val preferredId = conversation.presetId ?: state.defaultPresetId
        return state.presets.firstOrNull { it.id == preferredId } ?: state.presets.firstOrNull()
    }

    private fun updateActiveConversation(transform: (ChatConversation) -> ChatConversation) {
        val state = _uiState.value
        val activeId = state.activeConversationId ?: return
        val current = state.conversations.firstOrNull { it.id == activeId } ?: return
        replaceConversation(
            conversation = transform(current),
            isSending = state.isSending,
            isAgentRunning = state.isAgentRunning,
        )
    }

    private fun replaceConversation(
        conversation: ChatConversation,
        isSending: Boolean,
        pendingPermissionRequest: ChatPermissionRequest? = _uiState.value.pendingPermissionRequest,
        isAgentRunning: Boolean = isSending,
    ) {
        updateUiStateAndPersist { state ->
            state.copy(
                conversations = state.conversations.reorderedWith(conversation),
                activeConversationId = conversation.id,
                isSending = isSending,
                isAgentRunning = isAgentRunning,
                pendingPermissionRequest = pendingPermissionRequest,
            )
        }
    }

    private fun replacePendingMessage(
        conversationId: String,
        pendingMessageId: String,
        replacement: ChatMessage,
    ) {
        updateUiStateAndPersist { state ->
            val agentStillRunning = replacement.role == ChatMessageRole.ASSISTANT &&
                replacement.toolApprovalState == ChatToolApprovalState.PENDING
            state.copy(
                conversations = state.conversations.map { conversation ->
                    if (conversation.id != conversationId) return@map conversation
                    val updatedMessages = conversation.messages.map { message ->
                        if (message.id == pendingMessageId) replacement else message
                    }
                    conversation.copy(
                        messages = updatedMessages,
                        title = deriveConversationTitle(updatedMessages),
                        updatedAtMillis = replacement.timestampMillis,
                    )
                }.sortedByDescending { it.updatedAtMillis },
                isSending = false,
                isAgentRunning = agentStillRunning,
                pendingPermissionRequest = null,
            )
        }
    }

    private fun deriveConversationTitle(messages: List<ChatMessage>): String {
        val firstUserMessage = messages.firstOrNull { it.role == ChatMessageRole.USER }?.content
            ?.replace('\n', ' ')
            ?.trim()
            .orEmpty()
        if (firstUserMessage.isBlank()) {
            return String.format(Locale.getDefault(), "新对话 %d", conversationCounter)
        }
        return if (firstUserMessage.length > 18) {
            "${firstUserMessage.take(18)}..."
        } else {
            firstUserMessage
        }
    }

    private fun restoreSessionState() {
        val persisted = repository.getSessionState().sanitizeForRestore()
        if (persisted.conversations.isEmpty() && persisted.benchmarkRuns.isEmpty()) return
        _uiState.update { state ->
            val activeConversationId = persisted.activeConversationId
                ?.takeIf { id -> persisted.conversations.any { it.id == id } }
                ?: persisted.conversations.firstOrNull()?.id
            state.copy(
                conversations = persisted.conversations,
                activeConversationId = activeConversationId,
                isSending = false,
                isAgentRunning = false,
                isBenchmarkRunning = false,
                benchmarkUi = state.benchmarkUi.copy(
                    recentRuns = persisted.benchmarkRuns.map { run ->
                        if (run.status == ChatBenchmarkRunStatus.RUNNING) {
                            run.copy(
                                status = ChatBenchmarkRunStatus.CANCELLED,
                                finishedAtMillis = run.finishedAtMillis ?: System.currentTimeMillis(),
                            )
                        } else {
                            run
                        }
                    },
                    activeRun = null,
                ),
            )
        }
        conversationCounter = persisted.nextConversationCounter()
    }

    private fun updateUiStateAndPersist(transform: (ChatUiState) -> ChatUiState) {
        _uiState.update(transform)
        persistSessionState()
    }

    /**
     * 流式期间**原位增长**一条消息的正文与思考内容（`chat-streaming-design.md` §4.4）。
     *
     * ⚠️ **只更新内存，不落盘**。这是与 [updateUiStateAndPersist] 的关键区别：
     * 后者每次都把**整个会话表**序列化成一个大 JSON 写 SharedPreferences，
     * 而流式每个 delta 都会调一次本方法 ⇒ 用它会导致**每秒十几次全量序列化**。
     * 落盘只在收尾时做一次（见 `requestAssistantReply` 的 `persistSessionState()`）。
     *
     * ⚠️ 变换本身在 `ChatMessagePatch.patchStreamingMessage`（纯函数、有单测）。
     */
    private fun patchStreamingMessage(
        conversationId: String,
        messageId: String,
        content: String,
        reasoning: String?,
    ) {
        _uiState.update { state ->
            state.copy(
                conversations = state.conversations.patchStreamingMessage(
                    conversationId = conversationId,
                    messageId = messageId,
                    content = content,
                    reasoningContent = reasoning,
                )
            )
        }
    }

    /**
     * **中断**时的兜底收尾（`chat-streaming-design.md` §4.7 / 共识 C4）：
     * 保留已收到的文本，把消息从 pending 状态固化下来。
     *
     * ⚠️ 与 [finishWithError] 的区别：这里**不追加错误消息**——取消是用户的主动意图，
     * 不是失败，不该在对话里留下一条错误记录。
     *
     * ⚠️ **幂等**：若消息已是 `isPending = false`（已被 `stopAgent` 收尾过），本方法什么都不做。
     * 这是必要的——取消路径有 `stopAgent` 与协程 `catch` 两个可能的触发点。
     */
    private fun finalizeOnInterruption(
        conversationId: String,
        messageId: String,
        content: String,
        reasoning: String?,
    ) {
        val state = _uiState.value
        val message = state.conversations
            .firstOrNull { it.id == conversationId }
            ?.messages?.firstOrNull { it.id == messageId }
            ?: return
        if (!message.isPending) return  // 已被收尾过，不重复

        DebugLogger.i(LOG_TAG, "Interrupted, keeping partial content chars=${content.length}")
        val outcome = state.conversations.finalizeStreamingMessage(
            conversationId = conversationId,
            messageId = messageId,
            patch = StreamingFinalizePatch(
                content = content,
                reasoningContent = reasoning,
                tokenCount = null,
            ),
        )
        _uiState.update { it.copy(conversations = outcome.conversations) }
        persistSessionState()
    }

    /**
     * **失败**时收尾：保留已收到的正文（若有），把错误作为**独立消息**追加。
     *
     * ⚠️ 为什么是独立消息而非覆盖：流式下用户**已经读到了部分内容**，
     * 覆盖掉等于让他刚看到的东西消失。追加还让「在第几个字失败的」可被用户判断。
     *
     * ⚠️ 完全没收到内容时（连第一个 delta 都没来）只保留错误消息，
     * **不留空气泡**——与改动前「只剩一条错误」的观感一致。
     */
    private fun finishWithError(
        conversationId: String,
        messageId: String,
        partialContent: String,
        partialReasoning: String?,
        errorText: String,
    ) {
        val outcome = _uiState.value.conversations.finalizeStreamingMessage(
            conversationId = conversationId,
            messageId = messageId,
            patch = StreamingFinalizePatch(
                content = partialContent,
                reasoningContent = partialReasoning,
                tokenCount = null,
            ),
        )
        val now = System.currentTimeMillis()
        _uiState.update { current ->
            val found = current.conversations.any { it.id == conversationId }
            current.copy(
                // 会话还在就追加错误消息；会话已被删除则不再污染数据
                conversations = if (found) {
                    outcome.conversations.map { conversation ->
                        if (conversation.id != conversationId) return@map conversation
                        conversation.copy(
                            messages = conversation.messages + ChatMessage(
                                role = ChatMessageRole.ERROR,
                                content = errorText,
                                timestampMillis = now,
                            ),
                            updatedAtMillis = now,
                        )
                    }
                } else {
                    current.conversations
                },
                isSending = false,
                isAgentRunning = false,
                pendingPermissionRequest = null,
            )
        }
        persistSessionState()
    }

    private fun persistSessionState() {
        val state = _uiState.value
        repository.saveSessionState(
            ChatSessionState(
                conversations = state.conversations.map { conversation ->
                    conversation.copy(
                        messages = conversation.messages.filterNot { it.isPending }
                    )
                },
                activeConversationId = state.activeConversationId,
                benchmarkRuns = state.benchmarkUi.recentRuns,
            )
        )
    }

    private fun ChatSessionState.sanitizeForRestore(): ChatSessionState {
        val sanitizedConversations = conversations.map { conversation ->
            conversation.copy(
                messages = conversation.messages.filterNot { it.isPending }
            )
        }
        val sanitizedActiveId = activeConversationId?.takeIf { id ->
            sanitizedConversations.any { it.id == id }
        }
        return copy(
            conversations = sanitizedConversations,
            activeConversationId = sanitizedActiveId,
            benchmarkRuns = benchmarkRuns,
        )
    }

    private fun ChatSessionState.nextConversationCounter(): Int {
        val nextFromTitles = conversations.maxOfOrNull { conversation ->
            newConversationTitlePattern.find(conversation.title)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        } ?: 0
        return (nextFromTitles + 1).coerceAtLeast(conversations.size + 1).coerceAtLeast(1)
    }

    private val ChatUiState.activeConversation: ChatConversation?
        get() = conversations.firstOrNull { it.id == activeConversationId }

    private fun resolveBenchmarkPreset(state: ChatUiState = _uiState.value): ChatPresetConfig? {
        return state.activeConversation?.let { resolvePreset(it, state) }
            ?: state.presets.firstOrNull { it.id == state.defaultPresetId }
            ?: state.presets.firstOrNull()
    }

    private fun List<ChatConversation>.reorderedWith(updated: ChatConversation): List<ChatConversation> {
        return listOf(updated) + filterNot { it.id == updated.id }
    }

    private fun List<ChatConversation>.updateMessage(
        messageId: String,
        transform: (ChatMessage) -> ChatMessage,
    ): List<ChatConversation> {
        return map { conversation ->
            val updatedMessages = conversation.messages.map { message ->
                if (message.id == messageId) transform(message) else message
            }
            if (updatedMessages == conversation.messages) {
                conversation
            } else {
                conversation.copy(
                    messages = updatedMessages,
                    updatedAtMillis = System.currentTimeMillis(),
                )
            }
        }.sortedByDescending { it.updatedAtMillis }
    }

    private fun ChatConversation.pendingToolApprovalMessage(): ChatMessage? {
        return messages.firstOrNull { message ->
            message.role == ChatMessageRole.ASSISTANT &&
                message.toolApprovalState == ChatToolApprovalState.PENDING
        }
    }

    private fun String.compactForLog(maxLength: Int = MAX_LOG_SNIPPET): String {
        val compact = replace(Regex("""\s+"""), " ").trim()
        return if (compact.length > maxLength) compact.take(maxLength) + "…" else compact
    }

    private fun List<ChatToolCall>.summarizeToolCalls(): String {
        if (isEmpty()) return "none"
        return joinToString(separator = "; ") { toolCall ->
            "${toolCall.name} args=${toolCall.argumentsJson.compactForLog(120)}"
        }
    }

    private fun List<ChatToolResult>.summarizeToolResults(): String {
        if (isEmpty()) return "none"
        return joinToString(separator = "; ") { result ->
            "${result.name}:${result.status} summary=${result.summary.compactForLog(80)} output=${result.outputText.compactForLog(120)}"
        }
    }
}
