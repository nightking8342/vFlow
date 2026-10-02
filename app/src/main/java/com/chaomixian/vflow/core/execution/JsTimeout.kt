package com.chaomixian.vflow.core.execution

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/**
 * JavaScript 脚本超时异常。
 *
 * ⚠️ 用 `RuntimeException` 而非 `Error`：从 `observeInstructionCount` 抛出的
 *    RuntimeException 实测可**原样穿透** Rhino 与 `ContextFactory.call`（未被包装成
 *    `WrappedException`），因此调用方能按类型判断「超时」与「脚本报错」。
 *
 * @param timeoutMs 触发本次超时的那个预算值（取栈中最紧的一份）。
 */
class JsScriptTimeoutException(val timeoutMs: Long) :
    RuntimeException("JavaScript 脚本执行超时（${timeoutMs}ms）")

/**
 * 带**指令级超时**的 ContextFactory。
 *
 * ## 一、为什么必须是 ContextFactory，而不是只设阈值
 *
 * `Context.setInstructionObserverThreshold(N)` 只负责「每 N 条指令回调一次」；
 * 真正的回调落点是 `factory.observeInstructionCount`，而**全局默认 factory 的实现是空的**
 * （字节码方法体只有 `return`）⇒ 只设阈值等于什么都没做。
 *
 * 实测对照（Rhino 1.9.0）：
 * - 自定义 factory + threshold=10000 + `while(true){}` ⇒ **108ms 被中断**
 * - 全局默认 factory + 同 threshold + 同脚本 ⇒ **16784ms 自然跑完，完全不中断**
 *
 * ⚠️ 另一条实测：**阈值 <= 0 会关闭观察器**（`setInstructionObserverThreshold` 内部调
 *    `setGenerateObserverCount(N > 0)`）。阈值 0 + 死循环 ⇒ **>3s 不被中断**。
 *    即「忘了设阈值」= 静默失效，没有任何报错。
 *
 * ⚠️ 观察器**不依赖解释模式**：`optimizationLevel` = -1 / 0 / 1 / 9 实测**均在 ~400ms 被中断**。
 *    即本机制与既有的 `optimizationLevel = -1` **无耦合**（该行保持不动）。
 *
 * ## 二、为什么 factory 是单例、超时值用栈
 *
 * 脚本执行**可以嵌套**：脚本 A 执行中，其调用模块的参数里若含内联 `{% %}`
 * （`VariableResolver` → `InlineScriptEvaluator`），会再建一个 `JsExecutor` 跑脚本 B。
 *
 * 实测确认嵌套时的真实语义（这一条决定了整个设计）：
 * - 内层 `enter()` **返回同一个 Context 对象**（`Context.enter` 的 ThreadLocal 复用分支），
 *   且 **factory 保持为最外层那个** —— 内层试图换 factory 会被**忽略**。
 * - 内层 `setInstructionObserverThreshold(0)` 会让**外层失去超时**（危险）。
 *
 * ⇒ 因此：factory 用**单例**（嵌套天然共享）；超时值用 **ThreadLocal 栈**，
 *   判定取**栈中最紧的 deadline**（实测：外层 5000ms + 内层 700ms ⇒ 中断于 689ms）；
 *   阈值**只在「栈中存在活跃预算」时开启、在「栈中不再有活跃预算」时才关闭** ——
 *   这条是上文那个坑的针对性修复（内层退出不得关掉外层的观察器）。
 *
 * ## 三、⚠️ 它**只覆盖纯计算死循环**，不覆盖阻塞的 Java 调用
 *
 * 指令级观察器**只在「执行下一条指令」时触发**。脚本里一次 `java.lang.Thread.sleep(...)`、
 * 一次网络 IO、一次等锁，观察器**根本没有机会跑**。
 *
 * 实测：`while(true){ java.lang.Thread.sleep(2000); }` + deadline 800ms ⇒
 * **中断发生在 164680ms**（即等阻塞自己返回，不是 800ms）。
 *
 * ⇒ 本能力**不是**「脚本可中断」的无条件保证，只覆盖纯计算死循环。
 *   这条限制与 `docs/fork/xposed-architecture-v2.md` §5.7 的措辞一致。
 *
 * ## 四、线程语义
 *
 * 预算栈与 Rhino 的 Context 都是 **ThreadLocal** 的 ⇒ [beginBudget] / [endBudget]
 * **必须与 `enter` / `exit` 在同一线程**上配对。跨线程嵌套会得到不同线程的 Context，
 * 各自独立（新线程 = 新超时域），语义正确。
 */
object JsTimeoutContextFactory : ContextFactory() {

    /**
     * 每 N 条指令回调一次。
     *
     * 实测阈值 1000 / 10000 / 100000 对**中断延迟无影响**（deadline +450ms 时均 450ms 抛），
     * 取 10000 是因为它是 Rhino 的常见缺省量级、且观察器本身是热路径。
     */
    const val INSTRUCTION_OBSERVER_THRESHOLD = 10_000

    private class Budget(val deadline: Long, val timeoutMs: Long)

    private val budgets: ThreadLocal<ArrayDeque<Budget>> =
        ThreadLocal.withInitial { ArrayDeque() }

    /**
     * 每 N 条指令被回调一次（由 `Context.observeInstructionCount` 转发而来）。
     *
     * ⚠️ 这是**热路径**：不得做 I/O、不得记日志。它将来会被 system_server 侧复用。
     *
     * ⚠️ 父类里是 `protected`，这里放宽成 `public` —— Kotlin 的 `object` 不允许
     *    `protected` 成员（standalone object 无子类），放宽可见性是唯一写法，
     *    且不影响 Rhino 的虚方法分发（JVM 上仍是同一个 `observeInstructionCount`）。
     */
    public override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        val stack = budgets.get()
        if (stack.isEmpty()) return
        // 取最紧的 deadline：外层与内层同时有效时，先到期的那个说了算。
        val tightest = stack.minByOrNull { it.deadline } ?: return
        if (System.currentTimeMillis() >= tightest.deadline) {
            throw JsScriptTimeoutException(tightest.timeoutMs)
        }
    }

    /**
     * 进入一次带预算的执行。**必须与 [endBudget] 在 `finally` 里配对**。
     *
     * ⚠️ 无论 [timeoutMs] 是否为 null，**都会入栈**（null 记作永不到期）——
     * 否则 `endBudget` 会弹掉外层的预算，造成错配。
     *
     * 阈值只在此处按需开启：栈中存在活跃预算且当前阈值为 0 时才设，
     * 避免内层覆盖外层已经开启的观察器。
     */
    fun beginBudget(context: Context, timeoutMs: Long?) {
        budgets.get().addLast(newBudget(timeoutMs))
        if (hasActiveBudget() && context.instructionObserverThreshold == 0) {
            context.setInstructionObserverThreshold(INSTRUCTION_OBSERVER_THRESHOLD)
        }
    }

    /**
     * 退出一次带预算的执行。**必须与 [beginBudget] 配对**。
     *
     * 阈值只在**栈中不再有活跃预算**时才复位为 0 —— 若内层退出时无条件复位，
     * 外层的剩余部分就会失去观察器（实测复现过这个失败模式）。
     */
    fun endBudget(context: Context) {
        budgets.get().removeLastOrNull()
        if (!hasActiveBudget()) {
            context.setInstructionObserverThreshold(0)
        }
    }

    /**
     * 清空当前线程的预算栈。
     *
     * ⚠️ **仅供测试清理使用**，生产代码不得调用 —— 生产路径由
     * [beginBudget] / [endBudget] 配对维护。
     */
    internal fun clearBudgetsForTest() {
        budgets.get().clear()
    }

    /** 栈中是否存在「会到期」的预算。 */
    private fun hasActiveBudget(): Boolean =
        budgets.get().any { it.deadline != NEVER_DEADLINE }

    private fun newBudget(timeoutMs: Long?): Budget {
        if (timeoutMs == null || timeoutMs <= 0) return Budget(NEVER_DEADLINE, 0L)
        val now = System.currentTimeMillis()
        // saturating add：避免极大 timeoutMs 溢出成负数（那会变成「立即超时」）。
        val deadline = if (timeoutMs > NEVER_DEADLINE - now) NEVER_DEADLINE else now + timeoutMs
        return Budget(deadline, timeoutMs)
    }
}

/**
 * 「永不到期」的 deadline。
 *
 * ⚠️ **必须声明在文件顶层**（不能放进 `object` 内部再被 `Budget`/`newBudget` 引用）——
 * `const val` 在 object 内部对同 object 的**非嵌套**成员是可见的，但把它放在顶层更稳妥，
 * 也避免与 `object` 的初始化顺序纠缠。
 */
private const val NEVER_DEADLINE = Long.MAX_VALUE
