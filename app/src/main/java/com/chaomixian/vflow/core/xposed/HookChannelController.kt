package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.IHookCallback
import com.chaomixian.vflow.xposed.wire.ActivityPayload
import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityResponse
import com.chaomixian.vflow.xposed.wire.EventEnvelope
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.chaomixian.vflow.xposed.wire.HookConditionWire
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
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
        notifyOnConnected()
        return true
    }

    /**
     * 逐个通知「连接建立」的监听者（修缺陷 1）。
     *
     * ⚠️ 抽成独立方法有两个理由：
     * ① **一个失败不影响其他** —— 原实现只调单个槽位，且一个抛异常会让
     *    后面的都收不到；
     * ② **可测**：`IHookCallback.Stub` 继承 `android.os.Binder`，纯 JVM
     *    测试里**构造不出来**（`attachInterface` 未 mock）⇒ 测试无法经
     *    [onCallbackRegistered] 走这条路径，只能直接调本方法。
     *    这与本类既有的 `injectTokenForTest` 是同一种接缝。
     */
    internal fun notifyOnConnected() {
        for ((key, listener) in onConnectedListeners) {
            try {
                listener()
            } catch (t: Throwable) {
                DebugLogger.w(TAG, "连接回调失败（$key）：${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /**
     * hook 层断开。**由 binder 线程调用**。
     *
     * ## ⚠️⚠️ 必须带上「是哪条连接要断开」（修缺陷 13）
     *
     * 原先是无条件清空 `callback` + `token`。而**断开是异步投递的** ——
     * 实测（2026-09-29，MIX Fold 3）这条时序是**常态**而非边角：
     *
     * ```
     * 01:35:38.855  ActivityManager: unbindService …（旧 App 进程被杀后 AMS 才处理）
     * 01:35:39.858  Start proc: …vflow（新进程）
     * 01:35:40.002  onBind（新进程）
     * 01:35:40.007  hook 层已连接：callerUid=1000
     * ```
     *
     * ⇒ 只要那次 unbind 的投递**落到新连接的 `registerCallback` 之后**，
     * 它就会把**刚建立的新连接**清掉：hook 层 `host != null`（自认连着），
     * 而 App 侧 `callback == null` ⇒ **事件全丢**，且没有任何提示。
     *
     * 这在部署流程里是常态（重装 APK ⇒ 旧进程死 ⇒ 新进程接管）。
     *
     * @param which 要断开的那个 callback 实例（`IHookCallback` 的 binder 代理）。
     *   与当前 `callback` **不是同一个对象**时**直接忽略** —— 说明它是
     *   「已经被取代的旧连接」的迟到通知。
     *   ⚠️ 用 `!=` 比对象身份：`asInterface` 对同一个 binder 返回的代理
     *   可能是不同实例，故再补一层 `asBinder()` 比较（见下）。
     */
    fun onCallbackUnregistered(which: IHookCallback? = null) {
        val current = callback
        if (which != null && current != null && !sameCallback(which, current)) {
            // 不静默：这条日志是判断「13 是否真的发生过」的唯一途径
            DebugLogger.i(TAG, "收到【旧连接】的断开通知，已忽略（当前连接未受影响）")
            return
        }
        callback = null
        _connected.value = false
        token = ""
        // 不换 token：这里是「断开」，重新连上时会换（见 onCallbackRegistered）
        DebugLogger.i(TAG, "hook 层已断开")

        // ⚠️⚠️ **断连时必须立即唤醒全部 waiter**（§5.2 的定案）。
        //
        // 比等超时好得多：3 个并发调用 × 5 秒超时 = 用户白等，
        // 而此刻我们已经**确知**结果回不来了。
        //
        // ⚠️ 只在**真的清空了连接**时做 —— 上面那条「旧连接迟到通知」的
        // 分支已经 return 了，不会误伤（修缺陷 13 的那半）。
        failAllWaiters("hook 层已断开")
    }

    /**
     * 两个 `IHookCallback` 是否指向**同一个 binder**。
     *
     * ⚠️ 不能直接 `===`：`Stub.asInterface` 对同一个远程 binder 每次都可能
     * 返回**新的代理实例**，身份比较会误判为「不同连接」。
     * `asBinder()` 拿到的才是底层 binder（远程是 `BinderProxy`，其 `equals`
     * 按句柄比较）。
     */
    private fun sameCallback(a: IHookCallback, b: IHookCallback): Boolean = try {
        a.asBinder() == b.asBinder()
    } catch (_: Throwable) {
        // 取 binder 失败 ⇒ 保守判为「同一个」，走原有的清空路径。
        // 宁可多清一次（可恢复），也不要因为取不到 binder 就**拒绝**真的断开
        true
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
     * 连接**建立**时的回调注册表（**按 key 分发**）。
     *
     * ⚠️ 为什么必须有：hook 层重启（或热更新换代）后，它内存里的条件**是空的**
     * —— 条件只存在于它的内存中（决策 14：不落盘）。
     * 不在这里重下发的话，用户会遇到「重启后触发器再也不触发」，
     * 而通道看起来是活的（心跳正常）。
     *
     * ## ⚠️⚠️ 为什么是注册表而不是单个槽位（修缺陷 1）
     *
     * 原实现是 `var onConnected: (() -> Unit)?` + `setOnConnectedListener()`，
     * 语义是**后注册的覆盖先注册的** —— 与 `eventSinks` 修的那次（缺陷 ⑧）
     * **是同一个缺陷**，只是当时只修了一半。
     *
     * ```
     * ActivityChangedTriggerHandler.start() → setOnConnectedListener(自己的)
     *     KeyComboTriggerHandler.start()    → setOnConnectedListener(自己的)   ← 覆盖
     *     ⇒ Activity 触发器【再也不重下发条件】
     * ```
     *
     * 而且它是**双向的**：`TriggerHandlerRegistry` 按注册顺序建实例再统一
     * `start()` ⇒ **注册顺序靠后的那个会赢**，改一行顺序就换一个触发器失灵。
     *
     * ⚠️ 用 **key**（各 Handler 用自己的标识）而不是「追加到一个列表」：
     * 与 `eventSinks` 按 topic 做 key 同源 —— 注销时要能**精确摘掉自己**，
     * 否则 `stop()` 里的移除会误伤别人。
     */
    private val onConnectedListeners =
        java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** 注册连接建立回调。**幂等**（同 key 重复注册会替换）。 */
    fun setOnConnectedListener(key: String, listener: () -> Unit) {
        onConnectedListeners[key] = listener
    }

    /**
     * 注销连接建立回调。
     *
     * ⚠️ 必须带 key（修缺陷 1）—— 原签名 `setOnConnectedListener(null)`
     * 是「清空唯一槽位」的语义，在注册表下**会误伤其他消费者**。
     */
    fun removeOnConnectedListener(key: String) {
        onConnectedListeners.remove(key)
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

    // ════════════════════ ③ 能力调用（配对表） ════════════════════
    //
    // 设计文档：docs/fork/xposed-architecture-v2.md §5.2 / §3.4。
    //
    // ## ⚠️ 本任务只落地「配对表 + 断连唤醒 + 迟到响应丢弃」
    //
    // 超时丢弃与 await 语义属于**任务 2（app_runtime）**。这里提供的是
    // 它能接上的**缝** —— 不提供的话，任务 2 要么重复造一套，
    // 要么会重演「写了调用点注释但没有调用点」（本仓库反模式 6）。

    /**
     * 配对表：`request_id → waiter`。
     *
     * ## ⚠️ 为什么必须有界
     *
     * 若 hook 侧卡死而 App 侧超时放弃，waiter 不清理就是**泄漏 + 错配** ——
     * 这是 `EventQueue` 的同一条教训（`FORK.md` 记着 `LogcatStreamWrapper`
     * 因无断流检测留下的同类残留）。
     *
     * ⚠️ **容量上限由任务 2 的超时机制共同保证**（逐调用 `timeout_ms` + 兜底默认值），
     * 本类只负责「可注册 / 可注销 / 可批量失败」。这里额外加一道
     * [MAX_WAITERS] 的硬闸，避免任务 2 漏了清理时**无界累积** ——
     * 上限到了就拒绝新注册（调用方拿到 `handler_error`），而不是 OOM。
     *
     * ## ⚠️ §10-#17：配对表**按连接分桶**
     *
     * 设计口径是 `connectionId + request_id`。当前只有单连接，
     * 所以用「连接代次」做桶键即可 —— 断开时代次 +1，
     * **迟到的旧连接响应就配不上新连接的 waiter**（否则伪造/串号会被静默接受）。
     */
    private val waiters = ConcurrentHashMap<String, (CapabilityResponse) -> Unit>()

    /** 配对表容量硬闸（见 [waiters] 的说明）。 */
    private const val MAX_WAITERS = 64

    /**
     * 连接代次。**每次断开 +1**。
     *
     * ⚠️ 它让「配对表按连接分桶」这件事在单连接下也能成立：
     * 注册 waiter 时把代次编进 key，换代后旧 key 永远配不上
     * ⇒ 迟到的响应被天然丢弃（而不是落到新连接的 waiter 上）。
     */
    private val connectionGeneration = AtomicLong(0)

    /**
     * 注册一个等待中的调用。
     *
     * @return 是否注册成功。**false 绝不静默** —— 调用方必须把它转成
     *   `handler_error` 的响应返回给用户，而不是继续等一个永远不会来的结果。
     */
    fun registerWaiter(requestId: String, sink: (CapabilityResponse) -> Unit): Boolean {
        if (requestId.isBlank()) return false
        if (waiters.size >= MAX_WAITERS) {
            DebugLogger.w(TAG, "配对表已满（$MAX_WAITERS），拒绝新调用（requestId=$requestId）")
            return false
        }
        waiters[bucketKey(requestId)] = sink
        return true
    }

    /**
     * 注销一个 waiter（调用方拿到结果 / 超时后调）。
     *
     * ⚠️ **必须调** —— 不调会泄漏（见 [waiters] 的说明）。
     */
    fun unregisterWaiter(requestId: String) {
        waiters.remove(bucketKey(requestId))
    }

    /** 当前在途调用数。供诊断与测试。 */
    fun pendingWaiterCount(): Int = waiters.size

    /**
     * ⚠️ **仅供测试**：重置连接代次与配对表。
     *
     * 单例状态会在用例之间串（与 `injectTokenForTest` 同源的接缝）。
     */
    internal fun resetWaitersForTest() {
        waiters.clear()
        connectionGeneration.set(0L)
    }

    private fun bucketKey(requestId: String): String =
        "${connectionGeneration.get()}:$requestId"

    /**
     * 收到 hook 层的**配对响应**。**由 binder 线程调用**（`IHookHost.resolve`）。
     *
     * ## 鉴权：**逐字照搬 [onReport] 的三段**（§3.3 定案，不是可选）
     *
     * `resolve` 走的是**同一个 App 侧 binder**。若它没有凭证，
     * 「能 bind 到 `HookChannelService` 的进程」就能伪造响应 ——
     * 而这**比伪造事件更危险**：事件还要过 App 侧 filter 才触发，
     * `resolve` 的 `result` **直接就是 capability 的返回值**。
     *
     * ⚠️ 不能靠 `android:permission="HOOK_CONTROL"` 兜底 ——
     * 它是 `signature` 级，只挡**绑定**、不挡**绑定之后的方法调用**。
     *
     * ## 顺序：解析 → 校验 token → 查配对表 → 投递
     *
     * **任何一步失败都只记日志、绝不抛** —— 抛异常会跨国界且无人处理。
     */
    fun onResolve(responseJson: String) {
        val response = CapabilityInvocationCodec.decodeResponse(responseJson) ?: run {
            DebugLogger.w(TAG, "收到无法解析的响应信封，已忽略（长度 ${responseJson.length}）")
            return
        }

        // ── 第① 段：本侧无 token 直接拒绝 ──
        //
        // ⚠️⚠️ **必须先判「本侧有没有 token」**，不能直接比较：
        // 未连接时本侧 token 是空串，而伪造者送 `token:""` 也是空串 ——
        // 空串比空串**恒等**，于是无凭证的响应会被放行。
        // 这是本类在 onReport 上已经踩过的真实漏洞，不是理论问题。
        if (token.isEmpty()) {
            DebugLogger.w(TAG, "收到响应但本侧无 token（未连接），已丢弃（requestId=${response.requestId}）")
            return
        }

        // ── 第② 段：恒定时间比较 ──
        if (!constantTimeEquals(response.token, token)) {
            DebugLogger.w(TAG, "响应信封 token 校验失败，已丢弃（requestId=${response.requestId}）")
            return
        }

        // ── 第③ 段：查配对表（失败只记日志，见函数末尾的 try/catch）──
        val key = bucketKey(response.requestId)
        val sink = waiters.remove(key)
        if (sink == null) {
            // ⚠️ §5.2：**迟到 / 无配对的响应必须丢弃 + 告警，不能静默** ——
            // 否则「伪造响应被无痕接受」这条风险就有绕过面。
            // 这条日志也是判断「是否真的发生过迟到」的唯一途径。
            DebugLogger.w(
                TAG,
                "收到无配对的响应，已丢弃（requestId=${response.requestId}，" +
                    "可能是超时后的迟到响应或连接换代前的旧响应）"
            )
            return
        }

        try {
            sink(response)
        } catch (t: Throwable) {
            // 一个消费者抛异常不该把异常抛到 binder 线程上
            DebugLogger.w(TAG, "响应消费者抛异常（requestId=${response.requestId}）：${t.javaClass.simpleName}")
        }
    }

    /**
     * 让**全部**在途调用立即失败（断连时调，§5.2 定案）。
     *
     * ⚠️ 比等超时好得多 —— 此刻我们已经确知结果回不来了，
     * 而用户不必为每条调用白等满 `timeout_ms`。
     *
     * ⚠️ 换代：**先 +1 再唤醒** —— 这样唤醒过程中若有迟到的 `resolve` 到达，
     * 它用的是新代次的 key，**配不上**（已 remove 的旧 key 更配不上）。
     */
    private fun failAllWaiters(reason: String) {
        connectionGeneration.incrementAndGet()
        if (waiters.isEmpty()) return

        val snapshot = ArrayList(waiters.values)
        waiters.clear()
        DebugLogger.i(TAG, "连接断开：立即唤醒 ${snapshot.size} 个在途调用（$reason）")

        val failure = CapabilityResponse(
            requestId = "",
            ok = false,
            resultJson = "{}",
            error = CapabilityError(code = CapabilityErrorCode.CHANNEL_DOWN, detail = reason),
            elapsedMs = 0L,
            token = "",
            protocolVersion = EventEnvelopeCodec.PROTOCOL_VERSION,
        )
        for (sink in snapshot) {
            try {
                sink(failure)
            } catch (t: Throwable) {
                DebugLogger.w(TAG, "唤醒在途调用时抛异常：${t.javaClass.simpleName}")
            }
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
