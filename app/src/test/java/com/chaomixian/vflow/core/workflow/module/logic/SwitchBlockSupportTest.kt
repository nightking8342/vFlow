package com.chaomixian.vflow.core.workflow.module.logic

import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VNull
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * `SwitchBlockSupport` 的纯函数单测。
 *
 * ⚠️ **`@Before` 必须手动注册 4 个模块**（本仓库无 Robolectric，且本任务不改 `ModuleRegistry`）：
 * 不注册的话 `ModuleRegistry.getModule(...)?.blockBehavior` 恒为 null ⇒
 * `findDirectBranchPositions` / `BlockNavigator` 全部失效 ⇒ 测试会以**误导性的方式**失败。
 */
class SwitchBlockSupportTest {

    /** 普通步骤用的假 moduleId —— 未注册 ⇒ 分支定位会 `continue` 跳过，不影响嵌套计数。 */
    private val plain = "test.switch.plain"

    @Before
    fun setUp() {
        ModuleRegistry.reset()
        ModuleRegistry.register(SwitchModule())
        ModuleRegistry.register(SwitchCaseModule())
        ModuleRegistry.register(SwitchDefaultModule())
        ModuleRegistry.register(EndSwitchModule())
    }

    @After
    fun tearDown() {
        ModuleRegistry.reset()
    }

    // ───────────────────────── 构造辅助 ─────────────────────────

    private fun switchStart(
        branches: List<SwitchBranch>,
        value: String = ""
    ) = ActionStep(
        SWITCH_START_ID,
        mapOf(
            SWITCH_VALUE_KEY to value,
            SWITCH_BRANCHES_KEY to SwitchBlockSupport.toParameters(branches),
        )
    )

    private fun caseStep(caseId: String, match: String) =
        ActionStep(SWITCH_CASE_ID, mapOf(SWITCH_CASE_ID_KEY to caseId, SWITCH_MATCH_KEY to match))

    private fun defaultStep(caseId: String) =
        ActionStep(SWITCH_DEFAULT_ID, mapOf(SWITCH_CASE_ID_KEY to caseId))

    private fun plainStep(tag: String) = ActionStep(plain, mapOf("tag" to tag))

    private fun ids(steps: List<ActionStep>) = steps.map { it.id }

    // ───────────────────────── ★1 调序（核心用例） ─────────────────────────

    /**
     * ★ 本设计最核心的用例：调序后**每条分支体仍跟着自己的 `caseId`**。
     *
     * 分支体不是独立实体，它由「卡片的位置」隐式定义。若实现改成「在列表里挪卡片」，
     * 两段体就会**整体互换**（`"ok"` 名下跑 `"error"` 的步骤）—— 不报错、极难发现。
     */
    @Test
    fun `reordering branches keeps each body attached to its own caseId`() {
        val a = "branch-a"
        val b = "branch-b"
        val a1 = plainStep("A1")
        val a2 = plainStep("A2")
        val b1 = plainStep("B1")

        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"), SwitchBranch(b, "error"))),
            caseStep(a, "ok"),
            a1, a2,
            caseStep(b, "error"),
            b1,
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // 交换 branches 顺序（等价于用户在 sheet 里把第二条拖到第一条上面）
        steps[0] = switchStart(listOf(SwitchBranch(b, "error"), SwitchBranch(a, "ok")))
        SwitchBlockSupport.reconcileBranches(steps, 0)

        // 逐步骤 id 比对，不是只看条数
        // 结构：Start | Case(b) B1 | Case(a) A1 A2 | End
        assertEquals(SWITCH_START_ID, steps[0].moduleId)
        assertEquals(SWITCH_CASE_ID, steps[1].moduleId)
        assertEquals(b, steps[1].parameters[SWITCH_CASE_ID_KEY])
        assertEquals(listOf(b1.id), ids(steps.subList(2, 3)))
        assertEquals(SWITCH_CASE_ID, steps[3].moduleId)
        assertEquals(a, steps[3].parameters[SWITCH_CASE_ID_KEY])
        assertEquals(listOf(a1.id, a2.id), ids(steps.subList(4, 6)))
        assertEquals(SWITCH_END_ID, steps[6].moduleId)
        assertEquals(7, steps.size)
    }

    // ───────────────────────── 删除 / 新增 ─────────────────────────

    @Test
    fun `deleting a branch removes its card and its whole body only`() {
        val a = "branch-a"
        val b = "branch-b"
        val b1 = plainStep("B1")

        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"), SwitchBranch(b, "error"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            caseStep(b, "error"),
            b1,
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // 删第一条分支（卡片 + 它的体）
        assertTrue(SwitchBlockSupport.deleteBranch(steps, 1))

        assertEquals(4, steps.size)
        assertEquals(SWITCH_START_ID, steps[0].moduleId)
        assertEquals(SWITCH_CASE_ID, steps[1].moduleId)
        assertEquals(b, steps[1].parameters[SWITCH_CASE_ID_KEY])
        assertEquals(b1.id, steps[2].id)
        assertEquals(SWITCH_END_ID, steps[3].moduleId)

        // ⚠️ 被删的分支必须同时从 branches 里摘掉，否则下次 reconcile 会把它复活
        val remaining = SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY])
        assertEquals(listOf(b), remaining.map { it.id })
    }

    @Test
    fun `adding a new branch builds an empty card and leaves others untouched`() {
        val a = "branch-a"
        val c = "branch-c"
        val a1 = plainStep("A1")

        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"))),
            caseStep(a, "ok"),
            a1,
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        steps[0] = switchStart(listOf(SwitchBranch(a, "ok"), SwitchBranch(c, "new")))
        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(5, steps.size)
        assertEquals(SWITCH_CASE_ID, steps[1].moduleId)
        assertEquals(a, steps[1].parameters[SWITCH_CASE_ID_KEY])
        assertEquals(a1.id, steps[2].id)
        assertEquals(SWITCH_CASE_ID, steps[3].moduleId)
        assertEquals(c, steps[3].parameters[SWITCH_CASE_ID_KEY])
        assertEquals(SWITCH_END_ID, steps[4].moduleId)
    }

    @Test
    fun `recreating a default branch keeps no match key on the card`() {
        val d = "branch-default"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(d, null))),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(SWITCH_DEFAULT_ID, steps[1].moduleId)
        assertEquals(d, steps[1].parameters[SWITCH_CASE_ID_KEY])
        // ⚠️ Default 卡片上不能留 `match` 键，否则 getSummary 会渲染出一个空 pill
        assertFalse(steps[1].parameters.containsKey(SWITCH_MATCH_KEY))
        assertEquals(3, steps.size)
    }

    @Test
    fun `a case turning into a default changes module id and drops the match key`() {
        val a = "branch-a"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        steps[0] = switchStart(listOf(SwitchBranch(a, null)))
        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(SWITCH_DEFAULT_ID, steps[1].moduleId)
        assertFalse(steps[1].parameters.containsKey(SWITCH_MATCH_KEY))
        // 体保留（A1 还在，仍挂在同一张卡片下）
        assertEquals(4, steps.size)
        assertEquals(SWITCH_END_ID, steps[3].moduleId)
    }

    // ───────────────────────── 体检：放弃 reconcile ─────────────────────────

    @Test
    fun `reconcile is abandoned when a card lost its caseId`() {
        val a = "branch-a"
        val b = "branch-b"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(b, "error"), SwitchBranch(a, "ok"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            caseStep(b, "error"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
        // 把第一张卡片的 caseId 抹掉
        steps[1] = steps[1].copy(parameters = mapOf(SWITCH_MATCH_KEY to "ok"))
        val before = ids(steps)

        SwitchBlockSupport.reconcileBranches(steps, 0)

        // 步骤列表**逐项不变**（宁可不同步，也不毁掉用户已有的分支体）
        assertEquals(before, ids(steps))
    }

    @Test
    fun `reconcile is abandoned when two cards share a caseId`() {
        val a = "branch-a"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"), SwitchBranch("branch-b", "error"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            caseStep(a, "error"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
        val before = ids(steps)

        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(before, ids(steps))
    }

    @Test
    fun `reconcile is abandoned when branches table has duplicate ids`() {
        val a = "branch-a"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"), SwitchBranch(a, "error"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
        val before = ids(steps)

        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(before, ids(steps))
    }

    @Test
    fun `reconcile is abandoned when a branches entry has no id`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("branch-a", "ok"))),
            caseStep("branch-a", "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
        // branches 里混进一条没有 id 的条目（比如被手工改坏的 JSON）
        steps[0] = steps[0].copy(
            parameters = mapOf(
                SWITCH_VALUE_KEY to "",
                SWITCH_BRANCHES_KEY to listOf(
                    mapOf("id" to "branch-a", "match" to "ok"),
                    mapOf("match" to "orphan"),
                ),
            )
        )
        val before = ids(steps)

        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(before, ids(steps))
    }

    // ───────────────────────── 校验 ─────────────────────────

    @Test
    fun `blank match value is rejected`() {
        assertFalse(
            SwitchBlockSupport.validateBranches(
                listOf(SwitchBranch("a", ""), SwitchBranch("d", null))
            ).isValid
        )
        assertFalse(
            SwitchBlockSupport.validateBranches(
                listOf(SwitchBranch("a", "   "), SwitchBranch("d", null))
            ).isValid
        )
    }

    @Test
    fun `duplicate match values are rejected including case and space variants`() {
        val dup = SwitchBlockSupport.validateBranches(
            listOf(SwitchBranch("a", "ok"), SwitchBranch("b", "OK"), SwitchBranch("d", null))
        )
        assertFalse(dup.isValid)

        val padded = SwitchBlockSupport.validateBranches(
            listOf(SwitchBranch("a", "ok"), SwitchBranch("b", " ok "), SwitchBranch("d", null))
        )
        assertFalse(padded.isValid)
    }

    @Test
    fun `two default branches are rejected`() {
        val result = SwitchBlockSupport.validateBranches(
            listOf(SwitchBranch("a", "ok"), SwitchBranch("d1", null), SwitchBranch("d2", null))
        )
        assertFalse(result.isValid)
    }

    @Test
    fun `empty branches table is rejected`() {
        val result = SwitchBlockSupport.validateBranches(emptyList())
        assertFalse(result.isValid)
        assertTrue(result.errorMessage.orEmpty().contains("至少"))
    }

    @Test
    fun `a well formed table passes`() {
        val result = SwitchBlockSupport.validateBranches(
            listOf(SwitchBranch("a", "ok"), SwitchBranch("b", "error"), SwitchBranch("d", null))
        )
        assertTrue(result.errorMessage.orEmpty(), result.isValid)
    }

    @Test
    fun `a table without any default still passes`() {
        // 无 Default 是**合法**语义（不匹配 = 整块跳过），不是校验失败
        assertTrue(
            SwitchBlockSupport.validateBranches(listOf(SwitchBranch("a", "ok"))).isValid
        )
    }

    // ───────────────────────── 嵌套 ─────────────────────────

    @Test
    fun `nested switch does not confuse the outer block`() {
        val outerA = "outer-a"
        val outerB = "outer-b"
        val innerA = "inner-a"
        val outerB1 = plainStep("OUTER-B1")

        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(outerA, "ok"), SwitchBranch(outerB, "error"))),
            caseStep(outerA, "ok"),
            // ── 内层 Switch ──
            switchStart(listOf(SwitchBranch(innerA, "x"))),
            caseStep(innerA, "x"),
            plainStep("INNER"),
            ActionStep(SWITCH_END_ID, emptyMap()),
            // ── 内层结束 ──
            caseStep(outerB, "error"),
            outerB1,
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // 内层 End 不被当成外层结束 ⇒ 外层直接分支卡恰好两张
        val outerEnd = BlockNavigator.findEndBlockPosition(steps, 0, SWITCH_PAIRING_ID)
        assertEquals(8, outerEnd)
        val positions = SwitchBlockSupport.findDirectBranchPositions(steps, 0, outerEnd)
        assertEquals(listOf(1, 6), positions)

        // 删外层的第二条分支：只删外层那一条（卡片 + 它的体），内层整块不动
        assertTrue(SwitchBlockSupport.deleteBranch(steps, 6))
        assertEquals(7, steps.size)
        // 内层 Switch 三件套仍在原位
        assertEquals(SWITCH_START_ID, steps[2].moduleId)
        assertEquals(SWITCH_CASE_ID, steps[3].moduleId)
        assertEquals(SWITCH_END_ID, steps[5].moduleId)
        assertEquals(outerA, steps[1].parameters[SWITCH_CASE_ID_KEY])
        // 外层 branches 里只剩第一条
        assertEquals(
            listOf(outerA),
            SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY]).map { it.id }
        )
    }

    @Test
    fun `findOwningSwitchPosition resolves the nearest enclosing switch`() {
        val innerA = "inner-a"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("outer", "ok"))),
            caseStep("outer", "ok"),
            switchStart(listOf(SwitchBranch(innerA, "x"))),
            caseStep(innerA, "x"),
            ActionStep(SWITCH_END_ID, emptyMap()),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(2, SwitchBlockSupport.findOwningSwitchPosition(steps, 3))
        assertEquals(0, SwitchBlockSupport.findOwningSwitchPosition(steps, 1))
        // 块外的普通步骤
        assertEquals(-1, SwitchBlockSupport.findOwningSwitchPosition(steps, 0))
    }

    // ───────────────────────── deleteBranch 的边界 ─────────────────────────

    @Test
    fun `deleteBranch returns false and leaves the list untouched for non branch steps`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("a", "ok"))),
            caseStep("a", "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
        val before = ids(steps)

        assertFalse(SwitchBlockSupport.deleteBranch(steps, 2)) // 分支体内的普通步骤
        assertEquals(before, ids(steps))

        assertFalse(SwitchBlockSupport.deleteBranch(steps, 0)) // Switch 起始卡
        assertEquals(before, ids(steps))

        assertFalse(SwitchBlockSupport.deleteBranch(steps, 3)) // End 卡
        assertEquals(before, ids(steps))

        // 分支卡**不在任何 Switch 块内**时也不该动
        val orphan = mutableListOf(caseStep("x", "v"))
        assertFalse(SwitchBlockSupport.deleteBranch(orphan, 0))
        assertEquals(1, orphan.size)

        // 越界
        assertFalse(SwitchBlockSupport.deleteBranch(steps, 99))
        assertEquals(before, ids(steps))
    }

    @Test
    fun `deleting the default branch works the same way`() {
        val d = "branch-default"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("a", "ok"), SwitchBranch(d, null))),
            caseStep("a", "ok"),
            plainStep("A1"),
            defaultStep(d),
            plainStep("D1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertTrue(SwitchBlockSupport.deleteBranch(steps, 3))
        assertEquals(4, steps.size)
        assertEquals(SWITCH_END_ID, steps[3].moduleId)
        assertEquals(
            listOf("a"),
            SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY]).map { it.id }
        )
    }

    @Test
    fun `findBranchPosition locates a branch by caseId`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("a", "ok"), SwitchBranch("b", "error"))),
            caseStep("a", "ok"),
            plainStep("A1"),
            caseStep("b", "error"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(1, SwitchBlockSupport.findBranchPosition(steps, 0, "a"))
        assertEquals(3, SwitchBlockSupport.findBranchPosition(steps, 0, "b"))
        assertEquals(-1, SwitchBlockSupport.findBranchPosition(steps, 0, "missing"))
    }

    // ───────────────────────── read / toParameters ─────────────────────────

    @Test
    fun `readBranches and toParameters round trip all three states`() {
        val branches = listOf(
            SwitchBranch("a", "ok"),
            SwitchBranch("b", ""),
            SwitchBranch("d", null),
        )
        val params = SwitchBlockSupport.toParameters(branches)
        assertEquals(branches, SwitchBlockSupport.readBranches(params))
    }

    @Test
    fun `readBranches accepts VList of VDictionary`() {
        val value = VDictionary(
            mapOf(
                "id" to VString("a"),
                "match" to VString("ok"),
            )
        )
        val list = com.chaomixian.vflow.core.types.basic.VList(listOf(value))
        assertEquals(listOf(SwitchBranch("a", "ok")), SwitchBlockSupport.readBranches(list))
    }

    @Test
    fun `readBranches drops entries without an id`() {
        val value = listOf(
            mapOf("id" to "a", "match" to "ok"),
            mapOf("match" to "no id"),
            mapOf("id" to "", "match" to "blank id"),
            mapOf("id" to "   ", "match" to "spaces only"),
            "not a map",
        )
        assertEquals(listOf(SwitchBranch("a", "ok")), SwitchBlockSupport.readBranches(value))
    }

    @Test
    fun `readBranches treats a missing match key and an explicit VNull as default`() {
        // ⚠️ Gson 默认不写 null 值 ⇒ 落盘一圈回来 match 键就没了。
        //    这两条路必须同样判为 Default，否则所有 Default 会被读成空值 Case，
        //    validate 反而报「匹配值不能为空」。
        assertEquals(
            listOf(SwitchBranch("a", null)),
            SwitchBlockSupport.readBranches(listOf(mapOf("id" to "a")))
        )
        assertEquals(
            listOf(SwitchBranch("a", null)),
            SwitchBlockSupport.readBranches(
                listOf(mapOf("id" to "a", "match" to VNull))
            )
        )
        assertEquals(
            listOf(SwitchBranch("a", "")),
            SwitchBlockSupport.readBranches(listOf(mapOf("id" to "a", "match" to "")))
        )
    }

    @Test
    fun `readBranches returns empty for unrelated values`() {
        assertEquals(emptyList<SwitchBranch>(), SwitchBlockSupport.readBranches(null))
        assertEquals(emptyList<SwitchBranch>(), SwitchBlockSupport.readBranches("nope"))
        assertEquals(emptyList<SwitchBranch>(), SwitchBlockSupport.readBranches(42))
    }

    @Test
    fun `createBranches assigns distinct ids and keeps null as default`() {
        val branches = SwitchBlockSupport.createBranches(listOf("", null))
        assertEquals(2, branches.size)
        assertEquals("", branches[0].match)
        assertNull(branches[1].match)
        assertTrue(branches[0].id != branches[1].id)
    }

    // ───────────────────────── 卡片 ↔ branches 同步 ─────────────────────────

    @Test
    fun `syncMatchFromStep writes the card value back to the owning branches table`() {
        val a = "branch-a"
        val b = "branch-b"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"), SwitchBranch(b, "error"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            caseStep(b, "error"),
            plainStep("B1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        // 用户在卡片上把匹配值改成 done
        steps[1] = steps[1].copy(
            parameters = steps[1].parameters.toMutableMap().apply { put(SWITCH_MATCH_KEY, "done") }
        )

        assertTrue(SwitchBlockSupport.syncMatchFromStep(steps, 1))

        val branches = SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY])
        assertEquals("done", branches.first { it.id == a }.match)
        // ⚠️ 其它条目一个不动
        assertEquals("error", branches.first { it.id == b }.match)
        assertEquals(2, branches.size)
    }

    @Test
    fun `syncMatchFromStep returns false when nothing changed or the card is not a branch`() {
        val a = "branch-a"
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch(a, "ok"))),
            caseStep(a, "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertFalse(SwitchBlockSupport.syncMatchFromStep(steps, 1)) // 值没变
        assertFalse(SwitchBlockSupport.syncMatchFromStep(steps, 2)) // 普通步骤
        assertFalse(SwitchBlockSupport.syncMatchFromStep(steps, 0)) // 起始卡
        assertFalse(SwitchBlockSupport.syncMatchFromStep(steps, 99)) // 越界
    }

    @Test
    fun `readBranchesFromSteps reports the card side truth in position order`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("a", "ok"), SwitchBranch("d", null))),
            caseStep("a", "changed-on-card"),
            plainStep("A1"),
            defaultStep("d"),
            plainStep("D1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertEquals(
            listOf(SwitchBranch("a", "changed-on-card"), SwitchBranch("d", null)),
            SwitchBlockSupport.readBranchesFromSteps(steps, 0),
        )
    }

    // ───────────────────────── 空表 / 边界 ─────────────────────────

    @Test
    fun `reconcile with an empty branches table clears the whole branch area`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("a", "ok"))),
            caseStep("a", "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
        steps[0] = steps[0].copy(
            parameters = steps[0].parameters.toMutableMap()
                .apply { put(SWITCH_BRANCHES_KEY, emptyList<Map<String, Any?>>()) }
        )

        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(2, steps.size)
        assertEquals(SWITCH_START_ID, steps[0].moduleId)
        assertEquals(SWITCH_END_ID, steps[1].moduleId)
    }

    @Test
    fun `reconcile does nothing when the position is not a switch start`() {
        val steps = mutableListOf(plainStep("A1"), ActionStep(SWITCH_END_ID, emptyMap()))
        val before = ids(steps)

        SwitchBlockSupport.reconcileBranches(steps, 0)
        SwitchBlockSupport.reconcileBranches(steps, 99)

        assertEquals(before, ids(steps))
    }

    @Test
    fun `reconcile does nothing when the end switch is missing`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("a", "ok"), SwitchBranch("b", "error"))),
            caseStep("a", "ok"),
            plainStep("A1"),
        )
        val before = ids(steps)

        SwitchBlockSupport.reconcileBranches(steps, 0)

        assertEquals(before, ids(steps))
    }

    @Test
    fun `findDirectBranchPositions returns empty for an unrelated range`() {
        val steps = mutableListOf(plainStep("A1"), plainStep("A2"))
        assertEquals(emptyList<Int>(), SwitchBlockSupport.findDirectBranchPositions(steps, 0, 2))
    }

    @Test
    fun `deleteBranch on a card whose branches entry is missing still removes the card`() {
        val steps = mutableListOf(
            switchStart(listOf(SwitchBranch("other", "ok"))),
            caseStep("a", "ok"),
            plainStep("A1"),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )

        assertTrue(SwitchBlockSupport.deleteBranch(steps, 1))
        assertEquals(2, steps.size)
        // branches 本来就没有这一条 ⇒ 原样
        val branches = SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY])
        assertEquals(listOf("other"), branches.map { it.id })
    }

    @Test
    fun `branch parameters produced by the module are readable back`() {
        val module = SwitchModule()
        val steps = module.createSteps()
        assertNotNull(steps)
        val branches = SwitchBlockSupport.readBranches(steps[0].parameters[SWITCH_BRANCHES_KEY])
        assertEquals(2, branches.size)
        assertEquals(steps[1].parameters[SWITCH_CASE_ID_KEY], branches[0].id)
        assertEquals(steps[2].parameters[SWITCH_CASE_ID_KEY], branches[1].id)
    }
}
