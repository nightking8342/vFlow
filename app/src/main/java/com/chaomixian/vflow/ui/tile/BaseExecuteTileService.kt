package com.chaomixian.vflow.ui.tile

import android.content.Intent
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.model.TileKind

/**
 * **执行池**磁贴（`WorkflowTileService0..19`）：点一下把绑定的工作流跑一次。
 *
 * ⚠️⚠️ **类名一个字都不能改** —— SystemUI 按 `ComponentName` 记住用户已添加到
 * 控制中心的磁贴，改名 = 那个组件不存在了 ⇒ 用户已添加的磁贴**全部消失**。
 * 本池的 20 个类名是 `WorkflowTileServiceN`（无后缀、无前缀），沿用至今。
 */
abstract class BaseExecuteTileService : BaseWorkflowTileService() {

    override fun tileKind(): TileKind = TileKind.EXECUTE

    override fun onClick() {
        super.onClick()

        val bound = boundWorkflow()
        if (bound == null) {
            // 未绑定（或工作流已被删）⇒ 打开 App
            openApp()
            return
        }

        val (workflow, _) = bound

        // ⚠️⚠️ 闸 3（§4.6）：前两道闸（菜单显隐 / 面板分段）判的是「**绑定的那一刻**」，
        //       而 `hasAutoTriggers()` 的结果**会随用户编辑而变**。
        //       少了这一道，一个「绑定时是手动型、后来加了定时触发」的工作流会
        //       **继续按执行型跑**，而它的 `isEnabled` 开关在卡片上显示着、
        //       用户以为那个开关管用 —— 实际磁贴每次点击都在绕过它执行。
        //       ⚠️ 且**没有任何行为测试会因此变红**，只有源码扫描能锁住。
        if (!TileGate.accepts(TileKind.EXECUTE, workflow)) {
            // 表现刻意设计成**可见的**（§4.6/§4.7）：磁贴此刻是 UNAVAILABLE +
            // subtitle 说明去哪一池。点击只打开 App，**不执行、也不切换**。
            DebugLogger.i(
                TAG,
                "执行型磁贴拒绝执行：工作流「${workflow.name}」含自动触发器。" +
                    "磁贴本身已处于越界态，此处只打开 App。"
            )
            toast(getString(R.string.tile_out_of_kind_execute))
            openApp()
            return
        }

        executeWorkflow(workflow.id, workflow.name)
    }

    /**
     * 与「主屏快捷方式」走**同一条执行链路**（`ShortcutExecutorActivity`）——
     * 那条路已经处理了「静默执行、失败提示、触发器上下文」等既有语义。
     *
     * ⚠️ **不在这里判权限** —— 与卡片上的「立即执行」保持一致，交给执行链路自己报错。
     * 磁贴只负责「把请求转过去」，多判一遍会让两条路径的提示不一致。
     */
    private fun executeWorkflow(workflowId: String, workflowName: String) {
        val intent = Intent(applicationContext, com.chaomixian.vflow.ui.common.ShortcutExecutorActivity::class.java).apply {
            action = com.chaomixian.vflow.ui.common.ShortcutExecutorActivity.ACTION_EXECUTE_WORKFLOW
            putExtra(com.chaomixian.vflow.ui.common.ShortcutExecutorActivity.EXTRA_WORKFLOW_ID, workflowId)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            startActivity(intent)
        } catch (e: Exception) {
            DebugLogger.e(TAG, "执行型磁贴启动工作流失败：$workflowName", e)
        }
    }

    private companion object {
        const val TAG = "vFlowTile"
    }
}
