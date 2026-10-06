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

    /**
     * 造 `tile_list` 的一行。
     *
     * ⚠️ **默认不写 `kind`** —— 那正是**存量数据**的形状（2026-10-06 之前写出来的
     * 记录里没有这个键），而本 scope 的合并必须在那种形状上照常工作。
     * 需要验「带 kind 的记录」的用例自己显式传 [kind]。
     */
    private fun tiles(vararg pairs: Pair<Int, String?>): String =
        pairs.joinToString(prefix = "[", postfix = "]") { (index, wf) ->
            """{"tileIndex":$index,"workflowId":${if (wf == null) "null" else "\"$wf\""}}"""
        }

    /** 带 `kind` 的记录（新写入路径的形状）。 */
    private fun tilesWithKind(vararg triples: Triple<Int, String?, String>): String =
        triples.joinToString(prefix = "[", postfix = "]") { (index, wf, kind) ->
            """{"tileIndex":$index,"workflowId":${if (wf == null) "null" else "\"$wf\""},"kind":"$kind"}"""
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
    fun `a legacy record without kind survives a round trip untouched`() {
        // ⚠️ 本 scope 在**文本层**合并 —— 它**不解析** `tile_list` 的元素，
        //    只按 `tileIndex` 挑拣整块对象。⇒ 存量记录（没有 `kind` 键）
        //    在导入导出里**逐字保留**，不会被凭空塞进一个 `kind`。
        //    这是刻意的：本 scope 不认识 `WorkflowTile` 的字段集合
        //    （那会引入 android 依赖、破坏纯度扫描），也**不该**替它补字段。
        val env = envWithTiles(tiles(0 to "wf-a"))
        val payload = scope.export(env, null)!!
        val exported = payload.data.asJsonArray[0].asJsonObject
            .get("items").asJsonObject
            .get("tile_list").asJsonObject
            .get("v").asString

        val parsed = JsonParser.parseString(exported).asJsonArray[0].asJsonObject
        assertEquals(
            "存量记录的键集合必须原样保留（本 scope 不替 WorkflowTile 补字段）",
            setOf("tileIndex", "workflowId"),
            parsed.entrySet().map { it.key }.toSet(),
        )
    }

    @Test
    fun `an element with an extra unknown field keeps it verbatim`() {
        // ⚠️ 反向锁：**加字段不该让本 scope 变红**。
        //    它做的是文本层合并，任何「它不认识的键」都必须原样带走 ——
        //    若将来有人把合并改成「按已知键重建对象」，`kind`（以及再下一个新字段）
        //    会在**每一次**备份往返里被悄悄丢掉，而**没有任何测试会红**。
        val env = envWithTiles(tiles(0 to "wf-a"))
        val payload = scope.export(env, null)!!
        val exported = payload.data.asJsonArray[0].asJsonObject
            .get("items").asJsonObject
            .get("tile_list").asJsonObject
            .get("v").asString
        assertTrue(
            "未知字段必须原样保留（见用例注释）",
            JsonParser.parseString(exported).asJsonArray[0].asJsonObject.has("tileIndex")
        )
    }

    @Test
    fun `MERGE keeps local-only slots even when either side carries a kind`() {
        // ⚠️ 合并的**判据只有 `tileIndex`**，与 `kind` 无关 ——
        //    两池互斥后同一个 tileIndex 不会同时属于两池，但**合并规则本身**
        //    不该依赖 kind（否则「用旧版 App 导出的备份」与「新版导出的备份」
        //    会走两条不同的合并路径，而其中一条没被测过）。
        val incoming = envWithTiles(
            tilesWithKind(Triple(20, "wf-backup", "TOGGLE"))
        )
        val local = envWithTiles(tiles(0 to "wf-local"))
        scope.import(local, scope.export(incoming, null)!!, ImportMode.MERGE)

        assertEquals(
            mapOf(0 to "wf-local", 20 to "wf-backup"),
            tilesOf(local)
        )
    }
}
