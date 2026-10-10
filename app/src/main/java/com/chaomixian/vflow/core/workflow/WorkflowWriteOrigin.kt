package com.chaomixian.vflow.core.workflow

/**
 * 一次工作流写入的**来源**（fork 新增）。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md` §3.3。
 *
 * ## 它解决的是什么问题
 *
 * `vflow.trigger.workflow_toggle` 要监听「工作流开关被**显式**改变」，
 * 而**不能**把系统自动改的也算进去（权限丢失禁用、权限恢复重开、权限回弹）。
 *
 * 这条判据**不能**靠既有字段拼出来。文档 §3.3 用一组
 * **字段状态逐字节相同**的反例证伪了「借用 `wasEnabledBeforePermissionsLost`」的方案：
 *
 * | 场景 | isEnabled | oldWabpl | newWabpl | 应否触发 |
 * |---|---|---|---|---|
 * | 系统自动重开（权限恢复） | false→true | `true` | `false` | ❌ 不触发 |
 * | 工作流步骤（`vflow.logic.set_workflow_enabled`）启用同一个 | false→true | `true` | `false` | ✅ **要触发** |
 *
 * 两行的字段状态完全相同 ⇒ 必须引入**显式来源标记**。
 *
 * ## ⚠️ 默认值方向是**功能性的**，不是风格
 *
 * `WorkflowManager.saveWorkflow` 的 `origin` 参数默认值取 [EXPLICIT]：
 * 将来新增程序性写入点若**忘了**标 [AUTOMATIC]，后果是「多触发几次」
 * —— 用户**看得见**（工作流莫名其妙跑了一次），能被报上来；
 * 反过来（默认 `AUTOMATIC`）则是**静默失效**（该触发的不触发），
 * 而本仓库已记录过至少三次同类事故的形态。
 *
 * ⇒ **不要**为了「少打扰用户」把默认值改成 [AUTOMATIC]。
 */
enum class WorkflowWriteOrigin {
    /** 有人**显式要求**这次变更：用户（列表 / 磁贴 / 远程 API / AI）或工作流步骤（`vflow.logic.set_workflow_enabled`）。 */
    EXPLICIT,

    /** **系统自动**改的：权限丢失禁用、权限恢复重开、权限回弹。 */
    AUTOMATIC,
}
