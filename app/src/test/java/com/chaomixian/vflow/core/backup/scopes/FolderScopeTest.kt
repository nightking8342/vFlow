// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/FolderScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FolderScopeTest {

    private val scope = FolderScope()

    @Test
    fun `emptyDatasetIsNotEmptyPayload`() {
        val env = FakeBackupEnvironment()
        val payload = scope.export(env, null)

        assertNotNull("空数据集必须返回 payload，不能是 null（否则用户以为备了其实没备）", payload)
        assertEquals(0, payload!!.count)
        assertTrue(payload.data.isJsonArray)
        assertEquals(0, payload.data.asJsonArray.size())
    }

    @Test
    fun `roundTripKeepsFolders`() {
        val folders = listOf(
            WorkflowFolder(id = "f1", name = "甲"),
            WorkflowFolder(id = "f2", name = "乙")
        )
        val source = FakeBackupEnvironment(folders = folders)
        val payload = scope.export(source, null)

        val target = FakeBackupEnvironment()
        val result = scope.import(target, payload, ImportMode.REPLACE)

        assertEquals(ImportStatus.IMPORTED, result.status)
        assertEquals(2, result.imported)
        assertEquals(setOf("f1", "f2"), target.folderStore.map { it.id }.toSet())
        assertEquals(setOf("甲", "乙"), target.folderStore.map { it.name }.toSet())
    }

    @Test
    fun `replace_keepsFolderIds`() {
        // ⚠️ 反证 B 的目标断言：把 FolderScope 改成重生成 id 后这条必须变红。
        // 恢复语义下，工作流的 folderId 指向这些 id；重生成 = 全部悬空。
        val source = FakeBackupEnvironment(
            folders = listOf(WorkflowFolder(id = "keep-me", name = "原 id"))
        )
        val payload = scope.export(source, null)

        val target = FakeBackupEnvironment()
        scope.import(target, payload, ImportMode.REPLACE)

        assertEquals(
            "恢复必须保 id —— 重生成会让所有 workflow.folderId 悬空",
            "keep-me", target.folderStore.single().id
        )
    }

    @Test
    fun `replace_removesFoldersMissingFromBackup`() {
        val target = FakeBackupEnvironment(
            folders = listOf(
                WorkflowFolder(id = "local-only", name = "本地独有"),
                WorkflowFolder(id = "f1", name = "将被覆盖")
            )
        )
        val source = FakeBackupEnvironment(
            folders = listOf(WorkflowFolder(id = "f1", name = "来自备份"))
        )
        val payload = scope.export(source, null)

        scope.import(target, payload, ImportMode.REPLACE)

        assertEquals(listOf("f1"), target.folderStore.map { it.id })
        assertEquals(1, target.replaceFolderCalls)
        assertEquals(0, target.mergeFolderCalls)
    }

    @Test
    fun `merge_keepsLocalOnlyFolders`() {
        val target = FakeBackupEnvironment(
            folders = listOf(WorkflowFolder(id = "local-only", name = "本地独有"))
        )
        val source = FakeBackupEnvironment(
            folders = listOf(WorkflowFolder(id = "f1", name = "来自备份"))
        )
        val payload = scope.export(source, null)

        scope.import(target, payload, ImportMode.MERGE)

        assertEquals(setOf("local-only", "f1"), target.folderStore.map { it.id }.toSet())
        assertEquals(0, target.replaceFolderCalls)
        assertEquals(1, target.mergeFolderCalls)
    }

    @Test
    fun `nullPayloadSkipsWithoutTouchingEnvironment`() {
        val target = FakeBackupEnvironment(
            folders = listOf(WorkflowFolder(id = "untouched", name = "别动我"))
        )
        val result = scope.import(target, null, ImportMode.REPLACE)

        assertEquals(ImportStatus.SKIPPED_NOT_SELECTED, result.status)
        assertEquals(listOf("untouched"), target.folderStore.map { it.id })
        assertEquals(0, target.replaceFolderCalls)
        assertEquals(0, target.mergeFolderCalls)
    }

    @Test
    fun `importDoesNotReloadTriggers`() {
        // 文件夹不改触发器集合 —— 调 reloadTriggers 是多余的（且会打日志噪音）。
        val target = FakeBackupEnvironment()
        val source = FakeBackupEnvironment(folders = listOf(WorkflowFolder(id = "f1", name = "甲")))
        scope.import(target, scope.export(source, null), ImportMode.REPLACE)
        assertEquals(0, target.reloadCount)
    }

    @Test
    fun `exportPreservesParentId`() {
        val source = FakeBackupEnvironment(
            folders = listOf(
                WorkflowFolder(id = "child", name = "子", parentId = "parent"),
                WorkflowFolder(id = "parent", name = "父")
            )
        )
        val payload = scope.export(source, null)
        val target = FakeBackupEnvironment()
        scope.import(target, payload, ImportMode.REPLACE)

        assertEquals("parent", target.folderStore.single { it.id == "child" }.parentId)
    }

    @Test
    fun `importWritesParentsBeforeChildren`() {
        // 集合里子在前、父在后 —— 落盘顺序必须是父先于子。
        val source = FakeBackupEnvironment(
            folders = listOf(
                WorkflowFolder(id = "grandchild", name = "孙", parentId = "child"),
                WorkflowFolder(id = "child", name = "子", parentId = "parent"),
                WorkflowFolder(id = "parent", name = "父")
            )
        )
        val payload = scope.export(source, null)
        val target = FakeBackupEnvironment()
        scope.import(target, payload, ImportMode.REPLACE)

        val ids = target.folderStore.map { it.id }
        assertTrue("父必须先于子：$ids", ids.indexOf("parent") < ids.indexOf("child"))
        assertTrue("子必须先于孙：$ids", ids.indexOf("child") < ids.indexOf("grandchild"))
    }

    @Test
    fun `sortParentsFirstTreatsDanglingParentAsRoot`() {
        val folders = listOf(
            WorkflowFolder(id = "a", name = "A", parentId = "does-not-exist"),
            WorkflowFolder(id = "b", name = "B")
        )
        val sorted = FolderScope.sortParentsFirst(folders)
        assertEquals(setOf("a", "b"), sorted.map { it.id }.toSet())
    }

    @Test
    fun `sortParentsFirstDoesNotHangOnCycle`() {
        val folders = listOf(
            WorkflowFolder(id = "a", name = "A", parentId = "b"),
            WorkflowFolder(id = "b", name = "B", parentId = "a")
        )
        val sorted = FolderScope.sortParentsFirst(folders)
        assertEquals(2, sorted.size)
    }

    @Test
    fun `recordWithoutIdIsSkippedNotGivenAFreshId`() {
        // 「保 id」在类型层面要求本 scope 永不发明 id：
        // 残缺记录（连 id 都没有）无法与任何 workflow.folderId 对上，
        // 给它一个新 id 只会往用户列表里塞一个空壳文件夹。
        val env = FakeBackupEnvironment()
        val payload = com.chaomixian.vflow.core.backup.ScopePayload.of(
            listOf(
                com.google.gson.JsonParser.parseString("""{"name":"无 id"}"""),
                com.google.gson.JsonParser.parseString("""{"id":"has-id","name":"有 id"}""")
            )
        )

        val result = scope.import(env, payload, ImportMode.REPLACE)

        assertEquals(listOf("has-id"), env.folderStore.map { it.id })
        assertEquals(1, result.skipped)
    }

    @Test
    fun `recordWithNullNameFallsBackToEmptyString`() {
        // Gson 直接反序列化会让 `var name: String` 收到 null ⇒ 后续 NPE。
        val env = FakeBackupEnvironment()
        val payload = com.chaomixian.vflow.core.backup.ScopePayload.of(
            listOf(com.google.gson.JsonParser.parseString("""{"id":"f1"}"""))
        )

        scope.import(env, payload, ImportMode.REPLACE)

        assertEquals("", env.folderStore.single().name)
    }

    @Test
    fun `scopeMetadataIsStable`() {
        assertEquals("folders", scope.id)
        assertEquals(0, scope.importOrder)
        assertTrue(scope.dependsOn.isEmpty())
        assertEquals(false, scope.sensitive)
        assertEquals(true, scope.defaultIncluded)
    }
}
