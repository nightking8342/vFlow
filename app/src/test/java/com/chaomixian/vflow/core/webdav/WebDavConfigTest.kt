package com.chaomixian.vflow.core.webdav

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [WebDavConfig] 的纯 JVM 测试：地址规范化 + URL 拼接 + **JSON 往返**。
 *
 * ⚠️ JSON 往返是本任务需求点名的三项单测之一 —— 配置数据类本身**不需要 Context**，
 * Gson 走反射，故可纯 JVM 测。
 */
class WebDavConfigTest {

    private val gson = Gson()

    // ---------- normalizeBaseUrl ----------

    @Test
    fun normalize_trimsWhitespace() {
        assertEquals("https://dav.example.com", normalizeBaseUrl("  https://dav.example.com  "))
    }

    @Test
    fun normalize_removesSingleTrailingSlash() {
        assertEquals("https://dav.example.com", normalizeBaseUrl("https://dav.example.com/"))
    }

    @Test
    fun normalize_removesMultipleTrailingSlashes() {
        assertEquals("https://dav.example.com", normalizeBaseUrl("https://dav.example.com///"))
    }

    @Test
    fun normalize_keepsSchemeDoubleSlash() {
        assertEquals("https://dav.example.com", normalizeBaseUrl("https://dav.example.com/"))
        assertTrue(normalizeBaseUrl("https://dav.example.com").startsWith("https://"))
    }

    @Test
    fun normalize_keepsLeadingSlash() {
        // ⚠️⚠️ 这条才是 `trimEnd('/')` vs `trim('/')` 的**真正判别式**。
        // `trim('/')` 两侧都削 ⇒ 前导斜杠被吃掉；`trimEnd('/')` 只削尾 ⇒ 前导保留。
        // 之所以要保留前导：它是「根相对路径」的一部分，削掉会让 `/dav` 变成 `dav`
        //（后续 joinUrl 拼出来就少一层）。
        assertEquals("/remote.php/dav", normalizeBaseUrl("/remote.php/dav/"))
        assertEquals("/dav", normalizeBaseUrl("/dav/"))
    }

    @Test
    fun normalize_allSlashesBecomesEmpty() {
        // 全斜杠的怪值 ⇒ 空串，调用侧按「地址为空」拒绝。
        assertEquals("", normalizeBaseUrl("///"))
    }

    @Test
    fun normalize_noTrailingSlashIsNoop() {
        assertEquals("https://dav.example.com/dav", normalizeBaseUrl("https://dav.example.com/dav"))
    }

    @Test
    fun normalize_preservesInnerSlashes() {
        assertEquals(
            "https://dav.example.com/remote.php/dav",
            normalizeBaseUrl("https://dav.example.com/remote.php/dav/")
        )
    }

    // ---------- joinUrl ----------

    @Test
    fun join_emptyPathReturnsBase() {
        // ⚠️ **不加尾斜杠** —— PROPFIND 的目标就是配置本身的 baseUrl。
        assertEquals("https://dav.example.com", joinUrl("https://dav.example.com", ""))
    }

    @Test
    fun join_addsExactlyOneSlash() {
        assertEquals("https://dav.example.com/a/b", joinUrl("https://dav.example.com/", "/a/b/"))
    }

    @Test
    fun join_handlesLeadingAndTrailingSlashesInPath() {
        assertEquals("https://dav.example.com/a", joinUrl("https://dav.example.com", "/a/"))
        assertEquals("https://dav.example.com/a", joinUrl("https://dav.example.com/", "a"))
    }

    @Test
    fun join_blankPathIsTreatedAsEmpty() {
        assertEquals("https://dav.example.com", joinUrl("https://dav.example.com/", "   "))
    }

    // ---------- JSON 往返 ----------

    @Test
    fun webDavConfig_jsonRoundTrips_includingDefaults() {
        val original = WebDavConfig(
            id = "id-1",
            name = "我的网盘",
            baseUrl = "https://dav.example.com",
            username = "user",
            encryptedPassword = "BASE64CIPHERTEXT=="
        )

        val restored = gson.fromJson(gson.toJson(original), WebDavConfig::class.java)

        assertEquals(original, restored)
        // ⚠️ 重点断言默认值在往返后**仍然存在** —— 若某个字段被移出 data class 参数列表，往返后会丢。
        assertFalse(restored.allowInsecureTls)
        assertEquals(WebDavConfig.DEFAULT_TIMEOUT_SECONDS, restored.timeoutSeconds)
        assertEquals("", restored.remoteBasePath)
    }

    @Test
    fun webDavConfig_jsonRoundTrips_preservesAllFields() {
        val original = WebDavConfig(
            id = "id-2",
            name = "Full",
            baseUrl = "https://dav.example.com",
            username = "u",
            encryptedPassword = "CT",
            allowInsecureTls = true,
            timeoutSeconds = 42,
            remoteBasePath = "/a/b"
        )

        val restored = gson.fromJson(gson.toJson(original), WebDavConfig::class.java)

        assertEquals(original, restored)
        assertTrue(restored.allowInsecureTls)
        assertEquals(42, restored.timeoutSeconds)
        assertEquals("/a/b", restored.remoteBasePath)
    }

    @Test
    fun webDavConfig_jsonArrayRoundTrips() {
        // 这正是 WebDavConfigStore.getAll / upsert 的真实读写形状（JSON 数组）。
        val original = listOf(
            WebDavConfig("a", "A", "https://a.example.com", "ua", "CTa"),
            WebDavConfig("b", "B", "https://b.example.com", "ub", "CTb", timeoutSeconds = 30),
            WebDavConfig("c", "C", "https://c.example.com", "uc", "CTc", remoteBasePath = "/x")
        )

        val json = gson.toJson(original.toTypedArray())
        val restored = gson.fromJson(json, Array<WebDavConfig>::class.java).toList()

        assertEquals(original, restored)
        // ⚠️ 断言**顺序保持** —— Store 的 KDoc 写明「顺序即数组顺序」，UI 按持久化顺序展示、不排序。
        assertEquals(listOf("a", "b", "c"), restored.map { it.id })
    }

    @Test
    fun webDavConfig_missingFieldsDeserializeAsNull_thenCoerced() {
        // ⚠️⚠️ 这是 R13 的机器化证据：Gson **走反射、不调构造函数**，缺字段会被填 `null`
        // （不会走 Kotlin 的默认值），而字段声明是非空 String ⇒ UI 侧会拿到 null。
        val json = """{"id":"only-id"}"""

        val raw = gson.fromJson(json, WebDavConfig::class.java)

        // 1) 先确认「Gson 走反射、不调构造函数」这件事**真的发生**（否则下面的兜底测的就是空气）。
        //    ⚠️ 注意类型差异：String 字段填 **null**，而 Int 字段填 **0**、Boolean 填 **false**
        //    —— 即非空类型**都可能被填成非法值**，兜底必须逐类型处理。
        assertNull("Gson 应对缺失的 String 字段填 null", raw.name)
        assertNull("Gson 应对缺失的 String 字段填 null", raw.baseUrl)
        assertNull("Gson 应对缺失的 String 字段填 null", raw.encryptedPassword)
        assertEquals("Gson 应对缺失的 Int 字段填 0（不是 null）", 0, raw.timeoutSeconds)

        // 2) 再确认 Store 侧的兜底能把 null 收敛成可用值。
        val coerced = coerceConfig(
            id = raw.id,
            name = raw.name,
            baseUrl = raw.baseUrl,
            username = raw.username,
            encryptedPassword = raw.encryptedPassword,
            allowInsecureTls = raw.allowInsecureTls,
            timeoutSeconds = raw.timeoutSeconds,
            remoteBasePath = raw.remoteBasePath
        )

        assertEquals("only-id", coerced.id)
        assertEquals("", coerced.name)
        assertEquals("", coerced.baseUrl)
        assertEquals("", coerced.username)
        assertEquals("", coerced.encryptedPassword)
        assertFalse("缺失的布尔应兜底为 false", coerced.allowInsecureTls)
        assertEquals("缺失的超时应兜底为默认值", WebDavConfig.DEFAULT_TIMEOUT_SECONDS, coerced.timeoutSeconds)
        assertEquals("", coerced.remoteBasePath)
    }

    @Test
    fun clampTimeout_boundsToLegalRange() {
        assertEquals(WebDavConfig.MIN_TIMEOUT_SECONDS, clampTimeout(0))
        assertEquals(WebDavConfig.MIN_TIMEOUT_SECONDS, clampTimeout(-5))
        assertEquals(WebDavConfig.MAX_TIMEOUT_SECONDS, clampTimeout(99999))
        assertEquals(30, clampTimeout(30))
    }

    @Test
    fun coerceConfig_nonPositiveTimeoutFallsBackToDefault() {
        // ⚠️ 用户手工把 timeoutSeconds 改成 0 ⇒ 回落到默认值 15。
        // 之所以不是「钳到下限 1」：Gson 对**缺失**的 Int 字段也填 0，两者在数据层不可区分；
        // 钳到 1 会让「旧数据缺字段」静默劣化成「1 秒超时」，回落到默认值才是安全的一侧。
        val raw = gson.fromJson(
            """{"id":"x","name":"n","baseUrl":"https://a","username":"u","encryptedPassword":"c","timeoutSeconds":0}""",
            WebDavConfig::class.java
        )

        val coerced = coerceConfig(
            raw.id, raw.name, raw.baseUrl, raw.username, raw.encryptedPassword,
            raw.allowInsecureTls, raw.timeoutSeconds, raw.remoteBasePath
        )

        assertEquals(WebDavConfig.DEFAULT_TIMEOUT_SECONDS, coerced.timeoutSeconds)
    }

    @Test
    fun coerceConfig_oversizedTimeoutIsClampedToMax() {
        val raw = gson.fromJson(
            """{"id":"x","name":"n","baseUrl":"https://a","username":"u","encryptedPassword":"c","timeoutSeconds":99999}""",
            WebDavConfig::class.java
        )

        val coerced = coerceConfig(
            raw.id, raw.name, raw.baseUrl, raw.username, raw.encryptedPassword,
            raw.allowInsecureTls, raw.timeoutSeconds, raw.remoteBasePath
        )

        assertEquals(WebDavConfig.MAX_TIMEOUT_SECONDS, coerced.timeoutSeconds)
    }

    // ---------- ⚠️ @SerializedName 加固的接线锚定（源码扫描）----------

    /**
     * ⚠️⚠️ **本用例存在的理由**：`WebDavConfig` 经 Gson 持久化进 prefs，而**本仓库没有
     * `-applymapping` / 混淆字典** ⇒ R8 的字段短名只对当前二进制稳定，**重新构建就可能重分配**
     * ⇒ 用户升级 App 后旧 JSON 用旧名读、新代码用新名 ⇒ 配置静默丢失（实测已复现：
     * 无注解 + 字段重分配 ⇒ 读回全为 null）。
     *
     * 加固手段是每个字段上的 `@SerializedName`（把 key 钉死，与运行时字段名解耦）。
     * **纯 JVM 测不到 dex 里的注解**，故这里扫源码 —— 形态照本仓库既有的源码扫描型测试。
     *
     * 防的是**将来加字段时漏注解**：那是这类腐蚀唯一的入口。
     */
    @Test
    fun everyWebDavConfigFieldCarriesSerializedName() {
        // 测试工作目录 = app/（与其它源码扫描型测试一致）。
        val source = java.io.File(
            "src/main/java/com/chaomixian/vflow/core/webdav/WebDavConfig.kt"
        )
        org.junit.Assert.assertTrue("找不到 WebDavConfig.kt", source.exists())
        val code = source.readText()

        // 提取 `@SerializedName("x") val y` 配对。
        val pairs = Regex("""@SerializedName\("([^"]+)"\)\s*\n?\s*val\s+(\w+)""")
            .findAll(code)
            .map { it.groupValues[1] to it.groupValues[2] }
            .toList()

        // 1) 注解值与属性名必须一致 —— 否则「注解写错一个字母」会让该字段静默读不出。
        val mismatched = pairs.filter { (key, prop) -> key != prop }
        org.junit.Assert.assertTrue(
            "🔴 @SerializedName 的 key 必须与属性名一致（写错则该字段静默丢失）。不一致：$mismatched",
            mismatched.isEmpty()
        )

        // 2) 字段集合必须逐一覆盖（漏一个 ⇒ 那个字段跨版本不稳）。
        val annotated = pairs.map { it.second }.toSet()
        val expected = setOf(
            "id", "name", "baseUrl", "username", "encryptedPassword",
            "allowInsecureTls", "timeoutSeconds", "remoteBasePath"
        )
        org.junit.Assert.assertEquals(
            "🔴 WebDavConfig 的每个字段都必须有 @SerializedName —— 新增字段时漏加会让它在 App 升级后" +
                "静默丢失（本仓库无 -applymapping，字段短名逐构建可变）",
            expected,
            annotated
        )

        // 3) 防空转：确认真的扫到了注解（剥错/扫空时上面的断言会「因为空而通过」）。
        org.junit.Assert.assertEquals("应恰好扫到 8 个字段注解", 8, pairs.size)
    }
}
