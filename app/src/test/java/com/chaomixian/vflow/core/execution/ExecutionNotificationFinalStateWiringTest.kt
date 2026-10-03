package com.chaomixian.vflow.core.execution

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠️⚠️ **源码扫描型接线锚定**（形态照 `CoreDexFingerprintTest`）。
 *
 * ## 存在理由
 *
 * 修的是一个**真机缺陷**（用户日志 `vflow_log_20261003_224738.txt` 里三次执行逐次复现）：
 * `WorkflowExecutor.execute()` 在主流程返回后**无条件**写
 * `ExecutionNotificationState.Completed("执行完毕")`，而 `executeWorkflowInternal`
 * 在模块失败且策略为 STOP 时是 **`return null`**（不抛异常）⇒ 这条路径照样执行
 * ⇒ 通知先被写成「失败: …」、再被覆盖成「执行完毕」，而终态通知是 `setOngoing(true)`
 * 的（用户划不掉，只能等系统超时或手动清）。
 *
 * ## 为什么必须是源码扫描
 *
 * `WorkflowExecutor` 是 Android 依赖极重的执行器，纯 JVM 起不来；而这段逻辑的
 * 失败形态是**静默的**（通知文案错，日志里两行都"正常"）。仓库里已有同款先例：
 * `TriggerServiceXposedNoticeWiringTest`、`AgentErrorDialogWiringTest`。
 *
 * ⚠️ **必须剥注释** —— 修复本身在源码里加了大段含 `Completed(` / `failedExecutions`
 * 字样的 KDoc，只做 `contains` 的话「把守卫删掉」照样绿（本仓库踩过）。
 */
class ExecutionNotificationFinalStateWiringTest {

    private companion object {
        const val EXECUTOR =
            "src/main/java/com/chaomixian/vflow/core/execution/WorkflowExecutor.kt"
    }

    private fun executeBody(): String {
        // ⚠️ `execute` **不是** `suspend`（它是普通函数，内部 `launch` 起协程）。
        //
        // ⚠️⚠️ 签名片段**只能用单行**：本文件的源码是 **CRLF 行尾**
        //    （`file` 报 "with CRLF line terminators"），带 `\n` 的多行片段**匹配不到**。
        //    用 `fun execute(` **连同前导四空格**即可避免与
        //    `executeSubWorkflow` / `executeWorkflowInternal` 撞名
        //    （两者都不含 `    fun execute(` 这个连续子串）。
        //
        //    本条由"测试取不到函数体"实测得来 —— 防空转断言正是为这种情形设的。
        val body = SourceScan.functionBody(SourceScan.stripped(EXECUTOR), "    fun execute(")
        assertTrue("🔴 取不到 execute 函数体（签名改了？）—— 防空转", body != null && body.length > 2000)
        return body!!
    }

    /**
     * ⚠️ 终态的 `Completed` **必须**被「本次是否失败」守卫住，不能只看 `isTimeout`。
     */
    @Test
    fun `completed notification is guarded by the failure flag`() {
        val body = executeBody()

        assertTrue(
            "🔴 Completed(\"执行完毕\") 必须同时被 `!failed`（或等价的失败判据）守卫 —— " +
                "否则失败路径会被冒称成功（真机缺陷）",
            body.contains("!isTimeout && !failed") || body.contains("!failed && !isTimeout"),
        )
        // 反向：`Completed("执行完毕")` 的**紧邻上文**必须出现 `!failed`。
        //
        // ⚠️ 不能简单断言「body 里不存在 `if (!isTimeout)`」—— 它在
        //    `catch (e: CancellationException)` 里**仍然存在且正确**
        //    （那里判的是「超时就不再重复写 Cancelled」）。粒度必须精确到这一处调用。
        val completedIdx = body.indexOf("Completed(\"执行完毕\")")
        assertTrue("🔴 找不到 Completed(\"执行完毕\") —— 防空转", completedIdx > 0)
        val preceding = body.substring(maxOf(0, completedIdx - 400), completedIdx)
        assertTrue(
            "🔴 Completed(\"执行完毕\") 的上文必须含 `failed` 判据 —— 否则失败路径会被冒称成功",
            preceding.contains("failed"),
        )
    }

    /**
     * ⚠️ **读标记，不删标记**。
     *
     * 若在这里用 `failedExecutions.remove(...)`，`finally` 里的
     * `wasFailureHandled` 会变 false ⇒ **再广播一次 `Finished`**，把失败状态在
     * `ExecutionStateBus` 上盖掉（比通知更难发现 —— 触发器与 UI 都看它）。
     */
    @Test
    fun `the guard reads the failure flag without consuming it`() {
        val body = executeBody()

        assertTrue(
            "🔴 守卫里必须用 `failedExecutions[x] == true` 读，不得用 remove",
            body.contains("failedExecutions[executionInstanceId] == true"),
        )
        // ⚠️ 防空转 + 精确性：`remove` 只应出现在 finally 的 wasFailureHandled 那一处
        val removeCount = SourceScan.countOccurrences(body, "failedExecutions.remove(")
        assertTrue(
            "🔴 `failedExecutions.remove(` 在 execute 体内应**恰好 1 处**（finally 里的 wasFailureHandled），" +
                "实际 $removeCount —— 多出来的那处会把标记吃掉",
            removeCount == 1,
        )
        assertTrue(
            "🔴 那一处必须是 wasFailureHandled 的赋值",
            body.contains("val wasFailureHandled = failedExecutions.remove(executionInstanceId) == true"),
        )
    }
}
