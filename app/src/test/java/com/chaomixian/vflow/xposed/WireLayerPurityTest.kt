package com.chaomixian.vflow.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住「hook 层与 App 共享的 wire 层不得引用 App 侧依赖」。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.1；
 * 由本仓库在 `CoreLauncher` 上踩过的同类坑驱动（13 个纯函数单测全绿，
 * 但**调用点缺失** —— 纯函数测试测不出「引用面污染」）。
 *
 * ## 为什么这是**做**出来的共享而非拷贝
 *
 * `logcat` 那条链路是 app / Core **两份拷贝**，`FORK.md` 明确记着代价：
 * 「任何语义改动必须同时改两处……不一致的表现是『调试工具里看着能匹配的日志，
 * 触发器匹配不到』」。本通道同 APK 同 dex，**不该重复那个代价**。
 *
 * ## 但共享带来一个新风险，本测试就是钉它的
 *
 * wire 层跑在**两个进程**里（App + 注入 system_server 的 hook 层）。
 * 同 dex 里 App 类都在，但**只有被引用才会在 system_server 里被解析加载** ——
 * 一旦 wire 层 import 了 App 侧的重类，那个类的静态初始化就会在 system_server 里跑，
 * **把崩溃半径从「那个 App」扩大到整机**。
 *
 * ## 这个测试的边界（写清楚，免得被当成万能的）
 *
 * 它检查的是**源码里的 import 语句**。它能拦住「有人顺手加了个 DebugLogger」，
 * 但拦不住「R8 把某个 App 侧常量内联进来」。后者由
 * `assembleRelease` 后的 dex 反查兜底（见计划的 P2 验证步骤）。
 */
class WireLayerPurityTest {

    private companion object {
        /**
         * wire 层允许的 import 前缀 —— **最严档**。
         *
         * 它比 `xposed/` 其余部分更严，理由不是「它跑在 system_server」（那个整包都是），
         * 而是**它要对 App 侧保持纯 JVM 可测**：`EventEnvelopeCodecTest` 等
         * 在**没有 Android 运行时的普通 JVM** 里跑。
         * 所以这里连 `android.os.Bundle` 都不许（其余部分可以用）。
         *
         * - `org.json` —— 唯一允许的第三方库（Core 侧也用它，且它在 android.jar 里就有）
         * - `java.` / `kotlin.` —— 语言层
         * - `com.chaomixian.vflow.xposed.` —— hook 层内部
         */
        val ALLOWED_PREFIXES = listOf(
            "org.json.",
            "java.",
            "kotlin.",
            "com.chaomixian.vflow.xposed.",
        )

        /**
         * 显式禁止的项 —— 即使用户没写 import、只写了全限定名也要拦住。
         *
         * 单独列出来是为了让**报错信息直指原因**，而不是只说「不在白名单里」。
         */
        val FORBIDDEN_SUBSTRINGS = listOf(
            "android.util.Log",
            "android.content",
            "android.os.",
            "com.chaomixian.vflow.core.",
            "com.chaomixian.vflow.services.",
            "com.chaomixian.vflow.ui.",
            "com.google.gson",
            "DebugLogger",
        )

        /**
         * **整个 `xposed/` 包**都跑在 system_server 里（不只 `wire/`）。
         *
         * 允许的 Android 引用只有**纯数据类**（无 Context 初始化）：
         * `android.os.Build` / `android.os.Bundle` / `android.os.IBinder` /
         * `android.content.ComponentName` / `android.content.Context`（transport 要 bindService）/
         * `android.util.Log`（[HookLog] 的出口）/ `android.os.RemoteException`。
         *
         * 这些都在下面逐个列出 —— **加新的 android import 必须显式登记**，
         * 免得有人顺手引入 `android.widget.*` 之类把 App UI 拖进 system_server。
         */
        val ANDROID_ALLOWLIST = setOf(
            "android.os.Build",
            "android.os.Bundle",
            "android.os.IBinder",
            "android.os.RemoteException",
            "android.os.UserManager",
            "android.content.ComponentName",
            "android.content.Context",
            "android.content.Intent",
            "android.content.ServiceConnection",
            "android.util.Log",
            "android.os.Binder",   // IHookHost.Stub 的基类（AIDL 生成物需要）
        )

        /** `xposed/` 包下**禁止**引用的 App 侧包（会扩大崩溃半径）。 */
        val FORBIDDEN_APP_PACKAGES = listOf(
            "com.chaomixian.vflow.core.",
            "com.chaomixian.vflow.services.",
            "com.chaomixian.vflow.ui.",
            "com.chaomixian.vflow.permissions.",
            "com.chaomixian.vflow.api.",
            "com.chaomixian.vflow.ocr.",
            "com.chaomixian.vflow.speech.",
            "com.chaomixian.vflow.integration.",
            "com.google.gson",
            "com.chaomixian.vflow.core.logging.DebugLogger",
        )
    }

    /** `xposed/` 包下全部 .kt（含 `wire/` 与 `sources/`）。 */
    private fun allXposedFiles(): List<File> {
        val root = File("src/main/java/com/chaomixian/vflow/xposed")
        assertTrue("xposed 目录不存在：${root.absolutePath}", root.isDirectory)
        return root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()
    }

    /** 要被检查的文件：hook 层与 App 共享的全部 wire 层类。 */
    private fun wireFiles(): List<File> {
        val dir = File("src/main/java/com/chaomixian/vflow/xposed/wire")
        assertTrue("wire 目录不存在：${dir.absolutePath}", dir.isDirectory)
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }?.toList().orEmpty()
        assertTrue("wire 目录里没有 .kt 文件", files.isNotEmpty())
        return files
    }

    /**
     * 剥掉注释后的**代码行**，保留原始行号（`Pair<行号, 文本>`）。
     *
     * ⚠️⚠️ **必须剥注释**，第一版没剥，结果是**假阳性**：
     * 本类自己的 KDoc 里为了说明「哪些不允许」而**列举**了那些名字
     * （`android.util.Log` / `DebugLogger` …），扫描把它们当成真引用了。
     *
     * ⚠️ 但剥注释有个**危险的方向**：若剥得不干净，真引用可能被当成注释而漏掉。
     * 所以这里只处理 `//` 与 `/* */` 两种明确形态，且对
     * **行首独立成行的 `*`（KDoc 续行）** 一并剥掉 —— 与真实代码天然不冲突。
     */
    private fun codeLines(file: File): List<Pair<Int, String>> {
        val out = mutableListOf<Pair<Int, String>>()
        var inBlockComment = false

        file.readLines().forEachIndexed { index, raw ->
            val trimmed = raw.trim()

            // 块注释的结束
            if (inBlockComment) {
                if (trimmed.contains("*/")) inBlockComment = false
                return@forEachIndexed
            }
            // 块注释的开始（只认行首，避免把 `/*` 在字符串里的情况算进来）
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) inBlockComment = true
                return@forEachIndexed
            }
            // 行注释
            if (trimmed.startsWith("//")) return@forEachIndexed

            out += (index + 1) to raw
        }
        return out
    }

    @Test
    fun `wire layer only imports whitelisted packages`() {
        val violations = mutableListOf<String>()

        for (file in wireFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                val trimmed = raw.trim()
                if (!trimmed.startsWith("import ")) continue

                val fqcn = trimmed.removePrefix("import ").removeSuffix(";").trim()
                val allowed = ALLOWED_PREFIXES.any { fqcn.startsWith(it) }
                if (!allowed) {
                    violations += "${file.name}:$lineNo  $fqcn"
                }
            }
        }

        assertTrue(
            "❌ wire 层（hook 层与 App 共享）引入了非白名单依赖。\n" +
                "这会让该类在 system_server 里被加载 ⇒ 崩溃半径从「那个 App」扩大到整机。\n" +
                "若确实需要，请把它挪出 wire 包（只留 App 侧），或改写成不依赖它的形式。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `wire layer contains no forbidden references even fully qualified`() {
        // import 白名单拦不住「全限定名内联写法」（如 `android.util.Log.e(...)` 不 import）。
        // 本测试补上这一路
        val violations = mutableListOf<String>()

        for (file in wireFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                FORBIDDEN_SUBSTRINGS.forEach { forbidden ->
                    if (raw.contains(forbidden)) {
                        violations += "${file.name}:$lineNo  含 `$forbidden`  →  ${raw.trim()}"
                    }
                }
            }
        }

        assertTrue(
            "❌ wire 层出现禁止引用。\n" +
                "wire 层运行在 system_server 里，不能依赖任何需要 App Context 的东西。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `wire layer has no Android framework dependency at all`() {
        // 更严格的兜底：任何 `android.` 开头都不允许。
        // （上面那条列的是具体项，这条防漏网）
        val violations = mutableListOf<String>()

        for (file in wireFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                if (raw.trim().startsWith("import android.")) {
                    violations += "${file.name}:$lineNo  ${raw.trim()}"
                }
            }
        }

        assertTrue(
            "❌ wire 层不得 import 任何 android.* —— 它会跑在 system_server 里。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `comment stripping does not swallow real code`() {
        // ⚠️ 防「测试本身失效」：若 codeLines 剥过头（比如把整份文件都当注释），
        // 上面三条会**全部空转通过**。这里用真实文件做反向验证：
        // 必须还能看到已知存在的 import 行
        val envelope = wireFiles().first { it.name == "EventEnvelope.kt" }
        val code = codeLines(envelope)
        assertTrue(
            "注释剥离后看不到 EventEnvelope.kt 的 import —— 剥离逻辑可能剥过头了",
            code.any { it.second.trim().startsWith("import org.json") },
        )
        assertTrue(
            "注释剥离后看不到 encode 函数定义",
            code.any { it.second.contains("fun encode(") },
        )
    }

    // ═══ 以下三条覆盖**整个 xposed/ 包**（不只 wire/）═══════
    //
    // 理由：**整包都跑在 system_server 里**（不只 wire/）。wire/ 的额外约束
    // （不许碰 android.*）是「要对 App 侧保持纯 JVM 可测」，上面已单独覆盖。

    @Test
    fun `xposed package never references app-side packages`() {
        // ⚠️⚠️ 这是整套测试里最要紧的一条。
        // xposed/ 下的类会被注入 system_server 执行；它们**引用到的类也会在
        // system_server 里被解析加载**。引用 App 侧的重类（DebugLogger / UI /
        // ocr / speech …）= 把那些类的静态初始化拖进 system_server
        // ⇒ **崩溃半径从「那个 App」扩大到整机**。
        //
        // ⚠️ **必须跳过字符串字面量**（第一版没跳，撞了假阳性）：
        // `VFlowHookEntry` 里写的是
        //   private const val HOST_SERVICE = "com.chaomixian.vflow.services.HookChannelService"
        // 那是**给 AMS 查组件用的字符串**，不加载任何类 ——
        // 而且硬编码它**恰恰是为了**不引用 App 常量类（引用才会加载）。
        // 用 `loadClass` 那种「看字符串就当引用」的判据会把它误判成违规。
        val violations = mutableListOf<String>()

        for (file in allXposedFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                val code = stripStringLiterals(raw)
                FORBIDDEN_APP_PACKAGES.forEach { pkg ->
                    // ⚠️ 只在「import 行」或「像类型引用的位置」上判：
                    // import 之外还允许 `com.chaomixian.vflow.core.XXX` 这种
                    // 全限定名内联写法（Kotlin 里少见但合法），故一并检查
                    if (code.contains(pkg)) {
                        violations += "${file.name}:$lineNo  含 `$pkg`  →  ${raw.trim()}"
                    }
                }
            }
        }

        assertTrue(
            "❌ xposed/ 包引用了 App 侧代码。\n" +
                "它跑在 system_server 里，引用 App 侧重类会把崩溃半径扩大到整机。\n" +
                "需要日志请用 HookLog（android.util.Log），不要用 DebugLogger。\n" +
                "若你确实只是写了个**字符串常量**（如组件名），它应该已经被本测试放行 ——\n" +
                "出现这条说明该引用真的在代码里。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    /**
     * 把字符串字面量替换掉（保留长度无关的占位），避免「组件名字符串」
     * 被当成「类引用」。
     *
     * ⚠️ 只处理**双引号**字面量。Kotlin 的三引号字符串极少用于此类常量，
     * 且若真有，误报也只会是「多报而不漏报」—— 那是更安全的一侧。
     */
    private fun stripStringLiterals(line: String): String {
        val sb = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in line) {
            if (inString) {
                if (escaped) {
                    escaped = false
                } else if (ch == '\\') {
                    escaped = true
                } else if (ch == '"') {
                    inString = false
                    sb.append("__STR__")
                }
            } else {
                if (ch == '"') {
                    inString = true
                } else {
                    sb.append(ch)
                }
            }
        }
        return sb.toString()
    }

    @Test
    fun `xposed package only uses allowlisted android imports`() {
        // 允许 android 引用，但**必须显式登记** —— 防「顺手引入 android.widget.*」
        // 这类把 App 侧 UI 依赖拖进 system_server 的事
        val violations = mutableListOf<String>()

        for (file in allXposedFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                val trimmed = raw.trim()
                if (!trimmed.startsWith("import android.")) continue
                val fqcn = trimmed.removePrefix("import ").removeSuffix(";").trim()
                if (fqcn !in ANDROID_ALLOWLIST) {
                    violations += "${file.name}:$lineNo  $fqcn"
                }
            }
        }

        assertTrue(
            "❌ xposed/ 包引入了未登记的 android 类。\n" +
                "这些类会在 system_server 里被加载。若确实必要，请加进\n" +
                "ANDROID_ALLOWLIST **并说明理由**（要求：纯数据类 / 无 App Context 初始化）。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `xposed package files are present so the checks above are not vacuous`() {
        // 防「目录被改名/清空 ⇒ 上面全部空转通过」。
        // 本仓库踩过「测试不经过调用点所以反证不变红」的坑，故加这道保险
        val files = allXposedFiles()
        assertTrue("xposed/ 下文件数异常（${files.size} 个），检查是否被搬走", files.size >= 8)
        // ⚠️ 下面列的 ③ 契约层文件在 `capability/` 子目录里（`walkTopDown` 会递归到），
        // 所以文件数检查也要跟着抬 —— 否则「搬走一批」不会被这里发现
        for (expected in listOf(
            "VFlowHookEntry.kt",
            "HookRuntime.kt",
            "BinderTransport.kt",
            "HookTargets.kt",
            "HookSource.kt",
            "EventEnvelope.kt",
            "EventQueue.kt",
            "HookConditionWire.kt",
            "ActivityPayload.kt",
            "ActivityChangedSource.kt",
            // ③ 能力调用的契约层（与上面同样是「跑在 system_server 里」的文件，
            // 必须一并受本文件的引用面扫描管辖）
            "CapabilityInvocation.kt",
            "CapabilityErrorCode.kt",
            "CapabilityManifest.kt",
            "ResultBudget.kt",
            "Capability.kt",
            "CapabilityRegistry.kt",
            "CapabilityNames.kt",
            "CapabilityPresence.kt",
            // ③ 的 hook 侧**执行运行时**（`capabilities/` 复数，与上面 `capability/`
            // 单数只差一个 s）。同样是「跑在 system_server 里」的文件，
            // 必须一并受本文件的引用面扫描管辖 —— 它起线程池、跑 handler，
            // 引用面污染在这里的后果比别处更重。
            "CapabilityHandler.kt",
            "InvokePolicy.kt",
            "HookCapabilityRegistry.kt",
            "DiagnosticCapabilityHandler.kt",
            "HookCapabilityRuntime.kt",
        )) {
            assertTrue(
                "xposed/ 下缺少 $expected —— 是否改名/搬走了？",
                files.any { it.name == expected },
            )
        }
    }

    @Test
    fun `wire layer files are non-empty so the wire checks are not vacuous`() {
        val files = wireFiles()
        assertTrue("wire 层至少应有 EventEnvelope 与 EventQueue 两个文件", files.size >= 2)
        assertEquals(true, files.any { it.name == "EventEnvelope.kt" })
        assertEquals(true, files.any { it.name == "EventQueue.kt" })
    }
}
