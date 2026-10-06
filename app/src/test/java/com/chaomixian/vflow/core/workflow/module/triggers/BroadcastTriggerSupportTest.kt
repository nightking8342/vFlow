package com.chaomixian.vflow.core.workflow.module.triggers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广播触发器纯函数层的单测。
 *
 * ## ⚠️ 这些断言锁的都是「改错了不报错、只静默变差」的地方
 *
 * | 改错的方式 | 症状 | 有没有报错 |
 * |---|---|---|
 * | 归一化漏 trim | 用户配了 `" a "`，filter 里是带空格的 action | ❌ 永不触发，无日志 |
 * | 归一化不丢 `"*"` | `addAction("*")` 被平台接受但永不命中 | ❌ 同上 |
 * | scheme 做了小写化 | 大写 scheme 的广播被 filter 拦掉 | ❌ 同上 |
 * | 空判据不先 stripBlank | 全是空格的列表被判「非空」⇒ 注册一个空 filter | ❌ 同上 |
 *
 * 四条都**没有任何可观测点**（`IntentFilter` 那层没有日志），所以只能靠测试钉住。
 *
 * ⚠️ 关键用例已做**反证**（见 `FORK.md` 与本批交付说明）：
 * 把生产代码改回 bug 版本，确认对应用例变红。
 */
class BroadcastTriggerSupportTest {

    // ═══ stringListOf ═══

    @Test
    fun `stringListOf returns empty for null`() {
        assertEquals(emptyList<String>(), BroadcastTriggerSupport.stringListOf(null))
    }

    @Test
    fun `stringListOf returns empty for non-list types`() {
        // 数字 / 布尔等都不是列表 —— 宁可返回空（等价于「没配」），也不要瞎猜
        assertEquals(emptyList<String>(), BroadcastTriggerSupport.stringListOf(123))
        assertEquals(emptyList<String>(), BroadcastTriggerSupport.stringListOf(true))
        assertEquals(emptyList<String>(), BroadcastTriggerSupport.stringListOf(mapOf("a" to 1)))
    }

    @Test
    fun `stringListOf accepts a bare string`() {
        // 用户手工改 JSON / AI 写入时可能给单个字符串而不是数组
        assertEquals(listOf("a.b.C"), BroadcastTriggerSupport.stringListOf("a.b.C"))
        // 空串等价于「没配」
        assertEquals(emptyList<String>(), BroadcastTriggerSupport.stringListOf(""))
    }

    @Test
    fun `stringListOf drops non-string elements instead of stringifying them`() {
        // ⚠️⚠️ `toString()` 会造出一个**永远不会匹配**的 action（`123` ≠ 任何真实 action），
        // 而用户看到列表里那一项「长得没问题」⇒ 最难查的一类静默失效。
        // 宁可少一项，也不要造假项。
        val raw: List<Any?> = listOf("com.a.ACTION", 123, true, null, "com.b.ACTION")
        assertEquals(
            listOf("com.a.ACTION", "com.b.ACTION"),
            BroadcastTriggerSupport.stringListOf(raw),
        )
    }

    @Test
    fun `stringListOf tolerates a heterogeneous list`() {
        // JSON 导入时 List<*> 里混类型是常态，不能抛 —— 抛了会让整份导入失败
        val raw: List<Any?> = listOf(1, "x")
        assertEquals(listOf("x"), BroadcastTriggerSupport.stringListOf(raw))
    }

    // ═══ stripBlank ═══

    @Test
    fun `stripBlank trims and drops blanks`() {
        assertEquals(
            listOf("a", "b"),
            BroadcastTriggerSupport.stripBlank(listOf(" a ", "", "  ", "b")),
        )
    }

    @Test
    fun `stripBlank trims the ideographic space too`() {
        // ⚠️⚠️ 全角空格 U+3000：中文输入法下极易打进。
        // 用 `trim(' ')` 会漏掉它 ⇒ 保存出一个「看起来没填」的项，
        // 而它在 IntentFilter 里是个合法字面量（永不命中、无报错）。
        val ideographic = "　"
        assertEquals(
            listOf("a"),
            BroadcastTriggerSupport.stripBlank(listOf("${ideographic}a${ideographic}", ideographic)),
        )
    }

    @Test
    fun `stripBlank deduplicates preserving order`() {
        // 去重必须**保序** —— 顺序会影响摘要展示的「第一个」是哪一条
        assertEquals(
            listOf("b", "a"),
            BroadcastTriggerSupport.stripBlank(listOf("b", "a", "b", " a ")),
        )
    }

    @Test
    fun `stripBlank keeps the wildcard so validation can report it`() {
        // ⚠️⚠️ **反向锁**：stripBlank **不得**丢弃 `"*"`。
        // 在这里丢掉的话，用户在编辑器里输入 `"*"` 后点保存，
        // 那一项会**凭空消失**、既没报错也不知道为什么
        // —— 而正确的处置是让 validateActions 拦下并给出文案。
        assertEquals(
            listOf("*"),
            BroadcastTriggerSupport.stripBlank(listOf("*", "  *  ")),
        )
    }

    // ═══ normalizeActions ═══

    @Test
    fun `normalizeActions drops the wildcard`() {
        // 运行时兜底：编辑期漏网的（JSON 导入 / 直接改 prefs / 旧工作流）在这里拦住
        assertEquals(
            listOf("com.a.ACTION"),
            BroadcastTriggerSupport.normalizeActions(listOf("com.a.ACTION", "*")),
        )
    }

    @Test
    fun `normalizeActions drops blanks and duplicates`() {
        assertEquals(
            listOf("a", "b"),
            BroadcastTriggerSupport.normalizeActions(listOf(" a ", "", "a", "b", "  ")),
        )
    }

    @Test
    fun `normalizeActions can return an empty list`() {
        // ⚠️ 这个返回值是 Handler 的「不注册 receiver」判据（见 BroadcastTriggerHandler）。
        // 返回空 ⇒ 该触发器**不会注册任何 filter**，而不是注册一个空 filter。
        //
        // ⚠️ 空 filter **不是**「收全部」：平台层面它只匹配「没有 action 的 intent」，
        // 而 IntentResolver 的 action 索引里也没有它的 bucket ⇒ 等价于永不命中。
        // 「不注册」与「注册但永不命中」在这一维上等价，前者的好处是
        // **不白注册一个 EXPORTED receiver、不白接管一堆广播**（任意应用可发）。
        assertTrue(BroadcastTriggerSupport.normalizeActions(listOf("*", "", " ")).isEmpty())
        assertTrue(BroadcastTriggerSupport.normalizeActions(emptyList()).isEmpty())
    }

    @Test
    fun `normalizeActions keeps action case as-is`() {
        // action 也是精确比较（`mActions.contains(action)`），不做大小写折叠
        assertEquals(
            listOf("com.Foo.ACTION_Bar"),
            BroadcastTriggerSupport.normalizeActions(listOf("com.Foo.ACTION_Bar")),
        )
    }

    // ═══ normalizeSchemes ═══

    @Test
    fun `normalizeSchemes does not lowercase`() {
        // ⚠️⚠️ **反向锁**：`IntentFilter.matchData` 是 `schemes.contains(scheme)`
        // **精确比较、区分大小写**。把用户填的 `HTTP` 小写化成 `http` 之后，
        // 广播里带 `HTTP` 的那条就被 filter **拦在门外**了 ——
        // 归一化不但没帮忙，还制造了「明明配了却收不到」。
        assertEquals(
            listOf("HTTP", "content"),
            BroadcastTriggerSupport.normalizeSchemes(listOf("HTTP", "content")),
        )
    }

    @Test
    fun `normalizeSchemes strips blanks and drops the wildcard`() {
        assertEquals(
            listOf("package"),
            BroadcastTriggerSupport.normalizeSchemes(listOf(" package ", "", "*")),
        )
    }

    @Test
    fun `normalizeSchemes deduplicates preserving order`() {
        assertEquals(
            listOf("content", "file"),
            BroadcastTriggerSupport.normalizeSchemes(listOf("content", "file", "content")),
        )
    }

    // ═══ normalizeCategories ═══

    @Test
    fun `normalizeCategories strips blanks and drops the wildcard`() {
        assertEquals(
            listOf("android.intent.category.DEFAULT"),
            BroadcastTriggerSupport.normalizeCategories(
                listOf(" android.intent.category.DEFAULT ", "", "*")
            ),
        )
    }

    @Test
    fun `normalizeCategories does not lowercase`() {
        // matchCategories 同样是精确比较
        assertEquals(
            listOf("CAT_UPPER"),
            BroadcastTriggerSupport.normalizeCategories(listOf("CAT_UPPER")),
        )
    }

    // ═══ validateActions ═══

    @Test
    fun `validateActions reports empty for empty and blank-only input`() {
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.EMPTY,
            BroadcastTriggerSupport.validateActions(emptyList()),
        )
        // ⚠️⚠️ 关键在于**先 stripBlank 再判空**：直接用 `raw.isEmpty()` 的话，
        // 「用户敲了几个空格」会被判成「已填写」⇒ 保存成功 ⇒ 运行时注册一个空 filter
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.EMPTY,
            BroadcastTriggerSupport.validateActions(listOf("", "   ", "　")),
        )
    }

    @Test
    fun `validateActions reports wildcard when any item is a wildcard`() {
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.WILDCARD,
            BroadcastTriggerSupport.validateActions(listOf("*")),
        )
        // ⚠️ 混着合法项也**必须**报 WILDCARD：用户填 `"*"` 的意图是「通配」，
        // 而平台做不到 —— 静默丢掉 `"*"` 只监听剩下的那一项，
        // 会让他以为「通配生效了、只是恰好只有这一个 action 触发」。
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.WILDCARD,
            BroadcastTriggerSupport.validateActions(listOf("*", "com.a.ACTION")),
        )
        // 带空白的 `*` 也算（stripBlank 先跑）
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.WILDCARD,
            BroadcastTriggerSupport.validateActions(listOf("  *  ")),
        )
    }

    @Test
    fun `validateActions accepts normal input`() {
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.OK,
            BroadcastTriggerSupport.validateActions(listOf("com.a.ACTION")),
        )
        assertEquals(
            BroadcastTriggerSupport.ActionsValidation.OK,
            BroadcastTriggerSupport.validateActions(
                listOf("android.intent.action.MEDIA_MOUNTED", " com.b.ACTION ")
            ),
        )
    }

    @Test
    fun `wildcard constant matches the literal the platform cannot honor`() {
        // 常量本身被人改掉（比如改成空串）会让上面全部断言失去意义
        assertEquals("*", BroadcastTriggerSupport.WILDCARD)
    }
}
