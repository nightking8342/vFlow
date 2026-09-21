package com.chaomixian.vflow.core.logcat

/**
 * logcat 调试工具的 shell 命令构造。**纯函数，无 Android 依赖，可单测。**
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §4.3。
 *
 * ⚠️ **本文件里每一处 `>/dev/null`、`wc -l`、pidfile 精确匹配都不是风格选择，
 * 而是真机实测踩坑后的硬约束**，改动前请先读文档 §2.1 / §2.1b / §2.4：
 *
 * 1. **Binder 传输上限**：Shizuku 的 `exec` 走 Binder Parcel，
 *    裸跑 `logcat -d`（全量）会抛 `DeadObjectException: ... running out of binder
 *    buffer space` 并**打死 UserService**。→ 一切可能返回大量数据的命令，
 *    都必须在 shell 侧限流（`-T n` / `wc -l`）。
 * 2. **后台进程必须切断 stdio**：`sh -c "... &"` 的后台进程会继承 shell 的 stdout，
 *    管道不关闭 → `ShizukuUserService.readStream` 等不到 EOF → **`exec` 永久挂起**。
 *    这个坑**不报错、只静默挂住**，比第 1 条更隐蔽。
 * 3. **进程判定必须按 pidfile 精确匹配**：`grep logcat` 会把系统/其他进程算进来；
 *    实测该指标的可见性**还受执行身份影响**（同设备 root 与 shell 两轮分别数到 3 和 1）。
 */
object LogcatCommands {

    /** logcat 可执行文件的绝对路径。app_process / sh 环境下的 `PATH` 不确定。 */
    const val LOGCAT = "/system/bin/logcat"

    /**
     * 跨进程单次传输的硬上限（行数）。
     *
     * 2000 行 ≈ 200KB，安全落在 Binder 限额内（见类注释第 1 条）。
     */
    const val MAX_LINES = 2000

    /** 默认取的行数。 */
    const val DEFAULT_LINES = 1000

    /**
     * 采集文件的轮转容量（KB/份）。
     *
     * ## 为什么是 64MB 而不是原来的 1MB
     *
     * 原值 1MB × 4 份在高频日志下只覆盖约 10 秒（实测：某 TAG 每秒刷 2800 行、
     * 每行约 140 字符 → 约 400KB/秒）。用户"开采集 5 分钟再回来查"时，
     * 早期日志早已被轮转覆盖，而**界面上没有任何提示**。
     *
     * 64MB × 4 份 = 256MB，同样速率下能覆盖约 10 分钟，
     * 足够覆盖默认的 5 分钟上限。
     *
     * ⚠️ 这只是把窗口拉长，**不能保证覆盖整个区间**——
     * 写入速率是不可预知的。所以界面必须显示「实际覆盖了什么」，
     * 覆盖不全时让用户知道（见 [buildCaptureCoverage]）。
     */
    const val ROTATE_KB = 64 * 1024

    /**
     * 保留的轮转文件数。
     *
     * ⚠️ 实际文件数可能比它多 1（实测 `-n 2` 会得到 3 个文件），
     * 所以**读的时候必须用通配符枚举**，不要假定个数。
     */
    const val ROTATE_COUNT = 4

    /**
     * 单次检索返回的行数上限。
     *
     * 与 [MAX_LINES] 分开是因为含义不同：那是"跨进程传输上限"，
     * 这是"检索结果上限"。两者当前取值相同，但将来可能分道扬镳。
     */
    const val MAX_MATCHES = 2000

    /**
     * 单次读取的**字节窗口**下限（KB）。
     *
     * ## 为什么要有字节窗口
     *
     * 原实现是 `cat 全部轮转文件 | grep | tail -n N` ——
     * **为了拿最后 N 行，把整个采集文件读了一遍**。
     * 成本是 `O(文件大小)`，与「条数」选多少**完全无关**：
     * 选 1000 和选 2000 都要把 256MB 从头扫一遍。
     *
     * 实测代价（用户日志）：
     * ```
     * 读取日志: 用时=4018ms 行数=1000
     * 读取日志: 用时=6184ms 行数=1000
     * ```
     * 读 1000 行要 4–6 秒，而且**采集越久越慢**。
     *
     * 换成 `tail -c N` 之后，读的量**只与窗口大小有关**，
     * 与文件总大小、采集时长都无关。
     *
     * ## 为什么是 16MB
     *
     * 按实测约 200 字节/行：
     * - 16MB ≈ 8 万行，够取出 2000 行（留了 40 倍余量，应对窄过滤）
     * - 同时远小于 256MB 的总量
     *
     * ⚠️ 窗口是**按字节**切的，可能从一行的中间开始。
     * 所以取到之后要做一次**整行对齐**（丢掉首行的残片），
     * 否则解析器会读到半行，表现是"偶尔有一行日志是残缺的"。
     */
    const val READ_WINDOW_KB = 16 * 1024

    /**
     * 单次读取窗口的上限（KB）。
     *
     * 窗口本身要经跨进程传输（和结果一起回来），所以不能无限大。
     * 32MB 已远超"取出 2000 行"所需的量，再大只会拖慢传输。
     */
    const val MAX_READ_WINDOW_KB = 32 * 1024

    /**
     * 「取不满就扩窗重读」的窗口阶梯（KB）。
     *
     * ## 为什么需要它
     *
     * 窗口是**按字节**切的（16MB ≈ 8 万行，按 200 字节/行估）。
     * 要取满 2000 条，命中率必须 ≥ 2000 ÷ 80000 ≈ **2.5%**。
     *
     * 命中率低于这个值时，结果会**静默地变少**：
     *
     * | 过滤条件 | 8 万行里匹配 | 选 2000 条实得 |
     * |---|---|---|
     * | 不加过滤 | 8 万 | 2000（取满）|
     * | 热门 TAG（占 10%） | 8000 | 2000（取满）|
     * | 冷门 TAG（占 0.1%） | 80 | **80** ← 看起来像"就这么多" |
     *
     * 老实现扫全部 256MB，不存在这个问题 —— 定窗读取是拿"可能少"换"快"。
     * 这个阶梯把"可能少"变成"多花一次命令换确认"。
     *
     * ## 阶梯怎么用
     *
     * 从 [READ_WINDOW_KB] 起步，每次翻倍重读，直到取满 `limit` 或到达
     * [MAX_READ_WINDOW_KB]。翻倍而不是线性增长：需要更大窗口时通常
     * 差得不止一点点（命中率低一个数量级）。
     */
    val READ_WINDOW_LADDER_KB = listOf(READ_WINDOW_KB, 32 * 1024)

    /**
     * 判断一次读取的结果是否需要扩大窗口重试。
     *
     * ⚠️ **只在"结果行数没取满"时重试**，且窗口还有放大的余地。
     *
     * 不重试的两种情况：
     * - **已取满 `limit`**：说明窗口够大，再读纯属浪费
     * - **窗口已到上限**：再读也不会有更多（此时"少"是真的少）
     *
     * @param returned 本次实际拿到的行数
     * @param limit 用户要的条数
     * @param currentWindowKb 本次用的窗口
     * @return 下一档窗口（KB）；不需要重试时返回 `null`
     */
    fun nextWindowForRetry(
        returned: Int,
        limit: Int,
        currentWindowKb: Int,
    ): Int? {
        // 取满了 → 窗口够用，不必再读
        if (returned >= limit) return null

        val idx = READ_WINDOW_LADDER_KB.indexOfFirst { it >= currentWindowKb }
        val nextIdx = if (idx < 0) 0 else idx + 1
        return READ_WINDOW_LADDER_KB.getOrNull(nextIdx)
    }

    /**
     * 采集时长上限的默认值（秒）。5 分钟——够复现绝大多数问题，且不会忘关太久。
     *
     * 用户可在界面上调整；App 侧计时用于即时反馈，shell 侧 `timeout` 是兜底
     * （见 [buildStartCapture] 的说明）。
     */
    const val DEFAULT_TIMEOUT_SEC = 5 * 60

    /** 可选的时长上限（秒），供界面下拉框使用。 */
    val TIMEOUT_CHOICES_SEC = listOf(60, 2 * 60, 5 * 60, 10 * 60, 30 * 60)

    /** 采集写入的文件。 */
    const val CAPTURE_FILE = "/sdcard/vFlow/logs/logcat_capture.log"

    /** 采集进程的 pidfile。 */
    const val PID_FILE = "/sdcard/vFlow/temp/logcat_capture.pid"

    /**
     * 全量统计用的临时文件。
     *
     * 统计要四类信息（总数 / 首末时间戳 / TAG 分布）。若各读一遍采集文件，
     * 256MB 就要读 4 遍 —— 先落到这个临时文件再统计，只读一遍。
     *
     * 放 `/data/local/tmp`（shell 可写）而不是 sdcard：
     * 那是用户可见目录，留个临时文件会让人困惑。
     */
    const val STATS_TMP_FILE = "/data/local/tmp/vflow_stats.tmp"

    /**
     * 「采集已完成」的标记文件。
     *
     * 停止采集后 pidfile 会被删掉，若只看 pidfile 的存在与否，
     * 状态会退回 [CaptureState.Idle] —— 而 Idle 的数据源是**实时滚动的缓冲区**，
     * 于是用户切个 TAG 就会看到"现在的日志"而不是刚采的那批。
     *
     * 有了这个标记，「数据源」才能由状态唯一决定：
     * 有它 = 读采集文件（已固定），没有 = 读缓冲区（滚动）。
     *
     * ⚠️ 用**文件**而非内存标志：与 pidfile 同一个理由——
     * 采集脱离 UI 存活，App 被杀后重进也该看到同一批日志（§4.2.1）。
     */
    const val DONE_FILE = "/sdcard/vFlow/temp/logcat_capture.done"

    /** 输出格式。`threadtime` 是人类可读的默认格式，见设计文档 §5.2 的取舍。 */
    private const val VERBOSITY = "-v threadtime"

    // ── 采集控制 ──────────────────────────────────────────────────

    /**
     * 开始采集。立即返回（logcat 转到后台），pid 写入 pidfile。
     *
     * `-r`/`-n` 是 **logcat 自带的轮转**（每 [rotateKb] KB 转一次、保留 [rotateCount] 份），
     * 因此文件不会无限增长，**不需要自己写清理逻辑**。
     *
     * ⚠️ `>/dev/null 2>&1 </dev/null` **不是可选的**（类注释第 2 条）。
     * 漏掉任何一个都会导致 `exec` 永久挂起。
     *
     * ⚠️ 实测注意：`-n 2` 时实际可能得到 3 个文件（与"保留 2 份"的直觉不符），
     * 所以**不要硬编码文件数**，用 `ls $CAPTURE_FILE*` 动态枚举。
     *
     * ⚠️ **`timeout` 前缀是双保险，不是可有可无的**：
     * App 侧的"时长上限"计时只在 App 活着时有效。而采集恰恰设计成
     * **脱离 UI 存活**（用户可以离开 App 去复现问题），此时若 App 被系统杀掉，
     * App 侧的计时器随之消失 → **logcat 会无限期跑下去**，
     * 也就是说"防忘记关"的机制在最容易忘记关的场景下失效。
     * 把它下推到 shell 侧后，无论 App 死活，进程到点自己结束。
     * `timeout` 是 toybox 自带的（项目内已有用例）。
     *
     * @param rotateKb     单个文件的上限（KB）
     * @param rotateCount  保留的轮转文件数
     * @param timeoutSec   采集时长上限（秒）；进程到点自动结束
     */
    fun buildStartCapture(
        rotateKb: Int = ROTATE_KB,
        rotateCount: Int = ROTATE_COUNT,
        timeoutSec: Int = DEFAULT_TIMEOUT_SEC,
    ): String =
        // ⚠️ 四件事的顺序有讲究：
        // 1. 先删 done 标记：否则新一轮刚开始时（pidfile 已写、进程还没起来），
        //    探测可能读到旧标记，把"采集中"误判成"已完成"
        // 2. **先杀干净上一轮的残留进程**（见 [killCapturedProcesses]）
        // 3. 再删上一轮的**全部轮转文件**：否则新采集只覆盖主文件，
        //    `.1` `.2` … 里还是上一批的内容，检索时会把两批混在一起
        // 4. 最后启动（**带 `-T 1`**，见下）
        //
        // ## ⚠️⚠️ `-T 1` 是**必须的**，这是"采 2 秒却得到 65 分钟"的真因
        //
        // `logcat` 不带 `-T` 时**默认从缓冲区开头 dump 全部已有日志**，
        // 然后才跟随新日志。缓冲区里往往积压了几十分钟 ——
        // 于是"采集 2 秒"的文件里装满了采集之前的历史。
        //
        // 用户实测的现象正是这个：**手动删掉文件后重新采，仍是 65 分钟** ——
        // 内容根本不来自文件，而来自 logcat 的**内存缓冲区**。
        //
        // `-T 1` = 只从最后 1 行开始，也就是"从现在起"，语义正确。
        // 触发器的流早就用的是 `-T 1`（`LogcatStreamWrapper`），
        // 查看器这条采集命令当初漏了它。
        //
        // （`-T 1` 而不是 `-T 0`：`-T 0` 在部分实现上被当作"无限制"。）
        "rm -f $DONE_FILE; " +
            killCapturedProcesses(rotateKb, rotateCount) + "; " +
            "rm -f $CAPTURE_GLOB; " +
            "timeout ${timeoutSec.coerceAtLeast(1)} " +
            captureCommandLine(rotateKb, rotateCount) + " " +
            ">/dev/null 2>&1 </dev/null & echo \$! > $PID_FILE"

    /**
     * 采集进程的 **ps 特征串**（用于查杀，不含路径前缀）。
     *
     * ## ⚠️⚠️ 为什么不能直接用 [captureCommandLine]
     *
     * **`ps -A -o PID,ARGS` 显示的 ARGS 不含 `/system/bin/` 前缀。**
     *
     * 实测（真机，Android 17）：
     * ```
     * 启动命令:  timeout 300 /system/bin/logcat -v threadtime -T 1 -r 65536 ...
     * ps 显示:   8857 logcat -v threadtime -T 1 -r 65536 -n 4 -f ...
     *                  ↑ 只有 "logcat"，路径没了
     * ```
     * 原因是 logcat 启动后会把 argv[0] 改写为 `logcat`。
     *
     * 因此用 [captureCommandLine]（以 `/system/bin/` 开头）去 grep **一个都匹配不到** ——
     * 而失效表现正是「停止采集后文件一直涨」。这个坑已经实际踩过。
     *
     * ## 特征串为什么够独特
     *
     * 去掉路径后仍带完整参数 `-T 1 -r 65536 -n 4 -f <文件>`，是本工具独有的组合：
     * - 系统自己的 logcat 不带这些参数
     * - **触发器的流**是 `logcat -v threadtime -T 1`，不含 `-r/-n/-f`，不会被误杀
     *
     * ⚠️ 本函数的返回值**必须与 [captureCommandLine] 的 `logcat` 之后部分逐字一致** ——
     * 有测试锁住这一点（`the kill pattern matches what ps actually shows`）。
     */
    internal fun capturePatternForPs(rotateKb: Int, rotateCount: Int): String =
        "logcat $VERBOSITY -T 1 -r $rotateKb -n $rotateCount -f $CAPTURE_FILE"

    /**
     * 采集进程的命令行 —— 启动用的完整命令（**含绝对路径**）。
     *
     * ## ⚠️⚠️ 为什么必须与查杀特征串共用同一组参数
     *
     * 查杀用的是 `ps | grep <特征串>`，grep 是**子串**匹配。两处各写一遍时，
     * 只要有一处参数变动，匹配就静默失效 —— 而**失效的表现是
     * "进程杀不掉、文件一直涨"**，不报任何错。
     *
     * 这个坑实际发生过两次：
     * 1. 补了 `-T 1` 却没同步特征串 → 中间被隔断，匹配失败
     * 2. 特征串带了 `/system/bin/` 前缀 → 与 ps 的输出对不上，匹配失败
     *
     * 两次的症状完全一样，都是「点了结束采集，文件还在涨」。
     * 现在两个函数从同一组参数派生，且 [capturePatternForPs] 只取本函数
     * `logcat` 之后的尾巴 —— 参数变了，两边一起变。
     *
     * `-T 1` 一旦写进特征串，`-T` 就成了"杀进程识别"的必要组成，
     * 将来移除它（比如改用别的去历史手段）时必须同步考虑查杀。
     *
     * @return 不含重定向的 logcat 命令行（含绝对路径）
     */
    internal fun captureCommandLine(rotateKb: Int, rotateCount: Int): String =
        "$LOGCAT $VERBOSITY -T 1 -r $rotateKb -n $rotateCount -f $CAPTURE_FILE"

    /**
     * 杀掉**所有**我们启动的 logcat 采集进程。
     *
     * ## 为什么不能只 kill pidfile 里的 pid
     *
     * 实测事故：设备上累积了 **24 个**采集进程同时写一个文件 ——
     * 「点采集到结束只差 2 秒」却拿到几小时的日志，且 24 个进程持续吃 CPU。
     *
     * 真机复现出的完整链条（**实测**，不是推断）：
     *
     * ```
     * $ kill $(cat PIDFILE)          ← pidfile 丢了时变成【裸 kill】
     * cat: PIDFILE: No such file or directory
     *                                ← 报错被 2>/dev/null 吞掉
     * $ rm -f PIDFILE; touch DONE
     *                                ← 用 `;` 连接，所以照常执行
     * → 界面探测到 DONE，显示「已完成」、通知消失
     * → 而采集进程一个都没死，继续往文件里写
     * ```
     *
     * ## ⚠️⚠️ 为什么**不能**用 `pkill -f`（同一个坑踩了两次）
     *
     * 第一版写 `pkill -f "...logcat..."`：
     *
     * - 应用用 `ProcessBuilder("sh", "-c", 整条命令)` 执行，
     *   于是 **`sh` 自己的命令行里完整包含这段命令文本**；
     * - `pkill -f` 匹配的是**完整命令行**，因此它**匹配到了自己**，
     *   发出 SIGTERM 自杀。
     *
     * 真机症状：点「开始采集」立刻报
     * `Error (code 143): Command failed with no error message`。
     * **143 = 128 + 15 = SIGTERM** —— 被自己打死。
     *
     * 第二版想用字符类 `[l]ogcat` 绕开自匹配 —— **仍然失败**。
     * 因为 `[l]ogcat` 只让「模式字符串自身」不再匹配，
     * 但命令里**别的位置**照样有 `logcat` 字样
     * （`/system/bin/logcat`、`logcat_capture.log`），
     * 而 `[l]ogcat` 作为正则**能匹配这些字面量** —— 照样自杀。
     *
     * 现在改成：`ps` 取 pid 列表 → **显式排除 `$$`（自己）与 `$PPID`（父 shell）**
     * → 逐个 kill。不依赖任何正则技巧，语义直白。
     * （已在真机上用应用的调用方式 `sh -c` 验证：退出码 0、进程清空、不自杀。）
     *
     * ## 为什么不会误伤别的东西
     *
     * 特征串带了完整参数 `-r 65536 -n 4 -f <文件>`，是本工具独有的组合：
     * - 系统自己的 logcat 不带这些参数
     * - **logcat 触发器的流**是 `logcat -v threadtime -T 1`，不含 `-r/-n/-f`，
     *   已在真机上确认**不会被误杀**
     *
     * ## ⚠️ 特征串来自 [captureCommandLine]，不要就地拼
     *
     * grep 是子串匹配，特征串必须是启动命令行的连续子串。
     * 两处各拼一遍的代价见 [captureCommandLine] 的说明 —— 已经实际踩过一次。
     *
     * @param rotateKb    与 [buildStartCapture] 收到的值一致
     * @param rotateCount 同上
     */
    internal fun killCapturedProcesses(
        rotateKb: Int = ROTATE_KB,
        rotateCount: Int = ROTATE_COUNT,
    ): String {
        // ⚠️ 用 capturePatternForPs 而**不是** captureCommandLine ——
        // 后者带 `/system/bin/` 前缀，与 `ps` 显示的 ARGS 对不上（见该函数的说明）
        //
        // `[l]ogcat` 是为了让**本命令自身的命令行**不被匹配到（详见上方 pkill 那段的教训）
        val pat = capturePatternForPs(rotateKb, rotateCount).replaceFirst("logcat", "[l]ogcat")
        // ⚠️ shell 变量要写成 ${'$'}X（Kotlin 普通字符串里的转义），
        // 否则 `$P` 会被当成 Kotlin 模板插值、编译不过。
        // 比较用 `=` 而非 `==`：POSIX sh 更稳。
        return "ME=${'$'}${'$'}; " +
            "ps -A -o PID,ARGS | grep \"$pat\" " +
            "| while read P R; do " +
            "if [ \"${'$'}P\" != \"${'$'}ME\" ] && [ \"${'$'}P\" != \"${'$'}PPID\" ]; " +
            "then kill \"${'$'}P\" 2>/dev/null; fi; " +
            "done"
    }

    /**
     * 停止采集。
     *
     * ⚠️ **先杀进程、再删 pidfile**，顺序不能反 —— 反了就再也拿不到
     * 该杀谁的信息（虽然 [killCapturedProcesses] 按特征杀，不依赖 pidfile，
     * 但 pidfile 里那个 pid 仍是更精确的一手信息）。
     *
     * 而且**两个都要做**：pidfile 里的 pid 精确，特征匹配则兜住
     * "pidfile 丢失/记错"的情况。实测那次事故就是后者。
     *
     * ⚠️ **pidfile 里存的是 `timeout` 的 pid，不是 logcat 的** ——
     * `timeout ... &` 时 `$!` 拿到的是 timeout 进程。杀掉它 logcat 未必跟着死，
     * 所以特征匹配那一步**不是"兜底"，是主力**。
     * （这也是为什么 [captureCommandLine] 的共用如此关键：
     * 特征串一旦失配，停止采集就只杀得掉 timeout，logcat 会一直写下去。）
     */
    fun buildStopCapture(
        rotateKb: Int = ROTATE_KB,
        rotateCount: Int = ROTATE_COUNT,
    ): String =
        "if [ -s $PID_FILE ]; then kill \$(cat $PID_FILE) 2>/dev/null; fi; " +
            killCapturedProcesses(rotateKb, rotateCount) + "; " +
            "rm -f $PID_FILE; " +
            "touch $DONE_FILE"

    /**
     * 只清理脏状态（[CaptureState.STALE] 态下用户点「清理」）。
     *
     * 设计上**不自动清理**——用户可能想先看看那次异常结束前采集到的日志。
     */
    fun buildClearStale(): String = "rm -f $PID_FILE"

    /**
     * 删除采集文件（含轮转出来的历史份）。
     *
     * 用户主动清理时用。**不做自动定时清理** —— 采集文件是用户特意采下来
     * 排查问题的，有保留价值；只有在**下一次开始采集**时才会清掉上一轮
     * （见 [buildStartCapture]），那时旧数据已经没用了。
     *
     * ⚠️ 同时删 done 标记：文件没了却留着"已完成"标记的话，
     * 界面会停在一个空的已采集态，而用户以为还能看到内容。
     */
    fun buildDeleteCaptureFiles(): String =
        "rm -f $CAPTURE_GLOB $DONE_FILE"

    /**
     * 采集文件的当前占用（字节）。
     *
     * ⚠️ 用 `du -sk` 汇总而不是 `ls` —— 要算的是**所有轮转份之和**，
     * 只看主文件会低估（实测总量可达主文件的数倍）。
     *
     * @return 形如 `12345` 的 KB 数；失败时为 0
     */
    fun buildCaptureSize(): String =
        // `</dev/null` 同理：无匹配时 du 无参数会读 stdin 而挂住
        "du -sk $CAPTURE_GLOB 2>/dev/null </dev/null | awk '{s+=\$1} END {print s+0}'"

    /**
     * 回到空闲（读实时缓冲区）。
     *
     * 用户在「已完成」态点「重新采集」之外，也可以主动放弃这批日志 ——
     * 那时删掉标记，数据源就切回缓冲区。
     */
    fun buildClearDone(): String = "rm -f $DONE_FILE"

    /**
     * 判定当前采集状态。输出恒为一行，不会撞 Binder 上限。
     *
     * 输出格式：`IDLE` / `CAPTURING:<pid>` / `STALE:<pid>` / `COMPLETED`
     *
     * ⚠️ 判定顺序有讲究：**先看有没有进程，再看标记文件**。
     * 反过来的话，一次新采集刚开始（pidfile 已写、done 标记还没来得及删）
     * 会被误判成"已完成"，于是用户看到上次的日志。
     *
     * ⚠️ 用 pidfile 精确匹配而非 `grep logcat`（类注释第 3 条）。
     * `ps -A -o PID=` 的写法参照项目内先例 `services/CoreLauncher.kt:268`。
     */
    fun buildProbeState(): String =
        "if [ -f $PID_FILE ]; then " +
            "pid=\$(cat $PID_FILE); " +
            "if ps -A -o PID= | grep -qw \"\$pid\"; then echo CAPTURING:\$pid; " +
            "else echo STALE:\$pid; fi; " +
            "elif [ -f $DONE_FILE ]; then echo COMPLETED; " +
            "else echo IDLE; fi"

    /**
     * 解析 [buildProbeState] 的输出。
     */
    fun parseProbeState(output: String): CaptureState {
        val s = output.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            s == "IDLE" -> CaptureState.Idle
            s == "COMPLETED" -> CaptureState.Completed
            s.startsWith("CAPTURING:") -> CaptureState.Capturing(s.removePrefix("CAPTURING:").toIntOrNull() ?: -1)
            s.startsWith("STALE:") -> CaptureState.Stale(s.removePrefix("STALE:").toIntOrNull() ?: -1)
            // 无法识别时保守当作空闲——至少不会误报"正在采集"而阻止用户操作
            else -> CaptureState.Idle
        }
    }

    // ── 读取 ─────────────────────────────────────────────────────

    /** 需要转义的正则元字符。 */
    private const val REGEX_METACHARS = """[.\[\](){}*+?^$|]"""

    /** 转义前缀（反斜杠）。写成码位是为了避开转义层数 —— 这里已经写错过一次。 */
    private val REGEX_ESCAPE_PREFIX = 0x5C.toChar().toString()

    /** 采集文件的通配符（含轮转出来的历史份）。 */
    private const val CAPTURE_GLOB = "$CAPTURE_FILE*"

    /**
     * **在整个采集文件里检索**（采集态与已完成态都用它）。
     *
     * ## 为什么不是 `tail -n N`
     *
     * 原实现取文件**末尾 N 行**。在 2800 行/秒的日志下，
     * 1000 行只覆盖 **0.35 秒** —— 用户"采 5 分钟再回来查"时，
     * 除了最后零点几秒，其余全部看不到。
     * 这与「覆盖开关之间的日志」的设计意图完全背离。
     *
     * 改成检索后，**只要文件里还留着的行就能找回来**，
     * 不再受"末尾 N 行"这道闸门限制。
     *
     * ## 检索范围
     *
     * 用 `$CAPTURE_FILE*` 通配符覆盖**轮转出来的历史份**——
     * 只看当前那份的话，能见到的还是最近一小段。
     *
     * ## ⚠️ 顺序
     *
     * 轮转文件名是 `logcat_capture.log.1`、`.2`…（**数字越大越旧**），
     * 而 shell 的通配符展开是按字典序：`.1` 会排在 `.10` 之前。
     * 因此**不能靠通配符的展开顺序**得到时间顺序，
     * 得用 `ls -tr` 按修改时间反序再 cat。这里用 `-t` 正序（最旧在前）。
     *
     * ## 反压
     *
     * 匹配行数可能远超传输上限，所以 shell 侧就要截断（`tail -n`），
     * 否则会撞 Binder 上限把 UserService 打死（类注释第 1 条）。
     *
     * @param tagQuery TAG 关键字（空 = 不过滤）。**大小写不敏感**
     * @param messageQuery 消息关键字（空 = 不过滤）
     * @param minLevel 最低级别；用 `grep` 的级别字符匹配
     * @param limit  最多返回多少行
     * @param skipBytes 从文件末尾**往前跳过**多少字节再开始读（翻页用）。
     *   `0` = 读最新的那一段。见 [buildSearch] 的说明。
     * @param windowKb 本次读取的字节窗口大小（KB）
     */
    fun buildSearch(
        tagQuery: String = "",
        messageQuery: String = "",
        minLevel: LogLevel = LogLevel.VERBOSE,
        limit: Int = MAX_LINES,
        skipBytes: Long = 0L,
        windowKb: Int = READ_WINDOW_KB,
    ): String {
        val pattern = buildSearchPattern(tagQuery, messageQuery, minLevel)

        val windowed = buildWindowedRead(skipBytes, windowKb)

        // ⚠️ `-E` **不是可选的**：pattern 里用了 `[ ]+`、`|`、`.*` 等 ERE 语法，
        // 而在基本正则（BRE，grep 默认）里 `+` 是**字面字符**而不是量词 ——
        // `[ ]+` 会去匹配"一个空格后跟一个加号"，永远匹配不到。
        // 症状极其误导：文件好好写着日志、命令也正常返回，**只是永远搜不到**。
        // 已实际踩过（用户报「采集不到日志」）。
        //
        // ⚠️ `timeout` 是唯一能真正兜住"命令永不返回"的东西。
        // 日志实证：一条检索命令跑了 **24943ms** 才返回（期间 Core 被重启、
        // 连接断开，命令是靠断开"顺带"结束的）。没有 timeout 时，
        // 只要通道一直不回，协程就永远挂着 —— 而它持有 `refreshing` 标志，
        // 界面表现为"一直加载中"。
        //
        // 取 20 秒：正常检索几百毫秒，慢的一两秒。
        //
        // ⚠️⚠️ **`timeout` 必须裹住整条管道，不能只裹最左一段**。
        //
        // 写成 `timeout N cat ... | grep | tail` 是错的，两个后果都静默：
        //
        // 1. **退出码被吞掉**：管道退出码 = 最后一个命令（`tail`）的退出码，
        //    永远是 0。于是 `timeout` 到点杀掉 `cat` 后，调用方看到
        //    退出码 0 + 空输出 —— 分不清"没有匹配"与"命令被超时砍了"。
        //    实测：`timeout 1 sh -c 'sleep 3'` 单独跑返回 124，
        //    接个 `| cat` 就变成 0。
        // 2. 后面两段（grep / tail）**不受超时约束**，真卡住时没有兜底。
        //
        // 用 `timeout N sh -c '<整条管道>'` 而不是 `{ ...; } | cat`：
        // 后者仍然以管道结尾，退出码照吞（实测确认）。只有把 timeout
        // 放在最后一段的位置，124 才能传出来。
        //
        // 改动的前提：**超时必须在命令行上可判定**。原先 `timedOut` 的
        // 判定字段自始至终没接上数据源，这条命令是唯一能提供依据的地方。
        val timeoutSec = 20
        val pipeline = windowed +
            " | grep -aE${pattern.grepFlags} -e ${shellQuote(pattern.regex)}" +
            " | tail -n ${clampLines(limit)}"
        return "timeout $timeoutSec sh -c ${shellQuote(pipeline)}"
    }

    /**
     * `timeout` 到点杀进程时的退出码（`128 + SIGTERM`）。
     *
     * toybox / GNU 的 `timeout` 在超时后都返回 124，与"命令自己以 124 退出"
     * 无法区分 —— 但检索命令不会有别的理由返回 124，
     * 且误判的代价（提示"超时"而非"无结果"）远小于漏判（把超时说成"没日志"）。
     */
    const val TIMEOUT_EXIT_CODE = 124

    /**
     * 从 shell 结果判断这次读取是否**因超时**而中止。
     *
     * ⚠️⚠️ **只能看退出码，不能看输出前缀。**
     *
     * 三条执行路径（Shizuku / Root / Core）在退出码非零时，
     * **都会把输出包装成 `Error: ...`**（见 `BaseWorker.executeCommand`
     * 与 `ShellManager.executeRootCommand`）。超时时退出码是 124，
     * 于是输出恰好是 `Error: Exit code 124`。
     *
     * 如果按"输出以 Error: 开头就不是超时"排除，**会把唯一要检测的情况排除掉** ——
     * 这个修复就完全失效了，而且失效得无声无息（界面照旧显示"没日志"）。
     *
     * 而 Shell 层的真实失败（权限不足 / 通道断开）退出码是 `-1`，
     * 本来就不等于 124，不需要额外排除。
     *
     * 只看空输出也不够：正常"没有匹配"同样是空输出，
     * 但两者给用户的下一步完全不同（一个要放宽条件，一个要换数据源）。
     *
     * @param exitCode 命令退出码。`timeout` 到点杀进程时为 124。
     */
    fun isTimeoutResult(exitCode: Int): Boolean = exitCode == TIMEOUT_EXIT_CODE

    /**
     * 构造「只读末尾一窗」的 shell 片段。
     *
     * ## 老实现的问题
     *
     * `cat $(ls -tr ...) | grep | tail -n N` ——
     * **为了最后 N 行把整个采集文件读了一遍**。代价 `O(文件总大小)`，
     * 且与「条数」选多少**完全无关**：选 1000 和选 2000 都要扫 256MB。
     *
     * 实测代价（用户日志）：
     * ```
     * 读取日志: 用时=4018ms 行数=1000
     * 读取日志: 用时=6184ms 行数=1000
     * ```
     * 读 1000 行要 4–6 秒，**采集越久越慢**。
     *
     * ## ⚠️ 为什么不能简单写成 `cat ... | tail -c N`
     *
     * 那样 `cat` **仍然把每个文件都读了一遍**，只是 `tail` 只保留最后一窗 ——
     * 磁盘 I/O 一点没省。必须**从最新的文件往回数**，只挑够用的那几个文件读。
     *
     * ## 做法
     *
     * 1. `ls -t`（按时间**倒序**，最新的在前）逐个累加文件大小，
     *    直到凑够 `skip + window` 字节就停 —— 后面的老文件**根本不打开**
     * 2. 把挑中的文件按时间**正序**拼起来（累加时往前面插）
     * 3. 截出目标窗口：先砍掉末尾 `skip` 字节，再取尾部 `window` 字节
     *
     * ## ⚠️⚠️ 为什么是 `head -c (total-skip) | tail -c window`
     * 而不是 `tail -c (skip+window) | head -c window`
     *
     * 后者在**窗口大于「文件总量减 skip」**时会**静默失效**：
     *
     * ```
     * 文件总量 T=1000, skip=2000, window=1.6MB
     *   tail -c (2000 + 1.6M)  → 文件只有 1000 字节，取不到那么多，**退回整个文件**
     *   head -c 1.6M           → 也取不到那么多，**退回整个文件**
     *   → 结果是【整个文件】，skip 被完全忽略
     * ```
     *
     * 后果是**每一页都显示同一批内容**（最新那一段），且用户按「条数」看到的
     * 行数恒定 —— 真机实测：7103 行的文件、选 2000 条，"最后一页"仍显示
     * 整整 2000 行，而它本该只有 103 行。
     *
     * 新写法把 `skip` 表达成**末尾要砍掉的字节数**，与窗口大小无关：
     * ```
     *   head -c (T - skip)  → 砍掉末尾 skip 字节，留下 [0, T-skip)
     *   tail -c window      → 取这段的尾部 window 字节
     *   → 得到 [T-skip-window, T-skip)，正是目标窗口
     * ```
     * 边界核对（真机实测 toybox 行为）：
     *
     * | T | skip | window | 旧写法 | 新写法 |
     * |---|---|---|---|---|
     * | 1000 | 200 | 3000 | 整个文件（错）| `[0,800)` 的尾部 800 ✅ |
     * | 1000 | 0 | 3000 | 整个文件 ✅ | 整个文件 ✅ |
     * | 1000 | 1000 | 3000 | 整个文件（错）| 空 ✅ |
     *
     * ⚠️ `head -c -N`（排除末尾 N 字节的 GNU 写法）在 **toybox 上不支持**
     * （报 `head: -c < 0`），所以必须用「T 减 skip」的算术形式。
     *
     * ## 边界
     *
     * - 文件不存在时循环不执行，`cat` 无参数会**读 stdin 而挂死**，
     *   所以拼出的命令里 `cat` 永远带 `</dev/null`
     * - 窗口夹到 `[64KB, 32MB]`：太小会取不满 N 行，太大则拖慢跨进程传输
     */
    internal fun buildWindowedRead(skipBytes: Long, windowKb: Int): String {
        val kb = clampWindowKb(windowKb)
        val skip = skipBytes.coerceAtLeast(0L)
        val winBytes = kb * 1024L
        // ⚠️ 累加挑选仍按「skip + window」算：要打开的文件必须能覆盖到窗口的起点。
        // 这一步只是决定"打开哪几个轮转文件"，与截取方式无关
        val needBytes = skip + winBytes

        // 累加挑选 + 拼接。`n` 用来兜底：极端情况下（比如所有文件都很小）
        // 不会无上限地打开文件
        return "sh -c '" +
            "need=$needBytes; acc=0; files=\"\"; n=0; " +
            "for f in \$(ls -t $CAPTURE_GLOB 2>/dev/null); do " +
            // 往前面插，拼完就是时间正序（最旧在前）
            "files=\"\$f \$files\"; " +
            // ⚠️ `wc -c < f` 的 `<` 不能省：写成 `wc -c f` 会输出文件名，
            // 参与算术运算会报错并被 `|| echo 0` 吞掉 → 大小恒为 0 →
            // 循环因永远凑不够而**把所有文件都打开**，等于没优化
            "sz=\$(wc -c < \"\$f\" 2>/dev/null || echo 0); " +
            "acc=\$((acc + sz)); n=\$((n + 1)); " +
            "[ \$acc -ge \$need ] && break; " +
            "[ \$n -ge 16 ] && break; " +
            "done; " +
            // ⚠️ `keep` 必须在**管道外面**算 —— 放进管道就成了子 shell，
            // 那里的变量赋不回来，也没法对它做 clamp。
            //
            // `acc` 就是"接下来要 cat 的这批文件的字节总量"：
            // 窗口 [acc-skip-win, acc-skip) 必定落在它内部，所以只按它算即可。
            // clamp 到 0：skip 超过总量（翻到文件开头之上）时 head -c 负数会报错
            "keep=\$((acc - $skip)); [ \$keep -lt 0 ] && keep=0; " +
            // 无匹配时 files 为空 → `cat </dev/null` 立刻 EOF，不会挂死
            "cat \$files </dev/null 2>/dev/null " +
            "| head -c \$keep " +
            // ⚠️ 传**字节数**而不是 `16384k`：`tail -c` 的 k 后缀是 GNU 扩展，
            // toybox 上不保证支持。算术展开在任何实现上都成立
            "| tail -c \$(( $kb * 1024 ))" + "'" +
            " </dev/null"
    }

    /**
     * 读取窗口的下限（KB）。
     *
     * ⚠️ **它必须小于「最小条数档」所需的窗口**，否则会把那个档位的余量夹掉。
     *
     * 约束的算法（见 [windowKbForPageLines] 与 [FILTER_HEADROOM]）：
     * ```
     * 最小档 200 条 × 200 字节/行 × 余量 8 / 1024 = 312KB
     * ```
     * 所以下限只能取 **< 312** 的值。取 256 是留了余地的选择；
     * 取 320 之类**大于 312** 的值会反过来把 200 档的窗口**抬大**
     * （不是夹小 —— 这点容易搞反），同样破坏"窗口 = 档位所需"的关系。
     *
     * ⚠️ 实测记录：这个下限在历史上（64KB）**从未生效过** —— 因为所有档位
     * （200/500/1000/2000）算出的窗口都远大于它，clamp 的 `coerceIn` 左边
     * 一次都没触发。它现在的意义是"防止将来有人传入极小的窗口值"，
     * 不是"修正某个档位"。
     */
    const val MIN_READ_WINDOW_KB = 256

    /** 把读取窗口夹到合理范围。 */
    fun clampWindowKb(kb: Int): Int = kb.coerceIn(MIN_READ_WINDOW_KB, MAX_READ_WINDOW_KB)

    /**
     * 按**行数分页**时，估算第 [page] 页需要跳过多少字节。
     *
     * ## 为什么页码改按行数算
     *
     * 早先页码 = 「第几个字节窗口」，于是页码与「每页多少条」**定义不一致**：
     * 切一次「条数」，总页数就变了，而当前页码没变 —— 直接越界。
     *
     * 现在统一按行数分页：第 N 页 = 跳过 `(N-1) × 每页条数` 行。
     * 代价是 shell 侧按**字节**读取，行数要先换算成字节。
     *
     * ## ⚠️⚠️ 这里用估算，所以**不能**拿它当权威翻页步进
     *
     * 本函数存在的意义只剩「给首次定位一个起点」——比如用户从第 1 页
     * 直接跳到第 8 页，手上没有任何已读数据，只能估一个位置先读回一页。
     * 读回来之后，**后续翻页一律改用 [nextPageSkipBytes] 按实际内容推进**。
     *
     * 原先它是唯一的翻页机制，于是**行长估错就直接漏内容**：
     *
     * ```
     * 步进 = 每页条数 × 200（本函数的估算）
     * 一页实际内容 = 每页条数 × 真实行长
     * 真实行长 141 字节时：步进只能覆盖 1000×200/141 ≈ 1418 行，
     *                      而一页只显示 1000 行 → 每翻一页漏掉 418 行
     * ```
     *
     * 表里就是它与真实内容的错位（真实行长以实测推算的 141 为参照）：
     *
     * | 真实行长 | 步进覆盖 | 结果 |
     * |---|---|---|
     * | 141 字节 | 1418 行 | **漏 42%** |
     * | 165 字节 | 1212 行 | **漏 21%** |
     * | 200 字节 | 1000 行 | 恰好衔接（不可能恒成立） |
     * | 250 字节 | 800 行 | 重叠 20% |
     *
     * 只要真实行长不等于 200 这个估计值就会漏或重，而它是估的。
     *
     * @param page 页码（从 1 起）
     * @param linesPerPage 每页条数（用户选的「条数」）
     * @param avgLineBytes 平均行长；默认按实测的 200 字节
     */
    fun skipBytesForPage(
        page: Int,
        linesPerPage: Int,
        avgLineBytes: Int = AVG_LINE_BYTES,
    ): Long {
        val safePage = page.coerceAtLeast(1)
        val safeLines = linesPerPage.coerceAtLeast(1)
        val safeAvg = avgLineBytes.coerceAtLeast(1)
        // ⚠️ 先减 1 再乘：第 1 页不跳过任何内容
        return (safePage - 1L) * safeLines * safeAvg
    }

    /**
     * **翻到相邻页**时该跳过的字节数 —— 按上一页的**实际内容量**推进。
     *
     * ## 为什么不能用 [skipBytesForPage] 的估算
     *
     * 一页的内容**就是** `lineLimit` 行 —— shell 侧 `tail -n <limit>` 已经把它
     * 切齐了。所以推进一页 = 推进「这 `lineLimit` 行实际占用的字节数」。
     * 这个量**是可测的**（把读回来的行原文长度加起来），不需要估。
     *
     * 用估算的后果见 [skipBytesForPage] 的表格：行长估偏就漏内容，
     * 而且**不报错** —— 用户只会觉得"有些日志翻不到"。
     *
     * ## 边界
     *
     * 上一页一行都没读到（读到文件开头了）时，返回 `null` 表示**翻不动了**，
     * 调用方应提示而不是拿一个估算值去跳 —— 那样只会跳进虚空。
     *
     * @param previousSkipBytes 上一页用的 skip
     * @param previousContentBytes 上一页**实际内容**占用的字节数
     *   （由读回来的行的原文长度累加得到）
     * @return 下一页的 skip；没有可推进的内容时返回 `null`
     */
    fun nextPageSkipBytes(
        previousSkipBytes: Long,
        previousContentBytes: Long,
        lineLimit: Int,
    ): Long? {
        if (lineLimit <= 0) return null
        // 没有内容 = 已经读到文件开头，再往前没有东西了
        if (previousContentBytes <= 0L) return null
        return previousSkipBytes.coerceAtLeast(0L) + previousContentBytes
    }

    /**
     * 一页内容占用的字节数（含换行）。
     *
     * ⚠️ 用 `raw` 而不是拼接后的展示文本：定窗读取按**原始字节**定位，
     * 换算回去也必须用同一口径，否则对不上。
     *
     * 末尾那条不带换行的行（文件最后一行常常没有 `\n`）会少算 1 字节，
     * 属于可忽略的误差 —— 而漏算一整行才是要避免的。
     */
    fun contentBytesOf(lines: List<LogcatLine>): Long =
        lines.sumOf { it.raw.toByteArray(Charsets.UTF_8).size.toLong() + 1L }

    /**
     * 一行日志的**平均字节数**（含 `\n`）。
     *
     * 实测 `-v threadtime` 的典型行：
     * `09-20 14:57:29.678 31993 31993 E DynamicIslandEventCoordinator: checkError ...`
     * 约 130–200 字节。
     *
     * ⚠️ 取 200 是**上限方向**的估计，只用于 [skipBytesForPage] 的首次定位，
     * **不要**拿它当翻页步进（见该函数的说明）。
     */
    const val AVG_LINE_BYTES = 200

    /**
     * 本次读取应该用的**字节窗口** —— 读取与翻页的**唯一口径**。
     *
     * ## ⚠️⚠️ 为什么必须只有一个口径
     *
     * 原先是两套：`loadFromSource()`（刷新/首次加载）走 [READ_WINDOW_KB]（固定 16MB），
     * 而 `goToPage()`（翻页）走 [windowKbForPageLines]。两者相差最多 250 倍，
     * 于是**同一批日志，刷新翻到的是这 16MB，翻页翻到的是那块 64KB** ——
     * 用户看到"页码和内容对不上"，而源头是两条入口各算各的。
     *
     * 更糟的是 `windowKbForPageLines(200)` 算出 312KB，被 [clampWindowKb] 的下限
     * 夹成 64KB —— 余量系数（8）被夹没了：64KB ≈ 330 行原始日志，
     * 带一个命中率 30% 的 TAG 过滤只剩约 99 行 → **选 200 条永远取不满 200 条**，
     * 而界面不会说"这页没取满"。
     *
     * 现在两个入口都调本函数，且 [clampWindowKb] 的下限与这里的下限一致。
     *
     * @param linesPerPage 每页条数（= 用户选的「条数」）
     */
    fun windowKbForPageLines(linesPerPage: Int): Int {
        val bytes = linesPerPage.coerceAtLeast(1).toLong() * AVG_LINE_BYTES * FILTER_HEADROOM
        return clampWindowKb((bytes / 1024).toInt())
    }

    /**
     * 过滤余量系数。
     *
     * 窗口要覆盖"筛之前的行数"，否则窄过滤下取不满一页。
     * 取 8 是折中：能覆盖命中率 ≥12.5% 的常见 TAG，
     * 更窄的条件由「取不满就扩窗重读」的阶梯兜底（[nextWindowForRetry]）。
     */
    private const val FILTER_HEADROOM = 8L

    /**
     * 由过滤条件构造 grep 用的正则。
     *
     * ## 为什么在 shell 侧 grep 而不是把文件读回来内存筛
     *
     * 文件可达数百 MB，不可能整个读回来。必须在 shell 侧先收窄。
     *
     * ## 级别怎么匹配
     *
     * `threadtime` 的级别在固定列，但这**不能靠列位置硬切**——
     * pid/tid 的宽度在不同 ROM 上可能不同。用正则匹配 `\s级别\s`
     * 的形状更稳：日志行的结构是 `... pid tid 级别 TAG: msg`。
     *
     * ⚠️ `minLevel` 是"至少这么严重"，所以 E 要同时放行 E 与 F。
     */
    internal fun buildSearchPattern(
        tagQuery: String,
        messageQuery: String,
        minLevel: LogLevel,
    ): SearchPattern {
        val parts = mutableListOf<String>()

        // 级别：>= minLevel 的所有级别字符
        val levels = LogLevel.entries
            .filter { it.priority >= minLevel.priority }
            .joinToString("") { it.char.toString() }
        if (levels.length < LogLevel.entries.size) {
            parts.add("[ ]+[$levels][ ]+")
        }

        // TAG 与消息：都是大小写不敏感的子串
        tagQuery.trim().takeIf { it.isNotBlank() }?.let {
            parts.add("${escapeForRegex(it)}.*:")
        }
        messageQuery.trim().takeIf { it.isNotBlank() }?.let {
            parts.add(escapeForRegex(it))
        }

        return SearchPattern(
            regex = if (parts.isEmpty()) "." else parts.joinToString(".*"),
            grepFlags = if (tagQuery.isNotBlank() || messageQuery.isNotBlank()) "i" else "",
        )
    }

    /**
     * 转义正则元字符。
     *
     * 用户输入的 TAG / 关键字里可能有 `.` `[` `(` 等，
     * 不转义会被当成正则语法 —— 表现是**匹配结果莫名其妙**
     * （如 TAG 里有个 `.` 就变成"任意字符"）。
     */
    internal fun escapeForRegex(value: String): String =
        value.replace(Regex(REGEX_METACHARS)) { m -> REGEX_ESCAPE_PREFIX + m.value }

    /**
     * 快照：读 logcat 缓冲区（**空闲态**用）。
     *
     * @param lines    取最近多少行（会被夹到 [MAX_LINES]）
     * @param tag      TAG 过滤；null 或空白表示不过滤
     * @param minLevel 最低级别；用于 filter spec 的 `TAG:LEVEL` 形式
     */
    fun buildSnapshot(
        lines: Int = DEFAULT_LINES,
        tag: String? = null,
        minLevel: LogLevel = LogLevel.VERBOSE,
    ): String = buildString {
        append("$LOGCAT -d $VERBOSITY -T ${clampLines(lines)}")
        // 调试工具的 TAG 过滤可以下推（没有热更新需求，命令只在点击时生成一次）。
        // 这与触发器设计 §5.3「有意不下推」不冲突——两者场景不同。
        tag?.trim()?.takeIf { it.isNotBlank() }?.let { t ->
            append(" -s ").append(shellQuote(t)).append(":").append(minLevel.char)
        }
    }

    /**
     * TAG 统计专用的快照（调试工具 §4.5）。
     *
     * ⚠️ **不带 TAG 过滤**——用户点「TAG 统计」的动机恰恰是"我不知道该填哪个 TAG"，
     * 此时他要不没设过滤，要不设的是**猜的**。
     * 用被猜过的条件去统计，等于让用户自己回答自己的问题。
     *
     * 级别过滤保留：「只看 W 以上有哪些 TAG」是合理诉求。
     *
     * @param lines 采样行数；统计需要比常规浏览更多样本，默认取上限
     */
    fun buildSnapshotForTagStats(
        lines: Int = MAX_LINES,
        minLevel: LogLevel = LogLevel.VERBOSE,
    ): String = buildString {
        append("$LOGCAT -d $VERBOSITY -T ${clampLines(lines)}")
        // filter spec 的 `*:LEVEL` 形式：所有 TAG，最低该级别
        if (minLevel != LogLevel.VERBOSE) {
            append(" *:").append(minLevel.char)
        }
    }

    // ── 工具函数 ──────────────────────────────────────────────────

    /**
     * 按当前状态选择「刷新」该跑哪条命令（调试工具 §4.1.1）。
     *
     * **数据源由状态决定，不暴露给用户**——这正是把「抓取一次」与「刷新」
     * 两个按钮合并成一个的原因：并列两个按钮等于把内部状态泄漏到 UI。
     *
     * @return 要执行的命令；[CaptureState.Stale] 返回 null（应走提示而非执行）
     */
    fun buildRefresh(
        state: CaptureState,
        lines: Int = DEFAULT_LINES,
        tag: String? = null,
        minLevel: LogLevel = LogLevel.VERBOSE,
        messageQuery: String = "",
        /** 往前翻页：从文件末尾往回跳过的字节数。见 [buildSearch]。 */
        skipBytes: Long = 0L,
        /** 本次读取的字节窗口（KB）。见 [READ_WINDOW_KB]。 */
        windowKb: Int = READ_WINDOW_KB,
    ): String? = when (state) {
        is CaptureState.Idle -> buildSnapshot(lines, tag, minLevel)
        // 采集态与已完成态都走 shell 侧检索。
        //
        // ⚠️ 过滤**下推到了 grep**，这与"采集文件永远全量写入"不冲突：
        // 文件里仍是全量，只是读的时候在 shell 侧收窄。
        // 不下推的话（原实现）只能读文件末尾 N 行，高频日志下
        // 1000 行只覆盖 0.35 秒，等于大部分内容搜不到。
        is CaptureState.Capturing ->
            buildSearch(tag.orEmpty(), messageQuery, minLevel, lines, skipBytes, windowKb)
        is CaptureState.Completed ->
            buildSearch(tag.orEmpty(), messageQuery, minLevel, lines, skipBytes, windowKb)
        is CaptureState.Stale -> null
    }

    /**
     * 全量统计：**整个采集文件**的总行数 + TAG 分布 + 首末时间戳。
     *
     * ## 为什么不用 [buildTagStats]
     *
     * 那条命令带 `tail -n <limit>`，统计的是**文件末尾 N 行**的采样。
     * 用户拿它当"总量"看就会得出错误结论（实测症状：底部状态栏显示
     * 「本次读取 4176 行」，TAG 统计弹窗却显示「共 2000 行」——
     * 那个 2000 是 `tail` 的截断线，不是总量）。
     *
     * ## 为什么这样拼
     *
     * 聚合在 **shell 侧**完成，回传的只有几十行摘要 ——
     * 比传 2000 行原始日志还小，不存在 Binder 压力。
     * 这正是"可以扫全量"的前提：贵的不是扫描，是**回传**。
     *
     * 输出格式（便于解析，非面向人类）：
     * ```
     * TOTAL <总行数>
     * FIRST <时间戳>
     * LAST <时间戳>
     * TAG <TAG名> <行数>
     * ...
     * ```
     *
     * ⚠️ 用 `awk` 提取 TAG 而不是再起一个 `grep`/`sed` 管道：
     * 每多一个进程就多一次全量流经，而这里已经在读 256MB 了。
     *
     * @param minLevel 级别过滤（与读取口径一致，否则两个数没法比）
     * @return 要执行的命令
     */
    fun buildCaptureStats(minLevel: LogLevel = LogLevel.VERBOSE): String {
        val levels = LogLevel.entries
            .filter { it.priority >= minLevel.priority }
            .joinToString("") { it.char.toString() }
        // ⚠️⚠️ **必须带方括号** —— 它是 sed 匹配式里的一个【字符类】，
        // 不是字符序列。写成裸的 `DIWEF` 会被当成"依次出现这五个字符"，
        // 而日志行里级别只占一个字符位置 —— **永远匹配不到任何行**。
        //
        // 症状：TAG 统计弹「没有统计到任何 TAG」，而 TOTAL / 覆盖时间都正常
        // （那两项不依赖这个正则）—— 极容易误判成"文件是空的"。
        //
        // 这个 bug 长期没暴露，是因为**默认级别是 VERBOSE**，
        // 而那个分支当初恰好写成了带括号的形式；一旦用户切到 D/I/W/E/F
        // 就会走进裸写的分支。已实际踩过（用户报「TAG 统计始终为 0」）。
        val levelPat = if (levels.length < LogLevel.entries.size) "[$levels]" else "[VDIWEF]"

        // ⚠️⚠️ 这段 shell 是**本机跑通后才写进来**的，别凭直觉改引号。
        //
        // 它绕开了三个让统计**静默失败**（界面永远停在"统计中…"）的坑：
        //
        // 1. **`sh -c '...'` 的单引号里不能再出现单引号。**
        //    原来写 `sh -c '... awk '...' ...'` —— awk 的引号**提前闭合**了外层，
        //    整条命令被 shell 以语法错误拒绝，而界面上看不出命令根本没跑起来。
        //    → 现在**根本不用 `sh -c`**：脚本整段交给 `sh -c` 的 argv，
        //      内部一律用**双引号**，外层不含单引号。
        // 2. **不能用 `{ grep A; grep B; }` 分组分流输出。**
        //    第一个 grep 会把标准输入**读空**，第二个什么都拿不到 ——
        //    原来的症状正是 TOTAL 出来了、TAG 全丢。
        //    → 现在每条统计各自读临时文件，互不干扰。
        // 3. **TAG 提取不能用固定列偏移。**
        //    早先写 `substr($0,32)`，那一列落在**级别字符**上，
        //    提取出来是 `"W BroadcastQueue"`；pid/tid 位数一变整列就偏。
        //    → 现在按**形状**匹配 `级别 + TAG:`，与列宽无关。
        //
        // 另外**只 cat 一遍**到临时文件：全量可达 256MB，
        // 按统计项各读一遍就是 4 倍 I/O。
        //
        // ⚠️⚠️ **必须滤掉 logcat 的缓冲区标记行。**
        //
        // 采集文件的**第一行**通常不是日志，而是：
        //     --------- beginning of main
        // 直接 `head -1` 取它前 18 字符，得到的是 `--------- beginnin` ——
        // 真机上这就是状态栏里显示的那个值（用户截图实测）。
        //
        // 标记行的特征：**5 个以上短横开头**。用 `-v` 反选掉。
        //
        // ⚠️ **`-aE` 两个标志都不能省**，而且这次是**同一个坑踩了第二次**：
        //
        // - `-E`：`{5,}` 是**扩展正则**语法。`grep` 默认是 BRE，
        //   会把 `{5,}` 当**字面字符**，于是这条反选**完全匹配不到任何行** ——
        //   标记行原样留下。实测症状就是 `TOTAL` 比实际多 1、首行是
        //   `--------- beginnin`。
        //   （项目里 `buildSearch` 那条命令早就写过这个教训，这次写新命令时又忘了。）
        // - `-a`：日志是二进制安全的，不加时 grep 遇到 NUL 会当作二进制文件
        //   直接放弃处理。
        //
        // 正则用**双引号**而不是单引号 —— 整个脚本会被 `shellQuote` 再包一层，
        // 里面出现单引号会被再解析一次。`^-{5,}` 里没有 shell 特殊字符，双引号安全。
        val template = """
#!/system/bin/sh
F=$(ls -tr GLOBP 2>/dev/null)
[ -z "${'$'}F" ] && exit 0
T=TMPF
cat ${'$'}F </dev/null 2>/dev/null | grep -avE "^-{5,}" > ${'$'}T
printf "TOTAL %s\n" "$(wc -l < ${'$'}T)"
head -1 ${'$'}T | cut -c1-18 | sed "s/^/FIRST /"
tail -1 ${'$'}T | cut -c1-18 | sed "s/^/LAST /"
sed -nE "s/^[^ ]+ [^ ]+ +[0-9]+ +[0-9]+ +LEVELPAT([^:]+):.*/\1/p" ${'$'}T \
  | sort | uniq -c | sort -rn | head -500 \
  | sed -E "s/^ *([0-9]+) +(.+)$/TAG \2 \1/"
rm -f ${'$'}T
""".trimIndent()

        val script = template
            .replace("GLOBP", CAPTURE_GLOB)
            .replace("TMPF", STATS_TMP_FILE)
            .replace("LEVELPAT", levelPat)

        // ⚠️ 用 shellQuote 包整段脚本，而不是自己拼引号：
        // 这样脚本里的双引号不需要任何转义，也不会有嵌套问题
        return "timeout 60 sh -c ${shellQuote(script)}"
    }

    /**
     * **全量统计「当前过滤条件命中的行数」** —— 只回传一个数字。
     *
     * ## 为什么需要它
     *
     * 原先改级别 / TAG / 消息**只筛内存里那批行**（一次最多 `条数` 行），
     * 而页码却是按**未过滤**的总量算的。两者口径不同 →
     * 用户看到「共 4 页」，但每页里的命中数忽多忽少，页码与内容对不上。
     *
     * 现在改成：改条件后**先扫一遍全文件**拿到真实命中数 M，
     * 页数 = ⌈M ÷ 条数⌉。这样页数是真的，每页也真的装 `条数` 条命中。
     *
     * ## 为什么只回传数字（而不是命中位置索引）
     *
     * 记下每条命中的字节偏移就能"翻页时直接定位"，但命中可能上万条 ——
     * 索引本身几百 KB～几 MB，**跨进程回传会撞 Binder 上限、打死 UserService**
     * （真机实测过 `DeadObjectException`，见类注释第 1 条）。
     *
     * 只回传一个 count 恒定几十字节，代价是**翻页时可能还要再扫一次**。
     * 对这个工具（慢速排查，不是流畅浏览）来说这个取舍是合适的。
     *
     * ## 过滤条件与读取**完全同源**
     *
     * 用的是同一个 [buildSearchPattern]，所以"统计到的命中"与
     * "翻页时 grep 出来的行"口径必然一致 —— 不会出现
     * "统计说有 500 条，翻页只翻出 300 条"。
     *
     * @param tagQuery     TAG 关键字（空 = 不过滤）
     * @param messageQuery 消息关键字（空 = 不过滤）
     * @param minLevel     最低级别
     * @return 要执行的命令；输出形如 `MATCHES 12345`
     */
    fun buildFilteredCount(
        tagQuery: String = "",
        messageQuery: String = "",
        minLevel: LogLevel = LogLevel.VERBOSE,
    ): String {
        val pattern = buildSearchPattern(tagQuery, messageQuery, minLevel)

        // ⚠️ 结构与 buildCaptureStats 同源，绕开同样的三个坑：
        //   · 不用 `sh -c` 嵌套单引号（整段交给 shellQuote）
        //   · 不用 `{ grep A; grep B; }` 分流（第一个 grep 会把输入读空）
        //   · 不用固定列偏移
        //
        // ⚠️ 必须滤掉缓冲区标记行（`--------- beginning of main`），
        // 否则它们可能被算进命中数。
        //
        // ⚠️ `grep -c` 在无匹配时**输出 0 且退出码为 1**，
        // 而 `wc -l` 输出 0 且退出码 0 —— 这里用 `wc -l` 更稳：
        // 退出码非 0 会被 ShellManager 包成 `Error:`，让"0 条命中"看起来像失败。
        //
        // ⚠️ 走 `-aE`：`-E` 因为 pattern 是 ERE；`-a` 因为日志是二进制安全的。
        val inner = "grep -avE \"^-{5,}\" | " +
            "grep -aE${pattern.grepFlags} -e ${shellQuote(pattern.regex)} | wc -l"

        return "timeout 60 sh -c ${shellQuote("cat $CAPTURE_GLOB </dev/null 2>/dev/null | $inner")}"
    }

    /**
     * 解析 [buildFilteredCount] 的输出。
     *
     * ⚠️ 解析不出来返回 `null` 而不是 0 —— "不知道有多少" 与 "一条都没有"
     * 对界面的含义完全不同（前者不能显示页码，后者要提示放宽条件）。
     */
    fun parseFilteredCount(output: String): Int? =
        output.trim().lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.toIntOrNull()

    /**
     * 解析 [buildCaptureStats] 的输出。
     *
     * ⚠️ 解析失败一律返回 null 而不是抛异常或返回 0 ——
     * 界面宁可不显示统计，也不能显示一个**错的数字**
     * （这正是本功能要修的那个 bug）。
     */
    fun parseCaptureStats(output: String): CaptureStats? {
        var total = -1
        var first: String? = null
        var last: String? = null
        val tags = mutableListOf<Pair<String, Int>>()

        output.lineSequence().forEach { raw ->
            val line = raw.trim()
            when {
                line.startsWith("TOTAL ") ->
                    total = line.removePrefix("TOTAL ").trim().toIntOrNull() ?: -1

                line.startsWith("FIRST ") ->
                    first = line.removePrefix("FIRST ").trim().takeIf { it.isNotBlank() }

                line.startsWith("LAST ") ->
                    last = line.removePrefix("LAST ").trim().takeIf { it.isNotBlank() }

                line.startsWith("TAG ") -> {
                    val rest = line.removePrefix("TAG ")
                    val sp = rest.lastIndexOf(' ')
                    if (sp > 0) {
                        val name = rest.substring(0, sp)
                        val n = rest.substring(sp + 1).toIntOrNull()
                        if (n != null && name.isNotBlank()) tags.add(name to n)
                    }
                }
            }
        }

        if (total < 0) return null
        return CaptureStats(totalLines = total, firstTimestamp = first, lastTimestamp = last, tagCounts = tags)
    }
    fun buildTagStats(
        state: CaptureState,
        lines: Int = MAX_LINES,
        minLevel: LogLevel = LogLevel.VERBOSE,
    ): String? = when (state) {
        // 采集态与已完成态都只能读文件（App 进程读不到 shell 写的 /sdcard 路径，
        // 所以统一走 tail 由 shell 读）；文件本身是全量写入的，天然不带 TAG 过滤
        is CaptureState.Capturing -> buildSearch(minLevel = minLevel, limit = lines)
        is CaptureState.Completed -> buildSearch(minLevel = minLevel, limit = lines)
        is CaptureState.Idle -> buildSnapshotForTagStats(lines, minLevel)
        is CaptureState.Stale -> null
    }

    /** 行数夹取，防调用方传入越界值撞 Binder 上限。 */
    fun clampLines(lines: Int): Int = lines.coerceIn(1, MAX_LINES)

    /**
     * 单引号包裹一个 shell 参数。
     *
     * 用于 TAG 这类用户输入——**必须转义**，否则含引号/分号的 TAG
     * 会造成命令拼接错误甚至注入。
     * 做法：内部的单引号写成 `'\''`（结束引用 + 转义的单引号 + 重新开始引用）。
     */
    fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

/**
 * 一个 shell 检索用的正则 + grep 标志。
 *
 * 抽出来是为了**可单测**：正则拼错的表现是"搜不到东西"，
 * 而用户完全看不出是条件写错还是真的没有。
 */
/**
 * 一次**全量**统计的结果（见 [LogcatCommands.buildCaptureStats]）。
 *
 * 与读取不同，这些数字描述的是**整个采集文件**，不随后续的过滤/翻页变化 ——
 * 也正因如此，它才是"我看全了吗"这个问题的参照物。
 *
 * @param totalLines 全文件总行数（已应用级别过滤，与读取口径一致）
 * @param firstTimestamp 最早一行的时间戳 `MM-DD HH:MM:SS.mmm`
 * @param lastTimestamp 最晚一行的时间戳
 * @param tagCounts 各 TAG 行数，按行数降序
 */
data class CaptureStats(
    val totalLines: Int,
    val firstTimestamp: String?,
    val lastTimestamp: String?,
    val tagCounts: List<Pair<String, Int>>,
) {
    /** 覆盖时间段文案，如 `01:00:10–01:12:40`；拿不到完整两端时为 null。 */
    val rangeText: String?
        get() {
            val f = firstTimestamp?.let { timeOfDay(it) } ?: return null
            val l = lastTimestamp?.let { timeOfDay(it) } ?: return null
            return if (f == l) f else "$f–$l"
        }

    private fun timeOfDay(ts: String): String? {
        // 形如 "09-20 01:00:10.747" → 取 "01:00:10"
        val space = ts.indexOf(' ')
        if (space < 0) return null
        val time = ts.substring(space + 1)
        val dot = time.indexOf('.')
        val hms = if (dot > 0) time.substring(0, dot) else time
        return hms.takeIf { it.length >= 8 }
    }
}

internal data class SearchPattern(
    /** POSIX 扩展正则（`grep -E` 语法）。 */
    val regex: String,
    /** grep 的标志字符，如 `"i"` 表示大小写不敏感。 */
    val grepFlags: String,
)

/**
 * 采集状态（调试工具 §4.2.1）。
 *
 * **三态而非两态**——[Stale] 是必须单独处理的：App 被杀或进程崩溃后，
 * pidfile 还在但进程已死，仅靠"pidfile 存在与否"会误判成"正在采集"。
 */
sealed interface CaptureState {
    /** 空闲：pidfile 不存在。 */
    data object Idle : CaptureState

    /** 采集中：pidfile 存在且该 pid 存活。 */
    data class Capturing(val pid: Int) : CaptureState

    /**
     * 脏状态：pidfile 存在但 pid 已死。
     *
     * 处理方式：提示「上次采集异常结束」+ 让用户手动清理，
     * **不自动删**——用户可能想先看看那次采集到的日志。
     */
    data class Stale(val pid: Int) : CaptureState

    /**
     * 采集已完成（用户主动停止，或到点自动停止）。
     *
     * ## 为什么需要这个状态
     *
     * 停止后 pidfile 被删，若只看 pidfile 就会退回 [Idle] ——
     * 而 [Idle] 的数据源是**实时滚动的缓冲区**。
     * 后果是：用户刚采完一批日志，切个 TAG 过滤就变成了读"现在的日志"，
     * 刚采的那批内容凭空消失。
     *
     * 它和 [Idle] 的**唯一区别就是数据源**：
     *
     * | 状态 | 数据源 | 会变吗 |
     * |---|---|---|
     * | [Idle] | logcat 缓冲区 | ⚠️ 会（滚动窗口） |
     * | [Completed] | 采集文件 | ❌ 不会（已固定） |
     *
     * 「刷新」在 [Completed] 下**没有意义**（文件不再变化），
     * 因此界面上应当隐藏或禁用那个按钮 —— 改过滤条件只需在内存里重筛。
     */
    data object Completed : CaptureState
}

/**
 * 一次采集会话的**可重建状态**。
 *
 * ⚠️ 这个模型的全部意义在于：**它只依赖 pidfile 探测结果与开始时刻，
 * 不依赖任何内存里"我正在采集"的标志**。
 * App 被杀后日志还在采（调试工具文档 §4.2 边界 2），
 * 因此状态必须能从外部重建，而不能藏在内存的 `StateFlow` 里（§4.2.1）。
 *
 * @param state 当前状态（由 [LogcatCommands.parseProbeState] 得出）
 * @param startedAtMs 计时起点（毫秒）。**只在本次会话内有效**——
 *   pidfile 里没有开始时间，因此 App 重启后
 *   [CaptureState.Capturing] 而 [startedAtMs] 为 null 是**正常情况**，
 *   此时不显示计时，而不是显示一个错的时间。
 */
data class CaptureSession(
    val state: CaptureState,
    val startedAtMs: Long? = null,
) {
    /** 是否正在采集。 */
    val isCapturing: Boolean get() = state is CaptureState.Capturing

    /**
     * 已采集时长（毫秒）；无法计时时返回 null。
     *
     * 时钟回拨导致的负值也返回 null——显示 `00:00` 会让人以为刚开，
     * 显示负数则更糟。
     */
    fun elapsedMs(nowMs: Long): Long? {
        val start = startedAtMs ?: return null
        val elapsed = nowMs - start
        return if (elapsed >= 0) elapsed else null
    }

    /**
     * 是否已到时长上限，该由 App 侧主动停了。
     *
     * ⚠️ 这只是**双保险的第一层**。真正的兜底在 shell 侧（`timeout` 前缀）：
     * App 侧计时器会随 App 一起被杀，而采集恰恰设计成脱离 UI 存活。
     * 两层都不冲突——App 侧负责 UI 即时反馈，shell 侧负责无论死活都停
     * （见调试工具文档 §4.7）。
     */
    fun hasReachedLimit(nowMs: Long, timeoutSec: Int): Boolean {
        val elapsed = elapsedMs(nowMs) ?: return false
        return elapsed >= timeoutSec.coerceAtLeast(1) * 1000L
    }

    companion object {
        /** 空闲会话。 */
        val Idle = CaptureSession(CaptureState.Idle)
    }
}