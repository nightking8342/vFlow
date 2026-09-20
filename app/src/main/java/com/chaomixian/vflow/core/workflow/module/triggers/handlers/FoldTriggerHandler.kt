// 文件: main/java/com/chaomixian/vflow/core/workflow/module/triggers/handlers/FoldTriggerHandler.kt
// 描述: 折叠屏触发器处理器 —— 融合铰链角度与小米 device_posture 两路信号。
//
// 设计依据：docs/fork/fold-trigger-design.md
//   · §3.2 信号优先级：铰链角度（主力）> device_posture（校验）> DisplayListener（未实现，P2）
//   · §9.2 实测：device_posture 滞后 1–2 秒且半折值几乎不出现，故不能作主力
//
// 继承 BaseTriggerHandler 而非 ListeningTriggerHandler：需要自管状态机与去抖缓存，
// 与 PoseTriggerHandler 的选择理由一致（见其文件头注释）。
package com.chaomixian.vflow.core.workflow.module.triggers.handlers

import android.content.Context
import android.database.ContentObserver
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.module.normalizeEnumValue
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.module.triggers.FoldSignals
import com.chaomixian.vflow.core.workflow.module.triggers.FoldState
import com.chaomixian.vflow.core.workflow.module.triggers.FoldStateResolver
import com.chaomixian.vflow.core.workflow.module.triggers.FoldTriggerData
import com.chaomixian.vflow.core.workflow.module.triggers.FoldTriggerModule
import java.util.concurrent.CopyOnWriteArrayList

class FoldTriggerHandler : BaseTriggerHandler(), SensorEventListener {

    companion object {
        private const val TAG = "FoldTriggerHandler"

        /**
         * 小米私有 Settings.Global key。
         * 取值：0 未知 / 1 折叠 / 2 半折 / 3 展开（见设计文档 §1.1）。
         * 厂商私有协议收敛在本文件内，业务侧不出现该字符串。
         */
        private const val KEY_DEVICE_POSTURE = "device_posture"

        /** 非小米机型读取该 key 会返回 null/0，借此判断信号是否可用 */
        private const val POSTURE_UNKNOWN = 0
    }

    private var appContext: Context? = null
    private var sensorManager: SensorManager? = null
    private var hingeSensor: Sensor? = null
    private var postureObserver: ContentObserver? = null

    private val activeTriggers = CopyOnWriteArrayList<TriggerSpec>()
    private val resolver = FoldStateResolver()

    /** 最近一次读到的 device_posture（ContentObserver 与角度回调共用） */
    @Volatile
    private var latestPosture: Int? = null

    /** 是否已建立基线（用于避免服务重启误触发，见设计文档 §3.4） */
    private var primed = false

    // ---------------------------------------------------------------- 生命周期

    override fun start(context: Context) {
        super.start(context)
        appContext = context.applicationContext
        sensorManager = appContext?.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

        // API 30 起才有 TYPE_HINGE_ANGLE；设备无此硬件时 getDefaultSensor 返回 null
        hingeSensor = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            sensorManager?.getDefaultSensor(Sensor.TYPE_HINGE_ANGLE)
        } else {
            null
        }
        if (hingeSensor == null) {
            DebugLogger.w(TAG, "铰链角度传感器不可用，将降级依赖 device_posture")
        }

        reloadTriggers()
    }

    override fun stop(context: Context) {
        stopListening()
        activeTriggers.clear()
        resolver.reset()
        primed = false
        latestPosture = null
        appContext = null
        sensorManager = null
        hingeSensor = null
        super.stop(context)
    }

    override fun addTrigger(context: Context, trigger: TriggerSpec) {
        activeTriggers.removeAll { it.triggerId == trigger.triggerId }
        activeTriggers.add(trigger)
        reloadTriggers()
    }

    override fun removeTrigger(context: Context, triggerId: String) {
        activeTriggers.removeAll { it.triggerId == triggerId }
        reloadTriggers()
    }

    // ---------------------------------------------------------------- 监听启停

    /** 有触发器时才监听；无触发器即停，避免空转耗电（与 PoseTriggerHandler 同构）。 */
    private fun reloadTriggers() {
        if (activeTriggers.isEmpty()) {
            stopListening()
            return
        }
        startListening()
    }

    private fun startListening() {
        val ctx = appContext ?: return
        if (sensorManager == null && postureObserver == null) return

        // 信号 A：铰链角度（主力）
        if (hingeSensor != null) {
            sensorManager?.registerListener(this, hingeSensor, SensorManager.SENSOR_DELAY_NORMAL)
            DebugLogger.d(TAG, "已注册铰链角度传感器监听")
        }

        // 信号 B：小米 device_posture（校验/兜底）
        if (postureObserver == null) {
            readPosture(ctx) // 先读一次当前值
            postureObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    val c = appContext ?: return
                    readPosture(c)
                    // posture 变化也驱动一次推断（角度可能没变，但状态已变）
                    evaluate(System.currentTimeMillis())
                }
            }.also { observer ->
                runCatching {
                    ctx.contentResolver.registerContentObserver(
                        Settings.Global.getUriFor(KEY_DEVICE_POSTURE),
                        false,
                        observer,
                    )
                }.onFailure {
                    DebugLogger.w(TAG, "注册 device_posture 观察者失败: ${it.message}")
                }
            }
        }

        // 读一次当前状态作为基线 —— 不触发任何工作流（§3.4）
        if (!primed) {
            val snapshot = resolver.prime(
                FoldSignals(angleDegrees = null, miuiPosture = latestPosture),
                System.currentTimeMillis(),
            )
            if (snapshot != null) {
                primed = true
                DebugLogger.d(TAG, "已建立折叠状态基线: ${snapshot.state} (来源 ${snapshot.source})")
            }
        }
    }

    private fun stopListening() {
        sensorManager?.unregisterListener(this)
        postureObserver?.let { observer ->
            runCatching { appContext?.contentResolver?.unregisterContentObserver(observer) }
                .onFailure { DebugLogger.w(TAG, "注销 device_posture 观察者失败: ${it.message}") }
        }
        postureObserver = null
        primed = false
    }

    // ---------------------------------------------------------------- 传感器回调

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_HINGE_ANGLE) return
        val angle = event.values.firstOrNull() ?: return

        val ctx = appContext ?: return
        val transition = resolver.update(
            FoldSignals(angleDegrees = angle, miuiPosture = latestPosture),
            System.currentTimeMillis(),
        )

        if (transition != null) {
            DebugLogger.d(
                TAG,
                "折叠状态变迁: ${transition.from} -> ${transition.to} " +
                    "(角度 ${transition.snapshot.angleDegrees}, 来源 ${transition.snapshot.source})",
            )
            dispatch(ctx, transition.to, transition.snapshot.angleDegrees, transition.snapshot.source.serialized)
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    // ---------------------------------------------------------------- 内部分发

    private fun readPosture(context: Context) {
        latestPosture = runCatching {
            Settings.Global.getInt(context.contentResolver, KEY_DEVICE_POSTURE, POSTURE_UNKNOWN)
        }.getOrNull()
    }

    /** 由 ContentObserver 驱动的推断（角度回调之外的第二条路径）。 */
    private fun evaluate(nowMs: Long) {
        val ctx = appContext ?: return
        val transition = resolver.update(
            FoldSignals(angleDegrees = null, miuiPosture = latestPosture),
            nowMs,
        ) ?: return
        dispatch(ctx, transition.to, transition.snapshot.angleDegrees, transition.snapshot.source.serialized)
    }

    private fun dispatch(ctx: Context, state: FoldState, angle: Float, source: String) {
        val inputs = FoldTriggerModule().getInputs()
        val expected = state.serialized

        activeTriggers.forEach { trigger ->
            val raw = trigger.parameters[FoldTriggerModule.PARAM_FOLD_EVENT] as? String
                ?: return@forEach
            // 走模块定义的归一化，兼容旧值；不在 Handler 里硬编码文案（§5.2）
            val configured = inputs.normalizeEnumValue(FoldTriggerModule.PARAM_FOLD_EVENT, raw)
                ?: raw
            if (configured != expected) return@forEach

            DebugLogger.i(TAG, "条件满足, 触发工作流: ${trigger.workflowName} (状态 $expected, 来源 $source)")
            executeTrigger(
                context = ctx,
                trigger = trigger,
                triggerData = FoldTriggerData(
                    foldState = state.serialized,
                    angle = angle,
                    isFolded = state == FoldState.FOLDED,
                    isUnfolded = state == FoldState.UNFOLDED,
                    isHalfOpened = state == FoldState.HALF_OPENED,
                    postureSource = source,
                ),
            )
        }
    }
}
