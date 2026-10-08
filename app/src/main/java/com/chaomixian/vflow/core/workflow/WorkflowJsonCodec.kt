// 文件：main/java/com/chaomixian/vflow/core/workflow/WorkflowJsonCodec.kt
// 描述：Workflow ↔ JSON 的**唯一**字段映射实现。这是 fork 新增文件。

package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import java.util.UUID

/**
 * `Workflow` 与 JSON 之间的**字段映射唯一实现**。
 *
 * ## 为什么必须有这一个文件
 *
 * 工作流的 JSON 读路径原本有**三条**，各自手写一遍字段清单：
 *
 * | 路径 | 原实现 |
 * |---|---|
 * | 磁盘读盘 | `WorkflowManager.parseWorkflowRecord` |
 * | 文件导入（单文件 / 分享 / 仓库 / legacy 备份） | `WorkflowJsonImportParser.parseWorkflowObject` |
 * | 备份恢复 | `WorkflowScope.workflowFromJson`（Gson 反射） |
 *
 * 前两条是**逐字重复**的两份 25 字段清单；第三条走反射、**默认值不生效**。
 * 后果是本仓库反复踩的那类**静默失效**：
 *
 * - `functionSignature` 只在**读盘**那一份里被解析 ⇒ 函数工作流经文件导入后
 *   `isFunction` 变 false，**静默退化成普通工作流**（能存能跑能显示，不报错）；
 * - 单文件导出表漏了 5 个字段 ⇒ 导出再导入后用户的设置被**静默重置**；
 * - 备份恢复走反射 ⇒ 缺键时字段是 `null`，要等第一次 `copy()` 才炸。
 *
 * ⇒ 现在三条读路径**全部**走本文件。**给 `Workflow` 加字段时只改这里**
 * （导出侧走 [toExportJson]，自动跟随，见下）。
 *
 * ## 导出侧：反射派生，加字段不用改
 *
 * [toExportJson] 由 `gson.toJsonTree(workflow)` **反射派生**，而不是手写键表 ——
 * 手写的表必然在「模型加了字段」时过期，而过期的表现是**导出少一个键**
 * （导入侧对每个键都有回落 ⇒ 用户拿到的是「设置被静默重置」），
 * 没有任何行为测试会因此变红。
 *
 * ⚠️ 输出形状与改动前**逐字一致**：字段集、字段名、枚举取值都不变
 * （`reentryBehavior` / `logLevel` 靠各自的 wire 名映射写成 `allow_parallel` / `error`，
 * 与旧的手写键表相同）。由 `WorkflowJsonCodecTest` 的往返用例锁住。
 *
 * ⚠️ [EXPORT_EXCLUDED] 是**刻意留的空集**：将来若 `Workflow` 上出现不该给出去的
 * 字段（密钥之类），把它加进这个集合 —— 那比在导出处写 `if` 更难被漏掉。
 *
 * ## 它不管什么
 *
 * **不管密钥清洗**。步骤参数里的 `api_key` 由备份链路的 `SecretFieldScrubber`
 * 负责（按参数 id 的形状规则），那是一条横切关注点、不属于字段映射。
 */

object WorkflowJsonCodec {

    /**
     * 导出时**排除**的字段。
     *
     * ⚠️ 当前为空（`Workflow` 上没有不该给出去的字段）。留这个集合是给将来用的：
     * 出现敏感字段时**加进这里**，而不是在 [toExportJson] 里写条件分支 ——
     * 后者散在逻辑里、容易被下一次重构抹掉。
     */
    val EXPORT_EXCLUDED: Set<String> = emptySet()

    /**
     * 解析一条工作流记录。**任何键缺失都不会抛**，逐字段回落。
     *
     * @param record 工作流记录的 JSON 对象。
     */
    fun parse(record: JsonObject): Workflow {
        // `_meta` 是仓库 / 老导出文件里的旁挂元数据块；缺它就只看顶层键。
        val meta = record.getObject(META)

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
            name = record.getString("name")?.takeIf { it.isNotBlank() }
                ?: meta?.getString("name")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_NAME,
            triggers = normalizedContent.triggers,
            steps = normalizedContent.steps,
            isEnabled = record.getBoolean("isEnabled") ?: true,
            isFavorite = record.getBoolean("isFavorite") ?: false,
            wasEnabledBeforePermissionsLost =
                record.getBoolean("wasEnabledBeforePermissionsLost") ?: false,
            folderId = record.getString("folderId")?.takeIf { it.isNotBlank() },
            order = record.getInt("order") ?: 0,
            shortcutName = record.getString("shortcutName"),
            shortcutIconRes = record.getString("shortcutIconRes"),
            cardIconRes = WorkflowVisuals.normalizeIconResName(record.getString("cardIconRes")),
            cardThemeColor = WorkflowVisuals.normalizeThemeColorHex(record.getString("cardThemeColor")),
            modifiedAt = record.getLong("modifiedAt")?.takeIf { it > 0 } ?: System.currentTimeMillis(),
            version = record.getString("version")?.takeIf { it.isNotBlank() }
                ?: meta?.getString("version")?.takeIf { it.isNotBlank() }
                ?: DEFAULT_VERSION,
            vFlowLevel = record.getInt("vFlowLevel")?.takeIf { it > 0 }
                ?: meta?.getInt("vFlowLevel")?.takeIf { it > 0 }
                ?: DEFAULT_VFLOW_LEVEL,
            description = record.getString("description") ?: meta?.getString("description") ?: "",
            author = record.getString("author") ?: meta?.getString("author") ?: "",
            homepage = record.getString("homepage") ?: meta?.getString("homepage") ?: "",
            tags = record.getStringList("tags") ?: meta?.getStringList("tags") ?: emptyList(),
            maxExecutionTime = record.getInt("maxExecutionTime"),
            reentryBehavior = WorkflowReentryBehavior.fromStoredValue(record.getString("reentryBehavior")),
            // 旧记录没有这个键 → false，即保持既有行为。
            silentExecution = record.getBoolean("silentExecution") ?: false,
            // 旧记录没有这个键 → VERBOSE（= 改动前行为，全量记日志）。
            // ⚠️ 方向刻意是「不丢信息」而不是「更保守」：日志是排障的唯一依据，
            //    多记几条的代价远小于「故障时没有线索」。
            logLevel = WorkflowLogLevel.fromStoredValue(record.getString("logLevel")),
            // 实现见 `WorkflowFunctionSignatureCodec`（它曾只在读盘那条路上被调用）。
            functionSignature = WorkflowFunctionSignatureCodec.parse(record)
        )
    }

    /**
     * 解析一条工作流记录；**不是 JSON 对象 ⇒ null**（调用方自行决定跳过还是报错）。
     *
     * ⚠️ 与 [parse] 的分工：`parse` 假定形状正确、`parseOrNull` 负责形状判定。
     * 两个调用方对「形状不对」的处置不同（读盘跳过并计数、导入回落默认工作流），
     * 故判定留在调用方。
     */
    fun parseOrNull(element: JsonElement): Workflow? =
        if (element.isJsonObject) parse(element.asJsonObject) else null

    /**
     * 导出用的 JSON 对象（**反射派生**，字段集自动跟随 [Workflow]）。
     *
     * ⚠️ 用哪个 `Gson` 由调用方决定，两条路当前**刻意不同**：
     * 单文件导出用裸 `Gson()`（保持既有输出形状），备份用带 `VObjectGsonAdapter` 的
     * 那份（与磁盘写盘同源）。它们只在「参数值里真的存在 `VObject` 实例」时才有区别，
     * 而当前没有任何模块这么写（已 grep 核实）。
     */
    fun toExportJson(gson: Gson, workflow: Workflow): JsonObject {
        val obj = gson.toJsonTree(workflow).asJsonObject
        EXPORT_EXCLUDED.forEach(obj::remove)
        return obj
    }

    /** 仓库 / 老导出文件里的旁挂元数据块。 */
    private const val META = "_meta"

    private const val DEFAULT_NAME = "未命名工作流"
    private const val DEFAULT_VERSION = "1.0.0"
    private const val DEFAULT_VFLOW_LEVEL = 1

    // ── JSON 读取原语 ────────────────────────────────────────────────
    //
    // 一律「类型不符即 null」而不是抛 —— 一个键的类型不对不该让整条工作流
    // 导不进来（`getString` 对数字返回 null 是刻意的：旧文件里 `name` 是数字
    // 时应当回落默认名，而不是把数字当字符串用）。

    private fun JsonObject.getObject(name: String): JsonObject? {
        val element = get(name) ?: return null
        return if (element.isJsonObject) element.asJsonObject else null
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
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            // 没有 moduleId 的步骤无法执行、也无法显示 ⇒ 丢弃（而不是造一个空模块）。
            val moduleId = obj.getString("moduleId") ?: return@mapNotNull null
            ActionStep(
                moduleId = moduleId,
                parameters = obj.getMap("parameters") ?: emptyMap(),
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
