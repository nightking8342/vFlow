// 文件: test/java/com/chaomixian/vflow/core/backup/LegacyBackupAdapterTest.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.workflow.model.Workflow
import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LegacyBackupAdapterTest {

    private val gson = Gson()

    /** 既有导出格式：`{"workflows":[20 键 map],"folders":[...]}`。 */
    private val legacyText = """
        {
          "workflows": [
            {"id":"w1","name":"工作流一","isEnabled":true,"folderId":"f1","steps":[]},
            {"id":"w2","name":"工作流二","isEnabled":false,"steps":[]}
          ],
          "folders": [
            {"id":"f1","name":"文件夹一"}
          ]
        }
    """.trimIndent()

    @Test
    fun `adapts legacy backup into folders and workflows payloads`() {
        val adapted = LegacyBackupAdapter.adaptText(gson, legacyText)

        assertEquals(setOf("folders", "workflows"), adapted.keys)
        assertEquals(1, adapted.getValue("folders").count)
        assertEquals(2, adapted.getValue("workflows").count)
    }

    @Test
    fun `adapts legacy folder keeping its id`() {
        val adapted = LegacyBackupAdapter.adaptText(gson, legacyText)
        val folder = gson.fromJson(
            adapted.getValue("folders").data.asJsonArray[0],
            com.chaomixian.vflow.core.workflow.model.WorkflowFolder::class.java
        )
        assertEquals("f1", folder.id)
        assertEquals("文件夹一", folder.name)
    }

    @Test
    fun `adapts legacy workflow keeping id and folder link`() {
        val adapted = LegacyBackupAdapter.adaptText(gson, legacyText)
        val workflows = adapted.getValue("workflows").data.asJsonArray.map {
            gson.fromJson(it, Workflow::class.java)
        }
        assertEquals(listOf("w1", "w2"), workflows.map { it.id })
        assertEquals("f1", workflows.first().folderId)
        // ⚠️ 重名加后缀是 WorkflowImportHelper 的「导入」语义；
        //    适配器走 WorkflowJsonImportParser，只解析不改名。
        assertEquals("工作流一", workflows.first().name)
    }

    @Test
    fun `adapts bare array of workflows`() {
        val text = """[{"id":"only","name":"Only"}]"""
        val adapted = LegacyBackupAdapter.adaptText(gson, text)

        assertTrue("裸数组只有工作流，不该凭空造出 folders", "folders" !in adapted.keys)
        assertEquals(1, adapted.getValue("workflows").count)
    }

    @Test
    fun `legacy backup with folders only does not invent workflows`() {
        val text = """{"folders":[{"id":"f1","name":"F"}]}"""
        val adapted = LegacyBackupAdapter.adaptText(gson, text)

        assertEquals(setOf("folders"), adapted.keys)
    }

    @Test
    fun `legacy backup never invents global_variables`() {
        // 旧格式没有全局变量。凭空造一个空 payload 会与
        // 「本机注册了但信封里没有 ⇒ SKIPPED_MISSING」的语义混同。
        val adapted = LegacyBackupAdapter.adaptText(gson, legacyText)
        assertTrue("global_variables" !in adapted.keys)
    }

    @Test
    fun `isLegacy matches BackupEnvelope judgement`() {
        val adapter = LegacyBackupAdapter(gson)
        val legacy = com.google.gson.JsonParser.parseString(legacyText).asJsonObject
        val modern = com.google.gson.JsonParser
            .parseString("""{"schema":"vflow.backup","schemaVersion":1}""").asJsonObject

        assertTrue(adapter.isLegacy(legacy))
        assertTrue(!adapter.isLegacy(modern))
    }

    /**
     * ⚠️ **这条曾经锁的是一个缺陷**，现已修正 —— 见下。
     *
     * 旧行为：`WorkflowJsonImportParser` 从不解析 `functionSignature`
     * （实测 `grep` 零命中），而 `Workflow.isFunction` 由 `functionSignature != null`
     * 派生 ⇒ **函数工作流经 legacy 路径静默退化成普通工作流**。
     * 当时的用例刻意断言 `functionSignature == null` 把这个缺陷钉住，
     * 并在注释里写明「将来有人修好它，这条会变红，届时应当更新本用例」。
     *
     * 现在：解析实现下移到 `WorkflowFunctionSignatureCodec`（三条读路径共用），
     * 本用例按当时的约定**翻面**成正面断言 —— 比原来更强（原来只断言「是 null」，
     * 现在断言具体的参数名与类型都被还原）。
     */
    @Test
    fun `legacy path now keeps functionSignature`() {
        val text = """
            {"workflows":[{"id":"fn1","name":"函数流","steps":[],
              "functionSignature":{"params":[{"name":"a","type":"vflow.type.string"}],"returnDef":null}}]}
        """.trimIndent()

        val adapted = LegacyBackupAdapter.adaptText(gson, text)
        val workflow = gson.fromJson(
            adapted.getValue("workflows").data.asJsonArray[0],
            Workflow::class.java
        )

        assertNotNull(
            "函数工作流经 legacy 备份路径**必须**保住 functionSignature —— " +
                "丢了会让 isFunction 变 false，静默退化成普通工作流",
            workflow.functionSignature
        )
        assertEquals("a", workflow.functionSignature!!.params.single().name)
        assertEquals("vflow.type.string", workflow.functionSignature!!.params.single().type)
        assertTrue(workflow.isFunction)
    }

    @Test
    fun `adapting malformed json returns empty instead of crashing`() {
        val adapted = LegacyBackupAdapter.adaptText(gson, "{{{ broken")
        assertNotNull(adapted)
        assertTrue(adapted.isEmpty())
    }
}
