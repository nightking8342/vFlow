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

    // ══ ⭐ 行为级：无损路径的 extras **类型**必须变成正确的 flag ══════
    //
    // ⚠️⚠️ 这一组是本文件**唯一能证明「米家问题被修好」**的测试。
    //
    // 上面那些源码扫描型断言只能证明「某段代码存在」，**证明不了行为对**——
    // 我第一版就是：三个分支都调 dumpsys、`outcome.result` 零消费，
    // 而全部源码扫描断言**照样全绿**（独立评审抓出来的）。
    //
    // ⇒ 这里直接喂结构化的 item，断言产出的命令。

    @Test
    fun `lossless extras use the flag matching their declared type`() {
        // ⚠️ 米家那条：`extra_scene_account=1462285899` 是 **String**，
        // 而 dumpsys 路径按 `length < 10` 猜会得到 `--el`（Long）
        // ⇒ 米家 `getString()` 读到 null ⇒ 报「无账号权限」。
        // 无损路径拿到真实类型 ⇒ 必须是 `--es`。
        val cmd = ShortcutPickerSupport.buildLaunchCommandFromIntent(
            mapOf(
                "intent_action" to "com.xiaomi.smarthome.scene.smarthomelauncher",
                "intent_component" to "com.xiaomi.smarthome/.scene.activity.SmartHomeLauncherActivity",
                "extras" to listOf(
                    mapOf("key" to "extra_scene_account", "type" to "String", "value" to "1462285899"),
                ),
            ),
        )
        requireNotNull(cmd)
        assertTrue(
            "❌ String 类型的 extras 必须用 --es —— 这正是米家「无账号权限」那个故障的修法。\n" +
                "（dumpsys 路径会因 `length < 10` 猜成 --el，本测试锁的就是它不被重演。）\n实际：$cmd",
            cmd.contains("--es 'extra_scene_account' '1462285899'"),
        )
        assertTrue("不该出现 --el", !cmd.contains("--el"))
    }

    @Test
    fun `lossless extras pick flags per type not by guessing`() {
        fun cmd(type: String, value: String) = ShortcutPickerSupport.buildLaunchCommandFromIntent(
            mapOf(
                "intent_action" to "a",
                "extras" to listOf(mapOf("key" to "k", "type" to type, "value" to value)),
            ),
        ).orEmpty()

        // ⚠️ 逐类型验证 —— 尤其是 `Double` → `--ed`（`am` 的 double 不是 `--ef`，
        //    这个很容易写错，而写错的表现是「参数类型不对」而非「崩溃」）
        assertTrue("Integer → --ei", cmd("Integer", "5").contains("--ei 'k' '5'"))
        assertTrue("Long → --el", cmd("Long", "5").contains("--el 'k' '5'"))
        assertTrue("Float → --ef", cmd("Float", "1.5").contains("--ef 'k' '1.5'"))
        assertTrue("Double → --ed", cmd("Double", "1.5").contains("--ed 'k' '1.5'"))
        assertTrue("Boolean → --ez", cmd("Boolean", "true").contains("--ez 'k' 'true'"))
        assertTrue("String[] → --esa", cmd("String[]", "a, b").contains("--esa 'k' 'a, b'"))
        // 未知类型走兜底（应与 dumpsys 路径同款）
        assertTrue("未知类型兜底 --es", cmd("Weird", "x").contains("--es 'k' 'x'"))
    }

    @Test
    fun `lossless command carries dat and component`() {
        val cmd = ShortcutPickerSupport.buildLaunchCommandFromIntent(
            mapOf(
                "intent_action" to "android.intent.action.VIEW",
                "intent_data" to "imeituan://www.meituan.com/scan",
                "intent_component" to "com.sankuai.meituan/.MainActivity",
                "intent_flags" to 0x10000000,
            ),
        )
        requireNotNull(cmd)
        assertTrue("dat 是 dumpsys 路径丢的那 18.1% —— 必须带上:\n$cmd", cmd.contains("-d 'imeituan://"))
        assertTrue("component 必须带上", cmd.contains("-n 'com.sankuai.meituan/.MainActivity'"))
        assertTrue("flags 必须是十六进制", cmd.contains("-f 0x10000000"))
    }

    @Test
    fun `lossless command returns null when no locating info at all`() {
        // ⚠️ 与 dumpsys 路径同款语义：一个定位信息都没有 ⇒ 这条命令启动不了任何东西。
        // 返回 null 让调用方**跳过**它，而不是产出一条 `am start` 空命令（那会启动到首页）。
        assertEquals(
            null,
            ShortcutPickerSupport.buildLaunchCommandFromIntent(
                mapOf("shortcut_label" to "只有标签", "extras" to emptyList<Any>()),
            ),
        )
    }

    @Test
    fun `success branch actually consumes the capability result`() {
        // ⚠️⚠️ 这条锁的是**接线本身**（源码级）：`Success` 分支必须消费 `outcome.result`，
        // 而不是像第一版那样**三个分支都调 `loadShortcuts`**（dumpsys）——
        // 那种写法下 ③ 的结果被整个丢掉、选择器根本没换源，而**所有形状类断言照样全绿**。
        val code = codeOf(support)
        // ⚠️ 用**括号配平**切出 `if (outcome is …Success) { … }` 这个块 ——
        // 不能靠「找下一个分支关键字」或固定长度窗口：
        //   · 固定 300 字符会越界抓进降级分支的 `loadShortcuts(context)`（我第一版就是这么误判的）
        //   · 找 `Degraded` 也失效 —— 外层那个 `when` 已改成 `if + 无条件 return`，
        //     Success 之后**没有** Degraded 标记了
        val successAt = code.indexOf("CapabilityInvokeOutcome.Success)")
        assertTrue("应能找到 Success 分支的判断", successAt >= 0)
        val open = code.indexOf('{', successAt)
        assertTrue("Success 分支应当是带块的形式", open >= 0)
        var depth = 0
        var end = open
        for (i in open until code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        val successBlock = code.substring(open, end + 1)
        assertTrue("应能切出非空的 Success 块（否则本条在空转）", successBlock.length > 20)
        assertTrue(
            "❌ Success 分支**必须**消费 `outcome.result` —— 否则 ③ 白调了，选择器仍走 dumpsys。\n" +
                "实际片段：$successBlock",
            successBlock.contains("outcome.result"),
        )
        assertTrue(
            "❌ Success 分支不得回落到 `loadShortcuts`（那是有损的 dumpsys 路径）\n" +
                "实际片段：$successBlock",
            !successBlock.contains("loadShortcuts(context)"),
        )
    }
}
