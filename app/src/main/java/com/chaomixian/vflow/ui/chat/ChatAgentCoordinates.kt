package com.chaomixian.vflow.ui.chat

/**
 * 归一化坐标的取值上界（**含**）—— fork：AI 能力统一 §3.8 / D20。
 *
 * 语义：`0` = 左/上边缘，[CHAT_AGENT_NORMALIZED_COORDINATE_MAX] = 右/下边缘。
 */
internal const val CHAT_AGENT_NORMALIZED_COORDINATE_MAX = 1000

/**
 * 归一化 `0–1000` → **像素**（纯函数）。
 *
 * ## 为什么坐标要归一化（D20）
 *
 * 旧 schema 是「绝对屏幕像素」，而模型看到的是**截图**。截图会被服务端/客户端
 * 缩放（长边限制、`inSampleSize`、虚拟屏尺寸），于是模型数出来的像素与真实
 * 屏幕像素**对不上** —— 表现是「点偏」。归一化后「图上的相对位置」与
 * 「屏幕的相对位置」恒等，与缩放无关。
 *
 * ## 越界必须**报错**，不得钳制
 *
 * 静默把 `(540, 1200)` 钳到右下角是设计稿 §3.8 点名的**最坏失败形态**
 * （不报错、只点错）。抛出的异常会被 `ChatAgentNativeToolExecutor.prepare` 捕获，
 * 转成一条 ERROR 工具结果 + `chatAgentNativeRecoveryHint` —— 模型可自愈。
 * 故异常文案必须**说清怎么改**，而不只是「非法参数」。
 *
 * @param raw 归一化值，取值域 `0..1000`。
 * @param extent 该轴的真实像素长度（必须 > 0；调用方负责给出真实屏幕尺寸）。
 * @param axis `"x"` / `"y"`，仅用于异常文案。
 * @throws IllegalArgumentException `raw` 越界，或 `extent <= 0`。
 */
internal fun chatAgentNormalizedToPixels(raw: Int, extent: Int, axis: String): Int {
    require(extent > 0) {
        "Cannot map the normalized $axis coordinate because the screen extent is $extent. " +
            "Re-observe the UI and retry once a real display size is available."
    }
    require(raw in 0..CHAT_AGENT_NORMALIZED_COORDINATE_MAX) {
        "Invalid normalized $axis coordinate $raw: expected " +
            "0-$CHAT_AGENT_NORMALIZED_COORDINATE_MAX (0 = left/top edge, " +
            "$CHAT_AGENT_NORMALIZED_COORDINATE_MAX = right/bottom edge). " +
            "Coordinates are normalized so they stay correct even if the screenshot is scaled; " +
            "do not pass absolute screen pixels. Prefer a ScreenElement handle from observe_ui."
    }
    return (raw / 1000.0 * extent).toInt().coerceIn(0, extent - 1)
}
