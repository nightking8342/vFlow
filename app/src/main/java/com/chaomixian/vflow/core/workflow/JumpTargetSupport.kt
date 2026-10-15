package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.execution.LoopState
import com.chaomixian.vflow.core.module.BlockType
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.ActionStepExecutionSettings
import com.chaomixian.vflow.core.workflow.module.logic.DO_WHILE_PAIRING_ID
import com.chaomixian.vflow.core.workflow.module.logic.FOREACH_PAIRING_ID
import com.chaomixian.vflow.core.workflow.module.logic.LOOP_PAIRING_ID
import com.chaomixian.vflow.core.workflow.module.logic.WHILE_PAIRING_ID
import java.util.Stack

/**
 * **跳转目标的纯函数层**（fork 新增，P0-5 跳过跳转）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.4 / §2.5 / §2.6。
 *
 * ## 它服务两条路
 *
 * | 调用方 | 用途 |
 * |---|---|
 * | `WorkflowExecutor` 的 SKIP 分支 | 失败后跳到 `__skip_jump_target` 指定的步骤 |
 * | 编辑器（`ActionEditorSheet`） | 候选过滤（少给错选项）+ 保存时的静态环检测 |
 * | （P0 之后）`vflow.logic.jump` 的缺陷修复 | 复用同一套校验（设计 §9.2） |
 *
 * ## 为什么抽成纯函数 + 可注入的 [ShapeResolver]
 *
 * 本仓库**无 Robolectric**，`WorkflowExecutor` / 编辑器都起不来；而这块逻辑的失效方式
 * 全是**静默**的（跳错步骤、环检测漏报、`loopStack` 残留 ⇒ 后续步骤读到过期循环变量）。
 * 块结构信息在生产里来自 `ModuleRegistry`（JVM 单测里是空表），故做成**可注入**的
 * 函数接口：生产传 [REGISTRY_SHAPES]，测试传假表。
 *
 * ## 跳转规则（§2.5）
 *
 * - ✅ **允许任意方向**（向前 / 向后都行）：失败后回到开头重跑、回到上一步重做、
 *   状态机式编排都是真实需求，成熟范式也不禁（Step Functions 的 `Choice` 允许回跳）。
 * - ❌ **只禁止「向内跳」**：目标是块体内部时，块的开始/结束配对会失配 ——
 *   跳到 `Loop` 体内 ⇒ 该循环没进 `loopStack` ⇒ End Loop 的 `context.loopStack.peek()`
 *   在**空栈**上抛 `EmptyStackException` ⇒ 被执行器 catch 成
 *   `Failure("模块执行异常", "模块执行异常")`（**文案零信息量**）；
 *   跳到 `ForEach` 体内 ⇒ End ForEach 直接
 *   **`Failure("结束重复模块不在一个'重复每一项'循环内。")`**（`ForEachModule.kt:179-182`）；
 *   跳到 `If` 的中间分支 ⇒ 分支配对失配，行为未定义。
 *
 *   ⚠️ 注意 `Loop` 那一条**不是**「静默成功」—— `java.util.Stack.peek()` 空栈**抛异常、不返回 null**
 *   （`ExecutionContext.loopStack` 是 `java.util.Stack`；实测 `new Stack<>().peek()` →
 *   `EmptyStackException / null`）⇒ `LoopModule.kt:166` 的 `loopState == null` 是**死代码**。
 *   设计稿原先的「静默 `Success()`」断言已实测推翻（见该稿 §2.5 的更正段）。
 * - ⚠️ 方向不受限 ⇒ 必须有**环防护**：[MAX_JUMPS_PER_STEP]（单步上限，超限降级为
 *   「继续下一步」+ W 日志）、[MAX_JUMPS_PER_EXECUTION]（执行级总预算，超限**终止**）、
 *   [detectCycles]（编辑器静态环检测，只警告不阻止保存）。
 */
object JumpTargetSupport {

    /**
     * `vflow.logic.jump` 的模块 id。
     *
     * ⚠️ **执行器要按它把「模块发起的跳转」与「块结构发起的跳转」区分开** ——
     * `ExecutionSignal.Jump` 这个信号被 `If` / `Switch` / `Loop` / `ForEach` / `While` /
     * `DoWhile` / UI 块模块**共用**，那些是**结构性跳转**（每次迭代都发生），
     * 对它们计数会把正常循环掐死（详见 `WorkflowExecutor` 里该分支的注释）。
     *
     * ⚠️ 字面量与 `JumpModule.id` 重复是**刻意**的（本对象不依赖模块类，保持纯函数层可单测）；
     * 两者必须相等，由 `JumpTargetSupportTest` 断言锁死。
     */
    const val JUMP_MODULE_ID = "vflow.logic.jump"

    /**
     * 单**步骤**在**一次执行**内的跳转次数上限。
     *
     * 超限 ⇒ **不再跳转**，降级为 `pc++` 并打 `W` 日志（降级必须可见，不能静默）。
     * 取 10 的理由：正常编排里同一个失败步骤重复跳到同一点超过 10 次，
     * 几乎只可能是「环」，而它又必须留出「有意重试几次」的余量。
     */
    const val MAX_JUMPS_PER_STEP = 10

    /**
     * 一次执行的**跳转总预算**（跨所有步骤）。
     *
     * 超限 ⇒ **终止并报「疑似死循环」**（设计 §2.5：「比静默继续安全」）。
     * 取 200 的理由：正常编排的跳转总量是个位数到几十；200 足以放过「长链状态机」，
     * 又能在真正失控时**在用户察觉之前**停下来。
     *
     * ⚠️ 不能只靠 `workflow.maxExecutionTime` 兜底：它不是硬超时（只在挂起点检查），
     * 对「卡在阻塞模块内部」无效。
     */
    const val MAX_JUMPS_PER_EXECUTION = 200

    /** 一个步骤在块结构里的角色。 */
    enum class BlockRole { NONE, START, MIDDLE, END }

    /**
     * 跳转校验需要的块结构信息。
     *
     * @param role 块角色。只有 [BlockRole.NONE]（普通步骤）与 [BlockRole.START]
     *   （块开始，如 `Loop` / `If`）可以当目标；`MIDDLE` / `END` 一律不行。
     * @param pairingId 配对 id（判断是否同一类块）。
     * @param isLoop 是否**循环**块（Loop / While / ForEach / DoWhile）——
     *   只有循环会把状态压进 `loopStack`，故只有它影响对齐。
     */
    data class BlockShape(
        val role: BlockRole,
        val pairingId: String?,
        val isLoop: Boolean,
    )

    /** 步骤 → 块结构。生产用 [REGISTRY_SHAPES]，测试注入假表。 */
    fun interface ShapeResolver {
        fun shapeOf(step: ActionStep): BlockShape
    }

    /** 循环块的配对 id 集合（与 `BlockNavigator` 的判据同源）。 */
    private val LOOP_PAIRING_IDS = setOf(
        LOOP_PAIRING_ID,
        WHILE_PAIRING_ID,
        FOREACH_PAIRING_ID,
        DO_WHILE_PAIRING_ID,
    )

    /** 生产用的解析器：块结构来自 `ModuleRegistry`。模块未注册 ⇒ 当作普通步骤。 */
    val REGISTRY_SHAPES = ShapeResolver { step ->
        val behavior = ModuleRegistry.getModule(step.moduleId)?.blockBehavior
        BlockShape(
            role = when (behavior?.type) {
                BlockType.BLOCK_START -> BlockRole.START
                BlockType.BLOCK_MIDDLE -> BlockRole.MIDDLE
                BlockType.BLOCK_END -> BlockRole.END
                else -> BlockRole.NONE
            },
            pairingId = behavior?.pairingId,
            isLoop = behavior?.pairingId != null && behavior.pairingId in LOOP_PAIRING_IDS,
        )
    }

    /** 读一个步骤声明的跳转目标（`__skip_jump_target`）。空 / 空白 ⇒ `null`。 */
    fun targetIdOf(step: ActionStep): String? =
        (step.parameters[ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET] as? String)
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /**
     * 由 step id 反查步骤下标；找不到（目标被删 / id 拼错）⇒ **-1**。
     *
     * ⚠️ 存 id 不存序号是决策 D4：序号 = 位置引用 ⇒ 插入/删除步骤后**静默跳错步骤**；
     * id 把整类问题消掉，代价只是「目标被删 ⇒ 反查不到」（可检测、可降级、可警告）。
     */
    fun resolveTargetIndex(steps: List<ActionStep>, targetId: String?): Int {
        if (targetId.isNullOrBlank()) return -1
        return steps.indexOfFirst { it.id == targetId }
    }

    /**
     * 这个目标能不能跳。
     *
     * 两条判据（§2.5）：
     * 1. 目标必须是**普通步骤或块开始**（`MIDDLE` / `END` 会破坏块配对，见类注释）；
     * 2. 目标缩进必须 **≤** 当前步骤缩进 ⇒ 挡住「向内跳」。
     *
     * ⚠️ 判据用 `indentationLevel`（编辑器维护，`WorkflowEditorActivity`）而不是
     * 「算出来的嵌套深度」：UI 候选过滤与运行期校验**必须用同一把尺**，否则会出现
     * 「UI 让选、运行期拒绝」的分裂。
     */
    fun isAllowedTarget(
        steps: List<ActionStep>,
        targetIndex: Int,
        currentIndentationLevel: Int,
        shapes: ShapeResolver = REGISTRY_SHAPES,
    ): Boolean {
        if (targetIndex !in steps.indices) return false
        val target = steps[targetIndex]
        val role = shapes.shapeOf(target).role
        if (role == BlockRole.MIDDLE || role == BlockRole.END) return false
        return target.indentationLevel <= currentIndentationLevel
    }

    /**
     * 位置 [position] **被几个循环包着**（0 = 不在任何循环里）。
     *
     * 实现是「反复往外找」：先找包住 `position` 的最近循环起点 `s`，再从 `s - 1`
     * 继续找外层循环，直到找不到为止。之所以用推断法而不是直接记：
     * `LoopState` **不记录自身起始位置**（`ExecutionContext.kt`）。
     */
    fun enclosingLoopCount(
        steps: List<ActionStep>,
        position: Int,
        shapes: ShapeResolver = REGISTRY_SHAPES,
    ): Int {
        var count = 0
        var cursor = position
        while (cursor in steps.indices) {
            val start = findEnclosingLoopStart(steps, cursor, shapes)
            if (start < 0) break
            count++
            cursor = start - 1
        }
        return count
    }

    /**
     * 向外跳之后**把 `loopStack` 对齐到目标位置**（修既有缺陷 B3）。
     *
     * 现状：`ExecutionSignal.Jump` 只做 `pc = signal.pc`，**不碰 `loopStack`**
     * ⇒ 从循环体内跳到循环外会残留 stale 条目，后续步骤会读到**过期的循环变量**
     * （`vflow.logic.jump` 今天就有这个问题）。
     *
     * ⚠️ 只 **pop**，从不 push：向内跳已被 [isAllowedTarget] 挡住；
     * 万一缩进信息不可靠导致目标比当前更深，这里**什么都不做**（宁可留着旧状态，
     * 也不要凭空造一个 `LoopState` —— 那会让 End Loop 的迭代计数错乱）。
     */
    fun alignLoopStack(
        loopStack: Stack<LoopState>,
        steps: List<ActionStep>,
        targetIndex: Int,
        shapes: ShapeResolver = REGISTRY_SHAPES,
    ) {
        val enclosing = enclosingLoopCount(steps, targetIndex, shapes)
        while (loopStack.size > enclosing) {
            loopStack.pop()
        }
    }

    /**
     * **静态环检测**：把所有「跳转边」（步骤 → 它的 `__skip_jump_target`）建成图查环。
     *
     * 只在编辑器里用于**警告**（不阻止保存）：环也可能是有意的有限循环
     * （运行期还有 [MAX_JUMPS_PER_STEP] 与 [MAX_JUMPS_PER_EXECUTION] 兜底）。
     *
     * ⚠️ 这里**只要求目标存在**，不要求 [isAllowedTarget] 通过 —— 一个「运行期会被拒绝的
     * 环」同样是用户写错了配置（跳转会静默降级成「继续下一步」），值得一并提醒。
     *
     * @return 每个环按**遍历顺序**列出的 step id；无环 ⇒ 空列表。
     */
    fun detectCycles(
        steps: List<ActionStep>,
    ): List<List<String>> {
        // 每个步骤最多一条出边 ⇒ 图是「链的集合」，不需要通用 DFS。
        val edges = mutableMapOf<String, String>()
        steps.forEach { step ->
            val targetId = targetIdOf(step) ?: return@forEach
            val targetIndex = resolveTargetIndex(steps, targetId)
            if (targetIndex >= 0) edges[step.id] = steps[targetIndex].id
        }
        if (edges.isEmpty()) return emptyList()

        val cycles = mutableListOf<List<String>>()
        val settled = mutableSetOf<String>()

        steps.forEach { start ->
            if (start.id in settled) return@forEach
            val path = mutableListOf<String>()
            val indexInPath = mutableMapOf<String, Int>()
            var cursor: String? = start.id

            while (cursor != null && cursor !in settled) {
                val seenAt = indexInPath[cursor]
                if (seenAt != null) {
                    cycles += path.subList(seenAt, path.size).toList()
                    break
                }
                indexInPath[cursor] = path.size
                path += cursor
                cursor = edges[cursor]
            }
            settled += path
        }
        return cycles
    }

    /**
     * 找包住 [position] 的**最近**循环起点；没有 ⇒ -1。
     *
     * 形态照 `BlockNavigator.findCurrentLoopStartPosition`，但块结构来自 [shapes]
     * （可注入 ⇒ 可单测），且只认**循环**块。
     */
    private fun findEnclosingLoopStart(
        steps: List<ActionStep>,
        position: Int,
        shapes: ShapeResolver,
    ): Int {
        var openBlocks = 0
        // ⚠️ 从 `position - 1` 起搜，**不包含 position 自己**：循环状态是在
        //    「循环开始步骤**执行时**」被压进 `loopStack` 的（`LoopModule.execute`），
        //    所以「站在循环开始步骤上」时该循环**还没进栈** ⇒ 它不算包住自己。
        //    差这一格会让「从循环体内跳回自己的循环开头」多压一份状态（残留 stale 条目）。
        for (i in position - 1 downTo 0) {
            val shape = shapes.shapeOf(steps[i])
            if (!shape.isLoop) continue
            when (shape.role) {
                BlockRole.END -> openBlocks++
                BlockRole.START -> {
                    if (openBlocks == 0) return i
                    openBlocks--
                }
                else -> Unit
            }
        }
        return -1
    }
}
