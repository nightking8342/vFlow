package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.ActionStepExecutionSettings
import com.chaomixian.vflow.core.workflow.module.system.DelayModule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **保留的步骤级设置键必须被 AI 侧认识**（fork，2026-10-15）。
 *
 * ## 这条测试补的是哪个洞
 *
 * `resolveModuleInputDefinitions` 是「模型看得见的字段」与「执行时收下的字段」的**共用口径**。
 * 保留键（`__error_policy` 等）不由模块声明 ⇒ 不注入的话 `buildParameters` /
 * `applyParameterPatch` 会把它们判成 unknown 并**拒绝整个调用**
 * （`update_workflow` 甚至整份补丁不落地）。
 *
 * 修补前的实际后果：**AI 完全无法配置异常处理策略** —— 只能读，不能写；用户问「让某步失败时静默停止」
 * 时模型只会得到一个工具调用失败。这条测试把这个能力钉住。
 *
 * 纯 JVM 可跑：`DelayModule()` 的 `getInputs()` / `getDynamicInputs()` 不依赖 Context，
 * `ActionStep` 是纯数据类。
 */
class ReservedStepKeyInjectionTest {

    private val settingsPath =
        "src/main/java/com/chaomixian/vflow/core/workflow/model/ActionStepExecutionSettings.kt"

    /** 取一个**非触发器**模块 —— 触发器那条路早就有注入，回归风险都在普通模块上。 */
    private fun nonTriggerDefinitions() = resolveModuleInputDefinitions(
        DelayModule(),
        ActionStep(moduleId = "vflow.device.delay", parameters = emptyMap()),
    )

    @Test
    fun `普通模块的步骤也接受保留的步骤级设置键`() {
        val ids = nonTriggerDefinitions().map { it.id }.toSet()

        listOf(
            ActionStepExecutionSettings.KEY_ERROR_POLICY,
            ActionStepExecutionSettings.KEY_RETRY_COUNT,
            ActionStepExecutionSettings.KEY_RETRY_INTERVAL,
        ).forEach { key ->
            assertTrue(
                "AI 侧不认识保留键 `$key` ⇒ buildParameters / applyParameterPatch 会判它 unknown 并" +
                    "**拒绝整个调用** ⇒ AI 配不了异常处理策略。",
                key in ids,
            )
        }
    }

    @Test
    fun `策略键是 ENUM 且列出全部策略取值`() {
        val policy = nonTriggerDefinitions()
            .single { it.id == ActionStepExecutionSettings.KEY_ERROR_POLICY }

        assertEquals(ParameterType.ENUM, policy.staticType)

        // 自动枚举 POLICY_ 常量：将来加第 5 种策略时，漏了这里会让模型「选不到」它
        //（schema 里没有该取值），而人工在编辑器里能选 —— 口径分裂且不报错。
        val declared = Regex("""const val (POLICY_[A-Z_]+)\s*=\s*"([^"]+)"""")
            .findAll(SourceScan.stripped(settingsPath))
            .map { it.groupValues[2] }
            .toSet()
        assertTrue("没能从 $settingsPath 解析出任何 POLICY_ 常量，扫描失效", declared.size >= 4)
        assertEquals(
            "策略枚举取值必须与 ActionStepExecutionSettings 的 POLICY_* 常量一致",
            declared,
            policy.options.toSet(),
        )
    }

    @Test
    fun `保留键是隐藏的（不进 per-tool JSON Schema）`() {
        // 直接调用（call_module）没有「步骤」这一层，这些键对它没有意义；
        // 而 query_module_schema 的 inputs 段不按 isHidden 过滤 ⇒ 模型仍能发现它们。
        nonTriggerDefinitions()
            .filter { it.id.startsWith("__") }
            .forEach { assertTrue("`${it.id}` 应为 isHidden = true", it.isHidden) }
    }

    @Test
    fun `用尽后动作键是 ENUM 且取值与常量表一致`() {
        // P0-1 策略链：模型必须能选到全部「用尽后动作」，
        // 且**不能**选到 RETRY（重试用尽后再重试没有意义，会把策略链绕成无限循环）。
        val onExhausted = nonTriggerDefinitions()
            .single { it.id == ActionStepExecutionSettings.KEY_ON_RETRY_EXHAUSTED }

        assertEquals(ParameterType.ENUM, onExhausted.staticType)
        assertEquals(
            "AI 侧可选值必须与 ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES 一致 —— " +
                "少一个 ⇒ 模型选不到（schema 里没有）；多一个 ⇒ 模型能写出执行器不认的值。",
            ActionStepExecutionSettings.ON_RETRY_EXHAUSTED_CHOICES.toSet(),
            onExhausted.options.toSet(),
        )
        assertFalse(
            "用尽后动作里不能有 RETRY。",
            ActionStepExecutionSettings.POLICY_RETRY in onExhausted.options,
        )
    }

    @Test
    fun `新增保留键时注入表必须同步（自动枚举守卫）`() {
        // 从源码解析出**所有** KEY_ 常量的字面量并逐个要求注入 ——
        // 将来 P0-1 加 `__on_retry_exhausted`、P0-5 加 `__skip_jump_target` 时，
        // 漏了注入会在这里变红，而不是等到模型调用被拒（那时错误信息只会在对话里一闪而过）。
        val declaredKeys = Regex("""const val (KEY_[A-Z_]+)\s*=\s*"([^"]+)"""")
            .findAll(SourceScan.stripped(settingsPath))
            .map { it.groupValues[2] }
            .toSet()
        assertTrue("没能从 $settingsPath 解析出任何 KEY_ 常量，扫描失效", declaredKeys.isNotEmpty())

        val injected = nonTriggerDefinitions().map { it.id }.toSet()
        val missing = declaredKeys - injected
        assertTrue(
            "这些保留键没有注入 AI 侧定义表：$missing —— 模型写它们会被判 unknown 并拒绝整个调用。",
            missing.isEmpty(),
        )
    }
}
