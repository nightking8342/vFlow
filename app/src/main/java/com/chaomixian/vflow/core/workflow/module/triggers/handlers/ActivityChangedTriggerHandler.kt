package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.ActivityChangedTriggerData
import com.chaomixian.vflow.core.workflow.module.triggers.ActivityChangedTriggerModule
import com.chaomixian.vflow.core.xposed.HookChannelController
import com.chaomixian.vflow.xposed.wire.ActivityPayload
import com.chaomixian.vflow.xposed.wire.HookConditionWire
import kotlinx.coroutines.launch
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `activity_changed` 触发器处理器。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.2 / §3.4.3。
 *
 * ## ⚠️⚠️ 为什么必须继承 [BaseTriggerHandler]，**不能**用 `ListeningTriggerHandler`
 *
 * 与 `LogcatTriggerHandler` 同一条教训（那个类的类注释里写着，这里必须照做）：
 *
 * `ListeningTriggerHandler` 把 `start` / `stop` / `addTrigger` / `removeTrigger`
 * 四个方法全部 `final override` 了，子类只能实现 `startListening` / `stopListening`，
 * 而这两个**只在「空↔非空」的边界触发**。
 *
 * 后果很具体：**已有 1 个触发器时再加第 2 个，hook 层永远不知道** ——
 * 条件下的发是「全量替换」语义，不重发就还是旧的那一份，
 * 于是**第 2 个触发器静默不触发**。这正是本仓库反复记录的那类最难查的失效。
 *
 * ## 条件下发是「全量替换」
 *
 * 任一触发器变化 → 汇总当前**所有** activity 触发器的过滤条件 → 整体下发。
 * 好处是无状态同步问题：hook 层不必记「上次是什么」，App 侧也不必算增量。
 *
 * ## ⚠️ 过滤在两边都做，各司其职
 *
 * - **hook 层**：只按「包是否被关心」粗筛（[HookConditionWire] 的语义）。
 *   这是为了避免 hook 所有进程、避免全量上报洪泛。
 * - **App 侧（本类）**：按 `TriggerSpec.parameters` 精确匹配（类名/匹配方式/冷却）。
 *
 * **判定权在 App 侧** —— hook 层不知道工作流的存在（§3.2 硬约束）。
 */
class ActivityChangedTriggerHandler : BaseTriggerHandler() {

    companion object {
        private const val TAG = "ActivityChangedTrigger"

        /** 未配置冷却时的默认值（与模块的 defaultValue 一致）。 */
        private const val FALLBACK_COOLDOWN_MS = 1000L

        /**
         * 在 `HookChannelController` 的「连接建立」注册表里的 key（修缺陷 1）。
         *
         * ⚠️ 必须**每个消费者唯一** —— 用类名，将来加第二个 hook 触发器时
         * 照抄本行换掉即可，不会与别人互相覆盖。
         */
        private const val SINK_KEY = "ActivityChangedTriggerHandler"
    }

    /** 当前监听中的触发器。每次增删都重建，因此用写时复制容器。 */
    private val listeningTriggers = CopyOnWriteArrayList<TriggerSpec>()

    private var appContext: Context? = null

    /**
     * 冷却记录。**按触发器 id 独立计数**。
     *
     * ⚠️ 每个触发器的 `cooldown_ms` 可以不同，所以不能用全局窗口
     * （那样配了 `0` = 不冷却的触发器会被默认窗口挡住，等于配置没生效）。
     * 做法照 `LogcatTriggerHandler`：自己记时间戳，判定与记账在同一处完成。
     */
    private val lastTriggeredAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    // ── 生命周期 ────────────────────────────────────────────────

    override fun start(context: Context) {
        super.start(context)
        appContext = context.applicationContext

        // ⚠️ 接管事件消费者。P2 的默认消费者（只打日志）会被这里替换掉。
        // setEventSink 是「后注册的覆盖先注册的」，所以顺序无妨 ——
        // 但 TriggerService 必须在 HookChannelService 之后启动才稳；
        // 为保险，这里主动再设一次
        // ⚠️ 用 registerSink（按 topic）而不是原来的 setEventSink（单槽位覆盖）——
        // 后者会让「第二个 hook 触发器注册时把本处理器挤掉」，
        // 表现为「新触发器能用、Activity 触发器静默失效」
        HookChannelController.registerSink(ActivityPayload.TOPIC) { envelope -> onEnvelope(envelope) }
        // ⚠️ hook 层重启/换代后它内存里的条件会清空（条件不落盘），
        // 必须在这里重推 —— 否则「重启后触发器再也不触发」而通道看着是活的
        HookChannelController.setOnConnectedListener(SINK_KEY) { syncToChannel() }
        DebugLogger.i(TAG, "已接管 hook 事件消费")

        // 若此刻已经连着，立刻把当前条件下发一次
        if (HookChannelController.isConnected()) syncToChannel()
    }

    override fun stop(context: Context) {
        // ⚠️ 顺序很重要：BaseTriggerHandler.stop() 会 triggerScope.cancel()，
        // 因此**必须先把清理动作发出去**，再调 super ——
        // 否则协程已被取消，下发「空条件」的调用发不出去，
        // hook 层会白挂着 hook 点、白担崩溃风险
        triggerScope.launch { pushConditionsToChannel(emptyList()) }

        listeningTriggers.clear()
        lastTriggeredAt.clear()
        // ⚠️ 只注销**自己的 topic** —— 原实现 setEventSink(null) 是「清空唯一槽位」，
        // 在注册表语义下会**误伤其他消费者**
        HookChannelController.unregisterSink(ActivityPayload.TOPIC)
        // ⚠️ 必须带 key（修缺陷 1）—— 原 `setOnConnectedListener(null)` 是
        // 「清空唯一槽位」的语义，在注册表下会**误伤其他消费者**
        HookChannelController.removeOnConnectedListener(SINK_KEY)
        appContext = null

        super.stop(context)
    }

    // ── 触发器增删 ★ 每次都要重下发 ──────────────────────────────

    override fun addTrigger(context: Context, trigger: TriggerSpec) {
        appContext = context.applicationContext

        listeningTriggers.removeAll { it.triggerId == trigger.triggerId }
        listeningTriggers.add(trigger)

        DebugLogger.d(TAG, "addTrigger: ${trigger.triggerId}，当前共 ${listeningTriggers.size} 个")
        // ⚠️ 每次增删都要重发 —— 全量替换语义，不重发第 2 个触发器就收不到
        syncToChannel()
    }

    override fun removeTrigger(context: Context, triggerId: String) {
        val removed = listeningTriggers.removeAll { it.triggerId == triggerId }
        if (!removed) return

        DebugLogger.d(TAG, "removeTrigger: $triggerId，当前共 ${listeningTriggers.size} 个")
        // 冷却记录也要清，否则 map 会随触发器增删无界增长
        lastTriggeredAt.remove(triggerId)
        syncToChannel()
    }

    /**
     * 仅登记触发器、**不触碰 Channel / Context** —— 供纯 JVM 单测用。
     *
     * ⚠️ 单独开一个方法而不是让测试调 [addTrigger]：后者会
     * `triggerScope.launch { pushConditionsToChannel(...) }`，
     * 那条路需要 Android 环境（`HookChannelController` 依赖 `DebugLogger`），
     * 在纯 JVM 测试里会炸。**这个方法的唯一用途是测试**。
     */
    internal fun addTriggerForTest(trigger: TriggerSpec) {
        listeningTriggers.removeAll { it.triggerId == trigger.triggerId }
        listeningTriggers.add(trigger)
    }

    /**
     * 汇总所有触发器的过滤条件并下发。
     *
     * ⚠️ **空列表也要下发** —— 空条件的语义是「没有订阅者了，把 hook 卸掉」。
     * 不下发会让 hook 层白挂着 hook 点。
     */
    private fun syncToChannel() {
        val topics = if (listeningTriggers.isEmpty()) {
            emptyList()
        } else {
            listOf(ActivityPayload.TOPIC)
        }

        triggerScope.launch { pushConditionsToChannel(topics, computePushdownPackages()) }
    }

    /**
     * 计算可以**下推给 hook 层做粗筛**的包名列表。
     *
     * ## ⚠️⚠️ 只要有一个触发器的包过滤「推不了」，就必须整体放弃下推
     *
     * 下推是**白名单**语义（[HookConditionWire]：hook 层只上报列表内的包）。
     * 所以一旦有触发器关心的包**不在列表里**，它的包就会被 hook 层丢掉
     * ⇒ 那个触发器**静默不触发**。
     *
     * 具体场景（这就是本方法存在的理由）：
     * ```
     * 触发器 A: package_filter = "com.foo"
     * 触发器 B: package_filter = "{{vars.pkg}}"     ← 含变量，推不了
     * ⇒ 下推 ["com.foo"] ⇒ B 想看的包全被丢 ⇒ B 永不触发
     * ```
     *
     * 而「空列表」的语义是**全部包**（[HookConditionWire] 的刻意设计）。
     * 所以推不了时的正确做法不是「尽力而为地推一部分」，而是
     * **返回空列表 = 不限包** —— 宁可多采由 App 侧过滤，
     * 不可漏采（漏采是静默失效，多采只是浪费）。
     *
     * ## 什么情况推不了
     *
     * - **包过滤为空** ⇒ 语义是「任意包」（见 `ActivityChangedTriggerModule`），
     *   等价于不限包 ⇒ 已覆盖全部 ⇒ 直接放弃下推
     * - **包过滤含 `{{`** ⇒ 是魔法变量/命名变量，**运行期才知道值**，
     *   静态下推必然错
     */
    internal fun computePushdownPackages(): List<String> {
        if (listeningTriggers.isEmpty()) return emptyList()

        val out = LinkedHashSet<String>()
        for (trigger in listeningTriggers) {
            val raw = (trigger.parameters[ActivityChangedTriggerModule.PARAM_PACKAGE_FILTER] as? String)
                ?.trim()
                .orEmpty()

            // 推不了 → 整体放弃（返回空 = 不限包）。
            // ⚠️ 不打印成 warn 级：这是**正常情况**（用户配了「任意」或用了变量），
            // 不是错误；但 debug 级要留痕，方便排查「为什么没下推」
            if (raw.isEmpty()) {
                DebugLogger.d(TAG, "有触发器的包过滤为空（=任意包）⇒ 放弃包下推，改为不限包")
                return emptyList()
            }
            if (raw.contains("{{")) {
                DebugLogger.d(TAG, "有触发器的包过滤含变量「$raw」⇒ 放弃包下推，改为不限包")
                return emptyList()
            }

            out.add(raw)
        }
        return out.toList()
    }

    private suspend fun pushConditionsToChannel(
        topics: List<String>,
        packages: List<String> = emptyList(),
    ) {
        val json = HookConditionWire.encode(topics, packages)
        val ok = HookChannelController.pushConditions(json)

        if (ok) {
            // ⚠️ **成功也要打日志**（原本只有失败才打）。
            // 没有它的话，「重连后自动重下发」这件事在日志里**完全不可见** ——
            // 而它恰恰是最需要确认的一步（不做的话触发器永远收不到条件）。
            DebugLogger.i(
                TAG,
                "条件已下发：${topics.size} 个 topic，${listeningTriggers.size} 个触发器"
            )
        } else {
            // ⚠️⚠️ **措辞必须区分「还没连上」与「配置错了」** ——
            // 这是实测暴露的问题：`TriggerService` 加载触发器**比 hook 层连接早**约 1.6 秒，
            // 于是首次下推**必然**失败，而旧文案却说「需在 LSPosed 中启用并重启设备」——
            // 用户的 LSPosed 配置其实完全正确，会被这条信息**误导去白折腾**。
            //
            // 正确的说法是「连接建立后会自动重下发」——因为那确实会发生
            // （`HookChannelController.onCallbackRegistered` → `onConnected` → 本方法）。
            DebugLogger.w(
                TAG,
                "条件下发失败：hook 层尚未连接（当前 ${listeningTriggers.size} 个触发器）。" +
                    "⚠️ 若只是启动时序问题，**连接建立后会自动重下发**，无需处理；" +
                    "若持续如此，才检查 LSPosed 中是否已启用 vFlow 并勾选「系统框架」。"
            )
        }
    }

    // ── 事件处理 ────────────────────────────────────────────────

    /**
     * 收到一条信封。
     *
     * ⚠️ 跑在 **binder 线程**上 —— 只做「解析 + 匹配 + 投递」，不阻塞。
     */
    private fun onEnvelope(envelope: com.chaomixian.vflow.xposed.wire.EventEnvelope) {
        if (envelope.topic != ActivityPayload.TOPIC) return

        val event = ActivityPayload.decode(envelope.payloadJson) ?: run {
            // 坏载荷忽略而非崩溃（§3.4.5）
            DebugLogger.w(TAG, "载荷无法解析，已忽略")
            return
        }

        val ctx = appContext ?: return
        val now = System.currentTimeMillis()

        for (trigger in listeningTriggers) {
            if (!matches(trigger, event)) continue

            val cooldownMs = cooldownOf(trigger)
            if (!tryAcquireCooldown(trigger.triggerId, cooldownMs, now)) continue

            DebugLogger.i(TAG, "触发工作流 '${trigger.workflowName}'（${event.component}）")

            executeTrigger(
                ctx,
                trigger,
                ActivityChangedTriggerData(
                    packageName = event.packageName,
                    className = event.className,
                    component = event.component,
                    intentUri = event.intentUri,
                    extrasJson = event.extrasJson,
                    truncated = event.truncated,
                ),
            )
        }
    }

    /**
     * 参数匹配。
     *
     * ⚠️ 空参数 = **任意**，不是「不匹配」。
     * 反过来会让「新建一个触发器还没填条件」变成「配了但永不触发」，
     * 而用户以为是环境问题。
     */
    internal fun matches(
        trigger: TriggerSpec,
        event: com.chaomixian.vflow.xposed.wire.ActivityEvent,
    ): Boolean {
        val pkgFilter = (trigger.parameters[ActivityChangedTriggerModule.PARAM_PACKAGE_FILTER] as? String)
            ?.trim().orEmpty()
        val clsFilter = (trigger.parameters[ActivityChangedTriggerModule.PARAM_CLASS_FILTER] as? String)
            ?.trim().orEmpty()
        val mode = (trigger.parameters["match_mode"] as? String)
            ?.let { normalizeMatchMode(it) } ?: ActivityChangedTriggerModule.MATCH_CONTAINS

        if (pkgFilter.isNotBlank() && !matchOne(pkgFilter, event.packageName, mode)) return false
        if (clsFilter.isNotBlank() && !matchOne(clsFilter, event.className, mode)) return false
        return true
    }

    /**
     * 归一化匹配方式。
     *
     * ⚠️ 旧数据里可能存的是**本地化文案**（「包含」/「精确」）——
     * 直接字符串比较会让它们全部落到默认值，表现为「用户明明选了精确匹配，
     * 实际按包含匹配」，且**不报错**。故这里手工兜一层
     * （模块的 `legacyValueMap` 只在编辑器路径生效，读参数不走它）。
     */
    internal fun normalizeMatchMode(raw: String): String = when (raw) {
        ActivityChangedTriggerModule.MATCH_EXACT, "精确", "精确匹配" ->
            ActivityChangedTriggerModule.MATCH_EXACT
        ActivityChangedTriggerModule.MATCH_CONTAINS, "包含", "包含匹配" ->
            ActivityChangedTriggerModule.MATCH_CONTAINS
        else -> ActivityChangedTriggerModule.MATCH_CONTAINS
    }

    private fun matchOne(filter: String, actual: String, mode: String): Boolean = when (mode) {
        ActivityChangedTriggerModule.MATCH_EXACT -> actual.equals(filter, ignoreCase = true)
        else -> actual.contains(filter, ignoreCase = true)
    }

    /**
     * 冷却判定 + 记账。**一次调用同时完成两件事**，不拆开 ——
     * 拆开会出现「判断完忘了记账」或「记了账但没触发」的错配。
     *
     * ⚠️ 冷却期内的命中**不记账**。若每次都记账，高频的 activity 切换
     * 会让窗口无限顺延，触发器在持续切换时**永远不会再触发**。
     *
     * @return true = 允许触发（并已记账）
     */
    internal fun tryAcquireCooldown(triggerId: String, cooldownMs: Long, nowMs: Long): Boolean {
        if (cooldownMs <= 0L) return true

        val last = lastTriggeredAt[triggerId]
        if (last != null && nowMs - last < cooldownMs) return false

        lastTriggeredAt[triggerId] = nowMs
        return true
    }

    internal fun cooldownOf(trigger: TriggerSpec): Long {
        val raw = trigger.parameters["cooldown_ms"]
        return when (raw) {
            is Number -> raw.toLong().coerceAtLeast(0L)
            is String -> raw.toLongOrNull()?.coerceAtLeast(0L) ?: FALLBACK_COOLDOWN_MS
            else -> FALLBACK_COOLDOWN_MS
        }
    }
}
