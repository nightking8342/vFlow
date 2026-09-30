package com.chaomixian.vflow.xposed.capabilities

import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.CapabilityRequest
import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [DiagnosticCapabilityHandler] 的四条 mode 单测。
 *
 * ⚠️ 本能力是「四条失败路径能否走通」的**唯一可控探针** ——
 * 它自己出错会让那种可验证性整个消失，且**静默**（测试会以为验过了）。
 */
class DiagnosticCapabilityHandlerTest {

    private val handler = DiagnosticCapabilityHandler()

    private fun request(params: String, timeoutMs: Long = 5_000L) = CapabilityRequest(
        requestId = "r-1",
        protocolVersion = 1,
        capability = CapabilityNames.DIAGNOSTIC,
        paramsJson = params,
        timeoutMs = timeoutMs,
        token = "t",
    )

    @Test
    fun `name comes from CapabilityNames constant`() {
        // ⚠️ 必须引用常量而不是写字面量：名字是跨进程协议的一部分，
        // 拼错的表现是「这能力明明装了却说没有」（两端各自看都没问题）
        assertEquals(CapabilityNames.DIAGNOSTIC, handler.name)
    }

    @Test
    fun `both limits are null so both ends use defaults`() {
        // ⚠️ 两端都留 null ⇒ 不会出现「App 以为 5s、hook 按 3s 算」的错配
        assertNull("maxResultBytes 留 null ⇒ 用 ResultBudget 默认值", handler.maxResultBytes)
        assertNull("timeoutMs 留 null ⇒ 用请求里的 timeout_ms", handler.timeoutMs)
    }

    @Test
    fun `mode ok returns a single item`() {
        val out = handler.handle(request("""{"mode":"ok"}""")) as CapabilityOutcome.Items
        assertEquals(1, out.items.size)
        assertEquals("ok", out.items[0]["mode"])
        assertEquals(0, out.startIndex)
    }

    @Test
    fun `missing mode defaults to ok`() {
        // 「什么都没传」等价于走 ok 路径是最合理的解释
        assertTrue(handler.handle(request("{}")) is CapabilityOutcome.Items)
        assertTrue(handler.handle(request("""{"other":1}""")) is CapabilityOutcome.Items)
    }

    @Test
    fun `malformed params defaults to ok`() {
        // ⚠️ 坏 params 不当成错误：这是诊断能力，抛异常反而掩盖真实意图
        assertTrue(handler.handle(request("not json")) is CapabilityOutcome.Items)
        assertTrue(handler.handle(request("")) is CapabilityOutcome.Items)
    }

    @Test
    fun `mode slow sleeps beyond the requested budget`() {
        val start = System.nanoTime()
        val out = handler.handle(request("""{"mode":"slow"}""", timeoutMs = 100L))
        val elapsed = (System.nanoTime() - start) / 1_000_000L

        assertTrue("必须是 Items（睡眠本身不算失败）", out is CapabilityOutcome.Items)
        // ⚠️ 关键：必须**超过** budget，否则运行时的事后判定不会判超时，
        // 这条诊断能力就给出与预期相反的结果
        assertTrue(
            "slow 只睡了 ${elapsed}ms，未超过预算 100ms ⇒ 不会触发超时判定",
            elapsed > 100L,
        )
    }

    @Test
    fun `mode slow adapts to a tiny budget`() {
        // ⚠️ 用 `request.timeoutMs + 常量` 而不是写死一个数：
        // 调用方把 timeout 设小了（如 1ms）时，写死的睡眠可能反而**不超时**
        val start = System.nanoTime()
        handler.handle(request("""{"mode":"slow"}""", timeoutMs = 1L))
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        assertTrue(elapsed > 1L)
    }

    @Test
    fun `mode throw throws so the runtime top-level catch is exercised`() {
        val t = try {
            handler.handle(request("""{"mode":"throw"}"""))
            null
        } catch (e: Throwable) {
            e
        }
        assertNotNull("throw 模式必须真的抛（否则验不到顶层兜底）", t)
        assertTrue(t is IllegalStateException)
        assertTrue(t!!.message!!.contains("故意抛出"))
    }

    @Test
    fun `mode huge produces far more bytes than the default limit`() {
        val out = handler.handle(request("""{"mode":"huge"}""")) as CapabilityOutcome.Items
        assertEquals(DiagnosticCapabilityHandler.HUGE_ITEM_COUNT, out.items.size)

        // ⚠️ 全量必须**明确超过**默认上限 ⇒ 截断是**必然**发生的，
        // 而不是取决于余量取值的巧合
        val totalBytes = out.items.sumOf { InvokePolicy.itemByteCost(it) }
        assertTrue(
            "huge 全量只有 $totalBytes 字节，未超过默认上限 ${ResultBudget.DEFAULT_MAX_RESULT_BYTES} ⇒ 测不到截断",
            totalBytes > ResultBudget.DEFAULT_MAX_RESULT_BYTES,
        )
    }

    @Test
    fun `huge items are pure ascii so the escape overhead is zero`() {
        // ⚠️⚠️ 这条锁的是**夹具的可断言性**（不是功能）：
        // pad 用纯 ASCII 的 `x` ⇒ 转义开销为 0 ⇒ 只有约 1210 个数组逗号
        // 需要余量覆盖 ⇒ 「截断成功」是**稳定可断言**的结果。
        //
        // 若有人把 pad 改成引号/换行（转义会吃掉余量），测试会落在
        // `payload_too_large` 上 —— 与「截断成功」的期望**相反**。
        val item = DiagnosticCapabilityHandler.hugeItem(0)
        val pad = item["pad"] as String
        assertTrue("pad 必须是纯 ASCII", pad.all { it.code < 128 })
        assertEquals(DiagnosticCapabilityHandler.HUGE_PAD_CHARS, pad.length)
    }

    @Test
    fun `huge honours the cursor from params`() {
        // ⚠️ 分页切片是 **handler 的活**（框架只做字节兜底、不替它切片）
        val out = handler.handle(request("""{"mode":"huge","cursor":"100"}"""))
            as CapabilityOutcome.Items
        assertEquals(100, out.startIndex)
    }

    @Test
    fun `huge treats a bad cursor as the beginning`() {
        listOf("""{"mode":"huge","cursor":"abc"}""", """{"mode":"huge","cursor":""}""")
            .forEach { params ->
                val out = handler.handle(request(params)) as CapabilityOutcome.Items
                assertEquals("坏 cursor 应等价于从头开始：$params", 0, out.startIndex)
            }
    }

    @Test
    fun `unknown mode is an explicit failure not silence`() {
        // ⚠️ 静默当成 ok 会让「参数拼错了」看起来像「功能正常」
        val out = handler.handle(request("""{"mode":"bogus"}"""))
        assertTrue(out is CapabilityOutcome.Failure)
        out as CapabilityOutcome.Failure
        assertEquals(CapabilityErrorCode.HANDLER_ERROR, out.code)
        assertTrue("detail 应含那个未知的 mode 串", out.detail.contains("bogus"))
    }

    @Test
    fun `modeOf is a pure function over the params json`() {
        assertEquals("ok", DiagnosticCapabilityHandler.modeOf("""{"mode":"ok"}"""))
        assertEquals("slow", DiagnosticCapabilityHandler.modeOf("""{"mode":"slow"}"""))
        assertEquals("ok", DiagnosticCapabilityHandler.modeOf("{}"))
        assertEquals("ok", DiagnosticCapabilityHandler.modeOf("garbage"))
        assertEquals("ok", DiagnosticCapabilityHandler.modeOf("""{"mode":""}"""))
    }
}
