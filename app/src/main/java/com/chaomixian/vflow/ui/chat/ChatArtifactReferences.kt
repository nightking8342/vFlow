package com.chaomixian.vflow.ui.chat

/**
 * 正文里的一条**内联图片引用**（`![图注](artifact://call_x/screenshot)`）。
 *
 * fork：AI 能力统一 §3.9 —— 工具产出的图「给人看」走**正文内联引用**，
 * **不是** pin（pin 方案已否决：判据主观 ⇒ 必然出现「零 pin / 全 pin」两种失败形态）。
 */
internal data class ChatInlineImageReference(
    /** 原样的句柄，如 `artifact://call_x/screenshot`。 */
    val handle: String,
    /** 图注（`![这里]` 里的内容，可为空）。 */
    val caption: String,
    /** 引用在正文里的起始下标（含 `!`）。 */
    val startIndex: Int,
    /** 引用在正文里的结束下标（**不含**，即 `)` 之后一位）。 */
    val endIndex: Int,
)

/** 正文切出来的一段：纯 markdown 文本，或一条内联图片引用。 */
internal sealed interface ChatBodySegment {
    data class Text(val markdown: String) : ChatBodySegment

    data class Image(val reference: ChatInlineImageReference) : ChatBodySegment
}

/**
 * 正文引用的**纯解析**（无 `android.*`，可在纯 JVM 单测里跑）。
 *
 * 只认 `![alt](artifact://…)` 这一种形态 —— 其余图片语法（`http://` / `file://` /
 * `data:`）**原样留在文本段里**，交给 markdown 渲染器按它原本的方式处理。
 * 这个边界是「引用即保存」的安全前提：只有 `artifact://` 句柄才指向会话内的临时产物，
 * 把任意 URL 当引用会在落盘时做无意义的网络/文件读取。
 */
internal object ChatArtifactReferences {

    /** 唯一被认作「会话产物」的句柄前缀。 */
    const val HANDLE_SCHEME = "artifact://"

    /**
     * 内联图片引用的**唯一一处**正则。
     *
     * - `!\[([^\]]*)\]` —— 图注（可空）；
     * - `\(\s*(artifact://[^\s)]+?)\s*` —— 句柄，**非贪婪**且不吃 `)`；
     * - `(?:"[^"]*")?` —— 可选的 markdown title（`![a](artifact://x "t")`）；
     * - `\s*\)` —— 收尾。
     */
    private val referencePattern =
        Regex("""!\[([^\]]*)\]\(\s*(artifact://[^\s)]+?)\s*(?:"[^"]*")?\s*\)""")

    /**
     * 把正文切成「文本段 + 图段」，**保持原文顺序**。
     *
     * 空正文 ⇒ 空列表（不产生空文本段）；没有引用的正文 ⇒ 单个文本段。
     * 相邻引用之间的空白会留在文本段里（由渲染层决定要不要画）。
     */
    fun parse(body: String): List<ChatBodySegment> {
        if (body.isEmpty()) return emptyList()

        val segments = mutableListOf<ChatBodySegment>()
        var cursor = 0
        referencePattern.findAll(body).forEach { match ->
            if (match.range.first > cursor) {
                segments += ChatBodySegment.Text(body.substring(cursor, match.range.first))
            }
            segments += ChatBodySegment.Image(
                ChatInlineImageReference(
                    handle = match.groupValues[2],
                    caption = match.groupValues[1],
                    startIndex = match.range.first,
                    endIndex = match.range.last + 1,
                )
            )
            cursor = match.range.last + 1
        }
        if (cursor < body.length) {
            segments += ChatBodySegment.Text(body.substring(cursor))
        }
        return segments
    }

    /**
     * 正文里引用到的句柄：**去重、保持出现顺序** —— 这正是「引用即保存」的输入
     * （同一个句柄在一段正文里写两次只落盘一次，内容寻址本身也会去重）。
     */
    fun referencedHandles(body: String): List<String> {
        if (body.isEmpty()) return emptyList()
        return referencePattern.findAll(body)
            .map { it.groupValues[2] }
            .distinct()
            .toList()
    }
}
