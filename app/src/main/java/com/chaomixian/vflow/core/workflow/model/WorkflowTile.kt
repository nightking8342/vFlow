package com.chaomixian.vflow.core.workflow.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * 快捷设置磁贴的一条分配记录。
 *
 * ## 字段来源与兼容性
 *
 * - [tileIndex] 是**绝对索引** 0..39（0–19 = 执行池，20–39 = 开关池）。
 * - [kind] 是 2026-10-06 新增的（磁贴拆成执行 / 开关两池）。
 *
 * ⚠️⚠️ **[kind] 可空且默认 `null`，这是实测逼出来的形状，不要「顺手」改回非空默认值**：
 *
 * Gson 用 `Unsafe.allocateInstance` 构造对象、**绕过 Kotlin 构造函数** ⇒
 * 写成 `kind: TileKind = TileKind.EXECUTE` 时，缺键的旧记录反序列化出来是
 * **`null`**（枚举字段的 Java 默认值），**不是** `EXECUTE`。
 * 更糟的是：`null` 赋给 Kotlin 的**非空** `val` 后，**第一次读取**才抛
 * `NullPointerException`，而 `TileManager.getAllTiles()` 的 `catch (e: Exception)`
 * 会把它**吞成 `emptyList()`** —— 表现是「用户 20 个磁贴绑定全部消失」且**没有任何报错**。
 *
 * 本仓库对同类问题的既有解法是**显式归一化**，不是在模型上放默认值
 * （先例：`WorkflowManager.kt:298` 的 `WorkflowLogLevel.fromStoredValue(...)`、
 * 同处的 `WorkflowReentryBehavior.fromStoredValue(...)`）。⇒ 磁贴侧走同一条口径：
 * 读出来之后由 `TileFieldNormalizer.normalize(...)` 按**槽位**归一（`null` ⇒
 * `TileSlot.kindOf(tileIndex) ?: EXECUTE`）。有 `TileKindBackwardCompatTest` 锁住这个前提。
 *
 * ⚠️ 已存量的数据**只有执行池的 20 条**（那时还没有开关池），按槽位补出来的
 * 结果恰好也是 `EXECUTE` ⇒ 与改动前的语义逐字相同，**零迁移**。
 *
 * ⚠️ **`@Parcelize` 加字段不破坏兼容** —— 本类事实上只在进程内传递
 * （`TileSelectionItem` 是替身），跨版本 Parcel 兼容不是问题。
 *
 * ⚠️ **字段名即 wire 名**（Gson 走反射，无 `@SerializedName`）——
 * `core/backup/scopes/TileScope.kt` 是**文本层**合并、`TileScopeTest` 有一条断言
 * 把「导出的键集合」钉死在这里，改字段名会让那条断言变红（那是它**正常工作**的表现）。
 */
@Parcelize
data class WorkflowTile(
    val tileIndex: Int,
    val workflowId: String? = null,
    val kind: TileKind? = null,
) : Parcelable {
    companion object {
        /** 执行池槽位数（`WorkflowTileService0..19`）。 */
        const val EXECUTE_TILE_COUNT = 20

        /** 开关池槽位数（`WorkflowToggleTileService0..19`）。 */
        const val TOGGLE_TILE_COUNT = 20

        /**
         * 槽位总数。
         *
         * ⚠️ 这个常量由 [TileKind] 决定，**不是** `tile_list` 数组的长度
         * （未分配的槽不在那份 JSON 里）。
         * ⚠️ 改它必须同时改 `TileManager.getAllTilesWithEmpty()` 的范围 ——
         * 漏改的表现是「选择面板只显示 20 行、后 20 个槽**永远选不到**」。
         */
        const val TILE_COUNT = EXECUTE_TILE_COUNT + TOGGLE_TILE_COUNT
    }
}
