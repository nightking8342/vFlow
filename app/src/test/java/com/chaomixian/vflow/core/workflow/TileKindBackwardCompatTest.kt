package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `kind` 字段的**向后兼容**（设计 §8.1）。
 *
 * `kind` 是 2026-10-06 新增的、**带默认值**的字段，而 `tile_list` 是已经落在
 * 用户设备上的 JSON（Gson 走反射、无 `@SerializedName`）⇒ 旧记录**没有**这个键。
 *
 * ⚠️ 这条兼容性没有任何运行时保护 —— Gson 遇到缺键时会用 Kotlin 的默认值
 * （**前提是该字段有默认值**）。若有人把 `kind: TileKind = TileKind.EXECUTE`
 * 的默认值删掉（例如改成必填），旧记录会在**反序列化时抛异常**，
 * 而 `TileManager.getAllTiles()` 的 `try/catch` 会把它**吞成 `emptyList()`**
 * —— 表现是「用户的 20 个磁贴绑定全部消失」，且**没有任何报错**。
 * 故这里用**真实的 Gson 往返**锁住它。
 */
class TileKindBackwardCompatTest {

    private val gson = Gson()

    private fun parseList(json: String): List<WorkflowTile> {
        val type = object : TypeToken<List<WorkflowTile>>() {}.type
        return gson.fromJson(json, type)
    }

    @Test
    fun `an old record without the kind key still deserializes with both fields intact`() {
        // ★ 这就是用户设备上现在躺着的形状（2026-10-06 之前写出来的）。
        val old = """[{"tileIndex":0,"workflowId":"wf-a"},{"tileIndex":7,"workflowId":null}]"""

        val tiles = parseList(old)

        assertEquals("旧记录必须能读出来（读不出来 = 用户全部绑定静默消失）", 2, tiles.size)
        assertEquals(0, tiles[0].tileIndex)
        assertEquals("wf-a", tiles[0].workflowId)
        // ⚠️ `workflowId` 为 null 也是**合法状态**（已分配索引但尚未绑定）
        assertNull(tiles[1].workflowId)
    }

    @Test
    fun `a missing kind is NOT filled with the Kotlin default by Gson`() {
        // ⚠️⚠️ **实测事实，与直觉相反，必须记下来**：
        //
        // Gson 用 `Unsafe.allocateInstance` 构造对象，**绕过 Kotlin 构造函数**，
        // 所以 `kind: TileKind = TileKind.EXECUTE` 这个默认值**不会**被填上 ——
        // 缺键时读出的是 **`null`**（枚举字段的 Java 默认值）。
        //
        // 本仓库对同类问题的既有解法是**显式归一化**（见 `WorkflowManager.kt:298`
        // 的 `WorkflowLogLevel.fromStoredValue(record.getString("logLevel"))`、
        // `WorkflowReentryBehavior.fromStoredValue(...)`）—— 不是在模型上放默认值。
        //
        // ⇒ 磁贴侧必须走**同一条**口径：`TileManager` 读出来之后按槽位归一化
        //   （`TileSlot.kindOf(tileIndex) ?: EXECUTE`）。本用例锁住「Gson 一定给 null」
        //   这个前提 —— 若将来换成别的序列化方式，这条会变红，提醒维护者
        //   重新检查归一化逻辑是否还必要。
        val tiles = parseList("""[{"tileIndex":0,"workflowId":"wf-a"}]""")
        assertNull(
            "Gson 不填 Kotlin 默认值 ⇒ kind 是 null ⇒ 归一化必须在 TileManager 里做",
            tiles[0].kind
        )
    }

    @Test
    fun `a record with an explicit TOGGLE kind round-trips`() {
        val json = """[{"tileIndex":20,"workflowId":"wf-b","kind":"TOGGLE"}]"""
        val tiles = parseList(json)
        assertEquals(1, tiles.size)
        assertEquals(TileKind.TOGGLE, tiles[0].kind)
        assertEquals(20, tiles[0].tileIndex)
    }

    @Test
    fun `serialization writes all three field names verbatim`() {
        // ⚠️ 字段名即 wire 名（Gson 无 @SerializedName）。
        //    改名会让新旧备份互不兼容，而 `TileScopeTest` 有一条断言把
        //    「导出的键集合」钉死在这里 —— 那是它**正常工作**的表现。
        val json = gson.toJson(listOf(WorkflowTile(20, "wf-b", TileKind.TOGGLE)))
        assertEquals(
            """[{"tileIndex":20,"workflowId":"wf-b","kind":"TOGGLE"}]""",
            json
        )
    }

    @Test
    fun `a full round trip preserves every field`() {
        val original = listOf(
            WorkflowTile(0, "wf-a", TileKind.EXECUTE),
            WorkflowTile(20, null, TileKind.TOGGLE),
            WorkflowTile(39, "wf-z", TileKind.TOGGLE),
        )
        val restored = parseList(gson.toJson(original))
        assertEquals(original, restored)
    }

    @Test
    fun `an unknown kind value does not crash deserialization`() {
        // ⚠️ 将来若给 TileKind 加第三个值、又被降级安装回旧版本，旧版本的 Gson
        //    会拿到一个它不认识的枚举名。**这里锁的是「不抛」** ——
        //    `TileManager.getAllTiles()` 的 catch 会把异常吞成 `emptyList()`，
        //    那等于用户所有绑定**静默消失**。
        val tiles = parseList("""[{"tileIndex":0,"workflowId":"wf","kind":"SOMETHING_NEW"}]""")
        assertEquals(1, tiles.size)
        assertEquals(0, tiles[0].tileIndex)
        assertEquals("wf", tiles[0].workflowId)
        // 未知枚举名同样落 null（同上一条），由归一化兜住
        assertNull(tiles[0].kind)
    }
}
