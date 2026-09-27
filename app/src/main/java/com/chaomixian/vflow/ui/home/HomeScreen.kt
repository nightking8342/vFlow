package com.chaomixian.vflow.ui.home

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import android.widget.Toast
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionStateBus
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.locale.toast
import com.chaomixian.vflow.core.logging.LogEntry
import com.chaomixian.vflow.core.logging.LogManager
import com.chaomixian.vflow.core.logging.LogStatus
import com.chaomixian.vflow.core.module.ModuleRegistry
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.xposed.HookChannelController
import com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor
import com.chaomixian.vflow.core.xposed.XposedState
import com.chaomixian.vflow.ui.settings.XposedGuideDialog
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.permissions.PermissionActivity
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.services.VFlowCoreBridge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.ArrayList
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class HomeUiState(
    val totalWorkflowCount: Int = 0,
    val autoWorkflowCount: Int = 0,
    val enabledAutoWorkflowCount: Int = 0,
    val coreConnected: Boolean = false,
    val corePrivilegeMode: VFlowCoreBridge.PrivilegeMode = VFlowCoreBridge.PrivilegeMode.NONE,
    val coreNeedsUpdate: Boolean = false,
    val coreRunningVersionName: String? = null,
    val corePackagedVersionName: String? = null,
    val missingPermissionCount: Int = 0,
    // ── Xposed 通道状态（fork 新增）──
    // ⚠️ 用**两个状态位**而不是一个枚举：见 XposedState 的类注释 ——
    // 「框架在不在」与「事件能不能流过来」是两件独立的事，
    // 压成一个会让「框架正常但通道断了」这一格**表示不出来**，
    // 而它恰恰是最容易被误判成「框架问题」的。
    val xposedResult: XposedState.Result? = null,
    val xposedScope: List<String> = emptyList(),
    /** 框架版本串（如 `LSPosed 2.2.0`）。断开时为空。 */
    val xposedFrameworkVersion: String = "",
    /** hook 层报告的事件丢弃数（> 0 才显示提示）。 */
    val xposedDroppedCount: Long = 0,
    val quickWorkflows: List<Workflow> = emptyList(),
    val recentLogs: List<LogEntry> = emptyList(),
    val allLogs: List<LogEntry> = emptyList(),
)

@androidx.compose.material3.ExperimentalMaterial3Api
@Composable
fun HomeScreen(
    isActive: Boolean,
    bottomContentPadding: Dp,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val workflowManager = remember(context) { WorkflowManager(context) }
    val coroutineScope = rememberCoroutineScope()
    var uiState by remember { mutableStateOf(HomeUiState()) }
    var executionStateVersion by remember { mutableIntStateOf(0) }
    var pendingWorkflowId by rememberSaveable { mutableStateOf<String?>(null) }
    var coreStatusRefreshJob by remember { mutableStateOf<Job?>(null) }
    var permissionHealthRefreshJob by remember { mutableStateOf<Job?>(null) }
    var logSheetVisible by rememberSaveable { mutableStateOf(false) }
    var selectedLog by remember { mutableStateOf<LogEntry?>(null) }
    val logSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val pendingWorkflow = workflowManager.getAllWorkflows().firstOrNull { it.id == pendingWorkflowId }
        if (result.resultCode == Activity.RESULT_OK && pendingWorkflow != null) {
            executeWorkflow(context, pendingWorkflow, checkPermissions = false)
        }
        pendingWorkflowId = null
    }

    fun refreshStatisticsAndLists() {
        coroutineScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                loadWorkflowSnapshot(workflowManager)
            }
            uiState = uiState.copy(
                totalWorkflowCount = snapshot.totalWorkflowCount,
                autoWorkflowCount = snapshot.autoWorkflowCount,
                enabledAutoWorkflowCount = snapshot.enabledAutoWorkflowCount,
                quickWorkflows = snapshot.quickWorkflows,
                recentLogs = snapshot.recentLogs,
                allLogs = snapshot.allLogs,
            )
        }
    }

    fun refreshCoreStatus() {
        coroutineScope.launch {
            val coreState = withContext(Dispatchers.IO) {
                loadCoreState()
            }
            uiState = uiState.copy(
                coreConnected = coreState.coreConnected,
                corePrivilegeMode = coreState.corePrivilegeMode,
                coreNeedsUpdate = coreState.coreNeedsUpdate,
                coreRunningVersionName = coreState.coreRunningVersionName,
                corePackagedVersionName = coreState.corePackagedVersionName,
            )
        }
    }

    /**
     * 刷新 Xposed 通道状态（fork 新增）。
     *
     * ⚠️ 与 `refreshCoreStatus`（轮询）不同，这里**不需要轮询** ——
     * `XposedFrameworkMonitor.state` 是 `StateFlow`，框架状态变化会**推送**过来
     * （`onServiceBind` / `onServiceDied`）。见下面那个 LaunchedEffect。
     */
    fun refreshXposedStatus() {
        // ⚠️ 确保监听已启动 —— Application.onCreate 里已调过，这里是兜底
        // （某些入口可能先于 Application 初始化，且 start() 本身幂等）
        com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor.start(context)
        val monitor = com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor
        monitor.refresh()
        uiState = uiState.copy(
            xposedResult = monitor.evaluate(com.chaomixian.vflow.core.xposed.HookChannelController.isConnected()),
            xposedScope = monitor.state.value.scope,
            xposedFrameworkVersion = formatFrameworkVersion(monitor.state.value),
            // 丢弃计数：hook 层上报过才 > 0。⚠️ 只在 > 0 时给用户看（见下面的 info card）
            xposedDroppedCount = com.chaomixian.vflow.core.xposed.HookChannelController.lastReportedDroppedCount(),
        )
    }

    fun refreshPermissionHealth() {
        coroutineScope.launch {
            val missingPermissionCount = withContext(Dispatchers.IO) {
                getMissingPermissionCount(context, workflowManager)
            }
            uiState = uiState.copy(missingPermissionCount = missingPermissionCount)
        }
    }

    fun refreshAll() {
        refreshStatisticsAndLists()
        refreshCoreStatus()
        refreshPermissionHealth()
        refreshXposedStatus()
    }

    fun startCoreStatusAutoRefresh() {
        coreStatusRefreshJob?.cancel()
        if (uiState.coreConnected) return
        coreStatusRefreshJob = coroutineScope.launch {
            repeat(16) {
                delay(500)
                val coreState = withContext(Dispatchers.IO) {
                    loadCoreState()
                }
                uiState = uiState.copy(
                    coreConnected = coreState.coreConnected,
                    corePrivilegeMode = coreState.corePrivilegeMode,
                    coreNeedsUpdate = coreState.coreNeedsUpdate,
                    coreRunningVersionName = coreState.coreRunningVersionName,
                    corePackagedVersionName = coreState.corePackagedVersionName,
                )
                if (coreState.coreConnected) {
                    return@launch
                }
            }
        }
    }

    fun startPermissionHealthAutoRefresh() {
        permissionHealthRefreshJob?.cancel()
        permissionHealthRefreshJob = coroutineScope.launch {
            repeat(20) {
                delay(500)
                val missingPermissionCount = withContext(Dispatchers.IO) {
                    getMissingPermissionCount(context, workflowManager)
                }
                uiState = uiState.copy(missingPermissionCount = missingPermissionCount)
                if (missingPermissionCount == 0) {
                    return@launch
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        ExecutionStateBus.stateFlow.collectLatest {
            executionStateVersion++
            uiState = uiState.copy(
                recentLogs = LogManager.getRecentLogs(5),
                allLogs = LogManager.getAllLogs(),
            )
        }
    }

    // ⚠️ Xposed 状态走**推送**（StateFlow），不轮询 ——
    // 框架的连接/断开由 `onServiceBind` / `onServiceDied` 主动通知。
    // 这与上面 Core 的轮询是两套机制，**刻意不统一**：Core 没有推送源，只能轮询。
    LaunchedEffect(Unit) {
        com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor.state.collect {
            uiState = uiState.copy(
                xposedResult = com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor.evaluate(
                    com.chaomixian.vflow.core.xposed.HookChannelController.isConnected()
                ),
                xposedScope = it.scope,
                xposedFrameworkVersion = formatFrameworkVersion(it),
            )
        }
    }

    // ⚠️ L0（通道连接）也要订阅 —— 它与框架状态**独立**：
    // 「框架正常但通道断了」是单独的一格，只订阅 Monitor 会漏掉它
    LaunchedEffect(Unit) {
        com.chaomixian.vflow.core.xposed.HookChannelController.connected.collect {
            uiState = uiState.copy(
                xposedResult = com.chaomixian.vflow.core.xposed.XposedFrameworkMonitor.evaluate(it),
            )
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            refreshAll()
            startCoreStatusAutoRefresh()
            startPermissionHealthAutoRefresh()
        } else {
            coreStatusRefreshJob?.cancel()
            permissionHealthRefreshJob?.cancel()
        }
    }

    LaunchedEffect(logSheetVisible) {
        if (logSheetVisible && logSheetState.hasPartiallyExpandedState) {
            logSheetState.partialExpand()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            coreStatusRefreshJob?.cancel()
            permissionHealthRefreshJob?.cancel()
        }
    }

    val openCoreManagement = remember(context) {
        {
            context.startActivity(
                Intent(context, com.chaomixian.vflow.ui.settings.CoreManagementActivity::class.java)
            )
        }
    }
    val openPermissionHealth = remember(context) {
        {
            context.startActivity(
                Intent(context, PermissionActivity::class.java).apply {
                    putParcelableArrayListExtra(
                        PermissionActivity.EXTRA_PERMISSIONS,
                        ArrayList(PermissionManager.getAllRegisteredPermissions())
                    )
                }
            )
        }
    }
    Box(modifier = modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = 16.dp,
                end = 16.dp,
                bottom = bottomContentPadding + 16.dp
            ),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                HomeSummarySection(
                    uiState = uiState,
                    onOpenCoreManagement = openCoreManagement,
                    // ⚠️ 点击行为**按状态不同**：
                    //   · 需要引导（没启用/没挂上）→ 弹引导对话框
                    //   · 框架好但通道断（ACTIVE+DISCONNECTED）→ **不引导**，
                    //     因为问题在我们自己的连接，去改 LSPosed 没用（还可能改坏）
                    // ⚠️ 用 XposedState.tapAction 分类，**不要**在这里自己写 if ——
                    // 第一版写成 `if (needsGuidance) 弹引导 else 弹重连提示`，
                    // 而 needsGuidance 对「正常」也返回 false ⇒
                    // **一切正常时点卡片会弹「通道正在重连」**（核对时发现）。
                    // 分类逻辑抽进 XposedState 并有单测锁住。
                    onXposedAction = {
                        when (XposedState.tapAction(uiState.xposedResult)) {
                            XposedState.TapAction.GUIDE ->
                                XposedGuideDialog.show(context)

                            XposedState.TapAction.RECONNECT_HINT ->
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.home_xposed_reconnecting_hint),
                                    Toast.LENGTH_SHORT
                                ).show()
                        }
                    },
                )
            }

            // ⚠️ 丢弃提示：**条件显示**（> 0 才出现）——
            // 与状态卡不同，它不是网格的一部分，而是横条（`HomeInfoCard`），
            // 出现/消失不影响布局。这也与 `coreNeedsUpdate` 那条同一个模式。
            if (uiState.xposedDroppedCount > 0) {
                item {
                    HomeInfoCard(
                        onClick = {
                            // 点它直接去引导（用户需要知道怎么收窄条件）
                            XposedGuideDialog.show(context)
                        },
                        leadingIconRes = R.drawable.rounded_info_24,
                        title = stringResource(
                            R.string.home_xposed_dropped_title,
                            uiState.xposedDroppedCount,
                        ),
                        description = stringResource(R.string.home_xposed_dropped_desc),
                    )
                }
            }

            if (uiState.coreConnected && uiState.coreNeedsUpdate) {
                item {
                    HomeInfoCard(
                        onClick = openCoreManagement,
                        leadingIconRes = R.drawable.rounded_system_update_alt_24,
                        title = stringResource(R.string.home_core_update_title),
                        // 文案不再引用版本号：指纹变化时两个版本号是**相同**的，
                        // 显示"当前 22 / 内置 22"会读成"没更新"，反而误导
                        description = stringResource(R.string.home_core_update_desc),
                    )
                }
            }

            item {
                PermissionHealthCard(
                    missingPermissionCount = uiState.missingPermissionCount,
                    onClick = openPermissionHealth,
                )
            }

            if (uiState.recentLogs.isNotEmpty()) {
                item {
                    SectionCard(
                        title = stringResource(R.string.home_recent_logs),
                        onClick = { logSheetVisible = true },
                    ) {
                        RecentLogsList(
                            logs = uiState.recentLogs,
                            onShowDetail = { log -> selectedLog = log }
                        )
                    }
                }
            }

            if (uiState.quickWorkflows.isNotEmpty()) {
                item {
                    SectionCard(
                        title = stringResource(R.string.home_quick_execute),
                    ) {
                        QuickExecuteList(
                            workflows = uiState.quickWorkflows,
                            executionStateVersion = executionStateVersion,
                            onWorkflowClick = { workflow ->
                                if (WorkflowExecutor.isRunning(workflow.id)) {
                                    WorkflowExecutor.stopExecution(workflow.id)
                                    context.toast(context.getString(R.string.home_stopped_execution, workflow.name))
                                } else {
                                    val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
                                    if (missingPermissions.isNotEmpty()) {
                                        pendingWorkflowId = workflow.id
                                        permissionLauncher.launch(
                                            Intent(context, PermissionActivity::class.java).apply {
                                                putParcelableArrayListExtra(
                                                    PermissionActivity.EXTRA_PERMISSIONS,
                                                    ArrayList(missingPermissions)
                                                )
                                                putExtra(PermissionActivity.EXTRA_WORKFLOW_NAME, workflow.name)
                                            }
                                        )
                                    } else {
                                        executeWorkflow(context, workflow)
                                    }
                                }
                            }
                        )
                    }
                }
            }
        }

        if (logSheetVisible) {
            ModalBottomSheet(
                onDismissRequest = { logSheetVisible = false },
                sheetState = logSheetState,
            ) {
                LogViewerBottomSheet(
                    logs = uiState.allLogs,
                    onLogClick = { selectedLog = it },
                    onClearLogs = {
                        LogManager.clearLogs()
                        uiState = uiState.copy(
                            recentLogs = emptyList(),
                            allLogs = emptyList(),
                        )
                        context.toast(context.getString(R.string.settings_toast_logs_cleared))
                    },
                )
            }
        }

        selectedLog?.let { log ->
            LogDetailDialog(
                log = log,
                onDismiss = { selectedLog = null },
                onDelete = {
                    LogManager.deleteLog(it)
                    selectedLog = null
                    uiState = uiState.copy(
                        recentLogs = LogManager.getRecentLogs(5),
                        allLogs = LogManager.getAllLogs(),
                    )
                    context.toast(context.getString(R.string.toast_module_deleted))
                },
            )
        }
    }
}

private data class WorkflowSnapshot(
    val totalWorkflowCount: Int,
    val autoWorkflowCount: Int,
    val enabledAutoWorkflowCount: Int,
    val quickWorkflows: List<Workflow>,
    val recentLogs: List<LogEntry>,
    val allLogs: List<LogEntry>,
)

private data class CoreStateSnapshot(
    val coreConnected: Boolean,
    val corePrivilegeMode: VFlowCoreBridge.PrivilegeMode,
    val coreNeedsUpdate: Boolean,
    val coreRunningVersionName: String?,
    val corePackagedVersionName: String?,
)

private suspend fun loadWorkflowSnapshot(
    workflowManager: WorkflowManager,
): WorkflowSnapshot {
    val allWorkflows = workflowManager.getAllWorkflows()
    val autoWorkflows = allWorkflows.filter { it.hasAutoTriggers() }
    val quickWorkflows = allWorkflows.filter { it.isFavorite && it.hasManualTrigger() }
    return WorkflowSnapshot(
        totalWorkflowCount = allWorkflows.size,
        autoWorkflowCount = autoWorkflows.size,
        enabledAutoWorkflowCount = autoWorkflows.count { it.isEnabled },
        quickWorkflows = quickWorkflows,
        recentLogs = LogManager.getRecentLogs(5),
        allLogs = LogManager.getAllLogs(),
    )
}

private fun loadCoreState(): CoreStateSnapshot {
    val pingSuccess = VFlowCoreBridge.ping()
    val isConnected = pingSuccess && VFlowCoreBridge.isConnected
    val privilegeMode = if (isConnected) {
        VFlowCoreBridge.privilegeMode
    } else {
        VFlowCoreBridge.PrivilegeMode.NONE
    }
    val versionStatus = VFlowCoreBridge.getCoreVersionStatus()
    return CoreStateSnapshot(
        coreConnected = isConnected,
        corePrivilegeMode = privilegeMode,
        // ⚠️ 判据是 **dex 指纹变化**，不是版本号 —— 理由见
        // VFlowCoreBridge.isCoreDexNewerThanRunning 的注释。
        // 简言之：版本号只在发版时动，而"改了 core 要重启"是开发期的高频需求，
        // 两者节奏不同；用指纹还能避免改 app 代码时的误报
        coreNeedsUpdate = isConnected && (
            versionStatus.needsUpdate || VFlowCoreBridge.isCoreDexNewerThanRunning()
            ),
        coreRunningVersionName = versionStatus.running?.versionName,
        corePackagedVersionName = versionStatus.packaged.versionName,
    )
}

private fun getMissingPermissionCount(
    context: Context,
    workflowManager: WorkflowManager,
): Int {
    val allWorkflows = workflowManager.getAllWorkflows()
    val requiredPermissions = allWorkflows
        .flatMap { it.allSteps }
        .mapNotNull { step ->
            ModuleRegistry.getModule(step.moduleId)?.getRequiredPermissions(step)
        }
        .flatten()
        .distinct()

    return requiredPermissions.count { !PermissionManager.isGranted(context, it) }
}

private fun executeWorkflow(
    context: Context,
    workflow: Workflow,
    checkPermissions: Boolean = true,
) {
    if (checkPermissions) {
        val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
        if (missingPermissions.isNotEmpty()) {
            return
        }
    }
    if (!workflow.silentExecution) {
        context.toast(context.getString(R.string.home_starting_execution, workflow.name))
    }
    WorkflowExecutor.execute(
        workflow = workflow,
        context = context,
        triggerStepId = workflow.manualTrigger()?.id
    )
}

@Composable
private fun HomeSummarySection(
    uiState: HomeUiState,
    onOpenCoreManagement: () -> Unit,
    onXposedAction: () -> Unit,
) {
    val totalCard: @Composable () -> Unit = {
        StatCard(
            title = stringResource(R.string.home_total_workflows),
            value = uiState.totalWorkflowCount.toString(),
        )
    }
    val autoCard: @Composable () -> Unit = {
        StatCard(
            title = stringResource(R.string.home_auto_tasks),
            value = stringResource(
                R.string.home_auto_tasks_stats,
                uiState.enabledAutoWorkflowCount,
                uiState.autoWorkflowCount
            ),
        )
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 两张状态大卡并排（Core / Xposed）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            CoreStatusCard(
                uiState = uiState,
                onClick = onOpenCoreManagement,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
            XposedStatusCard(
                result = uiState.xposedResult,
                frameworkVersion = uiState.xposedFrameworkVersion,
                onAction = onXposedAction,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
            )
        }
        // 两个统计小卡下移并排
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) { totalCard() }
            Box(modifier = Modifier.weight(1f)) { autoCard() }
        }
    }
}

@Composable
private fun CoreStatusCard(
    uiState: HomeUiState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val containerColor = if (uiState.coreConnected) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (uiState.coreConnected) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }
    Card(
        onClick = onClick,
        modifier = modifier.defaultMinSize(minHeight = 160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(
                    if (uiState.coreConnected) {
                        R.drawable.rounded_check_circle_24
                    } else {
                        R.drawable.rounded_cancel_24
                    }
                ),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 40.dp, y = 40.dp)
                    .size(160.dp)
                    .alpha(0.2f)
            )
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Text(
                    text = stringResource(R.string.home_core_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor.copy(alpha = 0.9f)
                )
                Text(
                    text = stringResource(
                        if (uiState.coreConnected) {
                            R.string.home_core_status_working
                        } else {
                            R.string.home_core_status_stopped
                        }
                    ),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                Text(
                    text = stringResource(
                        R.string.home_core_mode,
                        stringResource(
                            when (uiState.corePrivilegeMode) {
                                VFlowCoreBridge.PrivilegeMode.ROOT -> R.string.home_privilege_root
                                VFlowCoreBridge.PrivilegeMode.SHELL -> R.string.home_privilege_shell
                                VFlowCoreBridge.PrivilegeMode.NONE -> R.string.home_privilege_none
                            }
                        )
                    ),
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

/**
 * Xposed 通道状态卡（fork 新增）。
 *
 * ## ⚠️ 与 [CoreStatusCard] 同构，但状态是**两组**而非二态
 *
 * Core 是「连上/停了」二态；Xposed 有 `Framework` × `Channel` 两组状态位
 * （见 [XposedState]）。**配色只用两种**（正常/异常），三种失败状态靠
 * **图标 + 文案**区分 —— 引入第三种容器色会打乱首页既有的视觉秩序。
 *
 * ## ⚠️ 常驻显示，不做条件显隐
 *
 * 它占据网格里的固定位置，四态都占位。理由：
 * ① 条件显隐会让旁边的 Core 卡**突然变宽**、布局跳动；
 * ② **「存在本身即信息」** —— 用户看到「未启用」才知道有这个能力。
 *
 * ## ⚠️ 文案按状态分流，尤其 `ACTIVE + DISCONNECTED`
 *
 * 那一格框架是好的、断的是我们自己的连接。若文案写成「去检查 LSPosed」，
 * 用户会去改**本来正确的**配置。
 */
/**
 * 把框架名与版本拼成一行（如 `LSPosed 2.2.0`）。
 *
 * ⚠️ 任一为空则**整体为空** —— 不要拼出 `LSPosed ` 或 ` 2.2.0` 这种半截串：
 * 卡片会据此决定是否显示这一行（空 ⇒ 显示操作提示）。
 */
private fun formatFrameworkVersion(obs: XposedFrameworkMonitor.Observation): String {
    if (obs.frameworkName.isBlank() || obs.frameworkVersion.isBlank()) return ""
    return "${obs.frameworkName} ${obs.frameworkVersion}"
}

@Composable
private fun XposedStatusCard(
    result: XposedState.Result?,
    frameworkVersion: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val healthy = result != null && XposedState.isHealthy(result)
    val containerColor = if (healthy) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.errorContainer
    }
    val contentColor = if (healthy) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onErrorContainer
    }

    // 图标按状态选（配色不变，靠图标区分三种失败态）
    val iconRes = when {
        healthy -> R.drawable.rounded_check_circle_24
        result == null || result.framework == XposedState.Framework.UNAVAILABLE ->
            R.drawable.rounded_extension_off_24
        result.framework == XposedState.Framework.DEGRADED ->
            R.drawable.rounded_sync_problem_24
        result.channel == XposedState.Channel.NOT_MOUNTED ->
            R.drawable.rounded_rule_24
        // ACTIVE + DISCONNECTED
        else -> R.drawable.rounded_sync_problem_24
    }

    val headline = when {
        healthy -> stringResource(R.string.home_xposed_status_working)
        result == null || result.framework == XposedState.Framework.UNAVAILABLE ->
            stringResource(R.string.home_xposed_status_unavailable)
        result.framework == XposedState.Framework.DEGRADED ->
            stringResource(R.string.home_xposed_status_degraded)
        result.channel == XposedState.Channel.NOT_MOUNTED ->
            stringResource(R.string.home_xposed_status_not_mounted)
        else -> stringResource(R.string.home_xposed_status_reconnecting)
    }

    Card(
        onClick = onAction,
        modifier = modifier.defaultMinSize(minHeight = 160.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Icon(
                painter = painterResource(iconRes),
                contentDescription = null,
                tint = contentColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 40.dp, y = 40.dp)
                    .size(160.dp)
                    .alpha(0.2f)
            )
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = stringResource(R.string.home_xposed_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = contentColor.copy(alpha = 0.9f)
                )
                Text(
                    text = headline,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = contentColor
                )
                // 副文案：正常时显示**框架版本**（如「LSPosed 2.2.0」），
                // 异常时显示操作提示。
                //
                // ⚠️ 原先正常时显示「作用域：system」—— 用户反馈那一行更有用
                // 的是框架版本（作用域对不对，出问题时用引导对话框里的实时显示核对
                // 就够了；而「我装的是哪个版」是用户更常想确认的）
                Text(
                    text = if (healthy && frameworkVersion.isNotBlank()) {
                        frameworkVersion
                    } else {
                        stringResource(R.string.home_xposed_tap_to_fix)
                    },
                    modifier = Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = contentColor.copy(alpha = 0.8f)
                )
            }
        }
    }
}

@Composable
private fun StatCard(
    title: String,
    value: String,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .defaultMinSize(minHeight = 74.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                modifier = Modifier.padding(top = 4.dp),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun HomeInfoCard(
    onClick: () -> Unit,
    leadingIconRes: Int,
    title: String,
    description: String,
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(leadingIconRes),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
            Column(
                modifier = Modifier
                    .padding(start = 16.dp)
                    .weight(1f)
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = description,
                    modifier = Modifier.padding(top = 2.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
private fun PermissionHealthCard(
    missingPermissionCount: Int,
    onClick: () -> Unit,
) {
    HomeInfoCard(
        onClick = onClick,
        leadingIconRes = R.drawable.rounded_security_24,
        title = stringResource(R.string.home_permission_health),
        description = if (missingPermissionCount == 0) {
            stringResource(R.string.home_permission_good)
        } else {
            stringResource(R.string.home_permission_missing, missingPermissionCount)
        },
    )
}

@Composable
private fun SectionCard(
    title: String,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (onClick != null) {
                        Modifier.clickable(onClick = onClick)
                    } else {
                        Modifier
                    }
                )
                .padding(vertical = 8.dp)
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                style = MaterialTheme.typography.titleMedium
            )
            content()
        }
    }
}

@Composable
private fun RecentLogsList(
    logs: List<LogEntry>,
    onShowDetail: (LogEntry) -> Unit,
) {
    Column {
        logs.forEachIndexed { index, log ->
            LogRow(
                log = log,
                onClick = { onShowDetail(log) }
            )
            if (index < logs.lastIndex) {
                SectionDivider()
            }
        }
    }
}

@Composable
private fun QuickExecuteList(
    workflows: List<Workflow>,
    executionStateVersion: Int,
    onWorkflowClick: (Workflow) -> Unit,
) {
    Column {
        workflows.forEachIndexed { index, workflow ->
            QuickExecuteRow(
                workflow = workflow,
                executionStateVersion = executionStateVersion,
                onClick = { onWorkflowClick(workflow) }
            )
            if (index < workflows.lastIndex) {
                SectionDivider()
            }
        }
    }
}

@Composable
private fun SectionDivider() {
    Spacer(
        modifier = Modifier
            .padding(start = 64.dp, end = 20.dp)
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    )
}

@Composable
private fun LogViewerBottomSheet(
    logs: List<LogEntry>,
    onLogClick: (LogEntry) -> Unit,
    onClearLogs: () -> Unit,
) {
    var showClearConfirmDialog by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.label_execution_log),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            FilledTonalButton(
                onClick = { showClearConfirmDialog = true },
                enabled = logs.isNotEmpty(),
            ) {
                Text(text = stringResource(R.string.settings_button_clear_logs))
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth()
        ) {
            itemsIndexed(logs, key = { _, log -> "${log.workflowId}-${log.timestamp}" }) { index, log ->
                LogRow(
                    log = log,
                    onClick = { onLogClick(log) }
                )
                if (index < logs.lastIndex) {
                    SectionDivider()
                }
            }
        }
    }

    if (showClearConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showClearConfirmDialog = false },
            title = {
                Text(text = stringResource(R.string.home_logs_clear_confirm_title))
            },
            text = {
                Text(text = stringResource(R.string.home_logs_clear_confirm_message))
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearLogs()
                        showClearConfirmDialog = false
                    }
                ) {
                    Text(text = stringResource(R.string.settings_button_clear_logs))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearConfirmDialog = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            }
        )
    }
}

@Composable
private fun LogDetailDialog(
    log: LogEntry,
    onDismiss: () -> Unit,
    onDelete: (LogEntry) -> Unit,
) {
    val context = LocalContext.current
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()) }
    val scrollState = rememberScrollState()

    // 复制按钮的取值必须与下面 `text` 槽里**渲染出来的内容逐字一致**，
    // 否则用户看到的和复制到的会是两份东西。
    // ⚠️ `resolveMessage` 的兜底文案、`detailedLog` 的空态兜底都要照搬：
    // 只拼"有值时"的分支会让复制结果在空日志时与显示不符。
    val basicInfo = log.resolveMessage(context) ?: stringResource(R.string.log_no_detail_message)
    val executionDetails = if (!log.detailedLog.isNullOrEmpty()) {
        log.detailedLog
    } else {
        stringResource(R.string.text_no_detailed_logs)
    }
    val plainTextForCopy = buildString {
        append(stringResource(R.string.log_details_title, log.workflowName))
        append('\n')
        append(
            stringResource(
                R.string.log_execution_time,
                dateFormat.format(Date(log.timestamp)),
            )
        )
        append('\n')
        append('\n')
        append(stringResource(R.string.label_basic_info))
        append('\n')
        append(basicInfo)
        append('\n')
        append(stringResource(R.string.log_workflow_id, log.workflowId))
        append('\n')
        append('\n')
        append(stringResource(R.string.text_execution_details))
        append('\n')
        append(executionDetails)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.button_close))
            }
        },
        dismissButton = {
            TextButton(onClick = { onDelete(log) }) {
                Text(stringResource(R.string.common_delete))
            }
        },
        title = {
            // ⚠️ 复制按钮**不能占用 dismissButton 槽** —— 那个位置是「删除」的，
            // 换掉就等于把这个功能删了。Material3 的 AlertDialog 只有 confirm /
            // dismiss 两个按钮槽，第三个动作只能自己找地方放，这里放标题行右侧。
            Row(verticalAlignment = Alignment.Top) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.log_details_title, log.workflowName),
                        style = MaterialTheme.typography.headlineSmall
                    )
                    Text(
                        text = stringResource(
                            R.string.log_execution_time,
                            dateFormat.format(Date(log.timestamp))
                        ),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 复制**不关弹窗** —— 用户多半要对着日志排错，关掉就得重新点进来
                IconButton(
                    onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(
                            ClipData.newPlainText(
                                context.getString(R.string.log_details_title, log.workflowName),
                                plainTextForCopy,
                            )
                        )
                        context.toast(context.getString(R.string.copied_to_clipboard))
                    }
                ) {
                    Icon(
                        painter = painterResource(R.drawable.rounded_content_copy_24),
                        contentDescription = stringResource(R.string.common_copy),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            // ⚠️ `SelectionContainer` 包在**滚动容器外面**：里面的基本信息、
            // 工作流 ID、执行详情三块要能被**跨块连续选中**，用户复制日志时
            // 通常是把详情整段拷走，而不是只挑其中一行。
            //
            // 包在里面（每块各包一层）只能单块选择；包在这一层则整块贯通。
            SelectionContainer {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 480.dp)
                        .verticalScroll(scrollState)
                ) {
                    Text(
                        text = stringResource(R.string.label_basic_info),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LogDetailCodeBlock(
                        text = basicInfo,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.log_workflow_id, log.workflowId),
                        modifier = Modifier.padding(top = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline
                    )
                    Text(
                        text = stringResource(R.string.text_execution_details),
                        modifier = Modifier.padding(top = 16.dp),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    LogDetailCodeBlock(
                        text = executionDetails,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
            }
        }
    )
}

@Composable
private fun LogDetailCodeBlock(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(12.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

@Composable
private fun LogRow(
    log: LogEntry,
    onClick: () -> Unit,
) {
    val iconRes = when (runCatching { log.status }.getOrNull()) {
        LogStatus.SUCCESS -> R.drawable.ic_log_success
        LogStatus.FAILURE, LogStatus.CANCELLED, null -> R.drawable.ic_log_failure
    }
    val iconTint = when (runCatching { log.status }.getOrNull()) {
        LogStatus.SUCCESS -> MaterialTheme.colorScheme.primary
        LogStatus.FAILURE, LogStatus.CANCELLED, null -> MaterialTheme.colorScheme.error
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Column(
            modifier = Modifier
                .padding(start = 20.dp)
                .weight(1f)
        ) {
            Text(
                text = log.workflowName,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = log.resolveMessage(LocalContext.current).orEmpty(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = DateUtils.getRelativeTimeSpanString(
                log.timestamp,
                System.currentTimeMillis(),
                DateUtils.MINUTE_IN_MILLIS
            ).toString(),
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1
        )
    }
}

@Composable
private fun QuickExecuteRow(
    workflow: Workflow,
    executionStateVersion: Int,
    onClick: () -> Unit,
) {
    val isRunning = remember(executionStateVersion, workflow.id) {
        WorkflowExecutor.isRunning(workflow.id)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .defaultMinSize(minHeight = 64.dp)
            .padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            modifier = Modifier.size(40.dp),
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.primaryContainer
        ) {
            Box(contentAlignment = Alignment.Center) {
                Icon(
                    painter = painterResource(
                        if (isRunning) R.drawable.rounded_pause_24 else R.drawable.rounded_play_arrow_24
                    ),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
        Text(
            text = workflow.name,
            modifier = Modifier.padding(start = 16.dp),
            style = MaterialTheme.typography.bodyLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
