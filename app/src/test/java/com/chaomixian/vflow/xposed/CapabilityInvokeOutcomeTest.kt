package com.chaomixian.vflow.xposed

import com.chaomixian.vflow.xposed.capability.CapabilityFallbackPlan
import com.chaomixian.vflow.xposed.capability.CapabilityFailure
import com.chaomixian.vflow.xposed.capability.CapabilityInvokeOutcome
import com.chaomixian.vflow.xposed.wire.CapabilityErrorAction
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.userAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * ③ 调用结果类型的测试（`xposed/capability/CapabilityInvokeOutcome.kt`）。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.2 / §6.3 / §6.4。
 *
 * 本文件锁的是**类型设计**本身的几条要求，而不是运行时行为 ——
 * 它们是「改错了不报错、只静默劣化」那一类：
 * ① 三态互斥（漏一个分支时会静默落到别的分支）；
 * ② `userAction` 必须**派生**自 `code`（独立字段会漂移）；
 * ③ `detail` **绝不参与判断** —— 这条只能靠**源码扫描**（运行时测不出来：
 *    一个 `detail.contains(...)` 在读得懂中文的机器上行为完全正确）。
 */
class CapabilityInvokeOutcomeTest {

    private fun outcomeFor(o: CapabilityInvokeOutcome): String = when (o) {
        is CapabilityInvokeOutcome.Success -> "success"
        is CapabilityInvokeOutcome.Failed -> "failed"
        is CapabilityInvokeOutcome.Degraded -> "degraded"
    }

    // ── ① 三态互斥且可穷尽 ────────────────────────────────────

    @Test
    fun `the three outcomes are mutually exclusive`() {
        // ⚠️ 之所以值得断言：`Success` 与 `Degraded` **都带 result**，
        // 有人会想把 Degraded 做成 Success 的**子类**（少一层 when）。
        // 那样做之后 `is Success -> …` 会把降级路径**静默当成成功**吞掉，
        // 而「降级必须留痕」（§6.2）当场失效。
        val success = CapabilityInvokeOutcome.Success("c", mapOf("k" to 1), 12L)
        val failed = CapabilityInvokeOutcome.Failed(
            "c", CapabilityFailure(CapabilityErrorCode.TIMEOUT, "x"),
        )
        val degraded = CapabilityInvokeOutcome.Degraded(
            "c", mapOf("k" to 1), CapabilityFailure(CapabilityErrorCode.CHANNEL_DOWN, "y"),
        )

        assertEquals("success", outcomeFor(success))
        assertEquals("failed", outcomeFor(failed))
        assertEquals("degraded", outcomeFor(degraded))

        // ⚠️ 反向断言：降级**不是**成功。这条断言就是「不许把 Degraded 变成 Success 子类」
        assertFalse("降级不是成功", degraded is CapabilityInvokeOutcome.Success)
        assertFalse("成功不是降级", success is CapabilityInvokeOutcome.Degraded)
    }

    @Test
    fun `all three outcomes carry the capability name`() {
        // 调用方不该为了知道「我在调什么」而先 when 一遍
        assertEquals("c", CapabilityInvokeOutcome.Success("c", emptyMap(), 1L).capability)
        assertEquals(
            "c",
            CapabilityInvokeOutcome.Failed(
                "c", CapabilityFailure(CapabilityErrorCode.TIMEOUT, "x"),
            ).capability,
        )
        assertEquals(
            "c",
            CapabilityInvokeOutcome.Degraded(
                "c", emptyMap(), CapabilityFailure(CapabilityErrorCode.TIMEOUT, "x"),
            ).capability,
        )
    }

    // ── ② userAction 必须派生自 code ──────────────────────────

    @Test
    fun `failure user action is derived from the code not stored separately`() {
        // ⚠️⚠️ 这条锁的是「两个地方表达同一件事必然漂移」：
        // `CapabilityFailure` 若把 userAction 做成**独立的构造参数**，
        // 就会出现 `CapabilityFailure(CHANNEL_DOWN, …, userAction = UPGRADE_APP)`
        // 这种自相矛盾的实例，而两个消费者各自读一个、结果不一致。
        CapabilityErrorCode.entries.forEach { code ->
            val f = CapabilityFailure(code, "任意 detail")
            assertEquals(
                "userAction 必须是 code.userAction() 的派生值（code=$code）",
                code.userAction(),
                f.userAction,
            )
        }
    }

    @Test
    fun `channel down points at lsposed and absent points at the app side`() {
        // §6.4 的核心区分：混在一起会让用户去白折腾错误的方向
        assertEquals(
            CapabilityErrorAction.CHECK_LSPOSED,
            CapabilityFailure(CapabilityErrorCode.CHANNEL_DOWN, "").userAction,
        )
        assertEquals(
            CapabilityErrorAction.UPGRADE_APP,
            CapabilityFailure(CapabilityErrorCode.CAPABILITY_ABSENT, "").userAction,
        )
    }

    @Test
    fun `changing only the detail never changes the action`() {
        // ⚠️ 这条是「detail 不参与判定」的**行为级**侧面：
        // 同一 code 配任意 detail，处置必须完全相同
        val a = CapabilityFailure(CapabilityErrorCode.TIMEOUT, "等了 5 秒没回")
        val b = CapabilityFailure(CapabilityErrorCode.TIMEOUT, "")
        val c = CapabilityFailure(CapabilityErrorCode.TIMEOUT, "TIMEOUT!!! 超时了")
        assertEquals(a.userAction, b.userAction)
        assertEquals(a.userAction, c.userAction)
    }

    // ── ③ detail 绝不参与判断（源码扫描）──────────────────────

    @Test
    fun `production code never branches on the detail text`() {
        // ⚠️⚠️ **必须源码扫描**，运行时测不出来。
        //
        // §6.4 约束 2 说 `detail` 是自由文本、将来会被三语本地化。
        // 一个 `if (failure.detail.contains("超时"))` 在中文环境下**行为完全正确** ——
        // 单测会全绿，直到某个用户切了语言。
        //
        // ⚠️ 扫描范围必须含**两个**目录：结果类型在 `xposed/capability/`，
        // 而分派逻辑在 `core/xposed/`。只扫一处会漏掉真正的分支点。
        val roots = listOf(
            "src/main/java/com/chaomixian/vflow/xposed/capability",
            "src/main/java/com/chaomixian/vflow/core/xposed",
        )
        val violations = mutableListOf<String>()

        for (root in roots) {
            val dir = File(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                .forEach { file ->
                    codeLines(file).forEach { (lineNo, raw) ->
                        val code = stripStringLiterals(raw).trim()
                        if (code.startsWith("//") || code.startsWith("*")) return@forEach
                        if (DETAIL_BRANCH.containsMatchIn(code)) {
                            violations += "${file.path}:$lineNo  $code"
                        }
                    }
                }
        }

        assertTrue(
            "❌ 出现了对 `detail` 的**文本判断** —— 违反 §6.4 约束 2。\n" +
                "`detail` 是自由文本、会被三语本地化；拿它做分支等于埋一个「切语言就坏」的雷。\n" +
                "**要分支请用 `code`（枚举），要看处置方向请用 `userAction`**。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `the detail branch scan is not vacuous`() {
        // ⚠️ 防「上面那条因为正则写错而恒真」——
        // 用一段**本该被拦下**的代码反问正则本身
        assertTrue(
            "正则必须能拦住 detail.contains(…)",
            DETAIL_BRANCH.containsMatchIn("""if (failure.detail.contains("x")) return"""),
        )
        assertTrue(
            "正则必须能拦住 detail == 字面量",
            DETAIL_BRANCH.containsMatchIn("""if (e.detail == "超时") return"""),
        )
        assertTrue(
            "正则必须能拦住 detail.equals(…)",
            DETAIL_BRANCH.containsMatchIn("""if (e.detail.equals("x")) return"""),
        )
        // 反向：正常的字段读取不该被拦
        assertFalse(
            "读 detail 打日志不该被拦（它是给人看的）",
            DETAIL_BRANCH.containsMatchIn("""DebugLogger.w(TAG, "失败：${'$'}{f.detail}")"""),
        )
        assertFalse(
            "构造 detail 不该被拦",
            DETAIL_BRANCH.containsMatchIn("""CapabilityFailure(code, "配对表已满")"""),
        )
    }

    // ── ④ 降级方案类型 ───────────────────────────────────────

    @Test
    fun `fallback plan distinguishes exclusive from replaceable`() {
        // ⚠️ §6.2：`fallback == null` ⇒ 独占型（无从降级）。
        // 这两种**必须是不同类型**且**不可互相 is 判断成功** ——
        // 否则「声明层面没有替代实现」会被写成运行时失败的一个分支（§7.4 反模式 9）
        val native: CapabilityFallbackPlan = CapabilityFallbackPlan.NativeCode
        val unavailable: CapabilityFallbackPlan = CapabilityFallbackPlan.Unavailable

        assertTrue(native is CapabilityFallbackPlan.NativeCode)
        assertTrue(unavailable is CapabilityFallbackPlan.Unavailable)
        assertFalse("独占型不是「有原生实现」", unavailable is CapabilityFallbackPlan.NativeCode)
        assertFalse("有原生实现不是「独占」", native is CapabilityFallbackPlan.Unavailable)

        // 且它与结果类型是**两套**东西（编译期就不同分支）
        assertNotNull(CapabilityFallbackPlan.Unavailable)
    }

    // ── ⑤ 分页字段的默认值（§3.6 契约 3/4）────────────────────

    @Test
    fun `pagination defaults mean the full result was taken`() {
        // ⚠️⚠️ `nextCursor == null && truncated == false` 是「全量已取完」的**唯一**表示。
        // 构造点的默认值必须落在这一侧，否则「没填」会被当成「截断了、但不知道下一页在哪」，
        // 调用方会退化成无限翻页。
        val s = CapabilityInvokeOutcome.Success("c", emptyMap(), 1L)
        assertEquals("默认没有下一页", null, s.nextCursor)
        assertFalse("默认没截断", s.truncated)
    }

    // ── 工具（与 WireLayerPurityTest 同一实现思路）─────────────

    private companion object {
        val DETAIL_BRANCH = Regex(
            """\bdetail\s*(\.\s*(contains|equals|startsWith|endsWith|matches|indexOf)\s*\(|[!=]=)""",
        )

        fun codeLines(file: File): List<Pair<Int, String>> {
            val out = mutableListOf<Pair<Int, String>>()
            var inBlock = false
            file.readLines().forEachIndexed { i, raw ->
                val t = raw.trim()
                if (inBlock) {
                    if (t.contains("*/")) inBlock = false
                    return@forEachIndexed
                }
                if (t.startsWith("/*")) {
                    if (!t.contains("*/")) inBlock = true
                    return@forEachIndexed
                }
                if (t.startsWith("//")) return@forEachIndexed
                out += (i + 1) to raw
            }
            return out
        }

        fun stripStringLiterals(line: String): String {
            val sb = StringBuilder()
            var inS = false
            var esc = false
            for (ch in line) {
                if (inS) {
                    if (esc) esc = false
                    else if (ch == '\\') esc = true
                    else if (ch == '"') {
                        inS = false
                        sb.append("__STR__")
                    }
                } else {
                    if (ch == '"') inS = true else sb.append(ch)
                }
            }
            return sb.toString()
        }
    }
}
