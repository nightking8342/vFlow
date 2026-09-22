package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `WorkflowPatch` 纯函数层的回归测试。
 *
 * 重点锁**「改错了不报错、只静默变差」**的语义——这些地方出错不会抛异常，
 * 只会让数据悄悄变样（参数被重置、步骤移到错位置、引用断掉）。
 *
 * 设计依据：`docs/fork/workflow-read-write-tools.md` §4.3.2。
 */
class WorkflowPatchTest {

    // ─────────────────────────── mergeParameters ───────────────────────────

    @Test
    fun mergeParameters_mergesByKeyAndKeepsUntouchedKeys() {
        val base = mapOf("duration" to 2000, "message" to "hi", "retry" to true)

        val merged = WorkflowPatch.mergeParameters(base, mapOf("duration" to 3000))

        // 未提及的键必须原样保留——写成整表替换会静默丢掉 message/retry
        assertEquals(3000, merged["duration"])
        assertEquals("hi", merged["message"])
        assertEquals(true, merged["retry"])
    }

    @Test
    fun mergeParameters_nullValueRemovesKeyInsteadOfWritingNull() {
        val base = mapOf("duration" to 2000, "message" to "hi")

        val merged = WorkflowPatch.mergeParameters(base, mapOf("message" to null))

        // 关键：是**删键**，不是写一个 null 值。
        // 两者在执行期对 isRequired 校验不等价，写 null 会让必填校验认为「键在但为空」。
        assertFalse("null 值必须删掉键而不是留下 key->null", merged.containsKey("message"))
        assertEquals(2000, merged["duration"])
    }

    @Test
    fun mergeParameters_emptyPatchKeepsEverything() {
        val base = mapOf("a" to 1, "b" to 2)

        assertEquals(base, WorkflowPatch.mergeParameters(base, emptyMap()))
    }

    // ─────────────────────────── step id 字符集 ───────────────────────────

    @Test
    fun isValidStepId_acceptsOrdinaryIds() {
        assertTrue(WorkflowPatch.isValidStepId("delay_1"))
        assertTrue(WorkflowPatch.isValidStepId("toast-2"))
        assertTrue(WorkflowPatch.isValidStepId("Step3"))
    }

    @Test
    fun isValidStepId_rejectsCharactersThatBreakVariableReferences() {
        // step id 会被拼进 {{id.output}}，而变量解析按 . 分段——
        // 含 . 或 {{ 的 id 会静默破坏引用解析（不是报错，是解析成别的东西）
        assertFalse(WorkflowPatch.isValidStepId("delay.1"))
        assertFalse(WorkflowPatch.isValidStepId("{{vars.x}}"))
        assertFalse(WorkflowPatch.isValidStepId("delay 1"))
        assertFalse(WorkflowPatch.isValidStepId(""))
    }

    // ─────────────────────── remapJumpReferences ───────────────────────

    @Test
    fun remapJumpReferences_repointsAfterStepInsertedBeforeTarget() {
        // 原状态：#1 target, #2 jump(→1)
        val target = step("target")
        val jump = jumpStep("jump", 1)
        val original = listOf(target, jump)
        // 在 target 前插了一步 → target 变成 #2，jump 必须跟着改成 2
        val inserted = step("inserted")
        val newSteps = listOf(inserted, target, jump)

        val result = WorkflowPatch.remapJumpReferences(original, newSteps)

        assertNotNull(result)
        assertEquals(2, result!![2].parameters["target_step_index"])
    }

    @Test
    fun remapJumpReferences_repointsAfterTargetStepDeleted() {
        // 原状态：#1 filler, #2 target, #3 jump(→2)
        val filler = step("filler")
        val target = step("target")
        val jump = jumpStep("jump", 2)
        val original = listOf(filler, target, jump)
        // 删掉 filler → target 变成 #1
        val newSteps = listOf(target, jump)

        val result = WorkflowPatch.remapJumpReferences(original, newSteps)

        assertNotNull(result)
        assertEquals(1, result!![1].parameters["target_step_index"])
    }

    @Test
    fun remapJumpReferences_leavesRuntimeVariableTargetUntouched() {
        // {{vars.x}} 是运行时才求值的，静态算不出新序号——必须原样返回
        val target = step("target")
        val jump = step("jump", moduleId = "vflow.logic.jump")
            .copy(parameters = mapOf("target_step_index" to "{{vars.x}}"))
        val original = listOf(target, jump)

        val result = WorkflowPatch.remapJumpReferences(original, listOf(step("new"), target, jump))

        assertNotNull(result)
        assertEquals("{{vars.x}}", result!![2].parameters["target_step_index"])
    }

    @Test
    fun remapJumpReferences_returnsNullWhenTargetWasDeleted() {
        // jump(→2) 指向 target，但补丁把 target 删了 —— 必须报错拒绝，不能静默保留旧序号
        // （静默保留会让触发器运行时跳到错的地方，比当场报错难排查得多）
        val filler = step("filler")
        val target = step("target")
        val jump = jumpStep("jump", 2)
        val original = listOf(filler, target, jump)
        val newSteps = listOf(filler, jump) // target 没了

        assertNull(WorkflowPatch.remapJumpReferences(original, newSteps))
    }

    @Test
    fun remapJumpReferences_leavesOutOfRangeOriginalIndexAlone() {
        // 原序号本来就越界（不是本次改动造成的）→ 不动它，也不报错
        val filler = step("filler")
        val jump = jumpStep("jump", 99)
        val original = listOf(filler, jump)

        val result = WorkflowPatch.remapJumpReferences(original, original)

        assertNotNull(result)
        assertEquals(99, result!![1].parameters["target_step_index"])
    }

    @Test
    fun findJumpsWithDeletedTarget_namesTheJumpThatLostItsTarget() {
        val filler = step("filler")
        val target = step("target")
        val jump = jumpStep("jump", 2)
        val original = listOf(filler, target, jump)

        val found = WorkflowPatch.findJumpsWithDeletedTarget(original, setOf("filler", "jump"))

        assertEquals(1, found.size)
        assertEquals("jump", found.first().id)
    }

    @Test
    fun findJumpsWithDeletedTarget_ignoresRuntimeVariableTarget() {
        val jump = step("jump", moduleId = "vflow.logic.jump")
            .copy(parameters = mapOf("target_step_index" to "{{vars.x}}"))

        val found = WorkflowPatch.findJumpsWithDeletedTarget(listOf(jump), emptySet())

        assertTrue("变量型 jump 算不出目标，不该被报成「目标被删」", found.isEmpty())
    }

    // ──────────────────── applyStepListPatch：顺序与基准 ────────────────────

    @Test
    fun applyStepListPatch_omittedStepsAreNotDeleted() {
        // 补丁只提了 update —— 其余步骤必须原样保留。
        // 这是补丁式相对整表替换的核心优势：模型列清单时漏了一条，不会被解释成删除。
        val a = step("a")
        val b = step("b")
        val target = step("target")
        val original = listOf(a, b, target)

        val outcome = applyStepListPatch(
            original = original,
            patch = StepListPatch(rebuilt = mapOf("b" to b.copy(parameters = mapOf("x" to 1)))),
        )

        assertTrue(outcome is StepPatchOutcome.Applied)
        val steps = (outcome as StepPatchOutcome.Applied).steps
        assertEquals(3, steps.size)
        assertEquals(listOf("a", "b", "target"), steps.map { it.id })
    }

    @Test
    fun applyStepListPatch_moveIndexIsMeasuredAgainstPrePatchList() {
        // 这是最容易出错的一条：流水线里 move 在 delete 之后执行，
        // 但模型从 get_workflow 抄来的 to_index 是「补丁前」的序号。
        //
        // 本用例刻意让「删除发生在目标位置之前」——只有这样两种实现才会分叉：
        //   原列表 #1=a #2=b，补丁删掉 a，再要求「把 e 移到补丁前的 #2」（也就是 b 的位置）
        //   正确：b 在删后列表里是索引 0 → [e, b, c, d]
        //   错误（直接用 to_index-1 当新索引）：落到索引 1 → [b, e, c, d]
        val a = step("a")
        val b = step("b")
        val c = step("c")
        val d = step("d")
        val e = step("e")
        val original = listOf(a, b, c, d, e)

        val outcome = applyStepListPatch(
            original = original,
            patch = StepListPatch(
                deletions = setOf("a"),
                moves = listOf(StepMove(stepId = "e", toIndex = 2)),
            ),
        )

        assertTrue(outcome is StepPatchOutcome.Applied)
        assertEquals(
            "to_index 必须按补丁前的列表解释——e 应落在 b 之前，而不是 b 之后",
            listOf("e", "b", "c", "d"),
            (outcome as StepPatchOutcome.Applied).steps.map { it.id },
        )
    }

    @Test
    fun applyStepListPatch_insertAfterAnchorPreservesOrderForRepeatedAnchor() {
        // 连续两次「插在 a 之后」应得到 a,first,second（而不是 a,second,first）
        val a = step("a")
        val original = listOf(a)

        val outcome = applyStepListPatch(
            original = original,
            patch = StepListPatch(
                insertions = listOf(
                    StepInsertion(step = step("first"), afterStepId = "a", atIndex = null),
                    StepInsertion(step = step("second"), afterStepId = "a", atIndex = null),
                ),
            ),
        )

        assertTrue(outcome is StepPatchOutcome.Applied)
        assertEquals(
            listOf("a", "first", "second"),
            (outcome as StepPatchOutcome.Applied).steps.map { it.id },
        )
    }

    @Test
    fun applyStepListPatch_rejectsInsertIdThatAlreadyExists() {
        val a = step("a")
        val outcome = applyStepListPatch(
            original = listOf(a),
            patch = StepListPatch(
                insertions = listOf(StepInsertion(step = step("a"), afterStepId = "a", atIndex = null)),
            ),
        )

        assertTrue(outcome is StepPatchOutcome.Rejected)
        assertTrue((outcome as StepPatchOutcome.Rejected).errors.any { it.contains("already exists") })
    }

    @Test
    fun applyStepListPatch_rejectsInsertWithBothAnchorKindSpecified() {
        val a = step("a")
        val outcome = applyStepListPatch(
            original = listOf(a),
            patch = StepListPatch(
                insertions = listOf(StepInsertion(step = step("x"), afterStepId = "a", atIndex = 0)),
            ),
        )

        assertTrue(outcome is StepPatchOutcome.Rejected)
        assertTrue((outcome as StepPatchOutcome.Rejected).errors.any { it.contains("exactly one") })
    }

    @Test
    fun applyStepListPatch_rejectsInsertWithNeitherAnchorKindSpecified() {
        val a = step("a")
        val outcome = applyStepListPatch(
            original = listOf(a),
            patch = StepListPatch(
                insertions = listOf(StepInsertion(step = step("x"), afterStepId = null, atIndex = null)),
            ),
        )

        assertTrue(outcome is StepPatchOutcome.Rejected)
        assertTrue((outcome as StepPatchOutcome.Rejected).errors.any { it.contains("exactly one") })
    }

    @Test
    fun applyStepListPatch_rejectsOutOfRangeAtIndex() {
        val a = step("a")
        val outcome = applyStepListPatch(
            original = listOf(a),
            patch = StepListPatch(
                insertions = listOf(StepInsertion(step = step("x"), afterStepId = null, atIndex = 5)),
            ),
        )

        assertTrue(outcome is StepPatchOutcome.Rejected)
        assertTrue((outcome as StepPatchOutcome.Rejected).errors.any { it.contains("out of range") })
    }

    @Test
    fun applyStepListPatch_rejectsUpdateOfNonexistentStep() {
        val outcome = applyStepListPatch(
            original = listOf(step("a")),
            patch = StepListPatch(rebuilt = mapOf("ghost" to step("ghost"))),
        )

        assertTrue(outcome is StepPatchOutcome.Rejected)
        assertTrue((outcome as StepPatchOutcome.Rejected).errors.any { it.contains("ghost") })
    }

    @Test
    fun applyStepListPatch_reportsRuntimeJumpWarning() {
        val jump = step("jump", moduleId = "vflow.logic.jump")
            .copy(parameters = mapOf("target_step_index" to "{{vars.x}}"))
        val a = step("a")

        val outcome = applyStepListPatch(
            original = listOf(a, jump),
            patch = StepListPatch(rebuilt = mapOf("a" to a.copy(parameters = mapOf("k" to 1)))),
        )

        assertTrue(outcome is StepPatchOutcome.Applied)
        val warnings = (outcome as StepPatchOutcome.Applied).warnings
        assertTrue(
            "变量型 jump 必须产生警告——它的目标算不出来，静默漂移用户不会发现",
            warnings.any { it.contains("runtime variable") },
        )
    }

    @Test
    fun applyStepListPatch_deleteBlockMembersInOneCallSucceeds() {
        // 块删除：一次 delete 数组里列出全部成员即可（结构校验在全部变更之后跑）
        val ifStart = step("if_1", moduleId = "vflow.logic.if.start")
        val elseStep = step("else_1", moduleId = "vflow.logic.if.middle")
        val ifEnd = step("endif_1", moduleId = "vflow.logic.if.end")
        val after = step("after")
        val original = listOf(ifStart, elseStep, ifEnd, after)

        val outcome = applyStepListPatch(
            original = original,
            patch = StepListPatch(deletions = setOf("if_1", "else_1", "endif_1")),
        )

        assertTrue(outcome is StepPatchOutcome.Applied)
        assertEquals(listOf("after"), (outcome as StepPatchOutcome.Applied).steps.map { it.id })
    }

    // ────────────── 回归：JsonElement 直传导致的类型污染 ──────────────
    //
    // 2026-09-22 真机缺陷：`update_workflow` 曾把未归一化的 `JsonElement`
    // 直接交给 `coerceInputValue`，导致
    //   - STRING 被多包一层引号（`ABC` → `""ABC""`）
    //   - NUMBER 整个 JsonElement 落库（release 里被 R8 混淆成 `{a:false,b:"6000"}`）
    //   - ANY 落库后再转义一遍（真换行变字面 `\n`、`\s` 变 `\\s`）
    //
    // 修法是先过一遍 `parseArguments` 归一化。下面几条锁住「Kotlin 值进入
    // mergeParameters 后不再被改动」——归一化本身由 executor 的
    // `normalizeParameterPatch` 负责，它的正确性靠 `ChatAgentToolingTest` 与真机验收。

    @Test
    fun mergeParameters_doesNotAlterPlainStringValue() {
        // 回归缺陷 A：纯 ASCII 短串必须原样落库，不能被多包引号
        val merged = WorkflowPatch.mergeParameters(emptyMap(), mapOf("content" to "ABC"))

        assertEquals("ABC", merged["content"])
    }

    @Test
    fun mergeParameters_preservesRealNewlinesAndRegexBackslash() {
        // 回归缺陷 C：真换行必须保留为真换行；`\s` 必须还是 `\s`（不是 `\\s`）
        val script = "var a=1;\nvar flat=ft.replace(/\\s+/g,'');"

        val merged = WorkflowPatch.mergeParameters(emptyMap(), mapOf("script" to script))

        val stored = merged["script"] as String
        assertTrue("真换行必须保留", stored.contains('\n'))
        assertFalse("换行不能被转义成字面 \\n", stored.contains("\\n"))
        assertTrue("正则 \\s 必须原样", stored.contains("/\\s+/"))
        assertFalse("正则不能被二次转义成 \\\\s", stored.contains("/\\\\s+/"))
    }

    @Test
    fun mergeParameters_keepsNumericTypeNotWrapped() {
        // 回归缺陷 B：数字必须是 Number，不能是别的东西
        val merged = WorkflowPatch.mergeParameters(emptyMap(), mapOf("duration" to 6000))

        assertEquals(6000, merged["duration"])
        assertTrue("必须是 Number 而不能是容器", merged["duration"] is Number)
    }

    @Test
    fun mergeParameters_keepsVariableReferenceVerbatim() {
        // 回归缺陷 A 的变量引用变体：{{vars.STATE}} 必须单层原样
        val merged = WorkflowPatch.mergeParameters(emptyMap(), mapOf("source" to "{{vars.STATE}}"))

        assertEquals("{{vars.STATE}}", merged["source"])
    }

    private fun step(
        id: String,
        moduleId: String = "vflow.device.delay",
    ): ActionStep = ActionStep(moduleId = moduleId, parameters = emptyMap(), id = id)

    private fun jumpStep(id: String, target: Number): ActionStep = ActionStep(
        moduleId = "vflow.logic.jump",
        parameters = mapOf("target_step_index" to target),
        id = id,
    )
}
