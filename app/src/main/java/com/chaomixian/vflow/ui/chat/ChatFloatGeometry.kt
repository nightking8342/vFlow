package com.chaomixian.vflow.ui.chat

/**
 * 悬浮窗的几何计算（纯函数，便于单测）。
 *
 * 解决的问题：**展开/折叠时保持窄条所贴的那条边不动**，让面板朝屏幕内侧生长。
 *
 *  - 窄条拖到屏幕底部 → 向上展开；再折叠，窄条仍停在底部
 *  - 窄条贴在右边缘 → 向左展开，右边不跳
 *
 * 反过来（不锚定）会出现：贴底时向下展开顶出屏幕、贴右时展开被夹回左侧。
 */
object ChatFloatGeometry {

    /** 水平锚定边。 */
    enum class HAnchor { LEFT, RIGHT }

    /** 垂直锚定边。 */
    enum class VAnchor { TOP, BOTTOM }

    data class Bounds(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
    ) {
        val right: Int get() = left + width
        val bottom: Int get() = top + height
    }

    /**
     * 依据当前窄条位置，判定**展开时**应锚定的边。
     *
     * 规则（按可用空间，最严谨）：
     *  1. 只有一侧放得下 → 选那一侧（绝不越界）
     *  2. 两侧都放得下 → 取离得近的那侧（窄条偏上就向下展开，偏下就向上展开）
     *  3. 两侧都放不下（屏幕比面板还矮）→ 按中心二分，由 `resize` 夹取
     *
     * ⚠️ **调用时机很关键**：只在**拖动结束时**（以及首次显示时）判定一次，
     * 展开/折叠都沿用同一结果。若折叠时重新判定，因窄条尺寸小、两侧都放得下，
     * 会退化成"中心二分"，导致折叠后位置跳变、无法"停在底部"。
     *
     * @param targetWidth/targetHeight 下次要变到的尺寸（通常是展开尺寸）
     */
    fun anchorsFor(
        bounds: Bounds,
        targetWidth: Int,
        targetHeight: Int,
        screenWidth: Int,
        screenHeight: Int,
    ): Pair<HAnchor, VAnchor> {
        // 锚定左边 = 左边不动，面板向右延伸 → 需要 left + targetWidth 放得下
        val canAnchorLeft = bounds.left + targetWidth <= screenWidth
        // 锚定右边 = 右边不动，面板向左延伸 → 需要 right - targetWidth 不越左边界
        val canAnchorRight = bounds.right - targetWidth >= 0
        val horizontal = when {
            canAnchorLeft && !canAnchorRight -> HAnchor.LEFT
            canAnchorRight && !canAnchorLeft -> HAnchor.RIGHT
            else -> if (bounds.left + bounds.width / 2 > screenWidth / 2) {
                HAnchor.RIGHT
            } else {
                HAnchor.LEFT
            }
        }

        // 锚定顶边 = 顶边不动，面板向下延伸
        val canAnchorTop = bounds.top + targetHeight <= screenHeight
        // 锚定底边 = 底边不动，面板向上延伸
        val canAnchorBottom = bounds.bottom - targetHeight >= 0
        val vertical = when {
            canAnchorTop && !canAnchorBottom -> VAnchor.TOP
            canAnchorBottom && !canAnchorTop -> VAnchor.BOTTOM
            else -> if (bounds.top + bounds.height / 2 > screenHeight / 2) {
                VAnchor.BOTTOM
            } else {
                VAnchor.TOP
            }
        }

        return horizontal to vertical
    }

    /**
     * 改为新尺寸，**保持锚定边不动**，最后把结果夹取进屏幕范围。
     *
     * 锚定边不动，意味着贴底的窄条展开后底边位置不变（向上生长）；
     * 折叠回窄条时同样保持底边，因此它会停在原来贴底的位置。
     */
    fun resize(
        bounds: Bounds,
        newWidth: Int,
        newHeight: Int,
        hAnchor: HAnchor,
        vAnchor: VAnchor,
        screenWidth: Int,
        screenHeight: Int,
    ): Bounds {
        val left = when (hAnchor) {
            HAnchor.LEFT -> bounds.left
            HAnchor.RIGHT -> bounds.right - newWidth
        }
        val top = when (vAnchor) {
            VAnchor.TOP -> bounds.top
            VAnchor.BOTTOM -> bounds.bottom - newHeight
        }
        return Bounds(
            left = left.coerceIn(0, (screenWidth - newWidth).coerceAtLeast(0)),
            top = top.coerceIn(0, (screenHeight - newHeight).coerceAtLeast(0)),
            width = newWidth,
            height = newHeight,
        )
    }
}
