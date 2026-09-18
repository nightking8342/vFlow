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
     * 「停止」：需要 Shell，且不能在刷新中。
     *
     * ⚠️ 即使已经在采集，也**不能**绕过 [refreshing]——
     * 所有 shell 操作共用同一条阻塞通道（§4.1.1），并发发起会互相干扰。
     * 停止虽然只是一条 `kill`，也得排队。
     */
    val canStop: Boolean get() = shellReady && !refreshing && capturing

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
