// 文件: main/java/com/chaomixian/vflow/core/backup/BackupPipeline.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.security.AeadFailure
import com.chaomixian.vflow.core.security.JvmAesGcmEngine
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive

/**
 * 备份导出/导入的**编排层** —— T5（UI）与模块层只跟本对象打交道。
 *
 * ## 它比 `BackupScopeRegistry` 多做了什么
 *
 * 注册表只管「把勾选的 scope 挨个导出/导入」。但**口令**这件事横跨两者：
 * 导出时要把 `SecretContext` 里的 salt/iterations/verifier 落进信封，
 * 导入时要先用它们验证口令、再解开密文、最后才让 scope 去消费。
 *
 * 这些步骤不属于任何单个 scope（`SecretsScope` 只管自己那个 payload 的加解密，
 * 不知道信封长什么样）。放在这里，scope 就完全不需要知道「加密段」的存在。
 */
object BackupPipeline {

    /** 导出结果。[scrubbedFields] 需由调用方展示给用户（不静默丢弃）。 */
    data class ExportResult(
        val text: String,
        val scrubbedFields: List<String>
    )

    sealed interface ImportOutcome {
        /** 成功。[results] 逐 scope 的导入结果（含被跳过的）。 */
        data class Done(
            val header: BackupEnvelope.Header,
            val results: List<ScopeImportResult>
        ) : ImportOutcome

        /** 口令不对。**只有这一支该提示用户重输口令。** */
        data object WrongPassphrase : ImportOutcome

        /** 密文/信封被改动（含 verifier 哈希不符）。 */
        data class Corrupted(val reason: String) : ImportOutcome

        /**
         * 备份含加密段，但调用方没给口令 ⇒ 加密的 scope 被跳过、其余照常导入。
         *
         * ⚠️ 这是**部分成功**而不是失败：用户可能只想恢复工作流，密钥以后再说。
         * 判成失败会让「不含密钥的恢复」这条路彻底走不通。
         */
        data class PassphraseRequired(
            val header: BackupEnvelope.Header,
            val results: List<ScopeImportResult>,
            /** 因缺口令而被跳过的 scope id（供 UI 明确告知，不静默）。 */
            val skippedScopes: List<String>
        ) : ImportOutcome

        /** `TooNew` / `Invalid` —— 整份拒绝，**不半解析**。 */
        data class Rejected(val reason: String) : ImportOutcome
    }

    /** 审计用的算法标识。解密路径写死用 GCM，本字段仅供参考/展示。 */
    const val ALGORITHM = "AES-256-GCM"

    /**
     * 导出。
     *
     * @param selected 本次勾选的 scope id。
     * @param secrets 口令上下文。**null ⇒ 无口令** ⇒ `sensitive` 的 scope 不写，
     *   且工作流里的凭证会被**清洗**（清成 `""` 并记入 `summary.scrubbedFields`）。
     */
    fun export(
        env: BackupEnvironment,
        selected: Set<String>,
        secrets: SecretContext?,
        createdAt: Long = System.currentTimeMillis()
    ): ExportResult {
        val payloads = BackupScopeRegistry.exportAll(env, selected, secrets)

        // ⚠️⚠️ **必须在 write 之前收集** —— `ScopePayload.scrubbedFields` 不参与
        //    信封往返（write 只写 count/data）。忘了这一步，summary.scrubbedFields
        //    会恒为 `[]`，用户永远不会知道密钥被抹掉了（方案 §6.2，反证 R6 锁住）。
        val scrubbed = payloads.values.flatMap { it.scrubbedFields }

        // 加密段**只在真的加密了东西时**才写。判据是「有没有 payload 是 $enc 节点」，
        // 而不是「用户给没给口令」—— 给了口令但没勾 secrets scope 时信封里其实
        // 一个密文都没有，此时写个 encryption 段会让导入端无谓地要求口令
        // （而那份备份本来不需要口令就能完整恢复）。
        val anySealed = payloads.values.any {
            SecretEnvelope.isWrapped(it.data) || containsSealedNested(it.data)
        }
        val section = if (secrets != null && anySealed) sectionOf(secrets) else null

        val included = payloads.keys.toList()
        val excluded = BackupScopeRegistry.all()
            .map { it.id }
            .filter { it !in included }

        val text = BackupEnvelope.write(
            json = env.json,
            sections = payloads,
            includedScopes = included,
            excludedScopes = excluded,
            scrubbedFields = scrubbed,
            appVersionName = env.appVersionName,
            appVersionCode = env.appVersionCode,
            createdAt = createdAt,
            encryption = section
        )
        return ExportResult(text, scrubbed)
    }

    /**
     * 导入。
     *
     * ⚠️ **Legacy 路径不做任何解密尝试**（方案 §6.10）：`LegacyBackupAdapter`
     * 只认顶层 `workflows`/`folders`，那种备份里**根本不含密文**。
     * 对它跑 verifier 会给一份好端端的旧备份报「口令错误」。
     *
     * @param mode MERGE / REPLACE。REPLACE 在 scope 层是真删（破坏性，调用方须二次确认）。
     * @param secrets 口令。null ⇒ 含密文的 scope 被跳过（返回 [ImportOutcome.PassphraseRequired]）。
     */
    fun import(
        env: BackupEnvironment,
        text: String,
        mode: ImportMode,
        secrets: SecretContext?
    ): ImportOutcome {
        return when (val read = BackupEnvelope.read(env.json, text)) {
            is BackupEnvelope.ReadResult.Legacy -> {
                // 旧备份：转成新形状的 payload 后按常规分发。**不试解密**。
                val payloads = LegacyBackupAdapter(env.json).adapt(read.root)
                ImportOutcome.Done(
                    header = BackupEnvelope.Header(
                        schemaVersion = 0,
                        createdAt = 0L,
                        appVersionName = null,
                        appVersionCode = null
                    ),
                    results = BackupScopeRegistry.importAll(env, payloads, mode)
                )
            }

            is BackupEnvelope.ReadResult.TooNew -> ImportOutcome.Rejected(
                "备份来自更新的版本（schemaVersion=${read.schemaVersion}），请先升级 App 再恢复"
            )

            is BackupEnvelope.ReadResult.Invalid -> ImportOutcome.Rejected(
                "不是有效的 vFlow 备份：${read.reason}"
            )

            is BackupEnvelope.ReadResult.Modern -> importModern(env, read, mode, secrets)
        }
    }

    private fun importModern(
        env: BackupEnvironment,
        read: BackupEnvelope.ReadResult.Modern,
        mode: ImportMode,
        secrets: SecretContext?
    ): ImportOutcome {
        val section = read.encryption
        val sealedIds = read.scopes.filterValues { containsSealedNested(it.data) }.keys

        // 这份备份没有加密段。
        if (section == null) {
            // 却含密文 ⇒ 信封被改过。这是**损坏**，不是口令问题。
            // 不拦这一支的话，那些 $enc 对象会原样落进 parameters，
            // 模块拿到的是 map 而不是字符串（静默错乱）。
            if (sealedIds.isNotEmpty()) {
                return ImportOutcome.Corrupted(
                    "备份含加密字段但缺少 encryption 段（文件可能被修改过）"
                )
            }
            return ImportOutcome.Done(
                read.header, BackupScopeRegistry.importAll(env, read.scopes, mode)
            )
        }

        // 有加密段但没口令 ⇒ 剔除加密 scope，其余照常导入（部分成功）。
        if (secrets == null) {
            val plain = read.scopes.filterKeys { it !in sealedIds }
            return ImportOutcome.PassphraseRequired(
                header = read.header,
                results = BackupScopeRegistry.importAll(env, plain, mode),
                skippedScopes = sealedIds.toList()
            )
        }

        // 先验口令 —— **这一步必须早于任何解密**。
        when (BackupCrypto.judge(secrets.passphrase, section)) {
            BackupCrypto.Failure.WRONG_PASSPHRASE -> return ImportOutcome.WrongPassphrase
            BackupCrypto.Failure.CORRUPTED -> return ImportOutcome.Corrupted(
                "加密段完整性校验失败（文件已损坏或被修改）"
            )
            null -> Unit // 通过
        }

        // 用**信封里的** salt / iterations 重建上下文（不是默认值）。
        // 失败 ⇒ judge 通过但参数仍不可用（极罕见），归为损坏而不是口令错 ——
        // 因为用户重输口令解决不了它。
        val importCtx = try {
            SecretContext.forImport(secrets.passphrase, section, JvmAesGcmEngine())
        } catch (e: IllegalArgumentException) {
            return ImportOutcome.Corrupted(e.message ?: "加密段参数不合法")
        }

        // 逐 payload 解密，把 $enc 节点**就地**换成明文。
        val decrypted = LinkedHashMap<String, ScopePayload>()
        for ((id, payload) in read.scopes) {
            val plain = try {
                decryptPayload(payload.data, importCtx)
            } catch (e: AeadFailure) {
                // ⚠️ 走到这里说明 **verifier 已通过、口令是对的**，那么解不开
                //    就只能是数据被改过 ⇒ 损坏。判成口令错会让用户反复重输
                //    一个本来就对的口令（这正是 §6.3 要防的误导）。
                return ImportOutcome.Corrupted("字段解密失败：${e.message}")
            }
            decrypted[id] = ScopePayload(payload.count, plain)
        }

        return ImportOutcome.Done(
            read.header, BackupScopeRegistry.importAll(env, decrypted, mode)
        )
    }

    /**
     * 解密一个 scope 的 payload。
     *
     * ## ⚠️ 同一个 `$enc` 形状承载**两种语义的明文**，必须分开处理
     *
     * | 生产者 | `$enc` 在哪 | 明文是什么 |
     * |---|---|---|
     * | `SecretsScope` | **整个 payload 就是一个 `$enc` 节点** | 一个 **JSON 对象**的文本 |
     * | `SecretFieldScrubber.encryptInPlace` | 嵌在 `steps[].parameters` 的**值**里 | 一个**裸字符串**（如 `sk-…`） |
     *
     * 若统一按「解出来的一定是 JSON」处理，第二种会被
     * `JsonParser.parseString("sk-…")` 解析失败 —— 而那会**报成数据损坏**，
     * 于是一个完好的备份被拒绝导入。
     *
     * ⇒ 判据按**位置**分：
     * - **顶层就是信封** ⇒ 明文是 JSON，解析后再递归（防深层还有信封）；
     * - **其余位置**（数组/对象内嵌）⇒ 明文是**裸字符串**，产出 `JsonPrimitive`。
     *
     * 这个不对称由两端生产者的写法决定，不是本函数的自由选择。
     */
    private fun decryptPayload(data: JsonElement, ctx: SecretContext): JsonElement {
        if (!SecretEnvelope.isWrapped(data)) {
            return unwrapNested(data, ctx)
        }
        val parsed = try {
            JsonParser.parseString(SecretEnvelope.unwrap(ctx, data))
        } catch (e: Exception) {
            // 顶层信封里装的必须是 JSON 文本（`SecretsScope` 的契约）。
            // 不是 ⇒ 内容被改过。
            throw AeadFailure("解密后的内容不是合法 JSON：${e.message}", e)
        }
        return unwrapNested(parsed, ctx)
    }

    /**
     * 递归把**嵌套**的 `$enc` 节点换成明文**字符串**。
     *
     * ⚠️ 产出 `JsonPrimitive(string)` 而不是解析成 JSON —— 理由见 [decryptPayload]。
     * 若将来有 scope 把「加密的 JSON 对象」嵌在深层，那时要给它一个**显式标记**，
     * **不要**在这里加「试探性解析」：那会让「值恰好长得像 JSON 的裸字符串」
     * （例如密钥本身就是一段 JSON）被静默改写形状。
     */
    private fun unwrapNested(element: JsonElement, ctx: SecretContext): JsonElement {
        if (SecretEnvelope.isWrapped(element)) {
            return JsonPrimitive(SecretEnvelope.unwrap(ctx, element))
        }
        return when {
            element.isJsonObject -> JsonObject().apply {
                element.asJsonObject.entrySet().forEach { (k, v) -> add(k, unwrapNested(v, ctx)) }
            }
            element.isJsonArray -> JsonArray().apply {
                element.asJsonArray.forEach { add(unwrapNested(it, ctx)) }
            }
            else -> element
        }
    }

    /** 判「这份 payload 里有没有密文」—— 顶层或任意深度。 */
    private fun containsSealedNested(element: JsonElement): Boolean {
        if (SecretEnvelope.isWrapped(element)) return true
        return when {
            element.isJsonObject -> element.asJsonObject.entrySet().any { containsSealedNested(it.value) }
            element.isJsonArray -> element.asJsonArray.any { containsSealedNested(it) }
            else -> false
        }
    }

    /**
     * 构造加密段。
     *
     * ⚠️⚠️ **`secrets.verifier` 只取一次**。它内部是 `lazy` 的（见其 KDoc），
     * 但即便如此这里也只取一次 —— 因为 `verifierHash` 必须对应**写进信封的那一串**。
     * 两处若各取一次而取值不同，hash 就永远匹配不上 ⇒ **每次导入都判「文件已损坏」**，
     * 而导出一侧完全看不出异样。
     *
     * ⚠️ **salt 每次导出随机**（`SecretContext` 的默认参数已给
     * `BackupCrypto.newSalt()`），且**只存在于信封里、App 侧不持久化**（方案 §6.6）。
     * 解密时从信封读 ⇒ 同一口令能解开任意多份 salt 不同的备份。
     */
    private fun sectionOf(secrets: SecretContext): EncryptionSection {
        val verifier = secrets.verifier
        // 哈希对象是 **verifier 密文本身**（base64 解码后的字节），
        // 与 `BackupCrypto.judge` 里的算法严格对应 —— 两处任何一处改了对象，
        // 都会让导入恒判「损坏」。故只引用这一份字符串。
        val verifierBytes = BackupCrypto.base64OrNull(verifier)
            ?: error("verifier 不是合法 base64（不应发生）")

        return EncryptionSection(
            algorithm = ALGORITHM,
            kdf = BackupCrypto.KDF_ALGORITHM,
            iterations = secrets.iterationCount,
            salt = BackupCrypto.base64(secrets.saltForEnvelope),
            verifier = verifier,
            verifierHash = BackupCrypto.sha256Base64(verifierBytes)
        )
    }
}
