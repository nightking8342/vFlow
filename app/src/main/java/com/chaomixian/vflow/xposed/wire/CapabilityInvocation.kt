package com.chaomixian.vflow.xposed.wire

import org.json.JSONObject

/**
 * ③ 能力调用的**请求 / 响应信封**编解码。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.3 / §3.4。
 *
 * ## ⚠️⚠️ 绝不复用 [EventEnvelope]
 *
 * 那条信封含 `seq` / `dropped`，语义是**事件侧的丢包检测**，与请求-响应**相反**：
 *
 * | | 事件信封 | 调用信封 |
 * |---|---|---|
 * | 配对 | `seq`（单调递增，用于**检测丢失**） | `request_id`（UUID，用于**配对响应**） |
 * | 丢一条 | 无所谓（下一跳不影响） | ❌ **调用方在等结果** |
 * | 方向 | 单向（hook → App） | **双向**（请求下行、响应上行） |
 *
 * 复用会让「丢包检测」与「配对」两套语义互相污染。
 *
 * ## ⚠️ 两个信封**都带 `token`**（§3.3 定案）
 *
 * 请求带 `token` 是自然的；**响应也必须带** ——
 * `resolve` 走的是同一个 App 侧 binder（`IHookHost`），
 * 若它没有凭证，**任何能 bind 到 `HookChannelService` 的进程都能伪造响应**，
 * 把任意 `result` 塞给正在等待的调用方。
 *
 * 这**比伪造事件更危险**：事件还要过 App 侧 filter 才触发，
 * 而 `resolve` 的 `result` **直接就是 capability 的返回值**。
 *
 * ## 依赖白名单
 *
 * 跑在 system_server 里，只允许 `org.json` / `java.*` / `kotlin.*`。
 * 由 `WireLayerPurityTest` 源码扫描锁住。
 */
object CapabilityInvocationCodec {

    // ── 键名。**一经发布不要改**（跨进程协议的一部分）──
    const val KEY_REQUEST_ID = "request_id"
    const val KEY_PROTOCOL = "protocol_version"
    const val KEY_CAPABILITY = "capability"
    const val KEY_PARAMS = "params"
    const val KEY_TIMEOUT_MS = "timeout_ms"
    const val KEY_TOKEN = "token"
    const val KEY_OK = "ok"
    const val KEY_RESULT = "result"
    const val KEY_ERROR = "error"
    const val KEY_ERROR_CODE = "code"
    const val KEY_ERROR_DETAIL = "detail"
    const val KEY_ELAPSED_MS = "elapsed_ms"
    /** §3.6 契约 4：分页游标（请求带 `cursor`）。 */
    const val KEY_CURSOR = "cursor"
    /** §3.6 契约 4：截断标志位（响应回 `next_cursor` / `truncated`）。 */
    const val KEY_NEXT_CURSOR = "next_cursor"
    const val KEY_TRUNCATED = "truncated"

    /** 缺省超时（§5.1 的默认值）。逐 capability 可覆盖（`Capability.timeoutMs`）。 */
    const val DEFAULT_TIMEOUT_MS = 5_000L

    /**
     * 编码请求。
     *
     * ⚠️ `protocolVersion` 的默认值**引用 [EventEnvelopeCodec.PROTOCOL_VERSION]** ——
     * 这就是 §5.5「版本号收敛成一处常量」的落实：加 ③ 之后版本会出现在**更多暴露点**
     * （③ 的请求/响应信封也要版本），而**暴露点会漂移，漂移的表现是「某条链路的行为莫名其妙」**。
     *
     * @param paramsJson `params` 的**原始 JSON 串**（不传 `Map`）——
     *   与 [EventEnvelope.payloadJson] 同一设计语言：信封层不认识任何业务字段
     *   （「Hook 层不知道工作流的存在」在数据层的体现）。
     *   传 `Map<String, Any>` 会在 org.json 转换中丢嵌套类型，而原始串不会。
     * @param timeoutMs `null` ⇒ **不写这个键 = 不超时**（2026-10-02 改）。
     *   ⚠️⚠️ **不能用 `0` 或负数表达「不超时」**：`0` 已被定义为「立刻超时」
     *   （见 `InvokePolicy.effectiveTimeoutMs` 的注释），而负数会在 hook 侧
     *   被 `isTimedOut` 判成恒真 ⇒ **每次调用立刻回 timeout**。
     *   用「键缺失」还有第二个好处：**旧 hook 层**（不认识这个语义）收到缺失会
     *   回落到它自己的默认值 ⇒ 安全降级，不会出现「新 App 让旧 hook 层永久挂起」。
     */
    fun encodeRequest(
        requestId: String,
        capability: String,
        paramsJson: String = "{}",
        timeoutMs: Long? = DEFAULT_TIMEOUT_MS,
        cursor: String? = null,
        token: String = "",
        protocolVersion: Int = EventEnvelopeCodec.PROTOCOL_VERSION,
    ): String {
        val obj = JSONObject()
            .put(KEY_REQUEST_ID, requestId)
            .put(KEY_PROTOCOL, protocolVersion)
            .put(KEY_CAPABILITY, capability)
            .put(KEY_PARAMS, paramsJson)
            .put(KEY_TOKEN, token)

        // ⚠️ 与 `cursor` 同一条约定：「不存在的键」比「值为 null 的键」更明确。
        if (timeoutMs != null) {
            obj.put(KEY_TIMEOUT_MS, timeoutMs)
        }

        // ⚠️ `cursor` 为空时**不写这个键**（而不是写 null）——
        // 与下面 `ok=true` 时不写 `error` 是同一条约定：
        // 「不存在的键」比「值为 null 的键」更明确，且旧端不需要认识它。
        //
        // ⚠️ 判空白而非只判 null：空串作游标是**无意义**的（分页语义里
        // 「从头开始」由「不传 cursor」表达），把它写出去会让 hook 侧要去区分
        // 「空串游标」与「没有游标」两种实为同义的情形。
        if (!cursor.isNullOrBlank()) {
            obj.put(KEY_CURSOR, cursor)
        }

        return obj.toString()
    }

    /**
     * 编码响应。
     *
     * ⚠️ **`ok=false` 时 `error` 必须非 null** —— 由 [decodeResponse] 反向校验。
     * 这里不做强制（`error` 允许为 null 是为了 `ok=true` 的路径），
     * 但**生产方必须遵守**：绝不静默（§3.3）。
     */
    fun encodeResponse(
        requestId: String,
        ok: Boolean,
        resultJson: String = "{}",
        error: CapabilityError? = null,
        elapsedMs: Long = 0L,
        nextCursor: String? = null,
        truncated: Boolean = false,
        token: String = "",
        protocolVersion: Int = EventEnvelopeCodec.PROTOCOL_VERSION,
    ): String {
        val obj = JSONObject()
            .put(KEY_REQUEST_ID, requestId)
            .put(KEY_PROTOCOL, protocolVersion)
            .put(KEY_OK, ok)
            .put(KEY_ELAPSED_MS, elapsedMs)
            .put(KEY_TOKEN, token)

        // ⚠️ 分页两键同为「有值才写」：
        // - `next_cursor` 为空 ⇒ 没有下一页（`ResultBudget.collectWithin` 未截断时返回 null）
        // - `truncated=false` ⇒ 没截断（§3.6 契约 3：截断**必须**带标志位，但「没截断」不必显式声明）
        //
        // 不写它们**不是**「省字节」—— 而是让「旧 hook 层回的信封」与
        // 「新 hook 层回了但没截断」在解码后落到**同一个默认值**，
        // 否则两者会在日志与断言里长得不一样，掩盖真实差异。
        if (!nextCursor.isNullOrBlank()) {
            obj.put(KEY_NEXT_CURSOR, nextCursor)
        }
        if (truncated) {
            obj.put(KEY_TRUNCATED, true)
        }

        if (ok) {
            obj.put(KEY_RESULT, resultJson)
            // ⚠️ ok=true 时不写 error 键（而非写 null）——
            // 「不存在的键」比「值为 null 的键」更明确，且与 org.json 的删键语义不冲突
        } else {
            // ⚠️ error 为 null 时给一个兜底的 code，而不是写 `"error":null`
            // —— 那会让 decodeResponse 返回 null（坏信封），把一次真实失败
            //    降级成「协议错误」，掩盖真正的原因
            val e = error ?: CapabilityError(
                code = CapabilityErrorCode.HANDLER_ERROR,
                detail = "调用方未提供 error，已按 handler_error 兜底",
            )
            obj.put(
                KEY_ERROR,
                JSONObject()
                    .put(KEY_ERROR_CODE, e.code.wire)
                    .put(KEY_ERROR_DETAIL, e.detail),
            )
        }

        return obj.toString()
    }

    /**
     * 解码请求。
     *
     * ⚠️ **任何形态的坏输入都返回 null，绝不抛异常** ——
     * 调用方是 **system_server** 侧，抛异常会危及整机（§5.1 的崩溃半径）。
     *
     * ⚠️ **未知 capability 不是坏输入** —— 它被原样解出来交给上层，
     * 由上层**显式报错**（§4.3：未知 capability 必须报错，与未知 topic 忽略相反）。
     * 本函数**不做**这个判断：它只管信封形状。
     */
    fun decodeRequest(json: String): CapabilityRequest? {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val requestId = obj.optString(KEY_REQUEST_ID)
        if (requestId.isBlank()) return null

        val capability = obj.optString(KEY_CAPABILITY)
        if (capability.isBlank()) return null

        // ⚠️⚠️ `timeout_ms` **缺失 ⇒ null = 不超时**（2026-10-02 改）。
        //
        // 此前缺失时回落 `DEFAULT_TIMEOUT_MS` —— 那会让「不超时」在跨进程时
        // **被静默改写成 5000ms**，而失败表现是「我的长脚本无缘无故被掐断」，
        // 没有任何线索指向真正的原因。
        //
        // ⚠️ **不能改用 0 或负数当哨兵**：`0` 已被定义为「立刻超时」
        //（见 `InvokePolicy.effectiveTimeoutMs` 的注释），而负数会让
        // `isTimedOut(elapsed, budget)` 恒真 ⇒ 每次调用立刻回 timeout。
        val timeout = if (obj.has(KEY_TIMEOUT_MS)) obj.optLong(KEY_TIMEOUT_MS, 0L) else null

        return CapabilityRequest(
            requestId = requestId,
            // ⚠️ 缺失时给 -1 而不是 0：0 是一个**合法的协议版本**，
            // 用 0 会让「缺失」与「版本 0」混同（照 EventEnvelopeCodec 的 seq 先例）
            protocolVersion = if (obj.has(KEY_PROTOCOL)) obj.optInt(KEY_PROTOCOL) else -1,
            capability = capability,
            paramsJson = obj.optString(KEY_PARAMS).ifBlank { "{}" },
            // ⚠️ 钳到非负：负超时会让工作线程的计时逻辑得到荒谬的结果
            // ⚠️ 但**保留 null** —— 它是「不超时」的唯一表达（见上）。
            timeoutMs = timeout?.coerceAtLeast(0L),
            // ⚠️ 缺省 null（不是空串）：`optString` 对缺失键返回 `""`，
            // 而空串与 null 在分页语义里是**两回事**（前者是「一个空的游标」，
            // 后者是「没有游标」）。归一成 null 让调用方只需判一种情形。
            cursor = obj.optString(KEY_CURSOR).takeIf { it.isNotBlank() },
            token = obj.optString(KEY_TOKEN),
        )
    }

    /**
     * 解码响应。
     *
     * ⚠️ 坏输入返回 null，**绝不抛**（调用方在 App 侧的 binder 线程上）。
     *
     * ⚠️⚠️ **`ok=false` 却没有 `error` ⇒ 返回 null（判为坏信封）**。
     * 这不是吹毛求疵：「失败但不知道原因」与「没收到响应」在排查上要区分开，
     * 而 §3.3 的硬要求就是「`ok=false` **必须带 error**，绝不静默」——
     * 放行一个没有原因的失败，等于让这条要求形同虚设。
     */
    fun decodeResponse(json: String): CapabilityResponse? {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val requestId = obj.optString(KEY_REQUEST_ID)
        if (requestId.isBlank()) return null

        // ⚠️ ok 缺失时按 false 处理：宁可当成失败（会走到下面的 error 校验），
        // 也不要默认成功 —— 一次「假成功」比一次显式失败难查得多
        val ok = obj.optBoolean(KEY_OK, false)

        val error: CapabilityError? = if (ok) {
            null
        } else {
            val errorObj = obj.optJSONObject(KEY_ERROR) ?: return null
            // ⚠️ 先判「有没有 code」：没有 code 的 error 是坏信封 ——
            // 上面那句「ok=false 必须带 error」在这里被真正强制
            val codeWire = errorObj.optString(KEY_ERROR_CODE)
            if (codeWire.isBlank()) return null

            // ⚠️ 未知错误码：**回落到 HANDLER_ERROR 而不是返回 null** ——
            // 返回 null 会把「新 hook 层引入的未知错误码」变成「坏信封」，
            // 而后者会走「协议错误」的提示路径，把排查引向错误方向。
            // 把原始串放进 detail 供人看（⚠️ detail 不参与任何判断）。
            CapabilityErrorCode.fromWire(codeWire)?.let { code ->
                CapabilityError(code = code, detail = errorObj.optString(KEY_ERROR_DETAIL))
            } ?: CapabilityError(
                code = CapabilityErrorCode.HANDLER_ERROR,
                detail = "未知错误码：$codeWire",
            )
        }

        return CapabilityResponse(
            requestId = requestId,
            ok = ok,
            resultJson = obj.optString(KEY_RESULT).ifBlank { "{}" },
            error = error,
            elapsedMs = obj.optLong(KEY_ELAPSED_MS),
            // ⚠️ 同 CapabilityRequest.cursor：缺失/空白都归一成 null，
            // 让调用方只需判一种情形（「没有下一页」）
            nextCursor = obj.optString(KEY_NEXT_CURSOR).takeIf { it.isNotBlank() },
            // ⚠️ 缺失 ⇒ false（向后兼容：旧 hook 层根本不写这个键）
            truncated = obj.optBoolean(KEY_TRUNCATED, false),
            token = obj.optString(KEY_TOKEN),
            protocolVersion = if (obj.has(KEY_PROTOCOL)) obj.optInt(KEY_PROTOCOL) else -1,
        )
    }
}

/**
 * 解码后的请求。
 *
 * @param paramsJson **原始 JSON 串** —— 每个 capability 的参数 schema 自己解析，
 *   信封层不认识任何业务字段
 * @param cursor §3.6 契约 4：分页游标。`null` ⇒ 从头开始（**不是**空串 ——
 *   两者在分页语义里是两回事，见 [CapabilityInvocationCodec.decodeRequest] 的说明）。
 *   ⚠️ **原样字符串，不在信封层解析成 Int** —— 契约里它只是「一个不透明游标」，
 *   `ResultBudget.collectWithin` 用 `Int` 下标只是那个原语的实现细节。
 *   用 `Int` 会把「将来换非整数游标」变成一次协议变更。
 */
data class CapabilityRequest(
    val requestId: String,
    val protocolVersion: Int,
    val capability: String,
    val paramsJson: String,
    /**
     * 超时预算（毫秒）。**`null` = 不超时**（协议里表现为**这个键不存在**）。
     *
     * ⚠️ 改动史（2026-10-02）：此前是非空 `Long`，缺失时回落 5000。
     * 现在契约改为「缺失 ⇒ 不超时」，与 `JsExecutor` 的 `null` 语义一致。
     * ⇒ **不要**为它加 `?: DEFAULT_TIMEOUT_MS` 之类的回落，那会把「不超时」
     * 静默改写成「5 秒超时」。
     */
    val timeoutMs: Long? = null,
    val cursor: String? = null,
    val token: String,
)

/**
 * 解码后的响应。
 *
 * ⚠️ `error` 与 `ok` 的关系由 [CapabilityInvocationCodec.decodeResponse] 保证：
 * `ok=false` ⇒ `error != null`；`ok=true` ⇒ `error == null`。
 *
 * ⚠️ **判断分支一律用 [ok] 与 `error.code`（枚举），绝不用 `error.detail`** ——
 * 后者是自由文本、会被三语本地化（§6.4 约束 2）。
 *
 * @param nextCursor §3.6 契约 4：下一页的游标。`null` ⇒ **全量已取完**。
 *   调用方把它原样回填到下一次请求的 `cursor`（不解析）。
 * @param truncated §3.6 契约 3：是否发生过截断。⚠️ **必须传给下游** ——
 *   少了几项时用户不能误以为「本来就没有」（照 `ActivityPayload.truncated` 的先例）。
 *   缺省 `false` 是为了向后兼容（旧 hook 层不写这个键）。
 */
data class CapabilityResponse(
    val requestId: String,
    val ok: Boolean,
    val resultJson: String,
    val error: CapabilityError?,
    val elapsedMs: Long,
    val nextCursor: String? = null,
    val truncated: Boolean = false,
    val token: String,
    val protocolVersion: Int,
)

/**
 * 失败原因。
 *
 * ⚠️⚠️ **[detail] 只给人看，绝不参与任何判断**（§6.4 约束 2）——
 * 它是自由文本，将来会被本地化（三语）。
 * `if (detail.contains("…"))` 这类写法等于埋一个「切语言就坏」的雷。
 * **要分支请用 [code]**。
 */
data class CapabilityError(
    val code: CapabilityErrorCode,
    val detail: String,
)
