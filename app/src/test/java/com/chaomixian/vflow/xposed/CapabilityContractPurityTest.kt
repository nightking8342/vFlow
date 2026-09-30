package com.chaomixian.vflow.xposed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住 ③ 契约层的三处**结构性**要求。
 *
 * 这些要求**测不出来**（不是运行时行为），只能靠扫源码：
 *
 * | # | 要求 | 违反的后果 |
 * |---|---|---|
 * | 1 | `capability/` 不得引用 App 侧包 | 崩溃半径从「那个 App」扩大到**整机**（§5.6） |
 * | 2 | `CapabilityPresence` **不得**进 `XposedState` | 新造了第三组状态位，违反 §6.1「复用现有两套」 |
 * | 3 | AIDL 形状（`invoke`/`resolve` 是 oneway、`capabilities` 不是；`ping` 仍是 `int`） | 传输形态错 ⇒ binder 线程被占 ⇒ §3.4 失效 |
 *
 * ⚠️ 为什么必须有这一层：本仓库反复记录「纯函数单测全绿、调用点缺失」
 * （`CoreDexFingerprint` 教训）。结构性要求必须由源码扫描来钉。
 */
class CapabilityContractPurityTest {

    private companion object {
        /** `capability/` 包下**禁止**引用的 App 侧包（会扩大崩溃半径）。 */
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
            "DebugLogger",
        )

        /**
         * capability 包允许的 import 前缀 —— 与 `xposed/` 其余部分同规。
         *
         * ⚠️ **刻意不含 `kotlinx.coroutines.`**：`WireLayerPurityTest` 对 `xposed/`
         * 全包的 import 白名单也没有它。本包必须保持「纯声明 + 注册表」，
         * 任何需要协程/Flow 的持有者都放到 `core/xposed/`（那里能引用）。
         * 这条白名单**窄**是有意的 —— 宽了就等于把「会不会被拖进 system_server」
         * 这件事交给自觉。
         */
        val ALLOWED_PREFIXES = listOf(
            "org.json.",
            "java.",
            "kotlin.",
            "com.chaomixian.vflow.xposed.",
        )
    }

    private fun capabilityFiles(): List<File> {
        val dir = File("src/main/java/com/chaomixian/vflow/xposed/capability")
        assertTrue("capability 包不存在：${dir.absolutePath}", dir.isDirectory)
        val files = dir.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()
        assertTrue("capability 包里没有 .kt 文件", files.isNotEmpty())
        return files
    }

    /** 剥掉注释后的代码行（保留行号）。与 `WireLayerPurityTest` 同一实现思路。 */
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
            out += (index + 1) to raw
        }
        return out
    }

    /** 把字符串字面量替换掉，避免「组件名/类名字符串」被当成类引用。 */
    private fun stripStringLiterals(line: String): String {
        val sb = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in line) {
            if (inString) {
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') {
                    inString = false
                    sb.append("__STR__")
                }
            } else {
                if (ch == '"') inString = true else sb.append(ch)
            }
        }
        return sb.toString()
    }

    // ── 要求 1：引用面 ────────────────────────────────────────

    @Test
    fun `capability package only imports whitelisted packages`() {
        val violations = mutableListOf<String>()
        for (file in capabilityFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                val trimmed = raw.trim()
                if (!trimmed.startsWith("import ")) continue
                val fqcn = trimmed.removePrefix("import ").removeSuffix(";").trim()
                if (ALLOWED_PREFIXES.none { fqcn.startsWith(it) }) {
                    violations += "${file.name}:$lineNo  $fqcn"
                }
            }
        }
        assertTrue(
            "❌ capability 包引入了非白名单依赖。\n" +
                "本包在 xposed/ 下，会被 hook 层（system_server）加载 ——\n" +
                "引入 App 侧重类会把它们的静态初始化拖进 system_server，\n" +
                "**崩溃半径从「那个 App」扩大到整机**（§5.6）。\n" +
                "若确实需要，请把注册动作挪到 xposed/ 之外的文件里\n" +
                "（见 CapabilityRegistry 类注释的分层说明）。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `capability package never references app-side packages`() {
        // ⚠️⚠️ 本文件最要紧的一条。
        val violations = mutableListOf<String>()
        for (file in capabilityFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                val code = stripStringLiterals(raw)
                FORBIDDEN_APP_PACKAGES.forEach { pkg ->
                    if (code.contains(pkg)) {
                        violations += "${file.name}:$lineNo  含 `$pkg`  →  ${raw.trim()}"
                    }
                }
            }
        }
        assertTrue(
            "❌ capability 包引用了 App 侧代码 —— 崩溃半径会变成整机。\n" +
                "特别是 fallback：它的**实现**必然在 App 侧（如 dumpsys 路径），\n" +
                "但**注册动作**必须在 xposed/ 之外的文件里做。\n" +
                "违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `capability package has no android framework dependency`() {
        // capability 包连 android.* 都不需要（它是纯声明 + 注册表）
        val violations = mutableListOf<String>()
        for (file in capabilityFiles()) {
            for ((lineNo, raw) in codeLines(file)) {
                if (raw.trim().startsWith("import android.")) {
                    violations += "${file.name}:$lineNo  ${raw.trim()}"
                }
            }
        }
        assertTrue(
            "❌ capability 包不得 import 任何 android.*。\n违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    @Test
    fun `comment stripping does not swallow real code`() {
        // 防「测试本身失效」：若剥过头，上面几条会**全部空转通过**
        val registry = capabilityFiles().first { it.name == "CapabilityRegistry.kt" }
        val code = codeLines(registry)
        assertTrue(
            "注释剥离后看不到 import —— 剥离逻辑可能剥过头了",
            code.any { it.second.trim().startsWith("import ") },
        )
        assertTrue(
            "注释剥离后看不到 fun register —— 剥离逻辑可能剥过头了",
            code.any { it.second.contains("fun register(") },
        )
    }

    // ── 要求 2：CapabilityPresence 的位置（§6.1 / §6.3）────────

    @Test
    fun `CapabilityPresence lives in the capability package`() {
        // ⚠️ §6.3 的硬要求：它**是**一个 enum、**有**状态迁移，
        // 所以放在 core/xposed/ 的状态模块里**一定会被当成第三组状态位** ——
        // 而 §6.1 明说「复用现有两套，不新造」
        val file = File("src/main/java/com/chaomixian/vflow/xposed/capability/CapabilityPresence.kt")
        assertTrue("CapabilityPresence.kt 必须在 capability 包里", file.isFile)
        assertTrue(
            "文件里应有 enum class CapabilityPresence",
            file.readText().contains("enum class CapabilityPresence"),
        )
    }

    @Test
    fun `XposedState does not know about CapabilityPresence`() {
        // ⚠️⚠️ 反向：状态模块里**不得**出现它。
        // 出现就等于新造了第三组状态位，违反 §6.1「复用现有两套，不新造」。
        //
        // ⚠️ 注意判据要**精确**：`Channel` 里本来就有 `READY`（合法），
        // 所以不能笼统地扫「READY」这个词 —— 只扫 `CapabilityPresence` 这个名字。
        val xposedState = File("src/main/java/com/chaomixian/vflow/core/xposed/XposedState.kt")
        assertTrue("XposedState.kt 应当存在", xposedState.isFile)
        val text = xposedState.readText()

        assertFalse(
            "❌ XposedState 里出现了 CapabilityPresence —— 这会新造第三组状态位（§6.1 禁止）",
            text.contains("CapabilityPresence"),
        )
        assertFalse(
            "❌ XposedState 里出现了 ABSENT —— CapabilityPresence 的取值不该出现在状态位里",
            text.contains("ABSENT"),
        )
    }

    @Test
    fun `XposedState still declares its original two state enums`() {
        // ⚠️ 「不新造第三组状态位」的**精确**判据：数 `enum class` 的个数。
        //
        // ⚠️ 基线是 **3**（不是 2）：`Framework` / `Channel` / `TapAction`
        // （`TapAction` 是点击行为分类，不是状态位，但它也在本文件里）。
        // 数错基线会让这条断言**恒假** —— 我在写这版时先数错了，靠跑测试才发现。
        //
        // ⚠️ 这个数字**刻意写死**：它的价值就是「变了就必须有人来看一眼」。
        val text = File("src/main/java/com/chaomixian/vflow/core/xposed/XposedState.kt").readText()
        val enumCount = Regex("""enum class \w+""").findAll(text).count()
        assertEquals(
            "❌ XposedState 里的 enum 数变了（基线是 Framework + Channel + TapAction）。" +
                "若是把 ③ 的 CapabilityPresence 放进来了 —— §6.1/§6.3 明确禁止；" +
                "若是别的正当原因，请更新本条基线并在注释里说明。",
            3,
            enumCount,
        )
    }

    @Test
    fun `XposedState state enum values are unchanged`() {
        // 取值集合的锁（防「往 Framework/Channel 里塞新值」）
        val text = File("src/main/java/com/chaomixian/vflow/core/xposed/XposedState.kt").readText()
        assertTrue("Framework 应有 UNAVAILABLE", text.contains("UNAVAILABLE"))
        assertTrue("Framework 应有 DEGRADED", text.contains("DEGRADED"))
        assertTrue("Framework 应有 ACTIVE", text.contains("ACTIVE"))
        assertTrue("Channel 应有 DISCONNECTED", text.contains("DISCONNECTED"))
        assertTrue("Channel 应有 NOT_MOUNTED", text.contains("NOT_MOUNTED"))
        assertTrue("Channel 应有 READY（本就是合法值）", text.contains("READY"))
    }

    // ── 要求 3：AIDL 形状（§3.1 / §3.4）───────────────────────

    @Test
    fun `IHookCallback keeps int ping and adds capabilities plus oneway invoke`() {
        val aidl = File("src/main/aidl/com/chaomixian/vflow/xposed/IHookCallback.aidl")
        assertTrue("IHookCallback.aidl 应当存在", aidl.isFile)
        val text = aidl.readText()

        // ⚠️ ping 的 int 签名**刻意保持不动**（B 组定案）——
        // 改成字符串会让一次简单探活变成解析 JSON，且把「版本号」与「能力清单」
        // 两件独立的事耦合在同一处、只能一起演进
        assertTrue(
            "❌ ping() 的 int 签名被改了 —— B 组定案是「不改 ping()，新增 capabilities()」",
            Regex("""\bint\s+ping\s*\(\s*\)\s*;""").containsMatchIn(text),
        )
        assertTrue(
            "应有 String capabilities()",
            Regex("""\bString\s+capabilities\s*\(\s*\)\s*;""").containsMatchIn(text),
        )
        // ⚠️ invoke 必须是 oneway void —— 带返回值会让 App 侧 binder 线程
        // 阻塞到 handler 跑完，直接违反 §3.4「不占 binder 线程」
        assertTrue(
            "❌ invoke 必须是 oneway void —— 见 §3.4（带返回值会占住 binder 线程）",
            Regex("""\boneway\s+void\s+invoke\s*\(\s*String\s+\w+\s*\)\s*;""").containsMatchIn(text),
        )
        assertFalse(
            "❌ 出现了非 oneway 的 String invoke(...) —— 与 §3.4 冲突",
            Regex("""^\s*String\s+invoke\s*\(""", RegexOption.MULTILINE).containsMatchIn(text),
        )
        assertTrue(
            "pushConditions 应当保持不动",
            text.contains("pushConditions"),
        )
    }

    @Test
    fun `IHookHost keeps registerCallback and report and adds oneway resolve`() {
        val aidl = File("src/main/aidl/com/chaomixian/vflow/xposed/IHookHost.aidl")
        assertTrue("IHookHost.aidl 应当存在", aidl.isFile)
        val text = aidl.readText()

        assertTrue(text.contains("registerCallback"))
        assertTrue(
            "report 必须保持 oneway",
            Regex("""oneway\s+void\s+report\s*\(""").containsMatchIn(text),
        )
        // ⚠️ resolve 也必须 oneway —— hook 层可能在 system_server 的任何线程上调它，
        // 绝不能阻塞等待 App 处理完（与 report 同理）
        assertTrue(
            "❌ resolve 必须是 oneway —— hook 层可能在任意线程上调它",
            Regex("""oneway\s+void\s+resolve\s*\(\s*String\s+\w+\s*\)\s*;""").containsMatchIn(text),
        )
    }

    @Test
    fun `AIDL changes are additive only`() {
        // ⚠️ AIDL 变更**无版本协商机制**，两端必须同时升级 ——
        // 所以任何**删除或改签名**都是破坏性的（旧端会 MethodNotFound）。
        // 这条断言锁住「只加不改」。
        val callback = File("src/main/aidl/com/chaomixian/vflow/xposed/IHookCallback.aidl").readText()
        val host = File("src/main/aidl/com/chaomixian/vflow/xposed/IHookHost.aidl").readText()

        // 三个旧方法一个都不能少
        assertTrue(callback.contains("pushConditions"))
        assertTrue(callback.contains("ping"))
        assertTrue(host.contains("registerCallback"))
        assertTrue(host.contains("report"))
    }

    // ── 要求 4：鉴权必须走恒定时间比较（§3.3）────────────────
    //
    // ⚠️⚠️ 这一条是我**做反证时才发现必须补**的：
    // 把 `onResolve` 里的 `constantTimeEquals(a, b)` 换成 `a != b`，
    // **没有任何行为测试变红** —— 因为恒定时间是**结构属性**，
    // 不是可观测行为（两者对「token 对不对」的判断结果**完全一样**，
    // 差别只在响应耗时，而单测测不出耗时）。
    //
    // 这正是本仓库那条教训的又一实例：**测试不经过能区分两种写法的判据，
    // 反证就不会变红**。⇒ 只能靠源码扫描把「必须调用 constantTimeEquals」钉住。

    @Test
    fun `onResolve authenticates with constant time comparison`() {
        val controller = File("src/main/java/com/chaomixian/vflow/core/xposed/HookChannelController.kt")
        assertTrue("HookChannelController.kt 应当存在", controller.isFile)
        val text = controller.readText()

        // ⚠️ 把「onResolve 这个方法体」单独切出来判 ——
        // 整个文件里 onReport 已经用了 constantTimeEquals，
        // 只扫全文的话「onResolve 漏用」会被 onReport 的那次调用掩盖。
        val body = methodBody(text, "fun onResolve(")
        assertTrue("应能切出 onResolve 的方法体（否则本断言在空转）", body.isNotBlank())

        assertTrue(
            "❌ onResolve 必须用 constantTimeEquals 比较 token —— \n" +
                "逐字符 `==` / `!=` 会在第一个不同的字符处返回，**响应时间就泄漏了前缀信息**，\n" +
                "对固定长度 token 而言这是可被逐字节爆破的。\n" +
                "（§3.3 定案：响应侧照搬 onReport 的三段，其中第②段就是恒定时间比较）",
            body.contains("constantTimeEquals("),
        )
        assertFalse(
            "❌ onResolve 里出现了直接用 != / == 比较 token 的写法",
            Regex("""(response\.token|it\.token)\s*[!=]=\s*token""").containsMatchIn(body),
        )
    }

    /**
     * 从源码里切出一个方法的方法体（括号配平）。
     *
     * ⚠️ 只做「够用」的实现：从 `signature` 之后第一个 `{` 开始，配平到对应 `}`。
     * 不比编译器精确，但对「这个方法里有没有出现某个调用」这个用途足够。
     */
    private fun methodBody(text: String, signature: String): String {
        val start = text.indexOf(signature)
        if (start < 0) return ""
        val open = text.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return ""
    }

    // ── 要求 5：capability 包的「调用点」（防反模式 6）────────
    //
    // ## ⚠️⚠️ 本条已于 2026-09-30 **翻面**（③ 的 hook 侧执行运行时接入）
    //
    // 它原来是一条**反向**断言：「capability 包**尚无**生产调用点」—— 用来标记
    // 契约层未被接入这个缺口。当时实测发现（检查 release dex）：
    // **`capability/` 包下的四个类在 release APK 的 dex 里一个都不存在** ——
    // R8 把它们当死代码剥掉了，因为**没有任何生产代码引用它们**。
    //
    // 同一个 APK 里的对照（证明那不是 R8 配置问题）：
    //
    // | 类 | 有没有被引用 | 在 dex 里吗 |
    // |---|---|---|
    // | `ResultBudget` | ✅ 被 `ActivityPayload` 引用 | ✅ 在 |
    // | `CapabilityInvocationCodec` | ✅ 被 `BinderTransport` / `HookChannelController` 引用 | ✅ 在 |
    // | `Capability` / `CapabilityRegistry` / `Names` / `Presence` | ❌ **零引用** | ❌ **被剥** |
    //
    // 那条断言自己的注释就写着：「接入后本条会变红 —— 请把它改成**正面断言**
    //（断言引用确实存在），而不是直接删掉」。
    //
    // ⇒ 现在按那个要求翻面。**为什么不能删**：本仓库反复记录的反模式 6 是
    // 「**写了调用点注释、但没有调用点**」（`CoreDexFingerprint` 那次：13 个纯函数
    // 单测全绿、集成点缺失，表现是「永远不提示重启，用户静默跑旧 Core 代码」）——
    // 删掉这条断言等于把那道防线也删了。
    //
    // ⚠️ 翻面后的强度**不比原来弱**：原来断言「零引用」（一个否命题，容易被
    // 无关引用满足），现在断言**具体哪几个符号有引用**（正命题，缺失就红）。

    @Test
    fun `capability package has production call sites`() {
        /**
         * 符号 → 它在生产代码里的**应有消费者**（缺一个就红）。
         *
         * ⚠️ **只列已经接上的**。`CapabilityPresence` **刻意不在表里**：
         * 它的消费者是「连接期能力交换」的持有者，尚未实现 —— 把它列进来
         * 会让本条恒红，而**一条恒红的断言会被下一个实现者直接删掉**，
         * 那才是真的失去防线。
         */
        val requiredSymbols = mapOf(
            // App 侧调用入口查表用（CapabilityInvoker）
            "CapabilityRegistry" to "调用入口的查表与注册（CapabilityFallbacks）",
            // hook 侧注册表与诊断 handler 引用名字常量（同一个 dex，复用无两份拷贝代价）
            "CapabilityNames" to "hook 侧注册表与诊断 handler 的名字来源",
        )

        val hits = mutableMapOf<String, MutableList<String>>()
        val productionRoots = listOf(
            "src/main/java/com/chaomixian/vflow/xposed",
            "src/main/java/com/chaomixian/vflow/core",
            "src/main/java/com/chaomixian/vflow/services",
            "src/main/java/com/chaomixian/vflow/ui",
        )

        for (root in productionRoots) {
            val dir = File(root)
            if (!dir.isDirectory) continue
            dir.walkTopDown()
                .filter { it.isFile && it.name.endsWith(".kt") }
                // 契约层自己的文件不算「调用点」。
                //
                // ⚠️ 路径分隔符必须归一化：Windows 上 `File.path` 用的是 `\`，
                // 只判 `/xposed/capability/` 会让本包自己的文件**不被排除**
                //（我第一版就这么写错了，失败信息里全是 capability 包自己的文件）。
                .filterNot { it.path.replace('\\', '/').contains("/xposed/capability/") }
                .forEach { file ->
                    // ⚠️ **必须走 codeLines 剥注释**：我第一版直接扫原始行，
                    // 结果被 `CapabilityManifest.kt` KDoc 里那句
                    // 「见 `CapabilityPresence` 的说明」误报成调用点。
                    // 这与 `WireLayerPurityTest` 踩过的是同一个坑（假阳性）。
                    for ((lineNo, raw) in codeLines(file)) {
                        val code = stripStringLiterals(raw).trim()
                        if (code.startsWith("import ")) continue
                        requiredSymbols.keys.forEach { symbol ->
                            if (Regex("""\b$symbol\w*\b""").containsMatchIn(code)) {
                                hits.getOrPut(symbol) { mutableListOf() }
                                    .add("${file.path}:$lineNo  $code")
                            }
                        }
                    }
                }
        }

        val missing = requiredSymbols.filterKeys { hits[it].isNullOrEmpty() }
        assertTrue(
            "❌ capability 包有符号**没有任何生产调用点** —— 这正是反模式 6 的形态\n" +
                "（`CoreDexFingerprint` 教训：纯函数单测全绿，但集成点缺失，无人发现）。\n" +
                "⚠️ 这不是理论风险：契约层刚落地时实测发现这些类在 release dex 里\n" +
                "**被 R8 当死代码剥掉了**（见上方注释的对照表）。\n" +
                "缺失的符号与它们的应有消费者：\n" +
                missing.entries.joinToString("\n") { (s, why) -> "  · $s —— $why" },
            missing.isEmpty(),
        )
    }

    @Test
    fun `the call site scan is not vacuous`() {
        // ⚠️ 防「路径写错 / 正则写错 ⇒ 上面那条恒真」。
        // 断言扫描**确实找到了**几个已知存在的调用点
        val controller = File("src/main/java/com/chaomixian/vflow/core/xposed/HookChannelController.kt")
        assertTrue("HookChannelController.kt 应当存在", controller.isFile)
        val code = codeLines(controller).map { it.second }
        assertTrue(
            "扫描应当能在 HookChannelController 里找到 capability 包的符号引用 —— " +
                "找不到说明路径或剥注释逻辑坏了（上面那条会因此空转通过）",
            code.any { it.contains("CapabilityResponse") || it.contains("CapabilityInvocationCodec") },
        )
    }

    @Test
    fun `capability package is not forced into proguard keep list`() {
        // ⚠️ V2.0 §7.2b-13：**只有「按名字被外部找到的类」才需要 keep**
        //（`java_init.list` / AIDL / provider）。capability 包里没有这类东西 ——
        // 它们只被 Kotlin 代码按符号引用，R8 能正确追踪。
        //
        // 若有人往 proguard 里加它们的 keep，那是**误诊**：
        // 会掩盖「真的没有调用点」这个问题（保持类存在但功能仍无入口）。
        val proguard = File("proguard-rules.pro")
        assertTrue("proguard-rules.pro 应当存在", proguard.isFile)
        val text = proguard.readText()

        assertFalse(
            "❌ proguard 里 keep 了 capability 包的类 —— 它们不按名字被外部找到，不该 keep。\n" +
                "（若动机是「release 里找不到这些类」，正确诊断是「还没有调用点」，" +
                "见上一条断言；keep 只会掩盖它。）",
            text.contains("xposed.capability"),
        )
    }

    // ── 防「测试空转」──────────────────────────────────────

    @Test
    fun `capability package files are present so the checks above are not vacuous`() {
        val names = capabilityFiles().map { it.name }
        for (expected in listOf(
            "Capability.kt",
            "CapabilityRegistry.kt",
            "CapabilityNames.kt",
            "CapabilityPresence.kt",
        )) {
            assertTrue("capability 包缺少 $expected —— 是否改名/搬走了？", expected in names)
        }
    }

    @Test
    fun `wire layer has the new capability codec files`() {
        // ③ 的信封与既有事件信封并列，放同一个 wire 包（两处都跑 system_server）
        val wire = File("src/main/java/com/chaomixian/vflow/xposed/wire")
        val names = wire.listFiles { f -> f.name.endsWith(".kt") }?.map { it.name }.orEmpty()
        for (expected in listOf(
            "CapabilityInvocation.kt",
            "CapabilityErrorCode.kt",
            "CapabilityManifest.kt",
            "ResultBudget.kt",
            "EventEnvelope.kt",
        )) {
            assertTrue("wire 包缺少 $expected", expected in names)
        }
    }
}
