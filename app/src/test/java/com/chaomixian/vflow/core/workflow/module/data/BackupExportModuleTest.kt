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
            setOf("scopes", "include_secrets", "backup_password", "file_name", "format"),
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
        //
        // ⚠️ 显式传 `FORMAT_JSON`：本用例只测「剥目录」，不该顺带把
        //    「扩展名跟着格式改」也测进来（那会让本用例在格式默认值变化时变红，
        //    而它想拦的是完全另一件事）。扩展名那条另有专门用例。
        assertEquals("x.json", sanitizeBackupFileName("../x.json", format = FORMAT_JSON))
        assertEquals("x.json", sanitizeBackupFileName("/tmp/a/b/x.json", format = FORMAT_JSON))
        assertEquals("x.json", sanitizeBackupFileName("a/b/x.json", format = FORMAT_JSON))
        // 默认档（zip）同样剥目录
        assertEquals("x.zip", sanitizeBackupFileName("../x.json"))
    }

    /**
     * 扩展名**跟着格式走**。
     *
     * ⚠️ 不跟着走会产出「名字说 json、内容是 zip」的文件 —— 导入侧靠魔数判容器
     * 所以照样能读，但用户在文件管理器里看到的是个错误的类型，且不会有任何提示。
     */
    @Test
    fun `the extension follows the format`() {
        assertEquals("x.zip", sanitizeBackupFileName("x.json", format = FORMAT_ZIP))
        assertEquals("x.json", sanitizeBackupFileName("x.zip", format = FORMAT_JSON))
        // 无扩展名 ⇒ 补上
        assertEquals("我的备份.zip", sanitizeBackupFileName("我的备份"))
        // 非扩展名的小数点 ⇒ **不**截断（`v2` 是名字的一部分）
        assertEquals("我的备份.v2.zip", sanitizeBackupFileName("我的备份.v2"))
        // 已知扩展名 ⇒ 替换而不是追加（`x.txt` 是未知的，故追加）
        assertEquals("x.txt.zip", sanitizeBackupFileName("x.txt"))
    }

    @Test
    fun `sanitize falls back to a timestamped name for blank or dot inputs`() {
        val fallback = sanitizeBackupFileName("", nowMs = 0L)
        assertTrue("空文件名应回落到自动命名：$fallback", fallback.startsWith("vflow_backup_"))
        // ⚠️ 默认档是 **zip**（见 `BackupExportModule.PARAM_FORMAT` 的 KDoc）。
        assertTrue("默认档是压缩包：$fallback", fallback.endsWith(".zip"))
        assertTrue(
            "显式选 JSON 时扩展名跟着变",
            sanitizeBackupFileName("", nowMs = 0L, format = FORMAT_JSON).endsWith(".json"),
        )
        // `.` / `..` 同样回落
        assertTrue(sanitizeBackupFileName(".", 0L).startsWith("vflow_backup_"))
        assertTrue(sanitizeBackupFileName("..", 0L).startsWith("vflow_backup_"))
        assertTrue(sanitizeBackupFileName(null, 0L).startsWith("vflow_backup_"))
    }

    /**
     * ⚠️⚠️ **真机回归锁**：`file_name` 必须**先解析模板再 sanitize**。
     *
     * 起因是用户实测报的缺陷：填 `自动备份_{{now.time}}test.json` 时，
     * 磁盘上落成了含 `{{now.time}}` **字面量**的文件名，而 WebDAV 那一步
     * 引用本步骤输出拿到的是**已解析**的路径 ⇒ 报「本地文件不存在」。
     *
     * ⚠️ 顺序不能反（解析在前、sanitize 在后）：
     * 解析结果可能含 `/`（如 `{{vars.dir}}/x.json`），那正是 sanitize 要剥的。
     *
     * 本用例用纯函数复现同一条链路 —— 生产代码走的是同一个 `VariableResolver.resolve`
     * 与同一个 `sanitizeBackupFileName`。
     */
    @Test
    fun `a template in file_name is resolved before sanitizing`() {
        // 模拟解析结果：{{now.time}} -> 22:47:11（真机日志里的实际值）
        val raw = "自动备份_{{now.time}}test.json"
        val resolved = raw.replace("{{now.time}}", "22:47:11")

        val fileName = sanitizeBackupFileName(resolved, format = FORMAT_JSON)

        assertEquals("自动备份_22:47:11test.json", fileName)
        assertTrue(
            "🔴 解析必须真的发生 —— 文件名里不得残留 {{ }}",
            !fileName.contains("{{") && !fileName.contains("}}"),
        )
    }

    /**
     * ⚠️ 顺序反了会怎样：先 sanitize 再解析 ⇒ 模板原样落盘。
     * 本用例锁住「**不能**把 sanitize 提到解析之前」这个约束的另一半 ——
     * 它证明上面那条断言不是空转（两者对同一输入给出**不同**答案）。
     */
    @Test
    fun `sanitizing before resolving would leak the template into the file name`() {
        val raw = "自动备份_{{now.time}}test.json"

        // 反序：先 sanitize（`{{...}}` 不含 `/` ⇒ 原样保留）再「解析」——
        // 此时得到的仍是模板字面量，因为解析的目标已经是个文件名了。
        val wrong = sanitizeBackupFileName(raw, format = FORMAT_JSON)

        assertTrue(
            "反序时模板会原样留在文件名里（这正是真机上发生的）",
            wrong.contains("{{now.time}}"),
        )
        // 与正序的结果必须不同 —— 否则本族断言无意义
        assertTrue(
            "正序与反序必须给出不同结果",
            wrong != sanitizeBackupFileName(raw.replace("{{now.time}}", "22:47:11"), format = FORMAT_JSON),
        )
    }

    /**
     * ⚠️ `sanitize` **不识别模板**：解析不掉的 `{{...}}`（变量不存在）会被当作合法文件名。
     * 这是刻意的 —— 与仓库里其它模块对 STRING 参数的既有语义一致
     * （`VariableResolver` 解析不了就原样返回），**本模块不单独拦**。
     * 本用例把这条「已知行为」写成断言，免得将来有人以为它是缺陷而随手加校验。
     */
    @Test
    fun `sanitize does not validate template syntax`() {
        val fileName = sanitizeBackupFileName("{{nonexistent.var}}.json", format = FORMAT_JSON)
        assertEquals("{{nonexistent.var}}.json", fileName)
    }

    @Test
    fun `default file name is stable for a fixed timestamp`() {
        // 同一时刻必产同名（否则「输出路径」这条契约就没法断言）
        assertEquals(defaultBackupFileName(0L), defaultBackupFileName(0L))
        assertTrue(defaultBackupFileName(0L).startsWith("vflow_backup_"))
        // ⚠️ 扩展名随格式走（默认 zip，显式 JSON 时 .json）。
        assertTrue(defaultBackupFileName(0L).endsWith(".zip"))
        assertTrue(defaultBackupFileName(0L, FORMAT_JSON).endsWith(".json"))
    }

    /**
     * ⚠️⚠️ **接线锚定**（源码扫描，形态照 `CoreDexFingerprintTest`）。
     *
     * 上面那三条 `sanitize` 用例测的是**纯函数语义**，它们对「生产代码有没有真的调
     * `VariableResolver`」**完全无感** —— 我实测过：把 `execute()` 里的解析删掉
     * （退回 `sanitizeBackupFileName(rawFileName)`），那三条**照样全绿**。
     * 这正是本仓库反复踩的那类盲区（`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、
     * `XposedDiagnostics.messageFor` 零调用点），只能靠源码扫描锁住调用点。
     *
     * ⚠️ 必须**剥注释后**再断言 —— 本文件的 KDoc 里到处是 `VariableResolver.resolve(` 字样，
     * 只做 `contains` 的话「把那一行删掉、只留注释」照样绿。
     */
    @Test
    fun `execute resolves the file name template before sanitizing`() {
        val body = SourceScan.functionBody(
            SourceScan.stripped("src/main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportModule.kt"),
            "override suspend fun execute(",
        )
        assertTrue("🔴 取不到 execute 函数体（签名改了？）—— 防空转", body != null && body.length > 300)

        assertTrue(
            "🔴 execute 里必须调 VariableResolver.resolve 解析 file_name —— " +
                "否则 `{{now.time}}` 会以字面量落进文件名，而下游步骤拿到的是已解析路径（真机缺陷）",
            body!!.contains("VariableResolver.resolve(rawFileName"),
        )
        // ⚠️ 「顺序」不能靠 indexOf 比位置 —— `resolve` 是 `sanitizeBackupFileName(...)`
        //    的**嵌套实参**，文本上它出现在 `sanitizeBackupFileName(` **之后**。
        //    换成等价且更精确的判据：**传给 sanitize 的不得是原始模板**。
        assertTrue(
            "🔴 不得把原始模板直接交给 sanitize —— 那正是真机缺陷的形态" +
                "（文件名里留 {{now.time}} 字面量）",
            !body.contains("sanitizeBackupFileName(rawFileName)"),
        )
    }
}
