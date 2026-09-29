package com.chaomixian.vflow.services

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.xposed.HookChannelController
import com.chaomixian.vflow.xposed.IHookCallback
import com.chaomixian.vflow.xposed.IHookHost

/**
 * Xposed 通道的 App 侧端点：被 hook 层（注入 system_server）`bindService` 连上来。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §4.2.2 / §4.2.4 / §4.4。
 *
 * ## ⚠️ 本 Service 必须是「可 bind 的」
 *
 * 现有 vFlow 的所有 Service（含 `TriggerService`）`onBind` **都返回 null** ——
 * 它们靠 `startService` / 前台服务存活，不需要 binder。
 * 本类是**第一个真正返回 binder 的 Service**。探针当初也是因为这个，
 * 不得不自造一个 `FakeVFlowService`。
 *
 * ## ⚠️ 不要抄 `TriggerService` 的 `startForeground`
 *
 * 它**不需要前台服务**：被 system_server bind 这一事实本身就让 AMS 保着 App 进程，
 * 加 `startForeground` 只会白白引入一条常驻通知 + 额外的 FGS 权限负担。
 *
 * ## ⚠️ 鉴权：token 是必需项，`HOOK_CONTROL` 是加分项
 *
 * Service 挂 `android:permission="com.chaomixian.vflow.permission.HOOK_CONTROL"`
 * （`signature` 级）。**system_server 能否持有本 App 的 signature 权限至今未定论**
 * （探针用 `checkPermission(自己pid,自己uid)` 的方法无效，恒 GRANTED，§8-#15）——
 * 见 [BinderTransport.doBind] 里那条失败日志。
 *
 * 因此设计上**不依赖它**：
 * - 挂了权限且 bind 成功 ⇒ 下行加固生效（多一道）；
 * - bind 失败 ⇒ 去掉 `android:permission`，**下行纯靠 token**；
 * - **上行真伪一律由 token 判定**（信封里的 `token` 字段）。
 *
 * ⚠️ **`Binder.getCallingUid() == 1000` 不能作为放行依据** ——
 * 它区分不了「system_server 本人」与「任何 uid 1000 的东西」。只用于诊断。
 */
class HookChannelService : Service() {

    companion object {
        private const val TAG = "HookChannelService"
    }

    private val hostBinder = HookHostBinder()

    /**
     * 本 Service 最近一次接到的 callback。
     *
     * ⚠️ 存在理由（修缺陷 13）：`onUnbind` / `onDestroy` 要能说清
     * 「**是哪条连接**在断开」—— 不带身份地清空会把（可能更晚注册的）
     * 新连接一起清掉。见 [HookChannelController.onCallbackUnregistered]。
     */
    @Volatile
    private var lastCallback: IHookCallback? = null

    /**
     * AIDL 实现。**所有回调都可能跑在 binder 线程上**（不是主线程）。
     *
     * ⚠️ 它们只做「读调用方 uid + 投递给 Controller」，不做耗时操作。
     */
    private inner class HookHostBinder : IHookHost.Stub() {

        override fun registerCallback(callback: IHookCallback?): Boolean {
            // ⚠️ **第一行**读 callerUid —— 它只在当前 binder 事务内有效，
            // 一旦进协程 / 跨 suspend 点就取不到了。
            // 只用于**诊断**，不作为放行依据（见类注释）。
            val callerUid = try {
                Binder.getCallingUid()
            } catch (_: Throwable) {
                -1
            }
            val callerPid = try {
                Binder.getCallingPid()
            } catch (_: Throwable) {
                -1
            }

            if (callback == null) {
                DebugLogger.w(TAG, "registerCallback 收到 null callback，拒绝")
                return false
            }

            DebugLogger.i(TAG, "hook 层已连接：callerUid=$callerUid callerPid=$callerPid")
            if (callerUid != 1000) {
                // 不拒绝，只告警：uid 1000 是预期值（system_server），
                // 但**不能**据此放行或拒绝 —— 见类注释
                DebugLogger.w(TAG, "⚠️ callerUid=$callerUid 非 1000，与预期的 system_server 不符")
            }

            lastCallback = callback
            return HookChannelController.onCallbackRegistered(callback)
        }

        override fun report(envelopeJson: String?) {
            if (envelopeJson.isNullOrBlank()) return
            // 信封解析、topic 路由、token 校验全在 Controller 里做（有单测）
            HookChannelController.onReport(envelopeJson)
        }

        /**
         * ③ 的配对响应（§3.3 / §3.4）。
         *
         * ⚠️ 与 [report] 走**同一个 App 侧 binder**，所以**鉴权规格必须相同** ——
         * 见 [IHookHost.resolve] 的注释：不校验的话，「能 bind 到本 Service 的进程」
         * 就能伪造任意 capability 返回值（而那个值会被写进工作流）。
         *
         * ⚠️ 只做「转交」：解析、token 三段校验、配对分发全在 Controller 里
         * （那里有单测，且失败只记日志不抛）。
         *
         * ⚠️ 本方法在 **binder 线程**上；空串直接丢（连解析都不必）。
         */
        override fun resolve(responseJson: String?) {
            if (responseJson.isNullOrBlank()) return
            HookChannelController.onResolve(responseJson)
        }
    }

    override fun onCreate() {
        super.onCreate()
        // P2 阶段：收到事件只打日志（不接触发器）——
        // 这样「采集对了但没触发」与「采集没对」不会混在一起排查。
        // P3 会由 ActivityChangedTriggerHandler 注册真正的消费者
        HookChannelController.attach(this)
        HookChannelController.installDefaultSink()
    }

    override fun onBind(intent: Intent?): IBinder {
        DebugLogger.i(TAG, "onBind：hook 层连上来了（action=${intent?.action}）")
        // ⚠️ 不要在这里读 Binder.getCallingUid() —— onBind 由 AMS 调用、
        // 不是 binder 事务，读到的是**自己**的 uid（探针实测踩过这个坑）
        return hostBinder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        DebugLogger.i(TAG, "onUnbind：hook 层断开")
        // ⚠️ 传**本次要断开的那个** callback 实例（修缺陷 13）——
        // 断开是异步投递的，这次通知有可能落在「新连接已注册」之后。
        // 不带身份地清空会把刚建立的新连接一起清掉（实测时序见
        // HookChannelController.onCallbackUnregistered 的注释）。
        HookChannelController.onCallbackUnregistered(lastCallback)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        DebugLogger.i(TAG, "onDestroy")
        HookChannelController.onCallbackUnregistered(lastCallback)
        super.onDestroy()
    }
}
