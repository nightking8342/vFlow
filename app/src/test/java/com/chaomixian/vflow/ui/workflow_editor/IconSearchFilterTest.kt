package com.chaomixian.vflow.ui.workflow_editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `IconSearchFilter` 的行为锁定。
 *
 * ⚠️ 重点在**三条"改错了不报错、只静默变差"的规则**：
 * 前缀剥离、连字符等价、词首匹配。它们任意一条写错，表现都不是崩溃，
 * 而是「用户照着官网上抄的名字搜不到」—— 用户会以为图标库里没有那个图标，
 * 然后去别处找，永远发现不了是搜索的问题。
 */
class IconSearchFilterTest {

    // ---- officialNameOf：资源名 → 官方名 ----

    @Test
    fun `strips rounded prefix and line suffix`() {
        assertEquals("play arrow", IconSearchFilter.officialNameOf("rounded_play_arrow_24"))
    }

    @Test
    fun `strips fill suffix before line suffix`() {
        // ⚠️ 顺序反了会把 `xxx_fill` 当成图标名（`rounded_download_fill_24`
        //    会变成 "download fill" 而不是 "download"）。
        assertEquals("download", IconSearchFilter.officialNameOf("rounded_download_fill_24"))
    }

    @Test
    fun `keeps multi word names intact`() {
        assertEquals(
            "arrow back ios new",
            IconSearchFilter.officialNameOf("rounded_arrow_back_ios_new_24")
        )
    }

    @Test
    fun `name with digits is preserved`() {
        assertEquals("10k", IconSearchFilter.officialNameOf("rounded_10k_24"))
        assertEquals("4k plus", IconSearchFilter.officialNameOf("rounded_4k_plus_fill_24"))
    }

    // ---- matches：三条规则 ----

    @Test
    fun `empty query matches everything`() {
        // ⚠️ 空查询必须**不过滤**（清空搜索框看到全量列表），
        //    而不是"什么都不显示"。
        assertTrue(IconSearchFilter.matches("rounded_play_arrow_24", ""))
        assertTrue(IconSearchFilter.matches("rounded_play_arrow_24", "   "))
    }

    @Test
    fun `hyphen in query is equivalent to space`() {
        // 官方名字用连字符（play-arrow），用户会照抄。
        assertTrue(IconSearchFilter.matches("rounded_play_arrow_24", "play-arrow"))
        assertTrue(IconSearchFilter.matches("rounded_play_arrow_24", "play arrow"))
    }

    @Test
    fun `query is case insensitive`() {
        assertTrue(IconSearchFilter.matches("rounded_wifi_tethering_24", "WIFI"))
        assertTrue(IconSearchFilter.matches("rounded_wifi_tethering_24", "Wifi"))
    }

    @Test
    fun `prefix of a word matches`() {
        assertTrue(IconSearchFilter.matches("rounded_wifi_tethering_24", "wif"))
        // 词首匹配：`back` 命中 `arrow_back_ios_new`
        assertTrue(IconSearchFilter.matches("rounded_arrow_back_ios_new_24", "back"))
    }

    @Test
    fun `does not match from the middle of a word`() {
        // ⚠️ 反向锁：`ether` 是 `tethering` 的中段，不该命中 ——
        //    允许中段匹配会让"搜 X 出来一堆无关项"。
        assertFalse(IconSearchFilter.matches("rounded_wifi_tethering_24", "ether"))
    }

    @Test
    fun `prefix text itself does not match`() {
        // ⚠️ 反向锁：`rounded_` 若参与匹配，搜 "round" 会得到全部 8310 项
        //    （等于搜索失效）。
        assertFalse(IconSearchFilter.matches("rounded_play_arrow_24", "round"))
    }

    @Test
    fun `fill and line variants both match the same query`() {
        assertTrue(IconSearchFilter.matches("rounded_home_24", "home"))
        assertTrue(IconSearchFilter.matches("rounded_home_fill_24", "home"))
    }

    // ---- filter ----

    @Test
    fun `filter returns the same instance for blank query`() {
        // ⚠️ 8310 项的 filter 每次都新建列表，而清空搜索框是高频动作。
        val icons = listOf("rounded_a_24", "rounded_b_24")
        assertTrue(IconSearchFilter.filter(icons, "  ") === icons)
        assertTrue(IconSearchFilter.filter(icons, "") === icons)
    }

    @Test
    fun `filter narrows to matching entries`() {
        val icons = listOf(
            "rounded_home_24",
            "rounded_home_fill_24",
            "rounded_home_work_24",
            "rounded_settings_24",
        )
        assertEquals(
            listOf("rounded_home_24", "rounded_home_fill_24", "rounded_home_work_24"),
            IconSearchFilter.filter(icons, "home")
        )
    }

    @Test
    fun `filter is not vacuous on a realistic sized list`() {
        // 防空转：确认上面的用例不是"因为列表本来就只有几项"才通过
        val big = List(4150) { "rounded_icon$it" + "_24" } + listOf("rounded_home_24")
        assertEquals(1, IconSearchFilter.filter(big, "home").size)
    }
}
