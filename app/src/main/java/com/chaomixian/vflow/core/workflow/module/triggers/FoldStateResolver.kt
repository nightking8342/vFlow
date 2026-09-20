// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/FoldStateResolver.kt
// 描述: 折叠屏状态推断器 —— 纯函数状态机，无 Android 依赖，可纯 JVM 单测。
//
// 设计依据见 docs/fork/fold-trigger-design.md：
//   · 做「状态推断」而非「事件监听」（§0、§3.2）
//   · 以铰链角度为主力信号，小米 device_posture 降为校验（§9.2 问题 1：实测滞后 1–2 秒）
//   · 阈值按 MIX Fold 3 实测校准（§9.3）：折叠 <40°、展开 >150°、半折 40–150°
package com.chaomixian.vflow.core.workflow.module.triggers

/**
 * 折叠状态。
 *
 * [serialized] 是**与语言无关的稳定常量**，会被输出给魔法变量供下游条件判断使用。
 * 刻意不输出本地化文案 —— 否则用户切换系统语言后，已有的「如果」判断会失效。
 */
enum class FoldState(val serialized: String) {
    FOLDED("folded"),
    HALF_OPENED("half_opened"),
    UNFOLDED("unfolded");

    companion object {
        fun fromSerialized(value: String?): FoldState? =
            entries.firstOrNull { it.serialized == value }
    }
}

/** 状态判定所依据的信号来源，用于诊断（输出为 `posture_source`）。 */
enum class PostureSource(val serialized: String) {
    /** 铰链角度传感器（主力） */
    SENSOR("sensor"),

    /** 小米私有 `Settings.Global.device_posture`（校验/兜底） */
    MIUI("miui");

    companion object {
        fun fromSerialized(value: String?): PostureSource? =
            entries.firstOrNull { it.serialized == value }
    }
}

/**
 * 一次采样的原始信号。
 * 任一字段为 null 表示该信号当前不可用（传感器不存在 / 读取失败 / 尚未回调）。
 */
data class FoldSignals(
    /** 铰链角度（度）。不可用为 null。 */
    val angleDegrees: Float? = null,
    /** 小米 `device_posture` 原始值：0 未知 / 1 折叠 / 2 半折 / 3 展开。不可用为 null。 */
    val miuiPosture: Int? = null,
)

/** 当前状态的快照，经 [FoldTriggerData] 传递给 `execute`。 */
data class FoldSnapshot(
    val state: FoldState,
    /** 铰链角度；不可用时为 [FoldStateResolver.ANGLE_UNAVAILABLE]。 */
    val angleDegrees: Float,
    val source: PostureSource,
)

/** 状态变迁（边沿）。仅在状态**确实发生变化**时产生。 */
data class FoldTransition(
    val from: FoldState,
    val to: FoldState,
    val snapshot: FoldSnapshot,
)

/**
 * 折叠状态推断器。
 *
 * 职责：把多路原始信号融合成三态判定，并做去抖与边沿检测。
 * **只对状态变迁的边沿返回结果**，不做「每次采样都通知」。
 *
 * 迟滞（hysteresis）说明：进入与退出用**不同阈值**，避免角度在阈值附近抖动时反复触发。
 * 例如从 UNFOLDED 进入 HALF_OPENED 需跌破 [unfoldedExit]，而回到 UNFOLDED 需超过 [unfoldedEnter]。
 *
 * 本类**不引用任何 Android API**（时间由 [nowMs] 参数注入），因此可用纯 JVM 单测覆盖。
 * 这与 `PoseTriggerMath` / `AlarmTriggerScheduler` 的抽法一致。
 */
class FoldStateResolver(
    /** 角度低于此值判定为 FOLDED */
    private val foldedEnter: Float = DEFAULT_FOLDED_ENTER,
    /** 角度高于此值才从 FOLDED 退出（迟滞上沿） */
    private val foldedExit: Float = DEFAULT_FOLDED_EXIT,
    /** 角度高于此值判定为 UNFOLDED */
    private val unfoldedEnter: Float = DEFAULT_UNFOLDED_ENTER,
    /** 角度低于此值才从 UNFOLDED 退出（迟滞下沿） */
    private val unfoldedExit: Float = DEFAULT_UNFOLDED_EXIT,
    /** 候选状态需稳定持续多久才确认切换 */
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    /** HALF_OPENED 的额外确认时长：开合过程中会快速扫过半折区间，需停留足够久才算 */
    private val halfOpenConfirmMs: Long = DEFAULT_HALF_OPEN_CONFIRM_MS,
) {

    companion object {
        /** 角度不可用时的占位值（与设计文档 §4.2 的约定一致） */
        const val ANGLE_UNAVAILABLE = -1f

        /** 实测：折叠态 2–10°，留足余量（§9.3） */
        const val DEFAULT_FOLDED_ENTER = 40f
        const val DEFAULT_FOLDED_EXIT = 55f

        /** 实测：展开态 172–179°（§9.3） */
        const val DEFAULT_UNFOLDED_ENTER = 150f
        const val DEFAULT_UNFOLDED_EXIT = 135f

        /** 实测稳定期抖动仅 7°，故 300ms 去抖足够 */
        const val DEFAULT_DEBOUNCE_MS = 300L

        /** 半折需持续停留，避免慢速开合被误判为半折 */
        const val DEFAULT_HALF_OPEN_CONFIRM_MS = 500L
    }

    /** 已确认的当前状态；null 表示尚未建立基线 */
    private var confirmed: FoldState? = null

    /** 正在观察的候选状态 */
    private var candidate: FoldState? = null

    /** 候选状态首次出现的时间戳 */
    private var candidateSinceMs: Long = 0L

    /** 最近一次成功推断的快照（供无变化时也能查询当前状态） */
    var lastSnapshot: FoldSnapshot? = null
        private set

    /**
     * 建立基线：读入当前状态但**不产生边沿**。
     *
     * 服务启动/重启时必须先调用（或让首次 [update] 只建基线），
     * 否则每次服务重启都会误触发一轮工作流。见设计文档 §3.4。
     *
     * @return 建立的快照；信号不足无法判定时为 null
     */
    fun prime(signals: FoldSignals, nowMs: Long): FoldSnapshot? {
        val inferred = infer(signals, from = null) ?: return null
        confirmed = inferred.first
        candidate = inferred.first
        candidateSinceMs = nowMs
        val snapshot = snapshotOf(inferred.first, inferred.second, signals)
        lastSnapshot = snapshot
        return snapshot
    }

    /**
     * 送入一次采样。
     *
     * @return 状态发生变迁时返回该变迁；无变化、信号不足、或尚未满足去抖条件时返回 null
     */
    fun update(signals: FoldSignals, nowMs: Long): FoldTransition? {
        val inferred = infer(signals, from = confirmed) ?: return null
        val rawState = inferred.first
        val source = inferred.second

        // 尚未建立基线：本次仅建立基线，不触发（等价于 prime）
        val current = confirmed
        if (current == null) {
            confirmed = rawState
            candidate = rawState
            candidateSinceMs = nowMs
            lastSnapshot = snapshotOf(rawState, source, signals)
            return null
        }

        // 候选状态变化 -> 重新计时（不复用旧时间戳）。
        // 注意：这里**不能直接 return** —— 去抖为 0 时应当当场确认，需继续走下面的判定。
        if (rawState != candidate) {
            candidate = rawState
            candidateSinceMs = nowMs
        }

        // 与已确认状态相同 -> 无变迁（仅刷新快照）
        if (rawState == current) {
            lastSnapshot = snapshotOf(rawState, source, signals)
            return null
        }

        // 去抖：候选状态需持续足够久
        val elapsed = nowMs - candidateSinceMs
        if (elapsed < debounceMs) return null
        if (rawState == FoldState.HALF_OPENED && elapsed < halfOpenConfirmMs) return null

        confirmed = rawState
        val snapshot = snapshotOf(rawState, source, signals)
        lastSnapshot = snapshot
        return FoldTransition(from = current, to = rawState, snapshot = snapshot)
    }

    /** 清空全部内部状态（Handler 停止监听时调用）。 */
    fun reset() {
        confirmed = null
        candidate = null
        candidateSinceMs = 0L
        lastSnapshot = null
    }

    /**
     * 信号融合。优先级：**铰链角度 > 小米 posture**。
     *
     * 实测依据（§9.2 问题 1）：`device_posture` 滞后物理动作约 1–2 秒，
     * 而角度实时连续，因此角度优先。
     */
    private fun infer(signals: FoldSignals, from: FoldState?): Pair<FoldState, PostureSource>? {
        signals.angleDegrees?.let { angle ->
            return classifyByAngle(angle, from) to PostureSource.SENSOR
        }
        signals.miuiPosture?.let { posture ->
            mapMiuiPosture(posture)?.let { return it to PostureSource.MIUI }
        }
        return null
    }

    /** 角度 -> 状态，带迟滞（退出阈值与进入阈值不同）。 */
    private fun classifyByAngle(angle: Float, from: FoldState?): FoldState = when (from) {
        FoldState.FOLDED -> when {
            angle > unfoldedEnter -> FoldState.UNFOLDED
            angle > foldedExit -> FoldState.HALF_OPENED
            else -> FoldState.FOLDED
        }

        FoldState.UNFOLDED -> when {
            angle < foldedEnter -> FoldState.FOLDED
            angle < unfoldedExit -> FoldState.HALF_OPENED
            else -> FoldState.UNFOLDED
        }

        // 含 from == null（无基线）：直接用双侧阈值判定
        FoldState.HALF_OPENED, null -> when {
            angle < foldedEnter -> FoldState.FOLDED
            angle > unfoldedEnter -> FoldState.UNFOLDED
            else -> FoldState.HALF_OPENED
        }
    }

    /**
     * 小米 `device_posture` -> 状态。
     *
     * ⚠️ 实测（§9.2 问题 2）：值 2（半折）几乎不出现（150 采样中仅 2 次），
     * 因此它只作校验/兜底，**半折判定不能依赖它**。
     */
    private fun mapMiuiPosture(posture: Int): FoldState? = when (posture) {
        1 -> FoldState.FOLDED
        2 -> FoldState.HALF_OPENED
        3 -> FoldState.UNFOLDED
        else -> null // 0 = UNKNOWN / 非法值
    }

    private fun snapshotOf(
        state: FoldState,
        source: PostureSource,
        signals: FoldSignals,
    ): FoldSnapshot = FoldSnapshot(
        state = state,
        angleDegrees = signals.angleDegrees ?: ANGLE_UNAVAILABLE,
        source = source,
    )
}
