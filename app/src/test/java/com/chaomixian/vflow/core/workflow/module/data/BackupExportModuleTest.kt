// 文件: test/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportModuleTest.kt
package com.chaomixian.vflow.core.workflow.module.data

import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ModuleCategories
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.permissions.PermissionManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `vflow.data.export_backup` 模块的**声明体检** + 摘要不回显口令。
 *
 * 形态照 `XposedJsModuleTest`（模块的**声明**写错了不会有任何报错 ——
 * 表现为「配不了」「配了静默失效」「下游取不到值」）。
 */
class BackupExportModuleTest {

    private val module = BackupExportModule()

    private fun readStringsModule(dir: String): String {
        val f = File("src/main/res/$dir/strings_module.xml")
        assertTrue("找不到 $dir/strings_module.xml", f.isFile)
        return f.readText()
    }

    // ════════════════════ 身份与分类 ════════════════════

    @Test
    fun `id is stable`() {
        // ⚠️ id 一经发布不改（已保存的工作流按它索引）。
        assertEquals("vflow.data.export_backup", module.id)
    }

    @Test
    fun `metadata declares the data category and it is registered`() {
        assertEquals("data", module.metadata.categoryId)
        assertEquals("data", module.metadata.getResolvedCategoryId())
        // ⚠️ 只在 metadata 写 categoryId 不够：未登记分类会被排到最后且标签回落成小写 id
        assertNotNull("ModuleCategories 里没有 data 分类", ModuleCategories.getSpec("data"))
    }

    @Test
    fun `it is a BaseModule so the registry injects its context`() {
        // getLocalizedName / getSummary 需要 appContext 注入
        assertTrue(module is BaseModule)
    }

    // ════════════════════ 权限双保险（静默失效点 16） ════════════════════

    @Test
    fun `it declares the storage permission`() {
        // ⚠️⚠️ 漏了声明的后果：写 /sdcard/vFlow/backups 在 Q+ 上**静默失败**
        //      （不抛异常、不报错，只是文件没出现）。范式照 SaveImageModule.kt:47。
        assertTrue(
            "❌ requirements 里必须有 PermissionManager.STORAGE",
            module.requiredPermissions.any { it.id == PermissionManager.STORAGE.id },
        )
        // 静态属性与 getRequiredPermissions 必须一致（BaseModule 默认透传）
        assertTrue(
            "getRequiredPermissions 也必须返回 STORAGE",
            module.getRequiredPermissions(null).any { it.id == PermissionManager.STORAGE.id },
        )
    }

    // ════════════════════ AI 元数据 ════════════════════

    @Test
    fun `usage scopes never include DIRECT_TOOL`() {
        // ⚠️ 写文件有副作用，且口令参数会被模型当成普通字符串填。
        assertFalse(
            "❌ 不得给 DIRECT_TOOL",
            module.aiMetadata!!.usageScopes.contains(AiModuleUsageScope.DIRECT_TOOL),
        )
        assertTrue(
            "应当可以在临时工作流里使用",
            module.aiMetadata!!.usageScopes.contains(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        )
    }

    @Test
    fun `risk level is not READ_ONLY`() {
        assertTrue(
            "❌ 它是写操作，不得标成 READ_ONLY",
            module.aiMetadata!!.riskLevel != AiModuleRiskLevel.READ_ONLY,
        )
    }

    // ════════════════════ 输入输出契约 ════════════════════

    @Test
    fun `input ids match the contract`() {
        assertEquals(
            setOf("scopes", "include_secrets", "backup_password", "file_name"),
            module.getInputs().map { it.id }.toSet(),
        )
    }

    @Test
    fun `output ids match the contract`() {
        val outputs = module.getOutputs(null).map { it.id }.toSet()
        assertTrue(outputs.containsAll(setOf("file_path", "file_name", "scope_count", "scrubbed_count")))
    }

    @Test
    fun `output types are strings and numbers as declared`() {
        val byId = module.getOutputs(null).associateBy { it.id }
        assertEquals(VTypeRegistry.STRING.id, byId.getValue("file_path").typeName)
        assertEquals(VTypeRegistry.STRING.id, byId.getValue("file_name").typeName)
        assertEquals(VTypeRegistry.NUMBER.id, byId.getValue("scope_count").typeName)
        assertEquals(VTypeRegistry.NUMBER.id, byId.getValue("scrubbed_count").typeName)
    }

    @Test
    fun `the scopes default value is derived from the registry`() {
        // ⚠️⚠️ 勾选项**唯一来源**是 BackupScopeRegistry。硬编码第二份清单
        //     会让新增的 scope 永远不出现在默认值里（静默失效点 13）。
        val scopesInput = module.getInputs().first { it.id == "scopes" }
        assertEquals(ParameterType.ANY, scopesInput.staticType)

        @Suppress("UNCHECKED_CAST")
        val defaultValue = scopesInput.defaultValue as List<String>
        assertEquals(
            BackupScopeRegistry.all().filter { it.defaultIncluded }.map { it.id },
            defaultValue,
        )
        // 至少有一个默认勾选的范围（否则新建步骤后必然「至少选一个」报错）
        assertTrue("默认勾选不能为空", defaultValue.isNotEmpty())
    }

    @Test
    fun `the custom UI provider only handles the scopes input`() {
        // ⚠️ backup_password / include_secrets / file_name 由自动表单渲染。
        //     把它们也塞进自定义 UI 会绕过标准校验与魔法变量入口。
        assertEquals(setOf("scopes"), module.uiProvider!!.getHandledInputIds())
    }

    // ════════════════════ 命名锁（§5.3 的机器化凭据） ════════════════════

    @Test
    fun `the passphrase parameter id is not named after the plain word passphrase`() {
        // 与 BackupExportNamingTest 成对：那条锁「为什么必须叫 backup_password」，
        // 这条锁「不许改回 passphrase」。
        assertFalse(
            "❌ 参数 id 不得是 passphrase（它不被 SecretFieldScrubber 清洗）",
            module.getInputs().any { it.id == "passphrase" },
        )
    }

    // ════════════════════ 摘要不回显口令（§5.4） ════════════════════

    @Test
    fun `buildSummaryText never mentions the passphrase`() {
        val text = buildSummaryText(listOf("workflows", "folders"), true, "b.json")
        assertTrue("摘要应含范围数", text.contains("2"))
        assertTrue("摘要应含文件名", text.contains("b.json"))
        assertFalse("❌ 摘要里出现了「含密钥」之外的密钥相关字样", text.contains("口令值"))
    }

    @Test
    fun `the summary builder has no passphrase parameter at all`() {
        // ⚠️⚠️ 这是「不回显口令」在**类型层面**的落实：想回显也拿不到值。
        //     反射断言参数表，防的是「顺手加一个 password: String = ""）——
        //     带默认值的参数不会让既有调用点编译失败，所以行为级测试测不出它。
        val fn = Class.forName("com.chaomixian.vflow.core.workflow.module.data.BackupExportModuleKt")
        val target = fn.declaredMethods.firstOrNull { it.name == "buildSummaryText" }
        assertNotNull("找不到 buildSummaryText（顶层函数被改名？）", target)

        val params = target!!.parameterTypes.map { it.simpleName }
        assertEquals("签名应当是 (List, boolean, String)：$params", 3, params.size)
        assertTrue(
            "❌ buildSummaryText 不得接受 char[] / CharArray 之类的口令载体（实际：$params）",
            params.none { it.contains("[]", ignoreCase = true) },
        )
    }

    @Test
    fun `getSummary function body does not touch the password parameter`() {
        // ⚠️ 源码扫描（**剥注释**）：本函数的 KDoc 里写着 backup_password 这个词
        //     （那正是文档该做的事），只做 contains 会恒红；
        //     而把真实拼接删掉只留注释又会被误判成绿。故先剥注释，再截函数体。
        val code = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportModule.kt"
        )
        val body = SourceScan.functionBody(code, "override fun getSummary(")
        assertNotNull("❌ 没能截取 getSummary 的函数体（大括号配对失败）", body)
        assertFalse(
            "❌ getSummary 函数体里出现了 PARAM_PASSWORD —— 摘要可能回显了口令",
            body!!.contains("PARAM_PASSWORD"),
        )
        assertTrue(
            "getSummary 应当委托给纯函数 buildSummaryText",
            body.contains("buildSummaryText("),
        )
    }

    // ════════════════════ 图标与文案 ════════════════════

    @Test
    fun `its icon differs from the save-image module icon`() {
        // 与「保存图片」区分：两者都写盘，图标一样会让用户分不清。
        assertEquals(R.drawable.rounded_backup_export_24, module.metadata.iconRes)
        assertFalse(
            "❌ 不得复用 rounded_save_24",
            module.metadata.iconRes == R.drawable.rounded_save_24,
        )
    }

    @Test
    fun `three-language strings are all present`() {
        val expected = listOf(
            "module_vflow_data_export_backup_name",
            "module_vflow_data_export_backup_desc",
            "param_vflow_data_export_backup_scopes_name",
            "param_vflow_data_export_backup_include_secrets_name",
            "param_vflow_data_export_backup_password_name",
            "param_vflow_data_export_backup_file_name_name",
            "output_vflow_data_export_backup_file_path_name",
            "output_vflow_data_export_backup_file_name_name",
            "output_vflow_data_export_backup_scope_count_name",
            "output_vflow_data_export_backup_scrubbed_count_name",
            "progress_vflow_data_export_backup_exporting",
            "progress_vflow_data_export_backup_done",
            "error_vflow_data_export_backup_execution_error",
            "error_vflow_data_export_backup_step_missing",
            "error_vflow_data_export_backup_no_scope",
            "error_vflow_data_export_backup_password_required",
            "error_vflow_data_export_backup_failed",
            "error_vflow_data_export_backup_unknown",
        )
        for (dir in listOf("values", "values-en", "values-ja")) {
            val xml = readStringsModule(dir)
            val missing = expected.filter { !xml.contains("name=\"$it\"") }
            assertTrue("❌ $dir 缺少这些键：$missing", missing.isEmpty())
        }
    }

    @Test
    fun `the password input carries a localised name and hint`() {
        val input = module.getInputs().first { it.id == PARAM_PASSWORD }
        assertNotNull("口令参数必须有本地化名", input.nameStringRes)
        assertNotNull("口令参数必须有 hint（建议引用全局变量）", input.hintStringRes)
    }

    // ════════════════════ 文件名安全处理 ════════════════════

    @Test
    fun `sanitize strips directory components`() {
        // ⚠️ 用户（或模型）可以写 `../x.json` 或 `/sdcard/vFlow/backups` ——
        //     后者会落到目录本身，写到目录上必然失败且错误信息含糊。
        assertEquals("x.json", sanitizeBackupFileName("../x.json"))
        assertEquals("x.json", sanitizeBackupFileName("/tmp/a/b/x.json"))
        assertEquals("x.json", sanitizeBackupFileName("a/b/x.json"))
    }

    @Test
    fun `sanitize falls back to a timestamped name for blank or dot inputs`() {
        val fallback = sanitizeBackupFileName("", nowMs = 0L)
        assertTrue("空文件名应回落到自动命名：$fallback", fallback.startsWith("vflow_backup_"))
        assertTrue(fallback.endsWith(".json"))
        // `.` / `..` 同样回落
        assertTrue(sanitizeBackupFileName(".", 0L).startsWith("vflow_backup_"))
        assertTrue(sanitizeBackupFileName("..", 0L).startsWith("vflow_backup_"))
        assertTrue(sanitizeBackupFileName(null, 0L).startsWith("vflow_backup_"))
    }

    @Test
    fun `default file name is stable for a fixed timestamp`() {
        // 同一时刻必产同名（否则「输出路径」这条契约就没法断言）
        assertEquals(defaultBackupFileName(0L), defaultBackupFileName(0L))
        assertTrue(defaultBackupFileName(0L).startsWith("vflow_backup_"))
    }
}
