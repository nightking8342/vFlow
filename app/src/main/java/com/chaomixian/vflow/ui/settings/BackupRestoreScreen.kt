// 文件: main/java/com/chaomixian/vflow/ui/settings/BackupRestoreScreen.kt
// 描述: 备份/恢复二级页（fork 新增）。导出走 SAF，导入先探测加密段再决定是否要口令。
package com.chaomixian.vflow.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.backup.AndroidBackupEnvironment
import com.chaomixian.vflow.core.backup.BackupEnvelope
import com.chaomixian.vflow.core.backup.BackupPipeline
import com.chaomixian.vflow.core.backup.BackupScope
import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopeImportResult
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.workflow.TileRefreshNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val TAG = "BackupRestore"

/** 屏幕内的 UI 模型（不是 core 类型）。 */
private data class ImportSummary(
    val title: String,
    val body: String,
    val warningLines: List<String>,
    val isError: Boolean,
    /**
     * 是否提供「重新输入口令」的入口。
     *
     * ⚠️ **只有 `WrongPassphrase` 才给** —— 见 `runImport` 两处注释：
     * 损坏时口令已经是对的，给重输入口会把用户引向错误方向。
     */
    val retryPassphrase: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupRestoreScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // ⚠️⚠️ 勾选清单的**唯一来源**。不得在任何地方硬编码第二份 ——
    //     硬编码会让新增的 scope 永远不出现在界面上，且没有任何报错
    //     （静默失效点 13，由 BackupRestoreWiringTest 机器化锁住）。
    val scopes: List<BackupScope> = remember { BackupScopeRegistry.all() }
    val selectedIds = remember {
        mutableStateListOf<String>().apply {
            addAll(scopes.filter { it.defaultIncluded }.map { it.id })
        }
    }

    val hasSensitiveScope = remember(scopes) { scopes.any { it.sensitive } }
    var includeSecrets by remember { mutableStateOf(false) }
    var passphrase by remember { mutableStateOf("") }
    var passphraseRepeat by remember { mutableStateOf("") }
    var exportError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var resultBanner by remember { mutableStateOf<String?>(null) }

    var pendingImportText by remember { mutableStateOf<String?>(null) }
    /**
     * 这份待导入备份在**导出时**被清空的字段位置（`summary.scrubbedFields`）。
     *
     * ⚠️⚠️ **必须在选「覆盖式」之前就告诉用户** —— REPLACE 不可撤销，而
     * 「工作流里的 api_key 被清空」在导入后只表现为「工作流不工作了」，
     * 用户会去查执行日志/模块/权限，查不到「导出时就没带密钥」。
     */
    var pendingScrubbedFields by remember { mutableStateOf<List<String>>(emptyList()) }
    var importModeDialogVisible by remember { mutableStateOf(false) }
    var replaceConfirmVisible by remember { mutableStateOf(false) }
    var passphraseDialogVisible by remember { mutableStateOf(false) }
    var importPassphrase by remember { mutableStateOf("") }
    var importSummary by remember { mutableStateOf<ImportSummary?>(null) }
    var pendingMode by remember { mutableStateOf(ImportMode.MERGE) }

    // ── 诊断出口：warningSink 默认丢弃，接到 DebugLogger 才看得见 ⚠️ ──
    remember {
        BackupScopeRegistry.warningSink = { DebugLogger.w(TAG, it) }
        BackupEnvelope.warningSink = { DebugLogger.w(TAG, it) }
        true
    }

    fun defaultFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        return "vflow_backup_$stamp.json"
    }

    /** 跑一次导入并渲染结果。**只在这一处调用 import**，避免 REPLACE 被跑两遍。 */
    fun runImport(text: String, mode: ImportMode, pw: String?) {
        busy = true
        scope.launch {
            val summary = withContext(Dispatchers.IO) {
                val env = AndroidBackupEnvironment(context.applicationContext)
                val secrets = pw?.takeIf { it.isNotEmpty() }?.let { SecretContext(it.toCharArray()) }
                val outcome = BackupPipeline.import(env, text, mode, secrets)

                // ⚠️⚠️ fork（2026-10-06）**磁贴刷新的第四处调用点**（设计 §4.4）。
                //     备份导入是唯一**不经过** `WorkflowManager.saveWorkflow` 的写入路径：
                //     REPLACE 走 `replaceAllWorkflows`、MERGE 走 `saveAllWorkflows`
                //     ⇒ 那两个方法都不会通知磁贴。漏了这里的表现是
                //     「导入一份备份后磁贴还是导入前的名字 / 图标」，用户会以为导入失败。
                //
                //     ⚠️ 判据用「这两种 outcome 才真的写过盘」而不是无条件调：
                //     `Rejected` / `Corrupted` / `WrongPassphrase` 三种**一个字节都没写**，
                //     对它们发 40 次跨进程调用纯属浪费。
                //     ⚠️ **`PassphraseRequired` 也必须算** —— 它是「非加密 scope 已经真的
                //     导入了一遍、只是加密段还要口令」，`results` 里已经有内容了。
                //     ⚠️ 本块**在 `Dispatchers.IO` 里**，故 `TileRefreshNotifier` 内部
                //     自己投主线程这件事是必需的、不是可选的。
                if (outcome is BackupPipeline.ImportOutcome.Done ||
                    outcome is BackupPipeline.ImportOutcome.PassphraseRequired
                ) {
                    TileRefreshNotifier.requestAll(context)
                }

                when (outcome) {
                    is BackupPipeline.ImportOutcome.Done -> ImportSummary(
                        title = context.getString(R.string.backup_restore_import_done),
                        body = renderResults(context, outcome.results),
                        // ⚠️⚠️ 被清空的字段位置**必须展示**（与导出侧对称）：
                        //     导入的是「已清洗」的值，用户若不被告知，
                        //     只会看到工作流不工作，查不到「导出时就没带密钥」。
                        //     它是**导入前**从同一份文本里读出来的（`pendingScrubbedFields`），
                        //     因为 `ImportOutcome.Done` 不携带 summary。
                        warningLines = scrubbedWarningOf(pendingScrubbedFields)?.let {
                            listOf(context.getString(it.messageRes, it.fieldsText))
                        } ?: emptyList(),
                        isError = false,
                    )

                    BackupPipeline.ImportOutcome.WrongPassphrase -> ImportSummary(
                        title = context.getString(R.string.backup_restore_wrong_passphrase),
                        body = "",
                        warningLines = emptyList(),
                        // ⚠️ 「口令错」与「文件损坏」必须是**两条不同的提示** ——
                        //     二者在加密层都表现为解密失败，判错了会让用户反复重输
                        //     一个本来就对的口令（见 BackupCrypto.judge 的 KDoc）。
                        isError = true,
                        // ⚠️ 这一支**必须允许重输**（方案 §5.5）：
                        //     用户第一次可能只是打错了一个字母。重输仍走同一条
                        //     一次性路径（不会把非加密 scope 导第二遍）。
                        retryPassphrase = true,
                    )

                    is BackupPipeline.ImportOutcome.Corrupted -> ImportSummary(
                        title = context.getString(R.string.backup_restore_corrupted),
                        body = outcome.reason,
                        warningLines = emptyList(),
                        // ⚠️ 损坏**不**给重输入口 —— 口令是对的（verifier 已通过），
                        //     再输多少次都一样，给了只会把用户引向错误的方向。
                        isError = true,
                        retryPassphrase = false,
                    )

                    is BackupPipeline.ImportOutcome.PassphraseRequired -> ImportSummary(
                        title = context.getString(R.string.backup_restore_import_done),
                        body = renderResults(context, outcome.results),
                        warningLines = listOf(
                            context.getString(
                                R.string.backup_restore_skipped_scopes,
                                outcome.skippedScopes.joinToString(", "),
                            )
                        ),
                        isError = false,
                    )

                    is BackupPipeline.ImportOutcome.Rejected -> ImportSummary(
                        title = context.getString(R.string.backup_restore_import_rejected_title),
                        body = outcome.reason,
                        warningLines = emptyList(),
                        isError = true,
                    )
                }
            }
            busy = false
            importSummary = summary
        }
    }

    /**
     * 模式已选定的**唯一入口**：先纯解析探测是否需要口令，再决定弹框还是直接导入。
     *
     * ⚠️⚠️ **先探测再加口令，而不是「无口令先试导一次」**。
     * 后者在含加密段的备份上会把非加密 scope **真的导入一遍**，再带口令导第二遍
     * —— REPLACE 模式下等于把破坏性操作跑两次（`BackupPipeline.importModern`
     * 的 `PassphraseRequired` 分支已经真的写过盘了）。这里先纯解析探测，
     * 导入只发生一次。
     */
    fun startImportWithProbe() {
        val text = pendingImportText ?: return
        if (needsPassphraseProbe(context, text)) {
            importPassphrase = ""
            passphraseDialogVisible = true
        } else {
            runImport(text, pendingMode, null)
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    val env = AndroidBackupEnvironment(context.applicationContext)
                    val secrets = if (includeSecrets) SecretContext(passphrase.toCharArray()) else null
                    val result = BackupPipeline.export(env, selectedIds.toSet(), secrets)
                    context.contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(result.text.toByteArray(Charsets.UTF_8))
                    } ?: error("openOutputStream 返回 null")
                    result
                }
            }
            busy = false
            outcome.fold(
                onSuccess = { r ->
                    // ⚠️ 被清洗的字段**必须展示**（方案 §5.7）：不展示等于静默丢弃 ——
                    //     用户永远不知道哪些密钥被抹掉了，还以为备份是完整的。
                    //     只在有清洗时列出前 N 项 + 总数。
                    resultBanner = buildString {
                        append(context.getString(R.string.backup_restore_export_success))
                        if (r.scrubbedFields.isNotEmpty()) {
                            append(" · ")
                            append(
                                context.getString(
                                    R.string.backup_restore_export_scrubbed,
                                    r.scrubbedFields.size,
                                )
                            )
                            append("\n")
                            append(
                                context.getString(
                                    R.string.backup_restore_scrubbed_fields,
                                    formatScrubbedFields(r.scrubbedFields),
                                )
                            )
                        }
                    }
                },
                onFailure = { e ->
                    resultBanner = context.getString(
                        R.string.backup_restore_export_failed,
                        e.localizedMessage ?: e.javaClass.simpleName,
                    )
                },
            )
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val uri = result.data?.data ?: return@rememberLauncherForActivityResult
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use {
                        BufferedReader(InputStreamReader(it)).readText()
                    } ?: error("openInputStream 返回 null")
                }
            }
            busy = false
            text.fold(
                onSuccess = { body ->
                    pendingImportText = body
                    // ⚠️ 选「覆盖式」**之前**就要知道哪些字段在导出时被清空了。
                    pendingScrubbedFields = BackupEnvelope.scrubbedFieldsOf(body)
                    importModeDialogVisible = true
                },
                onFailure = { e ->
                    resultBanner = context.getString(
                        R.string.backup_restore_import_read_failed,
                        e.localizedMessage ?: e.javaClass.simpleName,
                    )
                },
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_backup_restore)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 16.dp,
                top = padding.calculateTopPadding() + 12.dp,
                end = 16.dp,
                bottom = padding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                SectionHeader(stringResource(R.string.backup_restore_section_export))
            }
            item {
                ExportCard(
                    scopes = scopes,
                    selectedIds = selectedIds,
                    hasSensitiveScope = hasSensitiveScope,
                    includeSecrets = includeSecrets,
                    onIncludeSecretsChange = {
                        includeSecrets = it
                        exportError = null
                    },
                    passphrase = passphrase,
                    onPassphraseChange = { passphrase = it; exportError = null },
                    passphraseRepeat = passphraseRepeat,
                    onPassphraseRepeatChange = { passphraseRepeat = it; exportError = null },
                    error = exportError,
                    busy = busy,
                    onExport = {
                        exportError = validateExport(context, selectedIds, includeSecrets, passphrase, passphraseRepeat)
                        if (exportError == null) exportLauncher.launch(defaultFileName())
                    },
                )
            }

            item {
                SectionHeader(stringResource(R.string.backup_restore_section_import))
            }
            item {
                ImportCard(
                    busy = busy,
                    onImport = {
                        importLauncher.launch(
                            Intent(Intent.ACTION_GET_CONTENT).apply {
                                type = "application/json"
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                        )
                    },
                )
            }

            resultBanner?.let { banner ->
                item {
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(20.dp),
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        tonalElevation = 0.dp,
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = banner,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = { resultBanner = null }) {
                                Text(stringResource(android.R.string.ok))
                            }
                        }
                    }
                }
            }

            if (busy) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center,
                    ) {
                        CircularProgressIndicator(modifier = Modifier.width(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.backup_restore_processing),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }

    // ── 覆盖式 / 合并式选择（REPLACE 必须二次确认，见 BackupScope.kt:20-27） ──
    if (importModeDialogVisible) {
        ImportModeDialog(
            // ⚠️ 选「覆盖式」**之前**就要看到：这份备份在导出时把哪些字段清空了。
            //     REPLACE 不可撤销，用户若在导入之后才发现 api_key 是空的，
            //     他会去查执行日志 / 模块 / 权限，唯独查不到「导出时就没带密钥」。
            scrubbedFields = pendingScrubbedFields,
            onDismiss = {
                importModeDialogVisible = false
                pendingImportText = null
                pendingScrubbedFields = emptyList()
            },
            onPick = { mode ->
                importModeDialogVisible = false
                pendingMode = mode
                if (mode == ImportMode.REPLACE) {
                    // ⚠️ REPLACE 是**破坏性**的：它真的会删掉本地不在备份里的数据，
                    //     而本项目没有版本历史、没有撤销（工作流只存在
                    //     SharedPreferences）。`BackupScope.kt:20-27` 明令要求调用方二次确认。
                    replaceConfirmVisible = true
                } else {
                    startImportWithProbe()
                }
            },
        )
    }

    if (replaceConfirmVisible) {
        ReplaceConfirmDialog(
            onDismiss = {
                replaceConfirmVisible = false
                pendingImportText = null
                pendingScrubbedFields = emptyList()
            },
            onConfirm = {
                replaceConfirmVisible = false
                startImportWithProbe()
            },
        )
    }

    if (passphraseDialogVisible) {
        PassphraseDialog(
            value = importPassphrase,
            onValueChange = { importPassphrase = it },
            onDismiss = {
                // ⚠️ 用户看到「需要口令」后点取消 ⇒ **不导入**。
                //     静默退化成「只导非加密部分」会让他以为恢复完成了。
                passphraseDialogVisible = false
                pendingImportText = null
                pendingScrubbedFields = emptyList()
            },
            onConfirm = {
                val text = pendingImportText ?: return@PassphraseDialog
                passphraseDialogVisible = false
                runImport(text, pendingMode, importPassphrase)
            },
        )
    }

    importSummary?.let { summary ->
        ImportSummaryDialog(
            summary = summary,
            onDismiss = { importSummary = null },
            onRetryPassphrase = {
                // ⚠️ 重输走的是**同一条一次性路径**：pendingImportText 还在，
                //     pendingMode 也还在 ⇒ 不会把非加密 scope 导第二遍。
                importSummary = null
                importPassphrase = ""
                passphraseDialogVisible = true
            },
        )
    }
}

// ════════════════════ 校验 ════════════════════

/** 导出前的校验结果（**纯 JVM**，与文案解耦以便单测）。 */
internal enum class ExportValidationError {
    NO_SCOPE,
    PASSPHRASE_REQUIRED,
    PASSPHRASE_MISMATCH,
}

/**
 * 导出前的校验判据（纯函数）。
 *
 * ⚠️ 与文案分离是刻意的：判据要能被纯 JVM 单测覆盖，
 * 而 `Context.getString` 在 JVM 测试环境下抛 `not mocked`。
 * 文案映射留在 [validateExport] 里，那段是逐值 `when`（穷尽 ⇒ 编译期锁）。
 */
internal fun exportValidationError(
    selectedCount: Int,
    includeSecrets: Boolean,
    passphrase: String,
    passphraseRepeat: String,
): ExportValidationError? {
    if (selectedCount <= 0) return ExportValidationError.NO_SCOPE
    if (!includeSecrets) return null
    if (passphrase.isBlank()) return ExportValidationError.PASSPHRASE_REQUIRED
    if (passphrase != passphraseRepeat) return ExportValidationError.PASSPHRASE_MISMATCH
    return null
}

/** 判据 → 文案。 */
internal fun validateExport(
    context: Context,
    selectedIds: List<String>,
    includeSecrets: Boolean,
    passphrase: String,
    passphraseRepeat: String,
): String? = when (
    exportValidationError(selectedIds.size, includeSecrets, passphrase, passphraseRepeat)
) {
    null -> null
    ExportValidationError.NO_SCOPE -> context.getString(R.string.backup_scope_empty_hint)
    ExportValidationError.PASSPHRASE_REQUIRED ->
        context.getString(R.string.backup_restore_error_passphrase_required)
    ExportValidationError.PASSPHRASE_MISMATCH ->
        context.getString(R.string.backup_restore_error_passphrase_mismatch)
}

/**
 * 探测这份备份是否需要口令（**纯解析、无副作用**）。
 *
 * ⚠️ 用它而不是「无口令先 import 一次」的理由见调用点注释：
 *    后者会真的写入非加密部分，导致 REPLACE 跑两遍。
 */
internal fun needsPassphraseProbe(context: Context, text: String): Boolean {
    val env = AndroidBackupEnvironment(context.applicationContext)
    val read = BackupEnvelope.read(env.json, text)
    return read is BackupEnvelope.ReadResult.Modern && read.encryption != null
}

/** 被清洗字段的展示文本：最多列前 5 项，其余折成「等 N 项」（纯函数）。 */
internal fun formatScrubbedFields(fields: List<String>): String {
    val head = fields.take(SCRUBBED_PREVIEW_LIMIT)
    return if (fields.size <= SCRUBBED_PREVIEW_LIMIT) {
        head.joinToString(", ")
    } else {
        head.joinToString(", ") + " 等 ${fields.size} 项"
    }
}

private const val SCRUBBED_PREVIEW_LIMIT = 5

/**
 * 一条「该备份导出时未含密钥」的提示：`(资源 id, 已格式化的字段列表)`。
 *
 * ⚠️ **刻意不含 `Context`** —— 这样它才能被纯 JVM 单测断言
 * （`context.getString` 在单测环境抛 `not mocked`）。
 * 文案的渲染留给调用方（`context.getString(w.messageRes, w.fieldsText)`）。
 */
internal data class ScrubbedWarning(
    val messageRes: Int,
    val fieldsText: String,
)

/**
 * 由「已读出的被清空字段位置」构造提示。**纯函数**。
 *
 * ⚠️ [messageRes] 由调用方给：**导入前**（模式选择对话框）与**导入后**（结果 summary）
 * 说的是不同时点的事（「将被清空」vs「已被清空」），两条文案刻意不同 ——
 * 混用会让用户在还没导入时就以为已经发生了。
 *
 * ⚠️ 与 `formatScrubbedFields` 共用 `SCRUBBED_PREVIEW_LIMIT` 的截断规约
 * （列表太长会把弹窗撑爆）。**字段为空 ⇒ 返回 null**（调用方据此决定是否渲染），
 * 不要返回一个空串的提示 —— 那会在弹窗里多出一条无意义的空白行。
 */
internal fun scrubbedWarningOf(
    scrubbedFields: List<String>,
    messageRes: Int = R.string.backup_restore_import_scrubbed_warning,
): ScrubbedWarning? {
    if (scrubbedFields.isEmpty()) return null
    return ScrubbedWarning(
        messageRes = messageRes,
        fieldsText = formatScrubbedFields(scrubbedFields),
    )
}

/** 逐 scope 一行的导入结果文本（纯函数）。 */
internal fun renderResults(context: Context, results: List<ScopeImportResult>): String {
    return results.joinToString("\n") { r ->
        val label = context.getString(backupScopeLabelRes(r.scopeId))
        when (r.status) {
            ImportStatus.IMPORTED -> context.getString(
                R.string.backup_restore_summary_imported, label, r.imported, r.skipped,
            )
            ImportStatus.SKIPPED_MISSING -> context.getString(R.string.backup_restore_summary_missing, label)
            ImportStatus.SKIPPED_UNKNOWN -> context.getString(R.string.backup_restore_summary_unknown, label)
            ImportStatus.SKIPPED_NOT_SELECTED -> context.getString(R.string.backup_restore_summary_skipped, label)
            ImportStatus.FAILED -> context.getString(R.string.backup_restore_summary_failed, label)
        }
    }
}

// ════════════════════ Composable ════════════════════

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 4.dp),
    )
}

@Composable
private fun ExportCard(
    scopes: List<BackupScope>,
    selectedIds: MutableList<String>,
    hasSensitiveScope: Boolean,
    includeSecrets: Boolean,
    onIncludeSecretsChange: (Boolean) -> Unit,
    passphrase: String,
    onPassphraseChange: (String) -> Unit,
    passphraseRepeat: String,
    onPassphraseRepeatChange: (String) -> Unit,
    error: String?,
    busy: Boolean,
    onExport: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.backup_restore_scope_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            scopes.forEach { scope ->
                val checked = scope.id in selectedIds
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = checked,
                        onCheckedChange = { on ->
                            if (on) {
                                if (scope.id !in selectedIds) selectedIds.add(scope.id)
                            } else {
                                selectedIds.remove(scope.id)
                            }
                        },
                    )
                    Text(
                        text = stringResource(backupScopeLabelRes(scope.id)),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }

            if (hasSensitiveScope) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = includeSecrets, onCheckedChange = onIncludeSecretsChange)
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.backup_restore_include_secrets),
                            style = MaterialTheme.typography.bodyLarge,
                        )
                        Text(
                            text = stringResource(R.string.backup_restore_include_secrets_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            if (includeSecrets) {
                OutlinedTextField(
                    value = passphrase,
                    onValueChange = onPassphraseChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_restore_passphrase)) },
                    supportingText = { Text(stringResource(R.string.backup_restore_passphrase_hint)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
                OutlinedTextField(
                    value = passphraseRepeat,
                    onValueChange = onPassphraseRepeatChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_restore_passphrase_repeat)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }

            error?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            FilledTonalButton(
                onClick = onExport,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(stringResource(R.string.backup_restore_export_button))
            }
        }
    }
}

@Composable
private fun ImportCard(busy: Boolean, onImport: () -> Unit) {
    Card(
        shape = RoundedCornerShape(28.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            FilledTonalButton(
                onClick = onImport,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
            ) {
                Text(stringResource(R.string.backup_restore_import_button))
            }
        }
    }
}

@Composable
private fun ImportModeDialog(
    scrubbedFields: List<String>,
    onDismiss: () -> Unit,
    onPick: (ImportMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_mode_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // ⚠️⚠️ 这条提示**必须出现在模式选择之前**（尤其是选覆盖式之前）：
                //     这份备份在导出时不含密钥 ⇒ 导入后工作流里的 api_key / 密码
                //     会被清成空串，而 REPLACE 不可撤销。放在导入**之后**展示
                //     等于「事后才告诉用户他刚删掉了什么」。
                //     ⚠️ 这里用另一条文案（含「将被清空」的将来时），
                //     与导入完成后那条（「已被清空」）**刻意不同** ——
                //     两条说的是不同时点的事，混用会让用户以为已经发生了。
                scrubbedWarningOf(
                    scrubbedFields,
                    R.string.backup_restore_mode_scrubbed_warning,
                )?.let { warning ->
                    Text(
                        text = stringResource(warning.messageRes, warning.fieldsText),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                // ⚠️ 默认选中项是**合并式**（破坏性操作不该是默认）。
                Surface(
                    onClick = { onPick(ImportMode.MERGE) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.secondaryContainer,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.backup_restore_mode_merge),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.backup_restore_mode_merge_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Surface(
                    onClick = { onPick(ImportMode.REPLACE) },
                    shape = RoundedCornerShape(20.dp),
                    color = MaterialTheme.colorScheme.errorContainer,
                    tonalElevation = 0.dp,
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            text = stringResource(R.string.backup_restore_mode_replace),
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = stringResource(R.string.backup_restore_mode_replace_desc),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

/**
 * 覆盖式的**二次确认**（`BackupScope.kt:20-27` 明令要求）。
 *
 * ⚠️ 按钮文案是「覆盖导入（删除现有数据）」而不是「确定」——
 * 用户在点下去的那一刻必须清楚自己在删东西，且本应用**没有撤销**。
 */
@Composable
private fun ReplaceConfirmDialog(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_mode_replace)) },
        text = { Text(stringResource(R.string.backup_restore_mode_replace_desc)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.backup_restore_mode_confirm_replace))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun PassphraseDialog(
    value: String,
    onValueChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.backup_restore_passphrase_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.backup_restore_passphrase_dialog_body))
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.backup_restore_passphrase)) },
                    visualTransformation = PasswordVisualTransformation(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = value.isNotEmpty()) {
                Text(stringResource(android.R.string.ok))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) }
        },
    )
}

@Composable
private fun ImportSummaryDialog(
    summary: ImportSummary,
    onDismiss: () -> Unit,
    onRetryPassphrase: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(summary.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (summary.body.isNotEmpty()) Text(summary.body)
                summary.warningLines.forEach { line ->
                    Text(
                        text = line,
                        style = MaterialTheme.typography.bodySmall,
                        color = if (summary.isError) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }
        },
        confirmButton = {
            if (summary.retryPassphrase) {
                TextButton(onClick = onRetryPassphrase) {
                    Text(stringResource(R.string.backup_restore_passphrase))
                }
            }
            TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) }
        },
    )
}
