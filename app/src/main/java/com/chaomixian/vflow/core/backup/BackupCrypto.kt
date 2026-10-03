// 文件: main/java/com/chaomixian/vflow/core/backup/BackupCrypto.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.security.AeadFailure
import com.chaomixian.vflow.core.security.AesGcmEngine
import com.chaomixian.vflow.core.security.JvmAesGcmEngine
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * 备份加密的**参数常量与判定层**（纯 JVM，无 Android 依赖）。
 *
 * ## 分工
 *
 * | 层 | 职责 |
 * |---|---|
 * | [AesGcmEngine] | 纯粹的 AEAD 原语（`seal` / `open`） |
 * | **本对象** | KDF 派生 + 「口令错 / 数据损坏」的**判定** |
 * | [SecretEnvelope] | 字段标记的形状（`{"$enc":…,"data":…}`） |
 * | `BackupPipeline` | 编排（何时验、何时解、失败怎么上报） |
 *
 * ⚠️ 判定逻辑放在本对象而不是 `BackupPipeline`，是为了让
 * 「错口令 vs 损坏」这条硬要求能**纯 JVM 直接单测**（`BackupCryptoTest`），
 * 不必先搭一整套备份信封。
 */
object BackupCrypto {

    /** PBKDF2 的 salt 长度（字节）。 */
    const val SALT_BYTES = 16

    /**
     * PBKDF2 迭代次数。
     *
     * ⚠️ **常量只在这里定义一次** —— `SecretContext` 的默认迭代数引用它，
     * 测试也断言它。三处各写一份的话，「生产用 1000 次」这种降级不会有人发现。
     */
    const val DEFAULT_ITERATIONS = 210_000

    /** 派生密钥长度（bit）。 */
    const val KEY_BITS = 256

    const val KDF_ALGORITHM = "PBKDF2WithHmacSHA256"

    /**
     * verifier 的明文 —— **内嵌 schemaVersion**（`v1`）。
     *
     * 信封里存的是「用派生密钥加密这段常量」的结果。导入时先解它：
     * 解得开 ⇒ 口令对；解不开 ⇒ 口令错。它让「口令错」与「数据损坏」
     * 有了**第一条**分界依据（第二条是 [EncryptionSection.verifierHash]）。
     *
     * ⚠️ 尾缀 `v1` 与 `BackupEnvelope.SCHEMA_VERSION` 是**两件事**：
     * 前者标识「verifier 这份常量的形态」，后者标识信封结构。
     * 改信封结构不必改它；改 verifier 的构造方式才要改（那会让旧备份全部判口令错）。
     */
    const val VERIFIER_PLAINTEXT = "vflow.backup.verifier.v1"

    /** 字段加密标记的键名 —— JSON 里就是 `"$enc"`。 */
    const val MARKER_KEY = "\$enc"

    /** 字段加密标记的值。改它会**静默地**让旧备份的密文字段解不开。 */
    const val MARKER_VALUE = "aes-gcm-v1"

    /**
     * 从信封读到的迭代次数上限。
     *
     * ⚠️ **本方案对设计正文的一处防御性补充**：迭代次数是**从备份文件里读的**
     * （这是必要的 —— 否则将来提高默认值会让旧备份解不开）。但一个损坏或被构造的
     * 备份可以写 `iterations = 2000000000`，那会让 PBKDF2 **跑几十分钟**，
     * 表现是「点了恢复之后 App 卡死」。
     *
     * 上限取 1000 万：比默认值（21 万）高 47 倍，足够容纳未来多年的提升，
     * 又不至于让一次误操作变成不可中断的挂起。超出即判 [Failure.CORRUPTED]。
     */
    const val MAX_ITERATIONS = 10_000_000

    /**
     * 「口令错」与「数据损坏」的**唯一出口**。
     *
     * ⚠️ 两者在密码学层面**一模一样**（都表现为 GCM tag 校验失败），
     * 分开它们靠的是 verifier + verifierHash 两条**独立**证据，不是靠异常类型。
     */
    enum class Failure {
        /** verifier 密文完整（hash 相符）但用派生密钥解不开 ⇒ 口令不对。 */
        WRONG_PASSPHRASE,

        /** 密文/信封被改动、形状不合法、参数越界 —— 与口令无关。 */
        CORRUPTED
    }

    private val secureRandom = SecureRandom()

    /** 每次调用都取新的随机 salt，**不复用**。 */
    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { secureRandom.nextBytes(it) }

    /**
     * PBKDF2 派生 256bit 密钥。
     *
     * ⚠️ `iterations <= 0` **抛异常而不是静默退回默认值** ——
     * 静默退回会让「信封里的迭代数被改成 0」这种损坏变成「用默认值正常解开」，
     * 用户永远不知道文件被动过。
     */
    fun deriveKey(passphrase: CharArray, salt: ByteArray, iterations: Int): ByteArray {
        require(iterations > 0) { "iterations 必须为正数（收到 $iterations）" }
        val spec = PBEKeySpec(passphrase, salt, iterations, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance(KDF_ALGORITHM).generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    fun base64(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    /** 非法 base64 ⇒ **null（不抛）** —— 调用方据此判「形状不合法」而不是崩掉。 */
    fun base64OrNull(text: String): ByteArray? = try {
        Base64.getDecoder().decode(text)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** verifier 密文的 SHA-256（base64）。**与密钥派生无关**，纯完整性标记。 */
    fun sha256Base64(bytes: ByteArray): String =
        base64(MessageDigest.getInstance("SHA-256").digest(bytes))

    /**
     * 判定加密段是否可用 —— **判定链的唯一实现**。
     *
     * 四支（顺序即优先级，见方案 §6.3 的表）：
     *
     * | 情形 | verifierHash | verifier 能否解开 | 结果 |
     * |---|---|---|---|
     * | 口令错、文件完整 | 符 | 否 | [Failure.WRONG_PASSPHRASE] |
     * | verifier 被篡改 | **不符** | ——（**不派生密钥**） | [Failure.CORRUPTED] |
     * | 数据被篡改、verifier 完整 | 符 | 是 | `null`（通过，坏在别处） |
     * | hash 字段本身被改 | 不符 | —— | [Failure.CORRUPTED] |
     *
     * @return **null = 通过**（verifier 解得开且内容相符）。
     *
     * ⚠️ 第 2/4 支**刻意在派生密钥之前返回** —— 派生一次要 0.1–0.4 秒，
     * 而对一份已确认被改动的文件做派生毫无意义。
     *
     * ⚠️ 只有 [Failure.WRONG_PASSPHRASE] 这一支是「**让用户重输口令**」的信号。
     * 判成 [Failure.CORRUPTED] 时提示用户重输口令是**误导**（文件本来就是坏的）。
     */
    fun judge(
        passphrase: CharArray,
        section: EncryptionSection,
        engine: AesGcmEngine = JvmAesGcmEngine()
    ): Failure? {
        // ① 先量 verifier 的完整性。这一步**不需要**密钥。
        val verifierBytes = base64OrNull(section.verifier)
            ?: return Failure.CORRUPTED
        if (section.verifierHash.isBlank() ||
            sha256Base64(verifierBytes) != section.verifierHash
        ) {
            return Failure.CORRUPTED
        }

        // ② 参数合法性 —— 越界同样归「损坏」，因为它与用户的口令无关。
        if (section.iterations <= 0 || section.iterations > MAX_ITERATIONS) {
            return Failure.CORRUPTED
        }
        val salt = base64OrNull(section.salt) ?: return Failure.CORRUPTED

        // ③ 派生 + 解 verifier。到这里才可能真的是「口令错」。
        val key = try {
            deriveKey(passphrase, salt, section.iterations)
        } catch (e: Exception) {
            return Failure.CORRUPTED
        }

        val plain = try {
            engine.open(key, verifierBytes)
        } catch (e: AeadFailure) {
            // verifier 完整、参数合法、但用这个口令解不开 ⇒ **只有口令错这一种解释**。
            return Failure.WRONG_PASSPHRASE
        } catch (e: Exception) {
            return Failure.CORRUPTED
        }

        // ④ 解得开还不够 —— 明文必须是那段已知常量。
        //    多这一步是为了挡「用另一个口令加密的、长度恰好合法的随机串」这种构造。
        return if (plain.toString(Charsets.UTF_8) == VERIFIER_PLAINTEXT) {
            null
        } else {
            Failure.CORRUPTED
        }
    }
}
