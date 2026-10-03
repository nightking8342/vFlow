package com.chaomixian.vflow.core.webdav

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * WebDAV 两侧（[WebDavProbe] 的「测试连接」与 [WebDavClient] 的五动词）共用的 HTTP 装配。
 *
 * ## 为什么有这个文件
 *
 * 收敛前，下列三样在 `WebDavProbe` 与 `WebDavClient` 里**各写了一份**：
 *
 * | 重复项 | 重复的代价 |
 * |---|---|
 * | `REDIRECT_CODES = setOf(301, 302, 303, 307, 308)` | 两处各写一个集合 ⇒ **改一处忘另一处**，表现是「测试连接能过、模块执行报错」（或反过来） |
 * | `trustAllTrustManager()` | 8 行匿名类，两份**逐字相同**；改一份（例如将来加 `checkServerTrusted` 的日志）另一份不动 |
 * | `SSLContext` + `sslSocketFactory` 装配 | 同上，且这段的正确形状（**只换 TrustManager、保留默认 HostnameVerifier**）是安全相关的 |
 *
 * ⚠️ **刻意收敛进来的**只有这三样。**没有**收敛进来的：
 *
 * - ⚠️ **`followRedirects(false)` / `followSslRedirects(false)` 仍留在各自的 `buildClient()` 里**。
 *   它是一个**意图声明**（「这家伙不自动跟随」），读代码的人应当在装配 OkHttp 的地方
 *   直接看见它，而不是跳进一个 helper 才知道。`WebDavSettingsEntryTest` 也直接扫
 *   `WebDavProbe.kt` 的源码断言这两行在。
 * - ⚠️ **`MAX_REDIRECTS` / `resolveLocation` / `sameHost` 仍留在 [WebDavProbe]**：前者的取值
 *   约束（2..10，理由见其 KDoc）有独立断言；后两者是纯函数、各有单测直接调 `WebDavProbe.xxx`。
 *   把它们搬到本文件只会让测试跟着改一遍，收益为零。
 *
 * ## ⚠️ 安全约束（本文件是最容易改坏安全性的地方）
 *
 * `applyInsecureTlsIfNeeded` **只**替换 `X509TrustManager`（允许自签名 / 过期证书），
 * **保留**默认 `HostnameVerifier`。
 *
 * ⇒ **不要**在这里加 `OkHttpClient.Builder().hostnameVerifier { _, _ -> true }`。
 * 「允许自签名」与「允许任意中间人」是两件事，后者比前者危险得多 —— 而且加它不会让
 * 任何现有测试变红（没有测试能覆盖「中间人」），是典型的**静默劣化**。
 */
internal object WebDavHttpSupport {

    /**
     * 需要自己处理的重定向状态码。
     *
     * ⚠️ **这一份是两个客户端唯一的来源**（收敛前是两份）。
     * 301/302/303/307/308 一到，就由我们自己的循环读 `Location`、**保方法保 body** 重发。
     *
     * 注：PROPFIND 在 OkHttp 里本就不会被降级（实测 `redirectsToGet=false`），
     * 但 PUT/DELETE/MKCOL **会**被降级成 GET —— 关掉自动跟随是唯一能同时护住五种动词的做法。
     */
    val REDIRECT_CODES: Set<Int> = setOf(301, 302, 303, 307, 308)

    /**
     * 信任一切证书的 `X509TrustManager`。
     *
     * ⚠️ **仅在 `allowInsecureTls = true` 时使用**，且**只在** TLS 建链用
     * （见 [applyInsecureTlsIfNeeded]）。它的存在理由是用户连的是自签名证书的私有 NAS。
     */
    fun trustAllTrustManager(): X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * 按需给 [builder] 装 `sslSocketFactory`。
     *
     * `allowInsecureTls = false` 时**什么都不做**（走 OkHttp 默认的证书校验）——
     * 这是绝大多数用户的路径，必须保持与「没有这段代码」完全一致。
     *
     * ⚠️ **不设 `hostnameVerifier`**：见本文件类注释的安全约束。
     */
    fun applyInsecureTlsIfNeeded(builder: OkHttpClient.Builder, allowInsecureTls: Boolean) {
        if (!allowInsecureTls) return

        val trustManager = trustAllTrustManager()
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), SecureRandom())
        }
        builder.sslSocketFactory(sslContext.socketFactory, trustManager)
    }
}
