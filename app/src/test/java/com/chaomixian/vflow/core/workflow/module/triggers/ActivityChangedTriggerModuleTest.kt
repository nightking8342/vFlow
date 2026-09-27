package com.chaomixian.vflow.core.workflow.module.triggers

import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.permissions.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `activity_changed` 模块的**声明体检**。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.1；
 * 触发器规范见 `surveys/trigger-system-overview.md` §9.1。
 *
 * ## ⚠️ 这类测试存在的理由
 *
 * 触发器模块的**声明**（id / 分类 / 权限 / 输出）写错了**不会有任何报错** ——
 * 表现为「配不了」「配了不触发」「输出取不到值」。
 * 本仓库因此在 `SimDataSwitch` 上专门写了同类的体检测试。
 */
class ActivityChangedTriggerModuleTest {

    private val module = ActivityChangedTriggerModule()

    @Test
    fun `id has the trigger prefix`() {
        // ⚠️ `vflow.trigger.` 前缀是**必须的** —— 系统据此前缀与
        // metadata.categoryId 一起判定它是触发器（两者缺一不可）
        assertEquals("vflow.trigger.activity_changed", module.id)
        assertTrue(module.id.startsWith("vflow.trigger."))
    }

    @Test
    fun `metadata declares the trigger category`() {
        // ⚠️ categoryId 与 id 前缀缺一不可：只有 id 前缀而无 categoryId
        // 会让它不出现在「触发器」分类下
        assertEquals("trigger", module.metadata.categoryId)
        assertEquals("触发器", module.metadata.category)
    }

    @Test
    fun `declares the Xposed capability permission`() {
        // ⚠️⚠️ 这条是**必需的**，而且很容易被「优化掉」。
        //
        // `TriggerService.handleWorkflowChanged` 会在注册前检查权限，
        // 缺失时**静默把整个工作流置为 isEnabled=false**。
        // 所以「不声明权限」不等于「零权限可跑」，而是「配上就静默失效」。
        //
        // 本仓库在 SimDataSwitch 上踩过同一个坑（初版误判为「零权限」，
        // 在权限齐全的设备上测不出来 —— 属最坏的一类 bug）。
        assertTrue(
            "必须声明 XPOSED_HOOK，否则 TriggerService 会静默禁用整个工作流",
            module.requiredPermissions.contains(PermissionManager.XPOSED_HOOK),
        )
    }

    @Test
    fun `xposed permission has a registered strategy not falling back to runtime`() {
        // ⚠️⚠️ 同样要紧：`PermissionManager.strategies` 是**不可变 map**，
        // 没登记的权限会让 `isGranted` **回落到 runtimeStrategy** ⇒ 恒判「缺权限」
        // ⇒ 又是静默禁用整个工作流。
        //
        // 所以「加了权限常量」还不够，**必须同时加 strategy**。
        // 这里通过反射确认 strategy 表里真的有这一项 ——
        // 只加常量不加 strategy 是这个改动最容易漏的一半。
        val field = PermissionManager::class.java.getDeclaredField("strategies").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val strategies = field.get(PermissionManager) as Map<String, *>

        assertTrue(
            "XPOSED_HOOK 必须登记 strategy，否则 isGranted 会回落到 runtimeStrategy 并恒判缺权限",
            strategies.containsKey(PermissionManager.XPOSED_HOOK.id),
        )
    }

    @Test
    fun `xposed permission is a special capability not a runtime permission`() {
        // 它判的是「LSPosed 里勾没勾」这一外部前提，不是运行时权限对话框
        assertEquals(
            com.chaomixian.vflow.permissions.PermissionType.SPECIAL,
            PermissionManager.XPOSED_HOOK.type,
        )
    }

    @Test
    fun `inputs are all declared with stable ids`() {
        val inputs = module.getInputs()
        val ids = inputs.map { it.id }.toSet()
        assertEquals(
            setOf("package_filter", "class_filter", "match_mode", "cooldown_ms"),
            ids,
        )
        // 参数 key 必须是 snake_case（仓库代码风格约定）
        ids.forEach { id ->
            assertTrue("参数 key `$id` 应为 snake_case", id.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `match_mode default is contains and options are stable constants`() {
        val mode = module.getInputs().first { it.id == "match_mode" }
        assertEquals(ActivityChangedTriggerModule.MATCH_CONTAINS, mode.defaultValue)
        assertEquals(ParameterType.ENUM, mode.staticType)
        // ⚠️ options 里存的必须是**稳定常量**，不能是本地化文案 ——
        // 否则切语言后已保存的工作流失配
        assertEquals(
            listOf(
                ActivityChangedTriggerModule.MATCH_CONTAINS,
                ActivityChangedTriggerModule.MATCH_EXACT,
            ),
            mode.options,
        )
    }

    @Test
    fun `cooldown default is positive and it is not a magic variable`() {
        val cd = module.getInputs().first { it.id == "cooldown_ms" }
        assertEquals(ParameterType.NUMBER, cd.staticType)
        val default = cd.defaultValue as? Number
        assertNotNull(default)
        // ⚠️ 默认必须 > 0：activityResumedLocked 每次 resume 都触发
        //（含返回、锁屏解锁、同 Activity 重入），默认不冷却会瞬间刷爆工作流
        assertTrue("冷却默认值应大于 0，实际=$default", default!!.toLong() > 0L)
    }

    @Test
    fun `outputs match the trigger data fields`() {
        val outputs = module.getOutputs(null).map { it.id }.toSet()
        assertEquals(
            setOf("package_name", "class_name", "component", "intent_uri", "extras_json", "truncated"),
            outputs,
        )
    }

    @Test
    fun `truncated output is a boolean so users can detect partial extras`() {
        // ⚠️ 载荷可能因 Binder 事务上限被截断。不把它暴露出来的话，
        // 「extras 少了几个键」会被当成「那个 App 本来就没传」
        val truncated = module.getOutputs(null).first { it.id == "truncated" }
        assertEquals(VTypeRegistry.BOOLEAN.id, truncated.typeName)
    }

    @Test
    fun `extras_json is exposed as a single string not per-key outputs`() {
        // ⚠️ extras 的键**不可枚举**（任何 App 都能塞任意键），
        // 所以只给一个 JSON 字符串，由下游用 JSON 模块解析。
        // 为每个键建输出是做不到的
        val extras = module.getOutputs(null).first { it.id == "extras_json" }
        assertEquals(VTypeRegistry.STRING.id, extras.typeName)
    }

    @Test
    fun `legacy localized match values are mapped`() {
        val mode = module.getInputs().first { it.id == "match_mode" }
        val legacy = mode.legacyValueMap
        assertNotNull("应提供旧值兼容映射", legacy)
        assertEquals(ActivityChangedTriggerModule.MATCH_EXACT, legacy!!["精确匹配"])
        assertEquals(ActivityChangedTriggerModule.MATCH_CONTAINS, legacy["包含"])
    }
}
