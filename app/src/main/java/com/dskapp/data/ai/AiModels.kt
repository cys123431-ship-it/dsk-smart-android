package com.dskapp.data.ai

import kotlinx.serialization.Serializable

/**
 * A single attached image. [base64Data] is raw base64 (no data: prefix); the client adds
 * the `data:<mime>;base64,` prefix required by the OpenAI-compatible image_url part.
 */
@Serializable
data class AiImage(
    val mimeType: String,
    val base64Data: String
) {
    fun toDataUri(): String = "data:$mimeType;base64,$base64Data"
}

/** Chat turn. Provider-agnostic so the endpoint/transport can be swapped freely. */
@Serializable
data class AiMessage(
    val role: AiRole,
    val text: String,
    val images: List<AiImage> = emptyList()
)

@Serializable
enum class AiRole { USER, ASSISTANT }

/** Every AI call returns one of these, so the UI never deals with raw exceptions. */
sealed interface AiResult {
    data class Success(val text: String, val usage: TokenUsage? = null) : AiResult
    data class Failure(val error: AiError) : AiResult
}

/** Token counts reported by the server for one call. */
data class TokenUsage(val promptTokens: Int, val completionTokens: Int) {
    /** Totals across several calls for one answer (web search adds rounds). */
    operator fun plus(other: TokenUsage) =
        TokenUsage(promptTokens + other.promptTokens, completionTokens + other.completionTokens)
}

/**
 * Configuration of the OpenAI-compatible endpoint. All three values are user-supplied in
 * Settings, so the app works with River (https://api.river.ai/v1) or any other
 * OpenAI-compatible server (self-hosted vLLM, Ollama, OpenRouter, ...).
 */
data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String,
    val model: String
) {
    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && apiKey.isNotBlank() && model.isNotBlank()

    /** Joins base URL and endpoint path without producing a double slash. */
    fun endpoint(path: String): String =
        baseUrl.trimEnd('/') + "/" + path.trimStart('/')
}

sealed interface AiError {
    /** Base URL / key / model not configured yet - route the user to Settings. */
    data object NotConfigured : AiError

    data class Unauthorized(val message: String) : AiError
    data class RateLimited(val message: String) : AiError
    data class Network(val message: String) : AiError
    data class Provider(val status: Int, val message: String) : AiError

    /**
     * The server does not serve this model (any more). Providers change their lists without
     * notice; the raw 404 used to put 100+ model names into one snackbar.
     */
    data class ModelUnavailable(val model: String) : AiError
    data class Unexpected(val message: String) : AiError
}

val AiError.userMessage: String
    get() = when (this) {
        AiError.NotConfigured -> "서버 주소, API 키, 모델을 설정에서 먼저 입력해 주세요."
        is AiError.Unauthorized -> "API 키가 유효하지 않습니다. 설정에서 확인해 주세요."
        is AiError.RateLimited -> "요청이 많습니다. 잠시 후 다시 시도해 주세요."
        is AiError.Network -> "서버에 연결할 수 없습니다. 주소와 네트워크를 확인해 주세요."
        // Long provider texts (stack traces, model lists) are cut so a snackbar stays readable.
        is AiError.Provider -> "AI 서버 오류($status): ${message.take(200)}"
        is AiError.ModelUnavailable ->
            "모델 '${model.ifBlank { "선택한 모델" }}'을(를) 이 AI 서버에서 쓸 수 없습니다 " +
                "(제공업체 목록에서 빠졌을 수 있습니다). 설정 > 모델 목록을 눌러 다른 모델을 골라 주세요."
        is AiError.Unexpected -> "처리 중 문제가 발생했습니다: $message"
    }
