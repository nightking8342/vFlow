// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/WebDavSecretsTranscodeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.FakeWebDavBackup
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.backup.SecretEnvelope
import com.chaomixian.vflow.core.backup.WebDavBackupEntry
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **WebDAV 配置转档的端到端测试**（T6 的核心交付之一）。
 *
 * ## ⚠️⚠️ 为什么必须是转档
 *
 * 落盘的密码是**设备 Keystore 密文**（alias `vflow_webdav_password_v1`）。
 * Keystore 密钥**与设备绑定**，换机后解不开。直接把它塞进备份
 * ＝ 导出一坨换机后无法解密的字节，而用户看到「配置在备份里」，
 * 以为备了其实没备 —— 典型静默失效。
 *
 * ## 「换机」场景怎么模拟
 *
 * 用**两个不同的 `FakeWebDavBackup` 实例**：导出侧一个（它的 `readAll` 给出明文）、
 * 导入侧一个（它的 `writeAll` 收到明文、重新「加密」）——
 * 而中间传的是**备份口令的密文**（`SecretContext.seal`）。
 * 两侧没有任何共享状态，正是「换了一台设备」的语义。
 */
class WebDavSecretsTranscodeTest {

    private val scope = SecretsScope()

    /**
     * ⚠️ **每次调用都返回同一个实例**是必须的：`SecretContext` 的 salt 是
     * **构造时随机生成**的（设计使然，见 `SecretContext` 的 KDoc），
     * 两次 `ctx()` 得到的是**两个不同的派生密钥** —— 用后者去 `openSlot`
     * 前者密封的 payload 必然抛 `AeadFailure`。
     * 本类的「换机」语义靠**两个不同的 FakeWebDavBackup 实例**表达，
     * 而不是靠两个不同的加密上下文。
     */
    private val sharedCtx: SecretContext by lazy {
        SecretContext("correct horse battery staple".toCharArray())
    }

    private fun ctx(passphrase: String = "correct horse battery staple"): SecretContext {
        require(passphrase == "correct horse battery staple") {
            "本测试只用一个共享上下文；不同的口令请显式构造"
        }
        return sharedCtx
    }

    private fun entry(
        id: String = "cfg-1",
        name: String = "我的网盘",
        password: String? = "p@ss w0rd 中文 🔐",
        baseUrl: String = "https://dav.example.com"
    ) = WebDavBackupEntry(
        id = id, name = name, baseUrl = baseUrl, username = "user",
        password = password, allowInsecureTls = false, timeoutSeconds = 15,
        remoteBasePath = "vflow",
    )

    /** 导出 → 解开（模拟 `BackupPipeline` 的就地解密）→ 交给导入侧。 */
    private fun exportThenOpen(
        env: FakeBackupEnvironment,
        passphrase: String = "correct horse battery staple"
    ): ScopePayload {
        val exportingCtx = ctx(passphrase)
        val payload = scope.export(env, exportingCtx)!!
        return ScopePayload(
            payload.count,
            JsonParser.parseString(exportingCtx.openSlot(payload.data)),
            payload.scrubbedFields,
        )
    }

    // ── 核心：换机往返 ──────────────────────────────────────

    @Test
    fun `webdav passwords survive a device change as plaintext inside the sealed payload`() {
        // 「旧设备」：有两个配置。
        val oldDevice = FakeBackupEnvironment()
        (oldDevice.webDavBackup as FakeWebDavBackup).entries += listOf(
            entry("cfg-1", "网盘 A", "password-A"),
            entry("cfg-2", "网盘 B", "password-B"),
        )

        val openPayload = exportThenOpen(oldDevice)

        // 「新设备」：一个独立的 store 实例（**不共享任何状态**）。
        val newDevice = FakeBackupEnvironment()
        val newStore = newDevice.webDavBackup as FakeWebDavBackup

        val result = scope.import(newDevice, openPayload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(
            "两条配置都该带过去（imported 含密钥槽 + WebDAV 条目）",
            setOf("cfg-1", "cfg-2"), newStore.entries.map { it.id }.toSet(),
        )
        assertEquals(
            "❌ 密码必须原样到达新设备（明文只在内存里活一次）",
            "password-A", newStore.entries.first { it.id == "cfg-1" }.password,
        )
        assertEquals("password-B", newStore.entries.first { it.id == "cfg-2" }.password)
    }

    @Test
    fun `the sealed payload never contains the plaintext password`() {
        // ⚠️ 安全底线：备份文件（`payload.data`）里不得出现明文。
        val env = FakeBackupEnvironment()
        (env.webDavBackup as FakeWebDavBackup).entries += entry(password = "SUPER-SECRET-PASSWORD")

        val payload = scope.export(env, ctx())!!

        assertTrue(SecretEnvelope.isWrapped(payload.data))
        assertFalse(
            "❌ 密封后的 payload 里残留了明文密码",
            payload.data.toString().contains("SUPER-SECRET-PASSWORD"),
        )
    }

    @Test
    fun `the raw keystore ciphertext is never carried into the backup`() {
        // ⚠️ 反证 ④ 的靶子：把 `webdav_configs_json` 原样搬进 payload 的实现
        //    会在这里变红（那串密文换机解不开）。
        //    本测试用的假 store 里根本**没有**密文字段（`WebDavBackupEntry`
        //    结构上就没有）—— 这条断言的是「导出的 JSON 里不出现 prefs 的原始形状」。
        val env = FakeBackupEnvironment()
        (env.webDavBackup as FakeWebDavBackup).entries += entry()

        val payload = scope.export(env, ctx())!!
        val decrypted = ctx().openSlot(payload.data)

        assertTrue(
            "解出的明文里应当有 webdav_configs 数组",
            decrypted.contains("webdav_configs"),
        )
        assertFalse(
            "❌ `encryptedPassword` 之类的 Keystore 密文字段绝不该出现在备份里",
            decrypted.contains("encryptedPassword"),
        )
    }

    // ── 单条解不开：不整体失败 + 位置可见 ───────────────────

    @Test
    fun `a config whose password cannot be decrypted does not fail the whole export`() {
        val env = FakeBackupEnvironment()
        val store = env.webDavBackup as FakeWebDavBackup
        store.entries += listOf(
            entry("cfg-ok", "好的", "good-password"),
            entry("cfg-broken", "坏的", "irrelevant"),
        )
        store.undecryptableIds += "cfg-broken"
        // 同时放一个普通密钥槽，证明「密钥部分照样导得出去」。
        (env.secretStore as FakeBackupEnvironment.InMemorySecretStore)
            .data["ai_config"] = linkedMapOf("api_key" to "sk-live")

        val payload = scope.export(env, ctx())!!

        assertNotNull("单条坏配置不得让整次导出失败", payload)
        val decrypted = ctx().openSlot(payload.data)
        assertTrue("好的那条要照常导出", decrypted.contains("good-password"))
        assertTrue(
            "坏的那条**保留配置但不带密码**（地址/用户名仍有用）",
            decrypted.contains("cfg-broken"),
        )
        assertFalse(
            "❌ 坏的那条不得写出任何 password 值",
            decrypted.contains("\"password\":\"irrelevant\""),
        )
        assertTrue(
            "普通密钥槽照样导出（不被 WebDAV 的问题牵连）",
            decrypted.contains("sk-live"),
        )
    }

    @Test
    fun `an undecryptable config is reported in scrubbed fields so the user can see it`() {
        // ⚠️ 不复用这条通道的话用户完全看不到「这条配置没带密码」——
        //    换机后发现连不上会去查服务器（正是要避免的静默失效）。
        val env = FakeBackupEnvironment()
        val store = env.webDavBackup as FakeWebDavBackup
        store.entries += entry("cfg-1", "我的网盘", "x")
        store.undecryptableIds += "cfg-1"

        val payload = scope.export(env, ctx())!!

        assertEquals(
            "位置串必须形如 `webdav.<配置名>.password`（与 `<stepId>.<paramId>` 区分开）",
            listOf("webdav.我的网盘.password"),
            payload.scrubbedFields,
        )
    }

    // ── null vs "" 的语义（风险 R15 的靶子） ─────────────────

    @Test
    fun `a null password in the backup does not wipe the local password`() {
        // ⚠️⚠️ 反证 ④' 的靶子。`writeAll` 里把 `e.password` 写成
        //     `e.password ?: ""` 会让这条变红。
        //     `null` 的语义是「本次不动密码」，`""` 是「把密码设成空」。
        val newDevice = FakeBackupEnvironment()
        val newStore = newDevice.webDavBackup as FakeWebDavBackup
        // 新设备本地已有同 id 的配置，且有密码。
        newStore.entries += entry("cfg-1", "我的网盘", "LOCAL-PASSWORD")

        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"webdav_configs":[{"id":"cfg-1","name":"我的网盘","baseUrl":"https://dav.example.com","username":"user","allowInsecureTls":false,"timeoutSeconds":15,"remoteBasePath":""}]}"""
            ),
        )

        scope.import(newDevice, payload, ImportMode.MERGE)

        assertEquals(
            "❌ 备份里没带密码（null）时，本机原有密码必须保留 —— " +
                "传成空串会把它抹掉，而用户看到的是「导入成功」",
            "LOCAL-PASSWORD",
            newStore.entries.first { it.id == "cfg-1" }.password,
        )
    }

    @Test
    fun `the production write path passes a null password through instead of an empty string`() {
        // ⚠️⚠️ 上面那条语义测试用的是 `FakeWebDavBackup`，它自己实现了
        //     「null ⇒ 保留原密码」—— 于是**生产侧的 `AndroidWebDavBackup`
        //     即使写成 `e.password ?: ""`，上面那条也照样绿**（实测确认过：
        //     改坏 `AndroidBackupEnvironment.kt` 那行后 275 例全通过）。
        //
        //     这正是本仓库反复踩过的形态：**测试经过了类型，却没经过调用点**。
        //     故这里补一条**源码扫描型**断言，锁住生产代码里那一次调用。
        val code = com.chaomixian.vflow.core.backup.SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/backup/AndroidBackupEnvironment.kt"
        )
        val body = com.chaomixian.vflow.core.backup.SourceScan.functionBody(
            code, "override fun writeAll("
        )
        assertNotNull("❌ 没能截取 AndroidWebDavBackup.writeAll 的函数体（签名变了？）", body)
        assertTrue(
            "❌ 生产代码里必须把 `entry.password` **原样**传给 upsert —— " +
                "任何 `?: \"\"` / `?: empty` 都会把本机已存的密码抹掉（而用户看到「导入成功」）。\n" +
                "实际函数体：\n$body",
            body!!.contains("WebDavConfigStore.upsert(ctx, config, entry.password)"),
        )
        assertFalse(
            "❌ 出现了把 null 归一成空串的写法 —— 那会抹掉本机密码",
            body.contains("?: \"\""),
        )
    }

    @Test
    fun `a present password overwrites the local one`() {
        val newDevice = FakeBackupEnvironment()
        val newStore = newDevice.webDavBackup as FakeWebDavBackup
        newStore.entries += entry("cfg-1", "我的网盘", "OLD")

        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"webdav_configs":[{"id":"cfg-1","name":"我的网盘","baseUrl":"https://dav.example.com","username":"user","password":"NEW","allowInsecureTls":false,"timeoutSeconds":15,"remoteBasePath":""}]}"""
            ),
        )

        scope.import(newDevice, payload, ImportMode.MERGE)

        assertEquals("带了密码就要覆盖", "NEW", newStore.entries.first().password)
    }

    @Test
    fun `a config that exists only in the backup is created without a password when none is given`() {
        val newDevice = FakeBackupEnvironment()
        val newStore = newDevice.webDavBackup as FakeWebDavBackup

        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"webdav_configs":[{"id":"cfg-new","name":"新的","baseUrl":"https://x","username":"u"}]}"""
            ),
        )

        scope.import(newDevice, payload, ImportMode.MERGE)

        val created = newStore.entries.first { it.id == "cfg-new" }
        assertNull(
            "新增且没有密码 ⇒ password 为 null（不是空串）—— 由实现侧决定落盘形态",
            created.password,
        )
    }

    // ── REPLACE 语义 ────────────────────────────────────────

    @Test
    fun `REPLACE removes local configs missing from the backup`() {
        val newDevice = FakeBackupEnvironment()
        val newStore = newDevice.webDavBackup as FakeWebDavBackup
        newStore.entries += listOf(entry("keep", "保留"), entry("drop", "删掉"))

        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"webdav_configs":[{"id":"keep","name":"保留","baseUrl":"https://x","username":"u","password":"p"}]}"""
            ),
        )

        scope.import(newDevice, payload, ImportMode.REPLACE)

        assertEquals(listOf("keep"), newStore.entries.map { it.id })
    }

    @Test
    fun `MERGE keeps local configs missing from the backup`() {
        val newDevice = FakeBackupEnvironment()
        val newStore = newDevice.webDavBackup as FakeWebDavBackup
        newStore.entries += listOf(entry("keep", "本地独有"))

        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"webdav_configs":[{"id":"from-backup","name":"备份来的","baseUrl":"https://x","username":"u","password":"p"}]}"""
            ),
        )

        scope.import(newDevice, payload, ImportMode.MERGE)

        assertEquals(
            setOf("keep", "from-backup"), newStore.entries.map { it.id }.toSet(),
        )
    }

    // ── 不静默丢弃 ──────────────────────────────────────────

    @Test
    fun `a backup containing webdav config is logged when the environment cannot write it`() {
        // ⚠️ 本环境的密钥部分照样能导进去，只有 WebDAV 这块没写 ——
        //    静默跳过会让用户以为配置恢复了。
        val env = FakeBackupEnvironment()
        env.webDavBackup = null

        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"ai_config.api_key":"sk","webdav_configs":[{"id":"c","name":"n","baseUrl":"https://x"}]}"""
            ),
        )

        val result = scope.import(env, payload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals("密钥部分照样导入", "sk", env.secretStore!!.getString("ai_config", "api_key"))
        assertTrue(
            "❌ 本环境写不了 WebDAV 时必须留日志，不能静默丢弃",
            env.logs.any { it.second.contains("WebDAV") },
        )
    }

    @Test
    fun `webdav configs are not counted as unknown keys`() {
        // ⚠️ 反证：`webdav_configs` 不是扁平键 `prefs.key`，若不先摘出会被
        //     白名单判定当「不认识」而 `skipped++` 静默丢掉。
        val env = FakeBackupEnvironment()
        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"ai_config.api_key":"sk","webdav_configs":[{"id":"c","name":"n","baseUrl":"https://x","password":"p"}]}"""
            ),
        )

        val result = scope.import(env, payload, ImportMode.MERGE)

        assertEquals(
            "webdav_configs 不该被算成 skipped（它被显式摘出来单独处理）",
            0, result.skipped,
        )
        assertEquals(
            "1 个密钥槽 + 1 条 WebDAV 配置",
            2, result.imported,
        )
    }

    @Test
    fun `a malformed webdav entry is dropped without throwing`() {
        val env = FakeBackupEnvironment()
        val payload = ScopePayload(
            1,
            JsonParser.parseString(
                """{"webdav_configs":[{"name":"没有 id 的条目"},{"id":"ok","name":"正常的","baseUrl":"https://x","password":"p"}]}"""
            ),
        )

        val result = scope.import(env, payload, ImportMode.MERGE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(
            "坏形状的条目被丢弃、正常的照常导入",
            listOf("ok"), (env.webDavBackup as FakeWebDavBackup).entries.map { it.id },
        )
    }

    @Test
    fun `an empty webdav list does not add the key to the payload`() {
        // ⚠️ 一个都没有时不写 `webdav_configs` 键 —— 「键不存在」比
        //    「键是空数组」更明确，也让老版本读到时不会误解。
        val env = FakeBackupEnvironment()
        val payload = scope.export(env, ctx())!!

        assertFalse(
            "空列表不该产出 webdav_configs 键",
            ctx().openSlot(payload.data).contains("webdav_configs"),
        )
    }

    // ── 与 SECRET_SLOTS 的关系 ──────────────────────────────

    @Test
    fun `webdav does not enter the secret slots whitelist`() {
        // ⚠️ WebDAV **不是**一个「键→值」的字符串槽，是一个需要转档的结构。
        //    它进了 SECRET_SLOTS 反而会走错路径（照搬 Keystore 密文）。
        assertEquals(
            "SECRET_SLOTS 仍然是 10 项（WebDAV 不进去）",
            10, SecretsScope.SECRET_SLOTS.size,
        )
        assertTrue(
            "`webdav_configs` 必须与 SECRET_SLOTS / EXCLUDED_PREFS 同处，让「秘密范围的全部键」可见",
            SecretsScope.KEY_WEBDav == "webdav_configs",
        )
        assertFalse(
            "`webdav_configs` 不该是任何 SECRET_SLOT 的键名",
            SecretsScope.SECRET_SLOTS.any { it.second == SecretsScope.KEY_WEBDav },
        )
    }
}
