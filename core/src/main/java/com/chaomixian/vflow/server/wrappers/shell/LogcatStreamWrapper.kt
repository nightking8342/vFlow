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

    /**
     * 泵的运行标志。
     *
     * ⚠️⚠️ **它必须与"某一次具体的流"绑定，不能跨流共用。**
     *
     * ## 原先的缺陷（用户报「装包后 logcat 触发器失灵，重启或重存工作流才恢复」）
     *
     * 原实现是一个共用的 `@Volatile var running`，`stopPump()` 置 false、
     * `handleStream()` 置 true。问题出在**新流建立时旧泵还没退干净**：
     *
     * ```
     * t0  stopPump():   running = false
     * t1  新流订阅:      running = true        ← 旧泵还没观察到 false
     * t2  旧泵循环时:    while (running) → true → 【继续跑】
     * t3  两个泵同时跑，抢同一个进程与队列
     * ```
     *
     * 触发场景很具体：**App 被安装/强杀时 socket 不发 FIN**，
     * Core 的 `controlLoop` 不会立刻返回，旧泵因而不退出；
     * 新 App 起来重新订阅时，`startPump()` 看到 `pumpThread?.isAlive == true`
     * 就跳过启动 —— 而那个"还活着"的泵绑的是**已死的旧 socket**，
     * 事件全推给废弃的 writer，App 永远收不到。
     *
     * 「重存工作流」之所以能恢复，是因为它走 `removeTrigger` → `streamJob.cancel()`
     * → **App 主动关 socket** → Core 立刻收到 EOF → 旧泵真正退场。
     * 也就是说：**只有"干净断开一次"才能自愈**，这正是缺陷特征。
     *
     * 现在用 `[generation]` 做代次隔离：每次 `handleStream` 领一个新代号，
     * 泵只认自己那一代的代号。旧泵即使存活，也会在下一轮循环发现
     * "我的代号过期了"而退出，**不会再与新泵抢**。
     */
    private val generation = AtomicLong(0)

    /** 当前活跃代的运行标志；由 [generation] 与 [activeGen] 共同决定。 */
    @Volatile
    private var activeGen = -1L

    /** 某代是否仍在运行。泵线程用它判断"我这一代还算不算数"。 */
    private fun isCurrent(gen: Long): Boolean = activeGen == gen

    /**
     * 保护「换代」那几行（`stopPump` → 领代号 → `startPump`）。
     *
     * ⚠️ **不要拿它包住 `controlLoop`** —— 那个循环要阻塞到对端断开，
     * 包进来的话第二条连接会被挂死，正好毁掉本次修复的目的。
     */
    private val takeoverLock = Any()

    /**
     * 泵线程。
     *
     * ⚠️ **只写不读**：泵线程自己写，`stopPump()` 里置 null 也没有任何读取点
     * （守卫已改用代号）。保留字段只是为了诊断时能看到线程对象，
     * 不要再用它做"IsAlive → 跳过启动"这类判断 —— 那正是原缺陷。
     */
    private var pumpThread: Thread? = null

    /** 写线程。跨线程读写，故用 `@Volatile`（`startWriter` 会 interrupt 旧的）。 */
    @Volatile
    private var writeThread: Thread? = null

    /**
     * 当前代的 logcat 进程。
     *
     * ⚠️ **必须 `@Volatile`**：泵线程写它（`runLogcat` 里 `logcatProcess = process`），
     * 而**另一个线程**（新连接的 `stopPump()`）读它并 destroy。
     * 两者之间没有 happens-before 边（不是通过 Thread.start 传递的），
     * 非 volatile 时新线程可能读到 null → 旧进程不被 destroy →
     * 旧泵继续阻塞在自己的 readLine 上，把"新旧两个 logcat 并存"的窗口
     * 从微秒级拉长到"下一行日志到来"。
     *
     * ⚠️ 泵线程退出时用的是它**自己捕获的局部 process**，不是这个字段 ——
     * 所以即使换代交错，也不会误杀别的代的进程。
     */
    @Volatile
    private var logcatProcess: Process? = null

    /**
     * 待发送事件的有界队列。丢弃策略与计数都在它内部（独立类，有单测）。
     *
     * ⚠️ 跨代共享，但**每次换代都会 `clear()`**（见 `startPump`）——
     * 不清的话旧代积压的事件会由新代写线程推给 App 并误触发工作流。
     */
    @Volatile
    private var queue: LogcatEventQueue? = null

    /** 已成功发出的事件数。用于让用户看到"实际推了多少"。**换代时清零。** */
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

        // ⚠️ 换代这一段**必须互斥**：`BaseWorker` 对每条连接各起一个线程，
        // 两个订阅帧几乎同时到达时，`stopPump()` + `incrementAndGet()` +
        // `activeGen = gen` + `startPump()` 这套多步操作会交错 ——
        // 最直接的后果是 `queue` 被创建两份、丢掉一份（惰性初始化竞态）。
        //
        // 只锁这一段、**不锁整个方法**：下面的 `controlLoop` 是阻塞的
        // （要一直读控制帧直到对端断开），整体加锁会把第二条连接直接挂死。
        val gen = synchronized(takeoverLock) {
            // **先 stopPump 清掉上一代**，再发布新代号 —— 顺序不能反：
            // 否则上一代的泵可能在本代代号发布后又"复活"一轮
            stopPump()
            val g = generation.incrementAndGet()
            activeGen = g
            startPump(writer, g)
            g
        }

        try {
            // 主线程留给控制帧。这里阻塞是**必要的**——它同时充当断流检测：
            // App 一断开，readLine 立刻返回 null
            controlLoop(reader, writer, gen)
        } finally {
            // ⚠️ 只有当**自己这一代仍然活跃**时才清理。
            // 若期间已有新流接管（activeGen 变了），这次清理会误伤新流
            if (isCurrent(gen)) stopPump()
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
    private fun controlLoop(reader: BufferedReader, writer: PrintWriter, startGen: Long) {
        // ⚠️ 用 var：空条件停泵后若条件又变回非空，本连接会**重新领一个新代号**
        // 拉起泵（见下面 METHOD_UPDATE_TRIGGERS 分支）。
        // 用 val 的话这一代永远"已过期"，泵再也起不来
        var gen = startGen
        while (isCurrent(gen)) {
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
                        // 而不是让它空转。
                        // ⚠️ 这里用 stopPumpForGen 而不是 stopPump：只停**泵**，
                        // 本连接的 controlLoop 要继续跑（条件可能还会变回来）
                        if (next.isEmpty()) {
                            stopPump()
                        } else if (!isCurrent(gen)) {
                            // 上一轮空条件把泵停了 → 重新领代号拉起
                            val revived = generation.incrementAndGet()
                            activeGen = revived
                            startPump(writer, revived)
                            gen = revived
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

    /**
     * 启动泵。每次流订阅调用一次，持有**属于本次流**的 [gen]。
     *
     * ⚠️ 不再用 `pumpThread?.isAlive` 做守卫 —— 那个守卫配合共用 `running`
     * 会让"旧泵还活着"变成"新泵不启动"，而旧泵绑的是已死的 socket（见 [generation] 的说明）。
     * 现在的守卫换成了代号：**只要不是当前代，旧泵下一轮必然自杀**，
     * 而新泵总是会被创建出来。
     */
    private fun startPump(writer: PrintWriter, gen: Long) {
        // 队列与写线程必须一起起 —— 泵线程只管入队，不碰 socket
        val q = queue ?: LogcatEventQueue(QUEUE_CAPACITY).also { queue = it }

        // ⚠️⚠️ **换代必须先清空队列**，否则旧代积压的事件会由新代的写线程
        // 推给 App —— 而那些事件带着**仍然存在的 triggerId**，
        // App 侧按 id 定位成功 → 【几分钟前的旧日志触发了刚重启后的工作流】。
        //
        // 这是"新流消费旧积压"的组合，只在代次切换后成立；
        // 旧实现（共用 running + isAlive 守卫）因为旧泵永不退出，
        // 反而不存在这个组合 —— 所以它是本次代次隔离【引入】的问题。
        //
        // ⚠️ 调 `reportStats` **再** `clear()`，顺序不能反 ——
        // 上一代可能有没上报完的丢弃数，而换代这个时点是最后一次机会：
        // 它写的是**旧 writer**（还能不能写通另说，但至少尝试过）。
        // `reportStats` 内部走 `drainDropped()`（读+清零），无事可报时自身就 return，
        // 所以这里不必先判断。
        //
        // ⚠️ 用 `clear()`（只丢积压事件）而不是连计数一起清：
        // **丢弃数必须留到上报为止** —— 它是用户知道"丢过日志"的唯一途径。
        reportStats(writer)
        q.clear()

        // 发送计数是"本次会话推了多少"的展示口径，跨代累加会让用户
        // 看到一个与当前会话无关的数字，所以换代时归零
        sentCount.set(0)
        startWriter(writer, gen)

        pumpThread = thread(name = "vflow-logcat-pump", isDaemon = true) {
            // 照 VoiceTriggerHandler 的重试模式（文档 §6.4）：
            // logcat 意外退出时重启，而不是让整条流就此死掉
            while (isCurrent(gen)) {
                try {
                    runLogcat(writer, gen)
                } catch (e: Exception) {
                    if (isCurrent(gen)) {
                        System.err.println("[logcat] 泵异常退出，将重启: ${e.message}")
                    }
                }
                if (!isCurrent(gen)) break

                // ⚠️ 退避前先确认进程真的结束了。
                // 固定 delay 后重启会在"进程还在退出中"时撞上端口/资源冲突，
                // 陷入"启动→失败→等待→再失败"（文档 §6.4 借鉴 Tasker 的做法）
                Thread.sleep(RESTART_DELAY_MS)
            }
        }
    }

    /**
     * 停掉**当前代**的泵。
     *
     * ⚠️ `activeGen = -1` 而不是某个"运行中"布尔 —— 这样即使有**旧代的泵
     * 还卡在阻塞读上**，它醒来时发现 `isCurrent(旧gen)` 为假就会自行退出，
     * 不需要我们去 join 它（join 会阻塞调用线程，代价更大）。
     */
    private fun stopPump() {
        activeGen = -1L
        // destroy 会让阻塞在 stdout 上的 readLine 返回 null，泵线程据此退出
        runCatching { logcatProcess?.destroy() }
        logcatProcess = null
        pumpThread = null
        // 写线程靠代号与队列中断退出；唤醒它以免卡在 take() 上
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
    private fun startWriter(writer: PrintWriter, gen: Long) {
        // ⚠️ 与泵同源：不用 `writeThread?.isAlive` 守卫 ——
        // 旧写线程可能绑着已死的 socket 还活着，守卫会让新写线程起不来。
        //
        // ⚠️⚠️ **但不要以为 interrupt 一定收得回旧写线程。**
        // 它可能正阻塞在 `writer.println` 上：Java 的 `soTimeout` **只管读**，
        // 没有 `SO_SNDTIMEO`，`SocketOutputStream` 也不可中断 ——
        // 对端不读时（Android 杀进程前会先 cgroup freezer 冻结进程，
        // 这正是"不发 FIN"的现实来源）它会**卡到对端被回收为止**。
        //
        // 因此本方法只能保证"新写线程一定会建起来"，不能保证"旧的会立刻退场"。
        // 这类僵尸线程的根治要靠协议层心跳（见类注释的待办），
        // 现在至少做到：**它已经取走的那条事件被正确计数**，
        // 不会既没送达又不计丢弃（见下面的 sentCount 位置）。
        writeThread?.interrupt()

        writeThread = thread(name = "vflow-logcat-writer", isDaemon = true) {
            var lastStatsAt = System.currentTimeMillis()

            while (isCurrent(gen)) {
                // 带超时地取，这样既能及时响应停止，又能定期上报统计
                val payload = queue?.poll(STATS_INTERVAL_MS)

                if (payload != null) {
                    writer.println(payload)
                    if (writer.checkError()) break
                    // ⚠️ 计数放在 `checkError()` **之后** —— 它代表"这一条真的发出去了"。
                    // 放在 println 之前/中间的话，若那条卡在写缓冲里再也没送达，
                    // 用户看到的"累计推送 N 条"会偏高，而它是判断过载的唯一参照
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

            // 退出前再报一次，免得最后的丢弃数没送到。
            // ⚠️ 只在仍是当前代时才报 —— 换代时队列已被 clear() 清零，
            // 僵尸线程这次上报会把新流刚积累的丢弃数抢走并写到死 socket 上
            if (isCurrent(gen)) reportStats(writer)
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

    private fun runLogcat(writer: PrintWriter, gen: Long) {
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
                // ⚠️ 用**代号**而不是共用布尔：本代被取代时立刻停止读，
                // 不让两个泵同时消费同一个 logcat stdout
                while (isCurrent(gen)) {
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
