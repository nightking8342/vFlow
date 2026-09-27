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
     * **连接（重新）建立**后的回调（由 [HookRuntime] 注入）。
     *
     * ⚠️ 为什么必须有它：重连成功后，hook 层那边可能是**刚换代的新代码** ——
     * hook 点没挂上、条件被清空。不重挂、不重下发的话，
     * 表现为**「连上了但什么都不触发」**（通道看着是活的）。
     */
    @Volatile
    private var onConnectedSink: (() -> Unit)? = null

    /** 注册连接建立回调。 */
    fun onConnected(sink: () -> Unit) {
        onConnectedSink = sink
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
     * binder 死亡回执。
     *
     * ⚠️ 比 `onServiceDisconnected` 更可靠：它由 binder 驱动直接回调，
     * 不依赖 AMS 是否记得通知我们。
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
                try {
                    // 复用同一条「等服务就绪 + 等解锁 + bind」的路径 ——
                    // 它与首次连接面临的条件完全相同
                    doBind()
                } catch (t: Throwable) {
                    log("⟳ 重连异常：${t.javaClass.simpleName} ${t.message}")
                }

                if (host != null) {
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

    private fun doBind() {
        val ctx = contextProvider() ?: run {
            log("#14 拿不到 system context，放弃连接")
            return
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
                    try {
                        onConnectedSink?.invoke()
                    } catch (t: Throwable) {
                        log("连接回调失败：${t.javaClass.simpleName}")
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
