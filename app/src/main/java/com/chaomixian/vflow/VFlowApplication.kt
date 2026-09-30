package com.chaomixian.vflow

import android.app.Application
import com.chaomixian.vflow.core.logging.CrashReportManager
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.telemetry.TelemetryManager

class VFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LogManager.initialize(applicationContext)
        DebugLogger.initialize(applicationContext)
        CrashReportManager.install(applicationContext)
        TelemetryManager.preInit(applicationContext)

        // ⚠️ Xposed 框架状态监听必须**尽早注册**：`onServiceBind` 是**推送**的、
        // 不重放 —— 注册晚了就永远收不到，状态会卡在「未启用」。
        //
        // ⚠️ 但放在 DebugLogger.initialize 之后：Monitor 内部要打日志。
        //
        // ⚠️ 这里**只注册、不做任何耗时操作**（注册本身是同步的轻量调用）。
        // 本项目曾在启动期改动上踩过三次「模块不加载」，虽然那条结论**已被反证**
        // （不是平台限制），但纪律仍是「一次只改一处」——
        // 这一步是**新增一行**，不改既有顺序。
        com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor.start(applicationContext)

        // ⚠️ ③（能力调用）的 App 侧运行时接线。**只接线，不做跨进程调用** ——
        // 真正的能力交换由 `CapabilityPresenceHolder` 在**连接建立**时触发
        //（`notifyOnConnected` 是推送的，因此与「Service 还是 hook 层先启动」无关）。
        //
        // ⚠️ 它是本仓库「启动期改动」纪律下的**那「一处」**（三次「模块不加载」的历史，
        // 真因未查明、已被后续探针反证并非平台限制，纪律是「一次只改一处 + 保留回退版本」）。
        // 回退开关是 `CapabilityRuntime.isEnabled()` —— ⚠️ 且它是**运行时判定**
        //（每次连接建立时自检），不是「启动时判一次」，见那里的注释。
        com.chaomixian.vflow.core.xposed.CapabilityRuntime.attach(applicationContext)
        if (TelemetryManager.isEnabled(applicationContext)) {
            TelemetryManager.init(applicationContext)
        }
    }
}
