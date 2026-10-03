package com.chaomixian.vflow.xposed.script

import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory

/**
 * 脚本执行的 **Rhino 指令级沙箱**（超时中断）。
 *
 * 方案：`.mindfs/tasks/plan-6.md` §3.2。
 * 上位文档：`docs/fork/xposed-executor-design.md` §4.4（线程与阻塞）。
 *
 * ## 职责
 *
 * `ContextFactory` 的子类，重写 [observeInstructionCount] 作为**超时挂载点**。
 * Rhino 每隔「N 条字节码指令」回调一次本方法 ⇒ 在回调里比对 `deadline`，
 * 超了就抛异常中断执行。
 *
 * ## ⚠️ 为什么是「分段续期」而不是「固定大阈值」
 *
 * [Context.setInstructionObserverThreshold] 的语义是「每 N 条指令回调一次」。
 * 若只在开头设一次 N，回调在「N 条指令之后」**触发一次就结束** ——
 * 若那时尚未超时，此后**再无任何回调**，超时永远不会命中。
 *
 * ⇒ 在**每次回调里重设阈值**（[step]），使回调成为**周期性心跳**。
 * 实测（方案 §1 的 E4）：预算 300 / 800 / 1500ms ⇒ 实际中断 299 / 800 / 1500ms，
 * 精确命在预算上。
 *
 * ## ⚠️⚠️ 但对 **Rhino 1.9.0** 而言，那行续期**实测是 no-op**（本任务订正）
 *
 * 本机实测（同一段 `while(true){}`，预算 400ms / 1000ms）：
 *
 * | | 中断耗时 | 观察器回调次数 |
 * |---|---|---|
 * | **有**续期行（预算 1000ms） | 999ms | 382,071 |
 * | **无**续期行（预算 1000ms） | 1001ms | 366,734 |
 *
 * 回调次数**同一个量级** ⇒ 说明 Rhino 1.9 的`Interpreter` **自己就会把计数器
 * 重置回阈值**（`setInstructionObserverThreshold` 会让 `generateObserverCount`
 * 为真，之后每个循环回边都检查）⇒ **我们那行重设是多余的**。
 *
 * 方案 §6.3 的反证表里写着「去掉续期那行 ⇒ `ScriptSandboxTest` 的死循环用例变红」——
 * **本机复现不出来**（那行删掉后用例照绿）。
 *
 * ### 那为什么还留着
 *
 * **版本防御**：这个「Rhino 自己重置」是**当前实现的行为**，不是 API 契约。
 * 换一个 Rhino 版本（或某个优化路径）若不重置，缺了这行的后果是
 * **超时彻底失效**（死循环永远跑下去）—— 而那是本 capability 最不能出的故障。
 * 保留它的成本是**每条回调一次 setter 调用**（实测回调频率下可忽略）。
 *
 * ⇒ 结论：**保留，但如实承认它不可被测试保护**。`ScriptSandboxTest` 里那条
 * 死循环用例锁的是「超时机制生效」，**不是**「续期生效」——不要把它当成后者的证据。
 *
 * ## ⚠️ 观察器抛出的异常**拦不住**（这是好事，是安全声明的一部分）
 *
 * 实测（方案 §1 的 E6）：脚本**无法**用 `try/catch` 规避超时。四种写法
 *（`while(true){}` / `while(true){try{}catch(e){}}` / 外层 `try` / catch 后再死循环）
 * 全部在预算处被中断 —— 因为中断是**解释器内部**抛出的，JS 层的 `catch` 捕获不到。
 *
 * ## ⚠️⚠️ 已知限制：**一次阻塞的 Java 调用不可中断**
 *
 * 指令观察器只在「执行下一条**字节码指令**」时触发；脚本里的
 * `java.lang.Thread.sleep(99999)`、卡住的 IPC、或任何原生的阻塞调用
 * **根本不给观察器机会**。
 *
 * 实测（方案 §1 的 E7）：预算 600ms 的脚本调 `Thread.sleep(3000)`
 * 会**跑满 3040ms** 才返回，然后才被第 ② 层（框架的事后判定）判为超时。
 *
 * 后果：**该执行线程被占满整个阻塞时长**。
 * ⚠️ 2026-10-03 起执行器是**三档**（`default` / `io` / `ui`，见
 * [com.chaomixian.vflow.xposed.capabilities.HookCapabilityRuntime]）——
 * 阻塞型脚本选 `io` 档（`Dispatchers.IO`，最多 64 并发）比选 `ui` 档（单线程）安全得多。
 * 这是「总时长上限」的设计**主动接受**的代价，**不是缺陷**。
 *
 * ## ⚠️ 实测结论 E1：本类**不会**污染 system_server 的进程级全局工厂
 *
 * 实测：`ContextFactory()` 的构造**不调用** `initGlobal()` ——
 * 构造前后 `ContextFactory.getGlobal()` 是**同一实例**、`hasExplicitGlobal() == false`。
 * ⇒ 可以放心在 system_server 里实例化本类，不影响其他组件。
 *
 * @param timeoutMs 超时**预算时长**（毫秒）。**`null` = 不超时**（2026-10-02 改，
 *   与 `JsExecutor` 的 `null` 语义一致）。⚠️ 与 [deadlineMs] 别混：
 *   前者用于**用户可见的文案**，后者只用于比较。
 * @param step 指令观察器的续期间隔（字节码指令条数）。取小值让心跳更密、
 *   中断更精准，代价是回调更频繁。1000 是实测精确的取值。
 */
class ScriptSandbox(
    private val timeoutMs: Long?,
    private val step: Int = DEFAULT_STEP,
) : ContextFactory() {

    /**
     * 超时的**绝对时刻**。观察器每次触发都拿它与 `System.currentTimeMillis()` 比。
     *
     * ⚠️ 构造时先按当前时刻算一次（保证「构造即可用」的直觉语义 ——
     * 万一有人拿了沙箱却忘了 `arm()`，超时仍会生效，只是起点偏早），
     * 但**真正的起点由 [arm] 决定** —— 见那个方法的说明。
     *
     * ## ⚠️⚠️ 为什么必须是 `var` 而不是 `val`（方案 §3.2 与步骤 5 在这里矛盾）
     *
     * 方案 §3.2 写的是 `private val deadlineMs = …`，并在同一段里补了一句
     * 「构造应尽量**贴近** `evaluateString`」；而步骤 5 的执行流程写的是
     * `ScriptSandbox(deadlineMs = System.currentTimeMillis() + budgetMs)`。
     *
     * 两者**同时成立不了**：
     *
     * - 若照 §3.2 的形态（`val` + 「贴近」），则「构造与 `evaluateString` 之间的耗时」
     *   必然被算进脚本预算 ⇒ **实现越正确、离得越近，偏差越小**，但那是个**靠自觉**的约束 ——
     *   而失败形态是**静默**的（用户看到「我的脚本明明没超时」）。
     * - 更糟的是步骤 5 那个字面写法：把**绝对时刻**当 `deadlineMs` 参数传进去，
     *   若实现真按 §3.2 的签名（`构造时算 + timeoutMs`）接，就是**双重相加**
     *   ⇒ deadline 被推到遥远的将来 ⇒ **超时永远不触发**（完全静默）。
     *
     * ⇒ 本实现取**第三条路**，同时消除上面两个问题：
     * 构造参数是**时长** `timeoutMs`（与步骤 5 的调用意图一致）、
     * `deadlineMs` 在内部算**且不外露**（§3.2 的封装）、
     * 再用 [arm] 把起点**显式**钉在 `evaluateString` 之前（取代「尽量贴近」那句靠自觉的约定）。
     *
     * ✅ 由 `ScriptSandboxTest.the timeout window starts at arm not at construction` 锁住
     *（反证：把 `arm()` 改成空实现 ⇒ 该用例变红）。
     */
    @Volatile
    private var deadlineMs: Long = deadlineFrom(timeoutMs)

    /**
     * 上次 [arm] 的时刻。**不超时时不能用 `deadlineMs - timeoutMs` 反推**
     *（那个算式在 `timeoutMs == null` 时无意义），故单独记一个。
     */
    @Volatile
    private var armedAtMs: Long = System.currentTimeMillis()

    /**
     * 把「现在」设为超时计时的起点。**在 `evaluateString` 之前调用**。
     *
     * ## ⚠️ 为什么需要它（而不是「构造即可」）
     *
     * 环境装配（`ImporterTopLevel` 的构造 —— 它会初始化整套标准内建、
     * 建 `inputs` 对象、装 `console`）本身要花时间。若 deadline 在**构造时**就定下，
     * 那些时间会被算进脚本的预算里 ⇒ 实际可执行时长**短于** `timeoutMs`。
     *
     * 对「预算 5 秒、脚本跑 4.9 秒」这类**贴着预算**的脚本，这点差异足以让它
     * 从「成功」变成「超时」—— 而用户看到的是「我的脚本明明没超时」，
     * 排查方向会指向脚本本身（错的方向）。
     *
     * ⚠️ 这也让「构造脚本预算」与「脚本真正拿到多少时间」在语义上对齐：
     * `timeoutMs` 描述的是**脚本执行时长**，不是「从建沙箱起算的墙上时间」。
     *
     * ⚠️ **不要删掉它、改成「构造时就算好」** —— 那正是方案 §3.2 的写法，
     * 而它是**静默失效**的一类（脚本照跑、没人报错，只是可执行时间偷偷变短）。
     * `ScriptSandboxTest` 有一条专门的门禁守着这件事。
     */
    fun arm() {
        deadlineMs = deadlineFrom(timeoutMs)
        armedAtMs = System.currentTimeMillis()
    }

    /** 自上次 [arm] 起的耗时（毫秒）。给超时结果用的（`TimedOut.elapsedMs`）。 */
    fun elapsedSinceArm(): Long = System.currentTimeMillis() - armedAtMs

    /**
     * 是否已经因超时中断。
     *
     * ## ⚠️⚠️ 判定**不依赖异常类型**
     *
     * 实测（方案 §1 的 E10）：观察器抛出的异常在穿过 Rhino 之后可能变成
     * `java.lang.RuntimeException`（message 是观察器给的文本），而与
     * [ScriptTimeoutException] 未必是同一个类。⇒ 用一个**显式标志位**判定，
     * 异常类型只作参考。
     *
     * `@Volatile`：写在工作线程（观察器回调所在线程），读在调用方 ——
     * 本类可能被跨线程读取（超时判定与执行在同一线程，但不依赖这个前提）。
     */
    @Volatile
    var timedOut: Boolean = false
        private set

    /**
     * 观察器：**Rhino 每 `step` 条字节码指令回调一次**。
     *
     * 两件事，顺序不能反：
     * 1. 超了就置 [timedOut] 并抛（**先置标志再抛** —— 抛出去之后本方法就结束了）；
     * 2. 没超就**续期**，否则下一次永远不会被回调（见类注释）。
     */
    override fun observeInstructionCount(cx: Context, instructionCount: Int) {
        // ⚠️⚠️ 不超时 ⇒ 直接返回。**必须早退**，因为下面那句
        // `setInstructionObserverThreshold(step)` 会**开启观察器** ——
        // 在「不超时」下开着它只是白付回调开销（每次回调读一次时钟）。
        // ⚠️ 但阈值已经由 `ScriptExecutor` 设过一次（`DEFAULT_STEP`），
        // 所以这里的早退**不能**省掉回调本身，只能省掉我们的判断 —— 有意的：
        // 关掉阈值要调 `setInstructionObserverThreshold(0)`，而那会让
        // `RhinoServiceWarmUp` 之外的路径行为分叉，收益不抵复杂度。
        if (timeoutMs == null) return
        if (System.currentTimeMillis() > deadlineMs) {
            timedOut = true
            throw ScriptTimeoutException("脚本执行超时（预算 ${timeoutMs}ms）")
        }
        // ★ 分段续期。⚠️ 对 Rhino 1.9 而言它是 no-op（该版本自己会重置计数器），
        // 但**保留** —— 理由与实测数据见类注释的「版本防御」一节。
        cx.setInstructionObserverThreshold(step)
    }

    /** 第 ① 层超时的中断信号。⚠️ 判定**不**依赖它 —— 实际判定看 [timedOut]。 */
    class ScriptTimeoutException(message: String) : RuntimeException(message)

    companion object {
        /**
         * 指令观察器的默认续期间隔。
         *
         * 取 1000 的依据是实测（方案 §1 的 E4）：在 300ms–1500ms 的预算范围内，
         * 中断时刻与预算的偏差在一个回调周期之内。更大的值会让中断变粗，
         * 更小的值只是徒增回调开销（每次回调都要读一次时钟）。
         */
        const val DEFAULT_STEP = 1000

        /**
         * `timeoutMs` → deadline 绝对时刻。**`null` ⇒ `Long.MAX_VALUE`（永不超时）**。
         *
         * ⚠️ 用 `Long.MAX_VALUE` 而不是「加一个大数」：后者在
         * `System.currentTimeMillis() + big` 处会**溢出成负数** ⇒
         * 观察器判 `now > deadline` 恒真 ⇒ **立刻中断**（与意图完全相反）。
         * 也没用负数当哨兵 —— 同上，符号反了语义就反了。
         */
        internal fun deadlineFrom(timeoutMs: Long?): Long =
            timeoutMs?.let { System.currentTimeMillis() + it } ?: Long.MAX_VALUE
    }
}
