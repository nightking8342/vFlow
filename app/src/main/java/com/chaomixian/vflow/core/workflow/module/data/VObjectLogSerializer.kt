// 文件: main/java/com/chaomixian/vflow/core/workflow/module/data/VObjectLogSerializer.kt
package com.chaomixian.vflow.core.workflow.module.data

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
import com.chaomixian.vflow.core.types.complex.VFilePropertySupport
import com.chaomixian.vflow.core.types.complex.VImage
import com.chaomixian.vflow.core.types.complex.VNotification
import com.chaomixian.vflow.core.types.complex.VScreenElement
import com.chaomixian.vflow.core.types.complex.VUiComponent

/**
 * 把一个 [VObject] 渲染成**给人/模型看的一行**（`vflow.data.log` 用）。
 *
 * 设计文档：`docs/fork/log-module-design.md` §4。
 *
 * ## ⚠️ 三条硬规则（都有测试锁住）
 *
 * 1. **不截断** —— 使用者显式打了一条日志，就是要看到值的全部。
 *    没有长度上限、没有项数上限、没有深度上限。
 *    （**深度不需要上限**：`VObject` 图天然无环，理由见下。）
 * 2. **图片 / 文件绝不打印内容** —— 只取元数据。
 *    见 [renderImage] / [renderFile]：**永不**调用 `base64` / `content`。
 * 3. **转义** —— 字符串里的 `"` / `\` / 换行必须转义，保证**一条日志一行**
 *    （否则 `WorkflowExecutor` 的行级解析会错乱）。
 *
 * ## 为什么不需要「递归深度上限」
 *
 * 它原本是为了防无限递归，**但那个风险到不了这里**（已核实，非推断）：
 * `VObjectFactory.from` 对 `Collection` / `Map` **无条件递归**
 * ⇒ 一个循环引用的 Kotlin `Map` 在**构造 VObject 时**就已经爆栈了；
 * JS / Lua 侧同理（`JsValueConverter` 的 `NativeObject` / `ScriptableObject`
 * 分支同样没有 visited 集合）。⇒ 能到达本函数的 `VObject` 图**必然无环**。
 *
 * ## 为什么手写而不是 JSON
 *
 * 输出是 `{state:2, xy:"540,1200"}`（**键不加引号**）—— 目的是给人看，
 * 键加引号会让每行多十几个字符的噪音。值仍严格转义（规则 3）。
 *
 * ⚠️ **不复用 `VDictionary.asString()`**：它把**所有值**都包上引号且**完全不转义**
 * （`VDictionary.kt:26`），嵌引号的文本会直接把输出破坏掉。
 */
internal object VObjectLogSerializer {

    /** 渲染入口。任何情况下都不抛异常 —— 日志失败不该打断工作流。 */
    fun render(value: VObject?): String = renderObject(value ?: VNull)

    private fun renderObject(value: VObject): String = when (value) {
        is VNull -> "null"
        is VBoolean -> value.raw.toString()
        is VNumber -> value.asString()
        is VString -> quote(value.raw)
        is VList -> renderList(value)
        is VDictionary -> renderDictionary(value)
        is VCoordinate -> "{x:${value.x}, y:${value.y}}"
        is VCoordinateRegion ->
            "{left:${value.left}, top:${value.top}, right:${value.right}, bottom:${value.bottom}}"
        is VScreenElement -> renderScreenElement(value)
        is VImage -> renderImage(value)
        is VFile -> renderFile(value)
        is VNotification -> renderNotification(value)
        is VEvent -> renderEvent(value)
        is VUiComponent -> renderUiComponent(value)
        // ⚠️ 兜底：**用 asString() 且仍要转义**（不同实现可能含换行/引号）。
        else -> quote(value.asString())
    }

    private fun renderList(value: VList): String {
        val items = value.raw
        if (items.isEmpty()) return "[]"
        return buildString {
            append('[')
            append(items.size)
            append("项: ")
            append(items.joinToString(", ") { renderObject(it) })
            append(']')
        }
    }

    private fun renderDictionary(value: VDictionary): String {
        val entries = value.raw
        if (entries.isEmpty()) return "{}"
        return buildString {
            append('{')
            append(entries.entries.joinToString(", ") { (key, item) ->
                "${quoteIfNeeded(key)}:${renderObject(item)}"
            })
            append('}')
        }
    }

    private fun renderScreenElement(value: VScreenElement) = buildString {
        append("{text:").append(quote(value.text.orEmpty()))
        // ⚠️ 用 bounds 的四个字段自己算中心，**不调 `value.centerX`** ——
        //    它的实现是 `bounds.centerX()`（Android 的 `Rect` 方法），
        //    在纯 JVM 单测里是 not-mocked 的 stub。
        //    而 `Rect` 的**字段**在 mockable jar 下恒为 0（构造函数不去赋值），
        //    故真正被单测覆盖的是 [centerOfBounds] 这个纯函数 —— 见它的注释。
        val (centerX, centerY) = centerOfBounds(
            value.bounds.left,
            value.bounds.top,
            value.bounds.right,
            value.bounds.bottom,
        )
        append(", center:\"").append(centerX).append(',').append(centerY).append('"')
        value.viewId?.takeIf { it.isNotBlank() }?.let { append(", id:").append(quote(it)) }
        value.className?.takeIf { it.isNotBlank() }?.let { append(", class:").append(quote(it)) }
        if (value.childCount > 0) append(", children:").append(value.childCount)
        append('}')
    }

    /**
     * 从 bounds 的四边算中心点。
     *
     * ## ⚠️ 为什么把它单独抽出来
     *
     * 唯一的调用点是 [renderScreenElement]，而它拿到的 `Rect` 在**纯 JVM 单测里
     * 恒为 `Rect(0, 0, 0, 0)`** —— mockable android jar 只 stub 方法、不执行构造函数体，
     * 字段保持默认值 0。⇒ 从外面看，「中心算得对不对」**在单测里观察不到**。
     *
     * 而算错的后果是静默的：用户照着日志里 `center:"0,0"` 去点，点到屏幕左上角。
     * ⇒ 把纯算术提成独立函数，让四则运算本身可断言（`(left+right)/2` 而不是 `left/2`
     * 这类错误一测就红）；`Rect` 只负责把四个 int 递进来。
     */
    internal fun centerOfBounds(left: Int, top: Int, right: Int, bottom: Int): Pair<Int, Int> =
        (left + right) / 2 to (top + bottom) / 2

    /**
     * ⚠️⚠️ **只取元数据，永不读图片内容。**
     *
     * `VImage` 有 `base64` 属性（`VImage.kt:107`），一张 1080p PNG 的 base64 约 **2–5 MB**
     * —— 单条日志就能撑爆 `SharedPreferences`（`LogManager` 每次 `addLog` 全量读+解析+写回），
     * 并让崩溃上报的体积失控。
     *
     * 这里用 `VFilePropertySupport` 的**无内容**属性（`size` / `name`），
     * **不调用**它的 `base64` / `textContent`。
     */
    private fun renderImage(value: VImage): String = buildString {
        append("{name:").append(quote(safe { VFilePropertySupport.name(value.uriString) } ?: "image"))
        append(", size:").append(safe { VFilePropertySupport.size(value.uriString) } ?: 0L)
        append('}')
    }

    /** ⚠️ 同 [renderImage]：**永不调用 `VFile.content`**（读的是整个文件文本）。 */
    private fun renderFile(value: VFile): String = buildString {
        append("{name:").append(quote(safe { VFilePropertySupport.name(value.uriString) } ?: "file"))
        append(", size:").append(safe { VFilePropertySupport.size(value.uriString) } ?: 0L)
        val mime = safe { VFilePropertySupport.mimeType(value.uriString, value.mimeType) }
        if (!mime.isNullOrBlank()) append(", mime:").append(quote(mime))
        append('}')
    }

    private fun renderNotification(value: VNotification): String = buildString {
        append("{title:").append(quote(value.notification.title))
        append(", content:").append(quote(value.notification.content))
        value.notification.packageName.takeIf { it.isNotBlank() }
            ?.let { append(", package:").append(quote(it)) }
        append('}')
    }

    private fun renderEvent(value: VEvent): String =
        "{type:${quote(value.event.type)}, elementId:${quote(value.event.elementId)}}"

    private fun renderUiComponent(value: VUiComponent): String = buildString {
        append("{type:").append(quote(value.element.type.name.lowercase()))
        append(", label:").append(quote(value.element.label))
        value.currentValue?.let { append(", value:").append(quote(it.toString())) }
        append('}')
    }

    /**
     * 读属性时**吞掉异常**。
     *
     * 这些 getter 会做 IO（`size` 要 stat 文件、`mimeType` 可能查 `ContentResolver`）
     * —— 一个日志调用不该因为「文件恰好被删了」而让整个工作流失败。
     */
    private inline fun <T> safe(block: () -> T): T? = runCatching(block).getOrNull()

    /**
     * JSON 转义 + 加引号，**保证结果不含真实换行**。
     *
     * 只转义 JSON 必需的那些（`"` / `\` / 控制字符）—— 不转义 `<` `>` `&`
     * 之类的 HTML 实体（日志不是 HTML，转了反而难读）。
     */
    private fun quote(raw: String): String = buildString(raw.length + 2) {
        append('"')
        for (ch in raw) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                // 其余控制字符（含 \b \u0000-\u001f）走 \uXXXX，避免把日志撑成多行/乱码
                else -> if (ch < ' ') {
                    append("\\u").append(ch.code.toString(16).padStart(4, '0'))
                } else {
                    append(ch)
                }
            }
        }
        append('"')
    }

    /**
     * 字典**键**的渲染：含特殊字符时才加引号。
     *
     * 键大多是 `text` / `count` 这种标识符，加引号纯属噪音；
     * 但含空格、冒号、引号的键不加引号会让输出没法读（甚至歧义）——
     * 尤其 `{{cls}}` 打整表时，键来自**模块自己定义的输出名**，不受我们控制。
     */
    private fun quoteIfNeeded(key: String): String =
        if (key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '_' || it == '-' }) {
            key
        } else {
            quote(key)
        }
}
