package com.chaomixian.vflow.core.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * WebDAV 目标地址的拼接器（纯 JVM，无 Android 依赖）。
 *
 * ## ⚠️⚠️ 为什么**必须**逐段 `addPathSegment`，不许 `baseUrl + "/" + path`
 *
 * 字符串拼接的失败模式是**静默**的 —— 不报错，只是 URL 与用户以为的不是同一个：
 *
 * | 输入 | 字符串拼接的产物 | 服务器看到的 |
 * |---|---|---|
 * | `我的 文件.txt` | 原样带空格 | 请求行被空格截断，或 400 |
 * | `a#b` | `a#b` | `#` 之后是 **fragment**，**根本不发给服务器** |
 * | `a?b` | `a?b` | `?` 之后变成 **query string**，路径错了 |
 *
 * `HttpUrl.addPathSegment` 按 RFC 3986 的 path-segment 规则百分号编码，上述三种全部正确。
 *
 * ## ⚠️⚠️ 为什么必须拦 `..`（**实测结论，不是理论担忧**）
 *
 * `HttpUrl` 自己**不拒绝** `..` 与 `.`，而是按 RFC 3986 的路径归一化**静默改写**：
 *
 * ```
 * 实测（okhttp 4.12.0，base = https://dav.example.com/dav/）：
 *   addPathSegment("..")  ⇒  https://dav.example.com/          ← 静默上跳一级，无任何报错
 *   addPathSegment(".")   ⇒  https://dav.example.com/dav/      ← 被静默丢弃
 * ```
 *
 * 于是一个远端 path 里出现 `..`（用户手输、或**变量里带进来的**）会让请求打到**另一个目录**，
 * 而日志里显示的是「成功」。⇒ 本类直接判非法，把「静默跑错目标」变成「明确失败」。
 *
 * ⚠️ **段内的控制字符同样拒绝** —— 它们会在请求行/头部里造成歧义（CR/LF 尤其危险），
 * 而 OkHttp 对控制字符的编码行为依实现，不如我们自己拦掉。
 */
object WebDavUrlBuilder {

    /**
     * 拼出目标 URL。
     *
     * 规则：`baseUrl`（已规范化）→ 逐段 `addPathSegment(remoteBasePath 各段)`
     * → 逐段 `addPathSegment(path 各段)` → `directory=true` 时再补一个空段产生尾斜杠。
     *
     * ⚠️ 尾斜杠对部分服务器是**语义要求**（目录 URL 无尾斜杠会被 301 到带斜杠的版本，
     * 而某些服务器在不跟随重定向的客户端上直接 404）。见 [WebDavClient] 的 `directory` 参数。
     *
     * @param baseUrl 已规范化的地址（[normalizeBaseUrl] 产出，无尾斜杠）
     * @param remoteBasePath 配置里的远端根路径，可为空、可带前导/尾随斜杠
     * @param path 本次操作相对于 `remoteBasePath` 的路径
     * @param directory true ⇒ 结果以 `/` 结尾
     * @return null 表示**目标非法**（baseUrl 无法解析 / 任一段是 `.` 或 `..` / 段内含控制字符）
     */
    fun resolve(
        baseUrl: String,
        remoteBasePath: String,
        path: String,
        directory: Boolean,
    ): HttpUrl? {
        val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
        val segments = splitSegments(remoteBasePath, path) ?: return null

        val builder = base.newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        // ⚠️ 空段即尾斜杠 —— 已实测：addPathSegment("") 产出 `/` 结尾，且不会与
        // 「上一次已有尾斜杠」叠加成 `//`（HttpUrl 会归一化）。
        if (directory) builder.addPathSegment("")

        return builder.build()
    }

    /**
     * 取「**该路径的所有祖先目录**」用的段列表 —— 与 [resolve] 共用同一套切段与校验规则。
     *
     * ⚠️ 与 [resolve] 的差别只有一个：**本函数保留最后一段**。
     * [resolve] 是给请求用的（要的就是完整路径），而补建目录要的是「它上面有哪些层级」
     * （由调用方 `dropLast(1)` 自行决定要不要去掉文件名那一段）。
     *
     * ⚠️ 之所以**必须走同一个 `splitSegments`**：切段规则（滤空段、拦 `.`/`..`、拦控制字符）
     * 若在这里另写一份，就会出现「能请求的路径建不出目录」这类静默不一致。
     *
     * @return null = 非法路径（含 `.` / `..` / 控制字符）；空列表 = 目标就是根（无需建任何目录）
     */
    internal fun splitSegmentsForAncestors(path: String): List<String>? = splitSegments(path)

    /**
     * 把 `HttpUrl.toString()` 里的**百分号编码段解回可读文本**，供**给人看的诊断串**使用。
     *
     * ## 为什么需要它
     *
     * `HttpUrl` 对非 ASCII 路径段做百分号编码（`/dav/自动备份.json` →
     * `/dav/%E8%87%AA%E5%8A%A8%E5%A4%87%E4%BB%BD.json`）。**那个形式是对的** ——
     * 它才是真正发出去的 URL，可直接粘进浏览器或 `curl` 复现。
     *
     * 但把它原样丢给用户看（例如 409 报错里的「目标：…」）会让**中文路径完全不可读**，
     * 而中文路径恰恰是本项目的常态（备份文件名默认带中文）。⇒ 诊断串里用本函数解码。
     *
     * ⚠️ **只用于展示，绝不用于请求** —— 解码后的字符串不再是合法 URL
     * （空格、`#`、`?` 会改变语义）。要发请求请始终用 [resolve] 的返回值。
     *
     * ⚠️ 解码失败（畸形 `%` 序列）时**原样返回**，不抛 —— 它是给人看的辅助信息，
     * 不该因为一个畸形 URL 把真正的错误信息盖掉。
     */
    fun readableHttpUrl(url: String): String = try {
        java.net.URLDecoder.decode(url, Charsets.UTF_8.name())
    } catch (_: Exception) {
        url
    }

    /**
     * 把多个路径片段拼起来切段：按 `/` 切、`trim()`、丢空串。
     *
     * 返回 null 表示**非法**（含 `.`/`..` 或控制字符），与「切出空列表」是两件事 ——
     * 后者是合法的（操作根目录）。
     */
    private fun splitSegments(vararg parts: String): List<String>? {
        val segments = mutableListOf<String>()
        for (part in parts) {
            for (raw in part.split('/')) {
                val segment = raw.trim()
                if (segment.isEmpty()) continue
                // ⚠️ 精确比较 `.` / `..`（`trim` 之后）——不拦 `...` 之类，那是合法的文件名。
                if (segment == "." || segment == "..") return null
                if (segment.any { it.code < 0x20 || it.code == 0x7F }) return null
                segments.add(segment)
            }
        }
        return segments
    }
}
