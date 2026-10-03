package com.chaomixian.vflow.xposed.capabilities

import android.os.Handler
import android.os.HandlerThread
import com.chaomixian.vflow.xposed.HookLog
import com.chaomixian.vflow.xposed.wire.Budgeted
import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityManifest
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import com.chaomixian.vflow.xposed.wire.ResultBudget
import com.chaomixian.vflow.xposed.wire.ThreadModes
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * ③（能力调用）的 **hook 侧执行运行时** —— 在 system_server 里安全地执行 capability。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.4 / §5.2 / §5.6 / §6.4。
 *
 * ## ⚠️⚠️ 一句话：任何异常、任何阻塞、任何超大载荷**都不允许越过这道边界**
 *
 * 因为越过 = **整机**崩溃 / 整机卡死（本文件跑在 system_server 里，§5.6）。
 * 这条纪律与「hook 层所有回调都包 `try/catch`」同源，但本类**额外覆盖工作线程** ——
 * 那是最容易被漏掉的一处（回调包了、投出去的 Runnable 没包）。
 *
 * ## 职责划分（三处边界）
 *
 * | 执行位置 | 做什么 | **绝不能**做什么 |
 * |---|---|---|
 * | **binder 线程**（[onInvoke]） | 解码 + 校验 + 查注册表 + 投递 | 执行 handler、阻塞、排队 |
 * | **工作线程**（[runOnWorker]） | 执行 handler + 超时判定 + 截断 | 让异常逃逸 |
 * | 回响应的出口（构造参数 `respond`） | 交给对端（`IHookHost.resolve`） | 自己编信封（那是 codec 的活） |
 *
 * ## ⚠️⚠️ 执行器：**三档协程 dispatcher**（2026-10-03 替换了自建有界池）
 *
 * 请求里的 `thread_mode` 决定用哪一档（见 [ThreadModes]），未知一律回落 `default`：
 *
 * | 档 | dispatcher | 「满」时怎么办 | 用户选它的理由 |
 * |---|---|---|---|
 * | `default` | `Dispatchers.Default` | **弹性建线程**（不排队） | 我是**算**的（CPU 密集） |
 * | `io` | `Dispatchers.IO` | **排队**（64 并发上限） | 我是**等**的（阻塞调用） |
 * | `ui` | 自建 `HandlerThread`（容量 1） | **无界排队** ⇒ 本类加了安全阀 | 我**需要 Looper** |
 *
 * ⚠️⚠️ **三档的「满」行为【不同】，且这是有意的** —— 它们的容量来自库与 `Looper` 的
 * 物理常量，**不是取舍**（设计文档 §1.4 末表）。**不要试图统一它们。**
 *
 * ## ⚠️ 为什么不再自建 `ThreadPoolExecutor`
 *
 * 旧的「容量 2 + `SynchronousQueue`（不排队、满即拒）」只能表达**一种**执行环境，
 * 而用户的需求有三种（算 / 等 / 需要 Looper）。三档分别对应三种资源画像，
 * 自建池得建三个 —— 而那正是 `xposed-thread-modes-design.md` §1.3 被否决的方案
 *（协程库与 `HandlerThread` 已经把这三件事做好了，且它们的容量是既定事实而非拍的）。
 *
 * ⚠️ **ShortX 同样不自建池**（一行 `ThreadPoolExecutor` 都没有）—— 外部旁证。
 *
 * ## ⚠️⚠️ 两档新增的失败模式（本替换**主动引入**的，必须知道）
 *
 * 1. **`ui` 档从「立刻拒绝」变成「无界排队」** —— `Handler.post` 永不拒绝，
 *    而 `Looper` 只有 1 个线程 ⇒ 一个卡死的 UI 脚本会让队列无限涨到 OOM，
 *    而本进程是 **system_server** ⇒ 后果是**整机**。
 *    缓解是 [InvokePolicy.MAX_UI_QUEUE] 这道安全阀（超了回 `handler_error`）。
 * 2. **`default`/`io` 档的「池满」语义消失** —— 改为「排队等，最终 App 侧超时」。
 *    ⚠️ 用户看到的排查方向从「工作线程池已满」变成「超时」，**这不是回归**，
 *    而是「三档各自的天然行为」。
 *
 * ## ⚠️⚠️ 超时是**事后判定**，不做看门狗强制应答
 *
 * **做**：工作线程跑完 handler 后算 `elapsedMs`，`> budget` 就回 `TIMEOUT`（不回结果）。
 * **不做**：起定时器到点抢先回 `TIMEOUT`。三条理由：
 *
 * 1. ⚠️ **非脚本 handler 无法被中断** —— 看门狗**不会**让占着的工作线程释放，
 *    它只会让读代码的人以为「超时后线程回来了」，是个**误导性的复杂度**。
 * 2. 看门狗与工作线程会**同时**产生响应 ⇒ 需要「单次应答」的原子保证；
 *    而少一次响应好过发两次（第二次会被 App 侧当「无配对的响应」告警）。
 * 3. App 侧的等待窗口就是 `timeout_ms`；handler 真跑超了，**结果迟到 = 结果无用**，
 *    回 `TIMEOUT` 让两端日志能对上，比回一个已被丢弃的成功结果更有用。
 *
 * ### ⚠️ 本限制必须被承认（不是「暂时」的）
 *
 * **一个永久卡住的 handler 永远不会产生响应**，且那个工作线程**永久被占用**。
 * App 侧靠第一层超时兜（`HookChannelController` 的配对表），
 * 而这里**没有**补救手段 —— 这正是「总时长上限」的兜底方式。
 * ⚠️ 三档之后这条**更宽松了**（`default`/`io` 会排队、不会因满而拒），
 * 但 `ui` 档的容量仍是 1 —— 那道安全阀见 [InvokePolicy.MAX_UI_QUEUE]。
 *
 * ⚠️ **Rhino 指令级超时不在本类范围**（那是脚本类 handler 自己的事）。
 *
 * ## 依赖白名单
 *
 * 本文件在 `xposed/` 下（会被 hook 层加载），只允许 `org.json` / `java.*` / `kotlin.*` /
 * `kotlinx.coroutines.*` / `com.chaomixian.vflow.xposed.*`。
 * ⚠️ 两个新 android import（`android.os.Handler` / `android.os.HandlerThread`）
 * 已登记进 `WireLayerPurityTest.ANDROID_ALLOWLIST` —— 它们都是纯线程原语、无 App Context 初始化。
 */
class HookCapabilityRuntime(
    /**
     * 回响应的出口。
     *
     * ## ⚠️⚠️ 抽成函数类型 ⇒ **本类纯 JVM 可测**
     *
     * 这是「handler 跑在 LSPosed 的 ClassLoader 里、App 侧单测跑不到」（§7.2b-9）
     * 的唯一破解方式：把**执行逻辑**与**回响应**解耦，前者就能在普通 JVM 里跑。
     *
     * ## ⚠️ 签名是「一个编好的信封串」（2026-10-01 改）
     *
     * 旧签名是七个字段，由调用方（`VFlowHookEntry`）去编码 —— 那让本类的
     * **预算校验**手里只有 `resultJson`、量不到真正发出去的信封，
     * 真机实测撞了 `TransactionTooLargeException`（见 [InvokePolicy.PARCEL_PER_CODE_UNIT]）。
     *
     * ⇒ 编码移进本类（[encodeResponse]），出口只收串：
     * 「量的对象」与「发的对象」**在类型上就是同一个**。
     */
    private val respond: CapabilityResponder,
    private val registry: HookCapabilityRegistry = HookCapabilityRegistry,
    /**
     * `mode → dispatcher` 表。
     *
     * ## ⚠️ 这个接缝是**三档分发唯一能在纯 JVM 测的方式**
     *
     * 形态照仓库既有的 `injectTokenForTest` —— **不要删**。
     * 单测注入**记录身份的假 dispatcher** 来断言「`default`/`io`/`ui` 各落自己那个」；
     * 而真 `ui` 档在纯 JVM 里起不来（`Looper.myLooper()` 抛 `not mocked`），
     * 那条语义只能由 instrumented 测试覆盖（见 §7.1）。
     *
     * ⚠️ 生产路径**不传**它 —— 走 [defaultDispatchers]。
     */
    private val dispatchers: Map<String, CoroutineDispatcher> = defaultDispatchers(),
) {

    companion object {
        /**
         * 默认三档：`default` / `io` / `ui`。
         *
         * ⚠️ `ui` 档的 `HandlerThread` **懒启动**（见 [UiDispatcherHolder]）——
         * 没有任何脚本用 `ui` 档时不会建那个线程。这不是优化，是「不用的东西不占 system_server 资源」。
         */
        fun defaultDispatchers(): Map<String, CoroutineDispatcher> = mapOf(
            ThreadModes.DEFAULT to Dispatchers.Default,
            ThreadModes.IO to Dispatchers.IO,
            ThreadModes.UI to UiDispatcherHolder.dispatcher(),
        )

        /**
         * 终检超限后**最多收缩几轮**（2026-10-01 真机修复）。
         *
         * 每轮按「超出量 / 平均单项字节」估算该裁多少（至少 1 项）⇒ 收敛很快，
         * 32 是远高于实际需要的上界；设上界只为**保证循环终止**（不设的话，
         * 若 `estimatedParcelBytes` 对某项恒高估就会原地打转）。
         */
        const val MAX_SHRINK_ATTEMPTS = 32

        /**
         * ⚠️⚠️ **已废弃（2026-10-01）**：真机实测证明这条余量的**机制描述是错的**。
         *
         * 它原先的语义是「固定键 + JSON 转义与逗号的余量」（4 KiB）。
         * 而实测（`resolve` 报 `data parcel size 533700 bytes`）查明：真正的开销
         * **不是**二次转义（对全 ASCII 载荷只有 1.03×），而是
         * **信封在 parcel 里按 UTF-16 代码单元 × 2 字节计费** ⇒ 是 UTF-8 的 **2 倍**。
         *
         * ⇒ 4 KiB 的余量在一个 256 KiB 的预算上**差了 260 KiB**，完全兜不住。
         * 真正的开销模型现在是 [InvokePolicy.estimatedParcelBytes] +
         * [InvokePolicy.ITEM_ESCAPE_OVERHEAD_BYTES]，固定键开销走
         * [InvokePolicy.ENVELOPE_CARRIER_BYTES]。
         *
         * ⚠️ 保留它只为让 `FORK.md` 里的旧登记与新代码对得上；**不要再用**。
         */
        @Deprecated("被 InvokePolicy 的 parcel 口径取代，见其 KDoc 的实测记录")
        const val RESULT_ENVELOPE_MARGIN_BYTES = 4 * 1024

        private const val TAG = "VFlowHook"
    }

    /** 协议版本。⚠️ 收敛到 `EventEnvelopeCodec` 一处（§5.5「版本号收敛」）。 */
    private val protocolVersion: Int = EventEnvelopeCodec.PROTOCOL_VERSION

    /**
     * 已停止标志。
     *
     * ⚠️⚠️ **`stop()` 必须先置它、再 `cancel()`** —— [onInvoke] 读的就是它。
     * ⚠️ 两者**都不能少**：`CoroutineScope.cancel()` 之后的 `launch` 是**静默 no-op**
     * （不抛、不执行），若不先判标志，那种情形会表现为「**没有任何响应**」，
     * 而用户看到的是「超时」—— 与真实原因（运行时已停）不符。
     */
    @Volatile
    private var stopped = false

    /** `ui` 档的待执行计数（安全阀用，见 [InvokePolicy.MAX_UI_QUEUE]）。 */
    private val uiPending = AtomicInteger(0)

    /**
     * 每档一个 scope（都在**构造期**建）。
     *
     * ⚠️ 每档**独立的 `SupervisorJob`** —— 一个 handler 抛异常不该拖垮同档其他协程
     * （`runOnWorker` 已顶层 `try/catch(Throwable)`，这是**双保险**）。
     *
     * ⚠️ `CoroutineName` 让 logcat 能认出档位（`DefaultDispatcher-worker-*` /
     * `VFlowHook-ui`）。
     */
    private val scopes: Map<String, CoroutineScope> = dispatchers.mapValues { (mode, d) ->
        CoroutineScope(d + SupervisorJob() + CoroutineName("VFlowHook-cap-$mode"))
    }

    // ── binder 线程入口 ─────────────────────────────────────

    /**
     * ★★ **binder 线程调用**（`IHookCallback.invoke` 是 oneway）。
     *
     * 只做四件事：**解码 → 校验 → 查注册表 → 投递**，然后**立刻返回**。
     * 绝不在这里执行 handler，绝不阻塞，绝不排队（§3.4 末）。
     *
     * ⚠️ 本方法**整体**包在 `try/catch(Throwable)` 里 —— 它跑在 system_server
     * 的 binder 线程上，未捕获异常的危险性是**整机**（§5.6）。
     * `BinderTransport` 的 `invoke` 也包了一层，但**那不能替代这里**：
     * 本方法会被单测直接调用，而且双重防护在 system_server 里不算冗余。
     */
    fun onInvoke(requestJson: String) {
        // ★★ 全链路**最早**的点：请求刚抵达 binder 线程。
        // 工作线程用它算「排队等了多久」（见 [runOnWorker] 的出队判过期）。
        //
        // ⚠️ 是**局部变量**，随 `pool.execute { … }` 的闭包传给工作线程 ——
        // 不加字段、不改 `CapabilityRequest`（那是跨进程 codec 类，加字段 = 改协议）、
        // 不跨进程：到达时刻是 **hook 侧进程内**的事实。
        //
        // ⚠️ 取在 `try` **之外**：即使后面解码失败需要早退，取值本身也无副作用
        //（`nanoTime` 不抛）。反过来放在 try 里会让「早退路径没有它」看起来像个缺口。
        val arrivedAtMs = System.nanoTime()
        try {
            val request = try {
                CapabilityInvocationCodec.decodeRequest(requestJson)
            } catch (_: Throwable) {
                null
            } ?: run {
                // ⚠️ 连 request_id 都没有 ⇒ **无法配对**，回响应也没人要。
                // 只能留日志（这是唯一一处「不报错」的合法情形）。
                HookLog.e("$TAG  invoke 收到无法解析的请求，已忽略（长度 ${requestJson.length}）")
                return
            }

            if (request.protocolVersion != -1 && request.protocolVersion != protocolVersion) {
                // ⚠️ 只 warn、不拒绝（§3.5）：版本错配时**尽量让调用有结果**
                // 比直接失败更有用 —— 协议是加法演进的。
                HookLog.e(
                    "$TAG  协议版本不符（${request.protocolVersion} vs $protocolVersion），" +
                        "按 §3.5 继续处理：${request.capability}",
                )
            }

            HookLog.e("$TAG  收到 invoke：${request.capability}（request_id=${request.requestId}）")

            // ⚠️⚠️ 未知 capability **必须显式报错**（§4.3），与事件侧「未知 topic 忽略」
            // **方向相反**：调用方在等结果，静默会让它白等到超时，
            // 并把排查引向「hook 点 / 系统版本」这些错误方向。
            val handler = registry.find(request.capability)
            if (handler == null) {
                HookLog.e(
                    "$TAG  未知名，回 capability_absent（已注册：${registry.names().sorted()}）",
                )
                emitFailure(
                    request,
                    InvokePolicy.unknownCapabilityError(request.capability, registry.names()),
                    elapsedMs = 0L,
                )
                return
            }

            // ⚠️ 先判「已停止」再投递 —— `scope.cancel()` 之后的 `launch` 是**静默 no-op**，
            // 若不先判，那种情形会表现为「**没有任何响应**」（用户看到的是「超时」），
            // 而真实原因是「运行时已停」—— 两个完全不同的排查方向。
            if (stopped) {
                emitFailure(request, InvokePolicy.runtimeStoppedError(), elapsedMs = 0L)
                return
            }

            // ── 按 `thread_mode` 选执行器（未知 / null ⇒ default，绝不抛）──
            //
            // ⚠️ 表缺项也走 default（`?:` 兜住）—— 注入假 dispatcher 的测试可以
            // 只给一个档，那种情形不该崩。
            val mode = InvokePolicy.threadModeOf(request)
            val scope = scopes[mode] ?: scopes.getValue(ThreadModes.DEFAULT)
            val dispatcher = dispatchers[mode] ?: Dispatchers.Default

            // ── ⚠️ UI 档的**有界保护**（见 [InvokePolicy.MAX_UI_QUEUE]）──
            //
            // `Handler.post` **永不拒绝** ⇒ UI 档（1 个线程）会无界排队 ⇒
            // 一个卡死的脚本能让队列涨到 OOM。而本进程是 **system_server**
            // ⇒ OOM 的后果是**整机**。这是本次替换**主动引入**的失败模式，
            // 安全阀是本类主动加的缓解。
            val isUi = dispatcher === dispatchers[ThreadModes.UI]
            if (isUi) {
                val pending = uiPending.incrementAndGet()
                if (pending > InvokePolicy.MAX_UI_QUEUE) {
                    uiPending.decrementAndGet()
                    HookLog.e(
                        "$TAG  UI 档待处理过多（$pending > ${InvokePolicy.MAX_UI_QUEUE}），" +
                            "回 handler_error（request_id=${request.requestId}）",
                    )
                    emitFailure(request, InvokePolicy.uiQueueOverflowError(pending), elapsedMs = 0L)
                    return
                }
            }

            HookLog.e(
                "$TAG  分发：${request.capability} → 档=$mode" +
                    "（request_id=${request.requestId}）",
            )

            // ⚠️ `scope.launch` **不阻塞 binder 线程、不等结果** ——
            // 语义与旧的 `pool.execute` 一致（§5.1.1）。
            // ⚠️ 绝不 `launch` 后再同步等待（`runBlocking` / `future.await`）——
            // 那会占住 binder 线程，违反「binder 线程只投递不执行」。
            scope.launch {
                try {
                    // ⚠️ 打**执行线程名**（在档位线程上打，不是 binder 线程）——
                    // 它是「三档真的落不同线程」唯一可从 logcat 观测的证据
                    //（`default`/`io` → `DefaultDispatcher-worker-*`，`ui` → `VFlowHook-ui`）。
                    HookLog.e(
                        "$TAG  执行：${request.capability} 档=$mode " +
                            "线程=${Thread.currentThread().name}" +
                            "（request_id=${request.requestId}）",
                    )
                    runOnWorker(request, handler, arrivedAtMs)
                } finally {
                    // ⚠️ 递减必须在 finally —— 否则 handler 抛异常（虽已被 runOnWorker
                    // 内部吞掉）时计数会泄漏，安全阀会被**逐渐堵死**。
                    if (isUi) uiPending.decrementAndGet()
                }
            }
        } catch (t: Throwable) {
            // ★★ 最后一道兜底：连「解码 + 查表 + 投递」本身抛了也不许逃逸
            HookLog.e("$TAG  onInvoke 顶层异常：${t.javaClass.simpleName} ${t.message}")
        }
    }

    // ── 工作线程 ───────────────────────────────────────────

    /**
     * ★★ **工作线程**执行体。顶层 `try/catch(Throwable)` —— 这是「绝不逃逸」的落点。
     *
     * ⚠️ 这里是**最容易被漏掉**的一处：`BinderTransport.invoke` 的 `try/catch`
     * 只护住 binder 线程那一段，投出去的 Runnable 在**另一个线程**上跑，
     * 它抛出的异常**不会**被那里的 catch 看到 —— 会直接成为该线程的未捕获异常。
     *
     * @param arrivedAtMs 请求抵达 binder 线程的时刻（`System.nanoTime()`，由 [onInvoke] 传入），
     *   用于出队时判定「排队是否已吃满预算」。
     */
    private fun runOnWorker(
        request: CapabilityRequest,
        handler: CapabilityHandler,
        arrivedAtMs: Long,
    ) {
        try {
            // ── ★★ 出队判过期（必须在 handler.handle 之前，且在此之前不做任何别的活）──
            //
            // ## ⚠️⚠️ 没有它，「超时 + 队列」= 一个**假的失败提示**
            //
            // ```
            // T+5s    App 侧超时 → 用户看到「失败」，而任务【还在队列里】
            // T+30s   出队、执行 → 脚本真的跑了（改系统状态 / 开广播 / 开窗口）
            // ```
            // ⇒ 用户看到「超时失败」，**副作用却已经发生**。
            //
            // ## ⚠️ 为什么必须在 `handler.handle` **之前**、且是这里第一件事
            //
            // 判据的意义就是「**不执行**」——放在后面等于跑了脚本再判过期，
            // 白付了副作用。而「不做别的活」是为了让它在**每个**出队请求上都
            // 最先发生（附带收益：塞满过期请求的队列会被快速抽干，每个 worker
            // 弹出、立即 return）。
            //
            // ## ⚠️ `queuedBudget == null`（不超时）时本判据是 **no-op**
            //
            // 由 [InvokePolicy.isTimedOut] 保证（`budgetMs == null` ⇒ 恒 false）——
            // **不因等待久而丢弃**。这是契约，不是巧合：不能拿 `queuedBudget ?: 0L` 兜底，
            // 那会让「不超时」退化成「等 0ms 就过期」。**不加任何 `?: 5000` 回落**。
            //
            // ⚠️ 命名刻意与下面在途判定的 `budget` 区分（两者同值同源，但分属
            // 「排队期」与「执行期」两个判据）；同名还会在同一作用域里**编译冲突**。
            val queuedMs = (System.nanoTime() - arrivedAtMs) / 1_000_000L
            val queuedBudget = InvokePolicy.effectiveTimeoutMs(request.timeoutMs, handler.timeoutMs)
            if (InvokePolicy.isTimedOut(queuedMs, queuedBudget)) {
                HookLog.e(
                    "$TAG  排队已超预算：${request.capability}" +
                        "（queuedMs=$queuedMs > budget=$queuedBudget）→ 不执行" +
                        "（request_id=${request.requestId}）",
                )
                // ⚠️ `queuedBudget ?: 0L`：只在 `isTimedOut` 已判真（即它 != null）时求值，
                // 故安全。别为它再加别的回落 —— 那会把预算语义改掉。
                // ⚠️ `elapsedMs` 传 `queuedMs`：让 App 侧看到「它等了多久」而不是 0
                //（调用方的时间轴才有意义）。
                emitFailure(
                    request,
                    InvokePolicy.queuedExpiredError(queuedMs, queuedBudget ?: 0L),
                    queuedMs,
                )
                // ★★ 绝不调用 `handler.handle` —— 这是本判据的全部意义
                return
            }

            // ── 以下与原实现逐字一致 ──

            // ⚠️ `started` **保持在原位不动**（判过期之后）⇒ 在途执行的超时仍只量
            // 「执行时长」，不量排队 —— 两者是两个不同的判据（见 §3.3 的两侧口径讨论）。
            val started = System.nanoTime()

            var outcome: CapabilityOutcome? = null
            var thrown: CapabilityError? = null
            try {
                outcome = handler.handle(request)
            } catch (t: Throwable) {
                // ⚠️ 捕获 `Throwable` 而不是 `Exception`：`OutOfMemoryError` /
                // `StackOverflowError` 同样不许逃逸（在 system_server 里后果是整机）
                thrown = InvokePolicy.throwableToError(t)
                HookLog.e(
                    "$TAG  handler 抛异常：${t.javaClass.simpleName}: ${t.message}" +
                        "（request_id=${request.requestId}）→ 已转成 handler_error 响应",
                )
            }

            val elapsedMs = (System.nanoTime() - started) / 1_000_000L
            val budget = InvokePolicy.effectiveTimeoutMs(request.timeoutMs, handler.timeoutMs)

            when {
                // handler 自报失败优先于超时：它更具体（超时只是「慢了」）
                thrown != null -> emitFailure(request, thrown, elapsedMs)

                outcome is CapabilityOutcome.Failure -> emitFailure(
                    request,
                    InvokePolicy.sanitize(
                        CapabilityError(code = outcome.code, detail = outcome.detail),
                    ),
                    elapsedMs,
                )

                // ⚠️ `budget == null` ⇒ 不超时，这个分支不会被走到（见 `isTimedOut`）。
                InvokePolicy.isTimedOut(elapsedMs, budget) -> {
                    HookLog.e(
                        "$TAG  执行超时：${request.capability}" +
                            "（elapsedMs=$elapsedMs > budget=$budget）→ 回 timeout",
                    )
                    emitFailure(
                        request,
                        InvokePolicy.timeoutError(elapsedMs, budget ?: 0L),
                        elapsedMs,
                    )
                }

                outcome is CapabilityOutcome.Items ->
                    successFromItems(request, handler, outcome, elapsedMs)

                else -> emitFailure(
                    request,
                    InvokePolicy.throwableToError(
                        IllegalStateException("未知的产出类型：${outcome?.javaClass?.name}"),
                    ),
                    elapsedMs,
                )
            }
        } catch (t: Throwable) {
            // ★★ 第二道兜底：连「构造响应」本身抛了（如 org.json 在极端输入下）
            // 也不许逃逸。⚠️ 这一层**不能**省 —— 它护的正是上面 when 表达式里的代码。
            try {
                emitFailure(request, InvokePolicy.throwableToError(t), elapsedMs = 0L)
            } catch (t2: Throwable) {
                // 连回响应都失败了 ⇒ 只剩日志。**绝不 rethrow**。
                HookLog.e("$TAG  回响应彻底失败：${t2.javaClass.simpleName} ${t2.message}")
            }
        }
    }

    /**
     * 成功路径：按**信封 parcel 预算**截断 → 组装 → 编码 → **量信封** → 回响应。
     *
     * ## ⚠️⚠️ 校验对象 = 实际发送对象（2026-10-01 真机实测缺陷的修复）
     *
     * **旧实现**量的是 `resultJson` 的 **UTF-8** 字节，而实际发出去的是**外层信封** ——
     * 两者结构上不是同一个东西。实测（小米 MIX Fold 3 / Android 17）：
     *
     * ```
     * 结果超预算，已截断：收下 1228/2000 项，nextCursor=1228
     * resolve 异常：TransactionTooLargeException  data parcel size 533700 bytes
     * ```
     *
     * `resultJson` 259,237 字节 < 262,144（256 KiB）判为通过，而信封 parcel 是 **533,700**
     * ⇒ 撞上 oneway 的异步半缓冲（≈508 KiB）。
     *
     * **新实现**：预算按信封 parcel 算（[InvokePolicy.itemEnvelopeCost]），
     * 终检量 [CapabilityInvocationCodec.encodeResponse] 的**返回值**
     *（[InvokePolicy.estimatedParcelBytes]），而那**正是**交给 [respondEnvelope] 的那个串。
     *
     * ## ⚠️ 为什么「翻倍」的机制与直觉不同
     *
     * 真实开销**不是**二次转义（全 ASCII 时只有 1.03×），而是 binder 把信封串
     * 按 **UTF-16 代码单元 × 2 字节**写进 parcel（`writeString16`）。
     * 详见 [InvokePolicy.PARCEL_PER_CODE_UNIT] 的实测对照表。
     */
    private fun successFromItems(
        request: CapabilityRequest,
        handler: CapabilityHandler,
        outcome: CapabilityOutcome.Items,
        elapsedMs: Long,
    ) {
        // ── 预算：三个数各司其职（⚠️ 别把它们混成一个）──
        //
        // ① maxBytes      capability 声明的【内容】上限（§3.6 契约 1）
        // ② contentBudget 内容换算成 parcel（= 2 × maxBytes），并受传输层封顶
        // ③ checkLimit    终检的判据 = contentBudget + 信封固定开销
        //
        // ⚠️⚠️ **收集用 ②，终检用 ③** —— 不能都用 ②。
        // `collectWithin` 会把给它的上限**用满**，而序列化后还要额外付信封的固定键
        // ⇒ 拿 ② 去终检会**必然判超限**（这正是我第一版改完的状况，
        //    3 条用例同时变红才暴露出来）。
        val maxBytes = InvokePolicy.effectiveMaxBytes(handler.maxResultBytes)
        val contentBudget = InvokePolicy.envelopeParcelBudget(maxBytes)
        val checkLimit = contentBudget + InvokePolicy.ENVELOPE_FIXED_OVERHEAD_BYTES

        var budgeted = ResultBudget.collectWithin(
            items = outcome.items,
            maxBytes = contentBudget,
            // ⚠️⚠️ 按【信封内的 parcel 字节】而非单层 UTF-8 字节 ——
            // 后者只有前者的一半，会把预算开大一倍（这正是那个缺陷）
            sizeOf = InvokePolicy::itemEnvelopeCost,
            startIndex = outcome.startIndex,
        )

        // ⚠️⚠️ **2026-10-01 真机修复：终检超限要【继续裁】，不是直接回错。**
        //
        // 场景（真机 407 条时必然触发）：`collectWithin` 把 `contentBudget` **用满**，
        // 但 `itemEnvelopeCost` 是**逐项估算**（`2 × JSONObject(item).toString().length`），
        // 而真实信封还要付：数组逗号、键名引号、`{"items":[…]}` 外壳、
        // 以及外层 `{"request_id":…,"result":"…"}` 的**二次转义**。
        // ⇒ 估算乐观几百字节 ⇒ 收满后**实际 parcel 超 `checkLimit`**。
        //
        // 旧行为是「回 `payload_too_large`」⇒ **整个请求失败、一条都拿不到**
        //（真机表现：App 侧 `degraded=true` + 走 dumpsys 回退，③ 的价值全丢）。
        //
        // 正确行为：**这是截断该干的事** —— 少收几项直到装得下，
        // 并把 `truncated`/`nextCursor` 照常回给调用方。
        //
        // ⚠️ 循环有界（最多 32 次）+ 每次至少少 1 项 ⇒ 一定终止；
        // 且**保留至少 1 项**（收 0 项等于「什么都没返回」，那才是该报错的形态）。
        var attempts = 0
        var envelope = encodeEnvelope(budgeted, request, elapsedMs)
        while (InvokePolicy.estimatedParcelBytes(envelope) > checkLimit &&
            budgeted.items.size > 1 &&
            attempts < MAX_SHRINK_ATTEMPTS
        ) {
            attempts++
            // 按超出比例估算该裁多少（至少裁 1 项，避免原地打转）
            val over = InvokePolicy.estimatedParcelBytes(envelope) - checkLimit
            val avg = (InvokePolicy.estimatedParcelBytes(envelope) / budgeted.items.size).coerceAtLeast(1)
            val drop = ((over / avg) + 1).coerceIn(1, budgeted.items.size - 1)
            val kept = budgeted.items.dropLast(drop)
            budgeted = Budgeted(
                items = kept,
                truncated = true,
                nextCursor = budgeted.nextCursor?.minus(drop) ?: kept.size,
            )
            envelope = encodeEnvelope(budgeted, request, elapsedMs)
        }
        if (attempts > 0) {
            HookLog.e(
                "$TAG  信封超限已收缩 ${attempts} 轮 ⇒ 收下 ${budgeted.items.size} 项" +
                    "（parcel ${InvokePolicy.estimatedParcelBytes(envelope)} / 上限 $checkLimit）",
            )
        }

        // ⚠️⚠️ **量的是 `envelope` —— 与下面真正发出去的是同一个串。**
        // 这是本次修复的全部：校验对象与发送对象**结构上不可能不一致**。
        //
        // ⚠️ **2026-10-01 补**：上面的收缩循环已处理「靠多裁几项能解决」的情形。
        // 走到这里只剩**收缩也救不了**的两种情况：
        //   ① 已裁到只剩 1 项，而**那单项自己**就超 `checkLimit`
        //      （`collectWithin` 的单元素超限特例 —— 它必须收下那项，否则分页死循环）
        //   ② `estimatedParcelBytes` 严重低估（不该发生，但为兜底留着）
        // ⇒ 此时回 `payload_too_large` 是对的（§6.4：它属「报告问题」而非用户可处理）。
        val actualParcel = InvokePolicy.estimatedParcelBytes(envelope)
        if (actualParcel > checkLimit) {
            // 归 `payload_too_large` 而**不是** `timeout` / `handler_error`：
            // 它是**实现缺陷或数据异常**，不是用户能处理的失败（§6.4）。
            HookLog.e(
                "$TAG  信封仍超传输上限（收缩 ${attempts} 轮后仍超），回 payload_too_large：" +
                    "实际 parcel $actualParcel 字节 > 上限 $checkLimit 字节" +
                    "（收下 ${budgeted.items.size} 项，request_id=${request.requestId}）",
            )
            emitFailure(request, InvokePolicy.payloadTooLargeError(actualParcel, checkLimit), elapsedMs)
            return
        }

        if (budgeted.truncated) {
            HookLog.e(
                "$TAG  结果超预算，已截断：收下 ${budgeted.items.size}/${outcome.items.size} 项，" +
                    "nextCursor=${budgeted.nextCursor}，信封 parcel $actualParcel/$checkLimit 字节" +
                    "（request_id=${request.requestId}）",
            )
        }

        respondEnvelope(envelope, request)
    }

    /**
     * `IHookCallback.capabilities()` 的应答体 —— 清单**从 [registry] 出**。
     *
     * ⚠️ 本方法可能抛（`CapabilityManifest.encode` 理论上不会，但 `registry.names()`
     * 若被改坏就会）⇒ 调用方（`BinderTransport.capabilities`）已经包了 try/catch
     * 并回空清单。这里不再兜一层：空清单是**合法**的，而多一层会让
     * 「到底谁吞了异常」变得难查。
     */
    fun capabilitiesJson(): String = CapabilityManifest.encode(registry.names(), protocolVersion)

    /**
     * 停掉三档执行器。由 `onHotReloading` / 通道关闭时调。
     *
     * ## ⚠️⚠️ 顺序：**先置标志，再 cancel**
     *
     * [onInvoke] 读的就是 [stopped]。`CoroutineScope.cancel()` 之后的 `launch`
     * 是**静默 no-op**（既不抛、也不执行）⇒ 不先置标志的话，「cancel 之后、
     * 标志还是 false」那个窗口里的调用会**没有任何响应**。
     * ⚠️ 语义收窄（**如实记录，不假装消除**）：那个窗口极窄但**存在** ——
     * 窗口内到达的请求从「立刻回 `runtimeStoppedError`」变为「无响应 ⇒ 超时」。
     * 本仓库对这种窗口的既往处置是「记录并承认」。
     *
     * ## ⚠️ 为什么必须停（不是「可选的清理」）
     *
     * 热更新换代时旧代际的 scope 若继续活着，会与新代际的**并存**并抢
     * system_server 资源 —— 那是需求明写的硬要求。
     *
     * ⚠️ 停的是 **scope 的 context**（`CoroutineContext.cancel()` 扩展）——
     * 它只会取消**本 scope 派生的协程**，不会去动 `Dispatchers.Default` 那些共享的
     * 线程池（那本来就不该动：别的组件也在用）。
     *
     * ## ⚠️ 已知限制：对**不可中断的 handler 无效**
     *
     * 协程取消只在**挂起点**生效。handler 若是一个纯阻塞调用
     *（`Thread.sleep` / 卡住的 IO），它跑完才返回 —— 见类注释的「本限制必须被承认」。
     *
     * ⚠️ **幂等**：热更新与通道关闭可能都调。
     */
    fun stop() {
        stopped = true
        try {
            scopes.values.forEach { it.cancel() }
        } catch (t: Throwable) {
            HookLog.e("$TAG  cancel 异常：${t.javaClass.simpleName} ${t.message}")
        }
        // ⚠️ `ui` 线程**只丢引用、不 `quitSafely`** —— 见 [UiDispatcherHolder.resetForStop]。
        try {
            UiDispatcherHolder.resetForStop()
        } catch (t: Throwable) {
            HookLog.e("$TAG  UI 执行器重置异常：${t.javaClass.simpleName} ${t.message}")
        }
    }

    // ── 内部 ──────────────────────────────────────────────

    /**
     * 编码一条响应信封（**唯一编码出口**）。
     *
     * ⚠️ 抽出来是为了让「编码」与「量字节」用**同一个串**：
     * 若在别处再编一次，就会出现「量的是 A、发的是 B」——正是本次缺陷的形态。
     *
     * ⚠️ `ok = error == null`：与 codec 的契约一致
     *（`decodeResponse` 会反向校验「ok=false 必须带 error」）。
     */
    /**
     * 由**当前**的 [budgeted] 组装一次成功信封（供收缩循环反复调用）。
     *
     * ⚠️ 抽出来是为了让「量的对象」与「发的对象」是**同一段构造逻辑** ——
     * 循环里量完还要再拼一次，若两处各写一遍就会漂移。
     */
    private fun encodeEnvelope(
        budgeted: Budgeted<Map<String, Any?>>,
        request: CapabilityRequest,
        elapsedMs: Long,
    ): String = encodeResponse(
        request = request,
        ok = true,
        resultJson = InvokePolicy.buildResultJson(budgeted.items),
        error = null,
        nextCursor = budgeted.nextCursor?.toString(),
        truncated = budgeted.truncated,
        elapsedMs = elapsedMs,
    )

    private fun encodeResponse(
        request: CapabilityRequest,
        ok: Boolean,
        resultJson: String,
        error: CapabilityError?,
        nextCursor: String?,
        truncated: Boolean,
        elapsedMs: Long,
    ): String = CapabilityInvocationCodec.encodeResponse(
        requestId = request.requestId,
        ok = ok,
        resultJson = resultJson,
        error = error,
        nextCursor = nextCursor,
        truncated = truncated,
        elapsedMs = elapsedMs,
        token = request.token,
    )

    /** 组装一条**失败**响应并发出。 */
    private fun emitFailure(
        request: CapabilityRequest,
        error: CapabilityError,
        elapsedMs: Long,
    ) {
        respondEnvelope(
            encodeResponse(
                request = request,
                ok = false,
                resultJson = "{}",
                error = error,
                nextCursor = null,
                truncated = false,
                elapsedMs = elapsedMs,
            ),
            request,
        )
    }

    /**
     * 把**已编好的信封**交给对端。
     *
     * ⚠️ 唯一的发送出口 —— 分页两键若在别处被编进信封，就会出现
     * 「有的带标志有的不带」（§3.6 明令要避免）。
     *
     * ⚠️ `respond` 内部会走 binder（`IHookHost.resolve`，oneway）。
     * 它本身可能抛（`RemoteException` / **`TransactionTooLargeException`**）⇒
     * 在这里吞掉。调用点可能在**工作线程**上，异常逃逸同样会打崩那个线程。
     *
     * ## ⚠️ 若这里打了「回响应失败」的 `TransactionTooLargeException`
     *
     * 说明 [InvokePolicy.estimatedParcelBytes] 的估算仍不足（或上限常量定得太松）——
     * 那是**实现缺陷**，不是用户问题。日志里带上信封长度便于定位。
     */
    private fun respondEnvelope(envelope: String, request: CapabilityRequest) {
        try {
            respond.respond(envelope)
        } catch (t: Throwable) {
            HookLog.e(
                "$TAG  回响应失败：${t.javaClass.simpleName} ${t.message}" +
                    "（信封 ${envelope.length} 字符，request_id=${request.requestId}）",
            )
        }
    }

    /** 仅供测试：`ui` 档当前待执行数（安全阀断言用）。 */
    internal fun uiPendingForTest(): Int = uiPending.get()

    /** 仅供测试：是否已停止。 */
    internal fun isStoppedForTest(): Boolean = stopped

    /**
     * 仅供测试：以**受控的 [arrivedAtMs]** 直接执行一次工作线程体（**不经 scope**）。
     *
     * ## ⚠️⚠️ 存在的唯一理由：那一格经真实投递路径**物理不可达**
     *
     * task-10 引入它时，池是 `ThreadPoolExecutor(core = max = 2, SynchronousQueue)`
     * —— 队列**容量 0** ⇒ 第 3 个并发提交在 [onInvoke] 就被拒，**进不了 [runOnWorker]**。
     *
     * ⚠️ **2026-10-03 更新**：三档执行器之后，`default`/`io` 档**真的会排队**了
     * （`Dispatchers.IO` 的 64 并发信号量）⇒ 真机与端到端**理论上已可达**
     *（App 侧 5s 超时 + 排队 ⇒ 出队时已过期）。
     * ⚠️ **但本接缝保留** —— 经真实路径造那个状态需要「精确控制排队时长」，
     * 那是 sleep + latch 的 flaky 写法；接缝直接喂一个**过去的时刻**是**确定性**的。
     * 两条路各覆盖一半：接缝锁**判据语义**，源码扫描锁**接线**。
     *
     * ⚠️ 形态照本类既有的 [isStoppedForTest]（`internal` + `ForTest` 后缀）。
     * ⚠️ 它**不改变任何生产行为** —— 只是把已有的 private 方法以受控入参暴露给测试。
     */
    internal fun runOnWorkerForTest(
        request: CapabilityRequest,
        handler: CapabilityHandler,
        arrivedAtMs: Long,
    ) = runOnWorker(request, handler, arrivedAtMs)

    /**
     * 仅供测试：**经对应档的 scope** 投递一次（即 [onInvoke] 的投递那一步，
     * 但**不做** `stopped` 前置闸）。
     *
     * ## ⚠️⚠️ 为什么需要它（上面那个接缝证不了这件事）
     *
     * `stop()` 之后「不再落档」这条语义由**两道**闸共同保证：
     * ① `stopped` 标志（[onInvoke] 里显式的 `if (stopped) → runtimeStoppedError`）；
     * ② `scope.cancel()` 让后续 `launch` **静默 no-op**。
     *
     * ⇒ 只经 [onInvoke] 测的话，**闸 ① 会把所有情况都兜住** —— 就算有人把
     * `scope.cancel()` 删掉，测试照样绿。本接缝**绕过闸 ①**，于是唯一能拦住它的
     * 就是闸 ②，那条语义才真正被测到。
     *
     * ⚠️ 形态与理由同 [runOnWorkerForTest]：`internal` + `ForTest` 后缀，
     * 不改任何生产行为（它复用生产路径的 `scopes` 表与 `launch` 写法）。
     */
    internal fun launchOnTierForTest(
        request: CapabilityRequest,
        handler: CapabilityHandler,
        arrivedAtMs: Long,
        mode: String,
    ) {
        val scope = scopes[mode] ?: scopes.getValue(ThreadModes.DEFAULT)
        scope.launch { runOnWorker(request, handler, arrivedAtMs) }
    }
}

/**
 * `ui` 档的 dispatcher 持有者：**自建 `HandlerThread`** + `Handler.asCoroutineDispatcher()`。
 *
 * ## ⚠️ 为什么用自建 `HandlerThread` 而不是 `Dispatchers.Main`
 *
 * 协程**没有**「Looper」这个原语 —— 三档里 `ui` 这一档必须自己造。
 * `Dispatchers.Main` 在 system_server 里指的是**系统的主线程**，
 * 往那里投脚本是另一类危险（会阻塞系统启动/交互）。ShortX 同样用专线程
 *（`HandlerThread("SX-ShortXJS")`，`G00.java:3016`），不用主线程。
 *
 * ## ⚠️⚠️ 扩展的**接收者是 `Handler`，不是 `HandlerThread`**
 *
 * `asCoroutineDispatcher` 定义在 `HandlerDispatcherKt` 上，签名是
 * `from(android.os.Handler, java.lang.String)`（JVM 名 `from`）。
 * 而 `HandlerThread` **不继承 `Handler`、也不声明 `getHandler()`**
 *（`javap -p android/os/HandlerThread.class` 只列出
 * `getLooper / getThreadId / onLooperPrepared / quit / quitSafely / run`）。
 * ⇒ **`ht.asCoroutineDispatcher("…")` 编译不过**，必须显式造一个 `Handler`：
 * `Handler(ht.looper)`（`getLooper()` 在 Looper 就绪前会阻塞等待，正是我们要的）。
 *
 * ## ⚠️⚠️ 懒启动：**构造那张表时不得碰 `HandlerThread`**
 *
 * 照 ShortX 的做法：没有脚本用 `ui` 档时**不建**那个线程。
 * ⚠️ 这一条不是优化，是**两条硬约束**的结果：
 *
 * 1. **`defaultDispatchers()` 的值是急切求值的** —— 若它直接调
 *    「创建 HandlerThread 的函数」，那么**构造运行时**就会起线程，
 *    「懒启动」根本无从谈起。
 * 2. **纯 JVM 单测里 `HandlerThread.getLooper()` 抛 `Method … not mocked`**
 *    （本项目无 Robolectric）⇒ 急切创建的版本会让**整个单测类**在 `@Before`
 *    就崩掉（实测：27 个用例全红，报错全是 `getLooper not mocked`）。
 *
 * ⇒ 对外暴露的是 [LazyUiDispatcher] 这个**委托壳**：它**不持有**真 dispatcher，
 * 只有第一次 `dispatch` 时才去建 `HandlerThread`。构造那张表时一次都不碰 Android。
 */
private object UiDispatcherHolder {

    /** 单例委托壳。⚠️ 它**始终是同一个实例** ⇒ `===` 判等（`onInvoke` 里判 `isUi`）成立。 */
    private val lazy = LazyUiDispatcher()

    @Volatile
    private var thread: HandlerThread? = null

    /** 真正落到 `Looper` 的那个 dispatcher（`Handler(ht.looper).asCoroutineDispatcher(...)`）。 */
    @Volatile
    private var delegate: CoroutineDispatcher? = null

    /** 返回**委托壳**（不是真 dispatcher）—— 见类注释的懒启动说明。 */
    fun dispatcher(): CoroutineDispatcher = lazy

    /** 第一次真的要投递时才建线程（`synchronized` 保证只建一次）。 */
    internal fun realDispatcher(): CoroutineDispatcher = delegate ?: synchronized(this) {
        delegate ?: run {
            val ht = HandlerThread("VFlowHook-ui")   // ⚠️ 单参构造，优先级即默认
            ht.start()
            thread = ht
            Handler(ht.looper).asCoroutineDispatcher("VFlowHook-ui").also { delegate = it }
        }
    }

    /**
     * ⚠️⚠️ **不 `exitLooper`、也不 `quitSafely`** —— 只丢引用。
     *
     * `HandlerContext` 把在队的续体登记在 `Looper` 的 message 上，
     * 一处 `quitSafely()` 与另一处的 `scope.cancel()` 并发时会撞
     * `IllegalStateException`。而 `HandlerThread` 是**守护线程**，
     * 进程退出时自然终止，**业务语义上不依赖它退出**。
     *
     * ⚠️ 代价（**如实记录**）：本对象每次 `stop()` 后重置为 null，
     * 若之后再次 `start`（同一 classloader 内）会新建一个线程，旧线程**不会自己退出**。
     * 但生产路径上不存在这条时序（`onHotReloading → stop → 不再是同一代际`），
     * 真机项负责观测**跨代际线程数不增长**。
     */
    fun resetForStop() {
        synchronized(this) {
            delegate = null
            thread = null
        }
    }

    /**
     * 委托壳：`dispatch` 时才解析真 dispatcher。
     *
     * ⚠️ **必须转发 `isDispatchNeeded`** —— 协程库会用它判断「当前是否已在本 dispatcher
     * 的线程上」，不转发会让「已在此线程时跳过重投递」的优化失效（对本档无害但语义错）。
     */
    private class LazyUiDispatcher : CoroutineDispatcher() {
        override fun dispatch(context: CoroutineContext, block: Runnable) =
            UiDispatcherHolder.realDispatcher().dispatch(context, block)

        override fun isDispatchNeeded(context: CoroutineContext): Boolean =
            UiDispatcherHolder.realDispatcher().isDispatchNeeded(context)
    }
}

/**
 * ③ 的**响应出口**：把**已编好的信封串**交给对端（`IHookHost.resolve`）。
 *
 * ## ⚠️⚠️ 为什么签名从「七个字段」改成「一个信封串」
 *
 * 旧签名收 `requestId / resultJson / nextCursor / truncated / elapsedMs / token / error`
 * 七个字段，由调用方（`VFlowHookEntry`）去 `encodeResponse` **编码**。
 * 那样有两个结构性问题，第一个已经造成真实缺陷：
 *
 * 1. ⚠️⚠️ **预算校验无处安放** —— 运行时手里只有 `resultJson`，
 *    量不到真正发出去的信封（2026-10-01 的 `TransactionTooLargeException`）。
 *    改成收信封串后，「量的对象」与「发的对象」**在类型上就是同一个**。
 * 2. 分页两键的**编码规则**（非空白才写 / true 才写）会散到 `xposed/` 外面 ——
 *    而 §3.6 的立意恰恰是「**由框架统一**，不是各写各的」。
 *
 * ⚠️ 现在编码发生在 [HookCapabilityRuntime] 内部（`encodeResponse`），
 * 信封仍是 `CapabilityInvocationCodec` 的产物，规则仍收敛在一处。
 */
fun interface CapabilityResponder {
    /**
     * @param envelopeJson `CapabilityInvocationCodec.encodeResponse` 的**返回值**。
     *   ⚠️ 运行时已按 [InvokePolicy.estimatedParcelBytes] 校验过它不超过
     *   [ResultBudget.MAX_ENVELOPE_PARCEL_BYTES]。
     * @return 是否已交给对端
     */
    fun respond(envelopeJson: String): Boolean
}
