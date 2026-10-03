// 文件: test/java/com/chaomixian/vflow/core/backup/BackupWiringTest.kt
package com.chaomixian.vflow.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型接线锚定**测试（形态照仓库既有的 `CoreDexFingerprintTest` /
 * `TriggerServiceXposedNoticeWiringTest`）。
 *
 * ## ⚠️ 为什么必需（本任务的一处真实盲区）
 *
 * 三个 scope 都只经 `BackupEnvironment` 接缝读写，所以**纯 JVM 测试无法证明**
 * `AndroidBackupEnvironment` 的 REPLACE 分支真的调了新的 `replaceAllWorkflows`，
 * 而不是旧的、**合并语义**的 `saveAllWorkflows`。那个错误不报错、单测全绿，
 * 只是**旧数据残留** —— 用户看到「导入成功了，但我的旧工作流还在」。
 *
 * 本仓库反复踩过这个形态（`CoreDexFingerprint` 的「13 个纯函数单测全绿但集成点缺失」、
 * `XposedDiagnostics.messageFor` 的「零生产调用点」）。故这里从**源码层**钉住。
 *
 * ## 边界
 *
 * 它证明「源码里写了这次调用」，**不证明**「运行期真的走到那一支」
 * （那需要 instrumented 测试或真机）。对本任务够用了：
 * 上面那条错误的形态恰恰是「源码里就没写」。
 */
class BackupWiringTest {

    private companion object {
        const val WORKFLOW_MANAGER = "src/main/java/com/chaomixian/vflow/core/workflow/WorkflowManager.kt"
        const val FOLDER_MANAGER = "src/main/java/com/chaomixian/vflow/core/workflow/FolderManager.kt"
        const val ANDROID_ENV = "src/main/java/com/chaomixian/vflow/core/backup/AndroidBackupEnvironment.kt"
    }

    private fun file(path: String): File {
        val f = File(path)
        assertTrue("源码文件不存在：${f.absolutePath}", f.isFile)
        return f
    }

    /**
     * 剥掉注释，但**保留换行与长度结构**（用空格顶替注释内容）。
     *
     * ⚠️ 不能用「整行丢弃」的做法：本测试要做**大括号配对**截取函数体，
     * 而注释里的 `{` / `}` 会打乱配对。这里改成「原地抹成空格」，
     * 同时用状态机跳过字符串字面量与字符字面量，避免把 `"{"` 当成真括号。
     */
    private fun stripCommentsPreservingStructure(source: String): String {
        val sb = StringBuilder(source.length)
        var i = 0
        var inLineComment = false
        var inBlockComment = false
        var inString = false
        var inTripleString = false
        var escaped = false

        while (i < source.length) {
            val c = source[i]
            val next = if (i + 1 < source.length) source[i + 1] else '\u0000'

            when {
                inLineComment -> {
                    if (c == '\n') {
                        inLineComment = false
                        sb.append('\n')
                    } else {
                        sb.append(' ')
                    }
                }
                inBlockComment -> {
                    if (c == '*' && next == '/') {
                        inBlockComment = false
                        sb.append("  ")
                        i++
                    } else {
                        sb.append(if (c == '\n') '\n' else ' ')
                    }
                }
                inTripleString -> {
                    sb.append(c)
                    if (c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"') {
                        inTripleString = false
                        sb.append("\"\"")
                        i += 2
                    }
                }
                inString -> {
                    sb.append(c)
                    if (escaped) {
                        escaped = false
                    } else if (c == '\\') {
                        escaped = true
                    } else if (c == '"') {
                        inString = false
                    }
                }
                else -> when {
                    c == '/' && next == '/' -> {
                        inLineComment = true
                        sb.append("  ")
                        i++
                    }
                    c == '/' && next == '*' -> {
                        inBlockComment = true
                        sb.append("  ")
                        i++
                    }
                    c == '"' && next == '"' && i + 2 < source.length && source[i + 2] == '"' -> {
                        inTripleString = true
                        sb.append("\"\"\"")
                        i += 2
                    }
                    c == '"' -> {
                        inString = true
                        sb.append(c)
                    }
                    else -> sb.append(c)
                }
            }
            i++
        }
        return sb.toString()
    }

    /**
     * 按**大括号配对**截取函数体（正文，不含最外层 `{}`）。
     *
     * ⚠️ **不用正则** —— 函数体里有字符串与嵌套 lambda，正则会在第一个 `}` 就断。
     *
     * @param signature 函数签名里**足够独特**的一段（如 `fun replaceAllWorkflows(`）。
     */
    private fun functionBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
        if (start < 0) return null

        var i = source.indexOf('{', start)
        if (i < 0) return null

        var depth = 0
        val bodyStart = i + 1
        while (i < source.length) {
            when (source[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(bodyStart, i)
                }
            }
            i++
        }
        return null
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }

    // ═══ 1 & 2. AndroidBackupEnvironment 的两条路径各走各的 ═══

    @Test
    fun `AndroidBackupEnvironment wires REPLACE to the new replace methods`() {
        val env = stripCommentsPreservingStructure(file(ANDROID_ENV).readText())
        assertTrue(
            "❌ AndroidBackupEnvironment 没有调用 replaceAllWorkflows —— " +
                "REPLACE 会退化成合并语义，旧工作流静默残留",
            env.contains("replaceAllWorkflows")
        )
        assertTrue(
            "❌ AndroidBackupEnvironment 没有调用 replaceAllFolders",
            env.contains("replaceAllFolders")
        )
    }

    @Test
    fun `AndroidBackupEnvironment still wires MERGE to the merging methods`() {
        // 两条路径必须**各走各的**，不能互相顶替（否则 REPLACE 修好了、MERGE 又坏了）。
        val env = stripCommentsPreservingStructure(file(ANDROID_ENV).readText())
        assertTrue(
            "❌ AndroidBackupEnvironment 缺少 saveAllWorkflows —— MERGE 路径丢了",
            env.contains("saveAllWorkflows")
        )
        assertTrue(
            "❌ AndroidBackupEnvironment 缺少 saveFolder —— MERGE 路径丢了",
            env.contains("saveFolder")
        )
    }

    // ═══ 3. replaceAllWorkflows 必须是单次原子写 ═══

    @Test
    fun `replaceAllWorkflows_isASingleAtomicWrite`() {
        val source = stripCommentsPreservingStructure(file(WORKFLOW_MANAGER).readText())
        val body = functionBody(source, "fun replaceAllWorkflows(")

        assertNotNull("❌ WorkflowManager 里找不到 replaceAllWorkflows 函数", body)
        val code = body!!

        // 防空转：函数体确实被截到了
        assertTrue("截取到的函数体异常（可能是大括号配对失败）", code.contains("normalizeWorkflow"))

        assertEquals(
            "❌ replaceAllWorkflows 必须**恰好一次** prefs.edit()（两次写 = 中途崩溃数据全灭）",
            1, countOccurrences(code, "prefs.edit()")
        )
        assertEquals(
            "❌ 必须恰好一处 putString(\"workflow_list\"",
            1, countOccurrences(code, "putString(\"workflow_list\"")
        )
        assertTrue(
            "❌ replaceAllWorkflows 不得调用 saveAllWorkflows（那是合并语义，旧工作流会残留）",
            !code.contains("saveAllWorkflows")
        )
        assertTrue(
            "❌ replaceAllWorkflows 不得调用 clearAllWorkflows（两次写 = 中途崩溃数据全灭）",
            !code.contains("clearAllWorkflows")
        )
    }

    @Test
    fun `replaceAllWorkflows still normalizes like the read path does`() {
        val source = stripCommentsPreservingStructure(file(WORKFLOW_MANAGER).readText())
        val body = functionBody(source, "fun replaceAllWorkflows(")!!
        assertTrue(
            "❌ replaceAllWorkflows 必须做 normalizeWorkflow（与 getAllWorkflows 的读取路径对称）",
            body.contains("normalizeWorkflow")
        )
    }

    // ═══ 4. FolderManager 不得重生成 id ═══

    @Test
    fun `replaceAllFolders never regenerates folder ids`() {
        val source = stripCommentsPreservingStructure(file(FOLDER_MANAGER).readText())
        val body = functionBody(source, "fun replaceAllFolders(")

        assertNotNull("❌ FolderManager 里找不到 replaceAllFolders 函数", body)
        assertTrue(
            "❌ replaceAllFolders 里不得出现 UUID.randomUUID —— " +
                "文件夹恢复重生成 id 会让所有 workflow.folderId 悬空",
            !body!!.contains("UUID.randomUUID")
        )
        // 它应当委托既有的单次写方法，而不是另起一份序列化
        assertTrue(
            "❌ replaceAllFolders 应委托既有 saveAllFolders（不新增写路径、不产生第二份格式）",
            body.contains("saveAllFolders")
        )
    }

    // ═══ 5. 两处 Gson 构造必须一致 ═══

    @Test
    fun `both sides build gson with the same VObject adapter`() {
        val adapterCall = "registerTypeHierarchyAdapter(VObject::class.java, VObjectGsonAdapter())"

        val manager = stripCommentsPreservingStructure(file(WORKFLOW_MANAGER).readText())
            .replace(Regex("\\s+"), " ")
        val env = stripCommentsPreservingStructure(file(ANDROID_ENV).readText())
            .replace(Regex("\\s+"), " ")

        assertTrue(
            "❌ WorkflowManager 缺少 VObject typeHierarchyAdapter —— 基准被改动了？",
            manager.contains(adapterCall)
        )
        assertTrue(
            "❌ AndroidBackupEnvironment 必须与 WorkflowManager 用同一套 Gson 构造方式，\n" +
                "否则「备份写出的形状」与「WorkflowManager 读回的形状」会不一致，\n" +
                "而两端各自都不报错。",
            env.contains(adapterCall)
        )
    }

    // ═══ 6. 防空转 ═══

    @Test
    fun `scanned sources are non empty so the checks above are not vacuous`() {
        val paths = listOf(WORKFLOW_MANAGER, FOLDER_MANAGER, ANDROID_ENV)
        assertEquals(3, paths.size)
        paths.forEach { path ->
            val stripped = stripCommentsPreservingStructure(file(path).readText())
            assertTrue("$path 剥注释后为空 —— 剥离逻辑可能剥过头了", stripped.isNotBlank())
            assertTrue("$path 剥注释后看不到 fun 定义", stripped.contains("fun "))
        }
    }

    @Test
    fun `brace matching actually extracts a body rather than everything`() {
        // ⚠️ 配对失败时 functionBody 会返回 null（被上面的 assertNotNull 拦住），
        //    但「返回了整份文件」也要拦 —— 那会让所有 contains 断言假绿。
        val source = stripCommentsPreservingStructure(file(FOLDER_MANAGER).readText())
        val body = functionBody(source, "fun replaceAllFolders(")!!
        assertTrue(
            "截取到的函数体比整份文件还大，说明大括号配对没生效",
            body.length < source.length / 2
        )
    }
}
