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
     * ⚠️ **固定一处已知的 legacy 缺陷**（不是「测试通过就说明它是对的」）。
     *
     * `WorkflowJsonImportParser` 从不解析 `functionSignature`（实测 `grep` 零命中），
     * 而 `Workflow.isFunction` 由 `functionSignature != null` 派生 ⇒
     * **函数工作流经 legacy 路径会静默退化成普通工作流**。
     *
     * 这是 legacy 路径的既有行为，T1 范围内不修（见交付说明的遗留问题）。
     * 本用例把它**钉住**：将来有人修好它，这条会变红，届时应当**更新本用例
     * 并同步登记**，而不是以为「测试写错了」。
     */
    @Test
    fun `known limitation - legacy path drops functionSignature`() {
        val text = """
            {"workflows":[{"id":"fn1","name":"函数流","steps":[],
              "functionSignature":{"params":[{"name":"a","type":"vflow.type.string"}],"returnDef":null}}]}
        """.trimIndent()

        val adapted = LegacyBackupAdapter.adaptText(gson, text)
        val workflow = gson.fromJson(
            adapted.getValue("workflows").data.asJsonArray[0],
            Workflow::class.java
        )

        assertNull(
            "legacy 路径丢 functionSignature 是已知行为（WorkflowJsonImportParser 零解析）；" +
                "若这里不再为 null，说明该缺陷已被修 —— 请更新本用例并登记",
            workflow.functionSignature
        )
        assertTrue(workflow.isFunction.not())
    }

    @Test
    fun `adapting malformed json returns empty instead of crashing`() {
        val adapted = LegacyBackupAdapter.adaptText(gson, "{{{ broken")
        assertNotNull(adapted)
        assertTrue(adapted.isEmpty())
    }
}
