package com.chaomixian.vflow.ui.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.model.rememberMarkdownState

@Composable
fun ChatMarkdownContent(
    markdown: String,
    modifier: Modifier = Modifier,
    contentColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    val state = rememberMarkdownState(markdown)
    // ⚠️ `SelectionContainer` 包在 `Markdown` 外层，使消息内容可长按选中复制。
    //
    // 包在**这一层**（而不是各个调用点）是为了让 assistant 正文 / 思考过程 /
    // 工具结果 / 错误提示四处**一次覆盖**，以后新增 call site 也不会漏。
    //
    // ⚠️ `modifier` 要给 `SelectionContainer`、**不能给里面的 `Markdown`**：
    // 调用点用它做折叠态的高度裁剪（`heightIn` + `clip`），
    // 那些修饰必须作用在最外层容器上才有效 —— 给到内层会被 SelectionContainer
    // 包住，裁剪照样生效但 `heightIn` 的测量约束会与选中手势的命中区错位。
    //
    // ⚠️ mikepenz renderer 内部是 `Column`（**不是** Lazy），所以选区不会因为
    // 项回收而失稳。代价是**选择范围限于单条消息内** —— 消息列表本身是
    // `LazyColumn`，跨消息拖选会与列表滚动抢同一个手势。聊天场景下也不需要：
    // 用户复制的是某一条回答。
    SelectionContainer(modifier = modifier) {
        Markdown(
            state,
            colors = markdownColor(text = contentColor),
            components = markdownComponents(
                codeBlock = {
                    MarkdownHighlightedCodeBlock(
                        content = it.content,
                        node = it.node,
                        style = it.typography.code,
                        showHeader = true,
                    )
                },
                codeFence = {
                    MarkdownHighlightedCodeFence(
                        content = it.content,
                        node = it.node,
                        style = it.typography.code,
                        showHeader = true,
                    )
                },
            ),
        )
    }
}
