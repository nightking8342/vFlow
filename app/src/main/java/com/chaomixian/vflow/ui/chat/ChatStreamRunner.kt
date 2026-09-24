package com.chaomixian.vflow.ui.chat

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * 把 [ChatSse.SseFrame] 流 + [ChatStreamAssembler] 组合成 [ChatStreamEvent] 流
 * （`chat-streaming-design.md` §4.2）。
 *
 * ## 为什么单独一层
 *
 * [ChatSse] 只管传输（分帧、失败、结束），[ChatStreamAssembler] 只管协议语义（归约）。
 * 把它们拼起来时才有三个**跨层**的判定，且三条都是「不报错、只是悄悄不对」：
 *
 * 1. **正常结束必须有结束标记**（F7）。连接干净关闭但**没见过** `[DONE]` / `message_stop`
 *    ⇒ 响应被中途切断，而已收到的内容看起来是完整的。不判这一条，截断会被静默当成正常收尾。
 * 2. **失败必须转成异常**，不能只发一个事件就算了——上层（VM）需要一个「这轮失败了」的信号，
 *    否则会走正常收尾路径，把半截内容当成完整回复落盘。
 * 3. **`Completed` 必须携带 `finish()` 的权威结果**（B4），而不是把累积 delta 回传。
 *
 * 本类无 Android 依赖，可纯 JVM 单测（用假的帧流即可，无需网络）。
 */
internal object ChatStreamRunner {

    /**
     * @param frames 已建连的 SSE 帧流。
     * @param assembler 已按协议构造的归约器。
     * @throws IllegalStateException 流以失败结束，或**未见结束标记就关闭**（截断）。
     */
    fun run(
        frames: Flow<ChatSse.SseFrame>,
        assembler: ChatStreamAssembler,
    ): Flow<ChatStreamEvent> = flow {
        var failure: ChatSse.SseFrame.Failure? = null

        frames.collect { frame ->
            when (frame) {
                is ChatSse.SseFrame.Data -> {
                    // 归约器可能抛错（服务端在流中间报错）——让它冒泡，那就是失败
                    assembler.accept(frame.data, frame.type).forEach { emit(it) }
                }

                is ChatSse.SseFrame.EndOfStream -> {
                    // 记录但**不立即收尾**：等 collect 结束后统一处理，
                    // 这样 Failure 与 EndOfStream 的先后顺序不影响判定。
                }

                is ChatSse.SseFrame.Failure -> failure = frame
            }
        }

        failure?.let { fail ->
            // 服务端的错误原文已在传输层提取（含 HTTP 层失败时读响应体），直接展示
            throw IllegalStateException(fail.message)
        }

        // ⚠️ F7：连接正常关闭 ≠ 本轮正常结束。
        // OpenAI 以 `data: [DONE]` 收尾、Anthropic 以 `message_stop` 收尾；
        // 两者都没出现就说明是**截断**（dsh 的教训：未终止的尾部是截断，不是可 flush 的载荷）。
        if (!assembler.sawTerminator) {
            throw IllegalStateException(
                "连接在收到结束标记前就关闭了，回复可能不完整。"
            )
        }

        // ⚠️ B4：`Completed` 携带的是 `finish()` 的**权威值**（已经过 normalizeAssistantReply），
        // 不是累积的 delta。上层据此落盘，两条路径才逐字段一致。
        emit(ChatStreamEvent.Completed(assembler.finish()))
    }
}
