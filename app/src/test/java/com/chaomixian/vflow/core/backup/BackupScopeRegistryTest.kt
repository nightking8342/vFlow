// 文件: test/java/com/chaomixian/vflow/core/backup/BackupScopeRegistryTest.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupScopeRegistryTest {

    @Before
    fun setUp() {
        BackupScopeRegistry.clearForTest()
        BackupScopeRegistry.warningSink = {}
    }

    @After
    fun tearDown() {
        BackupScopeRegistry.clearForTest()
        BackupScopeRegistry.warningSink = {}
    }

    @Test
    fun `initialize registers the built-in scopes`() {
        val ids = BackupScopeRegistry.all().map { it.id }.toSet()
        assertEquals(
            setOf(
                "folders", "global_variables", "workflows", "secrets",
                // T6 追加的四个 prefs 类范围
                "settings", "chat", "modules", "tiles",
            ),
            ids
        )
    }

    @Test
    fun `initialize is idempotent`() {
        BackupScopeRegistry.initialize()
        val first = BackupScopeRegistry.all().map { it.id }
        BackupScopeRegistry.initialize()
        BackupScopeRegistry.initialize()
        assertEquals(first, BackupScopeRegistry.all().map { it.id })
        assertEquals(8, BackupScopeRegistry.all().size)
    }

    @Test
    fun `all triggers lazy initialization without explicit initialize call`() {
        // 懒初始化的意义：T5 不必记得调 initialize()，消掉「写了但没有调用点」的空档。
        // 这里刻意**不**调 initialize()。
        assertEquals(8, BackupScopeRegistry.all().size)
    }

    @Test
    fun `get returns registered scope and null for unknown`() {
        assertNotNull(BackupScopeRegistry.get("workflows"))
        assertNull(BackupScopeRegistry.get("no_such_scope"))
    }

    @Test
    fun `importOrder puts folders before workflows because of dependsOn`() {
        val order = BackupScopeRegistry.importOrder().map { it.id }
        assertTrue(
            "folders 必须在 workflows 之前（workflows.dependsOn = [folders]），实际：$order",
            order.indexOf("folders") < order.indexOf("workflows")
        )
    }

    @Test
    fun `importOrder respects importOrder value for independent scopes`() {
        val order = BackupScopeRegistry.importOrder().map { it.id }
        // folders(0) → global_variables(5) → modules(8) → workflows(10)
        //   → tiles(20, 依赖 workflows 已就位) → settings(40) → chat(50) → secrets(100)
        // ⚠️ 与设计正文 §1.5 的一处偏差（以代码为准）：secrets 的实际 importOrder
        //    是 100（不是 30），理由见 SecretsScope 的 KDoc ⇒ 它排最后。
        assertEquals(
            listOf(
                "folders", "global_variables", "modules", "workflows",
                "tiles", "settings", "chat", "secrets",
            ),
            order
        )
    }

    @Test
    fun `importOrder handles dependsOn on unregistered scope without stalling`() {
        BackupScopeRegistry.clearForTest()
        // ⚠️ clearForTest 会复位「已初始化」标记 ⇒ 下一次 all() 会**重新注册内置四个**。
        //    所以要先触发懒初始化，再注册测试用的 scope（顺序反了它会被内置覆盖/共存）。
        BackupScopeRegistry.all()
        // 依赖一个**不存在**的 id ⇒ 应视为已就绪，不得让拓扑排序卡住或漏掉它。
        BackupScopeRegistry.register(StubScope("orphan", 1, listOf("no_such_scope")))
        val order = BackupScopeRegistry.importOrder().map { it.id }
        assertTrue("orphan 必须出现且不被阻塞：$order", order.contains("orphan"))
    }

    @Test
    fun `importOrder does not crash on dependency cycle`() {
        BackupScopeRegistry.clearForTest()
        // 先启动懒初始化拿到四个内置 scope，再清掉它们，只留下成环的两个。
        BackupScopeRegistry.all()
        val warnings = mutableListOf<String>()
        BackupScopeRegistry.warningSink = { warnings += it }
        // ⚠️ 内置 scope 也在注册表里，但它们不参与这个环；为断言精确，用只含环的方式：
        //    直接构造一个环，且环内两个 id 排序在内置之后不影响「不崩」这一判据。
        BackupScopeRegistry.register(StubScope("cycle_a", 1, listOf("cycle_b")))
        BackupScopeRegistry.register(StubScope("cycle_b", 2, listOf("cycle_a")))

        val order = BackupScopeRegistry.importOrder().map { it.id }
        // 成环不崩、不丢项
        assertTrue("成环时两个成员都必须在结果里：$order", order.containsAll(listOf("cycle_a", "cycle_b")))
        assertTrue("成环必须记 W，否则无从排查", warnings.any { it.contains("成环") })
    }

    @Test
    fun `register with same id overwrites and warns`() {
        val warnings = mutableListOf<String>()
        BackupScopeRegistry.warningSink = { warnings += it }
        BackupScopeRegistry.all() // 先完成懒初始化（此时 workflows 已是内置实例）
        BackupScopeRegistry.register(StubScope("workflows", 99, emptyList()))

        assertEquals(99, BackupScopeRegistry.get("workflows")?.importOrder)
        assertTrue("同 id 覆盖必须记 W", warnings.any { it.contains("workflows") })
    }

    @Test
    fun `exportAll only calls selected scopes`() {
        val env = FakeBackupEnvironment()
        val result = BackupScopeRegistry.exportAll(env, setOf("folders"), null)
        assertEquals(setOf("folders"), result.keys)
    }

    @Test
    fun `exportAll returns empty payload not null for empty dataset`() {
        val env = FakeBackupEnvironment()
        val result = BackupScopeRegistry.exportAll(
            env, setOf("folders", "workflows", "global_variables"), null
        )
        assertEquals(3, result.size)
        result.forEach { (scopeId, payload) ->
            assertEquals("$scopeId 空数据集必须是 count=0 的 payload", 0, payload.count)
            assertTrue("$scopeId 的 data 必须是 JSON 数组而非 null", payload.data.isJsonArray)
        }
    }

    @Test
    fun `exportAll isolates a throwing scope from the others`() {
        BackupScopeRegistry.clearForTest()
        BackupScopeRegistry.register(StubScope("boom", 0, emptyList(), throwOnExport = true))
        BackupScopeRegistry.register(StubScope("ok", 1, emptyList()))

        val env = FakeBackupEnvironment()
        val result = BackupScopeRegistry.exportAll(env, setOf("boom", "ok"), null)

        assertTrue("抛异常的 scope 不得让整次导出失败", result.containsKey("ok"))
        assertTrue(result.values.any { it.count == 0 })
    }

    @Test
    fun `importAll marks missing scope as SKIPPED_MISSING`() {
        val env = FakeBackupEnvironment()
        val results = BackupScopeRegistry.importAll(env, mapOf("folders" to emptyPayload()), ImportMode.MERGE)

        val byId = results.associateBy { it.scopeId }
        assertEquals(ImportStatus.IMPORTED, byId.getValue("folders").status)
        assertEquals(ImportStatus.SKIPPED_MISSING, byId.getValue("workflows").status)
        assertEquals(ImportStatus.SKIPPED_MISSING, byId.getValue("global_variables").status)
    }

    @Test
    fun `importAll marks unregistered scope as SKIPPED_UNKNOWN without crashing`() {
        val env = FakeBackupEnvironment()
        val payloads = mapOf(
            "workflows" to emptyPayload(),
            "brand_new_scope" to emptyPayload()
        )
        val results = BackupScopeRegistry.importAll(env, payloads, ImportMode.REPLACE)

        val unknown = results.single { it.scopeId == "brand_new_scope" }
        assertEquals(ImportStatus.SKIPPED_UNKNOWN, unknown.status)
        // 且不得影响已注册 scope 的导入
        assertEquals(ImportStatus.IMPORTED, results.single { it.scopeId == "workflows" }.status)
    }

    @Test
    fun `importAll isolates a throwing scope and keeps going`() {
        BackupScopeRegistry.clearForTest()
        BackupScopeRegistry.register(StubScope("boom", 0, emptyList(), throwOnImport = true))
        BackupScopeRegistry.register(StubScope("after", 10, emptyList()))

        val env = FakeBackupEnvironment()
        val results = BackupScopeRegistry.importAll(
            env,
            mapOf("boom" to emptyPayload(), "after" to emptyPayload()),
            ImportMode.MERGE
        )

        val byId = results.associateBy { it.scopeId }
        assertEquals(ImportStatus.FAILED, byId.getValue("boom").status)
        assertEquals(
            "抛异常后必须继续跑后面的 scope",
            ImportStatus.IMPORTED, byId.getValue("after").status
        )
    }

    @Test
    fun `importAll orders folders before workflows`() {
        val env = FakeBackupEnvironment()
        val results = BackupScopeRegistry.importAll(
            env,
            mapOf("workflows" to emptyPayload(), "folders" to emptyPayload()),
            ImportMode.REPLACE
        )
        // 结果按 importOrder 产出：folders 必须早于 global_variables 与 workflows
        val ids = results.map { it.scopeId }
        assertTrue("folders 必须最先导入：$ids", ids.indexOf("folders") < ids.indexOf("global_variables"))
        assertTrue("folders 必须早于 workflows：$ids", ids.indexOf("folders") < ids.indexOf("workflows"))
    }

    @Test
    fun `payload type enforces non-null data`() {
        // 类型层面的约束：data 是 JsonElement（非空），empty() 给的是 JsonArray
        val payload = ScopePayload.empty()
        assertEquals(0, payload.count)
        assertTrue(payload.data is JsonArray)

        val of = ScopePayload.of(listOf(JsonPrimitive("x")))
        assertEquals(1, of.count)
        assertEquals(1, of.data.asJsonArray.size())
    }

    // ── 测试替身 ──

    private fun emptyPayload(): ScopePayload = ScopePayload.empty()

    private class StubScope(
        override val id: String,
        override val importOrder: Int,
        override val dependsOn: List<String>,
        private val throwOnExport: Boolean = false,
        private val throwOnImport: Boolean = false
    ) : BackupScope {
        override val group = ScopeGroup.USER_CONTENT
        override val sensitive = false
        override val defaultIncluded = true

        override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload {
            if (throwOnExport) throw IllegalStateException("stub export boom")
            return ScopePayload.empty()
        }

        override fun import(
            env: BackupEnvironment,
            payload: ScopePayload?,
            mode: ImportMode
        ): ScopeImportResult {
            if (throwOnImport) throw IllegalStateException("stub import boom")
            return ScopeImportResult(id, ImportStatus.IMPORTED)
        }
    }
}
