package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityError
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.ResultBudget
import com.chaomixian.vflow.xposed.wire.ThreadModes
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
     *
     * ⚠️ 它在**代码单元**里等于 [PARCEL_PER_CODE_UNIT] 倍字节，见 [estimatedParcelBytes]。
     */
    const val MAX_DETAIL_CHARS = 512

    /**
     * 每个 UTF-16 **代码单元**在 binder parcel 里占的字节数。
     *
     * ## ⚠️⚠️ 这是本文件最重要的一条常量（2026-10-01 真机实测得出）
     *
     * 信封是经 `IHookHost.resolve(String)` 发出去的，而 binder 的 AIDL 字符串
     * 在 parcel 里走 `writeString16` ⇒ **每个 UTF-16 代码单元 2 字节**
     *（而非 UTF-8 的「ASCII 1 字节 / CJK 3 字节」）。
     *
     * 对一个全 ASCII 的载荷，这让**信封的 parcel 字节数 = 其 UTF-8 字节数的 2 倍**。
     *
     * ### 实测证据（小米 MIX Fold 3 / Android 17，`diagnostic` 的 `huge` 路径）
     *
     * ```
     * 00:58:53.878  VFlowHook  结果超预算，已截断：收下 1228/2000 项，nextCursor=1228
     * 00:58:53.882  VFlowHook  resolve 异常：TransactionTooLargeException
     *                          data parcel size 533700 bytes
     * ```
     *
     * 逐字复核（1228 个 `huge` 元素）：
     *
     * | 量 | 值 |
     * |---|---|
     * | `resultJson`（单层，UTF-8） | 259,237 字节 |
     * | 信封串（二次转义后） | 266,757 **字符** |
     * | 信封 × 本常量 | **533,514 字节** |
     * | 实测 parcel | **533,700 字节**（差 186 = 信封外的固定开销） |
     *
     * ## ⚠️ 为什么旧实现漏掉了它
     *
     * 旧的「发送前最终校验」量的是 `resultJson` 的 **UTF-8** 字节（[ResultBudget.byteSizeOf]）
     * ⇒ 259,237 < 262,144（256 KiB）**判为通过**，而真实 parcel 是 533,700
     * ⇒ 撞上 oneway 的异步半缓冲（≈508 KiB）⇒ `TransactionTooLargeException`。
     *
     * ⚠️ **这不是边缘情况**：§3.6 的原始警告就是「返回路径必然撞上限」，
     * 旧实现让**任何触发截断的调用都必然失败**。
     */
    const val PARCEL_PER_CODE_UNIT = 2

    /**
     * 信封里与内容**无关**的固定键（`request_id` / `ok` / `elapsed_ms` / `token` /
     * 键名 / 二次转义的少量引号）占用的 parcel 字节**上界**。
     *
     * ## ⚠️⚠️ 它必须是一个**恒定的上界**，不能按预算比例缩小
     *
     * 起初写成了 `min(2 KiB, 预算 / 8)`，被 `a capability can lower its own byte limit`
     * 抓了出来：一个声明 512 字节上限的 capability，预算按比例缩到 128 字节
     * **小于真实的固定开销（约 150 字节）** ⇒ 它**永远装不下任何元素**，
     * 结果是 `payload_too_large`。
     *
     * 那正是本仓库 `RESULT_ENVELOPE_MARGIN` 那个 bug 的形态（固定开销吃光小预算）——
     * 我在重构时重犯了一次，所幸单测抓住了。
     *
     * ## 为什么可以在预算之外**另加**它（而不是从预算里扣）
     *
     * [envelopeParcelBudget] 的语义是「这个 capability **允许返回的内容**有多大」→
     * 换算成 parcel 之后**再加上**信封自身的开销。固定开销是**传输的成本**，
     * 不是内容的一部分 ⇒ 不该从内容预算里扣。
     *
     * 取 512 的依据：`request_id`（UUID 36 字符）+ `token` + `ok`/`elapsed_ms` +
     * 各键名 + 一对引号与括号，实测约 150–200 字节；512 是留了余量的上界。
     */
    const val ENVELOPE_FIXED_OVERHEAD_BYTES = 512

    /**
     * 每个元素在信封里**除内容之外**的开销（parcel 字节）：
     * 元素对象自身的引号（二次转义各 +1 字符）+ 数组分隔的逗号。
     *
     * ⚠️ 取 24 而不是按 `{"k":"v"}` 精确算出的 14（6 个引号 + 1 个逗号，各 2 字节）——
     * 留一倍余量，因为真实 capability 的元素键数与引号数**不可预知**
     *（`{"i":123,"pad":"…"}` 是 6 个引号，`ShortcutInfo` 序列化后会多得多）。
     *
     * ⚠️ 余量只负责「正常数据」；**真正的防线是 [estimatedParcelBytes] 那道终检**。
     */
    const val ITEM_ESCAPE_OVERHEAD_BYTES = 24

    /**
     * 估算**发出去的信封**占用的 binder parcel 字节数。
     *
     * ## ⚠️⚠️ 本函数的全部意义：让「校验对象」=「实际发送对象」
     *
     * 真机实测（见 [PARCEL_PER_CODE_UNIT]）暴露了一个真实缺陷：
     * 旧的终检量的是 `resultJson` 的 UTF-8 字节，而实际发出去的是
     * **外层信封** —— 两者**结构上不是同一个东西**，于是校验通过、发送失败。
     *
     * ⇒ 终检必须量 [com.chaomixian.vflow.xposed.wire.CapabilityInvocationCodec.encodeResponse]
     * 的**返回值**，而不是它内部的某个字段。
     *
     * ## 为什么按「代码单元 × 2」而不是 `String.length × 2`
     *
     * 代理对（emoji）在 Java 里占**两个** `Char`，用 `length` 恰好算对；
     * 但用 `codePointAt` + `charCount` 能对**孤立代理**（不成对的 `Char`）
     * 也算成 2 字节 —— 而 binder 写的是 UTF-16 代码单元，正该如此。
     * 这与 `ActivityPayload.truncateToBytes` 的教训同源：
     * 代理对处的账**必须按代码单元算**，按字符算会低估。
     */
    fun estimatedParcelBytes(json: String): Int {
        var units = 0
        var i = 0
        while (i < json.length) {
            val cp = json.codePointAt(i)
            units += if (cp > 0xFFFF) 2 else 1
            i += Character.charCount(cp)
        }
        return PARCEL_PER_CODE_UNIT * units
    }

    /**
     * 单个元素在信封里的**包裹开销**（parcel 字节）—— 喂给
     * [ResultBudget.collectWithin] 的 `sizeOf`，与 [ENVELOPE_CARRIER_BYTES] 在同一量纲里。
     *
     * ## ⚠️ 与 [itemByteCost] 的关系（别混用）
     *
     * | 函数 | 量纲 | 用途 |
     * |---|---|---|
     * | [itemByteCost] | **单层** UTF-8 字节 | 旧的近似，仍被子用例锁住；不再用于预算 |
     * | **本函数** | **信封内** parcel 字节 | 元素预算的唯一口径 |
     *
     * 换算：元素 JSON 的字符数 c（≈ 单层 UTF-8 字节数，全 ASCII 时相等）在信封里
     * 占 `2 × (c + 引号数)` ≈ [PARCEL_PER_CODE_UNIT] × [itemByteCost]，
     * 再加 [ITEM_ESCAPE_OVERHEAD_BYTES] 的引号/逗号余量。
     *
     * ⚠️ 它仍是**估算**（真实引号数取决于元素的键值形态）⇒
     * [HookCapabilityRuntime] 的终检**不可省**。
     */
    fun itemEnvelopeCost(item: Map<String, Any?>): Int =
        PARCEL_PER_CODE_UNIT * itemByteCost(item) + ITEM_ESCAPE_OVERHEAD_BYTES

    /**
     * 按声明的内容上限换算出的**信封 parcel 预算**，并与传输上限取 min。
     *
     * ## 两个上限是**两件事**，必须都满足
     *
     * | 上限 | 来源 | 含义 |
     * |---|---|---|
     * | `maxResultBytes` | `Capability.maxResultBytes`（per-capability 声明） | §3.6 契约 1：这个能力**愿意**返回多大 |
     * | [ResultBudget.MAX_ENVELOPE_PARCEL_BYTES] | 传输层（binder 异步半缓冲） | 这条链路**传得动**多大 |
     *
     * ## ⚠️ 语义是「**内容**预算」，不含信封自身的固定开销
     *
     * 换算系数是 [PARCEL_PER_CODE_UNIT]：结果 B 字节 ⇒ 信封里占 2B 字节。
     * 信封的固定键（[ENVELOPE_FIXED_OVERHEAD_BYTES]）由调用方**另加**，
     * 不从这里扣 —— 见那个常量的说明（扣了会让小预算能力的预算变成负数）。
     */
    fun envelopeParcelBudget(maxResultBytes: Int): Int =
        minOf(
            ResultBudget.MAX_ENVELOPE_PARCEL_BYTES,
            PARCEL_PER_CODE_UNIT.toLong().times(maxResultBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
        )

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
    fun effectiveTimeoutMs(requestedMs: Long?, declaredMs: Long?): Long? = when {
        // ⚠️⚠️ `null` = **不超时**（2026-10-02 改）。两个来源都为 null ⇒ 不超时。
        // 注意顺序：只要**【请求侧】是 null**，就不超时 —— 即便 capability 自己
        // 声明了一个值。理由见下方「为什么 declared 不能把 null 拉回有限值」。
        requestedMs == null -> null
        declaredMs == null -> requestedMs
        else -> minOf(requestedMs, declaredMs)
    }

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
     * ⚠️ `budgetMs == null` ⇒ **永远不超时**（2026-10-02 改）。
     * ⚠️ 会写这个判据是因为**负数不能用来表达「不超时」**：
     * `elapsed > -1` 恒真 ⇒ 每次调用立刻判超时（新 App + 旧 hook 层时会真的发生）。
     * ⇒ 「不超时」只能靠**可空**表达，绝不能靠哨兵值。
     *
     * ⚠️ 本判定是**事后**的（跑完才算），不是看门狗 —— 见 [HookCapabilityRuntime] 类注释。
     */
    fun isTimedOut(elapsedMs: Long, budgetMs: Long?): Boolean =
        budgetMs != null && elapsedMs > budgetMs

    /**
     * 单个元素的**单层** UTF-8 字节成本（元素的 JSON 串本身，不含信封）。
     *
     * ## ⚠️⚠️ 它**不再是预算口径**（2026-10-01 真机实测后改判）
     *
     * 它量的是元素**单独**序列化后的 UTF-8 字节，而真正决定能否发出去的是
     * **信封的 parcel 字节数**（≈ 单层字节 × [PARCEL_PER_CODE_UNIT] + 引号开销）。
     * 预算一律走 [itemEnvelopeCost]。
     *
     * 保留本函数的理由：它是**可精确断言**的基本量（「ASCII 1 字节 / CJK 3 字节」），
     * 由 `InvokePolicyTest` 逐值锁住 —— 那是 [itemEnvelopeCost] 的地基，
     * 删掉会让换算关系失去锚点。
     *
     * ⚠️ 用 [ResultBudget.byteSizeOf]（字节）而不是 `String.length`（字符）——
     * 全 CJK 时后者会把预算**低估 3 倍**。
     */
    fun itemByteCost(item: Map<String, Any?>): Int =
        ResultBudget.byteSizeOf(JSONObject(item).toString())

    /**
     * 请求里的执行模式 → 三档之一（`default` / `io` / `ui`）。
     * **未知 / `null` 一律回落 [ThreadModes.DEFAULT]，绝不抛。**
     *
     * ## ⚠️ 为什么是这个签名（收 `CapabilityRequest` 而不是 `String?`）
     *
     * 调用点在 [HookCapabilityRuntime] 的 `onInvoke`，那里手上只有 `CapabilityRequest`
     * —— 直接把「信封字段 → 执行器选择」这一步收进策略层，
     * 运行时那一侧就只需 `when (InvokePolicy.threadModeOf(request)) { … }`，
     * 不必自己知道 `null` 该怎么解释。
     *
     * ## ⚠️ 本函数**不**引用 App 侧的 `normalizeThreadMode`
     *
     * `xposed/` 包禁止引用 `com.chaomixian.vflow.core.*`
     *（`WireLayerPurityTest.FORBIDDEN_APP_PACKAGES`，本文件会被 hook 层加载）。
     * 两侧共用的是 [ThreadModes]，**不是彼此** —— 故归一逻辑只有一份实现。
     *
     * ⚠️ **静默降级是硬约束，不是偷懒**：新 App 发 `io`、旧 hook 层不认识时报错
     * 会让它变成一次**调用失败**；降级只损失「资源画像准确度」。完整论证见
     * [ThreadModes.normalize]。
     */
    fun threadModeOf(request: CapabilityRequest): String = ThreadModes.normalize(request.threadMode)

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
     *
     * ## ⚠️⚠️ 2026-10-03 补记：三档执行器下**无生产调用点**
     *
     * 执行器换成 `Dispatchers.Default` / `Dispatchers.IO` / 自建 `HandlerThread` 之后，
     * 「自建的有界池」这个容器**没有了** ⇒ 它原先的触发点
     * （`HookCapabilityRuntime.onInvoke` 的 `catch (RejectedExecutionException)`）
     * **整块删除**。这一格「满了」的语义由 [uiQueueOverflowError] 继续承载。
     *
     * ⚠️ **但本函数保留、不删** —— `InvokePolicyTest` 有三处断言它，删掉会连带删测试。
     * 这是 `docs/fork/xposed-thread-modes-design.md` §9-2 那条旧债的落地方式
     *（「要么改成历史/防御并注明何时会复活，要么删」——这里选前者）。
     *
     * ⚠️ **它会在将来复活**：若某天选回有界队列/有界池，这一格就是它的错误构造。
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
     * **出队时**已超预算（**从未执行过**）→ `timeout`。
     *
     * 由 [HookCapabilityRuntime.runOnWorker] 在**出队后第一件事**判定：
     * 请求在队列里等待的时间已经吃满了预算 ⇒ 直接回失败，**绝不调用 handler**。
     *
     * ## ⚠️⚠️ 没有这个判据会发生什么（本函数存在的全部理由）
     *
     * ```
     * T+0     App 提交，App 侧超时开始计时
     * T+0s    入队（前面还有别的请求在跑）
     * T+5s    App 侧超时 → 工作流按错误策略继续/终止（用户看到「失败」）
     *         而那个任务【还在队列里】
     * T+30s   出队、执行 → 脚本真的跑了（改系统状态 / 开广播 / 开窗口）
     * T+30s+  结果回来 → App 侧无配对 waiter → 丢弃
     * ```
     *
     * ⇒ **用户看到「超时失败」，副作用却已经发生。** 对 `risk = HIGH` 的
     * `vflow.xposed.js` 不可接受。
     *
     * ## ⚠️ 为什么不复用 [timeoutError]
     *
     * 两者 `code` 相同（都是 `TIMEOUT`），但**文案必须不同**：
     *
     * | | 文案 | 问题 |
     * |---|---|---|
     * | [timeoutError] | 「耗时 Nms 超过预算」 | 本情形**耗时是 0、脚本根本没跑** ⇒ 会把排查引向「脚本为什么这么慢」 |
     * | **本函数** | 「等待 Nms…**因此未执行**」 | 直接告诉用户**没有发生副作用**（这正是本判据要给的信息） |
     *
     * ⚠️ **不新增错误码** —— `CapabilityErrorCode` 是五值枚举，有「每个码可映射到
     * 一个用户动作」的硬约束（见 `CapabilityErrorCode`）。这边 `timeout` 语义本就正确：
     * 用户侧被告知的就是「超时」。
     *
     * @param queuedMs 出队时刻减到达时刻 = 在队列里等了多久
     * @param budgetMs 有效预算（由 [effectiveTimeoutMs] 算出，调用点已确保非 null）
     */
    fun queuedExpiredError(queuedMs: Long, budgetMs: Long): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.TIMEOUT,
            detail = "执行超时：请求在队列中等待 ${queuedMs}ms，已超过预算 ${budgetMs}ms，因此未执行。",
        ),
    )

    /**
     * 载荷超上限 → `payload_too_large`。
     *
     * ⚠️ **不是用户能处理的失败** —— 正常路径下**不该出现这个码**
     * （§3.6 要求 hook 侧主动截断并带标志位）。出现即**实现缺陷或数据异常**
     * ⇒ 用户的处置是「报告问题」，**不要**把他引去改配置。
     *
     * ⚠️⚠️ **两个参数都是 parcel 字节**（不是 UTF-8 字节）——
     * 判据与用户看到的数字必须与实际传输同量纲，否则「报 26 万字节超了 26 万上限」
     * 这类自相矛盾的文案会让排查整个跑偏（2026-10-01 实测缺陷的直接产物）。
     *
     * 它兜的是 [ResultBudget.collectWithin] 的**单元素超限特例**
     *（那种情况下函数会**收下那个超限元素**，否则分页会死循环），
     * 以及「估算余量不足以覆盖真实转义开销」的情形。
     */
    fun payloadTooLargeError(actualParcelBytes: Int, maxParcelBytes: Int): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.PAYLOAD_TOO_LARGE,
            detail = "结果超出上限：实际 $actualParcelBytes 字节 > 上限 $maxParcelBytes 字节。" +
                "这通常是实现缺陷，请报告问题。",
        ),
    )

    /**
     * `ui` 档的**有界保护**上限。
     *
     * ⚠️ 为什么需要它：`Handler.post` **永不拒绝**（设计 §1.4 末表：「无界排队」），
     * 而 UI 档只有 1 个线程 —— 一个卡死的脚本会让队列无限堆积直到 OOM。
     * 而本进程是 **system_server**，OOM 的后果是**整机**。
     *
     * ## ⚠️⚠️ 这个数字的来源：**没有依据，就是拍的 —— 必须真机压测复评**
     *
     * **如实记录**（不粉饰）：
     *
     * | 问题 | 回答 |
     * |---|---|
     * | 256 是怎么来的？ | **拍板给出的**（父会话裁决「加，取 256」）。**不是**由任何实测、压测或公式推算得到 |
     * | 有依据吗？ | ❌ **没有**。既没有量过「真实工作流的最大并发 `ui` 调用数」，也没有量过「256 个待执行协程占多少内存」 |
     * | 那为什么不用别的数？ | 没有理由 —— 它选的是「明显大过任何真实并发」这个**方向**，具体数值是任意取的 |
     *
     * ⚠️ 本仓库对「拍数字」有**明确教训**：旧执行器的 `DEFAULT_POOL_SIZE = 2`
     * 就是拍出来的，曾长期挂在 `xposed-architecture-v2.md` §10 **#21** 复评未决
     * （2026-10-03 换成三档执行器后该常量已删除，容量由协程库与 `Looper` 决定）。
     * ⇒ **不要**把这个 256 当成经过论证的容量，它只是一个**暂定的安全阀**。
     *
     * ## 复评要求（与真机项对应）
     *
     * 必须真机压测后回来改这个注释与数值。至少要知道两件事：
     * 1. **真实并发**：正常使用下 `ui` 档同时在队的请求数上限是多少？
     *    （若远小于 256 ⇒ 说明选大了，但无碍；若接近/超过 ⇒ 必须调整）
     * 2. **卡死脚本的代价**：一个 `while(true)` 的 UI 脚本会让队列涨多快？排水速度是多少？
     *
     * ⚠️ **在压测之前，这个数字不得被引用为「已论证的容量」** ——
     * 也不能用它去反推别的档的容量。
     */
    const val MAX_UI_QUEUE = 256

    /**
     * `ui` 档待执行数超上限 → `handler_error`（与超时是**两个不同的排查方向**）。
     *
     * ## ⚠️⚠️ 为什么归 `handler_error` 而不是 `timeout`
     *
     * 口径见 `CapabilityErrorCode.kt`：`handler_error` 是「**立刻就知道做不了**」，
     * `timeout` 是「等了预算仍无结果」。本情形是前者 —— 请求**从未被投递执行**。
     * **不新增第六个码。**
     *
     * ⚠️ 它与 [poolExhaustedError] 是**同一格**（都归 `handler_error`、都是「满了」）
     * 的两个**形态**：那个是「自建有界池满了」，本函数是「UI 档无界排队到了安全阀」。
     * 三档执行器下前者**无生产调用点**（见其 KDoc），「满了归 `HANDLER_ERROR`」
     * 这条口径由本函数继续承载。
     *
     * @param queued 触发时的待执行数（含刚提交的这一个）
     */
    fun uiQueueOverflowError(queued: Int): CapabilityError = sanitize(
        CapabilityError(
            code = CapabilityErrorCode.HANDLER_ERROR,
            detail = "UI 执行档待处理请求过多（$queued 个）。请稍后重试 —— " +
                "通常是某个脚本占住 UI 线程过久。",
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
