package com.chaomixian.vflow.core.workflow.model

import com.chaomixian.vflow.core.execution.ExecutionLogLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「工作流日志等级」的纯函数层。
 *
 * 设计文档：`docs/fork/workflow-log-level-design.md`。
 *
 * 覆盖三类「改错了不报错、只静默变差」的地方：
 * 1. **档位语义**（4 档 × 4 级 = 16 个组合逐一验，防「边界差一」）
 * 2. **报错永不丢失**（W/E 在任何档位下都不得被丢）
 * 3. **未知值回落 VERBOSE**（方向必须是「不丢信息」，不是「更保守」）
 */
class WorkflowLogLevelTest {

    private val allLevels = ExecutionLogLevel.entries.toList()

    private fun allowed(level: WorkflowLogLevel): Set<ExecutionLogLevel> =
        allLevels.filter { level.allows(it) }.toSet()

    // ── 1. 四档语义 ─────────────────────────────────────────────

    @Test
    fun `verbose keeps everything`() {
        assertEquals(
            "详细档必须保留全部四级 —— 它就是「改动前的行为」",
            allLevels.toSet(),
            allowed(WorkflowLogLevel.VERBOSE),
        )
    }

    @Test
    fun `normal drops only debug`() {
        assertEquals(
            "精简档只去掉 D（步骤切换 + 模块进度）",
            setOf(ExecutionLogLevel.INFO, ExecutionLogLevel.WARN, ExecutionLogLevel.ERROR),
            allowed(WorkflowLogLevel.NORMAL),
        )
    }

    @Test
    fun `warning keeps warn and error`() {
        assertEquals(
            "⚠️ 边界：WARNING 档必须**留下 W 自己**（`>=` 而非 `>`）",
            setOf(ExecutionLogLevel.WARN, ExecutionLogLevel.ERROR),
            allowed(WorkflowLogLevel.WARNING),
        )
    }

    @Test
    fun `error keeps only error`() {
        assertEquals(
            setOf(ExecutionLogLevel.ERROR),
            allowed(WorkflowLogLevel.ERROR),
        )
    }

    // ── 2. 报错永不丢失（本轮设计的底线）────────────────────────

    @Test
    fun `error level survives every tier`() {
        WorkflowLogLevel.entries.forEach { tier ->
            assertTrue(
                "$tier 把 ERROR 级日志丢掉了 —— 那是失败定位的唯一依据",
                tier.allows(ExecutionLogLevel.ERROR),
            )
        }
    }

    @Test
    fun `warn level survives every tier except the error-only one`() {
        WorkflowLogLevel.entries
            .filter { it != WorkflowLogLevel.ERROR }
            .forEach { tier ->
                assertTrue(
                    "$tier 把 WARN 级日志丢掉了 —— 跳过错误、重试、循环异常都走这个级别",
                    tier.allows(ExecutionLogLevel.WARN),
                )
            }
        // 而「仅错误」档刻意丢掉 W —— 那是它的定义，不是缺陷。
        assertFalse(WorkflowLogLevel.ERROR.allows(ExecutionLogLevel.WARN))
    }

    // ── 3. 档位单调性（防「档位名字与行为对不上」）────────────────

    @Test
    fun `more restrictive tiers allow a strict subset`() {
        val ordered = listOf(
            WorkflowLogLevel.VERBOSE,
            WorkflowLogLevel.NORMAL,
            WorkflowLogLevel.WARNING,
            WorkflowLogLevel.ERROR,
        )
        ordered.zipWithNext().forEach { (looser, tighter) ->
            assertTrue(
                "$tighter 允许的级别不是 $looser 的子集 —— 「越往右越安静」这条不成立了",
                allowed(tighter).all { it in allowed(looser) },
            )
        }
    }

    // ── 4. 解析：未知值必须回落 VERBOSE ─────────────────────────

    @Test
    fun `known storage values round-trip`() {
        WorkflowLogLevel.entries.forEach { tier ->
            assertEquals(tier, WorkflowLogLevel.fromStoredValue(tier.storageValue))
        }
    }

    @Test
    fun `case and whitespace are tolerated`() {
        assertEquals(WorkflowLogLevel.ERROR, WorkflowLogLevel.fromStoredValue("  ERROR  "))
    }

    @Test
    fun `unknown and blank values fall back to verbose`() {
        // ⚠️ 方向是刻意的：回落「不丢信息」的那一档，而不是「更保守」的那一档。
        //    日志是排障的唯一依据 —— 多记几条的代价远小于「故障时没有线索」。
        listOf(null, "", "   ", "silent", "OFF", "verbose2").forEach { raw ->
            assertEquals(
                "未知值 `$raw` 应回落 VERBOSE（否则老工作流会静默丢日志）",
                WorkflowLogLevel.VERBOSE,
                WorkflowLogLevel.fromStoredValue(raw),
            )
        }
    }

    // ── 5. 存储值本身 ───────────────────────────────────────────

    @Test
    fun `storage values are stable and distinct`() {
        // ⚠️ 这些字符串会**落盘**。改了它们 = 存量工作流的等级全部失配
        //    （回落 VERBOSE，用户会看到「我设的仅错误没生效」）。
        assertEquals(
            listOf("verbose", "normal", "warning", "error"),
            WorkflowLogLevel.entries.map { it.storageValue },
        )
    }
}
