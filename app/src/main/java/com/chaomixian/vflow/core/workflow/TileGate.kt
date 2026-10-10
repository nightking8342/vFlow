package com.chaomixian.vflow.core.workflow

import androidx.annotation.StringRes
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowTile

/**
 * 两个磁贴池的**准入判据** —— 本仓库**唯一**允许判「这一池接不接受这个工作流」的地方。
 *
 * ## 判据（**2026-10-10 修订**，用户定案）
 *
 * | 池 | 判据 |
 * |---|---|
 * | [TileKind.EXECUTE] | `workflow.hasManualTrigger()` |
 * | [TileKind.TOGGLE] | `workflow.hasAutoTriggers()` |
 *
 * ## ⚠️⚠️ 两池**不再互斥**（2026-10-10 的**行为变更**）
 *
 * | 触发器组成 | EXECUTE | TOGGLE |
 * |---|---|---|
 * | 无触发器 | ❌ | ❌ |
 * | 仅手动 | ✅ | ❌ |
 * | 仅自动（Agent 建 / 外部导入） | ❌ | ✅ |
 * | **手动 + 自动** | ✅ | **✅（本次新增）** |
 *
 * ⇒ 同一个工作流**可以同时**出现在两个池里（两个槽位、互不影响）。
 * `TileManager.removeTileByWorkflowIdInKind` 按 `(workflowId, kind)` 删，正是为这一格准备的。
 *
 * ⚠️ 「无触发器」那一行**实践中不会出现** —— 三条读路径都会过
 * `WorkflowNormalizer.normalize`（`ensureTrigger` 默认 true），零触发器的工作流会被补一个
 * 手动触发器；`saveWorkflow` 同样会归一。保留这一行是为了说明判据**是诚实的**：
 * 没有手动触发器就是不给执行磁贴，不做「没有触发器 ⇒ 放行」的兜底。
 *
 * ## 为什么执行池改成看「手动触发器」
 *
 * 执行磁贴的语义 = **卡片上的 ▶ 按钮**（手动点火一次），而卡片上的 ▶ **本来就不看
 * `isEnabled`** —— 一个同时挂着定时触发器的工作流，用户照样可以手动跑它。
 *
 * 此前用 `!hasAutoTriggers()` 的后果是：编辑器新建的工作流**默认就带手动触发器**，
 * 用户再加一个定时触发器之后，它就**再也绑不到执行磁贴**了 —— 而那恰恰是最常见的形态。
 *
 * ⚠️ 这**推翻了**一条旧论证，是**用户明确要求的取舍**，不是遗漏：旧版担心
 * 「自动工作流绑在执行磁贴上 ⇒ 点磁贴会绕过 `isEnabled` 开关」。新判据接受这一点，
 * 理由是它与卡片 ▶ 的行为**逐字一致**；开关磁贴仍然是控制自动触发的那个入口。
 *
 * ⚠️⚠️ **判据是强制的，不是「推荐」** —— 它要落在**三道闸**上：
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
 * ⚠️⚠️ **闸 3 不是冗余**：前两道判的是「**绑定的那一刻**」，而触发器组成**会随用户编辑而变**。
 * 少了闸 3，一个「绑定时有手动触发器、后来把它删掉」的工作流会**继续按执行型跑** ——
 * 那时它只剩自动触发器，用户以为磁贴是手动点火口，实际点一下等于跳过 `isEnabled` 直接跑。
 * 且**没有任何行为测试会因此变红**（只能靠源码扫描）。
 *
 * ⚠️ 两侧越界**都只剩一种可能**，故引导文案是确定的：
 * - EXECUTE 越界 ⇒ 该工作流**没有**手动触发器；而它至少有一个触发器（归一化保证）
 *   ⇒ **必然**只剩自动触发器 ⇒ 引导去**开关池**是对的。
 * - TOGGLE 越界 ⇒ 没有自动触发器 ⇒ 必然有手动触发器 ⇒ 引导去**执行池**是对的。
 */
object TileGate {

    /**
     * 该池是否接受这个工作流。
     *
     * ⚠️ 三道闸全部走这里；**不要**在调用点复写判据。
     */
    fun accepts(kind: TileKind, workflow: Workflow): Boolean = when (kind) {
        // ⚠️ 不是 `!hasAutoTriggers()` —— 见类注释「为什么执行池改成看手动触发器」。
        TileKind.EXECUTE -> workflow.hasManualTrigger()
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
