package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「开关型磁贴改数据 ⇒ 列表页刷新」这条链路的**接线锚定**（fork，2026-10-06）。
 *
 * ## 为什么必须扫源码
 *
 * 这条链路的两端都是**编译期与单测都看不见**的：
 * - 磁贴那边漏发通知 ⇒ 列表不刷；
 * - 列表那边漏订阅 ⇒ 磁贴发了也没人听。
 *
 * 两种都是**静默**的（不报错、不崩溃），而症状又特别难自查：
 * 磁贴是对的、数据是对的，**只有列表那一格是旧的** —— 而用户要切出去再切回来
 * 才会发现「其实是刷新了」。
 *
 * ⚠️ 本仓库在 `CoreLauncher` / `XposedDiagnostics.messageFor` 上踩过三次同类形态
 * （纯函数测试全绿、集成点缺失），故这里逐端扫描。
 */
class WorkflowDataChangeWiringTest {

    private companion object {
        const val TOGGLE_SERVICE =
            "src/main/java/com/chaomixian/vflow/ui/tile/BaseToggleTileService.kt"
        const val LIST_ROUTE =
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"
    }

    @Test
    fun `the toggle tile publishes a data-change signal after writing`() {
        val source = SourceScan.stripped(TOGGLE_SERVICE)
        val toggle = SourceScan.functionBody(source, "private fun toggleWorkflowEnabled(")
        assertNotNull("必须能截到 toggleWorkflowEnabled 的函数体（防空转）", toggle)

        assertTrue(
            // ★ 少了这一句，下拉 QS 面板改了开关、回到列表看到的还是旧状态
            //   （`ON_RESUME` 与 `isActive` 都不变，列表没机会重新读盘）。
            "toggleWorkflowEnabled 必须发 WorkflowDataChangeBus 通知",
            toggle!!.contains("WorkflowDataChangeBus.notifyChanged()")
        )
    }

    @Test
    fun `the first publish happens on the main path, after the write`() {
        val source = SourceScan.stripped(TOGGLE_SERVICE)
        val body = SourceScan.functionBody(source, "private fun toggleWorkflowEnabled(")!!
        assertNotNull("必须能截到 toggleWorkflowEnabled 的函数体（防空转）", body)

        val writeAt = body.indexOf("manager.saveWorkflow(")
        val branchAt = body.indexOf("if (!enable)")
        val notifyAt = body.indexOf("WorkflowDataChangeBus.notifyChanged()")

        assertTrue("必须能同时找到写入点 / 分支点 / 通知点", writeAt >= 0 && branchAt >= 0 && notifyAt >= 0)

        // ⚠️⚠️ **判据必须是「第一次通知落在哪」而不是「函数体里出现过」** ——
        //     本函数里**有第二处** `notifyChanged()`（权限回弹那条路径），
        //     只做 `contains` 的话，把主路径那处删掉测试**照样绿**
        //     （实现期已实际踩到：删了主发布点，测试没红）。
        assertTrue(
            "主路径的通知必须在 saveWorkflow **之后** —— 发早了列表读到的是旧值，" +
                "而这是一次性信号、不会自己再刷一次",
            notifyAt > writeAt
        )
        assertTrue(
            "主路径的通知必须在 `if (!enable)` **之前**（即：无论开还是关都要通知）。" +
                "落到那个分支后面就说明主路径那处被删了 —— 剩下的只是权限回弹那处，" +
                "关掉开关时**不会**通知列表。",
            notifyAt < branchAt
        )
    }

    @Test
    fun `there are exactly two publish points and both are reachable`() {
        val source = SourceScan.stripped(TOGGLE_SERVICE)
        val body = SourceScan.functionBody(source, "private fun toggleWorkflowEnabled(")!!
        // 主路径（开关都要）+ 权限回弹（那是另一次数据变更）
        assertEquals(
            "通知点应恰为 2 处（主路径 + 权限回弹）",
            2,
            SourceScan.countOccurrences(body, "WorkflowDataChangeBus.notifyChanged()")
        )
    }

    @Test
    fun `the list screen subscribes to the signal`() {
        val source = SourceScan.stripped(LIST_ROUTE)
        assertTrue(
            // ★ 另一端：只发布、不订阅 = 磁贴发了也没人听。
            "WorkflowListRoute 必须订阅 WorkflowDataChangeBus.changes",
            source.contains("WorkflowDataChangeBus.changes.collect")
        )
        assertTrue(
            "订阅后必须真的重新读盘（不是只 bump 一个版本号 —— 那不会重新解析 prefs）",
            source.contains("loadData()")
        )
    }

    @Test
    fun `the publish point is deliberately NOT inside saveWorkflow`() {
        // ⚠️ 反向锁：`WorkflowManager.saveWorkflow` 是**所有**写入路径的汇聚点
        //    （列表页自己的开关、编辑器、AI、导入……）。在那里发布会让
        //    「列表页自己改自己」也绕一圈回来重新 `loadData()` ——
        //    虽然不会成环，但每次列表内开关都白付一趟读盘 + 一次 `setLoading(true)` 的闪。
        //
        //    ⇒ 这条断言锁的是**设计意图**，防止将来有人「顺手统一到汇聚点」。
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/workflow/WorkflowManager.kt"
        )
        assertFalse(
            "WorkflowManager 不得发布数据变更信号（会形成列表自己改自己还重读一圈）",
            source.contains("WorkflowDataChangeBus")
        )
    }

    @Test
    fun `the wiring scan is not vacuous`() {
        for (path in listOf(TOGGLE_SERVICE, LIST_ROUTE)) {
            val stripped = SourceScan.stripped(path)
            assertTrue("$path 剥注释后不应为空（防空转）", stripped.length > 500)
            assertTrue("$path 应含有真实的代码行", stripped.contains("fun "))
        }
    }
}
