// 文件: test/java/com/chaomixian/vflow/core/backup/BackupPipelineTest.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.workflow.model.ActionStep
import com.chaomixian.vflow.core.workflow.model.Workflow
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [BackupPipeline] 的端到端单测（纯 JVM）。
 *
 * ## 本文件承担的两类不同职责
 *
 * 1. **行为**：导出 → 落文本 → 读回 → 导入，覆盖无口令（清洗）、有口令（加密）、
 *    口令错、篡改、缺口令等分支。
 * 2. **接线锚定**（文件末尾两条源码扫描）：T1 的 `BackupWiringTest` 按父会话决断 1
 *    没有复制到本 worktree，故 T2 需要的两条接线锚定落在这里 —— 形态照该文件的
 *    既有写法与仓库既有的 `CapabilityContractPurityTest`。
 *
 * ## 为什么需要源码扫描（不是重复劳动）
 *
 * 本仓库反复记录的反模式：**纯函数单测全绿，但调用点缺失**
 * （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor`
 * 零生产调用点）。`WorkflowScope.export` 有没有真的调清洗器、
 * `AndroidBackupEnvironment` 有没有真的实现 `secretStore`，都是**测不出来**的。
 */
class BackupPipelineTest {

    private val passphrase = "hunter2".toCharArray()

    private fun ctx() = SecretContext(passphrase, ByteArray(16) { it.toByte() }, 1000)

    private fun workflowWithApiKey(id: String, apiKey: String = "sk-live-123"): Workflow = Workflow(
        id = id,
        name = "wf-$id",
        steps = listOf(
            ActionStep(
                moduleId = "vflow.interaction.agent",
                parameters = linkedMapOf(
                    "api_key" to apiKey,
                    "base_url" to "https://api.example.com",
                    "max_steps" to 8.0,
                ),
                id = "s1"
            )
        )
    )

    private fun envWith(workflows: List<Workflow> = emptyList()): FakeBackupEnvironment =
        FakeBackupEnvironment(workflows = workflows)

    // ── 无口令：清洗支 ──────────────────────────────────────

    @Test
    fun `export without a passphrase scrubs credentials and reports the positions`() {
        val env = envWith(listOf(workflowWithApiKey("w1")))

        val result = BackupPipeline.export(env, setOf("workflows"), secrets = null)

        assertEquals(
            "被清洗的步骤位置必须上报 —— 这是「不静默丢弃」的唯一落实方式",
            listOf("s1.api_key"),
            result.scrubbedFields,
        )
        val root = JsonParser.parseString(result.text).asJsonObject
        val summary = root.getAsJsonObject("summary")
        val scrubbed = summary.getAsJsonArray("scrubbedFields").map { it.asString }
        assertEquals(
            "summary.scrubbedFields 里必须有那个步骤 id —— 否则用户永远不会知道密钥被抹掉了",
            listOf("s1.api_key"),
            scrubbed,
        )
    }

    @Test
    fun `the exported text contains no plaintext api key when no passphrase is given`() {
        val env = envWith(listOf(workflowWithApiKey("w1", "sk-MUST-NOT-APPEAR")))
        val result = BackupPipeline.export(env, setOf("workflows"), secrets = null)

        assertFalse(
            "无口令导出时，明文密钥不得出现在备份文本里",
            result.text.contains("sk-MUST-NOT-APPEAR"),
        )
        // 但其余参数必须在（清洗不是把整个参数表抹掉）
        assertTrue(result.text.contains("https://api.example.com"))
    }

    @Test
    fun `an envelope without secrets has a null encryption section`() {
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), secrets = null).text

        val root = JsonParser.parseString(text).asJsonObject
        assertTrue("encryption 键必须存在", root.has("encryption"))
        assertTrue("无口令时该键必须是 null", root.get("encryption").isJsonNull)
    }

    // ── 有口令：加密支 ──────────────────────────────────────

    @Test
    fun `export with a passphrase encrypts step credentials and leaves nothing in the clear`() {
        val env = envWith(listOf(workflowWithApiKey("w1", "sk-SECRET-VALUE")))

        val result = BackupPipeline.export(
            env, setOf("workflows", "secrets"), ctx()
        )

        assertFalse(
            "有口令时明文密钥不得出现在备份文本里",
            result.text.contains("sk-SECRET-VALUE"),
        )
        assertTrue(
            "加密不算丢失信息 ⇒ 不该报清洗位置",
            result.scrubbedFields.isEmpty(),
        )
        val root = JsonParser.parseString(result.text).asJsonObject
        val encryption = root.getAsJsonObject("encryption")
        assertNotNull("有密文就必须有加密段", encryption)
        assertEquals(1000, encryption.get("iterations").asInt)
        assertTrue(encryption.has("salt"))
        assertTrue(encryption.has("verifier"))
        assertTrue("verifierHash 必须写进信封", encryption.has("verifierHash"))
    }

    @Test
    fun `an encrypted backup round trips through export then import`() {
        val env = envWith(listOf(workflowWithApiKey("w1", "sk-round-trip")))
        val text = BackupPipeline.export(env, setOf("workflows"), ctx()).text

        val target = envWith()
        val outcome = BackupPipeline.import(target, text, ImportMode.REPLACE, ctx())

        assertTrue("应当导入成功，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
        val imported = target.workflowStore.single()
        val params = imported.steps.single().parameters
        assertEquals(
            "加密字段必须被解回原字符串（不是 map，也不是密文）",
            "sk-round-trip",
            params["api_key"],
        )
        assertEquals("https://api.example.com", params["base_url"])
    }

    @Test
    fun `a secret payload round trips so the store is restored`() {
        val env = FakeBackupEnvironment()
        env.secretStore.putString("ai_config", "api_key", "sk-pref")

        val text = BackupPipeline.export(env, setOf("secrets"), ctx()).text
        val target = FakeBackupEnvironment()
        val outcome = BackupPipeline.import(target, text, ImportMode.MERGE, ctx())

        assertTrue("应当导入成功，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
        assertEquals(
            "解密必须递归到 payload 顶层 ⇒ 值落回原槽",
            "sk-pref",
            target.secretStore.getString("ai_config", "api_key"),
        )
    }

    @Test
    fun `a credential whose value looks like JSON stays a string`() {
        // ⚠️⚠️ 这条是 `decryptPayload` 那条**不对称判据**的区分性用例。
        //
        // 背景：同一个 `$enc` 形状承载两种明文 —— `secrets` scope 的整个 payload
        // 装的是 **JSON 对象文本**，而步骤参数里的 api_key 装的是**裸字符串**。
        // 若统一按「解出来的一定是 JSON」处理，一个**值恰好长得像 JSON** 的凭证
        // 就会被静默改类型：字符串 `{"nested":true}` 变成对象、`12345` 变成数字。
        // 模块拿到的就不是字符串，**行为错乱且零报错**（方案 §6.7）。
        //
        // ⚠️ **必须用「长得像 JSON」的值**才测得出来。第一版用的是 `sk-both`，
        // 而 Gson 的宽松模式会把裸字符串原样解析回去 ⇒ 改坏了也不变红
        // （R9 实测：去掉不对称判据后测试全绿）。
        for (jsonish in listOf("""{"nested":true}""", "12345", "true", "[1,2]")) {
            val env = envWith(listOf(workflowWithApiKey("w1", jsonish)))
            val text = BackupPipeline.export(env, setOf("workflows"), ctx()).text

            val target = envWith()
            val outcome = BackupPipeline.import(target, text, ImportMode.REPLACE, ctx())
            assertTrue("应当导入成功，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)

            val value = target.workflowStore.single().steps.single().parameters["api_key"]
            assertEquals(
                "值 `$jsonish` 必须以**字符串**形态还原 —— " +
                    "被解析成对象/数字会让模块拿到错误的类型，且没有任何报错",
                jsonish,
                value,
            )
            assertTrue("必须是 String 而不是 map/number，实际是 ${value?.javaClass}", value is String)
        }
    }

    @Test
    fun `both the whole payload and the per field style decrypt in the same run`() {
        // ⚠️⚠️ 这条覆盖 `decryptPayload` 的**不对称判据**：
        //     - `secrets` scope 的 data 整个是一个信封，明文是 **JSON 对象**
        //     - 工作流步骤里的 api_key 是一个信封，明文是 **裸字符串**
        //     若两者按同一规则处理，裸字符串那侧会被 `parseString` 判成
        //     「解密后的内容不是合法 JSON」⇒ 报**数据损坏**，
        //     于是一个完好的备份被拒绝导入。
        val env = envWith(listOf(workflowWithApiKey("w1", "sk-both")))
        env.secretStore.putString("ai_config", "api_key", "sk-pref")

        val text = BackupPipeline.export(env, setOf("workflows", "secrets"), ctx()).text
        val target = FakeBackupEnvironment()
        val outcome = BackupPipeline.import(target, text, ImportMode.MERGE, ctx())

        assertTrue("两种明文形态并存时必须全部解开，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
        assertEquals("sk-both", target.workflowStore.single().steps.single().parameters["api_key"])
        assertEquals("sk-pref", target.secretStore.getString("ai_config", "api_key"))
    }

    // ── 口令错 / 篡改 ──────────────────────────────────────

    @Test
    fun `a wrong passphrase yields WrongPassphrase and imports nothing`() {
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), ctx()).text

        val target = envWith()
        val outcome = BackupPipeline.import(
            target, text, ImportMode.REPLACE, SecretContext("wrong".toCharArray(), ByteArray(16), 1000)
        )

        assertEquals(
            "口令错必须给出专门的结论 —— 它该让用户重输口令，而不是去修文件",
            BackupPipeline.ImportOutcome.WrongPassphrase,
            outcome,
        )
        assertTrue("口令错时不得写入任何数据", target.workflowStore.isEmpty())
    }

    @Test
    fun `a single flipped ciphertext byte yields Corrupted not WrongPassphrase`() {
        val env = envWith(listOf(workflowWithApiKey("w1", "sk-tamper-me")))
        val text = BackupPipeline.export(env, setOf("workflows"), ctx()).text

        val tampered = flipOneCiphertextChar(text)
        val outcome = BackupPipeline.import(envWith(), tampered, ImportMode.REPLACE, ctx())

        assertEquals(
            "篡改密文必须判 Corrupted —— 判成口令错会让用户反复重输一个本来就对的口令",
            BackupPipeline.ImportOutcome.Corrupted::class.java,
            outcome.javaClass,
        )
    }

    @Test
    fun `a tampered verifier yields Corrupted`() {
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), ctx()).text

        val root = JsonParser.parseString(text).asJsonObject
        val encryption = root.getAsJsonObject("encryption")
        val verifier = encryption.get("verifier").asString
        // 改一个 base64 字符（保持长度合法，内容不同）
        encryption.addProperty("verifier", flipBase64Char(verifier))

        val outcome = BackupPipeline.import(envWith(), root.toString(), ImportMode.REPLACE, ctx())
        assertTrue(
            "verifier 被篡改必须判损坏，实际：$outcome",
            outcome is BackupPipeline.ImportOutcome.Corrupted,
        )
    }

    @Test
    fun `a backup with ciphertext but no encryption section is Corrupted`() {
        // 信封被改过、或导出实现有 bug 漏写该段 ⇒ 若不拦，
        // $enc 对象会原样落进 parameters（模块拿到 map 而不是字符串）。
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), ctx()).text

        val root = JsonParser.parseString(text).asJsonObject
        root.add("encryption", com.google.gson.JsonNull.INSTANCE)

        val outcome = BackupPipeline.import(envWith(), root.toString(), ImportMode.REPLACE, ctx())
        assertTrue(
            "含密文却缺加密段必须判损坏，实际：$outcome",
            outcome is BackupPipeline.ImportOutcome.Corrupted,
        )
    }

    @Test
    fun `a missing passphrase on an encrypted backup imports the plain scopes and reports every skipped one`() {
        // ⚠️ 有口令导出时，**含凭证的工作流也会被加密**（api_key 就地变成 $enc 节点）
        //     ⇒ 缺口令时它同样会被跳过。这不是缺陷，而是「数据本来就是密文」的
        //     必然结果 —— 本用例把它明确**锁住**，免得将来有人以为
        //     「应该只跳过 secrets」而把 workflows 的密文当明文导进去。
        val env = envWith(listOf(workflowWithApiKey("w1")))
        env.secretStore.putString("ai_config", "api_key", "sk-pref")
        val text = BackupPipeline.export(env, setOf("workflows", "secrets"), ctx()).text

        val target = envWith()
        val outcome = BackupPipeline.import(target, text, ImportMode.REPLACE, secrets = null)

        assertTrue("缺口令是**部分成功**，不是失败：$outcome", outcome is BackupPipeline.ImportOutcome.PassphraseRequired)
        val required = outcome as BackupPipeline.ImportOutcome.PassphraseRequired
        assertEquals(
            "被跳过的 scope 必须全部明确列出（不静默）",
            listOf("secrets", "workflows"),
            required.skippedScopes.sorted(),
        )
        assertTrue("加密的 scope 在缺口令时不得写入", target.workflowStore.isEmpty())
        assertNull(
            "加密的 scope 在缺口令时不得写入",
            target.secretStore.getString("ai_config", "api_key"),
        )
    }

    @Test
    fun `an encrypted backup still restores its plain scopes when the passphrase is omitted`() {
        // 上一条的对照：**不带密钥**的导出（无口令 ⇒ 清洗）里没有密文，
        // 因此缺口令导入时一切都照常。这正是「用户只想恢复工作流」那条路。
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), secrets = null).text

        val target = envWith()
        val outcome = BackupPipeline.import(target, text, ImportMode.REPLACE, secrets = null)

        assertTrue("不含密文的备份无需口令即可导入，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
        assertEquals(1, target.workflowStore.size)
        assertEquals(
            "凭证应当是被**清空**（而不是原样保留）—— 备份里本来就没有它",
            "",
            target.workflowStore.single().steps.single().parameters["api_key"],
        )
    }

    // ── 兼容性与边界 ────────────────────────────────────────

    @Test
    fun `a legacy backup is adapted and never asks for a passphrase`() {
        // ⚠️ 方案 §6.10：legacy 备份里**根本不含密文**。对它跑 verifier
        //     会给一份好端端的旧备份报「口令错误」。
        val legacy = """
            {"workflows":[{"id":"legacy-1","name":"旧工作流","steps":[
              {"moduleId":"vflow.core.shell_command","parameters":{"command":"echo hi"},"id":"s1"}]}]}
        """.trimIndent()

        val target = envWith()
        val outcome = BackupPipeline.import(target, legacy, ImportMode.REPLACE, secrets = null)

        assertTrue("旧备份应当能导入，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
        assertEquals(1, target.workflowStore.size)
        assertEquals("legacy-1", target.workflowStore.single().id)
    }

    @Test
    fun `an unknown scope id in the envelope does not crash the import`() {
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), secrets = null).text

        val root = JsonParser.parseString(text).asJsonObject
        root.getAsJsonObject("scopes").add(
            "brand_new_scope",
            JsonObject().apply { addProperty("count", 1); add("data", com.google.gson.JsonArray()) }
        )

        val target = envWith()
        val outcome = BackupPipeline.import(target, root.toString(), ImportMode.REPLACE, null)

        assertTrue("未知 scope 必须跳过而不是崩，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
        val done = outcome as BackupPipeline.ImportOutcome.Done
        assertTrue(
            "未知 scope 应当以 SKIPPED_UNKNOWN 记在结果里",
            done.results.any { it.status == ImportStatus.SKIPPED_UNKNOWN },
        )
    }

    @Test
    fun `a too new schema version is rejected rather than half parsed`() {
        val env = envWith()
        val text = BackupPipeline.export(env, setOf("workflows"), secrets = null).text
        val root = JsonParser.parseString(text).asJsonObject
        root.addProperty("schemaVersion", BackupEnvelope.SCHEMA_VERSION + 1)

        val outcome = BackupPipeline.import(envWith(), root.toString(), ImportMode.REPLACE, null)
        assertTrue("更高版本必须整份拒绝，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Rejected)
    }

    @Test
    fun `the same passphrase opens two backups with different salts`() {
        // ⚠️ 证明「salt 随文件走、App 侧不持久化」这条设计成立（方案 §6.6）。
        //
        // ⚠️ 这里**不能**用 `ctx()` —— 那个夹具传的是固定 salt（为了断言可复现）。
        //    为了验「盐每次随机」，必须让 salt 走默认参数（`BackupCrypto.newSalt()`）。
        fun randomSaltCtx() = SecretContext(passphrase, iterations = 1000)

        val env = envWith(listOf(workflowWithApiKey("w1")))
        val textA = BackupPipeline.export(env, setOf("workflows"), randomSaltCtx()).text
        val textB = BackupPipeline.export(env, setOf("workflows"), randomSaltCtx()).text

        val saltA = JsonParser.parseString(textA).asJsonObject
            .getAsJsonObject("encryption").get("salt").asString
        val saltB = JsonParser.parseString(textB).asJsonObject
            .getAsJsonObject("encryption").get("salt").asString
        assertFalse("两次导出的 salt 必须不同（同盐 ⇒ 派生同一密钥，丧失 KDF 的意义）", saltA == saltB)

        for (text in listOf(textA, textB)) {
            val target = envWith()
            val outcome = BackupPipeline.import(target, text, ImportMode.REPLACE, randomSaltCtx())
            assertTrue("同一口令必须能解开不同 salt 的备份，实际：$outcome", outcome is BackupPipeline.ImportOutcome.Done)
            assertEquals("sk-live-123", target.workflowStore.single().steps.single().parameters["api_key"])
        }
    }

    @Test
    fun `export reports excluded scopes so the summary is honest`() {
        val env = envWith(listOf(workflowWithApiKey("w1")))
        val text = BackupPipeline.export(env, setOf("workflows"), secrets = null).text
        val summary = JsonParser.parseString(text).asJsonObject.getAsJsonObject("summary")

        val included = summary.getAsJsonArray("includedScopes").map { it.asString }
        val excluded = summary.getAsJsonArray("excludedScopes").map { it.asString }
        assertEquals(listOf("workflows"), included)
        assertTrue("未勾选的 scope 必须出现在 excludedScopes 里", "secrets" in excluded)
        assertTrue("folders / global_variables 也应当列为未包含", "folders" in excluded)
    }

    // ── 接线锚定（源码扫描）────────────────────────────────
    //
    // ⚠️ 这两条**测不出行为**，只能扫源码。存在理由见文件头。

    @Test
    fun `WorkflowScope export really calls the scrubber on both branches`() {
        val file = File("src/main/java/com/chaomixian/vflow/core/backup/scopes/WorkflowScope.kt")
        assertTrue("WorkflowScope.kt 应当存在", file.isFile)
        val body = functionBody(codeLines(file), "override fun export(")

        assertTrue("应能切出 export 的函数体（否则本断言在空转）", body.isNotBlank())
        assertTrue(
            "❌ 无口令分支必须真的调用 SecretFieldScrubber.scrub —— \n" +
                "只声明 scrub 而不调用，会让步骤参数里的 api_key **明文进备份**，\n" +
                "且不会有任何报错（本仓库反模式 6 的形态）。",
            body.contains("SecretFieldScrubber.scrub("),
        )
        assertTrue(
            "❌ 有口令分支必须真的调用 SecretFieldScrubber.encryptInPlace",
            body.contains("SecretFieldScrubber.encryptInPlace("),
        )
        assertTrue(
            "❌ 清洗出的位置必须往 ScopePayload 里传（否则 summary.scrubbedFields 恒为空）",
            body.contains("ScopePayload(") || body.contains("ScopePayload.of("),
        )
        assertFalse(
            "TODO(T2) 的占位注释应当已被真实实现取代",
            body.contains("TODO(T2)"),
        )
    }

    @Test
    fun `AndroidBackupEnvironment really implements the secret store`() {
        val file = File("src/main/java/com/chaomixian/vflow/core/backup/AndroidBackupEnvironment.kt")
        assertTrue("AndroidBackupEnvironment.kt 应当存在", file.isFile)
        val code = codeLines(file)

        assertTrue(
            "❌ AndroidBackupEnvironment 必须覆写 secretStore —— \n" +
                "接口有默认实现（null），漏覆写**编译照样通过**，\n" +
                "而后果是生产环境永远拿不到密钥（SecretsScope.export 恒返回 null）——\n" +
                "且因为它是「本次不适用」的合法语义，**没有任何报错**。",
            code.any { it.contains("override val secretStore") },
        )
        assertTrue(
            "secretStore 必须返回一个 SecretStore 实现",
            code.any { it.contains("SecretStore") },
        )
        assertTrue(
            "该实现必须碰 SharedPreferences",
            code.any { it.contains("getSharedPreferences") },
        )
    }

    @Test
    fun `the source scan is not vacuous`() {
        // 防空转：若剥注释/切函数体的逻辑坏了，上面两条会假绿。
        val envFile = File("src/main/java/com/chaomixian/vflow/core/backup/AndroidBackupEnvironment.kt")
        val code = codeLines(envFile)
        assertTrue(
            "剥注释后应当仍能看到类定义与 import —— 剥离逻辑可能剥过头了",
            code.any { it.contains("class AndroidBackupEnvironment") },
        )
        val scopeFile = File("src/main/java/com/chaomixian/vflow/core/backup/scopes/WorkflowScope.kt")
        val body = functionBody(codeLines(scopeFile), "override fun export(")
        assertTrue(
            "切出的 export 函数体应当含真实代码（不是空串或只剩注释）",
            body.length > 100,
        )
    }

    // ── 夹具 ────────────────────────────────────────────────

    /** 剥掉块注释与行注释，保留原始行（不做 trim，便于按缩进切函数体）。 */
    private fun codeLines(file: File): List<String> {
        val out = mutableListOf<String>()
        var inBlock = false
        file.readLines().forEach { raw ->
            val trimmed = raw.trim()
            if (inBlock) {
                if (trimmed.contains("*/")) inBlock = false
                return@forEach
            }
            if (trimmed.startsWith("/*")) {
                if (!trimmed.contains("*/")) inBlock = true
                return@forEach
            }
            if (trimmed.startsWith("//")) return@forEach
            out += raw
        }
        return out
    }

    /**
     * 按大括号配平切出一个函数体。
     *
     * ⚠️ 不能用正则 —— 函数体里有字符串字面量（含 `{`/`}`）与嵌套 lambda。
     */
    private fun functionBody(lines: List<String>, signature: String): String {
        val text = lines.joinToString("\n")
        val start = text.indexOf(signature)
        if (start < 0) return ""
        val open = text.indexOf('{', start)
        if (open < 0) return ""
        var depth = 0
        for (i in open until text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
        }
        return ""
    }

    /** 把 `scopes.*.data` 里第一个 `$enc` 节点的 base64 密文改一个字符。 */
    private fun flipOneCiphertextChar(text: String): String {
        val pattern = Regex(""""data"\s*:\s*"([A-Za-z0-9+/=]{20,})"""")
        val match = pattern.find(text) ?: error("备份里找不到密文字段")
        val original = match.groupValues[1]
        val flipped = flipBase64Char(original)
        return text.replaceFirst(original, flipped)
    }

    /** 改 base64 串里一个字符，**保持长度**（避免变成「长度非法」而不是「tag 不符」）。 */
    private fun flipBase64Char(base64: String): String {
        val index = base64.length - 2
        val original = base64[index]
        val replacement = if (original == 'A') 'B' else 'A'
        return base64.substring(0, index) + replacement + base64.substring(index + 1)
    }
}
