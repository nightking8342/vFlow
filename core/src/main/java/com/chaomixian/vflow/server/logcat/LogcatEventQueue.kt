package com.chaomixian.vflow.server.logcat

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.atomic.AtomicLong

/**
 * logcat 命中事件的**有界缓冲队列**。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §6.5.4。
 *
 * ## 为什么需要它
 *
 * 原实现是"泵线程读到命中就地 `writer.println`"。这条链的隐患：
 *
 * ```
 * App 消费慢 → socket 发送缓冲满 → println 阻塞
 *   → 泵线程停止读 logcat stdout
 *   → 管道缓冲满 → **logcat 自己丢日志**
 * ```
 *
 * 丢日志由内核决定，且**完全静默** —— 用户配的触发器没触发，
 * 却没有任何地方告诉他"丢过多少"。
 *
 * 插入有界队列后，读与写解耦，丢弃由本类按明确策略处理并计数。
 *
 * ## ⚠️ 满时绝不能阻塞
 *
 * [offer] 跑在泵线程上。一旦阻塞就会停止读 stdout → 管道满 → 内核丢日志，
 * 那一刻本类就失去意义了。所以必须用非阻塞的 [ArrayBlockingQueue.offer]。
 *
 * ## 丢弃策略：丢**最新**
 *
 * | 策略 | 效果 |
 * |---|---|
 * | 丢队首（FIFO 淘汰） | 保留最新事件，但**顺序错乱**，时间戳跳跃 |
 * | **丢最新**（本类） | 保留一段**连续**的早期事件，之后整段丢失 |
 *
 * 后者更容易理解：用户看到的是"某段时间之后就没有触发了"，
 * 而不是"触发记录中间莫名缺了几条"。日志本来就是时间序的，
 * 连续缺失比随机缺失好判断。
 *
 * 抽成独立类而非内嵌在 wrapper 里，是为了**可单测** ——
 * 丢弃策略写错了不会报错，只会让用户看到空白的触发记录。
 */
class LogcatEventQueue(capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        /** 默认容量。足以吸收正常突发（一次异常刷几十行），又不至于积压过久。 */
        const val DEFAULT_CAPACITY = 1000
    }

    private val queue = ArrayBlockingQueue<String>(capacity.coerceAtLeast(1))

    private val dropped = AtomicLong(0)
    private val accepted = AtomicLong(0)

    /**
     * 入队。**不阻塞**。
     *
     * @return true = 已入队；false = 队列满，该事件已被丢弃并计数
     */
    fun offer(payload: String): Boolean {
        val ok = queue.offer(payload)
        if (ok) accepted.incrementAndGet() else dropped.incrementAndGet()
        return ok
    }

    /**
     * 取出一条；队列空时阻塞至多 [timeoutMs]。
     *
     * @return 事件；超时或中断返回 null
     */
    fun poll(timeoutMs: Long): String? =
        try {
            queue.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            null
        }

    /** 取出并清零丢弃计数。**只在上报时调用** —— 读到多少就报多少，不重复报。 */
    fun drainDropped(): Long = dropped.getAndSet(0)

    /** 仅查看当前丢弃计数，不清零。 */
    fun peekDropped(): Long = dropped.get()

    /** 累计入队成功数。 */
    fun acceptedCount(): Long = accepted.get()

    /** 当前积压量。用于界面/诊断判断是否在过载。 */
    fun size(): Int = queue.size

    /**
     * 丢弃队列里积压的事件（换代时调用，避免旧事件串到下一次流）。
     *
     * ⚠️ **本方法故意不清 `dropped` / `accepted` 计数。**
     *
     * 清队列的语义是"不要再发了"，不是"假装没丢过" ——
     * 丢弃数是用来告诉用户「丢过 N 条」的，必须留到 [drainDropped] 上报为止。
     * 在这里清零会让那次上报永远发不出去，用户就再也知道不了丢过日志。
     *
     * （这是原有设计，且 [LogcatEventQueueTest] 有测试锁住。
     * 我曾试图让本方法连计数一起清 —— 那是错的，已回退。）
     *
     * @return 本次清掉的积压条数，便于调用方决定是否需要提示
     */
    fun clear(): Int {
        val n = queue.size
        queue.clear()
        return n
    }

    /**
     * 重置**全部**计数与队列 —— 仅在"确认上一代的丢弃数已经上报完毕"后调用。
     *
     * ⚠️ 与 [clear] 的区别：那个只丢事件、保留计数；这个连计数一起清。
     * 换代时**不要**直接用它 —— 先把 [drainDropped] 的结果上报出去，再调本方法。
     */
    fun resetAll() {
        queue.clear()
        dropped.set(0)
        accepted.set(0)
    }
}
