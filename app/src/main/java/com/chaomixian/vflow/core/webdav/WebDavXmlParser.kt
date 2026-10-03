package com.chaomixian.vflow.core.webdav

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 一条 WebDAV 资源（`PROPFIND` 的 207 Multi-Status 里一个 `<response>`）。
 *
 * @param href 原始 href。**可能是绝对 URL（`https://host/dav/a`）或绝对路径（`/dav/a`）**，
 *             且**可能百分号编码**（服务器常把空格回成 `%20`）—— 本类**不做任何归一化**，
 *             由调用方按需处理（`WebDavModule` 用它算相对路径、过滤 self 条目）。
 * @param displayName `<displayname>`，缺失时回落空串（调用方按 href 兜底）。
 * @param isCollection `<resourcetype>` 下有 `DAV:collection` ⇒ 目录。
 * @param lastModified 原样字符串（**不解析成时间**：格式有 RFC1123、也有 ISO8601，解析失败的代价是静默丢弃）。
 */
data class WebDavResource(
    val href: String,
    val displayName: String,
    val isCollection: Boolean,
    val contentLength: Long?,
    val contentType: String?,
    val lastModified: String?,
)

sealed interface WebDavParseResult {
    data class Success(val resources: List<WebDavResource>) : WebDavParseResult

    /**
     * 畸形 XML / 根本不是 multistatus。
     *
     * ⚠️⚠️ **本类永不抛异常**，故意把「解析失败」表达成一个返回值 —— 调用方必须把它映射成
     * **失败**，**绝不能**当成「空目录」。空目录与解析失败在用户侧是两件完全不同的事
     * （前者正常、后者要去查服务器），混同的表现是「列表永远是空的」而没有任何错误提示。
     */
    data class Malformed(val detail: String?) : WebDavParseResult
}

/**
 * `PROPFIND` 207 响应体的解析器（纯 JVM，不依赖 Android / OkHttp）。
 *
 * ## ⚠️⚠️ 本类存在的第一个理由：**必须 namespace-aware，绝不按前缀字符串匹配**
 *
 * `DAV:` 是**命名空间 URI**，而 XML 前缀只是个**可任意起的别名**。同一份响应，不同服务器
 * 会写成：
 *
 * ```xml
 * <D:multistatus xmlns:D="DAV:"> …            <!-- Apache mod_dav -->
 * <d:multistatus xmlns:d="DAV:"> …            <!-- Nextcloud -->
 * <ns0:multistatus xmlns:ns0="DAV:"> …        <!-- 某些 .NET / Java 服务端 -->
 * <multistatus xmlns="DAV:"> …                <!-- 默认命名空间，无前缀 -->
 * ```
 *
 * 四种**语义完全相同**。按 `"D:href"` 这样的前缀字符串匹配的解析器在前三种里能撞对一两种，
 * 换个服务器就**静默解析出空列表**（不报错，只是「目录看着是空的」）。
 *
 * ⇒ 本类一律用 `getElementsByTagNameNS(DAV_NS, localName)`，它按 `(namespaceURI, localName)`
 * 匹配，**与服务器用的前缀无关**。切换前缀的用例有单测锁（`WebDavXmlParserTest`）。
 *
 * ## ⚠️ 第二个理由：`propstat` 可能有两份
 *
 * 服务器可以把「取到的属性」与「没取到的属性」分成两个 `propstat`，各带自己的 `status`
 * （通常是 `200 OK` 与 `404 Not Found`）。直接取**第一个** `prop` 有 50% 概率拿到那份
 * 「什么都没有」的 404 分支 ⇒ 所有字段都是空。⇒ 本类**优先选 `status` 含 200 的那份**。
 */
object WebDavXmlParser {

    const val DAV_NS = "DAV:"

    /** 匹配 `HTTP/1.1 200 OK` / `HTTP/1.1 200` / `200 OK` 这类 status 文本。 */
    private val OK_STATUS = Regex("""\b200\b""")

    /**
     * 解析 207 Multi-Status 响应体。
     *
     * ⚠️ 直接传 `null` 或空串 ⇒ `Malformed`，与「畸形 XML」同类。
     * ⚠️ **不做「过滤自身集合条目」** —— 解析器保持中立、返回全部条目；过滤由调用方做
     * （它才知道请求的是哪个目录）。
     */
    fun parseMultiStatus(xml: String?): WebDavParseResult {
        if (xml.isNullOrBlank()) {
            return WebDavParseResult.Malformed("响应体为空")
        }

        val doc: Document = try {
            parseSecurely(xml)
        } catch (e: Exception) {
            // ⚠️ 宽 catch：不同 XML 实现对畸形输入抛的异常类型不一致
            //（SAXParseException / SAXException / IOException / RuntimeException 包装等）。
            return WebDavParseResult.Malformed("XML 解析失败：${e.message}")
        }

        val root = doc.documentElement
            ?: return WebDavParseResult.Malformed("响应体没有根元素")

        // ⚠️ 判根元素用「命名空间 + 本地名」，同样不管前缀。
        if (root.namespaceURI != DAV_NS || root.localName != "multistatus") {
            return WebDavParseResult.Malformed("根元素不是 DAV:multistatus（实际：${describe(root)}）")
        }

        // ⚠️⚠️ 只取 multistatus 的**直接子** response。
        // 用 getElementsByTagNameNS 会搜遍全文档，把嵌套结构里同名的 response 也算进来
        //（某些服务器会在 response 里嵌 <D:response> 用于其他用途）。
        val responses = directChildren(root, DAV_NS, "response")
        val resources = responses.mapNotNull { parseResponse(it) }

        return WebDavParseResult.Success(resources)
    }

    /** 一个 response ⇒ 一条资源。取不到 href 时返回 null（那一条无法定位，留着只会污染列表）。 */
    private fun parseResponse(response: Element): WebDavResource? {
        val href = directChildText(response, DAV_NS, "href") ?: return null

        val prop = selectPropElement(response)

        val resourceType = prop?.let { directChildren(it, DAV_NS, "resourcetype").firstOrNull() }
        val isCollection = resourceType != null &&
            directChildren(resourceType, DAV_NS, "collection").isNotEmpty()

        return WebDavResource(
            href = href,
            displayName = prop?.let { directChildText(it, DAV_NS, "displayname") }.orEmpty(),
            isCollection = isCollection,
            contentLength = prop?.let { directChildText(it, DAV_NS, "getcontentlength") }
                ?.trim()
                ?.toLongOrNull(),
            contentType = prop?.let { directChildText(it, DAV_NS, "getcontenttype") }?.trim()?.ifEmpty { null },
            lastModified = prop?.let { directChildText(it, DAV_NS, "getlastmodified") }?.trim()?.ifEmpty { null },
        )
    }

    /**
     * 选出该 response 里「有效」的那份 `prop`。
     *
     * 优先 `status` 含 200 的 `propstat`；没有（或者 status 缺失）则退回第一个 `propstat`。
     */
    private fun selectPropElement(response: Element): Element? {
        val propstats = directChildren(response, DAV_NS, "propstat")
        if (propstats.isEmpty()) return null

        val ok = propstats.firstOrNull { propstat ->
            val status = directChildText(propstat, DAV_NS, "status").orEmpty()
            OK_STATUS.containsMatchIn(status)
        }

        return (ok ?: propstats.first()).let { directChildren(it, DAV_NS, "prop").firstOrNull() }
    }

    // ── DOM 小工具（都按「直接子元素」取，避免误取深层嵌套的同名元素）──────

    private fun directChildren(parent: Element, ns: String, localName: String): List<Element> {
        val result = mutableListOf<Element>()
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            // ⚠️ 比 namespaceURI 而非前缀；localName 在非 namespace-aware 解析下会是 null，
            // 而本类的工厂**强制** namespaceAware=true，故这里可信。
            if (element.namespaceURI == ns && element.localName == localName) {
                result.add(element)
            }
        }
        return result
    }

    private fun directChildText(parent: Element, ns: String, localName: String): String? =
        directChildren(parent, ns, localName).firstOrNull()?.textContent

    private fun describe(element: Element): String =
        if (element.prefix.isNullOrEmpty()) element.tagName else "${element.tagName} (ns=${element.namespaceURI})"

    /**
     * 安全地建 DOM。
     *
     * 配置与 `ParseXmlModule.parseXmlSecurely` 同源（那份已在本仓库跑了一段时间）：
     * 关 DTD / 外部实体 / XInclude，开 `FEATURE_SECURE_PROCESSING`。
     * ⚠️ 每个 `setFeature` 都用 `runCatching` —— 不同 XML 实现（Android 上是 Expat 系）
     * 对某些 feature 名不支持，会抛 `ParserConfigurationException`，不包就会把
     * 「服务器返回的 XML 有点怪」变成「App 崩了」。
     */
    private fun parseSecurely(xml: String): Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            setNamespaceAwareSafely(true)
            setXIncludeAwareSafely(false)
            setExpandEntityReferencesSafely(false)
            setSafeFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setSafeFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setSafeFeature("http://xml.org/sax/features/external-general-entities", false)
            setSafeFeature("http://xml.org/sax/features/external-parameter-entities", false)
            setSafeFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        }

        return factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    }

    private fun DocumentBuilderFactory.setNamespaceAwareSafely(value: Boolean) {
        runCatching { isNamespaceAware = value }
    }

    private fun DocumentBuilderFactory.setXIncludeAwareSafely(value: Boolean) {
        runCatching { isXIncludeAware = value }
    }

    private fun DocumentBuilderFactory.setExpandEntityReferencesSafely(value: Boolean) {
        runCatching { isExpandEntityReferences = value }
    }

    private fun DocumentBuilderFactory.setSafeFeature(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}
