package com.chaomixian.vflow.core.workflow

import android.content.Context
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

class TileManager(val context: Context) {
    private val prefs = context.getSharedPreferences("vflow_tiles", Context.MODE_PRIVATE)
    private val gson = Gson()

    companion object {
        private const val KEY_TILES = "tile_list"
    }

    /**
     * 保存Tile分配
     */
    fun saveTile(tile: WorkflowTile) {
        val tiles = getAllTiles().toMutableList()
        val index = tiles.indexOfFirst { it.tileIndex == tile.tileIndex }
        if (index != -1) {
            tiles[index] = tile
        } else {
            tiles.add(tile)
        }
        saveAllTiles(tiles)
    }

    /**
     * 获取指定索引的Tile
     */
    fun getTile(tileIndex: Int): WorkflowTile? {
        return getAllTiles().find { it.tileIndex == tileIndex }
    }

    /**
     * 获取所有已分配的Tile。
     *
     * ⚠️⚠️ **返回值必须过 [TileFieldNormalizer]** —— 这里是**所有读取路径的共同上游**
     * （磁贴 service / 选择面板 / `remove*` 系列都从这里拿）。
     *
     * 原因见 `WorkflowTile.kind` 的 KDoc：**Gson 不填 Kotlin 的默认值**
     * （它绕过构造函数），旧记录（缺 `kind` 键）读出的是 `null`；
     * 而 `null` 赋给非空 `val` 会在**首次读取**时抛 NPE，被本方法的
     * `catch (e: Exception)` **吞成 `emptyList()`** ⇒ 表现是「用户 20 个磁贴绑定
     * 全部消失」，且**没有任何报错**。
     *
     * ⚠️ 归一化放在这里（而不是只放在 `getAllTilesWithEmpty`）——
     * 只归一化一处会出现 `getTile()` 返回矛盾记录、而 `getAllTilesWithEmpty()`
     * 正常的**静默不一致**（`TileGate` 与 `removeTileByWorkflowIdInKind` 都按 kind 判）。
     */
    fun getAllTiles(): List<WorkflowTile> {
        val json = prefs.getString(KEY_TILES, null) ?: return emptyList()
        return try {
            val type = object : TypeToken<List<WorkflowTile>>() {}.type
            val parsed: List<WorkflowTile>? = gson.fromJson(json, type)
            TileFieldNormalizer.normalizeAll(parsed ?: emptyList())
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 获取全部 40 个磁贴槽（包括未分配的）。
     *
     * ⚠️ **未分配的槽也要带上正确的 `kind`** —— 面板、service、`TileRefreshNotifier`
     * 三处都按 `kind` 分派；若让未分配的槽落 `null`，开关池的槽会**被当成执行型**
     * （不报错，只是行为全错）。
     * 此处走 [TileSlot.kindOf]，与 [TileSlot] 的区间定义**同源**。
     *
     * ⚠️ 已分配的槽来自 [getAllTiles]（那里已经归一化过），此处不重复归一。
     */
    fun getAllTilesWithEmpty(): List<WorkflowTile> {
        val assignedTiles = getAllTiles()
        return (0 until WorkflowTile.TILE_COUNT).map { index ->
            assignedTiles.find { it.tileIndex == index }
                ?: WorkflowTile(index, null, TileSlot.kindOf(index) ?: TileKind.EXECUTE)
        }
    }

    /**
     * 只取**某一池**的 40 槽里的那 20 条（含未分配槽）。
     *
     * 选择面板按池分段显示，只列属于这一池的槽位（§4.6 闸 2）。
     */
    fun getAllTilesWithEmpty(kind: TileKind): List<WorkflowTile> =
        getAllTilesWithEmpty().filter { it.tileIndex in tileRangeOf(kind) }

    private fun tileRangeOf(kind: TileKind): IntRange {
        val offset = TileSlot.offsetOf(kind)
        return offset until (offset + TileSlot.tileCountOf(kind))
    }

    /**
     * 移除Tile分配
     */
    fun removeTile(tileIndex: Int) {
        val tiles = getAllTiles().toMutableList()
        tiles.removeAll { it.tileIndex == tileIndex }
        saveAllTiles(tiles)
    }

    /**
     * 根据workflowId获取已分配的Tile
     */
    fun getTileByWorkflowId(workflowId: String): WorkflowTile? {
        return getAllTiles().find { it.workflowId == workflowId }
    }

    /**
     * 移除工作流的Tile分配
     */
    fun removeTileByWorkflowId(workflowId: String) {
        val tiles = getAllTiles().toMutableList()
        tiles.removeAll { it.workflowId == workflowId }
        saveAllTiles(tiles)
    }

    /**
     * 只移除该工作流在**某一池**里的分配（fork，2026-10-06）。
     *
     * ⚠️ 与 [removeTileByWorkflowId]（无差别删全部）的区别是必要的：
     * 两池互斥后同一工作流**不会**同时在两池，但「先在执行池解绑、再去开关池绑定」
     * 这条路径上，无差别删会连带把用户在另一池刚绑好的也删掉 —— 且**没有任何提示**。
     *
     * ⚠️ 判定用 `TileSlot.kindOf(tile.tileIndex)` 而**不读 `tile.kind`** ——
     * 后者可能因**手工改过 JSON**（或将来某次迁移）与实际槽位不一致，
     * 而以槽位为准是这个类的既有口径（`getAllTilesWithEmpty` 也是这么补 kind 的）。
     */
    fun removeTileByWorkflowIdInKind(workflowId: String, kind: TileKind) {
        val tiles = getAllTiles().toMutableList()
        tiles.removeAll { it.workflowId == workflowId && it.kind == kind }
        saveAllTiles(tiles)
    }

    /**
     * 批量保存所有Tile
     */
    private fun saveAllTiles(tiles: List<WorkflowTile>) {
        val json = gson.toJson(tiles)
        prefs.edit().putString(KEY_TILES, json).apply()
    }
}
