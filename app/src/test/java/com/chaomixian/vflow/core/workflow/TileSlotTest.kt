package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `TileSlot` 的纯函数语义（设计 §8.1）。
 *
 * 重点锁三件「改错了不报错、只静默变差」的事：
 * 1. **两池的池内槽号都是 0..19，看起来一样** —— 映射错一位会让开关型磁贴
 *    去读写执行池的槽，而**槽位合法、不报错**；
 * 2. **越界必须返回 `null`**，不能悄悄回落到执行池（那会让 40 号槽映射到 0 号，
 *    与 0 号磁贴抢同一个绑定）；
 * 3. **两个池的未绑定显示名必须不同** —— 设计 §4.7 的用户诉求就是「区分这两个池」，
 *    全写成 `vFlow Tile N` 等于没改。
 */
class TileSlotTest {

    // ── 1. 索引 ↔ (kind, slot) 往返 ─────────────────────────

    @Test
    fun `kindOf splits the 40 slots into two disjoint pools`() {
        assertEquals(TileKind.EXECUTE, TileSlot.kindOf(0))
        assertEquals(TileKind.EXECUTE, TileSlot.kindOf(19))
        assertEquals(TileKind.TOGGLE, TileSlot.kindOf(20))
        assertEquals(TileKind.TOGGLE, TileSlot.kindOf(39))
    }

    @Test
    fun `kindOf returns null outside 0_39 instead of falling back to EXECUTE`() {
        // ⚠️ 回落成 EXECUTE 会把「不存在的槽」当成执行池第 N 个 —— 静默。
        assertNull(TileSlot.kindOf(-1))
        assertNull(TileSlot.kindOf(40))
        assertNull(TileSlot.kindOf(100))
        assertNull(TileSlot.kindOf(Int.MAX_VALUE))
        assertNull(TileSlot.kindOf(Int.MIN_VALUE))
    }

    @Test
    fun `the two pools have the same slot range but different absolute indexes`() {
        // ★ 本用例锁的正是「两个池的 0..19 看起来一样」这件事。
        // ⚠️ 两个池的**池内槽号**本来就该相同（都是 0..19）—— 会完全相同的是
        //    「槽号」，不会相同的是「绝对索引」。用例要断言的是后者。
        assertEquals(0, TileSlot.indexInKind(0))          // 执行池第 0 个
        assertEquals(0, TileSlot.indexInKind(20))         // 开关池第 0 个
        assertEquals(19, TileSlot.indexInKind(19))
        assertEquals(19, TileSlot.indexInKind(39))
        assertNotEquals(
            "0 号槽与 20 号槽的**绝对索引**必须不同（它们只是池内槽号都是 0）",
            0,
            20
        )
        // 真正要防的错：`tileIndexOf` 把两池算到同一个绝对索引上
        assertNotEquals(
            TileSlot.tileIndexOf(TileKind.EXECUTE, 0),
            TileSlot.tileIndexOf(TileKind.TOGGLE, 0)
        )
        assertNotEquals(
            TileSlot.tileIndexOf(TileKind.EXECUTE, 19),
            TileSlot.tileIndexOf(TileKind.TOGGLE, 19)
        )
    }

    @Test
    fun `indexInKind and tileIndexOf are exact inverses`() {
        for (index in 0 until WorkflowTile.TILE_COUNT) {
            val kind = TileSlot.kindOf(index)!!
            val slot = TileSlot.indexInKind(index)!!
            assertEquals(
                "index=$index 往返后必须回到自己",
                index,
                TileSlot.tileIndexOf(kind, slot)!!
            )
        }
    }

    @Test
    fun `tileIndexOf rejects out-of-range slots`() {
        assertNull(TileSlot.tileIndexOf(TileKind.EXECUTE, -1))
        assertNull(TileSlot.tileIndexOf(TileKind.EXECUTE, 20))
        assertNull(TileSlot.tileIndexOf(TileKind.TOGGLE, 20))
        assertEquals(0, TileSlot.tileIndexOf(TileKind.EXECUTE, 0))
        assertEquals(19, TileSlot.tileIndexOf(TileKind.EXECUTE, 19))
        assertEquals(20, TileSlot.tileIndexOf(TileKind.TOGGLE, 0))
        assertEquals(39, TileSlot.tileIndexOf(TileKind.TOGGLE, 19))
    }

    @Test
    fun `the two pools do not overlap and cover exactly 40 slots`() {
        val all = TileKind.entries.flatMap { kind ->
            (0 until TileSlot.tileCountOf(kind)).map { TileSlot.tileIndexOf(kind, it)!! }
        }
        assertEquals(WorkflowTile.TILE_COUNT, all.size)
        assertEquals(
            "两池的绝对索引必须恰好是 0..39、不重叠不遗漏",
            (0 until WorkflowTile.TILE_COUNT).toList(),
            all.sorted()
        )
    }

    // ── 2. 显示名（§4.7）────────────────────────────────────

    @Test
    fun `displayName differs between the two pools`() {
        // ★ 反向锁：本次改动的**用户诉求**就是区分这两个池。
        //    若有人把两池都写成 "vFlow Tile N"，本用例必须变红。
        assertEquals("vFlow Execute 1", TileSlot.displayName(TileKind.EXECUTE, 0))
        assertEquals("vFlow Execute 20", TileSlot.displayName(TileKind.EXECUTE, 19))
        assertEquals("vFlow Toggle 1", TileSlot.displayName(TileKind.TOGGLE, 0))
        assertEquals("vFlow Toggle 20", TileSlot.displayName(TileKind.TOGGLE, 19))

        for (slot in 0 until 20) {
            assertNotEquals(
                "同一槽号在两池的显示名必须不同（slot=$slot）",
                TileSlot.displayName(TileKind.EXECUTE, slot),
                TileSlot.displayName(TileKind.TOGGLE, slot)
            )
        }
    }

    @Test
    fun `displayName numbering is 1-based while slot is 0-based`() {
        // ⚠️ manifest 里是 `vFlow Execute 1..20`（人看的），而 slot 是 0..19（代码用的）。
        //    少加这个 1 会让面板显示 `vFlow Execute 0`，而系统面板里是 `1` ——
        //    同一块磁贴在两处名字不同，用户以为是两个东西。
        assertEquals("vFlow Execute 1", TileSlot.displayName(TileKind.EXECUTE, 0))
        assertTrue(TileSlot.displayName(TileKind.EXECUTE, 19).endsWith(" 20"))
    }

    // ── 3. 类名（`TileRefreshNotifier` 与 manifest 共用）────

    @Test
    fun `serviceClassName keeps the legacy execute prefix untouched`() {
        // ⚠️⚠️ 执行池的类名**一个字都不能改** —— SystemUI 按 ComponentName 记住
        //      用户已添加的磁贴，改名会让它们全部消失。
        assertEquals(
            "com.chaomixian.vflow.ui.tile.WorkflowTileService0",
            TileSlot.serviceClassName(TileKind.EXECUTE, 0)
        )
        assertEquals(
            "com.chaomixian.vflow.ui.tile.WorkflowTileService19",
            TileSlot.serviceClassName(TileKind.EXECUTE, 19)
        )
    }

    @Test
    fun `serviceClassName gives the toggle pool its own class names`() {
        assertEquals(
            "com.chaomixian.vflow.ui.tile.WorkflowToggleTileService0",
            TileSlot.serviceClassName(TileKind.TOGGLE, 0)
        )
        assertEquals(
            "com.chaomixian.vflow.ui.tile.WorkflowToggleTileService19",
            TileSlot.serviceClassName(TileKind.TOGGLE, 19)
        )
    }

    @Test
    fun `serviceClassName never collides between the two pools`() {
        val names = TileKind.entries.flatMap { kind ->
            (0 until TileSlot.tileCountOf(kind)).map { TileSlot.serviceClassName(kind, it) }
        }
        assertEquals(WorkflowTile.TILE_COUNT, names.size)
        assertEquals("40 个类名必须两两不同", WorkflowTile.TILE_COUNT, names.toSet().size)
    }

    // ── 4. 一致性判据（防手工改 JSON 造出矛盾记录）──────────

    @Test
    fun `isConsistent detects a tile whose kind contradicts its index`() {
        assertTrue(TileSlot.isConsistent(WorkflowTile(0, "wf", TileKind.EXECUTE)))
        assertTrue(TileSlot.isConsistent(WorkflowTile(20, "wf", TileKind.TOGGLE)))
        // 手工改 JSON 塞进去的矛盾记录 —— 必须被判为不一致
        assertTrue(!TileSlot.isConsistent(WorkflowTile(0, "wf", TileKind.TOGGLE)))
        assertTrue(!TileSlot.isConsistent(WorkflowTile(39, "wf", TileKind.EXECUTE)))
        // 越界索引一律不一致（kindOf 返回 null）
        assertTrue(!TileSlot.isConsistent(WorkflowTile(40, "wf", TileKind.EXECUTE)))
    }

    @Test
    fun `offsetOf matches the raw constants`() {
        assertEquals(TileSlot.EXECUTE_INDEX_OFFSET, TileSlot.offsetOf(TileKind.EXECUTE))
        assertEquals(TileSlot.TOGGLE_INDEX_OFFSET, TileSlot.offsetOf(TileKind.TOGGLE))
        assertEquals(0, TileSlot.EXECUTE_INDEX_OFFSET)
        assertEquals(
            "开关池起点必须紧接执行池末尾（漏了会有一个槽落进真空）",
            WorkflowTile.EXECUTE_TILE_COUNT,
            TileSlot.TOGGLE_INDEX_OFFSET
        )
    }
}
