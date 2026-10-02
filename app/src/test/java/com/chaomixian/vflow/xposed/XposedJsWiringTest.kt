package com.chaomixian.vflow.xposed

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **源码扫描型**测试：锁住 `xposed_js` capability 的三处**运行期测不出来**的要求。
 *
 * ## ⚠️ 为什么必须有这一层（而不是「多写几个单测」）
 *
 * 本仓库反复记录的反模式是「**写了调用点注释、但没有调用点**」
 *（`CoreDexFingerprint` 那次：13 个纯函数单测全绿，集成点缺失，
 * 表现是「永远不提示重启，用户静默跑旧 Core 代码」）。
 *
 * `xposed_js` 的三处关键要求**恰恰都是接线性的**：
 *
 * | # | 要求 | 为什么纯函数测试测不到 |
 * |---|---|---|
 * | 1 | `HookCapabilityRegistry.init` 真的注册了 handler | 注册表是进程级单例，用例会 reset 它且 `init` 不重跑 |
 * | 2 | `VFlowHookEntry.setup()` 真的赋值了 `contextProvider` | 它跑在 system_server 里，App 侧根本进不去那个方法 |
 * | 3 | `ScriptExecutor` 真的设了 `optimizationLevel = -1` | 运行期测不出「**生产代码**到底设没设」 |
 *
 * ## ⚠️ 本文件的边界（写清楚，免得被当成万能的）
 *
 * 它检查的是**源码文本**。它能拦住「有人顺手删了那行」，但**拦不住**
 * 「那行执行了却没生效」—— 后者靠行为测试（`ScriptExecutorTest` 真跑 Rhino）。
 * 两层是**互补**的，不是替代关系。
 */
class XposedJsWiringTest {

    private companion object {
        const val HOOK_ROOT = "src/main/java/com/chaomixian/vflow/xposed"
    }

    /**
     * 剥掉注释后的**代码行**（保留原始行号）。
     *
     * ⚠️⚠️ **必须剥注释**：本文件自己的 KDoc 与生产文件的 KDoc 里都**列举**了
     * 那些待检查的符号名（这正是第一版 `WireLayerPurityTest` 的假阳性形态 ——
     * 它把自己的文档当成了真引用）。
     *
     * ⚠️ 但剥注释有个**危险的方向**：剥得不干净会把真代码漏掉。
     * 所以本类末尾有一条「剥得不过头」的防空转断言。
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
            out += (index + 1) to raw
        }
        return out
    }

    private fun sourceOf(relative: String): String {
        val file = File(relative)
        assertTrue("应当存在：$relative", file.isFile)
        return file.readText()
    }

    // ── 要求 1：注册表真的注册了 ──────────────────────────────

    @Test
    fun `the hook side registry registers the xposed js handler`() {
        // ⚠️ 反证方式：删掉 `HookCapabilityRegistry.init` 里的
        // `register(XposedJsCapabilityHandler())` ⇒ 本用例变红。
        //
        // 漏了的后果：hook 侧没有这个 capability ⇒ App 侧调用拿到
        // `capability_absent`，而表现是「这能力明明装了却说没有」，
        // 两端各自看都没问题。
        val registry = File("$HOOK_ROOT/capabilities/HookCapabilityRegistry.kt")
        assertTrue("HookCapabilityRegistry.kt 应当存在", registry.isFile)

        val code = codeLines(registry).joinToString("\n") { it.second }
        assertTrue(
            "❌ `init` 块里必须注册 XposedJsCapabilityHandler",
            code.contains("register(XposedJsCapabilityHandler())"),
        )
    }

    @Test
    fun `the registry does not reorder existing registrations`() {
        // ⚠️ 只判「新注册在既有两个之后」—— 让上游合并的冲突面停留在
        // 「尾部追加」这一种形态。
        val code = codeLines(File("$HOOK_ROOT/capabilities/HookCapabilityRegistry.kt"))
            .joinToString("\n") { it.second }

        val diagnostic = code.indexOf("register(DiagnosticCapabilityHandler())")
        val shortcut = code.indexOf("register(QueryShortcutIntentsHandler())")
        val js = code.indexOf("register(XposedJsCapabilityHandler())")

        assertTrue("三个注册都应当存在", diagnostic >= 0 && shortcut >= 0 && js >= 0)
        assertTrue("DiagnosticCapabilityHandler 应仍在最前", diagnostic < shortcut)
        assertTrue("XposedJsCapabilityHandler 应**追加在最后**（不重排既有注册）", shortcut < js)
    }

    // ── 要求 2：contextProvider 的接线 ────────────────────────

    @Test
    fun `setup assigns the context provider before starting the runtime`() {
        // ⚠️⚠️ 反证方式：删掉 `VFlowHookEntry` 里那行赋值 ⇒ 本用例变红。
        //
        // 漏了的后果**不是报错**，而是「脚本里 `typeof context === "undefined"`」——
        // 用户会去查自己的脚本（错的方向）。这条正是本仓库反复记录的
        // 「静默变差」形态，纯函数测试**测不出来**（`contextProvider` 默认是 null，
        // 而 null 是**合法**的降级路径）。
        val entry = File("$HOOK_ROOT/VFlowHookEntry.kt")
        assertTrue("VFlowHookEntry.kt 应当存在", entry.isFile)
        val code = codeLines(entry).joinToString("\n") { it.second }

        assertTrue(
            "❌ `setup()` 里必须赋值 XposedJsCapabilityHandler.contextProvider",
            code.contains("XposedJsCapabilityHandler.contextProvider = ::systemContext"),
        )
    }

    @Test
    fun `the context provider assignment precedes rt start`() {
        // ⚠️ 顺序要求：赋值必须在 `rt.start()` **之前** ——
        // 赋值晚了的后果是「第一次调用时 context 还是 null」，
        // 而那**不是报错**，只是第一个请求少了个变量。
        //
        // ⚠️⚠️ **必须剥注释**：那行赋值本身就在注释里被引用（本类的 KDoc 也引用它），
        // 用 `readText()` 会让 `indexOf` 命中**注释里**的那一处，
        // 得到先于 `rt.start()` 的假位置（我第一版就这么写错了，被这条断言抓出来）。
        val code = codeLines(File("$HOOK_ROOT/VFlowHookEntry.kt"))
            .joinToString("\n") { it.second }

        val assign = code.indexOf("XposedJsCapabilityHandler.contextProvider = ::systemContext")
        val start = code.indexOf("rt.start()")

        assertTrue("两处都应当存在（assign=$assign, start=$start）", assign >= 0 && start >= 0)
        assertTrue(
            "❌ contextProvider 的赋值必须在 `rt.start()` 之前 —— " +
                "否则第一个 invoke 可能拿不到 context（静默，不报错）",
            assign < start,
        )
    }

    @Test
    fun `systemContext is reachable from the capabilities package`() {
        // ⚠️ 赋值那行能编译的前提是 `systemContext()` 不再 private。
        // 若有人把它改回 private，编译就会失败 —— 但那是在**别人**的改动里失败，
        // 排查成本高；这条把要求就地钉住。
        val entry = File("$HOOK_ROOT/VFlowHookEntry.kt").readText()
        assertTrue(
            "❌ systemContext() 必须是 internal（setup() 在同一类里，但赋值给的是" +
                "另一个包里的静态字段，可见性语义需要它至少 internal）",
            Regex("""internal\s+fun\s+systemContext\s*\(""").containsMatchIn(entry),
        )
    }

    // ── 要求 3：优化级别 -1（方案 §1 的 E5）──────────────────

    @Test
    fun `the executor sets the optimization level to minus one`() {
        // ⚠️ 方案 §1 的 E5 要求生产代码设 `-1`（解释模式），
        // 且方案 §6.2 条件 5 要求有回归锁。
        //
        // ⚠️⚠️ **但方案要求的那条「行为回归锁」我写不出来** ——
        // 本机实测**没能复现**优化级别导致的精度差异（-1/0/9 都精确中断，
        // 见 `ScriptSandboxTest` 里那一段如实记录）。
        // ⇒ 退而求其次：把「生产代码里设过它」这件事用源码扫描钉住。
        // 这**弱于**方案要求的锁，但不为空转（删掉那行就会红）。
        val executor = File("$HOOK_ROOT/script/ScriptExecutor.kt")
        assertTrue("ScriptExecutor.kt 应当存在", executor.isFile)

        val code = codeLines(executor).joinToString("\n") { it.second }
        assertTrue(
            "❌ ScriptExecutor 必须设 `setOptimizationLevel(-1)`（解释模式）—— 见方案 §1 的 E5",
            code.contains("setOptimizationLevel(-1)"),
        )
        assertFalse(
            "❌ 不该出现非 -1 的优化级别设置",
            Regex("""setOptimizationLevel\(\s*[0-9]""").containsMatchIn(code),
        )
    }

    @Test
    fun `the executor does not inject the vflow module tree`() {
        // ⚠️⚠️ 本任务**最要紧**的一条约束（V2.0 §5.7 的「定义性差别」）。
        //
        // 反证方式：在 `ScriptExecutor` 里加一行任何形式的模块树注入 ⇒ 本用例变红。
        //
        // 行为侧由 `ScriptExecutorTest` 的 `typeof vflow === "undefined"` 锁住；
        // 这里再钉一道源码级 —— 因为「注入」这件事**一旦发生就是整机级风险**，
        // 两道防线不算冗余（与 `BinderTransport` / `onInvoke` 的双重 try/catch 同理）。
        val code = codeLines(File("$HOOK_ROOT/script/ScriptExecutor.kt"))
            .joinToString("\n") { it.second }

        listOf("injectVFlowModules", "ModuleRegistry", "vflow.device", "\"vflow\"").forEach { forbidden ->
            assertFalse(
                "❌ ScriptExecutor 里不得出现 `$forbidden` —— " +
                    "本执行环境**不注入 vflow 模块树**（V2.0 §5.7 的定义性差别，不是省事）",
                code.contains(forbidden),
            )
        }
    }

    @Test
    fun `the script package does not import core execution classes`() {
        // ⚠️ `JsConsole` / `JsValueConverter` 都在 `core.execution` 下 ——
        // 命中 `FORBIDDEN_APP_PACKAGES`（会扩大崩溃半径到整机）。
        // 这正是任务要求「**必须移植**一份 JsConsole」的原因。
        val dir = File("$HOOK_ROOT/script")
        assertTrue("script 包应当存在", dir.isDirectory)

        val files = dir.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.toList()
        assertTrue("script 包里应当有文件（防目录被搬走 ⇒ 本用例空转）", files.size >= 4)

        val violations = mutableListOf<String>()
        files.forEach { file ->
            codeLines(file).forEach { (lineNo, raw) ->
                if (raw.trim().startsWith("import ") && raw.contains("core.execution")) {
                    violations += "${file.name}:$lineNo  ${raw.trim()}"
                }
            }
        }
        assertTrue(
            "❌ script 包不得引用 `core.execution.*` —— 那会把 App 侧重类拖进 system_server。\n" +
                "`JsConsole` 必须是**移植版**（见其类注释）。\n违规项：\n" + violations.joinToString("\n"),
            violations.isEmpty(),
        )
    }

    // ── 防空转 ──────────────────────────────────────────────

    @Test
    fun `the wiring files are present so the checks above are not vacuous`() {
        val names = File("$HOOK_ROOT/script")
            .listFiles { f -> f.name.endsWith(".kt") }
            ?.map { it.name }
            .orEmpty()

        listOf("ScriptRequest.kt", "ScriptSandbox.kt", "ScriptExecutor.kt", "JsConsole.kt").forEach {
            assertTrue("script 包缺少 $it —— 是否改名/搬走了？", it in names)
        }
        assertTrue(
            "capabilities 包里应当有 XposedJsCapabilityHandler.kt",
            File("$HOOK_ROOT/capabilities/XposedJsCapabilityHandler.kt").isFile,
        )
    }

    @Test
    fun `comment stripping does not swallow real code`() {
        // ⚠️ 防「剥过头 ⇒ 上面所有扫描全部空转通过」。
        // 用真实文件做反向验证：剥完之后必须还能看到已知存在的代码行。
        val code = codeLines(File("$HOOK_ROOT/script/ScriptExecutor.kt"))
            .joinToString("\n") { it.second }

        assertTrue("剥离后应仍能看到 import 行", code.contains("import org.mozilla.javascript"))
        assertTrue("剥离后应仍能看到 evaluateString 调用", code.contains("evaluateString("))
        assertTrue("剥离后应仍能看到 setOptimizationLevel", code.contains("setOptimizationLevel(-1)"))
    }
}
