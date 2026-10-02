package com.chaomixian.vflow.core.execution

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `JsExecutor.execute()` 的**接线锚定**测试（源码扫描型）。
 *
 * ## 为什么需要这一层（而不是只有 `JsTimeoutTest`）
 *
 * `JsTimeoutTest` 是**端到端**的（真实 Rhino + 真实 `JsExecutor`），强度高于本文件。
 * 但它**证明不了两件事**：
 *
 * 1. **`finally` 里的顺序**：`endBudget(context)` 必须在 `exit()` **之前**。
 *    `endBudget` 要读 `context.instructionObserverThreshold`，而 `exit()` 之后
 *    Context 已经交还给 ThreadLocal 语义（可能被下一次 `enter()` 复用）。
 *    实测确认：这两行**换序后现有单测仍会全绿** —— 即端到端测试对这条契约是盲的，
 *    只能靠本文件锁住。
 * 2. **异常归一 catch 的位置**：它必须排在 `catch (RhinoException)` / `catch (Exception)`
 *    **之前**，否则 `JsScriptTimeoutException`（是 RuntimeException）会被后者的
 *    `catch (Exception)` 接走、包装成 `Execution failed: ...`，上层再也分不出超时。
 *    （这一条 A12 其实覆盖到了，此处作为第二道锁。）
 *
 * ⚠️ 本仓库在 `CoreLauncher` / `CoreDexFingerprint` 上踩过「纯函数单测全绿但调用点缺失」，
 * 此类**调用点**缺陷只有源码扫描能拦住，故保留这一层。
 */
class JsExecutorTimeoutWiringTest {

    private val source: String by lazy {
        // 测试工作目录是 :app 模块目录（Gradle 约定）。
        val file = File("src/main/java/com/chaomixian/vflow/core/execution/JsExecutor.kt")
        assertTrue(
            "找不到 JsExecutor.kt（工作目录=${File(".").absolutePath}）—— 路径若变了需同步更新本测试",
            file.exists()
        )
        file.readText()
    }

    @Test
    fun `execute uses the timeout capable factory instead of the plain Context enter`() {
        assertTrue(
            "execute() 必须用 JsTimeoutContextFactory.enter()；用 Context.enter() 时" +
                "全局默认 factory 的 observeInstructionCount 是空实现，超时永不生效",
            source.contains("val context = JsTimeoutContextFactory.enter()")
        )
        assertTrue(
            "不得残留裸 Context.enter()",
            !Regex("""(?<!JsTimeoutContextFactory\.)\bContext\.enter\(\)""").containsMatchIn(source)
        )
    }

    @Test
    fun `execute opens and closes a budget around the script`() {
        assertTrue(
            "execute() 必须调 beginBudget(context, timeoutMs)",
            source.contains("JsTimeoutContextFactory.beginBudget(context, timeoutMs)")
        )
        assertTrue(
            "execute() 必须调 endBudget(context) —— 否则预算栈会持续增长、且观察器永不复位",
            source.contains("JsTimeoutContextFactory.endBudget(context)")
        )
    }

    /**
     * ⚠️ 本文件存在的主要理由（见类注释第 1 条）：端到端测试对这条顺序是盲的。
     */
    @Test
    fun `endBudget is called before exit in the finally block`() {
        val finallyBlock = extractFinallyBlock()
        val endBudgetAt = finallyBlock.indexOf("endBudget(context)")
        val exitAt = finallyBlock.indexOf("JsTimeoutContextFactory.exit()")

        assertTrue("finally 块里应有 endBudget", endBudgetAt >= 0)
        assertTrue("finally 块里应有 JsTimeoutContextFactory.exit()", exitAt >= 0)
        assertTrue(
            "endBudget 必须在 exit() **之前**：endBudget 要读 context.instructionObserverThreshold，" +
                "exit() 之后 Context 已不再属于本调用。实际顺序 endBudget@$endBudgetAt / exit@$exitAt",
            endBudgetAt < exitAt
        )
    }

    @Test
    fun `the timeout catch precedes the generic catches`() {
        val timeoutAt = source.indexOf("catch (e: JsScriptTimeoutException)")
        val rhinoAt = source.indexOf("catch (e: RhinoException)")
        val genericAt = source.indexOf("catch (e: Exception)")

        assertTrue("应有 JsScriptTimeoutException 的前置 catch", timeoutAt >= 0)
        assertTrue("应有 RhinoException 的 catch", rhinoAt >= 0)
        assertTrue("应有 Exception 的兜底 catch", genericAt >= 0)
        assertTrue(
            "超时 catch 必须排在最前，否则会被 catch(Exception) 包装成 'Execution failed: ...'。" +
                "实际 timeout@$timeoutAt / rhino@$rhinoAt / generic@$genericAt",
            timeoutAt < rhinoAt && timeoutAt < genericAt
        )
    }

    /**
     * 取出 `execute()` 的 `finally { ... }` 块（按大括号配对，跳过字符串与注释）。
     *
     * ⚠️ 不用正则直接匹配 —— 块内有 `}`（字符串与嵌套），正则会截错位置。
     */
    private fun extractFinallyBlock(): String {
        val anchor = source.indexOf("} finally {")
        assertTrue("未找到 execute() 的 finally 块", anchor >= 0)

        var depth = 0
        var i = source.indexOf('{', anchor)
        val start = i
        var inString = false
        var inLineComment = false
        var inBlockComment = false
        while (i < source.length) {
            val c = source[i]
            val next = source.getOrNull(i + 1)
            when {
                inLineComment -> if (c == '\n') inLineComment = false
                inBlockComment -> if (c == '*' && next == '/') { inBlockComment = false; i++ }
                inString -> if (c == '\\') i++ else if (c == '"') inString = false
                c == '/' && next == '/' -> inLineComment = true
                c == '/' && next == '*' -> { inBlockComment = true; i++ }
                c == '"' -> inString = true
                c == '{' -> depth++
                c == '}' -> {
                    depth--
                    if (depth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        error("finally 块未闭合")
    }
}
