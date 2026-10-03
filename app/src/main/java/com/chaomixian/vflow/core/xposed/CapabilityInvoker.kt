package com.chaomixian.vflow.core.xposed

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.xposed.IHookCallback
import com.chaomixian.vflow.xposed.capability.Capability
import com.chaomixian.vflow.xposed.capability.CapabilityFallbackPlan
import com.chaomixian.vflow.xposed.capability.CapabilityFailure
import com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome
import com.chaomixian.vflow.xposed.capability.CapabilityPresence
import com.chaomixian.vflow.xposed.capability.CapabilityRegistry
import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec
import com.chaomixian.vflow.xposed.wire.CapabilityResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * ③ 能力调用的**单一入口**（App 侧）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.4（传输形态）/ §5.2（超时与断连）/
 * §6.2（降级判据）/ §6.3（presence 只避免白试）。
 *
 * ## 为什么它在 `core/xposed/` 而不在 `xposed/capability/`
 *
 * 那个包在 `xposed/` 下 ⇒ 会被注入 system_server 的代码加载 ⇒
 * 受 `WireLayerPurityTest` 的 import 白名单管辖（**不含 `kotlinx.coroutines.`**，
 * 见 `CapabilityPresence` 的类注释）。而本文件需要 `suspend` / `withTimeout`
 * ⇒ 必须落在 `xposed/` 之外。**结果类型**（纯数据）留在那边，与 `Capability` 同处。
 *
 * ## 传输形态（§3.4 定案：「传输异步，业务等待同步」）
 *
 * ```
 * ① 本函数（调用方工作线程）   await 结果 —— 语义上【同步】等
 *         ↓ oneway invoke（不阻塞）
 * ② hook 层 binder 线程       接单 + 登记 waiter + 投递，立即返回 —— 【不占 binder 线程】
 *         ↓ 投递到工作线程
 * ③ hook 层工作线程           真正执行 handler，跑完回 IHookHost.resolve
 * ④ 本函数被唤醒              拿到结果
 * ```
 *
 * ## ⚠️⚠️ 不得在主线程调用 [invoke]
 *
 * 它最终是一次**同步 binder 事务**到 system_server（`IHookCallback.invoke` 的注释里
 * 有同一条纪律，`pushConditions` 也有）。本函数**刻意不做任何线程切换**
 *（没有 `withContext(Dispatchers.IO)`）—— 那会把「调用方本来就该在工作线程」
 * 这件事掩盖掉，让主线程调用「看起来能用」。**由调用方负责**，
 * 并在 KDoc 里写死这条约束。
 *
 * ## 结果面是**密封三态**，不是抛异常
 *
 * 见 [CapabilityInvokeOutcome] 的类注释：「走了降级路径」这个中间态在
 * `try/catch` 模型里**无处安放**，而它必须留痕（§6.2）。
 */
object CapabilityInvoker {

    private const val TAG = "CapabilityInvoker"

    /** appContext。⚠️ 存 applicationContext（本单例活得比任何界面都长）。 */
    @Volatile
    private var appContext: Context? = null

    /** 由 [CapabilityRuntime.attach] 注入。 */
    internal fun attach(context: Context?) {
        appContext = context?.applicationContext
    }

    // ════════════════════ 对 HookChannelController 的耦合点 ════════════════════

    /**
     * 与 `HookChannelController` 的**全部**耦合。
     *
     * ## ⚠️ 为什么抽成接口而不是直接调那个 object
     *
     * 本类最关键的三条用例都依赖「在**特定时刻**制造断连」：
     *
     * ```
     * ① registerWaiter 成功【之后】断连 ⇒ 立刻 channel_down（不白等超时）
     * ② registerWaiter 之前断连        ⇒ 立刻 channel_down
     * ③ registerWaiter 成功【之后】但代次变了 ⇒ 主动注销 waiter（堵泄漏）
     * ```
     *
     * 而 `HookChannelController` 是**进程级单例**，它在纯 JVM 测试里能用的接缝只有
     * `resetWaitersForTest` / `injectTokenForTest` / `notifyOnConnected`，
     * **没有**「让 waiter 注册成功、但随后代次变化」这种时机注入点。
     *
     * ⇒ 抽接口，默认实现直接转发给单例；测试注入一个**假实现**来精确控制时机。
     * 这与本仓库既有的 `LogcatCaptureController`（进程级单例 + 可注入探测）同一种做法。
     *
     * ⚠️ 接口是 `internal` —— 它不是公共 API，只是本模块与 Controller 之间的缝。
     */
    internal interface Hooks {
        fun callback(): IHookCallback?
        fun token(): String
        fun registerWaiter(requestId: String, sink: (CapabilityResponse) -> Unit): Boolean
        fun unregisterWaiter(requestId: String)
        fun disconnectGeneration(): Long
    }

    /** 默认实现：全部转发给 `HookChannelController`。 */
    private val defaultHooks = object : Hooks {
        override fun callback(): IHookCallback? = HookChannelController.callbackOrNull()
        override fun token(): String = HookChannelController.currentToken()
        override fun registerWaiter(requestId: String, sink: (CapabilityResponse) -> Unit): Boolean =
            HookChannelController.registerWaiter(requestId, sink)

        override fun unregisterWaiter(requestId: String) {
            HookChannelController.unregisterWaiter(requestId)
        }

        override fun disconnectGeneration(): Long = HookChannelController.disconnectGeneration()
    }

    @Volatile
    private var hooksOverride: Hooks? = null

    private fun hooks(): Hooks = hooksOverride ?: defaultHooks

    /** **仅供测试**：注入假 hooks（传 null 恢复默认）。 */
    internal fun attachHooksForTest(h: Hooks?) {
        hooksOverride = h
    }

    // ════════════════════ 入口 1：只调用，不降级 ════════════════════

    /**
     * 发起一次能力调用。**不降级** —— 任何失败都是一等结果（密封三态），**不抛异常**。
     *
     * @param capability capability 名（用 `CapabilityNames` 里的常量，别写字面量）
     * @param params 参数。⚠️ 会被序列化成请求信封里的 `params` 串（见 [buildRequestJson]）
     * @param timeoutMs 超时。`null` ⇒ 用 `Capability.timeoutMs`；再 `null` ⇒
     *   [CapabilityInvocationCodec.DEFAULT_TIMEOUT_MS]（5000）
     * @param threadMode 执行模式（`default` / `io` / `ui`，见
     *   [com.chaomixian.vflow.xposed.wire.ThreadModes]）。`null` ⇒ **不写这个键 = 未指定**
     *   ⇒ hook 侧回落 `default`。
     *   ⚠️ **本层不做归一**（未知值原样发出去）：归一有两个执行环境各自的落点
     *   （App 侧 `normalizeThreadMode` / hook 侧 `InvokePolicy.threadModeOf`），
     *   在这里多做一次会让「发出去的值」与「调用方给的值」不等，round-trip 断言失去意义。
     *   ⚠️ 传未知值的后果**只是资源画像不准**（hook 侧降级到默认池），**不会让调用失败**。
     */
    suspend fun invoke(
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Long? = null,
        threadMode: String? = null,
    ): CapabilityInvokeOutcome {
        // ── 步骤 0：查表（§3.2/§4.3「未知 capability 必须显式报错」）──
        //
        // ⚠️ 与「未知 topic 忽略」**方向相反**：事件流多一条少一条无所谓，
        // 而调用方在等结果 —— 静默会让超时把排查引向错误方向（用户会往 hook 点/
        // 系统版本上找，而真相是名字拼错了或能力没注册）。
        val cap = CapabilityRegistry.find(capability) ?: return CapabilityInvokeOutcome.Failed(
            capability,
            CapabilityFailure(
                CapabilityErrorCode.HANDLER_ERROR,
                "未注册的 capability：$capability（检查名字，或能力表里没登记）",
            ),
        )

        // ── 步骤 1：presence 避免白试（§6.3）──
        //
        // ⚠️ **只判 ABSENT 这一格**。UNKNOWN 与 READY 都继续试：
        //   · READY   —— 当然要试；
        //   · UNKNOWN —— 「拿不到答案」不等于「方法不存在」。hook 层可能
        //     实现了 capabilities() 但没实现 ping()（本判据无法分辨），
        //     也可能只是那一刻连接刚断。把它当失败会**误杀**这类 hook 层，
        //     代价只是「可能白等一次超时」—— 那是可接受的一侧。
        //
        // ⚠️ 它**不决定降级**（§6.3 定案）：降级看运行时的实际结果。
        val presence = CapabilityPresenceHolder.presence.value
        if (presence == CapabilityPresence.ABSENT) {
            DebugLogger.w(TAG, "能力 $capability 判为 ABSENT（hook 层代码太旧），不发起调用")
            // ⚠️ 码用 CAPABILITY_ABSENT，**由本端生成、不发给 hook 层**（§6.4 的生产者说明）。
            // 它的 userAction 指向 UPGRADE_APP —— 即「重启/升级 App」，
            // **不是**「去改 LSPosed 配置」（P4 踩过那个坑：旧文案把用户引向错误方向）
            return CapabilityInvokeOutcome.Failed(
                capability,
                CapabilityFailure(
                    CapabilityErrorCode.CAPABILITY_ABSENT,
                    "当前 hook 层代码里没有 ③ 能力交换（重启 App 后再试）",
                ),
            )
        }

        // ── 步骤 2：连接闸（原子快照）──
        //
        // ⚠️⚠️ 三个都要一次性读出**并**校验。分开读会引入新的窗口：
        // 「读到 cb 非 null，随后断开，于是 token 读到空串」是正常的
        //（那种情形由下面的代次复查兜住），但「token 为空却仍然提交」
        // 会得到一个必然被 onResolve 第①段拒掉的请求 —— 白等一次超时。
        //
        // ⚠️ 断连判据用 `token.isEmpty()` 而不是只判 callback：
        // 与 `onResolve` 第①段的判据**同源**（「本侧无 token」= 未连接）。
        // 两者用不同判据会导致「我们认为发了、对方永远不回」。
        val gen0 = hooks().disconnectGeneration()
        val cb = hooks().callback()
        val token = hooks().token()
        if (cb == null || token.isEmpty()) {
            return CapabilityInvokeOutcome.Failed(
                capability,
                channelDownFailure("hook 层未连接，无法发起调用"),
            )
        }

        // ── 步骤 3：注册 waiter ──
        val requestId = UUID.randomUUID().toString()

        // ⚠️⚠️ CompletableDeferred 而不是 `AtomicReference<CapabilityResponse>?` + 轮询：
        // 前者天然就是「挂起直到有值」，且**第二次投递**能被显式处理 ——
        // 见下面的 sink。用 AtomicReference 只能忙等，而忙等会占满调用方线程。
        val deferred = CompletableDeferred<CapabilityResponse>()

        val sink: (CapabilityResponse) -> Unit = { resp ->
            if (!deferred.complete(resp)) {
                // ⚠️⚠️ 这条路径（超时之后迟到的响应）**必须留痕**。
                //
                // 它在旧实现里被静默吞掉（`waiters.remove` 之后 `sink == null` 就走
                // 「无配对的响应」那条日志），而那时我们已经无法区分
                // 「hook 层真的没回」与「回得太晚、我们已放弃」——
                // 而这两者的排查方向完全不同（前者查 hook 层，后者调 timeout_ms）。
                //
                // 不改返回值（调用方已经拿到 TIMEOUT 了），只让日志里能看见。
                DebugLogger.w(
                    TAG,
                    "⚠️ 超时后收到迟到响应，已丢弃（capability=$capability requestId=$requestId）。" +
                        "若频繁出现，说明 timeout_ms 偏紧或 hook 侧 handler 太慢",
                )
            }
        }

        if (!hooks().registerWaiter(requestId, sink)) {
            // ⚠️⚠️ **立刻返回，绝不继续等**（§5.2：配对表必须有界，满了就拒绝新注册）。
            //
            // 池满归 HANDLER_ERROR 而不是 TIMEOUT —— 口径已写死在
            // `CapabilityErrorCode.HANDLER_ERROR` 的文档里（:68-87）：
            //   TIMEOUT   = 「等了 timeout_ms 仍无结果」  → 报告问题（可能真的慢）
            //   池满      = 「立刻就知道做不了」          → 看具体能力（并发打满？）
            // 两者**发生时序与含义都不同**，混用会把排查引向错误方向。
            //
            // ⚠️ 这里也调一次 unregisterWaiter：**幂等**，且能防住
            // 「某实现返回 false 却仍把 waiter 留在表里」这种差异
            //（表满与写入失败是两件事，不该假定它们同步）。
            hooks().unregisterWaiter(requestId)
            DebugLogger.w(TAG, "配对表已满，拒绝新调用（capability=$capability）")
            return CapabilityInvokeOutcome.Failed(
                capability,
                CapabilityFailure(
                    CapabilityErrorCode.HANDLER_ERROR,
                    "配对表已满（在途调用过多），本次调用未提交",
                ),
            )
        }

        // ── 步骤 3.5：⚠️⚠️ 断连复查（堵「注册前断连」这个洞）──
        //
        // ## 为什么必须有这一步
        //
        // `failAllWaiters`（`onCallbackUnregistered` 里调）会**换代 + 唤醒全部
        // 已注册的 waiter**。于是两个方向的覆盖不对称：
        //
        // ```
        // 断连发生在 waiter 注册【之后】 ⇒ 被唤醒 ⇒ 立刻拿到 channel_down   ✅
        // 断连发生在 waiter 注册【之前】 ⇒ 没人唤醒它 ⇒ 这条 waiter【永久残留】 ❌
        // ```
        //
        // 后者的后果是**泄漏**：`MAX_WAITERS = 64` 满了之后**所有**调用都回
        // `handler_error`，而用户看到的是「这个功能突然全坏了」。
        //
        // 窗口是真实存在的：本函数跑在调用方工作线程，`onCallbackUnregistered`
        // 跑在 App 侧 binder 线程 —— 它恰好落在步骤 2 与步骤 3 之间是可能的。
        //
        // ## 为什么用代次复查而不是「挂一个断连回调」
        //
        // `HookChannelController.onCallbackUnregistered` **没有对外注册接口**
        //（它是被 `HookChannelService` 调的）。现造一个只有单一消费者的注册表
        // 正是 §7.4 反模式 1 的形态。而代次是**纯读**的：
        //
        // ```
        // gen0（步骤 2 快照） != 此刻的代次  ⇒  期间发生过断连  ⇒  这条 waiter 没人管
        // ```
        //
        // 无等待、无副作用、不依赖任何回调是否被触发。
        if (hooks().disconnectGeneration() != gen0) {
            hooks().unregisterWaiter(requestId)
            DebugLogger.i(
                TAG,
                "提交前检测到断连（代次 ${gen0} → ${hooks().disconnectGeneration()}），已注销 waiter",
            )
            return CapabilityInvokeOutcome.Failed(
                capability,
                channelDownFailure("提交前连接已断开"),
            )
        }

        // ── 步骤 4：oneway 提交 ──
        //
        // ⚠️⚠️ `effectiveTimeout == null` = **不超时**（2026-10-02 改）。
        // 判据是「两个来源都是 null」，而 `Capability.timeoutMs` 也 null ⇒ 不超时。
        // 详见下方「不超时如何跨进程表达」。
        val effectiveTimeout = timeoutMs ?: cap.timeoutMs

        val requestJson = buildRequestJson(
            capability = capability,
            params = params,
            // ⚠️ **null ⇒ 不写 `timeout_ms` 键**（不是写 0）——
            // hook 侧的 `decodeRequest` 见到键缺失会走它自己的兜底，
            // 而写 0 会被理解为「立刻超时」（见 `InvokePolicy.effectiveTimeoutMs` 的注释）。
            timeoutMs = effectiveTimeout,
            requestId = requestId,
            // ⚠️ **原样透传，不在这一层归一** —— 「未知值回落 default」是两个执行环境
            // 各自的职责（App 侧 `normalizeThreadMode` / hook 侧 `InvokePolicy.threadModeOf`）。
            // 在这里归一会让发出去的值与调用方给的值不等，round-trip 失去意义。
            threadMode = threadMode,
            token = token,
        )

        try {
            cb.invoke(requestJson)
        } catch (t: Throwable) {
            // ⚠️ oneway 的语义是「不等应答」，所以「提交」这一步本身失败
            // 说明**连接已经不可用了**（binder 已死 / 事务无法排队）。
            // 与超时不同：此刻我们**确知**请求没发出去（或对方收不到），
            // 所以不必白等整个 timeout。
            hooks().unregisterWaiter(requestId)
            DebugLogger.w(TAG, "提交请求失败：${t.javaClass.simpleName} ${t.message}")
            return CapabilityInvokeOutcome.Failed(
                capability,
                channelDownFailure("提交请求失败：${t.javaClass.simpleName}"),
            )
        }

        // ── 步骤 5：等待（可带超时）──
        //
        // ⚠️⚠️ `effectiveTimeout == null` ⇒ **无限等**（`deferred.await()` 不带 `withTimeout`）。
        // 这不是遗漏 —— 是「不填则不超时」的落实（与 `JsExecutor` 的 `null` 语义一致）。
        // ⚠️ **代价必须知道**：hook 侧脚本若阻塞（`Thread.sleep` / 卡住的 IO），
        // 指令级中断对它无效 ⇒ 本函数会**一直挂着**，该工作流也一直停在这一步。
        // 兜底是**工作流级**的 `Workflow.maxExecutionTime`（`WorkflowExecutor.kt:246`），
        // ⚠️ 而它**默认是关的**（`null`）。
        val response = try {
            if (effectiveTimeout == null) {
                deferred.await()
            } else {
                withTimeout(effectiveTimeout) { deferred.await() }
            }
        } catch (t: Throwable) {
            // ⚠️⚠️ CancellationException 也走这里（withTimeout 的超时就是靠取消实现的）。
            // 若只 catch TimeoutCancellationException，协程被上游取消时会**漏掉注销** ⇒ 泄漏。
            //
            // ⚠️ 注销是**唯一**的清理动作，且必须在**所有**失败路径上做 ——
            // 不做的话 waiter 留在表里直到下一次断连（而不断连就永远不清）。
            hooks().unregisterWaiter(requestId)

            // 区分「真超时」与「被上游取消」是有必要的：
            // 前者是 §6.4 里「报告问题」那一类，后者是正常的生命周期事件（不该报 bug）
            // ⚠️ `effectiveTimeout` 可能是 null（= 不超时）⇒ 文案要分开写，
            // 否则会渲染成「等待 nullms 内未完成」。
            val window = effectiveTimeout?.let { "${it}ms 内" } ?: "（未设超时）"
            val detail = if (t is CancellationException) {
                "调用被取消（等待 $window 未完成）"
            } else {
                "等待 $window 无响应（hook 层未回 resolve）"
            }
            DebugLogger.w(TAG, "调用失败：$detail（capability=$capability requestId=$requestId）")
            return CapabilityInvokeOutcome.Failed(
                capability,
                CapabilityFailure(CapabilityErrorCode.TIMEOUT, detail),
            )
        }

        // ── 步骤 6：分类（§6.4 的失败分类落在 code 上，不靠 detail 文案）──
        return classifyResponse(capability, response, cap)
    }

    // ════════════════════ 入口 2：调用 + 降级分派 ════════════════════

    /**
     * 调用 + **按运行时结果**分派降级。
     *
     * ## 分派规则（§6.2 定案）
     *
     * | `invoke` 的结果 | `fallback` | 返回 |
     * |---|---|---|
     * | [CapabilityInvokeOutcome.Success] | 任意 | 原样返回（**不执行 fallback**） |
     * | 失败 / 降级不可用 | `null`（**独占型**） | [CapabilityInvokeOutcome.Failed] —— 明确告知 + 引导 |
     * | 失败 / 降级不可用 | 非 null（**替换型**） | [CapabilityInvokeOutcome.Degraded] —— 静默降级 + **留痕** |
     *
     * ⚠️⚠️ **判据永远是「运行时调用的实际结果」**（§6.3 定案）：
     * `CapabilityPresence` 只在 `invoke` 内部用来避免白试，**不用来判降级**。
     * `CapabilityFallbacks.planOf` 同理 —— 它只回答「有没有原生替代实现」，
     * 不回答「要不要降级」。
     *
     * ⚠️ 降级的契约是「**同样的入参、同形状的结果、更差的实现**」（§6.2 的 S7 修正）
     * ⇒ 把 `params` **原样**传给它（`Capability.fallback` 的签名就是为此设计的）。
     *
     * ⚠️ 降级实现自己抛异常时，**不能**把异常放出去（本函数的契约是「不抛」）——
     * 转成一个 `Failed`，并在 `detail` 里写明「降级实现也失败了」。
     * 这一支与「独占型」合并成同一个 `Failed`（对调用方而言处置完全一样）。
     */
    suspend fun invokeOrFallback(
        capability: String,
        params: Map<String, Any?> = emptyMap(),
        timeoutMs: Long? = null,
        threadMode: String? = null,
    ): CapabilityInvokeOutcome {
        val outcome = invoke(capability, params, timeoutMs, threadMode)
        if (outcome is CapabilityInvokeOutcome.Success) return outcome

        // 到这里 outcome 必是 Failed（invoke 只会返回 Success 或 Failed）
        val failure = (outcome as? CapabilityInvokeOutcome.Failed)?.failure
            ?: return outcome   // 防御：将来 invoke 若再引入新分支，不静默降级
        val cap = CapabilityRegistry.find(capability)

        // ⚠️ 判据用「声明里有没有实现」，**不是**「presence 是不是 ABSENT」。
        // 后者是**采样**的（§6.3：「状态是采样的，结果才是权威」）——
        // 拿它决定降级会让「hook 层其实好好的、只是那一次调用超时」也去走降级，
        // 而那正是 §7.4 反模式 8（用状态位替代运行时判断）。
        val fallback = cap?.fallback
        if (fallback == null) {
            DebugLogger.w(
                TAG,
                "能力 $capability 是**独占型**（无降级实现），失败即失败：" +
                    "${failure.code.wire}",
            )
            return CapabilityInvokeOutcome.Failed(capability, failure)
        }

        // ── 替换型：静默降级 + 留痕 ──
        return try {
            val result = fallback(params)
            DebugLogger.i(
                TAG,
                "能力 $capability 已**降级**（原因：${failure.code.wire}）。" +
                    "⚠️ 降级实现是有损的 —— 同一台设备降级前后结果可能不同，" +
                    "「昨天能用今天不能用」先查这里",
            )
            CapabilityInvokeOutcome.Degraded(capability, result, failure)
        } catch (t: Throwable) {
            // ⚠️ 降级实现也坏了。不能静默（那是「功能没有了但没人知道」），
            // 也不能把异常放出去（契约是不抛）。
            DebugLogger.e(
                TAG,
                "能力 $capability 的降级实现也失败：${t.javaClass.simpleName} ${t.message}",
            )
            CapabilityInvokeOutcome.Failed(
                capability,
                CapabilityFailure(
                    failure.code,
                    "${failure.detail}；且降级实现也失败：${t.javaClass.simpleName}",
                ),
            )
        }
    }

    // ════════════════════ 纯函数：请求构造与响应分类 ════════════════════

    /**
     * 构造请求信封串。**纯函数、不碰 binder** —— 因此可单测。
     *
     * ## ⚠️⚠️ `params` 的序列化：必须是 `JSONObject(params).toString()`
     *
     * 实测（`org.json:json:20251224`，与测试 classpath 同版本）：
     *
     * ```
     * new JSONObject({"s":"x","obj":{"k":"v"},"list":[1,2,3]}).toString()
     *   ⇒ {"list":[1,2,3],"obj":{"k":"v"},"s":"x"}      ✅ 嵌套 Map / List 正确深层序列化
     *
     * params.toString()      ← Kotlin 的 Map.toString()，即**反证用的 bug 版本**
     *   ⇒ {obj={k=v}, list=[1, 2]}                      ❌ 非法 JSON
     *      JSONObject(...) 解析它抛 JSONException ⇒ hook 侧什么都拿不到
     * ```
     *
     * （我前一版方案写了一段「防御式递归转换」——**实测证明它是多余的**，
     *  `JSONObject(Map)` 本来就正确。留下的只有这条注释与
     *  `CapabilityInvokerTest` 的嵌套断言。）
     *
     * ⚠️ 信封层不认识任何业务字段（`CapabilityInvocationCodec.encodeRequest` 的约定）——
     * 本函数只做「把 Map 变成 JSON 串」这一件事，不校验 `params` 的内容。
     */
    fun buildRequestJson(
        capability: String,
        params: Map<String, Any?>,
        timeoutMs: Long?,
        requestId: String,
        cursor: String? = null,
        threadMode: String? = null,
        token: String,
    ): String = CapabilityInvocationCodec.encodeRequest(
        requestId = requestId,
        capability = capability,
        paramsJson = JSONObject(params).toString(),
        // ⚠️⚠️ **原样透传，不要把 null 变成默认值**（2026-10-02 改）。
        // 此前这里是 `timeoutMs ?: DEFAULT_TIMEOUT_MS`，会把「不超时」静默改写成 5000 ——
        // 那种失败**没有任何报错**，只表现为「我的长脚本无缘无故被掐断」。
        // codec 见到 null 会**不写这个键**（旧 hook 层收到缺失则走它自己的兜底，
        // 是安全的降级方向：旧端按 5000 处理，不会永久挂起）。
        timeoutMs = timeoutMs,
        cursor = cursor,
        // ⚠️ 同 `timeoutMs`：原样透传。业务含义由 hook 侧解释，信封层只搬运。
        threadMode = threadMode,
        token = token,
    )

    /**
     * 把 hook 层的响应分类成结果。**纯函数**。
     *
     * ## ⚠️ 分支一律用 `ok` 与 `error.code`（枚举）——**绝不用 `error.detail`**
     *
     * §6.4 约束 2：`detail` 是自由文本、会被三语本地化。
     * `CapabilityInvokeOutcomeTest` 有一条源码扫描断言锁住这件事。
     *
     * ## ⚠️ hook 层「能力不存在」的响应归 `CAPABILITY_ABSENT`
     *
     * §3.2/§4.3：hook 层**收到了请求但不会做**时必须显式报错（能应答，所以能报错）——
     * 这与「hook 层根本没这个方法」（收不到，只能静默等超时）是**不同层次的失败**。
     * 前者由本函数原样透传 `CAPABILITY_ABSENT`，后者表现为 `TIMEOUT`。
     *
     * ## ⚠️ 降级**不在这里判**
     *
     * 本函数只回答「这次调用成不成」。降级由 [invokeOrFallback] 看结果决定 ——
     * 两件事分开，是为了让「降级依据永远是运行时结果」这句话在代码结构上就成立。
     */
    internal fun classifyResponse(
        capability: String,
        response: CapabilityResponse,
        cap: Capability?,
    ): CapabilityInvokeOutcome {
        if (!response.ok) {
            val error = response.error
                ?: CapabilityError(CapabilityErrorCode.HANDLER_ERROR, "失败但未提供原因")
            return CapabilityInvokeOutcome.Failed(
                capability,
                CapabilityFailure(error.code, error.detail),
            )
        }

        val result = try {
            jsonObjectToMap(JSONObject(response.resultJson))
        } catch (t: Throwable) {
            // ⚠️ 成功但结果解不出来 —— 这是**协议层的问题**（hook 层违反了
            // 「result 是 JSON 对象」的约定），不是用户的错。归 HANDLER_ERROR
            //（指向「看具体能力」），而不是 TIMEOUT（那会让用户以为是性能问题）。
            DebugLogger.w(TAG, "结果不是合法 JSON 对象：${t.javaClass.simpleName}")
            return CapabilityInvokeOutcome.Failed(
                capability,
                CapabilityFailure(
                    CapabilityErrorCode.HANDLER_ERROR,
                    "hook 层返回的结果不是合法的 JSON 对象",
                ),
            )
        }

        // ⚠️ maxResultBytes 的校验**不在这里做**：§3.6 契约 2 要求
        // 「hook 侧主动截断」+ 标志位（`response.truncated`），App 侧不重复判大小
        //（那需要重新序列化一遍，而在 App 侧我们没有原始字节数）。
        // cap 参数当前只用于日志与将来可能的逐能力校验 ——
        // ⚠️ 保留它而不是删掉签名，是为了让「这是哪个能力的响应」在类型上可见。
        if (response.truncated) {
            DebugLogger.w(
                TAG,
                "能力 $capability 的结果被**截断**（§3.6 契约 3）：" +
                    "nextCursor=${response.nextCursor ?: "无"}。" +
                    "⚠️ 少了几项**不能**被当成「本来就没有」",
            )
        }

        return CapabilityInvokeOutcome.Success(
            capability = capability,
            result = result,
            elapsedMs = response.elapsedMs,
            nextCursor = response.nextCursor,
            truncated = response.truncated,
        )
    }

    /**
     * `JSONObject` → `Map<String, Any?>`，**递归到 Kotlin 集合类型**。
     *
     * ## ⚠️⚠️ 必须是**深层**转换（2026-10-01 真机实测暴露的缺陷）
     *
     * 本函数原本是**浅层**的（`out[key] = obj.opt(key)`，注释还写着「浅层即可：
     * 值原样保留，嵌套结构自己也是 JSON 类型」）——**那个前提是错的**：
     * 「嵌套结构自己也是 JSON 类型」意味着**每个消费者都要自己再转一次**，
     * 而实际结果是**没有**任何消费者转。
     *
     * 后果（真机实证）：`query_shortcut_intents` 的结果 `result["items"]` 是
     * `org.json.JSONArray`、元素是 `JSONObject`，而消费者写的是
     * `as? List<*>` / `as? Map<*, *>` —— **`org.json` 的两个容器都不实现
     * `java.util.List` / `Map`**，两道转换**全为 null** ⇒ 结果被**静默丢光**，
     * 用户看到空列表。⚠️ 同一文件里 `CapabilityOutcome` 的 KDoc 早就写着
     * 「结果**通用可解码**」，浅层转换**没有兑现这句话**。
     *
     * ## 为什么修在**这里**而不是各消费者
     *
     * 这里修一次，**所有** capability 的消费者受益；修在消费者里则是
     * 「有的处理了有的没处理」——正是 `FORK.md` 记过的那类缺陷（logcat 双份实现）。
     *
     * ⚠️ 类型映射是**无损**的（不改变值的语义，只把 JSON 容器换成 Kotlin 容器）：
     * `JSONObject`→`Map`、`JSONArray`→`List`、`JSONObject.NULL`→`null`、其余原样。
     * ⚠️ 消费者若要判「原本是 JSON null 还是键不存在」，用法与 JSON 语义一致：
     * 键存在值为 JSON null ⇒ 映射后键存在、值为 `null`。
     */
    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>(obj.length())
        for (key in obj.keys()) {
            out[key] = deepConvert(obj.opt(key))
        }
        return out
    }

    /**
     * JSON 值 → Kotlin 值（递归）。见 [jsonObjectToMap] 的说明。
     *
     * ⚠️ `JSONObject.NULL` 必须显式判**在前**（它是 `JSONObject` 的一个哨兵实例，
     * 不是 `null`），否则它会被当普通对象带下去，消费者拿到的是一个
     * `toString() == "null"` 的怪东西。
     * ⚠️ 用 `is JSONObject` / `is JSONArray` 而不是查 `opt` 的重载：
     * `opt` 的类型由**调用点**决定，拿到的是 `Object`，只能靠运行时类型分派。
     */
    private fun deepConvert(value: Any?): Any? = when {
        value == null || value === JSONObject.NULL -> null
        value is JSONObject -> jsonObjectToMap(value)
        value is JSONArray -> List(value.length()) { i -> deepConvert(value.opt(i)) }
        else -> value
    }

    /** 构造 `channel_down` 失败（三处错误路径共用，避免口径漂移）。 */
    private fun channelDownFailure(detail: String): CapabilityFailure =
        CapabilityFailure(CapabilityErrorCode.CHANNEL_DOWN, detail)

    // ════════════════════ 测试接缝 ════════════════════

    /** **仅供测试**：清掉注入的 hooks 与 context。 */
    internal fun resetForTest() {
        hooksOverride = null
        appContext = null
    }

    /**
     * **仅供测试**：把响应里的 `result` 串走一遍**真实的**解码链路。
     *
     * ## ⚠️⚠️ 它存在的理由（不要当多余而删掉）
     *
     * 本仓库踩过一次「测试夹具的类型 ≠ 生产数据的类型」的坑：
     * 用例用手写的 `mapOf(...)` 喂消费者，那是**真正的** `Map`，
     * **恰好绕过**了「生产数据是 `org.json.JSONObject`」这个事实 ⇒
     * 类型失配的缺陷**全绿潜伏**（真机上 `itemsFromLossless` 恒返回空）。
     *
     * ⇒ 这条接缝让新用例从 **JSON 字符串**出发走真实转换，
     * 使「夹具类型 ≠ 生产类型」在结构上不可能重演。
     * `ShortcutPickerFallbackTest` 的 `json round trip…` 一例依赖它，且已做反证。
     */
    internal fun decodeResultForTest(resultJson: String): Map<String, Any?> =
        jsonObjectToMap(JSONObject(resultJson))

    /**
     * ⚠️ 供测试与将来的调用方查「某能力的降级方案」。
     *
     * ⚠️ 它**不参与** [invokeOrFallback] 的判定（那里直接读 `cap.fallback`）——
     * 保留它是为了让「降级方案」这个概念有一个可被断言的名字，
     * 且 `CapabilityFallbacks.planOf` 的实现也对齐它，避免两处口径漂移。
     */
    fun fallbackPlanOf(capability: String): CapabilityFallbackPlan {
        val cap = CapabilityRegistry.find(capability)
        return if (cap?.fallback != null) {
            CapabilityFallbackPlan.NativeCode
        } else {
            CapabilityFallbackPlan.Unavailable
        }
    }
}
