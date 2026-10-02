package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import org.json.JSONObject

/**
 * **诊断能力**：用 `params.mode` 显式选择要走哪条失败路径，用于端到端自证。
 *
 * ## ⚠️ 它为什么存在（不是为了「方便调试」，是为了**可验证性**）
 *
 * 池满 / 超时 / 截断这三条路径用真实 capability **很难稳定触发**
 * —— 要凑并发、要造超大数据。而失败路径出错时**往往静默**
 * （回一个错误的码、或干脆不回响应，让调用方白等到超时）。
 *
 * 有了它，「四条路径能不能走通」这件事从「等真实场景」变成「一次调用」。
 *
 * | `mode` | 行为 | 验证的路径 |
 * |---|---|---|
 * | `ok` | 正常返回一个元素 | 正常路径 |
 * | `slow` | 睡**超过**请求的 `timeout_ms` 再返回 | **超时**（`elapsedMs > budget ⇒ timeout`） |
 * | `throw` | 抛 `IllegalStateException` | **异常转响应**（工作线程顶层兜底） |
 * | `huge` | 返回 [HUGE_ITEM_COUNT] 个元素（远超默认上限） | **截断 + 分页** |
 *
 * 未知 `mode` 走 [CapabilityOutcome.Failure]（`handler_error` + 说明）——
 * **不静默、不猜**。静默地当成 `ok` 会让「参数拼错了」看起来像「功能正常」。
 *
 * ## ⚠️ 风险等级是 `READ_ONLY`（App 侧声明）
 *
 * 无副作用：`slow` 只 sleep、`huge` 只造数据、`throw` 只抛异常。
 * 故它留在**生产包**里是安全的 —— 刻意**不做**「debug 构建才有」的条件编译，
 * 因为那会让真机验证必须在 debug 包上做，而本项目交付**一律用 release**。
 */
class DiagnosticCapabilityHandler : CapabilityHandler {

    companion object {
        /**
         * `huge` 模式的元素个数。
         *
         * ⚠️ 取 2000 是为了让**全量**（≈426 KiB）明确超过默认上限 256 KiB ——
         * 这样「截断」是**必然**发生的，而不是取决于余量取值的巧合。
         */
        const val HUGE_ITEM_COUNT = 2000

        /**
         * `huge` 元素里 `pad` 字段的字符数（单元素 ≈ 213 字节）。
         *
         * ⚠️⚠️ **`pad` 用纯 ASCII 的 `x` 是有意的**：它是**不会被 JSON 转义**的字符，
         * 于是转义开销为 0、只剩约 1210 个数组逗号（≈1.2 KiB）需要
         * [HookCapabilityRuntime.RESULT_ENVELOPE_MARGIN_BYTES] 覆盖。
         *
         * ⇒ 这条夹具让「截断成功」成为**稳定可断言**的结果，而不是
         * **对余量取值敏感**的结果。若改用引号/换行做 pad，转义会吃掉余量，
         * 测试就会落在 `payload_too_large` 上 —— 与「截断成功」的期望相反。
         */
        const val HUGE_PAD_CHARS = 192

        const val MODE_OK = "ok"
        const val MODE_SLOW = "slow"
        const val MODE_THROW = "throw"
        const val MODE_HUGE = "huge"

        /**
         * `slow` 模式额外多睡的时间（超出预算的部分）。
         *
         * ⚠️ 必须**明确大于 0** 且足够大，否则 `elapsedMs` 会因计时精度
         * 落在预算之内 ⇒ 测试 flaky。250ms 对「严格大于」的判定是安全的。
         */
        const val SLOW_OVERSHOOT_MS = 250L

        /**
         * `slow` 模式在**请求没给超时**（`timeout_ms` 缺失 = 不超时）时的兜底睡眠。
         *
         * ⚠️ 存在的理由：`null` 意味着「没有预算可超」⇒ 超时判定不会命中。
         * 若把 `null` 传播下去，这个模式会变成**永久挂起**（诊断能力反而成了死点）。
         * 取 1 秒：足够让调用方观察到「慢」，又不会真的卡住验证流程。
         */
        const val DIAGNOSTIC_SLOW_FALLBACK_MS = 1_000L

        /** 造一个 `huge` 元素（单元素 ≈213 字节）。 */
        fun hugeItem(index: Int): Map<String, Any?> =
            mapOf("i" to index, "pad" to "x".repeat(HUGE_PAD_CHARS))

        /** 解析 `params` 里的 `mode`（**纯函数，可单测**）。坏 JSON / 缺键 ⇒ [MODE_OK]。 */
        fun modeOf(paramsJson: String): String = try {
            JSONObject(paramsJson).optString("mode").ifBlank { MODE_OK }
        } catch (_: Throwable) {
            // ⚠️ 坏 params 不当成错误：本能力是诊断用的，
            // 「什么都没传」等价于「走 ok 路径」是最合理的解释。
            MODE_OK
        }
    }

    override val name: String = com.chaomixian.vflow.xposed.capability.CapabilityNames.DIAGNOSTIC

    // maxResultBytes / timeoutMs 留 null：两端都取默认值
    // ⇒ 不会出现「App 以为 5s、hook 按 3s 算」的错配。

    override fun handle(request: CapabilityRequest): CapabilityOutcome = when (val mode = modeOf(request.paramsJson)) {
        MODE_OK -> CapabilityOutcome.Items(listOf(mapOf("mode" to MODE_OK)))

        MODE_SLOW -> {
            // ⚠️ 睡**超过**请求给的时间预算 —— 这样工作线程跑完后算出的
            // elapsedMs 一定 > budget ⇒ 触发超时判定（判定是**事后**的，见运行时注释）。
            //
            // ⚠️ 用 `request.timeoutMs + SLOW_OVERSHOOT_MS` 而不是写死一个数：
            // 调用方把 timeout 设小了（如 1ms）时，写死的睡眠可能反而**不超时**，
            // 于是这个诊断能力会给出与预期相反的结果。
            //
            // ⚠️⚠️ `timeoutMs == null`（= 不超时）时**没有预算可超** ⇒
            // 这个模式无意义。取一个有限的固定值，让调用方**必然**拿到
            // `handler_error`（超时判定不会命中，但调用方自己会超时/取消），
            // 而不是让 `null` 传播成「永远睡下去」—— 那会把诊断能力变成挂死点。
            sleepQuietly((request.timeoutMs ?: DIAGNOSTIC_SLOW_FALLBACK_MS) + SLOW_OVERSHOOT_MS)
            CapabilityOutcome.Items(listOf(mapOf("mode" to MODE_SLOW)))
        }

        MODE_THROW -> throw IllegalStateException("diagnostic: 故意抛出的异常")

        MODE_HUGE -> CapabilityOutcome.Items(
            items = (0 until HUGE_ITEM_COUNT).map { hugeItem(it) },
            // 分页起点由**请求**给（框架只做字节兜底，不替 handler 切片）
            startIndex = cursorOf(request.paramsJson),
        )

        else -> CapabilityOutcome.Failure(
            code = CapabilityErrorCode.HANDLER_ERROR,
            detail = "未知的 mode：\"$mode\"（支持 $MODE_OK / $MODE_SLOW / $MODE_THROW / $MODE_HUGE）",
        )
    }

    /**
     * 从 `params.cursor` 取分页起点。
     *
     * ⚠️ 协议里 `cursor` 是**不透明串**，但本能力自己知道它是下标 ——
     * 「怎么解释 cursor」是 **capability 自己的事**，框架不管。
     * 解析失败（不是数字 / 越界）按 0 处理，与「从头开始」同义。
     */
    private fun cursorOf(paramsJson: String): Int = try {
        JSONObject(paramsJson).optString("cursor").toIntOrNull()?.coerceAtLeast(0) ?: 0
    } catch (_: Throwable) {
        0
    }

    /**
     * 睡觉，**不把 `InterruptedException` 抛出去**。
     *
     * ⚠️ 两点：
     * ① 抛出去会被顶层兜成 `handler_error`，而 `stop()` 时的 interrupt 是
     *   **正常**的停止流程 —— 把它报成「handler 出错」会误导排查；
     * ② 被中断时必须**恢复中断标志**（`interrupt()`），否则上层再也看不见它。
     *   但恢复标志会让后续的 sleep 立刻返回 —— 这里的行为正是「尽快收尾」。
     */
    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
