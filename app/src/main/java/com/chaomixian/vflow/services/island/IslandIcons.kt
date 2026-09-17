// 文件: main/java/com/chaomixian/vflow/services/island/IslandIcons.kt
package com.chaomixian.vflow.services.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Icon
import android.os.Bundle
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger

/**
 * 超级岛的图标装配。
 *
 * 岛的图片不内联在 JSON 里，而是放进通知 extras 的 `miui.focus.pics` Bundle
 * （key → [Icon]），JSON 侧用 `{"type":1,"pic":"<key>"}` 引用。见 [IslandParamsBuilder]。
 *
 * **与通知小图标无关**：这里走的是 `miui.focus.pics` 通道，SystemUI 显示的是
 * **全彩图形**，不受 Android 通知小图标「alpha 蒙版、颜色由系统填充」的限制。
 */
internal object IslandIcons {

    private const val TAG = "IslandIcons"

    /** 应用图标缓存。裁剪成圆形位图的开销不值得每次发通知都做一遍。 */
    @Volatile
    private var cachedAppIcon: Icon? = null

    /**
     * 构建 `miui.focus.pics` Bundle。
     *
     * 岛上各处的图标统一用**应用图标**——它是用户识别「这条通知来自 vFlow」最直接的线索，
     * 比功能图标（四宫格）更能表达来源。状态差异由岛上的文本与强调色承载，
     * 不再靠换图标（那样反而让来源变模糊）。
     *
     * @param context 应用上下文。
     */
    fun buildPics(context: Context): Bundle =
        Bundle().apply {
            val appIcon = roundAppIcon(context)
            // 两个 key 都指向同一个应用图标：大岛 A 区与小岛容器各自引用其中之一。
            putParcelable(IslandParamsBuilder.PIC_APP, appIcon)
        }

    /**
     * 把 launcher 图标裁成圆形位图。
     *
     * **为什么需要裁剪**：API 26+ 的 launcher 图标是自适应图标（`<adaptive-icon>` XML），
     * 交给 SystemUI 在岛的小圆形容器里渲染会**变成方形**。必须自己画成圆形位图。
     */
    private fun roundAppIcon(context: Context): Icon {
        cachedAppIcon?.let { return it }

        val icon = try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val size = maxOf(48, (48 * context.resources.displayMetrics.density).toInt())
            val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

            Canvas(bitmap).apply {
                clipPath(Path().apply { addOval(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW) })
                drawable.setBounds(0, 0, size, size)
                drawable.draw(this)
            }

            Icon.createWithBitmap(bitmap)
        } catch (t: Throwable) {
            // 拿不到 launcher 图标时退回项目内置图标，不让整条通知链路失败。
            DebugLogger.d(TAG, "应用图标裁剪失败，回退内置图标：${t.message}")
            Icon.createWithResource(context, R.drawable.ic_workflows)
        }

        cachedAppIcon = icon
        return icon
    }
}
