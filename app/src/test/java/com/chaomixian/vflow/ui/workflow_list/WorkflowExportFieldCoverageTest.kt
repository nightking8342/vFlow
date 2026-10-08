// 文件: test/java/com/chaomixian/vflow/ui/workflow_list/WorkflowExportFieldCoverageTest.kt
package com.chaomixian.vflow.ui.workflow_list

import com.chaomixian.vflow.core.backup.SourceScan
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * **源码扫描型**测试：锁住「单文件导出」**走的是共用 codec**，而不是又长出一份手写键表。
 *
 * ## 为什么必须有
 *
 * 这里原先是一份**人工维护的 20 键 map**，实测漏了 5 个字段
 * （`maxExecutionTime` / `reentryBehavior` / `silentExecution` / `logLevel` /
 * `functionSignature`）。它的遗漏**完全不报错**：导出的 JSON 少几个键，
 * 导入侧对每个键都有回落 ⇒ 用户拿到的是「设置被静默重置」，
 * 没有任何行为测试会因此变红。
 *
 * 现在字段集由 `WorkflowJsonCodec.toExportJson` **反射派生**，加字段自动跟随。
 * 但**「反射派生」这个性质本身没有类型保护** —— 有人把 `createWorkflowExportData`
 * 改回手写 map，编译照样过、测试照样绿（手写的那份只要覆盖当前字段就行），
 * 而缺陷会在**下一次加字段**时静默复发。
 *
 * ⇒ 这条扫源码锁住委托关系。
 *
 * ⚠️ 行为侧（往返一致、导出形状跟随模型）由 `WorkflowJsonCodecTest` 覆盖，
 * 两条互补：那边测「codec 对不对」，这边测「接线还在不在」。
 */
class WorkflowExportFieldCoverageTest {

    private companion object {
        const val FILE = "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"
        const val SIGNATURE = "private fun createWorkflowExportData("
    }

    @Test
    fun `the single file export delegates to the shared codec`() {
        val body = SourceScan.functionBody(SourceScan.stripped(FILE), SIGNATURE)
            ?: error("找不到 $SIGNATURE —— 函数被改名或移走了？测试需要同步更新")

        assertTrue(
            "单文件导出必须委托给 WorkflowJsonCodec.toExportJson（反射派生）。" +
                "改回手写键表会让「导出少字段」这个缺陷在下次加字段时静默复发 —— " +
                "而那是**不会让任何行为测试变红**的一类缺陷。\n实际函数体：$body",
            body.contains("WorkflowJsonCodec.toExportJson(")
        )
    }

    @Test
    fun `the export function does not hand write a key table again`() {
        // 反向锁：函数体里不得再出现 `"xxx" to workflow.` 这种键表写法。
        // ⚠️ 剥注释后再扫 —— 该函数的 KDoc 里就写着字段名，不剥会恒红。
        val body = SourceScan.functionBody(SourceScan.stripped(FILE), SIGNATURE)
            ?: error("找不到 $SIGNATURE")

        val handWritten = Regex("""^\s*"[A-Za-z_][A-Za-z0-9_]*"\s+to\s+workflow\.""", RegexOption.MULTILINE)
            .findAll(body)
            .map { it.value.trim() }
            .toList()

        assertTrue(
            "单文件导出又出现了手写键表：$handWritten —— " +
                "请改回 WorkflowJsonCodec.toExportJson（它反射派生、加字段不用改）",
            handWritten.isEmpty()
        )
    }

    @Test
    fun `the scan is not vacuous`() {
        // 防空转：签名失配时 functionBody 返回 null 会让上面两条直接 error，
        // 但若有人把签名改成一个仍然存在、却指向别处的串，断言可能全在空转。
        val body = SourceScan.functionBody(SourceScan.stripped(FILE), SIGNATURE)
            ?: error("找不到 $SIGNATURE")
        assertTrue("函数体过短，扫描可能失配：$body", body.length > 50)
        assertTrue("函数体里应有 gson 参数的使用", body.contains("gson"))
    }
}
