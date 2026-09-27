package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.IHookCallback
import com.chaomixian.vflow.xposed.wire.ActivityPayload
import com.chaomixian.vflow.xposed.wire.EventEnvelope
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.chaomixian.vflow.xposed.wire.HookConditionWire
import java.security.SecureRandom
import java.util.concurrent.atomic.AtomicLong

/**
 * App 侧的**通道控制器**：持有与 hook 层的连接、装配下行条件、路由上行事件。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.2（App 侧 Handler 的职责）。
 *
 * ## 职责边界（§3.2 的硬约束）
 *
 * | 做 | 不做 |
 * |---|---|
 * | 下发过滤条件（触发器增删时） | ❌ 不直接用 hook 能力 |
 * | 接收上报、**按 `TriggerSpec.parameters` 过滤** | ❌ 不让 hook 层做业务判定 |
 * | 触发 `executeTrigger`（后续阶段接入） | |
 *
 * **判定全在 App 侧** ⇒ 「Hook 层不知道工作流的存在」；改规则根本不需要碰 hook 层。
 *
 * ## 单例而非注入
 *
 * `HookChannelService`（binder 线程）与 `ActivityChangedTriggerHandler`（协程）
 * 都要用它，且 Service 生命周期与 Handler 独立 —— 用进程级单例最简单。
 * 本仓库 `LogcatCaptureController` 也是这个形态。
 *
 * ## 线程模型
 *
 * - [onReport] 跑在 **binder 线程**：只做「解析 + 校验 + 分派」，不做耗时操作。
 * - 下发 [pushConditions] 可能从任意线程调用，内部同步。
 */
object HookChannelController {

    private const val TAG = "HookChannelController"

    /** 当前连上来的 hook 层回调。为 null 表示未连接。 */
    @Volatile
    private var callback: IHookCallback? = null

    /**
     * 下行鉴权 token。**每次 hook 层重新连接就换一个新的** ——
     * hook 层进程重启后旧 token 即作废，避免长期有效的凭据被复用。
     */
    @Volatile
    private var token: String = ""

    /**
     * 已收到的最新 `seq`。用于**丢包检测**。
     *
     * ⚠️ 没有它就只能看到「这段时间没有事件」，分不清
     * 「真的没有 activity 切换」与「事件被丢了」（§3.4.2）。
     */
    private val lastSeq = AtomicLong(-1L)

    /** 事件分派回调。后续阶段由 `ActivityChangedTriggerHandler` 注册。 */
    /**
     * **按 topic 分发**的事件消费者注册表。
     *
     * ## ⚠️⚠️ 为什么必须是注册表而不是单个槽位
     *
     * 原实现是 `var eventSink: ((EventEnvelope) -> Unit)?` + `setEventSink()`，
     * 语义是**后注册的覆盖先注册的**。只有一个消费者（`ActivityChangedTriggerHandler`）时
     * 看不出问题，但**第二个 hook 触发器一加就出事**：
     *
     * ```
     * ActivityChangedTriggerHandler.start() → setEventSink(activity 的)
     *     KeyComboTriggerHandler.start()    → setEventSink(组合键的)   ← 覆盖
     *     ⇒ Activity 触发器静默不再收到任何事件
     * ```
     *
     * 而且**双向**：`TriggerHandlerRegistry` 按注册顺序建实例再统一 `start()`，
     * 所以**注册顺序靠后的那个会赢** —— 改一行注册顺序就换一个触发器失灵。
     *
     * 表现是「新加的触发器能用，原来那个不触发了」，而
     * `HookSource` / `HookRuntime` 那两层**看起来毫无问题**（它们确实没问题，
     * 坏的是这里）—— 属于最难查的那类。
     *
     * ⚠️ 按 **topic** 做 key 而不是「注册多个无差别回调」：
     * 每个 hook 触发器只关心自己的 topic（§3.4.2 的主题路由），
     * 无差别广播会让每个 Handler 都要自己过滤一遍，且容易漏判。
     */
    private val eventSinks = java.util.concurrent.ConcurrentHashMap<String, (EventEnvelope) -> Unit>()

    /**
     * 注册某 topic 的事件消费者。**幂等**（同 topic 重复注册会替换）。
     *
     * @param topic 该消费者关心的事件主题，如 `ActivityPayload.TOPIC`
     */
    fun registerSink(topic: String, sink: (EventEnvelope) -> Unit) {
        eventSinks[topic] = sink
    }

    /** 注销某 topic 的消费者（触发器被移除时调）。 */
    fun unregisterSink(topic: String) {
        eventSinks.remove(topic)
    }

    /** 最近一次收到的丢弃计数（用于诊断展示）。 */
    @Volatile
    private var lastDroppedReported: Long = 0

    /**
     * App Context。用于记录「曾经连上过」（[XposedCapability] 的判据）。
     *
     * ⚠️ 存 applicationContext（不是 Activity）—— 这个单例活得比任何界面都长。
     */
    @Volatile
    private var appContext: android.content.Context? = null

    /** 由 [com.chaomixian.vflow.services.HookChannelService] 在其 onCreate 里注入。 */
    fun attach(context: android.content.Context) {
        appContext = context.applicationContext
    }


    /** hook 层连上来了。**由 binder 线程调用**。 */
    fun onCallbackRegistered(cb: IHookCallback): Boolean {
        callback = cb
        _connected.value = true
        token = newToken()
        lastSeq.set(-1L)
        DebugLogger.i(TAG, "hook 层已连接，已生成新 token")

        // ⚠️ 这里**不再**记录「曾经连上过」——
        // 能力判据已改成**实时**（框架是否连着），不再依赖持久化标记。
        // 原因见 XposedCapability 的类注释：「永久授权」会让权限页在模块被停用后
        // 仍显示「已授权」，且与 vFlow 其他所有权限判据不一致。

        // ⚠️ 连上后立刻通知业务层重下发条件 —— hook 层可能刚重启、
        // 内存里的条件已被清空（决策 14：条件不落盘）。
        // 不做这件事的话，用户会看到「明明配了触发器，但重启后不触发」
        try {
            onConnected?.invoke()
        } catch (t: Throwable) {
            DebugLogger.w(TAG, "连接回调失败：${t.javaClass.simpleName} ${t.message}")
        }
        return true
    }

    /** hook 层断开。 */
    fun onCallbackUnregistered() {
        callback = null
        _connected.value = false
        token = ""
        // 不换 token：这里是「断开」，重新连上时会换（见 onCallbackRegistered）
        DebugLogger.i(TAG, "hook 层已断开")
    }

    /**
     * L0 连接状态的**可订阅**版本。
     *
     * ⚠️ 为什么需要它：UI（首页状态卡）必须能 `collect` 变化，
     * 而 [isConnected] 是同步查询、没有通知机制。
     *
     * ⚠️ **它与框架状态（L1-L3）是两件独立的事** ——
     * 框架好好的但本连接断了，是**最容易被误判成「框架问题」**的一格。
     * 见 [XposedState.evaluate] 的说明。
     */
    private val _connected = MutableStateFlow(false)
    val connected: StateFlow<Boolean> = _connected.asStateFlow()

    /** 连接状态（同步查询版）。这是「hook 挂载态」判据的来源（§3.3 状态位 B）。 */
    fun isConnected(): Boolean = callback != null

    /**
     * 连接**建立**时的回调。
     *
     * ⚠️ 为什么必须有：hook 层重启（或热更新换代）后，它内存里的条件**是空的**
     * —— 条件只存在于它的内存中（决策 14：不落盘）。
     * 不在这里重下发的话，用户会遇到「重启后触发器再也不触发」，
     * 而通道看起来是活的（心跳正常）。
     *
     * 由 `ActivityChangedTriggerHandler` 注册，用来重推当前条件。
     */
    @Volatile
    private var onConnected: (() -> Unit)? = null

    /** 注册连接建立回调（后注册的覆盖先注册的）。 */
    fun setOnConnectedListener(listener: (() -> Unit)?) {
        onConnected = listener
    }

    /**
     * 下发条件（**全量替换**）+ token。
     *
     * ⚠️ **空条件下也要下发** —— 空串的语义是「没有触发器了，把 hook 卸掉」。
     * 不下发会让 hook 层白挂着 hook 点、白担崩溃风险。
     *
     * @return 是否已被 hook 层接受。**false 绝不静默** ——
     *   下不去意味着触发器不会工作，而用户只会看到「没反应」
     *   （`LogcatTriggerHandler.pushConditionsToCore` 的同一条纪律）
     */
    fun pushConditions(conditionsJson: String): Boolean {
        val cb = callback ?: run {
            DebugLogger.w(TAG, "条件下发失败：hook 层未连接（当前 ${conditionsJson.length} 字符）")
            return false
        }
        val t = token
        return try {
            val ok = cb.pushConditions(conditionsJson, t)
            if (!ok) {
                DebugLogger.w(TAG, "⚠️ hook 层接受了连接但拒绝了下发 —— 检查鉴权")
            }
            ok
        } catch (t2: Throwable) {
            // 连接可能已死但 onServiceDisconnected 还没到（无 FIN 时不触发）
            DebugLogger.w(TAG, "条件下发异常：${t2.javaClass.simpleName} ${t2.message}")
            false
        }
    }

    /**
     * 收到一条上行事件。**由 binder 线程调用**。
     *
     * 顺序：解析 → 校验 token → 丢包检测 → 分派。
     * **任何一步失败都只记日志、绝不抛** —— 抛异常会跨国界且无人处理。
     */
    fun onReport(envelopeJson: String) {
        val envelope = EventEnvelopeCodec.decode(envelopeJson) ?: run {
            // 未知 / 坏格式必须**忽略而非崩溃**（§3.4.5）
            DebugLogger.w(TAG, "收到无法解析的信封，已忽略（长度 ${envelopeJson.length}）")
            return
        }

        // ── 上行鉴权：token 是唯一依据 ──
        //
        // ⚠️⚠️ **必须先判「本侧有没有 token」**，不能直接比较。
        // 未连接时本侧 token 是空串，而伪造者送 `token:""` 也是空串 ——
        // 空串比空串**恒等**，于是无凭证的信封会被放行。
        // 这是本类的第一条测试跑出来的真实漏洞，不是理论问题。
        if (token.isEmpty()) {
            DebugLogger.w(TAG, "收到事件但本侧无 token（未连接），已丢弃（topic=${envelope.topic}）")
            return
        }

        // 用恒定时间比较，避免通过响应时间差逐字节猜测。
        // 不打印 token 内容（日志可能被导出）
        if (!constantTimeEquals(envelope.token, token)) {
            DebugLogger.w(TAG, "信封 token 校验失败，已丢弃（topic=${envelope.topic}）")
            return
        }

        // ── 丢包检测 ──
        val prev = lastSeq.get()
        if (prev >= 0 && envelope.seq > prev + 1) {
            val missed = envelope.seq - prev - 1
            DebugLogger.w(TAG, "⚠️ 事件序号跳跃：${prev} → ${envelope.seq}，丢失约 $missed 条")
        }
        lastSeq.set(envelope.seq)

        // ── 丢弃计数上报 ──
        // hook 层把「自上次上报以来丢了多少」附在信封里（§3.4.4）。
        // ⚠️ 必须显式告知用户，否则他只会看到「触发器偶尔没反应」，
        // 而这正是最难查的那类问题
        if (envelope.droppedCount > 0) {
            lastDroppedReported = envelope.droppedCount
            DebugLogger.w(
                TAG,
                "hook 层事件过载：已丢弃 ${envelope.droppedCount} 条。" +
                    "事件产生速率过高，考虑收窄触发条件或加长冷却。"
            )
        }

        // ── 协议版本 ──
        if (envelope.protocolVersion != EventEnvelopeCodec.PROTOCOL_VERSION) {
            // 不拒绝：payload「只加不改不删」，版本不同仍可能能处理。
            // 但要留痕 —— 用户升级 App 后 hook 层仍是旧版本是常态（§3.4.5）
            DebugLogger.w(
                TAG,
                "协议版本不一致：hook=${envelope.protocolVersion} app=${EventEnvelopeCodec.PROTOCOL_VERSION}"
            )
        }

        // ── 按 topic 分发 ──
        // ⚠️ 未知 topic **忽略而非崩溃**（§3.4.5：旧 App + 新 hook 层是常态）。
        // 这也顺带让「加第二个 hook 触发器」不必改这里 —— 它自己注册自己的 topic
        val sink = eventSinks[envelope.topic]
        if (sink == null) {
            DebugLogger.d(TAG, "收到事件但该 topic 无消费者（${envelope.topic}），已忽略")
            return
        }
        try {
            sink(envelope)
        } catch (t: Throwable) {
            // ⚠️ 一个消费者抛异常不该影响其他 consumer —— 但这里是按 topic 分发的，
            // 所以实际只影响它自己。留这条 catch 是为了不把异常抛到 binder 线程上
            DebugLogger.w(TAG, "事件消费者抛异常（${envelope.topic}）：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 最近一次上报的丢弃计数，供诊断展示。 */
    fun lastReportedDroppedCount(): Long = lastDroppedReported

    /**
     * 收到 `activity_changed` 事件时的默认处理。
     *
     * ⚠️ P2 阶段**故意只打日志、不接触发器** ——
     * 这样「采集对了但没触发」与「采集没对」不会混在一起
     * （否则出问题时无从判断该查哪一层）。
     * P3 会把它替换成 `ActivityChangedTriggerHandler` 的分发。
     */
    private fun logActivityEvent(envelope: EventEnvelope) {
        val event = ActivityPayload.decode(envelope.payloadJson) ?: run {
            DebugLogger.w(TAG, "activity_changed 载荷无法解析，已忽略")
            return
        }
        // 这些字段正是本通道存在的理由（前三条通道拿不到 intent extras）
        DebugLogger.i(
            TAG,
            "📱 activity: ${event.component}" +
                " intent=${event.intentUri.take(120)}" +
                (if (event.truncated) " ⚠️已截断" else "") +
                " extras=${event.extrasJson.take(200)}"
        )
    }

    /**
     * 安装**兜底**事件消费者（只打日志）。
     *
     * ⚠️ **它不负责订阅**。订阅由
     * [com.chaomixian.vflow.core.workflow.module.triggers.handlers.ActivityChangedTriggerHandler]
     * 按「用户实际配了哪些触发器」下发 ——
     * 那才是唯一知道「该采什么」的地方（§3.2：判定权在 App 侧的业务层）。
     *
     * P2 阶段这里还兼做「无条件全订阅」，那是当时的临时手段；
     * P3 接入 Handler 后已移除 —— 否则两者会打架
     * （Handler 想「没有触发器就卸下 hook」，兜底却坚持「全订阅」，结果是永远在采）。
     */
    fun installDefaultSink() {
        // ⚠️ 用 registerSink 且**只在没有真消费者时才装** ——
        // 真消费者（`ActivityChangedTriggerHandler`）注册后会**替换**它，
        // 所以这里不需要额外的判断（注册表语义是「同 topic 替换」）
        if (!eventSinks.containsKey(ActivityPayload.TOPIC)) {
            registerSink(ActivityPayload.TOPIC) { envelope -> logActivityEvent(envelope) }
        }
    }

    /**
     * **仅供测试**：注入一个已知的 token 并置为「已连接」。
     *
     * ⚠️ 为什么需要它：正常路径的 token 由 `SecureRandom` 生成，
     * **测试拿不到** ⇒ 所有 `onReport` 调用都会在 token 校验那步 return ⇒
     * **测试永远走不到分发逻辑**。
     *
     * 这不是多此一举 —— 我第一版就是没意识到这点，写了一组「看起来在测分发、
     * 实际什么都没测」的用例，**反证时不变红**才发现。
     * （本仓库有同类教训：测试要经过调用点，否则反证不会变红。）
     */
    internal fun injectTokenForTest(knownToken: String) {
        token = knownToken
        _connected.value = true
    }

    /**
     * 生成 token。
     *
     * ⚠️ 用 `SecureRandom` 而非 `Random`：这个值决定「谁的话算数」，
     * 可预测的 token 等于没有 token。
     */
    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * 恒定时间字符串比较。
     *
     * 逐字符 `==` 会在第一个不同的字符处返回，**响应时间就泄漏了前缀信息**。
     * 对固定长度 token 而言这是可被逐字节爆破的。
     */
    internal fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }
}
