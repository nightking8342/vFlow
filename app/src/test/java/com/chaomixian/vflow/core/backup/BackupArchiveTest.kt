// 文件: test/java/com/chaomixian/vflow/core/backup/BackupArchiveTest.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream

/**
 * [BackupArchive] 的回归测试 —— **备份容器从「一个 JSON」换成「一个压缩包」的那一层**。
 *
 * ## 为什么必须有
 *
 * 这一层的失败模式**全是静默的**：
 *
 * | 改错了什么 | 表现 | 会被行为测试发现吗 |
 * |---|---|---|
 * | `$ref` 没还原 | 导入后所有 scope 都是空的 | 会（但只在导入路径上） |
 * | 附件没打进包 | 跨设备恢复后图标变默认 | **不会**（同设备恢复照常） |
 * | 扩展名/魔数判反 | 压缩包被当 JSON 解析 | 会（报「不是合法备份」） |
 * | 条目名没防穿越 | 一份改过的备份能往任意路径写文件 | **不会** |
 *
 * 后两类正是本文件存在的理由。
 */
class BackupArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleEnvelope(): String {
        val sections = linkedMapOf(
            "workflows" to ScopePayload.of(
                listOf(
                    JsonParser.parseString("""{"id":"w1","name":"甲"}"""),
                    JsonParser.parseString("""{"id":"w2","name":"乙"}"""),
                )
            ),
            "folders" to ScopePayload.empty(),
        )
        return BackupEnvelope.write(
            json = com.google.gson.Gson(),
            sections = sections,
            includedScopes = sections.keys.toList(),
            excludedScopes = listOf("secrets"),
            scrubbedFields = listOf("s1.api_key"),
            appVersionName = "1.5.4",
            appVersionCode = 50,
            createdAt = 0L,
        )
    }

    private fun writeArchive(plain: String, filesRoot: java.io.File? = null): ByteArray {
        val out = ByteArrayOutputStream()
        BackupArchive.write(plain, out, filesRoot)
        return out.toByteArray()
    }

    // ── 容器判定 ────────────────────────────────────────────────────

    @Test
    fun `isArchive keys on the magic bytes not the extension`() {
        assertTrue(BackupArchive.isArchive(writeArchive(sampleEnvelope())))
        // 纯 JSON 文本**不是**压缩包 —— 旧备份必须继续走文本路径。
        assertFalse(BackupArchive.isArchive(sampleEnvelope().toByteArray(Charsets.UTF_8)))
        // 不足 4 字节 ⇒ false，不抛（空文件也要能被安全地探一下）。
        assertFalse(BackupArchive.isArchive(ByteArray(0)))
        assertFalse(BackupArchive.isArchive(byteArrayOf(0x50, 0x4B)))
    }

    // ── 往返 ────────────────────────────────────────────────────────

    @Test
    fun `a scope payload survives the archive round trip`() {
        val plain = sampleEnvelope()
        val content = BackupArchive.read(writeArchive(plain).inputStream())
        assertNotNull("必须能解出一份纯 JSON", content)

        // ⚠️ 比的是**解析后的结构**而不是文本 —— 键顺序不保证一致，
        //    而这条用例要锁的是「数据没丢」。
        assertEquals(
            JsonParser.parseString(plain),
            JsonParser.parseString(content!!.plainJson),
        )
    }

    /**
     * ⚠️⚠️ **拆分粒度只到 scope** —— 每个 scope 的 `data` 各成一个条目，
     * `scopes/workflows.json` 里是**一个包含全部工作流的 JSON 数组**，
     * **不是**一条工作流一个文件。
     *
     * 不拆到条目级的理由：本地存储就是「所有工作流一个 JSON 塞进一个 prefs 键」
     * （`vflow_workflows` / `workflow_list`），拆到条目级等于凭空多一套心智模型，
     * 且用户打开包看到一堆碎文件反而更难找他想要的那份。
     */
    @Test
    fun `each scope becomes one entry and the data is referenced from the manifest`() {
        val entries = readAllEntries(writeArchive(sampleEnvelope()))

        assertEquals(
            "主条目 + 两个 scope 条目（这份信封没有附件）",
            setOf(
                BackupArchive.MANIFEST_ENTRY,
                "scopes/workflows.json",
                "scopes/folders.json",
            ),
            entries.keys,
        )

        // 主条目里各 scope 的 `data` 必须是 `$ref` 引用，而不是内容本身。
        val manifest = JsonParser.parseString(
            entries.getValue(BackupArchive.MANIFEST_ENTRY).decodeToString()
        ).asJsonObject
        val scopeObj = manifest.getAsJsonObject("scopes").getAsJsonObject("workflows")
        assertEquals(
            "scopes/workflows.json",
            scopeObj.getAsJsonObject("data").get("${'$'}ref").asString,
        )
        // ⚠️ `count` 必须**留在主条目里** —— 读侧要在不解压的情况下显示条数。
        assertEquals(2, scopeObj.get("count").asInt)

        // ⚠️ 粒度锁：scope 条目里是**一个数组**（全部工作流），不是一条一个文件。
        val data = JsonParser.parseString(
            entries.getValue("scopes/workflows.json").decodeToString()
        )
        assertTrue("scope 条目必须是数组本体", data.isJsonArray)
        assertEquals("全部工作流都在同一个数组里", 2, data.asJsonArray.size())
    }

    /**
     * ⚠️ 手工造的「拆过 scope 的包」**必须能读**。
     *
     * 读不出来的表现是「导入成功但什么都没进来」—— 各 scope 的 `data` 会是一个
     * `{"$ref": …}` 对象，而 scope 拿到它只会当空数据处理，**不报错**。
     */
    @Test
    fun `a legacy per scope archive still reads back correctly`() {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("scopes/workflows.json"))
            zip.write("""[{"id":"w1","name":"甲"}]""".toByteArray())
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchive.MANIFEST_ENTRY))
            zip.write(
                """
                {"schema":"vflow.backup","schemaVersion":1,
                 "scopes":{"workflows":{"count":1,"data":{"${'$'}ref":"scopes/workflows.json"}}}}
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
        val content = BackupArchive.read(out.toByteArray().inputStream())
        assertNotNull("旧包必须能读", content)
        val data = JsonParser.parseString(content!!.plainJson).asJsonObject
            .getAsJsonObject("scopes").getAsJsonObject("workflows").get("data")
        assertTrue("引用必须被还原成真实内容（否则 scope 会拿到空数据且不报错）", data.isJsonArray)
        assertEquals("w1", data.asJsonArray[0].asJsonObject.get("id").asString)
    }

    @Test
    fun `reading a non zip stream returns null instead of throwing`() {
        assertNull(BackupArchive.read(sampleEnvelope().toByteArray(Charsets.UTF_8).inputStream()))
        assertNull(BackupArchive.read(ByteArray(0).inputStream()))
    }

    @Test
    fun `a zip with no top level json at all is rejected`() {
        // ⚠️ 主条目判据是「排除法」（任何不在 files/ 与旧包 scopes/ 下的 .json），
        //    所以这里**不能**用「有个叫 other.json 的条目」来测「拒绝」——
        //    那个条目**就是**主条目。真正的拒绝条件是「一个候选都没有」。
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("files/card_icons/x.png"))
            zip.write(byteArrayOf(1))
            zip.closeEntry()
        }
        assertNull(BackupArchive.read(out.toByteArray().inputStream()))
    }

    @Test
    fun `a dangling reference degrades to an empty array rather than failing the whole read`() {
        // 手工造一个「引用了不存在的条目」的包（模拟包被截断）。
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(BackupArchive.MANIFEST_ENTRY))
            zip.write(
                """
                {"schema":"vflow.backup","schemaVersion":1,
                 "scopes":{"workflows":{"count":3,"data":{"${'$'}ref":"scopes/workflows.json"}}}}
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
        }
        val content = BackupArchive.read(out.toByteArray().inputStream())
        assertNotNull("单条引用坏掉不该让整份读不出来", content)
        val scopeData = JsonParser.parseString(content!!.plainJson).asJsonObject
            .getAsJsonObject("scopes").getAsJsonObject("workflows").get("data")
        assertTrue("坏引用 ⇒ 空数组（下游按『这个范围是空的』处理）", scopeData.isJsonArray)
        assertEquals(0, scopeData.asJsonArray.size())
    }

    // ── 附件 ────────────────────────────────────────────────────────

    @Test
    fun `a custom icon file is packed and restored under filesRoot`() {
        val root = tmp.newFolder("files")
        val iconDir = java.io.File(root, "card_icons").apply { mkdirs() }
        val icon = java.io.File(iconDir, "card_icon_1.png")
        icon.writeBytes(byteArrayOf(1, 2, 3, 4, 5))

        val plain = envelopeWithIcon(icon.absolutePath)
        val out = ByteArrayOutputStream()
        assertEquals("恰好一条附件", 1, BackupArchive.write(plain, out, root))
        val bytes = out.toByteArray()

        // 解到**另一个**根目录，模拟换设备。
        val otherRoot = tmp.newFolder("other-files")
        val content = BackupArchive.read(bytes.inputStream())!!
        val restored = BackupArchive.restoreAttachments(content.entries, otherRoot)

        val restoredPath = restored["card_icon_1.png"]
        assertNotNull("按**文件名**作为键（旧绝对路径的前缀可能完全对不上）", restoredPath)
        assertTrue(java.io.File(restoredPath!!).isFile)
        assertEquals(
            listOf<Byte>(1, 2, 3, 4, 5),
            java.io.File(restoredPath).readBytes().toList(),
        )
    }

    @Test
    fun `remapping points the field at the restored path`() {
        val root = tmp.newFolder("files")
        val iconDir = java.io.File(root, "card_icons").apply { mkdirs() }
        val icon = java.io.File(iconDir, "card_icon_1.png").apply { writeBytes(byteArrayOf(9)) }

        val otherRoot = tmp.newFolder("other-files")
        val content = BackupArchive.read(writeArchive(envelopeWithIcon(icon.absolutePath), root).inputStream())!!
        val restored = BackupArchive.restoreAttachments(content.entries, otherRoot)

        val tree = JsonParser.parseString(content.plainJson).asJsonObject
        BackupArchive.remapAttachmentPaths(tree, restored)

        val newPath = tree.getAsJsonObject("scopes").getAsJsonObject("workflows")
            .getAsJsonArray("data")[0].asJsonObject.get("cardIconRes").asString
        assertEquals(restored["card_icon_1.png"], newPath)
        assertTrue("必须指向新根目录，不是旧设备那个绝对路径", newPath.startsWith(otherRoot.absolutePath))
    }

    @Test
    fun `a field pointing outside the app private dir is not packed`() {
        // ⚠️ 用户完全可以把 `cardIconRes` 设成相册里的路径（老数据 / 手工改过的 JSON）。
        //    把它卷进备份既不合预期（用户没打算备份相册），又会让包体积失控。
        val root = tmp.newFolder("files")
        val outside = tmp.newFile("photo.png").apply { writeBytes(byteArrayOf(1)) }

        val out = ByteArrayOutputStream()
        BackupArchive.write(envelopeWithIcon(outside.absolutePath), out, root)
        val entries = readAllEntries(out.toByteArray())

        assertFalse(
            "私有目录之外的文件不得进包：${entries.keys}",
            entries.keys.any { it.startsWith(BackupArchive.FILES_DIR) },
        )
    }

    @Test
    fun `a missing icon file is skipped rather than failing the export`() {
        val root = tmp.newFolder("files")
        val plain = envelopeWithIcon(java.io.File(root, "card_icons/never-existed.png").absolutePath)

        val out = ByteArrayOutputStream()
        val count = BackupArchive.write(plain, out, root)

        assertEquals(0, count)
        assertNotNull("整份备份仍必须写出来", BackupArchive.read(out.toByteArray().inputStream()))
    }

    @Test
    fun `a path outside filesRoot is rejected even if the file exists`() {
        val root = tmp.newFolder("files")
        val other = tmp.newFolder("elsewhere")
        val sneaky = java.io.File(other, "x.png").apply { writeBytes(byteArrayOf(1)) }

        val out = ByteArrayOutputStream()
        BackupArchive.write(envelopeWithIcon(sneaky.absolutePath), out, root)
        val entries = readAllEntries(out.toByteArray())
        assertFalse(entries.keys.any { it.startsWith(BackupArchive.FILES_DIR) })
    }

    @Test
    fun `restore refuses to write outside filesRoot`() {
        // ⚠️ 一份**被改过的**备份可以带 `files/../../x` 这样的条目名。
        //    必须在写之前拦 —— 否则它能往任意路径落文件。
        val root = tmp.newFolder("files")
        val restored = BackupArchive.restoreAttachments(
            mapOf(
                "files/../../escaped.png" to byteArrayOf(1),
                "files/card_icons/ok.png" to byteArrayOf(2),
            ),
            root,
        )

        assertNull("穿越条目必须被丢掉", restored["escaped.png"])
        assertTrue("正常条目照常还原", restored.containsKey("ok.png"))
        assertFalse(
            "文件不得落到 filesRoot 之外",
            java.io.File(root.parentFile, "escaped.png").exists(),
        )
    }

    @Test
    fun `restore does nothing when there is no filesRoot`() {
        // 纯 JVM 测试环境的默认（`BackupEnvironment.filesRoot == null`）。
        assertTrue(BackupArchive.restoreAttachments(mapOf("files/a.png" to byteArrayOf(1)), null).isEmpty())
    }

    @Test
    fun `attachments are only collected from the whitelisted keys`() {
        val root = tmp.newFolder("files")
        val icon = java.io.File(root, "card_icons/x.png").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }

        // ⚠️ **键名白名单**：脚本正文 / 日志里出现的绝对路径**不得**被卷进包 ——
        //    那既不是用户数据、又会把包撑大，而且没有任何报错。
        val root2 = JsonObject().apply {
            addProperty("script", icon.absolutePath)
            addProperty("logPath", icon.absolutePath)
        }
        assertTrue(
            "非白名单键里的路径不得进包",
            BackupArchive.attachmentsOf(root2, root).isEmpty(),
        )
        assertEquals(1, BackupArchive.attachmentsOf(iconObject(icon.absolutePath), root).size)
    }

    @Test
    fun `a file uri is understood the same way as an absolute path`() {
        val root = tmp.newFolder("files")
        val icon = java.io.File(root, "card_icons/x.png").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(1))
        }
        // 与 `WorkflowIconValue.filePathOf` 同一套规则（`file://` 剥前缀）。
        assertEquals(
            BackupArchive.attachmentsOf(iconObject("file://${icon.absolutePath}"), root)
                .single().sourcePath,
            BackupArchive.attachmentsOf(iconObject(icon.absolutePath), root).single().sourcePath,
        )
    }

    /**
     * 读侧必须认出**各种名字**的主条目。
     *
     * ⚠️ 单文件 / 文件夹 / 备份全部三种导出各有条目名（`workflow.json` /
     * `folder.json` / `workflows.json`），而**早期版本写的是 `manifest.json`**
     * —— 只认一个名字会让那些包读不出来，表现是「不是有效的备份文件」，
     * 用户完全无从判断是包坏了还是格式变了。
     */
    @Test
    fun `the reader recognises any top level json as the primary entry`() {
        for (name in listOf("workflow.json", "folder.json", "workflows.json", "manifest.json")) {
            val out = ByteArrayOutputStream()
            BackupArchive.writePlain("""{"id":"w1","name":"甲"}""", out, entryName = name)
            val content = BackupArchive.read(out.toByteArray().inputStream())
            assertNotNull("主条目名 '$name' 必须能被读出来", content)
            assertEquals(
                "w1",
                JsonParser.parseString(content!!.plainJson).asJsonObject.get("id").asString,
            )
        }
    }

    @Test
    fun `the primary entry never collides with scope or file entries`() {
        // `scopes/*.json` 与 `files/*` 都不是主条目 —— 排除法必须挡住它们，
        // 否则一份**只有 scopes/ 没有主条目**的坏包会被当成「主条目是那个 scope」。
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("scopes/workflows.json"))
            zip.write("[]".toByteArray())
            zip.closeEntry()
        }
        assertNull("没有主条目 ⇒ null（调用方回落到按纯 JSON 解析）", BackupArchive.read(out.toByteArray().inputStream()))
    }

    @Test
    fun `writePlain keeps the payload inline instead of splitting it into scopes`() {
        // 单文件 / 文件夹导出走这条路：载荷**不是**备份信封（没有 `scopes`），
        // 拆不出也没必要拆 —— 拆了只会让用户解压后更难找到那份 JSON。
        val payload = """{"id":"w1","name":"甲","cardIconRes":"rounded_home_24"}"""
        val out = ByteArrayOutputStream()
        BackupArchive.writePlain(payload, out)

        val entries = readAllEntries(out.toByteArray())
        // ⚠️ 默认条目名是 `workflow.json`（**不是** `manifest.json`）——
        //    包里装的是一份工作流，名字就该反映里面是什么。
        assertEquals(setOf(BackupArchive.WORKFLOW_ENTRY), entries.keys)
        val primary = JsonParser.parseString(entries.getValue(BackupArchive.WORKFLOW_ENTRY).decodeToString())
        assertEquals(JsonParser.parseString(payload), primary)
    }

    @Test
    fun `writePlain still carries attachments`() {
        val root = tmp.newFolder("files")
        val icon = java.io.File(root, "card_icons/x.png").apply {
            parentFile!!.mkdirs(); writeBytes(byteArrayOf(7))
        }
        // ⚠️ 用 Gson 拼 JSON —— 手工拼字符串会把 Windows 路径里的 `\`
        //    当成转义符（实测 `MalformedJsonException: Invalid escape sequence`）。
        val payload = JsonObject().apply {
            addProperty("id", "w1")
            addProperty("cardIconRes", icon.absolutePath)
        }.toString()

        val out = ByteArrayOutputStream()
        val count = BackupArchive.writePlain(payload, out, root)
        assertEquals(1, count)
        assertTrue(readAllEntries(out.toByteArray()).containsKey("files/card_icons/x.png"))
    }

    // ── 辅助 ────────────────────────────────────────────────────────

    private fun iconObject(path: String): JsonObject = JsonObject().apply {
        addProperty("cardIconRes", path)
    }

    /** 一份只含一条工作流的信封，那条工作流的卡片图标指向 [iconPath]。 */
    private fun envelopeWithIcon(iconPath: String): String {
        val wf = JsonObject().apply {
            addProperty("id", "w1")
            addProperty("name", "甲")
            addProperty("cardIconRes", iconPath)
        }
        return BackupEnvelope.write(
            json = com.google.gson.Gson(),
            sections = linkedMapOf("workflows" to ScopePayload.of(listOf(wf))),
            includedScopes = listOf("workflows"),
            excludedScopes = emptyList(),
            scrubbedFields = emptyList(),
            appVersionName = "1.5.4",
            appVersionCode = 50,
            createdAt = 0L,
        )
    }

    private fun readAllEntries(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        java.util.zip.ZipInputStream(bytes.inputStream()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.isDirectory) out[entry.name] = zip.readBytes()
            }
        }
        return out
    }
}
