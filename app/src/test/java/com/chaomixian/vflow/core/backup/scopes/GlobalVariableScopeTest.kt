// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/GlobalVariableScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalVariableScopeTest {

    private val scope = GlobalVariableScope()

    @Test
    fun `emptyDatasetIsNotEmptyPayload`() {
        val payload = scope.export(FakeBackupEnvironment(), null)
        assertNotNull("空数据集必须返回 payload，不能是 null", payload)
        assertEquals(0, payload!!.count)
        assertTrue(payload.data.isJsonArray)
    }

    @Test
    fun `roundTripKeepsEveryKeyAndValue`() {
        // ⚠️ 逐键比「值」而不是只比 count —— 「形状对了但值丢了」是本 scope 最容易出的静默失效。
        val variables = linkedMapOf<String, com.chaomixian.vflow.core.types.VObject>(
            "s" to VString("文本"),
            "n" to VNumber(3.5),
            "b" to VBoolean(true),
            "empty" to VString("")
        )
        val source = FakeBackupEnvironment(variables = variables)
        val target = FakeBackupEnvironment()

        scope.import(target, scope.export(source, null), ImportMode.REPLACE)

        assertEquals(variables.keys, target.variableStore.keys)
        assertEquals("文本", (target.variableStore.getValue("s") as VString).raw)
        assertEquals(3.5, (target.variableStore.getValue("n") as VNumber).raw.toDouble(), 0.0001)
        assertEquals(true, (target.variableStore.getValue("b") as VBoolean).raw)
        assertEquals("", (target.variableStore.getValue("empty") as VString).raw)
    }

    @Test
    fun `exportedBackupShapeMatchesStoreFormat`() {
        // 「备份格式 ≠ 存储格式」那一节：形状必须仍是
        // [{"name":...,"type":"string"|"number"|"boolean","value":...}]，
        // type 是裸标签而不是 "vflow.type.string"。
        val source = FakeBackupEnvironment(
            variables = mapOf(
                "s" to VString("v"),
                "n" to VNumber(1),
                "b" to VBoolean(false)
            )
        )
        val payload = scope.export(source, null)!!

        val byName = payload.data.asJsonArray.associate {
            val obj = it.asJsonObject
            obj.get("name").asString to obj
        }
        assertEquals("string", byName.getValue("s").get("type").asString)
        assertEquals("number", byName.getValue("n").get("type").asString)
        assertEquals("boolean", byName.getValue("b").get("type").asString)
        // 形状约束：不得出现 vflow.type.* 全限定类型 ID
        payload.data.asJsonArray.forEach {
            val type = it.asJsonObject.get("type").asString
            assertTrue("type 必须是裸标签，实际：$type", !type.startsWith("vflow.type."))
        }
    }

    @Test
    fun `numberTypeSurvivesAsNumber`() {
        // 存储层把 number 存成字符串（value.raw.toString()），读回要能还原成 VNumber。
        val source = FakeBackupEnvironment(variables = mapOf("n" to VNumber(42.0)))
        val payload = scope.export(source, null)!!

        val obj = payload.data.asJsonArray[0].asJsonObject
        assertEquals("42.0", obj.get("value").asString)

        val target = FakeBackupEnvironment()
        scope.import(target, payload, ImportMode.REPLACE)
        assertEquals(42.0, (target.variableStore.getValue("n") as VNumber).raw.toDouble(), 0.0001)
    }

    @Test
    fun `replace_removesLocalKeysMissingFromBackup`() {
        val target = FakeBackupEnvironment(
            variables = mapOf("localOnly" to VString("别动我"), "shared" to VString("旧"))
        )
        val source = FakeBackupEnvironment(
            variables = mapOf("shared" to VString("来自备份"))
        )

        scope.import(target, scope.export(source, null), ImportMode.REPLACE)

        assertEquals(
            "REPLACE 必须真的删掉本地独有的键：${target.variableStore.keys}",
            setOf("shared"), target.variableStore.keys
        )
        assertEquals("来自备份", (target.variableStore.getValue("shared") as VString).raw)
    }

    @Test
    fun `merge_keepsLocalKeysAndBackupWinsOnConflict`() {
        val target = FakeBackupEnvironment(
            variables = mapOf("localOnly" to VString("保留"), "shared" to VString("旧"))
        )
        val source = FakeBackupEnvironment(
            variables = mapOf("shared" to VString("来自备份"), "backupOnly" to VString("新增"))
        )

        scope.import(target, scope.export(source, null), ImportMode.MERGE)

        assertEquals(
            setOf("localOnly", "shared", "backupOnly"), target.variableStore.keys
        )
        assertEquals("保留", (target.variableStore.getValue("localOnly") as VString).raw)
        assertEquals("入参优先", "来自备份", (target.variableStore.getValue("shared") as VString).raw)
    }

    @Test
    fun `nullPayloadSkipsWithoutTouchingEnvironment`() {
        val target = FakeBackupEnvironment(variables = mapOf("k" to VString("v")))
        val result = scope.import(target, null, ImportMode.REPLACE)

        assertEquals(ImportStatus.SKIPPED_NOT_SELECTED, result.status)
        assertEquals(setOf("k"), target.variableStore.keys)
    }

    @Test
    fun `importDoesNotReloadTriggers`() {
        val source = FakeBackupEnvironment(variables = mapOf("k" to VString("v")))
        val target = FakeBackupEnvironment()
        scope.import(target, scope.export(source, null), ImportMode.REPLACE)
        assertEquals(0, target.reloadCount)
    }

    @Test
    fun `malformedRecordIsSkipped`() {
        val env = FakeBackupEnvironment()
        val payload = ScopePayload.of(
            listOf(
                JsonParser.parseString("""{"type":"string","value":"无 name"}"""),
                JsonParser.parseString("""{"name":"no-type","value":"x"}"""),
                JsonParser.parseString("""{"name":"ok","type":"string","value":"fine"}""")
            )
        )

        val result = scope.import(env, payload, ImportMode.REPLACE)

        assertEquals(setOf("ok"), env.variableStore.keys)
        assertEquals(2, result.skipped)
    }

    @Test
    fun `unknownTypeFallsBackToStringLikeTheStoreDoes`() {
        val env = FakeBackupEnvironment()
        val payload = ScopePayload.of(
            listOf(JsonParser.parseString("""{"name":"k","type":"weird","value":"v"}"""))
        )
        scope.import(env, payload, ImportMode.REPLACE)
        assertEquals("v", (env.variableStore.getValue("k") as VString).raw)
    }

    @Test
    fun `exportUsesStableKeyOrderSoRoundTripsAreAssertable`() {
        val source = FakeBackupEnvironment(
            variables = linkedMapOf("z" to VString("1"), "a" to VString("2"), "m" to VString("3"))
        )
        val names = scope.export(source, null)!!.data.asJsonArray.map {
            it.asJsonObject.get("name").asString
        }
        assertEquals(listOf("a", "m", "z"), names)
    }

    @Test
    fun `scopeMetadataIsStable`() {
        assertEquals("global_variables", scope.id)
        assertEquals(5, scope.importOrder)
        assertTrue(scope.dependsOn.isEmpty())
        assertEquals(false, scope.sensitive)
    }
}
