package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `parseEnvironmentOperation` / `describeEnvironmentOperation` 的纯函数语义测试。
 *
 * 设计文档：`docs/fork/environment-write-tool.md`（§4.3 各 operation 的语义与校验、§7 测试清单）。
 *
 * ## 为什么集中测这几类
 *
 * 本仓库判「静默失效」的标准是**改错了不报错、只静默变差**。本工具里有三处正好是那个形态：
 *
 * | 陷阱 | 不测的后果 |
 * |---|---|
 * | `GlobalVariableStore.put` 是 **upsert** | 同名调用会**静默覆盖**用户已有的值并回 success（唯一能改坏数据的路径） |
 * | `rename_folder` 的同名判据漏了「排除自己」 | 「改名成当前名」被自己挡住，报错说「名字已存在」——**指向错误方向** |
 * | `String.toBoolean()` 静默返回 false | `value: "yes"` 变成「静默存成 false 并回 success」 |
 *
 * ⚠️ 入参一律是**原始 JSON 字符串**（不是 `Map`）：喂 `mapOf` 会绕过解析层，
 * 而「测试夹具的类型必须等于生产数据的类型」这条本仓库已记过教训。
 */
class EnvironmentOperationParseTest {

    private fun parse(
        argumentsJson: String,
        folders: List<WorkflowFolder> = emptyList(),
        globals: Set<String> = emptySet(),
    ): EnvironmentPlan = parseEnvironmentOperation(argumentsJson, folders, globals)

    private fun planned(
        argumentsJson: String,
        folders: List<WorkflowFolder> = emptyList(),
        globals: Set<String> = emptySet(),
    ): EnvironmentOperation {
        val plan = parse(argumentsJson, folders, globals)
        assertTrue("期望 Planned，实际：$plan", plan is EnvironmentPlan.Planned)
        return (plan as EnvironmentPlan.Planned).operation
    }

    private fun rejectedMessage(
        argumentsJson: String,
        folders: List<WorkflowFolder> = emptyList(),
        globals: Set<String> = emptySet(),
    ): String {
        val plan = parse(argumentsJson, folders, globals)
        assertTrue("期望 Rejected，实际：$plan", plan is EnvironmentPlan.Rejected)
        return (plan as EnvironmentPlan.Rejected).message
    }

    // ── 1. 键白名单（静默失效点 5）─────────────────────────────────────

    @Test
    fun `parent_id is explicitly rejected, not silently ignored`() {
        // ⚠️ schema 里的 `additionalProperties: false` 只是**给模型的建议** ——
        //    手写 JSON 的模型照样能塞这个键。没有显式白名单，它会**被静默忽略**，
        //    而模型以为嵌套文件夹建成了。
        val message = rejectedMessage("""{"operation":"create_folder","name":"x","parent_id":"p"}""")
        assertTrue("错误文案必须点名那个非法键：$message", message.contains("parent_id"))
        assertTrue("错误文案必须列出该 operation 接受的键：$message", message.contains("Accepted keys"))
        assertTrue("错误文案应点明没有嵌套：$message", message.contains("nesting"))
    }

    @Test
    fun `any unknown key is rejected`() {
        val message = rejectedMessage("""{"operation":"dissolve_folder","folder_id":"f1","force":true}""")
        assertTrue("错误文案必须点名那个非法键：$message", message.contains("force"))
    }

    // ── 2. 畸形入参 ────────────────────────────────────────────────────

    @Test
    fun `non-object or malformed json is rejected`() {
        assertTrue(rejectedMessage("[]").isNotBlank())
        assertTrue(rejectedMessage("{").isNotBlank())
    }

    @Test
    fun `missing or unknown operation is rejected with the valid list`() {
        val missing = rejectedMessage("{}")
        assertTrue("必须点明 operation 缺失：$missing", missing.contains("missing"))
        assertTrue("必须列出合法值：$missing", missing.contains("create_folder"))

        val unknown = rejectedMessage("""{"operation":"drop_database"}""")
        assertTrue("必须回显模型给的那个值：$unknown", unknown.contains("drop_database"))
        assertTrue("必须列出合法值：$unknown", unknown.contains("delete_global_variable"))
    }

    // ── 3. create_folder ──────────────────────────────────────────────

    @Test
    fun `create_folder requires a non-blank name`() {
        assertTrue(rejectedMessage("""{"operation":"create_folder"}""").contains("name"))
        assertTrue(rejectedMessage("""{"operation":"create_folder","name":"   "}""").contains("name"))
    }

    @Test
    fun `create_folder plans a new folder with the trimmed name`() {
        val operation = planned("""{"operation":"create_folder","name":"  自动化  "}""")
        assertTrue(operation is EnvironmentOperation.CreateFolder)
        val folder = (operation as EnvironmentOperation.CreateFolder).folder
        assertEquals("自动化", folder.name)
        // 不传 parentId / order：保持默认（与 UI 的新建一致）。
        assertEquals(null, folder.parentId)
        assertEquals(0, folder.order)
    }

    /** 反向锁（§7 大小写）：既有 `A` 时新建 `"a"` **必须拒绝** —— 判据要忽略大小写。 */
    @Test
    fun `create_folder rejects a name that only differs in case`() {
        val existing = listOf(WorkflowFolder(id = "f1", name = "Auto"))
        val message = rejectedMessage("""{"operation":"create_folder","name":"auto"}""", folders = existing)
        // ⚠️ 文案里要给出已存在那个文件夹的 id —— 这恰好是**自愈**的：
        //    模型读到 id 就能直接用它，而不是换个名字重试。
        assertTrue("错误文案必须附已存在的 id：$message", message.contains("f1"))
    }

    // ── 4. rename_folder ──────────────────────────────────────────────

    @Test
    fun `rename_folder keeps the id and only changes the name`() {
        val existing = listOf(WorkflowFolder(id = "f1", name = "旧名", order = 3))
        val operation = planned("""{"operation":"rename_folder","folder_id":"f1","new_name":"新名"}""", folders = existing)
        assertTrue(operation is EnvironmentOperation.RenameFolder)
        val rename = operation as EnvironmentOperation.RenameFolder
        assertEquals("旧名", rename.oldName)
        assertEquals("新名", rename.folder.name)
        // ⚠️ 改名**不动 id** —— 这正是它「无条件可逆、不打断任何引用」的全部依据。
        assertEquals("f1", rename.folder.id)
        assertEquals("其余字段（order / parentId / createdAt）原样保留", 3, rename.folder.order)
    }

    /**
     * **反向锁 ②**（§7 反向锁 ②）：只有一个文件夹 `A`，把 `A` 改名成 `"A"` ⇒ **必须放行**。
     *
     * 这条锁的是判据里的 `it.id != folderId` 那个排除条件。漏了它，用户会收到
     * 「名字已存在」——一个**指向错误方向**的报错（他会去改别的文件夹）。
     */
    @Test
    fun `renaming a folder to its own current name is allowed`() {
        val existing = listOf(WorkflowFolder(id = "A", name = "A"))
        val operation = planned("""{"operation":"rename_folder","folder_id":"A","new_name":"A"}""", folders = existing)
        assertTrue(operation is EnvironmentOperation.RenameFolder)
        assertEquals("A", (operation as EnvironmentOperation.RenameFolder).folder.name)
    }

    /**
     * **大小写 · 自改**（§7 大小写 · 自改）：只有 `A` 时把 `A` 改成 `"a"` ⇒ **放行**。
     *
     * ⚠️ 与上面两条 `create_folder` / `rename_folder` 的大小写锁**方向相反**，
     * 三条合起来才钉死「忽略大小写 + 排除自己」这个看起来矛盾的正确行为：
     * 只测「新建 `"a"` 被拒」会把实现推向「一律拒绝」，只测这条会推向「一律放行」。
     */
    @Test
    fun `changing only the case of a folder's own name is allowed`() {
        val existing = listOf(WorkflowFolder(id = "A", name = "A"))
        val operation = planned("""{"operation":"rename_folder","folder_id":"A","new_name":"a"}""", folders = existing)
        assertTrue(operation is EnvironmentOperation.RenameFolder)
        assertEquals("a", (operation as EnvironmentOperation.RenameFolder).folder.name)
    }

    @Test
    fun `rename_folder rejects a name already used by another folder`() {
        val existing = listOf(
            WorkflowFolder(id = "A", name = "甲"),
            WorkflowFolder(id = "B", name = "乙"),
        )
        val message = rejectedMessage(
            """{"operation":"rename_folder","folder_id":"A","new_name":"乙"}""",
            folders = existing,
        )
        assertTrue("错误文案必须附上冲突方的 id：$message", message.contains("B"))
    }

    @Test
    fun `rename_folder requires folder_id and a non-blank new_name`() {
        assertTrue(rejectedMessage("""{"operation":"rename_folder","new_name":"x"}""").contains("folder_id"))
        val existing = listOf(WorkflowFolder(id = "f1", name = "甲"))
        assertTrue(
            rejectedMessage(
                """{"operation":"rename_folder","folder_id":"f1","new_name":"  "}""",
                folders = existing,
            ).contains("new_name")
        )
    }

    // ── 5. dissolve_folder ────────────────────────────────────────────

    @Test
    fun `dissolve_folder requires an existing folder id and lists the real ones`() {
        val existing = listOf(WorkflowFolder(id = "f1", name = "甲"))
        val message = rejectedMessage("""{"operation":"dissolve_folder","folder_id":"ghost"}""", folders = existing)
        // 自愈文案：给出真实 id，模型下一次调用就能改对。
        assertTrue("错误文案必须附真实 id 列表：$message", message.contains("f1"))
        assertTrue("错误文案必须点明「传 id、不是名字」：$message", message.contains("id"))
    }

    /**
     * `dissolve_folder` 遇子文件夹 ⇒ 拒绝。
     *
     * ⚠️ **防御性**检查，不是缺陷修复：本 App 不存在嵌套文件夹（决策 8），
     * `parentId` 当前恒为 null。正常数据永不命中；命中了说明数据被外部改过。
     */
    @Test
    fun `dissolve_folder rejects a folder that still has child folders`() {
        val existing = listOf(
            WorkflowFolder(id = "parent", name = "父"),
            WorkflowFolder(id = "child", name = "子", parentId = "parent"),
        )
        val message = rejectedMessage("""{"operation":"dissolve_folder","folder_id":"parent"}""", folders = existing)
        assertTrue("错误文案必须点名那个子文件夹：$message", message.contains("子"))
    }

    @Test
    fun `dissolve_folder plans when there is no child folder`() {
        val existing = listOf(WorkflowFolder(id = "f1", name = "甲"))
        val operation = planned("""{"operation":"dissolve_folder","folder_id":"f1"}""", folders = existing)
        assertTrue(operation is EnvironmentOperation.DissolveFolder)
        // 名字在解析期就固定下来：执行期只按 id 找，不必再查一次（也就不存在「查时已被改名」）。
        assertEquals("甲", (operation as EnvironmentOperation.DissolveFolder).folderName)
    }

    // ── 6. create_global_variable ─────────────────────────────────────

    /**
     * **反向锁 ①**（§7 反向锁 ①）：同名已存在 ⇒ **必须回拒，绝不能回成功**。
     *
     * 这条直接锁 §2.2 的 upsert 陷阱：`GlobalVariableStore.put` 同名会**静默覆盖**且返回 success，
     * 是本工具唯一能静默改坏用户数据的路径。
     */
    @Test
    fun `create_global_variable never overwrites an existing name`() {
        val plan = parse(
            """{"operation":"create_global_variable","name":"x","value":"1","type":"number"}""",
            globals = setOf("x"),
        )
        assertTrue("同名必须回拒，实际：$plan", plan is EnvironmentPlan.Rejected)
        // 断言它**不是** Planned —— 只断言「有错误消息」是不够的，那种写法在恒成功时也会绿。
        assertTrue(plan !is EnvironmentPlan.Planned)
        val message = (plan as EnvironmentPlan.Rejected).message
        assertTrue("必须给出出路（先删再建 / 设置页改）：$message", message.contains("delete_global_variable"))
    }

    @Test
    fun `create_global_variable rejects an out-of-range type`() {
        val message = rejectedMessage(
            """{"operation":"create_global_variable","name":"x","value":"2026-10-06","type":"date"}"""
        )
        assertTrue("必须回显模型给的类型：$message", message.contains("date"))
        assertTrue("必须列出合法类型：$message", message.contains("boolean"))
    }

    @Test
    fun `create_global_variable requires a type`() {
        // schema 里刻意不给 `default`，执行层把它当**必填** —— 省略时无从判断它想要哪种类型。
        assertTrue(
            rejectedMessage("""{"operation":"create_global_variable","name":"x","value":"1"}""")
                .contains("type")
        )
    }

    @Test
    fun `number type needs a parseable value`() {
        val message = rejectedMessage(
            """{"operation":"create_global_variable","name":"x","value":"abc","type":"number"}"""
        )
        assertTrue("错误文案应回显收到的值：$message", message.contains("abc"))
    }

    @Test
    fun `number type produces a VNumber and reports the type it wrote`() {
        val operation = planned(
            """{"operation":"create_global_variable","name":"n","value":"42.5","type":"number"}"""
        )
        assertTrue(operation is EnvironmentOperation.CreateGlobalVariable)
        val variable = operation as EnvironmentOperation.CreateGlobalVariable
        assertTrue("必须构造 VNumber", variable.value is VNumber)
        assertEquals(42.5, (variable.value as VNumber).raw.toDouble(), 0.0001)
        // 类型**按实际写入的那个报**，不写死 string —— 模型据此核对类型有没有落对。
        assertTrue(describeEnvironmentOperation(operation).contains("(number)"))
    }

    /**
     * **`boolean` 解析失败 ⇒ 拒**。
     *
     * ⚠️ 这条锁的是 `String.toBoolean()` 那个静默陷阱：`"yes".toBoolean()` **返回 false
     * 且不抛异常**，用它会得到「静默存成 false 并回 success」。
     * 用 `toBoolean()` 实现时本用例**必须变红**。
     */
    @Test
    fun `boolean type only accepts a literal true or false`() {
        val message = rejectedMessage(
            """{"operation":"create_global_variable","name":"x","value":"yes","type":"boolean"}"""
        )
        assertTrue("错误文案应回显收到的值：$message", message.contains("yes"))
        // 同一形态的另一种常见误写（0/1）也必须被拒。
        assertTrue(
            rejectedMessage("""{"operation":"create_global_variable","name":"x","value":"1","type":"boolean"}""")
                .contains("1")
        )
    }

    @Test
    fun `boolean type accepts either case`() {
        val upper = planned("""{"operation":"create_global_variable","name":"x","value":"TRUE","type":"boolean"}""")
        assertEquals(true, (upper as EnvironmentOperation.CreateGlobalVariable).value.let { it as VBoolean }.raw)

        val mixed = planned("""{"operation":"create_global_variable","name":"y","value":"False","type":"boolean"}""")
        assertEquals(false, (mixed as EnvironmentOperation.CreateGlobalVariable).value.let { it as VBoolean }.raw)
    }

    @Test
    fun `string type keeps the value verbatim and trims only the name`() {
        val operation = planned(
            """{"operation":"create_global_variable","name":"  s  ","value":"  保留空格  ","type":"string"}"""
        )
        val variable = operation as EnvironmentOperation.CreateGlobalVariable
        // 名字去掉首尾空白（用于匹配）；**值原样保留** —— 首尾空格可能是用户真想存的东西。
        assertEquals("s", variable.name)
        assertEquals("  保留空格  ", (variable.value as VString).raw)
    }

    // ── 7. delete_global_variable ─────────────────────────────────────

    @Test
    fun `delete_global_variable requires an existing name and lists the real ones`() {
        val message = rejectedMessage(
            """{"operation":"delete_global_variable","name":"ghost"}""",
            globals = setOf("alpha", "beta"),
        )
        assertTrue("错误文案必须附真实名字：$message", message.contains("alpha"))
        assertTrue("错误文案必须附真实名字：$message", message.contains("beta"))
    }

    @Test
    fun `delete_global_variable matches the name case-sensitively`() {
        // ⚠️ 与文件夹侧**相反**：变量引用 `{{global.<name>}}` 按 key **精确**取值，
        //    设置页的重复检查也是区分大小写的。放行大小写不同的名字会让删除落空。
        val message = rejectedMessage(
            """{"operation":"delete_global_variable","name":"ALPHA"}""",
            globals = setOf("alpha"),
        )
        assertTrue("必须附真实名字：$message", message.contains("alpha"))
    }

    @Test
    fun `delete_global_variable plans for an existing name`() {
        val operation = planned(
            """{"operation":"delete_global_variable","name":"alpha"}""",
            globals = setOf("alpha"),
        )
        assertEquals("alpha", (operation as EnvironmentOperation.DeleteGlobalVariable).name)
    }

    // ── 8. 风险等级（设计 §4.4）────────────────────────────────────────

    @Test
    fun `risk levels follow the operation and never reach HIGH`() {
        // ⚠️ 标 HIGH 等于「除非开 ALL 否则永远弹窗」—— 而用户审批的是「删一个空文件夹」，
        //    与提示不成比例（本仓库在 LogModule 上记过一模一样的教训）。
        assertEquals(ChatAgentToolRiskLevel.LOW, EnvironmentOperation.CreateFolder(WorkflowFolder(name = "x")).riskLevel)
        assertEquals(
            ChatAgentToolRiskLevel.LOW,
            EnvironmentOperation.RenameFolder(WorkflowFolder(name = "x"), oldName = "y").riskLevel,
        )
        assertEquals(ChatAgentToolRiskLevel.LOW, EnvironmentOperation.CreateGlobalVariable("x", VString("1")).riskLevel)
        assertEquals(ChatAgentToolRiskLevel.STANDARD, EnvironmentOperation.DissolveFolder("f", "n").riskLevel)
        assertEquals(ChatAgentToolRiskLevel.STANDARD, EnvironmentOperation.DeleteGlobalVariable("x").riskLevel)
    }

    // ── 9. 回执文案 ────────────────────────────────────────────────────

    @Test
    fun `describe reports every operation with the details the model needs`() {
        val created = describeEnvironmentOperation(
            EnvironmentOperation.CreateFolder(WorkflowFolder(id = "f1", name = "甲"))
        )
        // ⚠️ 必须报出 id：模型后续要把工作流放进这个文件夹，靠的就是它。
        assertEquals("Created folder \"甲\" (id: f1).", created)

        val renamed = describeEnvironmentOperation(
            EnvironmentOperation.RenameFolder(WorkflowFolder(id = "f1", name = "新"), oldName = "旧")
        )
        // ⚠️ **必须报出旧名**：模型可能在多轮之间改了别的文件夹，只有新名它无法确认改的是哪一个。
        assertEquals("Renamed folder \"旧\" -> \"新\".", renamed)

        val dissolved = describeEnvironmentOperation(
            EnvironmentOperation.DissolveFolder("f1", "甲"),
            affectedWorkflowCount = 3,
        )
        assertEquals("Dissolved folder \"甲\". 3 workflow(s) moved to the root.", dissolved)

        val createdVariable = describeEnvironmentOperation(
            EnvironmentOperation.CreateGlobalVariable("n", VNumber(1))
        )
        assertEquals("Created global variable \"n\" (number).", createdVariable)

        val deleted = describeEnvironmentOperation(EnvironmentOperation.DeleteGlobalVariable("x"))
        assertEquals(
            "Deleted global variable \"x\". " +
                "Existing {{global.x}} references in workflows will no longer resolve.",
            deleted,
        )
    }

    @Test
    fun `describe for delete warns about references without claiming a scan`() {
        // ⚠️ P0 **没有**做引用扫描（设计 §4.5 定 P1）。文案只说「将不再解析」——
        //    不得声称扫过、不得报出条数（那是 P1 才能给的东西）。
        val text = describeEnvironmentOperation(EnvironmentOperation.DeleteGlobalVariable("x"))
        assertTrue(text.contains("{{global.x}}"))
        assertTrue("不得声称已扫描：$text", !text.contains("workflow(s) still"))
    }
}
