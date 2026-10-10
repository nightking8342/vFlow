// 文件: main/java/com/chaomixian/vflow/services/TriggerService.kt
// 描述: 统一的后台服务，实现了持久化处理器和精细化任务分发。

package com.chaomixian.vflow.services

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.logging.ExecutionLogger
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.locale.LocaleManager
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.TriggerExecutionCoordinator
import com.chaomixian.vflow.core.workflow.WorkflowPermissionRecovery
import com.chaomixian.vflow.core.workflow.WorkflowWriteOrigin
import com.chaomixian.vflow.core.workflow.model.TriggerSpec
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.module.scripted.ModuleManager
import com.chaomixian.vflow.core.workflow.module.triggers.KeyEventTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.WorkflowToggleTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.ITriggerHandler
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.KeyEventTriggerHandler
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.TriggerHandlerRegistry
import com.chaomixian.vflow.core.workflow.module.triggers.handlers.WorkflowToggleTriggerHandler
import com.chaomixian.vflow.core.xposed.HookChannelController
import com.chaomixian.vflow.core.xposed.XposedDiagnostics
import com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor
import com.chaomixian.vflow.core.xposed.XposedReadiness
import com.chaomixian.vflow.core.xposed.XposedState
import com.chaomixian.vflow.extension.ExternalModuleManager
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.common.AppearanceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TriggerService : Service() {

    private lateinit var workflowManager: WorkflowManager
    // 处理器现在是服务的持久成员，在 onCreate 时创建
    private val triggerHandlers = mutableMapOf<String, ITriggerHandler>()
    // 为服务创建一个独立的协程作用域
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    // Core 守护任务
    private var coreWatcherJob: Job? = null
    private val workflowChangeMutex = Mutex()

    /**
     * 「通道未就绪」时要用的通知正文资源；`null` = 通道正常（用默认文案）。
     *
     * ## ⚠️ 为什么必须缓存上一次的值
     *
     * 状态流会频繁发射（`MutableStateFlow` 每次赋值都发）。若不比较就直接
     * `updateForegroundState()`，每次发射都会 `startForeground` 一次，**通知会闪**。
     *
     * ## ⚠️ 它是**只读派生**，不是新的状态源
     *
     * 真实状态在 [XposedFrameworkMonitor] 与 [HookChannelController] 里；
     * 本字段只是「上一次算出来的通知文案」的缓存，**不参与任何判定**。
     */
    @Volatile
    private var xposedNoticeRes: Int? = null


    companion object {
        private const val TAG = "TriggerServiceManager"
        private const val NOTIFICATION_ID = 2
        private const val CHANNEL_ID = "trigger_service_channel"
        // 新增的精确指令 Actions
        const val ACTION_WORKFLOW_CHANGED = "com.chaomixian.vflow.ACTION_WORKFLOW_CHANGED"
        const val ACTION_WORKFLOW_REMOVED = "com.chaomixian.vflow.ACTION_WORKFLOW_REMOVED"
        const val ACTION_RELOAD_TRIGGERS = "com.chaomixian.vflow.ACTION_RELOAD_TRIGGERS"
        const val EXTRA_TRIGGER_DELTA = "extra_trigger_delta"
        // 新增的通知更新 Action
        const val ACTION_UPDATE_NOTIFICATION = "com.chaomixian.vflow.ACTION_UPDATE_NOTIFICATION"
    }

    override fun attachBaseContext(newBase: Context) {
        val languageCode = LocaleManager.getLanguage(newBase)
        val localizedContext = LocaleManager.applyLanguage(newBase, languageCode)
        val context = AppearanceManager.applyDisplayScale(localizedContext)
        super.attachBaseContext(context)
    }

    override fun onCreate() {
        super.onCreate()
        workflowManager = WorkflowManager(applicationContext)

        // 让服务变得自给自足，无论应用进程是否存活，都能正确初始化所有依赖项。
        // 这可以修复在后台被杀后触发工作流导致的 UninitializedPropertyAccessException 崩溃。
        DebugLogger.initialize(applicationContext) // 确保服务独立运行时也能初始化
        ModuleRegistry.initialize(applicationContext)
        ModuleManager.loadModules(applicationContext) // 注册用户模块
        ExternalModuleManager.loadModulesAsync(applicationContext)
        TriggerHandlerRegistry.initialize() // 确保服务独立运行时也能初始化注册表
        ExecutionNotificationManager.initialize(this)
        LogManager.initialize(applicationContext)
        ExecutionLogger.initialize(this, serviceScope) // 使用服务的协程作用域和当前语言上下文

        // 在服务创建时就注册并启动所有处理器
        registerAndStartHandlers()
        DebugLogger.d(TAG, "TriggerService 已创建并启动了 ${triggerHandlers.size} 个触发器处理器。")

        // 首次启动时，加载所有活动的触发器
        loadAllActiveTriggers()
        // 订阅 Xposed 通道状态，供「已授权但通道断」的提示用
        observeXposedChannelState()
        WorkflowPermissionRecovery.recoverEligibleWorkflows(applicationContext)

        // 启动 Core 状态监控与保活处理
        startCoreWatcher()

        // 在服务创建时（如开机后）检查并应用启动设置
        checkAndApplyStartupSettings()
    }

    /**
     * 检查并应用 Shizuku 相关的启动设置
     */
    private fun checkAndApplyStartupSettings() {
        val prefs = getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        val autoEnableAccessibility = prefs.getBoolean("autoEnableAccessibility", false)
        val forceKeepAlive = prefs.getBoolean("forceKeepAliveEnabled", false)

        // 只有当任一开关为 true 时才执行检查
        if (autoEnableAccessibility || forceKeepAlive) {
            serviceScope.launch {
                // 延迟几秒，确保 Shizuku 服务在开机后有足够的时间准备好
                delay(10000)
                val shizukuActive = ShellManager.isShizukuActive(this@TriggerService)
                val rootAvailable = ShellManager.isRootAvailable()

                if (autoEnableAccessibility) {
                    if (shizukuActive || rootAvailable) {
                        DebugLogger.d(TAG, "Shell 环境就绪，正在恢复无障碍服务...")
                        val success = ShellManager.ensureAccessibilityServiceRunning(this@TriggerService)
                        if (success) {
                            DebugLogger.d(TAG, "已在启动时自动启用无障碍服务。")
                        } else {
                            DebugLogger.w(TAG, "尝试自动启用无障碍服务失败。")
                        }
                    } else {
                        DebugLogger.w(TAG, "无法自动启用无障碍服务: Shell (Shizuku/Root) 环境未就绪。")
                    }
                }

                if (forceKeepAlive) {
                    if (shizukuActive) {
                        ShellManager.startWatcher(this@TriggerService)
                        DebugLogger.d(TAG, "已在启动时自动启动 Shizuku 守护。")
                    } else {
                        DebugLogger.d(TAG, "强制保活已启用，当前未激活 Shizuku，仅使用无障碍 overlay 保活。")
                    }
                }
            }
        }
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 确保无论什么 Action，都先检查并正确设置前台状态
        updateForegroundState()

        // 处理来自 TriggerServiceProxy 的精确指令
        when (intent?.action) {
            ACTION_UPDATE_NOTIFICATION -> {
                // updateForegroundState 已在上面被调用
                DebugLogger.d(TAG, "收到通知设置更新请求，状态已刷新。")
            }
            ACTION_RELOAD_TRIGGERS -> {
                DebugLogger.d(TAG, "收到触发器重载请求。")
                reloadAllHandlers()
            }
            ACTION_WORKFLOW_CHANGED -> {
                val delta = intent.getParcelableExtra<WorkflowTriggerDelta>(EXTRA_TRIGGER_DELTA)
                if (delta != null) {
                    val latestWorkflow = workflowManager.getWorkflow(delta.workflowId)
                    if (latestWorkflow != null) {
                        handleWorkflowChanged(latestWorkflow, delta.oldTriggerRefs)
                        // fork（2026-10-10）：工作流开关触发器派发。
                        // ⚠️ 必须放在 handleWorkflowChanged **之后** —— 它负责增删该工作流
                        //    自己的触发器：「启用 Y 而 Y 自带开关触发器」要能立刻响应；
                        //    「关闭 Y」时 Y 的触发器刚被移除 ⇒ 不自触发。
                        // ⚠️ 接线点在这里而**不在** handleWorkflowChanged 内部：后者有
                        //    「开机装载」第二个调用方（loadAllActiveTriggers），写进去就必须
                        //    再给它加一个「我是不是启动装载」的参数。
                        //    形态照抄既有的 KeyEventTriggerHandler 分支。
                        (triggerHandlers[WorkflowToggleTriggerModule().id] as? WorkflowToggleTriggerHandler)
                            ?.onWorkflowSaved(this, latestWorkflow, delta.oldIsEnabled, delta.writeOrigin)
                    } else {
                        handleWorkflowRemoved(delta.oldTriggerRefs)
                    }
                }
            }
            ACTION_WORKFLOW_REMOVED -> {
                val delta = intent.getParcelableExtra<WorkflowTriggerDelta>(EXTRA_TRIGGER_DELTA)
                if (delta != null) {
                    handleWorkflowRemoved(delta.oldTriggerRefs)
                }
            }
            // 按键事件直接分发
            KeyEventTriggerHandler.ACTION_KEY_EVENT_RECEIVED -> {
                (triggerHandlers[KeyEventTriggerModule().id] as? KeyEventTriggerHandler)?.handleKeyEventIntent(this, intent)
            }
        }

        return START_STICKY
    }

    /**
     * 更新服务的前台状态。
     * 1. 始终先调用 startForeground，以满足 startForegroundService 的契约（防止 ANR/Crash）。
     * 2. 如果用户关闭了通知，则立即调用 stopForeground(REMOVE) 将其降级为后台服务。
     */
    private fun updateForegroundState() {
        val prefs = getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
        val showNotification = prefs.getBoolean("backgroundServiceNotificationEnabled", true)

        // 必须先调用一次 startForeground 确保满足 Android 8+ 的 startForegroundService 要求
        startForeground(NOTIFICATION_ID, createNotification())

        if (!showNotification) {
            // 用户选择隐藏通知 -> 降级为后台服务
            // 这样做会移除通知，但服务仍然运行（容易被杀）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        }
    }

    /**
     * 此方法现在从 TriggerHandlerRegistry 动态加载处理器，而不是硬编码。
     */
    private fun registerAndStartHandlers() {
        triggerHandlers.clear()
        // 从注册表获取所有已注册的处理器工厂
        val factories = TriggerHandlerRegistry.getAllHandlerFactories()
        factories.forEach { (triggerId, factory) ->
            // 通过工厂函数创建处理器实例
            val handler = factory()
            triggerHandlers[triggerId] = handler
        }
        // 启动所有处理器
        triggerHandlers.values.forEach { it.start(this) }
    }

    private fun reloadAllHandlers() {
        triggerHandlers.values.forEach { handler ->
            runCatching { handler.stop(this) }
        }
        triggerHandlers.clear()
        registerAndStartHandlers()
        loadAllActiveTriggers()
    }

    /**
     * 在服务首次启动时，加载所有已启用的工作流。
     */
    private fun loadAllActiveTriggers() {
        val activeWorkflows = workflowManager.getAllWorkflows().filter { it.isEnabled }
        DebugLogger.d(TAG, "TriggerService 首次启动，加载 ${activeWorkflows.size} 个活动的触发器。")
        activeWorkflows.forEach { workflow ->
            // 复用变更逻辑，确保启动时也进行权限检查
            handleWorkflowChanged(workflow, emptyList())
        }
    }

    /**
     * 处理工作流变更，增加权限守卫。
     * @param newWorkflow 新的工作流状态。
     * @param oldTriggerRefs 旧工作流触发器的轻量引用。
     */
    private fun handleWorkflowChanged(
        newWorkflow: Workflow,
        oldTriggerRefs: List<WorkflowTriggerRef>,
    ) {
        val newHandlers = getHandlersForWorkflow(newWorkflow)
        val oldHandlers = oldTriggerRefs.mapNotNull { triggerRef ->
            val handler = triggerHandlers[triggerRef.type] ?: return@mapNotNull null
            handler to triggerRef.triggerId
        }

        if (oldHandlers.isNotEmpty()) {
            DebugLogger.d(TAG, "准备更新，正在从处理器中移除旧版: ${newWorkflow.name}")
            oldHandlers.forEach { (handler, triggerId) -> handler.removeTrigger(this, triggerId) }
        }

        if (newHandlers.isEmpty()) {
            return
        }

        if (newWorkflow.isEnabled) {
            val missingPermissions = PermissionManager.getMissingPermissions(this, newWorkflow)

            if (missingPermissions.isEmpty()) {
                DebugLogger.d(TAG, "权限正常，正在向处理器添加/更新: ${newWorkflow.name}")
                newHandlers.forEach { (handler, trigger) ->
                    handler.addTrigger(this, trigger)
                }
                if (newWorkflow.wasEnabledBeforePermissionsLost) {
                    val fixedWorkflow = newWorkflow.copy(wasEnabledBeforePermissionsLost = false)
                    workflowManager.saveWorkflow(fixedWorkflow)
                }
            } else {
                DebugLogger.d(TAG, "工作流 '${newWorkflow.name}' 缺少权限，转入后台尝试恢复。")
                serviceScope.launch {
                    recoverWorkflowPermissionsAndApplyState(newWorkflow.id)
                }
            }
        } else {
            DebugLogger.d(TAG, "工作流 '${newWorkflow.name}' 已被禁用，正在从处理器中移除。")
            newHandlers.forEach { (handler, trigger) -> handler.removeTrigger(this, trigger.triggerId) }
        }
    }

    private fun handleWorkflowRemoved(oldTriggerRefs: List<WorkflowTriggerRef>) {
        oldTriggerRefs.forEach { triggerRef ->
            triggerHandlers[triggerRef.type]?.removeTrigger(this, triggerRef.triggerId)
        }
    }

    private suspend fun recoverWorkflowPermissionsAndApplyState(workflowId: String) {
        workflowChangeMutex.withLock {
            val latestWorkflow = workflowManager.getWorkflow(workflowId) ?: return
            if (!latestWorkflow.isEnabled) return

            val latestHandlers = getHandlersForWorkflow(latestWorkflow)
            if (latestHandlers.isEmpty()) {
                return
            }

            val remainingPermissions = TriggerExecutionCoordinator
                .recoverMissingPermissions(this@TriggerService, latestWorkflow)

            if (remainingPermissions.isEmpty()) {
                DebugLogger.d(TAG, "权限恢复成功，正在向处理器添加/更新: ${latestWorkflow.name}")
                latestHandlers.forEach { (handler, trigger) ->
                    handler.addTrigger(this@TriggerService, trigger)
                }
                if (latestWorkflow.wasEnabledBeforePermissionsLost) {
                    workflowManager.saveWorkflow(latestWorkflow.copy(wasEnabledBeforePermissionsLost = false))
                }
            } else if (shouldTreatAsTransientAccessibilityState(remainingPermissions)) {
                DebugLogger.w(
                    TAG,
                    "工作流 '${latestWorkflow.name}' 的无障碍服务暂未连接，但系统设置中仍已启用；跳过自动禁用，等待服务恢复。"
                )
            } else {
                DebugLogger.w(
                    TAG,
                    "工作流 '${latestWorkflow.name}' 因缺少权限 (${remainingPermissions.joinToString { it.name }}) 将被自动禁用。"
                )
                workflowManager.saveWorkflow(
                    latestWorkflow.copy(
                        isEnabled = false,
                        wasEnabledBeforePermissionsLost = true
                    ),
                    // fork：程序性自动禁用 ⇒ 不触发工作流开关触发器（设计文档 §3.3）
                    origin = WorkflowWriteOrigin.AUTOMATIC,
                )
            }
        }
    }

    private fun shouldTreatAsTransientAccessibilityState(
        remainingPermissions: List<com.chaomixian.vflow.permissions.Permission>
    ): Boolean {
        return remainingPermissions.size == 1 &&
            remainingPermissions.first().id == PermissionManager.ACCESSIBILITY.id &&
            AccessibilityServiceStatus.isEnabledInSettings(this)
    }

    /**
     * 根据工作流的触发器类型查找对应的处理器。
     */
    private fun getHandlersForWorkflow(workflow: Workflow): List<Pair<ITriggerHandler, TriggerSpec>> {
        return workflow.toAutoTriggerSpecs().mapNotNull { trigger ->
            val handler = triggerHandlers[trigger.type] ?: return@mapNotNull null
            handler to trigger
        }
    }

    // ── Xposed 通道未就绪提示 ──────────────────────────────────────────────
    //
    // 设计文档：`docs/fork/xposed-architecture-v2.md` §6.1。
    //
    // ## ⚠️ 为什么这块必须存在（不是可选的锦上添花）
    //
    // §6.1 定案：权限判据**保持 L1 实时**、不改成 `L0 ∪ L1`。代价是
    // **通道断时不会禁用工作流** ⇒ 用户看到「权限全绿 + 触发器不工作」。
    // 那条定案原文写着「可见性由新状态位承担 …… **不是可选项**」，
    // 并建议至少做进 `TriggerService` —— 本方法就是兑现它。
    //
    // ## ⚠️⚠️ 三条不许违反的纪律
    //
    // | # | 纪律 | 落实 |
    // |---|---|---|
    // | 1 | 改判据不改 | 本块**不碰** [XposedCapability]；它仍判 L1 |
    // | 2 | **不要用「禁用工作流」把用户引过来** | 本块**只读** `getAllWorkflows()`，**从不** `saveWorkflow` |
    // | 3 | [XposedState] 的语义不动 | 只**读** `result`，不重新判定 |

    /**
     * 订阅框架状态与通道状态。
     *
     * ⚠️ **订阅两条流** —— 状态位是两件独立的事
     * （照 `HomeScreen` 的既有模式）：框架状态变了（`bind` / `died`）与通道连上了，
     * 都可能改变最终判定，只订阅一条会漏掉另一半。
     *
     * ⚠️ `StateFlow` 的 `collect` **立刻收到当前值** ⇒ 订阅本身就已经触发了一次初始刷新，
     * 不需要在 `onCreate` 里再同步调一次 [refreshXposedChannelNotice]（那会读
     * SharedPreferences + 解析工作流 JSON，**压在 `onCreate` 栈上**）。
     */
    private fun observeXposedChannelState() {
        serviceScope.launch {
            XposedFrameworkMonitor.state.collect { refreshXposedChannelNotice() }
        }
        serviceScope.launch {
            HookChannelController.connected.collect { refreshXposedChannelNotice() }
        }
    }

    /**
     * 重算「通道未就绪」提示，必要时刷新前台通知并打日志。
     *
     * 在 [serviceScope]（`Dispatchers.IO`）里跑 —— 它会读工作流与模块注册表。
     */
    private fun refreshXposedChannelNotice() {
        val result = XposedFrameworkMonitor.evaluate(HookChannelController.isConnected())

        // ⚠️ 只在「用户真的配了 Xposed 触发器」时提示 —— 否则会把
        //「没装 LSPosed 的普通用户」也拖进来（他们的通道本来就是断的，且无所谓）
        val affected = if (XposedReadiness.needsChannelNotice(result)) {
            XposedReadiness.selectAffectedWorkflows(workflowManager.getAllWorkflows()) { moduleId ->
                ModuleRegistry.getModule(moduleId)?.getRequiredPermissions(null).orEmpty()
            }
        } else {
            emptyList()
        }

        val newNotice = if (affected.isEmpty()) null else XposedDiagnostics.channelNoticeRes(result)
        if (newNotice == xposedNoticeRes) return   // 值没变 ⇒ 不动通知（否则会闪）

        val previous = xposedNoticeRes
        xposedNoticeRes = newNotice
        if (newNotice != null) {
            // ⚠️ 用 DebugLogger.w 而不是 i：它同时走 android.util.Log 与内存缓冲，
            // 且**不受** debugLoggingEnabled 开关影响 ⇒ adb logcat 一定看得到
            DebugLogger.w(
                TAG,
                "Xposed 通道未就绪（影响 ${affected.size} 个工作流：" +
                    "${affected.joinToString { it.name }}）—— 相关触发器此刻不会触发。" +
                    "处置：${describeXposedNoticeAction(result)}"
            )
        } else if (previous != null) {
            DebugLogger.i(TAG, "Xposed 通道已就绪，触发器恢复工作。")
        }
        updateForegroundState()
    }

    /**
     * 日志里那句「处置」的措辞。
     *
     * ⚠️ 分支**复用** [XposedState.tapAction]，不在这里写 `if` ——
     * 否则「哪种状态给哪种提示」会有两处口径，迟早漂移。
     */
    private fun describeXposedNoticeAction(result: XposedState.Result): String =
        when (XposedState.tapAction(result)) {
            XposedState.TapAction.RECONNECT_HINT -> "等待自动重连（无需改配置）"
            XposedState.TapAction.GUIDE -> "检查 LSPosed 配置或重启设备"
        }

    override fun onDestroy() {
        super.onDestroy()
        // 停止 Core 守护任务
        coreWatcherJob?.cancel()
        triggerHandlers.values.forEach { it.stop(this) }
        // 服务销毁时，取消协程作用域
        serviceScope.cancel()
        DebugLogger.d(TAG, "TriggerService 已销毁。")
    }

    /**
     * 启动 Core 状态监控。
     * 当允许保活时负责自动拉起；否则将依赖 Core 的工作流转入缺权限停用状态。
     */
    private fun startCoreWatcher() {
        coreWatcherJob = serviceScope.launch {
            var restartAttempts = 0
            val maxRestartAttempts = 10
            val checkInterval = 30_000L // 30秒检查一次

            DebugLogger.i(TAG, "Core Watcher: Starting to monitor vFlowCore process...")

            while (!coroutineContext[Job]?.isCancelled!!) {
                delay(checkInterval)

                val prefs = getSharedPreferences("vFlowPrefs", Context.MODE_PRIVATE)
                val manualStopRequested = prefs.getBoolean(CoreManagementService.PREF_CORE_MANUAL_STOP_REQUESTED, false)
                val mutualKeepAliveEnabled = prefs.getBoolean("mutual_keep_alive_enabled", true)

                if (manualStopRequested || !mutualKeepAliveEnabled) {
                    if (!VFlowCoreBridge.ping()) {
                        disableWorkflowsMissingCorePermissions()
                    }
                    restartAttempts = 0
                    continue
                }

                // 检查 Core 是否存活
                val isCoreAlive = VFlowCoreBridge.ping()

                if (!isCoreAlive) {
                    DebugLogger.w(TAG, "Core Watcher: Core process not responding, attempting to restart...")

                    if (restartAttempts < maxRestartAttempts) {
                        restartAttempts++
                        val success = CoreManager.ensureStarted(
                            this@TriggerService,
                            CoreLauncher.LaunchMode.AUTO
                        )

                        if (success) {
                            DebugLogger.i(TAG, "Core Watcher: Successfully restarted Core ($restartAttempts/$maxRestartAttempts)")
                            restartAttempts = 0 // 重置计数器
                        } else {
                            DebugLogger.e(TAG, "Core Watcher: Failed to restart Core ($restartAttempts/$maxRestartAttempts)")
                            disableWorkflowsMissingCorePermissions()
                        }
                    } else {
                        DebugLogger.e(TAG, "Core Watcher: Max restart attempts reached, giving up")
                        disableWorkflowsMissingCorePermissions()
                        break
                    }
                } else {
                    // Core 存活，重置计数器
                    restartAttempts = 0
                }
            }
        }
    }

    private fun disableWorkflowsMissingCorePermissions() {
        val affectedWorkflows = workflowManager.getAllWorkflows().filter { workflow ->
            workflow.isEnabled && PermissionManager.getMissingPermissions(this, workflow).any { permission ->
                permission.id == PermissionManager.CORE.id || permission.id == PermissionManager.CORE_ROOT.id
            }
        }

        affectedWorkflows.forEach { workflow ->
            DebugLogger.w(TAG, "Core 不可用，暂停依赖 Core 的工作流: ${workflow.name}")
            workflowManager.saveWorkflow(
                workflow.copy(
                    isEnabled = false,
                    wasEnabledBeforePermissionsLost = true
                ),
                // fork：程序性自动禁用（Core 不可用批量暂停）⇒ 不触发开关触发器
                origin = WorkflowWriteOrigin.AUTOMATIC,
            )
        }
    }

    private fun createNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.trigger_service_notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.trigger_service_notification_title))
            // ⚠️ 「通道未就绪」时改用提示文案（§6.1 的可见性义务）。
            // 用户关掉后台服务通知时 updateForegroundState() 会 stopForeground(REMOVE)，
            // 此时提示只剩日志 —— 这是**已知且可接受**的范围收窄，不要为此改成常驻前台通知。
            .setContentText(getString(xposedNoticeRes ?: R.string.trigger_service_notification_text))
            .setSmallIcon(R.drawable.ic_workflows)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
