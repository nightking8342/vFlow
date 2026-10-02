package com.chaomixian.vflow.core.workflow.module.xposed

import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ModuleCategories
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.xposed.CapabilityFallbacks
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.permissions.PermissionType
import com.chaomixian.vflow.xposed.capability.CapabilityNames
import com.chaomixian.vflow.xposed.script.ScriptExecutor
import com.chaomixian.vflow.xposed.capability.CapabilityRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `vflow.xposed.js` 模块的**声明体检**。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §5.7。
 *
 * ## ⚠️ 这类测试存在的理由
 *
 * 模块的**声明**写错了**不会有任何报错** —— 表现为「配不了」「配了静默失效」
 * 「下游取不到值」。本仓库在 `SimDataSwitch` 上正是因为「初版误判为零权限」
 * 踩过一次（权限齐全的设备上**测不出来**，属最坏的一类 bug）。
 * 形态照 `ActivityChangedTriggerModuleTest`。
 */
class XposedJsModuleTest {

    private val module = XposedJsModule()

    // ════════════════════ 身份 ════════════════════

    @Test
    fun `id is stable and uses the xposed channel prefix`() {
        // ⚠️ id 一经发布不改（已保存的工作流按它索引）。
        // `xposed` 是**通道名**（与 shizuku / core 同级），不是厂商名。
        assertEquals("vflow.xposed.js", module.id)
    }

    @Test
    fun `metadata declares the xposed category`() {
        assertEquals("xposed", module.metadata.categoryId)
        assertEquals("xposed", module.metadata.getResolvedCategoryId())
    }

    @Test
    fun `the xposed category is registered in ModuleCategories`() {
        // ⚠️⚠️ 只在 metadata 写 categoryId 是**不够**的：
        //   · getSortOrder 对未登记分类返回 Int.MAX_VALUE（排最后）
        //   · getLocalizedLabel 回落 defaultLabel（= categoryId，显示成小写 "xposed"）
        // ⇒ spec 必须存在，这一条锁住它。
        val spec = ModuleCategories.getSpec(ModuleCategories.XPOSED)
        assertNotNull("ModuleCategories 里必须有 xposed 的 spec", spec)
        assertEquals("Xposed", spec!!.defaultLabel)
        assertNotNull("分类文案必须有资源（否则显示成小写 id）", spec.labelRes)
    }

    @Test
    fun `the xposed category sort order does not collide with existing ones`() {
        // 反向断言：取 15 = 既有最大值 14 + 1，**不重排任何既有分类**
        val orders = ModuleCategories.allSpecs().map { it.sortOrder }
        assertEquals("分类 sortOrder 不得重复", orders.size, orders.toSet().size)
        assertEquals(15, ModuleCategories.getSortOrder(ModuleCategories.XPOSED))
    }

    // ════════════════════ 权限 ════════════════════

    @Test
    fun `declares the Xposed capability permission`() {
        // ⚠️⚠️ 漏声明会让权限体系判为「缺权限」—— 而在触发器那条路径上
        // `TriggerService` 会**静默把整个工作流置为 isEnabled=false**。
        assertTrue(
            "必须声明 XPOSED_HOOK",
            module.getRequiredPermissions(null).contains(PermissionManager.XPOSED_HOOK),
        )
    }

    @Test
    fun `the xposed permission is a special capability with a registered strategy`() {
        // 与 ActivityChangedTriggerModuleTest 同款双保险：
        // 只加常量不加 strategy ⇒ isGranted 回落 runtimeStrategy ⇒ 恒判缺权限。
        assertEquals(PermissionType.SPECIAL, PermissionManager.XPOSED_HOOK.type)

        val field = PermissionManager::class.java.getDeclaredField("strategies").apply {
            isAccessible = true
        }
        @Suppress("UNCHECKED_CAST")
        val strategies = field.get(PermissionManager) as Map<String, *>
        assertTrue(
            "XPOSED_HOOK 必须登记 strategy，否则 isGranted 恒判缺权限",
            strategies.containsKey(PermissionManager.XPOSED_HOOK.id),
        )
    }

    // ════════════════════ AI 元数据（V2.0 §5.7 安全边界）════════════════════

    @Test
    fun `ai usage scope is temporary workflow only`() {
        // ⚠️⚠️ 这是**安全边界**，不是偏好设置：
        // `DIRECT_TOOL` 意味着 AI 可以**未经人审**直接往 system_server 里投脚本，
        // 而崩溃半径是**整机**。
        assertEquals(
            setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
            module.aiMetadata.usageScopes,
        )
        assertTrue(
            "绝不能给 DIRECT_TOOL —— AI 不得未经人审就写 system_server 脚本",
            !module.aiMetadata.usageScopes.contains(AiModuleUsageScope.DIRECT_TOOL),
        )
    }

    @Test
    fun `ai risk level is high so it goes through approval`() {
        assertEquals(AiModuleRiskLevel.HIGH, module.aiMetadata.riskLevel)
    }

    @Test
    fun `ai description states the division of labour with the app-process JS module`() {
        // 前提 3：hints / 描述必须写清与 `vflow.system.js` 的分工，
        // 否则 AI 会把它当成「换个地方跑的 JS 模块」，进而写出调 vflow.* 的脚本
        //（那些脚本在本模块里一律 ReferenceError）。
        val hints = module.aiMetadata.inputHints
        assertTrue("应有 script 的 hint", hints.containsKey("script"))
        assertTrue("应有 inputs 的 hint", hints.containsKey("inputs"))
        assertTrue("应有 timeout_ms 的 hint（本模块独有的参数）", hints.containsKey("timeout_ms"))
        assertTrue(
            "script 的 hint 必须点明没有 vflow 模块树",
            hints.getValue("script").contains("vflow"),
        )
        assertTrue(
            "workflowStepDescription 必须点明与 vflow.system.js 的分工",
            module.aiMetadata.workflowStepDescription!!.contains("vflow.system.js"),
        )
    }

    @Test
    fun `script is the only required input`() {
        assertEquals(setOf("script"), module.aiMetadata.requiredInputIds)
    }

    // ════════════════════ 输入 / 输出契约 ════════════════════

    @Test
    fun `inputs are exactly script inputs timeout_ms with snake_case ids`() {
        val ids = module.getInputs().map { it.id }.toSet()
        assertEquals(setOf("script", "inputs", "timeout_ms"), ids)
        ids.forEach { id ->
            assertTrue("参数 key `$id` 应为 snake_case", id.matches(Regex("[a-z][a-z0-9_]*")))
        }
    }

    @Test
    fun `timeout default and type match the clamp contract`() {
        val timeout = module.getInputs().first { it.id == "timeout_ms" }
        assertEquals(ParameterType.NUMBER, timeout.staticType)
        assertEquals(DEFAULT_TIMEOUT_MS, timeout.defaultValue)
        assertNotNull("timeout_ms 必须带 hint 说明钳位规则", timeout.hintStringRes)
        assertNotNull("timeout_ms 必须有本地化名", timeout.nameStringRes)
    }

    @Test
    fun `the timeout bounds are written into the label not only the hint`() {
        // ⚠️⚠️ 这条锁的是**用户在编辑器里能不能看见约束**。
        //
        // `timeout_ms` 走自动表单，而它的 `hint` 在自动表单里只是**输入框占位符**
        //（`StandardControlFactory.createTextInputLayout(hint = …)`）——
        // 字段**预填了 5000** ⇒ 占位符**永远不显示**。
        // ⇒ 「默认 5000、上限 30000」若只写在 hint 里，用户既看不到上限、
        //   也可能以为它是必填项。
        //
        // 故约束**必须在标签里**。三语标签都查（漏一种语言就会有一批用户看不到）。
        val inputs = module.getInputs().first { it.id == "timeout_ms" }
        assertTrue(
            "位置参 name（fallback）必须含默认值与上限",
            (inputs.name.contains("5000") && inputs.name.contains("30000")),
        )

        val nameResId = inputs.nameStringRes!!
        for (dir in listOf("values", "values-en", "values-ja")) {
            val xml = readStringsModule(dir)
            val line = xml.lineSequence().firstOrNull { it.contains("param_vflow_xposed_js_timeout_name") }
            assertNotNull("$dir 缺 param_vflow_xposed_js_timeout_name", line)
            assertTrue(
                "$dir 的超时标签必须含默认值 5000 与上限 30000（只写 hint 用户看不到）",
                line!!.contains("5000") && line.contains("30000"),
            )
        }
        assertTrue("nameStringRes 必须真的被声明", nameResId != 0)
    }

    private fun readStringsModule(dir: String): String {
        val candidates = listOf(
            File("app/src/main/res/$dir/strings_module.xml"),
            File("src/main/res/$dir/strings_module.xml"),
        )
        return candidates.firstOrNull { it.exists() }?.readText()
            ?: error("找不到 $dir/strings_module.xml")
    }

    @Test
    fun `script default example does not reference the vflow module tree`() {
        // ⚠️⚠️ 照抄 JsModule 的默认脚本（它调 `vflow.device.toast(...)`）会**误导用户** ——
        // 在本模块里那是 ReferenceError。示例必须体现「没有 vflow.*」。
        val script = module.getInputs().first { it.id == "script" }
        val example = script.defaultValue as String
        assertTrue(
            "默认示例不得调用 vflow.*（本模块没有模块树）",
            !example.contains("vflow.") || example.contains("没有"),
        )
        assertEquals(ParameterType.STRING, script.staticType)
    }

    @Test
    fun `outputs is a single dictionary named outputs`() {
        val outputs = module.getOutputs(null)
        assertEquals(1, outputs.size)
        val outputsDef = outputs.single()
        assertEquals("outputs", outputsDef.id)
        assertEquals(VTypeRegistry.DICTIONARY.id, outputsDef.typeName)
        // ⚠️ 本地化必须走具名参数 nameStringRes —— 第二位置参是**字面量** name
        assertNotNull("输出名必须有资源（否则中文会漏到其他语言）", outputsDef.nameStringRes)
    }

    // ════════════════════ 图标 / UIProvider ════════════════════

    @Test
    fun `icon differs from the app process JS module`() {
        // ⚠️ 两个模块名字里都含 "JavaScript"，图标一样的话用户在编辑器里分不清
        // 「这个脚本跑在 App 进程还是系统进程」—— 而那正是本模块存在意义的全部。
        val jsModule = com.chaomixian.vflow.core.workflow.module.system.JsModule()
        assertTrue(
            "不能与 vflow.system.js 共用图标",
            module.metadata.iconRes != jsModule.metadata.iconRes,
        )
    }

    @Test
    fun `shares the JS editor provider with the app process module`() {
        // 取舍 1：复用 `JsModuleUIProvider`（零改动上游文件）。
        // 两者的 script / inputs 语义本就设计为一致，故这是**有意的**耦合。
        val jsModule = com.chaomixian.vflow.core.workflow.module.system.JsModule()
        assertSame(
            jsModule.uiProvider!!::class.java,
            module.uiProvider!!::class.java,
        )
    }

    @Test
    fun `timeout_ms is not handled by the ui provider so it goes to the generic form`() {
        // ⚠️ 这是 `timeout_ms` 的 UI 归属的**机器化锁**：
        // `ActionEditorUiModel` 按 `getHandledInputIds()` 把字段从自动表单过滤掉 ⇒
        // 不在 handledIds 里的 `timeout_ms` 由**自动表单**渲染。
        // 若将来有人把它加进 handledIds 却不实现渲染，它会**从编辑器里消失**。
        val handled = module.uiProvider!!.getHandledInputIds()
        assertEquals(setOf("script", "inputs"), handled)
        assertTrue("timeout_ms 必须由自动表单渲染", !handled.contains("timeout_ms"))
    }

    // ════════════════════ 能力注册（③ 的闭环）════════════════════

    @Test
    fun `xposed_js capability is registered as exclusive and high risk`() {
        CapabilityFallbacks.resetForTest()
        CapabilityRegistry.resetForTest()
        try {
            CapabilityFallbacks.registerAll()

            val cap = CapabilityRegistry.find(CapabilityNames.XPOSED_JS)
            assertNotNull("CapabilityFallbacks 必须注册 xposed_js", cap)
            // 独占型：没有替代实现（UID 1000 是别的通道给不了的）
            assertNull("xposed_js 必须是独占型（fallback = null）", cap!!.fallback)
            assertEquals(
                com.chaomixian.vflow.xposed.capability.CapabilityRisk.HIGH,
                cap.risk,
            )
            // ⚠️ timeoutMs 必须留 null ⇒ 用请求里由用户配、模块钳位后的值。
            // 两端都声明会造成「App 配 30 秒、hook 按小值算」的错配。
            assertNull("timeoutMs 必须留 null，由请求携带", cap.timeoutMs)
            assertEquals(64 * 1024, cap.maxResultBytes)
        } finally {
            CapabilityFallbacks.resetForTest()
            CapabilityRegistry.resetForTest()
        }
    }

    // ════════════════════ 注册表接线 ════════════════════

    @Test
    fun `module is registered under the xposed category`() {
        // ⚠️ 源码扫描型接线锁（形态照 CoreDexFingerprintTest）：
        // ModuleRegistry 是 object，直接断言「按 id 能查到」需要 Context，
        // 故这里锁「注册行真的写了」+「分类解析落在 xposed」。
        val source = registrySource()
        assertTrue(
            "ModuleRegistry 必须注册 XposedJsModule",
            source.contains("register(XposedJsModule(), context)"),
        )
        assertTrue(
            "ModuleRegistry 必须 import xposed 包",
            source.contains("import com.chaomixian.vflow.core.workflow.module.xposed.*"),
        )
        assertEquals("xposed", module.metadata.getResolvedCategoryId())
    }

    @Test
    fun `the registration line was appended without reordering existing ones`() {
        // 反向断言（防空转）：注册行必须**在 Shizuku 段之后** ——
        // 本 fork 的纪律是「追加，不重排既有注册」，
        // 重排会让每次上游合并都在这个文件解冲突。
        val source = registrySource()
        val shizukuIdx = source.indexOf("// Shizuku 模块")
        val xposedIdx = source.indexOf("register(XposedJsModule(), context)")
        assertTrue("Shizuku 段落应存在", shizukuIdx >= 0)
        assertTrue("xposed 注册应在 Shizuku 段之后（追加而非重排）", xposedIdx > shizukuIdx)
    }

    @Test
    fun `the module is a BaseModule so the registry injects its context`() {
        // getLocalizedName / getLocalizedDescription 需要 appContext 注入
        // （ModuleRegistry.register 只对 BaseModule 注入）
        assertTrue(module is BaseModule)
    }

    @Test
    fun `the default script actually runs`() {
        // ⚠️⚠️ 这条用例存在的理由（**不是**凑数，别删）：
        // 首版默认脚本末行写的是**顶层 `return { sum: 1 + 1 };`**，
        // 而 Rhino 在脚本顶层遇到 `return` 是**解析期错误**（脚本一行都不执行），
        // 报 `脚本错误（第 9 行第 7 列）：返回的值无效`。
        // ⇒ 用户「新建模块 → 直接点运行」**必然失败**，且报错信息让人以为是自己的脚本有问题。
        //
        // 当时 41 例单测全绿却没抓到它 —— 因为它们测的是「纯函数」与「元数据声明」，
        // **没有一条真的把默认脚本跑一遍**。这条补上那个缺口。
        //
        // ⚠️ **必须从 `getInputs()` 取 `defaultValue`**，不要在测试里再抄一份字面量：
        // 抄一份的话，改了默认脚本而忘了改测试，两边各自漂移、测试照样绿 —— 那就白测了。
        val script = module.getInputs().first { it.id == "script" }.defaultValue as String

        val outcome = ScriptExecutor.run(
            script = script,
            inputs = emptyMap(),
            context = null,
            budgetMs = 5_000,
            maxResultBytes = 64 * 1024,
        )

        val ok = outcome as? ScriptExecutor.Outcome.Ok
            ?: error("默认脚本必须能正常执行完，实际结果：$outcome（脚本内容：$script）")
        // 默认脚本承诺的语义是「跑出一个 sum = 2 的字典」——
        // 断言它，而不是只断言「没报错」（否则把脚本改成空串也能过）。
        assertEquals(
            "默认脚本应产出 sum=2（脚本内容：$script）",
            2,
            (ok.outputs["sum"] as? Number)?.toInt(),
        )
    }

    private fun registrySource(): String {
        val candidates = listOf(
            File("app/src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"),
            File("src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"),
        )
        val file = candidates.firstOrNull { it.exists() }
            ?: error("找不到 ModuleRegistry.kt，候选路径：${candidates.map { it.absolutePath }}")
        return file.readText()
    }
}
