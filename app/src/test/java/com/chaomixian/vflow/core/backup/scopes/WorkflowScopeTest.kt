// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/WorkflowScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionReturn
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowScopeTest {

    private val scope = WorkflowScope()

    @Test
    fun `emptyDatasetIsNotEmptyPayload`() {
        val payload = scope.export(FakeBackupEnvironment(), null)
        assertNotNull("空数据集必须返回 payload，不能是 null", payload)
        assertEquals(0, payload!!.count)
        assertTrue(payload.data.isJsonArray)
    }

    @Test
    fun `roundTripPreservesEveryWorkflowField`() {
        // 这条曾是「锁住单文件导出漏的那 4 个字段」，现在那条路已补齐
        // （见 WorkflowExportFieldCoverageTest），本用例改成**全字段**往返：
        // 备份走整对象 Gson，任何字段都不该在往返中丢失或变形。
        val original = Workflow(
            id = "w1",
            name = "全字段",
            triggers = emptyList(),
            steps = listOf(
                ActionStep(moduleId = "vflow.logic.wait", parameters = mapOf("ms" to 100), id = "s1")
            ),
            isEnabled = false,
            isFavorite = true,
            wasEnabledBeforePermissionsLost = true,
            folderId = "f1",
            order = 7,
            shortcutName = "快捷名",
            shortcutIconRes = "ic_x",
            cardIconRes = "rounded_x_24",
            cardThemeColor = "#112233",
            modifiedAt = 1700000000000L,
            version = "2.0.0",
            vFlowLevel = 3,
            description = "描述",
            author = "作者",
            homepage = "https://example.com",
            tags = listOf("a", "b"),
            maxExecutionTime = 42,
            reentryBehavior = WorkflowReentryBehavior.ALLOW_PARALLEL,
            silentExecution = true,
            logLevel = com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel.ERROR,
            functionSignature = FunctionSignature(
                params = listOf(FunctionParam(name = "p1", type = "vflow.type.string", isRequired = true)),
                returnDef = FunctionReturn(keys = listOf(ReturnKey("k", "vflow.type.number")))
            )
        )
        val source = FakeBackupEnvironment(workflows = listOf(original))
        val payload = scope.export(source, null)

        val target = FakeBackupEnvironment()
        scope.import(target, payload, ImportMode.REPLACE)

        val restored = target.workflowStore.single()
        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.isEnabled, restored.isEnabled)
        assertEquals(original.isFavorite, restored.isFavorite)
        assertEquals(original.wasEnabledBeforePermissionsLost, restored.wasEnabledBeforePermissionsLost)
        assertEquals(original.folderId, restored.folderId)
        assertEquals(original.order, restored.order)
        assertEquals(original.shortcutName, restored.shortcutName)
        assertEquals(original.shortcutIconRes, restored.shortcutIconRes)
        assertEquals(original.cardIconRes, restored.cardIconRes)
        assertEquals(original.cardThemeColor, restored.cardThemeColor)
        assertEquals(original.modifiedAt, restored.modifiedAt)
        assertEquals(original.version, restored.version)
        assertEquals(original.vFlowLevel, restored.vFlowLevel)
        assertEquals(original.description, restored.description)
        assertEquals(original.author, restored.author)
        assertEquals(original.homepage, restored.homepage)
        assertEquals(original.tags, restored.tags)
        assertEquals(original.maxExecutionTime, restored.maxExecutionTime)
        assertEquals(original.reentryBehavior, restored.reentryBehavior)
        assertEquals(original.silentExecution, restored.silentExecution)
        assertEquals(original.logLevel, restored.logLevel)
        assertNotNull("functionSignature 必须往返 —— 丢了会把函数工作流退化成普通流", restored.functionSignature)
        assertEquals("p1", restored.functionSignature!!.params.single().name)
        assertEquals("k", restored.functionSignature!!.returnDef!!.keys.single().name)
        assertEquals("s1", restored.steps.single().id)
        assertEquals(original.steps.single().moduleId, restored.steps.single().moduleId)
    }

    @Test
    fun `a function workflow stays a function workflow after round trip`() {
        val fn = Workflow(
            id = "fn", name = "函数流",
            functionSignature = FunctionSignature(
                params = listOf(FunctionParam(name = "a", type = "vflow.type.string"))
            )
        )
        assertTrue(fn.isFunction)

        val source = FakeBackupEnvironment(workflows = listOf(fn))
        val target = FakeBackupEnvironment()
        scope.import(target, scope.export(source, null), ImportMode.REPLACE)

        assertTrue(
            "往返后 isFunction 必须仍为 true（新格式完整序列化，与 legacy 路径不同）",
            target.workflowStore.single().isFunction
        )
    }

    @Test
    fun `replace_removesWorkflowsMissingFromBackup`() {
        // ⚠️ 反证 A 的目标断言：把 REPLACE 分支改成调 env.mergeWorkflows 后这条必须变红。
        val target = FakeBackupEnvironment(
            workflows = listOf(
                Workflow(id = "local-only", name = "本地独有"),
                Workflow(id = "w1", name = "旧版")
            )
        )
        val source = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "来自备份"))
        )
        val payload = scope.export(source, null)

        scope.import(target, payload, ImportMode.REPLACE)

        assertEquals(
            "REPLACE 必须真删掉不在备份里的工作流：${target.workflowStore.map { it.id }}",
            listOf("w1"), target.workflowStore.map { it.id }
        )
        assertEquals("来自备份", target.workflowStore.single().name)
        assertEquals(1, target.replaceWorkflowCalls)
        assertEquals(0, target.mergeWorkflowCalls)
    }

    @Test
    fun `merge_keepsLocalOnlyWorkflows`() {
        val target = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "local-only", name = "本地独有"))
        )
        val source = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "来自备份"))
        )

        scope.import(target, scope.export(source, null), ImportMode.MERGE)

        assertEquals(setOf("local-only", "w1"), target.workflowStore.map { it.id }.toSet())
        assertEquals(0, target.replaceWorkflowCalls)
        assertEquals(1, target.mergeWorkflowCalls)
    }

    @Test
    fun `merge_keepsLocalFolderId - documents the known cost of MERGE`() {
        // 上游 saveAllWorkflows 的既有语义：既有条目的 folderId 反向覆盖新值。
        // 后果：合并式导入恢复不了文件夹归属。这是刻意保留的既有行为。
        val target = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "旧", folderId = "local-folder"))
        )
        val source = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "备份", folderId = "backup-folder"))
        )

        scope.import(target, scope.export(source, null), ImportMode.MERGE)

        assertEquals("本地 folderId 优先（既有语义）", "local-folder", target.workflowStore.single().folderId)
    }

    @Test
    fun `replace_restoresFolderIdFromBackup`() {
        // 与上一条对照：REPLACE 能恢复归属，MERGE 不能。
        val target = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "旧", folderId = "local-folder"))
        )
        val source = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "备份", folderId = "backup-folder"))
        )

        scope.import(target, scope.export(source, null), ImportMode.REPLACE)

        assertEquals("REPLACE 必须恢复备份里的归属", "backup-folder", target.workflowStore.single().folderId)
    }

    @Test
    fun `importReloadsTriggersForBothModes`() {
        // 静默失效点 5：不调 reloadTriggers ⇒ 工作流进来了但触发器不调度。
        val source = FakeBackupEnvironment(workflows = listOf(Workflow(id = "w1", name = "A")))

        val replaceTarget = FakeBackupEnvironment()
        scope.import(replaceTarget, scope.export(source, null), ImportMode.REPLACE)
        assertEquals(1, replaceTarget.reloadCount)

        val mergeTarget = FakeBackupEnvironment()
        scope.import(mergeTarget, scope.export(source, null), ImportMode.MERGE)
        assertEquals(1, mergeTarget.reloadCount)
    }

    @Test
    fun `replaceWithEmptyBackupReloadsTriggers`() {
        // REPLACE 空集 = 删光所有工作流，那正是最需要重载触发器的时刻。
        val target = FakeBackupEnvironment(
            workflows = listOf(Workflow(id = "w1", name = "将被删"))
        )
        scope.import(target, com.chaomixian.vflow.core.backup.ScopePayload.empty(), ImportMode.REPLACE)

        assertTrue("空集 REPLACE 后本地工作流必须消失", target.workflowStore.isEmpty())
        assertEquals("删光了更必须重载触发器", 1, target.reloadCount)
    }

    @Test
    fun `nullPayloadSkipsWithoutTouchingEnvironment`() {
        val target = FakeBackupEnvironment(workflows = listOf(Workflow(id = "untouched", name = "别动我")))
        val result = scope.import(target, null, ImportMode.REPLACE)

        assertEquals(ImportStatus.SKIPPED_NOT_SELECTED, result.status)
        assertEquals(listOf("untouched"), target.workflowStore.map { it.id })
        assertEquals(0, target.replaceWorkflowCalls)
        assertEquals(0, target.reloadCount)
    }

    @Test
    fun `recordWithoutIdIsSkipped`() {
        val env = FakeBackupEnvironment()
        val payload = com.chaomixian.vflow.core.backup.ScopePayload.of(
            listOf(
                com.google.gson.JsonParser.parseString("""{"name":"无 id"}"""),
                com.google.gson.JsonParser.parseString("""{"id":"ok","name":"有 id"}""")
            )
        )

        val result = scope.import(env, payload, ImportMode.REPLACE)

        assertEquals(listOf("ok"), env.workflowStore.map { it.id })
        assertEquals(1, result.skipped)
    }

    @Test
    fun `an older backup missing keys is restored with defaults instead of crashing`() {
        // ⚠️⚠️ 这条锁的是一个**会崩**的缺陷，不是「值不对」。
        //
        //    `env.json.fromJson` 走 Gson 的反射构造（`Unsafe.allocateInstance`），
        //    它**绕过 Kotlin 构造函数** ⇒ `Workflow.kt` 里那些 `= 默认值`
        //    完全不执行：引用类型字段缺键 ⇒ **`null`**（`logLevel` / `cardIconRes` …），
        //    原生类型 ⇒ `false` / `0`（`isEnabled` 期望的是 `true`）。
        //
        //    ⚠️ `null` 落在非空字段上**不会当场抛**，它潜伏到第一次 `copy()` ——
        //    而 REPLACE 恰好走 `replaceAllWorkflows` → `normalizeWorkflow` → `copy`
        //    ⇒ 不加归一化时，**恢复一份旧版本写的备份会直接崩**，
        //    栈指向 `Workflow.copy`，看不出是备份缺键。
        //
        //    这里模拟「logLevel 之前那一版」写出的备份：Gson 整对象序列化，
        //    但没有 `logLevel` 键。
        val oldRecord = """
            {"id":"w1","name":"旧备份里的工作流","triggers":[],"steps":[],
             "isEnabled":true,"isFavorite":false,"wasEnabledBeforePermissionsLost":false,
             "folderId":null,"order":0,"shortcutName":null,"shortcutIconRes":null,
             "cardIconRes":"rounded_home_24","cardThemeColor":"#112233","modifiedAt":1,
             "version":"1.0.0","vFlowLevel":1,"description":"","author":"","homepage":"",
             "tags":[],"maxExecutionTime":null,"reentryBehavior":"block_new",
             "silentExecution":false}
        """.trimIndent()

        val payload = com.chaomixian.vflow.core.backup.ScopePayload.of(
            listOf(com.google.gson.JsonParser.parseString(oldRecord))
        )
        val env = FakeBackupEnvironment()

        val result = scope.import(env, payload, ImportMode.REPLACE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        val restored = env.workflowStore.single()
        assertEquals("w1", restored.id)
        assertEquals(
            "缺 logLevel 必须回落 VERBOSE（= 改动前行为），而不是 null",
            com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel.VERBOSE,
            restored.logLevel
        )
        assertEquals(
            "缺的引用类型字段必须全部补齐 —— 留 null 会在下一次 copy() 上炸",
            "rounded_home_24", restored.cardIconRes
        )
        assertEquals(emptyList<String>(), restored.tags)
    }

    @Test
    fun `a sparse backup record is completed rather than left half-null`() {
        // 极端情形：只剩 id 与 name（手改过的 / 未来某版本裁过字段的备份）。
        val payload = com.chaomixian.vflow.core.backup.ScopePayload.of(
            listOf(com.google.gson.JsonParser.parseString("""{"id":"w1","name":"残缺"}"""))
        )
        val env = FakeBackupEnvironment()

        scope.import(env, payload, ImportMode.REPLACE)

        val restored = env.workflowStore.single()
        // ⚠️ 缺 isEnabled 时 Gson 留 `false`，而另外两条读路径的回落都是 `true`
        //    ⇒ 不补这一条，一份残缺备份会把用户的工作流**静默全部禁用**。
        assertEquals("缺 isEnabled 必须回落 true", true, restored.isEnabled)
        assertEquals(1, restored.vFlowLevel)
        assertEquals(com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel.VERBOSE, restored.logLevel)
        assertEquals(
            com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior.BLOCK_NEW,
            restored.reentryBehavior
        )
    }

    @Test
    fun `scopeMetadataIsStable`() {
        assertEquals("workflows", scope.id)
        assertEquals(10, scope.importOrder)
        assertEquals(listOf("folders"), scope.dependsOn)
        assertEquals(false, scope.sensitive)
    }
}
