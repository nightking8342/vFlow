// 文件: main/java/com/chaomixian/vflow/core/backup/BackupArchive.kt
package com.chaomixian.vflow.core.backup

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * **备份的 ZIP 容器层** —— 把「一份纯 JSON」升级成「一个压缩包」。
 *
 * ## 为什么要有它
 *
 * 纯 JSON 备份有三个各自独立的硬伤，而它们**都是结构性的**（调参解决不了）：
 *
 * | 问题 | 纯 JSON | ZIP |
 * |---|---|---|
 * | 自定义卡片图标的**图片本身** | 进不去（JSON 只能存路径字符串） | `files/` 目录原样带上 |
 * | 内存峰值 | 整棵树 → 整串文本 → UTF-8 字节数组，**三份同时在内存里** | 流式写，逐条落盘 |
 * | 体积 | 明文，`recording_data` 之类的大字段全量展开 | Deflate 压缩 |
 *
 * ## 布局
 *
 * ```
 * vflow_backup_20261008.zip
 * ├── manifest.json          ← 信封本体（schema / summary / encryption + 各 scope 的元信息）
 * ├── scopes/
 * │   ├── folders.json       ← 该 scope 的 data 本体（一个 JSON 数组）
 * │   ├── workflows.json
 * │   └── global_variables.json
 * └── files/
 *     ├── card_icons/a1b2c3.png
 *     └── shortcut_icons/d4e5f6.png
 * ```
 *
 * 单文件 / 文件夹 / 备份全部三种导出用 [writePlain]（**不拆 `scopes/`** —— 那三种
 * 载荷本来就没有 `scopes` 这层），主条目名各不相同
 * （`workflow.json` / `folder.json` / `workflows.json`）。
 *
 * ## 拆分粒度：**只拆到 scope**
 *
 * 每个 scope 的 `data` 各成一个条目，`manifest.json` 里那处换成 `{"$ref": …}`。
 *
 * ⚠️⚠️ **scope 内部不再拆** —— `scopes/workflows.json` 里是**一个包含全部工作流的
 * JSON 数组**，**不是**一条工作流一个文件。理由：本地存储就是「所有工作流一个 JSON
 * 塞进一个 prefs 键」（`vflow_workflows` / `workflow_list`），拆到条目级等于凭空
 * 多一套心智模型，且用户打开包看到一堆碎文件反而更难找他想要的那份。
 *
 * ⚠️ **但真正的收益是「按范围定位」**：用户只勾了「工作流」时，包里就只有
 * `scopes/workflows.json` 一个数据条目，其余范围不会白占体积；读侧也能**只解
 * 需要的那几个条目**（`read` 目前仍全读，见其 KDoc）。
 *
 * ## 与纯 JSON 的关系
 *
 * [write] 的输入就是 [BackupEnvelope.write] 的产出、[read] 的产出就是它的输入。
 * ⇒ **两条路共用同一份信封构造与解析**，不可能出现「ZIP 里的 manifest 与
 * 纯 JSON 备份形状不一致」这种事（那正是本仓库反复踩的静默失效形态）。
 *
 * ## 本文件必须保持纯 JVM
 *
 * 只用 `java.util.zip` / `java.io` —— 由 `BackupPurityTest` 机器化保证。
 * 「文件写到哪里」经 [BackupEnvironment.filesRoot] 这个接缝拿，不碰 `android.*`。
 */
object BackupArchive {

    /**
     * **备份信封**的主条目名。
     *
     * ⚠️ 只有 [write]（全局备份）用它 —— 那里的载荷确实是一份「信封」
     * （`schema` / `scopes` / `summary` / `encryption`），叫 manifest 名副其实。
     * 单文件 / 文件夹导出**不要**用它：那份载荷是一个工作流对象，
     * 用户解压出来看到 `manifest.json` 会以为里面是索引（见 [writePlain] 的 `entryName`）。
     */
    const val MANIFEST_ENTRY = "manifest.json"

    /** 单工作流导出的主条目名。 */
    const val WORKFLOW_ENTRY = "workflow.json"

    /** 文件夹导出的主条目名。 */
    const val FOLDER_ENTRY = "folder.json"

    /** 「备份全部」（裸格式，顶层 `workflows` + `folders`）的主条目名。 */
    const val WORKFLOWS_ENTRY = "workflows.json"

    /**
     * 读侧识别主条目时的**优先顺序**。
     *
     * ⚠️ 顺序只是「同名撞车时取谁」的裁决，不是「必须有其中一个」——
     * 真正的判据见 [primaryEntryOf]（**任何**不在 `files/` 与旧包的 `scopes/`
     * 下的 `.json` 条目都算主条目）。这样做的理由是**名字会变**：
     * 早期版本的单文件导出写的是 `manifest.json`，用户手上可能已有那种包。
     */
    private val PRIMARY_ENTRY_PREFERENCE = listOf(
        MANIFEST_ENTRY, WORKFLOW_ENTRY, FOLDER_ENTRY, WORKFLOWS_ENTRY,
    )

    /**
     * 附件（自定义图标等二进制文件）的条目前缀。
     */
    const val FILES_DIR = "files/"

    /**
     * 各 **scope 数据**的条目前缀。
     *
     * ⚠️ 备份信封里的每个 scope 各成一个条目（`scopes/workflows.json` …），
     * 主条目（`manifest.json`）里那处 `data` 换成 `{"$ref": …}` 引用。
     *
     * ⚠️⚠️ **只拆到 scope 这一层，scope 内部不再拆** ——
     * `scopes/workflows.json` 里是**一个包含全部工作流的 JSON 数组**，
     * **不是**一条工作流一个文件。理由：本地存储就是「所有工作流一个 JSON
     * 塞进一个 prefs 键」（`vflow_workflows` / `workflow_list`），
     * 拆到条目级等于凭空多一套心智模型，且用户打开包看到一堆碎文件更难找。
     */
    const val SCOPE_DIR = "scopes/"

    /** 主条目里指向 scope 数据的引用键。 */
    private const val REF_KEY = "\$ref"

    /** ZIP 的本地文件头魔数。用它判「是不是压缩包」，比看扩展名可靠（用户会改扩展名）。 */
    private val MAGIC = byteArrayOf(0x50, 0x4B, 0x03, 0x04)

    /** 单条附件的体积上限。超过就**不打包**并记进摘要 —— 不做静默截断。 */
    const val MAX_ATTACHMENT_BYTES: Long = 32L * 1024 * 1024

    /**
     * 附件来源：`scope data` 里**这些键**的值若是自定义图片路径，就把文件带进包。
     *
     * ⚠️ **刻意用「键名白名单」而不是「看起来像路径的字符串」**：
     * 后者会把脚本正文里的 `/data/...`、日志里的绝对路径一起卷进来 ——
     * 那既不是用户数据，又会把包撑大，而且**没有任何报错**。
     *
     * 与 `Workflow` 的字段名一一对应（`WorkflowIconValue` 判形态、这里判「该不该打包」）。
     */
    private val ATTACHMENT_KEYS = setOf("cardIconRes", "shortcutIconRes")

    /** 一条被带进包的附件。 */
    data class Attachment(
        /** 包内路径（`files/card_icons/x.png`）。 */
        val entryName: String,
        /** 源文件绝对路径（打包时读它）。 */
        val sourcePath: String
    )

    // ── 判定 ────────────────────────────────────────────────────────

    /**
     * 这段字节是不是 ZIP 压缩包。
     *
     * ⚠️ 判据是**魔数**而不是扩展名：用户会重命名，且 `SAF` 拿到的 `displayName`
     * 未必可信。魔数不符时调用方按纯 JSON 处理 —— 那是向后兼容的关键
     * （旧备份必须继续能导入）。
     *
     * @param bytes 至少前 4 字节。**不足 4 字节 ⇒ false**（不抛）。
     */
    fun isArchive(bytes: ByteArray): Boolean {
        if (bytes.size < MAGIC.size) return false
        return MAGIC.indices.all { bytes[it] == MAGIC[it] }
    }

    // ── 写 ──────────────────────────────────────────────────────────

    /**
     * 把一份**纯 JSON 备份文本**写成 ZIP。
     *
     * @param plainJson [BackupEnvelope.write] 的产出。
     * @param out **由调用方持有并关闭**（本函数只 `flush`，不 `close` ——
     *   `ZipOutputStream.close()` 会关掉底层的 `OutputStream`，
     *   而那个流可能是 `ContentResolver` 给的、由调用方管理）。
     * @param filesRoot 应用私有目录（`context.filesDir`）。**null ⇒ 不带附件**。
     * @return 实际打包进去的附件条数（供摘要展示；**没打包的不算**）。
     */
    fun write(plainJson: String, out: OutputStream, filesRoot: File? = null): Int =
        writeInternal(plainJson, out, filesRoot, primaryEntry = MANIFEST_ENTRY, splitScopes = true)

    /**
     * 写一个**没有 scope 结构**的压缩包 —— 供「单文件 / 文件夹 / 备份全部」用。
     *
     * ⚠️ 与 [write] 的差别是**不拆 `scopes/`**（那三种导出的载荷本来就没有
     * `scopes` 这层）+ 主条目名不同（[WORKFLOW_ENTRY] / [FOLDER_ENTRY] /
     * [WORKFLOWS_ENTRY]）。两者都是「一段现成的 JSON + 可选的 `files/`」，
     * 读侧走同一个 [read]。
     *
     * ⚠️⚠️ **主条目名必须反映里面是什么**，**不能一律叫 `manifest.json`** ——
     * 那个名字的语义是「索引」，用户解压一个单工作流导出、看到里面只有
     * `manifest.json` 会以为还有别的东西没解出来，或者以为这不是工作流文件。
     * 包里本来就是**一份工作流**，条目就该叫 `workflow.json`。
     */
    fun writePlain(
        payloadJson: String,
        out: OutputStream,
        filesRoot: File? = null,
        entryName: String = WORKFLOW_ENTRY,
    ): Int = writeInternal(payloadJson, out, filesRoot, primaryEntry = entryName, splitScopes = false)

    private fun writeInternal(
        json: String,
        out: OutputStream,
        filesRoot: File?,
        primaryEntry: String,
        splitScopes: Boolean,
    ): Int {
        val root = JsonParser.parseString(json).asJsonObject

        val zip = ZipOutputStream(out)
        zip.setLevel(java.util.zip.Deflater.BEST_SPEED)

        // 1. 附件先写（图片是包里的「大块头」，先写让解压时的顺序与用户直觉一致）。
        val attachmentCount = writeAttachments(zip, root, filesRoot)

        // 2. 各 scope 的数据各成条目，主条目里留 `$ref` 引用。
        //    ⚠️ 只拆到 scope 这一层（见 SCOPE_DIR 的说明），scope 内部不再拆。
        if (splitScopes) {
            val scopes = root.get("scopes")?.takeIf { it.isJsonObject }?.asJsonObject
            if (scopes != null) {
                for ((id, element) in scopes.entrySet().toList()) {
                    if (!element.isJsonObject) continue
                    val scopeObj = element.asJsonObject
                    val data = scopeObj.get("data") ?: continue
                    val scopeEntry = SCOPE_DIR + entryNameOf(id)
                    putEntry(zip, scopeEntry, data)
                    // ⚠️ 就地替换成引用。`count` 原样留在主条目里 ——
                    //    它是「这个 scope 有几条」的摘要，读侧要在**不解压**的情况下显示它。
                    scopeObj.add("data", JsonObject().apply { addProperty(REF_KEY, scopeEntry) })
                }
            }
        }

        // 3. 主条目最后写 —— 放最后能让「只读主条目」的实现在流式读取时
        //    先拿到附件的位置信息（ZIP 的中央目录也在末尾）。
        putEntry(zip, primaryEntry, root)

        zip.finish()
        zip.flush()
        return attachmentCount
    }

    /** scope id → 条目文件名。只做最小净化，防 id 里混进路径分隔符。 */
    private fun entryNameOf(id: String): String =
        id.map { if (it.isLetterOrDigit() || it == '_' || it == '-') it else '_' }
            .joinToString("") + ".json"

    private fun putEntry(zip: ZipOutputStream, name: String, element: JsonElement) {
        zip.putNextEntry(ZipEntry(name))
        val text = BackupEnvelope.writeElementTree(element)
        zip.write(text.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    /**
     * 收集并写入附件。
     *
     * ⚠️ **文件读不到（不存在 / 权限 / 超过 [MAX_ATTACHMENT_BYTES]）时跳过它，
     * 不中断整份备份** —— 一份「少了张图」的备份仍然是有用的；
     * 而因为一张图让用户导出失败，是把小问题放大成大问题。
     * 跳过的事实由 `BackupPipeline` 记进摘要（不静默）。
     */
    private fun writeAttachments(zip: ZipOutputStream, root: JsonObject, filesRoot: File?): Int {
        var written = 0
        for (attachment in attachmentsOf(root, filesRoot)) {
            val file = File(attachment.sourcePath)
            if (!file.isFile || file.length() > MAX_ATTACHMENT_BYTES) continue
            try {
                zip.putNextEntry(ZipEntry(attachment.entryName))
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
                written++
            } catch (e: Exception) {
                // 单张图坏掉不该让整份备份失败。关掉半截条目，继续下一个。
                runCatching { zip.closeEntry() }
            }
        }
        return written
    }

    // ── 读 ──────────────────────────────────────────────────────────

    /**
     * 解包结果。
     *
     * ⚠️ **附件字节随结果一起交出去**，而不是让调用方再读一遍流 ——
     * ZIP 只能顺序读一次，重读需要 `reset()`（`ContentResolver` 给的流通常不支持）。
     */
    data class Content(
        /** 还原后的**纯 JSON 文本**（`$ref` 已换回真实内容）。 */
        val plainJson: String,
        /** 包内条目名 → 内容。附件还原（[restoreAttachments]）用。 */
        val entries: Map<String, ByteArray>
    )

    /**
     * 把 ZIP 解回**纯 JSON 文本** + 全部条目。
     *
     * @return null ⇒ 不是合法 ZIP / 没有主条目（调用方据此回落到「按纯 JSON 解析」）。
     *
     * ⚠️ **不在这里落盘附件** —— 解包与落盘是两件事，「解不出来」与「写不进去」
     * 的处置完全不同，故拆成两个函数。
     */
    fun read(input: InputStream): Content? {
        val entries = try {
            readEntries(input)
        } catch (e: Exception) {
            return null
        }

        val primary = primaryEntryOf(entries) ?: return null
        val root = try {
            JsonParser.parseString(entries.getValue(primary).decodeToString()).asJsonObject
        } catch (e: Exception) {
            return null
        }

        // 把各 scope 的 `data` 从 `$ref` 换回真实内容。
        resolveRefs(root, entries)
        return Content(BackupEnvelope.writeElementTree(root), entries)
    }

    /**
     * 找出包里的**主条目**（那段「其余条目都是它的附属」的 JSON）。
     *
     * ⚠️⚠️ **不能只认 `manifest.json`**：单文件 / 文件夹 / 备份全部三种导出
     * 各有自己的条目名（`workflow.json` / `folder.json` / `workflows.json`）。
     * 只认一个名字会让那几种包读不出来，而**读不出来的表现是「不是有效的备份文件」**，
     * 用户完全无从判断是包坏了还是格式变了。
     *
     * ⇒ 判据是**排除法**：不在 `files/` 与 `scopes/` 下的 `.json` 条目都算主条目；
     * 同名撞车时按 [PRIMARY_ENTRY_PREFERENCE] 取（现实中不会撞，这只是兜底）。
     */
    private fun primaryEntryOf(entries: Map<String, ByteArray>): String? {
        val candidates = entries.keys.filter {
            it.endsWith(".json") &&
                !it.startsWith(FILES_DIR) &&
                !it.startsWith(SCOPE_DIR)
        }
        if (candidates.isEmpty()) return null
        if (candidates.size == 1) return candidates.single()
        return PRIMARY_ENTRY_PREFERENCE.firstOrNull { it in candidates } ?: candidates.min()
    }

    /** 一次性把全部条目读进内存。备份包里条目数很少（主条目 + 附件数个）。 */
    private fun readEntries(input: InputStream): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                out[entry.name] = zip.readBytes()
            }
        }
        return out
    }

    /**
     * 把 `scopes[*].data` 的 `$ref` 换回真实内容。
     *
     * ⚠️ 这是「拆 scope」的另一半：写侧把各 scope 的 `data` 换成 `{"$ref": …}`，
     * 读侧必须换回来。少了它，各 scope 拿到的 `data` 会是一个 `$ref` 对象
     * ⇒ **解析全空但不报错**（用户看到的是「导入成功，但什么都没进来」）。
     *
     * ⚠️ **只有 `scopes.<id>.data` 这一处的 `$ref` 会被还原**，其余位置一律不动。
     * 不做「任意深度扫 `$ref`」的原因：用户数据里完全可能出现一个真名叫 `$ref` 的键
     * （工作流步骤的参数字典是用户可控的），全局还原会把那个键**静默吃掉**。
     *
     * ⚠️ 引用指不到条目（包被改过 / 截断）⇒ 该 scope 的 `data` 置为**空数组**，
     * 让下游按「这个范围是空的」处理。**不抛** —— 与 `BackupEnvelope.readScopes`
     * 对坏 scope 的纪律一致（局部损坏不该让整份失败）。
     */
    private fun resolveRefs(root: JsonObject, entries: Map<String, ByteArray>) {
        val scopes = root.get("scopes")?.takeIf { it.isJsonObject }?.asJsonObject ?: return
        for ((_, element) in scopes.entrySet()) {
            if (!element.isJsonObject) continue
            val scopeObj = element.asJsonObject
            val data = scopeObj.get("data") ?: continue
            if (!data.isJsonObject) continue
            val ref = data.asJsonObject.get(REF_KEY)
                ?.takeIf { it.isJsonPrimitive }
                ?.asString ?: continue

            val bytes = entries[ref]
            val restored = bytes?.let {
                try {
                    JsonParser.parseString(it.decodeToString())
                } catch (e: Exception) {
                    null
                }
            }
            scopeObj.add("data", restored ?: JsonArray())
        }
    }

    // ── 附件：收集与还原 ────────────────────────────────────────────

    /**
     * 从信封树里挑出**需要随包走的附件**。
     *
     * 判据：键名在 [ATTACHMENT_KEYS] 里，且值是一个存在的、位于应用私有目录下的文件。
     *
     * ⚠️ **必须限定在应用私有目录内** —— 用户完全可以把 `cardIconRes` 设成
     * 相册里的某个路径（老数据 / 手工改过的 JSON）。把 `/sdcard/DCIM/...` 卷进备份
     * 既不合预期（用户没打算备份相册），又会让包体积失控。
     */
    fun attachmentsOf(root: JsonObject, filesRoot: File? = null): List<Attachment> {
        val out = LinkedHashMap<String, Attachment>()
        collectAttachments(root, out, filesRoot)
        return out.values.toList()
    }

    private fun collectAttachments(
        element: JsonElement,
        out: MutableMap<String, Attachment>,
        filesRoot: File?
    ) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (key in ATTACHMENT_KEYS && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    attachmentOf(value.asString, filesRoot)?.let { out[it.entryName] = it }
                } else {
                    collectAttachments(value, out, filesRoot)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { collectAttachments(it, out, filesRoot) }
            else -> Unit
        }
    }

    /**
     * 一个字段值 → 一条附件；不是「私有目录下的真实文件」⇒ null。
     *
     * ⚠️ `filesRoot == null`（纯 JVM 测试的默认）⇒ **一律返回 null**。
     * 这条不是偷懒：没有目标根目录就无法判断「这个路径是不是我们自己的」，
     * 而放行任意路径等于让测试环境把宿主机上的文件卷进包。
     */
    private fun attachmentOf(value: String, filesRoot: File?): Attachment? {
        if (filesRoot == null) return null
        val path = customImagePathOf(value) ?: return null
        val file = File(path)
        if (!file.isFile) return null

        // ⚠️⚠️ **用 `relativeTo` 判「在不在根目录里」，不要自己拼 `"$root/"` 做前缀比较。**
        // 后者在 Windows 上恒失败 —— 路径分隔符是 `\` 而拼出来的是 `/`，
        // 于是「在私有目录里的文件」一个都匹配不上，**表现是附件全部不进包且不报错**
        // （实测踩到：本机跑单测时 17 例里 5 例红，全因这一处）。
        // `relativeTo` 走 `java.io.File` 自己的比较逻辑，两个平台都对。
        val relative = try {
            file.relativeTo(filesRoot)
        } catch (e: IllegalArgumentException) {
            // 不在同一棵树下 ⇒ 不是我们的文件（相册 / 外部存储）。
            return null
        }
        // 再挡一层 `..`：`relativeTo` 对「根目录的父目录」也能算出相对路径。
        val segments = relative.path.split(File.separatorChar)
        if (segments.isEmpty() || segments.any { it == ".." }) return null

        // ⚠️ 条目名**一律用 `/`** —— ZIP 规范如此，`\` 会被当成文件名的一部分。
        return Attachment(
            entryName = FILES_DIR + relative.path.replace(File.separatorChar, '/'),
            sourcePath = file.absolutePath,
        )
    }

    /**
     * 字段值 → 文件路径；不是「绝对路径形态」⇒ null。
     *
     * ⚠️ 与 `WorkflowIconValue.filePathOf` 语义相同（`file://` 剥前缀 / 绝对路径），
     * 但**判据用 `File.isAbsolute` 而不是 `startsWith("/")`**：
     * 后者是 Android 专用的写法，在 Windows 上（本仓库的单测就跑在那儿）
     * `C:\...` 不以 `/` 开头 ⇒ 一律返回 null ⇒ **附件全部不进包且不报错**。
     * `File.isAbsolute` 在两端都对，且判「是不是路径」本来就该按平台来。
     *
     * ⚠️ 刻意**不 import `WorkflowIconValue`**：它在 `core.workflow` 下，
     * 而本类要能被 `core.backup` 的纯 JVM 测试独立引用。
     *
     * ⚠️ 本函数只判**形态**，真正的门禁是 [attachmentOf] 里的
     * 「必须落在 `filesRoot` 之内」—— 形态对了但不在私有目录里的文件照样不带。
     */
    private fun customImagePathOf(value: String): String? {
        val v = value.trim()
        if (v.isEmpty()) return null
        val path = if (v.startsWith("file://")) v.removePrefix("file://") else v
        return path.takeIf { File(it).isAbsolute }
    }

    /**
     * 把包内附件还原到 `filesRoot`，返回**文件名 → 新绝对路径**的映射。
     *
     * ⚠️ 键是**文件名**而不是相对路径：调用方（[remapAttachmentPaths]）拿到的
     * 字段值是一个**旧设备的绝对路径**（`/data/user/0/<旧包名>/files/card_icons/x.png`），
     * 从它身上只能稳定地取出**文件名** —— 包名与用户 id 都可能变，前缀对不上。
     *
     * ⚠️ 返回映射而不是「就地改 JSON」的理由：改 JSON 需要知道字段在哪，
     * 而那是 scope 的事；本层只负责「文件落到哪了」。
     *
     * ⚠️ **同名文件直接覆盖** —— 文件名是 `card_icon_<时间戳>.png`，同名的
     * 只可能是同一张图（同一台设备反复导入），覆盖是幂等的。
     * 跨设备时时间戳撞车的概率可忽略，撞了也只是显示成对方的图。
     *
     * @param bytes 包内条目 → 内容（[Content.entries]）。
     * @param filesRoot 目标根目录（`context.filesDir`）；null ⇒ 什么都不做。
     */
    fun restoreAttachments(bytes: Map<String, ByteArray>, filesRoot: File?): Map<String, String> {
        if (filesRoot == null) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for ((entryName, content) in bytes) {
            if (!entryName.startsWith(FILES_DIR)) continue
            val relative = entryName.removePrefix(FILES_DIR)
            if (relative.isEmpty()) continue
            // ⚠️ 防穿越：包里的条目名可以是 `../../x`。**必须在写之前拦**，
            //    否则一份被改过的备份能往任意路径写文件。
            if (relative.split('/').any { it == ".." || it.isEmpty() }) continue
            // ⚠️ 条目名用 `/` 分隔（ZIP 规范），落盘时要按**平台**分隔符拆，
            //    否则 Windows 上会造出一个名字里带 `/` 的文件。
            val target = File(filesRoot, relative.replace('/', File.separatorChar))
            try {
                target.parentFile?.mkdirs()
                target.writeBytes(content)
                out[target.name] = target.absolutePath
            } catch (e: Exception) {
                // 单个附件写不进去不该让整份导入失败。
            }
        }
        return out
    }

    /**
     * 把 `cardIconRes` / `shortcutIconRes` 里指向旧路径的值换成还原后的新路径。
     *
     * ⚠️ 按**文件名**匹配（见 [restoreAttachments] 的说明）。
     *
     * ⚠️ 换不上（图没打进包 / 写盘失败）时**保留原值**。抹成空会静默把用户的图标
     * 重置成默认图标，而保留原路径至少还留着「他设过什么」这条信息 ——
     * 同设备恢复时那个路径本来就是对的。
     */
    fun remapAttachmentPaths(root: JsonObject, restored: Map<String, String>) {
        if (restored.isEmpty()) return
        remapIn(root, restored)
    }

    private fun remapIn(element: JsonElement, restored: Map<String, String>) {
        when {
            element.isJsonObject -> element.asJsonObject.entrySet().forEach { (key, value) ->
                if (key in ATTACHMENT_KEYS && value.isJsonPrimitive && value.asJsonPrimitive.isString) {
                    val path = customImagePathOf(value.asString) ?: return@forEach
                    // ⚠️ 取文件名用 `File.name` 而不是 `substringAfterLast('/')` ——
                    //    后者在 Windows 路径（`\` 分隔）上会返回**整条路径**，
                    //    于是永远匹配不上、图标静默回落默认图标（实测踩到）。
                    restored[File(path).name]?.let {
                        element.asJsonObject.addProperty(key, it)
                    }
                } else {
                    remapIn(value, restored)
                }
            }
            element.isJsonArray -> element.asJsonArray.forEach { remapIn(it, restored) }
            else -> Unit
        }
    }

}
