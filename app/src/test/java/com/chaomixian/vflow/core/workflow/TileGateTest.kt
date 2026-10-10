package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两池**准入判据**的逐格验证（设计 §4.6 / §8.1）。
 *
 * ⚠️⚠️ 这是三道闸（菜单 / 面板 / service）**共用的唯一判据**。
 * 任何一处自己写 `hasAutoTriggers()` / `hasManualTrigger()`，都会让
 * 「菜单项显示着、点了却被拒绝」出现。
 * 故本文件逐格锁死四个组合，外加两条反向锁。
 *
 * ⚠️ **2026-10-10 行为变更**：执行池的判据由 `!hasAutoTriggers()` 改为
 * `hasManualTrigger()`（用户定案）⇒ 两池**不再互斥**，「手动 + 自动」的工作流同时进两池。
 * 下面几条用例就是这次变更的机器化保证。
 */
class TileGateTest {

    private val manualTrigger = ActionStep(
        moduleId = MANUAL_TRIGGER_ID,
        parameters = emptyMap(),
        id = "t-manual",
    )
    private val autoTrigger = ActionStep(
        moduleId = "vflow.trigger.time",
        parameters = emptyMap(),
        id = "t-auto",
    )

    private fun workflow(
        id: String = "wf",
        triggers: List<ActionStep> = emptyList(),
        isEnabled: Boolean = true,
    ) = Workflow(
        id = id,
        name = "W-$id",
        triggers = triggers,
        isEnabled = isEnabled,
    )

    // ── 准入判据逐格 ────────────────────────────────────────

    @Test
    fun `EXECUTE accepts any workflow with a manual trigger`() {
        assertTrue(TileGate.accepts(TileKind.EXECUTE, workflow(triggers = listOf(manualTrigger))))
        assertTrue(
            "★ 2026-10-10：手动 + 自动并存时**也**能进执行池（本次变更的核心）",
            TileGate.accepts(TileKind.EXECUTE, workflow(triggers = listOf(manualTrigger, autoTrigger)))
        )
    }

    @Test
    fun `EXECUTE rejects a workflow without a manual trigger`() {
        assertFalse(TileGate.accepts(TileKind.EXECUTE, workflow(triggers = listOf(autoTrigger))))
        assertFalse(
            "无触发器也不行 —— 判据是诚实的，不做「没有触发器 ⇒ 放行」的兜底" +
                "（实践中不会出现：三条读路径的归一化都会补一个手动触发器）",
            TileGate.accepts(TileKind.EXECUTE, workflow(triggers = emptyList()))
        )
    }

    @Test
    fun `EXECUTE is not gated by isManualOnly`() {
        // ★ 反向锁：`isManualOnly()`（= 有手动且**无**自动）正是**被推翻的旧口径**。
        //    用它（或等价的 `!hasAutoTriggers()`）会把手动 + 自动的工作流挡在执行池外 ——
        //    而那是编辑器新建工作流的默认形态 ⇒ 用户会发现「加了定时触发器之后
        //    就再也绑不到执行磁贴了」，这正是本次要修的那个问题。
        val both = workflow("both", listOf(manualTrigger, autoTrigger))
        assertFalse("前提：这个工作流不是 manual-only", both.isManualOnly())
        assertTrue("但它必须能进执行池", TileGate.accepts(TileKind.EXECUTE, both))
    }

    @Test
    fun `TOGGLE accepts a workflow with auto triggers`() {
        assertTrue(TileGate.accepts(TileKind.TOGGLE, workflow(triggers = listOf(autoTrigger))))
        assertTrue(
            TileGate.accepts(TileKind.TOGGLE, workflow(triggers = listOf(manualTrigger, autoTrigger)))
        )
    }

    @Test
    fun `TOGGLE rejects a workflow without auto triggers`() {
        assertFalse(TileGate.accepts(TileKind.TOGGLE, workflow(triggers = emptyList())))
        assertFalse(
            "★ 反向锁：manual trigger **不是** auto trigger，别把它算进去。\n" +
                "  把 manual 当成 auto 会让「只有手动触发器」的工作流进开关池 ——\n" +
                "  而开关池翻转的是 isEnabled，那个开关对纯手动工作流毫无意义。",
            TileGate.accepts(TileKind.TOGGLE, workflow(triggers = listOf(manualTrigger)))
        )
    }

    @Test
    fun `the two pools overlap on a manual plus auto workflow`() {
        // ⚠️ 2026-10-10：两池**不再互斥、也不再穷尽**（旧断言是「恰好被一个池接受」）。
        //    这条测试锁住新口径 —— 若有人把执行池改回 `!hasAutoTriggers()`，它立刻变红。
        val both = workflow("both", listOf(manualTrigger, autoTrigger))
        assertTrue("手动 + 自动：执行池接受", TileGate.accepts(TileKind.EXECUTE, both))
        assertTrue("手动 + 自动：开关池**也**接受（同一工作流可占两个槽位）", TileGate.accepts(TileKind.TOGGLE, both))

        // 其余组合仍然各归一处
        assertTrue(TileGate.accepts(TileKind.EXECUTE, workflow("manual-only", listOf(manualTrigger))))
        assertFalse(TileGate.accepts(TileKind.TOGGLE, workflow("manual-only", listOf(manualTrigger))))
        assertFalse(TileGate.accepts(TileKind.EXECUTE, workflow("auto-only", listOf(autoTrigger))))
        assertTrue(TileGate.accepts(TileKind.TOGGLE, workflow("auto-only", listOf(autoTrigger))))

        // ⚠️ 唯一两池都不收的一格：零触发器（归一化会补手动触发器 ⇒ 实际不会出现）
        val empty = workflow("empty", emptyList())
        assertFalse(TileGate.accepts(TileKind.EXECUTE, empty))
        assertFalse(TileGate.accepts(TileKind.TOGGLE, empty))
    }

    @Test
    fun `accepts ignores isEnabled`() {
        // ⚠️ 判据只看**触发器组成**，不看启用状态 —— 一个被停用的自动工作流
        //    仍然属于开关池（用户正是要靠开关磁贴把它打开）。
        assertTrue(
            TileGate.accepts(TileKind.TOGGLE, workflow(triggers = listOf(autoTrigger), isEnabled = false))
        )
        assertTrue(
            TileGate.accepts(
                TileKind.EXECUTE,
                workflow(triggers = listOf(manualTrigger), isEnabled = false)
            )
        )
    }

    // ── 越界判据（§4.7 越界态）─────────────────────────────

    @Test
    fun `isOutOfKind is false for an unbound slot`() {
        // ★ 反向锁：空槽**不是**越界态 —— 它只是没绑，不该提示「请重新绑定」。
        //   判错的后果是每个未绑定的磁贴都显示「含自动触发器，请重新绑定…」，
        //   用户会以为自己的配置坏了。
        assertFalse(
            TileGate.isOutOfKind(WorkflowTile(0, null, TileKind.EXECUTE), null)
        )
        assertFalse(
            TileGate.isOutOfKind(WorkflowTile(20, null, TileKind.TOGGLE), null)
        )
    }

    @Test
    fun `isOutOfKind is false when the bound workflow was deleted`() {
        // ⚠️ 工作流已被删 ⇒ 既有行为是显示成「未绑定」态，本改动**不改它**。
        //    越界态专指「工作流还在、但类型不匹配」。
        assertFalse(TileGate.isOutOfKind(WorkflowTile(0, "gone", TileKind.EXECUTE), null))
    }

    @Test
    fun `isOutOfKind is false when a bound execute workflow gained auto triggers`() {
        // ★ 2026-10-10 修订：加自动触发器**不再**让执行磁贴越界（判据是「有手动触发器」）。
        //    它此后同时属于两池 —— 用户想去开关池控制自动触发，可以**另外**绑一个开关槽位，
        //    而不是被强制把执行槽位换掉。
        val tile = WorkflowTile(0, "wf", TileKind.EXECUTE)
        assertFalse(
            TileGate.isOutOfKind(tile, workflow("wf", listOf(manualTrigger, autoTrigger)))
        )
    }

    @Test
    fun `isOutOfKind is true when a bound execute workflow lost its manual trigger`() {
        // ★ 现在执行池**唯一**的越界形态：用户把手动触发器删掉了。
        //   此时该工作流必然只剩自动触发器（归一化保证至少有一个触发器）
        //   ⇒ `tile_out_of_kind_execute` 引导去开关池是对的。
        val tile = WorkflowTile(0, "wf", TileKind.EXECUTE)
        assertTrue(TileGate.isOutOfKind(tile, workflow("wf", listOf(autoTrigger))))
    }

    @Test
    fun `isOutOfKind is true when a bound toggle workflow lost its auto triggers`() {
        val tile = WorkflowTile(20, "wf", TileKind.TOGGLE)
        val nowManualOnly = workflow("wf", listOf(manualTrigger))
        assertTrue(TileGate.isOutOfKind(tile, nowManualOnly))
    }

    @Test
    fun `isOutOfKind is false when the binding still matches`() {
        assertFalse(
            TileGate.isOutOfKind(
                WorkflowTile(0, "wf", TileKind.EXECUTE),
                workflow("wf", listOf(manualTrigger))
            )
        )
        assertFalse(
            TileGate.isOutOfKind(
                WorkflowTile(20, "wf", TileKind.TOGGLE),
                workflow("wf", listOf(autoTrigger))
            )
        )
    }

    @Test
    fun `isOutOfKind reads the tile kind not the index`() {
        // ⚠️ 判据来源必须是 `tile.kind`（用户绑定时写进数据里的那个），
        //    而不是 `TileSlot.kindOf(tile.tileIndex)` —— 后者在「手工改过 JSON」
        //    的存量数据上会与 kind 不一致，而**以谁为准**决定了这个磁贴
        //    该按哪一池的行为跑。这里锁定「以 kind 为准」。
        val contradictory = WorkflowTile(tileIndex = 20, workflowId = "wf", kind = TileKind.EXECUTE)
        // kind = EXECUTE 且工作流有 auto ⇒ 越界（若按索引推会得到 TOGGLE、判为不越界）
        assertTrue(TileGate.isOutOfKind(contradictory, workflow("wf", listOf(autoTrigger))))
    }

    // ── 文案资源映射（三池各一张表）────────────────────────

    @Test
    fun `pool title and both message maps are distinct per kind`() {
        // ⚠️ 四张映射必须两两不同 —— 写错一张的表现是「提示指向另一池」，
        //    用户照着提示去改还是不对（可发现性极差）。
        val poolTitles = TileKind.entries.map { TileGate.poolTitleRes(it) }
        assertTrue("池标题两池必须不同", poolTitles[0] != poolTitles[1])

        val mismatches = TileKind.entries.map { TileGate.mismatchMessageRes(it) }
        assertTrue("绑定时提示两池必须不同", mismatches[0] != mismatches[1])

        val outOfKinds = TileKind.entries.map { TileGate.outOfKindMessageRes(it) }
        assertTrue("越界提示两池必须不同", outOfKinds[0] != outOfKinds[1])

        // ⚠️ 且「绑定时被拒」与「越界」**刻意分开**（§6.3）——
        //    混用会让用户以为「我明明绑上过，怎么又说不行」。
        for (kind in TileKind.entries) {
            assertTrue(
                "$kind：绑定时提示与越界提示必须是两套文案",
                TileGate.mismatchMessageRes(kind) != TileGate.outOfKindMessageRes(kind)
            )
        }
    }

    private companion object {
        /** 与 `Workflow.kt` 的 `MANUAL_TRIGGER_ID` 逐字一致（那里是 file-private）。 */
        const val MANUAL_TRIGGER_ID = "vflow.trigger.manual"
    }
}
