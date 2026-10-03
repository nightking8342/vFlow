// 文件: test/java/com/chaomixian/vflow/core/backup/BackupModelFormatTest.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.backup.scopes.BackupGlobalVariable
import com.chaomixian.vflow.core.backup.scopes.GlobalVariableScope
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.google.gson.annotations.SerializedName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **加固：把备份格式的 key 从「运行时字段名」改为 `@SerializedName` 固化。**
 *
 * ## ⚠️ 这不是「修 bug」—— 当前实现没有可观测故障
 *
 * 背景：`BackupGlobalVariable` 是 `core/backup/` 里**唯一**由 Gson **反射**序列化、
 * 又**不在任何 `-keep` 范围**的持久化模型（`core.backup.scopes` 未列入
 * `proguard-rules.pro`）。R8 会把它的字段名改短（`name`→`a` 之类）。
 *
 * - **同一次构建内不会失败**：Gson 的写与读都用运行时字段名，**对称**。
 * - **跨版本会失败**：全仓**没有 `-applymapping`、也没有混淆字典**（已 grep 核实）
 *   ⇒ 字段短名只对当前这一份二进制稳定。备份文件的寿命天生比一次构建长
 *   （用户升级 App 后导入旧备份）⇒ 旧文件里的键对不上新二进制 ⇒
 *   `GlobalVariableScope.parse` 是**手工按键读**的 ⇒ 返回 null ⇒
 *   那条变量被**静默跳过**（「恢复成功但少了几条变量」）。
 *
 * `@SerializedName` 把 key 固化在源码里，从此与 R8 怎么改名无关。
 *
 * ## 为什么这条测试必须是**反射 + 逐字段**的
 *
 * 只断言「往返正常」**测不出**这个隐患 —— 同一次构建内往返本来就正常。
 * 只有断言「注解确实在、且值确实是这三个」才能拦住
 * 「将来有人加字段忘了注解」与「有人把注解删了」。
 */
class BackupModelFormatTest {

    private companion object {
        /** 备份格式契约：字段 → 它必须固化的 key。**改这个表 = 改备份格式**。 */
        val EXPECTED_KEYS = mapOf(
            "name" to "name",
            "type" to "type",
            "value" to "value",
        )
    }

    // ── 主断言：注解确实在、且值就是那三个 ──────────────────

    @Test
    fun `every BackupGlobalVariable field pins its JSON key with SerializedName`() {
        val fields = BackupGlobalVariable::class.java.declaredFields
            .filterNot { it.isSynthetic }
            // Kotlin 编译器为 data class 生成的 `$stable` 合成字段（Compose/内联类相关）
            .filterNot { it.name.startsWith("$") }

        // ⚠️⚠️ **断言的是注解的值，不是字段名** —— 两者看似等价，但：
        // · 断言字段名会让「把 Kotlin 属性改名、同时保留 @SerializedName」
        //   这种**安全的重构**变红（格式其实没变），是误报；
        // · 而真正要守的是「写进 JSON 的 key 是哪三个」—— 那只有注解说了算。
        val missing = fields.filter { it.getAnnotation(SerializedName::class.java) == null }
        assertTrue(
            "❌ 这些字段缺少 @SerializedName：${missing.map { it.name }}\n" +
                "R8 会把字段名改短，而全仓没有 -applymapping / 混淆字典 ⇒ " +
                "字段短名**跨版本不稳定** ⇒ 用户升级 App 后导入旧备份时该字段读不到 ⇒ " +
                "那条全局变量被**静默跳过**（无任何报错）。\n" +
                "这是持久化格式，必须把 key 固化在源码里。",
            missing.isEmpty(),
        )

        val pinnedKeys = fields.map { it.getAnnotation(SerializedName::class.java).value }.toSet()
        assertEquals(
            "❌ 固化的 JSON key 变了 —— 改它等于改备份格式（旧备份将读不回来）。\n" +
                "若确实要改格式，必须同时写兼容读取逻辑，并更新本测试的期望表。",
            EXPECTED_KEYS.values.toSet(),
            pinnedKeys,
        )
    }

    @Test
    fun `the pinned keys are the ones actually written into the envelope`() {
        // 端到端：export 出来的 JSON 里必须真的出现这三个 key。
        // （注解写对了但序列化改了别的路，本条会红。）
        val env = FakeBackupEnvironment(
            variables = mapOf(
                "cfg_name" to VString("v"),
                "cfg_num" to VNumber(3.0),
                "cfg_flag" to VBoolean(true),
            )
        )
        val payload = GlobalVariableScope().export(env, null)
        // 按 name 取而不是 `first()` —— export 按行名排序，`cfg_flag` 排在 `cfg_name` 前。
        val entry = payload.data.asJsonArray
            .map { it.asJsonObject }
            .first { it.get("name").asString == "cfg_name" }

        assertEquals(EXPECTED_KEYS.keys, entry.entrySet().map { it.key }.toSet())
        assertEquals("cfg_name", entry.get("name").asString)
        assertEquals("string", entry.get("type").asString)
        assertEquals("v", entry.get("value").asString)
    }

    // ── 包内自查：还有没有别的同形态暴露面 ─────────────────

    @Test
    fun `no other self declared model in this package is reflection serialized`() {
        // ⚠️ 这条扫的是**同形态暴露面**：凡「在同一文件里声明了 data class、
        //     又对该 class 走 Gson 反射序列化」的，都必须带 @SerializedName。
        //
        // ## 判据（每一处都是实测踩出来的）
        //
        // 1. **先剥注释** —— 本包的 KDoc 里大量出现 `env.json.toJsonTree(...)` 字样
        //    （就是在解释这件事），不剥会把 `BackupEnvelope.kt` 这类文件误判成违规。
        // 2. **手写 `toJson()` / `fromJson()` 的类豁免** —— `EncryptionSection` 是
        //    **手工按键读写**的（`BackupEnvelope.kt:390` / `:411`），根本不走反射。
        //    对这种类加 `@SerializedName` 是**虚假的安全感**（注解不生效），
        //    而误报会逼着人去加、最终让这条约束贬值并被删掉。
        // 3. **只看顶层 `data class`**（行首无缩进）—— 嵌套的 `Header` / `ExportResult`
        //    不落盘（前者只在 `read` 里临时构造，后者纯内存），没有暴露面。
        //
        // ⚠️ 判据刻意宽松（宁可漏报也不误报）：漏报的后果是新模型忘了注解，
        //    而那由上面那条**逐字段反射断言**兜着（它对 `BackupGlobalVariable` 是强的）；
        //    误报的后果是这条约束被下一个人删掉，那才是真的失去防线。
        val root = File("src/main/java/com/chaomixian/vflow/core/backup")
        assertTrue("core/backup 目录不存在", root.isDirectory)

        val offenders = mutableListOf<String>()
        var scannedSerializerFiles = 0

        root.walkTopDown()
            .filter { it.isFile && it.name.endsWith(".kt") }
            .forEach { file ->
                val text = stripComments(file.readText())
                if (!text.contains("toJsonTree(") && !text.contains("json.toJson(")) return@forEach
                scannedSerializerFiles++

                // 手写序列化的类豁免（见判据 2）
                if (Regex("""fun\s+toJson\s*\(\s*\)""").containsMatchIn(text)) return@forEach

                val declarations = Regex("""(?m)^data class\s+(\w+)""").findAll(text)
                    .map { it.groupValues[1] }
                    .toList()

                for (name in declarations) {
                    if (!text.contains("@SerializedName")) {
                        offenders += "${file.name}: data class $name 被反射序列化但没有 @SerializedName"
                    }
                }
            }

        assertTrue(
            "❌ 本包内还有别的「自建 data class + Gson 反射序列化」没有固化 key：\n" +
                offenders.joinToString("\n") +
                "\n（同 BackupGlobalVariable 的理由：R8 改字段名 ⇒ 备份跨版本读不回来）\n" +
                "⚠️ 若该 class 是**手写** toJson/fromJson 的（不走反射），" +
                "那它不该进本名单 —— 请检查上面的豁免判据是否失效。",
            offenders.isEmpty(),
        )

        // 防空转：判据若写坏（例如 `toJsonTree(` 被改名），本条会一条都没扫到而假绿。
        assertTrue(
            "应当至少扫到若干「含 toJsonTree 的文件」—— 扫不到说明判据失效，上一条在空转。" +
                "实际扫到 $scannedSerializerFiles 个。",
            scannedSerializerFiles >= 2,
        )
    }

    @Test
    fun `the scan above is not vacuous because GlobalVariableScope declares a model`() {
        // 防空转的第二重：确认「同一文件里既有 toJsonTree 又有 data class」
        // 这个形态在本包里**确实存在**（就是 GlobalVariableScope.kt）——
        // 否则上一条的 offenders 恒空，它就等于没测。
        val file = File("src/main/java/com/chaomixian/vflow/core/backup/scopes/GlobalVariableScope.kt")
        val text = file.readText()
        assertTrue("GlobalVariableScope.kt 应当含 toJsonTree(", text.contains("toJsonTree("))
        assertTrue(
            "GlobalVariableScope.kt 应当声明 data class BackupGlobalVariable",
            text.contains("data class BackupGlobalVariable"),
        )
    }

    /**
     * 剥掉块注释与行注释。
     *
     * ⚠️ 不剥会让本包的 KDoc（大量出现 `toJsonTree` 字样）被当成真实调用点。
     */
    private fun stripComments(source: String): String {
        val noBlock = Regex("""/\*[\s\S]*?\*/""").replace(source, "")
        return noBlock.lines().joinToString("\n") { line ->
            val index = line.indexOf("//")
            if (index >= 0) line.substring(0, index) else line
        }
    }

    // ── 用探针证明「key 由注解决定，与字段名无关」────────────────
    //
    // ⚠️ 上面那条反射断言说的是「注解在且值对」，但**没有**证明
    // 「Gson 真的按注解写 key」。这两件事必须分开证明 —— 否则注解可能只是
    // 个装饰品（例如将来有人换成别的 JSON 库、或加了自定义 TypeAdapter）。
    //
    // 用一个**字段名模拟 R8 改短**的探针类来证：字段叫 `a`，但 key 必须是 `name`。

    /** 模拟「R8 把字段名改短、但 @SerializedName 保留」的情形。 */
    private data class RenamedProbe(
        @SerializedName("name") val a: String,
        @SerializedName("type") val b: String,
        @SerializedName("value") val c: Any?,
    )

    /** 对照组：**没有**注解、字段名也被改短的类 —— 它写出的就是短名。 */
    private data class UnannotatedProbe(val a: String, val b: String, val c: Any?)

    private val json: com.google.gson.Gson = com.google.gson.Gson()

    @Test
    fun `SerializedName wins over the field name so a renamed class still writes the pinned keys`() {
        val obj = json.toJsonTree(RenamedProbe("cfg", "string", "v")).asJsonObject

        assertEquals(
            "❌ 写出的 key 必须由 @SerializedName 决定，而不是字段名。\n" +
                "若这里出现 a/b/c，说明注解没生效 ⇒ R8 改名后备份格式就变了 ⇒ " +
                "用户升级 App 后旧备份读不回来（且恢复时会**静默少几条变量**）。",
            setOf("name", "type", "value"),
            obj.entrySet().map { it.key }.toSet(),
        )
        assertEquals("cfg", obj.get("name").asString)
    }

    @Test
    fun `the control group proves the probe would fail without the annotations`() {
        // ⚠️ 防空转：若 Gson 无论有没有注解都写出 `name`/`type`/`value`，
        //     上面那条就什么也没证明。这个对照组的输出是 `a`/`b`/`c` ⇒
        //     证明「注解确实是 key 的决定因素」。
        val obj = json.toJsonTree(UnannotatedProbe("cfg", "string", "v")).asJsonObject

        assertEquals(
            "对照组（无注解、字段名被改短）写出的应当是短名 —— " +
                "若不是，说明上一条断言在空转（Gson 无论有没有注解都写同一套 key）。",
            setOf("a", "b", "c"),
            obj.entrySet().map { it.key }.toSet(),
        )
    }

    @Test
    fun `a renamed model is still readable by the hand written parser`() {
        // `GlobalVariableScope.parse` 是**手工按键读**的（`obj.get("name")`），
        // 它本就与字段名无关 —— 这条把那个事实钉住：只要写出的 key 对，
        // 读端就永远读得到（读端不依赖反射）。
        val written = json.toJsonTree(RenamedProbe("cfg", "string", "v"))
        val parsed = GlobalVariableScope.parse(written)

        assertNotNull("改名后的模型写出的 JSON 必须仍能被 parse 解析", parsed)
        assertEquals("cfg", parsed!!.first)
        assertEquals("v", (parsed.second as VString).raw)
    }
}
