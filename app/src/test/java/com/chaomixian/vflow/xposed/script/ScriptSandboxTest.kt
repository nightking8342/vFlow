package com.chaomixian.vflow.xposed.script

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.mozilla.javascript.Context
import org.mozilla.javascript.ImporterTopLevel
import org.mozilla.javascript.Scriptable

/**
 * [ScriptSandbox] 的**真跑 Rhino** 单测：死循环必须在预算内被中断。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §4 步骤 3 / §6.2。
 *
 * ## ⚠️ 这些断言必须写成「改坏了就会红」的形态
 *
 * 方案 §6.3 明令：本任务最容易做错的是写出「看起来在测、实际什么都没测」的用例。
 * ⇒ 关键用例断言的是**真实的墙上耗时**（不是「异常消息里含某个串」这种自我满足的断言），
 * 并在注释里写明**反证方式**。
 *
 * ## ⚠️ 为什么它能在纯 JVM 里跑
 *
 * Rhino 只依赖 JDK（方案的 E9 实测）。本测试不引任何 `android.*`。
 */
class ScriptSandboxTest {

    /**
     * 真跑一段脚本，返回耗时（毫秒）与是否被中断。
     *
     * ⚠️ 结构照抄**生产路径**（`ImporterTopLevel` + `optimizationLevel = -1` +
     * `setInstructionObserverThreshold`），否则测到的不是生产行为。
     */
    private fun run(script: String, budgetMs: Long): Pair<Long, Boolean> {
        val sandbox = ScriptSandbox(budgetMs)
        val cx = sandbox.enterContext()
        try {
            @Suppress("DEPRECATION")
            cx.setOptimizationLevel(-1)
            cx.setInstructionObserverThreshold(ScriptSandbox.DEFAULT_STEP)
            val scope: Scriptable = ImporterTopLevel(cx)
            sandbox.arm()

            val started = System.currentTimeMillis()
            return try {
                cx.evaluateString(scope, script, "sandbox-test", 1, null)
                (System.currentTimeMillis() - started) to false
            } catch (_: Throwable) {
                (System.currentTimeMillis() - started) to sandbox.timedOut
            }
        } finally {
            Context.exit()
        }
    }

    /** 预热 JIT，避免第一条用例把类加载时间算进去。 */
    private fun warmUp() {
        run("var s=0; for(var i=0;i<5e4;i++) s+=i; s;", 10_000)
    }

    // ── 核心：至少两个预算值下被精确中断 ──────────────────────

    @Test
    fun `infinite loop is interrupted at two different budgets`() {
        warmUp()

        // ⚠️⚠️ 至少**两个不同**的预算值 —— 方案的 E4 就是这么测的。
        // 只测一个值的话，「耗时恰好等于那个值」可能只是巧合
        //（例如一个写死的 timeout）。
        //
        // ## ⚠️ 反证结果（已实测，与方案 §6.3 的说法不符）
        //
        // 方案说「删掉 `observeInstructionCount` 里的续期那行 ⇒ 本用例变红」。
        // **本机复现不出来**：删掉后回调次数几乎不变（144,551 vs 149,726），
        // 死循环**照样**在预算处被精确中断 ⇒ 本用例照绿。
        //
        // 真因：Rhino 1.9 的 `Interpreter` 自己就会把指令计数器重置回阈值
        // ⇒ 那行续期是 **no-op**（见 `ScriptSandbox` 类注释里的实测表与「版本防御」结论）。
        //
        // ⇒ **本用例锁的是「超时机制生效」，不是「续期生效」**。
        // 不要把它当成后者的证据。那行仍保留，理由与代价见生产代码的注释。
        //
        // 有效的反证方式：删掉整个 `observeInstructionCount` 重写
        //（或去掉 `deadlineMs` 比较）⇒ 本用例变红。
        listOf(400L, 1000L).forEach { budget ->
            val (elapsed, interrupted) = run("while(true){}", budget)

            assertTrue("预算 ${budget}ms 的死循环必须被中断（实际 ${elapsed}ms）", interrupted)
            // ⚠️ 断言**真实耗时**落在预算附近 —— 这是本用例的全部意义。
            // 下界：不能**提前**中断（提前 = 把能跑完的脚本杀掉了）
            assertTrue(
                "中断过早：预算 ${budget}ms 却在 ${elapsed}ms 就中断了",
                elapsed >= budget - GRACE_MS,
            )
            // 上界：不能**远远晚于**预算（那说明观察器没在心跳）
            assertTrue(
                "中断过晚：预算 ${budget}ms 却跑了 ${elapsed}ms —— 分段续期可能失效了",
                elapsed <= budget + TOLERANCE_MS,
            )
        }
    }

    @Test
    fun `a well behaved script is not interrupted`() {
        // ⚠️ 反向保证：沙箱不能把**正常**脚本也杀掉。
        // 只测「死循环会被中断」是不够的 —— 一个「永远立刻抛」的实现也能通过那条。
        warmUp()
        val (elapsed, interrupted) = run("var s=0; for(var i=0;i<200000;i++) s+=i; s;", 5_000)

        assertTrue("正常脚本不该被判定超时", !interrupted)
        assertTrue("正常脚本应很快完成（实际 ${elapsed}ms）", elapsed < 5_000)
    }

    // ── 脚本级规避：try/catch 拦不住（方案的 E6）────────────

    @Test
    fun `script level try catch cannot evade the timeout`() {
        // ⚠️⚠️ 这是**安全声明**的组成部分（不是锦上添花）：
        // 若脚本能用 try/catch 吞掉中断，那么「超时」对恶意/失控脚本就是纸糊的。
        //
        // 实测（方案 §1 的 E6）：四种写法**全部**被中断 —— 因为中断是
        // **解释器内部**抛出的，JS 层的 catch 捕获不到。
        //
        // 反证方式：同「分段续期」那条（这四段脚本里没有可用的 catch 路径）。
        warmUp()
        val evasions = listOf(
            "while(true){ try { } catch(e) { } }",
            "try { while(true){} } catch(e) { }",
            "while(true){ try { while(true){} } catch(e) { } }",
            "try { while(true){} } catch(e) { while(true){} }",
        )
        evasions.forEach { script ->
            val (elapsed, interrupted) = run(script, 400L)
            assertTrue("`$script` 逃过了超时（跑了 ${elapsed}ms）", interrupted)
            assertTrue("`$script` 的中断时刻 ${elapsed}ms 偏离预算 400ms 太多", elapsed <= 400 + TOLERANCE_MS)
        }
    }

    // ── ★★ 超时窗口的起点（`arm()`）—— 本批**唯一**的真回归门禁 ──

    @Test
    fun `the timeout window starts at arm not at construction`() {
        // ⚠️⚠️ 这条是方案 §6.2 条件 5 那个位置的**替代门禁**（原因见下方条目的长注释）。
        // 它锁的是一件**真实存在、且能变红**的事：超时窗口必须从 `arm()` 起算，
        // 而不是从构造时起算。
        //
        // ## 反证方式（已实测确认变红）
        //
        // 把 `arm()` 改成空实现（不重置 `deadlineMs`）⇒ 本用例**变红**
        //（脚本只会跑约 300 − ASSEMBLY_DELAY_MS = 60ms，低于下界 250ms）。
        //
        // ## 为什么必须锁它
        //
        // 环境装配（`ImporterTopLevel` 的构造 —— 实测**首次**约 210ms）若被算进
        // 脚本预算，实际可执行时长就**短于** `timeoutMs`。对贴着预算跑的脚本，
        // 这一点差异足以让「成功」变成「超时」—— 而用户看到的是
        //「我的脚本明明没超时」，排查方向会指向脚本本身（错的方向）。
        val sandbox = ScriptSandbox(timeoutMs = 300)
        val cx = sandbox.enterContext()
        try {
            @Suppress("DEPRECATION")
            cx.setOptimizationLevel(-1)
            cx.setInstructionObserverThreshold(ScriptSandbox.DEFAULT_STEP)

            // ⚠️⚠️ 刻意把「装配」这一步做得**足够慢**，让差异远远超出调度噪声。
            // 不用 `ImporterTopLevel` 的构造耗时做这件事 —— 它太不稳定
            //（实测首次约 210ms、热态仅 2ms），结果会取决于「本用例是不是同类里第一个跑的」。
            // ⇒ 用**显式可控的 sleep**，并给它一个安全的时窗：
            // 240ms 远小于 300ms 的预算 ⇒ 不可能被观察器抓住（观察器只在脚本执行时触发）。
            Thread.sleep(ASSEMBLY_DELAY_MS)

            // ★ 装配完成，武装沙箱 ⇒ deadline 从此处起算
            sandbox.arm()

            val started = System.currentTimeMillis()
            val interrupted = try {
                cx.evaluateString(ImporterTopLevel(cx), "while(true){}", "arm-test", 1, null)
                false
            } catch (_: Throwable) {
                sandbox.timedOut
            }
            val elapsed = System.currentTimeMillis() - started

            assertTrue("死循环必须被中断", interrupted)
            assertTrue(
                "❌ 超时窗口似乎从**构造时**就起算了：脚本只跑了 ${elapsed}ms，" +
                    "而预算是 300ms（装配已花掉 ${ASSEMBLY_DELAY_MS}ms）。" +
                    "这会让贴着预算的脚本被误判超时 —— 见 ScriptSandbox.arm 的说明。",
                elapsed >= 300 - 50,
            )
            assertTrue(
                "中断也不该晚太多（跑了 ${elapsed}ms）",
                elapsed <= 300 + TOLERANCE_MS,
            )
        } finally {
            Context.exit()
        }
    }

    // ── 优化级别（方案的 E5）────────────────────────────────
    //
    // ## ⚠️⚠️ 本条与方案 §6.2 条件 5 的约定不符，如实记录
    //
    // 方案说「优化级别必须是 -1」，并据此要求在测试里写一条
    // 「改 0 就变红」的 E5 回归锁（具体建议是「断言 1500ms 预算下耗时 < 1200ms」）。
    //
    // **本机三轮实测都没能复现那个差异**：优化级别 -1 / 0 / 9 在 200ms–5s 的预算
    // 区间内**都精确中断**，多种脚本形态（空死循环 / 自增 / for-;; / do-while /
    // 函数调用 / 属性自增 / 内层 try / 数组分配）一致。
    //
    // ## ⭐ 那条门禁的推算前提，我复刻出来了 —— 是一个**测量假象**
    //
    // 方案 §1.1 的探针把 `deadline` 设在 `ImporterTopLevel` 构造**之前**、
    // 把计时起点设在构造**之后** ⇒ **首次构造的约 210ms 被从测量结果里扣掉**。
    // 而探针按 `-1 → 0 → 9` 的顺序跑，只有**第一个**（`-1`）吃到这笔一次性开销
    // ⇒ 它报 387–389ms，后两个报 599ms。
    //
    // 本机逐字复刻该探针（含同样顺序、同样无预热）：
    //
    // ```
    // 第一轮  optLevel=-1 → 389ms   optLevel=0 → 599ms   optLevel=9 → 598ms
    // 第二轮  optLevel=-1 → 599ms   optLevel=0 → 599ms   optLevel=9 → 599ms   ← 完全一致
    // ```
    //
    // ⇒ 「`-1` 在预算的 65% 处中断」是**构造开销被扣掉**造成的，与优化级别无关。
    // 那条「< 1200ms」的门禁若照写，**正常路径就会红**（实测三个级别都是 1501ms）。
    //
    // ⚠️ **这与方案 §3.2 那处笔误（`deadlineMs` 越早算越糟）是同一个错的两种形态** ——
    // 一个在探针里、一个在代码里。生产代码用 `arm()` 消除它（见上一条门禁）。
    //
    // 生产代码**仍然**设 `-1`（方案要求 + 与 App 侧 `JsExecutor` 一致），
    // 并用下面这条断言锁住「它确实被设过」——那是能测的部分。

    @Test
    fun `the sandbox honours the optimization level it is given`() {
        // ⚠️ 能测的部分：给沙箱设 -1 之后，`Context.optimizationLevel` 确实是 -1。
        // `ScriptExecutor` 里那行 `cx.setOptimizationLevel(-1)` 的**存在性**
        // 由 `ScriptExecutorOptimizationTest`（源码扫描）钉住 —— 运行期测不出
        // 「生产代码到底设没设」。
        val sandbox = ScriptSandbox(1000)
        val cx = sandbox.enterContext()
        try {
            @Suppress("DEPRECATION")
            cx.setOptimizationLevel(-1)
            assertTrue("setOptimizationLevel(-1) 应当生效", cx.optimizationLevel == -1)
        } finally {
            Context.exit()
        }
    }

    // ── 阻塞调用不可中断（方案的 E7）—— 这条是 KDoc 的机器化版本 ──

    @Test
    fun `a blocking java call cannot be interrupted by the observer`() {
        // ⚠️⚠️ 这条**故意断言「拦不住」** —— 它是已知限制的机器化记录，
        // 不是缺陷报告。方案 §3.2 要求把这条限制写进 KDoc，
        // 而这里更进一步：让它**可被回归验证**。
        //
        // 若将来有人想办法让阻塞调用也可中断了，**这条会变红** ——
        // 那正是提醒他来更新 KDoc 与池容量假设的时刻。
        //
        // ⚠️ `assumeTrue`：本用例真的睡 600ms，且依赖 Rhino 对 Java 互操作的
        // 支持（`java.lang.Thread` 经 `ImporterTopLevel` 的 `Packages` 可达 ——
        // 本机探针实测该路径**确实可达**：`java.lang.Thread.sleep(500)` 真的睡了 569ms）。
        // 若该路径不可用（脚本直接抛错、耗时远小于睡眠时长），说明前置条件不成立 ——
        // 那**不能**记成功能失败，故 skip 而不是红。
        warmUp()
        val sleepMs = 600L
        val budget = 200L

        val (elapsed, _) = run("java.lang.Thread.sleep($sleepMs); 1;", budget)

        assumeTrue(
            "脚本没能走到 sleep（耗时 ${elapsed}ms 接近 0）⇒ 前置条件不成立，跳过",
            elapsed >= sleepMs / 2,
        )
        assertTrue(
            "阻塞调用被中断了（耗时 ${elapsed}ms < 睡眠 ${sleepMs}ms）—— " +
                "若这是有意为之的改进，请同步更新 ScriptSandbox 的 KDoc 与池容量假设。",
            elapsed >= sleepMs - 100,
        )
        assertTrue(
            "阻塞时长 ${elapsed}ms 应当**远超过**指令级预算 ${budget}ms —— " +
                "这正是「观察器只在执行下一条指令时触发」的直接后果",
            elapsed > budget * 2,
        )
    }

    private companion object {
        /**
         * `arm()` 用例里模拟「环境装配」的耗时。
         *
         * ⚠️ 取 240ms：必须**足够大**（让「从构造起算」的差异远超声纳噪声）、
         * 又必须**明显小于** 300ms 的预算（否则装配自己就把预算吃光，
         * 那条用例会因别的原因失败）。240 留了 60ms 的余量。
         */
        const val ASSEMBLY_DELAY_MS = 240L

        /**
         * 允许的**提前**中断量。
         *
         * ⚠️ 计时精度 + 一次回调周期，给 50ms 足够；给太大就等于没测「不能提前」。
         */
        const val GRACE_MS = 50L

        /**
         * 允许的**延后**中断量。
         *
         * ⚠️⚠️ 这个值必须**小**。
         *
         * 方案的 E5 记录过一种失败：优化级别非 -1 时，中断会发生在
         * **超时的一半处或更晚**（预算 600ms → 599ms 才兜到，预算越大偏差越大）。
         * 若把本值放宽到几百毫秒，那种失败就**测不出来了**（断言会照样通过）。
         *
         * 300ms 在 400ms–1000ms 的预算区间上足以容纳 Windows 的调度抖动，
         * 同时仍能抓住「偏差随时长增长」那类问题。
         */
        const val TOLERANCE_MS = 300L
    }
}
