// 文件: test/java/com/chaomixian/vflow/ui/chat/ChatConversationStoreTest.kt
package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.chat.store.ChatConversationLayout
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [ChatConversationStore] 的回归测试 —— 「每会话一文件 + 轻量索引」这一层。
 *
 * ## 它真正在防什么
 *
 * 改造前会话全挤在 `chat_session_prefs` 的一个 JSON 键里：
 *
 * | 改错的地方 | 表现 | 会被别的测试抓住吗 |
 * |---|---|---|
 * | 解析失败又退回「空状态」 | **全部会话被静默抹掉**（本项目不可恢复） | 不会（正是本次要修的缺陷） |
 * | 迁移时覆盖了已有文件 | 用户迁移后看到的会话被旧 blob 的版本顶掉 | 不会 |
 * | 迁移后没清旧 blob | 每次启动都重复迁移（幂等，但白做） | 不会 |
 * | 写时不做 diff | 每条消息落盘都要重写全部会话（O(全部) 写放大） | 不会 |
 * | 删除时扫目录 | 会把**备份导入**写进来的会话误删 | 不会 |
 * | 原子写退化成直接写 | 掉电/被杀进程留下半个 JSON ⇒ 会话读不出来 | 不会 |
 * | id 未做路径校验 | 一份改过的备份可以往会话目录之外写文件 | 不会 |
 *
 * 全部测试跑在**临时目录**上（纯 JVM，不碰 Android）。
 */
class ChatConversationStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    // ── 工具 ────────────────────────────────────────────────

    private fun root(): File = tmp.newFolder()

    /**
     * 建一个 store。
     *
     * `legacyBlob` 用可变的 lambda（清空回调把它置 null）—— 这样「blob 仍在 prefs 里」
     * 这件事可以被直接断言，而不是靠计数推断。
     */
    private fun storeFor(
        filesRoot: File,
        legacyBlob: () -> String? = { null },
        onClearLegacyBlob: () -> Boolean = { true },
    ) = ChatConversationStore(
        filesRoot = filesRoot,
        readLegacyBlob = legacyBlob,
        clearLegacyBlob = onClearLegacyBlob,
    )

    private fun conversation(
        id: String,
        title: String = id,
        updatedAt: Long = 0L,
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

    private fun writeConversationFile(filesRoot: File, conversation: ChatConversation) {
        val file = ChatConversationLayout.conversationFile(filesRoot, conversation.id)
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(conversation))
    }

    private fun storeJson(state: ChatSessionState): String = json.encodeToString(state)

    private fun quarantineDir(filesRoot: File): File =
        ChatConversationLayout.quarantineDir(filesRoot)

    // ── 路径安全（纯函数） ──────────────────────────────────

    @Test
    fun `unsafe conversation ids are rejected`() {
        // 备份是外部输入 ⇒ id 必须先过这道闸，否则 `../evil` 会写到会话目录之外。
        assertFalse(ChatConversationLayout.isSafeConversationId(""))
        assertFalse(ChatConversationLayout.isSafeConversationId("   "))
        assertFalse("目录穿越", ChatConversationLayout.isSafeConversationId("../evil"))
        assertFalse("目录穿越", ChatConversationLayout.isSafeConversationId("a/b"))
        assertFalse("目录穿越", ChatConversationLayout.isSafeConversationId("a\\b"))
        assertFalse(".. 出现即拒", ChatConversationLayout.isSafeConversationId("a..b"))
        assertFalse("与原子写临时文件混淆", ChatConversationLayout.isSafeConversationId("x.tmp"))
        // 合法的 UUID 形态（本应用的会话 id）必须放行。
        assertTrue(
            ChatConversationLayout.isSafeConversationId("3f2b1c8e-6a4d-4f1b-9c7e-0d5a2b3c4d5e")
        )
    }

    @Test
    fun `save refuses to write outside the conversations dir for an unsafe id`() {
        val filesRoot = root()
        val store = storeFor(filesRoot)
        store.load()

        val report = store.save(
            ChatSessionState(conversations = listOf(conversation("../evil")))
        )

        assertEquals("不安全的 id 不该被写成文件", 0, report.writtenConversations)
        assertFalse(
            "❌ 会话文件跑出了 conversations/ 目录",
            File(filesRoot, "chat/evil.json").exists(),
        )
        assertFalse(
            "❌ 会话文件跑出了 filesDir",
            File(filesRoot.parentFile, "evil.json").exists(),
        )
    }

    // ── 迁移 ────────────────────────────────────────────────

    @Test
    fun `legacy blob is migrated once and then cleared`() {
        val filesRoot = root()
        val run = benchmarkRun()
        val first = conversation("a", "甲", 1L, messageCount = 2)
        val second = conversation("b", "乙", 2L)
        val blob = storeJson(
            ChatSessionState(
                conversations = listOf(first, second),
                activeConversationId = "b",
                benchmarkRuns = listOf(run),
            )
        )
        var blobInPrefs: String? = blob
        var clearCalls = 0
        val store = storeFor(
            filesRoot = filesRoot,
            legacyBlob = { blobInPrefs },
            onClearLegacyBlob = { clearCalls++; blobInPrefs = null; true },
        )

        val loaded = store.load()

        // ① 数据完整：与旧 blob 逐条相等（经磁盘往返，含消息）
        assertEquals(setOf(first, second), loaded.conversations.toSet())
        assertEquals("b", loaded.activeConversationId)
        assertEquals(listOf(run), loaded.benchmarkRuns)

        // ② 落盘形状
        assertTrue(ChatConversationLayout.conversationFile(filesRoot, "a").isFile)
        assertTrue(ChatConversationLayout.conversationFile(filesRoot, "b").isFile)
        assertTrue(ChatConversationLayout.indexFile(filesRoot).isFile)
        assertTrue(ChatConversationLayout.benchmarkRunsFile(filesRoot).isFile)
        assertEquals(
            "旧 blob 必须留一份**原文**副本",
            blob,
            ChatConversationLayout.legacyBackupFile(filesRoot).readText(),
        )

        // ③ 清空恰好一次
        assertEquals(1, clearCalls)
        assertNull("旧 blob 应当已被清空", blobInPrefs)

        // ④ 再 load 不重复清空（幂等）
        store.load()
        assertEquals(1, clearCalls)
    }

    @Test
    fun `migration never overwrites a conversation file that already exists`() {
        val filesRoot = root()
        val onDisk = conversation("a", "磁盘上的版本", 5L)
        writeConversationFile(filesRoot, onDisk)
        var blobInPrefs: String? = storeJson(
            ChatSessionState(conversations = listOf(conversation("a", "旧 blob 的版本", 1L)))
        )
        val store = storeFor(
            filesRoot = filesRoot,
            legacyBlob = { blobInPrefs },
            onClearLegacyBlob = { blobInPrefs = null; true },
        )

        val loaded = store.load()

        assertEquals(
            "已有文件优先（迁移只补「文件里没有的 id」）",
            listOf(onDisk),
            loaded.conversations,
        )
    }

    @Test
    fun `a blob that fails to parse is quarantined and never cleared`() {
        val filesRoot = root()
        // 先放两个正常会话文件（模拟「已经迁移过一部分」/ 用户的新数据）
        val first = conversation("a", "甲", 1L)
        val second = conversation("b", "乙", 2L)
        val seeding = storeFor(filesRoot)
        seeding.load()
        seeding.save(ChatSessionState(conversations = listOf(first, second)))

        val bad = """{"conversations":[{"id":"""
        var blobInPrefs: String? = bad
        var clearCalls = 0
        val store = storeFor(
            filesRoot = filesRoot,
            legacyBlob = { blobInPrefs },
            onClearLegacyBlob = { clearCalls++; blobInPrefs = null; true },
        )

        val loaded = store.load()

        assertEquals("❌ 坏 blob 不得让全部会话消失", 2, loaded.conversations.size)
        assertEquals("❌ 坏 blob 绝不能被清空", 0, clearCalls)
        assertEquals("坏 blob 应当原样留在 prefs 里", bad, blobInPrefs)
        assertEquals(
            "坏数据必须有留存副本",
            listOf(bad),
            quarantineDir(filesRoot).listFiles()?.map { it.readText() },
        )
        assertTrue(
            "必须产出可见的加载问题",
            store.loadIssues.any { it.kind == ChatSessionLoadIssueKind.LEGACY_BLOB_UNPARSEABLE },
        )

        // 落盘也不清空（save 里同样受「解析成功过才清」约束）
        val report = store.save(loaded)
        assertFalse(report.legacyBlobCleared)
        assertEquals(0, clearCalls)

        // 反复加载也不会重复堆副本（否则每次启动都多一份，坏文件大时会把磁盘填满）
        store.reload()
        assertEquals(1, quarantineDir(filesRoot).listFiles()?.size)
    }

    @Test
    fun `a failed commit keeps the blob for the next attempt`() {
        val filesRoot = root()
        val blob = storeJson(ChatSessionState(conversations = listOf(conversation("a"))))
        var blobInPrefs: String? = blob
        val store = storeFor(
            filesRoot = filesRoot,
            legacyBlob = { blobInPrefs },
            // 模拟 `commit()` 返回 false
            onClearLegacyBlob = { false },
        )

        store.load()
        assertEquals("清不掉时数据必须留着", blob, blobInPrefs)

        val report = store.save(store.load())
        assertFalse("清空没成功，报告里不能写「已清空」", report.legacyBlobCleared)
        assertEquals("清不掉时数据必须留着", blob, blobInPrefs)
    }

    // ── 读路径的坏数据 ──────────────────────────────────────

    @Test
    fun `one unparsable conversation file is quarantined while the rest load`() {
        val filesRoot = root()
        writeConversationFile(filesRoot, conversation("a", "甲", 1L))
        writeConversationFile(filesRoot, conversation("b", "乙", 2L))
        val badText = """{"id":"c","title":"""
        ChatConversationLayout.conversationFile(filesRoot, "c").writeText(badText)

        val store = storeFor(filesRoot)
        val loaded = store.load()

        assertEquals(listOf("b", "a"), loaded.conversations.map { it.id })
        assertEquals(
            1,
            store.loadIssues.count {
                it.kind == ChatSessionLoadIssueKind.CONVERSATION_FILE_UNPARSEABLE
            },
        )
        assertTrue(
            "❌ 坏文件必须**留在原位**（不许删）",
            ChatConversationLayout.conversationFile(filesRoot, "c").isFile,
        )
        assertEquals(badText, ChatConversationLayout.conversationFile(filesRoot, "c").readText())
        val quarantined = quarantineDir(filesRoot).listFiles()!!.single()
        assertEquals(badText, quarantined.readText())
        assertTrue(quarantined.name.startsWith("c.json."))
        assertTrue(quarantined.name.endsWith(".bad.json"))
    }

    @Test
    fun `a broken index does not lose any conversation`() {
        val filesRoot = root()
        writeConversationFile(filesRoot, conversation("a", "甲", 1L))
        writeConversationFile(filesRoot, conversation("b", "乙", 2L))
        ChatConversationLayout.indexFile(filesRoot).writeText("""{"version":1,"entries":[""")

        val store = storeFor(filesRoot)
        val loaded = store.load()

        assertEquals("索引不是加载真源 ⇒ 会话一条都不能少", 2, loaded.conversations.size)
        assertNull("索引坏了 ⇒ 回落到「最近更新」，而不是瞎指一个 id", loaded.activeConversationId)
        assertEquals(
            1,
            store.loadIssues.count { it.kind == ChatSessionLoadIssueKind.INDEX_UNPARSEABLE },
        )
    }

    @Test
    fun `an unknown active conversation id falls back to null`() {
        val filesRoot = root()
        writeConversationFile(filesRoot, conversation("a", "甲", 1L))
        ChatConversationLayout.indexFile(filesRoot).writeText(
            """{"version":1,"activeConversationId":"ghost","entries":[]}"""
        )

        assertNull(storeFor(filesRoot).load().activeConversationId)
    }

    // ── 写路径的 diff ───────────────────────────────────────

    @Test
    fun `save only rewrites the conversation that actually changed`() {
        val filesRoot = root()
        val first = conversation("a", "甲", 1L)
        val second = conversation("b", "乙", 2L)
        val store = storeFor(filesRoot)
        store.load()
        val initial = ChatSessionState(conversations = listOf(first, second), activeConversationId = "a")
        assertEquals(2, store.save(initial).writtenConversations)

        // 给「没变」的那一条打一个哨兵：它若被重写，哨兵就没了。
        val untouched = ChatConversationLayout.conversationFile(filesRoot, "b")
        untouched.writeText(SENTINEL)

        val report = store.save(
            initial.copy(conversations = listOf(first.copy(title = "甲（改了）"), second))
        )

        assertEquals("只该重写变化的那一条", 1, report.writtenConversations)
        assertEquals("❌ 没变化的会话被重写了（O(全部) 写放大）", SENTINEL, untouched.readText())
        assertTrue(
            "变化的那一条必须真的写下去了",
            ChatConversationLayout.conversationFile(filesRoot, "a").readText().contains("甲（改了）"),
        )
    }

    @Test
    fun `an unchanged state writes nothing at all`() {
        val filesRoot = root()
        val state = ChatSessionState(
            conversations = listOf(conversation("a", "甲", 1L), conversation("b", "乙", 2L)),
            activeConversationId = "a",
        )
        val store = storeFor(filesRoot)
        store.load()
        store.save(state)

        val watched = listOf(
            ChatConversationLayout.conversationFile(filesRoot, "a"),
            ChatConversationLayout.conversationFile(filesRoot, "b"),
            ChatConversationLayout.indexFile(filesRoot),
        )
        watched.forEach { it.writeText(SENTINEL) }

        val report = store.save(state)

        assertEquals("没有任何变化 ⇒ 一个字节都不该写", SaveReport(), report)
        watched.forEach { file ->
            assertEquals("❌ ${file.name} 被无谓地重写了", SENTINEL, file.readText())
        }
    }

    @Test
    fun `removed conversations are deleted but unknown files on disk survive`() {
        val filesRoot = root()
        val first = conversation("a", "甲", 1L)
        val second = conversation("b", "乙", 2L)
        val store = storeFor(filesRoot)
        store.load()
        store.save(ChatSessionState(conversations = listOf(first, second)))

        // 模拟「备份导入写进来了一条内存里没有的会话」
        writeConversationFile(filesRoot, conversation("imported", "导入进来的", 9L))

        val report = store.save(ChatSessionState(conversations = listOf(first)))

        assertEquals(1, report.deletedConversations)
        assertFalse(
            "内存里移除的会话，文件要删掉",
            ChatConversationLayout.conversationFile(filesRoot, "b").exists(),
        )
        assertTrue(
            "❌ 不得扫目录删除 —— 那会把备份导入写进来的会话误删",
            ChatConversationLayout.conversationFile(filesRoot, "imported").exists(),
        )
    }

    @Test
    fun `no temp file is left behind and a stale temp file is ignored on load`() {
        val filesRoot = root()
        val store = storeFor(filesRoot)
        store.load()
        store.save(ChatSessionState(conversations = listOf(conversation("a", "甲", 1L))))

        val conversationsDir = ChatConversationLayout.conversationsDir(filesRoot)
        assertTrue(
            "❌ 保存后残留了 .tmp：${conversationsDir.listFiles()?.map { it.name }}",
            conversationsDir.listFiles()!!.none { it.name.endsWith(".tmp") },
        )

        // 预置一个残留的 .tmp（上次被杀进程留下）—— 加载时必须忽略它
        File(conversationsDir, "b.json.tmp").writeText("""{"id":"b","title":""")
        val reloaded = storeFor(filesRoot).load()
        assertEquals(listOf("a"), reloaded.conversations.map { it.id })
    }

    @Test
    fun `the index carries lightweight metadata and a preview`() {
        val filesRoot = root()
        val store = storeFor(filesRoot)
        store.load()
        store.save(
            ChatSessionState(
                conversations = listOf(
                    conversation("a", "甲", 1L).copy(
                        messages = listOf(
                            ChatMessage(
                                role = ChatMessageRole.USER,
                                content = "第一行\n第二行   " + "长".repeat(120),
                                timestampMillis = 1L,
                            )
                        )
                    )
                ),
                activeConversationId = "a",
            )
        )

        val indexText = ChatConversationLayout.indexFile(filesRoot).readText()
        assertTrue("索引要带 version", indexText.contains("\"version\":1"))
        assertTrue("索引要带 activeConversationId", indexText.contains("\"activeConversationId\":\"a\""))
        assertTrue("索引要带消息数", indexText.contains("\"messageCount\":1"))
        assertFalse("索引是「轻量」的 ⇒ 不该装整条消息", indexText.contains("长".repeat(120)))
        val preview = json.parseToJsonElement(indexText)
            .jsonObject.getValue("entries").jsonArray.single()
            .jsonObject.getValue("preview").jsonPrimitive.content
        assertEquals(
            "换行/连续空白折成单空格后截断到 80 字符",
            "第一行 第二行 " + "长".repeat(72),
            preview,
        )
    }

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

    private companion object {
        /** 打在所有「不该被重写」的文件上的哨兵文本。 */
        const val SENTINEL = """{"sentinel":"这一行若消失，说明文件被重写了"}"""
    }
}
