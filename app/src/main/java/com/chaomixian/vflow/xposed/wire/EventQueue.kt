package com.chaomixian.vflow.xposed.wire

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * hook 层的**有界事件缓冲队列**。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.4。
 *
 * ## 为什么必须在框架层统一（而不是让每个适配器自己决定）
 *
 * 每个 source 都手写「满了怎么办」，必然出现三种坏形态：
 * 「有的阻塞（**拖垮 system_server**）、有的无限缓冲（OOM）、有的静默丢（用户不知道）」。
 * 所以入队 / 丢弃 / 计数一律收在这里，适配器只能调 [offer]。
 *
 * ## ⚠️ [offer] 跑在 hook 回调线程上 —— 绝不能阻塞
 *
 * 那是 **system_server 的主线程或 Binder 线程**。一旦阻塞，
 * 被 hook 的原方法就卡住 ⇒ 整机卡顿甚至软重启（§5.1 的「崩溃半径 = 整机」）。
 * 所以用非阻塞的 `ArrayBlockingQueue.offer`，满了就丢。
 *
 * ## 丢弃策略：丢**最新**
 *
 * | 策略 | 效果 |
 * |---|---|
 * | 丢队首（FIFO 淘汰） | 保留最新事件，但**顺序错乱**、时间戳跳跃 |
 * | **丢最新**（本类） | 保留一段**连续**的早期事件，之后整段丢失 |
 *
 * 后者更容易理解：用户看到的是「某段时间之后就没触发了」，
 * 而不是「记录中间莫名缺了几条」。
 *
 * ## ⚠️ 语义照抄 `LogcatEventQueue`（`core/.../server/logcat/`）
 *
 * 尤其是 [clear] **故意不清 [dropped] 计数** 这条 —— 丢弃数是用户知道
 * 「丢过事件」的唯一途径，留到 [drainDropped] 上报为止。
 * 本仓库在 `LogcatEventQueue` 上有测试专门锁这个语义。
 */
class EventQueue(capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /**
         * 默认容量。与 `LogcatEventQueue` 同理：足以吸收正常突发，
         * 又不至于让积压久到「事件反映的是几秒前的状态」。
         */
        const val DEFAULT_CAPACITY = 256
    }

    private val queue = ArrayBlockingQueue<String>(capacity.coerceAtLeast(1))

    private val dropped = AtomicLong(0)
    private val accepted = AtomicLong(0)

    /**
     * 入队。**不阻塞**。
     *
     * @return true = 已入队；false = 队列满，该事件已被丢弃并计数
     */
    fun offer(envelopeJson: String): Boolean {
        val ok = queue.offer(envelopeJson)
        if (ok) accepted.incrementAndGet() else dropped.incrementAndGet()
        return ok
    }

    /** 取出一条；队列空时阻塞至多 [timeoutMs]。 */
    fun poll(timeoutMs: Long): String? =
        try {
            queue.poll(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            null
        }

    /** 取出并清零丢弃计数。**只在上报时调用** —— 读到多少报多少，不重复报。 */
    fun drainDropped(): Long = dropped.getAndSet(0)

    /**
     * 上报完成后，只扣掉**实际上报出去的那部分**。
     *
     * ## ⚠️⚠️ 为什么不能用 [drainDropped] 收尾（这是最容易写错的一处）
     *
     * [HookRuntime.emit] 跑在**hook 回调线程**，`drainLoop` 跑在**发送线程**。
     * 发送路径是「装信封时 `peekDropped()` → 发送 → 成功后清零」：
     *
     * ```
     * emit:  peek 得 5，装进信封（这条要发出去）
     *        ↓ ← ⚠️ 这个窗口里又丢了 2 条（值变成 7）
     * drain: drainDropped() ⇒ 读走 7、清零 ⇒ 只报了 5，那 2 条【永久丢账】
     * ```
     *
     * 而那 2 条**正是本类存在的意义** —— 「丢过多少」是用户唯一的知情途径。
     * ⇒ 故按「已上报的值」**精确扣除**：清了 5 就只减 5，新出现的 2 留给下一条信封。
     *
     * @param reported 本次已随信封上报出去的丢弃数（即装信封时的 `peekDropped()`）
     * @return 扣除后仍积压、尚未上报的丢弃数
     */
    fun drainDroppedAtMost(reported: Long): Long {
        if (reported <= 0) return dropped.get()
        // ⚠️ 用 accumulateAndGet 在**同一个原子操作里**钳住**存储的值** ——
        // 不能写成 `addAndGet(-reported).coerceAtLeast(0)`：
        // 那样 coerce 只作用在**返回值**上，计数器本身已经被扣成负数了
        // （本方法的单测抓到了这一点：peekDropped() 会返回 -4，
        //   UI 上就是「已丢弃 -4 条」）。
        return dropped.accumulateAndGet(-reported) { cur, delta ->
            (cur + delta).coerceAtLeast(0)
        }
    }

    fun peekDropped(): Long = dropped.get()

    fun acceptedCount(): Long = accepted.get()

    fun size(): Int = queue.size

    /**
     * 丢弃积压（换代 / 重新连接时调用）。
     *
     * ⚠️ **故意不清 [dropped] / [accepted] 计数** —— 见类注释。
     * 需要连计数一起清时用 [resetAll]，且必须先 [drainDropped] 上报过。
     *
     * @return 本次清掉的积压条数
     */
    fun clear(): Int {
        val n = queue.size
        queue.clear()
        return n
    }

    /** 重置全部计数与队列。换代时**不要直接用它** —— 先上报 [drainDropped]。 */
    fun resetAll() {
        queue.clear()
        dropped.set(0)
        accepted.set(0)
    }
}
