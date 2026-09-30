package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.HookLog
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
     * ## ⚠️ 签名刻意收成「分页三值 + 结果」而不是「一个编好的信封串」
     *
     * 若让调用方（`VFlowHookEntry`）去编信封，分页两键的**编码规则**
     * （`nextCursor` 非空白才写、`truncated` 为 true 才写）就会散到 `xposed/` 外面 ——
     * 而 §3.6 的立意恰恰是「**由框架统一**，不是各写各的」。
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
         * 结果信封的**固定键 + JSON 转义余量**。
         *
         * ## ⚠️ 它是**必需开销**，不是宽松余量
         *
         * 元素是**对象**，框架用 `JSONArray` 统一序列化 ⇒
         * `byteSizeOf(元素.toString())` 与实际写出的字节**存在差异**：
         *
         * | 项 | 量级 |
         * |---|---|
         * | 固定键 `{"items":[]}` | ≈ 13 字节 |
         * | 数组逗号 | +1 字节/项 |
         * | 转义膨胀（`"` → `\"`、控制字符 → `\uXXXX`） | ⚠️ **与数据相关** |
         *
         * ⚠️⚠️ **最坏情况余量兜不住**：一份内容全是引号的载荷，转义会让字节**翻倍**。
         * ⇒ **真正的防线是发出去之前再验一次整串字节数**（见 [successFromItems]），
         * 余量只负责把「正常数据」的误差兜住。
         *
         * 取 4 KiB 的依据：[DiagnosticCapabilityHandler] 的 `huge` 夹具
         * （约 1210 项、每项 213 字节）只有约 1210 个逗号 ≈ 1.2 KiB 的开销，
         * 且其元素是**纯 ASCII 的 x**、转义开销为 0 ⇒ 余量足够。
         */
        const val RESULT_ENVELOPE_MARGIN_BYTES = 4 * 1024

        /**
         * 余量占预算的**上限比例**。
         *
         * ⚠️ 为什么需要它：余量是**绝对字节数**，而 [CapabilityHandler.maxResultBytes]
         * 可以是任意正值。一个把上限声明成 512 字节的 capability，
         * 若余量仍取 4 KiB，`上限 - 余量` 会变成负数 ⇒ 它**永远收不下任何元素**
         * （只能靠 `collectWithin` 的单元素特例拿回 1 项）。
         *
         * ⇒ 余量取 `min(固定值, 上限 / 8)`：大预算时是那条实测标定的 4 KiB，
         * 小预算时按比例缩小。取 1/8 而不是 1/2 是因为余量要覆盖的是
         * **逗号 + 转义**，那与项数相关、与总字节数大致同阶但远小于它。
         */
        private const val RESULT_ENVELOPE_MARGIN_DIVISOR = 8

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
                emit(
                    failure(
                        request,
                        InvokePolicy.unknownCapabilityError(request.capability, registry.names()),
                        elapsedMs = 0L,
                    ),
                )
                return
            }

            // ⚠️ 先判「已停止」再投递 —— `shutdownNow()` 之后的拒绝同样是
            // RejectedExecutionException，若不先判就会报成「池满」（见 RejectionCause）。
            if (stopped) {
                emit(failure(request, InvokePolicy.runtimeStoppedError(), elapsedMs = 0L))
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
                emit(failure(request, error, elapsedMs = 0L))
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
                thrown != null -> emit(failure(request, thrown, elapsedMs))

                outcome is CapabilityOutcome.Failure -> emit(
                    failure(
                        request,
                        InvokePolicy.sanitize(
                            CapabilityError(code = outcome.code, detail = outcome.detail),
                        ),
                        elapsedMs,
                    ),
                )

                InvokePolicy.isTimedOut(elapsedMs, budget) -> {
                    HookLog.e(
                        "$TAG  执行超时：${request.capability}" +
                            "（elapsedMs=$elapsedMs > budget=$budget）→ 回 timeout",
                    )
                    emit(failure(request, InvokePolicy.timeoutError(elapsedMs, budget), elapsedMs))
                }

                outcome is CapabilityOutcome.Items ->
                    successFromItems(request, handler, outcome, elapsedMs)

                else -> emit(
                    failure(
                        request,
                        InvokePolicy.throwableToError(
                            IllegalStateException("未知的产出类型：${outcome?.javaClass?.name}"),
                        ),
                        elapsedMs,
                    ),
                )
            }
        } catch (t: Throwable) {
            // ★★ 第二道兜底：连「构造响应」本身抛了（如 org.json 在极端输入下）
            // 也不许逃逸。⚠️ 这一层**不能**省 —— 它护的正是上面 when 表达式里的代码。
            try {
                emit(failure(request, InvokePolicy.throwableToError(t), elapsedMs = 0L))
            } catch (t2: Throwable) {
                // 连回响应都失败了 ⇒ 只剩日志。**绝不 rethrow**。
                HookLog.e("$TAG  回响应彻底失败：${t2.javaClass.simpleName} ${t2.message}")
            }
        }
    }

    /**
     * 成功路径：按字节截断 → 组装 `resultJson` → 最终校验 → 回响应。
     *
     * ## ⚠️ 截断发生在「产出」而不是「序列化后」（§3.6 的硬要求）
     *
     * 用 [ResultBudget.collectWithin] 在**收集阶段**就按上限收，
     * 不是「拼完大对象再砍」—— 后者内存已经占过，而它在 **system_server** 里。
     */
    private fun successFromItems(
        request: CapabilityRequest,
        handler: CapabilityHandler,
        outcome: CapabilityOutcome.Items,
        elapsedMs: Long,
    ) {
        val maxBytes = InvokePolicy.effectiveMaxBytes(handler.maxResultBytes)
        val marginBytes = minOf(RESULT_ENVELOPE_MARGIN_BYTES, maxBytes / RESULT_ENVELOPE_MARGIN_DIVISOR)
        val elementBudget = (maxBytes - marginBytes).coerceAtLeast(1)

        val budgeted = ResultBudget.collectWithin(
            items = outcome.items,
            maxBytes = elementBudget,
            // ⚠️ 按【字节】而非 `String.length`：全 CJK 时后者低估 3 倍，
            // 会直接撞上 binder 的 oneway 上限 ⇒ **整条响应被静默丢弃**
            sizeOf = InvokePolicy::itemByteCost,
            startIndex = outcome.startIndex,
        )

        val resultJson = InvokePolicy.buildResultJson(budgeted.items)

        if (budgeted.truncated) {
            HookLog.e(
                "$TAG  结果超预算，已截断：收下 ${budgeted.items.size}/${outcome.items.size} 项，" +
                    "nextCursor=${budgeted.nextCursor}（request_id=${request.requestId}）",
            )
        }

        // ⚠️⚠️ **发出去之前必须验一次真实字节数**。
        //
        // `itemByteCost` 是**近似值**（JSON 的转义与逗号会让实际写出更短或更长），
        // 而 oneway 超限是**静默丢弃**（binder 不通知发送方，§3.6）——
        // 不能让一条超限的响应离开本进程。
        //
        // 这道校验兜的是两件事：
        // ① [ResultBudget.collectWithin] 的**单元素超限特例**（它会收下那个超限元素，
        //    否则分页会死循环）；
        // ② 转义膨胀（最坏是「内容全是引号」时字节翻倍，余量兜不住）。
        //
        // ⚠️⚠️ **判据是 [maxBytes]（真实上限），不是 [elementBudget]** ——
        // 这里改过一版，记下为什么。`collectWithin` 会一直收到「再加一项就超过
        // `elementBudget`」为止 ⇒ 收下的部分**已经把 `elementBudget` 用满**，
        // 而序列化还要额外付 `(项数 - 1)` 个逗号 + 信封固定键。
        //
        // 拿 `huge` 的实测值算：收下约 1211 项、合计约 257,943 字节
        // （`elementBudget` = 258048）⇒ 拼成 JSON 后约 259,000 字节。
        // 若与 `elementBudget` 比，**必然判超限** ⇒ `huge` 会回
        // `payload_too_large` 而**不是**「截断成功」，与验收 #6 的期望**相反**。
        //
        // ⇒ 余量的真实作用是「**预留出逗号与转义占的那几百字节**，
        // 使最终结果仍落在 [maxBytes] 内」，因此核对的对象必须是 [maxBytes]。
        val actualBytes = ResultBudget.byteSizeOf(resultJson)
        if (actualBytes > maxBytes) {
            // ⚠️ 归 `payload_too_large` 而**不是** `timeout` / `handler_error`：
            // 它是**实现缺陷或数据异常**，不是用户能处理的失败（§6.4）——
            // 用户该做的是「报告问题」，不是去改配置。
            HookLog.e(
                "$TAG  结果仍超上限，回 payload_too_large：实际 $actualBytes 字节 > " +
                    "上限 $maxBytes 字节（request_id=${request.requestId}）",
            )
            emit(failure(request, InvokePolicy.payloadTooLargeError(actualBytes, maxBytes), elapsedMs))
            return
        }

        emit(
            PendingResponse(
                requestId = request.requestId,
                token = request.token,
                ok = true,
                resultJson = resultJson,
                // ⚠️ `Budgeted.nextCursor` 是 `Int?`，而契约里它是**不透明串** ⇒ 转字符串。
                // ✅ 分页两键**走信封顶层**（由 codec 决定「非空白才写」）——
                // 塞进 `resultJson` 内部会让 App 侧的两个字段**永远填不上**。
                nextCursor = budgeted.nextCursor?.toString(),
                truncated = budgeted.truncated,
                elapsedMs = elapsedMs,
                error = null,
            ),
        )
    }

    // ── 对外 ──────────────────────────────────────────────

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

    /** 待回的响应（组装好、还没交给对端）。 */
    private data class PendingResponse(
        val requestId: String,
        val token: String,
        val ok: Boolean,
        val resultJson: String,
        val nextCursor: String?,
        val truncated: Boolean,
        val elapsedMs: Long,
        val error: CapabilityError?,
    )

    /** 组装一条失败响应。 */
    private fun failure(
        request: CapabilityRequest,
        error: CapabilityError,
        elapsedMs: Long,
    ): PendingResponse = PendingResponse(
        requestId = request.requestId,
        token = request.token,
        ok = false,
        resultJson = "{}",
        nextCursor = null,
        truncated = false,
        elapsedMs = elapsedMs,
        error = error,
    )

    /**
     * 把响应交给对端。
     *
     * ⚠️ 唯一的出口 —— 分页两键若在别处被编进信封，就会出现
     * 「有的带标志有的不带」（§3.6 明令要避免）。
     *
     * ⚠️ `respond` 内部会走 binder（`IHookHost.resolve`，oneway）。
     * 它本身可能抛（`RemoteException` / `DeadObjectException`）⇒ 在这里吞掉。
     * 调用点可能在**工作线程**上，异常逃逸同样会打崩那个线程。
     */
    private fun emit(r: PendingResponse) {
        try {
            respond.respond(
                requestId = r.requestId,
                resultJson = r.resultJson,
                nextCursor = r.nextCursor,
                truncated = r.truncated,
                elapsedMs = r.elapsedMs,
                token = r.token,
                error = r.error,
            )
        } catch (t: Throwable) {
            HookLog.e("$TAG  回响应失败：${t.javaClass.simpleName} ${t.message}")
        }
    }

    /** 仅供测试：当前在跑的任务数（断言「池满后会回落」用）。 */
    internal fun activeWorkerCount(): Int = pool.activeCount

    /** 仅供测试：池是否已停止。 */
    internal fun isStoppedForTest(): Boolean = stopped
}

/**
 * 回响应的出口。
 *
 * ## ⚠️ 为什么是 `fun interface` 而不是普通函数类型
 *
 * 具名参数让**调用点自解释**（7 个参数的 lambda 里，`String` / `Boolean` 混排时
 * 位置写错是**编译期通不过**的 —— 除非类型恰好相同）。
 * 用 [CapabilityResponder] 这个名字也让「唯一出口」这件事有个可被引用的标识。
 */
fun interface CapabilityResponder {
    /**
     * @param nextCursor 下一页游标（**不透明串**）。`null` ⇒ 没有下一页。
     *   ⚠️ 编码规则（非空白才写键）由 `CapabilityInvocationCodec` 负责，本接口只传值。
     * @param truncated 是否发生过截断。⚠️ 与 [nextCursor] 一样走**信封顶层**。
     * @param error `ok=false` 时**必须非 null**（由 codec 的 `decodeResponse` 反向校验）。
     * @return 是否已交给对端
     */
    fun respond(
        requestId: String,
        resultJson: String,
        nextCursor: String?,
        truncated: Boolean,
        elapsedMs: Long,
        token: String,
        error: CapabilityError?,
    ): Boolean
}
