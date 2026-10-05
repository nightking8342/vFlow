// 文件: main/java/com/chaomixian/vflow/core/workflow/module/logic/SwitchModule.kt
// 描述: Switch（多路分支）块的四个模块 + 纯函数层 SwitchBlockSupport。
//
// 设计文档: docs/fork/switch-module-design.md（§3 数据结构 / §4 执行语义 / §5 静默失效点清单）
//
// ⚠️ **本文件刻意不继承 `BaseBlockModule`** —— 它把 `createSteps()` / `onStepDeleted()`
//    都设成了 `final`，而 Switch 两者都必须自己写（`createSteps` 要带默认分支、
//    `onStepDeleted` 要按「分支 + 它的整段体」删）。形态与 `ChooseFromMenuModule` 同款。
package com.chaomixian.vflow.core.workflow.module.logic

import android.content.Context
import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.BlockBehavior
import com.chaomixian.vflow.core.module.BlockType
import com.chaomixian.vflow.core.module.CustomEditorViewHolder
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.ExecutionSignal
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.module.temporaryWorkflowOnlyMetadata
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNull
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import java.util.UUID

private const val TAG = "SwitchModule"

/** 配对 ID（设计文档 §3.1 定案字面量）。 */
const val SWITCH_PAIRING_ID = "switch"

/** 四个模块 id。 */
const val SWITCH_START_ID = "vflow.logic.switch.start"
const val SWITCH_CASE_ID = "vflow.logic.switch.case"
const val SWITCH_DEFAULT_ID = "vflow.logic.switch.default"
const val SWITCH_END_ID = "vflow.logic.switch.end"

// ── 参数 key（设计文档 §3.2）。⚠️ 全部 `internal`：下游 `WorkflowEditorActivity` 要用
//    `SWITCH_START_ID` 接 `syncDynamicBlockAfterSave`，那个是本包可见性就够；这几个 key
//    目前只有本文件与同包的下游文件用。若下游（`SwitchEditorSheet`）也要写 `branches`，
//    改成 `const val`（public）即可。
internal const val SWITCH_VALUE_KEY = "value"
internal const val SWITCH_BRANCHES_KEY = "branches"
internal const val SWITCH_MATCH_KEY = "match"
internal const val SWITCH_CASE_ID_KEY = "caseId"

/**
 * `branches` 里的一条分支。
 *
 * @param id    稳定 UUID，改名 / 改匹配值都不动它；`reconcileBranches` 靠它认领分支体
 * @param match **`null` = 这是一条 Default 分支**；非 null 一律是 Case（`""` = 该 Case 尚未填值）
 */
data class SwitchBranch(val id: String, val match: String?)

/**
 * Switch 块的纯函数层。**零 Android 依赖**（只用 `ActionStep` / `ModuleRegistry` 这类纯 Kotlin 类型），
 * 故可纯 JVM 单测。
 *
 * 与本对象同构的既有实现是 `MenuBlockSupport`（`ChooseFromMenuModule.kt`）——
 * 差别只在「分支的身份是 `caseId`、分支的值叫 `match`」。
 */
object SwitchBlockSupport {
    private const val BRANCH_ID = "id"
    private const val MATCH = "match"
    private const val CASE_ID = "caseId"

    /** 按给定的匹配值序列建分支（`null` = Default）。id 用 `UUID.randomUUID()`。 */
    fun createBranches(matches: List<String?>): List<SwitchBranch> =
        matches.map { SwitchBranch(UUID.randomUUID().toString(), it) }

    /**
     * 转成可直接塞进 `branches` 参数的形态。
     * ⚠️ Default 的 `match` **显式写入 null** —— 落盘时 Gson 不写 null（键会消失），
     * 而 `readBranches` 对「缺键」与「显式 null」都判为 Default，故两条路一致。
     */
    fun toParameters(branches: List<SwitchBranch>): List<Map<String, Any?>> =
        branches.map { mapOf(BRANCH_ID to it.id, MATCH to it.match) }

    /**
     * 从参数值读回；兼容 `VList`/`List` 与 `VDictionary`/`Map`（照 `MenuBlockSupport.readItems`）。
     *
     * ⚠️⚠️ **「缺 `match` 键」与「显式 `VNull`」都必须判为 Default** ——
     * `WorkflowManager` 的 Gson 没有 `serializeNulls`，落盘一圈回来 `match` 键就没了；
     * 只认显式 null 的话所有 Default 会被读成 Case（`match` 变成 `""`），
     * 表现为 `validate` 反而报「匹配值不能为空」（设计文档 §5 #4 的同源形态）。
     *
     * ⚠️ id 为空白的条目**直接丢弃**（与 `MenuBlockSupport.readItems` 一致）——
     * 认不回 id 的分支无法维护，留着只会让 `reconcileBranches` 建出孤儿卡片。
     */
    fun readBranches(value: Any?): List<SwitchBranch> {
        val entries = when (value) {
            is VList -> value.raw
            is List<*> -> value
            else -> emptyList<Any>()
        }
        return entries.mapNotNull { entry ->
            val map = when (entry) {
                is VDictionary -> entry.raw
                is Map<*, *> -> entry
                else -> null
            } ?: return@mapNotNull null
            val id = valueAsString(map[BRANCH_ID])?.takeIf(String::isNotBlank) ?: return@mapNotNull null
            val rawMatch = map[MATCH]
            val match = when (rawMatch) {
                null, is VNull -> null
                else -> valueAsString(rawMatch) ?: ""
            }
            SwitchBranch(id, match)
        }
    }

    /**
     * ★ 本设计最核心的一段：把 `branches` 的新顺序落到步骤列表上。
     *
     * ① 按 `caseId` 把「分支卡片 + 它整段分支体」收进 map
     * ② 清空整个块的分支区
     * ③ 按新顺序重建：每条从 map 里按 id 取回原分支体，取不到（新加的）⇒ 只建一张空卡片
     *
     * ⚠️⚠️ **为什么必须是「按 id 重建」而不是「在列表里挪卡片」**：
     * 分支体不是独立实体，它由「卡片的位置」隐式定义（下一个分支卡之前的所有步骤都属于它）。
     * 只挪卡片会把两段体**整体互换**（`"ok"` 名下跑 `"error"` 的步骤）——
     * **不报错、极难发现**。按 id 重建则天然满足：调序 ⇒ 体跟着走；删除 ⇒ 体一起消失。
     */
    fun reconcileBranches(steps: MutableList<ActionStep>, startPosition: Int) {
        val start = steps.getOrNull(startPosition) ?: return
        if (start.moduleId != SWITCH_START_ID) return
        val rawBranches = start.parameters[SWITCH_BRANCHES_KEY]
        val branches = readBranches(rawBranches)
        val endPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, SWITCH_PAIRING_ID)
        if (endPosition == -1) return

        val branchPositions = findDirectBranchPositions(steps, startPosition, endPosition)

        // ── 完整性体检（设计文档 §5 #5）：任一条不通过就**整体放弃**本次同步。
        //    宁可这次不同步，也不能毁掉用户已有的分支体。
        if (branches.any { it.id.isBlank() }) {
            DebugLogger.w(TAG, "reconcileBranches 放弃：branches 里存在空白 id")
            return
        }
        if (branches.map { it.id }.toSet().size != branches.size) {
            DebugLogger.w(TAG, "reconcileBranches 放弃：branches 里的 id 有重复")
            return
        }
        // 有条目因 id 缺失被 readBranches 丢弃 ⇒ 计数对不上 ⇒ 同样放弃
        if (rawEntryCount(rawBranches) != branches.size) {
            DebugLogger.w(TAG, "reconcileBranches 放弃：branches 里有条目缺失 id（已被丢弃）")
            return
        }
        val cardIds = branchPositions.map { steps[it].parameters[SWITCH_CASE_ID_KEY] as? String }
        if (cardIds.any { it.isNullOrBlank() }) {
            DebugLogger.w(TAG, "reconcileBranches 放弃：分支卡片上存在缺失的 caseId")
            return
        }
        if (cardIds.filterNotNull().toSet().size != cardIds.size) {
            DebugLogger.w(TAG, "reconcileBranches 放弃：分支卡片上的 caseId 有重复")
            return
        }

        val existingBodies = linkedMapOf<String, List<ActionStep>>()
        branchPositions.forEachIndexed { index, branchStart ->
            val branchEnd = branchPositions.getOrElse(index + 1) { endPosition }
            val caseId = steps[branchStart].parameters[SWITCH_CASE_ID_KEY] as? String ?: return@forEachIndexed
            existingBodies[caseId] = steps.subList(branchStart, branchEnd).toList()
        }

        if (branchPositions.isNotEmpty()) {
            steps.subList(branchPositions.first(), endPosition).clear()
        }

        var insertPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, SWITCH_PAIRING_ID)
        if (insertPosition == -1) return
        branches.forEach { branch ->
            val match = branch.match
            val body = existingBodies[branch.id]?.toMutableList()
                ?: mutableListOf(
                    ActionStep(if (match == null) SWITCH_DEFAULT_ID else SWITCH_CASE_ID, emptyMap())
                )
            // ⚠️ Default 卡片的 `match` 键要**删掉**（不是留空串）——
            //    否则它的 `getSummary` 会渲染出一个空 pill。
            body[0] = if (match == null) {
                body[0].copy(
                    moduleId = SWITCH_DEFAULT_ID,
                    parameters = mapOf(SWITCH_CASE_ID_KEY to branch.id)
                )
            } else {
                body[0].copy(
                    moduleId = SWITCH_CASE_ID,
                    parameters = mapOf(
                        SWITCH_CASE_ID_KEY to branch.id,
                        SWITCH_MATCH_KEY to match
                    )
                )
            }
            steps.addAll(insertPosition, body)
            insertPosition += body.size
        }
    }

    /** 所有**直接**分支卡（Case + Default）的位置，升序。 */
    fun findDirectBranchPositions(steps: List<ActionStep>, start: Int, end: Int): List<Int> {
        var depth = 1
        val positions = mutableListOf<Int>()
        for (index in (start + 1) until end) {
            val behavior = com.chaomixian.vflow.core.module.ModuleRegistry
                .getModule(steps[index].moduleId)?.blockBehavior ?: continue
            if (behavior.pairingId != SWITCH_PAIRING_ID) continue
            when (behavior.type) {
                BlockType.BLOCK_START -> depth++
                BlockType.BLOCK_END -> depth--
                BlockType.BLOCK_MIDDLE -> if (depth == 1 &&
                    (steps[index].moduleId == SWITCH_CASE_ID || steps[index].moduleId == SWITCH_DEFAULT_ID)
                ) {
                    positions += index
                }
                BlockType.NONE -> Unit
            }
        }
        return positions
    }

    /** 某个 `caseId` 对应的分支卡位置；找不到返回 -1。 */
    fun findBranchPosition(steps: List<ActionStep>, startPosition: Int, caseId: String): Int {
        val endPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, SWITCH_PAIRING_ID)
        if (endPosition == -1) return -1
        return findDirectBranchPositions(steps, startPosition, endPosition)
            .firstOrNull { steps[it].parameters[SWITCH_CASE_ID_KEY] == caseId } ?: -1
    }

    /**
     * 找到某个直接分支卡所属的 Switch 起始卡位置；找不到返回 -1。
     * 走**配对 ID + 嵌套计数**（设计文档 §5 #6），嵌套 Switch 时内层不会认到外层。
     */
    fun findOwningSwitchPosition(steps: List<ActionStep>, branchPosition: Int): Int {
        var depth = 0
        for (index in (branchPosition - 1) downTo 0) {
            val behavior = com.chaomixian.vflow.core.module.ModuleRegistry
                .getModule(steps[index].moduleId)?.blockBehavior ?: continue
            if (behavior.pairingId != SWITCH_PAIRING_ID) continue
            when (behavior.type) {
                BlockType.BLOCK_END -> depth++
                BlockType.BLOCK_START -> {
                    if (depth == 0) return index
                    depth--
                }
                BlockType.BLOCK_MIDDLE -> Unit
                BlockType.NONE -> Unit
            }
        }
        return -1
    }

    /**
     * 删「这条分支 + 它的整段分支体」。`position` 不是分支卡时返回 false **且不改动列表**。
     *
     * ⚠️ **同时把该分支从所属 Switch 的 `branches` 里摘掉**。
     * 只删卡片/体而不动 `branches` 的话，下次 `reconcileBranches` 会**凭旧 `branches` 把它复活**
     * （重建出一张空卡片），而「删了又回来」是最难解释的一类症状。
     * 摘掉之后，「卡片 🗑」与「sheet 🗑」才真的是同一件事（设计文档 §2.4.4 / §5 #11）。
     */
    fun deleteBranch(steps: MutableList<ActionStep>, position: Int): Boolean {
        val step = steps.getOrNull(position) ?: return false
        if (step.moduleId != SWITCH_CASE_ID && step.moduleId != SWITCH_DEFAULT_ID) return false
        val startPosition = findOwningSwitchPosition(steps, position)
        if (startPosition == -1) return false
        val endPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, SWITCH_PAIRING_ID)
        if (endPosition == -1) return false
        val positions = findDirectBranchPositions(steps, startPosition, endPosition)
        val index = positions.indexOf(position)
        if (index == -1) return false
        val bodyEnd = positions.getOrElse(index + 1) { endPosition }
        val caseId = step.parameters[SWITCH_CASE_ID_KEY] as? String

        steps.subList(position, bodyEnd).clear()

        if (!caseId.isNullOrBlank()) {
            val startStep = steps.getOrNull(startPosition)
            if (startStep != null && startStep.moduleId == SWITCH_START_ID) {
                val remaining = readBranches(startStep.parameters[SWITCH_BRANCHES_KEY])
                    .filterNot { it.id == caseId }
                steps[startPosition] = startStep.copy(
                    parameters = startStep.parameters.toMutableMap()
                        .apply { put(SWITCH_BRANCHES_KEY, toParameters(remaining)) }
                )
            }
        }
        return true
    }

    /**
     * 设计文档 §5 第 1/2/7 条的硬校验。由 `SwitchModule.validate()` 消费。
     *
     * ⚠️ 三条都不是「洁癖」，是对**静默失效**的拦住：
     * ① 空匹配值 ⇒ `looseEquals("", 任意非数字串)` 返回 true ⇒ 该 Case **匹配一切**；
     * ② 重复匹配值 ⇒ 只有第一个生效，第二个的体是死代码且无提示；
     * ③ 两张 Default ⇒ 只有第一张生效。
     *
     * ⚠️ ② 的比较用 **`trim()` + 忽略大小写归一化后**的值 ——
     * 否则 `"ok"` 与 `"OK"` 会绕过检查，而执行期 `looseEquals` 又判它们相等。
     */
    fun validateBranches(branches: List<SwitchBranch>): ValidationResult {
        if (branches.isEmpty()) return ValidationResult(false, "请至少添加一个分支")
        if (branches.any { it.match != null && it.match.isBlank() }) {
            return ValidationResult(false, "分支的匹配值不能为空")
        }
        val caseValues = branches.mapNotNull { it.match }.map { it.trim().lowercase() }
        if (caseValues.toSet().size != caseValues.size) {
            return ValidationResult(false, "分支的匹配值不能重复")
        }
        if (branches.count { it.match == null } > 1) {
            return ValidationResult(false, "只能有一个默认分支")
        }
        return ValidationResult(true)
    }

    /**
     * 步骤列表里那个 Switch 块的直接分支卡，对应的 `branches` 表（按位置顺序）。
     * 与 `branches` 参数可能不一致（用户在卡片上改过 `match` 但没走 `syncMatchFromStep`）——
     * 本函数给出的是**卡片侧**的真相。
     */
    fun readBranchesFromSteps(steps: List<ActionStep>, startPosition: Int): List<SwitchBranch> {
        val start = steps.getOrNull(startPosition) ?: return emptyList()
        if (start.moduleId != SWITCH_START_ID) return emptyList()
        val endPosition = BlockNavigator.findEndBlockPosition(steps, startPosition, SWITCH_PAIRING_ID)
        if (endPosition == -1) return emptyList()
        return findDirectBranchPositions(steps, startPosition, endPosition).mapNotNull { position ->
            val step = steps[position]
            val rawId = step.parameters[SWITCH_CASE_ID_KEY] as? String
            if (rawId.isNullOrBlank()) return@mapNotNull null
            val match = if (step.moduleId == SWITCH_DEFAULT_ID) {
                null
            } else {
                valueAsString(step.parameters[SWITCH_MATCH_KEY]) ?: ""
            }
            SwitchBranch(rawId, match)
        }
    }

    /**
     * ⚠️ 把某张 **Case/Default 卡片**上改过的 `match` 写回它所属 Switch 的 `branches`。
     *
     * 不接这个 ⇒ 用户在卡片上改的值会在下次 `reconcileBranches` 时被旧 `branches` **静默抹掉**
     * （设计文档 §5 #10）。
     *
     * @return 是否真的改了（卡片不在某个 Switch 块内 / 找不到对应分支 / 值没变 ⇒ false）
     */
    fun syncMatchFromStep(steps: MutableList<ActionStep>, branchPosition: Int): Boolean {
        val step = steps.getOrNull(branchPosition) ?: return false
        if (step.moduleId != SWITCH_CASE_ID && step.moduleId != SWITCH_DEFAULT_ID) return false
        val startPosition = findOwningSwitchPosition(steps, branchPosition)
        if (startPosition == -1) return false
        val rawCaseId = step.parameters[SWITCH_CASE_ID_KEY] as? String
        if (rawCaseId.isNullOrBlank()) return false
        val caseId = rawCaseId
        val startStep = steps[startPosition]
        if (startStep.moduleId != SWITCH_START_ID) return false
        val branches = readBranches(startStep.parameters[SWITCH_BRANCHES_KEY])
        val index = branches.indexOfFirst { it.id == caseId }
        if (index == -1) return false
        val newMatch = if (step.moduleId == SWITCH_DEFAULT_ID) {
            null
        } else {
            valueAsString(step.parameters[SWITCH_MATCH_KEY]) ?: ""
        }
        if (branches[index].match == newMatch) return false
        val updated = branches.toMutableList().also { it[index] = branches[index].copy(match = newMatch) }
        steps[startPosition] = startStep.copy(
            parameters = startStep.parameters.toMutableMap()
                .apply { put(SWITCH_BRANCHES_KEY, toParameters(updated)) }
        )
        return true
    }

    private fun rawEntryCount(value: Any?): Int = when (value) {
        is VList -> value.raw.size
        is List<*> -> value.size
        else -> 0
    }

    private fun valueAsString(value: Any?): String? = when (value) {
        is VObject -> value.asString()
        null -> null
        else -> value.toString()
    }
}

/**
 * Switch 块起始卡片。
 *
 * ⚠️ 显示名**刻意不设 `nameStringRes`**（设计文档 §1.3）：模块名三语都写英文
 * （`Switch` / `Case` / `Default` / `End Switch`），一个语言显示英文、另一个语言显示自造中文，
 * 会让三语用户看到的**不是同一个东西**。
 */
class SwitchModule : BaseModule() {
    override val id = SWITCH_START_ID
    override val metadata = ActionMetadata(
        name = "Switch",
        description = "按一个值的相等匹配，在多条分支中选一条执行",
        descriptionStringRes = R.string.module_vflow_logic_switch_start_desc,
        iconRes = R.drawable.rounded_switch_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val aiMetadata = temporaryWorkflowOnlyMetadata(
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "Start a multi-way branch block that compares one value against " +
            "several case values with equality. Emit the four parts in order: vflow.logic.switch.start, " +
            "then one or more vflow.logic.switch.case, then at most one vflow.logic.switch.default, " +
            "then vflow.logic.switch.end. The start step must carry `branches`: a JSON array of " +
            "{\"id\": \"<uuid>\", \"match\": \"<value>\"}; use \"match\": null for the default branch. " +
            "Each case step must carry the same uuid in its `caseId` parameter and its " +
            "`match` value. Case match values must be non-empty and must not repeat " +
            "(comparison ignores case and trims spaces). There is no fall-through.",
        inputHints = mapOf(
            "value" to "The value compared against every case. Usually a previous step output or variable.",
            "branches" to "JSON array [{id, match}] in execution order. match = null marks the default branch.",
        ),
        requiredInputIds = setOf("value", "branches"),
    )
    override val blockBehavior = BlockBehavior(BlockType.BLOCK_START, SWITCH_PAIRING_ID)
    override val uiProvider: ModuleUIProvider = SwitchEditorUiProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = SWITCH_VALUE_KEY,
            name = "匹配值",
            staticType = ParameterType.ANY,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            nameStringRes = R.string.param_vflow_logic_switch_start_value_name
        ),
        InputDefinition(
            id = SWITCH_BRANCHES_KEY,
            name = "分支",
            staticType = ParameterType.ANY,
            defaultValue = emptyList<Map<String, Any?>>(),
            acceptsMagicVariable = false,
            acceptsNamedVariable = false,
            nameStringRes = R.string.param_vflow_logic_switch_start_branches_name
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = emptyList()

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val valueInput = getInputs().first { it.id == SWITCH_VALUE_KEY }
        val valuePill = PillUtil.createPillFromParam(step.parameters[SWITCH_VALUE_KEY], valueInput)
        return PillUtil.buildSpannable(context, "Switch ", valuePill)
    }

    override fun createSteps(): List<ActionStep> {
        // 默认带一条空 Case + 一条 Default（设计文档 §2.2）：
        // 没有 Default 时未匹配会让整块静默跳过，而「什么都不发生」极难排查。
        val branches = SwitchBlockSupport.createBranches(listOf("", null))
        return listOf(
            ActionStep(
                SWITCH_START_ID,
                mapOf(
                    SWITCH_VALUE_KEY to "",
                    SWITCH_BRANCHES_KEY to SwitchBlockSupport.toParameters(branches),
                )
            ),
            ActionStep(
                SWITCH_CASE_ID,
                mapOf(SWITCH_MATCH_KEY to "", SWITCH_CASE_ID_KEY to branches[0].id)
            ),
            ActionStep(SWITCH_DEFAULT_ID, mapOf(SWITCH_CASE_ID_KEY to branches[1].id)),
            ActionStep(SWITCH_END_ID, emptyMap()),
        )
    }

    override fun onStepDeleted(steps: MutableList<ActionStep>, position: Int): Boolean {
        val end = BlockNavigator.findEndBlockPosition(steps, position, SWITCH_PAIRING_ID)
        if (end == -1) return super.onStepDeleted(steps, position)
        for (index in end downTo position) steps.removeAt(index)
        return true
    }

    /**
     * ⚠️⚠️ **必须真的 override**：`BaseModule.validate()` 默认返回 `isValid = true`，
     * 不 override 等于**不校验**（设计文档 §5 第 1/2/7 条会全部静默失效）。
     * 生产调用点有两处（`ActionEditorSheet` 保存 sheet、`WorkflowEditorActivity` 保存工作流逐 step）。
     *
     * 校验源取 `step.parameters["branches"]` —— 它是第一真相源（行序 = 执行顺序），
     * 也是 sheet 写的那一份。
     */
    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult =
        SwitchBlockSupport.validateBranches(
            SwitchBlockSupport.readBranches(step.parameters[SWITCH_BRANCHES_KEY])
        )

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val steps = context.allSteps
        val current = context.currentStepIndex
        val end = BlockNavigator.findEndBlockPosition(steps, current, SWITCH_PAIRING_ID)
        if (end == -1) return ExecutionResult.Failure("Switch 结构错误", "找不到配对的 End Switch")

        val value = context.getVariable(SWITCH_VALUE_KEY)
        val positions = SwitchBlockSupport.findDirectBranchPositions(steps, current, end)

        var chosen = -1
        var defaultPosition = -1
        for (position in positions) {
            when (steps[position].moduleId) {
                SWITCH_DEFAULT_ID -> if (defaultPosition == -1) defaultPosition = position
                SWITCH_CASE_ID -> if (chosen == -1) {
                    // ⚠️ Switch 读的是**别人**（Case 卡片）的参数，走不到执行器的统一解析路径，
                    //    必须自己解析。用 `resolveValue` 而非 `resolve` 是刻意的：
                    //    它在「整串就是一个变量」时保留原始类型（Case 的值能连魔法变量是设计意图）。
                    val raw = steps[position].parameters[SWITCH_MATCH_KEY]
                    val caseValue = if (raw is String) VariableResolver.resolveValue(raw, context) else raw
                    if (ConditionEvaluator.evaluateCondition(value, OP_EQUALS, caseValue, null)) {
                        chosen = position
                    }
                }
            }
        }

        // 命中即跳走 ⇒ 执行流永远不会从一个分支体落进下一个（这就是「没有 break」的实现层含义）。
        val target = when {
            chosen != -1 -> chosen + 1
            defaultPosition != -1 -> defaultPosition + 1
            else -> end + 1 // 整块跳过
        }
        onProgress(ProgressUpdate("Switch: 跳转到第 ${target + 1} 步"))
        return ExecutionResult.Signal(ExecutionSignal.Jump(target))
    }
}

/** 分支卡片（Case）。匹配值可与魔法变量/命名变量连接。 */
class SwitchCaseModule : BaseModule() {
    override val id = SWITCH_CASE_ID
    override val metadata = ActionMetadata(
        name = "Case",
        description = "Switch 块中的一条匹配分支",
        descriptionStringRes = R.string.module_vflow_logic_switch_case_desc,
        iconRes = R.drawable.rounded_switch_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val aiMetadata = temporaryWorkflowOnlyMetadata(
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "One case branch inside a vflow.logic.switch.start block. Its `caseId` " +
            "must equal one of the `branches[].id` values declared on the matching switch.start step, " +
            "and its `match` value must equal that branch's `match`.",
        inputHints = mapOf(
            "match" to "The value this branch matches against the switch value.",
            "caseId" to "Internal uuid linking this card to branches[].id. Always copied from switch.start.",
        ),
    )
    override val blockBehavior = BlockBehavior(
        BlockType.BLOCK_MIDDLE,
        SWITCH_PAIRING_ID,
        // ⚠️ 对设计文档 §3.1（写 false）的**有意偏离**，用户 2026-10-05 已拍板取 true：
        //    该字段的唯一作用是让 ⋮ 菜单里的删除按钮出现（卡片上的直接 🗑 已被 fork 改成恒 GONE），
        //    取 false 会让 Case 卡片**完全没有删除入口**。
        isIndividuallyDeletable = true
    )
    override val uiProvider: ModuleUIProvider = SwitchCaseUiProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = SWITCH_MATCH_KEY,
            name = "匹配值",
            staticType = ParameterType.ANY,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            nameStringRes = R.string.param_vflow_logic_switch_case_match_name
        ),
        InputDefinition(
            id = SWITCH_CASE_ID_KEY,
            name = "分支标识",
            staticType = ParameterType.STRING,
            isHidden = true,
            acceptsMagicVariable = false,
            acceptsNamedVariable = false
        )
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = emptyList()

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val matchInput = getInputs().first { it.id == SWITCH_MATCH_KEY }
        val matchPill = PillUtil.createPillFromParam(step.parameters[SWITCH_MATCH_KEY], matchInput)
        return PillUtil.buildSpannable(context, "Case ", matchPill)
    }

    override fun onStepDeleted(steps: MutableList<ActionStep>, position: Int): Boolean =
        SwitchBlockSupport.deleteBranch(steps, position)

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        // 走到这里说明**这一条没被选中**（选中的会被 Switch 直接跳过去）。
        val end = BlockNavigator.findEndBlockPosition(context.allSteps, context.currentStepIndex, SWITCH_PAIRING_ID)
        return if (end == -1) {
            ExecutionResult.Failure("Switch 结构错误", "找不到配对的 End Switch")
        } else {
            ExecutionResult.Signal(ExecutionSignal.Jump(end + 1))
        }
    }
}

/** Default 分支卡片。无参数。 */
class SwitchDefaultModule : BaseModule() {
    override val id = SWITCH_DEFAULT_ID
    override val metadata = ActionMetadata(
        name = "Default",
        description = "所有 Case 都不匹配时执行的分支",
        descriptionStringRes = R.string.module_vflow_logic_switch_default_desc,
        iconRes = R.drawable.rounded_switch_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val aiMetadata = temporaryWorkflowOnlyMetadata(
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "The optional default branch inside a vflow.logic.switch.start block. " +
            "At most one is allowed. Its `caseId` must equal the `branches[].id` whose `match` is null.",
        inputHints = mapOf(
            "caseId" to "Internal uuid linking this card to the branches[] entry with match = null.",
        ),
    )
    override val blockBehavior = BlockBehavior(
        BlockType.BLOCK_MIDDLE,
        SWITCH_PAIRING_ID,
        // 同 SwitchCaseModule：有意偏离文档 §3.1，用户 2026-10-05 拍板（见该处注释）。
        isIndividuallyDeletable = true
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = emptyList()

    override fun getSummary(context: Context, step: ActionStep): CharSequence = "Default"

    override fun onStepDeleted(steps: MutableList<ActionStep>, position: Int): Boolean =
        SwitchBlockSupport.deleteBranch(steps, position)

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val end = BlockNavigator.findEndBlockPosition(context.allSteps, context.currentStepIndex, SWITCH_PAIRING_ID)
        return if (end == -1) {
            ExecutionResult.Failure("Switch 结构错误", "找不到配对的 End Switch")
        } else {
            ExecutionResult.Signal(ExecutionSignal.Jump(end + 1))
        }
    }
}

/** Switch 块的结束卡片。 */
class EndSwitchModule : BaseModule() {
    override val id = SWITCH_END_ID
    override val metadata = ActionMetadata(
        name = "End Switch",
        description = "Switch 块的结束点",
        descriptionStringRes = R.string.module_vflow_logic_switch_end_desc,
        iconRes = R.drawable.rounded_switch_24,
        category = "逻辑控制",
        categoryId = "logic"
    )
    override val aiMetadata = temporaryWorkflowOnlyMetadata(
        riskLevel = AiModuleRiskLevel.LOW,
        workflowStepDescription = "Close a switch block started by vflow.logic.switch.start.",
    )
    override val blockBehavior = BlockBehavior(BlockType.BLOCK_END, SWITCH_PAIRING_ID)

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = emptyList()

    override fun getSummary(context: Context, step: ActionStep): CharSequence = "End Switch"

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult = ExecutionResult.Success()
}

// ────────────────────────── 占位 UIProvider ──────────────────────────
//
// ⚠️ 这一对 provider 是**占位实现**，实体（管理 sheet）由下游任务交付
//    （`SwitchEditorSheet.kt`）。它们存在的唯一理由见下。

private class SwitchBranchesHolder(
    view: View,
    val branches: List<SwitchBranch>
) : CustomEditorViewHolder(view)

/**
 * `SwitchModule` 的**占位** provider。
 *
 * ⚠️⚠️ **`hasCustomEditor()` 必须留在默认的 `true`**：它是 `ActionEditorSheet`
 * 调用 `createEditor` / `readFromEditor` 的**唯一开关**。返回 `false` 虽能让界面更干净，
 * 但 `readFromEditor` 不再被调用 ⇒ `branches` 退回 `currentParameters` 的 `defaultValue`（空表）
 * ⇒ **默认分支被清空** —— 那是一个「不报错、功能不对」的静默失效。
 *
 * ⚠️ 已知临时代价 ①（用户 2026-10-05 已接受）：`ActionEditorSheet` 会显示一张**空的参数卡片**
 * （`customUiCard` 的显隐由上游控制，本层的占位实现改不了）。
 * 下游接上真正的管理 sheet 后该代价**自动消除**，**不要当缺陷返工**。
 */
private class SwitchEditorUiProvider : ModuleUIProvider {
    override fun getHandledInputIds(): Set<String> =
        setOf(SWITCH_VALUE_KEY, SWITCH_BRANCHES_KEY)

    override fun createEditor(
        context: Context,
        parent: ViewGroup,
        currentParameters: Map<String, Any?>,
        onParametersChanged: () -> Unit,
        onMagicVariableRequested: ((inputId: String) -> Unit)?,
        allSteps: List<ActionStep>?,
        onStartActivityForResult: ((Intent, (resultCode: Int, data: Intent?) -> Unit) -> Unit)?
    ): CustomEditorViewHolder {
        val branches = SwitchBlockSupport.readBranches(currentParameters[SWITCH_BRANCHES_KEY])
            .ifEmpty { SwitchBlockSupport.createBranches(listOf("", null)) }
        val placeholder = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0)
        }
        return SwitchBranchesHolder(placeholder, branches)
    }

    /**
     * ⚠️ 刻意**不返回 `value`** —— 它归 sheet 管（`getHandledInputIds` 已把它收走），
     * 在占位实现里回传一个空串会把用户已填的值抹掉。
     */
    override fun readFromEditor(holder: CustomEditorViewHolder): Map<String, Any?> =
        mapOf(
            SWITCH_BRANCHES_KEY to SwitchBlockSupport.toParameters(
                (holder as SwitchBranchesHolder).branches
            )
        )
}

/**
 * `SwitchCaseModule` 的 provider —— **只为了把 `caseId` 从通用表单里收走**。
 *
 * `hasCustomEditor() == false` ⇒ 不渲染自定义区、`customUiCard` 隐藏；
 * 但 `getHandledInputIds()` **仍然生效**（两道过滤是独立的）⇒ 用户只会看到「匹配值」一个输入框。
 */
private class SwitchCaseUiProvider : ModuleUIProvider {
    override fun getHandledInputIds(): Set<String> = setOf(SWITCH_CASE_ID_KEY)
    override fun hasCustomEditor(): Boolean = false
}
