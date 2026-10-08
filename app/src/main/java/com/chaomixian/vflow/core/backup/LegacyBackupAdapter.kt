// 文件: main/java/com/chaomixian/vflow/core/backup/LegacyBackupAdapter.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.workflow.WorkflowJsonImportParser
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject

/**
 * 把**旧格式备份**（无 `schema` 的信封）转成新的 scope payload。
 *
 * 旧格式来自 `ui/workflow_list/WorkflowListRoute.kt` 的既有导出：
 * `{"workflows":[…键表…],"folders":[…]}`。
 *
 * ⚠️ **旧版本导出的文件里确实缺那 5 个字段**（`maxExecutionTime` /
 * `reentryBehavior` / `silentExecution` / `logLevel` / `functionSignature`）
 * —— 那是历史事实，改不了；但**现在的导出已经补上**（见
 * `WorkflowListRoute.createWorkflowExportData`），故只有「用旧版本导出的文件」
 * 会走回落路径。`WorkflowJsonImportParser` 对每个缺失键都有明确回落
 * （`logLevel` → `VERBOSE`，即改动前行为）。
 *
 * ## 做法：解析成对象 → 用新格式重新序列化
 *
 * 解析复用既有的 [WorkflowJsonImportParser]（它认 4 种形状），产出的是
 * `Workflow` / `WorkflowFolder` **对象**；再经 [Gson] 序列化回新格式。
 *
 * ⚠️ 刻意**不做**「把旧 JSON 的键原样搬进新信封」—— 那会让 legacy 路径产生
 * 一份**只此一处的形状**，而后继所有消费者（scope 的 import）只认新形状。
 * 走对象中转，保证「到了 scope 手里，新旧备份的形状完全一致」。
 *
 * ## ⚠️ 一处继承自 `WorkflowJsonImportParser` 的既有行为（本适配器不修）
 *
 * **id 缺失才重生成**：`WorkflowJsonImportParser` 在 `id` 字段缺失/空白时
 * `UUID.randomUUID()`。备份里通常有 id，故正常备份不受影响；但**残缺备份**
 * 会拿到新 id —— 那是「导入」语义，不是「恢复」语义。
 *
 * （曾另有第二条：`functionSignature` 零解析导致函数工作流静默退化。
 *  该缺陷已修 —— 解析实现下移到 `WorkflowFunctionSignatureCodec`，
 *  三条读路径共用，`LegacyBackupAdapterTest` 的用例已按约定翻面成正面断言。）
 */
class LegacyBackupAdapter(private val json: Gson) {

    /**
     * @return scopeId → payload。**只包含 legacy 里实际存在的范围** ——
     *   旧备份没有 `global_variables`，就不会凭空造一个空的出来
     *   （否则会与「本机注册了但信封里没有 ⇒ SKIPPED_MISSING」的语义混同）。
     */
    fun adapt(root: JsonObject): Map<String, ScopePayload> {
        val parser = WorkflowJsonImportParser(json)
        val parsed = try {
            parser.parse(json.toJson(root))
        } catch (e: Exception) {
            // 坏 JSON 不该让整次恢复崩掉 —— 交给上层记 FAILED / 空结果。
            return emptyMap()
        }

        val out = LinkedHashMap<String, ScopePayload>()

        // ⚠️ 顺序与 BackupScopeRegistry.importOrder() 一致（folders 先于 workflows），
        // 让产出的信封键顺序在 legacy 与新格式两条路径上一致。
        //
        // ⚠️ **按「原始 JSON 里有没有这个键」判存在，不按「解析出几条」** ——
        //    `WorkflowJsonImportParser` 的 `parseObject` 有一个兜底分支：
        //    `data.has("workflows")` 不成立时会把**整个对象当成一个工作流**解析。
        //    于是 `{"folders":[…]}`（只有文件夹的旧备份）会被它产出一条垃圾工作流。
        //    按解析结果判存在，就会把那条垃圾当成真数据导进去。
        if (root.has(KeyWorkflows)) {
            out[WorkflowScopeId] = ScopePayload.of(
                parsed.workflows.map { json.toJsonTree(it) }
            )
        }
        if (root.has(KeyFolders)) {
            out[FolderScopeId] = ScopePayload.of(
                parsed.folders.map { json.toJsonTree(it) }
            )
        }
        // 保持「folders 先于 workflows」的产出顺序（与导入拓扑一致），
        // 上面的写入顺序是为了能按原始键判断，故在这里重排。
        return LinkedHashMap<String, ScopePayload>().apply {
            out[FolderScopeId]?.let { put(FolderScopeId, it) }
            out[WorkflowScopeId]?.let { put(WorkflowScopeId, it) }
        }
    }

    /** 判断一份 root 是否属于「legacy 备份」——与 [BackupEnvelope.read] 的判定保持一致。 */
    fun isLegacy(root: JsonObject): Boolean =
        !root.has("schema") && (root.has("workflows") || root.has("folders"))

    companion object {
        /** 与 `FolderScope.id` 一致。此处硬编码字符串而非 `FolderScope().id`，
         *  是为了避免适配器对 scope 实现类产生编译期依赖（它是「旧→新」的中间层）。 */
        const val FolderScopeId = "folders"
        const val WorkflowScopeId = "workflows"

        const val KeyWorkflows = "workflows"
        const val KeyFolders = "folders"

        /**
         * 便于调用方从文本直接适配。
         *
         * @return 解析失败（非法 JSON）时返回**空 map**而不是抛异常 ——
         *   坏 JSON 不该让整次恢复崩掉，调用方据此走 `Invalid` / 空结果。
         */
        fun adaptText(json: Gson, text: String): Map<String, ScopePayload> {
            val root = try {
                com.google.gson.JsonParser.parseString(text)
            } catch (e: Exception) {
                return emptyMap()
            }
            val obj = if (root.isJsonObject) {
                root.asJsonObject
            } else {
                // 根本身是数组 ⇒ 裸工作流列表，包一层成 legacy 形状。
                JsonObject().apply {
                    add(KeyWorkflows, if (root.isJsonArray) root.asJsonArray else JsonArray())
                }
            }
            return LegacyBackupAdapter(json).adapt(obj)
        }
    }
}
