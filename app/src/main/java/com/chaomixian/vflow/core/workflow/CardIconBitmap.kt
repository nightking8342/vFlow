package com.chaomixian.vflow.core.workflow

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import java.io.File

/**
 * 图片文件的**解码 → 中心裁剪 → 缩放到目标尺寸**（纯工具，无状态）。
 *
 * ## 为什么要有这个文件
 *
 * 这段逻辑原先**只**在 `ui/common/ShortcutHelper.kt` 里、且是 `private`。
 * 快捷设置磁贴要给 `Icon` 一张位图（`Icon.createWithBitmap`），而
 * `Icon` 会**跨 Binder** 传给 SystemUI ——
 * ⚠️ **不缩放就把原图交出去会撞 `TransactionTooLargeException`**
 * （与 `IslandRemoteViews` 那次崩溃同源），表现是**磁贴完全不更新**。
 *
 * ⇒ 抽到这里复用，**不复制一份**（本仓库记过双份实现的代价：`FORK.md` 里 logcat 两处实现
 * 「语义改动必须同时改两处，不一致的表现是调试工具能匹配而触发器匹配不到」）。
 * `ShortcutHelper` 改为调用本文件。
 *
 * ## 三条硬约束（都是「改错了不报错、只静默变差」）
 *
 * 1. **目标尺寸 192** —— 与 `ShortcutHelper` 原先一致。调大不会报错，只会更早撞 Binder 上限。
 * 2. **解码失败一律返回 `null`**，**绝不返回半成品** —— 调用方据此回落（磁贴回落到
 *    `ic_workflows`，快捷方式回落到 `ic_shortcut_play`）。
 *    ⚠️ 文件已删 / 换机后路径失效是**常态**：直接把 `null` 交给 `Icon.createWithBitmap`
 *    会 **NPE 崩 service**。
 * 3. **逐级 `recycle()`** —— 一张 4K 照片的中间态是三份位图，不回收会 OOM（尤其是磁贴
 *    在 SystemUI 每次刷新时都会走一遍）。
 *
 * ⚠️ **本文件的单测边界（如实记录）**：`android.graphics.BitmapFactory` 在纯 JVM 单测里是
 * mockable stub ⇒ `decodeFile` 大概率直接返回 `null`，那么「文件不存在返回 null」这条用例
 * **通过的原因恰恰是解码根本没执行**。「缩放目标 / 中心裁剪」的**真实行为单测覆盖不到**，
 * 只能靠真机验证清单。为此把两处**纯算术**抽成 [inSampleSizeFor] / [centerCropOf] 单独断言。
 */
object CardIconBitmap {

    private const val TAG = "CardIconBitmap"

    /** 目标边长（像素）。与 `ShortcutHelper` 原先的 `targetSize` 逐字一致。 */
    const val TARGET_SIZE = 192

    /**
     * 加载并裁剪图片为正方形（中心裁剪），然后缩放到 [TARGET_SIZE]。
     *
     * @return 成功返回位图；文件不存在 / 尺寸非法 / 解码失败 / 裁剪抛异常一律返回 `null`。
     */
    fun loadCenterCroppedBitmap(filePath: String): Bitmap? =
        loadCenterCroppedBitmap(filePath, TARGET_SIZE)

    /**
     * 同 [loadCenterCroppedBitmap]，但目标尺寸可指定（供单测与将来的其它消费点使用）。
     *
     * ⚠️ 函数体**逐字搬运**自 `ShortcutHelper.kt`（改动前）的 `loadCenterCroppedBitmap`，
     * 只把 `targetSize` 参数化。**不重写、不「顺手优化」** —— 它已经在生产里跑着。
     */
    fun loadCenterCroppedBitmap(filePath: String, targetSize: Int): Bitmap? {
        // 先获取图片尺寸
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(filePath, options)

        if (options.outWidth <= 0 || options.outHeight <= 0) {
            return null
        }

        // 计算正方形边长（取宽高的较小值）
        val squareSize = minOf(options.outWidth, options.outHeight)

        // 计算采样率，使加载后的图片不超过目标尺寸的2倍（为了更好的质量）
        val inSampleSize = inSampleSizeFor(squareSize, targetSize)

        // 加载缩放后的图片
        val decodeOptions = BitmapFactory.Options().apply {
            this.inSampleSize = inSampleSize
        }
        val fullBitmap = BitmapFactory.decodeFile(filePath, decodeOptions) ?: return null

        try {
            // 裁剪区域（中心正方形）
            val bounds = centerCropOf(fullBitmap.width, fullBitmap.height)

            // 裁剪出中心正方形
            val croppedBitmap = Bitmap.createBitmap(
                fullBitmap,
                bounds[0], bounds[1], bounds[2], bounds[3]
            )

            // 如果裁剪后的图片不等于原图，回收原图
            if (croppedBitmap != fullBitmap) {
                fullBitmap.recycle()
            }

            // 缩放到目标尺寸
            val finalBitmap = Bitmap.createScaledBitmap(
                croppedBitmap,
                targetSize,
                targetSize,
                true
            )

            // 如果缩放后的图片不等于裁剪图片，回收裁剪图片
            if (finalBitmap != croppedBitmap) {
                croppedBitmap.recycle()
            }

            return finalBitmap
        } catch (e: Exception) {
            fullBitmap.recycle()
            Log.e(TAG, "Failed to crop bitmap", e)
            return null
        }
    }

    /**
     * 采样率：让解码后的边长不超过 [targetSize] 的 2 倍，且**取 2 的幂**。
     *
     * ⚠️ 抽成纯函数是因为 [loadCenterCroppedBitmap] 在纯 JVM 单测里跑不到 ——
     * `BitmapFactory` 是 stub。这段算术是唯一能机器化验证的部分。
     *
     * ⚠️ `BitmapFactory` 只认 2 的幂（非 2 的幂会被它自己向下取整），
     * 所以这里用 `Integer.highestOneBit` 而不是直接整除。
     */
    internal fun inSampleSizeFor(squareSize: Int, targetSize: Int): Int {
        if (targetSize <= 0) return 1
        var inSampleSize = 1
        if (squareSize > targetSize * 2) {
            inSampleSize = squareSize / (targetSize * 2)
            // 确保是2的幂次方
            inSampleSize = Integer.highestOneBit(inSampleSize)
        }
        return inSampleSize.coerceAtLeast(1)
    }

    /**
     * 中心正方形裁剪区域，返回 `[x, y, width, height]`。
     *
     * ⚠️ 边长取**宽高的较小值**；偏移取差值的一半（**整数除法**，落在偏左上那一侧，
     * 与改动前逐字一致 —— 不要"修正"成四舍五入，那会改变既有快捷方式的裁剪结果）。
     */
    internal fun centerCropOf(width: Int, height: Int): IntArray {
        val cropSize = minOf(width, height)
        val x = (width - cropSize) / 2
        val y = (height - cropSize) / 2
        return intArrayOf(x, y, cropSize, cropSize)
    }

    /** 该路径是否存在且是可读文件（调用方据此决定要不要走 [loadCenterCroppedBitmap]）。 */
    fun isUsableFile(filePath: String?): Boolean {
        if (filePath.isNullOrBlank()) return false
        return runCatching { File(filePath).isFile }.getOrDefault(false)
    }
}
