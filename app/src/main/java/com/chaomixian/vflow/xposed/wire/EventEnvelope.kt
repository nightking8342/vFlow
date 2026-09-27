package com.chaomixian.vflow.xposed.wire

import org.json.JSONObject

/**
 * Xposed 通道的**统一事件信封** —— 上行（hook 层 → App）的唯一载体。
 *
 * 设计文档：`docs/fork/xposed-channel-design.md` §3.4.2。
 *
 * ## ⚠️⚠️ 本文件是 hook 层与 App 侧**共享的唯一实现**
 *
 * `logcat` 那条链路有两份拷贝（app 侧 + Core 侧），`FORK.md` 明确记着它的代价：
 * 「**任何语义改动必须同时改两处**，且以 app 侧为准；不一致的表现是
 * 『调试工具里看着能匹配的日志，触发器匹配不到』」。
 *
 * 本通道**不重复那个代价**：hook 层与 App 在**同一个 APK、同一个 dex** 里
 * （路线 1 的红利），所以只留一份。
 *
 * ## 🚫 依赖白名单 —— 违反它会扩大崩溃半径
 *
 * 本文件**运行在两个进程里**：App 进程，以及注入在 **system_server** 里的 hook 层。
 * 同一个 dex 里 App 类都在，但**只有被引用才会在 system_server 里被解析加载** ——
 * 一旦它 import 了 App 侧的类，那个类的静态初始化就会在 system_server 里跑。
 *
 * **允许**：`org.json` / `java.*` / `kotlin.*`
 * **禁止**：`android.*`（含 `android.util.Log`）/ Gson / `DebugLogger` /
 *          `com.chaomixian.vflow.core.*` / `com.chaomixian.vflow.services.*`
 *
 * 这条由 `test/.../xposed/WireLayerPurityTest.kt` 以**源码扫描**方式锁住 ——
 * 纯函数测试测不出「引用面污染」（本仓库在 `CoreLauncher` 上踩过同类坑：
 * 13 个纯函数单测全绿，但调用点缺失）。
 *
 * ## 为什么用 JSON 字符串而不是 Parcelable
 *
 * AIDL 面收窄成一个 `String` 参数：加字段天然向后兼容，未知 topic 天然可忽略。
 * 代价是一次多余的序列化 —— 事件频率远低于 binder 事务上限，可接受。
 */
object EventEnvelopeCodec {

    const val KEY_TOPIC = "topic"
    const val KEY_SEQ = "seq"
    const val KEY_TS = "ts"
    const val KEY_PAYLOAD = "payload"
    const val KEY_DROPPED = "dropped"
    const val KEY_TOKEN = "token"
    const val KEY_PROTOCOL = "protocol_version"

    /** 编码。**只加不改不删**是兼容约定（删改要走版本号）。 */
    fun encode(
        topic: String,
        seq: Long,
        ts: Long,
        payloadJson: String,
        droppedCount: Long = 0,
        token: String = "",
        protocolVersion: Int = PROTOCOL_VERSION,
    ): String = JSONObject()
        .put(KEY_TOPIC, topic)
        .put(KEY_SEQ, seq)
        .put(KEY_TS, ts)
        .put(KEY_PAYLOAD, payloadJson)
        .put(KEY_DROPPED, droppedCount)
        .put(KEY_TOKEN, token)
        .put(KEY_PROTOCOL, protocolVersion)
        .toString()

    /**
     * 解码。
     *
     * ⚠️ **任何形态的坏输入都返回 null，绝不抛异常** ——
     * 调用方是 App 侧的 binder 线程，抛异常会跨国界、且没有可用的处理方式。
     * 「未知 topic 必须忽略而非崩溃」是 §3.4.5 的硬要求。
     */
    fun decode(json: String): EventEnvelope? {
        val obj = try {
            JSONObject(json)
        } catch (_: Exception) {
            return null
        }

        val topic = obj.optString(KEY_TOPIC)
        if (topic.isBlank()) return null

        return EventEnvelope(
            topic = topic,
            // seq 缺失时给 -1 而不是 0：0 是一个合法的首个序号，
            // 用 0 会让「缺失」与「第一条」混同，丢包检测失去意义
            seq = if (obj.has(KEY_SEQ)) obj.optLong(KEY_SEQ) else -1L,
            ts = obj.optLong(KEY_TS),
            payloadJson = obj.optString(KEY_PAYLOAD),
            droppedCount = obj.optLong(KEY_DROPPED),
            token = obj.optString(KEY_TOKEN),
            protocolVersion = obj.optInt(KEY_PROTOCOL),
        )
    }

    /** 协议版本。hook 层与 App 生命周期独立 ⇒ 版本错配是常态（§3.4.5）。 */
    const val PROTOCOL_VERSION = 1
}

/**
 * 解码结果。
 *
 * `payloadJson` 保持**原始字符串**，由各 topic 的 schema 自己解析 ——
 * 信封层不认识任何业务字段（「Hook 层不知道工作流的存在」在数据层的体现）。
 */
data class EventEnvelope(
    val topic: String,
    val seq: Long,
    val ts: Long,
    val payloadJson: String,
    val droppedCount: Long,
    val token: String,
    val protocolVersion: Int,
)
