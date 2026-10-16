package com.chaomixian.vflow.ui.chat

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * **三条协议的 content part 形状**（纯函数，6 个）。
 *
 * 三家的差异只在字段名与嵌套层级，语义完全一致；把它们集中在一处
 * （而不是散在三个历史装配函数里）是为了让「协议形状」这件事只有**一个**可核对的地方。
 *
 * | 协议 | 文本 part | 图片 part |
 * |---|---|---|
 * | `chat/completions` | `{"type":"text","text":T}` | `{"type":"image_url","image_url":{"url":"data:<mime>;base64,<b64>"}}` |
 * | Responses | `{"type":"input_text","text":T}` | `{"type":"input_image","image_url":"data:<mime>;base64,<b64>"}` |
 * | Anthropic messages | `{"type":"text","text":T}` | `{"type":"image","source":{"type":"base64","media_type":"<mime>","data":"<b64>"}}` |
 *
 * ⚠️ 三者**不能互串**：`chat/completions` 的 `image_url` 是**对象**、Responses 的是**字符串**，
 * 而 Anthropic 根本不用 `image_url`（它要 `source.media_type` + `source.data`）。
 * 串了不会在本地报错——只会在服务端 400。
 */
internal object ChatImageParts {
    // ── chat/completions ──────────────────────────────────────────

    fun openAiChatTextPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun openAiChatImagePart(payload: ChatImagePayload): JsonObject = buildJsonObject {
        put("type", "image_url")
        put(
            "image_url",
            buildJsonObject {
                put("url", dataUrl(payload))
            },
        )
    }

    // ── OpenAI Responses ─────────────────────────────────────────

    fun responsesTextPart(text: String): JsonObject = buildJsonObject {
        put("type", "input_text")
        put("text", text)
    }

    fun responsesImagePart(payload: ChatImagePayload): JsonObject = buildJsonObject {
        put("type", "input_image")
        put("image_url", dataUrl(payload))
    }

    // ── Anthropic messages ───────────────────────────────────────

    fun anthropicTextPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    fun anthropicImagePart(payload: ChatImagePayload): JsonObject = buildJsonObject {
        put("type", "image")
        put(
            "source",
            buildJsonObject {
                put("type", "base64")
                put("media_type", payload.mimeType)
                put("data", payload.base64)
            },
        )
    }

    private fun dataUrl(payload: ChatImagePayload): String =
        "data:${payload.mimeType};base64,${payload.base64}"
}
