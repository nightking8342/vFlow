package com.chaomixian.vflow.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**接线测试：锁住 WebDAV 设置页入口与 `WebDavProbe` 的两条不变量。
 *
 * 形态照 `services/TriggerServiceXposedNoticeWiringTest.kt` 与
 * `ui/chat/AgentErrorDialogWiringTest.kt`（相对路径，Gradle test 工作目录 = `app/`）。
 *
 * ## ⚠️ 为什么必须是源码扫描
 *
 * 1. `SettingsScreen` / `SettingsRoute` 是 Android Compose / Activity，纯 JVM 起不来
 *    ⇒ 「有没有真的把这一行接上」只能在源码层锁。
 *    本仓库三次踩过「纯函数单测全绿但调用点缺失」（`CoreLauncher` 漏调
 *    `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor` 零调用点）。
 * 2. ⚠️⚠️ **订正（2026-10-03 实测）**：原先写「`followRedirects(false)` 无法用行为断言锁、
 *    只能靠本文件」—— **那是错的**。实测改回 `true` 时 `WebDavProbeTest` 有 **2 条**行为断言
 *    变红（`redirectLoopBeyondMaxHops_isNetworkError` / `redirectChainOfTwoHops_isFollowedToSuccess`），
 *    加上本文件的扫描共 3 条。⇒ 源码扫描是**第二道锁**，不是唯一一道。
 *    保留它的理由不是「行为测不到」，而是它更**直接**：锁的是「开关这一行在不在」本身。
 *
 * ## ⚠️⚠️ 剥注释是本测试的必要条件
 *
 * 本改动要在源码里加 `webDavTitle` 等字样，而**注释与文档里到处都是**。
 * 只做 `contains` 的话，「把 `listOf` 里那两项删掉」**照样绿**。
 * 故一律先剥块注释 / 行注释，并加**防空转**断言（剥注释后仍应有足够的代码行）。
 */
class WebDavSettingsEntryTest {

    private companion object {
        const val SETTINGS_SCREEN = "src/main/java/com/chaomixian/vflow/ui/settings/SettingsScreen.kt"
        const val SETTINGS_ROUTE = "src/main/java/com/chaomixian/vflow/ui/settings/SettingsRoute.kt"
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val PROBE = "src/main/java/com/chaomixian/vflow/core/webdav/WebDavProbe.kt"
        const val CLIENT = "src/main/java/com/chaomixian/vflow/core/webdav/WebDavClient.kt"
        const val HTTP_SUPPORT = "src/main/java/com/chaomixian/vflow/core/webdav/WebDavHttpSupport.kt"
        const val STRINGS_ZH = "src/main/res/values/strings.xml"
        const val STRINGS_EN = "src/main/res/values-en/strings.xml"
        const val STRINGS_JA = "src/main/res/values-ja/strings.xml"

        /** 本任务新增的全部字符串键（除 `settings_*` 两个外都是 `webdav_*`）。 */
        val REQUIRED_STRING_KEYS = listOf(
            "settings_webdav", "settings_webdav_desc",
            "webdav_config_title", "webdav_config_desc", "webdav_config_empty",
            "webdav_config_add", "webdav_config_edit", "webdav_config_delete",
            "webdav_config_delete_confirm_title", "webdav_config_delete_confirm_message",
            "webdav_config_name_label", "webdav_config_url_label",
            "webdav_config_username_label", "webdav_config_password_label",
            "webdav_config_allow_insecure_tls", "webdav_config_timeout_label",
            "webdav_config_remote_path_label",
            "webdav_config_invalid_name", "webdav_config_invalid_url",
            "webdav_config_duplicate_name", "webdav_config_test",
            "webdav_test_running", "webdav_test_success", "webdav_test_auth_failed",
            "webdav_test_not_found", "webdav_test_not_supported",
            "webdav_test_network_error",
            "webdav_test_key_unavailable", "webdav_test_invalid_config"
        )
    }

    private fun source(path: String): String {
        val file = File(path)
        assertTrue("找不到 $path（当前目录 ${File(".").absolutePath}）", file.exists())
        return file.readText()
    }

    /** 剥掉块注释与行注释，只留代码。 */
    private fun codeOnly(text: String): String {
        val withoutBlockComments = text.replace(Regex("""/\*[\s\S]*?\*/"""), " ")
        return withoutBlockComments.lineSequence()
            .joinToString("\n") { it.substringBefore("//") }
    }

    /**
     * 按**圆括号配对**截出从 [anchor] 起第一个 `(` 到其配对的 `)` 之间的区域（含括号本身）。
     *
     * ⚠️ 必须用括号配对而不是「找第一个 `{`」：`data class SettingsScreenActions(...)`
     * 与 `listOf(...)` 的参数区在**圆括号**内，而它们后面出现的第一个 `{`
     * 属于**下一个**构造（下一个类型声明 / `.any { ... }` lambda）——
     * 用大括号截会截到完全无关的区域，断言恒绿（实现期实际踩到）。
     */
    private fun parenBlock(code: String, anchor: String): String {
        val start = code.indexOf(anchor)
        assertTrue("找不到锚点：$anchor", start >= 0)
        val open = code.indexOf('(', start)
        assertTrue("锚点 $anchor 后面没有圆括号", open >= 0)

        var depth = 0
        var index = open
        var inString = false
        var inChar = false
        while (index < code.length) {
            val c = code[index]
            when {
                inString -> {
                    if (c == '\\') index++ else if (c == '"') inString = false
                }
                inChar -> {
                    if (c == '\\') index++ else if (c == '\'') inChar = false
                }
                c == '"' -> inString = true
                c == '\'' -> inChar = true
                c == '(' -> depth++
                c == ')' -> {
                    depth--
                    if (depth == 0) return code.substring(open, index + 1)
                }
            }
            index++
        }
        error("锚点 $anchor 的圆括号不配对")
    }

    // ── 防空转 ────────────────────────────────────────────────

    @Test
    fun theScanIsNotVacuous() {
        // ⚠️ 剥注释若剥过头（比如把整个文件都吃掉），后面的断言会「因为没有而通过」——
        // 这条断言保证剥注释后两个锚点都还在、且内容足够长。
        val screen = codeOnly(source(SETTINGS_SCREEN))

        assertTrue("剥注释后应仍能看到 SettingsScreenActions", screen.contains("data class SettingsScreenActions"))
        assertTrue("剥注释后应仍能看到 showGeneralSection", screen.contains("val showGeneralSection"))
        assertTrue("剥注释后文件应仍有大量代码行，实际 ${screen.lines().size} 行", screen.lines().size > 800)
    }

    // ── 1. SettingsScreenActions 有字段 ──────────────────────────

    @Test
    fun settingsScreenActionsHasWebDavField() {
        val screen = codeOnly(source(SETTINGS_SCREEN))
        val actionsBlock = parenBlock(screen, "data class SettingsScreenActions(")

        assertTrue(
            "SettingsScreenActions 必须有 onOpenWebDavConfig 字段（删掉它 ⇒ SettingsRoute 编译不过也应在此变红）",
            actionsBlock.contains("onOpenWebDavConfig")
        )
    }

    // ── 2. SettingsRoute 真的接了线 ──────────────────────────────

    @Test
    fun settingsRouteWiresTheEntry() {
        val route = codeOnly(source(SETTINGS_ROUTE))

        val occurrences = Regex("""onOpenWebDavConfig\s*=\s*\{""").findAll(route).count()
        assertEquals("SettingsRoute 里 onOpenWebDavConfig = { 应恰好出现 1 处", 1, occurrences)

        // 定位那一段，确认它真的启动了 WebDavConfigActivity。
        val start = route.indexOf("onOpenWebDavConfig = {")
        val snippet = route.substring(start, (start + 260).coerceAtMost(route.length))
        assertTrue(
            "🔴 接线块必须启动 WebDavConfigActivity（改成空 lambda ⇒ 本断言变红）。实际：$snippet",
            snippet.contains("WebDavConfigActivity::class.java")
        )
    }

    // ── 3. ⚠️ 搜索清单不变量（本文件存在的首要理由）───────────────

    @Test
    fun generalSectionSearchListIncludesWebDavTitles() {
        val screen = codeOnly(source(SETTINGS_SCREEN))
        val generalBlock = parenBlock(screen, "val showGeneralSection = listOf(")

        assertTrue(
            "🔴 webDavTitle 必须在 showGeneralSection 的 listOf 内 —— " +
                "漏加会让用户搜「WebDAV」时整个「通用」分组消失（SettingsScreen.kt:259-262 记过同款缺陷）",
            generalBlock.contains("webDavTitle")
        )
        assertTrue(
            "🔴 webDavSubtitle 同上",
            generalBlock.contains("webDavSubtitle")
        )
    }

    // ── 5. 既有缺陷修复（步骤 5b 的机器化锁）─────────────────────

    @Test
    fun generalSectionSearchListAlsoIncludesGlobalVariableTitles() {
        val screen = codeOnly(source(SETTINGS_SCREEN))
        val generalBlock = parenBlock(screen, "val showGeneralSection = listOf(")

        // ⚠️ 这两项此前漏在搜索清单外（变量在 :182-183 取值、:449-450 渲染），
        // 是**既有缺陷**。撤回修复 ⇒ 本断言变红。
        assertTrue(
            "🔴 globalVariablesTitle 必须在 showGeneralSection 的 listOf 内（既有缺陷修复）",
            generalBlock.contains("globalVariablesTitle")
        )
        assertTrue(
            "🔴 globalVariablesSubtitle 同上",
            generalBlock.contains("globalVariablesSubtitle")
        )
    }

    // ── 4. 真的渲染了 NativeEntryRow ─────────────────────────────

    @Test
    fun webDavEntryIsRenderedAsNativeRow() {
        val screen = codeOnly(source(SETTINGS_SCREEN))

        // 找到含 webDavTitle 的那个 NativeEntryRow 调用，并确认它同时用了两个变量。
        var found = false
        var index = screen.indexOf("NativeEntryRow(")
        while (index >= 0) {
            val rowBlock = parenBlock(screen.substring(index), "NativeEntryRow(")
            if (rowBlock.contains("webDavTitle") && rowBlock.contains("webDavSubtitle")) {
                assertTrue("该行必须接 onClick = actions.onOpenWebDavConfig", rowBlock.contains("onOpenWebDavConfig"))
                found = true
                break
            }
            index = screen.indexOf("NativeEntryRow(", index + 1)
        }

        assertTrue("🔴 必须存在一个同时用 webDavTitle/webDavSubtitle 的 NativeEntryRow（删掉渲染 ⇒ 变红）", found)
    }

    // ── 6. Manifest 声明 ────────────────────────────────────────

    @Test
    fun manifestDeclaresWebDavActivityAsNotExported() {
        val manifest = source(MANIFEST)

        assertTrue("Manifest 应声明 .ui.settings.WebDavConfigActivity", manifest.contains(".ui.settings.WebDavConfigActivity"))

        val start = manifest.indexOf(".ui.settings.WebDavConfigActivity")
        val snippet = manifest.substring(start, (start + 260).coerceAtMost(manifest.length))
        assertTrue(
            "🔴 该 Activity 必须 exported=\"false\"（否则任何 App 都能拉起它）。实际：$snippet",
            snippet.contains("""android:exported="false"""")
        )
    }

    // ── 7. 三语字符串齐全（逐份分别断言，防「只补了中文」）────────

    @Test
    fun allStringKeysExistInChinese() = assertAllKeys(STRINGS_ZH)

    @Test
    fun allStringKeysExistInEnglish() = assertAllKeys(STRINGS_EN)

    @Test
    fun allStringKeysExistInJapanese() = assertAllKeys(STRINGS_JA)

    private fun assertAllKeys(path: String) {
        val content = source(path)
        val missing = REQUIRED_STRING_KEYS.filterNot { content.contains("""name="$it"""") }
        assertTrue("🔴 $path 缺少以下键（三语必须同步）：$missing", missing.isEmpty())
    }

    // ── 9. ⚠️ followRedirects(false)：无法用行为断言反证的项 ──────

    @Test
    fun probeDisablesAutomaticRedirectFollowing() {
        val probe = codeOnly(source(PROBE))

        // ⚠️ 订正（2026-10-03 实测）：这条**并非**「不可替代」—— 改成 true 时 WebDavProbeTest
        // 也有 2 条行为断言变红。它是**第二道锁**，价值在于直接锁「开关这一行在不在」。
        assertTrue(
            "🔴 WebDavProbe 必须 followRedirects(false) —— 我们需要看见重定向链才能给出诊断，" +
                "且要在跳数超限时给出明确结论（改成 true ⇒ 本断言与 2 条行为断言同时变红）",
            probe.contains("followRedirects(false)")
        )
        assertTrue(
            "同源：SSL 重定向也要自己处理",
            probe.contains("followSslRedirects(false)")
        )
    }

    // ── 10. 重定向上限的取值约束 ────────────────────────────────

    @Test
    fun maxRedirectsConstantIsWithinSaneRange() {
        val probe = codeOnly(source(PROBE))

        // ⚠️ 从**源码**解析常量值，而不是把 5 写死到断言里 ——
        // 写死的话「把源码改成 50」断言照样绿（那正是本测试要抓的）。
        val match = Regex("""MAX_REDIRECTS\s*=\s*(\d+)""").find(probe)
        assertTrue("找不到 MAX_REDIRECTS 的赋值", match != null)

        val value = match!!.groupValues[1].toInt()
        assertTrue(
            "🔴 MAX_REDIRECTS 必须 ≥ 2（单跳重定向是常态，设成 1 会把正常配置判成重定向过多），" +
                "且 ≤ 10（设太大 ⇒ 一个填错的地址会挂住工作流线程很久）。实际：$value",
            value in 2..10
        )
    }

    // ── 11. 重定向与 TLS 装配的**收敛**（2026-10-03 集成期去重）────────────

    /**
     * ⚠️ 本组存在理由：收敛前 `REDIRECT_CODES` 与 `trustAllTrustManager()` 在
     * `WebDavProbe` 与 `WebDavClient` **各写一份**（后者两份逐字相同）。
     * 重复的代价不是「多几行」而是**改一处忘另一处** —— 表现是「测试连接能过、
     * 模块执行报错」（或反过来），而两边各自都看着对。
     */
    @Test
    fun bothClientsShareOneRedirectCodeSet() {
        val probe = codeOnly(source(PROBE))
        val client = codeOnly(source(CLIENT))
        val support = codeOnly(source(HTTP_SUPPORT))

        assertTrue(
            "🔴 REDIRECT_CODES 的字面量集合必须只在 WebDavHttpSupport 里出现一次",
            support.contains("setOf(301, 302, 303, 307, 308)")
        )
        assertTrue(
            "🔴 WebDavProbe 必须引用共享件，不得自己重写集合",
            probe.contains("WebDavHttpSupport.REDIRECT_CODES")
        )
        assertTrue(
            "🔴 WebDavClient 同上",
            client.contains("WebDavHttpSupport.REDIRECT_CODES")
        )
        assertTrue(
            "🔴 WebDavProbe 不得残留自己的 setOf(301, ...)",
            !probe.contains("setOf(301, 302, 303, 307, 308)")
        )
        assertTrue(
            "🔴 WebDavClient 不得残留自己的 setOf(301, ...)",
            !client.contains("setOf(301, 302, 303, 307, 308)")
        )
    }

    @Test
    fun bothClientsShareOneTrustAllManager() {
        val probe = codeOnly(source(PROBE))
        val client = codeOnly(source(CLIENT))
        val support = codeOnly(source(HTTP_SUPPORT))

        assertTrue(
            "🔴 trustAllTrustManager 的定义必须只在 WebDavHttpSupport 里",
            support.contains("fun trustAllTrustManager()")
        )
        assertTrue("🔴 WebDavProbe 不得再定义一份", !probe.contains("fun trustAllTrustManager()"))
        assertTrue("🔴 WebDavClient 不得再定义一份", !client.contains("fun trustAllTrustManager()"))
        assertTrue(
            "🔴 两侧都必须经 WebDavHttpSupport.applyInsecureTlsIfNeeded 装配",
            probe.contains("WebDavHttpSupport.applyInsecureTlsIfNeeded") &&
                client.contains("WebDavHttpSupport.applyInsecureTlsIfNeeded")
        )
    }

    /**
     * ⚠️⚠️ **安全不变量**：`allowInsecureTls` 只放宽**证书信任**，**绝不放宽 hostname 校验**。
     *
     * 这条必须是源码扫描 —— 加 `hostnameVerifier { _, _ -> true }` 之后
     * **没有任何行为测试会变红**（没有测试能覆盖「中间人」），是典型的静默劣化。
     */
    @Test
    fun insecureTlsNeverDisablesHostnameVerification() {
        val support = codeOnly(source(HTTP_SUPPORT))
        val probe = codeOnly(source(PROBE))
        val client = codeOnly(source(CLIENT))

        listOf("WebDavHttpSupport" to support, "WebDavProbe" to probe, "WebDavClient" to client)
            .forEach { (name, body) ->
                assertTrue(
                    "🔴 $name 不得出现 hostnameVerifier —— 「允许自签名」≠「允许任意中间人」",
                    !body.contains("hostnameVerifier")
                )
            }
        // 防空转：确认这三个文件确实都读到了（剥注释后仍有内容）
        listOf(support, probe, client).forEach {
            assertTrue("🔴 剥注释后源码不应为空（防空转）", it.length > 500)
        }
    }

    /** ⚠️ 收敛**不得**把 `followRedirects(false)` 藏进 helper —— 它是意图声明，必须在装配点可见。 */
    @Test
    fun redirectSwitchesStayVisibleAtEachAssemblySite() {
        val probe = codeOnly(source(PROBE))
        val client = codeOnly(source(CLIENT))

        listOf("WebDavProbe" to probe, "WebDavClient" to client).forEach { (name, body) ->
            assertTrue("🔴 $name 必须自己写 followRedirects(false)", body.contains("followRedirects(false)"))
            assertTrue("🔴 $name 必须自己写 followSslRedirects(false)", body.contains("followSslRedirects(false)"))
        }
    }
}
