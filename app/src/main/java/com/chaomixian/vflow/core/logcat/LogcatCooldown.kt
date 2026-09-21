package com.chaomixian.vflow.core.logcat

/**
 * 触发器的冷却判定。**纯函数**（时间由调用方传入），无 Android 依赖，可单测。
 *
 * 设计文档：`docs/fork/logcat-trigger-design.md` §8。
 *
 * ## 为什么是「时间窗」而不是「签名去重」
 *
 * `ClipboardTriggerHandler` 用的是签名去重（内容相同则跳过）。
 * 那在剪贴板场景成立——同一份内容被反复复制没有意义。
 *
 * **logcat 场景不适用**：每次命中都是**新的日志行**，哪怕 TAG 与消息文字完全相同，
 * 也是"又发生了一次"（比如应用在循环重试）。按签名去重会把"第 2 次失败"
 * 当成"和上次一样"而丢掉，那正是用户最想知道的。
 *
 * 所以用时间窗：一段时间内只触发一次，窗口外照常触发。
 *
 * ## 冷却期内的命中「丢弃」而非「补发」
 *
 * 已与用户确认（文档 §8）：不排队、不补发。
 * 补发会导致「冷却结束后突然触发一串过期事件」，比丢掉更难理解。
 */
class LogcatCooldown(private val cooldownMs: Long) {

    /** 每个触发器独立的上次触发时刻。 */
    private val lastTriggeredAtMs = HashMap<String, Long>()

    /**
     * 判定并记账。
     *
     * **一次调用同时完成「判断」与「记账」**，不拆成两个方法——
     * 拆开会出现「判断完忘了记账」或「记了账但没触发」的错配，
     * 而这类错配表现为冷却失效或触发器再也不触发，都不好查。
     *
     * @param triggerId 触发器 id（各自独立计数，与 `ElementTriggerState` 一致）
     * @param nowMs 当前时刻（毫秒）
     * @return true = 允许触发（并已记账）；false = 冷却中，应丢弃
     */
    fun tryAcquire(triggerId: String, nowMs: Long): Boolean {
        // cooldownMs <= 0 表示不冷却
        if (cooldownMs <= 0L) return true

        val last = lastTriggeredAtMs[triggerId]
        if (last != null && nowMs - last < cooldownMs) return false

        lastTriggeredAtMs[triggerId] = nowMs
        return true
    }

    /**
     * 只判断不记账。
     *
     * 仅在需要预判时使用（如界面提示"冷却中"）；
     * **真正的触发路径必须用 [tryAcquire]**，否则会漏记账。
     */
    fun isCoolingDown(triggerId: String, nowMs: Long): Boolean {
        if (cooldownMs <= 0L) return false
        val last = lastTriggeredAtMs[triggerId] ?: return false
        return nowMs - last < cooldownMs
    }

    /** 清掉某个触发器的记录（触发器被删除时调用，防 map 无界增长）。 */
    fun forget(triggerId: String) {
        lastTriggeredAtMs.remove(triggerId)
    }

    /** 清空全部记录（[com.chaomixian.vflow.core.workflow.module.triggers.handlers.BaseTriggerHandler.stop] 时调用）。 */
    fun clear() {
        lastTriggeredAtMs.clear()
    }

    /** 当前记录数。供测试与诊断使用。 */
    internal fun trackedCount(): Int = lastTriggeredAtMs.size
}

/**
 * 默认冷却时长（毫秒）。
 *
 * 1000ms 的取值理由：日志触发往往是**突发**的（一次异常刷出几十行），
 * 1 秒足够把一次突发收敛成一次触发，又不至于让用户觉得"反应慢"。
 */
const val DEFAULT_LOGCAT_COOLDOWN_MS = 1000L
