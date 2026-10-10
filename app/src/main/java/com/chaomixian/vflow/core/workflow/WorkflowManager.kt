package com.chaomixian.vflow.core.workflow

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.serialization.VObjectGsonAdapter
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.core.workflow.model.FunctionSignatureHelper
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.module.triggers.AppStartTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.KeyEventTriggerModule
import com.chaomixian.vflow.core.workflow.module.triggers.ReceiveShareTriggerModule
import com.chaomixian.vflow.services.TriggerServiceProxy
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import java.util.UUID

internal fun normalizeJsonElementValue(element: JsonElement?): Any? {
    if (element == null || element.isJsonNull) return null

    return when {
        element.isJsonObject -> {
            LinkedHashMap<String, Any?>().apply {
                element.asJsonObject.entrySet().forEach { (key, value) ->
                    put(key, normalizeJsonElementValue(value))
                }
            }
        }
        element.isJsonArray -> element.asJsonArray.map { item ->
            normalizeJsonElementValue(item)
        }
        element.isJsonPrimitive -> {
            val primitive = element.asJsonPrimitive
            when {
                primitive.isBoolean -> primitive.asBoolean
                primitive.isNumber -> primitive.asNumber
                primitive.isString -> primitive.asString
                else -> null
            }
        }
        else -> null
    }
}

internal fun normalizedObjectMap(value: Any?): Map<String, Any?>? {
    val map = value as? Map<*, *> ?: return null
    return LinkedHashMap<String, Any?>().apply {
        map.forEach { (key, nestedValue) ->
            key?.toString()?.let { put(it, nestedValue) }
        }
    }
}

internal fun normalizedObjectMapList(value: Any?): List<Map<String, Any?>>? {
    val list = value as? List<*> ?: return null
    return list.mapNotNull(::normalizedObjectMap)
}

class WorkflowManager(val context: Context) {
    private val prefs = context.getSharedPreferences("vflow_workflows", Context.MODE_PRIVATE)
    private val gson = GsonBuilder()
        .registerTypeHierarchyAdapter(VObject::class.java, VObjectGsonAdapter())
        .create()

    /**
     * 保存工作流。
     *
     * @param origin 这次写入的**来源**（fork 新增，见 [WorkflowWriteOrigin]）。
     *   ⚠️⚠️ **默认值必须是 [WorkflowWriteOrigin.EXPLICIT]，不要改成 `AUTOMATIC`** ——
     *   默认值决定了「将来有人忘了标记」时的失败方向：忘标记 ⇒ **多触发**（用户可见、
     *   能报上来）；反过来则是**静默失效**（该触发的不触发）。设计文档 §3.3 定案。
     *
     *   只有 5 处**程序性**写入传 `AUTOMATIC`（权限丢失禁用 ×2 / 权限恢复重开 /
     *   权限回弹 ×2），其余调用点**一行都不用改**。
     */
    fun saveWorkflow(
        workflow: Workflow,
        origin: WorkflowWriteOrigin = WorkflowWriteOrigin.EXPLICIT,
    ) {
        val workflows = getAllWorkflows().toMutableList()
        val index = workflows.indexOfFirst { it.id == workflow.id }
        val oldWorkflow = if (index != -1) workflows[index] else null
        val normalizedWorkflow = normalizeWorkflow(workflow)
        val normalizedVisualWorkflow = normalizedWorkflow.copy(
            cardIconRes = WorkflowVisuals.normalizeIconResName(normalizedWorkflow.cardIconRes),
            cardThemeColor = WorkflowVisuals.normalizeThemeColorHex(normalizedWorkflow.cardThemeColor)
        )

        // 聚合函数签名：从「定义函数」卡片的第一步步骤参数解析 params，并叠加返回值静态推导（决策 9/20）。
        val aggregateSignature = aggregateFunctionSignature(
            steps = normalizedVisualWorkflow.steps,
            existing = normalizedVisualWorkflow.functionSignature
        )

        val workflowToSave = normalizedVisualWorkflow.copy(
            modifiedAt = System.currentTimeMillis(),
            version = normalizedVisualWorkflow.version.ifBlank { "1.0.0" },
            vFlowLevel = normalizedVisualWorkflow.vFlowLevel.takeIf { it > 0 } ?: 1,
            description = normalizedVisualWorkflow.description,
            author = normalizedVisualWorkflow.author,
            homepage = normalizedVisualWorkflow.homepage,
            tags = normalizedVisualWorkflow.tags,
            triggers = normalizedVisualWorkflow.triggers,
            steps = normalizedVisualWorkflow.steps,
            maxExecutionTime = normalizedVisualWorkflow.maxExecutionTime,
            reentryBehavior = normalizedVisualWorkflow.reentryBehavior,
            silentExecution = normalizedVisualWorkflow.silentExecution,
            // ⚠️ 这一行**不能漏**：`copy(...)` 是显式白名单，漏一个字段就是
            //    「用户在编辑器里改了、保存后却没生效」的静默失效。
            logLevel = normalizedVisualWorkflow.logLevel,
            functionSignature = aggregateSignature
        )

        if (index != -1) {
            workflows[index] = workflowToSave
        } else {
            workflows.add(workflowToSave)
        }

        prefs.edit().putString("workflow_list", gson.toJson(workflows)).apply()
        TriggerServiceProxy.notifyWorkflowChanged(context, workflowToSave, oldWorkflow, origin)
        // fork（2026-10-06）：磁贴显示工作流名 / 图标 / 启用态，而这些都在本次写入里。
        // ⚠️ 必须放在**所有**写入路径的汇聚点（本方法）—— 磁贴是「推」模型：
        //    加了 `ACTIVE_TILE` 元数据之后系统不会主动绑，只靠 `requestListeningState`。
        //    少了这一行，表现是「进 App 改了图标，退出后磁贴还是旧的」，看起来像系统缓存。
        // ⚠️ 必须放在 `notifyWorkflowChanged` **之后** —— 那条链路会异步改 `isEnabled`
        //    （权限恢复回弹），先刷会读到中间态。
        TileRefreshNotifier.requestAll(context)
    }

    fun findShareableWorkflows(): List<Workflow> {
        return getAllWorkflows().filter {
            it.isEnabled && it.hasTriggerType(ReceiveShareTriggerModule().id)
        }
    }

    fun findAppStartTriggerWorkflows(): List<Workflow> {
        return getAllWorkflows().filter {
            it.isEnabled && it.hasTriggerType(AppStartTriggerModule().id)
        }
    }

    fun findKeyEventTriggerWorkflows(): List<Workflow> {
        return getAllWorkflows().filter {
            it.isEnabled && it.hasTriggerType(KeyEventTriggerModule().id)
        }
    }

    fun deleteWorkflow(id: String) {
        val workflows = getAllWorkflows().toMutableList()
        val workflowToRemove = workflows.find { it.id == id }
        if (workflowToRemove != null) {
            workflows.remove(workflowToRemove)
            prefs.edit().putString("workflow_list", gson.toJson(workflows)).apply()
            TriggerServiceProxy.notifyWorkflowRemoved(context, workflowToRemove)
            // fork（2026-10-06）：绑了这个工作流的磁贴要回落成「未绑定」态
            // （`BaseWorkflowTileService.updateTileState` 里 `workflow == null` 的分支）。
            TileRefreshNotifier.requestAll(context)
        }
    }

    fun getWorkflow(id: String): Workflow? {
        return getAllWorkflows().find { it.id == id }
    }

    fun getAllWorkflows(): List<Workflow> {
        val json = prefs.getString("workflow_list", null) ?: return emptyList()
        return try {
            val root = JsonParser.parseString(json)
            if (!root.isJsonArray) {
                DebugLogger.w("WorkflowManager", "workflow_list is not a JSON array")
                return emptyList()
            }

            var skippedCount = 0
            val workflows = root.asJsonArray.mapIndexedNotNull { index, element ->
                try {
                    parseWorkflowRecord(element)
                } catch (e: Exception) {
                    skippedCount++
                    DebugLogger.w("WorkflowManager", "Failed to parse workflow record at index $index", e)
                    null
                }
            }

            if (skippedCount > 0) {
                DebugLogger.w("WorkflowManager", "Skipped $skippedCount invalid workflow record(s) while loading")
            }

            workflows
        } catch (e: Exception) {
            DebugLogger.e("WorkflowManager", "Failed to load workflow_list", e)
            emptyList()
        }
    }

    fun clearAllWorkflows() {
        prefs.edit().remove("workflow_list").apply()
    }

    fun duplicateWorkflow(id: String) {
        val original = getWorkflow(id) ?: return
        val newWorkflow = original.copy(
            id = UUID.randomUUID().toString(),
            name = "${original.name} (副本)",
            isEnabled = false
        )
        saveWorkflow(newWorkflow)
    }

    fun saveAllWorkflows(newWorkflows: List<Workflow>) {
        val existingWorkflows = getAllWorkflows().associateBy { it.id }
        val normalizedNewWorkflows = newWorkflows.map(::normalizeWorkflow)
        val newWorkflowIds = normalizedNewWorkflows.map { it.id }.toSet()

        val mergedWorkflows = normalizedNewWorkflows.map { newWorkflow ->
            val existing = existingWorkflows[newWorkflow.id]
            if (existing != null) {
                newWorkflow.copy(folderId = existing.folderId)
            } else {
                newWorkflow
            }
        } + existingWorkflows.values.filter { it.id !in newWorkflowIds }

        prefs.edit().putString("workflow_list", gson.toJson(mergedWorkflows)).apply()
    }

    /**
     * **覆盖式**写入：写完后不在这份列表里的工作流会**永久消失**。
     *
     * ⚠️⚠️ **这是破坏性操作**。本项目的工作流只存在 `SharedPreferences`，
     * **没有版本历史、没有撤销**。调用方（备份恢复的 REPLACE 模式）必须先向用户二次确认。
     *
     * ⚠️ 刻意**不用** `clearAllWorkflows() + saveAllWorkflows()`：那是**两次写**，
     * 中途崩溃 = 工作流全灭且不可恢复。
     *
     * ⚠️ 刻意**不复用** [saveAllWorkflows]：那是**合并**语义（按 id 覆盖、本地独有保留、
     * 且既有条目的 `folderId` 会反向覆盖新值）⇒ 恢复备份时旧工作流会残留、归属也恢复不了。
     *
     * ⚠️ 不做 `aggregateFunctionSignature`（那是编辑器保存路径的职责）：
     * 备份里的 `functionSignature` 已经是聚合过的结果，重算反而可能改变数据。
     * 只做与既有读取路径对称的 [normalizeWorkflow]。
     */
    fun replaceAllWorkflows(list: List<Workflow>) {
        val normalized = list.map(::normalizeWorkflow)
        prefs.edit().putString("workflow_list", gson.toJson(normalized)).apply()
    }

    private fun normalizeWorkflow(workflow: Workflow): Workflow {
        val normalizedContent = WorkflowNormalizer.normalize(
            triggers = workflow.triggers,
            steps = workflow.steps
        )

        return workflow.copy(
            triggers = normalizedContent.triggers,
            steps = normalizedContent.steps
        )
    }

    /**
     * 解析一条工作流记录。
     *
     * ⚠️ 实现**已下移到** [WorkflowJsonCodec]（fork 新增文件）—— 本方法原来
     * 与 `WorkflowJsonImportParser.parseWorkflowObject` 是**逐字重复的两份
     * 25 字段清单**，而「哪一份漏了哪个字段」完全不会被任何测试发现。
     * 三条读路径（读盘 / 文件导入 / 备份恢复）现在共用那一份。
     *
     * ⚠️ 形状判定留在**调用方**（本方法）：`getAllWorkflows` 对「不是对象」的记录
     * 是**跳过并计数**，而导入路径是回落成一个默认工作流 —— 两种处置不同。
     */
    private fun parseWorkflowRecord(element: JsonElement): Workflow {
        val record = element.asJsonObjectOrNull()
            ?: throw IllegalStateException("Workflow record is not a JSON object")
        return WorkflowJsonCodec.parse(record)
    }

    /**
     * 从步骤聚合函数签名（决策 20：签名唯一存 Workflow.functionSignature，卡片是 UI 入口）。
     * 扫描第一步是否为「定义函数」卡片，从 step.parameters["functionParams"]（JSON 字符串）解析 params；
     * 再叠加返回值静态推导（决策 9）。
     */
    private fun aggregateFunctionSignature(steps: List<ActionStep>, existing: FunctionSignature?): FunctionSignature? {
        val defineStep = steps.firstOrNull { it.moduleId == "vflow.logic.define_function" } ?: return existing
        val rawParams = defineStep.parameters["functionParams"] as? String
        val params = if (rawParams != null) {
            try {
                gson.fromJson<List<FunctionParam>>(rawParams, object : TypeToken<List<FunctionParam>>() {}.type) ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
        } else {
            existing?.params ?: emptyList()
        }

        // 返回值不再写入签名（决策 #11）：类型与键改由**现场推导**
        // （FunctionSignatureHelper.deriveReturn），5 处读取点全部走它。
        // ⚠️ 这是**有意的行为变化**：存量 returnDef.keys 会在用户下次保存时被清空，
        //    不做迁移（该字段即将废弃，见设计文档 §5）。
        return FunctionSignature(params = params, returnDef = null)
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }
}
