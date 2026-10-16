// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/ChatScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.AbstractPrefsScope
import com.chaomixian.vflow.core.backup.BackupEnvironment
import com.chaomixian.vflow.core.backup.BackupPrefsSpec
import com.chaomixian.vflow.core.backup.BackupScope
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.LogLevel
import com.chaomixian.vflow.core.backup.PrefsBackedScope
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopeImportResult
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.chat.store.ChatConversationFiles
import com.chaomixian.vflow.core.chat.store.ChatConversationLayout
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File

/**
 * 备份/恢复**聊天配置 + 会话数据**（不含任何凭证）。
 *
 * ## 为什么 `defaultIncluded = false`
 *
 * 有**两个独立的理由**，不要合并成一句（它们的处置完全不同）：
 *
 * 1. **体积** —— 会话历史是四类里唯一会显著撑大备份体积的数据。
 *    默认勾上会让用户在不知不觉中把几十 MB 的对话写进**每一份**备份。
 * 2. **隐私** —— 对话历史本身敏感，而「换机要不要带过去」因人而异。
 *
 * 这与 `secrets` 的「敏感，需口令」**不是同一件事**：
 * `secrets` 是「**要口令才能带**」，本 scope 是「**默认不带，但带不需要口令**」。
 * 写成一句会让读者以为本 scope 也要口令。
 *
 * ## 两道数据通道（本 scope 是**混合** scope）
 *
 * | 通道 | 数据 | 走法 |
 * |---|---|---|
 * | prefs | 聊天策略（4 键，见下表） | 复用 `AbstractPrefsScope`（**组合**，不是继承） |
 * | 会话文件 | `filesDir/chat/conversations/<id>.json` 与 `benchmark_runs.json` | 本类自己按 [ChatConversationLayout] 读写 |
 *
 * 为什么必须是组合而不是继续继承 `AbstractPrefsScope`：基类的 `export` / `import`
 * 都是 `final`，只认 `specs` 里的 prefs 键 —— 会话数据是**文件**，继承就永远带不进备份。
 * 组合后：内部持一个私有实例复用原有 prefs 逻辑（**不重写一份**，避免与基类漂移，
 * 泄漏扫描也仍覆盖 chat 白名单），外层只多负责会话文件那一个元素。
 *
 * ## 键 → 收 / 不收 → 原因
 *
 * ### 收（4 键）
 *
 * | prefs | 键 | 类型 | 原因 |
 * |---|---|---|---|
 * | `module_config_prefs` | `chat_default_preset_id` | S | 默认聊天预设的选择 |
 * | `module_config_prefs` | `chat_auto_approve_tools` | B | 工具自动审批开关（**不含凭证**，是策略） |
 * | `module_config_prefs` | `chat_auto_approval_scope` | S | 自动审批范围（策略） |
 * | `chat_session_prefs` | `chat_session_state_json` | S | **旧位置**的会话历史（见下） |
 *
 * ### 不收
 *
 * | prefs | 键 | 原因 |
 * |---|---|---|
 * | `module_config_prefs` | `chat_presets_json` | 含 `apiKey` ⇒ 已在 `SecretsScope.SECRET_SLOTS`。放进普通 scope = 绕过「包含密钥」勾选 ⇒ **明文泄漏** |
 * | `module_config_prefs` | `chat_provider_configs_json` | 同上（含 `apiKey`） |
 * | `module_config_prefs` | `chat_session_state_json` | 同名的**旧位置**：迁移完成后它自然为空，收它等于把一份可能坏掉的旧 blob 重新写回 ⇒ 与迁移逻辑打架 |
 *
 * ### 为什么 `chat_session_prefs.chat_session_state_json` **仍然**在收
 *
 * 会话自本改动起改为**每会话一文件**（见 [ChatConversationLayout]）。保留这个 key
 * 只是为了「**升级后从没打开过 Chat** 的设备」：那种设备上会话还全在旧 blob 里、
 * 一个会话文件都没有，不收它就等于**悄悄漏掉全部历史**。代价只是备份里多一份旧 blob。
 * 迁移过一次之后本机该键为空 ⇒ 对后续备份零影响。
 *
 * ## 会话元素的形状
 *
 * ```json
 * { "conversations": {
 *     "version": 1,
 *     "items": [ { "id": "<会话 id>", "conversation": { …ChatConversation 的 JSON… } } ],
 *     "benchmarkRuns": [ …ChatBenchmarkRun 的 JSON… ]
 * } }
 * ```
 *
 * - 会话文件按**不透明 JSON** 搬运（`env.json.parse` / `toJson`）⇒ 本 scope 不需要
 *   认识 `ChatConversation` 类型，格式的归属仍在会话存储层；
 * - 不含 `activeConversationId`（导入后由「最近更新」回落）；
 * - 不含 `legacy/` 与 `quarantine/`（本地安全副本，带进备份会让体积翻倍）。
 */
class ChatScope : BackupScope, PrefsBackedScope {

    /**
     * prefs 部分**复用**既有的 `AbstractPrefsScope` 实现（组合）。
     *
     * 全部静态声明也放在它里面 ⇒ 外层只是**委托**，值仍然只有一份（不会两处漂移）。
     */
    private val prefsScope = ChatPrefsScope()

    override val id: String get() = prefsScope.id
    override val group: ScopeGroup get() = prefsScope.group
    override val sensitive: Boolean get() = prefsScope.sensitive
    override val defaultIncluded: Boolean get() = prefsScope.defaultIncluded
    override val importOrder: Int get() = prefsScope.importOrder
    override val dependsOn: List<String> get() = prefsScope.dependsOn
    override val specs: List<BackupPrefsSpec> get() = prefsScope.specs

    // ── 导出 ────────────────────────────────────────────────

    override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload? {
        // null ⇒ 本环境不支持（与 SecretsScope 同款）。**不是**「本次没勾选」。
        val prefsPayload = prefsScope.export(env, secrets) ?: return null
        val elements = prefsPayload.data.asJsonArray.map { it }.toMutableList()
        conversationsElement(env)?.let { elements += it }
        return ScopePayload.of(elements, prefsPayload.scrubbedFields)
    }

    /**
     * 会话文件元素。
     *
     * @return null ⇒ **不追加元素**（既无会话文件、也无 benchmark 记录）——
     *   保持「空数据集 = `count` 与 prefs 元素数一致」的既有语义。
     */
    private fun conversationsElement(env: BackupEnvironment): JsonElement? {
        val filesRoot = env.filesRoot
        if (filesRoot == null) {
            // 记 W 而不是静默跳过：纯 JVM 测试环境本就该如此，但生产环境拿不到
            // filesRoot 就意味着「用户以为备了会话、其实一份都没有」。
            env.log(LogLevel.W, TAG, "本环境不支持 filesRoot ⇒ 本次备份不含会话文件")
            return null
        }

        val items = JsonArray()
        for ((id, file) in ChatConversationFiles.conversationFiles(filesRoot)) {
            if (!ChatConversationLayout.isSafeConversationId(id)) {
                env.log(
                    LogLevel.W, TAG,
                    "会话文件 '${file.name}' 的文件名不是安全的会话 id，已跳过（文件留在本地）"
                )
                continue
            }
            val text = runCatching { file.readText() }.getOrElse { error ->
                env.log(LogLevel.W, TAG, "会话文件 '${file.name}' 读取失败，已跳过", error)
                null
            } ?: continue
            val node = runCatching { JsonParser.parseString(text) }.getOrElse { error ->
                env.log(LogLevel.W, TAG, "会话文件 '${file.name}' 不是合法 JSON，已跳过", error)
                null
            } ?: continue
            items.add(
                JsonObject().apply {
                    addProperty(KEY_ID, id)
                    add(KEY_CONVERSATION, node)
                }
            )
        }

        val runsNode = readBenchmarkRunsElement(env, filesRoot)

        if (items.size() == 0 && runsNode == null) return null

        return JsonObject().apply {
            add(
                KEY_CONVERSATIONS,
                JsonObject().apply {
                    addProperty(KEY_VERSION, ChatConversationLayout.SCHEMA_VERSION)
                    add(KEY_ITEMS, items)
                    if (runsNode != null) add(KEY_BENCHMARK_RUNS, runsNode)
                }
            )
        }
    }

    /** 读 `benchmark_runs.json` 的 `runs` 数组（读不出来 ⇒ null + 记 W，不半途而废）。 */
    private fun readBenchmarkRunsElement(
        env: BackupEnvironment,
        filesRoot: File,
    ): JsonArray? {
        val file = ChatConversationLayout.benchmarkRunsFile(filesRoot)
        if (!file.isFile) return null
        val text = runCatching { file.readText() }.getOrElse { error ->
            env.log(LogLevel.W, TAG, "benchmark 记录读取失败，本次备份不含它", error)
            return null
        }
        val root = runCatching { JsonParser.parseString(text) }.getOrNull()
            ?.takeIf { it.isJsonObject }?.asJsonObject
        val runs = root?.get(KEY_RUNS)?.takeIf { it.isJsonArray }?.asJsonArray
        if (runs == null) {
            env.log(LogLevel.W, TAG, "benchmark 记录形状不对（缺 runs 数组），本次备份不含它")
        }
        return runs
    }

    // ── 导入 ────────────────────────────────────────────────

    override fun import(
        env: BackupEnvironment,
        payload: ScopePayload?,
        mode: ImportMode
    ): ScopeImportResult {
        // prefs 部分先走既有实现（它负责 null payload / 非数组 / 无 prefs 接缝 /
        // 白名单过滤 / REPLACE 只删白名单内的键这一整套语义）。
        val prefsResult = prefsScope.import(env, payload, mode)
        if (prefsResult.status != ImportStatus.IMPORTED) return prefsResult

        val array = payload!!.data.asJsonArray
        val element = array
            .firstOrNull { it.isJsonObject && it.asJsonObject.has(KEY_CONVERSATIONS) }
            ?.asJsonObject
            ?: return prefsResult
        val conversations = element.get(KEY_CONVERSATIONS)
            ?.takeIf { it.isJsonObject }?.asJsonObject
            ?: return prefsResult

        val version = conversations.get(KEY_VERSION)?.takeIf { it.isJsonPrimitive }?.asInt
        if (version != null && version > ChatConversationLayout.SCHEMA_VERSION) {
            env.log(
                LogLevel.W, TAG,
                "备份里的会话 version=$version 高于本版支持的 " +
                    "${ChatConversationLayout.SCHEMA_VERSION}，尽力导入"
            )
        }

        val filesRoot = env.filesRoot
        if (filesRoot == null) {
            env.log(LogLevel.W, TAG, "本环境不支持 filesRoot ⇒ 跳过会话文件（未导入）")
            return prefsResult.copy(skipped = prefsResult.skipped + 1)
        }

        var imported = prefsResult.imported
        var skipped = prefsResult.skipped
        val backupIds = mutableSetOf<String>()

        val items = conversations.get(KEY_ITEMS)?.takeIf { it.isJsonArray }?.asJsonArray
        if (items != null) {
            for (item in items) {
                val obj = item.takeIf { it.isJsonObject }?.asJsonObject
                if (obj == null) {
                    skipped++
                    continue
                }
                val id = obj.get(KEY_ID)?.takeIf { it.isJsonPrimitive }?.asString
                val node = obj.get(KEY_CONVERSATION)
                if (node == null || node.isJsonNull) {
                    skipped++
                    continue
                }
                // 备份是**外部输入**：不安全 id（`../x` 之类）会写到会话目录之外。
                if (id == null || !ChatConversationLayout.isSafeConversationId(id)) {
                    env.log(LogLevel.W, TAG, "备份里的会话 id '$id' 不安全，已跳过")
                    skipped++
                    continue
                }
                backupIds += id
                val target = ChatConversationLayout.conversationFile(filesRoot, id)
                if (ChatConversationFiles.writeAtomically(target, env.json.toJson(node))) {
                    imported++
                } else {
                    env.log(LogLevel.W, TAG, "会话 '$id' 导入写盘失败")
                    skipped++
                }
            }
        }

        // benchmark 记录按「入参覆盖」写回（与旧 blob 整键覆盖的语义一致）。
        val runsNode = conversations.get(KEY_BENCHMARK_RUNS)
        if (runsNode != null && runsNode.isJsonArray) {
            val text = env.json.toJson(
                JsonObject().apply {
                    addProperty(KEY_VERSION, ChatConversationLayout.SCHEMA_VERSION)
                    add(KEY_RUNS, runsNode)
                }
            )
            val target = ChatConversationLayout.benchmarkRunsFile(filesRoot)
            if (ChatConversationFiles.writeAtomically(target, text)) {
                imported++
            } else {
                env.log(LogLevel.W, TAG, "benchmark 记录导入写盘失败")
                skipped++
            }
        }

        if (mode == ImportMode.REPLACE) {
            // 只在备份**确实带了** conversations 元素时才删本地独有会话 —— 与 prefs 侧的
            // 既有语义对齐：「文件不在备份里」＝本次不是 scope 成员，不是「删掉它」。
            // 否则导入一份**改造前导出的旧备份**（它没有这个元素）会静默清空全部会话。
            for ((id, file) in ChatConversationFiles.conversationFiles(filesRoot)) {
                if (id in backupIds) continue
                if (!ChatConversationLayout.isSafeConversationId(id)) continue
                ChatConversationFiles.deleteQuietly(file)
            }
        }

        // 刻意**不写也不删** `index.json`：索引归会话存储层所有，
        // 它会在下一次 `save()` 时按真实磁盘内容重建（避免两处各写一份索引而漂移）。
        return prefsResult.copy(
            status = ImportStatus.IMPORTED,
            imported = imported,
            skipped = skipped,
        )
    }

    /**
     * prefs 部分的静态声明与读写 —— 即原 `ChatScope` 里那份 `AbstractPrefsScope`。
     *
     * 它**只被 [ChatScope] 以组合方式使用**，不注册进 `BackupScopeRegistry`
     * （注册表里只有外层那个混合 scope），否则备份 UI 会出现两个 chat 条目。
     */
    private class ChatPrefsScope : AbstractPrefsScope() {

        override val id: String = ID
        override val group: ScopeGroup = ScopeGroup.CONFIG
        override val sensitive: Boolean = false

        /** 体积 + 隐私，两个理由见外层类 KDoc。 */
        override val defaultIncluded: Boolean = false

        override val importOrder: Int = 50
        override val dependsOn: List<String> = emptyList()

        override val scopeTag: String = TAG

        override val specs: List<BackupPrefsSpec> = listOf(
            BackupPrefsSpec(
                MODULE_CONFIG_PREFS,
                exactKeys = setOf(
                    "chat_default_preset_id",
                    "chat_auto_approve_tools",
                    "chat_auto_approval_scope",
                )
            ),
            BackupPrefsSpec(
                CHAT_SESSION_PREFS,
                exactKeys = setOf("chat_session_state_json")
            ),
        )
    }

    companion object {
        const val ID = "chat"

        private const val TAG = "ChatScope"

        /** 与 `ModuleConfigActivity.PREFS_NAME` 逐字一致。 */
        private const val MODULE_CONFIG_PREFS = "module_config_prefs"

        /** 与 `ChatPresetRepository.CHAT_SESSION_PREFS_NAME` 逐字一致。 */
        private const val CHAT_SESSION_PREFS = "chat_session_prefs"

        val MODULE_CONFIG_KEYS: Set<String> = setOf(
            "chat_default_preset_id",
            "chat_auto_approve_tools",
            "chat_auto_approval_scope",
        )
        val SESSION_KEYS: Set<String> = setOf("chat_session_state_json")

        // 会话元素的键名（形状见类 KDoc）；"version" / "runs" 与
        // benchmark_runs.json 的两个键名一致。
        private const val KEY_CONVERSATIONS = "conversations"
        private const val KEY_ITEMS = "items"
        private const val KEY_ID = "id"
        private const val KEY_CONVERSATION = "conversation"
        private const val KEY_BENCHMARK_RUNS = "benchmarkRuns"
        private const val KEY_RUNS = "runs"
        private const val KEY_VERSION = "version"
    }
}
