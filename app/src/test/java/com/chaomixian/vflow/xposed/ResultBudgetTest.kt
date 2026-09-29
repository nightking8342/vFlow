package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.wire.ResultBudget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * §3.6 结果大小契约原语测试。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §3.6。
 *
 * 重点锁三处「改错了不报错、只静默变差」：
 * ① 字节口径（不是字符口径 —— 那是本仓库踩过的真实缺陷）；
 * ② 单元素超限必须**收下那一个**（否则分页死循环）；
 * ③ cursor 的语义（截断时指向「下次从哪开始」，未截断为 null）。
 */
class ResultBudgetTest {

    @Test
    fun `default budget is 256 KiB`() {
        // ⚠️ 远低于 oneway 的异步半缓冲（≈508 KiB，且那份空间与所有其他 oneway 事务共享）
        assertEquals(256 * 1024, ResultBudget.DEFAULT_MAX_RESULT_BYTES)
    }

    @Test
    fun `byteSizeOf counts utf8 bytes not chars`() {
        // ⚠️⚠️ 本文件最重要的一条：中文一个字 3 字节，而 .length 是 1
        assertEquals(3, ResultBudget.byteSizeOf("中"))
        assertEquals(3, ResultBudget.byteSizeOf("中").coerceAtMost(3))
        assertEquals("中文".toByteArray(Charsets.UTF_8).size, ResultBudget.byteSizeOf("中文"))
        assertEquals(6, ResultBudget.byteSizeOf("中文"))
        // ASCII 是 1:1
        assertEquals(5, ResultBudget.byteSizeOf("abcde"))
        // emoji 通常 4 字节
        assertEquals(4, ResultBudget.byteSizeOf("😀"))
        // 空串
        assertEquals(0, ResultBudget.byteSizeOf(""))
    }

    @Test
    fun `byteSizeOf would catch what char length misses`() {
        // 反向断言的具体形态：一段 CJK 文本，
        // 字符数是 n，字节数是 3n —— 若按字符判「没超」而按字节判「超了」，
        // 正是那个静默缺陷。
        val cjk = "中".repeat(1000)
        assertEquals(1000, cjk.length)
        assertEquals(3000, ResultBudget.byteSizeOf(cjk))
        // 用 2000 字节的上限：字符口径判「没超」，字节口径判「超了」
        assertTrue("字符口径会误判为没超", cjk.length <= 2000)
        assertTrue("字节口径才判得出来", ResultBudget.byteSizeOf(cjk) > 2000)
    }

    @Test
    fun `collectWithin collects until the budget is reached`() {
        val items = listOf("aa", "bb", "cc", "dd")   // 每项 2 字节
        val r = ResultBudget.collectWithin(items, maxBytes = 5, sizeOf = ResultBudget::byteSizeOf)

        // 收到第 3 项时是 6 > 5 ⇒ 只收 2 项
        assertEquals(listOf("aa", "bb"), r.items)
        assertTrue(r.truncated)
        assertEquals("cursor 指向没装下的那一个", 2, r.nextCursor)
    }

    @Test
    fun `collectWithin without truncation has null cursor`() {
        // ⚠️ §3.6 契约 4：未截断 ⇒ cursor 为 null（全量已取完）。
        // 给 0 或列表长度都会让调用方**继续翻页**，白做一轮。
        val items = listOf("aa", "bb")
        val r = ResultBudget.collectWithin(items, maxBytes = 100, sizeOf = ResultBudget::byteSizeOf)

        assertEquals(items, r.items)
        assertFalse(r.truncated)
        assertNull(r.nextCursor)
    }

    @Test
    fun `collectWithin resumes from startIndex`() {
        // 分页衔接：用上次的 nextCursor 当下次的 startIndex
        val items = listOf("aa", "bb", "cc", "dd", "ee")
        val first = ResultBudget.collectWithin(items, maxBytes = 5, sizeOf = ResultBudget::byteSizeOf)
        assertEquals(2, first.nextCursor)

        val second = ResultBudget.collectWithin(
            items, maxBytes = 5, sizeOf = ResultBudget::byteSizeOf, startIndex = first.nextCursor!!,
        )
        assertEquals(listOf("cc", "dd"), second.items)
        assertEquals(4, second.nextCursor)

        val third = ResultBudget.collectWithin(
            items, maxBytes = 5, sizeOf = ResultBudget::byteSizeOf, startIndex = second.nextCursor!!,
        )
        assertEquals(listOf("ee"), third.items)
        assertFalse("取完最后一页", third.truncated)
        assertNull(third.nextCursor)
    }

    @Test
    fun `a single oversized element is still collected so paging does not loop`() {
        // ⚠️⚠️ 这条锁的是**分页死循环**这一具体故障：
        // 若超大元素一个都不收，cursor 永远停在同一个位置 ⇒
        // 调用方拿着一样的 cursor 反复请求，每次都得到空结果。
        //
        // 收下它 + truncated=true 至少让调用方能推进，且它已经知道「这条不完整」。
        val items = listOf("x".repeat(100))
        val r = ResultBudget.collectWithin(items, maxBytes = 10, sizeOf = ResultBudget::byteSizeOf)

        assertEquals("超大的那一个也必须收下", 1, r.items.size)
        assertTrue(r.truncated)
        assertEquals("cursor 必须向前推进（否则死循环）", 1, r.nextCursor)

        // 副作用证明：拿这个 cursor 再取一次，会得到「没有了」而不是「又一条同样的」
        val next = ResultBudget.collectWithin(
            items, maxBytes = 10, sizeOf = ResultBudget::byteSizeOf, startIndex = r.nextCursor!!,
        )
        assertTrue(next.items.isEmpty())
        assertNull(next.nextCursor)
    }

    @Test
    fun `an element that exceeds the budget is not followed by more elements`() {
        // 收下超大元素后**立刻停**，不能把后面的也顺带塞进去
        val items = listOf("x".repeat(100), "y", "z")
        val r = ResultBudget.collectWithin(items, maxBytes = 10, sizeOf = ResultBudget::byteSizeOf)
        assertEquals(1, r.items.size)
        assertEquals(1, r.nextCursor)
    }

    @Test
    fun `empty input yields no truncation`() {
        val r = ResultBudget.collectWithin(emptyList<String>(), maxBytes = 10, sizeOf = ResultBudget::byteSizeOf)
        assertTrue(r.items.isEmpty())
        assertFalse(r.truncated)
        assertNull(r.nextCursor)
    }

    @Test
    fun `startIndex beyond the end yields an empty non-truncated page`() {
        val r = ResultBudget.collectWithin(
            listOf("a", "b"), maxBytes = 10, sizeOf = ResultBudget::byteSizeOf, startIndex = 5,
        )
        assertTrue(r.items.isEmpty())
        assertFalse(r.truncated)
        assertNull(r.nextCursor)
    }

    @Test
    fun `negative startIndex is clamped to zero`() {
        val r = ResultBudget.collectWithin(
            listOf("aa"), maxBytes = 10, sizeOf = ResultBudget::byteSizeOf, startIndex = -3,
        )
        assertEquals(listOf("aa"), r.items)
    }

    @Test
    fun `non positive budget still collects one element and does not hang`() {
        // 上限非法（0 / 负数）时不能变成「什么都不收且 cursor 不动」
        // —— 那又是死循环。钳到至少 1 之后，行为是「收一个然后停」。
        val zero = ResultBudget.collectWithin(
            listOf("a", "b"), maxBytes = 0, sizeOf = ResultBudget::byteSizeOf,
        )
        assertEquals(1, zero.items.size)
        assertTrue(zero.truncated)
        assertNotNull(zero.nextCursor)

        val negative = ResultBudget.collectWithin(
            listOf("a", "b"), maxBytes = -100, sizeOf = ResultBudget::byteSizeOf,
        )
        assertEquals(1, negative.items.size)
    }

    @Test
    fun `sizeOf failure collects the element and stops`() {
        // sizeOf 由调用方给出，可能抛（自定义对象的序列化）。
        // 抛了就当作「无法计量」⇒ 收下并停，绝不把异常抛出去
        //（调用方可能跑在 system_server 里，抛异常 = 整机）
        var calls = 0
        val r = ResultBudget.collectWithin(
            listOf("a", "b", "c"),
            maxBytes = 100,
            sizeOf = {
                calls++
                throw IllegalStateException("boom")
            },
        )
        assertEquals(1, r.items.size)
        assertTrue(r.truncated)
        assertEquals(1, r.nextCursor)
        assertEquals("抛出后必须立刻停止，不再试后面的", 1, calls)
    }

    @Test
    fun `budget is enforced in bytes for multibyte content`() {
        // ⚠️ 端到端的口径验证：30 个中文字 = 90 字节。
        // 用 100 字节的预算 ⇒ 全部装得下（若按字符误算成 30，也装得下，
        // 所以这里再收一次让它超：40 个字 = 120 字节 > 100）
        val items = List(40) { "中" }   // 每项 3 字节
        val r = ResultBudget.collectWithin(items, maxBytes = 100, sizeOf = ResultBudget::byteSizeOf)

        val used = r.items.sumOf { ResultBudget.byteSizeOf(it) }
        assertTrue("收下的内容不得超过预算", used <= 100)
        assertTrue("预算内应当收满（33 个 = 99 字节）", r.items.size >= 33)
        assertTrue(r.truncated)
    }
}
