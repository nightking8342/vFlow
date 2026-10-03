// 文件: test/java/com/chaomixian/vflow/core/backup/PrefsScopeContractTest.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.backup.scopes.SecretsScope
import com.google.gson.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **四个 prefs 类 scope 的共享契约测试**。
 *
 * ## 为什么必须遍历注册表而不是逐个 scope 写一遍
 *
 * 这些契约（空数据集不得返回 null、REPLACE 只删白名单内的键、…）是
 * **每一个** prefs scope 都要满足的。逐个写的话，新增第五个 scope 时
 * 没有人会记得补 —— 而那正是「静默失效点 12」的形态
 * （用户以为备了、其实没备，或以为只删了自己的东西、其实删了别人的）。
 *
 * 遍历 `all()` 后，新增 scope **自动**进入覆盖范围。
 */
class PrefsScopeContractTest {

    private fun prefsScopes(): List<AbstractPrefsScope> =
        BackupScopeRegistry.all().filterIsInstance<AbstractPrefsScope>()

    private fun envWith(prefs: Map<String, Map<String, Any?>>): FakeBackupEnvironment {
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        prefs.forEach { (name, values) -> store.data[name] = LinkedHashMap(values) }
        return env
    }

    /** 播种：给每个 spec 的白名单键各写一个值。 */
    private fun seed(env: FakeBackupEnvironment, scope: AbstractPrefsScope) {
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        for (spec in scope.specs) {
            val target = store.data.getOrPut(spec.prefsName) { LinkedHashMap() }
            spec.exactKeys.forEach { key -> target[key] = "seed-$key" }
            spec.keyPrefixes.forEach { prefix -> target["${prefix}example.pkg"] = true }
        }
    }

    // ── 覆盖范围不得为空 ─────────────────────────────────────

    @Test
    fun `the contract covers every prefs backed scope and at least four of them`() {
        // ⚠️ 防空转：若 `all()` 被清空或类名改了，下面每条都恒绿。
        val scopes = prefsScopes()
        assertTrue(
            "❌ 应当至少覆盖 4 个 prefs 类 scope（settings/chat/modules/tiles），" +
                "实际 ${scopes.map { it.id }} —— 遍历的判据失效了？",
            scopes.size >= 4,
        )
        assertEquals(
            setOf("settings", "chat", "modules", "tiles"),
            scopes.map { it.id }.toSet(),
        )
        // 每个都必须声明至少一个 spec，否则它其实什么都没备。
        scopes.forEach { scope ->
            assertTrue("scope '${scope.id}' 没有任何 spec", scope.specs.isNotEmpty())
            scope.specs.forEach { spec ->
                assertTrue(
                    "scope '${scope.id}' 的 spec '${spec.prefsName}' 既无精确键也无前缀",
                    spec.exactKeys.isNotEmpty() || spec.keyPrefixes.isNotEmpty(),
                )
            }
        }
    }

    // ── 导出 ────────────────────────────────────────────────

    @Test
    fun `every prefs scope exports a payload when seeded`() {
        prefsScopes().forEach { scope ->
            val env = FakeBackupEnvironment()
            seed(env, scope)
            val payload = scope.export(env, null)
            assertNotNull("scope '${scope.id}' 播种后导出返回了 null", payload)
            assertTrue(
                "scope '${scope.id}' 播种后 count 应当 > 0，实际 ${payload!!.count}",
                payload.count > 0,
            )
        }
    }

    @Test
    fun `an empty dataset yields a count zero payload rather than null`() {
        // ⚠️⚠️ 静默失效点 12：空数据集返回 null 会让用户以为「备了」，
        //     而实际上备份文件里根本没有这一项。
        prefsScopes().forEach { scope ->
            val payload = scope.export(FakeBackupEnvironment(), null)
            assertNotNull(
                "❌ scope '${scope.id}' 在空数据集上返回了 null —— " +
                    "那是「本次不适用」的语义，会让用户以为备了其实没备",
                payload,
            )
            assertEquals("scope '${scope.id}' 空数据集的 count 应当是 0", 0, payload!!.count)
            assertTrue(
                "scope '${scope.id}' 空数据集的 data 应当是空数组",
                payload.data.isJsonArray && payload.data.asJsonArray.size() == 0,
            )
        }
    }

    @Test
    fun `export returns null when the environment has no prefs seam`() {
        prefsScopes().forEach { scope ->
            assertNull(
                "scope '${scope.id}' 在环境不支持 prefs 时应当返回 null（本次不适用）",
                scope.export(NoPrefsEnvironment(), null),
            )
        }
    }

    /** 一个**不支持** prefs 的环境。 */
    private class NoPrefsEnvironment :
        BackupEnvironment by FakeBackupEnvironment() {
        override val prefs: BackupPrefs? = null
    }

    // ── 导入：往返 ──────────────────────────────────────────

    @Test
    fun `every prefs scope round trips its own data`() {
        prefsScopes().forEach { scope ->
            val exporting = FakeBackupEnvironment()
            seed(exporting, scope)
            val payload = scope.export(exporting, null)!!

            // ⚠️ 接收方必须是**全新空环境** —— 否则证明不了值是从 payload 来的。
            val importing = FakeBackupEnvironment()
            val result = scope.import(importing, payload, ImportMode.MERGE)

            assertEquals(
                "scope '${scope.id}' 导入状态应当是 IMPORTED",
                ImportStatus.IMPORTED, result.status,
            )
            // ⚠️ `payload.count` 是**有数据的 prefs 文件数**，`result.imported` 是
            //    **导入的键数** ⇒ 后者必然 ≥ 前者（一个文件里通常有多个键）。
            assertTrue(
                "scope '${scope.id}' 导入的键数（${result.imported}）" +
                    "应当不少于备份里的 prefs 文件数（${payload.count}）",
                result.imported >= payload.count,
            )
            assertTrue("scope '${scope.id}' 应当导入了至少一个键", result.imported > 0)

            val source = (exporting.prefs as FakeBackupEnvironment.InMemoryPrefs).data
            val dest = (importing.prefs as FakeBackupEnvironment.InMemoryPrefs).data
            source.forEach { (prefsName, values) ->
                values.forEach { (key, expected) ->
                    if (expected == null) return@forEach
                    assertEquals(
                        "scope '${scope.id}' 的 $prefsName/$key 经往返应当逐值还原",
                        expected, dest[prefsName]?.get(key),
                    )
                }
            }
        }
    }

    @Test
    fun `a null payload is skipped and does not touch the environment`() {
        prefsScopes().forEach { scope ->
            val env = FakeBackupEnvironment()
            val result = scope.import(env, null, ImportMode.MERGE)
            assertEquals(
                "scope '${scope.id}'：payload 为 null ⇒ SKIPPED_NOT_SELECTED",
                ImportStatus.SKIPPED_NOT_SELECTED, result.status,
            )
            assertTrue(
                "scope '${scope.id}'：payload 为 null 时不得写任何东西",
                (env.prefs as FakeBackupEnvironment.InMemoryPrefs).data.isEmpty(),
            )
        }
    }

    @Test
    fun `import fails cleanly when data is not a JSON array`() {
        prefsScopes().forEach { scope ->
            val bad = ScopePayload(1, com.google.gson.JsonObject())
            val result = scope.import(FakeBackupEnvironment(), bad, ImportMode.MERGE)
            assertEquals(
                "scope '${scope.id}'：data 不是数组 ⇒ FAILED（而不是静默成功）",
                ImportStatus.FAILED, result.status,
            )
        }
    }

    // ── 导入：REPLACE 的删键边界（风险 R3 的靶子） ──────────

    /**
     * 挑一个「本 scope 之外的 prefs 文件」里的键 —— 用来验证 REPLACE **不会**
     * 跨文件删：
     *  - 优先挑 secrets 管的**同 prefs 文件**的凭证键（最危险的误删形态）；
     *  - 没有时退回 `vflow_api_tokens`（另一个 prefs 文件，测的是「不跨文件删」）。
     */
    private fun foreignKeyFor(spec: BackupPrefsSpec): Pair<String, String> =
        SecretsScope.SECRET_SLOTS.firstOrNull { it.first == spec.prefsName }
            ?: (SecretsScope.EXCLUDED_PREFS to "some_token")

    @Test
    fun `REPLACE only removes keys inside this scope whitelist`() {
        prefsScopes().forEach { scope ->
            val spec = scope.specs.first()
            // 本机：一个白名单内的键（备份里有）、一个白名单内的键（备份里没有）、
            //       一个白名单**外**的键、一个**别的 scope 管的**键。
            val (foreignPrefs, foreignKey) = foreignKeyFor(spec)

            // ⚠️ 「白名单内、备份里没有」的那个键**必须取自本 scope 的白名单** ——
            //    随手编一个字面量（例如 `inside-but-not-in-backup`）其实落在白名单**外**，
            //    于是它与下一条断言测的是同一件事，而「REPLACE 真的会删」这一条
            //    就永远没被验证过（本用例第一版就是这么写错的，被断言自己抓出来）。
            //
            // ⚠️ **单键 scope（tiles）测不了这一格**：它只有一个白名单键，
            //    而「备份里没有这个键」⇒ 该 prefs 文件根本不进 payload 数组
            //    ⇒ REPLACE 不会遍历到它。这是设计的既有语义（文件不在备份里
            //    ＝本次不是 scope 成员），故此处**如实跳过**而不是硬凑一个键。
            val prefixKey = spec.keyPrefixes.firstOrNull()?.let { "${it}extra.pkg" }
            val secondWhitelisted = spec.exactKeys.elementAtOrNull(1)
            val inWhitelistNotInBackup = prefixKey ?: secondWhitelisted
            val canTestWhitelistedDeletion = inWhitelistNotInBackup != null

            val localValues = linkedMapOf<String, Any?>(
                spec.exactKeys.first() to "from-backup",
                "outside-the-whitelist" to "must-survive",
            )
            if (inWhitelistNotInBackup != null) localValues[inWhitelistNotInBackup] = "local-extra"
            // 与 secrets **共用同一个 prefs 文件**时，把凭证键也放进本文件
            // —— 那是 REPLACE 误删后果最严重的一种形态。
            if (foreignPrefs == spec.prefsName) localValues[foreignKey] = "MUST-NOT-BE-DELETED"

            val seed = mutableMapOf<String, Map<String, Any?>>(spec.prefsName to localValues)
            if (foreignPrefs != spec.prefsName) {
                seed[foreignPrefs] = linkedMapOf(foreignKey to "MUST-NOT-BE-DELETED")
            }
            val env = envWith(seed)

            // 用真实导出的形状构造 payload，避免手工拼错形状导致用例假绿。
            val exporting = FakeBackupEnvironment()
            val store = exporting.prefs as FakeBackupEnvironment.InMemoryPrefs
            store.data[spec.prefsName] = linkedMapOf(spec.exactKeys.first() to "from-backup")
            if (foreignPrefs != spec.prefsName) {
                store.data[foreignPrefs] = linkedMapOf(foreignKey to "MUST-NOT-BE-DELETED")
            }
            val realPayload = scope.export(exporting, null)!!

            scope.import(env, realPayload, ImportMode.REPLACE)

            val data = (env.prefs as FakeBackupEnvironment.InMemoryPrefs).data
            val after = data[spec.prefsName]!!
            assertEquals(
                "scope '${scope.id}'：白名单内、备份里有 ⇒ 被覆盖",
                "from-backup", after[spec.exactKeys.first()],
            )
            if (canTestWhitelistedDeletion) {
                assertNull(
                    "❌ scope '${scope.id}'：白名单内、备份里没有 ⇒ 应当被删" +
                        "（否则 REPLACE 与 MERGE 无差别；被测键 '$inWhitelistNotInBackup'）",
                    after[inWhitelistNotInBackup],
                )
            } else {
                // 单键 scope：该 prefs 文件不在备份里 ⇒ REPLACE 不该动它。
                assertEquals(
                    "❌ scope '${scope.id}'：备份里没有这个 prefs 文件时不该动本地值",
                    "from-backup", after[spec.exactKeys.first()],
                )
            }
            assertEquals(
                "❌❌ scope '${scope.id}'：白名单外的键被删了 —— 这是误删用户设置（风险 R3）",
                "must-survive", after["outside-the-whitelist"],
            )
            assertEquals(
                "❌❌ scope '${scope.id}'：别的 scope 管的键被删了 —— 灾难性误删",
                "MUST-NOT-BE-DELETED", data[foreignPrefs]?.get(foreignKey),
            )
        }
    }

    @Test
    fun `MERGE keeps local keys that the backup does not contain`() {
        prefsScopes().forEach { scope ->
            val spec = scope.specs.first()
            val env = envWith(
                mapOf(
                    spec.prefsName to mapOf(
                        spec.exactKeys.first() to "local-old",
                        "local-only" to "keep-me",
                    )
                )
            )
            val exporting = FakeBackupEnvironment()
            val store = exporting.prefs as FakeBackupEnvironment.InMemoryPrefs
            store.data[spec.prefsName] = linkedMapOf(spec.exactKeys.first() to "from-backup")
            val payload = scope.export(exporting, null)!!

            scope.import(env, payload, ImportMode.MERGE)

            val after = (env.prefs as FakeBackupEnvironment.InMemoryPrefs).data[spec.prefsName]!!
            assertEquals(
                "MERGE 应当覆盖同名键", "from-backup", after[spec.exactKeys.first()],
            )
            assertEquals(
                "MERGE 必须保留本地独有的键", "keep-me", after["local-only"],
            )
        }
    }

    // ── 导入：外部输入不得越界写 ────────────────────────────

    @Test
    fun `import never writes keys outside the whitelist`() {
        // 备份是**外部输入**（可以是别处拷来的文件、也可以被手工编辑过）。
        // 不过滤的话，它能往任意 prefs 的任意键里写值 —— 一条从
        // 「恢复备份」到「任意配置覆盖」的提权路径。
        prefsScopes().forEach { scope ->
            val spec = scope.specs.first()
            val env = FakeBackupEnvironment()
            val items = com.google.gson.JsonObject().apply {
                add("evil-key-not-in-whitelist", PrefsValueCodec.encode("evil")!!)
                add(spec.exactKeys.first(), PrefsValueCodec.encode("ok")!!)
            }
            val payload = ScopePayload(
                1,
                JsonArray().apply {
                    add(
                        com.google.gson.JsonObject().apply {
                            addProperty(AbstractPrefsScope.KEY_PREFS, spec.prefsName)
                            add(AbstractPrefsScope.KEY_ITEMS, items)
                        }
                    )
                }
            )

            val result = scope.import(env, payload, ImportMode.MERGE)

            assertEquals(ImportStatus.IMPORTED, result.status)
            assertEquals("只应写入 1 个白名单内的键", 1, result.imported)
            assertTrue("应当记 1 个 skip", result.skipped >= 1)
            val after = (env.prefs as FakeBackupEnvironment.InMemoryPrefs).data[spec.prefsName]!!
            assertNull(
                "❌❌ scope '${scope.id}'：白名单外的键被写入了 —— 任意配置覆盖",
                after["evil-key-not-in-whitelist"],
            )
        }
    }

    @Test
    fun `import skips unknown prefs names instead of creating them`() {
        prefsScopes().forEach { scope ->
            val env = FakeBackupEnvironment()
            val payload = ScopePayload(
                1,
                JsonArray().apply {
                    add(
                        com.google.gson.JsonObject().apply {
                            addProperty(AbstractPrefsScope.KEY_PREFS, "some_unknown_prefs")
                            add(
                                AbstractPrefsScope.KEY_ITEMS,
                                com.google.gson.JsonObject().apply {
                                    addProperty("whatever", "x")
                                }
                            )
                        }
                    )
                }
            )
            val result = scope.import(env, payload, ImportMode.MERGE)
            assertEquals(ImportStatus.IMPORTED, result.status)
            assertEquals(0, result.imported)
            assertTrue(
                "❌ scope '${scope.id}'：不认识的 prefs 名不得被创建",
                (env.prefs as FakeBackupEnvironment.InMemoryPrefs).data.isEmpty(),
            )
        }
    }

    // ── 拓扑顺序 ────────────────────────────────────────────

    @Test
    fun `import order puts modules and workflow deps before their consumers`() {
        val order = BackupScopeRegistry.importOrder().map { it.id }
        assertEquals(
            listOf(
                "folders", "global_variables", "modules", "workflows",
                "tiles", "settings", "chat", "secrets",
            ),
            order,
        )
        assertTrue(
            "tiles 依赖 workflows ⇒ 必须排在它之后",
            order.indexOf("tiles") > order.indexOf("workflows"),
        )
    }
}
