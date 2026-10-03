// 文件: app/src/main/java/com/chaomixian/vflow/core/workflow/module/network/WebDavModule.kt
package com.chaomixian.vflow.core.workflow.module.network

import android.content.Context
import android.content.Intent
import android.webkit.MimeTypeMap
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.AiModuleMetadata
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.EditorAction
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.InputVisibility
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.PickerType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.module.ValidationResult
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VFile
import com.chaomixian.vflow.core.utils.StorageManager
import com.chaomixian.vflow.core.webdav.WebDavClient
import com.chaomixian.vflow.core.webdav.WebDavConfig
import com.chaomixian.vflow.core.webdav.WebDavConfigStore
import com.chaomixian.vflow.core.webdav.WebDavParseResult
import com.chaomixian.vflow.core.webdav.WebDavResource
import com.chaomixian.vflow.core.webdav.WebDavResult
import com.chaomixian.vflow.core.webdav.WebDavUrlBuilder
import com.chaomixian.vflow.core.webdav.normalizeBaseUrl
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.security.CryptoKeyUnavailableException
import com.chaomixian.vflow.ui.settings.WebDavConfigActivity
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.asRequestBody
import java.io.File
import java.io.IOException

/**
 * WebDAV 模块：`list` / `upload` / `download` / `mkdir` / `delete` 五种操作共用一个模块，
 * 由 [OPERATION_ID] 枚举区分。
 *
 * ## ⚠️ 配置选择存「名字」不存 id —— 失配必须**显式失败**（三处都要有）
 *
 * ENUM 控件的渲染路径只吃 `InputDefinition.options`，不吃任意自定义值 ⇒ 动态配置选择
 * 只能把「要显示的字符串」（= 配置名）当 option 值。代价是**用户改名或删配置后，
 * 已保存的步骤里的名字就失配了**。此时三个地方都必须让用户**看得出来**：
 *
 * | 位置 | 做法 |
 * |---|---|
 * | [validate] | 返回「无效」+ 明确文案（⚠️ `BaseModule` 的默认实现是**恒 true**，不覆写等于不校验） |
 * | [execute] | `Failure`，且**文案里带上那个名字** |
 * | [getSummary] | 摘要显示成「（配置已删除：X）」而不是照常 render 出配置名 |
 *
 * ⚠️ **刻意不把已保存的名字塞回 options**（`options = 现有配置 + listOf(已保存的)）——
 * 那样用户删掉配置后下拉里仍有个「幽灵条目」，看着像配置还在、实际执行必失败。
 *
 * ## ⚠️ 不要在这里做「路径归一化」
 *
 * 路径的百分号编码与 `..` 拦截全部由 [WebDavUrlBuilder] 负责 —— 本模块只传原始字符串。
 * 在模块侧再拼一次 URL 就会绕开那两道防线（尤其 `#`/`?` 会变成 fragment / query）。
 */
class WebDavModule : BaseModule() {

    companion object {
        internal const val CONFIG_ID = "config"
        internal const val OPERATION_ID = "operation"
        internal const val REMOTE_PATH_ID = "remote_path"
        internal const val LOCAL_FILE_ID = "local_file"
        internal const val SAVE_DIR_ID = "save_dir"
        internal const val OVERWRITE_ID = "overwrite"

        internal const val OP_LIST = "list"
        internal const val OP_UPLOAD = "upload"
        internal const val OP_DOWNLOAD = "download"
        internal const val OP_MKDIR = "mkdir"
        internal const val OP_DELETE = "delete"

        internal val OPERATIONS = listOf(OP_LIST, OP_UPLOAD, OP_DOWNLOAD, OP_MKDIR, OP_DELETE)

        /**
         * 旧值兜底。
         *
         * ⚠️ `legacyValueMap` **只在编辑器路径生效**（渲染 + `normalizeEnumValue`），
         * 模块自己读参数时**必须**显式调 `normalizeEnumValue` —— 本模块在
         * [operationOf] 里做了，别处不要再裸读 `parameters[operation]`。
         */
        private val LEGACY_OPERATION_ALIASES = mapOf(
            "列出" to OP_LIST, "List" to OP_LIST, "LIST" to OP_LIST,
            "上传" to OP_UPLOAD, "Upload" to OP_UPLOAD, "UPLOAD" to OP_UPLOAD,
            "下载" to OP_DOWNLOAD, "Download" to OP_DOWNLOAD, "DOWNLOAD" to OP_DOWNLOAD,
            "创建目录" to OP_MKDIR, "Mkdir" to OP_MKDIR, "MKDIR" to OP_MKDIR, "mkdir " to OP_MKDIR,
            "删除" to OP_DELETE, "Delete" to OP_DELETE, "DELETE" to OP_DELETE,
        )

        private const val UNKNOWN_FILE_NAME = "download"
    }

    override val id = "vflow.network.webdav"
    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_network_webdav_name,
        descriptionStringRes = R.string.module_vflow_network_webdav_desc,
        name = "WebDAV",
        description = "通过 WebDAV 服务器上传、下载、列出与删除文件",
        // ⚠️ 复用既有图标（FeishuMediaUploadModule 也在用），不新增 drawable —— 控制 diff 面积。
        iconRes = R.drawable.rounded_cloud_24,
        category = "网络",
        categoryId = "network",
    )

    /**
     * AI 元数据。
     *
     * ⚠️ **只给 `TEMPORARY_WORKFLOW`，不给 `DIRECT_TOOL`** —— 本模块能删远端文件、
     * 能把服务器上的东西覆盖掉，不该让 AI 在没有人工过目的情况下直接调用。
     *
     * ⚠️ 风险等级**统一 `HIGH`**：`ActionModule.aiMetadata` 是模块上的静态 `val`
     * （签名里**没有** `step`），无法按 operation 分档 ⇒ 按保守侧取 HIGH
     * （upload/download/delete/mkdir 都有写副作用，把「列出」一起抬上来是可接受的）。
     */
    override val aiMetadata = AiModuleMetadata(
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        riskLevel = AiModuleRiskLevel.HIGH,
        workflowStepDescription = "Perform a WebDAV operation (list, upload, download, mkdir, delete) " +
            "against a server configured in Settings → WebDAV.",
        inputHints = mapOf(
            CONFIG_ID to "Name of a WebDAV configuration saved in Settings → WebDAV. Must match exactly.",
            OPERATION_ID to "One of list, upload, download, mkdir, delete.",
            REMOTE_PATH_ID to "Remote path relative to the server base. For list/mkdir it is the directory; otherwise the file.",
            LOCAL_FILE_ID to "Local file path to upload (required for upload).",
            SAVE_DIR_ID to "Local directory to save the downloaded file into (required for download).",
            OVERWRITE_ID to "Whether to overwrite an existing remote file / local file.",
        ),
        requiredInputIds = setOf(CONFIG_ID, OPERATION_ID, REMOTE_PATH_ID),
    )

    // ── 参数 ────────────────────────────────────────────────────

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = CONFIG_ID,
            nameStringRes = R.string.param_vflow_network_webdav_config_name,
            name = "配置",
            staticType = ParameterType.ENUM,
            defaultValue = "",
            // options 由 getDynamicInputs 动态填充（见下）—— 这里留空是**有意的**：
            // 静态 options 一旦写上，就等于把配置清单钉死在类初始化时。
            options = emptyList(),
            acceptsMagicVariable = false,
            hint = "在设置 → WebDAV 中新增配置",
            hintStringRes = R.string.hint_vflow_network_webdav_config,
        ),
        InputDefinition(
            id = OPERATION_ID,
            nameStringRes = R.string.param_vflow_network_webdav_operation_name,
            name = "操作",
            staticType = ParameterType.ENUM,
            defaultValue = OP_LIST,
            options = OPERATIONS,
            optionsStringRes = listOf(
                R.string.option_vflow_network_webdav_operation_list,
                R.string.option_vflow_network_webdav_operation_upload,
                R.string.option_vflow_network_webdav_operation_download,
                R.string.option_vflow_network_webdav_operation_mkdir,
                R.string.option_vflow_network_webdav_operation_delete,
            ),
            legacyValueMap = LEGACY_OPERATION_ALIASES,
            inputStyle = InputStyle.CHIP_GROUP,
        ),
        InputDefinition(
            id = REMOTE_PATH_ID,
            nameStringRes = R.string.param_vflow_network_webdav_remote_path_name,
            name = "远端路径",
            staticType = ParameterType.STRING,
            defaultValue = "",
            supportsRichText = true,
            acceptsMagicVariable = true,
            hint = "相对于服务器地址的路径，例如 backups/2026.zip",
            hintStringRes = R.string.hint_vflow_network_webdav_remote_path,
        ),
        InputDefinition(
            id = LOCAL_FILE_ID,
            nameStringRes = R.string.param_vflow_network_webdav_local_file_name,
            name = "本地文件",
            staticType = ParameterType.STRING,
            defaultValue = "",
            pickerType = PickerType.FILE,
            supportsRichText = true,
            acceptsMagicVariable = true,
            visibility = InputVisibility.whenEquals(OPERATION_ID, OP_UPLOAD),
            hint = "要上传的本地文件",
            hintStringRes = R.string.hint_vflow_network_webdav_local_file,
        ),
        InputDefinition(
            id = SAVE_DIR_ID,
            nameStringRes = R.string.param_vflow_network_webdav_save_dir_name,
            name = "保存目录",
            staticType = ParameterType.STRING,
            defaultValue = "",
            pickerType = PickerType.DIRECTORY,
            supportsRichText = true,
            acceptsMagicVariable = true,
            visibility = InputVisibility.whenEquals(OPERATION_ID, OP_DOWNLOAD),
            hint = "下载文件的存放目录，留空则用 /sdcard/vFlow/exports",
            hintStringRes = R.string.hint_vflow_network_webdav_save_dir,
        ),
        InputDefinition(
            id = OVERWRITE_ID,
            nameStringRes = R.string.param_vflow_network_webdav_overwrite_name,
            name = "覆盖已存在的文件",
            staticType = ParameterType.BOOLEAN,
            defaultValue = true,
            inputStyle = InputStyle.SWITCH,
            isFolded = true,
            visibility = InputVisibility.whenIn(OPERATION_ID, listOf(OP_UPLOAD, OP_DOWNLOAD)),
        ),
    ) + moduleProxyInputDefinitions()

    /**
     * 动态输入：把「配置」这个 ENUM 的 options 换成**当前保存的配置名**。
     *
     * ⚠️⚠️ **本函数绝对不能抛异常** —— 它的调用方是**步骤参数面板**
     * （`ActionEditorUiModelBuilder.build` 每次重算参数都会走），抛出去就是**整屏不可用**。
     * 而最现实的抛法就是 `appContext` 未注入（`BaseModule` 的 `lateinit var`，
     * 未 `initContext` 时读它是 `UninitializedPropertyAccessException`）
     * 或 prefs 读取异常（设备存储异常、用户手工改坏数据）。
     * ⇒ [safeConfigNames] 一律 `catch (Throwable)` 回落空列表。
     */
    override fun getDynamicInputs(step: ActionStep?, allSteps: List<ActionStep>?): List<InputDefinition> {
        val options = safeConfigNames()
        if (options.isEmpty()) return getInputs()

        return getInputs().map { definition ->
            if (definition.id == CONFIG_ID) {
                // ⚠️ optionsStringRes 必须一起清空 —— 它非空时 `getLocalizedOptions`
                // **优先走资源**，动态 options 会被完全忽略（表现为下拉里仍是空/旧值）。
                definition.copy(options = options, optionsStringRes = emptyList())
            } else {
                definition
            }
        }
    }

    /**
     * 读配置名清单。**任何异常都回落空列表**（见 [getDynamicInputs] 的说明）。
     *
     * ⚠️ `catch (Throwable)` 而不是 `catch (Exception)`：`UninitializedPropertyAccessException`
     * 是 `Error` 系的兄弟吗？—— 不，它是 `RuntimeException`；但 `lateinit` 在**并发初始化**
     * 下还可能抛别的。宽 catch 在这里的代价是「下拉为空」，窄 catch 的代价是「整屏崩」，不对等。
     */
    private fun safeConfigNames(): List<String> {
        return try {
            WebDavConfigStore.getAll(appContext)
                .map { it.name }
                .filter { it.isNotBlank() }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** 配置是否存在。同样宽 catch（[getSummary] / [validate] 都可能在 appContext 未就绪时被调）。 */
    private fun configExists(name: String): Boolean = try {
        name.isNotBlank() && WebDavConfigStore.getAll(appContext).any { it.name == name }
    } catch (_: Throwable) {
        false
    }

    // ── 输出（按 operation 分支）────────────────────────────────

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = when (operationOf(step)) {
        OP_LIST -> listOf(
            OutputDefinition(
                "files", "文件列表", VTypeRegistry.LIST.id,
                listElementType = VTypeRegistry.DICTIONARY.id,
                nameStringRes = R.string.output_vflow_network_webdav_files_name,
            ),
            OutputDefinition("count", "条目数", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_network_webdav_count_name),
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_network_webdav_success_name),
            OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_error_name),
        )

        OP_UPLOAD -> listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_network_webdav_success_name),
            OutputDefinition("remote_path", "远端路径", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_remote_path_name),
            OutputDefinition("status_code", "状态码", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_network_webdav_status_code_name),
            OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_error_name),
        )

        OP_DOWNLOAD -> listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_network_webdav_success_name),
            OutputDefinition("file", "文件", VTypeRegistry.FILE.id, nameStringRes = R.string.output_vflow_network_webdav_file_name),
            OutputDefinition("file_path", "本地路径", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_file_path_name),
            OutputDefinition("size", "文件大小", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_network_webdav_size_name),
            OutputDefinition("status_code", "状态码", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_network_webdav_status_code_name),
            OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_error_name),
        )

        OP_MKDIR -> listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_network_webdav_success_name),
            OutputDefinition("remote_path", "远端路径", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_remote_path_name),
            OutputDefinition("status_code", "状态码", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_network_webdav_status_code_name),
            OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_error_name),
        )

        else -> listOf(
            OutputDefinition("success", "是否成功", VTypeRegistry.BOOLEAN.id, nameStringRes = R.string.output_vflow_network_webdav_success_name),
            OutputDefinition("status_code", "状态码", VTypeRegistry.NUMBER.id, nameStringRes = R.string.output_vflow_network_webdav_status_code_name),
            OutputDefinition("error", "错误信息", VTypeRegistry.STRING.id, nameStringRes = R.string.output_vflow_network_webdav_error_name),
        )
    }

    private fun operationOf(step: ActionStep?): String {
        val input = getInputs().first { it.id == OPERATION_ID }
        val raw = step?.parameters?.get(OPERATION_ID) as? String ?: OP_LIST
        return input.normalizeEnumValue(raw, OP_LIST) ?: OP_LIST
    }

    // ── 摘要（失配必须看得出来）──────────────────────────────────

    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val operation = operationOf(step)
        val rawConfigName = (step.parameters[CONFIG_ID] as? String).orEmpty()
        val configInput = getInputs().first { it.id == CONFIG_ID }

        val configPill = when {
            rawConfigName.isBlank() -> PillUtil.createPillFromParam(
                context.getString(R.string.summary_vflow_network_webdav_no_config),
                configInput,
            )
            // ⚠️⚠️ 失配时**不得**照常 render 出那个配置名 —— 那看着像一切正常，
            // 用户只有跑到执行期才会知道配置早就被删了。
            !configExists(rawConfigName) -> PillUtil.createPillFromParam(
                context.getString(R.string.summary_vflow_network_webdav_config_missing, rawConfigName),
                configInput,
            )
            else -> PillUtil.createPillFromParam(rawConfigName, configInput)
        }

        val rawPath = (step.parameters[REMOTE_PATH_ID] as? String).orEmpty()
        val pathPill = PillUtil.createPillFromParam(rawPath, getInputs().first { it.id == REMOTE_PATH_ID })

        val prefix = context.getString(
            when (operation) {
                OP_UPLOAD -> R.string.summary_vflow_network_webdav_prefix_upload
                OP_DOWNLOAD -> R.string.summary_vflow_network_webdav_prefix_download
                OP_MKDIR -> R.string.summary_vflow_network_webdav_prefix_mkdir
                OP_DELETE -> R.string.summary_vflow_network_webdav_prefix_delete
                else -> R.string.summary_vflow_network_webdav_prefix_list
            }
        )

        return PillUtil.buildSpannable(context, prefix, configPill, " ", pathPill)
    }

    // ── 编辑器动作 ──────────────────────────────────────────────

    override fun getEditorActions(step: ActionStep?, allSteps: List<ActionStep>?): List<EditorAction> =
        listOf(
            EditorAction(labelStringRes = R.string.module_editor_action_manage_webdav) { ctx ->
                // ⚠️ NEW_TASK 必需：编辑器可能在非 Activity 上下文里调这个 action。
                ctx.startActivity(
                    Intent(ctx, WebDavConfigActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        )

    // ── 校验 ────────────────────────────────────────────────────

    /**
     * ⚠️⚠️ **必须覆写**：`BaseModule.validate` 的默认实现是 `ValidationResult(isValid = true)`
     * —— 不覆写就等于**完全不校验**。
     *
     * 这里做的是**唯一一处能在执行前拦住「配置已被删除」的地方**（另两处是执行期与摘要）。
     */
    override fun validate(step: ActionStep, allSteps: List<ActionStep>): ValidationResult {
        val rawConfigName = (step.parameters[CONFIG_ID] as? String).orEmpty().trim()
        if (rawConfigName.isEmpty()) {
            return ValidationResult(false, appContext.getString(R.string.error_vflow_network_webdav_validate_no_config))
        }
        if (!configExists(rawConfigName)) {
            return ValidationResult(
                false,
                appContext.getString(R.string.error_vflow_network_webdav_config_not_found, rawConfigName),
            )
        }

        val remotePath = (step.parameters[REMOTE_PATH_ID] as? String).orEmpty()
        if (remotePath.isBlank()) {
            return ValidationResult(false, appContext.getString(R.string.error_vflow_network_webdav_validate_no_remote_path))
        }

        val operation = operationOf(step)
        if (operation == OP_UPLOAD && (step.parameters[LOCAL_FILE_ID] as? String).orEmpty().isBlank()) {
            return ValidationResult(false, appContext.getString(R.string.error_vflow_network_webdav_validate_no_local_file))
        }

        return ValidationResult(isValid = true)
    }

    // ── 执行 ────────────────────────────────────────────────────

    /**
     * 执行入口。
     *
     * ⚠️ 整体骨架刻意是「**先解析出配置与密码，再按 operation 分派**」——
     * 密钥失效（[CryptoKeyUnavailableException]）必须在**创建 client 之前**单独分支处理，
     * 否则会落进泛 `Exception` 变成「操作失败」，而用户的正确处置是**重新输入密码**。
     *
     * ⚠️ 全程 `withContext(Dispatchers.IO)` —— 所有 `execute` 都是阻塞 IO。
     */
    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult = withContext(Dispatchers.IO) {
        try {
            val operation = operationOf(currentStepOf(context))
            val configName = context.getVariableAsString(CONFIG_ID, "").trim()

            if (configName.isBlank()) {
                return@withContext ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                    appContext.getString(R.string.error_vflow_network_webdav_no_config_desc),
                    partialOutputsOf(operation, success = false, error = "未配置 WebDAV"),
                )
            }

            val config = WebDavConfigStore.getAll(appContext).firstOrNull { it.name == configName }
                ?: return@withContext ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                    appContext.getString(R.string.error_vflow_network_webdav_config_not_found, configName),
                    partialOutputsOf(operation, success = false, error = "找不到配置：$configName"),
                )

            val password = try {
                WebDavConfigStore.decryptPassword(config)
            } catch (e: CryptoKeyUnavailableException) {
                // ⚠️⚠️ 必须单独分支：密钥失效 ≠ 网络失败。处置是「重新输入密码」，
                // 若落进泛 Exception，用户会一直去查服务器地址。
                return@withContext ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_key_unavailable_title),
                    appContext.getString(R.string.error_vflow_network_webdav_key_unavailable_desc),
                    partialOutputsOf(operation, success = false, error = e.message ?: "设备密钥不可用"),
                )
            }

            val client = buildClient(config, password, context)

            when (operation) {
                OP_LIST -> doList(client, config, context, onProgress)
                OP_UPLOAD -> doUpload(client, config, context, onProgress)
                OP_DOWNLOAD -> doDownload(client, config, context, onProgress)
                OP_MKDIR -> doMkdir(client, config, context, onProgress)
                OP_DELETE -> doDelete(client, config, context, onProgress)
                else -> ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                    appContext.getString(R.string.error_vflow_network_webdav_unknown_operation, operation),
                    partialOutputsOf(OP_LIST, success = false, error = "未知操作：$operation"),
                )
            }
        } catch (e: IOException) {
            ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_network_error),
                e.message ?: e.javaClass.simpleName,
            )
        } catch (e: Exception) {
            ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                e.localizedMessage ?: e.javaClass.simpleName,
            )
        }
    }

    /**
     * 当前步骤。
     *
     * ⚠️ 走 `context.allSteps[currentStepIndex]` 读**原始参数**，而不是 `context.getVariable`：
     * 后者拿到的是**已被变量解析器处理过**的值，而 [operationOf] 需要的是原始的枚举字符串
     * （解析后的值可能已被替换成用户数据）。
     */
    private fun currentStepOf(context: ExecutionContext): ActionStep? =
        context.allSteps.getOrNull(context.currentStepIndex)

    private fun buildClient(config: WebDavConfig, password: String, context: ExecutionContext): WebDavClient {
        val proxyAddress = resolveModuleProxyAddress(
            context.getVariableAsString("proxy_mode", ""),
            context.getVariableAsString("proxy", ""),
            context,
        )
        // ⚠️ 模块没显式配代理时回落到全局设置（与 HttpRequestModule 同款口径）。
        val proxy = if (proxyAddress.isNotEmpty()) parseProxy(proxyAddress) else readConfiguredProxy(appContext)

        return WebDavClient(
            baseUrl = normalizeBaseUrl(config.baseUrl),
            username = config.username,
            password = password,
            allowInsecureTls = config.allowInsecureTls,
            timeoutSeconds = config.timeoutSeconds,
            proxy = proxy,
        )
    }

    // ── list ────────────────────────────────────────────────────

    private suspend fun doList(
        client: WebDavClient,
        config: WebDavConfig,
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val remotePath = resolveRemotePath(context)
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_network_webdav_listing, remotePath)))

        return when (val result = client.propfind(config.remoteBasePath, remotePath, directory = true, depth = 1)) {
            is WebDavResult.Success -> {
                when (val parsed = com.chaomixian.vflow.core.webdav.WebDavXmlParser.parseMultiStatus(result.text())) {
                    is WebDavParseResult.Malformed ->
                        // ⚠️⚠️ **绝不能把解析失败当成空列表** —— 那表现为「目录永远是空的」
                        // 而没有任何错误提示，用户会去查服务器配置（其实服务器没问题）。
                        ExecutionResult.Failure(
                            appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                            appContext.getString(
                                R.string.error_vflow_network_webdav_malformed,
                                parsed.detail ?: "",
                            ),
                            partialOutputsOf(OP_LIST, success = false, error = "响应无法解析"),
                        )

                    is WebDavParseResult.Success -> {
                        val entries = buildListEntries(parsed.resources, config, remotePath)
                        ExecutionResult.Success(
                            mapOf(
                                "files" to VList(entries),
                                "count" to VNumber(entries.size.toDouble()),
                                "success" to VBoolean(true),
                                "error" to VString(""),
                            )
                        )
                    }
                }
            }

            is WebDavResult.HttpError -> listFailure(result.code, httpErrorDetail(result))
            is WebDavResult.Failure -> listFailure(null, describeFailure(result))
        }
    }

    /**
     * 把解析结果映射成模块输出，并**滤掉 self 条目**。
     *
     * ⚠️ self 过滤按**归一化后的路径**比，而不是按 href 原始字符串 ——
     * 服务器可能回 `/dav/` 也可能回 `https://host/dav/`，甚至带百分号编码，
     * 直接字符串比会漏掉一部分 self 条目（表现为「列表第一项是目录自己」）。
     */
    private fun buildListEntries(
        resources: List<WebDavResource>,
        config: WebDavConfig,
        remotePath: String,
    ): List<VDictionary> {
        val requestPath = WebDavUrlBuilder
            .resolve(config.baseUrl, config.remoteBasePath, remotePath, directory = true)
            ?.encodedPath
            .orEmpty()
            .let { if (it.endsWith("/")) it else "$it/" }

        val baseUrl = normalizeBaseUrl(config.baseUrl)

        return resources.mapNotNull { resource ->
            val hrefUrl = resolveHref(baseUrl, resource.href) ?: return@mapNotNull null
            val hrefPath = hrefUrl.encodedPath.let { if (it.endsWith("/")) it else "$it/" }

            // ⚠️ self 条目：PROPFIND Depth 1 一定包含请求目录自身，列表里不该出现它。
            if (hrefPath == requestPath) return@mapNotNull null

            val relative = if (hrefPath.startsWith(requestPath)) {
                hrefPath.removePrefix(requestPath)
            } else {
                hrefPath.trimStart('/')
            }

            val name = resource.displayName.ifBlank {
                hrefUrl.pathSegments.lastOrNull { it.isNotEmpty() } ?: relative.trimEnd('/')
            }

            VDictionary(
                mapOf(
                    "name" to VString(name),
                    "path" to VString(relative.trimEnd('/')),
                    "is_directory" to VBoolean(resource.isCollection),
                    "size" to VNumber((resource.contentLength ?: 0L).toDouble()),
                    "content_type" to VString(resource.contentType.orEmpty()),
                    "last_modified" to VString(resource.lastModified.orEmpty()),
                    "url" to VString(hrefUrl.toString()),
                )
            )
        }
    }

    /** href 可能是绝对 URL 或绝对路径 ⇒ 统一成 `HttpUrl`（失败返回 null，该条被丢弃）。 */
    private fun resolveHref(baseUrl: String, href: String): HttpUrl? =
        runCatching {
            href.toHttpUrlOrNull() ?: baseUrl.toHttpUrlOrNull()?.resolve(href)
        }.getOrNull()

    // ── upload ──────────────────────────────────────────────────

    private suspend fun doUpload(
        client: WebDavClient,
        config: WebDavConfig,
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val remotePath = resolveRemotePath(context)
        val source = classifyLocalSource(VariableResolver.resolve(context.getVariableAsString(LOCAL_FILE_ID, ""), context))

        val body: okhttp3.RequestBody
        val displayName: String
        when (source) {
            null -> return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                appContext.getString(R.string.error_vflow_network_webdav_no_local_file),
                partialOutputsOf(OP_UPLOAD, success = false, error = "未选择本地文件"),
            )

            is WebDavLocalSource.FilePath -> {
                val file = File(source.path)
                if (!file.isFile) {
                    return ExecutionResult.Failure(
                        appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                        appContext.getString(R.string.error_vflow_network_webdav_local_file_missing, source.path),
                        partialOutputsOf(OP_UPLOAD, success = false, error = "本地文件不存在：${source.path}"),
                    )
                }
                // ⚠️ `asRequestBody` 是**可重发**的（每次 writeTo 重新读文件）——
                // 重定向要重发同一个 body，用一次性流会在第二跳拿到空 body。
                displayName = file.name
                body = file.asRequestBody(mimeTypeOf(file.name).toMediaTypeOrNull())
            }

            is WebDavLocalSource.ContentUri -> {
                // SAF 选的 `content://` 文件：读进内存拿字节（本轮不做流式，见方案 R3）。
                val bytes = try {
                    appContext.contentResolver.openInputStream(android.net.Uri.parse(source.uri))?.use { it.readBytes() }
                } catch (e: Exception) {
                    null
                } ?: return ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
                    appContext.getString(R.string.error_vflow_network_webdav_local_file_unreadable, source.uri),
                    partialOutputsOf(OP_UPLOAD, success = false, error = "无法读取：${source.uri}"),
                )

                displayName = android.net.Uri.parse(source.uri).lastPathSegment?.substringAfterLast('/') ?: UNKNOWN_FILE_NAME
                body = bytes.toRequestBody(mimeTypeOf(displayName).toMediaTypeOrNull())
            }
        }

        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_network_webdav_uploading, displayName)))

        val overwrite = context.getVariableAsBoolean(OVERWRITE_ID) ?: true

        return when (val result = client.put(config.remoteBasePath, remotePath, body, overwrite)) {
            is WebDavResult.Success -> ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "remote_path" to VString(remotePath),
                    "status_code" to VNumber(result.code.toDouble()),
                    "error" to VString(""),
                )
            )

            is WebDavResult.HttpError -> {
                val message = if (result.code == 412) {
                    appContext.getString(R.string.error_vflow_network_webdav_remote_file_exists, remotePath)
                } else {
                    httpErrorDetail(result)
                }
                ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_upload_failed),
                    message,
                    partialOutputsOf(OP_UPLOAD, success = false, error = message, statusCode = result.code),
                )
            }

            is WebDavResult.Failure -> ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_upload_failed),
                describeFailure(result),
                partialOutputsOf(OP_UPLOAD, success = false, error = describeFailure(result)),
            )
        }
    }

    // ── download ────────────────────────────────────────────────

    private suspend fun doDownload(
        client: WebDavClient,
        config: WebDavConfig,
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val remotePath = resolveRemotePath(context)
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_network_webdav_downloading, remotePath)))

        return when (val result = client.get(config.remoteBasePath, remotePath)) {
            is WebDavResult.Success -> {
                val fileName = remoteFileName(config, remotePath)
                val rawDir = context.getVariableAsString(SAVE_DIR_ID, "")
                val dir = resolveLocalPath(rawDir, context)?.let { File(it) } ?: StorageManager.exportsDir

                if (!dir.exists() && !dir.mkdirs()) {
                    return ExecutionResult.Failure(
                        appContext.getString(R.string.error_vflow_network_webdav_download_failed),
                        appContext.getString(R.string.error_vflow_network_webdav_save_dir_failed, dir.absolutePath),
                        partialOutputsOf(OP_DOWNLOAD, success = false, error = "无法创建目录：${dir.absolutePath}"),
                    )
                }

                val overwrite = context.getVariableAsBoolean(OVERWRITE_ID) ?: true
                val target = File(dir, fileName)
                if (target.exists() && !overwrite) {
                    val message = appContext.getString(R.string.error_vflow_network_webdav_local_file_exists, target.absolutePath)
                    return ExecutionResult.Failure(
                        appContext.getString(R.string.error_vflow_network_webdav_download_failed),
                        message,
                        partialOutputsOf(OP_DOWNLOAD, success = false, error = message),
                    )
                }

                target.writeBytes(result.bytes)
                onProgress(
                    ProgressUpdate(
                        appContext.getString(R.string.msg_vflow_network_webdav_download_saved, target.absolutePath),
                        100,
                    )
                )

                ExecutionResult.Success(
                    mapOf(
                        "success" to VBoolean(true),
                        "file" to VFile(target.toURI().toString(), mimeTypeOf(fileName)),
                        "file_path" to VString(target.absolutePath),
                        "size" to VNumber(result.bytes.size.toDouble()),
                        "status_code" to VNumber(result.code.toDouble()),
                        "error" to VString(""),
                    )
                )
            }

            is WebDavResult.HttpError -> ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_download_failed),
                httpErrorDetail(result),
                partialOutputsOf(OP_DOWNLOAD, success = false, error = httpErrorDetail(result), statusCode = result.code),
            )

            is WebDavResult.Failure -> ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_download_failed),
                describeFailure(result),
                partialOutputsOf(OP_DOWNLOAD, success = false, error = describeFailure(result)),
            )
        }
    }

    /**
     * 把用户在参数面板里选的本地来源分类。
     *
     * ⚠️ **必须区分 `content://`** —— 编辑器的 `PickerType.FILE` 走 SAF
     * （`ACTION_GET_CONTENT`），选完可能是 `content://com.android.providers...`；
     * 而 `StableFilePathResolver.resolveFilePathOrUri` 在**映射不到文件系统路径时会原样返回 URI**。
     * 早先版本把这种情形一律当「没选文件」⇒ 用户明明选了却报「未选择要上传的本地文件」，
     * 而且没有任何线索指向真正的原因。这是**从 SAF 选文件的主路径**，不是边缘情况。
     *
     * 空串 ⇒ null（= 用户根本没填）。
     */
    internal fun classifyLocalSource(raw: String): WebDavLocalSource? {
        val value = raw.trim()
        return when {
            value.isEmpty() -> null
            value.startsWith("content://") -> WebDavLocalSource.ContentUri(value)
            value.startsWith("file://") -> WebDavLocalSource.FilePath(value.removePrefix("file://"))
            else -> WebDavLocalSource.FilePath(value)
        }
    }

    /** 远端路径的末段作为本地文件名（**URL 解码后**，否则中文名会落成 `%E4%B8%AD`）。 */
    private fun remoteFileName(config: WebDavConfig, remotePath: String): String {
        val segments = WebDavUrlBuilder.resolve(config.baseUrl, config.remoteBasePath, remotePath, directory = false)
            ?.pathSegments
            .orEmpty()
        return segments.lastOrNull { it.isNotBlank() } ?: UNKNOWN_FILE_NAME
    }

    // ── mkdir ───────────────────────────────────────────────────

    private suspend fun doMkdir(
        client: WebDavClient,
        config: WebDavConfig,
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val remotePath = resolveRemotePath(context)
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_network_webdav_mkdir, remotePath)))

        return when (val result = client.mkcol(config.remoteBasePath, remotePath)) {
            is WebDavResult.Success -> ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "remote_path" to VString(remotePath),
                    "status_code" to VNumber(result.code.toDouble()),
                    "error" to VString(""),
                )
            )

            is WebDavResult.HttpError -> if (result.code == 405) {
                // ⚠️ RFC 4918：对**已存在**的集合做 MKCOL，服务器应回 405。
                // 按「已存在」成功处理（幂等语义），但把提示放进输出，别让用户以为真新建了。
                ExecutionResult.Success(
                    mapOf(
                        "success" to VBoolean(true),
                        "remote_path" to VString(remotePath),
                        "status_code" to VNumber(result.code.toDouble()),
                        "error" to VString(appContext.getString(R.string.error_vflow_network_webdav_dir_exists, remotePath)),
                    )
                )
            } else {
                ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_mkdir_failed),
                    httpErrorDetail(result),
                    partialOutputsOf(OP_MKDIR, success = false, error = httpErrorDetail(result), statusCode = result.code),
                )
            }

            is WebDavResult.Failure -> ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_mkdir_failed),
                describeFailure(result),
                partialOutputsOf(OP_MKDIR, success = false, error = describeFailure(result)),
            )
        }
    }

    // ── delete ──────────────────────────────────────────────────

    private suspend fun doDelete(
        client: WebDavClient,
        config: WebDavConfig,
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit,
    ): ExecutionResult {
        val remotePath = resolveRemotePath(context)
        onProgress(ProgressUpdate(appContext.getString(R.string.msg_vflow_network_webdav_deleting, remotePath)))

        // ⚠️ directory 参数影响尾斜杠 —— 但这里**不知道**目标是文件还是目录。
        // 不强猜：直接把原始 path 发出去（多数服务器对文件/目录都接受无尾斜杠的 DELETE，
        // 而加错尾斜杠反而会让部分服务器 404）。若目标其实需要尾斜杠，用户可在路径里自带。
        val directory = remotePath.endsWith("/")

        return when (val result = client.delete(config.remoteBasePath, remotePath, directory)) {
            is WebDavResult.Success -> ExecutionResult.Success(
                mapOf(
                    "success" to VBoolean(true),
                    "status_code" to VNumber(result.code.toDouble()),
                    "error" to VString(""),
                )
            )

            is WebDavResult.HttpError -> {
                // ⚠️ 404 **不当成功**：用户以为删掉了、其实目标路径写错了，
                // 后续步骤会基于错误的前提继续跑。
                val message = if (result.code == 404) {
                    appContext.getString(R.string.error_vflow_network_webdav_remote_not_found, remotePath)
                } else {
                    httpErrorDetail(result)
                }
                ExecutionResult.Failure(
                    appContext.getString(R.string.error_vflow_network_webdav_delete_failed),
                    message,
                    partialOutputsOf(OP_DELETE, success = false, error = message, statusCode = result.code),
                )
            }

            is WebDavResult.Failure -> ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_network_webdav_delete_failed),
                describeFailure(result),
                partialOutputsOf(OP_DELETE, success = false, error = describeFailure(result)),
            )
        }
    }

    // ── 小工具 ──────────────────────────────────────────────────

    private fun resolveRemotePath(context: ExecutionContext): String =
        VariableResolver.resolve(context.getVariableAsString(REMOTE_PATH_ID, ""), context).trim()

    /** 把 `content://` / `file://` / 裸路径统一成文件系统路径；空串返回 null。 */
    private fun resolveLocalPath(raw: String, context: ExecutionContext): String? {
        val resolved = VariableResolver.resolve(raw, context).trim()
        if (resolved.isEmpty()) return null
        val withoutScheme = if (resolved.startsWith("file://")) resolved.removePrefix("file://") else resolved
        return withoutScheme.ifBlank { null }
    }

    private fun mimeTypeOf(fileName: String): String {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension) ?: "application/octet-stream"
    }

    private fun describeFailure(failure: WebDavResult.Failure): String = when (failure.kind) {
        WebDavResult.Failure.Kind.TLS -> appContext.getString(R.string.error_vflow_network_webdav_tls, failure.detail.orEmpty())
        WebDavResult.Failure.Kind.TOO_MANY_REDIRECTS -> failure.detail.orEmpty()
        WebDavResult.Failure.Kind.INVALID_URL -> failure.detail.orEmpty()
        WebDavResult.Failure.Kind.NETWORK -> failure.detail.orEmpty()
    }

    /** 失败时的部分输出（`SKIP` 策略下会被当作该步骤的输出，避免下游拿到 `VNull`）。 */
    private fun partialOutputsOf(
        operation: String,
        success: Boolean,
        error: String,
        statusCode: Int? = null,
    ): Map<String, Any?> = when (operation) {
        OP_LIST -> mapOf(
            "files" to VList(emptyList()),
            "count" to VNumber(0.0),
            "success" to VBoolean(success),
            "error" to VString(error),
        )
        OP_DOWNLOAD -> mapOf(
            "success" to VBoolean(success),
            "file_path" to VString(""),
            "size" to VNumber(0.0),
            "status_code" to VNumber((statusCode ?: 0).toDouble()),
            "error" to VString(error),
        )
        OP_UPLOAD, OP_MKDIR -> mapOf(
            "success" to VBoolean(success),
            "remote_path" to VString(""),
            "status_code" to VNumber((statusCode ?: 0).toDouble()),
            "error" to VString(error),
        )
        else -> mapOf(
            "success" to VBoolean(success),
            "status_code" to VNumber((statusCode ?: 0).toDouble()),
            "error" to VString(error),
        )
    }

    /**
     * 把服务器答复拼成给用户看的诊断串，**带上实际请求到的 URL**。
     *
     * ⚠️ 带上 URL 的起因是一次真机 409（坚果云 `AncestorsNotFound`）：
     * 只报「上传失败 - HTTP 409」时，用户无法判断是**路径拼错**还是**那个集合不存在** ——
     * 这两种的处置完全不同（改路径 vs 先去服务器上建目录）。
     * 有了 URL，用户能自己看出 `…/dav/自动备份.json` 少了一段
     * （坚果云真实可写的只有 `/dav/<用户名>/`，`/dav/` 本身是虚拟根）。
     *
     * ⚠️ URL 里**不含凭据**（Basic Auth 走 header，不拼进 URL）⇒ 可以安全地进工作流日志。
     *
     * ⚠️ 展示前经 [WebDavUrlBuilder.readableHttpUrl] **解码** —— `HttpUrl.toString()` 会把
     * 中文段编成 `%E8%87%AA...`，直接给用户看等于没给（备份文件名默认就带中文）。
     */
    private fun httpErrorDetail(result: WebDavResult.HttpError): String {
        val base = result.detail.orEmpty()
        val url = result.url
        return if (url.isNullOrBlank()) base
        else "$base（目标：${WebDavUrlBuilder.readableHttpUrl(url)}）"
    }

    private fun listFailure(code: Int?, detail: String?): ExecutionResult {
        val message = detail ?: "HTTP ${code ?: 0}"
        return ExecutionResult.Failure(
            appContext.getString(R.string.error_vflow_network_webdav_operation_failed),
            message,
            partialOutputsOf(OP_LIST, success = false, error = message, statusCode = code),
        )
    }
}

/**
 * 用户在「本地文件」参数里给的内容，**按来源分成两类**。
 *
 * ⚠️ 存在的理由：编辑器的 `PickerType.FILE` 走 SAF（`ACTION_GET_CONTENT`），
 * 选完可能是 `content://`（很多文件管理器都走这条），也可能是文件系统路径
 * （`StableFilePathResolver` 能映射出来时）。两者的读取方式**完全不同**，
 * 而把它们混成一个 `String` 就会让「从 SAF 选文件」这条**主路径**被误判成「未选择文件」。
 */
internal sealed interface WebDavLocalSource {
    /** 文件系统路径（已剥掉 `file://` 前缀）。 */
    data class FilePath(val path: String) : WebDavLocalSource

    /** `content://` URI，需经 `ContentResolver` 读取。 */
    data class ContentUri(val uri: String) : WebDavLocalSource
}
