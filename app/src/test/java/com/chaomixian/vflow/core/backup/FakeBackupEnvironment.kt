// 文件: test/java/com/chaomixian/vflow/core/backup/FakeBackupEnvironment.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.serialization.VObjectGsonAdapter
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.google.gson.Gson
import com.google.gson.GsonBuilder

/**
 * 纯 JVM 的内存实现 —— 本任务全部 scope 语义测试的基础设施。
 *
 * [json] 必须与 `AndroidBackupEnvironment` 用**同一套构造方式**（含 `VObjectGsonAdapter`），
 * 否则测试验证的序列化形状与生产不一致，测试全绿而真机数据丢失。
 */
class FakeBackupEnvironment(
    workflows: List<Workflow> = emptyList(),
    folders: List<WorkflowFolder> = emptyList(),
    variables: Map<String, VObject> = emptyMap()
) : BackupEnvironment {

    override val appVersionName: String = "1.5.4-test"
    override val appVersionCode: Int = 50

    override val json: Gson = GsonBuilder()
        .registerTypeHierarchyAdapter(VObject::class.java, VObjectGsonAdapter())
        .create()

    var workflowStore: MutableList<Workflow> = workflows.toMutableList()
    var folderStore: MutableList<WorkflowFolder> = folders.toMutableList()
    var variableStore: MutableMap<String, VObject> = LinkedHashMap(variables)

    /** `reloadTriggers` 被调次数 —— 断言「只导文件夹时不调」。 */
    var reloadCount: Int = 0
        private set

    /** 记录每次**覆盖式**写发生了什么（REPLACE 反证靠它区分两条路径）。 */
    var replaceWorkflowCalls: Int = 0
        private set
    var mergeWorkflowCalls: Int = 0
        private set
    var replaceFolderCalls: Int = 0
        private set
    var mergeFolderCalls: Int = 0
        private set

    val logs: MutableList<Pair<LogLevel, String>> = mutableListOf()

    override fun getWorkflows(): List<Workflow> = workflowStore.toList()

    override fun replaceWorkflows(workflows: List<Workflow>) {
        replaceWorkflowCalls++
        this.workflowStore = workflows.toMutableList()
    }

    override fun mergeWorkflows(workflows: List<Workflow>) {
        mergeWorkflowCalls++
        val existing = this.workflowStore.associateBy { it.id }
        val incomingIds = workflows.map { it.id }.toSet()
        // 复刻 WorkflowManager.saveAllWorkflows 的两条既有语义：
        //   ① 按 id 覆盖；② 既有条目的 folderId **反向覆盖**新值；③ 本地独有保留。
        val merged = workflows.map { incoming ->
            val old = existing[incoming.id]
            if (old != null) incoming.copy(folderId = old.folderId) else incoming
        } + this.workflowStore.filter { it.id !in incomingIds }
        this.workflowStore = merged.toMutableList()
    }

    override fun getFolders(): List<WorkflowFolder> = folderStore.toList()

    override fun replaceFolders(folders: List<WorkflowFolder>) {
        replaceFolderCalls++
        this.folderStore = folders.toMutableList()
    }

    override fun mergeFolders(folders: List<WorkflowFolder>) {
        mergeFolderCalls++
        val result = this.folderStore.toMutableList()
        folders.forEach { incoming ->
            val index = result.indexOfFirst { it.id == incoming.id }
            if (index >= 0) result[index] = incoming else result.add(incoming)
        }
        this.folderStore = result
    }

    override fun getGlobalVariables(): Map<String, VObject> = LinkedHashMap(variableStore)

    override fun replaceGlobalVariables(values: Map<String, VObject>) {
        this.variableStore = LinkedHashMap(values)
    }

    /**
     * 内存版密钥存储（T2 追加）。
     *
     * ⚠️ **是实例字段而非 object** —— 每个用例从干净状态开始，
     * 不需要 `@After` 清理，用例之间也不会互相污染
     * （若做成 object 的静态存储，上一个用例写进去的 `api_key`
     * 会让下一个用例的「导出为空」断言莫名失败）。
     */
    override val secretStore: SecretStore = InMemorySecretStore()

    /**
     * 内存 prefs（T6 追加）。**是实例字段**（与 [secretStore] 同款）——
     * 每个用例从干净状态开始，不互相污染。
     */
    override val prefs: BackupPrefs = InMemoryPrefs()

    /** 内存版 WebDAV 转档（T6 追加）。默认空表 ⇒ 既有用例的导出结果不含 `webdav_configs`。 */
    override var webDavBackup: WebDavBackupStore? = FakeWebDavBackup()

    /**
     * 内存 prefs 实现。
     *
     * `value == null` 在 [putAll] 里被**跳过**（与 `SharedPreferences.putXxx(null)` 的
     * 不可能性一致）—— 「删键」只能走 [removeAll]。
     */
    class InMemoryPrefs : BackupPrefs {
        val data: MutableMap<String, MutableMap<String, Any?>> = LinkedHashMap()

        override fun readAll(prefsName: String): Map<String, Any?> =
            LinkedHashMap(data[prefsName] ?: emptyMap())

        override fun putAll(prefsName: String, values: Map<String, Any?>) {
            val target = data.getOrPut(prefsName) { LinkedHashMap() }
            for ((key, value) in values) {
                if (value == null) continue
                target[key] = value
            }
        }

        override fun removeAll(prefsName: String, keys: Set<String>) {
            val target = data[prefsName] ?: return
            keys.forEach { target.remove(it) }
        }
    }

    /** 内存实现：`prefs 名 → 键 → 值`。`value == null` 删键（与 SharedPreferences 一致）。 */
    class InMemorySecretStore : SecretStore {
        val data: MutableMap<String, MutableMap<String, String>> = LinkedHashMap()

        override fun getString(prefsName: String, key: String): String? =
            data[prefsName]?.get(key)

        override fun putString(prefsName: String, key: String, value: String?) {
            val prefs = data.getOrPut(prefsName) { LinkedHashMap() }
            if (value == null) prefs.remove(key) else prefs[key] = value
        }
    }

    override fun reloadTriggers() {
        reloadCount++
    }

    override fun log(level: LogLevel, tag: String, message: String, error: Throwable?) {
        logs += level to "$tag: $message"
    }
}

/**
 * 内存版 WebDAV 转档（T6）。
 *
 * ⚠️ 它模拟的是**一台设备的 Keystore 边界**：条目以「明文密码」在内存里流转，
 * 而**导出侧与导入侧各用一个实例**就能模拟「换机」
 * （导出侧解出的明文被备份口令加密带走、导入侧重新落盘）。
 *
 * [entries] 可写，便于用例播种「本机已有配置」。
 */
class FakeWebDavBackup(
    val entries: MutableList<WebDavBackupEntry> = mutableListOf()
) : WebDavBackupStore {

    /** `writeAll` 收到过的东西，供断言「null 被原样透传」。 */
    var lastWritten: List<WebDavBackupEntry>? = null
        private set

    var lastWriteMode: ImportMode? = null
        private set

    /** 模拟「这些 id 的密码在本机解不开」。 */
    var undecryptableIds: MutableSet<String> = mutableSetOf()

    override fun readAll(): List<WebDavBackupEntry> =
        entries.map { if (it.id in undecryptableIds) it.copy(password = null) else it }

    override fun writeAll(entries: List<WebDavBackupEntry>, mode: ImportMode): Int {
        lastWritten = entries
        lastWriteMode = mode
        for (entry in entries) {
            val index = this.entries.indexOfFirst { it.id == entry.id }
            if (index >= 0) {
                // ⚠️ 复刻 `WebDavConfigStore.upsert` 的关键语义：
                //    `password == null` ⇒ **保留本机原有的密码**（不是清空）。
                val existing = this.entries[index]
                this.entries[index] = if (entry.password == null) {
                    entry.copy(password = existing.password)
                } else {
                    entry
                }
            } else {
                this.entries.add(entry)
            }
        }
        if (mode == ImportMode.REPLACE) {
            val keep = entries.map { it.id }.toSet()
            this.entries.removeAll { it.id !in keep }
        }
        return entries.size
    }
}
