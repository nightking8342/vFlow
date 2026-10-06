package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两池**互斥判据**的逐格验证（设计 §4.6 / §8.1）。
 *
 * ⚠️⚠️ 这是三道闸（菜单 / 面板 / service）**共用的唯一判据**。
 * 任何一处自己写 `hasAutoTriggers()`，都会让「菜单项显示着、点了却被拒绝」出现。
 * 故本文件逐格锁死四个组合，外加两条反向锁。
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

    // ── 互斥判据逐格 ────────────────────────────────────────

    @Test
    fun `EXECUTE accepts a workflow without auto triggers`() {
        assertTrue(TileGate.accepts(TileKind.EXECUTE, workflow(triggers = emptyList())))
        assertTrue(TileGate.accepts(TileKind.EXECUTE, workflow(triggers = listOf(manualTrigger))))
    }

    @Test
    fun `EXECUTE rejects a workflow with auto triggers`() {
        assertFalse(TileGate.accepts(TileKind.EXECUTE, workflow(triggers = listOf(autoTrigger))))
        assertFalse(
            "manual + auto 并存时仍属开关池（这正是既有缺陷被掩盖的形态）",
            TileGate.accepts(TileKind.EXECUTE, workflow(triggers = listOf(manualTrigger, autoTrigger)))
        )
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
    fun `the two pools are strictly complementary`() {
        // ⚠️ 两池**互斥且穷尽**：任一工作流恰好被一个池接受（设计 §4.1）。
        //    若将来有人「放宽」某一池，这条会立刻变红。
        val cases = listOf(
            workflow("empty", emptyList()),
            workflow("manual-only", listOf(manualTrigger)),
            workflow("auto-only", listOf(autoTrigger)),
            workflow("both", listOf(manualTrigger, autoTrigger)),
        )
        for (wf in cases) {
            val execute = TileGate.accepts(TileKind.EXECUTE, wf)
            val toggle = TileGate.accepts(TileKind.TOGGLE, wf)
            assertTrue("${wf.id}：必须恰好被一个池接受", execute != toggle)
        }
    }

    @Test
    fun `accepts ignores isEnabled`() {
        // ⚠️ 判据只看**触发器组成**，不看启用状态 —— 一个被停用的自动工作流
        //    仍然属于开关池（用户正是要靠开关磁贴把它打开）。
        assertTrue(
            TileGate.accepts(TileKind.TOGGLE, workflow(triggers = listOf(autoTrigger), isEnabled = false))
        )
        assertTrue(
            TileGate.accepts(TileKind.EXECUTE, workflow(triggers = emptyList(), isEnabled = false))
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
    fun `isOutOfKind is true when a bound workflow gained auto triggers`() {
        // ★ 本设计最核心的一格（§9 未决项 4）：绑定时是手动型、后来加了定时触发。
        val tile = WorkflowTile(0, "wf", TileKind.EXECUTE)
        val nowAuto = workflow("wf", listOf(autoTrigger))
        assertTrue(TileGate.isOutOfKind(tile, nowAuto))
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
