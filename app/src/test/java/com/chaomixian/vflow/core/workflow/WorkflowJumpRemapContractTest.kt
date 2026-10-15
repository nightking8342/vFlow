package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * **两份「跳转引用重映射」实现的契约一致性测试**（fork，2026-10-15，设计稿 §8 #17）。
 *
 * ## 为什么必须有这条测试
 *
 * 仓库里同时存在两份语义相近的实现：
 *
 * | 实现 | 位置 | 覆盖范围 |
 * |---|---|---|
 * | `WorkflowJumpReferenceUpdater.remapAfterReorder`（**上游**） | `core/workflow/` | 开头就 `if (size != size) return` ⇒ **仅等长重排** |
 * | `WorkflowPatch.remapJumpReferences`（**fork**） | `core/workflow/` | 按 step id 建映射 ⇒ **支持增删**；目标被删返回 `null` |
 *
 * 两条路都在**生产代码里活着**：拖拽走上游版（等长重排本就是它的设计场景），
 * 插入 / 删除 / 复制走 fork 版。本仓库吃过「两份实现必须同时改、结果只改了一处」的亏
 * （见 `FORK.md` 里 core 侧 logcat 移植版那条），所以这里把**它们必须一致的那部分**
 * 用断言钉住 —— 将来谁改坏了其中一份，这条会当场变红。
 *
 * ## 一致的边界（本测试锁的）
 *
 * 1. **等长重排**：两份必须给出**逐字相同**的结果；
 * 2. **变量型目标**（`{{vars.x}}`）：两份都必须**原样不动**（静态算不出来，不能猜）；
 * 3. **原本就越界的序号**：两份都必须**原样不动**（不是本次改动造成的，不背这个锅）。
 *
 * ## 刻意不一致的边界（本测试也显式锁住，免得被当成 bug）
 *
 * **长度变化**：上游版直接短路返回（序号**保持旧值** ⇒ 漂移），fork 版会重映射（或拒绝）。
 * 这不是缺陷，是分工 —— 上游那个函数就是为「拖拽」写的。
 */
class WorkflowJumpRemapContractTest {

    private fun step(id: String, moduleId: String = "vflow.device.delay"): ActionStep =
        ActionStep(moduleId = moduleId, parameters = emptyMap(), id = id)

    private fun jumpStep(id: String, target: Any): ActionStep = ActionStep(
        moduleId = "vflow.logic.jump",
        parameters = mapOf("target_step_index" to target),
        id = id,
    )

    /** 取「跳转目标序号」用于比较（两份实现都可能返回新对象）。 */
    private fun targets(steps: List<ActionStep>): List<Any?> =
        steps.map { it.parameters["target_step_index"] }

    @Test
    fun `等长重排两份实现结果逐字相同`() {
        // 原状态：#1 a, #2 target, #3 jump(→2)
        val a = step("a")
        val target = step("target")
        val jump = jumpStep("jump", 2)
        val original = listOf(a, target, jump)
        // 交换 #1 与 #2 ⇒ target 变 #1 ⇒ jump 必须改成 1
        val reordered = listOf(target, a, jump)

        val upstream = WorkflowJumpReferenceUpdater.remapAfterReorder(original, reordered)
        val fork = WorkflowPatch.remapJumpReferences(original, reordered)

        assertNotNull("fork 版对等长重排必须成功（不该返回 null）", fork)
        assertEquals(
            "两份实现对等长重排的结果必须一致 —— 不一致说明有人只改了一份",
            targets(upstream),
            targets(fork!!),
        )
        assertEquals(1, fork[2].parameters["target_step_index"])
    }

    @Test
    fun `变量型目标两份实现都不动`() {
        // `{{vars.x}}` 静态算不出新序号 ⇒ 只能原样保留（两份都必须如此，谁也别自作聪明）
        val target = step("target")
        val jump = jumpStep("jump", "{{vars.x}}")
        val original = listOf(target, jump)
        val reordered = listOf(jump, target)

        val upstream = WorkflowJumpReferenceUpdater.remapAfterReorder(original, reordered)
        val fork = WorkflowPatch.remapJumpReferences(original, reordered)

        assertEquals(listOf("{{vars.x}}", null), targets(upstream))
        assertEquals(targets(upstream), targets(fork!!))
    }

    @Test
    fun `原本就越界的序号两份实现都不动`() {
        // 原序号 99 本来就越界（不是本次改动造成的）⇒ 不动它、也不报错
        val filler = step("filler")
        val jump = jumpStep("jump", 99)
        val original = listOf(filler, jump)
        val reordered = listOf(jump, filler)

        val upstream = WorkflowJumpReferenceUpdater.remapAfterReorder(original, reordered)
        val fork = WorkflowPatch.remapJumpReferences(original, reordered)

        assertEquals(listOf(99, null), targets(upstream))
        assertEquals(targets(upstream), targets(fork!!))
    }

    @Test
    fun `长度变化时两份实现的分工差异被显式锁住`() {
        // ⚠️ 这条断言记录的是**刻意的差异**，不是缺陷：
        //   - 上游版只为拖拽设计 ⇒ 长度变了直接短路，序号**保持旧值**（漂移）；
        //   - fork 版按 id 重映射 ⇒ 序号跟着目标走。
        // 若哪天有人把上游版也升级成支持增删，这条会红 —— 那是好事：说明该重新审视
        // 「两份实现」这个局面了（要么合并、要么删掉一份）。
        val target = step("target")
        val jump = jumpStep("jump", 1)
        val original = listOf(target, jump)
        val inserted = listOf(step("inserted"), target, jump)

        val upstream = WorkflowJumpReferenceUpdater.remapAfterReorder(original, inserted)
        val fork = WorkflowPatch.remapJumpReferences(original, inserted)

        assertEquals("上游版长度变了就不动（这是它的设计边界）", 1, upstream[2].parameters["target_step_index"])
        assertEquals("fork 版会把序号改成 target 的新位置 #2", 2, fork!![2].parameters["target_step_index"])
    }

    @Test
    fun `目标被删时 fork 版拒绝而上游版根本不处理`() {
        // 两种「不处理」的区别：fork 返回 null 让调用方**报错**；上游在等长场景下也会返回
        // 一个带旧序号的结果（但删除必然改变长度 ⇒ 它照样短路）。
        val target = step("target")
        val jump = jumpStep("jump", 1)
        val original = listOf(target, jump)
        val deleted = listOf(jump)

        assertNull(WorkflowPatch.remapJumpReferences(original, deleted))
        assertEquals(
            "上游版在长度变化时直接返回入参（调用方拿到的是未处理结果）",
            1,
            WorkflowJumpReferenceUpdater.remapAfterReorder(original, deleted)[0].parameters["target_step_index"],
        )
    }
}
