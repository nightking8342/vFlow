// 文件: test/java/com/chaomixian/vflow/ui/settings/BackupRestoreWiringTest.kt
package com.chaomixian.vflow.ui.settings

import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.backup.BackupEnvelope
import com.chaomixian.vflow.core.backup.BackupPipeline
import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.backup.FakeBackupEnvironment
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线锚定测试**。
 *
 * ## ⚠️⚠️ 它存在的理由
 *
 * 本仓库已三次踩过「纯函数单测全绿、但调用点缺失」的坑
 * （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`；`XposedDiagnostics.messageFor`
 * 零生产调用点；`EventQueue.drainDropped()` 零生产调用者）。这三处的共同形态是：
 * **逻辑本身没问题，只是没人调它** —— 而单元测试测不出「有没有人调」。
 *
 * 本任务的产物（备份 UI）无法在纯 JVM 里跑（需要 `Context` / `ContentResolver` /
 * SAF），所以接线只能在源码层锁。
 *
 * ## ⚠️⚠️ 所有断言都必须**先剥注释**
 *
 * 这些文件的 KDoc 里写满了 `BackupScopeRegistry.all()` / `BackupPipeline.import(`
 * 这类字样（那正是文档该做的事）。只做 `contains` 的话，把真实调用删掉、
 * 只留注释也一样是绿的 —— 本仓库在 `AgentErrorDialogWiringTest` 上踩过
 * 完全相同的坑。剥注释 helper 走 `SourceScan`（状态机实现，保留换行与长度结构，
 * 大括号配对截取函数体时不会被注释里的 `{}` 打乱）。
 */
class BackupRestoreWiringTest {

    private companion object {
        /**
         * 设置页的落点。
         *
         * ⚠️ 勾选项的渲染在 `BackupRestoreScreen.kt`，而 `BackupRestoreActivity.kt`
         * 只是壳。第一条断言扫**两者**（谁渲染谁负责从注册表派生）。
         */
        const val SCREEN = "src/main/java/com/chaomixian/vflow/ui/settings/BackupRestoreScreen.kt"
        const val ACTIVITY = "src/main/java/com/chaomixian/vflow/ui/settings/BackupRestoreActivity.kt"
        const val MODULE_REGISTRY = "src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"
        const val WORKFLOW_SCOPE = "src/main/java/com/chaomixian/vflow/core/backup/scopes/WorkflowScope.kt"
        const val SETTINGS_SCREEN = "src/main/java/com/chaomixian/vflow/ui/settings/SettingsScreen.kt"
    }

    // ═══════════ 1. 设置页没有硬编码 scope 清单（静默失效点 13） ═══════════

    @Test
    fun `the settings screen derives its scope list from the registry`() {
        val code = SourceScan.stripped(SCREEN)
        assertTrue(
            "❌ $SCREEN 里没有 BackupScopeRegistry.all( —— 勾选项必须从注册表派生，"
                + "否则新增的 scope 永远不会出现在界面上（且没有任何报错）",
            code.contains("BackupScopeRegistry.all("),
        )
    }

    @Test
    fun `the settings screen does not hardcode a second scope list`() {
        val code = SourceScan.stripped(SCREEN)
        // 真正的「第二份清单」形态：把 scope id 写成字面量集合。
        // 注意：单个 id 字面量**允许**出现在 `backupScopeLabelRes` 的映射里
        // （那是标签翻译，不是清单）—— 而那个映射刻意被移到了
        // BackupScopeLabels.kt，所以本文件里连单个字面量都不该有。
        //
        // ⚠️ T6：清单**从注册表派生**而不是再抄一份。
        //    抄一份的话，T6 新增的四个 scope 需要在这里手工补 4 个字面量，
        //    而「忘了补」的后果是**这条断言悄悄失去对新 scope 的覆盖**
        //    —— 正是它自己要防的那种漂移。派生后新 scope 自动进入 forbid 集。
        val forbidden = BackupScopeRegistry.all().map { "\"${it.id}\"" }
        assertTrue(
            "❌ 注册表为空 —— forbid 集会恒空，本断言空转",
            forbidden.size >= 8,
        )
        val hits = forbidden.filter { code.contains(it) }
        assertTrue(
            "❌ $SCREEN 里出现了 scope id 字面量：$hits\n"
                + "这是静默失效点 13 的形态 —— 硬编码第二份清单 ⇒ 新 scope 永不出现。\n"
                + "id → 显示名的映射请放在 BackupScopeLabels.kt。",
            hits.isEmpty(),
        )
    }

    @Test
    fun `the label mapping lives in its own file and is not a second list`() {
        val code = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/settings/BackupScopeLabels.kt"
        )
        assertTrue("标签映射文件里应当只有 when(id) 分支", code.contains("fun backupScopeLabelRes("))
        // 防空转：映射本身不能被剥没了
        assertTrue(
            "❌ 标签映射被剥空了 —— 检查剥注释逻辑",
            code.contains("backup_scope_"),
        )
    }

    // ═══════════ 2. 导入链路三跳（需求：断言导入真的调了 reloadTriggers） ═══════════

    @Test
    fun `hop 1 - the screen imports through BackupPipeline`() {
        val code = SourceScan.stripped(SCREEN)
        assertTrue(
            "❌ $SCREEN 没有调用 BackupPipeline.import( —— 导入链路没接上",
            code.contains("BackupPipeline.import("),
        )
    }

    @Test
    fun `hop 2 - the import uses the production environment, not a stub`() {
        val code = SourceScan.stripped(SCREEN)
        assertTrue(
            "❌ $SCREEN 没有构造 AndroidBackupEnvironment —— "
                + "导入若走一个 Fake/stub 环境，reloadTriggers 就不会真的被调到",
            code.contains("AndroidBackupEnvironment("),
        )
        // ⚠️ 探测与导入**两条路径都必须用生产环境**：探测用了 stub 的话
        //    它会判定「不需要口令」，而真实导入时才炸。
        val occurrences = SourceScan.countOccurrences(code, "AndroidBackupEnvironment(")
        assertTrue(
            "❌ $SCREEN 只在一处构造了 AndroidBackupEnvironment（找到 $occurrences 处）。\n"
                + "探测（needsPassphraseProbe）与导入（runImport）各需一个 —— "
                + "缺探测会让「含加密段的备份」在导入时才失败。",
            occurrences >= 2,
        )
        assertTrue(
            "❌ 没有看到口令探测链路（BackupEnvelope.read）—— "
                + "缺它就只能靠「无口令先试导一次」，那会把 REPLACE 跑两遍",
            code.contains("BackupEnvelope.read("),
        )
    }

    @Test
    fun `hop 3 - workflow import reloads triggers`() {
        val code = SourceScan.stripped(WORKFLOW_SCOPE)
        val body = SourceScan.functionBody(code, "override fun import(")
        assertTrue("❌ 没能截取 WorkflowScope.import 的函数体（大括号配对失败）", body != null)
        assertTrue(
            "❌ WorkflowScope.import 里没有 env.reloadTriggers() —— "
                + "工作流进来了但触发器不会调度（静默失效点 5）",
            body!!.contains("reloadTriggers("),
        )
    }

    // ═══════════ 3. 模块已注册，且是**追加**而非重排 ═══════════

    @Test
    fun `BackupExportModule is registered in the data section`() {
        val raw = SourceScan.file(MODULE_REGISTRY).readText()
        val code = SourceScan.stripCommentsPreservingStructure(raw)

        // ⚠️⚠️ 分段标记**必须从原始源码里找** —— 它们是行注释，
        // 在剥注释后的字符串里已被抹成空格（找 `// 数据` 会恒失败）。
        // 反过来，注册行必须从剥注释后的字符串里找（防注释里写着它的 KDoc）。
        //
        // 两种取法可以**直接比较下标**：剥注释保留换行与长度结构
        // （注释原地抹成空格），故两份字符串长度一致、位置一一对应。
        // 若哪天有人把剥离实现换成「整行丢弃」，这条会立刻变红 ——
        // 那种实现会让后面所有下标比较全部错位（且错得很隐蔽）。
        assertEquals(
            "❌ 剥注释改变了字符串长度 —— 下标比较不再可靠",
            raw.length, code.length,
        )
        val dataSectionIdx = raw.indexOf("// 数据")
        assertTrue("❌ ModuleRegistry 里找不到「// 数据」分段注释", dataSectionIdx >= 0)

        // ⚠️ 「// 文件」在本文件里出现**两次**（文件头注释 + 分段注释）。
        //    必须从 dataSectionIdx 之后起找，否则会取到文件头那一处 ⇒ 断言恒真。
        val fileSectionIdx = raw.indexOf("// 文件", dataSectionIdx)
        assertTrue("❌ 「// 数据」之后找不到「// 文件」分段注释", fileSectionIdx > dataSectionIdx)

        val registerIdx = code.indexOf("register(BackupExportModule(), context)")
        assertTrue(
            "❌ ModuleRegistry 里没有 register(BackupExportModule(), context) —— "
                + "模块在列表里根本不出现",
            registerIdx >= 0,
        )
        assertTrue(
            "❌ BackupExportModule 没有注册在「数据」段内（index=$registerIdx，"
                + "数据段起点=$dataSectionIdx，文件段起点=$fileSectionIdx）—— "
                + "它必须在数据段末尾追加，不得重排既有注册",
            registerIdx in (dataSectionIdx + 1) until fileSectionIdx,
        )
    }

    @Test
    fun `the registry still contains the pre-existing registrations around the new one`() {
        // 防空转：文件被改名/搬走 ⇒ indexOf 返回 -1 ⇒ 上一条可能恒真。
        val code = SourceScan.stripped(MODULE_REGISTRY)
        assertTrue(
            "❌ 找不到 register(FileOperationModule(), context) —— "
                + "ModuleRegistry 的形状变了，上一条断言的分段判据不再成立",
            code.contains("register(FileOperationModule(), context)"),
        )
        assertTrue(code.contains("register(CreateVariableModule(), context)"))
    }

    // ═══════════ 4. 设置页入口的接线 ═══════════

    @Test
    fun `the settings entry is wired end to end`() {
        val screen = SourceScan.stripped(SETTINGS_SCREEN)
        assertTrue(
            "❌ SettingsScreen 的 actions 数据类里没有 onOpenBackupRestore",
            screen.contains("val onOpenBackupRestore: () -> Unit"),
        )
        assertTrue(
            "❌ SettingsScreen 没有渲染使用 onOpenBackupRestore 的入口行",
            screen.contains("onClick = actions.onOpenBackupRestore"),
        )
        // 静默失效点 14：文案不进 matchesSearch ⇒ 搜索时整组消失。
        assertTrue(
            "❌ 新入口的文案没有进 showGeneralSection 的搜索列表 —— "
                + "用户搜「备份」时整个「通用设置」分组会消失",
            screen.contains("backupRestoreTitle, backupRestoreSubtitle,"),
        )

        val route = SourceScan.stripped("src/main/java/com/chaomixian/vflow/ui/settings/SettingsRoute.kt")
        assertTrue(
            "❌ SettingsRoute 没有为 onOpenBackupRestore 接线",
            route.contains("onOpenBackupRestore = {"),
        )
        assertTrue(
            "❌ SettingsRoute 没有启动 BackupRestoreActivity",
            route.contains("BackupRestoreActivity::class.java"),
        )

        val manifest = SourceScan.stripped("src/main/AndroidManifest.xml")
        assertTrue(
            "❌ Manifest 里没有声明 BackupRestoreActivity",
            manifest.contains(".ui.settings.BackupRestoreActivity"),
        )
    }

    // ═══════════ 防空转 ═══════════

    @Test
    fun `the scans are not vacuous`() {
        val screen = SourceScan.stripped(SCREEN)
        assertTrue(
            "❌ 剥注释后 SourceScan 看不到 BackupRestoreScreen —— 剥过头了（后面的断言会假绿）",
            screen.contains("fun BackupRestoreScreen("),
        )
        assertTrue(
            "❌ BackupRestoreScreen.kt 剥注释后字符数为 0",
            screen.isNotBlank(),
        )
        val activity = SourceScan.stripped(ACTIVITY)
        assertTrue(
            "❌ 剥注释后看不到 class BackupRestoreActivity",
            activity.contains("class BackupRestoreActivity"),
        )
        val registry = SourceScan.stripped(MODULE_REGISTRY)
        assertTrue(registry.isNotBlank())
    }

    // ═══════════ 导出前的校验判据（纯函数） ═══════════

    @Test
    fun `export validation covers the four branches`() {
        // ⚠️ 判据与文案分离（见 exportValidationError 的 KDoc），故这里能纯 JVM 测。
        assertTrue(
            exportValidationError(0, false, "", "") == ExportValidationError.NO_SCOPE,
        )
        assertTrue(exportValidationError(2, false, "", "") == null)
        assertTrue(
            exportValidationError(2, true, "", "") == ExportValidationError.PASSPHRASE_REQUIRED,
        )
        assertTrue(
            exportValidationError(2, true, "a", "b") == ExportValidationError.PASSPHRASE_MISMATCH,
        )
        assertTrue(exportValidationError(2, true, "a", "a") == null)
    }

    @Test
    fun `include secrets without a passphrase is never silently downgraded`() {
        // ⚠️ 反向断言：勾了「包含密钥」却没给口令时**必须报错**，
        //    不得退化成「不含密钥」（用户不会知道密钥没进去）。
        val err = exportValidationError(1, true, "   ", "   ")
        assertTrue(
            "❌ 口令全为空白时被当成有效口令 —— 会导出一份没有密钥的「成功」备份",
            err == ExportValidationError.PASSPHRASE_REQUIRED,
        )
    }

    // ═══════════ 被清洗字段必须展示给用户（方案 §5.7） ═══════════

    @Test
    fun `scrubbed fields are summarised with a bounded preview`() {
        assertEquals("a, b", formatScrubbedFields(listOf("a", "b")))
        val many = (1..9).map { "s$it" }
        val text = formatScrubbedFields(many)
        // 前 5 项 + 总数；不得把 9 项全列出来（横幅会撑爆）
        assertTrue("应含前 5 项：$text", text.contains("s1") && text.contains("s5"))
        assertTrue("不应含第 6 项：$text", !text.contains("s6"))
        assertTrue("应含总数：$text", text.contains("9"))
    }

    @Test
    fun `the export path actually renders the scrubbed fields`() {
        // ⚠️ 源码扫描（剥注释）：把「展示 scrubbedFields」删掉、只留注释也一样是绿的，
        //    而静默丢弃它正是方案 §5.7 明确要防的事。
        val code = SourceScan.stripped(SCREEN)
        assertTrue(
            "❌ 导出成功的提示里没有展示 scrubbedFields —— 用户不知道密钥被抹掉了",
            code.contains("formatScrubbedFields("),
        )
        assertTrue(
            "❌ 没有引用 backup_restore_scrubbed_fields 文案",
            code.contains("backup_restore_scrubbed_fields"),
        )
    }

    // ═══════════ 口令错可重输、损坏不可（方案 §5.5） ═══════════

    @Test
    fun `only the wrong-passphrase branch offers a retry`() {
        val code = SourceScan.stripped(SCREEN)
        // retryPassphrase = true 必须**恰好出现一次**（只有 WrongPassphrase 那一支）。
        assertEquals(
            "❌ retryPassphrase = true 应当恰好出现一次（只有口令错那一支给重输入口）",
            1,
            SourceScan.countOccurrences(code, "retryPassphrase = true"),
        )
        assertTrue(
            "❌ 没有看到 retryPassphrase = false 的显式标注（损坏那一支必须写明不可重输）",
            code.contains("retryPassphrase = false"),
        )
    }

    @Test
    fun `the wrong passphrase and corrupted branches are distinct messages`() {
        val code = SourceScan.stripped(SCREEN)
        // 两者用**不同的**字符串资源 ⇒ 用户不会把「文件坏了」当成「口令错」。
        assertTrue(code.contains("R.string.backup_restore_wrong_passphrase"))
        assertTrue(code.contains("R.string.backup_restore_corrupted"))
    }

    // ═══════════ REPLACE 的二次确认（方案 §8.2 硬性要求） ═══════════

    @Test
    fun `REPLACE is gated behind a confirmation before any import runs`() {
        // ⚠️⚠️ 这条断言**必须截取函数体**，不能用全文件 contains ——
        //  `ReplaceConfirmDialog` 的定义、`backup_restore_mode_confirm_replace`
        //  的引用、`ImportMode.REPLACE` 的字面量**都在同一份文件里**，
        //  全文件 contains 的话「把调用删掉、只留定义」照样是绿的
        //  （本仓库在 AgentErrorDialogWiringTest 上踩过完全相同的坑：
        //   断言自己写的字面量、而不是断言调用链）。
        val code = SourceScan.stripped(SCREEN)

        val body = SourceScan.functionBody(code, "onPick = { mode ->")
        assertTrue("❌ 没能截取模式选择回调的函数体", body != null)

        val replaceBranchIdx = body!!.indexOf("ImportMode.REPLACE")
        val confirmIdx = body.indexOf("replaceConfirmVisible = true")
        val importIdx = body.indexOf("startImportWithProbe()")
        assertTrue(
            "❌ 模式选择回调里没有 ImportMode.REPLACE 分支 —— 覆盖式会被直接执行",
            replaceBranchIdx >= 0,
        )
        assertTrue(
            "❌ 模式选择回调里没有 replaceConfirmVisible = true —— 覆盖式没有二次确认",
            confirmIdx >= 0,
        )
        assertTrue(
            "❌ 模式选择回调里没有 startImportWithProbe（合并式的直通路径）",
            importIdx >= 0,
        )
        assertTrue(
            "❌ 覆盖式的确认必须**先于**导入（确认在下游 ⇒ 等于没确认）",
            replaceBranchIdx < confirmIdx,
        )
        // 合并式走直通、覆盖式走确认 —— 两条分支都要在同一个回调里。
        assertTrue("合并式应在覆盖式分支之外直通导入", importIdx > confirmIdx)

        // 确认按钮的文案必须是「覆盖导入（删除现有数据）」而不是「确定」——
        // 资源 id 本身就是这条契约的表达（定义在 ReplaceConfirmDialog 里）。
        assertTrue(
            "❌ 确认按钮必须用 backup_restore_mode_confirm_replace（明确写清后果），" +
                "不能用 android.R.string.ok",
            code.contains("R.string.backup_restore_mode_confirm_replace"),
        )
    }

    @Test
    fun `merge is the default and is listed first`() {
        val code = SourceScan.stripped(SCREEN)
        // ⚠️ 破坏性操作不该是默认项。列表顺序也是意图的一部分（用户先看到的是安全项）。
        val mergeIdx = code.indexOf("onPick(ImportMode.MERGE)")
        val replaceIdx = code.indexOf("onPick(ImportMode.REPLACE)")
        assertTrue("merge 与 replace 选项都应存在", mergeIdx >= 0 && replaceIdx >= 0)
        assertTrue(
            "❌ 合并式必须排在覆盖式之前（默认项应当是**安全**的那个）",
            mergeIdx < replaceIdx,
        )
    }

    // ═══════════ 导入侧也必须告知「凭据已被清空」 ═══════════
    //
    // ⚠️⚠️ 这一组的由来：导出侧早就展示了被清空的字段（§5.7），导入侧却漏了
    //      —— 不对称。用户 REPLACE 导入一份不含密钥的备份后，只会看到
    //      「导入 12 · 跳过 0」，然后发现工作流里的 api_key 是空的：
    //      他会去查执行日志、查模块、查权限，唯独查不到「导出时就没带密钥」。

    @Test
    fun `scrubbed fields survive the export-import round trip`() {
        // 端到端：造一份「导出时清洗过密钥」的备份文本，再把它读回来。
        val env = FakeBackupEnvironment(
            workflows = listOf(
                Workflow(
                    id = "w1",
                    name = "wf",
                    steps = listOf(
                        ActionStep(
                            moduleId = "vflow.interaction.agent",
                            parameters = linkedMapOf("api_key" to "sk-live-1"),
                            id = "s1",
                        )
                    ),
                )
            )
        )
        val text = BackupPipeline.export(env, setOf("workflows"), secrets = null).text

        // ① 只读访问器能从文本里取出它们（UI 在导入**前**用它，见 ImportModeDialog）。
        val fields = BackupEnvelope.scrubbedFieldsOf(text)
        assertEquals(
            "❌ 导入侧读不出被清空的字段位置 —— 用户永远不会知道凭据被抹掉了",
            listOf("s1.api_key"),
            fields,
        )

        // ② 导入完成后展示的 warning 文本必须**含那个位置**（这才是用户看到的东西）。
        val warning = scrubbedWarningOf(fields)
        assertNotNull("❌ 字段非空时必须产出提示（否则 summary 里什么都没有）", warning)
        assertTrue(
            "❌ 提示文本里没有字段位置：${warning!!.fieldsText}",
            warning.fieldsText.contains("s1.api_key"),
        )

        // ③ 这份备份确实能导入成功（提示不是「失败」路径的产物）。
        val outcome = BackupPipeline.import(env, text, ImportMode.MERGE, null)
        assertTrue(
            "❌ 期望 Done，实际是 ${outcome::class.simpleName}",
            outcome is BackupPipeline.ImportOutcome.Done,
        )
    }

    @Test
    fun `no warning is produced when nothing was scrubbed`() {
        // ⚠️ 反向断言：不得产出空提示 —— 那会在弹窗里多出一条无意义的空白行，
        //    也会让「有清洗」与「没清洗」在 UI 上无法区分。
        assertNull(scrubbedWarningOf(emptyList()))
        assertNull(
            "空数组文本也不该产出提示",
            scrubbedWarningOf(BackupEnvelope.scrubbedFieldsOf("{}")),
        )
        // 坏文本（不是 JSON）必须安全退化，不抛 —— 调用方在 UI 路径上。
        assertTrue(BackupEnvelope.scrubbedFieldsOf("not json at all").isEmpty())
    }

    @Test
    fun `the pre-import warning uses a different string from the post-import one`() {
        // ⚠️ 两条文案说的事不同（「将被清空」vs「已被清空」），必须各自存在 ——
        //    混用会让用户在**还没导入**时就以为已经发生了。
        val pre = scrubbedWarningOf(listOf("s1.api_key"), R.string.backup_restore_mode_scrubbed_warning)
        val post = scrubbedWarningOf(listOf("s1.api_key"))
        assertNotNull(pre)
        assertTrue(
            "❌ 导入前与导入后用了同一条文案",
            pre!!.messageRes != post!!.messageRes,
        )
        // 但两者对字段的截断规约必须一致（同一个 formatScrubbedFields）。
        assertEquals(pre.fieldsText, post.fieldsText)
    }

    @Test
    fun `the Done branch renders the scrubbed warning instead of a hardcoded empty list`() {
        // ⚠️ 源码扫描（**剥注释**）：本改动要在源码里写大量含 scrubbedFields
        //    字样的注释，只做 contains 的话「把渲染删掉只留注释」照样绿。
        //    ⚠️⚠️ 必须截 `runImport` 的函数体 —— 因为 `scrubbedWarningOf`
        //    在**同一文件**里还有 ImportModeDialog 那一处调用，
        //    全文件 contains 的话删掉 Done 分支那一处仍会绿
        //    （与 `AgentErrorDialogWiringTest` 同源：断言调用链而非字面量）。
        val code = SourceScan.stripped(SCREEN)
        val body = SourceScan.functionBody(code, "fun runImport(")
        assertNotNull("❌ 没能截取 runImport 的函数体", body)

        val doneIdx = body!!.indexOf("ImportOutcome.Done")
        assertTrue("❌ runImport 里没有 Done 分支", doneIdx >= 0)
        val doneBranch = body.substring(doneIdx, minOf(body.length, doneIdx + 900))
        assertTrue(
            "❌ Done 分支的 warningLines 没有引用被清空的字段 —— " +
                "用户 REPLACE 导入后只会看到「导入 N · 跳过 0」，发现不了凭据是空的",
            doneBranch.contains("scrubbedWarningOf("),
        )
        assertTrue(
            "❌ Done 分支的 warningLines 不应再被写死成 emptyList()",
            !doneBranch.contains("warningLines = emptyList()"),
        )
    }

    @Test
    fun `the import mode dialog warns before the user picks replace`() {
        // ⚠️⚠️ 这条比「导入后展示」更重要：REPLACE **不可撤销**，
        //    用户必须在**选覆盖式之前**就知道后果。
        val code = SourceScan.stripped(SCREEN)
        val body = SourceScan.functionBody(code, "private fun ImportModeDialog(")
        assertNotNull("❌ 没能截取 ImportModeDialog 的函数体", body)
        assertTrue(
            "❌ 模式选择对话框里没有「未含密钥」的提示 —— " +
                "用户会在按了覆盖式之后才发现凭据是空的",
            body!!.contains("R.string.backup_restore_mode_scrubbed_warning"),
        )

        // 且这份提示的数据必须来自**真实解析**（不是写死的文案）——
        // 解析发生在**选完文件、读回文本**那一刻（`importLauncher` 的回调里），
        // 这样模式选择对话框才有内容可提示。⚠️ 这里不能截 `runImport`：
        // 那时已经太晚了（用户早选完模式了）。
        val pickBody = SourceScan.functionBody(
            code,
            "val importLauncher = rememberLauncherForActivityResult(",
        )
        assertNotNull("❌ 没能截取 importLauncher 的回调体", pickBody)
        assertTrue(
            "❌ 选完文件后没有解析被清空的字段 —— 对话框拿不到要提示的内容",
            pickBody!!.contains("BackupEnvelope.scrubbedFieldsOf("),
        )
    }

    @Test
    fun `three-language strings carry both scrubbed warnings`() {
        val expected = listOf(
            "backup_restore_import_scrubbed_warning",
            "backup_restore_mode_scrubbed_warning",
        )
        for (dir in listOf("values", "values-en", "values-ja")) {
            val f = java.io.File("src/main/res/$dir/strings.xml")
            assertTrue("找不到 $dir/strings.xml", f.isFile)
            val xml = f.readText()
            val missing = expected.filter { !xml.contains("name=\"$it\"") }
            assertTrue("❌ $dir 缺少这些键：$missing", missing.isEmpty())

            // ⚠️ 模板里必须有 `%1$s` —— 掉了它，「哪些字段被清空」在 UI 上
            //    就只剩一句「该备份未含密钥」，用户仍然不知道**哪里**被抹了。
            expected.forEach { key ->
                val m = Regex("<string name=\"$key\">(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
                    .find(xml)
                assertNotNull("❌ $dir 里找不到 $key 的文本", m)
                assertTrue(
                    "❌ $dir/$key 的模板里没有 %1\$s（字段列表会渲染不出来）：${m!!.groupValues[1]}",
                    m.groupValues[1].contains("%1\$s"),
                )
            }
        }
    }
}
