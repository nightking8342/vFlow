package com.chaomixian.vflow.ui.overlay

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.CancellationException

/**
 * fork：AI 智能体 / AutoGLM 的**运行状态总线**。
 *
 * 存在的理由（改这块前先读）：
 *  1. **思考过程原先只活在一个 30 字的状态行里** —— `AgentOverlayManager.updateStatus()`
 *     里一句 `"Thinking: ${thought.take(30)}..."`，单行、无历史、每步覆盖。完整文本
 *     （`AgentModule` 的 `content` / AutoGLM 的 `think`）本来就传进来了，只是没地方放。
 *  2. **运行控制状态原先锁在悬浮窗实例里**（`pauseSignal` / `isCancelled`）⇒ 只有悬浮窗
 *     那几个按钮能暂停/结束。监视 Activity 要能控制，就必须把状态挪到一个共享的地方。
 *
 * 分工：
 *  - **状态**（[state]）：给 UI 读（悬浮窗的状态行、监视 Activity 的思考流）；
 *  - **运行控制**（[awaitState] / [togglePause] / [requestCancel]）：给 UI 写，由 agent 主循环等待。
 *
 * ⚠️ 刻意**不**把 `updateStatus(thought, action)` 的 `action` 放进 [entries]：
 * 那个参数里混着真动作（"点击坐标"）和纯相位标签（"决策中..."），在调用点区分不了，
 * 全塞进时间线只会变噪音。`action` 仍只用于"当前动作"那一行。
 */
object AgentRunMonitor {

    /** 任务相位。 */
    enum class Phase { IDLE, RUNNING, PAUSED, ENDED }

    enum class EntryKind { THOUGHT, ERROR }

    data class Entry(
        val kind: EntryKind,
        val text: String,
        val timestampMs: Long,
        /**
         * fork：**流式条目**（同轮内可被就地替换）所属的轮次号；非流式条目恒为 `null`。
         *
         * 存在的理由：引擎发来的 `AgentProgressUpdate.Streaming.content` 是**累积正文**
         * （见该数据类的 KDoc），而宿主过去每个 delta 都调一次 `publishModelOutput`
         * ⇒ 累积文本被当成**新条目**逐条追加，真机上表现为「第 1 行 1 个字、第 2 行 2 个字…」
         * 的三角形。现在同轮的连续流式发布**就地替换**本条目（见 [publishStreamingThought]），
         * 轮次边界由 [beginThoughtRound] 显式推进。
         */
        val streamingRound: Int? = null,
    )

    data class Snapshot(
        val phase: Phase = Phase.IDLE,
        /** 模块显示名（"AI 智能体" / "AutoGLM"）。 */
        val moduleLabel: String = "",
        val step: Int = 0,
        val maxSteps: Int = 0,
        /** 当前动作（来自 `updateStatus` 的第二参数）。 */
        val currentAction: String? = null,
        /**
         * 当前**阶段状态**（如"分析屏幕…"、"生成计划"）。
         *
         * ⚠️ 它和 [entries] 是**两条不同的通道**：
         *  - [statusText] 是模块写死的阶段文案 —— **不是**模型的思考；
         *  - [entries] 只装**模型输出的文字**。
         *
         * 真机反馈过：两者混在一起时，监视器里的"思考流"大半是"分析屏幕…""生成计划"这类噪音，
         * 用户以为这就是模型的思考过程。
         */
        val statusText: String? = null,
        val entries: List<Entry> = emptyList(),
        /**
         * fork：**流式轮次计数**（只服务「同轮替换 / 跨轮追加」这一条语义）。
         *
         * ⚠️ 它与 [step] **不是**一回事：`step` 是模块自己报的进度，本字段是**监视器侧**的
         * 流式分段边界，由宿主在每轮开头调 [beginThoughtRound] 推进一次
         * （漏调 ⇒ 后一轮的流式正文会把前一轮那条**顶掉**）。
         */
        val thoughtRound: Int = 0,
        /**
         * 用户已请求结束、但主循环还没走到取消检查点。
         *
         * ⚠️ 这个字段存在的唯一理由是**给 UI 可见反馈**：`cancelled` 原先只是个 `@Volatile` 布尔，
         * 不进 StateFlow ⇒ 点了「结束」之后 UI **什么都不会变**（主循环可能还在一次几十秒的
         * LLM 调用里），用户看到的就是「点了没反应」。
         */
        val cancelling: Boolean = false,
    ) {
        companion object {
            val IDLE = Snapshot()
        }
    }

    /**
     * 思考流上限。
     *
     * ⚠️ 必须有界：单步思考可能上千字，`max_steps` 默认 15（AutoGLM 30），但用户可以把
     * 工作流跑很久 —— 无界列表会一路吃内存。
     */
    private const val MAX_ENTRIES = 200

    private val _state = MutableStateFlow(Snapshot.IDLE)
    val state: StateFlow<Snapshot> = _state.asStateFlow()

    // ---- 运行控制（原 AgentOverlayManager 的实现搬到这里）----

    /** 不为 null 时表示暂停中，[awaitState] 会挂起等待它完成。 */
    @Volatile
    private var pauseSignal: CompletableDeferred<Unit>? = null

    @Volatile
    private var paused = false

    @Volatile
    private var cancelled = false

    val isPaused: Boolean get() = paused

    val isCancelled: Boolean get() = cancelled

    /** 一次运行开始：清空上一轮的思考流。 */
    fun begin(moduleLabel: String, maxSteps: Int) {
        cancelled = false
        paused = false
        pauseSignal = null
        _state.value = Snapshot(
            phase = Phase.RUNNING,
            moduleLabel = moduleLabel,
            maxSteps = maxSteps,
        )
    }

    /**
     * 一次运行结束。
     *
     * ⚠️ **保留**最后一次快照（只把 phase 置 ENDED）—— 跑完还能回看刚才 AI 想了什么，
     * 这正是"没有历史"要解决的问题。
     */
    fun finish() {
        val current = _state.value
        if (current.phase != Phase.IDLE) {
            _state.value = current.copy(phase = Phase.ENDED, currentAction = null, cancelling = false)
        }
        // 解锁，避免主循环卡在 awaitState 上
        pauseSignal?.complete(Unit)
        pauseSignal = null
        paused = false
    }

    /**
     * 发布一条**完整**思考文本（不截断）。截断只发生在 UI 侧。
     *
     * ⚠️ 语义 = **一次性文本**：永远追加、**永不**被替换。**累积的流式正文不要走这里**
     * （那正是「三角形」的成因），走 [publishStreamingThought]。
     */
    fun publishThought(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        appendEntry(EntryKind.THOUGHT, trimmed)
    }

    /**
     * fork：**新一轮开始** —— 宿主在每轮开头调一次（流式条目的轮次边界）。
     *
     * 语义：推进 [Snapshot.thoughtRound] ⇒ **下一次** [publishStreamingThought] 会**追加**新条目，
     * 而不是替换上一轮那条。本方法自身**不产生任何条目**（因此重复调用是无害的空转）。
     *
     * ⚠️ 漏调的后果：第 2 轮的流式正文会把第 1 轮那条**顶掉**（旧条目凭空消失）。
     * 生产调用点：`AgentModule` 的观察装配（每轮开头）、`AutoGLMModule` 的每步开头。
     */
    fun beginThoughtRound() {
        _state.update { it.copy(thoughtRound = it.thoughtRound + 1) }
    }

    /**
     * fork：发布**流式正文**（`text` 是**累积值**，不是增量 —— 引擎侧契约不变）。
     *
     * 语义：**同一轮内**连续调用**就地替换**同一条目（只留一条、文本取最新）；**跨轮**
     * （中间调过 [beginThoughtRound]）则**追加**新条目。非流式条目
     * （[publishThought] / [publishError]）永远不会被替换。
     *
     * ⚠️ 这正是「三角形」的修法：引擎的 `Streaming.content` 是累积正文，**不能**像
     * [publishThought] 那样逐条追加（老实现每个 delta 追加一条 ⇒ 第 1 行 1 个字、第 2 行 2 个字…）。
     */
    fun publishStreamingThought(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        _state.update { snapshot ->
            val last = snapshot.entries.lastOrNull()
            val now = System.currentTimeMillis()
            val updated = if (last != null && last.streamingRound == snapshot.thoughtRound) {
                // 同一轮：就地把那一条的文本换成最新累积值
                snapshot.entries.dropLast(1) + last.copy(text = trimmed, timestampMs = now)
            } else {
                // 跨轮（或本条是本轮的第一条）：追加
                snapshot.entries + Entry(
                    kind = EntryKind.THOUGHT,
                    text = trimmed,
                    timestampMs = now,
                    streamingRound = snapshot.thoughtRound,
                )
            }
            snapshot.copy(entries = updated.boundedByMaxEntries())
        }
    }

    /**
     * 发布**阶段状态**（模块写死的文案，如"分析屏幕…"）。
     *
     * ⚠️ **刻意不进 [entries]** —— 见 [Snapshot.statusText] 的说明。
     */
    fun publishStatus(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(statusText = trimmed) }
    }

    /** 发布一条错误/提示（如"需要人工接管"）。 */
    fun publishError(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        appendEntry(EntryKind.ERROR, trimmed)
    }

    /** 只更新"当前动作"，不进时间线（原因见类注释）。 */
    fun publishAction(text: String?) {
        val trimmed = text?.trim().orEmpty()
        if (trimmed.isEmpty()) return
        _state.update { it.copy(currentAction = trimmed) }
    }

    /** 更新步数（如 `3/15`）。 */
    fun publishStep(step: Int, maxSteps: Int) {
        _state.update { it.copy(step = step, maxSteps = maxSteps) }
    }

    private fun appendEntry(kind: EntryKind, text: String) {
        _state.update { snapshot ->
            snapshot.copy(
                entries = (snapshot.entries + Entry(kind, text, System.currentTimeMillis()))
                    .boundedByMaxEntries(),
            )
        }
    }

    /**
     * 思考流必须有界（见 [MAX_ENTRIES]）。
     *
     * ⚠️ 「追加」与「替换」两条路径**都要**过这里：只给追加那条加裁剪的话，
     * 长任务下流式条目会把列表撑破（流式路径每轮只加一条，但它同样会累积）。
     */
    private fun List<Entry>.boundedByMaxEntries(): List<Entry> =
        if (size > MAX_ENTRIES) takeLast(MAX_ENTRIES) else this

    /**
     * agent 主循环每轮调用的闸门：暂停时挂起，被取消时抛 [CancellationException]。
     *
     * ⚠️ 语义与搬来之前**逐字一致**（原 `AgentOverlayManager.awaitState`）——
     * 两个模块的 4 处调用点因此一行都不用改。
     */
    suspend fun awaitState() {
        pauseSignal?.await()
        if (cancelled) {
            throw CancellationException("用户手动停止了任务")
        }
    }

    /** 暂停/继续。返回切换后的 `isPaused`。 */
    fun togglePause(): Boolean {
        if (paused) {
            paused = false
            pauseSignal?.complete(Unit)
            pauseSignal = null
        } else {
            paused = true
            pauseSignal = CompletableDeferred()
        }
        _state.update { it.copy(phase = if (paused) Phase.PAUSED else Phase.RUNNING) }
        return paused
    }

    /**
     * 请求结束任务。幂等。
     *
     * ⚠️ 必须**先解锁**再置 `cancelled`：若当前处于暂停态，主循环正卡在 `pauseSignal.await()`，
     * 不解锁就永远走不到 [awaitState] 里的取消检查（原实现注释里的原话）。
     */
    fun requestCancel() {
        pauseSignal?.complete(Unit)
        pauseSignal = null
        paused = false
        cancelled = true
        // 立刻把"正在停止"反映到 UI —— 主循环要等下一次 awaitState 才真的退出
        _state.update { it.copy(cancelling = true) }
    }

    /** 仅测试用：把总线复位成初始态。 */
    internal fun resetForTest() {
        pauseSignal = null
        paused = false
        cancelled = false
        _state.value = Snapshot.IDLE
    }
}
