package com.chaomixian.vflow.core.webdav

import okhttp3.HttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebDavUrlBuilder] 的回归测试。
 *
 * ## 为什么这些用例值得锁
 *
 * 本类的失败模式**全是静默的**：URL 拼错不会报错，只会让请求打到别的地方、或者服务器
 * 收到一个与用户以为的不一样的路径。三个最典型的静默点：
 *
 * 1. **`#` 与 `?` 不编码** ⇒ 分别变成 fragment 与 query string（**`#` 之后根本不发给服务器**）。
 * 2. **`..` 不被拒绝** ⇒ `HttpUrl` 按 RFC 3986 静默上跳一级（已实测，见 [WebDavUrlBuilder] 类注释）。
 * 3. **目录缺尾斜杠** ⇒ 部分服务器 301，而「不跟随重定向」的客户端会看到另一个结果。
 *
 * ## ⚠️ 必做反证（父会话点名）
 *
 * 把 [WebDavUrlBuilder] 里的 `..` 拦截删掉 ⇒ [dotDotSegmentIsRejected] 等穿越用例**变红**
 * （`HttpUrl` 会给出 `https://dav.example.com/`，而不是 null）。
 */
class WebDavUrlBuilderTest {

    private val base = "https://dav.example.com/dav"

    /** 期望成功的解析（断言失败比 NPE 更能说明问题）。 */
    private fun url(
        path: String,
        directory: Boolean = false,
        remoteBasePath: String = "",
        baseUrl: String = base,
    ): String {
        val resolved: HttpUrl? = WebDavUrlBuilder.resolve(baseUrl, remoteBasePath, path, directory)
        requireNotNull(resolved) { "期望解析成功，但返回 null：base=$baseUrl remoteBase=$remoteBasePath path=$path" }
        return resolved.toString()
    }

    // ── 百分号编码（静默点 1）────────────────────────────────────

    @Test
    fun `space is percent encoded`() {
        assertEquals("https://dav.example.com/dav/a%20b.txt", url("a b.txt"))
    }

    @Test
    fun `cjk is percent encoded`() {
        assertEquals("https://dav.example.com/dav/%E4%B8%AD%E6%96%87%E7%9B%AE%E5%BD%95/", url("中文目录", directory = true))
    }

    @Test
    fun `hash becomes percent 23 and is not treated as a fragment`() {
        // ⚠️ 字符串拼接的产物是 `.../a#b.txt`，`#b.txt` 是 fragment —— 服务器只会收到 `/dav/a`。
        assertEquals("https://dav.example.com/dav/a%23b.txt", url("a#b.txt"))
        assertNull(WebDavUrlBuilder.resolve(base, "", "a#b.txt", false)!!.fragment)
    }

    @Test
    fun `question mark becomes percent 3F and is not treated as a query`() {
        assertEquals("https://dav.example.com/dav/a%3Fb.txt", url("a?b.txt"))
        assertNull(WebDavUrlBuilder.resolve(base, "", "a?b.txt", false)!!.query)
    }

    @Test
    fun `percent sign itself is escaped`() {
        // 防双重编码 / 防「用户输入的 %20 被当成已编码」—— addPathSegment 会把 `%` 编成 `%25`。
        assertEquals("https://dav.example.com/dav/100%25.txt", url("100%.txt"))
    }

    // ── 尾斜杠（静默点 3）───────────────────────────────────────

    @Test
    fun `directory adds trailing slash`() {
        assertEquals("https://dav.example.com/dav/sub/", url("sub", directory = true))
    }

    @Test
    fun `file has no trailing slash`() {
        assertEquals("https://dav.example.com/dav/sub/file.txt", url("sub/file.txt"))
    }

    @Test
    fun `directory on the base itself yields a single trailing slash`() {
        assertEquals("https://dav.example.com/dav/", url("", directory = true))
    }

    // ── 路径穿越（静默点 2，必做反证）─────────────────────────────

    @Test
    fun `dotDotSegmentIsRejected`() {
        assertNull(WebDavUrlBuilder.resolve(base, "", "..", directory = false))
    }

    @Test
    fun `dotDotInsidePath is rejected`() {
        assertNull(WebDavUrlBuilder.resolve(base, "", "sub/../other", directory = false))
    }

    @Test
    fun `dotSegment is rejected`() {
        // ⚠️ HttpUrl 对 `.` 的处理是**静默丢弃该段**（实测 `addPathSegment(".")` ⇒ `/dav/`），
        // 这个更隐蔽：路径少一段但没有任何错误。
        assertNull(WebDavUrlBuilder.resolve(base, "", ".", directory = false))
    }

    @Test
    fun `dotDotInRemoteBasePath is rejected too`() {
        // remoteBasePath 来自用户的设置页，同样是不可信输入。
        assertNull(WebDavUrlBuilder.resolve(base, "a/../b", "c", directory = false))
    }

    @Test
    fun `three dots is a legal file name`() {
        // 反向断言：只拦精确的 `.` / `..`，别把 `...` 也拦掉（那是合法的文件名）。
        assertEquals("https://dav.example.com/dav/...", url("..."))
    }

    @Test
    fun `name containing dotDot as substring is legal`() {
        assertEquals("https://dav.example.com/dav/a..b", url("a..b"))
    }

    // ── 控制字符 ────────────────────────────────────────────────

    @Test
    fun `control characters are rejected`() {
        assertNull(WebDavUrlBuilder.resolve(base, "", "a\nb", directory = false))
        assertNull(WebDavUrlBuilder.resolve(base, "", "a\u0000b", directory = false))
        assertNull(WebDavUrlBuilder.resolve(base, "", "a\rb", directory = false))
    }

    // ── 基址与多段路径 ──────────────────────────────────────────

    @Test
    fun `base with existing path is preserved`() {
        assertEquals(
            "https://dav.example.com/remote.php/dav/files/u/x",
            url("x", baseUrl = "https://dav.example.com/remote.php/dav/files/u")
        )
    }

    @Test
    fun `invalid base returns null`() {
        assertNull(WebDavUrlBuilder.resolve("not a url", "", "x", directory = false))
        assertNull(WebDavUrlBuilder.resolve("", "", "x", directory = false))
    }

    @Test
    fun `leading and trailing slashes in path are normalized away`() {
        assertEquals(url("sub/file.txt"), url("/sub/file.txt/"))
    }

    @Test
    fun `remoteBasePath and path are joined without doubling slashes`() {
        assertEquals(
            "https://dav.example.com/dav/backups/2026/x.zip",
            url("/2026/x.zip", remoteBasePath = "/backups/")
        )
    }

    @Test
    fun `empty everything with directory false yields the base path itself`() {
        val resolved = url("")
        assertEquals("https://dav.example.com/dav", resolved)
        assertTrue(resolved.startsWith("https://"))
    }

    @Test
    fun `query of the configured base survives`() {
        // 反向断言：不要为了编码路径而把 baseUrl 上原本就有的 query 弄丢。
        assertEquals(
            "https://example.com/dav/x?token=abc",
            url("x", baseUrl = "https://example.com/dav?token=abc")
        )
    }
}
