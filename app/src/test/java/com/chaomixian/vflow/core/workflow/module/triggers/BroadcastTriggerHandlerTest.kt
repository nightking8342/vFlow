package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.BroadcastTriggerHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 广播触发器 Handler 的**纯判定面**单测。
 *
 * ## ⚠️ 这个测试测**什么**、**不测什么**
 *
 * 测：[filterSpecOf] 的规格推导、[BroadcastTriggerHandler.tryAcquireCooldown] 的冷却语义、
 * [BroadcastTriggerHandler.cooldownOf] 的参数解析。
 *
 * **不测 `buildFilter` / `registerFor`** —— `IntentFilter` 是 Android 框架类，
 * 本模块既没开 `unitTests { isReturnDefaultValues = true }` 也没上 Robolectric
 * ⇒ 纯 JVM 里 `IntentFilter()` 会抛 "not mocked"
 * （与 `VObjectPropertyTest` 那条已知失败的成因同类）。
 *
 * ⚠️ **不要**为了让测试能跑而去开 `isReturnDefaultValues = true` ——
 * 那会让「filter 其实没配上」这类错在测试里表现为「提前返回的默认值」，
 * 等于把断言变成恒真（本仓库记过这类教训）。
 *
 * ⇒ 「规格 → `IntentFilter` → 平台匹配」这一段**没有任何自动化覆盖**，
 * 只能靠真机验证（设计文档 §11 第 1/3/4/13 项）。
 * **不要**为此写「恒真」的断言。
 *
 * ## 构造形态
 *
 * `TriggerSpec` 是 `(workflow, step)` **派生**的（`triggerId = "workflowId:stepId"`），
 * 不能直接 new —— 照 `ActivityChangedTriggerHandlerTest` 的 helper 写。
 * 构造 Handler 本身不碰 Android（`triggerScope` 是 `CoroutineScope` 的普通构造），
 * 所以可以直接 `BroadcastTriggerHandler()`。
 */
class BroadcastTriggerHandlerTest {

    private val handler = BroadcastTriggerHandler()

    private fun trigger(
        params: Map<String, Any?> = emptyMap(),
        stepId: String = "step_1",
        workflowId: String = "w1",
        workflowName: String = "测试工作流",
    ): TriggerSpec {
        val step = ActionStep(
            id = stepId,
            moduleId = BroadcastTriggerModule().id,
            parameters = params,
        )
        val workflow = Workflow(
            id = workflowId,
            name = workflowName,
            steps = listOf(step),
        )
        return TriggerSpec(workflow, step)
    }

    // ═══ filterSpecOf ═══

    @Test
    fun `filterSpecOf returns null when actions are missing`() {
        // ⚠️⚠️ 空 actions ⇒ **不注册 receiver**（不是注册一个空 filter）。
        //
        // 两种做法在「命中行为」上等价（空 filter 在平台层面只匹配
        // 「无 action 的 intent」，等价于永不命中），但本模块的 receiver 是
        // **EXPORTED** 的（任意应用可发）⇒ 白注册等于**白接管一堆广播**。
        //
        // 反证：把 registerFor 里那句 `?: return` 删掉 ⇒
        // BroadcastTriggerWiringTest 里那条「空 actions 时不得调用 registerReceiver」变红。
        assertNull(handler.filterSpecOf(trigger()))
        assertNull(handler.filterSpecOf(trigger(mapOf("actions" to emptyList<String>()))))
        assertNull(handler.filterSpecOf(trigger(mapOf("actions" to null))))
    }

    @Test
    fun `filterSpecOf returns null when actions are blank only`() {
        // 「用户敲了几个空格」是最常见的情形，不能因为「列表非空」就放过
        assertNull(handler.filterSpecOf(trigger(mapOf("actions" to listOf("", "   ", "　")))))
    }

    @Test
    fun `filterSpecOf returns null when every action is the wildcard`() {
        // ⚠️⚠️ `addAction("*")` 在平台上**无效** —— `matchAction` 是
        // `mActions.contains(action)`，列表里存的是字面量 `"*"`，真实 action 永远匹配不到。
        // ⇒ 归一化后为空时同样**不注册**（否则那个触发器永不可能命中，
        // 却白占一个 EXPORTED receiver）。
        assertNull(handler.filterSpecOf(trigger(mapOf("actions" to listOf("*")))))
        assertNull(handler.filterSpecOf(trigger(mapOf("actions" to listOf("*", "  ", "")))))
    }

    @Test
    fun `filterSpecOf keeps a valid action alongside a dropped wildcard`() {
        // 有合法项时正常注册，`"*"` 被丢掉（编辑期本该被 validate 拦下）
        val spec = handler.filterSpecOf(trigger(mapOf("actions" to listOf("com.a.ACTION", "*"))))
        assertNotNull(spec)
        assertEquals(listOf("com.a.ACTION"), spec!!.actions)
    }

    @Test
    fun `filterSpecOf strips blanks and deduplicates actions`() {
        val spec = handler.filterSpecOf(
            trigger(mapOf("actions" to listOf(" a ", "", "a", "b")))
        )
        assertNotNull(spec)
        assertEquals(listOf("a", "b"), spec!!.actions)
    }

    @Test
    fun `filterSpecOf keeps scheme case as-is`() {
        // ⚠️⚠️ **反向锁**：`IntentFilter.matchData` 是 `schemes.contains(scheme)`
        // **精确比较、区分大小写**。把 `HTTP` 小写化后再 `addDataScheme`，
        // 广播里带大写 scheme 的那条就被 filter 拦在门外了
        // ⇒ 归一化反而制造「明明配了却收不到」。
        val spec = handler.filterSpecOf(
            trigger(
                mapOf(
                    "actions" to listOf("a"),
                    "data_schemes" to listOf("HTTP ", "content"),
                )
            )
        )
        assertNotNull(spec)
        assertEquals(listOf("HTTP", "content"), spec!!.schemes)
    }

    @Test
    fun `filterSpecOf normalizes schemes and categories`() {
        val spec = handler.filterSpecOf(
            trigger(
                mapOf(
                    "actions" to listOf("a"),
                    "data_schemes" to listOf(" package ", "", "package", "*"),
                    "categories" to listOf(" CAT_A ", "*", "CAT_A", ""),
                )
            )
        )
        assertNotNull(spec)
        assertEquals(listOf("package"), spec!!.schemes)
        assertEquals(listOf("CAT_A"), spec!!.categories)
    }

    @Test
    fun `filterSpecOf accepts missing scheme and category keys`() {
        // 只填了 actions 是常态（可选参数没配）
        val spec = handler.filterSpecOf(trigger(mapOf("actions" to listOf("a"))))
        assertNotNull(spec)
        assertTrue(spec!!.schemes.isEmpty())
        assertTrue(spec.categories.isEmpty())
    }

    @Test
    fun `filterSpecOf tolerates a bare string instead of a list`() {
        // 手工改 JSON / AI 写入时可能是单字符串而不是数组
        val spec = handler.filterSpecOf(trigger(mapOf("actions" to "com.a.ACTION")))
        assertNotNull(spec)
        assertEquals(listOf("com.a.ACTION"), spec!!.actions)
    }

    @Test
    fun `filterSpecOf drops non-string elements instead of stringifying`() {
        // `123` 会被 toString 成 `"123"` —— 一个永远不会命中的 action，
        // 而用户看到列表里那一项「长得没问题」
        val spec = handler.filterSpecOf(
            trigger(mapOf("actions" to listOf("com.a.ACTION", 123)))
        )
        assertNotNull(spec)
        assertEquals(listOf("com.a.ACTION"), spec!!.actions)
    }

    // ═══ 冷却 ═══

    @Test
    fun `cooldown of zero or negative always allows`() {
        // ⚠️ `0` = 不冷却，必须是「恒放行」而不是「恒拒绝」——
        // 用户配 0 的意图就是「不要冷却」
        assertTrue(handler.tryAcquireCooldown("t1", 0L, 1000L))
        assertTrue(handler.tryAcquireCooldown("t1", 0L, 1000L))
        assertTrue(handler.tryAcquireCooldown("t2", -5L, 1000L))
    }

    @Test
    fun `cooldown rejects within the window and allows after it`() {
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 10_000L))
        // 窗口内
        assertTrue(!handler.tryAcquireCooldown("t1", 1000L, 10_500L))
        // 窗口边界（恰好 1000ms）应当放行 —— 判据是 `< cooldownMs`
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 11_000L))
    }

    @Test
    fun `rejected hits inside the window do not extend it`() {
        // ⚠️⚠️ **本仓库反复记过的一条**：冷却期内的命中**不记账**。
        //
        // 若每次都记账，高频广播（每秒好几条）会让窗口**无限顺延**
        // ⇒ 触发器在持续收到广播时**永远不会再触发**，而用户看到的是
        // 「一开始能触发，后来就不动了」。
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 0L))

        // 窗口内狂轰 9 次（100…900），全部被拒。
        // ⚠️ 上界是 **900** 而不是 1000 —— 判据是 `nowMs - last < cooldownMs`，
        // t=1000 恰好满足「已满一个窗口」⇒ 本该放行（下面那条断言就是它）
        for (t in 100L..900L step 100L) {
            assertTrue("窗口内应被拒 (t=$t)", !handler.tryAcquireCooldown("t1", 1000L, t))
        }

        // ⚠️ 关键断言：**从首次触发算**满 1000ms 就该放行
        //（若窗口被顺延，这里仍会被拒）
        assertTrue(
            "窗口内被拒的命中不得顺延窗口 —— 首次记账在 t=0，t=1000 应放行",
            handler.tryAcquireCooldown("t1", 1000L, 1000L),
        )
    }

    @Test
    fun `cooldown is counted per trigger id`() {
        // 两个触发器监听同一个 action 时，必须**各自**判冷却、各自触发自己的工作流
        //（这是正确行为，与 ActivityChangedTriggerHandler 的语义一致）
        assertTrue(handler.tryAcquireCooldown("t1", 1000L, 0L))
        assertTrue("t2 有独立的窗口", handler.tryAcquireCooldown("t2", 1000L, 0L))
        assertTrue(!handler.tryAcquireCooldown("t1", 1000L, 0L))
        assertTrue(!handler.tryAcquireCooldown("t2", 1000L, 0L))
    }

    // ═══ cooldownOf ═══

    @Test
    fun `cooldownOf reads a number parameter`() {
        assertEquals(2500L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to 2500))))
        assertEquals(2500L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to 2500L))))
        assertEquals(0L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to 0))))
    }

    @Test
    fun `cooldownOf reads a numeric string`() {
        // JSON 导入时数字可能是字符串
        assertEquals(3000L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to "3000"))))
    }

    @Test
    fun `cooldownOf falls back to the module default`() {
        // 未配置 / 非法字符串 ⇒ 回落默认值（不是 0 —— 0 会让高频广播刷爆工作流）
        assertEquals(
            BroadcastTriggerModule.DEFAULT_COOLDOWN_MS.toLong(),
            handler.cooldownOf(trigger()),
        )
        assertEquals(
            BroadcastTriggerModule.DEFAULT_COOLDOWN_MS.toLong(),
            handler.cooldownOf(trigger(mapOf("cooldown_ms" to "abc"))),
        )
    }

    @Test
    fun `cooldownOf clamps negative numbers to zero`() {
        // 负数 = 不冷却（钳到 0），不是「用默认值」—— 用户明确表达了「不要窗口」
        assertEquals(0L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to -1))))
        assertEquals(0L, handler.cooldownOf(trigger(mapOf("cooldown_ms" to "-1"))))
    }

    @Test
    fun `the module default cooldown matches the handler fallback`() {
        // ⚠️ 两处常量必须一致：模块的 defaultValue 给编辑器用，
        // Handler 的 FALLBACK 给「参数缺失」用 —— 不一致会让
        // 「界面上显示 1000、实际按别的值跑」
        assertEquals(1000, BroadcastTriggerModule.DEFAULT_COOLDOWN_MS)
        assertEquals(
            BroadcastTriggerModule.DEFAULT_COOLDOWN_MS.toLong(),
            handler.cooldownOf(trigger()),
        )
    }

    // ═══ 登记（不触碰 Android 的部分）═══

    @Test
    fun `addTriggerForTest replaces a trigger with the same id`() {
        // 同一触发器重复 addTrigger（改配置后重加）不得产生两条
        val t = trigger(stepId = "s1")
        handler.addTriggerForTest(t)
        handler.addTriggerForTest(t)
        assertEquals(1, handler.listeningTriggerCount())
    }

    @Test
    fun `addTriggerForTest keeps distinct triggers`() {
        handler.addTriggerForTest(trigger(stepId = "s1"))
        handler.addTriggerForTest(trigger(stepId = "s2"))
        assertEquals(2, handler.listeningTriggerCount())
    }
}
