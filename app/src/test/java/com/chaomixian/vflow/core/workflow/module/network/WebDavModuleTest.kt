package com.chaomixian.vflow.core.workflow.module.network

import com.chaomixian.vflow.core.module.AiModuleRiskLevel
import com.chaomixian.vflow.core.module.AiModuleUsageScope
import com.chaomixian.vflow.core.module.InputStyle
import com.chaomixian.vflow.core.module.InputVisibility
import com.chaomixian.vflow.core.module.ParameterType
import com.chaomixian.vflow.core.types.VTypeRegistry
import com.chaomixian.vflow.core.workflow.model.ActionStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [WebDavModule] 的**声明体检** + 源码扫描型接线锁。
 *
 * 形态照 `core/workflow/module/xposed/XposedJsModuleTest.kt`。
 *
 * ## ⚠️ 为什么这里要混用「反射读声明」与「扫源码」两种手段
 *
 * - **声明**（`getInputs` / `getOutputs` / `aiMetadata`）是纯数据，直接读即可。
 * - **接线**（`getDynamicInputs` 里到底有没有 try/catch、有没有真的注册进
 *   `ModuleRegistry`）**读声明读不出来** —— 本仓库三次踩过「纯函数单测全绿但调用点缺失」
 *   （`CoreLauncher` 漏调 `recordLaunchedDexFingerprint`、`XposedDiagnostics.messageFor` 零调用点），
 *   故这两条走源码扫描。
 */
class WebDavModuleTest {

    private val module = WebDavModule()

    private companion object {
        const val MODULE_SRC = "src/main/java/com/chaomixian/vflow/core/workflow/module/network/WebDavModule.kt"
        const val REGISTRY_SRC = "src/main/java/com/chaomixian/vflow/core/workflow/module/ModuleRegistry.kt"
        const val XML_PARSER_SRC = "src/main/java/com/chaomixian/vflow/core/webdav/WebDavXmlParser.kt"
        const val URL_BUILDER_SRC = "src/main/java/com/chaomixian/vflow/core/webdav/WebDavUrlBuilder.kt"
        const val STRINGS_ZH = "src/main/res/values/strings_module.xml"
        const val STRINGS_EN = "src/main/res/values-en/strings_module.xml"
        const val STRINGS_JA = "src/main/res/values-ja/strings_module.xml"

        val REQUIRED_STRING_KEYS = listOf(
            "module_vflow_network_webdav_name", "module_vflow_network_webdav_desc",
            "param_vflow_network_webdav_config_name", "hint_vflow_network_webdav_config",
            "param_vflow_network_webdav_operation_name",
            "option_vflow_network_webdav_operation_list",
            "option_vflow_network_webdav_operation_upload",
            "option_vflow_network_webdav_operation_download",
            "option_vflow_network_webdav_operation_mkdir",
            "option_vflow_network_webdav_operation_delete",
            "param_vflow_network_webdav_remote_path_name", "hint_vflow_network_webdav_remote_path",
            "param_vflow_network_webdav_local_file_name", "hint_vflow_network_webdav_local_file",
            "param_vflow_network_webdav_save_dir_name", "hint_vflow_network_webdav_save_dir",
            "param_vflow_network_webdav_overwrite_name",
            "output_vflow_network_webdav_files_name", "output_vflow_network_webdav_count_name",
            "output_vflow_network_webdav_success_name", "output_vflow_network_webdav_error_name",
            "output_vflow_network_webdav_remote_path_name", "output_vflow_network_webdav_status_code_name",
            "output_vflow_network_webdav_file_name", "output_vflow_network_webdav_file_path_name",
            "output_vflow_network_webdav_size_name",
            "summary_vflow_network_webdav_no_config", "summary_vflow_network_webdav_config_missing",
            "summary_vflow_network_webdav_prefix_list", "summary_vflow_network_webdav_prefix_upload",
            "summary_vflow_network_webdav_prefix_download", "summary_vflow_network_webdav_prefix_mkdir",
            "summary_vflow_network_webdav_prefix_delete",
            "module_editor_action_manage_webdav",
            "error_vflow_network_webdav_validate_no_config",
            "error_vflow_network_webdav_config_not_found",
            "error_vflow_network_webdav_validate_no_remote_path",
            "error_vflow_network_webdav_validate_no_local_file",
            "error_vflow_network_webdav_operation_failed",
            "error_vflow_network_webdav_no_config_desc",
            "error_vflow_network_webdav_key_unavailable_title",
            "error_vflow_network_webdav_key_unavailable_desc",
            "error_vflow_network_webdav_unknown_operation",
            "error_vflow_network_webdav_malformed",
            "error_vflow_network_webdav_network_error",
            "error_vflow_network_webdav_no_local_file",
            "error_vflow_network_webdav_local_file_missing",
            "error_vflow_network_webdav_local_file_unreadable",
            "error_vflow_network_webdav_upload_failed",
            "error_vflow_network_webdav_remote_file_exists",
            "error_vflow_network_webdav_download_failed",
            "error_vflow_network_webdav_save_dir_failed",
            "error_vflow_network_webdav_local_file_exists",
            "error_vflow_network_webdav_mkdir_failed",
            "error_vflow_network_webdav_dir_exists",
            "error_vflow_network_webdav_delete_failed",
            "error_vflow_network_webdav_remote_not_found",
            "error_vflow_network_webdav_tls",
            "msg_vflow_network_webdav_listing",
            "msg_vflow_network_webdav_uploading",
            "msg_vflow_network_webdav_downloading",
            "msg_vflow_network_webdav_download_saved",
            "msg_vflow_network_webdav_mkdir",
            "msg_vflow_network_webdav_deleting",
        )

        /** 剥掉块注释与行注释（本模块的注释正文里大量出现被测关键字，不剥会恒绿）。 */
        fun stripComments(source: String): String {
            val noBlock = Regex("""/\*.*?\*/""", RegexOption.DOT_MATCHES_ALL).replace(source, "")
            return noBlock.lines().joinToString("\n") { line ->
                val index = line.indexOf("//")
                if (index >= 0) line.substring(0, index) else line
            }
        }

        fun read(path: String): String {
            val file = File(path)
            require(file.exists()) { "源文件不存在（测试工作目录应为 app/）：$path" }
            return file.readText()
        }
    }

    // ── 身份与分类 ──────────────────────────────────────────────

    @Test
    fun `module id is stable`() {
        assertEquals("vflow.network.webdav", module.id)
    }

    @Test
    fun `module belongs to the network category and does not create a new one`() {
        assertEquals("network", module.metadata.categoryId)
        assertEquals("network", module.metadata.getResolvedCategoryId())
    }

    // ── operation 枚举 ──────────────────────────────────────────

    @Test
    fun `operation offers exactly the five documented values`() {
        val operation = module.getInputs().first { it.id == "operation" }
        assertEquals(ParameterType.ENUM, operation.staticType)
        assertEquals(listOf("list", "upload", "download", "mkdir", "delete"), operation.options)
        assertEquals(InputStyle.CHIP_GROUP, operation.inputStyle)
        assertEquals("list", operation.defaultValue)
    }

    @Test
    fun `operation options have one localized string each`() {
        val operation = module.getInputs().first { it.id == "operation" }
        assertEquals(operation.options.size, operation.optionsStringRes.size)
    }

    @Test
    fun `legacy localized operation values are mapped to stable constants`() {
        val operation = module.getInputs().first { it.id == "operation" }
        for (alias in listOf("列出", "上传", "下载", "创建目录", "删除", "List", "Upload", "Download", "Mkdir", "Delete")) {
            val normalized = operation.normalizeEnumValue(alias, "list")
            assertTrue(
                "旧值「$alias」没有被归一化成稳定常量，实际：$normalized",
                normalized in WebDavModule.OPERATIONS,
            )
        }
    }

    // ── visibility 接线 ─────────────────────────────────────────

    private fun visibleIds(parameters: Map<String, Any?>): List<String> =
        module.getInputs()
            .filter { it.visibility?.isVisible(parameters) ?: true }
            .map { it.id }

    @Test
    fun `local file input is only visible for upload`() {
        assertTrue("local_file" in visibleIds(mapOf("operation" to "upload")))
        assertFalse("local_file" in visibleIds(mapOf("operation" to "download")))
        assertFalse("local_file" in visibleIds(mapOf("operation" to "list")))
    }

    @Test
    fun `save dir input is only visible for download`() {
        assertTrue("save_dir" in visibleIds(mapOf("operation" to "download")))
        assertFalse("save_dir" in visibleIds(mapOf("operation" to "upload")))
    }

    @Test
    fun `overwrite input is visible for upload and download only`() {
        assertTrue("overwrite" in visibleIds(mapOf("operation" to "upload")))
        assertTrue("overwrite" in visibleIds(mapOf("operation" to "download")))
        assertFalse("overwrite" in visibleIds(mapOf("operation" to "mkdir")))
        assertFalse("overwrite" in visibleIds(mapOf("operation" to "delete")))
        assertFalse("overwrite" in visibleIds(mapOf("operation" to "list")))
    }

    @Test
    fun `config and remote path are always visible`() {
        for (operation in WebDavModule.OPERATIONS) {
            val visible = visibleIds(mapOf("operation" to operation))
            assertTrue("config 必须在 $operation 下可见", "config" in visible)
            assertTrue("remote_path 必须在 $operation 下可见", "remote_path" in visible)
        }
    }

    @Test
    fun `visibility uses declarative InputVisibility rather than hidden flags`() {
        val conditional = module.getInputs().filter { it.visibility != null || it.isHidden }
        assertTrue("条件参数必须用 visibility 声明（isHidden 已废弃）", conditional.isNotEmpty())
        assertTrue("不应有参数使用已废弃的 isHidden", module.getInputs().none { it.isHidden })
    }

    // ── 输出契约 ────────────────────────────────────────────────

    private fun outputsFor(operation: String) =
        module.getOutputs(ActionStep("s", mapOf("operation" to operation)))

    @Test
    fun `list outputs the file list as a dictionary list`() {
        val files = outputsFor("list").first { it.id == "files" }
        assertEquals(VTypeRegistry.LIST.id, files.typeName)
        // ⚠️ listElementType 丢了的话，魔法变量选择器展开不了元素里的键 ——
        // 用户只能手输 files.0.name，而没有任何提示。
        assertEquals(VTypeRegistry.DICTIONARY.id, files.listElementType)
    }

    @Test
    fun `list outputs count success and error`() {
        val ids = outputsFor("list").map { it.id }
        assertEquals(listOf("files", "count", "success", "error"), ids)
    }

    @Test
    fun `upload outputs success remote path status and error`() {
        assertEquals(listOf("success", "remote_path", "status_code", "error"), outputsFor("upload").map { it.id })
    }

    @Test
    fun `download outputs the file handle path and size`() {
        val outputs = outputsFor("download")
        assertEquals(listOf("success", "file", "file_path", "size", "status_code", "error"), outputs.map { it.id })
        assertEquals(VTypeRegistry.FILE.id, outputs.first { it.id == "file" }.typeName)
    }

    @Test
    fun `mkdir outputs success remote path status and error`() {
        assertEquals(listOf("success", "remote_path", "status_code", "error"), outputsFor("mkdir").map { it.id })
    }

    @Test
    fun `delete outputs success status and error`() {
        assertEquals(listOf("success", "status_code", "error"), outputsFor("delete").map { it.id })
    }

    @Test
    fun `legacy operation value still yields the right outputs`() {
        // 防「归一化只写在了渲染路径、读参数时漏了」⇒ 输出表与 operation 不匹配。
        val outputs = module.getOutputs(ActionStep("s", mapOf("operation" to "下载")))
        assertTrue(outputs.any { it.id == "file" })
    }

    // ── AI 元数据 ───────────────────────────────────────────────

    @Test
    fun `ai metadata is temporary workflow only and never a direct tool`() {
        val ai = module.aiMetadata
        assertNotNull(ai)
        // ⚠️⚠️ 本模块能删远端文件、覆盖服务器上的东西 ⇒ **绝不能**让 AI 直接调用。
        assertFalse(
            "WebDAV 模块不得开放 DIRECT_TOOL",
            ai!!.usageScopes.contains(AiModuleUsageScope.DIRECT_TOOL),
        )
        assertEquals(setOf(AiModuleUsageScope.TEMPORARY_WORKFLOW), ai.usageScopes)
    }

    @Test
    fun `ai risk level is HIGH`() {
        // ⚠️ `AiModuleMetadata` 是模块上的静态 val（签名里没有 step）⇒ 无法按 operation 分档，
        // 取保守侧 HIGH。这条锁住「将来有人把它调低」。
        assertEquals(AiModuleRiskLevel.HIGH, module.aiMetadata!!.riskLevel)
    }

    @Test
    fun `ai hints mention the config name must match exactly`() {
        // 配置选择存的是「名字」⇒ AI 必须知道它得逐字匹配，否则生成的临时工作流永远失配。
        val hints = module.aiMetadata!!.inputHints
        assertTrue(hints.getValue("config").contains("Must match exactly", ignoreCase = true))
        assertTrue(hints.getValue("operation").contains("list"))
    }

    @Test
    fun `ai required input ids cover config operation and path`() {
        assertEquals(setOf("config", "operation", "remote_path"), module.aiMetadata!!.requiredInputIds)
    }

    // ── 源码扫描：动态 options 的防护（R6）───────────────────────

    @Test
    fun `dynamic inputs never touches appContext outside of a try catch`() {
        val source = stripComments(read(MODULE_SRC))

        // 防空转：剥注释后仍应有足够的代码行。
        assertTrue("剥注释后源码过短，扫描可能失效", source.lines().count { it.isNotBlank() } > 100)

        // ⚠️⚠️ 这条锁的是 R6：`getDynamicInputs` 的调用方是**步骤参数面板**，
        // 抛出去 = 整屏不可用。而 `appContext` 是 lateinit（未 initContext 时读它是
        // UninitializedPropertyAccessException），必须靠 catch 兜住。
        val body = functionBody(source, "private fun safeConfigNames()")
        assertNotNull("未找到 safeConfigNames 函数体", body)
        assertTrue(
            "safeConfigNames 必须 catch(Throwable) —— 否则 appContext 未注入时参数面板整屏崩",
            body!!.contains("catch (_: Throwable)") || body.contains("catch (e: Throwable)"),
        )
        assertTrue("safeConfigNames 必须真的读 WebDavConfigStore", body.contains("WebDavConfigStore"))
    }

    @Test
    fun `getDynamicInputs returns the static inputs when there are no configs`() {
        val source = stripComments(read(MODULE_SRC))
        val body = functionBody(source, "override fun getDynamicInputs(")
        assertNotNull(body)
        // 空 options 的 ENUM 在渲染层会得到空下拉 —— 但**不能**因此抛异常。
        assertTrue("getDynamicInputs 不应直接触碰 appContext", !body!!.contains("appContext"))
    }

    @Test
    fun `module never rewrites the saved config name into the options list`() {
        // ⚠️ D3 的镜子：把已保存的名字塞回 options 会造出「幽灵条目」——
        // 用户删了配置，下拉里却还显示它，看着像配置还在。
        val source = stripComments(read(MODULE_SRC))
        assertFalse(
            "不得把已保存的配置名塞进 options（幽灵条目）",
            source.contains("safeConfigNames() +"),
        )
    }

    // ── 源码扫描：validate / 三处失配显式失败 ───────────────────

    @Test
    fun `validate is overridden because the base implementation always returns valid`() {
        val source = stripComments(read(MODULE_SRC))
        val body = functionBody(source, "override fun validate(")
        assertNotNull("必须覆写 validate —— BaseModule 的默认实现是恒 true", body)
        assertTrue("validate 必须检查配置是否存在", body!!.contains("configExists"))
        assertTrue("validate 必须返回 ValidationResult(false, ...)", body.contains("ValidationResult(false"))
        assertTrue("validate 必须校验 remote_path", body.contains("REMOTE_PATH_ID"))
    }

    @Test
    fun `summary renders a visible anomaly when the config is gone`() {
        val source = stripComments(read(MODULE_SRC))
        val body = functionBody(source, "override fun getSummary(")
        assertNotNull(body)
        assertTrue(
            "getSummary 必须对「配置不存在」单独分支（否则摘要看着一切正常）",
            body!!.contains("summary_vflow_network_webdav_config_missing"),
        )
    }

    @Test
    fun `execute maps a missing config to a failure that names it`() {
        val source = stripComments(read(MODULE_SRC))
        val body = functionBody(source, "override suspend fun execute(")
        assertNotNull(body)
        assertTrue("执行期必须对找不到的配置显式失败", body!!.contains("error_vflow_network_webdav_config_not_found"))
    }

    @Test
    fun `execute handles key unavailability in its own branch`() {
        val source = stripComments(read(MODULE_SRC))
        val body = functionBody(source, "override suspend fun execute(")!!
        // ⚠️ 密钥失效若落进泛 Exception 会变成「WebDAV 操作失败」，用户会去查服务器地址 ——
        // 而正确处置是「重新输入密码」。T3 已在 testConnection 建立这个口径。
        assertTrue("必须单独 catch CryptoKeyUnavailableException", body.contains("CryptoKeyUnavailableException"))
        assertTrue(body.contains("error_vflow_network_webdav_key_unavailable_title"))
    }

    @Test
    fun `execute maps a malformed multistatus to failure instead of an empty list`() {
        val source = stripComments(read(MODULE_SRC))
        val body = functionBody(source, "private suspend fun doList(")
        assertNotNull(body)
        // ⚠️⚠️ 空目录 vs 解析失败必须区分：混淆的表现是「列表永远是空的」且无任何错误提示。
        assertTrue("Malformed 必须映射成 Failure", body!!.contains("WebDavParseResult.Malformed"))
        assertTrue(body.contains("error_vflow_network_webdav_malformed"))
    }

    // ── 源码扫描：协议层不变量被真正用上 ─────────────────────────

    @Test
    fun `module delegates url building instead of concatenating strings`() {
        val source = stripComments(read(MODULE_SRC))
        // ⚠️ 模块侧不得自己拼 URL（否则绕开百分号编码与 .. 拦截两道防线）。
        assertFalse(
            "模块里不得出现 baseUrl + \"/\" 之类的字符串拼接",
            Regex("""(baseUrl|config\.baseUrl)\s*\+""").containsMatchIn(source),
        )
    }

    @Test
    fun `xml parser stays namespace aware and never matches by prefix string`() {
        val source = stripComments(read(XML_PARSER_SRC))
        assertTrue("必须设 namespaceAware", source.contains("setNamespaceAwareSafely(true)") || source.contains("isNamespaceAware = true"))
        assertTrue("必须按 namespaceURI + localName 匹配", source.contains("element.namespaceURI == ns && element.localName == localName"))
        // ⚠️ 反向断言：任何按前缀字符串的比较都是 bug（服务器换前缀就静默解析不到）。
        assertFalse(
            "不得出现 \"D:\" + name 形式的前缀匹配",
            Regex("""nodeName\s*==\s*"D:"""").containsMatchIn(source),
        )
        assertFalse(Regex("""getElementsByTagName\("D:""").containsMatchIn(source))
    }

    @Test
    fun `url builder rejects dot dot segments`() {
        val source = stripComments(read(URL_BUILDER_SRC))
        // ⚠️ HttpUrl 对 `..` 是**静默上跳**（实测），必须我方拦截。
        assertTrue(source.contains("segment == \".\" || segment == \"..\""))
        assertTrue("必须用 addPathSegment 而不是字符串拼接", source.contains("addPathSegment"))
    }

    @Test
    fun `url builder has no string concatenation of path segments`() {
        val source = stripComments(read(URL_BUILDER_SRC))
        assertFalse(Regex("""baseUrl\s*\+""").containsMatchIn(source))
    }

    // ── 源码扫描：注册点 ────────────────────────────────────────

    @Test
    fun `module is registered in ModuleRegistry exactly once inside the network section`() {
        // ⚠️ 本用例刻意用**未剥注释**的源码：两个锚点本身就是行注释
        //（`// 网络` / `// 应用与系统`），剥掉之后 indexOf 恒为 -1、断言会失去意义。
        val source = read(REGISTRY_SRC)
        val occurrences = Regex("""register\(WebDavModule\(\)""").findAll(source).count()
        assertEquals("WebDavModule 应恰好注册一次", 1, occurrences)

        val networkIndex = source.indexOf("// 网络")
        val webDavIndex = source.indexOf("register(WebDavModule()")
        val systemIndex = source.indexOf("// 应用与系统")
        assertTrue("未找到「// 网络」锚点，扫描已失效", networkIndex > 0)
        assertTrue("未找到「// 应用与系统」锚点，扫描已失效", systemIndex > networkIndex)

        assertTrue("注册行应位于「// 网络」段之后", webDavIndex > networkIndex)
        assertTrue(
            "注册行应追加在网络段末尾（「// 应用与系统」之前）—— 不得重排既有注册",
            webDavIndex < systemIndex,
        )
    }

    @Test
    fun `module is not added to any other registration site`() {
        val registry = stripComments(read(REGISTRY_SRC))
        // 防「顺手在新分类里也注册一次」⇒ 模块列表出现两个同名项。
        assertEquals(1, Regex("""WebDavModule\(""").findAll(registry).count())
    }

    // ── 三语字符串 ──────────────────────────────────────────────

    @Test
    fun `all three locales define every string used by the module`() {
        for (path in listOf(STRINGS_ZH, STRINGS_EN, STRINGS_JA)) {
            val content = read(path)
            for (key in REQUIRED_STRING_KEYS) {
                assertTrue("$path 缺少字符串 $key", content.contains("""name="$key""""))
            }
        }
    }

    @Test
    fun `string keys are not duplicated inside a file`() {
        // ⚠️ 重复键会让构建**直接失败**（Resource and asset merger），
        // 而报错信息只给资源名，定位很慢 ⇒ 这里提前锁住。
        for (path in listOf(STRINGS_ZH, STRINGS_EN, STRINGS_JA)) {
            val content = read(path)
            for (key in REQUIRED_STRING_KEYS) {
                val count = Regex("""name="$key"""").findAll(content).count()
                assertEquals("$path 里 $key 出现了 $count 次", 1, count)
            }
        }
    }

    @Test
    fun `the required key list itself is not empty`() {
        assertTrue(REQUIRED_STRING_KEYS.size > 60)
    }

    // ── 本地文件来源分类（SAF 主路径）────────────────────────────

    @Test
    fun `content uri from the SAF picker is recognised as uploadable`() {
        // ⚠️⚠️ 编辑器的 PickerType.FILE 走 ACTION_GET_CONTENT，选完很可能是 `content://`。
        // 早先把它一律当「没选文件」⇒ 用户明明选了却报「未选择要上传的本地文件」，
        // 而这是**主路径**、不是边缘情况。
        val source = module.classifyLocalSource("content://com.android.providers.media.documents/document/image%3A42")
        assertTrue("content:// 必须被识别为可上传来源，实际：$source", source is WebDavLocalSource.ContentUri)
    }

    @Test
    fun `plain and file uri paths are recognised as file paths`() {
        assertEquals(WebDavLocalSource.FilePath("/sdcard/a.txt"), module.classifyLocalSource("/sdcard/a.txt"))
        assertEquals(WebDavLocalSource.FilePath("/sdcard/a.txt"), module.classifyLocalSource("file:///sdcard/a.txt"))
    }

    @Test
    fun `blank local source is null`() {
        assertNull(module.classifyLocalSource(""))
        assertNull(module.classifyLocalSource("   "))
    }

    @Test
    fun `local source classification trims surrounding whitespace`() {
        assertEquals(WebDavLocalSource.FilePath("/sdcard/a.txt"), module.classifyLocalSource("  /sdcard/a.txt  "))
    }

    // ── 工具 ────────────────────────────────────────────────────

    /** 按大括号配对截取函数体（不靠缩进、不用正则 —— 函数体里有字符串与嵌套块）。 */
    private fun functionBody(source: String, signature: String): String? {
        val start = source.indexOf(signature)
        if (start < 0) return null
        val open = source.indexOf('{', start)
        if (open < 0) return null

        var depth = 0
        var index = open
        while (index < source.length) {
            when (source[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return source.substring(open, index + 1)
                }
            }
            index++
        }
        return null
    }
}
