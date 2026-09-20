package com.chaomixian.vflow.services

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [shouldSilenceNotification] 的回归测试。
 *
 * 这段判断锁的是「静默执行」开关的边界，两个方向都会静默失效：
 *  - 漏掉豁免 → 静默工作流失败了却不提示，用户以为它正常运行；
 *  - 豁免过头 → 沉默的不只是过程，连正常完成也被吞掉，开关形同虚设。
 * 都不会抛异常，只会让行为悄悄变差。
 */
class SilentExecutionNotificationTest {

    @Test
    fun `非静默工作流任何状态都不静音`() {
        val states = listOf(
            ExecutionNotificationState.Running(0, "正在开始..."),
            ExecutionNotificationState.Completed("执行完毕"),
            ExecutionNotificationState.Cancelled("已停止"),
            ExecutionNotificationState.Failed("失败: 找不到元素"),
        )

        states.forEach { state ->
            assertFalse(
                "非静默工作流不应静音 ${state::class.simpleName}",
                shouldSilenceNotification(silentExecution = false, state = state)
            )
        }
    }

    @Test
    fun `静默工作流的过程性状态全部静音`() {
        assertTrue(
            shouldSilenceNotification(
                silentExecution = true,
                state = ExecutionNotificationState.Running(50, "步骤 2/4: 点击")
            )
        )
        assertTrue(
            shouldSilenceNotification(
                silentExecution = true,
                state = ExecutionNotificationState.Completed("执行完毕")
            )
        )
        assertTrue(
            shouldSilenceNotification(
                silentExecution = true,
                state = ExecutionNotificationState.Cancelled("已停止")
            )
        )
    }

    @Test
    fun `静默工作流失败时不静音`() {
        // 这是本开关最重要的边界：静默 = 别播报过程，而不是「炸了也别告诉我」。
        assertFalse(
            shouldSilenceNotification(
                silentExecution = true,
                state = ExecutionNotificationState.Failed("失败: 找不到元素")
            )
        )
    }

    @Test
    fun `超时走的是 Failed 因此同样不静音`() {
        // 执行器把超时也映射为 Failed（WorkflowExecutor 的 TimeoutCancellation 分支），
        // 所以超时同样会通知到用户——这个用例把该事实固定下来。
        assertFalse(
            shouldSilenceNotification(
                silentExecution = true,
                state = ExecutionNotificationState.Failed("执行超时（60秒）")
            )
        )
    }
}
