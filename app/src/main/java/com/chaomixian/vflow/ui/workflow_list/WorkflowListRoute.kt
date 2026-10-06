package com.chaomixian.vflow.ui.workflow_list

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionStateBus
import com.chaomixian.vflow.core.execution.WorkflowExecutor
import com.chaomixian.vflow.core.workflow.FolderManager
import com.chaomixian.vflow.core.workflow.TileManager
import com.chaomixian.vflow.core.workflow.TileGate
import com.chaomixian.vflow.core.workflow.TileRefreshNotifier
import com.chaomixian.vflow.core.workflow.TileSlot
import com.chaomixian.vflow.core.workflow.model.TileKind
import com.chaomixian.vflow.core.workflow.TriggerExecutionCoordinator
import com.chaomixian.vflow.core.workflow.WorkflowBatchEnumMigrationPreview
import com.chaomixian.vflow.core.workflow.WorkflowEnumMigration
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.WorkflowPermissionRecovery
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.chaomixian.vflow.core.workflow.model.WorkflowTile
import com.chaomixian.vflow.permissions.PermissionActivity
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.common.ShortcutHelper
import com.chaomixian.vflow.ui.float.WorkflowsFloatPanelService
import com.chaomixian.vflow.ui.main.MainActivity
import com.chaomixian.vflow.ui.main.WorkflowLayoutMode
import com.chaomixian.vflow.ui.main.WorkflowSortMode
import com.chaomixian.vflow.ui.main.WorkflowTopBarAction
import com.chaomixian.vflow.ui.viewmodel.WorkflowListViewModel
import com.chaomixian.vflow.ui.workflow_editor.WorkflowEditorActivity
import com.chaomixian.vflow.ui.workflow_list.WorkflowImportHelper
import com.chaomixian.vflow.ui.workflow_list.WorkflowListItem
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.ArrayList
import java.util.Date
import java.util.LinkedList
import java.util.Locale
import java.util.UUID

private const val PREF_WORKFLOW_SORT_MODE = "workflow_sort_mode"
private data class TileSelectionTarget(
    val workflowId: String,
    /**
     * 用户是从哪一个池的菜单项进来的（§4.6 闸 2）。
     *
     * ⚠️ **必须带 `kind`** —— 面板只列这一池的槽位。不带的话两池的 0..19 会一起列出来，
     * 用户点错池**不会**失败（槽位合法），只是行为完全不同（一个执行、一个开关）。
     */
    val kind: TileKind,
)

@Composable
@OptIn(ExperimentalMaterial3Api::class)
fun WorkflowListRoute(
    activity: MainActivity,
    isActive: Boolean,
    workflowSortMode: WorkflowSortMode,
    workflowLayoutMode: WorkflowLayoutMode,
    workflowAction: WorkflowTopBarAction?,
    workflowActionVersion: Int,
    extraBottomPadding: androidx.compose.ui.unit.Dp,
    isWideLayout: Boolean,
    // 液态玻璃开关：内容区顶部的文件夹 Tab 栏是否走玻璃样式。
    // 由 `MainComposeShell` 从 `MainActivity.liquidGlassNavBarEnabled` 传下来。
    liquidGlassEnabled: Boolean = false,
    modifier: Modifier = Modifier,
    workflowListViewModel: WorkflowListViewModel = viewModel(),
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val uiState by workflowListViewModel.uiState.collectAsStateWithLifecycle()
    val workflowManager = remember(context) { WorkflowManager(context) }
    val folderManager = remember(context) { FolderManager(context) }
    val tileManager = remember(context) { TileManager(context) }
    val chineseCollator = remember { Collator.getInstance(Locale.CHINA).apply { strength = Collator.PRIMARY } }
    val gson = remember { Gson() }
    val delayedExecuteHandler = remember { Handler(Looper.getMainLooper()) }
    val importQueue = remember { LinkedList<Workflow>() }
    var conflictChoice by remember { mutableStateOf(ConflictChoice.ASK) }
    var pendingWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var pendingExportWorkflow by remember { mutableStateOf<Workflow?>(null) }
    var pendingExportFolderId by remember { mutableStateOf<String?>(null) }
    var pendingEnumMigrationPreview by remember { mutableStateOf<WorkflowBatchEnumMigrationPreview?>(null) }
    var dismissedEnumMigrationSignature by rememberSaveable { mutableStateOf<String?>(null) }
    var loadDataJob by remember { mutableStateOf<Job?>(null) }
    var requestBackup by remember { mutableStateOf<((String) -> Unit)?>(null) }
    var tileSelectionTarget by remember { mutableStateOf<TileSelectionTarget?>(null) }
    var tileSelectionVersion by remember { mutableStateOf(0) }
    val tileSheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = false)

    fun compareWithChineseCollator(selector: (WorkflowFolder) -> String): Comparator<WorkflowFolder> {
        return Comparator { a, b -> chineseCollator.compare(selector(a), selector(b)) }
    }

    fun compareWorkflowWithChineseCollator(selector: (Workflow) -> String): Comparator<Workflow> {
        return Comparator { a, b -> chineseCollator.compare(selector(a), selector(b)) }
    }

    fun persistWorkflowSortMode() {
        context.getSharedPreferences(MainActivity.PREFS_NAME, Activity.MODE_PRIVATE)
            .edit()
            .putString(PREF_WORKFLOW_SORT_MODE, workflowSortMode.name)
            .apply()
    }

    fun buildWorkflowItems(
        workflows: List<Workflow>,
        folders: List<WorkflowFolder>,
    ): MutableList<WorkflowListItem> {
        val items = mutableListOf<WorkflowListItem>()
        val sortedFolders = when (workflowSortMode) {
            WorkflowSortMode.Name -> folders.sortedWith(compareWithChineseCollator { it.name })
            else -> folders
        }
        sortedFolders.forEach { folder ->
            val folderWorkflows = workflows.filter { it.folderId == folder.id }
            val searchableContent = folderWorkflows.joinToString(separator = "\n") { workflow ->
                buildString {
                    append(workflow.name)
                    if (workflow.description.isNotBlank()) {
                        append('\n')
                        append(workflow.description)
                    }
                }
            }
            items.add(
                WorkflowListItem.FolderItem(
                    folder = folder,
                    workflowCount = folderWorkflows.size,
                    searchableContent = searchableContent,
                    childWorkflows = folderWorkflows
                )
            )
        }

        val rootWorkflows = workflows.filter { it.folderId == null }
        val sortedWorkflows = when (workflowSortMode) {
            WorkflowSortMode.Name -> rootWorkflows.sortedWith(compareWorkflowWithChineseCollator { it.name })
            WorkflowSortMode.RecentModified -> rootWorkflows.sortedByDescending { it.modifiedAt }
            WorkflowSortMode.FavoritesFirst -> rootWorkflows.sortedWith(
                compareByDescending<Workflow> { it.isFavorite }
            )
            WorkflowSortMode.Default -> rootWorkflows
        }
        sortedWorkflows.forEach { workflow ->
            items.add(WorkflowListItem.WorkflowItem(workflow))
        }
        return items
    }

    fun loadData(showMigrationPrompt: Boolean = false) {
        loadDataJob?.cancel()
        workflowListViewModel.setLoading(true)
        loadDataJob = scope.launch(Dispatchers.Default) {
            val workflows = workflowManager.getAllWorkflows()
            val folders = folderManager.getAllFolders()
            val items = buildWorkflowItems(workflows, folders)
            val migrationPreview = if (showMigrationPrompt) WorkflowEnumMigration.scan(workflows) else null

            withContext(Dispatchers.Main) {
                workflowListViewModel.setItems(items)
                // fork：原先这里会把「当前打开的文件夹」的内容同步进 ViewModel
                // （供底部弹窗用）。文件夹改成 Tab 栏后，内容由 `filterByFolderTab`
                // 直接从 `items` 里筛，这条同步链路已经不存在。
                if (showMigrationPrompt) {
                    maybePromptWorkflowEnumMigration(
                        context = context,
                        preview = migrationPreview,
                        dismissedEnumMigrationSignature = dismissedEnumMigrationSignature,
                        setDismissedSignature = { dismissedEnumMigrationSignature = it },
                        setPendingPreview = { pendingEnumMigrationPreview = it },
                        onApplyMigration = { previewToApply ->
                            previewToApply.migratedWorkflows.forEach(workflowManager::saveWorkflow)
                            dismissedEnumMigrationSignature = null
                            loadData()
                            Toast.makeText(
                                context,
                                context.getString(
                                    R.string.toast_workflow_enum_migration_success,
                                    previewToApply.affectedWorkflowCount,
                                    previewToApply.affectedFieldCount
                                ),
                                Toast.LENGTH_SHORT
                            ).show()
                        },
                        onRequestBackup = {
                            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                                .format(Date())
                            requestBackup?.invoke("vflow_backup_before_enum_migration_${timestamp}.json")
                        }
                    )
                }
            }

            launch(Dispatchers.IO) {
                ShortcutHelper.updateShortcuts(context)
            }
        }
    }

    val importHelper = remember(context, workflowManager, folderManager) {
        WorkflowImportHelper(
            context,
            workflowManager,
            folderManager
        ) { loadData() }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            pendingWorkflow?.let { workflow ->
                if (!workflow.silentExecution) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_starting_workflow, workflow.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                WorkflowExecutor.execute(
                    workflow = workflow,
                    context = context,
                    triggerStepId = workflow.manualTrigger()?.id
                )
            }
        }
        pendingWorkflow = null
    }

    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (checkOverlayPermission(context)) {
            context.startService(
                Intent(context, WorkflowsFloatPanelService::class.java).apply {
                    action = WorkflowsFloatPanelService.ACTION_SHOW
                }
            )
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.toast_overlay_permission_required),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val exportSingleLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { fileUri ->
            pendingExportWorkflow?.let { workflow ->
                try {
                    val exportData = createWorkflowExportData(gson, workflow)
                    val jsonString = gson.toJson(exportData)
                    writeTextToDocumentUri(context, fileUri, jsonString)
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_export_success),
                        Toast.LENGTH_SHORT
                    ).show()
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        pendingExportWorkflow = null
    }

    val exportFolderLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri?.let { fileUri ->
            pendingExportFolderId?.let { folderId ->
                try {
                    val folder = folderManager.getFolder(folderId)
                    val workflows = workflowManager.getAllWorkflows().filter { it.folderId == folderId }
                    if (folder != null) {
                        val workflowsWithMeta = workflows.map { createWorkflowExportData(gson, it) }
                        val exportData = mapOf("folder" to folder, "workflows" to workflowsWithMeta)
                        val jsonString = gson.toJson(exportData)
                        writeTextToDocumentUri(context, fileUri, jsonString)
                        Toast.makeText(
                            context,
                            context.getString(R.string.toast_folder_export_success),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_export_failed, e.message ?: ""),
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        pendingExportFolderId = null
    }

    val backupLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        val migrationPreview = pendingEnumMigrationPreview
        uri?.let { fileUri ->
            try {
                backupAllWorkflowsToUri(context, workflowManager, folderManager, gson, fileUri)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_success),
                    Toast.LENGTH_SHORT
                ).show()
                migrationPreview?.let { preview ->
                    preview.migratedWorkflows.forEach(workflowManager::saveWorkflow)
                    dismissedEnumMigrationSignature = null
                    loadData()
                    Toast.makeText(
                        context,
                        context.getString(
                            R.string.toast_workflow_enum_migration_success,
                            preview.affectedWorkflowCount,
                            preview.affectedFieldCount
                        ),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_backup_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
        pendingEnumMigrationPreview = null
    }
    requestBackup = backupLauncher::launch

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data
        uri?.let { fileUri ->
            try {
                val jsonString = context.contentResolver.openInputStream(fileUri)?.use {
                    BufferedReader(InputStreamReader(it)).readText()
                } ?: throw Exception(context.getString(R.string.error_cannot_read_file))
                importHelper.importFromJson(jsonString)
            } catch (e: Exception) {
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_import_failed, e.message ?: ""),
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        ExecutionStateBus.stateFlow.collectLatest {
            workflowListViewModel.bumpExecutionStateVersion()
        }
    }

    DisposableEffect(lifecycleOwner, context) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                workflowListViewModel.bumpExecutionStateVersion()
                scope.launch(Dispatchers.IO) {
                    WorkflowPermissionRecovery.recoverEligibleWorkflows(context)
                    withContext(Dispatchers.Main) {
                        loadData(showMigrationPrompt = true)
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            delayedExecuteHandler.removeCallbacksAndMessages(null)
        }
    }

    LaunchedEffect(isActive) {
        if (isActive) {
            workflowListViewModel.bumpExecutionStateVersion()
            scope.launch(Dispatchers.IO) {
                WorkflowPermissionRecovery.recoverEligibleWorkflows(context)
                withContext(Dispatchers.Main) {
                    loadData(showMigrationPrompt = true)
                }
            }
        }
    }

    LaunchedEffect(workflowSortMode) {
        persistWorkflowSortMode()
        loadData()
    }

    LaunchedEffect(workflowActionVersion) {
        when (workflowAction) {
            WorkflowTopBarAction.FavoriteFloat -> {
                if (checkOverlayPermission(context)) {
                    context.startService(
                        Intent(context, WorkflowsFloatPanelService::class.java).apply {
                            action = WorkflowsFloatPanelService.ACTION_SHOW
                        }
                    )
                } else {
                    requestOverlayPermission(context, overlayPermissionLauncher::launch)
                }
            }

            WorkflowTopBarAction.CreateFolder -> {
                showCreateFolderDialog(context, folderManager) { loadData() }
            }

            WorkflowTopBarAction.BackupWorkflows -> {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                    .format(Date())
                backupLauncher.launch("vflow_backup_${timestamp}.json")
            }

            WorkflowTopBarAction.ImportWorkflows -> {
                importLauncher.launch(
                    Intent(Intent.ACTION_GET_CONTENT).apply {
                        type = "application/json"
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                )
            }

            WorkflowTopBarAction.SortDefault,
            WorkflowTopBarAction.SortByName,
            WorkflowTopBarAction.SortByRecentModified,
            WorkflowTopBarAction.SortFavoritesFirst,
            WorkflowTopBarAction.ToggleLayoutMode,
            null -> Unit
        }
    }

    WorkflowListScreen(
        uiState = uiState,
        layoutMode = workflowLayoutMode,
        isWideLayout = isWideLayout,
        liquidGlassEnabled = liquidGlassEnabled,
        extraBottomPadding = extraBottomPadding,
        modifier = modifier,
        actions = WorkflowListScreenActions(
            onCreateWorkflow = {
                context.startActivity(Intent(context, WorkflowEditorActivity::class.java))
            },
            onToggleFavorite = { workflow ->
                workflowManager.saveWorkflow(workflow.copy(isFavorite = !workflow.isFavorite))
                ShortcutHelper.updateShortcuts(context)
                loadData()
            },
            onToggleEnabled = { workflow, enabled ->
                val appContext = context.applicationContext
                val updatedWorkflow = workflow.copy(
                    isEnabled = enabled,
                    wasEnabledBeforePermissionsLost = false
                )
                workflowManager.saveWorkflow(updatedWorkflow)
                loadData()
                if (!enabled || !workflow.hasAutoTriggers()) return@WorkflowListScreenActions

                scope.launch {
                    val latestWorkflow = workflowManager.getWorkflow(workflow.id) ?: return@launch
                    val remainingPermissions = withContext(Dispatchers.IO) {
                        TriggerExecutionCoordinator.recoverMissingPermissions(appContext, latestWorkflow)
                    }
                    if (remainingPermissions.isEmpty()) {
                        workflowListViewModel.bumpExecutionStateVersion()
                        return@launch
                    }
                    val currentWorkflow = workflowManager.getWorkflow(workflow.id) ?: return@launch
                    if (!currentWorkflow.isEnabled) return@launch
                    workflowManager.saveWorkflow(
                        currentWorkflow.copy(
                            isEnabled = false,
                            wasEnabledBeforePermissionsLost = true
                        )
                    )
                    loadData()
                    Toast.makeText(
                        context,
                        context.getString(R.string.toast_missing_permissions_cannot_enable_workflow),
                        Toast.LENGTH_SHORT
                    ).show()
                }
            },
            onOpenWorkflow = { workflow ->
                context.startActivity(
                    Intent(context, WorkflowEditorActivity::class.java).apply {
                        putExtra(WorkflowEditorActivity.EXTRA_WORKFLOW_ID, workflow.id)
                    }
                )
            },
            onDeleteWorkflow = { workflow ->
                MaterialAlertDialogBuilder(context)
                    .setTitle(R.string.dialog_delete_title)
                    .setMessage(context.getString(R.string.dialog_delete_message, workflow.name))
                    .setNegativeButton(R.string.common_cancel, null)
                    .setPositiveButton(R.string.common_delete) { _, _ ->
                        workflowManager.deleteWorkflow(workflow.id)
                        loadData()
                    }
                    .show()
            },
            onDuplicateWorkflow = { workflow ->
                workflowManager.duplicateWorkflow(workflow.id)
                Toast.makeText(
                    context,
                    context.getString(R.string.toast_copied_as, workflow.name),
                    Toast.LENGTH_SHORT
                ).show()
                loadData()
            },
            onExportWorkflow = { workflow ->
                pendingExportWorkflow = workflow
                exportSingleLauncher.launch("${workflow.name}.json")
            },
            onExecuteWorkflow = { workflow ->
                if (WorkflowExecutor.isRunning(workflow.id)) {
                    WorkflowExecutor.stopExecution(workflow.id)
                } else {
                    val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
                    if (missingPermissions.isEmpty()) {
                        if (!workflow.silentExecution) {
                            Toast.makeText(
                                context,
                                context.getString(R.string.toast_starting_workflow, workflow.name),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                        WorkflowExecutor.execute(
                            workflow = workflow,
                            context = context,
                            triggerStepId = workflow.manualTrigger()?.id
                        )
                    } else {
                        pendingWorkflow = workflow
                        permissionLauncher.launch(
                            Intent(context, PermissionActivity::class.java).apply {
                                putParcelableArrayListExtra(
                                    PermissionActivity.EXTRA_PERMISSIONS,
                                    ArrayList(missingPermissions)
                                )
                                putExtra(PermissionActivity.EXTRA_WORKFLOW_NAME, workflow.name)
                            }
                        )
                    }
                }
            },
            onExecuteWorkflowDelayed = { workflow, delayMs ->
                val delayText = when (delayMs) {
                    5_000L -> context.getString(R.string.workflow_execute_delay_5s)
                    15_000L -> context.getString(R.string.workflow_execute_delay_15s)
                    60_000L -> context.getString(R.string.workflow_execute_delay_1min)
                    else -> context.getString(R.string.workflow_execute_delay_seconds, delayMs / 1000)
                }
                // 延时预约的确认横幅同样静默——这是「立即执行」的另一种形态，
                // 若立即静默、延时仍弹，同一开关下两种行为不一致，看起来像 bug。
                if (!workflow.silentExecution) {
                    Toast.makeText(
                        context,
                        context.getString(R.string.workflow_execute_delayed, delayText, workflow.name),
                        Toast.LENGTH_SHORT
                    ).show()
                }
                delayedExecuteHandler.postDelayed({
                    val missingPermissions = PermissionManager.getMissingPermissions(context, workflow)
                    if (missingPermissions.isEmpty()) {
                        WorkflowExecutor.execute(
                            workflow = workflow,
                            context = context,
                            triggerStepId = workflow.manualTrigger()?.id
                        )
                    }
                }, delayMs)
            },
            onAddShortcut = { workflow ->
                ShortcutHelper.requestPinnedShortcut(context, workflow)
            },
            onAddToTile = { workflow, kind ->
                // ⚠️⚠️ 闸 1 已把不该出现的菜单项藏起来了，但**这里再判一次**：
                //    `shouldShowMenu` 的判据与这里可能因重组时序失配（菜单弹出后用户
                //    在别处改了触发器再回来点），而绑定一次错误的池会**静默**产生
                //    一个行为完全不对的磁贴（§4.6 闸 3 同理，两处判据都走 TileGate）。
                if (!TileGate.accepts(kind, workflow)) {
                    Toast.makeText(
                        context,
                        context.getString(TileGate.mismatchMessageRes(kind)),
                        Toast.LENGTH_SHORT
                    ).show()
                    return@WorkflowListScreenActions
                }
                tileSelectionTarget = TileSelectionTarget(workflowId = workflow.id, kind = kind)
            },
            onCopyWorkflowId = { workflow ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Workflow ID", workflow.id))
                Toast.makeText(context, R.string.workflow_id_copied, Toast.LENGTH_SHORT).show()
            },
            onMoveWorkflowToFolder = { workflow ->
                showMoveToFolderDialog(context, folderManager, workflowManager, workflowSortMode, chineseCollator, workflow) {
                    loadData()
                }
            },
            onRenameFolder = { folderId ->
                showRenameFolderDialog(context, folderManager, folderId) { loadData() }
            },
            onExportFolder = { folderId ->
                pendingExportFolderId = folderId
                val folder = folderManager.getFolder(folderId)
                exportFolderLauncher.launch("${folder?.name ?: "folder"}.json")
            },
            onDissolveFolder = { folderId ->
                showDissolveFolderConfirmationDialog(context, folderManager, workflowManager, folderId) {
                    loadData()
                }
            },
            onDeleteFolder = { folderId ->
                showDeleteFolderConfirmationDialog(context, folderManager, workflowManager, folderId) {
                    loadData()
                }
            },
            onPersistWorkflowOrder = { workflows ->
                workflowManager.saveAllWorkflows(workflows)
                ShortcutHelper.updateShortcuts(context)
                loadData()
            }
        )
    )

    tileSelectionTarget?.let { target ->
        val tileItems = remember(target.workflowId, target.kind, tileSelectionVersion, uiState.executionStateVersion) {
            // ⚠️ 只列**这一池**的槽位（§4.6 闸 2）。`getAllTilesWithEmpty(kind)` 与
            //    `TileSlot` 的区间定义同源，不会出现「面板显示 20 个但其中几个属于另一池」。
            tileManager.getAllTilesWithEmpty(target.kind).map { tile ->
                TileSelectionItem(
                    tileIndex = tile.tileIndex,
                    assignedWorkflowName = tile.workflowId?.let { workflowId ->
                        workflowManager.getWorkflow(workflowId)?.name
                    },
                    isSelected = tile.workflowId == target.workflowId,
                    kind = target.kind,
                )
            }
        }
        androidx.compose.material3.ModalBottomSheet(
            onDismissRequest = { tileSelectionTarget = null },
            sheetState = tileSheetState,
            containerColor = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        ) {
            TileSelectionSheet(
                items = tileItems,
                kind = target.kind,
                onSelect = { item ->
                    val slot = TileSlot.indexInKind(item.tileIndex) ?: item.tileIndex
                    if (item.isSelected) {
                        tileManager.removeTile(item.tileIndex)
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.tile_removed,
                                TileSlot.displayName(target.kind, slot)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        tileSelectionVersion++
                    } else {
                        // ⚠️ 解绑时**按 (workflowId, kind) 删**，不是无差别删 ——
                        //    两池互斥后同一工作流不会同时在两池，但「先在执行池解绑、
                        //    再去开关池绑定」之间若用无差别删，会把用户刚在另一池
                        //    绑好的也一起删掉（无提示）。
                        tileManager.removeTileByWorkflowIdInKind(target.workflowId, target.kind)
                        tileManager.saveTile(
                            WorkflowTile(
                                tileIndex = item.tileIndex,
                                workflowId = target.workflowId,
                                kind = target.kind,
                            )
                        )
                        Toast.makeText(
                            context,
                            context.getString(
                                R.string.tile_added,
                                TileSlot.displayName(target.kind, slot)
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                        tileSelectionVersion++
                    }
                    // ⚠️ 绑定/解绑**不经过** `WorkflowManager.saveWorkflow`，故不会自动
                    //    触发 `TileRefreshNotifier` —— 必须显式刷一次，否则磁贴要等
                    //    下次下拉面板才知道自己换了工作流（§4.4 第四处调用点）。
                    TileRefreshNotifier.requestAll(context)
                }
            )
        }
    }
}

private enum class ConflictChoice { ASK, REPLACE_ALL, KEEP_ALL }

private fun createWorkflowExportData(gson: Gson, workflow: Workflow): Map<String, Any?> {
    return mapOf(
        "id" to workflow.id,
        "name" to workflow.name,
        "triggers" to workflow.triggers,
        "steps" to workflow.steps,
        "isEnabled" to workflow.isEnabled,
        "isFavorite" to workflow.isFavorite,
        "wasEnabledBeforePermissionsLost" to workflow.wasEnabledBeforePermissionsLost,
        "folderId" to workflow.folderId,
        "order" to workflow.order,
        "shortcutName" to workflow.shortcutName,
        "shortcutIconRes" to workflow.shortcutIconRes,
        "cardIconRes" to workflow.cardIconRes,
        "cardThemeColor" to workflow.cardThemeColor,
        "modifiedAt" to workflow.modifiedAt,
        "version" to workflow.version,
        "vFlowLevel" to workflow.vFlowLevel,
        "description" to workflow.description,
        "author" to workflow.author,
        "homepage" to workflow.homepage,
        "tags" to workflow.tags
    )
}

private fun backupAllWorkflowsToUri(
    context: Context,
    workflowManager: WorkflowManager,
    folderManager: FolderManager,
    gson: Gson,
    fileUri: Uri,
) {
    val allWorkflows = workflowManager.getAllWorkflows()
    val allFolders = folderManager.getAllFolders()
    val workflowsWithMeta = allWorkflows.map { createWorkflowExportData(gson, it) }
    val backupData = mapOf("workflows" to workflowsWithMeta, "folders" to allFolders)
    val jsonString = gson.toJson(backupData)
    writeTextToDocumentUri(context, fileUri, jsonString)
}

private fun writeTextToDocumentUri(context: Context, fileUri: Uri, text: String) {
    val outputStream = openDocumentOutputStream(context, fileUri)
    outputStream.use { stream ->
        stream.write(text.toByteArray(Charsets.UTF_8))
        stream.flush()
    }
}

private fun openDocumentOutputStream(context: Context, fileUri: Uri): OutputStream {
    return resolveDocumentOutputStream(
        openWithMode = { mode -> context.contentResolver.openOutputStream(fileUri, mode) },
        openDefault = { context.contentResolver.openOutputStream(fileUri) }
    )
}

internal fun resolveDocumentOutputStream(
    openWithMode: (String) -> OutputStream?,
    openDefault: () -> OutputStream?
): OutputStream {
    return openWithMode("wt")
        ?: openDefault()
        ?: throw IllegalStateException("Failed to open output stream")
}

private fun maybePromptWorkflowEnumMigration(
    context: Context,
    preview: WorkflowBatchEnumMigrationPreview?,
    dismissedEnumMigrationSignature: String?,
    setDismissedSignature: (String?) -> Unit,
    setPendingPreview: (WorkflowBatchEnumMigrationPreview) -> Unit,
    onApplyMigration: (WorkflowBatchEnumMigrationPreview) -> Unit,
    onRequestBackup: () -> Unit,
) {
    if (preview == null) {
        setDismissedSignature(null)
        return
    }

    val signature = preview.previews
        .sortedBy { it.originalWorkflow.id }
        .joinToString(separator = "|") {
            "${it.originalWorkflow.id}:${it.originalWorkflow.modifiedAt}:${it.affectedFieldCount}"
        }
    if (signature == dismissedEnumMigrationSignature) return

    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.dialog_workflow_enum_migration_title)
        .setMessage(
            context.getString(
                R.string.dialog_workflow_enum_migration_batch_message,
                preview.affectedWorkflowCount,
                preview.affectedStepCount,
                preview.affectedFieldCount
            )
        )
        .setPositiveButton(R.string.common_yes) { _, _ ->
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.dialog_workflow_enum_migration_backup_title)
                .setMessage(R.string.dialog_workflow_enum_migration_backup_message)
                .setPositiveButton(R.string.common_yes) { _, _ ->
                    setPendingPreview(preview)
                    onRequestBackup()
                }
                .setNegativeButton(R.string.common_no) { _, _ ->
                    onApplyMigration(preview)
                }
                .setNeutralButton(R.string.common_cancel) { _, _ ->
                    setDismissedSignature(signature)
                }
                .setOnCancelListener {
                    setDismissedSignature(signature)
                }
                .show()
        }
        .setNegativeButton(R.string.common_no) { _, _ ->
            setDismissedSignature(signature)
        }
        .setOnCancelListener {
            setDismissedSignature(signature)
        }
        .show()
}

private fun checkOverlayPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        Settings.canDrawOverlays(context)
    } else {
        true
    }
}

private fun requestOverlayPermission(
    context: Context,
    launch: (Intent) -> Unit,
) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        MaterialAlertDialogBuilder(context)
            .setTitle(context.getString(R.string.dialog_overlay_permission_title))
            .setMessage(context.getString(R.string.dialog_overlay_permission_message))
            .setPositiveButton(context.getString(R.string.dialog_button_go_to_settings)) { _, _ ->
                launch(
                    Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:${context.packageName}")
                    )
                )
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }
}

private fun showCreateFolderDialog(
    context: Context,
    folderManager: FolderManager,
    onChanged: () -> Unit,
) {
    val editText = EditText(context).apply {
        hint = context.getString(R.string.folder_name_hint)
        setPadding(48, 32, 48, 32)
    }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.folder_create)
        .setView(editText)
        .setPositiveButton(R.string.common_confirm) { _, _ ->
            val name = editText.text.toString().trim()
            if (name.isNotEmpty()) {
                folderManager.saveFolder(WorkflowFolder(name = name))
                Toast.makeText(context, context.getString(R.string.toast_folder_created), Toast.LENGTH_SHORT).show()
                onChanged()
            } else {
                Toast.makeText(context, context.getString(R.string.toast_folder_name_empty), Toast.LENGTH_SHORT).show()
            }
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}

private fun showRenameFolderDialog(
    context: Context,
    folderManager: FolderManager,
    folderId: String,
    onChanged: () -> Unit,
) {
    val folder = folderManager.getFolder(folderId) ?: return
    val editText = EditText(context).apply {
        setText(folder.name)
        hint = context.getString(R.string.folder_name_hint)
        setPadding(48, 32, 48, 32)
    }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.folder_rename)
        .setView(editText)
        .setPositiveButton(R.string.common_confirm) { _, _ ->
            val name = editText.text.toString().trim()
            if (name.isNotEmpty()) {
                folderManager.saveFolder(folder.copy(name = name))
                Toast.makeText(context, context.getString(R.string.toast_folder_renamed), Toast.LENGTH_SHORT).show()
                onChanged()
            }
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}

private fun showMoveToFolderDialog(
    context: Context,
    folderManager: FolderManager,
    workflowManager: WorkflowManager,
    workflowSortMode: WorkflowSortMode,
    chineseCollator: Collator,
    workflow: Workflow,
    onChanged: () -> Unit,
) {
    val folders = folderManager.getAllFolders().let { allFolders ->
        when (workflowSortMode) {
            WorkflowSortMode.Name -> allFolders.sortedWith { a, b -> chineseCollator.compare(a.name, b.name) }
            else -> allFolders
        }
    }
    if (folders.isEmpty()) {
        Toast.makeText(context, context.getString(R.string.dialog_move_to_folder_no_folders), Toast.LENGTH_SHORT).show()
        return
    }
    val folderNames = folders.map { it.name }.toTypedArray()
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.dialog_move_to_folder_title)
        .setItems(folderNames) { _, which ->
            val folder = folders[which]
            workflowManager.saveWorkflow(workflow.copy(folderId = folder.id))
            Toast.makeText(
                context,
                context.getString(R.string.toast_workflow_moved_to_folder, workflow.name, folder.name),
                Toast.LENGTH_SHORT
            ).show()
            onChanged()
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}

/**
 * 「解散文件夹」：删掉文件夹本身，**里面的工作流保留**（移到根目录）。
 *
 * ⚠️ 与 [showDeleteFolderConfirmationDialog] 是**两件完全不同的事**，
 * 只是共用了「删文件夹」这个动作名。历史上只有这一个入口、语义就是「保留工作流」，
 * 现在拆成两个（用户 2026-10-05 要求），文案必须把差别说清 ——
 * 点错的那一个会直接删掉用户的工作流，而本项目**没有版本历史、没有撤销**。
 */
private fun showDissolveFolderConfirmationDialog(
    context: Context,
    folderManager: FolderManager,
    workflowManager: WorkflowManager,
    folderId: String,
    onChanged: () -> Unit,
) {
    val folder = folderManager.getFolder(folderId) ?: return
    val affectedCount = workflowManager.getAllWorkflows().count { it.folderId == folderId }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.dialog_folder_dissolve_title)
        .setMessage(
            context.getString(
                R.string.dialog_folder_dissolve_message,
                folder.name,
                affectedCount,
            )
        )
        .setPositiveButton(R.string.folder_dissolve) { _, _ ->
            workflowManager.getAllWorkflows()
                .filter { it.folderId == folderId }
                .forEach { workflow -> workflowManager.saveWorkflow(workflow.copy(folderId = null)) }
            folderManager.deleteFolder(folderId)
            Toast.makeText(context, context.getString(R.string.toast_folder_dissolved), Toast.LENGTH_SHORT).show()
            onChanged()
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}

/**
 * 「删除文件夹」：文件夹**连同里面所有工作流**一起删掉。
 *
 * ⚠️ 走 [WorkflowManager.deleteWorkflow] 逐个删，而**不是**只把 `folderId` 置空 ——
 * 后者是「解散」的语义。逐个删还能顺带触发它内部的
 * `TriggerServiceProxy.notifyWorkflowRemoved`（更新快捷方式 / 撤销触发器调度），
 * 少走任何一步都会留下「工作流没了但触发器还在调度」这类残影。
 *
 * ⚠️ 这是**不可逆**的（没有版本历史、没有撤销），故对话框里显式报出会被删掉的数量。
 */
private fun showDeleteFolderConfirmationDialog(
    context: Context,
    folderManager: FolderManager,
    workflowManager: WorkflowManager,
    folderId: String,
    onChanged: () -> Unit,
) {
    val folder = folderManager.getFolder(folderId) ?: return
    val victims = workflowManager.getAllWorkflows().filter { it.folderId == folderId }
    MaterialAlertDialogBuilder(context)
        .setTitle(R.string.dialog_folder_delete_title)
        .setMessage(
            context.getString(
                R.string.dialog_folder_delete_message,
                folder.name,
                victims.size,
            )
        )
        .setPositiveButton(R.string.common_delete) { _, _ ->
            victims.forEach { workflow -> workflowManager.deleteWorkflow(workflow.id) }
            folderManager.deleteFolder(folderId)
            Toast.makeText(context, context.getString(R.string.toast_folder_deleted), Toast.LENGTH_SHORT).show()
            onChanged()
        }
        .setNegativeButton(R.string.common_cancel, null)
        .show()
}
