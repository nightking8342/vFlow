// 文件: test/java/com/chaomixian/vflow/core/backup/BackupPrefsLeakTest.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.backup.scopes.SecretsScope
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **泄漏断言** —— 本任务（T6）最重要的机器化防线。
 *
 * ## ⚠️⚠️ 它守的是什么
 *
 * 四个新 scope 都是 `sensitive == false`（**不需要口令就会写进备份文件**）。
 * 它们的白名单里若混进任何一个凭证键，那份备份就会**明文**带走它 ——
 * 而用户根本没勾「包含密钥」，也**不会收到任何提示**。
 *
 * 静默失效点 6/7 正是这个形态：
 * - 6：工作流步骤的 `api_key` 随 `workflow_list` 明文导出（由 T2 的
 *      `SecretFieldScrubber` 处理，不在本文件范围）；
 * - 7：只勾「聊天」漏勾「密钥」，而 `chat_*_json` 内含 `apiKey`。
 *
 * ## 判据从哪来（不是自己编的清单）
 *
 * 全部从既有事实**派生**：
 * - `SecretsScope.SECRET_SLOTS`（权威凭证清单）
 * - `SecretsScope.EXCLUDED_PREFS`（可再生且敏感，默认排除）
 * - `WebDavConfigStore.PREFS_NAME`（走独立转档通道，绝不走普通 prefs）
 *
 * 这样新增凭证时，本文件**不需要人工同步** —— 新凭证进了 `SECRET_SLOTS`，
 * 任何一个普通 scope 若收它就会自动变红。
 */
class BackupPrefsLeakTest {

    /**
     * 凭证形状的键名子串。
     *
     * ⚠️ 它是**补充**判据，不是主判据（主判据是 `SECRET_SLOTS` 派生）。
     * 因为 `SECRET_SLOTS` 是人工维护的 —— 一个**新加的**、还没登记的凭证
     * （例如某个新集成把 token 写进 prefs 时忘了登记）不会被它抓到，
     * 但会被形状规则抓到。
     */
    private val credentialShape = listOf(
        "token", "secret", "password", "passwd", "api_key", "apikey",
        "device_key", "device_id", "device_token", "push_token",
        "registration_id", "install_id", "fcm_token", "oaid", "imei",
        "android_id", "machine_id", "private_key", "credential",
    )

    /**
     * 人工放行名单。
     *
     * ⚠️ **当前为空**，且**不应该是随便加的**：每加一条都要在下面写明
     * 「为什么这个形状规则的命中是误报」。留空会让下一个人加条目时
     * 先看到「这里本来是空的」，从而多想一想。
     */
    private val allowedByDesign: Set<String> = emptySet()

    private fun prefsScopes(): List<AbstractPrefsScope> =
        BackupScopeRegistry.all().filterIsInstance<AbstractPrefsScope>()

    // ── 主断言 ──────────────────────────────────────────────

    @Test
    fun `no non sensitive scope whitelists the api tokens prefs`() {
        val offenders = prefsScopes()
            .filter { !it.sensitive }
            .flatMap { scope -> scope.specs.map { scope.id to it } }
            .filter { (_, spec) -> spec.prefsName == SecretsScope.EXCLUDED_PREFS }
            .map { (id, spec) -> "$id → ${spec.prefsName}" }

        assertTrue(
            "❌ 以下非敏感 scope 收了 `vflow_api_tokens`：$offenders\n" +
                "那个 prefs 里的 token 可再生且安全敏感，设计上**默认排除且不提供勾选**。\n" +
                "它进任何普通 scope ⇒ 明文写进备份文件。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no non sensitive scope whitelists a known secret slot`() {
        val secretPairs = SecretsScope.SECRET_SLOTS.toSet()
        val offenders = mutableListOf<String>()

        for (scope in prefsScopes().filter { !it.sensitive }) {
            for (spec in scope.specs) {
                for (key in spec.exactKeys) {
                    if ((spec.prefsName to key) in secretPairs) {
                        offenders += "${scope.id} → ${spec.prefsName}/$key"
                    }
                }
                // ⚠️ 前缀规则也要查：前缀可以把一整个含有凭证键的键空间收进来。
                for (prefix in spec.keyPrefixes) {
                    secretPairs
                        .filter { (prefs, key) -> prefs == spec.prefsName && key.startsWith(prefix) }
                        .forEach { (_, key) ->
                            offenders += "${scope.id} → ${spec.prefsName}/$key（经前缀 '$prefix'）"
                        }
                }
            }
        }

        assertTrue(
            "❌ 以下非敏感 scope 的白名单里含 `SecretsScope.SECRET_SLOTS` 里的凭证键：\n" +
                offenders.joinToString("\n") +
                "\n这些键**必须在 secrets scope 里**（要口令才写）—— " +
                "出现在普通 scope 就是「绕过『包含密钥』勾选」= 明文泄漏（静默失效点 7）。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no non sensitive scope whitelists the webdav config prefs`() {
        // ⚠️ WebDAV 配置**必须**走 `WebDavBackupStore` 转档（设备 Keystore 密文
        //     换机解不开），绝不走普通 prefs 通道。
        val offenders = prefsScopes()
            .filter { !it.sensitive }
            .flatMap { scope -> scope.specs.map { scope.id to it } }
            .filter { (_, spec) -> spec.prefsName == "webdav_config_prefs" }
            .map { (id, spec) -> "$id → ${spec.prefsName}" }

        assertTrue(
            "❌ 以下非敏感 scope 收了 `webdav_config_prefs`：$offenders\n" +
                "WebDAV 密码是**设备 Keystore 密文**，换机解不开 ⇒ 必须走转档通道。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no non sensitive scope whitelists a key with a credential like name`() {
        // 形状规则这一层是**补充**判据：抓「还没登记进 SECRET_SLOTS 的新凭证」。
        val offenders = mutableListOf<String>()

        for (scope in prefsScopes().filter { !it.sensitive }) {
            for (spec in scope.specs) {
                for (key in spec.exactKeys) {
                    if (key in allowedByDesign) continue
                    val hit = credentialShape.firstOrNull { key.lowercase().contains(it) }
                    if (hit != null) {
                        offenders += "${scope.id} → ${spec.prefsName}/$key（命中 '$hit'）"
                    }
                }
            }
        }

        assertTrue(
            "❌ 以下非敏感 scope 的白名单里有「看起来是凭证」的键：\n" +
                offenders.joinToString("\n") +
                "\n若确认它不是凭证（已逐字读过读写点），请加进 `allowedByDesign` 并写明理由；\n" +
                "否则它应当归 secrets scope（否则会明文写进备份）。",
            offenders.isEmpty(),
        )
    }

    @Test
    fun `no non sensitive scope whitelists a device identity shaped key`() {
        // ⚠️ 设备标识类的键**当前在本仓库不存在**（已全仓 grep 核实）——
        //     本用例是**为将来**留的：这类键的读写点通常在远程推送 SDK 里，
        //     最容易顺手写进 prefs，而跨设备复制设备标识会让推送串号。
        val identityShape = listOf(
            "device_id", "device_token", "push_token", "registration_id",
            "install_id", "fcm_token", "oaid", "imei", "android_id", "machine_id",
        )
        val offenders = mutableListOf<String>()

        for (scope in prefsScopes().filter { !it.sensitive }) {
            for (spec in scope.specs) {
                for (key in spec.exactKeys) {
                    val hit = identityShape.firstOrNull { key.lowercase().contains(it) }
                    if (hit != null) offenders += "${scope.id} → ${spec.prefsName}/$key（命中 '$hit'）"
                }
            }
        }

        assertTrue(
            "❌ 非敏感 scope 里有「与设备标识绑定」的键：$offenders\n" +
                "跨设备复制设备标识会让远程推送/统计串号。",
            offenders.isEmpty(),
        )
    }

    // ── 防空转 ──────────────────────────────────────────────

    @Test
    fun `the leak scan covers enough scopes to be meaningful`() {
        val scopes = prefsScopes()
        assertTrue(
            "❌ 被判据覆盖的非敏感 scope 只有 ${scopes.filter { !it.sensitive }.size} 个 —— " +
                "少于 4 说明 `all()` / 类型判据失效，上面几条都在空转",
            scopes.count { !it.sensitive } >= 4,
        )
        // 判据源本身必须非空，否则「不得含 SECRET_SLOTS 的键」恒真。
        assertTrue(
            "SECRET_SLOTS 为空 ⇒ 第 2 条断言空转",
            SecretsScope.SECRET_SLOTS.isNotEmpty(),
        )
        assertTrue(
            "被检查的键总数太少 ⇒ 检查形同虚设",
            scopes.flatMap { it.specs }.sumOf { it.exactKeys.size } >= 30,
        )
    }

    @Test
    fun `the credential shape rule actually matches something that exists`() {
        // ⚠️ 防空转：若 `credentialShape` 的字符串被改坏（例如写成大写），
        //     上面那条会「一条都不命中」而恒绿。
        //     拿一个**确实存在**的凭证键验证这条规则是活的。
        val known = "feishu_app_secret"
        assertTrue(
            "credentialShape 规则被改坏了？'$known' 应当命中 'secret'",
            credentialShape.any { known.contains(it) },
        )
        assertTrue(
            "SECRET_SLOTS 里应当确实有这个键（否则上一条的对照失去意义）",
            SecretsScope.SECRET_SLOTS.any { it.second == known },
        )
    }
}
