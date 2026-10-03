package com.chaomixian.vflow.core.webdav

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [WebDavProbe] 的纯 JVM 测试（MockWebServer）。
 *
 * ⚠️ **订正（2026-10-03 实测）**：本测试原先声称「不能验证 `followRedirects(false)` 这个开关
 * 本身生效 —— 改回 `true` 时行为断言两种都绿」，**那是错的**。实测改 `true` 后有 **3 条**变红：
 * [redirectLoopBeyondMaxHops_isNetworkError]（OkHttp 默认上限 20 跳）、
 * [redirectChainOfTwoHops_isFollowedToSuccess]（断言读到 3 条请求）、
 * 以及源码扫描型 `WebDavSettingsEntryTest.probeDisablesAutomaticRedirectFollowing`。
 * ⇒ 本测试**能**验证该开关，行为面锁得住；源码扫描是第二道锁。
 * （底下那条「PROPFIND 不降级成 GET」的实测结论仍然成立，与开关本身的锁是两件事。）
 */
class WebDavProbeTest {

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

    private fun url(path: String = "/"): String = server.url(path).toString().trimEnd('/')

    private fun probe(
        path: String = "/",
        username: String = "user",
        password: String = "pass",
        allowInsecureTls: Boolean = false,
    ): WebDavProbeResult = WebDavProbe.probe(
        baseUrl = url(path),
        username = username,
        password = password,
        allowInsecureTls = allowInsecureTls,
        timeoutSeconds = 5
    )

    // ---------- 状态码映射 ----------

    @Test
    fun propfind_207_isSuccess() {
        server.enqueue(MockResponse().setResponseCode(207))

        val result = probe()

        assertEquals(WebDavProbeOutcome.SUCCESS, result.outcome)
        assertEquals(207, result.httpCode)
    }

    @Test
    fun propfind_200_isSuccess() {
        server.enqueue(MockResponse().setResponseCode(200))

        assertEquals(WebDavProbeOutcome.SUCCESS, probe().outcome)
    }

    @Test
    fun propfind_401_isAuthFailed() {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = probe()

        assertEquals(WebDavProbeOutcome.AUTH_FAILED, result.outcome)
        assertEquals(401, result.httpCode)
    }

    @Test
    fun propfind_403_isAuthFailed() {
        server.enqueue(MockResponse().setResponseCode(403))

        assertEquals(WebDavProbeOutcome.AUTH_FAILED, probe().outcome)
    }

    @Test
    fun propfind_404_isNotFound() {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = probe()

        assertEquals(WebDavProbeOutcome.NOT_FOUND, result.outcome)
        assertEquals(404, result.httpCode)
    }

    @Test
    fun propfind_405_isNotSupported() {
        server.enqueue(MockResponse().setResponseCode(405))

        assertEquals(WebDavProbeOutcome.NOT_SUPPORTED, probe().outcome)
    }

    @Test
    fun propfind_501_isNotSupported() {
        server.enqueue(MockResponse().setResponseCode(501))

        assertEquals(WebDavProbeOutcome.NOT_SUPPORTED, probe().outcome)
    }

    @Test
    fun propfind_500_isNetworkErrorWithCodeInDetail() {
        server.enqueue(MockResponse().setResponseCode(500))

        val result = probe()

        assertEquals(WebDavProbeOutcome.NETWORK_ERROR, result.outcome)
        assertEquals(500, result.httpCode)
        assertTrue("detail 应带原状态码，实际：${result.detail}", result.detail?.contains("500") == true)
    }

    // ---------- 重定向 ----------

    @Test
    fun redirect302_isFollowedAndReturnsFinalOutcome() {
        val target = server.url("/target").toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        server.enqueue(MockResponse().setResponseCode(207))

        val result = probe()

        assertEquals(WebDavProbeOutcome.SUCCESS, result.outcome)
        assertEquals(207, result.httpCode)
        // ⚠️⚠️ 这条断言是「自实现重定向」的**判别式**：
        // 能读到 **2 条**请求 ⇒ 是我们自己重发的，不是 OkHttp 代劳。
        // （不断言「第二次 method == PROPFIND」——OkHttp 默认也会保 PROPFIND，那条区分不出来。）
        val first = server.takeRequest()
        val second = server.takeRequest()
        assertNotNull("应能读到第一条请求（302 的来源）", first)
        assertNotNull("应能读到第二条请求（我们重发的）", second)
        assertEquals("第二次请求必须是 PROPFIND", "PROPFIND", second.method)
    }

    @Test
    fun redirect307_keepsBodyAndMethod() {
        val target = server.url("/target").toString()
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location", target))
        server.enqueue(MockResponse().setResponseCode(207))

        val result = probe()

        assertEquals(WebDavProbeOutcome.SUCCESS, result.outcome)

        server.takeRequest() // 丢弃第一条
        val second = server.takeRequest()
        assertEquals("307 后必须保方法", "PROPFIND", second.method)
        // ⚠️ `second.body` 是 okio Buffer：`readUtf8()` **消费**它，只能读一次。
        // 先取到局部变量，否则第二个断言会读到空串（实现期实际踩到过）。
        val bodyText = second.body.readUtf8()
        // 307 的语义就是「保方法保 body」—— body 为空说明我们退化成了 GET 式重发。
        assertTrue("307 后必须保留 body，实际：'$bodyText'", bodyText.isNotEmpty())
        assertTrue("body 应含 PROPFIND XML，实际：'$bodyText'", bodyText.contains("propfind"))
    }

    @Test
    fun redirectChainOfTwoHops_isFollowedToSuccess() {
        // ⚠️⚠️ 这条是「跳数上限**不能设成 1**」的判别式 —— 单跳重定向是常态，
        // 但真实世界存在**两跳**链（如 `/dav` → `/dav/` → `/dav/index`，或 http→https→最终）。
        // 上限设成 1 会把这类**配置完全正确**的服务器判成 NETWORK_ERROR（detail 写「重定向超限」）。
        val hop1 = server.url("/hop1").toString()
        val hop2 = server.url("/hop2").toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", hop1))
        server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", hop2))
        server.enqueue(MockResponse().setResponseCode(207))

        val result = probe()

        assertEquals("两跳重定向后应拿到最终结果", WebDavProbeOutcome.SUCCESS, result.outcome)
        assertEquals(207, result.httpCode)
        assertEquals("应记录 2 跳", 2, result.redirectHops)
        // 三条请求都真实发生过 ⇒ 确实是我们自己逐跳重发的。
        assertNotNull(server.takeRequest())
        assertNotNull(server.takeRequest())
        assertNotNull(server.takeRequest())
    }

    @Test
    fun maxRedirects_isSmallEnoughToBoundTheCost() {
        // ⚠️⚠️ 这条锁的是「上限**必须足够小**」这个不变量本身。
        // 环用例（`redirectLoopBeyondMaxHops`）用的是 `repeat(MAX_REDIRECTS + 3)`，
        // 上限调到 50 时它照样绿 —— 因为它与常量**同向**变化。
        // 而本函数是**反向**约束：上限设太大 ⇒ 每次探测都要真的发几十个请求，
        // 放在工作流里就是「一个填错的地址把线程挂住很久」。
        assertTrue(
            "MAX_REDIRECTS 应足够小以界定最坏耗时（实测 5 足够覆盖真实服务器；" +
                "常见的是 1 跳，极少超过 2 跳）。实际：${WebDavProbe.MAX_REDIRECTS}",
            WebDavProbe.MAX_REDIRECTS in 2..10
        )
    }

    @Test
    fun perRequestTimeoutBoundsTheTotalTime() {
        // 每个请求都受 timeoutSeconds 约束（connect/read/write 三者都设了），
        // 故最坏耗时 ≈ MAX_REDIRECTS × timeout。这条断言防止有人「只设 readTimeout
        // 忘了 connectTimeout」——那时连不上的地址会一直卡在 TCP 连接阶段。
        // 这里用一个必然连不上的地址验证「确实会在合理时间内返回」。
        val start = System.currentTimeMillis()
        val result = WebDavProbe.probe("http://127.0.0.1:1/", "u", "p", timeoutSeconds = 1)
        val elapsed = System.currentTimeMillis() - start

        assertEquals(WebDavProbeOutcome.NETWORK_ERROR, result.outcome)
        assertTrue("应在超时约束内快速返回，实际耗时 ${elapsed}ms", elapsed < 15_000)
    }

    @Test
    fun redirectLoopBeyondMaxHops_isNetworkError() {
        // 在 /a 与 /b 之间互相 302 —— 构造一个不会自己停下来的环。
        val a = server.url("/a").toString()
        val b = server.url("/b").toString()
        repeat(WebDavProbe.MAX_REDIRECTS + 3) { i ->
            server.enqueue(
                MockResponse().setResponseCode(302)
                    .setHeader("Location", if (i % 2 == 0) b else a)
            )
        }

        val result = WebDavProbe.probe(url("/a"), "user", "pass", timeoutSeconds = 5)

        // ⚠️ 归 NETWORK_ERROR（方案 §4.5/§7.1 R4：五态穷尽，重定向超限不单列一态），
        // 具体原因在 detail 里 —— 这条断言同时锁住「不挂死」与「原因可诊断」两件事。
        assertEquals(
            "超过 $WebDavProbe.MAX_REDIRECTS 跳应判 NETWORK_ERROR（防重定向环挂死线程）",
            WebDavProbeOutcome.NETWORK_ERROR,
            result.outcome
        )
        assertTrue(
            "detail 必须说明是重定向超限（否则用户只知道「网络错误」，无从下手）。实际：${result.detail}",
            result.detail?.contains("重定向") == true
        )
        assertTrue("redirectHops 应记录实际跳数，实际：${result.redirectHops}", result.redirectHops >= WebDavProbe.MAX_REDIRECTS)
    }

    @Test
    fun redirect_withoutLocationHeader_isNetworkError() {
        server.enqueue(MockResponse().setResponseCode(302))

        val result = probe()

        assertEquals(WebDavProbeOutcome.NETWORK_ERROR, result.outcome)
        assertTrue("detail 应提到 Location，实际：${result.detail}", result.detail?.contains("Location") == true)
    }

    @Test
    fun directResponse_hasZeroRedirectHops() {
        server.enqueue(MockResponse().setResponseCode(207))

        assertEquals(0, probe().redirectHops)
    }

    // ---------- 请求构造 ----------

    @Test
    fun depth0HeaderIsSent() {
        server.enqueue(MockResponse().setResponseCode(207))

        probe()

        val request = server.takeRequest()
        assertEquals("PROPFIND 必须带 Depth: 0（否则服务器回整棵树）", "0", request.getHeader("Depth"))
    }

    @Test
    fun propfindBodyIsSentAndContentTypeIsXml() {
        server.enqueue(MockResponse().setResponseCode(207))

        probe()

        val request = server.takeRequest()
        assertTrue("body 应含 <D:propfind", request.body.readUtf8().contains("<D:propfind"))
        val contentType = request.getHeader("Content-Type").orEmpty()
        assertTrue("Content-Type 应以 application/xml 开头，实际：$contentType", contentType.startsWith("application/xml"))
    }

    @Test
    fun basicAuthHeaderIsSentWhenUsernamePresent() {
        server.enqueue(MockResponse().setResponseCode(207))

        probe(username = "alice", password = "s3cret")

        val request = server.takeRequest()
        val auth = request.getHeader("Authorization").orEmpty()
        assertTrue("应带 Basic 认证头，实际：$auth", auth.startsWith("Basic "))
    }

    @Test
    fun basicAuthHeaderOmittedWhenUsernameEmpty() {
        server.enqueue(MockResponse().setResponseCode(207))

        probe(username = "", password = "")

        assertNull("用户名为空时不应带 Authorization", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun basicAuthEncodesNonAsciiCredentialsAsUtf8() {
        server.enqueue(MockResponse().setResponseCode(207))

        probe(username = "用户名", password = "密码")

        val auth = server.takeRequest().getHeader("Authorization").orEmpty()
        val decoded = String(
            java.util.Base64.getDecoder().decode(auth.removePrefix("Basic ")),
            Charsets.UTF_8
        )
        // ⚠️ 若 Credentials.basic 没传 UTF_8（默认 ISO-8859-1），这里会是乱码。
        assertEquals("用户名:密码", decoded)
    }

    @Test
    fun crossHostRedirect_dropsAuthorizationHeader() {
        // 第二个 server 模拟「另一台主机」。
        val other = MockWebServer()
        other.start()
        try {
            other.enqueue(MockResponse().setResponseCode(207))
            val target = other.url("/target").toString()
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))

            val result = probe(username = "alice", password = "s3cret")

            assertEquals(WebDavProbeOutcome.SUCCESS, result.outcome)
            val redirected = other.takeRequest()
            assertNull(
                "⚠️ 跨 host 重定向**必须**丢弃 Authorization（否则凭据泄漏给第三方主机）",
                redirected.getHeader("Authorization")
            )
        } finally {
            other.shutdown()
        }
    }

    @Test
    fun sameHostRedirect_keepsAuthorizationHeader() {
        val target = server.url("/target").toString()
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", target))
        server.enqueue(MockResponse().setResponseCode(207))

        probe(username = "alice", password = "s3cret")

        server.takeRequest()
        val second = server.takeRequest()
        assertTrue(
            "同 host 重定向应保留 Authorization（否则会被误判成认证失败）",
            second.getHeader("Authorization").orEmpty().startsWith("Basic ")
        )
    }

    // ---------- 异常路径 ----------

    @Test
    fun unreachableHost_isNetworkError() {
        server.shutdown() // 关掉，制造连接失败

        val result = WebDavProbe.probe("http://127.0.0.1:1/", "u", "p", timeoutSeconds = 2)

        assertEquals(WebDavProbeOutcome.NETWORK_ERROR, result.outcome)
        assertNotNull("detail 应带异常信息", result.detail)
    }

    @Test
    fun blankUrl_isNetworkError() {
        val result = WebDavProbe.probe("", "u", "p", timeoutSeconds = 2)

        assertEquals(WebDavProbeOutcome.NETWORK_ERROR, result.outcome)
    }

    @Test
    fun malformedUrlWithoutScheme_isNetworkError() {
        val result = WebDavProbe.probe("not-a-url", "u", "p", timeoutSeconds = 2)

        assertEquals(WebDavProbeOutcome.NETWORK_ERROR, result.outcome)
    }

    // ---------- messageKey ----------

    @Test
    fun outcomes_mapToDistinctResourceKeys() {
        val keys = WebDavProbeOutcome.entries.map { it.messageKey }
        assertEquals("每个 outcome 都应有独立文案键", keys.size, keys.toSet().size)
        assertTrue("SUCCESS 应映射到 webdav_test_success", WebDavProbeOutcome.SUCCESS.messageKey == "webdav_test_success")
        assertEquals(
            "outcome 应为五态（方案 §4.5 定案：互斥且穷尽；加第六态会让 UI 的 when 分支漂移）",
            5,
            WebDavProbeOutcome.entries.size
        )
    }

    // ---------- 重定向解析辅助函数 ----------

    @Test
    fun resolveLocation_handlesRelativeAndAbsolute() {
        assertEquals(
            "http://h/a/b",
            WebDavProbe.resolveLocation("http://h/a/c", "b")
        )
        assertEquals(
            "http://other/x",
            WebDavProbe.resolveLocation("http://h/a", "http://other/x")
        )
        assertNull("无法解析时返回 null", WebDavProbe.resolveLocation("::not a url::", "/x"))
    }

    @Test
    fun sameHost_comparesHostAndPort() {
        assertTrue(WebDavProbe.sameHost("http://h:80/a", "http://h/b"))
        assertTrue(!WebDavProbe.sameHost("http://h/a", "http://other/b"))
        assertTrue(!WebDavProbe.sameHost("http://h:80/a", "http://h:8080/b"))
    }
}
