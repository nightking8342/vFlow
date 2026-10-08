// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/WorkflowScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.BackupEnvelope
import com.chaomixian.vflow.core.backup.BackupEnvironment
import com.chaomixian.vflow.core.backup.BackupScope
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.LogLevel
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopeImportResult
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.backup.SecretFieldScrubber
import com.chaomixian.vflow.core.workflow.WorkflowJsonCodec
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.google.gson.JsonArray

/**
 * 备份/恢复**工作流**。
 *
 * ## 完整字段
 *
 * 直接对**整个 `Workflow` 对象**做 Gson 序列化（全部字段），
 * **不是** `WorkflowListRoute.createWorkflowExportData` 那份人工维护的键表
 * （那条路曾漏 5 个字段，现已补齐并由 `WorkflowExportFieldCoverageTest` 机器化核对）。
 *
 * ⚠️ 序列化用的 Gson 由 `BackupEnvironment.json` 提供，生产实现必须与
 * `WorkflowManager` 用同一套构造方式（含 `VObjectGsonAdapter`），否则同一份数据
 * 在两处会写出不同形状 —— 由 `BackupWiringTest` 的源码扫描锁住。
 *
 * ## ⚠️ 导入侧的字段映射走 `WorkflowJsonCodec`
 *
 * 曾经这里用 `env.json.fromJson(element, Workflow::class.java)`，而 Gson 的
 * **反射构造**（`Unsafe.allocateInstance`）**绕过 Kotlin 构造函数** ⇒
 * `Workflow.kt` 里那些 `= 默认值` 完全不执行：引用类型字段缺键 ⇒ `null`、
 * 原生类型 ⇒ `false` / `0`。而 `null` 落在非空字段上**不会当场抛**，
 * 要等第一次 `copy()`（REPLACE 路径必经过）才炸。
 * 现在与磁盘读盘 / 文件导入共用同一份 codec，见 [workflowFromJson] 的注释。
 *
 * ## REPLACE 真的删
 *
 * `REPLACE` 走 `env.replaceWorkflows` ⇒ `WorkflowManager.replaceAllWorkflows`（单次原子写）。
 * **不能**走 `saveAllWorkflows` —— 那是合并语义，旧工作流会残留，
 * 用户看到的是「导入成功了，但旧工作流还在」（反证 A 锁住这条）。
 */
class WorkflowScope : BackupScope {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.USER_CONTENT
    override val sensitive: Boolean = false
    override val defaultIncluded: Boolean = true

    override val importOrder: Int = 10

    /** 工作流的 `folderId` 指向文件夹 id ⇒ 文件夹必须先落盘。 */
    override val dependsOn: List<String> = listOf(FolderScope.ID)

    /**
     * 导出工作流，**并按有无口令处理步骤参数里的凭证**。
     *
     * ## 为什么这里必须处理（静默失效点 6）
     *
     * 工作流步骤参数里也有密钥（`AgentModule.kt:134` / `AutoGLMModule.kt:99` 的
     * `api_key`，`BarkPushModule.kt:60` 的 `device_key` 等）。它们随本 scope
     * 一起导出，**完全绕过 `secrets` scope 的勾选** —— 用户不勾「包含密钥」
     * 却照样把 AI 服务的 key 写进了备份文件。
     *
     * 两条对称的支路（与 [SecretFieldScrubber] 的两条方法一一对应）：
     *
     * | `secrets` | 处理 | `scrubbedFields` |
     * |---|---|---|
     * | null（无口令） | 命中项清成 `""` | **非空** —— 要告诉用户抹了什么 |
     * | 非 null（有口令） | 命中项就地加密成 `$enc` | 空 —— 加密不算丢失信息 |
     *
     * ⚠️ **空集合仍返回 `count = 0` 的 payload，绝不返回 null**
     * （null 是「本次不适用」的专用语义）。
     */
    override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload {
        val workflows = env.getWorkflows()
        val tree = JsonArray().apply { workflows.forEach { add(env.json.toJsonTree(it)) } }

        val scrubbed = if (secrets == null) {
            // 无口令 ⇒ 清空并记位置。返回值**必须**往上传（见 scrubbedFields 的 KDoc）。
            SecretFieldScrubber.scrub(tree)
        } else {
            // 有口令 ⇒ 逐字段加密。加密不是丢失信息，故不记 scrubbedFields。
            SecretFieldScrubber.encryptInPlace(tree, secrets)
            emptyList()
        }

        return ScopePayload(tree.size(), tree, scrubbed)
    }

    override fun import(
        env: BackupEnvironment,
        payload: ScopePayload?,
        mode: ImportMode
    ): ScopeImportResult {
        if (payload == null) {
            return ScopeImportResult(id, ImportStatus.SKIPPED_NOT_SELECTED)
        }

        val elements = BackupEnvelope.asArrayOrNull(payload.data)
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "workflows 的 data 不是 JSON 数组"
            )

        var skipped = 0
        val workflows = elements.mapNotNull { element ->
            val workflow = workflowFromJson(env, element)
            if (workflow == null) {
                skipped++
                null
            } else {
                workflow
            }
        }

        when (mode) {
            ImportMode.REPLACE -> env.replaceWorkflows(workflows)
            // ⚠️ 委托上游既有语义（合并、本地独有保留、既有条目 folderId 反向覆盖）。
            //    已知代价：合并式导入恢复不了文件夹归属。
            ImportMode.MERGE -> env.mergeWorkflows(workflows)
        }

        // ⚠️ 工作流落盘后**必须**重载触发器，否则「工作流进来了但触发器不调度」
        //    （静默失效点 5）。**空列表也要调** —— REPLACE 空集 = 删光了所有工作流，
        //    那正是最需要重载触发器的时刻。文件夹与全局变量**不调**（它们不改触发器集合）。
        env.reloadTriggers()

        return ScopeImportResult(
            scopeId = id,
            status = ImportStatus.IMPORTED,
            imported = workflows.size,
            skipped = skipped
        )
    }

    companion object {
        const val ID = "workflows"

        /**
         * 解析单条工作流记录。
         *
         * ⚠️ **`id` 缺失时返回 null（跳过该条）而不是补一个新 id** ——
         * 没有 id 的工作流无法与任何东西对上，静默给它一个新 id 只会往用户列表里
         * 塞空壳。（这是本路径与另外两条的**唯一**差别：读盘/文件导入都允许补 id，
         * 因为那两处是「导入」语义；这里是「恢复」，同一份数据不该换身份。）
         *
         * ⚠️⚠️ **字段映射走 `WorkflowJsonCodec`，不用 Gson 反射反序列化。**
         * 曾经这里用 `env.json.fromJson(element, Workflow::class.java)`，而 Gson 的
         * 反射构造用 `Unsafe.allocateInstance` **绕过 Kotlin 构造函数** ⇒
         * `Workflow.kt` 里那些 `= 默认值` 完全不执行：
         * - 引用类型字段（`logLevel` / `cardIconRes` / `tags` …）缺键 ⇒ **`null`**；
         * - 原生类型（`isEnabled` / `vFlowLevel`）缺键 ⇒ `false` / `0`
         *   （而另外两条读路径的回落是 `true` / `1`）。
         *
         * ⚠️ `null` 落在非空字段上**不会当场抛**（Kotlin 的检查插在赋值处、
         * 不插在读取处），它会潜伏到第一次 `copy()` —— 而 REPLACE 走
         * `replaceAllWorkflows` → `normalizeWorkflow` → **`copy`**
         * ⇒ 表现是「恢复一份**旧版本**写的备份直接崩」，栈却指向 `Workflow.copy`，
         * 看不出是备份缺键（已实测复现）。
         *
         * ⇒ 改用 codec 后，四条读写路径（磁盘读盘 / 文件导入 / 备份恢复 /
         * 导出）**共用同一份字段映射**，加字段只改 codec。
         */
        fun workflowFromJson(
            env: BackupEnvironment,
            element: com.google.gson.JsonElement
        ): Workflow? {
            if (!element.isJsonObject) return null
            val obj = element.asJsonObject

            // 先挡 id：缺失 / null / 非字符串 / 空白都算「这条记录没法与任何东西对上」。
            val id = obj.get("id") ?: return null
            if (id.isJsonNull || !id.isJsonPrimitive || !id.asJsonPrimitive.isString) return null
            if (id.asString.isBlank()) return null

            return try {
                WorkflowJsonCodec.parse(obj)
            } catch (e: Exception) {
                // codec 对任何键缺失/类型不符都不抛；走到这里说明遇到了更意外的东西
                // （如构造期的内存问题）。单条坏掉不该让整份备份失败。
                env.log(LogLevel.W, "WorkflowScope", "工作流记录解析失败，已跳过", e)
                null
            }
        }
    }
}
