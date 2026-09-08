package com.chaomixian.vflow.core.workflow

import android.content.Context
import com.chaomixian.vflow.core.logging.DebugLogger
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.serialization.VObjectGsonAdapter
import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionReturn
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.core.workflow.model.FunctionSignatureHelper
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
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

    fun saveWorkflow(workflow: Workflow) {
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
            functionSignature = aggregateSignature
        )

        if (index != -1) {
            workflows[index] = workflowToSave
        } else {
            workflows.add(workflowToSave)
        }

        prefs.edit().putString("workflow_list", gson.toJson(workflows)).apply()
        TriggerServiceProxy.notifyWorkflowChanged(context, workflowToSave, oldWorkflow)
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

    private fun parseWorkflowRecord(element: JsonElement): Workflow {
        val record = element.asJsonObjectOrNull()
            ?: throw IllegalStateException("Workflow record is not a JSON object")
        val legacyTriggerConfigs = buildList {
            record.getMapList("triggerConfigs")?.let { addAll(it) }
            record.getMap("triggerConfig")?.let { add(it) }
        }
        val normalizedContent = WorkflowNormalizer.normalize(
            triggers = record.getActionSteps("triggers"),
            steps = record.getActionSteps("steps"),
            legacyTriggerConfigs = legacyTriggerConfigs
        )

        return Workflow(
            id = record.getString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = record.getString("name")?.takeIf { it.isNotBlank() } ?: "未命名工作流",
            triggers = normalizedContent.triggers,
            steps = normalizedContent.steps,
            isEnabled = record.getBoolean("isEnabled") ?: true,
            isFavorite = record.getBoolean("isFavorite") ?: false,
            wasEnabledBeforePermissionsLost = record.getBoolean("wasEnabledBeforePermissionsLost") ?: false,
            folderId = record.getString("folderId"),
            order = record.getInt("order") ?: 0,
            shortcutName = record.getString("shortcutName"),
            shortcutIconRes = record.getString("shortcutIconRes"),
            cardIconRes = WorkflowVisuals.normalizeIconResName(record.getString("cardIconRes")),
            cardThemeColor = WorkflowVisuals.normalizeThemeColorHex(record.getString("cardThemeColor")),
            modifiedAt = record.getLong("modifiedAt")?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            version = record.getString("version")?.takeIf { it.isNotBlank() } ?: "1.0.0",
            vFlowLevel = record.getInt("vFlowLevel")?.takeIf { it > 0 } ?: 1,
            description = record.getString("description") ?: "",
            author = record.getString("author") ?: "",
            homepage = record.getString("homepage") ?: "",
            tags = record.getStringList("tags") ?: emptyList(),
            maxExecutionTime = record.getInt("maxExecutionTime"),
            reentryBehavior = WorkflowReentryBehavior.fromStoredValue(record.getString("reentryBehavior")),
            functionSignature = parseFunctionSignature(record)
        )
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

        // 返回值静态推导
        val derivedReturn = FunctionSignatureHelper.deriveReturnDef(steps)
        val returnDef = derivedReturn ?: existing?.returnDef

        return FunctionSignature(params = params, returnDef = returnDef)
    }

    private fun parseFunctionSignature(record: JsonObject): FunctionSignature? {
        val element = record.get("functionSignature") ?: return null
        if (!element.isJsonObject) return null
        val obj = element.asJsonObject

        // 参数声明
        val params = obj.getAsJsonArraySafe("params")?.mapNotNull { p ->
            val pObj = p.asJsonObjectOrNull() ?: return@mapNotNull null
            val name = pObj.getString("name") ?: return@mapNotNull null
            val type = pObj.getString("type") ?: return@mapNotNull null
            val rawDefault = pObj.get("defaultValue")
            FunctionParam(
                name = name,
                type = type,
                defaultValue = if (rawDefault == null || rawDefault.isJsonNull) null else normalizeJsonElementValue(rawDefault),
                isRequired = pObj.getBoolean("isRequired") ?: false
            )
        } ?: emptyList()

        // 返回值声明（可空）
        val returnDef = obj.getAsJsonObjectSafe("returnDef")?.let { retObj ->
            val retType = retObj.getString("type") ?: com.chaomixian.vflow.core.types.VTypeRegistry.DICTIONARY.id
            val keys = retObj.getAsJsonArraySafe("keys")?.mapNotNull { k ->
                val kObj = k.asJsonObjectOrNull() ?: return@mapNotNull null
                val kName = kObj.getString("name") ?: return@mapNotNull null
                val kType = kObj.getString("type") ?: com.chaomixian.vflow.core.types.VTypeRegistry.ANY.id
                ReturnKey(kName, kType)
            } ?: emptyList()
            FunctionReturn(type = retType, keys = keys)
        }

        return FunctionSignature(params = params, returnDef = returnDef)
    }

    /** 安全读取 JSON 数组（非数组返回 null）。 */
    private fun JsonObject.getAsJsonArraySafe(name: String): JsonArray? {
        val element = get(name) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }

    /** 安全读取 JSON 对象（非对象返回 null）。 */
    private fun JsonObject.getAsJsonObjectSafe(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private fun JsonObject.getString(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
    }

    private fun JsonObject.getBoolean(name: String): Boolean? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asBoolean }.getOrNull()
    }

    private fun JsonObject.getInt(name: String): Int? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asInt }.getOrNull()
    }

    private fun JsonObject.getLong(name: String): Long? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive) return null
        return runCatching { element.asLong }.getOrNull()
    }

    private fun JsonObject.getStringList(name: String): List<String>? {
        val element = get(name) ?: return null
        if (!element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { item ->
            if (item.isJsonPrimitive && item.asJsonPrimitive.isString) item.asString else null
        }
    }

    private fun JsonObject.getActionSteps(name: String): List<ActionStep>? {
        val element = get(name) ?: return null
        if (!element.isJsonArray) return null
        return element.asJsonArray.mapNotNull { item ->
            val obj = item.asJsonObjectOrNull() ?: return@mapNotNull null
            val moduleId = obj.getString("moduleId") ?: return@mapNotNull null
            val parameters = obj.getMap("parameters") ?: emptyMap()
            ActionStep(
                moduleId = moduleId,
                parameters = parameters,
                isDisabled = obj.getBoolean("isDisabled") ?: false,
                indentationLevel = obj.getInt("indentationLevel") ?: 0,
                id = obj.getString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
            )
        }
    }

    private fun JsonObject.getMap(name: String): Map<String, Any?>? {
        val element = get(name) ?: return null
        if (!element.isJsonObject) return null
        return normalizedObjectMap(normalizeJsonElementValue(element))
    }

    private fun JsonObject.getMapList(name: String): List<Map<String, Any?>>? {
        val element = get(name) ?: return null
        if (!element.isJsonArray) return null
        return normalizedObjectMapList(normalizeJsonElementValue(element))
    }
}
