package com.chaomixian.vflow.xposed

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.UserManager
import com.chaomixian.vflow.xposed.IHookCallback
import com.chaomixian.vflow.xposed.IHookHost

/**
 * hook 层的 `bindService` 传输实现（**唯一主通道**）。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.2.2 / §4.2.5。
 * 探针实证：`scripts/probe/xposed-channel/P0-FINDINGS.md` §5.1。
 *
 * ## ⚠️⚠️ 两个「等待」是本类存在的主要理由，缺一个就会得到假的「连不上」
 *
 * | 等待 | 判据 | 不等会怎样 |
 * |---|---|---|
 * | **用户已解锁** | `UserManager.isUserUnlocked()` | 目标 Service `directBootAware=false`，未解锁时组件**在 package 解析阶段就被排除**，表现与「包不可见」**一模一样**：`resolveService` 返 null、`bindService` 返 false |
 * | **系统服务就绪** | `PackageManager` 非 null | `onSystemServerStarting` 时 `PackageManager` / `IActivityManager` **均为 null**，约 11 秒后才可用 |
 *
 * 这两条都是**实测**得来，不是推断。探针曾因漏掉第一条而误判「推翻了通道选型」，
 * 连错两次（`P0-FINDINGS.md` §5.1.1 的归因反面教材）。
 *
 * ⚠️ **绝不要用 `directBootAware=true` 来「修好连不上」** ——
 * 那会改变语义并掩盖真因。
 *
 * ## 为什么 `bindService` 优于广播（§4.2.2）
 *
 * ① **连接状态本身就是「hook 挂载态」的判据**（`onServiceConnected` / `onServiceDisconnected`），
 *   不需要 hook 层额外上报 —— 广播接收器「收下即返回、不能回包确认」，判不出来；
 * ② 双向，无广播的频率/大小限制；③ 5b（uid 1000）不受包可见性限制。
 *
 * ## 为什么不用 `startService`
 *
 * Android 12+ 后台启动前台服务限制会让第三方身份调 `startService` 抛
 * `ForegroundServiceStartNotAllowedException`（§4.2.3）。
 * 而 5b 用 `bindService`，**根本不涉及 `startService`**。
 */
class BinderTransport(
    private val component: ComponentName,
    /** 取 system_server 的 Context。抽成函数是为了让「拿不到」可被测试与降级。 */
    private val contextProvider: () -> Context?,
) : HookTransport {

    companion object {
        private const val TAG = "VFlowHook"

        /** 轮询间隔。500ms 与探针一致，实测足够（解锁等待最长约 60 秒）。 */
        private const val POLL_INTERVAL_MS = 500L

        /**
         * 等待上限。**300 次 × 500ms = 150 秒**，与探针一致。
         *
         * ⚠️ 不要缩短到 30 秒：探针实测比设备解锁早约 **47 秒**，
         * 30 秒的上限会在解锁前就放弃，得到假的「连不上」。
         */
        private const val MAX_WAIT_ATTEMPTS = 300

        /** 重连退避起点。首次断开后 1 秒重试。 */
        private const val RECONNECT_BASE_MS = 1_000L

        /**
         * 重连退避上限。
         *
         * ⚠️ 封顶 30 秒而不是无限翻倍：重装 APK / 强杀 App 之后，
         * 用户通常几秒内就会开始用，等太久等于「功能坏了」。
         * 30 秒是「不刷屏」与「能及时恢复」的折中。
         */
        private const val RECONNECT_MAX_MS = 30_000L

        /**
         * 存活性巡检间隔。
         *
         * ⚠️⚠️ **为什么光有 `onServiceDisconnected` 不够**：
         * 它在某些情况下**不触发**（无 FIN、AMS 未通知等）——
         * 这是 `LogcatStreamWrapper` 踩过的同一个坑（`FORK.md` 有记载）。
         * 所以再加一条主动巡检兜底：定期检查 binder 是否还活着。
         */
        private const val LIVENESS_CHECK_MS = 15_000L

        /**
         * 等连接落地时的轮询步长。
         *
         * ⚠️ 存在的理由见 [awaitConnected]：`bindService()` 只是「已提交」，
         * 不等它落地就会重复 bind（缺陷 20）。
         */
        private const val AWAIT_STEP_MS = 100L

        /**
         * 等连接落地的**总窗口**。
         *
         * ⚠️ 取 5 秒而与退避值无关：`bindService` 提交到 `onServiceConnected` 落地
         * 实测约 200ms，但设备刚开机 / App 冷启动时会更慢。
         * 窗口太小会把「慢落地」误判为失败 ⇒ 又 bind 一次（缺陷 20 复发）。
         * 代价只是「真的连不上时多等 5 秒」，而重连循环本身就是低频的。
         */
        private const val AWAIT_TIMEOUT_MS = 5_000L

        /**
         * 计算下一次重连的退避时长（**纯函数，可单测**）。
         *
         * 抽出来是因为「退避算错」的表现是**静默的**：算小了刷屏打日志、
         * 算大了用户以为功能坏了。而它本身与 Android 无关，没必要放进集成路径测。
         *
         * @param previous 上一次的退避（首次传 [RECONNECT_BASE_MS]）
         */
        internal fun nextBackoffMs(previous: Long): Long =
            (previous * 2).coerceAtMost(RECONNECT_MAX_MS)

        /** 重连尝试次数上限判断（纯函数）。超过后放弃、等下一次触发。 */
        internal fun shouldKeepRetrying(attempts: Int): Boolean = attempts < MAX_RECONNECT_ATTEMPTS

        /**
         * 重连尝试上限。
         *
         * ⚠️ 为什么要封顶：`onServiceDisconnected` 可能在**框架真的没了**的情况下反复触发
         * （用户卸载了 LSPosed）。无限重连会一直占着线程 + 刷日志。
         * 上限之后停手，等下次连接事件（或用户重启）再试。
         */
        private const val MAX_RECONNECT_ATTEMPTS = 20
    }

    @Volatile
    private var host: IHookHost? = null

    @Volatile
    private var waitThread: Thread? = null

    @Volatile
    private var stopped = false

    /** App 侧下发的条件回调（由 [HookRuntime] 注入）。 */
    @Volatile
    private var conditionSink: ((String, String) -> Unit)? = null

    /**
     * **连接（重新）建立**后的回调注册表（由调用方注入）。
     *
     * ⚠️ 为什么必须有它：重连成功后，hook 层那边可能是**刚换代的新代码** ——
     * hook 点没挂上、条件被清空。不重挂、不重下发的话，
     * 表现为**「连上了但什么都不触发」**（通道看着是活的）。
     *
     * ## ⚠️⚠️ 为什么是注册表而不是单个槽位（修缺陷 12）
     *
     * 原实现是 `var onConnectedSink: (() -> Unit)?`，语义是**后注册的覆盖先注册的**
     * —— 这是本通道**第三处**单槽位（另两处：`HookChannelController.eventSinks`
     * 已修、`onConnected` 已随缺陷 1 修）。三处同一个形态，**同一个教训**。
     *
     * ⚠️ 现在只有一个注册方（`VFlowHookEntry`），但它是**框架层**的钩子 ——
     * 将来加「连上后要做什么」的第二件事时，覆盖会**静默**生效。
     * 按 key 做注册表，加第二件事的人不必先知道这里曾是单槽位。
     */
    private val onConnectedSinks =
        java.util.concurrent.ConcurrentHashMap<String, () -> Unit>()

    /** 注册连接建立回调。**幂等**（同 key 重复注册会替换）。 */
    fun onConnected(key: String, sink: () -> Unit) {
        onConnectedSinks[key] = sink
    }

    /** 注销连接建立回调（带 key，不会误伤其他注册方）。 */
    fun removeOnConnected(key: String) {
        onConnectedSinks.remove(key)
    }

    /**
     * 重连线程。
     *
     * ⚠️ **为什么必须有重连**（这是一个实测暴露的真实缺陷，不是预防性设计）：
     *
     * `onServiceDisconnected` 原先只把 `host = null`，**不做任何重连** ——
     * 于是断开之后**永久失联**，Activity 触发器再也不工作，只能靠重启 App 恢复。
     *
     * 而触发断开最常见的场景恰恰是**我们自己的部署流程**（重装 APK ⇒
     * App 进程被杀）与**热更新换代**。用户看到的是「装了新版本之后触发器就不灵了」。
     */
    @Volatile
    private var reconnectThread: Thread? = null

    /** 存活性巡检线程（兜底 `onServiceDisconnected` 不触发的情况）。 */
    @Volatile
    private var livenessThread: Thread? = null

    /**
     * binder 死亡回执（**已接线**，修缺陷 3）。
     *
     * ⚠️ **比 `onServiceDisconnected` 更可靠**：它由 binder 驱动**直接**回调，
     * 不依赖 AMS 是否记得通知我们 —— 而后者「无 FIN 时不一定触发」是本仓库
     * 反复记录的坑（`LogcatStreamWrapper` 吃过同一个亏，那也正是
     * [startLivenessWatchdog] 存在的理由）。
     *
     * ⚠️ 此前它**声明了却从未 `linkToDeath`**（死字段）。
     * 而 ③（能力调用）需要「断开时**立即唤醒**全部 waiter 回 error」——
     * 靠 15 秒一轮的存活性巡检太慢（用户要等巡检才发现失败）。
     * 接上它之后，App 进程死掉会**立刻**得到回调。
     */
    @Volatile
    private var deathRecipient: android.os.IBinder.DeathRecipient? = null

    override val isConnected: Boolean
        get() = host != null

    /** hook 层的回调 binder，交给 App 侧反向调用。 */
    private val callback = object : IHookCallback.Stub() {

        override fun pushConditions(conditionsJson: String, token: String): Boolean {
            val sink = conditionSink ?: return false
            return try {
                sink(conditionsJson, token)
                true
            } catch (t: Throwable) {
                log("pushConditions 处理失败：${t.javaClass.simpleName}")
                false
            }
        }

        override fun ping(): Int = com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec.PROTOCOL_VERSION

        /**
         * 连接期能力交换（§3.1）。
         *
         * ⚠️ **本类只提供「怎么答」，不提供「答什么」** —— 清单由运行时
         * （`HookRuntime`/hook 侧 capability 注册表）决定，本任务只把管道接通。
         *
         * ⚠️⚠️ **绝不能抛**：本方法跑在 system_server 的 binder 线程上，
         * 未捕获异常的危险性是「整机」（§5.1）。任何失败都吞掉并回空清单 ——
         * 空清单是**合法**的（判 `READY` 但一个能力都没有），
         * 而抛异常会让 App 侧判 `ABSENT`（误报「hook 层太旧」）。
         */
        override fun capabilities(): String = try {
            manifestProvider?.invoke() ?: emptyManifest()
        } catch (t: Throwable) {
            log("capabilities() 异常：${t.javaClass.simpleName}")
            emptyManifest()
        }

        /**
         * ③ 的统一入口（§3.4）。**oneway** —— 接单即返回，绝不在此执行 handler。
         *
         * ⚠️⚠️ **本任务只接通管道，不实现执行** —— 真正的分发（工作线程池 /
         * 超时 / 截断）是任务 3（hook_runtime）的范围。
         *
         * ⚠️ 当前的占位行为是「**显式回一个 `capability_absent` 响应**」，
         * **不是**静默吞掉。理由：
         * ① §4.3 要求「未知 capability **必须报错**」—— 调用方在等结果，
         *   静默会让它白等到超时，把排查引向「hook 点/系统版本」这些错误方向；
         * ② 静默正是本仓库反复记录的失效形态。
         *
         * ⚠️ 任务 3 接入真实注册表后，**这一整段会被替换**；届时
         * 「能力确实不存在」仍走同一个 `capability_absent` 码（语义一致）。
         */
        override fun invoke(requestJson: String) {
            try {
                val sink = invokeSink
                if (sink != null) {
                    sink(requestJson)
                    return
                }
                respondUnimplemented(requestJson)
            } catch (t: Throwable) {
                // ⚠️ 本方法在 system_server 的 binder 线程上，异常绝不能逃逸
                log("invoke 处理异常：${t.javaClass.simpleName} ${t.message}")
            }
        }
    }

    /** 空清单（见 [IHookCallback.capabilities] 的异常处理说明）。 */
    private fun emptyManifest(): String =
        com.chaomixian.vflow.xposed.wire.CapabilityManifest.encode(emptyList())

    /**
     * 能力清单的提供者。由 `VFlowHookEntry` 在接入运行时后注入。
     *
     * ⚠️ 抽成注入而非在本类里直接读 `HookRuntime`：本类只管**传输**，
     * 不该知道「清单从哪来」（那会让它同时依赖 runtime，形成环）。
     */
    @Volatile
    private var manifestProvider: (() -> String)? = null

    /** 注册能力清单提供者。 */
    fun onCapabilities(provider: () -> String) {
        manifestProvider = provider
    }

    /**
     * ③ 请求的处理者。由 `VFlowHookEntry` 在接入运行时后注入。
     *
     * ⚠️ 在注入之前，[callback] 的 `invoke` 回一个显式的 `capability_absent`
     * （见那里的注释）—— 不是静默吞掉。
     */
    @Volatile
    private var invokeSink: ((String) -> Unit)? = null

    /** 注册 ③ 请求处理者。 */
    fun onInvoke(sink: (requestJson: String) -> Unit) {
        invokeSink = sink
    }

    /**
     * 没有处理者时的显式应答（任务 3 接入前 / 接入失败的降级路径）。
     *
     * ⚠️ 回 `capability_absent` 而不是 `handler_error`：语义上「本 hook 层
     * 现在做不了这件事」与「这个 capability 不存在」对调用方的**处置相同**
     * （都指向「升级/重启」），且任务 3 接入后走的是同一个码。
     *
     * ⚠️ 只回、不重试、不排队 —— 绝不阻塞 binder 线程（§3.4 末）。
     */
    private fun respondUnimplemented(requestJson: String) {
        val req = com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
            .decodeRequest(requestJson) ?: run {
            // 连请求都解析不了 ⇒ 没有 request_id，无法配对，只能留日志
            log("invoke 收到无法解析的请求，已忽略（长度 ${requestJson.length}）")
            return
        }
        val hostRef = host ?: return
        val response = com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec.encodeResponse(
            requestId = req.requestId,
            ok = false,
            error = com.chaomixian.vflow.xposed.wire.CapabilityError(
                code = com.chaomixian.vflow.xposed.wire.CapabilityErrorCode.CAPABILITY_ABSENT,
                detail = "hook 侧尚未接入 ③ 执行运行时",
            ),
            token = req.token,
        )
        try {
            hostRef.resolve(response)
        } catch (t: Throwable) {
            log("回 capability_absent 失败：${t.javaClass.simpleName}")
        }
    }

    /**
     * 连接 App。
     *
     * ⚠️ **本方法立即返回**，真正的连接在后台线程里做（要等解锁 + 等服务就绪，
     * 合计可能 150 秒）。它跑在 `onSystemServerStarting` 的调用栈上 ——
     * **在那里阻塞会拖住 system_server 启动**。
     */
    override fun start() {
        if (waitThread?.isAlive == true) return
        stopped = false
        waitThread = Thread({ connectWithWaits() }, "VFlowHook-connect").apply {
            isDaemon = true
            start()
        }
        startLivenessWatchdog()
    }

    /** 注册条件回调。必须在 [start] 前后都可调用（幂等）。 */
    fun onConditions(sink: (conditionsJson: String, token: String) -> Unit) {
        conditionSink = sink
    }

    override fun stop() {
        stopped = true
        waitThread?.interrupt()
        waitThread = null
        reconnectThread?.interrupt()
        reconnectThread = null
        livenessThread?.interrupt()
        livenessThread = null
        unbindQuietly()
    }

    // ── 重连与存活性 ─────────────────────────────────────────────

    /**
     * 触发一次重连（**幂等**：已有重连线程在跑就忽略）。
     *
     * ⚠️ 退避上限 30 秒（见 [RECONNECT_MAX_MS]）—— 无限翻倍会让
     * 「重装 APK 之后」的用户等太久，体感就是功能坏了。
     */
    private fun scheduleReconnect(reason: String) {
        if (stopped) return
        if (reconnectThread?.isAlive == true) return

        log("⟳ 安排重连（原因：$reason）")
        reconnectThread = Thread({
            var delay = RECONNECT_BASE_MS
            var attempts = 0
            while (!stopped && host == null && shouldKeepRetrying(attempts)) {
                attempts++
                try {
                    Thread.sleep(delay)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped) return@Thread

                log("⟳ 尝试重连（累计退避 ${delay}ms）…")

                // ⚠️⚠️ 重新 bind **之前**必须先释放上一次的绑定。
                //
                // `unbindQuietly()` 此前**只**在 `stop()` 里调，而重连路径从不调 ⇒
                // 每次重连都在 AMS 里多留一条永远不会被回收的 `ConnectionRecord`。
                // 实测（2026-09-29）：`dumpsys activity services` 里的
                // distinct ConnectionRecord 7 → 8 → 9 单调增长（每轮重连 +1）。
                //
                // 此刻 `host` 已被 `onServiceDisconnected` / 存活性巡检置为 null，
                // 所以释放是安全的（且对已死的绑定调用 `unbindService` 也就是一次 no-op）。
                unbindQuietly()

                val submitted = try {
                    // 复用同一条「等服务就绪 + 等解锁 + bind」的路径 ——
                    // 它与首次连接面临的条件完全相同
                    doBind()
                } catch (t: Throwable) {
                    log("⟳ 重连异常：${t.javaClass.simpleName} ${t.message}")
                    false
                }

                // ⚠️⚠️ 必须等 `host` 落地再判成败 —— 见 [awaitConnected] 的注释。
                // 直接查 `host != null` 会让本轮变成一次多余的 bind。
                //
                // ⚠️ 等待窗口用 [AWAIT_TIMEOUT_MS] 而**不是**退避值 `delay`：
                // 首次退避只有 1000ms，而设备刚开机时连接落地可能更慢 ——
                // 用退避值当窗口会让「慢落地」被误判为失败，于是又 bind 一次，
                // 等于把这个 bug 以更低的频率留着。
                if (submitted && awaitConnected(AWAIT_TIMEOUT_MS)) {
                    log("⟳ ✅ 重连成功")
                    return@Thread
                }
                // 指数退避，封顶
                delay = nextBackoffMs(delay)
            }
            if (host == null && !stopped) {
                // ⚠️ 不静默：放弃重连意味着触发器整体失效，
                // 而用户只会看到「没反应」
                log("⟳ ⚠️ 重连尝试已达上限（${MAX_RECONNECT_ATTEMPTS} 次），放弃。" +
                    "Activity 触发器暂不可用；重启 App 或检查 LSPosed 配置后重试")
            }
        }, "VFlowHook-reconnect").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 存活性巡检。
     *
     * ⚠️ **兜底 `onServiceDisconnected` 不触发的情况** ——
     * 那是有先例的：`LogcatStreamWrapper` 曾因「socket 不发 FIN」而永久卡住
     * （见 `FORK.md` 的残留问题记录）。这里用 binder 的 `isBinderAlive` 主动查。
     */
    private fun startLivenessWatchdog() {
        if (livenessThread?.isAlive == true) return
        livenessThread = Thread({
            while (!stopped) {
                try {
                    Thread.sleep(LIVENESS_CHECK_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (stopped) return@Thread

                val h = host ?: continue
                // IHookHost 是 IInterface 的子接口，asBinder 直接可用
                val alive = try {
                    h.asBinder().isBinderAlive
                } catch (_: Throwable) {
                    // 取 binder 都失败 ⇒ 视为已断（与「还没连」不同）
                    false
                }

                if (!alive) {
                    log("⚠️ 巡检发现 hook 层已失联（onServiceDisconnected 未触发）")
                    host = null
                    scheduleReconnect("巡检失联")
                }
            }
        }, "VFlowHook-liveness").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * 两个等待 + bind。
     *
     * 全程 `try/catch(Throwable)` —— 本线程跑在 system_server 里，
     * 未捕获异常会危及整机（§5.1）。
     */
    private fun connectWithWaits() {
        try {
            var waited = 0L
            for (i in 0 until MAX_WAIT_ATTEMPTS) {
                if (stopped) return

                if (!waitSystemReadyAndUnlocked(i)) {
                    sleepQuietly()
                    waited += POLL_INTERVAL_MS
                    continue
                }

                log("#14 系统服务已就绪【且已解锁】（等待 ${waited}ms）")
                doBind()
                return
            }
            log("#14 等「服务就绪 + 用户解锁」超时（${MAX_WAIT_ATTEMPTS * POLL_INTERVAL_MS / 1000}s），放弃")
        } catch (t: Throwable) {
            log("#14 连接线程异常：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /**
     * @return true = 两个条件都满足，可以 bind 了
     */
    private fun waitSystemReadyAndUnlocked(attempt: Int): Boolean {
        val ctx = contextProvider() ?: return false

        // 判据①：系统服务就绪。onSystemServerStarting 时它是 null
        val pm = try {
            ctx.packageManager
        } catch (_: Throwable) {
            null
        } ?: return false

        // 判据②：⭐ 用户已解锁。这才是 P0 那个「假的连不上」的真因
        val um = try {
            ctx.getSystemService(Context.USER_SERVICE) as? UserManager
        } catch (_: Throwable) {
            null
        } ?: return false

        if (!um.isUserUnlocked) {
            // 每 10 次（5 秒）打一条，避免刷屏
            if (attempt % 10 == 0) {
                log("等待用户解锁…（已等 ${(attempt + 1) * POLL_INTERVAL_MS}ms）")
            }
            return false
        }

        // 顺带用一下 pm，避免「取了没用」被优化掉（也让日志能证明它真的非空）
        @Suppress("UNUSED_EXPRESSION")
        pm
        return true
    }

    private fun doBind(): Boolean {
        val ctx = contextProvider() ?: run {
            log("#14 拿不到 system context，放弃连接")
            return false
        }

        val intent = Intent().setComponent(component)

        // 先用 resolveService 做一次「看得见吗」的判据 ——
        // 探针实测：这一步在「未解锁」与「包不可见」两种情况下都会返 null，
        // 所以**它只能证明「现在不行」，不能证明「永远不行」**。
        // 保留它是因为它能在日志里把「哪一步失败」定位得更细。
        val resolved = try {
            ctx.packageManager.resolveService(intent, 0)
        } catch (t: Throwable) {
            log("#14 resolveService 抛异常：${t.javaClass.simpleName}（不代表组件不存在）")
            null
        }
        log("#14 resolveService(...) = ${resolved?.serviceInfo?.name ?: "null"}")

        val ok = try {
            ctx.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (t: Throwable) {
            log("#14 bindService 抛异常：${t.javaClass.simpleName} ${t.message}")
            false
        }

        log("#14 bindService() 返回 $ok${if (ok) " ⇒ 已提交" else " ⇒ ❌ 失败"}")

        if (!ok) {
            // ⚠️ 这条日志是**判定 #15（signature 权限）的关键线索**：
            // 若带 android:permission 的 Service bind 失败，而去掉权限就成功，
            // 就说明 system_server **没有**持有本 App 的 signature 权限
            //（uid 1000 并不天然豁免 signature 权限检查）。
            // 此时下行加固只能靠 token，见 HookChannelService 的注释。
            log("#14 ⚠️ bind 失败。若 Service 挂了 signature 权限，说明 system_server 未持有它")
        }
        return ok
    }

    /**
     * 等连接落地（`host` 被置上），上限 [timeoutMs]。
     *
     * ⚠️ **存在理由**：`bindService()` 返回 true 只代表「**已提交**」——
     * `host` 是由 `onServiceConnected` 在 **system_server 主线程**上**异步**设置的
     * （见 [connection] 的注释）。调用方若在 `doBind()` 之后**立刻**判 `host != null`，
     * 那一刻必然是 null。
     *
     * 这正是缺陷 20 的成因：`scheduleReconnect` 的循环据此继续下一轮退避，
     * 于是**又 bind 一次** —— 实测每轮重连 **2 次** `bindService()`，
     * 而 `dumpsys` 里 AMS 的 `ConnectionRecord` **只增不减**
     * （`unbindQuietly()` 只在 `stop()` 里调，重连路径从不调），
     * 每次都往 system_server 里留下永不释放的记录。
     */
    private fun awaitConnected(timeoutMs: Long): Boolean {
        var waited = 0L
        while (waited < timeoutMs) {
            if (host != null) return true
            if (stopped) return false
            try {
                Thread.sleep(AWAIT_STEP_MS)
            } catch (_: InterruptedException) {
                return host != null
            }
            waited += AWAIT_STEP_MS
        }
        return host != null
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                val h = IHookHost.Stub.asInterface(service)
                if (h == null) {
                    log("#14 onServiceConnected 但 asInterface 返回 null")
                    return
                }
                host = h
                log("★★★★ bindService 成功，拿到 binder=$service")
                linkDeathRecipient(service)

                // 交出回调 binder —— 这一步**同步返回**，用来判定鉴权是否放行
                val registered = try {
                    h.registerCallback(callback)
                } catch (t: Throwable) {
                    log("registerCallback 失败：${t.javaClass.simpleName} ${t.message}")
                    false
                }

                if (registered) {
                    log("✅ registerCallback 成功（App 侧已知我方存在）")
                    // ⚠️ 连接（重新）建立后通知上层 —— 重连场景下 hook 层可能
                    // **刚换代、hook 点没挂上**，条件也可能被清空（决策 14：条件不落盘）。
                    // 不通知的话，「连上了但什么都不触发」。
                    // ⚠️ 逐个通知，一个失败不影响其他（修缺陷 12）
                    for ((key, sink) in onConnectedSinks) {
                        try {
                            sink()
                        } catch (t: Throwable) {
                            log("连接回调失败（$key）：${t.javaClass.simpleName}")
                        }
                    }
                } else {
                    // 不静默：注册失败意味着 App 不会下发条件，而用户只会看到「没反应」
                    log("❌ registerCallback 被拒 —— 大概率是 signature 权限没放行（#15）")
                }
            } catch (t: Throwable) {
                log("onServiceConnected 异常：${t.javaClass.simpleName} ${t.message}")
            }
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            // App 进程被杀 / 被 force-stop。⚠️ 它**不一定触发**（无 FIN 时），
            // 所以还有 [startLivenessWatchdog] 兜底。
            //
            // ⚠️⚠️ **必须安排重连** —— 这是实测暴露的真实缺陷：
            // 原先这里只 `host = null`，于是断开后**永久失联**，
            // Activity 触发器再也不工作，只能靠重启 App 恢复。
            // 而触发它的最常见场景恰恰是**我们自己的部署流程**（重装 APK ⇒ App 被杀）
            // 与**热更新换代** —— 用户看到的是「装了新版本之后触发器就不灵了」。
            log("onServiceDisconnected —— App 侧连接断开")
            host = null
            scheduleReconnect("onServiceDisconnected")
        }
    }

    /**
     * 给 App 侧的 binder 挂死亡回执（修缺陷 3）。
     *
     * ⚠️ 挂之前先 `unlink` 旧的 —— 否则每次重连都会往**已经死掉的**
     * 那个 binder 上再挂一个，且旧的永远不解绑（与缺陷 20 的
     * `ConnectionRecord` 泄漏是同一种「只增不减」形态）。
     *
     * ⚠️ 全程 `try/catch`：本方法跑在 system_server 里，且 `linkToDeath`
     * 对**已经死掉的** binder 会抛 `DeadObjectException`。
     */
    private fun linkDeathRecipient(service: android.os.IBinder?) {
        if (service == null) return
        unlinkDeathRecipient()
        val r = android.os.IBinder.DeathRecipient {
            // ⚠️ 这个回调在 binder 驱动线程上，必须只做「清状态 + 安排重连」
            try {
                log("💀 App 侧 binder 死亡（deathRecipient）—— 走重连")
                host = null
                scheduleReconnect("binder 死亡")
            } catch (t: Throwable) {
                log("deathRecipient 处理异常：${t.javaClass.simpleName}")
            }
        }
        try {
            service.linkToDeath(r, 0)
            deathRecipient = r
        } catch (t: Throwable) {
            // 不静默：挂不上意味着只剩 15 秒巡检这一条兜底路径
            log("linkToDeath 失败：${t.javaClass.simpleName} ${t.message}（退回存活性巡检）")
            deathRecipient = null
        }
    }

    /** 解绑死亡回执。`unbindQuietly()` 与 [linkDeathRecipient] 都会调。 */
    private fun unlinkDeathRecipient() {
        val r = deathRecipient ?: return
        try {
            host?.asBinder()?.unlinkToDeath(r, 0)
        } catch (_: Throwable) {
            // binder 已死时 unlink 会抛 —— 忽略即可，回执本身已无意义
        }
        deathRecipient = null
    }

    override fun send(envelopeJson: String): Boolean {
        val h = host ?: return false
        return try {
            h.report(envelopeJson)
            true
        } catch (t: Throwable) {
            // oneway 调用在这里几乎不会抛，但 RemoteException 仍可能
            log("report 异常：${t.javaClass.simpleName}")
            false
        }
    }

    private fun unbindQuietly() {
        // ⚠️ 顺序：先解死亡回执，再 unbind（修缺陷 3）——
        // 否则 unbind 之后 host 已是 null，`unlinkToDeath` 再也拿不到 binder。
        unlinkDeathRecipient()
        val ctx = contextProvider() ?: return
        try {
            ctx.unbindService(connection)
        } catch (_: Throwable) {
        }
        host = null
    }

    private fun sleepQuietly() {
        try {
            Thread.sleep(POLL_INTERVAL_MS)
        } catch (_: InterruptedException) {
        }
    }

    private fun log(msg: String) {
        try {
            android.util.Log.e(TAG, msg)
        } catch (_: Throwable) {
        }
    }
}
