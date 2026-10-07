package com.chaomixian.vflow.ui.shortcut

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.core.workflow.model.Workflow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `CREATE_SHORTCUT` 响应页的**纯函数层**逐格验证。
 *
 * ⚠️⚠️ 本文件的每一条断言都对应一个**静默失效**：判据写错只会让第三方 App 的列表里
 * 少几个（或多几个）工作流，**不会有任何报错**；显示名规则与 `ShortcutHelper`
 * 漂移会让同一个工作流在两处名字不同，用户当成两个东西。这两件事都只能靠断言锁。
 */
class CreateShortcutSupportTest {

    private val manualTrigger = ActionStep(
        moduleId = "vflow.trigger.manual",
        parameters = emptyMap(),
        id = "t-manual",
    )
    private val autoTrigger = ActionStep(
        moduleId = "vflow.trigger.time",
        parameters = emptyMap(),
        id = "t-auto",
    )

    private fun workflow(
        id: String = "wf",
        name: String = "W",
        triggers: List<ActionStep> = listOf(manualTrigger),
        shortcutName: String? = null,
        functionSignature: FunctionSignature? = null,
    ) = Workflow(
        id = id,
        name = name,
        triggers = triggers,
        shortcutName = shortcutName,
        functionSignature = functionSignature,
    )

    // ── 可选项判据 ────────────────────────────────────────

    @Test
    fun `a manual-triggered workflow is pickable`() {
        assertEquals(1, CreateShortcutSupport.pickableWorkflows(listOf(workflow())).size)
    }

    @Test
    fun `a manual plus auto workflow is still pickable`() {
        // ★ 这是与磁贴 `TileGate` **刻意不同**的那一格（用户 2026-10-07 定案）：
        //   磁贴的执行池排斥自动工作流（自动工作流在磁贴上该是个开关），
        //   而「创建快捷方式」只是给用户一个手动点火入口 —— 挂了定时触发器
        //   的工作流照样能手动跑，没有理由藏起来。
        val mixed = workflow(triggers = listOf(manualTrigger, autoTrigger))
        assertEquals(
            "手动 + 自动并存时**仍要**出现在快捷方式列表里",
            1,
            CreateShortcutSupport.pickableWorkflows(listOf(mixed)).size,
        )
    }

    @Test
    fun `an auto-only workflow is not pickable`() {
        // ★ 判据的核心：只有手动触发器才算数。
        assertTrue(
            CreateShortcutSupport.pickableWorkflows(
                listOf(workflow(triggers = listOf(autoTrigger)))
            ).isEmpty()
        )
    }

    @Test
    fun `a workflow without any trigger is not pickable`() {
        assertTrue(
            CreateShortcutSupport.pickableWorkflows(listOf(workflow(triggers = emptyList()))).isEmpty()
        )
    }

    @Test
    fun `a function workflow is listed as long as it has a manual trigger`() {
        // ★ 用户 2026-10-07 明确要求：函数工作流**要列**，判据只看手动触发器。
        //   （曾经的候选写法是「排除函数工作流」—— 那会让用户在第三方工具里
        //     找不到自己写的函数，而函数本就经常被手动调试。）
        val fn = workflow(
            name = "我的函数",
            functionSignature = FunctionSignature(params = emptyList(), returnDef = null),
        )
        assertEquals(
            "函数工作流不能因为 isFunction 被排除",
            1,
            CreateShortcutSupport.pickableWorkflows(listOf(fn)).size,
        )
    }

    @Test
    fun `pickable workflows are sorted by name with a Chinese collator`() {
        val pickable = CreateShortcutSupport.pickableWorkflows(
            listOf(
                workflow(id = "c", name = "次"),
                workflow(id = "a", name = "阿"),
                workflow(id = "b", name = "波"),
            )
        )
        assertEquals(listOf("阿", "波", "次"), pickable.map { it.name })
    }

    @Test
    fun `ordering is independent of the input order`() {
        val ascending = CreateShortcutSupport.pickableWorkflows(
            listOf(workflow(id = "1", name = "阿"), workflow(id = "2", name = "波"))
        ).map { it.id }
        val descending = CreateShortcutSupport.pickableWorkflows(
            listOf(workflow(id = "2", name = "波"), workflow(id = "1", name = "阿"))
        ).map { it.id }
        assertEquals(ascending, descending)
    }

    @Test
    fun `non-pickable workflows are filtered out but keep the relative order of the rest`() {
        val pickable = CreateShortcutSupport.pickableWorkflows(
            listOf(
                workflow(id = "a", name = "阿"),
                workflow(id = "no", name = "自动的", triggers = listOf(autoTrigger)),
                workflow(id = "b", name = "波"),
            )
        )
        assertFalse("自动工作流必须被滤掉", pickable.any { it.id == "no" })
        assertEquals(listOf("a", "b"), pickable.map { it.id })
    }

    // ── 显示名 ────────────────────────────────────────────

    @Test
    fun `a custom shortcut name wins`() {
        assertEquals("我的名字", CreateShortcutSupport.shortcutLabelOf(workflow(shortcutName = "我的名字")))
    }

    @Test
    fun `a null shortcut name falls back to the workflow name`() {
        assertEquals("W", CreateShortcutSupport.shortcutLabelOf(workflow(name = "W", shortcutName = null)))
    }

    @Test
    fun `an empty shortcut name falls back to the workflow name`() {
        // ⚠️ 存量数据 / 外部导入的 shortcutName 可能是空串（编辑器把空白转 null，
        //    但别的写入路径不一定）。直接用会让第三方列表里出现一格空白。
        assertEquals(
            "空串必须按「未设置」处理",
            "W",
            CreateShortcutSupport.shortcutLabelOf(workflow(name = "W", shortcutName = "")),
        )
    }

    // ── 接线锚定（源码扫描型）──────────────────────────────

    @Test
    fun `the activity is declared with the CREATE_SHORTCUT intent filter`() {
        // ⚠️ 少了这条声明，第三方 App 的 queryIntentActivities 里**根本看不到 vFlow** ——
        //    表现是「在 ShortX 里找不到 vFlow」，而 App 侧一切正常、零报错。
        val manifest = SourceScan.file("src/main/AndroidManifest.xml").readText()
        assertTrue(
            "manifest 必须声明 CreateShortcutActivity",
            manifest.contains("""android:name=".ui.shortcut.CreateShortcutActivity"""")
        )
        assertTrue(
            "必须带 CREATE_SHORTCUT intent-filter",
            manifest.contains("""android:name="android.intent.action.CREATE_SHORTCUT"""")
        )
    }

    @Test
    fun `the result carries the two shortcut extras the caller reads`() {
        // ⚠️⚠️ 这两个键是**跨 App 协议**（ShortX 读的就是它们）。改成新 API 或拼错，
        //    对方会一个都读不到 —— 且它只表现为「用户取消了」，完全静默。
        //    ⚠️ 剥注释后断言：KDoc 里逐字写着这两个键名，不剥的话删掉真实调用也照样绿。
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/shortcut/CreateShortcutActivity.kt"
        )
        assertTrue(
            "必须把执行 Intent 放进 EXTRA_SHORTCUT_INTENT",
            source.contains("Intent.EXTRA_SHORTCUT_INTENT"),
        )
        assertTrue(
            "必须把显示名放进 EXTRA_SHORTCUT_NAME",
            source.contains("Intent.EXTRA_SHORTCUT_NAME"),
        )
        assertTrue(
            "✨ 必须是成功结果码，否则调用方一律当成「用户取消」",
            source.contains("setResult(Activity.RESULT_OK, result)"),
        )
    }

    @Test
    fun `both shortcut paths share one execution intent and one label rule`() {
        // ⚠️ 两条路（桌面长按图标 / 第三方 App 来取）造出的 Intent 与显示名**必须同源**。
        //    各写一份一旦漂移，表现是「从桌面点能跑、从手势工具点不跑」——完全静默。
        val helper = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/common/ShortcutHelper.kt"
        )
        val activity = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/shortcut/CreateShortcutActivity.kt"
        )
        assertTrue(
            "ShortcutHelper 必须提供共用的 executionIntent",
            helper.contains("fun executionIntent("),
        )
        assertTrue(
            "CreateShortcutActivity 必须走共用的 executionIntent",
            activity.contains("ShortcutHelper.executionIntent("),
        )
        assertTrue(
            "ShortcutHelper 的显示名必须走共用的 shortcutLabelOf",
            helper.contains("CreateShortcutSupport.shortcutLabelOf("),
        )
        assertTrue(
            "CreateShortcutActivity 的显示名必须走共用的 shortcutLabelOf",
            activity.contains("CreateShortcutSupport.shortcutLabelOf("),
        )
    }

    @Test
    fun `an empty shortcut name is not returned verbatim`() {
        val label = CreateShortcutSupport.shortcutLabelOf(workflow(name = "W", shortcutName = ""))
        assertTrue("空串会让第三方列表出现空白格", label.isNotBlank())
    }
}
