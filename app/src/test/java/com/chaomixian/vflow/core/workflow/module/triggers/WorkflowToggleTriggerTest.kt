package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.workflow.WorkflowWriteOrigin
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.WorkflowToggleTriggerHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工作流开关触发器的判定单测 + **四组源码扫描锚定**。
 *
 * 设计文档：`docs/fork/workflow-toggle-design.md`（§3.3 判定机制 / §11.2 验证）。
 *
 * ## ⚠️ 为什么必须有源码扫描那一半
 *
 * 本批的失败模式**全是静默的**：
 *
 * | 失效 | 症状 | 有行为测试会红吗 |
 * |---|---|---|
 * | 少一行 `register(...)` | 能选能配、后台永不触发 | ❌（注册发生在 `TriggerService` 里，纯 JVM 起不来） |
 * | 派发调用排在 `handleWorkflowChanged` **之前** | 关闭自己时把自己再跑一次 / 启用时漏一次 | ❌ |
 * | 漏标一处 `AUTOMATIC` | 权限恢复 / 自动禁用也触发（「我没碰开关它自己跑了」） | ❌ |
 * | Proxy 没填 `oldIsEnabled` / `writeOrigin` | 字段恒为默认值 ⇒ 判定恒不派发 | ❌（纯函数测试全绿） |
 * | `saveWorkflow` 的 `origin` 默认值被改成 `AUTOMATIC` | 该触发的不触发 | ❌ |
 *
 * ⇒ 本仓库已**三次**踩过「纯函数全绿但集成点缺失」
 * （`CoreDexFingerprint` 的 13 个单测全绿、`CoreLauncher` 漏调
 * `recordLaunchedDexFingerprint`；`XposedDiagnostics.messageFor` 零生产调用点）。
 * 这道防线按同一形态建，且**每条都做过反证**（见交付说明的「反证记录」）。
 *
 * ⚠️ **全部先剥注释再断言** —— 本批的 KDoc 里到处写着这些符号，
 * 不剥注释的话「把真实调用删掉、只留注释」照样绿。
 *
 * ⚠️ **断言不能锚在改造前就存在的标识符上**（会恒真）。第 ④ 组锚的两个表达式、
 * 第 ② 组的 `onWorkflowSaved(`、第 ③ 组的 `WorkflowWriteOrigin.AUTOMATIC`
 * 都是**本批新增**的。
 */
class WorkflowToggleTriggerTest {

    // ═══════════════════════════════════════════════════════════════════
    // 一、判定纯函数 shouldDispatch（设计文档 §3.3 的核心）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `explicit toggle dispatches in both directions`() {
        // 开 → 关
        assertTrue(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = true, newIsEnabled = false,
            )
        )
        // 关 → 开
        assertTrue(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = false, newIsEnabled = true,
            )
        )
    }

    @Test
    fun `automatic toggle never dispatches in either direction`() {
        // ⚠️ 这是本设计存在的全部意义：系统自动改的（权限丢失禁用 / 权限恢复重开 / 回弹）
        //    必须被过滤掉，否则「用户每次打开 App 都可能看到我没碰开关工作流自己跑了」。
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.AUTOMATIC, oldIsEnabled = true, newIsEnabled = false,
            )
        )
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.AUTOMATIC, oldIsEnabled = false, newIsEnabled = true,
            )
        )
    }

    @Test
    fun `automatic is filtered even when nothing else differs`() {
        // 反向锁：把 AUTOMATIC 换成 EXPLICIT 时同一条输入**必须**为 true ——
        // 防的是「shouldDispatch 恒 false」这种「所有过滤测试都通过」的退化。
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.AUTOMATIC, oldIsEnabled = false, newIsEnabled = true,
            )
        )
        assertTrue(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = false, newIsEnabled = true,
            )
        )
    }

    @Test
    fun `null old state never dispatches`() {
        // null = 保存前该工作流不存在（新建 / 导入 / API 创建）⇒ 不是一次「开关变化」
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = null, newIsEnabled = true,
            )
        )
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = null, newIsEnabled = false,
            )
        )
    }

    @Test
    fun `same value never dispatches`() {
        // 只是一次「又保存了一次」，不是开关变化
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = true, newIsEnabled = true,
            )
        )
        assertFalse(
            WorkflowToggleTriggerHandler.shouldDispatch(
                WorkflowWriteOrigin.EXPLICIT, oldIsEnabled = false, newIsEnabled = false,
            )
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 二、state 过滤
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `state filter`() {
        val enabled = WorkflowToggleTriggerModule.VALUE_ENABLED
        val disabled = WorkflowToggleTriggerModule.VALUE_DISABLED
        val any = WorkflowToggleTriggerModule.VALUE_ANY

        assertTrue(WorkflowToggleTriggerHandler.matchesState(enabled, newIsEnabled = true))
        assertFalse(WorkflowToggleTriggerHandler.matchesState(enabled, newIsEnabled = false))

        assertTrue(WorkflowToggleTriggerHandler.matchesState(disabled, newIsEnabled = false))
        assertFalse(WorkflowToggleTriggerHandler.matchesState(disabled, newIsEnabled = true))

        assertTrue(WorkflowToggleTriggerHandler.matchesState(any, newIsEnabled = true))
        assertTrue(WorkflowToggleTriggerHandler.matchesState(any, newIsEnabled = false))

        // null / 未知值 = 不限制（与「留空 = 任意」的既有惯例一致）
        assertTrue(WorkflowToggleTriggerHandler.matchesState(null, newIsEnabled = true))
        assertTrue(WorkflowToggleTriggerHandler.matchesState("bogus", newIsEnabled = false))
    }

    // ═══════════════════════════════════════════════════════════════════
    // 三、枚举归一化
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `state enum normalizes stable english values and falls back on unknown`() {
        val inputs = WorkflowToggleTriggerModule().getInputs()

        assertEquals(
            WorkflowToggleTriggerModule.VALUE_ENABLED,
            inputs.normalizeEnumValue(
                WorkflowToggleTriggerModule.PARAM_STATE,
                WorkflowToggleTriggerModule.VALUE_ENABLED,
                WorkflowToggleTriggerModule.VALUE_ANY,
            ),
        )
        assertEquals(
            WorkflowToggleTriggerModule.VALUE_DISABLED,
            inputs.normalizeEnumValue(
                WorkflowToggleTriggerModule.PARAM_STATE,
                WorkflowToggleTriggerModule.VALUE_DISABLED,
                WorkflowToggleTriggerModule.VALUE_ANY,
            ),
        )
        // null ⇒ 回落默认档
        assertEquals(
            WorkflowToggleTriggerModule.VALUE_ANY,
            inputs.normalizeEnumValue(
                WorkflowToggleTriggerModule.PARAM_STATE,
                null,
                WorkflowToggleTriggerModule.VALUE_ANY,
            ),
        )
        // ⚠️ 非空未知值**原样透出**（`normalizeEnumValue` 不做白名单校验）——
        //    `matchesState` 的 `else` 分支正是为它准备的。
        assertEquals(
            "bogus",
            inputs.normalizeEnumValue(
                WorkflowToggleTriggerModule.PARAM_STATE,
                "bogus",
                WorkflowToggleTriggerModule.VALUE_ANY,
            ),
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    // 四、触发器登记（纯 JVM 可测的那一半）
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `addTrigger keeps the registry consistent`() {
        val handler = WorkflowToggleTriggerHandler()
        val workflow = Workflow(id = "w1", name = "W1")
        val step = ActionStep(
            moduleId = WorkflowToggleTriggerModule().id,
            parameters = mapOf(WorkflowToggleTriggerModule.PARAM_WORKFLOW_ID to "target"),
        )

        handler.addTriggerForTest(TriggerSpec(workflow, step))
        assertEquals(1, handler.listeningTriggerCount())

        // 同一个 triggerId 重复添加只保留一份（addTrigger 先 removeAll 再 add）
        handler.addTriggerForTest(TriggerSpec(workflow, step))
        assertEquals(1, handler.listeningTriggerCount())

        // 另一个触发器（不同步骤 id）⇒ 两条并存
        handler.addTriggerForTest(
            TriggerSpec(workflow, step.copy(id = "another-step")),
        )
        assertEquals(2, handler.listeningTriggerCount())
    }

    // ═══════════════════════════════════════════════════════════════════
    // 五、四组源码扫描锚定（设计文档 §11.2 必做）
    // ═══════════════════════════════════════════════════════════════════

    private val moduleRegistryPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"
    private val handlerRegistryPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/module/triggers/handlers/TriggerHandlerRegistry.kt"
    private val triggerServicePath =
        "src/main/java/com/chaomixian/vflow/services/TriggerService.kt"
    private val proxyPath =
        "src/main/java/com/chaomixian/vflow/services/TriggerServiceProxy.kt"
    private val workflowManagerPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/WorkflowManager.kt"
    private val permissionRecoveryPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/WorkflowPermissionRecovery.kt"
    private val listRoutePath =
        "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"
    private val tileServicePath =
        "src/main/java/com/chaomixian/vflow/ui/tile/BaseToggleTileService.kt"

    // ── 组 ① 两个 Registry 各一行注册 ──────────────────────────────

    @Test
    fun `group1 the trigger module is registered in ModuleRegistry`() {
        val src = SourceScan.stripped(moduleRegistryPath)

        assertTrue(
            "ModuleRegistry 里没有注册 WorkflowToggleTriggerModule —— 编辑器里选不到该触发器",
            src.contains("register(WorkflowToggleTriggerModule(), context)"),
        )
        assertTrue(
            "ModuleRegistry 里没有注册 SetWorkflowEnabledModule —— 编辑器里选不到该动作模块",
            src.contains("register(SetWorkflowEnabledModule(), context)"),
        )
        // 防空转：文件没被搬走 / 剥注释没过火
        val count = SourceScan.countOccurrences(src, "register(")
        assertTrue("ModuleRegistry 里的 register( 只有 $count 次，断言可能在空转", count >= 100)
    }

    @Test
    fun `group1 the handler is registered in TriggerHandlerRegistry`() {
        val src = SourceScan.stripped(handlerRegistryPath)

        assertTrue(
            "TriggerHandlerRegistry 里没有注册 WorkflowToggleTriggerHandler —— " +
                "触发器会「能选能配、后台永不触发」",
            src.contains("register(WorkflowToggleTriggerModule().id) { WorkflowToggleTriggerHandler() }"),
        )
        assertTrue(
            "TriggerHandlerRegistry 的 register( 少于 10 次，断言可能在空转",
            SourceScan.countOccurrences(src, "register(") >= 10,
        )
    }

    // ── 组 ② 派发调用在 handleWorkflowChanged 之后 ────────────────

    @Test
    fun `group2 dispatch comes after handleWorkflowChanged inside the changed branch`() {
        val src = SourceScan.stripped(triggerServicePath)

        val branchStart = src.indexOf("ACTION_WORKFLOW_CHANGED ->")
        val handled = src.indexOf("handleWorkflowChanged(latestWorkflow, delta.oldTriggerRefs)")
        val dispatched = src.indexOf("onWorkflowSaved(")
        val branchEnd = src.indexOf("ACTION_WORKFLOW_REMOVED ->")

        assertTrue("找不到 ACTION_WORKFLOW_CHANGED 分支", branchStart >= 0)
        assertTrue("找不到 handleWorkflowChanged 调用", handled >= 0)
        assertTrue("找不到 onWorkflowSaved 派发调用（工作流开关触发器不会工作）", dispatched >= 0)
        assertTrue("找不到 ACTION_WORKFLOW_REMOVED 分支（无法界定分支范围）", branchEnd >= 0)

        // ⚠️ 顺序是**功能性的**，不只是风格：
        //    「启用 Y 而 Y 自带开关触发器」要能立刻响应（放前面会漏掉这一次）；
        //    「关闭 Y」时 Y 的触发器刚被移除 ⇒ 不会把刚关掉的 Y 再跑一次。
        assertTrue(
            "派发必须落在 ACTION_WORKFLOW_CHANGED 分支内、且在 handleWorkflowChanged **之后**" +
                "（实际下标：branch=$branchStart handled=$handled dispatch=$dispatched end=$branchEnd）",
            branchStart < handled && handled < dispatched && dispatched < branchEnd,
        )
    }

    // ── 组 ③ 5 处 AUTOMATIC 标记逐处存在 ─────────────────────────

    @Test
    fun `group3 automatic markers exist in all five programmatic write sites`() {
        val marker = "WorkflowWriteOrigin.AUTOMATIC"

        // 1) TriggerService.recoverWorkflowPermissionsAndApplyState（缺权限自动禁用）
        val triggerService = SourceScan.stripped(triggerServicePath)
        val recoverBody = SourceScan.functionBody(
            triggerService, "private suspend fun recoverWorkflowPermissionsAndApplyState(",
        )
        assertTrue("找不到 recoverWorkflowPermissionsAndApplyState 的函数体", recoverBody != null)
        assertTrue(
            "缺权限自动禁用那处没标 AUTOMATIC —— 权限恢复/禁用会误触发开关触发器",
            recoverBody!!.contains(marker),
        )

        // 2) TriggerService.disableWorkflowsMissingCorePermissions（Core 不可用批量暂停）
        val coreBody = SourceScan.functionBody(
            triggerService, "private fun disableWorkflowsMissingCorePermissions()",
        )
        assertTrue("找不到 disableWorkflowsMissingCorePermissions 的函数体", coreBody != null)
        assertTrue(
            "Core 不可用批量暂停那处没标 AUTOMATIC",
            coreBody!!.contains(marker),
        )

        // ⚠️ 反向锁：TriggerService 里 AUTOMATIC **恰好 2 处**。
        //    另外两处 saveWorkflow（清 wasEnabledBeforePermissionsLost）**刻意不标** ——
        //    它们不改 isEnabled，由判定规则的状态相等分支天然过滤；
        //    顺手标上会掩盖真实来源（设计文档 §1.3）。
        val triggerServiceCount = SourceScan.countOccurrences(triggerService, marker)
        assertEquals(
            "TriggerService 里 AUTOMATIC 应恰好 2 处（缺权限禁用 + Core 暂停），" +
                "多标/少标都说明标记点与设计文档 §3.1 的清单不符",
            2, triggerServiceCount,
        )

        // 3) WorkflowPermissionRecovery.recoverEligibleWorkflows（权限恢复自动重开）
        val recovery = SourceScan.stripped(permissionRecoveryPath)
        val recoveryBody = SourceScan.functionBody(recovery, "fun recoverEligibleWorkflows(")
        assertTrue("找不到 recoverEligibleWorkflows 的函数体", recoveryBody != null)
        assertTrue(
            "权限恢复自动重开那处没标 AUTOMATIC —— 用户每次进 App 都可能看到工作流自己跑了",
            recoveryBody!!.contains(marker),
        )

        // 4) WorkflowListRoute 的权限回弹（onToggleEnabled 内）
        val listRoute = SourceScan.stripped(listRoutePath)
        val toggleBody = SourceScan.functionBody(listRoute, "onToggleEnabled = { workflow, enabled ->")
        assertTrue("找不到 onToggleEnabled 的 lambda 体", toggleBody != null)
        assertTrue(
            "列表页权限回弹那处没标 AUTOMATIC",
            toggleBody!!.contains(marker),
        )

        // 5) BaseToggleTileService.toggleWorkflowEnabled 的权限回弹
        val tile = SourceScan.stripped(tileServicePath)
        val tileBody = SourceScan.functionBody(tile, "private fun toggleWorkflowEnabled(")
        assertTrue("找不到 toggleWorkflowEnabled 的函数体", tileBody != null)
        assertTrue(
            "磁贴权限回弹那处没标 AUTOMATIC",
            tileBody!!.contains(marker),
        )

        // 总数：5 个文件合计 ≥ 5 处
        val total = listOf(
            triggerService, recovery, listRoute, tile,
        ).sumOf { SourceScan.countOccurrences(it, marker) }
        assertTrue("AUTOMATIC 标记合计只有 $total 处（应 ≥ 5）", total >= 5)
    }

    @Test
    fun `group3 the origin parameter default is EXPLICIT`() {
        // ⚠️ 默认值方向是**功能性的**：忘标记 ⇒ 多触发（用户可见，能报上来）；
        //    反过来（默认 AUTOMATIC）是**静默失效**。设计文档 §3.3 定案。
        val src = SourceScan.stripped(workflowManagerPath)

        assertTrue(
            "saveWorkflow 的 origin 参数默认值必须是 EXPLICIT",
            src.contains("origin: WorkflowWriteOrigin = WorkflowWriteOrigin.EXPLICIT"),
        )
        // 防空转：确认剥注释后仍能看到 saveWorkflow 本体
        assertTrue(
            "WorkflowManager 里没有 saveWorkflow(，断言可能在空转",
            src.contains("fun saveWorkflow("),
        )
    }

    // ── 组 ④ Proxy 真的填了那 2 个字段 ───────────────────────────

    @Test
    fun `group4 the proxy fills both new delta fields`() {
        val src = SourceScan.stripped(proxyPath)

        assertTrue(
            "TriggerServiceProxy 没有填 oldIsEnabled —— 开关触发器永远判不出「变化前」的状态",
            src.contains("oldIsEnabled = oldWorkflow?.isEnabled"),
        )
        assertTrue(
            "TriggerServiceProxy 没有填 writeOrigin —— 自动写入会被当成显式写入",
            src.contains("writeOrigin = origin"),
        )
        // 防空转
        assertTrue(
            "TriggerServiceProxy 里没有 notifyWorkflowChanged(，断言可能在空转",
            src.contains("fun notifyWorkflowChanged("),
        )
    }
}
