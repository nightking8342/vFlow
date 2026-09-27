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
        if (TelemetryManager.isEnabled(applicationContext)) {
            TelemetryManager.init(applicationContext)
        }
    }
}
