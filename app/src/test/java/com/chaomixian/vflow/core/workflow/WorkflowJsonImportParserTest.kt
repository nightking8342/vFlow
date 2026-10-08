package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.workflow.model.WorkflowLogLevel
import com.chaomixian.vflow.core.workflow.model.WorkflowReentryBehavior
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkflowJsonImportParserTest {
    private val parser = WorkflowJsonImportParser()

    @Test
    fun `parses backup format with workflows and folders`() {
        val json = """
            {
              "workflows": [
                {
                  "_meta": { "name": "测试工作流" },
                  "id": "workflow-1",
                  "name": "测试工作流",
                  "steps": [
                    {
                      "moduleId": "vflow.trigger.time",
                      "parameters": { "time": "08:00" },
                      "id": "step-1"
                    },
                    {
                      "moduleId": "vflow.action.log",
                      "parameters": {},
                      "id": "step-2"
                    }
                  ],
                  "folderId": "folder-1"
                }
              ],
              "folders": [
                {
                  "id": "folder-1",
                  "name": "默认文件夹"
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json)

        assertEquals(1, parsed.workflows.size)
        assertEquals(1, parsed.folders.size)
        assertEquals("workflow-1", parsed.workflows.first().id)
        assertEquals("默认文件夹", parsed.folders.first().name)
        assertEquals("folder-1", parsed.workflows.first().folderId)
    }

    @Test
    fun `sanitizes missing workflow identity fields`() {
        val json = """
            {
              "name": "",
              "steps": [
                {
                  "moduleId": "vflow.action.log",
                  "parameters": {},
                  "id": "step-1"
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json)
        val workflow = parsed.workflows.single()

        assertTrue(workflow.id.isNotBlank())
        assertEquals("未命名工作流", workflow.name)
        assertEquals("1.0.0", workflow.version)
        assertEquals(1, workflow.vFlowLevel)
        assertNotNull(workflow.tags)
    }

    @Test
    fun `keeps folder id null when source folder is blank`() {
        val json = """
            [
              {
                "id": "workflow-1",
                "name": "测试工作流",
                "folderId": "",
                "steps": [
                  {
                    "moduleId": "vflow.action.log",
                    "parameters": {},
                    "id": "step-1"
                  }
                ]
              }
            ]
        """.trimIndent()

        val parsed = parser.parse(json)

        assertNull(parsed.workflows.single().folderId)
    }

    @Test
    fun `parses repository workflow metadata from meta block and legacy trigger config`() {
        val json = """
            {
              "_meta": {
                "id": "repo-workflow",
                "name": "仓库工作流",
                "version": "2.3.4",
                "vFlowLevel": 3,
                "description": "来自仓库",
                "author": "Repo Author",
                "homepage": "https://example.com/workflow",
                "tags": ["仓库", "测试"]
              },
              "id": "workflow-1",
              "name": "",
              "steps": [
                {
                  "moduleId": "vflow.action.log",
                  "parameters": {},
                  "id": "step-1"
                }
              ],
              "triggerConfig": {
                "type": "vflow.trigger.time",
                "time": "09:30"
              }
            }
        """.trimIndent()

        val parsed = parser.parse(json)
        val workflow = parsed.workflows.single()

        assertEquals("workflow-1", workflow.id)
        assertEquals("仓库工作流", workflow.name)
        assertEquals("2.3.4", workflow.version)
        assertEquals(3, workflow.vFlowLevel)
        assertEquals("来自仓库", workflow.description)
        assertEquals("Repo Author", workflow.author)
        assertEquals("https://example.com/workflow", workflow.homepage)
        assertEquals(listOf("仓库", "测试"), workflow.tags)
        assertEquals(1, workflow.triggers.size)
        assertEquals("vflow.trigger.time", workflow.triggers.single().moduleId)
        assertEquals("09:30", workflow.triggers.single().parameters["time"])
        assertEquals(1, workflow.steps.size)
        assertEquals("vflow.action.log", workflow.steps.single().moduleId)
    }

    @Test
    fun `ignores null string fields in repository workflow object`() {
        val json = """
            {
              "_meta": {
                "name": "仓库工作流"
              },
              "id": "workflow-1",
              "description": null,
              "author": null,
              "homepage": null,
              "folderId": null,
              "steps": [
                {
                  "moduleId": "vflow.action.log",
                  "parameters": null,
                  "id": "step-1"
                }
              ]
            }
        """.trimIndent()

        val parsed = parser.parse(json)
        val workflow = parsed.workflows.single()

        assertEquals("仓库工作流", workflow.name)
        assertEquals("", workflow.description)
        assertEquals("", workflow.author)
        assertEquals("", workflow.homepage)
        assertNull(workflow.folderId)
        assertEquals(emptyMap<String, Any?>(), workflow.steps.single().parameters)
    }

    @Test
    fun `preserves numeric location trigger parameters as numbers`() {
        val json = """
            {
              "id": "location-workflow",
              "name": "位置工作流",
              "triggers": [
                {
                  "moduleId": "vflow.trigger.location",
                  "parameters": {
                    "event": "enter",
                    "latitude": 31.2304,
                    "longitude": 121.4737,
                    "radius": 750
                  },
                  "id": "location-trigger"
                }
              ]
            }
        """.trimIndent()

        val parameters = parser.parse(json).workflows.single().triggers.single().parameters

        assertEquals(31.2304, (parameters["latitude"] as Number).toDouble(), 0.000001)
        assertEquals(121.4737, (parameters["longitude"] as Number).toDouble(), 0.000001)
        assertEquals(750.0, (parameters["radius"] as Number).toDouble(), 0.000001)
    }

    // ── 曾经漏掉的 5 个字段 ────────────────────────────────────────
    //
    // 起因：单文件导出表只写 20 个键，而**导入侧的解析器认得更多**
    // ⇒ 导出再导入后用户的设置被静默重置（超时上限没了、日志等级回到最详细、
    //   重入策略回到默认），函数工作流更是直接退化成普通工作流。
    // 下面几条锁住解析侧确实认得这些键（导出侧由
    // `WorkflowExportFieldCoverageTest` 的源码扫描锁住）。

    @Test
    fun `parses the four execution settings that used to be dropped`() {
        val json = """
            {"id":"w1","name":"n",
             "maxExecutionTime":42,
             "reentryBehavior":"allow_parallel",
             "silentExecution":true,
             "logLevel":"error"}
        """.trimIndent()

        val workflow = parser.parse(json).workflows.single()

        assertEquals(42, workflow.maxExecutionTime)
        assertEquals(WorkflowReentryBehavior.ALLOW_PARALLEL, workflow.reentryBehavior)
        assertTrue(workflow.silentExecution)
        assertEquals(WorkflowLogLevel.ERROR, workflow.logLevel)
    }

    @Test
    fun `a function workflow stays a function workflow after a file round trip`() {
        // ⚠️ 这条曾经是红的（解析器对 functionSignature **零解析**）——
        //    表现是「导入后函数工作流静默变成普通工作流」：能存能跑能显示，
        //    只是「调用函数」步骤再也找不到参数声明。
        val json = """
            {"id":"fn1","name":"函数流","steps":[],
             "functionSignature":{
               "params":[{"name":"who","type":"vflow.type.string","isRequired":true}],
               "returnDef":{"type":"vflow.type.dictionary",
                            "keys":[{"name":"greeting","type":"vflow.type.string"}]}}}
        """.trimIndent()

        val workflow = parser.parse(json).workflows.single()

        assertNotNull("functionSignature 必须被解析，否则函数工作流会静默退化", workflow.functionSignature)
        assertTrue(workflow.isFunction)
        assertEquals("who", workflow.functionSignature!!.params.single().name)
        assertTrue(workflow.functionSignature!!.params.single().isRequired)
        assertEquals("greeting", workflow.functionSignature!!.returnDef!!.keys.single().name)
    }

    @Test
    fun `a missing functionSignature still means a normal workflow`() {
        // 反向锁：不能为了修上一条就把所有工作流都判成函数。
        val workflow = parser.parse("""{"id":"w1","name":"普通流"}""").workflows.single()
        assertNull(workflow.functionSignature)
        assertTrue(!workflow.isFunction)
    }

    @Test
    fun `a malformed functionSignature does not fail the whole import`() {
        // 容错口径：签名的某一段坏掉不该让整条工作流导不进来。
        val json = """
            {"id":"w1","name":"n","functionSignature":"not-an-object"}
        """.trimIndent()

        val workflow = parser.parse(json).workflows.single()

        assertEquals("w1", workflow.id)
        assertNull("不是对象 ⇒ 当普通工作流处理", workflow.functionSignature)
    }

    @Test
    fun `a signature param missing its type is dropped without losing the others`() {
        val json = """
            {"id":"w1","name":"n","functionSignature":{"params":[
               {"name":"good","type":"vflow.type.string"},
               {"name":"no-type"},
               {"type":"vflow.type.number"}
            ]}}
        """.trimIndent()

        val params = parser.parse(json).workflows.single().functionSignature!!.params

        assertEquals("缺 name/type 的条目只丢自己，不整份作废", 1, params.size)
        assertEquals("good", params.single().name)
    }
}
