package com.chaomixian.vflow.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.security.CryptoKeyUnavailableException
import com.chaomixian.vflow.core.webdav.WebDavConfig
import com.chaomixian.vflow.core.webdav.WebDavConfigStore
import com.chaomixian.vflow.core.webdav.WebDavProbeOutcome
import com.chaomixian.vflow.core.webdav.WebDavTestResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/** 编辑弹窗的初始状态。`originalId == null` 表示新增。 */
private data class WebDavEditorState(
    val originalId: String? = null,
    val name: String = "",
    val baseUrl: String = "",
    val username: String = "",
    val password: String = "",
    val allowInsecureTls: Boolean = false,
    val timeoutSeconds: String = WebDavConfig.DEFAULT_TIMEOUT_SECONDS.toString(),
    val remoteBasePath: String = ""
)

/**
 * 测试连接的 UI 三态。
 *
 * ⚠️ 必须有 [Running] —— 否则用户点完按钮到结果返回之间没有任何反馈，
 * 会以为按钮坏了而反复点（每次点都真的发一次网络请求）。
 */
private sealed interface TestUiState {
    data object Idle : TestUiState
    data object Running : TestUiState
    data class Done(val result: WebDavTestResult) : TestUiState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun WebDavConfigScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val items = remember { mutableStateListOf<WebDavConfig>().apply { addAll(WebDavConfigStore.getAll(context)) } }
    var editorState by remember { mutableStateOf<WebDavEditorState?>(null) }
    var deleteTarget by remember { mutableStateOf<WebDavConfig?>(null) }
    // key = config.id
    val testResults = remember { mutableStateMapOf<String, TestUiState>() }

    fun reload() {
        items.clear()
        items.addAll(WebDavConfigStore.getAll(context))
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.webdav_config_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.webdav_config_desc),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            FilledTonalButton(
                onClick = { editorState = WebDavEditorState() },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(stringResource(R.string.webdav_config_add))
            }

            if (items.isEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.webdav_config_empty),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                items.forEach { config ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(config.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                text = config.baseUrl,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (config.username.isNotEmpty()) {
                                Text(
                                    text = config.username,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { editorState = config.toEditorState() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.webdav_config_edit))
                                }
                                OutlinedButton(
                                    onClick = {
                                        testResults[config.id] = TestUiState.Running
                                        scope.launch {
                                            // ⚠️ testConnection 是**阻塞**的（OkHttp execute()），
                                            // 直接在 onClick 里调会 ANR —— 必须切到 IO。
                                            val result = withContext(Dispatchers.IO) {
                                                runCatching { WebDavConfigStore.testConnection(context, config.id) }
                                                    .getOrElse { WebDavTestResult.InvalidConfig(it.message ?: "未知错误") }
                                            }
                                            testResults[config.id] = TestUiState.Done(result)
                                        }
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(stringResource(R.string.webdav_config_test))
                                }
                            }

                            TextButton(
                                onClick = { deleteTarget = config },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(stringResource(R.string.webdav_config_delete))
                            }

                            TestResultLine(testResults[config.id] ?: TestUiState.Idle)
                        }
                    }
                }
            }
        }
    }

    editorState?.let { state ->
        WebDavEditorDialog(
            initialState = state,
            onDismiss = { editorState = null },
            onSave = { updated ->
                val name = updated.name.trim()
                if (name.isBlank()) {
                    toast(context, R.string.webdav_config_invalid_name)
                    return@WebDavEditorDialog
                }

                val url = updated.baseUrl.trim()
                if (!url.startsWith("http://") && !url.startsWith("https://")) {
                    toast(context, R.string.webdav_config_invalid_url)
                    return@WebDavEditorDialog
                }

                if (items.any { it.name == name && it.id != updated.originalId }) {
                    toast(context, R.string.webdav_config_duplicate_name)
                    return@WebDavEditorDialog
                }

                val timeout = updated.timeoutSeconds.trim().toIntOrNull() ?: WebDavConfig.DEFAULT_TIMEOUT_SECONDS
                val target = WebDavConfig(
                    id = updated.originalId ?: UUID.randomUUID().toString(),
                    name = name,
                    baseUrl = url,
                    username = updated.username.trim(),
                    encryptedPassword = "", // 由 Store 按 password 参数决定（占位，不落盘）
                    allowInsecureTls = updated.allowInsecureTls,
                    timeoutSeconds = timeout,
                    remoteBasePath = updated.remoteBasePath.trim()
                )

                // ⚠️ 密码框留空 ⇒ 传 null ⇒ Store 保持原密文不动（编辑时没改密码的常见路径）。
                val passwordOrNull = if (updated.password.isEmpty()) null else updated.password

                try {
                    WebDavConfigStore.upsert(context, target, passwordOrNull)
                } catch (e: CryptoKeyUnavailableException) {
                    // 加密失败（密钥不可用）—— 与「保存成功但连不上」是两件事，必须说清。
                    toast(context, R.string.webdav_test_key_unavailable)
                    return@WebDavEditorDialog
                }

                reload()
                // 配置变了，旧测试结果不再代表当前配置。
                testResults.clear()
                editorState = null
            }
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.webdav_config_delete_confirm_title)) },
            text = { Text(stringResource(R.string.webdav_config_delete_confirm_message, target.name)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        WebDavConfigStore.delete(context, target.id)
                        testResults.remove(target.id)
                        reload()
                        deleteTarget = null
                    }
                ) { Text(stringResource(R.string.webdav_config_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.common_cancel)) }
            }
        )
    }
}

@Composable
private fun TestResultLine(state: TestUiState) {
    // ⚠️ 没有 Running 分支的话，点完按钮到结果返回之间界面毫无变化。
    val (text, isError, detail) = when (state) {
        TestUiState.Idle -> return
        TestUiState.Running -> Triple(stringResource(R.string.webdav_test_running), false, null)
        is TestUiState.Done -> testResultText(state.result)
    }

    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        )
        // ⚠️ 诊断副行：把 `detail` 显示出来。
        // 必要性来自一个具体的归并 —— 「重定向超限」按方案归入了 NETWORK_ERROR（不单列枚举），
        // 若不显示 detail，用户只看到「网络错误，请检查地址与网络」，
        // 而真实原因是「重定向环」或「跳数超限」—— 那是可诊断的信息，不该被吞掉。
        detail?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun testResultText(result: WebDavTestResult): Triple<String, Boolean, String?> = when (result) {
    is WebDavTestResult.Success ->
        Triple(stringResource(R.string.webdav_test_success), false, null)
    is WebDavTestResult.KeyUnavailable ->
        // ⚠️⚠️ 本任务的核心质量点：必须走**自己的**文案（「重新输入密码」），
        // 绝不能落到「网络错误」里 —— 那会让用户对着正确的地址反复重试。
        Triple(stringResource(R.string.webdav_test_key_unavailable), true, result.reason)
    is WebDavTestResult.ConfigNotFound, is WebDavTestResult.InvalidConfig ->
        Triple(stringResource(R.string.webdav_test_invalid_config), true, (result as? WebDavTestResult.InvalidConfig)?.reason)
    is WebDavTestResult.ServerRejected -> {
        val text = when (result.outcome) {
            WebDavProbeOutcome.SUCCESS -> stringResource(R.string.webdav_test_success)
            WebDavProbeOutcome.AUTH_FAILED -> stringResource(R.string.webdav_test_auth_failed)
            WebDavProbeOutcome.NOT_FOUND -> stringResource(R.string.webdav_test_not_found)
            WebDavProbeOutcome.NOT_SUPPORTED -> stringResource(R.string.webdav_test_not_supported)
            WebDavProbeOutcome.NETWORK_ERROR -> stringResource(R.string.webdav_test_network_error)
        }
        // detail 里是状态码 / 异常类名 / 「重定向超过 N 跳」等实现级信息，供排障。
        Triple(text, result.outcome != WebDavProbeOutcome.SUCCESS, result.detail)
    }
}

@Composable
private fun WebDavEditorDialog(
    initialState: WebDavEditorState,
    onDismiss: () -> Unit,
    onSave: (WebDavEditorState) -> Unit
) {
    var name by remember(initialState) { mutableStateOf(initialState.name) }
    var baseUrl by remember(initialState) { mutableStateOf(initialState.baseUrl) }
    var username by remember(initialState) { mutableStateOf(initialState.username) }
    var password by remember(initialState) { mutableStateOf(initialState.password) }
    var allowInsecureTls by remember(initialState) { mutableStateOf(initialState.allowInsecureTls) }
    var timeoutSeconds by remember(initialState) { mutableStateOf(initialState.timeoutSeconds) }
    var remoteBasePath by remember(initialState) { mutableStateOf(initialState.remoteBasePath) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (initialState.originalId == null) R.string.webdav_config_add
                    else R.string.webdav_config_edit
                )
            )
        },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_config_name_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = baseUrl,
                    onValueChange = { baseUrl = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_config_url_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_config_username_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_config_password_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = remoteBasePath,
                    onValueChange = { remoteBasePath = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_config_remote_path_label)) },
                    singleLine = true
                )
                OutlinedTextField(
                    value = timeoutSeconds,
                    onValueChange = { timeoutSeconds = it.filter { c -> c.isDigit() } },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(stringResource(R.string.webdav_config_timeout_label)) },
                    singleLine = true
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(stringResource(R.string.webdav_config_allow_insecure_tls))
                    Switch(checked = allowInsecureTls, onCheckedChange = { allowInsecureTls = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onSave(
                        initialState.copy(
                            name = name,
                            baseUrl = baseUrl,
                            username = username,
                            password = password,
                            allowInsecureTls = allowInsecureTls,
                            timeoutSeconds = timeoutSeconds,
                            remoteBasePath = remoteBasePath
                        )
                    )
                }
            ) { Text(stringResource(R.string.common_save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
        }
    )
}

private fun WebDavConfig.toEditorState() = WebDavEditorState(
    originalId = id,
    name = name,
    baseUrl = baseUrl,
    username = username,
    // ⚠️ 密码框**始终留空** —— 清单页拿到的只有密文，无法回填明文；
    // 留空时保存会走 `password = null` 分支，保持原密文不动。
    password = "",
    allowInsecureTls = allowInsecureTls,
    timeoutSeconds = timeoutSeconds.toString(),
    remoteBasePath = remoteBasePath
)

private fun toast(context: Context, resId: Int) {
    Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT).show()
}
