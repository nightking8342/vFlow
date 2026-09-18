// 文件: main/java/com/chaomixian/vflow/services/island/IslandIcons.kt
package com.chaomixian.vflow.services.island

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.drawable.Icon
import android.os.Bundle
import com.chaomixian.vflow.R
import com.chaomixian.vflow.core.logging.DebugLogger

/**
 * 超级岛的图标装配。
 *
 * 岛上各处的图标统一使用**应用图标**——它是用户识别「这条通知来自 vFlow」最直接的线索，
 * 比功能图标更能表达来源。状态差异由岛上的文本与强调色承载，不再靠换图标。
 *
 * **注意**：这里的图标走 `miui.focus.pics` 通道（JSON 用 `{"type":1,"pic":"<key>"}` 引用）
 * 或 RemoteViews 的 `setImageViewBitmap`，显示的都是**全彩图形**——
 * 与 Android 通知小图标「alpha 蒙版、颜色由系统填充」是两回事。
 */
internal object IslandIcons {

    private const val TAG = "IslandIcons"

    /** 裁圆后的应用图标位图缓存。裁剪有开销，不值得每次发通知都做一遍。 */
    @Volatile
    private var cachedAppBitmap: Bitmap? = null

    /**
     * 构建 `miui.focus.pics` Bundle。
     *
     * 岛上多处（大岛 A 区、小岛容器、状态栏 ticker、息屏）都引用同一个 key。
     *
     * @param context 应用上下文。
     */
    fun buildPics(context: Context): Bundle =
        Bundle().apply {
            putParcelable(IslandParamsBuilder.PIC_APP, roundAppIcon(context))
        }

    /**
     * 裁圆后的应用图标位图。
     *
     * 供 RemoteViews 展开态使用（那里要 Bitmap 而非 Icon）。
     */
    fun appIconBitmap(context: Context): Bitmap = roundedAppBitmap(context)

    private fun roundAppIcon(context: Context): Icon =
        Icon.createWithBitmap(roundedAppBitmap(context))

    /**
     * 把 launcher 图标裁成圆形位图。
     *
     * **为什么需要裁剪**：API 26+ 的 launcher 图标是自适应图标（`<adaptive-icon>` XML），
     * 直接交给 SystemUI 渲染会显示成**方形**，与岛/通知的圆形容器不符。
     */
    private fun roundedAppBitmap(context: Context): Bitmap {
        cachedAppBitmap?.let { return it }

        val bitmap = try {
            val drawable = context.packageManager.getApplicationIcon(context.packageName)
            val size = maxOf(48, (48 * context.resources.displayMetrics.density).toInt())

            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).also { bmp ->
                Canvas(bmp).apply {
                    clipPath(
                        Path().apply {
                            addOval(0f, 0f, size.toFloat(), size.toFloat(), Path.Direction.CW)
                        }
                    )
                    drawable.setBounds(0, 0, size, size)
                    drawable.draw(this)
                }
            }
        } catch (t: Throwable) {
            // 拿不到 launcher 图标时退回项目内置图标，不让整条通知链路失败。
            DebugLogger.d(TAG, "应用图标裁剪失败，回退内置图标：${t.message}")
            BitmapFactory.decodeResource(context.resources, R.drawable.ic_workflows)
        }

        cachedAppBitmap = bitmap
        return bitmap
    }
}
