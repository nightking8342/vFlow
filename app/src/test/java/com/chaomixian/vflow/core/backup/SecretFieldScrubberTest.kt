// 文件: test/java/com/chaomixian/vflow/core/backup/SecretFieldScrubberTest.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [SecretFieldScrubber] 的纯 JVM 单测。
 *
 * ## 本文件的结构与理由
 *
 * 「改了不报错、只**静默变差**」是本任务最重要的失效形态：
 *
 * - `key_code` 被清空 ⇒ **按键模块在某个凌晨静默失灵**，没人会联想到是备份清洗干的。
 * - `device_key` 漏清洗 ⇒ Bark 推送的凭证**明文进了备份文件**，没有任何提示。
 *
 * 故本文件对**每一个**判定过的 id 都有一条正向或反向断言（覆盖方案 §6.4 全表），
 * 而不是只测两个代表。
 */
class SecretFieldScrubberTest {

    // ── 判定规则 ────────────────────────────────────────────

    @Test
    fun `shouldScrub clears the six known credentials`() {
        // 方案 §6.4 全表里判定为「清」的六个
        for (id in listOf("access_token", "bot_token", "api_key", "device_key", "key", "file_token")) {
            assertTrue("$id 应当被清洗", SecretFieldScrubber.shouldScrub(id))
        }
    }

    @Test
    fun `shouldScrub leaves the five excluded ids untouched`() {
        // ⚠️⚠️ 反向断言 —— 这是「改错了不报错」的那一侧。
        //     `key_code` 被清空会让按键模块静默失效；`page_token` 被清空会让
        //     飞书分页静默失效。两者用户都完全无从发现。
        //
        // ⚠️⚠️ **必须对着【写死的期望列表】断言，不能遍历 `SCRUB_EXCLUDED` 自己** ——
        //     两者看起来等价，但遍历自身时，**把排除名单清空会让本条自动变绿**
        //     （它遍历一个空集合，一条违规都找不到）。这正是反证 R4 的改法，
        //     第一版测试**没有变红**，说明它当时什么都没测到。
        //
        // ⚠️ 同时汇总**全部**违规项再断言，而不是循环里逐条 assert ——
        //     后者在第一个违规处就中止，把其余四个做成盲区。
        val expected = listOf("page_token", "key_code", "key_encoding", "key_action", "auth_mode")

        val violations = expected.filter { SecretFieldScrubber.shouldScrub(it) }
        assertTrue(
            "❌ 这些 id 看着像凭证但**不是**，清洗它们会让对应模块静默失效：\n" +
                violations.joinToString("\n") { "  · $it" } +
                "\n（`key_code` ⇒ 按键模块；`page_token` ⇒ 飞书分页；" +
                "`key_encoding`/`key_action`/`auth_mode` ⇒ 各自的参数语义）",
            violations.isEmpty(),
        )

        // 排除名单必须**恰好**是这五个：漏掉任何一个 ⇒ 它会被子串规则误清；
        // 多出一个 ⇒ 有正常参数被误排除（那条参数从此不再被清洗，静默泄漏）。
        assertEquals(
            "SCRUB_EXCLUDED 必须恰好是这五个 —— 漏一个会误清正常参数，多一个会漏清真凭证",
            expected.toSet(),
            SecretFieldScrubber.SCRUB_EXCLUDED,
        )
    }

    @Test
    fun `key is matched exactly while the key_ prefixed ids are not`() {
        // 三段式规则的**核心分界**：`key` 本身是加解密密钥（真凭证），
        // 但作为子串会误伤三个 `key_*`。
        assertTrue("`key` 本身必须被清洗（它是加解密模块的密钥）", SecretFieldScrubber.shouldScrub("key"))
        assertFalse(SecretFieldScrubber.shouldScrub("key_code"))
        assertFalse(SecretFieldScrubber.shouldScrub("key_encoding"))
        assertFalse(SecretFieldScrubber.shouldScrub("key_action"))
    }

    @Test
    fun `substring matching is case insensitive`() {
        assertTrue(SecretFieldScrubber.shouldScrub("API_KEY"))
        assertTrue(SecretFieldScrubber.shouldScrub("Access_Token"))
        assertTrue(SecretFieldScrubber.shouldScrub("PASSWORD"))
    }

    @Test
    fun `ordinary parameters are never matched`() {
        for (id in listOf("base_url", "model", "max_steps", "instruction", "provider", "folderId")) {
            assertFalse("$id 不是凭证", SecretFieldScrubber.shouldScrub(id))
        }
    }

    // ── 清洗：端到端（构造真实的工作流 JSON 形状）──────────

    @Test
    fun `scrub clears api_key and leaves every other parameter byte identical`() {
        val workflows = JsonArray().apply {
            add(workflow(
                steps = listOf(
                    step(
                        "s1",
                        linkedMapOf(
                            "api_key" to "sk-live-123",
                            "base_url" to "https://api.example.com",
                            "model" to "gpt-x",
                            "max_steps" to 12.0,
                        )
                    )
                )
            ))
        }

        val touched = SecretFieldScrubber.scrub(workflows)

        assertEquals(listOf("s1.api_key"), touched)
        val params = paramsOf(workflows, 0, 0)
        assertEquals("", params.get("api_key").asString)
        // 其余逐字原样 —— 不只是「还在」，而是值与类型都没动
        assertEquals("https://api.example.com", params.get("base_url").asString)
        assertEquals("gpt-x", params.get("model").asString)
        assertEquals(12.0, params.get("max_steps").asDouble, 0.0)
    }

    @Test
    fun `a step containing both api_key and key_code scrubs only the former`() {
        // ⚠️ 一条用例同时覆盖两侧 —— 这是最容易写错的地方。
        val workflows = JsonArray().apply {
            add(workflow(
                steps = listOf(
                    step("s1", linkedMapOf("api_key" to "sk-1", "key_code" to "4"))
                )
            ))
        }

        val touched = SecretFieldScrubber.scrub(workflows)

        val params = paramsOf(workflows, 0, 0)
        assertEquals("", params.get("api_key").asString)
        assertEquals(
            "key_code 是按键码，清掉它会让按键模块静默失灵",
            "4", params.get("key_code").asString,
        )
        assertEquals(listOf("s1.api_key"), touched)
    }

    @Test
    fun `credentials inside trigger steps are scrubbed too`() {
        // ⚠️ 遍历范围必须是 triggers + steps（与 Workflow.allSteps 对齐）。
        //     只走 steps 会漏掉触发器上的凭证。
        val workflows = JsonArray().apply {
            add(workflow(
                triggers = listOf(step("t1", linkedMapOf("bot_token" to "123:ABC"))),
                steps = listOf(step("s1", linkedMapOf("device_key" to "barkkey")))
            ))
        }

        val touched = SecretFieldScrubber.scrub(workflows)

        assertEquals("", paramsOf(workflows, 0, 0, isTrigger = true).get("bot_token").asString)
        assertEquals("", paramsOf(workflows, 0, 0).get("device_key").asString)
        assertEquals(listOf("t1.bot_token", "s1.device_key"), touched)
    }

    @Test
    fun `scrub returns the positions of everything it cleared so nothing is silently dropped`() {
        val workflows = JsonArray().apply {
            add(workflow(steps = listOf(
                step("s1", linkedMapOf("api_key" to "a")),
                step("s2", linkedMapOf("access_token" to "b", "key" to "c")),
            )))
        }

        val touched = SecretFieldScrubber.scrub(workflows)

        assertEquals(
            "被清洗的位置必须全部返回 —— 这是「不静默丢弃」的唯一落实方式",
            listOf("s1.api_key", "s2.access_token", "s2.key"),
            touched,
        )
    }

    @Test
    fun `an empty workflow list yields no positions`() {
        assertEquals(emptyList<String>(), SecretFieldScrubber.scrub(JsonArray()))
    }

    @Test
    fun `a workflow without steps or triggers is skipped without crashing`() {
        val workflows = JsonArray().apply {
            add(JsonObject().apply { addProperty("id", "w1") })
            add(JsonObject().apply {
                addProperty("id", "w2")
                add("steps", JsonArray())  // 空步骤列表
            })
            // 形状不对的成员也不该让整次导出崩掉
            add(com.google.gson.JsonPrimitive("not an object"))
        }

        assertEquals(emptyList<String>(), SecretFieldScrubber.scrub(workflows))
    }

    // ── 加密：与清洗对称的那条支路 ──────────────────────────

    @Test
    fun `encryptInPlace turns matching values into envelopes and leaves others alone`() {
        val ctx = SecretContext("pw".toCharArray(), ByteArray(16) { it.toByte() }, 1000)
        val workflows = JsonArray().apply {
            add(workflow(steps = listOf(
                step("s1", linkedMapOf("api_key" to "sk-live", "key_code" to "4", "model" to "m"))
            )))
        }

        val count = SecretFieldScrubber.encryptInPlace(workflows, ctx)

        assertEquals(1, count)
        val params = paramsOf(workflows, 0, 0)
        assertTrue("api_key 应当变成信封", SecretEnvelope.isWrapped(params.get("api_key")))
        assertEquals("sk-live", ctx.openSlot(params.get("api_key")))
        assertEquals("4", params.get("key_code").asString)
        assertEquals("m", params.get("model").asString)
    }

    @Test
    fun `encrypted values do not leak the plaintext anywhere in the tree`() {
        val ctx = SecretContext("pw".toCharArray(), ByteArray(16) { it.toByte() }, 1000)
        val workflows = JsonArray().apply {
            add(workflow(steps = listOf(step("s1", linkedMapOf("api_key" to "sk-THIS-MUST-NOT-APPEAR")))))
        }
        SecretFieldScrubber.encryptInPlace(workflows, ctx)

        assertFalse(
            "加密后整棵树里不得残留明文 —— 否则加密等于没做",
            workflows.toString().contains("sk-THIS-MUST-NOT-APPEAR"),
        )
    }

    @Test
    fun `non string values are skipped by encryptInPlace rather than mangled`() {
        // ⚠️ 字典/列表类型的参数会被写成对象（`{"type":…,"value":…}`），
        //     那不是密文信封的形状；把它包成信封会让模块拿到 map 而不是原类型。
        val ctx = SecretContext("pw".toCharArray(), ByteArray(16) { it.toByte() }, 1000)
        val workflows = JsonArray().apply {
            add(workflow(steps = listOf(
                step("s1", linkedMapOf(
                    "api_key" to JsonObject().apply { addProperty("type", "vflow.type.dictionary") },
                    "token_count" to 7.0,
                ))
            )))
        }

        assertEquals(0, SecretFieldScrubber.encryptInPlace(workflows, ctx))
        val params = paramsOf(workflows, 0, 0)
        assertTrue(params.get("api_key").isJsonObject)
        assertEquals(7.0, params.get("token_count").asDouble, 0.0)
    }

    // ── ⚠️ 反僵尸：排除名单的 id 必须仍存在于代码库 ──────────

    @Test
    fun `every excluded id still exists in the module sources`() {
        // ⚠️⚠️ **为什么必需**：排除名单是**人工维护**的。若某个被排除的 id
        //     随代码演进被删掉/改名，那条排除项就成了**僵尸** ——
        //     它再也拦不住任何东西，但**看起来还在起作用**。
        //     更坏的是：将来有人新增一个同名或相似的 id 时，会**静默命中**
        //     排除名单而漏清，且没有任何报错。
        //
        // ⚠️ **扫源码，不读任何外部清单文件** —— 那份全量清单在主仓库
        //     `.mindfs/` 下，不在本 worktree，单测工作目录是 app 模块根，
        //     相对路径解析不到，写出来会恒红。
        //
        // ⚠️ **必须同时覆盖两种写法**：`key_code` 恰好两种都有
        //     （`CorePressKeyModule.kt` 换行 / `KeyEventTriggerModule.kt` 单行），
        //     只查一种会误判成僵尸。
        val moduleRoot = File("src/main/java/com/chaomixian/vflow/core/workflow/module")
        assertTrue("模块目录不存在：${moduleRoot.absolutePath}", moduleRoot.isDirectory)

        val sources = moduleRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .toList()
        assertTrue("没有扫到模块源码 —— 路径写错了？", sources.size > 50)

        val text = sources.joinToString("\n") { it.readText() }

        for (id in SecretFieldScrubber.SCRUB_EXCLUDED) {
            val inline = Regex("""InputDefinition\(\s*"$id"""")
            val multiline = Regex("""id\s*=\s*"$id"""")
            assertTrue(
                "❌ 排除名单里的 `$id` 在当前代码库里找不到定义了 —— 它成了**僵尸条目**。\n" +
                    "僵尸条目的危害：再也拦不住任何东西，却看起来还在起作用；\n" +
                    "将来有人新增同名/相似的 id 时会**静默命中**它而漏清，且无任何报错。\n" +
                    "处理方式：确认该参数确实已删除/改名后，把它从 SCRUB_EXCLUDED 里去掉。",
                inline.containsMatchIn(text) || multiline.containsMatchIn(text),
            )
        }
    }

    @Test
    fun `the anti zombie scan really does cover both definition styles`() {
        // 防空转：上面那条若正则写错会「永远找不到」而恒红，或「总能找到」而恒绿。
        // 用一个两种写法都存在的 id（key_code）证明两条正则各自都能命中。
        val moduleRoot = File("src/main/java/com/chaomixian/vflow/core/workflow/module")
        val text = moduleRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }

        assertTrue(
            "换行写法 `id = \"key_code\"` 应当能命中",
            Regex("""id\s*=\s*"key_code"""").containsMatchIn(text),
        )
        assertTrue(
            "单行写法 `InputDefinition(\"key_code\"` 应当能命中",
            Regex("""InputDefinition\(\s*"key_code"""").containsMatchIn(text),
        )
    }

    @Test
    fun `every scrubbed id also exists in the module sources`() {
        // 与反僵尸同理，但针对**正向**名单：一个不存在的 id 落在 SCRUB_SUBSTRINGS /
        // SCRUB_EXACT 里说明它是为某个已删模块写的，应当清掉。
        // ⚠️ 子串名单是**规则**（覆盖未来新增的 id），故只检查 SCRUB_EXACT
        //    这一个精确名单 —— 对子串提同样要求是错的。
        val moduleRoot = File("src/main/java/com/chaomixian/vflow/core/workflow/module")
        val text = moduleRoot.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .joinToString("\n") { it.readText() }

        for (id in SecretFieldScrubber.SCRUB_EXACT) {
            assertTrue(
                "精确名单里的 `$id` 在当前代码库里找不到定义 —— 它是为已删模块写的，应清掉",
                Regex("""id\s*=\s*"$id"""").containsMatchIn(text) ||
                    Regex("""InputDefinition\(\s*"$id"""").containsMatchIn(text),
            )
        }
    }

    // ── 夹具 ────────────────────────────────────────────────

    private fun workflow(
        triggers: List<JsonObject> = emptyList(),
        steps: List<JsonObject> = emptyList()
    ): JsonObject = JsonObject().apply {
        addProperty("id", "w1")
        addProperty("name", "test")
        add("triggers", JsonArray().apply { triggers.forEach { add(it) } })
        add("steps", JsonArray().apply { steps.forEach { add(it) } })
    }

    private fun step(id: String, parameters: Map<String, Any?>): JsonObject = JsonObject().apply {
        addProperty("id", id)
        addProperty("moduleId", "vflow.test")
        add("parameters", JsonObject().apply {
            parameters.forEach { (k, v) ->
                when (v) {
                    null -> add(k, com.google.gson.JsonNull.INSTANCE)
                    is Number -> addProperty(k, v)
                    is Boolean -> addProperty(k, v)
                    is String -> addProperty(k, v)
                    is com.google.gson.JsonElement -> add(k, v)
                    else -> addProperty(k, v.toString())
                }
            }
        })
    }

    private fun paramsOf(
        workflows: JsonArray,
        workflowIndex: Int,
        stepIndex: Int,
        isTrigger: Boolean = false
    ): JsonObject {
        val container = if (isTrigger) "triggers" else "steps"
        return workflows[workflowIndex].asJsonObject
            .getAsJsonArray(container)[stepIndex].asJsonObject
            .getAsJsonObject("parameters")
    }
}
