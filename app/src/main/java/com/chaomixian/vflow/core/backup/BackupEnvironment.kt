// 文件: main/java/com/chaomixian/vflow/core/backup/BackupEnvironment.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.google.gson.Gson

/** 接缝层的日志级别 —— 刻意不直接复用 `DebugLogger`，否则本包就不是纯 JVM 了。 */
enum class LogLevel { D, I, W, E }

/**
 * **Android-free 接缝**：所有 scope 只经这个接口读写数据。
 *
 * 生产实现是 `AndroidBackupEnvironment`（委托 `WorkflowManager` / `FolderManager` /
 * `GlobalVariableStore` / `TriggerServiceProxy`），测试实现是 `FakeBackupEnvironment`。
 *
 * ## 为什么 REPLACE 与 MERGE 是**两个方法**而不是一个方法加开关
 *
 * 「REPLACE 误走了合并语义」是本设计里最危险的静默失效之一 —— 它不报错，
 * 只是**旧数据残留**，用户看到的是「导入成功了，但我的旧工作流还在」。
 * 分成两个方法后，这个错误在**类型层面**就能被单测抓住：把 REPLACE 分支改成
 * 调 `mergeWorkflows` 会让 `WorkflowScopeTest > replace_removesWorkflowsMissingFromBackup`
 * 直接变红（反证 A）。挤进一个布尔开关就做不出这条反证。
 */
interface BackupEnvironment {
    val appVersionName: String
    val appVersionCode: Int

    /**
     * JSON 构造点，便于测试注入。
     *
     * ⚠️ 生产实现必须与 `WorkflowManager` 用**同一套构造方式**
     * （`GsonBuilder().registerTypeHierarchyAdapter(VObject::class.java, VObjectGsonAdapter())`），
     * 否则同一份 `Workflow` 会在两处序列化出不同形状 ⇒ 备份写出来的东西读不回来。
     * 这条由 `BackupWiringTest` 的源码扫描锁住。
     */
    val json: Gson

    fun getWorkflows(): List<Workflow>

    /** **覆盖式**：写完后不在这份列表里的工作流**必须消失**。 */
    fun replaceWorkflows(workflows: List<Workflow>)

    /**
     * **合并式**：委托 `WorkflowManager.saveAllWorkflows` 的既有语义 ——
     * 按 id 覆盖，但**既有条目的 `folderId` 会反向覆盖新值**，本地独有工作流全部保留。
     *
     * ⚠️ 已知代价：**合并式导入恢复不了文件夹归属**（REPLACE 可以）。
     * 这是上游既有行为，本任务刻意不改（见方案 §8 决策 3）。
     */
    fun mergeWorkflows(workflows: List<Workflow>)

    fun getFolders(): List<WorkflowFolder>

    /** **覆盖式**：委托新增的 `FolderManager.replaceAllFolders`。 */
    fun replaceFolders(folders: List<WorkflowFolder>)

    /** **合并式**：逐个 `FolderManager.saveFolder`（按 id upsert，既有条目保留）。 */
    fun mergeFolders(folders: List<WorkflowFolder>)

    fun getGlobalVariables(): Map<String, VObject>

    /**
     * **覆盖式**替换全部全局变量。
     *
     * ⚠️ **刻意没有对应的 `mergeGlobalVariables`**：`GlobalVariableStore` 是 object，
     * 且 `replaceAll` 已是唯一批量写入口；「合并」只是 scope 层三行的事
     * （本地 ∪ 入参、入参优先），不值得为此多一个接口方法、多一份实现漂移面。
     */
    fun replaceGlobalVariables(values: Map<String, VObject>)

    /**
     * 工作流落盘后**必须**调用，否则「工作流进来了但触发器不调度」（静默失效点 5）。
     *
     * 只有 `WorkflowScope` 会调它 —— 文件夹与全局变量不改触发器集合。
     */
    fun reloadTriggers()

    /**
     * 密钥读写出口。**null ⇒ 本环境不支持读写密钥**（纯 JVM 测试的默认）。
     *
     * `SecretsScope` 据此判定「本次不适用」并返回 null —— 与「勾了但数据为空」
     * 区分开（后者要返回 `count = 0` 的 payload）。
     *
     * ⚠️ **带默认实现**，是刻意的：T1 的 `FakeBackupEnvironment` 与任何第三方
     * 实现**不改也能编译**。这条属性是 T2 唯一对接口的扩展（方案 §4.7）。
     *
     * 生产实现 `AndroidBackupEnvironment` 覆写它、用嵌套私有类承载
     * `android.content.SharedPreferences` ——「只有该文件能碰 android.*」
     * 这条约束的落实方式。
     */
    val secretStore: SecretStore? get() = null

    /**
     * 通用 prefs 出口。**null ⇒ 本环境不支持**（纯 JVM 测试的默认）。
     *
     * 与 [secretStore] 同款设计（T2 的先例）：带默认实现 ⇒ 既有实现不改也能编译。
     * 服务 `settings` / `chat` / `modules` / `tiles` 四个 scope。
     */
    val prefs: BackupPrefs? get() = null

    /**
     * WebDAV 配置转档出口。**null ⇒ 本环境不支持**（测试默认）。
     *
     * ⚠️ 之所以要专门的接缝而不是走 [prefs]：WebDAV 的密码落盘是**设备 Keystore 密文**，
     * 换机解不开 ⇒ 必须解成明文再用备份口令重加密。见 [WebDavBackupStore] 的 KDoc。
     */
    val webDavBackup: WebDavBackupStore? get() = null

    fun log(level: LogLevel, tag: String, message: String, error: Throwable? = null)
}
