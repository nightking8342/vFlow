// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/SettingsScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopeGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [SettingsScope] 的逐键测试。
 *
 * ## ⚠️ 本文件真正在防的是什么
 *
 * `vFlowPrefs` 里有 **13 个明确排除的键**，其中两条是**硬排除**
 * （`is_first_run` / `disclaimer_accepted`）—— 恢复它们会造成
 * 「替新设备用户接受免责声明」这种**合规**问题，而不是体验问题。
 * 排除清单若被谁「顺手补全」，不会有任何报错。
 */
class SettingsScopeTest {

    private val scope = SettingsScope()

    // ── 静态声明 ────────────────────────────────────────────

    @Test
    fun `declares the contract from the design`() {
        assertEquals("settings", scope.id)
        assertEquals(ScopeGroup.CONFIG, scope.group)
        assertFalse("settings 不含密钥，不该要求口令", scope.sensitive)
        assertTrue("settings 默认勾选", scope.defaultIncluded)
        assertEquals(40, scope.importOrder)
        assertEquals(emptyList<String>(), scope.dependsOn)
        assertEquals(setOf("vFlowPrefs"), scope.specs.map { it.prefsName }.toSet())
    }

    // ── 硬排除（合规/设备绑定） ─────────────────────────────

    @Test
    fun `the first run flag is hard excluded`() {
        // ⚠️ 首次运行标记是「本设备本用户」的状态，跨设备复制等于
        //    跳过新设备用户的引导（用户会直接面对一个「已配好」的空壳）。
        assertFalse(
            "is_first_run 绝不能被备份/恢复",
            scope.specs.first().matches("is_first_run"),
        )
    }

    @Test
    fun `the disclaimer acknowledgement is hard excluded`() {
        // ⚠️⚠️ 这是**合规**问题不是偏好问题：「已接受」是对**当前设备/用户**的确认，
        //     跨设备复制 = 替别人接受了一份法律声明。
        assertFalse(
            "disclaimer_accepted 绝不能被备份/恢复",
            scope.specs.first().matches("disclaimer_accepted"),
        )
    }

    @Test
    fun `the core dex fingerprint is hard excluded`() {
        // ⚠️ 恢复它会让「Core 代码变了需重启」的提示**永远不出现**
        //    ⇒ 用户静默跑旧 Core 代码（本仓库已为此踩过三次）。
        assertFalse(
            "core_last_launched_dex_fingerprint 绝不能被备份/恢复",
            scope.specs.first().matches("core_last_launched_dex_fingerprint"),
        )
    }

    // ── 设备绑定类排除 ──────────────────────────────────────

    @Test
    fun `device bound keys are excluded`() {
        val spec = scope.specs.first()
        listOf(
            "default_shell_mode",
            "preferred_core_launch_mode",
            "core_auto_start_enabled",
            "mutual_keep_alive_enabled",
            "core_manual_stop_requested",
            "core_unix_socket_enabled",
            "forceKeepAliveEnabled",
            "autoEnableAccessibility",
            "accessibilityGuardEnabled",
        ).forEach { key ->
            assertFalse(
                "$key 与设备本地的通道/授权态绑定，换机后静默失效 ⇒ 不该被备份",
                spec.matches(key),
            )
        }
    }

    @Test
    fun `runtime and diagnostic keys are excluded`() {
        val spec = scope.specs.first()
        listOf("debugLoggingEnabled", "execution_logs").forEach { key ->
            assertFalse("$key 是运行/诊断态，不是用户配置", spec.matches(key))
        }
    }

    // ── 收的键 ──────────────────────────────────────────────

    @Test
    fun `appearance and behaviour preferences are included`() {
        val spec = scope.specs.first()
        listOf(
            "dynamicColorEnabled",
            "colorfulWorkflowCardsEnabled",
            "appScale",
            "liquidGlassNavBarEnabled",
            "workflow_sort_mode",
            "workflow_layout_mode",
            "hideFromRecents",
            "enableTypeFilter",
            "allowShowOnLockScreen",
            "allowPopupKeepScreenOn",
            "keepDeviceAwakeDuringWorkflow",
            "defaultErrorPolicy",
            "defaultRetryCount",
            "defaultRetryInterval",
            "progressNotificationEnabled",
            "backgroundServiceNotificationEnabled",
            "autoCheckUpdatesEnabled",
            "telemetryEnabled",
            "accessibilityDisguiseEnabled",
            "sherpa_ncnn_download_source",
        ).forEach { key ->
            assertTrue("$key 应当被备份", spec.matches(key))
        }
    }

    @Test
    fun `the whitelist is exactly twenty keys`() {
        assertEquals(20, SettingsScope.EXACT_KEYS.size)
        assertTrue(
            "不得有重复键（Set 天然去重，但常量若被改成 List 就会静默吃下重复）",
            SettingsScope.EXACT_KEYS.size == scope.specs.first().exactKeys.size,
        )
    }

    // ── 端到端往返 ──────────────────────────────────────────

    @Test
    fun `values of every type survive the round trip`() {
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        store.data["vFlowPrefs"] = linkedMapOf<String, Any?>(
            "dynamicColorEnabled" to true,
            "appScale" to 1.15f,
            "defaultRetryCount" to 3,
            "workflow_sort_mode" to "Default",
        )

        val payload = scope.export(env, null)!!
        val importing = FakeBackupEnvironment()
        val result = scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        val after = (importing.prefs as FakeBackupEnvironment.InMemoryPrefs).data["vFlowPrefs"]!!
        assertEquals(true, after["dynamicColorEnabled"])
        assertEquals(1.15f, after["appScale"])
        assertEquals(3, after["defaultRetryCount"])
        assertEquals("Default", after["workflow_sort_mode"])
    }

    // ── 反僵尸：白名单的键在源码里确实存在 ──────────────────

    @Test
    fun `every whitelisted key still exists in the codebase`() {
        // ⚠️ 白名单是人工维护的 —— 一个陈旧的条目（键名改了）会让备份
        //     **静默地**少备一项，而「导出成功」看起来毫无异常。
        val roots = listOf(
            File("src/main/java/com/chaomixian/vflow/ui"),
            File("src/main/java/com/chaomixian/vflow/services"),
            File("src/main/java/com/chaomixian/vflow/core"),
            File("src/main/java/com/chaomixian/vflow/speech"),
        ).filter { it.isDirectory }
        assertTrue("没有扫到源码目录 —— 路径写错了？", roots.isNotEmpty())

        val text = roots
            .flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() }
            .joinToString("\n") { it.readText() }

        val missing = SettingsScope.EXACT_KEYS.filterNot { text.contains("\"$it\"") }
        assertTrue(
            "❌ 白名单里这些键在源码中找不到：$missing\n" +
                "键名可能改了 —— 那会让它们**静默地**备不到（导出成功但少了几项）。",
            missing.isEmpty(),
        )
    }

    // ── 与本 scope 无关的键 ─────────────────────────────────

    @Test
    fun `keys of other prefs files are not matched`() {
        val spec = scope.specs.first()
        assertFalse("别的 prefs 的键不该命中", spec.matches("chat_default_preset_id"))
        assertNull(
            "settings 不该管凭证 —— 那是 secrets scope",
            com.chaomixian.vflow.core.backup.scopes.SecretsScope.SECRET_SLOTS
                .firstOrNull { scope.specs.any { s -> s.matches(it.second) && s.prefsName == it.first } },
        )
    }
}
