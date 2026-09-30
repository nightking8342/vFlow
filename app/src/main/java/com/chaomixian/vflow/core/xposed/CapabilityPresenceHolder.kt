package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.capability.CapabilityPresence
import com.chaomixian.vflow.xposed.capability.presenceAfterDisconnect
import com.chaomixian.vflow.xposed.capability.presenceAfterExchange
import com.chaomixian.vflow.xposed.wire.CapabilityManifest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicReference

/**
 * ③ **连接期能力交换**结果的持有者（App 侧）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.3。
 *
 * ## ⚠️ 为什么它在 `core/xposed/` 而 `CapabilityPresence` 在 `xposed/capability/`
 *
 * `CapabilityPresence` 的类注释写着「本包只有枚举与纯函数，没有任何持有者」——
 * 一个可 `collect` 的持有者需要 `kotlinx.coroutines.flow`，而 `WireLayerPurityTest`
 * 对 `xposed/` 的 import 白名单**不含它**（那个包会被注入 system_server 加载）。
 *
 * ⇒ **纯逻辑在那边，可订阅的持有者在这边。** 本文件是那条分层要求的落实点。
 *
 * ## 它回答什么（与 P4 的 `XposedState.Channel` 不是一回事）
 *
 * | | 回答 | 来源 |
 * |---|---|---|
 * | `XposedState.Channel.NOT_MOUNTED` | 模块**挂到 system 了吗** | `runningTargets`（L3） |
 * | **本持有者（`ABSENT`）** | hook 层的**代码里有没有这个方法** | `ping()` + `capabilities()` |
 *
 * 一个**已挂载的旧版本 hook 层**：`Channel` 判 `READY` 而本持有者判 `ABSENT`。
 * **两者同时成立是正常的。**
 *
 * ## ⚠️ 它**不用来判降级**（§6.3 定案）
 *
 * ```
 * presence（本类）：提前决定【要不要试】     ← 优化，可能错（状态是采样的）
 * 运行时调用结果：  决定【要不要降级】       ← 权威，每次实测
 * ```
 *
 * 唯一用途是**避免白试**：旧 hook 层没有 `invoke` 方法时，oneway 调用会被 binder
 * **静默丢弃**（无错误、无返回值、无回调）⇒ 不判就会**每次白等满 5 秒超时**。
 * §7.4 反模式 8（用状态位替代运行时判断）说的就是这个 —— 本类的消费方
 * （`CapabilityInvoker`）**只**用它来短路，绝不用它决定降级。
 *
 * ## ⚠️ 线程模型
 *
 * - [presence] 是 `StateFlow` —— UI 与业务都可订阅。
 * - 探测**不在 binder 线程原地做**：`onCallbackRegistered` 由 binder 线程调用，
 *   而它随后会 `notifyOnConnected()` 逐个通知监听器 ⇒ 本类的监听器**必须立刻返回**，
 *   真正的工作丢给自己的线程。
 */
object CapabilityPresenceHolder {

    private const val TAG = "CapabilityPresence"

    /**
     * 在 `HookChannelController` 的「连接建立」注册表里的 key。
     *
     * ⚠️ 用类名，与 `ActivityChangedTriggerHandler.SINK_KEY` 同款 ——
     * 每个消费者唯一，加第二个时不会互相覆盖（修缺陷 1 的纪律）。
     */
    private const val CONNECT_KEY = "CapabilityPresenceHolder.probe"

    /** 在「连接断开」注册表里的 key。 */
    private const val DISCONNECT_KEY = "CapabilityPresenceHolder.reset"

    private val _presence = MutableStateFlow(CapabilityPresence.UNKNOWN)

    /** 唯一消费者入口。⚠️ 只用于「避免白试」，不用于判降级（见类注释）。 */
    val presence: StateFlow<CapabilityPresence> = _presence.asStateFlow()

    /**
     * 在途探测线程。
     *
     * ⚠️ 用 `AtomicReference` + `compareAndSet` 而不是 `@Volatile var`：
     * 「start 时若已有线程在跑就跳过」这个判断需要**原子读改写** ——
     * 两个线程同时通过检查会起两个线程，而它们会**各自改变 presence**
     * （后到的写入覆盖先到的，得到一个不确定的结果）。
     */
    private val probeThread = AtomicReference<Thread?>(null)

    /** 是否已接线。⚠️ 让 [start] 幂等。 */
    private val started = AtomicReference(false)

    // ════════════════════ 接线 ════════════════════

    /**
     * 接线到 `HookChannelController` 的两个方向。**幂等**。
     *
     * ## ⚠️⚠️ 为什么探测的触发点是「连接建立」的推送，而不是 Application 生命周期钩子
     *
     * `HookChannelService`（App 侧端点）与 hook 层（system_server 侧）**谁先启动是不确定的**：
     * `VFlowHookEntry` 依赖 App 进程里的 `DebugLogger.initialize`，而
     * `HookChannelService` 又等 system_server 来 bind。
     *
     * 若在 `Application.onCreate` 里「起线程等连接」，就要求本类的启动**先于**
     * hook 层的连接 —— 而那个顺序**没有任何东西保证**。而
     * `HookChannelController.onCallbackRegistered` 里的 `notifyOnConnected()`
     * 是**推送**的（连接一建立就通知）⇒ 挂在它上面**与启动顺序无关**。
     *
     * ⚠️ 但**推送有个代价：它不重放**。错过那一次就永远不知道「已经连上了」
     *（`XposedFrameworkMonitor` 的注释里有同一句教训）。⇒ 见 [onConnectNotified] 的
     * 「每次自检」要求。
     */
    fun start() {
        if (!started.compareAndSet(false, true)) return

        HookChannelController.setOnConnectedListener(CONNECT_KEY) { onConnectNotified() }
        HookChannelController.setOnDisconnectedListener(DISCONNECT_KEY) { onDisconnectNotified() }

        DebugLogger.i(TAG, "能力交换已接线（等 hook 层连接建立后探测）")

        // ⚠️ 若此刻**已经**连着（本类接线晚于连接建立），立刻补一次探测 ——
        // 否则「接线晚了」就等价于「永久错过」（推送不重放）。
        if (HookChannelController.isConnected()) {
            DebugLogger.i(TAG, "接线时已处于连接态，立刻补一次能力交换")
            onConnectNotified()
        }
    }

    /** 取消接线并失效状态。⚠️ 只供测试与将来的「彻底停用」路径用。 */
    fun stop() {
        started.set(false)
        HookChannelController.removeOnConnectedListener(CONNECT_KEY)
        HookChannelController.removeOnDisconnectedListener(DISCONNECT_KEY)
        _presence.value = presenceAfterDisconnect()
    }

    // ════════════════════ 两个方向的反应 ════════════════════

    /**
     * 连接建立时被通知。**跑在 binder 线程上**（由 `onCallbackRegistered` 推送）——
     * 所以这里只做「接线判定 + 起线程」，绝不调用 binder。
     */
    private fun onConnectNotified() {
        // ⚠️⚠️ **每次都要自检开关，不能在 `start()` 时判一次**。
        //
        // 理由（`docs/fork/mindfs` 的定案记录与此处同源）：
        // `start()` 发生在**主 App 进程**，而 hook 层的连接建立发生在 **system_server 侧** ——
        // 两者没有顺序保证。若开关在 `start()` 时是关的（比如用户/测试刚关掉它），
        // 我们**不注册**监听 ⇒ 那一次连接建立被**永久错过** ⇒
        // presence 停在 `UNKNOWN` ⇒ `CapabilityInvoker` 的 `ABSENT` 短路永不生效 ⇒
        // **每次调用都白等满 5 秒超时**。而唯一的恢复办法是重启 App。
        //
        // 改成「here 每次都自检」之后，开关重新打开时会**在下一次连接建立时**生效；
        // 且开关关着时**不会**做那两次 binder 往返。
        if (!CapabilityRuntime.isEnabled()) {
            DebugLogger.d(TAG, "③ 运行时未启用，跳过能力交换")
            return
        }

        // ⚠️ 起线程而非原地做：本方法在 binder 线程上（`notifyOnConnected` 的调用栈），
        // 而 `ping()` / `capabilities()` 是**同步 binder 往返**到 system_server。
        // 在 binder 线程上做同步 IPC = 占住 App 侧的 binder 池（§5.1 的纪律反过来也成立）。
        probeAsync()
    }

    /** 连接断开时被通知（由 `onCallbackUnregistered` 推送，在 App 侧 binder 线程上）。 */
    private fun onDisconnectNotified() {
        val before = _presence.value
        _presence.value = presenceAfterDisconnect()
        if (before != CapabilityPresence.UNKNOWN) {
            DebugLogger.i(TAG, "连接断开，能力可用性已失效（$before → ${_presence.value}）")
        }
    }

    /**
     * **越界自愈**（由 `HookChannelController.onCallbackUnregistered` 在所有早退分支之前调）。
     *
     * ## ⚠️ 为什么需要它，而不是只靠 [onDisconnectNotified]
     *
     * `onCallbackUnregistered` 有**早退分支**（「旧连接迟到通知」那条会 `return` 而不清空
     * 任何东西）。那个分支**不会**触发断开回调，但「一条连接已经不在了」这件事本身
     * 仍然可能意味着「此刻没有连接」⇒ 残留的 `READY` 必须失效。
     *
     * 本函数把这个判断**收敛到 Controller 自己的接口上**（[HookChannelController.isConnected]），
     * 于是：
     * - 它**不依赖**任何回调是否被触发；
     * - `UNKNOWN` 时是纯 no-op（`isConnected()` 是纯读，无副作用）；
     * - 即使将来 `onCallbackUnregistered` 又加了新的早退分支，本函数仍在**第一条**
     *   语句上兜着（它的调用点被 deliberately 放在函数最前面）。
     *
     * ⚠️ **为什么它不是「直写 reset」**：`HookChannelController` 直写
     * `CapabilityPresenceHolder.reset()` 会让它**单向依赖**上层模块（③ 的运行时），
     * 而上层已经依赖它 ⇒ 成环。这里方向是 `PresenceHolder → Controller`，
     * 与既有的 `ActivityChangedTriggerHandler → Controller` 同向。
     */
    internal fun resetIfDisconnected() {
        if (_presence.value == CapabilityPresence.UNKNOWN) return
        if (HookChannelController.isConnected()) return
        val before = _presence.value
        _presence.value = presenceAfterDisconnect()
        DebugLogger.i(TAG, "检测到无连接，能力可用性已失效（$before → UNKNOWN）")
    }

    // ════════════════════ 探测 ════════════════════

    /**
     * 起一次异步探测（幂等：已有在途线程时跳过）。
     *
     * ⚠️ 用**裸线程**而不是协程：本类所在文件属于 `core/xposed/` 的既有风格
     * （`HookChannelController` 只 import 过 `flow.*`，`BinderTransport` 用裸线程做
     * 等待与重连）—— 本仓库的既有做法就是这样，且这里只需要「起一次、跑完就结束」，
     * 协程的取消/结构化并发在这里没有收益。
     */
    private fun probeAsync() {
        val t = Thread({
            try {
                probeNow()
            } catch (t2: Throwable) {
                // ⚠️ 绝不逃逸：本线程的名字是唯一线索，逃逸后就是一次静默的线程死亡
                DebugLogger.w(TAG, "能力交换线程异常：${t2.javaClass.simpleName} ${t2.message}")
            } finally {
                probeThread.set(null)
            }
        })
        t.name = "VFlowCapPresence"
        t.isDaemon = true
        if (!probeThread.compareAndSet(null, t)) {
            DebugLogger.d(TAG, "已有在途的能力交换探测，跳过本次")
            return
        }
        try {
            t.start()
        } catch (e: Throwable) {
            probeThread.set(null)
            DebugLogger.w(TAG, "能力交换线程启动失败：${e.javaClass.simpleName}")
        }
    }

    /**
     * 一次能力交换：**先 `ping()`，再 `capabilities()`**。
     *
     * ⚠️⚠️ **判据的顺序是定案的**（`IHookCallback.aidl` 里两个方法的 KDoc 逐字写着）：
     *
     * ```
     * ping 通  &&  capabilities() 通   ⇒ READY    （哪怕清单是空的 JSON 数组）
     * ping 通  &&  capabilities() 抛    ⇒ ABSENT   （hook 层代码里没有这个方法）
     * ping 不通                         ⇒ UNKNOWN  （拿不到答案 ≠ 方法不存在）
     * ```
     *
     * `ping()` 是**第一判据** —— 它最老、必然存在。`capabilities()` **本身就不存在**时
     * 它不能当第一判据（那时收不到任何东西，无法区分「方法不存在」与「连不上」）。
     *
     * ⚠️ **「方法不存在」在 AIDL 层面表现为「调用抛异常」，不是「返回空串」** ——
     * 所以这里必须 `try/catch`。旧 hook 层（没有这个导出方法）会让 binder 抛
     * `TransactionException` / `NoSuchMethod` 一类；把它们当成 `null` 交给
     * `presenceAfterExchange` 才会得到 `ABSENT`。
     *
     * ⚠️ 第三支（ping 都不通）**刻意**用 `presenceOnDisconnect()`（= `UNKNOWN`）
     * 而不是 `ABSENT`：拿不到答案**不等于**方法不存在（连接可能刚断）。
     * 把 UNKNOWN 当 ABSENT 会让用户被引去「升级 App」，而 App 其实是最新的 ——
     * 正是 §6.4 要避免的误导方向。
     */
    private fun probeNow() {
        val callback = HookChannelController.callbackOrNull()
        if (callback == null) {
            DebugLogger.d(TAG, "探测时已无连接，保持 UNKNOWN")
            _presence.value = presenceOnDisconnect()
            return
        }

        val result = try {
            // ① 第一判据：最老、必然存在的方法
            callback.ping()
            // ② 清单。⚠️ 拿不到会**抛**（见上面的说明）—— 这正是 exchangeOnConnect
            //    的 ManifestProbe 契约（「拿不到时抛」）
            exchangeOnConnect { callback.capabilities() }
        } catch (t: Throwable) {
            DebugLogger.d(
                TAG,
                "能力交换失败（${t.javaClass.simpleName}）：判 UNKNOWN —— " +
                    "ping 都不通说明是连接问题，不是 hook 层版本旧",
            )
            presenceOnDisconnect()
        }

        _presence.value = result
        when (result) {
            CapabilityPresence.READY -> DebugLogger.i(TAG, "能力交换成功：hook 层支持 ③ 能力交换")
            CapabilityPresence.ABSENT -> DebugLogger.w(
                TAG,
                "⚠️ ping 通但 capabilities() 拿不到 ⇒ hook 层代码太旧（判 ABSENT）。" +
                    "此时 §6.4 的处置指向**升级/重启 App**，不是去改 LSPosed 配置",
            )
            CapabilityPresence.UNKNOWN -> DebugLogger.d(TAG, "能力交换未得出结论（保持 UNKNOWN）")
        }
    }

    // ════════════════════ 测试接缝 ════════════════════

    /**
     * **仅供测试**：把 presence 直接置为某个值。
     *
     * ⚠️ 存在理由：正常路径的探测是**异步**的（起线程 + 两次 binder 往返），
     * 单测若依赖它就会变成时序相关（本仓库反复记录过「测试不经过调用点所以反证不变红」，
     * 而时序相关的测试是它的近亲：看起来在测、实际只测到「大概是这样」）。
     *
     * ⇒ 需要「已 ABSENT / 已 READY」这两个**已定状态**的用例，直接调本方法。
     * 与 `HookChannelController.injectTokenForTest` 是同一种接缝。
     */
    internal fun setForTest(p: CapabilityPresence) {
        _presence.value = p
    }

    /**
     * **仅供测试**：等在途探测线程结束。
     *
     * @return 是否在超时前结束。⚠️ 返 false **不代表探测失败** ——
     *   只代表「它还在跑」，调用方自己决定怎么断言。
     */
    internal fun awaitIdleForTest(timeoutMs: Long): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val t = probeThread.get() ?: return true
            if (!t.isAlive) return true
            try {
                Thread.sleep(5)
            } catch (_: InterruptedException) {
                return false
            }
        }
        return false
    }

    /** **仅供测试**：回到初始状态（单例状态会在用例之间串）。 */
    internal fun resetForTest() {
        started.set(false)
        HookChannelController.removeOnConnectedListener(CONNECT_KEY)
        HookChannelController.removeOnDisconnectedListener(DISCONNECT_KEY)
        _presence.value = CapabilityPresence.UNKNOWN
        probeThread.set(null)
    }

    /**
     * 供 [probeNow] 用的**清单解码**：把 `capabilities()` 的返回值解成集合。
     *
     * ⚠️ 本仓库当前**没有**消费这个集合的地方（`CapabilityInvoker` 只判 presence）——
     * 它存在的理由是**让探测真的去解一次**，从而让「清单是坏的 JSON」这种情况
     * 在日志里可见（而不是静默吞掉）。
     *
     * ⚠️ 不用它的返回值做任何判断（更**不用它**推断 hook 层支持哪些能力）：
     * 「有哪些能力」的权威在 hook 侧的注册表，App 侧只按 `capability` 名逐个试。
     */
    internal fun decodeManifestForLog(json: String): Set<String>? =
        CapabilityManifest.decode(json)
}
