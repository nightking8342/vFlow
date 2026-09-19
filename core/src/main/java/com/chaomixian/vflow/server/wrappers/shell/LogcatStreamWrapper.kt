package com.chaomixian.vflow.server.wrappers.shell

import com.chaomixian.vflow.server.logcat.LogcatCondition
import com.chaomixian.vflow.server.logcat.LogcatEventQueue
import com.chaomixian.vflow.server.logcat.LogcatConditionCodec
import com.chaomixian.vflow.server.logcat.LogcatLineParser
import com.chaomixian.vflow.server.logcat.LogcatMatcher
import com.chaomixian.vflow.server.wrappers.ServiceWrapper
import com.chaomixian.vflow.server.wrappers.StreamingWrapper
import org.json.JSONObject
import java.io.BufferedReader
import java.io.PrintWriter
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread

/**
 * logcat 触发器的 Core 侧实现。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §6。
 *
 * ## 数据流
 *
 * ```
 * /system/bin/logcat -v threadtime -T 1     ← 长驻，命令行不带任何过滤参数
 *        ↓ stdout（独立线程阻塞读）
 *   解析 → 逐条件匹配
 *        ├─ 命中 → JSON → writer            ← 跨进程
 *        └─ 未命中 → 就地丢弃                ← 不跨进程 ★ 性能关键
 * ```
 *
 * ## 为什么过滤不下推到命令行（§5.3）
 *
 * `equals` 之外的匹配方式（contains / regex）**无法翻译**成 logcat 的 TAG 参数，
 * 强行下推会造成**静默的假阴性**——用户配了条件、日志打了、触发器不响，
 * 且没有任何提示。这是最坏的失败模式，所以宁可在 Core 里全量判。
 *
 * 实测代价可忽略：基准（`LogcatTriggerBenchmarkTest`，本机 JVM 20 万行）
 * 端到端 116~168 ns/行，10k 行/秒 占单核 0.1~0.2%。
 *
 * ## 线程模型（关键）
 *
 * 两个输入源必须并行等待，单线程做不到（读一个会阻塞另一个）：
 *
 * | 线程 | 职责 | 阻塞在 |
 * |---|---|---|
 * | 泵线程（独立） | 读 logcat stdout、解析、匹配、推送 | `reader.readLine()` |
 * | 主线程 | 读 App 的控制帧 | 控制帧的 `readLine()` |
 *
 * 条件列表被两个线程读写 → 用 [AtomicReference] 整体替换（§6.5.2）。
 */
class LogcatStreamWrapper :
    ServiceWrapper(SERVICE_NAME, STUB_CLASS_NAME),
    StreamingWrapper {

    companion object {
        /**
         * ⚠️ logcat **不包装任何系统服务**，因此这两个名字是占位。
         *
         * 这样做的依据（文档 §6.3 方案 A）：`ServiceWrapper.connect()` 在
         * `getService` 返回 null 时是**提前 return**，只打一行 stderr，不抛异常。
         * 因此继承它几乎没有副作用，而 `BaseWorker` 的流式路由只认
         * `serviceWrappers` 这张表，不继承就接不进来。
         *
         * 代价是启动时多一行 `⚠️ Service unavailable: logcat` 的输出——
         * 这是有意的取舍，比改 `BaseWorker` 的表结构（上游文件）划算。
         */
        private const val SERVICE_NAME = "logcat"
        private const val STUB_CLASS_NAME = "android.os.ILogcatStub\$DoesNotExist"

        /** 订阅方法名。 */
        const val METHOD_SUBSCRIBE = "subscribeLogcatStream"

        /** 上行控制帧：全量替换条件列表。 */
        const val METHOD_UPDATE_TRIGGERS = "updateTriggers"

        /** logcat 绝对路径：app_process 环境下 `PATH` 不确定（§6.6）。 */
        private const val LOGCAT_BIN = "/system/bin/logcat"

        /** 退避重启的等待时长。 */
        private const val RESTART_DELAY_MS = 1_000L

        /** 条件列表在帧里的字段名。**必须与 app 侧 LogcatConditionWire 一致**。 */
        private const val KEY_CONDITIONS = "conditions"

        /**
         * 待发送事件队列的容量。
         *
         * ## 为什么需要它
         *
         * 原实现是"泵线程读到命中就地 `writer.println`"。这条链的隐患是：
         *
         * ```
         * App 消费慢 → socket 发送缓冲满 → println 阻塞
         *   → 泵线程停止读 logcat stdout
         *   → 管道缓冲满 → **logcat 自己丢日志**
         * ```
         *
         * 也就是说：**丢日志这件事由内核替我们决定了，而且完全静默** ——
         * 用户配的触发器没触发，却没有任何地方告诉他"丢过多少"。
         *
         * 插入有界队列后，读与写解耦：写慢了只会让**我们的**队列积压，
         * 而丢弃由我们按明确策略处理并计数，可上报给 App。
         *
         * ## 深度取舍
         *
         * 1000 条在内存里很小，但足以吸收正常的突发（一次异常刷几十行）。
         * 再深无益：积压越多，用户看到的事件越滞后，而"迟到的触发"比"丢了"
         * 更难理解。
         */
        private const val QUEUE_CAPACITY = 1000

        /** 统计上报的间隔（毫秒）。太频繁会自己成为噪音。 */
        private const val STATS_INTERVAL_MS = 5_000L
    }

    /** 当前条件列表。**整体替换**，不做部分更新（§6.5.2）。 */
    private val conditions = AtomicReference<List<LogcatCondition>>(emptyList())

    @Volatile
    private var running = false

    private var pumpThread: Thread? = null
    private var writeThread: Thread? = null
    private var logcatProcess: Process? = null

    /**
     * 待发送事件的有界队列。丢弃策略与计数都在它内部（独立类，有单测）。
     */
    private var queue: LogcatEventQueue? = null

    /** 已成功发出的事件数。用于让用户看到"实际推了多少"。 */
    private val sentCount = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * 不包装任何系统服务，因此**没有"连接成功"这回事**。
     *
     * 这是文档 §6.3 方案 A 的必然结果：`ServiceWrapper.connect()` 里
     * `getService("logcat")` 返回 null → 提前 return → 本方法**永远不会被调用**。
     * 实现成空的是诚实的，不要在这里放初始化逻辑。
     */
    override fun onServiceConnected(service: Any) {
        // 不会发生。logcat 不是系统服务
    }

    /**
     * 非流式的普通请求。
     *
     * logcat 触发器只有流式用法（长驻 logcat 进程），因此不提供请求-响应式的接口。
     * 返回明确的错误而不是空成功——静默的空响应会让调用方以为"执行了但没结果"。
     */
    override fun handle(method: String, params: JSONObject): JSONObject =
        JSONObject()
            .put("success", false)
            .put("error", "logcat 只支持流式订阅，请用 $METHOD_SUBSCRIBE（method=$method）")

    override fun handleStream(
        method: String,
        params: JSONObject,
        writer: PrintWriter,
        reader: BufferedReader,
    ): Boolean {
        if (method != METHOD_SUBSCRIBE) return false

        // 首帧就带全量条件（§6.5.2 改动 1）——这样 Core 不必等第二次通信才开始工作，
        // 也让"App 重连即重发完整条件"成为天然正确的语义（§6.5.3）
        conditions.set(LogcatConditionCodec.decode(params.optJSONArray(KEY_CONDITIONS)))

        // 告知 App 已就绪，便于它区分"订阅成功"与"连接失败"
        writer.println(JSONObject().put("success", true).put("event", "ready").toString())
        if (writer.checkError()) return true

        running = true
        startPump(writer)

        try {
            // 主线程留给控制帧。这里阻塞是**必要的**——它同时充当断流检测：
            // App 一断开，readLine 立刻返回 null
            controlLoop(reader, writer)
        } finally {
            stopPump()
        }

        return true
    }

    /**
     * 控制帧循环。
     *
     * ⚠️ 它同时是**断流检测**：`checkError()` 只有发生写才置位，
     * 日志稀少时 App 断开了 Core 完全无感（§6.5.4），
     * 而这里一旦 `readLine()` 返回 null 就知道该收工了。
     */
    private fun controlLoop(reader: BufferedReader, writer: PrintWriter) {
        while (running) {
            val line = try {
                reader.readLine()
            } catch (e: Exception) {
                // socket 被关闭属正常收尾，不当错误
                null
            } ?: break

            if (line.isBlank()) continue

            try {
                val frame = JSONObject(line)
                when (frame.optString("method")) {
                    METHOD_UPDATE_TRIGGERS -> {
                        val next = LogcatConditionCodec.decode(frame.optJSONArray(KEY_CONDITIONS))
                        // 原子整体替换 —— 泵线程可能正在用旧列表匹配，不能原地改
                        conditions.set(next)

                        // §7.1：空列表意味着"没有触发器了"，应停掉 logcat 进程省资源，
                        // 而不是让它空转
                        if (next.isEmpty()) {
                            stopPump()
                        } else if (pumpThread?.isAlive != true) {
                            startPump(writer)
                        }

                        writer.println(
                            JSONObject()
                                .put("success", true)
                                .put("event", "triggersUpdated")
                                .put("count", next.size)
                                .toString()
                        )
                        if (writer.checkError()) break
                    }
                }
            } catch (e: Exception) {
                // 单条控制帧解析失败不该终止整条流
                System.err.println("[logcat] 控制帧解析失败: ${e.message}")
            }
        }
    }

    // ── logcat 泵 ────────────────────────────────────────────────

    private fun startPump(writer: PrintWriter) {
        if (pumpThread?.isAlive == true) return

        // 队列与写线程必须一起起 —— 泵线程只管入队，不碰 socket
        if (queue == null) queue = LogcatEventQueue(QUEUE_CAPACITY)
        startWriter(writer)

        pumpThread = thread(name = "vflow-logcat-pump", isDaemon = true) {
            // 照 VoiceTriggerHandler 的重试模式（文档 §6.4）：
            // logcat 意外退出时重启，而不是让整条流就此死掉
            while (running) {
                try {
                    runLogcat(writer)
                } catch (e: Exception) {
                    if (running) {
                        System.err.println("[logcat] 泵异常退出，将重启: ${e.message}")
                    }
                }
                if (!running) break

                // ⚠️ 退避前先确认进程真的结束了。
                // 固定 delay 后重启会在"进程还在退出中"时撞上端口/资源冲突，
                // 陷入"启动→失败→等待→再失败"（文档 §6.4 借鉴 Tasker 的做法）
                Thread.sleep(RESTART_DELAY_MS)
            }
        }
    }

    private fun stopPump() {
        running = false
        // destroy 会让阻塞在 stdout 上的 readLine 返回 null，泵线程据此退出
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        pumpThread = null
        // 写线程靠 running 标志与队列中断退出；唤醒它以免卡在 take() 上
        writeThread?.interrupt()
        writeThread = null
    }

    // ── 队列与写线程 ─────────────────────────────────────────────

    /**
     * 入队一个待发送事件。
     *
     * ## 丢弃策略：丢**最新**的
     *
     * 队列满时丢掉刚到的这条，而不是队首那条。理由：
     *
     * | 策略 | 效果 |
     * |---|---|
     * | 丢队首（FIFO 淘汰） | 保留最新事件，但**顺序错乱**——用户看到的事件时间戳会跳跃 |
     * | **丢最新**（本实现） | 保留一段**连续**的早期事件，之后的事件整段丢失 |
     *
     * 后者更容易理解：用户看到的是"某段时间之后就没有触发了"，
     * 而不是"触发记录中间莫名缺了几条"。日志本来就是时间序的，
     * 连续缺失比随机缺失好判断得多。
     *
     * ⚠️ **满时不能阻塞**：这个方法运行在泵线程上，一旦阻塞就会停止读
     * logcat stdout → 管道满 → 内核丢日志。那样丢弃就重新变成不可控的了。
     */
    private fun enqueue(payload: String) {
        // 丢弃策略与计数都在 LogcatEventQueue 内部（见其类注释）
        queue?.offer(payload)
    }

    /**
     * 写线程：从队列取事件发往 socket。
     *
     * 与泵线程分离，这样**写慢不会反压到读**。
     * 唯一的耦合点是队列，而队列满的处理是明确的丢弃 + 计数。
     */
    private fun startWriter(writer: PrintWriter) {
        if (writeThread?.isAlive == true) return

        writeThread = thread(name = "vflow-logcat-writer", isDaemon = true) {
            var lastStatsAt = System.currentTimeMillis()

            while (running) {
                // 带超时地取，这样既能及时响应停止，又能定期上报统计
                val payload = queue?.poll(STATS_INTERVAL_MS)

                if (payload != null) {
                    writer.println(payload)
                    if (writer.checkError()) break
                    sentCount.incrementAndGet()
                }

                // 定期上报丢弃统计。
                // ⚠️ 这是本层存在的**核心价值**：原实现丢日志由内核决定且完全静默，
                // 用户只会看到"触发器没反应"。现在至少能知道"丢过 N 条"
                val now = System.currentTimeMillis()
                if (now - lastStatsAt >= STATS_INTERVAL_MS) {
                    lastStatsAt = now
                    reportStats(writer)
                }
            }

            // 退出前再报一次，免得最后的丢弃数没送到
            reportStats(writer)
        }
    }

    /**
     * 上报累计统计。
     *
     * **只在有丢弃时上报** —— 没丢过就报数字只会成为噪音，
     * 而"一切都好"是用户的默认预期，不需要反复确认。
     */
    private fun reportStats(writer: PrintWriter) {
        val dropped = queue?.drainDropped() ?: 0
        if (dropped <= 0) return

        writer.println(
            JSONObject()
                .put("success", true)
                .put("event", "logcatOverflow")
                .put("dropped", dropped)
                .put("sent", sentCount.get())
                .toString()
        )
    }

    private fun runLogcat(writer: PrintWriter) {
        val process = ProcessBuilder(
            LOGCAT_BIN, "-v", "threadtime",
            // ⚠️ `-T 1` 而不是 `logcat -c`（文档 §6.4）：
            // 从最近一行开始，避免把缓冲区里的历史日志一次性灌进来造成连环触发；
            // `-c` 虽然也有效，但它清的是**全局**缓冲区，
            // 会连带清掉用户正在调试工具里看的内容
            "-T", "1",
        )
            .redirectErrorStream(true)   // stderr 也读掉，避免管道缓冲被写满而卡住 logcat
            .start()

        logcatProcess = process

        try {
            process.inputStream.bufferedReader().use { reader ->
                val parser = LogcatLineParser()
                while (running) {
                    val line = reader.readLine() ?: break

                    val parsed = parser.parse(line) ?: continue

                    val current = conditions.get()
                    if (current.isEmpty()) continue

                    // 逐条件判命中。未命中就地丢弃 —— 不跨进程 ★
                    val hits = LogcatMatcher.match(current, parsed)
                    if (hits.isEmpty()) continue

                    for (condition in hits) {
                        val payload = JSONObject()
                            .put("success", true)
                            .put("event", "logcatMatch")
                            .put("triggerId", condition.triggerId)
                            .put("tag", parsed.tag)
                            .put("message", parsed.message)
                            .put("level", parsed.level.toString())
                            .put("pid", parsed.pid)
                            .put("raw", line)
                            .toString()

                        enqueue(payload)
                    }
                }
            }
        } finally {
            runCatching { process.destroy() }
        }
    }

}
