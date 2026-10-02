package com.chaomixian.vflow.core.execution

import android.content.ContextWrapper
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mozilla.javascript.Context
import java.io.File
import java.util.Stack

/**
 * `JsTimeout` 的真实 Rhino 测试。
 *
 * ⚠️ **全部用真实 Rhino 引擎，不 mock** —— 本测试的全部价值就在于证明
 * 真实引擎里的纯计算死循环能被指令级观察器打断。
 *
 * ⚠️ **每个用例体都在独立线程里跑**：死循环若因回归而不再被中断，在测试线程上直接跑
 * 会把整个 Gradle 测试进程挂死（表现为 CI 超时而不是「测试失败」）。
 * 独立线程 + `join(上限)` + 事后 `interrupt()`，把「挂死」变成「断言失败」。
 * 副产物：ThreadLocal 预算栈也随之隔离（每个用例一个干净线程）。
 */
class JsTimeoutTest {

    @After
    fun tearDown() {
        JsTimeoutContextFactory.clearBudgetsForTest()
    }

    // ------------------------------------------------------------------
    // A 组：纯 Rhino（不经过 JsExecutor）
    // ------------------------------------------------------------------

    @Test
    fun `A1 pure computation infinite loop is interrupted within the budget`() {
        val started = System.currentTimeMillis()
        val outcome = runOffThread(limitMs = 2_000) {
            withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 300)
                context.evaluateString(context.initStandardObjects(), "while(true){}", "t", 1, null)
            }
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("应抛出 JsScriptTimeoutException，实际: ${outcome.describe()}", outcome.isTimeout)
        assertTrue("中断耗时 ${elapsed}ms 应远小于 2000ms", elapsed < 2_000)
    }

    /**
     * A2 —— **反向锁**：阈值必须 > 0 才有效。
     *
     * 锁的是「忘了设 `setInstructionObserverThreshold` = 静默失效、没有任何报错」这个失败模式。
     * ⚠️ 本用例断言的是「**未被**中断」——若将来有人「优化」掉阈值设置，它会**变红**，
     * 这正是我们要的通知。
     */
    @Test
    fun `A2 without the threshold the observer never fires so the loop is not interrupted`() {
        val outcome = runOffThread(limitMs = 2_000) {
            withTimeoutContext { context ->
                // 刻意不调 beginBudget ⇒ 阈值保持 0 ⇒ 观察器被关闭。
                context.evaluateString(context.initStandardObjects(), "while(true){}", "t", 1, null)
            }
        }

        assertTrue(
            "阈值 0 时死循环**不应**被中断（这正是要锁住的静默失效点），实际: ${outcome.describe()}",
            !outcome.finished
        )
    }

    @Test
    fun `A3 script-level try catch cannot swallow the timeout`() {
        val outcome = runOffThread(limitMs = 2_000) {
            withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 300)
                context.evaluateString(
                    context.initStandardObjects(),
                    "try{while(true){}}catch(e){}",
                    "t", 1, null
                )
            }
        }

        assertTrue("脚本层 try/catch 不得吞掉超时，实际: ${outcome.describe()}", outcome.isTimeout)
    }

    @Test
    fun `A4 try catch inside the loop body cannot swallow the timeout either`() {
        val outcome = runOffThread(limitMs = 2_000) {
            withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 300)
                context.evaluateString(
                    context.initStandardObjects(),
                    "var n=0; while(true){ try{n++}catch(e){} }",
                    "t", 1, null
                )
            }
        }

        assertTrue("循环体内的 try/catch 不得吞掉超时，实际: ${outcome.describe()}", outcome.isTimeout)
    }

    /** A5 —— 预算栈取**最紧**的 deadline（实测：外层 5000ms + 内层 700ms ⇒ 中断于 689ms）。 */
    @Test
    fun `A5 the tightest deadline in the budget stack wins`() {
        val started = System.currentTimeMillis()
        val outcome = runOffThread(limitMs = 3_000) {
            withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 5_000)
                JsTimeoutContextFactory.beginBudget(context, 300)
                context.evaluateString(context.initStandardObjects(), "while(true){}", "t", 1, null)
            }
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("应取最紧的 300ms 预算，实际: ${outcome.describe()}", outcome.isTimeout)
        assertTrue("耗时 ${elapsed}ms 应接近 300ms 而非 5000ms", elapsed < 2_000)
    }

    /**
     * A6 —— **内层退出不得关掉外层观察器**的针对性回归。
     *
     * 失败模式：内层 `endBudget` 无条件把阈值复位为 0 ⇒ 外层剩余部分从此**没有超时**，
     * 而外层脚本根本不知道（静默失效，极难排查）。
     */
    @Test
    fun `A6 inner budget exit does not switch off the outer observer`() {
        val started = System.currentTimeMillis()
        val outcome = runOffThread(limitMs = 4_000) {
            withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 1_500)
                // 内层：开了又立刻关掉。
                JsTimeoutContextFactory.beginBudget(context, 300)
                JsTimeoutContextFactory.endBudget(context)

                assertNotEquals(
                    "内层退出后阈值不得被复位为 0，否则外层失去超时",
                    0, context.instructionObserverThreshold
                )

                context.evaluateString(context.initStandardObjects(), "while(true){}", "t", 1, null)
            }
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue("外层预算仍应生效，实际: ${outcome.describe()}", outcome.isTimeout)
        assertTrue("应约 1500ms 中断，实际 ${elapsed}ms", elapsed < 3_000)
    }

    /** A7 —— 超时后没有状态残留：同一 factory 再跑正常脚本仍正确，ThreadLocal 已清空。 */
    @Test
    fun `A7 the factory stays usable after a timeout and leaves no thread local residue`() {
        val outcome = runOffThread(limitMs = 5_000) {
            withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 300)
                try {
                    context.evaluateString(context.initStandardObjects(), "while(true){}", "t", 1, null)
                } catch (expected: JsScriptTimeoutException) {
                    // 预期路径
                }
            }

            // 同一个 factory 上再跑一个正常脚本。
            val value = withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 5_000)
                context.evaluateString(context.initStandardObjects(), "1 + 1", "t", 1, null)
            }
            assertEquals(2.0, (value as Number).toDouble(), 1e-9)

            assertEquals(
                "exit() 之后当前线程不应还持有 Context",
                null, Context.getCurrentContext()
            )
        }

        assertTrue("正常脚本应能跑完，实际: ${outcome.describe()}", outcome.error == null)
    }

    @Test
    fun `A8 a normal script returns its value without being killed`() {
        val outcome = runOffThread(limitMs = 3_000) {
            val value = withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 5_000)
                context.evaluateString(context.initStandardObjects(), "6 * 7", "t", 1, null)
            }
            assertEquals(42.0, (value as Number).toDouble(), 1e-9)
        }

        assertTrue("正常脚本不得被误杀，实际: ${outcome.describe()}", outcome.error == null)
    }

    @Test
    fun `A9 repeated timeouts are all interrupted and leave the thread local clean`() {
        val outcome = runOffThread(limitMs = 8_000) {
            repeat(3) { round ->
                val started = System.currentTimeMillis()
                var timedOut = false
                withTimeoutContext { context ->
                    JsTimeoutContextFactory.beginBudget(context, 300)
                    try {
                        context.evaluateString(context.initStandardObjects(), "while(true){}", "t", 1, null)
                    } catch (expected: JsScriptTimeoutException) {
                        timedOut = true
                    }
                }
                val elapsed = System.currentTimeMillis() - started
                assertTrue("第 ${round + 1} 轮应超时", timedOut)
                assertTrue("第 ${round + 1} 轮耗时 ${elapsed}ms 异常", elapsed < 2_000)
                assertEquals("第 ${round + 1} 轮后 ThreadLocal 应干净", null, Context.getCurrentContext())
            }
        }

        assertTrue("三轮均应正常完成，实际: ${outcome.describe()}", outcome.error == null)
    }

    /**
     * A10 —— §4.1 契约：`null` / `<= 0` 表示**不超时**，且**不得开启**观察器。
     *
     * 这是验收项「既有 `JsModule` 行为不变」的机器化锁：默认参数下 `hasActiveBudget()==false`
     * ⇒ 不调 `setInstructionObserverThreshold` ⇒ 阈值保持 0 ⇒ 与改动前的 `Context.enter()`
     * 路径等价。
     */
    @Test
    fun `A10 a null or non positive timeout neither kills the script nor enables the observer`() {
        val outcome = runOffThread(limitMs = 15_000) {
            for (noTimeout in listOf<Long?>(null, 0L, -1L)) {
                val value = withTimeoutContext { context ->
                    JsTimeoutContextFactory.beginBudget(context, noTimeout)
                    assertEquals(
                        "无活跃预算时不得开启观察器（timeoutMs=$noTimeout）",
                        0, context.instructionObserverThreshold
                    )
                    val v = context.evaluateString(
                        context.initStandardObjects(),
                        "var s=0; for (var i=0;i<200000;i++) s+=i; s",
                        "t", 1, null
                    )
                    // endBudget 必须与 beginBudget 配对（模拟 JsExecutor 的 finally）。
                    JsTimeoutContextFactory.endBudget(context)
                    v
                }
                assertEquals(
                    "有限循环应跑完（timeoutMs=$noTimeout）",
                    19_999_900_000.0, (value as Number).toDouble(), 1e-3
                )
            }
        }

        assertTrue("有限循环不得被误杀，实际: ${outcome.describe()}", outcome.error == null)
    }

    /**
     * A11 —— ⚠️ **已知限制的诚实记录，不是回归**。
     *
     * 指令级观察器只在「执行下一条指令」时触发，**阻塞的 Java 调用期间根本没有下一条指令**。
     * 所以这里断言的是「**正常结束、不抛**」，而**不是**「在 200ms 内被中断」。
     *
     * 实测参照：`while(true){ Thread.sleep(2000); }` + 800ms 预算 ⇒ **164680ms** 才结束。
     * 本用例只用单次 `sleep(400)`（约 400ms 结束），上界取 2s，远离那个量级。
     */
    @Test
    fun `A11 blocking java calls are not interruptible a known documented limitation`() {
        val started = System.currentTimeMillis()
        val outcome = runOffThread(limitMs = 5_000) {
            val value = withTimeoutContext { context ->
                JsTimeoutContextFactory.beginBudget(context, 200)
                context.evaluateString(
                    context.initStandardObjects(),
                    "java.lang.Thread.sleep(400); 'done'",
                    "t", 1, null
                )
            }
            assertEquals("阻塞脚本应正常跑完（超时对它无效）", "done", value)
        }
        val elapsed = System.currentTimeMillis() - started

        assertTrue(
            "阻塞调用**不应**被超时打断 —— 这是已知限制；此处失败说明机制语义变了: ${outcome.describe()}",
            outcome.error == null
        )
        assertTrue(
            "耗时 ${elapsed}ms 应在 sleep 量级（约 400ms），而非被 200ms 预算切断",
            elapsed < 2_000
        )
    }

    /**
     * A12 —— 端到端：`JsExecutor.execute()` 里抛出的必须是**未包装**的
     * [JsScriptTimeoutException]（而不是 `Execution failed: ...`）。
     *
     * 覆盖 §4.3 的异常归一契约（新增前置 catch 的实际效果）。
     */
    @Test
    fun `A12 executor surfaces the timeout exception unwrapped end to end`() {
        val outcome = runOffThread(limitMs = 5_000) {
            JsExecutor(executionContext()).execute("while(true){}", mutableMapOf(), timeoutMs = 300)
        }

        assertTrue("应抛 JsScriptTimeoutException，实际: ${outcome.describe()}", outcome.isTimeout)
        assertEquals("超时值应原样带出", 300L, (outcome.error as JsScriptTimeoutException).timeoutMs)
    }

    /** A12b —— 阳性对照：默认参数（不超时）下 `execute()` 行为与改动前一致。 */
    @Test
    fun `A12b executor without a timeout still runs normally`() {
        val outcome = runOffThread(limitMs = 10_000) {
            val result = JsExecutor(executionContext()).execute("({answer: 6 * 7})", mutableMapOf())
            assertEquals(42.0, (result["answer"] as Number).toDouble(), 1e-9)
        }

        assertTrue("默认无超时时正常脚本应跑完，实际: ${outcome.describe()}", outcome.error == null)
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    /** 一次线程执行的观测结果。 */
    private class Outcome(val error: Throwable?, val finished: Boolean) {
        val isTimeout: Boolean get() = error is JsScriptTimeoutException

        fun describe(): String = when {
            error != null -> "${error::class.java.simpleName}: ${error.message}"
            !finished -> "线程仍在运行（未被中断）"
            else -> "正常结束"
        }
    }

    /**
     * 在独立线程里跑 [block]，最多等 [limitMs]。
     *
     * 超时未结束则 `interrupt()` 收尾并把 `finished` 记作 false —— 这样「观察器失效」
     * 表现为**断言失败**而不是 Gradle 进程挂死。
     */
    private fun runOffThread(limitMs: Long, block: () -> Unit): Outcome {
        var error: Throwable? = null
        var finished = false
        val thread = Thread {
            try {
                block()
                finished = true
            } catch (e: Throwable) {
                error = e
                finished = true
            }
        }
        thread.isDaemon = true
        thread.start()
        thread.join(limitMs)
        if (thread.isAlive) {
            thread.interrupt()
            finished = false
        }
        return Outcome(error, finished)
    }

    /**
     * 用超时 factory 进入一个 Context，并在**同一线程**上配对退出。
     *
     * Rhino 的 `Context` 不是 `AutoCloseable`，故用这个局部辅助统一收尾
     * （对应 `JsExecutor.execute` 的 `finally` 块）。
     */
    private inline fun <T> withTimeoutContext(block: (Context) -> T): T {
        val context = JsTimeoutContextFactory.enter()
        try {
            return block(context)
        } finally {
            JsTimeoutContextFactory.exit()
        }
    }

    private fun executionContext(): ExecutionContext = ExecutionContext(
        applicationContext = StubContext(),
        variables = mutableMapOf(),
        magicVariables = mutableMapOf(),
        services = ExecutionServices(),
        allSteps = emptyList(),
        currentStepIndex = 0,
        stepOutputs = mutableMapOf(),
        loopStack = Stack(),
        namedVariables = mutableMapOf(),
        workDir = File("build/test-workdir-js-timeout")
    )

    /**
     * 纯 JVM 测试用的最小 `Context` 替身。
     *
     * ⚠️ **不能用 `ContextWrapper(null)`**：`execute()` 第一步就调
     * `applicationContext.classLoader`，而 android.jar 的桩实现在未 mock 时会抛
     * `Method getClassLoader in android.content.ContextWrapper not mocked`
     * （实测：A12/A12b 初版即因此失败）。
     *
     * 这里只覆盖 `execute()` 真正会走到的那几个方法；其余一律返回 null / 空实现 ——
     * 它们对应的路径（`getSystemService` / `getContentResolver`）在脚本里才被使用，
     * 本测试的脚本不碰。
     */
    private class StubContext : ContextWrapper(null) {
        override fun getClassLoader(): ClassLoader = StubContext::class.java.classLoader

        override fun getApplicationContext(): android.content.Context = this
    }
}
