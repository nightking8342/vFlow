// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/ModuleScopeTest.kt
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
 * [ModuleScope] 的逐键测试（含前缀规则）。
 *
 * ## ⚠️ 本文件真正在防的是什么
 *
 * 1. **`network_proxy` 内嵌 `user:pass@host`**（静默失效点：`SecretFieldScrubber`
 *    只覆盖工作流参数、**不覆盖 prefs** ⇒ 收了它就是**明文写进备份文件**）；
 * 2. **飞书整组不能半进半出** —— 凭证在 secrets、非凭证的 `feishu_app_id` 等
 *    若落进来，排障时看不出飞书配置到底备没备；
 * 3. **前缀规则是唯一会「多收」的形态**，必须锁死它的边界。
 */
class ModuleScopeTest {

    private val scope = ModuleScope()
    private val moduleSpec get() = scope.specs.first { it.prefsName == "module_config_prefs" }
    private val providerSpec get() = scope.specs.first { it.prefsName == "external_module_providers" }

    // ── 静态声明 ────────────────────────────────────────────

    @Test
    fun `declares the contract from the design`() {
        assertEquals("modules", scope.id)
        assertEquals(ScopeGroup.CONFIG, scope.group)
        assertFalse("模块配置不含凭证", scope.sensitive)
        assertTrue("模块配置默认勾选", scope.defaultIncluded)
        assertEquals(8, scope.importOrder)
        assertEquals(emptyList<String>(), scope.dependsOn)
    }

    // ── network_proxy 必须排除（可能内嵌 user:pass） ────────

    @Test
    fun `network proxy is excluded because it may embed credentials`() {
        // ⚠️⚠️ 本文件首要理由。`network_proxy` 形如
        //     `http://user:password@host:port` —— 而 `SecretFieldScrubber`
        //     **只覆盖工作流参数、不覆盖 prefs** ⇒ 没有第二道防线。
        assertFalse(
            "network_proxy 可能内嵌 user:pass 凭据 ⇒ 必须排除",
            moduleSpec.matches("network_proxy"),
        )
        // KDoc 必须写明「若要收得先剥 userinfo」，否则下一个人会顺手加进来。
        val kdoc = File(
            "src/main/java/com/chaomixian/vflow/core/backup/scopes/ModuleScope.kt"
        ).readText()
        assertTrue(
            "KDoc 必须写明 network_proxy 的排除理由与「收它需要先剥 userinfo」",
            kdoc.contains("userinfo"),
        )
    }

    // ── 飞书整组处理 ────────────────────────────────────────

    @Test
    fun `no feishu key is in this scope`() {
        // ⚠️ 凭证在 secrets；非凭证的 feishu_app_id / *_expires_at /
        //    _redirect_uri / _scope 也**整组不收**（避免「一半在 secrets、
        //    一半在 modules」的割裂）。`feishu_app_id` 与 `feishu_user_scope`
        //    不含任何凭证子串，故这条**不是**靠形状规则兜住的，必须显式断言。
        listOf(
            "feishu_app_id",
            "feishu_app_secret",
            "feishu_app_access_token",
            "feishu_app_access_token_expires_at",
            "feishu_access_token",
            "feishu_access_token_expires_at",
            "feishu_user_auth_code",
            "feishu_user_redirect_uri",
            "feishu_user_code_verifier",
            "feishu_user_scope",
            "feishu_user_access_token",
            "feishu_user_access_token_expires_at",
            "feishu_user_refresh_token",
            "feishu_user_refresh_token_expires_at",
        ).forEach { key ->
            assertFalse("飞书键 $key 不该出现在 modules scope", moduleSpec.matches(key))
        }
    }

    // ── 收的键 ──────────────────────────────────────────────

    @Test
    fun `module level preferences are included`() {
        listOf(
            "backtap_sensitivity",
            "app_start_close_check_delay",
            "app_start_verification_delay",
            "app_start_min_check_interval",
            "screen_operation_pointer_enabled",
            "screen_operation_pointer_style",
            "voice_trigger_download_source",
            "voice_trigger_audio_source",
            "voice_trigger_retrigger_cooldown_ms",
            "voice_trigger_similarity_threshold_percent",
            "voice_trigger_template_1",
            "voice_trigger_template_2",
            "voice_trigger_template_3",
        ).forEach { key ->
            assertTrue("$key 应当被备份", moduleSpec.matches(key))
        }
        assertEquals(13, ModuleScope.MODULE_KEYS.size)
    }

    @Test
    fun `runtime state prefs are excluded`() {
        // 这些在**别的 prefs 文件**里，本 scope 根本不声明那些文件 ⇒ 天然不收。
        // 断言的是「不声明它们」，防有人「顺手把整个 prefs 都收进来」。
        assertEquals(
            setOf("module_config_prefs", "external_module_providers"),
            scope.specs.map { it.prefsName }.toSet(),
        )
        listOf(
            "recent_modules_prefs",
            "flashlight_prefs",
            "vflow_do_not_disturb",
            "location_trigger_geofence_states",
        ).forEach { prefsName ->
            assertFalse(
                "$prefsName 是运行态/跨机无效的 prefs，不该被本 scope 声明",
                scope.specs.any { it.prefsName == prefsName },
            )
        }
    }

    // ── 前缀规则 ────────────────────────────────────────────

    @Test
    fun `the provider enabled prefix matches only that prefix`() {
        assertTrue(providerSpec.matches("provider_enabled_com.example.app"))
        assertTrue(
            "前缀规则允许键名含包名（键空间有界 = 已安装扩展数）",
            providerSpec.matches("provider_enabled_"),
        )
        assertFalse(
            "❌ 不得匹配无关的键 —— 前缀是最容易「多收」的形态",
            providerSpec.matches("provider_disabled_x"),
        )
        assertFalse(providerSpec.matches("some_other_key"))
    }

    @Test
    fun `known providers is an exact key`() {
        assertTrue(providerSpec.matches("known_providers"))
        assertFalse(
            "known_providers_x 不该被收（它是精确键，不是前缀）",
            providerSpec.matches("known_providers_x"),
        )
    }

    // ── 端到端：前缀键真的会被导出/导入 ─────────────────────

    @Test
    fun `dynamically named provider toggles round trip`() {
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        store.data["external_module_providers"] = linkedMapOf<String, Any?>(
            "known_providers" to setOf("com.example.a", "com.example.b"),
            "provider_enabled_com.example.a" to true,
            "provider_enabled_com.example.b" to false,
        )
        store.data["module_config_prefs"] = linkedMapOf<String, Any?>(
            "backtap_sensitivity" to 2,
            "network_proxy" to "http://u:p@host",   // 必须被排除
        )

        val payload = scope.export(env, null)!!
        assertFalse(
            "❌ network_proxy 绝不能进 payload",
            payload.data.toString().contains("network_proxy"),
        )
        assertTrue(
            "前缀键（每台设备不同）必须真的被导出",
            payload.data.toString().contains("provider_enabled_com.example.a"),
        )

        val importing = FakeBackupEnvironment()
        val result = scope.import(importing, payload, ImportMode.MERGE)
        assertEquals(ImportStatus.IMPORTED, result.status)

        val after = (importing.prefs as FakeBackupEnvironment.InMemoryPrefs).data
        assertEquals(2, after["module_config_prefs"]?.get("backtap_sensitivity"))
        assertEquals(true, after["external_module_providers"]?.get("provider_enabled_com.example.a"))
        assertEquals(false, after["external_module_providers"]?.get("provider_enabled_com.example.b"))
        assertEquals(
            setOf("com.example.a", "com.example.b"),
            after["external_module_providers"]?.get("known_providers"),
        )
    }

    @Test
    fun `every whitelisted key still exists in the codebase`() {
        val roots = listOf(
            File("src/main/java/com/chaomixian/vflow/ui/settings"),
            File("src/main/java/com/chaomixian/vflow/speech/voice"),
            File("src/main/java/com/chaomixian/vflow/extension"),
        ).filter { it.isDirectory }
        assertTrue("没有扫到源码目录", roots.isNotEmpty())
        val text = roots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() }
            .joinToString("\n") { it.readText() }

        val missing = ModuleScope.MODULE_KEYS.filterNot { text.contains("\"$it\"") }
        assertTrue(
            "❌ 这些键在源码里找不到（键名可能改了，那会让备份静默少项）：$missing",
            missing.isEmpty(),
        )
        assertTrue(text.contains("\"known_providers\""))
        assertTrue(text.contains("\"provider_enabled_"))
    }
}
