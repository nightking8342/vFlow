package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.module.BlockType
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep

/**
 * `update_workflow` 的操作原语补丁——**纯函数层**。
 *
 * 这里只做「列表变换 + 结构校验」这些不依赖 Android / Context 的事，
 * 好让全部语义都有单测保护。补丁的语义细节（按 key 合并、`to_index` 基准、
 * 块成员禁用拒绝等）是「改错了不报错、只静默变差」的高发区，
 * 不能只靠端到端验证。
 *
 * 设计依据：`docs/fork/workflow-read-write-tools.md` §4.3.2。
 *
 * ## 三条容易踩的语义
 *
 * 1. **`update` 的 parameters 按 key 合并**，不是整表替换。传 `null` = 删键
 *    （不是写一个 null 值——那与「键不存在」在执行期对 `isRequired` 校验不等价）。
 * 2. **`move.to_index` 一律按「补丁执行前」的列表解释**。流水线里 move 在 delete
 *    之后执行，若按字面用当时的序号，模型从 `get_workflow` 抄来的 `#N` 会差一个删除量。
 * 3. **块成员的 `is_disabled` 必须拒绝**。执行器对 `BLOCK_END` 的禁用只跳一步
 *    （`WorkflowExecutor.kt:471-487`），禁用 `EndLoop` 会让循环静默只跑一遍且
 *    `loopStack` 泄漏。这是静态结构校验**原理上抓不到**的场景。
 */
object WorkflowPatch {

    /** 步骤 id 的合法字符集。含 `.` 或 `{{` 会静默破坏 `{{id.output}}` 引用解析。 */
    private val STEP_ID_PATTERN = Regex("[A-Za-z0-9_-]+")

    /** 触发器的禁用/移动无意义，但 id 校验与步骤同规则。 */
    fun isValidStepId(id: String): Boolean = id.isNotEmpty() && STEP_ID_PATTERN.matches(id)

    /**
     * 把一个 `parameters` 补丁应用到现有参数表上。
     *
     * 按 key 合并：只有出现在 [patch] 里的键被动。值为 `null` 表示**删掉该键**。
     *
     * @param base 现有参数（步骤原参数，或空表 = 用模块默认值兜底前）
     * @param patch 补丁里的键值对。`null` 值 = 删键
     */
    fun mergeParameters(
        base: Map<String, Any?>,
        patch: Map<String, Any?>,
    ): Map<String, Any?> {
        val merged = LinkedHashMap(base)
        patch.forEach { (key, value) ->
            if (value == null) {
                merged.remove(key)
            } else {
                merged[key] = value
            }
        }
        return merged
    }

    /**
     * 定位「插入到 [afterStepId] 之后」对应在 [steps] 里的下标。
     *
     * @return `afterStepId` 的下标 + 1；找不到返回 `null`
     */
    fun indexAfterStep(
        steps: List<ActionStep>,
        afterStepId: String,
    ): Int? {
        val index = steps.indexOfFirst { it.id == afterStepId }
        return if (index == -1) null else index + 1
    }

    /**
     * 把「1-based 显示序号」（补丁前口径）换算成插入位置。
     *
     * 模型从 `get_workflow` 看到的 `#N` 是 1-based 的：`#1` 是第一个动作步骤。
     * 插入到 `#N` **之前** ⇔ 下标 `N - 1`。允许 `N == size + 1`（追加到末尾）。
     */
    fun indexForDisplayPosition(
        steps: List<ActionStep>,
        displayIndex: Int,
    ): Int? {
        val index = displayIndex - 1
        return if (index in 0..steps.size) index else null
    }

    /**
     * 重映射 `vflow.logic.jump` 的 `target_step_index`——**支持增删步骤**。
     *
     * 与 [WorkflowJumpReferenceUpdater.remapAfterReorder] 的区别：那个函数开头就
     * `if (originalSteps.size != reorderedSteps.size) return`，只为编辑器的纯拖拽设计；
     * 本函数按 **step id** 建映射，因此增删步骤也能正确repoint。
     *
     * 三种情形：
     * - **字面数字**：按「原列表该位置的 step id」反查新序号
     * - **变量引用**（`{{vars.x}}` 等）：原样返回，不尝试、不猜测——静态算不出来
     * - **指向的步骤被删**：返回 `null`，由调用方报错拒绝（静默保留旧序号会让触发器跳飞）
     *
     * @return 重映射后的步骤列表，或 `null` 表示有 jump 的目标已被删除、补丁应被拒绝
     */
    fun remapJumpReferences(
        originalSteps: List<ActionStep>,
        newSteps: List<ActionStep>,
    ): List<ActionStep>? {
        // 先探测「目标已被删除」——这是必须让调用方拒绝整个补丁的情形，
        // 不能靠逐个 step 处理时才发现（那时错误信息已经丢掉了是哪一步）。
        val newStepIds = newSteps.mapTo(mutableSetOf()) { it.id }
        if (findJumpsWithDeletedTarget(originalSteps, newStepIds).isNotEmpty()) return null

        val newDisplayIndexByStepId = newSteps
            .mapIndexed { index, step -> step.id to (index + 1) }
            .toMap()

        return newSteps.map { step ->
            if (step.moduleId != JUMP_MODULE_ID) return@map step
            val rawTarget = step.parameters[TARGET_STEP_INDEX_PARAM] ?: return@map step

            // 变量引用 / 非数字：静态算不出新序号，原样保留（与 remapAfterReorder 同策略）。
            val targetNumber = rawTarget as? Number ?: return@map step
            val targetDouble = targetNumber.toDouble()
            if (targetDouble.isNaN() || targetDouble % 1.0 != 0.0) return@map step

            val originalDisplayIndex = targetDouble.toInt()
            val targetStepId = originalSteps.getOrNull(originalDisplayIndex - 1)?.id
                ?: return@map step // 原序号本来就越界：不是本次改动造成的，不动
            val newDisplayIndex = newDisplayIndexByStepId[targetStepId] ?: return@map step

            if (newDisplayIndex == originalDisplayIndex) {
                step
            } else {
                step.copy(
                    parameters = step.parameters.toMutableMap().apply {
                        put(TARGET_STEP_INDEX_PARAM, rewriteNumber(targetNumber, newDisplayIndex))
                    }
                )
            }
        }
    }

    /**
     * 该步骤是否**块成员**（START / MIDDLE / END）。
     *
     * 用于拒绝块成员的 `is_disabled`——理由见本对象文档第 3 条。
     * 模块未注册时按「非块成员」处理（后面还有模块存在性校验兜底）。
     */
    fun isBlockMember(moduleId: String): Boolean {
        val behavior = ModuleRegistry.getModule(moduleId)?.blockBehavior ?: return false
        return behavior.type != BlockType.NONE
    }

    /**
     * 找出 [steps] 里所有会被 [remapJumpReferences] 判定为「目标已删」的 jump 步骤。
     *
     * 单独提供是为了在**应用补丁之前**就能给出可读的错误信息（指名是哪一步的目标被删），
     * 而不是等到重映射失败时只拿到一个 `null`。
     */
    fun findJumpsWithDeletedTarget(
        originalSteps: List<ActionStep>,
        newStepIds: Set<String>,
    ): List<ActionStep> {
        return originalSteps.filter { step ->
            if (step.moduleId != JUMP_MODULE_ID) return@filter false
            val targetNumber = step.parameters[TARGET_STEP_INDEX_PARAM] as? Number ?: return@filter false
            val targetDouble = targetNumber.toDouble()
            if (targetDouble.isNaN() || targetDouble % 1.0 != 0.0) return@filter false
            val targetStepId = originalSteps.getOrNull(targetDouble.toInt() - 1)?.id ?: return@filter false
            targetStepId !in newStepIds
        }
    }

    private fun rewriteNumber(source: Number, value: Int): Number {
        return when (source) {
            is Long -> value.toLong()
            is Short -> value.toShort()
            is Byte -> value.toByte()
            is Float -> value.toFloat()
            is Double -> value.toDouble()
            else -> value
        }
    }

    private const val JUMP_MODULE_ID = "vflow.logic.jump"
    private const val TARGET_STEP_INDEX_PARAM = "target_step_index"
}

/** 已构建好的插入项。参数的类型强制转换由 executor 负责（那需要 Android 侧的类型系统）。 */
data class StepInsertion(
    val step: ActionStep,
    val afterStepId: String?,
    val atIndex: Int?,
)

/** 一次移动。`toIndex` 是 **1-based 显示序号，按补丁执行前的列表**解释。 */
data class StepMove(
    val stepId: String,
    val toIndex: Int,
)

/**
 * 步骤列表的补丁。
 *
 * [rebuilt] 是「已重建好的步骤」——executor 先按模块求值 + 类型转换把新参数算出来，
 * 这里只负责列表手术。这样拆分的理由是：参数求值依赖 `ModuleRegistry` 与 `ArtifactStore`，
 * 而列表手术是纯逻辑，必须能脱离 Android 单测。
 */
data class StepListPatch(
    val rebuilt: Map<String, ActionStep> = emptyMap(),
    val insertions: List<StepInsertion> = emptyList(),
    val deletions: Set<String> = emptySet(),
    val moves: List<StepMove> = emptyList(),
)

sealed interface StepPatchOutcome {
    data class Applied(
        val steps: List<ActionStep>,
        val warnings: List<String>,
    ) : StepPatchOutcome

    data class Rejected(val errors: List<String>) : StepPatchOutcome
}

/**
 * 把 [StepListPatch] 应用到 [original] 上。
 *
 * ## 固定的执行顺序
 *
 * `update` → `insert` → `delete` → `move` → jump 重映射。顺序固定而不是按入参书写次序，
 * 否则同一份补丁换个排列就会产生不同结果、无法复现。
 *
 * ## 索引基准：一切位置引用都按**补丁前**的列表解释
 *
 * `insert.at_index` 与 `move.to_index` 指的都不是「执行到那一步时的当前位置」，
 * 而是「补丁前列表里的那个位置」。原因是模型从 `get_workflow` 抄来的序号是补丁前的，
 * 若按字面执行，它算出的位置会与 delete 掉的数量差一个偏移——静默错位。
 *
 * 锚点被删掉时回退到「它后面第一个还活着的步骤」，全都被删则追加到末尾。
 */
fun applyStepListPatch(
    original: List<ActionStep>,
    patch: StepListPatch,
): StepPatchOutcome {
    val originalIds = original.map { it.id }
    val originalIdSet = originalIds.toSet()
    val errors = mutableListOf<String>()

    // ── 校验：目标必须存在 ────────────────────────────────────────────────
    patch.rebuilt.keys.filterNot(originalIdSet::contains).forEach { id ->
        errors += "No step with id `$id` in this workflow, so it cannot be updated."
    }
    patch.deletions.filterNot(originalIdSet::contains).forEach { id ->
        errors += "No step with id `$id` in this workflow, so it cannot be deleted."
    }
    patch.moves.filterNot { it.stepId in originalIdSet }.forEach { move ->
        errors += "No step with id `${move.stepId}` in this workflow, so it cannot be moved."
    }

    // ── 校验：插入的 id 与位置参数 ────────────────────────────────────────
    patch.insertions.forEach { insertion ->
        val id = insertion.step.id
        if (!WorkflowPatch.isValidStepId(id)) {
            errors += "Step id `$id` is invalid. Use only letters, digits, underscore and hyphen."
        }
        if (id in originalIdSet) {
            errors += "Step id `$id` already exists in this workflow. Inserted ids must be new."
        }
        if (insertion.afterStepId == null && insertion.atIndex == null) {
            errors += "Inserted step `$id` needs exactly one of `after_step_id` or `at_index`."
        }
        if (insertion.afterStepId != null && insertion.atIndex != null) {
            errors += "Inserted step `$id` got both `after_step_id` and `at_index`; give exactly one."
        }
        insertion.afterStepId?.let { anchor ->
            if (anchor !in originalIdSet) {
                errors += "Inserted step `$id` anchors to `after_step_id: $anchor`, which does not exist."
            }
        }
        insertion.atIndex?.let { index ->
            if (index !in 0..originalIds.size) {
                errors += "Inserted step `$id` has `at_index: $index` out of range (0..${originalIds.size})."
            }
        }
    }
    patch.insertions
        .groupingBy { it.step.id }
        .eachCount()
        .filterValues { it > 1 }
        .keys
        .forEach { id -> errors += "Step id `$id` is inserted more than once." }

    // ── 校验：目标被 jump 引用的步骤不能删 ────────────────────────────────
    val remainingIds = originalIds.filterNot(patch.deletions::contains).toMutableSet()
    remainingIds += patch.insertions.map { it.step.id }
    WorkflowPatch.findJumpsWithDeletedTarget(original, remainingIds).forEach { jump ->
        val target = jump.parameters["target_step_index"]
        errors += "Step `${target ?: "?"}` is the jump target of `${jump.id}`. " +
            "Deleting it would make that jump point at the wrong step; " +
            "remove the jump step too, or pick a different step to delete."
    }

    if (errors.isNotEmpty()) return StepPatchOutcome.Rejected(errors)

    // ── update：原地替换 ──────────────────────────────────────────────────
    val working = original.map { patch.rebuilt[it.id] ?: it }.toMutableList()

    // ── insert ────────────────────────────────────────────────────────────
    // 同一锚点连续插入时要保持数组顺序（X 后插 A 再插 B 应是 X,A,B 而不是 X,B,A），
    // 所以给每个锚点记一个已插入计数。
    val insertedAfterAnchor = mutableMapOf<String, Int>()
    patch.insertions.forEach { insertion ->
        val position = when {
            insertion.afterStepId != null -> {
                val anchorIndex = working.indexOfFirst { it.id == insertion.afterStepId }
                val offset = insertedAfterAnchor.getOrDefault(insertion.afterStepId, 0)
                insertedAfterAnchor[insertion.afterStepId] = offset + 1
                anchorIndex + 1 + offset
            }
            else -> resolveAnchorPosition(working, originalIds, insertion.atIndex!!)
        }
        working.add(position.coerceIn(0, working.size), insertion.step)
    }

    // ── delete ────────────────────────────────────────────────────────────
    if (patch.deletions.isNotEmpty()) {
        working.removeAll { it.id in patch.deletions }
    }

    // ── move ──────────────────────────────────────────────────────────────
    patch.moves.forEach { move ->
        val from = working.indexOfFirst { it.id == move.stepId }
        if (from == -1) return@forEach
        val step = working.removeAt(from)
        val target = resolveAnchorPosition(working, originalIds, move.toIndex - 1)
        working.add(target.coerceIn(0, working.size), step)
    }

    // ── jump 重映射 ───────────────────────────────────────────────────────
    val remapped = WorkflowPatch.remapJumpReferences(original, working)
        ?: return StepPatchOutcome.Rejected(
            listOf(
                "A `vflow.logic.jump` step points at a step that no longer exists. " +
                    "Adjust or remove that jump step in the same patch."
            )
        )

    return StepPatchOutcome.Applied(
        steps = remapped,
        warnings = buildRuntimeJumpWarnings(remapped),
    )
}

/**
 * 把「1-based 显示位置」解析成 [working] 里的插入下标。
 *
 * 位置引用按**补丁前**的 [originalIds] 解释；锚点已被删除时回退到它后面第一个
 * 还活着的步骤，全都没了则返回列表长度（追加到末尾）。
 */
private fun resolveAnchorPosition(
    working: List<ActionStep>,
    originalIds: List<String>,
    anchorIndex: Int,
): Int {
    for (i in anchorIndex.coerceAtLeast(0) until originalIds.size) {
        val position = working.indexOfFirst { it.id == originalIds[i] }
        if (position != -1) return position
    }
    return working.size
}

/**
 * 变量型 jump 的警告文案。
 *
 * `target_step_index` 可以是 `{{vars.x}}` 这类**运行时才求值**的写法，此时它的目标
 * 算不出来、静态重映射也做不到。改动步骤顺序后它是否还指向原来那一步，只有跑起来才知道——
 * 所以必须显式告知，不能让它静默漂移（`docs/fork/workflow-read-write-tools.md` §3.1.4）。
 */
private fun buildRuntimeJumpWarnings(steps: List<ActionStep>): List<String> {
    val count = steps.count { step ->
        if (step.moduleId != "vflow.logic.jump") return@count false
        val raw = step.parameters["target_step_index"] ?: return@count false
        raw !is Number
    }
    if (count == 0) return emptyList()
    return listOf(
        "This workflow has $count `vflow.logic.jump` step(s) whose `target_step_index` is a runtime " +
            "variable. Their targets cannot be recalculated statically, so they were left unchanged — " +
            "verify them manually if this change altered step order."
    )
}

