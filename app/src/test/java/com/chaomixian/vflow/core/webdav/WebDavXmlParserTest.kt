package com.chaomixian.vflow.core.webdav

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebDavXmlParser] 的回归测试。
 *
 * ## ⚠️⚠️ 本文件的头号作用是「锁住 namespace-aware」
 *
 * `DAV:` 是**命名空间 URI**，前缀只是别名。同一份响应换个服务器就换个前缀 ——
 * 而**按前缀字符串匹配的解析器换了前缀会静默解析出空列表**（不报错、只是「目录看着是空的」）。
 *
 * 故本文件把同一份内容用 **四种前缀** 各写一遍（`D:` / `d:` / `ns0:` / 默认 ns），
 * 断言**四次解析结果逐字段相同**。
 *
 * ## ⚠️ 必做反证（父会话点名）
 *
 * 把 [WebDavXmlParser] 改成按前缀匹配（例如把取 href 改成 `getElementsByTagName("D:href")`）
 * ⇒ 换前缀的用例**变红**。
 *
 * ## 另一条不变量：解析失败 ≠ 空目录
 *
 * 畸形 XML 必须返回 [WebDavParseResult.Malformed] 而**不是** `Success(emptyList())`。
 * 二者混同的表现是「列表永远是空的」而没有任何错误提示，用户会去查服务器配置。
 */
class WebDavXmlParserTest {

    // ── 四种前缀（同一份内容的四次改写）────────────────────────

    /** Apache mod_dav 风格：`D:` */
    private val withDPrefix = """
        <?xml version="1.0" encoding="utf-8"?>
        <D:multistatus xmlns:D="DAV:">
          <D:response>
            <D:href>/dav/</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype><D:collection/></D:resourcetype>
                <D:displayname>根目录</D:displayname>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
          <D:response>
            <D:href>/dav/a%20b.txt</D:href>
            <D:propstat>
              <D:prop>
                <D:resourcetype/>
                <D:displayname>a b.txt</D:displayname>
                <D:getcontentlength>1234</D:getcontentlength>
                <D:getcontenttype>text/plain</D:getcontenttype>
                <D:getlastmodified>Wed, 01 Oct 2026 10:00:00 GMT</D:getlastmodified>
              </D:prop>
              <D:status>HTTP/1.1 200 OK</D:status>
            </D:propstat>
          </D:response>
        </D:multistatus>
    """.trimIndent()

    /** Nextcloud 风格：`d:`（**小写前缀**，最容易骗过按前缀匹配的实现） */
    private val withLowerDPrefix = withDPrefix
        .replace("<D:", "<d:").replace("</D:", "</d:").replace("xmlns:D=", "xmlns:d=")

    /** .NET / 某些 Java 服务端风格：`ns0:` */
    private val withNs0Prefix = withDPrefix
        .replace("<D:", "<ns0:").replace("</D:", "</ns0:").replace("xmlns:D=", "xmlns:ns0=")

    /** 默认命名空间（**完全不出现前缀**）—— 前缀匹配实现在这里必然全灭 */
    private val withDefaultNs = withDPrefix
        .replace("<D:", "<").replace("</D:", "</").replace(""" xmlns:D="DAV:"""", """ xmlns="DAV:"""")

    private fun resourcesOf(xml: String): List<WebDavResource> {
        val result = WebDavXmlParser.parseMultiStatus(xml)
        require(result is WebDavParseResult.Success) { "期望解析成功，实际：$result" }
        return result.resources
    }

    // ── 四种前缀逐一 ────────────────────────────────────────────

    @Test
    fun `parses with capital D prefix`() {
        assertCanonical(resourcesOf(withDPrefix))
    }

    @Test
    fun `parses with lowercase d prefix`() {
        assertCanonical(resourcesOf(withLowerDPrefix))
    }

    @Test
    fun `parses with ns0 prefix`() {
        assertCanonical(resourcesOf(withNs0Prefix))
    }

    @Test
    fun `parses with default namespace and no prefix at all`() {
        assertCanonical(resourcesOf(withDefaultNs))
    }

    @Test
    fun `all four prefixes yield identical results`() {
        val a = resourcesOf(withDPrefix)
        val b = resourcesOf(withLowerDPrefix)
        val c = resourcesOf(withNs0Prefix)
        val d = resourcesOf(withDefaultNs)
        assertEquals(a, b)
        assertEquals(a, c)
        assertEquals(a, d)
    }

    /** 四份文档共用的字段级断言（换前缀时任何一条不成立都说明解析器在看前缀）。 */
    private fun assertCanonical(resources: List<WebDavResource>) {
        assertEquals(2, resources.size)

        val dir = resources[0]
        assertEquals("/dav/", dir.href)
        assertTrue("根目录必须被判定为 collection", dir.isCollection)
        assertEquals("根目录", dir.displayName)
        assertNull(dir.contentLength)

        val file = resources[1]
        // ⚠️ href 原样保留（含百分号编码）—— 归一化是调用方的事。
        assertEquals("/dav/a%20b.txt", file.href)
        assertFalse(file.isCollection)
        assertEquals("a b.txt", file.displayName)
        assertEquals(1234L, file.contentLength)
        assertEquals("text/plain", file.contentType)
        assertEquals("Wed, 01 Oct 2026 10:00:00 GMT", file.lastModified)
    }

    // ── collection 判定 ─────────────────────────────────────────

    @Test
    fun `collection detection does not depend on prefix`() {
        // 四种前缀下 resourcetype 里的 collection 都要认出来。
        for (xml in listOf(withDPrefix, withLowerDPrefix, withNs0Prefix, withDefaultNs)) {
            assertTrue("前缀变体下 collection 判定失效", resourcesOf(xml)[0].isCollection)
        }
    }

    @Test
    fun `empty resourcetype is a file`() {
        assertFalse(resourcesOf(withDPrefix)[1].isCollection)
    }

    @Test
    fun `collection with extra children is still a collection`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/dir</D:href>
                <D:propstat><D:prop>
                  <D:resourcetype><D:collection/><D:principal/></D:resourcetype>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertTrue(resourcesOf(xml)[0].isCollection)
    }

    // ── 空 / 畸形（**绝不抛异常**）──────────────────────────────

    @Test
    fun `empty multistatus is a success with no resources`() {
        val xml = """<?xml version="1.0"?><D:multistatus xmlns:D="DAV:"></D:multistatus>"""
        val result = WebDavXmlParser.parseMultiStatus(xml)
        assertTrue(result is WebDavParseResult.Success)
        assertTrue((result as WebDavParseResult.Success).resources.isEmpty())
    }

    @Test
    fun `malformed xml returns Malformed and does not throw`() {
        val result = WebDavXmlParser.parseMultiStatus("<D:multistatus xmlns:D=\"DAV:\"><D:response>")
        assertTrue("畸形 XML 必须是 Malformed，实际：$result", result is WebDavParseResult.Malformed)
    }

    @Test
    fun `plain html error page returns Malformed`() {
        // 反向代理返回的 HTML 错误页（真实场景：地址填错指向了某个网站首页）。
        val result = WebDavXmlParser.parseMultiStatus("<html><body>404 Not Found</body></html>")
        assertTrue(result is WebDavParseResult.Malformed)
    }

    @Test
    fun `blank input returns Malformed`() {
        assertTrue(WebDavXmlParser.parseMultiStatus("") is WebDavParseResult.Malformed)
        assertTrue(WebDavXmlParser.parseMultiStatus("   ") is WebDavParseResult.Malformed)
        assertTrue(WebDavXmlParser.parseMultiStatus(null) is WebDavParseResult.Malformed)
    }

    @Test
    fun `xml with a different default namespace is not a multistatus`() {
        // ⚠️ 只有本地名对、命名空间不对 ⇒ 必须判 Malformed。
        // 这一条锁住「不会退化成只比 localName」。
        val xml = """<?xml version="1.0"?><multistatus xmlns="urn:not-dav:"><response/></multistatus>"""
        assertTrue(WebDavXmlParser.parseMultiStatus(xml) is WebDavParseResult.Malformed)
    }

    // ── propstat 200 / 404 并存 ─────────────────────────────────

    @Test
    fun `prefers the 200 propstat over the 404 one`() {
        // ⚠️ 服务器可把「取到的属性」与「没取到的属性」拆成两个 propstat。
        // 取错那一份 ⇒ 所有字段都是空，且没有任何报错。
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/x.txt</D:href>
                <D:propstat>
                  <D:prop><D:displayname/></D:prop>
                  <D:status>HTTP/1.1 404 Not Found</D:status>
                </D:propstat>
                <D:propstat>
                  <D:prop>
                    <D:displayname>x.txt</D:displayname>
                    <D:getcontentlength>42</D:getcontentlength>
                  </D:prop>
                  <D:status>HTTP/1.1 200 OK</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()

        val resource = resourcesOf(xml).single()
        assertEquals("x.txt", resource.displayName)
        assertEquals(42L, resource.contentLength)
    }

    @Test
    fun `falls back to the first propstat when no 200 is present`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/x.txt</D:href>
                <D:propstat>
                  <D:prop><D:displayname>x.txt</D:displayname></D:prop>
                  <D:status>HTTP/1.1 403 Forbidden</D:status>
                </D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals("x.txt", resourcesOf(xml).single().displayName)
    }

    // ── 字段缺失 / 边界 ─────────────────────────────────────────

    @Test
    fun `response without href is dropped`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:propstat><D:prop/></D:propstat></D:response>
              <D:response><D:href>/dav/ok.txt</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        val resources = resourcesOf(xml)
        assertEquals(1, resources.size)
        assertEquals("/dav/ok.txt", resources[0].href)
    }

    @Test
    fun `missing optional fields become null or empty instead of crashing`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/bare.txt</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        val resource = resourcesOf(xml).single()
        assertEquals("", resource.displayName)
        assertNull(resource.contentLength)
        assertNull(resource.contentType)
        assertNull(resource.lastModified)
        assertFalse(resource.isCollection)
    }

    @Test
    fun `non numeric content length is null not a crash`() {
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>/dav/x</D:href>
                <D:propstat><D:prop><D:getcontentlength>abc</D:getcontentlength></D:prop>
                <D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        assertNull(resourcesOf(xml).single().contentLength)
    }

    @Test
    fun `absolute href is preserved verbatim`() {
        // 服务器可能回绝对 URL 而不是绝对路径 —— 解析器不做归一化，原样交给调用方。
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response><D:href>https://dav.example.com/dav/x.txt</D:href></D:response>
            </D:multistatus>
        """.trimIndent()
        assertEquals("https://dav.example.com/dav/x.txt", resourcesOf(xml).single().href)
    }

    @Test
    fun `nested response inside prop is not double counted`() {
        // ⚠️ 用 getElementsByTagNameNS 会搜遍全文档、把嵌套的 response 也算进来。
        // 本类只取 multistatus 的直接子 ⇒ 嵌套的那个必须被忽略。
        val xml = """
            <?xml version="1.0"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/a</D:href>
                <D:propstat><D:prop>
                  <D:response><D:href>/dav/nested</D:href></D:response>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
        val resources = resourcesOf(xml)
        assertEquals(1, resources.size)
        assertEquals("/dav/a", resources[0].href)
    }

    @Test
    fun `doctype is rejected rather than resolved`() {
        // 安全配置：disallow-doctype-decl。带 DOCTYPE 的输入应被判 Malformed 而不是去解析实体。
        val xml = """
            <?xml version="1.0"?>
            <!DOCTYPE multistatus [ <!ENTITY xxe SYSTEM "file:///etc/passwd"> ]>
            <D:multistatus xmlns:D="DAV:"><D:response><D:href>/dav/&xxe;</D:href></D:response></D:multistatus>
        """.trimIndent()
        val result = WebDavXmlParser.parseMultiStatus(xml)
        assertTrue("带 DOCTYPE 的输入必须被拒绝（或至少不解析实体），实际：$result", result is WebDavParseResult.Malformed)
    }
}
