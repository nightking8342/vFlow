package com.chaomixian.vflow.core.webdav

import okhttp3.Credentials
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.net.Proxy
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 一次 WebDAV 请求的结论。
 *
 * ## ⚠️ 为什么是密封三态而不是 `Boolean` / 抛异常
 *
 * [HttpError]（服务器明确拒绝）与 [Failure]（传输没成功）对用户的**处置完全不同**：
 * 前者要改路径/权限/凭据，后者要查网络。压成一个 `false` 会让用户对着正确的配置反复重试。
 *
 * [Success] 带 [Success.hops] 是**可观测性**的落点：没有它，调用方分不清
 * 「服务器直接回 207」与「经过 3 跳才回 207」。
 */
sealed interface WebDavResult {

    /** 2xx。 */
    data class Success(
        val code: Int,
        val bytes: ByteArray,
        /** 实际经过的重定向跳数（0 = 没有重定向）。 */
        val hops: Int,
    ) : WebDavResult {
        fun text(): String = bytes.toString(Charsets.UTF_8)

        // data class 带 ByteArray 必须手写这两个 —— 否则是引用相等，测试里比较会意外失败。
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Success) return false
            return code == other.code && hops == other.hops && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * (31 * code + hops) + bytes.contentHashCode()
    }

    /** 服务器给了明确答复，但不是 2xx（401/403/404/405/409/412/507…）。 */
    data class HttpError(val code: Int, val detail: String?) : WebDavResult

    /** IO / TLS / 超时 / 无法解析地址 / 重定向超限。 */
    data class Failure(val kind: Kind, val detail: String?) : WebDavResult {
        enum class Kind {
            NETWORK,
            TLS,
            TOO_MANY_REDIRECTS,
            INVALID_URL,
        }
    }
}

/**
 * WebDAV 传输层（`PROPFIND` / `PUT` / `GET` / `DELETE` / `MKCOL`）。
 *
 * ## ⚠️⚠️ 为什么必须 `followRedirects(false)` + 自己跑重定向循环
 *
 * ⚠️ **先纠正一个常见的错误理由**（T3 实测更正过，本类沿用其口径）：不是「怕方法被降级」——
 * 实测 okhttp 4.12.0 里 **`PROPFIND` 恰是唯一 `redirectsToGet=false` 的方法**
 * （`redirectsWithBody=true`），301/302/303/307/308 一律保方法保 body。
 *
 * 真正的理由是**可观测性与可判定性**：OkHttp 自动跟随会**吞掉**重定向链 ——
 * 调用方只看到最终响应，拿不到「经过几跳」，也无法在跳数超限时给出明确结论
 * （OkHttp 自己的上限是 20 跳，会替我们跟到底，然后给一个语焉不详的成功或失败）。
 *
 * ⇒ 自己转发时**显式**保留方法与 body（`Request.Builder().method(methodName, body)`，
 * **不要** `.get()`），**跨 host 时丢弃 `Authorization`**，上限 [WebDavProbe.MAX_REDIRECTS] 跳。
 *
 * ## ⚠️ 与 [WebDavProbe] 共用符号，不另写一套
 *
 * | 符号 | 位置 | 为什么共用 |
 * |---|---|---|
 * | [WebDavProbe.MAX_REDIRECTS] | `WebDavProbe` | 两处各写一个 5 就是**静默不一致**（改一处忘另一处） |
 * | [WebDavProbe.resolveLocation] | `WebDavProbe` | 相对 `Location` → 绝对 URL |
 * | [WebDavProbe.sameHost] | `WebDavProbe` | 判定跨 host（跨 host 丢凭据） |
 * | `REDIRECT_CODES` / `trustAllTrustManager` / TLS 装配 | **[WebDavHttpSupport]** | 收敛前这三样在本类与 `WebDavProbe` 各写一份（`trustAllTrustManager` 两份逐字相同） |
 *
 * ⚠️ **`followRedirects(false)` / `followSslRedirects(false)` 刻意留在本类与 `WebDavProbe` 各自的
 * `buildClient()` 里**（不收敛进 `WebDavHttpSupport`）：它是一个**意图声明**，
 * 读代码的人应当在装配 OkHttp 的地方直接看见它。
 * ## ⚠️ 线程模型
 *
 * [OkHttpClient] **必须复用**（内部有连接池与线程池，每次 new 一个会漏线程）。
 * 本类的每个公开方法都可从任意线程调用，但**不得在主线程调用**（阻塞 IO）。
 */
class WebDavClient(
    private val baseUrl: String,
    private val username: String,
    private val password: String,
    private val allowInsecureTls: Boolean = false,
    private val timeoutSeconds: Int = WebDavConfig.DEFAULT_TIMEOUT_SECONDS,
    private val proxy: Proxy? = null,
) {

    private val client: OkHttpClient by lazy { buildClient() }
    private val credentials: String? by lazy {
        // ⚠️ 必须显式传 UTF_8 —— 两参重载默认 ISO-8859-1，中文凭据会**静默**编错。
        if (username.isEmpty()) null else Credentials.basic(username, password, Charsets.UTF_8)
    }

    // ── 五个动词 ────────────────────────────────────────────────

    /**
     * `PROPFIND`。
     *
     * @param depth 0 或 1。0 只取目标自身，1 取「目标 + 直接子项」。
     * @param directory true ⇒ 目标 URL 以 `/` 结尾（对部分服务器是**语义要求**，见 [WebDavUrlBuilder]）。
     */
    fun propfind(remoteBasePath: String, path: String, directory: Boolean, depth: Int = 1): WebDavResult =
        execute("PROPFIND", remoteBasePath, path, directory) {
            it.method("PROPFIND", PROPFIND_BODY)
                .header("Depth", depth.coerceIn(0, 1).toString())
        }

    /** `PUT`。⚠️ [body] 必须是**可重发**的（`ByteArray` / `File`）—— 重定向要重发同一个 body。 */
    fun put(remoteBasePath: String, path: String, body: RequestBody, overwrite: Boolean = true): WebDavResult =
        execute("PUT", remoteBasePath, path, directory = false) {
            val builder = it.method("PUT", body)
            if (!overwrite) {
                // ⚠️ `If-None-Match: *` 是 WebDAV 的「仅在不存在时创建」——服务器应回 412。
                // 注意这是**服务器行为**，不是所有实现都支持（不支持时会静默覆盖），
                // 故模块侧在 412 之外不做「已存在」的推断。
                builder.header("If-None-Match", "*")
            }
            builder
        }

    fun get(remoteBasePath: String, path: String): WebDavResult =
        execute("GET", remoteBasePath, path, directory = false) { it.get() }

    fun delete(remoteBasePath: String, path: String, directory: Boolean): WebDavResult =
        execute("DELETE", remoteBasePath, path, directory) { it.delete() }

    fun mkcol(remoteBasePath: String, path: String): WebDavResult =
        execute("MKCOL", remoteBasePath, path, directory = false) { it.method("MKCOL", EMPTY_BODY) }

    // ── 主循环 ──────────────────────────────────────────────────

    private fun execute(
        methodName: String,
        remoteBasePath: String,
        path: String,
        directory: Boolean,
        apply: (Request.Builder) -> Request.Builder,
    ): WebDavResult {
        val target = WebDavUrlBuilder.resolve(baseUrl, remoteBasePath, path, directory)
            ?: return WebDavResult.Failure(
                WebDavResult.Failure.Kind.INVALID_URL,
                "路径非法（含 .. 穿越、控制字符，或地址无法解析）"
            )

        var url = target.toString()
        var withAuth = credentials != null

        try {
            var hop = 0
            while (true) {
                val result = runCatching {
                    client.newCall(
                        apply(
                            Request.Builder().url(url).apply {
                                if (withAuth) header("Authorization", credentials!!)
                            }
                        ).build()
                    ).execute()
                }

                val response = result.getOrElse { throw it }

                response.use { resp ->
                    val code = resp.code

                    if (code in REDIRECT_CODES) {
                        val location = resp.header("Location")
                            ?: return WebDavResult.Failure(
                                WebDavResult.Failure.Kind.NETWORK,
                                "重定向响应缺少 Location 头（HTTP $code）"
                            )

                        if (hop >= WebDavProbe.MAX_REDIRECTS) {
                            return WebDavResult.Failure(
                                WebDavResult.Failure.Kind.TOO_MANY_REDIRECTS,
                                "重定向超过 ${WebDavProbe.MAX_REDIRECTS} 跳（最后指向 $location）"
                            )
                        }

                        val next = WebDavProbe.resolveLocation(url, location)
                            ?: return WebDavResult.Failure(
                                WebDavResult.Failure.Kind.NETWORK,
                                "无法解析重定向目标：$location"
                            )

                        // ⚠️ 跨 host 丢弃 Authorization —— 防凭据被转发给第三方主机。
                        // 不能依赖 OkHttp 做这件事：我们关掉了自动跟随，这条路径是**我们自己**的。
                        if (!WebDavProbe.sameHost(url, next)) withAuth = false

                        url = next
                        hop++
                        continue
                    }

                    val bytes = resp.body?.bytes() ?: ByteArray(0)
                    return if (code in 200..299) {
                        WebDavResult.Success(code, bytes, hop)
                    } else {
                        WebDavResult.HttpError(code, describe(code, bytes))
                    }
                }
            }
        } catch (e: UnknownHostException) {
            return WebDavResult.Failure(WebDavResult.Failure.Kind.NETWORK, "无法解析主机：${e.message}")
        } catch (e: SSLException) {
            return WebDavResult.Failure(WebDavResult.Failure.Kind.TLS, "TLS 失败：${e.message}")
        } catch (e: IOException) {
            // ⚠️ 用 javaClass.simpleName 而不是固定文案 —— SocketTimeoutException 与
            // ConnectException 对用户的指向不同（超时 vs 连不上）。
            return WebDavResult.Failure(WebDavResult.Failure.Kind.NETWORK, "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            return WebDavResult.Failure(WebDavResult.Failure.Kind.INVALID_URL, "地址无法解析：${e.message}")
        }
    }

    /**
     * 服务器答复的正文摘要（诊断用）。
     *
     * ⚠️ **截断到 200 字符**：有些服务器会回一整页 HTML 错误页，
     * 原样塞进模块的 `error` 输出会把工作流日志淹掉。
     */
    private fun describe(code: Int, bytes: ByteArray): String {
        val body = runCatching { bytes.toString(Charsets.UTF_8).trim() }.getOrDefault("")
        val head = if (body.length > ERROR_BODY_LIMIT) {
            body.substring(0, ERROR_BODY_LIMIT) + "…"
        } else {
            body
        }
        return if (head.isEmpty()) "HTTP $code" else "HTTP $code: $head"
    }

    // ── HTTP 客户端装配 ─────────────────────────────────────────

    private fun buildClient(): OkHttpClient {
        val timeout = clampTimeout(timeoutSeconds).toLong()

        return OkHttpClient.Builder()
            .connectTimeout(timeout, TimeUnit.SECONDS)
            .readTimeout(timeout, TimeUnit.SECONDS)
            .writeTimeout(timeout, TimeUnit.SECONDS)
            // ⚠️ 刻意**不用** callTimeout —— 大文件上传/下载会被它整请求误杀
            //（callTimeout 覆盖「连接 + 写入 + 读取」全程，而备份文件动辄几十 MB）。
            // ⚠️ 见类注释：关自动跟随是为了拿回判断权，不是怕方法降级。
            .followRedirects(false)
            .followSslRedirects(false)
            .apply {
                if (proxy != null) proxy(proxy)
                // ⚠️ 只替换 TrustManager（允许自签名 / 过期证书），**保留**默认 HostnameVerifier。
                // 装配细节与 WebDavProbe 共用一份（WebDavHttpSupport）。
                WebDavHttpSupport.applyInsecureTlsIfNeeded(this, allowInsecureTls)
            }
            .build()
    }

    companion object {
        // ⚠️ 与 WebDavProbe 共用一份（WebDavHttpSupport）。
        private val REDIRECT_CODES get() = WebDavHttpSupport.REDIRECT_CODES
        private const val ERROR_BODY_LIMIT = 200

        private val PROPFIND_MEDIA_TYPE: MediaType = "application/xml; charset=utf-8".toMediaType()

        private val EMPTY_BODY: RequestBody = ByteArray(0).toRequestBody(null)

        /**
         * `PROPFIND` 的请求体：一次要全 list 需要的字段。
         *
         * ⚠️ 字段与 [WebDavXmlParser] 消费的字段**必须对应** —— 这里少要一个
         * （例如 `getcontentlength`），列表里那一列就会**永远是 0**，而没有任何报错。
         */
        private val PROPFIND_BODY: RequestBody = """
            <?xml version="1.0" encoding="utf-8"?>
            <D:propfind xmlns:D="DAV:"><D:prop>
              <D:resourcetype/><D:displayname/><D:getcontentlength/>
              <D:getcontenttype/><D:getlastmodified/>
            </D:prop></D:propfind>
        """.trimIndent().toRequestBody(PROPFIND_MEDIA_TYPE)
    }
}
