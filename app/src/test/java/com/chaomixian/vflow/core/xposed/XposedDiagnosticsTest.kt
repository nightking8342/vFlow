package com.chaomixian.vflow.core.xposed

import com.chaomixian.vflow.R
import com.chaomixian.vflow.xposed.wire.CapabilityErrorAction
import com.chaomixian.vflow.xposed.wire.CapabilityErrorCode
import com.chaomixian.vflow.xposed.wire.userAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [XposedDiagnostics] 的单测。
 *
 * 设计文档：`docs/fork/xposed-architecture-v2.md` §6.4。
 *
 * ## 本测试钉的是「改错了不报错、只是把用户引向错误方向」的那一类
 *
 * 最典型的是 P4 踩过的坑：用户配置**完全正确**，文案却让他去检查 LSPosed 配置。
 * 故这里有多条**反向断言**（`assertNotEquals` 而不是 `assertEquals`）——
 * 它们锁的是「**不要**混到某一类里去」。
 */
class XposedDiagnosticsTest {

    // ── 构造工具 ──────────────────────────────────────────────

    private fun messageOf(code: CapabilityErrorCode) = XposedDiagnostics.messageFor(code)

    /** 与「通道断」那格的标题 / 正文做区分用的参照。 */
    private val channelDownTitle = messageOf(CapabilityErrorCode.CHANNEL_DOWN).titleRes
    private val channelDownBody = messageOf(CapabilityErrorCode.CHANNEL_DOWN).bodyRes

    // ── 1. 全量覆盖（需求点名）────────────────────────────────

    @Test
    fun `every code has a title and a body`() {
        CapabilityErrorCode.entries.forEach { code ->
            val message = messageOf(code)
            assertNotEquals("$code 缺标题", 0, message.titleRes)
            assertNotEquals("$code 缺正文", 0, message.bodyRes)
        }
    }

    @Test
    fun `the codes are still exactly five`() {
        // ⚠️ 这是**测试期**的锁：`bodyResOf` 的穷尽 `when` 是编译期锁，
        // 本断言让「悄悄加了第六个码」在第一道门禁就变红，强制实现者回来补文案。
        assertEquals(5, CapabilityErrorCode.entries.size)
    }

    @Test
    fun `every action has a title`() {
        // 标题按 userAction 派生 ⇒ 五个码、四个处置；每个处置都必须能取到标题
        val titles = CapabilityErrorCode.entries.map { messageOf(it).titleRes }
        assertEquals(4, titles.toSet().size)
        titles.forEach { assertNotEquals(0, it) }
    }

    // ── 2. ⚠️ 反向断言：不许把用户引向错误方向 ──────────────────

    @Test
    fun `payload_too_large is not routed to the lsposed advice`() {
        // 它是**实现缺陷**（正常路径下 hook 侧会主动截断），不是配置问题。
        // 把它归到「改配置」会让用户白折腾 —— 防的是「图省事抄成 channel_down」。
        val message = messageOf(CapabilityErrorCode.PAYLOAD_TOO_LARGE)
        assertNotEquals(
            "payload_too_large 的标题不能与 channel_down 相同（那会让用户去改 LSPosed 配置）",
            channelDownTitle,
            message.titleRes,
        )
        assertNotEquals(channelDownBody, message.bodyRes)
    }

    @Test
    fun `payload_too_large shares the reporting advice with timeout`() {
        // 两者 userAction() 同为 REPORT_PROBLEM ⇒ 标题**必须**相同。
        // 标题由 userAction 派生这一点正是本断言能成立的原因。
        assertEquals(CapabilityErrorAction.REPORT_PROBLEM, CapabilityErrorCode.PAYLOAD_TOO_LARGE.userAction())
        assertEquals(CapabilityErrorAction.REPORT_PROBLEM, CapabilityErrorCode.TIMEOUT.userAction())
        assertEquals(
            messageOf(CapabilityErrorCode.TIMEOUT).titleRes,
            messageOf(CapabilityErrorCode.PAYLOAD_TOO_LARGE).titleRes,
        )
    }

    @Test
    fun `capability_absent points at the app not at lsposed`() {
        // ⭐ 这正是 P4 踩过的坑：用户配置完全正确，却被引去检查 LSPosed。
        // capaiblity_absent 的正确指向是 **App 侧**（更新 / 重启 App）。
        val message = messageOf(CapabilityErrorCode.CAPABILITY_ABSENT)
        assertEquals(CapabilityErrorAction.UPGRADE_APP, CapabilityErrorCode.CAPABILITY_ABSENT.userAction())
        assertNotEquals(channelDownTitle, message.titleRes)
        assertNotEquals(channelDownBody, message.bodyRes)
    }

    @Test
    fun `only channel_down uses the lsposed title`() {
        val usingLsposedTitle = CapabilityErrorCode.entries
            .filter { messageOf(it).titleRes == channelDownTitle }
        assertEquals(listOf(CapabilityErrorCode.CHANNEL_DOWN), usingLsposedTitle)
    }

    // ── 3. 标题真的走 userAction 而不是手写表 ───────────────────

    @Test
    fun `every code maps through userAction rather than a hand written table`() {
        // 标题的分组粒度必须与 userAction() 的分组粒度**完全一致**：
        // 若有人手写了一张「看起来差不多」的标题表，两组数就会不等。
        val titleGroups = CapabilityErrorCode.entries.groupBy { messageOf(it).titleRes }.size
        val actionGroups = CapabilityErrorCode.entries.groupBy { it.userAction() }.size
        assertEquals(actionGroups, titleGroups)

        // 且同处置 ⇒ 同标题（逐对验证，不只看分组数）
        CapabilityErrorCode.entries.forEach { a ->
            CapabilityErrorCode.entries.forEach { b ->
                if (a.userAction() == b.userAction()) {
                    assertEquals(
                        "$a 与 $b 同为 ${a.userAction()}，标题必须相同",
                        messageOf(a).titleRes,
                        messageOf(b).titleRes,
                    )
                }
            }
        }
    }

    // ── 4. detail 只拼接、不判断 ──────────────────────────────

    @Test
    fun `detail is only appended never inspected`() {
        val message = CapabilityErrorMessage(
            titleRes = R.string.capability_error_title_report_problem,
            bodyRes = R.string.capability_error_body_timeout,
        )
        // ⚠️ 用不了真的 Context（纯 JVM），故这里只锁「映射层不接收 detail」这件事：
        // messageFor 的签名里根本没有 detail 参数 —— 任何想拿 detail 做分支的实现
        // 都必须先改签名，而那会立刻在这里的编译期被发现。
        val method = XposedDiagnostics::class.java
            .getMethod("messageFor", CapabilityErrorCode::class.java)
        assertEquals(
            "messageFor 只接受 CapabilityErrorCode —— detail 绝不能进入映射层",
            listOf(CapabilityErrorCode::class.java),
            method.parameterTypes.toList(),
        )

        // 拼接入口存在且**唯一**，其参数顺序是 (Context, 消息, detail)
        val format = XposedDiagnostics::class.java.getMethod(
            "formatBodyWithDetail",
            android.content.Context::class.java,
            CapabilityErrorMessage::class.java,
            String::class.java,
        )
        assertEquals(3, format.parameterTypes.size)
        assertEquals(String::class.java, format.parameterTypes[2])
        assertNotEquals(0, message.bodyRes)
    }

    @Test
    fun `production code never branches on detail`() {
        val files = productionFilesMentioningCapabilityErrorCode()
        val pattern = Regex("""\bdetail\s*(\.contains\(|\.startsWith\(|\.endsWith\(|==|!=|\bin\b)""")

        val violations = mutableListOf<String>()
        files.forEach { file ->
            codeLines(file).forEach { (lineNo, raw) ->
                if (pattern.containsMatchIn(raw)) {
                    violations += "${file.name}:$lineNo  ${raw.trim()}"
                }
            }
        }

        assertTrue(
            "❌ 有生产代码对 `detail` 做了判断。\n" +
                "`detail` 是自由文本、会被三语本地化 —— 拿它做分支等于埋一个「切语言就坏」的雷。\n" +
                "要用 `error.code`（CapabilityErrorCode）判断，`detail` 只用于显示。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `the detail scan is not vacuous`() {
        val files = productionFilesMentioningCapabilityErrorCode()
        assertTrue(
            "扫描范围只覆盖 ${files.size} 个文件 —— 疑似路径写错扫了个空集合（恒绿）",
            files.size >= 5,
        )
        val codeLineCount = files.sumOf { codeLines(it).size }
        assertTrue("剥掉注释后没有任何代码行可扫", codeLineCount > 0)
    }

    // ── 5. 通道提示复用 tapAction ─────────────────────────────

    @Test
    fun `channel notice follows tapAction not a new rule`() {
        val activeDisconnected = evaluate(channelConnected = false)
        assertEquals(XposedState.Framework.ACTIVE, activeDisconnected.framework)
        assertEquals(XposedState.Channel.DISCONNECTED, activeDisconnected.channel)
        assertEquals(
            "⭐ 框架正常、通道断 ⇒ 必须说「会自动重连」，绝不能引去改 LSPosed 配置",
            R.string.trigger_xposed_notice_reconnect,
            XposedDiagnostics.channelNoticeRes(activeDisconnected),
        )

        val unavailable = evaluate(frameworkConnected = false, everConnected = false, channelConnected = false)
        assertEquals(XposedState.Framework.UNAVAILABLE, unavailable.framework)
        assertEquals(
            R.string.trigger_xposed_notice_config,
            XposedDiagnostics.channelNoticeRes(unavailable),
        )

        val notMounted = evaluate(channelConnected = true, runningTargetNames = emptyList())
        assertEquals(XposedState.Channel.NOT_MOUNTED, notMounted.channel)
        assertEquals(
            R.string.trigger_xposed_notice_config,
            XposedDiagnostics.channelNoticeRes(notMounted),
        )

        val ready = evaluate(channelConnected = true, runningTargetNames = listOf("system"))
        assertEquals(XposedState.Channel.READY, ready.channel)
        // 通道正常时 TriggerService 不该显示这条（调用点会用 needsChannelNotice 短路），
        // 但即便被调到也**不能**返回某个"看起来像报错"的文案 —— 这里只锁它映射到 GUIDE 那一档
        assertEquals(
            R.string.trigger_xposed_notice_config,
            XposedDiagnostics.channelNoticeRes(ready),
        )
    }

    // ── 6. 三语文案齐全 ───────────────────────────────────────

    @Test
    fun `all three locales carry all eleven keys`() {
        val keys = listOf(
            "trigger_xposed_notice_reconnect",
            "trigger_xposed_notice_config",
            "capability_error_title_upgrade_app",
            "capability_error_title_report_problem",
            "capability_error_title_check_capability",
            "capability_error_title_check_lsposed",
            "capability_error_body_absent",
            "capability_error_body_timeout",
            "capability_error_body_handler_error",
            "capability_error_body_channel_down",
            "capability_error_body_payload_too_large",
        )

        val locales = listOf("values", "values-en", "values-ja")
        val missing = mutableListOf<String>()
        locales.forEach { locale ->
            val file = File(repoRoot(), "app/src/main/res/$locale/strings.xml")
            assertTrue("$locale/strings.xml 不存在：${file.absolutePath}", file.exists())
            val text = file.readText()
            keys.forEach { key ->
                if (!text.contains("""name="$key"""")) missing += "$locale 缺 $key"
            }
        }

        assertTrue(
            "❌ 三语文案不同步（漏一条只表现为「某语言下显示成另一语言」，不报错）：\n" +
                missing.joinToString("\n"),
            missing.isEmpty(),
        )
    }

    // ── 内部工具 ──────────────────────────────────────────────

    private fun evaluate(
        frameworkConnected: Boolean = true,
        everConnected: Boolean = true,
        runningTargetNames: List<String> = listOf("system"),
        channelConnected: Boolean = true,
    ) = XposedState.evaluate(
        XposedState.Input(
            frameworkConnected = frameworkConnected,
            everConnected = everConnected,
            scope = listOf("system"),
            runningTargetNames = runningTargetNames,
            channelConnected = channelConnected,
        )
    )

    /** 生产代码里**任何提到** `CapabilityErrorCode` 的文件 —— 自动扩展扫描范围，不误伤无关代码。 */
    private fun productionFilesMentioningCapabilityErrorCode(): List<File> {
        val root = File(repoRoot(), "app/src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains("CapabilityErrorCode") }
            .toList()
    }

    /**
     * 剥掉注释后的代码行。
     *
     * ⚠️⚠️ **不剥注释这条测试会恒红** —— `xposed/wire/CapabilityErrorCode.kt` 与
     * `xposed/wire/CapabilityInvocation.kt` 两处**注释正文里就写着**
     * `if (detail.contains("…"))`（它们是「禁止这么写」的说明文字）。
     * 实现照 `WireLayerPurityTest.codeLines()` 的既有形态。
     */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlockComment = false

        file.readLines().forEachIndexed { index, raw ->
            val trimmed = raw.trim()

            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false
                return@forEachIndexed
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) inBlockComment = true
                return@forEachIndexed
            }
            if (trimmed.startsWith("//")) return@forEachIndexed
            // KDoc 续行（行首独立成行的 `*`）—— 被注释掉的示例代码常写在这里
            if (trimmed.startsWith("*")) return@forEachIndexed

            out += (index + 1) to raw
        }
        return out
    }

    /** 从测试类所在位置向上找到仓库根（含 `settings.gradle.kts` 的那一级）。 */
    private fun repoRoot(): File {
        var dir: File? = File(System.getProperty("user.dir") ?: ".")
        while (dir != null) {
            if (File(dir, "settings.gradle.kts").exists()) return dir
            dir = dir.parentFile
        }
        error("找不到仓库根（settings.gradle.kts）")
    }
}
