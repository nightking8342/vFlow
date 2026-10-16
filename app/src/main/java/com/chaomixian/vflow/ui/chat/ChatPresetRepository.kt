package com.chaomixian.vflow.ui.chat

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.content.edit
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class ChatPresetRepository(context: Context) {
    companion object {
        private const val PREFS_NAME = "module_config_prefs"
        private const val CHAT_SESSION_PREFS_NAME = "chat_session_prefs"
        private const val KEY_CHAT_PRESETS_JSON = "chat_presets_json"
        private const val KEY_CHAT_PROVIDER_CONFIGS_JSON = "chat_provider_configs_json"
        private const val KEY_CHAT_DEFAULT_PRESET_ID = "chat_default_preset_id"
        private const val KEY_CHAT_SESSION_STATE_JSON = "chat_session_state_json"
        private const val KEY_CHAT_AUTO_APPROVE_TOOLS = "chat_auto_approve_tools"
        private const val KEY_CHAT_AUTO_APPROVAL_SCOPE = "chat_auto_approval_scope"

        /** 日志 TAG（fork 新增，仅用于会话持久化路径的 DebugLogger 调用）。 */
        private const val TAG = "ChatPresetRepository"

        const val BUILTIN_OPENAI_ID = "builtin-openai"
        const val BUILTIN_DEEPSEEK_ID = "builtin-deepseek"
        const val BUILTIN_ANTHROPIC_ID = "builtin-anthropic"
        const val BUILTIN_OPENROUTER_ID = "builtin-openrouter"
        const val BUILTIN_OLLAMA_ID = "builtin-ollama"

        /**
         * 「会话数据解析失败」的可见提示**同一进程内只弹一次**。
         *
         * 放在 companion（而非实例字段）：`ModelConfigActivity` 与 `ChatViewModelHolder`
         * 会各建一个 repository，用实例字段会在两处各弹一次。
         */
        private val sessionLoadIssueNotified = AtomicBoolean(false)
    }

    private val appContext: Context = context.applicationContext

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val sessionPrefs =
        context.applicationContext.getSharedPreferences(CHAT_SESSION_PREFS_NAME, Context.MODE_PRIVATE)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * 会话持久化层（fork 新增）—— 每会话一文件 + 轻量索引，见 [ChatConversationStore]。
     *
     * 旧 blob 的读写通过 lambda 注入：store 因此是**纯 JVM**（可在临时目录里单测），
     * 不必知道 `SharedPreferences` 的存在。
     */
    private val store = ChatConversationStore(
        filesRoot = appContext.filesDir,
        readLegacyBlob = {
            sessionPrefs.getString(KEY_CHAT_SESSION_STATE_JSON, null)
                ?: prefs.getString(KEY_CHAT_SESSION_STATE_JSON, null)
        },
        clearLegacyBlob = {
            // ⚠️ 用 `commit()` 的返回值判成败（`androidx.core.content.edit` 返回 Unit）。
            //    清不掉时**宁可把旧数据留在 prefs 里**，也不假装清干净了。
            //    两个键**都要尝试**清理（不用 `&&` 短路，否则第一个失败就不动第二个）。
            val sessionCleared = sessionPrefs.edit().remove(KEY_CHAT_SESSION_STATE_JSON).commit()
            val moduleCleared = prefs.edit().remove(KEY_CHAT_SESSION_STATE_JSON).commit()
            sessionCleared && moduleCleared
        },
        log = { level, message, error ->
            when (level) {
                ChatStoreLogLevel.WARN -> DebugLogger.w("ChatPresetRepository", message, error)
                ChatStoreLogLevel.ERROR -> DebugLogger.e("ChatPresetRepository", message, error)
            }
        },
    )

    fun getProviderConfig(providerConfigId: String): ChatProviderConfig? {
        return loadState().providers.firstOrNull { it.id == providerConfigId }
    }

    fun getPresets(): List<ChatPresetConfig> = loadState().presets

    fun getProviderConfigs(): List<ChatProviderConfig> = loadState().providers

    fun getDefaultPresetId(): String? {
        return prefs.getString(KEY_CHAT_DEFAULT_PRESET_ID, null)
    }

    /**
     * 读回会话状态。
     *
     * ## ⚠️⚠️ 为什么**不能**再用 `runCatching { … }.getOrDefault(ChatSessionState())`
     *
     * 那是本仓库的一处既有缺陷（本例修掉的就是它）：一处 JSON 坏掉 ⇒ 这里返回**空状态**
     * ⇒ `ChatViewModel.init → persistSessionState()` 紧接着把空状态**写回**
     * ⇒ **全部会话被抹掉**，而用户**看不到任何提示**（本项目没有版本历史、不可恢复）。
     *
     * 现在：坏数据由 [ChatConversationStore] **原样留存**（复制一份进
     * `filesDir/chat/quarantine/`）并产出 [ChatSessionLoadIssue]，这里
     * ① 记日志（W/E）② 弹**一次**用户可见提示（复用既有三语字符串），
     * 然后**尽力返回能读出来的那些会话**。
     */
    fun getSessionState(): ChatSessionState {
        val state = store.load()
        val issues = store.loadIssues
        if (issues.isNotEmpty()) {
            issues.forEach { issue ->
                val message = "${issue.kind}: ${issue.detail}"
                if (issue.kind == ChatSessionLoadIssueKind.LEGACY_BLOB_UNPARSEABLE) {
                    DebugLogger.e(TAG, message)
                } else {
                    DebugLogger.w(TAG, message)
                }
            }
            notifySessionLoadIssueOnce()
        }
        return state
    }

    /**
     * 弹一次「会话数据解析失败、原文已保留」的提示。
     *
     * 约束（父会话裁决）：**只在确实发生解析失败时**弹（调用点已判 `loadIssues` 非空）；
     * **同一进程内最多一次**（[sessionLoadIssueNotified]，与 repository 实例个数无关）。
     * `DebugLogger` 与 `store.loadIssues` 都保留 ⇒ 将来 UI 层要接管展示时不必再改这里。
     */
    private fun notifySessionLoadIssueOnce() {
        if (!sessionLoadIssueNotified.compareAndSet(false, true)) return
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                appContext.getString(
                    R.string.toast_load_failed,
                    "chat 会话数据解析失败，原始数据已保留（chat/quarantine）"
                ),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun getAutoApprovalScope(): ChatToolAutoApprovalScope {
        prefs.getString(KEY_CHAT_AUTO_APPROVAL_SCOPE, null)?.let { raw ->
            return ChatToolAutoApprovalScope.fromStorage(raw)
        }
        return if (prefs.getBoolean(KEY_CHAT_AUTO_APPROVE_TOOLS, false)) {
            ChatToolAutoApprovalScope.STANDARD
        } else {
            ChatToolAutoApprovalScope.OFF
        }
    }

    fun setAutoApprovalScope(scope: ChatToolAutoApprovalScope) {
        prefs.edit {
            putString(KEY_CHAT_AUTO_APPROVAL_SCOPE, scope.storageValue)
            putBoolean(KEY_CHAT_AUTO_APPROVE_TOOLS, scope != ChatToolAutoApprovalScope.OFF)
        }
    }

    /**
     * 落盘（每会话一文件 + 轻量索引 + 原子写 + 按会话 diff）。
     *
     * 旧 blob 的清空**改由 [ChatConversationStore] 决定**（只有迁移成功提交后才清），
     * 不再像改造前那样无条件 `remove` —— 无条件 remove 会把「解析失败但还没救回来」的
     * 数据直接删掉。
     */
    fun saveSessionState(state: ChatSessionState) {
        val report = store.save(state)
        DebugLogger.d(
            TAG,
            "会话落盘：写入 ${report.writtenConversations} 条、删除 ${report.deletedConversations} 条、" +
                "索引 ${if (report.indexWritten) "已写" else "未变"}、" +
                "benchmark ${if (report.benchmarkRunsWritten) "已写" else "未变"}、" +
                "旧 blob 清空 ${report.legacyBlobCleared}"
        )
    }

    fun saveProviderConfig(config: ChatProviderConfig): ChatProviderConfig {
        val state = loadState()
        val normalized = config.normalize()
        val updatedProviders = state.providers.replaceById(normalized)
        val updatedPresets = state.presets.map { preset ->
            if (preset.providerConfigId == normalized.id) {
                preset.syncWithProvider(normalized)
            } else {
                preset
            }
        }
        persistState(updatedProviders, updatedPresets)
        return normalized
    }

    fun deleteProviderConfig(providerConfigId: String) {
        val state = loadState()
        val updatedProviders = state.providers.filterNot { it.id == providerConfigId }
        val removedPresetIds = state.presets
            .filter { it.providerConfigId == providerConfigId }
            .map { it.id }
            .toSet()
        val updatedPresets = state.presets.filterNot { it.providerConfigId == providerConfigId }
        persistState(updatedProviders, updatedPresets)
        if (removedPresetIds.contains(getDefaultPresetId())) {
            setDefaultPresetId(updatedPresets.firstOrNull()?.id)
        }
    }

    fun savePreset(preset: ChatPresetConfig): ChatPresetConfig {
        val state = loadState()
        val provider = state.providers.firstOrNull { it.id == preset.providerConfigId }
            ?: ChatProviderConfig(
                id = preset.providerConfigId.ifBlank { UUID.randomUUID().toString() },
                name = preset.providerEnum.displayName,
                provider = preset.provider,
                apiKey = preset.apiKey,
                baseUrl = preset.baseUrl,
                systemPrompt = preset.systemPrompt,
                temperature = preset.temperature,
                useResponsesApi = preset.useResponsesApi,
            ).normalize()

        val normalizedPreset = preset.normalize(provider)
        val updatedProviders = state.providers.replaceById(provider)
        val updatedPresets = state.presets.replaceById(normalizedPreset)
        persistState(updatedProviders, updatedPresets)
        if (getDefaultPresetId().isNullOrBlank()) {
            setDefaultPresetId(normalizedPreset.id)
        }
        return normalizedPreset
    }

    fun deletePreset(presetId: String) {
        val state = loadState()
        val updated = state.presets.filterNot { it.id == presetId }
        persistState(state.providers, updated)
        if (getDefaultPresetId() == presetId) {
            setDefaultPresetId(updated.firstOrNull()?.id)
        }
    }

    fun setDefaultPresetId(presetId: String?) {
        prefs.edit {
            if (presetId.isNullOrBlank()) {
                remove(KEY_CHAT_DEFAULT_PRESET_ID)
            } else {
                putString(KEY_CHAT_DEFAULT_PRESET_ID, presetId)
            }
        }
    }

    fun registerChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.registerOnSharedPreferenceChangeListener(listener)
    }

    fun unregisterChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) {
        prefs.unregisterOnSharedPreferenceChangeListener(listener)
    }

    private fun loadState(): RepositoryState {
        val rawPresets = decodeList(KEY_CHAT_PRESETS_JSON)
        val rawProviders = decodeProviderList(KEY_CHAT_PROVIDER_CONFIGS_JSON)
        val builtInProviders = builtInProviderConfigs()
        val builtInIds = builtInProviders.map { it.id }.toSet()
        val rawProvidersById = rawProviders.associateBy { it.id }

        val normalizedProviders = buildMap {
            builtInProviders.forEach { builtIn ->
                val stored = rawProvidersById[builtIn.id]
                put(
                    builtIn.id,
                    (stored ?: builtIn).copy(isBuiltIn = true).normalize()
                )
            }
            rawProviders
                .filterNot { it.id in builtInIds }
                .forEach { provider ->
                    put(provider.id, provider.normalize())
                }
        }.toMutableMap()

        val normalizedPresets = rawPresets.map { rawPreset ->
            val providerId = rawPreset.providerConfigId.ifBlank { rawPreset.id }
            val linkedProvider = normalizedProviders[providerId] ?: ChatProviderConfig(
                id = providerId,
                name = rawPreset.providerEnum.displayName,
                provider = rawPreset.provider,
                apiKey = rawPreset.apiKey,
                baseUrl = rawPreset.baseUrl,
                systemPrompt = rawPreset.systemPrompt,
                temperature = rawPreset.temperature,
                useResponsesApi = rawPreset.useResponsesApi,
                isBuiltIn = false,
            ).normalize().also { normalizedProviders[it.id] = it }
            rawPreset.normalize(linkedProvider)
        }

        val providers = normalizedProviders.values.sortedWith(
            compareBy<ChatProviderConfig> { provider ->
                if (provider.isBuiltIn) {
                    builtInSortOrder(provider.id)
                } else {
                    Int.MAX_VALUE
                }
            }.thenBy { it.name.lowercase() }
        )
        val presets = normalizedPresets.sortedBy { it.name.lowercase() }

        val shouldPersist = rawProviders.size != providers.size ||
            rawPresets != presets ||
            rawProviders.sortedBy { it.id } != providers.sortedBy { it.id }
        if (shouldPersist) {
            persistState(providers, presets)
        }

        return RepositoryState(
            providers = providers,
            presets = presets,
        )
    }

    private fun decodeList(key: String): List<ChatPresetConfig> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatPresetConfig>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun decodeProviderList(key: String): List<ChatProviderConfig> {
        val raw = prefs.getString(key, null).orEmpty()
        if (raw.isBlank()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ChatProviderConfig>>(raw)
        }.getOrDefault(emptyList())
    }

    private fun persistState(
        providers: List<ChatProviderConfig>,
        presets: List<ChatPresetConfig>,
    ) {
        prefs.edit {
            putString(KEY_CHAT_PROVIDER_CONFIGS_JSON, json.encodeToString(providers))
            putString(KEY_CHAT_PRESETS_JSON, json.encodeToString(presets))
        }
    }

    private fun ChatProviderConfig.normalize(): ChatProviderConfig {
        val resolvedProvider = providerEnum
        val normalizedBaseUrl = normalizeBaseUrl(
            provider = resolvedProvider,
            raw = baseUrl,
        )
        return copy(
            id = id.ifBlank { UUID.randomUUID().toString() },
            name = name.trim().ifBlank { resolvedProvider.displayName },
            provider = resolvedProvider.storageValue,
            apiKey = apiKey.trim(),
            baseUrl = normalizedBaseUrl,
            systemPrompt = systemPrompt.trim().ifBlank { ChatPresetConfig.DEFAULT_SYSTEM_PROMPT },
            temperature = temperature.coerceIn(0.0, 2.0),
            useResponsesApi = useResponsesApi && resolvedProvider == ChatProvider.OPENAI,
            isBuiltIn = isBuiltIn,
        )
    }

    private fun ChatPresetConfig.normalize(providerConfig: ChatProviderConfig): ChatPresetConfig {
        val resolvedProvider = providerConfig.providerEnum
        return copy(
            id = id.ifBlank { UUID.randomUUID().toString() },
            providerConfigId = providerConfig.id,
            name = name.trim().ifBlank { model.trim().ifBlank { resolvedProvider.defaultModel } },
            provider = resolvedProvider.storageValue,
            model = model.trim().ifBlank { resolvedProvider.defaultModel },
            apiKey = providerConfig.apiKey,
            baseUrl = providerConfig.baseUrl,
            systemPrompt = providerConfig.systemPrompt,
            temperature = providerConfig.temperature,
            useResponsesApi = providerConfig.useResponsesApi,
        )
    }

    private fun ChatPresetConfig.syncWithProvider(providerConfig: ChatProviderConfig): ChatPresetConfig {
        return normalize(providerConfig).copy(name = name)
    }

    private fun List<ChatPresetConfig>.replaceById(item: ChatPresetConfig): List<ChatPresetConfig> {
        val index = indexOfFirst { it.id == item.id }
        return if (index >= 0) {
            toMutableList().apply { set(index, item) }
        } else {
            this + item
        }
    }

    private fun List<ChatProviderConfig>.replaceById(item: ChatProviderConfig): List<ChatProviderConfig> {
        val index = indexOfFirst { it.id == item.id }
        return if (index >= 0) {
            toMutableList().apply { set(index, item) }
        } else {
            this + item
        }
    }

    private data class RepositoryState(
        val providers: List<ChatProviderConfig>,
        val presets: List<ChatPresetConfig>,
    )

    private fun builtInProviderConfigs(): List<ChatProviderConfig> {
        return listOf(
            builtInProvider(id = BUILTIN_OPENAI_ID, provider = ChatProvider.OPENAI),
            builtInProvider(id = BUILTIN_DEEPSEEK_ID, provider = ChatProvider.DEEPSEEK),
            builtInProvider(id = BUILTIN_ANTHROPIC_ID, provider = ChatProvider.ANTHROPIC),
            builtInProvider(id = BUILTIN_OPENROUTER_ID, provider = ChatProvider.OPENROUTER),
            builtInProvider(id = BUILTIN_OLLAMA_ID, provider = ChatProvider.OLLAMA),
        )
    }

    private fun builtInProvider(id: String, provider: ChatProvider): ChatProviderConfig {
        return ChatProviderConfig(
            id = id,
            name = provider.displayName,
            provider = provider.storageValue,
            baseUrl = provider.defaultBaseUrl,
            isBuiltIn = true,
        )
    }

    private fun builtInSortOrder(providerId: String): Int {
        return when (providerId) {
            BUILTIN_OPENAI_ID -> 0
            BUILTIN_DEEPSEEK_ID -> 1
            BUILTIN_ANTHROPIC_ID -> 2
            BUILTIN_OPENROUTER_ID -> 3
            BUILTIN_OLLAMA_ID -> 4
            else -> Int.MAX_VALUE
        }
    }

    private fun normalizeBaseUrl(
        provider: ChatProvider,
        raw: String,
    ): String {
        val normalized = raw.trim().ifBlank { provider.defaultBaseUrl }.removeSuffix("/")
        return if (provider == ChatProvider.OPENAI && normalized == "https://api.openai.com") {
            provider.defaultBaseUrl.removeSuffix("/")
        } else {
            normalized
        }
    }
}
