package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.workflow.WorkflowWriteOrigin
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.module.triggers.WorkflowToggleTriggerData
import com.chaomixian.vflow.core.workflow.module.triggers.WorkflowToggleTriggerModule
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `vflow.trigger.workflow_toggle` 触发器处理器。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md`（§3 判定机制 / §2.5 派发时机）。
 *
 * ## ⚠️⚠️ 为什么必须继承 [BaseTriggerHandler]，**不能**用 `ListeningTriggerHandler`
 *
 * `ListeningTriggerHandler` 把 `start` / `stop` / `addTrigger` / `removeTrigger`
 * 四个方法全部 `final override` 了，而它只在「空↔非空」的边界触发 ——
 * 会导致「已有 1 个触发器时再加第 2 个」条件永远下不去（本仓库既有教训，
 * 见 `LogcatTriggerHandler` / `ActivityChangedTriggerHandler` / `BroadcastTriggerHandler`
 * 的类注释）。
 *
 * ## ⚠️ 事件源**不在本类内部**
 *
 * 它监听的是 App 自己写的 `Workflow.isEnabled` —— 没有可注册的系统事件源。
 * 形态照抄 `key_event`：由 `TriggerService.onStartCommand` 的
 * `ACTION_WORKFLOW_CHANGED` 分支**直接调进** [onWorkflowSaved]。
 *
 * ## ⚠️ 判定规则（三个条件缺一不可）
 *
 * `origin == EXPLICIT && oldIsEnabled != null && oldIsEnabled != newIsEnabled`
 *
 * - `AUTOMATIC` 过滤掉「权限丢失禁用 / 权限恢复重开 / 权限回弹」——
 *   不过滤的后果是「用户每次打开 App 都可能看到我没碰开关工作流自己跑了」；
 * - `oldIsEnabled == null` 表示保存前该工作流不存在（新建 / 导入 / API 创建），
 *   不是一次「开关变化」；
 * - 同值 ⇒ 只是一次「又保存了一次」，不是开关变化。
 *
 * ⚠️ **不要**改成借用 `wasEnabledBeforePermissionsLost` 拼判据 ——
 * 见 [WorkflowWriteOrigin] 类注释里的反例（字段逐字节相同）。
 */
class WorkflowToggleTriggerHandler : BaseTriggerHandler() {

    companion object {
        private const val TAG = "WorkflowToggleTriggerHandler"

        /**
         * 纯函数：这次写入该不该派发。
         *
         * 独立成纯函数以便纯 JVM 单测（本仓库「判定逻辑提成纯函数」的既有做法）。
         */
        internal fun shouldDispatch(
            origin: WorkflowWriteOrigin,
            oldIsEnabled: Boolean?,
            newIsEnabled: Boolean,
        ): Boolean =
            origin == WorkflowWriteOrigin.EXPLICIT &&
                oldIsEnabled != null &&
                oldIsEnabled != newIsEnabled

        /** 纯函数：`state` 过滤。`any` / 未知值 = 不限制。 */
        internal fun matchesState(state: String?, newIsEnabled: Boolean): Boolean = when (state) {
            WorkflowToggleTriggerModule.VALUE_ENABLED -> newIsEnabled
            WorkflowToggleTriggerModule.VALUE_DISABLED -> !newIsEnabled
            else -> true
        }
    }

    /** 当前登记的触发器。每次增删都重建，因此用写时复制容器。 */
    private val activeTriggers = CopyOnWriteArrayList<TriggerSpec>()

    override fun start(context: Context) {
        super.start(context)
        DebugLogger.d(TAG, "工作流开关触发器处理器已启动")
    }

    override fun stop(context: Context) {
        activeTriggers.clear()
        super.stop(context)
    }

    override fun addTrigger(context: Context, trigger: TriggerSpec) {
        activeTriggers.removeAll { it.triggerId == trigger.triggerId }
        activeTriggers.add(trigger)
        DebugLogger.d(TAG, "addTrigger: ${trigger.triggerId}，当前共 ${activeTriggers.size} 个")
    }

    override fun removeTrigger(context: Context, triggerId: String) {
        activeTriggers.removeAll { it.triggerId == triggerId }
        DebugLogger.d(TAG, "removeTrigger: $triggerId，当前共 ${activeTriggers.size} 个")
    }

    /**
     * 由 `TriggerService.onStartCommand` 的 `ACTION_WORKFLOW_CHANGED` 分支直接调用。
     *
     * ⚠️ 调用点必须排在 `handleWorkflowChanged(...)` **之后** —— 它负责增删该工作流
     * 自己的触发器：「启用 Y 而 Y 自带本触发器」要能立刻响应；「关闭 Y」时 Y 的触发器
     * 刚被移除 ⇒ 不会自触发。
     *
     * @param newWorkflow 此刻盘上的最新工作流
     * @param oldIsEnabled 保存**前**的启用态；`null` = 保存前该工作流不存在
     * @param origin 这次写入的来源（显式 / 系统自动）
     */
    fun onWorkflowSaved(
        context: Context,
        newWorkflow: Workflow,
        oldIsEnabled: Boolean?,
        origin: WorkflowWriteOrigin,
    ) {
        if (!shouldDispatch(origin, oldIsEnabled, newWorkflow.isEnabled)) return

        val inputs = WorkflowToggleTriggerModule().getInputs()
        activeTriggers.forEach { trigger ->
            if (trigger.parameters[WorkflowToggleTriggerModule.PARAM_WORKFLOW_ID] != newWorkflow.id) {
                return@forEach
            }
            val configured = inputs.normalizeEnumValue(
                WorkflowToggleTriggerModule.PARAM_STATE,
                trigger.parameters[WorkflowToggleTriggerModule.PARAM_STATE] as? String,
                WorkflowToggleTriggerModule.VALUE_ANY,
            )
            if (!matchesState(configured, newWorkflow.isEnabled)) return@forEach

            DebugLogger.i(
                TAG,
                "触发工作流 '${trigger.workflowName}'（目标 ${newWorkflow.name} " +
                    "${if (newWorkflow.isEnabled) "已启用" else "已关闭"}）",
            )
            executeTrigger(
                context = context,
                trigger = trigger,
                triggerData = WorkflowToggleTriggerData(
                    workflowId = newWorkflow.id,
                    workflowName = newWorkflow.name,
                    isEnabled = newWorkflow.isEnabled,
                    wasEnabled = oldIsEnabled!!,
                ),
            )
        }
    }

    /**
     * 仅登记触发器、**不触碰 Context** —— 供纯 JVM 单测用。
     *
     * **这个方法的唯一用途是测试**（与 `BroadcastTriggerHandler.addTriggerForTest` 同款）。
     */
    internal fun addTriggerForTest(trigger: TriggerSpec) {
        activeTriggers.removeAll { it.triggerId == trigger.triggerId }
        activeTriggers.add(trigger)
    }

    /** 当前登记数，供单测断言。 */
    internal fun listeningTriggerCount(): Int = activeTriggers.size
}
