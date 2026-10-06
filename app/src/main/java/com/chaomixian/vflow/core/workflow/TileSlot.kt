package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.WorkflowTile

/**
 * 磁贴**槽位**与「池 / 池内序号 / 绝对索引 / 类名 / 显示名」之间的换算（纯函数，零 Android 依赖）。
 *
 * ## 为什么要有这一层
 *
 * 两个池各有 0..19 的槽号，**看起来一样**。任何一处「按类名后缀反推池」的写法都会让
 * 开关型磁贴去执行工作流（或反过来），且**不报错**。故映射显式写死在 [EXECUTE_INDEX_OFFSET] /
 * [TOGGLE_INDEX_OFFSET]，并且**类名口径也只有这一处**（[serviceClassName]）——
 * manifest 的 `<service android:name>`、`TileRefreshNotifier` 的 `ComponentName`、
 * 以及运行时未绑定态的显示名都从本文件派生，脱节会静默失效。
 *
 * ## 命名口径（与 manifest 的 `android:label` 逐字一致）
 *
 * | kind | 未绑定时的名字 |
 * |---|---|
 * | [TileKind.EXECUTE] | `vFlow Execute N`（N = 1..20） |
 * | [TileKind.TOGGLE] | `vFlow Toggle N`（N = 1..20） |
 *
 * ⚠️ 系统「添加磁贴」面板显示的就是 manifest 的 `android:label`（那时磁贴还没绑定、
 * `onStartListening` 还没跑，改不了）⇒ 运行时未绑定态**必须**用同一套命名，
 * 否则「面板里叫 A、加进去变成 B」，用户会以为是两个不同的东西。
 *
 * ⚠️ 这两个名字**刻意不进字符串资源**：manifest 的 `android:label` 不能拼变量，
 * 走资源要 40 条 ×3 语言。故这里是**英文字面量**，与 manifest 一一对应（有源码扫描测试锁住）。
 */
object TileSlot {

    /** 执行池在绝对索引里的起点。 */
    const val EXECUTE_INDEX_OFFSET = 0

    /** 开关池在绝对索引里的起点。 */
    const val TOGGLE_INDEX_OFFSET = WorkflowTile.EXECUTE_TILE_COUNT

    /**
     * 两个池的**类名前缀**。
     *
     * ⚠️ 执行池的类名 `WorkflowTileServiceN` **不得改** —— SystemUI 按 `ComponentName`
     * 记住用户已添加到控制中心的磁贴，改名会让它们**全部消失**。
     */
    private const val EXECUTE_CLASS_PREFIX = "com.chaomixian.vflow.ui.tile.WorkflowTileService"
    private const val TOGGLE_CLASS_PREFIX = "com.chaomixian.vflow.ui.tile.WorkflowToggleTileService"

    /** [TileKind.EXECUTE] 的未绑定显示名前缀（与 manifest 的 label 逐字一致）。 */
    private const val EXECUTE_DISPLAY_PREFIX = "vFlow Execute"

    /** [TileKind.TOGGLE] 的未绑定显示名前缀（与 manifest 的 label 逐字一致）。 */
    private const val TOGGLE_DISPLAY_PREFIX = "vFlow Toggle"

    /** 绝对索引 → 所属的池；**越界返回 `null`**（不是回退到执行池）。 */
    fun kindOf(tileIndex: Int): TileKind? = when {
        tileIndex in EXECUTE_INDEX_OFFSET until TOGGLE_INDEX_OFFSET -> TileKind.EXECUTE
        tileIndex in TOGGLE_INDEX_OFFSET until WorkflowTile.TILE_COUNT -> TileKind.TOGGLE
        else -> null
    }

    /** 绝对索引 → 池内槽号（0..19）；**越界返回 `null`**。 */
    fun indexInKind(tileIndex: Int): Int? = when (kindOf(tileIndex)) {
        TileKind.EXECUTE -> tileIndex - EXECUTE_INDEX_OFFSET
        TileKind.TOGGLE -> tileIndex - TOGGLE_INDEX_OFFSET
        null -> null
    }

    /** (池, 池内槽号) → 绝对索引；槽号越界返回 `null`。 */
    fun tileIndexOf(kind: TileKind, slot: Int): Int? {
        val count = tileCountOf(kind)
        if (slot !in 0 until count) return null
        return offsetOf(kind) + slot
    }

    /** 该池的槽位数。 */
    fun tileCountOf(kind: TileKind): Int = when (kind) {
        TileKind.EXECUTE -> WorkflowTile.EXECUTE_TILE_COUNT
        TileKind.TOGGLE -> WorkflowTile.TOGGLE_TILE_COUNT
    }

    /** 该池在绝对索引里的起点。 */
    fun offsetOf(kind: TileKind): Int = when (kind) {
        TileKind.EXECUTE -> EXECUTE_INDEX_OFFSET
        TileKind.TOGGLE -> TOGGLE_INDEX_OFFSET
    }

    /**
     * 未绑定磁贴的显示名（`vFlow Execute 1` / `vFlow Toggle 1`）。
     *
     * ⚠️ **编号从 1 起**（与 manifest 的 `vFlow Execute 1..20` 对齐），而入参 [slot] 是 0-based。
     */
    fun displayName(kind: TileKind, slot: Int): String {
        val prefix = when (kind) {
            TileKind.EXECUTE -> EXECUTE_DISPLAY_PREFIX
            TileKind.TOGGLE -> TOGGLE_DISPLAY_PREFIX
        }
        return "$prefix ${slot + 1}"
    }

    /**
     * 该槽对应的 `TileService` **全限定类名**，供 `ComponentName(context, className)` 与
     * manifest 的 `<service android:name>` 使用。
     *
     * ⚠️ `TileRefreshNotifier` 用它拼 `ComponentName`，而 manifest 里写的是
     * `.ui.tile.WorkflowTileServiceN`（相对形式）—— 两者**必须**指向同一个类，
     * 否则 `requestListeningState` 传的是一个不存在的组件 ⇒ **磁贴永不刷新**（静默）。
     */
    fun serviceClassName(kind: TileKind, slot: Int): String {
        val prefix = when (kind) {
            TileKind.EXECUTE -> EXECUTE_CLASS_PREFIX
            TileKind.TOGGLE -> TOGGLE_CLASS_PREFIX
        }
        return "$prefix$slot"
    }

    /** 已绑定记录里的 `kind` 与其绝对索引是否自洽（防手工改 JSON 造出矛盾的记录）。 */
    fun isConsistent(tile: WorkflowTile): Boolean = kindOf(tile.tileIndex) == tile.kind
}

/**
 * `WorkflowTile` 的**字段归一化**（纯函数）。
 *
 * ## 为什么必须有这一层
 *
 * ⚠️⚠️ **Gson 不填 Kotlin 的默认值** —— 它用 `Unsafe.allocateInstance` 构造对象、
 * **绕过构造函数**，故 `kind: TileKind = TileKind.EXECUTE` 对**缺键的旧记录**
 * 完全不起作用：读出的是 `null`（枚举字段的 Java 默认值）。
 *
 * ⇒ 每一条**进入内存**的 `WorkflowTile` 都必须过这里一次。**特别是 `getAllTiles()`
 * 的返回值** —— 它是所有读取路径（磁贴 service、选择面板、`remove*` 系列）的共同上游；
 * 只归一化其中一处会出现「service 看着对、面板里是空的」这类**静默不一致**。
 *
 * ⚠️ **归一化按 `tileIndex` 而非 `EXECUTE` 兜底**：槽位 20..39 属于开关池，
 * 一律回落 `EXECUTE` 会把开关池的槽判成执行池（`TileGate.isOutOfKind` 与
 * `removeTileByWorkflowIdInKind` 都按 kind 判 ⇒ 行为全错，且不报错）。
 * 已存量的数据只有 0..19，按槽位补出来恰好是 `EXECUTE`，与改动前逐字相同。
 *
 * ⚠️ 这是本仓库对 Gson 默认值的**既有口径**（先例：`WorkflowManager.kt:298`
 * 的 `WorkflowLogLevel.fromStoredValue(...)` 与 `WorkflowReentryBehavior.fromStoredValue(...)`
 * —— 都是「读出来之后显式归一」，不是「在模型上放默认值」）。
 */
object TileFieldNormalizer {

    /**
     * 把 [tile] 归一成「`kind` 一定非空且与槽位自洽」的记录。
     *
     * ⚠️ **`kind` 与槽位矛盾时以槽位为准**（而不是保留用户的 `kind`）：
     * `tileIndex` 是**物理事实**（它决定 SystemUI 绑的是哪个 service），
     * 以它为准才不会出现「数据说这是开关池、实际却是执行型组件」的错位。
     * 手工改过 JSON 的记录会因此被静默纠正 —— 这是刻意的。
     */
    fun normalize(tile: WorkflowTile): WorkflowTile {
        val derived = kindOf(tile.tileIndex)
        if (tile.kind == derived && derived != null) return tile
        return tile.copy(kind = derived ?: TileKind.EXECUTE)
    }

    fun normalizeAll(tiles: List<WorkflowTile>): List<WorkflowTile> = tiles.map(::normalize)

    private fun kindOf(tileIndex: Int): TileKind? = TileSlot.kindOf(tileIndex)
}
