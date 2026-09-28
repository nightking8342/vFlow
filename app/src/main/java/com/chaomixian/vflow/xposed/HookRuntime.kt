package com.chaomixian.vflow.xposed

import android.os.IBinder
import android.os.RemoteException
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import com.chaomixian.vflow.xposed.wire.EventQueue
import io.github.libxposed.api.XposedInterface
import java.util.concurrent.atomic.AtomicLong

/**
 * hook 层运行时骨架。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.1 的中间层。
 *
 * ## ⚠️ 本类运行在 system_server 进程里（uid 1000）
 *
 * 与 `VFlowHookEntry` 同一条引用面约束：**禁止引用 App 侧重类**
 * （`core.*` / `services.*` / Gson / `DebugLogger`）。日志只用 `android.util.Log`。
 *
 * ## 三层职责（本类是中间那层）
 *
 * ```
 *   HookSource（适配器，每类事实一个）  ← 只做「挂点 + 取值」
 *          │ emit(topic, payload)
 *          ▼
 *   HookRuntime（本类）                ← 信封装配 + 有界队列 + 发送线程
 *          │
 *          ▼
 *   Transport（可替换传输层）           ← 只管「搬信封」
 * ```
 *
 * **`HookSource` 不序列化、不通信、不知道 `seq`** —— 这样取值逻辑可单测，
 * 且「加第 N 个触发器」时改动面积与加第 1 个相同（§3.4.3）。
 *
 * ## ⚠️ 线程模型（这是本类最要紧的部分）
 *
 * - [emit] 跑在 **hook 回调线程**（system_server 的主线程或 Binder 线程）：
 *   只做「装信封 + 入队」，**绝不做 IPC**。
 * - 发送由**独立线程** [drainLoop] 负责。
 *
 * 把发送移出回调不只是性能考量 —— P0 期间三次「模块不加载」中，
 * 嫌疑最大的一条就是「hook 回调里新增调用」（见 `P0-FINDINGS.md` §7）。
 * 结构上分开，顺带把这条嫌疑点从代码里消掉。
 */
class HookRuntime(
    private val transport: HookTransport,
    /**
     * 框架接口，用来挂 hook。
     *
     * ⚠️ 传的是 `XposedModule` **自身**（它实现了 `XposedInterface`）——
     * 不要试图从别处 new 一个，`hook()` 的上下文与模块实例绑定。
     */
    private val xposed: XposedInterface,
    capacity: Int = EventQueue.DEFAULT_CAPACITY,
) {

    companion object {
        private const val TAG = "VFlowHook"

        /** 发送线程从队列取事件的等待上限。仅影响退出延迟，不影响事件延迟。 */
        private const val DRAIN_POLL_MS = 250L
    }

    private val queue = EventQueue(capacity)

    /**
     * 连接级**单调递增**序号。
     *
     * ⚠️ 没有它就分不清「没事件」与「**丢了事件**」——
     * 这是本仓库反复记录的静默失效形态（§3.4.2）。
     *
     * 用**连接级全局**计数而非 per-topic：通信层只有一条流，
     * 全局序号能同时检出任何 topic 的丢包，实现也最简（一个 `AtomicLong`）。
     */
    private val seq = AtomicLong(0)

    /** 鉴权 token。由 App 随第一次条件下发给出；未拿到前不上报任何事件。 */
    @Volatile
    private var token: String = ""

    /** 当前订阅的 topic 集合。只影响「哪些 source 开着」，不含任何业务规则。 */
    @Volatile
    private var subscribedTopics: Set<String> = emptySet()

    @Volatile
    private var running = false
    private var drainThread: Thread? = null

    /**
     * 启动发送线程。**幂等**。
     *
     * 注意：这里**不等**「系统服务就绪」也不等「用户解锁」——
     * 那是 [HookTransport] 的职责（它要 bindService）。
     * 本类只管「拿到信封就发」。
     */
    @Synchronized
    fun start() {
        if (running) return
        running = true
        drainThread = Thread({ drainLoop() }, "VFlowHook-drain").apply {
            isDaemon = true
            start()
        }
    }

    @Synchronized
    fun stop() {
        stopDrainOnly()
        // ⚠️ 卸下所有 hook：无触发器时白挂着 hook 点，白担崩溃风险（§3.4.4）
        for (s in sources) {
            try {
                s.unmount()
            } catch (t: Throwable) {
                log("unmount ${s.topic} 失败：${t.javaClass.simpleName}")
            }
        }
    }

    /**
     * 只停发送线程，**不动 hook 点**。
     *
     * ⚠️ 热更新路径专用：`onHotReloading` 里若 unmount，新代际的
     * `getOldHookHandles()` 就是空的，`replaceHook()` 无从接手 ⇒ 事件永久消失。
     */
    @Synchronized
    fun stopDrainOnly() {
        running = false
        drainThread?.interrupt()
        drainThread = null
    }

    // ── HookSource 管理 ──────────────────────────────────────────

    /** 挂载点 + 过滤器，**每个 topic 一份**。 */
    private val sources = java.util.concurrent.CopyOnWriteArrayList<HookSource>()

    /**
     * 注册一个适配器（**幂等**：同 topic 重复注册会被忽略）。
     *
     * ⚠️ 用 topic 去重而不是「无脑 append」：热更新重建通道时
     * `startChannel()` 会把 source 列表重新填一遍，若不去重，
     * 同一 hook 点会被挂两次 —— 表现为**每个事件上报两遍**。
     */
    fun register(source: HookSource) {
        if (sources.any { it.topic == source.topic }) {
            log("source ${source.topic} 已注册，跳过")
            return
        }
        sources.add(source)
        log("已注册 source：${source.topic}")
    }

    /**
     * 挂载所有适配器。由 [VFlowHookEntry] 在拿到 ClassLoader 后调用。
     *
     * ⚠️ 与 [start] 分开：`start` 只起发送线程（不需要 ClassLoader），
     * 而挂 hook 必须有目标进程的 ClassLoader。
     */
    fun mountSources(classLoader: ClassLoader) {
        for (s in sources) {
            try {
                s.mount(this, classLoader)
            } catch (t: Throwable) {
                // 一个 source 挂不上不该影响其他的
                log("mount ${s.topic} 失败：${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /**
     * **强制重挂**（先卸再挂）。
     *
     * ⚠️⚠️ `mount()` 自己带 `if (handle != null) return` 的幂等守卫，
     * 但那在**重连场景下是错的**：hook 层换代后，旧 `handle` 指向的
     * 已经是**死掉那一代的句柄**，字段非 null 却已失效 ——
     * 于是 `mount()` 直接跳过，**hook 点永远挂不上**，
     * 表现为「通道连上了、状态显示正常，但什么都不触发」。
     *
     * ⇒ 重连时用本方法（先 unmount 清掉陈旧句柄，再挂）。
     */
    fun remountSources(classLoader: ClassLoader) {
        for (s in sources) {
            try {
                s.unmount()
            } catch (t: Throwable) {
                log("remount 前 unmount ${s.topic} 失败：${t.javaClass.simpleName}")
            }
        }
        mountSources(classLoader)
    }

    /** 已注册的 source 数。用于自检「挂载是否真的发生了」。 */
    fun sourceCount(): Int = sources.size

    /**
     * ⚠️ **此处的「请求重下发条件」是 App 侧的职责，hook 层不做。**
     *
     * 记录一下为什么（我一度在这里加过一个 `requestConditionResend()`，已删）：
     *
     * 1. **鸡生蛋**：它要用 `emit()` 发出去，而 `emit()` 在 `token` 为空时直接 return；
     *    重连那一刻 token 正好是空的（token 由 App 经 `pushConditions` 下发）。
     * 2. **重复**：App 侧**已经**在连接建立时重下发 ——
     *    `HookChannelController.onCallbackRegistered` → `onConnected` →
     *    `ActivityChangedTriggerHandler.syncToChannel()`（P3 就实现了）。
     *
     * ⇒ 结论：**条件是 App 侧推的，所以「重推」也只能由 App 侧发起**；
     * hook 层连上后什么都不用做，App 会推过来。
     */

    /** 供适配器读取框架接口（它们需要 `hook(Executable)`）。 */
    fun xposed(): XposedInterface = xposed

    /**
     * 热更新后让所有适配器接手旧 hook。
     *
     * ⚠️⚠️ **不调用它的后果是「静默失效」**：`onHotReloaded` 的默认实现会
     * unhook 全部旧 hook，而热更新不重放任何回调 —— 于是重装 APK 后
     * 通道心跳正常、但**事件永远不再产生**。
     *
     * @return 成功接手的 source 数
     */
    fun remountAfterHotReload(oldHandles: List<XposedInterface.HookHandle>): Int {
        var ok = 0
        for (s in sources) {
            try {
                if (s.remountAfterHotReload(oldHandles, this)) ok++
            } catch (t: Throwable) {
                log("remount ${s.topic} 失败：${t.javaClass.simpleName} ${t.message}")
            }
        }
        return ok
    }

    /**
     * ★ **hook 回调线程调用**：装信封 + 入队，立即返回。
     *
     * ⚠️ 绝不要在这里加 IPC / I/O —— 见类注释的线程模型。
     * ⚠️ 未拿到 token 时**直接丢弃**，不入队：无凭证的事件在 App 侧会被拒，
     *    入队只会挤掉有凭证的事件。
     */
    fun emit(topic: String, payloadJson: String) {
        if (token.isEmpty()) return

        // ⚠️ **只读一次**，并把它记进信封 —— 发送成功后要按这个值精确扣除
        //（见 [EventQueue.drainDroppedAtMost] 的注释）。原先这里读一次、
        // 下面日志里又读一次，两次可能不同 ⇒ 无法说清「这条信封报了哪个数」。
        val dropped = queue.peekDropped()

        val envelope = EventEnvelopeCodec.encode(
            topic = topic,
            seq = seq.incrementAndGet(),
            ts = System.currentTimeMillis(),
            payloadJson = payloadJson,
            // 丢弃数随下一个信封上报，而不是单独造一条通道（§3.4.4）。
            // 只读不清 —— 清零由【发送成功之后】做，
            // 这样「入了队但没发出去」的丢弃不会丢账
            droppedCount = dropped,
            token = token,
        )

        if (!queue.offer(envelope)) {
            log("事件队列已满，丢弃一条（累计丢弃 ${queue.peekDropped()}）")
        }
    }

    /**
     * 发送线程：从队列取信封 → 交给 transport。
     *
     * 失败时**不重试、不重排** —— 事件是有时效的，
     * 补发一条几秒前的 activity 事件没有意义，反而可能误触发。
     * 丢掉的会被 `dropped` 计数反映（下次上报时可见）。
     */
    private fun drainLoop() {
        while (running) {
            val envelope = queue.poll(DRAIN_POLL_MS) ?: continue
            var sent = false
            try {
                sent = transport.send(envelope)
                if (!sent) {
                    log("信封发送失败（连接已断？），本条丢弃")
                }
            } catch (t: Throwable) {
                // 单独 catch：一条发不出去不能把整个发送线程打死
                log("信封发送异常：${t.javaClass.simpleName} ${t.message}")
            }

            // ⚠️ 只有**发送成功**才把「已上报的丢弃数」扣掉（修缺陷 14）。
            // 失败时保留 —— 下一条信封会把它再报一次，那个信息不会丢。
            if (sent) {
                val stillPending = queue.drainDroppedAtMost(envelopeDroppedCount(envelope))
                if (stillPending > 0) {
                    // 不静默：说明「上报期间又丢了」，用户应该知道
                    log("丢弃计数已上报，期间又新增 $stillPending 条待报")
                }
            }
        }
    }

    /**
     * 从已编码的信封里取回 `dropped` 字段。
     *
     * ⚠️ 为什么要**反解**而不是在 `emit` 里另存一份：信封是唯一真实来源
     * （它是实际发出去的东西）。另存一份会引入「两份状态可能不一致」，
     * 而这正是本仓库反复踩的形态。解析失败一律返回 0（不扣、不静默出错）。
     */
    private fun envelopeDroppedCount(envelopeJson: String): Long = try {
        org.json.JSONObject(envelopeJson).optLong(EventEnvelopeCodec.KEY_DROPPED)
    } catch (_: Throwable) {
        0L
    }

    /**
     * 下行：App 下发条件 + token。
     *
     * @param conditionsJson 全量条件；**空串表示「没有触发器了，卸下 hook」**
     * @param token 鉴权凭据
     */
    fun onConditions(conditionsJson: String, token: String) {
        this.token = token

        // 条件变了 ⇒ 队列里的旧事件已无意义，丢弃积压。
        // ⚠️ clear() 不清丢弃计数（照 LogcatEventQueue 语义），
        // 那次丢弃仍然会被下一次上报带出去，用户能知道
        val cleared = queue.clear()
        if (cleared > 0) {
            log("条件下发：丢弃 $cleared 条积压事件（来自上一次连接）")
        }

        // ⚠️ 条件的**业务语义**不在这里解析 —— hook 层不做判定（§3.2 硬约束）。
        // 这里只取出 topic 集合（用于「有哪些订阅」），并把原始串分发给各适配器，
        // 由适配器自己决定「据此少采什么」。
        subscribedTopics = parseTopics(conditionsJson)
        log("条件下发：${subscribedTopics.size} 个 topic，token=${if (this.token.isEmpty()) "空" else "已设置"}")

        for (s in sources) {
            try {
                s.applyConditions(conditionsJson)
            } catch (t: Throwable) {
                // 一个适配器解析不了不该影响其他的
                log("applyConditions ${s.topic} 失败：${t.javaClass.simpleName}")
            }
        }
    }

    /** 当前订阅的 topic 集合（供 source 判「要不要挂」）。 */
    fun subscribedTopics(): Set<String> = subscribedTopics

    /**
     * 从条件 JSON 里取出 topic 列表。
     *
     * **只认 topic，不认业务字段** —— 这是「Hook 层不知道工作流的存在」的落实。
     * 解析失败一律当作「空订阅」（卸下 hook），而不是猜。
     */
    private fun parseTopics(conditionsJson: String): Set<String> {
        if (conditionsJson.isBlank()) return emptySet()
        return try {
            val obj = org.json.JSONObject(conditionsJson)
            val arr = obj.optJSONArray("topics") ?: return emptySet()
            buildSet {
                for (i in 0 until arr.length()) {
                    val t = arr.optString(i)
                    if (t.isNotBlank()) add(t)
                }
            }
        } catch (t: Throwable) {
            log("条件 JSON 解析失败，按空订阅处理：${t.javaClass.simpleName}")
            emptySet()
        }
    }

    private fun log(msg: String) {
        try {
            android.util.Log.e(TAG, msg)
        } catch (_: Throwable) {
        }
    }
}

/**
 * 可替换传输层。
 *
 * 设计文档 §3.4.1 的第三层。上层只认「发一条信封」，不关心背后是谁。
 *
 * 当前唯一实现是 [BinderTransport]；广播版是既有的备选方案（§4.2.2），
 * 但**它拿不到连接状态**，而连接状态正是 `bindService` 做主通道的核心理由
 * （§3.3 的「hook 挂载态」判据）。
 */
interface HookTransport {

    /** 发起连接。**必须自己处理「服务就绪」与「用户已解锁」两个等待**（见实现）。 */
    fun start()

    /** 断开。 */
    fun stop()

    /**
     * 发送一条信封。
     *
     * ⚠️ **只能被发送线程调用** —— 绝不要在 hook 回调里直接调它。
     *
     * @return 是否已成功交给对端
     */
    fun send(envelopeJson: String): Boolean

    /** 连接是否建立。这是「hook 挂载态」的判据（§3.3）。 */
    val isConnected: Boolean
}

/**
 * hook 层的 binder 回调实现：接收 App 的下行调用。
 *
 * 抽成顶层函数而非内嵌类，是为了让 [BinderTransport] 能把它交给 `IHookCallback.Stub`。
 */
internal fun handlePushConditions(
    runtime: HookRuntime,
    conditionsJson: String,
    token: String,
): Boolean = try {
    runtime.onConditions(conditionsJson, token)
    true
} catch (t: Throwable) {
    try {
        android.util.Log.e("VFlowHook", "pushConditions 处理失败", t)
    } catch (_: Throwable) {
    }
    false
}

/** `ping()` 返回的协议版本，供 App 侧版本协商。 */
internal fun currentProtocolVersion(): Int = EventEnvelopeCodec.PROTOCOL_VERSION

/** 供实现复用的空 IBinder 判空。 */
internal fun IBinder?.isAlive(): Boolean = this != null && try {
    isBinderAlive
} catch (_: RemoteException) {
    false
}
