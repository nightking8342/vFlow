package com.chaomixian.vflow.services

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * 「改了 Core 需要重启」判据的回归测试。
 *
 * ## 为什么这个判断值得单独测
 *
 * 它错了的两个方向代价都不小：
 *
 * | 错法 | 后果 |
 * |---|---|
 * | 该提示却返回 false | 用户**静默地继续跑旧代码**——这个坑已实际踩过两次（logcat 触发器的 Core 侧实现、路由表注册），两次都是"代码明明写了却不生效"，排查成本极高 |
 * | 不该提示却返回 true | 提示变得不可信，用户最终学会无视它 |
 */
class CoreDexFingerprintTest {

    private fun streamOf(vararg bytes: Byte) = ByteArrayInputStream(bytes)

    // ── 指纹计算 ────────────────────────────────────────────────

    @Test
    fun `fingerprint is sixteen hex characters`() {
        val fp = coreDexFingerprint(streamOf(1, 2, 3, 4, 5))
        assertEquals(16, fp.length)
        assertTrue("应全为十六进制字符: $fp", fp.all { it in "0123456789abcdef" })
    }

    @Test
    fun `identical content yields identical fingerprints`() {
        // ✅ 已实测 `:core:buildDex` 是确定性的（同样源码 → 字节相同的 dex）。
        // 这条是那个结论的前提：不成立的话每次构建都会误报"core 有更新"
        val a = coreDexFingerprint(streamOf(1, 2, 3, 4, 5))
        val b = coreDexFingerprint(streamOf(1, 2, 3, 4, 5))
        assertEquals(a, b)
    }

    @Test
    fun `different content yields different fingerprints`() {
        val a = coreDexFingerprint(streamOf(1, 2, 3, 4, 5))
        val b = coreDexFingerprint(streamOf(1, 2, 3, 4, 6))
        assertNotEquals(a, b)
    }

    @Test
    fun `a single bit flip is detected`() {
        // 改一行代码通常只影响 dex 里的一小段。检测不出来就等于没提示
        val base = ByteArray(4096) { it.toByte() }
        val modified = base.copyOf().also { it[2048] = (it[2048] + 1).toByte() }

        assertNotEquals(
            coreDexFingerprint(ByteArrayInputStream(base)),
            coreDexFingerprint(ByteArrayInputStream(modified))
        )
    }

    @Test
    fun `content larger than the read buffer is hashed completely`() {
        // ⚠️ 必须逐块读完，不能只读首块 —— dex 有 2.7MB，远大于 64KB 缓冲。
        // 只读首块的话，改动落在文件后半部分的代码就检测不到了
        val big = ByteArray(300_000) { (it % 251).toByte() }
        val modifiedNearEnd = big.copyOf().also { it[290_000] = (it[290_000] + 1).toByte() }

        assertNotEquals(
            "文件末尾的改动必须被检测到",
            coreDexFingerprint(ByteArrayInputStream(big)),
            coreDexFingerprint(ByteArrayInputStream(modifiedNearEnd))
        )
    }

    @Test
    fun `empty input still yields a valid fingerprint`() {
        val fp = coreDexFingerprint(streamOf())
        assertEquals(16, fp.length)
    }

    // ── 判定逻辑 ★ ──────────────────────────────────────────────

    @Test
    fun `prompts when the fingerprint changed`() {
        // 核心场景：装了新 apk，dex 变了 → 应提示重启
        assertTrue(shouldPromptCoreRestart("aaaa1111", "bbbb2222"))
    }

    @Test
    fun `does not prompt when the fingerprint is unchanged`() {
        // 改了一行 app 代码、core 没动 → 不该提示。
        // 这正是用指纹而非构建时间戳的原因
        assertFalse(shouldPromptCoreRestart("same1234", "same1234"))
    }

    @Test
    fun `does not prompt when nothing was ever recorded`() {
        // 首次安装 / 从更早的、还没有指纹记录的版本升级上来：
        // 那时 Core 本来就会被启动，不需要额外提醒
        assertFalse(shouldPromptCoreRestart("aaaa1111", null))
        assertFalse(shouldPromptCoreRestart("aaaa1111", ""))
        assertFalse(shouldPromptCoreRestart("aaaa1111", "   "))
    }

    @Test
    fun `does not prompt when the current fingerprint cannot be read`() {
        // ⚠️ 读不出当前指纹时选择**不提示**：
        // 提示了用户也没法判断真假，而"提示不可信"比"没提示"更糟
        assertFalse(shouldPromptCoreRestart(null, "bbbb2222"))
        assertFalse(shouldPromptCoreRestart("", "bbbb2222"))
        assertFalse(shouldPromptCoreRestart("   ", "bbbb2222"))
    }

    @Test
    fun `does not prompt when neither side is known`() {
        assertFalse(shouldPromptCoreRestart(null, null))
    }

    @Test
    fun `comparison is exact not case insensitive`() {
        // 指纹是十六进制串，理论上不该出现大小写差异；
        // 若出现了，说明生成逻辑有两套 —— 那时**应当**提示，而不是掩盖
        assertTrue(shouldPromptCoreRestart("ABCD1234", "abcd1234"))
    }

    // ── 与真实 dex 的对照 ───────────────────────────────────────

    @Test
    fun `the real bundled dex produces a stable fingerprint`() {
        // 用真实产物跑一次，确认算法在实际大小（2.7MB）下也能正常工作。
        // 读不到就跳过 —— 单测环境不一定有 assets
        val file = java.io.File("src/main/assets/vFlowCore.dex")
        if (!file.isFile) return

        val first = file.inputStream().use { coreDexFingerprint(it) }
        val second = file.inputStream().use { coreDexFingerprint(it) }

        assertEquals("同一文件两次读取必须一致", first, second)
        assertEquals(16, first.length)
    }
}
