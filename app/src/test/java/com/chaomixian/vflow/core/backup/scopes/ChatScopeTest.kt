// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/ChatScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.LogLevel
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.ui.chat.ChatBenchmarkRun
import com.chaomixian.vflow.ui.chat.ChatBenchmarkRunStatus
import com.chaomixian.vflow.ui.chat.ChatConversation
import com.chaomixian.vflow.ui.chat.ChatConversationStore
import com.chaomixian.vflow.ui.chat.ChatMessage
import com.chaomixian.vflow.ui.chat.ChatMessageRole
import com.chaomixian.vflow.ui.chat.ChatSessionState
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [ChatScope] 的逐键测试。
 *
 * ## ⚠️ 本文件真正在防的是什么
 *
 * 静默失效点 7：**只勾「聊天」漏勾「密钥」，而 `chat_*_json` 内含 `apiKey`**
 * ⇒ 明文泄漏。本 scope 与 `SecretsScope` 共用 `module_config_prefs`，
 * 白名单划错一个键就是**明文写进备份文件**且毫无提示。
 */
class ChatScopeTest {

    private val scope = ChatScope()

    @get:Rule
    val tmp = TemporaryFolder()

    // ── 静态声明 ────────────────────────────────────────────

    @Test
    fun `declares the contract from the design`() {
        assertEquals("chat", scope.id)
        assertEquals(ScopeGroup.CONFIG, scope.group)
        assertFalse("chat 本身不含密钥（含密钥的那两个键归 secrets）", scope.sensitive)
        assertEquals(50, scope.importOrder)
        assertEquals(emptyList<String>(), scope.dependsOn)
    }

    @Test
    fun `chat is not included by default and the reason is two fold`() {
        // ⚠️ 父会话 2026-10-03 定案：chat = false（settings/modules/tiles = true）。
        //    两个**独立**理由：体积（会话历史）+ 隐私。KDoc 要求分开写，
        //    不能与 secrets 的「敏感，需口令」合并成一句（处置完全不同）。
        assertFalse(
            "chat 默认不勾选（体积 + 隐私，见类 KDoc）",
            scope.defaultIncluded,
        )

        val kdoc = File(
            "src/main/java/com/chaomixian/vflow/core/backup/scopes/ChatScope.kt"
        ).readText()
        assertTrue(
            "KDoc 必须写明「体积」这个理由 —— 它是「每份备份都会变大」的直接后果",
            kdoc.contains("体积"),
        )
        assertTrue(
            "KDoc 必须写明「隐私」这个理由",
            kdoc.contains("隐私"),
        )
        assertTrue(
            "KDoc 必须点明它与 secrets 的处置不同（带本 scope 不需要口令）",
            kdoc.contains("不需要口令"),
        )
    }

    // ── 两个 prefs 文件 ─────────────────────────────────────

    @Test
    fun `covers exactly two prefs files`() {
        assertEquals(
            setOf("module_config_prefs", "chat_session_prefs"),
            scope.specs.map { it.prefsName }.toSet(),
        )
    }

    // ── 含 apiKey 的键必须排除（静默失效点 7） ──────────────

    @Test
    fun `the two json keys that embed an api key are excluded`() {
        // ⚠️⚠️ 这是本文件存在的**首要理由**：这两个键在 SECRET_SLOTS 里，
        //     放进普通 scope = 绕过「包含密钥」勾选 ⇒ 明文泄漏。
        val moduleSpec = scope.specs.first { it.prefsName == "module_config_prefs" }
        assertFalse(
            "chat_presets_json 含 apiKey ⇒ 必须在 secrets scope 里",
            moduleSpec.matches("chat_presets_json"),
        )
        assertFalse(
            "chat_provider_configs_json 含 apiKey ⇒ 必须在 secrets scope 里",
            moduleSpec.matches("chat_provider_configs_json"),
        )
    }

    @Test
    fun `the old session state key in module_config_prefs is excluded`() {
        // ⚠️ `ChatPresetRepository.saveSessionState` 会把 `chat_session_prefs`
        //     写好后**主动 remove 掉 `module_config_prefs` 里的这份**。
        //     收它等于把已被迁移掉的旧键重新写回 ⇒ 与读侧的兼容分支打架。
        val moduleSpec = scope.specs.first { it.prefsName == "module_config_prefs" }
        assertFalse(
            "module_config_prefs.chat_session_state_json 是**旧位置**，不该收",
            moduleSpec.matches("chat_session_state_json"),
        )
        val sessionSpec = scope.specs.first { it.prefsName == "chat_session_prefs" }
        assertTrue(
            "新位置（chat_session_prefs）才是要收的那份",
            sessionSpec.matches("chat_session_state_json"),
        )
    }

    // ── 收的键 ──────────────────────────────────────────────

    @Test
    fun `policies and session history are included`() {
        val moduleSpec = scope.specs.first { it.prefsName == "module_config_prefs" }
        assertTrue(moduleSpec.matches("chat_default_preset_id"))
        assertTrue(moduleSpec.matches("chat_auto_approve_tools"))
        assertTrue(moduleSpec.matches("chat_auto_approval_scope"))

        val sessionSpec = scope.specs.first { it.prefsName == "chat_session_prefs" }
        assertTrue(sessionSpec.matches("chat_session_state_json"))
    }

    // ── 端到端 ──────────────────────────────────────────────

    @Test
    fun `the two prefs files round trip`() {
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        store.data["module_config_prefs"] = linkedMapOf<String, Any?>(
            "chat_default_preset_id" to "preset-1",
            "chat_auto_approve_tools" to true,
        )
        store.data["chat_session_prefs"] = linkedMapOf<String, Any?>(
            "chat_session_state_json" to """{"conversations":[]}""",
        )

        val payload = scope.export(env, null)!!
        // 两个文件都有数据 ⇒ count = 2
        assertEquals(2, payload.count)

        val importing = FakeBackupEnvironment()
        val result = scope.import(importing, payload, ImportMode.MERGE)
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(3, result.imported)

        val after = (importing.prefs as FakeBackupEnvironment.InMemoryPrefs).data
        assertEquals("preset-1", after["module_config_prefs"]?.get("chat_default_preset_id"))
        assertEquals(
            """{"conversations":[]}""",
            after["chat_session_prefs"]?.get("chat_session_state_json"),
        )
    }

    @Test
    fun `a file with no matching key does not enter the payload`() {
        // ⚠️ 「一个 prefs 文件里一个匹配键都没有」⇒ 该文件不进数组
        //    （空数据集 = ScopePayload(0, [])，不是「文件条目 + 空 items」）。
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        // 只有非白名单键
        store.data["module_config_prefs"] = linkedMapOf<String, Any?>(
            "chat_presets_json" to """{"k":{"apiKey":"SECRET"}}""",
            "network_proxy" to "http://u:p@host",
        )

        val payload = scope.export(env, null)!!
        assertEquals("不含白名单键 ⇒ count = 0", 0, payload.count)
        assertFalse(
            "❌ 含 apiKey 的键绝不能进 payload",
            payload.data.toString().contains("SECRET"),
        )
        assertFalse(
            "❌ network_proxy（可能含 user:pass）绝不能进 payload",
            payload.data.toString().contains("network_proxy"),
        )
    }

    @Test
    fun `every whitelisted key still exists in the codebase`() {
        val roots = listOf(
            File("src/main/java/com/chaomixian/vflow/ui/chat"),
            File("src/main/java/com/chaomixian/vflow/ui/workflow_editor"),
        ).filter { it.isDirectory }
        assertTrue("没有扫到源码目录", roots.isNotEmpty())
        val text = roots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() }
            .joinToString("\n") { it.readText() }

        val keys = listOf(
            "chat_default_preset_id", "chat_auto_approve_tools", "chat_auto_approval_scope",
            "chat_session_state_json",
        )
        val missing = keys.filterNot { text.contains("\"$it\"") }
        assertTrue(
            "❌ 这些键在源码里找不到（键名可能改了，那会让备份静默少项）：$missing",
            missing.isEmpty(),
        )
    }

    // ── 会话文件：本 scope 的**第二道通道**（2026-10 起「每会话一文件」） ──

    /**
     * ⚠️ 本组用例存在的理由：会话数据从「prefs 单键」搬到了 `filesDir/chat/`，
     * 而 `AbstractPrefsScope` 的 `export`/`import` 都是 `final`、只认 prefs 键。
     * 接线一旦漏掉，**备份里就一条会话都没有**，而 prefs 那部分照样正常
     * ⇒ 用户看到「备份成功」，换机后会话全空。这正是本仓库最怕的静默失效形态。
     */
    @Test
    fun `conversation files are carried in a conversations element`() {
        val filesRoot = tmp.newFolder()
        seedConversations(filesRoot, listOf(conversation("a", "甲", 1L), conversation("b", "乙", 2L)))

        val payload = scope.export(FakeBackupEnvironment(filesRoot = filesRoot), null)!!

        assertEquals("prefs 里没数据 ⇒ 只有会话这一个元素", 1, payload.count)
        val conversations = payload.data.asJsonArray[0].asJsonObject["conversations"]!!.asJsonObject
        assertEquals(1, conversations["version"]!!.asInt)
        assertEquals(
            listOf("a", "b"),
            conversations["items"]!!.asJsonArray.map { it.asJsonObject["id"]!!.asString },
        )
        assertFalse(
            "备份不带 activeConversationId（导入后由「最近更新」回落）",
            conversations.has("activeConversationId"),
        )
        assertFalse(
            "❌ 不得把本地安全副本（legacy/、quarantine/）带进备份 —— 体积会翻倍",
            payload.data.toString().contains("legacy_chat_session_state"),
        )
    }

    @Test
    fun `conversations and benchmark runs round trip into a fresh device`() {
        val exportingRoot = tmp.newFolder()
        val conversations = listOf(
            conversation("a", "甲", 1L, messageCount = 2),
            conversation("b", "乙", 2L),
        )
        val runs = listOf(benchmarkRun())
        seedConversations(exportingRoot, conversations, runs = runs)

        val payload = scope.export(FakeBackupEnvironment(filesRoot = exportingRoot), null)!!

        // 接收方必须是**全新空环境** —— 否则证明不了数据是从 payload 来的。
        val importingRoot = tmp.newFolder()
        val result = scope.import(
            FakeBackupEnvironment(filesRoot = importingRoot),
            payload,
            ImportMode.MERGE,
        )

        assertEquals(ImportStatus.IMPORTED, result.status)
        val restored = loadSessionState(importingRoot)
        assertEquals("会话必须逐条还原（含消息）", conversations.toSet(), restored.conversations.toSet())
        assertEquals("benchmark 记录也要还原", runs, restored.benchmarkRuns)
    }

    @Test
    fun `REPLACE deletes local conversations that the backup does not contain`() {
        val exportingRoot = tmp.newFolder()
        val backupConversations = listOf(conversation("a", "甲", 1L))
        seedConversations(exportingRoot, backupConversations)
        val payload = scope.export(FakeBackupEnvironment(filesRoot = exportingRoot), null)!!

        val importingRoot = tmp.newFolder()
        seedConversations(
            importingRoot,
            backupConversations + conversation("local-only", "本地独有", 9L),
        )

        val result = scope.import(
            FakeBackupEnvironment(filesRoot = importingRoot),
            payload,
            ImportMode.REPLACE,
        )

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(
            "REPLACE 要真删（否则与 MERGE 无差别）",
            setOf("a"),
            loadSessionState(importingRoot).conversations.map { it.id }.toSet(),
        )
    }

    @Test
    fun `MERGE keeps local conversations that the backup does not contain`() {
        val exportingRoot = tmp.newFolder()
        val backupConversations = listOf(conversation("a", "甲", 1L))
        seedConversations(exportingRoot, backupConversations)
        val payload = scope.export(FakeBackupEnvironment(filesRoot = exportingRoot), null)!!

        val importingRoot = tmp.newFolder()
        seedConversations(
            importingRoot,
            backupConversations + conversation("local-only", "本地独有", 9L),
        )

        scope.import(FakeBackupEnvironment(filesRoot = importingRoot), payload, ImportMode.MERGE)

        assertEquals(
            "MERGE 必须保留本地独有的会话",
            setOf("a", "local-only"),
            loadSessionState(importingRoot).conversations.map { it.id }.toSet(),
        )
    }

    @Test
    fun `an old backup without a conversations element never wipes local conversations`() {
        // 「改造前导出的备份」＝只有 prefs 元素（那次导出时 filesRoot 里还没有会话文件）。
        val exporting = FakeBackupEnvironment()
        (exporting.prefs as FakeBackupEnvironment.InMemoryPrefs).data["module_config_prefs"] =
            linkedMapOf<String, Any?>("chat_default_preset_id" to "preset-1")
        val oldBackup = scope.export(exporting, null)!!
        assertEquals("老备份里只有 prefs 元素", 1, oldBackup.count)

        val importingRoot = tmp.newFolder()
        seedConversations(importingRoot, listOf(conversation("local", "本地", 1L)))

        val result = scope.import(
            FakeBackupEnvironment(filesRoot = importingRoot),
            oldBackup,
            ImportMode.REPLACE,
        )

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(
            "❌ 导入一份老备份不得清空本地会话（与 prefs 侧「文件不在备份里＝本次不是 scope 成员」一致）",
            listOf("local"),
            loadSessionState(importingRoot).conversations.map { it.id },
        )
    }

    @Test
    fun `an unsafe conversation id in a backup is skipped instead of escaping the directory`() {
        val importingRoot = tmp.newFolder()
        val payload = ScopePayload.of(
            listOf(
                JsonObject().apply {
                    add(
                        "conversations",
                        JsonObject().apply {
                            addProperty("version", 1)
                            add(
                                "items",
                                JsonArray().apply {
                                    add(
                                        JsonObject().apply {
                                            addProperty("id", "../evil")
                                            add(
                                                "conversation",
                                                JsonParser.parseString(
                                                    """{"id":"../evil","title":"x"}"""
                                                ),
                                            )
                                        }
                                    )
                                },
                            )
                        },
                    )
                }
            )
        )

        val result = scope.import(
            FakeBackupEnvironment(filesRoot = importingRoot),
            payload,
            ImportMode.MERGE,
        )

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue("不安全的 id 要被跳过", result.skipped >= 1)
        assertFalse(
            "❌ 会话文件跑出了 conversations/ 目录",
            File(importingRoot, "chat/evil.json").exists(),
        )
        assertFalse(
            "❌ 会话文件跑出了 filesDir",
            File(importingRoot.parentFile, "evil.json").exists(),
        )
    }

    @Test
    fun `an environment without filesRoot skips conversations and logs a warning`() {
        val exportingRoot = tmp.newFolder()
        seedConversations(exportingRoot, listOf(conversation("a", "甲", 1L)))
        val payload = scope.export(FakeBackupEnvironment(filesRoot = exportingRoot), null)!!

        val importing = FakeBackupEnvironment() // filesRoot = null
        val result = scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertTrue("会话元素要被计为 skipped", result.skipped >= 1)
        assertTrue(
            "❌ 静默跳过会让用户以为会话带过去了 —— 必须记 W",
            importing.logs.any { (level, message) ->
                level == LogLevel.W && message.contains("filesRoot")
            },
        )
    }

    @Test
    fun `a device with no conversations and no prefs still yields a count zero payload`() {
        // 空数据集 = count 0 的 payload（绝不是 null）—— 既有契约，不因新增通道而变。
        val payload = scope.export(FakeBackupEnvironment(filesRoot = tmp.newFolder()), null)!!
        assertEquals(0, payload.count)
        assertTrue(payload.data.isJsonArray)
    }

    // ── 会话文件的播种 / 读回（复用会话存储层，避免手工拼格式而测错东西） ──

    private fun seedConversations(
        filesRoot: File,
        conversations: List<ChatConversation>,
        runs: List<ChatBenchmarkRun> = emptyList(),
    ) {
        val store = ChatConversationStore(filesRoot, { null }, { true })
        store.load()
        store.save(
            ChatSessionState(
                conversations = conversations,
                activeConversationId = conversations.firstOrNull()?.id,
                benchmarkRuns = runs,
            )
        )
    }

    private fun loadSessionState(filesRoot: File): ChatSessionState =
        ChatConversationStore(filesRoot, { null }, { true }).load()

    private fun conversation(
        id: String,
        title: String,
        updatedAt: Long,
        messageCount: Int = 0,
    ): ChatConversation = ChatConversation(
        id = id,
        title = title,
        createdAtMillis = updatedAt,
        updatedAtMillis = updatedAt,
        messages = (1..messageCount).map { index ->
            ChatMessage(
                role = ChatMessageRole.USER,
                content = "消息 $index",
                timestampMillis = updatedAt,
            )
        },
    )

    private fun benchmarkRun(): ChatBenchmarkRun = ChatBenchmarkRun(
        id = "run-1",
        suiteId = "suite-1",
        suiteTitle = "套件",
        presetId = "preset-1",
        presetName = "预设",
        provider = "openai",
        modelName = "gpt-test",
        status = ChatBenchmarkRunStatus.COMPLETED,
        startedAtMillis = 1L,
        finishedAtMillis = 2L,
    )
}
