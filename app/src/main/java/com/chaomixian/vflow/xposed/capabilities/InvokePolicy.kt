package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.json.JSONArray
import org.json.JSONObject

/**
 * ③ 执行运行时的**纯函数策略层**：超时 / 字节预算 / 结果 JSON 组装 / 错误构造。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.4 / §3.6 / §5.2 / §6.4。
 *
 * ## ⚠️ 为什么把这些抽成独立的一层
 *
 * [HookCapabilityRuntime] 本身**跑在 system_server 里、由 LSPosed 的 ClassLoader 加载**
 * —— App 侧的单元测试**跑不到它**（见 §7.2b-9）。而这一层里恰恰是最容易「改错了不报错、
 * 只静默变差」的地方（超时判定的边界、字节口径、错误码归属）。
 *
 * ⇒ 把**所有判定**放在这个不依赖 `android.*` / 线程 / IO 的对象里，
 * 让它们能在纯 JVM 单测里逐值锁定。运行时只剩「接线 + 投递」。
 *
 * ## 依赖白名单
 *
 * 本文件在 `xposed/` 下（会被 hook 层加载），只允许 `org.json` / `java.*` / `kotlin.*` /
 * `com.chaomixian.vflow.xposed.*`。由 `WireLayerPurityTest` 源码扫描锁住。
 *
 * ## ⚠️ `capabilities/`（复数）与 `capability/`（单数）是两个包
 *
 * 后者（`xposed/capability/`）是**跨进程契约层的声明与注册表**；
 * 前者（本包）是**hook 侧的执行体**。两者只差一个 `s`，**极易读错**
 * —— 见 [HookCapabilityRegistry] 类注释里的对照表。
 */
object InvokePolicy {

    /**
     * 结果 JSON 里装元素的键名。
     *
     * ⚠️ 它是 [buildResultJson] 的**内部约定**，**不是协议常量** ——
     * `KEY_*` 那批（`CapabilityInvocationCodec`）是与 App 侧约定的线上键，
     * 而本键只出现在 `result` **字符串内部**，由 App 侧按 `result["items"]` 取。
     * 两者不要混在同一个常量表里。
     *
     * ⚠️ **分页两键（`next_cursor` / `truncated`）刻意不在这里** ——
     * 它们走**信封顶层**（`CapabilityResponse`），见 [buildResultJson] 的说明。
     */
    const val KEY_ITEMS = "items"

    /**
     * `detail` 的长度上限（**字符**）。
     *
     * ⚠️ 存在的理由：`detail` 是自由文本，若让它无限长，一条错误响应自己就能
     * 撑爆 binder 的 oneway 缓冲 —— 而那会**静默丢弃**整条响应（§3.6）。
     * 也就是「为了让用户看到错误信息，反而把错误信息丢了」。
     */
    const val MAX_DETAIL_CHARS = 512

    /**
     * hook 侧判定用的超时预算。**取 min**，不钳位。
     *
     * ## 为什么取 min
     *
     * App 侧的等待窗口就是请求里的 `timeout_ms`（第一层超时，§5.2）。
     *
     * | hook 侧判得 | 后果 |
     * |---|---|
     * | **比它长** | 「hook 层判成功、App 侧早已放弃」⇒ 那条响应变成 `onResolve` 里「无配对的响应」告警，纯噪声 |
     * | 比它短 | 本来能成功的被误判超时 |
     *
     * ⇒ 取 min 让两端对齐。
     *
     * ## ⚠️ 本函数**不做非负钳位**（刻意，理由如下）
     *
     * 初版写过 `coerceAtLeast(1)`，理由是「0 会让正常请求必然失败」—— **那个理由不成立**：
     * 1ms 对正常 handler 同样是必然超时，钳到 1 并没有解决问题，只是把「0 立刻超时」
     * 变成「1ms 后超时」。而 0 也确实**不该被静默改写**：
     *
     * - `timeout_ms` 是**请求侧的输入**（App 逐次给出）。把它悄悄改成 1 会让
     *   「App 说 0、hook 层按 1 算」，两端对同一字段的解释不一致；
     * - 一个「预算 0」的调用**本来就该立刻失败** —— 回 `TIMEOUT` 正是它的正确语义；
     * - ⚠️ 更要紧的是：**钳位会把「App 侧算错了超时」这类 bug 掩盖成「handler 太慢」**，
     *   又一条「静默变差」。
     *
     * ⇒ 让 0 与负数原样生效（`elapsed >= 0 > budget` ⇒ 立刻判超时），值是多少就是多少。
     *
     * @param requestedMs 请求里的 `timeout_ms`（[com.chaomixian.vflow.xposed.wire.CapabilityRequest.timeoutMs]）
     * @param declaredMs capability 自己声明的超时（[CapabilityHandler.timeoutMs]），null ⇒ 用请求里的
     */
    fun effectiveTimeoutMs(requestedMs: Long, declaredMs: Long?): Long =
        if (declaredMs == null) requestedMs else minOf(requestedMs, declaredMs)

    /**
     * §3.6 契约 1：有效字节上限。声明非法（`<= 0`）时回落 [ResultBudget.DEFAULT_MAX_RESULT_BYTES]。
     *
     * ## ⚠️ 与 [effectiveTimeoutMs] 的处理**刻意不同**（那边不钳位）
     *
     * 因为两者的**来源不同**，不该用同一套处理：
     *
     * | | 来源 | 非法值的合理解释 |
     * |---|---|---|
     * | `declaredBytes`（本函数） | **声明侧**（`Capability.maxResultBytes`） | 写 0 或负数属于**笔误** ⇒ 回落默认值是唯一合理的解释 |
     * | `requestedMs`（上一条） | **请求侧**，App 逐次给出 | 是**真实的运行期输入**（已被 `CapabilityInvocationCodec.decodeRequest` 钳到 `>= 0`）⇒ 必须原样生效 |
     *
     * 把声明侧的笔误当成「上限 0 字节」会让该 capability **永远返回空结果**，
     * 且没有任何报错 —— 正是本仓库反复记录的那类静默失效。
     */
    fun effectiveMaxBytes(declaredBytes: Int?): Int =
        declaredBytes?.takeIf { it > 0 } ?: ResultBudget.DEFAULT_MAX_RESULT_BYTES

    /**
     * 超时判定。
     *
     * ⚠️ **严格大于** —— 恰好用满预算**不算**超时。
     * 用 `>=` 会让「正好卡在预算上」的调用被误判，而那是**合法**的成功执行。
     *
     * ⚠️ 本判定是**事后**的（跑完才算），不是看门狗 —— 见 [HookCapabilityRuntime] 类注释。
     */
    fun isTimedOut(elapsedMs: Long, budgetMs: Long): Boolean = elapsedMs > budgetMs

    /**
     * 单个元素的字节成本估算（喂给 [ResultBudget.collectWithin] 的 `sizeOf`）。
     *
     * ⚠️ **它是近似值**：元素经 [JSONObject] 序列化后真实写出的字节会因
     * 转义（`"` → `\"`、控制字符 → `\uXXXX`）与数组逗号而与它不同。
     * 故 [HookCapabilityRuntime.RESULT_ENVELOPE_MARGIN_BYTES] 是**必需开销**而非宽松余量，
     * 且发出去之前必须**再验一次真实字节数**。
     *
     * ⚠️ 用 [ResultBudget.byteSizeOf]（字节）而不是 `String.length`（字符）——
     * 全 CJK 时后者会把预算**低估 3 倍**，而那会直接撞上 binder 的 oneway 上限，
     * 表现是**整条响应被静默丢弃**。
     */
    fun itemByteCost(item: Map<String, Any?>): Int =
        ResultBudget.byteSizeOf(JSONObject(item).toString())

    /**
     * 把收下的元素序列化成 **`resultJson`** —— `{"items":[…]}`。
     *
     * ## ⚠️⚠️ **分页两键不在这里**（它们走**信封顶层**）
     *
     * ```
     * ① IHookHost.resolve 收到的响应信封    ← CapabilityInvocationCodec.encodeResponse(...)
     *    { "request_id": …, "ok": true,
     *      "result": "{\"items\":[…]}",      ← result 本身是一个【字符串】，内容是 ②
     *      "next_cursor": "1310",             ★ 分页两键在【信封顶层】
     *      "truncated": true,                 ★
     *      "elapsed_ms": 42, "token": … }
     *
     * ② resultJson 内部                     ← 本函数
     *    { "items": [ {…}, {…} ] }            ← 只有元素【对象】，不带分页键
     * ```
     *
     * **为什么不能塞进 ②**：App 侧的 `CapabilityInvokeOutcome.Success.nextCursor`
     * / `.truncated` 是**从 `CapabilityResponse`（层 ①）读的**。塞进 `result` 内部
     * ⇒ 那两个字段**永远填不上、恒为缺省值**，而 codec 的 round-trip 测试**照样全绿**
     * （它测编解码、不测「谁填的」）。
     *
     * ⇒ 截断一旦发生，App 侧会把「少了几项」当成「本来就没有」——
     * 正是 §3.6 契约 3 明令要避免的形态。
     *
     * ## 元素是**对象**（不是预序列化的 JSON 串）
     *
     * 由框架统一用 [JSONArray] 序列化，带来三点：
     * ① 结果**通用可解码**（App 侧 `JSONObject(resultJson).toMap()` 直接能用，
     *    不需要「与 handler 同构的自定义解码器」）；
     * ② handler **不可能**产出非法 JSON 片段；
     * ③ 避免「绕过共享编解码器另行解析」的反模式。
     *
     * ⚠️ **代价**：字节记账从精确变成**近似**（转义与逗号），见 [itemByteCost]。
     */
    fun buildResultJson(items: List<Map<String, Any?>>): String =
        JSONObject()
            .put(KEY_ITEMS, JSONArray().apply { items.forEach { put(JSONObject(it)) } })
            .toString()

    /** 未知名 → `capability_absent`（含「当前已注册了哪些」，供人看）。 */
    fun unknownCapabilityError(name: String, registered: Collection<String>): CapabilityError {
        val list = if (registered.isEmpty()) {
            "（空）"
        } else {
            registered.sorted().joinToString(", ")
        }
        return sanitize(
            CapabilityError(
                code = CapabilityErrorCode.CAPABILITY_ABSENT,
                detail = "hook 层没有名为 \"$name\" 的 capability。当前已注册：$list",
            ),
        )
    }

    /**
     * 池满 → `handler_error` + 「工作线程池已满」的人可读 detail。
     *
     * ⚠️⚠️ **归 `handler_error` 而不是 `timeout`** —— 口径已写死在
     * `CapabilityErrorCode.kt:68-87`，**不要新增第六个码**。
     *
     * | | 语义 | 排查方向 |
     * |---|---|---|
     * | `timeout` | 等了 `timeout_ms` **仍无结果** | 报告问题（可能真的慢） |
     * | **池满**（本函数） | **立刻就知道做不了** | 看具体能力（是不是并发打满了） |
     *
     * ⚠️ `detail` 仍**只给人看、绝不参与判断**（它会被三语本地化）。
     */
    fun poolExhaustedError(poolSize: Int): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.HANDLER_ERROR,
            detail = "工作线程池已满（容量 $poolSize 个并发）。请稍后重试或降低并发调用。",
        ),
    )

    /**
     * 运行时已停止 → `handler_error` + 「执行运行时已停止」的人可读 detail。
     *
     * ## ⚠️⚠️ **不可与 [poolExhaustedError] 混同**
     *
     * 两者的 `code` 相同（都是 `handler_error`，不新增第六个码），
     * 但**成因完全不同**，而 `detail` 是用户唯一能看到的线索：
     *
     * | 成因 | 用户该做什么 |
     * |---|---|
     * | 池满 | **稍后重试**（池子会空出来） |
     * | **运行时已停止**（热更新换代 / 通道关闭） | **不会有响应**，重试无用 |
     *
     * 不区分的话，本情形会报出「工作线程池已满（容量 2 个并发）」——
     * 用户会去查并发，而真实原因是「运行时已停」。
     * 这正是 `CapabilityErrorCode.kt:78-86` 花一整段要避免的「排查方向错掉」。
     */
    fun runtimeStoppedError(): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.HANDLER_ERROR,
            detail = "执行运行时已停止（hook 层正在换代或已关闭），本次调用不会有响应。",
        ),
    )

    /** 超时 → `timeout`。 */
    fun timeoutError(elapsedMs: Long, budgetMs: Long): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.TIMEOUT,
            detail = "执行超时：耗时 ${elapsedMs}ms 超过预算 ${budgetMs}ms。",
        ),
    )

    /**
     * 载荷超上限 → `payload_too_large`。
     *
     * ⚠️ **不是用户能处理的失败** —— 正常路径下**不该出现这个码**
     * （§3.6 要求 hook 侧主动截断并带标志位）。出现即**实现缺陷或数据异常**
     * ⇒ 用户的处置是「报告问题」，**不要**把他引去改配置。
     *
     * 它兜的是 [ResultBudget.collectWithin] 的**单元素超限特例**：
     * 那种情况下函数会**收下那个超限元素**（否则分页会死循环），
     * 于是整串真的可能超过上限 —— 必须在发出去之前拦下（oneway 超限是**静默丢弃**）。
     */
    fun payloadTooLargeError(actualBytes: Int, maxBytes: Int): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.PAYLOAD_TOO_LARGE,
            detail = "结果超出上限：实际 ${actualBytes} 字节 > 上限 ${maxBytes} 字节。" +
                "这通常是实现缺陷，请报告问题。",
        ),
    )

    /** 任意 [Throwable] → `handler_error`（**绝不逃逸**的落点）。 */
    fun throwableToError(t: Throwable): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.HANDLER_ERROR,
            // ⚠️ message 可能为 null；拼成 `IllegalStateException: null` 会误导排查
            detail = if (t.message == null) {
                t.javaClass.simpleName
            } else {
                "${t.javaClass.simpleName}: ${t.message}"
            },
        ),
    )

    /**
     * handler 自报失败 → 原样，但 `detail` 截断。
     *
     * ⚠️ `detail` 只给人看；不设限会让一条错误响应自己撑爆 binder 缓冲
     * （见 [MAX_DETAIL_CHARS]），而**超限的 oneway 事务是静默丢弃的** ——
     * 也就是「为了报错反而把错误丢了」。
     */
    fun sanitize(error: CapabilityError): CapabilityError =
        if (error.detail.length <= MAX_DETAIL_CHARS) {
            error
        } else {
            // ⚠️ 截到「上限 - 后缀长度」而不是「上限」—— 否则加上后缀又会超限，
            // 而这条函数的**全部意义**就是让结果**确实**落在上限内。
            error.copy(detail = error.detail.take(MAX_DETAIL_CHARS - TRUNCATION_SUFFIX.length) + TRUNCATION_SUFFIX)
        }

    /** `detail` 被截断时追加的标记（长度计入 [MAX_DETAIL_CHARS]）。 */
    private const val TRUNCATION_SUFFIX = "…（已截断）"
}
