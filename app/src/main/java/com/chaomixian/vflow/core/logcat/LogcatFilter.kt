package com.chaomixian.vflow.core.logcat

/**
 * logcat 查看器的过滤条件与过滤逻辑。**纯函数，无 Android 依赖，可单测。**
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §4.1、§4.4、§4.5。
 *
 * ## 为什么过滤一律在 App 侧做
 *
 * | 数据源 | 过滤位置 | 原因 |
 * |---|---|---|
 * | 快照（空闲态） | 级别与 TAG **可下推**到 `-s` | 没有热更新需求，命令只在点击时生成一次 |
 * | **采集文件** | **必须 App 侧** | 采集文件永远全量写入（§4.3 要点 5） |
 *
 * 采集态之所以不下推，是为了**改过滤条件不用重跑命令**：
 * 数据已经在文件里，改条件只是重渲染，还能事后切换。
 *
 * 「排除本应用日志」同理不下推——`--pid` 是**白名单**语义用不上（§4.3 要点 4），
 * 而 `grep -v` 会让用户无法事后切换。
 */

/**
 * 过滤条件。
 *
 * @param minLevel 最低级别（含）。占位用的是「至少这么严重」的直觉，与触发器一致。
 * @param tagQuery TAG 关键字。**大小写不敏感的子串匹配**，空串表示不过滤。
 * @param showOwnApp 是否显示 vFlow 自己的日志。**默认显示**——调试工具里
 *   「看不到东西」比「看到太多」更难排查，且勾掉只是一个开关。
 * @param lineLimit 取多少行。是**上界而非精确值**（§4.4：`-T n` 会多出日志头标记行）。
 */
data class LogcatFilter(
    val minLevel: LogLevel = LogLevel.DEBUG,
    val tagQuery: String = "",
    val showOwnApp: Boolean = true,
    val lineLimit: Int = LogcatCommands.DEFAULT_LINES,
) {
    /** TAG 条件是否生效。 */
    val hasTagQuery: Boolean get() = tagQuery.isNotBlank()
}

/**
 * 在 App 侧按条件过滤已解析的日志行。
 *
 * ## ⚠️ 降级行为什么能自动跟着走
 *
 * 降级行（无 TAG 前缀的续行）**继承了上一条的 tag/level/pid**
 * （见 [LogcatParser] 的降级语义），因此对它施加同样的判定，
 * 结果与它的"母行"**必然一致**：
 *
 * - 母行被 TAG 条件筛掉 → 续行的 tag 相同 → 一起被筛掉（不会留下孤儿续行）
 * - 母行被级别筛掉 → 续行 level 相同 → 一起被筛掉
 * - 勾掉「显示本应用日志」→ 续行 pid 相同 → 一起被筛掉
 *
 * 这是继承语义带来的**结构性好处**，不需要为续行写任何特例。
 * 反过来说：**如果哪天把降级行的 tag 置空/level 置 `V`，这里立刻会碎**
 * （孤儿续行 + 续行被级别过滤静默丢弃），所以 [LogcatParserTest] 锁死了继承链。
 *
 * @param ownPids vFlow 自己的进程号集合（主进程与 `:core` 等）。
 *                仅在 [LogcatFilter.showOwnApp] 为 false 时使用。
 */
fun applyLogcatFilter(
    lines: List<LogcatLine>,
    filter: LogcatFilter,
    ownPids: Set<Int> = emptySet(),
): List<LogcatLine> {
    val query = filter.tagQuery.trim()
    return lines.filter { line ->
        if (!line.passesLevel(filter.minLevel)) return@filter false

        if (!filter.showOwnApp && line.pid in ownPids) return@filter false

        if (query.isNotEmpty() && !line.tag.contains(query, ignoreCase = true)) {
            return@filter false
        }

        true
    }
}

/**
 * 空列表的原因。**由纯函数判定、界面负责翻译成文案**——
 * 这样文案可以走字符串资源（三语言），而判定逻辑仍可单测。
 *
 * 设计文档 §4.6：各类空/异常态**必须分别给文案**，不要一律「没有日志」。
 * 其中「快照抓到空」是 §2.2 那个**静默失败**的对策，最需要说清楚。
 */
enum class LogcatEmptyReason {
    /** 空闲态抓到空：缓冲区可能已轮转。**这不是错误**，但需要引导。 */
    BufferRotated,

    /** 采集刚开，还没有日志产生。**完全正常**，不要报错。 */
    CaptureJustStarted,

    /** 采集异常结束（`STALE` 态）。 */
    CaptureStale,

    /** Shell 权限未就绪，压根没执行命令。 */
    ShellUnavailable,

    /** 命令超时。 */
    CommandTimeout,

    /** 有日志但**全被过滤条件筛掉了**。与"没日志"是两回事。 */
    FilteredOut,
}

/**
 * 判定空列表的原因。
 *
 * ⚠️ [filtered] 与 [raw] 必须分开传：
 * **「有日志但被筛掉」和「压根没有日志」给用户的下一步完全不同**——
 * 前者要提示放宽条件，后者要提示换数据源或开采集。
 * 把它们混为一谈（都显示「没有日志」）会让人白白去找权限问题。
 *
 * @param filterActive 当前是否有任何过滤条件生效
 */
fun diagnoseEmpty(
    state: CaptureState,
    filtered: List<LogcatLine>,
    rawCount: Int,
    filterActive: Boolean,
    shellAvailable: Boolean = true,
    timedOut: Boolean = false,
): LogcatEmptyReason? {
    if (filtered.isNotEmpty()) return null

    if (timedOut) return LogcatEmptyReason.CommandTimeout
    if (!shellAvailable) return LogcatEmptyReason.ShellUnavailable

    // 抓到过日志、只是被筛没了 —— 与"没有日志"必须区分
    if (rawCount > 0) return LogcatEmptyReason.FilteredOut

    return when (state) {
        is CaptureState.Stale -> LogcatEmptyReason.CaptureStale
        is CaptureState.Capturing -> LogcatEmptyReason.CaptureJustStarted
        is CaptureState.Idle -> LogcatEmptyReason.BufferRotated
    }
}

/**
 * 从采集文件的首末行取时间范围，如 `10:00:12–10:02:43`。
 *
 * 采集中额外显示它是为了让用户确认「这批覆盖哪段时间」——
 * 直接对应「我第 1 秒开、第 10 秒关，拿到的就是这 10 秒」的心智模型（§4.1.2）。
 *
 * @return 形如 `10:00:12–10:02:43`；**只有一行时返回单个时刻** `10:00:12`
 *         （此时显示成范围没有意义）；没有任何可解析的时间戳时返回 null
 */
fun captureTimeRange(lines: List<LogcatLine>): String? {
    val stamps = lines.asSequence()
        .filter { !it.isContinuation }
        .map { it.timestamp }
        .filter { it.isNotBlank() }
        .toList()
    if (stamps.isEmpty()) return null

    val first = stamps.first().timeOfDay() ?: return null
    val last = stamps.last().timeOfDay() ?: return null

    return if (first == last) first else "$first–$last"
}

/**
 * 从 `MM-dd HH:mm:ss.SSS` 里取出 `HH:mm:ss`。
 *
 * 取不到就返回 null，由调用方决定降级（而不是显示一个错的时间）。
 */
private fun String.timeOfDay(): String? {
    val space = indexOf(' ')
    if (space < 0 || length < space + 3 + 1) return null
    val time = substring(space + 1)
    val dot = time.indexOf('.')
    return if (dot > 0) time.substring(0, dot) else time
}
