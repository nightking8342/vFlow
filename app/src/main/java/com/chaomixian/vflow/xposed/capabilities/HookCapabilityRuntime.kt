package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.HookLog
import com.chaomixian.vflow.xposed.wire.Budgeted
import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityManifest
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.EventEnvelopeCodec
import com.chaomixian.vflow.xposed.wire.ResultBudget
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadFactory
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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
 * ## 自建有界线程池（**必须是我们自己创建的**）
 *
 * ⚠️ 不借 system_server 的线程池（`ForkJoinPool.commonPool` / `AsyncTask`）：
 * 容量不可控、且与系统自身任务共享 —— 把它占满会拖慢系统本身。
 *
 * ## ⚠️⚠️ 为什么用 `SynchronousQueue`（容量 0）而不是有界队列
 *
 * 需求与 §3.4 的定案都是「池满**立即**回 error」。
 *
 * 用有界队列会让「排队中」变成**第三种状态** —— 它既不阻塞 binder 线程（算合规），
 * 又**把池满伪装成超时**：调用方等不到结果，最后看到的是 `timeout`。
 * 而 `CapabilityErrorCode.kt:68-87` 恰恰花了一整段论证
 * 「池满与超时是**两个不同的排查方向**」（前者该「降并发/重试」，
 * 后者该「报告问题，可能真的慢」）。
 *
 * ⇒ 容量 0 让这两种情况在语义上真正分开。代价是池满时不做等待重试 ——
 * 那是**有意**的：宁可让调用方立刻知道，也不要让它等一个不确定的时间。
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
 * 而这里**没有**补救手段 —— 这正是「池有界 + 总时长上限」的兜底方式，
 * 也是 [DEFAULT_POOL_SIZE] 必须**小**的原因（容量 2 时，两个卡住的 handler
 * 会让后续调用全部立刻收到「池已满」，而不是整个池无限膨胀）。
 *
 * ⚠️ **Rhino 指令级超时不在本类范围**（那是脚本类 handler 自己的事）。
 *
 * ## 依赖白名单
 *
 * 本文件在 `xposed/` 下（会被 hook 层加载），只允许 `org.json` / `java.*` / `kotlin.*` /
 * `com.chaomixian.vflow.xposed.*`。由 `WireLayerPurityTest` 源码扫描锁住。
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
    poolSize: Int = DEFAULT_POOL_SIZE,
) {

    companion object {
        /**
         * 工作线程池容量。
         *
         * ⚠️ **必须小**。见类注释：不可中断的 handler 会**永久**占住一个线程，
         * 容量越大，「被永久占用」的绝对量越大；而且本池跑在 system_server 里。
         *
         * 2 是「能重叠一次调用」与「被占满的代价可接受」之间的折中。
         */
        const val DEFAULT_POOL_SIZE = 2

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
     * ⚠️⚠️ **`stop()` 必须先置它、再 `shutdownNow()`** —— [onInvoke] 读的就是它，
     * 反过来会让一个正在投递的调用被误判成「池满」（见 [RejectionCause]）。
     */
    @Volatile
    private var stopped = false

    private val threadCounter = AtomicInteger(0)

    /**
     * 自建的**有界**工作线程池。见类注释：`SynchronousQueue` = 不排队、满即拒绝。
     *
     * ⚠️ daemon 线程：`stop()` 的 interrupt 对不可中断的 handler 无效
     * （见类注释的已知限制），此时至少不能让这些线程**阻止进程退出**。
     */
    private val pool: ThreadPoolExecutor = ThreadPoolExecutor(
        poolSize.coerceAtLeast(1),
        poolSize.coerceAtLeast(1),
        0L,
        TimeUnit.MILLISECONDS,
        // ⚠️ 容量 0：没有空闲 worker 就**立刻**拒绝，绝不排队（见类注释）
        SynchronousQueue(),
        ThreadFactory { r ->
            Thread(r, "VFlowHook-cap-${threadCounter.incrementAndGet()}").apply { isDaemon = true }
        },
        // ⚠️ AbortPolicy ⇒ RejectedExecutionException（我们在 onInvoke 里接住它）
        ThreadPoolExecutor.AbortPolicy(),
    )

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

            // ⚠️ 先判「已停止」再投递 —— `shutdownNow()` 之后的拒绝同样是
            // RejectedExecutionException，若不先判就会报成「池满」（见 RejectionCause）。
            if (stopped) {
                emitFailure(request, InvokePolicy.runtimeStoppedError(), elapsedMs = 0L)
                return
            }

            try {
                pool.execute { runOnWorker(request, handler) }
            } catch (_: RejectedExecutionException) {
                // ⚠️⚠️ 两道拒绝对应两种成因，`detail` **必须不同**
                //（否则排查方向会错 —— 见 InvokePolicy 的两个构造函数注释）
                val error = if (stopped) {
                    InvokePolicy.runtimeStoppedError()
                } else {
                    InvokePolicy.poolExhaustedError(pool.maximumPoolSize)
                }
                HookLog.e(
                    "$TAG  ${if (stopped) "运行时已停止" else "工作线程池已满"}" +
                        "（容量 ${pool.maximumPoolSize} 个并发），回 handler_error",
                )
                emitFailure(request, error, elapsedMs = 0L)
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
     */
    private fun runOnWorker(request: CapabilityRequest, handler: CapabilityHandler) {
        try {
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

                InvokePolicy.isTimedOut(elapsedMs, budget) -> {
                    HookLog.e(
                        "$TAG  执行超时：${request.capability}" +
                            "（elapsedMs=$elapsedMs > budget=$budget）→ 回 timeout",
                    )
                    emitFailure(request, InvokePolicy.timeoutError(elapsedMs, budget), elapsedMs)
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
     * 停止池 + 中断工作线程。由 `onHotReloading` / 通道关闭时调。
     *
     * ## ⚠️⚠️ 顺序：**先置标志，再 shutdown**
     *
     * [onInvoke] 读的就是 [stopped]。反过来（先 shutdown）会让一个正在投递的调用
     * 撞上 `shutdownNow` 的拒绝，而此时标志还是 false ⇒ 被报成「**池满**」——
     * 而真实原因是「运行时已停，不会再有响应」。用户会去查并发，排查方向整个错掉。
     *
     * ## ⚠️ 为什么必须停（不是「可选的清理」）
     *
     * 热更新换代时旧代际的池若继续活着，会与新代际的池**并存**并抢 system_server
     * 资源 —— 那是需求明写的硬要求。
     *
     * ## ⚠️ 已知限制：`shutdownNow()` 的 interrupt 对**不可中断的 handler 无效**
     *
     * 对 `Thread.sleep` / IO 有效（会抛 `InterruptedException`），
     * 对纯循环的处理器无效 —— 那些线程会一直跑到自己结束。它们是 daemon，
     * 所以至少不会阻止进程退出。见类注释的「本限制必须被承认」。
     *
     * ⚠️ **幂等**：热更新与通道关闭可能都调。
     */
    fun stop() {
        stopped = true
        try {
            pool.shutdownNow()
        } catch (t: Throwable) {
            HookLog.e("$TAG  shutdownNow 异常：${t.javaClass.simpleName} ${t.message}")
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

    /** 仅供测试：当前在跑的任务数（断言「池满后会回落」用）。 */
    internal fun activeWorkerCount(): Int = pool.activeCount

    /** 仅供测试：池是否已停止。 */
    internal fun isStoppedForTest(): Boolean = stopped
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
