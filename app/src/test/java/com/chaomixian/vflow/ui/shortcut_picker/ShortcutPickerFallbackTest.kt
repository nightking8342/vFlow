package com.chaomixian.vflow.ui.shortcut_picker

import org.junit.Assert.assertEquals

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 选择器**换数据源**这一层的回归测试。
 *
 * 设计文档：`xposed-capability-invocation-design.md` §6.3（③ 是数据源不是目的）；
 * 方案 `plan-4.md` §6 S11/S12。
 *
 * ## ⚠️⚠️ 为什么这里用**源码扫描**而不是构造字面量断言
 *
 * 本文件第一版写的是「`mapOf("source" to "dumpsys")` 断言它等于 dumpsys」——
 * 那种测试**永远不会红**：它断的是我自己刚写在测试里的字面量，
 * **根本没经过被改的源码**。我用反证验过：删掉源码里的 `"source" to "dumpsys"`，
 * 测试**照样全绿**。
 *
 * ⇒ 这正是本仓库反复记的那条教训（`test-the-call-chain-not-the-function`）：
 * **反证不变红 = 测试没经过调用点**。
 * 所以改成扫源码，让「关键结构真的在代码里」成为可证伪的断言。
 */
class ShortcutPickerFallbackTest {

    private val support = File("src/main/java/com/chaomixian/vflow/ui/shortcut_picker/ShortcutPickerSupport.kt")
    private val sheet = File("src/main/java/com/chaomixian/vflow/ui/shortcut_picker/UnifiedShortcutPickerSheet.kt")

    /** 剥掉注释行（KDoc 里会提到这些关键字，不剥会假阳性）。 */
    private fun codeOf(f: File): String =
        f.readLines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }
            .joinToString("\n")

    // ── 降级路径可区分 ────────────────────────────────────────────

    @Test
    fun `degraded path marks its source in the real code`() {
        val code = codeOf(support)
        assertTrue(
            "❌ 降级路径必须给结果打 `source` 标记 —— 否则「走了哪条路」只能靠字段缺失去猜，" +
                "而字段缺失既可能是降级、也可能是那个 App 真的没有该字段。\n" +
                "（这条断言扫**源码**而不是构造字面量：构造字面量的写法永远不会红。）",
            code.contains("\"source\" to \"dumpsys\""),
        )
    }

    @Test
    fun `degraded path does not fake a structured intent`() {
        val code = codeOf(support)
        // ⚠️ 降级路径只给得出 `launch_command`（文本），给不出结构化 Intent。
        // 若把 parse 出来的半成品塞进 `intent_data`，它会**看起来像无损结果**，
        // 调用方据此判断「dat 完整」就会得到错的结论。
        assertTrue("降级路径应给 launch_command", code.contains("\"launch_command\" to"))
        val degradedBlock = code.substringAfter("queryViaDumpsys").substringBefore("private fun requireContextOrNull")
        assertTrue(
            "❌ 降级路径**不得**填 `intent_data`（那会让它伪装成无损结果）",
            !degradedBlock.contains("\"intent_data\""),
        )
    }

    // ── 留痕（S12 的核心）──────────────────────────────────────────

    @Test
    fun `degraded notice exists and explains cause plus data impact`() {
        val code = codeOf(support)
        // ⚠️ 文案声明是**两行**（`private const val DEGRADED_NOTICE =` 换行才是字符串）——
        // 第一版只取「含 = 的那一行」，抓到的是 `notice = DEGRADED_NOTICE,` 那个**调用点**，
        // 于是断言恒假。改为：从声明处起取一段，再在里面找字符串字面量。
        val declAt = code.indexOf("DEGRADED_NOTICE =")
        assertTrue(
            "❌ 找不到降级留痕文案 —— 不留痕的话，用户看到「昨天能用今天不能用」会去查错的方向",
            declAt >= 0,
        )
        val notice = code.substring(declAt, (declAt + 400).coerceAtMost(code.length))
        assertTrue(
            "文案要说清『为什么』（提到 Xposed 或通道）",
            notice.contains("Xposed") || notice.contains("通道"),
        )
        assertTrue(
            "文案要说清『对数据意味着什么』（提到有损 / 缺失 / 类型）",
            notice.contains("有损") || notice.contains("缺失") || notice.contains("类型"),
        )
    }

    @Test
    fun `sheet consumes the fallback-aware loader and surfaces the notice`() {
        val code = codeOf(sheet)
        assertTrue(
            "❌ 加载链路必须走 loadShortcutsWithFallback（否则 ③ 根本没被用上）",
            code.contains("loadShortcutsWithFallback"),
        )
        assertTrue(
            "❌ 界面上必须把 notice 显示出来 —— 拿到了却不用等于没留痕",
            code.contains("notice"),
        )
    }

    @Test
    fun `sheet does not hard block on missing shell when the channel may be available`() {
        val code = codeOf(sheet)
        // ⚠️ 原实现是「没 Shizuku 就直接出提示、**连加载都不试**」——
        // 装了 Xposed 的设备上这会**白白挡住**可用路径（③ 与 shell 权限无关）。
        //
        // ⚠️ 判据**不能依赖缩进/换行**（第一版写成 `"...\")\n            return"`，
        //    结果空转：注入真实的早退后它**仍然绿** —— 因为缩进对不上）。
        //    改为**归一化空白**后再找「requires_shell ... return」这个组合。
        val beforeLoad = code.substringBefore("loadShortcutsWithFallback")
            .replace(Regex("\\s+"), " ")          // 归一化所有空白（含换行与缩进）
        val hit = Regex("text_shortcut_picker_requires_shell[^;]{0,80}?return")
            .containsMatchIn(beforeLoad)
        assertTrue(
            "❌ 不该在尝试加载之前就因「没有 shell」而 return —— " +
                "③ 通道与 shell 权限无关，装了 Xposed 的设备会被白白挡住。\n" +
                "（本条的判据刻意不依赖缩进：第一版依赖缩进，注入 bug 后仍绿 = 空转。）",
            !hit,
        )
    }

    // ── LoadResult 的形状（纯数据，可以构造）──────────────────────

    @Test
    fun `load result defaults to not degraded and no notice`() {
        val r = ShortcutPickerSupport.LoadResult(items = emptyList(), degraded = false)
        assertEquals(null, r.notice)
        assertTrue(!r.degraded)
    }

    @Test
    fun `load result can express degraded with a notice`() {
        val r = ShortcutPickerSupport.LoadResult(items = emptyList(), degraded = true, notice = "x")
        assertTrue(r.degraded)
        assertEquals("x", r.notice)
    }
}
