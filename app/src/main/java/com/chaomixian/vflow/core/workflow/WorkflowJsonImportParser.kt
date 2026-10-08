package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowFolder
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.util.UUID

class WorkflowJsonImportParser(
    private val gson: Gson = Gson()
) {
    data class ParsedImport(
        val workflows: List<Workflow>,
        val folders: List<WorkflowFolder> = emptyList()
    )

    fun parse(jsonString: String): ParsedImport {
        val root = JsonParser.parseString(jsonString)
        return when {
            root.isJsonArray -> ParsedImport(
                workflows = parseWorkflowList(root)
            )
            root.isJsonObject -> parseObject(root.asJsonObject)
            else -> ParsedImport(emptyList())
        }
    }

    private fun parseObject(data: JsonObject): ParsedImport {
        return when {
            data.has("folders") && data.has("workflows") -> ParsedImport(
                workflows = parseWorkflowList(data.get("workflows")),
                folders = parseFolderList(data.get("folders"))
            )
            data.has("folder") && data.has("workflows") -> ParsedImport(
                workflows = parseWorkflowList(data.get("workflows")),
                folders = listOf(parseFolder(data.get("folder")))
            )
            data.has("workflows") -> ParsedImport(
                workflows = parseWorkflowList(data.get("workflows"))
            )
            else -> ParsedImport(
                workflows = listOf(parseWorkflow(data))
            )
        }
    }

    private fun parseWorkflowList(rawValue: JsonElement?): List<Workflow> {
        if (rawValue == null || !rawValue.isJsonArray) {
            return emptyList()
        }
        return rawValue.asJsonArray.mapNotNull { item ->
            item.asJsonObjectOrNull()?.let(::parseWorkflowObject)
        }
    }

    private fun parseFolderList(rawValue: JsonElement?): List<WorkflowFolder> {
        if (rawValue == null || !rawValue.isJsonArray) {
            return emptyList()
        }
        return rawValue.asJsonArray.mapNotNull { item ->
            item.asJsonObjectOrNull()?.let(::parseFolderObject)
        }
    }

    /**
     * 解析单条工作流；**不是 JSON 对象 ⇒ 回落成一条空工作流**（而不是丢掉整份文件）。
     *
     * ⚠️ 回落走 `WorkflowJsonCodec.parse(JsonObject())`（全默认值），**不手搓
     * `Workflow(...)`** —— 手搓等于再维护一份字段默认值清单，而那份清单
     * 会在「模型加字段」时过期（过期表现是「这个字段恒为默认值」，
     * 且不会有任何测试发现）。
     */
    private fun parseWorkflow(rawValue: JsonElement): Workflow =
        WorkflowJsonCodec.parseOrNull(rawValue) ?: WorkflowJsonCodec.parse(JsonObject())

    private fun parseFolder(rawValue: JsonElement): WorkflowFolder {
        val folderObject = rawValue.asJsonObjectOrNull()
            ?: return WorkflowFolder(name = "")
        return parseFolderObject(folderObject)
    }

    /**
     * 解析一条工作流记录。
     *
     * ⚠️ 字段映射**已下移到** [WorkflowJsonCodec]（fork 新增文件）—— 本方法原来
     * 与 `WorkflowManager.parseWorkflowRecord` 是**逐字重复的两份 25 字段清单**。
     * 三条读路径（读盘 / 文件导入 / 备份恢复）现在共用那一份，
     * **给 `Workflow` 加字段时只改那里**。
     *
     * ⚠️ 形状判定留在**调用方**（本类）：`parseWorkflow` 对「不是对象」的输入
     * 回落成一个默认工作流，而读盘路径是跳过并计数 —— 两种处置不同。
     */
    private fun parseWorkflowObject(data: JsonObject): Workflow =
        WorkflowJsonCodec.parse(data)

    private fun parseFolderObject(data: JsonObject): WorkflowFolder {
        return WorkflowFolder(
            id = data.getString("id")?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString(),
            name = data.getString("name") ?: "",
            parentId = data.getString("parentId"),
            order = data.getInt("order") ?: 0,
            createdAt = data.getLong("createdAt")?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            modifiedAt = data.getLong("modifiedAt")?.takeIf { it > 0 } ?: System.currentTimeMillis()
        )
    }

    // ── 文件夹读取原语 ────────────────────────────────────────────
    //
    // ⚠️ 工作流的那套已随字段映射一起下移到 `WorkflowJsonCodec`；这里只剩
    //    文件夹需要的几个。文件夹的字段少、且没有第二条读路径，故暂不抽。

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? {
        return if (isJsonObject) asJsonObject else null
    }

    private fun JsonObject.getString(name: String): String? {
        val element = get(name) ?: return null
        if (!element.isJsonPrimitive || !element.asJsonPrimitive.isString) return null
        return element.asString
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
}
