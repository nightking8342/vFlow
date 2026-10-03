// 文件: main/java/com/chaomixian/vflow/core/backup/SecretEnvelope.kt
package com.chaomixian.vflow.core.backup

import com.chaomixian.vflow.core.security.AesGcmEngine
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/**
 * **字段级**加密信封：`{"$enc":"aes-gcm-v1","data":"b64(iv‖ct‖tag)"}`。
 *
 * ## 为什么逐字段加密，而不是把整段 JSON 加密
 *
 * 整段加密（例如把整个 `workflows` payload 变成一个 base64 blob）看起来更简单，
 * 但代价是：**加密后的备份完全不可读**。而备份文件是需要人审阅的产物 ——
 * 用户可能想确认「我这次到底备了哪些工作流」「某个字段有没有进去」。
 * 逐字段加密保留了信封结构（哪些 scope、多少个、有没有加密段），
 * 只把凭证值本身变成密文。
 *
 * 附带好处：**损坏面小**。一个字段的密文坏了，其余字段照样能解
 * （虽然本项目在导入时选的是「一处坏 ⇒ 整体判损坏」，但那是**策略**选择，
 * 数据结构层面保留了更细的信息）。
 *
 * ## 与 Gson 的关系
 *
 * 本对象**只操作 `JsonElement` 树**，不做任何 `fromJson` 反序列化 ——
 * `$enc` 节点必须在「变成对象」之前就被解开，否则它会作为
 * `LinkedTreeMap` 混进 `ActionStep.parameters` 的 `Map<String, Any?>` 里，
 * 模块拿到的就不是字符串而是 map，**行为错乱且零报错**（方案 §6.7）。
 */
object SecretEnvelope {

    /** 标记的键名 / 值 —— **引用 `BackupCrypto` 的常量，不各写字面量**（方案 §6.7 防线 1）。 */
    const val MARKER_KEY: String = BackupCrypto.MARKER_KEY

    const val MARKER_VALUE: String = BackupCrypto.MARKER_VALUE

    /**
     * 判断一个节点是不是本项目的密文信封。
     *
     * ⚠️ **只认精确形状**：对象、`$enc` 字段等于 `aes-gcm-v1`、`data` 是字符串。
     * 不认「任意对象」—— 否则用户的**字典类型参数**
     * （`{"type":"vflow.type.dictionary","value":{…}}`，见 `VObjectGsonAdapter`）
     * 会被误当密文去解，报出假的「损坏」。
     *
     * 正因为这条判据严格，`判断` 与 `解密` 可以分开：调用方先 `isWrapped`
     * 决定要不要解，`unwrap` 只在确认是信封后调用。
     */
    fun isWrapped(node: JsonElement?): Boolean {
        val obj = node?.takeIf { it.isJsonObject }?.asJsonObject ?: return false
        val marker = obj.get(MARKER_KEY)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString ?: return false
        if (marker != MARKER_VALUE) return false
        return unwrapData(obj) != null
    }

    /**
     * 取出 base64 密文体。
     *
     * 形状不对（缺 `$enc` / 标记值不是 `aes-gcm-v1` / `data` 不是字符串）
     * ⇒ **null（不抛）**。调用方据此判「这不是信封」，而不是崩掉。
     */
    fun unwrapData(node: JsonElement?): String? {
        val obj = node?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val marker = obj.get(MARKER_KEY)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
        if (marker != MARKER_VALUE) return null
        return obj.get(DataKey)
            ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }
            ?.asString
    }

    /**
     * 加密一个字符串值，产出信封对象。
     *
     * @param aad 关联数据。传 null（当前生产路径）时行为与不传完全一致；
     *   保留它是因为 `AesGcmEngine` 支持，将来若要「把密文绑定到它所在的位置」
     *   （防止攻击者把 A 字段的密文搬到 B 字段）只需在这里传入槽位名。
     */
    fun wrap(ctx: SecretContext, plaintext: String, aad: ByteArray? = null): JsonObject {
        val sealed = ctx.sealBytes(plaintext.toByteArray(Charsets.UTF_8), aad)
        return JsonObject().apply {
            addProperty(MARKER_KEY, MARKER_VALUE)
            addProperty(DataKey, BackupCrypto.base64(sealed))
        }
    }

    /**
     * 解开一个信封。
     *
     * @throws com.chaomixian.vflow.core.security.AeadFailure
     *   **一切**解不开的情形（形状不对 / base64 非法 / tag 不符）。
     *   调用方把它归入 [BackupCrypto.Failure.CORRUPTED] —— 因为走到这里
     *   说明 verifier 已经通过、口令是对的，那么解不开就只能是数据被改过。
     */
    fun unwrap(ctx: SecretContext, node: JsonElement): String {
        val data = unwrapData(node)
            ?: throw com.chaomixian.vflow.core.security.AeadFailure(
                "不是有效的加密字段（形状不匹配）"
            )
        val sealed = BackupCrypto.base64OrNull(data)
            ?: throw com.chaomixian.vflow.core.security.AeadFailure(
                "加密字段的 data 不是合法 base64"
            )
        return ctx.openBytes(sealed, aad = null).toString(Charsets.UTF_8)
    }

    private const val DataKey = "data"

    /** 便于测试与调用方构造一个「已知不是信封」的普通值节点。 */
    fun plain(value: String): JsonElement = JsonPrimitive(value)
}
