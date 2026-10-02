package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityRequest

/**
 * 一个 ③ capability 的 **hook 侧执行体**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.4 / §5.2 / §7.2b-2。
 *
 * ## ⚠️⚠️ `capabilities/`（复数，本包）与 `capability/`（单数）是两个包
 *
 * 两者只差一个 `s`，**极易读错**：
 *
 * | | `xposed/capability/`（单数） | **`xposed/capabilities/`（复数，本包）** |
 * |---|---|---|
 * | 是什么 | 跨进程**契约层的声明与注册表** | hook 侧的**执行运行时** |
 * | 谁用 | App 侧（`CapabilityRegistry.find`） | hook 层（本包） |
 * | 进程 | App 与 hook **跨进程各一份**（不是拷贝） | 只有 hook 层（system_server） |
 *
 * ## ⚠️ 实现约束
 *
 * - [handle] **跑在工作线程上**（不是 binder 线程）—— 可以阻塞。
 *   ⚠️ 三档之后「可阻塞」的程度**按档而异**：`io` 档（`Dispatchers.IO`，最多 64 并发）
 *   适合阻塞等待；`ui` 档是**单线程**且队列有上限，卡住会让后续 `ui` 调用排队甚至被拒。
 * - 实现**不必**自己包 `try/catch`：执行运行时的顶层会兜
 *   （异常转 `handler_error`）。**但那不是偷懒的理由** ——
 *   自己能判定的失败走 [CapabilityOutcome.Failure] 比抛异常好，
 *   因为前者能给出**具体的错误码**（如 `payload_too_large`）。
 *
 * ⚠️ 与 `capability/Capability` 是**两个不同的类型**（跨进程的声明 vs 执行体），
 * 不要互相引用 —— 本包在 `xposed/` 下，引用面受 `WireLayerPurityTest` 管辖。
 */
interface CapabilityHandler {

    /**
     * capability 名。
     *
     * ⚠️ **直接引用 `CapabilityNames` 的常量**（同 dex、同进程，复用无「两份拷贝」代价
     * —— `FORK.md` 里 logcat 那条双份实现的教训不适用这里）。
     */
    val name: String

    /**
     * §3.6 契约 1：本 capability 的**响应大小上限**（字节）。
     * `null` ⇒ 用 [com.chaomixian.vflow.xposed.wire.ResultBudget.DEFAULT_MAX_RESULT_BYTES]（256 KiB）。
     *
     * ⚠️ 声明得越小越安全 —— 那半边异步缓冲是**与所有其他 oneway 事务共享**的。
     */
    val maxResultBytes: Int? get() = null

    /**
     * §5.2：本 capability 的超时。
     * `null` ⇒ 用请求里的 `timeout_ms`（执行运行时会取两者的 **min**，
     * 且**请求侧为 null 时不超时** —— 见 `InvokePolicy.effectiveTimeoutMs`）。
     *
     * ## ⚠️ 这一个值**同时**用于两处判定（别以为它只影响其一）
     *
     * | 判定 | 位置 | 量的是什么 |
     * |---|---|---|
     * | **出队期** | `HookCapabilityRuntime.runOnWorker` 首行 | 请求**在队列里等了多久** —— 超了则**不执行** |
     * | **执行期** | 同上，`handler.handle` 返回后 | handler **跑了多久** —— 超了则结果作废、回 `timeout` |
     *
     * 两处都用 `InvokePolicy.effectiveTimeoutMs(request.timeoutMs, this)`，
     * 且都是 `isTimedOut`（**严格大于**）。⇒ 声明一个值就等于给
     * 「排队 + 执行」的**总时长**设了上限，不是只给执行。
     */
    val timeoutMs: Long? get() = null

    /**
     * 执行。**跑在工作线程上**。
     *
     * @param request 已解码的请求。`params` 由 capability 自己解析
     *   （信封层不认识任何业务字段）。
     */
    fun handle(request: CapabilityRequest): CapabilityOutcome
}

/**
 * handler 的产出。
 *
 * ## ⚠️ 为什么是密封类而不是「直接返回 JSON 串」
 *
 * 让 handler 直接产出预序列化的 JSON 片段有两个真问题：
 * ① handler 产出**非法** JSON 片段时框架**无从校验**；
 * ② 会要求 App 侧用一个**与 handler 同构的自定义解码器** ——
 *   而 `capability/` 与 `capabilities/` 是两个包，同构解码器无法共享，是结构性错配。
 *
 * ⇒ 元素是**对象**、由框架统一序列化 ⇒ 结果**通用可解码**，
 * handler 也不可能产出非法 JSON。代价是字节记账变成近似
 * （见 [InvokePolicy.itemByteCost]）。
 */
sealed interface CapabilityOutcome {

    /**
     * 列表型产出 —— **截断发生在「产出」而不是「序列化后」的落点**。
     *
     * ## ⚠️ [items] 是**窗口**，不是完整列表
     *
     * handler 自己解析请求 `params` 里的 `cursor`（一个下标），
     * **从该下标起**返回该窗口的元素。框架只做**字节兜底**、不替它切片。
     *
     * 理由：`cursor` 在协议里是**不透明串**（`CapabilityRequest.cursor: String?`），
     * 而框架的 `result` 只是一个 `Map` —— 框架若加参数会让同一信息两处存在。
     *
     * @param items 已切好窗口的元素。⚠️ 是**对象**而不是预序列化字符串，见 [CapabilityOutcome]。
     * @param startIndex [items] 在**全量列表**里的起始下标。
     *   框架据此算 `nextCursor = startIndex + 收下的元素数`。
     */
    data class Items(
        val items: List<Map<String, Any?>>,
        val startIndex: Int = 0,
    ) : CapabilityOutcome

    /**
     * 显式失败 —— handler 自己判定「做不了」（而不是抛异常）。
     *
     * ⚠️ 比抛异常**更好**：能给出具体的错误码。
     * 异常一律被顶层兜成 `handler_error`，而 `handler_error` 的语义是
     * 「handler 自己出错」—— 用它表达「参数不合法」「结果太大」会让
     * 用户的排查方向错掉（§6.4 的立意）。
     */
    data class Failure(val code: CapabilityErrorCode, val detail: String) : CapabilityOutcome
}
