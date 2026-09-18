package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.content.Context
import com.chaomixian.vflow.core.logcat.DEFAULT_LOGCAT_COOLDOWN_MS
import com.chaomixian.vflow.core.logcat.LogcatTriggerCondition
import com.chaomixian.vflow.core.logcat.LogcatConditionWire
import com.chaomixian.vflow.core.logcat.LogcatFilterType
import com.chaomixian.vflow.core.logcat.LogLevel
import com.chaomixian.vflow.core.logcat.buildMatcher
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.LogcatTriggerModule
import com.chaomixian.vflow.services.VFlowCoreBridge
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList
import org.json.JSONObject

/**
 * logcat 触发器处理器。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §6、§7.1、§8。
 *
 * ## ⚠️ 为什么继承 [BaseTriggerHandler] 而不是 `ListeningTriggerHandler`
 *
 * 文档 §7.1 的结论，**必须照做**：
 *
 * `ListeningTriggerHandler` 把 `start` / `stop` / `addTrigger` / `removeTrigger`
 * 四个方法全部 `final override` 了，子类只能实现 `startListening` / `stopListening`，
 * 而这两个**只在"空↔非空"的边界触发**。
 *
 * 后果很具体：**已有 1 个触发器时再加第 2 个，Core 永远不知道**，
 * 于是第 2 个触发器**静默不触发**——正是 `trigger-system-overview.md` §8.2
 * 说的那类最难查的静默失效。
 *
 * 因此这里自己实现 `addTrigger` / `removeTrigger`，每次增删改都同步条件到 Core。
 *
 * ## 条件同步是"全量替换"（§6.2）
 *
 * 任一触发器变化 → 汇总当前**所有** logcat 触发器的条件 → 整体下发。
 * 好处是无状态同步问题，且**不需要重启 logcat 进程**
 * （因为 logcat 命令行本身不带过滤参数，过滤是 Core 内部的逻辑）。
 *
 * ## 冷却为什么不用签名去重
 *
 * `ClipboardTriggerHandler` 用的是"内容签名相同则跳过"。logcat 场景**不适用**：
 * 每次命中都是**新的日志行**，哪怕文字完全相同也是"又发生了一次"
 * （应用在循环重试就是典型）。按签名去重会把"第 2 次失败"当成"和上次一样"丢掉，
 * 而那恰恰是用户最想知道的。故用时间窗冷却（文档 §8）。
 */
class LogcatTriggerHandler : BaseTriggerHandler() {

    companion object {
        private const val TAG = "LogcatTriggerHandler"

        /** 断线重连的退避时长，照 `ClipboardTriggerHandler`。 */
        private const val RECONNECT_DELAY_MS = 1_000L

        /** 未配置冷却时的默认值。 */
        private val FALLBACK_COOLDOWN_MS = DEFAULT_LOGCAT_COOLDOWN_MS
    }

    /** 当前监听中的触发器。每次增删都重建，因此用写时复制的容器。 */
    private val listeningTriggers = CopyOnWriteArrayList<TriggerSpec>()

    private var appContext: Context? = null
    private var streamJob: Job? = null

    /**
     * 冷却记录。**按触发器 id 独立计数**（[LogcatCooldown] 内部按 id 分桶）。
     *
     * ⚠️ 每个触发器的 `cooldown_ms` 可以不同，而 `LogcatCooldown` 的窗口是
     * **构造期固定**的，因此不能直接用它做判定 —— 那样配了 `0`（不冷却）的触发器
     * 会被默认窗口挡住，而用户明明关掉了冷却。
     *
     * 做法：自己记时间戳，判定与记账都在 [tryAcquireCooldown] 里完成。
     */
    private val lastTriggeredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── 生命周期 ────────────────────────────────────────────────

    override fun start(context: Context) {
        super.start(context)
        appContext = context.applicationContext
    }

    override fun stop(context: Context) {
        // ⚠️ 顺序很重要：BaseTriggerHandler.stop() 会 triggerScope.cancel()，
        // 因此**必须先把清理动作发出去**，再调 super —— 否则协程已被取消，
        // 下发"空条件"的调用发不出去，Core 侧的 logcat 进程会变成孤儿
        // （`trigger-system-overview.md` §9.2 第 3 条）
        streamJob?.cancel()
        streamJob = null

        val ctx = appContext
        if (ctx != null) {
            // 空条件 = 让 Core 停掉 logcat 进程，别让它空转
            triggerScope.launch { pushConditionsToCore(emptyList()) }
        }

        listeningTriggers.clear()
        lastTriggeredAt.clear()
        appContext = null

        super.stop(context)
    }

    // ── 触发器增删 ★ 每次都要同步 ────────────────────────────────

    override fun addTrigger(context: Context, trigger: TriggerSpec) {
        appContext = context.applicationContext

        listeningTriggers.removeAll { it.triggerId == trigger.triggerId }
        listeningTriggers.add(trigger)

        DebugLogger.d(TAG, "addTrigger: ${trigger.triggerId}，当前共 ${listeningTriggers.size} 个")
        syncToCore()

        // 第一个触发器进来时启动流
        ensureStreamRunning()
    }

    override fun removeTrigger(context: Context, triggerId: String) {
        val removed = listeningTriggers.removeAll { it.triggerId == triggerId }
        if (!removed) return

        DebugLogger.d(TAG, "removeTrigger: $triggerId，当前共 ${listeningTriggers.size} 个")
        // 冷却记录也要清，否则 map 会随着触发器的增删无界增长
        lastTriggeredAt.remove(triggerId)
        syncToCore()

        if (listeningTriggers.isEmpty()) {
            streamJob?.cancel()
            streamJob = null
        }
    }

    /**
     * 汇总所有触发器的条件并下发给 Core。
     *
     * **空列表也要下发**——Core 据此停掉 logcat 进程（§7.1），
     * 否则日志量大的设备上会白耗 CPU。
     */
    private fun syncToCore() {
        val conditions = listeningTriggers.mapNotNull { toCondition(it) }
        triggerScope.launch { pushConditionsToCore(conditions) }
    }

    private suspend fun pushConditionsToCore(conditions: List<LogcatTriggerCondition>) {
        val ok = VFlowCoreBridge.updateLogcatTriggers(LogcatConditionWire.encodeConditions(conditions))
        if (!ok) {
            // 不静默：下不去意味着触发器不会工作，而用户只会看到"没反应"
            DebugLogger.w(TAG, "条件下发失败（Core 未连接？），当前 ${conditions.size} 条")
        }
    }

    /**
     * 把触发器参数翻译成 Core 侧的条件。
     *
     * @return null 表示该触发器的配置不合法（如正则写错），**跳过它但不影响其他触发器**。
     *   这比整体失败好：一个配错的触发器不该让其他触发器全部失效。
     */
    private fun toCondition(trigger: TriggerSpec): LogcatTriggerCondition? {
        val p = trigger.parameters

        val tagMatcher = buildMatcher(
            p["tag_filter_type"] as? String ?: LogcatFilterType.ANY,
            p["tag_filter_value"] as? String,
        ) ?: run {
            DebugLogger.w(TAG, "触发器 ${trigger.triggerId} 的 TAG 条件不合法（正则？），已跳过")
            return null
        }

        val messageMatcher = buildMatcher(
            p["message_filter_type"] as? String ?: LogcatFilterType.ANY,
            p["message_filter_value"] as? String,
        ) ?: run {
            DebugLogger.w(TAG, "触发器 ${trigger.triggerId} 的消息条件不合法（正则？），已跳过")
            return null
        }

        // 级别字符。参数存的是稳定常量（"I" 之类），不是本地化文案
        // 参数里存的是稳定常量（"I" 之类）。认不出时回退默认而非报错 ——
        // 这与 Core 侧解码时的严格不同：那边拒收非法值，这边是**生成**，
        // 回退到默认至少能让触发器工作
        val minLevel = (p["min_level"] as? String)?.firstOrNull()
            ?.let { LogLevel.fromChar(it) }
            ?: LogLevel.INFO

        return LogcatTriggerCondition(
            triggerId = trigger.triggerId,
            tagMatcher = tagMatcher,
            messageMatcher = messageMatcher,
            minLevel = minLevel,
        )
    }

    // ── 事件流 ──────────────────────────────────────────────────

    private fun ensureStreamRunning() {
        if (streamJob?.isActive == true) return

        streamJob = triggerScope.launch {
            while (isActive) {
                if (listeningTriggers.isEmpty()) return@launch

                if (!VFlowCoreBridge.ping()) {
                    delay(RECONNECT_DELAY_MS * 2)
                    continue
                }

                // 重连时**重发完整条件**（§6.5.3）——订阅帧自带 conditionList，
                // 因此重连语义天然正确，Core 不必保留上次状态
                val conditions = listeningTriggers.mapNotNull { toCondition(it) }

                val connected = VFlowCoreBridge.streamLogcatEvents(
                    conditionArray = LogcatConditionWire.encodeConditions(conditions)
                ) { event ->
                    handleCoreEvent(event)
                }

                if (!connected && isActive) {
                    DebugLogger.w(TAG, "logcat 事件流已断开，稍后重连")
                    delay(RECONNECT_DELAY_MS)
                }
            }
        }
    }

    /**
     * 处理一条来自 Core 的命中事件。
     *
     * Core 已经在那边判过一轮条件，但**这里仍要按 triggerId 定位并重新确认触发器还在**
     * ——条件下发与事件到达之间有窗口期，期间用户可能已经删掉了那个触发器。
     */
    private suspend fun handleCoreEvent(event: JSONObject) {
        if (event.optString("event") != "logcatMatch") return

        val triggerId = event.optString("triggerId").takeIf { it.isNotBlank() } ?: return
        val trigger = listeningTriggers.firstOrNull { it.triggerId == triggerId } ?: return

        // 自触发环：vFlow 自己的日志可能命中用户配的条件（如 message contains "error"），
        // 而触发的工作流又会打日志 → 无限循环。
        // Tasker 专门用 `grep -v "<myPid>"` 防这个（文档 §1.3），
        // 我们在这里按 pid 判，语义等价且不用下推
        if (shouldExcludeSelf(trigger) && isOwnProcess(event.optInt("pid", -1))) return

        val cooldownMs = cooldownOf(trigger)
        if (!tryAcquireCooldown(triggerId, cooldownMs, System.currentTimeMillis())) {
            return   // 冷却期内的命中直接丢弃，不补发（§8）
        }

        DebugLogger.i(TAG, "触发工作流 '${trigger.workflowName}'（tag=${event.optString("tag")}）")

        executeTrigger(
            appContext ?: return,
            trigger,
            VDictionary(
                mapOf(
                    "message" to VString(event.optString("message")),
                    "tag" to VString(event.optString("tag")),
                    "level" to VString(event.optString("level")),
                    "pid" to VNumber(event.optInt("pid", -1).toDouble()),
                    "raw" to VString(event.optString("raw")),
                )
            )
        )
    }

    /**
     * 冷却判定 + 记账。**一次调用同时完成两件事**，不拆开 ——
     * 拆开会出现"判断完忘了记账"或"记了账但没触发"的错配，
     * 而这类错配表现为冷却失效或触发器再也不触发，都不好查。
     *
     * ⚠️ **必须用触发器自己的 [cooldownMs]**，不能用全局默认值：
     * 用户把冷却设为 0 表示"不冷却"，用默认窗口挡他就等于配置没生效。
     *
     * ⚠️ 冷却期内的命中**不记账**。若把每次命中都记账，持续的高频日志
     * 会让窗口无限顺延，触发器在日志不停的情况下**永远不会再触发**。
     *
     * @return true = 允许触发（并已记账）
     */
    private fun tryAcquireCooldown(triggerId: String, cooldownMs: Long, nowMs: Long): Boolean {
        if (cooldownMs <= 0L) return true

        val last = lastTriggeredAt[triggerId]
        if (last != null && nowMs - last < cooldownMs) return false

        lastTriggeredAt[triggerId] = nowMs
        return true
    }

    private fun shouldExcludeSelf(trigger: TriggerSpec): Boolean =
        (trigger.parameters["exclude_self"] as? String) != LogcatTriggerModule.EXCLUDE_SELF_OFF

    /**
     * 判断某个 pid 是否属于 vFlow 自己。
     *
     * ⚠️ 只认**主进程**。Core 是独立进程（`com.chaomixian.vflow:core` 之类），
     * 它的日志同样可能命中用户条件——但那是**有意义的信息**（比如"Core 重启了"），
     * 而且 Core 不会因为触发工作流而再产生日志（它不执行工作流），
     * 所以不构成环。真正会成环的是主进程。
     */
    private fun isOwnProcess(pid: Int): Boolean =
        pid > 0 && pid == android.os.Process.myPid()

    /** 取该触发器自己的冷却时长。 */
    private fun cooldownOf(trigger: TriggerSpec): Long {
        val raw = trigger.parameters["cooldown_ms"]
        return when (raw) {
            is Number -> raw.toLong().coerceAtLeast(0L)
            is String -> raw.toLongOrNull()?.coerceAtLeast(0L) ?: FALLBACK_COOLDOWN_MS
            else -> FALLBACK_COOLDOWN_MS
        }
    }
}
