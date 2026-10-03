// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/SecretsScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.backup.SecretEnvelope
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [SecretsScope] 的纯 JVM 单测。 */
class SecretsScopeTest {

    private val scope = SecretsScope()

    private fun ctx() = SecretContext("pw".toCharArray(), ByteArray(16) { it.toByte() }, 1000)

    private fun envWithSecrets(values: Map<String, String>): FakeBackupEnvironment {
        val env = FakeBackupEnvironment()
        seedSlots(env, values)
        return env
    }

    private fun seedSlots(env: FakeBackupEnvironment, flat: Map<String, String>) {
        flat.forEach { (flatKey, value) ->
            val slot = SecretsScope.slotOf(flatKey) ?: error("测试夹具用了白名单外的槽：$flatKey")
            env.secretStore.putString(slot.first, slot.second, value)
        }
    }

    // ── 元数据与声明 ────────────────────────────────────────

    @Test
    fun `declares itself sensitive with the secret group and is not included by default`() {
        assertEquals("secrets", scope.id)
        assertEquals(ScopeGroup.SECRET, scope.group)
        assertTrue("sensitive 必须为 true —— 否则无口令时会写明文密钥", scope.sensitive)
        assertFalse(
            "SECRET 组不能默认勾选 —— 否则无口令时静默不写（用户以为备了），" +
                "或有口令时未经明确同意就写密钥",
            scope.defaultIncluded,
        )
        assertEquals("必须排在 workflows(10) 之后", 100, scope.importOrder)
        assertEquals("与任何 scope 都无数据依赖，声明假依赖是语义上的假话", emptyList<String>(), scope.dependsOn)
    }

    @Test
    fun `is registered in the scope registry`() {
        // ⚠️ T2 在 BackupScopeRegistry.initialize() 里追加了一行注册。
        //     这条断言是 T2 自己的（不依赖 T1 的测试文件 —— 按父会话决断 1，
        //     那些文件没有复制到本 worktree）。
        BackupScopeRegistry.initialize()
        assertNotNull(
            "SecretsScope 没有注册进 BackupScopeRegistry —— 它会永远不出现在 UI 的勾选清单里",
            BackupScopeRegistry.get("secrets"),
        )
        assertEquals(SecretsScope::class.java, BackupScopeRegistry.get("secrets")!!.javaClass)

        val order = BackupScopeRegistry.importOrder().map { it.id }
        // ⚠️ T6 追加了四个 prefs 类 scope（settings / chat / modules / tiles），
        //    故这里不再断言「完整顺序」（那份断言归 BackupScopeRegistryTest），
        //    只锁本 scope 的**相对位置**：它必须排在 workflows 之后。
        //    理由见 SecretsScope.importOrder 的 KDoc（避免「密钥是新的、
        //    工作流是旧的」这种最难解释的中间态）。
        assertTrue(
            "secrets 必须排在 workflows 之后，实际：$order",
            order.indexOf("workflows") < order.indexOf("secrets"),
        )
    }

    // ── 导出 ────────────────────────────────────────────────

    @Test
    fun `export returns null when there is no passphrase`() {
        // ⚠️⚠️ 本 scope 的**安全闸**。删掉它 ⇒ 没勾密钥的用户照样把全部凭证明文
        //      写进备份文件（反证 R7 锁住这条）。
        val env = envWithSecrets(mapOf("ai_config.api_key" to "sk-live"))

        assertNull(
            "无口令时 export 必须返回 null —— 绝不明文写出密钥",
            scope.export(env, null),
        )
    }

    @Test
    fun `export returns null when the environment has no secret store`() {
        // 纯 JVM 环境下 secretStore 默认 null ⇒ 「本环境不适用」。
        val noStore = NoSecretStoreEnvironment()
        assertNull(scope.export(noStore, ctx()))
    }

    /** 一个**不支持**密钥的环境 —— 用来验证 `secretStore == null` 这一支。 */
    private class NoSecretStoreEnvironment :
        com.chaomixian.vflow.core.backup.BackupEnvironment
        by FakeBackupEnvironment() {
        override val secretStore: com.chaomixian.vflow.core.backup.SecretStore? = null
    }

    @Test
    fun `export seals the whole payload and never leaks plaintext`() {
        val env = envWithSecrets(mapOf("ai_config.api_key" to "sk-THIS-MUST-NOT-APPEAR"))
        val payload = scope.export(env, ctx())

        assertNotNull(payload)
        assertTrue(
            "payload 的 data 应当是一个 \$enc 节点",
            SecretEnvelope.isWrapped(payload!!.data),
        )
        assertFalse(
            "加密后的 payload 里不得残留明文",
            payload.data.toString().contains("sk-THIS-MUST-NOT-APPEAR"),
        )
    }

    @Test
    fun `export round trip restores exactly the values that were exported`() {
        val original = linkedMapOf(
            "ai_config.api_key" to "sk-live-123",
            "module_config_prefs.feishu_app_secret" to "fs-secret",
            "module_config_prefs.chat_provider_configs_json" to """{"k":{"apiKey":"inner"}}""",
        )
        val exporting = envWithSecrets(original)
        val exportingCtx = ctx()
        val payload = scope.export(exporting, exportingCtx)!!

        // `import` 拿到的必须是**已解密**的明文形态 —— 解密由 `BackupPipeline` 做
        // （见 `SecretsScope.import` 的 KDoc）。这里手工走同一步，
        // 以证明「导出密封 → 解开 → 导入」这条链是通的。
        val decrypted = ScopePayload(
            payload.count,
            com.google.gson.JsonParser.parseString(exportingCtx.openSlot(payload.data))
        )

        // 用一个**全新的空环境**接收，才能证明值是从 payload 里来的
        // （而不是本地原本就有）。
        val importing = FakeBackupEnvironment()
        val result = scope.import(importing, decrypted, ImportMode.MERGE)
        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(original.size, result.imported)

        original.forEach { (flatKey, expected) ->
            val slot = SecretsScope.slotOf(flatKey)!!
            assertEquals(
                "$flatKey 经导出→导入应当逐字还原",
                expected,
                importing.secretStore.getString(slot.first, slot.second),
            )
        }
    }

    @Test
    fun `an empty secret set still produces a sealed payload rather than null`() {
        // 「勾了、有口令、但本机确实没配任何密钥」是一个**有效结果**，
        // 不是「本次不适用」。返回 null 会让用户以为备份里没有 secrets 这一项。
        val payload = scope.export(FakeBackupEnvironment(), ctx())
        assertNotNull("空密钥集也必须返回 payload（count=0），不能是 null", payload)
        assertEquals(0, payload!!.count)
        assertTrue(SecretEnvelope.isWrapped(payload.data))
    }

    // ── 导入 ────────────────────────────────────────────────

    @Test
    fun `import with a null payload is skipped and does not touch the environment`() {
        val env = FakeBackupEnvironment()
        val result = scope.import(env, null, ImportMode.MERGE)
        assertEquals(ImportStatus.SKIPPED_NOT_SELECTED, result.status)
        val store = env.secretStore as FakeBackupEnvironment.InMemorySecretStore
        assertTrue("payload 为 null 时不得写任何东西", store.data.isEmpty())
    }

    @Test
    fun `import writes each slot back into the store`() {
        val env = FakeBackupEnvironment()
        val payload = SecretsScope.slotPayload(
            mapOf(
                "ai_config.api_key" to "sk-restored",
                "module_config_prefs.feishu_app_secret" to "fs-restored",
            )
        )

        val result = scope.import(env, payload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(2, result.imported)
        assertEquals("sk-restored", env.secretStore.getString("ai_config", "api_key"))
        assertEquals("fs-restored", env.secretStore.getString("module_config_prefs", "feishu_app_secret"))
    }

    @Test
    fun `import skips keys outside the whitelist instead of writing them`() {
        // ⚠️ 安全边界：一个被构造的备份**不得**往任意 prefs 的任意键里写值
        //     （那是一条从「恢复备份」到「任意配置覆盖」的提权路径）。
        val env = FakeBackupEnvironment()
        val payload = SecretsScope.slotPayload(
            mapOf(
                "ai_config.api_key" to "ok",
                "module_config_prefs.network_proxy" to "evil",   // 白名单外
                "some_other_prefs.whatever" to "evil",           // prefs 名都不认识
            )
        )

        val result = scope.import(env, payload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(1, result.imported)
        assertEquals(2, result.skipped)
        assertNull(
            "白名单外的键绝不能被写入 —— 否则是任意配置覆盖",
            env.secretStore.getString("module_config_prefs", "network_proxy"),
        )
    }

    @Test
    fun `REPLACE also only upserts and never deletes local keys`() {
        // ⚠️ **刻意行为**（方案 §6.5，已核实）：`module_config_prefs` 里混着
        //     `network_proxy` / `backtap_sensitivity` / `app_start_*` 等**非密钥**配置。
        //     在这里按备份内容清理会**误删用户设置**。
        //     代价是 REPLACE 不删本地多余的密钥键 —— 故这条断言锁住那个代价。
        val env = envWithSecrets(
            mapOf(
                "ai_config.api_key" to "local-key",
                "module_config_prefs.feishu_app_secret" to "local-only",
            )
        )
        val payload = SecretsScope.slotPayload(mapOf("ai_config.api_key" to "from-backup"))

        scope.import(env, payload, ImportMode.REPLACE)

        assertEquals("from-backup", env.secretStore.getString("ai_config", "api_key"))
        assertEquals(
            "REPLACE 也不得删除本地多余的密钥键（否则会误删用户的非密钥设置）",
            "local-only",
            env.secretStore.getString("module_config_prefs", "feishu_app_secret"),
        )
        assertTrue(
            "REPLACE 下应当记一条 W 说明这个偏差",
            env.logs.any { it.first == com.chaomixian.vflow.core.backup.LogLevel.W },
        )
    }

    @Test
    fun `import fails cleanly when the payload is not a JSON object`() {
        val env = FakeBackupEnvironment()
        val bad = ScopePayload(1, com.google.gson.JsonArray())
        val result = scope.import(env, bad, ImportMode.MERGE)
        assertEquals(ImportStatus.FAILED, result.status)
    }

    // ── 白名单与源码一致性（反僵尸）────────────────────────

    @Test
    fun `the ten whitelist slots match the keys actually used in the codebase`() {
        // ⚠️ 白名单是**人工维护**的 —— 一个陈旧的条目（键名改了）会让备份
        //     静默地什么也备不到，而「导出成功、count=0」看起来毫无异常。
        // ⚠️ 扫源码而不读外部清单文件：那份文件在主仓库 `.mindfs/` 下，
        //     不在本 worktree，单测工作目录解析不到。
        assertEquals(
            "方案 §2.8 的实测表是 10 个槽",
            10, SecretsScope.SECRET_SLOTS.size,
        )

        val keys = SecretsScope.SECRET_SLOTS.map { it.second }.toSet()
        assertEquals("不得有重复键", SecretsScope.SECRET_SLOTS.size, keys.size)

        val roots = listOf(
            File("src/main/java/com/chaomixian/vflow/ui/chat"),
            File("src/main/java/com/chaomixian/vflow/ui/workflow_editor"),
            File("src/main/java/com/chaomixian/vflow/integration"),
            File("src/main/java/com/chaomixian/vflow/ui/settings"),
        ).filter { it.isDirectory }
        assertTrue("没有扫到源码目录 —— 路径写错了？", roots.isNotEmpty())
        val text = roots.flatMap { it.walkTopDown().filter { f -> f.isFile && f.name.endsWith(".kt") }.toList() }
            .joinToString("\n") { it.readText() }

        for (key in keys) {
            assertTrue(
                "❌ 白名单里的 `$key` 在源码里找不到 —— 键名可能改了，\n" +
                    "那会让备份**静默地**什么也备不到（导出成功但 count=0）。",
                text.contains("\"$key\""),
            )
        }
    }

    @Test
    fun `the excluded prefs is documented and not part of the whitelist`() {
        assertEquals("vflow_api_tokens", SecretsScope.EXCLUDED_PREFS)
        assertFalse(
            "vflow_api_tokens 必须不在白名单里（可再生、安全敏感）",
            SecretsScope.SECRET_SLOTS.any { it.first == SecretsScope.EXCLUDED_PREFS },
        )
    }

    @Test
    fun `slotOf round trips with flatKeyOf and rejects unknown slots`() {
        for (slot in SecretsScope.SECRET_SLOTS) {
            val flat = SecretsScope.flatKeyOf(slot)
            assertEquals(slot, SecretsScope.slotOf(flat))
        }
        assertNull(SecretsScope.slotOf("no_dot_here"))
        assertNull(SecretsScope.slotOf(".leading"))
        assertNull(SecretsScope.slotOf("trailing."))
        assertNull(SecretsScope.slotOf("unknown_prefs.some_key"))
    }

    private fun plainAsObject(text: String): JsonObject =
        com.google.gson.JsonParser.parseString(text).asJsonObject
}
