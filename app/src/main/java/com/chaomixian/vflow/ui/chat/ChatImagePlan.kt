package com.chaomixian.vflow.ui.chat

/**
 * 单图 base64 上限（**口径 = base64 字符串长度**，不是原始字节）。
 *
 * 与 ccb 的 `API_IMAGE_MAX_BASE64_SIZE` 同口径。base64 比原始字节大约 4/3，
 * 故 5MB 的 base64 ≈ 3.75MB 的原始图片。**恰好等于上限不缩**。
 */
internal const val CHAT_MAX_IMAGE_BASE64_CHARS = 5 * 1024 * 1024

/**
 * 每请求图片预算（fork：AI 能力统一 §3.7 / D19，**决策已定**）。
 *
 * 两条硬约束来自 D19：
 * 1. **按来源分档**（用户图 / Agent 截图），**不设合计上限**——两档互不影响，
 *    默认 1 + 5 = 6 张可以全部保留；把 [maxAgentImages] 配成 3 时 3 + 5 = 8 张也全部保留。
 * 2. 超预算的图**换文本占位**，**不得静默丢弃**（模型必须知道「这里曾有张图」）。
 *
 * ⚠️ 计数单位是**张数**，不是字节（§3.7 的实测结论：每轮重发的成本是平方级的，
 * 只有张数能约束它）。
 */
internal data class ChatImageBudget(
    /** D19：用户图 5 张。 */
    val maxUserImages: Int = 5,
    /**
     * D19：Agent 截图「最近 1–3 张」⇒ **默认取 1**（父会话 2026-10-16 裁决，取值保持可配）。
     *
     * 理由：Agent **每轮都会重新截图**，「当前屏幕」就是它真正需要的；更早的屏幕
     * 已由**文字节点树**记录在历史里。15 轮任务量级：默认 1 ⇒ 约 2.3 万 token；
     * 默认 3 ⇒ 约 6.8 万 token。
     */
    val maxAgentImages: Int = 1,
) {
    companion object {
        val DEFAULT = ChatImageBudget()

        /** 不裁剪（测试与诊断用；**不要**在生产路径上用它）。 */
        val UNLIMITED = ChatImageBudget(maxUserImages = Int.MAX_VALUE, maxAgentImages = Int.MAX_VALUE)
    }
}

/** 一张图被换掉占位的原因。三档对应三种固定文案。 */
internal enum class ChatImageOmissionReason {
    /** 超出每请求图片预算（按来源分档）。 */
    BUDGET,

    /** 读不到字节（路径失效 / 无权限 / 内容提供者报错）。 */
    UNREADABLE,

    /** 编码后仍超过单图 base64 上限。 */
    TOO_LARGE,
}

/** 一张**真正发给模型**的图：mime + base64（**只存在于本次请求的内存里**）。 */
internal data class ChatImagePayload(
    val mimeType: String,
    val base64: String,
)

/** 单张图在一次请求里的归宿：保留（带载荷）或换成占位文本。 */
internal sealed interface ChatPlannedImage {
    val attachment: ChatImageAttachment

    data class Kept(
        override val attachment: ChatImageAttachment,
        val payload: ChatImagePayload,
    ) : ChatPlannedImage

    data class Omitted(
        override val attachment: ChatImageAttachment,
        val reason: ChatImageOmissionReason,
        val placeholderText: String,
    ) : ChatPlannedImage
}

/**
 * 一次请求的图片计划：**按消息 id 分组**，组内保持消息里的原始顺序。
 *
 * 只包含**有附件的消息**——没有附件的消息查不到条目（[forMessage] 返回空表），
 * 于是装配层可以据此保持「content 仍是字符串」的既有行为。
 */
internal class ChatImagePlan(private val byMessage: Map<String, List<ChatPlannedImage>>) {
    fun forMessage(messageId: String): List<ChatPlannedImage> = byMessage[messageId].orEmpty()

    fun isEmpty(): Boolean = byMessage.isEmpty()

    fun keptCount(): Int = byMessage.values.sumOf { images -> images.count { it is ChatPlannedImage.Kept } }

    fun omittedCount(reason: ChatImageOmissionReason): Int = byMessage.values.sumOf { images ->
        images.count { it is ChatPlannedImage.Omitted && it.reason == reason }
    }

    companion object {
        val EMPTY = ChatImagePlan(emptyMap())
    }
}

/**
 * 超预算 / 读不到 / 超字节的**统一占位文案**（照 dsh `offloadedImageText` 的形态）。
 *
 * 三条设计约束：
 * 1. **面向模型**（英文），且必须说清「图还在历史里 / 怎么才能再看到」——
 *    只说「omitted」会让模型以为图不存在，进而反复要求用户重发。
 * 2. **单条 < 200 字符**：占位文本会进历史、被每轮重发，不能自己变成新负担。
 * 3. **三档固定文案**：不做按工具 / 按场景的特例分支（与 `query_module_schema`
 *    撤掉函数工作流特化同一条原则）。
 */
internal object ChatImagePlaceholder {
    fun text(reason: ChatImageOmissionReason, attachment: ChatImageAttachment): String {
        val identity = identityOf(attachment)
        return when (reason) {
            ChatImageOmissionReason.BUDGET ->
                "[image omitted to fit request image limits; $identity. " +
                    "The image is still part of the conversation history; " +
                    "ask the user to re-attach it if it must be seen again.]"

            ChatImageOmissionReason.TOO_LARGE ->
                "[image omitted: the encoded image exceeds the 5 MB per-image limit; $identity. " +
                    "Ask the user to send a smaller image.]"

            ChatImageOmissionReason.UNREADABLE ->
                "[image omitted: the image could not be read; $identity. " +
                    "Ask the user to re-attach it.]"
        }
    }

    /** `"<label>" (<id>)`；label 为空时退化为 `attachment <id>`（照 dsh 的 `imageIdentity`）。 */
    private fun identityOf(attachment: ChatImageAttachment): String =
        if (attachment.label.isBlank()) {
            "attachment ${attachment.id}"
        } else {
            "\"${attachment.label}\" (${attachment.id})"
        }
}

/** 纯规划器对单张图的判定（**尚未带载荷**——读字节是 [ChatImageResolver] 的事）。 */
internal sealed interface ChatImageDecision {
    val messageId: String
    val attachment: ChatImageAttachment

    data class Keep(
        override val messageId: String,
        override val attachment: ChatImageAttachment,
    ) : ChatImageDecision

    data class Omit(
        override val messageId: String,
        override val attachment: ChatImageAttachment,
        val reason: ChatImageOmissionReason,
        val placeholderText: String,
    ) : ChatImageDecision
}

/**
 * **纯规划器**：只做「保留最近 N 张 / 更旧的换占位」判定，**不读字节、无 IO**。
 *
 * 之所以把这一步与读字节分开：预算是纯函数（可单测、可反证），
 * 而读字节/缩压依赖 Android 与文件系统（单测环境里是 not-mocked 桩）。
 */
internal object ChatImageBudgetPlanner {
    /**
     * @param history 已过滤掉 ERROR 角色的请求历史（**oldest first**）
     * @return 按请求顺序（oldest first）的保留 / 省略判定；[ChatImageDecision.Keep] 项尚未带 payload
     *
     * 计数口径（照 dsh 的 occurrence 口径）：**同一张图出现在多条消息里按多次出现分别计数**。
     * 判据用「出现序号」而不是 attachment id，因为同一条消息里也可能出现两张同 id 的图
     * （例如用户重复发送同一张），按 id 去重会**静默**把它们当成一张。
     */
    fun plan(history: List<ChatMessage>, budget: ChatImageBudget): List<ChatImageDecision> {
        data class Occurrence(val messageId: String, val attachment: ChatImageAttachment)

        val occurrences = history.flatMap { message ->
            message.attachments.map { attachment -> Occurrence(message.id, attachment) }
        }
        if (occurrences.isEmpty()) return emptyList()

        val kept = BooleanArray(occurrences.size)
        ChatImageSource.entries.forEach { source ->
            val limit = when (source) {
                ChatImageSource.USER -> budget.maxUserImages
                ChatImageSource.AGENT -> budget.maxAgentImages
            }.coerceAtLeast(0)
            // 保留**最近**的 limit 张（列表是 oldest first ⇒ 取尾部）
            occurrences.indices
                .filter { occurrences[it].attachment.source == source }
                .takeLast(limit)
                .forEach { kept[it] = true }
        }

        return occurrences.mapIndexed { index, occurrence ->
            if (kept[index]) {
                ChatImageDecision.Keep(occurrence.messageId, occurrence.attachment)
            } else {
                ChatImageDecision.Omit(
                    messageId = occurrence.messageId,
                    attachment = occurrence.attachment,
                    reason = ChatImageOmissionReason.BUDGET,
                    placeholderText = ChatImagePlaceholder.text(
                        ChatImageOmissionReason.BUDGET,
                        occurrence.attachment,
                    ),
                )
            }
        }
    }
}
