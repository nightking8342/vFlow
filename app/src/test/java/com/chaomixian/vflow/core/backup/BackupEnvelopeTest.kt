// 文件: test/java/com/chaomixian/vflow/core/backup/BackupEnvelopeTest.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class BackupEnvelopeTest {

    private val gson = Gson()

    @Before
    fun setUp() {
        BackupEnvelope.warningSink = {}
    }

    @Test
    fun `round trip preserves scopes`() {
        val sections = mapOf(
            "folders" to ScopePayload.of(listOf(JsonPrimitive("f1"))),
            "workflows" to ScopePayload.of(listOf(JsonPrimitive("w1"), JsonPrimitive("w2")))
        )
        val text = BackupEnvelope.write(
            json = gson,
            sections = sections,
            includedScopes = listOf("folders", "workflows"),
            excludedScopes = emptyList(),
            scrubbedFields = emptyList(),
            appVersionName = "1.5.4",
            appVersionCode = 50,
            createdAt = 1712345678000L
        )

        val result = BackupEnvelope.read(gson, text)
        assertTrue(result is BackupEnvelope.ReadResult.Modern)
        val modern = result as BackupEnvelope.ReadResult.Modern
        assertEquals(BackupEnvelope.SCHEMA_VERSION, modern.header.schemaVersion)
        assertEquals(1712345678000L, modern.header.createdAt)
        assertEquals("1.5.4", modern.header.appVersionName)
        assertEquals(50, modern.header.appVersionCode)
        assertEquals(2, modern.scopes.getValue("workflows").count)
        assertEquals(1, modern.scopes.getValue("folders").count)
    }

    @Test
    fun `read rejects backup from a newer version without half parsing`() {
        val text = """
            {"schema":"vflow.backup","schemaVersion":99,"scopes":{"workflows":{"count":1,"data":[]}}}
        """.trimIndent()

        val result = BackupEnvelope.read(gson, text)
        assertTrue("必须返回 TooNew 而不是 Modern", result is BackupEnvelope.ReadResult.TooNew)
        assertEquals(99, (result as BackupEnvelope.ReadResult.TooNew).schemaVersion)
    }

    @Test
    fun `read accepts current schema version`() {
        val text = """
            {"schema":"vflow.backup","schemaVersion":1,"scopes":{}}
        """.trimIndent()
        assertTrue(BackupEnvelope.read(gson, text) is BackupEnvelope.ReadResult.Modern)
    }

    @Test
    fun `read rejects schemaVersion that is not a positive integer`() {
        val missing = """{"schema":"vflow.backup","scopes":{}}"""
        val zero = """{"schema":"vflow.backup","schemaVersion":0,"scopes":{}}"""
        val text = """{"schema":"vflow.backup","schemaVersion":"abc","scopes":{}}"""

        assertTrue(BackupEnvelope.read(gson, missing) is BackupEnvelope.ReadResult.Invalid)
        assertTrue(BackupEnvelope.read(gson, zero) is BackupEnvelope.ReadResult.Invalid)
        assertTrue(BackupEnvelope.read(gson, text) is BackupEnvelope.ReadResult.Invalid)
    }

    @Test
    fun `read rejects unknown envelope schema`() {
        val text = """{"schema":"someone.else.backup","schemaVersion":1}"""
        val result = BackupEnvelope.read(gson, text)
        assertTrue(result is BackupEnvelope.ReadResult.Invalid)
        assertTrue((result as BackupEnvelope.ReadResult.Invalid).reason.contains("未知信封类型"))
    }

    @Test
    fun `read detects legacy backup with workflows key`() {
        val text = """{"workflows":[{"id":"w1","name":"A"}],"folders":[]}"""
        val result = BackupEnvelope.read(gson, text)
        assertTrue("无 schema + 有 workflows ⇒ Legacy", result is BackupEnvelope.ReadResult.Legacy)
    }

    @Test
    fun `read detects legacy backup with folders key only`() {
        val text = """{"folders":[{"id":"f1","name":"F"}]}"""
        assertTrue(BackupEnvelope.read(gson, text) is BackupEnvelope.ReadResult.Legacy)
    }

    @Test
    fun `read treats bare array as legacy workflow list`() {
        // WorkflowJsonImportParser 认这种形状（导出的「裸工作流数组」）。
        // ⚠️ 这条必须在「不是对象 ⇒ Invalid」之前判，否则会被整份拒掉。
        val text = """[{"id":"w1","name":"A"}]"""
        val result = BackupEnvelope.read(gson, text)
        assertTrue(result is BackupEnvelope.ReadResult.Legacy)
        assertTrue((result as BackupEnvelope.ReadResult.Legacy).root.has("workflows"))
    }

    @Test
    fun `read rejects a plain JSON object that is neither modern nor legacy`() {
        val result = BackupEnvelope.read(gson, """{"foo":"bar"}""")
        assertTrue(result is BackupEnvelope.ReadResult.Invalid)
    }

    @Test
    fun `read rejects non JSON text`() {
        val result = BackupEnvelope.read(gson, "not json at all {{{")
        assertTrue(result is BackupEnvelope.ReadResult.Invalid)
    }

    @Test
    fun `read rejects a bare scalar`() {
        assertTrue(BackupEnvelope.read(gson, "42") is BackupEnvelope.ReadResult.Invalid)
    }

    @Test
    fun `read skips a malformed scope member without failing the whole file`() {
        // 局部损坏 ≠ 整份语义不明。坏一个范围，其余仍应能恢复。
        val warnings = mutableListOf<String>()
        BackupEnvelope.warningSink = { warnings += it }
        val text = """
            {"schema":"vflow.backup","schemaVersion":1,
             "scopes":{
               "workflows":{"count":1,"data":[{"id":"w1"}]},
               "broken":{"data":[]},
               "broken2":{"count":3}
             }}
        """.trimIndent()

        val result = BackupEnvelope.read(gson, text)
        assertTrue(result is BackupEnvelope.ReadResult.Modern)
        val scopes = (result as BackupEnvelope.ReadResult.Modern).scopes
        assertEquals(setOf("workflows"), scopes.keys)
        assertEquals(2, warnings.size)
    }

    @Test
    fun `read tolerates missing scopes object`() {
        val text = """{"schema":"vflow.backup","schemaVersion":1}"""
        val result = BackupEnvelope.read(gson, text)
        assertTrue(result is BackupEnvelope.ReadResult.Modern)
        assertNotNull((result as BackupEnvelope.ReadResult.Modern).scopes)
        assertTrue(result.scopes.isEmpty())
    }

    @Test
    fun `written envelope carries summary and null encryption slot`() {
        val text = BackupEnvelope.write(
            json = gson,
            sections = emptyMap(),
            includedScopes = listOf("folders"),
            excludedScopes = listOf("workflows"),
            scrubbedFields = emptyList(),
            appVersionName = "x",
            appVersionCode = 1
        )
        val root = com.google.gson.JsonParser.parseString(text).asJsonObject
        val summary = root.getAsJsonObject("summary")
        assertEquals("folders", summary.getAsJsonArray("includedScopes")[0].asString)
        assertEquals("workflows", summary.getAsJsonArray("excludedScopes")[0].asString)
        // T1 不做清洗 ⇒ 恒为空数组，但键必须在（T2 直接填）
        assertEquals(0, summary.getAsJsonArray("scrubbedFields").size())
        // 加密段 T1 恒为 null，键位保留使形状稳定
        assertTrue(root.has("encryption"))
        assertTrue(root.get("encryption").isJsonNull)
    }

    @Test
    fun `asArrayOrNull only accepts arrays`() {
        assertNotNull(BackupEnvelope.asArrayOrNull(JsonArray()))
        assertEquals(null, BackupEnvelope.asArrayOrNull(JsonPrimitive("x")))
        assertEquals(null, BackupEnvelope.asArrayOrNull(null))
    }
}
