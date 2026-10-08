// 文件: main/java/com/chaomixian/vflow/core/backup/AndroidBackupEnvironment.kt
package com.chaomixian.vflow.core.backup

import android.content.Context
import android.content.pm.PackageInfo
import androidx.core.content.edit
import androidx.core.content.pm.PackageInfoCompat
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.security.CryptoKeyUnavailableException
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.serialization.VObjectGsonAdapter
import com.chaomixian.vflow.core.webdav.WebDavConfig
import com.chaomixian.vflow.core.webdav.WebDavConfigStore
import com.chaomixian.vflow.core.workflow.FolderManager
import com.chaomixian.vflow.core.workflow.GlobalVariableStore
import com.chaomixian.vflow.core.workflow.WorkflowManager
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.chaomixian.vflow.services.TriggerServiceProxy
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * [BackupEnvironment] 的 Android 实现 —— 委托 `WorkflowManager` / `FolderManager` /
 * `GlobalVariableStore` / `TriggerServiceProxy`。
 *
 * ⚠️⚠️ **这是整个 `core/backup/` 里唯一允许 import `android.*` 的文件**
 * （由 `BackupPurityTest` 机器化保证）。其余文件必须保持纯 JVM 可测，
 * 否则本任务的验收（`./gradlew test`）就证明不了任何 scope 语义。
 *
 * ## REPLACE 与 MERGE 走**不同的**上游方法（本类存在的全部意义）
 *
 * | 模式 | 工作流 | 文件夹 |
 * |---|---|---|
 * | REPLACE | `replaceAllWorkflows`（**新增**，单次原子写、真删） | `replaceAllFolders`（**新增**） |
 * | MERGE | `saveAllWorkflows`（既有，合并） | 逐个 `saveFolder`（既有，upsert） |
 *
 * ⚠️ 把 REPLACE 写成 `saveAllWorkflows` 是本设计最危险的静默失效：**不报错**，
 * 只是旧数据残留。纯 JVM 测试**测不出**这类「调用点用错了哪个 API」——
 * 故由 `BackupWiringTest` 的源码扫描机器化锁住（这是必需项，不是可选项）。
 */
class AndroidBackupEnvironment(context: Context) : BackupEnvironment {

    private val appContext: Context = context.applicationContext
    private val workflowManager = WorkflowManager(appContext)
    private val folderManager = FolderManager(appContext)

    /**
     * ⚠️ **必须与 `WorkflowManager.kt:70-72` 用同一套构造方式。**
     *
     * 两处若漂移（例如这里漏了 `VObjectGsonAdapter`），同一份 `Workflow`
     * 会在「备份写出的形状」与「`WorkflowManager` 读回的形状」之间不一致 ⇒
     * 备份出来的东西读不回来，而**两端各自都不报错**。
     * 由 `BackupWiringTest` 的源码扫描锁住（两边都必须出现同一行构造调用）。
     */
    override val json: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(VObject::class.java, VObjectGsonAdapter())
        .create()

    /**
     * ⚠️ **不能用 `BuildConfig`** —— `app/build.gradle.kts` 的 `buildFeatures` 里
     * 没有 `buildConfig = true`，全仓零处 `BuildConfig` 引用，项目根本不生成它。
     * 走项目既有写法（`CrashReportManager.kt:194,201-202` / `DebugLogger.kt:90-92`）。
     */
    private val packageInfo: PackageInfo? = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0)
    }.getOrNull()

    override val appVersionName: String = packageInfo?.versionName ?: "N/A"

    override val appVersionCode: Int = packageInfo
        ?.let { PackageInfoCompat.getLongVersionCode(it).toInt() }
        ?: -1

    /**
     * 附件根目录（ZIP 容器里的 `files/` 落到这里）。
     *
     * ⚠️ `filesDir` 下**不止**图标目录 —— `shared_prefs` 等在别处，但
     * `files/` 里可能还有其它功能的文件。故打包侧只认 [ATTACHMENT_KEYS]
     * 显式列出的字段（见 `BackupArchive.attachmentsOf`），**不是**「把 filesDir 整个带走」。
     */
    override val filesRoot: java.io.File = appContext.filesDir

    override fun getWorkflows(): List<Workflow> = workflowManager.getAllWorkflows()

    override fun replaceWorkflows(workflows: List<Workflow>) {
        workflowManager.replaceAllWorkflows(workflows)
    }

    override fun mergeWorkflows(workflows: List<Workflow>) {
        workflowManager.saveAllWorkflows(workflows)
    }

    override fun getFolders(): List<WorkflowFolder> = folderManager.getAllFolders()

    override fun replaceFolders(folders: List<WorkflowFolder>) {
        folderManager.replaceAllFolders(folders)
    }

    override fun mergeFolders(folders: List<WorkflowFolder>) {
        folders.forEach(folderManager::saveFolder)
    }

    override fun getGlobalVariables(): Map<String, VObject> =
        GlobalVariableStore.getAll(appContext)

    override fun replaceGlobalVariables(values: Map<String, VObject>) {
        GlobalVariableStore.replaceAll(appContext, values)
    }

    override fun reloadTriggers() {
        TriggerServiceProxy.reloadTriggers(appContext)
    }

    /**
     * 密钥出口。**必须在此实现** —— 纯 JVM 测试环境对此不感兴趣，
     * 但 `SecretsScope` 在生产里只能通过它拿到 `SharedPreferences`。
     *
     * ⚠️ 本属性存在与否由 `BackupPipelineTest` 的源码扫描锁住（防「写了但没接」）。
     */
    override val secretStore: SecretStore = AndroidSecretStore(appContext)

    /**
     * 通用 prefs 出口（T6 追加）。
     *
     * ⚠️ 用**已有的** `android.content.Context`（[AndroidSecretStore] 同款），
     * **不新增任何 android import** —— 故 `BackupPurityTest.ANDROID_ALLOWLIST` 无需改动。
     */
    override val prefs: BackupPrefs = AndroidPrefs(appContext)

    /**
     * WebDAV 转档出口（T6 追加）。
     *
     * ⚠️ 存在的理由见 [WebDavBackupStore] 的 KDoc：落盘的密码是**设备 Keystore 密文**，
     * 换机解不开，必须解成明文再用备份口令重加密。**绝不能**把 `webdav_config_prefs`
     * 的原始 JSON 当普通键搬进备份。
     */
    override val webDavBackup: WebDavBackupStore = AndroidWebDavBackup(appContext)

    /**
     * 嵌套**私有**类 —— 「只有本文件能碰 `android.*`」这条约束的落实方式。
     *
     * 做成嵌套类而不是顶层类，是因为：一个顶层类若放在 `core/backup/` 下，
     * 会立刻被 `BackupPurityTest` / `SecretLayerPurityTest` 的扫描判为违规
     * （它们按目录走），从而逼着人去放宽白名单 —— 而放宽白名单正是这条约束
     * 最不该有的演化。嵌套在唯一被豁免的文件里，规则不需要任何例外。
     */
    private class AndroidSecretStore(private val ctx: Context) : SecretStore {

        private fun prefs(name: String) =
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

        override fun getString(prefsName: String, key: String): String? =
            prefs(prefsName).getString(key, null)

        override fun putString(prefsName: String, key: String, value: String?) {
            prefs(prefsName).edit().apply {
                if (value == null) remove(key) else putString(key, value)
            }.apply()
        }
    }

    /**
     * 通用 prefs 实现（T6）。
     *
     * ⚠️ 与 [AndroidSecretStore] 同款：**不写显式返回类型**，让编译器推断出
     * `android.content.SharedPreferences` —— 用全限定名写显式类型能绕过
     * `BackupPurityTest` 的 `import android.` 检查但是自欺（检查的本意是
     * 「不要依赖平台类型」，而不是「不要写 import 行」）。
     */
    private class AndroidPrefs(private val ctx: Context) : BackupPrefs {

        private fun prefs(name: String) =
            ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

        override fun readAll(prefsName: String): Map<String, Any?> =
            prefs(prefsName).all

        override fun putAll(prefsName: String, values: Map<String, Any?>) {
            // ⚠️ `commit = true` 与 `GlobalVariableStore` / `WebDavConfigStore` 同款 ——
            // 备份是用户显式动作，需要「写完才返回」，否则紧接着读回会拿到旧值。
            prefs(prefsName).edit(commit = true) {
                for ((key, value) in values) {
                    when (value) {
                        is String -> putString(key, value)
                        is Boolean -> putBoolean(key, value)
                        is Int -> putInt(key, value)
                        is Long -> putLong(key, value)
                        is Float -> putFloat(key, value)
                        is Set<*> -> {
                            // ⚠️ 只认 `Set<String>`；别的 Set 静默转字符串比跳过更危险。
                            @Suppress("UNCHECKED_CAST")
                            if (value.all { it is String }) {
                                putStringSet(key, value as Set<String>)
                            } else {
                                DebugLogger.w(TAG, "prefs '$prefsName' 的键 '$key' 是 Set<非字符串>，已跳过")
                            }
                        }
                        else -> DebugLogger.w(
                            TAG, "prefs '$prefsName' 的键 '$key' 类型不支持，已跳过"
                        )
                    }
                }
            }
        }

        override fun removeAll(prefsName: String, keys: Set<String>) {
            if (keys.isEmpty()) return
            prefs(prefsName).edit(commit = true) {
                keys.forEach { remove(it) }
            }
        }
    }

    /**
     * WebDAV 转档实现（T6）。契约见 [WebDavBackupStore]。
     *
     * ⚠️⚠️ 本类是 `WebDavBackupEntry` 的**唯一**生产进出点；[WebDavBackupEntry]
     * 没有 `encryptedPassword` 字段 ⇒ 结构上不存在「搬别的设备的 Keystore 密文」这条路。
     */
    private class AndroidWebDavBackup(private val ctx: Context) : WebDavBackupStore {

        override fun readAll(): List<WebDavBackupEntry> =
            WebDavConfigStore.getAll(ctx).map { cfg ->
                val pwd = try {
                    WebDavConfigStore.decryptPassword(cfg)
                } catch (e: CryptoKeyUnavailableException) {
                    // ⚠️ 单条失败**不抛**：否则一条坏配置毁掉整次导出
                    //    （用户连工作流都备不了，而这与 WebDAV 毫无关系）。
                    DebugLogger.w(TAG, "WebDAV 配置 '${cfg.id}' 的密码解不开，该条不带密码进备份", e)
                    null
                }
                WebDavBackupEntry(
                    id = cfg.id,
                    name = cfg.name,
                    baseUrl = cfg.baseUrl,
                    username = cfg.username,
                    password = pwd,
                    allowInsecureTls = cfg.allowInsecureTls,
                    timeoutSeconds = cfg.timeoutSeconds,
                    remoteBasePath = cfg.remoteBasePath
                )
            }

        override fun writeAll(entries: List<WebDavBackupEntry>, mode: ImportMode): Int {
            var written = 0
            for (entry in entries) {
                val config = WebDavConfig(
                    id = entry.id,
                    name = entry.name,
                    baseUrl = entry.baseUrl,
                    username = entry.username,
                    encryptedPassword = "",
                    allowInsecureTls = entry.allowInsecureTls,
                    timeoutSeconds = entry.timeoutSeconds,
                    remoteBasePath = entry.remoteBasePath
                )
                try {
                    // ⚠️⚠️ **password 为 null 时必须原样传 null，不能传 ""**。
                    //     upsert 的语义：null + 已存在 ⇒ **保留原有密文不动**；
                    //     而 "" ⇒ 写入「空密码」。传 "" 会把本机已有的密码**抹掉**，
                    //     而 null 的含义正是「备份里没带密码」，不是「备份说没有密码」。
                    //     由 WebDavSecretsTranscodeTest 的一条用例锁住（风险 R15）。
                    WebDavConfigStore.upsert(ctx, config, entry.password)
                    written++
                } catch (e: CryptoKeyUnavailableException) {
                    // 单条失败：该条跳过，其余继续（**不影响其它密钥的导入**，
                    // 也不让整个 scope 变 FAILED）。
                    DebugLogger.w(TAG, "WebDAV 配置 '${entry.id}' 写入失败，已跳过", e)
                }
            }

            if (mode == ImportMode.REPLACE) {
                // REPLACE 才删「备份里没有的」。MERGE 保留本地多出的配置。
                val keep = entries.map { it.id }.toSet()
                WebDavConfigStore.getAll(ctx)
                    .filter { it.id !in keep }
                    .forEach { WebDavConfigStore.delete(ctx, it.id) }
            }
            return written
        }
    }

    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.D -> DebugLogger.d(tag, message)
            LogLevel.I -> DebugLogger.i(tag, message)
            LogLevel.W -> if (error != null) {
                DebugLogger.w(tag, message, error)
            } else {
                DebugLogger.w(tag, message)
            }
            LogLevel.E -> if (error != null) {
                DebugLogger.e(tag, message, error)
            } else {
                DebugLogger.e(tag, message)
            }
        }
    }

    private companion object {
        const val TAG = "AndroidBackupEnv"
    }
}
