package com.chaomixian.vflow.core.workflow

import com.chaomixian.vflow.core.backup.SourceScan
import com.chaomixian.vflow.core.workflow.model.TileKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * **磁贴 Toast 文案的格式化契约**（真机崩溃回归，2026-10-06）。
 *
 * ## 这个文件存在的理由：一次真机崩溃
 *
 * 真机（小米 2308CPXD0C / Android 17）点「添加到控制中心」**直接闪退**，栈是：
 *
 * ```
 * java.util.IllegalFormatConversionException: d != java.lang.String
 *     at android.content.res.Resources.getString(Resources.java:671)
 * ```
 *
 * 根因：`tile_added` / `tile_removed` 两个字符串**还是 `%1$d`**（它们原本显示的是
 * **绝对索引**），而本次改动把调用点改成了传 `TileSlot.displayName(kind, slot)`
 * —— 那是**字符串**（`vFlow Execute 3`）。`%1$d` 收到 `String` ⇒ 当场抛。
 *
 * ⚠️⚠️ **为什么编译、lint 与既有单测都拦不住**：
 * - `getString(res, arg)` 的 `arg` 是 `vararg Any` ⇒ **类型不匹配编译得过**；
 * - `lintVitalRelease` 只查「资源引用的参数个数」，**不查类型**；
 * - 单测里从来没有人**真的把那两条字符串格式化一遍** —— 而这是唯一能发现它的方式。
 *
 * ## 本文件的做法：真的格式化一次
 *
 * 不是断言「源码里写了 `%1$s`」（那是**把要测的东西抄一遍**，改坏了也不会红），
 * 而是**把三条语言（中/英/日）里的真实格式串用真实的实参跑一遍
 * `String.format`** —— 资源没改对就当场抛 `IllegalFormatConversionException`，
 * 与真机崩溃**同源**。
 */
class TileStringFormatTest {

    /** 三份 `strings.xml`（与 `SwitchEditorSheetWiringTest` 同一套 locale 口径）。 */
    private val locales = listOf("values", "values-en", "values-ja")

    private fun stringXml(locale: String): String =
        File("src/main/res/$locale/strings.xml").readText()

    /** 从 `strings.xml` 取某个 `<string>` 的正文（含转义前的原文）。 */
    private fun rawString(locale: String, name: String): String {
        val xml = stringXml(locale)
        val match = Regex("""<string name="$name">(.*?)</string>""", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
        assertTrue("$locale/strings.xml 里找不到 `$name`", match != null)
        return match!!.groupValues[1]
    }

    /**
     * `tile_added` / `tile_removed` 必须能用**一个字符串实参**格式化。
     *
     * ⚠️ 实参用 `TileSlot.displayName(...)` 的**真实产物**，不是手工造的
     * 「vFlow Execute 1」 —— 后者会让「两者口径漂移」这类问题测不出来。
     */
    @Test
    fun `tile_added and tile_removed accept a String argument in all three languages`() {
        for (kind in TileKind.entries) {
            for (slot in listOf(0, 19)) {
                val name = TileSlot.displayName(kind, slot)
                for (locale in locales) {
                    for (key in listOf("tile_added", "tile_removed")) {
                        val fmt = rawString(locale, key)
                        val rendered = try {
                            String.format(fmt, name)
                        } catch (e: Exception) {
                            throw AssertionError(
                                "$locale 的 `$key` 无法用字符串实参格式化（就是真机崩溃那个错）：\n" +
                                    "  格式串 = $fmt\n  实参   = $name\n  异常   = ${e::class.java.simpleName}: ${e.message}",
                                e
                            )
                        }
                        assertTrue(
                            "$locale 的 `$key` 格式化结果里应含磁贴名：$rendered",
                            rendered.contains(name)
                        )
                    }
                }
            }
        }
    }

    @Test
    fun `the two Toast call sites pass a String, not an index`() {
        // ⚠️ 反向锁：把调用点改回传 `item.tileIndex + 1`（Int）会让**上面那条**
        //    在 `%1$s` 上抛 `s != java.lang.Integer` —— 两条一正一反，改哪一头都红。
        val source = SourceScan.stripped(
            "src/main/java/com/chaomixian/vflow/ui/workflow_list/WorkflowListRoute.kt"
        )
        for (key in listOf("R.string.tile_added", "R.string.tile_removed")) {
            val index = source.indexOf(key)
            assertTrue("源码里应引用 `$key`", index >= 0)
            // 取该调用点后面的一小段，断言实参是 displayName
            val window = source.substring(index, minOf(index + 200, source.length))
            assertTrue(
                "`$key` 的实参必须是 `TileSlot.displayName(...)`（字符串）；" +
                    "传 Int 会撞 IllegalFormatConversionException（真机已崩过）。实际片段：$window",
                window.contains("TileSlot.displayName(")
            )
        }
    }

    @Test
    fun `the newly added tile strings carry no unexpected format specifiers`() {
        // ⚠️ 本轮新增的 12 条文案里，**只有** `tile_added` / `tile_removed` 带实参。
        //    多出一个 `%` 会让 `getString` 在真机上抛
        //    `MissingFormatArgumentException` —— 同样是**编译与 lint 都拦不住**的。
        val noArgKeys = listOf(
            "workflow_item_menu_add_to_execute_tile",
            "workflow_item_menu_add_to_toggle_tile",
            "tile_execute_pool_title",
            "tile_toggle_pool_title",
            "tile_kind_mismatch_execute",
            "tile_kind_mismatch_toggle",
            "tile_unbound_subtitle",
            "tile_toggle_enabled",
            "tile_toggle_disabled",
            "tile_toggle_failed_permission",
            "tile_out_of_kind_execute",
            "tile_out_of_kind_toggle",
        )
        for (locale in locales) {
            for (key in noArgKeys) {
                val body = rawString(locale, key)
                assertTrue(
                    "$locale 的 `$key` 不带实参，正文里不得出现格式占位符：$body",
                    !body.contains("%")
                )
            }
        }
    }

    @Test
    fun `every language defines every new tile string`() {
        // ⚠️ 三语必须同步：漏一条会让该语言下显示成**另一个语言**的文案（或崩溃）。
        val keys = listOf(
            "workflow_item_menu_add_to_execute_tile",
            "workflow_item_menu_add_to_toggle_tile",
            "tile_execute_pool_title",
            "tile_toggle_pool_title",
            "tile_kind_mismatch_execute",
            "tile_kind_mismatch_toggle",
            "tile_unbound_subtitle",
            "tile_toggle_enabled",
            "tile_toggle_disabled",
            "tile_toggle_failed_permission",
            "tile_out_of_kind_execute",
            "tile_out_of_kind_toggle",
            "tile_added",
            "tile_removed",
        )
        for (locale in locales) {
            val xml = stringXml(locale)
            for (key in keys) {
                assertTrue("$locale/strings.xml 缺少 `$key`", xml.contains("""<string name="$key">"""))
            }
        }
        assertEquals("三语 locale 数量应为 3", 3, locales.size)
    }
}
