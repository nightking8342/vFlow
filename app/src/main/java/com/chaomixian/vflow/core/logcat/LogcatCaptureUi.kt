package com.chaomixian.vflow.core.logcat

/**
 * logcat 采集的**展示层**：会话模型 + 计时/时长格式化。
 *
 * 与 [LogcatCommands]/[LogcatParser] 一样是纯函数，**无 Android 依赖，可单测**。
 * 之所以把会话模型从控制器里抽出来，是因为这里全是"改错了不报错、
 * 只让岛上显示不对或自动停止失灵"的地方——正是最该被测试锁住的部分。
 *
 * 设计文档：`docs/fork/logcat-debug-tool.md` §4.7、§5b。
 */
object LogcatCaptureUi {

    /**
     * 岛上的图标：系统的秒表动图。
     *
     * ⚠️ 这是 **SystemUI 内置的 Lottie 资源**，应用无需自备资源文件
     * （已真机实测；见调试工具文档 §5b.4）。
     * 走的是 Lottie 而非普通图片，因此岛上会**真的转起来**——
     * 前提是 `IslandTemplateBuilder` 写了 `autoplay = true`（它已按计时状态自动决定）。
     */
    const val ISLAND_ICON_KEY = "stopwatch_big"

    /**
     * 岛通知的稳定标识。
     *
     * 固定值 → 重复发送是**更新同一条通知**而非堆出多条
     * （`IslandNotifier.notificationIdFor` 由它派生通知 id）。
     */
    const val ISLAND_CACHE_KEY = "logcat_capture"

    /**
     * 岛存活时间相对采集时长上限的**余量**（秒）。
     *
     * 岛的 `islandTimeout` 是独立的兜底：App 若被杀，采集由 shell 侧 `timeout` 结束，
     * 但**没人再去取消岛通知**——用户会一直看到一条"正在采集"的岛。
     * 让它比采集上限晚一点自动消失，就能自愈。
     */
    const val ISLAND_TIMEOUT_MARGIN_SEC = 60

    /**
     * 岛存活时长（秒）。
     *
     * ⚠️ 单位是**秒**，而通知的 `timeout` 是**分钟**——官方协议里两者不同，
     * 弄混会让岛提前消失（见调试工具文档 §5b.3 坑 4）。
     */
    fun islandTimeoutSec(timeoutSec: Int): Int =
        timeoutSec.coerceAtLeast(1) + ISLAND_TIMEOUT_MARGIN_SEC

    /**
     * 把已采集时长格式化为 `MM:SS`（不足 1 小时）或 `H:MM:SS`。
     *
     * **正计时而非倒计时**：上限可调，倒计时会随改动跳变；
     * 且"我采了多久"比"还剩多久"更符合直觉（调试工具文档 §4.7）。
     *
     * 非正数（含时钟回拨导致的负值）一律显示 `00:00`，不显示负号。
     */
    fun formatElapsed(elapsedMs: Long): String {
        if (elapsedMs <= 0L) return "00:00"
        val totalSec = elapsedMs / 1000
        val hours = totalSec / 3600
        val minutes = (totalSec % 3600) / 60
        val seconds = totalSec % 60
        return if (hours > 0) {
            "%d:%02d:%02d".format(hours, minutes, seconds)
        } else {
            "%02d:%02d".format(minutes, seconds)
        }
    }

    /**
     * 采集时长的可读文案，如 `5分钟`。供界面与提示语拼装。
     *
     * 未满 1 分钟时用秒，避免出现"0分钟"这种读起来像没设上限的文案。
     */
    fun formatDuration(timeoutSec: Int): String {
        val safe = timeoutSec.coerceAtLeast(1)
        return if (safe < 60) {
            "${safe}秒"
        } else if (safe % 60 == 0) {
            "${safe / 60}分钟"
        } else {
            "${safe / 60}分${safe % 60}秒"
        }
    }
}
