package com.chaomixian.vflow.ui.tile

/**
 * **执行池**的 20 个磁贴（槽位 0–19）。
 *
 * ⚠️⚠️ **类名 `WorkflowTileServiceN` 一个字都不能改** —— SystemUI 按 `ComponentName`
 * 记住用户已添加到控制中心的磁贴，改名 = 那个组件不存在了 ⇒ 用户已添加的磁贴
 * **全部消失**，而 App 侧不会收到任何提示。开关池刻意用**新类名**
 * （`WorkflowToggleTileServiceN`，见 `WorkflowToggleTileServices.kt`），
 * 正是为了不碰这 20 个。
 *
 * ⚠️ 实现已由 `BaseWorkflowTileService` 下沉到 `BaseExecuteTileService`
 * （2026-10-06）—— 基类不再带 `onClick`，因为两个池的点击语义完全不同。
 * **本文件的类名、槽位号一个都没动**，只是父类换了。
 */

class WorkflowTileService0 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 0
}

class WorkflowTileService1 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 1
}

class WorkflowTileService2 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 2
}

class WorkflowTileService3 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 3
}

class WorkflowTileService4 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 4
}

class WorkflowTileService5 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 5
}

class WorkflowTileService6 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 6
}

class WorkflowTileService7 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 7
}

class WorkflowTileService8 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 8
}

class WorkflowTileService9 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 9
}

class WorkflowTileService10 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 10
}

class WorkflowTileService11 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 11
}

class WorkflowTileService12 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 12
}

class WorkflowTileService13 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 13
}

class WorkflowTileService14 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 14
}

class WorkflowTileService15 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 15
}

class WorkflowTileService16 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 16
}

class WorkflowTileService17 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 17
}

class WorkflowTileService18 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 18
}

class WorkflowTileService19 : BaseExecuteTileService() {
    override fun getTileIndex(): Int = 19
}
