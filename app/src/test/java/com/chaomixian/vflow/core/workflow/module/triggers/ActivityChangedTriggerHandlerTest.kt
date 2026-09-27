package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.ActivityChangedTriggerHandler
import com.chaomixian.vflow.xposed.wire.ActivityEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `activity_changed` 触发器的匹配与冷却语义。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.1。
 *
 * ⚠️ 这些测试锁的是**「改错了不报错、只静默变差」**的地方：
 * 匹配写松了 = 错误触发（用户的工作流被莫名其妙跑起来）；
 * 写紧了 = 永远不触发。两者都不报错。
 *
 * ⚠️ 不覆盖 `executeTrigger`（它需要 Android Context 与工作流引擎）——
 * 所以本测试只测可纯函数化的部分，并把它们开成 `internal` 供测试调用。
 */
class ActivityChangedTriggerHandlerTest {

    private val handler = ActivityChangedTriggerHandler()

    private fun event(
        pkg: String = "com.foo",
        cls: String = "com.foo.MainActivity",
    ) = ActivityEvent(
        packageName = pkg,
        className = cls,
        component = "$pkg/$cls",
        intentUri = "intent:#Intent;end",
        extrasJson = "{}",
        truncated = false,
    )

    /**
     * 构造一个 TriggerSpec。
     *
     * ⚠️ `TriggerSpec` 是 `(workflow, step)` 派生出来的（triggerId = "workflowId:stepId"），
     * 不能直接 new —— 所以这里建最小的 Workflow + ActionStep。
     */
    private fun trigger(
        params: Map<String, Any?> = emptyMap(),
        stepId: String = "step_1",
        workflowId: String = "w1",
    ): TriggerSpec {
        val step = ActionStep(
            id = stepId,
            moduleId = "vflow.trigger.activity_changed",
            parameters = params,
        )
        val workflow = Workflow(
            id = workflowId,
            name = "测试工作流",
            steps = listOf(step),
        )
        return TriggerSpec(workflow, step)
    }

    // ── 匹配语义 ──────────────────────────────────────────────

    @Test
    fun `blank filters match everything`() {
        // ⚠️⚠️ 空参数必须是「任意」，不能是「都不匹配」。
        // 反过来会让「新建触发器还没填条件」表现为「配了但永不触发」，
        // 用户会以为是环境问题
        val t = trigger(mapOf("package_filter" to "", "class_filter" to ""))
        assertTrue(handler.matches(t, event()))
        assertTrue(handler.matches(t, event(pkg = "com.any", cls = "X.Y")))
    }

    @Test
    fun `missing params match everything`() {
        // 参数键完全不存在时同理 —— 不能因为「读不到」就判 false
        assertTrue(handler.matches(trigger(), event()))
    }

    @Test
    fun `package filter restricts to that package`() {
        val t = trigger(mapOf("package_filter" to "com.foo"))
        assertTrue(handler.matches(t, event(pkg = "com.foo")))
        assertFalse(handler.matches(t, event(pkg = "com.bar")))
    }

    @Test
    fun `class filter distinguishes activities within the same package`() {
        // ⚠️⚠️ 这是本触发器**存在的理由**：
        // AppSwitchTriggerHandler 按包名去重，同包内 Activity 切换被丢弃。
        // 所以「同包不同 Activity 能区分」必须是本模块的核心保证
        val t = trigger(mapOf("class_filter" to "ReadBookActivity"))
        assertTrue(handler.matches(t, event(cls = "io.legado.app.ui.book.read.ReadBookActivity")))
        assertFalse(handler.matches(t, event(cls = "io.legado.app.ui.main.MainActivity")))
    }

    @Test
    fun `contains mode is the default and does substring match`() {
        val t = trigger(mapOf("class_filter" to "Settings"))
        assertTrue(
            "默认应为包含匹配",
            handler.matches(t, event(cls = "com.android.settings.Settings\$WifiSettingsActivity")),
        )
    }

    @Test
    fun `exact mode requires full equality`() {
        val t = trigger(
            mapOf("class_filter" to "com.android.settings.Settings", "match_mode" to "exact")
        )
        assertTrue(handler.matches(t, event(cls = "com.android.settings.Settings")))
        assertFalse(
            "精确模式下不该匹配子串",
            handler.matches(t, event(cls = "com.android.settings.Settings\$WifiActivity")),
        )
    }

    @Test
    fun `matching is case insensitive for both modes`() {
        // 包名/类名大小写敏感在 Android 里其实是敏感的，但用户手输时大小写容易错。
        // 这里选择宽容 —— 匹配失败是静默的，宽容比严格更不容易造成「配了不触发」
        // 包含模式：大小写不同仍匹配
        assertTrue(handler.matches(trigger(mapOf("package_filter" to "COM.FOO")), event()))
        // 精确模式：**全名**大小写不同仍匹配
        // ⚠️ 注意精确模式要求**全等**（不是子串）—— 所以这里必须给全名。
        // 我第一版写成 "mainactivity"（只给类名的一部分）配 exact 模式，那是错的：
        // 它失败不是大小写问题，而是「不全等」
        assertTrue(
            handler.matches(
                trigger(
                    mapOf(
                        "class_filter" to "COM.FOO.MAINACTIVITY",
                        "match_mode" to "exact",
                    )
                ),
                event(),
            )
        )
    }

    @Test
    fun `both filters must match when both are set`() {
        val t = trigger(
            mapOf("package_filter" to "com.foo", "class_filter" to "MainActivity")
        )
        assertTrue(handler.matches(t, event(pkg = "com.foo", cls = "com.foo.MainActivity")))
        assertFalse("包对了类不对 ⇒ 不匹配", handler.matches(t, event(pkg = "com.foo", cls = "Other")))
        assertFalse("类对了包不对 ⇒ 不匹配", handler.matches(t, event(pkg = "com.bar", cls = "com.foo.MainActivity")))
    }

    @Test
    fun `whitespace-only filters are treated as blank`() {
        // 用户手输时容易多打空格 —— 若按字面匹配，输入框里看着是空的却永不触发
        val t = trigger(mapOf("package_filter" to "   ", "class_filter" to "\t"))
        assertTrue(handler.matches(t, event()))
    }

    // ── 枚举归一化（旧数据兼容）────────────────────────────────

    @Test
    fun `legacy localized match_mode values are normalized`() {
        // ⚠️⚠️ 旧数据里可能存的是本地化文案。直接字符串比较会让它们
        // **全部落到默认值**，表现为「用户选了精确匹配，实际按包含匹配」，
        // 而且**不报错** —— 正是本仓库最怕的那类
        assertEquals(
            ActivityChangedTriggerModule.MATCH_EXACT,
            handler.normalizeMatchMode("精确匹配"),
        )
        assertEquals(
            ActivityChangedTriggerModule.MATCH_EXACT,
            handler.normalizeMatchMode("精确"),
        )
        assertEquals(
            ActivityChangedTriggerModule.MATCH_CONTAINS,
            handler.normalizeMatchMode("包含"),
        )
    }

    @Test
    fun `stable match_mode values pass through`() {
        assertEquals(
            ActivityChangedTriggerModule.MATCH_EXACT,
            handler.normalizeMatchMode(ActivityChangedTriggerModule.MATCH_EXACT),
        )
        assertEquals(
            ActivityChangedTriggerModule.MATCH_CONTAINS,
            handler.normalizeMatchMode(ActivityChangedTriggerModule.MATCH_CONTAINS),
        )
    }

    @Test
    fun `unknown match_mode falls back to contains not crash`() {
        assertEquals(
            ActivityChangedTriggerModule.MATCH_CONTAINS,
            handler.normalizeMatchMode("完全不认识的值"),
        )
    }

    @Test
    fun `legacy localized exact mode actually affects matching end to end`() {
        // 端到端确认归一化真的接进了 matches ——
        // 否则「归一化函数对了但没被调用」这种错会漏过（本仓库踩过同类坑）
        val t = trigger(
            mapOf("class_filter" to "Settings", "match_mode" to "精确匹配")
        )
        assertFalse(
            "旧值「精确匹配」应生效为精确模式 ⇒ 子串不该匹配上",
            handler.matches(t, event(cls = "com.android.settings.Settings\$WifiActivity")),
        )
    }

    // ── 包下推（防静默漏采）────────────────────────────────────

    /**
     * ⚠️⚠️ 这一组锁的是**真实缺陷**（用户提问时发现的）：
     * 下推给 hook 层的是**白名单**，所以「推不了一半就当推了一半」会让
     * 推不了的触发器**静默不触发**。
     */
    @Test
    fun `pushdown returns all package filters when every trigger is pushable`() {
        handler.addTriggerForTest(trigger(mapOf("package_filter" to "com.foo")))
        handler.addTriggerForTest(trigger(mapOf("package_filter" to "com.bar"), stepId = "step_2"))
        assertEquals(setOf("com.foo", "com.bar"), handler.computePushdownPackages().toSet())
    }

    @Test
    fun `pushdown is abandoned when any trigger has a blank package filter`() {
        // ⚠️ 空包过滤 = 「任意包」。若只下推另一个触发器的包，
        // 这个「任意」的触发器就会被 hook 层筛掉 ⇒ 永不触发。
        // 故必须整体放弃下推（返回空 = 不限包）
        handler.addTriggerForTest(trigger(mapOf("package_filter" to "com.foo")))
        handler.addTriggerForTest(trigger(mapOf("package_filter" to ""), stepId = "step_2"))
        assertTrue(
            "有空过滤时必须放弃下推（返回空=不限包），实际=${handler.computePushdownPackages()}",
            handler.computePushdownPackages().isEmpty(),
        )
    }

    @Test
    fun `pushdown is abandoned when any trigger uses a magic variable`() {
        // ⚠️⚠️ 这正是缺陷场景：A 固定包、B 用变量。
        // 只下推 A 的包会让 B 收不到任何事件。
        handler.addTriggerForTest(trigger(mapOf("package_filter" to "com.foo")))
        handler.addTriggerForTest(
            trigger(mapOf("package_filter" to "{{vars.pkg}}"), stepId = "step_2")
        )
        assertTrue(
            "有变量时必须放弃下推（运行期才知道值），实际=${handler.computePushdownPackages()}",
            handler.computePushdownPackages().isEmpty(),
        )
    }

    @Test
    fun `pushdown is empty when there are no triggers`() {
        assertTrue(handler.computePushdownPackages().isEmpty())
    }

    @Test
    fun `pushdown deduplicates identical package filters`() {
        handler.addTriggerForTest(trigger(mapOf("package_filter" to "com.foo")))
        handler.addTriggerForTest(trigger(mapOf("package_filter" to "com.foo"), stepId = "step_2"))
        assertEquals(listOf("com.foo"), handler.computePushdownPackages())
    }

    // ── 冷却语义 ──────────────────────────────────────────────

    @Test
    fun `cooldown of zero means no cooldown`() {
        assertTrue(handler.tryAcquireCooldown("t1", 0L, 1000L))
        assertTrue(handler.tryAcquireCooldown("t1", 0L, 1000L))
        assertTrue(handler.tryAcquireCooldown("t1", 0L, 1000L))
    }

    @Test
    fun `cooldown blocks within the window then allows`() {
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 10_000L))
        assertFalse("窗口内应被挡", handler.tryAcquireCooldown("t1", 1000L, 10_500L))
        assertTrue("窗口过后应放行", handler.tryAcquireCooldown("t1", 1000L, 11_001L))
    }

    @Test
    fun `blocked hits do not extend the window`() {
        // ⚠️⚠️ 关键语义：冷却期内的命中**不记账**。
        // 若每次都记账，持续的高频切换会让窗口无限顺延 ——
        // 触发器在「一直在切 Activity」的情况下**永远不会再触发**
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 10_000L))
        // 窗口内密集命中
        repeat(10) { i ->
            assertFalse(handler.tryAcquireCooldown("t1", 1000L, 10_100L + i))
        }
        // 从**第一次**允许之后的 1000ms 就该放行，而不是从最后一次命中算
        assertTrue(
            "窗口不应被窗口内的命中顺延",
            handler.tryAcquireCooldown("t1", 1000L, 11_001L),
        )
    }

    @Test
    fun `cooldown is tracked per trigger id`() {
        // 两个触发器各自的冷却互不影响 —— 共用一个窗口会让先配的那个压制后配的
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 10_000L))
        assertTrue("另一个触发器不该被 t1 的冷却挡住", handler.tryAcquireCooldown("t2", 1000L, 10_100L))
        assertFalse(handler.tryAcquireCooldown("t1", 1000L, 10_100L))
    }

    // ── 冷却参数读取 ──────────────────────────────────────────

    @Test
    fun `cooldown reads from number string and defaults`() {
        assertEquals(500L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to 500))))
        assertEquals(500L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to "500"))))
        // 缺失 / 非法 ⇒ 回退默认（而不是 0 = 不冷却，那会瞬间刷爆工作流）
        assertTrue(handler.cooldownOf(trigger()) > 0L)
        assertTrue(handler.cooldownOf(trigger(mapOf("cooldown_ms" to "abc"))) > 0L)
    }

    @Test
    fun `negative cooldown is clamped to zero`() {
        // 用户填负数时的语义应是「不冷却」，而不是「永远挡住」
        assertEquals(0L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to -5))))
        assertEquals(0L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to "-5"))))
    }
}
