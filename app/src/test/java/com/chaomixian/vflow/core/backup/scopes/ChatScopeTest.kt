// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/ChatScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
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

    // ── 三个 prefs 文件 ─────────────────────────────────────

    @Test
    fun `covers exactly three prefs files`() {
        assertEquals(
            setOf("module_config_prefs", "chat_session_prefs", "ai_config"),
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

        val aiSpec = scope.specs.first { it.prefsName == "ai_config" }
        assertFalse(
            "ai_config.api_key ⇒ 必须在 secrets scope 里",
            aiSpec.matches("api_key"),
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

        val aiSpec = scope.specs.first { it.prefsName == "ai_config" }
        assertTrue(aiSpec.matches("provider"))
        assertTrue(aiSpec.matches("base_url"))
        assertTrue(aiSpec.matches("model"))
    }

    // ── 端到端 ──────────────────────────────────────────────

    @Test
    fun `the three prefs files round trip`() {
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        store.data["module_config_prefs"] = linkedMapOf<String, Any?>(
            "chat_default_preset_id" to "preset-1",
            "chat_auto_approve_tools" to true,
        )
        store.data["chat_session_prefs"] = linkedMapOf<String, Any?>(
            "chat_session_state_json" to """{"conversations":[]}""",
        )
        store.data["ai_config"] = linkedMapOf<String, Any?>(
            "provider" to "bigmodel",
            "base_url" to "https://example.invalid/v4",
            "model" to "glm-4.5-flash",
        )

        val payload = scope.export(env, null)!!
        // 三个文件都有数据 ⇒ count = 3
        assertEquals(3, payload.count)

        val importing = FakeBackupEnvironment()
        val result = scope.import(importing, payload, ImportMode.MERGE)
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(6, result.imported)

        val after = (importing.prefs as FakeBackupEnvironment.InMemoryPrefs).data
        assertEquals("preset-1", after["module_config_prefs"]?.get("chat_default_preset_id"))
        assertEquals(
            """{"conversations":[]}""",
            after["chat_session_prefs"]?.get("chat_session_state_json"),
        )
        assertEquals("bigmodel", after["ai_config"]?.get("provider"))
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
            "chat_session_state_json", "provider", "base_url", "model",
        )
        val missing = keys.filterNot { text.contains("\"$it\"") }
        assertTrue(
            "❌ 这些键在源码里找不到（键名可能改了，那会让备份静默少项）：$missing",
            missing.isEmpty(),
        )
    }
}
