package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.core.xposed.XposedState.Channel
import com.chaomixian.vflow.core.xposed.XposedState.Framework
import com.chaomixian.vflow.core.xposed.XposedState.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Xposed 状态判定的测试。
 *
 * 设计文档：`docs/fork/xposed-channel-p4-design.md` §3.5。
 *
 * ⚠️ 这一层的失效形态是**误导性 UI**：用户看到「一切正常」但功能不工作，
 * 或看到「框架有问题」而实际框架好好的（于是去 LSPosed 里瞎折腾）。
 * 两者都不报错，只能靠测试锁住。
 */
class XposedStateTest {

    /** 默认：一切未知（全新安装、什么都没配）。 */
    private fun input(
        frameworkConnected: Boolean = false,
        everConnected: Boolean = false,
        scope: List<String> = emptyList(),
        runningTargetNames: List<String> = emptyList(),
        channelConnected: Boolean = false,
    ) = Input(frameworkConnected, everConnected, scope, runningTargetNames, channelConnected)

    /** 一切正常的样子（对照实测：scope=[system]、targets 里有 system）。 */
    private fun healthy() = input(
        frameworkConnected = true,
        everConnected = true,
        scope = listOf("system"),
        runningTargetNames = listOf("system"),
        channelConnected = true,
    )

    // ── 状态位 A ──────────────────────────────────────────────

    @Test
    fun `unavailable when never connected`() {
        assertEquals(Framework.UNAVAILABLE, XposedState.evaluate(input()).framework)
    }

    @Test
    fun `degraded when connected before but not now`() {
        // ⚠️ 这个区分很重要：用户「用得好好的突然不工作」与「从没用过」
        // 需要的帮助完全不同。只报「不可用」会把前者误导成「没配置好」
        assertEquals(
            Framework.DEGRADED,
            XposedState.evaluate(input(frameworkConnected = false, everConnected = true)).framework,
        )
    }

    @Test
    fun `active when connected`() {
        assertEquals(Framework.ACTIVE, XposedState.evaluate(healthy()).framework)
    }

    // ── 状态位 B ──────────────────────────────────────────────

    @Test
    fun `disconnected takes precedence over mount signals`() {
        // ⚠️ L0 断就是断，哪怕框架说「我注入好了」。
        // 事件链路走的是 L0 —— 先判这条
        val r = XposedState.evaluate(
            healthy().copy(channelConnected = false)
        )
        assertEquals(Channel.DISCONNECTED, r.channel)
    }

    @Test
    fun `not_mounted when connected but system missing from targets`() {
        // 连上了、scope 也说勾了，但实际没注入 system ⇒ 事件不会产生
        val r = XposedState.evaluate(
            healthy().copy(runningTargetNames = emptyList())
        )
        assertEquals(Channel.NOT_MOUNTED, r.channel)
    }

    @Test
    fun `ready when system is in running targets`() {
        assertEquals(Channel.READY, XposedState.evaluate(healthy()).channel)
    }

    @Test
    fun `targets are matched case insensitively`() {
        val r = XposedState.evaluate(healthy().copy(runningTargetNames = listOf("System")))
        assertEquals(Channel.READY, r.channel)
    }

    @Test
    fun `scope is NOT used to decide channel state`() {
        // ⚠️⚠️ 关键语义：**以 runningTargets（实际结果）为准，不以 scope（配置）为准**。
        // scope 说勾了、实际没注入 ⇒ 就是没挂上（可能没重启）。
        // 反过来若用 scope 判，会给出「配置看着对、但功能不工作」的误导
        val scopeSaysYesButNotMounted = healthy().copy(
            scope = listOf("system"),
            runningTargetNames = emptyList(),
        )
        assertEquals(Channel.NOT_MOUNTED, XposedState.evaluate(scopeSaysYesButNotMounted).channel)

        val scopeSaysNoButMounted = healthy().copy(
            scope = emptyList(),
            runningTargetNames = listOf("system"),
        )
        assertEquals(Channel.READY, XposedState.evaluate(scopeSaysNoButMounted).channel)
    }

    // ── ⭐ 最易误判的一格 ──────────────────────────────────────

    @Test
    fun `ACTIVE plus DISCONNECTED must be reachable and not treated as healthy`() {
        // ⚠️⚠️ 这是本文件最要紧的一格，也是「两组状态位」这个设计的全部意义：
        //
        // 框架好好的（L1 ✅ 官方权威信号）但我们的信道断了（L0 ❌）
        //   ⇒ 单看框架信号会判成「一切正常」，而实际什么都不会触发。
        //
        // 如果状态压成一个枚举（如「框架在 = ACTIVE」），这一格根本表示不出来。
        val r = XposedState.evaluate(healthy().copy(channelConnected = false))
        assertEquals(Framework.ACTIVE, r.framework)
        assertEquals(Channel.DISCONNECTED, r.channel)
        assertFalse("框架正常但通道断 ≠ 健康", XposedState.isHealthy(r))
    }

    @Test
    fun `ACTIVE plus DISCONNECTED must not send the user to framework settings`() {
        // ⚠️ 同样要紧：这一格**不该引导**用户去 LSPosed 折腾 ——
        // 框架是好的，问题在我们自己的连接。引导他去改作用域只会白费功夫，
        // 还可能导致他把本来正确的配置改坏
        val r = XposedState.evaluate(healthy().copy(channelConnected = false))
        assertFalse(
            "框架正常时不该引导用户去改框架配置",
            XposedState.needsGuidance(r),
        )
    }

    // ── 引导判定 ──────────────────────────────────────────────

    @Test
    fun `unavailable needs guidance`() {
        assertTrue(XposedState.needsGuidance(XposedState.evaluate(input())))
    }

    @Test
    fun `degraded needs guidance`() {
        // 框架被停/卸载 ⇒ 需要用户处理
        val r = XposedState.evaluate(input(everConnected = true, channelConnected = false))
        assertEquals(Framework.DEGRADED, r.framework)
        assertTrue(XposedState.needsGuidance(r))
    }

    @Test
    fun `not_mounted needs guidance`() {
        // 多半是作用域没勾对或没重启 ⇒ 需要引导
        val r = XposedState.evaluate(healthy().copy(runningTargetNames = emptyList()))
        assertTrue(XposedState.needsGuidance(r))
    }

    @Test
    fun `healthy does not need guidance`() {
        assertFalse(XposedState.needsGuidance(XposedState.evaluate(healthy())))
    }

    // ── 健康判定 ──────────────────────────────────────────────

    @Test
    fun `isHealthy requires both state bits`() {
        assertTrue(XposedState.isHealthy(XposedState.evaluate(healthy())))
        // 逐个破坏真正影响可用性的输入
        assertFalse(XposedState.isHealthy(XposedState.evaluate(healthy().copy(frameworkConnected = false))))
        assertFalse(XposedState.isHealthy(XposedState.evaluate(healthy().copy(runningTargetNames = emptyList()))))
        assertFalse(XposedState.isHealthy(XposedState.evaluate(healthy().copy(channelConnected = false))))
    }

    @Test
    fun `everConnected does not affect health when currently connected`() {
        // ⚠️ 这条是我写测试时**写错过一次**的地方，记下来：
        //
        // `everConnected` 的**唯一用途**是「断开时区分『从没连过』与『连过又断』」。
        // 此刻连着（`frameworkConnected = true`）时它不参与任何判定 ——
        // 包括健康判定。所以 `everConnected = false` 但当前连着**仍是健康的**。
        //
        // 我第一版断言它「不健康」，那是把「持久化的历史标记」误当成了
        // 「当前状态的一部分」。测试改了，代码没改 —— 因为是测试理解错了。
        val r = XposedState.evaluate(healthy().copy(everConnected = false))
        assertEquals(Framework.ACTIVE, r.framework)
        assertTrue(XposedState.isHealthy(r))
    }

    @Test
    fun `isHealthy does not depend on scope list contents`() {
        // scope 是配置、不是结果 —— 不该影响「功能是否可用」的判定
        assertTrue(XposedState.isHealthy(XposedState.evaluate(healthy().copy(scope = emptyList()))))
    }

    // ── 点击行为分类 ──────────────────────────────────────────

    @Test
    fun `healthy state guides instead of claiming it is reconnecting`() {
        // ⚠️⚠️ 这条锁的是一个**我实际写出来的 bug**：
        // UI 里曾写成 `if (needsGuidance(r)) 弹引导 else 弹重连提示`，
        // 而 `needsGuidance` 对「正常」也返回 false ⇒
        // **一切正常时点卡片会弹「通道正在重连」**（莫名其妙）。
        //
        // 正常状态点卡片应当**弹引导对话框**（它里面有实时状态与作用域，正常时也有用）。
        assertEquals(
            XposedState.TapAction.GUIDE,
            XposedState.tapAction(XposedState.evaluate(healthy())),
        )
    }

    @Test
    fun `only the ACTIVE plus DISCONNECTED cell shows the reconnect hint`() {
        // ⭐ 这是唯一该给「重连提示」而不是引导的一格 ——
        // 框架好的，断的是我们自己的连接；让用户去 LSPosed 里折腾没用，
        // 还可能把本来正确的配置改坏
        val r = XposedState.evaluate(healthy().copy(channelConnected = false))
        assertEquals(XposedState.TapAction.RECONNECT_HINT, XposedState.tapAction(r))
    }

    @Test
    fun `all other failing states guide the user`() {
        // 未启用 / 框架断开 / 未挂载 —— 都需要用户去配置，一律引导
        val cases = listOf(
            "从没连过" to input(),
            "连过又断" to input(everConnected = true, channelConnected = false),
            "已配置未挂载" to healthy().copy(runningTargetNames = emptyList()),
        )
        for ((name, inp) in cases) {
            assertEquals(
                "「$name」应当引导用户去配置",
                XposedState.TapAction.GUIDE,
                XposedState.tapAction(XposedState.evaluate(inp)),
            )
        }
    }

    @Test
    fun `null result guides rather than hinting`() {
        // 状态还没加载出来时点卡片 —— 弹引导（它至少能显示「未知」），
        // 而不是弹一个说不通的「正在重连」
        assertEquals(XposedState.TapAction.GUIDE, XposedState.tapAction(null))
    }

    // ── 展示辅助 ──────────────────────────────────────────────

    @Test
    fun `describeScope distinguishes unknown from empty`() {
        // ⚠️ 空列表的语义是「拿不到」，不是「一个都没勾」。
        // 混同会给用户错误信息（让他以为要去勾，而其实是没读到）
        assertEquals("未知", XposedState.describeScope(emptyList()))
        assertEquals("system", XposedState.describeScope(listOf("system")))
        assertEquals("system, com.foo", XposedState.describeScope(listOf("system", "com.foo")))
    }
}
