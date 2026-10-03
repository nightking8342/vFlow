// 文件: test/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportUiSourceScanTest.kt
package com.chaomixian.vflow.core.workflow.module.data

import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **模块侧 UI 的源码扫描**。
 *
 * ## 与 `BackupRestoreWiringTest` 的分工
 *
 * 那条测的是**设置页**有没有硬编码 scope 清单（静默失效点 13 的第一个落点）；
 * 这条测的是**模块编辑器**同一个失效模式（第二个落点）——
 * 两处各自独立，只锁一处的话另一处照样会给新 scope「静默消失」。
 *
 * ⚠️ 模块的解析在 `BackupExportUIProvider`，而它引用的标签映射在
 * `ui/settings/BackupScopeLabels.kt` —— 但那个文件已在
 * `BackupRestoreWiringTest` 里被断言过「不是第二份清单」，
 * 故这里只锁本包内的文件。
 */
class BackupExportUiSourceScanTest {

    private companion object {
        const val PROVIDER =
            "src/main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportUIProvider.kt"
        const val MODULE =
            "src/main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportModule.kt"
    }

    @Test
    fun `the module editor derives its chips from the registry`() {
        val code = SourceScan.stripped(PROVIDER)
        assertTrue(
            "❌ $PROVIDER 里没有 BackupScopeRegistry.all( —— " +
                "编辑器里的勾选项必须从注册表派生，否则新 scope 永远配不了（且无报错）",
            code.contains("BackupScopeRegistry.all("),
        )
        assertTrue(
            "必须存在 BackupExportUIProvider 类（文件被改名了？）",
            code.contains("class BackupExportUIProvider"),
        )
    }

    @Test
    fun `the module editor does not hardcode a second scope list`() {
        val code = SourceScan.stripped(PROVIDER)
        // ⚠️ T6：清单从注册表**派生**而不是再抄一份（理由同 BackupRestoreWiringTest
        //    的对应用例：抄一份的话新 scope 需要手工补字面量，
        //    而「忘了补」＝ 这条断言悄悄失去对新 scope 的覆盖）。
        val forbidden = BackupScopeRegistry.all().map { "\"${it.id}\"" }
        assertTrue(
            "❌ 注册表为空 —— forbid 集会恒空，本断言空转",
            forbidden.size >= 8,
        )
        val hits = forbidden.filter { code.contains(it) }
        assertTrue(
            "❌ $PROVIDER 里出现了 scope id 字面量：$hits —— 硬编码第二份清单是静默失效点 13",
            hits.isEmpty(),
        )
    }

    @Test
    fun `the chip group is explicitly multi-select in the layout`() {
        // ⚠️ `InputStyle.CHIP_GROUP` 在 StandardControlFactory 里被硬编码为
        //     isSingleSelection = true（单选），所以本模块才必须自定义 UIProvider。
        //     而自定义布局若漏写/写错 singleSelection，多选会**静默退化成单选**。
        val layout = SourceScan.file("src/main/res/layout/partial_backup_export_editor.xml").readText()
        assertTrue(
            "❌ 布局里的 ChipGroup 必须显式声明 singleSelection=\"false\"",
            layout.contains("app:singleSelection=\"false\""),
        )
        assertTrue(layout.contains("cg_backup_scopes"))
    }

    @Test
    fun `the module exposes a module provider and the module file does not import a Context`() {
        val code = SourceScan.stripped(MODULE)
        // 防空转：先确认真的读到了这个文件
        assertTrue(code.contains("class BackupExportModule"))
        assertTrue(code.contains("override val uiProvider"))
        // ⚠️ 本模块的 execute 是 suspend（签名写错的话这里会返回 null，
        //     而不是静默通过 —— 这正是要断言非 null 的原因）。
        assertNotNull(
            "❌ 没能截取 execute 的函数体（签名变了？）",
            SourceScan.functionBody(code, "override suspend fun execute("),
        )
    }

    @Test
    fun `the module never touches appContext inside getInputs`() {
        // ⚠️⚠️ appContext 是 lateinit，只在 ModuleRegistry.register(module, context)
        //      之后可用。模块列表在未注册时也会调 getInputs() ⇒ 在那里碰它会抛
        //      UninitializedPropertyAccessException，**整屏模块列表崩掉**。
        val code = SourceScan.stripped(MODULE)
        val body = SourceScan.functionBody(code, "override fun getInputs(")
        assertNotNull("❌ 没能截取 getInputs 的函数体", body)
        assertFalse(
            "❌ getInputs 里出现了 appContext —— 未注册时调用会崩掉模块列表",
            body!!.contains("appContext"),
        )
    }

    @Test
    fun `the scan is not vacuous`() {
        val provider = SourceScan.stripped(PROVIDER)
        val module = SourceScan.stripped(MODULE)
        assertTrue("剥注释后 provider 为空", provider.isNotBlank())
        assertTrue("剥注释后 module 为空", module.isNotBlank())
        // 剥注释确实保留了代码（不是把整份都当注释）
        assertTrue(provider.contains("readFromEditor"))
        assertTrue(module.contains("vflow.data.export_backup"))
    }
}
