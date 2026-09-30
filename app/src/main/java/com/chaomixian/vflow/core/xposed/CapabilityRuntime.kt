package com.chaomixian.vflow.core.xposed

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * ③ 能力调用运行时的**启动接线点**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.3；
 * 定案记录见 `FORK.md` 的「Xposed 通道 ③」段。
 *
 * ## 它做什么 / 不做什么
 *
 * | 做 | 不做 |
 * |---|---|
 * | 把 [CapabilityPresenceHolder] 接到 `HookChannelController` 的两个方向 | ❌ 注册任何 capability（那是 `CapabilityFallbacks`，且 T1 为空） |
 * | 交给 [CapabilityInvoker] 一个 appContext（构造「连接已断」的提示串用） | ❌ 做任何 IO / 跨进程调用（**接线本身是同步的轻量调用**） |
 *
 * ## ⚠️⚠️ 为什么 `isEnabled()` 默认是 `true`（而不是「先关着、验证过再打开」）
 *
 * 本方案前一版取 `false`，理由是「打开开关会新增启动期的跨进程调用」——
 * **那个措辞不准确**，已更正。准确的事实是：**那两条 binder 往返落在一个本来就有事在做的
 * 路径上，而且更重**。同一条连接建立路径上**已经**有两个生产消费者：
 *
 * | 位置 | 在做什么 |
 * |---|---|
 * | `VFlowHookEntry.kt:223` `transport.onConnected("VFlowHookEntry.remount")` | `loadClass` + `getDeclaredMethods` + **`hook()`**（hook 层侧） |
 * | `ActivityChangedTriggerHandler.kt:91` `setOnConnectedListener(SINK_KEY)` | **同步** binder 调用 `pushConditions`（App 侧） |
 *
 * 而 `notifyOnConnected()` 是**按 key 逐个 `try/catch`** 地通知监听器
 * ⇒ 本模块加的是**同一条路径上的第三个监听器**，新增成本是
 * 「**一次 `ping()` + 一次 `capabilities()` 两次 binder 往返**」，
 * **不是**「新增一条启动期跨进程路径」。
 *
 * 本仓库那三次「模块不加载」的历史留下的纪律是
 * 「**一次只改一处 + 始终保留可回退版本**」——它约束的是**改动方式**，不是「禁止改动」。
 * T1 的接线**就是那「一处」**，而本开关本身就是回退开关。
 *
 * ## ⚠️ 取 `false` 有一个具体的静默劣化模式（这才是默认 `true` 的真正理由）
 *
 * ```
 * attach 挂在 VFlowApplication ⇒ 那是【主 App 进程】
 * hook 层的连接建立发生在【system_server 侧】，且 notifyOnConnected 是【推送的、不重放】
 * 开关关着 ⇒ CapabilityPresenceHolder 不注册监听 ⇒ 那一次连接建立被【永久错过】
 *         ⇒ presence 停在 UNKNOWN
 *         ⇒ CapabilityInvoker 的「presence == ABSENT 短路」永不生效
 *         ⇒ ⚠️ 每次调用都要白等满 5 秒超时
 * ```
 *
 * 唯一的恢复办法是**重启 App**（让 `attach` 重跑）——
 * 正是本仓库反复记录的那类「看起来能用、其实在静默劣化」。
 *
 * ## ⚠️⚠️ 开关的语义是**运行时判定**，不是「`start()` 时判一次」
 *
 * 上面那个失败模式的根因是「判定发生得太早」。所以
 * `CapabilityPresenceHolder.onConnectNotified()` 里**每次被通知时都自检**
 * `CapabilityRuntime.isEnabled()` —— 见那里的注释。
 * 若只在 `start()` 时判一次，这个开关就是**假开关**
 *（关掉它之后监听器照跑），而「回退」正是它最主要的用途。
 */
object CapabilityRuntime {

    private const val TAG = "CapabilityRuntime"

    /**
     * 开关。
     *
     * ⚠️ 用 `AtomicReference`（而不是 `@Volatile var`）：将来若有「读-改-写」的
     * 使用方式，`@Volatile` 的复合操作不是原子的。当前只有赋值与读取，
     * 但这层保障是免费的。
     */
    private val enabled = AtomicReference(true)

    private val attached = AtomicReference(false)

    /** ③ 的运行时是否启用。⚠️ 消费点必须**每次调用时**读它，不要缓存。 */
    fun isEnabled(): Boolean = enabled.get()

    /**
     * 启动接线。**幂等**。
     *
     * ⚠️ 由 `VFlowApplication.onCreate` 调用一行。它**只做接线**：
     * - 不注册任何 capability；
     * - 不做 IO、不做跨进程调用；
     * - 真正的探测由 [CapabilityPresenceHolder] 在**连接建立**时触发
     *   （因此与「`HookChannelService` 还是 hook 层先启动」无关）。
     *
     * ⚠️ `context` 允许为 null（且当前**只用于**日志上下文，不持有）：
     * 本类的接线不依赖 Context，而 `VFlowApplication` 一定会传。
     * 允许 null 是为了让单测能直接调它而不必造 Context。
     */
    fun attach(context: Context?) {
        if (!attached.compareAndSet(false, true)) {
            DebugLogger.d(TAG, "已接线，跳过重复 attach")
            return
        }

        // ⚠️ 先交给 CapabilityInvoker 一个 appContext（它构造「连接已断」的
        // 人类可读提示时可能要用），再决定要不要起能力交换。
        CapabilityInvoker.attach(context)

        // ⚠️ 能力表的装配与开关**无关** —— 它是「本端有哪些能力」的声明，
        // 即使 ③ 运行时被关掉，注册表也该是完整的（将来别的消费者要用它）。
        // ⚠️ T1 阶段它是**空实现**（首个真能力是 T3 的交付物），
        // 但调用点必须现在就接上 —— 否则 T3 加能力时会漏掉接线（反模式 6）。
        CapabilityFallbacks.registerAll()

        // ⚠️⚠️ **接线无条件做，开关只控制「探测」**（`CapabilityPresenceHolder` 里判）。
        //
        // 本文件第一版在这里 `if (!enabled.get()) return` —— **那是错的**，
        // 而且错法正是本类要避免的那个失败模式：开关关着时不接线 ⇒ 监听器不在册 ⇒
        // 之后**重新打开开关也永远不生效**（除非重启 App 让 `attach` 重跑）。
        // 由 `CapabilityPresenceHolderTest.re-enabling the runtime takes effect
        // on the next connection` 抓出来。
        //
        // 接线本身是**纯注册**（`ConcurrentHashMap.put` + 一次 `isConnected()` 纯读），
        // 没有跨进程调用 —— 它不该受开关节制。开关管的是「要不要真的去 ping/capabilities」。
        CapabilityPresenceHolder.start()
        DebugLogger.i(
            TAG,
            "③ 能力调用运行时已接线（能力交换" + if (enabled.get()) "已启用" else "被开关关闭" + "）",
        )
    }

    /**
     * **仅供测试**：开关某个状态。
     *
     * ⚠️ 它必须**真的影响下一次连接建立的判定** —— 这条由
     * `CapabilityPresenceHolderTest` 的「回退开关是运行时语义」用例锁住
     *（关掉它之后再触发一次连接建立 ⇒ 不探测）。
     * 若把判定挪到 `start()` 里，那条用例会红 —— 那正是它存在的意义。
     */
    internal fun setEnabledForTest(value: Boolean) {
        enabled.set(value)
    }

    /** **仅供测试**：回到初始状态。 */
    internal fun resetForTest() {
        attached.set(false)
        enabled.set(true)
    }
}
