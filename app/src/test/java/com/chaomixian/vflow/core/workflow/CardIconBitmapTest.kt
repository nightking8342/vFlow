package com.chaomixian.vflow.core.workflow

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * `CardIconBitmap` 的**可机器化部分**（设计 §8.1）。
 *
 * ## ⚠️ 本文件的覆盖边界（如实记录，不得当成「解码逻辑已验证」）
 *
 * `android.graphics.BitmapFactory` 在纯 JVM 单测里是 **mockable stub** ——
 * 它的方法体不执行、`decodeFile` 恒返回 `null`。后果是：
 *
 * | 想验的东西 | 能不能验 |
 * |---|---|
 * | 采样率算术 [CardIconBitmap.inSampleSizeFor] | ✅ 纯算术，已抽成独立函数 |
 * | 中心裁剪区域 [CardIconBitmap.centerCropOf] | ✅ 同上 |
 * | 文件可读性 [CardIconBitmap.isUsableFile] | ✅ 用真实临时文件 |
 * | **「文件不存在 ⇒ 返回 null」** | ⚠️ **通过的原因不可靠** —— 真实的 `decodeFile` 在这个环境里本来就不执行，故这条用例**通过的原因恰恰是解码根本没跑**，而不是「我们判对了」 |
 * | **真实解码 / 裁剪 / 缩放 / `recycle()`** | ❌ **测不到**，只能靠真机（设计 §8.3） |
 *
 * ⇒ 这条边界必须写在这里，否则下一个人会以为「单测全绿 = 解码没问题」。
 * 真机上真正会出问题的是**缩放没生效**（4K 照片直接交给 `Icon` ⇒ 撞
 * `TransactionTooLargeException` ⇒ 磁贴完全不更新，且不报错），
 * 而那一点单测**永远测不出来**。
 */
class CardIconBitmapTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    // ── 采样率（纯算术）──────────────────────────────────────

    @Test
    fun `inSampleSizeFor is 1 when the image is not larger than twice the target`() {
        assertEquals(1, CardIconBitmap.inSampleSizeFor(squareSize = 384, targetSize = 192))
        assertEquals(1, CardIconBitmap.inSampleSizeFor(squareSize = 192, targetSize = 192))
        assertEquals(1, CardIconBitmap.inSampleSizeFor(squareSize = 100, targetSize = 192))
    }

    @Test
    fun `inSampleSizeFor downsamples once the image exceeds twice the target`() {
        // 768 = 192*4 ⇒ 768/(192*2) = 2
        assertEquals(2, CardIconBitmap.inSampleSizeFor(squareSize = 768, targetSize = 192))
        // 1536/(384) = 4
        assertEquals(4, CardIconBitmap.inSampleSizeFor(squareSize = 1536, targetSize = 192))
    }

    @Test
    fun `inSampleSizeFor always returns a power of two`() {
        // ⚠️ `BitmapFactory` 只认 2 的幂（非 2 的幂会被它自己向下取整）。
        //    若这里返回 3、5 这类值，**实际生效的**与我们要的不是同一个数 ——
        //    表现是「内存占用比预期高、偶尔 OOM」，而代码看起来完全正确。
        for (size in listOf(400, 700, 1000, 4096, 8000, 12345)) {
            val sample = CardIconBitmap.inSampleSizeFor(size, 192)
            assertTrue(
                "size=$size ⇒ sample=$sample 必须是 2 的幂",
                sample > 0 && (sample and (sample - 1)) == 0
            )
        }
    }

    @Test
    fun `inSampleSizeFor never returns zero or a negative value`() {
        // ⚠️ `inSampleSize = 0` 会让 `decodeFile` 抛 `IllegalArgumentException`
        //    （在 service 里 = 崩），而它是除法的可能结果。
        //    非正整数入参（targetSize <= 0）走的是 `return 1` 的早退分支。
        assertTrue(CardIconBitmap.inSampleSizeFor(0, 192) > 0)
        assertTrue(CardIconBitmap.inSampleSizeFor(1000, 0) > 0)
        assertTrue(CardIconBitmap.inSampleSizeFor(1000, -5) > 0)
        assertTrue(CardIconBitmap.inSampleSizeFor(Int.MAX_VALUE, 192) > 0)
    }

    // ── 中心裁剪区域（纯算术）────────────────────────────────

    @Test
    fun `centerCropOf takes the smaller side and centers the window`() {
        assertArrayEquals(
            intArrayOf(0, 0, 400, 400),
            CardIconBitmap.centerCropOf(400, 400)
        )
        // 宽 > 高：左右各裁掉 (600-400)/2 = 100
        assertArrayEquals(
            intArrayOf(100, 0, 400, 400),
            CardIconBitmap.centerCropOf(600, 400)
        )
        // 高 > 宽：上下各裁掉 (900-400)/2 = 250
        assertArrayEquals(
            intArrayOf(0, 250, 400, 400),
            CardIconBitmap.centerCropOf(400, 900)
        )
    }

    @Test
    fun `centerCropOf floors the offset for odd differences`() {
        // ⚠️ 差值奇数时是**整数除法**（落在偏左上那一侧），与抽取前逐字一致。
        //    「顺手修正成四舍五入」会改变**既有快捷方式**的裁剪结果 ——
        //    那是用户已经看惯的画面，不该被这次改动动到。
        assertArrayEquals(intArrayOf(0, 0, 3, 3), CardIconBitmap.centerCropOf(3, 4))
        assertArrayEquals(intArrayOf(0, 0, 3, 3), CardIconBitmap.centerCropOf(4, 3))
    }

    @Test
    fun `centerCropOf is always a square inside the source bounds`() {
        val cases = listOf(1 to 1, 1 to 100, 100 to 1, 1080 to 1920, 1920 to 1080, 4096 to 4096)
        for ((w, h) in cases) {
            val (x, y, cw, ch) = CardIconBitmap.centerCropOf(w, h).toList()
            assertEquals("$w x $h：裁剪区必须是正方形", cw, ch)
            assertEquals("$w x $h：边长取较小边", minOf(w, h), cw)
            assertTrue("$w x $h：x 不能越界", x >= 0 && x + cw <= w)
            assertTrue("$w x $h：y 不能越界", y >= 0 && y + ch <= h)
        }
    }

    // ── 文件可读性（真实文件）────────────────────────────────

    @Test
    fun `isUsableFile is true for a real file`() {
        val file = tempFolder.newFile("icon.png")
        assertTrue(CardIconBitmap.isUsableFile(file.absolutePath))
    }

    @Test
    fun `isUsableFile is false for null blank and missing paths`() {
        assertFalse(CardIconBitmap.isUsableFile(null))
        assertFalse(CardIconBitmap.isUsableFile(""))
        assertFalse(CardIconBitmap.isUsableFile("   "))
        assertFalse(CardIconBitmap.isUsableFile("/definitely/not/here/icon.png"))
    }

    @Test
    fun `isUsableFile is false for a directory`() {
        // ⚠️ 目录存在但不是文件 —— `File.isFile` 为 false。
        //    用 `exists()` 判会让目录通过，然后 `decodeFile` 在**真机**上返回 null。
        assertFalse(CardIconBitmap.isUsableFile(tempFolder.root.absolutePath))
    }

    @Test
    fun `the decode entry points return null or throw in a pure JVM environment`() {
        // ⚠️⚠️ **这条用例记录的是一个限制，不是一个行为契约。**
        //
        // 实测（本用例就是证据）：pure JVM 下 `BitmapFactory.decodeFile` 抛
        // `RuntimeException: Method decodeFile in android.graphics.BitmapFactory not mocked`
        // —— 它**不是**返回 null，而是**抛**。
        //
        // ⚠️ 由此推出**两条必须知道的事实**：
        // 1. `CardIconBitmap.loadCenterCroppedBitmap` 的「文件不存在 ⇒ 返回 null」
        //    这条分支在单测里**永远跑不到**（解码先抛了）⇒ 它的正确性
        //    **只能靠真机验证**（设计 §8.3：「自定义照片的磁贴不崩、不是空白」）。
        // 2. 生产代码里**不能**假设它「只会返回 null 而不抛」——
        //    `BitmapFactory` 在真机上对**损坏的图片文件**会抛 `RuntimeException`
        //    （`decodeFile` 的文档明确写了 "if the image data cannot be decoded"）。
        //    调用方（`BaseWorkflowTileService.applyWorkflowIcon`）因此必须
        //    把整段包在 try/catch 里，否则一个损坏的 PNG 就能**崩掉磁贴 service**。
        val thrown = runCatching {
            CardIconBitmap.loadCenterCroppedBitmap("/definitely/not/here/icon.png")
        }
        assertTrue(
            "pure JVM 下必须能观察到「抛」或「null」二者之一（用来锁住上面那条限制）",
            thrown.isFailure || thrown.getOrNull() == null
        )
    }

    @Test
    fun `target size stays at 192`() {
        // ⚠️ 这个数与「位图能不能过 Binder」直接相关（`Icon` 要跨进程传给 SystemUI）。
        //     调大不会报错，只会更早撞 `TransactionTooLargeException`，
        //     而表现是**磁贴完全不更新**（静默）。192 与 `ShortcutHelper` 原值一致。
        assertEquals(192, CardIconBitmap.TARGET_SIZE)
    }
}
