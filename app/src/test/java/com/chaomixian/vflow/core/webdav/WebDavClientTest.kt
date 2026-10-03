package com.chaomixian.vflow.core.webdav

import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * [WebDavClient] 的传输层测试（MockWebServer，纯 JVM）。
 *
 * ## ⚠️ 为什么重定向是这批用例的重点
 *
 * `followRedirects(false)` + 自己转发这条路径有**四个**各自的静默失败点：
 *
 * | 失败点 | 表现 |
 * |---|---|
 * | 忘了自己转发（只发一次请求） | 收到 302，判成 `HttpError(302)` —— 用户看到「HTTP 302」而不知道去哪 |
 * | 转发时用了 `.get()` | 方法被降级 ⇒ `PROPFIND` 变 `GET`，服务器回 HTML，解析失败 ⇒「列表是空的」 |
 * | 跨 host 仍带 `Authorization` | **凭据泄漏**给第三方主机（安全缺陷，且用户完全无感） |
 * | 没有跳数上限 | 重定向环把工作流线程**永久挂住** |
 *
 * ⚠️ **本文件是 T4 新增的独立测试**：T3 已有 `WebDavProbeTest`，父会话 D1 裁决
 * 「不跨任务改他人文件」⇒ 这里**不碰**那份，重定向用例在本文件内自成一套。
 *
 * ## ⚠️ 必做反证
 *
 * 把 [WebDavClient] 的 `followRedirects(false)` 改回 `true` ⇒ 跳数相关用例**变红**
 * （OkHttp 会自己跟到底，我们那条逐跳循环不再执行，`hops` 恒为 0、
 * `redirectLoopBeyondMaxHops` 也不再由我们的上限拦下）。
 */
class WebDavClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(
        username: String = "",
        password: String = "",
        timeoutSeconds: Int = 15,
    ) = WebDavClient(
        baseUrl = server.url("/dav").toString().trimEnd('/'),
        username = username,
        password = password,
        timeoutSeconds = timeoutSeconds,
    )

    private fun takeRequest(): RecordedRequest = server.takeRequest(3, TimeUnit.SECONDS)!!

    // ── 五个动词的方法名与头部 ──────────────────────────────────

    @Test
    fun `propfind sends the right method depth and body`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        val result = client().propfind("", "sub", directory = true, depth = 1)

        assertTrue("期望 Success，实际 $result", result is WebDavResult.Success)
        val request = takeRequest()
        assertEquals("PROPFIND", request.method)
        assertEquals("/dav/sub/", request.path)
        assertEquals("1", request.getHeader("Depth"))
        val body = request.body.readUtf8()
        assertTrue("body 必须请求 resourcetype: $body", body.contains("resourcetype"))
        // ⚠️ body 少要一个字段 ⇒ 列表里那一列**永远是空**且没有任何报错。
        // 五个字段与 WebDavXmlParser 的消费面一一对应。
        for (field in listOf("displayname", "getcontentlength", "getcontenttype", "getlastmodified")) {
            assertTrue("PROPFIND body 缺少 $field", body.contains(field))
        }
        assertTrue("Content-Type 必须由 RequestBody 自带", request.getHeader("Content-Type")!!.startsWith("application/xml"))
    }

    @Test
    fun `propfind depth zero is honoured`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
        client().propfind("", "", directory = true, depth = 0)
        assertEquals("0", takeRequest().getHeader("Depth"))
    }

    @Test
    fun `propfind depth is clamped into 0 or 1`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))
        client().propfind("", "", directory = true, depth = 7)
        assertEquals("1", takeRequest().getHeader("Depth"))
    }

    @Test
    fun `put sends the file bytes with the right method`() {
        server.enqueue(MockResponse().setResponseCode(201))

        val result = client().put("", "a.txt", "hello 世界".toRequestBody())

        assertTrue(result is WebDavResult.Success)
        assertEquals(201, (result as WebDavResult.Success).code)
        val request = takeRequest()
        assertEquals("PUT", request.method)
        assertEquals("/dav/a.txt", request.path)
        assertEquals("hello 世界", request.body.readUtf8())
    }

    @Test
    fun `put with overwrite false sends If-None-Match`() {
        server.enqueue(MockResponse().setResponseCode(412))

        val result = client().put("", "a.txt", "x".toRequestBody(), overwrite = false)

        assertEquals("*", takeRequest().getHeader("If-None-Match"))
        // ⚠️ 412 是服务器明确答复，属 HttpError 而不是 Failure —— 模块侧据此报「已存在」。
        assertTrue("期望 HttpError，实际 $result", result is WebDavResult.HttpError)
        assertEquals(412, (result as WebDavResult.HttpError).code)
    }

    @Test
    fun `put with overwrite true does not send If-None-Match`() {
        server.enqueue(MockResponse().setResponseCode(201))
        client().put("", "a.txt", "x".toRequestBody(), overwrite = true)
        assertNull(takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `get returns the body bytes`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("file-content"))

        val result = client().get("", "a.txt")

        assertEquals("GET", takeRequest().method)
        assertTrue(result is WebDavResult.Success)
        assertEquals("file-content", (result as WebDavResult.Success).text())
    }

    @Test
    fun `delete sends the right method with directory trailing slash`() {
        server.enqueue(MockResponse().setResponseCode(204))

        client().delete("", "sub", directory = true)

        val request = takeRequest()
        assertEquals("DELETE", request.method)
        assertEquals("/dav/sub/", request.path)
    }

    @Test
    fun `mkcol sends the right method`() {
        server.enqueue(MockResponse().setResponseCode(201))

        val result = client().mkcol("", "newdir")

        assertEquals("MKCOL", takeRequest().method)
        assertEquals(201, (result as WebDavResult.Success).code)
    }

    // ── Basic Auth ──────────────────────────────────────────────

    @Test
    fun `basic auth header is sent when username is non empty`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        client(username = "alice", password = "s3cret").propfind("", "", true)

        val expected = "Basic " + Base64.getEncoder().encodeToString("alice:s3cret".toByteArray())
        assertEquals(expected, takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `basic auth encodes non ascii credentials as utf8`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        client(username = "用户", password = "密码").propfind("", "", true)

        // ⚠️ 两参 Credentials.basic 默认 ISO-8859-1 ⇒ 中文会被**静默**编错。
        val expected = "Basic " + Base64.getEncoder().encodeToString("用户:密码".toByteArray(Charsets.UTF_8))
        assertEquals(expected, takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `no auth header when username is empty`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        client().propfind("", "", true)

        assertNull(takeRequest().getHeader("Authorization"))
    }

    // ── 重定向（本文件的核心）────────────────────────────────────

    @Test
    fun `redirectChainOfTwoHops keeps method and body`() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/dav/step1"))
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", "/dav/step2"))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        val result = client().propfind("", "start", directory = true, depth = 1)

        assertTrue("期望 Success，实际 $result", result is WebDavResult.Success)
        assertEquals(2, (result as WebDavResult.Success).hops)

        assertEquals("/dav/start/", takeRequest().path)
        assertEquals("/dav/step1", takeRequest().path)
        val last = takeRequest()
        assertEquals("/dav/step2", last.path)
        // ⚠️ 三跳全部是 PROPFIND 且每跳都带 body —— 「保方法保 body」的断言。
        // 若转发时用了 `.get()`，这里会是 GET 且 body 为空。
        assertEquals("PROPFIND", last.method)
        assertTrue("重发必须保留 body", last.body.readUtf8().contains("resourcetype"))
        assertEquals("1", last.getHeader("Depth"))
    }

    @Test
    fun `relative Location is resolved against the current url`() {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "../other"))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        val result = client().propfind("", "sub", directory = false)

        assertTrue(result is WebDavResult.Success)
        assertEquals("/dav/sub", takeRequest().path)
        assertEquals("/other", takeRequest().path)
    }

    @Test
    fun `redirectLoopBeyondMaxHops fails with TOO_MANY_REDIRECTS`() {
        // 每次请求都回 302 指回同一个地址 ⇒ 环。
        repeat(WebDavProbe.MAX_REDIRECTS + 3) {
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/dav/loop"))
        }

        val result = client().propfind("", "loop", directory = false)

        assertTrue("期望 Failure，实际 $result", result is WebDavResult.Failure)
        assertEquals(WebDavResult.Failure.Kind.TOO_MANY_REDIRECTS, (result as WebDavResult.Failure).kind)
        // ⚠️ 上限必须真的被用上：请求数 = 上限 + 1（第一跳不算「超限」）。
        assertEquals(WebDavProbe.MAX_REDIRECTS + 1, server.requestCount)
    }

    @Test
    fun `redirect without Location header fails with NETWORK`() {
        server.enqueue(MockResponse().setResponseCode(302))

        val result = client().propfind("", "", true)

        assertTrue(result is WebDavResult.Failure)
        assertEquals(WebDavResult.Failure.Kind.NETWORK, (result as WebDavResult.Failure).kind)
        assertTrue("细节里要说明是缺 Location", (result as WebDavResult.Failure).detail!!.contains("Location"))
    }

    @Test
    fun `cross host redirect drops the Authorization header`() {
        // ⚠️ 第二个 MockWebServer 端口不同 ⇒ sameHost 判为跨 host。
        val other = MockWebServer()
        other.start()
        try {
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", other.url("/dav/moved").toString())
            )
            other.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

            val result = client(username = "alice", password = "pw").propfind("", "", true)

            assertTrue(result is WebDavResult.Success)
            val first = takeRequest()
            assertEquals("同 host 时必须带凭据", "Basic " + Base64.getEncoder().encodeToString("alice:pw".toByteArray()), first.getHeader("Authorization"))
            val second = other.takeRequest(3, TimeUnit.SECONDS)!!
            // ⚠️⚠️ 这条是**安全断言**：跨 host 转发凭据 = 把用户名密码交给第三方主机，
            // 而用户完全无感。不能依赖 OkHttp 处理（我们关掉了自动跟随，这是我们的循环）。
            assertNull("跨 host 重定向后必须丢弃 Authorization", second.getHeader("Authorization"))
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun `same host redirect keeps the Authorization header`() {
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", "/dav/other"))
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        client(username = "alice", password = "pw").propfind("", "", true)

        takeRequest()
        val second = takeRequest()
        assertTrue("同 host 重定向后应继续带凭据", second.getHeader("Authorization")!!.startsWith("Basic "))
    }

    // ── HTTP 错误与传输失败 ─────────────────────────────────────

    @Test
    fun `401 becomes HttpError not Failure`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("Unauthorized"))

        val result = client(username = "a", password = "b").propfind("", "", true)

        assertTrue("期望 HttpError，实际 $result", result is WebDavResult.HttpError)
        assertEquals(401, (result as WebDavResult.HttpError).code)
        assertTrue((result as WebDavResult.HttpError).detail!!.contains("401"))
    }

    @Test
    fun `error body is truncated so it cannot flood the workflow log`() {
        server.enqueue(MockResponse().setResponseCode(500).setBody("x".repeat(5000)))

        val result = client().propfind("", "", true) as WebDavResult.HttpError

        assertTrue("错误正文必须被截断", result.detail!!.length < 500)
    }

    @Test
    fun `nonexistent directory 404 is HttpError`() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client().propfind("", "missing", true)

        assertEquals(404, (result as WebDavResult.HttpError).code)
    }

    @Test
    fun `timeout is NETWORK failure`() {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))

        val result = client(timeoutSeconds = 1).propfind("", "", true)

        assertTrue("期望 Failure，实际 $result", result is WebDavResult.Failure)
        assertEquals(WebDavResult.Failure.Kind.NETWORK, (result as WebDavResult.Failure).kind)
    }

    @Test
    fun `path traversal is rejected before any request is made`() {
        val result = client().propfind("", "../../etc", directory = false)

        assertTrue(result is WebDavResult.Failure)
        assertEquals(WebDavResult.Failure.Kind.INVALID_URL, (result as WebDavResult.Failure).kind)
        // ⚠️ 关键：**一个请求都不该发出去** —— 否则就是把穿越当成了正常路径。
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `remote base path from the configuration is applied`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        client().propfind("/backups/2026", "x.zip", directory = false)

        assertEquals("/dav/backups/2026/x.zip", takeRequest().path)
    }

    @Test
    fun `success carries the actual hop count for observability`() {
        server.enqueue(MockResponse().setResponseCode(207).setBody(multistatus))

        val result = client().propfind("", "", true) as WebDavResult.Success

        assertEquals(0, result.hops)
        assertFalse(result.bytes.isEmpty())
    }

    private companion object {
        val multistatus = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:multistatus xmlns:D="DAV:">
              <D:response>
                <D:href>/dav/</D:href>
                <D:propstat><D:prop>
                  <D:resourcetype><D:collection/></D:resourcetype>
                </D:prop><D:status>HTTP/1.1 200 OK</D:status></D:propstat>
              </D:response>
            </D:multistatus>
        """.trimIndent()
    }
}
