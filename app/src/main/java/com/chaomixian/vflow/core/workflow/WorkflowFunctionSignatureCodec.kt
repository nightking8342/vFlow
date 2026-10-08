// 文件：main/java/com/chaomixian/vflow/core/workflow/WorkflowFunctionSignatureCodec.kt
// 描述：函数签名的**唯一** JSON 解析实现。这是 fork 新增文件。

package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionReturn
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * `functionSignature` 字段的 JSON 解析 —— **工作流有三条读路径，这里必须是它们共用的那一份**。
 *
 * ## 为什么必须抽出来（而不是各写一份）
 *
 * 工作流的 JSON 读路径有三条：`WorkflowManager` 读盘、`WorkflowJsonImportParser`
 * 导入、`WorkflowScope` 备份恢复。前两条原本各自实现了一遍字段解析
 * （`WorkflowManager.parseWorkflowRecord` / `WorkflowJsonImportParser.parseWorkflowObject`），
 * 于是 **`functionSignature` 只在读盘那条路上被解析**：
 *
 * | 路径 | 修这个 bug 之前 |
 * |---|---|
 * | `WorkflowManager` 读盘 | ✅ 有 `parseFunctionSignature` |
 * | `WorkflowJsonImportParser`（单文件导入 / 分享导入 / 仓库导入 / legacy 备份） | ❌ **零解析** |
 * | `WorkflowScope`（新格式备份） | ✅ 整对象 Gson（不走这里） |
 *
 * 后果是**函数工作流经单文件往返后静默退化成普通工作流** —— `Workflow.isFunction`
 * 由 `functionSignature != null` 派生，而普通工作流照样能存、能跑、能显示，
 * 只是「调用函数」步骤再也找不到它的参数声明。这正是本仓库反复记的那类
 * 「改错了不报错、只静默变差」。
 *
 * ⇒ 三处共用本文件。**新增函数签名字段时只改这里**，不要在任何读路径里另写一份。
 *
 * ## 容错口径
 *
 * 逐字段 `?:` 回落，**任何一段坏掉都不影响其它段**（`params` 里单条缺 `name`/`type`
 * 只丢那一条，不整份作废）；`functionSignature` 整体不是对象 ⇒ 返回 `null`
 * （= 普通工作流，与字段缺失同义）。
 */
internal object WorkflowFunctionSignatureCodec {

    /**
     * @param record 工作流记录的原始 JSON 对象。
     * @return 解析出的签名；无该键 / 不是对象 ⇒ `null`（普通工作流）。
     */
    fun parse(record: JsonObject): FunctionSignature? {
        val element = record.get(KEY) ?: return null
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
                defaultValue = if (rawDefault == null || rawDefault.isJsonNull) {
                    null
                } else {
                    normalizeJsonElementValue(rawDefault)
                },
                isRequired = pObj.getBoolean("isRequired") ?: false
            )
        } ?: emptyList()

        // 返回值声明（可空）
        val returnDef = obj.getAsJsonObjectSafe("returnDef")?.let { retObj ->
            val retType = retObj.getString("type") ?: VTypeRegistry.DICTIONARY.id
            val keys = retObj.getAsJsonArraySafe("keys")?.mapNotNull { k ->
                val kObj = k.asJsonObjectOrNull() ?: return@mapNotNull null
                val kName = kObj.getString("name") ?: return@mapNotNull null
                val kType = kObj.getString("type") ?: VTypeRegistry.ANY.id
                ReturnKey(kName, kType)
            } ?: emptyList()
            FunctionReturn(type = retType, keys = keys)
        }

        return FunctionSignature(params = params, returnDef = returnDef)
    }

    /** 与 `Workflow.functionSignature` 的 wire 名逐字一致。 */
    const val KEY = "functionSignature"

    private fun JsonObject.getAsJsonArraySafe(name: String): JsonArray? {
        val element = get(name) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }

    private fun JsonObject.getAsJsonObjectSafe(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
    }

    private fun JsonElement.asJsonObjectOrNull(): JsonObject? =
        if (isJsonObject) asJsonObject else null

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
}
