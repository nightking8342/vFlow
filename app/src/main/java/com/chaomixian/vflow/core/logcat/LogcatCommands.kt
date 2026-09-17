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

    /** 采集写入的文件。 */
    const val CAPTURE_FILE = "/sdcard/vFlow/logs/logcat_capture.log"

    /** 采集进程的 pidfile。 */
    const val PID_FILE = "/sdcard/vFlow/temp/logcat_capture.pid"

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
     * @param rotateKb   单个文件的上限（KB）
     * @param rotateCount 保留的轮转文件数
     */
    fun buildStartCapture(rotateKb: Int = 1024, rotateCount: Int = 3): String =
        "$LOGCAT $VERBOSITY -r $rotateKb -n $rotateCount -f $CAPTURE_FILE " +
            ">/dev/null 2>&1 </dev/null & echo \$! > $PID_FILE"

    /**
     * 停止采集，并清理 pidfile。
     *
     * 即使 kill 失败（进程已死）也要删 pidfile，否则会留下
     * [CaptureState.STALE] 脏状态。
     */
    fun buildStopCapture(): String =
        "kill \$(cat $PID_FILE) 2>/dev/null; rm -f $PID_FILE"

    /**
     * 只清理脏状态（[CaptureState.STALE] 态下用户点「清理」）。
     *
     * 设计上**不自动清理**——用户可能想先看看那次异常结束前采集到的日志。
     */
    fun buildClearStale(): String = "rm -f $PID_FILE"

    /**
     * 判定当前采集状态。输出恒为一行，不会撞 Binder 上限。
     *
     * 输出格式：`IDLE` / `CAPTURING:<pid>` / `STALE:<pid>`
     *
     * ⚠️ 用 pidfile 精确匹配而非 `grep logcat`（类注释第 3 条）。
     * `ps -A -o PID=` 的写法参照项目内先例 `services/CoreLauncher.kt:268`。
     */
    fun buildProbeState(): String =
        "if [ -f $PID_FILE ]; then " +
            "pid=\$(cat $PID_FILE); " +
            "if ps -A -o PID= | grep -qw \"\$pid\"; then echo CAPTURING:\$pid; " +
            "else echo STALE:\$pid; fi; " +
            "else echo IDLE; fi"

    /**
     * 解析 [buildProbeState] 的输出。
     */
    fun parseProbeState(output: String): CaptureState {
        val s = output.trim().lineSequence().firstOrNull()?.trim().orEmpty()
        return when {
            s == "IDLE" -> CaptureState.Idle
            s.startsWith("CAPTURING:") -> CaptureState.Capturing(s.removePrefix("CAPTURING:").toIntOrNull() ?: -1)
            s.startsWith("STALE:") -> CaptureState.Stale(s.removePrefix("STALE:").toIntOrNull() ?: -1)
            // 无法识别时保守当作空闲——至少不会误报"正在采集"而阻止用户操作
            else -> CaptureState.Idle
        }
    }

    // ── 读取 ─────────────────────────────────────────────────────

    /**
     * 从采集文件尾部有界读取（**采集态**用）。
     *
     * 结果只有 [lines] 行，天然满足 Binder 上限。
     */
    fun buildTail(lines: Int = DEFAULT_LINES): String =
        "tail -n ${clampLines(lines)} $CAPTURE_FILE"

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
    ): String? = when (state) {
        is CaptureState.Idle -> buildSnapshot(lines, tag, minLevel)
        // 采集态：过滤在 App 侧做（采集文件永远全量写入），
        // 这样改过滤条件只需重渲染，不用重跑命令，也支持事后切换
        is CaptureState.Capturing -> buildTail(lines)
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
}
