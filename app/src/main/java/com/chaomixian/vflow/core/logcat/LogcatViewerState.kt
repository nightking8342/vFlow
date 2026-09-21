package com.chaomixian.vflow.core.logcat

/**
 * 查看器界面状态的**纯函数部分**。
 *
 * 不含任何 Android / Compose 依赖，因此界面逻辑（尤其是"刷新按钮该不该禁用"
 * 这类容易写错的判断）可以脱离 UI 单测。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §4.1、§4.4、§4.6。
 */

/**
 * 一次「刷新」的结果。
 *
 * @param lines 过滤后的行（界面直接渲染这个）
 * @param rawLineCount 过滤**前**的行数。与 [lines] 一起用于区分
 *   「没日志」与「日志被筛没了」（§4.6）
 * @param continuationCount 降级行数。底部要显示它——这是本工具最有价值的提示：
 *   告诉用户"有 N 行没有 TAG 前缀，你配的 message 条件可能因此失配"
 * @param timeRange 采集中时的覆盖时间段，如 `10:00:12–10:02:43`
 * @param elapsedMs 本次命令耗时，用于底部展示
 * @param emptyReason 空结果的原因；非空时界面据此选文案
 */
data class LogcatViewerResult(
    val lines: List<LogcatLine>,
    val rawLineCount: Int,
    val continuationCount: Int,
    val timeRange: String?,
    val elapsedMs: Long,
    val emptyReason: LogcatEmptyReason?,
)

/**
 * 界面上的操作可用性。
 *
 * ⚠️ **命令执行期间必须禁用「刷新」**（§4.1.1）：Shizuku 通道是同步阻塞的
 * （`IShizukuUserService.exec`），并发发起多个 `exec` 会互相干扰。
 * 用单一 in-flight 标志位控制，**不排队**。
 *
 * 同理，采集的「开始」要防重复（§4.2.2 边界 5），
 * 而这个防备**必须落在界面上**——不能只靠控制器内部探测，
 * 因为用户在探测返回前就能再点一次。
 */
data class LogcatViewerActions(
    val shellReady: Boolean,
    val refreshing: Boolean,
    val capturing: Boolean,
    val stale: Boolean,
    val completed: Boolean,
) {
    /**
     * 数据源是否已固定（采集文件不再变化）。
     *
     * 此时「刷新」没有意义——重读一遍得到的是同一批内容。
     * 界面据此**隐藏刷新按钮**，改过滤条件走内存重筛即可。
     */
    val sourceIsFixed: Boolean get() = completed || stale

    /** 「刷新」：需要 Shell，且不能有命令在飞。`STALE` 态下不刷新（走提示）。 */
    val canRefresh: Boolean get() = shellReady && !refreshing && !stale

    /** 「开始」：需要 Shell，且当前不在采集。 */
    val canStart: Boolean get() = shellReady && !refreshing && !capturing

    /**
     * 「停止」：需要 Shell，且当前在采集。
     *
     * ⚠️ **刻意不依赖 [refreshing]**（这里改过一次，用户报的 bug）。
     *
     * 原先写的是 `shellReady && !refreshing && capturing`，理由是不与其它 shell
     * 命令并发（§4.1.1 那条同步阻塞通道）。但进入采集态时界面会**自动检索一次**
     * （为了让用户立刻看到已有日志进来），于是 `refreshing` 在「开始」之后
     * 立刻为真 —— **停止按钮从一开始就是灰的**，用户只能退出重进才恢复。
     *
     * 取舍：「必须能停下」的优先级高于「通道串行」。停止只是一条 `kill`，
     * 即使与读命令并发也不会破坏状态；而停不下来会让用户以为整个页面卡死。
     * 其余按钮仍按 [refreshing] 串行，它们没这个紧迫性。
     */
    val canStop: Boolean get() = shellReady && capturing

    /** 「清理」：仅 `STALE` 态可用（§10 决策 2：不自动清理）。 */
    val canClear: Boolean get() = shellReady && !refreshing && stale

    /** 「导出」：有内容才导。 */
    fun canExport(hasLines: Boolean): Boolean = hasLines
}

/**
 * 由界面状态推导操作可用性。
 */
fun buildViewerActions(
    state: CaptureState,
    shellReady: Boolean,
    refreshing: Boolean,
): LogcatViewerActions = LogcatViewerActions(
    shellReady = shellReady,
    refreshing = refreshing,
    capturing = state is CaptureState.Capturing,
    stale = state is CaptureState.Stale,
    completed = state is CaptureState.Completed,
)

/**
 * 组装一次刷新结果。
 *
 * ⚠️ **过滤前与过滤后的行数都要留着**：只看过滤后为空会得出「没有日志」，
 * 而真相可能是「抓到了 800 行，但全被筛掉了」。两者的下一步动作完全不同（§4.6）。
 */
fun buildViewerResult(
    raw: List<LogcatLine>,
    filter: LogcatFilter,
    state: CaptureState,
    ownPids: Set<Int>,
    elapsedMs: Long,
    shellAvailable: Boolean = true,
    timedOut: Boolean = false,
): LogcatViewerResult {
    val filtered = applyLogcatFilter(raw, filter, ownPids)

    return LogcatViewerResult(
        lines = filtered,
        rawLineCount = raw.size,
        continuationCount = LogcatParser.countContinuations(filtered),
        // 采集中与已完成都要显示时间范围：用户据此确认"这批覆盖哪段时间"。
        // 已完成时更是唯一能说明"这批是什么"的信息
        timeRange = if (state is CaptureState.Capturing || state is CaptureState.Completed) {
            captureTimeRange(filtered)
        } else null,
        elapsedMs = elapsedMs,
        emptyReason = diagnoseEmpty(
            state = state,
            filtered = filtered,
            rawCount = raw.size,
            filterActive = filter.hasTagQuery || filter.hasMessageQuery ||
                filter.minLevel != LogLevel.VERBOSE,
            shellAvailable = shellAvailable,
            timedOut = timedOut,
        ),
    )
}

/**
 * 底部状态栏的第一段：数据源说明。
 *
 * ⚠️ **两种数据源的语义不同，文案必须写出来**（§4.1.2）：
 * 缓冲区是**会变**的滚动窗口（两次刷新结果可能不同），
 * 采集文件在采集区间内**不变**。不说清楚，用户会以为工具不稳定。
 */
fun dataSourceLabel(state: CaptureState): String = when (state) {
    is CaptureState.Capturing -> "采集文件（采集中）"
    is CaptureState.Completed -> "采集文件（已固定）"
    is CaptureState.Stale -> "上次采集文件（异常结束）"
    is CaptureState.Idle -> "缓冲区（会变化）"
}

/**
 * 采集覆盖范围的描述。
 *
 * ## 为什么需要它
 *
 * 用户点「开始采集」、5 分钟后回来查，会**默认这批日志覆盖了整段时间**。
 * 但如果日志速率很高，采集文件的轮转会把早期内容挤掉 —— 实际只覆盖了最后十几秒。
 *
 * 这个落差必须显式告诉用户，否则他会得出"这个工具漏日志"的结论，
 * 而真相是"那部分内容已被轮转覆盖"。
 *
 * @param firstTimestamp 实际读到的最早一行的时间
 * @param lastTimestamp  实际读到的最晚一行的时间
 * @param truncatedByLimit 结果是否被行数上限截断（说明后面还有更多）
 */
data class CaptureCoverage(
    val firstTimestamp: String?,
    val lastTimestamp: String?,
    val truncatedByLimit: Boolean,
) {
    /** 是否有可显示的时间范围。 */
    val hasRange: Boolean get() = !firstTimestamp.isNullOrBlank() && !lastTimestamp.isNullOrBlank()

    /**
     * 范围文案，如 `09:13:26–09:13:36`。
     *
     * 只有一行时返回该时刻本身。
     */
    val rangeText: String?
        get() {
            if (!hasRange) return null
            val first = firstTimestamp!!.timeOfDay()
            val last = lastTimestamp!!.timeOfDay()
            return when {
                first == null || last == null -> null
                first == last -> first
                else -> "$first–$last"
            }
        }

    private fun String.timeOfDay(): String? {
        val space = indexOf(' ')
        if (space < 0) return null
        val time = substring(space + 1)
        val dot = time.indexOf('.')
        return if (dot > 0) time.substring(0, dot) else time
    }
}

/**
 * 从检索结果算出覆盖范围。
 *
 * ⚠️ `truncatedByLimit` 的判断依据是"结果行数达到了上限" ——
 * 那说明 shell 侧的 `tail -n` 截断过，后面还有更多内容。
 * 不告诉用户的话，他会以为这就是全部。
 */
fun buildCoverage(
    /**
     * 用于计算范围的行。
     *
     * ⚠️ **应传未经过滤的原始行**，不是展示用结果。
     * 覆盖范围描述的是"这批采集留下了哪一段"，
     * 不该因为它此刻的过滤条件而变 —— 那是两码事。
     * （踩过：筛选后只剩降级行时，rangeText 为 null，
     * 界面上的覆盖范围整行消失，看起来像"没有覆盖范围"）
     */
    lines: List<LogcatLine>,
    limit: Int,
    /**
     * 结果是否被行数上限截断。
     *
     * ⚠️ 由调用方传入而非从 `lines.size >= limit` 推断：
     * 传入的是**原始行**时这个推断就不成立了。
     */
    truncated: Boolean = lines.size >= limit,
): CaptureCoverage {
    val stamped = lines.filter { !it.isContinuation && it.timestamp.isNotBlank() }
    return CaptureCoverage(
        firstTimestamp = stamped.firstOrNull()?.timestamp,
        lastTimestamp = stamped.lastOrNull()?.timestamp,
        truncatedByLimit = truncated,
    )
}

// ── 上方信息区的折叠 ────────────────────────────────────────────

/**
 * 上方信息区（Shell 状态 / 采集 / 过滤 / 查找栏 / A 行）的折叠量。
 *
 * ## 交互模型
 *
 * A 行（条数 / 检索 / 清空 / 删除 / 换行 / 搜索）**是这一带的一部分**，
 * 它跟着上方内容一起上移 —— 而不是固定不动、由上方内容去"挤压"它。
 * 这一点是用户明确纠正过的：早先的实现把 A 行钉死在外层，于是
 * 上方内容被压扁时紧挨 A 行的那一条先消失，且 A 行收尽后摸不到滚动手势。
 *
 * 上界不是"内容总高"，而是 **内容总高 − A行高** ——
 * 收尽时正好只剩 A 行贴住标题栏，这正是要达成的终态。
 *
 * ## 为什么基准值必须独立于当前折叠量
 *
 * 可见高度 = 内容高度 − 折叠量。若每帧从"当前可见高度"反推内容高度，
 * 会把**被裁掉的部分当成不存在** —— 于是每帧都丢一点，手指还按着
 * 就已经悄悄收光了。
 *
 * @param contentHeightPx 上方带的**完整**内容高度（不受折叠影响）
 * @param maxCollapsePx 折叠上界 = 内容总高 − A行高
 * @param collapsedPx 已收起的像素数，`0` = 完全展开
 */
data class UpperCollapse(
    val contentHeightPx: Int,
    val maxCollapsePx: Float,
    val collapsedPx: Float,
) {
    /** 上方区当前应有的可见高度。 */
    val visibleHeightPx: Int
        get() = (contentHeightPx - collapsedPx).coerceAtLeast(0f).toInt()

    /** 收起进度，`0f`（全展开）～`1f`（只剩 A 行）。 */
    val progress: Float
        get() = if (maxCollapsePx <= 0f) 1f else (collapsedPx / maxCollapsePx).coerceIn(0f, 1f)

    /** 是否已完全收起（A 行上方再无内容）。 */
    val isFullyCollapsed: Boolean
        get() = maxCollapsePx <= 0f || collapsedPx >= maxCollapsePx
}

/**
 * 折叠上界：内容总高减去 A 行高。
 *
 * 收尽时可见高度恰好等于 A 行高，也就是"A 行贴住标题栏"。
 * A 行还没测量出来时返回 0 —— 此时**不许折叠**，否则会把 A 行一起收走。
 */
fun maxUpperCollapse(contentHeightPx: Int, actionRowHeightPx: Int): Float {
    if (contentHeightPx <= 0 || actionRowHeightPx <= 0) return 0f
    return (contentHeightPx - actionRowHeightPx).coerceAtLeast(0).toFloat()
}

/**
 * 应用一次「在上方带上拖动」。
 *
 * ⚠️ `dragDeltaY` 的方向约定：Compose 的 `detectVerticalDragGestures`
 * 给的是**手指位移**，向下为正。手指下滑 = 把上方区拉回来（折叠量减少）。
 */
fun advanceUpperCollapse(
    collapsedPx: Float,
    dragDeltaY: Float,
    maxCollapsePx: Float,
): Float {
    if (maxCollapsePx <= 0f) return 0f
    val next = collapsedPx - dragDeltaY
    // ⚠️ 两端都要夹住：不夹上界会把 A 行也收走（屏幕只剩日志区），
    // 不夹下界会让内容下方出现空白
    return next.coerceIn(0f, maxCollapsePx)
}

/**
 * 应用一次「日志区到顶后继续下拉」。
 *
 * 返回 新的折叠量 与 **本次被消费掉的位移**。
 *
 * ⚠️ 必须回报消费量：不回报的话，嵌套滚动会认为这段位移没人要，
 * 于是继续向上抛给更外层 —— 表现是"日志到顶后继续下拉，整页跟着弹动"。
 *
 * @return `first` = 新折叠量；`second` = 本次消费的位移（≥0）
 */
fun consumePullToExpand(
    collapsedPx: Float,
    availableY: Float,
    maxCollapsePx: Float,
): Pair<Float, Float> {
    // availableY ≤ 0 表示手指在上推（或没有剩余位移）——那是"滚动日志"，不归这里管
    if (availableY <= 0f || collapsedPx <= 0f || maxCollapsePx <= 0f) {
        return collapsedPx to 0f
    }
    val next = (collapsedPx - availableY).coerceAtLeast(0f)
    return next to (collapsedPx - next).coerceAtMost(availableY)
}

/**
 * 松手后的吸附。
 *
 * ⚠️ 这里**曾经是"过半收尽、否则弹回"的两段吸附**，用户明确反馈不要：
 * 「目前日志内容上方的滚动不能停留在中间，要么全部展开，要么滚动到顶部。
 * 能不能实现随便停在中间？」
 *
 * 两段吸附对**按钮式**折叠合适（点击=意图明确），但对**拖拽**不合适：
 * 拖动本身就是在连续地表达位置，用户停在哪就是想停在哪。
 * 强制吸附会让"我只想让它让出半屏"这个很自然的需求无法实现。
 *
 * 现在只保留一条**防误触**规则：几乎没动（< 2%）时弹回 ±0，
 * 因为那多半是点击时的手抖，而不是想把上方区挪开 1 像素。
 *
 * @return 夹在 `[0, maxCollapsePx]` 内的最终折叠量
 */
fun snapUpperCollapse(collapsedPx: Float, maxCollapsePx: Float): Float {
    if (maxCollapsePx <= 0f) return 0f
    val clamped = collapsedPx.coerceIn(0f, maxCollapsePx)
    // 只在"几乎没动"时弹回，其余位置**原样保留**（随便停在中间）
    return if (clamped < maxCollapsePx * 0.02f) 0f else clamped
}

// ── 滚动轴拖动预览 ───────────────────────────────────────────────

/**
 * 拖动滚动轴时，把手指的 y 位置换算成列表下标。
 *
 * ## 为什么不用系统 Scrollbar
 *
 * Compose 没有内置"可拖动 + 带预览"的滚动条。用户要的是**预览时间**：
 * 「当我按着滚动轴拖动时，可以预览当前位置的时间」——
 * 这需要在拖动过程中实时读出目标行的 `timestamp` 并显示出来，
 * 所以必须自己画。
 *
 * @param dragY 手指相对轨道顶部的像素位置
 * @param trackHeightPx 轨道总高
 * @param itemCount 列表项数
 * @return 目标下标，已夹到 `[0, itemCount-1]`
 */
fun scrollIndexForDrag(dragY: Float, trackHeightPx: Float, itemCount: Int): Int {
    if (itemCount <= 0) return 0
    if (itemCount == 1 || trackHeightPx <= 0f) return 0
    val ratio = (dragY / trackHeightPx).coerceIn(0f, 1f)
    return (ratio * (itemCount - 1)).toInt().coerceIn(0, itemCount - 1)
}

/**
 * 列表下标 → 滑块在轨道上的 y 位置（未拖动时用来画滑块）。
 *
 * ⚠️ 分子分母都按 `itemCount - 1` 算（而不是 `itemCount`）：
 * 这样首项落在 0、末项落在 `trackHeight - thumbHeight`，
 * 两端都被**用完**。用 `itemCount` 的话滑块永远到不了底部，
 * 看起来像"还有没滚完的内容"。
 *
 * @param thumbHeightPx 滑块自身高度（它占掉的那段行程要扣掉）
 */
fun thumbYForScrollIndex(
    index: Int,
    trackHeightPx: Float,
    itemCount: Int,
    thumbHeightPx: Float,
): Float {
    if (itemCount <= 1 || trackHeightPx <= 0f) return 0f
    val travel = (trackHeightPx - thumbHeightPx).coerceAtLeast(1f)
    val ratio = (index.toFloat() / (itemCount - 1)).coerceIn(0f, 1f)
    return ratio * travel
}

/**
 * 滑块高度（按时长比例）。
 *
 * 列表很长时滑块会很小，所以要给一个下限 —— 太小的滑块按不住。
 */
fun thumbHeightForTrack(
    trackHeightPx: Float,
    visibleCount: Int,
    itemCount: Int,
    minThumbPx: Float,
): Float {
    if (trackHeightPx <= 0f || itemCount <= 0) return 0f
    val ratio = (visibleCount.toFloat() / itemCount).coerceIn(0f, 1f)
    return (trackHeightPx * ratio).coerceIn(minThumbPx.coerceAtMost(trackHeightPx), trackHeightPx)
}

// ── 查找（与筛选不同）────────────────────────────────────────────

/**
 * 查找模式：**不改动结果集，只标出匹配的行**。
 *
 * ## 与「消息筛选」的区别
 *
 * | | 筛选 | 查找 |
 * |---|---|---|
 * | 不匹配的行 | **消失** | 保留 |
 * | 能否看到上下文 | ❌ 看不到 | ✅ 看得到 |
 * | 能否下推 shell | ✅ 能（grep） | ❌ 不能 |
 *
 * 这两个诉求是**互斥**的，而**上下文恰恰是 logcat 调试的核心**——
 * 一个崩溃堆栈，看 `NullPointerException` 那一行没用，
 * 要看它前后发生了什么。所以两者都要，但必须分开。
 *
 * ⚠️ 因此查找**不下推 grep**（下推了不匹配的行就没了），
 * 只在已加载的结果内做，快且不跑命令。
 */
data class LogcatSearch(
    /** 查找关键字；空串表示未启用查找 */
    val query: String = "",
    /** 大小写敏感？默认不敏感，与筛选口径一致 */
    val caseSensitive: Boolean = false,
) {
    val isActive: Boolean get() = query.isNotBlank()

    /** 该行是否命中查找。 */
    fun matches(line: LogcatLine): Boolean =
        isActive && line.raw.contains(query, ignoreCase = !caseSensitive)
}

/**
 * 查找的结果摘要。
 *
 * @param matchIndices 命中行在列表中的下标（用于跳转）
 * @param currentIndex 当前定位到第几个命中（从 0 起）；无命中时为 -1
 */
data class LogcatSearchResult(
    val matchIndices: List<Int>,
    val currentIndex: Int,
) {
    val matchCount: Int get() = matchIndices.size
    val hasMatches: Boolean get() = matchIndices.isNotEmpty()

    /** 当前命中在列表中的行下标；无命中时为 null */
    val currentLineIndex: Int?
        get() = matchIndices.getOrNull(currentIndex)

    /** 展示用，如 `3/17`。 */
    fun positionLabel(): String =
        if (hasMatches) "${currentIndex + 1}/$matchCount" else ""
}

/**
 * 在结果集里执行查找。
 *
 * @param lines 当前展示的行（查找**不改变**它，只标出命中）
 * @param currentIndex 上次定位的位置；切换关键字时应重置为 0
 */
fun runLogcatSearch(
    lines: List<LogcatLine>,
    search: LogcatSearch,
    currentIndex: Int = 0,
): LogcatSearchResult {
    if (!search.isActive) return LogcatSearchResult(emptyList(), -1)

    val indices = lines.indices.filter { search.matches(lines[it]) }
    if (indices.isEmpty()) return LogcatSearchResult(emptyList(), -1)

    // 越界时夹回范围内 —— 换关键字后行数变少，旧的 index 可能失效
    return LogcatSearchResult(indices, currentIndex.coerceIn(0, indices.size - 1))
}

/**
 * 查找里跳到下一个/上一个命中。
 *
 * **循环跳转**：走到末尾再点会回到第一个。比"到底了就停住"更好用 ——
 * 用户不需要知道自己在第几个，一直点总能找到想看的。
 */
fun LogcatSearchResult.advance(step: Int): LogcatSearchResult {
    if (!hasMatches) return this
    val next = (currentIndex + step).mod(matchCount)   // mod 保证负数也落回正区间
    return copy(currentIndex = next)
}
