package com.chaomixian.vflow.core.webdav

import okhttp3.Credentials
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

/**
 * 一次 PROPFIND 探测的结论。
 *
 * ⚠️ 五态**互斥且穷尽** —— 上层 UI 据 [messageKey] 直接给文案，不再二次判断。
 *
 * ⚠️ 「重定向超过 [WebDavProbe.MAX_REDIRECTS] 跳」**归入 [NETWORK_ERROR]**，
 * 不单列一态 —— 具体原因走 [WebDavProbeResult.detail]（「重定向超过 5 跳」），
 * 由 UI 作为诊断副行显示。这样做的理由是保持枚举最小：它的用户处置与
 * 「地址填错」同属「改配置」，而与「改凭据」「重输密码」不同。
 */
enum class WebDavProbeOutcome {
    /** 207 Multi-Status / 200 OK —— 服务器接受了 PROPFIND。 */
    SUCCESS,
    /** 401 / 403 —— 地址通了，但凭据不对。 */
    AUTH_FAILED,
    /** 404 —— 地址通了，但那个路径不存在（多半是 `remoteBasePath` 填错）。 */
    NOT_FOUND,
    /** 405 / 501 —— 服务器不接受 PROPFIND，多半根本不是 WebDAV 服务。 */
    NOT_SUPPORTED,
    /** IO / TLS / 超时 / 无法解析的地址 / 重定向超限。 */
    NETWORK_ERROR,
    ;

    /**
     * 映射到 `R.string.*` 的**资源名**。
     *
     * ⚠️ 本文件（`core/webdav`）不引 Android ⇒ 返回资源**名**由 UI 侧解析成资源 id。
     * 这样 [WebDavProbe] 才能在纯 JVM 测试里跑（MockWebServer）。
     */
    val messageKey: String
        get() = when (this) {
            SUCCESS -> "webdav_test_success"
            AUTH_FAILED -> "webdav_test_auth_failed"
            NOT_FOUND -> "webdav_test_not_found"
            NOT_SUPPORTED -> "webdav_test_not_supported"
            NETWORK_ERROR -> "webdav_test_network_error"
        }
}

/** 探测结果 + 诊断细节。 */
data class WebDavProbeResult(
    val outcome: WebDavProbeOutcome,
    val httpCode: Int? = null,
    /** 人可读的诊断信息（状态码 / 异常类名 + message）。**只给人看，不参与判断**。 */
    val detail: String? = null,
    /** 实际经过的跳数（诊断用；0 表示没有重定向）。 */
    val redirectHops: Int = 0
)

/**
 * 「测试连接」的最小实现：对 [baseUrl] 发 `PROPFIND` + `Depth: 0`。
 *
 * ⚠️⚠️ 本文件是 **T4 的雏形**（任务书要求：T4 未交付时本任务自己写，T4 再统一）。
 * 刻意只实现这一个动词、这一条链路，**不**抽象成通用客户端 —— 抽象要等 T4 见过
 * list/upload/download/mkdir/delete 五条路径的真实形状之后再抽，现在抽就是猜。
 *
 * 三个**必须**遵守的点（都是「不报错、只是静默变坏」型）：
 *
 * 1. ⚠️ **`followRedirects(false)` 的理由是「拿回判断权」，不是「怕方法被降级」**。
 *
 *    实测（`javap` + 直接调用 okhttp-4.12.0 的 `HttpMethod`）：
 *    ```
 *    GET      redirectsToGet=true   redirectsWithBody=false
 *    PROPFIND redirectsToGet=false  redirectsWithBody=true    ← 唯一被特判成 false 的
 *    ```
 *    ⇒ **PROPFIND 恰是 OkHttp 唯一不会在重定向时被降级成 GET 的非 GET 方法**，
 *    且 301/302/303/307/308 一律保留方法 + body。
 *
 *    ⇒ 那为什么仍要 `followRedirects(false)`？理由是**可观测性与可判定性**：
 *    - OkHttp 自动跟随会**吞掉**重定向链 —— 调用方只看到最终响应，无法区分
 *      「服务器直接回 207」与「经过 3 跳才回 207」，也无法在跳数超限时给出明确结论。
 *    - 本任务的核心产出是**诊断**（用户对着一个填错的地址，要告诉他错在哪）。
 *      跳数上限与 `Location` 链都是这个产出的一部分。
 *    - 顺带堵住跨 host 重定向时 `Authorization` 的外泄（OkHttp 自身也做了这件事，
 *      但我们自己转发时**必须显式处理**，不能依赖它）。
 *
 *    ⚠️⚠️ **订正（2026-10-03 实测）**：此处原先写「改回 `followRedirects(true)` 时行为断言
 *    两种情况都绿、只能靠源码扫描锁住」—— **那是错的**。实测把开关改成 `true` 后有
 *    **3 条**行为断言变红：`redirectLoopBeyondMaxHops_isNetworkError`（OkHttp 默认上限
 *    20 跳，会替我们把重定向环跟到底、不再由我们的跳数上限拦下）、
 *    `redirectChainOfTwoHops_isFollowedToSuccess`（断言「能读到 3 条请求」，OkHttp 代劳时
 *    我们自己的逐跳循环不再执行）、`probeDisablesAutomaticRedirectFollowing`（源码扫描）。
 *    ⇒ 本不变量**有**行为测试保护，源码扫描是第二道锁、不是唯一一道。
 *    （源码扫描仍值得保留：它直接锁「开关这一行在不在」，语义比行为断言更直接。）
 * 2. ⚠️ **自己处理重定向时保留方法与 body**：读 `Location` → 拼接成绝对 URL → **重新用
 *    `Request.Builder().method("PROPFIND", body)` 构造**（不能 `.get()`）。上限 [MAX_REDIRECTS] 跳，
 *    超出判 [WebDavProbeOutcome.NETWORK_ERROR]（防重定向环把工作流线程挂死）——
 *    五态穷尽、**不**单列「重定向超限」一态，具体原因写在 [WebDavProbeResult.detail]。
 *    跨 host 时不带 `Authorization`（防凭据外泄）。
 * 3. ⚠️ **`allowInsecureTls=true` 时的信任范围要收窄**：用
 *    `SSLContext` + 自定义 `X509TrustManager`，**只在** `allowInsecureTls` 时启用；
 *    且**不做** hostname 通配（保留默认 `HostnameVerifier`）—— 关掉 hostname 校验等于把
 *    「允许自签名」升级成「允许任意中间人」。
 *    装配细节见 [WebDavHttpSupport.applyInsecureTlsIfNeeded]（与 [WebDavClient] 共用一份）。
 */
object WebDavProbe {

    private const val PROPFIND_METHOD = "PROPFIND"
    private const val PROPFIND_XML =
        """<?xml version="1.0" encoding="utf-8"?><D:propfind xmlns:D="DAV:"><D:prop><D:resourcetype/></D:prop></D:propfind>"""

    private val PROPFIND_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

    /**
     * 重定向上限。
     *
     * ⚠️ 值必须 > 1 —— 单跳重定向（服务器把 `/dav` 301 到 `/dav/`）是**常态**，
     * 上限设成 1 会让正常配置被判成「重定向过多」。
     */
    internal const val MAX_REDIRECTS = 5

    /**
     * 发一次 PROPFIND 探测。
     *
     * @param baseUrl 已规范化（[normalizeBaseUrl]）的地址
     * @param username / [password] 明文（仅在内存中存活本次调用）
     * @param timeoutSeconds 会被钳到 `[1, MAX_TIMEOUT_SECONDS]`
     */
    fun probe(
        baseUrl: String,
        username: String,
        password: String,
        allowInsecureTls: Boolean = false,
        timeoutSeconds: Int = WebDavConfig.DEFAULT_TIMEOUT_SECONDS,
    ): WebDavProbeResult {
        if (baseUrl.isBlank()) {
            return WebDavProbeResult(WebDavProbeOutcome.NETWORK_ERROR, detail = "地址为空")
        }

        val timeout = clampTimeout(timeoutSeconds)
        val client = buildClient(timeout, allowInsecureTls)
        var url = baseUrl
        var withAuth = username.isNotEmpty()

        try {
            var hop = 0
            while (true) {
                val response = client.newCall(buildRequest(url, username, password, withAuth)).execute()
                response.use { resp ->
                    val code = resp.code

                    // ---- 重定向：自己处理，**保方法保 body**（见类注释第 2 点）----
                    if (code in REDIRECT_CODES) {
                        val location = resp.header("Location")
                            ?: return WebDavProbeResult(
                                WebDavProbeOutcome.NETWORK_ERROR,
                                httpCode = code,
                                detail = "重定向响应缺少 Location 头",
                                redirectHops = hop
                            )

                        if (hop >= MAX_REDIRECTS) {
                            // ⚠️ 归 NETWORK_ERROR（不单列枚举），原因写在 detail 里 ——
                            // 见 WebDavProbeOutcome 的 KDoc：它的用户处置与「地址填错」同类。
                            return WebDavProbeResult(
                                WebDavProbeOutcome.NETWORK_ERROR,
                                httpCode = code,
                                detail = "重定向超过 $MAX_REDIRECTS 跳（最后指向 $location）",
                                redirectHops = hop
                            )
                        }

                        val nextUrl = resolveLocation(url, location)
                            ?: return WebDavProbeResult(
                                WebDavProbeOutcome.NETWORK_ERROR,
                                httpCode = code,
                                detail = "无法解析重定向目标：$location",
                                redirectHops = hop
                            )

                        // ⚠️ 跨 host 时**丢弃** Authorization —— 防凭据被转发给第三方主机。
                        if (!sameHost(url, nextUrl)) {
                            withAuth = false
                        }

                        url = nextUrl
                        hop++
                        continue
                    }

                    // ---- 正常响应：映射 ----（见表）
                    val outcome = mapCode(code)
                    return WebDavProbeResult(
                        outcome = outcome,
                        httpCode = code,
                        detail = if (outcome == WebDavProbeOutcome.NETWORK_ERROR) "HTTP $code" else null,
                        redirectHops = hop
                    )
                }
            }
        } catch (e: UnknownHostException) {
            return WebDavProbeResult(WebDavProbeOutcome.NETWORK_ERROR, detail = "无法解析主机：${e.message}")
        } catch (e: SSLException) {
            return WebDavProbeResult(WebDavProbeOutcome.NETWORK_ERROR, detail = "TLS 失败：${e.message}")
        } catch (e: IOException) {
            return WebDavProbeResult(WebDavProbeOutcome.NETWORK_ERROR, detail = "${e.javaClass.simpleName}: ${e.message}")
        } catch (e: IllegalArgumentException) {
            // OkHttp 对非法 URL（缺 scheme 等）抛这个 —— 属「地址填错」，不是网络问题，
            // 但对用户的表现一致（检查地址）。
            return WebDavProbeResult(WebDavProbeOutcome.NETWORK_ERROR, detail = "地址无法解析：${e.message}")
        }
    }

    /** 状态码映射表（定案，逐值实现）。 */
    private fun mapCode(code: Int): WebDavProbeOutcome = when (code) {
        207, 200 -> WebDavProbeOutcome.SUCCESS
        401, 403 -> WebDavProbeOutcome.AUTH_FAILED
        404 -> WebDavProbeOutcome.NOT_FOUND
        405, 501 -> WebDavProbeOutcome.NOT_SUPPORTED
        else -> WebDavProbeOutcome.NETWORK_ERROR
    }

    // ⚠️ 与 WebDavClient 共用一份（收敛前是两份，改一处忘另一处 ⇒ 测试连接与模块行为不一致）
    private val REDIRECT_CODES get() = WebDavHttpSupport.REDIRECT_CODES

    private fun buildRequest(
        url: String,
        username: String,
        password: String,
        withAuth: Boolean
    ): Request = Request.Builder()
        .url(url)
        // ⚠️ `toRequestBody` 内部会把 media type 设成 Content-Type，
        // **不要**再手动 `.header("Content-Type", ...)`（会与 RequestBody 自带的重复）。
        .method(PROPFIND_METHOD, PROPFIND_XML.toRequestBody(PROPFIND_MEDIA_TYPE))
        .header("Depth", "0")
        .apply {
            if (withAuth && username.isNotEmpty()) {
                // ⚠️ 必须显式传 UTF_8 —— 三参重载不传时默认 ISO-8859-1，
                // 用户名/密码含中文时静默编错。
                header("Authorization", Credentials.basic(username, password, Charsets.UTF_8))
            }
        }
        .build()

    private fun buildClient(timeoutSeconds: Int, allowInsecureTls: Boolean): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .connectTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            .writeTimeout(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            // ⚠️ 关掉自动跟随（见类注释第 1 点）：我们需要看见重定向链才能给出诊断，
            // 并且要在跳数超限时给出明确结论，而不是让 OkHttp 悄悄跟到底。
            .followRedirects(false)
            .followSslRedirects(false)

        // ⚠️ 只替换 TrustManager（允许自签名/过期证书），**保留**默认 HostnameVerifier。
        // 装配细节与 WebDavClient 共用一份（WebDavHttpSupport）。
        WebDavHttpSupport.applyInsecureTlsIfNeeded(builder, allowInsecureTls)

        return builder.build()
    }

    /** 把相对 `Location` 解析成绝对 URL。解析失败返回 null。 */
    internal fun resolveLocation(currentUrl: String, location: String): String? {
        return try {
            val base = currentUrl.toHttpUrlOrNull() ?: return null
            base.resolve(location)?.toString()
        } catch (e: Exception) {
            null
        }
    }

    /** 两个 URL 是否同 host（跨 host 时要丢弃 Authorization）。 */
    internal fun sameHost(a: String, b: String): Boolean {
        val ua = a.toHttpUrlOrNull() ?: return false
        val ub = b.toHttpUrlOrNull() ?: return false
        return ua.host.equals(ub.host, ignoreCase = true) && ua.port == ub.port
    }
}
