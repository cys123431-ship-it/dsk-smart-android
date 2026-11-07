package com.dskapp.data.ai

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Talks to any OpenAI-compatible /chat/completions endpoint.
 *
 * Verified against the River AI docs:
 *  - base URL  https://api.river.ai/v1
 *  - auth      Authorization: Bearer rv_...
 *  - streaming SSE terminated by `data: [DONE]`
 *  - images    content part {"type":"image_url","image_url":{"url":"data:image/jpeg;base64,..."}}
 *
 * [configProvider] is read on every call, so editing the base URL / key / model in Settings
 * takes effect immediately without rebuilding anything. [usageRecorder] sees the token
 * counts of every call (answers, titles, summaries) for the monthly usage total.
 */
class OpenAiCompatibleClient(
    private val client: OkHttpClient,
    private val configProvider: () -> ProviderConfig,
    private val usageRecorder: (TokenUsage) -> Unit = {}
) : AiClient {

    /**
     * Set once a server rejects `stream_options` (400). Usage is then simply unknown for
     * streamed answers instead of every request failing.
     */
    @Volatile
    private var streamUsageUnsupported = false

    override suspend fun complete(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int,
        model: String?
    ): AiResult = withContext(Dispatchers.IO) {
        val config = configProvider()
        if (!config.isComplete) return@withContext AiResult.Failure(AiError.NotConfigured)

        val request = chatRequest(
            config,
            buildRequest(config, model, systemPrompt, messages, maxTokens, stream = false, includeUsage = false),
            accept = "application/json"
        )

        try {
            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                if (response.isSuccessful) return@withContext parseCompleteResponse(payload)

                // Reasoning models (DeepSeek / Kimi / GLM) can spend the whole max_tokens
                // budget on hidden thinking and fail with "no room for content". Asking the
                // provider to skip thinking makes a retry succeed. One retry only.
                if (isThinkingBudgetFailure(response.code, payload)) {
                    val retry = chatRequest(
                        config,
                        buildRequest(
                            config, model, systemPrompt, messages, maxTokens,
                            stream = false, includeUsage = false, disableThinking = true
                        ),
                        accept = "application/json"
                    )
                    client.newCall(retry).execute().use { second ->
                        val secondPayload = second.body?.string().orEmpty()
                        if (second.isSuccessful) return@withContext parseCompleteResponse(secondPayload)
                    }
                }
                AiResult.Failure(parseError(response.code, payload))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            AiResult.Failure(AiError.Network(e.message ?: "연결 실패"))
        } catch (e: Exception) {
            AiResult.Failure(AiError.Unexpected(e.message ?: e::class.simpleName ?: "알 수 없는 오류"))
        }
    }

    /**
     * Decodes a non-streaming 200 response. A reply with no visible text is reported with
     * the real cause (thinking-only reply) instead of a bare "응답이 비어 있습니다",
     * which is what made "연결 테스트" look broken while normal chat kept working.
     */
    private fun parseCompleteResponse(payload: String): AiResult {
        val parsed = runCatching {
            ChatJson.decodeFromString(ChatResponse.serializer(), payload)
        }.getOrElse { error ->
            return AiResult.Failure(
                AiError.Unexpected("AI 응답 형식을 해석하지 못했습니다: ${error.message ?: "unknown"}")
            )
        }
        val usage = parsed.usage?.toTokenUsage()?.also(usageRecorder)
        val text = parsed.text()
        if (text.isNotBlank()) return AiResult.Success(text, usage)

        val thinkingOnly = parsed.thinkingText().isNotBlank()
        return AiResult.Failure(
            AiError.Unexpected(
                if (thinkingOnly) {
                    "AI 모델이 생각만 하고 답을 내지 않았습니다. " +
                        "응답 길이(max_tokens)가 추론 토큰으로 모두 쓰였을 수 있습니다."
                } else {
                    "AI 모델이 빈 응답을 보냈습니다. 모델 이름과 서버 응답을 확인해 주세요."
                }
            )
        )
    }

    /** Narrow check: only retry when the provider body blames the token budget or thinking. */
    private fun isThinkingBudgetFailure(status: Int, payload: String): Boolean {
        if (status != 400 && status != 422) return false
        val lower = payload.lowercase()
        return lower.contains("max_tokens") || lower.contains("finish_reason") ||
            lower.contains("reasoning") || lower.contains("thinking")
    }

    override suspend fun listModels(): Result<List<String>> = withContext(Dispatchers.IO) {
        runCatching {
            val config = configProvider()
            require(config.baseUrl.isNotBlank() && config.apiKey.isNotBlank()) { "설정이 필요합니다." }

            val request = Request.Builder()
                .url(config.endpoint("models"))
                .addHeader("Authorization", "Bearer ${config.apiKey}")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                val payload = response.body?.string().orEmpty()
                require(response.isSuccessful) {
                    "모델 목록을 가져오지 못했습니다 (${response.code})"
                }
                ChatJson.decodeFromString(ModelsResponse.serializer(), payload)
                    .data.map { it.id }
                    .sorted()
            }
        }
    }

    override fun stream(
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int,
        model: String?,
        onText: (String) -> Unit,
        onError: (AiError) -> Unit,
        onUsage: (TokenUsage) -> Unit,
        onReasoning: (String) -> Unit
    ): Flow<Unit> = flow<Unit> {
        val config = configProvider()
        if (!config.isComplete) {
            onError(AiError.NotConfigured)
            return@flow
        }

        fun newCall(includeUsage: Boolean): Call = client.newCall(
            chatRequest(
                config,
                buildRequest(config, model, systemPrompt, messages, maxTokens, stream = true, includeUsage),
                accept = "text/event-stream"
            )
        )

        val reportUsage: (TokenUsage) -> Unit = { usage ->
            usageRecorder(usage)
            onUsage(usage)
        }

        // The read loop below blocks on the socket, which coroutine cancellation cannot
        // interrupt. A sibling guard coroutine wakes up on cancellation and cancels the OkHttp
        // call, closing the socket, so "stop" really stops the download instead of silently
        // reading (and paying for) the rest of the answer.
        val includeUsage = !streamUsageUnsupported
        val current = AtomicReference(newCall(includeUsage))
        coroutineScope {
            val guard = launch {
                try {
                    awaitCancellation()
                } finally {
                    current.get().cancel()
                }
            }
            try {
                val retry = readStream(current.get(), includeUsage, onText, onError, reportUsage, onReasoning)
                if (retry) {
                    streamUsageUnsupported = true
                    current.set(newCall(includeUsage = false))
                    currentCoroutineContext().ensureActive()
                    readStream(current.get(), includeUsage = false, onText, onError, reportUsage, onReasoning)
                }
            } finally {
                guard.cancel()
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Reads one SSE response. Returns true only when the server rejected `stream_options`
     * and the caller should retry without it; every other outcome is reported via callbacks.
     */
    private suspend fun readStream(
        call: Call,
        includeUsage: Boolean,
        onText: (String) -> Unit,
        onError: (AiError) -> Unit,
        onUsage: (TokenUsage) -> Unit,
        onReasoning: (String) -> Unit
    ): Boolean {
        try {
            call.execute().use { okResponse ->
                if (!okResponse.isSuccessful) {
                    val payload = okResponse.body?.string().orEmpty()
                    // Only a rejection of stream_options justifies the retry; any other 400
                    // (bad model id, bad payload) must still surface its own message.
                    if (includeUsage && okResponse.code == 400 &&
                        payload.contains("stream_options", ignoreCase = true)
                    ) {
                        return true
                    }
                    onError(parseError(okResponse.code, payload))
                    return false
                }

                val source = okResponse.body?.source() ?: return false
                while (!source.exhausted()) {
                    // No further deltas once the user pressed stop.
                    currentCoroutineContext().ensureActive()
                    val line = source.readUtf8Line() ?: break
                    if (!line.startsWith("data:")) continue

                    val payload = line.removePrefix("data:").trim()
                    if (payload.isEmpty()) continue
                    if (payload == DONE_SENTINEL) break

                    val chunk = runCatching {
                        ChatJson.decodeFromString(ChatStreamChunk.serializer(), payload)
                    }.getOrNull() ?: continue

                    val delta = chunk.choices.firstOrNull()?.delta
                    (delta?.reasoningContent ?: delta?.thinking ?: delta?.reasoning)?.let { thought ->
                        if (thought.isNotEmpty()) onReasoning(thought)
                    }
                    delta?.content?.let { text ->
                        if (text.isNotEmpty()) onText(text)
                    }
                    // With include_usage the last chunk has empty choices and the totals.
                    chunk.usage?.let { onUsage(it.toTokenUsage()) }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            // A cancelled call fails with "Canceled"/"Socket closed": that is the user's stop,
            // not a network error worth reporting.
            if (!call.isCanceled()) onError(AiError.Network(e.message ?: "연결 실패"))
        } catch (e: Exception) {
            onError(AiError.Unexpected(e.message ?: "알 수 없는 오류"))
        }
        return false
    }

    override fun testHttpClient(): OkHttpClient = client

    private fun chatRequest(config: ProviderConfig, body: ChatRequest, accept: String): Request =
        Request.Builder()
            .url(config.endpoint("chat/completions"))
            .addHeader("Authorization", "Bearer ${config.apiKey}")
            .addHeader("Content-Type", "application/json")
            .addHeader("Accept", accept)
            .post(ChatJson.encodeToString(ChatRequest.serializer(), body).toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun buildRequest(
        config: ProviderConfig,
        model: String?,
        systemPrompt: String,
        messages: List<AiMessage>,
        maxTokens: Int,
        stream: Boolean,
        includeUsage: Boolean,
        disableThinking: Boolean = false
    ) = ChatRequest(
        model = model?.takeIf { it.isNotBlank() } ?: config.model,
        messages = buildList {
            // "no_think" is the Qwen/GLM convention; harmless for models that ignore it and
            // the cheapest way to keep a one-word answer (connection test, title) short.
            val system = if (disableThinking) systemPrompt + "\n\n/no_think" else systemPrompt
            chatRequestMessage("system", system)?.let(::add)
            messages.forEach { message ->
                val role = when (message.role) {
                    AiRole.USER -> "user"
                    AiRole.ASSISTANT -> "assistant"
                }
                chatRequestMessage(role, message.text, message.images.map { it.toDataUri() })?.let(::add)
            }
        },
        maxTokens = maxTokens,
        stream = stream,
        streamOptions = if (stream && includeUsage) StreamOptions(includeUsage = true) else null,
        chatTemplateKwargs = if (disableThinking) {
            kotlinx.serialization.json.buildJsonObject {
                put("enable_thinking", kotlinx.serialization.json.JsonPrimitive(false))
                put("thinking", kotlinx.serialization.json.JsonPrimitive(false))
            }
        } else {
            null
        }
    )

    /** Maps an HTTP failure to a typed [AiError], preferring the provider's own message. */
    private fun parseError(status: Int, payload: String): AiError {
        val providerMessage = runCatching {
            val envelope = ChatJson.decodeFromString(ChatErrorEnvelope.serializer(), payload)
            envelope.error?.message ?: envelope.message
        }.getOrNull()

        return when (status) {
            401, 403 -> AiError.Unauthorized(providerMessage ?: "인증 실패")
            429 -> AiError.RateLimited(providerMessage ?: "요청 한도 초과")
            in 500..599 -> AiError.Provider(status, providerMessage ?: "서버 오류")
            else -> AiError.Provider(status, providerMessage ?: "요청 실패")
        }
    }

    companion object {
        /** River AI default; the user can change this in Settings. */
        const val DEFAULT_BASE_URL = "https://api.river.ai/v1"
        const val DEFAULT_MODEL = "deepseek-ai/DeepSeek-V4.1-Flash"

        /** Suggested models from the River docs; Settings also loads the live list. */
        val SUGGESTED_MODELS = listOf(
            "deepseek-ai/DeepSeek-V4.1-Flash",
            "deepseek-ai/DeepSeek-V4.1-Flash-1017",
            "moonshotai/Kimi-K3",
            "zai-org/GLM-5.3-Flash",
            "GLM-5.3",
            "Qwen/Qwen3.8-27B-FP8",
            "nvidia/NVIDIA-Nemotron-3.5-Super-VL-120B-A12B-NVFP4"
        )

        private const val DONE_SENTINEL = "[DONE]"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        /** Long reads are normal for documents and images, so timeouts are generous. */
        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS)
            .writeTimeout(120, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .callTimeout(600, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}
