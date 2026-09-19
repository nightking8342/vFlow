package com.chaomixian.vflow.services

import android.content.Context
import android.content.Intent
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logcat.CaptureSession
import com.chaomixian.vflow.core.logcat.CaptureState
import com.chaomixian.vflow.core.logcat.LogcatCaptureUi
import com.chaomixian.vflow.core.logcat.LogcatCommands
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.services.island.IslandAction
import com.chaomixian.vflow.services.island.IslandCapability
import com.chaomixian.vflow.services.island.IslandNotifier
import com.chaomixian.vflow.services.island.IslandTemplate
import com.chaomixian.vflow.services.island.ActionSlot
import com.chaomixian.vflow.services.island.TimerSpec
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * logcat 采集的状态机 + 计时 + 超级岛联动。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §4.2、§4.7、§5b。
 *
 * ## 为什么是进程级单例
 *
 * 采集**脱离 UI 存活**（用户可以离开 App 去复现问题，§4.2 边界 1），
 * 且「结束」按钮可能从岛的通知广播里触发（那时没有 Activity）。
 * 因此状态必须放在一个 Activity 与 Receiver 都能拿到的地方。
 *
 * ## ⚠️ 但内存状态**不是**判定的依据
 *
 * 真正的判定来源永远是 **pidfile + 进程存活**（[LogcatCommands.buildProbeState]）。
 * 内存里的 [session] 只用于**界面显示**（如"我是什么时候开始计的时"）。
 *
 * 这不是洁癖：App 被杀后 shell 侧的 logcat 还在跑，而内存里的一切都没了。
 * 若靠内存标志判断，重进 App 会看到"空闲"，用户再点一次「开始」就会
 * **同时跑起第二个 logcat 进程**——两个进程写同一个文件，互相覆写。
 *
 * 因此每次操作前都要 [probe]（§4.2.1 的调用时机表），
 * 探到的结果才是真相，内存状态只是缓存。
 */
object LogcatCaptureController {

    private const val TAG = "LogcatCapture"

    /** 计时刷新间隔。1 秒足够岛与界面上的正计时有"在走"的观感。 */
    private const val TICK_MS = 1000L

    /**
     * 每隔几次 tick 重新探测一次真实状态。
     *
     * 每次探测都是一次跨进程调用，因此不能每秒都探；
     * 但也不能不探——采集**意外死亡**（进程被系统杀）只有靠探测才能发现，
     * 否则界面会一直显示"采集中"（§4.2.1 的转 `STALE` 路径）。
     */
    private const val PROBE_EVERY_TICKS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _session = MutableStateFlow(CaptureSession.Idle)

    /**
     * 当前采集会话，供界面观察。
     *
     * ⚠️ 观察它可以驱动 UI，但**不能拿它做判定**——判定一律走 [probe]（见类注释）。
     */
    val session: StateFlow<CaptureSession> = _session.asStateFlow()

    /**
     * 一次性提示消息（出错、到点停止等），界面消费后应调 [clearMessage]。
     *
     * 用状态而非 Channel：Activity 可能正在后台，回来后仍应看到那条提示。
     */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * 计时循环的"当前时刻"，每秒推进一次；空闲时不推进。
     *
     * ⚠️ **不能靠重复赋值 [session] 来驱动界面重算时长**——
     * `StateFlow` 用 `equals` 去重，赋回同一个实例**不会发射**，
     * 界面上的正计时会停在原地不动。所以时间由这个独立的流承载。
     */
    private val _nowMs = MutableStateFlow(System.currentTimeMillis())
    val nowMs: StateFlow<Long> = _nowMs.asStateFlow()

    /** 时长上限（秒）。由界面下拉框设置，采集期间改动只影响下一次 [start]。 */
    @Volatile
    var timeoutSec: Int = LogcatCommands.DEFAULT_TIMEOUT_SEC

    private var tickerJob: Job? = null

    // ── 对外操作 ──────────────────────────────────────────────────

    /**
     * 探测真实状态并刷新内存状态。
     *
     * ⚠️ 调用时机见 §4.2.1：进入界面、点「开始」前、点「刷新」前。
     * **「点开始前」尤其不能省**——那是防重复启动的唯一手段。
     */
    suspend fun probe(context: Context): CaptureSession {
        val state = runCatching {
            LogcatCommands.parseProbeState(ShellManager.execShellCommand(context, LogcatCommands.buildProbeState()))
        }.getOrElse {
            DebugLogger.w(TAG, "状态探测失败", it)
            _message.value = "无法读取采集状态，请检查 Shell 权限"
            CaptureState.Idle
        }
        return applyState(state, context)
    }

    /**
     * 开始采集。
     *
     * 内部的探测是**防重复启动的关键**：内存状态可能是陈旧的
     * （例如 App 重启过），只有探测才知道 shell 侧是否已经跑着一个 logcat。
     */
    suspend fun start(context: Context) {
        val appContext = context.applicationContext

        // 先探再启——避免与残留的 logcat 进程抢同一个输出文件
        val current = probe(appContext)
        if (current.state is CaptureState.Capturing) {
            _message.value = "已经在采集中了"
            return
        }
        // STALE 不阻止启动，但也不静默吞掉——用户下次点「清理」才知道有这回事。
        // 启动本身会覆盖 pidfile，脏状态就此消失。

        val cmd = LogcatCommands.buildStartCapture(timeoutSec = timeoutSec)
        val result = runCatching {
            ShellManager.execShellCommandWithResult(appContext, cmd)
        }.getOrElse {
            DebugLogger.e(TAG, "开始采集失败", it)
            _message.value = "开始采集失败：${it.message ?: "未知错误"}"
            return
        }

        if (!result.success) {
            // 不静默失败（§4.2.2 边界 4）：最常见的两种原因是 Shizuku 未授权、
            // 以及 /sdcard/vFlow/ 下目标目录不存在导致 logcat 打不开文件。
            DebugLogger.w(TAG, "开始采集返回失败: ${result.output}")
            _message.value = "开始采集失败：${result.output.ifBlank { "Shell 未就绪或权限不足" }}"
            return
        }

        // 命令已立即返回（logcat 转后台），但 pidfile 是 shell 写的，
        // 再探一次拿到真实 pid，同时确认进程真的起来了。
        // 不能直接把 result 当成功——`&` 之后的失败（如路径不可写）不会反映在退出码上。
        val after = probe(appContext)
        val started = after.state
        if (started !is CaptureState.Capturing) {
            _message.value = "采集进程没有启动成功，请检查 Shell 权限与存储权限"
            return
        }

        _message.value = null
        DebugLogger.i(TAG, "采集已开始 pid=${started.pid}")
    }

    /**
     * 停止采集。幂等——重复调用（含岛按钮连点）不会出错。
     *
     * ⚠️ **必须幂等**：实测岛按钮连点 5 次会收到 5 次广播（§5b.3 坑 6）。
     *
     * 岛的处理分两步（见 [finishIsland]）：先定格展示，隔几秒再撤。
     */
    suspend fun stop(context: Context) {
        val appContext = context.applicationContext

        // 定格要用的总时长，必须在清零状态**之前**取——清零后就没人记得起点了
        val elapsedMs = _session.value.elapsedMs(System.currentTimeMillis())

        runCatching {
            ShellManager.execShellCommand(appContext, LogcatCommands.buildStopCapture())
        }.onFailure {
            DebugLogger.w(TAG, "停止采集失败", it)
            _message.value = "停止失败：${it.message ?: "未知错误"}"
        }
        // ⚠️ 置 **Completed 而不是 Idle**。
        //
        // `buildStopCapture` 会 `touch` 标记文件，磁盘上的真实状态就是"已完成"。
        // 若这里置成 Idle，内存与磁盘矛盾，而且后果很具体：
        // Idle 的数据源是**实时滚动的缓冲区**，于是刚停下这一小段时间里
        // 界面会去读缓冲区 —— 用户看到的不是自己刚采的那批。
        // （下一次探测会纠正成 Completed，但那个窗口里的首屏已经错了。）
        applyState(
            CaptureState.Completed,
            appContext,
            delayIslandDismiss = true,
            finishedElapsedMs = elapsedMs,
        )
    }

    /**
     * 放弃当前这批日志，回到空闲（读实时缓冲区）。
     *
     * [CaptureState.Completed] 态下用户点「放弃这批」时调用 ——
     * 删掉标记文件，数据源就切回缓冲区。
     *
     * 与 [clearStale] 的区别：那个是清理异常残留的 pidfile，
     * 这个是主动扔掉一份**完整的**采集结果。两者都把状态归到 Idle，
     * 但删的文件不同。
     */
    suspend fun releaseCapture(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            ShellManager.execShellCommand(appContext, LogcatCommands.buildClearDone())
        }.onFailure {
            DebugLogger.w(TAG, "放弃采集结果失败", it)
        }
        applyState(CaptureState.Idle, appContext)
    }

    /**
     * 删除采集文件（含轮转历史份），回到空闲态。
     *
     * 用户主动清理时调用。
     *
     * ⚠️ **不做定时自动清理**：采集文件是用户特意采下来排查问题的，
     * 有保留价值。只在**下一次开始采集**时清掉上一轮（见 `buildStartCapture`），
     * 那时旧数据确实没用了。
     */
    suspend fun deleteCaptureFiles(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            ShellManager.execShellCommand(appContext, LogcatCommands.buildDeleteCaptureFiles())
        }.onFailure {
            DebugLogger.w(TAG, "删除采集文件失败", it)
            _message.value = "删除失败：${it.message ?: "未知错误"}"
        }
        applyState(CaptureState.Idle, appContext)
    }

    /**
     * 清理脏状态（[CaptureState.Stale] 态下用户点「清理」）。
     *
     * 设计上**不自动清理**——用户可能想先看看那次异常结束前采集到的日志（§10 决策 2）。
     */
    suspend fun clearStale(context: Context) {
        val appContext = context.applicationContext
        runCatching {
            ShellManager.execShellCommand(appContext, LogcatCommands.buildClearStale())
        }.onFailure {
            DebugLogger.w(TAG, "清理脏状态失败", it)
            _message.value = "清理失败：${it.message ?: "未知错误"}"
        }
        applyState(CaptureState.Idle, appContext)
    }

    fun clearMessage() {
        _message.value = null
    }

    // ── 内部 ─────────────────────────────────────────────────────

    /**
     * 应用一次探测结果：更新状态、启动/停止计时、同步岛。
     *
     * 所有状态变更都走这里，保证「状态变了但岛没跟上」这种不一致不会发生。
     */
    private fun applyState(
        state: CaptureState,
        context: Context,
        /**
         * 是否让岛定格几秒再撤（用户主动停止时用）。
         *
         * 到点自动停**不走这条路**——那时用户多半不在看岛，
         * 让它直接消失更干净。
         */
        delayIslandDismiss: Boolean = false,
        /** 定格态要展示的总时长。仅在 [delayIslandDismiss] 为 true 时有意义。 */
        finishedElapsedMs: Long? = null,
    ): CaptureSession {
        val previous = _session.value
        val startedAtMs = when {
            // 仍在对同一个 pid 采集 → 保留原起点，计时不跳回 0
            state is CaptureState.Capturing && previous.state is CaptureState.Capturing &&
                previous.state.pid == state.pid -> previous.startedAtMs

            // 新开始的采集 → 从现在起计
            state is CaptureState.Capturing -> System.currentTimeMillis()

            else -> null
        }

        val next = CaptureSession(state, startedAtMs)
        _session.value = next

        when (state) {
            is CaptureState.Capturing -> {
                startTicker(context)
                showIsland(context, startedAtMs)
            }

            // 已完成：数据源固定为采集文件，不再有进程在跑。
            // 岛要撤掉（采集结束了），但不报"异常结束"
            CaptureState.Completed -> {
                stopTicker()
                if (delayIslandDismiss && IslandCapability.isAvailable()) {
                    finishIsland(context, finishedElapsedMs ?: 0L)
                } else {
                    cancelIsland(context)
                }
            }

            CaptureState.Idle, is CaptureState.Stale -> {
                stopTicker()
                // 无论之前有没有岛，都尝试取消——cancel 对不存在的通知是安全的，
                // 比"记录一个是否发过岛的标志"更不容易漏
                if (delayIslandDismiss && IslandCapability.isAvailable()) {
                    finishIsland(context, finishedElapsedMs ?: 0L)
                } else {
                    cancelIsland(context)
                }
                if (state is CaptureState.Stale) {
                    _message.value = "上次采集异常结束（进程已不存在），可清理后重新开始"
                }
            }
        }

        return next
    }

    /**
     * 启动计时循环。
     *
     * 两个职责：刷新已采集时长（驱动界面与岛上的正计时），
     * 以及**到点自动停**（§4.7 双保险的第一层，第二层是 shell 侧 `timeout`）。
     *
     * 注意岛上显示的时间**不需要每次重发通知**——`timerInfo.timerWhen` 是起点，
     * SystemUI 自己会往前走。重发只为在状态变化时刷新图标动效的开合。
     */
    private fun startTicker(context: Context) {
        if (tickerJob?.isActive == true) return

        tickerJob = scope.launch {
            var ticks = 0
            while (true) {
                delay(TICK_MS)
                ticks++

                if (!_session.value.isCapturing) break

                val now = System.currentTimeMillis()
                _nowMs.value = now

                val current = _session.value
                val limit = timeoutSec

                // 到时长上限：App 侧主动停（即时反馈）。
                // 即使这里没停（App 被杀），shell 侧的 timeout 也会停。
                if (current.hasReachedLimit(now, limit)) {
                    DebugLogger.i(TAG, "到达时长上限，自动停止采集")
                    stopAndReport(context, limit)
                    break
                }

                if (ticks % PROBE_EVERY_TICKS == 0) {
                    // 重新探测：采集进程是否还活着。
                    val probed = runCatching {
                        LogcatCommands.parseProbeState(
                            ShellManager.execShellCommand(context, LogcatCommands.buildProbeState())
                        )
                    }.getOrNull()

                    if (probed != null && probed !is CaptureState.Capturing) {
                        DebugLogger.i(TAG, "采集进程已结束（探测为 $probed），转出采集态")

                        // ⚠️ 探到 STALE 不等于"异常"：shell 侧 `timeout` 到点会结束进程，
                        // 而它**不会清理 pidfile**（`echo $!` 写的是 `timeout` 自己的 pid，
                        // 它退出后该 pid 就查不到了）→ 自然收尾同样留下 STALE。
                        //
                        // 区分靠"是否已到自己设的上限"：到了是正常收尾，
                        // 没到却死了才是异常（进程被系统杀）。
                        if (current.hasReachedLimit(now, limit)) {
                            stopAndReport(context, limit)
                        } else {
                            applyState(probed, context)
                        }
                        break
                    }
                }
            }
        }
    }

    /**
     * 自动停止并给出提示。
     *
     * ⚠️ **必须另起协程**：本方法的调用者在 [tickerJob] 里，
     * 而 [stop] 内部会 `tickerJob.cancel()`——即这个协程在取消**自己**。
     * 当前 tick 恰好没有挂起点所以不会立刻中断，但那太脆弱：
     * 以后只要在 [stop] 之后加一行带挂起点的代码就会被静默跳过。
     */
    private fun stopAndReport(context: Context, limit: Int) {
        scope.launch {
            stop(context)
            _message.value = "已到达时长上限 ${LogcatCaptureUi.formatDuration(limit)}，采集已停止"
        }
    }

    private fun stopTicker() {
        tickerJob?.cancel()
        tickerJob = null
    }

    /**
     * 发送/更新岛通知。
     *
     * 只在系统具备岛能力时才发——否则会平白多一条常驻通知，
     * 而普通通知并不能比界面提供更多信息。
     */
    private fun showIsland(context: Context, startedAtMs: Long?) {
        if (!IslandCapability.isAvailable()) return

        val now = System.currentTimeMillis()
        val template = IslandTemplate(
            cacheKey = LogcatCaptureUi.ISLAND_CACHE_KEY,
            content = context.getString(R.string.logcat_capture_island_content),
            // 起点未知（App 重启后 pidfile 里没有开始时间）时退化为「从现在开始计」，
            // 宁可计时不准也不要岛上空着一块
            timer = TimerSpec.countUpRunning(startedAtMs ?: now, now),
            iconKey = LogcatCaptureUi.ISLAND_ICON_KEY,
            actions = listOf(buildStopAction(context)),
            // 岛存活比采集上限晚一点自动消失：App 被杀时没人取消岛，
            // 让它自己到时消失是唯一的自愈手段（见 LogcatCaptureUi 的说明）
            islandTimeoutSec = LogcatCaptureUi.islandTimeoutSec(timeoutSec),
            // contentIntent 故意留空：打开查看器的 Activity 属实现第 5 步，
            // 现在指向一个还不存在的组件没有意义
        )

        IslandNotifier.show(
            context,
            IslandNotifier.notificationIdFor(LogcatCaptureUi.ISLAND_CACHE_KEY),
            template,
        )
    }

    /**
     * 定格展示已停止的岛，隔几秒再撤。
     *
     * ## 为什么不能直接 cancel
     *
     * 用户点了「结束」，如果岛立刻消失，他**看不到这次采了多久**，
     * 也分不清是自己停的、还是到点自动停的——两个结果对"这批日志覆盖哪段时间"
     * 的含义完全不同。定格一个暂停态的秒表是收尾的视觉确认。
     *
     * ## 为什么要有"撤"这一步
     *
     * 岛通知是 `setOngoing(true)`，**用户划不掉**。若只定格不撤，
     * 它就会永久占着通知栏——那比"啪一下消失"更糟。
     *
     * 用独立的协程延迟取消，是因为调用方（[stop]）可能正在
     * ticker 协程里，而 ticker 会随状态清零被取消（见 §4.2.3 坑 3）。
     */
    private fun finishIsland(context: Context, elapsedMs: Long) {
        val now = System.currentTimeMillis()
        val template = IslandTemplate(
            cacheKey = LogcatCaptureUi.ISLAND_CACHE_KEY,
            content = context.getString(R.string.logcat_capture_finished_content),
            // 暂停态：秒表定格在总时长上（`whenMs` 反向推算出起点，让显示值正好等于 elapsedMs）
            timer = TimerSpec.countUpPaused(now - elapsedMs, now),
            iconKey = LogcatCaptureUi.ISLAND_ICON_KEY,
            // 没有按钮——已经停了，再给个"结束"是误导
            actions = emptyList(),
            // 存活时长略长于定格时间，避免它在延迟取消之前自己先过期（观感上是闪一下）
            islandTimeoutSec = (LogcatCaptureUi.FINISHED_LINGER_MS / 1000).toInt() + 10,
        )

        IslandNotifier.show(
            context,
            IslandNotifier.notificationIdFor(LogcatCaptureUi.ISLAND_CACHE_KEY),
            template,
        )

        scope.launch {
            delay(LogcatCaptureUi.FINISHED_LINGER_MS)
            // 期间用户可能又开了一次采集——那就把岛留给新的那次，不要撤掉
            if (!_session.value.isCapturing) {
                cancelIsland(context)
            }
        }
    }

    /** 撤掉岛通知。对不存在的通知是安全的。 */
    private fun cancelIsland(context: Context) {
        IslandNotifier.cancel(
            context,
            IslandNotifier.notificationIdFor(LogcatCaptureUi.ISLAND_CACHE_KEY),
        )
    }

    /**
     * 岛的「结束」按钮。
     *
     * ⚠️ **必须是显式 Component 意图**，指向 Manifest 静态注册的接收器
     * （先例见 [WorkflowActionReceiver]）。用动态注册的接收器时，
     * 用户点击时它早已注销——表现是**按钮渲染正常、点着完全没反应**（§5b.3 坑 2）。
     * 这个坑已经实际踩过一次。
     */
    private fun buildStopAction(context: Context): IslandAction = IslandAction(
        slot = ActionSlot.PRIMARY,
        label = context.getString(R.string.logcat_capture_stop_action),
        // 单色剪影图标，小尺寸下最清晰；**不能用应用图标**（用户看不出那是"结束"）
        iconRes = R.drawable.rounded_stop_circle_24,
        actionIntent = Intent(context, LogcatActionReceiver::class.java).apply {
            action = LogcatActionReceiver.ACTION_STOP_CAPTURE
        },
    )
}
