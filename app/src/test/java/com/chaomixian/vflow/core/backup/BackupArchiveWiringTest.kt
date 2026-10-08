// 文件: test/java/com/chaomixian/vflow/core/backup/BackupArchiveWiringTest.kt
package com.chaomixian.vflow.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型接线锚定**（形态照 `CoreDexFingerprintTest` / `AgentErrorDialogWiringTest`）。
 *
 * ## 为什么必须有
 *
 * `BackupArchiveTest` 把容器层的**纯函数语义**测透了，但它对
 * 「生产代码有没有真的用上容器」**完全无感** —— 这正是本仓库反复踩的那类盲区
 * （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor`
 * 零调用点、`EventQueue.drainDropped` 无调用者）。
 *
 * 本批的失败模式全是静默的：
 *
 * | 漏了什么 | 表现 | 行为测试能发现吗 |
 * |---|---|---|
 * | 导入侧没用 `readImportPayload` | 压缩包按 UTF-8 读成乱码 ⇒ 「不是有效备份」 | 会（但只在真机上） |
 * | 设置页导出仍走 `export`（纯 JSON）而文件名叫 `.zip` | 用户拿到一个 `.zip` 后缀的纯文本 | **不会** |
 * | 单文件导出的压缩包档没接上 | 菜单点了没反应 / 出的还是 JSON | **不会** |
 * | `filesRoot` 没在生产环境实现 | 附件永远不进包（`null` ⇒ 不打包） | **不会** |
 *
 * ⚠️ 全部断言**先剥注释**再比对 —— 本批在源码里写了大量含
 * `BackupArchive.write` / `readImportPayload` 字样的 KDoc，只做 `contains`
 * 的话「把真实调用删掉、只留注释」照样绿。
 */
class BackupArchiveWiringTest {

    private val archive = "src/main/java/com/chaomixian/vflow/core/backup/BackupArchive.kt"
    private val pipeline = "src/main/java/com/chaomixian/vflow/core/backup/BackupPipeline.kt"
    private val envImpl = "src/main/java/com/chaomixian/vflow/core/backup/AndroidBackupEnvironment.kt"
    private val module = "src/main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportModule.kt"
    private val settingsScreen = "src/main/java/com/chaomixian/vflow/ui/settings/BackupRestoreScreen.kt"
    private val listRoute = "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"

    @Test
    fun `the pipeline exposes the container entry points`() {
        val src = SourceScan.stripped(pipeline)
        assertTrue(
            "导入侧必须有容器探测入口（UI 只跟它打交道）",
            src.contains("fun readImportPayload("),
        )
        assertTrue(
            "导出侧必须有压缩包入口",
            src.contains("fun exportToArchive("),
        )
        // ⚠️ 防空转：确认读到的确实是那个文件（而不是被 check 静默放过）。
        assertTrue(src.length > 3000)
    }

    @Test
    fun `exportToArchive delegates to the plain json path instead of rebuilding the envelope`() {
        // ⚠️⚠️ 这是本设计的核心不变量：压缩包 = 「同一段 JSON + 附件」，
        //    不是**第二条**构造路径。各建一份树会让两条路的字段集漂移 ——
        //    那正是本仓库刚用 `WorkflowJsonCodec` 收敛掉的那类缺陷。
        val body = SourceScan.functionBody(SourceScan.stripped(pipeline), "fun exportToArchive(")
            ?: error("找不到 exportToArchive 函数体（签名改了？）")

        assertTrue("必须先走 export(...) 拿到纯 JSON", body.contains("val plain = export("))
        assertTrue("再把那段 JSON 包成压缩包", body.contains("BackupArchive.write("))
        // 反向锁：不得在压缩包路径上另建信封。
        assertFalse(
            "压缩包路径不得自己调 BackupEnvelope.write（那会造出第二份构造路径）",
            body.contains("BackupEnvelope.write("),
        )
    }

    @Test
    fun `the settings screen exports the archive container`() {
        val src = SourceScan.stripped(settingsScreen)

        assertTrue(
            "设置页的全局备份必须走压缩包（它是唯一能带上自定义图标的容器）",
            src.contains("BackupPipeline.exportToArchive("),
        )
        assertFalse(
            "设置页不得再直接调 BackupPipeline.export( —— 那会产出纯 JSON 却顶着 .zip 的名字",
            src.contains("val result = BackupPipeline.export("),
        )
        assertTrue("文件名必须是 .zip", src.contains("vflow_backup_\$stamp.zip"))
    }

    @Test
    fun `the settings screen imports through the container probe`() {
        val src = SourceScan.stripped(settingsScreen)
        assertTrue(
            "导入必须先过 readImportPayload（判容器 + 还原附件）",
            src.contains("BackupPipeline.readImportPayload("),
        )
        assertFalse(
            "不得直接 readText() —— 压缩包会被按 UTF-8 解成乱码且不报错",
            src.contains("BufferedReader(InputStreamReader(it)).readText()"),
        )
    }

    @Test
    fun `the workflow list import goes through the container probe`() {
        val src = SourceScan.stripped(listRoute)
        assertTrue(src.contains("BackupPipeline.readImportPayload("))
        assertFalse(
            "列表页的单文件导入同样不得按文本读",
            src.contains("BufferedReader(InputStreamReader(it)).readText()"),
        )
    }

    @Test
    fun `both single-file export entries reach the container writer`() {
        val src = SourceScan.stripped(listRoute)

        // 三处（单文件 / 文件夹 / 「备份全部」）都必须经过同一个出口。
        // ⚠️ 数的是**调用**而不是某一行文本 —— 调用点现在跨多行（多了 `entryName` 参数），
        //    锚单行字面量会在下次格式化时无声失效。
        //    定义处本身也算一次 `writeExportToDocumentUri(`，故总数是 1 + 3。
        assertEquals(
            "恰好一个定义 + 三处调用",
            4,
            SourceScan.countOccurrences(src, "writeExportToDocumentUri("),
        )
        assertEquals(
            "恰好一处定义（多出来说明有人另写了一份出口）",
            1,
            SourceScan.countOccurrences(src, "fun writeExportToDocumentUri("),
        )
        // ⚠️ 单文件与文件夹那两处必须读 **pending**（SAF 回调是异步的，
        //    读当前勾选会在用户改选后静默导出成另一种格式）；
        //    第三处（备份全部）是固定档，直接传 `withIcons = true`。
        assertEquals(
            "单文件 + 文件夹两处读 pending",
            2,
            SourceScan.countOccurrences(src, "pendingExportWithIcons,"),
        )
        // ⚠️ 三处的主条目名必须**各不相同** —— 一律 `manifest.json` 是这次要修的问题。
        assertTrue(src.contains("BackupArchive.WORKFLOW_ENTRY"))
        assertTrue(src.contains("BackupArchive.FOLDER_ENTRY"))
        assertTrue(src.contains("BackupArchive.WORKFLOWS_ENTRY"))
        val body = SourceScan.functionBody(src, "private fun writeExportToDocumentUri(")
            ?: error("找不到 writeExportToDocumentUri 函数体")
        assertTrue(
            "压缩包档必须调 BackupArchive.writePlain",
            body.contains("BackupArchive.writePlain("),
        )
        assertTrue("纯 JSON 档直接写字节", body.contains("stream.write(jsonString.toByteArray("))
        // ⚠️ 主条目名**由调用方传**，不能写死成 manifest.json ——
        //    包里装的是什么就叫什么（单工作流 workflow.json / 文件夹 folder.json /
        //    备份全部 workflows.json）。写死会让用户解压后以为里面是个索引。
        // ⚠️ `entryName` 在**签名**里、不在 `functionBody` 截到的块里，故查 `src`。
        assertTrue(
            "条目名必须由调用方给（签名里要有 entryName 参数）",
            src.contains("entryName: String"),
        )
        assertTrue(
            "而且必须真的传给 BackupArchive.writePlain",
            body.contains("entryName)"),
        )
    }

    @Test
    fun `the export format is a pending state not a live read`() {
        // ⚠️⚠️ SAF 的 `CreateDocument` 是**异步**的：回调跑的时候，
        //    「用户当时选的是哪种格式」必须来自 pending 状态。
        //    靠读组合里的当前值会在用户改了勾选后**静默导出成另一种格式**。
        val src = SourceScan.stripped(listRoute)
        assertTrue(src.contains("var pendingExportWithIcons by remember"))
        assertTrue(
            "触发点必须写 pending",
            src.contains("pendingExportWithIcons = withIcons"),
        )
    }

    @Test
    fun `the production environment supplies filesRoot`() {
        val src = SourceScan.stripped(envImpl)
        assertTrue(
            "生产环境必须实现 filesRoot，否则附件永远不进包（null ⇒ 不打包）且不报错",
            src.contains("override val filesRoot"),
        )
        assertTrue(src.contains("appContext.filesDir"))
    }

    @Test
    fun `the backup module routes the zip format to the archive exporter`() {
        val src = SourceScan.stripped(module)
        val body = SourceScan.functionBody(src, "override suspend fun execute(")
            ?: error("找不到 execute 函数体")

        assertTrue(
            "ZIP 档必须走 exportToArchive",
            body.contains("BackupPipeline.exportToArchive("),
        )
        assertTrue(
            "纯 JSON 档保留（有人要「能直接看/粘」的文本）",
            body.contains("BackupPipeline.export("),
        )
        // ⚠️ 两条路必须**共用**同一份 scrubbedFields 语义（摘要要如实告知抹了什么）。
        assertTrue(body.contains("result.scrubbedFields"))
    }

    @Test
    fun `the backup module summary honours the format`() {
        // ⚠️ 摘要不传 format 会显示 `x.json` 而实际落盘 `x.zip` ——
        //    「改错了不报错」的形态。
        val src = SourceScan.stripped(module)
        val body = SourceScan.functionBody(src, "override fun getSummary(")
            ?: error("找不到 getSummary 函数体")
        assertTrue(
            "摘要里的文件名必须按格式推导",
            body.contains("normalizeFormat("),
        )
    }
}
