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
        // ⚠️ 三件事的顺序有讲究：
        // 1. 先删 done 标记：否则新一轮刚开始时（pidfile 已写、进程还没起来），
        //    探测可能读到旧标记，把"采集中"误判成"已完成"
        // 2. 再删上一轮的**全部轮转文件**：否则新采集只覆盖主文件，
        //    `.1` `.2` … 里还是上一批的内容，检索时会把两批混在一起
        //    （实测踩过：`.4` 的时间比主文件早了 7 分钟）
        // 3. 最后启动
        "rm -f $DONE_FILE $CAPTURE_GLOB; " +
            "timeout ${timeoutSec.coerceAtLeast(1)} " +
            "$LOGCAT $VERBOSITY -r $rotateKb -n $rotateCount -f $CAPTURE_FILE " +
            // ⚠️ 先删掉上一轮的 done 标记：否则新一轮刚开始时
            // （pidfile 已写、进程还没起来）探测可能读到旧标记，
            // 把"采集中"误判成"已完成"
            ">/dev/null 2>&1 </dev/null & echo \$! > $PID_FILE"

    /**
     * 停止采集，并清理 pidfile。
     *
     * 即使 kill 失败（进程已死）也要删 pidfile，否则会留下
     * [CaptureState.STALE] 脏状态。
     */
    fun buildStopCapture(): String =
        "kill \$(cat $PID_FILE) 2>/dev/null; rm -f $PID_FILE; " +
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
        "du -sk $CAPTURE_GLOB 2>/dev/null | awk '{s+=\$1} END {print s+0}'"

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
     * @param limit 最多返回多少行
     */
    fun buildSearch(
        tagQuery: String = "",
        messageQuery: String = "",
        minLevel: LogLevel = LogLevel.VERBOSE,
        limit: Int = MAX_LINES,
    ): String {
        val pattern = buildSearchPattern(tagQuery, messageQuery, minLevel)

        // `ls -tr` 按时间正序（最旧在前），这样 grep 的输出天然是时间序
        val sources = "\$(ls -tr $CAPTURE_GLOB 2>/dev/null)"

        // ⚠️ `-E` **不是可选的**：pattern 里用了 `[ ]+`、`|`、`.*` 等 ERE 语法，
        // 而在基本正则（BRE，grep 默认）里 `+` 是**字面字符**而不是量词 ——
        // `[ ]+` 会去匹配"一个空格后跟一个加号"，永远匹配不到。
        //
        // 症状极其误导：采集文件好好地写着日志、命令也正常返回，
        // 只是**永远搜不到东西**，看起来像"采集不到日志"。
        // 已实际踩过（用户报「采集不到日志」）。
        return "cat $sources | grep -aE${pattern.grepFlags} -e ${shellQuote(pattern.regex)} " +
            "| tail -n ${clampLines(limit)}"
    }

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
    ): String? = when (state) {
        is CaptureState.Idle -> buildSnapshot(lines, tag, minLevel)
        // 采集态与已完成态都走 shell 侧检索。
        //
        // ⚠️ 过滤**下推到了 grep**，这与"采集文件永远全量写入"不冲突：
        // 文件里仍是全量，只是读的时候在 shell 侧收窄。
        // 不下推的话（原实现）只能读文件末尾 N 行，高频日志下
        // 1000 行只覆盖 0.35 秒，等于大部分内容搜不到。
        is CaptureState.Capturing -> buildSearch(tag.orEmpty(), messageQuery, minLevel, lines)
        is CaptureState.Completed -> buildSearch(tag.orEmpty(), messageQuery, minLevel, lines)
        is CaptureState.Stale -> null
    }

    /**
     * TAG 统计该跑哪条命令（§4.5）。
     *
     * **采样源同样按状态分流**（§4.1.1）：采集态下必须从**采集文件**读，
     * 而不是缓冲区——否则用户会奇怪「我明明在采集，统计出来怎么没有刚才那条」。
     *
     * ⚠️ 与 [buildRefresh] 的关键区别：**这里永远不带 TAG 过滤**。
     * 用户点「TAG 统计」的动机恰恰是"我不知道该过滤哪个 TAG"，
     * 用他猜的条件去统计等于让他自己回答自己的问题。
     * 级别过滤保留（「只看 W 以上有哪些 TAG」是合理诉求）。
     *
     * @return 要执行的命令；[CaptureState.Stale] 返回 null（应走提示而非执行）
     */
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