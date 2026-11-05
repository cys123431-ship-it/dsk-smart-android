package com.dskapp.data.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/**
 * OpenAI-compatible chat completions wire format.
 * Verified against River AI docs (POST https://api.river.ai/v1/chat/completions).
 */
@Serializable
internal data class ChatRequest(
    val model: String,
    val messages: List<ChatRequestMessage>,
    @SerialName("max_tokens") val maxTokens: Int? = null,
    val stream: Boolean = false,
    val temperature: Double? = null,
    @SerialName("stream_options") val streamOptions: StreamOptions? = null
)

/** Asks the server to append a final chunk carrying token usage to a streamed answer. */
@Serializable
internal data class StreamOptions(
    @SerialName("include_usage") val includeUsage: Boolean
)

/**
 * A message's content is a plain string for text-only turns and a list of parts
 * (`[{type:"text"...},{type:"image_url"...}]`) only when images are attached.
 *
 * Why not always a list: River's DeepSeek backend turns list content into its literal JSON
 * text before templating, so the model saw its own earlier turns as
 * `[{"text": "...", "type": "text"}]` and started writing that into new answers.
 */
@Serializable
internal data class ChatRequestMessage(
    val role: String,
    val content: JsonElement
)

/** Builds a request message; null when there is nothing to send (no text and no images). */
internal fun chatRequestMessage(
    role: String,
    text: String,
    imageDataUris: List<String> = emptyList()
): ChatRequestMessage? {
    if (imageDataUris.isEmpty()) {
        return if (text.isBlank()) null else ChatRequestMessage(role, JsonPrimitive(text))
    }
    val parts = buildList {
        if (text.isNotBlank()) add(ChatContentPart.text(text))
        imageDataUris.forEach { add(ChatContentPart.image(it)) }
    }
    return ChatRequestMessage(
        role,
        JsonArray(parts.map { ChatJson.encodeToJsonElement(ChatContentPart.serializer(), it) })
    )
}

@Serializable
internal data class ChatContentPart(
    val type: String,
    val text: String? = null,
    @SerialName("image_url") val imageUrl: ChatImageUrl? = null
) {
    companion object {
        fun text(value: String) = ChatContentPart(type = "text", text = value)

        /** [dataUri] must be a full "data:image/jpeg;base64,..." URI per the docs. */
        fun image(dataUri: String) = ChatContentPart(
            type = "image_url",
            imageUrl = ChatImageUrl(url = dataUri)
        )
    }
}

@Serializable
internal data class ChatImageUrl(val url: String)

@Serializable
internal data class ChatResponse(
    val id: String? = null,
    val choices: List<ChatChoice> = emptyList(),
    val usage: ChatUsage? = null
) {
    fun text(): String =
        choices.firstOrNull()?.message?.content.orEmpty().trim()
}

@Serializable
internal data class ChatChoice(
    val index: Int = 0,
    val message: ChatResponseMessage? = null,
    val finishReason: String? = null
)

@Serializable
internal data class ChatResponseMessage(
    val role: String? = null,
    val content: String? = null
)

@Serializable
internal data class ChatUsage(
    @SerialName("prompt_tokens") val promptTokens: Int = 0,
    @SerialName("completion_tokens") val completionTokens: Int = 0,
    @SerialName("total_tokens") val totalTokens: Int = 0
) {
    fun toTokenUsage() = TokenUsage(promptTokens = promptTokens, completionTokens = completionTokens)
}

/**
 * Streaming chunk: `choices[0].delta.content` carries the incremental text; with
 * `stream_options.include_usage` the final chunk carries [usage] instead.
 */
@Serializable
internal data class ChatStreamChunk(
    val choices: List<ChatStreamChoice> = emptyList(),
    val usage: ChatUsage? = null
)

@Serializable
internal data class ChatStreamChoice(
    val index: Int = 0,
    val delta: ChatStreamDelta? = null,
    val finishReason: String? = null
)

@Serializable
internal data class ChatStreamDelta(
    val role: String? = null,
    val content: String? = null,
    /** DeepSeek-style thinking stream. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    /** OpenRouter-style thinking stream. */
    val reasoning: String? = null
)

@Serializable
internal data class ChatErrorEnvelope(val error: ChatErrorBody? = null, val message: String? = null)

@Serializable
internal data class ChatErrorBody(val message: String? = null, val type: String? = null, val code: String? = null)

@Serializable
internal data class ModelsResponse(val data: List<ModelInfo> = emptyList())

@Serializable
internal data class ModelInfo(val id: String, val ownedBy: String? = null)

internal val ChatJson: Json = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    encodeDefaults = true
    isLenient = true
}
