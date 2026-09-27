package com.chaomixian.vflow.xposed

import android.util.Log

/**
 * hook 层的统一日志出口。
 *
 * ## ⚠️ 为什么不用本仓库的 `DebugLogger`
 *
 * `DebugLogger`（`com.chaomixian.vflow.core.logging`）是 App 侧的类，
 * 它需要 App Context / 文件路径等初始化。而本包的代码**运行在 system_server 里** ——
 * 引用它会把那个类（及其依赖闭包）拖进 system_server，
 * **把崩溃半径从「那个 App」扩大到整机**。
 *
 * 所以本包一律用 `android.util.Log`。这条约束由 `WireLayerPurityTest` 与
 * `HookPackagePurityTest` 以源码扫描方式锁住。
 *
 * ## ⚠️ 为什么每条都套 `try/catch`
 *
 * 早期回调（`onModuleLoaded` 等）里框架 API 可能尚未就绪，
 * `Log` 本身在极端情况下也可能抛（如 tag 超长）。日志失败绝不能反过来
 * 把被 hook 的原方法带崩 —— 那会破坏整机行为。
 *
 * ## ⚠️ 用 ERROR 级
 *
 * 探针实测：LSPosed 的框架日志**只持久化 Error 级**内容（INFO/WARN 看不到）。
 * 所以即使语义上是 debug 信息，也走 ERROR。
 */
internal object HookLog {

    const val TAG = "VFlowHook"

    fun e(msg: String) {
        try {
            Log.e(TAG, msg)
        } catch (_: Throwable) {
        }
    }

    fun e(msg: String, t: Throwable?) {
        try {
            Log.e(TAG, msg, t)
        } catch (_: Throwable) {
        }
    }

    /**
     * 带节流错误日志 —— 用于**可能高频触发**的路径（如 hook 回调）。
     *
     * ⚠️ 必要性：hook 回调每秒可能命中几十次，若每次都打日志，
     * 既拖慢 system_server，又会把 logcat 环形缓冲冲掉
     * （实测开机洪流 2 MiB 只够约 50 秒）。
     */
    private val lastLogAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun throttled(key: String, msg: String, intervalMs: Long = 5_000L) {
        val now = System.currentTimeMillis()
        val last = lastLogAt[key]
        if (last != null && now - last < intervalMs) return
        lastLogAt[key] = now
        e(msg)
    }
}
