// 文件: main/java/com/chaomixian/vflow/core/backup/BackupScopeRegistry.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.backup.scopes.ChatScope
import com.chaomixian.vflow.core.backup.scopes.FolderScope
import com.chaomixian.vflow.core.backup.scopes.GlobalVariableScope
import com.chaomixian.vflow.core.backup.scopes.ModuleScope
import com.chaomixian.vflow.core.backup.scopes.SecretsScope
import com.chaomixian.vflow.core.backup.scopes.SettingsScope
import com.chaomixian.vflow.core.backup.scopes.TileScope
import com.chaomixian.vflow.core.backup.scopes.WorkflowScope

/**
 * **全部可备份范围的唯一清单**。
 *
 * ## 为什么要有注册表（而不是各处硬编码）
 *
 * 静默失效点 13：导出 UI 的勾选清单、备份模块的勾选清单、导入时的分发逻辑
 * —— 这三处若各自硬编码一份范围列表，**新加的 scope 永远不会出现在 UI 里**，
 * 而且不会有任何报错（用户只会觉得「怎么没有 xx 的备份选项」）。
 * 三处全部从本注册表派生后，新增一类数据 = 加一个 scope 文件 + 一行 `register`。
 *
 * ## 线程模型
 *
 * 全部方法都应在主线程/单一调用线程上使用（注册表本身不加锁）。
 * 备份的耗时部分（文件 IO、加密）在 scope 内部，不在这里。
 */
object BackupScopeRegistry {

    private val registry = LinkedHashMap<String, BackupScope>()

    private var initialized = false
    private var initializing = false

    /**
     * 诊断出口。默认丢弃。
     *
     * ⚠️ 刻意**不**直接用 `DebugLogger` —— 本包必须保持纯 JVM 可测
     * （由 `BackupPurityTest` 机器化保证）。生产侧若要接日志，由 T5 接线时注入。
     */
    var warningSink: (String) -> Unit = {}

    /**
     * 幂等。注册 T1 的三个 scope（`folders` / `global_variables` / `workflows`）。
     *
     * ⚠️ 由 [all] / [importOrder] / [get] **懒触发**，因此调用方（T5）**无需记得**
     * 显式初始化 —— 这消掉了「写了 `initialize()` 但没有任何调用点」的空档
     * （本仓库已在 `CoreLauncher` / `XposedDiagnostics.messageFor` 上踩过两次同类坑）。
     *
     * 第三方（T2 的 `secrets` scope）随时 `register()` 即可。
     */
    fun initialize() {
        if (initialized || initializing) return
        initializing = true
        try {
            // ⚠️ 顺序只是可读性；真正的导入顺序由 importOrder() 的拓扑排序决定。
            register(FolderScope())
            register(GlobalVariableScope())
            register(WorkflowScope())
            // T2 追加（**不重排上面三行**）：密钥范围。importOrder = 100 ⇒ 排在
            // workflows 之后（理由见 SecretsScope 的 KDoc）。
            register(SecretsScope())
            // T6 追加（**不重排上面四行**）：四个 prefs 类范围。
            // ⚠️ 顺序只是可读性；真正的导入顺序由 importOrder() 拓扑排序决定
            //    ⇒ folders → global_variables → modules → workflows
            //       → tiles → settings → chat → secrets。
            register(SettingsScope())
            register(ChatScope())
            register(ModuleScope())
            register(TileScope())
            initialized = true
        } finally {
            initializing = false
        }
    }

    /**
     * 按 id 注册（或覆盖）。
     *
     * 同 id 覆盖时记一条 W —— 覆盖通常是**意外**（两个 scope 抢同一个 id），
     * 静默覆盖会让其中一个永远不生效且无从排查。
     */
    fun register(scope: BackupScope) {
        val replaced = registry.put(scope.id, scope)
        if (replaced != null) {
            warningSink(
                "BackupScopeRegistry: scope id '${scope.id}' 被覆盖 " +
                    "(${replaced.javaClass.simpleName} → ${scope.javaClass.simpleName})"
            )
        }
    }

    /** 全部已注册的 scope，**注册序**。 */
    fun all(): List<BackupScope> {
        ensureInitialized()
        return registry.values.toList()
    }

    fun get(id: String): BackupScope? {
        ensureInitialized()
        return registry[id]
    }

    /**
     * 拓扑排序后的导入顺序。
     *
     * 规则：在**依赖已就绪**的候选中取 `importOrder` 最小者（并列取 id 字典序，
     * 保证结果稳定可断言）；每轮放行一个，直到全部放行。
     *
     * - **依赖不在注册表里** ⇒ 视为已就绪。它永远不会被导入，因此阻塞没有任何意义
     *   （否则「注册了 workflows 却没注册 folders」会让 workflows 掉进成环兜底路径）。
     * - **成环** ⇒ **不崩**：记一条 W，然后按 `importOrder` 兜底放行。
     *   成环是配置错误，但备份导入是用户数据路径，宁可顺序不佳也不能整体失败。
     */
    fun importOrder(): List<BackupScope> {
        val registered = all()
        val byId = registered.associateBy { it.id }
        val placed = LinkedHashSet<String>()
        val remaining = registered.toMutableList()
        val out = mutableListOf<BackupScope>()

        while (remaining.isNotEmpty()) {
            val ready = remaining.filter { scope ->
                scope.dependsOn.all { dep -> dep in placed || dep !in byId }
            }
            val next = if (ready.isNotEmpty()) {
                ready.minWithOrNull(scopeOrder)
            } else {
                warningSink(
                    "BackupScopeRegistry: importOrder() 检测到依赖成环，" +
                        "剩余 ${remaining.map { it.id }} 按 importOrder 兜底放行"
                )
                remaining.minWithOrNull(scopeOrder)
            } ?: break

            remaining.remove(next)
            placed += next.id
            out += next
        }
        return out
    }

    private val scopeOrder = compareBy<BackupScope>({ it.importOrder }, { it.id })

    /**
     * 导出被选中的 scope。
     *
     * @param selected 本次勾选的 scope id。未勾选者**不会被调用**（`export` 的
     *   返回值里也就不会出现它们 —— 与「勾了但数据为空」区分开）。
     * @return id → payload。**只含 `export` 返回非 null 的项**（null = 本次不适用）。
     *
     * ⚠️ 遍历顺序用 [importOrder] 而非注册序：让产出的 JSON 键顺序稳定，
     * 便于人眼审阅与快照式断言。
     */
    fun exportAll(
        env: BackupEnvironment,
        selected: Set<String>,
        secrets: SecretContext?
    ): Map<String, ScopePayload> {
        val out = LinkedHashMap<String, ScopePayload>()
        for (scope in importOrder()) {
            if (scope.id !in selected) continue
            val payload = try {
                scope.export(env, secrets)
            } catch (e: Exception) {
                // 单个 scope 导出失败不影响其它 scope（与导入侧同一条纪律）。
                env.log(LogLevel.E, TAG, "scope '${scope.id}' 导出失败", e)
                null
            }
            if (payload != null) {
                out[scope.id] = payload
            }
        }
        return out
    }

    /**
     * 导入一份备份。
     *
     * 三条跳过语义（设计 §1.2「不整体失败」）：
     * 1. 信封里有、本机**没注册** ⇒ [ImportStatus.SKIPPED_UNKNOWN]，不崩。
     * 2. 本机注册了、信封里**没有** ⇒ [ImportStatus.SKIPPED_MISSING]，不调 scope。
     * 3. 某个 scope **抛异常** ⇒ 记 [ImportStatus.FAILED]，**继续跑后面的 scope**。
     *
     * ⚠️ 未知 id **永远不进** [get] 查找路径，只记结果（否则将来加 scope 时
     * 一个笔误会变成运行时异常）。
     *
     * ⚠️ 与 [BackupScope.import] 的 `payload == null ⇒ SKIPPED_NOT_SELECTED` 的分工：
     * 这里是**按信封的实际内容**分发（有/没有），null 分支留给直接调用 scope 的场景
     * 以及 scope 自己判定「本机不适用」的情形。
     */
    fun importAll(
        env: BackupEnvironment,
        payloads: Map<String, ScopePayload>,
        mode: ImportMode
    ): List<ScopeImportResult> {
        val results = mutableListOf<ScopeImportResult>()
        val registeredIds = mutableSetOf<String>()

        for (scope in importOrder()) {
            registeredIds += scope.id
            val payload = payloads[scope.id]
            if (payload == null) {
                results += ScopeImportResult(scope.id, ImportStatus.SKIPPED_MISSING)
                continue
            }
            results += try {
                scope.import(env, payload, mode)
            } catch (e: Exception) {
                env.log(LogLevel.E, TAG, "scope '${scope.id}' 导入失败", e)
                ScopeImportResult(
                    scopeId = scope.id,
                    status = ImportStatus.FAILED,
                    message = e.message
                )
            }
        }

        // 信封里有、本机没注册的 —— 记结果但不尝试处理（跳过 + 警告，不崩）。
        payloads.keys
            .filter { it !in registeredIds }
            .sorted()
            .forEach { unknownId ->
                env.log(LogLevel.W, TAG, "备份里有未知 scope '$unknownId'，已跳过")
                results += ScopeImportResult(
                    scopeId = unknownId,
                    status = ImportStatus.SKIPPED_UNKNOWN,
                    message = "本机未注册该范围（备份可能来自更新的版本）"
                )
            }

        return results
    }

    /** 仅供测试：清空注册表并复位初始化标记。 */
    fun clearForTest() {
        registry.clear()
        initialized = false
        initializing = false
    }

    private fun ensureInitialized() {
        if (!initialized) initialize()
    }

    private const val TAG = "BackupScopeRegistry"
}
