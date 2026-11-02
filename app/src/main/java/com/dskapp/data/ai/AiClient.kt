package com.dskapp.data.ai

import kotlinx.coroutines.flow.Flow

/**
 * The single seam between the app and whatever runs the model.
 *
 * [OpenAiCompatibleClient] targets any OpenAI-compatible /chat/completions server
 * (River AI by default: https://api.river.ai/v1). Because base URL, key and model all come
 * from user settings, moving to a relay server later only needs a new implementation bound
 * in `com.dskapp.di.ServiceLocator`.
 *
 * [model] overrides the Settings model for one call (per-thread model choice).
 */
interface AiClient {

    /** Non-streaming call. Returns the full answer once the model finishes. */
    suspend fun complete(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        model: String? = null
    ): AiResult

    /**
     * Streaming call. Emits incremental text deltas through [onText] and reports problems
     * through [onError] so callers can keep partial text on screen. [onUsage] fires once at
     * the end when the server reports token counts. [onReasoning] receives the model's
     * thinking when the server streams it separately from the answer.
     */
    fun stream(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        model: String? = null,
        onText: (String) -> Unit,
        onError: (AiError) -> Unit,
        onUsage: (TokenUsage) -> Unit = {},
        onReasoning: (String) -> Unit = {}
    ): Flow<Unit>

    /** Lists model IDs from GET /models; empty when the server does not support it. */
    suspend fun listModels(): Result<List<String>>

    companion object {
        const val DEFAULT_MAX_TOKENS = 8192
    }
}
