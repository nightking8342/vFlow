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
 */
internal object IslandIcons {

    private const val TAG = "IslandIcons"

    /** 应用图标缓存。裁剪成圆形位图的开销不值得每次发通知都做一遍。 */
    @Volatile
    private var cachedAppIcon: Icon? = null

    /**
     * 构建 `miui.focus.pics` Bundle。
     *
     * @param context 应用上下文。
     * @param state 当前状态，决定工作流图标用哪一张。
     */
    fun buildPics(context: Context, state: IslandNotificationSpec.State): Bundle =
        Bundle().apply {
            putParcelable(IslandParamsBuilder.PIC_WORKFLOW, workflowIcon(context, state))
            putParcelable(IslandParamsBuilder.PIC_APP, roundAppIcon(context))
        }

    /**
     * 状态对应的工作流图标。
     *
     * 三个 drawable 都是项目既有的（与 `ExecutionNotificationManager` 通知小图标用的
     * 是同一套），无需新增资源。
     */
    private fun workflowIcon(context: Context, state: IslandNotificationSpec.State): Icon {
        val resId = when (state) {
            IslandNotificationSpec.State.RUNNING -> R.drawable.ic_workflows
            IslandNotificationSpec.State.COMPLETED -> R.drawable.rounded_save_24
            IslandNotificationSpec.State.FAILED -> R.drawable.rounded_close_small_24
            IslandNotificationSpec.State.CANCELLED -> R.drawable.rounded_close_small_24
        }
        return Icon.createWithResource(context, resId)
    }

    /**
     * 把 launcher 图标裁成圆形位图。
     *
     * **为什么不能直接用 `Icon.createWithResource(context, R.mipmap.ic_launcher)`**：
     * API 26+ 的 launcher 图标是自适应图标（`<adaptive-icon>` XML），交给 SystemUI
     * 在岛的小圆形容器里渲染会**变成方形**。必须自己画成圆形位图。
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
