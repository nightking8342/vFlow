package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.permissions.Permission
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.permissions.PermissionType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [XposedReadiness] 的单测。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.1。
 *
 * ## 本测试钉的是两类东西
 *
 * 1. **`ACTIVE + DISCONNECTED` 这一格仍可达**（需求点名）—— 它是 §6.1 定案的
 *    附带义务存在的理由，而它恰好是「最容易被误判成框架问题」的一格。
 * 2. **「只看 `triggers`」与「比 id 不比实例」这两条判据** —— 它们错了不会报错，
 *    只会让提示出现在错误的场合（或该出现时不出现）。
 */
class XposedReadinessTest {

    // ── 构造工具 ──────────────────────────────────────────────

    /** 一个普通触发器 / 步骤。判据只看 `moduleId`，参数无所谓。 */
    private fun step(moduleId: String) = ActionStep(moduleId = moduleId, parameters = emptyMap())

    /** 除 `channelConnected` 外一切「框架这边都正常」的输入。 */
    private fun input(
        frameworkConnected: Boolean = true,
        everConnected: Boolean = true,
        scope: List<String> = listOf("system"),
        runningTargetNames: List<String> = listOf("system"),
        channelConnected: Boolean = true,
    ) = XposedState.Input(
        frameworkConnected = frameworkConnected,
        everConnected = everConnected,
        scope = scope,
        runningTargetNames = runningTargetNames,
        channelConnected = channelConnected,
    )

    private fun evaluate(input: XposedState.Input): XposedState.Result = XposedState.evaluate(input)

    /** 按 moduleId 给权限的表。未列出的模块 = 不声明任何权限。 */
    private fun permissions(declared: Map<String, List<Permission>>): (String) -> List<Permission> =
        { moduleId -> declared[moduleId].orEmpty() }

    /**
     * 复制一份 [PermissionManager.XPOSED_HOOK] 的**新实例**（模拟 Parcelable 往返）。
     *
     * 它 `==` 常量不成立（不是同一个对象、也不是 `equals` 相等的 data class 新实例
     * —— 字段全同其实 `equals` 会成立，故这里刻意改掉一个无关字段让它真正不同）。
     */
    private fun xposedHookCopy(): Permission = Permission(
        id = PermissionManager.XPOSED_HOOK.id,
        name = PermissionManager.XPOSED_HOOK.name,
        description = "（Parcelable 往返后的副本）",
        type = PermissionType.SPECIAL,
    )

    // ── 1. ⭐ 需求点名的那一格 ─────────────────────────────────

    @Test
    fun `ACTIVE plus DISCONNECTED is still reachable end to end`() {
        val result = evaluate(input(channelConnected = false))

        assertEquals(XposedState.Framework.ACTIVE, result.framework)
        assertEquals(XposedState.Channel.DISCONNECTED, result.channel)
        assertTrue(
            "框架在跑、通道断了 ⇒ 必须提示（这正是权限判据不耦合 L0 的代价，见 §6.1）",
            XposedReadiness.needsChannelNotice(result),
        )
    }

    @Test
    fun `ACTIVE plus DISCONNECTED is still not healthy`() {
        val result = evaluate(input(channelConnected = false))

        assertFalse("两组状态位必须同时正常才算健康", XposedState.isHealthy(result))
        assertFalse(
            "⚠️ 这一格**绝不能**引导用户去改框架配置 —— 框架是好的，断的是我们自己的连接",
            XposedState.needsGuidance(result),
        )
        assertEquals(XposedState.TapAction.RECONNECT_HINT, XposedState.tapAction(result))
    }

    // ── 2. 其余各格 ───────────────────────────────────────────

    @Test
    fun `ready channel needs no notice`() {
        val result = evaluate(input(channelConnected = true, runningTargetNames = listOf("system")))

        assertEquals(XposedState.Channel.READY, result.channel)
        assertFalse("通道正常时不该有任何提示", XposedReadiness.needsChannelNotice(result))
    }

    @Test
    fun `not_mounted also needs a notice`() {
        val result = evaluate(input(channelConnected = true, runningTargetNames = emptyList()))

        assertEquals(XposedState.Channel.NOT_MOUNTED, result.channel)
        assertTrue("连上了但没挂上也是「触发器不工作」", XposedReadiness.needsChannelNotice(result))
    }

    @Test
    fun `channel state does not depend on framework state`() {
        // 固定 L0 断开，其余三格任意组合 ⇒ Channel **恒为** DISCONNECTED。
        // 锁住 P4 的核心设计（防有人「顺手」把两者耦合起来 —— 那会让
        //「框架在但通道断」这一格永远判不出来）
        val combos = listOf(
            true to true,
            true to false,
            false to true,
            false to false,
        )
        combos.forEach { (frameworkConnected, everConnected) ->
            val result = evaluate(
                input(
                    frameworkConnected = frameworkConnected,
                    everConnected = everConnected,
                    runningTargetNames = listOf("system"),
                    channelConnected = false,
                )
            )
            assertEquals(
                "frameworkConnected=$frameworkConnected everConnected=$everConnected 时 Channel 仍应为 DISCONNECTED",
                XposedState.Channel.DISCONNECTED,
                result.channel,
            )
            assertTrue(XposedReadiness.needsChannelNotice(result))
        }
    }

    // ── 3. 影响面筛选 ─────────────────────────────────────────

    @Test
    fun `selects only enabled workflows with xposed triggers`() {
        val xposedTriggers = mapOf(XPOSED_TRIGGER to listOf(PermissionManager.XPOSED_HOOK))
        val enabledHit = Workflow(
            id = "enabled-hit",
            name = "启用 + Xposed 触发器",
            triggers = listOf(step(XPOSED_TRIGGER)),
            isEnabled = true,
        )
        val disabledHit = Workflow(
            id = "disabled-hit",
            name = "禁用 + Xposed 触发器",
            triggers = listOf(step(XPOSED_TRIGGER)),
            isEnabled = false,
        )
        val plainTriggers = mapOf(
            XPOSED_TRIGGER to listOf(PermissionManager.XPOSED_HOOK),
            PLAIN_TRIGGER to listOf(PermissionManager.ACCESSIBILITY),
        )
        val enabledMiss = Workflow(
            id = "enabled-miss",
            name = "启用 + 无 Xposed 触发器",
            triggers = listOf(step(PLAIN_TRIGGER)),
            isEnabled = true,
        )

        val selected = XposedReadiness.selectAffectedWorkflows(
            listOf(enabledHit, disabledHit, enabledMiss),
            permissions(plainTriggers),
        )

        assertEquals(listOf("enabled-hit"), selected.map { it.id })
    }

    @Test
    fun `xposed module declared on a step does not count`() {
        // 锁 §4.1 的「只看 triggers」：本提示回答的是「触发器为什么不触发」。
        // steps 里的 Xposed 动作会走**执行期显式失败**（用户看得到步骤报错），
        // 不需要这里再报一次；把它算进来会让提示出现在根本没坏的场景里。
        val map = mapOf(XPOSED_TRIGGER to listOf(PermissionManager.XPOSED_HOOK))
        val workflow = Workflow(
            id = "xposed-on-step",
            name = "Xposed 模块只在 steps 里",
            triggers = listOf(step(PLAIN_TRIGGER)),
            steps = listOf(step(XPOSED_TRIGGER)),
            isEnabled = true,
        )

        val selected = XposedReadiness.selectAffectedWorkflows(listOf(workflow), permissions(map))

        assertTrue("step 上的 Xposed 模块不该让触发器提示出现", selected.isEmpty())
    }

    @Test
    fun `matches permission by id not by instance`() {
        // 锁 `it.id ==` 而非 `it ==`：注册表里的实例可能来自 Parcelable 往返
        val map = mapOf(XPOSED_TRIGGER to listOf(xposedHookCopy()))
        val workflow = Workflow(
            id = "copy-instance",
            name = "权限是副本实例",
            triggers = listOf(step(XPOSED_TRIGGER)),
            isEnabled = true,
        )

        val selected = XposedReadiness.selectAffectedWorkflows(listOf(workflow), permissions(map))

        assertEquals(
            "必须按 id 匹配 —— 按实例相等会在 Parcelable 往返后失配",
            listOf("copy-instance"),
            selected.map { it.id },
        )
    }

    @Test
    fun `empty input yields empty output`() {
        assertTrue(XposedReadiness.selectAffectedWorkflows(emptyList(), permissions(emptyMap())).isEmpty())
        // 未知模块 id ⇒ 权限表里查不到 ⇒ 什么都不命中（也不该抛）
        val unknownModule = Workflow(
            id = "unknown",
            name = "未知模块",
            triggers = listOf(step("vflow.trigger.does_not_exist")),
            isEnabled = true,
        )
        assertTrue(
            XposedReadiness.selectAffectedWorkflows(listOf(unknownModule), permissions(emptyMap())).isEmpty()
        )
    }

    private companion object {
        const val XPOSED_TRIGGER = "vflow.trigger.activity_changed"
        const val PLAIN_TRIGGER = "vflow.trigger.manual"
    }
}
