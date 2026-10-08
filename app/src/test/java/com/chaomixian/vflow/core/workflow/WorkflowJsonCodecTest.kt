// 文件: test/java/com/chaomixian/vflow/core/workflow/WorkflowJsonCodecTest.kt
package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.FunctionParam
import com.chaomixian.vflow.core.workflow.model.FunctionReturn
import com.chaomixian.vflow.core.workflow.model.FunctionSignature
import com.chaomixian.vflow.core.workflow.model.ReturnKey
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WorkflowJsonCodec] 的回归测试 —— **三条读路径共用的那一份字段映射**。
 *
 * ## 为什么必须有
 *
 * 本文件合并前，「字段清单」在三处各有一份（读盘 / 文件导入 / 备份恢复），
 * 而**哪一份漏了哪个字段**完全不会被任何测试发现 —— 表现全是静默的：
 * 函数工作流退化成普通流、导出再导入后设置被重置、恢复旧备份直接崩。
 * 这类缺陷**只能**靠「往返一致 + 逐字段断言」来拦。
 */
class WorkflowJsonCodecTest {

    private val gson = GsonBuilder().create()

    private fun parse(json: String): Workflow =
        WorkflowJsonCodec.parse(JsonParser.parseString(json).asJsonObject)

    /** 一条「全字段」的工作流，用于往返断言。 */
    private fun fullWorkflow() = Workflow(
        id = "w1",
        name = "全字段",
        triggers = emptyList(),
        steps = emptyList(),
        isEnabled = false,
        isFavorite = true,
        wasEnabledBeforePermissionsLost = true,
        folderId = "f1",
        order = 7,
        shortcutName = "sn",
        shortcutIconRes = "si",
        cardIconRes = "rounded_home_24",
        cardThemeColor = "#112233",
        modifiedAt = 1700000000000L,
        version = "2.0.0",
        vFlowLevel = 3,
        description = "d",
        author = "a",
        homepage = "https://e.com",
        tags = listOf("x"),
        maxExecutionTime = 42,
        reentryBehavior = WorkflowReentryBehavior.ALLOW_PARALLEL,
        silentExecution = true,
        logLevel = WorkflowLogLevel.ERROR,
        functionSignature = FunctionSignature(
            params = listOf(FunctionParam(name = "p1", type = "vflow.type.string", isRequired = true)),
            returnDef = FunctionReturn(keys = listOf(ReturnKey("k", "vflow.type.number")))
        )
    )

    // ── 往返 ────────────────────────────────────────────────────────

    @Test
    fun `a full workflow survives an export-import round trip`() {
        val original = fullWorkflow()
        val restored = WorkflowJsonCodec.parse(WorkflowJsonCodec.toExportJson(gson, original))

        assertEquals(original.id, restored.id)
        assertEquals(original.name, restored.name)
        assertEquals(original.isEnabled, restored.isEnabled)
        assertEquals(original.isFavorite, restored.isFavorite)
        assertEquals(original.wasEnabledBeforePermissionsLost, restored.wasEnabledBeforePermissionsLost)
        assertEquals(original.folderId, restored.folderId)
        assertEquals(original.order, restored.order)
        assertEquals(original.shortcutName, restored.shortcutName)
        assertEquals(original.shortcutIconRes, restored.shortcutIconRes)
        assertEquals(original.cardIconRes, restored.cardIconRes)
        assertEquals(original.cardThemeColor, restored.cardThemeColor)
        assertEquals(original.modifiedAt, restored.modifiedAt)
        assertEquals(original.version, restored.version)
        assertEquals(original.vFlowLevel, restored.vFlowLevel)
        assertEquals(original.description, restored.description)
        assertEquals(original.author, restored.author)
        assertEquals(original.homepage, restored.homepage)
        assertEquals(original.tags, restored.tags)
        assertEquals(original.maxExecutionTime, restored.maxExecutionTime)
        assertEquals(original.reentryBehavior, restored.reentryBehavior)
        assertEquals(original.silentExecution, restored.silentExecution)
        assertEquals(original.logLevel, restored.logLevel)
        assertNotNull("函数签名必须往返 —— 丢了会把函数工作流退化成普通流", restored.functionSignature)
        assertEquals("p1", restored.functionSignature!!.params.single().name)
        assertTrue(restored.functionSignature!!.params.single().isRequired)
        assertEquals("k", restored.functionSignature!!.returnDef!!.keys.single().name)
        assertTrue(restored.isFunction)
    }

    /**
     * ⚠️⚠️ **导出侧是反射派生的**（`gson.toJsonTree`）⇒ 加字段自动跟随。
     * 这条用例锁住这个性质：模型上的字段名必须**逐个**出现在导出 JSON 里。
     *
     * 它是「以后加字段不用改导入导出代码」这句话的机器化保证 ——
     * 若哪天有人把 [WorkflowJsonCodec.toExportJson] 改成手写键表，这条会变红。
     */
    @Test
    fun `the export shape follows the model automatically`() {
        val obj = WorkflowJsonCodec.toExportJson(gson, fullWorkflow())
        val modelFields = Workflow::class.java.declaredFields
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }

        val missing = modelFields.filter { !obj.has(it) && it !in WorkflowJsonCodec.EXPORT_EXCLUDED }
        assertTrue(
            "导出 JSON 缺字段：$missing —— 导出侧应是反射派生的（`gson.toJsonTree`），" +
                "若这里变红说明有人改成了手写键表（那必然会在加字段时过期）",
            missing.isEmpty()
        )
    }

    // ── 缺键回落：逐条与另外两条读路径的历史口径对齐 ──────────────

    @Test
    fun `a record with only an id falls back to every documented default`() {
        val w = parse("""{"id":"w1"}""")

        assertEquals("w1", w.id)
        assertEquals("未命名工作流", w.name)
        // ⚠️ `triggers` **不是**空 —— `WorkflowNormalizer` 会补一个手动触发器
        //    （`ensureTrigger` 默认 true）。这是三条读路径共同的行为。
        assertEquals(1, w.triggers.size)
        assertEquals(0, w.steps.size)
        assertEquals(true, w.isEnabled)
        assertEquals(false, w.isFavorite)
        assertEquals(false, w.wasEnabledBeforePermissionsLost)
        assertNull(w.folderId)
        assertEquals(0, w.order)
        assertNull(w.shortcutName)
        assertNull(w.shortcutIconRes)
        assertEquals("rounded_layers_fill_24", w.cardIconRes)
        assertEquals("#5B8CFF", w.cardThemeColor)
        assertEquals("1.0.0", w.version)
        assertEquals(1, w.vFlowLevel)
        assertEquals("", w.description)
        assertEquals("", w.author)
        assertEquals("", w.homepage)
        assertEquals(emptyList<String>(), w.tags)
        assertNull(w.maxExecutionTime)
        assertEquals(WorkflowReentryBehavior.BLOCK_NEW, w.reentryBehavior)
        assertEquals(false, w.silentExecution)
        assertEquals(WorkflowLogLevel.VERBOSE, w.logLevel)
        assertNull(w.functionSignature)
        assertTrue("缺 triggers ⇒ 归一化会补一个手动触发器", w.hasManualTrigger())
    }

    @Test
    fun `_meta supplies values when the top level key is absent`() {
        val w = parse(
            """{"_meta":{"name":"来自 meta","version":"9.9.9","vFlowLevel":5,
                       "description":"md","author":"ma","homepage":"mh","tags":["mt"]}}"""
        )

        assertEquals("来自 meta", w.name)
        assertEquals("9.9.9", w.version)
        assertEquals(5, w.vFlowLevel)
        assertEquals("md", w.description)
        assertEquals("ma", w.author)
        assertEquals("mh", w.homepage)
        assertEquals(listOf("mt"), w.tags)
    }

    @Test
    fun `a top level value wins over _meta`() {
        val w = parse("""{"name":"顶层","_meta":{"name":"meta"}}""")
        assertEquals("顶层", w.name)
    }

    @Test
    fun `legacy triggerConfig and triggerConfigs still become triggers`() {
        val w = parse(
            """{"id":"w1","triggerConfig":{"type":"vflow.trigger.time","time":"08:00"}}"""
        )
        assertTrue("legacy triggerConfig 必须被认成触发器", w.hasTriggerType("vflow.trigger.time"))
    }

    @Test
    fun `a step without moduleId is dropped not turned into an empty module`() {
        val w = parse(
            """{"id":"w1","steps":[
                 {"moduleId":"vflow.logic.wait","parameters":{"ms":100},"id":"s1"},
                 {"parameters":{},"id":"broken"}
               ]}"""
        )
        assertEquals(1, w.steps.size)
        assertEquals("s1", w.steps.single().id)
    }

    @Test
    fun `a step missing an id gets a generated one`() {
        val w = parse("""{"id":"w1","steps":[{"moduleId":"vflow.logic.wait"}]}""")
        assertTrue("步骤缺 id 必须补一个，否则列表操作会撞 id", w.steps.single().id.isNotBlank())
    }

    @Test
    fun `a wrong typed key falls back instead of throwing`() {
        // `name` 是数字：应当回落默认名，而不是把数字当字符串用。
        val w = parse("""{"id":"w1","name":123,"tags":"not-a-list","order":"not-a-number"}""")
        assertEquals("未命名工作流", w.name)
        assertEquals(emptyList<String>(), w.tags)
        assertEquals(0, w.order)
    }

    @Test
    fun `a blank id is regenerated`() {
        val w = parse("""{"id":"   ","name":"n"}""")
        assertTrue("空白 id 必须重新生成", w.id.isNotBlank())
    }

    @Test
    fun `a blank name falls back to the placeholder`() {
        assertEquals("未命名工作流", parse("""{"id":"w1","name":"  "}""").name)
    }

    @Test
    fun `a blank folderId becomes null not an empty string`() {
        assertNull(parse("""{"id":"w1","folderId":"  "}""").folderId)
    }

    @Test
    fun `an unknown enum value falls back`() {
        val w = parse(
            """{"id":"w1","reentryBehavior":"nonsense","logLevel":"nonsense"}"""
        )
        assertEquals(WorkflowReentryBehavior.BLOCK_NEW, w.reentryBehavior)
        assertEquals(WorkflowLogLevel.VERBOSE, w.logLevel)
    }

    // ── 导出形状 ────────────────────────────────────────────────────

    @Test
    fun `the exported json keeps the historic wire form of the enums`() {
        // ⚠️ 这条锁的是**兼容性**：导出形状必须与旧的手写键表逐字一致，
        //    否则用新版本导出的文件在旧版本里读不出正确档位。
        val obj = WorkflowJsonCodec.toExportJson(gson, fullWorkflow())

        assertEquals(
            "reentryBehavior 写 storedValue（不是枚举名）",
            "allow_parallel", obj.get("reentryBehavior").asString
        )
        assertEquals(
            "logLevel 写枚举名（WorkflowLogLevel 没有 @SerializedName）",
            "ERROR", obj.get("logLevel").asString
        )
    }

    @Test
    fun `null valued keys are omitted, matching the historic export shape`() {
        // ⚠️ 这条锁的是**形状兼容**，不是「我觉得应该怎样」。
        //
        //    Gson 默认 `serializeNulls = false` ⇒ **值为 null 的键整个不写**。
        //    旧的手写键表虽然显式列了 `"folderId" to workflow.folderId`，但那条
        //    经过 `gson.toJson(map)` 时同样被省略 ⇒ 新旧两种写法**输出逐字相同**。
        //    （若哪天有人给这个 Gson 开了 `serializeNulls`，导出形状会变，
        //     这条会变红 —— 那时要评估旧版本还能不能读。）
        val obj = WorkflowJsonCodec.toExportJson(gson, Workflow(id = "w1", name = "n"))
        assertTrue("null 的 folderId 不写键（与改动前一致）", !obj.has("folderId"))
        assertTrue("普通工作流的 functionSignature 不写键", !obj.has("functionSignature"))
        assertTrue("null 的 shortcutName 不写键", !obj.has("shortcutName"))
    }

    @Test
    fun `a custom icon path survives parsing and re-export`() {
        // ⚠️ 反向锁：图标/主题色的归一化**只对非法值回落默认**，
        //    自定义图片路径必须原样保留。
        //    抹掉路径会把「图标文件没备份」这个**可解释的缺口**
        //    （见 docs/fork/backup-webdav-design.md §8.2）变成「路径也没了」这种
        //    更难查的问题 —— 而且后者在**同设备恢复**时本来是不该发生的。
        val path = "/data/user/0/com.chaomixian.vflow/files/card_icons/x.png"
        val w = parse("""{"id":"w1","cardIconRes":"$path","cardThemeColor":"#AABBCC"}""")

        assertEquals(path, w.cardIconRes)
        assertEquals("#AABBCC", w.cardThemeColor)
        assertEquals(path, WorkflowJsonCodec.toExportJson(gson, w).get("cardIconRes").asString)
    }

    @Test
    fun `parseOrNull rejects a non-object element`() {
        assertNull(WorkflowJsonCodec.parseOrNull(JsonParser.parseString("[1,2]")))
        assertNull(WorkflowJsonCodec.parseOrNull(JsonParser.parseString("\"str\"")))
        assertNotNull(WorkflowJsonCodec.parseOrNull(JsonObject()))
    }
}
