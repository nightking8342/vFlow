// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/TileScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.AbstractPrefsScope
import com.chaomixian.vflow.core.backup.BackupEnvironment
import com.chaomixian.vflow.core.backup.BackupPrefsSpec
import com.chaomixian.vflow.core.backup.LogLevel
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * 备份/恢复**快捷磁贴的分配**（`vflow_tiles` / `tile_list`）。
 *
 * ## 键 → 收 / 不收 → 原因
 *
 * ### 收（1 键）
 *
 * | 键 | 类型 | 原因 |
 * |---|---|---|
 * | `tile_list` | S | 磁贴定义（`[{tileIndex, workflowId}]` 的 JSON）。`dependsOn = ["workflows"]` ⇒ 工作流先就位，`workflowId` 才不至于指向不存在的工作流 |
 *
 * ### 不收
 *
 * 该 prefs 只有这一个键，无其它。
 *
 * ## ⚠️ MERGE 的专门语义
 *
 * `tile_list` 是**一个键装一个数组**：若 MERGE 也整键覆盖，本地独有的磁贴会消失
 * —— 那不是 MERGE 的含义（用户选合并就是不想丢本地的东西）。
 * 故本 scope 覆写 [mergeValue]：
 * - `MERGE` ⇒ 解析两份 JSON，**按 `tileIndex` 合并**（备份优先，本地独有的 tileIndex 保留）。
 * - `REPLACE` ⇒ 走基类的默认行为（整键替换，备份里没有的磁贴消失）。
 * - 任一 JSON 解析失败 ⇒ 退化为「备份优先的整键替换」并记一条 W 日志（**不整体失败**）。
 *
 * ## 元素形状（已核实）
 *
 * `List<WorkflowTile>`，元素是 `{"tileIndex": Int, "workflowId": String?}`
 * （`WorkflowTile.kt:7-14`；`workflowId` 可空 ⇒ 「已分配索引但尚未绑定工作流」是合法状态）。
 *
 * ⚠️ **合并按 `tileIndex` 而非 `workflowId`** —— 后者可为 null、
 * 且同一工作流理论上可出现在多个磁贴（`TileManager.saveTile` 只按 `tileIndex` 去重）。
 *
 * ⚠️ `TILE_COUNT = 20` 是**磁贴槽位总数**（Manifest 里声明了 20 个 `WorkflowTileServiceN`），
 * **不是** `tile_list` 数组的长度 —— 未分配的槽**不在** `tile_list` 里。
 */
class TileScope : AbstractPrefsScope() {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.CONFIG
    override val sensitive: Boolean = false
    override val defaultIncluded: Boolean = true
    override val importOrder: Int = 20

    /** 磁贴引用工作流 id ⇒ 必须等工作流就位。 */
    override val dependsOn: List<String> = listOf("workflows")

    override val scopeTag: String = TAG

    override val specs: List<BackupPrefsSpec> = listOf(
        BackupPrefsSpec(PREFS_NAME, exactKeys = setOf(KEY_TILES))
    )

    /**
     * MERGE 时对 `tile_list` 做按 `tileIndex` 的并集。见类 KDoc。
     *
     * ⚠️ 走**文本层合并**而不是「先解析成 `WorkflowTile` 再写回」：
     * 后者会让本文件依赖 `core.workflow.model.WorkflowTile`（带 `@Parcelize`，
     * 依赖 android）⇒ 本包的纯 JVM 纯度扫描立刻变红。
     * 文本层合并也天然容忍元素里**将来新增的字段**（原样保留，不丢）。
     */
    override fun mergeValue(
        env: BackupEnvironment,
        prefsName: String,
        key: String,
        incoming: Any?,
        local: Any?
    ): Any? {
        if (prefsName != PREFS_NAME || key != KEY_TILES) return incoming
        val incomingJson = incoming as? String ?: return incoming
        val localJson = local as? String ?: return incoming

        val incomingArray = parseArrayOrNull(incomingJson)
        val localArray = parseArrayOrNull(localJson)
        if (incomingArray == null || localArray == null) {
            // 退化：备份优先的整键替换。**不整体失败** —— 一份被手工改坏的
            // tile_list 不该让整个「没有问题的文件」导入失败。
            env.log(
                LogLevel.W, scopeTag,
                "tile_list 有一侧不是合法 JSON 数组，MERGE 退化为整键替换"
            )
            return incoming
        }

        val incomingByIndex = indexByTileIndex(incomingArray)
        val localIndexes = indexByTileIndex(localArray)
        if (incomingByIndex == null || localIndexes == null) {
            // ⚠️ 元素里读不到 `tileIndex`（R8 改名 / 结构被手工改过）⇒ 退化为整键替换。
            //    不能「当作本地独有」处理 —— 那会把每一个元素都复制一份。
            env.log(
                LogLevel.W, scopeTag,
                "tile_list 的元素读不到 tileIndex，MERGE 退化为整键替换"
            )
            return incoming
        }

        val localOnly = localArray
            .mapNotNull { element -> element.asJsonObjectOrNull() }
            .filter { participant ->
                val index = participant.tileIndexOrNull() ?: return@filter false
                index !in incomingByIndex
            }

        val merged = JsonArray()
        // 顺序：先本机独有的（保持本地顺序），再备份的（保持备份顺序）。
        // ⚠️ 顺序本身不参与语义（`TileManager` 恒按 `tileIndex` 查），但固定下来
        //    让往返可逐字断言。
        localOnly.forEach { merged.add(it) }
        incomingArray.forEach { merged.add(it) }
        return merged.toString()
    }

    private fun parseArrayOrNull(json: String): JsonArray? =
        runCatching { JsonParser.parseString(json) }
            .getOrNull()
            ?.takeIf { it.isJsonArray }
            ?.asJsonArray

    /**
     * `tileIndex` → 下标集合。
     *
     * @return **null** 表示「至少一个元素读不到 `tileIndex`」——
     *   与「集合为空」是两件事，调用方必须区别对待（见 [mergeValue]）。
     */
    private fun indexByTileIndex(array: JsonArray): Set<Int>? {
        val out = mutableSetOf<Int>()
        for (element in array) {
            val index = element.asJsonObjectOrNull()?.tileIndexOrNull() ?: return null
            out += index
        }
        return out
    }

    private fun JsonElement.asJsonObjectOrNull() =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun com.google.gson.JsonObject.tileIndexOrNull(): Int? {
        val primitive = get(KEY_TILE_INDEX)?.takeIf { it.isJsonPrimitive } as? JsonPrimitive
            ?: return null
        return if (primitive.isNumber) primitive.asInt else null
    }

    companion object {
        const val ID = "tiles"

        private const val TAG = "TileScope"

        /** 与 `TileManager` 的 `getSharedPreferences("vflow_tiles", …)` 逐字一致。 */
        internal const val PREFS_NAME = "vflow_tiles"

        /** 与 `TileManager.KEY_TILES` 逐字一致。 */
        internal const val KEY_TILES = "tile_list"

        /** `WorkflowTile.tileIndex` 的 wire 名（Gson 用运行时字段名，见 `TileManager`）。 */
        private const val KEY_TILE_INDEX = "tileIndex"
    }
}
