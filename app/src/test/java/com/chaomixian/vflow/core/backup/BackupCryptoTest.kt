// 文件: test/java/com/chaomixian/vflow/core/backup/BackupCryptoTest.kt
package com.chaomixian.vflow.core.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackupCrypto] 的纯 JVM 单测。
 *
 * ## 本文件最重要的一条
 *
 * **「口令错」与「数据损坏」必须可区分。** 任务书把它列为硬要求，
 * 而两者在密码学层面完全一样（都是 GCM tag 校验失败）—— 分开它们靠的是
 * `verifier` + `verifierHash` 两条**独立**证据。
 *
 * ⚠️ 迭代次数刻意用**小值**（1000）—— 一次 210000 次派生约 0.1–0.4 秒，
 * 每条用例都跑真值会让整个测试类慢到不可接受。
 * 「默认值真的是 210000」由另外两条**轻量**断言覆盖（不跑派生）。
 */
class BackupCryptoTest {

    private val passphrase = "correct horse".toCharArray()
    private val salt = ByteArray(16) { it.toByte() }

    /** 用一个已知 salt/迭代数构造上下文，便于断言可复现。 */
    private fun context(
        pass: CharArray = passphrase,
        iterations: Int = 1000,
        s: ByteArray = salt
    ): SecretContext = SecretContext(pass, s, iterations)

    private fun sectionOf(ctx: SecretContext): EncryptionSection = EncryptionSection(
        algorithm = BackupPipeline.ALGORITHM,
        kdf = BackupCrypto.KDF_ALGORITHM,
        iterations = ctx.iterationCount,
        salt = BackupCrypto.base64(ctx.saltForEnvelope),
        verifier = ctx.verifier,
        verifierHash = BackupCrypto.sha256Base64(BackupCrypto.base64OrNull(ctx.verifier)!!)
    )

    // ── 往返 ────────────────────────────────────────────────

    @Test
    fun `round trip returns the original plaintext`() {
        val ctx = context()
        val sealed = ctx.seal("sk-secret-value")
        assertEquals("sk-secret-value", ctx.openSlot(sealed))
    }

    @Test
    fun `round trip works for multi byte and emoji payloads`() {
        val ctx = context()
        val text = "中文🎉ß€𝄞😀"
        assertEquals(text, ctx.openSlot(ctx.seal(text)))
    }

    // ── 判定链（本文件的核心）────────────────────────────────

    @Test
    fun `a correct passphrase passes the verifier`() {
        val ctx = context()
        assertNull("口令正确时 judge 必须返回 null", BackupCrypto.judge(passphrase, sectionOf(ctx)))
    }

    @Test
    fun `a wrong passphrase is reported as WRONG_PASSPHRASE not CORRUPTED`() {
        // ⚠️⚠️ 任务书点名的硬要求。判成 CORRUPTED 会让用户去查文件，
        //     而文件其实完好；判成 WRONG_PASSPHRASE 才是让他重输口令。
        val ctx = context()
        val section = sectionOf(ctx)

        assertEquals(
            "口令错必须判 WRONG_PASSPHRASE —— 判成 CORRUPTED 会误导用户去修一个完好的文件",
            BackupCrypto.Failure.WRONG_PASSPHRASE,
            BackupCrypto.judge("wrong horse".toCharArray(), section)
        )
    }

    @Test
    fun `a single flipped byte in the verifier ciphertext is CORRUPTED`() {
        // verifier 密文被改动 ⇒ verifierHash 不符 ⇒ **不派生密钥**即判损坏。
        // 这一支若缺失，用户会对着一个坏文件反复重输**正确**的口令。
        val ctx = context()
        val section = sectionOf(ctx)

        val tampered = flipLastByte(section.verifier)
        assertEquals(
            "verifier 被篡改（hash 不符）必须判 CORRUPTED —— 与「口令错」是两件不同的事",
            BackupCrypto.Failure.CORRUPTED,
            BackupCrypto.judge(passphrase, section.copy(verifier = tampered))
        )
    }

    @Test
    fun `a flipped verifierHash byte is CORRUPTED`() {
        val ctx = context()
        val section = sectionOf(ctx)
        val tamperedHash = flipLastByte(section.verifierHash)

        assertEquals(
            "hash 字段本身被改 ⇒ CORRUPTED（此时根本不知道 verifier 有没有被动过）",
            BackupCrypto.Failure.CORRUPTED,
            BackupCrypto.judge(passphrase, section.copy(verifierHash = tamperedHash))
        )
    }

    @Test
    fun `the wrong passphrase and the tampered verifier are distinguishable`() {
        // ⚠️⚠️ 这条是「可区分」这条要求的**直接断言**：同样的错误口令、
        //     同样的文件，只因为 verifier 被动过，结论就必须不同。
        val ctx = context()
        val good = sectionOf(ctx)
        val bad = good.copy(verifier = flipLastByte(good.verifier))

        val wrongPass = BackupCrypto.judge("nope".toCharArray(), good)
        val corrupted = BackupCrypto.judge("nope".toCharArray(), bad)

        assertNotEquals(
            "「口令错」与「文件损坏」必须给出不同的结论（任务书硬要求）",
            wrongPass, corrupted
        )
        assertEquals(BackupCrypto.Failure.WRONG_PASSPHRASE, wrongPass)
        assertEquals(BackupCrypto.Failure.CORRUPTED, corrupted)
    }

    // ── 参数合法性与防御 ────────────────────────────────────

    @Test
    fun `illegal base64 in salt is CORRUPTED not an exception`() {
        val section = sectionOf(context())
        assertEquals(
            BackupCrypto.Failure.CORRUPTED,
            BackupCrypto.judge(passphrase, section.copy(salt = "!!!not base64!!!"))
        )
    }

    @Test
    fun `iterations beyond the safety cap is CORRUPTED`() {
        // ⚠️ 迭代次数是从**文件里读的**。一个被构造/损坏的备份若写个
        //    20 亿次，PBKDF2 会跑几十分钟 —— 表现是「点了恢复之后 App 卡死」。
        val section = sectionOf(context())
        assertEquals(
            "超出上限的迭代数必须判损坏（否则会让恢复操作变成不可中断的挂起）",
            BackupCrypto.Failure.CORRUPTED,
            BackupCrypto.judge(passphrase, section.copy(iterations = BackupCrypto.MAX_ITERATIONS + 1))
        )
    }

    @Test
    fun `zero or negative iterations is CORRUPTED`() {
        val section = sectionOf(context())
        for (bad in listOf(0, -1)) {
            assertEquals(
                "iterations=$bad 必须判损坏",
                BackupCrypto.Failure.CORRUPTED,
                BackupCrypto.judge(passphrase, section.copy(iterations = bad))
            )
        }
    }

    @Test
    fun `deriveKey rejects non positive iterations instead of silently defaulting`() {
        // ⚠️ 静默退回默认值会让「信封里的迭代数被改成 0」这种损坏
        //    变成「用默认值正常解开」，用户永远不知道文件被动过。
        for (bad in listOf(0, -1, -100)) {
            try {
                BackupCrypto.deriveKey(passphrase, salt, bad)
                org.junit.Assert.fail("iterations=$bad 应当抛 IllegalArgumentException，而不是静默退回默认值")
            } catch (expected: IllegalArgumentException) {
                // 期望路径
            }
        }
    }

    // ── salt / iv 随机性 ───────────────────────────────────

    @Test
    fun `newSalt returns a fresh random salt each time`() {
        val seen = mutableSetOf<String>()
        repeat(32) {
            val s = BackupCrypto.newSalt()
            assertEquals(BackupCrypto.SALT_BYTES, s.size)
            assertTrue("salt 出现重复 —— SecureRandom 被换成了固定值？", seen.add(BackupCrypto.base64(s)))
        }
    }

    @Test
    fun `two exports of the same value produce different ciphertext`() {
        // IV 每次随机 ⇒ 同一明文两次加密必须不同。
        // 相同就意味着「同一密钥 + 同一 IV」被重用，GCM 下会泄漏明文异或值。
        val ctx = context()
        val a = ctx.seal("same")
        val b = ctx.seal("same")
        assertNotEquals(a.toString(), b.toString())
        assertEquals("same", ctx.openSlot(a))
        assertEquals("same", ctx.openSlot(b))
    }

    @Test
    fun `default contexts use different salts so the same passphrase yields different keys`() {
        val a = SecretContext(passphrase)
        val b = SecretContext(passphrase)
        assertNotEquals(
            "两个默认上下文的 salt 必须不同（否则同一口令派生同一密钥，丧失 KDF 的意义）",
            BackupCrypto.base64(a.saltForEnvelope),
            BackupCrypto.base64(b.saltForEnvelope),
        )
    }

    // ── 迭代次数从信封读（全链路，但不慢）────────────────────

    @Test
    fun `import context reads iterations and salt from the envelope`() {
        // ⚠️ 覆盖「迭代次数从信封读」这条 —— 若 forImport 误用默认值，
        //    备份里记录的迭代数/盐就白存了，且**没有任何报错**：
        //    解密会以 tag 不符失败，被上层判成「口令错」，
        //    用户于是反复重输一个本来就对的口令。
        val exportCtx = context(iterations = 1234)
        val section = sectionOf(exportCtx)
        val importCtx = SecretContext.forImport(passphrase, section)

        assertEquals(1234, importCtx.iterationCount)
        assertEquals(
            BackupCrypto.base64(exportCtx.saltForEnvelope),
            BackupCrypto.base64(importCtx.saltForEnvelope),
        )
        // 派生密钥相同 ⇒ 导出的密文能被导入上下文解开。
        assertEquals("cross-context", importCtx.openSlot(exportCtx.seal("cross-context")))
    }

    @Test
    fun `forImport rejects a malformed salt instead of silently using the default`() {
        val section = sectionOf(context())
        try {
            SecretContext.forImport(passphrase, section.copy(salt = "@@@"))
            org.junit.Assert.fail("salt 非法时 forImport 应当抛，而不是静默用默认 salt")
        } catch (expected: IllegalArgumentException) {
            // 期望路径
        }
    }

    // ── 默认值（轻量，不跑 210000 次派生）──────────────────

    @Test
    fun `the default iteration count is 210000 and is actually wired in`() {
        // ⚠️ **不能只断言常量等于 210000** —— 那证明不了生产路径**用**了它：
        //    把 SecretContext 的默认参数写成 1000 也照样全绿。
        assertEquals(210_000, BackupCrypto.DEFAULT_ITERATIONS)
        assertEquals(
            "SecretContext 的默认迭代数必须引用 BackupCrypto.DEFAULT_ITERATIONS",
            210_000,
            SecretContext(passphrase).iterationCount,
        )
    }

    @Test
    fun `sha256Base64 is stable and base64OrNull never throws`() {
        val bytes = byteArrayOf(1, 2, 3)
        assertEquals(BackupCrypto.sha256Base64(bytes), BackupCrypto.sha256Base64(bytes))
        assertNotEquals(
            BackupCrypto.sha256Base64(bytes),
            BackupCrypto.sha256Base64(byteArrayOf(1, 2, 4)),
        )
        assertNull("非法 base64 必须返回 null 而不是抛", BackupCrypto.base64OrNull("!!!"))
        assertNull(BackupCrypto.base64OrNull("a"))  // 长度不合法
    }

    @Test
    fun `verifier is cached so repeated reads yield the same ciphertext`() {
        // ⚠️⚠️ 若 verifier 不缓存，两次取值会因新 IV 而不同，
        //     而 BackupPipeline 要用它同时填 verifier 字段与算 verifierHash
        //     ⇒ hash 永远匹配不上 ⇒ **每次导入都判「文件已损坏」**。
        val ctx = context()
        assertEquals(
            "verifier 必须缓存（否则 verifierHash 与 verifier 永远对不上）",
            ctx.verifier, ctx.verifier,
        )
    }

    private fun flipLastByte(base64: String): String {
        val bytes = BackupCrypto.base64OrNull(base64)!!
        bytes[bytes.size - 1] = (bytes[bytes.size - 1].toInt() xor 0x01).toByte()
        return BackupCrypto.base64(bytes)
    }
}
