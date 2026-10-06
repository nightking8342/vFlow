package com.chaomixian.vflow.core.workflow

import androidx.annotation.StringRes
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowTile

/**
 * 两个磁贴池的**互斥判据** —— 本仓库**唯一**允许判「这一池接不接受这个工作流」的地方。
 *
 * ## 判据（用户 2026-10-06 定案）
 *
 * | 池 | 判据 |
 * |---|---|
 * | [TileKind.EXECUTE] | `!workflow.hasAutoTriggers()` |
 * | [TileKind.TOGGLE] | `workflow.hasAutoTriggers()` |
 *
 * 两池互补、**互斥**、穷尽：同一个工作流**不会**同时出现在两个池里。
 *
 * ⚠️⚠️ **「执行型不允许绑 auto 工作流」是强制的，不是「推荐」** —— 它要落在**三道闸**上：
 *
 * | # | 闸 | 位置 |
 * |---|---|---|
 * | 1 | 菜单项显隐 | `WorkflowListScreen` 的 `regularMenuActions` |
 * | 2 | 选择面板分段 | `WorkflowSelectionTarget` → `TileSelectionSheet` |
 * | 3 | Service 侧兜底 | `BaseExecuteTileService` / `BaseToggleTileService` 的 `onClick` |
 *
 * **三闸必须调同一处判据**（就是本文件）。任何一处自己写 `hasAutoTriggers()`，都会让
 * 「菜单项显示着、点了却被拒绝」这类不一致出现 —— 用户会认为功能坏了。
 *
 * ⚠️⚠️ **闸 3 不是冗余**：前两道判的是「**绑定的那一刻**」，而 `hasAutoTriggers()` 的结果
 * **会随用户编辑而变**。少了闸 3，一个「绑定时是手动型、后来加了定时触发」的工作流会
 * **继续按执行型跑**，而它的 `isEnabled` 开关在卡片上显示着、用户以为那个开关管用 ——
 * 实际磁贴每次点击都在**绕过它执行**。且**没有任何行为测试会因此变红**（只能靠源码扫描）。
 *
 * ⚠️ **判据不是 `hasManualTrigger()`**（那是既有缺陷）：编辑器新建工作流时**默认就带**一个
 * `vflow.trigger.manual`，用户之后再加自动触发器**不会**把它删掉 ⇒ 实践中多数自动化工作流
 * **同时**有 manual + auto，两个判据**碰巧都成立**、缺陷被掩盖。但
 * `WorkflowNormalizer.normalize` 在**已有任意触发器**时**不会**补手动触发器 ⇒
 * Agent 建的、或外部导入的**纯自动**工作流根本没有 manual trigger。
 */
object TileGate {

    /**
     * 该池是否接受这个工作流。
     *
     * ⚠️ 三道闸全部走这里；**不要**在调用点复写判据。
     */
    fun accepts(kind: TileKind, workflow: Workflow): Boolean = when (kind) {
        TileKind.EXECUTE -> !workflow.hasAutoTriggers()
        TileKind.TOGGLE -> workflow.hasAutoTriggers()
    }

    /**
     * 已绑定的磁贴是否**越界**（绑定时合法、后来工作流的触发器组成变了）。
     *
     * ⚠️ **两种情形不算越界**：
     * - **空槽**（`workflowId == null`）—— 它只是没绑，不该提示「请重新绑定」；
     * - **工作流已被删**（`workflow == null`）—— 既有代码把它显示成未绑定态，
     *   本改动**不改这个行为**：越界态专指「工作流还在、但类型不匹配」。
     *
     * ⚠️ `tile.kind` 可能为 `null`（Gson 不填 Kotlin 默认值，见 `WorkflowTile` 的 KDoc）
     * ⇒ 这里**再归一一次**，而不是直接 `accepts(tile.kind!!, …)`。虽然 `TileManager`
     * 已经在读取时归一过，但本类是**三道闸共用的判据**，不能依赖调用方先归一 ——
     * 一条绕过 `TileManager` 的构造路径（测试、将来的新写入点）就会让 `!!` 抛 NPE。
     */
    fun isOutOfKind(tile: WorkflowTile, workflow: Workflow?): Boolean {
        if (tile.workflowId == null || workflow == null) return false
        return !accepts(kindOf(tile), workflow)
    }

    /**
     * 取该磁贴的池（归一后**一定非空**）。
     *
     * ⚠️ 归一按**槽位**而非「一律 EXECUTE」—— 见 `TileFieldNormalizer` 的 KDoc。
     */
    fun kindOf(tile: WorkflowTile): TileKind =
        tile.kind ?: TileSlot.kindOf(tile.tileIndex) ?: TileKind.EXECUTE

    /**
     * 该池的标题（选择面板用）。
     *
     * ⚠️ 放在这里而不是散在两个 UI 调用点：池标题与下面两个文案映射**同源**，
     * 三处共用一张表，改动时不会漏掉任何一处。
     */
    @StringRes
    fun poolTitleRes(kind: TileKind): Int = when (kind) {
        TileKind.EXECUTE -> R.string.tile_execute_pool_title
        TileKind.TOGGLE -> R.string.tile_toggle_pool_title
    }

    /**
     * **绑定时**被拒的提示（闸 1/2 的兜底文案）。
     *
     * ⚠️ 与 [outOfKindMessageRes] **刻意分开** —— 一个发生在「绑定时」、一个发生在
     * 「已经绑了但条件变了」，**用户要做的事不同**（前者是换一池，后者是**重新**绑）。
     * 混用会让用户以为「我明明绑上过，怎么又说不行」。
     */
    @StringRes
    fun mismatchMessageRes(kind: TileKind): Int = when (kind) {
        TileKind.EXECUTE -> R.string.tile_kind_mismatch_execute
        TileKind.TOGGLE -> R.string.tile_kind_mismatch_toggle
    }

    /**
     * **越界态**的 subtitle（§4.7）。
     *
     * ⚠️ 越界是**可见**的（磁贴进 `STATE_UNAVAILABLE` + 这句 subtitle），
     * 而不是「点下去才发现没用」—— 静默会让用户以为磁贴在执行。
     */
    @StringRes
    fun outOfKindMessageRes(kind: TileKind): Int = when (kind) {
        TileKind.EXECUTE -> R.string.tile_out_of_kind_execute
        TileKind.TOGGLE -> R.string.tile_out_of_kind_toggle
    }
}
