package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型**测试：锁住 `update_environment` 的两条**不可能用行为测试覆盖**的接线。
 *
 * 设计文档：`docs/fork/environment-write-tool.md`（§5 静默失效点 1/5/6、§7 测试清单）。
 *
 * ## ⚠️ 为什么必须是源码扫描
 *
 * 1. **「写操作没被放进 prepare 阶段」** —— `ChatAgentModuleExecutor` 依赖 Android `Context`，
 *    纯 JVM 起不来；而这条一旦破了，表现是**审批形同虚设**（`prepareBatch` 会被
 *    `ChatViewModel.shouldAutoApproveToolCalls` 在用户点批准**之前**调用），
 *    没有任何行为测试能观察到。形态照 `AgentErrorDialogWiringTest` / `CoreDexFingerprintTest`。
 * 2. **`get_environment` 的 folders 段补了 `id`** —— 同理由。
 *
 * ## ⚠️⚠️ 判据的两条纪律（本仓库踩过的坑）
 *
 * - **剥注释后再断言**：两处改动的注释正文里恰好写着 `saveFolder` / `parentId` 之类字样，
 *   只做 `contains` 的话「把代码删掉、只留注释」照样绿。
 * - **判据必须锚「新增的那个表达式」**：`get_environment` 的函数体改造前就有
 *   `workflows.count { it.folderId == folder.id }`，裸测 `folder.id` **改前改后都绿**、
 *   反证也变不红。故锚 `append(folder.id)`。
 * - 每条断言都带**防空转**（函数体非空、行数够多）。
 *
 * ⚠️ `ChatAgentModuleExecutor.kt` 的工作区是 **CRLF** ⇒
 * `SourceScan.functionBody` 的签名片段**只能用单行**（带 `\n` 的多行片段匹配不到、返回 null）。
 */
class UpdateEnvironmentWiringTest {

    private companion object {
        const val EXECUTOR_PATH = "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentModuleExecutor.kt"
        const val REGISTRY_PATH = "src/main/java/com/chaomixian/vflow/ui/chat/ChatAgentToolRegistry.kt"

        /**
         * 本工具**只允许出现在 `executeUpdateEnvironment`** 里的五个写调用。
         *
         * ⚠️ `saveFolder` 用短名而不是 `FolderManager(appContext).saveFolder` —— 后者对
         * 「换个变量名调」就不生效了（`val manager = FolderManager(appContext)` 这种写法
         * 在本文件的 `dissolve_folder` 分支里就有）。
         */
        val WRITE_CALLS = listOf(
            "saveFolder",
            "deleteFolder",
            "GlobalVariableStore.put",
            "GlobalVariableStore.remove",
            "saveWorkflow",
        )
    }

    /** 取某个函数的函数体（已剥注释）；断言函数一定存在，避免「改了签名 ⇒ 断言恒真」。 */
    private fun bodyOf(path: String, signature: String): String {
        val body = SourceScan.functionBody(SourceScan.stripped(path), signature)
        assertTrue("未在 $path 找到函数：$signature", body != null)
        return body!!
    }

    // ── 1. 写操作**不在** prepare 阶段（静默失效点 1）──────────────────

    @Test
    fun `no write happens during prepareUpdateEnvironment`() {
        val body = bodyOf(EXECUTOR_PATH, "private fun prepareUpdateEnvironment(")

        // 防空转：函数体必须有实质内容，且确实是本函数（不是被截到别的函数去了）。
        assertTrue("函数体被截空（断言在空转）", body.count { it == '\n' } > 5)
        assertTrue("截到的不是 prepareUpdateEnvironment", body.contains("parseEnvironmentOperation"))

        WRITE_CALLS.forEach { write ->
            assertFalse(
                "`$write` 出现在 prepareUpdateEnvironment 里 —— prepareBatch 会在用户点批准" +
                    "**之前**被 shouldAutoApproveToolCalls 调用，写在 prepare 等于审批形同虚设",
                body.contains(write),
            )
        }
    }

    @Test
    fun `prepareUpdateEnvironment only reads folder and global variable state`() {
        // 正向断言：prepare 阶段**允许**的两个读调用必须真的在（否则它根本没东西可校验）。
        val body = bodyOf(EXECUTOR_PATH, "private fun prepareUpdateEnvironment(")
        assertTrue("必须读既有文件夹", body.contains("getAllFolders()"))
        assertTrue("必须读既有全局变量名", body.contains("GlobalVariableStore.getAll("))
    }

    // ── 2. 五个写调用只出现在 execute 阶段 ────────────────────────────

    @Test
    fun `every write call lives in executeUpdateEnvironment`() {
        val body = bodyOf(EXECUTOR_PATH, "private fun executeUpdateEnvironment(")

        assertTrue("函数体被截空（断言在空转）", body.count { it == '\n' } > 5)
        assertTrue("截到的不是 executeUpdateEnvironment", body.contains("item.operation"))

        // ⚠️ 全部五个都要在 —— 少一个说明搬运没完成（比如某个 operation 的落盘被漏掉了）。
        WRITE_CALLS.forEach { write ->
            assertTrue("`$write` 必须出现在 executeUpdateEnvironment 里（落盘阶段）", body.contains(write))
        }
    }

    @Test
    fun `executeUpdateEnvironment moves workflows out before deleting the folder`() {
        // ⚠️ 顺序不可反：先删文件夹中途崩溃会留下「文件夹没了、工作流还指着它」——
        //    那些工作流会从列表上消失（列表只认「命中已存在文件夹」与 null）。
        val body = bodyOf(EXECUTOR_PATH, "private fun executeUpdateEnvironment(")
        val saveIndex = body.indexOf("saveWorkflow")
        val deleteIndex = body.indexOf("deleteFolder")
        assertTrue("必须有 saveWorkflow 调用", saveIndex >= 0)
        assertTrue("必须有 deleteFolder 调用", deleteIndex >= 0)
        assertTrue("必须先移出工作流、再删文件夹（当前顺序相反）", saveIndex < deleteIndex)
    }

    // ── 3. get_environment 输出：补 id（决策 2）、去 parentId（决策 3）──

    @Test
    fun `get_environment prints the folder id`() {
        val body = bodyOf(EXECUTOR_PATH, "private fun prepareGetEnvironment(")

        assertTrue("函数体被截空（断言在空转）", body.count { it == '\n' } > 5)
        assertTrue("截到的不是 prepareGetEnvironment", body.contains("folders:"))

        // ⚠️⚠️ 判据**不能**是裸的 `folder.id` —— 同函数体改造前就有
        //     `workflows.count { it.folderId == folder.id }` ⇒ 裸标识符断言改前改后都绿。
        //     必须锚**新增的那个表达式**。
        assertTrue(
            "folders 段必须打印 folder.id —— 三条写路径的 folderId / folder_id 只收 id，" +
                "而此前这里是模型拿到文件夹信息的唯一来源、且只有名字",
            body.contains("append(folder.id)"),
        )
        assertTrue(
            "「id:」这个前缀是给模型看的锚点，缺了它模型不一定认出那串是 id",
            body.contains("(id: "),
        )
    }

    @Test
    fun `get_environment no longer prints parentId`() {
        val body = bodyOf(EXECUTOR_PATH, "private fun prepareGetEnvironment(")

        // ⚠️ 这条在改造前**也不含** parentId（判据用的是代码标识符，中文注释里的「父文件夹」
        //    不受影响 —— 且注释已被 SourceScan.stripped 抹掉）。
        //    它的价值是**反向锁**：防止有人把删掉的那三行加回来（决策 3：
        //    本 App 不存在嵌套文件夹，展示它是把不存在的概念摆到模型面前）。
        assertFalse(
            "prepareGetEnvironment 不应再出现 parentId —— 决策 3 要求去掉一切嵌套展示",
            body.contains("parentId"),
        )
        // 防空转的另一半：确认剥注释没把整段抹掉（否则上面这条同样恒真）。
        assertTrue("folders 段应当还在", body.contains("sortedBy { it.order }"))
    }

    // ── 4. 工具真的注册了（防「写了定义但没接上」）─────────────────────

    @Test
    fun `the update_environment tool is defined and registered`() {
        val registry = SourceScan.stripped(REGISTRY_PATH)

        assertTrue("缺少工具名常量", registry.contains("CHAT_UPDATE_ENVIRONMENT_TOOL_NAME = \"vflow_agent_update_environment\""))
        assertTrue("缺少 moduleId 常量", registry.contains("CHAT_UPDATE_ENVIRONMENT_MODULE_ID = \"vflow.agent.update_environment\""))
        assertTrue(
            "缺少 buildUpdateEnvironmentToolDefinition 的定义",
            registry.contains("private fun buildUpdateEnvironmentToolDefinition()"),
        )
        assertTrue("缺少 buildUpdateEnvironmentSchema 的定义", registry.contains("private fun buildUpdateEnvironmentSchema()"))

        // ⚠️ 注册点：带括号的调用必须**恰好一次**（另一次是"定义"那行，带 `private fun `）。
        //    直接把定义也算进去的话，把调用从 listOf 里删掉后计数从 2 变 1、断言仍可写成"≥1"而恒绿。
        val callSites = SourceScan.countOccurrences(registry, "buildUpdateEnvironmentToolDefinition()") -
            SourceScan.countOccurrences(registry, "private fun buildUpdateEnvironmentToolDefinition()")
        assertTrue("buildUpdateEnvironmentToolDefinition() 必须被调用恰一次（toolsByName），实际 $callSites", callSites == 1)
    }

    @Test
    fun `the tool definition declares the three things the model must be told`() {
        val registry = SourceScan.stripped(REGISTRY_PATH)
        val body = SourceScan.functionBody(registry, "private fun buildUpdateEnvironmentToolDefinition()")
        assertTrue("未找到 buildUpdateEnvironmentToolDefinition", body != null)
        val block = body!!

        assertTrue("函数体被截空（断言在空转）", block.count { it == '\n' } > 10)
        assertTrue("截到的不是本工具的定义块", block.contains("CHAT_UPDATE_ENVIRONMENT_TOOL_NAME"))

        // ① 写文件夹要的是 id 不是名字；且点明 list_workflows 的 folder 参数是名字筛选（静默失效点 6）。
        assertTrue("必须说明 folder_id 是 id", block.contains("the id from"))
        assertTrue("必须说明 list_workflows 的 folder 是名字筛选", block.contains("NAME filter"))
        // ② 删全局变量会打断引用。
        assertTrue("必须说明删除会打断 {{global.}} 引用", block.contains("{{global.<name>}}"))
        assertTrue("必须说明引用会静默失效", block.contains("silently stop resolving"))
        // ③ 文件夹不能重名（忽略大小写）+ 没有嵌套 + 没有「连工作流一起删」。
        assertTrue("必须说明重名会被拒", block.contains("already used by another folder"))
        assertTrue("必须说明重名判定忽略大小写", block.contains("ignores case"))
        assertTrue("必须说明没有嵌套文件夹", block.contains("no nested folders"))
        assertTrue("必须说明不存在「连同工作流一起删」", block.contains("NEVER deleted"))
    }

    @Test
    fun `prepareToolCall routes the update_environment tool`() {
        val body = bodyOf(EXECUTOR_PATH, "private fun prepareToolCall(")
        // ⚠️ 必须早返回：本工具的 moduleId 不是注册模块，落到后面的通用分支会命中
        //    「未注册」错误（`Unknown tool` / `not registered` 那条）。
        //
        // ⚠️⚠️ **判据不能是「函数体里出现过这两个字符串」** —— 验收实测过：把分流临时改成
        //    `if (false && toolCall.name == ...)` 时，两个标识符都还在、**断言照绿**，
        //    可那个分流已**永远不可达**（等于分流被删）。故必须锚**完整的条件 + 调用**：
        //    中间只允许空白，`&&` / `false` / 换行改写都会把 `.*` 打断（`DotMatchesAll` 默认关）。
        //
        //    诚实说明这条判据的**边界**：它保证「分流写成了可用的形状」，
        //    但**证不了运行期真的命中**（那需要起 Android 环境）。
        //    若将来 `prepareToolCall` 里出现第二条同名分支，`Regex` 会各自匹配一条 ——
        //    配合上面 bodyOf 的防空转断言，仍是安全的。
        val routing = Regex(
            """if\s*\(\s*toolCall\.name\s*==\s*CHAT_UPDATE_ENVIRONMENT_TOOL_NAME\s*\)""" +
                """\s*\{?\s*return\s+prepareUpdateEnvironment\(toolCall\)\s*\}?""",
            RegexOption.DOT_MATCHES_ALL,
        )
        assertTrue(
            "prepareToolCall 必须有 `if (toolCall.name == CHAT_UPDATE_ENVIRONMENT_TOOL_NAME) " +
                "return prepareUpdateEnvironment(toolCall)` 形态的早返回分流",
            routing.containsMatchIn(body),
        )
    }

    // ── 5. 穷尽 when 的计数（不是「都齐了没」，而是「别多加」）──────────

    @Test
    fun `the source scan is not vacuous`() {
        // 防空转兜底：剥注释后仍应有大量实质代码（防「正则把文件剥空 ⇒ 全部断言恒真」）。
        val executor = SourceScan.stripped(EXECUTOR_PATH)
        val codeLines = executor.lineSequence().count { it.isNotBlank() }
        assertTrue("剥注释后 ChatAgentModuleExecutor 代码行数异常（$codeLines）", codeLines > 2000)
    }
}
