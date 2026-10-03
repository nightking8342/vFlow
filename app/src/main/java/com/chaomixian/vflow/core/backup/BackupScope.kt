// 文件: main/java/com/chaomixian/vflow/core/backup/BackupScope.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.security.AesGcmEngine
import com.chaomixian.vflow.core.security.JvmAesGcmEngine
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject

/**
 * 备份范围的**分组**，用于导出 UI 的视觉归组与「默认勾选」策略的依据。
 *
 * - [USER_CONTENT] —— 用户自己的数据（工作流、文件夹、全局变量）。丢了没法重造。
 * - [CONFIG] —— 可重配的设置（外观、AI 模型参数等）。
 * - [SECRET] —— 含明文密钥的数据。**只在勾选「包含密钥」且提供了口令时才写**，
 *   且写入前必须逐字段加密（T2 落地）。
 */
enum class ScopeGroup { USER_CONTENT, CONFIG, SECRET }

/**
 * 导入模式。
 *
 * ⚠️ **REPLACE 是破坏性操作** —— 它真的会删掉本地「不在备份里」的数据，
 * 而本项目**没有版本历史、没有撤销**（工作流只存在 `SharedPreferences`）。
 * 调用方必须向用户二次确认。见 `WorkflowManager.replaceAllWorkflows` 的 KDoc。
 */
enum class ImportMode { MERGE, REPLACE }

/** 单个 scope 的一次导入结果状态。 */
enum class ImportStatus {
    /** 成功写入（可能 [ScopeImportResult.skipped] > 0，那是**部分**跳过）。 */
    IMPORTED,

    /** `payload == null` —— 本次没勾选这个范围（或调用方判定「不适用」）。 */
    SKIPPED_NOT_SELECTED,

    /** 本机注册了这个 scope，但备份信封里没有它（旧备份、或导出时未勾选）。 */
    SKIPPED_MISSING,

    /** 备份信封里有这个 id，但本机**没注册**（备份来自更新的版本，带了本机不认识的范围）。 */
    SKIPPED_UNKNOWN,

    /** 这个 scope 自己在导入时抛了异常。**只影响它自己**，后续 scope 继续跑。 */
    FAILED
}

/**
 * 一次 scope 的导出结果。
 *
 * ⚠️⚠️ **[count] / [data] 一律非空**：空数据集必须是 `ScopePayload(0, JsonArray())`，
 * **绝不能是 null** —— 那会让用户以为「备了」，实际什么都没备（静默失效点 12）。
 * 「本次不适用（未勾选）」只由 [BackupScope.export] 返回 **null** 表达，两者语义不同。
 */
data class ScopePayload(
    val count: Int,
    val data: JsonElement,
    /**
     * 本次导出中被清洗掉的敏感字段位置（如 `s1.api_key`）。
     *
     * ⚠️⚠️ **它不参与信封往返** —— `BackupEnvelope.write` 只写 `count`/`data`，
     * `readScopes` 也只读这两个。所以本字段是一个**只在内存里活一次**的载体：
     * `exportAll` 返回时读走、`write` 时作为独立参数传入、读完即弃
     * （方案 §6.2）。若调用方忘了收集它，信封的 `summary.scrubbedFields`
     * 会恒为空数组 —— 用户于是**永远不会知道密钥被抹掉了**，
     * 那正是「不静默丢弃」这条承诺的反面。
     */
    val scrubbedFields: List<String> = emptyList()
) {
    companion object {
        /** 空数据集的标准构造方式 —— 用它，别手写 `ScopePayload(0, null-ish)`。 */
        fun empty(): ScopePayload = ScopePayload(0, JsonArray())

        /** 从元素列表构造，[count] 与列表长度天然一致（防两处各写一遍漂移）。 */
        fun of(
            elements: List<JsonElement>,
            scrubbedFields: List<String> = emptyList()
        ): ScopePayload = ScopePayload(
            elements.size,
            JsonArray().apply { elements.forEach { add(it) } },
            scrubbedFields
        )
    }
}

/**
 * 单个 scope 的导入结果。
 *
 * [message] 只用于日志/诊断，**不参与任何判断**（与 `CapabilityErrorCode` 的 `detail` 同理）。
 */
data class ScopeImportResult(
    val scopeId: String,
    val status: ImportStatus,
    val imported: Int = 0,
    val skipped: Int = 0,
    val message: String? = null
)

/**
 * 备份口令的载体**兼加密上下文**。
 *
 * `secrets != null` 表示「调用方提供了口令」；此时 [BackupScope.sensitive]
 * 为 true 的 scope 才允许写数据。
 *
 * 用 `CharArray` 而不是 `String` 是刻意的：`String` 在 JVM 里会进字符串常量池、
 * 生命周期不可控，口令是少数值得为此多写几行的地方。
 *
 * ## 派生密钥只做一次
 *
 * PBKDF2 210000 次在桌面 JVM 上约 0.1–0.4 秒。一次导出要加密**很多**字段 ——
 * 逐字段派生会让导出慢到不可接受。故密钥派生存成 `lazy` 字段，
 * **同一个 `SecretContext` 实例内所有字段共用一个派生密钥**。
 *
 * ## salt 不持久化（方案 §6.6）
 *
 * [salt] 默认每次构造都取新的随机值，**只存在于备份文件的 `encryption` 段里**，
 * App 侧不存任何东西。导入时从信封读（[forImport]），因此
 * **同一口令可以解开任意多份 salt 不同的备份**。
 *
 * 副带好处：不同备份的派生密钥不同，避免密钥重用。
 */
class SecretContext(
    val passphrase: CharArray,
    // ↓ 三个带默认值的参数：T1 的 `SecretContext(passphrase)` 仍能编译（只加不改）。
    private val salt: ByteArray = BackupCrypto.newSalt(),
    private val iterations: Int = BackupCrypto.DEFAULT_ITERATIONS,
    private val engine: AesGcmEngine = JvmAesGcmEngine()
) {

    /** 本次上下文使用的 salt（导入侧要把信封里的 salt 灌进来，故需可读）。 */
    val saltForEnvelope: ByteArray get() = salt

    /** 当前上下文的迭代数 —— 断言「默认值确实是 210000」用。 */
    val iterationCount: Int get() = iterations

    /**
     * 派生密钥。`lazy` ⇒ 单个上下文内**只派生一次**。
     *
     * ⚠️ 刻意做成**私有**（它是密钥材料）：外部只能经 [seal] / [openSlot] /
     * [verifier] 这几个语义化出口使用它，看不到字节本身。
     */
    private val derivedKey: ByteArray by lazy {
        BackupCrypto.deriveKey(passphrase, salt, iterations)
    }

    /** 加密一个字符串，产出 `$enc` 信封节点。 */
    fun seal(plaintext: String): JsonObject = SecretEnvelope.wrap(this, plaintext)

    /** 解开一个 `$enc` 节点。解不开抛 `AeadFailure`（调用方归入 CORRUPTED）。 */
    fun openSlot(node: JsonElement): String = SecretEnvelope.unwrap(this, node)

    /** [SecretEnvelope] 的底层出口 —— 不对外，避免密钥材料扩散。 */
    internal fun sealBytes(plaintext: ByteArray, aad: ByteArray?): ByteArray =
        engine.seal(derivedKey, plaintext, aad)

    internal fun openBytes(data: ByteArray, aad: ByteArray?): ByteArray =
        engine.open(derivedKey, data, aad)

    /**
     * **用派生密钥加密的已知常量** —— 导入时先验它再解字段。
     *
     * 解得开 ⇒ 口令对；解不开 ⇒ 口令错（前提是 [EncryptionSection.verifierHash] 相符，
     * 那条判据在 [BackupCrypto.judge] 里）。
     *
     * ⚠️⚠️ **必须缓存**：`seal` 每次都会取**新的随机 IV**，所以两次调用会产出
     * **两个不同的密文**。而 `BackupPipeline` 要用它同时填 `verifier` 字段
     * **并**算 `verifierHash` —— 若两次取值不同，hash 对应的就不是写进信封的那串，
     * 于是**每次导入都判「文件已损坏」**，而导出一侧毫无异样。
     * 缓存让「取两次」与「取一次」等价，从根上消除这个陷阱。
     * （`sectionOf` 里也刻意只取一次，那是第二道保险。）
     */
    val verifier: String by lazy {
        BackupCrypto.base64(sealBytes(BackupCrypto.VERIFIER_PLAINTEXT.toByteArray(Charsets.UTF_8), null))
    }

    companion object {
        /**
         * 导入路径：salt / iterations **一律从信封读**，不用默认值。
         *
         * ⚠️ 这是「同一口令能解开旧备份」的唯一依据 —— 若这里误用默认值，
         * 备份文件里记录的迭代数/盐就白存了，且**没有任何报错**：
         * 解密会以 `AEADBadTagException` 失败，而那被上层判成「口令错」——
         * 用户会去反复重输一个**本来就对**的口令。
         *
         * @throws IllegalArgumentException salt 不是合法 base64。
         *   调用方（`BackupPipeline`）应在此之前先跑 [BackupCrypto.judge] 拿到
         *   更准确的 [BackupCrypto.Failure]，不要把这个异常直接暴露给用户。
         */
        fun forImport(
            passphrase: CharArray,
            section: EncryptionSection,
            engine: AesGcmEngine = JvmAesGcmEngine()
        ): SecretContext = SecretContext(
            passphrase = passphrase,
            salt = BackupCrypto.base64OrNull(section.salt)
                ?: throw IllegalArgumentException("encryption.salt 不是合法 base64"),
            iterations = section.iterations,
            engine = engine
        )
    }
}

/**
 * 一个**可备份范围**的统一接口。整个备份体系的地基。
 *
 * ## 设计要点
 *
 * 1. **`export` 与 `import` 在同一个实现里** —— 结构上不可能出现「只注册了导出、
 *    忘了写导入」这种半边实现。
 * 2. **每个 scope 自己知道怎么读写自己的数据**，通过 [BackupEnvironment] 这个
 *    Android-free 接缝 —— 因此全部 scope 都能跑纯 JVM 单测。
 * 3. **注册表 → UI 勾选清单 → 导入分发** 三处都从同一个注册表派生，
 *    新增一类数据 = 加一个 scope 文件 + 一行 `register`（防漂移，静默失效点 13）。
 *
 * 实现类放在 `core/backup/scopes/` 下，必须是**纯 JVM**（不得 import `android.*`），
 * 由 `BackupPurityTest` 机器化保证。
 */
interface BackupScope {
    /** 稳定 id，会落进备份 JSON 的 `scopes` 键。**一经发布不要改**（改了旧备份就认不出）。 */
    val id: String

    val group: ScopeGroup

    /**
     * true ⇒ 只在「勾选包含密钥」**且**提供了口令时才写数据。
     * T1 的三个 scope（folders / workflows / global_variables）均为 false。
     */
    val sensitive: Boolean

    /** 导出 UI 首次打开时默认是否勾选。 */
    val defaultIncluded: Boolean

    /**
     * 导入顺序的**建议值**（小者先）。真正的顺序由 `BackupScopeRegistry.importOrder()`
     * 按 [dependsOn] 拓扑排序后决定 —— 它只在「依赖已就绪的候选」之间做 tie-break。
     */
    val importOrder: Int

    /** 必须先于本 scope 导入的 scope id 列表。 */
    val dependsOn: List<String>

    /**
     * 导出。
     *
     * @return **null 仅表示「本次不适用」**（未勾选 / 无口令 / sensitive 但没给口令）。
     *   **空数据集必须返回 `ScopePayload(0, JsonArray())`，绝不能返回 null** ——
     *   两者混同会让用户以为「备了」而实际什么都没备。
     */
    fun export(env: BackupEnvironment, secrets: SecretContext?): ScopePayload?

    /**
     * 导入。
     *
     * @param payload 信封里属于本 scope 的数据；**null 表示本次没导入这个范围**
     *   （不勾选 / 备份里没有），实现应立即返回 [ImportStatus.SKIPPED_NOT_SELECTED]
     *   且**不碰 `env`**。
     */
    fun import(env: BackupEnvironment, payload: ScopePayload?, mode: ImportMode): ScopeImportResult
}
