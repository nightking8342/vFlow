package com.chaomixian.vflow.ui.chat

import com.chaomixian.vflow.core.logging.DebugLogger
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources

/**
 * SSE 读取层（`chat-streaming-design.md` §4.2）。把一条 HTTP 长连接变成 [SseFrame] 流。
 *
 * ## 为什么不手写分帧
 *
 * 自己 `split("\n\n")` 会在这些地方出错，且**都是静默的**：
 * 分片边界恰好落在一个事件中间、UTF-8 多字节序列被切成两半、CRLF 与裸 LF 混用、
 * 前导 BOM、一个事件有多个 `data:` 行（应拼接而非各算一个）。
 * 故交给 okhttp-sse 的 [EventSources]。
 *
 * ## ⚠️ 三个终止回调都必须结束这条流（B2）
 *
 * [EventSourceListener] 有**两个**终止入口，加上正常收尾共三种：
 * [EventSourceListener.onClosed] / [EventSourceListener.onFailure] / 显式取消。
 * 只处理 `onClosed` 会让失败路径**永久挂起**：
 * `callbackFlow` 的 channel 不关闭 ⇒ 收集方永远等 ⇒ `awaitClose` 也永不执行。
 * 而失败时用户看到的是**一直转圈、没有报错、没有日志、没有超时**
 * ——本项目的 OkHttp 没有 `callTimeout`（`ChatCompletionClient.kt:169-175`），
 * `readTimeout` 也救不了（此时响应头已到、连接是健康的，只是没有事件）。
 *
 * ⇒ `onFailure` 里**必须** `close(...)`，这就是 [SseFrame.Closed] 存在的意义：
 * 用一个显式事件承载「流以非正常方式结束」，让上层能统一处理。
 *
 * ## 线程
 *
 * 回调由 OkHttp 的读线程触发，`trySendBlocking` 保证不丢帧；
 * 组装与解析用 [flowOn] 挪到 IO 线程，**不会**占用 UI 线程。
 */
/**
 * 传输层失败分类（`chat-stream-recovery-design.md` §4.2）。
 * 前三个都源自 [SocketTimeoutException] 且**都可重试**（v1.2 / D13），第四个是其余全部情形。
 */
internal enum class SseFailureCause {
    /**
     * 压根没拿到响应：`response == null`。
     * 同时覆盖「TCP 建连阶段超时」与「连上了、但响应头一直没来」（缓冲型网关/反代常见）。
     * ⚠️ v1.2 起**不再叫 `CONNECT_TIMEOUT`**（那个名字只描述了前一半），且**可重试**。
     */
    NO_RESPONSE_TIMEOUT,

    /** 响应头已到、但一个 SSE 事件都还没收到。 */
    FIRST_BYTE_TIMEOUT,

    /** 已经收到过事件之后空闲超时。 */
    IDLE_TIMEOUT,

    /** 非超时失败（非 2xx、content-type 不对、连接被掐断……）。 */
    TRANSPORT,
}

internal object ChatSse {

    private const val LOG_TAG = "ChatSse"

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val sseMediaType = "text/event-stream".toMediaType()

    /**
     * 一条 SSE 帧。
     *
     * ⚠️ 用事件而非抛异常表达「失败」，是为了让**失败原因能被分类**：
     * HTTP 层失败（响应体是 JSON 错误体）与网络层失败的处理方式不同——
     * 前者要**读响应体**提取服务端原文（复用 `extractServiceError`），
     * 后者只有 `IOException` 的 message。
     */
    internal sealed interface SseFrame {
        /** 一个 `data:` 载荷。`type` 是 SSE 的 `event:` 字段（Anthropic 用它；OpenAI 不用）。 */
        data class Data(val data: String, val type: String?) : SseFrame

        /**
         * 流**正常**结束（`onClosed`）。
         *
         * ⚠️ 注意这里**不做**「是否见过结束标记」的校验：OpenAI 的 `[DONE]`
         * 与 Anthropic 的 `message_stop` 由 [ChatStreamAssembler] 层判断，
         * 因为只有它知道协议。本层的职责仅限于传输。
         */
        data object EndOfStream : SseFrame

        /**
         * 流**异常**结束。
         *
         * @param httpCode 非 null 表示**响应层**失败（非 2xx，或 content-type 不是 SSE）；
         *   此时 [message] 已是**服务端错误体里的原文**（尽力提取），可直接展示给用户。
         * @param cause 传输层失败分类（§4.2），带默认值 ⇒ 既有构造点不必改。
         */
        data class Failure(
            val message: String,
            val httpCode: Int? = null,
            val cause: SseFailureCause = SseFailureCause.TRANSPORT,
        ) : SseFrame
    }

    /**
     * 发起请求并把响应体读成 [SseFrame] 流。
     *
     * @param request 已含 `Accept: text/event-stream` 的完整请求头（由调用方构造，见 `buildStreamHeaders`）。
     * @param timeouts 连接/读取超时（唯一真值来源 [ChatStreamTimeouts]）。带默认值 ⇒ 既有调用点/测试不必改。
     * @throws IOException 仅在建连阶段（`newEventSource` 同步失败时）。
     */
    fun frames(
        request: Request,
        timeouts: ChatStreamTimeouts = ChatStreamTimeouts.DEFAULT,
    ): Flow<SseFrame> = callbackFlow {
        val startedAt = System.currentTimeMillis()
        var sawFirstEvent = false          // onEvent 里置 true（唯一写入点）
        val listener = object : EventSourceListener() {
            override fun onEvent(
                eventSource: EventSource,
                id: String?,
                type: String?,
                data: String,
            ) {
                sawFirstEvent = true
                // ⚠️ 用 trySendBlocking 而非 trySend：channel 缓冲满时，
                // trySend 会**静默丢弃**这一帧——表现为「回答少了一段」而不报错。
                trySendBlocking(SseFrame.Data(data = data, type = type))
            }

            override fun onClosed(eventSource: EventSource) {
                trySendBlocking(SseFrame.EndOfStream)
                close()
            }

            override fun onFailure(
                eventSource: EventSource,
                t: Throwable?,
                response: Response?,
            ) {
                // ⚠️⚠️ B2 的落点：这里**必须** close()，否则整条 Flow 永不结束。
                val code = response?.code
                val detail = when {
                    t != null -> t.message?.takeIf { it.isNotBlank() } ?: t::class.java.simpleName
                    else -> readErrorBody(response) ?: "HTTP $code"
                }
                // ⚠️ 分类只看两个事实：是否 SocketTimeoutException、以及是否已见过事件。
                // 判据必须是**字节级**（收到任意事件即算活跃）——keep-alive 注释行不算事件，
                // 但它保持连接不空闲，故 readTimeout 不会触发、自然不会走到这里（见 §6.1 用例③）。
                val cause = when {
                    t is SocketTimeoutException && response == null -> SseFailureCause.NO_RESPONSE_TIMEOUT
                    t is SocketTimeoutException && !sawFirstEvent -> SseFailureCause.FIRST_BYTE_TIMEOUT
                    t is SocketTimeoutException && sawFirstEvent -> SseFailureCause.IDLE_TIMEOUT
                    else -> SseFailureCause.TRANSPORT
                }
                DebugLogger.w(
                    LOG_TAG,
                    "SSE failed code=${code ?: -1} cause=$cause elapsedMs=${System.currentTimeMillis() - startedAt} detail=$detail",
                )
                trySendBlocking(SseFrame.Failure(message = detail, httpCode = code, cause = cause))
                close()
            }
        }

        val source = EventSources.createFactory(timeouts.toClient()).newEventSource(request, listener)
        awaitClose {
            // ⚠️ 取消连接用 `EventSource.cancel()`，**不是** `Call.cancel()`——
            // 这条路径拿不到 Call 对象。不取消的话，用户点「停止」后连接仍会占着
            // 直到 120s readTimeout，表现为「停止后仍持续计费/占连接」。
            source.cancel()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * 从失败的响应里尽力取出**服务端错误原文**。
     *
     * ⚠️ **必须读完就关**（[Response.close]），否则连接泄漏。
     * ⚠️ 有些情况下响应体已被 okhttp-sse 消费/关闭（待 P3 真机确认），
     * 此时读不到内容——故返回 null 时由调用方回退成 `HTTP <code>`。
     * 这不影响「失败必须 close()」的结论，只影响错误文案的丰富度。
     */
    private fun readErrorBody(response: Response?): String? {
        val body = response?.body ?: return null
        val raw = runCatching { body.string() }.getOrNull()?.trim()
        if (raw.isNullOrBlank()) return null
        // 服务端错误体是 JSON（不是 SSE），复用与非流式路径相同的提取口径
        val root = runCatching { json.parseToJsonElement(raw) }.getOrNull() as? JsonObject
        if (root == null) return raw.take(300)
        return extractServiceError(root) ?: raw.take(300)
    }

    /**
     * 与非流式路径（`ChatCompletionClient.extractServiceError`）**同一口径**的
     * 服务端错误提取。
     *
     * ⚠️ 刻意不改成「共享顶层函数」：那处的版本是 `private`，且**同时**被 4 个调用点使用；
     * 本层只需要它的一小部分行为。两边若将来需要同步，以 `ChatCompletionClient` 为准
     * ——它的覆盖面更全（含 `type == "error"` 的顶层形态）。
     */
    private fun extractServiceError(root: JsonObject): String? {
        val error = root["error"]
        if (error != null && error !is JsonNull) {
            val message = error.jsonObjectOrNull()?.let { obj ->
                obj["message"]?.jsonPrimitive?.contentOrNull
                    ?: obj["msg"]?.jsonPrimitive?.contentOrNull
                    ?: obj["detail"]?.jsonPrimitive?.contentOrNull
            }
            val type = error.jsonObjectOrNull()?.get("type")?.jsonPrimitive?.contentOrNull
            if (!message.isNullOrBlank()) {
                return if (type.isNullOrBlank()) message else "$message [$type]"
            }
            if (!type.isNullOrBlank()) return type
            error.jsonPrimitiveOrNull()?.contentOrNull?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return root["message"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: root["msg"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
        runCatching { jsonObject }.getOrNull()

    private fun kotlinx.serialization.json.JsonElement.jsonPrimitiveOrNull() =
        runCatching { jsonPrimitive }.getOrNull()

    /** 供测试构造请求头用（生产路径由 `ChatCompletionClient.buildStreamHeaders` 构造）。 */
    internal fun sseAcceptType() = sseMediaType
}
