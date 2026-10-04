package com.chaomixian.vflow.core.workflow.module.data

import android.graphics.Rect
import com.chaomixian.vflow.core.types.VObject
import com.chaomixian.vflow.core.types.basic.VBoolean
import com.chaomixian.vflow.core.types.basic.VDictionary
import com.chaomixian.vflow.core.types.basic.VList
import com.chaomixian.vflow.core.types.basic.VNull
import com.chaomixian.vflow.core.types.basic.VNumber
import com.chaomixian.vflow.core.types.basic.VString
import com.chaomixian.vflow.core.types.complex.VCoordinate
import com.chaomixian.vflow.core.types.complex.VCoordinateRegion
import com.chaomixian.vflow.core.types.complex.VEvent
import com.chaomixian.vflow.core.types.complex.VFile
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.types.complex.VNotification
import com.chaomixian.vflow.core.types.complex.VScreenElement
import com.chaomixian.vflow.core.workflow.module.notification.NotificationObject
import com.chaomixian.vflow.core.workflow.module.ui.UiEvent
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 日志序列化器的纯函数层。
 *
 * 设计文档：`docs/fork/log-module-design.md` §4。
 *
 * 覆盖三类「改错了不报错、只静默变差」的地方：
 * 1. **图片/文件绝不打印内容**（打印 base64 会让单条日志到几 MB）
 * 2. **不截断**（本模块的核心承诺；有反向锁）
 * 3. **转义**（不转义会把一条日志拆成多行，破坏日志的行级解析）
 */
class VObjectLogSerializerTest {

    private fun render(value: VObject): String = VObjectLogSerializer.render(value)

    private fun dict(vararg pairs: Pair<String, VObject>): VDictionary =
        VDictionary(linkedMapOf(*pairs))

    // ── 1. 按类型分派 ───────────────────────────────────────────

    @Test
    fun `null and booleans and numbers render literally`() {
        assertEquals("null", render(VNull))
        assertEquals("true", render(VBoolean(true)))
        assertEquals("false", render(VBoolean(false)))
        assertEquals("42", render(VNumber(42)))
        assertEquals("3.5", render(VNumber(3.5)))
    }

    @Test
    fun `strings are always quoted`() {
        // ⚠️ 必须加引号：否则 `"1234"`（字符串）与 `1234`（数字）看不出区别。
        assertEquals("\"1234\"", render(VString("1234")))
        assertEquals("\"hello\"", render(VString("hello")))
    }

    @Test
    fun `lists carry the item count`() {
        val list = VList(listOf(VString("a"), VString("b"), VString("c")))
        assertEquals("[3项: \"a\", \"b\", \"c\"]", render(list))
    }

    @Test
    fun `empty containers render as empty`() {
        assertEquals("[]", render(VList(emptyList())))
        assertEquals("{}", render(VDictionary(emptyMap())))
    }

    @Test
    fun `dictionaries use bare keys when they look like identifiers`() {
        val value = dict(
            "state" to VNumber(2),
            "xy" to VString("540,1200"),
        )
        assertEquals("{state:2, xy:\"540,1200\"}", render(value))
    }

    @Test
    fun `dictionary keys with odd characters get quoted`() {
        // ⚠️ 键来自「模块自己定义的输出名」，不受本模块控制 ——
        //    含空格/冒号的键不加引号会让输出有歧义。
        val value = dict("my key" to VNumber(1))
        assertEquals("{\"my key\":1}", render(value))
    }

    @Test
    fun `coordinates and regions use named fields`() {
        assertEquals("{x:540, y:1200}", render(VCoordinate(540, 1200)))
        assertEquals(
            "{left:0, top:0, right:1080, bottom:2400}",
            render(VCoordinateRegion(0, 0, 1080, 2400)),
        )
    }

    @Test
    fun `screen elements print text and center but not children`() {
        val element = VScreenElement(
            bounds = Rect(440, 1100, 640, 1300),  // center = (540, 1200)
            text = "确定",
            contentDescription = null,
            allTexts = listOf("确定"),
            viewId = "btn_ok",
            className = "android.widget.Button",
            isClickable = true,
            isEnabled = true,
            isCheckable = false,
            isChecked = false,
            isFocusable = true,
            isFocused = false,
            isScrollable = false,
            isLongClickable = false,
            isSelected = false,
            isEditable = false,
            depth = 3,
            childCount = 2,
            accessibilityId = 7,
        )
        val rendered = render(element)
        assertTrue(rendered.contains("\"确定\""))
        assertTrue(rendered.contains("btn_ok"))
        assertTrue(rendered.contains("children:2"))

        // ⚠️⚠️ **不断言 `540,1200`** —— 在这里它**恒为 `0,0`**，且那**不是**本类的缺陷：
        //    mockable android jar 里的 `Rect` 只 stub 方法、**不执行构造函数体**，
        //    四个字段保持默认值 0（实测：`Rect(440, 1100, 640, 1300)` 读回来全是 0）。
        //    ⇒ 真正的算术断言走下面的 `centerOfBounds`（纯函数，不经过 `Rect`）。
        //    若在这里写「应含 540,1200」，得到的是一个**恒红的假断言** ——
        //    而恒红的断言会被下一个实现者直接删掉，连带把这条覆盖也带走。
        assertTrue(
            "JVM 下 Rect 字段恒为 0 —— 若这里不再是 0，说明该前提变了，请改为断言真实中心",
            rendered.contains("center:\"0,0\""),
        )
    }

    @Test
    fun `center of bounds is the midpoint of both axes`() {
        // ⚠️ 这是**唯一**能真正验到中心点算术的地方（见上一个用例的说明）。
        //    写成 `left / 2` 之类的错误在这里立刻变红。
        assertEquals(540 to 1200, VObjectLogSerializer.centerOfBounds(440, 1100, 640, 1300))
        assertEquals(0 to 0, VObjectLogSerializer.centerOfBounds(0, 0, 0, 0))
        // 奇数边长向下取整（与 Android `Rect.centerX()` 的整数除法一致）
        assertEquals(1 to 2, VObjectLogSerializer.centerOfBounds(0, 0, 3, 5))
        assertEquals(-5 to -5, VObjectLogSerializer.centerOfBounds(-10, -10, 0, 0))
    }

    @Test
    fun `notifications and events print their fields`() {
        val notification = VNotification(
            NotificationObject(id = "1", packageName = "com.x", title = "标题", content = "内容")
        )
        val renderedNotification = render(notification)
        assertTrue(renderedNotification.contains("\"标题\""))
        assertTrue(renderedNotification.contains("\"内容\""))
        assertTrue(renderedNotification.contains("com.x"))

        val event = VEvent(UiEvent(sessionId = "s", elementId = "ok", type = "click", value = null))
        val renderedEvent = render(event)
        assertTrue(renderedEvent.contains("\"click\""))
        assertTrue(renderedEvent.contains("\"ok\""))
    }

    // ── 2. ⚠️⚠️ 图片 / 文件绝不打印内容（红线）──────────────────

    @Test
    fun `images render metadata only, never base64`() {
        // 造一个真实存在的文件，确保「没打印内容」不是因为「读不到文件」。
        val file = File.createTempFile("log-serializer-test", ".png")
        try {
            file.writeBytes(ByteArray(64 * 1024) { 0x7F })
            val rendered = render(VImage(file.toURI().toString()))

            assertTrue("应含文件名", rendered.contains("log-serializer-test"))
            assertTrue("应含大小", rendered.contains(65536.toString()))
            assertFalse(
                "⚠️ 输出里出现了长 base64 串 —— 图片内容被打印了",
                rendered.contains("f39/f39"),
            )
            assertTrue("整条渲染必须很短（只是元数据）", rendered.length < 200)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `files render metadata only, never text content`() {
        val file = File.createTempFile("log-serializer-test", ".txt")
        val marker = "SECRET_FILE_CONTENT_MARKER_12345"
        try {
            file.writeText(marker)
            val rendered = render(VFile(file.toURI().toString()))
            assertFalse(
                "⚠️ 文件内容被打印了 —— 那可能是任意大的文本",
                rendered.contains(marker),
            )
        } finally {
            file.delete()
        }
    }

    // ── 3. ⚠️ 不截断（反向锁）──────────────────────────────────

    @Test
    fun `long strings are not truncated`() {
        val long = "x".repeat(5_000)
        val rendered = render(VString(long))
        assertEquals("⚠️ 长字符串被截断了", long.length, rendered.length - 2)
        assertFalse("不应出现截断标注", rendered.contains("已截断"))
    }

    @Test
    fun `long lists are not truncated`() {
        val list = VList((1..50).map { VNumber(it) })
        val rendered = render(list)
        assertTrue("50 项应全部出现", rendered.contains("50项:"))
        assertTrue("最后一项必须在", rendered.contains("50"))
        assertFalse("不应省略任何项", rendered.contains("…"))
    }

    @Test
    fun `deeply nested dictionaries are not truncated`() {
        var value: VObject = VString("leaf")
        repeat(6) { value = dict("nested" to value) }
        val rendered = render(value)
        // 6 层 × "{nested:…}" 全部展开
        assertEquals("⚠️ 深度被限制了", 6, Regex("nested").findAll(rendered).count())
        assertTrue("最内层的值必须在", rendered.contains("\"leaf\""))
        assertFalse(rendered.contains("…"))
    }

    // ── 4. ⚠️ 转义（一条日志必须是一行）────────────────────────

    @Test
    fun `newlines and quotes are escaped so the record stays one line`() {
        val value = VString("第一行\n第二行\t带\"引号\"和\\反斜杠")
        val rendered = render(value)

        assertFalse("输出里出现了真实换行 —— 会把一条日志拆成多行", rendered.contains('\n'))
        assertFalse(rendered.contains('\t'))

        // ⚠️ 解回来必须等于原文 —— 光是「没有换行」不够，转义可能转错。
        val decoded = JSONObject("{\"v\":$rendered}").getString("v")
        assertEquals("第一行\n第二行\t带\"引号\"和\\反斜杠", decoded)
    }

    @Test
    fun `dictionary values are escaped while VDictionary asString is not`() {
        // ⚠️⚠️ 反向锁：`VDictionary.asString()`（VDictionary.kt:26）把所有值都包引号
        // 且**完全不转义** —— 嵌引号的文本会直接破坏输出。本模块刻意不复用它。
        val value = dict("k" to VString("a\"b"))
        val rendered = render(value)
        val naive = value.asString()

        assertTrue("本模块输出必须转义", rendered.contains("\\\""))
        assertFalse("若相等说明在复用那个有缺陷的实现", rendered == naive)
    }

    // ── 5. 组合场景（贴近真实用法）─────────────────────────────

    @Test
    fun `a realistic step output renders readably`() {
        val value = dict(
            "state" to VNumber(2),
            "xy" to VString("540,1200"),
            "hit" to VString("K2"),
            "items" to VList(listOf(VString("a"), VString("b"))),
        )
        assertEquals(
            "{state:2, xy:\"540,1200\", hit:\"K2\", items:[2项: \"a\", \"b\"]}",
            render(value),
        )
    }
}
