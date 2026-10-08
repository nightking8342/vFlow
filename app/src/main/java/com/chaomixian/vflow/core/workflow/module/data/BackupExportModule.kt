// 文件: main/java/com/chaomixian/vflow/core/workflow/module/data/BackupExportModule.kt
// 描述: 把 T1/T2 的备份能力接到工作流编排面上（fork 新增，见 docs/fork/backup-webdav-design.md）。
package com.chaomixian.vflow.core.workflow.module.data

import android.content.Context
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.backup.AndroidBackupEnvironment
import com.chaomixian.vflow.core.backup.BackupPipeline
import com.chaomixian.vflow.core.backup.BackupScopeRegistry
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.execution.ExecutionContext
import com.chaomixian.vflow.core.execution.VariableResolver
import com.chaomixian.vflow.core.module.ActionMetadata
import com.chaomixian.vflow.core.module.AiModuleMetadata
import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.BaseModule
import com.chaomixian.vflow.core.module.ExecutionResult
import com.chaomixian.vflow.core.module.InputDefinition
import com.chaomixian.vflow.core.module.ModuleUIProvider
import com.chaomixian.vflow.core.module.OutputDefinition
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.module.ProgressUpdate
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.utils.StorageManager
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.permissions.PermissionManager
import com.chaomixian.vflow.ui.workflow_editor.PillUtil
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 口令参数 id。⚠️ 命名是**契约**，不许改成 `passphrase` —— 见文件末尾 `PARAM_PASSWORD` 的说明。 */
internal const val PARAM_PASSWORD = "backup_password"

internal const val PARAM_SCOPES = "scopes"
internal const val PARAM_INCLUDE_SECRETS = "include_secrets"
internal const val PARAM_FILE_NAME = "file_name"

/**
 * 备份**容器格式**参数。
 *
 * ⚠️⚠️ **`zip` 是默认值**，理由是纯 JSON 有三个结构性问题（见 `BackupArchive` 的 KDoc）：
 * 图片进不去、内存峰值三份、体积不压缩。而「给一个默认值」意味着**存量工作流**
 * （参数里没有这个键）也会走 ZIP —— 那是刻意的：它们本来就导不出图片。
 *
 * ⚠️ 存的是**稳定常量**（`zip`/`json`），不是本地化文案 —— 存文案会让用户切语言后
 * 已保存的工作流失配（本仓库在 `SimDataSwitch` / `ActivityChanged` 的 match_mode 上
 * 踩过两次同形的坑）。
 */
internal const val PARAM_FORMAT = "format"
internal const val FORMAT_ZIP = "zip"
internal const val FORMAT_JSON = "json"

/** 选项顺序 = `optionsStringRes` 的顺序（两处按位置对应，改动必须同步）。 */
internal val FORMAT_OPTIONS = listOf(FORMAT_ZIP, FORMAT_JSON)

/**
 * 默认文件名：`vflow_backup_<yyyyMMdd_HHmmss>.<ext>`
 *
 * 与 `WorkflowListRoute.kt:458-461` 的时间戳格式一致（复用同一习语，便于用户对照）。
 *
 * ⚠️ **扩展名跟着格式走** —— 一个 `.json` 后缀的 ZIP 文件会让用户双击打不开、
 * 让文件管理器显示错误的类型图标，且**没有任何报错**。
 */
internal fun defaultBackupFileName(
    nowMs: Long = System.currentTimeMillis(),
    format: String = FORMAT_ZIP
): String {
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(nowMs))
    return "vflow_backup_$stamp.${extensionOf(format)}"
}

/** 格式 → 扩展名。未知值按 [FORMAT_ZIP] 处理（与 [normalizeFormat] 同口径）。 */
internal fun extensionOf(format: String?): String =
    if (format == FORMAT_JSON) "json" else "zip"

/**
 * 格式参数的归一化。
 *
 * ⚠️ **未知值 ⇒ [FORMAT_ZIP]**（而不是 JSON）：新格式是新安装的默认，
 * 一个拼错的值应当落到默认档，而不是悄悄退回**能力更弱**的那一档
 * （JSON 导不出图片 —— 用户会以为「图片功能坏了」）。
 */
internal fun normalizeFormat(raw: Any?): String = when (raw) {
    is String -> if (raw.trim().lowercase() == FORMAT_JSON) FORMAT_JSON else FORMAT_ZIP
    else -> FORMAT_ZIP
}

/**
 * 把用户填的 `file_name` 收敛成**一个安全的纯文件名**，并把扩展名对齐到 [format]。
 *
 * ⚠️ 只取 `File(name).name`（剥掉任何目录成分）。否则用户（或模型）可以写
 * `../../x.json` 或 `/sdcard/vFlow/backups` —— 后者会落到目录本身，
 * 写到目录上必然失败，而错误信息含糊（表现为「权限问题」）。
 *
 * ⚠️⚠️ **扩展名必须与格式一致**（用户填 `我的备份.json` 而格式是 zip ⇒ 改成
 * `我的备份.zip`）。不改的话会产出「名字说 json、内容是 zip」的文件 ——
 * 导入侧靠**魔数**判容器（不看扩展名）所以能读，但用户在文件管理器里
 * 看到的就是个错误的类型，且不会有任何提示。
 *
 * ⚠️ 用户填的名字**不带扩展名**时补上；带了**别的**扩展名时**替换**掉
 * （而不是追加成 `x.txt.zip`）。
 *
 * @return 安全文件名；入参为空 / `.` / `..` 时回落到 [defaultBackupFileName]。
 */
internal fun sanitizeBackupFileName(
    raw: String?,
    nowMs: Long = System.currentTimeMillis(),
    format: String = FORMAT_ZIP
): String {
    val trimmed = raw?.trim().orEmpty()
    if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") {
        return defaultBackupFileName(nowMs, format)
    }
    val leaf = File(trimmed).name
    if (leaf.isEmpty() || leaf == "." || leaf == "..") {
        return defaultBackupFileName(nowMs, format)
    }
    return withExtension(leaf, format)
}

/**
 * 把文件名收敛成「主干 + 正确的扩展名」。
 *
 * ⚠️ 主干取 `substringBeforeLast('.')`，**不是** `removeSuffix(".json")` ——
 * 后者只认一种旧扩展名，用户填 `x.txt` 会得到 `x.txt.zip`。
 * 但**也不能无条件截断**：`我的备份.v2` 会被截成 `我的备份`，
 * 那是用户名字的一部分。⇒ 判据是「最后一段是不是一个**已知的**扩展名」。
 */
internal fun withExtension(fileName: String, format: String): String {
    val ext = extensionOf(format)
    val dot = fileName.lastIndexOf('.')
    if (dot <= 0) return "$fileName.$ext"  // 无扩展名（或点名以 . 开头，如 .hidden）
    val tail = fileName.substring(dot + 1)
    return if (tail.lowercase() in KNOWN_EXTENSIONS) {
        fileName.substring(0, dot) + "." + ext
    } else {
        "$fileName.$ext"
    }
}

/**
 * 会被**替换**掉而不是追加的扩展名。
 *
 * ⚠️ 刻意做成一个小集合而不是「任意扩展名」：`我的备份.v2` 里的 `v2` 是名字的一部分，
 * 无条件截断会静默改掉用户起的名字。集合里只放「明显是备份文件扩展名」的那些。
 */
private val KNOWN_EXTENSIONS = setOf("json", "zip", "bak", "backup")

/**
 * 摘要文本的**纯函数层**（§7.2 的可测性要求）。
 *
 * ⚠️⚠️ **它不接受口令参数** —— 这是「摘要不回显口令」在**类型层面**的落实。
 * 参数被有意从签名里去掉：想回显也拿不到值。（由 `BackupExportModuleTest` 锁住。）
 *
 * @param scopeIds 本次勾选的范围 id。
 * @param includeSecrets 是否勾了「包含密钥」。
 * @param fileName 目标文件名（已 sanitize）。
 */
internal fun buildSummaryText(
    scopeIds: List<String>,
    includeSecrets: Boolean,
    fileName: String,
): String {
    val secretsLabel = if (includeSecrets) "含密钥" else "不含密钥"
    return "导出备份：${scopeIds.size} 个范围（$secretsLabel）→ $fileName"
}

/**
 * 「导出备份」模块（`vflow.data.export_backup`）。
 *
 * ## 与设置页二级页的关系
 *
 * 两者共用同一份范围清单（`BackupScopeRegistry.all()`）与同一条编排链路
 * （`BackupPipeline.export`）。差别只在**出口**：设置页走 SAF 让用户选位置，
 * 本模块写死 `StorageManager.backupsDir`（`/sdcard/vFlow/backups`）并输出路径
 * —— 后者是「无人值守可跑」的前提（SAF 必然要人点）。
 *
 * ## ⚠️ 参数 id `backup_password` 是契约，不是风格
 *
 * 工作流参数会随 `WorkflowScope` 一起进备份文件。T2 的 `SecretFieldScrubber`
 * 按**子串**判定（`token`/`secret`/`password`/`device_key`/`api_key`），
 * 而 `passphrase` **一个都不命中** ⇒ 用来加密别人口令的那个口令会明文躺进
 * 同一份备份里。`backup_password` 含子串 `password` ⇒ 被清洗。
 * 由 `BackupExportNamingTest` 机器化锁住（断言 `shouldScrub(PARAM_PASSWORD)`）。
 */
class BackupExportModule : BaseModule() {

    override val id = "vflow.data.export_backup"

    override val metadata = ActionMetadata(
        nameStringRes = R.string.module_vflow_data_export_backup_name,
        descriptionStringRes = R.string.module_vflow_data_export_backup_desc,
        name = "导出备份",  // Fallback
        description = "将所选数据导出为备份文件",  // Fallback
        iconRes = R.drawable.rounded_backup_export_24,
        category = "数据",
        categoryId = "data",
    )

    /**
     * ⚠️⚠️ **必须声明 STORAGE**。
     *
     * 本模块写 `/sdcard/vFlow/backups`。Android Q+ 上没有该权限时写入**静默失败**
     * （不抛异常、不报错，只是文件没出现）—— 这是本仓库记录过的「权限齐全的设备上
     * 测不出来」的最坏一类 bug。范式照 `SaveImageModule.kt:47`。
     */
    override val requiredPermissions = listOf(PermissionManager.STORAGE)

    override val uiProvider: ModuleUIProvider = BackupExportUIProvider()

    override fun getInputs(): List<InputDefinition> = listOf(
        InputDefinition(
            id = PARAM_SCOPES,
            nameStringRes = R.string.param_vflow_data_export_backup_scopes_name,
            name = "导出范围",
            staticType = ParameterType.ANY,
            // ⚠️ 勾选清单的**唯一来源**是注册表；默认值同理。硬编码第二份会让
            //    新加的 scope 永远不出现在这里，且没有任何报错。
            defaultValue = BackupScopeRegistry.all()
                .filter { it.defaultIncluded }
                .map { it.id },
            acceptsMagicVariable = false,
        ),
        InputDefinition(
            id = PARAM_INCLUDE_SECRETS,
            nameStringRes = R.string.param_vflow_data_export_backup_include_secrets_name,
            name = "包含密钥",
            staticType = ParameterType.BOOLEAN,
            defaultValue = false,
            acceptsMagicVariable = false,
        ),
        // ⚠️ 约束写在**标签**里而不是只写 hint：hint 在自动表单里是输入框占位符，
        //    而本字段预填了 "" ⇒ 占位符可能不显示（XposedJs 那一批踩过同一个坑）。
        InputDefinition(
            id = PARAM_PASSWORD,
            nameStringRes = R.string.param_vflow_data_export_backup_password_name,
            name = "备份口令（勾选「包含密钥」时必填）",
            hintStringRes = R.string.param_vflow_data_export_backup_password_hint,
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            supportsRichText = true,
        ),
        InputDefinition(
            id = PARAM_FILE_NAME,
            nameStringRes = R.string.param_vflow_data_export_backup_file_name_name,
            name = "文件名（留空则自动命名）",
            staticType = ParameterType.STRING,
            defaultValue = "",
            acceptsMagicVariable = true,
            acceptsNamedVariable = true,
            supportsRichText = true,
        ),
        InputDefinition(
            id = PARAM_FORMAT,
            nameStringRes = R.string.param_vflow_data_export_backup_format_name,
            name = "格式",
            staticType = ParameterType.ENUM,
            // ⚠️ 存**稳定常量**，不是本地化文案（切语言后已保存的工作流会失配）。
            defaultValue = FORMAT_ZIP,
            options = FORMAT_OPTIONS,
            // ⚠️ 本地化走 `optionsStringRes`（与 `options` **按位置对应**），
            //    不是 `appContext.getString` —— `getInputs()` 可能在
            //    `initialize(context)` 之前被调用，那时 `appContext` 尚未注入。
            optionsStringRes = listOf(
                R.string.option_vflow_data_export_backup_format_zip,
                R.string.option_vflow_data_export_backup_format_json,
            ),
            acceptsMagicVariable = false,
        ),
    )

    override fun getOutputs(step: ActionStep?): List<OutputDefinition> = listOf(
        OutputDefinition(
            id = "file_path",
            nameStringRes = R.string.output_vflow_data_export_backup_file_path_name,
            name = "文件路径",
            typeName = VTypeRegistry.STRING.id,
        ),
        OutputDefinition(
            id = "file_name",
            nameStringRes = R.string.output_vflow_data_export_backup_file_name_name,
            name = "文件名",
            typeName = VTypeRegistry.STRING.id,
        ),
        OutputDefinition(
            id = "scope_count",
            nameStringRes = R.string.output_vflow_data_export_backup_scope_count_name,
            name = "范围数量",
            typeName = VTypeRegistry.NUMBER.id,
        ),
        OutputDefinition(
            id = "scrubbed_count",
            nameStringRes = R.string.output_vflow_data_export_backup_scrubbed_count_name,
            name = "被清洗字段数",
            typeName = VTypeRegistry.NUMBER.id,
        ),
    )

    /**
     * ⚠️ 摘要**只经 [buildSummaryText]**，本函数体内**不得**再单独拼接
     * `backup_password` 的值（含前 N 位）。由 `BackupExportModuleTest` 的
     * 源码扫描（剥注释后）锁住。
     */
    override fun getSummary(context: Context, step: ActionStep): CharSequence {
        val scopeIds = readScopeIds(step.parameters[PARAM_SCOPES])
        val includeSecrets = step.parameters[PARAM_INCLUDE_SECRETS] as? Boolean ?: false
        // ⚠️ **必须把 format 传进去** —— 不传的话摘要显示 `x.json` 而实际落盘 `x.zip`
        //    （`sanitizeBackupFileName` 会按格式改扩展名），是「改错了不报错」的形态。
        val fileName = sanitizeBackupFileName(
            step.parameters[PARAM_FILE_NAME] as? String,
            format = normalizeFormat(step.parameters[PARAM_FORMAT])
        )

        return PillUtil.buildSpannable(
            context,
            buildSummaryText(scopeIds, includeSecrets, fileName),
        )
    }

    override suspend fun execute(
        context: ExecutionContext,
        onProgress: suspend (ProgressUpdate) -> Unit
    ): ExecutionResult {
        val step = context.allSteps.getOrNull(context.currentStepIndex)
            ?: return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_data_export_backup_execution_error),
                appContext.getString(R.string.error_vflow_data_export_backup_step_missing),
            )

        val scopeIds = readScopeIds(step.parameters[PARAM_SCOPES])
        if (scopeIds.isEmpty()) {
            return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_data_export_backup_execution_error),
                appContext.getString(R.string.error_vflow_data_export_backup_no_scope),
            )
        }

        val includeSecrets = step.parameters[PARAM_INCLUDE_SECRETS] as? Boolean ?: false
        val rawPassword = (step.parameters[PARAM_PASSWORD] as? String).orEmpty()
        // 走魔法变量/命名变量解析：口令常被放在全局变量里（推荐做法）。
        val password = if (rawPassword.isBlank()) "" else VariableResolver.resolve(rawPassword, context)

        if (includeSecrets && password.isBlank()) {
            // ⚠️ **不允许静默降级成「不含密钥」** —— 用户勾了却拿不到密钥，
            //    而他不会知道。宁可失败。
            return ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_data_export_backup_execution_error),
                appContext.getString(R.string.error_vflow_data_export_backup_password_required),
            )
        }

        // ⚠️⚠️ **必须先解析再 sanitize，顺序不能反**。
        //
        // 原先直接 `sanitizeBackupFileName(step.parameters[...])` ⇒ 模板原样落盘成
        // `自动备份_{{now.time}}test.json`（用户实测），而**下游步骤引用本步骤的输出时
        // 拿到的是已解析的路径**（`file_path` 在下面由 `file.absolutePath` 构造）——
        // 两边不一致 ⇒ WebDAV 上传报「本地文件不存在：…/自动备份_22:47:11test.json」，
        // 而磁盘上的文件名里是 `{{now.time}}` 字面量。
        //
        // 反过来 sanitize **不能**提到解析之前：解析结果可能含 `/`（例如用户填
        // `{{vars.dir}}/x.json`），那正是 sanitize 要剥掉的目录成分。
        //
        // ⚠️ `sanitizeBackupFileName` 只做「剥目录 + 防空」，不做模板识别 ——
        // 解析失败（变量不存在）时它拿到的仍是 `{{...}}` 字面量，会被当作合法文件名
        // 直接落盘。这与仓库里其它模块对 STRING 参数的处理一致（`VariableResolver`
        // 解析不了就原样返回），**不额外加校验**：那是「模板里写了不存在的变量」的
        // 既有全局语义，本模块单独拦会让它成为异类。
        val format = normalizeFormat(step.parameters[PARAM_FORMAT])
        val rawFileName = (step.parameters[PARAM_FILE_NAME] as? String).orEmpty()
        val fileName = sanitizeBackupFileName(
            if (rawFileName.isBlank()) rawFileName else VariableResolver.resolve(rawFileName, context),
            format = format
        )

        onProgress(ProgressUpdate(appContext.getString(R.string.progress_vflow_data_export_backup_exporting)))

        val env = AndroidBackupEnvironment(context.applicationContext)
        val secrets = if (includeSecrets) SecretContext(password.toCharArray()) else null

        return try {
            val dir = StorageManager.backupsDir
            val file = File(dir, fileName)

            // ⚠️ 两条出口的**异常类型不同**：ZIP 路径可能抛 `IOException`（磁盘满、
            //    `Deflater` 分配失败），JSON 路径可能抛 `OutOfMemoryError`（整棵树 + 整串 +
            //    UTF-8 字节数组三份同时在内存里）。下面 catch 的是 `Exception`，
            //    **OOM 是 `Error`、捕不到** —— 这是纯 JSON 格式已知的代价，
            //    也是把它降为可选档的理由（见 `BackupArchive` 的 KDoc）。
            val scrubbedFields: List<String> = if (format == FORMAT_JSON) {
                val result = BackupPipeline.export(env, scopeIds.toSet(), secrets)
                file.writeText(result.text)
                result.scrubbedFields
            } else {
                file.outputStream().use { out ->
                    BackupPipeline.exportToArchive(env, scopeIds.toSet(), secrets, out).scrubbedFields
                }
            }

            onProgress(
                ProgressUpdate(
                    appContext.getString(
                        R.string.progress_vflow_data_export_backup_done,
                        file.absolutePath,
                    )
                )
            )

            ExecutionResult.Success(
                mapOf(
                    "file_path" to file.absolutePath,
                    "file_name" to file.name,
                    "scope_count" to scopeIds.size,
                    "scrubbed_count" to scrubbedFields.size,
                )
            )
        } catch (e: Exception) {
            ExecutionResult.Failure(
                appContext.getString(R.string.error_vflow_data_export_backup_failed),
                e.localizedMessage
                    ?: appContext.getString(R.string.error_vflow_data_export_backup_unknown),
            )
        }
    }

    private fun readScopeIds(raw: Any?): List<String> = when (raw) {
        is List<*> -> raw.filterIsInstance<String>()
        is String -> raw.split(',', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        else -> emptyList()
    }

    override val aiMetadata = AiModuleMetadata(
        // ⚠️ 不给 DIRECT_TOOL：写文件有副作用，且口令参数会被模型当成普通字符串填。
        usageScopes = setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW),
        // ⚠️ 不给 READ_ONLY：它会写盘。
        riskLevel = AiModuleRiskLevel.STANDARD,
        workflowStepDescription = "把所选数据导出为备份文件，输出文件路径",
        inputHints = mapOf(
            // ⚠️ 这条提示是有依据的：模块参数（含口令）明文存在 workflow_list 里，
            //    引用全局变量可让它不随工作流走（方案 §8.1）。
            PARAM_PASSWORD to "备份口令；建议引用全局变量而不是直接填写",
        ),
    )
}
