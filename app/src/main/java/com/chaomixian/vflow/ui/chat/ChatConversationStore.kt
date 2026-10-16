// 文件: main/java/com/chaomixian/vflow/ui/chat/ChatConversationStore.kt
package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.chat.store.ChatConversationFiles
import com.chaomixian.vflow.core.chat.store.ChatConversationLayout
import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 会话存储层的日志级别 —— 刻意不复用 `DebugLogger`，否则本文件就不是纯 JVM 了。 */
enum class ChatStoreLogLevel { WARN, ERROR }

/**
 * 一次 [ChatConversationStore.save] 的**实际动作**报告。
 *
 * ⚠️ 它是「按会话 diff」这件事的**可断言证据**：只改一条会话时，
 * `writtenConversations` 必须是 1 而不是「会话总数」。
 * 改造前每次落盘都要把**全部**会话序列化进一个 prefs 键（O(全部) 写放大）。
 */
data class SaveReport(
    val writtenConversations: Int = 0,
    val deletedConversations: Int = 0,
    val indexWritten: Boolean = false,
    val benchmarkRunsWritten: Boolean = false,
    val legacyBlobCleared: Boolean = false,
)

/**
 * 会话持久化层：**每会话一文件 + 轻量索引**（纯 JVM，无 `android.*`）。
 *
 * ## 它替掉了什么
 *
 * 改造前：全部会话挤在 `chat_session_prefs` 的**一个** JSON 键里，
 * 每次落盘全量序列化（O(全部) 写放大），且读侧
 * `runCatching { decode(raw) }.getOrDefault(ChatSessionState())`
 * —— **一处坏 JSON ⇒ 静默返回空 ⇒ 全部会话被抹掉**（用户看不到任何提示）。
 *
 * 现在：
 * 1. 磁盘布局见 [ChatConversationLayout]（会话文件是唯一真源，索引只放元数据）；
 * 2. 写时**按会话 diff**（只重写变化的那一个）+ 原子写；
 * 3. 旧 blob **一次性迁移**（成功后清空并留一份 `legacy/` 原文副本）；
 * 4. 解析失败**不再返回空**：坏数据留存到 `quarantine/`、加载尽力而为、
 *    并产出 [ChatSessionLoadIssue]（由调用方变成用户可见提示）。
 *
 * ## 内存模型不变
 *
 * [load] 仍**全量**读回所有会话（本次硬约束）——`ChatViewModel` 零改动。
 * 索引不作为加载真源（取舍：加载仍要读全部文件；收益：索引损坏/丢失不丢任何会话）。
 *
 * ## 线程模型
 *
 * 与改造前一致：只在调用线程同步执行（`ChatViewModel.init` 与每次落盘都在主线程），
 * **不加锁、不引协程**。
 */
class ChatConversationStore(
    private val filesRoot: File,
    /** 读旧 blob：`chat_session_prefs` 优先、`module_config_prefs` 兜底。 */
    private val readLegacyBlob: () -> String?,
    /** 清空旧 blob（两个键一起 remove）；返回是否真的成功（`commit()` 的结果）。 */
    private val clearLegacyBlob: () -> Boolean,
    private val log: (level: ChatStoreLogLevel, message: String, error: Throwable?) -> Unit =
        { _, _, _ -> },
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    private val issues = mutableListOf<ChatSessionLoadIssue>()

    /** 最近一次加载产生的问题（测试 + 将来 UI 可读）。 */
    val loadIssues: List<ChatSessionLoadIssue> get() = issues.toList()

    private var loaded = false
    private var state = ChatSessionState()

    /** 索引/benchmark 的「上次写出的文本」——「内容有变才写」的判据。 */
    private var lastIndexText: String? = null
    private var lastBenchmarkText: String? = null

    /** `benchmark_runs.json` 在加载时是否存在（不存在且本次也没有 runs ⇒ 不凭空造文件）。 */
    private var benchmarkFilePresent = false

    /** 加载时读到的旧 blob 原文（null ⇒ 当时没有 blob）。清理前要比对它**逐字未变**。 */
    private var legacyBlobTextAtLoad: String? = null

    /** 旧 blob 是否**允许**被清空：解析成功过，或本来就不存在。解析失败的 blob **永不清**。 */
    private var legacyBlobClearable = false

    /**
     * 读回全部会话（幂等：一个实例只真正读一次盘）。
     *
     * 首次调用时顺带完成**一次性迁移**（见类注释）。**绝不返回空来掩盖解析失败**。
     */
    fun load(): ChatSessionState {
        ensureLoaded()
        return state
    }

    /** 强制重读（测试 / 外部改动后用）。 */
    fun reload() {
        loaded = false
        ensureLoaded()
    }

    /**
     * 落盘。内部先 [ensureLoaded]（首次调用读盘一次，取得 diff 基线）。
     *
     * 只重写**与上次加载/写入不同**的会话文件；只删「上次已知、本次消失」的会话文件
     * （**不做目录扫描删除** —— 备份导入写进来的文件不属于内存里的旧状态，
     * 扫描删除会把它们误删）。
     */
    fun save(state: ChatSessionState): SaveReport {
        ensureLoaded()
        val previousById = this.state.conversations.associateBy { it.id }

        val nextById = LinkedHashMap<String, ChatConversation>()
        for (conversation in state.conversations) {
            if (!ChatConversationLayout.isSafeConversationId(conversation.id)) {
                log(
                    ChatStoreLogLevel.WARN,
                    "会话 id '${conversation.id}' 不安全（含路径分隔符/.. 等），已跳过落盘（内存里仍可用）",
                    null
                )
                continue
            }
            nextById[conversation.id] = conversation
        }

        var written = 0
        var deleted = 0
        val failedIds = mutableSetOf<String>()

        for ((id, conversation) in nextById) {
            if (previousById[id] == conversation) continue
            val file = ChatConversationLayout.conversationFile(filesRoot, id)
            if (ChatConversationFiles.writeAtomically(file, json.encodeToString(conversation))) {
                written++
            } else {
                failedIds += id
                log(ChatStoreLogLevel.ERROR, "会话 '$id' 落盘失败（旧文件保留）", null)
            }
        }

        for (id in previousById.keys) {
            if (id in nextById) continue
            if (!ChatConversationLayout.isSafeConversationId(id)) continue
            val file = ChatConversationLayout.conversationFile(filesRoot, id)
            if (ChatConversationFiles.deleteQuietly(file)) {
                deleted++
            } else {
                log(ChatStoreLogLevel.WARN, "会话 '$id' 的文件删除失败（数据仍在磁盘上）", null)
            }
        }

        val indexText = renderIndex(
            conversations = nextById.values.toList(),
            activeConversationId = state.activeConversationId?.takeIf { it in nextById },
        )
        var indexWritten = false
        if (indexText != lastIndexText) {
            if (ChatConversationFiles.writeAtomically(
                    ChatConversationLayout.indexFile(filesRoot), indexText
                )
            ) {
                lastIndexText = indexText
                indexWritten = true
            } else {
                log(ChatStoreLogLevel.WARN, "索引 index.json 写盘失败（下次落盘会重试）", null)
            }
        }

        val runsText = renderBenchmarkRuns(state.benchmarkRuns)
        var benchmarkRunsWritten = false
        val runsShouldExist = state.benchmarkRuns.isNotEmpty() || benchmarkFilePresent
        if (runsText != lastBenchmarkText && runsShouldExist) {
            if (ChatConversationFiles.writeAtomically(
                    ChatConversationLayout.benchmarkRunsFile(filesRoot), runsText
                )
            ) {
                lastBenchmarkText = runsText
                benchmarkFilePresent = true
                benchmarkRunsWritten = true
            } else {
                log(ChatStoreLogLevel.WARN, "benchmark 记录写盘失败（下次落盘会重试）", null)
            }
        }

        val legacyBlobCleared = maybeClearLegacyBlob()

        // 新基线：写失败的会话**退回旧版本**（没有旧版本就干脆不进基线）——
        // 否则下次 save() 会以为它已在盘上而不再重试，那是「静默丢数据」。
        val baseline = mutableListOf<ChatConversation>()
        for (conversation in state.conversations) {
            if (conversation.id in failedIds) {
                previousById[conversation.id]?.let { baseline += it }
            } else if (conversation.id in nextById) {
                baseline += conversation
            }
        }
        this.state = state.copy(conversations = baseline)

        return SaveReport(
            writtenConversations = written,
            deletedConversations = deleted,
            indexWritten = indexWritten,
            benchmarkRunsWritten = benchmarkRunsWritten,
            legacyBlobCleared = legacyBlobCleared,
        )
    }

    // ── 读盘 ────────────────────────────────────────────────

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        issues.clear()
        state = readFromDisk()
    }

    private fun readFromDisk(): ChatSessionState {
        val conversations = readConversationFiles()
        val benchmarkRuns = readBenchmarkRuns()
        // ⚠️ 必须在迁移**之前**取走这个标记：迁移自己会把缺的 benchmark 文件写出来，
        //    之后再读它就会得到「文件本来就在」的假象，于是旧 blob 里的记录被丢掉。
        val benchmarkFileExistedBeforeMigration = benchmarkFilePresent
        val index = readIndex()

        val migration = migrateLegacyBlob(
            knownConversations = conversations,
            indexUsable = index != null,
        )

        val all = conversations + migration.conversations
        val runs = if (!benchmarkFileExistedBeforeMigration) {
            migration.benchmarkRuns
        } else {
            benchmarkRuns
        }
        val sorted = all.sortedByDescending { it.updatedAtMillis }
        val activeId = (index?.activeConversationId ?: migration.activeConversationId)
            ?.takeIf { id -> sorted.any { it.id == id } }

        return ChatSessionState(
            conversations = sorted,
            activeConversationId = activeId,
            benchmarkRuns = runs,
        )
    }

    private fun readConversationFiles(): List<ChatConversation> {
        val out = mutableListOf<ChatConversation>()
        for ((id, file) in ChatConversationFiles.conversationFiles(filesRoot)) {
            if (!ChatConversationLayout.isSafeConversationId(id)) {
                log(
                    ChatStoreLogLevel.WARN,
                    "会话文件 '${file.name}' 的文件名不是安全的会话 id，已忽略（文件保留在磁盘上）",
                    null
                )
                continue
            }
            val text = try {
                file.readText()
            } catch (e: IOException) {
                log(ChatStoreLogLevel.WARN, "会话文件 '${file.name}' 读取失败，已跳过", e)
                continue
            }
            val parsed = runCatching { json.decodeFromString<ChatConversation>(text) }.getOrNull()
            if (parsed == null) {
                quarantineFile(
                    file,
                    ChatSessionLoadIssueKind.CONVERSATION_FILE_UNPARSEABLE,
                    "会话文件 '${file.name}' 解析失败，原文已留存（该会话本次不可见）",
                )
                continue
            }
            if (parsed.id == id) {
                out += parsed
            } else {
                // 文件名是存储层的唯一 id 来源（落盘路径由它拼出来）⇒ 不一致时以文件名为准，
                // 否则同一份数据会被加载成两个 id，写回时又各写一份。
                log(
                    ChatStoreLogLevel.WARN,
                    "会话文件 '${file.name}' 内的 id '${parsed.id}' 与文件名不一致，以文件名为准",
                    null
                )
                out += parsed.copy(id = id)
            }
        }
        return out
    }

    private fun readBenchmarkRuns(): List<ChatBenchmarkRun> {
        val file = ChatConversationLayout.benchmarkRunsFile(filesRoot)
        benchmarkFilePresent = file.isFile
        if (!benchmarkFilePresent) {
            lastBenchmarkText = null
            return emptyList()
        }
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log(ChatStoreLogLevel.WARN, "benchmark 记录读取失败，本次按空处理（文件保留）", e)
            return emptyList()
        }
        val parsed = runCatching { json.decodeFromString<ChatBenchmarkRunsFile>(text) }.getOrNull()
        if (parsed == null) {
            lastBenchmarkText = null
            quarantineFile(
                file,
                ChatSessionLoadIssueKind.BENCHMARK_RUNS_UNPARSEABLE,
                "benchmark 记录 ${file.name} 解析失败，原文已留存（本次不可见）",
            )
            return emptyList()
        }
        lastBenchmarkText = text
        if (parsed.version > ChatConversationLayout.SCHEMA_VERSION) {
            log(
                ChatStoreLogLevel.WARN,
                "benchmark 记录的 version=${parsed.version} 高于本版支持的 " +
                    "${ChatConversationLayout.SCHEMA_VERSION}，尽力加载",
                null
            )
        }
        return parsed.runs
    }

    private fun readIndex(): ChatConversationIndexFile? {
        val file = ChatConversationLayout.indexFile(filesRoot)
        if (!file.isFile) {
            lastIndexText = null
            return null
        }
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log(ChatStoreLogLevel.WARN, "索引 index.json 读取失败，本次按无索引处理", e)
            return null
        }
        val parsed = runCatching { json.decodeFromString<ChatConversationIndexFile>(text) }.getOrNull()
        if (parsed == null) {
            lastIndexText = null
            quarantineFile(
                file,
                ChatSessionLoadIssueKind.INDEX_UNPARSEABLE,
                "索引 index.json 解析失败，原文已留存（会话文件不受影响，只是失去「当前会话」记忆）",
            )
            return null
        }
        lastIndexText = text
        if (parsed.version > ChatConversationLayout.SCHEMA_VERSION) {
            log(
                ChatStoreLogLevel.WARN,
                "索引 version=${parsed.version} 高于本版支持的 " +
                    "${ChatConversationLayout.SCHEMA_VERSION}，尽力加载",
                null
            )
        }
        return parsed
    }

    // ── 一次性迁移 ──────────────────────────────────────────

    /**
     * 旧 blob（`chat_session_prefs.chat_session_state_json`）一次性迁移。
     *
     * 1. blob 能解析 ⇒ 只补「文件里没有的 id」（**绝不覆盖**已有文件）+ 补写缺失的
     *    `benchmark_runs.json` + 补写缺失/损坏的 `index.json` + 把 blob 原文写进 `legacy/`；
     *    **全部成功后才**清空 blob。
     * 2. blob 解析失败 ⇒ 原文复制进 `quarantine/`、记 issue、**绝不清空**。
     */
    private fun migrateLegacyBlob(
        knownConversations: List<ChatConversation>,
        indexUsable: Boolean,
    ): MigrationResult {
        val raw = runCatching { readLegacyBlob() }.getOrElse { e ->
            log(ChatStoreLogLevel.WARN, "读取旧会话 blob 失败，本次不迁移", e)
            null
        }
        val text = raw?.takeIf { it.isNotBlank() }
        legacyBlobTextAtLoad = text
        legacyBlobClearable = text == null
        if (text == null) return MigrationResult()

        val parsed = runCatching { json.decodeFromString<ChatSessionState>(text) }.getOrNull()
        if (parsed == null) {
            quarantineText(
                ChatConversationLayout.LEGACY_BLOB_FILE_NAME,
                text,
                ChatSessionLoadIssueKind.LEGACY_BLOB_UNPARSEABLE,
                "旧会话 blob（chat_session_state_json）解析失败，原文已留存、**未清空**" +
                    "（已迁移出来的会话文件不受影响）",
            )
            return MigrationResult()
        }

        legacyBlobClearable = true
        val knownIds = knownConversations.map { it.id }.toSet()
        val added = mutableListOf<ChatConversation>()
        var allOk = true

        for (conversation in parsed.conversations) {
            if (conversation.id in knownIds) continue
            if (!ChatConversationLayout.isSafeConversationId(conversation.id)) {
                log(
                    ChatStoreLogLevel.WARN,
                    "旧 blob 里的会话 id '${conversation.id}' 不安全，未迁移" +
                        "（旧 blob 因此保留，不冒险清空）",
                    null
                )
                allOk = false
                continue
            }
            val file = ChatConversationLayout.conversationFile(filesRoot, conversation.id)
            if (ChatConversationFiles.writeAtomically(file, json.encodeToString(conversation))) {
                added += conversation
            } else {
                log(ChatStoreLogLevel.WARN, "旧 blob 会话 '${conversation.id}' 迁移写盘失败", null)
                allOk = false
            }
        }

        if (parsed.benchmarkRuns.isNotEmpty() && !benchmarkFilePresent) {
            val runsText = renderBenchmarkRuns(parsed.benchmarkRuns)
            if (ChatConversationFiles.writeAtomically(
                    ChatConversationLayout.benchmarkRunsFile(filesRoot), runsText
                )
            ) {
                lastBenchmarkText = runsText
                benchmarkFilePresent = true
            } else {
                log(ChatStoreLogLevel.WARN, "旧 blob 的 benchmark 记录迁移写盘失败", null)
                allOk = false
            }
        }

        if (!indexUsable) {
            val indexText = renderIndex(
                conversations = knownConversations + added,
                activeConversationId = parsed.activeConversationId,
            )
            if (ChatConversationFiles.writeAtomically(
                    ChatConversationLayout.indexFile(filesRoot), indexText
                )
            ) {
                lastIndexText = indexText
            } else {
                log(ChatStoreLogLevel.WARN, "迁移后写索引失败", null)
                allOk = false
            }
        }

        if (!ChatConversationFiles.writeAtomically(
                ChatConversationLayout.legacyBackupFile(filesRoot), text
            )
        ) {
            log(ChatStoreLogLevel.WARN, "旧 blob 的本地安全副本写入失败", null)
            allOk = false
        }

        if (allOk) maybeClearLegacyBlob()

        return MigrationResult(
            conversations = added,
            activeConversationId = parsed.activeConversationId,
            benchmarkRuns = parsed.benchmarkRuns,
        )
    }

    /**
     * 清空旧 blob 的**唯一出口**。
     *
     * 三个前提缺一不可：
     * ① 本次加载时 blob 解析成功（迁移已提交）或本来就不存在；
     * ② 现在读到的 blob 与加载时**逐字相同**（否则说明有别的写入者在动它）；
     * ③ 清空回调返回 true（`commit()` 的结果）。
     *
     * 解析失败的 blob **永不**走到这里（`legacyBlobClearable == false`）——
     * 这正是「解析失败不再静默清空」的落点。
     */
    private fun maybeClearLegacyBlob(): Boolean {
        if (!legacyBlobClearable) return false
        val expected = legacyBlobTextAtLoad ?: return false
        val current = runCatching { readLegacyBlob() }.getOrNull() ?: return false
        if (current != expected) {
            log(
                ChatStoreLogLevel.WARN,
                "旧会话 blob 在加载之后发生了变化，本次不清空（宁可留数据，下次启动再处理）",
                null
            )
            return false
        }
        val cleared = runCatching { clearLegacyBlob() }.getOrDefault(false)
        if (!cleared) {
            log(
                ChatStoreLogLevel.WARN,
                "旧会话 blob 清空失败（commit 返回 false），数据未被删除，下次落盘/启动重试",
                null
            )
        }
        return cleared
    }

    // ── 隔离（坏数据留存） ──────────────────────────────────

    /**
     * 把**解析失败**的文件原文复制进 `chat/quarantine/`。
     *
     * ⚠️ **复制，不移动**：原位保留 ⇒ 用户/后续版本还有机会读它，
     * 而「静默清空」正是本任务要修掉的缺陷。
     * ⚠️ 同名同内容已有副本时**不重复复制**（否则每次启动都多一份，坏文件很大时会把磁盘填满）。
     */
    private fun quarantineFile(
        file: File,
        kind: ChatSessionLoadIssueKind,
        detail: String,
    ): String? {
        val text = try {
            file.readText()
        } catch (e: IOException) {
            log(ChatStoreLogLevel.WARN, "坏数据留存失败：'${file.name}' 读不出来", e)
            issues += ChatSessionLoadIssue(kind, detail, null)
            return null
        }
        return quarantineText(file.name, text, kind, detail)
    }

    private fun quarantineText(
        originalName: String,
        text: String,
        kind: ChatSessionLoadIssueKind,
        detail: String,
    ): String? {
        val dir = ChatConversationLayout.quarantineDir(filesRoot)
        val existing = dir.listFiles()?.firstOrNull { candidate ->
            candidate.isFile &&
                candidate.name.startsWith("$originalName.") &&
                candidate.name.endsWith(ChatConversationLayout.BAD_SUFFIX) &&
                runCatching { candidate.readText() == text }.getOrDefault(false)
        }
        val target = existing ?: File(
            dir,
            "$originalName.${System.currentTimeMillis()}${ChatConversationLayout.BAD_SUFFIX}"
        )
        if (existing == null &&
            !ChatConversationFiles.writeAtomically(target, text)
        ) {
            log(ChatStoreLogLevel.WARN, "坏数据留存失败（$originalName），原文仍在原位", null)
            issues += ChatSessionLoadIssue(kind, detail, null)
            return null
        }
        val path = target.absolutePath
        log(ChatStoreLogLevel.WARN, "$detail（留存副本：$path）", null)
        issues += ChatSessionLoadIssue(kind, detail, path)
        return path
    }

    // ── 渲染 ────────────────────────────────────────────────

    private fun renderIndex(
        conversations: List<ChatConversation>,
        activeConversationId: String?,
    ): String = json.encodeToString(
        ChatConversationIndexFile(
            version = ChatConversationLayout.SCHEMA_VERSION,
            activeConversationId = activeConversationId,
            entries = conversations.map { conversation ->
                ChatConversationIndexEntry(
                    id = conversation.id,
                    title = conversation.title,
                    updatedAtMillis = conversation.updatedAtMillis,
                    messageCount = conversation.messages.size,
                    preview = previewOf(conversation),
                )
            },
        )
    )

    private fun renderBenchmarkRuns(runs: List<ChatBenchmarkRun>): String = json.encodeToString(
        ChatBenchmarkRunsFile(
            version = ChatConversationLayout.SCHEMA_VERSION,
            runs = runs,
        )
    )

    /** 索引里的「末条预览」：最后一条消息的正文（折成单空格、截断），空则回落工具结果摘要。 */
    private fun previewOf(conversation: ChatConversation): String {
        val last = conversation.messages.lastOrNull() ?: return ""
        val raw = last.content.ifBlank { last.toolResult?.summary ?: "" }
        val collapsed = raw.replace(WHITESPACE, " ").trim()
        return if (collapsed.length > PREVIEW_MAX_CHARS) {
            collapsed.take(PREVIEW_MAX_CHARS)
        } else {
            collapsed
        }
    }

    private data class MigrationResult(
        val conversations: List<ChatConversation> = emptyList(),
        val activeConversationId: String? = null,
        val benchmarkRuns: List<ChatBenchmarkRun> = emptyList(),
    )

    private companion object {
        const val PREVIEW_MAX_CHARS = 80
        val WHITESPACE = Regex("\\s+")
    }
}

/** `chat/index.json` 的形状（轻量索引 —— **不含**消息正文）。 */
@Serializable
private data class ChatConversationIndexFile(
    val version: Int = ChatConversationLayout.SCHEMA_VERSION,
    val activeConversationId: String? = null,
    val entries: List<ChatConversationIndexEntry> = emptyList(),
)

@Serializable
private data class ChatConversationIndexEntry(
    val id: String,
    val title: String,
    val updatedAtMillis: Long,
    val messageCount: Int,
    val preview: String,
)

/** `chat/benchmark_runs.json` 的形状（单独一份 —— 含完整 trace，进索引就与「轻量」冲突）。 */
@Serializable
private data class ChatBenchmarkRunsFile(
    val version: Int = ChatConversationLayout.SCHEMA_VERSION,
    val runs: List<ChatBenchmarkRun> = emptyList(),
)
