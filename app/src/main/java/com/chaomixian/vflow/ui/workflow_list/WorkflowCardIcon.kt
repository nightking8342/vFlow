package com.chaomixian.vflow.ui.workflow_list

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.chaomixian.vflow.core.workflow.WorkflowIconValue
import com.chaomixian.vflow.core.workflow.WorkflowVisuals

/**
 * 卡片图标（工作流列表 + 编辑器预览共用）。
 *
 * ## 为什么要抽出来
 *
 * 卡片图标有**两个 Compose 消费点**（紧凑瀑布流卡片、列表模式卡片），加上
 * 编辑器里的预览一共三处。它们各自要处理的形态从一种变成了三种
 * （内置资源名 / 绝对路径 / `file://`），判定逻辑分开写的话，
 * 漏掉一处的表现是「列表上显示图片、编辑器预览里显示默认图标」。
 *
 * ## 三个形态的处理
 *
 * | 字段值 | 渲染 |
 * |---|---|
 * | `rounded_home_24` 这类资源名 | `painterResource` + **主题色 tint**（内置图标是单色矢量）|
 * | `/data/.../card_icons/x.png` 或 `file://...` | `AsyncImage` 显示原图，**不 tint** |
 * | 空 / 解析失败 | 回落到默认图标（复用 [WorkflowVisuals.resolveIconDrawableRes] 的回退）|
 *
 * ⚠️ **自定义图片不能 tint**：内置图标是单色矢量，tint 是它上色的唯一手段；
 *    而用户选的图片是彩色的，套 tint 会把它整张染成一种颜色
 *    （`Icon` 默认就会这么做，所以两条分支必须分开写，不能共用一个 `Icon`）。
 *
 * ⚠️ 自定义图片走 **Coil 的 `AsyncImage`** 而不是自己解码 Bitmap：卡片列表会
 *    快速滚过几百项，Coil 的内存/磁盘缓存与「滚出屏幕自动取消」是必需的
 *    （自己解码需要手动管缓存与生命周期，本仓库的
 *    `DebugLogViewerWiringTest` 那类"必须 manually 管"的地方已经够多了）。
 */
@Composable
fun WorkflowCardIcon(
    cardIconRes: String?,
    tint: Color,
    size: Dp,
    modifier: Modifier = Modifier,
    /** 内置图标四周留白（视觉上与 40dp 容器搭配）；自定义图片不缩，铺满。 */
    iconPadding: Dp = 9.dp,
) {
    val iconPath = WorkflowIconValue.filePathOf(cardIconRes)
    if (iconPath != null) {
        AsyncImage(
            model = java.io.File(iconPath),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size),
        )
    } else {
        val context = LocalContext.current
        // ⚠️ 走 `resolveIconDrawableResOrZero`（含 getIdentifier 全量路径），
        //    拿不到再回落到 `resolveIconDrawableRes` 的默认图标 ——
        //    绝不把 0 交给 painterResource（会抛 NotFoundException）。
        val resolved = WorkflowVisuals.resolveIconDrawableResOrZero(context, cardIconRes)
        val drawableRes = if (resolved != 0) resolved else WorkflowVisuals.resolveIconDrawableRes(cardIconRes)
        Icon(
            painter = painterResource(drawableRes),
            contentDescription = null,
            tint = tint,
            modifier = modifier.padding(iconPadding).size(size),
        )
    }
}
