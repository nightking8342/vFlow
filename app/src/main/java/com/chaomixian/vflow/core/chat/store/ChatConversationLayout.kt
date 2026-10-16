// 文件: main/java/com/chaomixian/vflow/core/chat/store/ChatConversationLayout.kt
package com.chaomixian.vflow.core.chat.store

import java.io.File

/**
 * 会话持久化的**磁盘布局**与路径助手（纯 JVM —— 不得 import `android.*`）。
 *
 * ## 布局
 *
 * ```
 * filesDir/chat/conversations/<id>.json                 每会话一份 ChatConversation 的 JSON（唯一真源）
 * filesDir/chat/index.json                              轻量索引（id / title / updatedAt / 消息数 / 末条预览 + version）
 * filesDir/chat/benchmark_runs.json                     {"version":1,"runs":[ChatBenchmarkRun…]}
 * filesDir/chat/legacy/legacy_chat_session_state.json   迁移成功后旧 blob 的**原始文本**副本
 * filesDir/chat/quarantine/<原名>.<时间戳>.bad.json      解析失败的坏数据留存（原文，不改写）
 * filesDir/chat/conversations/<id>.json.tmp             仅原子写期间存在（加载时忽略）
 * filesDir/chat/attachments/                            留给任务 ⑤（本任务**不创建**，布局上与之平级）
 * ```
 *
 * ## 为什么本文件与 `ChatScope` 共用这批字面量
 *
 * 备份范围（`core/backup/scopes/ChatScope.kt`）也要按同一布局读写会话文件。
 * 若两侧各写一份 `"conversations"` / `"index.json"`，**改名时只改一处**就会出现
 * 「备份说备了会话、实际收不到」这类静默失效。字面量集中在这里 ⇒ 只有一处能漂移。
 *
 * ⚠️ 本文件只有常量与路径拼接，**不做任何 IO** —— IO 原语在
 * [ChatConversationFiles]，读/写/迁移/隔离的语义在 `ui/chat/ChatConversationStore`。
 */
object ChatConversationLayout {

    /** `filesDir` 下的会话根目录。 */
    const val ROOT_DIR = "chat"

    /** 每会话一份文件所在目录。 */
    const val CONVERSATIONS_DIR = "conversations"

    /** 轻量索引文件名。**不是加载真源**（索引损坏/丢失不丢任何会话）。 */
    const val INDEX_FILE_NAME = "index.json"

    /** benchmark 运行记录文件名（单独一份 —— 它含完整 trace，进索引就与「轻量」冲突）。 */
    const val BENCHMARK_RUNS_FILE_NAME = "benchmark_runs.json"

    /** 迁移前旧 blob 的本地安全副本所在目录。 */
    const val LEGACY_DIR = "legacy"

    /** 旧 blob 的本地安全副本文件名。 */
    const val LEGACY_BLOB_FILE_NAME = "legacy_chat_session_state.json"

    /** 解析失败的坏数据留存目录。 */
    const val QUARANTINE_DIR = "quarantine"

    /** 隔离文件的统一后缀（`<原名>.<时间戳>.bad.json`）。 */
    const val BAD_SUFFIX = ".bad.json"

    const val JSON_SUFFIX = ".json"

    /** 原子写的临时文件后缀（与 [JSON_SUFFIX] 拼成 `<id>.json.tmp`）。 */
    const val TEMP_SUFFIX = ".tmp"

    /**
     * 本布局的版本号，写进 `index.json` / `benchmark_runs.json` / 备份 payload。
     *
     * 读到**更高**的 version ⇒ 记 W 并尽力加载（不拒收、不清空）。
     */
    const val SCHEMA_VERSION = 1

    fun root(filesRoot: File): File = File(filesRoot, ROOT_DIR)

    fun conversationsDir(filesRoot: File): File = File(root(filesRoot), CONVERSATIONS_DIR)

    fun indexFile(filesRoot: File): File = File(root(filesRoot), INDEX_FILE_NAME)

    fun benchmarkRunsFile(filesRoot: File): File =
        File(root(filesRoot), BENCHMARK_RUNS_FILE_NAME)

    fun legacyDir(filesRoot: File): File = File(root(filesRoot), LEGACY_DIR)

    fun legacyBackupFile(filesRoot: File): File =
        File(legacyDir(filesRoot), LEGACY_BLOB_FILE_NAME)

    fun quarantineDir(filesRoot: File): File = File(root(filesRoot), QUARANTINE_DIR)

    fun conversationFile(filesRoot: File, id: String): File =
        File(conversationsDir(filesRoot), conversationFileName(id))

    fun conversationFileName(id: String): String = "$id$JSON_SUFFIX"

    /**
     * `<id>.json` ⇒ `<id>`；不是本布局的会话文件 ⇒ null。
     *
     * ⚠️ `.json.tmp`（原子写期间的临时文件）**不匹配** —— 它以 `.tmp` 结尾，
     * 不是 `.json` 结尾。加载时它被静默忽略（是设计的一部分，不是漏项）。
     */
    fun conversationIdOf(fileName: String): String? {
        if (!fileName.endsWith(JSON_SUFFIX)) return null
        val id = fileName.removeSuffix(JSON_SUFFIX)
        return id.ifEmpty { null }
    }

    /**
     * 会话 id 是否**可以安全地拼进文件名**。
     *
     * 备份是**外部输入**（别处拷来的文件、也可能被手工编辑过），所以导入路径上的 id
     * 必须先过这道闸 —— 否则 `../../x` 这类 id 会写到会话目录之外。
     *
     * 规则（前四条来自方案，末一条为防御性补充，理由见下）：
     * 1. 空串 / 全空白 ⇒ 拒；
     * 2. 含 `/` 或 `\` ⇒ 拒（目录穿越的基本形态）；
     * 3. 含 `..` ⇒ 拒（`..` 本身、`a..b`、`../x` 全覆盖）；
     * 4. 以 `.tmp` 结尾 ⇒ 拒（与原子写的临时文件名混淆）；
     * 5. 含控制字符，或含 Windows 文件名非法字符 `: * ? " < > |` ⇒ 拒。
     *
     * ⚠️ 第 5 条是**补充**（方案只列了前四条）：`a:b` 在 Windows 上会被解释成
     * 备用数据流（ADS），写出来的东西读不回来 —— 而本项目在 Windows 上跑单测
     * （见方案 §6 风险 4），静默写坏比直接拒绝危险得多。
     */
    fun isSafeConversationId(id: String): Boolean {
        if (id.isEmpty() || id.isBlank()) return false
        if (id.contains('/') || id.contains('\\')) return false
        if (id.contains("..")) return false
        if (id.endsWith(TEMP_SUFFIX)) return false
        if (id.any { it.isISOControl() || it in ILLEGAL_FILE_NAME_CHARS }) return false
        return true
    }

    /** 各平台上会导致文件名非法/语义漂移的字符。 */
    private val ILLEGAL_FILE_NAME_CHARS = setOf(':', '*', '?', '"', '<', '>', '|')
}
