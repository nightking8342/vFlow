package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [InvokePolicy] 的纯函数单测。
 *
 * ⚠️ 本层是「**改错了不报错、只静默变差**」的高发区（超时边界、字节口径、错误码归属），
 * 而 [HookCapabilityRuntime] 本身跑在 system_server 里测不到 ⇒ 单测是主要验证手段。
 */
class InvokePolicyTest {

    // ── effectiveTimeoutMs：取 min，**不钳位** ────────────────

    @Test
    fun `effectiveTimeoutUsesRequestedWhenNothingDeclared`() {
        assertEquals(5_000L, InvokePolicy.effectiveTimeoutMs(5_000L, null))
    }

    @Test
    fun `effectiveTimeoutTakesTheSmallerOfTheTwo`() {
        // App 侧的等待窗口就是请求里的 timeout。hook 侧若判得比它长，
        // 会得到「hook 层判成功、App 侧早已放弃」的噪声响应 ⇒ 必须取 min。
        assertEquals(1_000L, InvokePolicy.effectiveTimeoutMs(1_000L, 3_000L))
        assertEquals(1_000L, InvokePolicy.effectiveTimeoutMs(3_000L, 1_000L))
    }

    @Test
    fun `effectiveTimeoutEqualsWhenBothSame`() {
        assertEquals(2_000L, InvokePolicy.effectiveTimeoutMs(2_000L, 2_000L))
    }

    @Test
    fun `effectiveTimeoutDoesNotClampZero`() {
        // ⚠️⚠️ 这是**反证 #10 的落点**（见方案 §4.4）。
        // 钳位（coerceAtLeast(1)）会把「App 侧算错了超时」这类 bug
        // 掩盖成「handler 太慢」，又一条静默变差。0 就是 0。
        assertEquals(0L, InvokePolicy.effectiveTimeoutMs(0L, null))
    }

    @Test
    fun `effectiveTimeoutDoesNotClampNegative`() {
        // 负数原样生效 ⇒ `elapsedMs >= 0 > budget` ⇒ 立刻判超时（正确语义）
        assertEquals(-5L, InvokePolicy.effectiveTimeoutMs(-5L, null))
    }

    // ── effectiveMaxBytes：声明非法时回落默认值 ──────────────

    @Test
    fun `effectiveMaxBytesFallsBackToDefaultWhenNotDeclared`() {
        assertEquals(
            ResultBudget.DEFAULT_MAX_RESULT_BYTES,
            InvokePolicy.effectiveMaxBytes(null),
        )
    }

    @Test
    fun `effectiveMaxBytesFallsBackWhenDeclaredNonPositive`() {
        // ⚠️ 与 effectiveTimeoutMs **刻意不同口径**（那边不钳位）：
        // maxResultBytes 是**声明侧**的值，写 0/负数是笔误 ——
        // 当成「上限 0 字节」会让该 capability 永远返回空结果且不报错。
        assertEquals(ResultBudget.DEFAULT_MAX_RESULT_BYTES, InvokePolicy.effectiveMaxBytes(0))
        assertEquals(ResultBudget.DEFAULT_MAX_RESULT_BYTES, InvokePolicy.effectiveMaxBytes(-1))
    }

    @Test
    fun `effectiveMaxBytesHonoursPositiveDeclaration`() {
        assertEquals(64 * 1024, InvokePolicy.effectiveMaxBytes(64 * 1024))
        assertEquals(1, InvokePolicy.effectiveMaxBytes(1))
    }

    // ── isTimedOut：严格大于 ──────────────────────────────

    @Test
    fun `isTimedOutIsStrictlyGreater`() {
        // ⚠️ 恰好用满预算**不算**超时 —— 那是合法的成功执行。
        // 用 `>=` 会把「正好卡在预算上」的调用误判。
        assertFalse("恰好用满不算超时", InvokePolicy.isTimedOut(1_000L, 1_000L))
        assertTrue("多 1ms 就算超时", InvokePolicy.isTimedOut(1_001L, 1_000L))
        assertFalse(InvokePolicy.isTimedOut(0L, 0L))
        assertTrue("预算 0 ⇒ 只要耗了时间就超时", InvokePolicy.isTimedOut(1L, 0L))
    }

    @Test
    fun `isTimedOutWithNegativeBudgetIsAlwaysTrue`() {
        // 负数预算（未被钳位的透传值）⇒ 立刻超时，与「预算 0」同语义
        assertTrue(InvokePolicy.isTimedOut(0L, -1L))
    }

    // ── buildResultJson：**只有 items，不带分页键** ──────────

    @Test
    fun `buildResultJsonWrapsItemsOnly`() {
        val json = InvokePolicy.buildResultJson(listOf(mapOf("a" to 1, "b" to "x")))
        val obj = JSONObject(json)

        assertEquals(1, obj.getJSONArray(InvokePolicy.KEY_ITEMS).length())
        val first = obj.getJSONArray(InvokePolicy.KEY_ITEMS).getJSONObject(0)
        assertEquals(1, first.getInt("a"))
        assertEquals("x", first.getString("b"))

        // ⚠️⚠️ 分页两键**必须在信封顶层**（`CapabilityResponse`）。
        // 出现在这里 ⇒ App 侧的 Success.nextCursor / .truncated 恒为缺省值，
        // 而 codec 的 round-trip 测试**照样全绿**（它测编解码、不测「谁填的」）。
        assertFalse("result 内部不得出现 next_cursor", obj.has("next_cursor"))
        assertFalse("result 内部不得出现 truncated", obj.has("truncated"))
    }

    @Test
    fun `buildResultJsonWithEmptyItemsIsStillAValidArray`() {
        val obj = JSONObject(InvokePolicy.buildResultJson(emptyList()))
        assertEquals(0, obj.getJSONArray(InvokePolicy.KEY_ITEMS).length())
    }

    @Test
    fun `buildResultJsonSerialisesNestedObjectsNotStrings`() {
        // ⚠️ 元素是**对象**（不是预序列化的 JSON 串）⇒ 嵌套值应能被通用解码器读出，
        // 而不是变成 `"{\"k\":1}"` 这样的字符串。这决定 App 侧 toMap() 能否直接用。
        val json = InvokePolicy.buildResultJson(
            listOf(mapOf("nested" to mapOf("k" to 1))),
        )
        val nested = JSONObject(json)
            .getJSONArray(InvokePolicy.KEY_ITEMS)
            .getJSONObject(0)
            .getJSONObject("nested")
        assertEquals(1, nested.getInt("k"))
    }

    // ── itemByteCost：按**字节**而非字符 ─────────────────────

    @Test
    fun `itemByteCostCountsBytesNotChars`() {
        // ⚠️⚠️ 反证 #6 的落点：纯 ASCII 夹具测不出字节/字符口径的 bug。
        // 「中」在 BMP 内只占 1 个 Char 却是 3 字节 ⇒ 必须用它才能区分两种写法。
        val ascii = InvokePolicy.itemByteCost(mapOf("k" to "x"))
        val cjk = InvokePolicy.itemByteCost(mapOf("k" to "中"))
        assertTrue("CJK 元素的字节数必须大于同长度的 ASCII 元素", cjk > ascii)

        // 「中」3 字节 vs 「x」1 字节 ⇒ 差值恰好 2
        assertEquals(2, cjk - ascii)
    }

    @Test
    fun `itemByteCostCountsEmojiAsFourBytes`() {
        // ⚠️ emoji 在 BMP 之外、占 2 个 Char、编码 4 字节 ——
        // 「按代理 Char 逐个算」的写法会把它算成 2 字节（低估一半）
        val pad = InvokePolicy.itemByteCost(mapOf("k" to "x"))
        val emoji = InvokePolicy.itemByteCost(mapOf("k" to "😀"))
        assertEquals(3, emoji - pad) // 4 - 1
    }

    // ── 错误构造 ─────────────────────────────────────────

    @Test
    fun `unknownCapabilityErrorIsCapabilityAbsentAndNamesTheName`() {
        val e = InvokePolicy.unknownCapabilityError("nope", listOf("b", "a"))
        assertEquals(CapabilityErrorCode.CAPABILITY_ABSENT, e.code)
        assertTrue("detail 应含被问到的名字", e.detail.contains("nope"))
        assertTrue("detail 应含已注册列表（供人看）", e.detail.contains("a, b"))
    }

    @Test
    fun `unknownCapabilityErrorHandlesEmptyRegistry`() {
        val e = InvokePolicy.unknownCapabilityError("nope", emptyList())
        assertEquals(CapabilityErrorCode.CAPABILITY_ABSENT, e.code)
        // ⚠️ 不能拼出「当前已注册：」这样的半截串
        assertTrue(e.detail.contains("（空）"))
    }

    @Test
    fun `poolExhaustedErrorIsHandlerErrorWithReadableDetail`() {
        // ⚠️ 口径：池满归 HANDLER_ERROR，**不新增第六个码**
        val e = InvokePolicy.poolExhaustedError(2)
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, e.code)
        assertTrue("detail 必须写明「工作线程池已满」（需求硬要求）", e.detail.contains("工作线程池已满"))
        assertTrue(e.detail.contains("2"))
    }

    @Test
    fun `runtimeStoppedErrorIsDistinctFromPoolExhausted`() {
        // ⚠️⚠️ 两者 code 相同，但 detail **必须可区分** ——
        // 不区分会让「运行时已停」报成「池已满」，用户去查并发（排查方向整个错掉）
        val stopped = InvokePolicy.runtimeStoppedError()
        val pool = InvokePolicy.poolExhaustedError(2)

        assertEquals(CapabilityErrorCode.HANDLER_ERROR, stopped.code)
        assertTrue(stopped.detail.contains("已停止"))
        assertFalse("运行时已停**不得**说成池满", stopped.detail.contains("工作线程池已满"))
        assertFalse("池满**不得**说成已停止", pool.detail.contains("已停止"))
    }

    @Test
    fun `timeoutErrorIsTimeout`() {
        val e = InvokePolicy.timeoutError(1_250L, 1_000L)
        assertEquals(CapabilityErrorCode.TIMEOUT, e.code)
        assertTrue(e.detail.contains("1250"))
        assertTrue(e.detail.contains("1000"))
    }

    @Test
    fun `payloadTooLargeErrorIsItsOwnCode`() {
        val e = InvokePolicy.payloadTooLargeError(300 * 1024, 256 * 1024)
        assertEquals(CapabilityErrorCode.PAYLOAD_TOO_LARGE, e.code)
    }

    @Test
    fun `throwableToErrorIsHandlerErrorAndKeepsClassName`() {
        val e = InvokePolicy.throwableToError(IllegalStateException("boom"))
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, e.code)
        assertTrue(e.detail.contains("IllegalStateException"))
        assertTrue(e.detail.contains("boom"))
    }

    @Test
    fun `throwableToErrorHandlesNullMessage`() {
        // ⚠️ 拼成 `IllegalStateException: null` 会误导排查
        val e = InvokePolicy.throwableToError(IllegalStateException())
        assertTrue(e.detail.contains("IllegalStateException"))
        assertFalse("message 为 null 时不该拼出 `: null`", e.detail.contains("null"))
    }

    @Test
    fun `throwableToErrorCatchesErrorsNotJustExceptions`() {
        // ⚠️ 顶层兜的是 `Throwable`（含 `OutOfMemoryError` / `StackOverflowError`）——
        // 只 catch Exception 会让 Error 逃逸进 system_server
        val e = InvokePolicy.throwableToError(StackOverflowError("deep"))
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, e.code)
        assertTrue(e.detail.contains("StackOverflowError"))
    }

    // ── sanitize：detail 截断 ────────────────────────────

    @Test
    fun `sanitizeLeavesShortDetailUntouched`() {
        val e = InvokePolicy.throwableToError(IllegalStateException("short"))
        assertEquals(e, InvokePolicy.sanitize(e))
    }

    @Test
    fun `sanitizeTruncatesLongDetailWithinTheLimit`() {
        // ⚠️ 截断后的长度**必须真的落在上限内** —— 若先 take(上限) 再加后缀，
        // 结果仍会超限，而这条函数的全部意义就是让结果落在上限内。
        val long = "x".repeat(InvokePolicy.MAX_DETAIL_CHARS * 2)
        val e = InvokePolicy.sanitize(
            com.chaomixian.vflow.xposed.wire.CapabilityError(
                code = CapabilityErrorCode.HANDLER_ERROR,
                detail = long,
            ),
        )
        assertTrue(
            "截断后仍超限：${e.detail.length} > ${InvokePolicy.MAX_DETAIL_CHARS}",
            e.detail.length <= InvokePolicy.MAX_DETAIL_CHARS,
        )
        assertTrue("应留下截断标记", e.detail.endsWith("（已截断）"))
    }

    @Test
    fun `everyErrorConstructorReturnsDetailWithinTheLimit`() {
        // 体检：所有错误构造函数的 detail 都落在上限内（否则一条错误响应能撑爆 binder 缓冲）
        val errors = listOf(
            InvokePolicy.unknownCapabilityError("x".repeat(2_000), listOf("a")),
            InvokePolicy.poolExhaustedError(2),
            InvokePolicy.runtimeStoppedError(),
            InvokePolicy.timeoutError(1, 2),
            InvokePolicy.payloadTooLargeError(1, 2),
            InvokePolicy.throwableToError(IllegalStateException("y".repeat(2_000))),
        )
        errors.forEach { e ->
            assertTrue(
                "detail 超限（${e.detail.length}）：${e.detail.take(40)}…",
                e.detail.length <= InvokePolicy.MAX_DETAIL_CHARS,
            )
        }
    }
}
