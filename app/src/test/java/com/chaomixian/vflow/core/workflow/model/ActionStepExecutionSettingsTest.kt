package com.chaomixian.vflow.core.workflow.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 异常处理策略的**纯函数层**测试（fork，P0-4 静默终止 + P0-1 策略链）。
 *
 * 设计依据：`docs/fork/error-handling-extension-design.md` §2.1（策略模型）/ §2.3（决策 D3）。
 *
 * 为什么必须有：`STOP_SILENT` 的两端都是「改错了不报错、只静默变差」——
 *  - **漏抑制** ⇒ 用户被阻塞式弹窗打扰（还能忍）；
 *  - **抑制过头**（把别的策略也静默、或把通知/终态一起静默）⇒ 失败没有任何提示，
 *    与既有 `Workflow.silentExecution` 的「失败豁免」原则相悖，且**无人会立刻发现**。
 *
 * P0-1 的策略链同理：`__on_retry_exhausted` 的**缺省值写错 = 存量工作流行为漂移**
 * （原来「RETRY 耗尽必然终止」，若缺省被写成 `SKIP` 就会变成"悄悄继续跑"）。
 *
 * 本仓库无 Robolectric，`WorkflowExecutor` 起不来 ⇒ 判断被抽成纯函数后在这里钉住，
 * 接线点由 `StopSilentPolicyWiringTest` / `RetryExhaustedPolicyWiringTest`（源码扫描型）钉住。
 */
class ActionStepExecutionSettingsTest {

    @Test
    fun `策略常量必须两两不同`() {
        // ⚠️ 防复制粘贴：若 STOP_SILENT 被写成 "STOP"，一切都会静默退化成默认策略
        //    （不报错、不崩溃，只是弹窗照样弹 / 或该弹的不弹）。
        val policies = listOf(
            ActionStepExecutionSettings.POLICY_STOP,
            ActionStepExecutionSettings.POLICY_STOP_SILENT,
            ActionStepExecutionSettings.POLICY_SKIP,
            ActionStepExecutionSettings.POLICY_RETRY,
        )
        assertEquals("策略常量出现重复值：$policies", policies.size, policies.toSet().size)
        assertNotEquals(
            ActionStepExecutionSettings.POLICY_STOP,
            ActionStepExecutionSettings.POLICY_STOP_SILENT,
        )
    }

    @Test
    fun `STOP_SILENT 抑制弹窗`() {
        assertFalse(
            ActionStepExecutionSettings.shouldShowErrorDialog(
                policy = ActionStepExecutionSettings.POLICY_STOP_SILENT,
                showErrorDialog = true,
            ),
        )
    }

    @Test
    fun `其它策略一律不抑制弹窗`() {
        // 反向断言：防「顺手把别的策略也静默掉」。
        // 未知值同样必须按默认处理（弹窗），不能静默吞掉。
        listOf(
            ActionStepExecutionSettings.POLICY_STOP,
            ActionStepExecutionSettings.POLICY_SKIP,
            ActionStepExecutionSettings.POLICY_RETRY,
            "UNKNOWN_POLICY",
        ).forEach { policy ->
            assertTrue(
                "策略 $policy 不应抑制弹窗",
                ActionStepExecutionSettings.shouldShowErrorDialog(policy, showErrorDialog = true),
            )
        }
    }

    @Test
    fun `调用方总开关为 false 时任何策略都不弹窗`() {
        // Agent 调试路径（showErrorDialog = false）整段跳过，与步骤策略无关。
        listOf(
            ActionStepExecutionSettings.POLICY_STOP,
            ActionStepExecutionSettings.POLICY_STOP_SILENT,
            ActionStepExecutionSettings.POLICY_SKIP,
            ActionStepExecutionSettings.POLICY_RETRY,
        ).forEach { policy ->
            assertFalse(
                "showErrorDialog=false 时策略 $policy 也不应弹窗",
                ActionStepExecutionSettings.shouldShowErrorDialog(policy, showErrorDialog = false),
            )
        }
    }

    @Test
    fun `fromParameters 能读回 STOP_SILENT`() {
        val settings = ActionStepExecutionSettings.fromParameters(
            mapOf(
                ActionStepExecutionSettings.KEY_ERROR_POLICY
                    to ActionStepExecutionSettings.POLICY_STOP_SILENT
            )
        )
        assertEquals(ActionStepExecutionSettings.POLICY_STOP_SILENT, settings.policy)
        assertFalse(ActionStepExecutionSettings.shouldShowErrorDialog(settings.policy, true))
    }

    @Test
    fun `缺键时回落 STOP 且不抑制弹窗`() {
        // 向后兼容：存量工作流（无 __error_policy）行为必须逐字不变。
        val settings = ActionStepExecutionSettings.fromParameters(emptyMap())
        assertEquals(ActionStepExecutionSettings.POLICY_STOP, settings.policy)
        assertTrue(ActionStepExecutionSettings.shouldShowErrorDialog(settings.policy, true))
    }

    // ── P0-1 策略链（fork，2026-10-15）─────────────────────────────

    @Test
    fun `RETRY 用尽后按 onRetryExhausted 展开`() {
        // 这是 P0-1 的全部内容：RETRY 不再必然落 STOP。
        listOf(
            ActionStepExecutionSettings.POLICY_STOP,
            ActionStepExecutionSettings.POLICY_STOP_SILENT,
            ActionStepExecutionSettings.POLICY_SKIP,
        ).forEach { onExhausted ->
            assertEquals(
                "RETRY 用尽后应展开成 $onExhausted",
                onExhausted,
                ActionStepExecutionSettings.effectivePolicy(
                    ActionStepExecutionSettings.POLICY_RETRY,
                    onExhausted,
                ),
            )
        }
    }

    @Test
    fun `非 RETRY 策略不受 onRetryExhausted 影响`() {
        // 反向断言：防「顺手让所有策略都去查这个键」——
        // 例如 SKIP 被 onRetryExhausted=STOP 带跑，会从「跳过」变成「终止」。
        listOf(
            ActionStepExecutionSettings.POLICY_STOP,
            ActionStepExecutionSettings.POLICY_STOP_SILENT,
            ActionStepExecutionSettings.POLICY_SKIP,
            "UNKNOWN_POLICY",
        ).forEach { policy ->
            ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES.forEach { onExhausted ->
                assertEquals(
                    "策略 $policy 不应被用尽后动作 $onExhausted 改写",
                    policy,
                    ActionStepExecutionSettings.effectivePolicy(policy, onExhausted),
                )
            }
        }
    }

    @Test
    fun `缺省 onRetryExhausted 等于今天的行为`() {
        // ⚠️⚠️ 本测试是 P0-1 的**向后兼容硬约束**（设计稿 §2.1）：
        // 改动前 RETRY 耗尽后走的是「终止 + 弹窗」（WorkflowExecutor 失败分支原注释：
        // 「POLICY_STOP (默认) 或 重试耗尽」）⇒ 缺省必须仍是 STOP，
        // 否则存量工作流会在无人察觉的情况下从「失败即停」变成「失败继续跑」。
        val settings = ActionStepExecutionSettings.fromParameters(
            mapOf(ActionStepExecutionSettings.KEY_ERROR_POLICY to ActionStepExecutionSettings.POLICY_RETRY)
        )
        assertEquals(ActionStepExecutionSettings.POLICY_STOP, settings.onRetryExhausted)
        val effective = ActionStepExecutionSettings.effectivePolicy(
            settings.policy,
            settings.onRetryExhausted,
        )
        assertEquals(
            ActionStepExecutionSettings.POLICY_STOP,
            effective,
        )
        // 且该策略**仍然弹窗**（静默是用户显式选择的结果，不能被缺省值顺手打开）
        assertTrue(ActionStepExecutionSettings.shouldShowErrorDialog(effective, true))
    }

    @Test
    fun `fromParameters 能读回 onRetryExhausted`() {
        val settings = ActionStepExecutionSettings.fromParameters(
            mapOf(
                ActionStepExecutionSettings.KEY_ERROR_POLICY
                    to ActionStepExecutionSettings.POLICY_RETRY,
                ActionStepExecutionSettings.KEY_ON_RETRY_EXHAUSTED
                    to ActionStepExecutionSettings.POLICY_SKIP,
            )
        )
        assertEquals(ActionStepExecutionSettings.POLICY_SKIP, settings.onRetryExhausted)
        assertEquals(
            ActionStepExecutionSettings.POLICY_SKIP,
            ActionStepExecutionSettings.effectivePolicy(settings.policy, settings.onRetryExhausted),
        )
    }

    @Test
    fun `非法 onRetryExhausted 收敛到 STOP 而不是抛异常`() {
        // 工作流参数来自 JSON（旧版本 / 手改过的文件），未知值必须能跑；
        // 且只能往**更可见**的方向退化 —— 收敛到 SKIP 会让本该停下的工作流悄悄继续。
        listOf(null, "", "RETRY", "skip", "GARBAGE").forEach { raw ->
            assertEquals(
                "非法值 ${raw ?: "null"} 应收敛到 STOP",
                ActionStepExecutionSettings.POLICY_STOP,
                ActionStepExecutionSettings.normalizeOnRetryExhausted(raw),
            )
        }
    }

    @Test
    fun `用尽后动作的取值里没有 RETRY`() {
        // 「重试用尽后再重试」没有意义，且会把策略链绕成无限循环。
        assertFalse(
            ActionStepExecutionSettings.POLICY_RETRY in ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES
        )
        // 也不能把 4 种策略照搬过来（子集关系，防复制粘贴）
        assertTrue(
            ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES.toSet().size ==
                ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES.size
        )
    }

    @Test
    fun `用尽后动作的顺序即 UI 顺序`() {
        // ⚠️ 编辑器用 `optionsStringRes` 按 **index** 取文案（`bindDropdown`），
        // 所以这个 list 的顺序被 UI 依赖；调顺序必须同时调
        // `ActionEditorSheet` 里那三行字符串资源的顺序（由
        // `RetryExhaustedPolicyWiringTest` 的源码扫描守卫锁死）。
        assertEquals(
            listOf(
                ActionStepExecutionSettings.POLICY_STOP,
                ActionStepExecutionSettings.POLICY_STOP_SILENT,
                ActionStepExecutionSettings.POLICY_SKIP,
            ),
            ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES,
        )
    }

    // ── P0-5 跳过跳转目标（fork，2026-10-15）──────────────────────

    @Test
    fun `缺省不跳转（等于今天的行为）`() {
        // 向后兼容硬约束：没有 `__skip_jump_target` 时，跳过就是「继续下一步」，
        // 与改动前逐字相同。
        assertNull(ActionStepExecutionSettings.fromParameters(emptyMap()).skipJumpTarget)
    }

    @Test
    fun `读回跳转目标时去掉首尾空白`() {
        val settings = ActionStepExecutionSettings.fromParameters(
            mapOf(ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET to " cleanup ")
        )
        assertEquals("cleanup", settings.skipJumpTarget)
    }

    @Test
    fun `空白与非字符串的跳转目标一律当没设置`() {
        // ⚠️ 空串会被执行期当成一个（永远解析不到的）目标 id ⇒ 每次失败都多一条
        //    「目标不可用」的 W 日志，把真正的降级原因埋掉。
        listOf<Any?>("", "   ", 42, null, listOf("a")).forEach { raw ->
            assertNull(
                "非法目标 ${raw ?: "null"} 应视为没设置",
                ActionStepExecutionSettings.fromParameters(
                    mapOf(ActionStepExecutionSettings.KEY_SKIP_JUMP_TARGET to raw)
                ).skipJumpTarget,
            )
        }
    }
}
