package com.chaomixian.vflow.ui.workflow_list

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「在某个文件夹 Tab 下点 ＋ 新建的工作流自动归入该文件夹」（fork，2026-10-10）的**接线**测试。
 *
 * ## 为什么是源码扫描而不是行为测试
 *
 * 这条链路跨三个文件、两个进程内的 Activity，中间**没有任何可注入的接缝**：
 * `WorkflowListScreen`（Compose，只知道当前选中的 Tab 串）→ `WorkflowListRoute`
 * （起 Intent）→ `WorkflowEditorActivity`（落盘时写 `folderId`）。纯 JVM 测试
 * 够不到任何一环，而三处**各自都能编译通过**、只有连起来才成立。
 *
 * ## 锁的到底是什么
 *
 * 1. **「全部」哨兵串必须换算成 `null`** —— `WORKFLOW_TAB_ALL` 是 UI 概念
 *    （`"vflow.tab.all"`），把它原样写进 `Workflow.folderId` 会得到一个
 *    **指向不存在文件夹的 id**。`filterByFolderTab` 没有「未分类」兜底
 *    （见它的 KDoc：刻意不设），于是该工作流切到任何文件夹都找不到。
 *    ⚠️ 这一条**编译得过、也不崩**，只是「新建完就不在文件夹里」，最容易在重构
 *    「顺手简化成 `actions.onCreateWorkflow(selectedFolderTab)`」时静默坏掉。
 * 2. **归属写在编辑器侧**（`createDraftWorkflow`），不是列表页 —— 列表页点 ＋ 的
 *    那一刻**还没有工作流 id**（要等用户在编辑器里保存才生成），所以归属只能由
 *    编辑器在建草稿时带上去。改成「列表页先建再改」是另一套设计，不该悄悄发生。
 * 3. **编辑器要校验文件夹仍然存在** —— 悬空 `folderId` 同样让工作流在列表页
 *    「任何一个 Tab 下都看不见」，而这一条是**保存成功之后**才暴露的。
 *
 * ⚠️ 断言一律锚到**完整表达式**（不是「文件里出现过某个词」）—— 本仓库记过教训：
 * 只测关键词时，把真实调用删掉、注释里留着同样的词，测试照样绿。
 */
class NewWorkflowFolderWiringTest {

    private companion object {
        const val SCREEN = "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListScreen.kt"
        const val ROUTE = "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"
        const val EDITOR = "src/main/java/com/chaomixian/vflow/ui/workflow_editor/WorkflowEditorActivity.kt"

        /** 防「函数体没截到 ⇒ 断言全空转」的下限（本仓库踩过这个坑）。 */
        const val MIN_BODY = 400
    }

    @Test
    fun `fab converts the all-tab sentinel into null`() {
        val body = SourceScan.functionBody(SourceScan.stripped(SCREEN), "fun WorkflowListScreen(")
        checkNotNull(body) { "没能截取 WorkflowListScreen 函数体（签名可能变了）" }
        assertTrue("防空转：函数体必须真的被截到", body.length > MIN_BODY)

        // ⚠️ 判据是**完整的实参表达式** —— 少了 `takeIf` 那半句就是把哨兵串当文件夹 id 传下去。
        assertTrue(
            "FAB 必须把「全部」哨兵换算成 null 再交给 onCreateWorkflow",
            body.contains("actions.onCreateWorkflow(selectedFolderTab.takeIf { it != WORKFLOW_TAB_ALL })"),
        )

        val actions = SourceScan.stripped(SCREEN)
        assertTrue(
            "onCreateWorkflow 的参数必须可空（null = 「全部」Tab，不归类）",
            actions.contains("val onCreateWorkflow: (String?) -> Unit,"),
        )
    }

    @Test
    fun `route forwards the folder id to the editor`() {
        val body = SourceScan.functionBody(SourceScan.stripped(ROUTE), "fun WorkflowListRoute(")
        checkNotNull(body) { "没能截取 WorkflowListRoute 函数体（签名可能变了）" }
        assertTrue("防空转：函数体必须真的被截到", body.length > MIN_BODY)

        assertTrue(
            "route 的 onCreateWorkflow 必须接收文件夹 id",
            body.contains("onCreateWorkflow = { folderId ->"),
        )
        assertTrue(
            "文件夹 id 必须经 EXTRA_NEW_WORKFLOW_FOLDER_ID 传给编辑器（不能只传个空的 Intent）",
            body.contains("putExtra(WorkflowEditorActivity.EXTRA_NEW_WORKFLOW_FOLDER_ID, folderId)"),
        )
    }

    @Test
    fun `editor stamps the incoming folder id onto the new draft workflow`() {
        val source = SourceScan.stripped(EDITOR)

        val draftBody = SourceScan.functionBody(source, "private fun createDraftWorkflow(")
        checkNotNull(draftBody) { "没能截取 createDraftWorkflow 函数体（签名可能变了）" }
        assertTrue("防空转：函数体必须真的被截到", draftBody.length > MIN_BODY)
        assertTrue(
            "新建草稿必须带上预归属文件夹（否则工作流落在「全部」里）",
            draftBody.contains("folderId = newWorkflowFolderId()"),
        )

        val helperBody = SourceScan.functionBody(source, "private fun newWorkflowFolderId(")
        checkNotNull(helperBody) { "没能截取 newWorkflowFolderId 函数体（签名可能变了）" }
        assertTrue("防空转：函数体必须真的被截到", helperBody.length > 100)
        assertTrue(
            "归属来源必须是列表页传来的 extra",
            helperBody.contains("intent.getStringExtra(EXTRA_NEW_WORKFLOW_FOLDER_ID)"),
        )
        // ⚠️ 悬空 folderId 的工作流在列表页**任何 Tab 下都看不见**（保存成功但「不见了」），
        //    故落盘前必须确认文件夹还在（它可能在编辑器打开期间被远程 API / AI 对话删掉）。
        assertTrue(
            "必须校验文件夹仍然存在，否则会写出悬空 folderId",
            helperBody.contains("FolderManager(this).getFolder(it) != null"),
        )
    }

    @Test
    fun `new-workflow folder extra does not collide with the workflow id extra`() {
        val source = SourceScan.stripped(EDITOR)
        // ⚠️ 两者一旦同值，`editingWorkflowId()` 会读到文件夹 id ⇒ 编辑器把「新建」当成
        //    「打开某个工作流」（查不到 ⇒ 静默退化成新建），故障形态极难定位。
        assertTrue(
            "两个 extra 的 key 不能撞车",
            source.contains("const val EXTRA_WORKFLOW_ID = \"WORKFLOW_ID\"") &&
                source.contains("const val EXTRA_NEW_WORKFLOW_FOLDER_ID = \"NEW_WORKFLOW_FOLDER_ID\""),
        )
    }
}
