package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager

/**
 * 「Xposed 触发器**此刻就绪**了吗」的**判定纯函数层**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.1。
 *
 * ## ⚠️⚠️ 本文件存在的理由：一条**因定案而产生的义务**
 *
 * §6.1 定案：权限判据**保持 L1（实时）**、**不**改成 `L0 ∪ L1`。
 * 代价是 —— **通道断时不会禁用工作流**，于是用户看到的是
 * 「**权限全绿 + 触发器不工作**」这一格。
 *
 * §6.1 原文逐字写着「**可见性由新状态位承担** …… 上面那条『可见状态』**不是可选项**」，
 * 并建议至少做进 `TriggerService`（它与「缺权限会禁用工作流」是**同一个位置**）。
 * 本文件与 [XposedDiagnostics] 一起兑现那条义务。
 *
 * ## ⚠️ 三条硬约束（本文件一个字都不许违反）
 *
 * | # | 约束 | 本文件的落实 |
 * |---|---|---|
 * | 1 | **改判据不改** | 本文件**不**碰 [XposedCapability]；它仍判 L1 |
 * | 2 | **不要用「禁用工作流」把用户引过来** | 本文件是**纯函数**，没有 `Context`、没有 `WorkflowManager`，**根本写不进去盘** |
 * | 3 | [XposedState] 的既有语义（两组状态位独立、`Channel` 不依赖 `Framework`）不动 | 本文件只**读**`result.channel`，不重新判定 |
 *
 * ## 与同目录三个既有文件的分工（刻意不重叠）
 *
 * | 文件 | 职责 |
 * |---|---|
 * | [XposedState] | 判定**状态位**（`Framework` × `Channel`） |
 * | [XposedCapability] | **权限**判据（L1 实时） |
 * | **本文件** | **触发器就绪度**（状态位 + 用户配了什么 ⇒ 要不要提示） |
 * | [XposedDiagnostics] | **文案**（资源 ID 映射） |
 */
object XposedReadiness {

    /**
     * 通道是否**尚未就绪** ⇒ 依赖它的触发器此刻不会工作。
     *
     * 判据 = `Channel != READY`，三个分支的**用户处置完全不同**（由
     * [XposedState.tapAction] 分流，本类不重复表达）：
     *  - `DISCONNECTED`：连都没连上
     *  - `NOT_MOUNTED`：连上了，但 system 没在 `runningTargets` 里（多半还没重启）
     *  - `READY`：正常
     *
     * ⚠️ 本方法是**纯判定**，不产出任何文案 —— 文案在 [XposedDiagnostics]。
     */
    fun needsChannelNotice(result: XposedState.Result): Boolean =
        result.channel != XposedState.Channel.READY

    /**
     * 从**全部**工作流里挑出「会因为通道未就绪而不工作」的那些。
     *
     * 判据（三条同时成立）：
     *  1. `workflow.isEnabled`；
     *  2. 该工作流有**至少一个触发器**（`workflow.triggers`，不是 `allSteps`）；
     *  3. 那个触发器的模块声明了 [PermissionManager.XPOSED_HOOK]。
     *
     * ## ⚠️ 为什么只看 `triggers` 而不看 `steps`
     *
     * 本提示回答的是「**触发器为什么不触发**」。通道断时，配在 `steps` 里的
     * Xposed 动作模块会走**执行期显式失败**（v2.0 §6.2：「③ 的失败形态是**显式**的
     * —— 用户看得到步骤报错」），不需要这里再报一次。
     * 而 `steps` 里挂 Xposed 模块的工作流，其**触发器**多半是别的东西
     * （手动 / 定时），把它也算进来会让提示出现在**根本没坏**的场景里。
     *
     * ## ⚠️ 为什么不直接读全局 [com.chaomixian.vflow.core.module.ModuleRegistry]
     *
     * 依赖注册表意味着测试要先把整个注册表初始化好 —— 脆弱且慢。
     * 注入 `permissionsOfModule` 让单测可直接喂一张 map。
     * 调用点传 `ModuleRegistry::getModule` 的派生（见 `TriggerService`）。
     *
     * ## ⚠️ 为什么比 `id` 而不比 `Permission` 实例
     *
     * `Permission` 是 `@Parcelize data class`，但注册表里拿到的实例与常量
     * **不保证同一**（Parcelable 往返会造新实例）。比 `id` 是稳定判据，
     * 与全仓其它按 `permission.id` 比较的写法一致
     * （如 `TriggerService.shouldTreatAsTransientAccessibilityState`）。
     *
     * @param workflows **全部**工作流（是否 `isEnabled` 由本方法自己筛）
     * @param permissionsOfModule moduleId → 该模块声明的权限（**传 `null` step 调用即可**：
     *   `BaseModule.getRequiredPermissions(null)` 返回静态 `requiredPermissions`，忽略 step）
     * @return 命中的工作流；空 = 不需要提示
     */
    fun selectAffectedWorkflows(
        workflows: List<Workflow>,
        permissionsOfModule: (String) -> List<Permission>,
    ): List<Workflow> =
        workflows.filter { workflow ->
            workflow.isEnabled && workflow.triggers.any { trigger ->
                permissionsOfModule(trigger.moduleId)
                    .any { it.id == PermissionManager.XPOSED_HOOK.id }
            }
        }
}
