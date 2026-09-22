package com.chaomixian.vflow.ui.chat

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.mikepenz.markdown.compose.components.markdownComponents
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeBlock
import com.mikepenz.markdown.compose.elements.MarkdownHighlightedCodeFence
import com.mikepenz.markdown.compose.elements.MarkdownTable
import com.mikepenz.markdown.compose.elements.MarkdownTableHeader
import com.mikepenz.markdown.compose.elements.MarkdownTableRow
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import com.mikepenz.markdown.model.MarkdownTypography
import com.mikepenz.markdown.model.rememberMarkdownState

/**
 * 聊天区专用的 Markdown 排版。
 *
 * ⚠️ **必须覆盖，不能用库默认的 `markdownTypography()`**。
 *
 * 库默认把标题映射到了 Material 3 的 **display / headline 档**
 * （`MarkdownTypography.kt:17-22`）：
 *
 * | Markdown | 库默认（m3 字段） | 字号 |
 * |---|---|---|
 * | h1 | `displayLarge` | **57sp** |
 * | h2 | `displayMedium` | **45sp** |
 * | h3 | `displaySmall` | **36sp** |
 * | h4 | `headlineMedium` | 28sp |
 * | h5 | `headlineSmall` | 24sp |
 * | h6 | `titleLarge` | 22sp |
 * | 正文 | `bodyLarge` | 16sp |
 *
 * `display*` 系列在 Material 3 里是给**大屏短文案**用的（启动页标语、数字看板），
 * **不是长文档的标题**。那个默认值适合「整屏就是一篇文章」的文档阅读器，
 * 但聊天是**消息流**——h2 有 45sp 而正文只有 16sp，**2.8 倍落差**，视觉上非常突兀
 * （用户实际反馈过「二级标题跟正文字号差距太大」）。
 *
 * 这里把标题压回「正文之上的合理台阶」：h1 约 1.5 倍正文，h2 约 1.35 倍，
 * h3-h6 逐级递减到正文同级。
 *
 * ⚠️ **h4-h6 与正文的分辨靠「字号 + 字重」，绝不靠颜色**。两个踩过的坑：
 *
 * 1. 曾写成 `bodyLarge + SemiBold`（与正文同款只加粗）——但正文里的
 *    `**加粗**` 也是 `bodyLarge + 粗体`，于是 **h4 与加粗正文长得完全一样**，
 *    标题失去可辨识性。现在 h4-h6 字号降到 15/14sp 并保持 Bold，与加粗正文分得开。
 * 2. 曾试图用「同字号 + 递减透明度」做层级，写成 `Color.Unspecified.copy(alpha=…)`
 *    —— **这是错的**：`Unspecified` 的 RGB 是 0，淡化后得到的是**半透明黑**，
 *    深色主题下会变黑字。而且 `MarkdownText` 是把 `style` 经 `pushStyle` 写进 span
 *    （`MarkdownText.kt:63-64`），其 color 会**覆盖** `markdownColor(text = ...)`
 *    给的主题色。所以这里**一律不设 color**，让主题色自然生效。
 *
 * 正文 / 列表 / 段落保持 16sp 不变，所以**正文观感与改动前一致**，只有标题变小。
 */
@Composable
private fun chatMarkdownTypography(): MarkdownTypography {
    val base = MaterialTheme.typography
    return markdownTypography(
        h1 = base.headlineSmall,                                     // 24sp
        h2 = base.titleLarge,                                        // 22sp  ★ 主要修这个
        h3 = base.titleMedium.copy(fontWeight = FontWeight.SemiBold), // 16sp + 半粗
        // h4-h6：靠「比正文略小 + Bold」区分于加粗正文（后者是 16sp）。
        h4 = base.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Bold),
        h5 = base.bodyLarge.copy(fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.Bold),
        h6 = base.bodyLarge.copy(fontSize = 13.sp, lineHeight = 20.sp, fontWeight = FontWeight.Bold),
    )
}

/**
 * 表格单元格的行数上限。
 *
 * ⚠️ **不要改回窄值**：mikepenz renderer 的默认是 `maxLines = 1` +
 * `TextOverflow.Ellipsis`（见 `MarkdownTable.kt:95/132/161`），即**每个单元格
 * 单行显示、超出直接截断成 `...`**。表现为「表格里某一格内容稍长就看不到后半句」。
 *
 * 对照过四家头部 Agent（ccb / dsh / opencode / pi）：**没有任何一家截断单元格**，
 * 全部选择折行——ccb 甚至专门注释了 "Wrapping text within cells (no truncation)"，
 * 它超 4 行时是整表降级为竖排 key-value 排版，内容 100% 保留。
 *
 * 这里取 `Int.MAX_VALUE`（完全折行、不截断），与四家一致。代价是长单元格会撑高
 * 行高——这是「信息完整」换来的，可接受。
 */
private const val CHAT_TABLE_CELL_MAX_LINES = Int.MAX_VALUE

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
            typography = chatMarkdownTypography(),
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
                // 表格：覆盖默认的「单行截断」为「完整折行」。
                //
                // 走 `MarkdownTable` 自己的 `headerBlock` / `rowBlock` 钩子（库为这种
                // 定制留的参数），只改 `maxLines`，其余布局（横滚 / 分隔线 / 圆角 /
                // 背景色 / 图片单元格分支）全部复用库的实现——所以这不是重写表格，
                // 改动面最小。
                //
                // ⚠️ `overflow` 一并用 `Clip`（默认是 `Ellipsis`）：既然已经不限行数，
                // 留 `Ellipsis` 反而会在极端情况下给最后一行硬加省略号。
                table = {
                    MarkdownTable(
                        content = it.content,
                        node = it.node,
                        // ⚠️ 必须是 `typography.table`，与库默认一致
                        // （`MarkdownComponents.kt:202`）。写成 `.text` 虽能编译，
                        // 但表格字体样式会与其他 markdown 元素不一致。
                        style = it.typography.table,
                        headerBlock = { content, header, tableWidth, style ->
                            MarkdownTableHeader(
                                content = content,
                                header = header,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = CHAT_TABLE_CELL_MAX_LINES,
                                overflow = TextOverflow.Clip,
                            )
                        },
                        rowBlock = { content, row, tableWidth, style ->
                            MarkdownTableRow(
                                content = content,
                                header = row,
                                tableWidth = tableWidth,
                                style = style,
                                maxLines = CHAT_TABLE_CELL_MAX_LINES,
                                overflow = TextOverflow.Clip,
                            )
                        },
                    )
                },
            ),
        )
    }
}
