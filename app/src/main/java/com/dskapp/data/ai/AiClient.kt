package com.dskapp.data.ai

import kotlinx.coroutines.flow.Flow

/**
 * The single seam between the app and whatever runs the model.
 *
 * [OpenAiCompatibleClient] targets any OpenAI-compatible /chat/completions server
 * (River AI by default: https://api.river.ai/v1). Because base URL, key and model all come
 * from user settings, moving to a relay server later only needs a new implementation bound
 * in `com.dskapp.di.ServiceLocator`.
 */
interface AiClient {

    /** Non-streaming call. Returns the full answer once the model finishes. */
    suspend fun complete(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): AiResult

    /**
     * Streaming call. Emits incremental text deltas through [onText] and reports problems
     * through [onError] so callers can keep partial text on screen.
     */
    fun stream(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int = DEFAULT_MAX_TOKENS,
        onText: (String) -> Unit,
        onError: (AiError) -> Unit
    ): Flow<Unit>

    /** Lists model IDs from GET /models; empty when the server does not support it. */
    suspend fun listModels(): Result<List<String>>

    companion object {
        const val DEFAULT_MAX_TOKENS = 8192
    }
}

