package com.chaomixian.vflow.ui.tile

import com.chaomixian.vflow.core.workflow.TileSlot

/**
 * **开关池**的 20 个磁贴（绝对槽位 20–39，池内槽号 0–19）。
 *
 * ⚠️ **必须是新增的类与新增的 manifest 条目** —— 执行池那 20 个
 * （`WorkflowTileServiceN`）的名字**不能动**（SystemUI 按 `ComponentName` 记
 * 已添加的磁贴，改名会让它们全部消失）。本池是新能力，没有历史包袱。
 *
 * ⚠️⚠️ **槽位号必须写成 `TileSlot.TOGGLE_INDEX_OFFSET + N`，不能写裸 `N`** ——
 * 两个池的池内槽号都是 0..19，**看起来一样**；写裸 `N` 会让开关型磁贴去读写
 * 执行池的槽位（点一下开关磁贴 = 执行某个工作流，或反之），且**不报错**。
 * `BaseWorkflowTileService.onStartListening` 里的一致性检查会在真机上抓到它，
 * 但正确写法应当在写的那一刻就避免。
 */

class WorkflowToggleTileService0 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 0
}

class WorkflowToggleTileService1 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 1
}

class WorkflowToggleTileService2 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 2
}

class WorkflowToggleTileService3 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 3
}

class WorkflowToggleTileService4 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 4
}

class WorkflowToggleTileService5 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 5
}

class WorkflowToggleTileService6 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 6
}

class WorkflowToggleTileService7 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 7
}

class WorkflowToggleTileService8 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 8
}

class WorkflowToggleTileService9 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 9
}

class WorkflowToggleTileService10 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 10
}

class WorkflowToggleTileService11 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 11
}

class WorkflowToggleTileService12 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 12
}

class WorkflowToggleTileService13 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 13
}

class WorkflowToggleTileService14 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 14
}

class WorkflowToggleTileService15 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 15
}

class WorkflowToggleTileService16 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 16
}

class WorkflowToggleTileService17 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 17
}

class WorkflowToggleTileService18 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 18
}

class WorkflowToggleTileService19 : BaseToggleTileService() {
    override fun getTileIndex(): Int = TileSlot.TOGGLE_INDEX_OFFSET + 19
}
