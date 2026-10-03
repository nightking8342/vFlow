// 文件: test/java/com/chaomixian/vflow/core/backup/scopes/TileScopeTest.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.LogLevel
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TileScope] 的逐键测试 + **MERGE 的专门语义**。
 *
 * ## ⚠️ 本文件真正在防的是什么
 *
 * `tile_list` 是**一个键装一个数组**。默认的「备份优先、整键覆盖」会让
 * **本地独有的磁贴消失** —— 那正是用户在选「合并式」时最不想要的
 * （选合并就是不想丢本地的东西）。故本 scope 覆写合并为**按 `tileIndex` 的并集**。
 *
 * 而「按 tileIndex 而不是 workflowId 合并」也有理由：后者可为 null
 * （已分配索引但尚未绑定工作流是合法状态）。
 */
class TileScopeTest {

    private val scope = TileScope()

    private fun tiles(vararg pairs: Pair<Int, String?>): String =
        pairs.joinToString(prefix = "[", postfix = "]") { (index, wf) ->
            """{"tileIndex":$index,"workflowId":${if (wf == null) "null" else "\"$wf\""}}"""
        }

    private fun envWithTiles(json: String): FakeBackupEnvironment {
        val env = FakeBackupEnvironment()
        val store = env.prefs as FakeBackupEnvironment.InMemoryPrefs
        store.data["vflow_tiles"] = linkedMapOf<String, Any?>("tile_list" to json)
        return env
    }

    private fun tilesOf(env: FakeBackupEnvironment): Map<Int, String?> {
        val raw = (env.prefs as FakeBackupEnvironment.InMemoryPrefs)
            .data["vflow_tiles"]?.get("tile_list") as String
        return JsonParser.parseString(raw).asJsonArray.associate { element ->
            val obj = element.asJsonObject
            obj.get("tileIndex").asInt to
                obj.get("workflowId")?.takeIf { !it.isJsonNull }?.asString
        }
    }

    // ── 静态声明 ────────────────────────────────────────────

    @Test
    fun `declares the contract from the design`() {
        assertEquals("tiles", scope.id)
        assertEquals(ScopeGroup.CONFIG, scope.group)
        assertFalse(scope.sensitive)
        assertTrue(scope.defaultIncluded)
        assertEquals(20, scope.importOrder)
        assertEquals(
            "磁贴引用 workflowId ⇒ 必须等工作流就位（否则 id 指向不存在的项）",
            listOf("workflows"), scope.dependsOn,
        )
        assertEquals(setOf("vflow_tiles"), scope.specs.map { it.prefsName }.toSet())
        assertEquals(
            setOf("tile_list"), scope.specs.first().exactKeys,
        )
    }

    // ── 往返 ────────────────────────────────────────────────

    @Test
    fun `a plain round trip preserves every tile`() {
        val env = envWithTiles(tiles(0 to "wf-a", 3 to "wf-b", 7 to null))
        val payload = scope.export(env, null)!!
        assertEquals(1, payload.count)

        val importing = FakeBackupEnvironment()
        val result = scope.import(importing, payload, ImportMode.MERGE)
        assertEquals(ImportStatus.IMPORTED, result.status)

        assertEquals(
            mapOf(0 to "wf-a", 3 to "wf-b", 7 to null),
            tilesOf(importing),
        )
    }

    @Test
    fun `an unassigned tile index with a null workflow id round trips`() {
        // ⚠️「已分配索引但尚未绑定工作流」是**合法状态**（`WorkflowTile.workflowId` 可空）。
        //    把它读成字符串 "null" 或丢掉这一条，会让用户看到磁贴突然空了一格。
        val env = envWithTiles(tiles(5 to null))
        val payload = scope.export(env, null)!!
        val importing = FakeBackupEnvironment()
        scope.import(importing, payload, ImportMode.MERGE)
        assertEquals(mapOf(5 to null), tilesOf(importing))
    }

    // ── MERGE：按 tileIndex 的并集 ──────────────────────────

    @Test
    fun `MERGE keeps local tiles that the backup does not contain`() {
        // ⚠️⚠️ 本文件的核心用例。默认的整键覆盖会让本地独有的磁贴**消失**。
        val importing = envWithTiles(tiles(1 to "local-only", 2 to "shared"))
        val exporting = envWithTiles(tiles(2 to "from-backup", 9 to "backup-only"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(
            "本地独有的磁贴必须保留、备份的必须并入、同 index 以备份优先",
            mapOf(
                1 to "local-only",
                2 to "from-backup",
                9 to "backup-only",
            ),
            tilesOf(importing),
        )
    }

    @Test
    fun `MERGE merges by tileIndex not by workflow id`() {
        // ⚠️ 按 workflowId 合并的话，同一个工作流出现在两个磁贴上时
        //    会互相覆盖 —— 而 `TileManager.saveTile` 只按 tileIndex 去重，
        //    即同一工作流出现在多个磁贴是允许的。
        val importing = envWithTiles(tiles(0 to "same-wf"))
        val exporting = envWithTiles(tiles(1 to "same-wf"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(
            "两个磁贴指向同一工作流时必须都保留",
            mapOf(0 to "same-wf", 1 to "same-wf"),
            tilesOf(importing),
        )
    }

    @Test
    fun `REPLACE replaces the whole key so local only tiles disappear`() {
        val importing = envWithTiles(tiles(1 to "local-only", 2 to "shared"))
        val exporting = envWithTiles(tiles(2 to "from-backup"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.REPLACE)

        assertEquals(
            "REPLACE 语义就是「备份里没有的消失」",
            mapOf(2 to "from-backup"),
            tilesOf(importing),
        )
    }

    // ── 退化路径 ────────────────────────────────────────────

    @Test
    fun `MERGE degrades to whole key replacement when one side is not valid JSON`() {
        val importing = envWithTiles("not json at all")
        val exporting = envWithTiles(tiles(2 to "from-backup"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(
            "坏 JSON 一侧 ⇒ 退化为整键替换（不能整体失败）",
            mapOf(2 to "from-backup"),
            tilesOf(importing),
        )
        assertTrue(
            "退化必须留一条 W 日志（否则用户不知道本地磁贴为什么没了）",
            importing.logs.any { it.first == LogLevel.W },
        )
    }

    @Test
    fun `MERGE degrades when elements carry no tileIndex`() {
        // ⚠️ 元素读不到 tileIndex（R8 改名 / 手工改过）时**不能**把每个元素
        //    都当「本地独有」处理 —— 那会把整个数组复制一份。
        val importing = envWithTiles("""[{"workflowId":"no-index"}]""")
        val exporting = envWithTiles(tiles(2 to "from-backup"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(
            "读不到 tileIndex ⇒ 退化为整键替换，而不是把本地那份复制一份",
            mapOf(2 to "from-backup"),
            tilesOf(importing),
        )
    }

    @Test
    fun `MERGE into an empty local list just takes the backup`() {
        val importing = FakeBackupEnvironment()
        val exporting = envWithTiles(tiles(4 to "wf"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(mapOf(4 to "wf"), tilesOf(importing))
    }

    @Test
    fun `an empty local value does not crash the merge`() {
        // `TileManager.getAllTiles` 在 prefs 为空时返回空表；
        // 但若某个版本写成 `""`（空串），解析会失败 ⇒ 必须走退化路径而不是抛。
        val importing = envWithTiles("")
        val exporting = envWithTiles(tiles(2 to "from-backup"))
        val payload = scope.export(exporting, null)!!

        scope.import(importing, payload, ImportMode.MERGE)

        assertEquals(mapOf(2 to "from-backup"), tilesOf(importing))
    }

    // ── 元素形状必须与 TileManager 兼容 ─────────────────────

    @Test
    fun `the wire shape matches what TileManager writes`() {
        // ⚠️ 本 scope 在**文本层**合并（不解析成 WorkflowTile —— 那会引入
        //    android 依赖、破坏纯度扫描）。故它必须与 `TileManager` 写出的
        //    形状一致。用一份**手工构造的、与 TileManager 同形状的** JSON 验证。
        val env = envWithTiles(tiles(0 to "wf-a"))
        val payload = scope.export(env, null)!!
        val exported = payload.data.asJsonArray[0].asJsonObject
            .get("items").asJsonObject
            .get("tile_list").asJsonObject
            .get("v").asString

        val parsed = JsonParser.parseString(exported).asJsonArray[0].asJsonObject
        assertEquals(
            "键名必须与 `WorkflowTile` 的字段名逐字一致（Gson 走反射）",
            setOf("tileIndex", "workflowId"),
            parsed.entrySet().map { it.key }.toSet(),
        )
    }
}
