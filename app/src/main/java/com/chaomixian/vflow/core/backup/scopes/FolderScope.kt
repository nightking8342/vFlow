// 文件: main/java/com/chaomixian/vflow/core/backup/scopes/FolderScope.kt
package com.chaomixian.vflow.core.backup.scopes

import com.chaomixian.vflow.core.backup.BackupEnvelope
import com.chaomixian.vflow.core.backup.BackupEnvironment
import com.chaomixian.vflow.core.backup.BackupScope
import com.chaomixian.vflow.core.backup.ImportMode
import com.chaomixian.vflow.core.backup.ImportStatus
import com.chaomixian.vflow.core.backup.ScopeGroup
import com.chaomixian.vflow.core.backup.ScopeImportResult
import com.chaomixian.vflow.core.backup.ScopePayload
import com.chaomixian.vflow.core.backup.SecretContext
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 备份/恢复**工作流文件夹**。
 *
 * ## ⚠️⚠️ 恢复语义：**必须保 id**，且 parent 必须先于 child 写入
 *
 * 这是本 scope 与 `WorkflowImportHelper.importFolders` 的**定义性差别**：
 * 那个是「导入」语义（给每个文件夹 `UUID.randomUUID()` 并重名加后缀），
 * 本处是「恢复」语义。若重生成 id，备份里所有 `Workflow.folderId` 会**全部悬空**
 * ⇒ 用户的文件夹层级看起来「恢复了」，实际每个工作流都不在任何文件夹里。
 *
 * 同理，`WorkflowFolder` 有 `parentId`：父文件夹必须先落盘，子文件夹才能挂上去。
 * 这里按「parentId 链深度」稳定排序保证这一点。
 */
class FolderScope : BackupScope {

    override val id: String = ID
    override val group: ScopeGroup = ScopeGroup.USER_CONTENT
    override val sensitive: Boolean = false
    override val defaultIncluded: Boolean = true

    /** 第一个导入 —— 工作流依赖它（`WorkflowScope.dependsOn = ["folders"]`）。 */
    override val importOrder: Int = 0

    override val dependsOn: List<String> = emptyList()

    override fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload {
        val folders = env.getFolders()
        // ⚠️ 空集合也必须返回 count=0 的 payload，**绝不返回 null**（静默失效点 12）。
        return ScopePayload.of(folders.map { env.json.toJsonTree(it) })
    }

    override fun import(
        env: BackupEnvironment,
        payload: ScopePayload?,
        mode: ImportMode
    ): ScopeImportResult {
        // 规则 1：null = 本次不勾选/不适用。立即返回，不碰 env。
        if (payload == null) {
            return ScopeImportResult(id, ImportStatus.SKIPPED_NOT_SELECTED)
        }

        val elements = BackupEnvelope.asArrayOrNull(payload.data)
            ?: return ScopeImportResult(
                id, ImportStatus.FAILED, message = "folders 的 data 不是 JSON 数组"
            )

        var skipped = 0
        val folders = elements.mapNotNull { element ->
            val folder = folderFromJson(element)
            if (folder == null) {
                skipped++
                null
            } else {
                folder
            }
        }

        // ⚠️ 保 id 的关键一步在这里：全程不做任何 id 重生成，
        //    也不做重名加后缀（那是「导入」语义）。
        //    FolderManager.replaceAllFolders 会原样落盘。
        val sorted = sortParentsFirst(folders)

        when (mode) {
            ImportMode.REPLACE -> env.replaceFolders(sorted)
            ImportMode.MERGE -> env.mergeFolders(sorted)
        }

        return ScopeImportResult(
            scopeId = id,
            status = ImportStatus.IMPORTED,
            imported = sorted.size,
            skipped = skipped
        )
    }

    companion object {
        const val ID = "folders"

        /**
         * 解析单个文件夹记录。
         *
         * ⚠️ **`id` 缺失时返回 null（跳过该条）而不是 `UUID.randomUUID()`** ——
         * 「保 id」这条约束在**类型层面**就要求本 scope 永不发明 id。
         * 残缺记录（连 id 都没有）无法与任何 `workflow.folderId` 对上，
         * 给它一个新 id 只会往用户列表里塞一个空壳文件夹。
         *
         * `name` 缺失回落空串（与 `WorkflowJsonImportParser.parseFolderObject` 一致），
         * Gson 直接反序列化会让 `var name: String` 收到 **null** ⇒ 后续 NPE。
         */
        fun folderFromJson(element: JsonElement): WorkflowFolder? {
            val obj = if (element.isJsonObject) element.asJsonObject else return null
            val folderId = obj.stringOrNull("id")?.takeIf { it.isNotBlank() } ?: return null
            return WorkflowFolder(
                id = folderId,
                name = obj.stringOrNull("name") ?: "",
                parentId = obj.stringOrNull("parentId")?.takeIf { it.isNotBlank() },
                order = obj.intOrNull("order") ?: 0,
                createdAt = obj.longOrNull("createdAt")?.takeIf { it > 0 } ?: System.currentTimeMillis(),
                modifiedAt = obj.longOrNull("modifiedAt")?.takeIf { it > 0 } ?: System.currentTimeMillis()
            )
        }

        /**
         * 按「parentId 链深度」稳定排序：父先于子。
         *
         * - **parent 不在集合内**（指向已删文件夹、或跨备份引用）⇒ 当作**根**（深度 0），
         *   **不抛异常**。备份里出现悬空 parentId 是可预期的（用户删过文件夹），
         *   为此让整次恢复失败代价太大。
         * - **自引用/成环** ⇒ 深度计算不能死循环，用「步数上限 = 集合大小」兜底。
         * - **稳定**：同深度保持输入顺序（`sortedBy` 稳定），使往返可断言。
         */
        fun sortParentsFirst(folders: List<WorkflowFolder>): List<WorkflowFolder> {
            if (folders.size <= 1) return folders

            val byId = folders.associateBy { it.id }
            val depthCache = HashMap<String, Int>()

            fun depthOf(folder: WorkflowFolder): Int {
                depthCache[folder.id]?.let { return it }
                var depth = 0
                var current: WorkflowFolder? = folder
                var guard = folders.size + 1
                while (current != null && guard-- > 0) {
                    val parentId = current.parentId
                    val parent = if (parentId != null) byId[parentId] else null
                    if (parent == null || parent.id == current.id) break
                    depth++
                    current = parent
                }
                depthCache[folder.id] = depth
                return depth
            }

            return folders.sortedBy { depthOf(it) }
        }
    }
}

private fun JsonObject.stringOrNull(name: String): String? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
    return element.asString
}

private fun JsonObject.intOrNull(name: String): Int? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
    return runCatching { element.asInt }.getOrNull()
}

private fun JsonObject.longOrNull(name: String): Long? {
    val element = get(name) ?: return null
    if (!element.isJsonPrimitive || !element.asJsonPrimitive.isNumber) return null
    return runCatching { element.asLong }.getOrNull()
}
