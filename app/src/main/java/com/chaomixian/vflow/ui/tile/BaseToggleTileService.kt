package com.chaomixian.vflow.ui.tile

import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.TriggerExecutionCoordinator
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * **开关池**磁贴（`WorkflowToggleTileService0..19`）：点一下开/关绑定的工作流的**自动触发**。
 *
 * ⚠️⚠️ **这是一个真开关**，与执行池有两条定义性差别：
 * 1. `Tile.state` 读 `isEnabled` 做**双态**（执行池恒 `INACTIVE`）；
 * 2. manifest 里**保留** `TOGGLEABLE_TILE`（无障碍播报成「开关」是**对的**），
 *    执行池则**必须去掉**它（§3.1）。
 *
 * ## 写入照抄列表页范式（§2.5，两条都不能少）
 *
 * 1. **`wasEnabledBeforePermissionsLost = false`** —— 不清的话，
 *    `WorkflowPermissionRecovery.recoverEligibleWorkflows` 会把用户**刚从磁贴关掉**的
 *    工作流在下次权限恢复时**自动重开**，而用户不知道。
 * 2. **走 `workflowManager.saveWorkflow`，绝不直接写 prefs** ——
 *    `saveWorkflow` 内部调 `TriggerServiceProxy.notifyWorkflowChanged`，
 *    那才是让 `TriggerService` 挂/卸触发器的入口。绕过它 = **界面变了但触发器还挂着**
 *    （表现是「关了开关，工作流照样定时跑」）。
 */
abstract class BaseToggleTileService : BaseWorkflowTileService() {

    override fun tileKind(): TileKind = TileKind.TOGGLE

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        scope.coroutineContext[kotlinx.coroutines.Job]?.cancel()
    }

    override fun onClick() {
        super.onClick()

        val bound = boundWorkflow()
        if (bound == null) {
            openApp()
            return
        }

        val (workflow, _) = bound

        // ⚠️⚠️ 闸 3（§4.6）：与执行池对称。绑定时有 auto、后来被用户删光了触发器
        //       ⇒ 这个开关已经没有可开关的东西（点了也只是改一个不会被任何东西读的
        //       `isEnabled`），而用户会以为自己在控制某个触发。
        if (!TileGate.accepts(TileKind.TOGGLE, workflow)) {
            DebugLogger.i(
                TAG,
                "开关型磁贴拒绝切换：工作流「${workflow.name}」已无自动触发器。" +
                    "磁贴本身已处于越界态，此处只打开 App。"
            )
            toast(getString(R.string.tile_out_of_kind_toggle))
            openApp()
            return
        }

        toggleWorkflowEnabled(workflow)
    }

    /**
     * 翻转 `isEnabled`，并照列表页范式做**权限恢复 + 回弹**。
     *
     * ⚠️ **关闭路径必须同步完成**（不进协程）—— `TileService` 在 `onClick` 返回后
     * 随时可能被系统回收；关闭是**纯写盘**，没有异步成分，同步做完最安全。
     * 开启路径要跑权限恢复（suspend + 跨进程），必须进协程。
     *
     * ⚠️⚠️ **刷新的时机很关键，曾经踩过一个坑**：早先版本在 `saveWorkflow` **之后**
     * 立刻调 `refreshTile()`，于是「数据已写、但 `updateTileState` 里读到的还是旧值」
     * 这类时序问题会被**下一次** `onStartListening` 的日志暴露出来。
     * 现在把每条路径的**终态**都打出来（`toggle 后` 那条日志），
     * 真机上能不能对上就一目了然。
     */
    private fun toggleWorkflowEnabled(workflow: Workflow) {
        val appContext = applicationContext
        val enable = !workflow.isEnabled
        val manager = WorkflowManager(appContext)

        DebugLogger.d(
            TAG,
            "开关磁贴点击：${workflow.name} isEnabled=${workflow.isEnabled} ⇒ 目标=$enable"
        )

        manager.saveWorkflow(
            workflow.copy(
                isEnabled = enable,
                // ⚠️ 见类注释第 1 条：这一行是「关掉之后不被权限恢复悄悄重开」的唯一依据
                wasEnabledBeforePermissionsLost = false
            )
        )

        // 回读确认（⚠️ 只用于诊断；回读不一致**不改判失败** —— 与 `SimDataSwitch` 同款纪律
        // 那是「切换异步、期间不一致」的正常现象，在这里也不该当失败处理）
        val readBack = manager.getWorkflow(workflow.id)?.isEnabled
        DebugLogger.d(TAG, "开关磁贴写入后回读：${workflow.name} isEnabled=$readBack")

        if (!enable) {
            // 关闭：立即刷新磁贴（`requestListeningState` 会再走一次 onStartListening）
            refreshTile()
            return
        }

        // 开启：异步补权限（与列表页逐字一致）
        scope.launch {
            val latest = manager.getWorkflow(workflow.id) ?: return@launch
            val remaining = withContext(Dispatchers.IO) {
                TriggerExecutionCoordinator.recoverMissingPermissions(appContext, latest)
            }
            if (remaining.isEmpty()) {
                // ⚠️ 权限齐全也要刷！这条 `return@launch` 曾经让**开启路径永远不刷新** ——
                //    「关了能亮、开了不亮」的不对称正是这么来的：关闭路径在同步分支里
                //    调了 `refreshTile()`，而开启路径走协程，权限齐全时**直接 return**
                //    把刷新漏掉了。表现是「点一下灭、然后永远不亮」。
                DebugLogger.d(TAG, "开关磁贴开启成功（权限齐全）：${workflow.name}")
                withContext(Dispatchers.Main) { refreshTile() }
                return@launch
            }

            // 仍缺权限 ⇒ 回弹为关闭，并把「是权限恢复想开它」记在
            // `wasEnabledBeforePermissionsLost = true` 上（与列表页一致）
            val current = manager.getWorkflow(workflow.id) ?: return@launch
            if (!current.isEnabled) return@launch
            manager.saveWorkflow(
                current.copy(
                    isEnabled = false,
                    wasEnabledBeforePermissionsLost = true
                )
            )
            DebugLogger.w(
                TAG,
                "开关型磁贴开启失败：工作流「${workflow.name}」仍缺权限，已回弹为关闭"
            )
            withContext(Dispatchers.Main) {
                toast(getString(R.string.tile_toggle_failed_permission))
                // ⚠️ 回弹后同样要重绘 —— 这次 `onClick` 已经返回很久了，
                //    不在 `TileService` 的生命周期里，但 `qsTile` 仍拿得到
                //    （`TileService` 由系统持有），且**必须**在主线程改。
                refreshTile()
            }
        }
    }

    /**
     * 主动让 SystemUI 重新读一次状态。
     *
     * ⚠️ 这里**不能**用 `TileSlot` 的批量刷新（`TileRefreshNotifier`）—— 那是给
     * 「App 内改动」用的、带 500ms 去抖，而用户此刻正盯着磁贴看，去抖会让它
     * **看起来没反应**（面板还开着、状态还是旧的，直到用户关掉再拉开）。
     * 而 `requestListeningState(this)` 只针对**本磁贴**，代价可以忽略。
     */
    private fun refreshTile() {
        // ⚠️⚠️ **必须同时做两件事**，缺一个就会出现「灭一下又亮回来 / 永远不亮」。
        //
        // 1. **就地同步重绘**（`updateTileState()`）：`onClick` 跑在 `TileService`
        //    自己的生命周期里，此刻直接改 `qsTile` 是**立即**生效的 —— 这是唯一
        //    「点完就变」的路径。
        // 2. **再请求一次 `onStartListening`**：把我方内存里刚算出的状态与 SystemUI
        //    真正持有的一致化（`requestListeningState` 是**异步**的，靠它单打独斗
        //    会让用户先看到旧状态、几百毫秒后才变，观感就是「灭一下又亮」）。
        //
        // ⚠️ 反过来只做 1、不做 2 也不行：`onClick` 结束、面板还在时 SystemUI
        //    可能用**它自己缓存**的 state 再画一次，把刚改的覆盖回去。
        try {
            updateTileState()
        } catch (e: Exception) {
            DebugLogger.d(TAG, "开关型磁贴就地重绘失败（不影响开关本身）", e)
        }
        try {
            android.service.quicksettings.TileService.requestListeningState(
                applicationContext,
                android.content.ComponentName(applicationContext, javaClass.name)
            )
        } catch (e: Exception) {
            DebugLogger.d(TAG, "开关型磁贴刷新失败（不影响开关本身）", e)
        }
    }

    private companion object {
        const val TAG = "vFlowTile"
    }
}
